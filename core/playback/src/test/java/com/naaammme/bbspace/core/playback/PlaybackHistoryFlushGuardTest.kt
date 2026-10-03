package com.naaammme.bbspace.core.playback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L4-101 修复的守卫：**进后台时把当前进度落本地历史**。
 *
 * ## 这条修复为什么必须存在（真机实测的缺陷）
 * 本地历史只在 ① 首帧 ② 暂停沿 ③ 播完沿 ④ 会话结束时写；而默认配置下**后台播放是开着的**
 * （前台服务保活）→ "按返回键 / 回桌面"既不暂停也不结束会话 → 进度停在首帧写的 `0:00`。
 * 实测：详情页播放 30s 后返回，历史仍是 `00:00 / 17:44`；显式暂停后同一行立刻变
 * `600.1s / 1063.4s`。
 *
 * ## 三条不变式
 * 1. **必须挂在"进后台"这一侧**（`inBackground == true`）——前台恢复不该写；
 * 2. flush **只写本地**，不许顺带发远端上报（远端上报留在暂停/播完沿，否则每次切后台多一次网络请求）；
 * 3. flush 必须以**会话快照**为入参（进后台瞬间的位置才对应"用户离开时看到的位置"）。
 *
 * 防假绿：扫描前过滤注释与 import 行（本仓注释里必然出现 `localHistoryRecorder.record` 等词）。
 */
class PlaybackHistoryFlushGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun reporterLines(): List<String> {
        val file = File(
            repoRoot,
            "core/playback/src/main/java/com/naaammme/bbspace/core/playback/PlaybackReporter.kt"
        )
        assertTrue("找不到 PlaybackReporter.kt（repoRoot=${repoRoot.absolutePath}）", file.isFile)
        return file.readLines().map { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) "" else line
        }
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

    /** 不变式 1：flush 只挂在 `inBackground` 分支里。 */
    @Test
    fun flushIsGatedOnEnteringBackground() {
        val body = blockBody(reporterLines(), "fun setHostBackground(")
        val gateAt = body.indexOfFirst { it.contains("if (inBackground)") }
        val callAt = body.indexOfFirst { it.contains("flushLocalHistoryOnBackground()") }
        assertTrue("必须调用 flushLocalHistoryOnBackground()", callAt >= 0)
        assertTrue(
            "flush 必须在 `if (inBackground)` 分支内（前台恢复不写历史）gate@$gateAt call@$callAt",
            gateAt >= 0 && gateAt < callAt
        )
    }

    /** 不变式 2：只写本地，不发远端上报。 */
    @Test
    fun flushWritesLocalHistoryOnly() {
        val body = blockBody(reporterLines(), "private fun flushLocalHistoryOnBackground(")
        assertTrue(
            "必须调用 localHistoryRecorder.record(...)",
            body.any { it.contains("localHistoryRecorder.record(") }
        )
        assertTrue(
            "**不许**在进后台时发远端上报（`reportPlaybackHistory`/`recordAndReportPlaybackHistory` " +
                "仍留在暂停/播完沿）",
            body.none { it.contains("reportPlaybackHistory(") } &&
                body.none { it.contains("recordAndReportPlaybackHistory(") }
        )
    }

    /** 不变式 3：以会话快照为入参，且失败不静默。 */
    @Test
    fun flushUsesSessionSnapshotAndLogsFailure() {
        val body = blockBody(reporterLines(), "private fun flushLocalHistoryOnBackground(")
        assertTrue(
            "必须取 `ctx` 的快照（mu.withLock { ctx…copy() }），否则并发改会话会写错对象",
            body.any { it.contains("mu.withLock") } && body.any { it.contains("?.copy()") }
        )
        assertTrue(
            "必须判 `playbackHistoryStarted`（历史还没开始时报进度是无意义的）",
            body.any { it.contains("playbackHistoryStarted") }
        )
        assertTrue(
            "失败必须留日志（不许静默吞掉）",
            body.any { it.contains("Logger.w(TAG)") }
        )
    }

    /**
     * 不变式 4（**主修复**）：播放中必须有**周期性**本地检查点。
     *
     * 为什么它是主修复：`PlaybackHost` 的生命周期观察者只在**后台播放关闭**时才注册
     * （`if (target == null || backgroundPlaybackEnabled) return`），而真机默认后台播放是开的
     * → 退出时那条 flush 根本不会被调用。周期检查点不依赖生命周期注册，因此覆盖
     * "看一半直接退出 / 被系统杀掉"。
     */
    @Test
    fun playbackWritesPeriodicLocalCheckpoints() {
        val lines = reporterLines()
        // 驱动源必须是**进度脉搏**（playbackState 只在变化时发，稳态播放期间不触发）
        assertTrue(
            "PlaybackReporter 必须提供 onPlaybackProgress 作为检查点的驱动入口",
            lines.any { it.contains("fun onPlaybackProgress(") }
        )
        val pulse = blockBody(lines, "fun onPlaybackProgress(")
        assertTrue(
            "脉搏里必须调 checkpointLocalHistory",
            pulse.any { it.contains("checkpointLocalHistory(") }
        )
        val checkpoint = blockBody(lines, "private fun checkpointLocalHistory(")
        assertTrue(
            "检查点必须节流（按 elapsedRealtime 比较 HISTORY_CHECKPOINT_INTERVAL_MS）",
            checkpoint.any { it.contains("HISTORY_CHECKPOINT_INTERVAL_MS") } &&
                checkpoint.any { it.contains("elapsedRealtime()") }
        )
        assertTrue(
            "检查点只在历史已开始且会话未终结时写",
            checkpoint.any { it.contains("playbackHistoryStarted") } &&
                checkpoint.any { it.contains("!it.finalized") }
        )
        assertTrue(
            "检查点只写本地历史，不许发远端上报（避免稳态每分钟多次网络请求）",
            checkpoint.any { it.contains("localHistoryRecorder.record(") } &&
                checkpoint.none { it.contains("reportPlaybackHistory(") }
        )
        assertTrue(
            "间隔常量必须是常量的单一来源（口径写清 5s 的取舍）",
            lines.count { it.contains("const val HISTORY_CHECKPOINT_INTERVAL_MS") } == 1
        )
        // 接线：会话必须把进度脉搏转给上报器（挂在 playbackProgress 收集器里，
        // 不是 playbackState —— 后者稳态不触发）
        val session = File(
            repoRoot,
            "core/playback/src/main/java/com/naaammme/bbspace/core/playback/StreamPlaybackSessionImpl.kt"
        ).readLines()
        val progressCollectorAt = session.indexOfFirst { it.contains("playerEngine.playbackProgress.collect") }
        assertTrue("找不到进度收集器", progressCollectorAt >= 0)
        assertTrue(
            "进度收集器里必须调 reporter.onPlaybackProgress(...)",
            session.drop(progressCollectorAt).take(12).any { it.contains("reporter.onPlaybackProgress(") }
        )
    }
}
