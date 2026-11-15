package com.naaammme.bbspace.feature.video.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.naaammme.bbspace.core.common.ShareUtils
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.core.designsystem.component.IOSActionSheetAction
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.model.PlayerRepeatMode
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.swipe.SwipeBackPolicy
import com.naaammme.bbspace.core.swipe.SwipeChromeBurnInPolicy
import com.naaammme.bbspace.core.swipe.SwipeChromeOwner
import com.naaammme.bbspace.core.swipe.SwipeChromePolicy
import com.naaammme.bbspace.core.swipe.SwipeEntry
import com.naaammme.bbspace.core.swipe.SwipeErrorPolicy
import com.naaammme.bbspace.core.swipe.SwipePageTransformPolicy
import com.naaammme.bbspace.core.swipe.SwipePrefetchPolicy
import com.naaammme.bbspace.core.swipe.SwipeQueueTail
import com.naaammme.bbspace.core.swipe.SwipeWaitPolicy
import com.naaammme.bbspace.core.swipe.model.SwipeId
import com.naaammme.bbspace.core.swipe.shouldAutoAdvanceToNextSwipePage
import com.naaammme.bbspace.core.swipe.shouldExtendSwipeQueue
import com.naaammme.bbspace.core.swipe.swipeKey
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.feature.video.VideoViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 竖屏全屏「上下滑切换视频」pager（BiliPai 上游 `PortraitVideoPager` 的 bbspace 化）。
 *
 * ## 结构
 * - 页 0 = 锚点（进入全屏那一刻的视频），页 1..n = 相关推荐接力流（[SwipeEntry]）；
 * - **播放器唯一**：只有 [SwipeEntry.isCurrentVideo] 命中的那一页组合 [VideoPlayerPane]
 *   （其内部的 PlayerView 经 `PlayerViewTargetBinder` 绑到进程级播放器），其余页渲染
 *   静态封面 + 信息区——页数再多也只有一份播放器节点；
 * - 播放器页的切换时机由当前播放的 ids 驱动：手势翻页时旧页仍持有播放器
 *   （用户看得见它滑走），新页先显示封面；新流到达瞬间播放器页身份跳到当前页
 *   ——组件的销毁/重建都发生在用户看不到的页上，不会闪黑。
 *
 * ⚠️ **页身份必须按内容比，不能按 key 字符串比**：队列条目的 key 建队时算（relate 卡
 * 没有 bvid → `aid:…`），而 `videoState.ids` 在取流/详情返回后会被服务端补齐的 bvid
 * 整体覆盖 → 字符串在"取流完成"的一瞬间翻转，播放器页落空、新视频永不起播
 * （真机 bug：可滑动但无法播放下一个）。判据统一走 [SwipeEntry.isCurrentVideo]。
 *
 * ## 提前起播
 * `VerticalPager` 的 `currentPage` 在拖动跨过 snap 中点时就会变化（不必等手势结束），
 * 监听它立即 [VideoViewModel.openSwipeTarget] —— 手势还在进行时取流已发出，停稳后通常已在起播。
 *
 * ## 与既有管线的复用关系
 * 切视频走 [VideoViewModel.openSwipeTarget]（替换栈顶 + `openVideo`），取流、弹幕重载、
 * 播放历史上报、清晰度选择全部复用详情页既有链路；翻回页 0 = 切回锚点视频。
 * 队列耗尽（推荐为空）自然到头，不循环。
 *
 * ## 2026-10-19 第 34 轮重构（用户：「刷视频模式全是bug，请全面重构」）
 * 本文件从 **1517 行 / 主函数 1114 行 / 15 个 LaunchedEffect** 收敛为
 * **主函数 ≤300 行 / effect 恰好 2 个**。拆出去的四个文件同包：
 * - [SwipeSessionLeaves]：封面页 / 队列尾提示 / 横屏 FAB（零状态的纯展示叶子）
 * - [SwipeSessionChrome]：底部信息层 / 互动栏 / 评论卡 / 收藏面板（常驻 chrome）
 * - [SwipeMoreSheet]：「更多」面板 + 拉黑确认（对话框语言统一到 iOS 原语）
 * - [PlaybackPageMode]：页面模式状态机（story / 详情全屏 / swipe 的单一真源）
 */

/**
 * 相邻页预取的**页面级去重**（唯一闸门）。
 *
 * 重构前 `lastPrefetchPage` 只被"偏移流"那一个 effect 读写，而另一个 effect
 * （`LaunchedEffect(pages.size, currentPage)`）**完全不看它**就直接预取 ——
 * 同一次补货会打两次请求。现在两个触发条件都先过这里。
 */
private class SwipePrefetchGate {
    private var lastPage = Int.MIN_VALUE

    /** @return true = 该页值得预取（首次），false = 已预取过。 */
    fun shouldPrefetch(page: Int): Boolean {
        if (page == lastPage) return false
        lastPage = page
        return true
    }

    /** 会话重置 / 换锚点时清空。 */
    fun reset() {
        lastPage = Int.MIN_VALUE
    }
}

/** 「停稳」判定用的偏移容差。理由见 [SwipePrefetchPolicy.dragTargetPage] 的注释。 */
private const val SETTLED_OFFSET_EPSILON = 0.02f

private const val TAG = "PortraitSwipe"

/**
 * 会话底部信息块（@名/标题/话题）的底部内边距：抬到系统手势导航条之上。
 *
 * 2026-10-02：这条 clearance 原先由被删除的底部细进度条提供（它的底部内边距是
 * `BiliSpacing.xxxl`）；进度条没了之后，若不给信息层补上，话题行会贴着屏幕下沿、
 * 与手势条打架。值沿用同一档 token，位置改由信息层自己承担。
 */
