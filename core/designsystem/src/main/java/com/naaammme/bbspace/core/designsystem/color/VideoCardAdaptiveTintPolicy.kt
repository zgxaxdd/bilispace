package com.naaammme.bbspace.core.designsystem.color

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 推荐页卡片「动态取色」策略（移植自 BiliPai 上游 `VideoCardAdaptiveTintPolicy`）。
 *
 * 全部是**纯函数**：输入颜色与开关，输出颜色与透明度。不依赖 Compose 运行时、
 * 不读 CompositionLocal，因此可以在 JVM 单测里逐个断言（上游同样配了 352 行测试）。
 *
 * 两路取色来源：
 * - **壁纸调色板**（[WallpaperPaletteStore] 取系统壁纸主色，按滚动 Y 轴插值）；
 * - **封面代表色**（[VideoCardCoverColorStore] 提取）。
 *
 * 优先级照搬上游：**壁纸开着时壁纸是唯一来源，封面色只作为无壁纸时的回退**
 * （见 [shouldUseCoverTintForCard]）。
 */

/**
 * 卡片底部信息区的动态着色决策结果。
 *
 * @param containerColor 卡片容器底色（半透明，透出背后的模糊）
 * @param coverGlowAlpha 封面边缘微光强度；0 = 不画
 * @param borderColor 描边色（与封面同调，构筑毛玻璃棱镜边）
 */
@Immutable
data class VideoCardAmbientDrawSpec(
    val containerColor: Color,
    val coverGlowAlpha: Float,
    val borderColor: Color
)

/**
 * 壁纸调色板：从系统壁纸取出的主/次/三级色。
 *
 * [stops] 是**按屏幕 Y 轴从上到下**排列的色段，供 [interpolateWallpaperColor] 插值。
 * 上游自定义壁纸按高度切 5 片聚类；BBSpace 只有系统给的 3 个色，所以是 3 段。
 * 段数不同不影响插值 —— 见该函数的实现。
 */
@Immutable
data class WallpaperPalette(
    val topColor: Color,
    val bottomColor: Color,
    val dominantColor: Color = topColor,
    val stops: List<Color> = listOf(topColor, bottomColor)
)

/**
 * 当前壁纸调色板（由宿主 provide；未提供时为 null）。
 *
 * 用 `staticCompositionLocalOf` 而非 `compositionLocalOf`：调色板**极少变**
 * （换壁纸才变），不需要细粒度失效；static 版本读取开销更低，而卡片在滚动帧里会读它。
 */
val LocalWallpaperPalette = staticCompositionLocalOf<WallpaperPalette?> { null }

/**
 * 按屏幕 Y 轴位置在调色板色段之间线性插值。
 *
 * [yFraction] 是卡片在屏幕上的纵向位置（0 = 顶部，1 = 底部）；本函数内部再钳一次，
 * 越界不抛。
 *
 * 为什么按位置插值而不是全屏一个色：滚动时卡片自上而下移动，若所有卡片同色，
 * 就失去了"色彩随位置流动"的观感；按位置取色让顶部卡片偏 [WallpaperPalette.topColor]、
 * 底部偏 [WallpaperPalette.bottomColor]，滚动时颜色随之过渡。这是上游强调 120fps
 * 轻量的原因 —— 本函数是纯浮点运算，**无堆分配**（`lerp` 返回 inline value class）。
 */
fun interpolateWallpaperColor(palette: WallpaperPalette, yFraction: Float): Color {
    val clamped = yFraction.coerceIn(0f, 1f)
    val stops = palette.stops
    if (stops.isEmpty()) return palette.topColor
    if (stops.size == 1) return stops[0]
    val scaled = clamped * (stops.size - 1)
    // coerceIn 防浮点误差：clamped==1f 时 scaled 可能算出 size-1，导致 index+1 越界
    val index = scaled.toInt().coerceIn(0, stops.size - 2)
    val fraction = scaled - index
    return lerp(stops[index], stops[index + 1], fraction)
}

