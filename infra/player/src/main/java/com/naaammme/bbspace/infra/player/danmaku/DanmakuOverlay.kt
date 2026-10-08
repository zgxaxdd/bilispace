package com.naaammme.bbspace.infra.player.danmaku

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import com.naaammme.bbspace.core.model.DanmakuConfig
import com.naaammme.bbspace.core.model.DanmakuSessionState
import master.flame.danmaku.api.SegmentDanmakuSession
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.ui.widget.DanmakuTextureView

enum class DanmakuRenderMode {
    Segmented,
    LiveAppend
}

// initial* 参数仅在首次创建时生效，后续状态通过 sync/syncLive 更新
@Composable
fun rememberDanmakuOverlayState(
    initialConfig: DanmakuConfig,
    initialPositionMs: Long,
    initialIsPlaying: Boolean,
    initialSpeed: Float
): DanmakuOverlayState {
    val context = LocalContext.current
    return remember(context) {
        // 绘制缓存 内存换cpu占用
        // 必须用 TextureView 而非 SurfaceView 渲染弹幕：
        // SurfaceView 是独立硬件合成层，开启弹幕时把 DanmakuSurfaceView 叠到
        // TextureView 视频画面上会与窗口合成冲突（surface 区域被挖洞/盖住），
        // 视频画面整体变黑但解码与声音正常；TextureView 画进窗口本身，
        // 与视频层级、Compose 圆角裁剪、ColorOS 无缝动画的 View 内容快照均兼容。
        val danmakuView = DanmakuTextureView(context).apply {
            // 必须关闭 drawing cache：CacheManagingDrawTask 的 ready() 由缓存线程
            // 首次 BUILD_CACHES 后才回调，实测在 Compose AndroidView 宿主里该回调
            // 链路可能永不完成（心跳 prepared=false 恒定），绘制循环因此无法启动；
            // 普通 DrawTask.prepare() 同步回调 ready()，PREPARE 一过门立即起播。
            enableDanmakuDrawingCache(false)
            showFPS(false)
            setDrawingThreadType(IDanmakuView.THREAD_TYPE_LOW_PRIORITY)
            // 库默认 setLayerType(HARDWARE)+willNotDraw(true)：与视频层的 TextureView
            // （无此配置、surface 正常创建）不同，实测会导致 onSurfaceTextureAvailable
            // 不回调、DrawHandler 的 PREPARE 门卡死。对齐普通 TextureView 配置。
            setLayerType(android.view.View.LAYER_TYPE_NONE, null)
            setWillNotDraw(false)
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }
        val danmakuContext = createDanmakuContext(context.resources.displayMetrics.density).apply {
            applyConfig(initialConfig)
        }
        val timeProvider = DanmakuPlayerTimeProvider(
            positionMs = initialPositionMs,
            isPlaying = initialIsPlaying,
            speed = initialSpeed
        )
        DanmakuOverlayState(
            danmakuView = danmakuView,
            danmakuCtrl = danmakuView,
            danmakuContext = danmakuContext,
            timeProvider = timeProvider,
            session = SegmentDanmakuSession(
                danmakuView,
                danmakuContext,
                DefaultDanmakuItemMapper(),
                timeProvider
            )
        )
    }
}

@Suppress("UnsafeOptInUsageError")
@UnstableApi
@Composable
fun DanmakuLayer(
    playerView: PlayerView,
    overlayState: DanmakuOverlayState,
    danmakuState: DanmakuSessionState,
    danmakuConfig: DanmakuConfig,
    positionMs: Long,
    isPlaying: Boolean,
    speed: Float,
    seekEventId: Long,
    hasSource: Boolean,
    renderMode: DanmakuRenderMode = DanmakuRenderMode.Segmented,
    manageLifecycle: Boolean = true
) {
    // 先挂载、后 prepare：DrawHandler 的 PREPARE 完成回调会立即 start 播放，
    // 若彼时 view 尚未挂载（surface 未就绪）绘制循环可能起不来。Effects 按声明
    // 顺序执行，挂载效果声明在前，保证 prepare 时 view 已在窗口中。
    DisposableEffect(playerView, overlayState) {
        val view = overlayState.danmakuView
        val host = playerView.overlayFrameLayout ?: playerView
        val parent = view.parent as? ViewGroup
        if (parent !== host) {
            parent?.removeView(view)
            host.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            DiagnosticLog.i(
                "BbDanmaku",
                "attach danmaku view to ${host.javaClass.simpleName} " +
                    "hostAttached=${host.isAttachedToWindow} size=${host.width}x${host.height}"
            )
        } else {
            val lp = view.layoutParams as? FrameLayout.LayoutParams
            if (
                lp == null ||
                lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                view.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        onDispose {
            // **只摘自己挂上去的那一个宿主**（2026-10-07 审查）：宿主会因省电模式翻转
            // （`key(useSurfaceView)` 重建 PlayerView）而更换，而同一个 danmakuView 是复用的 ——
            // 若旧 effect 的清理在新 effect 挂载之后才跑，按"当前 parent"删就会把刚挂到
            // **新宿主**上的同一个 view 摘掉 ⇒ 之后弹幕永久不显示（弹幕线程还在跑）。
            if (view.parent === host) host.removeView(view)
        }
    }

    if (manageLifecycle) {
        DisposableEffect(overlayState) {
            overlayState.prepare()
            onDispose {
                overlayState.release()
            }
        }
    }

    // 诊断心跳：低频上报管线状态（无弹幕问题定位用，2s 一条）。
    //
    // **仅 debug**：原来直接用 android.util.Log（绕过项目 Logger 的 isDebug 门控），
    // 且无任何构建类型判断 —— release 用户只要在播放页/直播页就每 2 秒一条日志，
    // 每次还构造 diagnosticState() 字符串。同类诊断（AppInitializer）是显式
    // `if (!BuildConfig.DEBUG) return` 门控的，这里用 Logger.isDebug 对齐
    // （本模块未开启 buildConfig 生成，拿不到 BuildConfig）。
    LaunchedEffect(overlayState) {
        if (!Logger.isDebug) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(2_000)
            Logger.i("BbDanmaku") { "heartbeat ${overlayState.diagnosticState()}" }
        }
    }

    when (renderMode) {
        DanmakuRenderMode.Segmented -> {
            LaunchedEffect(
                danmakuState.sourceKey,
                danmakuState.window?.id,
                danmakuConfig,
                isPlaying,
                speed,
                seekEventId,
                hasSource
            ) {
                overlayState.sync(
                    danmakuState = danmakuState,
                    config = danmakuConfig,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    speed = speed,
                    seekEventId = seekEventId,
                    hasSource = hasSource
                )
            }
        }

        DanmakuRenderMode.LiveAppend -> {
            LaunchedEffect(danmakuConfig, isPlaying, speed, hasSource) {
                overlayState.syncLive(
                    config = danmakuConfig,
                    isPlaying = isPlaying,
                    speed = speed,
                    hasSource = hasSource
                )
            }
        }
    }
}
