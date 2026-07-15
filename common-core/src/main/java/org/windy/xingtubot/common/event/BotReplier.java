package org.windy.xingtubot.common.event;

/**
 * Compatibility alias for the reply capability attached to a bot message.
 *
 * <p>New code should depend on {@link MessageReply}; this name is kept because
 * existing handlers and transport adapters still refer to it.</p>
 *
 * @deprecated Use {@link MessageReply}.
 */
@Deprecated
public interface BotReplier extends MessageReply {
}
