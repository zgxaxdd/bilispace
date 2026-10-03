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
     * 失效该视频的播放地址缓存（PB-01）。
     *
     * **为什么必须有这个能力**：重试的语义是"再取一次流"。但取流入口是**缓存优先**
     * （`VideoPlayerRepositoryImpl.fetchPlaybackSource` 先查 `PlayUrlCache`），
     * 而自动重试的退避是 1s / 3s、TTL 是 90s —— 重试必然命中缓存，
     * 于是"URL 失效 / 403 / 404 / 签名过期"这类失败**结构性地不可能重试成功**
     * （拿回来的是同一份失效 URL）。
     *
     * 契约：非挂起（纯内存 LRU 操作）；按**视频身份前缀**失效，覆盖同一支视频的所有
     * flavor（用户切了清晰度再重试也要生效）。返回被删除的条目数（便于日志/测试）。
     */
    fun invalidatePlayUrl(request: PlaybackRequest): Int

    /**
     * 整清播放地址缓存（AP-04：trim 分级表 BACKGROUND 及以上档的登记出口）。
     *
     * 这是 `PlayUrlCache.clear()` 的**第一个真实调用点** —— 此前全仓没有任何一处
     * 调用它（历史上 FL 书 T8 曾误记"接线已存在"，AP-04 才真正接上）。缓存量小、
     * TTL 90s 自愈，清空几乎零代价；分级理由见 `MemoryTrimPolicy.NetCacheAction`。
     * （PB-01 新增的是 `invalidateByPrefix`，**不是** `clear()`：本条"唯一真实调用点"
     * 的措辞仍然成立。）
     */
    fun clearPlayUrlCache()
}
