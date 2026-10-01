package com.naaammme.bbspace.feature.video.player

import android.media.AudioManager
import android.os.BatteryManager
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Star
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawWithContent
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.R as Media3UiR
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.naaammme.bbspace.infra.player.danmaku.DanmakuLayer
import com.naaammme.bbspace.infra.player.danmaku.DanmakuOverlayState
import com.naaammme.bbspace.infra.player.danmaku.rememberDanmakuOverlayState
import com.naaammme.bbspace.infra.player.PlayerViewTargetBinder
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.core.model.PlaybackState
import com.naaammme.bbspace.core.model.VideoPlaybackState
import com.naaammme.bbspace.core.common.ShareUtils
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.core.designsystem.component.BiliParticleLoader
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.glass.GlassConfig
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassConfig
import com.naaammme.bbspace.core.designsystem.glass.liquid.lens
import com.naaammme.bbspace.core.designsystem.glass.liquid.vibrancy
import com.naaammme.bbspace.feature.video.R
import com.naaammme.bbspace.feature.video.SponsorBlockUiState
import com.naaammme.bbspace.feature.video.VideoViewModel
import com.naaammme.bbspace.feature.video.formatPlaybackTime
import com.naaammme.bbspace.feature.video.formatSpeed
import com.naaammme.bbspace.feature.video.getAudioName
import com.naaammme.bbspace.feature.video.getQualityName
import com.naaammme.bbspace.feature.video.swipe.SwipeHandoffTiming
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt


internal enum class PlayerVideoResizeMode {
    Fit,
    Zoom,
    Fill
}

private enum class PlayerDialog {
    Quality,
    Audio,
    Speed
}

// —— 播放器浮层视觉规范 ——
// 浮层（顶栏/控制条/胶囊/手势气泡）直接叠在视频画面上，与主题色解耦：
// 统一「黑遮罩 + 白前景」语言，遮罩透明度按可读性校准，保证亮画面上白字仍清晰，
// 深/浅色主题下表现一致。改动时请维持整套 token 同步，勿在单处硬编码。

private val PlayerOverlayScrimTopEnd = Color.Black.copy(alpha = 0.66f)
private val PlayerOverlayScrimBottomEnd = Color.Black.copy(alpha = 0.76f)

/**
 * 控制条顶部渐隐的**中间步**。它不是"另一个遮罩"：控制条上缘用
 * 透明 → 本值 → [PlayerOverlayScrimBottomEnd] 的三段渐变，让控制条与画面衔接更柔和。
 * 单独成 token 是因为它是渐变曲线上的一个定点，与两端语义不同。
 */
private val PlayerOverlayScrimMid = Color.Black.copy(alpha = 0.26f)
private val PlayerPillScrim = Color.Black.copy(alpha = 0.30f)
private val PlayerPillBorder = Color.White.copy(alpha = 0.16f)
private val PlayerPillTextDim = Color.White.copy(alpha = 0.68f)
private val PlayerSecondaryText = Color.White.copy(alpha = 0.9f)

// —— 以下为本轮补全的 token ——
// 此前这些值以裸字面量散落在各处（方案 P1-4 指出"浮层只做了半程 token 化"）。
// 补全的意义：透明度的可读性校准值集中成组，后续统一调整不必再全局搜替换。
// 注意全部维持「黑遮罩 + 白前景」语言，**不要**改成主题色（本文件顶部约定）。

/** 大播放按钮的圆形底盘。比控制条遮罩更透（按钮叠在画面中央，过重会挡住内容）。 */
private val PlayerPlayButtonScrim = Color.Black.copy(alpha = 0.55f)

/** 大播放按钮的描边。比 [PlayerPillBorder] 略亮：圆形描边在亮画面上更易被"吃掉"。 */
private val PlayerPlayButtonBorder = Color.White.copy(alpha = 0.18f)

/** 手势气泡 / 提示胶囊的底盘（深底保证白字在任意画面上可读）。 */
private val PlayerToastScrim = Color.Black.copy(alpha = 0.72f)

/**
 * 播放器区形状常量（PF-06）。
 *
 * 全部取值只依赖 `BiliRadius` 里的固定 dp（对象上的普通 `val`，不读主题状态），
 * 所以进程内恒定。写在 composable 体内 = **每次重组新建一份 Shape 对象**，
 * 而它同时是 `clip` / `background` / `border` / 玻璃链 `remember` 的 key ——
 * 键一旦每帧变新，那条"必须 remember 住整条链"的优化会被静默抵消。
 */
private val PlayerTrackPillShape = RoundedCornerShape(percent = 50)
private val PlayerControlShape = RoundedCornerShape(BiliRadius.playerControl)

/** 统计胶囊的圆端（与轨道同为 50%，但**角色不同**：以后轨道改半径时不该连带改它）。 */
private val PlayerPillShape = RoundedCornerShape(percent = 50)

/** 非全屏播放窗口的圆角（全屏时用 RectangleShape，见调用处）。 */
private val PlayerWindowShape = RoundedCornerShape(16.dp)

/** 气泡胶囊的 20dp 圆角：既不是卡片角色也不是控件角色，所以独立命名。 */
private val PlayerToastShape = RoundedCornerShape(20.dp)

/** 进度条**已播**轨道：实心白。此前与未播同为 `White 0.24`，进度条等于看不出进度。 */
private val PlayerSliderActiveTrack = Color.White

/** 进度条**未播**轨道：白 0.22。压在模糊玻璃之上，太实会把模糊盖死。 */
private val PlayerSliderInactiveTrack = Color.White.copy(alpha = 0.22f)

/** 进度条停用态轨道。 */
private val PlayerSliderDisabledTrack = Color.White.copy(alpha = 0.1f)

/** 进度条停用态滑块。 */
private val PlayerSliderDisabledThumb = Color.White.copy(alpha = 0.24f)

/** 拖动时滑块底盘的染色（玻璃生效时叠在模糊之上，否则即纯色底盘）。 */
private val PlayerSliderThumbScrim = Color.Black.copy(alpha = 0.32f)

/** 拖动时跟随滑块的时间预览气泡底色。 */
private val PlayerSliderPreviewScrim = Color.Black.copy(alpha = 0.72f)

// —— 进度条尺寸规范（细线语言）——
// 轨道 6dp、滑块 16dp。4dp 在 560dpi 实机上偏"发丝"，拖动命中感弱。
private val PlayerSliderTrackHeight = 6.dp
private val PlayerSliderThumbSize = 16.dp

/**
 * 进度平滑外推窗口：与播放进度的轮询周期一致
 * （`Media3PlayerEngine.updateProgressPolling` 的 `delay(500)`）。
 * 每收到一次真实进度，就往"真实值 + 一个窗口的预估增量"做一段线性动画。
 */
private const val SMOOTH_WINDOW_MS = 500L

/** 真实值与视觉值相差超过该比例时直接吸附（seek / 切分P / 缓冲恢复），不做慢速追帧。 */
private const val SMOOTH_SNAP_THRESHOLD = 0.05f

/**
 * 进度条轨道几何（px）。轨道两端按滑块半径内缩，滑块中心才能走到 0%/100% 不越界。
 * 抽出来是因为玻璃层与轨道 Canvas 是**两个兄弟节点**，必须共用同一份几何才不错位。
 *
 * [filledRight] 是 lambda 而不是值：已播右端必须在**绘制/布局期**求值
 * （Canvas draw 块 / offset{} 块），这样平滑动画每帧推进只重绘轨道，不触发重组。
 */
private data class TrackGeometry(
    val left: Float,
    val width: Float,
    val filledRight: () -> Float,
)

/** 控制条按钮选中态底盘。 */
private val PlayerCtrlButtonOn = Color.White.copy(alpha = 0.16f)

/** 控制条按钮未选中态底盘。 */
private val PlayerCtrlButtonOff = Color.White.copy(alpha = 0.10f)

/** 控制条按钮未选中态前景（0.72 而非 0.5：停用也要在亮画面上保持可读）。 */
private val PlayerCtrlButtonTextOff = Color.White.copy(alpha = 0.72f)

// 播放页玻璃：视频节点（TextureView）录进专用背板，控制条采样它做真模糊。
// 视频逐帧更新而录制节点不自动重绘——播放中用 withFrameNanos 逐帧 tick。
// 消费者只有 pane 内部的控制件 chrome（showCtrl 门控、2 秒 transient）；会话级
// 常驻控件（互动栏/评论卡）不允许用真模糊（用户反馈「使用体验很卡」），
// 因此这套 locals 收回文件私有。
private val LocalVideoBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
/**
 * 逐帧 tick 的 **provider**，不是 tick 的值。
 *
 * 为什么必须是 `() -> Int`：`staticCompositionLocalOf<Int>` 交出数值时，提供点
 * （[VideoPlayerPane] 里那句 `provides`）必须在**组合期**读那个 mutableIntStateOf。
 * 于是播放期间每帧一次组合失效 → 整块播放器面板每秒重组 60 次，
 * 而 tick 的唯一用途是让 `drawWithContent` 在绘制期强制重录/重采样。
 * 改成 provider 后读取全部落在绘制期（消费点在 `drawWithContent { tick() }` 里），
 * 每帧只重绘、不重组。
 */
private val LocalVideoGlassTick = staticCompositionLocalOf<() -> Int> { { 0 } }

/**
 * 播放页控件是否采样实时画面做玻璃（设置 → 外观设计「播放页控件模糊」）。
 * 默认 false：未接线时一律走「黑遮罩 + 白前景」纯色，可读性最稳。
 */
private val LocalVideoControlsBlur = staticCompositionLocalOf { false }

/**
 * 播放页控件的玻璃材质（磨砂 / 液态），设置里**独立于**全局 [LocalGlassConfig]。
 * 默认 FROSTED：播放控件叠在实时视频画面上，均匀雾面比强折射更稳、更可读。
 */
private val LocalVideoControlsMaterial = staticCompositionLocalOf { GlassMaterial.FROSTED }

/** 播放页控件的模糊半径基准（dp），乘玻璃强度后即为实际半径。 */
private const val PLAYER_CONTROL_BLUR_DP = 14f

