# 昕途QQ机器人插件 XingtuBot

QQ 官方机器人 × Minecraft 群服互通插件。支持 Spigot/Paper 单服与 Velocity 群组服。
通过 QQ 官方 WebSocket 网关直连，全程出站，**无需公网 IP、无需备案**。

> 📦 本文档讲**有哪些功能、怎么用**；安装与配置见下文「四、配置速查」与「五、架构 → 构建」。

---

## 一、群内命令（@机器人 发送）

### 🎮 通用功能（人人可用）

| 发送 | 功能 |
|------|------|
| `菜单` / `帮助` / `help` | 显示功能菜单（自动列出可用命令，超管多显示管理项） |
| `测试` / `/demo` | 富消息演示（文本+图片+Markdown） |
| `天气 城市` | 查询实时天气，如 `天气 北京` |
| `运势` | 今日运势（当天固定，含幸运值进度条） |
| `骰子` / `roll` | 掷骰 1~100 |
| `选择 A 还是 B` | 帮你随机做选择 |
| `来张图` / `随机图片` / `来点图` / `二次元` | 随机二次元图片 |
| `/查服` | 查看全服在线情况 |
| 其它非指令消息 | @机器人 发的普通话会直接同步进游戏服务器（群服互联） |
| `/mod 关键词` | 搜索 MCMOD 模组，再回复**序号**看详情（60秒内有效） |

### 🔑 白名单/登录（由 xt-auth 扩展插件提供）

| 流程 | 说明 |
|------|------|
| 进服未绑定 | 游戏内被锁定，提示在聊天框输入自己的 **QQ 号**（系统下载该 QQ 头像登记） |
| 群里发 `绑定` | 机器人取**发送者 openid 的头像**与登记记录比对，命中即关联 openid↔角色，绑定+解锁 |
| 群里发 `登录` | 已绑定者免密码登录（每次进服需重新触发） |

> **绑定靠头像比对**：「QQ号头像」(`headimg_dl`) 与「openid头像」(`qqapp/{appId}/{openid}`) 对同一人返回同一张源图（实测 dHash 距离 0）。
> ⚠️ 必须正确配置 `openapi-app-id`，否则 openid 头像接口会回落成默认企鹅占位图、所有绑定失败。
> ⚠️ 权衡：QQ 头像公开可下载，理论上可被改成同图顶替；要强认证请改用验证码方案。

### 👑 管理命令（仅超管，按 openid 鉴权）

| 发送 | 功能 |
|------|------|
| `绑定列表` | 列出所有白名单绑定 |
| `查绑定 玩家名/QQ号` | 查询某人的绑定信息 |
| `解绑 玩家名` | 解除某玩家的白名单绑定 |
| `执行 服务器标识 命令` | 让指定子服后台执行命令并回传输出，如 `执行 lobby say 大家好`；`执行 all <命令>` 广播全部 |

> 超管在菜单里能额外看到「管理员专用」栏；非超管使用管理命令会被拒绝。

---

## 二、后台控制台命令

### Velocity 端：`vxtb <子命令>`

| 命令 | 功能 |
|------|------|
| `vxtb status` | 查看连接状态 |
| `vxtb connect` / `disconnect` | 手动连接/断开网关 |
| `vxtb captureid` | **获取 openid**：开启后让目标用户群里 @机器人发句话，其 openid 打印到控制台（用于配置超管） |

### Spigot 端：`/xtb <子命令>`

| 命令 | 功能 |
|------|------|
| `/xtb reload` | 重载配置 |
| `/xtb connect` | 手动重连 |
| `/xtb reply <内容>` | 回复最近一条群消息 |

---

## 三、怎么设置超管

1. Velocity 后台敲 `vxtb captureid`
2. 让目标用户群里 @机器人 发任意一句话
3. 控制台打印出他的 openid
4. 填进 `config.yml` 的 `admin-openids` 列表 → 重启生效

---

## 四、配置速查（config.yml）

