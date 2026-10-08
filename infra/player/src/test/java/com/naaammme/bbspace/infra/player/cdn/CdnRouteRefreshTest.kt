package com.naaammme.bbspace.infra.player.cdn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「长会话路由过期 ⇒ 并行静默降级」的回归守卫（R-15 真机 soak 抓到的缺陷）。
 *
 * ## 缺陷形态
 *
 * `CdnTransferRuntime` 的候选路由是**本次 playurl 的授权**，有效期 [CDN_ROUTE_TTL_MS]（10 分钟，
 * 源仓库同口径）。而 `register()` 只在**起播**时调用一次 ⇒ 一次播放超过 10 分钟后
 * `candidates(url)` 恒空 ⇒ `CdnPlaybackDataSource` 的 `monitored=false / parallel=false`
 * ⇒ **并行分片下载静默降级成单连接**（不报错、不掉帧，能力悄悄没了）。
 *
 * 真机实测（2026-10-06，15 分钟 soak）：23:23 起播（`cands=1/2 monitored=true`），
 * 23:38 首次读到未缓存区间时 `CdnParallel: cands=0 monitored=false parallel=false`。
 *
 * ## 修法（本守卫钉住）
 *
 * 引擎在 500ms 进度轮询里周期性续期：`refreshCdnRoutesIfStale()` 每
 * `CDN_ROUTE_REFRESH_MS`（5 分钟）用**当前播放源自己的候选**重新 `register()`。
 */
class CdnRouteRefreshTest {

    @Test
    fun routeIsFreshWithinTtlAndStaleAfterIt() {
        val registered = 1_000_000L
        assertTrue("刚登记：新鲜", isRouteFresh(registered, registered))
        assertTrue(
            "差 1ms 到 10 分钟：仍新鲜（边界必须闭在 ≤）",
            isRouteFresh(registered, registered + CDN_ROUTE_TTL_MS - 1L)
        )
        assertTrue("正好 10 分钟：仍新鲜", isRouteFresh(registered, registered + CDN_ROUTE_TTL_MS))
        assertFalse(
            "超过 10 分钟：过期 ⇒ candidates() 必须返回空（并行让位）",
            isRouteFresh(registered, registered + CDN_ROUTE_TTL_MS + 1L)
        )
        assertEquals("有效期口径 = 10 分钟（源仓库同值）", 10L * 60L * 1000L, CDN_ROUTE_TTL_MS)
    }

    /** 行为层：登记之后 `candidates(url)` 必须能拿到候选（否则"并行可用"这个前提就不成立）。 */
    @Test
    fun registeredUrlIsUsableImmediately() {
        val url = "https://upos-sz-mirrorhwb.bilivideo.com/upgcxcode/1/2/3.m4s?deadline=1"
        CdnTransferRuntime.configure(active = true, parallel = true)
        try {
            CdnTransferRuntime.register(video = listOf(url), audio = emptyList())
            assertTrue(
                "登记后 candidates() 应包含该 URL —— 否则真机上并行永远不会触发（正是 10 分钟后的形态）",
                CdnTransferRuntime.candidates(url).contains(url)
            )
        } finally {
            CdnTransferRuntime.configure(active = false, parallel = false)
        }
    }

    /** 接线层：续期必须挂在进度轮询里，且间隔小于有效期。 */
    @Test
    fun engineRefreshesRoutesInsideTheTtlWindow() {
        val engine = codeOf("Media3PlayerEngine.kt")
        assertTrue(
            "`updatePlaybackProgress()`（500ms 轮询）里必须续期路由 —— " +
                "不续期 = 一次播放超过 10 分钟后并行静默降级（真机 soak 实测 cands=0）",
            blockBody(engine, "private fun updatePlaybackProgress(")
                .any { it.contains("refreshCdnRoutesIfStale()") }
        )
        val refresh = blockBody(engine, "private fun refreshCdnRoutesIfStale(")
        assertTrue("续期必须真的重新 register", refresh.any { it.contains("CdnTransferRuntime.register(") })
        assertTrue(
            "续期只能用**当前播放源自己**的候选（不引入新 URL ⇒ 不扩大授权面）",
            refresh.any { it.contains("_currentSource.value as? EngineSource.SingleFileDash") }
        )
        assertTrue(
            "间隔必须小于 10 分钟有效期",
            engine.any { it.contains("CDN_ROUTE_REFRESH_MS = 5 * 60_000L") }
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

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`，无法验证其内部结构。", start >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> {
                        depth++
                        opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        return body
    }
}
