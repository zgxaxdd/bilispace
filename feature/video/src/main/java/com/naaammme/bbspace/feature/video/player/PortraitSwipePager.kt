package com.naaammme.bbspace.feature.video.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.naaammme.bbspace.feature.comment.CommentPanel
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.naaammme.bbspace.core.common.ShareUtils
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.core.designsystem.component.BiliParticleLoader
import com.naaammme.bbspace.core.designsystem.component.BiliSheetGrabber
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.biliSheetShape
import com.naaammme.bbspace.feature.video.VideoViewModel
import com.naaammme.bbspace.feature.video.detail.FavFolderSheetContent
import com.naaammme.bbspace.core.model.PlayerRepeatMode
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.swipe.SwipeBackPolicy
import com.naaammme.bbspace.core.swipe.SwipeChromeBurnInPolicy
import com.naaammme.bbspace.core.swipe.SwipeEntry
import com.naaammme.bbspace.core.swipe.SwipeErrorPolicy
import com.naaammme.bbspace.core.swipe.SwipePageTransformPolicy
import com.naaammme.bbspace.core.swipe.SwipeWaitPolicy
import kotlinx.coroutines.delay
import com.naaammme.bbspace.core.swipe.SwipePrefetchPolicy
import com.naaammme.bbspace.core.swipe.SwipeQueueTail
import com.naaammme.bbspace.core.swipe.shouldAutoAdvanceToNextSwipePage
import com.naaammme.bbspace.core.swipe.shouldExtendSwipeQueue
import com.naaammme.bbspace.core.swipe.model.SwipeId
import com.naaammme.bbspace.core.swipe.swipeKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 监听它立即 [onSwipeTo] —— 手势还在进行时取流已发出（BiliPai 的
 * `resolvePortraitEarlyPlaybackPage` 同款思路），停稳后通常已在起播。
 *
 * ## 与既有管线的复用关系
 * 切视频走 [VideoViewModel.openSwipeTarget]（替换栈顶 + `openVideo`），取流、弹幕重载、
 * 播放历史上报、清晰度选择全部复用详情页既有链路；翻回页 0 = 切回锚点视频，与
 * B 站官方上下滑语义一致。队列耗尽（推荐为空）自然到头，不循环。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    /**
     * 相邻页预取（页号）：拖动跨过 25% 偏移 / 停稳时各触发一次，
     * 由 [VideoViewModel.prefetchSwipeQueue] 按网络与开关决定预几条。
     */
    onPrefetch: (Int) -> Unit = {},
    /** 取流/解码失败后的重试（沿用同一目标重开一次播放）。 */
    onRetryPlayback: () -> Unit = {},
    /** 队列尾部状态：驱动"正在为你找更多视频…" / "已经是最后一个了"。 */
    tailState: SwipeQueueTail = SwipeQueueTail.Idle,
    /** 当前是否已被用户手动锁成横屏（"横屏/竖屏"胶囊的状态）。 */
    landscapeForced: Boolean = false,
    /** 手动横屏开关；由 VideoScreen 落到 requestedOrientation 上。 */
    onToggleLandscape: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pages = remember(anchor, queue) { listOf(anchor) + queue }
    // pageCount 经 rememberUpdatedState 间接读：rememberPagerState 会记住首次传入的
    // lambda，若让它直接捕获 pages，队列追加后 pager 拿到的仍是旧页数——「无限刷」失效。
    val pageCountState = rememberUpdatedState(pages.size)
    val pagerState = rememberPagerState { pageCountState.value }
    val scope = rememberCoroutineScope()
    // SW-24：更多面板用到剪贴板/Toast（复制链接、系统分享）
    val context = LocalContext.current
    // pager 认定的「应播放页」：以它为准发切换请求，而不是拿 currentId 反向比较——
    // 后者在取流完成前仍是旧值，快速往返翻页时会造成目标震荡。
    // 身份键 = SwipeEntry.id（SW-03 类型化；**只用于"已请求页"的去重震荡防护**，
    // 「哪一页归播放器」一律走 entry.isCurrentVideo）。
    val currentId = SwipeId.of(currentBvid, currentAid)
    var requestedKey by remember { mutableStateOf(anchor.id) }
    // 防烧屏的"最近交互时刻"（用户要求）：翻页回调（下方 collect）与评论/收藏面板都要写它，
    // 所以在第一个 effect 之前声明；亮度/drift 的派生在本函数更下方（要读 playerSettings）。
    var lastChromeInteractionMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    // 底部细进度条已移除（用户决定，2026-10-02），因此没有"拖动中"这个状态；
    // 播完自动翻页只需四判据（见 SwipeAutoAdvancePolicy）。
    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                val entry = pages.getOrNull(page) ?: return@collect
                // 状态机记账（SW-02）：Ready.page 跟随落定页，供恢复/快照与后续派生 UI 用。
                viewModel.onSwipePageChanged(page)
                // 防烧屏：翻页是交互 → 常驻控件立刻回满亮并重算漂移基准
                lastChromeInteractionMs = android.os.SystemClock.elapsedRealtime()
                if (entry.id != requestedKey) {
                    requestedKey = entry.id
                    Log.i(
                        TAG,
                        "swipe: page=$page key=${entry.legacyKey()} aid=${entry.target.aid} bvid=${entry.bvid}"
                    )
                    onSwipeTo(entry)
                }
                // 补货判据（BiliPai loadMoreStories 同款）：进入倒数第 2 页区间就拉
                // 下一批推荐流追加进队列；防抖由调用方（ViewModel）负责。
                if (shouldExtendSwipeQueue(committedPage = page, totalPages = pages.size)) {
                    onNeedMore()
                }
            }
    }
    // ── 相邻页预取（对照 BiliPai：拖动 0.25 偏移 + 停稳各预一次）──────────────────
    // 拖动中就预取（而不是等停稳）：用户"明确要往那一页去"时，playurl 请求已经飞出去了，
    // 停稳后通常只等解码 —— 这是"上滑之后画面来得快不快"的关键一步。
    // 用一个页面级的去重（lastPrefetchPage）压住每帧都会变化的 offsetFraction。
    // 独立的「队列变化 / 换页」触发：**不依赖 offset**。
    //
    // 真机实测：进场那一刻队列还是空的（`available = 0`，预取空转），
    // 而停稳时的 `currentPageOffsetFraction` 又未必落在 [SETTLED_OFFSET_EPSILON] 内 ——
    // 只靠 offset 判"停稳"会出现"进场后完全没有预取，等用户先滑一次才预取"。
    // 所以只要**页数或当前页**变了就补一次（VM 侧按 aid#cid 去重，重复调用无副作用）。
    LaunchedEffect(pages.size, pagerState.currentPage) {
        onPrefetch(pagerState.currentPage)
    }
    var lastPrefetchPage by remember { mutableStateOf(-1) }
    // key 里必须带 `pages`：进场那一刻队列还是空的（`available = 0`），第一轮预取会空转；
    // 等详情/补货把队列填上后，只有随 `pages` 重启这个 effect 才会补上"第 0 页的邻居"。
    // 真机实测踩过：不带 `pages` 时，第 0 页的预取要等用户先滑一次才发生（等于没预取）。
    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.currentPage to pagerState.currentPageOffsetFraction }
            .collect { (page, offset) ->
                val target = SwipePrefetchPolicy.dragTargetPage(
                    currentPage = page,
                    offsetFraction = offset,
                    lastPageIndex = pageCountState.value - 1
                ) ?: page.takeIf { kotlin.math.abs(offset) <= SETTLED_OFFSET_EPSILON }
                    ?: return@collect
                if (target == lastPrefetchPage) return@collect
                lastPrefetchPage = target
                onPrefetch(target)
            }
    }
    LaunchedEffect(currentId, pages) {
        // 取流完成后的对齐：正常路径上 pager 停在的页就是当前播放的视频，直接同步；
        // 若是快速往返翻页的迟到结果（pager 停在用户已离开的页），以用户所在页为
        // 准再发一次切换（openVideo 幂等）——否则 pager 会停在"播放器在看不见的页"的状态。
        // 判据是**内容身份**（isCurrentVideo）而非字符串：见 SwipeEntry.isCurrentVideo。
        //
        // 同步的是 entry.id 而不是 currentId：requestedKey 的语义是「已请求的那一页」，
        // 用 currentId 会让「锚点 bvid 为空（story 入口）」这类条目在补货/补流引发的
        // 重新收集里反复判定为"目标变了"（虽然 openSwipeTarget 会短路，但白跑一趟）。
        val entry = pages.getOrNull(pagerState.currentPage)
        if (entry == null) {
            requestedKey = currentId
        } else if (entry.isCurrentVideo(currentBvid, currentAid)) {
            requestedKey = entry.id
        } else {
            requestedKey = entry.id
            onSwipeTo(entry)
        }
    }
    // 播完自动翻下一页（刷抖音的核心体验，BiliPai 的 autoAdvance 同款）：
    // 当前页是最后一页则不动。播放器 repeat 设置若为单曲循环则 STATE_ENDED 不会发出，
    // 天然与此逻辑互斥。animateScrollToPage 是挂起函数，经外部 scope 派发。
    //
    // **幂等键**（对照 BiliPai `lastAutoAdvancedBvid`，PortraitVideoPager.kt:902-905）：
    // 某些机型/解码器会在同一次播完里连发两次 STATE_ENDED（或与引擎重建叠加），
    // 没有去重就会**一次翻两页**（用户看到"跳过了一支"）。同一支视频只自动翻一次。
    // 键存在会话状态机里（SW-02 从 remember 提升）：随会话 exit 清空、进程重建归零。
    val player by viewModel.player.collectAsStateWithLifecycle()
    // 初值给默认设置：DataStore 的 Flow 没有同步初值，而默认值就是"自动翻页开"，
    // 第一帧判据与落地后的真实值一致（不会出现"先不翻、过一帧才开始翻"的抖动）。
    val playerSettings by viewModel.settingsState.collectAsStateWithLifecycle(
        initialValue = PlayerSettingsState()
    )
    DisposableEffect(player, pages, playerSettings.playback.swipeAutoAdvance) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                val key = swipeKey(currentBvid, currentAid)
                val next = pagerState.currentPage + 1
                val shouldAdvance = shouldAutoAdvanceToNextSwipePage(
                    autoAdvanceEnabled = playerSettings.playback.swipeAutoAdvance,
                    repeatOne = playerSettings.playback.repeatMode != PlayerRepeatMode.Off,
                    isLastPage = next > pages.lastIndex,
                    alreadyAdvancedForThisVideo =
                        key.isNotEmpty() && key == viewModel.swipeLastAutoAdvancedKey.value
                )
                if (!shouldAdvance) return
                viewModel.noteSwipeAutoAdvanced(key)
                Log.i(TAG, "autoAdvance: page=${pagerState.currentPage} -> $next key=$key")
                scope.launch { pagerState.animateScrollToPage(next) }
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }
    // 播放器归属诊断：页身份失配（症状="翻页后只有封面、不起播"）在真机上唯一的可观测
    // 证据就是这个三元组。只在两侧变化时打一行，不在重组里刷屏。
    // 日志沿用 legacyKey 字符串（bvid 优先/aid 兜底），字段格式与既有脚本兼容。
    val currentLegacyKey = swipeKey(currentBvid, currentAid)
    LaunchedEffect(currentId, pagerState.currentPage, pages) {
        val owner = pages.indexOfFirst { it.isCurrentVideo(currentBvid, currentAid) }
        Log.i(TAG, "bind: page=${pagerState.currentPage} current=$currentLegacyKey playerPage=$owner")
    }
    // ── 等首帧（真机反馈：上滑后第一个出现的是"黑屏 + 大暂停键"）──────────────────
    // 症状链：翻页 → 新页立刻从 SwipeCoverPage 切成 VideoPlayerPane → 但播放器
    // 开了 `setKeepContentOnPlayerReset(true)`，这一瞬间画面还是**上一支视频的最后一帧**；
    // 中央那颗 72dp 播放键也会因为 playWhenReady 短暂为假而冒出来。
    //
    // **不能直接取 `!hasRenderedFirstFrame` 的瞬时值**：换视频后的头几十毫秒里它仍然是
    // 上一支的 `true` —— `finishVideoPlayback` 是**先**发布新 ids
    // （`_videoState.value = nextState.toVideoPlaybackState(state = 引擎当时的值)`）、
    // **后**才 `stopForReuse()` 把 `firstFrameSeq` 归零。跟着瞬时值走，加载层会先卸载、
    // 几十毫秒后再挂上，中间那一帧就是用户看到的黑屏 + 大暂停键
    // （真机日志实证：`veil: visible=false mounted=false` 之后 37ms 又 `visible=true`）。
    //
    // 所以用**粘滞**状态：换视频（currentId 变化）进入等待，直到"这一支"真的出帧才退出。
    //
    // **失败时不再撤掉加载层**（本轮改动，对照 BiliPai 的失败态）：以前这里是
    // `if (firstFrameRendered || playbackFailed) waitingKey = null` —— 取流失败就把加载层
    // 撤掉，露出"纯黑 + 中央播放键"，看起来像卡死；而且错误只写在播放器侧栏里，
    // 用户在连播页面上根本看不到。现在改成：失败时加载层**留着**并切成错误态 + 重试按钮
    // （见 VideoPlayerPane 的 SwipeWaitVeil），返回手势与上下滑照常可用。
    val playbackState by viewModel.videoState.collectAsStateWithLifecycle()
    // SW-13：失败类型（veil 文案按类型给"人话"）
    val swipeErrorKind by viewModel.swipeErrorKind.collectAsStateWithLifecycle()
    val firstFrameRendered = playbackState.hasRenderedFirstFrame
    val playbackFailed = playbackState.error != null
    // 粘滞键类型化（SW-03）：数据类相等语义与旧字符串完全同拍（同视频同键）。
    var waitingKey by remember { mutableStateOf<SwipeId?>(null) }
    // veil 本轮展示的起点（SW-11）：随切页重置，供"只补足、不新增等待"判据用。
    var waitingSinceMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(currentId) {
        // 首次组合同样置位：锚点视频还没出帧时，一样该看到加载层而不是黑屏
        waitingKey = currentId
        waitingSinceMs = android.os.SystemClock.elapsedRealtime()
    }
    // veil 退场（SW-11）：走 SwipeWaitPolicy 的纯判据，**同一个 effect 里直接清键**
    // （中间不再经 retireVeil 状态转手——那会多一跳 effect 派发，实测把"首帧→撤遮罩"
    // 从 ~19ms 拖到 ~37ms，真机上表现为遮罩多盖一帧）。
    // 默认 MIN_VEIL_VISIBLE_MS_DEFAULT = 0 → firstFrame 到位时 delay=0 立即清，
    // 与旧行为（首帧即撤）逐帧同拍；真机确认"快网仍闪"后调大常量即开启。
    LaunchedEffect(firstFrameRendered, waitingKey, playbackFailed) {
        val delayMs = SwipeWaitPolicy.retireDelayMs(
            firstFrameRendered = firstFrameRendered,
            visibleSinceMs = waitingSinceMs,
            nowMs = android.os.SystemClock.elapsedRealtime(),
            minVisibleMs = SwipeWaitPolicy.MIN_VEIL_VISIBLE_MS_DEFAULT,
            playbackFailed = playbackFailed
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
    // 遮掩时间线（真机反馈「感觉就是盖了一层遮罩」）：必须能量出
    // 「换页 → 遮罩出现 → 首帧到达 → 遮罩撤掉」四点的间隔，否则只能靠观感猜。
    // 只在状态翻转时各打一行，不进重组刷屏。
    LaunchedEffect(waitingFirstFrame, currentId, playbackState.hasRenderedFirstFrame) {
        Log.i(
            TAG,
            "veil: waiting=$waitingFirstFrame key=$currentLegacyKey " +
                "firstFrame=${playbackState.hasRenderedFirstFrame} " +
                "error=${playbackState.error != null} state=${playbackState.playbackState}"
        )
    }
    // 当前视频是不是横屏：决定要不要给"手动横屏"入口（竖屏视频转横屏只会更小）。
    // 与 VideoScreen 的 isPortraitVideo 同源同键（currentStream），不会两处算出不同结论。
    val isPortraitVideo = remember(playbackState.currentStream) {
        val stream = playbackState.currentStream ?: return@remember true
        (stream.width ?: 0) in 1..<(stream.height ?: 0)
    }
    // ── 常驻控件的防烧屏（用户要求，2026-10-01）─────────────────────────────────
    // 一处计时（本层），经 LocalSwipeChromeBurnIn 下发给 pane 的常驻件；
    // 交互 = 翻页落定 / 打开评论 / 打开收藏 / 打开侧栏 → 立刻回满亮并重置漂移相位基准。
    val burnInEnabled = playerSettings.playback.burnInGuard
    val chromeAlpha = remember { Animatable(1f) }
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
            val target = SwipeChromeBurnInPolicy.chromeAlpha(now, lastChromeInteractionMs)
            if (chromeAlpha.value != target) {
                chromeAlpha.animateTo(target, tween(durationMillis = 400))
            }
            chromeDrift = SwipeChromeBurnInPolicy.driftOffsetPx(now)
        }
    }
    val chromeBurnIn = SwipeChromeBurnIn(
        alpha = chromeAlpha.value,
        offsetXPx = chromeDrift.first,
        offsetYPx = chromeDrift.second
    )
    // ── 会话内评论（用户需求 1：刷视频模式要能看评论）──────────────────────────────
    // bbspace 的 `CommentPanel` 本来是**详情 pager 的一页**（不是弹层），所以这里另起一个
    // 悬浮卡片宿主：单实例挂在 pager 上（不是每页一个），互动栏的评论按钮把它打开。
    var showComments by remember { mutableStateOf(false) }
    // 播放设置侧栏（pane 内 ⋮ 面板）可见性：pane 内的面板盖不住 pane 之外的互动栏，
    // 侧栏打开时常驻互动栏要让位（见下方互动栏接线的门控）。
    var playbackSheetVisible by remember { mutableStateOf(false) }
    // 会话内收藏（补齐互动栏 赞/评/藏/享）：宿主在这里，内容复用详情页那份
    // `FavFolderSheetContent`；收藏夹状态机整个挂在 VM 上，会话不另存一份。
    var showFavSheet by remember { mutableStateOf(false) }
    // SW-24：会话内「更多」面板（不感兴趣/弹幕开关/复制链接/系统分享）
    var showMoreSheet by remember { mutableStateOf(false) }
    val moreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val swipeDislikeUndoable by viewModel.swipeDislikeUndoable.collectAsStateWithLifecycle()
    val favSheetState by viewModel.favSheet.collectAsStateWithLifecycle()
    val favBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun closeFavSheet() {
        scope.launch {
            try {
                if (favBottomSheetState.isVisible) favBottomSheetState.hide()
            } finally {
                // `hide()` 是挂起点，而 scope 随宿主组合一起取消：收起动画没跑完就被杀时，
                // 写在后面的 `showFavSheet = false` 不会执行 → 面板留在"已隐藏但还在组合里"。
                // 详情页的 closeSheet 为此把状态落位放进 NonCancellable，这里同理。
                withContext(NonCancellable) { showFavSheet = false }
            }
        }
    }

    /**
     * 收起「更多」面板（与 [closeFavSheet] 同一套纪律）：
     * **不包 `runCatching`** —— 它会连 `CancellationException` 一起吞掉（本仓纪律：
     * 挂起调用一律走可取消版本）；状态落位放 `NonCancellable`，宿主被取消也收得掉。
     */
    fun closeMoreSheet() {
        scope.launch {
            try {
                if (moreSheetState.isVisible) moreSheetState.hide()
            } finally {
                withContext(NonCancellable) { showMoreSheet = false }
            }
        }
    }

    // ── 会话内 back 优先级（用户反馈：看完评论顺手侧滑 → 直接退出到推荐页）──────────
    // 面板开着 → 只关面板；面板刚关（宽限窗口内）→ 吞掉这一次（顺手滑不该带走会话）；
    // 否则放行给宿主（story 退出 / 全屏回缩）。
    // Compose 的 BackHandler 是后注册优先：本层在 VideoScreen 的宿主 handler 之内，
    // 因此这里的 enabled=true 时会先接走 back（含系统侧滑返回手势）。
    val anySheetOpen = showComments || showFavSheet || showMoreSheet
    var inSheetCloseGrace by remember { mutableStateOf(false) }
    var previousSheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(anySheetOpen) {
        // 只在"开 → 关"的那一次转换置位宽限；进入会话时的初始 false 不算
        if (previousSheetOpen && !anySheetOpen) inSheetCloseGrace = true
        previousSheetOpen = anySheetOpen
    }
    LaunchedEffect(inSheetCloseGrace) {
        if (inSheetCloseGrace) {
            delay(SwipeBackPolicy.SHEET_CLOSE_GRACE_MS)
            inSheetCloseGrace = false
        }
    }
    val backAction = SwipeBackPolicy.resolve(
        anySheetOpen = anySheetOpen,
        inSheetCloseGrace = inSheetCloseGrace
    )
    BackHandler(enabled = backAction != SwipeBackPolicy.Action.PassThrough) {
        when (backAction) {
            SwipeBackPolicy.Action.CloseSheet -> {
                when {
                    showMoreSheet -> closeMoreSheet()
                    showComments -> showComments = false
                    showFavSheet -> closeFavSheet()
                }
            }
            // 宽限窗口内：吞掉（不退出会话）。窗口过后按钮/手势仍然能正常退出。
            SwipeBackPolicy.Action.Swallow -> Unit
            SwipeBackPolicy.Action.PassThrough -> Unit
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
    // 1:1 跟手由 VerticalPager 自带；这里补的是"带走感"（出页轻微缩放 + 淡出）
    // 与"越界阻尼"（滑到末端还继续拖，画面按系数折减跟随、松手回弹）。
    // 读值一律在 graphicsLayer 的 lambda 里（绘制阶段读取 → 不触发重组）。
    val reduceMotion = LocalAnimations.current.reduceMotion
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
                // 松手：阻尼位移回弹到 0（`animate` 是挂起函数，不是 animate*AsState，
                // 不占帧预算棘轮的额度）
                if (rawOverScroll != 0f) {
                    val start = rawOverScroll
                    androidx.compose.animation.core.animate(
                        initialValue = start,
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 240)
                    ) { value, _ -> rawOverScroll = value }
                }
                return Velocity.Zero
            }
        }
    }
    CompositionLocalProvider(LocalSwipeChromeBurnIn provides chromeBurnIn) {
    Box(modifier = modifier) {
        // `beyondViewportPageCount = 1`：**起滑之前**就把左右邻居页组合出来。
        // 默认 0 时，邻居页要等手指拖动、它开始进入视口才建树 —— 于是"手指一动"那一帧
        // 要同时做「组合整页 + 发封面请求 + 布局」，这正是滑动起手最容易被读成"顿一下"
        // 的时刻。预组合一页的代价是两页静态封面（非播放器页只有图片 + 文字，
        // 播放器节点仍然唯一，见本文件 KDoc），换来的是起手即有内容。
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                // SW-10：越界阻尼（观察未消费位移 + 松手回弹；reduceMotion 时不接）
                .then(if (reduceMotion) Modifier else Modifier.nestedScroll(overScrollConnection))
                .graphicsLayer {
                    translationY = SwipePageTransformPolicy.overScrollTranslation(rawOverScroll)
                }
        ) { page ->
            val entry = pages.getOrNull(page) ?: return@VerticalPager
            if (entry.isCurrentVideo(currentBvid, currentAid)) {
                VideoPlayerPane(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    videoTitle = detailTitle,
                    isFull = isFull,
                    onToggleFull = onToggleFull,
                    onBackClick = onBackClick,
                    onGoHome = onGoHome,
                    // 翻页会话激活：垂直拖动让位给 pager（否则亮度/音量手势抢走上下滑）
                    swipePagingActive = true,
                    // 等首帧加载层：深色底 + 大尺寸粒子，首帧到达后淡出
                    swipeWaitingFirstFrame = waitingFirstFrame,
                    // 失败态：加载层留着并切成"错误 + 重试"（见 VideoPlayerPane 的 KDoc）
                    swipeFailed = playbackFailed,
                    // SW-13：失败文案按类型给"人话"（网络/取流/解码）
                    swipeErrorText = swipeErrorKind?.let(SwipeErrorPolicy::messageOf),
                    onRetrySwipe = onRetryPlayback,
                    // 等首帧加载层用它铺底（与 SwipeCoverPage 同一个封面、同一种取景），
                    // 消灭"封面 → 一块黑布 → 视频"的遮罩观感
                    swipeCoverUrl = entry.cover,
                    // 侧栏显隐上报：互动栏在侧栏打开时让位（见下方互动栏接线）
                    onPlaybackSheetVisibilityChange = {
                        playbackSheetVisible = it
                        // 防烧屏：面板开/关都是交互 → 常驻件回满亮
                        lastChromeInteractionMs = android.os.SystemClock.elapsedRealtime()
                    },
                    // 手动横屏：会话锁竖屏，横屏视频要满屏只能用户显式要一次
                    landscapeForced = landscapeForced,
                    onToggleLandscape = onToggleLandscape.takeIf { !isPortraitVideo }
                )
            } else {
                SwipeCoverPage(
                    entry = entry,
                    isLoading = page == pagerState.currentPage && detailLoading,
                    // SW-10：封面页按"离落定位置的距离"做轻微缩放 + 淡出，
                    // 让翻页有"带走感"而不是硬切。读值在 graphicsLayer（绘制阶段）。
                    modifier = Modifier.graphicsLayer {
                        val offset = (pagerState.currentPage - page) +
                            pagerState.currentPageOffsetFraction
                        val t = SwipePageTransformPolicy.transformFor(offset, reduceMotion)
                        scaleX = t.scale
                        scaleY = t.scale
                        alpha = t.alpha
                    }
                )
            }
        }

        // 队列尾部的反馈：只有停在**最后一页**时才提示，否则会打扰正常浏览。
        // 以前滑到最后一条什么都看不到：用户分不清"卡住了"还是"真的没有更多"。
        // 提示要抬到底部信息层（遮罩+标题，见 VideoPlayerPane 的底部信息层 v2）
        // 之上，否则会压在标题上。
        if (pagerState.currentPage >= pages.lastIndex && tailState != SwipeQueueTail.Idle) {
            SwipeQueueTailHint(
                state = tailState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = BiliSpacing.xxxl * 6f)
            )
        }

        // ── 会话级常驻**底部三件套**（用户真机反馈：翻页时下方简介"从下往上跳动"）──────
        // 与互动栏同一条决策：底部 chrome 曾长在 pane 里（pane 每页一个）→ 翻页时跟着
        // 页面位移；提升为会话级单例后原地换数据、不随页面位移，且只组合一份（省重组）。
        // **无门控**（2026-10-02 二次修正）：曾写 `if (!waitingFirstFrame)`，而翻页 veil
        // 每次亮 ~0.5s → 整块"消失再出现"，与互动栏同款毛病。常驻件就该常驻——
        // 它在加载层之上（与互动栏同一 z 关系）。
        // 块内高度也已恒定（粘滞标题/保底两行/话题单行预留），不会再靠内容高度把它顶动。
        // 互动栏头像/信息层 @名 的出口（SW-20）：mid 为 0（relate 源无 mid）时不接。
        // **构建一次、两处共用**：原先互动栏与底部信息层各自拼一遍 SpaceRoute（重复代码）。
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
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(SWIPE_BOTTOM_ZONE_FRACTION)
                .applyBurnIn(chromeBurnIn)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(SwipeBottomScrimBrush)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
            ) {
                SwipeSessionInfoLine(
                    title = playbackState.detail?.title,
                    owner = playbackState.detail?.owner?.name,
                    tags = playbackState.detail?.tags.orEmpty(),
                    seasonTitle = playbackState.detail?.season?.title,
                    staffs = playbackState.detail?.staffs.orEmpty(), // PT-08：联合创作成员
                    onOpenSpace = openOwnerSpace,
                    resetKey = playbackState.ids.aid.takeIf { it > 0L }
                        ?: playbackState.ids.bvid,
                    // 进度条删除后，手势条 clearance 由信息层自己承担（见常量注释）。
                    modifier = Modifier.padding(bottom = SwipeBottomInfoInset)
                )
                // 底部**细进度条 / 可拖 seek** 已按用户决定移除（2026-10-02：
                // 「把底部细进度条这个功能删掉，我不需要」）：常驻指示条、拖动 seek、
                // 时间气泡，以及 core:swipe 里只服务于它的判据与自动翻页的「拖动中抑制」
                // 入参一并删除（守卫：feature:video 的 `swipeProgressLineStaysRemoved`）。
                // 若将来要恢复，需连同判据与守卫一起恢复，不要只把组件加回来。
            }
        }

        // ── 会话级常驻互动栏（用户定稿 v3；v4 对齐 BiliPai 手感；v6 去掉翻页闪烁）──────
        // 点赞/评论/分享**只此一份**、钉在页面层之上：
        // - 上下滑时它不再跟着整页"滑走再滑回来"（用户反馈的"整体向上调动"），
        //   原地换数据（BiliPai 手法：覆盖层是**纯参数组件**，数据用本层**已收集**
        //   的 playbackState 传入，不在调用点重复 collect——一次订阅、多处读值）；
        // - 位置整体上移（用户定稿"向上"）：悬在底部信息块（UP+标题）上方，
        //   不再与标题/进度条同排拥挤；
        // - 显隐走 AnimatedVisibility 淡入淡出（BiliPai 同款），不再"啪一下"弹出；
        // - **门控只有"让位"两类**（播放设置侧栏 / 评论卡 / 收藏面板）：
        //   v6 去掉了 `!waitingFirstFrame`——翻页 veil 每次会亮 ~0.5s，带上它等于
        //   "每次下滑互动栏先消失再出现"（用户反馈的体验问题）。常驻件的常驻性
        //   优先于"加载层期间让位"；计数的短暂缺失由 rail 内部的粘滞值兜住。
        // 互动栏头像/信息层 @名 的出口（SW-20）：mid 为 0（relate 源无 mid）时不接
        // （声明在底部三件套之前——信息层的 @名 与互动栏头像共用这一份）
        AnimatedVisibility(
            visible = !playbackSheetVisible && !showComments && !showFavSheet,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            SwipeSessionActionRail(
                liked = playbackState.detail?.userState?.liked == true,
                likeText = playbackState.detail?.stat?.like,
                onToggleLike = viewModel::toggleLike,
                replyText = playbackState.detail?.stat?.reply,
                onOpenComments = {
                    showComments = true
                    // 防烧屏：打开面板也算交互 → 常驻件回满亮（下同）
                    lastChromeInteractionMs = android.os.SystemClock.elapsedRealtime()
                },
                // SW-20：点赞长按 = 一键三连（结果经 VM.msg → VideoScreen 的 Toast 提示）
                onLongPressLike = viewModel::triple,
                // SW-20：UP 头像 → 主页（与底部信息层 @名 共用同一个出口，见 openOwnerSpace）
                ownerFace = playbackState.detail?.owner?.face,
                ownerName = playbackState.detail?.owner?.name,
                onOpenSpace = openOwnerSpace,
                // SW-24：更多面板（不感兴趣/弹幕开关/复制链接/系统分享）
                onOpenMore = {
                    showMoreSheet = true
                    lastChromeInteractionMs = android.os.SystemClock.elapsedRealtime()
                },
                faved = playbackState.detail?.userState?.faved == true,
                favText = playbackState.detail?.stat?.fav,
                onOpenFavorite = {
                    // 弹不弹由 `openFavSheet()` 的返回值决定：未登录时它只弹 toast 并返回
                    // false，无条件弹就会把一层空白的"暂无收藏夹"面板盖在"请先登录"上面
                    // （详情页实测踩过，见 VideoDetailPage 的同一判据）。
                    if (viewModel.openFavSheet()) {
                        showFavSheet = true
                        lastChromeInteractionMs = android.os.SystemClock.elapsedRealtime()
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

    // 评论：**悬浮卡片**（用户明确要求：不要占满屏的弹层）。
    //
    // 为什么不是 `ModalBottomSheet`：连播里弹层会把整屏盖住、只剩一条评论列表，
    // 用户失去"我在看哪支视频"的上下文。悬浮卡片贴底、底下视频仍在，
    // 点卡片外任意处关闭 —— 与短视频应用的评论浮层一致。
    //
    // 观感与性能定稿（v5，用户反馈「使用体验很卡 / 评论区风格非常奇怪」）：
    // - **主题实底**（surface，随浅色/深色主题），不再做背景模糊——大面模糊是
    //   逐帧 RenderEffect，评论列表滚动期间持续吃 GPU，卡顿的主因之一；
    //   之前的"磨砂板上浮白卡"双层观感也随之消失：实底面板上条目自然融合；
    // - 顶部圆角（sheetTop）贴底 + 投影，形状 remember 一次，不随重组重建。
    if (showComments) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = COMMENT_SCRIM_ALPHA))
                    .clickable(onClick = { showComments = false })
            )
            val commentCardShape = remember {
                RoundedCornerShape(
                    topStart = BiliRadius.sheetTop,
                    topEnd = BiliRadius.sheetTop
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = commentCardShape,
                shadowElevation = COMMENT_CARD_ELEVATION,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(COMMENT_CARD_HEIGHT_FRACTION)
            ) {
                CommentPanel(
                    subject = viewModel.commentSubject,
                    isActive = true,
                    contentPadding = PaddingValues(
                        horizontal = BiliSpacing.md,
                        vertical = BiliSpacing.sm
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // 会话内收藏：**底部面板**，不是评论那样的悬浮卡。
    // 区别在于交互性质：评论要"边看边刷"（盖住画面就失去上下文），而收藏夹是一次
    // "选完即提交"的短表单 —— 与详情页同一形态，用户不必学两种样式。
    // 形状/拉手/遮罩三件套也走同一出口（biliSheetShape / BiliSheetGrabber），
    // 免得会话里的弹层与详情页差一个圆角半径。
    if (showFavSheet) {
        ModalBottomSheet(
            onDismissRequest = { closeFavSheet() },
            sheetState = favBottomSheetState,
            shape = biliSheetShape(),
            scrimColor = Color.Black.copy(alpha = 0.40f),
            dragHandle = { BiliSheetGrabber() }
        ) {
            FavFolderSheetContent(
                state = favSheetState,
                onToggle = viewModel::toggleFavFolder,
                onCreateFolder = viewModel::createFolderInSheet,
                onSubmit = viewModel::submitFav
            )
        }
    }
    // SW-24：「更多」面板（会话级单实例）。用 ModalBottomSheet（与收藏面板同款形态）。
    if (showMoreSheet) {
        val currentEntry = pages.getOrNull(pagerState.currentPage)
        ModalBottomSheet(
            onDismissRequest = { closeMoreSheet() },
            sheetState = moreSheetState,
            shape = biliSheetShape(),
            scrimColor = Color.Black.copy(alpha = 0.40f),
            dragHandle = { BiliSheetGrabber() }
        ) {
            SwipeMoreSheetContent(
                // 不感兴趣：只有 feed 源带负反馈通道；relate/story 源没有 → 不显示（不做假按钮）
                canDislike = currentEntry?.dislikeContext != null,
                canUndoDislike = swipeDislikeUndoable,
                onDislike = {
                    val entry = currentEntry ?: return@SwipeMoreSheetContent
                    if (viewModel.submitSwipeDislike(entry, pagerState.currentPage)) {
                        lastChromeInteractionMs = android.os.SystemClock.elapsedRealtime()
                        showMoreSheet = false
                    }
                },
                onUndoDislike = {
                    viewModel.undoSwipeDislike()
                    showMoreSheet = false
                },
                onToggleDanmaku = { viewModel.toggleDanmaku() },
                onCopyLink = {
                    val url = ShareUtils.videoUrl(playbackState.ids.bvid, playbackState.ids.aid, page = 0)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("video_url", url))
                    Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                    showMoreSheet = false
                },
                onShare = {
                    val url = ShareUtils.videoUrl(playbackState.ids.bvid, playbackState.ids.aid, page = 0)
                    ShareUtils.shareText(context, playbackState.detail?.title, url)
                    showMoreSheet = false
                }
            )
        }
    }
    } // CompositionLocalProvider(LocalSwipeChromeBurnIn)
}

/**
 * SW-24「更多」面板内容：不感兴趣（+ 撤回）/ 弹幕开关 / 复制链接 / 系统分享。
 *
 * 为什么只有这几项（如实）：举报无接口、稍后再看仓库只读（`WatchLaterRepository` 仅
 * fetchPage）、下载在详情页已有完整流程（会话内不新造第二条入口）、画质在播放设置侧栏
 * ——都不在这里做假按钮。这里的每一项都直连既有能力。
 */
@Composable
private fun SwipeMoreSheetContent(
    canDislike: Boolean,
    canUndoDislike: Boolean,
    onDislike: () -> Unit,
    onUndoDislike: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BiliSpacing.lg)
            .padding(bottom = BiliSpacing.xxxl)
    ) {
        if (canDislike) {
            SwipeMoreRow(text = "不感兴趣", onClick = onDislike)
        }
        if (canUndoDislike) {
            SwipeMoreRow(text = "撤回不感兴趣", onClick = onUndoDislike)
        }
        SwipeMoreRow(text = "弹幕开关", onClick = onToggleDanmaku)
        SwipeMoreRow(text = "复制链接", onClick = onCopyLink)
        SwipeMoreRow(text = "系统分享", onClick = onShare)
    }
}

