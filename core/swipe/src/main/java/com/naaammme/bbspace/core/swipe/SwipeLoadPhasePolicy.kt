package com.naaammme.bbspace.core.swipe

/**
 * 会话加载层的**三态判据**（任务书 SW-34：骨架/粒子/错误态统一）。
 *
 * 现状是 veil 同时收 `visible` 与 `failed` 两个布尔——两处独立派生就有组合不一致的余地
 * （例如 visible=false 而 failed=true：错误态被"看不见"）。这里把三态收成一个纯判据，
 * 调用点只从它派生（[resolve]），veil 内部的时序与渲染顺序保持不变。
 *
 * 优先级（与既有行为逐条一致）：
 * 1. **失败优先**：失败态不吃加载/骨架——错误 + 重试要立刻可见（不透明优先级）；
 * 2. 其次"在等首帧"：有封面 → 封面铺底 + 粒子；无封面 → 纯深色底 + 粒子；
 * 3. 其余：Hidden（不组合加载层，回到正常画面）。
 */
enum class SwipeLoadPhase {
    Hidden,
    LoadingWithCover,
    LoadingWithoutCover,
    Error,
}

object SwipeLoadPhasePolicy {

    fun resolve(
        waitingFirstFrame: Boolean,
        failed: Boolean,
        hasCover: Boolean,
    ): SwipeLoadPhase = when {
        failed -> SwipeLoadPhase.Error
        waitingFirstFrame && hasCover -> SwipeLoadPhase.LoadingWithCover
        waitingFirstFrame -> SwipeLoadPhase.LoadingWithoutCover
        else -> SwipeLoadPhase.Hidden
    }

    /** 加载层是否要组合（错误态也要——错误就长在加载层里）。 */
    fun isVeilVisible(phase: SwipeLoadPhase): Boolean = phase != SwipeLoadPhase.Hidden

    /** 是否处于错误态（veil 内切成"错误 + 重试"）。 */
    fun isError(phase: SwipeLoadPhase): Boolean = phase == SwipeLoadPhase.Error

    /** 粒子相对宽度：有封面时小一档（封面是主角、粒子只是"在加载"的提示）。 */
    fun particleWidthFraction(phase: SwipeLoadPhase, coverFraction: Float, plainFraction: Float): Float =
        if (phase == SwipeLoadPhase.LoadingWithCover) coverFraction else plainFraction
}