| 配置 | 说明 |
|------|------|
| `bot-mode` | `gateway`(QQ 官方 WebSocket 网关) / `off` |
| `openapi-app-id` / `openapi-client-secret` | QQ 机器人 AppID / AppSecret |
| ~~`bot-name`~~ | 已移除：机器人昵称连接后自动从 QQ 官方 API 获取（`{bot}` 占位符即用该昵称） |
| `reply-mode` | `text` / `voice` / `text+voice`（语音用免费 Edge TTS） |
| `admin-openids` | 超管 openid 列表 |
| `whitelist-enable` | 是否启用白名单 |
| `whitelist-role` | `auto` / `local`(单服) / `slave`(子服，由 Velocity 主导) |
| `storage-type` | `json` / `sqlite` / `mysql`（多子服推荐 mysql） |
| `mcmod-markdown` | mcmod 详情用 Markdown 卡片（需机器人原生 markdown 权限） |

> 配置**自动更新**：新版本新增的配置项，重启后自动补充到你的 config.yml（保留原值与注释）。

---

## 五、架构

```
QQ 官方 WebSocket 网关 ⇄ 插件（收消息：长连接出站，无需公网/备案）
插件 ──OpenAPI 出站──> QQ（发消息：文本/图片/Markdown/语音）

多子服：Velocity(大脑) ⇄ PluginMessage ⇄ Spigot子服(手脚)
```

- **收消息**：插件主动连 QQ 官方 WebSocket 网关，事件实时推送（不需公网/备案）
- **发消息**：插件 → QQ OpenAPI（富媒体经图床）
- **跨服**：Velocity 当大脑（收群消息+头像比对绑定+数据库），Spigot 子服当手脚（执行游戏内操作）
- **登录**：自研锁（无 AuthMe、免密码），未登录玩家原地冻结，群里发「登录」解锁

### 模块划分

核心做薄，花哨功能拆成独立**附属扩展插件**（各自独立 jar + 独立 config），按需安装。

**主插件（bundle 产出，必装）**

| 模块 | 说明 |
|------|------|
| `common-core` | 平台无关核心：通信、绑定、指令注册中心、敏感词、占位符、扩展 API |
| `spigot`      | Spigot/Paper 入口：群服互联手脚、占位符解析 |
| `velocity`    | Velocity 代理入口：机器人大脑、群指令、跨服大脑 |
| `bungeecord`  | BungeeCord 代理入口（与 velocity 功能对等） |
| `bundle`      | 聚合打包上述四者，产出多合一主 jar |

**附属扩展插件（`xt-*`，按需安装，依赖主插件）**

| 插件 | 功能 |
|------|------|
| `xt-auth`     | 白名单 + 登录（头像比对绑定、平台原生锁、加群二维码地图） |
| `xt-chatlink` | 群服互联双向聊天桥（QQ↔游戏、[点击回复] 按钮） |
| `xt-group`    | 群内功能（菜单、自定义问答/回复等） |
| `xt-fun`      | 娱乐功能（天气、运势、骰子、随机图） |
| `xt-modquery` | MCMOD / Modrinth 模组查询 |
| `xt-github`   | GitHub / Gitee 仓库追踪推送 |
| `xt-ai`       | AI 对话 |

> 扩展走与内置同一套注册中心（dogfooding），第三方可照此开发自己的扩展。

### 构建

```bash
./gradlew :bundle:shadowJar      # 主插件 → bundle/build/libs/XingtuBot-<version>.jar
./gradlew :xt-auth:shadowJar     # 各附属插件 → xt-<name>/build/libs/XingtuBot-<Name>-<version>.jar
```
主 jar 是**多合一 jar**：放 Spigot 走 `plugin.yml`、放 Velocity 走 `velocity-plugin.json`、放 BungeeCord 走 `bungee.yml`，各端加载对应入口，互不干扰。附属插件运行期从主插件 classloader 解析 `common-core`（`compileOnly`，绝不 shade）。
