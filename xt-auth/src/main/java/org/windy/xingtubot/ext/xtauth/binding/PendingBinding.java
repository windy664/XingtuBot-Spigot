package org.windy.xingtubot.ext.xtauth.binding;

/**
 * 待验证记录（内存，未持久化）：玩家已在游戏内声明 QQ 号、系统已取到该 QQ 头像指纹，
 * 等待玩家在群里 {@code @机器人 发送「绑定」} 完成确认。
 *
 * <p><b>凭证 = 头像比对</b>：玩家声明 QQ 号时取「该 QQ 号的头像」({@code headimg_dl})，
 * 群里发「绑定」时取「发送者 openid 的头像」({@code qqapp/{appId}/{openid}})。这两条链接对
 * 同一个人返回的是<b>同一张源图</b>（实测 dHash 距离 0），据此把 openid 关联到玩家。
 *
 * <p>头像必须是可用头像（非纯色默认、非占位小图）才会建立本记录——否则比对没有意义。
 */
public class PendingBinding {
    public final String player;
    public final String qq;
    public final long qqAvatarHash;     // 声明 QQ 号的头像 dHash（建记录时已确保头像可用）
    public final long createdAt;

    public PendingBinding(String player, String qq, long qqAvatarHash) {
        this.player = player;
        this.qq = qq;
        this.qqAvatarHash = qqAvatarHash;
        this.createdAt = System.currentTimeMillis();
    }

    public boolean isExpired(long ttlMillis) {
        return System.currentTimeMillis() - createdAt > ttlMillis;
    }
}
