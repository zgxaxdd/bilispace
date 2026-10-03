package com.naaammme.bbspace.core.common.cache

/**
 * 播放字节缓存的控制面（PB-03）：**占用查询 + 清理**。
 *
 * ## 为什么是接口而不是直接注入实现
 * 实现在 `infra:player`（`PlaybackMediaCache`，只有那里能拿到 media3 的 `SimpleCache`），
 * 而消费方在设置页（`feature:settings` → `core:auth/CacheManager`）。直接在设置链路里
 * 依赖 `infra:player` 会新增一条**跨模块依赖边**（本仓纪律：不新增依赖/模块）；
 * 放在 `core:common`（各模块的公共底座）由 `infra:player` 用 `@Binds` 提供实现，
 * 是本仓既有的桥接范式（同 `core:common` 的 `UpBlocker` ← `core:space`）。
 */
interface PlaybackCacheControl {

    /** 当前磁盘占用（字节）。 */
    suspend fun cachedBytes(): Long

    /**
     * 清空播放缓存。
     *
     * @return false = **拒绝**（有正在播放的媒体源；抽掉正在读的文件会中断播放）。
     *   调用方应据此给出提示，而不是当作"已清理"。
     */
    suspend fun clear(): Boolean
}
