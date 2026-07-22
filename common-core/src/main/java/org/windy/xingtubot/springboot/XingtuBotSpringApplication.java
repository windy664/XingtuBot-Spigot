package org.windy.xingtubot.springboot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.windy.xingtubot.common.bot.BotLauncher;
import org.windy.xingtubot.common.bot.QQOnboard;
import org.windy.xingtubot.common.bridge.CrossServerChannel;
import org.windy.xingtubot.common.bridge.CrossServerChannelFactory;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.poll.JsonOpenidNameRepository;
import org.windy.xingtubot.common.poll.OpenidNameCache;
import org.windy.xingtubot.common.poll.OpenidNameRepository;
import org.windy.xingtubot.common.poll.QQGatewayClient;
import org.windy.xingtubot.common.poll.QqBot;
import org.windy.xingtubot.common.queue.KnownGroupStore;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.runtime.BotRuntimeState;
import org.windy.xingtubot.common.service.SensitiveFilter;
import org.windy.xingtubot.common.util.Texts;

import java.io.File;
import java.util.function.Consumer;

/**
 * 昕途机器人 · Spring Boot 独立运行载体。
 *
 * <p>可独立运行（不依赖 Minecraft 服务器），通过 QQ 官方 WebSocket 网关
 * 连接 QQ 机器人，支持所有 xt-* 扩展插件。
 *
 * <h3>启动方式</h3>
 * <pre>
 * # 直接启动（使用默认配置）
 * java -jar XingtuBot-SpringBoot.jar
 *
 * # 指定数据目录
 * java -jar XingtuBot-SpringBoot.jar --xingtubot.data-dir=./data
 *
 * # 加载扩展插件（xt-*.jar 放在 libs/ 目录）
 * java -Dloader.path=libs/ -jar XingtuBot-SpringBoot.jar
 * </pre>
 *
 * <h3>扩展插件加载</h3>
 * <p>xt-* 扩展 jar 放入 {@code libs/} 目录，启动时自动加入 classpath。
 * 扩展通过 {@code ServiceLoader<BotModule>} 或 push 模型注册到核心。
 *
 * <h3>跨服互联</h3>
 * <p>通过 Redis 与游戏端的 XingtuBot 互通（跨服信道已内置）。
 */
@SpringBootApplication
public class XingtuBotSpringApplication {

    private static final Logger log = LoggerFactory.getLogger(XingtuBotSpringApplication.class);

    private QqBot qqBot;
    private QQGatewayClient gatewayClient;
    private SpringCommandHandler commandHandler;
    private CrossServerChannelFactory.Holder redisHolder;
    private SpringBootBotLogger logger;

    @Value("${xingtubot.data-dir:./data}")
    private String dataDirPath;

    @org.springframework.beans.factory.annotation.Autowired
    ApplicationContext appCtx;

    public static void main(String[] args) {
        // 使用 PropertiesLauncher 以支持 -Dloader.path=libs/ 加载扩展 jar
        System.setProperty("loader.path", System.getProperty("loader.path", "libs/"));
        SpringApplication.run(XingtuBotSpringApplication.class, args);
    }

    // ==================== 启动 ====================

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
    try {
        File dataFolder = new File(dataDirPath);
        dataFolder.mkdirs();
        BotConfig config = new SpringBootConfig(dataFolder);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("xingtu-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        SpringBootAdapter adapter = new SpringBootAdapter(scheduler);
        logger = new SpringBootBotLogger("XingtuBot");

        // ===== 初始化基础设施 =====
        BotRuntimeState.bindDebug(() -> config.getBoolean("debug", false));
        PendingMessageQueue.getInstance().init(dataFolder);
        KnownGroupStore.getInstance().init(dataFolder);
        initOpenidNameCache(dataFolder);

        // ===== 打印 Banner =====
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        for (String line : Texts.banner(version, "Spring Boot", "/xtb help")) {
            logger.info(line);
        }

        // ===== 命令中心（含 XingtuBotHost 服务总线）=====
        commandHandler = new SpringCommandHandler(config, logger, dataFolder, appCtx);
        // Spring Boot 独立运行 = 大脑模式
        commandHandler.getHost().setBrain(true);
        commandHandler.getHost().registerService("core.allowed-groups",
                (java.util.function.Supplier<java.util.List<String>>) () -> config.getStringList("allowed-groups"));

        // 敏感词过滤
        SensitiveFilter sf = SensitiveFilter.fromConfig(config, "sensitive-filter", logger);
        commandHandler.getHost().registerService(SensitiveFilter.class, sf);

        // 注册到 Spring 容器，供扩展插件注入
        registerBeans(appCtx, commandHandler);

        // ===== 跨服 Redis 信道 =====
        redisHolder = CrossServerChannelFactory.create(config, true, logger);
        if (redisHolder != null) {
            commandHandler.getHost().registerService(CrossServerChannel.class, redisHolder.channel);
            logger.info("✅ Redis 跨服信道已连接");
        }

        // ===== 启动 QQ Bot =====
        startBot(config, adapter, logger);

        // ===== 配置摘要 =====
        if (BotRuntimeState.isDebugEnabled()) {
            printDebugInfo(config, logger);
        }

        logger.info("✅ 昕途机器人 Spring Boot 载体已就绪");
    } catch (Throwable t) {
        log.error("启动失败", t);
        System.exit(1);
    }
    }

