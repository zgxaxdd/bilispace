package com.naaammme.bbspace.infra.player.cdn

/**
 * CDN 传输的**区间与并发**判据（逐字移植自 BiliPai
 * `feature/plugin/CdnTransferPolicy.kt:3-41` 与 `CdnOptimizationPolicy.kt:21-29`）。
 *
 * ## 为什么需要它（本仓此前缺什么）
 *
 * 本仓原来的换线实现（`CdnFailoverDataSource`，2.8KB）**只在 open() 失败时串行换一条**：
 * 一次请求 = 一次连接 = 顺序读到底。源仓库则把一次请求**切成多片并行**发（`splitCdnRange`），
 * 并按缓冲/码率动态决定并发数（`resolveCdnConnections`）—— 这是"起播快 / 弱网不卡"的关键。
 *
 * ## 与既有 [com.naaammme.bbspace.core.model.player.CdnHealthPolicy] 的关系
 *
 * 那边的 `decay` 已经覆盖了源仓库 `decayCdnHealth` 的语义，**不重复移植**。
 * 这里只搬"区间切分 + 范围校验 + 并发决策"这些纯判据。
 */

/** 单次范围读的窗口上限（1 MiB）。 */
const val CDN_WINDOW_BYTES = 1024L * 1024L

/** 低于这个长度就不值得并行切分（切了也是小碎块）。 */
const val CDN_MIN_PARALLEL_BYTES = 256L * 1024L

/** 并行连接上限（4）：再多收益递减，且更容易被 CDN 限流。 */
const val CDN_MAX_CONNECTIONS = 4

/**
 * CDN 媒体请求的 User-Agent（预取下载分段与预取索引读共用）。
 *
 * 取值的来历：`CdnDashSegmentPrefetcher` 下载分段时一直用这个 UA（长期实测可用），
 * `CdnPlaybackDataSource` 的并行分片则用 `"Mozilla/5.0"`。两者都能通，说明 CDN 只要求"带 UA"。
 *
 * ⚠️ 它**不是**"某些视频预取索引读 403"的成因：2026-10-07 真机分别用
 * 本值 / 播放侧 `UserAgentBuilder` / `"Mozilla/5.0"` 三种 UA 复现了同样的 403，
 * 因此根因仍待定位（见 [CdnDashSegmentPrefetcher] 的 `prefetch index read error` 日志）。
 */
const val CDN_MEDIA_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"


/** 解析出来的 `Content-Range`。 */
data class CdnContentRange(val start: Long, val end: Long, val total: Long)

/** 解析 `bytes <start>-<end>/<total>`；不合法返回 null。 */
fun parseCdnContentRange(value: String?): CdnContentRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        .matchEntire(value?.trim().orEmpty()) ?: return null
    val (start, end, total) = match.destructured
    val first = start.toLongOrNull() ?: return null
    val last = end.toLongOrNull() ?: return null
    val size = total.toLongOrNull() ?: return null
    return CdnContentRange(first, last, size).takeIf { first >= 0 && last >= first && size > last }
}

/**
 * 服务端是否**精确**满足了我们请求的区间。
 *
 * 只认 206 + 起点终点都对：CDN 常见"忽略 Range 直接回 200 全量"或"回一个更大的区间"，
 * 两者都会让分片的字节错位 —— 必须拒绝，不能"看起来能用就用"。
 */
fun isExactCdnRange(status: Int, header: String?, range: CdnByteRange): Boolean {
    val actual = parseCdnContentRange(header) ?: return false
    return status == 206 && actual.start == range.start && actual.end == range.endInclusive
}

/** 把 `[start, start+length)` 切成最多 `connections` 段（最后一段吃掉余数）。 */
fun splitCdnRange(start: Long, length: Long, connections: Int): List<CdnByteRange> {
    if (start < 0 || length <= 0 || length - 1 > Long.MAX_VALUE - start) return emptyList()
    val count = connections.coerceIn(1, CDN_MAX_CONNECTIONS).coerceAtMost(length.toIntSafe())
    val chunk = length / count
    return (0 until count).map { index ->
        val first = start + index * chunk
        CdnByteRange(first, if (index == count - 1) start + length - 1 else first + chunk - 1)
    }
}

private fun Long.toIntSafe(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * 按"缓冲高度 / 实测速率 / 码率"决定并发连接数（源仓库同名函数，逐字）。
 *
 * - 计量网络 → 1（不浪费流量）；
 * - 缓冲已经很厚（≥30s）→ 1（不急）；
 * - 缓冲薄（<5s）或实测速率追不上码率 → 3（抢时间）；
 * - 其余 → 2。
 */
fun resolveCdnConnections(bufferMs: Long, speedBps: Long, bitrateBps: Long, limited: Boolean): Int = when {
    limited -> 1
    bufferMs >= 30_000 -> 1
    bufferMs < 5_000 || (bitrateBps > 0 && speedBps * 8 < bitrateBps) -> 3
    else -> 2
}

