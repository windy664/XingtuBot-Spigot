package org.windy.xingtubot.common.tts;

/**
 * 语音合成接口（文字 → 音频字节）。<b>核心不内置实现</b>——
 * Edge TTS 国内 403、其它 TTS 又各有 key/计费，故合成留给使用者按需提供。
 *
 * <p>装上一个实现后，{@code reply-mode=voice/text+voice} 会把回复文字合成音频，
 * 经 {@link org.windy.xingtubot.common.api.QqOpenApiClient#sendGroupVoiceData} 直发 QQ 群/单聊；
 * 没有实现时核心自动退回纯文字（不报错）。
 *
 * <p>接入方式：实现本接口并通过 {@link java.util.ServiceLoader} 注册
 * （在 jar 的 {@code META-INF/services/org.windy.xingtubot.common.tts.VoiceSynthesizer}
 * 里写上实现类全名），{@code BotLauncher} 启动时自动拾取第一个实现。
 *
 * <p>注意：本接口只负责"文字→音频"。如果你已有现成音频文件/字节，<b>无需实现本接口</b>，
 * 直接用 {@code BotMessageEvent.replyVoice(url)} / {@code replyVoiceData(bytes)} 发送即可。
 */
public interface VoiceSynthesizer {

    /**
     * 把文字合成为音频字节（QQ 语音需 silk/mp3 等，详见 QQ 官方文档）。
     *
     * @param text 要合成的文字
     * @return 音频字节；返回 {@code null} 或抛异常都会被核心安全跳过（该条回复退回纯文字）。
     */
    byte[] synthesize(String text) throws Exception;
}