/**
 * 播放页控件的「玻璃底盘」修饰符（进度条 / 中央播放钮 / 控制条按钮 / 胶囊共用）。
 *
 * 材质由 [LocalVideoControlsMaterial] 决定，两档是**两套真实的绘制链路**，不是同一份
 * 模糊换个透明度：
 *
 * - **FROSTED（磨砂）**：`blur` + 容器染色。均匀雾面，画面透过率低，任何画面上都稳。
 * - **LIQUID（液态）**：`blur` + `vibrancy`（饱和度补偿）+ **SDF 边缘折射 `lens()`**
 *   + **Fresnel 高光边**。与全局液态（Dock / 顶栏）走同一组 miuix 效果，
 *   只是参数按控件尺寸缩小。
 *
 * 折射量自适应：`lens()` 的折射量必须有物理意义（≈ 玻璃厚度）。全局 24dp 是给
 * 64dp 高的 Dock 调的；播放控件最矮只有 6dp（进度条轨道），直接套会把整条轨道
 * "吞掉"。这里按 [shapeHeightPx] 取短边的 ~1/3.5 并夹在 2~10dp。
 *
 * 生效条件：控件模糊开关打开、材质未关、背板已录制。任一不满足即返回纯色
 * [scrim]，保证任意组合下都可读。
 *
 * 两点必须一起做，否则会出现"光栅冻结"：
 * 1. 在 `drawWithContent` 里读 [LocalVideoGlassTick] —— 视频逐帧更新，而录制节点
 *    只在自身重绘时重录；读 tick 把本控件的重绘绑到每一帧画面上；
 * 2. 模糊半径乘 [GlassConfig.strength]（0.5–2.0），与 `liquidGlassSurface`
 *    的口径一致——设置里的「玻璃强度」必须同时作用于 Dock/顶栏与播放控件。
 *
 * @param shapeHeightPx 控件短边（px），用于把折射量按尺寸自适应。
 */
@Composable
internal fun Modifier.playerControlGlass(
    shape: Shape,
    scrim: Color,
    shapeHeightPx: Float,
): Modifier {
    val backdrop = LocalVideoBackdrop.current
    val tick = LocalVideoGlassTick.current
    val enabled = LocalVideoControlsBlur.current
    val material = LocalVideoControlsMaterial.current
    val strength = LocalGlassConfig.current.strength.coerceIn(0.5f, 2f)
    val isLiquid = material == GlassMaterial.LIQUID
    if (!enabled || backdrop == null || material == GlassMaterial.OFF) {
        return this.background(scrim, shape)
    }
    val refractionDp = (shapeHeightPx / 3.5f).coerceIn(2f, 10f)
    // **必须 remember 住整条链**：miuix 的 `DrawBackdropElement.equals` 对 shape/effects/
    // highlight/onDraw* 做**引用比较**（已反编译 miuix-blur 0.9.4-rc01 核实），而
    // `update(node)` 末尾**无条件 `invalidateDrawCache()`** —— 每次重组新建 lambda 就会让
    // 该玻璃节点的 blur/sharp/crossfade 缓存整体作废重建。播放进度 500ms 轮询让控制条每秒
    // 重组 2 次 = 每秒重算 2 次玻璃；卡片取色 350ms 渐变期更糟（每帧重算）。
    // `tick` 是 `{ videoGlassTick }` 形态的 provider（读的是状态委托），被 remember 捕获
    // 旧实例也永远读到最新值 —— 这正是它被设计成 `() -> Int` 的原因之一，可安全进键。
    val glassChain = remember(backdrop, shape, scrim, refractionDp, isLiquid, strength, tick) {
        Modifier
            .drawWithContent {
                // 绘制期读 tick（见 LocalVideoGlassTick 的注释）：组合期读会让整块
                // 播放器面板在播放期间每秒重组 60 次。
                tick()
                drawContent()
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    val r = (PLAYER_CONTROL_BLUR_DP * strength).dp.toPx()
                    blur(r, r)
                    if (isLiquid) {
                        // 液态：饱和补偿 + 边缘折射（透镜感）
                        vibrancy(1.5f)
                        lens(
                            refractionHeight = refractionDp.dp.toPx(),
                            refractionAmount = refractionDp.dp.toPx(),
                            chromaticAberration = 0f
                        )
                    }
                },
                highlight = {
                    // 液态才描 Fresnel 高光边：磨砂档描边会读作"硬边框"，不是雾面该有的样子
                    if (isLiquid) Highlight.GlassStrokeBigLight else null
                },
                onDrawSurface = {
                    // 磨砂档染色偏透（雾面要轻）；液态档染色偏实（玻璃要有厚度）
                    drawRect(scrim)
                    if (isLiquid) drawRect(Color.White.copy(alpha = 0.05f))
                }
            )
    }
    return this.then(glassChain)
}


internal val LocalVideoResizeModeState = compositionLocalOf<MutableState<PlayerVideoResizeMode>> {
    error("Missing player video resize mode state")
}

