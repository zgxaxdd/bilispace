package com.naaammme.bbspace.feature.video.player

import android.media.AudioManager
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
// 2026-10-18 第十九轮：档位组从"连体胶囊 + 值文本"改为"独立圆钮 + 图标"，
// 需要这三颗图标。它们在 `material-icons-extended`（模块已依赖）里，
// 且**只以扩展属性形式存在** ⇒ 必须逐个 import，直接写 `Icons.Rounded.Speed`
// 会报 "Unresolved reference"（本轮已实测确认）。
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Speed
// 2026-10-18 第 33 轮：顶栏文字胶囊 → 圆钮，开/关态改用图标变体表达。
// 同样在 `material-icons-extended` 里且只以扩展属性存在 ⇒ 必须逐个 import。
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawWithContent
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
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
import com.naaammme.bbspace.core.model.VideoStaff
import com.naaammme.bbspace.core.common.ShareUtils
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.core.designsystem.component.BiliSheetGrabber
import com.naaammme.bbspace.core.designsystem.theme.biliSheetShape
import com.naaammme.bbspace.core.designsystem.component.BiliParticleLoader
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.lightTabularLabelSmall
import com.naaammme.bbspace.core.designsystem.theme.softShadowLabelSmall
import com.naaammme.bbspace.core.designsystem.glass.GlassConfig
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.designsystem.glass.GlassSurfaceType
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassConfig
import com.naaammme.bbspace.core.designsystem.glass.LocalGlassGuard
import com.naaammme.bbspace.core.designsystem.glass.MotionTier
import com.naaammme.bbspace.core.designsystem.glass.resolveGlassBudget
import com.naaammme.bbspace.core.designsystem.glass.liquid.lens
import com.naaammme.bbspace.core.designsystem.glass.liquid.vibrancy
import com.naaammme.bbspace.feature.video.R
import com.naaammme.bbspace.feature.video.SponsorBlockUiState
import com.naaammme.bbspace.feature.video.VideoViewModel
import com.naaammme.bbspace.feature.video.formatPlaybackTime
import com.naaammme.bbspace.feature.video.formatSpeed
import com.naaammme.bbspace.feature.video.getAudioName
import com.naaammme.bbspace.feature.video.getQualityName
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.swipe.SwipeHeartBurstPolicy
import com.naaammme.bbspace.core.swipe.SwipeLoadPhasePolicy
import com.naaammme.bbspace.core.swipe.SwipeMarqueePolicy
import com.naaammme.bbspace.core.swipe.SwipeHandoffTiming
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import androidx.compose.runtime.key




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
// 浮层（顶栏/控制条/控件底盘/手势气泡）直接叠在视频画面上，与主题色解耦：
// 统一「黑底 + 白前景」语言，透明度按可读性校准，保证亮画面上白字仍清晰，
// 深/浅色主题下表现一致。改动时请维持整套 token 同步，勿在单处硬编码。
//
// ## 2026-10-18 第 33 轮：**整层遮罩全部删除**（用户原话：「视频播放页还是有一层遮罩
// 请全部去除，且视频播放页的其他控件风格没有统一」）
// 此前有三条 `Brush.verticalGradient` 整层压黑：顶部 0.66→透明、控制层 0.26→0.42 平台、
// 会话底部 0.58。它们在本轮**全部删除且不换名保留**，画面 100% 干净。
// 可读性改由两件事承担：
// 1. **每个控件自带一块紧贴自身的底盘** —— [PlayerChipScrim]（见其 KDoc 的 AA 反解）；
// 2. **信息文字投影** —— [PlayerInfoTextShadow]（标题/UP 名/话题/时间文本这类
//    只读信息不进圆钮，靠投影在最坏情况纯白画面上保持可读）。
// 进度轨道的对比度由轨道自己的衬底承担（见 [PlayerTrackBedScrim]）。
// 守卫：`PlayerOverlayScrimContrastTest`（反向断言三条渐变画笔不存在 + 正向断言 chip 达标）。

/**
 * 播放器**每一个控件**共用的底盘（顶栏与底部控制行**同一个常量**）。
 *
 * ## 为什么是 0.58（不是观感值，是过线）
 * 整层渐变删除后，白图标/白字的最坏情况是**纯白画面**。要让白 1.00 达到 WCAG AA
 * 4.5:1，黑遮罩 α 的反解值约 **0.5347**（`ContrastMath.minScrimAlphaForWhiteText(4.5)`）；
 * 取 **0.58** 留余量 ⇒ 白 1.00 = **5.32:1**、白 0.92 = **4.80:1**。
 *
 * ## 一处定义、两处 chrome 共用
 * 顶栏（返回/首页/弹幕/横屏/更多/还原）与底部控制行（播放/音轨/画质/倍速/全屏）
 * **全部**收成 `PlayerIconButton(chip = PlayerChipScrim, chipShape = CircleShape)`：
 * 直径统一取 [PlayerCtrlRowHeight]（34dp）、同一 chip 色、纯图标，文字只进
 * `contentDescription`。--- 这是用户说的「控件风格没有统一」的修法：
 * 此前同屏混着裸 `IconButton`、`PlayerPillChip` 文字胶囊、白 18%/白 20% 两种圆底。
 */
private val PlayerChipScrim = Color.Black.copy(alpha = 0.58f)

/**
 * 只读信息文字的**投影**（整层遮罩删除后可读性的承担者之一）。
 *
 * 与 [PlayerChipScrim] 分工：控件有自己的底盘（AA 4.5 可证明）；而标题/UP 名/话题/
 * 时间文本属于**信息**，画一块底会重新引入"遮罩"观感 —— 按用户口径改为
 * 文字投影（黑 55% / blur 6），只压字形周围几 dp，不压画面。
 */
private val PlayerInfoTextShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 6f)

/**
 * 给任意排版样式挂上 [PlayerInfoTextShadow]。
 *
 * 以**基样式**为键 `remember`：同一基样式只算一次（`ListAllocationStructureTest` 的
 * 内联 `typography.x.copy(` 棘轮是 0，不能在调用点现拼）；同时与
 * `TextStyleVariants` 的具名出口同一写法。
 */
@Composable
private fun playerInfoStyle(base: TextStyle): TextStyle =
    remember(base) { base.copy(shadow = PlayerInfoTextShadow) }

/**
 * 播放器**常态文字**（白 0.92）。
 *
 * 整层遮罩删除后（见文件顶部规范），白 0.92 的可读性由**文字投影**
 * （[PlayerInfoTextShadow]）与**控件底盘**（[PlayerChipScrim]）承担，
 * 不再由任何"整条横幅压黑"供给。
 */
private val PlayerSecondaryText = Color.White.copy(alpha = 0.92f)

/**
 * 进度条**停用态**的「已播」轨色（白 0.16）。
 *
 * 2026-10-18 第 33 轮改名：原名 `PlayerPillBorder`（胶囊描边时代的遗留名），
 * 但它一直只用于这一处（停用态已播轨）—— 胶囊语言整体删除后，旧名会把人引向
 * 已经不存在的形状，故改名。
 */
private val PlayerSliderDisabledActiveTrack = Color.White.copy(alpha = 0.16f)

// —— 以下为本轮补全的 token ——
// 此前这些值以裸字面量散落在各处（方案 P1-4 指出"浮层只做了半程 token 化"）。
// 补全的意义：透明度的可读性校准值集中成组，后续统一调整不必再全局搜替换。
// 注意全部维持「黑遮罩 + 白前景」语言，**不要**改成主题色（本文件顶部约定）。

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

/**
 * 手势气泡 / 提示胶囊的圆角 = 控件档 17dp。
 *
 * 2026-10-18 第 33 轮：`PlayerPillShape`（无用于文字胶囊）**已随 `PlayerPillChip`
 * 一起删除** —— 播放页不再有"文字胶囊 vs 圆钮"两套形状语言；剩下的非圆控件只有
 * 信息气泡（[PlayerToastShape]）与拖动时间气泡（[PlayerControlShape]）。
 */
private val PlayerToastShape = RoundedCornerShape(BiliRadius.playerControl)

/** 进度条**已播**轨道：实心白。此前与未播同为 `White 0.24`，进度条等于看不出进度。 */
private val PlayerSliderActiveTrack = Color.White

/**
 * 进度条**未播**轨道：白 0.72。
 *
 * ## 2026-10-18 第 33 轮（整层遮罩全部删除）后它靠什么达标
 * 遮罩删除前，轨道坐在底部渐变的 α≈0.74~0.77 上；现在**没有渐变**了，
 * 最坏情况是纯白画面。轨道的可读性因此**完全**由 [PlayerTrackBedScrim]（紧贴轨道
 * 上下各 2dp 的暗槽）承担：合成 α = 0.52 ⇒ 白 0.72 = **3.05:1**（非文本 3:1 ✅）。
 * 该值由 `PlayerOverlayScrimContrastTest` 从源码解析后断言，改任何一个都必须同批跑。
 */
private val PlayerSliderInactiveTrack = Color.White.copy(alpha = 0.72f)

/**
 * 进度轨道的**衬底**（黑 0.52，2026-10-18 第 33 轮由 0.45 调深）。
 *
 * ## 为什么调深
 * 本轮把最后三条整层渐变**全部删除**（0.42 的平台值也不存在了），轨道背后的
 * 最坏情况回到**纯白画面**。轨道衬底因此成为唯一供底者，必须自己把 α 顶过反解线：
 * - 黑 **0.45** ⇒ 白 0.72 轨道只有 **2.51:1** ❌（`PlayerOverlayScrimContrastTest` 实测）
 * - 黑 **0.52** ⇒ **3.05:1** ✅（非文本 3:1，余量 1.7%）
 *
 * 尺寸只有「轨道宽 × (2dp 轨道 + 上下各 2dp)」，**不是**一整条遮罩 ——
 * 这正是与旧方案（118dp 高的黑渐变）最本质的区别。
 */
private val PlayerTrackBedScrim = Color.Black.copy(alpha = 0.52f)

/** 进度条停用态轨道。 */
private val PlayerSliderDisabledTrack = Color.White.copy(alpha = 0.1f)

/** 进度条停用态滑块。 */
private val PlayerSliderDisabledThumb = Color.White.copy(alpha = 0.24f)

/** 拖动时跟随滑块的时间预览气泡底色。 */
private val PlayerSliderPreviewScrim = Color.Black.copy(alpha = 0.72f)

// —— 进度条尺寸规范 ——
// 轨道 2dp、滑块 12dp（2026-10-16 UI 现代化）。iOS-2（同日）把进度行**收回控制层**：
// 它随控制层显隐（用户 2026-10-02 口径：不要贴底常驻的细进度条）。
// ⚠️ 2026-10-18 第 33 轮：控制层**不再有任何整层渐变**，轨道的非文本对比度
// 全部由 [PlayerTrackBedScrim]（紧贴轨道的 6dp 暗槽）承担 —— 见该常量的 KDoc。
private val PlayerSliderTrackHeight = 2.dp
private val PlayerSliderThumbSize = BiliSpacing.md

/**
 * 拖动时滑块的放大倍数（上游 `PlayerControlGlassThumb` 的 pressScale = 1.18）。
 *
 * 16dp 的滑块在 6dp 细轨上很小，手指按住它时**视觉上没有任何"我抓住了"的反馈**
 * （轨道本身没有位移，只有白环在动）。放大 1.18 = 约 19dp，够看得出来但不会
 * 挤到相邻的时间气泡与 SponsorBlock 标记。
 *
 * 刻意**不**放大命中区：手势由外层 32dp 高的 Box 承接，这个值只影响绘制。
 */
