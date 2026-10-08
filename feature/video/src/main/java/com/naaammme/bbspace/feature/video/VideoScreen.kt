package com.naaammme.bbspace.feature.video

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.media3.common.util.UnstableApi
import com.naaammme.bbspace.core.designsystem.component.BiliSheetGrabber
import com.naaammme.bbspace.core.designsystem.theme.biliSheetShape
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.core.designsystem.component.VideoDetailInfoSkeleton
import com.naaammme.bbspace.core.designsystem.component.topSolidProgressiveFade
import com.naaammme.bbspace.core.designsystem.color.VideoCardCoverColorStore
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessTransitionState
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.model.PlaybackSource
import com.naaammme.bbspace.core.model.PlaybackStream
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoDownloadKind
import com.naaammme.bbspace.core.model.VideoDownloadOption
import com.naaammme.bbspace.core.model.VideoDownloadOptions
import com.naaammme.bbspace.core.model.VideoDownloadRequest
import com.naaammme.bbspace.feature.video.detail.VideoDetailPage
import com.naaammme.bbspace.feature.video.player.PortraitSwipePager
import com.naaammme.bbspace.feature.video.player.VideoPlayerPane
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
// ── 本轮三项界面优化新增 ────────────────────────────────────────────────
// 形变（aspectMorph / fullscreenMorph）的值一律在**布局期 / 绘制期**读取
// （playerMorphHeight 的 measure 块、graphicsLayer / offset{} 块、
// onGloballyPositioned 回调），不新增 animate*AsState、不在组合期读动画值，
// 与 FrameBudgetLintTest 的口径一致（见两个 Animatable 处的注释）。
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal val speedOps = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

// K-7（2026-10-04 审查报告）：这里原有一个 `private const val MASK_FIRST_FRAME_GRACE_MS = 120L`
// 与它的说明（"详情遮罩等首帧的宽限期"）。详情区遮罩已整体移除（见下面
// `为什么这里没有详情区遮罩了` 那段），该常量**全仓零引用**，属于"逻辑删了、常量忘了删"，
// 故删除；播放器区域的封面垫底仍在（`CoverSettleBackdrop`，按首帧淡出 + 2.5s 超时兜底）。

/**
 * 播放器区域的两种形状常量（PF-06）。
 *
 * 取值只依赖 `BiliRadius`（对象上的普通 dp `val`，不读主题状态），进程内恒定；
 * 写在 composable 体内就是**每次重组新建一份 Shape**，而它同时被 `clip` 与
 * 无缝 morph 的动画两端读取 —— 两端各自新一份还可能算出不同实例。
 */
// **播放区域 = 直角（用户定稿 2026-10-07）**：上下沿都不做圆角。
// 缘由：在"画面满宽满框"的几何下，圆角会在上沿被状态栏那一带黑底读成"缺角"、
// 下沿又会与简介区的底色打架，试过对齐半径后用户判定不好看 ⇒ 直接方形。
// 视频表面（SurfaceView）不再需要 outline 裁剪（直角）。
private val ExpandedPlayerShape = RectangleShape
private val CompactPlayerShape = RectangleShape

