package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.lock.LockState;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 自研登录锁（取代 AuthMe forceLogin 的「未登录冻结」效果）。
 *
 * <p>对处于 {@link LockState} 锁定态的玩家，原地冻结并拦截一切操作：
 * 移动（只允许原地转头）、挖放方块、交互、攻击、丢物品、开背包、聊天、命令（白名单命令除外）。
 *
 * <p><b>人身安全（对标 AuthMe 未认证保护）：</b>锁定期玩家免疫一切伤害（摔落/火/岩浆/溺水/窒息/虚空/怪物等）、
 * 怪物不索敌、免疫饥饿——绑定/登录前绝不会因环境或怪物死亡。同时禁止捡物、切快捷栏、换副手，
 * 防止锁定期被动改变背包状态。绑定成功 / 群里登录后由外部 unlock 解锁。
 */
public class PlayerLockListener implements Listener {

    private final LockState lockState;
    // 锁定时仍允许的命令前缀（避免把玩家彻底卡死）
    private final List<String> allowedCommands;
    // 正在等待输入 QQ 号的玩家集合（这些玩家的聊天不在此拦截，交给绑定流程读取）
    private final Set<String> awaitingQQ;

    public PlayerLockListener(Plugin plugin, LockState lockState, Set<String> awaitingQQ) {
        this.lockState = lockState;
        this.awaitingQQ = awaitingQQ;
        this.allowedCommands = Arrays.asList("/login", "/register", "/bind", "/绑定", "/登录");
    }

    private boolean locked(Player p) {
        return lockState.isLocked(p.getName());
    }

    // 移动：允许原地转头，禁止位移（对比坐标方块是否变化）
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!locked(e.getPlayer())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()) {
            // 拉回原方块中心，保留视角
            Location back = from.clone();
            back.setPitch(to.getPitch());
            back.setYaw(to.getYaw());
            e.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && locked((Player) e.getDamager())) {
            e.setCancelled(true); // 锁定玩家不能攻击别人
        }
    }

    // 人身安全：锁定玩家免疫一切伤害（摔落/火/岩浆/溺水/窒息/虚空/爆炸/怪物攻击等全覆盖）
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && locked((Player) e.getEntity())) {
            e.setCancelled(true);
        }
    }

    // 怪物不索敌锁定玩家（免得被推搡/逼到危险位置）
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() instanceof Player && locked((Player) e.getTarget())) {
            e.setCancelled(true);
        }
    }

    // 禁止捡物（避免锁定期被动改变背包）
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player && locked((Player) e.getEntity())) {
            e.setCancelled(true);
        }
    }

    // 禁止切换快捷栏选中格
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    // 禁止主手/副手交换
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player && locked((Player) e.getWhoClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!locked(e.getPlayer())) return;
        // 等待输 QQ 号的玩家：放行，交由绑定流程读取（它会自行 setCancelled 不广播）
        if (awaitingQQ.contains(e.getPlayer().getName())) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!locked(e.getPlayer())) return;
        String msg = e.getMessage().toLowerCase();
        for (String allowed : allowedCommands) {
            if (msg.startsWith(allowed)) return;
        }
        e.setCancelled(true);
        e.getPlayer().sendMessage("§c请先完成绑定/登录后再使用命令");
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && locked((Player) e.getEntity())) {
            e.setCancelled(true); // 锁定时不掉饥饿
        }
    }
}
