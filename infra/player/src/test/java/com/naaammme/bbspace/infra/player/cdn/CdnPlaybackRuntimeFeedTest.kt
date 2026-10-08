package com.naaammme.bbspace.infra.player.cdn

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 07 复核报告 **P1-A** 的验收：`CdnTransferRuntime.playback(bufferMs, bitrateBps)` 必须是
 * "被喂过"的状态 —— 它是自适应并发的唯一输入。
 *
 * 移植时漏掉的正是这一处调用点：文件搬过来了、并行分片也接线了，但 `bufferMs` 恒为 0，
 * `resolveCdnConnections` 永远走 `< 5_000 → 3`，自适应部分**静默失效**（BiliPai 在
 * `VideoPlaybackViewModel.startPluginCheck()` / `onPlaybackStateChanged` 两处喂它）。
 *
 * 本测试分两层：
 * 1. **行为层**（可证伪）：喂不同的 `bufferMs` / 码率，`connectionLimit()` 必须真的变；
 * 2. **接线层**（结构断言，防"文件在、调用点没了"再次发生）：`Media3PlayerEngine` 的
 *    进度轮询里必须出现 `CdnTransferRuntime.playback(`。
 */
class CdnPlaybackRuntimeFeedTest {

    @Before
    fun setUp() {
        CdnTransferRuntime.configure(active = true, parallel = true)
        CdnTransferRuntime.playback(bufferMs = 0L, bitrateBps = 0L)
    }

    @After
    fun tearDown() {
        CdnTransferRuntime.configure(active = false, parallel = false)
        CdnTransferRuntime.playback(bufferMs = 0L, bitrateBps = 0L)
    }

    /** 行为层：缓冲高度直接决定并发数（`resolveCdnConnections` 的三个分支都要走到）。 */
    @Test
    fun bufferHeightDrivesConnectionCount() {
        CdnTransferRuntime.playback(bufferMs = 3_000L, bitrateBps = 0L)
        assertEquals("缓冲 < 5s 应抢时间（3 连接）", 3, CdnTransferRuntime.connectionLimit())

        CdnTransferRuntime.playback(bufferMs = 20_000L, bitrateBps = 0L)
        assertEquals("缓冲充裕但不到 30s 应回落到 2 连接", 2, CdnTransferRuntime.connectionLimit())

        CdnTransferRuntime.playback(bufferMs = 31_000L, bitrateBps = 0L)
        assertEquals("缓冲 ≥ 30s 不该再占带宽（1 连接）", 1, CdnTransferRuntime.connectionLimit())
    }

    /** 行为层：码率追不上实测速率时也要抢（这条是"弱网不卡"的判据）。 */
    @Test
    fun bitrateOutrunningMeasuredSpeedForcesThreeConnections() {
        CdnTransferRuntime.playback(bufferMs = 20_000L, bitrateBps = 80_000_000L)
        assertEquals(
            "缓冲 20s 但码率远高于实测速率 ⇒ 仍要 3 连接",
            3,
            CdnTransferRuntime.connectionLimit()
        )
    }

    /** 接线层：`Media3PlayerEngine` 的进度轮询必须把真实缓冲/码率喂进去。 */
    @Test
    fun engineFeedsBufferAndBitrateIntoRuntime() {
        val body = blockBody(codeOf("Media3PlayerEngine.kt"), "private fun updatePlaybackProgress(")
        assertTrue(
            "`updatePlaybackProgress()` 里找不到 `CdnTransferRuntime.playback(` —— " +
                "自适应并发就失去输入（bufferMs 恒 0）。BiliPai 的对应点见 " +
                "`VideoPlaybackViewModel.startPluginCheck()`。",
            body.any { it.contains("CdnTransferRuntime.playback(") }
        )
        assertTrue(
            "喂进去的缓冲必须是 `bufferedPosition - currentPosition`（剩余缓冲），" +
                "不能是绝对播放位置。",
            body.any { it.contains("player.bufferedPosition - player.currentPosition") }
        )
        assertTrue(
            "码率必须把视频与音频相加（BiliPai 同式：video.bitrate + audio.bitrate）。",
            body.any { it.contains("player.videoFormat?.bitrate") } &&
                body.any { it.contains("player.audioFormat?.bitrate") }
        )
    }

