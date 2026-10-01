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

    /**
     * 取色采样的边长（px）。palette 最终只用 48×48 的面积，留一档余量是为了缩图时
     * 不把相邻色块混成一坨；96² 的 ARGB_8888 ≈ 36KB，比旧写法"按封面尺寸整张复制"
     * （640×480 ≈1.2MB、960×540 ≈2.1MB）小两个数量级。见 [extractRepresentativeColor]。
     */
    private const val PALETTE_SAMPLE_PX = 96

    private val colorCache = LruCache<String, Color>(MAX_CACHE_SIZE)

    /** 同步读取已缓存的封面代表色；未命中返回 null（调用方据此决定是否触发提取）。 */
    fun getCachedColor(cacheKey: String): Color? {
        if (cacheKey.isBlank()) return null
        return synchronized(colorCache) {
            colorCache.get(cacheKey)
        }
    }

    /**
     * 同键「在飞」去重：同一封面的取色同时只允许一个任务在跑。
     * 卡片槽位复用、同封面多张卡同帧组合时，旧写法会并发发起多个等价请求
     * （每个都是一整套下载/解码），这里压成一次，其余调用者等下一次组合再查缓存。
     */
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Job>()

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
            // **Main.immediate**：调用点就在主线程（卡片组合里的 LaunchedEffect），
            // 用 `Dispatchers.Main` 会再排一次队 → 缓存明明命中了，染色却要晚一帧才出现
            // （滚动里表现为"卡片先白后有色"）。对照 BiliPai `7ee5c10b5` 的同类修正。
            // 下面两处 `withContext(Dispatchers.Main)` 不改：那两处是从 Default 线程
            // 跳回主线程，本来就要排队，immediate 不改变任何行为。
            if (onExtracted != null) scope.launch(Dispatchers.Main.immediate) { onExtracted(cached) }
            return
        }
        synchronized(inFlight) {
            if (inFlight.containsKey(cacheKey)) return
            val job = scope.launch(Dispatchers.Default) {
                val color = runCatching {
                    val request = coil3.request.ImageRequest.Builder(appContext)
                        .data(url)
                        .size(sizePx.first, sizePx.second)
                        // **不要显式指定 memoryCacheKey / diskCacheKey**：Coil 的缓存键在
                        // 未显式指定时由 `ImageRequest.data` 派生，而卡片显示请求与首页预加载
                        // 都是「data = 变体 URL、无显式键」（BiliAsyncImage.rememberBiliImageRequest /
                        // HomeVideoPage 的预加载）。这里若把键设成**未解析的封面 URL**（取色键），
                        // 两条请求就永远对不上：本请求对缓存必双 miss → 同一封面被下载两次、
                        // 解码两次，还各写一份内存/磁盘缓存（实测冷滚动整屏卡片全部命中这条
                        // 重复路径）。取色结果的键（colorCache）仍用 cacheKey，与请求缓存无关。
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
            inFlight[cacheKey] = job
            job.invokeOnCompletion {
                synchronized(inFlight) {
                    // 只删自己登记的那条：极晚的完成回调不能误删后来者
                    if (inFlight[cacheKey] === job) inFlight.remove(cacheKey)
                }
            }
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
     * 对封面聚类取代表色。
     *
     * `resizeBitmapArea(48*48)` 把 palette 的采样压到约 2300 像素：它的耗时与像素数
     * 近似线性，这个尺寸下足以反映主色调。`clearFilters()` 关掉默认的白/黑/近白过滤
     * ——暗色封面（夜景、黑底字幕）本来就该染深色，过滤掉会让它无色可染。
     *
     * ## 为什么先画进 [PALETTE_SAMPLE_PX] 见方的小图（2026-10-01，对照 BiliPai `8cfc4246b`）
     * 旧写法是 `bitmap.copy(Bitmap.Config.ARGB_8888, false)`：HARDWARE 位图读不了像素，
     * 于是**按封面原尺寸**复制一份软件位图 —— 640×480 ≈1.2MB、960×540 ≈2.1MB，
     * 而且**没人回收它**，要等 GC。每张染色卡片一次，滚动时就是持续制造大对象，
     * 而 GC 停顿恰恰落在滚动帧上（这类"看起来没泄漏"的分配最容易被忽略）。
     * palette 反正只按 48×48 面积采样，先降采样不损失任何信息：改成画进
     * [PALETTE_SAMPLE_PX]² ≈36KB 的小图并在 finally 里 recycle，**分配少两个数量级**。
     *
     * 线性缩放的各向异性拉伸不改变**相对面积**（仿射变换对所有区域同比例缩放），
     * 所以 swatch 的 population 排序与整图一致，选色结论不变。
     */
    internal fun extractRepresentativeColor(bitmap: Bitmap): Color? {
        return runCatching {
            if (bitmap.isRecycled) return@runCatching null
            val sampled = sampleIntoSoftwareBitmap(bitmap) ?: return@runCatching null
            try {
                val palette = Palette.from(sampled)
                    .resizeBitmapArea(48 * 48)
                    .maximumColorCount(16)
                    .clearFilters()
                    .generate()
                resolveRepresentativeSwatch(palette.swatches)?.rgb?.let { Color(it) }
            } finally {
                // 只回收我们自己造的那张；软件位图路径返回的是入参本身，不归我们管
                if (sampled !== bitmap && !sampled.isRecycled) sampled.recycle()
            }
        }.onFailure {
            // 取色失败的表现是"卡片没颜色"这种**静默降级**：不崩、不报错、单测也照绿。
            // 没有这行日志就只能靠人眼发现（2026-10-01 的真机回归就是这么踩到的）。
            android.util.Log.w("VideoCardCoverColor", "封面取色失败：${it.message}", it)
        }.getOrNull()
    }

    /**
     * HARDWARE 位图 → 小尺寸软件位图；软件位图原样返回。
     *
     * **必须走 [Bitmap.copy]**：HARDWARE 位图不支持读像素，也**不允许画进软件 Canvas**
     * （`Canvas.drawBitmap` 会抛 `Software rendering doesn't support hardware bitmaps`）。
     * 2026-10-01 有一轮改动想用"画进 96×96 小图"绕开整尺寸复制，结果在真机上把整条取色
     * 打成了静默失败（卡片染色全丢）—— 这条注释就是那次事故的结论，别再试第二次。
     *
     * 真正能拿到的收益在这里：复制出来的整尺寸 ARGB_8888（640×480≈1.2MB、960×540≈2.1MB）
     * 用完**立刻 recycle**，不再等 GC —— 旧写法最大的问题不是峰值，而是生命周期。
     * 峰值不变，但滚动时同时存活的这种大块从"一堆"变成"零"。
     */
    private fun sampleIntoSoftwareBitmap(source: Bitmap): Bitmap? {
        val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        if (!hardware) return source
        val full = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: return null
        val small = runCatching {
            Bitmap.createScaledBitmap(full, PALETTE_SAMPLE_PX, PALETTE_SAMPLE_PX, true)
        }.getOrNull()
        return if (small != null && small !== full) {
            if (!full.isRecycled) full.recycle()
            small
        } else {
            // 缩放失败就用整图继续（palette 内部自己会 resize），交给调用方在 finally 里回收
            full
        }
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
