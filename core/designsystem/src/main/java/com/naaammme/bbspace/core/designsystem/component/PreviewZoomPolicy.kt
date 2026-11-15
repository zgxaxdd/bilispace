package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.ui.geometry.Offset

/**
 * 图片预览的缩放判据（纯逻辑，JVM 可测）。
 *
 * ## 为什么单独抽出来
 * 双击缩放这件事看着简单，但有三处容易写错、且错了**在无设备条件下无法发现**：
 * ① 双击到底该放大到几倍（要不要按"已放大就复位"循环）；
 * ② 放大时**锚点**必须钉在手指点的位置，否则用户总觉得"图跑到别处去了"；
 * ③ 放大后平移越界要能夹住，但夹的是"缩放后的可视范围"，不是原图尺寸。
 * 这三条都是纯算术 —— 抽成纯函数就能在 JVM 上钉死，不必等真机。
 *
 * ## 与手势层的关系
 * 手势层（`PreviewImagePage`）只负责：把点击点换算成"相对容器中心的偏移"、把结果喂给
 * `graphicsLayer`。**所有数值决策都在这里**，改观感只改本文件。
 */
internal object PreviewZoomPolicy {

    /** 双击放大目标倍率。2.5 是"能看清细节又不至于立刻要平移"的常用值（iOS 相册同档）。 */
    const val DOUBLE_TAP_SCALE: Float = 2.5f

    /** 缩放上限（与捏合手势共用）。 */
    const val MAX_SCALE: Float = 5f

    /** 小于这个值视为"已经回到 1x"（浮点误差不应让"已复位"被当成"仍放大"）。 */
    const val RESET_EPSILON: Float = 0.01f

    /**
     * 双击后的目标倍率：**已放大就复位到 1，1x 就放大到 [DOUBLE_TAP_SCALE]**。
     *
     * 不做"连续多级放大"（2.5 → 4 → 5）：那需要用户盲猜点了几下才回得去；
     * 两次循环（1x ↔ 2.5x）语义最稳，想更大倍率用捏合。
     */
    fun nextScale(currentScale: Float): Float {
        if (!currentScale.isFinite()) return 1f
        return if (currentScale > 1f + RESET_EPSILON) 1f else DOUBLE_TAP_SCALE.coerceAtMost(MAX_SCALE)
    }

    /**
     * 以 [tapFromCenter]（**屏幕坐标**：锚点相对容器中心的位置）为锚点，把缩放从
     * [fromScale] 变到 [toScale] 时，平移量该是多少。
     *
     * 推导：内容局部点 `u` 在屏幕上的位置是 `p = u * s + offset`。要让锚点处的 `u_a`
     * 在缩放前后**落在同一屏幕位置** `p_a`：
     * `u_a = (p_a - offset) / s`，且 `p_a = u_a * s' + offset'`
     * ⇒ `offset' = p_a - (p_a - offset) * (s' / s)`。
     *
     * ⚠️ 注意 `p_a` 是**屏幕**位置而不是内容坐标 —— 这样同一个函数在"放大"与"缩小"
     * 两个方向都能用（缩小不是放大的反函数：中间 `offset` 已经不是起始值了）。
     * 早期写成 `offset + p_a * (s - s') / s`（只在 `offset == 0` 时才对）会导致
     * "双击放大再双击缩小后，图没有回到正中"—— 单测 `zoomInThenOutAroundSamePointReturnsToOrigin`
     * 就是钉这一条的。
     */
    fun anchoredOffset(
        currentOffset: Offset,
        tapFromCenter: Offset,
        fromScale: Float,
        toScale: Float
    ): Offset {
        if (!fromScale.isFinite() || fromScale <= 0f) return currentOffset
        if (!toScale.isFinite() || toScale <= 0f) return currentOffset
        if (!currentOffset.isSane() || !tapFromCenter.isSane()) return Offset.Zero
        val ratio = toScale / fromScale
        return Offset(
            x = tapFromCenter.x - (tapFromCenter.x - currentOffset.x) * ratio,
            y = tapFromCenter.y - (tapFromCenter.y - currentOffset.y) * ratio
        )
    }

    /**
     * 缩放为 [scale] 时平移量的合法范围（对称，单位 px）。
     *
     * 内容按 [ContentScale.Fit][androidx.compose.ui.layout.ContentScale.Fit] 铺满容器：
     * 放大 `s` 倍后，可平移的余量是 `容器尺寸 * (s - 1) / 2`。`s <= 1` 时余量为 0
     * （此时任何平移都该被清掉，否则复位后会留下"偏了一点"的残影）。
     */
    fun maxPan(containerWidthPx: Float, containerHeightPx: Float, scale: Float): Offset {
        if (!containerUsable(containerWidthPx, containerHeightPx, scale)) return Offset.Zero
        val excess = (scale - 1f) / 2f
        return Offset(containerWidthPx * excess, containerHeightPx * excess)
    }

    /** `Offset.isFinite()` 在部分 Compose 版本不存在，自己判两个分量。 */
    private fun Offset.isSane(): Boolean = x.isFinite() && y.isFinite()

    private fun containerUsable(widthPx: Float, heightPx: Float, scale: Float): Boolean =
        widthPx.isFinite() && heightPx.isFinite() && widthPx > 0f && heightPx > 0f &&
            scale.isFinite() && scale > 1f

    /** 把平移量夹进 [maxPan] 范围；[maxPan] 为 0 时直接归零。 */
    fun clampPan(offset: Offset, maxPan: Offset): Offset {
        if (!offset.isSane()) return Offset.Zero
        if (!maxPan.isSane()) return Offset.Zero
        // 上限为 0 时直接给 `0f`：`coerceIn(-0.0f, 0.0f)` 在 x 为负时会返回 `-0.0f`，
        // 而 `Offset` 是位打包比较 ⇒ `Offset(0f, -0.0f) != Offset(0f, 0f)`（测试会红）。
        return Offset(
            x = if (maxPan.x <= 0f) 0f else offset.x.coerceIn(-maxPan.x, maxPan.x),
            y = if (maxPan.y <= 0f) 0f else offset.y.coerceIn(-maxPan.y, maxPan.y)
        )
    }
}
