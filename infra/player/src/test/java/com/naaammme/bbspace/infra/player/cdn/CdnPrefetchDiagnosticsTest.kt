package com.naaammme.bbspace.infra.player.cdn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「预取为什么没干活」的**可判定日志**守卫（R-15，2026-10-07 真机发现）。
 *
 * ## 缺陷形态
 *
 * `Media3PlayerEngine` 只记结果：`cdn prefetch plan=3 planned=0 cached=0 hosts=0`。
 * 同样是 `planned=0`，可能是"闸门没过（缓冲薄）"、"索引读不到"、"前沿之后没有分段"三种完全不同的
 * 原因 —— 而**只有第二种是功能缺失**。真机上就是靠这行日志看不清原因，才需要一条条加日志定位。
 *
 * ## 真机实测结论（记录在此，免得后人再猜）
 *
 * 部分 `upos-*` 镜像对**带偏移的 sidx Range**（如 `bytes=1083-6390`）回 **403**，
 * 而播放侧对同一 host 的 `bytes=0-91535` 正常；因此 `loadIndex` 逐条候选全失败 ⇒
 * 该视频的分段预取静默全关（`planned=0`）。UA 不是成因：三种 UA
 *（本仓默认 / `UserAgentBuilder` / `Mozilla/5.0`）均复现 → 根因仍待定位，
 * 所以本仓只保证"原因可见"，不做无据改动。
 */
class CdnPrefetchDiagnosticsTest {

    @Test
    fun prefetchExplainsWhyItDidNothing() {
        val prefetcher = codeOf("CdnDashSegmentPrefetcher.kt")
        assertTrue(
            "闸门没过要记 `prefetch skip target=… candidates=…`（与「索引读不到」区分开）",
            prefetcher.any { it.contains("prefetch skip target=") }
        )
        assertTrue(
            "索引彻底拿不到要记 `prefetch index UNAVAILABLE …`（= 该视频预取静默全关）",
            prefetcher.any { it.contains("prefetch index UNAVAILABLE") }
        )
        assertTrue(
            "索引读失败必须带原因（HTTP 码/异常消息），否则分不出「该镜像不支持这个 Range」与「网络失败」",
            prefetcher.any { it.contains("prefetch index read error host=") }
        )
        assertTrue(
            "sidx 解析失败要单独记（字节拿到了但结构不认）",
            prefetcher.any { it.contains("prefetch sidx parse failed host=") }
        )
        assertTrue(
            "前沿之后没有分段也要记，否则与「读不到索引」混淆",
            prefetcher.any { it.contains("prefetch nothing ahead") }
        )
    }

    /** 预取索引读也必须带 UA：无 UA 的请求会被部分镜像直接拒（本仓原来裸读不带 UA）。 */
    @Test
    fun cdnRangeReadCarriesAUserAgent() {
        val request = codeOf("CdnRangeRequest.kt")
        assertTrue(
            "范围读必须带 User-Agent（否则部分镜像 403/错误页 → 严格区间校验直接失败）",
            request.any { it.contains("\"User-Agent\", CDN_MEDIA_USER_AGENT") }
        )
    }

    /**
     * 索引读必须**从 0 读前缀、并且走播放字节缓存**。
     *
     * 这是 2026-10-07 真机缺陷的修法：原来直接请求 sidx 那一段（如 `bytes=1083-6390`），
     * 部分 `upos-*` 镜像对此回 **403**（同一 host 读 `bytes=0-91535` 正常）⇒ 逐条候选全失败
     * ⇒ `planned=0`：预取对该视频静默全关。改成"读前缀 + 用 0 作绝对偏移扫盒"后：
     * ①请求变成 `bytes=0-…`，与播放侧同形；②前缀字节就在播放缓存里 ⇒ HIT 即零流量。
     * 真机复验：BV1HBaz6aEVK（修前 `planned=0`）→ `planned=3 cached=3`（cache=HIT）；
     * 未缓存视频 BV1XuHq67Exg → `planned=3 cached=3`（cache=MISS→网络，同样成功）。
     */
    @Test
    fun prefetchReadsIndexPrefixThroughThePlaybackCache() {
        val prefetcher = codeOf("CdnDashSegmentPrefetcher.kt")
        assertTrue(
            "预取索引读必须走 `readPrefixThroughCache`（从 0 读前缀）—— 直接读 sidx 段会被部分镜像 403",
            prefetcher.any { it.contains("readPrefixThroughCache(") }
        )
        assertTrue(
            "拿到的是从 0 开始的字节 ⇒ 扫盒的绝对偏移必须是 0",
            prefetcher.any { it.contains("parseCdnSidx(bytes, 0L)") }
        )
    }

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun codeOf(fileName: String): List<String> {
        val matches = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .filter { it.name == fileName }
            .toList()
        assertEquals(
            "`$fileName` 命中 ${matches.size} 个文件（要求恰好 1）：" + matches.joinToString { it.path },
            1,
            matches.size
        )
        return matches.single().readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("import ")
            ) {
                ""
            } else {
                line
            }
        }
    }
}
