package com.naaammme.bbspace.infra.player.cdn

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 一次范围读的结果（含**首字节耗时**，供并发决策参考）。
 *
 * 逐字移植自 BiliPai `feature/plugin/CdnRangeRequest.kt`（仅改包名与可见性：源为 `internal`，
 * 本仓放在 `infra:player` 内同样是模块私有）。
 */
data class CdnRangeSample(val bytes: ByteArray, val firstByteMs: Long, val elapsedMs: Long)

/**
 * 严格、有界的范围读：**调用方取消时立刻取消 socket**。
 *
 * 逐字移植自 BiliPai `CdnRangeRequest.kt:16-59`。三条严格性都保留：
 * - 必须精确满足请求区间（`isExactCdnRange`），否则抛错 —— 不允许"字节错位也算成功"；
 * - 短读（truncated）与超长（oversized）都当失败，避免把半个分片当完整分片写进缓存；
 * - `invokeOnCancellation { call.cancel() }`：分片并行时"谁先赢谁活"，输的那几片必须立刻断，
 *   否则弱网下会白白占着连接。
 */
suspend fun readExactCdnRange(
    client: OkHttpClient,
    url: String,
    range: CdnByteRange
): CdnRangeSample {
    require(range.start >= 0 && range.endInclusive >= range.start && range.length in 1..CDN_WINDOW_BYTES)
    return suspendCancellableCoroutine { continuation ->
        val startedAt = System.nanoTime()
        val request = Request.Builder().url(url)
            .header("Range", "bytes=${range.start}-${range.endInclusive}")
            .header("Referer", "https://www.bilibili.com")
            // 无 UA 的请求会被部分镜像直接拒（403），而下面 isExactCdnRange 是**严格**校验
            // ⇒ 静默失败。这里带上与预取下载分段一致的那份 UA（真机实测该 UA 在正常镜像上可用；
            // 注意它**不是**"某些视频预取索引 403"的成因 —— 三种 UA 组合均复现，见 CdnDashSegmentPrefetcher）。
            .header("User-Agent", CDN_MEDIA_USER_AGENT)
            .header("Accept-Encoding", "identity")
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val firstByteMs = ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(1)
                    val bytes = response.use {
                        if (!isExactCdnRange(it.code, it.header("Content-Range"), range)) {
                            throw IOException("CDN invalid range: HTTP ${it.code}")
                        }
                        val input = it.body?.byteStream() ?: throw IOException("CDN empty body")
                        input.use {
                            val data = ByteArray(range.length.toInt())
                            var offset = 0
                            while (offset < data.size) {
                                if (call.isCanceled()) throw IOException("CDN request canceled")
                                val read = input.read(data, offset, data.size - offset)
                                if (read < 0) throw IOException("CDN truncated range")
                                offset += read
                            }
                            if (input.read() != -1) throw IOException("CDN oversized range")
                            data
                        }
                    }
                    val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(1)
                    if (continuation.isActive) {
                        continuation.resume(CdnRangeSample(bytes, firstByteMs, elapsedMs))
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
