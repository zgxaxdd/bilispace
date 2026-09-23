package com.naaammme.bbspace.feature.home.video

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.designsystem.color.LocalHomeCardDynamicTintEnabled
import com.naaammme.bbspace.core.designsystem.color.VideoCardCoverColorStore
import com.naaammme.bbspace.core.designsystem.color.resolveVideoCardAdaptiveContentColors
import com.naaammme.bbspace.core.designsystem.color.resolveVideoCardAmbientDrawSpec
import com.naaammme.bbspace.core.designsystem.component.AdaptiveMediaGrid
import com.naaammme.bbspace.core.designsystem.component.BiliCardSurface
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.component.biliImageUrlForVariant
import com.naaammme.bbspace.core.designsystem.component.feedCoverVariant
import com.naaammme.bbspace.core.designsystem.component.ProvideSkeletonShimmer
import com.naaammme.bbspace.core.designsystem.component.SkeletonBlock
import com.naaammme.bbspace.core.designsystem.component.rememberAdaptiveGridColumnCount
import com.naaammme.bbspace.core.designsystem.seamless.CoverExpandOrigin
import com.naaammme.bbspace.core.designsystem.seamless.LocalBottomNavOcclusionRatio
import com.naaammme.bbspace.core.designsystem.seamless.LocalSeamlessCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessAnchor
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessTransitionState
import com.naaammme.bbspace.core.designsystem.seamless.coverExpandTrack
import com.naaammme.bbspace.core.designsystem.seamless.listCoverRatio
import com.naaammme.bbspace.core.designsystem.seamless.recordCardLayer
import com.naaammme.bbspace.core.designsystem.seamless.sharedContentKey
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessAnchorState
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessCardSpec
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassConfig
import com.naaammme.bbspace.core.designsystem.glass.liquid.lens
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.LiveRoute
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.ThreePointItem
import com.naaammme.bbspace.core.model.ThreePointReason
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.feature.home.LOAD_MORE_THRESHOLD
import com.naaammme.bbspace.feature.home.rememberHomeFlingBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeVideoPage(
    items: List<FeedItem>,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    errorMessage: String?,
    toastMessage: String,
    dislikedReasons: Map<String, String>,
    singleColumn: Boolean = false,
    topContentPadding: Dp = 0.dp,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    /** 错误卡就地重试；不传则错误卡不显示重试按钮。 */
    onRetry: (() -> Unit)? = null,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenLive: (LiveRoute) -> Unit,
    onOpenDynamic: (String) -> Unit,
    onDislike: (FeedItem, ThreePointReason) -> Unit,
    onCancelDislike: (FeedItem) -> Unit,
    onToastShown: () -> Unit,
    // 程序化滚动也会派发嵌套滚动事件，跳回顶部后需通知外层复位顶栏偏移
    onScrollToTop: () -> Unit = {}
) {
    val context = LocalContext.current
    val gridState = rememberLazyStaggeredGridState()
    // 预加载封面 URL 列表。**必须与卡片请求的是同一个 URL**：Coil 的 memory / disk
    // cache key 都由 ImageRequest.data（这里即解析后的 URL 字符串）派生，
    // 差一个 `@640w_480h_1e_1c_85q.webp` 后缀就是另一个键。
    //
    // 上一轮这里传的是**未解析的 `item.cover`（原图地址）**，于是：
    // - 预加载解出来的位图，卡片永远取不到 → 预加载 100% 白做；
    // - 而且它下载的是原图，比不预加载更贵（多一份带宽 + 多一份内存缓存）。
    // 现在与卡片共用 biliImageUrlForVariant / feedCoverVariant，结构上不可能再分叉。
    //
    // 单独派生而不是直接用 items：预加载只需要 URL，而 items 里任何字段变化
    // （播放量、点赞数刷新）都不该重启预加载 effect。key 用 List 的 equals，
    // 只有真正换了内容才会变。
    val preloadVariant = feedCoverVariant(singleColumn)
    // 解码尺寸必须**不小于**卡片的布局尺寸。Coil 的 memory cache key 不含尺寸
    // （只有带 transformations 的请求才把 size 写进 key 的 extras），所以命中与否
    // 取决于 `MemoryCacheService.isCacheValueValidForSize`：它拿已缓存位图的
    // 实际宽高与本次请求的尺寸比，缓存的图更小就判为不可用、重新解码。
    // 因此单列大图（Large = 960w_540h）不能沿用双列的 640×480。
    val preloadSizePx = if (singleColumn) {
        COVER_PRELOAD_LARGE_WIDTH_PX to COVER_PRELOAD_LARGE_HEIGHT_PX
    } else {
        COVER_PRELOAD_WIDTH_PX to COVER_PRELOAD_HEIGHT_PX
    }
    val preloadCoverUrls = remember(items, preloadVariant) {
        items.map { biliImageUrlForVariant(it.cover, preloadVariant) }
    }
    val homeFling = rememberHomeFlingBehavior()
    var wasRefreshing by remember { mutableStateOf(false) }
    val currentItems by rememberUpdatedState(items)
    LaunchedEffect(toastMessage, context) {
        if (toastMessage.isNotEmpty()) {
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
            onToastShown()
        }
    }
    // 只以 isRefreshing 为 key：以 items 为 key 会让每次 loadMore 追加都重启本 effect
    LaunchedEffect(isRefreshing) {
        if (wasRefreshing && !isRefreshing && currentItems.isNotEmpty()) {
            gridState.scrollToItem(0)
            // scrollToItem(0) 会派发一次巨大的负向嵌套滚动位移，
            // 若不复位，外层顶栏偏移会被顶到上限 → 顶栏一打开就是隐藏的。
            onScrollToTop()
        }
        wasRefreshing = isRefreshing
    }
    // 官方推荐：返回无缝动画进行中用户开始滑动列表时立即结束动画（让 leash 跟随
    // 列表滚动而非停留在旧位置）。只在返回阶段放行——打开动画期间手指带出的轻微
    // 滚动若触发中断，会把刚启动的打开动画杀掉（系统退回默认窗口动画）。
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && SeamlessTransitionState.returnRunning) {
                OplusViewSeamlessCompat.finishCurrentAnimation()
            }
        }
    }
    // ── 封面预加载（移植自 BiliPai 上游的 snapshotFlow 方案，本轮修了三个致命细节）──
    // 为什么需要：滚动时新进入视口的封面要现场走「网络/磁盘 → 解码 → 上传纹理」，
    // 解码发生在滚动帧里就是可见的抖动。提前把**即将进入视口**的封面解码好，
    // 进视口时直接命中内存缓存。
    //
    // 本轮修正的三点（前两点是"预加载其实没在工作"的根因）：
    // 1. **URL 必须与卡片一致**（见 preloadCoverUrls 的注释）。原先传的是未解析的
    //    item.cover，与卡片请求的 @640w_480h… 不是同一个键 → 预加载 100% 白做。
    // 2. **debounce(180) 在连续滚动下等于"永远不发射"**：snapshotFlow 每帧都在产出
    //    新值，debounce 每帧都被重置，只有**停下来**才会入队。也就是说整段快速
    //    fling 期间一个都没预取 —— 而这恰恰是唯一需要预取的时刻（冷缓存的首几轮
    //    滚动 jank 就来自这里）。改成按滚动态取超时：滚动中 0ms（立即入队跟上节奏），
    //    静止时仍用 180ms 合并抖动。
    // 3. 相应地取消"滚动中直接 return"的门控 —— 它和第 2 点合起来让预加载只在
    //    列表完全静止时才有用。解码始终在 IO 线程，不占主线程。
    LaunchedEffect(gridState, preloadCoverUrls, preloadSizePx) {
        if (preloadCoverUrls.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            lastVisible to gridState.isScrollInProgress
        }
            .distinctUntilChanged()
            // 见修正点 2：滚动中不 debounce，否则整段 fling 一次都不入队
            .debounce { (_, scrolling) ->
                if (scrolling) 0L else COVER_PRELOAD_DEBOUNCE_MS
            }
            .collect { (lastVisible, _) ->
                if (lastVisible < 0) return@collect
                val from = lastVisible + 1
                val to = (lastVisible + COVER_PRELOAD_AHEAD).coerceAtMost(preloadCoverUrls.lastIndex)
                if (from > to) return@collect
                val urls = (from..to).mapNotNull { preloadCoverUrls.getOrNull(it) }
                if (urls.isEmpty()) return@collect
                // 解码/网络都在 Coil 自己的调度器上，主线程只负责入队
                withContext(Dispatchers.IO) {
                    val loader = SingletonImageLoader.get(context)
                    urls.forEach { url ->
                        loader.enqueue(
                            ImageRequest.Builder(context)
                                .data(url)
                                // 与卡片实际展示尺寸同档（见 preloadSizePx 的注释：
                                // 预加载的位图比卡片需要的小，就会被判不可用而重新解码）
                                .size(preloadSizePx.first, preloadSizePx.second)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()
                        )
                    }
                }
            }
    }

    // 返回动画末端遮挡渐隐参数：顶栏（含状态栏）遮挡区占屏比 + 底部悬浮导航栏遮挡比。
    // 底部比例由 App 层实测后经 LocalBottomNavOcclusionRatio 下发（见 ListCoverRatio.kt）；
    // 底部被 Tab 栏/FAB 盖住的卡片，返回动画末端会做 alpha 渐隐，不再被"切一刀"。
    val density = LocalDensity.current
    val screenHpx = LocalWindowInfo.current.containerSize.height
    val bottomNavRatio = LocalBottomNavOcclusionRatio.current
    // 遮挡比延迟到**点击那一刻**才算，不在组合期算好 remember 住：
    // 1) 组合期读 `bottomNavRatio` 会让本页在 Dock 合并动画期间跟着按帧重组，
    //    而 listCover 只在 `tryOpen` 那一瞬间用得到 —— 提前算纯属浪费；
    // 2) 时序上也只有点击算才对：底部遮挡比是 Dock 的布局回调**异步实测**的，
    //    常常晚于本页首次组合。原实现靠 remember 的 key 变化来跟进，一旦哪天
    //    key 漏了一个来源，就会把"还没测到"的 0f 永久缓存，表现为返回动画末端
    //    卡片被 Dock 生生切一刀（遮挡渐隐失效）。点击时读则永远拿当下最新值。
    val listCover: () -> Pair<Float, Float>? = {
        listCoverRatio(
            topOcclusionPx = with(density) { topContentPadding.roundToPx() },
            screenHeightPx = screenHpx,
            bottomOcclusionRatio = bottomNavRatio()
        )?.let { it.top to it.bottom }
    }

    AdaptiveMediaGrid(
        items = items,
        isRefreshing = isRefreshing,
        isLoadingMore = isLoadingMore,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        columns = if (singleColumn) 1 else rememberAdaptiveGridColumnCount(),
        flingBehavior = homeFling,
        // 尾部提前预取：距末尾 6 条就取下一页，滑到底不再等转圈
        loadMoreThreshold = LOAD_MORE_THRESHOLD,
        // 首屏不足一屏时自动补一页（额度上限在 HomeViewModel）
        autoFillWhenNotScrollable = true,
        contentPadding = PaddingValues(
            // 与 AdaptiveMediaGrid 的默认边距保持一致（显式传参覆盖了默认值，
            // 两处不同步就会出现"首页贴边、排行页留白"这种不一致）
            start = 10.dp,
            top = topContentPadding,
            end = 10.dp,
            bottom = 12.dp
        ),
        errorMessage = errorMessage,
        onRetry = onRetry,
        key = { _, item -> item.actionKey },
        contentType = { _, item -> item.cardType },
        loadingContent = {
            FeedCardSkeleton(singleColumn = singleColumn)
        }
    ) { item ->
        FeedCard(
            item = item,
            singleColumn = singleColumn,
            listCover = listCover,
            onOpenSpace = onOpenSpace,
            dislikedReason = dislikedReasons[item.actionKey],
            onDislike = onDislike,
            onCancelDislike = onCancelDislike,
            onClick = {
                if (item.cardGoto == "dynamic") {
                    onOpenDynamic(item.param)
                } else {
                    item.liveRoute?.let(onOpenLive)
                        ?: item.target?.let(onOpenVideo)
                }
            }
        )
    }
}

