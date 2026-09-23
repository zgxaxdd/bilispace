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
        // ③ 封面路：与容器底色按比例混合（不直接铺满，否则卡片变成一块纯色板、失去玻璃感）
        hasValidCoverTint -> {
            val blendFraction = if (isDarkTheme) 0.80f else 0.68f
            lerp(defaultContainerColor, coverTint!!, blendFraction).copy(alpha = glassTransparency)
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

    val finalBorderColor = if (hasValidCoverTint) {
        val borderBlendFraction = if (isDarkTheme) 0.50f else 0.40f
        val borderAlpha = if (isDarkTheme) 0.28f else 0.40f
        val baseBorder = Color.White.copy(alpha = borderAlpha)
        lerp(baseBorder, coverTint!!, borderBlendFraction).copy(alpha = borderAlpha)
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
