package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.windy.xingtubot.bukkit.SpigotConfig;
import org.windy.xingtubot.bukkit.XingtuBot;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.binding.BindingStorageFactory;
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
 *   <li>未绑定玩家聊天框输 QQ 号 → declareQQ（下载头像）。</li>
 *   <li>群里发「白名单」→ 头像唯一匹配 → 绑定 + 解锁。</li>
 *   <li>群里发「登录」→ 校验绑定 + 解锁。</li>
 * </ul>
 */
public class WhitelistModule implements Listener {

    private final XingtuBot plugin;
    private final BindingService service;
    private final LockState lockState = new LockState();
    // 未绑定、正等待游戏内输入 QQ 号的玩家
    private final Set<String> awaitingQQ = ConcurrentHashMap.newKeySet();

    private static WhitelistModule instance;

    public WhitelistModule(XingtuBot plugin) {
        this.plugin = plugin;
        instance = this;

        BindingRepository store = BindingStorageFactory.create(
                new SpigotConfig(plugin.getConfig()), true, plugin.getDataFolder(),
                msg -> plugin.getLogger().warning(msg));
        String appId = plugin.getConfig().getString("openapi-app-id", "");
        if (appId.isEmpty()) {
            plugin.getLogger().warning("⚠️ 未配置 openapi-app-id，群头像比对将无法工作（绑定依赖它）");
        }
        this.service = new BindingService(store, new LockAuthAdapter(plugin, lockState), appId,
                msg -> plugin.getLogger().warning(msg));

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new PlayerLockListener(plugin, lockState, awaitingQQ), plugin);
        plugin.getLogger().info("[白名单] 本地模式监听器已注册（绑定+锁）");
    }

    public static WhitelistModule getInstance() {
        return instance;
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
            player.sendMessage("§e欢迎回来！请在群里 §b@机器人 发送「登录」§e完成登录");
        } else {
            awaitingQQ.add(name);
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
                if (r.success) awaitingQQ.remove(name);
            });
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        awaitingQQ.remove(name);
        lockState.clear(name);
        service.cancelPending(name);
    }

    // ==================== 群消息事件 ====================

    @EventHandler
    public void onGuildMessage(GuildMessageEvent event) {
        String message = event.getMessage() == null ? "" : event.getMessage().trim();
        String openid = event.getFormId();
        String loginPrompt = plugin.getConfig().getString("login-prompt", "登录");

        if (loginPrompt.equals(message)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                    event.reply(service.loginByGroup(openid).message));
        } else if (message.contains("白名单")) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                    event.reply(service.bindByGroupAvatar(openid).message));
        }
    }
}
