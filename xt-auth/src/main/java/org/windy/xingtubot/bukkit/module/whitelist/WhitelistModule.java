package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.windy.xingtubot.bukkit.SpigotConfig;
import org.windy.xingtubot.bukkit.XingtuBot;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.common.api.XingtuBotService;
import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.ext.xtauth.binding.BindingServiceImpl;
import org.windy.xingtubot.common.binding.BindingStorageFactory;
import org.windy.xingtubot.common.binding.AutoLoginRepository;
import org.windy.xingtubot.common.binding.AutoLoginStorageFactory;
import org.windy.xingtubot.common.whitelist.LockMessages;
import org.windy.xingtubot.common.whitelist.LockPrompt;

import java.util.List;

/**
 * 白名单绑定模块（packetevents 包级锁，无 AuthMe）。
 *
 * <p>本地模式（单机 Spigot 即大脑）。流程：
 * <ul>
 *   <li>进服 → packetevents 锁定（包级拦截，玩家不能移动/交互/聊天）。</li>
 *   <li>未绑定玩家聊天框输 QQ 号 → packetevents 读取 → declareQQ。</li>
 *   <li>玩家在群里发送<b>「绑定」</b>→ 取发送者头像与登记记录比对，命中即绑定 + 解锁。</li>
 *   <li>群里发「登录」→ 校验绑定 + 解锁。</li>
 * </ul>
 */
public class WhitelistModule implements Listener {

    private final XingtuBot plugin;
    private final BindingService service;
    private volatile SpigotConfig spigotConfig;

    // ===== IP 绑定的自动登录信任期（本地大脑版，对标 VelocityBridge）=====
    private final java.util.Map<String, AutoLoginEntry> autoLoginMem = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile AutoLoginRepository autoLoginRepo;
    private volatile long autoLoginWindowMillis = 0L;

    private static final long REMINDER_INTERVAL = 20L * 8; // 8 秒（ticks）

    private static WhitelistModule instance;

    // packetevents 登录锁
    private final BukkitPlayerLock bukkitLock;

