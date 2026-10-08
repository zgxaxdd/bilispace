package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「网络恢复后补一次重建」判据（R-19，产品决策 A2）。
 *
 * 背景：R-12 修掉了"断网时每 3.3 秒重建一次"的活锁（断网期间不为网络错误重建），
 * 代价是网络恢复后**没人**把那次重建补上，用户得手点重试。本判据就是"什么时候补"。
 *
 * 最关键的一条不是"网络回来了"，而是 `retryUsed`：**一次会话只补一次**，
 * 绝不能让"自动重试"变成新的重试风暴（那正是 R-12 修掉的东西）。
 */
class NetworkReturnRetryPolicyTest {

    private fun retry(
        pendingOfflineRetry: Boolean = true,
        retryUsed: Boolean = false,
        networkAvailable: Boolean = true,
        playWhenReady: Boolean = true,
        isPlaying: Boolean = false
    ) = NetworkReturnRetryPolicy.shouldRetryOnNetworkReturn(
        pendingOfflineRetry = pendingOfflineRetry,
        retryUsed = retryUsed,
        networkAvailable = networkAvailable,
        playWhenReady = playWhenReady,
        isPlaying = isPlaying
    )

    @Test
    fun retriesOnceWhenNetworkComesBack() {
        assertTrue("断网时落下的重建 + 网络回来 + 用户仍想播 ⇒ 补一次", retry())
    }

    @Test
    fun neverRetriesMoreThanOncePerSession() {
        assertFalse(
            "已经补过 ⇒ 再也不补（防重试风暴，R-12 的教训）",
            retry(retryUsed = true)
        )
    }

    @Test
    fun fourGuardsMustAllHold() {
        assertFalse("没有待办（不是断网时落下的）⇒ 不动作", retry(pendingOfflineRetry = false))
        assertFalse("网络还没真正可用（回调早于路由就绪）⇒ 不动作", retry(networkAvailable = false))
        assertFalse("用户已暂停/退出 ⇒ 不自作主张开播", retry(playWhenReady = false))
        assertFalse("还在正常播（比如只是切了 Wi-Fi）⇒ 不打扰", retry(isPlaying = true))
    }

    @Test
    fun debounceIsNotZero() {
        assertTrue(
            "回调必须去抖（等 Wi-Fi 拿到可用路由），否则会在网络稳定前白重建一次",
            NetworkReturnRetryPolicy.DEBOUNCE_MS >= 500L
        )
    }
}