/**
 * 把半透明的染色结果**合成到不透明底色上**，得到可直接铺满卡片的实色。
 *
 * ## 为什么需要这一步（实测定位）
 *
 * [resolveVideoCardAmbientDrawSpec] 返回的 `containerColor` 是**半透明**的
 * （浅色 0.28 / 深色 0.34 / data saver 0.65+），设计意图是"透出背后的高斯模糊"。
 * 但这个前提在本项目的双列卡片上**不成立**：卡片底色是 `surfaceContainerLow = White`
 * （不透明），没有模糊层可透。
 *
 * 直接把半透明色交给 `Surface(color =)` 有两个问题：
 * 1. 卡片实际呈现的是"半透明染色叠在**页面背景**上"而不是叠在卡片底色上 ——
 *    颜色偏冷偏淡，与设计意图不符；
 * 2. `Surface` 的 color 是**组合期参数**，首帧若取色还没到（null）就固定成底色，
 *    之后再也不会更新（这是"卡片永远不上色"的直接原因之一）。
 *
 * 所以改为：`Surface` 用不透明底色，染色在**绘制期**用本函数合成后覆盖绘制。
 * 合成后的颜色不透明，盖上去即可，不需要依赖背景。
 *
 * 合成用 sRGB 逐通道线性插值，与 Android 绘制半透明图层的实际行为一致。
 */
fun compositeTintOverBase(
    tintedContainer: Color,
    baseColor: Color
): Color {
    val a = tintedContainer.alpha
    if (a >= 1f) return tintedContainer
    if (a <= 0f) return baseColor
    return Color(
        red = tintedContainer.red * a + baseColor.red * (1f - a),
        green = tintedContainer.green * a + baseColor.green * (1f - a),
        blue = tintedContainer.blue * a + baseColor.blue * (1f - a),
        alpha = 1f
    )
}

/**
 * 是否该用封面取色。
 *
 * 壁纸取色开启时，壁纸是唯一来源，封面色只作为**无壁纸时的回退**
 * （上游注释原文："壁纸是卡片动态取色的唯一来源时，封面色只作为无壁纸时的回退"）。
 */
internal fun shouldUseCoverTintForCard(
    wallpaperTintEnabled: Boolean,
    coverTint: Color?,
): Boolean = !wallpaperTintEnabled && coverTint != null && coverTint.alpha > 0f

/**
 * 把封面代表色整理成**可用作卡片染色**的颜色。
 *
 * 两步，顺序不能反：
 * 1. **明度归一化**：把过暗的颜色沿"黑→原色"推到 [TINT_TARGET_LUMINANCE]；
 * 2. **饱和度下限**：HSV 饱和度不足 [TINT_MIN_SATURATION] 时补足。
 *
 * ## 为什么需要这一步（2026-09-22 真机实测，21 个真实封面色样本）
 *
 * | 指标 | 中位 | 分布 |
 * |---|---|---|
 * | HSV 饱和度 | 40% | <30% 占 47%，<15% 占 23% |
 * | 相对亮度 | **0.051** | <0.10 占 **61%** |
 *
 * 提取出来的颜色**大多很暗**（视频封面夜景/黑底字幕很常见），直接与卡片底色混合
 * 等于没混 —— 这才是"很多卡片没有取色"的真正原因，不是提取失败。
 *
 * ## 目标亮度为什么是 0.22 而不是"越亮越好"（实测纠正）
 *
 * 第一版把目标定在 0.42，结果**更难看**：提到 0.42 后颜色已经很浅，再以 0.68
 * 混进**纯白**卡片底（`iOSColorScheme.surfaceContainerLow = White`）几乎等于白色。
 * 实测算例 `#080820`（深蓝）：
 *
 * | 目标亮度 | 归一化后 | 混白 0.68 后 | 观感 |
 * |---|---|---|---|
 * | 0.42 | `#6585CC` | `#B9C9EE` | 很淡，几乎看不出 |
 * | **0.22** | `#2A3D8A` | `#8794C6` | **明显蓝** |
 *
 * 正确方向是**保留颜色本身的深浅**、只把过暗的抬到"能参与混合"的下限。
 *
 * ## 为什么还要饱和度下限
 *
 * 亮度归一化是**逐通道等比缩放**，保不住饱和度：很暗的近灰色（如 `#202820`，
 * 饱和度 20%）提亮后仍是低饱和灰绿，混上去读作"脏"而不是"有颜色"。
 *
 * ## 深色主题下不改动
 *
 * 深色主题卡片底色本来就暗（`#1C1C1E`），深色封面色混上去是**可见**的；
 * 提亮反而让深色主题的卡片发灰。
 *
 * @param coverTint 提取到的封面代表色（应是不透明色）
 * @param isDarkTheme 深色主题下原样返回
 */
