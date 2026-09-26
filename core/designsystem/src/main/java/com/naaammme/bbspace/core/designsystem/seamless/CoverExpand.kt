package com.naaammme.bbspace.core.designsystem.seamless

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.naaammme.bbspace.core.designsystem.theme.BiliAnimations
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.model.VideoTarget
import kotlinx.coroutines.launch
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
 * 卡片窗口坐标登记表：onGloballyPositioned 在滚动期间高频回调。读写都只发生在
 * UI 线程（布局回调与点击处理器），因此不加锁；按 4px 粒度取整去重，静止时
 * （列表无滚动、无重排）零写入，滚动帧只有坐标真正跨过粒度才写。仅点击瞬间读取。
 */
private object CoverExpandBoundsStore {
    private const val MAX_ENTRIES = 96
    private const val QUANTUM_PX = 4f
    private val map = object : LinkedHashMap<String, Rect>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Rect>): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * 滚动期间**每张卡片每帧**都会被调用，所以这里的原则是"没跨粒度的帧一个对象都不分配"。
     *
     * 之前的写法是先 `Rect(...)` 再和 map 里的比 —— 于是"分配 4 个 float + 一次构造 +
     * 一次 equals"全部发生在去重**之前**，去重只省了一次 map 写入，白分配。
     * 现在先把 4 个分量各自量化成 Float，与已存值逐个比，只有真的变了才构造 Rect。
     *
     * 调用方 `boundsInWindow()` 仍会分配一个临时 Rect：LayoutCoordinates 的公开 API
     * 没有"写入调用方 Rect"的重载，这一份无法省。净效果是稳态下每卡每帧 2 → 1 次分配。
     */
    fun put(key: String, bounds: Rect) {
        val left = snap(bounds.left)
        val top = snap(bounds.top)
        val right = snap(bounds.right)
        val bottom = snap(bounds.bottom)
        val prev = map[key]
        if (prev != null &&
            prev.left == left && prev.top == top &&
            prev.right == right && prev.bottom == bottom
        ) {
            return
        }
        map[key] = Rect(left, top, right, bottom)
    }

    /** 按 [QUANTUM_PX] 向下取整到网格。单独成函数是为了让"先比标量、后构造 Rect"可读。 */
    private fun snap(value: Float): Float = (value / QUANTUM_PX).toInt() * QUANTUM_PX

    fun get(key: String): Rect? = map[key]
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

    /**
     * 回退 morph 的实时进度（0..1），**只供源卡片淡出**。
     *
     * 非 OPPO 设备没有系统 morph：卡片不会"被系统捕获"，若不淡出就会与飞行的封面幽灵
     * 同屏出现**两份封面**（真机反馈"过渡观感非常奇怪"的直接来源）。
     * 反之 OPPO 系统 morph 路径**绝不能淡出**——系统要捕获卡片像素，淡成透明会截到
     * 空卡片（见 HomeVideoPage 的历史教训）。因此该值只在 [record] 之后才驱动淡出：
     * 系统路径从不 record()，读到的恒是初值 0f → 卡片 alpha 恒为 1（行为不变）。
     */
    var morphProgress by mutableFloatStateOf(0f)
        internal set

    /** 卡片点击时调用：快照来源键、封面与封面边界；无稳定键或无坐标时静默跳过。 */
    fun record(target: VideoTarget?, coverUrl: String?, coverLarge: Boolean) {
        val key = target?.sharedContentKey() ?: return
        val bounds = CoverExpandBoundsStore.get(key) ?: return
        morphProgress = 0f
        pending = CoverExpandRecord(key, coverUrl, coverLarge, bounds)
    }

    /** morph 结束或展开中断后清除，避免残留记录影响后续展开。 */
    fun clear() {
        pending = null
        morphProgress = 0f
    }
}

/**
 * 回退 morph 期间**源卡片**的淡出修饰符：卡片内容随幽灵进度同步淡出，
 * 消除「原卡片 + 飞行的封面幽灵」两份封面同屏。
 *
 * - 只对本次过渡的那张卡生效（按 [key] 比对，[CoverExpandOrigin.pending] 的 key）；
 * - OPPO 系统 morph 路径**从不 record()**，本修饰符恒为 alpha=1（系统要捕获卡片像素，
 *   淡成透明会截到空卡片，那是历史上"返回过程预览图发黑"的根因）；
 * - alpha 在**绘制期**读取，逐帧淡出零重组。
 */
fun Modifier.coverExpandSourceFade(key: String?): Modifier = graphicsLayer {
    val record = CoverExpandOrigin.pending
    alpha = if (record != null && record.key == key) {
        1f - CoverExpandOrigin.morphProgress
    } else {
        1f
    }
}

/** [coverExpandSourceFade] 的目标重载：内部按 [sharedContentKey] 归一，与 [coverExpandTrack] 同键。 */
fun Modifier.coverExpandSourceFade(target: VideoTarget?): Modifier =
    coverExpandSourceFade(target?.sharedContentKey())

/**
 * 封面幽灵 morph 与详情页淡入**共用**的动画曲线。
 *
 * 两条动画必须在同一条时间线上：早先幽灵走硬编码 spring、页面走 `LocalAnimations` 的
 * tween，时长互不相干 —— 幽灵还在半空、页面已经显形，读作"两层画面错位"。
 * 统一到动效 token 后，"动效速度"设置（含无障碍降级 OFF）对两者同时生效。
 */
fun coverExpandMorphSpec(anim: BiliAnimations): FiniteAnimationSpec<Float> =
    tween(durationMillis = anim.long, easing = anim.emphasized)

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
    val anim = LocalAnimations.current
    val progress = remember { Animatable(0f) }
    val end = remember(host) { Rect(0f, 0f, host.width, host.height) }
    // 无障碍降级（BiliAnimations.reduceMotion）：位移/形变类动画退化为纯 fade ——
    // 幽灵从第一帧就铺满目标矩形，只做透明度过渡，不再"飞"。
    val start = remember(record, host, anim.reduceMotion) {
        if (anim.reduceMotion) {
            end
        } else {
            Rect(
                record.cardBounds.left - host.left,
                record.cardBounds.top - host.top,
                record.cardBounds.right - host.left,
                record.cardBounds.bottom - host.top,
            )
        }
    }
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
    // 与详情页淡入共用同一条时间线（coverExpandMorphSpec）：见该函数注释。
    // 进度逐帧写回 CoverExpandOrigin.morphProgress，驱动源卡片同步淡出（否则同屏两份封面）。
    LaunchedEffect(record, host, anim) {
        val progressJob = launch {
            snapshotFlow { progress.value }.collect { CoverExpandOrigin.morphProgress = it }
        }
        try {
            progress.animateTo(1f, coverExpandMorphSpec(anim))
            onFinished()
        } finally {
            progressJob.cancel()
        }
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