@Suppress("UnsafeOptInUsageError")
@UnstableApi
@Composable
internal fun VideoPlayerPane(
    modifier: Modifier,
    viewModel: VideoViewModel,
    videoTitle: String?,
    isFull: Boolean,
    onToggleFull: () -> Unit,
    onBackClick: () -> Unit,
    onGoHome: () -> Unit,
    /**
     * 上下滑翻页会话（全屏 pager）激活：垂直拖动让位给 pager 翻页，
     * 亮度/音量手势禁用（水平 seek、单击/双击/长按不受影响）。
     */
    swipePagingActive: Boolean = false,
    /**
     * 上下滑会话里"正在等这支视频出画面"（pager 判据：切到这个条目后首帧还没到）。
     *
     * 为什么需要：PlayerView 开了 `setKeepContentOnPlayerReset(true)`，换流期间画面会
     * **停留在上一支视频的最后一帧**；而 pager 那边新页此刻刚从"封面页"切成播放器页
     * ——用户看到的就是"上滑时预览图又闪一下、然后画面还是上一条的"。真机反馈第 2 条。
     *
     * 置 true 时整屏只剩一层**深色加载层 + 大尺寸粒子**（见 [SwipeWaitVeil]）：
     * 不重复铺封面、也不让中央播放键/控制栏露出来，首帧到达后淡出。
     */
    swipeWaitingFirstFrame: Boolean = false,
    /**
     * 等首帧期间**取流/解码失败**：加载层不撤，切成"错误 + 重试"。
     *
     * 为什么需要：以前失败会把加载层撤掉 → 露出"纯黑 + 中央播放键"，看起来像卡死；
     * 而错误详情只写在播放器侧栏里，用户在连播页面上看不到任何解释。
     */
    swipeFailed: Boolean = false,
    /** 失败态的重试回调（`null` = 不给重试入口，只显示错误）。 */
    onRetrySwipe: (() -> Unit)? = null,
    /**
     * 这一页的封面 URL（等首帧时的加载层用它铺底）。
     *
     * 真机反馈「感觉就是盖了一层遮罩」：原来加载层是**不透明深色渐变**，
     * 于是观感是"封面 → 一块黑布 → 视频"。现在用同款封面（`PlayerPreview` + `Fit`）
     * 铺底 + 轻遮罩 + 小粒子，过渡变成"图 → 图+暗+粒子 → 视频"。
     * 传 `null` 时退化回纯深色底（封面缺失的老行为）。
     */
    swipeCoverUrl: String? = null,
    /**
     * 播放设置侧栏（⋮）显隐变化上报：会话宿主（pager）的**常驻互动栏**要据此
     * 隐藏——侧栏是 pane 内的全屏右挂面板，盖不住 pane 之外的互动栏，
     * 不让位的话点亮的爱心会浮在设置面板上（z 序跨层）。`null` = 宿主不接。
     */
    onPlaybackSheetVisibilityChange: ((Boolean) -> Unit)? = null,
    /**
     * 手动横屏开关（`null` = 不显示该入口）。
     *
     * 为什么需要：上下滑会话期间 VideoScreen **锁死竖屏**（否则切视频时会因
     * `isPortraitVideo` 瞬时翻转而转屏，见那边 effect 的注释）。锁的代价是
     * "横屏视频只能看 letterbox"，所以必须给用户一个显式要横屏的入口——
     * BiliPai 的竖屏 pager 也是同一个做法（`onRotateToLandscape`）。
     * 只在**当前视频是横屏**时由 pager 传入（竖屏视频转横屏只会更小）。
     */
    onToggleLandscape: (() -> Unit)? = null,
    /** 当前是否已被用户手动锁成横屏（决定"横屏/竖屏"胶囊的文案与高亮）。 */
    landscapeForced: Boolean = false,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val timeFmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val state by viewModel.videoState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle(initialValue = PlayerSettingsState())
    var activeDialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var showPlaybackSheet by remember { mutableStateOf(false) }
    var showCtrl by remember { mutableStateOf(false) }
    // 底部控制栏的实测高度：会话底部信息层在控制条出现时要**整体让位**上移
    // （否则控制条的进度滑条顶进标题/玻璃圆盘，见 SwipeSessionBottomZone 注释）。
    // 布局期一次性写入，位移在 graphicsLayer 里读 —— 零重组。
    var ctrlBarHeightPx by remember { mutableIntStateOf(0) }
    val zoomState = remember { PlayerZoomState() }
    val videoResizeMode = rememberSaveable { mutableStateOf(PlayerVideoResizeMode.Fit) }
    val topMetaText = remember(showCtrl, isFull) {
        if (showCtrl && isFull) {
            readPlayerTopMetaText(context, timeFmt)
        } else {
            null
        }
    }
    val danmakuOn = settingsState.danmaku.enabled
    val danmakuOverlayState = if (danmakuOn) {
        rememberDanmakuOverlayState(
            initialConfig = settingsState.danmaku,
            initialPositionMs = viewModel.playbackProgress.value.positionMs,
            initialIsPlaying = state.isPlaying,
            initialSpeed = state.speed
        )
    } else {
        null
    }
    val videoAspect = remember(state.currentStream) {
        val width = state.currentStream?.width?.takeIf { it > 0 } ?: return@remember null
        val height = state.currentStream?.height?.takeIf { it > 0 } ?: return@remember null
        width.toFloat() / height.toFloat()
    }
    val resizeMode = remember(isFull, videoResizeMode.value) {
        if (!isFull) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            when (videoResizeMode.value) {
                PlayerVideoResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                PlayerVideoResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                PlayerVideoResizeMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
    }
    val playerView = remember(context) {
        // TextureView（而非默认 SurfaceView）：Compose 圆角裁剪才能作用到视频画面
        (LayoutInflater.from(context).inflate(R.layout.view_bili_player, null, false) as PlayerView)
            .apply {
                useController = false
                setEnableComposeSurfaceSyncWorkaround(true)
                setKeepContentOnPlayerReset(true)
            }
    }
    var lastWarmAspect by remember(playerView) { mutableStateOf<Float?>(null) }

    LaunchedEffect(state.playWhenReady) {
        playerView.keepScreenOn = state.playWhenReady
    }

    // 捏合缩放重置时机：退出全屏、切换视频（ids 变化）、切换清晰度/分P/剧集（currentStream 变化）
    LaunchedEffect(isFull) {
        if (!isFull) zoomState.reset()
    }
    LaunchedEffect(state.ids, state.currentStream) {
        zoomState.reset()
    }

    DisposableEffect(owner, playerView, player) {
        val lifecycle = owner.lifecycle
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            PlayerViewTargetBinder.bind(playerView, player)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> PlayerViewTargetBinder.bind(playerView, player)
                Lifecycle.Event.ON_STOP -> PlayerViewTargetBinder.unbind(playerView)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            PlayerViewTargetBinder.unbind(playerView)
        }
    }

    CompositionLocalProvider(LocalVideoResizeModeState provides videoResizeMode) {
        // 非全屏时播放窗口 16dp 圆角；全屏保持全屏无边角
        val playerCornerShape = if (isFull) {
            RectangleShape
        } else {
            PlayerWindowShape
        }
        // 播放页专用背板：视频节点录进它，控制条玻璃采样它做真模糊。
        // 视频逐帧更新而录制节点不自动重绘——播放中用 withFrameNanos 逐帧 tick 强制重录。
        val videoBackdrop = rememberLayerBackdrop()
        var videoGlassTick by remember { mutableIntStateOf(0) }
        // 设置项「播放页控件模糊 / 质感」：叶子控件经 CompositionLocal 读取，
        // 不把 ViewModel 传进浮层组件（见文件顶部约定）。
        val controlsBlur by viewModel.playerControlsBlur.collectAsStateWithLifecycle()
        val controlsMaterial by viewModel.playerControlsMaterial.collectAsStateWithLifecycle()
        // tick 门控 = 「有消费者」+「画面在动」：
        // - 消费者只有 playerControlGlass 一族（文件内私有 Local），模糊关闭或材质 OFF
        //   时它们早退为纯色背景 —— 这时逐帧重录是 0 消费的纯 CPU/GPU/电量的浪费；
        // - **showCtrl 门控**（性能重构）：玻璃消费者全部长在控制条 chrome 里（控制条/
        //   胶囊/滑杆/中央播放键），控制条隐藏期间没有任何玻璃节点在组合里——
        //   tick 卻照样逐帧唤醒帧时钟，与视频渲染抢帧预算。会话级常驻控件已改静态
        //   玻璃画（见 SwipeRailAction KDoc），控制条一隐藏就整个停掉；
        // - 暂停/缓冲时画面静止，录制链不需要逐帧刷新（glass 采样到的是正确的静态帧），
        //   恢复播放时 LaunchedEffect 随 key 翻转重新起循环，最多落后一帧。
        // 播放页专用背板的 layerBackdrop 录制链本身保持常驻：节点只在被 tick 读
        // 失效时才重绘重录，不 tick 就没有录制成本，注册本身免费。
        val videoGlassActive = showCtrl &&
            state.playWhenReady &&
            controlsBlur &&
            controlsMaterial != GlassMaterial.OFF
        LaunchedEffect(videoGlassActive) {
            if (!videoGlassActive) return@LaunchedEffect
            while (true) {
                withFrameNanos { }
                videoGlassTick++
            }
        }
        // 侧栏显隐上报（会话互动栏让位用，见参数注释）。
        LaunchedEffect(showPlaybackSheet) {
            onPlaybackSheetVisibilityChange?.invoke(showPlaybackSheet)
        }
        CompositionLocalProvider(
            LocalVideoBackdrop provides videoBackdrop,
            LocalVideoGlassTick provides { videoGlassTick },
            LocalVideoControlsBlur provides controlsBlur,
            LocalVideoControlsMaterial provides controlsMaterial
        ) {
        Box(
            modifier = modifier
                .clip(playerCornerShape)
                .background(Color.Black)
        ) {
            // 捏合缩放只作用于视频画面本体：graphicsLayer 挂在 AndroidView 上，
            // 播放器控件（顶栏/控制条/中央按钮）与弹幕层不跟随缩放；
            // Box 的 clip 在外层保证放大内容裁剪在播放视口内
            AndroidView(
                factory = { playerView },
                update = { view ->
                    val content = view.findViewById<AspectRatioFrameLayout>(Media3UiR.id.exo_content_frame)
                    if (content != null) {
                        content.resizeMode = resizeMode
                        if (lastWarmAspect != videoAspect) {
                            content.setAspectRatio(videoAspect ?: 0f)
                            lastWarmAspect = videoAspect
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoomState.scale
                        scaleY = zoomState.scale
                        translationX = zoomState.offsetX
                        translationY = zoomState.offsetY
                    }
                    // 读 tick 强制逐帧重录 + 把视频节点录进播放页专用背板
                    .drawWithContent {
                        videoGlassTick
                        drawContent()
                    }
                    .layerBackdrop(videoBackdrop)
            )

            if (danmakuOverlayState != null) {
                VideoDanmakuLayer(
                    viewModel = viewModel,
                    state = state,
                    settingsState = settingsState,
                    danmakuOverlayState = danmakuOverlayState,
                    playerView = playerView
                )
            }

            VideoPlayerOverlay(
                viewModel = viewModel,
                state = state,
                player = player,
                settingsState = settingsState,
                isFull = isFull,
                zoomState = zoomState,
                showCtrl = showCtrl,
                activeDialog = activeDialog,
                showPlaybackSheet = showPlaybackSheet,
                onCtrlBarHeightChange = { ctrlBarHeightPx = it },
                onShowCtrlChange = { showCtrl = it },
                onShowA = {
                    showCtrl = true
                    activeDialog = PlayerDialog.Audio
                },
                onShowQ = {
                    showCtrl = true
                    activeDialog = PlayerDialog.Quality
                },
                onShowSp = {
                    showCtrl = true
                    activeDialog = PlayerDialog.Speed
                },
                onToggleFull = {
                    showCtrl = true
                    onToggleFull()
                },
                swipePagingActive = swipePagingActive,
                landscapeForced = landscapeForced,
                onToggleLandscape = onToggleLandscape
            )

            // A5：控制层显隐动画（fadeIn + 4dp 轻位移 200ms Standard，退出 150ms fadeOut）
            val ctrlAnim = LocalAnimations.current
            AnimatedVisibility(
                visible = showCtrl,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(durationMillis = ctrlAnim.medium, easing = ctrlAnim.standard)) +
                    slideInVertically(tween(durationMillis = ctrlAnim.medium, easing = ctrlAnim.standard)) { it / 16 },
                exit = fadeOut(tween(durationMillis = 150))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    PlayerOverlayScrimTopEnd,
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }
                        if (!isFull) {
                            IconButton(onClick = onGoHome) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "首页",
                                    tint = Color.White
                                )
                            }
                        }
                        if (isFull) {
                            Column(modifier = Modifier.weight(1f)) {
                                videoTitle?.takeIf(String::isNotBlank)?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                topMetaText?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = PlayerSecondaryText,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 全屏捏合放大后的快捷还原入口
                        if (isFull && zoomState.isZoomed) {
                            PlayerPillChip(
                                text = "还原",
                                showDot = false,
                                dimmed = false,
                                onClick = { zoomState.reset() }
                            )
                        }
                        PlayerPillChip(
                            text = if (danmakuOn) "弹幕" else "弹幕关",
                            showDot = danmakuOn,
                            dimmed = !danmakuOn,
                            onClick = {
                                showCtrl = true
                                viewModel.updateDanmaku(
                                    settingsState.danmaku.copy(enabled = !settingsState.danmaku.enabled)
                                )
                            }
                        )
                        // 手动横屏（只在允许的宿主里出现，见 onToggleLandscape 的参数注释）：
                        // 竖屏连播是锁竖屏的，横屏视频想看满屏只能由用户显式要一次。
                        onToggleLandscape?.let { toggle ->
                            PlayerPillChip(
                                text = if (landscapeForced) "竖屏" else "横屏",
                                showDot = landscapeForced,
                                dimmed = false,
                                onClick = {
                                    showCtrl = true
                                    toggle()
                                }
                            )
                        }
                        IconButton(
                            onClick = {
                                showCtrl = true
                                showPlaybackSheet = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "更多信息",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // ── 会话底部信息层 v2（用户反馈「大幅优化操作布局和 UI」）────────────────
            //
            // 旧版三件套（进度条 BottomCenter / 信息 BottomStart / 互动栏 CenterEnd）
            // 各自 align 散点摆放：互动栏悬在右侧中部、压在画面上，信息两行小字贴着
            // 导航条，底部区域没有结构。新版收进**同一个贴底容器**：
            //
            //   ┌──────────────────────────────┐
            //   │（渐变遮罩：透明 → 58% 黑）      │  ← 任何画面上文字都可读；
            //   │ @UP                ♡ 4551   │     横屏 letterbox 时整块落在
            //   │ 标题（最多两行）      💬 370   │     黑边里，不再与画面字幕打架
            //   │ ─────────── 进度 ─────────── │  ← 抬在手势导航条之上
            //   └──────────────────────────────┘
            //
            // 信息层**常显**（用户定稿）：即使控制条出现也不隐藏——细进度条与信息
            // 随时可见。控制条出现时信息层**整体上移**让位（位移读 ctrlBarHeightPx，
            // graphicsLayer 零重组）：控制条的进度滑条不得顶进标题。
            //
            // 互动栏**不在这里**（用户定稿 v3）：它曾是本层的一员，但 pane 是"每页
            // 一个"的 —— 上下滑时互动栏会跟着整页滑走再滑回来（用户反馈的"整体
            // 向上调动"）。现在它提升为会话级单实例，常驻在 PortraitSwipePager 的
            // 页面层之上，切视频原地换数据、不随页面位移（见 pager 的互动栏接线）。
            if (swipePagingActive && !swipeWaitingFirstFrame) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(SWIPE_BOTTOM_ZONE_FRACTION)
                        .graphicsLayer {
                            translationY = if (showCtrl) -ctrlBarHeightPx.toFloat() else 0f
                        }
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
                        // 会话内常驻信息（信息层第二阶段第一步）：标题 + UP。
                        //
                        // 为什么需要：连播时控件 2 秒自动隐藏，用户连"在看谁的作品"都不知道
                        // （BiliPai 的竖屏页默认就常驻信息区，PortraitFullscreenOverlay.kt:194-394）。
                        // 右侧给会话级互动栏留白（pager 层），标题不与其重叠。
                        // BiliPai 手法：读**本作用域已收集**的 state，不在调用点重复 collect。
                        SwipeSessionInfoLine(
                            title = state.detail?.title,
                            owner = state.detail?.owner?.name
                        )
                        // 会话内**常驻细进度条**（信息层第一阶段）。
                        //
                        // 为什么需要：上下滑会话里控件是 2 秒自动隐藏的，用户没有任何"这支还剩多久"的
                        // 线索；BiliPai 的竖屏页底部就常驻一条细进度（PortraitFullscreenOverlay.kt:387-394），
                        // 抖音/TikTok 同为行业惯例。它不抢注意力，也不与手势冲突（不可点、不消费触摸）。
                        SwipeProgressLine(
                            viewModel = viewModel,
                            modifier = Modifier.padding(
                                top = BiliSpacing.lg,
                                bottom = SwipeProgressBottomInset
                            )
                        )
                    }
                }
            }

            // 播放设置侧栏：必须排在**底部信息层之后**——它是全屏右挂面板，
            // z 序要盖住互动栏/信息行，否则点亮的爱心和计数会穿透面板浮在
            // 设置项上（真机截图实证）。仍排在 SwipeWaitVeil 之前（加载层最上）。
            if (isFull && showPlaybackSheet) {
                VideoPlaybackSidebar(
                    state = state,
                    viewModel = viewModel,
                    onDismiss = { showPlaybackSheet = false },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxSize()
                )
            }

            // 上下滑的「等首帧」加载层。
            //
            // ⚠️ **必须写在播放器 Box 的里面、并且是最后一个子节点**：
            // 这个 `}` 下面几行的那个 `}` 就是 Box 的收尾 —— 放到它外面，这一层会成为
            // CompositionLocalProvider 的第二个子节点、被父级布局排到屏幕外：
            // 组合照常发生（日志、状态都对），但**永远看不见**。
            // 实测踩过：真机上"加载动画不出现"就是这个位置问题。
            // 放在最后 = 盖住视频/弹幕/手势 HUD/中央播放键/上下控制栏；
            // 失败时它切成"错误 + 重试"态（只有按钮消费触摸），
            // 其余情况自身不带 pointerInput，上下滑翻页照常。
            SwipeWaitVeil(
                visible = swipeWaitingFirstFrame,
                coverUrl = swipeCoverUrl,
                failed = swipeFailed,
                onRetry = onRetrySwipe
            )
        }

        if (activeDialog == PlayerDialog.Quality) {
            val src = state.playbackSource
            if (src != null) {
                QualitySelectionDialog(
                    options = src.qualityOptions,
                    curQuality = state.currentStream?.quality,
                    onDismiss = { activeDialog = null },
                    onSelect = { quality ->
                        viewModel.switchQuality(quality)
                        activeDialog = null
                    }
                )
            }
        }

        if (activeDialog == PlayerDialog.Audio) {
            val src = state.playbackSource
            if (src != null) {
                AudioSelectionDialog(
                    audios = src.audios,
                    curAudioId = state.currentAudio?.id,
                    onDismiss = { activeDialog = null },
                    onSelect = { audioId ->
                        viewModel.switchAudio(audioId)
                        activeDialog = null
                    }
                )
            }
        }

        if (activeDialog == PlayerDialog.Speed) {
            SpeedSelectionDialog(
                curSpeed = state.speed,
                onDismiss = { activeDialog = null },
                onSelect = { speed ->
                    viewModel.setSpeed(speed)
                    activeDialog = null
                }
            )
        }

        if (!isFull && showPlaybackSheet) {
            VideoPlaybackSheet(
                state = state,
                viewModel = viewModel,
                limitUnderPlayer = true,
                onDismiss = { showPlaybackSheet = false }
            )
        }
        }
    }
}

