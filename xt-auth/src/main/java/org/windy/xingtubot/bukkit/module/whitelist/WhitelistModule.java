package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
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
import org.windy.xingtubot.common.lock.LockState;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 白名单绑定模块（自研锁版，无 AuthMe）。
 *
 * <p>本地模式（单机 Spigot 即大脑）。流程：
 * <ul>
 *   <li>进服 → 锁定。未绑定者提示输 QQ 号；已绑定者提示群里发「登录」。</li>
 *   <li>未绑定玩家聊天框输 QQ 号 → declareQQ，下载该 QQ 头像登记。</li>
 *   <li>玩家在群里发送<b>「绑定」</b>→ 取发送者头像与登记记录比对，命中即绑定 + 解锁。</li>
 *   <li>群里发「登录」→ 校验绑定 + 解锁。</li>
 * </ul>
 */
public class WhitelistModule implements Listener {

    private final XingtuBot plugin;
    private final BindingService service;
    private final LockState lockState = new LockState();
    // 未绑定、正等待游戏内输入 QQ 号的玩家
    private final Set<String> awaitingQQ = ConcurrentHashMap.newKeySet();
    private volatile SpigotConfig spigotConfig;

    // ===== IP 绑定的自动登录信任期（本地大脑版，对标 VelocityBridge）=====
    // 玩家登录后退出 → 按退出时 IP 记 (ip, 过期时刻)；同 IP 在窗口内重进 → 免密自动登录。
    // 持久化走 autoLoginRepo（json/sqlite/mysql，跨重启）；创建失败退回内存 Map（不跨重启）。
    private final java.util.Map<String, AutoLoginEntry> autoLoginMem = new ConcurrentHashMap<>();
    private volatile AutoLoginRepository autoLoginRepo;
    private volatile long autoLoginWindowMillis = 0L;

    private static final long REMINDER_INTERVAL = 20L * 8; // 8 秒（ticks）

    private static WhitelistModule instance;

    public WhitelistModule(XingtuBot plugin) {
        this.plugin = plugin;
        this.spigotConfig = new SpigotConfig(plugin.getConfig());
        instance = this;

        BindingRepository store = BindingStorageFactory.create(
                new SpigotConfig(plugin.getConfig()), true, plugin.getDataFolder(),
                msg -> plugin.getLogger().warning(msg));

        // AppID 惰性解析：每次绑定时现取，不在构造时一次性捕获（避免核心未就绪时空值固化 → 永久企鹅）。
        java.util.function.Supplier<String> appIdSupplier = () -> {
            XingtuBotService s = Bukkit.getServicesManager().load(
                    org.windy.xingtubot.common.api.XingtuBotService.class);
            return s != null ? s.getBotAppId() : "";
        };
        if (appIdSupplier.get() == null || appIdSupplier.get().isEmpty()) {
            plugin.getLogger().warning("⚠️ 启动时 openapi-app-id 暂为空（核心可能还没起好）——已改惰性获取，"
                    + "绑定时会重取；若届时仍为空，请在 config 配置 openapi-app-id。");
        }

        this.service = new BindingServiceImpl(store, new LockAuthAdapter(plugin, lockState), appIdSupplier,
                msg -> plugin.getLogger().warning(msg));
        this.service.setBindingPrompt(plugin.getConfig().getString("binding-prompt", "绑定"));
        this.service.setMaxBindAttempts(plugin.getConfig().getInt("bind-max-attempts", 5));
        this.service.setSuccessTemplates(
                plugin.getConfig().getString("messages.bind-success", ""),
                plugin.getConfig().getString("messages.login-success", ""));

        // 自动登录信任期（对标 Velocity 的 auto-login-window-minutes；本地 Bukkit 即大脑 isBrain=true）。
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

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 【搁置】PlayerLockListener：Velocity 端已用 packetevents 实现包级登录锁，
        // Bukkit 端的事件锁不再需要。代码保留，仅注释注册行。
        // plugin.getServer().getPluginManager().registerEvents(
        //         new PlayerLockListener(plugin, lockState, awaitingQQ), plugin);

        // 定时提醒：每隔几秒给锁定玩家发提示
        startReminderTask(plugin);

        plugin.getLogger().info("[白名单] 本地模式监听器已注册（绑定+定时提醒）——锁已迁移到 Velocity 端 packetevents");
    }

    public static WhitelistModule getInstance() {
        return instance;
    }

    /** 获取绑定仓库（供 XingtuBotService API / 服务总线使用）。 */
    public BindingRepository getBindingStore() {
        return service.getStore();
    }