@Suppress("UnsafeOptInUsageError")
@UnstableApi
@Composable
fun VideoScreen(
    onBack: () -> Unit,
    onGoHome: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    onOpenDownloadCache: () -> Unit,
    onStartDownload: (VideoDownloadRequest) -> Unit,
    viewModel: VideoViewModel,
    hostExpanded: Boolean = true,
    // 详情页封面垫底（仅视频详情 Activity 传入；覆盖层路径保持 null 零改动）：
    // 铺在播放器区域（与播放器容器同边界同圆角），首帧渲染后交叉淡出。
    // 不做全屏铺底——系统无缝动画的 leash 终态交给真实页面接管，避免长时间"预览图铺屏"。
    coverBackdropUrl: String? = null,
    coverBackdropLarge: Boolean = false,
    // 刷视频模式（首页顶栏入口）：常驻全屏上下滑，没有紧凑态——返回键/「全屏」钮
    // 都是关闭页面；会话锚点/队列由 story 流（bootstrapStorySession）负责。
    storyMode: Boolean = false,
    /**
     * 退出 story 模式（需求 3：刷视频会话里点「还原」应当进到**当前这支**的详情页）。
     *
     * 为什么不能只调 `onBack()`：story 模式下这一页是"顶栏入口直开"的，`onBack()` 会把
     * **整个详情页**关掉（用户拿不到详情/评论）；正确语义是"退出会话、露出详情页"。
     * 传 `null` 时退化成老行为（`onBack()`），保证覆盖层路径不受影响。
     */
    onExitStoryMode: (() -> Unit)? = null
) {
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle(initialValue = PlayerSettingsState())
    val act = LocalActivity.current
    val context = LocalContext.current
    // 播放器容器在窗口坐标系中的矩形与收尾圆角（垫底形变终点），三个布局分支各自上报
    var coverBackdropFrame by remember { mutableStateOf<BackdropFrame?>(null) }

    // 互动动作（点赞/投币/收藏/三连）结果提示
    LaunchedEffect(viewModel) {
        viewModel.msg.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val isExpandedWidth = windowSizeClass.isWidthAtLeastBreakpoint(840)
    // 「省电模式」（视频用 SurfaceView）：外层布局必须据此避开 Compose `clip`/`graphicsLayer`
    // ——它们是图层，会把独立 surface 挖空（画面全黑）。圆角改由 PlayerView 的 outline 裁剪，
    // 位移改用 layout 期 offset；代价是控件真模糊与进全屏的淡入缩放动效让位。
    val surfacePowersave by viewModel.playerSurfaceView.collectAsStateWithLifecycle()
    // SX-03：横屏（**非全屏**）时，视频画面下方要给一个悬浮「全屏」按钮（用户需求③：
    // "不用去顶栏点"）。判据用 `Configuration.orientation` 而**不是**宽度分级：
    // 横屏手机宽度约 800dp，`isExpandedWidth`（840dp）仍为 false、走的是紧凑分支
    // ——「横屏」与「展开宽度」是两件事，混用会让按钮在横屏手机上不出现。
    val isLandscapeWindow =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val themeUsesDarkSystemBarIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f
    var isFull by rememberSaveable { mutableStateOf(storyMode) }
    var downloadSheetOn by rememberSaveable { mutableStateOf(false) }
    val fullOn = hostExpanded && isFull
    // 刷视频模式是常驻全屏（isFull 初始即 true），swipe 会话也常驻
    val effectiveStoryMode = storyMode && hostExpanded
    // Activity 无缝路径：详情本体**立即组合**（打开动画期间被纯背景色遮罩盖住，
    // 首帧组合在动画内完成），动画结束（或 1.2s 超时兜底）后遮罩直接淡出——
    // 简介与详情随视频画面同步出现，不再有「动画结束→组合→再淡入」的串行等待。
    // 遮罩只铺主题背景色，不画封面：封面连续性由播放器区域的 CoverSettleBackdrop
    // 负责，详情区再画一份会露出重复的预览图。详情区域 Box 恒占位、遮罩是覆盖层
    // ——播放器与布局尺寸全程不变。以视频 ids（aid/cid）为键，避免连续打开
    // 同源封面视频时错误复用上次的遮罩状态。
    val anim = LocalAnimations.current
    val videoIdsKey = videoState.ids
    // ── 为什么这里**没有**详情区遮罩了 ──────────────────────────────────────
    //
    // 旧实现用一层不透明遮罩（纯主题背景色）盖住详情区，等到"打开动画播完 + 播放器
    // 首帧/宽限期"再淡出。它的原始目的是"不露骨架/占位闪变"。
    //
    // 但那与现在的目标冲突，而且会**跳动**：详情区下方本来就已经渲染着真实的列表骨架
    // （`detailItems` 的 else 分支，`detailLoading` 默认 true）。遮罩里再画一份骨架 =
    // 两份骨架叠加，淡出时两层不同步 → 肉眼可见的跳动。
    //
    // 现在直接**不盖**：骨架从打开动画的第一帧就露出来（正是需求），遮罩移除后
    // 不存在"骨架 → 骨架"的交接，跳动随之消失。
    //
    // 遮罩原本还有一个副作用是"防止详情区出现重复封面"——这一点由播放器区域的
    // `CoverSettleBackdrop` 独立负责（它只铺播放器区域，不碰详情区），因此移除安全。
    val isPortraitVideo = remember(videoState.currentStream) {
        val stream = videoState.currentStream ?: return@remember false
        (stream.width ?: 0) in 1..<(stream.height ?: 0)
    }
    // 视频宽高比（w/h）：竖屏比例贴合预览框（#1）的唯一输入。
    // 与 isPortraitVideo 同源同键，避免两处各算一遍还可能算出不同结论。
    val videoAspect = remember(videoState.currentStream) {
        val stream = videoState.currentStream ?: return@remember null
        val width = stream.width?.takeIf { it > 0 } ?: return@remember null
        val height = stream.height?.takeIf { it > 0 } ?: return@remember null
        width.toFloat() / height.toFloat()
    }
    // ── 播放区形变状态机（三项界面优化共用）─────────────────────────────────
    // aspectMorph：预览框从「16:9 基准高度」连续过渡到「贴合视频比例的目标高度」。
    //   打开视频 / 切清晰度 / 切分P 让目标比例变化时重播，避免比例跳变。
    // fullscreenMorph：下滑进全屏（#3）的「紧凑高度 → 满屏」直接操纵进度 ——
    //   手势期间由手指位置 snapTo（播放带底边贴着手指生长），松手后 animateTo
    //   到 0（回弹）或 1（提交切全屏）；退出全屏时反向播放同一动画。
    //
    // 两个 Animatable 的值**只在布局期 / 绘制期读取**：playerMorphHeight 的
    // measure 块、graphicsLayer / offset{} 块、onGloballyPositioned 回调。
    // 形变全程零重组，也不新增 animate*AsState（FrameBudgetLintTest 棘轮）。
    val fullscreenMorph = remember { Animatable(0f) }
    val aspectMorph = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val morphSpec = tween<Float>(
        durationMillis = if (anim.reduceMotion) 1 else anim.morph,
        easing = anim.morphEasing
    )
    // 播放框展开（16:9 基准 → 贴合竖屏比例）用**临界阻尼弹簧**，不再用 tween：
    // 这段形变的距离极大（竖屏手机上从约 1/4 屏高长到近满屏宽的高度），
    // 200ms tween + 缓入缓动起步有迟滞、结束急停，真机读感是"猛地一跳"；
    // 弹簧起步即有速度、落地渐进收敛（无过冲），视觉时长 ~350-400ms，连贯得多。
    // stiffness 用 Low 档而不是 MediumLow：距离越长越要把时间摊开。
    // reduceMotion 不走弹簧（弹簧时长不可控），在下面的 effect 里直接 snapTo。
    val aspectSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
        visibilityThreshold = 0.001f
    )
    // 详情列表状态提升到本层：下滑手势要用它判断「列表还能不能向下滚」——
    // 能滚 = 手势让位给列表（详情区正常滚动）；不能滚 = 已在简介区顶边，
    // 向下拖才接管为进全屏形变。原状态在 VideoDetailPage 内按 aidKey 创建，
    // 提升后键语义不变（切视频仍重置），且平板/竖排两个分支共用同一份。
    val detailListState = remember(videoState.ids.aid.takeIf { it > 0L }) { LazyListState() }
    // SX-01：详情页当前显示的是不是「简介」页（pager 第 0 页）。由 [VideoDetailPage] 上报 ——
    // pager 与评论列表都创建在它内部，本层拿不到（见该参数 KDoc）。
    // 默认 true：未上报前按"简介页"处理 = 与改动前行为一致（零回归）。
    var detailIntroPageVisible by remember { mutableStateOf(true) }
    // 退出全屏时读最新宽度分级：平板/横屏全屏没有紧凑形变链，直接回零
    // （等价于原来的瞬时切换），否则竖向 compact 分支重挂后播放回缩动画。
    val isExpandedWidthState = rememberUpdatedState(isExpandedWidth)
    val enterFullscreen: () -> Unit = {
        scope.launch {
            fullscreenMorph.animateTo(1f, morphSpec)
            isFull = true
        }
    }
    val exitFullscreen: () -> Unit = {
        isFull = false
        scope.launch {
            if (isExpandedWidthState.value) {
                fullscreenMorph.snapTo(0f)
            } else {
                fullscreenMorph.animateTo(0f, morphSpec)
            }
        }
    }
    val openTarget = remember(viewModel) {
        { target: VideoTarget -> viewModel.openTarget(target) }
    }
    val switchPage = remember(viewModel) {
        { cid: Long -> viewModel.switchPage(cid) }
    }
    val switchEpisode = remember(viewModel) {
        { target: VideoTarget -> viewModel.switchEpisode(target) }
    }
    val downloadClick = remember { { downloadSheetOn = true } }
    val handleBack = {
        when {
            // 刷视频模式没有紧凑态：返回 = 关闭页面（finishWithHostState，
            // 系统按成对返回动画收起）
            effectiveStoryMode -> onBack()
            fullOn -> exitFullscreen()
            else -> if (!viewModel.popPage()) {
                onBack()
            } else {
                Unit
            }
        }
    }

    // ── 竖屏全屏「上下滑切换视频」（BiliPai 上游同款功能的 bbspace 化）──────────
    // 会话在进入全屏后锚定当时的视频（页 0），队列 = 其相关推荐的接力流。
    // 键里带 videoState.detail：detail 晚到（快速滑进全屏时详情尚未返回）也能补开
    // 会话；以 swipeAnchor == null 为判据，刷视频过程中 detail 不断变化也不会重置锚点
    // ——锚点必须稳定在「进入全屏那一刻的视频」，翻回页 0 才能回到起点。
    // 启用判据与画幅无关（横屏视频也能进，会话内锁竖屏 → letterbox 呈现），但**必须
    // 携带简介区的显式下滑请求**（swipeModeRequested）：「全屏」按钮只进全屏、不开会话；
    // 简介区顶边下滑提交（pullDownToFullscreen 的 commit 分支）才是唯一入口。
    // 队列为空（相关推荐没有可刷的）时不启用，保持单视频全屏的现状。
    //
    // 刷视频模式（effectiveStoryMode）下本 effect 全程不接管会话：锚点/队列由
    // VideoDetailActivity 的 bootstrapStorySession（story 流）负责，swipeSessionActive
    // 常驻 true。
    var swipeSessionActive by rememberSaveable { mutableStateOf(storyMode) }
    // 「用户明确要刷视频」的一次性请求：仅由简介区边缘下滑提交置位，离开全屏即复位。
    // 放在本层而不是 VM：它描述的是"这次进全屏的来路"，与 VM 里跨全屏往返的锚点/队列
    // 生命周期本来就不同——按钮进全屏不得复用上一次手势的请求。
    var swipeModeRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(fullOn, videoState.detail) {
        if (!fullOn) {
            swipeSessionActive = false
            viewModel.clearSwipeSession()
            swipeModeRequested = false
            return@LaunchedEffect
        }
        if (effectiveStoryMode) {
            swipeSessionActive = true
            return@LaunchedEffect
        }
        if (viewModel.swipeAnchor.value == null) {
            viewModel.beginSwipeSession()
            // 启用判据：**显式请求 && 队列非空**。画幅不参与——横屏视频同样可进，
            // 会话内仍锁竖屏 → letterbox 呈现，需要满屏时顶栏还有手动「横屏」胶囊；
            // 「全屏」按钮不携带请求 → 只进全屏（R3）。队列为空仍不启用，保持单视频全屏。
            swipeSessionActive = swipeModeRequested && viewModel.swipeQueue.value.isNotEmpty()
            DiagnosticLog.i(
                "PortraitSwipe",
                "activate: requested=$swipeModeRequested portrait=$isPortraitVideo " +
                    "queue=${viewModel.swipeQueue.value.size} active=$swipeSessionActive"
            )
        }
    }
    val swipeAnchor by viewModel.swipeAnchor.collectAsStateWithLifecycle()
    val swipeQueue by viewModel.swipeQueue.collectAsStateWithLifecycle()
    val swipeTail by viewModel.swipeTail.collectAsStateWithLifecycle()

    if (hostExpanded) {
        BackHandler {
            handleBack()
        }
    }

    LaunchedEffect(hostExpanded) {
        if (!hostExpanded) {
            downloadSheetOn = false
        }
    }

    if (act != null) {
        val win = act.window
        val ctrl = remember(win) { WindowInsetsControllerCompat(win, win.decorView) }
        val restoreLightSystemBars by rememberUpdatedState(themeUsesDarkSystemBarIcons)

        LaunchedEffect(ctrl, fullOn, isExpandedWidth, themeUsesDarkSystemBarIcons) {
            ctrl.isAppearanceLightStatusBars =
                !fullOn && isExpandedWidth && themeUsesDarkSystemBarIcons
            ctrl.isAppearanceLightNavigationBars = !fullOn && themeUsesDarkSystemBarIcons

            if (fullOn) {
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        DisposableEffect(ctrl) {
            onDispose {
                ctrl.isAppearanceLightStatusBars = restoreLightSystemBars
                ctrl.isAppearanceLightNavigationBars = restoreLightSystemBars
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ── 方向 ────────────────────────────────────────────────────────────────
    // 竖屏刷视频会话（详情全屏的上下滑 / story 常驻全屏）期间**锁竖屏**：
    // BiliPai 的 PortraitVideoPager 全程只做 immersive 系统栏管理、从不请求横屏；
    // 抖音/B 站官方的竖屏连播同样是竖屏锁定的。
    //
    // 两个真机症状都由此而来（用户反馈「每次下滑，屏幕会有一个旋转动画」）：
    // 1. **瞬时空流**：切视频时 `videoState.currentStream` 会短暂为 null（新会话
    //    isPreparing、取流未回），下面的 isPortraitVideo 随之翻成 false —— 只要
    //    autoRotateFullscreen 开着，就会朝 SENSOR_LANDSCAPE 发一次请求 → 转屏动画，
    //    新流回来（竖屏）再转回来。**每一次翻页都会转两次**；
    // 2. 刷到真的是横屏的视频时同样触发。
    // 会话期间第一分支短路，这两条都不会再发生（锁是 PORTRAIT 而不是 UNSPECIFIED：
    // 用户此刻要的是"连播"，方向由 pager 说了算，不听传感器）。
    val portraitSwipeActive = swipeSessionActive || effectiveStoryMode
    // 用户显式要横屏（刷视频会话里点"横屏"胶囊）：只对**当前这一支**视频生效——
    // 切到下一条（ids 变化）或离开会话就收回，于是连播始终是竖屏节奏，
    // 只有用户点名的那条视频会满屏。对齐 BiliPai 的做法
    // （`onRotateToLandscape` → `setPortraitFullscreen(false)` + SENSOR_LANDSCAPE）。
    var swipeLandscape by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(videoState.ids, swipeSessionActive) {
        swipeLandscape = false
    }
    DisposableEffect(
        act,
        fullOn,
        settingsState.playback.autoRotateFullscreen,
        isPortraitVideo,
        portraitSwipeActive,
        swipeLandscape,
        swipeModeRequested
    ) {
        act?.requestedOrientation = when {
            // 手动横屏优先于竖屏锁：用户点名要看满屏，就给满屏
            swipeLandscape && portraitSwipeActive -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            portraitSwipeActive -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // 下滑请求在场时**不走**「单视频全屏自动横屏」：会话激活比 fullOn 晚一拍
            // （fullOn 先翻真，swipeSessionActive 要等下一次重组才翻真），这个空窗里
            // 若转到横屏、会话激活后又锁回竖屏，就是真机看到的「先横屏、再切回刷视频」
            // 闪跳。请求在场保持 UNSPECIFIED（紧凑态本来就是竖持，方向无变化），
            // 随后 portraitSwipeActive 接管锁竖屏。
            fullOn &&
                settingsState.playback.autoRotateFullscreen &&
                !isPortraitVideo &&
                !swipeModeRequested -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // **必须在离开组合时复位**。这个 effect 的 key 里有 fullOn，但 VideoScreen
        // 还有另一条离开路径：外层把 VideoScreen 包在 AnimatedVisibility 里，
        // 收胶囊/点 × 会让 target 变 null 而**直接 dispose**，此时 fullOn 仍是 true，
        // 于是 Activity 被留在 SCREEN_ORIENTATION_SENSOR_LANDSCAPE ——
        // 表现是"退出全屏后主界面还是横屏"。
        // 上面那段 systemBars 的兄弟 effect 就正确做了 restore，这里原本是漏的。
        onDispose {
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val playerTopPad = 16.dp
        val playerGap = 16.dp
        val compactVideoH = maxWidth * (9f / 16f)
        val compactPlayerSpaceH = statusTop + compactVideoH
        val expandedContentW = (maxWidth - (playerTopPad * 2) - playerGap).coerceAtLeast(0.dp)
        val expandedPlayerMaxW = expandedContentW * 0.54f
        val expandedPlayerMaxH = (maxHeight - statusTop - (playerTopPad * 2)).coerceAtLeast(0.dp)
        // 展开态（平板/折叠屏）播放框同样贴合视频比例（#1）：
        // 在「宽上限 × 高上限」的区域内取**与视频同比例的最大矩形** ——
        // 宽视频先按宽度定高（原 540×700 的方框变成 540×304，不再大片 letterbox），
        // 竖屏视频改为按高度定宽。流信息未到时保持原 54% 宽 × 全高的占位。
        val expandedPlayerW: Dp
        val expandedPlayerH: Dp
        if (videoAspect != null && videoAspect > 0f) {
            val heightByMaxW = expandedPlayerMaxW / videoAspect
            if (heightByMaxW <= expandedPlayerMaxH) {
                expandedPlayerW = expandedPlayerMaxW
                expandedPlayerH = heightByMaxW
            } else {
                expandedPlayerW = expandedPlayerMaxH * videoAspect
                expandedPlayerH = expandedPlayerMaxH
            }
        } else {
            expandedPlayerW = expandedPlayerMaxW
            expandedPlayerH = expandedPlayerMaxH
        }

        when {
            !hostExpanded || fullOn -> {
                // 本分支里的播放器有两种载体：刷视频 Pager（页面级缩放/透明动画 ⇒ 必须 TextureView，
                // 传 allowSurfaceView=false）与独立播放页（可 SurfaceView）。进全屏动效要让位的只有后者，
                // 否则刷视频用户白丢一段动效却没有省电收益。
                val pagerActive = swipeSessionActive && swipeAnchor != null
                // SW-33：**详情 → 全屏**的进场过渡（200–280ms 淡入 + 极轻微缩放）。
                // 首页卡片 → 刷视频走的是 OPPO 无缝 morph（既有路径）；这里补的是
                // 详情页按下全屏时的"瞬时硬切"。reduceMotion 时瞬时到位（无动画）。
                val animations = LocalAnimations.current
                val fullEntryAnim = remember { Animatable(if (fullOn) 1f else 0f) }
                LaunchedEffect(fullOn) {
                    if (!fullOn) {
                        // 离开全屏复位：下次进场重新播一遍
                        fullEntryAnim.snapTo(0f)
                        return@LaunchedEffect
                    }
                    if (animations.reduceMotion || (surfacePowersave == true && !pagerActive)) {
                        // 省电模式（且本分支确实用 SurfaceView）：进全屏不做淡入/缩放
                        // —— 那需要 graphicsLayer（图层）⇒ 会挖空 surface
                        fullEntryAnim.snapTo(1f)
                        return@LaunchedEffect
                    }
                    fullEntryAnim.snapTo(0f)
                    fullEntryAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            // 200–280ms：短于一次滑动、长于"闪一下"，读作"铺满"而不是"跳"
                            durationMillis = (animations.medium + animations.short)
                                .coerceIn(200, 280)
                        )
                    )
                }
                // 本分支里的播放器有两种载体：刷视频 Pager（页面级缩放/透明动画 ⇒ 必须 TextureView，
                // 传 allowSurfaceView=false）与独立播放页（可 SurfaceView）。进全屏动效要让位的只有后者，
                // 否则刷视频用户白丢一段动效却没有省电收益。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 进全屏的淡入 + 轻微缩放：省电模式（SurfaceView）下**整段跳过** ——
                        // alpha/scale 都要一层 graphicsLayer，而图层会把独立 surface 挖空（全黑）。
                        // 与 reduceMotion 同处理：瞬时到位（下面 fullEntryAnim 也按同判据 snapTo(1f)）。
                        .then(
                            if (surfacePowersave == true && !pagerActive) {
                                Modifier
                            } else {
                                Modifier.graphicsLayer {
                                    if (fullOn) {
                                        val p = fullEntryAnim.value
                                        alpha = 0.55f + 0.45f * p
                                        val s = 0.985f + 0.015f * p
                                        scaleX = s
                                        scaleY = s
                                    }
                                }
                            }
                        )
                        .onGloballyPositioned {
                            coverBackdropFrame = BackdropFrame(it.boundsInWindow(), RectangleShape)
                        }
                ) {
                    val anchor = swipeAnchor
                    if (swipeSessionActive && anchor != null) {
                        // 竖屏全屏上下滑（BiliPai 同款）：页 0 锚点 + 接力队列。
                        // 播放器唯一，只有当前播放的 bvid 所在页组合 VideoPlayerPane。
                        PortraitSwipePager(
                            anchor = anchor,
                            queue = swipeQueue,
                            // 页身份按内容比（bvid 优先 / aid 兜底，两侧任一缺失都能命中）——
                            // 不能在这里先拼成 key 字符串再比：relate 卡的 bvid 是取流后
                            // 才被补齐的，字符串会在那一刻翻转、播放器页落空（见 isCurrentVideo）
                            currentBvid = videoState.ids.bvid,
                            currentAid = videoState.ids.aid,
                            detailTitle = videoState.detail?.title,
                            detailLoading = videoState.detailLoading,
                            viewModel = viewModel,
                            isFull = fullOn,
                            onToggleFull = {
                                // 刷视频模式没有「退出全屏」态：
                                // - story 入口（顶栏直开）→ 退出 story 模式、露出**当前这支**的详情页
                                //   （需求 3；`onExitStoryMode` 为 null 时退化回关闭整页）
                                // - 详情全屏路径 → 形变回缩到详情页（本来就是对的）
                                if (effectiveStoryMode) {
                                    val exit = onExitStoryMode
                                    if (exit != null) exit() else onBack()
                                } else if (fullOn) {
                                    exitFullscreen()
                                }
                            },
                            onBackClick = handleBack,
                            onGoHome = onGoHome,
                            // SW-20：会话内头像/@名 → UP 空间（与详情页同一出口）
                            onOpenSpace = onOpenSpace,
                            onSwipeTo = viewModel::openSwipeTarget,
                            onNeedMore = viewModel::loadMoreSwipeQueue,
                            // 相邻页预取：拖动 25% 偏移 / 停稳各触发一次（按网络与开关定量）
                            onPrefetch = viewModel::prefetchSwipeQueue,
                            onRetryPlayback = viewModel::retryCurrentPlayback,
                            // 队列尾部状态：停在最后一页时提示"正在找更多/到头了"
                            tailState = swipeTail,
                            // 会话锁竖屏 → 横屏视频要给一个"手动横屏"的出口（见方向 effect）
                            landscapeForced = swipeLandscape,
                            onToggleLandscape = { swipeLandscape = !swipeLandscape },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        VideoPlayerPane(
                            modifier = Modifier.fillMaxSize(),
                            viewModel = viewModel,
                            videoTitle = videoState.detail?.title,
                            isFull = fullOn,
                            onToggleFull = {
                                // 刷视频模式流未就绪时暂以单视频全屏呈现，行为同上
                                if (effectiveStoryMode) {
                                    onBack()
                                } else if (fullOn) {
                                    exitFullscreen()
                                }
                            },
                            onBackClick = handleBack,
                            onGoHome = onGoHome,
                            // story 入口在 bootstrapStorySession 返回之前锚点还是 null，
                            // pager 分支不成立 —— 这段"还没有内容"的时间必须由加载层盖住：
                            // 否则首屏是黑底 + 中央 72dp 播放键（真机帧序列实证，用户说的
                            // 「大大的暂停按钮」）。非 story 路径保持原样（它有自己的
                            // CoverSettleBackdrop 封面垫底）。
                            swipeWaitingFirstFrame = effectiveStoryMode &&
                                !videoState.hasRenderedFirstFrame
                        )
                    }
                }
            }

            isExpandedWidth -> {
                val expandedPlayerShape = ExpandedPlayerShape
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(playerTopPad),
                    horizontalArrangement = Arrangement.spacedBy(playerGap),
                    verticalAlignment = androidx.compose.ui.Alignment.Top
                ) {
                    VideoPlayerPane(
                        modifier = Modifier
                            .width(expandedPlayerW)
                            .height(expandedPlayerH)
                            // 省电模式（SurfaceView）下不能套 Compose clip（裁剪层会挖空 surface ⇒ 全黑）：
                            // 圆角由 PlayerView 的 outline 裁剪保证，视觉一致。
                            .then(if (surfacePowersave == true) Modifier else Modifier.clip(expandedPlayerShape))
                            .onGloballyPositioned {
                                coverBackdropFrame = BackdropFrame(
                                    it.boundsInWindow(),
                                    expandedPlayerShape
                                )
                            },
                        viewModel = viewModel,
                        videoTitle = videoState.detail?.title,
                        isFull = false,
                        onToggleFull = { isFull = true },
                        onBackClick = handleBack,
                        onGoHome = onGoHome
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        VideoDetailPage(
                            modifier = Modifier.fillMaxSize(),
                            viewModel = viewModel,
                            detail = videoState.detail,
                            ids = videoState.ids,
                            biz = videoState.biz,
                            detailLoading = videoState.detailLoading,
                            detailError = videoState.detailError,
                            commentSubject = viewModel.commentSubject,
                            contentHorizontalPad = 0.dp,
                            detailListState = detailListState,
                            onOpenVideo = openTarget,
                            onOpenSpace = onOpenSpace,
                            onDownloadClick = downloadClick,
                            onOpenEpisode = switchEpisode,
                            onSwitchPage = switchPage
                        )
                    }
                }
            }

            else -> {
                val compactPlayerShape = CompactPlayerShape
                // ── 竖排布局：Box 取代 Column ──────────────────────────────────
                // 三项优化都要求「播放带高度连续可变」：竖屏比例贴合（aspectMorph）、
                // 下滑进全屏（fullscreenMorph）。Column 的 weight(1f) 会让详情区在
                // 播放带每次长高时重新 measure 整个列表；改为「详情区按 16:9 基准
                // 高度固定布局，只随播放带底边位移 + 淡退」后，形变全程零 measure。
                //
                // 背景分工：外层 Box 铺**黑色**——满屏形变终点播放带铺满窗口，
                // 底部圆角切过的露底与详情淡退后的露底都是黑，终点无角色突变；
                // 主题背景移到详情区自己身上（静止态观感与原 Column 逐像素一致）。
                // 代价是静止态多一层全屏黑底（原 Column 单层背景）：这是形变能力
                // 的必要成本——淡出中的详情区背后必须有黑底接住，否则浅色主题下
                // 下滑进全屏会从内容边缘漏出一圈亮色。实测算一层纯色填充，可接受。
                // ── 播放器 / 内容衔接（#2 定稿·BiliPai 纯色渐变 + 整圈描边）─────────
                // 三层，全部静态、零逐帧成本：
                // 1. 播放带用 CompactPlayerShape（四角同半径，上下统一）；
                // 2. 贴缝一道**整圈圆角描边**（rim light）：亮度沿垂直渐变走线——
                //    底边全亮、绕过两个圆角时起坡渐弱、圆角上方完全透明。描边与
                //    播放带圆角同构。**描边色 = 下方的光晕色（封面代表色）**——
                //    用户定稿"播放窗口描边的颜色与下方的光同一个颜色"：视频边缘
                //    在发光、光沿圆角流下，整条接缝是一个同源同色的光源；
                // 3. 描边下方铺**纯色光晕渐变**——BiliPai 上游「封面光晕渐变」
                //    （236c6a442）的参数直接移植：封面代表色**原色**（不向白 lerp）
                //    以 0.48/0.38 峰值沿 Telegram 非线性 5 档（topSolidProgressiveFade）
                //    从播放带底边向下洒落，"光"从视频播放区洒在下方内容上。
                //    **原色是关键**：向白 lerp 的低峰值淡彩与 Screen 提亮的灰雾
                //    两版都试过，真机观感"廉价"；上游卡片 coverGlow 用的是封面
                //    原色、高得多的峰值，颜色真实地"渗"下来才有光感。
                //    光晕的**图层与圆角覆盖**见下方接缝三层布局处的注释。
                val seamOnDark = !themeUsesDarkSystemBarIcons
                // 光源取色：垫底封面 URL（打开即有）优先，详情响应的封面兜底；
                // 请求对齐 CoverSettleBackdrop 的同一 URL → 命中同一条 Coil 内存缓存，
                // 不产生第二次下载（与 HomeVideoPage 取色同款防重路径）。
                val seamTintSource = coverBackdropUrl ?: videoState.detail?.cover
                var seamCoverTint by remember(seamTintSource) {
                    mutableStateOf(seamTintSource?.let { VideoCardCoverColorStore.getCachedColor(it) })
                }
                val activeSeamTintKey by rememberUpdatedState(seamTintSource)
                LaunchedEffect(seamTintSource) {
                    val key = seamTintSource ?: return@LaunchedEffect
                    VideoCardCoverColorStore.requestExtraction(
                        appContext = context,
                        cacheKey = key,
                        url = key,
                        sizePx = SeamTintSamplePx,
                        scope = scope
                    ) { color ->
                        // 视频已切换时丢弃晚到的取色，防止上一个视频的颜色串到接缝上
                        if (activeSeamTintKey == key) seamCoverTint = color
                    }
                }
                // 峰值分档用 BiliPai 上游 coverGlow 原值（深 0.48 / 浅 0.38）；
                // 无取色时退纯白（屏幕本光）。颜色原样使用，不冲淡。
                val seamGlowPeak = if (seamOnDark) SEAM_GLOW_PEAK_DARK else SEAM_GLOW_PEAK_LIGHT
                val seamGlowColor = remember(seamCoverTint) {
                    val tint = seamCoverTint
                    if (tint == null || tint.alpha <= 0f) {
                        Color.White
                    } else {
                        tint
                    }
                }
                // 光要"洒"开就得多给一段行程：160dp（五倍 xxxl）——上游是整卡 85% 高度，
                // 播放页取"足够读完首屏标题区"的固定行程；组件内部会 coerce 到容器高度内。
                val seamGlowHeight = BiliSpacing.xxxl * 5f
                // remember 住 modifier 本体：色值/峰值不变时跨重组复用同一个
                // drawWithCache 节点，避免 lambda 换实例触发绘制缓存失效
                val seamGlowModifier = remember(seamGlowColor, seamGlowHeight, seamGlowPeak) {
                    Modifier.topSolidProgressiveFade(
                        surfaceColor = seamGlowColor,
                        fadeHeight = seamGlowHeight,
                        peakAlpha = seamGlowPeak
                    )
                }
                val density = LocalDensity.current
                val statusTopPx = with(density) { statusTop.toPx() }
                val maxHeightPx = with(density) { maxHeight.toPx() }
                val baseVideoPx = with(density) { compactVideoH.toPx() }
                // 竖屏视频按比例加高预览带（#1）：自然高度 = 宽 / 宽高比；
                // 极端长高比（9:16 等）受「详情区保底 30% 可视高度」截顶，
                // 视频 FIT 居中、露出的黑边与黑带同色不可见——预览框从此与
                // 画面比例同构，不再有「小画面嵌大横框」的割裂感。
                val naturalVideoH = if (videoAspect != null && videoAspect > 0f) {
                    maxWidth / videoAspect
                } else {
                    compactVideoH
                }
                val targetVideoH = minOf(naturalVideoH, (maxHeight - statusTop) * 0.70f)
                val targetVideoPx = with(density) { targetVideoH.toPx() }
                // 详情区基准高度（16:9 播放带占位时的高度）。必须在这里算：
                // BoxWithConstraintsScope 带 @LayoutScopeMarker，maxHeight 这类成员
                // 在内层 Box 的 content 作用域里不可见（编译期报"implicit receiver"）。
                // 下限用 BiliSpacing.xs 而非 0.dp：极端矮窗（横屏小窗）下 16:9 播放带
                // 可能高过窗口本身，兜一个非零下限即可，dp 字面量棘轮已无余额
                // （DesignTokenLintTest 实测 980/980）。
                val detailBaseH = (maxHeight - compactPlayerSpaceH).coerceAtLeast(BiliSpacing.xs)

                // 目标比例偏离 16:9 基准时播放一次比例过渡（同屏切分P/切清晰度
                // 导致目标变化时重播）；横向视频比例更宽时同理收窄。
                // reduceMotion（动效速度=关）：弹簧时长不可控，直接 snap 到终点。
                LaunchedEffect(baseVideoPx, targetVideoPx) {
                    val target = if (abs(targetVideoPx - baseVideoPx) > 1f) 1f else 0f
                    if (anim.reduceMotion) {
                        aspectMorph.snapTo(target)
                    } else {
                        aspectMorph.animateTo(
                            targetValue = target,
                            animationSpec = aspectSpec
                        )
                    }
                }

                // 形变高度：布局期求值（见 playerMorphHeight 的 KDoc），每帧只重新
                // measure 播放带这一棵子树，详情列表不参与。
                val videoMorphPx: () -> Float = {
                    lerp(
                        lerp(baseVideoPx, targetVideoPx, aspectMorph.value),
                        maxHeightPx,
                        fullscreenMorph.value
                    )
                }
                val bandMorphHeightPx: () -> Float = {
                    // 播放带 = 状态栏内缩 + 画面高度。**不能去掉 statusTop**：
                    // 画面框已在上面按"videoMorphPx + statusTop"给足，
                    // 带底与画面底因此天然重合（圆角与简介区连成一片），
                    // 少算这一段反而会让带底比画面底高一截、圆角处露底。
                    (statusTopPx + videoMorphPx()).coerceAtMost(maxHeightPx)
                }
                // 「下滑进全屏」的判定区必须读到**最新**的播放带底边（2026-10-07 审查）：
                // `pullDownToFullscreen` 内部是 `pointerInput(progress, windowBottomPx)`，
                // 而 Compose 的 pointerInput 元素相等性**只比 key**（compose-ui 1.12.1 字节码实核：
                // 不含 handler 引用）⇒ key 不变就不会重启手势块，块里捕获的 `zoneTopPx`
                // 会**冻结在首次组合那一刻**（那时 `currentStream` 还是 null ⇒ target = 16:9 基准
                // ⇒ 区顶停在 856）。竖屏视频贴合后带底涨到 ~2000px，画面下半部会被当成
                // "播放带下方"：Initial pass 抢走亮度/音量手势，在列表顶部向下拖还会进全屏。
                // 修法：稳定外壳（实例不变 ⇒ 手势块不重启）+ `rememberUpdatedState` 读最新 lambda。
                val zoneTopPxLatest = rememberUpdatedState<() -> Float> {
                    statusTopPx + lerp(baseVideoPx, targetVideoPx, aspectMorph.value)
                }
                val zoneTopPxStable: () -> Float = remember { { zoneTopPxLatest.value() } }
                // 几何诊断（release 开「现场诊断日志」也可见）：画面框与预留骨架是否一致，靠这行判定。
                LaunchedEffect(videoAspect, baseVideoPx, targetVideoPx, maxWidth) {
                    val maxW = with(density) { maxWidth.toPx() }
                    DiagnosticLog.i("PlayerGeometry") {
                        "aspect=$videoAspect maxW=$maxW statusTop=$statusTopPx base=$baseVideoPx " +
                            "target=$targetVideoPx morph=${aspectMorph.value} " +
                            "paneContent=${videoMorphPx() - statusTopPx} band=${bandMorphHeightPx()}"
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // **根层铺「主题背景色」，不能铺黑、也不能留空（2026-10-07 用户反馈
                        // 「视频上部分的圆角没了，要上下圆角统一」）**：
                        // 播放区域的黑由**播放带自己**按 Shape 绘制（见下方
                        // `background(Color.Black, compactPlayerShape)`），圆角因此自带；
                        // 根层若铺方形黑，就会在圆角**外侧**补出直角黑边；
                        // 根层若留空，露出的是**窗口背景**——`VideoDetailActivity` 为了过渡帧
                        // 防闪白把它设成了纯黑（见那边的注释），所以结果同样是直角黑边。
                        // 铺主题背景色后：圆角外 = 页面底色（与下沿的"详情区背景层"同色）⇒
                        // 上下两沿的圆角读起来一致 ✓，而播放区域内部（含 letterbox）仍由
                        // 播放带与 PlayerView 的黑底负责 ✓。
                        .background(MaterialTheme.colorScheme.background)
                        .pullDownToFullscreen(
                            scope = scope,
                            progress = fullscreenMorph,
                            zoneTopPx = zoneTopPxStable,
                            windowBottomPx = maxHeightPx,
                            // ── SX-01 要点②③：页面级下滑手势**只在简介页武装** ──────────
                            // 这个 lambda 的返回值语义是"让列表自己接管吗"：
                            // · 简介页：与改动前**逐字一致**（`detailListState.canScrollBackward`）
                            //   ⇒ 原入口「简介区下滑进刷视频」零回归（验收②）；
                            // · 评论页（含楼中楼）：一律返回 true = 全部让给列表 ⇒ 在评论里
                            //   上下滚动**任何边界情形**都不会被判定为"想进刷视频"（缺陷①）。
                            //   评论列表到顶继续下滑 = 列表自己的 overscroll，不是进刷视频（要点③）。
                            canListScrollDown = {
                                !detailIntroPageVisible || detailListState.canScrollBackward
                            },
                            onSettle = { commit ->
                                scope.launch {
                                    if (commit) {
                                        // 简介区顶边下滑提交 = 刷视频模式的唯一显式入口：
                                        // 置位请求再进全屏，会话启用交给上面的 effect；
                                        // 「全屏」按钮不经过这里，因此只进全屏（R3）。
                                        swipeModeRequested = true
                                        DiagnosticLog.i("PortraitSwipe", "enter: via=introPull")
                                        fullscreenMorph.animateTo(1f, morphSpec)
                                        isFull = true
                                    } else {
                                        fullscreenMorph.animateTo(0f, morphSpec)
                                    }
                                }
                            }
                        )
                ) {
                    // ── 接缝三层（用户定稿的图层序）：背景 → 光晕 → 简介内容 ──────
                    // 圆角覆盖的几何：**背景层与光晕层都上移一个圆角半径**。播放带
                    // 底部圆角切掉的月牙区是透明的，会让这两层沿圆角轮廓透出来——
                    // 光因此从圆角最亮处流出、向下衰减，与播放带圆角精确相切；
                    // 圆角内的部分被播放带（z 序最上）盖住，互不干扰。
                    // 浅色主题的"黑耳朵"（外层黑底从月牙透出）也由这套上移的
                    // 背景层解决——旧的接缝描边里那条会盖住光的垫底色已随描边一并删除。
                    val seamGlowRadiusPx = 0f  // 播放区域直角 ⇒ 没有圆角月牙要盖，光晕层不再上移一个半径
                    // 1. 详情区背景层（主题背景色，z 最低；上移 r 覆盖月牙区）
                    // N-3（用户真机反馈：「部分视频尺寸较小 ⇒ 屏幕下方出现黑色部分」）：
                    // 这里**必须铺满整屏高度**，不能再用「预留高度」推出来的 height。
                    // 旧写法 `.height(detailBaseH + r)` 的下沿是
                    //   band + maxHeight − compactPlayerSpaceH
                    // 而 `detailBaseH = maxHeight − compactPlayerSpaceH`、播放带预留是
                    // `compactPlayerSpaceH = statusTop + 16:9 高度` —— 只要**视频比 16:9 更宽**
                    // （自然高度更矮 ⇒ `targetVideoPx < baseVideoPx` ⇒ 播放带比预留矮），
                    // 下沿就比屏幕矮一截 `compactPlayerSpaceH − band`，根层的黑底 (`background(Color.Black)`)
                    // 从底部透出来。视频越扁漏得越多 —— 与用户"尺寸较小的视频才出现"完全吻合。
                    // 改成 fillMaxHeight + 下移：起点仍是 `band − r`（月牙区照旧被盖住），
                    // 但下沿永远越过屏幕底、由父层裁掉 ⇒ 与播放带当前高度**无关**地盖到底。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .offset {
                                IntOffset(
                                    0,
                                    (bandMorphHeightPx() - seamGlowRadiusPx).roundToInt()
                                )
                            }
                            .graphicsLayer { alpha = 1f - fullscreenMorph.value }
                            .background(MaterialTheme.colorScheme.background)
                    )
                    // 2. 接缝光晕层：封面代表色（原色）沿 Telegram 非线性 5 档向下洒落
                    //    （BiliPai 上游 coverGlow 同款）。**图层在简介内容之下**——光
                    //    洒在背景上、文字清晰浮在光上，不遮挡内容（用户定稿）。
                    //    不随 fullscreenMorph 淡退：形变期它始终贴着播放带底边，
                    //    全屏后整层被播放带盖住，无可见影响。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(seamGlowHeight + BiliRadius.playerCompact)
                            .offset {
                                IntOffset(
                                    0,
                                    (bandMorphHeightPx() - seamGlowRadiusPx).roundToInt()
                                )
                            }
                            .then(seamGlowModifier)
                    )
                    // 3. 简介 / 评论区：布局固定、形变期只做位移 + 淡退（绘制期读动画值）。
                    //    **透明背景、z 在光晕之上**——简介内容是最上层内容图层。
                    //
                    // 位置 = 播放带底边（= 画面底边）。**间距不能靠 offset 拉开**：
                    // 那样中间会留出一段没有背景的空白（露出根层黑底）；间距交给
                    // 简介内容自己的顶部 padding（见下面 contentTopPad），
                    // 于是那段区域由接缝光晕/垫底铺满，圆角与简介区连成一片。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(detailBaseH)
                            .offset { IntOffset(0, bandMorphHeightPx().roundToInt()) }
                            .graphicsLayer { alpha = 1f - fullscreenMorph.value }
                    ) {
                        VideoDetailPage(
                            modifier = Modifier.fillMaxSize(),
                            viewModel = viewModel,
                            detail = videoState.detail,
                            ids = videoState.ids,
                            biz = videoState.biz,
                            detailLoading = videoState.detailLoading,
                            detailError = videoState.detailError,
                            commentSubject = viewModel.commentSubject,
                            contentHorizontalPad = 16.dp,
                            detailListState = detailListState,
                            onOpenVideo = openTarget,
                            onOpenSpace = onOpenSpace,
                            onDownloadClick = downloadClick,
                            onOpenEpisode = switchEpisode,
                            onSwitchPage = switchPage,
                            // SX-01：把"当前是不是简介页"接进上面的手势门控。
                            onIntroPageVisibleChange = { detailIntroPageVisible = it },
                            // 与画面之间的呼吸间距：给在**内容自身**（而不是把整块内容推下去，
                            // 那样中间会露出没有背景的一段黑）。取状态栏高度 = 与改动前同量级。
                            contentTopPad = statusTop
                        )
                    }
                    // 播放带：黑底 + 底部圆角，高度随形变插值（= 状态栏内缩 + 画面高度 ⇒ 带底 == 画面底）
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .playerMorphHeight(bandMorphHeightPx)
                            // 先画背景再谈裁剪：省电模式（SurfaceView）下**不能**用 Compose clip
                            // （裁剪层会把独立 surface 挖空 ⇒ 画面全黑），改成"按形状绘制黑底"
                            // （background(color, shape) 不建图层）；画面的圆角由 PlayerView 的
                            // outline 裁剪负责，视觉一致。
                            .then(if (surfacePowersave == true) Modifier else Modifier.clip(compactPlayerShape))
                            .background(Color.Black, compactPlayerShape)
                            .onGloballyPositioned {
                                // 形变期间不上报垫底帧：终点矩形只在静止态有意义，
                                // 且避免每帧新建 BackdropFrame 触发重组
                                val p1 = aspectMorph.value
                                val p2 = fullscreenMorph.value
                                if (p2 == 0f && (p1 == 0f || p1 == 1f)) {
                                    coverBackdropFrame = BackdropFrame(
                                        it.boundsInWindow(),
                                        compactPlayerShape
                                    )
                                }
                            }
                    ) {
                        VideoPlayerPane(
                            modifier = Modifier
                                .fillMaxWidth()
                                // **用外层的 statusTop，不再各自解析 inset**（2026-10-07 实测：
                                // 外层 `WindowInsets.statusBars` = 141，而播放页内部
                                // `statusBarsPadding()` 只解析出 71 —— 两个来源不同 ⇒
                                // 播放页实际占 [71, 927]，比播放带（底边 856）多出 71px 黑，
                                // 画面还被挤得必须 letterbox，"与视频骨架对不上"就是这两笔）。
                                // **顺序不能反（2026-10-07 真机 dump 定案）**：`playerMorphHeight`
                                // 在外层（它给的是播放页**整页高度**），顶部内缩加在内层。
                                // 反过来写（padding 在外层）时，内层那个高度表达式会整个变成"画面框"，
                                // 于是画面框 = videoMorphPx + statusTop —— 真机 dump 实测
                                // `PlayerView 1272×856`（= 716 + 141）：比画面高整整一个状态栏，
                                // 16:9 画面被 FIT 居中 ⇒ 上下各一条 71px 黑边（用户报的"视频下方那条黑"），
                                // 而且播放页比播放带高 141px、越过带底压到简介区。
                                // 这样写：总高 = videoMorphPx + statusTop = 播放带高，
                                // 画面框 = videoMorphPx（与视频同比例 ⇒ letterbox = 0），
                                // 画面满宽满框、底边与播放带底边重合。
                                .playerMorphHeight { videoMorphPx() + statusTopPx }
                                .padding(top = statusTop)
                                // 满屏形变终点 = 全屏分支布局（铺满窗口、无状态栏内缩）：
                                // 用位移抵消这段顶部内缩，提交切换时零跳变。
                                // 两套写法按需选：
                                // · 省电模式（SurfaceView）：必须用 layout 期 offset —— graphicsLayer 会建
                                //   图层，把独立 surface 挖空 ⇒ 画面全黑；
                                // · 老渲染（TextureView）：保留 graphicsLayer，形变全程零 measure（当初的设计）。
                                //   若统一改成 offset，会让所有人多付"逐帧重排"，属于无收益回归。
                                .then(
                                    if (surfacePowersave == true) {
                                        Modifier.offset {
                                            IntOffset(0, -(statusTopPx * fullscreenMorph.value).roundToInt())
                                        }
                                    } else {
                                        Modifier.graphicsLayer {
                                            translationY = -statusTopPx * fullscreenMorph.value
                                        }
                                    }
                                ),
                            viewModel = viewModel,
                            videoTitle = videoState.detail?.title,
                            isFull = false,
                            onToggleFull = enterFullscreen,
                            onBackClick = handleBack,
                            onGoHome = onGoHome
                        )
                    }
                    // ── SX-03：横屏（非全屏）时，画面**下方**的悬浮「全屏」按钮 ──────
                    //
                    // 只在本分支出现：`when` 的第一个分支已经吃掉了 `fullOn`，所以
                    // 走到这里必然 `!fullOn` ⇒ "全屏时不出现" 是分支结构保证的，不需要额外判据。
                    // 竖屏不满足 isLandscapeWindow ⇒ 不出现（要点①）。
                    if (isLandscapeWindow) {
                        LandscapeFullscreenFab(
                            onClick = enterFullscreen,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = BiliSpacing.md)
                                // 贴在播放带**下沿**：读布局期的 bandMorphHeightPx（不在组合期
                                // 读动画值，与文件顶部形变纪律一致）；全屏形变期间随之下移，
                                // 同时按 morph 进度淡出（此时按钮已无意义，见 SX-05）。
                                // **必须钳在窗口内**（2026-10-07 审查）：`bandMorphHeightPx()` 出口已经有
                                // `coerceAtMost(maxHeightPx)`，而横屏手机窗口宽高比 ≈2.2 时
                                // `compactVideoH = 0.5625 × maxWidth` 必然超过可用高 ⇒ 钳位必命中 ⇒
                                // 按钮落到 `maxHeight + sm` = **屏幕外**，这个横屏专属入口根本点不到。
                                // 上限留出按钮自身高度（`defaultMinSize` = xxxl×1.5 = 48dp）与一个 sm 间距。
                                .offset {
                                    IntOffset(
                                        0,
                                        (bandMorphHeightPx() + with(density) { BiliSpacing.sm.toPx() })
                                            .coerceAtMost(
                                                maxHeightPx - with(density) {
                                                    (BiliSpacing.xxxl * 1.5f).toPx() +
                                                        BiliSpacing.sm.toPx()
                                                }
                                            )
                                            .coerceAtLeast(0f)
                                            .roundToInt()
                                    )
                                }
                                .graphicsLayer { alpha = 1f - fullscreenMorph.value }
                        )
                    }
                }
            }
        }

        // 封面垫底：绘制在最上层但无点击语义（触摸穿透到下方内容），
        // 铺在播放器区域内，首帧渲染后淡出露出视频。
        //
        // **必须按视频 ids 归键**（上方注释早已声明这个契约，但一直没被执行）：
        // CoverSettleBackdrop 内部的 `dismissed` / `alpha` 是无 key 的 remember，
        // 而页内切分P / 切选集 / 相关推荐都让 VideoScreen 留在组合里、不重置记忆。
        // 于是第 1 条视频淡出完成后 dismissed=true、alpha=0，第 2 条视频的垫底
        // 直接是个透明层 —— 打开动画期间播放器区域露出黑帧。
        // 用 ids 而不是 coverUrl 归键，正是为了"连续打开同源封面视频"这种
        // URL 相同、但应当重新走一遍垫底流程的情况。
        key(videoIdsKey) {
            coverBackdropUrl?.let { url ->
                CoverSettleBackdrop(
                    coverUrl = url,
                    coverLarge = coverBackdropLarge,
                    frame = coverBackdropFrame,
                    firstFrameRendered = videoState.hasRenderedFirstFrame
                )
            }
        }
    }

    if (hostExpanded && downloadSheetOn) {
        val sheetVideoQuality = videoState.currentStream?.quality ?: 80
        val sheetAudioQuality = videoState.currentAudio?.id ?: 0
        DownloadTaskSheet(
            currentVideoQuality = sheetVideoQuality,
            currentAudioQuality = sheetAudioQuality,
            canStartDownload = viewModel.currentDownloadRequest(
                kind = VideoDownloadKind.VIDEO,
                videoQuality = sheetVideoQuality,
                audioQuality = sheetAudioQuality
            ) != null,
            onDismiss = { downloadSheetOn = false },
            onOpenCache = {
                downloadSheetOn = false
                onOpenDownloadCache()
            },
            onStart = { kind, videoQuality, audioQuality ->
                // `canStartDownload` 是**组合期**算的，而请求依赖 `videoState.detail`：
                // 组合与点击之间详情被清空/切页（重试、换目标）就会拿到 null ——
                // 原来这里 `?: error(...)` 是**必崩点**（IllegalStateException）。
                // 拿不到请求就当作"这次没点上"直接返回，不崩。
                val request = viewModel.currentDownloadRequest(
                    kind = kind,
                    videoQuality = videoQuality,
                    audioQuality = audioQuality
                ) ?: return@DownloadTaskSheet
                onStartDownload(request)
            }
        )
    }
}