/** 「更多」面板的一行（token 间距；整行可点，触摸高度 ≥48dp）。 */
@Composable
private fun SwipeMoreRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = BiliSpacing.md)
    )
}

/** 评论悬浮卡片高度占屏比：留出上方约 1/3 屏，视频仍然可见。 */
private const val COMMENT_CARD_HEIGHT_FRACTION = 0.68f

/** 卡片外遮罩：够暗以突出卡片，又不至于让视频"消失"。 */
private const val COMMENT_SCRIM_ALPHA = 0.42f

/** 卡片阴影高度（4dp 档：浮起但不"飘"）。 */
private val COMMENT_CARD_ELEVATION = 8.dp

/** 玻璃卡片描边：白色微光，勾出卡片轮廓（与中央播放键同档）。 */
private val CommentSheetBorderColor = Color.White.copy(alpha = 0.18f)

/**
 * 会话底部信息块（@名/标题/话题）的底部内边距：抬到系统手势导航条之上。
 *
 * 2026-10-02：这条 clearance 原先由被删除的底部细进度条提供（它的底部内边距是
 * `BiliSpacing.xxxl`）；进度条没了之后，若不给信息层补上，话题行会贴着屏幕下沿、
 * 与手势条打架。值沿用同一档 token，位置改由信息层自己承担。
 */
