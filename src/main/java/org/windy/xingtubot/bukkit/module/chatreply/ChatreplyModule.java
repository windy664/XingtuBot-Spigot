package org.windy.xingtubot.bukkit.module.chatreply;

import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.windy.xingtubot.bukkit.XingtuBot;
import org.windy.xingtubot.bukkit.module.chatreply.listener.GuildMessageListener;

public class ChatreplyModule implements Listener {

    private static ChatreplyModule instance;
    public final XingtuBot plugin;
    private static SensitiveFilter sensitiveFilter;

    public ChatreplyModule(XingtuBot plugin) {
        this.plugin = plugin;
        instance = this;

        // 加载配置文件
        plugin.saveDefaultConfig();

        // 初始化敏感词过滤器
        sensitiveFilter = SensitiveFilter.fromConfig(plugin.getConfig());

        // 注册事件和指令
        Bukkit.getPluginManager().registerEvents(new GuildMessageListener(), plugin);
        plugin.getCommand("messagereply").setExecutor(new ReplyCommand());

        plugin.getLogger().info("群服回复模块已启用");
        sensitiveFilter.reloadCloudWords();
    }

    public static ChatreplyModule getInstance() {
        return instance;
    }

    public static SensitiveFilter getSensitiveFilter() {
        return sensitiveFilter;
    }
}