/**
 * 横屏（非全屏）时，视频画面**下方**的悬浮「全屏」按钮（SX-03）。
 *
 * ## 为什么需要它
 * 横屏播放时，现成的全屏入口在**顶栏**最上方（`VideoPlayerPane` 的顶栏胶囊/控制条
 * 「全屏」键），单手横握时够不到。用户需求③明确要"视频下方给一个悬浮全屏按钮
 * （不用去顶栏点）"。
 *
 * ## 设计约束（任务书要点逐条）
 * ① 只在**横屏且非全屏**出现 —— 调用点在本分支内，而该分支由 `when` 结构保证
 *    `!fullOn`；横屏由调用点的 `isLandscapeWindow` 判；
 * ② 位置在**画面下方** —— 调用点用播放带底边 `bandMorphHeightPx()` 再下移一个
 *    `BiliSpacing.sm`，不压画面、也不进底部信息层（本分支底部是详情页）；
 * ③ 点击走**既有**全屏通路 —— 调用点直接传 `enterFullscreen`（`fullscreenMorph`
 *    形变 + `isFull`），不另造状态；
 * ④ 顶栏入口**保留**（本次未动 `VideoPlayerPane` 的任何入口）；
 * ⑤ 无障碍：图标带 `contentDescription`，点击有 `onClickLabel`，
 *    `defaultMinSize` 兜住 48dp 触摸目标（`BiliSpacing.xxxl * 1.5f`）；
 * ⑥ 尺寸全部走 token（`BiliSpacing`），圆角用百分比 pill，**不新增 dp 字面量**
 *    （DesignTokenLintTest 棘轮）。
 *
 * 观感与 SX-02/SX-10 同一套语言：半透明主题面 + 轻投影，**不做模糊**（D-2）。
 */