private val SwipeBottomInfoInset = BiliSpacing.xxxl

private const val TAG = "PortraitSwipe"

/**
 * 队列尾部提示（停在最后一页时）：
 * - [SwipeQueueTail.Extending]：还在补货 —— 让用户知道"系统在忙"，不是卡住；
 * - [SwipeQueueTail.Exhausted]：补货跑完也没拿到新的 —— 明确"到头了"。
 *
 * 不可点、不消费触摸（上下滑与返回手势照常）；间距走 [BiliSpacing] token，
 * 不新增 dp 字面量（`DesignTokenLintTest` 的棘轮）。
 */
@Composable
private fun SwipeQueueTailHint(
    state: SwipeQueueTail,
    modifier: Modifier = Modifier,
) {
    val text = when (state) {
        SwipeQueueTail.Extending -> "正在为你找更多视频…"
        SwipeQueueTail.Exhausted -> "已经是最后一个了"
        SwipeQueueTail.Idle -> return
    }
    Box(
        modifier = modifier
            .padding(bottom = BiliSpacing.xxxl)
            .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.extraLarge)
            .padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.smPlus)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** 封面页加载动画的边长（占屏宽比例）。比播放器页的加载层小一档：它压在封面之上。 */
private const val COVER_LOADER_WIDTH_FRACTION = 0.26f

