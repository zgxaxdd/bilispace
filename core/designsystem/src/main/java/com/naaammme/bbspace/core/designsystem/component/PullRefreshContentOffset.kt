package com.naaammme.bbspace.core.designsystem.component

/**
 * 下拉刷新时**内容跟随指示器**的位移量（纯函数，可 JVM 测）。
 *
 * ## 为什么需要它（BiliPai B8-b 移植）
 *
 * 上游 `HomePullRefreshUiPolicy.resolveMd3PullContentOffsetPx` 的做法：Material3 的
 * 原生指示器自己会下降，而内容**不动** —— 下拉时只有一个圆点在跑、正文钉在原地，
 * 读作"只是转了个圈"，没有"把整页拉下来"的物理感。
 *
 * ## 位移必须与指示器的基准**同源**，不能自己拍一个数
 *
 * 指示器底边的实际位置可以从 material3 的字节码读出来
 * （`PullToRefreshDefaults.IndicatorBox` 的 `graphicsLayer`）：
 *
 * ```
 * translationY = distanceFraction * roundToPx(maxDistance) − size.height
 * ```
 *
 * 即 `translationY + height == distanceFraction * maxDistancePx` —— **指示器底边**
 * 就落在 `distanceFraction * maxDistancePx` 处。所以内容要"贴着指示器下沿"，
 * 就应当位移**同一个量**，这正是本函数返回的东西。
 *
 * ## 与上游的一处刻意分叉
 *
 * 上游用的是 M3 的固定常量 `PullToRefreshDefaults.PositionalThreshold`（80dp，字节码
 * `<clinit>` 里实证）。本仓**不用那个常量**：`BiliPullToRefreshBox` 的 `maxDistance`
 * 传的是 `LocalPullRefreshThreshold`（用户可在设置里改的「下拉刷新距离」，
 * 默认 80dp 但被 `MinPullRefreshIndicatorThreshold = 40dp` 兜底），
 * 而 `positionalThreshold` 恒为 80dp ⇒ 若照抄上游，用户把刷新距离调到 40dp 时
 * 指示器只下移 40dp、内容却仍按 80dp 位移，**内容会跑到指示器下面去**。
 * 传 `indicatorThresholdPx` 让两者始终同一个数，是本仓唯一自洽的接法。
 *
 * @param distanceFraction 指示器的下拉进度（`PullToRefreshState.distanceFraction`），
 *   1.0 == 达到触发阈值。**主线程读、绘制期用**是它的预期用法（`graphicsLayer`）。
 * @param indicatorThresholdPx 指示器的位移基准（px），必须与传给
 *   `PullToRefreshDefaults.Indicator(maxDistance = …)` 的是同一个值。
 * @param gapPx 指示器下沿与内容顶边之间的净空（px）。超过 1.0 的部分**不再计入净空**
 *   （`coerceAtMost(1f)`），否则回弹阶段内容会被推得越来越远、像被甩出去。
 */
internal fun resolvePullContentOffsetPx(
    distanceFraction: Float,
    indicatorThresholdPx: Float,
    gapPx: Float,
): Float {
    val distance = distanceFraction.coerceAtLeast(0f)
    return distance * indicatorThresholdPx + distance.coerceAtMost(1f) * gapPx
}