@Composable
private fun LandscapeFullscreenFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        shadowElevation = BiliSpacing.xs,
        modifier = modifier
            .defaultMinSize(minHeight = BiliSpacing.xxxl * 1.5f)
            .clickable(onClickLabel = "进入全屏", onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BiliSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
        ) {
            Icon(
                imageVector = Icons.Rounded.Fullscreen,
                contentDescription = "进入全屏",
                modifier = Modifier.size(BiliSpacing.xl)
            )
            Text(
                text = "全屏",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** 封面垫底形变的目标帧：播放器容器在窗口坐标系中的矩形与收尾圆角形状。 */
private data class BackdropFrame(val rect: Rect, val endShape: Shape)

/**
 * 播放区「形变高度」：目标高度在**布局期**从 [heightPx] 求值。
 *
 * Animatable 的读取落在 `layout{}` 的 measure 块里 → 动画每帧只触发一次
 * 重新 measure/place，不重组、不进入组合期（FrameBudgetLintTest 的
 * 「组合期不读动画值」口径）。高度超过父约束是**刻意**的：满屏形变终点
 * 播放带要超出窗口一个状态栏高度，由播放带自身的 clip 裁掉。
 */
private fun Modifier.playerMorphHeight(heightPx: () -> Float): Modifier =
    layout { measurable, constraints ->
        val height = heightPx().roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(
            constraints.copy(minHeight = height, maxHeight = height)
        )
        layout(constraints.maxWidth.coerceAtLeast(1), height) {
            placeable.place(0, 0)
        }
    }


/** 光源取色的解码档（palette 内部还会压到 48×48 采样，档位只影响解码内存）。 */
private val SeamTintSamplePx = 480 to 360

/**
 * 接缝光晕的峰值 alpha——**BiliPai 上游「封面光晕渐变」（236c6a442）的 coverGlow 原值**
 * （深色 0.48 / 浅色 0.38），真机验证过的观感基准。
 *
 * 为什么不再压低：接缝光试过两轮"更微妙"的方案——(a) 向白 lerp 的低峰值淡彩
 * （0.22/0.16）、(b) Screen 提亮的双层灰白光（halo+spill）——真机都被读作"廉价/
 * 灰雾"。上游卡片 coverGlow 之所以好看，靠的就是**封面原色 + 高近一倍的峰值**：
 * 颜色真实地"渗"下来才有光感，冲淡颜色只会得到一层脏雾。
 */
private const val SEAM_GLOW_PEAK_DARK = 0.48f
private const val SEAM_GLOW_PEAK_LIGHT = 0.38f

/**
 * 下滑进入全屏的提交阈值（进度比例）。
 *
 * 进度是「手指 y」在「播放带底边 → 窗口底边」区间上的直接映射，0.5 即
 * 拖过全程一半：播放带此时已盖住约半屏，继续拖完比回弹更符合预期。
 */
private const val PULL_FULLSCREEN_COMMIT = 0.5f


/**
 * 下滑进入全屏的手势（#3），挂在竖排外层 Box 上。
 *
 * ## 为什么走 Initial pass
 * 详情列表（LazyColumn）在 API 31+ 默认带 overscroll stretch：在列表顶部继续
 * 下拉时**列表自己会消费掉这些事件**驱动拉伸动画。若在 Main pass 里等
 * 「子节点不消费」，这根手势在现代设备上永远等不到。Initial pass 是
 * 「父先于子」的通道：在这里裁决——列表还能向下滚（[canListScrollDown]）
 * 就一律放行；已在顶边才接管并 consume，子节点（列表 / 横向 Pager /
 * 按钮）因此完全看不到这些事件，overscroll 也不会被触发。
 *
 * ## 直接操纵
 * 进度不跟阈值阶梯走，而是把手指 y 线性映射到「播放带底边 → 窗口底边」
 * 区间：播放带底边全程贴着手指生长（[progress] 由手指位置 snapTo），
 * 松手才按 [onSettle] 提交（true）或回弹（false）。
 *
 * ## 触发区
 * 只有按下点在播放带**下方**（简介区域）才接管；播放带上的手势归播放器
 * 浮层（亮度/音量/进度），两者通过按下点分区，不互相抢。
 */
private fun Modifier.pullDownToFullscreen(
    scope: CoroutineScope,
    progress: Animatable<Float, *>,
    zoneTopPx: () -> Float,
    windowBottomPx: Float,
    canListScrollDown: () -> Boolean,
    onSettle: (Boolean) -> Unit,
): Modifier = pointerInput(progress, windowBottomPx) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            ?: return@awaitEachGesture
        // 按下点在播放带上 → 整体放行（播放器浮层的手势）
        if (down.position.y <= zoneTopPx()) return@awaitEachGesture
        var tracking = false
        var lastX = down.position.x
        var lastY = down.position.y
        var baseY = down.position.y
        var baseP = 0f
        var accumDx = 0f
        var accumDy = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (tracking) onSettle(progress.value >= PULL_FULLSCREEN_COMMIT)
                break
            }
            accumDx += change.position.x - lastX
            accumDy += change.position.y - lastY
            lastX = change.position.x
            lastY = change.position.y
            if (!tracking) {
                // 起手条件：向下越过 touchSlop、纵向占优、列表已到顶
                // （列表还能向下滚时让位，避免抢走详情区滚动）
                if (accumDy > slop && abs(accumDy) > abs(accumDx) && !canListScrollDown()) {
                    tracking = true
                    baseY = lastY
                    baseP = progress.value
                }
            }
            if (tracking) {
                val span = (windowBottomPx - zoneTopPx()).coerceAtLeast(1f)
                val next = (baseP + (lastY - baseY) / span).coerceIn(0f, 1f)
                // snapTo 是挂起函数，而 awaitEachGesture 的块运行在
                // @RestrictsSuspension 的 AwaitPointerEventScope 里（只能调用
                // 该 scope 的成员挂起函数）—— 经外部 scope 派发。Main 派发器
                // FIFO，逐事件落定的顺序即手指顺序，settle 的 animateTo 排在其后。
                scope.launch { progress.snapTo(next) }
                change.consume()
            }
        }
    }
}

