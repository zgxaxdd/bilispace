package com.naaammme.bbspace.feature.video.player

import android.app.Activity
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.icon.AppIcons
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing

@Stable
class VideoGestureState {
    var dragType by mutableStateOf(DragType.None)
        internal set
    var dragFraction by mutableFloatStateOf(0f)
        internal set
    var dragSeekPosMs by mutableStateOf<Long?>(null)
        internal set

    var seekLabel by mutableStateOf<String?>(null)
        internal set
    var showSpeedBadge by mutableStateOf(false)
        internal set
    var speedBadgeText by mutableStateOf("2x")
        internal set
    var doubleTapHint by mutableStateOf<DoubleTapHint?>(null)
        internal set

    internal var doubleTapToken by mutableLongStateOf(0L)
    internal var dragStartPosMs by mutableLongStateOf(0L)

    internal fun resetDrag() {
        dragType = DragType.None
        dragFraction = 0f
        dragSeekPosMs = null
        seekLabel = null
    }

    internal fun showDoubleTap(hint: DoubleTapHint) {
        doubleTapHint = hint
        doubleTapToken++
    }
}

enum class DragType { None, Seek, Brightness, Volume }

/**
 * C1f：亮度手势 HUD 的强调色（**固定暖黄，不随主题**）。
 *
 * 为什么亮度不跟主题、音量跟主题：亮度是**设备物理量**（屏幕背光），它和 app 配色
 * 没有任何语义关系 —— 跟主题走会让"同一块屏幕在不同主题下亮度反馈色不同"，没有道理；
 * 音量是**内容相关的媒体状态**，跟主题主色走是合理的。
 *
 * 上游对应：BiliPai `bbf0587f9` 把亮度色从"按风格硬编码的黄"改成 `brightnessColor`
 * **必填参数**并配 `player_brightness_accent` 资源，测试也从
 * `md3Colors_followThemeForBothLevels` 改名为 `md3Colors_keepBrightnessIndependentOfTheme`
 * 并断言这个**分歧**。
 *
 * ⚠️ 本仓**不引入 `values/colors.xml` 资源色**：`core/designsystem/src/main/res/` 下
 * 只有 `font/` 与 `raw/`，全仓 `colorResource`/`R.color.` **0 命中**，配色方向是
 * **跟随主题**（`theme/BiliColorRoles.kt` + 三条颜色棘轮）。引入并行资源色有踩
 * `ColorRoleAdoptionRatchetTest` / `DesignTokenLintTest` / `ColorContrastTest` 的风险
 * ⇒ 用与 `VideoPlayerPane.FavStarColor` 同一套的"语义色常量"做法。
 *
 * 取值 `0xFFFFD54D` 与 `FavStarColor` 同色：两者不会同屏（手势 HUD 只在拖动时出现，
 * 星标在常驻互动栏），且都属于"暖黄 = 一个明确的数值状态"这套既有语言。
 */
internal val BrightnessAccentColor = Color(0xFFFFD54D)

enum class DoubleTapHint {
    Play,
    Pause,
    Like
}

