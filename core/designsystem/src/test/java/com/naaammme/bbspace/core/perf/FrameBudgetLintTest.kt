package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 帧预算棘轮（移植自 BiliPai 上游 `core/ui/perf/FrameBudgetLintTest`）。
 *
 * ## 为什么需要它
 *
 * 性能问题最常见的复发形态不是"某处写错了"，而是**修好一处、又在别处新增一处**。
 * 每次都要靠人肉 review 去发现"这里又加了个 `Modifier.composed{}`"是不可靠的——
 * 这类反模式单看一行都很合理，只有统计量才能暴露趋势。
 *
 * 所以这里把已知反模式的**数量冻结成上限**：只许减、不许增。想新增就必须
 * **连同上限一起改**，并在 PR 里写明理由——这个动作被刻意做得显眼。
 *
 * ## 两个被真实事故逼出来的设计（照搬上游，勿简化）
 *
 * 1. **跳过注释行**（[countInSources]）。上游踩过的坑：给某个 `Offscreen` 用法
 *    补一段解释性注释，注释里提到了 `CompositingStrategy.Offscreen` 这个词，
 *    计数就 +1 —— 等于"把为什么写清楚"变成有代价的事。那会直接扼杀注释。
 * 2. **断言真的扫到了源码**（[scannerActuallyReadsSources]）。若模块路径变了、
 *    扫描到 0 个文件，所有棘轮都会"全部通过"——最危险的假绿。
 *
 * ## 纯 JVM
 *
 * 不依赖设备、不依赖 Android framework，`./gradlew test` 即可跑，
 * 因此可以放进任何 CI 而不会被"没有真机"挡住。
 */
class FrameBudgetLintTest {

