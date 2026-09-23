package com.naaammme.bbspace.core.designsystem.color

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /**
     * 取色结果的**可观察**版本：每完成一次取色就发射一次 (key, color)。
     *
     * ## 为什么必须有它（第三个时序缺口，实测定位）
     *
     * 卡片里是 `var coverTint by remember(coverCacheKey) { mutableStateOf(getCachedColor(...)) }`。
     * `remember` **只在首次组合求值一次**。而预加载侧取色是异步的（在 `Dispatchers.IO` 上
     * 解码 + Palette 聚类），卡片往往**先**组合、取色**后**完成 —— 于是：
     *
     * 1. 卡片首次组合读到 null；
     * 2. 取色完成、写进 cache；
     * 3. 但 `remember` 不会重算，且卡片走的又是 Coil 缓存命中路径、
     *    `onSuccess` 不再触发 → **这张卡的染色永远是 null**。
     *
     * 表现就是"大部分卡片没有取色"，而且取色日志明明有记录 —— 正是本功能最迷惑人的一处。
     *
     * 有了这个 Flow，卡片可以订阅、在取色落地时补上颜色，不再依赖"谁先谁后"。
     */
    private val _colorUpdates = MutableSharedFlow<Pair<String, Color>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val colorUpdates: SharedFlow<Pair<String, Color>> = _colorUpdates.asSharedFlow()

    /** 同步读取已缓存的封面代表色；未命中返回 null（调用方据此决定是否触发提取）。 */
    fun getCachedColor(cacheKey: String): Color? {
        if (cacheKey.isBlank()) return null
        return synchronized(colorCache) {
            colorCache.get(cacheKey)
        }
    }

    /**
     * 直接写入一份取色结果，并通知订阅者。
     *
     * 用途：**预加载侧取色**。预加载本来就持有解码好的位图，在那边算完直接落缓存，
     * 卡片首次组合时 [getCachedColor] 就命中 —— 不依赖 Coil `onSuccess` 的触发时机。
     *
     * 为什么必须有这条路（实测定位）：`onSuccess` **只在真正解码时触发一次**。
     * 而预加载的目的正是让卡片命中内存缓存，于是卡片走的是缓存命中路径、
     * `onSuccess` 不再触发 → **取色永远不会发生**。日志实测：屏上 6~8 张卡，
     * 只有 2 条取色记录。这是"很多卡片没有取色"的直接原因。
     */
    fun putCachedColor(cacheKey: String, color: Color) {
        if (cacheKey.isBlank()) return
        synchronized(colorCache) {
            colorCache.put(cacheKey, color)
        }
        // 通知订阅者（卡片据此补色）。tryEmit 不阻塞：没有订阅者或缓冲满时静默丢弃，
        // 卡片下次组合仍能从 cache 读到。
        _colorUpdates.tryEmit(cacheKey to color)
    }

    /**
     * **同步**从位图提取代表色并落缓存，返回取到的色（失败返回 null）。
     *
     * 给预加载侧用：那边已经在 `Dispatchers.IO` 上、且手上就有解码好的位图，
     * 直接算完落缓存即可。调用方**不要在主线程序列里调**（Palette 聚类是 CPU 活）。
     *
     * 与 [extractColorAsync] 的分工：那个给"卡片拿到位图后异步补算"用（回主线程回调），
     * 这个给"预加载侧一次算完"用（无回调）。两条路写的是同一个 [colorCache]，
     * 键也相同，所以谁先到都算数、不会互相覆盖出问题。
     */
    fun extractAndCache(cacheKey: String, bitmap: Bitmap): Color? {
        if (cacheKey.isBlank()) return null
        getCachedColor(cacheKey)?.let { return it }
        val color = extractRepresentativeColor(bitmap) ?: return null
        putCachedColor(cacheKey, color)
        return color
    }

    /**
     * **主动**为某个键取一次色：从 Coil 缓存里把位图取出来（不重新下载）再提取。
     *
     * ## 为什么必须有"主动"这条路（第四个时序缺口，实测定位）
     *
     * 前面三条路都依赖某个**时机**，而它们都会漏掉同一批卡片 —— **当前可见的那些**：
     *
     * | 路径 | 触发条件 | 漏掉谁 |
     * |---|---|---|
     * | 卡片 `onSuccess` | Coil **真正解码**时触发一次 | 图片已在缓存里的卡（预加载刚填过） |
     * | 预加载侧取色 | 只处理 `lastVisible+1 .. +AHEAD` | **当前可见的卡** |
     *
     * 两者合起来的空洞正好是"屏幕上的卡片"：预加载只填后面的、可见卡的 `onSuccess`
     * 又因为缓存命中不触发。实测日志 `RENDER ... tint=null` 全是可见卡，
     * 而同一时刻 `preload-extracted` 有 11 条 —— **取色在跑，但屏上没一张有颜色**。
     *
     * 这个方法由卡片在组合时主动调用：**不依赖任何回调时机**。图片没进缓存时
     * Coil 会自己去取（走网络/磁盘），所以它对"直接打开首页"这种冷启动也成立。
     *
     * @param sizePx 期望的解码尺寸；与卡片实际展示同档，避免多解码一份
     */
    fun requestExtraction(
        appContext: android.content.Context,
        cacheKey: String,
        url: String,
        sizePx: Pair<Int, Int>,
        scope: CoroutineScope
    ) {
        if (cacheKey.isBlank() || url.isBlank()) return
        if (getCachedColor(cacheKey) != null) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = coil3.request.ImageRequest.Builder(appContext)
                    .data(url)
                    .size(sizePx.first, sizePx.second)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .memoryCachePolicy(coil3.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil3.request.CachePolicy.ENABLED)
                    .build()
                val result = coil3.SingletonImageLoader.get(appContext).execute(request)
                val bitmap = (result.image as? coil3.BitmapImage)?.bitmap ?: return@runCatching
                extractAndCache(cacheKey, bitmap)
            }
        }
    }

    /**
     * 异步提取封面代表色并写入缓存，随后在主线程回调 [onColorExtracted]。
     *
     * 命中缓存时**同步**回调，避免已经算过的封面还要多等一帧才上色。
     *
     * @param onColorExtracted 提取成功（或命中缓存）时回调，主线程。
     * @param onExtractionFailed 提取失败时回调，主线程；**可选，仅用于诊断**。
     *
     * ## 为什么需要 [onExtractionFailed]
     *
     * 原实现失败时**静默 return**：调用方只看到"回调没来"，分不清是
     * ①图片没加载完 ②位图不可用（HARDWARE/recycled）③Palette 聚类没出结果。
     * 用户报「很多卡片没有取色」时，这三种原因的修法完全不同，而现场**没有任何痕迹**。
     * 加上这个回调后，debug 构建下能直接看出是哪一类。
     *
     * 注意：`cacheKey` 为空是**调用方约定**上的"不该发生"（见 [getCachedColor]），
     * 也走失败回调 —— 否则键算错会表现成"静默不上色"，与本次修的两个 bug 同类。
     */
    fun extractColorAsync(
        cacheKey: String,
        bitmap: Bitmap,
        scope: CoroutineScope,
        onColorExtracted: (Color) -> Unit,
        onExtractionFailed: ((reason: String) -> Unit)? = null
    ) {
        if (cacheKey.isBlank()) {
            onExtractionFailed?.invoke("blank cache key")
            return
        }

        getCachedColor(cacheKey)?.let {
            onColorExtracted(it)
            return
        }

        scope.launch(Dispatchers.Default) {
            if (bitmap.isRecycled) {
                withContext(Dispatchers.Main) { onExtractionFailed?.invoke("bitmap recycled") }
                return@launch
            }
            val color = extractRepresentativeColor(bitmap)
            if (color == null) {
                withContext(Dispatchers.Main) {
                    onExtractionFailed?.invoke(
                        "palette returned no swatch (config=${bitmap.config} " +
                            "${bitmap.width}x${bitmap.height})"
                    )
                }
                return@launch
            }
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
