package org.windy.xingtubot.common.module.capability;

import java.util.List;

/**
 * 平台能力：查询各子服在线情况（查服命令用）。
 *
 * <ul>
 *   <li>Velocity：遍历 {@code proxy.getAllServers()} + 在线玩家归属。</li>
 *   <li>Spigot：单机无子服概念，通常为 null → module-admin 不注册查服命令。</li>
 * </ul>
 *
 * <p>只返回结构化快照，文本排版由消费方（module-admin 的查服命令）负责，
 * 保证功能模块平台中立。
 */
public interface ServerQuery {

    /** 各子服在线快照。 */
    List<ServerInfo> servers();

    /** 单个子服的在线信息。 */
    final class ServerInfo {
        public final String name;
        public final List<String> players;

        public ServerInfo(String name, List<String> players) {
            this.name = name;
            this.players = players;
        }

        public int count() {
            return players == null ? 0 : players.size();
        }
    }
}
