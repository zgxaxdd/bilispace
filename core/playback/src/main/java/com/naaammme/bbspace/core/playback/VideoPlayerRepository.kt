package com.naaammme.bbspace.core.playback

import com.naaammme.bbspace.core.model.PlaybackRequest
import com.naaammme.bbspace.core.model.PlaybackSource

interface VideoPlayerRepository {
    suspend fun fetchPlaybackSource(request: PlaybackRequest): PlaybackSource

    /**
     * 相邻页预取：把这一支的播放地址先取回来，与正常开播**共用同一份进程内 LRU**
     * （见 `PlayUrlCache`）—— 命中即省掉一整个 gRPC `PlayViewUnite` 往返。
     * 竖屏连播"上滑之后画面来得快不快"主要就取决于这一步（BiliPai 同款能力：
     * `PlayUrlCache` + `resolvePortraitPlayUrlPreloadCount/Targets`）。
     *
     * 契约：
     * - **尽力而为**：失败只返回 false，不抛异常、不影响正在播放的会话；
     * - 无副作用：不改会话状态、不发播放上报、不动播放器；
     * - 幂等：重复调用只是再查一次缓存。
     */
    suspend fun preloadPlayUrl(request: PlaybackRequest): Boolean

    /**
     * 整清播放地址缓存（AP-04：trim 分级表 BACKGROUND 及以上档的登记出口）。
     *
     * 这是 `PlayUrlCache.clear()` 的**第一个真实调用点** —— 此前全仓没有任何一处
     * 调用它（历史上 FL 书 T8 曾误记"接线已存在"，AP-04 才真正接上）。缓存量小、
     * TTL 90s 自愈，清空几乎零代价；分级理由见 `MemoryTrimPolicy.NetCacheAction`。
     */
    fun clearPlayUrlCache()
}