private const val ThumbDragScale = 1.18f

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
    // AP-25：接入帧守卫与预算 —— 此前该链既不读 LocalGlassGuard 也不走
    // resolveGlassBudget，是**出厂默认配置（MATERIAL + FROSTED、playerControlsBlur
    // 默认 true）下唯一仍在跑的玻璃链**：守卫降级时 Dock/顶栏都停了，播放控件的
    // 逐帧背板重录照跑。语义与 GlassEffect 的收口一致：降级档（maxBlurLevel<=0）
    // 早退为纯底色；滑动/转场只停实时重录、不单独降模糊等级（Pulsing 教训）。
    // 播放页没有滚动语义，isScrolling/isTransitionRunning 恒 false。
    val guard = LocalGlassGuard.current
    val budget = remember(guard.effectiveMotionTier, guard.forceLowBlurBudget) {
        resolveGlassBudget(
            surfaceType = GlassSurfaceType.OVERLAY,
            motionTier = guard.effectiveMotionTier,
            isScrolling = false,
            isTransitionRunning = false,
            forceLowBudget = guard.forceLowBlurBudget
        )
    }
    if (budget.maxBlurLevel <= 0) {
        return this.background(scrim, shape)
    }
    // `shapeHeightPx` 是 **px**（见参数注释），这里要的是"短边 **dp** 的 1/3.5"：
    // 原来除以字面量 3.5 = **把本机密度写死**（本机 density 恰好 3.5，所以一直没暴露）——
    // 别的设备上折射量会整体乘上 density/3.5（density 2.75 时 6dp 的进度条轨道会被折射吞掉，
    // density 4 时又几乎看不见）。按真实密度换算（2026-10-07 审查）。
    val refractionDp = with(LocalDensity.current) {
        (shapeHeightPx.toDp().value / 3.5f)
    }.coerceIn(2f, 10f)
    // 高光边的**尺寸档**也要跟着控件尺寸走（与 [refractionDp] 同一思路）。
    // miuix 的三档只差 BloomStroke 的描边宽度（已反编译核实其 `<clinit>`）：
    // Big = 3.5dp、Middle = 2.8dp、Small = 2.6dp，其余光源参数一并按档缩放。
    // 全局 Dock（64dp 高）用 Big 合适；而播放控件最小是 6dp 的进度条轨道 ——
    // 3.5dp 的 bloom 描边套在 6dp 高的轨道上会**糊满整条轨道**（轨道本身只剩 2.5dp
    // 可读芯线），这正是"液态档下进度条读不清"的来源。
    // 判据用 dp 而不是 px：控件尺寸的设计单位是 dp，跨密度才稳定。
    val highlightShapeHeightDp = with(LocalDensity.current) { shapeHeightPx.toDp().value }
    val highlight = if (!isLiquid) {
        null
    } else {
        when {
            highlightShapeHeightDp <= 20f -> Highlight.GlassStrokeSmallLight
            highlightShapeHeightDp <= 40f -> Highlight.GlassStrokeMiddleLight
            else -> Highlight.GlassStrokeBigLight
        }
    }
    // **必须 remember 住整条链**：miuix 的 `DrawBackdropElement.equals` 对 shape/effects/
    // highlight/onDraw* 做**引用比较**（已反编译 miuix-blur 0.9.4-rc01 核实），而
    // `update(node)` 末尾**无条件 `invalidateDrawCache()`** —— 每次重组新建 lambda 就会让
    // 该玻璃节点的 blur/sharp/crossfade 缓存整体作废重建。播放进度 500ms 轮询让控制条每秒
    // 重组 2 次 = 每秒重算 2 次玻璃；卡片取色 350ms 渐变期更糟（每帧重算）。
    // `tick` 是 `{ videoGlassTick }` 形态的 provider（读的是状态委托），被 remember 捕获
    // 旧实例也永远读到最新值 —— 这正是它被设计成 `() -> Int` 的原因之一，可安全进键。
    val glassChain = remember(backdrop, shape, scrim, refractionDp, highlight, isLiquid, strength, tick) {
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
                    // 液态才描 Fresnel 高光边：磨砂档描边会读作"硬边框"，不是雾面该有的样子。
                    // 档位按控件短边自适应（见上面 `highlight` 的推导）。
                    highlight
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

/**
 * 播放页（详情竖排 / 全屏 / 宽屏展开 / 刷视频 Pager 共用）。
 *
 * 参数表按四处调用点重建（2026-10-07 误删后恢复）。
 * 可选参数都带默认值：各宿主只传自己关心的那几个。
 */
@Suppress("UnstableApiUsage")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoPlayerPane(
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel,
    videoTitle: String? = null,
    isFull: Boolean,
    onToggleFull: () -> Unit,
    onBackClick: () -> Unit,
    onGoHome: () -> Unit,
    /** 翻页会话激活：垂直拖动让位给 pager。 */
    swipePagingActive: Boolean = false,
    /** 等首帧加载层（深色底 + 粒子），首帧到达后淡出。 */
    swipeWaitingFirstFrame: Boolean = false,
    /** 播放失败：加载层留着并切成「错误 + 重试」。 */
    swipeFailed: Boolean = false,
    /** 失败文案（按类型给「人话」）。 */
    swipeErrorText: String? = null,
    onRetrySwipe: () -> Unit = {},
    /** 等首帧加载层的封面（与封面页同一个 URL）。 */
    swipeCoverUrl: String? = null,
    /** 侧栏显隐上报（互动栏让位）。 */
    onPlaybackSheetVisibilityChange: ((Boolean) -> Unit)? = null,
    /** 只在允许的宿主里出现的「手动横屏」出口。 */
    onToggleLandscape: (() -> Unit)? = null,
    landscapeForced: Boolean = false,
    /**
     * 会话级控制层归属（第 35 轮 P0-1「控制层唯一化」）。
     *
     * `null` = **本 pane 自持** `showCtrl`（详情页 / 展开态等非会话宿主，行为与重构前逐帧一致）；
     * 非 `null` = 由会话层（`PortraitSwipePager` 的 `SwipeChromeOwner`）**唯一**决定，
     * pane 只读 + 上报请求、绝不自己改。这是"屏幕上最多一套顶栏、一个 ⋮"的落地方式：
     * 真机 bug 的根因就是这里与 `SwipeSessionChrome` 各持一个显隐布尔、互不感知
     * （证据 `docs/evidence-r35/27-tap-once.png`）。
     */
    sessionChromeVisible: Boolean? = null,
    /** 会话层要显/隐控制条时上报（[sessionChromeVisible] 非空时必填，见上）。 */
    onSessionChromeRequest: ((Boolean) -> Unit)? = null,
    /** 是否**允许**省电模式（SurfaceView）在本宿主生效：页面级 graphicsLayer 变换的宿主传 false。 */
    allowSurfaceView: Boolean = true,
) {

    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state by viewModel.videoState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle(initialValue = PlayerSettingsState())
    // 「省电模式」：视频节点换 SurfaceView（设置项 + 外层布局是否允许）。
    //
    // **关键约束（2026-10-07 真机实测）**：SurfaceView 是独立图层，只要祖先里有
    // `Modifier.clip(圆角)` 或非恒等的 `graphicsLayer{}`，surface 就会被挖空 ⇒ 画面全黑
    // （第一版就栽在这里：只换 surface_type，结果内嵌黑、全屏正常 —— 差别正是圆角裁剪层）。
    // 因此本模式生效时：
    // ① 播放页**不再用 Compose clip**，圆角改由 PlayerView 的 outline 裁剪（系统侧裁 surface）；
    // ② 黑底改用 `background(color, shape)`（只按形状绘制、不建裁剪层）；
    // ③ 捏合缩放改写成 **View 级**变换（Compose graphicsLayer 会建图层）；
    // ④ 控件模糊按关处理（画面不在应用图层里，玻璃采不到帧）。
    val surfacePowersave by viewModel.playerSurfaceView.collectAsStateWithLifecycle()
    // 设置迟迟读不出来（DataStore 异常等）时的兜底：最多等 800ms ——
    // 绝不允许播放页因为一个设置读不到而永久黑屏。
    //
    // **兜底值必须与设置的出厂默认同向**（2026-10-11 审查）：`AppSettings.playerSurfaceView`
    // 是 `?: true`（省电模式默认开），而这里原先兜底取 `false` —— 方向相反。DataStore 首次
    // 读取一旦超过 800ms，就会走"先用 TextureView 版建视图并绑定 → 真值 `true` 到达 →
    // `key(useSurfaceView)` 重建为 SurfaceView 版并重绑"，即**恰好是下面注释说要避免的
    // "建两次视图"**：解码器输出重挂 + 弹幕 view 重挂（DanmakuOverlay 的 attach 链路），
    // 用户看到一次黑帧/闪动。取 `true` 后正常路径零重建；万一真值是 `false`，
    // 也只是把那次重建挪到"用户明确关掉了省电模式"的情形上。
    var surfaceSettingTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(surfacePowersave) {
        if (surfacePowersave == null) {
            kotlinx.coroutines.delay(800)
            surfaceSettingTimedOut = true
        }
    }
    val surfaceSetting = surfacePowersave ?: if (surfaceSettingTimedOut) true else null
    if (surfaceSetting == null) {
        // 设置还没读出来（首帧）：**先不建 PlayerView**。
        // 若此刻按 false 兜底建一个 TextureView 版并绑定播放器、等真值到达再建 SurfaceView 版，
        // 真机上**第二个视图根本不会被挂上屏**（`AndroidView` 的 holder 只认第一次创建的视图），
        // 而 `PlayerViewTargetBinder` 已把播放器改绑到这个没上屏的新视图 ⇒ **首次进入黑屏**
        // （2026-10-07 实测；`PlayerSurface` 诊断日志：useSurfaceView 先 false 后 true，
        // 且 SurfaceHolder 从未 created）。DataStore 首次读取只有几毫秒，这里等价于一帧黑底。
        Box(modifier = modifier.background(Color.Black))
        return
    }
    val useSurfaceView = surfaceSetting && allowSurfaceView
    var activeDialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var showPlaybackSheet by remember { mutableStateOf(false) }
    // ── 控制层显隐的**单一入口**（第 35 轮 P0-1）──────────────────────────────
    // 会话受控时 `showCtrl` 是 `sessionChromeVisible` 的只读投影，写操作上报给会话状态机；
    // 非会话时退回原来的本地 state（详情页/展开态行为不变）。
    var localShowCtrl by remember { mutableStateOf(false) }
    val showCtrl = sessionChromeVisible ?: localShowCtrl
    val setShowCtrl: (Boolean) -> Unit = { wanted ->
        if (sessionChromeVisible != null) {
            onSessionChromeRequest?.invoke(wanted)
        } else {
            localShowCtrl = wanted
        }
    }
    // 底部控制栏的实测高度：会话底部信息层在控制条出现时要**整体让位**上移
    // （否则控制条的进度滑条顶进标题/玻璃圆盘，见 SwipeSessionBottomZone 注释）。
    // 布局期一次性写入，位移在 graphicsLayer 里读 —— 零重组。
    var ctrlBarHeightPx by remember { mutableIntStateOf(0) }
    val zoomState = remember { PlayerZoomState() }
    val videoResizeMode = rememberSaveable { mutableStateOf(PlayerVideoResizeMode.Fit) }
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
    val playerView = remember(context, useSurfaceView) {
        // 默认 TextureView：Compose 圆角裁剪才能作用到视频画面。
        // 「省电模式」打开时换 SurfaceView：画面交给系统显示管线合成，省掉每帧一次应用侧合成
        // （真机实测 RenderThread 19.7%→2.8%、整机 CPU 105.5%→75.2%）。
        //
        // ⚠️ SurfaceView 是**独立图层**，不能被放进带裁剪/变换的 Compose 图层：
        // 第一版只换了 surface_type，结果**内嵌播放全黑、全屏正常**（证据截图
        // `D:\111j\c\r23\a3-surface.png`）—— 差别就在内嵌套了 16dp 圆角裁剪（`clip`）而全屏是
        // RectangleShape（无裁剪层）。所以本模式必须同时满足：①播放页内部不套圆角裁剪
        // （不产生裁剪层）；②外层带 graphicsLayer/clip 的调用点传 `allowSurfaceView = false`
        // —— 全仓仅一处（刷视频 Pager），VideoScreen 守卫要求 0 处；③控件模糊按关处理。
        val layoutId = if (useSurfaceView) {
            R.layout.view_bili_player_surface
        } else {
            R.layout.view_bili_player
        }
        (LayoutInflater.from(context).inflate(layoutId, null, false) as PlayerView)
            .apply {
                useController = false
                setEnableComposeSurfaceSyncWorkaround(true)
                setKeepContentOnPlayerReset(true)
            }
    }
    var lastWarmAspect by remember(playerView) { mutableStateOf<Float?>(null) }

    // **键必须带上 `playerView`**（2026-10-07 审查）：视图会因省电模式设置翻转
    // （或 800ms 兜底后真值到达）换成另一份 PlayerView，而 `playWhenReady` 多数时候没变 ——
    // 只以它为键时新视图拿不到 `keepScreenOn`，播放中会按系统超时息屏。
    LaunchedEffect(playerView, state.playWhenReady) {
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
        // **播放窗口 = 直角（用户定稿 2026-10-07，删繁就简）**：
        // 上下沿都不做圆角 ⇒ 不再需要任何形状/裁剪/outline 机制：
        //   · 非省电模式：不做 Compose clip（也省掉一个裁剪层）；
        //   · 省电模式：surface 就是矩形视图本身，不需要 outline 裁剪；
        //   · 黑底按矩形绘制，播放带用的也是 RectangleShape，两者同形。
        // 留一条 surface 类型日志：真机黑屏类问题（独立图层被挖空）只有它能一眼看出来。
        LaunchedEffect(playerView, useSurfaceView) {
            DiagnosticLog.i("PlayerSurface") {
                "pane surfaceType=" + (playerView.videoSurfaceView?.javaClass?.simpleName ?: "null")
            }
        }
        // 省电模式下缩放走 View 级变换 ⇒ 必须在组合期读（否则 AndroidView.update 不会重跑）。
        // 非省电模式不读（`if/else` 短路），避免缩放时整块面板逐帧重组。
        val zoomScale = if (useSurfaceView) zoomState.scale else 1f
        val zoomOffsetX = if (useSurfaceView) zoomState.offsetX else 0f
        val zoomOffsetY = if (useSurfaceView) zoomState.offsetY else 0f
        // 播放页专用背板：视频节点录进它，控制条玻璃采样它做真模糊。
        // 视频逐帧更新而录制节点不自动重绘——播放中用 withFrameNanos 驱动 tick 强制重录，
        // 但**按时间节流到约 30Hz**（R2，见 [VideoGlassTickPolicy]）：模糊是低频信号，
        // 满帧率的 GPU 重录在肉眼上换不来任何东西。
        //
        // **必须条件化**（2026-10-11 审查，与 `HomeVideoPage.kt` 的同款写法对齐）：
        // `rememberLayerBackdrop()` 内部会 `rememberGraphicsLayer()`，即**持有一个 RenderNode
        // 级离屏层**。省电模式（SurfaceView）下画面是独立 surface、录不进应用图层，玻璃也
        // 按关下发（见下面 `effectiveControlsBlur`）—— 此时这个离屏层没有任何消费者，
        // 却照样被创建并常驻。原先"注册本身免费"的口径在本仓已被 `HomeVideoPage.kt:711-716`
        // 推翻（那里就是为同一件事改成条件化的），这里补齐，与下面的"背板录制链整条摘掉"
        // 注释口径一致。
        val videoBackdrop = if (useSurfaceView) null else rememberLayerBackdrop()
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
        val glassGuard = LocalGlassGuard.current
        // 省电模式（SurfaceView）：画面不在应用图层里，玻璃采不到帧 ⇒ 直接按"模糊关闭"处理，
        // 否则逐帧重录白跑（泛白/纯色兜底本来就支持，见 LocalVideoControlsBlur 消费者）。
        val effectiveControlsBlur = controlsBlur && !useSurfaceView
        val videoGlassActive = showCtrl &&
            state.playWhenReady &&
            effectiveControlsBlur &&
            controlsMaterial != GlassMaterial.OFF &&
            // AP-25：守卫降级期间逐帧 tick 循环必须停 —— 消费端（playerControlGlass）
            // 已早退为纯底色，继续逐帧唤醒帧时钟是纯浪费（判据纪律：不进 jank 指标
            // 的空转同样是缺陷）。恢复档位时随 key 翻转重新起循环。
            glassGuard.effectiveMotionTier != MotionTier.Reduced &&
            !glassGuard.forceLowBlurBudget
        LaunchedEffect(videoGlassActive) {
            if (!videoGlassActive) return@LaunchedEffect
            // R2：帧时钟照旧逐帧回调，但只有跨过最小间隔才递增 tick ——
            // 不递增就没有失效，也就没有那一次 backdrop 的 GPU 重录。
            // 按**绝对时间**而不是"每 N 帧"节流：面板刷新率会变（60/90/120Hz），
            // 按帧数节流会在高刷机型上把成本又翻倍回去。
            var lastTickNanos = -1L
            while (true) {
                withFrameNanos { frameTimeNanos ->
                    if (VideoGlassTickPolicy.shouldRecord(frameTimeNanos, lastTickNanos)) {
                        lastTickNanos = frameTimeNanos
                        videoGlassTick++
                    }
                }
            }
        }
        // 侧栏显隐上报（会话互动栏让位用，见参数注释）。
        LaunchedEffect(showPlaybackSheet) {
            onPlaybackSheetVisibilityChange?.invoke(showPlaybackSheet)
        }
        // **离开组合时必须补报一次"关"**（2026-10-07 审查）：侧栏状态是本 pane 的局部
        // `remember`，pane 被销毁（会话内翻页换页、换宿主）时上面那个 effect 直接取消，
        // 永远不会上报 `false` ⇒ 父级 `playbackSheetVisible` 永久停在 true，
        // 互动栏与横屏悬浮按钮整场不再出现（只上报"开"没有"关"是状态泄漏）。
        val latestSheetReporter by rememberUpdatedState(onPlaybackSheetVisibilityChange)
        DisposableEffect(Unit) {
            onDispose { latestSheetReporter?.invoke(false) }
        }
        CompositionLocalProvider(
            LocalVideoBackdrop provides videoBackdrop,
            LocalVideoGlassTick provides { videoGlassTick },
            // 省电模式（SurfaceView）下按"模糊关"下发，见 effectiveControlsBlur
            LocalVideoControlsBlur provides effectiveControlsBlur,
            LocalVideoControlsMaterial provides controlsMaterial
        ) {
        Box(
            modifier = modifier
                // 播放窗口 = 直角（用户定稿 2026-10-07，删繁就简）：
                // 不需要任何 Compose clip / outline 机制，黑底就是矩形。
                // 省电模式（SurfaceView）怕的正是"祖先有裁剪/变换图层"（会把独立 surface 挖空 ⇒ 全黑），
                // 直角写法天然没有这一层，两个渲染路径同形。
                .background(Color.Black)
        ) {
            // 捏合缩放只作用于视频画面本体：graphicsLayer 挂在 AndroidView 上，
            // 播放器控件（顶栏/控制条/中央按钮）与弹幕层不跟随缩放；
            // Box 的 clip 在外层保证放大内容裁剪在播放视口内
            // **必须包 key(useSurfaceView)**：AndroidView 的 holder 只认第一次 factory 返回的视图，
            // 若 setting 变化（例如 DataStore 迟到、或用户在设置页改完回来），holder 里还是旧视图，
            // 而 PlayerViewTargetBinder 会把播放器改绑到"新造但没上屏"的视图 ⇒ 永久黑屏。
            // 包 key() 后：setting 一变，节点与视图一起重建，绑定目标必然已上屏。
            key(useSurfaceView) {
                AndroidView(
                    factory = { playerView },
                    update = { view ->
                        // **自愈补绑（2026-10-07 审查）**：绑定原本只在 `DisposableEffect` 里做一次，
                        // 而 binder 的契约是"一个 player 同时刻只有一个渲染目标"——
                        // 同屏出现过第二个 PlayerView 时（真机 dump 实测到两份），落败的那个视图会被
                        // `detachView` 摘成 `view.player == null`，而组合态随后不再变化 ⇒ **永久黑屏**
                        // （声音/进度正常）。binder 的 KDoc 本来就写明"AndroidView.update 每次重组都会调用"，
                        // 这里按契约补绑。
                        // 两个前置条件缺一不可：
                        // · `view.player == null`：已绑好就不碰（bind 幂等，但少一次判断更稳）；
                        // · 播放页处于 **RESUMED**：保证被压在后台的那个播放页不会把前台正在渲染的
                        //   目标抢走，也不破坏 ON_STOP 主动解绑（省电）的既有行为。
                        if (
                            player != null &&
                            view.player == null &&
                            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        ) {
                            PlayerViewTargetBinder.bind(view, player)
                        }
                        val content = view.findViewById<AspectRatioFrameLayout>(Media3UiR.id.exo_content_frame)
                        if (content != null) {
                            content.resizeMode = resizeMode
                            if (lastWarmAspect != videoAspect) {
                                content.setAspectRatio(videoAspect ?: 0f)
                                lastWarmAspect = videoAspect
                            }
                        }
                        if (useSurfaceView) {
                            // 捏合缩放写成 View 级变换：Compose graphicsLayer 会建图层 ⇒ surface 会被挖空
                            view.scaleX = zoomScale
                            view.scaleY = zoomScale
                            view.translationX = zoomOffsetX
                            view.translationY = zoomOffsetY
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (useSurfaceView) {
                                Modifier
                            } else {
                                Modifier.graphicsLayer {
                                    scaleX = zoomState.scale
                                    scaleY = zoomState.scale
                                    translationX = zoomState.offsetX
                                    translationY = zoomState.offsetY
                                }
                            }
                        )
                        .then(
                            if (useSurfaceView) {
                                // 省电模式：**背板录制链整条摘掉**。
                                // ① 画面是独立 surface，录不进应用图层，玻彩也采样不到（模糊已按关下发）；
                                // ② `layerBackdrop` 这条链会把节点录进 GPU 图层 —— 图层一旦盖到独立
                                //    surface 上就会把它挖空/留在坏状态（真机现象：首次进入黑屏、
                                //    全屏往返重建后才正常），所以省电模式下必须完全不挂它。
                                Modifier
                            } else {
                                Modifier
                                    // 读 tick 强制逐帧重录 + 把视频节点录进播放页专用背板
                                    .drawWithContent {
                                        videoGlassTick
                                        drawContent()
                                    }
                                    // 非省电模式分支：`videoBackdrop` 必然非空
                                    // （上面是 `if (useSurfaceView) null else rememberLayerBackdrop()`），
                                    // 走 `?.let` 只为让类型收窄、不在热路径加判断。
                                    .then(videoBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                            }
                        )
                )
            }

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
                onShowCtrlChange = setShowCtrl,
                onShowA = {
                    setShowCtrl(true)
                    activeDialog = PlayerDialog.Audio
                },
                onShowQ = {
                    setShowCtrl(true)
                    activeDialog = PlayerDialog.Quality
                },
                onShowSp = {
                    setShowCtrl(true)
                    activeDialog = PlayerDialog.Speed
                },
                onToggleFull = {
                    setShowCtrl(true)
                    onToggleFull()
                },
                swipePagingActive = swipePagingActive,
                landscapeForced = landscapeForced,
                onToggleLandscape = onToggleLandscape
            )

            // A5：控制层显隐动画（fadeIn + 4dp 轻位移 200ms Standard，退出 150ms fadeOut）
            //
            // ## 2026-10-18 第 33 轮：**整条顶部横幅遮罩删除**
            // 旧写法是本 `Row` 自己挂 `.background(PlayerTopScrimBrush)`（`Brush.verticalGradient`
            // 从黑 0.66 收到透明）—— 用户看到的那"一层遮罩"在顶部的来源之一。现在整层删除，
            // 顶栏没有任何背景；可读性由**每颗控件自己的圆底**（[PlayerChipScrim]）与
            // **信息文字的投影**（[PlayerInfoTextShadow]）承担。
            // 视觉形态：顶栏与底部控制行**共用同一个 chip 常量、同一个直径**
            // （[PlayerCtrlRowHeight] = 34dp），全部是 `PlayerIconButton` 圆钮 ——
            // 不再有裸 `IconButton` 与 `PlayerPillChip` 文字胶囊混排（用户说的"风格没统一"）。
            val ctrlAnim = LocalAnimations.current
            AnimatedVisibility(
                visible = showCtrl,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // P0-2（真机 `32-land-chrome.png`：标题与系统时间/电量叠字）：
                    // 全屏/横屏时状态栏是可见的（immersive 只在部分路径生效），
                    // 顶栏必须自己让出 `statusBars`，否则标题直接压在时钟上。
                    // 非全屏（内嵌播放带 / 展开态）**不加**：那两处的 inset 已由外层
                    // `VideoScreen` 的 `.padding(top = statusTop)` 统一承担，
                    // 再加一次会把播放带高度算错（2026-10-07 真机 dump 的教训）。
                    .then(if (isFull) Modifier.statusBarsPadding() else Modifier),
                enter = fadeIn(tween(durationMillis = ctrlAnim.medium, easing = ctrlAnim.standard)) +
                    slideInVertically(tween(durationMillis = ctrlAnim.medium, easing = ctrlAnim.standard)) { it / 16 },
                exit = fadeOut(tween(durationMillis = ctrlAnim.short))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BiliSpacing.smPlus, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
                    ) {
                        // 顶栏与底部控制行**同一套**圆钮：返回 / 首页 / 弹幕 / 横屏 / 更多。
                        // 文字只进 contentDescription（用户口径：控件风格统一）。
                        PlayerIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            onClick = onBackClick,
                            chip = PlayerChipScrim
                        )
                        if (!isFull) {
                            PlayerIconButton(
                                icon = Icons.Default.Home,
                                contentDescription = "首页",
                                onClick = onGoHome,
                                chip = PlayerChipScrim
                            )
                        }
                        if (isFull) {
                            Column(modifier = Modifier.weight(1f)) {
                                videoTitle?.takeIf(String::isNotBlank)?.let {
                                    Text(
                                        text = it,
                                        // 信息（不是控件）：不加圆底，靠文字投影保持可读
                                        style = playerInfoStyle(MaterialTheme.typography.titleMedium),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 全屏捏合放大后的快捷还原入口（圆钮，开/关态由图标本身表达）
                        if (isFull && zoomState.isZoomed) {
                            PlayerIconButton(
                                icon = Icons.Rounded.ZoomOutMap,
                                contentDescription = "还原画面",
                                onClick = { zoomState.reset() },
                                chip = PlayerChipScrim
                            )
                        }
                        // 弹幕开关（开/关态用图标变体表达，不再用「弹幕」/「弹幕关」文字胶囊）
                        PlayerIconButton(
                            icon = if (danmakuOn) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff,
                            contentDescription = if (danmakuOn) "关闭弹幕" else "开启弹幕",
                            onClick = {
                                setShowCtrl(true)
                                viewModel.updateDanmaku(
                                    settingsState.danmaku.copy(enabled = !settingsState.danmaku.enabled)
                                )
                            },
                            chip = PlayerChipScrim
                        )
                        // 手动横屏（只在允许的宿主里出现，见 onToggleLandscape 的参数注释）：
                        // 竖屏连播是锁竖屏的，横屏视频想看满屏只能由用户显式要一次。
                        // 开/关态同样用图标变体：已横屏时给"转回竖屏"的图标。
                        onToggleLandscape?.let { toggle ->
                            PlayerIconButton(
                                icon = if (landscapeForced) {
                                    Icons.Rounded.StayCurrentPortrait
                                } else {
                                    Icons.Rounded.StayCurrentLandscape
                                },
                                contentDescription = if (landscapeForced) "退出横屏" else "进入横屏",
                                onClick = {
                                    setShowCtrl(true)
                                    toggle()
                                },
                                chip = PlayerChipScrim
                            )
                        }
                        PlayerIconButton(
                            icon = Icons.Default.MoreVert,
                            contentDescription = "更多信息",
                            onClick = {
                                setShowCtrl(true)
                                showPlaybackSheet = true
                            },
                            chip = PlayerChipScrim
                        )
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
            // 底部三件套（渐变遮罩 + 信息层 + 常驻细进度条）**已提升为会话级单例**：
            // 用户真机反馈「翻下一个视频时下方简介会从下往上跳动」——根因就是它长在
            // pane 里（pane 每页一个），翻页时跟着页面位移；而互动栏早就是会话级单例，
            // 所以只有它晃。现在与互动栏同一处理：常驻在 PortraitSwipePager 的页面层
            // 之上、切视频原地换数据（见 pager 的底部 chrome 接线），顺带省掉每页一份
            // 的重复组合。
            // 组件定义留在本文件，
            // 与控制件玻璃同语言。

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
            // SW-34：三态由**一个判据**派生（失败优先 → 等首帧(有/无封面) → 隐藏），
            // 不再让 veil 收两个各自派生的布尔（那种写法允许 visible=false 而 failed=true
            // 的非法组合）。veil 内部的时序与渲染顺序未变。
            val loadPhase = SwipeLoadPhasePolicy.resolve(
                waitingFirstFrame = swipeWaitingFirstFrame,
                failed = swipeFailed,
                hasCover = !swipeCoverUrl.isNullOrBlank()
            )
            SwipeWaitVeil(
                visible = SwipeLoadPhasePolicy.isVeilVisible(loadPhase),
                coverUrl = swipeCoverUrl,
                failed = SwipeLoadPhasePolicy.isError(loadPhase),
                errorText = swipeErrorText,
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
    /**
     * 失败文案（SW-13 分类后的人话；`null` = 用默认文案）。
     * 网络类会指向"检查网络"，解码类会指向"换一支"——分类对用户可见。
     */
    errorText: String? = null,
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
    // 暗层 alpha 从 **0** 起（2026-10-11 用户反馈「每次刷视频会出现闪预览图」）。
    //
    // 为什么不能再 snapTo(1f)：本层的封面与**上一页的 `SwipeCoverPage` 是同一张图、同一取景**
    // （同 URL、同 `PlayerPreview` 变体、同 `ContentScale.Fit`）—— 设计意图正是"换页那一帧
    // 逐像素不变"。但暗遮罩 + 粒子原先在挂载瞬间直接满上，于是同一张图**突然被压暗 45~55%
    // 并多出一层粒子**，观感就是"预览图闪了一下"（图没变，明暗变了）。
    // 现在改成淡入：挂载后的前 `short` 毫秒里画面与封面页完全一致，暗层再平滑压上，
    // 视觉上读作"画面暗下来开始加载"，而不是"闪了一下"。
    val chromeAlpha = remember { Animatable(0f) }
    // 落定收缩的进度 0→1（只被乘进 scale，不是独立的动画目标）。
    //
    // **初值必须是 1f（= 已落定 = 缩放 1.0）**：原先 `visible` 时 `snapTo(0f)` ⇒ 等待期间
    // 封面一直是 1.02 倍，而上一页 `SwipeCoverPage` 是 1.00 倍 ⇒ 换页那一帧画面**放大 2%**，
    // 与上面那条暗层突现叠加成"闪"。落定收缩只在**退场**（与画面交叉淡化）那一段发生，
    // 进场必须与封面页严格同尺寸。
    val settle = remember { Animatable(1f) }
    var mounted by remember { mutableStateOf(visible) }
    // 暗层退完就把粒子从组合里摘掉：它带常驻无限动画，让它空转正好占掉交接那几帧的预算。
    var chromeGone by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            chromeGone = false
            coverAlpha.snapTo(1f)
            settle.snapTo(1f)
            // 淡入（不是 snapTo）：见 chromeAlpha 的注释。已经在满亮（同一层连续两次 visible）
            // 时不重播，避免"等首帧被延长"时暗层来回呼吸。
            if (chromeAlpha.value < 1f) {
                chromeAlpha.animateTo(1f, tween(timing.chromeFadeMs))
            }
        } else if (mounted) {
            // 1) 暗遮罩 + 粒子先退 → 2) 干净封面再停留一拍（刚出帧的画面常常偏暗，
            // 立刻 dissolve 会读成"闪"）→ 3) 封面与画面交叉淡化，同时做落定收缩。
            chromeAlpha.animateTo(0f, tween(timing.chromeFadeMs))
            chromeGone = true
            if (timing.coverHoldMs > 0) delay(timing.coverHoldMs.toLong())
            // 落定收缩从这一刻起：先回到 1.02，再随交叉淡化收回 1.0（与封面页同尺寸起步 ⇒
            // 反向也连续）。整段都在交叉淡化里，肉眼只读作"画面在坐实"。
            settle.snapTo(0f)
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
                            WaitVeilSolidBrush
                        } else {
                            WaitVeilCoverScrimBrush
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
                        text = errorText ?: "这一支加载失败了",
                        color = WaitVeilErrorTint,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (onRetry != null) {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.appElasticPress()
                        ) { Text("重试") }
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

// ── 底部细进度条 / 可拖 seek（原 SW-15）已删除 ─────────────────────────────────
// 用户决定（2026-10-02）：「把底部细进度条这个功能删掉，我不需要」。
// 常驻指示条、横向拖动 seek、时间气泡，以及 core:swipe 里只服务于它的判据与自动
// 翻页的「拖动中抑制」入参一并删除；相关常量与配色也已清理（守卫：feature:video 的
// `swipeProgressLineStaysRemoved`，那里写着将来要恢复时必须一起恢复的东西）。
// 任务书 SW-15 因此作废（产品决定覆盖任务书，已登记在 L4 与提交信息）。

/**
 * 非空展示值**粘滞**到下一个非空值到达（用户反馈「每次下滑控件消失再出现」的计数侧修复）：
 * 换页瞬间新视频详情未回（`stat` 为 null），计数若跟着 null 走就会闪没。
 * ⚠️ 只允许用于**展示数值**；`liked/faved` 这类状态粘滞会显示错觉，不许用。
 */
@Composable
private fun rememberStickyText(value: String?): String? {
    var last by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(value) {
        if (!value.isNullOrBlank()) last = value
    }
    return value ?: last
}

/**
 * 列表版粘滞（SW-21/SW-32 的话题/合集用）：**非空**列表记住，直到下一个非空列表到达。
 * 与 [rememberStickyText] 同一手法——换页瞬间新详情未到时，底部块的高度不塌陷。
 */
@Composable
private fun rememberStickyTags(value: List<String>): List<String> {
    var last by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(value) {
        if (value.isNotEmpty()) last = value
    }
    return value.ifEmpty { last }
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
    /**
     * 点赞态：`true` 已赞 / `false` 未赞 / **`null` 未知**（A16）。
     *
     * 为什么是 `Boolean?` 而不是 `Boolean`：详情回包的 `req_user` 只在登录态下出现，
     * 拿不到时**不能推断成"未点赞"**。上游 BiliPai `PortraitInteractionBar.kt:153-155`
     * 同款三态（`isActive = likeStatusKnown && isLiked`）。未知时画中性态（暗色 + "待确认"），
     * 而不是画成"未选中"——后者会在真值到达时凭空跳一下。
     */
    liked: Boolean?,
    likeText: String?,
    onToggleLike: () -> Unit,
    replyText: String?,
    /** 打开评论（`null` = 当前场景还没有评论宿主，不显示评论按钮，避免假按钮）。 */
    onOpenComments: (() -> Unit)?,
    /** 已收藏态 + 收藏数（`userState.faved` / `stat.fav`，与详情页同一份真值源）。`null` = 未知。 */
    faved: Boolean?,
    favText: String?,
    /**
     * 打开收藏夹面板（`null` = 宿主不接，同评论按钮的口径：宁可少一个入口，
     * 也不做点了没反应的假按钮）。会话与详情页共用 VM 上那套收藏夹状态机。
     */
    onOpenFavorite: (() -> Unit)? = null,
    shareBvid: String?,
    shareAid: Long,
    shareTitle: String?,
    /**
     * 点赞**长按**（SW-20）：一键三连（BiliPai「点赞（长按三连）」同款）。
     * `null` = 不给这个入口（宿主没接）。
     */
    onLongPressLike: (() -> Unit)? = null,
    /**
     * 计数动效请求（与详情页同一判据、同一套 requests 类型；由 [PortraitSwipePager]
     * 创建后传入，组件**不自持计数状态** —— 沿用本栏"数据由调用点传入"的既有口径）。
     *
     * ⚠️ 这里刻意用**全限定名**而不是新增 import：本文件顶部有玻璃挂载行号钉点
     * （`drawBackdrop` 514 / `layerBackdrop` 913，见 GlassMountRegistryTest），
     * import 增删会让它们整体漂移、守卫立即红。
     */
    countRequests: com.naaammme.bbspace.feature.video.detail.VideoActionCountRequests? = null,
    /** 原始计数（未格式化）：识别"格式化后没变但计数真变了"，并让请求被消费。 */
    likeCountValue: Long? = null,
    favCountValue: Long? = null,
    /** 计数动效身份键（aid）：切视频重置，避免上一支未消费请求让新视频首帧弹一下。 */
    countIdentity: Any? = null,
    /** UP 头像（SW-20）：`faceUrl` 为空时退化成玻璃圆盘底（不留空洞）。 */
    ownerFace: String? = null,
    ownerName: String? = null,
    /** 点头像进 UP 空间（`null` = 宿主不接，头像退化为不可点）。 */
    onOpenSpace: (() -> Unit)? = null,
    /**
     * 打开「更多」菜单（SW-24）：不感兴趣/弹幕开关/复制链接/系统分享。
     * `null` = 宿主不接（不发这个入口）。
     */
    onOpenMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // ── 计数粘滞（用户反馈：每次下滑控件"消失再出现"）────────────────────────────
    // 换页瞬间新视频的 detail 还没回来（`stat` 为 null），计数若直接跟着 null 走，
    // 就会出现"数字消失 → 数字出现"的闪烁。这里把**非空**值记住，直到新值到达；
    // 亚秒级的旧数字比"数字整块闪没"体验好得多（BiliPai/抖音同款做法）。
    // liked/faved 仍按真值（粘滞会显示错觉，绝不粘）。
    val stickyLike = rememberStickyText(likeText)
    val stickyReply = rememberStickyText(replyText)
    val stickyFav = rememberStickyText(favText)
    Column(
        modifier = modifier.padding(end = BiliSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.lg)
    ) {
        // UP 头像（SW-20，任务书 D-6/D-5：用"头像 → 主页"替代抖音的音乐碟片位置）
        SwipeRailAvatar(
            faceUrl = ownerFace,
            ownerName = ownerName,
            onClick = onOpenSpace
        )
        // 现代短视频的互动栏：图标大、计数在图标**下方**、层间距均匀（抖音/B 站竖屏同款）
        SwipeRailAction(
            // SX-09（用户需求⑨）：**未赞/已赞必须有两种视觉**。原来两态共用同一个
            // 填充图标、只靠 tint 区分 —— 在深色主题下低饱和主题色与白色的差别不够醒目。
            // 现在按 liked 在**描边 / 填充**之间切换，与同一栏的收藏按钮
            // （Star / StarBorder）同款语言，也与全站"收藏态用填充"的习惯一致；
            // 颜色区分同时保留（两个维度叠加，不是互相替代）。
            // 已赞/未赞两态都用 **Rounded 系**（BiliPai/抖音同款圆润轮廓，2026-10-02 定稿）；
            // 计数缺失时显示动作名（BiliPai 的 countText fallback：「0 条也读得懂这颗是什么」）。
            // A16：三态。`liked == null` = 状态未确认 ⇒ 画中性态（描边图标 + 暗灰 tint +
            // "待确认"），**不画成"未选中"**。上游 BiliPai `PortraitInteractionBar.kt:153-155`
            // 同款（`isActive = likeStatusKnown && isLiked`、`countText = if (!likeStatusKnown) "待确认"`）。
            icon = if (liked == true) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
            contentDescription = when (liked) {
                true -> "取消点赞"
                false -> "点赞"
                null -> "点赞状态待确认"
            },
            label = if (liked == null) "待确认" else stickyLike ?: "点赞",
            tint = when (liked) {
                true -> MaterialTheme.colorScheme.primary
                false -> Color.White
                null -> Color.White.copy(alpha = 0.38f)
            },
            onClick = {
                // 只有**本地操作**才 arm 计数动效（与详情页同一个判据/requests 类型）：
                // 后台回填、首帧组合、切视频都不弹。arm 在点击时发生，真正的计数变化
                // 随后由写入路径回填 —— 两者之间无论隔多久，请求都会等那次变化消费。
                countRequests?.requestLike()
                onToggleLike()
            },
            onLongClick = onLongPressLike?.let { triple ->
                {
                    // 一键三连会同时改变点赞/投币/收藏三个计数（上游同义）。
                    countRequests?.requestTriple()
                    triple()
                }
            },
            onLongClickLabel = "一键三连",
            // SW-31：点赞态变化 → 图标弹一下（形变反馈；关注按钮缺数据源见参数 KDoc）
            popToken = liked,
            countRequest = countRequests?.like ?: 0,
            countValue = likeCountValue,
            countIdentity = countIdentity
        )
        if (onOpenComments != null) {
            SwipeRailAction(
                icon = Icons.AutoMirrored.Rounded.Comment,
                contentDescription = "评论",
                label = stickyReply ?: "评论",
                tint = Color.White,
                onClick = onOpenComments
            )
        }
        // 收藏（补齐 BiliPai 竖屏互动栏的 赞/评/藏/享 四件套里缺的那一件）。
        // 与评论按钮同一口径：宿主不接（`null`）就整颗不出现，不做点了没反应的假按钮。
        if (onOpenFavorite != null) {
            SwipeRailAction(
                // A16：同样三态 —— 未确认时画描边星 + 暗灰，不画成"未收藏"。
                // 收藏本身是显式 addIds/delIds（非取反），所以这里只是**读**的诚实性。
                icon = if (faved == true) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = when (faved) {
                    true -> "取消收藏"
                    false -> "收藏"
                    null -> "收藏状态待确认"
                },
                label = if (faved == null) "待确认" else stickyFav ?: "收藏",
                tint = when (faved) {
                    true -> FavStarColor
                    false -> Color.White
                    null -> Color.White.copy(alpha = 0.38f)
                },
                onClick = {
                    // 收藏需要先选文件夹：arm 在"打开面板"时，实际收藏数变化在面板里落地；
                    // 与详情页收藏 chip 的同款时序（先 arm、后等数值变化）。
                    countRequests?.requestFavorite()
                    onOpenFavorite.invoke()
                },
                // SW-31：收藏态变化 → 图标弹一下（同点赞）
                popToken = faved,
                countRequest = countRequests?.favorite ?: 0,
                countValue = favCountValue,
                countIdentity = countIdentity
            )
        }
        SwipeRailAction(
            icon = Icons.Rounded.Share,
            contentDescription = "分享",
            label = null,
            tint = Color.White,
            onClick = {
                // 与详情页共用同一套链接构造（BV 优先、av 兜底）
                val url = ShareUtils.videoUrl(shareBvid, shareAid, page = 0)
                ShareUtils.shareText(context, shareTitle, url)
            }
        )
        // 更多（SW-24）：不感兴趣/弹幕开关/复制链接/系统分享。宿主不接则整颗不出现。
        if (onOpenMore != null) {
            SwipeRailAction(
                icon = Icons.Rounded.MoreVert,
                contentDescription = "更多",
                label = null,
                tint = Color.White,
                onClick = onOpenMore
            )
        }
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
    /**
     * 长按（SW-20）：点赞长按 = **一键三连**（对齐 BiliPai
     * `PortraitInteractionBar.kt:76-142` 的"点赞（长按三连）"）。
     * `null` = 这一项没有长按语义（不给"按了没反应"的长按）。
     */
    onLongClick: (() -> Unit)? = null,
    /**
     * 长按在无障碍里的动作名（TalkBack 会读"长按可三连"这类提示）。
     * 只有 [onLongClick] 非空时有意义。
     */
    onLongClickLabel: String? = null,
    /**
     * 状态形变令牌（SW-31）：值变化时图标"弹一下"（1 → 1.18 → 1）。
     *
     * 为什么是令牌而不是布尔：点赞/收藏各有自己的真值源，形变只关心"变了"——
     * 传 `liked` / `faved` 即可；`null` = 这一项没有状态形变（评论/分享）。
     * ⚠️ 原计划的"关注按钮形变"缺数据源：仓内关系 gRPC 只有 `ModifyRelation`（写），
     * 没有查询接口，`VideoUserState` 也不含关注位 → 不做假按钮（见 L4 记录）。
     * `reduceMotion` 时不弹（无障碍纪律）。
     */
    popToken: Any? = null,
    /**
     * 计数动效三件套（与详情页 `VideoActionCountText` 同一判据）：
     * 请求计数 / 原始计数 / 身份键。`countRequest == 0` 表示这一项不参与动效。
     */
    countRequest: Int = 0,
    countValue: Long? = null,
    countIdentity: Any? = null,
) {
    // 状态形变（SW-31）：令牌变化 → 弹一下。读值在 graphicsLayer。
    var lastPopToken by remember { mutableStateOf(popToken) }
    val pop = remember { Animatable(1f) }
    // CompositionLocal 只能在组合期读：effect 里读到的是"组合那一刻"的值（够用——
    // 用户改"动效速度"后重组的下一帧就会带上新值）
    val popAnim = LocalAnimations.current
    LaunchedEffect(popToken, popAnim.reduceMotion) {
        if (popToken != lastPopToken) {
            lastPopToken = popToken
            if (!popAnim.reduceMotion) {
                pop.snapTo(1f)
                pop.animateTo(1.18f, tween(durationMillis = popAnim.short))
                pop.animateTo(1f, tween(durationMillis = popAnim.short))
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // SX-02：视觉缩小（暗盘 52→≈46dp、图标 40→≈35dp，统一 SwipeRailScale），
        // 但**热区不缩** —— 外层 Box 兜住 48dp 无障碍下限，内层才是缩小后的暗盘。
        // 缩的是观感，不是可点区域（要点②：视觉缩小时热区用透明留白补回）。
        Box(
            modifier = Modifier
                .size(SwipeRailTouchTarget)
                .combinedClickable(
                    onClickLabel = contentDescription,
                    onLongClickLabel = if (onLongClick != null) onLongClickLabel else null,
                    onLongClick = onLongClick,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(SwipeRailDiscSize)
                    .clip(CircleShape)
                    // 现代化观感（2026-10-02，用户 "ui 不好看，要现代审美"）：图标底改成
                    // **纯色暗盘**（16% 黑，对标 BiliPai 竖屏栏 `iconBackingAlpha = 0.16f`）——
                    // 原来的"玻璃渐变 + 白描边"读起来沉、像上一代的拟物玻璃。
                    // 顺带更省：没有渐变/描边/模糊，只有一次纯色绘制。
                    .background(SwipeRailBackingColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier
                        .size(SwipeRailIconSize)
                        .graphicsLayer {
                            scaleX = pop.value
                            scaleY = pop.value
                        }
                )
            }
        }
        label?.let { count ->
            Spacer(modifier = Modifier.height(BiliSpacing.xs))
            SwipeRailCount(
                label = count,
                reduceMotion = LocalAnimations.current.reduceMotion,
                request = countRequest,
                countValue = countValue,
                identity = countIdentity
            )
        }
    }
}

/**
 * 互动栏计数（SW-30 的"计数滚动"）：数值变化时**上浮淡入**（旧值不重播，
 * 只让新值"滚进来"），比硬换字有质感；`reduceMotion` 时直接换字。
 *
 * 读值在 graphicsLayer（绘制阶段），不进重组。
 */
@Composable
private fun SwipeRailCount(
    label: String,
    reduceMotion: Boolean,
    request: Int = 0,
    countValue: Long? = null,
    identity: Any? = null,
) {
    var shown by remember(identity) { mutableStateOf(label) }
    // 动效请求消费：与详情页共用 `shouldAnimateVideoActionCountChange` 判据
    // （全限定名：避免新增 import 让本文件顶部玻璃行号钉点漂移）。
    var consumedRequest by remember(identity) { mutableIntStateOf(request) }
    var seenCount by remember(identity) { mutableStateOf(countValue) }
    val roll = remember(identity) { Animatable(1f) }
    // 时长走 token（此前硬编码 260ms）：`reduceMotion` 已由调用方传进来，
    // 但"动效速度档位"此前对它无效——降级只做了一半。
    val rollAnim = LocalAnimations.current
    LaunchedEffect(identity, label, request, countValue) {
        val countChanged = seenCount != countValue
        if (label != shown || countChanged) {
            // 先在**旧值**上判定，再更新 shown；否则 before==after 永远成立、判据失效。
            val allow = com.naaammme.bbspace.feature.video.detail
                .shouldAnimateVideoActionCountChange(
                    before = shown,
                    after = label,
                    request = request,
                    consumedRequest = consumedRequest,
                    reducedMotion = reduceMotion,
                )
            shown = label
            consumedRequest = request
            seenCount = countValue
            if (allow) {
                roll.snapTo(0f)
                roll.animateTo(1f, tween(durationMillis = rollAnim.morph))
            }
        }
    }
    Text(
        text = shown,
        color = Color.White,
        // 现代短视频的通用做法（对标 BiliPai `labelSmall + shadow(黑 50%, blur 8)`）：
        // 计数没有底衬，靠**文字阴影**在任何画面上保持可读——比再垫一层圆盘轻。
        // 走 designsystem 的**具名出口**（棘轮：业务层不许内联 `typography.x.copy(`）。
        style = softShadowLabelSmall(),
        maxLines = 1,
        modifier = Modifier.graphicsLayer {
            if (!reduceMotion) {
                // 滚动中的上浮位移（token 的 1 倍档）
                translationY = (1f - roll.value) * BiliSpacing.sm.toPx()
                alpha = 0.35f + 0.65f * roll.value
            }
        }
    )
}

/**
 * 互动栏顶部的 **UP 头像**（SW-20）：现代短视频的心智是"头像 → 主页"
 * （抖音的"音乐碟片"位置，bbspace 无 BGM 元数据，按任务书 D6 用头像替代）。
 *
 * 触摸目标走圆盘同一档 token（≥48dp）；点击进 UP 空间（`onOpenSpace`），
 * 与底部信息层的 @名 同一出口。
 */
@Composable
private fun SwipeRailAvatar(
    faceUrl: String?,
    ownerName: String?,
    onClick: (() -> Unit)?,
) {
    // SX-02：与动作圆盘同一套缩放与热区口径（缩观感、不缩可点区域）。
    Box(
        modifier = Modifier
            .size(SwipeRailTouchTarget)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = "打开 UP 主主页", onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(SwipeRailDiscSize)
                .clip(CircleShape)
                // 头像底也是纯色暗盘（16% 黑）：没有任何白描边/玻璃渐变——
                // 现代短视频的头像就是"图片 + 极细白环"，多余的装饰会被读成旧式。
                .background(SwipeRailBackingColor)
                .border(PlayerPlayButtonBorderWidth, SwipeRailAvatarRingColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AvatarImage(
                url = faceUrl,
                contentDescription = ownerName?.let { "@$it" } ?: "UP 主",
                modifier = Modifier.fillMaxSize(),
                // 头像缺失/加载失败时用**首字**兜底（真机实测：部分视频详情不带 owner.face，
                // 直接渲染图片组件会只剩一个空环）。取首个 **code point** 而不是 take(1)：
                // UP 名以 emoji 开头时，take(1) 会截出半个代理对。
                fallbackText = ownerName
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { String(Character.toChars(it.codePointAt(0))) },
                fallbackContainerColor = SwipeRailAvatarFallbackColor
            )
        }
    }
}

/**
 * 刷视频互动栏的**统一缩放比例**（SX-02 / 用户需求②「侧边 UI 控件变小，少挡画面」）。
 *
 * 为什么是一个比例而不是逐个改数：侧栏有 头像 + 4~5 颗动作圆盘 + 若干计数文案，
 * 逐个调必然彼此不成比例（任务书要点④："**先定一套比例再动手**，避免两处各自缩"）。
 * 这里只压**横向占位与图标视觉尺寸**，比例取 0.88（≈12% 小一档，肉眼可辨但不"缩水"）。
 *
 * ⚠️ 与 SX-10 的评论卡片同属一套视觉语言：两处的"缩小/透明"都朝同一个方向
 * （画面露出更多），所以比例定死在这里、评论侧只动透明度，不再各自调尺寸。
 */
private const val SwipeRailScale = 0.88f

/**
 * 互动栏图标视觉尺寸：40dp × 0.88 ≈ 35dp
 * （原 40dp 对标 BiliPai 竖屏栏 iconSizeDp=40，token 的 1.667 倍档）。
 */
private val SwipeRailIconSize = BiliSpacing.xxl * 1.6667f * SwipeRailScale

/** 互动栏暗盘直径：52dp × 0.88 ≈ 46dp（原 52dp 对标 BiliPai iconBackingSizeDp=52）。 */
private val SwipeRailDiscSize = BiliSpacing.xxxl * 1.625f * SwipeRailScale

/**
 * 互动栏的**触摸目标下限**（SX-02 要点②）：48dp = `BiliSpacing.xxxl * 1.5f`。
 *
 * 视觉缩小后暗盘只有 ≈46dp，**低于无障碍 48dp 下限** —— 所以外层用一个 48dp 的
 * 透明 Box 兜住热区，可点区域**不随观感缩小**（也不新增 dp 字面量，仍从 token 派生）。
 */
private val SwipeRailTouchTarget = BiliSpacing.xxxl * 1.5f

/**
 * 图标暗盘底色：16% 纯黑（对标 BiliPai 竖屏栏 `iconBackingAlpha=0.16f`）。
 * 旧版这里是"静态液态玻璃渐变 + 白描边"，2026-10-02 按用户要求「换现代审美」删掉——
 * 渐变/描边/模糊全没了，只剩一次纯色 fill：常驻控件在连播里每帧都在，少一层绘制
 * 就少一分 GPU 开销（用户同时反馈过「减少渲染带来的性能消耗」）。
 */
private val SwipeRailBackingColor = Color.Black.copy(alpha = 0.16f)

/** 头像极细白环（0.7 白、走 token 比例），比旧的白描边玻璃盘轻。 */
private val SwipeRailAvatarRingColor = Color.White.copy(alpha = 0.7f)

/** 头像缺失时的兜底底色（比图标暗盘稍实，保证白色首字读得清）。 */
private val SwipeRailAvatarFallbackColor = Color.Black.copy(alpha = 0.32f)
private val PlayerPlayButtonBorderWidth = BiliSpacing.xs / 4f


internal const val SWIPE_BOTTOM_ZONE_FRACTION = 0.38f

// 2026-10-18 第 33 轮：会话底部信息层的**整条渐变遮罩已删除**
// （旧值 `SwipeBottomScrimBrush = Brush.verticalGradient(透明 → 45% → 黑 58%)`，
//  挂在 PortraitSwipePager 的底部 38% 屏高 Box 上）——
// 它就是用户「视频播放页还是有一层遮罩请全部去除」里最显眼的一层。
// 信息层只有文字（@UP 名 / 标题 / 话题 / 合集），可读性改由**文字投影**承担
// （[PlayerInfoTextShadow]，黑 55% / blur 6，由 `playerInfoStyle(...)` 挂到每行样式上），
// 画面其余部分 100% 干净。守卫 `PlayerOverlayScrimContrastTest` 反向断言该符号不存在。

/**
 * 会话内常驻信息：头像 + UP 名 + 标题 + 话题（左下角，与互动栏同排、压在进度条之上）。
 *
 * v2 改版（用户反馈「大幅优化操作布局和 UI」）：
 * - **UP 名上置、标题在下**（抖音/B 站竖屏页同款的信息层级）——先知道"在看谁"，
 *   再读"在看什么"；UP 名提到 `labelLarge`、86% 白，标题提到 `titleMedium`；
 * - 标题放宽到**两行**：旧版一行省略，长标题（真机样例"扫舱剩下的玉米有几吨之多，
 *   为什么不能再卖出…"）永远读不全；
 * - 可读性**曾**交给底部的渐变遮罩 —— ⚠️ 该遮罩（`SwipeBottomScrimBrush`）已于
 *   2026-10-18 第 33 轮按用户口径**整层删除**，现由每行文字的**投影**
 *   （[PlayerInfoTextShadow]）承担，文字本身仍不加背板；
 *   **符号也已一并删除**（不是只删挂载）：此处仅作为历史记录提及，不要再引用它 ——
 *   `PlayerOverlayScrimContrastTest` 反向断言该符号在全仓不存在。
 *
 * v3 改版（SW-21，任务书 §SW-21）：
 * - **作者行**：小头像（token 的 1 倍档 = 24dp）+ `@名`，整体可点 → 进 UP 空间
 *   （与互动栏头像同一出口；mid 缺失时不接，也就不出现"点了没反应"）；
 * - **标题可展开**：两行省略 → 点一下展开全文、再点收起（展开态随页复位，见 [resetKey]）；
 * - **话题 chip**：详情自带 tags 的前 3 个，`#标签` 只读展示（点击需要话题路由，
 *   仓内没有该出口 —— 宁可不做，也不做点了没反应的假 chip）；
 * - 触摸热区**只有作者行与标题两处**：其余区域（含 chip 之间、文字行间）不消费触摸，
 *   上下滑翻页照旧（旧版"整层只读"的不变式按任务书反转，守卫同批更新）。
 *
 * 设计约束：间距/字号全部走 token（`BiliSpacing` + `MaterialTheme.typography`），
 * 不新增 dp 字面量（`DesignTokenLintTest` 的棘轮会判红）。头像尺寸用 token 加法表达。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeSessionInfoLine(
    title: String?,
    owner: String?,
    tags: List<String> = emptyList(),
    /** 合集名（SW-32；null = 这支不属于任何合集，不显示 chip）。 */
    seasonTitle: String? = null,
    /** 联合创作成员（PT-08；空 = 单人投稿，不显示 chip）。 */
    staffs: List<VideoStaff> = emptyList(),
    onOpenSpace: (() -> Unit)? = null,
    /** 展开态复位键（传当前视频的 aid）：换页自动收起，避免"下一页仍是展开的"。 */
    resetKey: Any? = null,
    modifier: Modifier = Modifier,
) {
    // ── 内容**粘滞**：换页时新详情还没回来（title/tags 全空），若直接跟着空值走，
    // 这一块会先塌成 0 高度、等新详情到达再长回来——它是贴底对齐的，于是表现为
    // 「底部的简介/标签从下往上移」（用户 2026-10-02 第二次反馈的真凶）。
    // 与互动栏计数同一手法：非空值记住，直到新值到达（亚秒级陈旧换来零位移）。
    val stickyTitle = rememberStickyText(title)
    val stickyOwner = rememberStickyText(owner)
    val stickySeason = rememberStickyText(seasonTitle)
    val stickyTags = rememberStickyTags(tags)
    val titleText = stickyTitle?.takeIf(String::isNotBlank) ?: return    // 常驻件：跟随会话级防烧屏（静置变暗 + 缓慢像素漂移，见 SwipeChromeBurnIn）。
    val burnIn = LocalSwipeChromeBurnIn.current
    // 展开态随页复位（SW-21）：resetKey 传当前视频的 aid
    var expanded by remember(resetKey) { mutableStateOf(false) }
    // PT-08：联合创作成员弹层（随页复位，同 expanded 口径）
    var showStaffSheet by remember(resetKey) { mutableStateOf(false) }
    val coCreation = isCoCreation(staffs)
    Column(
        modifier = modifier
            .applyBurnIn(burnIn)
            .padding(
                start = BiliSpacing.lg,
                end = BiliSpacing.sm,
                bottom = BiliSpacing.md
            ),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
    ) {
        // 作者行（SW-21）：`@名`，整行一个热区 → 进 UP 空间。
        // **不带头像**（真机反馈：与互动栏顶部的头像重复）——抖音同款：头像只在右侧
        // 互动栏出现，底部只留 `@名` 文本。
        val authorRowModifier = if (onOpenSpace != null) {
            Modifier.clickable(onClickLabel = "打开 UP 主主页", onClick = onOpenSpace)
        } else {
            Modifier
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
            modifier = authorRowModifier
        ) {
            // 作者行高度**恒定**：`@名` 用粘滞值（换页瞬间不会退化成空行）
            val name = stickyOwner?.takeIf(String::isNotBlank)
            name?.let {
                SwipeMarqueeText(
                    text = "@$it",
                    color = Color.White.copy(alpha = 0.86f),
                    // 底部整层遮罩已删除（2026-10-18 第 33 轮）⇒ 可读性靠投影
                    style = playerInfoStyle(MaterialTheme.typography.labelLarge)
                )
            }
            // PT-08：联合创作 chip（上游模板串逐字）。放在作者行内与上游同位；
            // **不新增行高**——chip 用比作者名低一档的 labelSmall，且行内元素越界
            // 时名字走跑马灯、chip 固定，行高由 labelLarge 决定。
            if (coCreation) {
                Text(
                    text = coCreationChipText(staffs),
                    color = Color.White.copy(alpha = 0.82f),
                    style = playerInfoStyle(MaterialTheme.typography.labelSmall),
                    maxLines = 1,
                    modifier = Modifier.clickable(
                        onClickLabel = "查看联合创作成员"
                    ) { showStaffSheet = true }
                )
            }
        }
        Text(
            text = titleText,
            color = Color.White,
            // 底部整层遮罩已删除（2026-10-18 第 33 轮）⇒ 标题靠投影保持任意画面上可读
            style = playerInfoStyle(MaterialTheme.typography.titleMedium),
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            // **保底两行**：短标题也占两行高度 → 换页时整块高度不变、不会"从下往上长"
            // （贴底对齐时高度变化就是位移，见上方粘滞注释）
            minLines = if (expanded) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(
                onClickLabel = if (expanded) "收起标题" else "展开标题"
            ) { expanded = !expanded }
        )
        // 话题 / 合集：**单行内联**并**预留一行高度**。
        // 旧实现是每条标签占一行（3 个标签 = 3 行）→ 标签数一变，整块高度就跳（用户
        // 「标签等内容还有从下往上移动」的主因）。现在无论有没有标签，这一行高度恒定。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
            modifier = Modifier.heightIn(min = TagRowMinHeight)
        ) {
            stickyTags.take(3).forEach { tag ->
                Text(
                    text = "#$tag",
                    color = Color.White.copy(alpha = 0.72f),
                    style = playerInfoStyle(MaterialTheme.typography.labelSmall),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 合集 chip（SW-32）：与话题同一行（只读；点击需要合集路由，不做假入口）
            stickySeason?.takeIf(String::isNotBlank)?.let { season ->
                Text(
                    text = "合集 · $season",
                    color = Color.White.copy(alpha = 0.72f),
                    style = playerInfoStyle(MaterialTheme.typography.labelSmall),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // PT-08：联合创作成员弹层（上游 PortraitStaffSheet 的 bbspace 适配版：
        // bbspace 的 VideoStaff 无 mid → 只列"昵称 + 分工"，无头像/无关注动作）
        if (showStaffSheet && coCreation) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                shape = biliSheetShape(),
                dragHandle = { BiliSheetGrabber() },
                scrimColor = Color.Black.copy(alpha = 0.40f),
                onDismissRequest = { showStaffSheet = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "联合创作成员",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(
                            start = BiliSpacing.lg,
                            end = BiliSpacing.lg,
                            bottom = BiliSpacing.md
                        )
                    )
                    coCreationSheetMembers(staffs).forEach { member ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = BiliSpacing.lg,
                                    end = BiliSpacing.lg,
                                    bottom = BiliSpacing.sm
                                )
                        ) {
                            // 弹层可滚动：成员名不设 maxLines（长名换行）——
                            // infoLine 块内的 maxLines=1 计数被 VideoLogicGuardTest 钉死，别加
                            Text(
                                text = member.name,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            member.role.takeIf(String::isNotBlank)?.let { role ->
                                Text(
                                    text = role,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 话题/合集行的最小高度（token 的一档，与 labelSmall 一行等高）：换页时高度不跳。 */
private val TagRowMinHeight = BiliSpacing.xl

/**
 * 单行**跑马灯**文案（SW-32）：只在真的超宽时滚动（判据 [SwipeMarqueePolicy] +
 * `TextLayoutResult.hasVisualOverflow`），两端各停留一拍再往返；`reduceMotion` 不滚。
 *
 * 实现纪律：用 `Animatable` 驱动（**不新增 `rememberInfiniteTransition`**，守帧预算棘轮）；
 * 位移只在 `graphicsLayer` 里读（绘制阶段，不触发重组）。
 */
@Composable
private fun SwipeMarqueeText(
    text: String,
    color: Color,
    style: androidx.compose.ui.text.TextStyle,
) {
    val reduceMotion = LocalAnimations.current.reduceMotion
    var containerWidthPx by remember { mutableStateOf(0f) }
    var textWidthPx by remember { mutableStateOf(0f) }
    var hasOverflow by remember { mutableStateOf(false) }
    val marquee = SwipeMarqueePolicy.shouldMarquee(textWidthPx, containerWidthPx, reduceMotion) && hasOverflow
    val offset = remember { Animatable(0f) }
    val distance = SwipeMarqueePolicy.travelDistancePx(textWidthPx, containerWidthPx)
    LaunchedEffect(marquee, distance, text) {
        if (!marquee || distance <= 0f) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        val travel = SwipeMarqueePolicy.travelDurationMs(distance)
        // 停留 → 滚到末尾 → 再停留 → 回起点（往返匀速，两端都有停顿）
        while (true) {
            delay(SwipeMarqueePolicy.HOLD_MS)
            offset.animateTo(-distance, tween(durationMillis = travel.toInt(), easing = LinearEasing))
            delay(SwipeMarqueePolicy.HOLD_MS)
            offset.animateTo(0f, tween(durationMillis = travel.toInt(), easing = LinearEasing))
        }
    }
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            textWidthPx = result.size.width.toFloat()
            hasOverflow = result.hasVisualOverflow || result.lineCount > 1
        },
        modifier = Modifier
            .onSizeChanged { containerWidthPx = it.width.toFloat() }
            .graphicsLayer { translationX = offset.value }
    )
}

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

/**
 * 加载层的两把静态画笔（**不要写回组合体内**）。
 *
 * `Brush.verticalGradient(...)` 是工厂函数、不是编译期常量：写在组合体里就是**每次重组
 * 新建一个 Brush 对象**（分配 + 参数校验）。加载层的 veil 在换页/起播期间频繁重组，
 * 这里提到文件级 —— 只构建一次，零逐帧成本。
 *（原注释写的是"按 `SwipeBottomScrimBrush` 的同一形态"；那个画笔已于 2026-10-18 第 33 轮
 *  整层删除，引用它会指向一个不存在的符号。手法本身保留。）
 *
 * **位置约束**：本组必须定义在 `WaitVeil*Color`（上方）之后。文件级 `val` 按书写顺序
 * 初始化，放到使用点之前会读到未初始化的 `Color` 默认值（全透明），画面直接错。
 */
private val WaitVeilSolidBrush = Brush.verticalGradient(
    listOf(WaitVeilTopColor, WaitVeilBottomColor)
)

/** 有封面铺底时的轻遮罩画笔，见 [WaitVeilScrimTopColor] 的说明。 */
private val WaitVeilCoverScrimBrush = Brush.verticalGradient(
    listOf(WaitVeilScrimTopColor, WaitVeilScrimBottomColor)
)

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
    val anim = LocalAnimations.current
    // SW-30：粒子版爱心。粒子参数由 token（种子）决定 —— 同一次点赞可复现、不同次不重复。
    // reduceMotion：不取粒子、主心也不缩放上浮，只"亮一下就收"（静态心）。
    val useBurst = !anim.reduceMotion
    val particles = remember(token) { if (useBurst) SwipeHeartBurstPolicy.particles(token) else emptyList() }
    var visible by remember(token) { mutableStateOf(true) }
    val progress = remember(token) { Animatable(0f) }
    LaunchedEffect(token) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = if (useBurst) anim.long * 2 else anim.short)
        )
        visible = false
    }
    if (!visible) return
    Box(modifier = modifier.fillMaxSize()) {
        particles.forEach { particle ->
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = LikeHeartColor.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(BiliSpacing.xl)
                    .graphicsLayer {
                        val p = progress.value
                        val (dxFraction, dyFraction) = SwipeHeartBurstPolicy.particleOffset(particle, p)
                        // 位移按屏幕短边比例（粒子只在小范围内散开，不随屏宽失真）
                        val referPx = minOf(size.width, size.height)
                        val particleScale = particle.scale * (0.8f + 0.4f * p)
                        scaleX = particleScale
                        scaleY = particleScale
                        alpha = SwipeHeartBurstPolicy.particleAlpha(p)
                        translationX = position.x + dxFraction * referPx - size.width / 2f
                        translationY = position.y + dyFraction * referPx - size.height / 2f
                    }
            )
        }
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = "点赞",
            tint = LikeHeartColor,
            modifier = Modifier
                .size(HeartSize)
                .graphicsLayer {
                    val p = progress.value
                    val heartScale = if (useBurst) SwipeHeartBurstPolicy.mainHeartScale(p) else 1f
                    scaleX = heartScale
                    scaleY = heartScale
                    alpha = if (useBurst) SwipeHeartBurstPolicy.mainHeartAlpha(p) else 1f
                    translationX = position.x - size.width / 2f
                    translationY = position.y - size.height / 2f -
                        if (useBurst) p * BiliSpacing.xxxl.toPx() else 0f
                }
        )
    }
}

/** 主心尺寸（与旧实现一致：56dp，走 spacing token 的 1.75 倍档，不新增 dp 字面量）。 */
private val HeartSize = BiliSpacing.xxxl * 1.75f

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

    // ── 控制件（chrome）显隐：SX-05 ────────────────────────────────────────────
    // 判据与超时都收进纯策略（`PlayerChromePolicy`，可纯 JVM 单测）；这里只做"事实汇总"。
    // `chromeGestureActive` 含长按加速徽标（它同样算"手上有动作"）；
    // `dragGestureActive` 只算**真正的拖动**（亮度/音量/快进）—— 长按加速时弹控制条会更闹。
    val dragGestureActive = dragMs != null || gestureState.dragType != DragType.None
    val chromeGestureActive = dragGestureActive || gestureState.showSpeedBadge
    LaunchedEffect(
        showCtrl,
        state.isPlaying,
        chromeGestureActive,
        activeDialog,
        showPlaybackSheet
    ) {
        if (
            PlayerChromePolicy.shouldAutoHide(
                controlsVisible = showCtrl,
                isPlaying = state.isPlaying,
                gestureActive = chromeGestureActive,
                dialogOpen = activeDialog != null,
                sheetOpen = showPlaybackSheet
            )
        ) {
            // SX-05 要点①：超时是**具名常量**（`PlayerChromePolicy.AUTO_HIDE_MS`），不是裸字面量。
            delay(PlayerChromePolicy.AUTO_HIDE_MS)
            onShowCtrlChange(false)
        }
    }
    // SX-05 要点①："任意点击、**拖动**唤出"。点击唤出早已存在（videoGestures 的
    // onToggleControls）；拖动原本只被自动隐藏排除、并不唤出 —— 横屏全屏里上下拖调亮度时
    // 控件始终不出现。这里补上；只在控件不可见时才写一次 state（避免每帧续期自动隐藏计时）。
    LaunchedEffect(dragGestureActive, showCtrl) {
        if (PlayerChromePolicy.shouldRevealOnGesture(dragGestureActive, showCtrl)) {
            onShowCtrlChange(true)
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
        //
        // 追加判据（2026-10-01 真机实测「翻页时大暂停键闪一帧」）：**会话内首帧未出**
        // 时不显示中央播放键。翻页瞬间引擎 `stopForReuse` 会让 `playWhenReady` 短暂为假，
        // 而加载层（veil）是 effect 异步架起的（真机实测架起间隙 10~23ms ≈ 一帧）——
        // 这一帧就是用户看到的闪帧。`hasRenderedFirstFrame == false` 正是"这一支还没有
        // 画面"的**同步**信号：veil 的粘滞键防的是它的"归零晚于 ids 发布"，而这里只在它
        // 为 false 时**抑制**按钮，不存在被滞后值误伤的路径。
        // 非会话（详情放大的单视频全屏）不受影响：swipePagingActive=false 时判据不生效。
        //
        // 追加判据（2026-10-11 用户反馈「全屏后 UI 控件有的重复图层（停止按钮等等）」）：
        // **控制条在场时不显示中央大按钮**。两者绑的是同一个动作（`togglePlayPause`），
        // 暂停时控制条里那颗「播放」与中央这颗大 ▶ 会**同屏各画一份** —— 用户读作
        // "重复的控件"。现代播放器（YouTube / B 站 / 抖音）的分工是：
        // 大按钮只属于**控件隐藏**的沉浸态（那是唯一能暂停的入口），控件一亮就让位给控制条。
        val showCenterPlayButton =
            !showCtrl &&
                !state.isPlaying &&
                (!state.playWhenReady || state.playbackState == PlaybackState.Ended) &&
                !(swipePagingActive && !state.hasRenderedFirstFrame)

        if (showCenterPlayButton) {
            // 2026-10-17「无模糊语言」方向二 §6-7：**半透明黑圆盘 → 白环**。
            // 旧版是 `playerControlGlass` + 黑 38% 底盘（模糊语言的"玻璃盘"形状）；
            // 本仓不做模糊，降级后它只是一块压暗画面的半透明件，与"无模糊的清晰层次"相反。
            // ⇒ 改成**空心白环 + 实心白三角**：靠"线"表达可点，不靠"块"遮挡画面。
            // ⚠️ 2dp 环宽是本波**唯一新增的 dp 字面量**（构件规格，非可调间距；
            // 间距档是 4/8/12/16，没有 2）—— 已按 `DesignTokenLintTest` 的体例留理由行。
            val centerPlaySize = BiliSpacing.xxxl * 2f
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(centerPlaySize)
                    .border(2.dp, Color.White, CircleShape)
                    .clickable { viewModel.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(BiliSpacing.xxxl)
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

        // ── 控制层（iOS-2，2026-10-16）────────────────────────────────────────
        //
        // 一整层：**渐变 + 进度行 + 控件行**，随 `showCtrl` 一起显隐、在 [PlayerCtrlBarHost]
        // 内部渲染。进度状态只算一处 —— 时间文本与进度线两个消费者不会各算一套
        // （拖动态 / 空降标记 / 时长解析同源）。
        //
        // 为什么进度**收回**容器（上一轮曾搬出去常驻贴底）：用户 2026-10-02 明确说过
        // 「把底部细进度条这个功能删掉，我不需要」。控制层隐藏时进度不显示，
        // 由 `VideoLogicGuardTest.progressLineIsRenderedOnlyInsideTheControlLayer` 双向守护。
        PlayerCtrlBarHost(
            viewModel = viewModel,
            state = state,
            player = player,
            isFull = isFull,
            showCtrl = showCtrl,
            dragMs = { dragMs },
            gestureSeekMs = gestureState.dragSeekPosMs,
            onDragMsChange = { dragMs = it },
            onShowCtrlChange = onShowCtrlChange,
            onShowA = onShowA,
            onShowQ = onShowQ,
            onShowSp = onShowSp,
            onToggleFull = onToggleFull,
            onCtrlBarHeightChange = onCtrlBarHeightChange,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun PlayerCtrlBarHost(
    viewModel: VideoViewModel,
    state: VideoPlaybackState,
    player: Player?,
    isFull: Boolean,
    /** 控制层（渐变 + 进度行 + 控件行）是否可见。**进度行随它一起显隐**（iOS-2）。 */
    showCtrl: Boolean,
    dragMs: () -> Long?,
    gestureSeekMs: Long?,
    onDragMsChange: (Long?) -> Unit,
    onShowCtrlChange: (Boolean) -> Unit,
    onShowA: () -> Unit,
    onShowQ: () -> Unit,
    onShowSp: () -> Unit,
    onToggleFull: () -> Unit,
    onCtrlBarHeightChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // ⚠️ 本函数**刻意不 collect 播放进度**（2026-10-16「滑动/运行卡顿」性能修复）：
    // 进度是 500ms 一跳的流，在宿主顶部 collect 会让**整个宿主**（含整条控制条）每
    // 500ms 重组一次；而本宿主是**常驻**的（控制条隐藏时照样跑）⇒ 空转的重组纯属浪费。
    // 进度实际只有两个消费者 —— 时间文本与进度线 —— 现在各自在**最小叶子**里订阅：
    // [PlayerTimeTextLeaf] 与 [PlayerProgressLeaf]。进度更新因此只重组那两个小作用域。
    //
    // 代价是 `durationMs` 的解析在叶子内各算一次（同一表达式，三处兜底来源一致）；
    // 换来的是整条控制条不再随进度重组。
    //
    // dragMs 存在**外层**（浮层之上、活得比控制条久）的作用域里，而原本只有
    // onSeekDone 会清它。手指还按在进度条上时控件自动隐藏、或另一根手指点视频区
    // 把控制条关掉，pointerInput 随组合消失，onSeekEnd/onSeekCancel 永不触发 →
    // `barMs = dragSeekMs ?: ...` 从此永久停在拖动位置，进度条看起来"卡死"。
    // 离开组合时兜底清一次。回调用 rememberUpdatedState 取最新实例，
    // 免得 DisposableEffect(Unit) 捕获到第一次组合时的旧闭包。
    val latestClearDragMs = rememberUpdatedState(onDragMsChange)
    DisposableEffect(Unit) {
        onDispose { latestClearDragMs.value(null) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 控制层：**进度行 + 控件行**，**整层随 `showCtrl` 显隐**（iOS-2，2026-10-16）──
        //
        // ⚠️ 这里有一条**用户口径**（2026-10-02：「把底部细进度条这个功能删掉，我不需要」）：
        // 进度线**不许**做成"贴屏幕底边常驻的细线"。上一轮把它搬出容器常驻是走错了方向，
        // iOS-2 收回本容器内 —— 控制层隐藏时进度**不显示**。
        // 该约束由 `VideoLogicGuardTest.progressLineIsRenderedOnlyInsideTheControlLayer`
        // 双向钉住（块内 1 处 **且** 宿主体内恰好 1 处），另有 `swipeProgressLineStaysRemoved`
        // 守"旧常驻组件不许回来"。
        //
        // ## 2026-10-18 第 33 轮：**整层渐变遮罩彻底删除**
        // 旧写法是「118/126dp 外层 Column + 包进度行/控件行的内层 Column 挂
        // `PlayerCtrlBarScrimBrush`」—— 用户在真机上看到的那层遮罩。本轮**连画笔一起删掉**，
        // 两个容器都只剩布局，没有任何 `.background(Brush)`：
        // 画面在这里是 100% 干净的，可读性全部由**每颗控件自己的圆底**
        // （[PlayerChipScrim]，34dp 圆钮）与**轨道衬底**（[PlayerTrackBedScrim]）承担。
        // 层高常量（[PlayerCtrlLayerHeight] / [PlayerCtrlLayerHeightFull]）保留：它还决定
        // 控制层的**垂直占位**（进度行 + 控件行 + 底留白），只是不再有背景。
        val ctrlAnim = LocalAnimations.current
        AnimatedVisibility(
            visible = showCtrl,
            enter = fadeIn(tween(durationMillis = ctrlAnim.medium, easing = ctrlAnim.standard)) +
                slideInVertically(tween(durationMillis = ctrlAnim.medium, easing = ctrlAnim.standard)) { it / 16 },
            exit = fadeOut(tween(durationMillis = ctrlAnim.short))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isFull) PlayerCtrlLayerHeightFull else PlayerCtrlLayerHeight)
                    // P0-2：全屏时进度条/控件行让出手势条（真机 `32-land-chrome.png`
                    // 底部进度条与三条手势条重叠）。非全屏不加，理由同顶栏。
                    .then(if (isFull) Modifier.navigationBarsPadding() else Modifier),
                verticalArrangement = Arrangement.Bottom
            ) {
                // 进度行：贴在控件行上方（间距见下方 Spacer）。
                // 轨道对比度由**轨道自己的衬底**（[PlayerTrackBedScrim]）承担 ——
                // 控制层已无任何整层背景，见上方说明。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BiliSpacing.md)
                        .height(BiliSpacing.xxxl),
                    contentAlignment = Alignment.Center
                ) {
                    PlayerProgressLeaf(
                        viewModel = viewModel,
                        player = player,
                        sourceDurationMs = state.playbackSource?.durationMs,
                        isPlaying = state.isPlaying,
                        interactive = showCtrl,
                        dragMs = dragMs,
                        gestureSeekMs = gestureSeekMs,
                        onDragMsChange = onDragMsChange,
                        onShowCtrlChange = onShowCtrlChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 轨道与胶囊端之间再留一档（14dp 无 token，属本批唯一新增字面量）
                            .padding(horizontal = 14.dp)
                    )
                }
                // 进度行 → 控件行 间距
                Spacer(Modifier.height(BiliSpacing.md))
                PlayerCtrlBar(
                    playText = if (state.isPlaying) "暂停" else "播放",
                    // 图标形态由它决定（`Pause` / `PlayArrow`）；与 `playText` 同一来源。
                    isPlaying = state.isPlaying,
                    // 时间文本在**自己的最小作用域**里订阅进度（见 [PlayerTimeTextLeaf]）：
                    // 槽位是 RowScope 的，叶子在自己的作用域里订阅、又照旧吃这一排的 weight。
                    timeSlot = {
                        PlayerTimeTextLeaf(
                            viewModel = viewModel,
                            player = player,
                            sourceDurationMs = state.playbackSource?.durationMs,
                            dragMs = dragMs,
                            gestureSeekMs = gestureSeekMs
                        )
                    },
                    audioText = state.currentAudio?.let { getAudioName(it.id, short = true) } ?: "音频",
                    qualityText = getQualityName(state.playbackSource, state.currentStream),
                    speedText = formatSpeed(state.speed),
                    fullText = if (isFull) "退出全屏" else "全屏",
                    isFullMode = isFull,
                    audioOn = (state.playbackSource?.audios?.size ?: 0) > 1,
                    qualityOn = (state.playbackSource?.qualityOptions?.size ?: 0) > 1,
                    onTogglePlay = viewModel::togglePlayPause,
                    onAudioClick = onShowA,
                    onQualityClick = onShowQ,
                    onSpeedClick = onShowSp,
                    onFullClick = onToggleFull,
                    modifier = Modifier.onSizeChanged { onCtrlBarHeightChange(it.height) }
                )
                // 底部留白：内嵌 8（与页面侧边距同口径）/ 全屏 16（托住手势导航条）
                Spacer(Modifier.height(if (isFull) BiliSpacing.lg else BiliSpacing.sm))
            }
        }
    }
}

/**
 * 时长解析：引擎给的 `duration` 优先，其次进度流里的 `durationMs`，再退到详情给的时长。
 *
 * 抽成一处是因为 [PlayerTimeTextLeaf] 与 [PlayerProgressLeaf] 都要它 —— 两个叶子各自
 * 订阅进度（这是性能修复的目的），但"时长怎么取"必须只有一套口径。
 */
@Composable
private fun resolveDurationMs(
    player: Player?,
    progressDurationMs: Long,
    sourceDurationMs: Long?
): Long = player?.duration
    ?.takeIf { it > 0L }
    ?: progressDurationMs.takeIf { it > 0L }
    ?: sourceDurationMs?.coerceAtLeast(0L)
    ?: 0L

/**
 * 时间文本叶子（控制条那一排里唯一吃 `weight` 的可变宽孩子）。
 *
 * **为什么单独成函数**（2026-10-16「滑动/运行卡顿」性能修复）：`viewModel.playbackProgress`
 * 是 500ms 一跳的流，此前在 [PlayerCtrlBarHost] 顶部 collect ⇒ 整个宿主（含整条控制条）
 * 每 500ms 重组一次，而宿主是常驻的。订阅下沉到本叶子后，进度更新只重组这一小块。
 *
 * 拖动优先链 `dragSeekMs ?: gestureSeekMs ?: progress.positionMs` **逐字保留**：
 * 手指按着时显示手指位置、手势 seek 期间显示手势位置、否则显示真实进度。
 *
 * `RowScope` 扩展而不是普通函数：`weight` 是 `RowScope` 的成员，写在函数体内而不是
 * 由调用方传 modifier 进来 —— 后者会把"吃权重"这件事从函数体里挪走，守卫与读者都
 * 看不到（原来那处注释明确要求"必须吃 weight"，形态要留在原处）。
 */
@Composable
private fun RowScope.PlayerTimeTextLeaf(
    viewModel: VideoViewModel,
    player: Player?,
    sourceDurationMs: Long?,
    dragMs: () -> Long?,
    gestureSeekMs: Long?
) {
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val durationMs = resolveDurationMs(player, progress.durationMs, sourceDurationMs)
    val dragSeekMs = dragMs()
    val barMs = dragSeekMs ?: gestureSeekMs ?: progress.positionMs
    // L3 只读信息：白 0.92 + labelSmall(Light, 11sp) + tnum。
    // 层级理由见 [lightTabularLabelSmall] 的 KDoc。
    //
    // ## 2026-10-18 第 33 轮：**极小衬底删除 → 裸文字 + 投影**
    // 上一轮为"渐变降浅"给时间文字加了一层 `drawBehind` 的 45% 黑胶囊（左右各外扩 4dp）。
    // 本轮用户要求「遮罩全部去除」，且同屏控件已统一成**同尺寸圆钮**；只剩这一小块
    // 黑色圆角底会读作"半颗圆钮"，正是"风格没统一"的残留。
    // ⇒ 删掉衬底，改与其余信息文字同一口径：**裸文字 + 投影**（[playerInfoStyle]）。
    // 时间文本是这一排里唯一**可读不可点**的元素，靠投影而不是靠"一块底"保持层级。
    //
    // ⚠️ `weight(1f)` 挂在**文本**上（不是 Box）：这一排的可变宽元素必须是文字本身，
    // 否则空间不足时亏空会落到右下角全屏图标上（2026-10-15 真机实测，见下方注释）。
    Text(
        text = formatPlaybackTime(barMs, durationMs),
        color = PlayerSecondaryText,
        style = playerInfoStyle(lightTabularLabelSmall()),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .weight(1f)
            // 2dp 是文字与两侧控件的最小呼吸位（非设计标度档位，沿用上一轮既有写法）
            .padding(horizontal = 2.dp)
    )
}

/**
 * 进度线叶子：订阅进度、算滑条值、算 SponsorBlock 标记，渲染 [PlayerProgressBar]。
 *
 * **渲染位置由宿主的控制层决定**（iOS-2）：它现在挂在 `PlayerCtrlBarHost` 的
 * `AnimatedVisibility(showCtrl)` 块**内部**（2026-10-17 本批**已删掉原先的胶囊底**）——
 * 控制层隐藏时这一整块都不渲染（用户 2026-10-02 口径：不要贴底常驻的细进度条）。
 *
 * 与 [PlayerTimeTextLeaf] 同一理由（见那边的 KDoc）：把 500ms 的进度订阅收进最小作用域。
 *
 * `onSeekDone` 块**整体从宿主搬来、内容一字未改** —— `SeekIntentSeparationGuardTest`
 * 锚定 `onSeekDone = {` 字面并要求其后 8 行内出现**用户主动 seek 的入口调用**；
 * 那是播放页唯一的用户 seek 入口，块结构或位置变了守卫就失去锚点。
 */
@Composable
private fun PlayerProgressLeaf(
    viewModel: VideoViewModel,
    player: Player?,
    sourceDurationMs: Long?,
    isPlaying: Boolean,
    interactive: Boolean,
    dragMs: () -> Long?,
    gestureSeekMs: Long?,
    onDragMsChange: (Long?) -> Unit,
    onShowCtrlChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val durationMs = resolveDurationMs(player, progress.durationMs, sourceDurationMs)
    // 拖动优先链：手指按着 > 手势 seek > 真实进度（顺序即优先级，勿动）。
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
    val onSeekChange: (Float) -> Unit = { frac ->
        onShowCtrlChange(true)
        onDragMsChange((durationMs * frac).toLong())
    }
    PlayerProgressBar(
        value = sliderVal,
        enabled = durationMs > 0L,
        playing = isPlaying,
        marks = sbMarks,
        durationMs = durationMs,
        interactive = interactive,
        onValueChange = onSeekChange,
        onSeekDone = {
            val next = dragMs()
            if (next != null) {
                // PB-02：**用户主动** seek 的唯一入口 → 走关键帧吸附（经 seekUserInitiated）；
                // 内部跳转（SponsorBlock/自动片尾）走精确的 seekTo，两者刻意分开。
                viewModel.seekUserInitiated(next)
            }
            onDragMsChange(null)
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerCtrlBar(
    playText: String,
    /**
     * 当前是否正在播放。白线语言把主操作从「文字胶囊」改成**图标钮**，
     * 图标形态因此需要这枚布尔（[playText] 只剩 `contentDescription` 一个用途）。
     */
    isPlaying: Boolean,
    /**
     * 时间文本的槽位（不是文本本身）。
     *
     * 必须是 `RowScope` 的无参 lambda：时间文本要在**自己的最小作用域**里订阅进度
     * （见 [PlayerTimeTextLeaf]），同时又要吃 `Row` 的 `weight` —— 把 collect 留在宿主、
     * 只传 String 回来，就等于宿主仍然每 500ms 重组，性能修复白做。
     */
    timeSlot: @Composable RowScope.() -> Unit,
    audioText: String,
    qualityText: String,
    speedText: String,
    fullText: String,
    /** 当前是否处于全屏：决定右下角那颗图标是「全屏」还是「退出全屏」。 */
    isFullMode: Boolean,
    audioOn: Boolean,
    qualityOn: Boolean,
    onTogglePlay: () -> Unit,
    onAudioClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onFullClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ## 2026-10-18 第 33 轮：**统一为同尺寸圆钮**
    //
    // 用户原话「视频播放页的其他控件风格没有统一」。此前这一排混着三种形态：
    // 播放=白 18% 圆底、档位组=白 20% 圆钮、全屏=白 20% 圆钮 —— 三种底色；
    // 而顶栏又是裸 IconButton 与黑色文字胶囊。现在**全页只剩一种控件形态**：
    //   `PlayerIconButton(chip = PlayerChipScrim)`，直径 = [PlayerCtrlRowHeight]（34dp），
    //   纯图标、文字只进 `contentDescription`。
    // 顶栏与底部控制行共用**同一个** [PlayerChipScrim] 常量（只在文件顶部定义一次）。
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BiliSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── 控件行排布（2026-10-11 重排 + 2026-10-18 形态统一）────────────
                //
                // 信息 → 主操作 → 选项组 → 贴角图标 四段，但**四段里的可点件形状完全一致**：
                //   [播放圆钮][时间（裸文字+投影）] ……留白…… [音轨|画质|倍速 圆钮组] [全屏圆钮]
                // 时间文本是**唯一**不参与圆钮语言的可点性元素 —— 它本来就不可点（L3 只读），
                // 所以"不统一"在这里读作层级，而不是风格混杂。
                PlayerIconButton(
                    icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = playText,
                    onClick = onTogglePlay,
                    chip = PlayerChipScrim
                )
                // 时间文本：订阅在叶子内部（见 [PlayerTimeTextLeaf]），这里只给槽位 ——
                // 叶子是 RowScope 扩展，所以它的 `weight(1f)` 作用在这一排上。
                timeSlot()
                PlayerSegmentGroup(
                    segments = buildList {
                        // 2026-10-18 第十九轮：档位组改为**独立圆钮 + 图标**，
                        // 档位值不再画在圆里（放不下 `480P`/`1.0x`），改由
                        // `contentDescription` 带给读屏、点开后由面板选值。
                        // 音轨：单音轨时不渲染（点开也只有一条，纯技术黑话）。
                        if (audioOn) {
                            add(
                                PlayerSegment(
                                    icon = Icons.Rounded.Audiotrack,
                                    contentDescription = "音轨：$audioText",
                                    onClick = onAudioClick
                                )
                            )
                        }
                        add(
                            PlayerSegment(
                                icon = Icons.Rounded.HighQuality,
                                contentDescription = "画质：$qualityText",
                                onClick = onQualityClick
                            )
                        )
                        add(
                            PlayerSegment(
                                icon = Icons.Rounded.Speed,
                                contentDescription = "倍速：$speedText",
                                onClick = onSpeedClick
                            )
                        )
                    }
                )
                // 全屏：与其余控件统一为**圆钮**（第十九轮起源、第 33 轮完成）——
                // 用户口径「如果都用圆形按钮，就把全屏等控件都做出圆形」。
                // 图标仍用 `outlined`（全屏这颗只有 outlined/filled 两套形状，
                // 圆底已经提供了"同一套语汇"的观感，不必为凑形状换字形）。
                PlayerIconButton(
                    icon = if (isFullMode) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = fullText,
                    onClick = onFullClick,
                    chip = PlayerChipScrim
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
    /**
     * 是否**接受拖动**。与 [enabled] 分开：视觉"活的"由 [enabled]（只看时长）决定，
     * 而手势另有闸门 —— 全屏态从底部起手的上滑是翻页手势，进度条若全程可拖会把它吞掉
     * （垂直拖动要让位给 pager）。iOS-2 之后进度行随控制层显隐，`showCtrl` 为真时
     * 才可能有人拖它，语义与调用方（[PlayerProgressLeaf]）传 `interactive = showCtrl` 一致。
     */
    interactive: Boolean = enabled,
    onValueChange: (Float) -> Unit,
    /**
     * 拖拽松手回调。**名字与守卫一致**（`SeekIntentSeparationGuardTest` 锚定
     * `onSeekDone = {` 字面，并要求其后 8 行内出现「用户主动 seek 的入口调用」）：
     * 播放页的用户 seek 入口只有这一处，改名会让守卫失去锚点。
     *
     * ⚠️ 本注释**刻意不写出那个函数名带左括号的形态** —— 守卫的第一条断言
     * （`callSites.size == 1`）虽然会过滤注释行，但把调用字面量抄进注释仍是坏习惯：
     * 一旦哪天这里被改成 `//` 之外的形式或守卫改用不过滤的扫描，就会凭空多出一个
     * "入口"。要引用就写函数名本身，别带调用括号。
     */
    onSeekDone: () -> Unit,
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
            // 触摸区比视觉轨道高得多（细轨只有 2dp，直接用轨道高度做命中区基本点不中）。
            // 高度 = `BiliSpacing.xxxl`(32dp)，即规格里的"进度行 32"（2026-10-17 起其上不再套胶囊底）。
            .height(BiliSpacing.xxxl)
            .onSizeChanged { barWidthPx = it.width }
            .pointerInput(enabled, interactive) {
                if (!enabled || !interactive) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        onValueChange((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        dragging = false
                        onSeekDone()
                    },
                    onDragCancel = {
                        dragging = false
                        onSeekDone()
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
        // 拖动时滑块放大 —— 上游 `PlayerControlGlassThumb` 的 `pressScale`（spring 0.8/400，
        // 拖动 1.18、松手回 1.0）。本仓用 `Animatable` + **默认 spring**（不写死参数，
        // 避免新增硬编码动画时长触发 `BbspaceHardcodedAnimationDuration`）；
        // 动效设为「关」时直接吸附：无障碍要"没有动效"就不该再跑一条跟随动画。
        val anim = LocalAnimations.current
        val thumbScale = remember { Animatable(1f) }
        LaunchedEffect(dragging, anim.reduceMotion) {
            val target = if (dragging) ThumbDragScale else 1f
            if (anim.reduceMotion) {
                thumbScale.snapTo(target)
            } else {
                thumbScale.animateTo(target)
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

            // 0) 轨道**衬底**（2026-10-18 第 33 轮：整层遮罩删除后，它是轨道对比度的**唯一**来源）
            //
            // 此前轨道坐在底部渐变的 α≈0.74~0.77 上；本轮把渐变整条删掉后，
            // 未播轨（白 0.72）在纯白画面上只剩 2.32:1（非文本门槛 3:1）。
            //
            // 修法不是把遮罩加回去，而是给**轨道自己**一条紧贴的暗底：
            // 高度 = 轨道高 + 上下各 2dp（共 6dp），圆端，α 由 0.45 提到 **0.52**
            // ⇒ 白 0.72 轨道 = **3.05:1** ✅（见 [PlayerTrackBedScrim] 的 KDoc）。
            // 面积只有轨道宽度 × 6dp —— 与旧方案"整条 118dp 黑渐变"是完全不同的量级。
            val badgePad = 2.dp.toPx()
            drawRoundRect(
                color = PlayerTrackBedScrim,
                topLeft = Offset(trackGeometry.left, trackTop - badgePad),
                size = Size(trackGeometry.width, trackPx + badgePad * 2f),
                cornerRadius = CornerRadius((trackPx + badgePad * 2f) / 2f)
            )

            // 0) ⚠️ 原「阴影兜底」（`PlayerSliderShadow`）**已在 iOS-2 删除**：
            // 那是"进度线常驻贴画面底边"时代的兜底（遮罩随控制条隐没、白细线落在浅色画面上读不清）。
            // 再叠一道同形阴影只会把细线周边压暗成"双描边" —— 对比度职责现在由上面那条衬底承担。

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
                    color = if (enabled) PlayerSliderActiveTrack else PlayerSliderDisabledActiveTrack,
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

        // 4) 滑块：**实心白圆**（2026-10-17「无模糊语言」方向二 §6-6）。
        //
        // 旧版是**两个节点**：3) 玻璃圆盘（`playerControlGlass` + 圆形 scrim）与 4) 独立
        // 白环 Canvas，两者靠"必须同心同倍率"的**注释约定**对齐 —— 约定没有强制力，
        // 半径按 `thumbPx` 各自算死，是拖动态破相（真机手抓到 39×11 扁形）的结构性来源。
        // ⇒ 合并成**一个** Canvas：一个节点、一次绘制，同心由构造保证，不可能再失配。
        //
        // ⚠️ `thumbScale.value` **仍必须在绘制期读**（Canvas 的 draw lambda 天然是绘制期；
        // 放组合期会让拖动期间整条进度条按帧重组）。命中区仍在外层 32dp 高 Box 的
        // pointerInput 上，滑块只是视觉件，放大不改手势范围。
        // ⚠️ `:3043-3049` 的 `thumbScale`（`Animatable` + `snapTo`/`animateTo`/`ThumbDragScale`）
        // 是 `VideoPlayerRenderCostGuardTest.seekThumbScalesWhileDragging` 钉住的四件套，
        // **删玻璃时不要顺手把它一起删掉**。
        if (enabled) {
            Canvas(modifier = Modifier.fillMaxWidth().height(PlayerSliderThumbSize)) {
                val s = thumbScale.value
                drawCircle(
                    color = Color.White,
                    radius = thumbPx / 2f * s,
                    center = Offset(trackGeometry.filledRight(), size.height / 2f)
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
                        // 拖动态滑块的**实际**外半径：与滑块 Canvas 同源读 `thumbScale`（布局期值），
                        // 放大 1.18 时偏移量同步放大 —— 这就是"用 thumbW 而非固定 dp"的自洽点。
                        val thumbOuter = thumbPx / 2f * thumbScale.value
                        val gap = BiliSpacing.xs.toPx()
                        val maxX = (barWidthPx - bubbleWidthPx).coerceAtLeast(0f)
                        // ⚠️ 与 `d-ios11` §4.5.2 的公式**有意不同**：规格给的偏移是
                        // `thumbW/2 + gap`（≈19px），但气泡本身宽 52dp≈182px —— 只偏 19px 时
                        // 气泡照样把 42px 的滑块整块盖住，数学上不可能"露出来"（那正是
                        // 真机实测"只剩 12×30px 白弧"的原因）。⇒ 按**目标**实现：
                        // 偏移量取 `thumbOuter + gap + bubbleW`（整块让开），并做左右翻转。
                        // 优先放**左侧**：拖动时手指压在滑块上，左侧空着，气泡既不盖滑块
                        // 也不盖手指；起点附近左侧放不下 ⇒ 翻到右侧；两端按边界夹住
                        // （`d-ios11` §4.5.2 注 2 已接受"两端气泡与滑块分离"这个代价）。
                        val leftX = centerX - thumbOuter - gap - bubbleWidthPx
                        val rightX = centerX + thumbOuter + gap
                        IntOffset(
                            x = (if (leftX >= 0f) leftX else rightX)
                                .coerceIn(0f, maxX)
                                .roundToInt(),
                            y = 0
                        )
                    }
                    .clip(PlayerControlShape)
                    .background(PlayerSliderPreviewScrim)
                    .padding(horizontal = BiliSpacing.sm, vertical = 3.dp)
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

// —— 控件层颜色（2026-10-16 iOS-2 起源；2026-10-18 第 33 轮收敛为单一 chip）——
//
// ⚠️ **位置约束（勿上移）**：本组常量刻意放在文件**后段**（`GlassMountRegistryTest` 登记的
// `drawBackdrop` / `layerBackdrop` 两个挂载点**之后**）。放回顶部常量区会把那两个挂载点
// 整体下推，登记表就要跟着改（该测试的 KDoc 里已记过多次同类漂移）。
//
// 语言仍是「黑底 + 白前景」（见文件顶部约定），**只有黑/白 alpha，不引入彩色**。
// 2026-10-18 第 33 轮起，常驻底盘只剩**一个**：[PlayerChipScrim]（定义在文件顶部常量区，
// 因为它同时被顶栏与底部控制行使用）。此处的白 alpha 只剩**按下叠色**。

/**
 * 控件**按下反馈**的叠色（白 18%）。
 *
 * 见 [rememberCtrlPress]：叠色画在 `clip` 之后、`clickable` 之前，随 0.97 缩放一起缩。
 * 压在 [PlayerChipScrim]（黑 0.58）上时亮度只提到 0.7456 的等效底，
 * 白图标仍 ≈6.3:1。
 */
private val PlayerCtrlPressOverlay = Color.White.copy(alpha = 0.18f)

/**
 * 控件行**按下反馈**的缩放。用 `graphicsLayer` 在绘制期读值，逐帧推进只重绘、不重组宿主
 * （与 [PlayerProgressBar] 的 `thumbScale` 同配方）。
 */
private const val PlayerCtrlPressScale = 0.97f

/**
 * 播放器**所有控件**共用的按钮直径（34dp）。**单一来源**：
 * `PlayerIconButton` 的「有 chip」分支、顶栏圆钮、控件行圆钮全部取它。
 *
 * 2026-10-18 第 33 轮：`CtrlBtn`（承载文字的连体段）随连体胶囊语言一起删除，
 * 于是这个常量的**唯一**消费者就是 [PlayerIconButton] —— 这正是用户要的"同尺寸"。
 */
private val PlayerCtrlRowHeight = 34.dp

/**
 * 控制层的**内嵌态层高**（118dp）。
 *
 * 构成，自上而下：32 顶部淡出 + 32 进度行 + 12 间距 + 34 控件行 + 8 底部留白。
 * 层级差：内嵌态下方紧接页面内容 ⇒ 底部留白与页面侧边距同口径（`BiliSpacing.sm`）。
 *
 * ## 2026-10-18 第 33 轮：它**只决定垂直占位**，不再承担任何对比度
 * 旧 KDoc 里"这个组合同时是进度轨道对比度的前提"（轨道落在渐变的哪一段）**已失效** ——
 * 控制层没有任何整层渐变了。层高保留是因为：控制层隐藏时不该占位、出现时
 * 进度行与控制行需要固定的垂直节奏（并且 [VideoPlayerOverlay] 的
 * `onCtrlBarHeightChange` 靠它上报，会话信息层据此上移让位）。
 *
 * 全 token 表达式（不含 dp 字面量）：`DesignTokenLintTest` 的棘轮不会因它变化。
 */
private val PlayerCtrlLayerHeight =
    BiliSpacing.xxxl + BiliSpacing.xxxl + BiliSpacing.md + PlayerCtrlRowHeight + BiliSpacing.sm

/**
 * 控制层的**全屏态层高**（126dp）：与 [PlayerCtrlLayerHeight] 的唯一差别是底部留白
 * 由 `sm`(8) 提到 `lg`(16) —— 全屏态下方没有页面内容，且要托住手势导航条。
 *
 * 2026-10-18 第 33 轮起两态的差异**只影响留白**（对比度不再随层高变化）。
 */
private val PlayerCtrlLayerHeightFull =
    BiliSpacing.xxxl + BiliSpacing.xxxl + BiliSpacing.md + PlayerCtrlRowHeight + BiliSpacing.lg

/**
 * 控件**按压态**：状态 + 修饰符（一次组合算一份，[PlayerIconButton] 使用）。
 *
 * ## 为什么从"纯修饰符"改成"返回状态的助手"
 * 早期版本需要把按压期间的 dim 文字提亮；连体胶囊语言删除后，前景只剩白图标，
 * 但**返回值形态保留**：调用方要按 `pressed` 决定内容（例如未来的态变体）。
 *
 * ## 为什么不用 `animateFloatAsState`
 * 它会强制**组合期**读值 ⇒ 每帧重组调用方；本仓 `:feature:video` 的
 * `BbspaceAnimateAsState` Lint 只在 `VideoDetailSummary` 留了豁免。这里照
 * [PlayerProgressBar] 的 `thumbScale` 配方走 `Animatable` + 绘制期读值。
 *
 * ## 修饰符顺序为什么不能换（`clip` → 本件 → `clickable`）
 * ① 叠色必须在 `clip` **之后**才被圆角裁住；
 * ② 必须在 `clickable` **之前**，否则它画在触摸层之上；
 * ③ `graphicsLayer` 放在叠色**之前**，缩放才会连叠色一起缩（放后面只缩内容，高亮会甩出去）。
 *
 * [pressShape] 默认 [PlayerControlShape]；**圆钮传 `CircleShape`**（[PlayerIconButton] 的默认值
 * 已是圆），否则 34dp 的方角叠色会从圆底四角露出来。
 */
private class CtrlPressState(val pressed: Boolean, val modifier: Modifier)

@Composable
private fun rememberCtrlPress(
    enabled: Boolean,
    onClick: () -> Unit,
    pressShape: Shape = PlayerControlShape,
    /**
     * 是否做 0.97 缩放。**连体档位段传 false**：段在组内缩放会露出缝（组底从缝里透出来），
     * 而"整块高亮"才是连体控件该有的按压反馈；圆形主操作/贴角图标传 true。
     */
    pressScale: Boolean = true
): CtrlPressState {
    if (!enabled) {
        return CtrlPressState(false, Modifier.clickable(enabled = false, onClick = onClick))
    }
    val anim = LocalAnimations.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed, pressScale) {
        val target = if (pressed && pressScale) PlayerCtrlPressScale else 1f
        // 无障碍降级：动效关闭时瞬时到位，不留"半按"的中间态。
        if (anim.reduceMotion) scale.snapTo(target)
        else scale.animateTo(target, tween(durationMillis = anim.short, easing = anim.standard))
    }
    val modifier = Modifier
        .then(
            if (pressScale) {
                Modifier.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
            } else {
                Modifier
            }
        )
        .then(
            if (pressed) Modifier.background(PlayerCtrlPressOverlay, pressShape)
            else Modifier
        )
        .clickable(interactionSource = interactions, indication = null, onClick = onClick)
    return CtrlPressState(pressed, modifier)
}

/**
 * `CtrlBtn`（承载文字的连体档位段）**已于 2026-10-18 第 33 轮删除**。
 *
 * ## 为什么删（不是忘在注释里）
 * 用户口径是「控件风格统一为同尺寸圆形」+「遮罩全部去除」。`CtrlBtn` 是**文字胶囊**
 * 形态（连体分段 + 17dp 方圆角 + 白 0.60/1.00 两档前景），与圆钮是两套语言；
 * 它最后的调用点（档位组）在第十九轮已换成 [PlayerSegmentGroup] 的圆钮。
 * 保留一个零调用点的文字控件，只会让它哪天被"顺手再用回去"。
 * ⇒ 正文、`PlayerCtrlOptionDim`（它的专属前景 token）、`RectangleShape` 的按压分支
 * 一起删除；**不存在**"换名保留"。
 */

/**
 * 把「音轨 / 画质 / 倍速」三个档位并成**一组同尺寸圆钮**。
 *
 * ## 2026-10-18 第 33 轮：**全部统一到 [PlayerChipScrim]**
 * 上一轮已把连体胶囊拆成独立圆钮，但底色还是白 20%（与播放/全屏的底色不一致）。
 * 本轮整层遮罩删除后，所有控件共用同一个黑 0.58 chip ⇒ 这一组不再有自己的颜色，
 * 尺寸/形状/按压反馈全部与 [PlayerIconButton] 一致，只有"成组靠近"这一件事由
 * [PlayerCtrlSegmentGap] 表达**语义**（这几个是可比较的档位），不再靠形状或颜色区分。
 *
 * ⚠️ **圆里放不下 `480P` / `1.0x` 这种值文本**（已知并接受）：
 * 因此本函数只收**图标**，档位值经 `contentDescription` 给读屏、点开后由面板选值。
 * 这也是上游 BiliPai 的做法（`PlayerControlGlassDock` + 点开面板选值）。
 */
@Composable
private fun PlayerSegmentGroup(
    segments: List<PlayerSegment>,
    modifier: Modifier = Modifier
) {
    if (segments.isEmpty()) return
    Row(
        modifier = modifier.height(PlayerCtrlRowHeight),
        horizontalArrangement = Arrangement.spacedBy(PlayerCtrlSegmentGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segments.forEach { seg ->
            // 每段 = 一颗圆钮：与播放/全屏/顶栏**同尺寸、同底色、同按压圆高亮**。
            PlayerIconButton(
                icon = seg.icon,
                contentDescription = seg.contentDescription,
                onClick = seg.onClick,
                chip = PlayerChipScrim
            )
        }
    }
}

/**
 * 档位圆钮之间的缝（`BiliSpacing.xs` = 4dp）。
 *
 * 为什么需要这条缝：同色圆如果**贴在一起**，圆与圆之间的凹口会被读成
 * "一个被切了几刀的胶囊"；留 4dp 后彼此的圆弧各自完整，才读作独立按钮。
 */
private val PlayerCtrlSegmentGap = BiliSpacing.xs

/** [PlayerSegmentGroup] 的一段：一段 = 一个"带值的档位"，现在只承载**图标**。 */
private data class PlayerSegment(
    /** 图标（档位值不再显示在圆内，见 [PlayerSegmentGroup] 的 KDoc）。 */
    val icon: ImageVector,
    /** 无障碍描述：必须**带当前值**，否则读屏用户无法知道现在是什么档位。 */
    val contentDescription: String,
    val onClick: () -> Unit
)

/**
 * 播放器**所有**控件的唯一形态：**同尺寸圆钮**（2026-10-11 起源；2026-10-18 第 33 轮收敛）。
 *
 * ## 为什么只剩一种形态
 * 历史上这里有两档（有底圆 / 无底贴角图标），而同屏的顶栏又用裸 `IconButton` +
 * `PlayerPillChip` 文字胶囊 —— 用户第 33 轮原话「视频播放页的其他控件风格没有统一」
 * 指的就是这件事。现在**顶栏与底部控制行的每一个控件都走本函数**：
 * 直径 = [PlayerCtrlRowHeight]（34dp）、底色 = 调用方传的 [chip]（全页同一个
 * [PlayerChipScrim]）、纯图标、文字只进 `contentDescription`。
 *
 * ## 两档形态（[chip]）—— 只为"不需要底盘"的极端场景保留
 * - **有底**（`chip != null`）：收成 34dp 的**圆**。**播放页目前所有控件都走这一档**。
 * - **无底**（默认 `null`）：给"画面上已经没有可读性风险"的宿主留的口子
 *   （例如将来把播放器控件画在纯黑 letterbox 里）；不加底盘时用内边距撑出触摸宽度。
 *
 * ⚠️ 图标尺寸 **22dp 保持不变**（2026-10-17）：真机手 FIX-01 的实测基线就按
 * `22dp @560dpi ⇒ 字形 45×45px` 建立，改尺寸会作废那批已采证据。
 */
@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 常驻底盘：**播放页全页统一传 [PlayerChipScrim]**；`null` = 无底（见 KDoc）。 */
    chip: Color? = null,
    /**
     * 交互形状。2026-10-18 真机反馈「圆形控件需保持统一」后**默认改圆**：
     * 纯图标动作的按压高亮也必须是圆，否则 34dp 的方角叠色会从圆底四角露出来。
     */
    chipShape: Shape = CircleShape
) {
    val press = rememberCtrlPress(enabled = true, onClick = onClick, pressShape = chipShape)
    Box(
        modifier = modifier
            .then(
                if (chip != null) Modifier.size(PlayerCtrlRowHeight)
                else Modifier.heightIn(min = PlayerCtrlRowHeight)
            )
            .clip(chipShape)
            .then(if (chip != null) Modifier.background(chip, chipShape) else Modifier)
            .then(press.modifier)
            // 无底盘时才需要横向内边距撑出触摸宽度；圆底本身就是 34dp 的触摸目标。
            .then(
                if (chip != null) Modifier
                else Modifier.padding(horizontal = BiliSpacing.smPlus, vertical = BiliSpacing.xs)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
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
            .padding(end = BiliSpacing.md, bottom = if (showCtrl) 88.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm),
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
