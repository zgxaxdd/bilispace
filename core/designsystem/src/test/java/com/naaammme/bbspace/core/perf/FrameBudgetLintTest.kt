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

    /**
     * `runBlocking`：**总量 ≤ 2**（第二道闸，与 Lint 的"形态检查"并存）。
     *
     * ## 为什么数量上限仍留在测试里，而不是 Lint（2026-10-18 债务偿还）
     * A2 把原 `runBlocking_isFrozen`（数文本，上限 2）迁成了 `BbspaceUnboundedRunBlocking`
     * 检测器（判"必须带 withTimeoutOrNull/withTimeout/runCatching"）。审计确认：**形态
     * 检查无法表达数量**——第 3 处**带超时**的 runBlocking 在迁移后会被放行，这是净放宽。
     *
     * 为什么不给检测器加计数（审计给出的 A 方案）：
     * 1. Lint 检测器是**逐文件**扫描（`UastScanner`），跨文件累计总数需要 Project 级
     *    service / after-analysis 钩子，属改检测器架构；
     * 2. 就算做了，"存量豁免"只能落在 `<lint.xml>` 的 `<ignore path=...>`（**文件粒度**），
     *    无法表达"mainSources 里总数恰好 2"；把两个调用点所在文件各自豁免，等于允许
     *    同文件里再添一处 —— 与它想守的"总数"不是一回事。
     *
     * ⇒ 选择第二道闸：本测试用**仓库级** `mainSources` 直接数 `runBlocking` 代码行，
     * 与 Lint 的形态检查**互补**（一个管"几处"、一个管"每处有没有界"）。两者都红才放行
     * 新调用点，任一失守都会立刻红。
     *
     * ⚠️ 这是一条**新增的**守卫，不是恢复旧断言的放宽版：旧断言在 A2 里被删除，
     * 本条只保留"只减不增"的语义（`<= 2`），不改任何现有断言的值。
     */
    private val maxRunBlocking = 2

    /**
     * `rememberInfiniteTransition`：常驻无限动画，每个都会持续驱动重组/重绘。
     *
     * **上限 2 → 1 的变更记录（2026-10-02，AP-01）**：全仓 main 源码实测**仅 1 处**
     * （`Skeleton.kt:95`，骨架呼吸的唯一时钟）。原上限 2 是没有按实测口径读数定下的
     * （W24）。运行期"实例数 vs 调用点数"的缺口（1 个调用点被 N 个 item 各调一次）
     * 由 `SkeletonShimmerStructureTest` 的容器级 provider 守卫管辖，本棘轮只管调用点。
     *
     * **上限 1 → 2 的变更记录（2026-10-05）**：新增的 1 处来自**移植件**
     * `motion/DissolvableSurface.kt` 的邻卡抖动（`Modifier.jiggleOnDissolve`：
     * 别的卡片正在消散时，周围卡片 ±1.5°/80ms 抖动）。这是 BiliPai 粒子消散效果的
     * 一部分（用户要求 1:1 移植），且**只在"有别的卡片正在消散"时才挂** ——
     * 调用方不传该修饰符就完全不创建动画对象（`shouldJiggleOnDissolve` 早退）。
     * 它的实例数受"同时只有一张卡在消散"约束，不会随列表长度增长。
     */
    private val maxInfiniteTransition = 2

    /**
     * `runBlocking`：阻塞当前线程。主线程上使用会直接掉帧，启动路径上会拖慢冷启。
     *
     * **上限 2 的实测口径（2026-10-02，AP-01(d) 修正）**：全仓 main 源码恰好 2 处 ——
     * `MainActivity.kt:293` 与 `VideoDetailActivity.kt:217`，同为 `preloadThemeConfig`
     * 的同步主题预读（详情页是独立 Activity，有自己的 `setContent`；若不在此同步预读
     * 真实主题，无缝打开动画期间会先渲染默认的 MATERIAL 浅色、再翻成用户真实主题，
     * 观感就是"打开瞬间白闪"）。两处都带超时兜底、异常时回退默认值。
     *
     * **勘误**：本注释此前写"与 MainActivity 的两处同源同目的"，与代码不符
     * （MainActivity 实为 1 处）—— 交接数字未复核就以讹传讹（W4），本次按本仓扫描器
     * 实测口径（W24）改正，上限 3 → 2 收紧到真值。
     *
     * **A2 迁移（2026-10-13 第 13 轮）：本字段与对应断言已删除**，改由 Lint 检测器
     * `BbspaceUnboundedRunBlocking` 执行。上面的实测口径与勘误**原样保留** ——
     * 它是这 2 处的来历（为什么允许存在、为什么必须带超时），删了就只剩一个数字。
     */

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
     * 6. **8 → 9（2026-09-29，排行卡同步推荐 UI）**：+1 为
     *    `rememberVideoCardCoverTint`（core/designsystem/color/VideoCardCoverTint.kt）
     *    —— 推荐页封面取色淡入的**同配方抽包复用**（排行卡要与推荐卡同观感，
     *    取色宿主从 FeedCard 抽到 designsystem 供两页共用）。第 5 条的全部理由
     *    原样适用：只在取色回来那一刻触发一次 350ms 过渡，不在滚动路径上。
     *
     * **AP-33 复核（2026-10-02）：9 保持不变，书面理由** —— FL-07（把卡片取色淡入
     * 移出组合期）本轮**未执行**（史实：那是重构而非调优，历史坑在
     * `HomeVideoPage` 的取色只挂 Coil onSuccess 上；在无设备条件下改它风险大于收益）。
     * 实测仍为 9 处，故上限维持；FL-07 若单独立项再下调。
     *
     * **上限 9 → 10 的变更记录（2026-10-05）**：新增的 1 处来自**移植件**
     * `motion/DissolvableSurface.kt` 的卡片高度塌缩（`animateFloatAsState`：
     * 消散完成后把卡片高度收到 0，让列表回流）。它是 BiliPai 粒子消散的收尾动作，
     * 用户要求 1:1 移植，故保留其原写法。**触发时机极窄**：只有被解散的那一块在
     * 消散结束后跑一次（约 240ms），不在滚动路径上、也不随列表长度增长。
     *
     * **A2 迁移（2026-10-13 第 10 轮）：本字段与对应断言已删除**，改由 Lint 检测器
     * `BbspaceAnimateAsState` 执行。上面的历史记录（1 → 10 的每一次上调及理由）
     * 原样保留 —— 它是这批调用点的来历，删了就没人知道它们为什么在这儿。
     * 现存 10 处的清单在各模块 `lint.xml` 的 `<ignore path=...>` 里，一行一处。
     */

    /**
     * **真实模糊入口**（`hazeBlur(` + `drawBackdrop(`）：不比较半径就重建 = 每帧重编着色器。
     *
     * **AP-01(c) 改锚记录（2026-10-02）**：原棘轮锚在 `BlurEffect(` 上，但全仓 main 源码
     * 对 `BlurEffect(` 的命中为 **0** —— 真实模糊已全部迁入 miuix-blur / haze 依赖
     * （S5 反编译 miuix-blur 0.9.4-rc01 证实），原守卫**恒真、零咬合力**，是条假守卫。
     * 现改锚到仅存的两个真实模糊入口：
     * - `hazeBlur(`（GlassEffect.kt 的 haze 分支，全仓 1 处）；
     * - `drawBackdrop(`（miuix 背板采样，全仓 3 处：LiquidBackdrop 工厂 /
     *   HomeVideoPage.glassBadgeSurface / VideoPlayerPane.playerControlGlass）。
     * 每多一处就是多一条玻璃模糊链；新增前先回答能不能复用既有背板
     * （挂载点登记见 GlassMountRegistryTest）。
     */
    private val maxRealBlurEntries = 4

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

    // A2 迁移（2026-10-13 第 13 轮）：`runBlocking_isFrozen` 的**形态**判据已迁到 Lint
    // 检测器 `BbspaceUnboundedRunBlocking`（ci-lint/RunBlockingNeedsBoundDetector.java）：
    // runBlocking 的 lambda 体内必须出现 withTimeoutOrNull / withTimeout / runCatching 之一。
    // 本仓现存 2 处（MainActivity.preloadThemeConfig 与 VideoDetailActivity.preloadThemeConfig）
    // 都套了 withTimeoutOrNull ⇒ 检测器报告 0，**零基线**。
    //
    // 为什么这是真升级：棘轮自己留着一处勘误（"本注释此前写'与 MainActivity 的两处
    // 同源同目的'，与代码不符……交接数字未复核就以讹传讹"）—— 计数棘轮只知道有几处、
    // 不知道那几处长什么样。"数量对但漏了超时"这种情形，现在第一次变得可判。
    //
    // ⚠️ 迁移时踩的坑：Kotlin 的**尾随 lambda 不在 `getValueArguments()` 里**，
    // 第一版按实参表递归 ⇒ 现存两处**全被误报**。改用 `accept(AbstractUastVisitor)`
    // 深度遍历后归零。见执行报告 §1.14。
    //
    // ⚠️ 2026-10-18 审计补记：上面的"升级"只换了判据**维度**，**数量上限丢了** ——
    // 第 3 处带超时的 runBlocking 会同时通过检测器与旧报告。故在下方补回
    // `runBlocking_countStaysAtTwo()`（仓库级源码计数）作为第二道闸；
    // 为什么不再回 Lint 见 [maxRunBlocking] 的 KDoc。

    /**
     * 第二道闸：`runBlocking` 在**全仓 main 源码**里的总数不得超过 2。
     *
     * 与 Lint 检测器互补：检测器管"每一处有没有界"，本条管"一共几处"。
     * 判据与旧 `runBlocking_isFrozen` 相同（`\brunBlocking\b`，跳过注释/import 行），
     * 只减不增；新增调用点必须**同时**过这两道闸。
     */
    @Test
    fun runBlocking_countStaysAtTwo() {
        assertRatchet(
            actual = countInSources("""\brunBlocking\b"""),
            limit = maxRunBlocking,
            what = "runBlocking（全仓 main 源码）",
            why = "阻塞当前线程：主线程上直接掉帧、启动路径上拖慢冷启。" +
                "存量 2 处（MainActivity / VideoDetailActivity 的首帧主题预读）已带" +
                "withTimeoutOrNull 上限（Lint 检测器 BbspaceUnboundedRunBlocking 管形态），" +
                "本条管总量：新增第 3 处必须同时过形态与数量两道闸，且要写明理由。"
        )
    }

    // A2 迁移（2026-10-13 第 10 轮）：本断言已删除，改由 Lint 检测器
    // `BbspaceAnimateAsState`（ci-lint/AnimateAsStateDetector.java）执行。
    //
    // 为什么可以删：切换前做过**等价性证明** —— 检测器在 :app / :core:designsystem /
    // :feature:dynamic / :feature:home / :feature:video 五个模块里报出的正是同样那 10 处，
    // 行号逐一相同。已存在的 10 处按上游形态打进各模块 lint.xml 的 <ignore path=...>。
    //
    // 为什么更该删：棘轮只给"当前 10 处、上限 10 处"，加第 11 处时你得自己找在哪；
    // 检测器直接给文件:行，且靠**类型解析**（只认 androidx.compose.animation.* 下的
    // animate*AsState）排除同名自定义函数 —— 数文本的正则分不清，只能误报或漏报。
    //
    // ⚠️ 若日后要恢复本条，先读 docs/2026-10-13-上游同步落地-执行报告.md §1.11。

    @Test
    fun realBlurEntries_areFrozen() {
        assertRatchet(
            actual = countInSources("""\.(hazeBlur|drawBackdrop)\("""),
            limit = maxRealBlurEntries,
            what = "hazeBlur(/drawBackdrop(（真实模糊入口）",
            why = "每多一处就是多一条玻璃模糊链，且 miuix 的 DrawBackdropElement.equals 对 " +
                "lambda 引用比较 —— 不 remember 住整条链就等于每次重组重建模糊缓存。" +
                "新增前先回答：能不能复用既有背板？（挂载点登记见 GlassMountRegistryTest）"
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
