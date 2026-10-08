package com.naaammme.bbspace.feature.video.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页两处"每帧成本"的接线守卫（R2 玻璃重录节流 / U2 手势 HUD 绘制层化）。
 *
 * ## 为什么这两条放在一起、并且要写成"正反一对"
 *
 * 两项改的都是**写法层面的不变式**：JVM 上跑不出行为差异（本仓没有 Robolectric），
 * 但改回去就是静默变慢 —— 逐帧 GPU 重录 / 拖动期每帧 relayout。
 * 单靠"必须存在 X"的正向断言不够：把节流删掉、同时把 `shouldRecord` 留在注释里，
 * 正向断言照样绿。所以每条都配一条**"旧写法不得回来"**的反向断言。
 *
 * 扫描按 W20/W21 的口径：跳过注释与 import 行，判据匹配**调用/写法本身**而不是名字，
 * 并且先确认真的取到了函数体（取不到直接失败，避免"扫到空列表 ⇒ 反向断言假绿"）。
 */
class VideoPlayerRenderCostGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    private fun linesOf(uniqueSuffix: String): List<String> {
        val normalized = uniqueSuffix.replace('/', File.separatorChar)
        val matches = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${FS}src${FS}main${FS}") }
            .filterNot { it.path.contains("${FS}build${FS}") }
            .filter { it.path.endsWith(normalized) }
            .toList()
        assertEquals(
            "后缀 `$uniqueSuffix` 命中 ${matches.size} 个文件（要求恰好 1）：" +
                matches.joinToString { it.path },
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

    private val pane by lazy {
        linesOf("feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoPlayerPane.kt")
    }
    private val gesture by lazy {
        linesOf("feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoGestureController.kt")
    }

    // ---- R2：玻璃背板重录必须按时间节流 ----

    @Test
    fun glassTickLoopIsThrottledByTime() {
        val starts = pane.indices.filter {
            pane[it].trimStart().startsWith("LaunchedEffect(videoGlassActive)")
        }
        assertTrue("找不到玻璃 tick 协程（改名了？）", starts.isNotEmpty())
        val body = starts.map { blockBodyAt(pane, it) }
            .firstOrNull { candidate -> candidate.any { it.contains("videoGlassTick++") } }
        assertTrue("找不到包含 videoGlassTick++ 的协程体", body != null)
        val tick = body!!
        assertTrue(
            "tick 递增必须受 VideoGlassTickPolicy 闸门控制（否则又是逐帧重录整层视频）",
            tick.any { it.contains("VideoGlassTickPolicy.shouldRecord(") }
        )
        assertTrue(
            "基线要在录过时前移（只判不更新 = 第一个间隔之后每帧都满足条件，节流等于没做）",
            tick.any { it.contains("lastTickNanos = frameTimeNanos") }
        )
    }

    @Test
    fun unthrottledTickFormDoesNotComeBack() {
        // 反向断言：`withFrameNanos { }` + 无条件 `videoGlassTick++` 就是被替换掉的满帧率写法。
        val starts = pane.indices.filter {
            pane[it].trimStart().startsWith("LaunchedEffect(videoGlassActive)")
        }
        val bodies = starts.map { blockBodyAt(pane, it) }
            .filter { candidate -> candidate.any { it.contains("videoGlassTick++") } }
        assertTrue("节流后的协程体应当只剩一个，实际 ${bodies.size} 个", bodies.size == 1)
        val tick = bodies.single()
        assertTrue(
            "不得再出现空参 withFrameNanos（那意味着 tick 又在帧回调之外无条件递增）",
            tick.none { it.contains("withFrameNanos { }") }
        )
        val recordAt = tick.indexOfFirst { it.contains("VideoGlassTickPolicy.shouldRecord(") }
        val incrementAt = tick.indexOfFirst { it.contains("videoGlassTick++") }
        assertTrue("间隔判断必须在前，递增在后", recordAt in 0 until incrementAt)
    }

    // ---- U2：手势 HUD 的进度条不得再驱动布局 ----

    @Test
    fun gestureHudBarIsDrawnNotLaidOut() {
        val body = blockBody(gesture, "fun VideoGestureFeedback(")
        val canvasAt = body.indexOfFirst { it.trimStart().startsWith("Canvas(") }
        assertTrue("HUD 的填充条必须用 Canvas 画（找不到 Canvas 说明换回了布局驱动）", canvasAt >= 0)
        val canvasBody = blockBodyAt(body, canvasAt)
        assertTrue(
            "dragFraction 必须在 draw 阶段读（放在组合期读会让整块 HUD 每帧重组）",
            canvasBody.any { it.contains("state.dragFraction") }
        )
        assertTrue("必须真的画出来", canvasBody.any { it.contains("drawRoundRect(") })
    }

    @Test
    fun fractionDrivenLayoutHeightDoesNotComeBack() {
        // 反向断言：`.height((100 * barH).dp)` 是"拖动期间每帧一次 relayout"的那一行。
        val body = blockBody(gesture, "fun VideoGestureFeedback(")
        assertTrue(
            "HUD 里不得再出现按分数算高度的 Modifier.height(...)",
            body.none { it.contains(".height((100 *") }
        )
        assertTrue(
            "组合期把 dragFraction 取成局部变量的写法也不许回来（那等于回到每帧重组）",
            body.none { it.contains("val barH = state.dragFraction") }
        )
    }

    /** 从给定下标开始按花括号配对取函数体（与 [blockBody] 同规则，但入口是下标）。 */
    private fun blockBodyAt(lines: List<String>, start: Int): List<String> {
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
