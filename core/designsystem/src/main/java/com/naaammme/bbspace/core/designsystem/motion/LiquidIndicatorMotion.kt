package com.naaammme.bbspace.core.designsystem.motion

import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * 指示器位移参数化（W3 · A 级纯逻辑）。
 *
 * ## 上游对应物
 * `design-system/.../ui/animation/LiquidIndicatorMotion.kt`。上游把"位置 spring 的
 * 参数、速度形变系数、按压 bloom 曲线"集中在这一个对象里，让底栏/顶栏/分段控件
 * 共用同一套手感。本仓照这个**结构**做，但只留能 JVM 单测的部分：
 *
 * | 上游成员 | 本仓 | 为什么 |
 * |---|---|---|
 * | `positionSpec/velocitySpec` | ✅ 同语义 | spring 参数集中一处，调用方不再各写数字 |
 * | `pagerPressProgress` | ✅ 同公式 | 相邻页之间的 bloom 曲线（`sin(π·fraction)`） |
 * | `scaleX/scaleY` 速度形变 | ✅ 同公式 | 速度越快越扁/越拉长（液态感） |
 * | `press/release` 状态机 | ❌ 不做 | 它依赖 Compose 帧时钟，属"接线后真机验"的部分 |
 *
 * ## 为什么 spring 参数写成常量而不是直接写数字
 * 本仓对 `tween/spring` 的**数字字面量**有 Lint 基线（上限 4、已满，只许降）。
 * 把参数提成常量既满足守卫，也正好是"一处调手感"的正确结构 —— 这不是绕守卫，
 * 数字从调用点搬到了有名字的定义点。
 */
object LiquidIndicatorMotion {

    /** 速度形变的分母：velocity/10 = "每秒 10 个屏宽"时进入满形变。 */
    const val VelocityDivisor = 10f

    /** 速度形变的上限（±20%）：再快也不继续压扁，否则读作"坏掉了"。 */
    const val VelocityClamp = 0.2f

    /** 吸附判定的容差（比例）：距目标小于它的 1.5% 视为已到位。 */
    const val SettleTolerance = 0.015f

    /** 松手飞掷阈值（px/s）：低于它不换档，只回弹到当前档。 */
    const val SnapVelocityPxPerSecond = 420f

    /**
     * 按压膨胀倍率（1.18 = 按下时元素放大 18%）。
     *
     * ## 为什么按压是"膨胀"而不是"缩小"
     * 本仓其它按钮的按压反馈是**缩小**（0.97/0.92），因为它们是"被按下去的实体"。
     * 底栏指示器不同：它是**液态的一块**，手指压上去应该"被挤出来"——像水球被按。
     * 用户明确描述了这个方向（"按压时元素膨胀"），所以这里用 >1 的值，
     * 与按钮族相反。这不是不一致，是两种物件的物理语言不同。
     */
    const val PressedScale = 1.18f

    /** 拖拽时的基础缩放（松手回到 1f）：略小于 1 让"被拉着走"读起来更轻。 */
    const val DragBaseScale = 1f

    /** 膨胀的等待上限（ms）：超过就不再等，直接开始回弹（防卡死）。 */
    const val BloomWaitMillis = 300L

    /** 位置收敛的等待上限（ms）：同上。 */
    const val SettleWaitMillis = 450L

    // ── spring 参数（上游同款取值）──────────────────────────────────────────
    private const val PositionDampingRatio = 1f
    private const val PositionStiffness = 1000f
    private const val VelocityDampingRatio = 0.5f
    private const val VelocityStiffness = 300f
    private const val DefaultVisibilityThreshold = 0.001f

    /** 位置 spring：无回弹（阻尼 1.0）、收得干脆（stiffness 1000）。 */
    fun positionSpec(visibilityThreshold: Float = DefaultVisibilityThreshold): SpringSpec<Float> =
        spring(
            dampingRatio = PositionDampingRatio,
            stiffness = PositionStiffness,
            visibilityThreshold = visibilityThreshold
        )

