package com.naaammme.bbspace.feature.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PB-02 的**意图分离**守卫：关键帧 seek 只能出现在"用户主动"这一条路上。
 *
 * ## 为什么这是本项最关键的不变式
 * bbspace 里"seek"至少有四类来源共用同一批入口：用户拖进度条、SponsorBlock 跳段、
 * 自动片尾、续播/恢复。上游的做法是"设置项开着 → **所有** seek 都吸附关键帧"，
 * 那在本仓会出两个实际后果：
 * ① 跳段被吸附到关键帧 → 向后吸附会重新落进片段内（触发一次重入）；
 * ② 续播位置被吸附成粗值 → 被 `LocalPlaybackHistoryRecorder` 写回历史。
 * 所以本项**按意图分档**，并把"哪条路用哪一档"钉死。
 *
 * 防假绿：先确认文件读到；扫描前过滤注释与 import 行（本仓注释里必然出现
 * `forUserSeek`、`seekUserInitiated` 这些词）。
 */
class SeekIntentSeparationGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    private fun codeOf(uniqueSuffix: String): List<String> {
        val normalized = uniqueSuffix.replace('/', File.separatorChar)
        val matches = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${FS}src${FS}main${FS}") }
            .filterNot { it.path.contains("${FS}build${FS}") }
            .filter { it.path.endsWith(normalized) }
            .toList()
        // 后缀必须唯一：命中多个 = 断言可能跑在错的文件上（NX 批次的 W36 教训）
        assertEquals(
            "后缀 `$uniqueSuffix` 命中 ${matches.size} 个文件（要求恰好 1）：" +
                matches.joinToString { it.path } + " —— 请用更长的路径后缀。",
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

    private fun engine() = codeOf("infra${FS}player${FS}Media3PlayerEngine.kt")
    private fun vm() = codeOf("video${FS}VideoViewModel.kt")

    // ── 验收 2：成对出现（锚在调用本身） ─────────────────────────────────────

    @Test
    fun userSeekSetsThenRestoresParametersInOneFunction() {
        val body = blockBody(engine(), "override fun seekUserInitiated(")
        val setFastAt = body.indexOfFirst { it.contains("setSeekParameters(SeekParametersPolicy.forUserSeek())") }
        val seekAt = body.indexOfFirst { it.contains("player.seekTo(") }
        val restoreAt = body.indexOfFirst { it.contains("setSeekParameters(SeekParametersPolicy.forPreciseSeek())") }
        assertTrue("用户 seek 必须先设 CLOSEST_SYNC", setFastAt >= 0)
        assertTrue("用户 seek 必须真的 seek", seekAt >= 0)
        assertTrue("用户 seek 必须**恢复** DEFAULT（否则吸附意图泄漏给后续内部 seek）", restoreAt >= 0)
        assertTrue(
            "顺序必须是 设档 → seek → 恢复（当前 setFast@$setFastAt seek@$seekAt restore@$restoreAt）",
            setFastAt < seekAt && seekAt < restoreAt
        )
    }

    // ── 验收 3：续播路径必须是精确的 ─────────────────────────────────────────

    @Test
    fun resumePathIsPreciseNotSnapped() {
        val setSource = blockBody(engine(), "override suspend fun setSource(")
        assertTrue(
            "续播/恢复（setSource 带 startPositionMs）必须显式断言精确档 —— " +
                "吸附后的粗值会被写回历史记录。",
            setSource.any { it.contains("setSeekParameters(SeekParametersPolicy.forPreciseSeek())") }
        )
        assertTrue(
            "**续播路径不许出现用户档**（CLOSEST_SYNC / forUserSeek）",
            setSource.none { it.contains("forUserSeek") }
        )
    }

    @Test
    fun preciseSeekReassertsDefaultEveryTime() {
        val body = blockBody(engine(), "override fun seekTo(")
        assertTrue(
            "精确 seek 每次都要重新断言 DEFAULT：媒体3 的 setSeekParameters 是**保持状态**，" +
                "上次用户拖拽设的 CLOSEST_SYNC 会残留到下一次跳段/续播。",
            body.any { it.contains("setSeekParameters(SeekParametersPolicy.forPreciseSeek())") }
        )
        assertTrue(
            "精确 seek 不许出现用户档",
            body.none { it.contains("forUserSeek") }
        )
    }

    // ── 验收 4：内部跳转仍走精确入口 ─────────────────────────────────────────

    @Test
    fun internalJumpsKeepUsingThePreciseEntry() {
        val lines = vm()
        // SponsorBlock 跳段与自动片尾都调 seekTo(...)，不许改成 seekUserInitiated(...)
        val userSeekCalls = lines.filter { it.contains("seekUserInitiated(") }
        assertTrue(
            "VideoViewModel 里 `seekUserInitiated` 只允许出现在它自己的**定义**与转调处，" +
                "内部跳转一律用精确的 seekTo。实际命中：$userSeekCalls",
            userSeekCalls.size <= 2
        )
        assertTrue(
            "SponsorBlock / 自动片尾的跳转必须仍是 `seekTo(`（吸附会让跳段重新落进片段内）",
            lines.any { it.contains("seekTo(seg.endMs)") } ||
                lines.any { it.contains("seekTo(seg.startMs)") } ||
                lines.any { it.contains("seekTo(autoEndMs)") }
        )
    }

    /** 用户主动 seek 的唯一 UI 入口是进度条拖拽松手（onSeekDone）。 */
    @Test
    fun uiDragReleaseIsTheOnlyUserSeekEntry() {
        val pane = codeOf("player${FS}VideoPlayerPane.kt")
        val callSites = pane.filter { it.contains("seekUserInitiated(") }
        assertEquals(
            "播放页只允许一处用户 seek 入口（进度条拖拽松手）；" +
                "多出来的入口必须逐个说明意图。实际：$callSites",
            1,
            callSites.size
        )
        assertTrue(
            "该入口必须挂在 onSeekDone（拖拽松手）回调块内 —— 注意两个 token 跨行，不能要求同一行。",
            run {
                val start = pane.indexOfFirst { it.contains("onSeekDone = {") }
                start >= 0 && pane.drop(start).take(8).any { it.contains("seekUserInitiated(") }
            }
        )
    }
}
