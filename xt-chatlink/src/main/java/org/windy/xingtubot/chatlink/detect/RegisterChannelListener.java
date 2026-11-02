package org.windy.xingtubot.chatlink.detect;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.windy.xingtubot.common.chatlink.ChatImageTracker;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * ChatImage 模组探测监听器（PacketEvents，平台中立：Bukkit/Velocity 共用同一份）。
 *
 * <p>原理：装 ChatImage 的客户端入服会经原版 {@code minecraft:register}（旧版 MC|Register）
 * 广播自己可接收的频道；扫到 {@code chatimage:*} 前缀即把玩家标记进 tracker。
 * 依据 kitUIN/ChatImage 源码：三端（Fabric/Forge/NeoForge）都用 chatimage 命名空间频道，
 * Fabric 客户端注册 download_file_channel / get_file_channel 接收器；
 * Forge/NeoForge 是否同走原版广播待实测——未广播的客户端 AUTO 模式下按未装渲染，不影响正确性。
 *
 * <p><b>防冲突约束（项目 PE 研究结论：xt-auth 是唯一 bundler）</b>：
 * 本类只 import PE 类型、只消费 xt-auth 内置并初始化好的实例——
 * 绝不 setAPI/build/load/init，避免第二份 PE 实例在同一 netty pipeline 上重复注入。
 * PE 不在（未装 XingtuBot-Auth）时本类加载即 NoClassDefFoundError，
 * 由 {@link ChatImageDetection} 守卫捕获，检测功能静默降级，不影响群服互联主流程。
 */
public final class RegisterChannelListener extends PacketListenerAbstract {

    private static volatile RegisterChannelListener active;

    private final ChatImageTracker tracker;

    private RegisterChannelListener(ChatImageTracker tracker) {
        super(PacketListenerPriority.NORMAL);
        this.tracker = tracker;
    }

    /** 挂载探测（重复调用先卸旧）。PE 未就绪返回 false（不抛异常）。 */
    public static synchronized boolean attach(ChatImageTracker tracker) {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || !api.isInitialized()) return false;
        RegisterChannelListener listener = new RegisterChannelListener(tracker);
        api.getEventManager().registerListener(listener);
        active = listener;
        return true;
    }

    /** 卸载探测（插件 disable 时调用；未挂载时 no-op）。 */
    public static synchronized void detachAll() {
        RegisterChannelListener l = active;
        active = null;
        if (l == null) return;
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(l);
        } catch (Throwable ignored) {
            // PE 已被卸载/不可用：静默
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLUGIN_MESSAGE) return;
        try {
            WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);
            String channel = wrapper.getChannelName();
            if (!"minecraft:register".equals(channel) && !"MC|Register".equals(channel)) return;
            User user = event.getUser();
            UUID playerId = user != null ? user.getUUID() : null;
            if (playerId == null) return;
            // register 载荷 = NUL 分隔的频道名列表
            for (String c : new String(wrapper.getData(), StandardCharsets.UTF_8).split("\0")) {
                if (c.startsWith("chatimage:")) {
                    tracker.mark(playerId);
                    return;
                }
            }
        } catch (Exception ignored) {
            // 探测失败不影响主流程
        }
    }
}
