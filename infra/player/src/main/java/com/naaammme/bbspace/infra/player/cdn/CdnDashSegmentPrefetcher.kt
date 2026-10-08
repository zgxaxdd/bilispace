package com.naaammme.bbspace.infra.player.cdn

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.infra.player.PlaybackMediaCache
import com.naaammme.bbspace.infra.player.SingleFileDashMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class CdnDashPrefetchRequest(
    val candidates: List<String>,
    val indexRange: CdnByteRange,
    val trackCacheKey: String,
    val bufferedDurationMs: Long,
    val frontierPositionMs: Long
)

data class CdnDashPrefetchResult(
    val plannedSegments: Int,
    val cachedSegments: Int,
    val selectedHosts: List<String>
)

/**
 * Stateless, caller-owned DASH prefetch operation. It is deliberately suspend-only so player
 * sessions control cancellation on seeks, low buffer, replacement, and teardown.
 */
class CdnDashSegmentPrefetcher(
    private val context: Context,
    private val client: OkHttpClient,
    /** 预取要写进本仓的播放字节缓存（源仓库是 object 静态调用；本仓是注入的实例）。 */
    private val playbackMediaCache: PlaybackMediaCache,
) {
    // OkHttpDataSource 与 CacheWriter 属 media3 unstable API：预取链路在应用层封装后消费，opt-in 标记会级联污染全部调用方。
    @SuppressLint("UnsafeOptInUsageError")
    suspend fun prefetch(request: CdnDashPrefetchRequest): CdnDashPrefetchResult = withContext(Dispatchers.IO) {
        val targetCount = resolveCdnPrefetchSegmentCount(request.bufferedDurationMs)
        if (targetCount == 0 || request.candidates.isEmpty()) {
            // 取证（2026-10-07）：预取"计划了却没做"必须能查出原因，否则真机只看得到
            // `planned=0`，无法区分"闸门没过"与"索引读不到"（后者是静默功能缺失）。
            DiagnosticLog.i(TAG) {
                "prefetch skip target=$targetCount candidates=${request.candidates.size}"
            }
            return@withContext CdnDashPrefetchResult(0, 0, emptyList())
        }
        val index = loadIndex(request.candidates, request.indexRange)
        if (index == null) {
            DiagnosticLog.w(TAG) {
                "prefetch index UNAVAILABLE start=${request.indexRange.start} " +
                    "len=${request.indexRange.length} candidates=${request.candidates.size}"
            }
            return@withContext CdnDashPrefetchResult(0, 0, emptyList())
        }
        val frontierUs = request.frontierPositionMs.coerceAtLeast(0L) * 1_000L
        val segments = index.segments
            .filter { it.startTimeUs >= frontierUs }
            .take(targetCount)
        if (segments.isEmpty()) {
            DiagnosticLog.i(TAG) {
                "prefetch nothing ahead: segments=${index.segments.size} " +
                    "frontierMs=${request.frontierPositionMs}"
            }
        }
        val upstreamFactory = OkHttpDataSource.Factory(client).setDefaultRequestProperties(PLAYBACK_HEADERS)
        val selectedHosts = mutableListOf<String>()
        segments.forEach { segment ->
            currentCoroutineContext().ensureActive()
            // Live transfer samples rank nodes; do not download probe bytes for every segment.
            val winner = CdnTransferRuntime.rank(request.candidates).firstOrNull() ?: return@forEach
            try {
                playbackMediaCache.prefetchRange(
                    upstreamFactory = upstreamFactory,
                    url = Uri.parse(winner),
                    cacheKey = request.trackCacheKey,
                    position = segment.range.start,
                    length = segment.range.length
                )
                selectedHosts += hostFromCdnUrl(winner)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
            }
        }
        CdnDashPrefetchResult(
            plannedSegments = segments.size,
            cachedSegments = selectedHosts.size,
            selectedHosts = selectedHosts
        )
    }

    private suspend fun loadIndex(candidates: List<String>, range: CdnByteRange): CdnDashIndex? {
        // 读**前缀**（0 → sidx 段末尾）而不是「只读 sidx 那一段」。
        //
        // 真机根因（2026-10-07）：部分 upos 镜像对**带偏移的 Range**（如 `bytes=1083-6390`）
        // 直接回 **403**，而播放侧读同一 host 的 `bytes=0-91535` 是 206 正常
        //（`prefetch index read error host=… CDN invalid range: HTTP 403` × 全部候选
        //  ⇒ `prefetch index UNAVAILABLE` ⇒ `planned=0`：预取对该视频静默全关）。
        // 三种 UA 组合均复现 ⇒ 与 UA 无关，是"这个偏移区间不被接受"。
        //
        // 顺带收益：前缀字节就是播放侧刚读过的那段 ⇒ 走播放字节缓存，命中即零流量
        //（`readPrefixThroughCache` 自带 HIT/MISS 日志）。
        val prefixLength = (range.endInclusive + 1L)
            .coerceAtMost(SingleFileDashMediaSourceFactory.DASH_PREFETCH_MAX_BYTES.toLong())
        val upstreamFactory = OkHttpDataSource.Factory(client).setDefaultRequestProperties(PLAYBACK_HEADERS)
        candidates.forEach { url ->
            val bytes = try {
                playbackMediaCache.readPrefixThroughCache(url, prefixLength, upstreamFactory)?.bytes
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DiagnosticLog.w(TAG) {
                    "prefetch index read error host=${hostFromCdnUrl(url)} ${error.message}"
                }
                null
            }
            if (bytes == null) {
                DiagnosticLog.w(TAG) { "prefetch index read failed host=${hostFromCdnUrl(url)}" }
                return@forEach
            }
            // 字节从文件 0 开始 ⇒ 用 0 作为绝对偏移扫盒（sidx 之前是 ftyp/moov，扫描器会跳过）。
            parseCdnSidx(bytes, 0L)?.let { return it }
            DiagnosticLog.w(TAG) {
                "prefetch sidx parse failed host=${hostFromCdnUrl(url)} bytes=${bytes.size}"
            }
        }
        return null
    }

    private companion object {
        const val TAG = "CdnPrefetch"

        val PLAYBACK_HEADERS = mapOf(
            "Referer" to "https://www.bilibili.com",
            "User-Agent" to CDN_MEDIA_USER_AGENT
        )
    }
}
