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
    private val enabled: () -> Boolean = { true }
) {

    /** 上一张被画了过渡内容的卡片；新打开时清理，避免软件位图副本累积。 */
    private var lastDecorated: View? = null

    /** 软件位图单条缓存：点击路径避免重复做整张位图 copy（主线程上可感知的停顿）。 */
    private var coverMemo: Pair<String, SoftwareCover>? = null

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
        lastDecorated?.let { last ->
            if (last !== anchor) {
                last.foreground = null
                last.background = null
            }
        }
        val cover = buildSoftwareCoverDrawable(anchor, coverUrl, coverLarge, anchorRadiusPx)
        if (cover != null) {
            // 与下方 CoverImage 同像素（同图同圆角），平时不可见；框架"绘制 view 内容"
            // 时即拿到封面。background/foreground 双通道，覆盖框架不同的绘制路径。
            anchor.background = cover.drawable
            anchor.foreground = cover.drawable
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
            Log.d(TAG, "registerOpenTransition=false, fallback to plain open")
            return false
        }
        SeamlessTransitionState.beginOpen()
        lastDecorated = anchor
        route(target, coverUrl, coverLarge, bundle)
        return true
    }

    /** 点击瞬间读取锚点屏幕矩形：系统取景计算失败时的兜底（纯内存读，无 IO）。 */
    private fun View.screenRect(): Rect? {
        if (width <= 0 || height <= 0) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
    }

    private data class SoftwareCover(val drawable: Drawable, val bitmap: Bitmap)

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
        coverMemo?.let { (memoUrl, memoCover) ->
            if (memoUrl == resolved) return memoCover
        }
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
        val drawable = RoundedBitmapDrawableFactory.create(anchor.context.resources, safeBitmap).apply {
            cornerRadius = radiusPx
            setAntiAlias(true)
        }
        return SoftwareCover(drawable, safeBitmap).also { coverMemo = resolved to it }
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
            Log.d(TAG, "progress=$progress")
        }

        override fun onStart(isOpen: Boolean) {
            Log.d(TAG, "onAnimationStart isOpen=$isOpen")
            if (isOpen) SeamlessTransitionState.beginOpen() else SeamlessTransitionState.beginReturn()
        }

        override fun onEnd(isOpen: Boolean) {
            Log.d(TAG, "onAnimationEnd isOpen=$isOpen")
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
    }
}

/** App 层在根组合提供；null 表示当前环境未接线（页面点击直接走普通打开）。 */
val LocalSeamlessCardOpener = staticCompositionLocalOf<SeamlessCardOpener?> { null }
