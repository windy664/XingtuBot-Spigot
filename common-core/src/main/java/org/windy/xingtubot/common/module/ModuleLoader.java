package org.windy.xingtubot.common.module;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 模块加载器：通过 {@link ServiceLoader} 自动发现并加载所有 {@link BotModule}。
 *
 * <p>每个功能子模块在 {@code META-INF/services/org.windy.xingtubot.common.module.BotModule}
 * 中声明自己的实现类，平台侧只需调用 {@link #loadAll} 即可装配全部模块，
 * 新增/删除功能不再改平台代码。
 *
 * <p>加载流程：发现 → 总开关门控（config key {@code module-<name>-enable}，默认 true）
 * → {@code onEnable(ctx)} → 收集到列表供 {@link #disableAll} 调用 {@code onDisable}。
 *
 * <p><b>ClassLoader 陷阱</b>：插件 jar 由 shadowJar 合并，运行期线程上下文类加载器
 * （TCCL）在 Bukkit/Velocity 下未必是插件类加载器，直接 {@code ServiceLoader.load(BotModule.class)}
 * 可能发现不到 services。故显式使用 {@code BotModule.class.getClassLoader()}。
 */
public final class ModuleLoader {

    private final List<BotModule> loaded = new ArrayList<>();

    /**
     * 发现并启用所有模块。
     *
     * @param ctx 模块上下文（平台侧应在调用前注册好所有平台能力服务）
     * @return 已启用模块数
     */
    public int loadAll(ModuleContext ctx) {
        ClassLoader cl = BotModule.class.getClassLoader();
        ServiceLoader<BotModule> services = ServiceLoader.load(BotModule.class, cl);

        // ServiceLoader 迭代顺序无保证 → 显式按 loadPriority 排序，
        // 让「服务提供型」模块（翻译/TTS 等）先于消费方加载。
        List<BotModule> discovered = new ArrayList<>();
        for (BotModule m : services) {
            discovered.add(m);
        }
        discovered.sort(java.util.Comparator.comparingInt(BotModule::loadPriority));

        List<String> enabled = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (BotModule module : discovered) {
            String name = safeName(module);
            String key = "module-" + name + "-enable";
            if (!ctx.config().getBoolean(key, true)) {
                skipped.add(name);
                continue;
            }
            try {
                // 设置当前模块的菜单分类名，handler 注册时自动继承
                if (ctx.registry() != null) {
                    ctx.registry().setCurrentModuleDisplayName(module.displayName());
                }
                module.onEnable(ctx);
                loaded.add(module);
                enabled.add(name);
            } catch (Throwable t) {
                ctx.logger().warn("[ModuleLoader] 模块 " + name + " 启用失败: " + t);
            }
        }

        ctx.logger().info("[ModuleLoader] 已加载模块(" + enabled.size() + "): "
                + (enabled.isEmpty() ? "无" : String.join(", ", enabled)));
        if (!skipped.isEmpty()) {
            ctx.logger().info("[ModuleLoader] 已禁用(config 关闭): " + String.join(", ", skipped));
        }
        return enabled.size();
    }

    /** 禁用所有已加载模块（释放资源/停定时任务）。 */
    public void disableAll() {
        for (BotModule m : loaded) {
            try {
                m.onDisable();
            } catch (Throwable ignored) {
            }
        }
        loaded.clear();
    }

    /** 已加载模块（只读视图）。 */
    public List<BotModule> loaded() {
        return new ArrayList<>(loaded);
    }

    private static String safeName(BotModule m) {
        try {
            String n = m.name();
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable ignored) {
        }
        return m.getClass().getSimpleName();
    }
}