    /** 获取绑定服务（供服务总线注册，给其他扩展查玩家数据）。 */
    public BindingService getService() {
        return service;
    }

    /** 获取锁定状态（供 GameChatForwarder 等模块判断注册态）。 */
    public LockState getLockState() {
        return lockState;
    }

    /** 热重载可变配置（/xtb reload 调用）。 */
    public void reload() {
        this.spigotConfig = new SpigotConfig(plugin.getConfig());
    }

    /** 兼容群服互联：按 openid 取玩家名。 */
    public List<String> getPlayersByFormId(String openid) {
        return service.getStore().getPlayersByOpenid(openid);
    }

    // ==================== 游戏内事件 ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        plugin.getLogger().info("[白名单] onPlayerJoin 触发: " + name + "，已锁定");
        lockState.lock(name); // 进服一律先锁
        if (service.isPlayerBound(name)) {
            // 同设备信任期内免密自动登录（对标 Velocity）：同 IP 且未过期 → 直接解锁，不打扰群。
            if (autoLoginAllowed(name, playerIp(player))) {
                service.markLoggedInSession(name);
                lockState.unlock(name);
                JoinQrMap.cleanup(player);
                player.sendTitle("§a§l欢迎回来", "§f同设备信任期内已自动登录", 10, 60, 15);
                player.sendMessage("§a✅ 同设备信任期内，已为你自动登录，祝游戏愉快！");
                return;
            }
            String title = spigotConfig.getStringResolved("title-code", "@{bot} /验证");
            String loginMsg = spigotConfig.getStringResolved("login-mssage",
                    "🔈§f请在群内发送 §b@{bot} §c登录 或 §f输入§c/login <密码> §f完成登录");
            player.sendTitle("§a§l欢迎回来", title, 10, 60, 15);
            // 登录也附加群二维码：有些玩家 QQ 群太多一时找不到，扫码即可定位到本群发「登录」
            JoinQrMap.sendWithQr(plugin, player, "§e欢迎回来！" + loginMsg);
        } else if (service.hasPending(name)) {
            // 之前已声明QQ（中途掉线/切服回来）：直接提示去群里发「绑定」，不必再输 QQ
            String bindWord = plugin.getConfig().getString("binding-prompt", "绑定");
            player.sendTitle("§6§l请完成绑定", "§f在群里发送「§e§l" + bindWord + "§f」", 10, 60, 15);
            JoinQrMap.sendWithQr(plugin, player, "§e你已登记QQ，请在群里发送「§e§l" + bindWord
                    + "§e」完成绑定（5 分钟内有效）");
        } else {
            awaitingQQ.add(name);
            player.sendTitle("§6§l欢迎来到本服", "§f请在聊天框输入 QQ 号开始白名单绑定", 10, 60, 15);
            // 输入 QQ 号阶段【不发】加群二维码：此时玩家还不需要进群，二维码会误导。
            // 二维码改在登记成功后（onPlayerChat 成功）才出现——那时才需要去群里发「绑定」。
            player.sendMessage("§e欢迎！请在聊天框输入你的 §bQQ号 §e完成白名单绑定");
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        if (!awaitingQQ.contains(name)) return;

        event.setCancelled(true);
        String qq = event.getMessage().trim();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BindingService.Result r = service.declareQQ(name, qq);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayerExact(name);
                if (p != null) p.sendMessage(r.message);
                if (r.success) {
                    awaitingQQ.remove(name);
                    // 进入「去群里发『绑定』」阶段，此时才发加群二维码（地图持久件；
                    // 后续的重复提醒会自带聊天二维码，方便没进群的玩家扫码进群发「绑定」）。
                    if (p != null) JoinQrMap.giveMapIfEnabled(plugin, p);
                }
            });
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        String name = p.getName();
        awaitingQQ.remove(name);
        lockState.clear(name);
        // 退出前若本会话已登录，按当前 IP 武装自动登录信任期（对标 VelocityBridge.onDisconnect）：
        // 同 IP 窗口内重进可免密自动登录。只对已登录玩家武装——没登录过的不会获得资格。
        if (autoLoginWindowMillis > 0 && service.isLoggedInSession(name)) {
            String ip = playerIp(p);
            if (ip != null) autoLoginPut(name, ip, System.currentTimeMillis() + autoLoginWindowMillis);
        }
        // 清会话：否则 loggedIn 集合永留该玩家，重进后 loginByGroup 会短路成「已经登录过了」而不解锁 → 永久卡死。
        // （此前本地模式漏了这步，导致「只能登录一次，下次登录不了」。对标 Velocity/BungeeCord 的退出处理。）
        service.clearSession(name);
        // 不清待验证记录：玩家可能掉线/切服后再去群里发「绑定」，让其在 TTL(5分钟)内仍能绑成。
        // 待验证记录由 BindingService 按 TTL 自动过期，无需在退出时清。
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
            // 玩家在群里发「绑定」→ 取其头像与待验证记录比对。
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                    reply(event, service.bindByAvatar(openid)));
        }
    }

    /** 成功卡片走 markdown 通道（不转义），其余文本照常。 */
    private static void reply(GuildMessageEvent event, BindingService.Result r) {
        if (r.markdown) {
            event.replyMarkdown(r.message, null);
        } else {
            event.reply(r.message);
        }
    }

    // ==================== 定时提醒 ====================

    private void startReminderTask(Plugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                String name = player.getName();
                if (!lockState.isLocked(name)) continue;

                if (awaitingQQ.contains(name)) {
                    // 第一步：还没输入 QQ 号——【不发二维码】，此阶段不需要进群，避免误导。
                    player.sendTitle("§6§l请绑定白名单", "§f在聊天框输入你的 QQ 号", 0, 40, 10);
                    player.sendMessage("§e⏳ 请在聊天框输入你的 §bQQ号 §e完成白名单绑定");
                } else if (!service.isPlayerBound(name)) {
                    // 第二步：已输入 QQ 号，等玩家在群里发「绑定」完成头像比对
                    String bindWord = plugin.getConfig().getString("binding-prompt", "绑定");
                    player.sendTitle("§6§l请完成绑定", "§f在群里发送「§e§l" + bindWord + "§f」", 0, 40, 10);
                    JoinQrMap.sendWithQr(plugin, player, "§e⏳ 请在群里发送「§e§l" + bindWord
                            + "§e」即可完成绑定（5 分钟内有效）");
                } else {
                    // 第三步：已绑定，等登录
                    String bot = org.windy.xingtubot.common.BotIdentity.getName();
                    player.sendTitle("§e§l请登录", "§f在群里 @" + bot + " 发送「登录」", 0, 40, 10);
                    JoinQrMap.sendWithQr(plugin, player, "§e⏳ 请在群里 §b@" + bot + " 发送「登录」§e完成登录");
                }
            }
        }, REMINDER_INTERVAL, REMINDER_INTERVAL);
    }

    // ==================== 自动登录信任期（对标 VelocityBridge）====================

    /** 写入信任记录：有仓库走仓库（持久化），否则退回内存。 */
    private void autoLoginPut(String player, String ip, long expiry) {
        AutoLoginRepository r = autoLoginRepo;
        if (r != null) r.put(player, ip, expiry);
        else autoLoginMem.put(player.toLowerCase(), new AutoLoginEntry(ip, expiry));
    }

    /** 读信任记录（统一转成 AutoLoginEntry；无则 null）。 */
    private AutoLoginEntry autoLoginGet(String player) {
        AutoLoginRepository r = autoLoginRepo;
        if (r != null) {
            AutoLoginRepository.Entry e = r.get(player);
            return e == null ? null : new AutoLoginEntry(e.ip, e.expiry);
        }
        return autoLoginMem.get(player.toLowerCase());
    }

    /** 删信任记录（过期清理）。 */
    private void autoLoginRemove(String player) {
        AutoLoginRepository r = autoLoginRepo;
        if (r != null) r.remove(player);
        else autoLoginMem.remove(player.toLowerCase());
    }

    /** 自动登录是否放行：开启 + 有记录 + 未过期 + IP 与上次登录退出时一致。 */
    private boolean autoLoginAllowed(String player, String ip) {
        if (autoLoginWindowMillis <= 0 || ip == null) return false;
        AutoLoginEntry e = autoLoginGet(player);
        if (e == null) return false;
        if (System.currentTimeMillis() >= e.expiry) {
            autoLoginRemove(player); // 过期清理
            return false;
        }
        return ip.equals(e.ip);
    }

    /** 玩家当前远端 IP（仅主机地址，不含端口）；取不到返回 null。 */
    private static String playerIp(Player p) {
        java.net.InetSocketAddress addr = p.getAddress();
        return addr != null && addr.getAddress() != null ? addr.getAddress().getHostAddress() : null;
    }

    /** 自动登录记录：上次登录退出时的 IP + 过期时刻。 */
    private static final class AutoLoginEntry {
        final String ip;
        final long expiry;
        AutoLoginEntry(String ip, long expiry) {
            this.ip = ip;
            this.expiry = expiry;
        }
    }
}