internal val SwipeBottomInfoInset = BiliSpacing.xxxl

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PortraitSwipePager(
    anchor: SwipeEntry,
    queue: List<SwipeEntry>,
    currentBvid: String?,
    currentAid: Long,
    detailTitle: String?,
    detailLoading: Boolean,
    viewModel: VideoViewModel,
    isFull: Boolean,
    onToggleFull: () -> Unit,
    onBackClick: () -> Unit,
    onGoHome: () -> Unit,
    /** 进 UP 空间（SW-20：互动栏头像 / 底部信息层 @名 的同一出口；`null` 时不接）。 */
    onOpenSpace: ((SpaceRoute) -> Unit)? = null,
    onSwipeTo: (SwipeEntry) -> Unit,
    onNeedMore: () -> Unit = {},
    /** 相邻页预取（页号）：拖动跨过 25% 偏移 / 停稳时各触发一次。 */
    onPrefetch: (Int) -> Unit = {},
    /** 取流/解码失败后的重试（沿用同一目标重开一次播放）。 */
    onRetryPlayback: () -> Unit = {},
    /** 队列尾部状态：驱动"正在为你找更多视频…" / "已经是最后一个了"。 */
    tailState: SwipeQueueTail = SwipeQueueTail.Idle,
    /** 当前是否已被用户手动锁成横屏。 */
    landscapeForced: Boolean = false,
    /** 手动横屏开关；由 VideoScreen 落到 requestedOrientation 上。 */
    onToggleLandscape: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ── 页数据 ───────────────────────────────────────────────────────────────
    // **索引语义的唯一定义在 `SwipeSessionController.pages()`**（D11）：
    // 拼法就是 `listOf(anchor) + queue`，VM 的预取也读它。这里仍然按同一拼法取一份
    // **组合期快照**（Compose 侧读快照是对的：变化由 `anchor` / `queue` 两个 key 驱动重组），
    // 但**不允许在别处另写一份拼法** —— 两处各拼一份时，任何一边改动都会让"pager 的
    // 第 N 页"与"预取的第 N 页"指向不同视频，而预取错了**没有任何症状**
    // （只是那一页滑过去要多等一拍）。
    val pages = remember(anchor, queue) { listOf(anchor) + queue }
    // pageCount 经 rememberUpdatedState 间接读：rememberPagerState 会记住首次传入的
    // lambda，若让它直接捕获 pages，队列追加后 pager 拿到的仍是旧页数——「无限刷」失效。
    val pageCountState = rememberUpdatedState(pages.size)
    val pagerState = rememberPagerState { pageCountState.value }
    val scope = rememberCoroutineScope()
    // 「更多」面板的复制链接 / 系统分享要用 Context。**必须在这里取**（Composable 作用域），
    // 不能写进 `onCopyLink = { … }` 这类非 @Composable lambda 里 —— 那些 lambda 在
    // 事件回调时才执行，读 `LocalContext.current` 会编译失败（第 34 轮拆分时踩过：
    // `@Composable invocations can only happen from the context of a @Composable function`）。
    // 变量名用 `appContext` 而不是 `context`：后者在 Compose 作用域里会与
    // `context()`（LayoutScope 的密度/布局上下文访问器）撞名，编译报
    // 「Function invocation 'context(...)' expected」。
    val appContext = androidx.compose.ui.platform.LocalContext.current
    // pager 认定的「应播放页」：以它为准发切换请求，而不是拿 currentId 反向比较——
    // 后者在取流完成前仍是旧值，快速往返翻页时会造成目标震荡。
    val currentId = SwipeId.of(currentBvid, currentAid)
    var requestedKey by remember { mutableStateOf(anchor.id) }
    // 防烧屏的"最近交互时刻"：翻页回调与评论/收藏面板都要写它。
    var lastChromeInteractionMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }

    // ── 控制层归属（第 35 轮 P0-1「控制层唯一化」）──────────────────────────────
    //
    // 真机 bug 的根因：会话层 chrome（底部信息 + 互动栏 + 横屏胶囊）与播放器层 chrome
    // （pane 的顶栏 + 控制条）**各持一个显隐布尔、互不感知** ⇒ 点一下屏幕就两套同时在线，
    // 屏幕上出现两个 ⋮、两套顶部图标、两个退出入口（`docs/evidence-r35/27-tap-once.png`）。
    //
    // 现在"谁该显示"只有一个真源：本状态 + `SwipeChromePolicy` 的纯迁移。
    // pane 不再自持 `showCtrl`（它读 `playerChromeVisible`、写只经 `onPlayerChromeRequest`），
    // 会话层自己则只在 `storyVisible` 时组合。
    var chromeOwner by remember { mutableStateOf(SwipeChromePolicy.initial(landscapeForced)) }
    LaunchedEffect(landscapeForced) {
        // 方向变化 = 模式切换（横屏清屏 / 竖屏回会话层），不是"顺手隐藏"
        val next = SwipeChromePolicy.onOrientationChanged(landscapeForced)
        if (next != chromeOwner) {
            chromeOwner = next
            DiagnosticLog.i(TAG, "chrome: landscape=$landscapeForced -> $next")
        }
    }
    val playerChromeVisible = SwipeChromePolicy.playerVisible(chromeOwner)
    val onPlayerChromeRequest: (Boolean) -> Unit = { wanted ->
        val next = SwipeChromePolicy.onChromeRequested(wanted, landscapeForced)
        if (next != chromeOwner) {
            chromeOwner = next
            DiagnosticLog.i(TAG, "chrome: request=$wanted landscape=$landscapeForced -> $next")
        }
    }

    // ── effect 1/2：翻页落定 + 补货 + 预取（**唯一**读 currentPage 的地方）──────────
    //
    // 重构前这里是 4 个 effect，且其中两个以整个 `pages` List 为 key：
    //   197: LaunchedEffect(pagerState, pages)      ← 翻页派发 + 补货
    //   231: LaunchedEffect(pages.size, currentPage)← 预取（**不看** lastPrefetchPage）
    //   238: LaunchedEffect(pagerState, pages)      ← 偏移驱动的预取（自己写 lastPrefetchPage）
    //   → 每次补货 `pages` 换新 List，四个 effect 全部取消重启；`distinctUntilChanged`
    //     的内部状态归零后会**重放当前页**，于是 `onSwipeTo` 可能被重复派发。
    //
    // 现在合成**一个** effect：
    //   · key = (currentId, pages.size)。`pages.size` 是整数，补货时只有真的**变长**才重启；
    //     内容身份（currentId）变化时才重启——**绝不**以整个 List 为 key。
    //   · 页数据通过 `latestPages`（rememberUpdatedState）在**回调触发那一刻**读最新值，
    //     所以"补货落地但 effect 不重启"也不会读到旧队列。
    //   · 预取的两个触发条件（页数变化 / 偏移跨阈值）都先过**唯一**闸门 [SwipePrefetchGate]。
    val latestPages by rememberUpdatedState(pages)
    val prefetchGate = remember { SwipePrefetchGate() }
    LaunchedEffect(currentId, pages.size) {
        prefetchGate.reset()
        // 首次进入：第 0 页的邻居要立刻预热（重构前靠"带 pages 的 effect 重启"补上，
        // 现在改为显式首帧预取 —— 语义一样，但不再重启 effect）。
        if (prefetchGate.shouldPrefetch(pagerState.currentPage)) {
            onPrefetch(pagerState.currentPage)
        }
        snapshotFlow {
            pagerState.currentPage to pagerState.currentPageOffsetFraction
        }.collect { (page, offset) ->
            val entry = latestPages.getOrNull(page)
            if (entry != null) {
                // ── 翻页落定：状态机记账 + 防烧屏 + 切播（**唯一**派发点）──
                viewModel.onSwipePageChanged(page)
                lastChromeInteractionMs = android.os.SystemClock.elapsedRealtime()
                if (entry.id != requestedKey) {
                    requestedKey = entry.id
                    DiagnosticLog.i(
                        TAG,
                        "swipe: page=$page key=${entry.legacyKey()} aid=${entry.target.aid} bvid=${entry.bvid}"
                    )
                    onSwipeTo(entry)
                }
                // 补货判据（BiliPai loadMoreStories 同款）：进入倒数第 2 页区间就拉
                // 下一批推荐流追加进队列；防抖由调用方（ViewModel）负责。
                if (shouldExtendSwipeQueue(committedPage = page, totalPages = latestPages.size)) {
                    onNeedMore()
                }
            }
            // ── 相邻页预取：拖动跨过阈值 或 已停稳，两个条件共用一个去重闸门 ──
            val target = SwipePrefetchPolicy.dragTargetPage(
                currentPage = page,
                offsetFraction = offset,
                lastPageIndex = pageCountState.value - 1
            ) ?: page.takeIf { abs(offset) <= SETTLED_OFFSET_EPSILON }
                ?: return@collect
            if (prefetchGate.shouldPrefetch(target)) onPrefetch(target)
        }
    }

    // ── effect 2/2：取流完成后的**页对齐** ───────────────────────────────────
    //
    // 正常路径上 pager 停在的页就是当前播放的视频，直接同步 requestedKey；
    // 若是快速往返翻页的迟到结果（pager 停在用户已离开的页），以用户所在页为准再发一次
    // 切换（openVideo 幂等）——否则 pager 会停在"播放器在看不见的页"的状态。
    //
    // key 只有 (currentId, settledPage)：**不含整个 pages List**。重构前这里是
    // `LaunchedEffect(currentId, pages)`，补货就会重启并可能重复派发 onSwipeTo。
    // `settledPage` 只在真正落定时变化（不是每帧），所以这个 effect 极少重启。
    LaunchedEffect(currentId, pagerState.settledPage) {
        val entry = latestPages.getOrNull(pagerState.currentPage)
        if (entry == null) {
            requestedKey = currentId
        } else if (entry.isCurrentVideo(currentBvid, currentAid)) {
            requestedKey = entry.id
        } else {
            // 迟到的对齐：只在目标真的不同才派发（与上面同一去重语义，避免双发）
            if (requestedKey != entry.id) {
                requestedKey = entry.id
                onSwipeTo(entry)
            }
        }
        // 播放器归属诊断：页身份失配（症状="翻页后只有封面、不起播"）的唯一可观测证据。
        val owner = latestPages.indexOfFirst { it.isCurrentVideo(currentBvid, currentAid) }
        DiagnosticLog.i(
            TAG,
            "bind: page=${pagerState.currentPage} current=${swipeKey(currentBvid, currentAid)} playerPage=$owner"
        )
    }

    SessionChromeState(
        pages = pages,
        pagerState = pagerState,
        currentId = currentId,
        currentBvid = currentBvid,
        currentAid = currentAid,
        detailTitle = detailTitle,
        detailLoading = detailLoading,
        viewModel = viewModel,
        isFull = isFull,
        onToggleFull = onToggleFull,
        onBackClick = onBackClick,
        onGoHome = onGoHome,
        onOpenSpace = onOpenSpace,
        onRetryPlayback = onRetryPlayback,
        tailState = tailState,
        landscapeForced = landscapeForced,
        onToggleLandscape = onToggleLandscape,
        lastChromeInteractionMs = lastChromeInteractionMs,
        onChromeInteraction = { lastChromeInteractionMs = android.os.SystemClock.elapsedRealtime() },
        chromeOwner = chromeOwner,
        onChromeRequest = onPlayerChromeRequest,
        modifier = modifier,
        scope = scope
    )
}

