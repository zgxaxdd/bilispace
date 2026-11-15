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

    // ---- C1f：亮度/音量手势 HUD 必须能一眼分开 ----

    /**
     * C1f（2026-10-13，上游 v0.3.6 同步）：亮度与音量**不得共用同一种强调色**。
     *
     * 缺陷：两者此前都用 `Color.White` —— 同样的黑底、同样的白字、同样的白色填充条，
     * 只有一行"亮度/音量"文字不同。左侧滑（亮度）与右侧滑（音量）是两块**肌肉记忆区**，
     * 配色一致时用户没法靠余光确认自己滑的是哪一个。
     *
     * 修法：亮度用固定暖黄（与主题解耦 —— 亮度是设备物理量），音量走主题主色。
     * 上游 `bbf0587f9` 的测试断言的就是这个**分歧**（从
     * `md3Colors_followThemeForBothLevels` 改名为 `md3Colors_keepBrightnessIndependentOfTheme`）。
     *
     * 正反一对：① 三处上色（label / 轨道底 / 填充条）都必须走 `accent`；
     * ② `Color.White` 不得再出现在这块 HUD 的强调位上（退回原形态即红）。
     */
    @Test
    fun gestureHudDistinguishesBrightnessFromVolume() {
        val body = blockBody(gesture, "fun VideoGestureFeedback(")
        // 只取**亮度/音量那一块**（从 dragType 分流到 Seek 分支之前）。
        // 不能扫整个 VideoGestureFeedback：同函数里的**快进**提示（`dragType == Seek`）
        // 本来就该是白字黑底 —— 它跟亮度/音量不是同一组、也不需要区分（快进没有
        // "另一个长得一样的手势"）。扫全函数会把那条合法用法误判成违规。
        val blockStart = body.indexOfFirst {
            it.contains("state.dragType == DragType.Brightness || state.dragType == DragType.Volume")
        }
        assertTrue("找不到亮度/音量 HUD 分支，无法验证 C1f。", blockStart >= 0)
        val blockEnd = body.indexOfFirst { it.contains("state.dragType == DragType.Seek && state.seekLabel != null") }
        assertTrue(
            "找不到 Seek 分支的起点 —— 亮度/音量块的边界取不到，本断言会退化成假绿。",
            blockEnd > blockStart
        )
        val levelHud = body.subList(blockStart, blockEnd)

        assertTrue(
            "亮度/音量的强调色必须由 isBrightness 分流（找不到 accent 定义说明退回单一颜色）",
            levelHud.any { it.contains("if (isBrightness) BrightnessAccentColor else MaterialTheme.colorScheme.primary") }
        )
        assertTrue(
            "三处上色必须都用 accent：label 文字 / 轨道底 / 填充条。",
            levelHud.count { it.contains("color = accent") } >= 2 &&
                levelHud.any { it.contains(".background(accent.copy(alpha = 0.24f))") } &&
                levelHud.any { it.contains("text = label,") }
        )
        assertTrue(
            "C1f 回归防护：亮度/音量 HUD 里不得再用 Color.White 当强调位" +
                "（那正是「亮度与音量看起来一样」的原因）。",
            levelHud.none { it.contains("color = Color.White") } &&
                levelHud.none { it.contains(".background(Color.White.copy(") }
        )
        assertTrue(
            "亮度强调色必须是**语义常量**而不是内联字面量（同 FavStarColor 的做法；" +
                "本仓 res/ 下没有 values/，不引入资源色）。",
            gesture.any { it.contains("internal val BrightnessAccentColor = Color(") }
        )
    }

    // ---- 拖动滑块放大：值必须在绘制期读 ----

    /**
     * 上游 `PlayerControlGlassThumb` 的 `pressScale`（拖动 1.18 / 松手 1.0）。
     *
     * 本仓原状是**静止 16dp 圆点** —— 6dp 细轨上一颗这么小的点，手指按住它时
     * 视觉上没有任何"我抓住了"的反馈（连白环都不动）。这是播放页最常用的手势，
     * 每次拖进度都看得见。
     *
     * 正反一对：① 缩放必须经 `graphicsLayer` 读 `thumbScale.value`（绘制期）；
     * ② 不得退回组合期读值 —— `scaleX = thumbScale.value` 直接写在 `Modifier.scale`
     *    或布局上会让进度条在**拖动期间按帧重组**（与上面 R2/U2 同一类缺陷）。
     */
    @Test
    fun seekThumbScalesWhileDragging() {
        val body = blockBody(pane, "private fun PlayerProgressBar(")
        assertTrue(
            "滑块缩放必须由 ThumbDragScale 常量驱动（找不到说明退回静止滑块）",
            body.any { it.contains("ThumbDragScale") }
        )
        assertTrue(
            "缩放必须经 Animatable 驱动（拖动/松手两态各自有目标值）",
            body.any { it.contains("thumbScale.animateTo(") }
        )
        assertTrue(
            "动效=关时必须 snapTo 而不是继续跑动画（无障碍口径）",
            body.any { it.contains("thumbScale.snapTo(") }
        )
        assertTrue(
            "拖动态必须由 dragging 这个状态分流",
            body.any { it.contains("if (dragging) ThumbDragScale else 1f") }
        )
    }

    @Test
    fun thumbScaleIsReadDuringDrawNotComposition() {
        val body = blockBody(pane, "private fun PlayerProgressBar(")
        // ⚠️ 2026-10-17「无模糊语言」方向二 §6-6：滑块由「玻璃 Box + 独立白环 Canvas」
        // **两个节点合并为一个实心白圆 Canvas** ⇒ 缩放的读取载体从 `graphicsLayer` 块
        // 变成 Canvas 的 draw lambda。**两者都是绘制期**，本断言守的语义（不得在组合期读）
        // 一字未变，只是锚点跟着形态改（形态变了同批改守卫并写明理由）。
        val readAt = body.indexOfFirst { it.contains("val s = thumbScale.value") }
        assertTrue(
            "滑块缩放必须在**绘制期**读 `val s = thumbScale.value`（找不到说明退回组合期取值）。",
            readAt >= 0
        )
        // 该读取必须落在某个 `Canvas(` 块体内 —— 否则就无从证明它是绘制期读的。
        val canvasAt = body.indices.lastOrNull { i -> i < readAt && body[i].trimStart().startsWith("Canvas(") }
        assertTrue(
            "滑块缩放的读取必须落在一个 `Canvas(` 块内（Canvas 的 draw lambda 天然是绘制期）。",
            canvasAt != null
        )
        val canvasBody = blockBodyAt(body, canvasAt!!)
        assertTrue(
            "`val s = thumbScale.value` 必须在 Canvas 块内读 —— 在组合期读会让整条进度条" +
                "在拖动期间按帧重组。",
            canvasBody.any { it.contains("val s = thumbScale.value") }
        )
        assertTrue(
            "反向断言：不得出现组合期取值的写法（val scale = thumbScale.value 后再用）",
            body.none { it.contains("val thumbScaleValue = thumbScale.value") }
        )
    }

    /**
     * 滑块现在由**一个** Canvas 绘制实心白圆（2026-10-17「无模糊语言」方向二 §6-6）。
     *
     * ## 为什么换了锚点（形态变了，同批改守卫并写明理由）
     * 旧版是「玻璃 Box（`graphicsLayer` 放大）+ 独立白环 Canvas」**两个**绘制节点，
     * 白环半径按 `thumbPx` **算死**，只放大玻璃就会「玻璃撑出白环之外」—— 旧守卫钉的
     * 正是「两层必须用同一个倍率」（`(thumbPx / 2f - 1.5.dp.toPx()) * s` + `Stroke(… * s)`）。
     *
     * 本批把两个节点**合并成一个**：同心与同倍率不再靠注释里的「约定」，而**由构造保证** ——
     * 失配这一类缺陷在结构上不可能再发生（真机手抓到的拖动态 39×11 扁形即属该类）。
     * ⇒ 守卫改为钉新不变量，且**保留反例**（不许退回旧两节点形态），强度不降：
     * ① 半径必须显式乘同一个绘制期倍率 `s`（不得写死）；
     * ② 反例：旧的「按 thumbPx 算死的白环半径」不得回来；
     * ③ 反例：滑块不得再用 `playerControlGlass`（玻璃底盘节点须保持已删除）。
     */
    @Test
    fun sliderThumbIsASingleScaledDraw() {
        val body = blockBody(pane, "private fun PlayerProgressBar(")
        assertTrue(
            "滑块半径必须显式乘绘制期倍率（`radius = thumbPx / 2f * s`）——" +
                "写死会让放大时滑块与轨道端点脱节。",
            body.any { it.contains("radius = thumbPx / 2f * s") }
        )
        assertTrue(
            "倍率必须在绘制期取（`val s = thumbScale.value`，见上一条断言）。",
            body.any { it.contains("val s = thumbScale.value") }
        )
        assertTrue(
            "反例：不得再出现「按 thumbPx 算死的白环半径」（`thumbPx / 2f - 1.5.dp.toPx()`）——" +
                "那正是「玻璃撑出白环」的旧两节点形态。",
            body.none { it.contains("thumbPx / 2f - 1.5.dp.toPx()") }
        )
        assertTrue(
            "反例：滑块不得再用 `PlayerSliderThumbScrim`（玻璃底盘节点须保持已删除状态）。",
            body.none { it.contains("scrim = PlayerSliderThumbScrim") }
        )
    }

    // ---- 播放控件玻璃的尺寸适配 ----

    /**
     * `Highlight` 的**档位必须跟着控件短边走**（BiliPai 液态玻璃适配）。
     *
     * miuix 三档的唯一差异是 `BloomStroke` 的描边宽度（反编译 `<clinit>` 核实）：
     * Big = **3.5dp**、Middle = 2.8dp、Small = 2.6dp。全局 Dock（64dp 高）用 Big 才对，
     * 而播放控件最矮是 **6dp** 的进度条轨道 —— 3.5dp 的 bloom 描边套上去等于糊满整条
     * 轨道（只剩 2.5dp 可读芯线），这就是"液态档下进度条读不清"的直接来源。
     *
     * 正反一对：① 必须按 `shapeHeightPx` 分档；② 不得退回无条件 `GlassStrokeBigLight`
     *    （原写法），否则 6dp 轨道又会被大描边糊住。
     */
    @Test
    fun glassHighlightSizeAdaptsToControlSize() {
        val body = blockBody(pane, "internal fun Modifier.playerControlGlass(")
        assertTrue(
            "找不到按控件尺寸分档的判据（`shapeHeightPx` 换算成 dp 后比较）",
            body.any { it.contains("highlightShapeHeightDp") }
        )
        assertTrue(
            "必须真的分三档：Small / Middle / Big 各出现一次（只留 Big 等于没适配）",
            body.count { it.contains("Highlight.GlassStroke") } >= 3
        )
        assertTrue(
            "反向断言：`if (isLiquid) Highlight.GlassStrokeBigLight else null` 是**适配前**的" +
                "写法（对所有尺寸一律用 64dp Dock 的大描边），不得回来。",
            body.none { it.contains("if (isLiquid) Highlight.GlassStrokeBigLight else null") }
        )
        assertTrue(
            "档位必须进 remember 的键（否则拖到不同尺寸的控件上不会重建玻璃链）",
            body.any { it.contains("remember(backdrop, shape, scrim, refractionDp, highlight,") }
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
