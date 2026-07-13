package org.windy.xingtubot.common.module;

import org.windy.xingtubot.common.handler.PermissionChecker;
import org.windy.xingtubot.common.handler.HandlerRegistry;
import org.windy.xingtubot.common.platform.PlatformAdapter;

/**
 * 昕途核心对<b>附属扩展插件</b>暴露的宿主能力面（push 模型的支点）。
 *
 * <p>背景：扩展功能不再靠 shadowJar 焊进同一个 bundle、由核心 {@link ModuleLoader} 经
 * {@code ServiceLoader} 拉取（拉模型只能发现同一 jar 内的 service）。改为<b>每个扩展是独立插件 jar</b>，
 * 启动时定位本接口、把自己的 handler/command/service 主动注册进核心（push 模型）。
 *
 * <p>本接口刻意只暴露「核心构建、跨插件共享」的部分——注册中心、共享服务总线、权限、i18n、平台适配器；
 * <b>不</b>含 {@code config()}/{@code dataFolder()}/{@code logger()}，因为那些随每个扩展插件各自独立
 * （各扩展有自己的配置文件与数据目录）。扩展侧用 {@link ExtensionBootstrap} 把宿主 + 自身配置组合成
 * 一个完整的 {@link ModuleContext} 交给 {@link BotModule#onEnable}。
 *
 * <p>定位方式：
 * <ul>
 *   <li>Bukkit：{@code getServer().getServicesManager().load(XingtuBotHost.class)}；</li>
 *   <li>Velocity：经 {@code proxy.getPluginManager().getPlugin("xingtubotvelocity")} 拿主类调
 *       {@code getHost()}。</li>
 * </ul>
 *
 * <p><b>服务总线身份</b>：核心平台侧把<b>同一个</b> {@link ModuleContextImpl} 实例既用于自身仍内置的
 * 模块（{@code loadAll}），又作为本宿主暴露——故内置模块与外部扩展共享同一个 {@code registerService}/
 * {@code getService} 总线，迁移期内外混跑也能互相取到对方注册的服务。
 */
public interface XingtuBotHost {

    /** 消息处理器注册中心（与核心内置功能共用同一分发路径）。 */
    HandlerRegistry registry();

    /** 注册一个共享服务实例（如翻译/AI），供其他模块/扩展经 {@link #getService(Class)} 获取。 */
    void registerService(Class<?> type, Object instance);

    /** 获取核心或其他扩展注册的共享服务。未注册返回 null。 */
    <T> T getService(Class<T> type);

    /**
     * 按 Class 对象获取共享服务（返回 Object），供跨 classloader 场景使用。
     *
     * <p>扩展插件无法直接引用主插件 classloader 中的类型（如 GroupChatLink），
     * 可通过 {@code Class.forName(...)} 反射获取 Class 对象后调用本方法。
     */
    Object getServiceObject(Class<?> type);

    /** 超管权限服务。 */
    PermissionChecker permission();

    /** 平台适配器（Bukkit/Velocity）。可能为 null。 */
    PlatformAdapter platform();

    /**
     * 本实例是否为「大脑（master/主导）」。
     *
     * <p>这是<b>框架级</b>的部署拓扑判定（单机 Bukkit 本地大脑 / Velocity·BungeeCord 代理大脑为 true；
     * 挂在代理后面、由代理大脑统一接管的 Bukkit 子服为 false）。由核心在启动时<b>一次性</b>算定并经本接口
     * 暴露——附属扩展（如 xt-auth）<b>不应</b>自行用 ProxyDetector 等手段重复判定，直接读本值即可，
     * 避免多处判定彼此打架。
     */
    boolean isBrain();
}