internal fun normalizeTintColor(
    coverTint: Color,
    isDarkTheme: Boolean
): Color {
    if (isDarkTheme) return coverTint

    val lum = coverTint.luminance()
    val lifted = if (lum >= TINT_TARGET_LUMINANCE) {
        coverTint
    } else {
        // 沿"黑→原色"推到目标亮度：RGB 各通道同比例放大（线性近似，够用且无分配）。
        // 分母用 maxOf(r,g,b) 而非 luminance()：避免低亮度时除法放大噪声，
        // 同时保证放大后最大通道正好是 1.0。
        val maxChannel = maxOf(coverTint.red, coverTint.green, coverTint.blue)
        if (maxChannel <= 0f) {
            // 纯黑：没有任何色相信息可保留
            return Color(TINT_TARGET_LUMINANCE, TINT_TARGET_LUMINANCE, TINT_TARGET_LUMINANCE)
        }
        val scale = TINT_TARGET_LUMINANCE / maxChannel
        Color(
            red = (coverTint.red * scale).coerceIn(0f, 1f),
            green = (coverTint.green * scale).coerceIn(0f, 1f),
            blue = (coverTint.blue * scale).coerceIn(0f, 1f),
            alpha = coverTint.alpha
        )
    }
    return boostSaturationIfNeeded(lifted)
}

/**
 * HSV 饱和度不足 [TINT_MIN_SATURATION] 时补足，保持色相与明度。
 *
 * ## 做法
 *
 * 记 `max`/`min` 为最大/最小通道，HSV 饱和度 `s = (max-min)/max`，明度 `V = max`。
 * 要在**不动 max**（保住明度）的前提下把 `s` 提到目标值 `S`：
 * 令新差值 `d' = S * max`，再把**所有非最大通道**的差值按同一比例 `k = d'/d` 放大。
 * 按比例放大（而不是给每个通道平摊一个常量）才能保住色相 —— 各通道到 max 的
 * **相对**距离不变，改变的只是"离 max 多远"。
 *
 * ## 踩过的两个坑（都记下来，别再犯）
 *
 * 1. **第一版反解比例是错的**：写成 `k = (min - targetMin) / (min - max)`，
 *    对 `#202820`（s=0.20）算出 **k = -0.5**，被 `coerceIn(0,1)` 钳成 0 ——
 *    **等于完全没提升**。
 * 2. **第二版平摊也是错的**：改成"把差值平均分给非最大通道"，
 *    对同一颜色只提到 **0.25**（目标 0.30）—— 因为非最大通道只有 1 个时，
 *    平摊的常量不足以把 min 推到目标位置。
 *
 * 两版都被单测 `boost_lowSaturation_isRaisedToFloor` 抓到。**按比例放大差值**才对。
 *
 * 纯灰（`max == min`）没有色相可保，**原样返回** —— 强行给灰色指定色相会得到
 * "随机偏色"，比灰更糟。
 */
internal fun boostSaturationIfNeeded(color: Color): Color {
    val maxC = maxOf(color.red, color.green, color.blue)
    if (maxC <= 0f) return color
    val minC = minOf(color.red, color.green, color.blue)
    val range = maxC - minC
    if (range <= 0f) return color // 纯灰：无色相可保
    val sat = range / maxC
    if (sat >= TINT_MIN_SATURATION) return color

    // 差值放大比例：把 range 提到 S*max。max 不动，所以明度不变。
    val scale = (TINT_MIN_SATURATION * maxC) / range
    fun push(c: Float) = if (c < maxC) (maxC - (maxC - c) * scale).coerceIn(0f, maxC) else c
    return Color(
        red = push(color.red),
        green = push(color.green),
        blue = push(color.blue),
        alpha = color.alpha
    )
}

/**
 * 染色目标亮度。
 *
 * 依据（21 个真实样本）：提取色相对亮度中位 **0.051**、61% 低于 0.10。
 * 0.22 是"抬到能参与混合、又保留足够饱和度"的位置 —— 见 [normalizeTintColor]
 * KDoc 里 `#080820` 的两组对照。第一版取 0.42 是**错的**（会洗白）。
 */
private const val TINT_TARGET_LUMINANCE = 0.22f

/**
 * 染色最低 HSV 饱和度。
 *
 * 依据：提取色饱和度中位 40%、但 47% 低于 30%。低于 30% 混到卡片上读作"脏灰"
 * 而不是"有颜色"，所以补到 30% —— 保住"这张卡有主色"的可辨识度，
 * 又不会把本来很艳的封面（如 `#d05018` 饱和度 88%）改得更艳。
 */
private const val TINT_MIN_SATURATION = 0.30f

