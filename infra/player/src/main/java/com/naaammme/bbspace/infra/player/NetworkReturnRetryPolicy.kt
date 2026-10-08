package com.naaammme.bbspace.infra.player

/**
 * 「断网时落下的那次重建，等网络回来补一次」判据（纯函数，JVM 可测）。
 *
 * ## 背景（为什么需要它）
 *
 * R-12（2026-10-06 真机）修掉了"断网时每 3.3 秒重建一次"的活锁：断网期间**不为网络错误重建**，
 * 只落错误态。代价是——网络恢复后**没有任何东西**会把那次该有的重建补上，用户得手点重试
 * （核对总表 §5.2 遗留项）。
 *
 * ## 判据（五条缺一不可）
 *
 * | # | 条件 | 为什么 |
 * |---|---|---|
 * | 1 | `pendingOfflineRetry` | 只有"断网时被跳过的重建"才补；网络自己抖动不算 |
 * | 2 | `!retryUsed` | **一次会话只补一次**：绝不允许退化成新的重试风暴 |
 * | 3 | `networkAvailable` | 网络确实回来了（回调里查 `NetworkTypeProvider`，不只看回调触发） |
 * | 4 | `playWhenReady` | 用户已暂停/退出时不要自作主张开播 |
 * | 5 | `!isPlaying` | 还在正常播（回切 Wi-Fi 但没断过）就别打扰 |
 *
 * 触发方（引擎）还要**去抖**：回调可能在网络稳定前连续来几次，先等 [DEBOUNCE_MS] 再查上面五条。
 */
internal object NetworkReturnRetryPolicy {

    /** 回调到真正重试之间的去抖窗口：等 Wi-Fi 拿到可用路由再动作。 */
    const val DEBOUNCE_MS = 1_500L

    fun shouldRetryOnNetworkReturn(
        pendingOfflineRetry: Boolean,
        retryUsed: Boolean,
        networkAvailable: Boolean,
        playWhenReady: Boolean,
        isPlaying: Boolean
    ): Boolean {
        if (!pendingOfflineRetry) return false
        if (retryUsed) return false
        if (!networkAvailable) return false
        if (!playWhenReady) return false
        if (isPlaying) return false
        return true
    }
}
