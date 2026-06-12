# 星途QQ机器人插件

多模块结构，同时支持 Spigot/Paper 与 Velocity 两个平台。

## 模块划分

| 模块 | 说明 | 依赖 |
|------|------|------|
| `common`   | 平台无关核心：WebSocket、消息事件、AI 调用、敏感词、MCMOD 搜索、配置/日志抽象 | java-websocket / gson / okhttp |
| `spigot`   | Spigot/Paper 插件：白名单、群服互联、AI 聊天 | common + spigot-api（+ AuthMe） |
| `velocity` | Velocity 代理端插件：AI 聊天、查服、MCMOD 搜索 | common + velocity-api |

## 构建

```bash
./gradlew shadowJar
```

产物：
- Spigot：`spigot/build/libs/XingtuBot.jar`
- Velocity：`velocity/build/libs/XingtuBotVelocity.jar`

## 说明

- AuthMe 为 Spigot 端白名单模块的必要前置。
- API Key、WebSocket 地址、服务器标识等均在各自的 `config.yml` 中配置，不再硬编码。
