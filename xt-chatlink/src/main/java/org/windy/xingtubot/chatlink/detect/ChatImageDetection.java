package org.windy.xingtubot.chatlink.detect;

import org.windy.xingtubot.common.chatlink.ChatImageTracker;
import org.windy.xingtubot.common.platform.BotLogger;

/**
 * ChatImage 探测装配器（本类不 import 任何 PacketEvents 类型）。
 *
 * <p>反射加载 {@link RegisterChannelListener}（它 import PE 类型）：
 * PE 不在时该类加载即抛 NoClassDefFoundError，在这里捕获降级——
 * 保证「未装 XingtuBot-Auth」时群服互联主流程零感知。
 * 调用方约定：attach 失败且模式为 AUTO 时，把 tracker 降级为 CICODE（保持旧行为）。
 */
public final class ChatImageDetection {

    private ChatImageDetection() {
    }

    /** 尝试挂探测。true=已挂（AUTO 模式可逐玩家分流）；false=不可用（调用方应回退渲染模式）。 */
    public static boolean tryAttach(ChatImageTracker tracker, BotLogger logger) {
        try {
            Class<?> cls = Class.forName("org.windy.xingtubot.chatlink.detect.RegisterChannelListener");
            boolean ok = Boolean.TRUE.equals(
                    cls.getMethod("attach", ChatImageTracker.class).invoke(null, tracker));
            logger.info(ok
                    ? "[Chatlink] ChatImage 模组探测已启用（监听 chatimage:* 频道注册：装模组的玩家收 CICode，未装的收 [图片] 链接）"
                    : "[Chatlink] PacketEvents 未初始化，ChatImage 模组探测未启用（图片按回退模式渲染）");
            return ok;
        } catch (Throwable t) {
            // NoClassDefFoundError / ClassNotFoundException：未装 XingtuBot-Auth（PE 的唯一 bundler）
            logger.info("[Chatlink] 未检测到 PacketEvents（需安装 XingtuBot-Auth），ChatImage 模组探测未启用（图片按回退模式渲染）");
            return false;
        }
    }

    /** 卸载探测（插件 disable 时调用；未挂载时 no-op）。 */
    public static void detach() {
        try {
            Class.forName("org.windy.xingtubot.chatlink.detect.RegisterChannelListener")
                    .getMethod("detachAll").invoke(null);
        } catch (Throwable ignored) {
        }
    }
}