/**
 * 「等新视频出画面」的加载层：深色底 + 大尺寸粒子加载。
 *
 * ## 为什么是深色底而不是铺封面（真机反馈）
 * 第一版铺的是当前条目封面（本意是"封面开场、首帧交接"），但用户在真机上看到的是
 * 「刷下一个视频的时候**还是有预览图**」——上滑时封面页已经把这张图给用户看过了，
 * 再铺一次读作"预览图又闪了一下"。现在改为**纯加载态**：不重复任何画面，
 * 只给一个明确的"内容马上来"。
 *
 * ## 为什么必须在控件**之上**（真机反馈）
 * 第一版插在弹幕层之后、控件层之前，于是中央那颗 72dp 播放/暂停键与底栏控件会
 * 压在加载层上面——用户的原话是「同时出现的有一个大大的暂停按钮」。
 * 现在它是播放页 Box 的**最后一个子节点**：等首帧期间整屏只有加载动画，
 * 而它自己不带任何 pointerInput，**不挡手势**（上下滑翻页照常）。
 *
 * 淡出用 [Animatable] + `graphicsLayer` 读值：动画期间零重组（同 `CoverSettleBackdrop`）。
 * `mounted` 单独记：淡出结束后整层从组合里摘掉，不在后台留一个常驻粒子循环。
 *
 * ## 交接**分两段**退场（2026-10-01 改，对齐 BiliPai v0.2.3 的时序形状）
 * 原先整层（封面 + 暗遮罩 + 粒子）共用一个 alpha 一起淡出，淡到一半就是
 * 「半张暗遮罩压在半条画面上」—— 比交接两端都暗，真机读作"翻页闪一下黑"。
 * 现在：暗层先退 → 干净封面保持不透明一拍 → 封面与画面交叉淡化，同时 1.02→1.0 落定收缩。
 * 时长一律从动效令牌推导（见 [SwipeHandoffTiming]），用户的「动效速度」设置照样作用其上。
 */
@Composable
private fun SwipeWaitVeil(
    visible: Boolean,
    /**
     * 这一页的封面（`null` = 没有，退化成纯深色底）。
     *
     * 真机反馈「感觉就是在向下刷视频的时候加了一层遮罩」——原因是换页瞬间封面页被换成
     * 播放器页，播放器页此时盖的是**不透明深色渐变**：用户看到的是"图 → 一块黑布 → 视频"。
     * 现在改成：**先用这一页的封面铺底（与封面页同款 `PlayerPreview` + `Fit` 居中，
     * 取景完全一致）+ 一层轻遮罩 + 粒子**，于是"图 → 图+暗+粒子 → 视频"，
     * 遮罩不再是"另一层东西"。
     *
     * 注意封面用的是**不裁切**的 `PlayerPreview` 变体 + `ContentScale.Fit`：
     * 与 `SwipeCoverPage` 同一个 URL、同一种取景，才不会出现"封面页一张、加载底另一张"的闪。
     */
    coverUrl: String? = null,
    /** 这一支取流/解码失败了：加载层留着，改成"错误 + 重试"，而不是撤掉露出一片黑。 */
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 初值就按 visible 落位：进入全屏/打开 story 时本来就在等首帧，加载层必须**首帧就在**，
    // 否则会先露一帧黑（或上一支视频的最后一帧）再盖上——那正是要修掉的闪。
    val anim = LocalAnimations.current
    val timing = remember(anim) {
        SwipeHandoffTiming.resolve(
            shortMs = anim.short,
            mediumMs = anim.medium,
            reduceMotion = anim.reduceMotion
        )
    }
    // **内容（封面）与非内容（暗遮罩 + 粒子）走两条 alpha**。原先它们是同一层、共用一个
    // alpha 一起淡出，于是淡到一半时是"半张暗遮罩压在半条画面上"—— 比交接两端都暗，
    // 真机读作"翻页闪一下黑"。暗遮罩在"还在等"的时候必要（白粒子要在浅色封面上读得清），
    // 但它不该跟着内容一起退。时序见 [SwipeHandoffTiming]。
    val coverAlpha = remember { Animatable(if (visible) 1f else 0f) }
    val chromeAlpha = remember { Animatable(if (visible) 1f else 0f) }
    // 落定收缩的进度 0→1（只被乘进 scale，不是独立的动画目标）。
    val settle = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(visible) }
    // 暗层退完就把粒子从组合里摘掉：它带常驻无限动画，让它空转正好占掉交接那几帧的预算。
    var chromeGone by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            chromeGone = false
            coverAlpha.snapTo(1f)
            chromeAlpha.snapTo(1f)
            settle.snapTo(0f)
        } else if (mounted) {
            // 1) 暗遮罩 + 粒子先退 → 2) 干净封面再停留一拍（刚出帧的画面常常偏暗，
            // 立刻 dissolve 会读成"闪"）→ 3) 封面与画面交叉淡化，同时做落定收缩。
            chromeAlpha.animateTo(0f, tween(timing.chromeFadeMs))
            chromeGone = true
            if (timing.coverHoldMs > 0) delay(timing.coverHoldMs.toLong())
            launch { settle.animateTo(1f, tween(timing.coverFadeMs)) }
            coverAlpha.animateTo(0f, tween(timing.coverFadeMs))
            mounted = false
        }
    }
    if (!mounted) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (!failed && !coverUrl.isNullOrBlank()) {
            // 封面铺底：与封面页同款（不裁切 + Fit 居中），整段过渡里"看到的是同一张图"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = coverAlpha.value
                        val scale = 1f + (timing.settleScale - 1f) * (1f - settle.value)
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                BiliAsyncImage(
                    url = coverUrl,
                    contentDescription = null,
                    variant = BiliImageVariant.PlayerPreview,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = chromeAlpha.value },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        // 轻遮罩：比原来的不透明渐变浅得多 —— 有封面时它只负责"压暗 + 提示在加载"；
                        // 没有封面时（封面缺失/失败）自动退化成深色底（下面的兜底色）。
                        if (failed || coverUrl.isNullOrBlank()) {
                            Brush.verticalGradient(listOf(WaitVeilTopColor, WaitVeilBottomColor))
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    WaitVeilScrimTopColor,
                                    WaitVeilScrimBottomColor
                                )
                            )
                        }
                    )
            )
            if (failed) {
                // 失败态：图标 + 文案 + 重试。整层只有按钮可点，其余区域不消费触摸
                // （刷视频的上下滑与返回手势照常可用）。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(VeilErrorSpacing)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = WaitVeilErrorTint,
                        modifier = Modifier.size(VeilErrorIconSize)
                    )
                    Text(
                        text = "这一支加载失败了",
                        color = WaitVeilErrorTint,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            } else if (!chromeGone) {
                // 暗层一退完就摘掉粒子：它的常驻无限动画不该占交接那几帧的预算。
                BiliParticleLoader(
                    modifier = Modifier
                        // 有封面时用**小一档**的粒子（封面是主角，粒子只是"在加载"的提示）；
                        // 没有封面时保持原来的大尺寸（它是唯一内容）。
                        .fillMaxWidth(
                            if (coverUrl.isNullOrBlank()) LOADER_WIDTH_FRACTION else VEIL_COVER_LOADER_WIDTH_FRACTION
                        )
                        .aspectRatio(1f)
                )
            }
        }
    }
}

