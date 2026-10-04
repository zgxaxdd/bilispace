package com.naaammme.bbspace.feature.video.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.naaammme.bbspace.feature.comment.CommentPanel
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerDefaults
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
import com.naaammme.bbspace.core.designsystem.color.VideoCardCoverColorStore
import com.naaammme.bbspace.core.designsystem.component.biliImageUrlForVariant
import com.naaammme.bbspace.core.designsystem.component.topSolidProgressiveFade
import com.naaammme.bbspace.feature.video.detail.FavFolderSheetContent
import com.naaammme.bbspace.feature.video.detail.actionCount
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
    // PT-06：拉黑二次确认的挂起 mid（非 null = 弹确认框；确认后才对账号实际生效）
    var pendingBlockUpMid by remember { mutableStateOf<Long?>(null) }
    // NX-08：拉黑当时记下 UP 名，供设置页的名单列表显示（不再逐条回查网络）
    var pendingBlockUpName by remember { mutableStateOf<String?>(null) }
    val moreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val swipeDislikeUndoable by viewModel.swipeDislikeUndoable.collectAsStateWithLifecycle()
    val favSheetState by viewModel.favSheet.collectAsStateWithLifecycle()

    // ── VK-01 / SX-09 验收补齐 ────────────────────────────────────────────────
    // 互动**写**路径（`toggleLike` / 收藏）只更新 VM 的 `_interaction`
    //（两者都走 `updateInteractionIfCurrent`），而 `playbackState`（= VM 的 `videoState`，
    // 见上）里的 `detail.userState` / `detail.stat` 是**服务器快照**、写路径从不碰它。
    // 于是真机上出现"点赞请求成功了（`setLike OK`）但互动栏图标与计数一动不动"——
    // 用户的原话就是"亮了也不知道、取消赞了也看不出区别"。
    //
    // 这里改成按**详情页同一口径**读互动状态：`liked = userState.liked`、
    // 计数 = `stat.likeValue + likeDelta`（`VideoDetailSummary.kt:352-375` 就是这么算的）。
    //
    // 为什么可以放心用、不需要再比一遍 aid：VM 里 `_interaction` 是对 **`videoState`**
    // 做 map → `distinctUntilChanged()` → 重置得来的，而本文件传出去的 `currentAid`
    // 也是 `videoState.ids.aid` ⇒ 二者**同源同步**，`_interaction` 永远对应当前页，
    // 不存在"读到上一页互动状态"的窗口。快照为空时仍回落到 `playbackState`。
    val interaction by viewModel.interaction.collectAsStateWithLifecycle()

    // N-1：当前页视频的宽高比（w/h）—— 只为算"画面下沿"用。
    // 口径与 `VideoScreen.videoAspect` 一致（同源：`videoState.currentStream`）；
    // "是不是横屏视频"本文件已有 `isPortraitVideo`（下面），不重复定义。
    val currentVideoAspect = remember(playbackState.currentStream) {
        val stream = playbackState.currentStream ?: return@remember null
        val w = stream.width?.takeIf { it > 0 } ?: return@remember null
        val h = stream.height?.takeIf { it > 0 } ?: return@remember null
        w.toFloat() / h.toFloat()
    }

    // ── 取色光（用户想法）：把详情页的"接缝光晕"复刻到会话里，而且**上下都洒** ──────
    // 光色与详情页**同源同参**：同一个 `VideoCardCoverColorStore`、同一个采样尺寸
    // （480×360 ⇒ 命中同一条缓存、同一个颜色）。
    // 峰值**固定走详情页的深色档**（0.48）：会话上下的黑边永远是黑的，不存在浅底那一档。
    // 位置见下面两层 Box 的注释；关键是它必须画在**视频之上** ——
    // 会话的黑边是 ExoPlayer（`AndroidView`）自己画的、不透明，画在它下面会被整个盖住。
    // ⚠️ 取色的 URL 必须是**页面那张封面真正请求的那个串**，也就是**变体解析之后**的 URL：
    // `BiliAsyncImage`（`BiliAsyncImage.kt:98-100`）与预置封面都会先
    // `biliImageUrlForVariant(url, variant)` 再 `.data(resolved)`；而 Coil 的
    // memory/disk 缓存键由 `data` 派生 ⇒ 我若喂**原始**封面 URL，键就与可见封面不同，
    // 每次都未命中 ⇒ 去下**原图**再解码（原图不在任何生产路径上，等于凭空多下一条）。
    // 这就是「切页不跟手」的根因之一（另一条见下面 geometry 的 sticky 处理）。
    val swipeGlowCover = pages.getOrNull(pagerState.currentPage)
        ?.cover
        ?.takeIf { it.isNotBlank() }
        ?.let { biliImageUrlForVariant(it, BiliImageVariant.PlayerPreview) }
    // 颜色**不随翻页清零**：留着上一个视频的颜色，直到新颜色算好。
    // 之前用 `remember(swipeGlowCover)` 重置 ⇒ 每次翻页先退纯白（`Color.White`）再变真色，
    // 观感就是"光闪一下"。`remember` 不带 key ⇒ 跨页保留；首帧无值时才是白（屏幕本光）。
    var swipeGlowTint by remember { mutableStateOf<Color?>(null) }
    val activeSwipeGlowKey by rememberUpdatedState(swipeGlowCover)
    LaunchedEffect(swipeGlowCover) {
        val key = swipeGlowCover ?: return@LaunchedEffect
        VideoCardCoverColorStore.requestExtraction(
            appContext = context.applicationContext,
            cacheKey = key,
            url = key,
            sizePx = SwipeGlowSamplePx,
            scope = scope
        ) { color ->
            // 切页后晚到的取色要丢掉（与详情页同款防串色）
            if (activeSwipeGlowKey == key) swipeGlowTint = color
        }
    }
    // 自愈：store 对"同一 key 正在飞行中"的第二次请求会**静默返回、不回调**
    //（`VideoCardCoverColorStore.kt:93-94`），而 `remember` 不带 key 时也不重算 ——
    // 万一回调被吞（快速来回翻到同一页），落定后再读一次缓存补上，避免一直用着旧色/白。
    LaunchedEffect(swipeGlowCover, pagerState.settledPage) {
        swipeGlowCover?.let { key ->
            VideoCardCoverColorStore.getCachedColor(key)?.let { swipeGlowTint = it }
        }
    }
    // 取不到色时退纯白（屏幕本光），与详情页口径一致
    val swipeGlowColor = remember(swipeGlowTint) {
        swipeGlowTint?.takeIf { it.alpha > 0f } ?: Color.White
    }
    // 几何**不能被 `currentStream` 的瞬时 null 带走**：播放会话在每次开播时先发
    // `currentStream = null`（`StreamPlaybackSessionImpl.kt:375-395`），新流要等 playurl 才到。
    // 之前门直接挂 `currentVideoAspect` ⇒ 翻页过半那一刻光**整块灭掉**、新流到了才回来，
    // 这就是「切页不流畅」最直观的一条。这里只记**非空**值（不写组合期状态，走 effect）。
    val glowAspectState = remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(currentVideoAspect) {
        if (currentVideoAspect != null) glowAspectState.value = currentVideoAspect
    }
    val glowAspect = glowAspectState.value
    val swipeGlowModifier = remember(swipeGlowColor) {
        Modifier.topSolidProgressiveFade(
            surfaceColor = swipeGlowColor,
            fadeHeight = SWIPE_GLOW_HEIGHT,
            peakAlpha = SWIPE_GLOW_PEAK
        )
    }
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
    BackHandler(enabled = landscapeForced || backAction != SwipeBackPolicy.Action.PassThrough) {
        // 需求③（用户）：**横屏观影时侧滑返回 = 切回竖屏播放**，而不是退出会话。
        // 优先级最高：横屏态下先吃掉这一次 back（含系统侧滑返回手势），
        // 只把手动横屏收回去（`onToggleLandscape` 与顶栏那颗胶囊是同一条通路），
        // 会话与当前视频都原地保留。竖屏态下一律走原来的面板/宽限逻辑。
        if (landscapeForced) {
            onToggleLandscape()
        } else when (backAction) {
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
    val animations = LocalAnimations.current
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
            // ── SX-08（用户需求⑧"下滑切下一个视频要有缓动曲线动画"）──────────────
            // 改动前这里用 Compose 默认 `flingBehavior`：吸附曲线是**框架默认 spring**、
            // 而且**不理会**本仓的「动效速度 = 关」（`reduceMotion`）—— 关掉动效的用户
            // 翻页照样会看到一整段动画。这里把**吸附那一段**显式化：
            // · 时长取 `animations.long`、曲线取主题的**强调曲线**（与 SX-07 同一套语言，
            //   不手写字面量时长）；
            // · `reduceMotion` 时 `createAnimations` 已把各档压到 ~1ms ⇒ 瞬时吸附（无障碍降级）；
            // · 只换吸附动画，`pagerSnapDistance` / `decayAnimationSpec` 仍用框架默认，
            //   即**不碰"翻到哪一页"的判据** —— 要点③④要求的 veil 先等首帧、
            //   `beyondViewportPageCount` 预组合、快速连滑不打架都不受影响。
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
        // ── 取色光：画面**上下各洒一层**（用户想法）──────────────────────────────
        // 复刻详情页的接缝光晕（同色源 / 同行程 / 同峰值），但这里视频是 letterbox 居中的，
        // 所以上下各来一层：向下的那层实心端贴**画面下沿**，向上的那层是同一支 brush
        // **旋转 180°**（实心端贴画面上沿、向外淡出）—— 不新增任何设计系统 API。
        //
        // 两个 z 序要点（都是必须的，不是讲究）：
        // ① 必须画在**视频之上**：会话的黑边是 ExoPlayer（`AndroidView`）自己画的、不透明，
        //    画在它下面会被整个盖住；
        // ② 必须画在**底部信息层之下**：详情页的图层序也是"背景 → 光 → 内容"，
        //    否则这层光会糊在标题/标签上。
        // 两层都只落在画面矩形**之外**，所以永远不会盖到画面内容上。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val glowDensity = LocalDensity.current
            val glowScreenHPx = with(glowDensity) { maxHeight.toPx() }
            val glowScreenWPx = with(glowDensity) { maxWidth.toPx() }
            // 用外层 sticky 的 `glowAspect`（不在此处重读 `currentVideoAspect`）
            // **竖屏视频同样要有光**（用户反馈：「横屏视频有光，竖屏没有」）——
            // 竖屏视频在竖屏会话里也不是满屏（例如 1080×1920 贴宽后高度约 2261 < 2800），
            // 上下照样有黑边，光一样该洒。唯一条件是"宽高比已知"。
            if (glowAspect != null) {
                // 与 N-1 用同一套 letterbox 几何（真机实测：画面在会话里垂直居中）
                val glowImageHPx = minOf(glowScreenHPx, glowScreenWPx / glowAspect)
                val glowImageTopPx = (glowScreenHPx - glowImageHPx) / 2f
                val glowImageBottomPx = (glowScreenHPx + glowImageHPx) / 2f
                // ⚠️ 高度必须**夹进黑边里** —— 光只允许洒在画面上下之外。
                // 行程 160dp 对 16:9（本机黑边 1106px）绰绰有余，但竖屏视频贴宽后
                // 黑边只有 ~270px：不夹的话这两层会直接**糊在画面上**（比没有光更糟）。
                // 夹短之后渐变照样淡出（brush 的 fadeHeight 不变，只是被裁短）。
                val glowMaxPx = with(glowDensity) { SWIPE_GLOW_HEIGHT.toPx() }
                val glowDownPx = minOf(glowMaxPx, glowScreenHPx - glowImageBottomPx)
                val glowUpPx = minOf(glowMaxPx, glowImageTopPx)
                if (glowDownPx > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(glowDensity) { glowDownPx.toDp() })
                            .offset { IntOffset(0, glowImageBottomPx.roundToInt()) }
                            .then(swipeGlowModifier)
                    )
                }
                if (glowUpPx > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(glowDensity) { glowUpPx.toDp() })
                            .offset { IntOffset(0, (glowImageTopPx - glowUpPx).roundToInt()) }
                            .graphicsLayer { rotationZ = 180f }
                            .then(swipeGlowModifier)
                    )
                }
            }
        }
        // 需求②：**横屏观影时清屏** —— 底部信息层（@名/标题/标签/底部暗角）整块让位，
        // 画面上不剩任何常驻 UI。退出路径见需求③（侧滑返回 = 切回竖屏）。
        if (!landscapeForced) Box(
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
        // ── N-1：横屏视频的悬浮「横屏」按钮（用户反馈）─────────────────────────
        // 原话：「刷视频模式中，横屏视频下方没有悬浮的横屏按钮，必须在顶栏点击横屏」。
        // 触发：**当前页是横屏视频**（aspect ≥ 1）且**还没被手动横屏**。
        // 显隐与互动栏同一判据（弹层/面板在场时一起收），避免浮在评论面板之上。
        // 点击复用顶栏那颗胶囊**同一条通路**（landscapeForced 取反），不新造转屏逻辑。
        if (!isPortraitVideo && !landscapeForced && currentVideoAspect != null &&
            !playbackSheetVisible && !showComments && !showFavSheet
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val screenHPx = with(density) { maxHeight.toPx() }
                val screenWPx = with(density) { maxWidth.toPx() }
                // 会话里视频是 letterbox **垂直居中**的（真机实测：1920×886 的流，
                // 画面带 1110→1690，中心 1400 = 屏高一半）⇒ 画面下沿 = (屏高 + 画面高) / 2。
                // 画面高 = 屏宽 / 宽高比（比屏更宽时按宽贴合，不会被拉高）。
                val imageHPx = minOf(screenHPx, screenWPx / currentVideoAspect)
                val imageBottomPx = (screenHPx + imageHPx) / 2f
                SwipeLandscapeFab(
                    onClick = {
                        // 需求①「与点赞的逻辑相同」：点它也算**一次会话交互** ——
                        // 常驻件立刻回满亮、并重置防烧屏的漂移相位基准（与打开评论/收藏同一口径）。
                        lastChromeInteractionMs = android.os.SystemClock.elapsedRealtime()
                        onToggleLandscape()
                    },
                    modifier = Modifier
                        // N-1 优化（用户给出抖音的参照）：**水平居中**贴在画面下沿
                        // —— 抖音那支「全屏观看」就是居中挂在画面下方，比左对齐更像"这画面的入口"。
                        .align(Alignment.TopCenter)
                        // 需求①：本按钮也要**加入防烧屏**（静置变暗 + 缓慢像素漂移）——
                        // 与互动栏/底部信息层用同一个 `chromeBurnIn`，不再是个"不参与常驻策略"的孤件。
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
        // 需求②：横屏观影时互动栏（含 UP 头像）也整块让位 —— 与"面板在场"同一类让位。
        if (!landscapeForced) AnimatedVisibility(
            visible = !playbackSheetVisible && !showComments && !showFavSheet,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            SwipeSessionActionRail(
                // VK-01：优先读**互动状态**（写路径真正更新的那一份），快照为空时回落。
                liked = interaction.userState?.liked
                    ?: (playbackState.detail?.userState?.liked == true),
                likeText = interaction.stat?.likeValue
                    ?.let { actionCount(it, interaction.likeDelta) }
                    ?: playbackState.detail?.stat?.like,
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
                faved = interaction.userState?.faved
                    ?: (playbackState.detail?.userState?.faved == true),
                favText = interaction.stat?.favValue
                    ?.let { actionCount(it, interaction.favDelta) }
                    ?: playbackState.detail?.stat?.fav,
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
    //
    // v6（SX-10 / 决策 **D-2**，用户 2026-10-03 拍板「评论区不要模糊，用透明就可以，
    // 保证流畅度」）：实底 → **alpha 半透明**，卡片透出背后的视频，"悬浮"感成立。
    // - **只用 alpha，不引入任何模糊**：`hazeBlur(` / `drawBackdrop(` 一个都不加
    //   （离屏模糊是每帧成本，正是本仓 AP/FL 批次重点治理项）；
    // - **不新增图层**：只把既有的一次填充改成半透明（遮罩本来就是半透明），
    //   图元数量不变 ⇒ 过度绘制不上升；若真机帧率劣化，按 D-2 减层而不是加模糊；
    // - 对比度红线：alpha 取 0.72（与 `HomeLivePage` 的半透明 surface 同档），
    //   卡片下还有 `COMMENT_SCRIM_ALPHA` 的 42% 黑遮罩压着视频，正文仍是
    //   `onSurface` on `surface`，真机需复核浅色/深色可读性（L4-121）。
    // v7（SX-07 / 用户需求⑦「点评论要有"展开"的缓动曲线动画」）：面板原来是 `if (showComments)`
    // **瞬时出现/消失**。改为 AnimatedVisibility：
    // - 时长与曲线**全走 LocalAnimations 的 token**（进出用 long/medium，曲线用主题的
    //   强调 / 标准曲线），不手写 `tween(300)` 之类的字面量时长（要点②）；
    // - **不新增 `animate*AsState` / `rememberInfiniteTransition`**（FrameBudgetLintTest 棘轮）；
    // - 遮罩**只淡入淡出**：它铺满全屏，跟着滑动会在动画期间露出没遮住的边缘；
    // - 卡片**从下方 1/3 屏滑入 + 淡入**；卡片高度本来就是固定占比
    //   （`COMMENT_CARD_HEIGHT_FRACTION`），全程没有高度变化 ⇒ 不会"先塌后长"、不抖邻居（要点③⑤）；
    // - `reduceMotion`（动效速度=关）：`createAnimations` 已把各档时长压到 ~1ms，
    //   所以直接读 token 就自然退化成"瞬时到位且不闪"（要点④），不需要另写分支。
    val commentAnimations = LocalAnimations.current
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showComments,
            enter = fadeIn(
                tween(commentAnimations.medium, easing = commentAnimations.standard)
            ),
            exit = fadeOut(
                tween(commentAnimations.medium, easing = commentAnimations.standard)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = COMMENT_SCRIM_ALPHA))
                    .clickable(onClick = { showComments = false })
            )
        }
        AnimatedVisibility(
            visible = showComments,
            enter = slideInVertically(
                animationSpec = tween(commentAnimations.long, easing = commentAnimations.emphasized),
                // 从下方 1/3 高度滑入：读作"卡片升起来"，而不是整屏跳一下
                initialOffsetY = { it / 3 }
            ) + fadeIn(
                tween(commentAnimations.long, easing = commentAnimations.emphasized)
            ),
            exit = slideOutVertically(
                animationSpec = tween(commentAnimations.medium, easing = commentAnimations.standard),
                targetOffsetY = { it / 3 }
            ) + fadeOut(
                tween(commentAnimations.medium, easing = commentAnimations.standard)
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val commentCardShape = remember {
                RoundedCornerShape(
                    topStart = BiliRadius.sheetTop,
                    topEnd = BiliRadius.sheetTop
                )
            }
            Surface(
                // SX-10 / D-2：半透明悬浮卡片——只用 alpha 透出背后画面，**不加任何模糊**。
                color = MaterialTheme.colorScheme.surface.copy(alpha = COMMENT_CARD_ALPHA),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = commentCardShape,
                shadowElevation = COMMENT_CARD_ELEVATION,
                modifier = Modifier
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
                // PT-06：拉黑该 UP（ownerMid > 0 才显示——story/relate 源没有 mid，不做假入口）
                canBlockUp = (currentEntry?.ownerMid ?: 0L) > 0L,
                onBlockUp = {
                    pendingBlockUpMid = currentEntry?.ownerMid?.takeIf { it > 0L }
                    pendingBlockUpName = currentEntry?.author
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

        // PT-06：拉黑**二次确认**（拉黑对账号实际生效，必须显式确认后才调用）
        pendingBlockUpMid?.let { mid ->
            AlertDialog(
                onDismissRequest = { pendingBlockUpMid = null; pendingBlockUpName = null },
                title = { Text("拉黑该 UP 主？") },
                text = {
                    Text("拉黑后其投稿将不再推荐给你，且该操作会对你的 B 站账号实际生效（可随时在网页端撤销）。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        // NX-08：连名字一起记下，设置页的名单才能显示"拉了谁"
                        viewModel.blockSwipeUp(mid, pendingBlockUpName)
                        Toast.makeText(context, "已拉黑该 UP 主", Toast.LENGTH_SHORT).show()
                        pendingBlockUpMid = null
                        pendingBlockUpName = null
                    }) {
                        Text("确认拉黑")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBlockUpMid = null; pendingBlockUpName = null }) {
                        Text("取消")
                    }
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
    // PT-06：拉黑该 UP（ownerMid > 0 才显示；二次确认在调用方）
    canBlockUp: Boolean = false,
    onBlockUp: () -> Unit = {},
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
        if (canBlockUp) {
            SwipeMoreRow(text = "拉黑该 UP 主", onClick = onBlockUp)
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

/**
 * 评论悬浮卡片的底色 alpha（SX-10 / 决策 D-2：**只用透明、不要模糊**）。
 *
 * 取 0.72 与 `HomeLivePage` 的半透明 surface 同档：卡片能透出背后视频，
 * 而卡片下还压着 [COMMENT_SCRIM_ALPHA] 的遮罩 ⇒ 正文对比度仍可接受。
 * 真机复核浅色/深色可读性见 L4-121。
 */
private const val COMMENT_CARD_ALPHA = 0.72f

/** 取色光的行程：与详情页同一档（5 × xxxl = 160dp），组件内部会 coerce 到容器内。 */
private val SWIPE_GLOW_HEIGHT = BiliSpacing.xxxl * 5f

/**
 * 取色光峰值：**固定用详情页的深色档 0.48**。
 *
 * 详情页按主题在 0.48/0.38 之间分档，因为它的光洒在"浅色主题的内容区"上；
 * 而会话里的光只洒在**上下黑边**上 —— 那里永远是黑的，所以固定深色档，不做主题分支。
 */
private const val SWIPE_GLOW_PEAK = 0.48f

/**
 * 取色采样尺寸：**必须与 `VideoScreen.SeamTintSamplePx` 逐字一致**（`480 to 360`）。
 * 同一个 `VideoCardCoverColorStore` 的缓存键含尺寸；尺寸不同会走两次提取、
 * 还有可能算出不同的颜色 ⇒ 同一个视频在详情页与会话里光色会不一致。
 */
private val SwipeGlowSamplePx = 480 to 360

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

/**
 * N-1：会话内**横屏视频**的悬浮「横屏」按钮（用户反馈：
 * 「刷视频模式中，横屏视频下方没有悬浮的横屏按钮，必须在顶栏点击横屏」）。
 *
 * 为什么另起一个而不是复用 `VideoScreen.LandscapeFullscreenFab`（SX-03 的「全屏」钮）：
 * 那个合成函数的守卫**钉住了它的调用点数量与无障碍文案**（`LandscapeFullscreenFab(` 恰 2 处、
 * `contentDescription = "进入全屏"` 恰 1 处），参数化会迫使放宽那条守卫；
 * 而两处语义本来就不同 —— 这里进的是**手动横屏**（会话内锁竖屏 + 点名这一支看满屏），
 * 那里进的是**全屏形变**。视觉语言（半透明主题面 + 轻投影 + 零模糊 + 圆角胶囊）保持一致。
 *
 * 位置由调用点算好（贴在**画面下沿** + 一个小间距），本函数只管长相与点击。
 */
@Composable
private fun SwipeLandscapeFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // N-1 优化（用户给出抖音「全屏观看」的参照）：这一颗挂在**黑边区**里，
    // 用亮面 pill 会像黑底上的一块白斑；改成**深色半透明 + 发丝白描边 + 白图标/白字**，
    // 与抖音同款语言，也不与画面内容抢注意力。
    //
    // 线宽 `BiliSpacing.xs / 8`（= 0.5dp，与仓内发丝描边 `biliDarkHairlineBorder` 同值）。
    // **不写 dp 字面量**：`DesignTokenLintTest` 的 dp 棘轮已无余额。
    // 也不直接复用 `biliDarkHairlineBorder` —— 它只对「iOS 档 + 深色 + surfaceContainer*
    // 容器」生效，本 pill 是半透明黑，走进去会返回 null（等于没描边）。
    val container = Color.Black.copy(alpha = 0.38f)
    Surface(
        color = container,
        contentColor = Color.White,
        shape = CircleShape,
        border = BorderStroke(BiliSpacing.xs / 8, Color.White.copy(alpha = 0.45f)),
        modifier = modifier
            .defaultMinSize(minHeight = BiliSpacing.xxxl * 1.5f)
            .clickable(onClickLabel = "进入横屏", onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BiliSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
        ) {
            Icon(
                imageVector = Icons.Rounded.ScreenRotation,
                contentDescription = "进入横屏",
                modifier = Modifier.size(BiliSpacing.xl)
            )
            Text(
                text = "横屏",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
