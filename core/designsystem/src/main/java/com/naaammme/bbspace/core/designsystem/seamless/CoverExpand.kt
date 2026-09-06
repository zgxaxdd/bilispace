package com.naaammme.bbspace.core.designsystem.seamless

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.core.model.VideoTarget
import kotlin.math.roundToInt

/** 幽灵与详情页开始交叉淡出的进度点：此前幽灵不透明，此后线性淡出至消失。 */
private const val GHOST_FADE_START = 0.62f

/**
 * 「首页卡片 → 视频详情覆盖层」封面展开（方案 B v2）。
 *
 * v1 教训：SharedTransitionLayout 会给整棵子树开启 Lookahead 双重测量（首页瀑布流
 * 全局变慢），且把整屏详情 UI 放进过渡 overlay 逐帧重排会严重掉帧。v2 改为系统级
 * 无缝动画同款思路——过渡只携带一张轻量「封面幽灵」，真实详情页在幽灵下方普通淡入，
 * morph 结束后幽灵淡出。全程仅稳定 API（Animatable + layout/offset 的延迟读取，
 * 动画帧零重组），单帧只绘制一张已缓存的封面图。
 *
 * 流程：FeedCard 用 [coverExpandTrack] 持续上报卡片窗口坐标（无重组成本），点击时经
 * [CoverExpandOrigin.record] 快照来源；PlaybackHost 在「来源键与当前视频匹配、
 * 来自卡片入口、非迷你原位展开」时放置 [CoverExpandGhost] 完成卡片边界 → 全屏 morph。
 * 其余入口（深链/搜索/历史等）不产生来源记录，维持原有过渡不变。
 */

/** 稳定匹配键（口径与 core/model 的 isSameEntry 一致）；无法派生时返回 null。 */
fun VideoTarget.sharedContentKey(): String? = when (this) {
    is VideoTarget.Ugc ->
        aid.takeIf { it > 0L }?.let { "video-ugc-$it" }
            ?: bvid?.takeIf { it.isNotBlank() }?.let { "video-ugc-bv-$it" }

    is VideoTarget.Pgc ->
        (epId.takeIf { it > 0L } ?: aid.takeIf { it > 0L } ?: seasonId?.takeIf { it > 0L })
            ?.let { "video-pgc-$it" }

    is VideoTarget.Pugv ->
        (epId.takeIf { it > 0L } ?: aid.takeIf { it > 0L } ?: seasonId?.takeIf { it > 0L })
            ?.let { "video-pugv-$it" }
}

/**
 * 卡片窗口坐标登记表：onGloballyPositioned 在滚动期间高频回调，刻意使用普通 Map
 * （LRU 截断）而非状态容器，避免写入触发重组；仅在点击瞬间读取一次。
 */
private object CoverExpandBoundsStore {
    private const val MAX_ENTRIES = 96
    private val map = object : LinkedHashMap<String, Rect>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Rect>): Boolean =
            size > MAX_ENTRIES
    }

    fun put(key: String, bounds: Rect) {
        synchronized(map) { map[key] = bounds }
    }

    fun get(key: String): Rect? = synchronized(map) { map[key] }
}

/** 最近一次「从卡片发起打开」的来源快照；仅 UI 线程读写。 */
class CoverExpandRecord(
    val key: String,
    val coverUrl: String?,
    /** 与来源卡片一致的封面尺寸变体（保证幽灵命中同一张内存缓存位图，无闪动）。 */
    val coverLarge: Boolean,
    /** 点击瞬间的封面边界（窗口坐标）：幽灵从封面本身起飞，像素级连续。 */
    val cardBounds: Rect,
)

object CoverExpandOrigin {
    /** 最近一次来源快照；只读，写入只能经 [record] / [clear]。 */
    var pending by mutableStateOf<CoverExpandRecord?>(null)
        private set

