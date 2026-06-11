package org.windy.xingtubot.core.api;

/**
 * 全局单例实例管理
 * 用于跨模块共享对象（如 MCMOD 服务、配置等）
 */
public final class GlobalInstances {

    // 全局唯一的 MCMOD 搜索服务实例
    public static final McmodApiService mcmodService = new McmodApiService();

    // 禁止外部实例化
    private GlobalInstances() {}
}