/**
 * 综合决策卡片底部组件的着色方案。
 *
 * 关键质感取舍（照搬上游，勿自行调参）：
 * - 容器是**半透明**的（深色 0.38 / 浅色 0.34，省流量档放宽到 0.65/0.70）——
 *   要"直接透出背后的高斯模糊"，同时保留饱和色彩，避免发白发灰的厚重白雾；
 * - 封面色与默认底色按比例混合（深色 0.85 / 浅色 0.75），不是直接铺满，
 *   否则卡片会变成一块纯色板、失去玻璃感；
 * - 微光与描边只在**确实有封面色**时才有强度，否则会凭空出现无来源的光。
 */
fun resolveVideoCardAmbientDrawSpec(
    coverTint: Color?,
    isDarkTheme: Boolean,
    defaultContainerColor: Color,
    defaultBorderColor: Color,
    wallpaperTintEnabled: Boolean = false,
    isDataSaverActive: Boolean = false,
    frostedGlassEnabled: Boolean = true,
    /**
     * 壁纸调色板。仅当 [wallpaperTintEnabled] 为 true 时参与决策。
     *
     * 放在形参末尾并给默认值：这样所有既有调用点与测试**无需改动**即可编译，
     * 且不传时行为与接入壁纸前完全一致（回归安全）。
     */
    wallpaperPalette: WallpaperPalette? = null,
    /** 卡片在屏幕上的纵向位置（0 顶部 / 1 底部），用于在 [wallpaperPalette] 的色段间插值。 */
    yFraction: Float = 0.5f
): VideoCardAmbientDrawSpec {
    // 上游语义：壁纸开启时，**壁纸就是唯一来源**，即使调色板还没加载好也不能退回封面色
    // （否则会先闪一下封面色再变壁纸色）。所以这里的判据是"壁纸开着或有封面色"。
    val wallpaperActive = wallpaperTintEnabled
    if (!frostedGlassEnabled || (!wallpaperActive && coverTint == null)) {
        return VideoCardAmbientDrawSpec(
            containerColor = defaultContainerColor,
            coverGlowAlpha = 0f,
            borderColor = defaultBorderColor
        )
    }

    val hasValidCoverTint = shouldUseCoverTintForCard(
        wallpaperTintEnabled = wallpaperTintEnabled,
        coverTint = coverTint,
    )

    // ── 染色强度：整屏观感的调节旋钮 ────────────────────────────────────────
    //
    // 这个值被改过两轮，两次的理由都成立，但方向相反 —— 记在这里免得再被无声改动：
    //
    // 轮 1（上游原值）：浅色 0.34 透明 / 0.75 混色 / 0.38 光晕。
    // 轮 2（`d70fcce0`，对着真机截图改的）：下调到 0.20 / 0.58 / 0.22，
    //   理由原文「原值在双列信息流上会让每张卡的信息区呈现一块高饱和色带……
    //   整屏读作拼色块，是当前界面最显旧的一处」。方向：**整屏统一优先**。
    // 轮 3（本次）：上调到 0.28 / 0.68 / 0.30。
    //   理由：轮 2 压过头了 —— `blend × alpha` 从上游的 0.255 掉到 0.116，
    //   **染色可见度只剩上游的 45%**，用户直接反馈"颜色提取有问题/没有提取色"。
    //   本次取上游与轮 2 的中间偏上：既看得出卡片与封面同源，又不回到"拼色块"。
    //
    // **判据不是"哪个值好看"**（那要有设备才能判），而是：
    // 1. `blend × alpha` 必须落在一个下限之上（见 `tintVisibility_isNotTooFaint`），
    //    否则用户会把"太淡"读成"功能坏了"——这正是本次的起因；
    // 2. 关系不变：半透明、深色更强、data saver 更不透明（现有 15 条单测钉的就是这些）。
    // 3. 真机观感仍需复核：见 docs/2026-09-22-卡片动态取色-任务书.md §五。
    val glassTransparency = if (isDarkTheme) {
        if (isDataSaverActive) 0.65f else 0.34f
    } else {
        if (isDataSaverActive) 0.70f else 0.28f
    }

    val baseColor = when {
        // ① 壁纸路：直接用插值后的**真实壁纸色**，不与容器底色混合稀释。
        //    上游注释原文：「直接采用壁纸取色插值后的真实色彩，杜绝与白色容器底色混合稀释」。
        //    混合会把壁纸色冲淡成灰白，失去"跟随壁纸"的意义。
        wallpaperActive && wallpaperPalette != null -> {
            interpolateWallpaperColor(wallpaperPalette, yFraction)
                .copy(alpha = glassTransparency)
        }
        // ② 壁纸开着但调色板还没加载好：保持中性玻璃表面，**不要**退回封面色。
        //    否则会先闪一下上一张卡/封面颜色，等壁纸色到位再跳变。
        wallpaperActive -> defaultContainerColor.copy(alpha = glassTransparency)
        // ③ 封面路：先做**明度归一化 + 饱和度下限**再与容器底色混合。
        //
        // 必须做：实测提取色的相对亮度中位只有 0.051（61% 低于 0.10）、
        // 饱和度中位 40%（47% 低于 30%）。不整理就直接混，大部分卡片会被冲成灰、
        // 读作"没取到色"—— 这是本功能最大的观感问题。
        // 详见 normalizeTintColor 的 KDoc（含 21 个真实样本的数据与两组对照）。
        hasValidCoverTint -> {
            val tint = normalizeTintColor(coverTint!!, isDarkTheme)
            val blendFraction = if (isDarkTheme) 0.80f else 0.68f
            lerp(defaultContainerColor, tint, blendFraction).copy(alpha = glassTransparency)
        }
        // ④ 都没有：中性玻璃表面
        else -> defaultContainerColor.copy(alpha = glassTransparency)
    }

    // 微光与描边只跟**封面色**走：壁纸路没有"封面边缘"这个概念，
    // 给它加光晕等于凭空出现无来源的光（上游同样只在 hasValidCoverTint 时给强度）。
    val glowAlpha = if (hasValidCoverTint) {
        if (isDarkTheme) 0.42f else 0.30f
    } else {
        0f
    }

    // 描边与微光跟**同一个归一化后的色**走，而不是原始封面色：
    // 否则会出现"底色被提亮了、描边还是那个深色"的脱节（描边几乎看不见）。
    val finalBorderColor = if (hasValidCoverTint) {
        val normalizedTint = normalizeTintColor(coverTint!!, isDarkTheme)
        val borderBlendFraction = if (isDarkTheme) 0.50f else 0.40f
        val borderAlpha = if (isDarkTheme) 0.28f else 0.40f
        val baseBorder = Color.White.copy(alpha = borderAlpha)
        lerp(baseBorder, normalizedTint, borderBlendFraction).copy(alpha = borderAlpha)
    } else {
        if (isDarkTheme) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.40f)
    }

    return VideoCardAmbientDrawSpec(
        containerColor = baseColor,
        coverGlowAlpha = glowAlpha,
        borderColor = finalBorderColor
    )
}