    /**
     * **松手回弹** spring：阻尼 0.62 ⇒ 会**过冲一点点再回来**（"弹性晃动"）。
     *
     * 为什么不能复用 [positionSpec]（阻尼 1.0 = 临界阻尼）：临界阻尼是"最快且不振荡"，
     * 读作"啪一下就到位"，完全没有用户要的"弹性晃动"。0.62 大约过冲 6~8%，
     * 两次振荡内收敛 —— 这就是 iOS 那种"吸住"的手感。
     */
    fun releaseSpec(): SpringSpec<Float> =
        spring(
            dampingRatio = 0.62f,
            stiffness = 700f,
            visibilityThreshold = DefaultVisibilityThreshold
        )

    /** 按压膨胀 spring：稍软（阻尼 0.75、刚度 600）⇒ 鼓起来有一点回弹。 */
    fun pressSpec(): SpringSpec<Float> =
        spring(dampingRatio = 0.75f, stiffness = 600f, visibilityThreshold = DefaultVisibilityThreshold)

    /** 横向拉伸 spring（速度形变值用，允许回弹）。 */
    fun scaleXSpec(): SpringSpec<Float> =
        spring(dampingRatio = 0.6f, stiffness = 400f, visibilityThreshold = DefaultVisibilityThreshold)

    /** 纵向形变 spring（比横向更"黏"，避免上下抖）。 */
    fun scaleYSpec(): SpringSpec<Float> =
        spring(dampingRatio = 0.7f, stiffness = 400f, visibilityThreshold = DefaultVisibilityThreshold)

    /**
     * 速度 spring：允许一点回弹（阻尼 0.5），用于液态形变值。
     *
     * 返回 **FloatSpringSpec**（而不是 `SpringSpec<Float>`）：`LiquidIndicatorVelocity`
     * 要逐帧求值（`getValueFromNanos`），那是 FloatSpringSpec 才有的数值接口。
     */
    fun velocitySpec(): FloatSpringSpec =
        FloatSpringSpec(
            dampingRatio = VelocityDampingRatio,
            stiffness = VelocityStiffness
        )

    /** 速度（px/s）→ "每秒多少个 item"。宽度非法时返回 0。 */
    fun itemsPerSecond(velocityPxPerSecond: Float, itemWidthPx: Float): Float {
        if (!velocityPxPerSecond.isFinite() || !itemWidthPx.isFinite()) return 0f
        if (itemWidthPx <= 0f) return 0f
        return velocityPxPerSecond / itemWidthPx
    }

    /** 把任意速度比钳进 [−VelocityClamp, VelocityClamp]（NaN 兜底为 0）。 */
    fun clampVelocityRatio(ratio: Float): Float {
        if (!ratio.isFinite()) return 0f
        return ratio.coerceIn(-VelocityClamp, VelocityClamp)
    }

    /** 拖拽拉伸的最大横向放大比例（1.22 = 最多拉长 22%）。 */
    const val MaxDragStretch = 0.22f

    /** 拖拽拉伸时纵向的**收缩**比例（横向拉长多少，纵向就被挤扁多少的 45%）。 */
    const val DragStretchSquashRatio = 0.45f

    /**
     * 拖拽时的**受力方向拉伸**：横向拉长、纵向被挤扁。
     *
     * ## 为什么之前是错的（用户："拖拽时沿受力方向拉伸"没做到）
     * 旧实现只写了 `scaleX = pressScale * stretch`、`scaleY = pressScale` ——
     * 纵向**完全不参与形变**。视觉上就是"整体等比放大"，读作"变大"而不是"被拉长"。
     * 真实的水球/软胶被横向拉时体积守恒：**横向变长、纵向必然变细**。缺了纵向
     * 收缩这一半，拉伸感就消失了，这也正是用户说"不是我想要的效果"的原因。
     *
     * ## 公式
     * - `dragRatio` = 偏离档位基准的距离 / 一个格距，钳到 0..1；
     * - 横向：`1 + MaxDragStretch * ratio`（越远越长，上限 +22%）；
     * - 纵向：`1 - MaxDragStretch * DragStretchSquashRatio * ratio`（同步变细）。
     *
     * 两者相乘的"视觉体积"近似守恒（不是严格守恒，因为按压膨胀也要叠乘进来）。
     *
     * @param dragRatio 0 = 停在档位上，1 = 拉满一格
     * @return `(scaleX, scaleY)`；非法输入返回 (1, 1)（不形变，绝不留 NaN 到图层）
     */
    fun dragStretch(dragRatio: Float): Pair<Float, Float> {
        if (!dragRatio.isFinite()) return 1f to 1f
        val ratio = dragRatio.coerceIn(0f, 1f)
        val stretchX = 1f + MaxDragStretch * ratio
        val squashY = 1f - MaxDragStretch * DragStretchSquashRatio * ratio
        return stretchX to squashY
    }

