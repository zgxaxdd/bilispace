package com.naaammme.bbspace.core.designsystem.glass.liquid

import androidx.compose.runtime.Immutable

/**
 * 液态玻璃的**参数层**（纯逻辑，可 JVM 单测）。
 *
 * ## 这层解决什么问题
 * 本仓的液态玻璃参数此前**硬编码在绘制调用点**（`LiquidBackdrop.kt` 里 `4f * s` /
 * `24f * s` / `vibrancy(1.5f)` / `0.40f × 明暗系数`）。三个后果：
 * 1. 想按"档位进度"（clear → balanced → frosted）调观感，得改绘制代码；
 * 2. 想让低端机/关动效的用户降级，没有单一开关；
 * 3. 参数没有任何断言保护 —— 改一个数字不会被任何测试拦住。
 *
 * 本层把"strength × progress × 明暗 × reduceMotion × 设备上限"解析成**一组确定的
 * 光学参数**，绘制调用点只消费结果。它是上游 `LiquidGlassTuning.kt` 的同构移植
 * （纯函数 + 数据类），但**取值锚定本仓现状**，不是照搬上游的 15 项配置。
 *
 * ## 默认值必须逐值等于现状（防"优化变观感"）
 * 默认（`strength = 1`、`progress = Balanced`、`glassStrengthCap = 1`、无 reduceMotion）：
 * - `blurRadiusDp = 4`（现状 `4f * s`）
 * - `refractionHeight/AmountDp = 24`（现状 `24f * s`）
 * - `saturation = 1.5`（现状 `vibrancy(1.5f)`）
 * - `surfaceAlpha = 0.40 × (深 0.75 / 浅 0.95)` = 0.30 / 0.38（现状同式）
 * - `chromaticAberration = 0`、`scrimAlpha = 0`（现状都没有）
 * 这条由 `LiquidGlassTuningTest.defaultsMatchCurrentRendering` 逐值断言。
 *
 * ## 三个"不改观感"的刻意决定
 * 1. **saturation / surfaceAlpha 不乘 strength**：现状就是这样（strength 只缩放
 *    模糊与折射，见 `GlassConfig` KDoc）。乘上去会让"强度 2"比现在更暗更艳 —— 观感变更。
 * 2. **chromaticAberration 恒为 0**：现状是 0；上游对 Dock **外壳**也是 0（色散只给
 *    指示器）。字段保留是为了下一轮 Dock 指示器能直接消费，不是本轮要开。
 * 3. **scrim 只在 progress > Balanced 时出现**：默认 0；极端磨砂端才叠一层可读性
 *    scrim（上游 `contentReadabilityScrimAlpha` 的作用），默认路径不绘制。
 */
@Immutable
data class LiquidGlassTuning(
    /** 背景模糊半径（dp）。 */
    val blurRadiusDp: Float,
    /** 容器染色 alpha（0..1，已含明暗系数）。 */
    val surfaceAlpha: Float,
    /** 饱和度补偿（vibrancy）。 */
    val saturation: Float,
    /** 边缘折射高度（dp）。 */
    val refractionHeightDp: Float,
    /** 边缘折射强度（dp）。 */
    val refractionAmountDp: Float,
    /** 色散强度（0 = 关闭；本轮恒 0，见类文档）。 */
    val chromaticAberration: Float,
    /** 可读性 scrim alpha（叠在玻璃底之上；0 = 不绘制）。 */
    val scrimAlpha: Float
)

/**
 * 参数解析策略（**纯逻辑**：无 Compose、无 Android、无随机）。
 *
 * 输入全部是可解释的量：用户强度、档位进度、明暗、无障碍开关、设备能力上限。
 * 输出是 [LiquidGlassTuning]；调用方（`liquidGlassSurface`）只负责把 dp 转 px、
 * 把 alpha 喂给 tint。
 */
object LiquidGlassTuningPolicy {

    /** 用户设置里的默认强度（`GlassConfig.strength` 默认 1）。 */
    const val DefaultStrength = 1f

    /**
     * "BALANCED 锚点"对应的档位进度 —— 即本仓**现状**所在的位置。
     *
     * 上游把进度分成 CLEAR(<0.34) / BALANCED(<0.68) / FROSTED(≥0.68)，本仓现状的
     * 参数（模糊 4 / 折射 24 / 饱和 1.5 / alpha 0.40）正是 BALANCED 锚点。取 0.5
     * 作为默认，保证曲线在默认点**刚好穿过现状值**。
     */
    const val BalancedProgress = 0.5f

    // ── 强度范围（与 GlassConfig 的 0.5–2.0 口径一致）───────────────────────
    const val MinStrength = 0.5f
    const val MaxStrength = 2f

    // ── 现状值（BALANCED = 默认）────────────────────────────────────────────
    const val DefaultBlurRadiusDp = 4f
    const val DefaultRefractionDp = 24f
    const val DefaultSaturation = 1.5f
    const val DefaultSurfaceAlpha = 0.40f
    const val DarkSurfaceAlphaScale = 0.75f
    const val LightSurfaceAlphaScale = 0.95f