    /** 卡片点击时调用：快照来源键、封面与封面边界；无稳定键或无坐标时静默跳过。 */
    fun record(target: VideoTarget?, coverUrl: String?, coverLarge: Boolean) {
        val key = target?.sharedContentKey() ?: return
        val bounds = CoverExpandBoundsStore.get(key) ?: return
        pending = CoverExpandRecord(key, coverUrl, coverLarge, bounds)
    }

    /** morph 结束或展开中断后清除，避免残留记录影响后续展开。 */
    fun clear() {
        pending = null
    }
}

/**
 * 卡片侧：持续上报卡片在窗口坐标系中的边界，供点击瞬间快照。
 * 不参与点击/绘制，无目标稳定键时为无操作。
 */
fun Modifier.coverExpandTrack(target: VideoTarget?): Modifier {
    val key = target?.sharedContentKey() ?: return this
    return onGloballyPositioned { CoverExpandBoundsStore.put(key, it.boundsInWindow()) }
}

/**
 * 封面幽灵：从 [CoverExpandRecord.cardBounds] morph 到宿主全屏，结束后淡出并回调
 * [onFinished]。需放置于详情覆盖层之上（绘制顺序靠后）；宿主应为全屏 Box。
 * 动画经 layout/offset/graphicsLayer 的延迟读取实现，帧间零重组。
 */
@Composable
fun CoverExpandGhost(
    record: CoverExpandRecord,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hostBounds by remember { mutableStateOf<Rect?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { hostBounds = it.boundsInWindow() }
    ) {
        val host = hostBounds
        if (host != null) {
            GhostBody(record = record, host = host, onFinished = onFinished)
        }
    }
}

@Composable
private fun GhostBody(
    record: CoverExpandRecord,
    host: Rect,
    onFinished: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val start = remember(record, host) {
        Rect(
            record.cardBounds.left - host.left,
            record.cardBounds.top - host.top,
            record.cardBounds.right - host.left,
            record.cardBounds.bottom - host.top,
        )
    }
    val end = remember(host) { Rect(0f, 0f, host.width, host.height) }
    val density = LocalDensity.current
    val largeCoverCorner = (MaterialTheme.shapes.large as? CornerBasedShape)?.topStart
    val startCornerPx = remember(record, density, largeCoverCorner) {
        if (record.coverLarge) {
            // 单列封面跟随卡片主题圆角
            largeCoverCorner?.toPx(Size.Unspecified, density) ?: 0f
        } else {
            // 双列封面固定 12dp 圆角（与 FeedCard 一致）
            with(density) { 12.dp.toPx() }
        }
    }
    LaunchedEffect(record, host) {
        progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 360f))
        onFinished()
    }
    Box(
        modifier = Modifier
            .expandGhostBounds({ progress.value }, start, end)
            .offset {
                val r = lerp(start, end, progress.value)
                IntOffset(r.left.roundToInt(), r.top.roundToInt())
            }
            .graphicsLayer {
                val p = progress.value
                // 末段与下方已就绪的详情页交叉淡出，避免整屏封面生硬消失
                alpha = when {
                    p < GHOST_FADE_START -> 1f
                    else -> 1f - (p - GHOST_FADE_START) / (1f - GHOST_FADE_START)
                }
                // 圆角随展开收敛到直角，对齐系统 leash 动画的形态过渡
                shape = RoundedCornerShape(CornerSize(startCornerPx * (1f - p)))
                clip = true
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        BiliAsyncImage(
            url = record.coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            variant = if (record.coverLarge) {
                BiliImageVariant.CardCoverLarge
            } else {
                BiliImageVariant.CardCover43
            },
            contentScale = ContentScale.Crop
        )
    }
}

/**
 * 幽灵节点按动画进度插值 [start]→[end] 的矩形测量和摆放自身内容；
 * size 与位置都在布局阶段读取动画值，不触发重组。
 */
private fun Modifier.expandGhostBounds(
    progress: () -> Float,
    start: Rect,
    end: Rect,
): Modifier = layout { measurable, _ ->
    val r = lerp(start, end, progress())
    val w = r.width.roundToInt().coerceAtLeast(1)
    val h = r.height.roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(w, h) { placeable.placeRelative(0, 0) }
}
