package com.naaammme.bbspace.core.designsystem.seamless

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import com.oplus.animation.OplusViewSeamless

/**
 * ColorOS「View 无缝动画」（OplusViewSeamless）安全接入层。
 * 官方文档：https://open.oppomobile.com/documentation/page/info?id=13771（能力介绍）、
 * https://open.oppomobile.com/documentation/page/info?id=13772（接入指引）。
 *
 * 能力：点击卡片 View 启动全屏 Activity 时，由系统在窗口层执行「View ⇄ Activity」
 * 的无缝过渡，支持跟手、阻断、回弹；返回时默认成对执行反向动画。
 *
 * 生效前提（官方规格限制）：
 * - 动画只发生在跨 [Activity] 窗口跳转上：[registerOpenTransition] 必须在 startActivity
 *   之前调用，且同一个 Bundle 必须传给 `startActivity(intent, bundle)`。单 Activity 内部
 *   的页面切换（Compose Navigation、覆盖层）没有窗口过渡，调用一律返回 false。
 * - 仅 ColorOS 16.1 及以上、动效等级 B+ 及以上机型；浮窗模式、兼容模式、平行视窗、
 *   分屏下系统不执行动画。
 *
 * 实现说明：运行时类由 ColorOS 系统提供，工程内仅 compileOnly 引入官方桩包，因此所有
 * 入口都必须捕获 [LinkageError]（NoClassDefFoundError/NoSuchMethodError/NoSuchFieldError）
 * 与 [RuntimeException]（官方要求的异常处理），在非 OPPO 机型或版本不匹配时静默降级，
 * 调用方以返回值 false / null 走普通 startActivity 分支。
 */
object OplusViewSeamlessCompat {

    private const val TAG = "OplusViewSeamless"

    /** 系统侧 SDK 版本号；0 表示当前设备不提供该系统类（非 ColorOS 或版本过旧）。 */
    val systemVersion: Int
        get() = runGuarded(fallback = 0) { OplusViewSeamless.getVersion() }

    /**
     * 廉价前置门禁：系统版本是否达到官方示例使用的门槛（`version > OS_16_0_BASE`，
     * 即 37001+，覆盖 16.0 起对未适配 EdgeToEdge 页面的支持）。
     * 仅用于减少无效调用，最终能否生效以 [registerOpenTransition] 返回值为准。
     */
    fun isVersionSupported(): Boolean =
        runGuarded(fallback = false) {
            OplusViewSeamless.getVersion() > OplusViewSeamless.OS_16_0_BASE
        }

    /**
     * 构造打开动画的过渡参数。契约：该 bundle 会同时传给 [registerOpenTransition]
     * 与 `startActivity(intent, bundle)`，两处必须是同一实例。
     *
     * @param anchorRadiusPx 卡片圆角（px）；有圆角的 view 必传，取值来自主题圆角 token。
     * @param fallbackColor 动画中 view 外区域填充色兜底，传具体色值（非资源 id）。
     * @param listCoverTopRatio/listCoverBottomRatio 瀑布流列表上下被遮挡区域占屏幕比例，
     *   传入后返回动画末端会对被遮挡区域做 alpha 渐隐。
     * @param contentRect/contentBitmap 系统 view 取景/取色失败时的兜底项，常规场景不传。
     * @param keepAnchorVisible 动画时原 view 是否可见（默认不可见）。
     * @param anchorHasAlpha 原 view 自带透明度时置 true，系统会按透明卡片处理动画。
     * @param alphaOutOnPositionChange 置 true 时返回动画中原 view 位置已变化则改为
     *   alpha 淡出（不再回弹到新位置），适用于列表返回动画期间卡片可能滚出屏幕的场景；
     *   null 不携带该字段（保持既有调用行为不变）。
     */
    fun createOpenBundle(
        anchorRadiusPx: Float? = null,
        fallbackColor: Int? = null,
        listCoverTopRatio: Float? = null,
        listCoverBottomRatio: Float? = null,
        contentRect: Rect? = null,
        contentBitmap: Bitmap? = null,
        keepAnchorVisible: Boolean = false,
        anchorHasAlpha: Boolean = false,
        alphaOutOnPositionChange: Boolean? = null,
    ): Bundle = Bundle().apply {
        putBoolean(OplusViewSeamless.VIEW_SEAMLESS_OPEN, true)
        anchorRadiusPx?.let { putFloat(OplusViewSeamless.BUNDLE_RADIUS, it) }
        fallbackColor?.let { putInt(OplusViewSeamless.BUNDLE_COLOR, it) }
        if (listCoverTopRatio != null && listCoverBottomRatio != null) {
            putFloatArray(
                OplusViewSeamless.BUNDLE_LIST_COVER,
                floatArrayOf(listCoverTopRatio, listCoverBottomRatio),
            )
        }
        contentRect?.let { putParcelable(OplusViewSeamless.BUNDLE_RECT, it) }
        contentBitmap?.let { putParcelable(OplusViewSeamless.BUNDLE_BITMAP, it) }
        if (keepAnchorVisible) putBoolean(OplusViewSeamless.BUNDLE_VIEW_VISIBLE, true)
        if (anchorHasAlpha) putBoolean(OplusViewSeamless.BUNDLE_VIEW_WITH_ALPHA, true)
        alphaOutOnPositionChange?.let {
            putBoolean(OplusViewSeamless.BUNDLE_ALPHA_OUT_ON_POSITION_CHANGE, it)
        }
    }

