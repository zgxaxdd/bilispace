package com.naaammme.bbspace.video

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessTransitionState
import com.naaammme.bbspace.core.designsystem.component.ForegroundEnterBox
import com.naaammme.bbspace.core.designsystem.theme.BiliTheme
import com.naaammme.bbspace.core.designsystem.theme.ThemeConfig
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.feature.video.VideoScreen
import com.naaammme.bbspace.feature.video.VideoViewModel
import com.naaammme.bbspace.navigation.EXTRA_COVER_LARGE
import com.naaammme.bbspace.navigation.EXTRA_COVER_URL
import com.naaammme.bbspace.navigation.EXTRA_FROM_SEAMLESS
import com.naaammme.bbspace.navigation.VideoDetailNavBus
import com.naaammme.bbspace.navigation.VideoTargetSerializer
import com.naaammme.bbspace.playback.PlaybackHostState
import com.naaammme.bbspace.playback.capsule.CapsuleIndicatorState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** 主题同步预读的超时上限（与 MainActivity 一致）。 */
private const val THEME_PRELOAD_TIMEOUT_MS = 300L

/**
 * 视频详情独立 Activity（ColorOS「View 无缝动画」路径的宿主）。
 *
 * 仅在支持无缝动画的设备（OplusViewSeamlessCompat.isVersionSupported）上被打开：
 * 首页卡片 → 封面矩形从卡片无缝展开到本 Activity；不支持设备维持 MainActivity 内的
 * PlaybackHost 覆盖层路径，不经过这里。
 *
 * 播放连续性：PlayerEngine / StreamPlaybackSession 是进程级单例，跨 Activity 只需
 * 把 PlayerView 重绑到同一 player（PlayerViewTargetBinder 在 STARTED/STOP 自动处理），
 * openVideoInternal 对同一 target 有短路保护，不会重头播放。
 *
 * 收起语义：
 * - 返回键（预期有系统成对返回动画）：先标记返回动画进行中，迷你出现与播放关停
 *   都等动画结束（SeamlessReturnAnimState 驱动 + 超时兜底），否则迷你 PlayerView
 *   抢绑 / close 释放 surface 会让缩回中的画面变黑；
 * - 页内导航（回首页 / 空间 / 缓存）：MainActivity 将切页或重组，锚点可能已销毁，
 *   改用系统默认过渡（useDefaultTransitionOnFinish），收起立即生效。
 */
@AndroidEntryPoint
class VideoDetailActivity : ComponentActivity() {

    @Inject
    lateinit var hostState: PlaybackHostState

    @Inject
    lateinit var capsuleState: CapsuleIndicatorState

    @Inject
    lateinit var navBus: VideoDetailNavBus

    @Inject
    lateinit var appSettings: AppSettings

    private val videoViewModel: VideoViewModel by viewModels()