    /**
     * 源码根目录。单测的 working dir 是模块目录（`:core:designsystem`），
     * 故向上两级到仓库根。找不到时由 [scannerActuallyReadsSources] 报错，
     * 不会静默变成"0 处、全绿"。
     */
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val mainSources: List<File> by lazy {
        File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    // ── 棘轮上限 ───────────────────────────────────────────────────────────
    // 数值 = 制定当天的实际计数（或略高）。**只允许调小，或在确实必要并写明理由时调大。**

    /** `Modifier.composed {}`：每张卡片/每个实例一个，且 lambda 每次重组都重跑。 */
    private val maxModifierComposed = 1

    /**
     * `CompositingStrategy.Offscreen`：强制一次离屏渲染。列表项上使用等于每帧多一层
     * GraphicsLayer 合成。BBSpace 当前为 0，**保持 0** 是明确目标。
     */
    private val maxOffscreenCompositing = 0

    /** `rememberInfiniteTransition`：常驻无限动画，每个都会持续驱动重组/重绘。 */
    private val maxInfiniteTransition = 2

    /**
     * `runBlocking`：阻塞当前线程。主线程上使用会直接掉帧，启动路径上会拖慢冷启。
     *
     * **上限 2 → 3 的变更记录（2026-09-21）**：
     * 新增的是 `VideoDetailActivity.preloadThemeConfig()` —— 详情页是**独立 Activity**，
     * 有自己的一次 `setContent`；若不在此同步预读真实主题，无缝打开动画期间会先渲染
     * `ThemeConfig()` 默认的 MATERIAL 浅色、再翻成用户真实主题（观感就是"打开瞬间白闪"）。
     * 与 `MainActivity` 的两处同源同目的，且都带 300ms 超时兜底、异常时回退默认值，
     * 不会把启动拖住。详见 `MainActivity.preloadThemeConfig` 的注释。
     */
    private val maxRunBlocking = 3

    /** `animate*AsState`：值在**组合期**读取 → 动画每帧重组宿主 composable。 */
    private val maxAnimateAsState = 10

    /** `BlurEffect(`：不比较半径就重建 = 每帧重编着色器。 */
    private val maxBlurEffect = 0

    @Test
    fun scannerActuallyReadsSources() {
        // 防假绿：路径变了 / 扫描逻辑坏了 时必须响亮地失败，而不是"全部通过"
        assertTrue(
            "扫描器没读到任何 main 源码（repoRoot=${repoRoot.absolutePath}）。" +
                "棘轮测试在假绿状态下比不存在更危险——请先修扫描路径。",
            mainSources.size > 100
        )
    }

    @Test
    fun modifierComposed_isFrozen() {
        assertRatchet(
            actual = countInSources("""composed\s*\{""", requireToken = "Modifier.composed"),
            limit = maxModifierComposed,
            what = "Modifier.composed{}",
            why = "它每实例化一次就多一份 lambda 与状态，且 lambda 在每次重组时重跑。" +
                "正确替代是 Modifier.Node（ModifierNodeElement + LayoutModifierNode/DrawModifierNode）。"
        )
    }

    @Test
    fun offscreenCompositing_isFrozen() {
        assertRatchet(
            actual = countInSources("""CompositingStrategy\.Offscreen"""),
            limit = maxOffscreenCompositing,
            what = "CompositingStrategy.Offscreen",
            why = "强制离屏渲染，列表项上等于每帧多一层 GraphicsLayer 合成。" +
                "若确实需要（如遮罩/混合），请在本测试里写明为什么该处无法避免。"
        )
    }

    @Test
    fun infiniteTransition_isFrozen() {
        assertRatchet(
            actual = countInSources("""rememberInfiniteTransition"""),
            limit = maxInfiniteTransition,
            what = "rememberInfiniteTransition",
            why = "常驻无限动画会持续驱动帧。多个同构项（如整屏骨架块）应**共用一个时钟**，" +
                "把脉冲值下发而不是各自起一个 infiniteTransition。"
        )
    }

    @Test
    fun runBlocking_isFrozen() {
        assertRatchet(
            actual = countInSources("""\brunBlocking\b"""),
            limit = maxRunBlocking,
            what = "runBlocking",
            why = "阻塞当前线程。主线程上会直接掉帧；启动路径上会拖慢冷启。" +
                "冷启同步预读这类场景应带超时（withTimeoutOrNull）并限制在极短预算内。"
        )
    }

    @Test
    fun animateAsState_isFrozen() {
        assertRatchet(
            actual = countInSources("""animate[A-Za-z]*AsState\("""),
            limit = maxAnimateAsState,
            what = "animate*AsState",
            why = "它的值在**组合期**读取，动画每帧都会重组宿主 composable。" +
                "正确做法是 Animatable + 在 graphicsLayer{} / drawBehind{} 块内读 value，" +
                "让每帧只更新图层属性或重绘，不触发重组。"
        )
    }

    @Test
    fun blurEffect_isFrozen() {
        assertRatchet(
            actual = countInSources("""BlurEffect\("""),
            limit = maxBlurEffect,
            what = "BlurEffect(",
            why = "在 graphicsLayer 里直接构造 BlurEffect 而不比较半径，等于每帧新建 RenderEffect，" +
                "底层要重新编译着色器。正确做法是先比较半径（lastBlurRadiusPx 之类）再赋值。"
        )
    }

    // ── 扫描器 ─────────────────────────────────────────────────────────────

    /**
     * 统计 main 源码里匹配 [regex] 的**代码行**数。
     *
     * - **跳过注释行**（见类注释第 1 条）：解释性注释不该让计数上升；
     * - **跳过 import 行**：`import kotlinx.coroutines.runBlocking` 这种是**声明**不是
     *   使用点。不跳过会按模块数虚增计数（本项目里 `runBlocking` 就因此从 3 变 4），
     *   让棘轮在"新增一处 import"时误报——与它想抓的"新增一处阻塞调用"完全无关。
     * - [requireToken] 可选：额外的字面量校验，避免正则过宽导致误计。
     */
    private fun countInSources(regex: String, requireToken: String? = null): Int {
        val re = Regex(regex)
        return mainSources.sumOf { file ->
            file.readLines().count { line ->
                val trimmed = line.trimStart()
                val isComment = trimmed.startsWith("//") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("/*")
                if (isComment) return@count false
                if (trimmed.startsWith("import ")) return@count false
                if (!re.containsMatchIn(line)) return@count false
                requireToken == null || line.contains(requireToken)
            }
        }
    }

    private fun assertRatchet(actual: Int, limit: Int, what: String, why: String) {
        assertTrue(
            "「$what」当前 $actual 处，超过冻结上限 $limit 处。\n" +
                "原因：$why\n" +
                "如果这次改动确实必须新增，请**连同上限一起调大**并在 PR 里写明理由 —— " +
                "这个动作是刻意做得显眼的。若只是解释性注释触发了计数，说明扫描器没跳过注释行。",
            actual <= limit
        )
    }
}
