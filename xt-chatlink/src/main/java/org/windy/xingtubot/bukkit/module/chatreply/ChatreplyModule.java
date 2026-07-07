package org.windy.xingtubot.bukkit.module.chatreply;

import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.module.chatreply.listener.GameChatForwarder;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.lock.LockState;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.service.SensitiveFilter;

/**
 * 群服互联 Bukkit 侧模块：游戏聊天 → QQ 群转发 + 敏感词过滤。
 *
 * <p>不直接引用主插件类（XingtuBot/SpigotConfig），所有依赖通过构造参数注入。
 */
public class ChatreplyModule implements Listener {

    private static ChatreplyModule instance;
    private final JavaPlugin plugin;
    private final BotConfig config;
    private final BotLogger logger;
    private static SensitiveFilter sensitiveFilter;
    private final GameChatForwarder gameChatForwarder;
    private final ReplyCommand replyCommand;

    public ChatreplyModule(JavaPlugin plugin, BotConfig config, BotLogger logger, LockState lockState) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        instance = this;

        // 初始化敏感词过滤器
        sensitiveFilter = SensitiveFilter.fromConfig(config, logger);

        // 游戏内聊天 → QQ 群转发
        gameChatForwarder = new GameChatForwarder(lockState);
        Bukkit.getPluginManager().registerEvents(gameChatForwarder, plugin);

        // 游戏内 [点击回复] → 主动回 QQ 群
        replyCommand = new ReplyCommand();
        plugin.getCommand("messagereply").setExecutor(replyCommand);

        plugin.getLogger().info("群服回复模块已启用（双向互联）");
        sensitiveFilter.reloadCloudWords();
    }

    /** 注入主动发送器「供给器」（每次发送时现取）：游戏→QQ 转发 + [点击回复] 共用。 */
    public void setProactiveSender(java.util.function.Supplier<org.windy.xingtubot.common.module.capability.ProactiveSender> senderSupplier) {
        gameChatForwarder.setProactiveSender(senderSupplier);
        replyCommand.setProactiveSender(senderSupplier);
    }

    /** 兼容旧调用（无 LockState）。 */
    public ChatreplyModule(JavaPlugin plugin, BotConfig config, BotLogger logger) {
        this(plugin, config, logger, null);
    }

    public static ChatreplyModule getInstance() {
        return instance;
    }

    public static SensitiveFilter getSensitiveFilter() {
        return sensitiveFilter;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public BotConfig getConfig() {
        return config;
    }

    public GameChatForwarder getGameChatForwarder() {
        return gameChatForwarder;
    }

    /** 热重载可变配置（/xtb reload 调用）：重建敏感词过滤器。 */
    public void reload() {
        sensitiveFilter = SensitiveFilter.fromConfig(config, logger);
        sensitiveFilter.reloadCloudWords();
    }
}