/** 失败态配色（比正文暗一档，避免"加载失败"比视频本身还抢眼）。 */
private val WaitVeilErrorTint = Color(0xFFB9BEC7)

/**
 * 会话内常驻细进度条：底部一条轨道 + 已播比例。
 *
 * 高度与宽度都用**比例**（不写 dp 字面量）：设计 token 的 lint 棘轮会把新增的
 * `.dp` 字面量判红，而且比例写法在小屏/大屏上都自然跟随。
 */
@Composable
private fun SwipeProgressLine(
    viewModel: VideoViewModel,
    modifier: Modifier = Modifier,
) {
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val durationMs = progress.durationMs
    if (durationMs <= 0L) return
    val fraction = (progress.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(SWIPE_PROGRESS_TRACK_FRACTION)
            .background(SwipeProgressTrackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(SwipeProgressBarColor)
        )
    }
}

/**
 * 会话内互动栏（**会话级单实例**，由 `PortraitSwipePager` 常驻在页面层之上；
 * 组件实现留在本文件与控制件玻璃同语言）：**点赞 + 评论 + 分享**。
 *
 * 为什么提升到 pager：pane 是"每页一个"的，互动栏放 pane 里会跟着页面一起
 * 滑走再滑回来（用户反馈的"整体向上调动"）；单实例常驻后，上下滑时它钉在
 * 原位，只随 viewModel 状态原地换数据。
 *
 * 为什么是这三个：它们的"状态/动作/依赖"都现成 ——
 * 点赞有 `userState.liked` + `stat.like` + `toggleLike()`；评论有宿主传入的
 * `onOpenComments`（`PortraitSwipePager` 的悬浮卡片）；分享有
 * `ShareUtils.videoUrl/shareText`（详情页同一套链接构造）。
 * **收藏**要先选文件夹（`toggleFavFolder(fid)`）、**投币**有二次确认交互 ——
 * 这两个留到后续批次，不在这里做假按钮。
 *
 * 触摸：这是会话覆盖层里的可点区域（除失败态的重试按钮），固定在右侧、
 * 悬在信息块上方（用户定稿"整体向上"），图标之间的空隙仍透给 pager 的上下滑手势。
 */
@Composable
internal fun SwipeSessionActionRail(
    liked: Boolean,
    likeText: String?,
    onToggleLike: () -> Unit,
    replyText: String?,
    /** 打开评论（`null` = 当前场景还没有评论宿主，不显示评论按钮，避免假按钮）。 */
    onOpenComments: (() -> Unit)?,
    /** 已收藏态 + 收藏数（`userState.faved` / `stat.fav`，与详情页同一份真值源）。 */
    faved: Boolean,
    favText: String?,
    /**
     * 打开收藏夹面板（`null` = 宿主不接，同评论按钮的口径：宁可少一个入口，
     * 也不做点了没反应的假按钮）。会话与详情页共用 VM 上那套收藏夹状态机。
     */
    onOpenFavorite: (() -> Unit)? = null,
    shareBvid: String?,
    shareAid: Long,
    shareTitle: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.padding(end = BiliSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.lg)
    ) {
        // 现代短视频的互动栏：图标大、计数在图标**下方**、层间距均匀（抖音/B 站竖屏同款）
        SwipeRailAction(
            icon = Icons.Filled.Favorite,
            contentDescription = if (liked) "取消点赞" else "点赞",
            label = likeText,
            tint = if (liked) LikeHeartColor else Color.White,
            onClick = onToggleLike
        )
        if (onOpenComments != null) {
            SwipeRailAction(
                icon = Icons.AutoMirrored.Filled.Comment,
                contentDescription = "评论",
                label = replyText,
                tint = Color.White,
                onClick = onOpenComments
            )
        }
        // 收藏（补齐 BiliPai 竖屏互动栏的 赞/评/藏/享 四件套里缺的那一件）。
        // 与评论按钮同一口径：宿主不接（`null`）就整颗不出现，不做点了没反应的假按钮。
        if (onOpenFavorite != null) {
            SwipeRailAction(
                icon = if (faved) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = if (faved) "取消收藏" else "收藏",
                label = favText,
                tint = if (faved) FavStarColor else Color.White,
                onClick = onOpenFavorite
            )
        }
        SwipeRailAction(
            icon = Icons.Filled.Share,
            contentDescription = "分享",
            label = null,
            tint = Color.White,
            onClick = {
                // 与详情页共用同一套链接构造（BV 优先、av 兜底）
                val url = ShareUtils.videoUrl(shareBvid, shareAid, page = 0)
                ShareUtils.shareText(context, shareTitle, url)
            }
        )
    }
}

/**
 * 互动栏里的单个动作：**液态玻璃圆盘**（图标）+ 下方计数。
 *
 * 视觉约定（用户定稿「液态玻璃」，v2 改静态实现）：圆盘是**液态玻璃观感的静态画**
 * ——白色三段渐变 + 白色细描边，**不走** [playerControlGlass] 背景模糊。
 *
 * 为什么不用真模糊：互动栏是会话内**常驻**的（不随控制条隐藏），真背景模糊 =
 * 每帧一次 RenderEffect 采样，播放期间 3 颗圆盘持续吃 GPU（用户反馈「使用体验
 * 很卡」）；BiliPai 的互动栏同样不用玻璃。静态渐变在视频上的观感与磨砂玻璃
 * 几乎一致，代价为零。图标 36dp 视觉尺寸，计数 `labelSmall`、88% 白落在圆盘
 * 下方；整块用 `IconButton` 保证 ≥48dp 触摸目标。
 */
@Composable
private fun SwipeRailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    label: String?,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(SwipeRailDiscSize)
                .clip(CircleShape)
                .background(SwipeRailGlassBrush)
                .border(1.dp, PlayerPlayButtonBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(SwipeRailIconSize)
                )
            }
        }
        label?.takeIf(String::isNotBlank)?.let { count ->
            Text(
                text = count,
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/** 互动栏图标视觉尺寸（触摸目标由 IconButton 的 48dp 保证）。走 spacing token 的 1.5 倍档，不新增 dp 字面量。 */
private val SwipeRailIconSize = BiliSpacing.xxl * 1.5f

/** 互动栏液态玻璃圆盘直径：图标 36dp + 玻璃留白。token 的 1.75 倍档，不新增 dp 字面量。 */
private val SwipeRailDiscSize = BiliSpacing.xxxl * 1.75f

/**
 * 互动栏圆盘的**静态**液态玻璃画：白色三段渐变（上亮下暗 + 中段透）模拟磨砂，
 * 叠一条白描边（[PlayerPlayButtonBorder]）。顶层 val 只构建一次，零逐帧成本——
 * 常驻控件不许用逐帧采样的真背景模糊（见 [SwipeRailAction] 的 KDoc）。
 */
private val SwipeRailGlassBrush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.28f),
    0.55f to Color.White.copy(alpha = 0.10f),
    1f to Color.White.copy(alpha = 0.18f)
)

/** 会话内进度条的底部内边距：抬到手势导航条之上。 */
private val SwipeProgressBottomInset = BiliSpacing.xxxl

/** 会话底部信息层（遮罩 + 信息列 + 互动栏 + 进度条）高度占屏比：够放下两行标题与互动栏，又不盖过画面主体。 */
private const val SWIPE_BOTTOM_ZONE_FRACTION = 0.38f

/**
 * 底部渐变遮罩：上 45% 全透明、落到屏底 58% 黑。
 *
 * 为什么需要：旧版信息两行小字直接压在画面/黑底上——竖屏视频遇到亮画面时白字看不清，
 * 横屏 letterbox 时底部大片纯黑又显得"空"。遮罩把信息区从画面里"托"出来，
 * 两种画幅下文字都稳定可读。静态画笔，顶层 val 只构建一次，零逐帧成本。
 */
private val SwipeBottomScrimBrush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.45f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.58f)
)

/** 轨道高度占屏高比例（≈3dp）：够看清、又不抢画面。 */
private const val SWIPE_PROGRESS_TRACK_FRACTION = 0.0035f

