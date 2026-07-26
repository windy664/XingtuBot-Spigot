package org.windy.xingtubot.common.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.event.MessageReply;
import org.windy.xingtubot.common.queue.KnownGroupStore;
import org.windy.xingtubot.common.runtime.XingtuBotServiceImpl;

import org.windy.xingtubot.common.service.SensitiveFilter;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Central registry and dispatcher for bot message handlers.
 */
public class HandlerRegistry {

    private final List<BotMessageHandler> handlers = new ArrayList<>();
    private final List<BotMessageHandler> observers = new ArrayList<>();
    private final Consumer<String> logger;
    private final PermissionChecker permission;
    private final ThreadPoolExecutor pool;
    private org.windy.xingtubot.common.runtime.XingtuBotServiceImpl hookService;
    private boolean initialized = false;
    private HandlerContext readyCtx;
    private volatile Consumer<String> gameEcho;
    // 敏感词过滤：指定 handler name 列表，这些 handler 的输出自动走敏感词过滤
    private volatile SensitiveFilter sensitiveFilter;
    private volatile Set<String> sensitiveFilterHandlers = Collections.emptySet();
    // 当前正在加载的模块显示名（由 ModuleLoader 在 onEnable 前设置），
    // handler 注册时若自身 category 为空则自动继承。
    private volatile String currentModuleDisplayName;

