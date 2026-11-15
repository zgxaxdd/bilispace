package com.naaammme.bbspace.infra.player.cdn

/**
 * CDN 连接的**降级与恢复**判据（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么需要它（用户症状：卡在约 5 秒缓冲再也不动）
 *
 * `CdnPlaybackDataSource.read()` 在并行窗口失败时做的是 `CdnTransferRuntime.fallback();
 * parallel = false` —— 那个 `false` 是**该 DataSource 实例整个会话**的，没有任何重升路径。
 * 而并发策略 `resolveCdnConnections` 的核心救援分支是"缓冲 < 5_000ms → 3 连接"：
 * 缓冲掉到 5 秒以下本该**加并发抢时间**，但并行已经被永久关掉 ⇒ 救援分支永远进不去
 * ⇒ 单连接在弱网/被限速时只能以单流速度往前爬，表现为"停在 5 秒的缓冲上"。
 *
 * 这就是"降级不可逆"与"救援分支永远不可达"叠加出的死局。修法不是取消降级
 * （降级本身是为了别把坏网络打得更烂），而是**给降级加冷却与恢复条件**：
 * 单连接稳定跑够 [PARALLEL_RETRY_COOLDOWN_MS]、且缓冲已回到
 * [PARALLEL_RESTORE_BUFFER_MS] 以上，才允许重新尝试并行。
 */
internal object CdnConnectionPolicy {

    /** 降级后至少要单连接跑这么久才允许重试并行（避免在坏网络上反复抖动）。 */
    const val PARALLEL_RETRY_COOLDOWN_MS = 15_000L

    /** 缓冲回到这个厚度以上才允许重试并行（不够厚时重试只会再失败一次）。 */
    const val PARALLEL_RESTORE_BUFFER_MS = 10_000L

    /**
     * 并行窗口领取槽位的最长等待（毫秒）。
     *
     * 槽位由 OkHttp 回调释放，而 `cancelCalls()` 之后回调**不保证立刻执行** ⇒
     * 下一窗口的 `tryAcquire` 可能瞬时失败。修复前直接 `fallback()` 永久降级；
     * 现在先在这里等一个有界时间，让上一窗口的回调把槽位还回来。
     */
    const val WINDOW_SLOT_WAIT_MS = 300L

    /**
     * 是否允许把"已降级成单连接"的 DataSource 重新升回并行。
     *
     * 五个条件缺一不可：曾经降级过（`disabledAtMs > 0`）、冷却已过、缓冲够厚、
     * 运行时允许并行（设置项 + 会话未停）、网络仍是 Wi-Fi（省流量口径不变）。
     */
    fun shouldRestoreParallel(
        nowMs: Long,
        disabledAtMs: Long,
        bufferMs: Long,
        parallelAllowed: Boolean,
        wifi: Boolean
    ): Boolean {
        if (!parallelAllowed || !wifi) return false
        if (disabledAtMs <= 0L) return false
        if (nowMs - disabledAtMs < PARALLEL_RETRY_COOLDOWN_MS) return false
        return bufferMs >= PARALLEL_RESTORE_BUFFER_MS
    }
}