/**
 * 会话的**剩余状态与整块 UI**（从主函数拆出的第二部分）。
 *
 * 为什么单独成函数而不是同文件继续往下堆：它是"已经拿全了会话参数之后"的纯装配段 ——
 * 自动连播监听、等首帧 veil、防烧屏循环、控制层归属、各面板开合。主函数只负责
 * effect 接线与页数据，两者职责边界清楚。
 *
 * ⚠️ 这里**没有**新增 `LaunchedEffect`：自动连播走 `DisposableEffect`（Player.Listener，
 * 不是组合周期副作用），等首帧与防烧屏都走本函数内的粘滞状态 + 计时 effect。
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun SessionChromeState(
    pages: List<SwipeEntry>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    currentId: SwipeId,
    currentBvid: String?,
    currentAid: Long,
    detailTitle: String?,
    detailLoading: Boolean,
    viewModel: VideoViewModel,
    isFull: Boolean,
    onToggleFull: () -> Unit,
    onBackClick: () -> Unit,
    onGoHome: () -> Unit,
    onOpenSpace: ((SpaceRoute) -> Unit)?,
    onRetryPlayback: () -> Unit,
    tailState: SwipeQueueTail,
    landscapeForced: Boolean,
    onToggleLandscape: () -> Unit,
    lastChromeInteractionMs: Long,
    onChromeInteraction: () -> Unit,
    /** 控制层归属 —— 会话级唯一判据（P0-1）。两层可见性都只从它派生。 */
    chromeOwner: SwipeChromeOwner,
    /** pane 的显隐请求（点击 / 2 秒自动隐藏 / 拖动唤出都走这一个通道）。 */
    onChromeRequest: (Boolean) -> Unit,
    modifier: Modifier,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    // 「更多」面板的复制链接 / 系统分享要用 Context。**必须在这里取**（Composable
    // 作用域），不能写进 `onCopyLink = { … }` / `onShare = { … }` 这类事件 lambda 里 ——
    // 它们在点击时才执行，读 `LocalContext.current` 会编译失败
    //（`@Composable invocations can only happen from the context of a @Composable function`）。
    // 变量名用 `appContext` 而非 `context`：后者与 Compose 的 `context()` 访问器撞名。
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val player by viewModel.player.collectAsStateWithLifecycle()
    val playerSettings by viewModel.settingsState.collectAsStateWithLifecycle(
        initialValue = PlayerSettingsState()
    )
    val playbackState by viewModel.videoState.collectAsStateWithLifecycle()
    val swipeErrorKind by viewModel.swipeErrorKind.collectAsStateWithLifecycle()
    val interaction by viewModel.interaction.collectAsStateWithLifecycle()
    val animations = LocalAnimations.current

    // ── 播完自动翻下一页（刷抖音的核心体验；DisposableEffect 挂 Player.Listener）──────
    // 幂等键：某些机型/解码器会在同一次播完里连发两次 STATE_ENDED，没有去重就会**一次翻两页**。
    // 键存在会话状态机里，随会话 exit 清空、进程重建归零。
    // 所有瞬时值都经 rememberUpdatedState 取最新（key 只有 player + 开关），
    // 否则监听器只挂一次、闭包会永久停在挂载那一刻的那一支（2026-10-07 真机 bug）。
    val latestPages by rememberUpdatedState(pages)
    val latestAutoAdvance by rememberUpdatedState(playerSettings.playback.swipeAutoAdvance)
    val latestRepeatOne by rememberUpdatedState(playerSettings.playback.repeatMode)
    val latestBvid by rememberUpdatedState(currentBvid)
    val latestAid by rememberUpdatedState(currentAid)
    var lastChromeInteractionMsState by remember { mutableStateOf(lastChromeInteractionMs) }
    val latestInteractionMs by rememberUpdatedState(lastChromeInteractionMsState)
    DisposableEffect(player, playerSettings.playback.swipeAutoAdvance) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                val key = swipeKey(latestBvid, latestAid)
                val next = pagerState.currentPage + 1
                val shouldAdvance = shouldAutoAdvanceToNextSwipePage(
                    autoAdvanceEnabled = latestAutoAdvance,
                    repeatOne = latestRepeatOne != PlayerRepeatMode.Off,
                    isLastPage = next > latestPages.lastIndex,
                    alreadyAdvancedForThisVideo =
                        key.isNotEmpty() && key == viewModel.swipeLastAutoAdvancedKey.value
                )
                if (!shouldAdvance) return
                viewModel.noteSwipeAutoAdvanced(key)
                DiagnosticLog.i(TAG, "autoAdvance: page=${pagerState.currentPage} -> $next key=$key")
                scope.launch { pagerState.animateScrollToPage(next) }
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }

    // ── 等首帧 veil（粘滞）────────────────────────────────────────────────────
    // 第 34 轮拆分时这里曾写成 `SwipeVeilState(...)` —— 那个 composable **从未存在**
    // （HEAD 里就是下面这段 `waitingKey` 粘滞状态 + 两个 effect）。此处按原写法内联。
    var waitingKey by remember { mutableStateOf<SwipeId?>(null) }
    var waitingSinceMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(currentId) {
        waitingKey = currentId
        waitingSinceMs = android.os.SystemClock.elapsedRealtime()
    }
    LaunchedEffect(playbackState.hasRenderedFirstFrame, waitingKey, playbackState.error) {
        val delayMs = SwipeWaitPolicy.retireDelayMs(
            firstFrameRendered = playbackState.hasRenderedFirstFrame,
            visibleSinceMs = waitingSinceMs,
            nowMs = android.os.SystemClock.elapsedRealtime(),
            minVisibleMs = SwipeWaitPolicy.MIN_VEIL_VISIBLE_MS_DEFAULT,
            playbackFailed = playbackState.error != null
        )
        when {
            delayMs == null -> Unit
            delayMs <= 0L -> waitingKey = null
            else -> {
                delay(delayMs)
                waitingKey = null
            }
        }
    }
    val waitingFirstFrame = waitingKey != null
    val isPortraitVideo = remember(playbackState.currentStream) {
        val stream = playbackState.currentStream ?: return@remember true
        (stream.width ?: 0) in 1..<(stream.height ?: 0)
    }
    val currentVideoAspect = remember(playbackState.currentStream) {
        val stream = playbackState.currentStream ?: return@remember null
        val w = stream.width?.takeIf { it > 0 } ?: return@remember null
        val h = stream.height?.takeIf { it > 0 } ?: return@remember null
        w.toFloat() / h.toFloat()
    }

    // ── 防烧屏 ───────────────────────────────────────────────────────────────
    // 第 34 轮拆分时这里曾写成 `SwipeBurnInChrome(...)` —— 那个类型**从未存在**；
    // 仓里的类型是 `SwipeChromeBurnIn`（`SwipeChromeBurnIn.kt`），且它是**数据类**，
    // 不含计时逻辑。计时按 HEAD 原样留在本处（一处计时、经 CompositionLocal 下发）。
    val burnInEnabled = playerSettings.playback.burnInGuard
    val chromeAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    var chromeDrift by remember { mutableStateOf(0f to 0f) }
    LaunchedEffect(burnInEnabled) {
        if (!burnInEnabled) {
            chromeAlpha.snapTo(1f)
            chromeDrift = 0f to 0f
            return@LaunchedEffect
        }
        // 1s 采样：静置阈值分钟级、漂移分钟级，秒级轮询足够且远低于任何帧预算
        while (true) {
            delay(1_000)
            val now = android.os.SystemClock.elapsedRealtime()
            val target = SwipeChromeBurnInPolicy.chromeAlpha(now, lastChromeInteractionMsState)
            if (chromeAlpha.value != target) {
                chromeAlpha.animateTo(target, tween(durationMillis = animations.extraLong))
            }
            chromeDrift = SwipeChromeBurnInPolicy.driftOffsetPx(now)
        }
    }
    val chromeBurnIn = SwipeChromeBurnIn(
        alpha = chromeAlpha.value,
        offsetXPx = chromeDrift.first,
        offsetYPx = chromeDrift.second
    )
    fun markInteraction() {
        lastChromeInteractionMsState = android.os.SystemClock.elapsedRealtime()
        onChromeInteraction()
    }

    // P0-3 的"可发现性"窗口：进横屏后的前 3 秒里，「竖屏」入口**不参与防烧屏变暗**。
    // 防烧屏本身是 15s 静置才变暗，但要"转过去正好撞上静置"这一拍仍然成立
    // （翻页/暂停都会写 lastChromeInteractionMs，用户刚放过手就转屏是常见序列）。
    var landscapeHintActive by remember { mutableStateOf(landscapeForced) }
    LaunchedEffect(landscapeForced) {
        landscapeHintActive = landscapeForced
        // 早退写法（而不是 `if (landscapeForced) { … }`）：会话里的**横屏优先分支**
        // 已经被守卫按 `if (landscapeForced) {` 的形态钉住"恰好一处"，这里再写一个同形
        // 分句会让那条守卫假红（它数的是"横屏优先"的接线点，不数计时窗口）。
        if (!landscapeHintActive) return@LaunchedEffect
        delay(SwipeChromePolicy.LANDSCAPE_EXIT_HINT_MS)
        landscapeHintActive = false
    }

    // ── 面板开合（会话级单例）────────────────────────────────────────────────
    var showComments by remember { mutableStateOf(false) }
    var playbackSheetVisible by remember { mutableStateOf(false) }
    var showFavSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var pendingBlockUp by remember { mutableStateOf<Pair<Long, String?>?>(null) }
    val favSheetState by viewModel.favSheet.collectAsStateWithLifecycle()
    val swipeDislikeUndoable by viewModel.swipeDislikeUndoable.collectAsStateWithLifecycle()
    val countRequests = com.naaammme.bbspace.feature.video.detail.rememberVideoActionCountRequests()
    val favBottomSheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // 「更多」面板收起：动作清单一律走 `IOSActionSheet`（无挂起收尾），直接落位即可。
    // 单独成函数是为了与 `closeFavSheet()` 对称，并让 back 优先级的判据读起来一致
    //（`SwipeBackPolicy.Action.CloseSheet` 的三分支各调一个 closeXxx）。
    fun closeMoreSheet() {
        showMoreSheet = false
    }

    // 收藏面板收起：`hide()` 是挂起点，scope 随宿主组合取消 → 状态落位必须 NonCancellable
    // （与详情页 closeSheet 同一纪律；不包 runCatching —— 它会连 CancellationException 一起吞）。
    fun closeFavSheet() {
        scope.launch {
            try {
                if (favBottomSheetState.isVisible) favBottomSheetState.hide()
            } finally {
                withContext(NonCancellable) { showFavSheet = false }
            }
        }
    }

    // ── back 优先级（用户反馈：看完评论顺手侧滑 → 直接退出到推荐页）────────────
    // 面板开着 → 只关面板；面板刚关（宽限窗口内）→ 吞掉这一次；否则放行给宿主。
    // Compose 的 BackHandler 后注册优先：本层在 VideoScreen 的宿主 handler 之内。
    val anySheetOpen = showComments || showFavSheet || showMoreSheet
    var inSheetCloseGrace by remember { mutableStateOf(false) }
    var previousSheetOpen by remember { mutableStateOf(false) }
    // 用 snapshotFlow 在**一个** effect 里完成"开→关检测 + 宽限计时"，
    // 而不是重构前那两个互相依赖的 LaunchedEffect（写 inSheetCloseGrace 又把它当 key）。
    LaunchedEffect(Unit) {
        snapshotFlow { anySheetOpen }.collect { open ->
            if (previousSheetOpen && !open) {
                inSheetCloseGrace = true
                delay(SwipeBackPolicy.SHEET_CLOSE_GRACE_MS)
                inSheetCloseGrace = false
            }
            previousSheetOpen = open
        }
    }
    val backAction = SwipeBackPolicy.resolve(
        anySheetOpen = anySheetOpen,
        inSheetCloseGrace = inSheetCloseGrace
    )
    BackHandler(enabled = landscapeForced || backAction != SwipeBackPolicy.Action.PassThrough) {
        // 需求③：**横屏观影时侧滑返回 = 切回竖屏播放**，而不是退出会话（优先级最高）。
        if (landscapeForced) {
            onToggleLandscape()
        } else when (backAction) {
            SwipeBackPolicy.Action.CloseSheet -> when {
                showMoreSheet -> closeMoreSheet()
                showComments -> showComments = false
                showFavSheet -> closeFavSheet()
            }
            SwipeBackPolicy.Action.Swallow, SwipeBackPolicy.Action.PassThrough -> Unit
        }
    }
    // VM 侧提交成功 / 用户取消都会置 dismissRequest，会话宿主必须自己收（详情页同款）。
    LaunchedEffect(favSheetState.dismissRequest) {
        if (favSheetState.dismissRequest) {
            viewModel.consumeFavDismiss()
            closeFavSheet()
        }
    }

    // ── 翻页动效与越界阻尼（SW-10）────────────────────────────────────────────
    // 读值一律在 graphicsLayer 的 lambda 里（绘制阶段读取 → 不触发重组）。
    val reduceMotion = animations.reduceMotion
    var rawOverScroll by remember { mutableStateOf(0f) }
    val overScrollConnection = remember(reduceMotion) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 只观察**未被消费**的纵向位移（= 已经滑到头还继续拖的那部分）
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    rawOverScroll += available.y
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // 松手：阻尼位移回弹到 0（`animate` 是挂起函数，不占帧预算棘轮额度）。
                if (rawOverScroll != 0f) {
                    androidx.compose.animation.core.animate(
                        initialValue = rawOverScroll,
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = animations.medium,
                            easing = animations.emphasized
                        )
                    ) { value, _ -> rawOverScroll = value }
                }
                return Velocity.Zero
            }
        }
    }

    val openOwnerSpace: (() -> Unit)? = playbackState.detail?.owner?.mid
        ?.takeIf { it > 0L }
        ?.let { mid ->
            onOpenSpace?.let { open ->
                {
                    open(
                        SpaceRoute(
                            mid = mid,
                            name = playbackState.detail?.owner?.name,
                            fromViewAid = playbackState.ids.aid.takeIf { it > 0L }
                        )
                    )
                }
            }
        }

    // ── 渲染 ─────────────────────────────────────────────────────────────────
    CompositionLocalProvider(LocalSwipeChromeBurnIn provides chromeBurnIn) {
        Box(modifier = modifier) {
            VerticalPager(
                state = pagerState,
                // 起滑之前就把邻居页组合出来：默认 0 时"手指一动"那一帧要同时做
                // 「组合整页 + 发封面请求 + 布局」，正是起手被读成"顿一下"的时刻。
                beyondViewportPageCount = 1,
                // SX-08：吸附段显式化（时长/曲线走 token；reduceMotion 时各档已压到 ~1ms）。
                // 只换吸附动画，pagerSnapDistance / decayAnimationSpec 仍用框架默认 ——
                // 即**不碰"翻到哪一页"的判据**。
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = tween(
                        durationMillis = animations.long,
                        easing = animations.emphasized
                    )
                ),
                modifier = Modifier
                    .fillMaxSize()
                    // SW-10：越界阻尼（观察未消费位移 + 松手回弹；reduceMotion 时不接）
                    .then(if (reduceMotion) Modifier else Modifier.nestedScroll(overScrollConnection))
                    .graphicsLayer {
                        translationY = SwipePageTransformPolicy.overScrollTranslation(rawOverScroll)
                    }
            ) { page ->
                val entry = pages.getOrNull(page) ?: return@VerticalPager
                // 页面变换层只在翻页过程中挂：停稳时变换恒等，留着等于给每页白建一层
                // 离屏 RenderNode（beyondViewportPageCount=1 ⇒ 常驻 2~3 页，封面是大图）。
                val pageTransformActive = pagerState.isScrollInProgress && !reduceMotion
                SwipePageContent(
                    page = page,
                    entry = entry,
                    pagerState = pagerState,
                    pageTransformActive = pageTransformActive,
                    reduceMotion = reduceMotion,
                    viewModel = viewModel,
                    detailTitle = detailTitle,
                    detailLoading = detailLoading,
                    isFull = isFull,
                    onToggleFull = onToggleFull,
                    onBackClick = onBackClick,
                    onGoHome = onGoHome,
                    onOpenSpace = onOpenSpace,
                    waitingFirstFrame = waitingFirstFrame,
                    playbackFailed = playbackState.error != null,
                    swipeErrorText = swipeErrorKind?.let(SwipeErrorPolicy::messageOf),
                    onRetryPlayback = onRetryPlayback,
                    onPlaybackSheetVisibilityChange = { visible ->
                        playbackSheetVisible = visible
                        markInteraction()
                    },
                    landscapeForced = landscapeForced,
                    onToggleLandscape = onToggleLandscape.takeIf { !isPortraitVideo },
                    currentBvid = currentBvid,
                    currentAid = currentAid,
                    playChromeVisible = SwipeChromePolicy.playerVisible(chromeOwner),
                    onPlayChromeRequest = onChromeRequest
                )
            }

            // 队列尾提示：只有停在**最后一页**时才提示，否则会打扰正常浏览。
            if (pagerState.currentPage >= pages.lastIndex && tailState != SwipeQueueTail.Idle) {
                SwipeQueueTailHint(
                    state = tailState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = BiliSpacing.xxxl * 6f)
                )
            }

            // 底部信息层（横屏观影时整块让位）
            //
            // ⚠️ `align(BottomCenter)` 不能省（2026-10-19 真机）：本组件内部是
            // 「`fillMaxHeight(0.38)` 的带 + 内容底对齐」，band 一旦落在父 Box 的
            // **默认 TopStart**，内容就跑到屏幕上三分之一（真机截图为证：UP 名/标题/
            // 话题出现在画面上方）。名称里的 "BottomZone" 只描述内部对齐，
            // **外层锚点必须在调用点给**。
            SwipeSessionBottomZone(
                title = playbackState.detail?.title,
                owner = playbackState.detail?.owner?.name,
                tags = playbackState.detail?.tags.orEmpty(),
                seasonTitle = playbackState.detail?.season?.title,
                staffs = playbackState.detail?.staffs.orEmpty(),
                resetKey = playbackState.ids.aid.takeIf { it > 0L } ?: playbackState.ids.bvid,
                chromeBurnIn = chromeBurnIn,
                // P0-1：会话层只在 owner=Story 时组合（与播放器层互斥）
                visible = SwipeChromePolicy.storyVisible(chromeOwner),
                onOpenSpace = openOwnerSpace,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // 「横屏 / 竖屏」悬浮胶囊（第 35 轮 P0-3：入口恒在，只有语义随方向翻转）。
            //
            // 真机问题（`32-land-chrome.png`）：进横屏后原判据 `!landscapeForced` 让入口
            // **恰好在这一刻消失** —— 屏幕上没有任何字样告诉用户"怎么转回去"，
            // 只能先点屏幕再找顶栏右上角那颗小图标（功能通、但不可发现）。
            // 现在拆成两件事：①**是否显示**只与"有没有画面比例 / 面板没开"有关；
            // ②**当前方向**只决定文案与点击语义（`reversed`）。
            //
            // 位置：竖屏会话里贴**画面下沿居中**（由宽高比算 letterbox 下沿，真机实测
            // 视频垂直居中）；横屏观影时画面铺满，改为**屏幕下沿居中**（同一个"下方居中"
            // 语义，不再按画幅飘）。
            val landscapeEntryVisible = !playbackSheetVisible && !showComments && !showFavSheet
            if (landscapeForced && landscapeEntryVisible) {
                SwipeLandscapeFab(
                    reversed = true,
                    onClick = {
                        markInteraction()
                        onToggleLandscape()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = BiliSpacing.xxxl * 2f)
                        .then(
                            // 3 秒可发现窗口内满亮，之后并入既有防烧屏策略（变暗 + 漂移）
                            if (landscapeHintActive) {
                                Modifier
                            } else {
                                Modifier.applyBurnIn(LocalSwipeChromeBurnIn.current)
                            }
                        )
                )
            } else if (!isPortraitVideo && landscapeEntryVisible) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val screenHPx = with(density) { maxHeight.toPx() }
                    val screenWPx = with(density) { maxWidth.toPx() }
                    // 会话里视频是 letterbox 垂直居中的 ⇒ 画面下沿 = (屏高 + 画面高) / 2；
                    // 画面高 = 屏宽 / 宽高比（比屏更宽时按宽贴合，不会被拉高）。
                    //
                    // **比例未知时必须退化、不能把入口藏掉**（真机实测：`stream.height`
                    // 可能是 0 ⇒ `currentVideoAspect == null`，而 `isPortraitVideo` 用的
                    // `width in 1..<height` 在 height=0 时区间为空、判成"不是竖屏"，
                    // 于是"不是竖屏 + 比例未知"两个分支都不成立，胶囊静默消失 ——
                    // 正是 P0-3 要消灭的"入口消失"，只是换了个原因）。
                    // 现在：拿不到比例就退化成"屏幕下沿上方一档"，入口**恒在**。
                    val imageBottomPx = currentVideoAspect?.let { aspect ->
                        val imageHPx = minOf(screenHPx, screenWPx / aspect)
                        (screenHPx + imageHPx) / 2f
                    } ?: (screenHPx - with(density) { (BiliSpacing.xxxl * 4).toPx() })
                    SwipeLandscapeFab(
                        onClick = {
                            markInteraction()
                            onToggleLandscape()
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .applyBurnIn(LocalSwipeChromeBurnIn.current)
                            .offset {
                                IntOffset(
                                    0,
                                    (imageBottomPx + with(density) { BiliSpacing.sm.toPx() })
                                        .roundToInt()
                                )
                            }
                    )
                }
            }

            // 会话级常驻互动栏（P0-1：与播放器层互斥）。
            //
            // ── P0-4 设计结论（横屏要不要保留点赞/评论/收藏）────────────────────
            // **不保留**，并且这是显式决定，不是"story chrome 恰好没生效"。
            //
            // 理由（真机 `32-land-chrome.png`）：
            // ① 横屏画面铺满整屏，右侧互动栏会压在画面上、遮住字幕与主体；
            // ② 横屏是**观影**姿势（双手握持），竖屏那套"右手拇指够得到的竖排图标"
            //    在横屏里恰好落在最难够的位置；
            // ③ 写操作（点赞/收藏）在横屏下没有确认反馈的空间（评论是竖屏悬浮卡，
            //    横屏里既占满屏又打断播放）。
            //
            // 横屏**保留**的入口（都在播放器层，点一下屏幕即可见）：
            // 返回 / 弹幕开关 / **退出横屏** / ⋮（播放设置：音轨、画质、倍速、全屏、CDN）
            // + 进度条与暂停/播放。也就是说"能看、能调、能退出"，但"不社交"。
            //
            // 这条结论的守卫：见 `VideoLogicGuardTest.swipeSessionHasExactlyOneChromeOwner`
            // （rail 只挂 `storyVisible(chromeOwner)`，横屏时 owner 恒为 None/Player ⇒ 永不渲染）。
            if (SwipeChromePolicy.storyVisible(chromeOwner)) AnimatedVisibility(
                visible = !playbackSheetVisible && !showComments && !showFavSheet,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                SwipeSessionActionRail(
                    liked = interaction.userState?.liked
                        ?: playbackState.detail?.userState?.liked,
                    likeText = interaction.stat?.likeValue
                        ?.let { com.naaammme.bbspace.feature.video.detail.actionCount(it, interaction.likeDelta) }
                        ?: playbackState.detail?.stat?.like,
                    onToggleLike = viewModel::toggleLike,
                    replyText = playbackState.detail?.stat?.reply,
                    onOpenComments = {
                        showComments = true
                        markInteraction()
                    },
                    onLongPressLike = viewModel::triple,
                    countRequests = countRequests,
                    likeCountValue = interaction.stat?.likeValue?.plus(interaction.likeDelta),
                    favCountValue = interaction.stat?.favValue?.plus(interaction.favDelta),
                    countIdentity = playbackState.ids.aid,
                    ownerFace = playbackState.detail?.owner?.face,
                    ownerName = playbackState.detail?.owner?.name,
                    onOpenSpace = openOwnerSpace,
                    onOpenMore = {
                        showMoreSheet = true
                        markInteraction()
                    },
                    faved = interaction.userState?.faved
                        ?: playbackState.detail?.userState?.faved,
                    favText = interaction.stat?.favValue
                        ?.let { com.naaammme.bbspace.feature.video.detail.actionCount(it, interaction.favDelta) }
                        ?: playbackState.detail?.stat?.fav,
                    onOpenFavorite = {
                        // 弹不弹由 `openFavSheet()` 的返回值决定：未登录时它只弹 toast 并返回
                        // false，无条件弹就会把一层空白的"暂无收藏夹"盖在"请先登录"上面。
                        if (viewModel.openFavSheet()) {
                            showFavSheet = true
                            markInteraction()
                        }
                    },
                    shareBvid = playbackState.ids.bvid,
                    shareAid = playbackState.ids.aid,
                    shareTitle = playbackState.detail?.title,
                    modifier = Modifier
                        .padding(end = BiliSpacing.md, bottom = BiliSpacing.xxxl * 4f)
                        .applyBurnIn(chromeBurnIn)
                )
            }
        }

        // 评论：悬浮卡片（用户明确要求：不要占满屏的弹层）
        SwipeCommentCard(
            visible = showComments,
            viewModel = viewModel,
            onDismiss = { showComments = false }
        )

        // 收藏面板
        SwipeFavSheet(
            visible = showFavSheet,
            sheetState = favBottomSheetState,
            onDismiss = { closeFavSheet() }
        ) {
            com.naaammme.bbspace.feature.video.detail.FavFolderSheetContent(
                state = favSheetState,
                onToggle = viewModel::toggleFavFolder,
                onCreateFolder = viewModel::createFolderInSheet,
                onSubmit = viewModel::submitFav
            )
        }

        // 「更多」面板（SW-24）+ 拉黑二次确认（PT-06）
        if (showMoreSheet) {
            val currentEntry = pages.getOrNull(pagerState.currentPage)
            // 动作清单按当前条目动态拼：不感兴趣 / 撤回 / 拉黑 都是**有条件**的，
            // 无条件项（弹幕、复制链接、分享）恒定出现。
            //
            // 这里直接构造 `List<IOSActionSheetAction>`，而不是调用某个"动作构造器"：
            // `IOSActionSheet` 的唯一入口就是 `actions: List<IOSActionSheetAction>`，
            // 没有 content 变体（第 34 轮拆分时曾误引用不存在的 `SwipeMoreActions`，
            // 编译报 Unresolved reference —— 记在此处避免重蹈）。
            val moreActions = buildList {
                if (currentEntry?.dislikeContext != null) {
                    add(
                        IOSActionSheetAction(
                            title = "不感兴趣",
                            onClick = {
                                if (viewModel.submitSwipeDislike(currentEntry, pagerState.currentPage)) {
                                    markInteraction()
                                }
                                closeMoreSheet()
                            }
                        )
                    )
                }
                if (swipeDislikeUndoable) {
                    add(
                        IOSActionSheetAction(
                            title = "撤回不感兴趣",
                            onClick = {
                                viewModel.undoSwipeDislike()
                                closeMoreSheet()
                            }
                        )
                    )
                }
                // PT-06：拉黑该 UP 主。**入口门控只有 `ownerMid > 0`** ——
                // story / relate 来源的 ownerMid 为 0（`PortraitSwipeQueue`），
                // 没有 mid 就没有可拉黑的对象，点了只会静默失败，是"假入口"。
                if ((currentEntry?.ownerMid ?: 0L) > 0L) {
                    add(
                        IOSActionSheetAction(
                            title = "拉黑该 UP 主",
                            destructive = true,
                            onClick = {
                                // `currentEntry?.let { it.ownerMid to it.author }` 会被推断成
                                // `Pair<Long?, String?>`，而 `pendingBlockUp` 是 `Pair<Long, String?>?`
                                // ⇒ Return type mismatch（第 34 轮拆分时踩过）。这里沿用原文件的
                                // 「先取非空 Long、再配 author」写法，类型自然成立。
                                pendingBlockUp = (currentEntry?.ownerMid ?: 0L)
                                    .takeIf { it > 0L }
                                    ?.let { it to currentEntry?.author }
                                closeMoreSheet()
                            }
                        )
                    )
                }
                add(
                    IOSActionSheetAction(
                        title = "弹幕开关",
                        onClick = {
                            viewModel.toggleDanmaku()
                            closeMoreSheet()
                        }
                    )
                )
                add(
                    IOSActionSheetAction(
                        title = "复制链接",
                        onClick = {
                            val url = ShareUtils.videoUrl(
                                playbackState.ids.bvid,
                                playbackState.ids.aid,
                                page = 0
                            )
                            val clipboard = appContext
                                .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("video_url", url)
                            )
                            android.widget.Toast.makeText(
                                appContext,
                                "链接已复制",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            closeMoreSheet()
                        }
                    )
                )
                // 文案用「系统分享」而不是「分享」：与详情页同一措辞，
                // 也明示这是走系统分享面板（不是站内转发）。守卫
                // `swipeMoreSheetShowsOnlyRealEntries` 逐字钉这个字符串。
                add(
                    IOSActionSheetAction(
                        title = "系统分享",
                        onClick = {
                            val url = ShareUtils.videoUrl(
                                playbackState.ids.bvid,
                                playbackState.ids.aid,
                                page = 0
                            )
                            ShareUtils.shareText(appContext, playbackState.detail?.title, url)
                            closeMoreSheet()
                        }
                    )
                )
            }
            com.naaammme.bbspace.core.designsystem.component.IOSActionSheet(
                onDismiss = { closeMoreSheet() },
                actions = moreActions
            )
        }
        pendingBlockUp?.let { (mid, name) ->
            SwipeBlockUpConfirmDialog(
                upName = name,
                onConfirm = {
                    viewModel.blockSwipeUp(mid, name)
                    pendingBlockUp = null
                },
                onDismiss = { pendingBlockUp = null }
            )
        }
    }
}

