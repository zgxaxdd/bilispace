package com.naaammme.bbspace.core.playback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史上报节流判据的守卫（报告 P2-2 与 P1-2）。
 *
 * 两件事必须同时成立，缺一条这套改动就是"为了省请求把数据省没了"：
 * 1. **该发的必须发**：起播沿、播完沿、真的看了更久之后——一条都不许少；
 * 2. **不该发的不发**：位置没动过的重复沿（连按暂停/播放、暂停后立刻退出）。
 *
 * 另外钉住一条**接线形状**：`syncRemoteProgressIfDue` 必须独立于
 * `checkpointLocalHistory`。那两条不变式是 `PlaybackHistoryFlushGuardTest` 里写死的
 * 设计决策（"检查点只写本地"），把远端调用内联进检查点会直接让它红——
 * 而"红"只是表象，真正的问题是那样做等于**悄悄推翻**了一条有理由的决策。
 */
class PlaybackHistoryReportPolicyTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun reporterLines(): List<String> =
        File(
            repoRoot,
            "core/playback/src/main/java/com/naaammme/bbspace/core/playback/PlaybackReporter.kt"
        ).readLines().map { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) "" else line
        }

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`", start >= 0)
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

    // ── 判据本身 ─────────────────────────────────────────────────────────────

    @Test
    fun pauseEdgeWithNoProgressIsSuppressed() {
        assertFalse(
            "暂停后位置冻住，再报一次是纯重复（'暂停 → 立刻退出'此前会发两次）",
            PlaybackHistoryReportPolicy.shouldReportHistoryProgress(
                positionMs = 30_000L,
                lastReportedPositionMs = 30_000L
            )
        )
    }

    @Test
    fun pauseEdgeAfterRealProgressIsStillSent() {
        assertTrue(
            "真的又看了几秒再暂停，是新信息，必须照发",
            PlaybackHistoryReportPolicy.shouldReportHistoryProgress(
                positionMs = 33_000L,
                lastReportedPositionMs = 30_000L
            )
        )
    }

    @Test
    fun firstPauseOfASessionIsSent() {
        assertTrue(
            "会话里第一次暂停（上次上报位置还是 0）必须发",
            PlaybackHistoryReportPolicy.shouldReportHistoryProgress(
                positionMs = 12_000L,
                lastReportedPositionMs = 0L
            )
        )
    }

    @Test
    fun midSessionRemoteSyncRespectsIntervalAndDelta() {
        val interval = PlaybackHistoryReportPolicy.REMOTE_SYNC_INTERVAL_MS
        // 上一次写回就在 1 分钟前 → 间隔没到，不发
        // （注意 lastSyncElapsedMs 必须用一个**真实发生过**的时刻；
        //  0 在本策略里的语义是"本会话还没写回过"，那种情况不该被间隔挡住）
        assertFalse(
            "距上次写回才 1 分钟，不该发",
            PlaybackHistoryReportPolicy.shouldSyncRemoteProgress(
                nowElapsedMs = interval + 60_000L,
                lastSyncElapsedMs = interval,
                positionMs = 600_000L,
                lastSyncedPositionMs = 300_000L,
                inBackground = false
            )
        )
        // 间隔到了但没实质进展 → 不发
        assertFalse(
            "间隔到了但进度只多了 3s，不值得发一次请求",
            PlaybackHistoryReportPolicy.shouldSyncRemoteProgress(
                nowElapsedMs = 2 * interval,
                lastSyncElapsedMs = interval,
                positionMs = 303_000L,
                lastSyncedPositionMs = 300_000L,
                inBackground = false
            )
        )
        // 间隔 + 增量都满足 → 发
        assertTrue(
            "间隔到了且进度多了 5 分钟，必须补写回（这正是'中途被杀进程'要救的场景）",
            PlaybackHistoryReportPolicy.shouldSyncRemoteProgress(
                nowElapsedMs = 2 * interval,
                lastSyncElapsedMs = interval,
                positionMs = 600_000L,
                lastSyncedPositionMs = 300_000L,
                inBackground = false
            )
        )
        // 本会话还没写回过（lastSyncElapsedMs = 0）：只看增量，不被间隔挡住
        assertTrue(
            "长视频看到第 2 分钟、之前一次都没写回过 → 应该补一次（否则中途被杀就全丢）",
            PlaybackHistoryReportPolicy.shouldSyncRemoteProgress(
                nowElapsedMs = 120_000L,
                lastSyncElapsedMs = 0L,
                positionMs = 120_000L,
                lastSyncedPositionMs = 0L,
                inBackground = false
            )
        )
    }

    @Test
    fun midSessionRemoteSyncNeverHappensInBackground() {
        assertFalse(
            "后台不发远端：与周期心跳同一口径，后台进度由'只写本地'通道负责",
            PlaybackHistoryReportPolicy.shouldSyncRemoteProgress(
                nowElapsedMs = 10 * PlaybackHistoryReportPolicy.REMOTE_SYNC_INTERVAL_MS,
                lastSyncElapsedMs = 0L,
                positionMs = 3_600_000L,
                lastSyncedPositionMs = 0L,
                inBackground = true
            )
        )
    }

    // ── 接线形状 ─────────────────────────────────────────────────────────────

    @Test
    fun remoteSyncIsASeparateMethodFromTheLocalCheckpoint() {
        val lines = reporterLines()
        val checkpoint = blockBody(lines, "private fun checkpointLocalHistory(")
        assertTrue(
            "检查点里不许出现远端调用——PlaybackHistoryFlushGuardTest 不变式 4 明令如此",
            checkpoint.none { it.contains("reportPlaybackHistory(") } &&
                checkpoint.none { it.contains("syncRemoteProgressIfDue(") }
        )
        val sync = blockBody(lines, "private suspend fun syncRemoteProgressIfDue(")
        assertTrue(
            "中途远端写回必须是独立方法，且真的调 remoteReporter.reportPlaybackHistory",
            sync.any { it.contains("remoteReporter.reportPlaybackHistory(") }
        )
        assertTrue(
            "进度脉搏里必须挂上它（否则这条通道永远不会被触发）",
            blockBody(lines, "fun onPlaybackProgress(").any { it.contains("syncRemoteProgressIfDue(") }
        )
    }

    @Test
    fun startAndEndEdgesAreForced() {
        val lines = reporterLines()
        assertTrue(
            "起播沿必须 force=true：它的语义是'证明看过'，与'有没有新进展'无关",
            blockBody(lines, "private suspend fun startPlaybackHistory(").any { it.contains("force = true") }
        )
        // 播完沿：`completePlayback = true` 与 `force = true` 是**多行调用**的两个实参，
        // 不能要求它们出现在同一行 —— 按 onPlaybackState 的整个函数体判。
        val edgeBody = blockBody(lines, "suspend fun onPlaybackState(")
        assertTrue(
            "播完沿必须 force=true（与 completePlayback 一起显式表达'这一沿一定要发'）",
            edgeBody.any { it.contains("completePlayback = true") } &&
                edgeBody.any { it.contains("force = true") }
        )
    }

    @Test
    fun localHistoryIsWrittenRegardlessOfRemoteThrottle() {
        val body = blockBody(reporterLines(), "private suspend fun recordAndReportPlaybackHistory(")
        val localAt = body.indexOfFirst { it.contains("localHistoryRecorder.record(") }
        val gateAt = body.indexOfFirst { it.contains("shouldSendRemote") }
        assertTrue("本地记录必须还在", localAt >= 0)
        assertTrue(
            "本地记录必须在远端门控**之前**（本地历史是续播真值源，不能被省请求的门控挡掉）" +
                "local@$localAt gate@$gateAt",
            gateAt < 0 || localAt < gateAt
        )
    }
}
