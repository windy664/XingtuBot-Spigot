package org.windy.xingtubot.common.handler;

import com.google.gson.JsonObject;
import org.windy.xingtubot.common.auth.PermissionService;
import org.windy.xingtubot.common.command.GroupCommand;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.event.BotReplier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 消息处理器注册表：统一管理所有 MessageHandler 的注册、分发、生命周期。
 *
 * <p>按 priority 排序匹配，命中即处理并停止（先注册的同优先级按注册顺序）。
 * 所有 handle 在专属有界线程池执行，与主线程/ForkJoinPool 隔离。
 *
 * <p>同时支持 {@link MessageHandler} 和旧的 {@link GroupCommand}（通过适配器自动包装）。
 */
public class HandlerRegistry {

    private final List<MessageHandler> handlers = new ArrayList<>();
    // 观察者：在主匹配链之外「并行旁路」运行，命中也不阻断其他 handler。
    // 用于 AI 闲聊等被动响应器——它对每条消息都有机会响应，但不消费消息。
    private final List<MessageHandler> observers = new ArrayList<>();
    private final Consumer<String> logger;
    private final PermissionService permission;
    private final ThreadPoolExecutor pool;
    private org.windy.xingtubot.common.api.XingtuBotServiceImpl hookService;
    private boolean initialized = false;
    private HandlerContext readyCtx; // initAll 后缓存，供第三方延迟注册的 handler 补 init
    // 机器人群消息回显到游戏：非 null 时，命令回复会同时回显进游戏（平台侧注入，关闭则为 null）。
    private volatile Consumer<String> gameEcho;

    /**
     * 群消息监听模式：
     *   "mention" = 只响应 @机器人 的群消息（默认）
     *   "all"     = 响应所有群消息（不需要@）
     */
    private volatile String listenMode = "mention";

    public void setListenMode(String listenMode) {
        this.listenMode = listenMode == null ? "mention" : listenMode.trim().toLowerCase();
    }

    /**
     * 设置「机器人群消息回显到游戏」回调：传入纯文本（markdown 已剥离），由平台侧加前缀并广播给在线玩家。
     * 传 null 关闭回显。
     */
    public void setGameEcho(Consumer<String> gameEcho) {
        this.gameEcho = gameEcho;
    }

