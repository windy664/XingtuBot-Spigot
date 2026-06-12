# 星途QQ机器人插件

多模块结构，同时支持 Spigot/Paper 与 Velocity 两个平台。

## 模块划分

| 模块 | 说明 | 依赖 |
|------|------|------|
| `common`   | 平台无关核心：WebSocket、消息事件、AI 调用、敏感词、MCMOD 搜索、配置/日志抽象 | java-websocket / gson |
| `spigot`   | Spigot/Paper 插件：白名单、群服互联、AI 聊天 | common + spigot-api（+ AuthMe） |
| `velocity` | Velocity 代理端插件：AI 聊天、查服、MCMOD 搜索 | common + velocity-api |
| `bundle`   | 聚合打包，产出二合一 jar | common + spigot + velocity |

## 构建

```bash
./gradlew :bundle:shadowJar
```

产物：`bundle/build/libs/XingtuBot-<version>.jar`

这是一个 **二合一 jar**：

- 放进 Spigot/Paper 服务端 → 通过 `plugin.yml` 作为 Bukkit 插件加载。
- 放进 Velocity 代理端 → 通过 `velocity-plugin.json`（注解处理器生成）作为代理插件加载。

两端只会各自加载对应入口类，互不干扰。第三方库（java-websocket / gson / snakeyaml）已重定位，避免与平台自带版本冲突。

jar 内只有一份共享的 `config.yml`：顶部的 `WebSocket` / `server-name` / `HeartbeatSeconds` / `deepseek-*` 两端通用，白名单、群服互联等仅 Spigot 端读取。两个平台各自把它释放到自己的数据目录后独立编辑。

## 说明

- AuthMe 为 Spigot 端白名单模块的必要前置。
- API Key、WebSocket 地址、服务器标识等均在各自的 `config.yml` 中配置，不再硬编码。
