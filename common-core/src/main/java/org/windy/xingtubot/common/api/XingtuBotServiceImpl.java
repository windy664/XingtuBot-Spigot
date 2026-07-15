package org.windy.xingtubot.common.api;

/**
 * @deprecated Implementation lives in
 * {@link org.windy.xingtubot.common.runtime.XingtuBotServiceImpl}. This class
 * remains as a compatibility shim for older internal and external references.
 */
@Deprecated
public class XingtuBotServiceImpl extends org.windy.xingtubot.common.runtime.XingtuBotServiceImpl {

    @FunctionalInterface
    public interface GroupMarkdownSender
            extends org.windy.xingtubot.common.runtime.XingtuBotServiceImpl.GroupMarkdownSender {
    }

    public XingtuBotServiceImpl(GroupMarkdownSender markdownSender) {
        super(markdownSender);
    }
}
