package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 骨架"一个时钟"结构守卫（AP-01(b) / AP-02 对 FL-02(b) 的修正形态）。
 *
 * ## 缺陷本身（规则精确到缺陷，不按文件一刀切 —— W18）
 *
 * `rememberSkeletonBreath` 每个实例是一条常驻无限动画。骨架组件
 * （`VideoListCardSkeleton` 等）**自带** `ProvideSkeletonShimmer`（幂等：祖先已提供
 * 就复用），于是"逐个调用单卡骨架 + 无容器级 provider"的场景下，**每个 item 各起
 * 一条** `rememberInfiniteTransition` —— 源码调用点数不变、动画条数 = item 数。
 * 这正是 AP-01 说的盲区：数调用点的棘轮永远绿，运行期实例数失控。
 *
 * **正确范式（仓库内已有，本守卫即钉住它）**：`Skeleton.kt` 的 `VideoListSkeletonList`
 * —— 容器级 `ProvideSkeletonShimmer` 包住整个 LazyColumn，`items(count = …)` 体内
 * 的单卡骨架复用同一份呼吸时钟。
 *
 * ## 断言（AP-02 修正后的形态，替代 FL-02(b) 原措辞）
 *
 * FL-02(b) 原措辞「`rememberSkeletonBreath` 不得出现在 `items(count =` 的 item 体内」
 * 会误伤 `Skeleton.kt` 自身的**函数定义处**（`ProvideSkeletonShimmer` 的函数体本来
 * 就要调它）。修正后断言三件事：
 * 1. `rememberSkeletonBreath` 的**调用点**（不是定义点；定义点白名单 = `Skeleton.kt`
 *    内的骨架函数体）在 `Skeleton.kt` 之外必须为 **0**；
 * 2. `items(count = …)` 的 item 体内不得直接调用 `ProvideSkeletonShimmer` /
 *    `VideoListCardSkeleton` / `VideoGridCardSkeleton`，**除非同文件存在容器级
 *    provider**（容器级 = 不在任何 `items` 体内、也不在任何单卡骨架定义体内）；
 * 3. 上面第 2 条曾对 5 处违规**显式挂账**（搜索 / 历史 / 稍后再看 / 历史搜索 /
 *    收藏），AP-15 修复后挂账已清零 —— 现在是**硬规则**：任何文件在
 *    `items(count = …)` 体内直接调用单卡骨架且无容器级 provider（或 AP-15 提升形态）
 *    即红。
 *
 * ## 纯 JVM
 * 无设备可判定。W21 自检（合成正反例）内建；W22：验证时单模块单任务 `--rerun`。
 */
class SkeletonShimmerStructureTest {

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

    /** 逐个实例化会各起一条动画的骨架 API（调用形态，W20：匹配调用不是名字）。 */
    private val skeletonCallPatterns: List<Pair<String, Regex>> = listOf(
        "ProvideSkeletonShimmer" to Regex("""\bProvideSkeletonShimmer\s*[\(\{]"""),
        "VideoListCardSkeleton" to Regex("""\bVideoListCardSkeleton\s*\("""),
        "VideoGridCardSkeleton" to Regex("""\bVideoGridCardSkeleton\s*\("""),
    )

