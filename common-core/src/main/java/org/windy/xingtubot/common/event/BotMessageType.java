package org.windy.xingtubot.common.event;

/**
 * Stable message type exposed to extensions, independent from raw QQ event names.
 */
public enum BotMessageType {
    GROUP_AT,
    GROUP,
    DIRECT,
    CHANNEL,
    MEMBER_ADD,
    MEMBER_REMOVE,
    UNKNOWN;

    public static BotMessageType fromRawEventType(String raw) {
        if (raw == null || raw.isEmpty()) return UNKNOWN;
        switch (raw) {
            case "GROUP_AT_MESSAGE_CREATE":
                return GROUP_AT;
            case "GROUP_MESSAGE_CREATE":
                return GROUP;
            case "C2C_MESSAGE_CREATE":
            case "FRIEND_MESSAGE_CREATE":
                return DIRECT;
            case "AT_MESSAGE_CREATE":
            case "MESSAGE_CREATE":
                return CHANNEL;
            case "GROUP_MEMBER_ADD":
                return MEMBER_ADD;
            case "GROUP_MEMBER_REMOVE":
                return MEMBER_REMOVE;
            default:
                return UNKNOWN;
        }
    }
}
