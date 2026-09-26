package com.naaammme.bbspace.feature.video

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本轮 8 条逻辑缺陷修复的结构守卫（任务书 §2-T2 要求"每条修完补一条能咬人的测试"）。
 *
 * ## 为什么是结构断言而不是行为测试
 *
 * 这 8 条全部牵涉 Compose 组合生命周期或 Android 组件（Activity 方向、SharedPreferences、
 * AnimatedVisibility 的 dispose），在 JVM 上跑不起来；把它们做成 Robolectric 测试
 * 需要的脚手架比被修的代码还多。所以这里断言的是**写法层面的不变式**：
 * 每条都是"这个修复之所以成立，是因为 X 写在 Y 里面"，X 或 Y 被人改回去就会红。
 *
 * ## 两条防假绿（照抄 FrameBudgetLintTest）
 * 1. 每个断言先确认目标文件真的读到了（本类大量断言是"某写法必须存在"，
 *    文件读不到时这些会直接失败，不会假绿；但"必须不存在"那一类会假绿，所以逐个兜底）。
 * 2. 扫描时跳过注释行与 import 行 —— 本仓库的修复注释里必然出现
 *    `runCatching`、`coinBalance()`、`seamlessIdsKey` 这些词。
 */
class VideoLogicGuardTest {

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

