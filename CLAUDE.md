# XingtuBot-Spigot

QQ 群 ↔ Minecraft 服务器的多功能桥接机器人。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Java 8（`sourceCompatibility`/`targetCompatibility` = 8） |
| 构建工具 | Gradle（`com.github.johnrengelman.shadow` 7.1.2 打包 fat jar） |
| 包组织 | `org.windy.xingtubot`（group = `org.windy`，version = `2.2.1`） |

## 平台支持（三端合一）

`common-core` 产出单一 `XingtuBot.jar`，同时支持三类服务端：

| 端 | SDK | 适配器主类 |
|----|-----|-----------|
| Bukkit/Paper/Spigot | `spigot-api:1.12.2-R0.1-SNAPSHOT` | `org.windy.xingtubot.bukkit.XingtuBot` |
| Velocity | `velocity-api:3.1.1` | `org.windy.xingtubot.velocity.XingtuBotVelocity` |
| BungeeCord | `bungeecord-api:1.20-R0.2-SNAPSHOT` | `org.windy.xingtubot.bungee.XingtuBotBungeeCord` |

- 运行期由平台提供 SDK（`compileOnly`），严禁将平台 SDK shade 进 jar。
- 附属插件（`xt-*`）均为 "二合一 jar"，通过 `compileOnly project(':common-core')` 引用核心，运行时从主插件 classloader 解析。

## QQ 机器人协议

两种通信模式，由 `qq-protocol` 配置切换：

| 模式 | 协议 | WebSocket 客户端 |
|------|------|-----------------|
| `official`（默认） | QQ 官方 WebSocket 网关 + OpenAPI | `QQGatewayClient` |
| `onebot11` | OneBot 11（正向 WS + HTTP API） | `OneBot11Messenger` |

核心处理类：`org.windy.xingtubot.common.poll.QqBot`（平台无关的事件解析与分发）。

## 核心依赖（shade 进 jar）

| 库 | 用途 | 重定位包 |
|----|------|---------|
| `Java-WebSocket:1.5.3` | 官方网关 WebSocket 连接 | 不重定位 |
| `Gson:2.10.1` | JSON 解析 | `org.lib.gson` |
| `SnakeYaml:1.33` | YAML 配置解析 | `org.lib.snakeyaml` |
| `ZXing:core:3.5.2` | 二维码生成 | `org.lib.zxing` |
| `Jedis:5.1.0` | Redis 跨服信道 | `org.lib.jedis` |
| `commons-pool2` | Jedis 连接池 | `org.lib.pool2` |

## 项目结构

```
common-core/     核心插件：QQ 机器人引擎 + 跨服信道 + 命令/事件框架
xt-auth/         头像比对白名单、群内登录、免密信任期（依赖 PacketEvents）
xt-chatlink/     QQ↔游戏双向聊天桥接、敏感词过滤
xt-group/        迎送词、自定义问答（replies.yml）、自定义命令（commands.yml）
xt-ai/           LLM 群聊对话（提供 AiService 供其他附属软依赖）
xt-modquery/     Modrinth/MCMOD/CurseForge 模组搜索、更新订阅（内置 Jsoup）
xt-fun/          天气、运势、随机图片、文字生图
xt-github/       GitHub 仓库 Issue/PR/Release 推送
```

## 跨服架构

- **大脑/手脚模式**：`server-role` 配置决定。`auto` 下检测到代理自动为 `slave`。
  - 大脑（master）：本地跑 QQ bot。
  - 手脚（slave）：不跑 bot，由代理大脑统一接管。
- **Redis 跨服信道**：`CrossServerChannelFactory` + `RedisChannel`（Jedis）实现跨服消息广播。

## 构建命令

```bash
# 主插件 + 所有附属 jar
./gradlew shadowJar

# 产物位置
# common-core/build/libs/XingtuBot.jar
# xt-*/build/libs/XingtuBot-*.jar
```

## 关键约定

1. **common-core 必须保持平台中立**：QQ/bot/事件/业务逻辑全部在 `common-core` 内，平台相关代码通过 `PlatformAdapter` / `BotLogger` 接口 + 各端实现类隔离。
2. **附属插件不 shade 核心库**：gson/jedis/snakeyaml 等由核心 `XingtuBot.jar` 运行时提供，附属只 `compileOnly`。
3. **配置入口**：Bukkit 端读 `config.yml`，Velocity 端读 `velocity-config.yml`，BungeeCord 端读 `bungee-config.yml`，统一经 `BotConfig` 接口读取。
4. **事件分发**：QQ 原始事件 → `QqBot.onRawEvent` → `BotMessageEvent` → `HandlerRegistry` 分发到各 `MessageHandler`。

## Claude generated files

Claude Code 在会话中生成的中间文档，统一存放在 `docs/` 根路径下，按以下前缀命名。这些文件已被 `.gitignore` 忽略，不需纳入版本控制：

| 文件 | 用途 |
|------|------|
| `PLAN_*.md` | 实现方案，在编码前输出，供确认后再进入实现 |
| `IMPLEMENTATION_*.md` | 实现过程中的步骤记录与进度 |
| `FIX_*.md` | bug 修复记录（根因、修复、验证） |
| `DEBUG_*.md` | 调试过程记录（排查路径、关键证据） |
| `MIGRATION_*.md` | 迁移/重构类变更记录 |

**用途**：在不污染 git 历史的前提下保留会话上下文，方便中断后恢复工作；同一次会话中后续轮次可直接引用上一次产出的计划/记录。

**规则**：
- 文档根路径：`docs/`（相对项目根目录）。
- 文件名带顺序或主题后缀，例如 `PLAN_redis-migration.md`、`FIX_gateway-reconnect-loop.md`。
- 完成对应任务后可按需清理，不强制保留。
- 不在这些文档中存储环境 secrets（token、密钥等）。