/** [BackdropFrame.endShape] 的四角圆角半径（px），供形变插值。 */
private data class BackdropCornerPx(
    val topStart: Float,
    val topEnd: Float,
    val bottomEnd: Float,
    val bottomStart: Float
)

private fun Shape.cornerPxOrNull(density: Density): BackdropCornerPx? {
    // **直角也要给一组 0 半径**（2026-10-07 回归修复）：播放窗口改成 `RectangleShape`
    // 之后，`RectangleShape` 不是 `CornerBasedShape` ⇒ 这里返回 null ⇒ `CoverSettleBackdrop`
    // 在 `if (cornerPx == null) return` 处直接退出 —— 封面垫底**整块不再绘制**，
    // 首帧出来之前播放区退回纯黑（省电模式默认开，黑得更久）。
    // 垫底要的是"按播放框形状裁剪"，直角就是 0 半径的圆角矩形，语义相同。
    if (this === RectangleShape) return BackdropCornerPx(0f, 0f, 0f, 0f)
    val corners = this as? CornerBasedShape ?: return null
    return BackdropCornerPx(
        topStart = corners.topStart.toPx(Size.Unspecified, density),
        topEnd = corners.topEnd.toPx(Size.Unspecified, density),
        bottomEnd = corners.bottomEnd.toPx(Size.Unspecified, density),
        bottomStart = corners.bottomStart.toPx(Size.Unspecified, density)
    )
}

