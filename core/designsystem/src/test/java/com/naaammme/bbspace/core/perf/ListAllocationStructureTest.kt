package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 列表项分配与重组的结构断言（任务书 T3 的"无设备验收"）。
 *
 * ## 为什么用源码结构断言而不是运行时测量
 *
 * 这一类优化的收益是"少分配了几个对象、少重组了几次"，**在 JVM 单测里测不出来**
 * （没有 Compose 运行时），在 CI 里也测不出来（没有设备）。唯一能被可靠固化的
 * 是**写法**：这些反模式都有明确的文本特征，扫一遍就知道有没有。
 *
 * ## 与 [FrameBudgetLintTest] 的分工
 *
 * `FrameBudgetLintTest` 管**全仓计数**（棘轮：某反模式总共不超过 N 处）。
 * 本类管**指定文件的指定写法**（"HomeVideoPage 里不许内联 copy 排版"）。
 * 两者互补：棘轮防扩散，结构断言防回潮——某处被"顺手改回去"时棘轮可能仍通过。
 *
 * ## 照搬上游的两个防假绿设计（勿简化）
 *
 * 1. **每个断言先确认文件真的找到了**。路径一变就"读不到文件、于是没有违规、
 *    于是一切通过"是本类测试独有的假绿形态（棘轮那边靠"扫到 >100 文件"兜底，
 *    但本类看的是具体单个文件，必须逐个兜底）。
 * 2. **跳过注释行**（[countMatching]）。把"为什么这么写"记在注释里不该让断言失败；
 *    本仓库的性能注释非常密集，不跳注释会让注释变成有代价的事。
 */
class ListAllocationStructureTest {

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

    private val homeVideoPage: List<String> get() = sourceOf("HomeVideoPage.kt")
    private val filledTabRow: List<String> get() = sourceOf("FilledTabRow.kt")
    private val feedItem: List<String> get() = sourceOf("feed${FS}FeedItem.kt")
    private val coverExpand: List<String> get() = sourceOf("CoverExpand.kt")

    private val FS: String get() = File.separator

    // ── 断言：卡片排版样式必须 remember ────────────────────────────────────────

    /**
     * `MaterialTheme.typography.xxx.copy(...)` 直接写在参数上是**每次重组新建一个
     * TextStyle**。TextStyle 是重对象（约 20 个字段，内含 SpanStyle 等），
     * 信息流卡片每可见一张、每重组一次就分配一份。
     */
    @Test
    fun feedCardDoesNotCopyTypographyInline() {
        val hits = countMatching(homeVideoPage, """typography\.[A-Za-z]+\.copy\(""")
        assertTrue(
            "HomeVideoPage 里有 $hits 处 `typography.x.copy(...)` 内联样式。" +
                "卡片体内这样做等于每张卡每次重组新建一份 TextStyle，" +
                "应以 `remember(baseStyle) { baseStyle.copy(...) }` 提出来。",
            hits == 0
        )
    }

    /** 上一条的正面断言：确实存在一个被 remember 的卡片标题样式，而不是被直接删掉了。 */
    @Test
    fun feedCardTitleStyleIsMemoized() {
        val hits = countMatching(homeVideoPage, """val\s+titleStyle\s*=\s*remember\(""")
        assertTrue(
            "HomeVideoPage 应把卡片标题样式 remember 起来（`val titleStyle = remember(...)`）。" +
                "现在找不到它——如果是有意改名，请连同本断言一起改。",
            hits >= 1
        )
    }

