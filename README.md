# XingtuBot-Spigot

QQ 群 ↔ Minecraft 服务器的多功能桥接机器人。

## 功能

| 模块 | 功能 |
|------|------|
| **xt-auth** | 头像比对白名单绑定、群内登录、免密信任期 |
| **xt-chatlink** | QQ ↔ 游戏双向聊天、敏感词过滤 |
| **xt-modquery** | Modrinth / MCMOD / CurseForge 模组搜索、更新订阅、新模组发现 |
| **xt-ai** | LLM 群聊对话（人格设定、自主参与、多模态） |
| **xt-group** | 迎送词、自定义问答（replies.yml）、自定义命令（commands.yml） |
| **xt-fun** | 天气、运势、随机图片、文字生图 |
| **xt-github** | GitHub 仓库 Issue/PR/Release 推送 |

## 部署

**单机 Bukkit：** jar 放入 `plugins/`，填 `openapi-app-id` 和 `openapi-client-secret`，重启。

**代理 + 子服：** Velocity 当大脑跑 bot，Bukkit 子服自动探测代理（ProxyDetector），跨服走 Redis。

详见 [Wiki](https://github.com/windy664/XingtuBot-Spigot/wiki)。

## 环境

- Java 8+
- Minecraft 1.12+（Spigot/Paper）
- Velocity 3.x 或 BungeeCord（代理端，可选）
- [QQ 开放平台](https://q.qq.com) 机器人

## 项目结构

```
common-core/    核心插件（三端合一，产出 XingtuBot.jar）
xt-*/           附属插件（按需安装）
```
