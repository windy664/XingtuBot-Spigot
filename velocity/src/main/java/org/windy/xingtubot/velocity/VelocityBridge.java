package org.windy.xingtubot.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.bridge.BridgeCodec;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Velocity（大脑）侧的跨服桥：注册插件消息通道，处理子服上报，回应握手。
 *
 * <p>子服上报 → 这里：PLAYER_JOIN（判断绑定状态）、DECLARE_QQ（下载头像存 pending）、
 * PLAYER_QUIT（清理）、WHO_IS_BOSS（回 I_AM_BOSS 宣告主导）。
 *
 * <p>BindingService 由 xt-auth 注册为 service，本类通过 host 惰性获取。
 */
public class VelocityBridge {

    private final ProxyServer proxy;
    private final Object plugin;
    private final ChannelIdentifier channel;
    private final AuthAdapter auth;
    private final Consumer<String> logger;
    private final java.util.Map<String, java.util.function.Consumer<String>> consoleCallbacks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, java.util.function.Consumer<String>> papiCallbacks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** xt-auth 加载后通过 supplier 提供 BindingService（惰性解析）。 */
    private volatile BindingService service;
    private volatile java.util.function.Supplier<BindingService> serviceProvider;
    // Redis 跨服信道（可选，xt-auth 按 config 注入）。非 null 时大脑经它收发，免去「需在线玩家当载体」。
    private volatile org.windy.xingtubot.common.bridge.CrossServerChannel redisChannel;

    public VelocityBridge(ProxyServer proxy, Object plugin, ChannelIdentifier channel,
                          AuthAdapter auth, Consumer<String> logger) {
        this(proxy, plugin, channel, auth, logger, null);
    }

