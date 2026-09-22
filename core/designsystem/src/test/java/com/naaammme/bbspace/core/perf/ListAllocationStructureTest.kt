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
     * 全仓 `typography.x.copy(` 棘轮。制定日（2026-09-22）实测 7 处，
     * T3 修掉信息流卡片这 1 处后**冻结为 6，只许减**。
     *
     * 为什么不直接冻结为 0：其余 6 处分布在评论搜索、IM 输入框、排行榜、个人主页
     * 头部与关注列表，都不在首页滚动路径上，收益低且改动面广。留给后续任务逐条收口，
     * 但**不允许新增**。
     */
    @Test
    fun typographyInlineCopy_isFrozen() {
        val actual = mainSources.sumOf { countMatching(it.readLines(), """typography\.[A-Za-z]+\.copy\(""") }
        val limit = 6
        assertTrue(
            "全仓内联 `typography.x.copy(` 当前 $actual 处，超过冻结上限 $limit 处。" +
                "在组合路径上这样做每次重组都会新建一份重对象 TextStyle。" +
                "确实必要请连同上限一起改并写明理由；若只是注释触发了计数，" +
                "说明 countMatching 没跳过注释行。",
            actual <= limit
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
     * iOS 分段滑块的位移必须走 `offset { }`（布局期读），不能是 `offset(x = )`（组合期读）。
     *
     * 这条尤其值钱，因为它在**顶栏里**：`animateDpAsState` 的值被组合期读取时，
     * 切 Tab 的整个 spring 过程每帧重组一次顶栏。
     */
    @Test
    fun segmentedControlReadsThumbOffsetInLayoutPhase() {
        val eager = countMatching(filledTabRow, """\.offset\(\s*x\s*=""")
        val lazyOnes = countMatching(filledTabRow, """\.offset\s*\{""")
        assertTrue(
            "FilledTabRow 仍有 $eager 处 `offset(x = ...)`。它在组合期读 animateDpAsState 的值，" +
                "切 Tab 时每帧重组顶栏。应改为 `offset { IntOffset(px, 0) }`，" +
                "并保留 animateDpAsState 的 State 引用（不要 `by` 解包）。",
            eager == 0
        )
        assertTrue(
            "FilledTabRow 应使用布局期偏移（`offset { ... }`），当前找到 $lazyOnes 处。",
            lazyOnes >= 1
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
}
