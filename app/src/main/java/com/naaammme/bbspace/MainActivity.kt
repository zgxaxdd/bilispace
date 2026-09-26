package com.naaammme.bbspace

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.settings.update.AppUpdateCheckResult
import com.naaammme.bbspace.core.settings.update.AppUpdateChecker
import com.naaammme.bbspace.core.settings.update.toDialogState
import com.naaammme.bbspace.core.designsystem.component.AppUpdateDialog
import com.naaammme.bbspace.core.designsystem.component.AppUpdateDialogState
import com.naaammme.bbspace.core.designsystem.component.ForegroundEnterBox
import com.naaammme.bbspace.core.designsystem.theme.BiliTheme
import com.naaammme.bbspace.core.designsystem.theme.FrameRateMode
import com.naaammme.bbspace.core.designsystem.theme.ThemeConfig
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import com.naaammme.bbspace.core.model.WebLinkParser
import com.naaammme.bbspace.core.model.WebLinkTarget
import com.naaammme.bbspace.navigation.AppNavHost
import com.naaammme.bbspace.playback.PlaybackHostState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "MainActivity"

        /**
         * 主题同步预读的超时上限。DataStore 首次读盘正常在个位数毫秒；
         * 给 300ms 只为兜住异常情况，超时即回退默认主题，绝不拖住启动。
         */
        const val THEME_PRELOAD_TIMEOUT_MS = 300L
    }

    @Inject
    lateinit var appSettings: AppSettings

    @Inject
    lateinit var updateChecker: AppUpdateChecker

    @Inject
    lateinit var hostState: PlaybackHostState

    private var updateDialog by mutableStateOf<AppUpdateDialogState?>(null)

    private var cachedDisplayModes: Array<Display.Mode>? = null

    private var pendingAppLink by mutableStateOf<WebLinkTarget?>(null)

    // Android 13+ 通知运行时权限：后台播放的媒体通知没有它会被系统静默丢弃。
    // 时序（重要）：播放开始只**记标记**，真正弹窗推迟到 MainActivity 回到前台、
    // 拿到焦点且返回 morph 结束之后 —— 此前直接在 onPlaybackUiStarted（无缝打开
    // 动画期间的详情 Activity onCreate）里 launch，系统权限弹窗压在视频页上、
    // 并把返回键抢走（按返回先关弹窗，返回动画被吞），表现为"返回动画异常"。
    // 拒绝后不再骚扰（需要时用户可去系统设置授予）。
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Logger.d(TAG) { "POST_NOTIFICATIONS granted=$granted" }
        }

    private var pendingNotificationPermission = false

    private fun maybeRequestNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            pendingNotificationPermission = false
            return
        }
        val prefs = getSharedPreferences("app_flags", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notif_perm_asked", false)) {
            pendingNotificationPermission = false
            return
        }
        prefs.edit().putBoolean("notif_perm_asked", true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 消费待弹的权限请求。只在 MainActivity 处于前台时调用（onResume /
     * onWindowFocusChanged），且系统无缝返回 morph 进行中继续等待 —— 弹窗
     * 绝不落在动画窗口内，也不落在视频详情页上。
     */
    private fun maybeShowPendingNotificationPermission() {
        if (!pendingNotificationPermission) return
        if (com.naaammme.bbspace.core.designsystem.seamless.SeamlessTransitionState.isReturnRunning) return
        pendingNotificationPermission = false
        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        tryRestoreVideoPage("onResume")
        maybeShowPendingNotificationPermission()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 双触发兜底：OEM 清栈时详情页 onDestroy（alive 标志复位）可能晚于
            // onResume 落地，focus 时刻重查一次。展开后 hostMode!=Hidden，
            // shouldRestoreVideoPage 自身即去重，不会重复恢复。
            tryRestoreVideoPage("onWindowFocus")
            if (pendingNotificationPermission) {
                // 焦点刚落定可能还在返回 morph 里：等一段再试一次，仍进行中就
                // 交给下一次 onResume（标记保留，只弹一次的语义不变）
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(1_200)
                    maybeShowPendingNotificationPermission()
                }
            }
        }
    }

    /**
     * 视频页恢复：切后台前在看的视频页，回前台后原样恢复。
     *
     * 恢复动作是**就地展开播放宿主覆盖层**（hostState.expand），而不是重新
     * startActivity——理由：
     * 1. 播放会话/进度/详情/弹幕全是进程级状态，覆盖层 VideoScreen 渲染的即是
     *    切出前的现场，展开即恢复，零等待；
     * 2. 不发起任何跨 Activity 启动：onResume 时刻窗口尚未持有焦点，ColorOS 的
     *    后台启动拦截可能静默丢弃 startActivity（此前三轮"自愈恢复"均失败于此）；
     * 3. 判定基于 VideoDetailActivity 上报的真实存活性（videoDetailAlive），
     *    与 onResume/onWindowFocusChanged 双触发配合，覆盖销毁回调晚落地的时序。
     */
    private fun tryRestoreVideoPage(from: String) {
        val videoTarget = hostState.currentTarget.value as? StreamPlaybackTarget.Video
        val should = videoTarget != null && hostState.shouldRestoreVideoPage()
        Logger.d(TAG) {
            "restore-check from=$from target=${videoTarget != null} " +
                "alive=${hostState.videoDetailAlive} mode=${hostState.hostMode} " +
                "userClosed=${hostState.isUserClosedVideo} -> $should"
        }
        if (should && videoTarget != null) {
            hostState.expand()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 播放开始（覆盖层展开 / 无缝详情 Activity 创建）只记标记；权限弹窗在
        // onResume/onWindowFocusChanged 且返回 morph 结束后才弹（见
        // maybeShowPendingNotificationPermission 的时序注释）
        hostState.onPlaybackUiStarted = { pendingNotificationPermission = true }
        if (savedInstanceState == null) {
            autoCheckUpdate()
        }
        pendingAppLink = toAppLink(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            )
        )
        // minSdk 33：无需判版本
        window.isNavigationBarContrastEnforced = false
        setContent {
            // 主题首帧必须就是**真实配置**，不能用 `collectAsState(initial = ThemeConfig())`。
            //
            // 为什么：ThemeConfig() 的默认值是 MATERIAL + FROSTED，而用户的真实配置
            // （本机为 IOS + LIQUID）从 DataStore **异步**到达。于是第一帧按 Material
            // 渲染、第二帧才翻成 iOS —— 界面风格、玻璃材质、配色方案全变一次，
            // 用户看到的就是"打开软件闪一下"。实测日志（两条连续输出）：
            //   theme-first-real uiStyle=MATERIAL glass=FROSTED   ← 默认值首帧
            //   theme-first-real uiStyle=IOS      glass=LIQUID    ← 真实配置
            //
            // 修法：在**首次组合期**同步预读一次真实配置，作为 state 的初值。
            // 优先读**设置镜像**（SharedPreferences 完整快照，微秒级、不碰 DataStore），
            // 首帧主线程不再 runBlocking；只有镜像缺失（升级后首启 / 清数据）才回退
            // `preloadThemeConfig()` 那条带 300ms 超时的慢路径 —— 这种情况下宁可阻塞
            // 一次，也不能让首帧主题闪一下。
            // 用 MutableState 承载：它本身就是 Compose 可观察对象，直接读 .value 即可，
            // 不需要也不应该再 collectAsState（那是 Flow → State 的转换）。
            val themeConfigState: MutableState<ThemeConfig> =
                remember {
                    mutableStateOf(appSettings.peekThemeConfigBlocking() ?: preloadThemeConfig())
                }
            // 初值来自同步预读（首帧即正确主题）；随后继续跟随 DataStore，
            // 设置页改主题时立即生效。
            LaunchedEffect(Unit) {
                appSettings.themeConfig.collect { themeConfigState.value = it }
            }
            val themeConfig: ThemeConfig = themeConfigState.value
            LaunchedEffect(themeConfig) {
                com.naaammme.bbspace.core.common.log.Logger.d("MainActivity") {
                    "theme-first-real uiStyle=${themeConfig.uiStyle} " +
                        "themeMode=${themeConfig.themeMode} glass=${themeConfig.glassMaterial} " +
                        "seed=${themeConfig.seedColor}"
                }
            }

            LaunchedEffect(themeConfig.preferredFrameRate) {
                applyFrameRate(themeConfig.preferredFrameRate)
            }
            BiliTheme(config = themeConfig) {
                ApplySystemBarAppearance()
                ForegroundEnterBox {
                    AppNavHost(
                        themeConfig = themeConfig,
                        appLink = pendingAppLink,
                        onAppLinkConsumed = { pendingAppLink = null }
                    )
                }
                updateDialog?.let { release ->
                    AppUpdateDialog(
                        state = release,
                        onDismiss = { updateDialog = null },
                        onOpenUrl = {
                            updateDialog = null
                            startActivity(Intent(Intent.ACTION_VIEW, it.toUri()))
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAppLink = toAppLink(intent)
    }

    /**
     * 同步预读真实主题配置的**兜底**路径。正常情况下首帧走
     * [com.naaammme.bbspace.core.settings.AppSettings.peekThemeConfigBlocking]
     * 的设置镜像（SharedPreferences 快照，微秒级），不会走到这里。
     *
     * 为什么仍要保留：镜像缺失（升级后首启、清数据、镜像写入被进程终止打断）时，
     * 首帧用默认主题会造成"打开软件闪一下"，那比阻塞一次更糟。
     *
     * 为什么可以同步兜底：AppSettings 是进程内单例，DataStore 已初始化，首次读盘只是
     * 读一个已 mmap 的小文件，实测个位数毫秒。仍加 [THEME_PRELOAD_TIMEOUT_MS] 超时，
     * 异常时回退默认值，绝不把启动拖住。
     */
    private fun preloadThemeConfig(): ThemeConfig = runCatching {
        runBlocking {
            withTimeoutOrNull(THEME_PRELOAD_TIMEOUT_MS) {
                appSettings.themeConfig.first()
            }
        }
    }.getOrNull() ?: ThemeConfig()

    private fun autoCheckUpdate() {        lifecycleScope.launch {
            if (!appSettings.autoCheckUpdate.first()) return@launch
            val result = updateChecker.check().getOrNull() ?: return@launch
            if (result is AppUpdateCheckResult.HasUpdate) {
                updateDialog = result.toDialogState()
            }
        }
    }

    /**
     * 应用帧率偏好。
     *
     * ## AUTO 不再等于"放弃高刷"
     *
     * 原实现 AUTO 走 `preferredDisplayModeId = 0` + `preferredRefreshRate = 0f`，
     * 即**交还给系统默认模式**。问题在于系统默认往往是 60Hz：本机（PLC110）
     * 面板支持 120/144Hz，但 `mActiveRenderFrameRate` 长期是 60.0 —— 也就是
     * "买了高刷屏却全程 60Hz"。而本项目首页滚动的 p50 只有 8~11ms（60fps 预算
     * 16.67ms），余量充足，提到 120Hz 能把主观流畅度直接翻倍。
     *
     * 因此 AUTO 改为"**同分辨率下取最高刷新率**"（BiliPai 上游
     * `resolvePreferredHighRefreshModeId` 同款策略）。要点：
     * - 候选必须**同分辨率**：跨分辨率切模式会重建 surface、画面闪一下；
     * - 按 refreshRate 降序取最高，而不是取"最接近某个目标值"—— AUTO 的语义就是"能多快就多快"；
     * - 找不到更高刷新率的模式（或只有 60Hz 屏）时退回原行为，不影响低端机。
     *
     * 想省电的用户可以在设置里显式选 RATE_60，那条路径不变。
     */
    private fun applyFrameRate(mode: FrameRateMode) {
        val attrs = window.attributes
        if (mode == FrameRateMode.AUTO) {
            val best = findHighestRefreshDisplayMode()
            if (best != null) {
                attrs.preferredDisplayModeId = best.modeId
                attrs.preferredRefreshRate = best.refreshRate
            } else {
                // 没有更高刷模式：交还系统默认
                attrs.preferredDisplayModeId = 0
                attrs.preferredRefreshRate = 0f
            }
        } else {
            val targetMode = findBestDisplayMode(mode.value)
            if (targetMode != null) {
                attrs.preferredDisplayModeId = targetMode.modeId
            }
            attrs.preferredRefreshRate = mode.value
        }
        window.attributes = attrs
        // 诊断：确认实际选中的模式。`dumpsys display` 里存在**两套 mode-ID 命名空间**
        // （DisplayMode 列表 0..n 与 DisplayDeviceInfo 的 supportedModes 1..n），
        // 光看 dumpsys 容易误判，所以把 App 侧真正读到的值打出来。
        Logger.d("FrameRate") {
            "applyFrameRate mode=$mode -> modeId=${attrs.preferredDisplayModeId} " +
                "refreshRate=${attrs.preferredRefreshRate} " +
                "current=${window.decorView.display?.mode?.let { "id=${it.modeId} ${it.refreshRate}Hz" }}"
        }
    }

    /**
     * 同分辨率下刷新率最高的显示模式；已是最高（或拿不到模式列表）时返回 null。
     *
     * 返回 null 而非"最高那个"是有意的：当前模式本身就是最高时不必去写
     * `preferredDisplayModeId`，避免无谓的 window attributes 写入。
     */
    private fun findHighestRefreshDisplayMode(): Display.Mode? {
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return null
        val current = display.mode ?: return null
        val modes = cachedDisplayModes ?: display.supportedModes
            ?.also { cachedDisplayModes = it }
            ?: return null
        val best = modes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: return null
        return best.takeIf { it.refreshRate > current.refreshRate + 1f }
    }

    private fun findBestDisplayMode(targetHz: Float): Display.Mode? {
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return null
        val currentMode = display.mode
        val w = currentMode.physicalWidth
        val h = currentMode.physicalHeight
        val modes = cachedDisplayModes ?: display.supportedModes
            ?.also { cachedDisplayModes = it }
            ?: return null
        return modes
            .filter { it.physicalWidth == w && it.physicalHeight == h }
            .minByOrNull { abs(it.refreshRate - targetHz) }
            ?.takeIf { abs(it.refreshRate - targetHz) < 5f }
    }

    private fun toAppLink(intent: Intent?): WebLinkTarget? {
        val url = intent?.dataString ?: return null
        return when (val target = WebLinkParser.parse(url)) {
            is WebLinkTarget.ToVideo,
            is WebLinkTarget.ToSpace,
            is WebLinkTarget.ToLive -> target
            is WebLinkTarget.External,
            is WebLinkTarget.Stay -> null
        }
    }

    @Composable
    private fun ApplySystemBarAppearance() {
        val view = LocalView.current
        val useDarkSystemBarContent = MaterialTheme.colorScheme.background.luminance() > 0.5f

        if (!view.isInEditMode) {
            SideEffect {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = useDarkSystemBarContent
                insetsController.isAppearanceLightNavigationBars = useDarkSystemBarContent
            }
        }
    }
}
