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
            "翻页监听必须经 requestedKey（SwipeEntry.key，bvid 优先 / aid 兜底）防目标震荡" +
                "（直接比 currentKey 会在取流完成前被快速往返翻页打转），" +
                "翻页与迟到结果对齐各走一处 onSwipeTo。",
            countMatching(pager, """requestedKey""") >= 3 &&
                countMatching(pager, """onSwipeTo\(entry\)""") == 2
        )
        assertTrue(
            "「哪一页归播放器」必须按**内容身份**判断（isCurrentVideo），不许拿 entry.key " +
                "和 currentKey 比字符串——队列条目的 key 是建队时算的（relate 卡没有 bvid " +
                "→ `aid:…`），而 videoState.ids 在取流/详情返回后会被服务端补齐的 bvid " +
                "整体覆盖，字符串会在「取流完成」的一瞬间翻转 → 播放器页落空、翻页后新视频" +
                "永不起播（真机阻塞问题的根因）。",
            countMatching(pager, """isCurrentVideo\(""") >= 3 &&
                countMatching(pager, """entry\.key == currentKey""") == 0
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
            "同条目短路也必须 aid 优先（与 isCurrentVideo 同一判据），否则「同一个视频的 " +
                "aid-only 目标 vs 已补齐 bvid 的栈顶」会被判成不同目标 → 白白从重开播一次。",
            countMatching(vm, """current\.aid\s*==\s*entry\.target\.aid""") == 1
        )
        assertTrue(
            "全屏分支必须用 swipeSessionActive + 锚点判据启用 pager；会话开启必须调 " +
                "beginSwipeSession；切换必须接 viewModel::openSwipeTarget。",
            countMatching(screen, """swipeSessionActive""") >= 2 &&
                countMatching(screen, """beginSwipeSession\(""") == 1 &&
                countMatching(screen, """viewModel::openSwipeTarget""") == 1 &&
                countMatching(screen, """PortraitSwipePager\(""") == 1
        )
        assertTrue(
            "上下滑切换必须替换栈顶（dropLast(1) + target）而不是压栈，" +
                "且队列接力必须走 appendSwipeEntries（去重 + UP 主多样性）。",
            countMatching(vm, """fun\s+openSwipeTarget\(entry:\s*SwipeEntry\)""") == 1 &&
                countMatching(vm, """dropLast\(1\)\s*\+\s*entry\.target""") == 1 &&
                countMatching(vm, """appendSwipeEntries\(""") >= 2
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
        assertTrue(
            "story 会话必须由 bootstrapStorySession 负责（story 流优先），补货必须走 " +
                "loadMoreSwipeQueue（推荐流分页接力，与详情全屏共用队列）。",
            countMatching(vm, """fun\s+bootstrapStorySession\(seed:\s*VideoTarget\)""") == 1 &&
                countMatching(vm, """fun\s+loadMoreSwipeQueue\(""") == 1 &&
                countMatching(vm, """swipeEntryFromStory""") >= 1 &&
                countMatching(vm, """swipeEntryFromFeedItem""") >= 1
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
        assertTrue(
            "「等首帧」必须是**粘滞**状态：换视频（currentKey）进入等待，" +
                "直到这一支真的出帧才退出。" +
                "直接取 `!hasRenderedFirstFrame` 的瞬时值是错的 —— 换视频后的头几十毫秒里" +
                "它仍是上一支的 true（finishVideoPlayback 先发布新 ids、后 stopForReuse 归零），" +
                "加载层会先卸载再挂上，中间那一帧就是真机看到的「黑屏 + 大暂停键」。" +
                "**失败不再撤掉加载层**（本轮 C10 改动）：撤掉会露出「纯黑 + 中央播放键」像卡死，" +
                "而错误详情只在播放器侧栏里、连播页面上看不到；改成留着加载层并切成错误态 + 重试。",
            countMatching(pager, """var\s+waitingKey\s+by\s+remember""") == 1 &&
                countMatching(pager, """val\s+waitingFirstFrame\s*=\s*waitingKey != null""") == 1 &&
                countMatching(pager, """LaunchedEffect\(currentKey\)""") == 1 &&
                countMatching(
                    pager,
                    """if \(firstFrameRendered\) waitingKey = null"""
                ) == 1
        )
        val pane = codeOf("player/VideoPlayerPane.kt")
        assertTrue(
            "加载层必须是播放页 Box 的**最后一个子节点**：插在控件之前时，" +
                "中央那颗 72dp 播放键会压在它上面（真机反馈「大大的暂停按钮」）。" +
                "判据只允许用 swipeWaitingFirstFrame —— 不许再与 swipePagingActive 相与：" +
                "story 入口在锚点就位前走的是「单播放器」分支（没有 pager），" +
                "那条路上 swipePagingActive 恒 false，相与等于那段黑屏没有加载层。" +
                "失败态必须真的接上（swipeFailed / onRetrySwipe 传进加载层，且加载层里有重试分支）。",
            countMatching(pane, """private fun SwipeWaitVeil\(""") == 1 &&
                countMatching(pane, """visible = swipeWaitingFirstFrame,""") == 1 &&
                countMatching(pane, """failed = swipeFailed,""") == 1 &&
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
            "加载层**不许再铺封面**（上滑时封面页已经把那张图给用户看过了，再铺一次就是" +
                "「还是有预览图」），也不许让整层可点 —— 它要盖住控件，但不能把上下滑翻页的" +
                "手势一起吃掉（失败态里那个「重试」按钮是唯一的例外，且只在失败分支里）。",
            countMatching(pane, """coverUrl: String\?""") == 0 &&
                countMatching(pane, """BiliAsyncImage\(""") == 0 &&
                blockBody(pane, "private fun SwipeWaitVeil(")
                    .none { it.contains("pointerInput") }
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
     * 会话内**常驻细进度条**（C10 信息层第一阶段）。
     *
     * 上下滑会话里控件 2 秒自动隐藏，用户没有任何"这支还剩多久"的线索；
     * BiliPai 的竖屏页底部常驻一条细进度（`PortraitFullscreenOverlay.kt:387-394`）。
     * 这里钉三件事：① 只在会话内且不在等首帧时出现（等首帧时整屏是加载层）；
     * ② 尺寸只用**比例**（设计 token lint 有 `.dp` 棘轮，新增字面量会判红）；
     * ③ 它必须排在加载层**之前**（加载层是最后一个子节点，要盖住它）。
     */
    @Test
    fun swipeSessionShowsPersistentProgressLine() {
        val pane = codeOf("player/VideoPlayerPane.kt")
        assertTrue(
            "会话内常驻细进度条必须存在、且判据是 `swipePagingActive && !swipeWaitingFirstFrame`" +
                "（等首帧时整屏是加载层，进度条浮在上面会显得脏）。",
            countMatching(pane, """if \(swipePagingActive && !swipeWaitingFirstFrame\) \{""") == 1 &&
                countMatching(pane, """private fun SwipeProgressLine\(""") == 1 &&
                countMatching(pane, """SWIPE_PROGRESS_TRACK_FRACTION""") >= 2
        )
        assertTrue(
            "进度条尺寸必须走比例而不是 dp 字面量（DesignTokenLintTest 的棘轮会判红），" +
                "也不许带 pointerInput/clickable —— 它只是指示器，不能吃掉手势。",
            blockBody(pane, "private fun SwipeProgressLine(").let { body ->
                body.none { it.contains(".dp") } &&
                    body.none { it.contains("pointerInput") || it.contains("clickable") }
            }
        )
        assertTrue(
            "进度条必须排在加载层之前：加载层是 Box 的最后一个子节点，" +
                "顺序反了会让进度条浮在加载动画上面。",
            run {
                val lineAt = pane.indexOfFirst {
                    it.contains("SwipeProgressLine(") && !it.contains("private fun")
                }
                val veilAt = pane.indexOfFirst { it.trimStart().startsWith("SwipeWaitVeil(") }
                lineAt >= 0 && veilAt >= 0 && lineAt < veilAt
            }
        )
    }

    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { it.isNotBlank() && re.containsMatchIn(it) }
    }
}
