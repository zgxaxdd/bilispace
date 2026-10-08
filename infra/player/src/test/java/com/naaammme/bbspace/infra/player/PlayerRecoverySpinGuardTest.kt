package com.naaammme.bbspace.infra.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「断网时播放器重建自旋」的回归守卫（真机实测缺陷，2026-10-06 PLC110）。
 *
 * ## 缺陷形态
 *
 * `onRenderedFirstFrame` 里无条件 `rebuildThrottle.reset(); networkRetryCount = 0`。
 * 断网后 `PlaybackMediaCache` 的缓存**仍能供出 1 帧** ⇒ 每次出帧都把两道预算清零 ⇒
 * 引擎每 ~3.3 秒重建一次 ExoPlayer。真机日志：55 秒内 `recovering from system error` **28 次**，
 * `retained released players after recover` 涨到 16，**0 次** `recover abandoned`（预算从未生效）。
 *
 * ## 修法（本守卫钉住的四条）
 *
 * 1. 出帧只记时刻（`recoveryFirstFrameAtMs`），**不再直接清零预算**；
 * 2. 稳定播放满 [RECOVERY_SETTLE_MS] 才在进度轮询里清零（而轮询只在"正在播放且 READY"时跑）；
 * 3. 再出错立即作废稳定期（`recoveryFirstFrameAtMs = -1L`）；
 * 4. 断网时**不为网络错误重建**（直接落错误态，交给 UI 的重试路径）。
 *
 * 结构断言（与 `CdnPlaybackRuntimeFeedTest` 同风格）：这类"接线被改回去"的缺陷只有
 * 源码级断言能拦住 —— 引擎依赖 ExoPlayer/Android 运行时，行为层无法在 JVM 单测里复现。
 */
class PlayerRecoverySpinGuardTest {

    @Test
    fun firstFrameAloneMustNotClearTheRecoveryBudget() {
        val body = blockBody(codeOf("Media3PlayerEngine.kt"), "override fun onRenderedFirstFrame()")
        assertFalse(
            "出帧回调里不许直接清零重建预算 —— 缓存里的一帧会冒充'恢复成功'，" +
                "断网时表现为每 ~3s 重建一次的活锁（真机 55s / 28 次）。" +
                "清零必须等 isRecoverySettled() 判定稳定播放满 RECOVERY_SETTLE_MS。",
            body.any { it.contains("rebuildThrottle.reset()") } ||
                body.any { it.contains("networkRetryCount = 0") }
        )
        assertTrue(
            "出帧必须记下时刻，供稳定期判定使用",
            body.any { it.contains("recoveryFirstFrameAtMs = android.os.SystemClock.elapsedRealtime()") }
        )
    }

    @Test
    fun budgetIsClearedOnlyAfterSustainedPlaybackInProgressPolling() {
        val lines = codeOf("Media3PlayerEngine.kt")
        val body = blockBody(lines, "private fun updatePlaybackProgress(")
        assertTrue(
            "稳定期判定（isRecoverySettled）必须落在进度轮询里 —— 该轮询只在" +
                "`isPlaying && STATE_READY` 时运行，所以「能走到这里」本身就是「确实在播」的证据。",
            body.any { it.contains("isRecoverySettled(") }
        )
        assertTrue("判定通过后才能清零重建防抖窗口", body.any { it.contains("rebuildThrottle.reset()") })
        assertTrue("判定通过后才能清零网络重试计数", body.any { it.contains("networkRetryCount = 0") })
    }

    @Test
    fun errorInvalidatesTheSettleWindow() {
        val body = blockBody(codeOf("Media3PlayerEngine.kt"), "override fun onPlayerError(error: PlaybackException)")
        assertTrue(
            "错误一到就要作废稳定期（否则上一段的出帧时刻会被算成本次恢复成功）",
            body.any { it.contains("recoveryFirstFrameAtMs = -1L") }
        )
    }

    @Test
    fun offlineNetworkErrorsAreNotRebuiltAtAll() {
        val body = blockBody(codeOf("Media3PlayerEngine.kt"), "override fun onPlayerError(error: PlaybackException)")
        assertTrue(
            "网络类错误必须先判「当前有没有网」：断网时重建 ExoPlayer 没有任何意义",
            body.any { it.contains("isNetworkPlaybackError(error.errorCode)") } &&
                body.any { it.contains("networkTypeProvider.isNetworkAvailable()") }
        )
    }

    @Test
    fun newSessionAlsoResetsTheSettleMarker() {
        val body = blockBody(codeOf("Media3PlayerEngine.kt"), "private fun resetRuntimeState()")
        assertTrue(
            "换源/重建后要作废稳定期计时，否则会把上一个会话的出帧时刻算进来",
            body.any { it.contains("recoveryFirstFrameAtMs = -1L") }
        )
    }

    /** 稳定期常量只能有一个来源（策略文件），引擎不许写死毫秒。 */
    @Test
    fun settleDurationHasASingleSource() {
        val engine = codeOf("Media3PlayerEngine.kt")
        assertFalse(
            "引擎里不许写死稳定期毫秒数（单一来源是 PlayerErrorRecoveryPolicy.RECOVERY_SETTLE_MS）",
            engine.any { it.contains("10_000L") }
        )
        assertTrue(
            "常量必须定义在 PlayerErrorRecoveryPolicy.kt 里",
            codeOf("PlayerErrorRecoveryPolicy.kt").any { it.contains("const val RECOVERY_SETTLE_MS") }
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