    public HandlerRegistry(PermissionChecker permission, Consumer<String> logger) {
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

    public void setGameEcho(Consumer<String> gameEcho) {
        this.gameEcho = gameEcho;
    }

    /**
     * 设置敏感词过滤：filter=过滤器实例，handlerNames=需要过滤的 handler name 列表。
     * 列表中的 handler 发出的所有文本/Markdown 回复都会自动过敏感词。
     */
    public void setSensitiveFilter(SensitiveFilter filter, List<String> handlerNames) {
        this.sensitiveFilter = filter;
        Set<String> set = new HashSet<>();
        if (handlerNames != null) {
            for (String h : handlerNames) {
                if (h != null && !h.trim().isEmpty()) set.add(h.trim().toLowerCase());
            }
        }
        this.sensitiveFilterHandlers = set;
    }

    public void setHookService(org.windy.xingtubot.common.runtime.XingtuBotServiceImpl hookService) {
        this.hookService = hookService;
    }

    /** ModuleLoader 在每个模块 onEnable 前调用，设置当前模块的菜单分类名。 */
    public void setCurrentModuleDisplayName(String displayName) {
        this.currentModuleDisplayName = displayName;
    }

    public HandlerRegistry register(BotMessageHandler handler) {
        if (handler == null) return this;
        // 若 handler 自身未设置 category，自动继承当前模块的显示名
        if (currentModuleDisplayName != null && !currentModuleDisplayName.isEmpty()) {
            String cat = handler.category();
            if (cat == null || cat.isEmpty()) {
                handler = new ModuleCategoryWrapper(handler, currentModuleDisplayName);
            }
        }
        handlers.add(handler);
        handlers.sort(Comparator.comparingInt(BotMessageHandler::priority));
        initLate(handler, "Handler");
        return this;
    }

    public HandlerRegistry register(BotCommand command) {
        if (command == null) return this;
        return register(new BotCommandHandler(command, currentModuleDisplayName));
    }

    public HandlerRegistry registerObserver(BotMessageHandler observer) {
        if (observer == null) return this;
        observers.add(observer);
        initLate(observer, "Observer");
        return this;
    }

    private void initLate(BotMessageHandler handler, String kind) {
        if (!initialized || readyCtx == null) return;
        try {
            handler.init(readyCtx);
        } catch (Exception e) {
            log(kind + " " + handler.name() + " delayed init failed: " + e.getMessage());
        }
    }

    public void initAll(HandlerContext ctx) {
        if (initialized) return;
        initialized = true;
        this.readyCtx = ctx;
        for (BotMessageHandler handler : handlers) {
            init(handler, "Handler", ctx);
        }
        for (BotMessageHandler observer : observers) {
            init(observer, "Observer", ctx);
        }
    }

    private void init(BotMessageHandler handler, String kind, HandlerContext ctx) {
        try {
            handler.init(ctx);
        } catch (Exception e) {
            log(kind + " " + handler.name() + " init failed: " + e.getMessage());
        }
    }

    public void shutdownAll() {
        for (BotMessageHandler handler : handlers) {
            shutdown(handler, "Handler");
        }
        for (BotMessageHandler observer : observers) {
            shutdown(observer, "Observer");
        }
        pool.shutdownNow();
    }

    private void shutdown(BotMessageHandler handler, String kind) {
        try {
            handler.shutdown();
        } catch (Exception e) {
            log(kind + " " + handler.name() + " shutdown failed: " + e.getMessage());
        }
    }

    public boolean dispatch(BotMessageEvent event) {
        String msg = event.getMessage() == null ? "" : event.getMessage().trim();
        if (msg.isEmpty()) return false;

        if (event.isGroupMessage()
                && event.getConversationId() != null
                && !event.getConversationId().isEmpty()) {
            KnownGroupStore.getInstance().record(event.getConversationId());
        }

        boolean mentionGated = isMentionGated(event, msg);
        boolean isAdmin = permission.isAdmin(event.getSenderId());
        log("[Dispatch] msg=" + (msg.length() > 30 ? msg.substring(0, 30) + "..." : msg)
                + " mentionGated=" + mentionGated + " isAdmin=" + isAdmin
                + " senderId=" + event.getSenderId());
        BotMessageEvent dispatchEvent = withGameEcho(event, msg);

        // 命令消息不走观察者：防止 AI 等 observer 与命令处理器并行抢答
        if (!isCommandMessage(msg, dispatchEvent, mentionGated)) {
            dispatchObservers(msg, dispatchEvent);
        }

        for (BotMessageHandler handler : handlers) {
            if (mentionGated && !handler.acceptsWithoutMention()) {
                continue;
            }
            if (!matches(handler, msg, dispatchEvent)) {
                continue;
            }
            log("[Dispatch] matched handler=" + handler.name()
                    + " adminFor=" + handler.adminFor(msg));
            if (handler.adminFor(msg) && !isAdmin) {
                dispatchEvent.reply("该指令仅管理员可用");
                return true;
            }
            pool.execute(() -> executeHandler(handler, msg, dispatchEvent));
            return true;
        }
        log("[Dispatch] no handler matched, msg will fall through");
        return false;
    }

    /**
     * 判断消息是否为已注册命令：匹配任意一个 handler 且该 handler 非 catch-all。
     * 命令消息跳过观察者分发，防止 AI 等 observer 与命令处理器并行抢答。
     */
    private boolean isCommandMessage(String msg, BotMessageContext event, boolean mentionGated) {
        String nonce = "cmd_probe_" + Long.toHexString(System.nanoTime());
        for (BotMessageHandler handler : handlers) {
            if (mentionGated && !handler.acceptsWithoutMention()) continue;
            if (!matches(handler, msg, event)) continue;
            // catch-all 型 handler（如 GroupChatHandler）对任意消息都匹配，需排除
            if (!matches(handler, nonce, event)) return true;
        }
        return false;
    }

    private boolean isMentionGated(BotMessageEvent event, String msg) {
        if (!event.isGroupMessage() || event.isGroupAtMessage()) {
            return false;
        }
        // 管理员消息不做 mention 门控：允许直接发 "执行 xxx" 等超管命令
        if (permission.isAdmin(event.getSenderId())) {
            return false;
        }
        String eventType = event.getEventType();
        boolean gated = eventType == null || eventType.endsWith("_MESSAGE_CREATE");
        return gated && !msg.startsWith("/");
    }

    private BotMessageEvent withGameEcho(BotMessageEvent event, String msg) {
        MessageReply reply = event.getReply();
        Consumer<String> echo = this.gameEcho;
        if (reply == null || echo == null) {
            return event;
        }
        BotMessageEvent wrapped = new BotMessageEvent(
                event.getConversationId(), event.getSenderId(), msg,
                new EchoReply(reply, echo), event.getUsername(), event.getEventType());
        wrapped.setImageUrls(event.getImageUrls());
        return wrapped;
    }

    private void dispatchObservers(String msg, BotMessageEvent event) {
        for (BotMessageHandler observer : observers) {
            if (!matches(observer, msg, event)) {
                continue;
            }
            BotMessageEvent evt = maybeWrapWithFilter(observer.name(), event);
            pool.execute(() -> {
                try {
                    observer.handle(msg, evt);
                } catch (Exception e) {
                    log("Observer " + observer.name() + " failed: " + e.getMessage());
                }
            });
        }
    }

    private void executeHandler(BotMessageHandler handler, String msg, BotMessageEvent event) {
        BotMessageEvent evt = maybeWrapWithFilter(handler.name(), event);
        if (hookService != null && !hookService.fireBeforeCommand(handler.name(), evt)) {
            return;
        }
        try {
            handler.handle(msg, evt);
        } catch (Exception e) {
            log("Handler " + handler.name() + " failed: " + e.getMessage());
            try {
                event.reply("处理出错了，稍后再试");
            } catch (Exception ignored) {
            }
        }
        if (hookService != null) {
            hookService.fireAfterCommand(handler.name(), event);
        }
    }

    private boolean matches(BotMessageHandler handler, String msg, BotMessageContext event) {
        try {
            return handler.matches(msg, event);
        } catch (Exception e) {
            return false;
        }
    }

    public String buildMenu(boolean isAdmin) {
        String botName = XingtuBotServiceImpl.runtime().getBotName();
        Map<String, StringBuilder> categories = new LinkedHashMap<>();
        StringBuilder admin = new StringBuilder();

        List<BotMessageHandler> menuSources = new ArrayList<>(handlers);
        menuSources.addAll(observers);

        for (BotMessageHandler handler : menuSources) {
            for (MenuEntry entry : handler.menuEntries()) {
                if (entry == null || entry.trigger == null) continue;
                String label = entry.label != null && !entry.label.isEmpty() ? entry.label : entry.trigger;
                String category = entry.category != null && !entry.category.isEmpty() ? entry.category : handler.category();
                if (category.isEmpty()) category = "其他";
                String line = "`" + entry.trigger + "`  " + label + "\n";
                if (entry.adminOnly) {
                    admin.append(line);
                } else {
                    categories.computeIfAbsent(category, k -> new StringBuilder()).append(line);
                }
            }
        }

        for (BotMessageHandler handler : menuSources) {
            String usage = handler.usage();
            if (usage == null || usage.trim().isEmpty()) continue;
            String line = "`" + usage + "`  " + handler.description() + "\n";
            if (handler.adminOnly()) {
                admin.append(line);
            } else {
                String category = handler.category();
                if (category == null || category.isEmpty()) category = "其他";
                categories.computeIfAbsent(category, k -> new StringBuilder()).append(line);
            }
        }

        StringBuilder sb = new StringBuilder("## ").append(botName).append(" · 菜单\n");
        for (Map.Entry<String, StringBuilder> entry : categories.entrySet()) {
            if (entry.getValue().length() > 0) {
                sb.append("\n**").append(entry.getKey()).append("**\n");
                sb.append(entry.getValue());
            }
        }
        if (isAdmin && admin.length() > 0) {
            sb.append("\n> 管理员专用\n\n").append(admin);
        }
        sb.append("\n> @我 + 上面任意指令即可使用");
        return sb.toString();
    }

    public String buildMenuKeyboard(boolean isAdmin) {
        List<BotMessageHandler> sources = new ArrayList<>(handlers);
        sources.addAll(observers);

        JsonArray rows = new JsonArray();
        JsonArray row = new JsonArray();
        int[] count = {0};
        int[] id = {1};

        for (BotMessageHandler handler : sources) {
            String usage = handler.usage();
            if (usage == null || usage.trim().isEmpty()) continue;
            if (handler.adminOnly() && !isAdmin) continue;
            String first = usage.trim().split("\\s+")[0];
            boolean hasParam = !usage.trim().equals(first)
                    || usage.contains("<") || usage.contains("[") || usage.contains("{");
            row = addMenuButton(rows, row, id, count, first, hasParam, handler.adminOnly());
            if (count[0] >= 25) break;
        }

        if (count[0] < 25) {
            for (BotMessageHandler handler : sources) {
                for (MenuEntry entry : handler.menuEntries()) {
                    if (entry == null || entry.trigger == null || entry.trigger.isEmpty()) continue;
                    if (entry.adminOnly && !isAdmin) continue;
                    row = addMenuButton(rows, row, id, count, entry.trigger, false, entry.adminOnly);
                    if (count[0] >= 25) break;
                }
                if (count[0] >= 25) break;
            }
        }

        if (row.size() > 0) rows.add(wrapRow(row));
        if (count[0] == 0) return null;

        JsonObject content = new JsonObject();
        content.add("rows", rows);
        JsonObject keyboard = new JsonObject();
        keyboard.add("content", content);
        return keyboard.toString();
    }

    private JsonArray addMenuButton(JsonArray rows, JsonArray row, int[] id, int[] count,
                                    String cmd, boolean hasParam, boolean adminOnly) {
        if (count[0] >= 25) return row;
        JsonObject btn = new JsonObject();
        btn.addProperty("id", String.valueOf(id[0]++));

        JsonObject render = new JsonObject();
        render.addProperty("label", cmd);
        render.addProperty("visited_label", cmd);
        render.addProperty("style", adminOnly ? 3 : 1);
        btn.add("render_data", render);

        JsonObject action = new JsonObject();
        action.addProperty("type", 2);
        action.addProperty("data", hasParam ? cmd + " " : cmd);
        action.addProperty("enter", !hasParam);
        JsonObject permission = new JsonObject();
        permission.addProperty("type", adminOnly ? 1 : 2);
        action.add("permission", permission);
        action.addProperty("unsupport_tips", "当前版本不支持按钮，请手动输入命令");
        btn.add("action", action);

        row.add(btn);
        count[0]++;
        if (row.size() == 5) {
            rows.add(wrapRow(row));
            return new JsonArray();
        }
        return row;
    }

    private static JsonObject wrapRow(JsonArray buttons) {
        JsonObject row = new JsonObject();
        row.add("buttons", buttons);
        return row;
    }

    public boolean hasMenuEntries() {
        for (BotMessageHandler handler : handlers) {
            if (handler.usage() != null) return true;
        }
        return false;
    }

    public List<String> getManagedPrefixes() {
        List<String> prefixes = new ArrayList<>();
        for (BotMessageHandler handler : handlers) {
            String usage = handler.usage();
            if (usage != null && !usage.isEmpty()) {
                String prefix = usage.split("\\s+")[0].trim().toLowerCase();
                if (!prefix.isEmpty()) prefixes.add(prefix);
            }
            for (String trigger : handler.triggers()) {
                if (trigger != null && !trigger.isEmpty()) {
                    prefixes.add(trigger.toLowerCase());
                }
            }
        }
        return prefixes;
    }

    public boolean isHandledByCommand(String message, BotMessageContext event) {
        if (message == null || message.trim().isEmpty()) return false;
        String nonce = "wa_probe_" + Long.toHexString(System.nanoTime());
        for (BotMessageHandler handler : handlers) {
            if (!matches(handler, message, event)) {
                continue;
            }
            if (!matches(handler, nonce, event)) {
                return true;
            }
        }
        return false;
    }

    private void log(String message) {
        if (logger != null) logger.accept(message);
    }

    /**
     * 如果 handler name 在 sensitive-filter-handlers 列表中，包装 event 的 reply 为自动过滤版。
     */
    private BotMessageEvent maybeWrapWithFilter(String handlerName, BotMessageEvent event) {
        SensitiveFilter sf = this.sensitiveFilter;
        if (sf == null || !sf.isEnabled() || handlerName == null) return event;
        if (!sensitiveFilterHandlers.contains(handlerName.toLowerCase())) return event;
        MessageReply reply = event.getReply();
        if (reply == null) return event;
        BotMessageEvent wrapped = new BotMessageEvent(
                event.getConversationId(), event.getSenderId(), event.getMessage(),
                new FilteringReply(reply, sf), event.getUsername(), event.getEventType());
        wrapped.setImageUrls(event.getImageUrls());
        return wrapped;
    }

    /** 包装 handler，使其 category() 返回模块显示名（handler 自身 category 为空时生效）。 */
    private static class ModuleCategoryWrapper implements BotMessageHandler {
        private final BotMessageHandler delegate;
        private final String moduleCategory;

        ModuleCategoryWrapper(BotMessageHandler delegate, String moduleCategory) {
            this.delegate = delegate;
            this.moduleCategory = moduleCategory;
        }

        @Override public boolean matches(String message, BotMessageContext event) { return delegate.matches(message, event); }
        @Override public void handle(String message, BotMessageContext event) { delegate.handle(message, event); }
        @Override public String name() { return delegate.name(); }
        @Override public int priority() { return delegate.priority(); }
        @Override public boolean adminOnly() { return delegate.adminOnly(); }
        @Override public boolean acceptsWithoutMention() { return delegate.acceptsWithoutMention(); }
        @Override public boolean adminFor(String message) { return delegate.adminFor(message); }
        @Override public java.util.List<String> triggers() { return delegate.triggers(); }
        @Override public java.util.List<MenuEntry> menuEntries() { return delegate.menuEntries(); }
        @Override public String usage() { return delegate.usage(); }
        @Override public String description() { return delegate.description(); }
        @Override public String category() { return moduleCategory; }
        @Override public void init(HandlerContext ctx) { delegate.init(ctx); }
        @Override public void shutdown() { delegate.shutdown(); }
    }

    private static class BotCommandHandler implements BotMessageHandler {
        private final BotCommand command;
        private final String moduleCategory;

        BotCommandHandler(BotCommand command, String moduleCategory) {
            this.command = command;
            this.moduleCategory = moduleCategory;
        }

        @Override
        public boolean matches(String message, BotMessageContext event) {
            return command.matches(message);
        }

        @Override
        public void handle(String message, BotMessageContext event) {
            command.handle(message, event);
        }

        @Override
        public String name() {
            return command.name();
        }

        @Override
        public int priority() {
            return 50;
        }

        @Override
        public boolean adminOnly() {
            return command.adminOnly();
        }

        @Override
        public boolean adminFor(String message) {
            return command.adminFor(message);
        }

        @Override
        public List<String> triggers() {
            return command.triggers();
        }

        @Override
        public String usage() {
            return command.usage();
        }

        @Override
        public String description() {
            return command.description();
        }

        @Override
        public String category() {
            String cat = command.category();
            return (cat != null && !cat.isEmpty()) ? cat : moduleCategory;
        }
    }

    private static class EchoReply implements MessageReply {
        private final MessageReply delegate;
        private final Consumer<String> echo;

        EchoReply(MessageReply delegate, Consumer<String> echo) {
            this.delegate = delegate;
            this.echo = echo;
        }

        @Override
        public void replyText(String text) {
            delegate.replyText(text);
            echo(text);
        }

        @Override
        public void replyImage(String imageUrl, String content) {
            delegate.replyImage(imageUrl, content);
            echo(empty(content) ? "[图片]" : content + " [图片]");
        }

        @Override
        public void replyImageData(byte[] imageBytes, String content) {
            delegate.replyImageData(imageBytes, content);
            echo(empty(content) ? "[图片]" : content + " [图片]");
        }

        @Override
        public void replyVoice(String voiceUrl) {
            delegate.replyVoice(voiceUrl);
        }

        @Override
        public void replyVoiceData(byte[] audioBytes) {
            delegate.replyVoiceData(audioBytes);
        }

        @Override
        public void replyVideo(String videoUrl, String content) {
            delegate.replyVideo(videoUrl, content);
            echo(empty(content) ? "[视频]" : content + " [视频]");
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

        private void echo(String text) {
            if (empty(text)) return;
            try {
                echo.accept(text.trim());
            } catch (Exception ignored) {
            }
        }

        private static boolean empty(String text) {
            return text == null || text.trim().isEmpty();
        }
    }

    /** 包装 MessageReply，对所有文本/Markdown 输出自动走敏感词过滤。 */
    private static class FilteringReply implements MessageReply {
        private final MessageReply delegate;
        private final SensitiveFilter filter;

        FilteringReply(MessageReply delegate, SensitiveFilter filter) {
            this.delegate = delegate;
            this.filter = filter;
        }

        @Override public void replyText(String text) { delegate.replyText(filter.filter(text)); }
        @Override public void replyImage(String imageUrl, String content) { delegate.replyImage(imageUrl, filter.filter(content)); }
        @Override public void replyImageData(byte[] imageBytes, String content) { delegate.replyImageData(imageBytes, filter.filter(content)); }
        @Override public void replyVoice(String voiceUrl) { delegate.replyVoice(voiceUrl); }
        @Override public void replyVoiceData(byte[] audioBytes) { delegate.replyVoiceData(audioBytes); }
        @Override public void replyVideo(String videoUrl, String content) { delegate.replyVideo(videoUrl, filter.filter(content)); }
        @Override public void replyEmbed(String embedJson) { delegate.replyEmbed(embedJson); }
        @Override public void replyMarkdown(String content, String keyboardTemplateId) { delegate.replyMarkdown(filter.filter(content), keyboardTemplateId); }
        @Override public void replyKeyboard(String markdownContent, String keyboardJson) { delegate.replyKeyboard(filter.filter(markdownContent), keyboardJson); }
        @Override public void replyArk(String arkJson) { delegate.replyArk(arkJson); }
    }

    static String stripMarkdown(String md) {
        if (md == null) return null;
        String[] lines = md.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String s = line;
            s = s.replaceAll("^\\s{0,3}#{1,6}\\s*", "");
            s = s.replaceAll("^\\s*>\\s?", "");
            s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "[图片]");
            s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]*)\\)", "$1($2)");
            s = s.replace("**", "").replace("`", "");
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString().trim();
    }
}