    /**
     * 在 startActivity 之前注册「卡片 View → 全屏 Activity」无缝过渡。
     *
     * @return true 表示系统支持：调用方必须用同一个 [bundle] 调用
     *   `startActivity(intent, bundle)`；false 表示不支持（非 OPPO 机型、版本不足、
     *   浮窗/分屏、非 Activity context），调用方直接 `startActivity(intent)`。
     */
    fun registerOpenTransition(
        anchor: View,
        bundle: Bundle,
        callback: AnimationCallback? = null,
    ): Boolean = runGuarded(fallback = false) {
        val context = anchor.context
        if (context !is Activity) {
            Log.w(TAG, "setSeamlessView requires an Activity context, got ${context.javaClass.name}")
            return@runGuarded false
        }
        OplusViewSeamless.setSeamlessView(anchor, context, bundle, callback?.delegate)
    }

    /**
     * 目标 Activity finish 时强制使用 alpha 淡出（无回弹），适用于返回时原卡片
     * 内容或位置已变化的场景；须在目标 Activity 的 finish 之前调用。
     */
    fun forceLeashAlphaOutOnFinish(activity: Activity, force: Boolean = true) {
        runGuarded(fallback = Unit) { OplusViewSeamless.setForceLeashAlphaOut(activity, force) }
    }

    /** 目标 Activity finish 时不做任何返回动效（打开时仍有无缝动画），如跟手拖动返回场景。 */
    fun skipBackAnimOnFinish(activity: Activity) {
        runGuarded(fallback = Unit) { OplusViewSeamless.skipBackAnim(activity) }
    }

    /** 目标 Activity finish 时改用系统默认横切过渡（不做无缝返回），如原 view 已销毁或宿主已重建。 */
    fun useDefaultTransitionOnFinish(activity: Activity) {
        runGuarded(fallback = Unit) { OplusViewSeamless.setSkipViewSeamless(activity) }
    }

    /** 立即结束当前进行中的无缝动画；一般无需调用，官方示例为返回动画过程中用户开始滑动列表。 */
    fun finishCurrentAnimation(): Boolean =
        runGuarded(fallback = false) { OplusViewSeamless.finishCurrentAnimation() }

    private inline fun <T> runGuarded(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: LinkageError) {
            // compileOnly：非 ColorOS 机型无系统类，或系统版本与桩包不匹配
            Log.d(TAG, "view seamless unavailable: ${e.javaClass.simpleName}")
            fallback
        } catch (e: RuntimeException) {
            // 官方「异常处理」一节要求捕获的版本不匹配运行时异常
            Log.d(TAG, "view seamless runtime error: $e")
            fallback
        }

    /**
     * 无缝动画进度回调。[onProgress] 的 progress 可用于判断当前是否处于回弹状态；
     * onStart/onEnd 的 [isOpen] 区分打开与返回动画。
     */
    abstract class AnimationCallback {
        abstract fun onProgress(progress: Float)
        open fun onStart(isOpen: Boolean) {}
        open fun onEnd(isOpen: Boolean) {}

        // 惰性构造：仅在 runGuarded 内访问，避免在非 ColorOS 机型上因加载
        // OplusViewSeamless.AnimationCallback 抛出 NoClassDefFoundError
        internal val delegate: OplusViewSeamless.AnimationCallback by lazy {
            object : OplusViewSeamless.AnimationCallback() {
                override fun animationProgress(progress: Float) = onProgress(progress)
                override fun onAnimationStart(isOpen: Boolean) = onStart(isOpen)
                override fun onAnimationEnd(isOpen: Boolean) = onEnd(isOpen)
            }
        }
    }
}