private const val SEEK_MAX_MS = 60_000L
private const val DRAG_SENSITIVITY = 0.6f
private const val SIDE_GESTURE_ZONE = 0.2f
private const val RIGHT_GESTURE_ZONE_START = 1f - SIDE_GESTURE_ZONE
private val FullscreenVerticalDragBlockExtra = 8.dp
private val SpeedBadgeTopPadding = 20.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.videoGestures(
    state: VideoGestureState,
    onToggleControls: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onStartSpeedUp: () -> String,
    onStopSpeedUp: () -> Unit,
    onBrightnessDelta: (Float) -> Float,
    onVolumeDelta: (Float) -> Float,
    onDragStart: (DragType) -> Unit = {},
    onDragEnd: () -> Unit = {},
    doubleTapLike: () -> Boolean = { false },
    onDoubleTapLike: (Offset) -> Unit = {},
    isPlaying: () -> Boolean,
    positionMs: () -> Long,
    durationMs: () -> Long,
    zoomActive: () -> Boolean = { false },
    /**
     * 上下滑翻页会话（全屏 pager）激活时置 true：**垂直拖动整体让位给 pager**
     * （不判定亮度/音量、不消费事件，pager 从 down 起就能追踪翻页）。
     * 水平拖动（进度 seek）、单击/双击/长按不受影响——与缩放平移的让位同款模式。
     */
    verticalDragPassthrough: () -> Boolean = { false }
): Modifier {
    val curToggleControls by rememberUpdatedState(onToggleControls)
    val curTogglePlay by rememberUpdatedState(onTogglePlay)
    val curSeekTo by rememberUpdatedState(onSeekTo)
    val curStartSpeedUp by rememberUpdatedState(onStartSpeedUp)
    val curStopSpeedUp by rememberUpdatedState(onStopSpeedUp)
    val curBrightnessDelta by rememberUpdatedState(onBrightnessDelta)
    val curVolumeDelta by rememberUpdatedState(onVolumeDelta)
    val curDragStart by rememberUpdatedState(onDragStart)
    val curDragEnd by rememberUpdatedState(onDragEnd)
    val curDoubleTapLike by rememberUpdatedState(doubleTapLike)
    val curOnDoubleTapLike by rememberUpdatedState(onDoubleTapLike)
    val curIsPlaying by rememberUpdatedState(isPlaying)
    val curPositionMs by rememberUpdatedState(positionMs)
    val curDurationMs by rememberUpdatedState(durationMs)
    val curZoomActive by rememberUpdatedState(zoomActive)
    val curVerticalDragPassthrough by rememberUpdatedState(verticalDragPassthrough)
    val visibleStatusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hiddenStatusBarHeight = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()
        .calculateTopPadding()
    val topVerticalDragBlockHeight = if (visibleStatusBarHeight == 0.dp) {
        hiddenStatusBarHeight + FullscreenVerticalDragBlockExtra
    } else {
        0.dp
    }

    return this.pointerInput(topVerticalDragBlockHeight) {
        val slop = viewConfiguration.touchSlop
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
        val topVerticalDragBlockPx = topVerticalDragBlockHeight.toPx()

        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downTime = down.uptimeMillis
                val downPos = down.position
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) continue

                val zoneX = downPos.x / w

                var phase = Phase.Press
                var totalDelta = Offset.Zero
                var lastPos = downPos

                while (true) {
                    val event = if (phase == Phase.Press) {
                        val remaining = (longPressTimeout - (SystemClock.uptimeMillis() - downTime))
                            .coerceAtLeast(0L)
                        withTimeoutOrNull(remaining) {
                            awaitPointerEvent(PointerEventPass.Main)
                        }
                    } else {
                        awaitPointerEvent(PointerEventPass.Main)
                    }
                    if (event == null) {
                        phase = Phase.LongPress
                        state.speedBadgeText = curStartSpeedUp()
                        state.showSpeedBadge = true
                        continue
                    }
                    // 双指接管：捏合缩放层处理，单指状态机完全退出（resetDrag 丢弃未提交
                    // 的 seek；长按倍速需立即恢复原速）。break 后由外层等待全新手势。
                    if (event.changes.count { it.pressed } > 1) {
                        if (phase == Phase.LongPress) {
                            curStopSpeedUp()
                            state.showSpeedBadge = false
                        }
                        state.resetDrag()
                        break
                    }
                    val change = event.changes.firstOrNull() ?: break

                    if (!change.pressed) {
                        when (phase) {
                            Phase.Press -> {
                                val second = withTimeoutOrNull(doubleTapTimeout) {
                                    awaitFirstDown(requireUnconsumed = false)
                                }
                                if (second != null) {
                                    if (curDoubleTapLike()) {
                                        // 双击点赞开启时：双击任意位置 = 点赞 + 爱心动效
                                        curOnDoubleTapLike(second.position)
                                        state.showDoubleTap(DoubleTapHint.Like)
                                    } else {
                                        // 默认：双击任意位置 = 暂停/播放。
                                        //
                                        // **反馈图标必须按「动作发生前」的状态判**（2026-10-11 真机实测缺陷）：
                                        // `curTogglePlay()` 只把意图交给会话（`pause()`/`play()` 是异步落地的），
                                        // 紧接着读 `curIsPlaying()` 拿到的**仍是旧值** —— 于是
                                        // 「正在播 → 双击暂停」提示的却是 ▶（Play），用户看到的反馈与刚发生的
                                        // 动作**正好相反**（真机复现：双击后底栏已显示「播放」，中央提示仍是 ▶）。
                                        // 判据改为"刚才在播吗"：在播 ⇒ 本次动作是暂停 ⇒ 给 ⏸。
                                        val wasPlaying = curIsPlaying()
                                        curTogglePlay()
                                        state.showDoubleTap(
                                            if (wasPlaying) DoubleTapHint.Pause else DoubleTapHint.Play
                                        )
                                    }
                                    waitForAllUp()
                                } else {
                                    curToggleControls()
                                }
                            }
                            Phase.Drag -> {
                                if (state.dragType == DragType.Seek) {
                                    curSeekTo(state.dragSeekPosMs ?: state.dragStartPosMs)
                                }
                                state.resetDrag()
                                curDragEnd()
                            }
                            Phase.LongPress -> {
                                curStopSpeedUp()
                                state.showSpeedBadge = false
                            }
                        }
                        break
                    }

                    val delta = change.position - lastPos
                    totalDelta += delta
                    lastPos = change.position

                    when (phase) {
                        Phase.Press -> {
                            // 轴仲裁（2026-10-01）：横向要**明显主导**才算 seek，
                            // 竖向是否再给一段意图宽限则**分场景**：
                            // - 连播会话（passthrough）：给宽限。旧写法在手指落地的自然竖向
                            //   抖动上就把轴锁死，斜着上滑被读成横向 → 翻页被吞（不可逆）。
                            // - 非会话（详情页）：不给。这里"未决不消费"会让外层纵向列表
                            //   先接走手势，等我们再锁竖向就变成"列表滚 + 亮度动"双响应。
                            val passthrough = curVerticalDragPassthrough()
                            val intent = PlayerDragAxisPolicy.resolveIntent(
                                totalX = totalDelta.x,
                                totalY = totalDelta.y,
                                touchSlop = slop,
                                ambiguousSlopMultiplier =
                                    if (passthrough) {
                                        PlayerDragAxisPolicy.AMBIGUOUS_SLOP_MULTIPLIER
                                    } else {
                                        1f
                                    }
                            )
                            if (intent != PlayerDragIntent.UNDECIDED) {
                                // 已放大（缩放>1）时拖动让位给缩放平移层；单击/双击/长按不受影响
                                if (curZoomActive()) {
                                    state.resetDrag()
                                    waitForAllUp()
                                    break
                                }
                                val isHorizontal = intent == PlayerDragIntent.HORIZONTAL
                                // 上下滑翻页会话激活：垂直拖动整体让位给 pager（不判定
                                // 亮度/音量、全程不消费，pager 从 down 起自行追踪翻页）。
                                // 这是「全屏刷视频时上下滑被亮度/音量抢走」的修复点。
                                if (!isHorizontal && passthrough) {
                                    state.resetDrag()
                                    waitForAllUp()
                                    break
                                }
                                phase = Phase.Drag
                                val dragType = when {
                                    isHorizontal -> DragType.Seek
                                    downPos.y <= topVerticalDragBlockPx -> DragType.None
                                    zoneX < SIDE_GESTURE_ZONE -> DragType.Brightness
                                    zoneX > RIGHT_GESTURE_ZONE_START -> DragType.Volume
                                    else -> DragType.None
                                }
                                state.dragStartPosMs = curPositionMs()
                                state.dragType = dragType
                                curDragStart(dragType)
                                when (dragType) {
                                    DragType.Brightness -> state.dragFraction = curBrightnessDelta(0f)
                                    DragType.Volume -> state.dragFraction = curVolumeDelta(0f)
                                    else -> Unit
                                }
                            }
                        }
                        Phase.Drag -> {
                            when (state.dragType) {
                                DragType.Seek -> {
                                    val frac = totalDelta.x / (w * DRAG_SENSITIVITY)
                                    val deltaMs = (frac * SEEK_MAX_MS).roundToLong()
                                    val newPos = (state.dragStartPosMs + deltaMs)
                                        .coerceIn(0L, curDurationMs())
                                    state.dragFraction = frac.coerceIn(-1f, 1f)
                                    state.dragSeekPosMs = newPos
                                    state.seekLabel = formatSeekDelta(deltaMs)
                                }
                                DragType.Brightness -> {
                                    val deltaFrac = (-totalDelta.y / (h * DRAG_SENSITIVITY)).coerceIn(-1f, 1f)
                                    state.dragFraction = curBrightnessDelta(deltaFrac)
                                }
                                DragType.Volume -> {
                                    val deltaFrac = (-totalDelta.y / (h * DRAG_SENSITIVITY)).coerceIn(-1f, 1f)
                                    state.dragFraction = curVolumeDelta(deltaFrac)
                                }
                                DragType.None -> {}
                            }
                        }
                        Phase.LongPress -> {
                        }
                    }

                    // 方向一旦锁定（phase 不再是 Press）就照旧全程消费，播放器手势语义不变。
                    // **未决期不消费**：连播会话的 pager 要能从起手就追踪翻页——这正是宽限期
                    // 存在的意义；反过来，未决期一消费手势就被播放器吃掉（比旧版更糟）。
                    if (phase != Phase.Press) change.consume()
                }
            }
        }
    }
}

