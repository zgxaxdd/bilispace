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
import androidx.compose.runtime.setValue
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessTransitionState
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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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
        if (!consumeIntent(intent)) {
            finish()
            return
        }
        // 上一次返回动画遗留的延迟关停还没执行时，本次打开立即取消，避免误杀新会话
        hostState.cancelPendingClose()
        // 与 MainActivity 一致的 edge-to-edge 适配
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // 窗口过渡帧兜底：无缝打开/返回瞬间系统可能露出一帧窗口背景，
        // 深色与播放器区域同色，避免浅色主题下闪白（正常内容始终盖在背景之上）
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setContent {
            val themeConfig by appSettings.themeConfig.collectAsState(initial = ThemeConfig())
            BiliTheme(config = themeConfig) {
                LaunchedEffect(pendingTarget) {
                    pendingTarget?.let(videoViewModel::openRoot)
                }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
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
        if (pendingSeamlessLaunch) {
            // 官方成对返回：框架按锚点实时位置把窗口缩回卡片（缩回画面 = 框架绘制的
            // 锚点内容，共享执行器已在打开结束时把封面画进锚点）。这里仅预判返回
            // 阶段开始，源侧 onStart/onEnd(isOpen=false) 会再次确认。
            SeamlessTransitionState.beginReturn()
        }
        if (hostState.miniPlayerAvailable.value) {
            hostState.minimize()
        } else {
            hostState.closeAfterReturnAnimation()
        }
        finish()
    }

    /**
     * 页内导航收起（回首页/空间/缓存）：MainActivity 将切页，锚点卡片可能已随
     * 重组销毁，成对返回动画会飞向失效锚点——改系统默认过渡，收起立即生效。
     */
    private fun finishWithDefaultTransition() {
        if (pendingSeamlessLaunch) {
            OplusViewSeamlessCompat.useDefaultTransitionOnFinish(this)
            SeamlessTransitionState.endReturn()
        }
        if (hostState.miniPlayerAvailable.value) {
            hostState.minimize()
        } else {
            hostState.close()
        }
        finish()
    }
}