/**
 * 详情页封面垫底：铺在播放器区域（容器同边界同圆角），视频首帧渲染后交叉淡出；
 * 流迟迟未就绪时超时兜底淡出（最长驻留 2.5s）。变体与来源卡片一致，直接命中
 * 内存缓存位图，无占位闪烁。位置经 layout 延迟读取，帧间零重组。
 */
@Composable
private fun CoverSettleBackdrop(
    coverUrl: String,
    coverLarge: Boolean,
    frame: BackdropFrame?,
    firstFrameRendered: Boolean
) {
    var dismissed by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(1f) }
    val fadeSpec = tween<Float>(durationMillis = LocalAnimations.current.short)

    LaunchedEffect(firstFrameRendered) {
        if (firstFrameRendered && !dismissed) {
            alpha.animateTo(0f, fadeSpec)
            dismissed = true
        }
    }
    LaunchedEffect(Unit) {
        delay(2500)
        if (!dismissed) {
            alpha.animateTo(0f, fadeSpec)
            dismissed = true
        }
    }

    if (dismissed || frame == null) return
    val density = LocalDensity.current
    val cornerPx = remember(frame, density) { frame.endShape.cornerPxOrNull(density) }
    if (cornerPx == null) return
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .backdropBounds(frame.rect)
                .graphicsLayer {
                    this.alpha = alpha.value
                    shape = RoundedCornerShape(
                        topStart = CornerSize(cornerPx.topStart),
                        topEnd = CornerSize(cornerPx.topEnd),
                        bottomEnd = CornerSize(cornerPx.bottomEnd),
                        bottomStart = CornerSize(cornerPx.bottomStart)
                    )
                    clip = true
                }
                .background(Color.Black)
        ) {
            BiliAsyncImage(
                url = coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                variant = if (coverLarge) {
                    BiliImageVariant.CardCoverLarge
                } else {
                    BiliImageVariant.CardCover43
                },
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * 垫底节点按 [rect]（窗口坐标）测量并摆放自身内容；
 * 尺寸与位置都在布局阶段读取，不触发重组。
 */
private fun Modifier.backdropBounds(rect: Rect): Modifier = layout { measurable, _ ->
    val w = rect.width.roundToInt().coerceAtLeast(1)
    val h = rect.height.roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(w, h) { placeable.place(rect.left.roundToInt(), rect.top.roundToInt()) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DownloadTaskSheet(
    currentVideoQuality: Int,
    currentAudioQuality: Int,
    canStartDownload: Boolean,
    onDismiss: () -> Unit,
    onOpenCache: () -> Unit,
    onStart: (VideoDownloadKind, Int, Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by rememberSaveable { mutableStateOf(VideoDownloadKind.VIDEO) }
    var videoQuality by rememberSaveable {
        mutableIntStateOf(currentVideoQuality.takeIf { it > 0 } ?: 80)
    }
    var audioQuality by rememberSaveable {
        mutableIntStateOf(currentAudioQuality.takeIf { it > 0 } ?: 0)
    }

    ModalBottomSheet(
        // 弹层形状唯一来源（见 biliSheetShape）：iOS 28dp 只圆上两角，Material M3 默认
        shape = biliSheetShape(),
        // 风格自适应 grabber：iOS 36×5dp 细拉手，Material 保持 M3 默认
        dragHandle = { BiliSheetGrabber() },

        scrimColor = Color.Black.copy(alpha = 0.40f),
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("下载", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == VideoDownloadKind.VIDEO,
                    onClick = { kind = VideoDownloadKind.VIDEO },
                    label = { Text("下载视频") }
                )
                FilterChip(
                    selected = kind == VideoDownloadKind.AUDIO,
                    onClick = { kind = VideoDownloadKind.AUDIO },
                    label = { Text("下载音频") }
                )
            }
            if (kind == VideoDownloadKind.VIDEO) {
                DownloadOptionGroup(
                    title = "画质",
                    options = VideoDownloadOptions.videoQualities,
                    selected = videoQuality,
                    onSelect = { videoQuality = it }
                )
            }
            DownloadOptionGroup(
                title = "音质",
                options = VideoDownloadOptions.audioQualities,
                selected = audioQuality,
                onSelect = { audioQuality = it }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenCache,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("查看缓存")
                }
                Button(
                    onClick = { onStart(kind, videoQuality, audioQuality) },
                    enabled = canStartDownload,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (kind == VideoDownloadKind.VIDEO) "下载视频" else "下载音频")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadOptionGroup(
    title: String,
    options: List<VideoDownloadOption>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option.value == selected,
                    onClick = { onSelect(option.value) },
                    label = { Text(option.label) }
                )
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    val min = sec / 60
    val hour = min / 60
    return if (hour > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hour, min % 60, sec % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", min, sec % 60)
    }
}

internal fun formatPlaybackTime(
    posMs: Long,
    durMs: Long
): String {
    return "${formatDuration(posMs)} / ${formatDuration(durMs)}"
}

internal fun formatSpeed(speed: Float): String {
    val num = if (speed % 1f == 0f) {
        String.format(Locale.ROOT, "%.1f", speed)
    } else {
        String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
    return "${num}x"
}

internal fun getCodecName(codecId: Int): String {
    return when (codecId) {
        7 -> "AVC/H.264"
        12 -> "HEVC/H.265"
        13 -> "AV1"
        else -> "未知 $codecId"
    }
}

internal fun getAudioName(
    audioId: Int,
    short: Boolean = false
): String {
    return when (audioId) {
        30216 -> "64K"
        30232 -> "132K"
        30280 -> "192K"
        30250 -> if (short) "杜比" else "杜比全景声"
        30251 -> if (short) "无损" else "Hi-Res 无损"
        else -> if (short) "音频" else "音频 $audioId"
    }
}

internal fun getQualityName(
    source: PlaybackSource?,
    stream: PlaybackStream?
): String {
    source ?: return "画质"
    stream ?: return "画质"
    val option = source.qualityOptions.firstOrNull { it.quality == stream.quality }
    val label = option?.displayDescription?.takeIf(String::isNotBlank)
        ?: option?.description?.takeIf(String::isNotBlank)
        ?: stream.description.takeIf(String::isNotBlank)
        ?: "画质"
    return label.substringBefore(' ').ifBlank { label }
}


