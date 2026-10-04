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
        for (name in listOf("StreamPlaybackSessionImpl.kt", "VodDanmakuSession.kt", "PortraitSwipePager.kt")) {
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

    // ── 接缝：整圈描边 + 纯色光晕 + 渐进模糊（2026-09-28 定稿·BiliPai 语言版）──

    /**
     * 接缝结构的写法不变式（用户两轮真机反馈后的定稿）：
     * 1. 描边核心色必须按内容底明暗分档（深色底→白 / 浅色底→灰）；
     * 2. 描边必须是**整圈圆角矩形轮廓**（drawRoundRect + Stroke，rim light）；
     * 3. 描边下方的光必须是 **BiliPai 上游语言的纯色渐晕**
     *    （topSolidProgressiveFade + 封面**原色** + 0.48/0.38 峰值）——
     *    试过并被用户否掉的两版：向白 lerp 的低峰值淡彩（"廉价"）、
     *    BlendMode.Screen 双层提亮（"太丑/灰雾"）。**不许再回退到 Screen 方案**；
     * 4. 光晕下方/同域必须有**渐进模糊**（seamProgressiveBlur，AGSL 自写，
     *    参数对齐 BiliPai 顶部渐进模糊）——"光从视频播放区洒在下方内容上"
     *    的光感主体；
     * 5. 光源必须来自 VideoCardCoverColorStore（封面代表色，防重缓存路径）。
     */
    @Test
    fun playerSeamUsesThemeStrokeAndCoverLightSpill() {
        val lines = codeOf("VideoScreen.kt")
        assertTrue(
            "描边色必须 = 下方光晕色（封面代表色，用户定稿「描边与光同一个颜色」），" +
                "深/浅色底分档（白/SeamStrokeGray）已废弃。",
            countMatching(lines, """val\s+seamStrokeBase\s*=\s*seamGlowColor""") == 1 &&
                countMatching(lines, """SeamStrokeGray""") == 0
        )
        assertTrue(
            "描边必须是整圈圆角轮廓（drawRoundRect + Stroke 走线），" +
                "不许回退成底边直线描边——直线到圆角处会断掉，盖不住播放带圆角。" +
                "且不许再垫底色（垫底矩形会盖住圆角月牙处透上来的光，" +
                "真机症状「光色没有在圆角处显示」）。",
            countMatching(lines, """drawRoundRect\(""") == 1 &&
                countMatching(lines, """style\s*=\s*Stroke\(""") == 1 &&
                countMatching(lines, """backingColor""") == 0
        )
        assertTrue(
            "接缝光必须是 BiliPai 上游 coverGlow 的纯色渐晕（topSolidProgressiveFade + " +
                "封面原色 + SEAM_GLOW_PEAK 分档），且不许回退到被否掉的 Screen 双层方案" +
                "（BlendMode.Screen / seamLightSpill 计数必须为 0）。",
            countMatching(lines, """topSolidProgressiveFade\(""") == 1 &&
                countMatching(lines, """surfaceColor\s*=\s*seamGlowColor""") == 1 &&
                countMatching(lines, """peakAlpha\s*=\s*seamGlowPeak""") == 1 &&
                countMatching(lines, """BlendMode\.Screen""") == 0 &&
                countMatching(lines, """seamLightSpill""") == 0
        )
        assertTrue(
            "接缝层必须是**独立背景层与光晕层、都上移一个圆角半径**：圆角月牙区" +
                "（播放带 clip 透明处）透出它们——光沿圆角轮廓显示、与弧线相切；" +
                "被否掉的 AGSL 邻域采样方案（seamProgressiveBlur）不得回退。",
            countMatching(lines, """seamGlowRadiusPx""") >= 3 &&
                countMatching(lines, """height\(detailBaseH \+ BiliRadius\.playerCompact\)""") == 1 &&
                countMatching(lines, """height\(seamGlowHeight \+ BiliRadius\.playerCompact\)""") == 1 &&
                countMatching(lines, """bandMorphHeightPx\(\) - seamGlowRadiusPx""") == 2 &&
                countMatching(lines, """seamProgressiveBlur""") == 0
        )
        run {
            // 图层序（用户定稿）：简介内容必须声明在光晕层**之后**（z 序在上）——
            // 光洒在背景上、文字清晰浮在光上，不遮挡内容。
            val glowLayerAt = lines.indexOfFirst {
                it.contains("height(seamGlowHeight + BiliRadius.playerCompact)")
            }
            val contentAboveGlow = glowLayerAt >= 0 && lines.withIndex().any { (index, line) ->
                index > glowLayerAt && line.contains("VideoDetailPage(")
            }
            assertTrue(
                "简介内容图层必须排在光晕层之后（在光晕之上）——用户定稿的图层序，" +
                    "光不能遮挡内容的显示效果。",
                contentAboveGlow
            )
        }
        assertTrue(
            "光源必须来自 VideoCardCoverColorStore（封面代表色），" +
                "光晕峰值必须按主题分档（BiliPai coverGlow 原值 0.48/0.38）。",
            countMatching(lines, """VideoCardCoverColorStore\.requestExtraction""") == 1 &&
                countMatching(lines, """SEAM_GLOW_PEAK_DARK""") >= 1 &&
                countMatching(
                    codeOf("VideoScreen.kt"),
                    """SEAM_GLOW_PEAK_DARK = 0\.48f"""
                ) == 1 &&
                countMatching(lines, """surfaceColor\s*=\s*Color\.Black""") == 0
        )
    }

    // ── 竖屏全屏上下滑（BiliPai 上游 PortraitVideoPager 的 bbspace 化）─────────

    /**
     * 上下滑功能的写法不变式：
     * 1. pager 只在全屏（fullOn）且进入会话时启用（swipeSessionActive）——
     *    紧凑态/平板分支不能出现 VerticalPager；
     * 2. **播放器唯一**：pager 内只有一处 VideoPlayerPane（当前播放的 bvid 所在页），
     *    其余页渲染封面——多份 PlayerView 会互抢 surface；
     * 3. 切换必须走 openSwipeTarget（替换栈顶），不许改回 openTarget（压栈语义
     *    会让返回键逐个回放刷过的视频）；
     * 4. 会话锚点与队列由 beginSwipeSession 在进入全屏时建立。
     */
    @Test
    fun portraitSwipePagerStaysSinglePlayerAndStackTopReplace() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        val screen = codeOf("VideoScreen.kt")
        val vm = codeOf("VideoViewModel.kt")
        assertTrue(
            "上下滑必须是 VerticalPager（foundation pager，页数随接力队列增长实现\"无限刷\"），" +
                "且 pager 内只允许一处 VideoPlayerPane（播放器唯一，其余页是封面）。",
            countMatching(pager, """VerticalPager\(""") == 1 &&
                countMatching(pager, """VideoPlayerPane\(""") == 1
        )
        assertTrue(
            "翻页监听必须经 requestedKey（SW-03 起为类型化 SwipeEntry.id，aid 优先 / bvid 兜底）" +
                "防目标震荡（直接比 currentId 会在取流完成前被快速往返翻页打转），" +
                "翻页与迟到结果对齐各走一处 onSwipeTo。",
            countMatching(pager, """requestedKey""") >= 3 &&
                countMatching(pager, """onSwipeTo\(entry\)""") == 2
        )
        assertTrue(
            "「哪一页归播放器」必须按**内容身份**判断（isCurrentVideo / SwipeId.matches），" +
                "不许拿 entry 与 currentId 比字符串——队列条目的旧字符串 key 是建队时算的" +
                "（relate 卡没有 bvid → `aid:…`），而 videoState.ids 在取流/详情返回后会被服务端" +
                "补齐的 bvid 整体覆盖，字符串会在「取流完成」的一瞬间翻转 → 播放器页落空、" +
                "翻页后新视频永不起播（真机阻塞问题的根因）。",
            countMatching(pager, """isCurrentVideo\(""") >= 3 &&
                countMatching(pager, """entry\.id == currentId""") == 0
        )
        assertTrue(
            "pager 必须从调用方拿到当前播放的 ids（currentBvid / currentAid）——" +
                "只传一个已经拼好的字符串，等于把「取流前算的 key」和「取流后算的 key」" +
                "又混成同一个东西，本 bug 会原样复发。",
            countMatching(pager, """currentBvid:\s*String\?""") == 1 &&
                countMatching(pager, """currentAid:\s*Long""") == 1 &&
                countMatching(screen, """currentAid\s*=\s*videoState\.ids\.aid""") == 1
        )
        assertTrue(
            "同条目短路也必须走类型化身份（SW-03：entry.id.matches(SwipeId.of(current.bvid, " +
                "current.aid))，aid 优先语义内置在 SwipeId.matches，与 isCurrentVideo 同一判据），" +
                "否则「同一个视频的 aid-only 目标 vs 已补齐 bvid 的栈顶」会被判成不同目标 → " +
                "白白从重开播一次。",
            countMatching(vm, """entry\.id\.matches\(SwipeId\.of\(current\.bvid, current\.aid\)\)""") == 1
        )
        assertTrue(
            "全屏分支必须用 swipeSessionActive + 锚点判据启用 pager；会话开启必须调 " +
                "beginSwipeSession；切换必须接 viewModel::openSwipeTarget。",
            countMatching(screen, """swipeSessionActive""") >= 2 &&
                countMatching(screen, """beginSwipeSession\(""") == 1 &&
                countMatching(screen, """viewModel::openSwipeTarget""") == 1 &&
                countMatching(screen, """PortraitSwipePager\(""") == 1
        )
        val controller = codeOf("SwipeSessionController.kt")
        assertTrue(
            "上下滑切换必须替换栈顶（dropLast(1) + target）而不是压栈，" +
                "且队列接力必须走 appendSwipeEntries（去重 + UP 主多样性）。\n" +
                "SW-02 起 merge 归口到 core:swipe 的 SwipeSessionController：VM 只留" +
                "beginSwipeSession 的种子队列一处理地（此处原来 >= 2 是因为接力/补货/" +
                "兜底三处都在 VM 里拼队列，现统一进控制器的 mergeRelates/seedArrived）。",
            countMatching(vm, """fun\s+openSwipeTarget\(entry:\s*SwipeEntry\)""") == 1 &&
                countMatching(vm, """dropLast\(1\)\s*\+\s*entry\.target""") == 1 &&
                countMatching(vm, """appendSwipeEntries\(""") == 1 &&
                countMatching(controller, """appendSwipeEntries\(""") >= 2
        )
        assertTrue(
            "会话状态必须收敛到 SwipeSessionController（单一真值源，SW-02）：\n" +
                "VM 不得再私设锚点/队列/尾态的 MutableStateFlow，也不得保留预取去重表\n" +
                "（挪进控制器后随 exit() 一并清空——历史 bug 的预防性清理）。",
            countMatching(vm, """private val swipeSession = SwipeSessionController\(\)""") == 1 &&
                countMatching(vm, """_swipeAnchor|_swipeQueue|_swipeTail|prefetchedKeys""") == 0 &&
                countMatching(
                    controller,
                    """fun exit\(\)"""
                ) == 1
        )
    }

    /**
     * SW-03：条目身份必须**类型化**，字符串键退场到"日志与去重 map"两个用途。
     *
     * ## 为什么要有这条守卫
     * C7 真机事故（能滑动但不播放）的根因就是"页身份"与"去重键"共用一个字符串，
     * 而字符串在取流完成的一瞬间翻转。`SwipeId` 类型化把两个语义在编译期分开；
     * 但只要 `val key: String` 还在，下一个改动就可能把它再写回页身份判断里——
     * 这条守卫让"改回去"直接红。
     */
    @Test
    fun swipeEntryIdentityIsTypedNotString() {
        val queue = codeOf("PortraitSwipeQueue.kt")
        assertTrue(
            "SwipeEntry 不得再有 val key: String（旧字符串身份键）：页身份一律走 " +
                "val id: SwipeId + isCurrentVideo（内部 id.matches(SwipeId.of(...))），" +
                "legacyKey() 只许用于日志与去重 map——删掉类型化身份或把字符串键加回来都会红。",
            countMatching(queue, """val key: String""") == 0 &&
                countMatching(queue, """val id: SwipeId""") == 1 &&
                countMatching(queue, """fun legacyKey\(\): String""") == 1 &&
                countMatching(queue, """id\.matches\(SwipeId\.of\(bvid, aid\)\)""") == 1
        )
    }

    /**
     * 会话内 back 优先级（用户反馈「看完评论顺手侧滑 → 直接退出到推荐页」）：
     * 面板开着只关面板、刚关过面板的宽限窗口内吞一次、其余才放行给宿主。
     */
    @Test
    fun swipeBackClosesSheetsBeforeLeavingSession() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        val policy = codeOf("SwipeBackPolicy.kt")
        assertTrue(
            "会话必须自己接管 back（含系统侧滑返回）：BackHandler 的 enabled 由纯判据决定，" +
                "面板开着时只关面板、宽限窗口内吞掉、其余放行给宿主（story 退出/全屏回缩）；" +
                "判据在 core:swipe 的 SwipeBackPolicy（单测覆盖）。",
            countMatching(pager, """BackHandler\(enabled = backAction != SwipeBackPolicy\.Action\.PassThrough\)""") == 1 &&
                countMatching(pager, """SwipeBackPolicy\.resolve\(""") == 1 &&
                countMatching(pager, """SwipeBackPolicy\.Action\.CloseSheet ->""") == 1 &&
                countMatching(pager, """showMoreSheet -> closeMoreSheet\(\)""") == 1 &&
                countMatching(pager, """showComments -> showComments = false""") == 1 &&
                countMatching(pager, """showFavSheet -> closeFavSheet\(\)""") == 1 &&
                countMatching(pager, """delay\(SwipeBackPolicy\.SHEET_CLOSE_GRACE_MS\)""") == 1 &&
                countMatching(policy, """const val SHEET_CLOSE_GRACE_MS = 400L""") == 1
        )
    }

    /**
     * SW-24「更多」面板与「不感兴趣」闭环的写法不变式：
     * 入口只从 rail 的 `onOpenMore` 来；不感兴趣只在 feed 源（`dislikeContext` 非空）出现；
     * 退队走纯策略（当前页被摘只前进一次）；撤回必须能落回原位置；提交成功后要补货。
     */
    @Test
    fun swipeMoreSheetWiresDislikeLoop() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        val pane = codeOf("player/VideoPlayerPane.kt")
        val vm = codeOf("VideoViewModel.kt")
        val policy = codeOf("SwipeDislikeRemovalPolicy.kt")
        assertTrue(
            "更多入口由 rail 的 onOpenMore 提供（宿主不接则整颗不出现）；" +
                "不感兴趣只在 feed 源出现（relate/story 无负反馈通道 → 不做假按钮）。",
            countMatching(pane, """onOpenMore: \(\(\) -> Unit\)\? = null""") == 1 &&
                countMatching(pane, """onClick = onOpenMore""") == 1 &&
                countMatching(pager, """canDislike = currentEntry\?\.dislikeContext != null""") == 1
        )
        assertTrue(
            "退队必须走 SwipeDislikeRemovalPolicy（摘当前页页号不变=前进一支、锚点不可摘）；" +
                "撤回插回原位置；提交成功后必须补货（队列不因负反馈变薄）。",
            countMatching(vm, """swipeSession\.removeQueueEntry\(entry, currentPage\)""") == 1 &&
                countMatching(vm, """swipeSession\.restoreQueueEntry\(index, entry\)""") == 1 &&
                countMatching(vm, """feedDislikeRepository\.dislike\(context, reason\)""") == 1 &&
                countMatching(vm, """feedDislikeRepository\.cancelDislike\(context\)""") == 1 &&
                countMatching(policy, """shiftCurrentPageDown = pageOfTarget < currentPage""") == 1
        )
    }

    /**
     * SW-24 的界面构成：不感兴趣/撤回/弹幕开关/复制链接/系统分享五项，
     * 且撤回入口只在"有可撤条目"时出现。
     */
    @Test
    fun swipeMoreSheetShowsOnlyRealEntries() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "「更多」面板的每一项都必须直连既有能力（举报/稍后再看/下载/画质不在会话内新造）：",
            countMatching(pager, """text = "不感兴趣"""") == 1 &&
                countMatching(pager, """text = "撤回不感兴趣"""") == 1 &&
                countMatching(pager, """text = "弹幕开关"""") == 1 &&
                countMatching(pager, """text = "复制链接"""") == 1 &&
                countMatching(pager, """text = "系统分享"""") == 1 &&
                // 2026-10-03（PT-06）：拉黑该 UP —— 直连既有 gRPC 拉黑能力（UpBlocker），
                // ownerMid > 0 才显示；二次确认后才对被拉黑账号实际生效（见 BlockedUpWiringTest）
                countMatching(pager, """text = "拉黑该 UP 主"""") == 1 &&
                countMatching(pager, """if \(canUndoDislike\) \{""") == 1 &&
                countMatching(pager, """举报""") == 0
        )
    }

    /**
     * SW-30 动效质感（粒子爱心 + 计数滚动）的写法不变式：
     * 数值来自纯策略（种子确定性）、读值只在 graphicsLayer、reduceMotion 必须退化。
     */
    @Test
    fun swipeAnimationQualityIsSeededAndDegrades() {
        val pane = codeOf("player/VideoPlayerPane.kt")
        val policy = codeOf("SwipeHeartBurstPolicy.kt")
        assertTrue(
            "双击爱心 = 种子粒子版（同一次点赞可复现、不同次不重复）：粒子/主心都由 " +
                "SwipeHeartBurstPolicy 给值，读值在 graphicsLayer；" +
                "reduceMotion（动画速度=关）不取粒子、主心不缩放上浮。",
            countMatching(pane, """SwipeHeartBurstPolicy\.particles\(""") == 1 &&
                countMatching(pane, """SwipeHeartBurstPolicy\.mainHeartScale\(""") == 1 &&
                countMatching(pane, """val useBurst = !anim\.reduceMotion""") == 1 &&
                countMatching(policy, """const val PARTICLE_COUNT""") == 1 &&
                countMatching(policy, """const val MAX_DISTANCE_FRACTION""") == 1
        )
        assertTrue(
            "计数滚动：计数变化时新值上浮淡入（Animatable + graphicsLayer），" +
                "reduceMotion 时直接换字（不做位移/淡入）。",
            countMatching(pane, """private fun SwipeRailCount\(""") == 1 &&
                countMatching(pane, """SwipeRailCount\(""") == 2 &&
                countMatching(pane, """roll\.animateTo\(1f, tween\(durationMillis = 260\)\)""") == 1 &&
                countMatching(
                    pane,
                    """translationY = \(1f - roll\.value\) \* BiliSpacing\.sm\.toPx\(\)"""
                ) == 1
        )
    }

    /**
     * SW-10 翻页动效的写法不变式：数值全部来自纯策略、读值只在 graphicsLayer
     * （绘制阶段读取 → 不触发重组）、越界阻尼走 nestedScroll 的未消费位移 + 松手回弹、
     * reduceMotion 时不接阻尼且策略退化。
     */
    @Test
    fun swipePageTransformIsPolicyDrivenAndDrawPhase() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        val policy = codeOf("SwipePageTransformPolicy.kt")
        assertTrue(
            "出页缩放/淡出必须由 SwipePageTransformPolicy 给值（幅度 ≤2% 与交接落定同量级）；" +
                "越界阻尼 = nestedScroll 未消费位移 + 策略折减 + 松手回弹（`animate` 挂起函数，" +
                "不占 animate*AsState 棘轮）；reduceMotion 时不接阻尼（策略内部退化）。",
            countMatching(pager, """SwipePageTransformPolicy\.transformFor\(""") == 1 &&
                countMatching(pager, """SwipePageTransformPolicy\.overScrollTranslation\(""") == 1 &&
                countMatching(pager, """NestedScrollConnection""") == 1 &&
                countMatching(pager, """if \(reduceMotion\) Modifier else Modifier\.nestedScroll""") == 1 &&
                countMatching(policy, """const val MAX_SCALE_DELTA""") == 1 &&
                countMatching(policy, """MIN_ALPHA""") >= 1
        )
    }

    // ── 刷视频模式（首页顶栏入口 → Story 全屏，BiliPai StoryScreen 同款）────────

    /**
     * 刷视频模式接线的写法不变式：
     * 1. 入口：HomeScreen 顶栏用 LocalStoryCardOpener（story 专用 route）做 OPPO
     *    无缝 morph（锚点 = 按钮上的 SeamlessAnchor，decorateAnchor=false 纯色 morph），
     *    失败回退 onEnterStoryMode（普通 startActivity，走统一窗口动画）；
     * 2. story intent：EXTRA_STORY_MODE 必须由 videoDetailIntent 写入；
     * 3. 会话：story 模式的锚点/队列由 bootstrapStorySession（story 流）负责，
     *    VideoScreen 的 beginSwipeSession 在 story 模式下必须跳过（两条数据链不竞争）；
     * 4. 无紧凑态：story 模式 isFull 初始即 true，返回键 = 关闭页面；
     * 5. 补货：pager 触发 loadMoreSwipeQueue（推荐流分页接力），与 VideoScreen 的
     *    onNeedMore 接线。
     */
    @Test
    fun homeStoryEntryWiring() {
        val home = codeOf("HomeScreen.kt")
        assertTrue(
            "顶栏入口必须用 LocalStoryCardOpener（story 专用 route）+ SeamlessAnchor 锚点" +
                "做无缝 morph，并保留 onEnterStoryMode 回退链路（截屏失败/不支持无缝）。",
            countMatching(home, """LocalStoryCardOpener\.current""") == 1 &&
                countMatching(home, """SeamlessAnchor\(storyAnchorState""") == 1 &&
                countMatching(home, """onEnterStoryMode\(target\)""") >= 2
        )
        assertTrue(
            "入口**必须有自己的可见芯片底**（用户反馈：「会突兀地出现一个边框」——" +
                "静息态没有底色时，框架只能用 BUNDLE_COLOR 画一块纯色，打开时凭空冒出、" +
                "返回时凭空消失）。形状与旁边「我的」头像同源（homeStoryChipShape / " +
                "homeProfileAvatarSize），底色走主题 token。",
            countMatching(home, """homeStoryChipShape""") >= 2 &&
                countMatching(home, """\.size\(homeProfileAvatarSize\)""") >= 2 &&
                countMatching(
                    home,
                    """background\(MaterialTheme\.colorScheme\.surfaceContainerHigh\)"""
                ) >= 1
        )
        assertTrue(
            "无缝 morph 的内容必须是**锚点真实像素**：点击这一帧先 captureCardBitmap，" +
                "再把它作为 cardBitmap 交给 tryOpen（与首页卡片同一时序）。" +
                "退回 decorateAnchor=false 的纯色兜底就又会看到「边框突兀出现/消失」。",
            countMatching(home, """captureCardBitmap\(anchorView\)""") == 1 &&
                countMatching(home, """cardBitmap = captured""") == 1 &&
                countMatching(home, """decorateAnchor = captured != null""") == 1
        )
        val intents = codeOf("navigation/VideoDetailIntents.kt")
        assertTrue(
            "story 标记必须经 EXTRA_STORY_MODE 由 videoDetailIntent 统一写入。",
            countMatching(intents, """EXTRA_STORY_MODE""") >= 2 &&
                countMatching(intents, """storyMode: Boolean = false""") == 1
        )
        val vm = codeOf("VideoViewModel.kt")
        val sources = codeOf("swipesource/SwipeSources.kt")
        assertTrue(
            "story 会话必须由 bootstrapStorySession 负责（story 流优先），补货必须走 " +
                "loadMoreSwipeQueue（推荐流分页接力，与详情全屏共用队列）。\n" +
                "SW-05 起三源映射（swipeEntryFromStory/FromFeedItem）随源实现落在 " +
                "swipesource/SwipeSources.kt（编排层取数据、映射在源处落地），" +
                "不再要求出现在 VM 里。",
            countMatching(vm, """fun\s+bootstrapStorySession\(seed:\s*VideoTarget\)""") == 1 &&
                countMatching(vm, """fun\s+loadMoreSwipeQueue\(""") == 1 &&
                countMatching(sources, """swipeEntryFromStory""") >= 1 &&
                countMatching(sources, """swipeEntryFromFeedItem""") >= 1 &&
                countMatching(vm, """SwipeSourceRegistry\(""") >= 1
        )
        val screen = codeOf("VideoScreen.kt")
        assertTrue(
            "story 模式必须常驻全屏（isFull 与 swipeSessionActive 初始即 storyMode），" +
                "且「全屏」钮与 swipe 分支共三处 effectiveStoryMode 分叉 = 关闭页面/接管会话。",
            countMatching(screen, """mutableStateOf\(storyMode\)""") == 2 &&
                countMatching(screen, """swipeSessionActive = true""") == 1 &&
                countMatching(screen, """if \(effectiveStoryMode\) \{""") == 3
        )
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "补货判据必须经 shouldExtendSwipeQueue 由 pager 触发 onNeedMore。",
            countMatching(pager, """onNeedMore""") >= 2 &&
                countMatching(pager, """shouldExtendSwipeQueue\(""") == 1
        )
    }

    /**
     * SW-13 失败态接线的写法不变式：分类只走策略函数、会话状态真的被置成失败、
     * 重试必须去抖、veil 文案按类型显示。
     */
    @Test
    fun swipeFailureIsClassifiedAndRetryIsDebounced() {
        val vm = codeOf("VideoViewModel.kt")
        val pager = codeOf("player/PortraitSwipePager.kt")
        val pane = codeOf("player/VideoPlayerPane.kt")
        assertTrue(
            "失败必须分类（SwipeErrorPolicy.classify）并落到会话状态机（playbackFailed）；" +
                "QoE 记一条 play_error；手动重试必须去抖（连点会把引擎打爆）。",
            countMatching(vm, """SwipeErrorPolicy\.classify\(""") == 1 &&
                countMatching(vm, """swipeSession\.playbackFailed\(""") == 1 &&
                countMatching(vm, """SwipeQoeEventType\.PlayError""") == 1 &&
                countMatching(vm, """SwipeErrorPolicy\.isManualRetryDebounced\(""") == 1 &&
                countMatching(vm, """SwipeErrorPolicy\.autoRetryDelayMs\(""") == 1
        )
        assertTrue(
            "veil 文案必须按分类给'人话'（网络→检查网络、解码→换一支）：" +
                "pager 传 swipeErrorText、pane 用它渲染，找不到则退默认文案。",
            countMatching(
                pager,
                """swipeErrorText = swipeErrorKind\?\.let\(SwipeErrorPolicy::messageOf\)"""
            ) == 1 &&
                countMatching(pane, """errorText = swipeErrorText,""") == 1 &&
                countMatching(pane, """text = errorText \?:""") == 1
        )
    }

    // ── 刷视频入口：简介区边缘下滑（不新增控件；全屏按钮不连播）────────────────

    /**
     * 横屏视频补齐刷视频入口（2026-09-29 交接书）的写法不变式：
     *
     * 1. **R1 不新增控件**：上一轮曾在播放器顶栏加过「刷视频」胶囊（PlayerPillChip），
     *    已按新方案删除——入口一律不得再以控件形态回到播放器 UI；
     * 2. **R2 唯一触发**：会话启用必须携带 `swipeModeRequested`——简介区顶边下滑提交时
     *    置位（`pullDownToFullscreen` 的 commit 分支是唯一置位点），离开全屏即复位；
     * 3. **R3 全屏按钮不连播**：启用判据 = 显式请求 && 队列非空，且与画幅无关
     *    （isPortraitVideo 不得回到判据里——它另有顶栏「横屏」胶囊与方向 effect 的用途）；
     * 4. **A7/A8 手势边界**：下滑手势只挂在详情布局上且保留 `canListScrollDown` 让位
     *    判据（简介能滚就不接管，普通滚动不得误进连播）；播放器面板不得挂该手势；
     *    会话内 pager 仍以 swipePagingActive = true 组合播放器（播放器手势语义不变）。
     */
    @Test
    fun swipeModeEntryIsIntroEdgePullOnly() {
        val screen = codeOf("VideoScreen.kt")
        val pane = codeOf("player/VideoPlayerPane.kt")
        assertTrue(
            "播放器顶栏不得有「刷视频」控件（R1：UI 与改动前一致）。" +
                "上一轮的 PlayerPillChip 入口已删，回来即违规。",
            countMatching(pane, """text\s*=\s*"刷视频"""") == 0 &&
                countMatching(pane, """onEnterSwipeMode""") == 0
        )
        assertTrue(
            "刷视频请求必须是一次性状态（rememberSaveable），仅描述「这次进全屏的来路」。",
            countMatching(screen, """var\s+swipeModeRequested\s+by\s+rememberSaveable""") == 1
        )
        assertTrue(
            "会话启用判据必须是「显式下滑请求 && 队列非空」（R3：全屏按钮只进全屏），" +
                "且激活日志要带上 requested 便于真机核对入口来路。",
            countMatching(
                screen,
                """swipeSessionActive\s*=\s*swipeModeRequested\s*&&\s*viewModel\.swipeQueue\.value\.isNotEmpty\(\)"""
            ) == 1 &&
                countMatching(screen, """activate: requested=""") == 1
        )
        assertTrue(
            "启用判据那一行不得引用 isPortraitVideo（画幅不参与会话启用，横屏同样可进）。",
            !screen.first { it.contains("swipeSessionActive = swipeModeRequested") }
                .contains("isPortraitVideo")
        )
        assertTrue(
            "下滑请求必须一次性：离开全屏（!fullOn 分支）复位，按钮进全屏不得复用上一次手势的请求。",
            countMatching(screen, """swipeModeRequested = false""") == 1
        )
        assertTrue(
            "唯一置位点必须在简介区下滑手势的提交分支里（R2）：手势只许定义 + 竖排详情布局" +
                "一处挂载（多挂一个分支就多一个触发点），置位语句与 via=introPull 日志都随 commit 走。",
            countMatching(screen, """pullDownToFullscreen\(""") == 2 &&
                countMatching(screen, """swipeModeRequested = true""") == 1 &&
                countMatching(screen, """enter: via=introPull""") == 1
        )
        run {
            val gestureAt = screen.indexOfFirst { it.contains(".pullDownToFullscreen(") }
            val requestAt = screen.indexOfFirst { it.contains("swipeModeRequested = true") }
            assertTrue(
                "置位语句必须写在 pullDownToFullscreen 的接线之内（onSettle 的 commit 分支），" +
                    "不能挪到别的入口上。",
                gestureAt in 0 until requestAt
            )
        }
        assertTrue(
            "手势边界（A7/A8）：让位判据 canListScrollDown 必须还在（简介能滚就不接管）；" +
                "播放器面板不得挂这个手势；会话内 pager 仍以 swipePagingActive = true " +
                "组合播放器（播放器手势语义不变）。\n" +
                "**SX-01 更新（W27）**：判据由 `{ detailListState.canScrollBackward }` 变成" +
                "**分层**形态 `{ !detailIntroPageVisible || detailListState.canScrollBackward }`" +
                "（评论页一律让位，缺陷①）。所以这里改为断言两件事：①门控调用点仍在、只有一处；" +
                "②简介页判据 `detailListState.canScrollBackward` 仍在且**只有一处**" +
                "（零回归；多一处就是没分层的旧门控又回来了）。分层语义由 " +
                "`commentPageNeverArmsThePageLevelPullDownGesture` 单独咬住。",
            countMatching(screen, """canListScrollDown\s*=\s*\{""") == 1 &&
                countMatching(screen, """detailListState\.canScrollBackward""") == 1 &&
                countMatching(pane, """pullDownToFullscreen""") == 0 &&
                countMatching(codeOf("player/PortraitSwipePager.kt"), """swipePagingActive = true""") == 1
        )
    }

    // ── 方向：竖屏刷视频会话期间锁竖屏（真机反馈"每次下滑都有旋转动画"）────────────

    /**
     * 用户反馈②：「每次下滑，屏幕会有一个**旋转动画**」。
     *
     * `isPortraitVideo` 的 remember key 是 `videoState.currentStream`：切换视频的那一刻
     * 它是 null → 翻成 false → 只要 `autoRotateFullscreen` 开着，这个 effect 就会朝
     * SENSOR_LANDSCAPE 写一次（转屏动画），新流回来（竖屏）再写回 UNSPECIFIED ——
     * **一次翻页转两圈**。刷到真的是横屏的视频时同理。
     *
     * 对齐 BiliPai：它的竖屏连播态**完全不写 requestedOrientation**
     * （`VideoDetailPlatformPolicy.resolvePhoneVideoRequestedOrientation` 在
     * `isPortraitFullscreen` 时 `return null`，调用方 `?: return@LaunchedEffect`），
     * 传感器自动旋转也被 `shouldObservePhoneAutoRotate` 关掉。
     * bbspace 走"会话期间锁竖屏"这一侧（与抖音/B 站官方的竖屏连播一致）。
     */
    @Test
    fun portraitSwipeSessionLocksOrientation() {
        val screen = codeOf("VideoScreen.kt")
        assertTrue(
            "刷视频会话期间必须锁竖屏（而不是让 isPortraitVideo 的瞬时值决定方向）：" +
                "一次翻页会转两圈，用户看到的就是「每次下滑都有旋转动画」。" +
                "SENSOR_LANDSCAPE 共两处：原有「单视频全屏自动横屏」分支 + 会话内" +
                "「手动横屏」分支（后者优先，见 swipeSessionOffersManualLandscape）。",
            countMatching(screen, """val\s+portraitSwipeActive\s*=""") == 1 &&
                countMatching(screen, """ActivityInfo\.SCREEN_ORIENTATION_PORTRAIT""") == 1 &&
                countMatching(screen, """ActivityInfo\.SCREEN_ORIENTATION_SENSOR_LANDSCAPE""") == 2
        )
        assertTrue(
            "竖屏锁判据必须同时覆盖「详情全屏的滑动会话」与「story 常驻全屏」两条入口。",
            countMatching(
                screen,
                """portraitSwipeActive\s*=\s*swipeSessionActive\s*\|\|\s*effectiveStoryMode"""
            ) == 1
        )
        val lockAt = screen.indexOfFirst {
            it.trim() == "portraitSwipeActive -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT"
        }
        val landscapeAt = screen.indexOfFirst {
            it.contains("ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE") &&
                !it.contains("swipeLandscape")
        }
        assertTrue(
            "锁竖屏分支必须排在（原有的「单视频全屏自动横屏」）横屏分支**之前**（when 取首个命中分支）。",
            lockAt in 0 until landscapeAt
        )
        assertTrue(
            "会话结束（effect 重跑/退出组合）必须把方向复位成 UNSPECIFIED：" +
                "留在竖屏锁里会让后续页面出不去横屏（与原先 SENSOR_LANDSCAPE 泄漏同理）。",
            countMatching(screen, """ActivityInfo\.SCREEN_ORIENTATION_UNSPECIFIED""") == 2
        )
    }

    // ── 刷视频的加载观感（真机反馈：上滑闪预览页 / 要高级加载动画）────────────────

    /**
     * 用户反馈第 2 条：「刷视频模式每次上滑都会**闪一下预览页**」，以及
     * 第二轮追加的「还是有**预览图**出现，同时有一个**大大的暂停按钮**」。
     *
     * 根因链：
     * 1. 翻页后新页立刻从 `SwipeCoverPage` 切成 `VideoPlayerPane`，而 PlayerView 开了
     *    `setKeepContentOnPlayerReset(true)` —— 那一瞬间画面还是**上一支视频的最后一帧**；
     * 2. 第一版修法在播放器之上铺了一层**封面**当遮罩：封面页刚给用户看过同一张图，
     *    再铺一次读作"预览图又闪了一下"；
     * 3. 那一层插在控件**之前**，于是中央 72dp 播放键与底栏控件压在它上面 = 用户看到的
     *    「大大的暂停按钮」。
     *
     * 现在的形态：等首帧期间整屏只剩一层**深色加载层 + 大尺寸粒子**，
     * 不重复铺封面、不露控件、不带 pointerInput（不挡上下滑）。
     */
    @Test
    fun swipeWaitsFirstFrameBehindLoadingVeil() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        val waitPolicy = codeOf("SwipeWaitPolicy.kt")
        assertTrue(
            "「等首帧」必须是**粘滞**状态：换视频（SW-03 起为类型化 currentId，旧字符串 " +
                "currentKey 与之同拍变化）进入等待，直到这一支真的出帧才退出。" +
                "直接取 `!hasRenderedFirstFrame` 的瞬时值是错的 —— 换视频后的头几十毫秒里" +
                "它仍是上一支的 true（finishVideoPlayback 先发布新 ids、后 stopForReuse 归零），" +
                "加载层会先卸载再挂上，中间那一帧就是真机看到的「黑屏 + 大暂停键」。\n" +
                "SW-11 起**何时撤**交给 SwipeWaitPolicy 纯判据，且清键必须与判据在**同一个 " +
                "effect** 内直落（不许再经 retireVeil 之类的中间状态转手：多一跳 effect 派发 " +
                "会把首帧→撤遮罩从 ~19ms 拖到 ~37ms，真机多盖一帧）。默认 " +
                "MIN_VEIL_VISIBLE_MS_DEFAULT=0 时退化为首帧即撤，逐帧同拍（编译期判据）。",
            countMatching(pager, """var\s+waitingKey\s+by\s+remember""") == 1 &&
                countMatching(pager, """val\s+waitingFirstFrame\s*=\s*waitingKey != null""") == 1 &&
                countMatching(pager, """LaunchedEffect\(currentId\)""") == 1 &&
                countMatching(pager, """retireVeil""") == 0 &&
                countMatching(pager, """delayMs <= 0L -> waitingKey = null""") == 1 &&
                countMatching(
                    pager,
                    """SwipeWaitPolicy\.retireDelayMs\("""
                ) == 1 &&
                countMatching(
                    waitPolicy,
                    """const val MIN_VEIL_VISIBLE_MS_DEFAULT = 0L"""
                ) == 1
        )
        val pane = codeOf("player/VideoPlayerPane.kt")
        assertTrue(
            "加载层必须是播放页 Box 的**最后一个子节点**：插在控件之前时，" +
                "中央那颗 72dp 播放键会压在它上面（真机反馈「大大的暂停按钮」）。" +
                "判据只允许用 swipeWaitingFirstFrame —— 不许再与 swipePagingActive 相与：" +
                "story 入口在锚点就位前走的是「单播放器」分支（没有 pager），" +
                "那条路上 swipePagingActive 恒 false，相与等于那段黑屏没有加载层。" +
                "失败态必须真的接上（onRetrySwipe 传进加载层，且加载层里有重试分支）。\n" +
                "SW-34 收口：visible/failed 不再各自派生，统一由 SwipeLoadPhasePolicy.resolve " +
                "（失败优先 → 等首帧(有/无封面) → 隐藏）判三态后派生——旧断言直读两个布尔，" +
                "新断言盯「判据单一来源 + 两个派生助手」，veil 内部时序与渲染顺序未变。",
            countMatching(pane, """private fun SwipeWaitVeil\(""") == 1 &&
                countMatching(pane, """SwipeLoadPhasePolicy\.resolve\(""") == 1 &&
                countMatching(
                    pane,
                    """visible = SwipeLoadPhasePolicy\.isVeilVisible\(loadPhase\)"""
                ) == 1 &&
                countMatching(
                    pane,
                    """failed = SwipeLoadPhasePolicy\.isError\(loadPhase\)"""
                ) == 1 &&
                countMatching(pane, """onRetry = onRetrySwipe""") == 1 &&
                countMatching(pane, """if \(failed\) \{""") == 1 &&
                countMatching(pane, """TextButton\(onClick = onRetry\)""") == 1 &&
                countMatching(pane, """BiliParticleLoader\(""") >= 1 &&
                countMatching(
                    codeOf("VideoScreen.kt"),
                    """swipeWaitingFirstFrame = effectiveStoryMode"""
                ) == 1
        )
        assertTrue(
            "中央播放键必须抑制「会话内首帧未出」的窗口（2026-10-01 真机：「翻页时大暂停键" +
                "闪一帧」——翻页瞬间引擎 stopForReuse 让 playWhenReady 短暂为假，而加载层是 " +
                "effect 异步架起的，架起前的 1~2 帧按钮会露出来）。判据必须是**同步**信号：" +
                "swipePagingActive && !hasRenderedFirstFrame；同时保留既有 !isPlaying / Ended " +
                "语义（暂停与播完仍要能点到按钮，非会话单视频不受影响）。",
            countMatching(pane, """val showCenterPlayButton =""") == 1 &&
                countMatching(pane, """!\(swipePagingActive && !state\.hasRenderedFirstFrame\)""") == 1
        )
        assertTrue(
            "加载层必须写在**播放器 Box 内部**：写在 Box 收尾之后，它会成为 " +
                "CompositionLocalProvider 的第二个子节点、被父级布局排到屏幕外——" +
                "组合照常（状态/日志全对）但永远看不见（真机实测踩过）。" +
                "判据：它必须排在那些**在 Box 之外**的对话框之前" +
                "（Quality/Audio/Speed 三个 dialog 都在 Box 收尾之后）。",
            run {
                val veilAt = pane.indexOfFirst { it.trimStart().startsWith("SwipeWaitVeil(") }
                val dialogAt = pane.indexOfFirst {
                    it.contains("activeDialog == PlayerDialog.Quality")
                }
                veilAt >= 0 && dialogAt >= 0 && veilAt < dialogAt
            }
        )
        assertTrue(
            "加载层**不许再盖不透明深色**：真机反馈「感觉就是盖了一层遮罩」—— " +
                "原来换页时是「封面 → 一块黑布 → 视频」，现在必须用**同款封面铺底**" +
                "（不裁切的 PlayerPreview + Fit 居中，与 SwipeCoverPage 同一 URL/取景）" +
                "+ 轻遮罩 + 小粒子，过渡才是「图 → 图+暗+粒子 → 视频」。" +
                "没有封面时才允许退化成深色底。整层仍不许带 pointerInput。" +
                "（用户原话：要么把预览图优化好、完美显示在视频中间——封面页现在就是这么做的。）",
            countMatching(pane, """swipeCoverUrl: String\? = null""") == 1 &&
                countMatching(pane, """^\s*coverUrl: String\? = null""") == 1 &&
                countMatching(pane, """variant = BiliImageVariant\.PlayerPreview""") == 1 &&
                countMatching(pane, """contentScale = ContentScale\.Fit""") == 1 &&
                // 封面页（pager 里）必须用**同一个**变体与取景，否则"封面页一张、加载底另一张"
                countMatching(
                    codeOf("player/PortraitSwipePager.kt"),
                    """variant = BiliImageVariant\.PlayerPreview"""
                ) == 1 &&
                countMatching(pane, """WaitVeilScrimTopColor""") >= 2 &&
                countMatching(pane, """VEIL_COVER_LOADER_WIDTH_FRACTION""") >= 2 &&
                countMatching(codeOf("player/PortraitSwipePager.kt"), """swipeCoverUrl = entry\.cover""") == 1 &&
                blockBody(pane, "private fun SwipeWaitVeil(")
                    .none { it.contains("pointerInput") }
        )
    }

    /**
     * 刷视频「封面 → 画面」交接的**两段退场**（2026-10-01，对齐 BiliPai v0.2.3 的时序形状）。
     *
     * 这条守卫守的是"翻页闪一下黑"这个具体缺陷的形状，不是泛泛的"有没有动画"：
     *
     * 1. 内容（封面）与非内容（暗遮罩 + 粒子）必须是**两条 alpha**。整层共用一个 alpha
     *    淡出 = 淡到一半时"半张暗遮罩压在半条画面上"，比交接两端都暗；
     * 2. 顺序必须是**暗层先退完**，封面才开始交叉淡化（写反了缺陷就回来了）；
     * 3. 粒子（常驻无限动画）必须随暗层一起摘掉 —— 它空转正好占掉交接那几帧的预算；
     * 4. 时长必须来自动效令牌 + `reduceMotion` 分支，不许写死毫秒；
     * 5. 邻居页必须**预组合**（`beyondViewportPageCount = 1`）：默认 0 时"手指一动"那一帧
     *    要同时建树 + 发封面请求 + 布局，起手顿挫就是这么来的。
     */
    @Test
    fun swipeHandoffRetiresChromeBeforeTheCoverDissolves() {
        val pane = codeOf("player/VideoPlayerPane.kt")
        val veil = blockBody(pane, "private fun SwipeWaitVeil(")
        assertTrue(
            "封面与暗层必须各有自己的 alpha（各 1 处），回退成整层共用一个即违规。",
            countMatching(pane, """val\s+coverAlpha\s*=\s*remember\s*\{\s*Animatable""") == 1 &&
                countMatching(pane, """val\s+chromeAlpha\s*=\s*remember\s*\{\s*Animatable""") == 1
        )
        assertTrue(
            "旧的单 alpha 形态不得回来：`val alpha = remember { Animatable(...) }` 一层带走全部。",
            veil.none { Regex("""val\s+alpha\s*=\s*remember""").containsMatchIn(it) }
        )
        val chromeOut = veil.indexOfFirst { it.contains("chromeAlpha.animateTo(0f") }
        val coverOut = veil.indexOfFirst { it.contains("coverAlpha.animateTo(0f") }
        assertTrue(
            "暗层退场必须**排在**封面淡化之前（顺序写反 = 暗层又被带进交接中段）。" +
                "chromeAt=$chromeOut coverAt=$coverOut",
            chromeOut >= 0 && coverOut >= 0 && chromeOut < coverOut
        )
        assertTrue(
            "粒子要随暗层摘掉（常驻无限动画不许空转过渡期）。",
            countMatching(veil, """else if \(!chromeGone\)""") == 1 &&
                veil.any { it.contains("chromeGone = true") }
        )
        assertTrue(
            "时序参数必须走纯策略 [SwipeHandoffTiming]（时长来自令牌、含 reduceMotion 分支），" +
                "这样它才可 JVM 单测；写死毫秒等于把用户的「动效速度」设置关掉。",
            countMatching(pane, """SwipeHandoffTiming\.resolve\(""") == 1 &&
                countMatching(pane, """reduceMotion = anim\.reduceMotion""") == 1 &&
                countMatching(pane, """shortMs = anim\.short""") == 1
        )
        assertTrue(
            "两条 alpha 与落定缩放都要在 graphicsLayer 里读值（读在组合期 = 逐帧重组）。",
            veil.count { it.contains("graphicsLayer {") } >= 2 &&
                veil.any { it.contains("alpha = coverAlpha.value") } &&
                veil.any { it.contains("alpha = chromeAlpha.value") }
        )

        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "上下滑 pager 必须预组合邻居页（beyondViewportPageCount = 1）。",
            countMatching(pager, """beyondViewportPageCount = 1""") == 1
        )
    }

    /**
     * 轴仲裁接线（判据本身由 `PlayerDragAxisPolicyTest` 覆盖，这里只钉"真的接上了"）。
     *
     * 三条都是"看起来对、实际没生效"的形状：
     * 1. 判据必须走 [PlayerDragAxisPolicy]，旧的 `abs(x) > abs(y)` 一过 slop 就锁轴的写法
     *    不得回来（那正是"斜着上滑被吞"的根因）；
     * 2. **未决期不许消费事件** —— 播放器在歧义窗口里吞掉位移，连播的起手就划不动，
     *    加了宽限反而比不加更糟；锁定方向后才照旧全程消费（播放器手势语义不变）；
     * 3. 宽限只在**会话**（passthrough）里给；详情页给宽限会与外层纵向列表双响应。
     */
    @Test
    fun gestureAxisArbitrationUsesGraceOnlyInSwipeSessions() {
        val ctrl = codeOf("player/VideoGestureController.kt")
        assertTrue(
            "轴判据必须来自 PlayerDragAxisPolicy.resolveIntent（可单测的纯函数）。",
            countMatching(ctrl, """PlayerDragAxisPolicy\.resolveIntent\(""") == 1 &&
                countMatching(ctrl, """intent == PlayerDragIntent\.HORIZONTAL""") == 1
        )
        assertTrue(
            "旧判据不得回来：`abs(totalDelta.x) > abs(totalDelta.y)` 在 slop 处立即锁轴。",
            ctrl.none { it.contains("abs(totalDelta.x) > abs(totalDelta.y)") } &&
                ctrl.none { Regex("""if \(totalDelta\.getDistance\(\) >= slop\)""").containsMatchIn(it) }
        )
        assertTrue(
            "未决期不消费：唯一的消费点必须带 `phase != Phase.Press` 前置条件。",
            countMatching(ctrl, """if \(phase != Phase\.Press\) change\.consume\(\)""") == 1 &&
                countMatching(ctrl, """^\s*change\.consume\(\)\s*$""") == 0
        )
        assertTrue(
            "宽限倍数按场景取（会话=策略常量、详情页=1f 即不宽限），" +
                "且 passthrough 只读一次（每个事件读两次会在中途改值时判据自相矛盾）。",
            countMatching(ctrl, """PlayerDragAxisPolicy\.AMBIGUOUS_SLOP_MULTIPLIER""") == 1 &&
                countMatching(ctrl, """if \(passthrough\) \{""") == 1 &&
                countMatching(ctrl, """val passthrough = curVerticalDragPassthrough\(\)""") == 1
        )
    }

    /**
     * 会话内**收藏**（补齐 BiliPai 竖屏互动栏的 赞/评/藏/享）。
     *
     * 钉三条，都是"接了但接错"的形状：
     * 1. 弹不弹必须由 `openFavSheet()` 的**返回值**决定 —— 未登录时它只弹 toast 并返回
     *    false，无条件弹会把一层空白"暂无收藏夹"盖在"请先登录"上面（详情页实测踩过）；
     * 2. 会话不另存收藏夹状态，必须读 VM 上那一份 `viewModel.favSheet`（两份真值源必然漂移）；
     * 3. 面板在场时互动栏要让位（与侧栏/评论卡同一门控口径），且 VM 置 dismissRequest
     *    时会话宿主必须自己收 —— 收起落位写在 NonCancellable 里（closeSheet 的同一条教训）。
     */
    @Test
    fun swipeSessionOffersFavorite() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        val pane = codeOf("player/VideoPlayerPane.kt")
        assertTrue(
            "互动栏必须有收藏动作，且宿主不接时整颗不出现（不做假按钮）。",
            countMatching(pane, """if \(onOpenFavorite != null\) \{""") == 1 &&
                countMatching(pane, """onOpenFavorite: \(\(\) -> Unit\)\? = null""") == 1
        )
        assertTrue(
            "弹层开关只能由 openFavSheet() 的返回值驱动。",
            countMatching(pager, """if \(viewModel\.openFavSheet\(\)\) \{""") == 1 &&
                countMatching(pager, """showFavSheet = true""") == 1
        )
        assertTrue(
            "收藏夹状态必须读 VM 那一份（`viewModel.favSheet`），并在 dismissRequest 时由宿主收。",
            countMatching(pager, """by viewModel\.favSheet\.collectAsStateWithLifecycle\(\)""") == 1 &&
                countMatching(pager, """viewModel\.consumeFavDismiss\(\)""") == 1 &&
                countMatching(pager, """FavFolderSheetContent\(""") == 1
        )
        assertTrue(
            "面板在场时互动栏必须让位；收起落位必须在 NonCancellable 里" +
                "（hide() 是挂起点，宿主被取消时后面的赋值不会跑）。",
            countMatching(pager, """&&\s*!showFavSheet""") == 1 &&
                countMatching(pager, """withContext\(NonCancellable\) \{ showFavSheet = false \}""") == 1
        )
    }

    /**
     * 蜂窝下的**详情预热**闸门接线（2026-10-01，对照上游 `a4b8c17f8`）。
     *
     * 判据本身由 `SwipePrefetchPolicyTest` 覆盖，这里只钉"VM 真的按它分流"：
     * 1. 详情预热必须包在 `if (warmDetail)` 里，且 warmDetail 只能来自策略函数；
     * 2. playurl 预取**不许**被顺手关掉（那是另一条更便宜的收益，判据在 prefetchCount）；
     * 3. 网络状态一次预取只读一次（循环里反复问系统会在同一次滑动里得出自相矛盾的结论），
     *    且日志要把 `unmetered` / `detailWarm` 打出来 —— 蜂窝行为只能靠日志核对。
     */
    @Test
    fun cellularGateSkipsDetailWarmButNotPlayUrl() {
        val vm = codeOf("VideoViewModel.kt")
        assertTrue(
            "warmDetail 必须来自 SwipePrefetchPolicy.shouldWarmDetail，不许写死。",
            countMatching(vm, """SwipePrefetchPolicy\.shouldWarmDetail\(isUnmetered\)""") == 1 &&
                countMatching(vm, """private fun prefetchEntry\(entry: SwipeEntry, warmDetail: Boolean\)""") == 1
        )
        val body = blockBody(vm, "private fun prefetchEntry(")
        assertTrue(
            "详情请求必须在 `if (warmDetail)` 之内；playurl 预取必须在之外（蜂窝下仍要走）。",
            countMatching(body, """if \(warmDetail\) \{""") == 1 &&
                run {
                    val gate = body.indexOfFirst { it.contains("if (warmDetail) {") }
                    val detail = body.indexOfFirst { it.contains("detailRepository.fetchVideoDetail(") }
                    val playUrl = body.indexOfFirst { it.contains("preloadPlayUrl(") }
                    gate in 0 until detail && playUrl in 0 until gate
                }
        )
        val prefetch = blockBody(vm, "fun prefetchSwipeQueue(")
        assertTrue(
            "网络状态一次预取只读一次，并随 count 共用同一个值；日志必须带 unmetered/detailWarm。",
            countMatching(prefetch, """networkTypeProvider\.isUnmetered\(\)""") == 1 &&
                countMatching(prefetch, """isUnmetered = isUnmetered""") == 1 &&
                countMatching(prefetch, """unmetered=[$]isUnmetered detailWarm=[$]warmDetail""") == 1
        )
    }

    /**
     * 会话锁竖屏（`portraitSwipeSessionLocksOrientation`）的**配套出口**：
     * 竖屏连播里刷到横屏视频时，用户必须有一个显式要横屏的入口——否则只能看 letterbox，
     * "锁竖屏"这条修复就成了功能倒退。BiliPai 同款（`onRotateToLandscape`）。
     *
     * 约束：
     * - 只在**当前视频是横屏**时出现（竖屏视频转横屏只会更小）；
     * - 手动横屏**优先于**竖屏锁（写在锁之后就是摆设）；
     * - 只对当前这支视频生效：切视频 / 离开会话就收回。
     */
    @Test
    fun swipeSessionOffersManualLandscape() {
        val screen = codeOf("VideoScreen.kt")
        assertTrue(
            "手动横屏必须参与方向决策，并在切视频/离开会话时收回。",
            countMatching(screen, """swipeLandscape && portraitSwipeActive""") == 1 &&
                countMatching(screen, """LaunchedEffect\(videoState\.ids, swipeSessionActive\)""") == 1 &&
                countMatching(screen, """onToggleLandscape = \{ swipeLandscape = !swipeLandscape \}""") == 1
        )
        val lockAt = screen.indexOfFirst { it.contains("swipeLandscape && portraitSwipeActive") }
        // 竖屏锁分支：当行就是 `portraitSwipeActive -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT`
        // —— 不能用 contains("portraitSwipeActive ->")，手动横屏那行也以它结尾。
        val portraitAt = screen.indexOfFirst {
            it.trim() == "portraitSwipeActive -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT"
        }
        assertTrue(
            "手动横屏分支必须排在竖屏锁分支**之前**（when 取首个命中分支）——写在后面等于没写。",
            lockAt >= 0 && portraitAt >= 0 && lockAt < portraitAt
        )
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "入口只在当前视频是横屏时给（onToggleLandscape.takeIf{!isPortraitVideo}），" +
                "且与 VideoScreen 的 isPortraitVideo 同源（currentStream）。",
            countMatching(
                pager,
                """onToggleLandscape = onToggleLandscape\.takeIf \{ !isPortraitVideo \}"""
            ) == 1 &&
                countMatching(pager, """remember\(playbackState\.currentStream\)""") == 1
        )
        val pane = codeOf("player/VideoPlayerPane.kt")
        assertTrue(
            "播放器要把开关渲染成胶囊（与「弹幕」胶囊同一套 chrome），null 时不出现。",
            countMatching(pane, """onToggleLandscape\?\.let \{ toggle ->""") == 1 &&
                countMatching(pane, """landscapeForced\) "竖屏" else "横屏"""") == 1
        )
    }

    /**
     * 用户反馈第 3 条：「等待视频加载的时候加一个**高级的加载动画**（最好用粒子效果）」。
     */
    @Test
    fun waitingUsesParticleLoader() {
        val loader = codeOf("component/BiliParticleLoader.kt")
        assertTrue(
            "粒子加载必须**只经绘制期读相位**：withInfiniteAnimationFrameMillis + " +
                "mutableFloatStateOf 在 Canvas 的 draw lambda 里读值，既不加 " +
                "rememberInfiniteTransition、也不用 animate*AsState（两者都在 " +
                "FrameBudgetLintTest 的棘轮里，组合期读值等于每帧重组宿主）。",
            countMatching(loader, """withInfiniteAnimationFrameMillis""") == 1 &&
                countMatching(loader, """rememberInfiniteTransition""") == 0 &&
                countMatching(loader, """animate[A-Za-z]*AsState\(""") == 0 &&
                countMatching(loader, """Canvas\(""") == 1
        )
        assertTrue(
            "两处等待都必须用同一个粒子组件（观感一致）：播放器页的等首帧遮罩、" +
                "封面页的加载指示；且封面页不许回退成 CircularProgressIndicator。",
            countMatching(codeOf("player/VideoPlayerPane.kt"), """BiliParticleLoader\(""") == 1 &&
                countMatching(codeOf("player/PortraitSwipePager.kt"), """BiliParticleLoader\(""") == 1 &&
                countMatching(codeOf("player/PortraitSwipePager.kt"), """CircularProgressIndicator""") == 0
        )
        val code = loader
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "粒子组件的尺寸必须全部取 size.minDimension 的比例，**不许出现 dp/sp 字面量**" +
                "（否则 DesignTokenLintTest 的棘轮要为它让路）。",
            !Regex("""\b\d+(\.\d+)?\.(dp|sp)\b""").containsMatchIn(code)
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

    /**
     * 底部细进度条 / 可拖 seek **已按产品决定删除**（2026-10-02）。
     *
     * 历史：SW-15 曾要求「底部常驻细进度条 + 横向拖动 seek + 时间气泡」，本测试原先钉的
     * 是「它必须存在、且在加载层之前」。用户真机验收明确表示不需要这条进度条
     * （「把底部细进度条这个功能删掉，我不需要」），于是把它连同 `SwipeSeekPolicy`
     * 与 `SwipeAutoAdvancePolicy` 里只服务于它的 `isScrubbing` 入参一起删除。
     *
     * §8.4：产品决定覆盖任务书 → 守卫同批反转（不留一条注定变红的旧断言）。
     * 新断言钉三件「不许回来」：
     * ① pane 里不再有该组件定义，pager 也不再调用；
     * ② 相关常量与配色（轨道比例 / 轨道色 / 已播色 / 底部内边距）清干净，不留死代码；
     * ③ 底部的「不动」不受影响：会话信息层仍在 pager 常驻，且封面页内部不再画标题条
     *    （后者正是用户两次反馈「底部简介从下往上移动」的根因）。
     * 将来要恢复：策略、单测、调用点与本文档一起恢复，不要只把组件加回来。
     */
    @Test
    fun swipeProgressLineStaysRemoved() {
        val pane = codeOf("player/VideoPlayerPane.kt")
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "底部细进度条已删除：pane 里不许再有该组件定义、pager 不许再调用。",
            countMatching(pane, """SwipeProgressLine""") == 0 &&
                countMatching(pager, """SwipeProgressLine""") == 0
        )
        assertTrue(
            "进度条相关常量/配色必须一并清掉，不留死代码。",
            countMatching(pane, """SWIPE_PROGRESS_TRACK_FRACTION""") == 0 &&
                countMatching(pane, """SwipeProgressTrackColor""") == 0 &&
                countMatching(pane, """SwipeProgressBarColor""") == 0 &&
                countMatching(pane, """SwipeProgressBottomInset""") == 0
        )
        assertTrue(
            "拖动 seek 的判据与「拖动中抑制自动翻页」入参也不许被引回（若引回说明有人把" +
                "拖动 seek 加回来了，必须连同守卫一起重写，而不是绕过断言）。",
            countMatching(pane, """SwipeSeekPolicy""") == 0 &&
                countMatching(pager, """SwipeSeekPolicy""") == 0 &&
                countMatching(pager, """isScrubbing""") == 0
        )
        assertTrue(
            "会话级底部信息层仍在（标题/UP/标签必须钉在会话层、翻页不动）。",
            countMatching(pager, """SwipeSessionInfoLine\(""") == 1 &&
                blockBody(pager, "private fun SwipeCoverPage(").none {
                    it.contains("displayAuthor") || it.contains("titleMedium")
                }
        )
    }

    /**
     * 队列到底的反馈（C10）：停在最后一页时给"正在为你找更多视频…" / "已经是最后一个了"。
     *
     * 以前滑到最后一条**什么都看不到**：用户分不清"卡住了"还是"真的没有更多"。
     * 钉三件事：① 只在最后一页提示；② 两种状态都有文案；③ 提示不可点、间距走 token。
     */
    @Test
    fun swipeQueueTailShowsHintOnLastPage() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "尾部提示必须只在**最后一页**且状态非 Idle 时出现（否则会打扰正常浏览）。",
            countMatching(
                pager,
                """if \(pagerState\.currentPage >= pages\.lastIndex && tailState != SwipeQueueTail\.Idle\) \{"""
            ) == 1 &&
                countMatching(pager, """private fun SwipeQueueTailHint\(""") == 1 &&
                countMatching(pager, """SwipeQueueTail\.Extending -> "正在为你找更多视频…"""") == 1 &&
                countMatching(pager, """SwipeQueueTail\.Exhausted -> "已经是最后一个了"""") == 1 &&
                countMatching(codeOf("VideoScreen.kt"), """tailState = swipeTail""") == 1
        )
        assertTrue(
            "提示不许带 pointerInput/clickable（不能吃掉上下滑手势），" +
                "间距必须走 BiliSpacing token（不新增 dp 字面量，lint 棘轮会判红）。",
            blockBody(pager, "private fun SwipeQueueTailHint(").let { body ->
                body.none { it.contains("pointerInput") || it.contains("clickable") } &&
                    body.none { it.contains(".dp") }
            }
        )
    }

    /**
     * 会话内常驻信息（信息层第二阶段；v2 底部信息层改版）：标题 + UP。
     *
     * v2 定稿（用户反馈「大幅优化操作布局和 UI」）：UP 名上置、标题放宽两行、
     * 与互动栏同排收进底部信息层（渐变遮罩 + Row(信息·互动栏) + 进度条）。
     */
    @Test
    fun swipeSessionShowsPersistentInfoLine() {
        val pane = codeOf("player/VideoPlayerPane.kt")
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "常驻信息必须与底部三件套同一门控（会话内 + 不在等首帧），并且真的接了标题与 UP。" +
                "BiliPai 手法：读本作用域已收集的 state，调用点不得重复 collectAsStateWithLifecycle。\n" +
                "2026-10-02：调用点从 pane 提升到 pager（会话级单例，修「翻页时简介跳动」）——" +
                "定义仍在 pane，pager 侧读自己的 playbackState（同样只订阅一份）。",
            countMatching(pane, """internal fun SwipeSessionInfoLine\(""") == 1 &&
                countMatching(pane, """SwipeSessionInfoLine\(""") == 1 &&
                countMatching(pager, """SwipeSessionInfoLine\(""") == 1 &&
                countMatching(pager, """title = playbackState\.detail\?\.title""") == 1 &&
                countMatching(pager, """owner = playbackState\.detail\?\.owner\?\.name""") == 1 &&
                countMatching(
                    pager,
                    """title = viewModel\.videoState\.collectAsStateWithLifecycle"""
                ) == 0 &&
                countMatching(pane, """val titleText = stickyTitle\?\.takeIf\(String::isNotBlank\) \?: return""") == 1
        )
        val infoLine = blockBody(pane, "internal fun SwipeSessionInfoLine(")
        assertTrue(
            "底部信息层 v2→v3（SW-21）：作者行（@名）→ 标题（两行可展开）→ 话题 chip。\n" +
                "⚠️ 不变式反转：旧断言是「整层只读、不许 clickable」；任务书 SW-21 明确要求" +
                "「只让展开/关注/chip 三个明确热区可点」，本次落地两个热区（作者行、标题），" +
                "整层其余区域仍不消费触摸。dimension/间距必须走 token（不许 .dp 字面量）。\n" +
                "2026-10-02：底部**不再带头像**（真机反馈与互动栏头像重复）——头像只在互动栏。\n" +
                "2026-10-03（PT-08）：**联合创作 chip**（作者行内，`等 N 人联合创作 ˅`，点击弹" +
                "成员清单）——热区 +1（作者行/标题/联合创作 chip = 3）、单行省略文本 +1" +
                "（@名/chip/合集 = 3）。",
            infoLine.none { it.contains(".dp") } &&
                infoLine.count { it.contains("clickable(") } == 3 &&
                // @名 一行 + 联合创作 chip 一行 + 合集 chip 一行（都是单行省略）
                infoLine.count { it.contains("maxLines = 1") } == 3 &&
                infoLine.count { it.contains("maxLines = if (expanded) Int.MAX_VALUE else 2") } == 1 &&
                infoLine.any { it.contains("remember(resetKey)") } &&
                infoLine.none { it.contains("BiliAsyncImage(") } &&
                infoLine.any { it.contains("BiliSpacing.lg") }
        )
        assertTrue(
            "底部块**高度必须恒定**（用户 2026-10-02 二次反馈「标签等内容还有从下往上移动」）：\n" +
                "换页瞬间新详情未到 → 标题/话题若跟着空值走，贴底对齐的整块会先塌后长 = 视觉位移。\n" +
                "三件套：① 标题/@名/话题走粘滞值（rememberStickyText / rememberStickyTags）；" +
                "② 标题保底两行（minLines）；③ 话题/合集合并成**单行**并预留一行高度（TagRowMinHeight）。\n" +
                "另外整块不得再有 veil 门控（`if (!waitingFirstFrame)` 会让它每翻一页消失再出现）。",
            countMatching(pane, """val stickyTitle = rememberStickyText\(title\)""") == 1 &&
                countMatching(pane, """val stickyTags = rememberStickyTags\(tags\)""") == 1 &&
                countMatching(pane, """private fun rememberStickyTags\(""") == 1 &&
                countMatching(pane, """minLines = if \(expanded\) 1 else 2""") == 1 &&
                countMatching(pane, """heightIn\(min = TagRowMinHeight\)""") == 1 &&
                countMatching(pager, """if \(!waitingFirstFrame\) \{""") == 0
        )
        assertTrue(
            "互动栏 v2（SW-20）：头像在最上（点进空间）、点赞长按 = 一键三连、计数粘滞；" +
                "头像/圆盘尺寸走 token（不新增 dp 字面量）。",
            countMatching(pane, """private fun SwipeRailAvatar\(""") == 1 &&
                countMatching(pane, """SwipeRailAvatar\(""") == 2 &&
                countMatching(pane, """onLongClickLabel = "一键三连"""") == 1 &&
                countMatching(pane, """combinedClickable\(""") == 1 &&
                countMatching(pager, """onLongPressLike = viewModel::triple""") == 1 &&
                countMatching(pager, """ownerFace = playbackState\.detail\?\.owner\?\.face""") == 1
        )
    }

    /**
     * 播完行为可配（C10）：自动翻页必须受设置与幂等键共同约束。
     *
     * 以前是"只要不是单曲循环就自动翻"——用户想"看完停在结尾"没有任何办法；
     * 现在判据抽到 `SwipeAutoAdvancePolicy`，pager 只负责把四个入参喂进去。
     */
    @Test
    fun autoAdvanceConsultsSettingAndIdempotency() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "自动翻页必须走 shouldAutoAdvanceToNextSwipePage，并把「开关 / 单曲循环 / 最后一页 / " +
                "本支是否已翻过」四个判据都传进去 —— 少任何一个都会退化成旧行为。\n" +
                "SW-02 起幂等键不在 pager 局部 remember 里（旧断言 lastAutoAdvancedKey）：" +
                "键提升到会话状态机 SwipeSessionController，随会话 exit() 清空、进程重建归零，" +
                "pager 改读 viewModel.swipeLastAutoAdvancedKey。四判据契约本身不变。",
            countMatching(pager, """shouldAutoAdvanceToNextSwipePage\(""") == 1 &&
                countMatching(pager, """autoAdvanceEnabled = playerSettings\.playback\.swipeAutoAdvance""") == 1 &&
                countMatching(pager, """repeatOne = playerSettings\.playback\.repeatMode != PlayerRepeatMode\.Off""") == 1 &&
                countMatching(pager, """isLastPage = next > pages\.lastIndex""") == 1 &&
                countMatching(pager, """alreadyAdvancedForThisVideo\s*=""") == 1 &&
                countMatching(
                    pager,
                    """key\.isNotEmpty\(\) && key == viewModel\.swipeLastAutoAdvancedKey\.value"""
                ) == 1 &&
                countMatching(pager, """if \(!shouldAdvance\) return""") == 1 &&
                countMatching(codeOf("player/VideoPlaybackSheet.kt"), """title = "播完自动下一个"""") == 1
        )
    }

    /**
     * 会话内互动栏（信息层第三阶段）：点赞 + 分享。
     *
     * 竖屏连播时双击点赞是"隐式"的：用户想确认"这支我赞过没有"看不到任何状态；
     * 分享更是在连播里完全没有入口（详情页有）。BiliPai 的竖屏页右侧常驻互动栏
     * （`PortraitFullscreenOverlay.kt:194-394`）。
     *
     * 收藏（要先选文件夹）、投币（二次确认）、评论（面板是详情 pager 的一页，
     * 需要另起弹层宿主 + 评论列表状态）留到后续批次 —— 这里钉住"已有闭环的两个"
     * 真的接上了真实状态与共用链接构造。
     */
    @Test
    fun swipeSessionShowsActionRail() {
        val pane = codeOf("player/VideoPlayerPane.kt")
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "互动栏必须接上真实状态与计数（userState.liked / stat.like）、" +
                "点赞走 viewModel.toggleLike，分享走 ShareUtils.videoUrl + shareText" +
                "（与详情页同一套链接构造）—— 不许自己造本地状态或另写一套分享。" +
                "组件定义在 pane 文件（与控制件玻璃同语言），调用点在 pager；" +
                "BiliPai 手法：数据用本层已收集的状态传入，调用点不得自己造状态或另存本地副本。" +
                "（**形态更新 · VK-01（2026-10-04 真机）**：点赞/收藏的选中态与计数从" +
                "`playbackState.detail…` 改为读本层收集的 `interaction` —— `playbackState` 就是 VM 的 " +
                "`videoState`，其 `detail.userState/stat` 是**服务器快照**，而互动写路径只更新 " +
                "`_interaction`；用快照会退化成真机上的「点了没反应」。守卫意图不变：仍是" +
                "「由调用点传入、组件不自持状态」。）",
            countMatching(pane, """internal fun SwipeSessionActionRail\(""") == 1 &&
                countMatching(pane, """ShareUtils\.videoUrl\(shareBvid, shareAid, page = 0\)""") == 1 &&
                countMatching(pane, """ShareUtils\.shareText\(context, shareTitle, url\)""") == 1 &&
                countMatching(pane, """contentDescription = "分享"""") == 1 &&
                countMatching(pager, """SwipeSessionActionRail\(""") == 1 &&
                countMatching(pager, """liked = interaction\.userState\?\.liked""") == 1 &&
                countMatching(pager, """actionCount\(it, interaction\.likeDelta\)""") == 1 &&
                countMatching(pager, """onToggleLike = viewModel::toggleLike""") == 1 &&
                countMatching(
                    pager,
                    """playbackState\.detail\?\.title,\s*$"""
                ) >= 1
        )
        assertTrue(
            "互动栏必须是**会话级单实例、常驻页面层之上**（用户定稿）：pane 里不得再调用" +
                "（pane 每页一个，互动栏会跟着整页滑走再滑回来——用户反馈的「整体向上调动」）；" +
                "显隐走 AnimatedVisibility 淡入淡出（不许\"啪一下\"弹出）；" +
                "门控 = 播放设置侧栏让位 + 评论卡片让位 + 收藏面板让位（**只许让位类条件**）。\n" +
                "2026-10-01 用户反馈「每次下滑互动栏消失再出现」：旧门控带了 !waitingFirstFrame，" +
                "而翻页 veil 每次亮 ~0.5s → 每次翻页都淡出淡入。常驻件的常驻性优先，已去掉该条件；" +
                "计数侧的短暂缺失由 rail 内的粘滞值兜住（rememberStickyText）。",
            countMatching(pane, """SwipeSessionActionRail\(""") == 1 &&
                countMatching(
                    pager,
                    """visible = !playbackSheetVisible && !showComments && !showFavSheet"""
                ) == 1 &&
                countMatching(pager, """waitingFirstFrame &&""") == 0 &&
                countMatching(pager, """onPlaybackSheetVisibilityChange = \{""") == 1 &&
                countMatching(pager, """playbackSheetVisible = it""") == 1
        )
        assertTrue(
            "互动栏计数必须**粘滞**（用户反馈「消失再出现」的计数侧）：换页时 stat 为 null，" +
                "三个计数一律走 rememberStickyText；liked/faved 仍按真值（粘滞会显示错觉）。",
            countMatching(pane, """private fun rememberStickyText\(""") == 1 &&
                countMatching(pane, """label = stickyLike""") == 1 &&
                countMatching(pane, """label = stickyReply""") == 1 &&
                countMatching(pane, """label = stickyFav""") == 1 &&
                countMatching(pane, """label = likeText""") == 0
        )
        assertTrue(
            "常驻控件防烧屏（用户要求）：策略在核心层（静置 15s 变暗到 0.55、每 60s ±2px 漂移），" +
                "pager 一处计时 + CompositionLocal 下发，pane 的常驻件必须真的读它。\n" +
                "2026-10-02（用户决定删除底部细进度条）：pane 里读 burnIn 的常驻件由两个减为一个 ——" +
                "只剩会话信息层（标题/UP/标签）。这是**删除功能**导致的合法减少，不是防烧屏退化：" +
                "pager 侧的 provide / 计时 / 漂移三条断言原样保留，故这里由 `>= 2` 收紧为 `>= 1`。",
            countMatching(pager, """LocalSwipeChromeBurnIn provides chromeBurnIn""") == 1 &&
                countMatching(pager, """SwipeChromeBurnInPolicy\.chromeAlpha\(""") == 1 &&
                countMatching(pager, """SwipeChromeBurnInPolicy\.driftOffsetPx\(""") == 1 &&
                countMatching(pane, """LocalSwipeChromeBurnIn\.current""") >= 1 &&
                countMatching(
                    codeOf("SwipeChromeBurnInPolicy.kt"),
                    """const val DIM_ALPHA = 0.55f"""
                ) == 1
        )
        assertTrue(
            "性能定稿（用户反馈「使用体验很卡」）：互动栏是**常驻**控件，不许用逐帧采样的真背景" +
                "模糊；pager 层不得出现任何 playerControlGlass（玻璃管线收敛回 pane 的控制条 " +
                "chrome）；pane 回传玻璃上下文的 onVideoGlassContext 管线必须已移除。\n" +
                "2026-10-02（用户「换现代审美」）：图标底由「静态玻璃渐变 + 白描边」改为" +
                "**纯色暗盘**（`SwipeRailBackingColor`，16% 黑，对标 BiliPai iconBackingAlpha）——" +
                "断言随之从「必须有 SwipeRailGlassBrush」改为「不得再有渐变 Brush、必须用纯色底」，" +
                "禁模糊/零逐帧开销这条实质约束不变（且更省：只剩一次纯色 fill）。",
            countMatching(pager, """playerControlGlass\(""") == 0 &&
                countMatching(pager, """onVideoGlassContext""") == 0 &&
                countMatching(pane, """SwipeRailGlassBrush""") == 0 &&
                countMatching(pane, """SwipeRailBackingColor""") >= 3 &&
                blockBody(pane, "private fun SwipeRailAction(")
                    .none { it.contains("playerControlGlass") || it.contains("Brush") }
        )
        run {
            val pagerAt = pager.indexOfFirst { it.contains("VerticalPager(") }
            val railAt = pager.indexOfFirst { it.contains("SwipeSessionActionRail(") }
            assertTrue(
                "互动栏必须排在 VerticalPager 之后（z 在页面层之上，钉住不随页面位移）。",
                pagerAt in 0 until railAt
            )
        }
        assertTrue(
            "互动栏视觉走**现代短视频参数**（2026-10-02，对标 BiliPai 竖屏栏 policy）：\n" +
                "暗盘、图标尺寸全部从 token 派生、16% 纯黑底、计数带文字阴影；" +
                "互动栏不再有白描边/玻璃渐变（`.border(` / `Brush` 都不得出现在动作体内——" +
                "中央播放键的描边是另一回事，不在本条断言范围）。\n" +
                "**SX-02 更新**：尺寸表达式多了统一缩放因子 `SwipeRailScale`（0.88）——" +
                "用户需求②「侧边 UI 控件变小，少挡画面」；断言仍只要求「尺寸走 token 派生」这个" +
                "形态，不钉具体数值（具体比例与 48dp 热区由 swipeRailShrinksWithoutShrinkingTouchTarget 守）。" +
                "计数缺失时显示动作名（BiliPai 的 countText fallback）。",
            countMatching(pane, """SwipeRailDiscSize""") >= 2 &&
                countMatching(pane, """SwipeRailIconSize""") >= 2 &&
                blockBody(pane, "private fun SwipeRailAction(")
                    .none { it.contains(".border(") || it.contains("Brush") } &&
                // 计数阴影走 designsystem 的**具名出口**（棘轮：业务层不许内联 typography.copy）
                countMatching(pane, """style = softShadowLabelSmall\(\)""") == 1 &&
                countMatching(
                    codeOf("TextStyleVariants.kt"),
                    """fun softShadowLabelSmall\("""
                ) == 1 &&
                countMatching(pane, """label = stickyLike \?: "点赞"""") == 1 &&
                countMatching(pane, """label = stickyReply \?: "评论"""") == 1 &&
                countMatching(pane, """label = stickyFav \?: "收藏"""") == 1
        )
    }

    /**
     * SX-02：侧边互动栏整体缩小，**但触摸目标不得小于 48dp**。
     *
     * 用户需求②「刷视频的侧边 UI 控件变小，少挡画面」。两条实质约束：
     * ① 缩小必须走**一套统一比例**（要点④：先定比例再动手，避免各处各自缩）；
     * ② 缩的是**观感**，不是**可点区域** —— 视觉尺寸缩到 ≈46dp 后已低于无障碍
     *    48dp 下限，必须用透明留白把热区补回来（要点②）。
     * 锚在标识符的使用形态上（W18/W20/W21），并把"不许新增 dp 字面量"交给
     * DesignTokenLintTest 的棘轮兜底。
     */
    @Test
    fun swipeRailShrinksWithoutShrinkingTouchTarget() {
        val pane = codeOf("player/VideoPlayerPane.kt")
        assertTrue(
            "SX-02：互动栏缩小必须走**统一比例** SwipeRailScale，且尺寸仍由 token 派生" +
                "（`BiliSpacing.xxl * 1.6667f * SwipeRailScale` / `BiliSpacing.xxxl * 1.625f * SwipeRailScale`）。",
            countMatching(pane, """private const val SwipeRailScale = """) == 1 &&
                countMatching(pane, """BiliSpacing\.xxl \* 1\.6667f \* SwipeRailScale""") == 1 &&
                countMatching(pane, """BiliSpacing\.xxxl \* 1\.625f \* SwipeRailScale""") == 1
        )
        assertTrue(
            "SX-02 要点②：视觉缩小后**触摸目标不得小于 48dp** —— 动作圆盘与头像都要用 " +
                "SwipeRailTouchTarget（= BiliSpacing.xxxl * 1.5f = 48dp）兜住热区。",
            countMatching(pane, """private val SwipeRailTouchTarget = BiliSpacing\.xxxl \* 1\.5f""") == 1 &&
                countMatching(pane, """size\(SwipeRailTouchTarget\)""") >= 2
        )
    }

    /**
     * 会话内评论悬浮卡片：**半透明主题面板 + 贴底顶部圆角**（SX-10 / 决策 D-2）。
     *
     * 收敛史：v1 主题亮色 surface 铺在视频上读作"白补丁"→ v2 强制深色（被质疑
     * 「为什么默认深色」）→ v3 真液态玻璃（大面逐帧 RenderEffect，评论滚动期间
     * 持续吃 GPU，且"磨砂板上浮白卡"双层观感奇怪）→ v5 主题 surface 实底
     * → **v6（SX-10/D-2）：`surface + alpha` 半透明，透出背后画面，零模糊**。
     *
     * ## 为什么断言形态要改（W27：形态变了必须同批改守卫并写明理由）
     * 决策 D-2（用户 2026-10-03：「评论区不要模糊，用透明就可以，保证流畅度」）
     * 把 v5 的"实底 surface"改成了"半透明 surface"，所以：
     * ① 底色断言从 `colorScheme.surface,` 改为 `surface.copy(alpha = COMMENT_CARD_ALPHA),`；
     * ② **新增** D-2 的硬门禁（结构侧）：面板文件里 `hazeBlur(` / `drawBackdrop(` 命中必须为 0
     *    —— 离屏模糊每帧都要重新录制整屏内容，与"流畅度优先"直接冲突；
     * ③ 其余不变：不强制深色、sheetTop 圆角、形状 remember、CommentPanel 在 Surface 之内。
     */
    @Test
    fun swipeCommentCardIsTranslucentAndBlurFree() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "评论卡片必须是**半透明主题面板**：color = colorScheme.surface.copy(alpha = …)、" +
                "contentColor = onSurface（不强制深色：darkColorScheme 计数为 0）；" +
                "只用 sheetTop 顶部圆角，且形状必须 remember（不许每次重组重建）。",
            countMatching(pager, """color = MaterialTheme\.colorScheme\.surface\.copy\(alpha = COMMENT_CARD_ALPHA\),""") == 1 &&
                countMatching(pager, """contentColor = MaterialTheme\.colorScheme\.onSurface""") == 1 &&
                countMatching(pager, """darkColorScheme\(""") == 0 &&
                countMatching(pager, """BiliRadius\.sheetTop""") >= 2 &&
                countMatching(pager, """val commentCardShape = remember""") == 1 &&
                countMatching(pager, """private const val COMMENT_CARD_ALPHA = """) == 1
        )
        assertTrue(
            "**D-2 硬门禁（结构侧）：评论面板所在文件里不得出现任何模糊入口** —— " +
                "`hazeBlur(` / `drawBackdrop(` / `playerControlGlass(` 命中数必须全为 0。" +
                "大面模糊是逐帧离屏 RenderEffect，评论滚动期间持续吃 GPU，是卡顿主因。",
            countMatching(pager, """hazeBlur\(""") == 0 &&
                countMatching(pager, """drawBackdrop\(""") == 0 &&
                countMatching(pager, """playerControlGlass\(""") == 0
        )
        run {
            val surfaceAt = pager.indexOfFirst { it.contains("Surface(") }
            val panelAt = pager.indexOfFirst { it.contains("CommentPanel(") }
            assertTrue(
                "CommentPanel 必须在面板 Surface 之内组合。",
                surfaceAt in 0 until panelAt
            )
        }
    }

    /**
     * 预取必须**同时热详情**（C10 收尾项）。
     *
     * 连播页左下角的标题/UP 直接取 `videoState.detail`，而详情原本是开播时才并发请求的
     * —— 只预热播放地址就会"翻过去先空一拍再补上标题"。预热详情后，
     * `StreamPlaybackSessionImpl` 的 `reuseDetail` 短路会命中，连取详情那次请求都省了。
     */
    @Test
    fun prefetchWarmsDetailNotOnlyPlayUrl() {
        val vm = codeOf("VideoViewModel.kt")
        assertTrue(
            "prefetchEntry 里必须既 preloadPlayUrl 又 fetchVideoDetail（同一个 params，" +
                "详情失败静默）—— 只热一个都会留下可见的空拍。",
            countMatching(vm, """playbackVideoRepository\.preloadPlayUrl\(params\.getResolveParams\(\)\)""") == 1 &&
                countMatching(vm, """detailRepository\.fetchVideoDetail\(ids = params\.ids, src = params\.src\)""") >= 1 &&
                countMatching(vm, """val params = target\.toPlayableParams\(\)""") == 1
        )
    }

    /**
     * 顶栏「刷视频模式」的**起步数据源**必须与 BiliPai 一致（首页推荐流）。
     *
     * BiliPai `StoryViewModel.kt:23` 的注释原文：「数据源与**首页推荐一致**
     * （`VideoRepository.getHomeVideos`），内容更元化」；
     * bbspace 之前用 `x/v2/feed/index/story`（以种子视频为锚的竖屏流）——
     * 真机反馈「点刷视频模式，先推送的是差不多类型的视频」，正是那个锚造成的。
     */
    @Test
    fun storyBootstrapSeedsFromHomeFeed() {
        val vm = codeOf("VideoViewModel.kt")
        val sources = codeOf("swipesource/SwipeSources.kt")
        assertTrue(
            "起步批次必须先用首页推荐流，story 竖屏流只能作为拿不到内容时的兜底；" +
                "并且要打印用了哪条源便于真机核对。\n" +
                "SW-05 起这条优先级由**源成本 + 编排顺序**表达（不再在 VM 里直呼接口）：\n" +
                "首页推荐流 = HomeFeedSwipeSource（Light，单请求分页）、story = " +
                "StoryFeedSwipeSource（Heavy，仅兜底），SwipeSourceRegistry.available 的 " +
                "cost 升序消费顺序由 core:swipe 的 SwipeSourceRegistryTest 锁定；" +
                "VM 侧断言两个源都在起步源集里、且日志仍打 source=。",
            countMatching(vm, """HomeFeedSwipeSource\(feedRepository\)""") >= 2 &&
                countMatching(vm, """StoryFeedSwipeSource\(""") == 1 &&
                countMatching(vm, """bootstrap: source=""") == 1 &&
                // SX-04/D-1：接力改走**感知推荐来源**的游标重载（旧入口 fetchFeed(idx,…) 已删除），
                // 形态守卫随形态同步更新 —— 取流调用点必须只剩游标重载这一种。
                countMatching(sources, """feedRepository\.fetchFeed\(cursor = feedCursor, pull = false, flush = 0\)""") == 1 &&
                countMatching(sources, """feedRepository\.fetchFeed\(idx =""") == 0 &&
                countMatching(sources, """storyFeedRepository\.fetchStoryFeed\(""") == 1
        )
    }

    /**
     * SX-03：横屏（非全屏）播放时，视频画面**下方**要有悬浮「全屏」按钮，
     * 且点击走**既有**全屏通路（任务书要点①~⑥）。
     *
     * 断言全部锚在**调用/表达式形态**上（W18/W20/W21），并把"尺寸不许出现 dp 字面量"
     * 交给 DesignTokenLintTest 的棘轮兜底。
     */
    @Test
    fun landscapeFloatingFullscreenButtonReusesExistingFullscreenPath() {
        val screen = codeOf("VideoScreen.kt")
        assertTrue(
            "SX-03 要点①：横屏判据必须是 `Configuration.orientation`，**不是**宽度分级 ——" +
                "横屏手机宽度 ≈800dp，低于 `isExpandedWidth` 的 840dp 阈值，" +
                "两者混用会让按钮在横屏手机上根本不出现。",
            countMatching(
                screen,
                """LocalConfiguration\.current\.orientation == Configuration\.ORIENTATION_LANDSCAPE"""
            ) == 1
        )
        assertTrue(
            "按钮只在 `isLandscapeWindow` 为真时组合（竖屏不出现）；" +
                "而「全屏时不出现」由 `when` 分支结构保证（本分支必然 !fullOn）。",
            countMatching(screen, """if \(isLandscapeWindow\) \{""") == 1 &&
                countMatching(screen, """LandscapeFullscreenFab\(""") == 2
        )
        assertTrue(
            "SX-03 要点③：点击必须复用**既有**全屏通路 `enterFullscreen`（不许另造全屏状态）。",
            countMatching(screen, """onClick = enterFullscreen,""") == 1
        )
        assertTrue(
            "SX-03 要点②：位置由播放带底边 `bandMorphHeightPx()` 下移一个间距驱动" +
                "（贴在画面下方，不压画面），并随 `fullscreenMorph` 淡出（进全屏即消失）。",
            countMatching(
                screen,
                """\(bandMorphHeightPx\(\) \+ with\(density\) \{ BiliSpacing\.sm\.toPx\(\) \}\)"""
            ) == 1 &&
                countMatching(screen, """alpha = 1f - fullscreenMorph\.value""") >= 2
        )
        assertTrue(
            "SX-03 要点⑤⑥：按钮必须带 contentDescription、48dp 触摸下限（token 派生），" +
                "且不得出现 dp 字面量尺寸。",
            countMatching(screen, """contentDescription = "进入全屏"""") == 1 &&
                countMatching(screen, """defaultMinSize\(minHeight = BiliSpacing\.xxxl \* 1\.5f\)""") == 1
        )
    }

    /**
     * SX-01：评论页（含楼中楼）**不得**参与页面级下滑手势（用户缺陷①：
     * "评论区里下滑后再上滑，被判定为想进刷视频模式"）。
     *
     * ## 缺陷根因（本轮读源码定位，W4）
     * 页面级手势是 [VideoScreen] 的 `pullDownToFullscreen`，它的门控参数原本是
     * `{ detailListState.canScrollBackward }` —— 而 `detailListState` 是**简介列表**的状态。
     * 评论列表（`commentListState`）与详情页 pager（`pagerState`）都创建在
     * [VideoDetailPage] **内部**，VideoScreen 拿不到。于是在评论页上，门控看的是
     * **简介列表**滚到哪：只要简介列表停在顶部，评论页里的下滑就会被判成
     * "列表已到顶 → 接管" ⇒ 进刷视频。这与用户描述完全一致。
     *
     * ## 修法（分层仲裁，零回归）
     * 由 [VideoDetailPage] 把"当前是不是简介页"上报上来，门控改成
     * `!introVisible || detailListState.canScrollBackward`：
     * 评论页上一律返回 true = 全部让给列表 ⇒ 评论里任何滚动/边界情形都不进刷视频
     * （要点②③）；简介页上的判据**逐字未变** ⇒ 原入口零回归（验收②）。
     *
     * 断言锚在**调用/表达式形态**上（W18/W20/W21）。
     */
    @Test
    fun commentPageNeverArmsThePageLevelPullDownGesture() {
        val screen = codeOf("VideoScreen.kt")
        val detail = codeOf("detail${java.io.File.separator}VideoDetailPage.kt")
        assertTrue(
            "手势门控必须**分层**：`!detailIntroPageVisible || detailListState.canScrollBackward`；" +
                "缺了前半段，评论页就会退回「看简介列表滚到哪」的老缺陷。",
            countMatching(screen, """!detailIntroPageVisible \|\| detailListState\.canScrollBackward""") == 1
        )
        assertTrue(
            "零回归（验收②）：简介页的判据必须仍然是 `detailListState.canScrollBackward`，" +
                "且**只允许出现这一处** —— 多出来的第二处就是没分层的旧门控又回来了。",
            countMatching(screen, """detailListState\.canScrollBackward""") == 1
        )
        assertTrue(
            "页面级手势本身必须还在（本次只改门控，不删手势）。",
            countMatching(screen, """private fun Modifier\.pullDownToFullscreen\(""") == 1
        )
        assertTrue(
            "「当前是不是简介页」必须由 VideoDetailPage 依据 `pagerState.currentPage` 上报" +
                "（pager 与评论列表都在它内部，VideoScreen 猜不出来）。",
            countMatching(detail, """onIntroPageVisibleChange\(page == 0\)""") == 1 &&
                countMatching(detail, """snapshotFlow \{ pagerState\.currentPage \}""") == 1 &&
                countMatching(screen, """var detailIntroPageVisible by remember \{ mutableStateOf\(true\) \}""") == 1 &&
                countMatching(screen, """onIntroPageVisibleChange = \{ detailIntroPageVisible = it \}""") >= 1
        )
    }

    /**
     * SX-09（用户需求⑨）：互动栏点赞按钮的「未赞 / 已赞」必须有**两种视觉**。
     *
     * 改动前两态共用 `Icons.Rounded.ThumbUp`、只靠 `tint` 区分 —— 深色主题下低饱和
     * 主题色与白色的差别不够醒目。现在按 `liked` 在**描边 / 填充**之间切换，
     * 与同栏收藏按钮（`Star` / `StarBorder`）同款语言。
     *
     * 同时钉住另外三条实质约束（这些**本来就已经成立**，本条守卫防回潮）：
     * ③ 数字跟着变（`likeDelta` ±1）+ 失败有用户可见提示（`emitActionError`）；
     * ④ `liked` 来自**单一真值源**（详情 `userState`），不在本地另存一份；
     * ⑤ 写操作只允许在调试包真机验证 —— 属设备侧纪律，代码侧不涉及。
     *
     * 断言锚在**表达式/调用形态**上（W18/W20/W21）。
     */
    @Test
    fun swipeRailLikeButtonDistinguishesLikedStateVisually() {
        val pane = codeOf("player/VideoPlayerPane.kt")
        val pager = codeOf("player/PortraitSwipePager.kt")
        val vm = codeOf("VideoViewModel.kt")
        assertTrue(
            "SX-09 要点②：点赞图标必须按 liked 在**描边/填充**之间切换（不能只换颜色）——" +
                "与同栏收藏按钮的 Star/StarBorder 同款语言。",
            countMatching(
                pane,
                """icon = if \(liked\) Icons\.Rounded\.ThumbUp else Icons\.Outlined\.ThumbUp,"""
            ) == 1
        )
        assertTrue(
            "颜色维度必须**同时保留**（描边/填充 + 主题色叠加，不是二选一）。",
            countMatching(
                pane,
                """tint = if \(liked\) MaterialTheme\.colorScheme\.primary else Color\.White,"""
            ) >= 1
        )
        assertTrue(
            "SX-09 要点④：liked 必须是**单一真值源** —— 由调用点传入，组件内不得另存一份本地 liked。" +
                "（**形态更新 · VK-01（2026-10-04 真机）**：真值源由 `playbackState.detail.userState` " +
                "改为 `interaction.userState`。二者不等价：前者是服务器快照、互动写路径从不更新它，" +
                "用它就会退化成「点赞成功但图标/数字不动」；`_interaction` 与 `videoState.ids` 同源同步，" +
                "永远对应当前页。守卫意图不变：仍是「单一真值源 + 组件不自持状态」。）",
            countMatching(pager, """liked = interaction\.userState\?\.liked""") == 1 &&
                countMatching(pane, """liked: Boolean,""") == 1
        )
        assertTrue(
            "SX-09 要点③：数字要跟着变（likeDelta ±1），失败要有用户可见提示。",
            countMatching(vm, """likeDelta = it\.likeDelta \+ if \(target\) 1 else -1""") == 1 &&
                countMatching(vm, """emitActionError\(it, "点赞失败"\)""") == 1
        )
    }

    /**
     * SX-07（用户需求⑦「点评论要有"展开"的缓动曲线动画」）：评论卡片的展开/收起必须有缓动，
     * 且动效**全部来自 token**。
     *
     * 落点确认（W4）：用户说的"展开"是**评论面板的出现**，因为 `feature/comment` 里
     * 根本没有评论正文的展开/收起功能（`CommentReplyCard` 的 `expanded` 是 `DropdownMenu` 的
     * 参数，不是正文折叠）。改动前面板是 `if (showComments)` 的**瞬时出现**。
     *
     * 断言锚在**调用/表达式形态**上（W18/W20/W21）：
     * ① 面板必须走 AnimatedVisibility 且绑定 showComments（不再是 if 瞬时切换）；
     * ② 时长与曲线必须取自 LocalAnimations（long/medium + emphasized/standard），
     *    不许手写字面量时长；
     * ③ 卡片走 slideInVertically/slideOutVertically（高度本来就固定占比 ⇒ 无高度动画、不抖邻居）；
     * ④ 不得为此新增 animate*AsState / rememberInfiniteTransition（FrameBudgetLintTest 棘轮口径）。
     */
    @Test
    fun commentCardExpandHasEasedAnimationFromTokens() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "SX-07：面板不能再是 `if (showComments)` 的瞬时出现/消失，必须走 AnimatedVisibility " +
                "（遮罩 + 卡片两处，都绑 showComments）。",
            countMatching(pager, """AnimatedVisibility\(""") >= 2 &&
                countMatching(pager, """visible = showComments,""") == 2 &&
                countMatching(pager, """if \(showComments\) \{""") == 0
        )
        assertTrue(
            "SX-07 要点②：时长与曲线必须来自 LocalAnimations 的 token ——" +
                "进入用 long + 强调曲线，退出与遮罩用 medium + 标准曲线；不许手写 tween 字面量时长。",
            countMatching(
                pager,
                """tween\(commentAnimations\.long, easing = commentAnimations\.emphasized\)"""
            ) == 2 &&
                countMatching(
                    pager,
                    """tween\(commentAnimations\.medium, easing = commentAnimations\.standard\)"""
                ) == 4
        )
        assertTrue(
            "SX-07 要点③⑤：卡片从下方滑入/滑出（高度本来就固定占比 ⇒ 全程无高度变化，" +
                "不会先塌后长、不抖相邻内容）。",
            countMatching(pager, """slideInVertically\(""") == 1 &&
                countMatching(pager, """slideOutVertically\(""") == 1
        )
        assertTrue(
            "SX-07 要点①：不许为这条动画新增 animate*AsState / rememberInfiniteTransition。",
            countMatching(pager, """animate[A-Za-z]*AsState\(""") == 0 &&
                countMatching(pager, """rememberInfiniteTransition\(""") == 0
        )
    }

    /**
     * SX-05：横屏全屏的"沉浸"三态（进入即隐藏 / 点击**与拖动**唤出 / 数秒无操作再隐藏），
     * 且超时**不许是裸字面量**。
     *
     * 本轮读源码核实：**大部分本来就成立** ——
     * ① `fullOn` 时系统栏已走 immersive（`VideoScreen.kt:402-414`：`hide(systemBars())` +
     *    `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，退出时 `show(...)` 恢复）；
     * ② 详情页内容在 `fullOn` 分支根本不组合（`VideoScreen.kt:493` 的 `when` 第一支）；
     * ③ 顶栏/控制条由 `showCtrl` 门控（pane 的 `AnimatedVisibility(visible = showCtrl)`）；
     * ④ 进入全屏时 `showCtrl` 初值就是 `false`（换分支 = 换组合，remember 重置）。
     * 所以本项**真正缺的只有两条**，本题看守它们：
     * · 超时是裸 `delay(2_000)` ⇒ 收进具名常量；
     * · **拖动不唤出**控件（点击唤出早有）⇒ 补上。
     */
    @Test
    fun playerChromeImmersivePolicyIsNamedAndRevealsOnDrag() {
        val pane = codeOf("player/VideoPlayerPane.kt")
        assertTrue(
            "SX-05 要点①：自动隐藏的超时必须走具名常量（`PlayerChromePolicy.AUTO_HIDE_MS`），" +
                "不许再出现裸的 `delay(2_000)`。",
            countMatching(pane, """delay\(PlayerChromePolicy\.AUTO_HIDE_MS\)""") == 1 &&
                countMatching(pane, """delay\(2_000\)""") == 0
        )
        assertTrue(
            "SX-05 要点①：显隐判据必须收在纯策略里（可纯 JVM 单测），不再在 effect 里散成一串 &&。",
            countMatching(pane, """PlayerChromePolicy\.shouldAutoHide\(""") == 1 &&
                countMatching(pane, """PlayerChromePolicy\.shouldRevealOnGesture\(""") == 1
        )
        assertTrue(
            "SX-05 要点②：弹层在场时必须把控件留着（退出/返回可用性）—— 判据要把 dialog/sheet 传进去。",
            countMatching(pane, """dialogOpen = activeDialog != null,""") == 1 &&
                countMatching(pane, """sheetOpen = showPlaybackSheet""") == 1
        )
        assertTrue(
            "SX-05 要点④：竖屏刷视频的常驻信息层不受影响 —— 本项不得动 " +
                "`PortraitSwipePager` 的 chrome 静置（防烧屏）策略。",
            countMatching(
                codeOf("player/PortraitSwipePager.kt"),
                """SwipeChromeBurnInPolicy\.chromeAlpha\("""
            ) == 1
        )
        assertTrue(
            "SX-05 要点③：与 SX-03 的悬浮按钮互斥 —— 进全屏时它必须淡出（随 fullscreenMorph 归零）。",
            countMatching(
                codeOf("VideoScreen.kt"),
                """alpha = 1f - fullscreenMorph\.value"""
            ) >= 2
        )
    }

    /**
     * SX-08（用户需求⑧「下滑切下一个视频要有缓动曲线动画」）：换页的**吸附动画**
     * 必须显式配置、曲线走 token，且不破坏既有的预组合与越界阻尼约束。
     *
     * 改动前的实际状态（读源码核实）：`PortraitSwipePager` 的 `VerticalPager`
     * **没有** `flingBehavior`（全文件 0 命中）⇒ 用的是 Compose 框架默认 spring：
     * ① 曲线不可控、也不在本仓的动效语言里；② **不理会**「动效速度 = 关」
     * （`reduceMotion`）—— 关掉动效的用户翻页照样看得到一整段动画。
     * 本轮把**吸附那一段**显式化（时长 `animations.long`、曲线 `animations.emphasized`），
     * 只换吸附、不碰"翻到哪一页"的判据。
     */
    @Test
    fun pagerSnapAnimationIsExplicitTokenDriven() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "SX-08：换页吸附必须显式给 `flingBehavior = PagerDefaults.flingBehavior(...)`。",
            countMatching(pager, """flingBehavior = PagerDefaults\.flingBehavior\(""") == 1 &&
                countMatching(pager, """state = pagerState,""") >= 1
        )
        assertTrue(
            "SX-08 要点②：吸附时长与曲线必须走 token（`animations.long` + 主题强调曲线），" +
                "不许手写字面量时长。",
            countMatching(pager, """snapAnimationSpec = tween\(""") == 1 &&
                countMatching(pager, """durationMillis = animations\.long,""") == 1 &&
                countMatching(pager, """easing = animations\.emphasized""") == 1
        )
        assertTrue(
            "SX-08 要点③④：**只换吸附** —— 预组合与越界阻尼必须原样保留" +
                "（`beyondViewportPageCount = 1`；reduceMotion 时不接阻尼连接）。",
            countMatching(pager, """beyondViewportPageCount = 1""") == 1 &&
                countMatching(
                    pager,
                    """if \(reduceMotion\) Modifier else Modifier\.nestedScroll\(overScrollConnection\)"""
                ) == 1 &&
                countMatching(pager, """SwipePageTransformPolicy\.overScrollTranslation\(""") == 1
        )
    }

    /**
     * VK-01（SX-09 的验收补齐）：互动栏的点赞/收藏**显示**必须读**互动状态**。
     *
     * 真机根因（2026-10-04，打点确认）：互动**写**路径只更新 VM 的 `_interaction`
     * （`toggleLike`/收藏都走 `updateInteractionIfCurrent`），而 `PortraitSwipePager` 里
     * `playbackState` 就是 VM 的 `videoState`，其 `detail.userState` / `detail.stat`
     * 是**服务器快照**、写路径从不碰它 ⇒ `setLike OK` 之后图标与计数一动不动，
     * 用户看到的就是「点了没反应」（原话：亮了也不知道、取消赞了也看不出区别）。
     *
     * 修法：按**详情页同一口径**读互动状态 —— `liked = userState.liked`、
     * 计数 = `actionCount(stat.xxxValue, xxxDelta)`（`VideoDetailSummary.kt:352-375` 同式）。
     * 本题同时**钉住旧形态不许回来**。
     */
    @Test
    fun swipeRailReadsInteractionStateNotOnlyTheServerSnapshot() {
        val pager = codeOf("player/PortraitSwipePager.kt")
        assertTrue(
            "VK-01：必须订阅互动状态，否则下面的读取没有真值来源。",
            countMatching(
                pager,
                """val interaction by viewModel\.interaction\.collectAsStateWithLifecycle\(\)"""
            ) == 1
        )
        assertTrue(
            "VK-01：点赞/收藏的选中态必须优先取互动状态（写路径真正更新的那一份）。",
            countMatching(pager, """liked = interaction\.userState\?\.liked""") == 1 &&
                countMatching(pager, """faved = interaction\.userState\?\.faved""") == 1
        )
        assertTrue(
            "VK-01：计数必须是「服务器值 + 本地增量」（与详情页 actionCount 同口径），" +
                "否则点赞成功也不会变数字。",
            countMatching(pager, """actionCount\(it, interaction\.likeDelta\)""") == 1 &&
                countMatching(pager, """actionCount\(it, interaction\.favDelta\)""") == 1
        )
        assertTrue(
            "VK-01 回归防护：旧的「只读 playbackState 快照」形态不得回来" +
                "（点赞/收藏各一处，退化了就是「点了没反应」）。",
            countMatching(pager, """liked = playbackState\.detail\?\.userState\?\.liked == true""") == 0 &&
                countMatching(pager, """faved = playbackState\.detail\?\.userState\?\.faved == true""") == 0
        )
    }

    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { it.isNotBlank() && re.containsMatchIn(it) }
    }
}
