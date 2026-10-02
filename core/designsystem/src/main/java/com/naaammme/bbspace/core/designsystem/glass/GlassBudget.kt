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
    // 底栏提档（终版方案 3.4，有意偏离 BiliPai 常量）。
    //
    // **AP-27 ① 口径更正（2026-10-02）**：原依据"bbspace 白名单仅 5 处"把
    // `glassEffect(` 的源码调用点当成了全部玻璃面 —— 该数字已漂移失效（调用点确实
    // 2 处，但运行期玻璃面远不止）。按 `GlassMountRegistryTest` 的挂载点登记表重算，
    // 运行期玻璃面 = 顶栏 1 + Dock 4 格 + 播放控件 1（OVERLAY）+ 每可见卡角标 2×N
    // （iOS+LIQUID 档才存在，出厂默认 MATERIAL 下为 0）。BiliPai 压最低档因其铺了
    // 71 处；本仓的数量级仍远低于上游，故 BOTTOM_BAR=2 维持。**降回上游的 1 属观感
    // 变更**：本轮只更正依据、不改值，降级决策列入待真机（AP-T6，gfxinfo 对照）。
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

// FL-04（2026-10-02）：`resolveGlassInputScale` 已删除 —— 本仓唯一调用点
// （GlassEffect 的 inputScaleFactor）是死代码：haze 2.0 移除 HazeInputScale 后
// 返回值从未被读取。**连带影响**：上游 BiliPai 仍在消费同名函数
// （UnifiedBlur.kt → HazeInputScale.Fixed）实现"非实时档降采样"；本仓的 haze 2.0
// API 没有等价的输入降采样钩子，该能力暂由 HazePerformanceMode.Performance 档
// 代替。待 haze 暴露等价参数后，再按 AP-27 ③ 评估接线（需真机对照观感）。