    /** 防假绿：扫不到源码时下面一半断言会失去意义。 */
    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效。",
            mainSources.size > 100
        )
    }

    // ── D2：closeSheet 的导航不能被协程取消吞掉 ────────────────────────────────

    /**
     * `afterClose`（真正的导航）必须在 `NonCancellable` 里执行。
     *
     * 修复前的形状是 `scope.launch { sheetState.hide(); sheet = null; afterClose() }`：
     * hide() 是挂起点、scope 是 rememberCoroutineScope()，宿主中途离开组合就会取消
     * 这个协程，于是导航两行**根本不跑** —— 用户看到"点了分P没反应"，且无日志。
     */
    @Test
    fun closeSheetNavigatesInsideNonCancellable() {
        val lines = codeOf("detail${FS}VideoDetailPage.kt")
        val body = blockBody(lines, "fun closeSheet(")
        assertTrue(
            "closeSheet 必须把 `sheet = null` 与 afterClose 放进 withContext(NonCancellable)，" +
                "否则收起动画期间宿主离开组合会静默丢掉一次导航。",
            body.any { it.contains("withContext(NonCancellable)") } &&
                body.any { it.contains("afterClose?.invoke()") }
        )
        val guardAt = body.indexOfFirst { it.contains("withContext(NonCancellable)") }
        val navAt = body.indexOfFirst { it.contains("afterClose?.invoke()") }
        assertTrue(
            "afterClose 必须写在 NonCancellable 块**内部**（当前顺序不对，等于还在被取消的上下文里导航）。",
            navAt > guardAt && guardAt >= 0
        )
        assertTrue(
            "hide() 要留在 try 里、让收尾走 finally。",
            body.any { it.contains("sheetState.hide()") } &&
                body.any { it.trimStart().startsWith("} finally {") }
        )
    }

    // ── D3：SHOW_OVERLAY 不再是隐式 fall-through ──────────────────────────────

    /**
     * `SbSkipType.SHOW_OVERLAY` 必须有显式分支。
     *
     * 它原本靠 `else -> Unit` 兜住，于是"仅显示"与"禁用"行为完全等价。
     * 本轮只把它**显式化并写清现状**（进度条标记由 sbMarks 独立承担、与 skipType 无关），
     * 没有改行为 —— 因为"它和 DISABLED 该有什么差别"是产品定义问题。
     * 这条断言守的是"别又变回隐式 fall-through"。
     */
    @Test
    fun sponsorBlockSkipTypesAreAllExplicit() {
        val lines = codeOf("VideoViewModel.kt")
        assertTrue(
            "VideoViewModel 的 skipType when 必须有显式的 SbSkipType.SHOW_OVERLAY 分支" +
                "（含它为什么不跳的说明），不能再靠 else 兜。",
            countMatching(lines, """SbSkipType\.SHOW_OVERLAY\s*->""") == 1
        )
    }

    // ── D4：未登录不得弹出一个空收藏夹 ────────────────────────────────────────

    @Test
    fun favSheetOnlyOpensWhenItCanLoad() {
        val vm = codeOf("VideoViewModel.kt")
        assertTrue(
            "openFavSheet 必须返回 Boolean，让调用方知道有没有真的开始加载。",
            countMatching(vm, """fun\s+openFavSheet\(\)\s*:\s*Boolean""") == 1
        )
        val page = codeOf("detail${FS}VideoDetailPage.kt")
        assertTrue(
            "VideoDetailPage 必须按 openFavSheet 的返回值决定是否弹面板。" +
                "无条件 `openFavSheet(); sheet = DetailSheet.Fav` 会让未登录用户" +
                "看到一个\"暂无收藏夹，新建一个吧\"的空面板盖在\"请先登录\"提示上。",
            countMatching(page, """if\s*\(\s*viewModel\.openFavSheet\(\)\s*\)""") == 1
        )
        // 反向：不许再出现"调用后紧接无条件置 Fav"的旧形状
        val offenders = mainSources.filter { countMatching(codeLines(it), """^\s*sheet\s*=\s*DetailSheet\.Fav\s*$""") > 0 }
        val unguarded = offenders.filter { file ->
            val l = codeLines(file)
            l.withIndex().any { (i, line) ->
                line.trimStart() == "sheet = DetailSheet.Fav" &&
                    (i == 0 || !l[i - 1].contains("openFavSheet()"))
            }
        }
        assertTrue(
            "以下文件把 sheet 置为 Fav 时没有紧跟在 openFavSheet() 的 if 之后：${unguarded.joinToString { it.name }}",
            unguarded.isEmpty()
        )
    }

    // ── D5：封面垫底必须按视频 ids 归键 ───────────────────────────────────────

    /**
     * `CoverSettleBackdrop` 的 `dismissed`/`alpha` 是无 key 的 remember，
     * 而页内切分P/选集/相关推荐不会重置它们 → 第 2 条视频的垫底是个透明层（黑帧）。
     * 修复是用 `key(videoIdsKey)` 包起来。用 ids 而非 coverUrl，是为了
     * "连续打开同源封面视频"这种 URL 相同但仍应重走垫底流程的情况。
     */
    @Test
    fun coverBackdropResetsPerVideo() {
        val lines = codeOf("VideoScreen.kt")
        assertTrue(
            "CoverSettleBackdrop 的调用点必须包在 key(videoIdsKey) 里，" +
                "否则它的 dismissed/alpha 会跨视频残留，第二条视频打开时露黑帧。",
            countMatching(lines, """key\(videoIdsKey\)""") == 1 &&
                countMatching(lines, """CoverSettleBackdrop\(""") >= 1
        )
        val keyAt = lines.indexOfFirst { it.contains("key(videoIdsKey)") }
        val callAt = lines.indexOfFirst { it.contains("CoverSettleBackdrop(") }
        assertTrue(
            "CoverSettleBackdrop 必须在 key(...) 之后（当前没被包进去，key 等于白写）。",
            keyAt in 0 until callAt
        )
        // 那个"算了却从没被引用"的死值必须已经消失
        assertTrue(
            "seamlessIdsKey 是上一轮留下的死值（算了从不被引用），已被 videoIdsKey 取代；" +
                "它回来就说明归键改动被回退了。",
            countMatching(lines, """seamlessIdsKey""") == 0
        )
    }

    // ── D6：dragMs 不得在 dispose 后残留 ─────────────────────────────────────

    @Test
    fun seekDragStateIsClearedOnDispose() {
        val lines = codeOf("player${FS}VideoPlayerPane.kt")
        val host = blockBody(lines, "private fun PlayerCtrlBarHost(")
        assertTrue(
            "PlayerCtrlBarHost 必须在离开组合时兜底清 dragMs。" +
                "dragMs 存在比控制条活得更久的外层作用域，而原本只有 onSeekDone 会清它；" +
                "手指还按在进度条上时控件自动隐藏，pointerInput 随组合消失，" +
                "进度条就永久停在拖动位置。",
            host.any { it.contains("DisposableEffect(") } &&
                host.any { it.contains("latestClearDragMs") }
        )
        assertTrue(
            "兜底清理必须经 rememberUpdatedState 取最新回调，否则 DisposableEffect(Unit) " +
                "捕获的是第一次组合的旧闭包。",
            countMatching(host, """rememberUpdatedState\(""") == 1
        )
    }

    // ── D7：余额不得在组合期读盘 ─────────────────────────────────────────────

    @Test
    fun coinBalanceIsNotReadFromComposition() {
        val offenders = mainSources.filter {
            countMatching(codeLines(it), """viewModel\.coinBalance\(\)""") > 0
        }
        assertTrue(
            "以下文件在组合期直接调用 coinBalance()（会同步读 SharedPreferences + 解析 JSON，" +
                "且每次重组都读）：${offenders.joinToString { it.name }}。" +
                "应改为 collect 那个 StateFlow。",
            offenders.isEmpty()
        )
        val vm = codeOf("VideoViewModel.kt")
        assertTrue(
            "VideoViewModel 必须把余额暴露成 StateFlow，并在投币成功后显式刷新。",
            countMatching(vm, """val\s+coinBalance\s*:\s*StateFlow<Double\?>""") == 1 &&
                countMatching(vm, """fun\s+coinBalance\(\)""") == 0 &&
                countMatching(vm, """refreshCoinBalance\(\)""") >= 2
        )
    }

    // ── D8：包挂起调用的 runCatching 已收口 ──────────────────────────────────

    /**
     * 三个文件里包挂起调用的 `runCatching` 必须换成 runCatchingCancellable。
     *
     * 刻意**保留**一处合法的 `runCatching`：`RemotePlaybackReporter` 里
     * `runCatching { JSONObject(data) }` 是纯解析、不可取消，换掉反而是噪音。
     * 所以断言写成"数量 + 那一处必须是 JSON 解析"，而不是简单归零。
     */
    @Test
    fun cancellationSwallowingIsClosed() {
        for (name in listOf("StreamPlaybackSessionImpl.kt", "VodDanmakuSession.kt")) {
            val lines = codeOf(name)
            assertTrue(
                "$name 里不该再有包挂起调用的 runCatching（应使用 runCatchingCancellable）",
                countMatching(lines, """\brunCatching\s*\{""") == 0
            )
        }
        val reporter = codeOf("RemotePlaybackReporter.kt")
        val plain = reporter.filter { Regex("""\brunCatching\s*\{""").containsMatchIn(it) }
        assertTrue(
            "RemotePlaybackReporter 允许且仅允许 1 处裸 runCatching（JSONObject 解析，非挂起）。" +
                "现在是 ${plain.size} 处：${plain.map { it.trim().take(60) }}",
            plain.size == 1 && plain[0].contains("JSONObject(")
        )
        // VodDanmakuSession 的手写取消特判应随替换一起消失
        val danmaku = codeOf("VodDanmakuSession.kt")
        assertTrue(
            "runCatchingCancellable 之后，`if (error is CancellationException) return@onFailure` " +
                "这类手写补丁就该删掉——留着说明换得不彻底，或者根本没换。",
            countMatching(danmaku, """error\s+is\s+CancellationException""") == 0
        )
    }

    /**
     * 同一约定扩展到 feature 层 ViewModel（本轮补齐）。
     *
     * 上一条只钉了 3 个播放文件，而 `runCatching` 吞取消在 feature 层同样成立：
     * `FavoriteViewModel.refresh()` 会 `contentJob?.cancel()` 后重发 —— 被取消的那次若吞掉
     * CancellationException 就走进 `onFailure`，把"取消"记成"加载失败"；`CommentViewModel`
     * 发评论链上取消/超时被记成"发送失败"，而请求可能已在服务端生效，用户重发即重复评论。
     *
     * 断言按**显式文件清单**（与本类其余断言一致，不用模糊 glob）：清单里的文件必须
     * ① 不再有裸 `runCatching {`；② 真的在用 runCatchingCancellable（防"删掉调用点
     * 也能过"的假绿）；③ try/catch 形态必须先重抛 CancellationException。
     */
    @Test
    fun cancellationSwallowingIsClosedInFeatureViewModels() {
        val converted = listOf(
            "CommentViewModel.kt",
            "DynamicViewModel.kt",
            "DynamicDetailViewModel.kt",
            "RankViewModel.kt",
            "ImConversationViewModel.kt",
            "PublishedRecordViewModel.kt",
            "SpaceNoteViewModel.kt",
            "HistorySearchViewModel.kt",
            "FavoriteViewModel.kt",
            "FavoriteFolderDetailViewModel.kt",
            "HomeViewModel.kt"
        )
        for (name in converted) {
            val lines = codeOf(name)
            assertTrue(
                "$name 里不该再有包挂起调用的 runCatching（应使用 runCatchingCancellable）",
                countMatching(lines, """\brunCatching\s*\{""") == 0
            )
            assertTrue(
                "$name 应真的在用 runCatchingCancellable（否则本断言只是删代码换来的假绿）",
                countMatching(lines, """\brunCatchingCancellable\s*\{""") > 0
            )
        }
        val home = codeOf("HomeViewModel.kt")
        assertTrue(
            "HomeViewModel 每个 catch (e: Exception) 都必须配套一个先重抛的 " +
                "catch (e: CancellationException)，否则取消会被记成加载失败。",
            countMatching(home, """catch\s*\(e:\s*CancellationException\)""") ==
                countMatching(home, """catch\s*\(e:\s*Exception\)""") &&
                countMatching(home, """catch\s*\(e:\s*Exception\)""") > 0
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private val FS: String get() = File.separator

    private fun codeOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。")
        return codeLines(file)
    }

    private fun codeLines(file: File): List<String> = file.readLines().map { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
            trimmed.startsWith("/*") || trimmed.startsWith("import ")
        ) {
            ""
        } else {
            line
        }
    }

    /** 取以 [signaturePrefix] 开头的函数体（含签名），按花括号配平定界。 */
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
                    '{' -> { depth++; opened = true }
                    '}' -> depth--
                }
            }
            // 必须等见过 `{` 才开始判结束：多行签名的函数（参数各占一行、
            // `) {` 在好几行之后才出现）在 depth 仍为 0 时就该继续往下读，
            // 否则函数体只会截到签名那两行，里面当然什么都找不到。
            if (opened && depth <= 0) break
        }
        return body
    }

    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { it.isNotBlank() && re.containsMatchIn(it) }
    }
}
