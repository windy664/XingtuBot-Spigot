package org.windy.xingtubot.common.binding;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 一条已完成的绑定：游戏玩家名 ↔ QQ openid ↔ 真实 QQ 号。
 *
 * <p>openid 是 QQ 机器人能拿到的唯一标识；qq 是玩家声明并经头像比对确认的真实 QQ 号。
 * 取代旧的 WhiteListEntry（去掉脆弱的自增 index、无用的 code 字段）。
 */
public class BindingEntry {
    public String player;
    public String openid;
    public String qq;
    public String time;

    public BindingEntry() {
    }

    public BindingEntry(String player, String openid, String qq) {
        this.player = player;
        this.openid = openid;
        this.qq = qq;
        this.time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
