package com.naaammme.bbspace.feature.video.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import android.util.Log
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.core.designsystem.component.BiliParticleLoader
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.feature.video.VideoViewModel
import com.naaammme.bbspace.feature.video.swipe.SwipeEntry
import com.naaammme.bbspace.feature.video.swipe.shouldExtendSwipeQueue
import com.naaammme.bbspace.feature.video.swipe.swipeKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
    onSwipeTo: (SwipeEntry) -> Unit,
    onNeedMore: () -> Unit = {},
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
    // pager 认定的「应播放页」：以它为准发切换请求，而不是拿 currentKey 反向比较——
    // 后者在取流完成前仍是旧值，快速往返翻页时会造成目标震荡。
    // 身份键 = SwipeEntry.key（bvid 优先 / aid 兜底，见其 KDoc）；**只用于去重**，
    // 「哪一页归播放器」一律走 entry.isCurrentVideo（字符串会在取流完成后翻转）。
    val currentKey = swipeKey(currentBvid, currentAid)
    var requestedKey by remember { mutableStateOf(anchor.key) }
    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                val entry = pages.getOrNull(page) ?: return@collect
                if (entry.key != requestedKey) {
                    requestedKey = entry.key
                    Log.i(
                        TAG,
                        "swipe: page=$page key=${entry.key} aid=${entry.target.aid} bvid=${entry.bvid}"
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
    LaunchedEffect(currentKey, pages) {
        // 取流完成后的对齐：正常路径上 pager 停在的页就是当前播放的视频，直接同步；
        // 若是快速往返翻页的迟到结果（pager 停在用户已离开的页），以用户所在页为
        // 准再发一次切换（openVideo 幂等）——否则 pager 会停在"播放器在看不见的页"的状态。
        // 判据是**内容身份**（isCurrentVideo）而非字符串：见 SwipeEntry.isCurrentVideo。
        //
        // 同步的是 entry.key 而不是 currentKey：requestedKey 的语义是「已请求的那一页」，
        // 用 currentKey 会让「锚点 bvid 为空（story 入口）」这类条目在补货/补流引发的
        // 重新收集里反复判定为"目标变了"（虽然 openSwipeTarget 会短路，但白跑一趟）。
        val entry = pages.getOrNull(pagerState.currentPage)
        if (entry == null) {
            requestedKey = currentKey
        } else if (entry.isCurrentVideo(currentBvid, currentAid)) {
            requestedKey = entry.key
        } else {
            requestedKey = entry.key
            onSwipeTo(entry)
        }
    }
    // 播完自动翻下一页（刷抖音的核心体验，BiliPai 的 autoAdvance 同款）：
    // 当前页是最后一页则不动。播放器 repeat 设置若为单曲循环则 STATE_ENDED 不会发出，
    // 天然与此逻辑互斥。animateScrollToPage 是挂起函数，经外部 scope 派发。
    val player by viewModel.player.collectAsStateWithLifecycle()
    DisposableEffect(player, pages) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                val next = pagerState.currentPage + 1
                if (next > pages.lastIndex) return
                scope.launch { pagerState.animateScrollToPage(next) }
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }
    // 播放器归属诊断：页身份失配（症状="翻页后只有封面、不起播"）在真机上唯一的可观测
    // 证据就是这个三元组。只在两侧变化时打一行，不在重组里刷屏。
    LaunchedEffect(currentKey, pagerState.currentPage, pages) {
        val owner = pages.indexOfFirst { it.isCurrentVideo(currentBvid, currentAid) }
        Log.i(TAG, "bind: page=${pagerState.currentPage} current=$currentKey playerPage=$owner")
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
    // 所以用**粘滞**状态：换视频（currentKey 变化）进入等待，直到"这一支"真的出帧、
    // 或这一支出错（不能让加载层把错误界面永远盖住）才退出。
    val playbackState by viewModel.videoState.collectAsStateWithLifecycle()
    val firstFrameRendered = playbackState.hasRenderedFirstFrame
    val playbackFailed = playbackState.error != null
    var waitingKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentKey) {
        // 首次组合同样置位：锚点视频还没出帧时，一样该看到加载层而不是黑屏
        waitingKey = currentKey
    }
    LaunchedEffect(firstFrameRendered, playbackFailed) {
        if (firstFrameRendered || playbackFailed) waitingKey = null
    }
    val waitingFirstFrame = waitingKey != null
    // 当前视频是不是横屏：决定要不要给"手动横屏"入口（竖屏视频转横屏只会更小）。
    // 与 VideoScreen 的 isPortraitVideo 同源同键（currentStream），不会两处算出不同结论。
    val isPortraitVideo = remember(playbackState.currentStream) {
        val stream = playbackState.currentStream ?: return@remember true
        (stream.width ?: 0) in 1..<(stream.height ?: 0)
    }
    VerticalPager(state = pagerState, modifier = modifier) { page ->
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
                // 手动横屏：会话锁竖屏，横屏视频要满屏只能用户显式要一次
                landscapeForced = landscapeForced,
                onToggleLandscape = onToggleLandscape.takeIf { !isPortraitVideo }
            )
        } else {
            SwipeCoverPage(
                entry = entry,
                isLoading = page == pagerState.currentPage && detailLoading
            )
        }
    }
}

private const val TAG = "PortraitSwipe"

/** 封面页加载动画的边长（占屏宽比例）。比播放器页的加载层小一档：它压在封面之上。 */
private const val COVER_LOADER_WIDTH_FRACTION = 0.26f

/** 非当前页：封面铺满 + 底部标题/UP 渐变信息条 + 当前页取流中的加载指示。 */
@Composable
private fun SwipeCoverPage(
    entry: SwipeEntry,
    isLoading: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
                .padding(
                    horizontal = BiliSpacing.lg,
                    vertical = BiliSpacing.xl
                )
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            entry.displayAuthor?.let { author ->
                Text(
                    text = "@$author",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = BiliSpacing.xs)
                )
            }
        }
    }
}
