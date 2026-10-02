package com.naaammme.bbspace.core.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续播判定的状态测（PT-03，语义移植自上游 BiliPai `PlaybackResumePolicy.kt:4-9`）。
 *
 * 上游 8 行逻辑逐条有对应断言（要点 ①②）；额外钉住：
 * - bbspace 保留的"贴结尾 3s"规则与上游"95%"规则**取或**（短视频/长视频各有覆盖）；
 * - 显式 seek 不触发 95% 重播（上游同款：95% 检查只对非显式候选生效）；
 * - 钳位边界（上游 `duration−1000` 规则，bbspace 本版补齐）；
 * - 两个同名 `COMPLETE_THRESHOLD_MS` 收敛为 1 处（要点 ④）。
 */
class PlaybackResumePolicyTest {

    // ── 显式 seek 永远优先（且不触发 95% 重播，但会被钳位） ─────────────────────

    @Test
    fun explicitSeekWinsOverLocalAndServer() {
        assertEquals(
            50_000L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = 50_000L, localMs = 10_000L, localFinished = false,
                serverMs = 20_000L, durationMs = 100_000L
            )
        )
    }

    /** 上游同款：95% 检查带 `explicitMs == null` 前置——显式 seek 到 99% 也照样续播（仅钳位）。 */
    @Test
    fun explicitSeekNearEndIsNotRestartedButIsClamped() {
        assertEquals(
            99_000L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = 99_500L, localMs = null, localFinished = false,
                serverMs = null, durationMs = 100_000L
            )
        )
    }

    // ── 本地 / 服务端候选 ────────────────────────────────────────────────────

    @Test
    fun localBelow95PercentResumes() {
        assertEquals(
            40_000L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = null, localMs = 40_000L, localFinished = false,
                serverMs = 20_000L, durationMs = 100_000L
            )
        )
    }

    /** 94.9%（上游边界）：不触发重播。 */
    @Test
    fun justBelow95PercentStillResumes() {
        assertEquals(
            94_900L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = null, localMs = 94_900L, localFinished = false,
                serverMs = null, durationMs = 100_000L
            )
        )
    }

    /** 恰好 95%（上游边界）：从 0 重播。破坏性验证：把 0.95 改 1.0 → 本条必须红。 */
    @Test
    fun exactly95PercentRestartsFromZero() {
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = null, localMs = 95_000L, localFinished = false,
                serverMs = 10_000L, durationMs = 100_000L
            )
        )
    }

    /** 服务端候选同样受 95% 规则约束（无显式 seek 时）。 */
    @Test
    fun serverAbove95PercentRestartsFromZero() {
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = null, localMs = null, localFinished = false,
                serverMs = 96_000L, durationMs = 100_000L
            )
        )
    }

    /** 本地"已看完"标记 → 跳过本地，用服务端。 */
    @Test
    fun locallyFinishedFallsBackToServer() {
        assertEquals(
            20_000L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = null, localMs = 40_000L, localFinished = true,
                serverMs = 20_000L, durationMs = 100_000L
            )
        )
    }

    /** 本地 ≥95% 时直接从 0 重播，**不回退服务端**（上游候选语义；bbspace 原实现会回退
     *  到服务端——有意的行为变更，见 PlaybackResumePolicy KDoc）。 */
    @Test
    fun watchedThroughLocalBeatsServer() {
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = null, localMs = 96_000L, localFinished = false,
                serverMs = 10_000L, durationMs = 100_000L
            )
        )
    }

    /** 都无效（null / 非正数）→ 从 0 开始。 */
    @Test
    fun invalidCandidatesStartFromZero() {
        assertEquals(0L, PlaybackResumePolicy.resolveResumePosition(null, null, false, null, 100_000L))
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(null, 0L, false, -5L, 100_000L)
        )
    }

    // ── bbspace 保留的"贴结尾 3s"规则（与 95% 取或） ──────────────────────────

    /** 短视频既有行为：30s 视频的最后 3s 内 → 重播（3s 规则比 95% 更严，保住现状）。 */
    @Test
    fun shortVideoTailRuleStillApplies() {
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = null, localMs = 28_000L, localFinished = false,
                serverMs = null, durationMs = 30_000L
            )
        )
        // 4s 距离（86.7%）：两规则都不命中 → 正常续播
        assertEquals(
            26_000L,
            PlaybackResumePolicy.resolveResumePosition(
                explicitMs = null, localMs = 26_000L, localFinished = false,
                serverMs = null, durationMs = 30_000L
            )
        )
    }

    // ── duration <= 0（未知时长） ────────────────────────────────────────────

    /** 上游同款：未知时长不判看完、不钳位，只保底 ≥0。 */
    @Test
    fun unknownDurationSkipsWatchedThroughAndClamp() {
        assertEquals(
            50_000L,
            PlaybackResumePolicy.resolveResumePosition(50_000L, null, false, null, 0L)
        )
        assertEquals(
            10_000L,
            PlaybackResumePolicy.resolveResumePosition(null, 10_000L, false, null, 0L)
        )
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(-1L, null, false, null, 0L)
        )
    }

    // ── 钳位（上游 duration−1000 规则，bbspace 本版补齐） ─────────────────────

    @Test
    fun clampBoundaries() {
        // 钳位对**显式 seek** 生效（上游同款：95% 检查在钳位之前，且 95% 只看非显式候选）：
        // 超出 duration−1000 → 钳到 duration−1000
        assertEquals(
            99_000L,
            PlaybackResumePolicy.resolveResumePosition(99_500L, null, false, null, 100_000L)
        )
        assertEquals(
            99_000L,
            PlaybackResumePolicy.resolveResumePosition(100_000L, null, false, null, 100_000L)
        )
        // 负显式 → 钳到 0
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(-100L, null, false, null, 100_000L)
        )
        // 短时长（duration−1000 < 0）→ 钳到 0
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(400L, null, false, null, 500L)
        )
        // 非显式候选 ≥95% → 先判"已看完"→ 0（钳位轮不到它）
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(null, 99_000L, false, null, 100_000L)
        )
        assertEquals(
            0L,
            PlaybackResumePolicy.resolveResumePosition(null, 100_000L, false, null, 100_000L)
        )
    }

    // ── isWithinTail（上报侧） ───────────────────────────────────────────────

    @Test
    fun withinTailMatchesLegacySemantics() {
        assertTrue(PlaybackResumePolicy.isWithinTail(98_000L, 100_000L))
        assertFalse(PlaybackResumePolicy.isWithinTail(90_000L, 100_000L))
        assertFalse("未知时长不判贴尾", PlaybackResumePolicy.isWithinTail(0L, 0L))
    }

    // ── 结构断言：常量收敛（要点 ④） ─────────────────────────────────────────

    /**
     * 两个同名 `COMPLETE_THRESHOLD_MS = 3_000L` 已收敛到
     * `PlaybackResumePolicy.COMPLETE_TAIL_MS` 一处——旧名字全仓必须为 0。
     */
    @Test
    fun constantLivesInExactlyOnePlace() {
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: File("").absoluteFile
        val mainSources = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
        val oldName = mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) {
                    return@mapIndexedNotNull null
                }
                if (line.contains("COMPLETE_THRESHOLD_MS")) "${file.name}:${index + 1}" else null
            }
        }
        assertTrue("旧常量名 COMPLETE_THRESHOLD_MS 必须为 0 处（已收敛）：$oldName", oldName.isEmpty())
        val policy = File(
            repoRoot,
            "core/playback/src/main/java/com/naaammme/bbspace/core/playback/PlaybackResumePolicy.kt"
        )
        assertTrue("PlaybackResumePolicy.kt 不见了", policy.isFile)
        val tailDefs = policy.readLines().count {
            it.contains("const val COMPLETE_TAIL_MS") && !it.trimStart().startsWith("//")
        }
        assertEquals("COMPLETE_TAIL_MS 必须只定义一处", 1, tailDefs)
    }
}
