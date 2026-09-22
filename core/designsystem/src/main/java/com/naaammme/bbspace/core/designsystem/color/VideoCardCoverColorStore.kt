package com.naaammme.bbspace.core.designsystem.color

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频封面「代表色」提取与缓存（移植自 BiliPai 上游 `VideoCardCoverColorStore`）。
 *
 * 用途：推荐页卡片按封面主色做动态染色（容器底色 / 封面边缘微光 / 描边），
 * 让卡片与画面内容产生色彩呼应。
 *
 * 设计要点（照搬上游，勿简化）：
 * 1. **纯异步单向流**：提取在 [Dispatchers.Default]，回主线程回调。封面解码本身已在
 *    Coil 线程池，若再在组合期同步取色会阻塞首帧；
 * 2. **内存 LRU 缓存**：同一张封面（按 URL 归一化后的 key）只算一次。滚动回来
 *    命中缓存即同步返回，不产生二次提取；
 * 3. **面积优先而非饱和度优先**：见 [resolveRepresentativeSwatch] —— 这是本功能
 *    观感好坏的关键，字幕色 / 高光边往往是画面里最艳的颜色，但它们面积小，
 *    取它们会让卡片染上与实际内容无关的颜色。
 */
object VideoCardCoverColorStore {

    private const val MAX_CACHE_SIZE = 128

    private val colorCache = LruCache<String, Color>(MAX_CACHE_SIZE)

    /** 同步读取已缓存的封面代表色；未命中返回 null（调用方据此决定是否触发提取）。 */
    fun getCachedColor(cacheKey: String): Color? {
        if (cacheKey.isBlank()) return null
        return synchronized(colorCache) {
            colorCache.get(cacheKey)
        }
    }

    /**
     * 异步提取封面代表色并写入缓存，随后在主线程回调 [onColorExtracted]。
     *
     * 命中缓存时**同步**回调，避免已经算过的封面还要多等一帧才上色。
     */
    fun extractColorAsync(
        cacheKey: String,
        bitmap: Bitmap,
        scope: CoroutineScope,
        onColorExtracted: (Color) -> Unit
    ) {
        if (cacheKey.isBlank()) return

        getCachedColor(cacheKey)?.let {
            onColorExtracted(it)
            return
        }

        scope.launch(Dispatchers.Default) {
            val color = extractRepresentativeColor(bitmap) ?: return@launch
            synchronized(colorCache) {
                colorCache.put(cacheKey, color)
            }
            withContext(Dispatchers.Main) {
                onColorExtracted(color)
            }
        }
    }

    /**
     * 对整张封面聚类，按 swatch 面积选代表色。
     *
     * `resizeBitmapArea(48*48)` 把采样压到约 2300 像素：palette 的耗时与像素数近似线性，
     * 这个尺寸下单张实测在毫秒级，且足以反映主色调。`clearFilters()` 关掉默认的
     * 白/黑/近白过滤——暗色封面（夜景、黑底字幕）本来就该染深色，过滤掉会让它无色可染。
     */
    internal fun extractRepresentativeColor(bitmap: Bitmap): Color? {
        return runCatching {
            if (bitmap.isRecycled) return@runCatching null

            // HARDWARE 位图不能直接给 palette 采样（读像素会抛），需先转 ARGB_8888
            val safeBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bitmap.config == Bitmap.Config.HARDWARE
            ) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
            } else {
                bitmap
            }

            val palette = Palette.from(safeBitmap)
                .resizeBitmapArea(48 * 48)
                .maximumColorCount(16)
                .clearFilters()
                .generate()

            resolveRepresentativeSwatch(palette.swatches)?.rgb?.let { Color(it) }
        }.getOrNull()
    }

    /**
     * 选「面积最大且有用」的颜色。
     *
     * 过滤掉三类无用色：近灰（饱和度 <0.08，染上去等于没染）、近黑（明度 <0.08，
     * 与卡片底色无区别）、近白（明度 >0.96，会把卡片洗白）。
     * 过滤后若为空则**退回未过滤全集**——宁可染一个平庸色，也不要无颜色可用。
     */
    internal fun resolveRepresentativeSwatch(
        swatches: List<Palette.Swatch>,
    ): Palette.Swatch? {
        if (swatches.isEmpty()) return null
        val useful = swatches.filterNot { swatch ->
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(swatch.rgb, hsv)
            hsv[1] < 0.08f || hsv[2] < 0.08f || hsv[2] > 0.96f
        }
        return (useful.ifEmpty { swatches }).maxByOrNull { it.population }
    }

    /**
     * 内存紧张时把缓存裁到 [maxSize]（由 `Application.onTrimMemory` 调用）。
     * 上游策略：切后台只保留可视卡片与壁纸调色板，历史封面色可丢。
     */
    fun trimToSize(maxSize: Int) {
        synchronized(colorCache) {
            colorCache.trimToSize(maxSize)
        }
    }

    fun clear() {
        synchronized(colorCache) {
            colorCache.evictAll()
        }
    }
}
