package com.naaammme.bbspace.core.designsystem.color

import android.graphics.Bitmap
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
     * **主动**为某个键取一次色：从 Coil 缓存取位图（不重新下载）→ 提取 → 落缓存 → 回调。
     *
     * ## 为什么需要"主动"这条路（真机实测定位）
     *
     * 取色只挂在 Coil 的 `onSuccess` 上，而 **`onSuccess` 只在真正解码时触发一次**。
     * 首页的封面预加载会把位图填进内存缓存，于是卡片走**缓存命中**路径、
     * 解码不重走 → `onSuccess` 不再触发 → **这张卡永远拿不到颜色**。
     *
     * 而卡片里的 `remember(coverTintKey) { getCachedColor(...) }` 只在首次组合
     * 求值一次，之后也不会重算。两者叠加：屏上一部分卡片完全没有染色
     *（实测 6 张里 3 张饱和度 0%）。
     *
     * 这个方法由卡片在组合时主动调用 —— **不依赖任何回调时机**。
     * 图片没进缓存时 Coil 会自己去取（走网络/磁盘），所以冷启动也成立。
     *
     * @param sizePx 期望解码尺寸；与卡片实际展示同档，避免多解码一份
     * @param onExtracted 提取成功回调（主线程）
     */
    fun requestExtraction(
        appContext: android.content.Context,
        cacheKey: String,
        url: String,
        sizePx: Pair<Int, Int>,
        scope: CoroutineScope,
        onExtracted: ((Color) -> Unit)? = null
    ) {
        if (cacheKey.isBlank() || url.isBlank()) return
        getCachedColor(cacheKey)?.let { cached ->
            if (onExtracted != null) scope.launch(Dispatchers.Main) { onExtracted(cached) }
            return
        }
        scope.launch(Dispatchers.Default) {
            val color = runCatching {
                val request = coil3.request.ImageRequest.Builder(appContext)
                    .data(url)
                    .size(sizePx.first, sizePx.second)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .memoryCachePolicy(coil3.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil3.request.CachePolicy.ENABLED)
                    .build()
                val result = coil3.SingletonImageLoader.get(appContext).execute(request)
                val bitmap = (result.image as? coil3.BitmapImage)?.bitmap ?: return@runCatching null
                extractRepresentativeColor(bitmap)
            }.getOrNull() ?: return@launch
            synchronized(colorCache) { colorCache.put(cacheKey, color) }
            withContext(Dispatchers.Main) { onExtracted?.invoke(color) }
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
     * 选「面积大、且颜色可用」的那块。
     *
     * 过滤掉三类无用色：近灰（饱和度 <0.08，染上去等于没染）、近黑（明度 <0.08，
     * 与卡片底色无区别）、近白（明度 >0.96，会把卡片洗白）。
     * 过滤后若为空则**退回未过滤全集**——宁可染一个平庸色，也不要无颜色可用。
     *
     * ## ⚠️ 为什么不用 `android.graphics.Color.colorToHSV`
     *
     * 原实现用 `AndroidColor.colorToHSV(rgb, hsv)`。那是 **Android framework 方法**，
     * 在 JVM 单测里被 stub 成**空实现**（`unitTests.isReturnDefaultValues = true`）——
     * `hsv[]` 三个分量恒为 0，于是：
     * - 过滤条件 `hsv[1] < 0.08f` 对**所有**颜色成立 → `useful` 恒为空
     * - 退化成"未过滤全集 + 按面积选"
     *
     * 后果有两层：① 这条分支在单测里**从未被真正验证过**；② 同一个函数在 JVM 与真机上
     * 行为不同，测试给出的信心是假的。
     * （发现经过：改用综合评分后单测报
     *  `IllegalArgumentException: background can not be translucent: #ff3a6ea5` ——
     *   那是 `Palette.Swatch` 拿被 stub 掉的 HSV 去算 HSL 背景色时抛的。）
     *
     * 现在换成**纯 Kotlin 的 HSV 转换**（[rgbToHsv]），与 framework 语义一致，
     * 且在任何环境下都真的执行 —— 可测性本身就是正确性的一部分。
     */
    internal fun resolveRepresentativeSwatch(
        swatches: List<Palette.Swatch>,
    ): Palette.Swatch? {
        if (swatches.isEmpty()) return null
        val useful = swatches.filterNot { swatch ->
            val hsv = rgbToHsv(swatch.rgb)
            hsv[1] < 0.08f || hsv[2] < 0.08f || hsv[2] > 0.96f
        }
        return (useful.ifEmpty { swatches }).maxByOrNull { swatch ->
            // ── 为什么不是纯 `population`（真机实测纠正，这是"颜色对应不上"的根因）──
            //
            // 上游用 `maxByOrNull { it.population }`（纯面积）。照搬后在 BBSpace 上
            // 频繁选到**低饱和的灰**或**近乎纯黑**的色块，因为封面里"面积最大"的
            // 那块常常是背景/压暗区，而不是人眼认定的主色。
            //
            // 实测（设备上真实封面，同一套 Palette 参数）：
            //   卡 A 候选：`#747469` 面积 216 饱和  9.5% 明度 45.5% ← 纯面积会选它（评分 16.9）
            //              `#866857` 面积  94 饱和 35.1% 明度 52.5% ← 人眼看到的主色（评分 31.5）
            //   卡 B 候选：`#0a1318` 面积 185 饱和 58.3% 明度 **9.4%** ← 纯面积选它（评分 18.5）
            //              `#253747` 面积 159 饱和 47.9% 明度 27.8%  ← 才是能用的蓝（评分 38.5）
            // 选到前者时，染出来的简介区是灰的 / 混白后几乎无彩 —— 用户看到的就是
            // "简介区颜色和预览图对应不上"。
            //
            // 所以改为**面积 × 饱和度 × 明度可用性**：
            // - 乘 `saturation`：同等面积下优先有色相的那块（灰块染了等于没染）；
            // - 乘明度权重：过暗的块即使面积大也不该当选 —— 它混进白底只会得到灰。
            //   用 `(v / 0.55).coerceAtMost(1)` 让"足够亮"的块不再互相拉开差距，
            //   避免变成"唯亮度论"（那会选到白色高光）。
            //
            // 面积仍是主导（大片色块优先），但"又大又灰/又大又黑"不再胜出。
            // 上游那条"小面积字幕色不得压过主体画面"的性质也保住了：
            // 实测主体蓝（面积 9000，评分 5836）完胜字幕红（面积 200，评分 200）。
            val hsv = rgbToHsv(swatch.rgb)
            val saturation = hsv[1].coerceIn(0f, 1f)
            val valueUsability = (hsv[2] / 0.55f).coerceIn(0f, 1f)
            swatch.population * saturation * valueUsability
        }
    }

    /**
     * 纯 Kotlin 的 RGB→HSV，语义与 `android.graphics.Color.colorToHSV` 一致：
     * 返回 `[hue(0..360), saturation(0..1), value(0..1)]`。
     *
     * 存在的唯一理由是**可测**（见 [resolveRepresentativeSwatch] 的说明）：
     * framework 版本在 JVM 单测里是空实现，会让依赖它的分支静默走错路。
     */
    internal fun rgbToHsv(rgb: Int): FloatArray {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val value = max
        val saturation = if (max <= 0f) 0f else delta / max
        val hue = if (delta <= 0f) {
            0f
        } else {
            val h = when (max) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
            if (h < 0f) h + 360f else h
        }
        return floatArrayOf(hue, saturation, value)
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