    private val breathCallPattern = Regex("""\brememberSkeletonBreath\s*\(""")
    private val funNamePattern = Regex("""\bfun\s+(?:[A-Za-z][A-Za-z0-9_]*\.)?\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val funDefLinePattern = Regex("""^\s*(private\s+|internal\s+|public\s+)?fun\s""")
    private val itemsOpenerPattern = Regex("""\bitems\s*\(""")
    private val countParamPattern = Regex("""\bcount\s*=""")

    /**
     * 单卡骨架**定义体**白名单：这些函数体内的骨架调用/呼吸调用是"自带 provider"
     * 的实现本身，不是调用方缺陷。判定 = 函数名以 `Skeleton` 结尾（不含
     * `SkeletonList` 结尾 —— 容器不是单卡），或就是 `ProvideSkeletonShimmer` 本身。
     */
    private fun isSingleCardSkeletonDefinition(name: String): Boolean =
        name == "ProvideSkeletonShimmer" ||
            (name.endsWith("Skeleton") && !name.endsWith("SkeletonList"))

    /**
     * **显式挂账**（棘轮只许降）：曾登记 5 处 `items(count = …)` 体内直接调用单卡
     * 骨架且无容器级 provider 的入口（搜索 / 历史 / 稍后再看 / 历史搜索 / 收藏）。
     * AP-15 已全部修复（屏幕层 hoist 共享呼吸 + item 内注入），挂账**清零**；
     * 此表只许保持为空 —— 新增条目必须连同"为什么暂时不修"的理由一起登记，
     * 且修复后必须移除（死条目也会红）。
     */
    private val knownDeferred: Map<String, String> = emptyMap()

    /** AP-15 的提升形态：屏幕层 `rememberSkeletonBreathOrNull()` 恰好 1 处 = 共享一个时钟。 */
    private val hoistedBreathPattern = Regex("""\brememberSkeletonBreathOrNull\s*\(""")

    // ── 断言 ─────────────────────────────────────────────────────────────────

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效。",
            mainSources.size > 100
        )
    }

    /**
     * AP-02 修正后的 FL-02(b)：`rememberSkeletonBreath` 的调用点只许存在于
     * `Skeleton.kt` 的骨架定义体内（那里的调用是实现自身），其余任何地方新增
     * "自己起一条无限动画"都红 —— 要呼吸，请走 `ProvideSkeletonShimmer` 共享时钟。
     */
    @Test
    fun rememberSkeletonBreathIsOnlyCalledInsideSkeletonDefinitions() {
        val offenders = mainSources.flatMap { file ->
            val rel = file.relativeTo(repoRoot).path.replace('\\', '/')
            if (rel.endsWith("component/Skeleton.kt")) return@flatMap emptyList()
            file.readLines().mapIndexedNotNull { index, line ->
                if (!isCodeLine(line)) return@mapIndexedNotNull null
                if (funDefLinePattern.containsMatchIn(line)) return@mapIndexedNotNull null
                if (!breathCallPattern.containsMatchIn(line)) return@mapIndexedNotNull null
                "$rel:${index + 1}"
            }
        }
        assertTrue(
            "Skeleton.kt 之外出现了 rememberSkeletonBreath 调用点：$offenders。" +
                "每处调用 = 一条新的常驻无限动画。正确做法是包一层 ProvideSkeletonShimmer" +
                "（幂等，外层已提供时零开销），让同屏骨架共用一个时钟。",
            offenders.isEmpty()
        )
    }

    /**
     * AP-01(b)：`items(count = …)` 的 item 体内不得直接调用单卡骨架，
     * 除非同文件有容器级 provider。挂账表之外的违规 = 新增 = 红。
     */
    @Test
    fun itemCountSkeletonCallsNeedContainerProvider() {
        val violations = scanViolations()
        val unknown = violations.keys - knownDeferred.keys
        assertTrue(
            buildString {
                append("items(count=…) 体内直接调用单卡骨架且无容器级 provider 的新违规：")
                if (unknown.isEmpty()) {
                    append("（无）")
                } else {
                    unknown.sorted().forEach { rel ->
                        append("\n    $rel —— 行 ${violations[rel]}。每次翻页每项各起一条" +
                            "rememberInfiniteTransition（AP-01 的实例数盲区）。修复：同文件容器级" +
                            "ProvideSkeletonShimmer 包住列表（范式见 Skeleton.kt VideoListSkeletonList），" +
                            "并评估是否需要挂账。")
                    }
                }
            },
            unknown.isEmpty()
        )
        // 反向：挂账表必须保持诚实 —— 已不违规的死条目也红（修复后必须移除，棘轮只许降）。
        val stale = knownDeferred.keys - violations.keys
        assertTrue(
            "挂账表里有已修复的死条目（棘轮只许降，修完必须移除）：$stale",
            stale.isEmpty()
        )
    }

    /**
     * W21 自检：合成正反例证明——调用形态抓得到；定义行/注释/import 不误伤；
     * `items(count=…)` 的 item 体能圈出来；容器级 provider 判定成立。
     */
    @Test
    fun scannerSelfCheck() {
        val positive = "                                    VideoListCardSkeleton()"
        skeletonCallPatterns.forEach { (name, re) ->
            val sample = when (name) {
                "ProvideSkeletonShimmer" -> "        ProvideSkeletonShimmer {"
                "VideoListCardSkeleton" -> positive
                else -> "        VideoGridCardSkeleton()"
            }
            assertTrue("自检失败：$name 的正则抓不到调用形态", re.containsMatchIn(sample))
        }
        // 扫描器把一行当调用点的完整判据 = isCodeLine && 非 fun 定义行 && 正则命中。
        val negatives = listOf(
            "fun VideoListCardSkeleton(modifier: Modifier = Modifier) {",   // 定义行
            "    private fun VideoGridCardSkeleton(modifier: Modifier = Modifier) {", // 带可见性的定义行
            "// VideoListCardSkeleton() 在注释里",                            // 注释
            "import com.naaammme.bbspace.core.designsystem.component.VideoListCardSkeleton", // import
            " * 引用 [VideoGridCardSkeleton] 的 KDoc"                          // KDoc
        )
        negatives.forEach { line ->
            val wouldCount = isCodeLine(line) &&
                !funDefLinePattern.containsMatchIn(line) &&
                skeletonCallPatterns.any { (_, re) -> re.containsMatchIn(line) }
            assertTrue("自检失败：本应跳过的行被当成调用：「$line」", !wouldCount)
        }
        // items(count=…) 块圈定：体内的调用应被判进 block，体外的判不在。
        // 两个形态都要圈住：多行参数（跨行 args）与单行 `items(count = 2) { … }`。
        val synthetic = listOf(
            "        LazyColumn {",
            "            items(",
            "                count = 2,",
            "                key = { i -> \"k\$i\" }",
            "            ) {",
            "                VideoListCardSkeleton()",
            "            }",
            "        }",
            "        VideoGridCardSkeleton()"
        )
        val oneLine = listOf(
            "            items(count = 2) {",
            "                VideoListCardSkeleton()",
            "            }",
            "        VideoGridCardSkeleton()"
        )
        listOf(
            "多行参数" to Triple(synthetic, 6, 9),
            "单行参数" to Triple(oneLine, 2, 4)
        ).forEach { (label, spec) ->
            val (lines, callLine, outsideLine) = spec
            val blocks = itemsCountBlocks(lines)
            assertTrue("自检失败（$label）：items(count=…) 块应恰好圈出 1 个，实际 ${blocks.size}", blocks.size == 1)
            val block = blocks.single()
            assertTrue("自检失败（$label）：item 体内的调用行 $callLine 应被圈进块（块=$block）", callLine in block)
            assertTrue("自检失败（$label）：块外的调用行 $outsideLine 不应被圈进块", outsideLine !in block)
        }
        // 无 count 的 items 不圈（数据驱动列表不在本规则范围内 —— W18 规则精确到缺陷）。
        val noCount = listOf(
            "            items(items = list) {",
            "                VideoListCardSkeleton()",
            "            }"
        )
        assertTrue(
            "自检失败：无 count= 的 items 不应被圈",
            itemsCountBlocks(noCount).isEmpty()
        )
        // 定义体白名单：单卡骨架定义体内的 provider 调用不算容器级（防"自带 provider"混充）。
        val defBody = listOf(
            "@Composable",
            "fun VideoListCardSkeleton(modifier: Modifier = Modifier) {",
            "    ProvideSkeletonShimmer {",
            "        SkeletonBlock()",
            "    }",
            "}"
        )
        val analysis = analyze(defBody)
        assertTrue(
            "自检失败：定义体内的 provider 调用应标记 insideSingleCardDefinition",
            analysis.providerSites.single().insideSingleCardDefinition
        )
    }

    /** 容器级判定（与 scanViolations 同判据，供自检复用）。 */
    private fun isContainerProviderSite(site: CallSite, analysis: FileAnalysis): Boolean =
        analysis.itemsBodies.none { site.line in it } && !site.insideSingleCardDefinition

    /**
     * FL-01 ①：AdaptiveMediaGrid 的骨架子树必须被容器级 `ProvideSkeletonShimmer`
     * 包裹，且包裹必须**条件化**（`enabled` 跟随首屏骨架分支）—— 否则要么 10 个
     * 占位 item 各起一条无限动画（缺陷本身），要么给常驻列表留一条永不停止的
     * 无限动画（待机空转，判据纪律 P2：不进 jank 指标的浪费同样是缺陷）。
     */
    @Test
    fun adaptiveMediaGridSkeletonSubtreeHasContainerProvider() {
        val lines = mainSources
            .firstOrNull { it.path.endsWith("component${File.separator}AdaptiveMediaGrid.kt") }
            ?.readLines()
            ?: throw AssertionError("找不到 AdaptiveMediaGrid.kt —— 此时断言无意义。")
        val providerAt = lines.indexOfFirst { it.trimStart().startsWith("ProvideSkeletonShimmer(enabled =") }
        val gridAt = lines.indexOfFirst { it.contains("LazyVerticalStaggeredGrid(") }
        assertTrue(
            "AdaptiveMediaGrid 必须用 ProvideSkeletonShimmer(enabled = …) 包裹骨架子树" +
                "（FL-01：首屏 10 个占位 item 共用一个呼吸时钟）",
            providerAt >= 0
        )
        assertTrue(
            "ProvideSkeletonShimmer 必须在 LazyVerticalStaggeredGrid 外层（provider@$providerAt grid@$gridAt）" +
                "—— 包在 item 体内等于每项各起一条。",
            providerAt in 0 until gridAt
        )
        val condAt = lines.indexOfFirst { it.contains("val showFirstLoadSkeleton = items.isEmpty() && isRefreshing") }
        assertTrue(
            "包裹必须条件化（enabled 跟随首屏骨架分支），否则常驻列表会永挂一条无限动画。",
            condAt in 0 until providerAt
        )
    }

    // ── 扫描器 ─────────────────────────────────────────────────────────────

    private fun isCodeLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return !(trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
            trimmed.startsWith("import "))
    }

    /** 返回「relPath -> 违规行号列表」。 */
    private fun scanViolations(): Map<String, List<Int>> {
        val result = mutableMapOf<String, List<Int>>()
        for (file in mainSources) {
            val rel = file.relativeTo(repoRoot).path.replace('\\', '/')
            val lines = file.readLines()
            val analysis = analyze(lines)
            val violating = analysis.skeletonCallSites.filter { site ->
                analysis.itemsBodies.any { site.line in it } && !site.insideSingleCardDefinition
            }
            if (violating.isEmpty()) continue
            // 两种合法形态（AP-01(b) 的"容器级 provider"）：
            // a) 容器级 ProvideSkeletonShimmer（不在 items 体内、不在单卡定义体内）；
            // b) AP-15 提升形态 —— 屏幕层 rememberSkeletonBreathOrNull() 恰好 1 处
            //    （呼吸在骨架态才创建，item 体内注入同一实例，仍然一个时钟）。
            val hasContainer = analysis.providerSites.any { isContainerProviderSite(it, analysis) } ||
                lines.count { line -> isCodeLine(line) && hoistedBreathPattern.containsMatchIn(line) } == 1
            if (!hasContainer) {
                result[rel] = violating.map { it.line }
            }
        }
        return result
    }

    private data class CallSite(val line: Int, val insideSingleCardDefinition: Boolean)

    private class FileAnalysis {
        val skeletonCallSites = mutableListOf<CallSite>()
        val providerSites = mutableListOf<CallSite>()
        val itemsBodies = mutableListOf<IntRange>()
    }

    private fun analyze(lines: List<String>): FileAnalysis {
        val analysis = FileAnalysis()
        val singleCardDefBodies = mutableListOf<IntRange>()
        // 1) 单卡骨架定义体（含多行签名）：fun <name>( … ) { … } 花括号配平。
        for (i in lines.indices) {
            val line = lines[i]
            if (!isCodeLine(line)) continue
            val name = funNamePattern.find(line)?.groupValues?.get(1)
            if (name != null && isSingleCardSkeletonDefinition(name)) {
                singleCardDefBodies.add(i..braceBalancedEnd(lines, i))
            }
        }
        fun insideSingleCardDef(lineNo: Int): Boolean =
            singleCardDefBodies.any { lineNo in it }

        // 2) items(count=…) 的 item lambda 体（跨行：括号 → 尾随花括号两阶段）。
        analysis.itemsBodies.addAll(itemsCountBlocks(lines))

        // 3) 骨架调用点 / provider 调用点（定义行与定义体内 = 实现自身，不算调用方缺陷）。
        for ((index, line) in lines.withIndex()) {
            if (!isCodeLine(line)) continue
            val lineNo = index + 1
            val inDef = funDefLinePattern.containsMatchIn(line) || insideSingleCardDef(lineNo)
            for ((_, re) in skeletonCallPatterns) {
                if (re.containsMatchIn(line)) {
                    val site = CallSite(lineNo, inDef)
                    analysis.skeletonCallSites.add(site)
                    if (re.pattern.contains("ProvideSkeletonShimmer")) {
                        analysis.providerSites.add(site)
                    }
                }
            }
        }
        return analysis
    }

    /**
     * 圈出所有 `items(count = …)` 的 item lambda 体（行号区间，1-based）。
     * 两阶段：先括号配平吃掉参数表（须含 `count =`，多行参数跨行读），
     * 再吃尾随 lambda 的花括号配平体。`itemsIndexed(` 不会被 `\bitems\s*\(` 命中。
     */
    private fun itemsCountBlocks(lines: List<String>): List<IntRange> {
        val blocks = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!isCodeLine(line)) { i++; continue }
            val opener = itemsOpenerPattern.find(line)
            if (opener == null) { i++; continue }
            // 从 opener 的 `(` 之后起做括号配平（跨行）。
            // 注意：必须从 range.last + 1 开始、depth 预置 1 —— 若从词元 `items` 开始扫，
            // 词元会被 append 进 args，单行 `items(count = 2)` 就拼成 "itemscount"，
            // `\bcount` 因无词边界而失配（破坏性验证实测踩过：多行参数因中间有缩进
            // 空格侥幸命中，单行参数静默漏检）。
            var depth = 1
            val args = StringBuilder()
            var cursorLine = i
            var closeLine = -1
            var closeCol = -1
            outer@ while (cursorLine < lines.size) {
                val text = lines[cursorLine]
                var col = if (cursorLine == i) opener.range.last + 1 else 0
                while (col < text.length) {
                    when (text[col]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                closeLine = cursorLine
                                closeCol = col
                                break@outer
                            }
                        }
                        else -> args.append(text[col])
                    }
                    col++
                }
                cursorLine++
            }
            if (closeLine < 0) break // 括号不配平的残缺行，交由编译器报错
            if (!countParamPattern.containsMatchIn(args.toString())) {
                i = closeLine + 1
                continue
            }
            // 尾随 lambda：从 `)` 之后找下一个非空白字符，是 `{` 才有 item 体。
            var bodyStart = -1
            var j = closeLine
            while (j < lines.size && bodyStart < 0) {
                val text = lines[j]
                var col = if (j == closeLine) closeCol + 1 else 0
                var decided = false
                while (col < text.length && !decided) {
                    if (!text[col].isWhitespace()) {
                        if (text[col] == '{') bodyStart = j
                        decided = true
                    }
                    col++
                }
                if (!decided) j++
            }
            if (bodyStart >= 0) {
                val end = braceBalancedEnd(lines, bodyStart)
                blocks.add((bodyStart + 1)..(end + 1))
                i = end + 1
            } else {
                i = closeLine + 1
            }
        }
        return blocks
    }

    /**
     * A14（2026-10-13，上游 v0.3.6 同步）：评论区首屏骨架的**槽位数**与**微光门控**。
     *
     * ## 两条缺口（都已核对是本仓真实缺口，不是照搬上游）
     *
     * **A14 槽位数**：原 `INIT_SKELETON_COUNT = 4` 写死。骨架铺在**真实 `LazyColumn`**
     * 里就地替换（`animateItem`），铺不够时数据到达会把列表撑长 → 位移；铺多了屏外
     * 槽位不会被组合、无成本。上游判据：`ceil(视口高度 / 130dp).coerceIn(3, 8)`。
     *
     * **A15 微光门控**：`CommentCardSkeleton` 自带幂等 provider，但"没人提供时自己起
     * 一条无限动画" —— 4~8 个槽位就是 4~8 条 `rememberInfiniteTransition`，正是本类
     * 开头说的"调用点数不变、实例数失控"盲区。容器级 provider 把它收敛成 1 条。
     *
     * 纯函数本身的边界断言在 `:feature:comment` 自己的
     * `CommentSkeletonSlotPolicyTest`（本测试在 designsystem，模块依赖方向不允许反向引用）。
     */
    @Test
    fun commentSkeletonSlotCountIsDerivedAndShimmerIsContainerScoped() {
        val panelFile = mainSources
            .firstOrNull { it.path.endsWith("comment${File.separator}CommentPanel.kt") }
            ?: throw AssertionError("找不到 feature/comment 的 CommentPanel.kt —— 此时断言无意义。")
        val panel = panelFile.readLines()

        assertTrue(
            "A14：首屏骨架槽位数必须由 resolveCommentSkeletonSlotCount(...) 推导，" +
                "且 `INIT_SKELETON_COUNT` 这个写死常量不得回来（铺不够会让列表被撑长）。",
            panel.any { it.contains("resolveCommentSkeletonSlotCount(viewportHeightDp)") } &&
                panel.none { it.trimStart().startsWith("private const val INIT_SKELETON_COUNT") }
        )

        val providerAt = panel.indexOfFirst { it.trimStart().startsWith("ProvideSkeletonShimmer(enabled =") }
        val lazyAt = panel.indexOfFirst { it.contains("LazyColumn(") }
        assertTrue(
            "A15：CommentPanel 必须有容器级 `ProvideSkeletonShimmer(enabled = …)`" +
                "（把 N 个骨架槽位的无限动画收敛成 1 条共享时钟）。",
            providerAt >= 0
        )
        assertTrue(
            "A15：provider 必须在 LazyColumn **外层**（provider@$providerAt lazy@$lazyAt）" +
                "—— 包在 item 体内等于每项各起一条无限动画。",
            providerAt in 0 until lazyAt
        )
        assertTrue(
            "A15：门控条件必须是「本屏此刻真的有骨架在场」（首屏骨架 or 翻页骨架），" +
                "否则常驻列表会永挂一条无限动画（待机空转同样是缺陷）。" +
                "⚠️ 必须覆盖**两种**骨架态：只门首屏的话，翻页那 2 条骨架仍是各起一条（未收敛）。",
            panel.any { it.contains("ProvideSkeletonShimmer(enabled = hasSkeletonOnScreen)") } &&
                panel.any { it.contains("val hasSkeletonOnScreen = isInitLoading || uiState.loadingMore") }
        )
    }

    /** 从 [start]（含）起按花括号配平找到闭合行（多行签名在 depth=0 时继续读）。 */
    private fun braceBalancedEnd(lines: List<String>, start: Int): Int {
        var depth = 0
        var opened = false
        for (k in start until lines.size) {
            for (ch in lines[k]) {
                when (ch) {
                    '{' -> { depth++; opened = true }
                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) return k
        }
        return lines.size - 1
    }
}
