package org.windy.xingtubot.bukkit.module.console;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 捕获型控制台 CommandSender（仿 RCON 实现）。
 * 继承 ConsoleCommandSender，重写 sendMessage 直接拦截输出。
 * 命令插件通过 sender.sendMessage() 发的输出全部被捕获，不走 Logger/System.out。
 * 兼容 Spigot 1.12.2 API。
 */
public class CapturingConsoleSender implements ConsoleCommandSender {

    private final ConsoleCommandSender wrapped;
    private final Consumer<String> callback;
    private final StringBuilder buffer = new StringBuilder();

    public CapturingConsoleSender(Consumer<String> callback) {
        this.wrapped = Bukkit.getConsoleSender();
        this.callback = callback;
    }

    @Override
    public void sendMessage(String message) {
        if (message != null && !message.isEmpty()) {
            if (buffer.length() > 0) buffer.append("\n");
            buffer.append(message);
        }
    }

    @Override
    public void sendMessage(String[] messages) {
        for (String msg : messages) sendMessage(msg);
    }

    /** 把捕获的输出通过回调返回。 */
    public void flush() {
        if (callback != null && buffer.length() > 0) {
            callback.accept(buffer.toString().trim());
        }
    }

    /**
     * 延迟 flush：等待异步输出到达后再回调。
     * 某些插件在下一个 tick 才通过 sender.sendMessage() 输出（如 LiteSignIn），
     * 同步 flush 会漏掉。延迟 5 tick（250ms）后收集并回调。
     *
     * @param plugin Bukkit 插件实例（用于调度）
     */
    public void flushDelayed(org.bukkit.plugin.Plugin plugin) {
        Bukkit.getScheduler().runTaskLater(plugin, this::flush, 5L);
    }

    // ---- 委托给真实的 ConsoleCommandSender ----

    @Override
    public String getName() { return wrapped.getName(); }

    @Override
    public org.bukkit.Server getServer() { return wrapped.getServer(); }

    @Override
    public boolean isPermissionSet(String name) { return wrapped.isPermissionSet(name); }

    @Override
    public boolean isPermissionSet(org.bukkit.permissions.Permission perm) { return wrapped.isPermissionSet(perm); }

    @Override
    public boolean hasPermission(String name) { return wrapped.hasPermission(name); }

    @Override
    public boolean hasPermission(org.bukkit.permissions.Permission perm) { return wrapped.hasPermission(perm); }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value) {
        return wrapped.addAttachment(plugin, name, value);
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin) {
        return wrapped.addAttachment(plugin);
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value, int ticks) {
        return wrapped.addAttachment(plugin, name, value, ticks);
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, int ticks) {
        return wrapped.addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(org.bukkit.permissions.PermissionAttachment attachment) {
        wrapped.removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() { wrapped.recalculatePermissions(); }

    @Override
    public Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() {
        return wrapped.getEffectivePermissions();
    }

    @Override
    public boolean isOp() { return wrapped.isOp(); }

    @Override
    public void setOp(boolean value) { wrapped.setOp(value); }

    @Override
    public boolean isConversing() { return wrapped.isConversing(); }

    @Override
    public void acceptConversationInput(String input) { wrapped.acceptConversationInput(input); }

    @Override
    public boolean beginConversation(Conversation conversation) { return wrapped.beginConversation(conversation); }

    @Override
    public void abandonConversation(Conversation conversation) { wrapped.abandonConversation(conversation); }

    @Override
    public void abandonConversation(Conversation conversation, ConversationAbandonedEvent details) {
        wrapped.abandonConversation(conversation, details);
    }

    @Override
    public void sendRawMessage(String message) { sendMessage(message); }

    @Override
    public Spigot spigot() { return wrapped.spigot(); }
}
