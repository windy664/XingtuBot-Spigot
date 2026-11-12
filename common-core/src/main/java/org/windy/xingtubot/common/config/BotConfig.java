package org.windy.xingtubot.common.config;

import org.windy.xingtubot.common.runtime.BotRuntimeState;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 平台无关的配置读取接口。
 * Spigot 用 FileConfiguration 实现，Velocity 用 YAML 实现。
 */
public interface BotConfig {
    String getString(String path, String def);

    boolean getBoolean(String path, boolean def);

    int getInt(String path, int def);

    List<String> getStringList(String path);

    /** 读取一个 String→String 的 map 段（如 mod-aliases）。默认返回空 map。 */
    default Map<String, String> getStringMap(String path) {
        return Collections.emptyMap();
    }

    /**
     * 读取一个 map 列表（如 [{repo: "xx", notify: ["yy"]}, ...]）。
     * Velocity 端用 SnakeYAML 原生解析；Spigot 端手动遍历 List&lt;Map&gt;。
     * 默认返回空列表。
     */
    default List<Map<String, Object>> getStringMapList(String path) {
        return Collections.emptyList();
    }

    /**
     * 读取配置字符串并替换 {bot} 占位符为机器人昵称（取自 {@link org.windy.xingtubot.common.runtime.BotRuntimeState}）。
     * 用于提示词等需要引用机器人名字的配置项。
     */
    default String getStringResolved(String path, String def) {
        String val = getString(path, def);
        return val.replace("{bot}", BotRuntimeState.getBotName());
    }

    /**
     * 重新从磁盘读取配置（可选）。
     *
     * <p>默认是空实现——不是所有实现都支持热重载。支持的实现（如
     * {@link YamlBotConfig}）应当重新读文件并把新值生效。
     *
     * <p>用于「改完配置不想重启服务器」的场景：调用方在 reload() 之后
     * 需要自己把读到的值重新应用到各个组件上。
     */
    default void reload() {
        // 不支持热重载的实现什么都不做
    }
}
