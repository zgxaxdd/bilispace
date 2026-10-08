package com.naaammme.bbspace.infra.player

import com.naaammme.bbspace.infra.player.cdn.CDN_WINDOW_BYTES
import com.naaammme.bbspace.infra.player.cdn.CdnByteRange

/**
 * DASH 分段预取的**静态计划**：一次播放会话里"预取哪条轨道、用哪些线路、读哪一段 sidx、
 * 写进哪把缓存键"这些**与播放进度无关**的部分。
 *
 * 与播放进度有关的两项（`bufferedDurationMs` / `frontierPositionMs`）由调度方
 * （[Media3PlayerEngine] 的进度轮询 / READY 事件）在每次真正调度时现取。
 *
 * 拆成纯函数（本文件）的理由与 `PlaybackCacheKeyFactory` 同：工厂类要 `Context` 与 Media3，
 * JVM 单测到不了；而**"缓存键算错"是静默失败**（预取写进去的字节播放时命不中，等于白下），
 * 必须能直接断言。
 *
 * ⚠️ 缓存键**必须**走 [playbackCacheKeyForPath]（播放侧的 `PlaybackCacheKeyFactory` 用的就是它）：
 * 源仓库用的是自己那套 `buildCdnTrackCacheKey(trackId, path)` 哈希，本仓的读路径不吃那套键
 * —— 若照抄，写进去的分段永远不会被命中（这正是"移植了却没生效"的高发点）。
 */
internal data class CdnDashPrefetchPlan(
    val candidates: List<String>,
    val indexRange: CdnByteRange,
    val cacheKey: String
)

/**
 * 由"sidx 绝对起点/长度 + 候选线路"构造预取计划；任一项不可用即返回 null（不预取）。
 */
internal fun buildCdnDashPrefetchPlan(
    candidates: List<String>,
    indexStart: Long,
    indexLength: Long,
    fallbackUrl: String
): CdnDashPrefetchPlan? {
    if (indexStart < 0L || indexLength <= 0L || indexLength > CDN_WINDOW_BYTES) return null
    val end = indexStart + indexLength - 1L
    if (end < indexStart) return null
    val urls = candidates.filter { it.isNotBlank() }.distinct()
    val primary = urls.firstOrNull()
        ?: fallbackUrl.takeIf { it.isNotBlank() }
        ?: return null
    val path = runCatching { java.net.URI(primary).rawPath }.getOrNull()
    return CdnDashPrefetchPlan(
        candidates = if (urls.isEmpty()) listOf(primary) else urls,
        indexRange = CdnByteRange(indexStart, end),
        cacheKey = playbackCacheKeyForPath(path, primary)
    )
}