@Composable
private fun FeedCard(
    item: FeedItem,
    singleColumn: Boolean = false,
    listCover: () -> Pair<Float, Float>? = { null },
    onOpenSpace: (SpaceRoute) -> Unit,
    dislikedReason: String?,
    onDislike: (FeedItem, ThreePointReason) -> Unit,
    onCancelDislike: (FeedItem) -> Unit,
    onClick: () -> Unit
) {
    val isDisliked = dislikedReason != null
    val isDynamic = item.cardGoto == "dynamic"
    // 角标玻璃：iOS 风格 + LIQUID 档时，封面时长/播放量角标走真模糊
    val badgeGlassOn = LocalUiStyle.current == UiStyle.IOS &&
        LocalGlassConfig.current.material == GlassMaterial.LIQUID
    val canOpen = !isDisliked && (item.target != null || item.liveRoute != null || isDynamic)
    var showMoreMenu by remember(item) { mutableStateOf(false) }
    // ── 封面动态取色（移植自 BiliPai 上游「卡片动态取色」）────────────────────────
    // 缓存键用封面 URL 本身：同一张封面在任意卡片/任意次滚动里都命中同一份取色结果。
    // 用 `remember(coverKey)` 而非 `remember(item)`：列表复用 slot 时 item 变而
    // coverKey 不变的情况（同一视频换推荐位）不该重置颜色。
    val coverTintKey = item.cover.orEmpty()
    val dynamicTintEnabled = LocalHomeCardDynamicTintEnabled.current
    val tintScope = rememberCoroutineScope()
    val cardContext = LocalContext.current
    var coverTint by remember(coverTintKey) {
        mutableStateOf(VideoCardCoverColorStore.getCachedColor(coverTintKey))
    }
    // 异步提取回来时，若卡片已被复用给别的封面，必须丢弃这次结果（上游同款防串色）
    val activeCoverTintKey by rememberUpdatedState(coverTintKey)
    // ── 主动补一次取色（修掉"有些卡片完全没染色"）────────────────────────────
    //
    // 为什么必须有这一步（真机实测定位）：
    //
    // 取色只挂在 Coil 的 `onSuccess` 上，而 **`onSuccess` 只在真正解码时触发一次**。
    // 首页的封面预加载会把位图填进内存缓存，于是卡片走的是**缓存命中**路径、
    // 解码不重走 → `onSuccess` 不再触发 → **这张卡永远拿不到颜色**。
    //
    // 而 `remember(coverTintKey)` 只在首次组合求值一次，之后也不会重算。
    // 两者叠加的结果：屏上一部分卡片完全没有染色（实测 6 张里 3 张饱和度 0%）。
    //
    // 这里让卡片**自己主动取一次**：从 Coil 缓存取位图（不重新下载）→ 提取 → 落缓存。
    // 不依赖任何回调时机，冷启动/滚动回来/缓存命中都成立。
    LaunchedEffect(coverTintKey, dynamicTintEnabled) {
        if (!dynamicTintEnabled || coverTintKey.isBlank()) return@LaunchedEffect
        if (coverTint != null) return@LaunchedEffect
        VideoCardCoverColorStore.requestExtraction(
            appContext = cardContext,
            cacheKey = coverTintKey,
            url = biliImageUrlForVariant(item.cover, feedCoverVariant(singleColumn)).orEmpty(),
            sizePx = if (singleColumn) {
                COVER_PRELOAD_LARGE_WIDTH_PX to COVER_PRELOAD_LARGE_HEIGHT_PX
            } else {
                COVER_PRELOAD_WIDTH_PX to COVER_PRELOAD_HEIGHT_PX
            },
            scope = tintScope
        ) { color ->
            // 卡片可能已被复用给别的封面：key 不匹配就丢弃，防止串色
            if (activeCoverTintKey == coverTintKey) coverTint = color
        }
    }
    // 取色结果渐变入场：直接跳变会让整张卡片"啪"地换色，350ms 过渡读起来是"光渗进来"
    val animatedCoverTint by animateColorAsState(
        targetValue = coverTint ?: Color.Transparent,
        animationSpec = tween(durationMillis = 350),
        label = "videoCardCoverTint"
    )
    val tintSource = if (animatedCoverTint.alpha > 0f) animatedCoverTint else null
    // 卡片底色：**必须不透明**，单双列都用 surfaceContainerLow。
    //
    // ## 为什么双列不能再是 Transparent（真机实测定位，这是"颜色对应不上"的根因）
    //
    // 双列原先照 PiliPlus-Max 做成「无卡片底色，封面与文字直接落在页面背景上」，
    // 即 `Color.Transparent`。当时没有动态染色，这样最干净。
    //
    // 但它是染色的**致命前提**：`resolveVideoCardAmbientDrawSpec` 里染色是
    // `lerp(defaultContainerColor, 封面色, 0.85).copy(alpha = 0.70)` ——
    // 底色若是 Transparent，`lerp` 出来的是**半透明色**（alpha 只有 0.85），
    // 再乘 0.70 后最终 alpha 只有 **0.595**，叠到屏幕上时被页面背景稀释掉 40%，
    // 颜色就"对应不上"预览图了。
    //
    // 实测：某封面主色 `#4b628b`（蓝，饱和度 46%），染出的简介区只有
    // `#ced3da`（饱和度 **6%**）—— 色相对但完全看不出是蓝的。
    //
    // 改成不透明底色后，`lerp(白, 封面色, 0.85)` 得到的是**实色**，
    // 乘 alpha 0.70 叠上去仍保留足够饱和度（同例 → 约 32%）。
    //
    // 单列本来就是 `surfaceContainerLow`，这里统一取同一个值，
    // 让单双列切换时卡片底色一致。
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val defaultOnSurface = MaterialTheme.colorScheme.onSurface
    val defaultOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    // 深浅判据与全站一致：以主题背景亮度为准（跟随主题模式，不直接问系统）
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 浅色主题 + 深色封面时正文翻白，否则被染深的卡片上深色字会失去对比度
    val adaptiveContentColors = remember(
        tintSource, darkTheme, defaultOnSurface, defaultOnSurfaceVariant, dynamicTintEnabled
    ) {
        resolveVideoCardAdaptiveContentColors(
            coverTint = tintSource,
            isDarkTheme = darkTheme,
            defaultOnSurface = defaultOnSurface,
            defaultOnSurfaceVariant = defaultOnSurfaceVariant,
            dynamicTintEnabled = dynamicTintEnabled
        )
    }
    // 卡片圆角：**单一来源**。容器、封面、无缝动画锚点三者共用同一个 shape，
    // 避免出现"容器 24dp / 封面 12dp / 单列封面直角"这种卡片自身就不一致的观感。
    // 单列本可用更大的圆角，但普通 HomeVideoPage 只有双列；排行榜/搜索等页在单列下
    // 也用卡片圆角，保持全局一致。
    //
    // 三个形状都是**文件级常量**（见文件末尾）：它们只由 BiliRadius.card 决定，
    // 原先写成局部 `val` / `remember` 时，每张卡片每次重组都要新建 RoundedCornerShape
    // （`CornerBasedShape` 持有 4 个 Dp），或至少占一个 remember 槽位并做一次
    // 键比较——而形状值在全进程内从不改变。
    //
    // 卡片是「封面 + 信息区」两块拼成的。两块**分别**只圆上/下两角，
    // 接缝处都保持直角 → 拼合无缝，读作一张卡片；若两块都用全圆角，
    // 接缝会各圆各的、中间露出一道背景，看起来像"图 + 贴在下面的小面板"。
    // 整卡最终由 BiliCardSurface 的 FeedCardShape 收圆。
    // 无缝动画参数：圆角取卡片自身 shape；兜底色即卡片所在区域的底色
    val seamlessSpec = rememberSeamlessCardSpec(
        shape = FeedCardShape,
        containerColor = if (singleColumn) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface
        }
    )
    val anchorState = rememberSeamlessAnchorState()
    val seamlessOpener = LocalSeamlessCardOpener.current
    // 版本门禁前置到组合期：不支持的设备不为每张卡片实例化锚点 View（省一个真实
    // FrameLayout 节点/卡）；isVersionSupported 进程内缓存，读取零成本
    val canSeamless = seamlessOpener != null &&
        OplusViewSeamlessCompat.isVersionSupported() &&
        item.target != null &&
        item.liveRoute == null

    // ── 卡片动态染色（移植自 BiliPai 上游）────────────────────────────────
    // **染色铺满整卡**（封面 + 信息区），不是只染信息区。
    // 只染信息区时，封面是照片、下面是色块，中间会有一道明确的分界，
    // 读起来像两块东西；铺满整卡后封面色与信息区同源，卡片才是一个整体。
    //
    // 分层：BiliCardSurface 的 containerColor 出整卡染色底 → 信息区再叠一道
    // 封面同调的竖向渐变，让封面下沿的颜色"渗"进文字区（上游 coverGlow 同款），
    // 最后 0.5dp 描边勾出玻璃棱镜边。
    // 无封面色时（未加载完 / 提取失败 / 开关关闭）全部退化：底色原样、无渐变、
    // 中性白描边，视觉与移植前一致。
    val infoDrawSpec = if (dynamicTintEnabled) {
        resolveVideoCardAmbientDrawSpec(
            coverTint = tintSource,
            isDarkTheme = darkTheme,
            defaultContainerColor = containerColor,
            defaultBorderColor = if (darkTheme) Color.White.copy(alpha = 0.25f)
            else Color.White.copy(alpha = 0.40f)
        )
    } else {        null
    }
    val showCoverGlow = infoDrawSpec != null &&
        infoDrawSpec.coverGlowAlpha > 0f &&
        tintSource != null
    // 信息区自身**不再铺底色**（底色已由整卡容器承担），只叠渐变与描边：
    // 再铺一层会让接缝处出现"双层色"的色阶断层。
    //
    // 标题样式：`TextStyle` 是重对象（约 20 个字段，含 fontSize/lineHeight/fontFamily/
    // 各种 SpanStyle 等）。原先直接写在 Text 的 `style =` 上是 `typography.bodyLarge.copy(...)`，
    // 于是**每张卡片每次重组**都要新建一份 TextStyle —— 而 copy 的结果只由主题排版决定，
    // 全站只有一份。以 `bodyLarge` 为 key remember：主题不变时零分配，换主题时正确重建。
    val baseTitleStyle = MaterialTheme.typography.bodyLarge
    val titleStyle = remember(baseTitleStyle) {
        baseTitleStyle.copy(
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
    // `drawWithCache` 而不是 `drawBehind`：brush 的 3 个 Color、stops 数组与 Brush 对象
    // 本身都要在缓存块外重建的话，等于**每次重绘**分配一份。滚动列表里重绘频率远高于
    // 取色变化频率，所以把 brush 挪进 cache 块。在缓存块内读取状态是 Compose 认可的
    // 用法（值变化会让缓存块重跑），`size.height` 也在块内读，因此 endY 仍跟随实际高度。
    val glowSpec = if (showCoverGlow) infoDrawSpec else null
    val glowTint = if (showCoverGlow) tintSource else null
    val infoModifier = Modifier
        .then(
            if (glowSpec != null && glowTint != null) {
                Modifier.drawWithCache {
                    val brush = Brush.verticalGradient(
                        colors = listOf(
                            glowTint.copy(alpha = glowSpec.coverGlowAlpha),
                            glowTint.copy(alpha = glowSpec.coverGlowAlpha * 0.35f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = size.height * 0.85f
                    )
                    onDrawBehind { drawRect(brush) }
                }
            } else {
                Modifier
            }
        )
        .then(
            if (infoDrawSpec != null) {
                Modifier.border(width = 0.5.dp, color = infoDrawSpec.borderColor, shape = FeedCardInfoShape)
            } else {
                Modifier
            }
        )
        .padding(horizontal = 8.dp, vertical = 6.dp)

    // ── 无缝过渡期间整卡内容的可见性 ────────────────────────────────────────
    // 锚点已铺满整卡（见 fullCardOverlay），且**不再用封面位图装饰**，因此框架
    // 在打开与返回两个方向都要捕获锚点所在区域的**实时像素**。
    //
    // 由此推出一条硬约束：**卡片内容在整个过渡期间必须保持不透明**。
    // 一旦把卡片淡到 alpha=0，框架捕获到的就是一张全透明的卡片——
    // 实测表现正是"返回过程中预览图和简介部分是黑色的"。
    //
    // 所以这里**不做任何淡出**。过渡的视觉由系统 morph 承担（它就是整卡 → 播放器
    // 的形变），原卡片会在 Activity 切换时被盖住，无需应用自己处理。
    //
    // 历史教训（勿重蹈）：
    // 1. 早期用"锚点只覆盖封面 + 卡片淡出"来模拟"整卡一起走"，结果是简介区不参与
    //    过渡、且返回时卡片已透明 → 黑色；
    // 2. 用封面位图装饰整卡大小的锚点，会被拉伸铺满（预览图盖住简介区）。
    // 正确做法就是现在这样：锚点铺满整卡 + 不装饰 + 不淡出。
    val seamlessSourceKey = item.target?.sharedContentKey()
    val sourceCardFadeModifier = Modifier
    // 封面区域高度（px）。合成兜底预览时用。
    var coverHeightPx by remember(item) { mutableIntStateOf(0) }

    BiliCardSurface(
        onClick = {
            val target = item.target
            // 无缝路径：**先发起一次 PixelCopy** 截取整卡真实像素（含简介文字），
            // 在其回调里完成 tryOpen + startActivity。
            //
            // 为什么是"截屏"而不是"每帧录制 GraphicsLayer"：
            // 后者会让每张可见卡片每帧多一次离屏渲染，实测 jank 1.27% → 4.06%。
            // PixelCopy 只在点击这一次截一块卡片大小的区域，与滚动帧率无关。
            //
            // 时序：官方要求 `setSeamlessView` 在 `startActivity(intent, bundle)` 之前。
            // 截屏回调里两者仍是先后紧邻执行的，契约成立；截取耗时毫秒级。
            val anchorView = anchorState.anchor
            if (target != null && canSeamless && anchorView != null) {
                val opener = seamlessOpener ?: return@BiliCardSurface
                opener.captureCardBitmap(anchorView) { captured ->
                    val opened = opener.tryOpen(
                        anchor = anchorView,
                        target = target,
                        coverUrl = item.cover,
                        coverLarge = singleColumn,
                        anchorRadiusPx = seamlessSpec.radiusPx,
                        listCover = listCover(),
                        sourceCardKey = seamlessSourceKey,
                        coverHeightPx = coverHeightPx,
                        cardBitmap = captured,
                        // 官方 BUNDLE_COLOR：view 之外区域的填充色。不传则由框架取色，
                        // 取错时表现为打开/返回瞬间闪一下白（实测踩过）。
                        fallbackColor = seamlessSpec.fallbackColor,
                        keepAnchorVisible = true
                    )
                    if (!opened) {
                        // 注册失败：释放截图并走普通跳转
                        captured?.recycle()
                        if (item.liveRoute == null) {
                            CoverExpandOrigin.record(
                                target = item.target,
                                coverUrl = item.cover,
                                coverLarge = singleColumn
                            )
                        }
                        onClick()
                    }
                }
                return@BiliCardSurface
            }
            // 降级路径：快照来源封面，供详情覆盖层做封面展开 morph；直播卡不参与
            if (item.liveRoute == null) {
                CoverExpandOrigin.record(
                    target = item.target,
                    coverUrl = item.cover,
                    coverLarge = singleColumn
                )
            }
            onClick()
        },
        onLongClick = {
            if (!isDisliked && !item.threePointV2.isNullOrEmpty()) {
                showMoreMenu = true
            }
        },
        enabled = canOpen,
        shape = FeedCardShape,
        // 整卡染色底：有封面色时是与封面同调的半透明色，无封面色时原样返回
        // containerColor（双列即 Transparent），行为不变。
        // 铺在**整卡**上而不是信息区，是"卡片与简介成为一个整体"的关键。
        containerColor = infoDrawSpec?.containerColor ?: containerColor,
        modifier = Modifier.fillMaxWidth(),
        // 无缝动画锚点铺满**整张卡片**（封面 + 简介区）。
        //
        // 依据：OPPO 无缝动画里**锚点矩形就是过渡的几何范围**。官方 B 站客户端
        // 的卡片过渡是"封面 + 简介一起动"，要复刻这一点，锚点就必须覆盖整卡；
        // 只覆盖封面时简介区不进入过渡（此前用户反馈的"简介没被带上"）。
        //
        // 放在 fullCardOverlay 而不是内容里：内容装在 Column（要用 weight），
        // 而 matchParentSize 是 BoxScope 限定修饰符，放 Column 里会被当成真实子项。
        fullCardOverlay = if (canSeamless) {
            { SeamlessAnchor(anchorState, Modifier.matchParentSize()) }
        } else {
            null
        }
    ) {
        // 无缝动画锚点：与封面同边界的零绘制透明 View，起飞矩形即封面自身。
        // 必须放在封面内容**之前**（z 序在下）：锚点的过渡位图画在自身
        // background+foreground 上，若盖在内容上方会遮住封面上的时长/播放量角标；
        // 框架取的是锚点自身的绘制内容，与它在兄弟节点中的层级无关。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (singleColumn) 16f / 9f else 4f / 3f)
                // 上报封面高度，供无缝过渡合成"整卡预览图"用（见 coverHeightPx）
                .onSizeChanged { coverHeightPx = it.height }
                // 封面节点上报窗口坐标：幽灵从封面本身起飞，与所见像素连续
                .coverExpandTrack(item.target.takeIf { item.liveRoute == null })
                // 无缝过渡期间与简介区一起淡出（详见 sourceCardAlpha 注释）
                .then(sourceCardFadeModifier)
        ) {
            // 锚点已移到 BiliCardSurface 的 fullCardOverlay（铺满整卡）。
            //
            // **关键：不要再用封面位图装饰锚点**（decorateAnchor = false）。
            // 锚点现在是整卡大小（比例近 1:1），而封面是 4:3；若仍把封面位图设为
            // 锚点的 background/foreground，`RoundedBitmapDrawable` 会把它**拉伸铺满
            // 整卡**——表现为"点击后预览图先盖住简介区，再开始动画"（返回方向同理）。
            // 不装饰时框架会去捕获锚点所在区域的**真实像素**，即整卡（封面+简介），
            // 这正是官方 B 站的效果。
            // 角标玻璃（LIQUID 档）：封面内容录进专用背板供角标采样。封面是静态
            // 内容（只在图片加载时重绘），录制一次即可，无逐帧开销。角标作为
            // 兄弟节点画在录制子树之外，避免采样到自身（反馈回路）。
            //
            // **必须条件化**：`rememberLayerBackdrop()` 内部默认参数会调
            // `rememberGraphicsLayer()`，即持有一个 RenderNode 级离屏层。无条件创建
            // 时，默认配置（GlassConfig 默认 FROSTED、badgeGlassOn=false）下每张可见
            // 卡片都会白创建一个离屏层，且随滚动不断创建/释放。
            // 条件调用 composable 是合法的（分支稳定，不会破坏槽位顺序）。
            val coverBackdrop = if (badgeGlassOn) rememberLayerBackdrop() else null
            CoverImage(
                url = item.cover,
                contentDescription = item.title,
                // 封面只圆上两角，下沿直角与信息区无缝拼接（详见 FeedCardCoverShape 注释）
                shape = FeedCardCoverShape,
                coverLarge = singleColumn,
                coverFourThree = !singleColumn,
                // 取色挂在 Coil 解码成功回调上：**复用已解码位图**，不额外发请求、
                // 不额外解码。已有颜色时跳过（滚动回来命中缓存，无需重复提取）。
                onImageLoaded = if (dynamicTintEnabled && coverTint == null) {
                    { bitmap ->
                        VideoCardCoverColorStore.extractColorAsync(
                            cacheKey = coverTintKey,
                            bitmap = bitmap,
                            scope = tintScope
                        ) { extracted ->
                            // 卡片可能已被复用给别的封面：key 不匹配就丢弃，防止串色
                            if (activeCoverTintKey == coverTintKey) {
                                coverTint = extracted
                                // 诊断：仅 debug 构建输出。取色是"看不见的"功能，
                                // 出问题时（卡片没上色/颜色不对）这条是唯一的直接证据。
                                // 本模块未开启 buildConfig 生成，故用 Logger.isDebug 门控。
                                if (Logger.isDebug) {
                                    android.util.Log.i(
                                        "BbDynamicTint",
                                        "extracted coverTint=#${Integer.toHexString(extracted.toArgb())} " +
                                            "key=${coverTintKey.takeLast(28)} dark=$darkTheme"
                                    )
                                }
                            }
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .matchParentSize()
                    .then(coverBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
            )
            // 封面左下：时长/竖屏等标记；右下：播放量（保留官方角标风格）
            if (dislikedReason != null) {
                DislikedOverlay(
                    reason = dislikedReason,
                    onUndo = { onCancelDislike(item) },
                    modifier = Modifier.matchParentSize()
                )
            } else {
                item.coverLeftText1?.let { text ->
                    CoverBadge(
                        text = text,
                        backdrop = coverBackdrop,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(BiliSpacing.sm)
                    )
                }
                item.coverRightText?.let { text ->
                    CoverBadge(
                        text = text,
                        backdrop = coverBackdrop,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(BiliSpacing.sm)
                    )
                }
            }
        }
        // 染色底/微光/描边三条由上方 infoModifier 统一承载（定义在 BiliCardSurface 之前，
        // 因为容器底色也要用同一份 drawSpec）。无缝过渡期间随封面一起淡出。
        Column(modifier = infoModifier.then(sourceCardFadeModifier)) {
            val spaceRoute = remember(item.args, item.target) {
                item.args?.let { args ->
                    if (args.upId <= 0L && args.upName.isNullOrBlank()) {
                        null
                    } else {
                        SpaceRoute(
                            mid = args.upId,
                            name = args.upName,
                            fromViewAid = args.aid.takeIf { it > 0L }
                                ?: (item.target as? VideoTarget.Ugc)?.aid?.takeIf { it > 0L }
                        )
                    }
                }
            }
            Text(
                text = item.title,
                // 双列压缩标题为 1 行，给图片更多空间；单列大图保留 2 行
                maxLines = if (singleColumn) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                // 浅色主题 + 深色封面时翻白（resolveVideoCardAdaptiveContentColors），
                // 否则被染深的卡片上深色标题会糊成一片
                color = adaptiveContentColors.titleColor,
                style = titleStyle
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                val upName = item.descButton?.text ?: item.args?.upName ?: ""
                if (upName.isNotEmpty()) {
                    // 首页 feed 未下发 UP 头像，直接以名字呈现，更简洁
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = upName,
                            style = MaterialTheme.typography.bodySmall,
                            color = adaptiveContentColors.subtitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (spaceRoute == null || isDisliked) {
                                Modifier
                            } else {
                                Modifier.clickable { onOpenSpace(spaceRoute) }
                            }
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // 竖屏等标签：放在 UP 名行最右侧，节省卡片纵向空间
                item.rcmdReason?.takeIf { it.text.isNotEmpty() }?.let { reason ->
                    Text(
                        text = reason.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    if (showMoreMenu && !item.threePointV2.isNullOrEmpty()) {
        MoreMenuDialog(
            item = item,
            items = item.threePointV2.orEmpty(),
            onDislike = onDislike,
            onDismiss = { showMoreMenu = false }
        )
    }
}

@Composable
private fun CoverBadge(
    text: String,
    backdrop: LayerBackdrop? = null,
    modifier: Modifier = Modifier
) {
    val shape = CoverBadgeShape
    val glass = backdrop != null
    Surface(
        modifier = modifier.then(
            if (backdrop != null) Modifier.glassBadgeSurface(backdrop, shape) else Modifier
        ),
        shape = shape,
        color = if (glass) Color.Transparent else Color.Black.copy(alpha = 0.62f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * 角标玻璃：采样所在卡片封面的背板，blur + 轻折射 + 深色染色（白字可读）。
 * 角标小、封面内容静态，采样成本低。
 */
private fun Modifier.glassBadgeSurface(backdrop: LayerBackdrop, shape: RoundedCornerShape): Modifier =
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(4.dp.toPx(), 4.dp.toPx())
            lens(
                refractionHeight = 4.dp.toPx(),
                refractionAmount = 3.dp.toPx(),
                chromaticAberration = 0f
            )
        },
        onDrawSurface = {
            drawRect(Color.Black.copy(alpha = 0.45f))
        }
    )

@Composable
private fun FeedCardSkeleton(singleColumn: Boolean = false) {
    // 首页信息流的骨架此前用的是裸 SkeletonBlock（没有走任何带微光的骨架组件），
    // 所以首页加载时看不到微光——这里补上 provider，与其他骨架观感一致。
    // ProvideSkeletonShimmer 幂等：若外层已提供会直接复用，不会叠加动画。
    ProvideSkeletonShimmer {
    Column(modifier = Modifier.fillMaxWidth()) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (singleColumn) 16f / 9f else 4f / 3f),
            shape = FeedCardShape
        )
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .height(18.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(16.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(14.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
        }
    }
    }
}

@Composable
private fun MoreMenuDialog(
    item: FeedItem,
    items: List<ThreePointItem>,
    onDislike: (FeedItem, ThreePointReason) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, menuItem ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(menuItem.title, style = MaterialTheme.typography.bodyLarge)
                    }
                    val options = menuItem.reasons.orEmpty() + menuItem.feedbacks.orEmpty()
                    if (options.isNotEmpty()) {
                        options.chunked(2).forEach { pair ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { reason ->
                                    TextButton(
                                        onClick = {
                                            onDismiss()
                                            onDislike(item, reason)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            reason.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun DislikedOverlay(
    reason: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = onUndo) {
                Text("撤回")
            }
        }
    }
}

// ── 卡片形状常量（原为每张卡片每次重组新建的局部值）────────────────────────────
// 为什么放文件级：`RoundedCornerShape(Dp)` 返回 `CornerBasedShape`，它持有 4 个 Dp
// （topStart/topEnd/bottomEnd/bottomStart）。写在 composable 里就是"每张可见卡片、
// 每次重组"各分配一份，而这些值在整个进程生命周期内从不改变。
// 用文件级 val 还顺带消掉了原先 `remember(cardShape) { BiliRadius.topRounded(...) }`
// 的两处 remember 槽位与键比较。

/** 整卡形状（容器 + 无缝动画锚点共用，两端同值才不跳）。 */
private val FeedCardShape: RoundedCornerShape = RoundedCornerShape(BiliRadius.card)

/** 封面块：只圆上两角，下沿直角，与信息区无缝拼接。 */
private val FeedCardCoverShape: Shape = BiliRadius.topRounded(BiliRadius.card)

/** 信息区块：只圆下两角，上沿直角。与 [FeedCardCoverShape] 配对。 */
private val FeedCardInfoShape: Shape = BiliRadius.bottomRounded(BiliRadius.card)

/** 封面角标（时长 / 播放量）形状。 */
private val CoverBadgeShape = RoundedCornerShape(8.dp)

// ── 封面预加载参数（见 HomeVideoPage 内 preload effect 的注释）────────────────
/** 合并"手指停下/惯性将尽"后的抖动；滚动中不走这个值（见 preload effect 修正点 2）。 */
private const val COVER_PRELOAD_DEBOUNCE_MS = 180L
/**
 * 视口之后预取几张。4 → 8（本轮）：双列一屏约 4 张，4 张只够半屏缓冲，
 * 快速 fling 一口气就吃掉；而预取窗口现在在滚动中也会推进（不再被 debounce 憋住），
 * 所以必须大于"一次惯性滑过的屏数"才有意义。
 * 仍不贪多：预取太多等于替用户提前解码他可能看不到的内容，白占内存与流量。
 */
private const val COVER_PRELOAD_AHEAD = 8
/**
 * 预加载解码尺寸，必须与卡片实际展示尺寸同档**且不小于它**：
 * Coil 的 memory cache key 不含尺寸，命中后还要过 `isCacheValueValidForSize`，
 * 缓存位图比请求尺寸小就会被判不可用、重新解码。
 * 双列走 BiliCoverSize.Small43（@640w_480h），单列走 Large（@960w_540h）。
 */
private const val COVER_PRELOAD_WIDTH_PX = 640
private const val COVER_PRELOAD_HEIGHT_PX = 480
private const val COVER_PRELOAD_LARGE_WIDTH_PX = 960
private const val COVER_PRELOAD_LARGE_HEIGHT_PX = 540
