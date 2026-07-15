package org.windy.xingtubot.module;

import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.command.CustomCommandConfig;
import org.windy.xingtubot.common.command.impl.CustomCommandHandler;
import org.windy.xingtubot.common.handler.impl.CustomReplyHandler;
import org.windy.xingtubot.common.handler.impl.LeaveHandler;
import org.windy.xingtubot.common.handler.impl.WelcomeHandler;
import org.windy.xingtubot.common.image.TextImageRenderer;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.common.module.capability.ConsoleExecutor;
import org.windy.xingtubot.common.module.capability.CrossServerConsole;
import org.windy.xingtubot.common.module.capability.PlayerCommandExecutor;
import org.windy.xingtubot.common.reply.CustomReplyService;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 群功能模块：迎送词 + 自定义回复 + 自定义指令。
 *
 * <p>迎送词直接注册 handler；自定义回复/指令需要 PlaceholderResolver / ConsoleExecutor /
 * PlayerCommandExecutor / CrossServerConsole 等能力（从主插件服务总线获取）。
 * BindingRepository 由 xt-auth 提供，通过服务总线反射获取。
 */
public final class GroupFeaturesModule implements BotModule {

    @Override
    public String name() {
        return "group";
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        // ===== 迎送词 =====
        List<String> allowedList = ctx.config().getStringList("allowed-groups");
        Set<String> allowedGroups = allowedList.isEmpty()
                ? Collections.singleton("*") : new HashSet<>(allowedList);
        if (ctx.config().getBoolean("welcome-enable", true)) {
            ctx.registry().register(new WelcomeHandler(ctx.config(), allowedGroups));
        }
        if (ctx.config().getBoolean("leave-enable", true)) {
            ctx.registry().register(new LeaveHandler(ctx.config(), allowedGroups));
        }

        // ===== 自定义问答（replies.yml）=====
        File dataFolder = ctx.dataFolder();
        if (dataFolder != null) {
            PlaceholderResolver placeholders = ctx.getService(PlaceholderResolver.class);
            TextImageRenderer textImage = ctx.getService(TextImageRenderer.class);
            BindingRepository bindingStore = ctx.getService(BindingRepository.class);
            ConsoleExecutor console = ctx.getService(ConsoleExecutor.class);
            PlayerCommandExecutor player = ctx.getService(PlayerCommandExecutor.class);
            CrossServerConsole cross = ctx.getService(CrossServerConsole.class);

            File repliesFile = ensureResource(dataFolder, "replies.yml", ctx);
            File imagesDir = new File(dataFolder, "images");
            imagesDir.mkdirs();
            CustomReplyService customReply = new CustomReplyService(
                    repliesFile, imagesDir, textImage, placeholders, m -> ctx.logger().info(m));
            // {menu} 占位符 → 全部命令菜单（自定义回复即菜单：replies.yml 里写 content: "{menu}"）。
            // 背后走 HandlerRegistry.buildMenu，按发送者是否超管分组渲染。
            customReply.setMenuProvider(event -> ctx.registry().buildMenu(
                    ctx.permission() != null && ctx.permission().isAdmin(event.getSenderId())));
            // {menu} 菜单自动附命令按钮：无参一键执行、带参点了只填草稿（不自动发）、超管命令仅管理员可点
            customReply.setMenuKeyboardProvider(event -> ctx.registry().buildMenuKeyboard(
                    ctx.permission() != null && ctx.permission().isAdmin(event.getSenderId())));
            if (customReply.count() > 0) {
                ctx.registry().register(new CustomReplyHandler(customReply));
            }

            // ===== 自定义命令（commands.yml）=====
            File commandsFile = ensureResource(dataFolder, "commands.yml", ctx);
            CustomCommandConfig cmdConfig = new CustomCommandConfig(m -> ctx.logger().info(m));
            cmdConfig.load(commandsFile);
            if (!cmdConfig.getEntries().isEmpty()) {
                CustomCommandHandler handler = new CustomCommandHandler(
                        cmdConfig, bindingStore,
                        console != null ? (target, cmd, cb) -> console.exec(cmd, cb) : null,
                        player != null ? (target, cmd, cb) -> player.exec(target, cmd, cb) : null,
                        cross != null ? (server, cmd, cb) -> cross.exec(server, cmd, cb) : null);
                handler.setPlaceholderResolver(placeholders);
                ctx.registry().register(handler);
            }
        }

        ctx.logger().info("[Group] 群功能已加载");
    }

    /** 确保数据目录下有指定资源；没有则从本模块 jar 资源释放。 */
    private static File ensureResource(File dataFolder, String name, ModuleContext ctx) {
        File file = new File(dataFolder, name);
        if (!file.exists()) {
            try (InputStream in = GroupFeaturesModule.class.getClassLoader().getResourceAsStream(name)) {
                if (in != null) Files.copy(in, file.toPath());
            } catch (Exception e) {
                ctx.logger().warn("[Group] 释放默认 " + name + " 失败: " + e.getMessage());
            }
        }
        return file;
    }
}
