package com.naaammme.bbspace.core.designsystem.seamless

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.naaammme.bbspace.core.common.media.BiliCoverSize
import com.naaammme.bbspace.core.common.media.coverUrlFor
import com.naaammme.bbspace.core.model.VideoTarget
import com.oplus.animation.OplusViewSeamless
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「视频卡片 → 详情」系统无缝打开的共享执行器（按官方接入指引实现）。
 *
 * 官方语义（open.oppomobile.com 文档 13772）：
 * - setSeamlessView 在 startActivity 前调用，同一 bundle 传给 startActivity；
 * - 打开/返回成对动画由框架执行，返回终点按 view 实时位置自动调整；
 * - 框架在打开与返回时都会「绘制 view 内容」作为过渡画面；
 * - BUNDLE_RADIUS 有圆角必传；BUNDLE_COLOR/RECT/BITMAP 是框架取色/取区域/绘制
 *   内容**失败时的兜底**，官方默认不传（主动传 COLOR 只会改变纯色兜底的颜色，
 *   此前返回阶段的白/黑纯色缩回即来源于此）。
 *
 * 关键适配：锚点是零绘制透明 View，框架"绘制 view 内容"时它没有内容 → 返回阶段
 * 退回纯色兜底。因此点击时把封面（**软件位图**）画进锚点 background+foreground，
 * 打开与返回的框架绘制共用这份内容；返回结束清除，平时保持零绘制。
 * 位图必须转软件配置：返回阶段的快照为软件画布，Coil 默认解码的硬件位图在软件
 * 画布上绘制会抛异常（此前"打开偶发退回默认动画、返回必现纯色"的根因）。
 * 另在打开动画结束后把同一软件位图补进 bundle 的 BUNDLE_BITMAP——打开已结束
 * 不影响开画面面，若框架在返回开始时重新读取 bundle，则多一层内容兜底。
 */
