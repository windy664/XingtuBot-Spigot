package org.windy.xingtubot.velocity.api.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import org.windy.xingtubot.velocity.api.WebSocketCommClient;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Velocity环境下的WebSocket客户端实现
 */
public class VelocityXingtuSocketClient extends WebSocketCommClient {

    private final Scheduler velocityScheduler;
    private final PluginContainer plugin;

    public VelocityXingtuSocketClient(String serverUri, PluginContainer plugin,
                                      Logger logger, Scheduler scheduler,
                                      boolean debug, boolean reconnect,
                                      int maxReconnect) throws Exception {
        super(serverUri, logger, debug, reconnect, maxReconnect);
        this.velocityScheduler = scheduler;
        this.plugin = plugin;
    }

    @Override
    public SchedulerFacade getScheduler() {
        return new VelocitySchedulerAdapter();
    }

    /**
     * Velocity调度器适配器
     */
    private class VelocitySchedulerAdapter implements SchedulerFacade {
        @Override
        public void execute(Runnable task) {
            velocityScheduler.buildTask(plugin, task).schedule();
        }

        @Override
        public ScheduledTask scheduleDelayed(Runnable task, long delay, TimeUnit unit) {
            return velocityScheduler.buildTask(plugin, task)
                    .delay(delay, unit)
                    .schedule();
        }

        @Override
        public ScheduledTask scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
            return velocityScheduler.buildTask(plugin, task)
                    .delay(initialDelay, unit)
                    .repeat(period, unit)
                    .schedule();
        }
    }

    /**
     * 创建标准化的群消息响应
     */
    public JsonObject createReplyStructure(JsonObject original, String replyContent) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "reply");
        response.addProperty("server", "Velocity服务器"); // 从配置获取
        response.addProperty("guild_id", original.get("guild_id").getAsString());
        response.addProperty("form_id", original.get("form_id").getAsString());
        response.addProperty("msg", replyContent);
        return response;
    }

    /**
     * 添加群消息处理器
     */
    public void addGuildMessageHandler(Consumer<JsonObject> handler) {
        super.addMessageHandler(message -> {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if ("message".equals(json.get("type").getAsString())) {
                handler.accept(json);
            }
        });
    }
}