    // ==================== 关闭 ====================

    @EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void onShutdown() {
        if (commandHandler != null) commandHandler.shutdown();
        if (gatewayClient != null) gatewayClient.stop();
        if (redisHolder != null) redisHolder.close();
        OpenidNameCache.getInstance().shutdown();
        logger.info("昕途机器人已关闭");
    }

    // ==================== 内部方法 ====================

    private void startBot(BotConfig config, SpringBootAdapter adapter, SpringBootBotLogger logger) {
        String appId = config.getString("openapi-app-id", "").trim();
        if (appId.isEmpty()) {
            logger.info("未配置 openapi-app-id，启动扫码接入流程...");
            QQOnboard onboard = new QQOnboard(logger);
            QQOnboard.ScanResult result = onboard.run();
            if (result != null) {
                if (config instanceof SpringBootConfig) {
                    SpringBootConfig sbc = (SpringBootConfig) config;
                    sbc.set("openapi-app-id", result.appId);
                    sbc.set("openapi-client-secret", result.clientSecret);
                    sbc.save();
                    logger.info("✅ 凭据已写入 config.yml，App ID: " + result.appId);
                }
                return;
            } else {
                logger.warn("扫码接入失败或超时，请手动填写 openapi-app-id 和 openapi-client-secret");
                return;
            }
        }

        Consumer<BotMessageEvent> listener = event -> {
            if (commandHandler != null) {
                commandHandler.handle(event);
            }
        };

        BotLauncher.GatewayResult gw = BotLauncher.buildGateway(
                config, adapter, logger, listener);
        if (gw != null) {
            qqBot = gw.bot;
            gatewayClient = gw.gatewayClient;
            gatewayClient.setOnBotNameResolved(name ->
                    logger.info("✅ 机器人昵称已自动获取: " + name));
            gatewayClient.start();
            logger.info("✅ 通信模式 = gateway（QQ 官方 WebSocket 网关）已启动");

            org.windy.xingtubot.common.qq.QqOpenApiClient apiClient = qqBot.getApi();
            if (apiClient != null) {
                commandHandler.getProactiveSender().bind(apiClient);
                if (commandHandler.getService() != null) {
                    commandHandler.getService().setApiClient(apiClient);
                }
                logger.info("✅ 主动消息已启用");
            }
        } else {
            logger.warn("Gateway 模式配置不全，请检查 openapi-app-id / openapi-client-secret");
        }
    }

    private void initOpenidNameCache(File dataFolder) {
        OpenidNameRepository repo = new JsonOpenidNameRepository(
                new File(dataFolder, "openid_names.json"),
                msg -> log.info(msg));
        OpenidNameCache.getInstance().init(repo);
    }

    /**
     * 把核心服务注册为 Spring Bean，供扩展插件通过 @Autowired 注入。
     */
    private void registerBeans(ApplicationContext appCtx, SpringCommandHandler handler) {
        if (appCtx instanceof org.springframework.beans.factory.support.DefaultListableBeanFactory) {
            org.springframework.beans.factory.support.DefaultListableBeanFactory beanFactory =
                    (org.springframework.beans.factory.support.DefaultListableBeanFactory) appCtx;
            beanFactory.registerSingleton("xingtuBotHost", handler.getHost());
            beanFactory.registerSingleton("xingtuBotService", handler.getService());
            beanFactory.registerSingleton("handlerRegistry", handler.getRegistry());
            beanFactory.registerSingleton("proactiveSender", handler.getProactiveSender());
        }
    }

    private void printDebugInfo(BotConfig config, SpringBootBotLogger logger) {
        String appId = config.getString("openapi-app-id", "");
        String masked = appId.length() > 4 ? appId.substring(0, 4) + "****" : "未配置";
        logger.info("▌ 角色     大脑（独立运行）");
        logger.info("▌ AppID    " + masked);
        logger.info("▌ 监听     mention");
        logger.info("▌ 跨服     已启用");
        logger.info("▌ 存储     JSON");
    }
}
