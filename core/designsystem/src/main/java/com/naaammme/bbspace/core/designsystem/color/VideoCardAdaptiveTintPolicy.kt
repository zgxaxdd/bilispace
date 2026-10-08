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
 * 上游有两路取色来源：
 * - **壁纸调色板**（跟随系统壁纸，按滚动 Y 轴插值）；
 * - **封面代表色**（[VideoCardCoverColorStore] 提取）。
 *
 * BBSpace 目前只移植**封面代表色**这一路（壁纸那路依赖 WallpaperManager 全链路，
 * 单独一期做）。因此策略函数保留了 `wallpaperTintEnabled` 形参但调用方传 false，
 * 这样将来接壁纸时不需要改签名与测试。
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
    frostedGlassEnabled: Boolean = true
): VideoCardAmbientDrawSpec {
    if (!frostedGlassEnabled || (!wallpaperTintEnabled && coverTint == null)) {
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

    // ── 染色强度：唯一判据是「简介区颜色与预览图内容对应得上」──────────────────
    //
    // 这里被改过三轮，前两轮都不对，记下来免得再犯。
    //
    // ## 根因：原公式**往白里兑了两次**
    //
    // 原实现是：
    //   ① `lerp(defaultContainerColor, coverTint, blendFraction)` —— 卡片底色是白，
    //      所以这一步 = 往白里兑 `(1 - blendFraction)`
    //   ② `.copy(alpha = glassTransparency)` —— 叠到屏幕上时**又**往白里兑
    //      `(1 - glassTransparency)`
    //
    // 两轮加起来兑掉 40%+ 的白，颜色就被冲没了。
    // 真机实测（设备上的真实封面，跑同一套 Palette 算法验证过取色是准的）：
    //   | 预览图主色          | 算法取到（准确）    | 旧公式渲染出      |
    //   | `#4b628b` 蓝 s46%   | `#4b628b`           | `#ced3da` **s6%** |
    //   | `#e8d898` 黄 s30%   | `#e8d898`           | `#f9f5e5` **s8%** |
    // 用户看到的就是"简介区颜色与预览图对应不上"。
    //
    // ## 正确做法：**只兑一次**
    //
    // 染色是叠在**不透明卡片底色**上的，所以直接
    //   `lerp(baseColor, coverTint, TINT_BLEND)` + 实色
    // 就是"兑一次白"的效果 —— 一步到位，不再叠第二次半透明。
    // （原来那两步等价于 `1-(1-0.85)*(1-0.70) = 0.955` 兑白，即颜色只剩 4.5%；
    //   现在 `0.70` 兑白，颜色保留 70%，是原来的 **15 倍**。）
    //
    // 实测对照（同一批真实封面）：
    //   | 预览图主色         | 旧（两次兑白）    | **新（一次兑白）**  |
    //   | `#4b628b` 蓝 46%   | #ced3da  6%      | **#98a6c1 25%**    |
    //   | `#e8d898` 黄 30%   | #f9f5e5  8%      | **#f3ecca 22%**    |
    //   | `#591b0a` 红 89%   | #d2cabb 11%      | **#a35f53 49%**    |
    //
    // ## 为什么是 0.70
    //
    // 卡片正文是**黑字**，染色不能把底色压太暗（WCAG AA 正文要 4.5:1）。
    // 实测最暗的红色封面 `#59140d`：0.70 → 对比度 **5.1** ✅；0.80 → 3.5 ❌。
    // 所以 0.70 是"颜色对应得上"与"文字仍可读"的交点。
    //
    // B-4/C-3（2026-10-04）：这里原先还有一个
    //   `val glassTransparency = if (isDarkTheme) { if (isDataSaverActive) 0.82f else 0.70f }
    //                            else { if (isDataSaverActive) 0.82f else 0.70f }`
    // —— 两个外层分支逐字相同，且**全仓无任何使用点**（染色改 `lerp` 一次兑白后它已被
    // 取代，只剩注释与测试在提它）。连同只服务于它的 `isDataSaverActive` 入参一起删除：
    // 留一个没人读的参数比留注释更容易让后来者以为"省流量档会影响染色"。

    val baseColor = if (hasValidCoverTint) {
        // 一步兑白：直接与不透明底色按比例混合，得到**实色**。
        // 不再 `.copy(alpha = ...)` —— 那会叠第二次白，是本次修复的核心。
        lerp(defaultContainerColor, coverTint!!, 0.70f)
    } else {
        // 无壁纸且无封面：保持中性玻璃表面，不要沿用上一张封面的颜色
        defaultContainerColor
    }

    val glowAlpha = if (hasValidCoverTint) {
        if (isDarkTheme) 0.48f else 0.38f
    } else {
        0f
    }

    val finalBorderColor = if (hasValidCoverTint) {
        val borderBlendFraction = if (isDarkTheme) 0.50f else 0.40f
        val borderAlpha = if (isDarkTheme) 0.30f else 0.45f
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
