package com.naaammme.bbspace.core.designsystem.glass.liquid

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy

/**
 * `PixelCopy` 实现的亮度采样器（**Android 侧**；默认关闭、可注入）。
 *
 * ## 为什么默认关闭
 * `PixelCopy` 每采一次都要：
 * 1. 在窗口上做一次 GPU→CPU 回读（贵，且与渲染线程争用）；
 * 2. 分配一张 Bitmap；
 * 3. 异步回调（拿到的值永远比"当前帧"晚一帧以上）。
 *
 * 因此它**不该是默认路径**：默认 `enabled = false` 时 [sample] 恒返回 null，
 * 上层走"不切换、保持现状"的保守分支。宿主只有在确认需要自适应前景（例如
 * Dock 悬浮在内容上、文案可能骑在亮画面上）时，才显式构造并 `requestSample()`。
 *
 * ## 用法（宿主侧）
 * ```
 * val sampler = PixelCopyLuminanceSampler(
 *     activityProvider = { activity },
 *     sampleBoundsProvider = { dockBoundsInWindow() },
 *     enabled = true,
 * )
 * sampler.requestSample()          // 滚动停止后请求一次（异步）
 * state.update(sampler.sample())   // 下一帧/下一拍读结果
 * ```
 *
 * ## 与纯逻辑的分界
 * 迟滞/保守值全部在 [LiquidGlassReadabilityPolicy]（JVM 可测）；本类只做
 * "取窗口像素 → 平均亮度"这一件 Android 特有的事，且**失败一律返回 null**
 * （不返回 0 冒充黑背景）。
 *
 * ## 接线状态（2026-10-18，B 组收口）
 * **当前无生产消费者**（默认 `enabled = false`，全仓没有 `PixelCopyLuminanceSampler(`
 * 的调用点）。不接线的三条理由与接线步骤见 `docs/2026-10-18-B组-未接线符号接线步骤.md`：
 * 1. **默认关闭是硬约束**：默认路径零回读。开它等于在渲染线程旁每拍多一次
 *    GPU→CPU 回读 + 一次 Bitmap 分配，必须由宿主在**明确的低频时机**（滚动停止、
 *    转场结束、折叠态切换）显式 `requestSample()`，不能每帧；
 * 2. 迟滞是否抖（0.58/0.42 中间带够不够宽）**只能真机验证**——本设备档下无
 *    emulator/设备可跑 UI，静态改动无法证明；
 * 3. 回读区域必须是玻璃片自身在窗口里的 bounds（`sampleBoundsProvider`），
 *    与 `GlassEffect` 的形状/圆角强相关，跨模块接线需同步改 `app`/`feature`
 *    （本轮不改 feature）。
 *
 * 接线顺序（建议）：先把采样器接到**底栏 Dock**（常驻、bounds 稳定、采样时机明确），
 * 真机确认迟滞不抖后再接顶栏（有滚动折叠，采样时机更复杂）。完整步骤、代码骨架、
 * 验收清单见上述 docs。
 */
class PixelCopyLuminanceSampler(
    private val activityProvider: () -> Activity?,
    private val sampleBoundsProvider: () -> Rect?,
    private val enabled: Boolean = false
) : LiquidGlassLuminanceSampler {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var latestLuminance: Float? = null

    /** 最近一次成功采样（0..1）；没有成功过为 null。 */
    override fun sample(): Float? = if (enabled) latestLuminance else null

    /**
     * 请求一次异步采样。
     *
     * 结果写入内部缓存，供下一次 [sample] 读取；连续调用时以后到的为准
     * （旧请求不取消 —— PixelCopy 没有取消接口，且一次采样只有几 KB 回读）。
     */
    fun requestSample() {
        if (!enabled) return
        // PixelCopy 自 API 26 起可用；本模块 minSdk 33，这里保留判断是为了让
        // 该文件在低 minSdk 的宿主复用时不至于直接崩。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val activity = activityProvider() ?: return
        val bounds = sampleBoundsProvider() ?: return
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val window = activity.window ?: return
        PixelCopy.request(
            window,
            bounds,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    latestLuminance = averageLuminance(bitmap)
                }
                // 失败保留上一次的值：一次采样失败不该让前景色回退/闪烁
            },
            mainHandler
        )
    }

    /** 清零（宿主切换/关闭玻璃时）。 */
    fun reset() {
        latestLuminance = null
    }

    /**
     * 平均相对亮度（0..1）。
     *
     * 用 sRGB 三通道加权（Rec.709 系数），与 Compose `Color.luminance()` 的
     * 线性化口径**不同**：这里刻意不线性化 —— 迟滞阈值 0.58/0.42 是上游按
     * "感知亮度"校准的，线性化会把中间调整体压暗、让阈值位置漂移。
     * 两种口径只差一个单调映射，判定结构不变；选与阈值同源的那种。
     */
    private fun averageLuminance(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0f
        var sum = 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = bitmap.getPixel(x, y)
                val r = (color shr 16 and 0xFF) / 255f
                val g = (color shr 8 and 0xFF) / 255f
                val b = (color and 0xFF) / 255f
                sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
        }
        return (sum / (width * height)).coerceIn(0f, 1f)
    }
}