    public WhitelistModule(XingtuBot plugin) {
        this.plugin = plugin;
        this.spigotConfig = new SpigotConfig(plugin.getConfig());
        instance = this;

        BindingRepository store = BindingStorageFactory.create(
                new SpigotConfig(plugin.getConfig()), true, plugin.getDataFolder(),
                msg -> plugin.getLogger().warning(msg));

        // AppID 惰性解析
        java.util.function.Supplier<String> appIdSupplier = () -> {
            XingtuBotService s = Bukkit.getServicesManager().load(
                    org.windy.xingtubot.common.api.XingtuBotService.class);
            return s != null ? s.getBotAppId() : "";
        };
        if (appIdSupplier.get() == null || appIdSupplier.get().isEmpty()) {
            plugin.getLogger().warning("⚠️ 启动时 openapi-app-id 暂为空（核心可能还没起好）——已改惰性获取，"
                    + "绑定时会重取；若届时仍为空，请在 config 配置 openapi-app-id。");
        }

        // ===== packetevents 登录锁 =====
        // 先创建 lockManager（BindingService 后补），再创建 AuthAdapter
        BukkitPlayerOps bukkitOps = new BukkitPlayerOps(plugin);
        bukkitLock = new BukkitPlayerLock(plugin, null);
        BukkitDirectAuthAdapter authAdapter = new BukkitDirectAuthAdapter(bukkitLock, bukkitOps);

        this.service = new BindingServiceImpl(store, authAdapter, appIdSupplier,
                msg -> plugin.getLogger().warning(msg));
        this.service.setBindingPrompt(plugin.getConfig().getString("binding-prompt", "绑定"));
        this.service.setMaxBindAttempts(plugin.getConfig().getInt("bind-max-attempts", 5));
        this.service.setGroupNumber(plugin.getConfig().getString("qq-group", ""));

        // 补全 lockManager 的 BindingService 引用
        bukkitLock.setBindingService(service);

        // QQ 登记成功后（进入「去群里发绑定」阶段）→ 把加群二维码地图强制放到玩家手上
        bukkitLock.setOnCodeIssued(name -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p != null) JoinQrMap.giveMap(plugin, p);
        });

        // 自动登录信任期
        this.autoLoginWindowMillis = Math.max(0L,
                plugin.getConfig().getInt("auto-login-window-minutes", 720) * 60_000L);
        if (autoLoginWindowMillis > 0) {
            try {
                this.autoLoginRepo = AutoLoginStorageFactory.create(
                        new SpigotConfig(plugin.getConfig()), true, plugin.getDataFolder(),
                        msg -> plugin.getLogger().warning(msg));
            } catch (Exception e) {
                plugin.getLogger().warning("⚠️ 自动登录信任期仓库创建失败，退回内存（不跨重启）：" + e.getMessage());
            }
        }

        // 注册 packetevents 包拦截器（三端共享 LockPacketListener）
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                .registerListener(new org.windy.xingtubot.common.whitelist.LockPacketListener(bukkitLock));

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startReminderTask(plugin);

        plugin.getLogger().info("[白名单] packetevents 登录锁已启用（Bukkit 端包级拦截）");
    }

    public static WhitelistModule getInstance() {
        return instance;
    }

    public BindingRepository getBindingStore() {
        return service.getStore();
    }

    public BindingService getService() {
        return service;
    }

    public BukkitPlayerLock getBukkitLock() {
        return bukkitLock;
    }

    public void reload() {
        this.spigotConfig = new SpigotConfig(plugin.getConfig());
    }

    public List<String> getPlayersByFormId(String openid) {
        return service.getStore().getPlayersByOpenid(openid);
    }

    // ==================== 游戏内事件 ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        plugin.getLogger().info("[白名单] onPlayerJoin 触发: " + name + "，已锁定");
        bukkitLock.lock(name);

        if (service.isPlayerBound(name)) {
            // 同设备信任期内免密自动登录
            if (autoLoginAllowed(name, playerIp(player))) {
                service.markLoggedInSession(name);
                bukkitLock.unlock(name);
                JoinQrMap.cleanup(player);
                sendWelcomeTitle(player, LockMessages.get("welcome-auto-login"));
                player.sendMessage(LockMessages.get("auto-login-msg"));
                return;
            }
            String title = LockMessages.get("need-login-title")
                    .replace("{bot}", org.windy.xingtubot.common.api.BotIdentity.getName());
            sendWelcomeTitle(player, LockMessages.format("welcome-need-login", "{title}", title));
            JoinQrMap.giveMap(plugin, player); // 加群二维码地图（强制手持）
        } else if (service.hasPending(name)) {
            String bindWord = plugin.getConfig().getString("binding-prompt", "绑定");
            sendWelcomeTitle(player, LockMessages.format("welcome-need-bind", "{bind}", bindWord));
            JoinQrMap.giveMap(plugin, player); // 加群二维码地图（强制手持）
        } else {
            bukkitLock.lock(name);
            sendWelcomeTitle(player, LockMessages.get("welcome-await-qq"));
            player.sendMessage(LockMessages.get("await-qq-msg"));
        }
    }

    /** 进服欢迎 title：文案按首个 " · " 拆成主/副标题。 */
    private static void sendWelcomeTitle(Player player, String text) {
        String[] parts = LockPrompt.titleParts(text);
        player.sendTitle(parts[0], parts[1], 10, 60, 15);
    }

    // onPlayerChat 不再需要——聊天由共享 LockPacketListener 处理

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        String name = p.getName();
        bukkitLock.onDisconnect(name);
        // 退出前若本会话已登录，按当前 IP 武装自动登录信任期
        if (autoLoginWindowMillis > 0 && service.isLoggedInSession(name)) {
            String ip = playerIp(p);
            if (ip != null) autoLoginPut(name, ip, System.currentTimeMillis() + autoLoginWindowMillis);
        }
        service.clearSession(name);
    }

    // ==================== 群消息事件 ====================

    @EventHandler
    public void onGuildMessage(GuildMessageEvent event) {
        String message = event.getMessage() == null ? "" : event.getMessage().trim();
        String openid = event.getFormId();
        String loginPrompt = plugin.getConfig().getString("login-prompt", "登录");

        String bindingPrompt = plugin.getConfig().getString("binding-prompt", "绑定");
        if (loginPrompt.equals(message)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                    reply(event, service.loginByGroup(openid)));
        } else if (bindingPrompt.equals(message)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                    reply(event, service.bindByAvatar(openid)));
        }
    }

    private static void reply(GuildMessageEvent event, BindingService.Result r) {
        if (r.markdown) {
            event.replyMarkdown(r.message, null);
        } else {
            event.reply(r.message);
        }
    }

    // ==================== 定时提醒 ====================

    private void startReminderTask(Plugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> bukkitLock.tickReminder(),
                REMINDER_INTERVAL, REMINDER_INTERVAL);
    }

    // ==================== 自动登录信任期 ====================

    private void autoLoginPut(String player, String ip, long expiry) {
        AutoLoginRepository r = autoLoginRepo;
        if (r != null) r.put(player, ip, expiry);
        else autoLoginMem.put(player.toLowerCase(), new AutoLoginEntry(ip, expiry));
    }

    private AutoLoginEntry autoLoginGet(String player) {
        AutoLoginRepository r = autoLoginRepo;
        if (r != null) {
            AutoLoginRepository.Entry e = r.get(player);
            return e == null ? null : new AutoLoginEntry(e.ip, e.expiry);
        }
        return autoLoginMem.get(player.toLowerCase());
    }

    private void autoLoginRemove(String player) {
        AutoLoginRepository r = autoLoginRepo;
        if (r != null) r.remove(player);
        else autoLoginMem.remove(player.toLowerCase());
    }

    private boolean autoLoginAllowed(String player, String ip) {
        if (autoLoginWindowMillis <= 0 || ip == null) return false;
        AutoLoginEntry e = autoLoginGet(player);
        if (e == null) return false;
        if (System.currentTimeMillis() >= e.expiry) {
            autoLoginRemove(player);
            return false;
        }
        return ip.equals(e.ip);
    }

    private static String playerIp(Player p) {
        java.net.InetSocketAddress addr = p.getAddress();
        return addr != null && addr.getAddress() != null ? addr.getAddress().getHostAddress() : null;
    }

    private static final class AutoLoginEntry {
        final String ip;
        final long expiry;
        AutoLoginEntry(String ip, long expiry) {
            this.ip = ip;
            this.expiry = expiry;
        }
    }
}
