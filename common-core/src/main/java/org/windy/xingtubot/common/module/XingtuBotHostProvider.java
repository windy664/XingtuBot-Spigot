package org.windy.xingtubot.common.module;

/**
 * 由核心 Velocity 主类实现的「宿主提供者」，供附属扩展插件经
 * {@code PluginManager.getPlugin("xingtubotvelocity").getInstance()} 拿到核心实例后，
 * 转型为本接口取 {@link XingtuBotHost}。
 *
 * <p>之所以需要它：Velocity 无 Bukkit 那样的 ServicesManager，扩展只能拿到核心主类实例；
 * 而核心主类在 {@code velocity} 模块、不在扩展的编译类路径上。把取宿主的方法收敛到 common-core
 * 的本接口，扩展即可只 compileOnly common-core 就完成转型，无需依赖核心平台模块。
 *
 * <p>（Bukkit 端不需要本接口——直接 {@code ServicesManager.load(XingtuBotHost.class)} 即可。）
 */
public interface XingtuBotHostProvider {

    /** 核心宿主能力面；核心未就绪时可能为 null。 */
    XingtuBotHost getHost();
}
