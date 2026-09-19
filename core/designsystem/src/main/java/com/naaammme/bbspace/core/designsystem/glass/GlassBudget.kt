package com.naaammme.bbspace.core.designsystem.glass

/**
 * 玻璃预算策略（方案 0.5，常量照搬 BiliPai BlurBudgetPolicy 实测值，不自创）。
 *
 * [GlassSurfaceType] 命名与语义保持与 BiliPai 一致，便于将来对照上游修复。
 * 滚动 / 转场期**只停实时重录（allowRealtime=false），不降模糊等级**——杜绝
 * 强切 0 级导致的明暗跳跃（BiliPai Pulsing / Popping 教训，源码注释原文）。
 */
enum class GlassSurfaceType {
    HEADER,
    BOTTOM_BAR,
    DRAWER_OR_SHEET,
    OVERLAY,
    GENERIC
}

data class GlassBudget(
    val maxBlurLevel: Int,
    val backgroundAlphaMultiplier: Float,
    val allowRealtime: Boolean
)

/**
 * 动效档位。声明顺序固定为 `Reduced < Normal < Enhanced`（与 BiliPai 一致），
 * [assertMotionTierOrderIsConservativeFirst] 断言该顺序，移植时不得重排。
 */
enum class MotionTier {
    Reduced,
    Normal,
    Enhanced
}

fun motionTierOrderIsConservativeFirst(): Boolean =
    MotionTier.entries.indexOf(MotionTier.Reduced) <
        MotionTier.entries.indexOf(MotionTier.Normal) &&
        MotionTier.entries.indexOf(MotionTier.Normal) <
        MotionTier.entries.indexOf(MotionTier.Enhanced)

fun minMotionTier(a: MotionTier, b: MotionTier): MotionTier =
    if (a.ordinal <= b.ordinal) a else b

fun resolveGlassBudget(
    surfaceType: GlassSurfaceType,
    motionTier: MotionTier,
    isScrolling: Boolean,
    isTransitionRunning: Boolean,
    forceLowBudget: Boolean = false
): GlassBudget {
    // 底栏提档（终版方案 3.4，有意偏离 BiliPai 常量）：BiliPai 压最低档是因
    // 其铺了 71 处；bbspace 白名单仅 5 处。gfxinfo 不达标则回退 BOTTOM_BAR=1。
    var maxBlurLevel = when (surfaceType) {
        GlassSurfaceType.HEADER -> 2
        GlassSurfaceType.DRAWER_OR_SHEET -> 2
        GlassSurfaceType.BOTTOM_BAR -> 2
        GlassSurfaceType.OVERLAY -> 1
        GlassSurfaceType.GENERIC -> 1
    }
    var backgroundAlphaMultiplier = when (surfaceType) {
        GlassSurfaceType.HEADER -> 1.00f
        GlassSurfaceType.DRAWER_OR_SHEET -> 1.00f
        GlassSurfaceType.BOTTOM_BAR -> 0.88f
        GlassSurfaceType.OVERLAY -> 0.92f
        GlassSurfaceType.GENERIC -> 0.95f
    }
    var allowRealtime = true

    when (motionTier) {
        MotionTier.Reduced -> {
            maxBlurLevel = 0
            backgroundAlphaMultiplier *= 0.9f
            allowRealtime = false
        }
        MotionTier.Normal -> Unit
        MotionTier.Enhanced -> Unit
    }

    if (isScrolling || isTransitionRunning) {
        // 保持视觉稳定：滑动与转场期间保持材质等级与透明度恒定（Pulsing / Popping 教训）
        allowRealtime = false
    }

    if (forceLowBudget) {
        maxBlurLevel = 0
        backgroundAlphaMultiplier *= 0.9f
        allowRealtime = false
    }

    return GlassBudget(
        maxBlurLevel = maxBlurLevel.coerceIn(0, 2),
        backgroundAlphaMultiplier = backgroundAlphaMultiplier.coerceIn(0.70f, 1.10f),
        allowRealtime = allowRealtime
    )
}

fun resolveGlassInputScale(
    budget: GlassBudget,
    surfaceType: GlassSurfaceType
): Float {
    if (budget.allowRealtime) return 1f
    return when (surfaceType) {
        GlassSurfaceType.HEADER -> 0.88f
        GlassSurfaceType.DRAWER_OR_SHEET -> 0.84f
        GlassSurfaceType.BOTTOM_BAR -> 0.86f
        GlassSurfaceType.OVERLAY -> 0.84f
        GlassSurfaceType.GENERIC -> 0.84f
    }
}