    /** 接线层：DASH 分段预取必须真的被调度（源 `scheduleCdnDashPrefetch()` 的移植点）。 */
    @Test
    fun engineSchedulesDashSegmentPrefetchBehindFourGates() {
        val engine = codeOf("Media3PlayerEngine.kt")
        assertTrue(
            "找不到 `cdnSegmentPrefetcher.prefetch(` —— 预取器又变成孤儿了（08 报告 G1 复发）",
            engine.any { it.contains("cdnSegmentPrefetcher.prefetch(") }
        )
        val body = blockBody(engine, "private fun scheduleCdnDashPrefetch(")
        assertTrue("闸门 1：设置项 cdnAdaptivePrefetch", body.any { it.contains("playerConfig.cdnAdaptivePrefetch") })
        assertTrue(
            "闸门 2/3：缓冲必须复用段数策略本身（缓冲薄时它就是 0）",
            body.any { it.contains("resolveCdnPrefetchSegmentCount(") }
        )
        assertTrue("闸门 4：只在不限流量的网络下预取", body.any { it.contains("networkTypeProvider.isUnmetered()") })
        assertTrue("READY 才预取", body.any { it.contains("Player.STATE_READY") })
        assertTrue("必须可取消（caller-owned）", engine.any { it.contains("cdnPrefetchJob?.cancel()") })
        assertTrue(
            "闸门 3 的条件（缓冲 ≥15s）在 READY 那一刻通常不成立 ⇒ 必须在 500ms 进度轮询里复查" +
                "（否则等于永远不预取：2026-10-06 真机验证抓到的实际缺陷）",
            blockBody(engine, "private fun updatePlaybackProgress(")
                .any { it.contains("maybeScheduleCdnDashPrefetch()") }
        )
        assertTrue(
            "复查必须带前沿步长判据（否则每 500ms 重读一次 sidx = 拿流量换重复劳动）",
            engine.any { it.contains("CDN_PREFETCH_FRONTIER_STEP_MS") } &&
                blockBody(engine, "private fun maybeScheduleCdnDashPrefetch(")
                    .any { it.contains("cdnPrefetchFrontierMs") }
        )
    }

    /** 接线层：并行开关必须来自设置项，不能又写死 true（否则设置页开关形同虚设）。 */
    @Test
    fun parallelSwitchComesFromSettingsNotAHardcodedTrue() {
        val factory = codeOf("SingleFileDashMediaSourceFactory.kt")
        assertTrue(
            "`configure(active = true, parallel = cdnParallel)`：开关必须从参数传入",
            factory.any { it.contains("configure(active = true, parallel = cdnParallel)") }
        )
        assertTrue(
            "开关要一路从 PlayerConfig 传到工厂",
            codeOf("Media3PlayerEngine.kt").any { it.contains("cdnParallel = playerConfig.cdnParallelDownload") }
        )
    }

    /** 接线层：预取取消必须能打断在途传输（源仓库的那段 `finally` 块，首次移植时漏了）。 */
    @Test
    fun prefetchCancellationInterruptsTheInflightTransport() {
        val cache = codeOf("PlaybackMediaCache.kt")
        assertTrue("必须调 writer.cancel()", cache.any { it.contains("writer.cancel()") })
        assertTrue(
            "必须打断上游传输中的 socket（否则「取消」要等整段读完）",
            cache.any { it.contains("cancelPendingRequests()") }
        )
    }

    /**
     * 选线闭环的**可判定证据**：prepare 时必须同时留下"服务端原始次序"与"健康度排序后次序"。
     *
     * 2026-10-06 真机 A/B（`docs/port-analysis/evidence/2026-10-06-cdn-health-loop-device.md`）
     * 正是靠这条日志证明：同一个候选集合，把某条线重罚后它会从第 3 位跳到第 1 位 ——
     * 没有它，"健康度有没有参与排序"在真机上只能靠结果猜（服务端候选每次还会轮换）。
     */
    @Test
    fun selectionLogsServerOrderAgainstRankedOrder() {
        val session = codeOf("StreamPlaybackSessionImpl.kt")
        assertTrue(
            "选线处必须记 `server=[…] ranked=[…] chosen=…`（选线闭环的唯一可判定证据）",
            session.any { it.contains("\"server=[\$server] ranked=[\$ranked] chosen=") }
        )
        assertTrue(
            "还要记 mode 与观测条数：`observations=0` 会走进'无观测护栏'，是排障第一问",
            session.any { it.contains("observations=\${cdnHealthSnapshot.size}") }
        )
    }
    /**
     * 现场取证：索引**读通**时必须留下"用的是哪条线路"。
     *
     * 失败分支一直有 `index attempt FAILED host=`；成功分支若不记，
     * "健康度排出来的第一条真的被用了"在真机上就**无法证伪** —— 2026-10-06 的真机核验
     * 正是靠 `index ok host=…` 证明"被扣分的 host 会被排到后面"（证据见
     * `docs/port-analysis/evidence/2026-10-06-cdn-health-loop-device.md`）。
     */
    @Test
    fun indexSuccessLogsTheHostThatWorked() {
        val factory = codeOf("SingleFileDashMediaSourceFactory.kt")
        assertTrue(
            "索引读通时必须记 `index ok host=…`（闭环可证伪的观测口）",
            factory.any { it.contains("\"index ok host=") }
        )
        assertTrue(
            "失败分支的 host 留痕不许被删",
            factory.any { it.contains("\"index attempt FAILED host=") }
        )
        assertTrue(
            "候选集合与轨道也要留痕：`index candidates track=video/audio hosts=…` —— " +
                "服务端候选每次轮换，没有它无法判断'排序到底改了什么'",
            factory.any { it.contains("\"index candidates track=") }
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