    // ── FROSTED 端（progress=1）────────────────────────────────────────────
    /** 磨砂端模糊更重（上游 4 → 10dp）。 */
    const val FrostedBlurRadiusDp = 10f
    /** 磨砂端色散饱和降一档（上游 1.5 → 1.24）。 */
    const val FrostedSaturation = 1.24f
    /** 磨砂端折射收小（上游 24 → 8dp）。 */
    const val FrostedRefractionDp = 8f
    /** 磨砂端染色更实（上游 0.40 → 0.54）。 */
    const val FrostedSurfaceAlpha = 0.54f
    /** 磨砂端可读性 scrim（比上游 0.34×保护系数轻：本仓只做兜底，不做主可读性层）。 */
    const val FrostedScrimAlpha = 0.10f

    /** 初始（clear）端与 balanced 同值：本仓没有"更清"的产品面，避免凭空造一档。 */
    const val ClearBlurRadiusDp = DefaultBlurRadiusDp
    const val ClearSaturation = DefaultSaturation
    const val ClearRefractionDp = DefaultRefractionDp
    const val ClearSurfaceAlpha = DefaultSurfaceAlpha
    const val ClearScrimAlpha = 0f

    /**
     * 解析一次玻璃参数。
     *
     * @param strength 用户强度（见 `GlassConfig.strength`，会被钳到 [MinStrength]..[MaxStrength]）
     * @param progress 档位进度 0..1；默认 [BalancedProgress] = 本仓现状
     * @param isDarkTheme 当前是否深色主题（只影响染色 alpha 系数）
     * @param reduceMotion 用户/系统"关闭动效"。折射与色散属于**形变类**效果：
     *   关动效时归零（观感退化为静态磨砂），模糊/染色/饱和保留
     * @param glassStrengthCap 设备能力上限（`MotionTierPolicy.glassStrengthCap`）；
     *   1 = 不限制，0 = 不挂玻璃（低端机）。**默认 1**，调用点本轮显式传 1，
     *   避免在没有真机复评前改变低端机的现行观感
     */
    fun resolve(
        strength: Float = DefaultStrength,
        progress: Float = BalancedProgress,
        isDarkTheme: Boolean = false,
        reduceMotion: Boolean = false,
        glassStrengthCap: Float = 1f
    ): LiquidGlassTuning {
        val safeStrength = strength.safeIn(MinStrength, MaxStrength, DefaultStrength)
        val safeProgress = progress.safeIn(0f, 1f, BalancedProgress)
        val safeCap = glassStrengthCap.safeIn(0f, 1f, 1f)
        // 强度与上限**相乘**：cap=0 ⇒ 玻璃整体不生效（模糊/折射/染色归零）
        val strengthFactor = safeStrength * safeCap
        // 形变类（折射/色散）在关动效时归零；模糊/染色/饱和是静态的，保留
        val deformationFactor = if (reduceMotion) 0f else strengthFactor
        val alphaFactor = if (isDarkTheme) DarkSurfaceAlphaScale else LightSurfaceAlphaScale
        return LiquidGlassTuning(
            blurRadiusDp = midpointLerp(ClearBlurRadiusDp, DefaultBlurRadiusDp, FrostedBlurRadiusDp, safeProgress) *
                strengthFactor,
            // 现状：alpha 不随 strength 缩放（见类文档"不改观感"第 1 条），只受 cap 控制
            surfaceAlpha = (midpointLerp(ClearSurfaceAlpha, DefaultSurfaceAlpha, FrostedSurfaceAlpha, safeProgress) *
                alphaFactor * safeCap).coerceIn(0f, 1f),
            saturation = midpointLerp(ClearSaturation, DefaultSaturation, FrostedSaturation, safeProgress),
            refractionHeightDp = midpointLerp(ClearRefractionDp, DefaultRefractionDp, FrostedRefractionDp, safeProgress) *
                deformationFactor,
            refractionAmountDp = midpointLerp(ClearRefractionDp, DefaultRefractionDp, FrostedRefractionDp, safeProgress) *
                deformationFactor,
            // 本轮恒 0（现状与上游 Dock 外壳都是 0）：字段留给 Dock 指示器
            chromaticAberration = 0f,
            scrimAlpha = (midpointLerp(ClearScrimAlpha, ClearScrimAlpha, FrostedScrimAlpha, safeProgress) * safeCap)
                .coerceIn(0f, 1f)
        )
    }

    /**
     * 三锚点插值（语义同上游 `midpointLerp`）：progress ≤ [BalancedProgress] 在
     * clear→balanced 之间，之后在 balanced→frosted 之间。
     *
     * 为什么不用一条二次曲线：两端锚点都是**产品口径**（现状值 / 上游磨砂值），
     * 分段线性保证"默认点精确等于现状值"，也让单调性一目了然、可被单测逐段验证。
     */
    private fun midpointLerp(clear: Float, balanced: Float, frosted: Float, progress: Float): Float =
        if (progress <= BalancedProgress) {
            clear + (balanced - clear) * (progress / BalancedProgress)
        } else {
            balanced + (frosted - balanced) * ((progress - BalancedProgress) / (1f - BalancedProgress))
        }
}

/** 浮点 clamp；NaN 一律回落 [fallback]（参数层不许把 NaN 传进绘制）。 */
private fun Float.safeIn(min: Float, max: Float, fallback: Float): Float {
    if (!isFinite()) return fallback
    return coerceIn(min, max)
}
