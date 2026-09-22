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

    /**
     * `animate*AsState`：值在**组合期**读取 → 动画每帧重组宿主 composable。
     *
     * **上限 10 → 8 的变更记录（2026-09-22，T4）**：逐一审查了全部调用点，
     * 砍掉 2 处组合期读值，并把剩下 8 处的去向逐个记在这里。
     *
     * 已修掉：
     * - `IOSControls.IOSSwitch` 的滑块：`animateDpAsState` + `offset(x = )` → 改为
     *   `Animatable<Float>`（px）+ `offset { }`，切换的 spring 过程不再按帧重组。
     *   换写法时唯一有坑的是**初值**：animateXAsState 首次组合直接吸附，
     *   Animatable 会从初值动画过去，所以必须 `Animatable(if (checked) travel else 0f)`，
     *   否则设置页里"默认开"的开关会在首帧集体滑一遍。
     * - `FilledTabRow` 分段滑块：改为 `offset { }` 布局期读取（本项由 T3 完成，
     *   它仍是 animateDpAsState 所以只减少重组、不减计数）。
     *
     * 剩下 8 处的去向（**不要再无脑往下砍**，其中三类是合规的）：
     * 1. `AppNavHost:805` 顶层导航位移 —— 值在 `graphicsLayer { }` 内读，**合规**。
     * 2. `BiliCardSurface:68` 按压缩放 —— 同样在 `graphicsLayer { }` 内读，**合规**。
     *    （这两处被本正则计数，是因为正则只能看到调用点、看不到读取阶段。）
     * 3. `FilledTabRow:164` 分段滑块 —— 已保留 State 引用、在 `offset { }` 内读，**合规**。
     * 4. `FilledTabRow:186` / `DynamicFeed:235` / `VideoDetailSummary:472` /
     *    `VideoDetailSummary:481` —— 都是**颜色**渐变，喂给 `Text(color=)` /
     *    `Icon(tint=)`。这两个参数本身就是组合期入参，没有等价的绘制期写法
     *    （除非换成自定义 draw 修饰符），且重组范围只到那一个叶子 Icon/Text。
     *    为省一次叶子重组而引入 colorFilter 层，代价大于收益。
     * 5. `HomeVideoPage:316` 封面取色 350ms 淡入 —— **这是剩下 8 处里唯一真正的
     *    宿主级组合期读值**（值变化会让整张卡片重组，并连带重算 adaptiveContentColors
     *    与 infoDrawSpec）。刻意**暂不**处理：它只在"图片解码完、取色回来"那一刻触发
     *    一次 350ms 过渡，**不在滚动路径上**，与本轮要压的滚动 jank 无关；
     *    真要修得先把卡片的颜色管线改成"底色由绘制期承担"，那是重构不是调优。
     *    → 见交付报告的遗留项。
     */
    private val maxAnimateAsState = 8

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