    /**
     * 全仓 `typography.x.copy(` 棘轮：制定日（2026-09-22）实测 7 处，T3 收 1 处后
     * 冻结为 6；**B21 把剩下 6 处全部收口，上限降到 0**（UI-02）。
     *
     * 收法不是"每处各写一个 remember"，而是走 `TextStyleVariants.kt` 的三个具名出口
     * （`asBold()` / `withFontSize()` / `withTextColor()`），因为它们内部就是
     * `remember(this, …)`：主题或字阶档变了才重新算。
     *
     * 刻意**不提供**通用的 `copy { … }` 透传：那等于让内联 copy 换个写法继续存在，
     * 这条棘轮就白设了。要新的排版变体，先在 TextStyleVariants 里加一个**有语义名字**的出口。
     */
    @Test
    fun typographyInlineCopy_isFrozen() {
        val actual = mainSources.sumOf { countMatching(it.readLines(), """typography\.[A-Za-z]+\.copy\(""") }
        val limit = 0
        val probe = listOf(
            "    Text(style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))"
        )
        assertTrue(
            "自检失败：内联 copy 的正则连示例都抓不到，那 limit=0 就是永真",
            countMatching(probe, """typography\.[A-Za-z]+\.copy\(""") == 1
        )
        assertTrue(
            "全仓内联 `typography.x.copy(` 当前 $actual 处，上限 $limit。" +
                "在组合路径上这样做每次重组都会新建一份重对象 TextStyle（约 20 字段）；" +
                "请改用 core/designsystem/theme/TextStyleVariants.kt 的具名出口，" +
                "需要新变体就在那儿加一个有语义名字的函数。" +
                "若只是注释触发了计数，说明 countMatching 没跳过注释行。",
            actual <= limit
        )
        val helpers = sourceOf("TextStyleVariants.kt")
        assertTrue(
            "TextStyleVariants 的三个出口不见了（棘轮降到 0 的前提是有地方可去）：" +
                "asBold=${countMatching(helpers, """fun TextStyle\.asBold\(""")} " +
                "withFontSize=${countMatching(helpers, """fun TextStyle\.withFontSize\(""")} " +
                "withTextColor=${countMatching(helpers, """fun TextStyle\.withTextColor\(""")}",
            countMatching(helpers, """fun TextStyle\.(asBold|withFontSize|withTextColor)\(""") == 3
        )
    }

    // ── 断言：列表 key 不在调用点拼接 ──────────────────────────────────────────

    /**
     * `FeedItem.actionKey` 必须是**属性**而不是每次调用的函数。
     *
     * 它是 `AdaptiveMediaGrid` 的 key lambda 与 dislike 映射的键：作为函数时，
     * 每个滚动帧里每张可见卡片至少调用两次，每次拼出一个新 String。
     */
    @Test
    fun feedItemActionKeyIsAProperty() {
        val decl = countMatching(feedItem, """val\s+actionKey\s*:\s*String\s*=""")
        assertTrue(
            "FeedItem.actionKey 应是构造期赋值的 val（`val actionKey: String =`），" +
                "当前找到 $decl 处声明。",
            decl == 1
        )
    }

    /**
     * 全仓不许再出现 `fun FeedItem.actionKey(`。
     *
     * 制定本断言的直接原因：`HomeViewModel` 与 `HomeVideoPage` 曾各有一份**逐字相同**的
     * `private fun FeedItem.actionKey() = "$goto|$param|$idx"`。两处独立演化迟早会算出
     * 不同的键，而键不一致的后果是列表 item 复用错乱（滚动时卡片内容串位），
     * 且这类 bug 只在两个模块被同时改动后才出现，review 很难发现。
     */
    @Test
    fun actionKeyExtensionIsNotRedeclared() {
        val sites = mainSources.filter { file ->
            countMatching(file.readLines(), """fun\s+[A-Za-z.]*FeedItem\.actionKey\(""") > 0
        }
        assertTrue(
            "以下文件重新声明了 FeedItem.actionKey 扩展函数，应改用 FeedItem 上的属性：" +
                sites.joinToString { it.name } +
                "。重复定义的键一旦分叉，列表复用会静默错乱。",
            sites.isEmpty()
        )
    }

    // ── 断言：形状常量在文件级 ─────────────────────────────────────────────────

    /**
     * 卡片形状不许在 composable 内就地构造。
     *
     * 只扫**缩进行**：文件级的 `private val FeedCardShape = RoundedCornerShape(...)`
     * 是正确写法，composable 体内缩进的 `val cardShape = RoundedCornerShape(...)` 才是
     * 每张卡每次重组一份分配。这个区分靠缩进就够，不需要解析语法。
     */
    @Test
    fun feedCardShapesAreFileLevel() {
        val locals = countMatching(homeVideoPage, """^\s+val\s+\w*[Ss]hape\w*\s*=\s*RoundedCornerShape\(""")
        assertTrue(
            "HomeVideoPage 的 composable 体内有 $locals 处就地 `RoundedCornerShape(...)`。" +
                "这些形状只由 BiliRadius.card 决定、全进程不变，应提到文件级常量" +
                "（连 remember 的槽位与键比较一起省掉）。",
            locals == 0
        )
        assertTrue(
            "HomeVideoPage 应保留文件级形状常量 FeedCardShape（见 feedCardShapesAreFileLevel）。",
            countMatching(homeVideoPage, """^private val FeedCardShape""") == 1
        )
    }

    // ── 断言：动画值在布局/绘制期读 ────────────────────────────────────────────

    /**
     * FilledTabRow 的分段控件必须走 **Miuix 渲染层**（`AppMiuixSegmentedControl`）。
     *
     * ## 2026-10-18（W2 批次 2）为什么改这条断言
     * 原断言防的是"自绘滑块在**组合期**读 `animateDpAsState` ⇒ 切 Tab 时每帧重组顶栏"。
     * 现在 iOS 分支整段自绘实现（`IOSSegmented`：`BoxWithConstraints` + `animateDpAsState`
     * + `offset {}`）**已删除**，改为委托 `AppMiuixSegmentedControl`（miuix `TabRow`，
     * 指示器位移是库内 spring、不占本仓 `animate*AsState` 棘轮）。
     * ⇒ 旧断言的**对象不存在了**，语义转成三条仍可失败的新断言：
     * ① 必须委托给唯一出口；② 不许回潮组合期 `offset(x = )`；
     * ③ 不许再自绘滑块动画（`animate*AsState(` **或** `Animatable(`）；
     * ④ `IOSSegmented` 这个名字不得再出现（防旧实现被贴回来）。
     *
     * ⚠️ 这是**结构改动带来的锚点迁移**，不是放宽：四条断言都仍然会在违规时红。
     *
     * **2026-10-18 审计补强（W6 复核）**：原第 ③ 条只认 `animateDpAsState(` —— 审计指出
     * "自绘滑块改用 `animateFloatAsState` / `Animatable` / `graphicsLayer { translationX }`
     * 时三条断言全过"。现把正则扩到 `animate[A-Za-z]*AsState\(|Animatable\(`，并补第 ④ 条。
     * 注意：`Animatable(` 只在**本文件**里禁（顶栏本该零动画状态）；别处用 Animatable 是合规写法。
     */
    @Test
    fun segmentedControlDelegatesToMiuixRenderer() {
        val delegates = countMatching(filledTabRow, """AppMiuixSegmentedControl\(""")
        assertTrue(
            "FilledTabRow 的 iOS 分支必须委托给 `AppMiuixSegmentedControl`（分段控件唯一出口）；" +
                "当前 $delegates 处。",
            delegates >= 1
        )
        val eager = countMatching(filledTabRow, """\.offset\(\s*x\s*=""")
        assertTrue(
            "FilledTabRow 里不许出现 `offset(x = ...)`（组合期读动画值 ⇒ 每帧重组顶栏）：当前 $eager 处。",
            eager == 0
        )
        val selfDrawn = countMatching(filledTabRow, """animate[A-Za-z]*AsState\(|Animatable\(""")
        assertTrue(
            "FilledTabRow 不许自绘滑块动画（`animate*AsState(` / `Animatable(`）：指示器交给 miuix " +
                "库内部实现。当前 $selfDrawn 处。",
            selfDrawn == 0
        )
        val legacy = countMatching(filledTabRow, """IOSSegmented""")
        assertTrue(
            "FilledTabRow 里出现了 `IOSSegmented`（已删除的自绘滑块实现）。" +
                "iOS 分支的唯一出口是 `AppMiuixSegmentedControl`，不要贴回旧实现。当前 $legacy 处。",
            legacy == 0
        )
    }

    /**
     * `onGloballyPositioned` 的坐标去重必须在**构造 Rect 之前**完成。
     *
     * 该回调在滚动期间每张卡片每帧触发。原实现先 `Rect(4 个量化分量)` 再和 map 里的值比，
     * 于是去重只省了一次 map 写入，对象分配一次没省下——去重形同虚设。
     *
     * 断言方式：[put] 函数体内，`Rect(` 只允许出现在第一次 `return` 之后。
     */
    @Test
    fun coverExpandDedupesBeforeAllocatingRect() {
        val body = functionBody(coverExpand, "fun put(")
        val returnAt = body.indexOfFirst { it.contains("return") }
        assertTrue(
            "CoverExpandBoundsStore.put 里找不到提前 return，说明去重逻辑被改没了。",
            returnAt >= 0
        )
        val early = body.take(returnAt).filter { it.contains("Rect(") && !it.contains("fun put(") }
        assertTrue(
            "CoverExpandBoundsStore.put 在提前 return **之前**构造了 Rect（${early.size} 处）：" +
                "滚动期每卡每帧白分配一个对象，去重形同虚设。" +
                "应先量化 4 个 Float 分量、与已存值逐个比较，只在真的变了时才构造 Rect。",
            early.isEmpty()
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    /**
     * 按路径后缀定位 main 源码并读出行。
     *
     * **找不到文件时必须响亮失败**：本类的断言都是"某文件里没有某种写法"，
     * 文件读不到就会得到"没有违规"的假绿——比测试不存在更危险。
     */
    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
        if (file == null) {
            val byName = mainSources
                .filter { it.name.endsWith(normalized.substringAfterLast(File.separatorChar)) }
                .joinToString { it.path }
            throw AssertionError(
                "结构断言找不到目标文件 `$suffix`（按名字候选：$byName）。" +
                    "要么文件被移动/改名，要么扫描根目录不对——此时所有\"没有违规\"都是假的。"
            )
        }
        return file.readLines()
    }

    /** 统计匹配 [regex] 的非注释、非 import 行数（理由见类注释第 2 条）。 */
    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@count false
            if (trimmed.startsWith("import ")) return@count false
            re.containsMatchIn(line)
        }
    }

    /**
     * 抽取以 [signaturePrefix] 开头的函数体（含签名行），按花括号配平定界。
     *
     * 只用于本类里"某段逻辑内部顺序"这种无法用单行正则表达的断言。
     * 找不到签名时抛异常——同样是为了避免"没扫到就算通过"。
     */
    private fun functionBody(lines: List<String>, signaturePrefix: String): List<String> {
        // W23：内部按 trimStart() 比较，带缩进的前缀会静默失配、红得莫名其妙 —— 入口处直接拒绝。
        require(!signaturePrefix.startsWith(" ") && !signaturePrefix.startsWith("\t")) {
            "signaturePrefix 带了前导缩进（\"$signaturePrefix\"）：本 helper 对行做 trimStart() 比较，" +
                "缩进会导致永不匹配。请传去掉缩进的签名。"
        }
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue(
            "找不到以 `$signaturePrefix` 开头的函数，无法验证其内部顺序。",
            start >= 0
        )
        var depth = 0
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (i > start && depth <= 0) break
        }
        return body
    }

    // ── 断言：形状与路径的分配点（PF-05 / PF-06，W7 的覆盖面扩展）──────────────

    /**
     * 全仓 composable 体内**不许**就地 `val xxxShape = RoundedCornerShape(...)`。
     *
     * 制定本批的直接原因：PF-06 的原描述（"勋章拼图五个 Shape 内联构造"）经复核**已经过时**
     * —— 那五个早已收进 `UserCollageShapes` 文件级对象。真正还内联的是**播放器**那 5 处
     * （`VideoScreen` 展开/竖排、`VideoPlayerPane` 轨道/控制条/统计胶囊）+
     * 两处 `RoundedCornerShape(20.dp)` 裸字面量，本批一并提到文件级。
     *
     * 为什么规则写成 0 而不是棘轮计数：这类写法**没有任何一种情况是必要的**
     * （值恒定 → 就该是常量；值随状态变 → 必须 `remember(keys)`，两种都不是"裸内联"）。
     * 与 [typographyInlineCopy_isFrozen] 同理，那条也在 B21 从 6 降到了 0。
     */
    @Test
    fun shapeConstantsAreNeverBuiltInsideComposables() {
        val offenders = mainSources.flatMap { file ->
            val rel = file.relativeTo(repoRoot).path.replace('\\', '/')
            inlineShapeSites(file.readLines()).map { "$rel:${it.first}" }
        }
        val probe = listOf(
            "@Composable",
            "fun Thing() {",
            "    val pill = RoundedCornerShape(percent = 50)",
            "}",
            "",
            "private object Shapes {",
            "    val octagon = PolygonShape(sides = 8)",
            "}"
        )
        val probeHits = inlineShapeSites(probe)
        assertTrue(
            "自检失败：合成的\"组合体内联 vs 文件级对象\"两个样例应恰好抓到 1 处，实际 $probeHits。" +
                "规则一旦失效，下面那条全仓断言就成了永真。",
            probeHits.size == 1 && probeHits[0].first == 3
        )
        assertTrue(
            "这些形状是在 composable / 函数体内就地构造的：${offenders.joinToString()}。" +
                "每次重组都新建一份 Shape；它们还常被 clip/背景/玻璃链当 remember 的键，" +
                "键每帧变新会让\"remember 整条链\"的优化静默失效。" +
                "值恒定就提到文件级（顶层 object 里的常量本规则不计），值随状态变就要 remember(keys)。",
            offenders.isEmpty()
        )
    }

    /**
     * 找"在函数/composable 体内就地构造形状"的行。
     *
     * 判定依据不是变量名（`val octagon = PolygonShape(...)` 也得算），而是**当前缩进块
     * 的最外层 opener**：`private object X {` 里的是文件级常量（正确写法），
     * `fun` 里的是每次调用的分配。按花括号配平跟踪 opener，够用于本仓的代码风格。
     */
    private fun inlineShapeSites(lines: List<String>): List<Pair<Int, String>> {
        val ctor = Regex(
            """^\s+(val|var)\s+\w+\s*=\s*(RoundedCornerShape|AbsoluteRoundedCornerShape|CircleShape|""" +
                """PolygonShape|StarShape|CutoutRoundedRectShape)\("""
        )
        val containerOfFileLevel = Regex("""^\S.*\b(object|enum class)\b""")
        // 栈里第 1 个元素就是"包住这一行的最外层顶层声明"；它以 object / enum class 开头
        // 就是文件级常量容器（正确写法），以 fun/class 开头就是调用期分配。
        val stack = ArrayDeque<String>()
        val hits = mutableListOf<Pair<Int, String>>()
        for ((index, raw) in lines.withIndex()) {
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
            if (ctor.containsMatchIn(raw)) {
                val owner = stack.firstOrNull().orEmpty()
                if (!containerOfFileLevel.containsMatchIn(owner)) hits += (index + 1) to trimmed
            }
            val opens = raw.count { it == '{' }
            val closes = raw.count { it == '}' }
            repeat(opens) { stack.addLast(raw) }
            repeat(closes) { if (stack.isNotEmpty()) stack.removeLast() }
        }
        return hits
    }

    /** 勋章拼图的五个形状必须继续待在文件级对象里（PF-06 的真实结论，防回潮）。 */
    @Test
    fun collageShapesStayInFileLevelObject() {
        val lines = sourceOf("UserCollageSection.kt")
        assertTrue(
            "UserCollageShapes 这个文件级对象不见了（五个多边形/星形被搬回组合体内的话，" +
                "每个拼图 item 每次重组分配 5 份 Shape）。",
            countMatching(lines, """^\s*private object UserCollageShapes""") == 1
        )
        assertTrue(
            "UserCollageShapes 里应能数出 5 个形状常量",
            countMatching(lines, """^\s+val \w+ = (PolygonShape|StarShape)\(""") == 5
        )
    }

    private fun drawBehindBlock(lines: List<String>): List<String> {
        // 只认**代码形态**的 `.drawBehind {`：本仓的性能注释密度很高，
        // 一句"原先这段在 drawBehind 里"就能把 indexOfFirst 带偏到 KDoc 上，
        // 于是扫到的是无关区块、断言变成永真（B20 实测踩过）。
        val start = lines.indexOfFirst { it.contains(Regex("""^\s*\.drawBehind\s*\{""")) }
        assertTrue(
            "SelectableText 里找不到 `.drawBehind {` —— 写法变了就要连同本断言一起改，" +
                "否则这条等于没跑。",
            start >= 0
        )
        var depth = 0
        val block = mutableListOf<String>()
        for (i in start until lines.size) {
            block += lines[i]
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (depth <= 0) break
        }
        return block
    }

    /**
     * ~~`Path()` / `Outline.Generic` 只许出现在形状自己的 `createOutline` / `build*Path` 里。~~
     *
     * **A2 迁移（2026-10-13 第 12 轮）：本断言已删除**，改由 Lint 检测器
     * `BbspacePathOutlineAllocation`（ci-lint/PathOutlineAllocationDetector.java）执行。
     *
     * 为什么这条迁得最干净：**现存 4 处全部合规**（2 处 `Outline.Generic` 在
     * `createOutline` 里、2 处 `Path()` 在 `buildPolygonPath`/`buildStarPath` 里）
     * ⇒ 检测器报告 0，**一条 `lint.xml` 豁免都不用写**。前两次迁移都要先打基线，
     * 这次不用 —— 因为判据从"总量不超过 4"升级成了"只能在 createOutline / build*Path 里"，
     * 而存量恰好都在那里。
     *
     * 覆盖面也因此变严：棘轮说"总共不超过 4 处"，别处再添 3 处仍绿；
     * 检测器说"只能在 createOutline / build*Path 里"，别处添 1 处就红。
     *
     * W7 的教训（**棘轮的覆盖面 = 它真正会红的写法集合，不是它的名字**）原样保留 ——
     * 它正是把覆盖面从"出现次数"改到"所在函数"的理由。
     *
     * 迁移详情与踩坑记录（`Path()` 是顶层工厂不是构造器、`Outline.Generic` 是嵌套类
     * 构造器）见 docs/2026-10-13-上游同步落地-执行报告.md §1.13。
     */

    /**
     * 选区背景路径不许在 `drawBehind` 里现算（PF-05）。
     *
     * `TextLayoutResult.getPathForRange` 只有"返回新 Path"的重载（javap 查过 ui-text
     * 1.11.2 / 1.12.1 都只有 `getPathForRange(int, int): Path`），所以分配省不掉，
     * 能省的是**重绘但选区没变**的那些：写在 drawBehind 里 = 每次重绘一份 Path。
     */
    @Test
    fun selectionPathIsRememberedNotBuiltPerRedraw() {
        val lines = sourceOf("SelectableText.kt")
        val drawBehind = drawBehindBlock(lines)
        assertTrue(
            "drawBehind 里又出现 getPathForRange —— 每次重绘分配一个 Path（PF-05 的病灶）。" +
                "应改回带 key 的 remember。",
            countMatching(drawBehind, """getPathForRange\(""") == 0
        )
        assertTrue(
            "选区路径必须由带 key 的 remember 产出，否则本文件等于没修",
            countMatching(lines, """remember\(textLayoutResult, selection, text\.length\)""") == 1
        )
    }

    // ── 形状分配的绕过形态（AP-29 ③ / W7 的覆盖面扩展）────────────────────────

    /**
     * 上限 = 制定日（2026-10-02）用**本仓扫描器**实测的计数（W24）：
     * 具名实参 16 处、表达式体工厂 2 处（均在 `Radius.kt`，见下）。只许降。
     */
    private val maxNamedArgShapes = 16
    private val maxFactoryFunctionShapes = 2

    /**
     * AP-29 ③：原 `shapeConstantsAreNeverBuiltInsideComposables` 只认
     * "局部 `val = RoundedCornerShape(`"，两类常见的绕过形态全逃逸：
     * (a) **具名实参** `shape = RoundedCornerShape(...)`；
     * (b) **包一层表达式体函数** `fun x(...): Shape = RoundedCornerShape(...)`
     *     （函数体是表达式，没有 `val` 行可匹配）。
     * 两类同罪：每次调用/每次组合各分配一份 Shape。这里按实测基线冻结（只许减）。
     *
     * 有意保留（写在这里，不写豁免表）：`Radius.kt` 的 `topRounded/bottomRounded`
     * 是**带参数的**文件级工厂（值随调用方变），归因在调用方 remember；`CoverExpand`
     * 的逐帧动画角半径是绘制期必需值。
     */
    @Test
    fun shapeAllocationBypassForms_areFrozenAtMeasuredBaseline() {
        val namedArg = """\bshape\s*=\s*RoundedCornerShape\("""
        val factory = """\bfun\s+\w+\([^)]*\)[^=]*=\s*(?!remember)RoundedCornerShape\("""
        // W21 正反例自检
        assertTrue(
            "自检失败：具名实参形态抓不到",
            countMatching(listOf("        shape = RoundedCornerShape(BiliRadius.card),"), namedArg) == 1
        )
        assertTrue(
            "自检失败：包函数形态抓不到",
            countMatching(listOf("fun dockCapsuleShape(): Shape = RoundedCornerShape(BiliRadius.dock)"), factory) == 1
        )
        assertTrue(
            "自检失败：已 remember 的工厂不许被计",
            countMatching(
                listOf("internal fun dockCapsuleShape(): Shape = remember { RoundedCornerShape(BiliRadius.dock) }"),
                factory
            ) == 0
        )

        val namedArgSites = mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (countMatching(listOf(line), namedArg) > 0) "${file.name}:${index + 1}" else null
            }
        }
        val factorySites = mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (countMatching(listOf(line), factory) > 0) "${file.name}:${index + 1}" else null
            }
        }
        assertTrue(
            "具名实参 `shape = RoundedCornerShape(` 当前 ${namedArgSites.size} 处，" +
                "冻结上限 $maxNamedArgShapes：$namedArgSites",
            namedArgSites.size <= maxNamedArgShapes
        )
        assertTrue(
            "表达式体工厂 `fun … = RoundedCornerShape(`（无 remember）当前 ${factorySites.size} 处，" +
                "冻结上限 $maxFactoryFunctionShapes：$factorySites",
            factorySites.size <= maxFactoryFunctionShapes
        )
    }
}