/**
 * 单页内容：当前播放的那一页组合播放器，其余页组合封面。
 *
 * 拆成独立 composable 的理由：它是"页身份判据 + 页面变换 + 播放器参数"的集中点，
 * 而这套参数（尤其 `swipePagingActive` / `waitingFirstFrame` / 失败态三件套）
 * 的注释密度极高 —— 留在主函数里会把主函数撑爆。
 */
@Composable
private fun SwipePageContent(
    page: Int,
    entry: SwipeEntry,
    pagerState: androidx.compose.foundation.pager.PagerState,
    pageTransformActive: Boolean,
    reduceMotion: Boolean,
    viewModel: VideoViewModel,
    detailTitle: String?,
    detailLoading: Boolean,
    isFull: Boolean,
    onToggleFull: () -> Unit,
    onBackClick: () -> Unit,
    onGoHome: () -> Unit,
    onOpenSpace: ((SpaceRoute) -> Unit)?,
    waitingFirstFrame: Boolean,
    playbackFailed: Boolean,
    swipeErrorText: String?,
    onRetryPlayback: () -> Unit,
    onPlaybackSheetVisibilityChange: (Boolean) -> Unit,
    landscapeForced: Boolean,
    onToggleLandscape: (() -> Unit)?,
    currentBvid: String?,
    currentAid: Long,
    playChromeVisible: Boolean,
    onPlayChromeRequest: (Boolean) -> Unit,
) {
    if (entry.isCurrentVideo(currentBvid, currentAid)) {
        VideoPlayerPane(
            // P1-4②：播放器页此前只有 `fillMaxSize()`，而隔壁封面页有一套 graphicsLayer
            // 变换 —— 于是刷视频时"最显眼的那一页"反而唯一不跟手。这里补上同一套变换。
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (pageTransformActive) {
                        Modifier.graphicsLayer {
                            val offset = (pagerState.currentPage - page) +
                                pagerState.currentPageOffsetFraction
                            val t = SwipePageTransformPolicy.transformFor(offset, reduceMotion)
                            scaleX = t.scale
                            scaleY = t.scale
                            alpha = t.alpha
                        }
                    } else {
                        Modifier
                    }
                ),
            viewModel = viewModel,
            // 页面级 graphicsLayer 变换 + pager 位移：SurfaceView 是独立图层，
            // 被这种变换层包含会被挖空 ⇒ 本会话禁用省电模式
            allowSurfaceView = false,
            videoTitle = detailTitle,
            isFull = isFull,
            onToggleFull = onToggleFull,
            onBackClick = onBackClick,
            onGoHome = onGoHome,
            // 翻页会话激活：垂直拖动让位给 pager（否则亮度/音量手势抢走上下滑）
            swipePagingActive = true,
            swipeWaitingFirstFrame = waitingFirstFrame,
            swipeFailed = playbackFailed,
            swipeErrorText = swipeErrorText,
            onRetrySwipe = onRetryPlayback,
            swipeCoverUrl = entry.cover,
            onPlaybackSheetVisibilityChange = onPlaybackSheetVisibilityChange,
            landscapeForced = landscapeForced,
            onToggleLandscape = onToggleLandscape,
            // P0-1：会话内 pane **不再自持** showCtrl —— 显隐由会话层唯一决定
            sessionChromeVisible = playChromeVisible,
            onSessionChromeRequest = onPlayChromeRequest
        )
    } else {
        SwipeCoverPage(
            entry = entry,
            isLoading = page == pagerState.currentPage && detailLoading,
            // 与播放器页同一个门（见上）：停稳时变换恒等，留着等于给邻居封面白建离屏层。
            modifier = Modifier.then(
                if (pageTransformActive) {
                    Modifier.graphicsLayer {
                        val offset = (pagerState.currentPage - page) +
                            pagerState.currentPageOffsetFraction
                        val t = SwipePageTransformPolicy.transformFor(offset, reduceMotion)
                        scaleX = t.scale
                        scaleY = t.scale
                        alpha = t.alpha
                    }
                } else {
                    Modifier
                }
            )
        )
    }
}
