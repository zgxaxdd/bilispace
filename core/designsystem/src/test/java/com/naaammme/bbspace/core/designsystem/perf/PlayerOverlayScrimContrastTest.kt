package com.naaammme.bbspace.core.designsystem.perf

import com.naaammme.bbspace.core.designsystem.theme.ContrastMath
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页**浮层可读性**判据（2026-10-16 起源；2026-10-18 第 33 轮**整篇改判据模型**）。
 *
 * ## 用户口径（本轮直接要求，原文）
 * 「视频播放页还是有一层遮罩请全部去除，且视频播放页的其他控件风格没有统一」。
 *
 * 上一轮（第十九轮）把遮罩**减浅**（渐变 0.88 → 0.42）——用户明确否掉了那条路线：
 * 他要的是**整层完全没有**。本轮据此删掉最后三条 `Brush.verticalGradient`：
 *
 * | # | 符号 | 原形态 | 位置 |
 * |---|---|---|---|
 * | 1 | `PlayerTopScrimBrush` | 黑 0.66 → 透明 | 顶栏 Row |
 * | 2 | `PlayerCtrlBarScrimBrush` | 四档 0.26 → 0.42 平台 | 控制层内层 Column |
 * | 3 | `SwipeBottomScrimBrush` | 透明 → 黑 0.58 | 会话底部信息层 |
 *
 * ## 判据模型为什么要整篇换（不是放宽）
 * 旧判据的**全部**断言都建立在一个几何前提上：文字/轨道坐在某条垂直渐变的某个
 * fraction 处，按停靠点插值求 α。渐变被删之后，fraction 这个概念在播放页**不存在**了 ——
 * 继续保留那套采样只会得到「测一个不存在的对象、然后恒绿」的假门禁（W26 同款）。
 *
 * 新模型把可读性拆成**三个可独立验证的来源**，各自从源码解析：
 *
 * 1. **控件（可点件）**：每颗控件自带一块**同尺寸**底盘 `PlayerChipScrim`（黑 0.58）。
 *    底与图标**同尺寸**⇒ 图标处的有效 α 恒等于该底的 α，与层高/停靠/画幅无关。
 *    ⇒ 判据：`whiteTextOnBlackScrimOverWhiteFrame(α) >= 4.5`（AA 正文）。
 * 2. **信息文字（不可点）**：标题/UP 名/话题/时间文本**没有底盘**，靠
 *    `PlayerInfoTextShadow`（黑 0.55 / blur 6）投影。投影不是均匀遮罩，**不能**假装
 *    它等于某个 α 的实底（那是自欺）——所以判据只做**结构断言**：
 *    α 必须真的挂在 `TextStyle.shadow` 上、必须真的接进每一个信息 Text（不许定义不用）。
 * 3. **进度轨道**：靠 `PlayerTrackBedScrim`（黑 0.52，紧贴轨道 6dp 的暗槽），
 *    判据按非文本 3:1（WCAG 1.4.11）。
 *
 * ⇒ 这三条**合起来比旧判据更严**：旧模型里「渐变达标 ⇒ 全绿」；新模型要求
 * ①没有任何整层渐变（反向断言）；②每个控件底盘自己达标；③投影真的接进了文字。
 *
 * ## 防假绿（照抄本仓 W24/W26 体例，勿简化）
 * 所有 α / dp **从源码解析**，解析失败**显式失败**；关键断言先跑「示例能不能抓到」的自检。
 * 硬编码一份副本的判据在代码改动后会静默失真 —— 那比没有门禁更糟。
 */
class PlayerOverlayScrimContrastTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val panePath = "feature/video/src/main/java/com/naaammme/bbspace" +
        "/feature/video/player/VideoPlayerPane.kt"

    private val pagerPath = "feature/video/src/main/java/com/naaammme/bbspace" +
        "/feature/video/player/PortraitSwipePager.kt"

    private fun readLines(rel: String): List<String> {
        val f = File(repoRoot, rel)
        assertTrue(
            "找不到 $rel（repoRoot=${repoRoot.absolutePath}）—— 此时全部断言都无意义。",
            f.isFile
        )
        return f.readLines()
    }

    private val pane: List<String> by lazy { readLines(panePath) }
    private val pager: List<String> by lazy { readLines(pagerPath) }

    // ── 解析工具 ─────────────────────────────────────────────────────────────

    /**
     * `private val X = Color.Black.copy(alpha = 0.58f)` → `X to 0.58`。
     *
     * 黑、白两种都认：播放页两种前景/背景色都在用。
     * ⚠️ 交替组写成**非捕获** `(?:Black|White)`，否则 `groupValues[1]` 会被颜色名抢走。
     */
    private fun colorAlphaIn(lines: List<String>, name: String): Double? {
        val re = Regex(
            """val\s+$name\s*=\s*Color\.(?:Black|White)\.copy\(\s*alpha\s*=\s*([0-9.]+)f\s*\)"""
        )
        for (line in lines) {
            val m = re.find(line) ?: continue
            return m.groupValues[1].toDoubleOrNull()
        }
        return null
    }

    private fun colorAlphaOf(name: String): Double? = colorAlphaIn(pane, name)

    /** 去掉注释与 import 行后的**代码行**（反向断言只看代码，否则注释里的旧名字会误判）。 */
    private fun codeLines(lines: List<String>): List<String> = lines.filter { line ->
        val t = line.trimStart()
        !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import "))
    }

    private fun countCode(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return codeLines(lines).count { re.containsMatchIn(it) }
    }

    // ── 断言 A：反向 —— 三条整层渐变**符号不存在** ──────────────────────────

    /**
     * 反向断言：三条整层遮罩画笔在源码里**一个都不许存在**（连换名保留也不行）。
     *
     * ## 为什么用「符号名 + 渐变工厂」双判据
     * - 只判符号名：有人把 `Brush.verticalGradient(...)` 直接内联进 `background(...)`，
     *   符号名消失但遮罩回来了 ⇒ 漏。
     * - 只判 `Brush.verticalGradient`：加载层（`SwipeWaitVeil`）的深色底**合法**使用它
     *   （它不是浮层遮罩，是没有画面时铺满屏幕的等待底，只在没有视频帧时可见），
     *   一刀切会把合法用法打红 ⇒ 误报。
     *
     * ⇒ 两条一起：① 三个符号名必须在**代码行**里零命中；
     * ② 播放页源码里的 `Brush.verticalGradient` 必须**逐条登记**（白名单 + 理由）。
     * 新增任何一条都会让本条失败（不允许新加一处渐变然后悄悄放行）。
     *
     * ⚠️ 第 ② 条刻意**不**按数量配额写：一个数字既不能说明这一处为什么合法，
     * 又会随无关改动漂移；改成逐条具名白名单后，新增必然要改这里并写理由。
     */
    @Test
    fun wholeLayerScrimBrushesAreGone() {
        val forbidden = listOf(
            "PlayerTopScrimBrush",
            "PlayerCtrlBarScrimBrush",
            "SwipeBottomScrimBrush",
            // 第十九轮之前的常量名也一并封死（防止恢复旧方案时只把值调浅）
            "PlayerOverlayScrimTopEnd",
            "PlayerOverlayScrimBottomEnd",
            "PlayerOverlayScrimMid",
            "PlayerOverlayScrimUpperMid"
        )
        val hits = forbidden.flatMap { sym ->
            val n = countCode(pane, """\b$sym\b""") + countCode(pager, """\b$sym\b""")
            if (n > 0) listOf("$sym×$n") else emptyList()
        }
        assertTrue(
            "这些**整层遮罩**符号仍在源码里（${hits.joinToString()}）—— 用户口径是「视频播放页" +
                "还是有一层遮罩请全部去除」。删除要**连符号带调用点**一起删，" +
                "不许改名后保留，也不许只把 α 调小。\n" +
                "可读性的正确承担者：控件 → `PlayerChipScrim`（同尺寸底盘）；" +
                "信息文字 → `PlayerInfoTextShadow`（投影）；轨道 → `PlayerTrackBedScrim`。",
            hits.isEmpty()
        )

        // 渐变工厂的逐条白名单（键 = 源码里出现它的那一行的特征片段，值 = 为什么合法）
        val allowedGradients = mapOf(
            "WaitVeilSolidBrush" to "等首帧加载层的**不透明**底（没有画面时铺满屏幕），不是浮层遮罩",
            "WaitVeilCoverScrimBrush" to "有封面铺底时的加载层轻压暗，只在视频帧到达前可见"
        )
        val gradientSites = codeLines(pane)
            .filter { it.contains("Brush.verticalGradient(") }
            .map { line ->
                allowedGradients.keys.firstOrNull { line.contains(it) }
                    ?: "未登记：${line.trim()}"
            }
        assertTrue(
            "播放页源码里的 `Brush.verticalGradient(` 出现 ${gradientSites.size} 处，" +
                "其中未登记的有：${gradientSites.filter { it.startsWith("未登记") }.joinToString()}。\n" +
                "允许存在的只有等首帧加载层那两把画笔（见 `allowedGradients` 的逐条理由）——" +
                "它们是「没有画面时」的底，不是「压在画面上的遮罩」。\n" +
                "**新增任何一处渐变都必须先在这里登记并写明它为什么不是遮罩**，否则本条红。",
            gradientSites.all { !it.startsWith("未登记") }
        )
        assertTrue(
            "自检失败：白名单连示例都抓不到 —— 那上面那条断言就是永真。",
            gradientSites.isNotEmpty()
        )

        // 会话底部信息层：那条 38% 屏高的渐变 Box 也必须整块删除（不只是删画笔）
        assertTrue(
            "`PortraitSwipePager` 里仍在用 `matchParentSize().background(...)` 给底部信息层铺底 —— " +
                "这正是用户看到的那层遮罩。内层 Box 应当整块删除，外层 Box 只留定位与防烧屏。",
            countCode(pager, """matchParentSize\(\)\s*\.background\(""") == 0
        )
    }

    // ── 断言 B：正向 —— 控件底盘达标且**全页共用同一个常量** ─────────────────

    /** 控件底盘 `PlayerChipScrim` 的 α **从源码解析**，解析失败即红。 */
    private val chipAlpha: Double by lazy {
        colorAlphaOf("PlayerChipScrim")
            ?: throw AssertionError(
                "解析不出 `PlayerChipScrim = Color.Black.copy(alpha = …)` —— " +
                    "整层遮罩删除后，**每个控件**的可读性完全依赖这块同尺寸底盘。" +
                    "改名/改形态都必须同批改本判据，不能静默跳过。"
            )
    }

    /**
     * **正向主断言**：白图标坐在 `PlayerChipScrim` 上，在最坏情况纯白画面里达到 AA 4.5:1。
     *
     * 因为是「底与图标同尺寸」，图标处的有效 α **恒等于** chip 的 α（不需要按几何插值，
     * 也不随画幅/层高变化）——这是本轮换判据模型后**比旧版更稳**的地方：
     * 旧版的 α 会随 118/126dp 两种层高和停靠点漂移，新版对任意布局恒等。
     */
    @Test
    fun chipScrimKeepsWhiteIconsAtWcagAa() {
        val ratio = ContrastMath.whiteTextOnBlackScrimOverWhiteFrame(chipAlpha)
        assertTrue(
            "控件底盘 `PlayerChipScrim` 的 α = ${"%.3f".format(chipAlpha)} 只给出 " +
                "${"%.2f".format(ratio)}:1（纯白画面最坏情况），低于 WCAG AA 正文 4.5:1。\n" +
                "反解线：白 1.00 达 4.5:1 需要 α ≥ " +
                "${"%.4f".format(ContrastMath.minScrimAlphaForWhiteText(4.5))}。\n" +
                "修法：加深 chip（例如 0.58 → 0.62），**不要**只调这里的阈值 —— " +
                "阈值是用户能感知的可读性下限，不是测试的松紧旋钮。",
            ratio >= 4.5
        )
        // 校准：判据里那两个数（4.5 门槛与反解线）必须自洽
        val minAlpha = ContrastMath.minScrimAlphaForWhiteText(4.5)
        assertTrue(
            "判据阈值与实测口径脱节：反解出的最小 α=" +
                "${"%.4f".format(minAlpha)} 按标准算法只有 " +
                "${"%.2f".format(ContrastMath.whiteTextOnBlackScrimOverWhiteFrame(minAlpha))}:1。",
            ContrastMath.whiteTextOnBlackScrimOverWhiteFrame(minAlpha) >= 4.5
        )
        // 反向：底盘 α 不许大到又把画面压回整层遮罩的观感（0.72 是本仓 toast 级别的量）
        assertTrue(
            "`PlayerChipScrim` 的 α = ${"%.3f".format(chipAlpha)} 已经超过 0.72 —— " +
                "34dp 的圆钮用这么实的黑会读作「一排黑片」，与「全部去除遮罩」的观感相反。" +
                "若确实需要更实的底，请先确认用户口径是否变了。",
            chipAlpha < 0.72
        )
    }

    /** 共用的按钮直径（`PlayerCtrlRowHeight`）—— 从源码解析，解析失败即红。 */
    private val rowHeightDp: Double by lazy {
        val re = Regex("""private\s+val\s+PlayerCtrlRowHeight\s*=\s*([0-9.]+)\.dp""")
        for (line in pane) {
            val m = re.find(line) ?: continue
            return@lazy m.groupValues[1].toDouble()
        }
        throw AssertionError(
            "解析不出 `private val PlayerCtrlRowHeight = N.dp` —— " +
                "它是「所有控件同尺寸」的单一来源（用户第 33 轮的核心要求）。"
        )
    }

    /**
     * **风格统一**的结构断言（用户同一句话的后半句）。
     *
     * 三类证据：
     * 1. `PlayerChipScrim` 只**定义一次**（顶栏与底部控制行共用同一个常量）；
     * 2. 圆钮直径**只有一个**来源（`PlayerIconButton` 用 `Modifier.size(PlayerCtrlRowHeight)`）；
     * 3. 顶栏不再出现文字胶囊/裸 `IconButton`、控件行不再出现第二/第三种 chip 常量。
     */
    @Test
    fun allControlsShareOneChipAndOneDiameter() {
        val chipDefinitions = countCode(pane, """private\s+val\s+PlayerChipScrim\s*=""")
        assertTrue(
            "`PlayerChipScrim` 定义了 $chipDefinitions 次（要求恰好 1 次）—— " +
                "顶栏与底部控制行必须共用**同一个**常量，否则迟早漂移成两种底色，" +
                "正是用户说的「控件风格没有统一」。",
            chipDefinitions == 1
        )
        assertTrue(
            "圆钮直径必须唯一来自 `PlayerCtrlRowHeight`（${"%.0f".format(rowHeightDp)}dp）：" +
                "`PlayerIconButton` 里找不到 `Modifier.size(PlayerCtrlRowHeight)` —— " +
                "有 chip 的那一档必须收成同尺寸的圆，这是「同尺寸」能被机器验证的唯一形态。",
            countCode(pane, """Modifier\.size\(PlayerCtrlRowHeight\)""") == 1
        )
        // 反向：旧的第三/第二种 chip 常量与文字胶囊都不许回来
        val legacy = listOf(
            "PlayerCtrlPrimaryChip",
            "PlayerCtrlGroupChip",
            "PlayerPillChip",
            "PlayerPillScrim",
            "PlayerPillShape",
            "PlayerPillTextDim",
            "CtrlBtn"
        ).filter { countCode(pane, """\b$it\b""") > 0 }
        assertTrue(
            "播放页里仍有旧控件形态的符号：${legacy.joinToString()}。\n" +
                "第 33 轮把顶栏文字胶囊（`PlayerPillChip`）与底部两种白底圆钮" +
                "（`PlayerCtrlPrimaryChip` / `PlayerCtrlGroupChip`）全部收敛到 `PlayerChipScrim`，" +
                "`CtrlBtn`（文字连体段）也一并无调用点删除。它们回来 = 风格又开始分叉。",
            legacy.isEmpty()
        )
        // 反向：顶栏不许再有裸 IconButton（Material 默认 48dp、无底盘，与圆钮读作两套）
        assertTrue(
            "播放页里又出现了 Material 的裸 `IconButton(` —— 顶栏/控制行的控件必须走 " +
                "`PlayerIconButton`（同尺寸 + 同 chip + 同按压反馈），否则又回到两套语言。" +
                "（互动栏的圆盘用 Box + combinedClickable，不是 IconButton。）",
            countCode(pane, """(^|[^A-Za-z])IconButton\(""") == 0
        )
    }

    // ── 断言 C：信息文字投影真的接进了文字 ──────────────────────────────────

    /** `PlayerInfoTextShadow` 的 α（从源码解析）。 */
    private val infoShadowAlpha: Double by lazy {
        val re = Regex(
            """private\s+val\s+PlayerInfoTextShadow\s*=\s*Shadow\(color\s*=\s*""" +
                """Color\.Black\.copy\(\s*alpha\s*=\s*([0-9.]+)f\s*\)"""
        )
        for (line in pane) {
            val m = re.find(line) ?: continue
            return@lazy m.groupValues[1].toDouble()
        }
        throw AssertionError(
            "解析不出 `PlayerInfoTextShadow = Shadow(color = Color.Black.copy(alpha = …))` —— " +
                "整层遮罩删除后，标题/UP 名/话题/时间文本的**唯一**可读性来源就是这条投影。"
        )
    }

    /**
     * 投影**必须接进每一个信息 Text**（不许定义了不用）。
     *
     * 判据形态刻意选「调用点数 ≥ 7」，而不是某个具体数字：
     * 信息层有 作者行 / 联合创作 chip / 标题 / 话题 / 合集 五处，加上顶栏两条 meta 与
     * 时间文本 —— 少接一处就是少一处可读性。数字下调时本条会红。
     */
    @Test
    fun infoTextShadowIsWiredIntoInfoTexts() {
        val sites = countCode(pane, """playerInfoStyle\(""")
        assertTrue(
            "`playerInfoStyle(...)` 只有 $sites 处调用（要求 ≥ 7）：\n" +
                "顶栏标题 + 顶栏 meta、作者行 @名 + 联合创作 chip + 标题 + 话题 + 合集、" +
                "控制条时间文本 —— 这些都是**没有底盘**的信息文字，" +
                "删掉任何一处的投影就等于那一行在亮画面上直接糊掉。\n" +
                "（若某行确实移出了信息区，请同批更新本断言并说明新的可读性来源。）",
            sites >= 7
        )
        assertTrue(
            "投影 α=${"%.2f".format(infoShadowAlpha)} 必须是个**能看见**的值（> 0.4）：" +
                "太淡的投影在纯白画面上等于没有 —— 这是整层遮罩删除后唯一的文字可读性兜底。",
            infoShadowAlpha > 0.4
        )
        assertTrue(
            "投影必须真的挂到 `TextStyle.shadow` 上（`base.copy(shadow = …)`）—— " +
                "找不到说明有人把 `PlayerInfoTextShadow` 换成了别的参数（比如 color），" +
                "那样它就不再是投影了。",
            countCode(pane, """copy\(shadow = PlayerInfoTextShadow\)""") == 1
        )
        // 反向：信息层不许再用「文字背后铺一块底」的老办法（那就又是遮罩）
        val infoLineBody = buildString {
            val start = pane.indexOfFirst { it.contains("internal fun SwipeSessionInfoLine(") }
            assertTrue(
                "找不到 `SwipeSessionInfoLine(` —— 信息层实现变了，本判据要同步更新。",
                start >= 0
            )
            var depth = 0
            var opened = false
            for (i in start until pane.size) {
                appendLine(pane[i])
                depth += pane[i].count { it == '{' } - pane[i].count { it == '}' }
                if (pane[i].contains('{')) opened = true
                if (opened && depth <= 0) break
            }
        }
        assertTrue(
            "信息层（`SwipeSessionInfoLine`）体内出现 `background(` —— 文字背板会让用户" +
                "再次看到一层遮罩。该类信息只允许投影，不允许底。",
            countCode(infoLineBody.lines(), """\.background\(""") == 0
        )
    }

    // ── 断言 D：进度轨道（非文本 3:1）────────────────────────────────────────

    /** 未播轨的白色 α（从源码解析）。 */
    private val trackAlpha: Double by lazy {
        colorAlphaOf("PlayerSliderInactiveTrack")
            ?: throw AssertionError(
                "解析不出 `PlayerSliderInactiveTrack = Color.White.copy(alpha = …)` —— " +
                    "未播轨常量改名/改形态了？判据要同批更新，不能静默跳过。"
            )
    }

    /** 轨道衬底的 α（从源码解析）。 */
    private val trackBedAlpha: Double by lazy {
        colorAlphaOf("PlayerTrackBedScrim")
            ?: throw AssertionError(
                "解析不出 `PlayerTrackBedScrim = Color.Black.copy(alpha = …)` —— " +
                    "整层遮罩删除后，它是轨道 3:1 的**唯一**来源。删它之前先算一遍对比度。"
            )
    }

    /**
     * **未播轨道必须过非文本 3:1**（WCAG 1.4.11）。
     *
     * ## 与旧判据的关系（同一个门槛，换了底的来源）
     * 旧版：轨道坐在底部渐变的爬坡上，α 按 fraction 插值；
     * 新版：**没有渐变**，轨道坐在自己的 `PlayerTrackBedScrim` 暗槽上，
     * 有效 α 恒等于衬底 α（槽与轨道同宽、上下各 2dp，几何上完全覆盖）。
     *
     * ⚠️ 白 0.72 轨道 + 黑 0.52 衬底 = **3.05:1**（余量 1.7%）。余量薄是**刻意的**：
     * 衬底每加深一档，那条 6dp 的暗槽在画面上就更显眼一分 —— 与「全部去除遮罩」的
     * 用户口径直接冲突。所以这里钉死「刚好过线」，任何一头松动都会红，逼改动者重新算。
     */
    @Test
    fun progressTrackClearsTheNonTextFloorOnItsOwnBed() {
        val ratio = ContrastMath.whiteTextOnBlackScrimOverWhiteFrame(
            trackBedAlpha,
            textAlpha = trackAlpha
        )
        assertTrue(
            "未播轨（白 ${"%.2f".format(trackAlpha)}）坐在 `PlayerTrackBedScrim`" +
                "（黑 ${"%.2f".format(trackBedAlpha)}）上只有 ${"%.2f".format(ratio)}:1，" +
                "低于非文本 3:1。\n" +
                "整层遮罩已删除 ⇒ 这条 6dp 暗槽是轨道的**唯一**底；" +
                "修法是加深衬底（`PlayerTrackBedScrim`）或提亮未播轨，" +
                "**不要**把渐变加回去（那违反本轮用户口径），也不要放宽阈值。",
            ratio >= 3.0
        )
        // 自检：textAlpha 必须真的进了公式（否则这条是拿纯白算的，与真实轨道无关）
        val opaque = ContrastMath.whiteTextOnBlackScrimOverWhiteFrame(
            trackBedAlpha,
            textAlpha = 1.0
        )
        assertTrue(
            "自检：`textAlpha` 没被传进公式 —— 白 ${"%.2f".format(trackAlpha)} 与纯白算出了同一个比值。",
            opaque - ratio > 0.01
        )
        // 轨道衬底必须真的画出来（画了才可能有对比度）
        assertTrue(
            "`PlayerTrackBedScrim` 必须出现在绘制里（`color = PlayerTrackBedScrim`）—— " +
                "只定义不绘制等于轨道没有底。",
            countCode(pane, """color = PlayerTrackBedScrim""") == 1
        )
    }

    // ── 断言 E：公式口径自检（性质断言，与几何无关）──────────────────────────

    /**
     * 对比度公式的**顺序**自检：sRGB 域合成 → 线性化 → 比值。
     *
     * 漏掉「合成后再线性化」这一步会把每个比值算成约一半（2026-10-16 那次争议的根因）。
     * 本条与几何无关，只保证判据用的算术是对的 —— 否则上面所有门槛都是假数。
     */
    @Test
    fun contrastFormulaKeepsTheSrgbComposeStep() {
        val alpha = 0.55
        val standard = ContrastMath.whiteTextOnBlackScrimOverWhiteFrame(alpha)
        // 漏步法：把 (1 - alpha) 直接当线性亮度（当年的错法）
        val naive = (1.0 + 0.05) / ((1.0 - alpha) + 0.05)

        assertTrue(
            "标准算法（sRGB 合成 → 线性化 → 比值）与漏步法的差异只有 " +
                "${"%.2f".format(standard / naive)} 倍，低于预期的约 2 倍 —— " +
                "说明有人把合成/线性化其中的一步省掉了，本判据将失去分辨力。" +
                "（标准 ${"%.2f".format(standard)}:1 vs 漏步 ${"%.2f".format(naive)}:1）",
            standard / naive >= 2.0
        )
    }

    /**
     * **性质断言**：`textAlpha` 必须真的参与公式（防常量没接进判据）。
     *
     * 只要公式正确，白 1.00 与白 0.92 必然算出不同的比值（实测差约 1.46:1）。
     * 如果两次调用得到同一个数，说明 `textAlpha` 参数被忽略了 —— 那
     * [progressTrackClearsTheNonTextFloorOnItsOwnBed] 就是在拿纯白算一个与真实轨道
     * 无关的数。与几何/配色都无关，所以以后再改遮罩方案也不会恒假。
     */
    @Test
    fun textAlphaParticipatesInTheFormula() {
        val opaque = ContrastMath.whiteTextOnBlackScrimOverWhiteFrame(chipAlpha, textAlpha = 1.0)
        val dimmed = ContrastMath.whiteTextOnBlackScrimOverWhiteFrame(chipAlpha, textAlpha = 0.92)
        assertTrue(
            "自检：`textAlpha` 没被传进公式 —— 白 1.00 与白 0.92 算出了同一个比值" +
                "（${"%.2f".format(opaque)} vs ${"%.2f".format(dimmed)}）。",
            opaque - dimmed > 0.3
        )
        assertTrue(
            "chip 上的白 0.92 常态文字也必须过 AA 4.5:1（实际 " +
                "${"%.2f".format(dimmed)}:1）—— 控件里如果有 0.92 的白字，它和图标是同一块底。",
            dimmed >= 4.5
        )
    }

    // ── 断言 F：解析自检（防读不到就算通过）────────────────────────────────

    /** 所有从源码解析出来的常量都必须落在合理区间 —— 抓解析到别的行。 */
    @Test
    fun parserReadsSaneValues() {
        assertTrue(
            "解析出的 α 明显不合理：chip=${"%.3f".format(chipAlpha)}" +
                " trackBed=${"%.3f".format(trackBedAlpha)}" +
                " track=${"%.3f".format(trackAlpha)}" +
                " infoShadow=${"%.3f".format(infoShadowAlpha)} —— 都应落在 (0,1)。",
            listOf(chipAlpha, trackBedAlpha, trackAlpha, infoShadowAlpha)
                .all { it > 0.0 && it < 1.0 }
        )
        assertTrue(
            "解析出的 `PlayerCtrlRowHeight` = ${"%.1f".format(rowHeightDp)}dp，明显不合理" +
                "（本轮控件统一为 34dp 的圆钮；落在 24~48dp 之外说明锚点抓错行了）。",
            rowHeightDp in 24.0..48.0
        )
        // 白名单自检：那两把加载层渐变必须真的还在（否则白名单是空转）
        assertTrue(
            "等首帧加载层的两把画笔不见了 —— 若它们确实被重写成非渐变形态，" +
                "请同批更新 `wholeLayerScrimBrushesAreGone` 的白名单与自检。",
            codeLines(pane).any { it.contains("WaitVeilSolidBrush") } &&
                codeLines(pane).any { it.contains("WaitVeilCoverScrimBrush") }
        )
    }
}