/**
 * "停稳"判定用的偏移容差。
 *
 * **不能判 `offset == 0f`**：真机上停稳时 `currentPageOffsetFraction` 往往不是精确的 0
 * （浮点残差 / pager 的 settle 收敛），等于 0 的判据会让"停稳预取"这一支永远不触发 ——
 * 实测症状就是"进场后完全没有预取日志，等用户滑一次才预取"。
 */
private const val SETTLED_OFFSET_EPSILON = 0.02f

/**
 * 非当前页：封面铺满 + 当前页取流中的加载指示。
 *
 * ⚠️ 这里**不再有**底部标题/UP 信息条（2026-10-02 删除，用户第二次反馈"底部简介还是会动"）：
 * 它长在**页面内部**，翻页时会跟着页面从下往上滑进来——而会话级信息层（pager 的底部三件套）
 * 已经在同一位置常驻显示同一份 @名/标题，两者重复且后者才是"钉住不动"的那一层。
 * 删掉后封面页只剩封面 + 加载指示，翻页时底部不再有任何东西位移。
 */
@Composable
private fun SwipeCoverPage(
    entry: SwipeEntry,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (entry.cover.isNotBlank()) {
            BiliAsyncImage(
                url = entry.cover,
                contentDescription = entry.title,
                modifier = Modifier.fillMaxSize(),
                // **保持原始比例 + Fit 居中**（用户反馈：「预览图完美地显示在视频中间」）：
                // 之前用 CardCoverLarge（960×540 裁切）+ Crop 铺满，竖屏视频的封面被切成
                // 16:9 再放大铺满整屏，主体被切掉、比例也不对。改成不裁切的限宽大图 +
                // Fit 之后，预览图的取景与接下来播放的视频**完全一致**
                // （视频也是按自身比例居中 letterbox），封面 → 视频的交接不再有跳动。
                variant = BiliImageVariant.PlayerPreview,
                contentScale = ContentScale.Fit
            )
        }
        if (isLoading) {
            // 粒子加载（用户要求"等待加载时给一个高级的加载动画"）：
            // 与播放器页的等首帧加载层用同一个组件，两处观感一致。
            // 封面可能是浅色的，先压暗一档再叠粒子，保证白粒子始终读得清。
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            BiliParticleLoader(
                modifier = Modifier
                    // 大尺寸：占屏宽 26% 的正方形（不写 dp 字面量，跟着屏幕走）
                    .fillMaxWidth(COVER_LOADER_WIDTH_FRACTION)
                    .aspectRatio(1f)
            )
        }
    }
}