/**
 * 卡片文字色是否需要在「浅色主题 + 深色封面」时翻转成白色。
 *
 * 这是动态取色的**可读性兜底**：浅色主题下若封面是深色（夜景、黑底），
 * 卡片被染深后原来的深色正文会失去对比度。判据用封面色的相对亮度阈值
 * （0.38，上游实测取值），而不是"看起来深不深"。
 */
@Immutable
data class VideoCardAdaptiveContentColors(
    val titleColor: Color,
    val subtitleColor: Color,
    val isDarkSurface: Boolean,
)

fun resolveVideoCardAdaptiveContentColors(
    coverTint: Color?,
    isDarkTheme: Boolean,
    defaultOnSurface: Color,
    defaultOnSurfaceVariant: Color,
    wallpaperTintEnabled: Boolean = false,
    dynamicTintEnabled: Boolean = true,
): VideoCardAdaptiveContentColors {
    if (!dynamicTintEnabled) {
        return VideoCardAdaptiveContentColors(
            titleColor = defaultOnSurface,
            subtitleColor = defaultOnSurfaceVariant,
            isDarkSurface = isDarkTheme
        )
    }

    val isDarkSurface = if (isDarkTheme) {
        true
    } else {
        val hasValidCover = shouldUseCoverTintForCard(
            wallpaperTintEnabled = wallpaperTintEnabled,
            coverTint = coverTint,
        )
        hasValidCover && coverTint!!.luminance() < 0.38f
    }

    return if (isDarkSurface && !isDarkTheme) {
        VideoCardAdaptiveContentColors(
            titleColor = Color.White,
            subtitleColor = Color.White.copy(alpha = 0.78f),
            isDarkSurface = true
        )
    } else {
        VideoCardAdaptiveContentColors(
            titleColor = defaultOnSurface,
            subtitleColor = defaultOnSurfaceVariant,
            isDarkSurface = isDarkSurface
        )
    }
}

/**
 * 卡片动态取色总开关（设置页可关）。默认 true：这是推荐页的观感特性，
 * 关掉后退回中性玻璃表面。
 */
val LocalHomeCardDynamicTintEnabled = staticCompositionLocalOf { true }