class SeamlessCardOpener(
    private val route: (VideoTarget, String?, Boolean, Bundle) -> Unit,
    private val cleanupScope: CoroutineScope? = null,
    private val enabled: () -> Boolean = { true }
) {

    /** 上一张被画了过渡内容的卡片；新打开时清理，避免软件位图副本累积。 */
    private var lastDecorated: View? = null

    /** 待执行的锚点清理协程；新打开时取消，防止误清正在使用的过渡位图。 */
    private var cleanupJob: Job? = null

    /**
     * 软件位图的小容量 LRU（最多 [COVER_MEMO_CAPACITY] 条）。
     *
     * 为什么不是单条：硬件位图转软件位图（`Bitmap.copy(ARGB_8888)`）是**主线程同步拷贝**，
     * 960×540 约 2MB、实测 5~20ms，而且发生在**点击那一帧**——正是系统要准备 Activity
     * 窗口 + 启动无缝动画的时刻，两者争主线程会让动画起步顿一下。单条缓存时用户在两张
     * 卡片之间来回点，每次切换都要重算；扩到多条即可覆盖"来回看同一批卡/连续开退"的真实场景。
     */
    private val coverMemo = LinkedHashMap<String, SoftwareCover>()

    private fun memoGet(key: String): SoftwareCover? {
        val hit = coverMemo.remove(key) ?: return null
        coverMemo[key] = hit // 重新插入 = 标记为最近使用
        return hit
    }

    private fun memoPut(key: String, cover: SoftwareCover) {
        coverMemo.remove(key)
        coverMemo[key] = cover
        while (coverMemo.size > COVER_MEMO_CAPACITY) {
            val eldest = coverMemo.keys.firstOrNull() ?: break
            coverMemo.remove(eldest)
        }
    }

    fun tryOpen(
        anchor: View?,
        target: VideoTarget?,
        coverUrl: String?,
        coverLarge: Boolean,
        anchorRadiusPx: Float,
        listCover: Pair<Float, Float>? = null
    ): Boolean {
        if (anchor == null || target == null) return false
        if (!enabled()) return false
        if (!OplusViewSeamlessCompat.isVersionSupported()) return false
        // 快速连开时上一轮清理可能还没跑：先取消，否则它会把本次正在使用的
        // 过渡位图清掉，打开动画中途丢内容（表现为打开动画闪变）
        cleanupJob?.cancel()
        cleanupJob = null
        lastDecorated?.let { last ->
            if (last !== anchor) {
                last.foreground = null
                last.background = null
            }
        }
        // 离开上一次会话时留下的 closing 标记必须在这里清掉（而不是等注册成功之后）：
        // 若上一次以 finishWithDefaultTransition 收起后又立刻打开新卡片，closing 会残留为 true，
        // 本函数启动的清理协程会在第一个 64ms 轮询就通过 closing 门、进入 800ms 延时，
        // 若用户在 800ms 内连续开关卡片，它就会在新的打开动画进行中把锚点过渡位图清掉
        // ——表现为打开/返回动画中途丢内容（一帧封面闪空白）。
        SeamlessTransitionState.resetClosing()
        val cover = buildSoftwareCoverDrawable(anchor, coverUrl, coverLarge, anchorRadiusPx)
        if (cover != null) {
            // 与下方 CoverImage 同像素（同图同圆角），平时不可见；框架"绘制 view 内容"
            // 时即拿到封面。background/foreground 双通道，覆盖框架不同的绘制路径。
            val drawable = cover.drawableFor(anchor.resources, anchorRadiusPx)
            anchor.background = drawable
            anchor.foreground = drawable
        } else {
            Log.d(TAG, "software cover unavailable (cache miss?), anchor stays zero-draw")
        }
        val bundle = OplusViewSeamlessCompat.createOpenBundle(
            anchorRadiusPx = anchorRadiusPx,
            listCoverTopRatio = listCover?.first,
            listCoverBottomRatio = listCover?.second,
            contentRect = anchor.screenRect(),
            // 卡片都在列表里：返回时 view 位置相较打开时几乎必然变化，按官方文档
            // 改为 alpha 淡出（无回弹），避免 leash 回弹到已滚走的旧位置
            alphaOutOnPositionChange = true
        )
        val registered = OplusViewSeamlessCompat.registerOpenTransition(
            anchor,
            bundle,
            Callback(anchor, bundle, cover?.bitmap)
        )
        if (!registered) {
            anchor.foreground = null
            anchor.background = null
            Log.i(TAG, "registerOpenTransition=false, fallback to plain open")
            return false
        }
        Log.i(TAG, "registerOpenTransition ok, seamless open pending")
        SeamlessTransitionState.resetClosing()
        SeamlessTransitionState.beginOpen()
        lastDecorated = anchor
        cleanupJob = scheduleAnchorCleanup(anchor)
        route(target, coverUrl, coverLarge, bundle)
        return true
    }

    /**
     * 锚点过渡位图的确定性清理。实测系统 AnimationCallback 在部分固件上不送达，
     * 若只依赖 onEnd(isOpen=false) 清理，位图会永久残留为卡片前景，盖住封面上的
     * 播放量/时长角标。策略：视频页收起（markClosing / beginReturn）后延迟 800ms
     * 清理——成对返回动画约 450ms，此时刻已在动画结束之后；清理仍以 onEnd 为准
     * （回调正常时更精确），此处仅兜底。
     */
    private fun scheduleAnchorCleanup(anchor: View): Job? {
        val scope = cleanupScope ?: return null
        return scope.launch {
            var waited = 0L
            while (!SeamlessTransitionState.closing) {
                delay(64)
                waited += 64
                if (waited > 60_000L) return@launch
            }
            delay(800)
            if (lastDecorated === anchor) {
                Log.i(TAG, "cleanup anchor decoration (fallback, callback missed)")
                anchor.foreground = null
                anchor.background = null
                lastDecorated = null
            }
        }
    }

    /** 点击瞬间读取锚点屏幕矩形：系统取景计算失败时的兜底（纯内存读，无 IO）。 */
    private fun View.screenRect(): Rect? {
        if (width <= 0 || height <= 0) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
    }

    /**
     * 一张封面对应的软件位图 + 按圆角半径缓存的 drawable。
     *
     * 为什么 drawable 也要缓存：`RoundedBitmapDrawableFactory.create` 对 960×540 位图
     * 实测 5~10ms（真机 5 轮采样：5158~10337us，均值 6767us），而它原本发生在**点击那一帧**
     * ——正是系统准备 Activity 窗口 + 启动无缝动画的时刻。位图内容与圆角半径在同一张卡上
     * 是稳定的，因此按半径缓存 drawable，命中后点击帧只剩一次哈希查找。
     */
    private data class SoftwareCover(
        val bitmap: Bitmap,
        private val drawablesByRadius: MutableMap<Float, Drawable> = HashMap(2)
    ) {
        fun drawableFor(
            resources: android.content.res.Resources,
            radiusPx: Float
        ): Drawable = drawablesByRadius.getOrPut(radiusPx) {
            RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                cornerRadius = radiusPx
                setAntiAlias(true)
            }
        }
    }

    /** 从内存缓存取封面位图并转软件配置（返回阶段框架快照为软件画布，可绘制）。 */
    private fun buildSoftwareCoverDrawable(
        anchor: View,
        coverUrl: String?,
        coverLarge: Boolean,
        radiusPx: Float
    ): SoftwareCover? {
        val resolved = if (coverLarge) {
            coverUrlFor(coverUrl, BiliCoverSize.Large)
        } else {
            coverUrlFor(coverUrl, BiliCoverSize.Small43)
        } ?: return null
        memoGet(resolved)?.let { return it }
        val image = SingletonImageLoader.get(anchor.context)
            .memoryCache
            ?.get(MemoryCache.Key(resolved))
            ?.image
        val cached = (image as? BitmapImage)?.bitmap
        if (cached == null) {
            Log.d(TAG, "cover bitmap not in memory cache: $resolved")
            return null
        }
        Log.d(TAG, "cover bitmap found, config=${cached.config}")
        // 系统返回阶段快照为软件画布：硬件位图必须转软件配置，否则绘制抛异常
        val safeBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            cached.config == Bitmap.Config.HARDWARE
        ) {
            runCatching { cached.copy(Bitmap.Config.ARGB_8888, false) }
                .getOrNull()
                ?.also { Log.d(TAG, "hardware bitmap copied to software") }
                ?: return null
        } else {
            cached
        }
        return SoftwareCover(safeBitmap).also { memoPut(resolved, it) }
    }

    /**
     * 每次注册独立的回调（携带锚点与兜底位图）。
     * 打开结束 → 软件位图补进 bundle（返回内容兜底）；返回结束 → 清理锚点内容。
     */
    private class Callback(
        private val anchor: View,
        private val bundle: Bundle,
        private val coverBitmap: Bitmap?
    ) : OplusViewSeamlessCompat.AnimationCallback() {

        override fun onProgress(progress: Float) {
            Log.i(TAG, "progress=$progress")
        }

        override fun onStart(isOpen: Boolean) {
            // 诊断动画问题：确认系统回调是否送达（缺失 = 应用走 1.2s/1.6s 超时兜底）
            Log.i(TAG, "onAnimationStart isOpen=$isOpen")
            if (isOpen) SeamlessTransitionState.beginOpen() else SeamlessTransitionState.beginReturn()
        }

        override fun onEnd(isOpen: Boolean) {
            Log.i(TAG, "onAnimationEnd isOpen=$isOpen")
            if (isOpen) {
                SeamlessTransitionState.endOpen()
                // 打开已完成：补进 BUNDLE_BITMAP 不影响开画面面；若框架在返回开始时
                // 重新读取 bundle，则该位图作为"绘制内容错误"的内容兜底
                coverBitmap?.let {
                    // 回调由系统线程路径触发，任何异常都不应外抛
                    runCatching {
                        bundle.putParcelable(OplusViewSeamless.BUNDLE_BITMAP, it)
                        Log.d(TAG, "BUNDLE_BITMAP attached for return fallback")
                    }
                }
            } else {
                SeamlessTransitionState.endReturn()
                anchor.foreground = null
                anchor.background = null
            }
        }
    }

    private companion object {
        const val TAG = "OplusViewSeamless"

        /**
         * 软件封面 LRU 容量。4 条约 8MB ARGB_8888（960×540×4B），
         * 覆盖"首页首屏几张卡来回点 + 连续开退同一张卡"的真实场景。
         */
        const val COVER_MEMO_CAPACITY = 4
    }
}

/** App 层在根组合提供；null 表示当前环境未接线（页面点击直接走普通打开）。 */
val LocalSeamlessCardOpener = staticCompositionLocalOf<SeamlessCardOpener?> { null }