    public VelocityBridge(ProxyServer proxy, Object plugin, ChannelIdentifier channel,
                          AuthAdapter auth, Consumer<String> logger,
                          java.util.function.Supplier<BindingService> serviceProvider) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channel = channel;
        this.auth = auth;
        this.logger = logger;
        this.serviceProvider = serviceProvider;
        proxy.getChannelRegistrar().register(channel);
        proxy.getEventManager().register(plugin, this);
    }

    /** 由 xt-auth 扩展插件在 onEnable 时注入。 */
    public void setService(BindingService service) {
        this.service = service;
    }

    /** 认证适配器（DO_LOGIN/DO_REGISTER 下发通道）：供 xt-auth 注入到 BindingService。 */
    public AuthAdapter getAuthAdapter() {
        return auth;
    }

    /** 设置 BindingService 供应者（惰性获取，避免编译期类型依赖）。 */
    public void setServiceProvider(java.util.function.Supplier<BindingService> provider) {
        this.serviceProvider = provider;
    }

    /** 获取 BindingService：优先直接注入，其次从 supplier 获取。 */
    private BindingService getService() {
        BindingService s = this.service;
        if (s != null) return s;
        java.util.function.Supplier<BindingService> sp = this.serviceProvider;
        if (sp != null) {
            s = sp.get();
            if (s != null) this.service = s; // 缓存
        }
        return s;
    }

    /** @deprecated 昵称统一走 {@link org.windy.xingtubot.common.BotIdentity}，本方法不再生效。 */
    @Deprecated
    public void setBotName(String botName) {
        // no-op
    }

    private volatile Consumer<String> onUnboundJoin;

    public void setOnUnboundJoin(Consumer<String> onUnboundJoin) {
        this.onUnboundJoin = onUnboundJoin;
    }

    // 玩家在游戏内输入 QQ 号、登记成功后触发（= 进入「该去群里发『绑定』」阶段）。
    // 加群二维码挂在这里发，而不是一进服(输QQ阶段)就发——避免误导玩家以为要先扫码。
    private volatile Consumer<String> onCodeIssued;

    public void setOnCodeIssued(Consumer<String> onCodeIssued) {
        this.onCodeIssued = onCodeIssued;
    }

    // 已绑定但未登录、且不在免密信任期内的玩家进服时触发：由 xt-auth 在群里发「免密登录」按钮卡片
    // （含玩家名 + 省级地区），绑定的 QQ 点一下即登录。参数为玩家名。
    private volatile Consumer<String> onNeedLogin;

    public void setOnNeedLogin(Consumer<String> onNeedLogin) {
        this.onNeedLogin = onNeedLogin;
    }

    // ===== IP 绑定的自动登录信任期 =====
    // 玩家登录后退出 → 按退出时 IP 记一条 (ip, 过期时刻)；同 IP 在窗口内重进 → 免密自动登录。
    // 窗口由 xt-auth 经 setAutoLoginWindowMillis 注入（读 auto-login-window-minutes）；0=关闭。
    // 安全：换 IP / 过期均需重新走 openid 验证（群内按钮登录），username-only 无法蒙混。
    // 持久化：记录存 autoLoginRepo（json/sqlite/mysql，跨重启存活）；未注入时退回内存 Map（不跨重启）。
    private final java.util.Map<String, AutoLoginEntry> autoLoginMem = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile org.windy.xingtubot.common.binding.AutoLoginRepository autoLoginRepo;
    private volatile long autoLoginWindowMillis = 0L;

    public void setAutoLoginWindowMillis(long millis) {
        this.autoLoginWindowMillis = Math.max(0L, millis);
    }

    /** 注入自动登录信任期仓库（xt-auth 按 storage-type 创建后注入；null=退回内存、不跨重启）。 */
    public void setAutoLoginRepository(org.windy.xingtubot.common.binding.AutoLoginRepository repo) {
        this.autoLoginRepo = repo;
    }

    /** 写入信任记录：有仓库走仓库（持久化），否则退回内存。 */
    private void autoLoginPut(String player, String ip, long expiry) {
        org.windy.xingtubot.common.binding.AutoLoginRepository r = autoLoginRepo;
        if (r != null) r.put(player, ip, expiry);
        else autoLoginMem.put(player.toLowerCase(), new AutoLoginEntry(ip, expiry));
    }

    /** 读信任记录（统一转成 AutoLoginEntry；无则 null）。 */
    private AutoLoginEntry autoLoginGet(String player) {
        org.windy.xingtubot.common.binding.AutoLoginRepository r = autoLoginRepo;
        if (r != null) {
            org.windy.xingtubot.common.binding.AutoLoginRepository.Entry e = r.get(player);
            return e == null ? null : new AutoLoginEntry(e.ip, e.expiry);
        }
        return autoLoginMem.get(player.toLowerCase());
    }

    /** 删信任记录（过期清理）。 */
    private void autoLoginRemove(String player) {
        org.windy.xingtubot.common.binding.AutoLoginRepository r = autoLoginRepo;
        if (r != null) r.remove(player);
        else autoLoginMem.remove(player.toLowerCase());
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

    public void setRedisChannel(org.windy.xingtubot.common.bridge.CrossServerChannel redisChannel) {
        this.redisChannel = redisChannel;
        // 订阅 Redis：收到子服经 Redis 上报的消息后，复用与 PluginMessage 同一套处理逻辑，
        // 应答走 Redis 广播（无 ServerConnection 可回）。子服只认 sourceType 不同的消息，不会回环。
        redisChannel.setMessageHandler((fromServer, data) -> {
            BridgeCodec.Decoded msg = BridgeCodec.decode(data);
            if (msg != null) handleDecoded(msg, this.redisChannel::broadcast);
        });
    }

    public void dispatchConsole(String targetServerName, String command,
                                java.util.function.Consumer<String> onResult) {
        String requestId = java.util.UUID.randomUUID().toString();
        consoleCallbacks.put(requestId, onResult);
        byte[] data = BridgeCodec.encode(CrossServerProtocol.Type.DO_CONSOLE,
                targetServerName, requestId, command);
        // 有 Redis 信道：广播即可送达（即使目标子服无玩家在线），应答也经 Redis 回来。
        org.windy.xingtubot.common.bridge.CrossServerChannel rc = this.redisChannel;
        if (rc != null) {
            rc.broadcast(data);
            scheduleCleanup(requestId);
            return;
        }
        boolean sentAny = false;
        for (com.velocitypowered.api.proxy.server.RegisteredServer rs : proxy.getAllServers()) {
            try {
                if (rs.sendPluginMessage(channel, data)) sentAny = true;
            } catch (Exception ignored) {
            }
        }
        if (!sentAny) {
            consoleCallbacks.remove(requestId);
            onResult.accept("⚠️ 没有可达的子服（子服需有玩家在线，插件消息才能送达；或改用 redis 信道）");
            return;
        }
        scheduleCleanup(requestId);
    }

    public void resolvePapi(String playerName, String text, java.util.function.Consumer<String> onResult) {
        java.util.Optional<Player> p =
                playerName == null ? java.util.Optional.empty() : proxy.getPlayer(playerName);
        if (!p.isPresent() || !p.get().getCurrentServer().isPresent()) {
            onResult.accept(text);
            return;
        }
        String requestId = java.util.UUID.randomUUID().toString();
        papiCallbacks.put(requestId, onResult);
        byte[] data = BridgeCodec.encode(CrossServerProtocol.Type.PAPI_RESOLVE, requestId, playerName, text);
        try {
            p.get().getCurrentServer().get().sendPluginMessage(channel, data);
        } catch (Exception e) {
            papiCallbacks.remove(requestId);
            onResult.accept(text);
            return;
        }
        Thread t = new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            java.util.function.Consumer<String> cb = papiCallbacks.remove(requestId);
            if (cb != null) cb.accept(text);
        });
        t.setDaemon(true);
        t.start();
    }

    private void scheduleCleanup(String requestId) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
            consoleCallbacks.remove(requestId);
        });
        t.setDaemon(true);
        t.start();
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection)) return;

        BridgeCodec.Decoded msg = BridgeCodec.decode(event.getData());
        if (msg == null) return;

        // 应答原路返回：经 PluginMessage 来的就用发起方的 ServerConnection 回。
        final ServerConnection src = (ServerConnection) event.getSource();
        handleDecoded(msg, resp -> src.sendPluginMessage(channel, resp));
    }

    /**
     * 统一处理子服上报的消息（PluginMessage 与 Redis 共用）。
     *
     * @param reply 把应答送回发起方子服的回调（PluginMessage 用 ServerConnection；Redis 用广播）。
     */
    private void handleDecoded(BridgeCodec.Decoded msg, java.util.function.Consumer<byte[]> reply) {
        switch (msg.type) {
            case WHO_IS_BOSS:
                reply.accept(BridgeCodec.encode(CrossServerProtocol.Type.I_AM_BOSS));
                break;

            case DECLARE_QQ: {
                String player = msg.field(0);
                String qq = msg.field(1);
                BindingService svc = getService();
                if (svc == null) break;
                CompletableFuture.runAsync(() -> {
                    try {
                        BindingService.Result r = svc.declareQQ(player, qq);
                        if (r.success) {
                            sendToPlayerServer(player, BridgeCodec.encode(CrossServerProtocol.Type.CLEAR_QQ, player));
                            // 登记成功 → 此刻才发加群二维码（进入「去群里发『绑定』」阶段）
                            Consumer<String> cb = onCodeIssued;
                            if (cb != null) {
                                try { cb.accept(player); } catch (Exception ignored) {}
                            }
                        }
                        auth.messagePlayer(player, r.message);
                    } catch (Exception e) {
                        log("处理 DECLARE_QQ 异常: " + e.getMessage());
                    }
                });
                break;
            }

            case PLAYER_QUIT:
                // 不清待验证记录：玩家切服/掉线后仍可在 TTL 内去群里发「绑定」绑成（按 TTL 自动过期）。
                break;

            case PAPI_RESULT: {
                String requestId = msg.field(0);
                String resolved = msg.field(1);
                java.util.function.Consumer<String> cb =
                        (requestId == null) ? null : papiCallbacks.remove(requestId);
                if (cb != null) cb.accept(resolved == null ? "" : resolved);
                break;
            }

            case CONSOLE_RESULT: {
                String requestId = msg.field(0);
                String serverName = msg.field(1);
                String output = msg.field(2);
                java.util.function.Consumer<String> cb = (requestId == null) ? null
                        : consoleCallbacks.remove(requestId);
                if (cb != null) {
                    cb.accept("【" + serverName + "】\n" + (output == null ? "" : output));
                }
                break;
            }

            case QUERY_BINDING_BY_OPENID: {
                String requestId = msg.field(0);
                String openid = msg.field(1);
                if (requestId == null) break;
                String playerName = "";
                BindingService svc = getService();
                if (svc != null && openid != null) {
                    java.util.List<String> players = svc.getStore().getPlayersByOpenid(openid);
                    if (!players.isEmpty()) playerName = players.get(0);
                }
                reply.accept(BridgeCodec.encode(CrossServerProtocol.Type.BINDING_RESULT, requestId, playerName));
                break;
            }

            default:
                break;
        }
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        final Player player = event.getPlayer();
        proxy.getScheduler().buildTask(plugin, () -> evaluateOnJoin(player.getUsername()))
                .delay(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void evaluateOnJoin(String player) {
        if (!proxy.getPlayer(player).isPresent()) return;
        BindingService svc = getService();
        if (svc == null) return; // xt-auth 未加载，跳过
        if (!svc.isPlayerBound(player)) {
            if (svc.hasPending(player)) {
                // 已声明 QQ（掉线/切服回来）：提示去群里发「绑定」，无需再输 QQ。
                auth.titlePlayer(player, "§6§l就差一步 · 请完成绑定", "§f在群里发送「绑定」完成验证");
                auth.messagePlayer(player, "§e你已登记 QQ，请在群里发送「§b绑定§e」完成绑定（5 分钟内有效）");
            } else {
                sendToPlayerServer(player, BridgeCodec.encode(CrossServerProtocol.Type.NEED_QQ, player));
                auth.titlePlayer(player, "§6§l欢迎 · 请绑定白名单", "§f请在聊天框输入 QQ 号开始白名单绑定");
                auth.messagePlayer(player, "§e欢迎！请在聊天框输入你的 §bQQ号 §e完成白名单绑定");
                Consumer<String> cb = onUnboundJoin;
                if (cb != null) {
                    try { cb.accept(player); } catch (Exception ignored) {}
                }
            }
        } else if (svc.isLoggedInSession(player)) {
            // 本会话已登录（跨子服切换）：静默解锁新子服，不打扰群、不重复提示。
            auth.login(player);
        } else if (autoLoginAllowed(player, currentIp(player))) {
            // IP 绑定的自动登录：上次登录后退出，同 IP 且在信任期内重进 → 免密自动登录。
            // 安全：换 IP / 过期一律落到下面的按钮分支重新走 openid 验证。
            auth.login(player);
            svc.markLoggedInSession(player);
            auth.titlePlayer(player, "§a§l欢迎回来", "§f同设备信任期内已自动登录");
            auth.messagePlayer(player, "§a✅ 同设备信任期内，已为你自动登录，祝游戏愉快！");
        } else {
            // 已绑定但需登录：游戏内提示「等群里点登录按钮」，并触发 xt-auth 在群里发免密登录按钮卡片。
            auth.titlePlayer(player, "§a§l欢迎回来", "§f请在群里点机器人发的「登录」按钮");
            auth.messagePlayer(player, "§e欢迎回来！机器人已在群里发「§a登录§e」按钮，绑定的 QQ 点一下即可登录");
            Consumer<String> cb = onNeedLogin;
            if (cb != null) {
                try { cb.accept(player); } catch (Exception ignored) {}
            }
        }
    }

    /** 当前在线玩家的远端 IP（仅主机地址，不含端口）；取不到返回 null。 */
    private String currentIp(String player) {
        return proxy.getPlayer(player)
                .map(Player::getRemoteAddress)
                .map(a -> a.getAddress() != null ? a.getAddress().getHostAddress() : null)
                .orElse(null);
    }

    /** IP 绑定的自动登录是否放行：开启 + 有记录 + 未过期 + IP 与上次登录退出时一致。 */
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

    /**
     * 启动登录提示【持续循环】：每隔 intervalSeconds 给未登录的在线玩家按其阶段显示<b>三种不同</b>的标题
     * —— 输入QQ / 完成绑定 / 登录，常驻直到完成。代理大脑直接发 title 包（含 §颜色码），由 xt-auth
     * 按 config 调用；intervalSeconds&lt;=0 不启用。已登录玩家（含切服/自动登录）跳过、不打扰。
     */
    public void startLoginReminder(int intervalSeconds) {
        if (intervalSeconds <= 0) return;
        final int stay = intervalSeconds * 20 + 20; // 停留覆盖到下次刷新，避免空档（tick）
        proxy.getScheduler().buildTask(plugin, () -> tickLoginReminder(stay))
                .delay(intervalSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .repeat(intervalSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .schedule();
    }

    private void tickLoginReminder(int stay) {
        BindingService svc = getService();
        if (svc == null) return;
        for (Player p : proxy.getAllPlayers()) {
            String name = p.getUsername();
            if (svc.isLoggedInSession(name)) continue; // 已登录（含切服/自动登录）→ 不提示
            // 三阶段各自不同的标题：未声明→输QQ；已声明未绑定→绑定；已绑定未登录→登录。
            if (svc.isPlayerBound(name)) {
                auth.titlePlayer(name, "§a§l欢迎回来 · 请登录",
                        "§f在群里点机器人发的「§a登录§f」按钮（或回复 登录）", 0, stay, 10);
            } else if (svc.hasPending(name)) {
                auth.titlePlayer(name, "§6§l就差一步 · 请完成绑定",
                        "§f在群里发送「§b绑定§f」完成头像验证", 0, stay, 10);
            } else {
                auth.titlePlayer(name, "§6§l欢迎 · 请绑定白名单",
                        "§f在聊天框输入你的 §bQQ号", 0, stay, 10);
            }
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player p = event.getPlayer();
        String name = p.getUsername();
        BindingService svc = getService();
        if (svc != null) {
            // 退出前若本会话已登录，按【当前 IP】武装自动登录信任期：同 IP 窗口内重进可免密自动登录。
            // 只对已登录玩家武装——从没登录过的不会获得自动登录资格。
            if (autoLoginWindowMillis > 0 && svc.isLoggedInSession(name)) {
                String ip = p.getRemoteAddress() != null && p.getRemoteAddress().getAddress() != null
                        ? p.getRemoteAddress().getAddress().getHostAddress() : null;
                if (ip != null) {
                    autoLoginPut(name, ip, System.currentTimeMillis() + autoLoginWindowMillis);
                }
            }
            svc.clearSession(name);
            // 不清待验证记录：玩家离开后仍可在 TTL(5分钟)内去群里发「绑定」绑成（按 TTL 自动过期）。
        }
    }

    private void sendToPlayerServer(String player, byte[] data) {
        proxy.getPlayer(player)
                .flatMap(p -> p.getCurrentServer())
                .ifPresent(sc -> sc.sendPluginMessage(channel, data));
    }

    private void log(String m) {
        if (logger != null) logger.accept(m);
    }
}