/**
 * 会话内常驻信息：UP 名 + 标题（左下角，与互动栏同排、压在进度条之上）。
 *
 * v2 改版（用户反馈「大幅优化操作布局和 UI」）：
 * - **UP 名上置、标题在下**（抖音/B 站竖屏页同款的信息层级）——先知道"在看谁"，
 *   再读"在看什么"；UP 名提到 `labelLarge`、86% 白，标题提到 `titleMedium`；
 * - 标题放宽到**两行**：旧版一行省略，长标题（真机样例"扫舱剩下的玉米有几吨之多，
 *   为什么不能再卖出…"）永远读不全；
 * - 可读性交给底部的渐变遮罩（[SwipeBottomScrimBrush]），文字本身不加背板；
 *
 * 设计约束：
 * - **只读**（无点击、无 pointerInput）：连播时任何可点区域都会和上下滑抢手指；
 * - 间距/字号全部走 token（`BiliSpacing` + `MaterialTheme.typography`），
 *   不新增 dp 字面量（`DesignTokenLintTest` 的棘轮会判红）。
 */
@Composable
private fun SwipeSessionInfoLine(
    title: String?,
    owner: String?,
    modifier: Modifier = Modifier,
) {
    val titleText = title?.takeIf(String::isNotBlank) ?: return
    Column(
        modifier = modifier
            .padding(
                start = BiliSpacing.lg,
                end = BiliSpacing.sm,
                bottom = BiliSpacing.md
            ),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
    ) {
        owner?.takeIf(String::isNotBlank)?.let { name ->
            Text(
                text = "@$name",
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = titleText,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val SwipeProgressTrackColor = Color.White.copy(alpha = 0.20f)
private val SwipeProgressBarColor = Color.White.copy(alpha = 0.86f)

/** 失败态图标尺寸与间距（仍走动画 token，不新增 dp 字面量）。 */
private val VeilErrorIconSize = 40.dp
private val VeilErrorSpacing = 12.dp

/** 加载层底色（播放器族统一「深色底 + 白前景」，与主题色解耦）。 */
private val WaitVeilTopColor = Color(0xFF14161A)
private val WaitVeilBottomColor = Color(0xFF08090B)

/**
 * 有封面铺底时的**轻遮罩**：压暗但不"糊黑"。
 *
 * 为什么要区分两套底色：没有封面时这一层就是唯一内容（需要够暗才不像"没画面"）；
 * 有封面时它只是给"在加载"做提示，太暗就又会变成用户说的"一层遮罩"。
 */
private val WaitVeilScrimTopColor = Color(0x7314161A)
private val WaitVeilScrimBottomColor = Color(0x8C08090B)

/** 加载动画边长占屏宽的比例。0.34 在 1272px 屏上约 433px（≈155dp）—— 是"主角"不是"小转圈"。 */
private const val LOADER_WIDTH_FRACTION = 0.34f

/** 有封面铺底时粒子缩小一档（封面是主角，粒子只是"在加载"的提示）。 */
private const val VEIL_COVER_LOADER_WIDTH_FRACTION = 0.20f

/** 双击点赞爱心动效：在点击位置放大、上浮并淡出 */

@Composable
private fun DoubleTapLikeHeart(
    position: Offset,
    token: Long,
    modifier: Modifier = Modifier
) {
    if (token == 0L) return
    var visible by remember(token) { mutableStateOf(true) }
    val scale = remember(token) { Animatable(0.4f) }
    val alpha = remember(token) { Animatable(1f) }
    val rise = remember(token) { Animatable(0f) }
    LaunchedEffect(token) {
        scale.animateTo(1.2f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
        rise.animateTo(1f, tween(durationMillis = 420))
        alpha.animateTo(0f, tween(durationMillis = 200))
        visible = false
    }
    if (!visible) return
    Icon(
        imageVector = Icons.Default.Favorite,
        contentDescription = "点赞",
        tint = LikeHeartColor,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
                translationX = position.x - 28.dp.toPx()
                translationY = position.y - 28.dp.toPx() - rise.value * 56.dp.toPx()
            }
            .size(56.dp)
    )
}

private val LikeHeartColor = Color(0xFFFF6B8B)

/**
 * 会话互动栏「已收藏」的星色：B 站收藏用的是暖黄而不是点赞的粉，两态必须能一眼分开
 * （同一条互动栏里两颗同色实心星/心会被读成同一个动作）。
 */
private val FavStarColor = Color(0xFFFFD54D)

@UnstableApi
@Composable
private fun VideoDanmakuLayer(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    settingsState: PlayerSettingsState,
    danmakuOverlayState: DanmakuOverlayState,
    playerView: PlayerView
) {
    val danmakuState by viewModel.danmakuState.collectAsStateWithLifecycle()
    // 必须 collect 而不是直读 `playbackProgress.value`：StateFlow.value 不订阅，
    // 本组合若因其它原因长期不重组，拿到的就是旧进度；随后任何一次 sync（改弹幕配置、
    // 播放/暂停）都会拿旧 positionMs 去算弹幕窗口，表现为弹幕跳回旧段。
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()

    DanmakuLayer(
        playerView = playerView,
        overlayState = danmakuOverlayState,
        danmakuState = danmakuState,
        danmakuConfig = settingsState.danmaku,
        positionMs = progress.positionMs,
        isPlaying = state.isPlaying,
        speed = state.speed,
        seekEventId = state.seekEventId,
        hasSource = state.playbackSource != null
    )
}

@Composable
private fun VideoPlayerOverlay(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    player: Player?,
    settingsState: PlayerSettingsState,
    isFull: Boolean,
    zoomState: PlayerZoomState,
    showCtrl: Boolean,
    activeDialog: PlayerDialog?,
    showPlaybackSheet: Boolean,
    onShowCtrlChange: (Boolean) -> Unit,
    onShowA: () -> Unit,
    onShowQ: () -> Unit,
    onShowSp: () -> Unit,
    onToggleFull: () -> Unit,
    // 上下滑翻页会话激活：垂直拖动让位给 pager（见 videoGestures 的参数注释）
    swipePagingActive: Boolean = false,
    /** 当前是否已被用户手动锁成横屏（决定"横屏/竖屏"胶囊的文案与高亮）。 */
    landscapeForced: Boolean = false,
    /** 手动横屏开关；null = 本宿主不提供该入口（非会话路径没有竖屏锁，不需要）。 */
    onToggleLandscape: (() -> Unit)? = null,
    /** 底部控制栏实测高度（px）上报：会话信息层据此在控制条出现时整体上移让位。 */
    onCtrlBarHeightChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val gestureState = remember { VideoGestureState() }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    var dragStartBrightness by remember { mutableFloatStateOf(0.5f) }
    var dragStartVolumeFrac by remember { mutableFloatStateOf(0f) }
    var speedBeforeGesture by remember { mutableFloatStateOf(1f) }
    var lastGestureBrightness by remember { mutableFloatStateOf(Float.NaN) }
    var lastGestureVolumeFrac by remember { mutableFloatStateOf(Float.NaN) }
    var heartToken by remember { mutableLongStateOf(0L) }
    var heartPos by remember { mutableStateOf(Offset.Zero) }
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val act = LocalActivity.current

    LaunchedEffect(
        showCtrl,
        state.isPlaying,
        dragMs,
        gestureState.dragType,
        gestureState.showSpeedBadge,
        activeDialog,
        showPlaybackSheet
    ) {
        if (
            showCtrl &&
            state.isPlaying &&
            dragMs == null &&
            gestureState.dragType == DragType.None &&
            !gestureState.showSpeedBadge &&
            activeDialog == null &&
            !showPlaybackSheet
        ) {
            delay(2_000)
            onShowCtrlChange(false)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .videoGestures(
                    state = gestureState,
                    onToggleControls = { onShowCtrlChange(!showCtrl) },
                    onTogglePlay = viewModel::togglePlayPause,
                    onSeekTo = viewModel::seekTo,
                    onStartSpeedUp = {
                        speedBeforeGesture = state.speed
                        val speed = settingsState.playback.gestureSpeed
                        viewModel.setSpeed(speed)
                        formatSpeed(speed)
                    },
                    onStopSpeedUp = { viewModel.setSpeed(speedBeforeGesture) },
                    onBrightnessDelta = { deltaFrac ->
                        val next = (dragStartBrightness + deltaFrac).coerceIn(0.01f, 1f)
                        if (abs(next - lastGestureBrightness) >= 0.02f || lastGestureBrightness.isNaN()) {
                            adjustWindowBrightness(act, next)
                            lastGestureBrightness = next
                        }
                        next
                    },
                    onVolumeDelta = { deltaFrac ->
                        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                        if (maxVol <= 0) {
                            0f
                        } else {
                            val next = (dragStartVolumeFrac + deltaFrac).coerceIn(0f, 1f)
                            if (abs(next - lastGestureVolumeFrac) >= (1f / maxVol.toFloat()) || lastGestureVolumeFrac.isNaN()) {
                                val newVol = (next * maxVol).roundToInt()
                                adjustStreamVolume(audioManager, newVol)
                                lastGestureVolumeFrac = next
                            }
                            next
                        }
                    },
                    onDragStart = { dragType ->
                        when (dragType) {
                            DragType.Brightness -> {
                                dragStartBrightness = act?.window?.attributes?.screenBrightness
                                    ?.takeIf { it in 0f..1f }
                                    ?.coerceIn(0.01f, 1f)
                                    ?: 0.5f
                                lastGestureBrightness = Float.NaN
                            }
                            DragType.Volume -> {
                                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                                val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                                dragStartVolumeFrac = if (maxVol > 0) {
                                    curVol.toFloat() / maxVol.toFloat()
                                } else {
                                    0f
                                }
                                lastGestureVolumeFrac = Float.NaN
                            }
                            else -> Unit
                        }
                    },
                    onDragEnd = { },
                    doubleTapLike = { settingsState.playback.doubleTapLike },
                    onDoubleTapLike = { pos ->
                        heartPos = pos
                        heartToken++
                        viewModel.toggleLike()
                    },
                    isPlaying = { state.isPlaying },
                    positionMs = {
                        val progress = viewModel.playbackProgress.value
                        dragMs
                            ?: gestureState.dragSeekPosMs
                            ?: progress.positionMs
                    },
                    durationMs = {
                        val progress = viewModel.playbackProgress.value
                        player?.duration
                            ?.takeIf { it > 0L }
                            ?: progress.durationMs.takeIf { it > 0L }
                            ?: state.playbackSource?.durationMs?.coerceAtLeast(0L)
                            ?: 0L
                    },
                    zoomActive = { zoomState.isZoomed },
                    // pager 会话激活：垂直拖动让位翻页（修「上下滑被亮度/音量抢走」）
                    verticalDragPassthrough = { swipePagingActive }
                )
                // 链内側：Main pass 先于 videoGestures；双指/已放大时接管，其余放行
                .playerPinchZoom(enabled = isFull, state = zoomState)
        )

        VideoGestureFeedback(gestureState)

        DoubleTapLikeHeart(
            position = heartPos,
            token = heartToken,
            modifier = Modifier.fillMaxSize()
        )

        // 暂停时显示中央大播放键（现代播放器通用交互）。
        //
        // 判据不能只看 isPlaying：Media3 的 isPlaying == (playbackState==READY && playWhenReady)，
        // 因此「起播前 / 缓冲中 / 首帧未出」这些阶段 isPlaying 也是 false。只用 isPlaying 判断，
        // 会让这个中央播放键在每次打开视频时闪一下再消失（用户可见的「暂停标志」）。
        //
        // 同时必须保留 Ended：播完后 playWhenReady 仍为 true 而 isPlaying 变 false，
        // 若一并抑制，用户就没有「重播」入口了。
        val showCenterPlayButton =
            !state.isPlaying &&
                (!state.playWhenReady || state.playbackState == PlaybackState.Ended)

        if (showCenterPlayButton) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .clip(CircleShape)
                    .playerControlGlass(
                        shape = CircleShape,
                        scrim = PlayerPlayButtonScrim,
                        shapeHeightPx = with(LocalDensity.current) { 72.dp.toPx() }
                    )
                    .border(1.dp, PlayerPlayButtonBorder, CircleShape)
                    .clickable { viewModel.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // 空降助手：手动跳过 / 精彩时刻按钮与跳过提示
        val sbState by viewModel.sbState.collectAsStateWithLifecycle()
        LaunchedEffect(sbState.toastMessage) {
            sbState.toastMessage?.let { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                viewModel.consumeSbToast()
            }
        }
        SponsorBlockOverlay(
            state = sbState,
            showCtrl = showCtrl,
            onSkip = viewModel::sbSkipManual,
            onDismiss = viewModel::sbDismissManual,
            onJumpPoi = viewModel::sbJumpPoi,
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        // A5：底部控制栏同规格显隐动画
        val ctrlAnim = LocalAnimations.current
        AnimatedVisibility(
            visible = showCtrl,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(durationMillis = ctrlAnim.medium, easing = ctrlAnim.standard)) +
                slideInVertically(tween(durationMillis = ctrlAnim.medium, easing = ctrlAnim.standard)) { it / 16 },
            exit = fadeOut(tween(durationMillis = 150))
        ) {
            PlayerCtrlBarHost(
                viewModel = viewModel,
                state = state,
                player = player,
                isFull = isFull,
                dragMs = { dragMs },
                gestureSeekMs = gestureState.dragSeekPosMs,
                onDragMsChange = { dragMs = it },
                onShowCtrlChange = onShowCtrlChange,
                onShowA = onShowA,
                onShowQ = onShowQ,
                onShowSp = onShowSp,
                onToggleFull = onToggleFull,
                modifier = Modifier.onSizeChanged { onCtrlBarHeightChange(it.height) }
            )
        }
    }
}

@Composable
private fun PlayerCtrlBarHost(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    player: Player?,
    isFull: Boolean,
    dragMs: () -> Long?,
    gestureSeekMs: Long?,
    onDragMsChange: (Long?) -> Unit,
    onShowCtrlChange: (Boolean) -> Unit,
    onShowA: () -> Unit,
    onShowQ: () -> Unit,
    onShowSp: () -> Unit,
    onToggleFull: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    // dragMs 存在**外层**（浮层之上、活得比控制条久）的作用域里，而原本只有
    // onSeekDone 会清它。本 host 挂在控制条的 AnimatedVisibility 内：手指还按在
    // 进度条上时控件自动隐藏、或另一根手指点视频区把控制条关掉，pointerInput 就
    // 随组合一起消失，onSeekEnd/onSeekCancel 永不触发 →
    // `barMs = dragSeekMs ?: ...` 从此永久停在拖动位置，进度条看起来"卡死"。
    // 离开组合时兜底清一次。回调用 rememberUpdatedState 取最新实例，
    // 免得 DisposableEffect(Unit) 捕获到第一次组合时的旧闭包。
    val latestClearDragMs = rememberUpdatedState(onDragMsChange)
    DisposableEffect(Unit) {
        onDispose { latestClearDragMs.value(null) }
    }
    val durationMs = player?.duration
        ?.takeIf { it > 0L }
        ?: progress.durationMs.takeIf { it > 0L }
        ?: state.playbackSource?.durationMs?.coerceAtLeast(0L)
        ?: 0L
    val dragSeekMs = dragMs()
    val barMs = dragSeekMs ?: gestureSeekMs ?: progress.positionMs
    val sliderVal = if (durationMs > 0L) {
        (barMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sbState by viewModel.sbState.collectAsStateWithLifecycle()
    val sbMarks = remember(sbState, durationMs) {
        if (!sbState.markersEnabled || durationMs <= 0L) {
            emptyList()
        } else {
            sbState.segments.map { seg ->
                val start = (seg.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                val rawEnd = if (seg.endMs > seg.startMs) {
                    seg.endMs.toFloat() / durationMs
                } else {
                    (seg.startMs + 500L).toFloat() / durationMs
                }
                SbMark(start, rawEnd.coerceIn(0f, 1f), Color(seg.category.colorArgb))
            }
        }
    }

    PlayerCtrlBar(
        playText = if (state.isPlaying) "暂停" else "播放",
        timeText = formatPlaybackTime(barMs, durationMs),
        audioText = state.currentAudio?.let { getAudioName(it.id, short = true) } ?: "音频",
        qualityText = getQualityName(state.playbackSource, state.currentStream),
        speedText = formatSpeed(state.speed),
        fullText = if (isFull) "还原" else "全屏",
        sliderVal = sliderVal,
        sliderOn = durationMs > 0L,
        playing = state.isPlaying,
        durationMs = durationMs,
        marks = sbMarks,
        audioOn = (state.playbackSource?.audios?.size ?: 0) > 1,
        qualityOn = (state.playbackSource?.qualityOptions?.size ?: 0) > 1,
        onTogglePlay = viewModel::togglePlayPause,
        onAudioClick = onShowA,
        onQualityClick = onShowQ,
        onSpeedClick = onShowSp,
        onFullClick = onToggleFull,
        onSeekChange = { frac ->
            onShowCtrlChange(true)
            onDragMsChange((durationMs * frac).toLong())
        },
        onSeekDone = {
            val next = dragMs() ?: return@PlayerCtrlBar
            viewModel.seekTo(next)
            onDragMsChange(null)
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerCtrlBar(
    playText: String,
    timeText: String,
    audioText: String,
    qualityText: String,
    speedText: String,
    fullText: String,
    sliderVal: Float,
    sliderOn: Boolean,
    /** 是否正在播放：转发给 [PlayerProgressBar] 做进度平滑外推。 */
    playing: Boolean,
    /** 视频总时长，供进度条拖动时间气泡换算；<= 0 时不显示气泡。 */
    durationMs: Long,
    marks: List<SbMark> = emptyList(),
    audioOn: Boolean,
    qualityOn: Boolean,
    onTogglePlay: () -> Unit,
    onAudioClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onFullClick: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                // 全出血遮罩：边缘与视频完全对齐，圆角由外层裁剪跟随
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        PlayerOverlayScrimMid,
                        PlayerOverlayScrimBottomEnd
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 进度条：**自绘**，不再复用 Material Slider 默认皮肤。
            //
            // 为什么自绘：Slider 的默认轨道高度/圆角/滑块是 Material 的通用形状，
            // 与播放页浮层（大圆角按钮 + 玻璃胶囊 + 黑遮罩白前景）不是一套语汇；
            // 且它无法把「已播 / 未播 / 空降标记 / 拖动时间气泡」画在同一层里。
            PlayerProgressBar(
                value = sliderVal,
                enabled = sliderOn,
                playing = playing,
                marks = marks,
                durationMs = durationMs,
                onValueChange = onSeekChange,
                onValueChangeFinished = onSeekDone,
                modifier = Modifier
                    .fillMaxWidth()
                    // 细线语言的呼吸感来自左右留白：滑块在两端不贴控制条边缘
                    .padding(horizontal = 10.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CtrlBtn(
                    text = playText,
                    on = true,
                    onClick = onTogglePlay,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = timeText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.widthIn(min = 64.dp)
                )
                CtrlBtn(
                    text = audioText,
                    on = audioOn,
                    onClick = onAudioClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = qualityText,
                    on = qualityOn,
                    onClick = onQualityClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = speedText,
                    on = true,
                    onClick = onSpeedClick,
                    modifier = Modifier.weight(1f)
                )
                CtrlBtn(
                    text = fullText,
                    on = true,
                    onClick = onFullClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 播放页进度条（自绘）。由下到上的图层：
 * 1) 整条轨道玻璃（blur 采样实时视频画面）
 * 2) 轨道 Canvas：未播轨（半透明，让模糊透出）/ 已播轨（实心白）/ 空降标记 / 停用态滑块
 * 3) 滑块玻璃（圆形模糊 + 深色底盘）
 * 4) 滑块白环
 * 5) 拖动时间气泡
 *
 * 关键点：miuix `DrawBackdropNode.draw` 的顺序是
 * `drawContent()` → `drawBlurredBackdrop()` → `onDrawSurface()`，即**模糊盖在
 * 本节点内容之上**。因此玻璃必须放在轨道**下面**（先画玻璃、再画半透明轨道），
 * 让轨道的半透明像素把模糊透出来；反过来放会被轨道整块盖死，等于没加模糊。
 *
 * 交互用 `pointerInput` + `detectHorizontalDragGestures` 自己实现，而不是包 Slider：
 * 需要在按下/拖动期间额外显示时间气泡，Slider 的槽位无法在不重复实现手势的情况下
 * 拿到「正在拖动」这一状态。
 *
 * @param value 0f..1f 播放进度
 * @param enabled false 时轨道与滑块降级为停用配色，且不响应手势
 * @param marks 空降助手片段标记（比例区间 + 颜色）
 * @param durationMs 用于拖动气泡的时间换算；<= 0 时不显示气泡
 */
@Composable
private fun PlayerProgressBar(
    value: Float,
    enabled: Boolean,
    /** 是否正在播放：只用于进度平滑外推（暂停/缓冲吸附真实值）。 */
    playing: Boolean,
    marks: List<SbMark>,
    durationMs: Long,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeValue = value.coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    val trackPx = with(LocalDensity.current) { PlayerSliderTrackHeight.toPx() }
    val thumbPx = with(LocalDensity.current) { PlayerSliderThumbSize.toPx() }
    // 气泡定位要用容器宽度，而 offset{} 的 receiver 是 LayoutCoordinates、不是 BoxScope
    // ——里面的 `size` 是坐标对象。因此在布局回调里单独记一份宽度。
    var barWidthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            // 触摸区比视觉轨道高得多（细轨只有 6dp，直接用轨道高度做命中区基本点不中）
            .height(32.dp)
            .onSizeChanged { barWidthPx = it.width }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        onValueChange((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        dragging = false
                        onValueChangeFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        onValueChangeFinished()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onValueChange((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // ── 平滑进度（B7）：播放中每帧推进，而不是跟着 500ms 轮询一跳一跳 ────────
        // 实现要点（与 FrameBudgetLintTest 的口径一致）：动画值**只在绘制/布局期读**
        // —— TrackGeometry.filledRight 是 lambda，调用点都在 Canvas draw 块与 offset{}
        // 块里，所以每帧推进只重绘这一小块轨道，不触发重组、也不违反"组合期读动画值"红线。
        // 目标值 = 最近一次真实进度 + 一个轮询窗口的预估增量；下一次真实值到达时从
        // **当前视觉值**重新起一段线性动画，视觉上连续。暂停/缓冲/拖动或跳变过大时
        // 直接吸附真实值（此时外推没有意义，还会掩盖 seek）。
        val smoothFraction = remember { Animatable(safeValue) }
        LaunchedEffect(safeValue, playing, dragging, durationMs) {
            val snap = dragging || !playing || durationMs <= 0L ||
                abs(safeValue - smoothFraction.value) > SMOOTH_SNAP_THRESHOLD
            if (snap) {
                smoothFraction.snapTo(safeValue)
            } else {
                smoothFraction.animateTo(
                    targetValue = (safeValue + SMOOTH_WINDOW_MS / durationMs.toFloat())
                        .coerceIn(0f, 1f),
                    animationSpec = tween(
                        durationMillis = SMOOTH_WINDOW_MS.toInt(),
                        easing = LinearEasing
                    )
                )
            }
        }
        val trackGeometry = remember(barWidthPx, thumbPx, smoothFraction) {
            val trackLeft = thumbPx / 2f
            val trackWidth = (barWidthPx - thumbPx).coerceAtLeast(1f)
            TrackGeometry(
                left = trackLeft,
                width = trackWidth,
                // 已播右端做成 lambda：调用处在绘制/布局期求值（见上方平滑进度说明）
                filledRight = { trackLeft + trackWidth * smoothFraction.value }
            )
        }
        val trackShape = PlayerTrackPillShape

        // 1) 整条轨道玻璃
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(trackGeometry.left.roundToInt(), 0) }
                .width(with(LocalDensity.current) { trackGeometry.width.toDp() })
                .height(PlayerSliderTrackHeight)
                .playerControlGlass(
                    shape = trackShape,
                    scrim = Color.Transparent,
                    shapeHeightPx = trackPx
                )
        )

        // 2) 轨道内容
        Canvas(modifier = Modifier.fillMaxWidth().height(PlayerSliderThumbSize)) {
            val cy = size.height / 2f
            val thumbRadius = thumbPx / 2f
            val trackRadius = CornerRadius(trackPx / 2f)
            val trackTop = cy - trackPx / 2f

            // 未播轨道：半透明白，模糊从下面透出来
            drawRoundRect(
                color = if (enabled) PlayerSliderInactiveTrack else PlayerSliderDisabledTrack,
                topLeft = Offset(trackGeometry.left, trackTop),
                size = Size(trackGeometry.width, trackPx),
                cornerRadius = trackRadius
            )
            // 已播轨道：实心白，进度一眼可读
            if (safeValue > 0f) {
                drawRoundRect(
                    color = if (enabled) PlayerSliderActiveTrack else PlayerPillBorder,
                    topLeft = Offset(trackGeometry.left, trackTop),
                    size = Size(
                        (trackGeometry.filledRight() - trackGeometry.left).coerceAtLeast(trackPx),
                        trackPx
                    ),
                    cornerRadius = trackRadius
                )
            }
            // 空降助手标记：比轨道略高
            if (marks.isNotEmpty()) {
                val markerHeight = trackPx + 3.dp.toPx()
                marks.forEach { mark ->
                    val left = trackGeometry.left + trackGeometry.width * mark.start
                    val right = (trackGeometry.left + trackGeometry.width * mark.end)
                        .coerceAtLeast(left + 2.dp.toPx())
                    drawRoundRect(
                        color = mark.color,
                        topLeft = Offset(left, cy - markerHeight / 2f),
                        size = Size(right - left, markerHeight),
                        cornerRadius = CornerRadius(markerHeight / 2f)
                    )
                }
            }
            // 停用态滑块（启用态走玻璃层 + 白环层）
            if (!enabled) {
                drawCircle(
                    color = PlayerSliderDisabledThumb,
                    radius = thumbRadius - 1.5.dp.toPx(),
                    center = Offset(trackGeometry.filledRight(), cy)
                )
            }
        }

        // 3) 滑块玻璃：圆形模糊 + 深色底盘
        if (enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        IntOffset(
                            x = (trackGeometry.filledRight() - thumbPx / 2f).roundToInt(),
                            y = 0
                        )
                    }
                    .size(PlayerSliderThumbSize)
                    .playerControlGlass(
                        shape = CircleShape,
                        scrim = PlayerSliderThumbScrim,
                        shapeHeightPx = thumbPx
                    )
            )
        }
        // 4) 滑块白环
        if (enabled) {
            Canvas(modifier = Modifier.fillMaxWidth().height(PlayerSliderThumbSize)) {
                drawCircle(
                    color = Color.White,
                    radius = thumbPx / 2f - 1.5.dp.toPx(),
                    center = Offset(trackGeometry.filledRight(), size.height / 2f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // 5) 拖动时间气泡：跟随滑块、悬在轨道上方
        if (dragging && durationMs > 0L && barWidthPx > 0) {
            val bubbleText = formatPlaybackTime((durationMs * safeValue).toLong(), durationMs)
            val bubbleWidthPx = with(LocalDensity.current) { 52.dp.toPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val centerX = thumbPx / 2f + (barWidthPx - thumbPx) * safeValue
                        IntOffset(
                            x = (centerX - bubbleWidthPx / 2f)
                                .coerceIn(0f, (barWidthPx - bubbleWidthPx).coerceAtLeast(0f))
                                .roundToInt(),
                            y = 0
                        )
                    }
                    .clip(PlayerControlShape)
                    .background(PlayerSliderPreviewScrim)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = bubbleText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CtrlBtn(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 停用态前景用 0.72 白而非 0.5：停用也要在亮画面上保持可读
    val bg = if (on) PlayerCtrlButtonOn else PlayerCtrlButtonOff
    val fg = if (on) {
        Color.White
    } else {
        PlayerCtrlButtonTextOff
    }
    // 全圆端：高度 34dp 配 17dp 圆角。此前是 15dp 裸字面量，与高度差 2dp，
    // 两端留下一小段直边，读作"圆角矩形"而非现代胶囊。
    val shape = PlayerControlShape

    Box(
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(shape)
            .playerControlGlass(shape = shape, scrim = bg, shapeHeightPx = with(LocalDensity.current) { 34.dp.toPx() })
            .clickable(enabled = on, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 播放器顶栏悬浮胶囊：统一「弹幕开关 / 缩放还原」等轻操作的视觉语言。
 * 半透明黑底 + 白字（[dimmed] 时降低字重）+ 细描边；[showDot] 用小白点表达开启态。
 */

@Composable
private fun PlayerPillChip(
    text: String,
    showDot: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = PlayerPillShape
    Box(
        modifier = modifier
            .clip(pillShape)
            .playerControlGlass(
                shape = pillShape,
                scrim = PlayerPillScrim,
                shapeHeightPx = with(LocalDensity.current) { 32.dp.toPx() }
            )
            .border(1.dp, PlayerPillBorder, pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (dimmed) PlayerPillTextDim else Color.White,
                maxLines = 1
            )
        }
    }
}

private fun readPlayerTopMetaText(
    context: android.content.Context,
    timeFmt: java.text.DateFormat
): String {
    val time = timeFmt.format(System.currentTimeMillis())
    val battery = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }
    return if (battery != null) "$time  ${battery}%" else time
}

/** 空降片段在标记条上的位置与颜色。 */

private data class SbMark(
    val start: Float,
    val end: Float,
    val color: Color
)

/**
 * 空降助手浮层：手动跳过按钮条与「跳到精彩时刻」。
 */

@Composable
private fun SponsorBlockOverlay(
    state: SponsorBlockUiState,
    showCtrl: Boolean,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onJumpPoi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val manual = state.manualSkip
    val poi = state.poiJump
    if (manual == null && poi == null) return
    Column(
        modifier = modifier
            .padding(end = 12.dp, bottom = if (showCtrl) 88.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (manual != null) {
            Row(
                modifier = Modifier
                    .background(
                        color = PlayerToastScrim,
                        shape = PlayerToastShape
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "跳过${manual.category.shortTitle}片段",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable(onClick = onSkip)
                        .padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp)
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭提示",
                    tint = PlayerSecondaryText,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onDismiss)
                        .padding(5.dp)
                )
            }
        }
        if (poi != null) {
            Text(
                text = "跳到精彩时刻",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(
                        color = PlayerToastScrim,
                        shape = PlayerToastShape
                    )
                    .clickable(onClick = onJumpPoi)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}