    private var pendingTarget by mutableStateOf<VideoTarget?>(null)
    private var pendingCoverUrl by mutableStateOf<String?>(null)
    private var pendingCoverLarge by mutableStateOf(false)
    private var pendingSeamlessLaunch by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 上报真实存活性：MainActivity 的视频页恢复判定以此为准（见 PlaybackHostState）
        hostState.onVideoDetailStarted()
        if (!consumeIntent(intent)) {
            finish()
            return
        }
        // 上一次返回动画遗留的延迟关停还没执行时，本次打开立即取消，避免误杀新会话
        hostState.cancelPendingClose()
        // 进入新视频详情：胶囊隐藏，新详情接管（方案 2.3 状态机）。
        //
        // **无缝打开时必须延后到本 Activity 销毁**（不是延后到打开动画结束）：
        // 胶囊（联动底栏的小横条）是**打开与返回两个方向**的系统过渡锚点宿主。
        // `deactivate()` 会让 nowPlayingContent 变 null → 小横条整块从组合中移除 →
        // 锚点 View 从 window detach。系统随后取锚点内容时拿到已 detach 的 View，
        // 直接放弃 morph 退回 alpha 交叉淡出：
        //   OplusViewSeamlessRemoteManager: view not attach to window or error, do alpha anim
        //   post onTransitionFinish, reason: alpha exception finish
        // 实测：只在打开动画结束后隐藏，打开方向就正常了，但**返回方向仍然退回
        // alpha**（日志 `anchor released (wasAttached=false)` 出现在返回动画开始之前）。
        // 因此这里全程保留胶囊，只把底栏的小横条**隐藏**（`nowPlayingVisible=false`，
        // 由 `PlaybackHostState.videoDetailAlive` 驱动），锚点留在 window 上；
        // 真正的 `deactivate()` 交给返回路径（`finishWithHostState` 里重新激活）
        // 或本 Activity 销毁时的兜底。
        // 非无缝路径（搜索/历史/深链）没有锚点，立即隐藏即可。
        if (!pendingSeamlessLaunch) {
            capsuleState.deactivate()
        }
        // 与 MainActivity 一致的 edge-to-edge 适配
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            )
        )
        // minSdk 33：无需判版本
        window.isNavigationBarContrastEnforced = false
        // 窗口过渡帧兜底：无缝打开/返回瞬间系统可能露出一帧窗口背景，
        // 深色与播放器区域同色，避免浅色主题下闪白（正常内容始终盖在背景之上）
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setContent {
            // 首帧主题必须**同步预读真实配置**，不能用 `collectAsState(initial = ThemeConfig())`。
            //
            // 为什么：`ThemeConfig()` 的默认值是 MATERIAL + 动态取色（浅色系），而用户
            // 真实配置可能是 IOS + LIQUID + 深色。异步到达后界面风格、玻璃材质、配色
            // 整体翻一次 —— 在**无缝打开动画期间**这表现为"详情页打开瞬间是白的"。
            // 与 MainActivity 的 preloadThemeConfig 是同一修法（那边注释里有完整实测）。
            //
            // 为什么可以同步：AppSettings 是进程内单例、DataStore 已初始化，首次读盘只是
            // 读一个已 mmap 的小文件（个位数毫秒）；仍带超时兜底，异常时回退默认值。
            val themeConfigState = remember { mutableStateOf(preloadThemeConfig()) }
            LaunchedEffect(Unit) {
                appSettings.themeConfig.collect { themeConfigState.value = it }
            }
            BiliTheme(config = themeConfigState.value) {
                LaunchedEffect(pendingTarget) {
                    pendingTarget?.let(videoViewModel::openRoot)
                }
                ForegroundEnterBox {
                    VideoScreen(
                        onBack = ::finishWithHostState,
                        onGoHome = {
                            navBus.post(VideoDetailNavBus.NavRequest.GoHome)
                            finishWithDefaultTransition()
                        },
                        onOpenSpace = { route ->
                            navBus.post(VideoDetailNavBus.NavRequest.OpenSpace(route))
                            finishWithDefaultTransition()
                        },
                        onOpenDownloadCache = {
                            navBus.post(VideoDetailNavBus.NavRequest.OpenDownloadCache)
                            finishWithDefaultTransition()
                        },
                        onStartDownload = { request ->
                            // 下载交给 MainActivity 侧的 DownloadViewModel 执行（总线排队不丢）
                            navBus.post(VideoDetailNavBus.NavRequest.StartDownload(request))
                        },
                        viewModel = videoViewModel,
                        hostExpanded = true,
                        coverBackdropUrl = pendingCoverUrl,
                        coverBackdropLarge = pendingCoverLarge
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    /**
     * 同步预读真实主题配置，作为首帧初值。与 MainActivity 的同名方法同源。
     *
     * 详情页是**独立 Activity**，有自己的一次 setContent，所以必须自己预读一次；
     * 否则无缝打开动画期间会先渲染 MATERIAL 浅色，再翻成用户真实主题 —— 观感是白闪。
     */
    private fun preloadThemeConfig(): ThemeConfig = runCatching {
        runBlocking {
            withTimeoutOrNull(THEME_PRELOAD_TIMEOUT_MS) {
                appSettings.themeConfig.first()
            }
        }
    }.getOrNull() ?: ThemeConfig()

    override fun onDestroy() {
        // 实例确已离开返回栈：恢复判定随之解除（含系统清栈/后台回收等一切移出方式）
        hostState.onVideoDetailStopped()
        super.onDestroy()
    }

    /** 解析并登记 Intent 中的目标视频；无有效目标返回 false（直接退出）。 */
    private fun consumeIntent(intent: Intent?): Boolean {
        val target = intent?.let(VideoTargetSerializer::getTarget) ?: return false
        pendingTarget = target
        pendingCoverUrl = intent.getStringExtra(EXTRA_COVER_URL)
        pendingCoverLarge = intent.getBooleanExtra(EXTRA_COVER_LARGE, false)
        pendingSeamlessLaunch = intent.getBooleanExtra(EXTRA_FROM_SEAMLESS, false)
        return true
    }

    /**
     * 返回键收起：先标记「返回动画进行中」，迷你出现/播放关停由
     * SeamlessReturnAnimState 在动画结束后放行；再改宿主状态、最后 finish，
     * 保证返回动画期间缩回的画面仍是活的播放器。
     */
    private fun finishWithHostState() {
        // 用户主动返回：标记收起（MainActivity 自愈逻辑据此不再重拉视频页）
        hostState.markVideoClosedByUser()
        if (pendingSeamlessLaunch) {
            // 这里**刻意不调用** OplusViewSeamlessCompat.finishCurrentAnimation()。
            //
            // 真机对比实验（同一台 PLC110 / ColorOS 16.0，同样的「快速开关 5 轮」脚本）：
            // - 官方哔哩哔哩客户端：startAnimation×1、SpringAnimation×1、
            //   finishCurrentAnimation×0、"can't reverse"×0 → 系统把连续的开/收
            //   合并成**同一个**动画实例并自然反向，全程平滑；
            // - 本应用（原先每个返回都中断）：startAnimation×4、SpringAnimation×4、
            //   finishCurrentAnimation×3、"can't reverse"×4 → 每轮都把系统刚合并好的
            //   过渡打断成 "can't reverse, finish now"，动画只能停在合并中途重新起一段，
            //   这正是"快速打开关闭时动画不稳定"的来源。
            //
            // 官方文档把 finishCurrentAnimation 定位为「一般无需调用」，适用场景是
            // 「页面返回时立即滑动列表」（见 HomeVideoPage 的滚动中断逻辑，那里保留）。
            // 返回键路径交给系统成对动画自行 merge/reverse 即可。
            SeamlessTransitionState.beginReturn()
        }
        SeamlessTransitionState.markClosing()
        // 胶囊等价替换（方案 2.3）：视频目标 → 激活胶囊（播放继续）；
        // 直播/空目标 → 原关停分支。胶囊无 PlayerView，收起动画期间可立即出现。
        if (!capsuleState.activateForVideoTarget()) {
            hostState.closeAfterReturnAnimation()
        }
        finish()
    }

    /**
     * 页内导航收起（回首页/空间/缓存）：MainActivity 将切页，锚点卡片可能已随
     * 重组销毁，成对返回动画会飞向失效锚点——改系统默认过渡，收起立即生效。
     */
    private fun finishWithDefaultTransition() {
        // 用户页内导航收起（回首页/空间/缓存）：标记收起，避免自愈逻辑重拉视频页
        hostState.markVideoClosedByUser()
        if (pendingSeamlessLaunch) {
            OplusViewSeamlessCompat.useDefaultTransitionOnFinish(this)
            SeamlessTransitionState.endReturn()
        }
        SeamlessTransitionState.markClosing()
        if (!capsuleState.activateForVideoTarget()) {
            hostState.close()
        }
        finish()
    }
}
