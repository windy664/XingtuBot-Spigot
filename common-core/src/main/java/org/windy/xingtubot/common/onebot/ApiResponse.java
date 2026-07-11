package org.windy.xingtubot.common.onebot;

/**
 * OneBot API 调用的通用响应包装。
 *
 * @param <T> 成功时的数据类型
 */
public final class ApiResponse<T> {

    private final int code;
    private final T data;
    private final String message;
    private final boolean ok;

    public ApiResponse(int code, T data, String message, boolean ok) {
        this.code = code;
        this.data = data;
        this.message = message;
        this.ok = ok;
    }

    public int code() {
        return code;
    }

    public T data() {
        return data;
    }

    public String message() {
        return message;
    }

    public boolean ok() {
        return ok;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, data, null, true);
    }

    public static <T> ApiResponse<T> failure(int code, String message) {
        return new ApiResponse<>(code, null, message, false);
    }

    // ==================== 内部值类 ====================

    public static final class GroupInfo {
        public final long groupId;
        public final String groupName;
        public final int memberCount;

        public GroupInfo(long groupId, String groupName, int memberCount) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.memberCount = memberCount;
        }
    }

    public static final class UserInfo {
        public final long userId;
        public final String nickname;
        public final String card;

        public UserInfo(long userId, String nickname, String card) {
            this.userId = userId;
            this.nickname = nickname;
            this.card = card;
        }
    }

    /** 消息信息（发送消息后返回）。 */
    public static final class MessageInfo {
        public final long messageId;
        public final long time;

        public MessageInfo(long messageId, long time) {
            this.messageId = messageId;
            this.time = time;
        }
    }
}