    public HandlerRegistry(PermissionService permission, Consumer<String> logger) {
        this.permission = permission;
        this.logger = logger;
        this.pool = new ThreadPoolExecutor(
                2, 6, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "MsgHandler-Worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    // ==================== 注册 ====================

    /**
     * 注册 MessageHandler（公开扩展入口，第三方插件经 XingtuBotService.registerHandler 走到这里）。
     * 按 priority 排序；若已 initAll 过（如第三方在我们启动后才注册），立即对该 handler 补调 init。
     */
    public HandlerRegistry register(MessageHandler handler) {
        if (handler == null) return this;
        handlers.add(handler);
        handlers.sort(Comparator.comparingInt(MessageHandler::priority));
        // 延迟注册：注册中心已初始化时，新来的 handler 也要 init（否则它的 init 永不被调）
        if (initialized && readyCtx != null) {
            try {
                handler.init(readyCtx);
            } catch (Exception e) {
                log("Handler " + handler.name() + " 延迟初始化失败: " + e.getMessage());
            }
        }
        return this;
    }

    /** 注册旧的 GroupCommand（自动适配为 MessageHandler）。 */
    public HandlerRegistry register(GroupCommand cmd) {
        return register(new GroupCommandAdapter(cmd));
    }

    /**
     * 注册观察者：对每条分发的消息都有机会响应（matches→handle），但<b>不阻断</b>主匹配链，
     * 也不被主匹配链阻断。用于 AI 闲聊这类被动响应器（与命令/群服互联并行，保持原并行监听语义）。
     * 观察者自身负责过滤（如排除已注册命令前缀），避免对命令消息误响应。
     */
    public HandlerRegistry registerObserver(MessageHandler observer) {
        if (observer == null) return this;
        observers.add(observer);
        if (initialized && readyCtx != null) {
            try {
                observer.init(readyCtx);
            } catch (Exception e) {
                log("Observer " + observer.name() + " 延迟初始化失败: " + e.getMessage());
            }
        }
        return this;
    }

    // ==================== 生命周期 ====================

    /** 初始化所有 handler（调用 init）。应在注册完成后、首次分发前调用。 */
    public void initAll(HandlerContext ctx) {
        if (initialized) return;
        initialized = true;
        this.readyCtx = ctx; // 缓存：供延迟注册的 handler 补 init
        for (MessageHandler h : handlers) {
            try {
                h.init(ctx);
            } catch (Exception e) {
                log("Handler " + h.name() + " 初始化失败: " + e.getMessage());
            }
        }
        for (MessageHandler o : observers) {
            try {
                o.init(ctx);
            } catch (Exception e) {
                log("Observer " + o.name() + " 初始化失败: " + e.getMessage());
            }
        }
    }

    /** 关闭所有 handler + 线程池。 */
    public void shutdownAll() {
        for (MessageHandler h : handlers) {
            try {
                h.shutdown();
            } catch (Exception e) {
                log("Handler " + h.name() + " 关闭失败: " + e.getMessage());
            }
        }
        for (MessageHandler o : observers) {
            try {
                o.shutdown();
            } catch (Exception e) {
                log("Observer " + o.name() + " 关闭失败: " + e.getMessage());
            }
        }
        pool.shutdownNow();
    }

    /** 设置命令 Hook 服务（可选）。 */
    public void setHookService(org.windy.xingtubot.common.api.XingtuBotServiceImpl hookService) {
        this.hookService = hookService;
    }


    // ==================== 分发 ====================

    /**
     * 分发一条群消息。命中返回 true（已提交到线程池异步处理），否则 false。
     */
    public boolean dispatch(BotMessageEvent event) {
        String msg = event.getMessage() == null ? "" : event.getMessage().trim();
        if (msg.isEmpty()) return false;

        // 记录已知群：QQ 机器人无法枚举自己加入的群，只能从收到的消息里反推。
        // 落盘后供「推送到全部群（*）」的主动消息使用。非消息事件（入群/退群等）同样带群 openid。
        if (event.isGroupMessage() && event.getGuildId() != null && !event.getGuildId().isEmpty()) {
            org.windy.xingtubot.common.queue.KnownGroupStore.getInstance().record(event.getGuildId());
        }

        // listen-mode 过滤：mention 模式下，非@的群消息默认门控（跳过）；
        // 但声明了 acceptsWithoutMention() 的处理器（如白名单/登录）仍可处理，
        // 让玩家不@机器人也能直接发「绑定」「登录」。
        // 非消息事件（GROUP_MEMBER_ADD 等）不受此门控。
        boolean mentionGated = false;
        if ("mention".equals(listenMode) && event.isGroupMessage() && !event.isGroupAtMessage()) {
            String et = event.getEventType();
            if (et == null || et.endsWith("_MESSAGE_CREATE")) {
                mentionGated = true;
            }
            // 斜杠命令（/mod、/modwatch 等）视为明确指向机器人，mention 模式下也免@放行。
            // 关键词命令（天气/运势等）仍需@，避免对群内闲聊误触发。
            String m = msg == null ? "" : msg.trim();
            if (m.startsWith("/")) {
                mentionGated = false;
            }
        }

        // 命令回复不做敏感词过滤：天气/mcmod/运势/绑定/菜单等是机器人自产内容，
        // 过滤无意义且会误伤（如模组名/简介被打码）。敏感词只作用于群服互联 + AI，
        // 在各自调用点处理。这里只做游戏回显装饰。
        final BotMessageEvent dispatchEvent;
        BotReplier replier = event.getReplier();
        if (replier != null && gameEcho != null) {
            // 必须保留 eventType（用 6 参构造）：否则 handle() 里 isGroupMessage()/isGroupAtMessage() 全失效，
            // 例如 /id 命令会因此拿不到群消息标识而不显示群 ID。
            dispatchEvent = new BotMessageEvent(
                    event.getGuildId(), event.getFormId(), msg,
                    new EchoReplier(replier, gameEcho), event.getUsername(), event.getEventType());
        } else {
            dispatchEvent = event;
        }

        // 观察者旁路：对每条消息并行运行（不阻断主链，也不被主链阻断）。
        // 观察者自身负责过滤（如 AI 排除已注册命令前缀），避免误响应命令消息。
        // 观察者不受 mention 门控——它们是"旁路"，天然需要看到所有消息（如 AI 旁听群聊语境）。
        for (MessageHandler obs : observers) {
            boolean obsHit;
            try {
                obsHit = obs.matches(msg, dispatchEvent);
            } catch (Exception e) {
                obsHit = false;
            }
            if (obsHit) {
                final MessageHandler o = obs;
                pool.execute(() -> {
                    try {
                        o.handle(msg, dispatchEvent);
                    } catch (Exception e) {
                        log("Observer " + o.name() + " 处理异常: " + e.getMessage());
                    }
                });
            }
        }

        for (MessageHandler handler : handlers) {
            // 门控：mention 模式下的非@消息，只放行声明了 acceptsWithoutMention() 的处理器
            if (mentionGated && !handler.acceptsWithoutMention()) {
                continue;
            }
            boolean hit;
            try {
                hit = handler.matches(msg, event);
            } catch (Exception e) {
                hit = false;
            }
            if (hit) {
                // 权限检查：按消息粒度判定（adminFor 默认回退 adminOnly；
                // 自定义命令等可重写为按条目鉴权，core 无需认识具体功能类型）。
                boolean needAdmin = handler.adminFor(msg);
                if (needAdmin && !permission.isAdmin(dispatchEvent.getFormId())) {
                    dispatchEvent.reply("⛔ 该指令仅管理员可用");
                    return true;
                }
                pool.execute(() -> {
                    // before hook：返回 false 表示被扩展拦截，命令不执行
                    if (hookService != null
                            && !hookService.fireBeforeCommand(handler.name(), dispatchEvent)) {
                        return;
                    }
                    try {
                        handler.handle(msg, dispatchEvent);
                    } catch (Exception e) {
                        log("Handler " + handler.name() + " 处理异常: " + e.getMessage());
                        try {
                            dispatchEvent.reply("处理出错了，稍后再试~");
                        } catch (Exception ignored) {
                        }
                    }
                    // after hook
                    if (hookService != null) {
                        hookService.fireAfterCommand(handler.name(), dispatchEvent);
                    }
                });
                return true;
            }
        }
        return false;
    }

    // ==================== 帮助菜单 ====================

    /**
     * 生成帮助菜单。自动收集所有声明了 usage 的 handler。
     * 标题昵称取自 {@link org.windy.xingtubot.common.api.BotIdentity}。
     */
    public String buildMenu(boolean isAdmin) {
        return buildMenu(isAdmin, org.windy.xingtubot.common.api.BotIdentity.getName());
    }

    /**
     * 生成帮助菜单。
     * @deprecated botName 参数已忽略，标题统一走 {@link org.windy.xingtubot.common.api.BotIdentity}；请用 {@link #buildMenu(boolean)}。
     */
    @Deprecated
    public String buildMenu(boolean isAdmin, String ignoredBotName) {
        String botName = org.windy.xingtubot.common.api.BotIdentity.getName();
        // 按分类收集条目：category → entries
        java.util.Map<String, StringBuilder> categories = new java.util.LinkedHashMap<>();
        StringBuilder admin = new StringBuilder();

        // 菜单收集横跨 handlers + observers：observer（如 AI 闲聊）也是「已注册功能」，
        // 应出现在「全部命令」菜单里。两者按相同规则收集 menuEntries + usage。
        List<MessageHandler> menuSources = new ArrayList<>(handlers);
        menuSources.addAll(observers);

        // 动态菜单条目（replies.yml 的 menu 部分 + menu=true 的自定义回复）
        for (MessageHandler h : menuSources) {
            for (MenuEntry e : h.menuEntries()) {
                if (e == null || e.trigger == null) continue;
                String label = e.label != null && !e.label.isEmpty() ? e.label : e.trigger;
                String cat = (e.category != null && !e.category.isEmpty()) ? e.category : h.category();
                if (cat.isEmpty()) cat = "📌 其他";
                if (e.adminOnly) {
                    admin.append("`").append(e.trigger).append("`　").append(label).append("\n");
                } else {
                    categories.computeIfAbsent(cat, k -> new StringBuilder())
                            .append("`").append(e.trigger).append("`　").append(label).append("\n");
                }
            }
        }

        // 静态命令条目（声明了 usage 的 handler/observer）
        for (MessageHandler h : menuSources) {
            String usage = h.usage();
            if (usage == null) continue;
            String line = "`" + usage + "`　" + h.description() + "\n";
            if (h.adminOnly()) {
                admin.append(line);
            } else {
                String cat = h.category();
                if (cat.isEmpty()) cat = "📌 其他";
                categories.computeIfAbsent(cat, k -> new StringBuilder()).append(line);
            }
        }

        StringBuilder sb = new StringBuilder("## 🤖 ").append(botName).append(" · 菜单\n");
        for (java.util.Map.Entry<String, StringBuilder> entry : categories.entrySet()) {
            if (entry.getValue().length() > 0) {
                sb.append("\n**").append(entry.getKey()).append("**\n");
                sb.append(entry.getValue());
            }
        }
        if (isAdmin && admin.length() > 0) {
            sb.append("\n> 👑 管理员专用\n\n").append(admin);
        }
        sb.append("\n> 💡 @我 + 上面任意指令即可使用");
        return sb.toString();
    }

    /**
     * 自动按已注册命令生成菜单<b>按钮键盘</b>（配合 buildMenu 文字一起发）。
     *
     * <p>规则：
     * <ul>
     *   <li>每个声明了 {@code usage()} 的命令 → 一个按钮（label=命令词）。</li>
     *   <li><b>无参命令</b>（usage 只有命令词）→ {@code enter=true}，点击直接发送执行（一键）。</li>
     *   <li><b>带参命令</b>（usage 含 {@code <…>} 或命令词后还有内容）→ {@code enter=false}，
     *       点击只把「命令 」填进输入框（草稿），等用户补参数再自己发，<b>不自动发送</b>。</li>
     *   <li>超管命令：仅 isAdmin 时纳入，按钮 {@code permission=仅管理员}（QQ 客户端层面也限制）+ 红色样式。</li>
     * </ul>
     * QQ 键盘上限 5 行×5=25 个按钮，超出的命令仍在文字菜单里列出。无可显示命令时返回 null。
     */
    public String buildMenuKeyboard(boolean isAdmin) {
        java.util.List<MessageHandler> sources = new java.util.ArrayList<>(handlers);
        sources.addAll(observers);

        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        com.google.gson.JsonArray row = new com.google.gson.JsonArray();
        int[] count = {0};
        int[] id = {1};

        // 静态命令（声明了 usage 的）
        for (MessageHandler h : sources) {
            String usage = h.usage();
            if (usage == null || usage.trim().isEmpty()) continue;
            if (h.adminOnly() && !isAdmin) continue;
            String first = usage.trim().split("\\s+")[0];
            boolean hasParam = !usage.trim().equals(first)
                    || usage.contains("<") || usage.contains("[") || usage.contains("{");
            row = addMenuButton(rows, row, id, count, first, hasParam, h.adminOnly());
            if (count[0] >= 25) break;
        }
        // 动态条目（自定义问答/菜单条目，多为无参关键词）
        if (count[0] < 25) {
            for (MessageHandler h : sources) {
                for (MenuEntry e : h.menuEntries()) {
                    if (e == null || e.trigger == null || e.trigger.isEmpty()) continue;
                    if (e.adminOnly && !isAdmin) continue;
                    row = addMenuButton(rows, row, id, count, e.trigger, false, e.adminOnly);
                    if (count[0] >= 25) break;
                }
                if (count[0] >= 25) break;
            }
        }
        if (row.size() > 0) rows.add(wrapRow(row));
        if (count[0] == 0) return null; // 没有可显示命令

        com.google.gson.JsonObject content = new com.google.gson.JsonObject();
        content.add("rows", rows);
        com.google.gson.JsonObject keyboard = new com.google.gson.JsonObject();
        keyboard.add("content", content);
        return keyboard.toString(); // 返回 JSON 字符串，避免 gson 类型跨插件失配
    }

    /** 追加一个命令按钮，满 5 个换行；返回当前行（可能是新行）。 */
    private com.google.gson.JsonArray addMenuButton(com.google.gson.JsonArray rows, com.google.gson.JsonArray row,
                                                    int[] id, int[] count, String cmd, boolean hasParam, boolean adminOnly) {
        if (count[0] >= 25) return row;
        com.google.gson.JsonObject btn = new com.google.gson.JsonObject();
        btn.addProperty("id", String.valueOf(id[0]++));

        com.google.gson.JsonObject rd = new com.google.gson.JsonObject();
        rd.addProperty("label", cmd);
        rd.addProperty("visited_label", cmd);
        rd.addProperty("style", adminOnly ? 3 : 1); // 3=红色(超管) 1=普通
        btn.add("render_data", rd);

        com.google.gson.JsonObject action = new com.google.gson.JsonObject();
        action.addProperty("type", 2);                       // 指令按钮：自动插入「@机器人 data」
        action.addProperty("data", hasParam ? cmd + " " : cmd);
        action.addProperty("enter", !hasParam);              // 无参=点了直接发；带参=只填草稿不发
        com.google.gson.JsonObject perm = new com.google.gson.JsonObject();
        perm.addProperty("type", adminOnly ? 1 : 2);         // 1=仅管理员 2=所有人
        action.add("permission", perm);
        action.addProperty("unsupport_tips", "当前版本不支持按钮，请手动输入命令");
        btn.add("action", action);

        row.add(btn);
        count[0]++;
        if (row.size() == 5) {
            rows.add(wrapRow(row));   // QQ 键盘每行须是 {"buttons":[...]} 对象
            return new com.google.gson.JsonArray();
        }
        return row;
    }

    /** QQ 键盘的一行：{"buttons":[...]}。 */
    private static com.google.gson.JsonObject wrapRow(com.google.gson.JsonArray buttons) {
        com.google.gson.JsonObject row = new com.google.gson.JsonObject();
        row.add("buttons", buttons);
        return row;
    }

    /** 是否有任意可显示的 handler。 */
    public boolean hasMenuEntries() {
        for (MessageHandler h : handlers) {
            if (h.usage() != null) return true;
        }
        return false;
    }

    /**
     * 返回所有已注册 handler 的命令前缀（usage 的第一个词 + 各 handler 经 triggers() 声明的触发词）。
     * 供 AI 模块排除已注册命令，避免 AI 重复响应。core 不认识具体功能类型。
     */
    public java.util.List<String> getManagedPrefixes() {
        java.util.List<String> prefixes = new java.util.ArrayList<>();
        for (MessageHandler h : handlers) {
            // 从 usage 提取前缀（如 "/mod <slug>" → "/mod"，"生图 <模板>" → "生图"）
            String usage = h.usage();
            if (usage != null && !usage.isEmpty()) {
                String prefix = usage.split("\\s+")[0].trim().toLowerCase();
                if (!prefix.isEmpty()) prefixes.add(prefix);
            }
            // handler 自报的额外触发词（自定义问答/命令的 trigger 等）
            for (String trigger : h.triggers()) {
                if (trigger != null && !trigger.isEmpty()) prefixes.add(trigger.toLowerCase());
            }
        }
        return prefixes;
    }

    /**
     * 这条消息是否会被某个<b>具体命令</b>主链 handler 认领——即 dispatch 时会有 handler {@code matches} 并消费它。
     *
     * <p>相比 {@link #getManagedPrefixes()}（只看 usage/triggers 声明，会漏掉靠 matches() 精确匹配却没声明前缀的
     * handler，如白名单的「登录/绑定」），本方法直接复用各 handler 真正的 {@code matches()} 作为判据，是<b>唯一可靠</b>
     * 的"这是不是命令"信号——handler 无需为此改任何声明。供并行观察者（AI 闲聊 / WindyAgent 运维 Agent）自我门控，
     * 避免超管发命令时观察者跟着抢答造成双回复。
     *
     * <p><b>排除 catch-all 兜底器</b>：群服互联(prefix 为空时)等会对<i>任意</i>消息返回 true，若也算"命令"会把观察者
     * 永久噤声。用一次性随机串差分探测：既匹配真实消息又匹配随机串的 = 兜底器，跳过；只匹配真实消息的 = 具体命令。
     *
     * @return true 表示有具体命令会处理它（观察者应让开）；false 表示无人认领（自由文本，观察者可接手）。
     */
    public boolean isHandledByCommand(String message, BotMessageEvent event) {
        if (message == null || message.trim().isEmpty()) return false;
        final String nonce = "wa_probe_" + Long.toHexString(System.nanoTime());
        for (MessageHandler h : handlers) {
            boolean hit;
            try {
                hit = h.matches(message, event);
            } catch (Exception e) {
                hit = false; // matches 抛错不当作命中，避免误噤声观察者
            }
            if (!hit) continue;
            // 差分探测：连随机串都匹配 = catch-all 兜底器（如群服互联 relay-all），不算"具体命令"
            boolean catchAll;
            try {
                catchAll = h.matches(nonce, event);
            } catch (Exception e) {
                catchAll = false;
            }
            if (!catchAll) return true;
        }
        return false;
    }

    // ==================== 工具 ====================

    private void log(String m) {
        if (logger != null) logger.accept(m);
    }

    /**
     * GroupCommand → MessageHandler 适配器。
     * priority 默认 50（命令型，比白名单/捕获低，比自定义问答/群服互联高）。
     */
    private static class GroupCommandAdapter implements MessageHandler {
        private final GroupCommand cmd;

        GroupCommandAdapter(GroupCommand cmd) {
            this.cmd = cmd;
        }

        @Override
        public boolean matches(String message, BotMessageEvent event) {
            return cmd.matches(message);
        }

        @Override
        public void handle(String message, BotMessageEvent event) {
            cmd.handle(message, event);
        }

        @Override
        public String name() {
            return cmd.name();
        }

        @Override
        public int priority() {
            return 50;
        }

        @Override
        public boolean adminOnly() {
            return cmd.adminOnly();
        }

        @Override
        public boolean adminFor(String message) {
            return cmd.adminFor(message);
        }

        @Override
        public java.util.List<String> triggers() {
            return cmd.triggers();
        }

        @Override
        public String usage() {
            return cmd.usage();
        }

        @Override
        public String description() {
            return cmd.description();
        }

        @Override
        public String category() {
            return cmd.category();
        }
    }

    /**
     * 游戏回显装饰器：在把回复发给 QQ 的同时，把文本回显进游戏。
     * markdown 回复会剥离标记转成游戏纯文本；图片/语音/视频回显占位提示；语音/embed/ark 不回显。
     */
    private static class EchoReplier implements BotReplier {
        private final BotReplier delegate;
        private final Consumer<String> echo;

        EchoReplier(BotReplier delegate, Consumer<String> echo) {
            this.delegate = delegate;
            this.echo = echo;
        }

        private void echo(String text) {
            if (text == null) return;
            String t = text.trim();
            if (t.isEmpty()) return;
            try {
                echo.accept(t);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void replyText(String text) {
            delegate.replyText(text);
            echo(text);
        }

        @Override
        public void replyImage(String imageUrl, String content) {
            delegate.replyImage(imageUrl, content);
            echo((content == null || content.trim().isEmpty()) ? "[图片]" : content + " [图片]");
        }

        @Override
        public void replyImageData(byte[] imageBytes, String content) {
            delegate.replyImageData(imageBytes, content);
            echo((content == null || content.trim().isEmpty()) ? "[图片]" : content + " [图片]");
        }

        @Override
        public void replyVoice(String voiceUrl) {
            delegate.replyVoice(voiceUrl);
        }

        @Override
        public void replyVideo(String videoUrl, String content) {
            delegate.replyVideo(videoUrl, content);
            echo((content == null || content.trim().isEmpty()) ? "[视频]" : content + " [视频]");
        }

        @Override
        public void replyEmbed(String embedJson) {
            delegate.replyEmbed(embedJson);
        }

        @Override
        public void replyMarkdown(String content, String keyboardTemplateId) {
            delegate.replyMarkdown(content, keyboardTemplateId);
            echo(stripMarkdown(content));
        }

        @Override
        public void replyKeyboard(String markdownContent, String keyboardJson) {
            delegate.replyKeyboard(markdownContent, keyboardJson);
            echo(stripMarkdown(markdownContent));
        }

        @Override
        public void replyArk(String arkJson) {
            delegate.replyArk(arkJson);
        }
    }

    /** 把 QQ markdown 卡片粗略转成游戏纯文本：去标题/引用/加粗/行内代码/图片/链接标记。 */
    static String stripMarkdown(String md) {
        if (md == null) return null;
        String[] lines = md.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String s = line;
            s = s.replaceAll("^\\s{0,3}#{1,6}\\s*", "");  // 标题 #
            s = s.replaceAll("^\\s*>\\s?", "");              // 引用 >
            s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "[图片]"); // 图片
            s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]*)\\)", "$1（$2）"); // 链接 → 文字（url）
            s = s.replace("**", "").replace("`", "");        // 加粗/行内代码
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString().trim();
    }
}