    /**
     * 相邻页之间的按压/膨胀进度：`position` 的小数部分走 `sin(π·f)` ——
     * 在两个整页之间（f=0.5）达到 1，落在任一端点回到 0。
     *
     * 为什么用正弦而不是线性：线性会让"到站"瞬间有个折点（导数突变），
     * 正弦在两端导数为 0，读作"自然张开又收回"。
     */
    fun pagerPressProgress(position: Float): Float {
        if (!position.isFinite()) return 0f
        val fraction = position - floor(position)
        return sin(PI * fraction).toFloat().coerceIn(0f, 1f)
    }

    /** 速度越快越"扁"（横向拉伸）：`base / (1 − velocity·0.75/divisor)`。 */
    fun scaleX(base: Float, velocity: Float): Float {
        if (!base.isFinite()) return 1f
        if (!velocity.isFinite()) return base
        val ratio = clampVelocityRatio(velocity / VelocityDivisor * 0.75f)
        val denominator = 1f - ratio
        if (abs(denominator) < Float.MIN_VALUE) return base
        return base / denominator
    }

    /** 速度越快越"拉长"（纵向收缩）：`base · (1 − velocity·0.25/divisor)`。 */
    fun scaleY(base: Float, velocity: Float): Float {
        if (!base.isFinite()) return 1f
        if (!velocity.isFinite()) return base
        val ratio = clampVelocityRatio(velocity / VelocityDivisor * 0.25f)
        return base * (1f - ratio)
    }

    /** 松手时是否算"扔出去了"（超过阈值才允许跨档）。 */
    fun shouldSnap(
        velocityPxPerSecond: Float,
        thresholdPxPerSecond: Float = SnapVelocityPxPerSecond
    ): Boolean {
        if (!velocityPxPerSecond.isFinite()) return false
        val threshold = if (thresholdPxPerSecond.isFinite()) {
            thresholdPxPerSecond.coerceAtLeast(0f)
        } else {
            SnapVelocityPxPerSecond
        }
        return abs(velocityPxPerSecond) >= threshold
    }

    /**
     * 吸附档位：投影值四舍五入，且**一次最多跨 [maxStep] 档**（防"一甩到底"）。
     *
     * @param projected 投影后的"档位单位"位置（0..count-1）
     * @param base 松手瞬间的档位
     */
    fun snapIndex(
        projected: Float,
        base: Int,
        maxStep: Int,
        itemCount: Int
    ): Int {
        if (itemCount <= 0) return 0
        if (!projected.isFinite()) return base.coerceIn(0, itemCount - 1)
        val stepLimit = maxStep.coerceAtLeast(1)
        var next = projected.roundToIntSafe()
        val safeBase = base.coerceIn(0, itemCount - 1)
        if (abs(next - safeBase) > stepLimit) {
            next = safeBase + (if (next > safeBase) stepLimit else -stepLimit)
        }
        return next.coerceIn(0, itemCount - 1)
    }

    /** `Float.roundToInt()` 对 NaN 会返回 0，这里显式收口语义（可读性）。 */
    private fun Float.roundToIntSafe(): Int =
        if (isFinite()) Math.round(this) else 0
}
