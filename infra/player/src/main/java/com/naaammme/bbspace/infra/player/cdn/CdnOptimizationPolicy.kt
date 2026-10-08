package com.naaammme.bbspace.infra.player.cdn

import java.net.URI
import java.security.MessageDigest

const val CDN_PREFETCH_SAFE_BUFFER_MS = 15_000L
const val CDN_PREFETCH_PROBE_BYTES = 32L * 1024L

/**
 * 预取的**推进步长**：只有当"已缓冲前沿"比上次预取时又前进了这么多，才值得再预取一批。
 *
 * 为什么需要它：预取的就绪条件是"缓冲已经够厚（≥ [CDN_PREFETCH_SAFE_BUFFER_MS]）"，
 * 而这个条件在**播放开始之后**才成立（`STATE_READY` 时缓冲通常只有几百毫秒）——
 * 所以判据必须周期性复查（源仓库是在 `startPluginCheck()` 的 ~500ms 轮询里复查）。
 * 但每 500ms 复查一次若不加限制，就会每 500ms 重读一次 sidx（一次真实网络请求）而
 * 预取的是同一批分段：本常量把"复查"与"重做"分开——复查很便宜，重做要等前沿真的前进。
 *
 * 取 10s：一个 DASH 分段通常 5~10s，等于"上一批预取还没被播到就不重复预取"。
 */
const val CDN_PREFETCH_FRONTIER_STEP_MS = 10_000L

data class CdnByteRange(
    val start: Long,
    val endInclusive: Long
) {
    val length: Long get() = (endInclusive - start + 1L).coerceAtLeast(0L)
}

data class CdnDashSegment(
    val range: CdnByteRange,
    val startTimeUs: Long,
    val durationUs: Long
)

data class CdnDashIndex(
    val segments: List<CdnDashSegment>,
    val timescale: Long
)

fun resolveCdnPrefetchSegmentCount(bufferedDurationMs: Long): Int {
    return when {
        bufferedDurationMs < CDN_PREFETCH_SAFE_BUFFER_MS -> 0
        bufferedDurationMs < 30_000L -> 3
        bufferedDurationMs < 60_000L -> 5
        else -> 8
    }
}

/**
 * Produces a stable key for a single media track across signed mirrors. The supplied track id
 * is intentionally part of the key so video and audio paths can never share cache spans.
 */
fun buildCdnTrackCacheKey(trackId: String, url: String): String {
    val path = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest((trackId + '\u0000' + path).toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }
    return "bili-cdn-track:$digest"
}

/** Parses an ISO-BMFF sidx response whose first byte maps to [indexRangeStart]. */
fun parseCdnSidx(bytes: ByteArray, indexRangeStart: Long): CdnDashIndex? {
    var offset = 0
    while (offset + 8 <= bytes.size) {
        val size32 = readUInt32(bytes, offset) ?: return null
        val type = bytes.copyOfRange(offset + 4, offset + 8).decodeToString()
        val headerSize = when (size32) {
            1L -> 16
            0L -> bytes.size - offset
            else -> 8
        }
        val boxSize = when (size32) {
            1L -> readUInt64(bytes, offset + 8) ?: return null
            0L -> (bytes.size - offset).toLong()
            else -> size32
        }
        if (boxSize < headerSize || offset + boxSize > bytes.size) return null
        if (type == "sidx") {
            return parseSidxBox(
                bytes = bytes,
                boxOffset = offset,
                boxSize = boxSize.toInt(),
                headerSize = headerSize,
                absoluteBoxOffset = indexRangeStart + offset
            )
        }
        offset += boxSize.toInt()
    }
    return null
}

private fun parseSidxBox(
    bytes: ByteArray,
    boxOffset: Int,
    boxSize: Int,
    headerSize: Int,
    absoluteBoxOffset: Long
): CdnDashIndex? {
    var cursor = boxOffset + headerSize
    val version = bytes.getOrNull(cursor)?.toInt()?.and(0xff) ?: return null
    cursor += 4 // version + flags
    cursor += 4 // reference id
    val timescale = readUInt32(bytes, cursor) ?: return null
    if (timescale <= 0L) return null
    cursor += 4
    val earliestPresentationTime: Long
    val firstOffset: Long
    if (version == 0) {
        earliestPresentationTime = readUInt32(bytes, cursor) ?: return null
        cursor += 4
        firstOffset = readUInt32(bytes, cursor) ?: return null
        cursor += 4
    } else if (version == 1) {
        earliestPresentationTime = readUInt64(bytes, cursor) ?: return null
        cursor += 8
        firstOffset = readUInt64(bytes, cursor) ?: return null
        cursor += 8
    } else {
        return null
    }
    cursor += 2 // reserved
    val referenceCount = readUInt16(bytes, cursor) ?: return null
    cursor += 2
    var segmentOffset = absoluteBoxOffset + boxSize + firstOffset
    var segmentTime = earliestPresentationTime
    val segments = buildList {
        repeat(referenceCount) {
            val reference = readUInt32(bytes, cursor) ?: return null
            cursor += 4
            val referenceType = reference shr 31
            val referencedSize = reference and 0x7fff_ffffL
            val duration = readUInt32(bytes, cursor) ?: return null
            cursor += 4
            cursor += 4 // starts_with_SAP, SAP type, SAP delta time
            if (referencedSize <= 0L) return null
            if (referenceType == 0L) {
                add(
                    CdnDashSegment(
                        range = CdnByteRange(segmentOffset, segmentOffset + referencedSize - 1L),
                        startTimeUs = segmentTime * 1_000_000L / timescale,
                        durationUs = duration * 1_000_000L / timescale
                    )
                )
            }
            segmentOffset += referencedSize
            segmentTime += duration
        }
    }
    return CdnDashIndex(segments = segments, timescale = timescale)
}

private fun readUInt16(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset + 2 > bytes.size) return null
    return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}

private fun readUInt32(bytes: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset + 4 > bytes.size) return null
    return (0 until 4).fold(0L) { value, index ->
        (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
    }
}

private fun readUInt64(bytes: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset + 8 > bytes.size) return null
    return (0 until 8).fold(0L) { value, index ->
        (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
    }
}
