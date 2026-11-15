package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **含无缝锚点的卡片，祖先链上不许挂无条件的 `appElasticPress`**（第 34 轮 OPPO 动画修复的守卫）。
 *
 * ## 为什么必须有这一条
 * 用户第 34 轮反馈「OPPO 无缝动画出现 bug，尤其是排行界面的动画」。机制在
 * `HistoryItemCard.kt`（第 138-151 行）已经写全，这里只记结论：
 * - OPPO 无缝框架用 `View.getLocationInWindow()` / `getLocationOnScreen()` 取锚点矩形，
 *   那是**布局期**坐标；
 * - `appElasticPress` 的形变是 `Modifier.graphicsLayer`，**绘制期**变换 —— 改变像素落点，
 *   不改布局；
 * - 于是「按住卡片（正被放大 ~2%、被手指拖偏）→ 点开视频」时，框架按**屏幕上已经不存在的
 *   矩形**起飞，动画从错误的位置/大小开始。
 *
 * 修法不是删掉按压反馈，而是**整卡形变让位**：`appElasticPress(enabled = !seamless.active)`。
 * `seamless.active` 为真时锚点真的组合了、系统过渡接管；不支持无缝的设备（锚点不组合、
 * 走普通打开）仍保留按压反馈。
 *
 * ## 判据
 * 一个**组合函数**里同时出现：
 * - 任一无缝锚点标记：`SeamlessCardCoverAnchor` / `rememberSeamlessVideoCardState` /
 *   `SeamlessVideoCardState` / `rememberSeamlessAnchorState` / `SeamlessAnchor(` / `.tryOpen(`；
 * - 且出现 `.appElasticPress(`，且它的**整个实参表里没有 `enabled =`**；
 * 就是一处违规，报到 `文件:行`。
 *
 * `enabled = <任意表达式>` 的挂载一律放行（`!seamless.active` 是标准写法，但 `enabled = canConfirm`
 * 这类由业务态门控的挂载同样不是本 bug 的形状）。判断 `enabled` 时读的是**整个实参表**，
 * 所以 `enabled =` 写在续行里也能识别。
 *
 * ## 为什么按「函数」而不是按「文件」作用域
 * 任务书给的是"文件里同时出现即报错"的文件级口径，但本仓现实形态会把它打成假红：
 * `SearchCard.kt` 的 `SearchCard`（锚点宿主）已经门控，而**同一个文件**里的
 * `SearchAuthorCard` / `SearchFeedbackMenu` 还有两处裸挂载——它们与锚点不共祖先链。
 * 文件级扫描会把这个**已经修好的文件**判成违规，属于拿错判据。
 * 因此这里取"最近的外层 `fun` 块"作为作用域：锚点与裸挂载只有落在**同一个组合函数**里
 * 才可能构成"祖先链上挂了形变"的形状。
 *
 * ## 唯一的例外：**已清空**（第 34 轮补修后）
 * 原本 `HomeScreen` 顶栏的刷视频芯片（`HomeTopBar` 里 `SeamlessAnchor(storyAnchorState, …)`
 * 的祖先 `IconButton` 挂着裸 `appElasticPress(visualSize = …)`）是唯一例外，理由是它同时走
 * `anchorState = storyAnchorState` → `displayRectPx()` 那条 `BUNDLE_RECT` 补偿。
 *
 * **但那两条是不同层面的修法**：`displayRectPx()` 只修正**锚点矩形**的读数，
 * 不会消除祖先链上的形变本身。既然正确修法是「让形变让位」，就没有理由保留例外 ——
 * 芯片按钮已改为 `enabled = !storySeamlessActive`（判据与卡片完全一致），
 * [compensatedHosts] 随之清空，通用扫描现在覆盖它。
 *
 * ## 这套扫描能证明什么、不能证明什么（重要，别把它当全量验收）
 * 本类只看**字面量** `.appElasticPress(`。而原语 `BiliCardSurface.kt` 在可点分支内部
 * 挂 `appElasticPress(dragEnabled = …)`；排行页 / 首页 / 空间页的卡片锚点
 * 恰好放在 `BiliCardSurface` 的 `fullCardOverlay = { SeamlessAnchor(…) }` 里。
 *
 * 第 34 轮补修后这条路径已闭环：原语新增 `elasticPressEnabled` 形参，
 * 三个锚点宿主调用点都传 `!<seamless 判据>`，由
 * [cardSurfacePrimitiveCanYieldToSeamlessAnchor] 单独钉住（含"形参真的参与判据"这一条，
 * 防"接了线却没通电"）。所以现在：
 * - **绿** = 没有裸挂载直接落在含锚点的组合函数里 **且** 原语让位开关在位、调用点传了它；
 * - 仍**不能**证明"任意深度远祖先链"干净（例如某个父级 Composable 把整卡包起来再挂形变）——
 *   那种形状本类看不到，需要在真机上用 `display=` / `layout=` 两条诊断日志比对。
 *
 * ## 防假绿
 * - `mainSources` 少于 200 个文件 → 路径推导失效，直接红；
 * - 锚点标记命中文件数 / 裸挂载文件数各有一条下限断言：标记或正则写坏时
 *   `offenders` 会恒为空，那叫失明，不叫通过；
 * - [scannerSelfCheck] 把正/负例喂给**同一套判据**，防止"判据写错导致永真"；
 * - [knownFixedCallSitesStayGated] 先 `assertTrue(…, file.isFile)` 再断言内容，
 *   文件被移动/改名时不会静默变成空转。
 *
 * ## 行号漂移
 * 本类不钉具体行号，只钉"某个组合函数里有没有裸挂载"。往文件里插行、搬函数都不会误红；
 * 唯一需要同步的是 [compensatedHosts]（见上）。
 */
class SeamlessAnchorElasticPressGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: File("").absoluteFile

    /** 全仓 main 源码（含 app/core/infra/feature 全部模块）。 */
    private val mainSources: List<File> by lazy {
        File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    // ── 判据 ─────────────────────────────────────────────────────────────────

    /**
     * 锚点标记。任一条出现，就说明这个函数在和无缝框架打交道。
     *
     * `SeamlessVideoCardState` 是 `rememberSeamlessVideoCardState` 的子串、`SeamlessAnchor(`
     * 与 `rememberSeamlessAnchorState` 也共享词根——这里按"任一命中"处理，重复不影响判据。
     */
    private val anchorMarkers: List<String> = listOf(
        "SeamlessCardCoverAnchor",
        "rememberSeamlessVideoCardState",
        "SeamlessVideoCardState",
        "rememberSeamlessAnchorState",
        "SeamlessAnchor(",
        ".tryOpen(",
    )

    /** 挂载调用（带前导点）。`appSearchElasticPress` 不是它的子串，不会被误算。 */
    private val mountCall = Regex("""\.appElasticPress\(""")

    /** 实参表里出现 `enabled =` 即视为门控挂载（正确写法）。 */
    private val enabledArgument = Regex("""\benabled\s*=""")

    /**
     * 函数声明行。用来：(1) 跳过 `fun SeamlessAnchor(…)` 这类**声明**；
     * (2) 找出每个组合函数的作用域。
     *
     * 形如 `private fun` / `internal suspend fun` / `override fun` 都能命中；
     * `@Composable` 在本仓总在独立一行，不会干扰。
     */
    private val functionDeclaration = Regex("""^\s*(?:\w+\s+)*fun\s+""")

    /** 声明行标记：`fun Modifier.appElasticPress(` 的主语不是挂载点。 */
    private val declarationMarker = "fun Modifier."

    /**
     * 已知的"锚点宿主 + 裸挂载"组合里**不是缺陷**的那一个（理由见类 KDoc 的"唯一的例外"）。
     *
     * 键是**函数签名前缀**而不是行号：搬行、在顶栏里加控件都不会让它失配。
     */
    private data class CompensatedHost(
        val pathSuffix: String,
        val functionSignature: String,
        /** 该例外成立所依赖的接线；接线没了，例外就失效。 */
        val compensationMarker: String,
    )

    private val compensatedHosts: List<CompensatedHost> = emptyList()

    // ── 主断言 ───────────────────────────────────────────────────────────────

    /**
     * 主断言：**含锚点的组合函数里不得出现无条件的 `.appElasticPress()`**。
     *
     * 这是本类唯一真正在守验收判据的测试；另外两条一条守扫描器、一条守三个已修调用点。
     */
    @Test
    fun anchorCardsDoNotUseUnconditionalElasticPress() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}）。" +
                "少于 200 个说明路径推导失效 —— 本类所有断言此刻都在假绿。",
            mainSources.size > 200
        )

        val anchorFiles = mainSources.filter { file -> file.readLines().any { isAnchorLine(it) } }
        assertTrue(
            "锚点标记只命中 ${anchorFiles.size} 个 main 文件（期望 >= 8）。" +
                "标记字符串被改名、或注释过滤写错时，下面的 offenders 会恒为空 —— " +
                "那不是通过，是失明。请先对齐判据，再谈结果。",
            anchorFiles.size >= 8
        )

        val bareMountFiles = mainSources.count { file -> hasUnconditionalMount(file) }
        assertTrue(
            "全仓只扫到 $bareMountFiles 个含无条件 `.appElasticPress()` 的文件（期望 >= 20）。" +
                "挂载正则写坏时 offenders 同样恒为空，必须先修扫描器。",
            bareMountFiles >= 20
        )

        assertCompensatedHostsAreStillJustified()

        val offenders = mainSources.flatMap { file ->
            val rel = file.relativeTo(repoRoot).path.replace('\\', '/')
            scanFile(file).map { index -> "$rel:${index + 1}" }
        }
        assertTrue(
            "以下位置在**含无缝锚点的同一个组合函数**里挂了无条件的 `.appElasticPress()`：\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\n\n" +
                "为什么不行：`appElasticPress` 的形变是 Modifier.graphicsLayer（绘制期），" +
                "而 OPPO 无缝框架用 View.getLocationInWindow/getLocationOnScreen 取锚点矩形（布局期）。" +
                "按住卡片点开时，框架按屏幕上已经不存在的矩形起飞，动画从错误位置/大小开始。\n" +
                "改法：`modifier.appElasticPress(enabled = !seamless.active)` —— " +
                "锚点真组合时整卡形变让位给系统过渡；`enabled = <表达式>` 的挂载一律放行。",
            offenders.isEmpty()
        )
    }

    // ── 扫描器自检 ───────────────────────────────────────────────────────────

    /**
     * 扫描器自检。理由同 `ElasticPressCoverageTest.scannerCountsRealMountsAndIgnoresProse`：
     * 本类判据同时依赖"锚点标记 / 挂载正则 / enabled 实参 / 注释过滤 / 函数作用域"五件事，
     * 任何一处写错都会让主断言在**恒为空**的读数上永真。
     *
     * 正例必须命中；带 `enabled` 的（含续行写法）、注释/import/声明行、以及
     * "裸挂载在另一个组合函数里"必须全部不命中。
     */
    @Test
    fun scannerSelfCheck() {
        assertEquals(
            "正例失败：同一函数里『锚点 + 裸挂载』必须恰好命中挂载那一行（第 4 行，0-based=3）。",
            listOf(3),
            scanLines(
                listOf(
                    "@Composable",
                    "fun BuggyCard() {",
                    "    Card(",
                    "        modifier = Modifier.fillMaxWidth().appElasticPress(),",
                    "    ) {",
                    "        SeamlessCardCoverAnchor(seamless, target, Modifier.matchParentSize())",
                    "    }",
                    "}",
                )
            )
        )

        val gated = listOf(
            "@Composable",
            "fun FixedCard() {",
            "    Card(",
            "        modifier = Modifier.fillMaxWidth().appElasticPress(enabled = !seamless.active),",
            "    ) {",
            "        SeamlessCardCoverAnchor(seamless, target, Modifier.matchParentSize())",
            "    }",
            "}",
        )
        assertTrue(
            "带 `enabled =` 门控的挂载是正确写法，必须放行 —— 否则守卫会逼人删掉门控。",
            scanLines(gated).isEmpty()
        )

        val multilineGated = listOf(
            "fun FixedCard() {",
            "    Card(",
            "        modifier = Modifier",
            "            .appElasticPress(",
            "                enabled = !seamless.active,",
            "            ),",
            "    ) {",
            "        SeamlessAnchor(anchorState, Modifier.matchParentSize())",
            "    }",
            "}",
        )
        assertTrue(
            "`enabled =` 写在续行里也必须被识别：判据读的是整个实参表，不是挂载那一行。",
            scanLines(multilineGated).isEmpty()
        )

        val multilineBare = listOf(
            "fun BuggyChip() {",
            "    IconButton(",
            "        modifier = Modifier.appElasticPress(",
            "            visualSize = 36,",
            "        )",
            "    ) {",
            "        SeamlessAnchor(anchorState, Modifier.matchParentSize())",
            "    }",
            "}",
        )
        assertEquals(
            "多行实参、没有 `enabled` 的挂载必须命中（第 3 行，0-based=2）。",
            listOf(2),
            scanLines(multilineBare)
        )

        val proseOnly = listOf(
            "import com.naaammme.bbspace.core.designsystem.motion.appElasticPress",
            "fun BuggyCard() {",
            "    // 旧写法：.appElasticPress() —— 注释不是挂载",
            "     * 同上，KDoc 里的 `.appElasticPress()` 也不算",
            "    SeamlessCardCoverAnchor(seamless, target, Modifier.matchParentSize())",
            "}",
        )
        assertTrue(
            "注释行 / import 行不是挂载，必须排除（本仓解释『为什么不能挂』的注释里必然出现它）。",
            scanLines(proseOnly).isEmpty()
        )

        val declarationLine = listOf(
            "fun Modifier.appElasticPress(enabled: Boolean = true): Modifier = composed {",
            "    this",
            "}",
            "fun BuggyCard() {",
            "    SeamlessCardCoverAnchor(seamless, target, Modifier.matchParentSize())",
            "}",
        )
        assertTrue(
            "`fun Modifier.appElasticPress(` 是声明不是挂载，必须排除 —— " +
                "否则定义文件自己就会红，且任何新增 `fun Modifier.xxxElasticPress` 都会让判据虚高。",
            scanLines(declarationLine).isEmpty()
        )

        val siblingFunctions = listOf(
            "fun AuthorCard() {",
            "    Card(modifier = Modifier.appElasticPress())",
            "}",
            "fun VideoCard() {",
            "    SeamlessCardCoverAnchor(seamless, target, Modifier.matchParentSize())",
            "}",
        )
        assertFalse(
            "裸挂载在**另一个**组合函数里不应命中。这正是本仓 SearchCard.kt 的现实形态：" +
                "作者卡裸挂、视频卡（锚点宿主）门控 —— 按文件扫描会把已修文件判红。",
            scanLines(siblingFunctions).isNotEmpty()
        )
    }

    // ── 已修调用点：门控不许被回退 ────────────────────────────────────────────

    /**
     * 三个已修调用点必须**仍然**是门控形态。
     *
     * 与主断言的差别：主断言扫全仓、但"某处挂载被删干净"同样能过；这里钉住
     * **指定文件里确实还存在 `enabled = !seamless.active`**，防止修复被整体回退到
     * "删掉挂载也算过关"的假绿。
     *
     * 每条都先 `assertTrue(…, file.isFile)`：文件被移动/改名时断言必须有对象，否则空转。
     */
    @Test
    fun knownFixedCallSitesStayGated() {
        val fixed = listOf(
            "feature/history/src/main/java/com/naaammme/bbspace/feature/history/component/HistoryItemCard.kt",
            "feature/search/src/main/java/com/naaammme/bbspace/feature/search/result/SearchCard.kt",
            "feature/history/src/main/java/com/naaammme/bbspace/feature/history/watchlater/WatchLaterScreen.kt",
        )
        for (suffix in fixed) {
            val file = mainSources.firstOrNull { it.path.replace('\\', '/').endsWith(suffix) }
            assertTrue(
                "找不到已修文件 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— " +
                    "本条断言此刻没有对象，等于假绿。若是改名/移动，请同步更新本清单。",
                file != null && file.isFile
            )
            val text = file!!.readText()
            assertTrue(
                "$suffix 里的锚点宿主卡必须保留 `enabled = !seamless.active` 门控。" +
                    "门控被删 = 按住卡片点开时 OPPO 框架又按布局期矩形起飞（第 34 轮 bug 回归）。",
                text.contains("enabled = !seamless.active")
            )
            assertTrue(
                "$suffix 必须仍然真的含无缝锚点标记 —— 否则这里的门控断言只是空转，" +
                    "那张卡已经不再是锚点宿主，本清单也该删。",
                anchorMarkers.any { text.contains(it) }
            )
            val stillUnguarded = scanFile(file)
            assertFalse(
                "$suffix 的锚点宿主函数里不应再有裸 `.appElasticPress()` 挂载：" +
                    stillUnguarded.joinToString { "第 ${it + 1} 行" },
                stillUnguarded.isNotEmpty()
            )
        }
    }

    // ── 原语层：BiliCardSurface 必须能被门控（第 34 轮补，堵上最大盲区）──────

    /**
     * `BiliCardSurface` 是首页卡 / 排行卡 / 空间页卡的**共同容器**，而这三类卡的
     * OPPO 锚点恰好放在它的 `fullCardOverlay = { SeamlessAnchor(…) }` 里。
     *
     * 本类其余断言只扫**字面量** `.appElasticPress(`，看不到原语内部的挂载 ——
     * 所以「原语自身必须提供让位开关」这条要单独钉住，否则整条验收判据在原语这一层是空的：
     * 调用点全都写对了，卡片照样从错误矩形起飞。
     *
     * 三条一起看才闭环：
     * 1. 原语**有** `elasticPressEnabled` 形参；
     * 2. 它真的参与了挂载判据（`&& elasticPressEnabled`），不是"接了但没用"；
     * 3. 三个已知锚点宿主调用点都传了 `!…active` / `!canSeamless`。
     */
    @Test
    fun cardSurfacePrimitiveCanYieldToSeamlessAnchor() {
        val primitive = mainSources.firstOrNull {
            it.path.replace('\\', '/').endsWith(
                "core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/component/BiliCardSurface.kt"
            )
        }
        assertTrue(
            "找不到 BiliCardSurface.kt —— 本断言此刻没有对象，等于假绿。",
            primitive != null && primitive.isFile
        )
        val code = primitive!!.readLines().map { if (isCommentOrImport(it)) "" else it }
        val text = code.joinToString("\n")
        assertTrue(
            "`BiliCardSurface` 必须提供 `elasticPressEnabled: Boolean = true` 形参 —— " +
                "锚点宿主卡片靠它让位给系统过渡。没有这个开关时，排行/首页/空间卡的锚点" +
                "永远被整卡形变扭曲（本 bug 的原始形状）。",
            Regex("""elasticPressEnabled\s*:\s*Boolean\s*=\s*true""").containsMatchIn(text)
        )
        assertTrue(
            "`elasticPressEnabled` 必须真的参与挂载判据（`&& elasticPressEnabled`）。" +
                "只声明不使用 = 接了线却没通电，调用点传 false 也不会有任何效果。",
            Regex("""&&\s*elasticPressEnabled""").containsMatchIn(text)
        )
        // 三个锚点宿主调用点：把 fullCardOverlay 当锚点槽位用的那几处必须传门控。
        val hosts = listOf(
            "feature/rank/src/main/java/com/naaammme/bbspace/feature/rank/RankScreen.kt",
            "feature/home/src/main/java/com/naaammme/bbspace/feature/home/video/HomeVideoPage.kt",
            "feature/space/src/main/java/com/naaammme/bbspace/feature/space/archive/SpaceArchiveSection.kt",
        )
        for (suffix in hosts) {
            val file = mainSources.firstOrNull { it.path.replace('\\', '/').endsWith(suffix) }
            assertTrue("找不到锚点宿主调用点 `$suffix` —— 本条断言没有对象。", file != null && file.isFile)
            val hostText = file!!.readLines().map { if (isCommentOrImport(it)) "" else it }.joinToString("\n")
            assertTrue(
                "$suffix 用 `fullCardOverlay` 装了无缝锚点，因此必须给 BiliCardSurface 传 " +
                    "`elasticPressEnabled = !<seamless 判据>`，否则整卡形变会扭曲锚点矩形。",
                Regex("""elasticPressEnabled\s*=\s*!\w""").containsMatchIn(hostText)
            )
        }
    }

    // ── 扫描器实现 ───────────────────────────────────────────────────────────

    /** 一行是否整行都是注释/import（与 `ElasticPressCoverageTest` 同款跳过规则）。 */
    private fun isCommentOrImport(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") || trimmed.startsWith("*") ||
            trimmed.startsWith("/*") || trimmed.startsWith("import ")
    }

    /** 是不是函数声明行（不是挂载、也不是锚点使用）。 */
    private fun isFunctionDeclaration(line: String): Boolean =
        !isCommentOrImport(line) && functionDeclaration.containsMatchIn(line)

    /** 是不是"这个函数在和无缝框架打交道"的标记行（声明行不算使用）。 */
    private fun isAnchorLine(line: String): Boolean {
        if (isCommentOrImport(line) || isFunctionDeclaration(line)) return false
        return anchorMarkers.any { line.contains(it) }
    }

    /** 行内出现 `.appElasticPress(`，且不是声明行。是否门控由 [isUnconditionalMount] 判断。 */
    private fun isMountLine(line: String): Boolean =
        !isCommentOrImport(line) && !line.contains(declarationMarker) &&
            mountCall.containsMatchIn(line)

    /** 挂载调用的开括号列号（正则最后一位就是 `(`）。 */
    private fun mountOpenParen(line: String): Int =
        mountCall.find(line)?.range?.last ?: -1

    /**
     * 从开括号起取整个实参表（按圆括号配平，可跨行）。
     *
     * 必须取整个实参表而不是只看挂载那一行：`enabled =` 完全可能写在续行
     * （本仓 `VideoDetailSheetCoinFav` 等处已有这种多行形态）。
     */
    private fun argumentText(lines: List<String>, startLine: Int, openParenCol: Int): String {
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (i in startLine until lines.size) {
            val line = lines[i]
            var c = if (i == startLine) openParenCol else 0
            while (c < line.length) {
                val ch = line[c]
                out.append(ch)
                when (ch) {
                    '(' -> {
                        depth++
                        opened = true
                    }

                    ')' -> {
                        depth--
                        if (opened && depth == 0) return out.toString()
                    }
                }
                c++
            }
            out.append('\n')
        }
        return out.toString()
    }

    /** 无 `enabled =` 的挂载：本类的违规主体。 */
    private fun isUnconditionalMount(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (!isMountLine(line)) return false
        val openParen = mountOpenParen(line)
        if (openParen < 0) return false
        return !enabledArgument.containsMatchIn(argumentText(lines, index, openParen))
    }

    /** 一个函数块的行号区间（含签名与收尾 `}`）。 */
    private data class FunBlock(val start: Int, val end: Int) {
        operator fun contains(index: Int): Boolean = index in start..end
    }

    /**
     * 找出所有函数块。
     *
     * 先按圆括号配平跳过参数列表，出列表后的第一个 `{` 才是函数体开头 ——
     * 直接数花括号会把参数默认值里的 lambda（`= {}`）当成块体边界，
     * 那是 `VideoLogicGuardTest.braceBalancedAt` 记录过的一次真实假红。
     * 表达式体（没有块体）只占签名那一行。
     */
    private fun functionBlocks(lines: List<String>): List<FunBlock> =
        lines.indices.mapNotNull { index ->
            if (!isFunctionDeclaration(lines[index])) null
            else FunBlock(index, bodyEnd(lines, index))
        }

    private fun bodyEnd(lines: List<String>, start: Int): Int {
        var paren = 0
        var openLine = -1
        var openCol = -1
        outer@ for (i in start until lines.size) {
            val line = lines[i]
            var c = if (i == start) line.indexOf('(').coerceAtLeast(0) else 0
            while (c < line.length) {
                when (line[c]) {
                    '(' -> paren++
                    ')' -> paren--
                    '{' -> if (paren <= 0) {
                        openLine = i
                        openCol = c
                        break@outer
                    }
                }
                c++
            }
        }
        if (openLine < 0) return start
        var depth = 0
        for (i in openLine until lines.size) {
            val from = if (i == openLine) openCol else 0
            for (c in from until lines[i].length) {
                when (lines[i][c]) {
                    '{' -> depth++
                    '}' -> depth--
                }
            }
            if (depth <= 0) return i
        }
        return lines.lastIndex
    }

    /** 行号所在的最内层函数块；顶层代码返回 null。 */
    private fun enclosingBlock(index: Int, blocks: List<FunBlock>): FunBlock? =
        blocks.filter { index in it }.maxByOrNull { it.start }

    /**
     * 扫描一份源码，返回违规行的 0-based 行号。
     *
     * 先把注释/import 行整体置空再分析：本仓 KDoc 里大量出现 `{ … }` 代码示例，
     * 保留它们会让花括号配平失准、函数作用域串到下一个函数，进而把无关挂载算进来。
     */
    private fun scanLines(lines: List<String>): List<Int> {
        val code = lines.map { if (isCommentOrImport(it)) "" else it }
        val blocks = functionBlocks(code)
        val anchorOwners = code.indices
            .filter { isAnchorLine(code[it]) }
            .map { enclosingBlock(it, blocks)?.start ?: -1 }
            .toSet()
        if (anchorOwners.isEmpty()) return emptyList()
        return code.indices.filter { index ->
            if (!isUnconditionalMount(code, index)) return@filter false
            val owner = enclosingBlock(index, blocks)?.start ?: -1
            owner in anchorOwners
        }
    }

    /**
     * 扫描单个文件，返回违规行的 0-based 行号。
     *
     * 第 34 轮补修后**没有例外清单**：原 HomeTopBar 那颗芯片已按同一判据门控
     * （`enabled = !storySeamlessActive`），`BiliCardSurface` 也补了让位开关。
     * 所以这里直接返回扫描结果 —— 不需要"扣例外"这一步，也就不需要维护一份
     * 会腐烂的豁免名单。
     */
    private fun scanFile(file: File): List<Int> = scanLines(file.readLines())

    private fun hasUnconditionalMount(file: File): Boolean {
        val code = file.readLines().map { if (isCommentOrImport(it)) "" else it }
        return code.indices.any { isUnconditionalMount(code, it) }
    }

    // ── 例外的"保鲜" ─────────────────────────────────────────────────────────
    //
    // 第 34 轮补修后 [compensatedHosts] 已清空（HomeTopBar 那颗芯片已按同一判据门控），
    // 所以下面两个辅助函数目前没有调用者。**刻意保留**：
    // · 它们是"将来若真需要例外时"的唯一入口，删掉会让下一个人重新发明一遍；
    // · 保留空清单 + 这套保鲜逻辑，等于把"例外必须有到期判据"这条纪律留在代码里。
    // 一旦 `compensatedHosts` 重新非空，主断言里的 `assertCompensatedHostsAreStillJustified()`
    // 会自动开始校验，无需再改这里。

    /**
     * 例外必须同时满足三件事，否则本类会失去意义：
     * 1. 文件还在（路径漂移 → 主扫描会漏掉它，或例外永远匹配不到）；
     * 2. 补偿接线还在（`anchorState = …` 传给 `tryOpen`，走 `BUNDLE_RECT` 真实显示矩形）；
     * 3. 该函数里**仍然有**裸挂载（说明例外还有存在必要）。
     *
     * 第 3 条是刻意设计的：等哪天该处也改成 `enabled = !seamless.active`，
     * 这条会红并提示删除例外 —— 不让"例外"变成永久的盲区。
     */
    private fun assertCompensatedHostsAreStillJustified() {
        for (host in compensatedHosts) {
            val file = mainSources.firstOrNull { it.path.replace('\\', '/').endsWith(host.pathSuffix) }
            assertTrue(
                "例外清单里的文件不存在：${host.pathSuffix}。" +
                    "路径漂移后这个例外匹配不到任何函数，等于给了一份失效的豁免。",
                file != null && file.isFile
            )
            val text = file!!.readText()
            assertTrue(
                "${host.pathSuffix} 里找不到补偿接线 `" + host.compensationMarker + "`。" +
                    "该例外成立的前提是锚点宿主把 SeamlessAnchorState 传给 tryOpen，" +
                    "由 displayRectPx() 的**真实显示矩形**充当 BUNDLE_RECT（而不是布局矩形）。" +
                    "接线没了就必须改成 `enabled = !seamless.active`，不许留着例外。",
                text.contains(host.compensationMarker)
            )
            assertTrue(
                "${host.pathSuffix} 里已经找不到 `SeamlessAnchor(` —— 例外指向的锚点没了，" +
                    "请同步从 compensatedHosts 删除这一项，让通用扫描接管。",
                text.contains("SeamlessAnchor(")
            )
            assertTrue(
                "例外已无必要：`${host.functionSignature}` 里已经没有裸 `.appElasticPress()` 了。" +
                    "说明它已被门控/改写 —— 请从 compensatedHosts 删除这一项，" +
                    "让主断言覆盖它（删例外是收紧，不是放宽）。",
                hasRawViolationInFunction(file, host.functionSignature)
            )
        }
    }

    /** 某函数内是否还有**未扣例外**的违规（用于验证例外仍然必要）。 */
    private fun hasRawViolationInFunction(file: File, signaturePrefix: String): Boolean {
        val lines = file.readLines()
        val code = lines.map { if (isCommentOrImport(it)) "" else it }
        val blocks = functionBlocks(code)
        return scanLines(lines).any { index ->
            val block = enclosingBlock(index, blocks) ?: return@any false
            lines[block.start].trimStart().startsWith(signaturePrefix)
        }
    }
}
