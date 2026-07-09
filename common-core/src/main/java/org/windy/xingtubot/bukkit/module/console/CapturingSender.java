package org.windy.xingtubot.bukkit.module.console;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.util.ColorCodeConverter;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 伪装 CommandSender：执行命令时捕获所有 sendMessage 输出。
 * 用于以玩家身份执行自定义命令时，把命令反馈回传给群。
 * 兼容 Spigot 1.12.2 API。
 */
public class CapturingSender implements CommandSender {

    private final String playerName;
    private final Consumer<String> output;
    private final StringBuilder buffer = new StringBuilder();

    public CapturingSender(String playerName, Consumer<String> output) {
        this.playerName = playerName;
        this.output = output;
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

    /** 刷新缓冲区，把所有捕获的输出通过回调返回。有颜色码时转 Markdown。 */
    public void flush() {
        if (output == null || buffer.length() == 0) return;
        String text = buffer.toString().trim();
        // 有 MC 颜色码 → 转成 Markdown font 标签
        if (text.contains("§") || text.contains("&")) {
            text = ColorCodeConverter.toMarkdown(text);
        }
        output.accept(text);
    }

    @Override
    public Server getServer() {
        return Bukkit.getServer();
    }

    @Override
    public String getName() {
        return playerName;
    }

    @Override
    public boolean isPermissionSet(String name) { return true; }

    @Override
    public boolean isPermissionSet(Permission perm) { return true; }

    @Override
    public boolean hasPermission(String name) { return true; }

    @Override
    public boolean hasPermission(Permission perm) { return true; }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) { return null; }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) { return null; }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) { return null; }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) { return null; }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {}

    @Override
    public void recalculatePermissions() {}

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() { return java.util.Collections.emptySet(); }

    @Override
    public boolean isOp() { return true; }

    @Override
    public void setOp(boolean value) {}

    @Override
    public Spigot spigot() { return new Spigot() {}; }

    private static String stripColor(String s) {
        return s.replaceAll("(?i)[§&][0-9A-FK-OR]", "").trim();
    }
}