@Composable
fun VideoGestureFeedback(
    state: VideoGestureState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (state.dragType == DragType.Brightness || state.dragType == DragType.Volume) {
            val isBrightness = state.dragType == DragType.Brightness
            val label = if (isBrightness) "亮度" else "音量"
            // C1f（2026-10-13，上游 v0.3.6 同步）：**亮度与音量必须能一眼分开**。
            //
            // 缺陷：两者此前共用 `Color.White` —— 同样的黑底、同样的白字、同样的白色
            // 填充条，只有一行"亮度/音量"文字不同。左侧滑（亮度）与右侧滑（音量）是
            // 两块**肌肉记忆区**，配色一致时用户没法靠余光确认自己滑的是哪一个。
            //
            // 修法对齐上游 `bbf0587f9` 的思路：亮度用**固定暖黄**（与主题解耦 —— 亮度
            // 是设备物理量，不该随 app 主题变色），音量继续走主题主色（它是内容相关的
            // 媒体状态）。与 `FavStarColor` 同一套"语义色不跟主题"的既有做法。
            //
            // ⚠️ 与本仓 `VideoPlayerPane` 的 `FavStarColor` 同为暖黄：两者不会同屏
            // （手势 HUD 只在拖动时出现、互动栏星标在常驻栏），且都是"暖黄 = 一个明确
            // 的数值状态"这一套语言，不冲突。
            val accent = if (isBrightness) BrightnessAccentColor else MaterialTheme.colorScheme.primary

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(BiliSpacing.lg)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md)
                ) {
                    Text(
                        text = label,
                        color = accent,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(100.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(accent.copy(alpha = 0.24f))
                    ) {
                        // U2：填充条在**绘制期**读 dragFraction。
                        // 原来是 `Modifier.height((100 * barH).dp)` —— 拖动期间每帧一次布局，
                        // 本节点与整条父链都要 relayout（HUD 恰恰只在拖动时出现，等于给手势
                        // 最忙的那段时间再加一次布局成本）。Canvas 的尺寸固定，
                        // 变化只发生在 draw 阶段：既不 relayout，也不重组。
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val fraction = state.dragFraction.coerceIn(0f, 1f)
                            if (fraction <= 0f) return@Canvas
                            val fillHeight = size.height * fraction
                            drawRoundRect(
                                color = accent,
                                topLeft = Offset(0f, size.height - fillHeight),
                                size = Size(size.width, fillHeight),
                                // 半径取半宽：这条只有 6dp 宽，extraSmall（约 3dp）在这里
                                // 就已经是胶囊端，绘制期按半宽取整与原视觉同形。
                                cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
                            )
                        }
                    }
                }
            }
        }

        if (state.dragType == DragType.Seek && state.seekLabel != null) {
            Text(
                text = state.seekLabel.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.sm)
            )
        }

        val hint = state.doubleTapHint
        if (hint != null) {
            val token = state.doubleTapToken
            var visible by remember(token) { mutableStateOf(true) }
            LaunchedEffect(token) {
                visible = true
                delay(400)
                visible = false
                state.doubleTapHint = null
            }
            if (visible) {
                // Like 的视觉反馈由播放面板的爱心动效承担，这里不再叠加文字提示
                if (hint == DoubleTapHint.Play || hint == DoubleTapHint.Pause) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(BiliSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (hint == DoubleTapHint.Pause) AppIcons.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (hint == DoubleTapHint.Pause) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        if (state.showSpeedBadge) {
            Text(
                text = state.speedBadgeText,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = SpeedBadgeTopPadding)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.xsPlus)
            )
        }
    }
}

private enum class Phase { Press, Drag, LongPress }

private suspend fun AwaitPointerEventScope.waitForAllUp() {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.changes.all { !it.pressed }) return
    }
}

private fun formatSeekDelta(ms: Long): String {
    val totalSec = ms / 1000
    if (totalSec == 0L) return "0s"
    val sign = if (totalSec > 0) "+" else ""
    return "$sign${totalSec}s"
}

internal fun adjustWindowBrightness(activity: Activity?, fraction: Float) {
    val win = activity?.window ?: return
    win.attributes = win.attributes.apply {
        screenBrightness = fraction.coerceIn(0.01f, 1f)
    }
}

internal fun adjustStreamVolume(audioManager: AudioManager?, volume: Int) {
    val am = audioManager ?: return
    val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    am.setStreamVolume(AudioManager.STREAM_MUSIC, volume.coerceIn(0, maxVolume), 0)
}
