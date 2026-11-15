package com.naaammme.bbspace.core.designsystem.motion

import kotlin.math.roundToInt

/**
 * 指示器与目标 pill 的**匹配几何**（W3 · A 级纯逻辑）。
 *
 * ## 上游对应物
 * `design-system/.../ui/MatchedLiquidIndicatorGeometry.kt`：BiliPai 的悬浮底栏
 * 以 64dp 为参考壳高，指示器静止 56dp、按压/拖拽时涨到 78dp（`78/56 ≈ 1.393`），
 * 其余尺寸的 chrome（小横条、胶囊 dock）都按这个比例缩放。「匹配」的含义是
 * **指示器与它所在容器的比例恒定**，而不是各自写死一套 dp。
 *
 * ## 本仓为什么按"比例 + 插值"重写
 * 本仓的 Dock 高度是 token（`BiliRadius.dock` 等）且会随设备/风格变化，写死
 * 56/78dp 会在小窗/竖屏里变成"过大的泡"。所以这里只输出**比例与插值函数**：
 * - 输入：容器高度（dp，由调用方从 token 取）；
 * - 输出：指示器高度、按压缩放、角半径、以及在"两个目标矩形之间"的插值。
 *
 * 全部为 Float，不引入 Compose `Dp` —— 这样它可 JVM 单测，也不会新增 dp 字面量
 * （本仓 `DesignTokenLintTest` 统计的是源码里的 `N.dp` 写法）。
 */
object MatchedLiquidIndicatorGeometry {

    /** 参考壳高（上游同款，64dp）：所有比例都以它为基准。 */
    const val ReferenceShellHeightDp = 64f

    /** 静止态指示器高度（上游同款 56dp）。 */
    const val ReferenceIndicatorHeightDp = 56f

    /** 按压/拖拽态的**物理上限**（上游同款 78dp）：小容器也不许涨破它。 */
    const val ReferencePressedHeightDp = 78f

    /** 一次匹配计算的结果（单位 dp / 比例，不是 Dp 类型）。 */
    data class IndicatorGeometry(
        val shellHeightDp: Float,
        val indicatorHeightDp: Float,
        val pressedScale: Float
    ) {
        /**
         * 按压后的实际高度（dp）。
         *
         * 封顶语义：不超过「静止高度与参考上限 78dp 的较大者」——静止高度本来就
         * 超过 78dp 的超大容器（壳高 > 89dp）**不会**被压回去（那会缩水），但也
         * 不再继续放大。小容器则按 78dp 的上限削平 bloom。
         */
        val pressedHeightDp: Float
            get() = minOf(
                indicatorHeightDp * pressedScale,
                maxOf(indicatorHeightDp, MatchedLiquidIndicatorGeometry.ReferencePressedHeightDp)
            )
    }

    /**
     * 指示器静止高度：壳高 × (56/64) = 壳高的 87.5%。
     *
     * 壳高非法（<=0 / NaN）时返回 0 —— 调用方据此跳过绘制，而不是画一个 0 高的泡。
     */
    fun indicatorHeightDp(shellHeightDp: Float): Float {
        if (!shellHeightDp.isFinite() || shellHeightDp <= 0f) return 0f
        return shellHeightDp * (ReferenceIndicatorHeightDp / ReferenceShellHeightDp)
    }

    /**
     * 按压缩放：在参考壳高下就是 78/56；容器变矮时按同一比例缩小，
     * 但**物理高度封顶 78dp**（小窗里不会涨成一个过大的泡）。
     */
    fun pressedScale(shellHeightDp: Float, indicatorHeightDp: Float): Float {
        if (!shellHeightDp.isFinite() || !indicatorHeightDp.isFinite()) return 1f
        if (shellHeightDp <= 0f || indicatorHeightDp <= 0f) return 1f
        val scaledPressedHeight =
            shellHeightDp * (ReferencePressedHeightDp / ReferenceShellHeightDp)
        val pressedHeight = minOf(ReferencePressedHeightDp, scaledPressedHeight)
        return (pressedHeight / indicatorHeightDp).coerceAtLeast(1f)
    }

    /** 一次算全：静止高度 + 按压缩放。 */
    fun resolve(
        shellHeightDp: Float,
        indicatorHeightDp: Float = indicatorHeightDp(shellHeightDp)
    ): IndicatorGeometry {
        val height = if (indicatorHeightDp.isFinite() && indicatorHeightDp > 0f) {
            indicatorHeightDp
        } else {
            indicatorHeightDp(shellHeightDp)
        }
        return IndicatorGeometry(
            shellHeightDp = if (shellHeightDp.isFinite()) shellHeightDp else 0f,
            indicatorHeightDp = height,
            pressedScale = pressedScale(shellHeightDp, height)
        )
    }

    /** 角半径：**半高** ⇒ 两端全圆（与 `BiliRadius.playerControl` 同一套"高度一半"惯例）。 */
    fun cornerRadiusDp(indicatorHeightDp: Float): Float {
        if (!indicatorHeightDp.isFinite() || indicatorHeightDp <= 0f) return 0f
        return indicatorHeightDp / 2f
    }

    /**
     * 角半径插值：指示器从"档位 pill"变形到"目标 pill"时，半径跟随进度线性过渡。
     *
     * 线性而不是弹簧：**几何插值**由动画进度驱动（进度本身可以是弹簧），
     * 这里再叠一层非线性会让两段曲线互相打架。
     */
    fun lerpCornerRadiusDp(fromDp: Float, toDp: Float, progress: Float): Float {
        val t = clampProgress(progress)
        return fromDp + (toDp - fromDp) * t
    }

    /** 水平位移插值：from 中心 → to 中心。 */
    fun lerpOffsetPx(fromPx: Float, toPx: Float, progress: Float): Float {
        val t = clampProgress(progress)
        return fromPx + (toPx - fromPx) * t
    }

    /** 宽度插值：from 宽 → to 宽（指示器追不同宽度的 tab 时用）。 */
    fun lerpWidthPx(fromPx: Float, toPx: Float, progress: Float): Float {
        val t = clampProgress(progress)
        return fromPx + (toPx - fromPx) * t
    }

    /** 档位索引 → 该档的静止中心（px）：`index · pitch + itemWidth/2`。 */
    fun itemCenterPx(index: Int, itemWidthPx: Float, pitchPx: Float): Float {
        if (index < 0 || !itemWidthPx.isFinite() || !pitchPx.isFinite()) return 0f
        return index * pitchPx + itemWidthPx / 2f
    }

    /** 进度钳制：NaN/越界一律回到 [0,1]（插值函数的安全入口）。 */
    fun clampProgress(progress: Float): Float {
        if (!progress.isFinite()) return 0f
        return progress.coerceIn(0f, 1f)
    }

    /** 高度取整（dp 画布上避免半像素模糊；至少 1dp，0 表示"不绘制"由调用方判）。 */
    fun roundHeightDp(shellHeightDp: Float): Int =
        indicatorHeightDp(shellHeightDp).roundToInt()
}
