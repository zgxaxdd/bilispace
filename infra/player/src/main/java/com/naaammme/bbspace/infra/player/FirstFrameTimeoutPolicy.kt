package com.naaammme.bbspace.infra.player

/**
 * 「起播超时」判据（纯函数，JVM 可测）。
 *
 * ## 为什么需要它
 *
 * `CdnHealthPolicy` 里为 `FirstFrameTimeout` 留了 **−180 分** 的位置，但从移植到现在
 * **生产代码里没有任何地方上报过它**（只有单测在调）—— 于是一条"起播总要转十几秒"的线路
 * 在健康度上完全看不见，选线不会因此把它降权（2026-10-08 审查发现，见
 * `docs/port-analysis/12-播放链路与缓存策略交互审查.md` §B1）。
 *
 * ## 判据（五条缺一不可）
 *
 * | # | 条件 | 为什么 |
 * |---|---|---|
 * | 1 | `playWhenReady` | 用户没点播放（含暂停/缓冲中未起播）不算"起播超时" |
 * | 2 | `!firstFrameRendered` | 出过画面就不叫起播超时（后续卡顿是 `Buffering` 的语义） |
 * | 3 | `buffering` | **必须是"还在等数据"**（`STATE_BUFFERING`）：已 READY 却不出帧是渲染/画面绑定的事，与线路无关 |
 * | 4 | `startedAtMs > 0` | 还没有"开播时刻"（尚未 setSource）时无从计时 |
 * | 5 | `now - startedAtMs >= TIMEOUT_MS` | 未满阈值不判超时 |
 *
 * 第 3 条是真机补出来的：2026-10-07 断网恢复后重建，播放器 1 秒内就 READY（缓冲 34s），
 * 但画面绑定的第一帧过了 10 秒才出 —— 那时若照记 `FirstFrameTimeout`，就是拿"渲染慢"去罚一条
 * 已经供上数据的线路。加上"仍在等数据"这一条，判据就只落在"数据没到"这类真实线路问题上。
 *
 * 外加一次性闸门 `alreadyReported`：一次开播只上报一次（否则 500ms 轮询会连报）。
 *
 * 阈值取 10s：本仓 `playBufferMs=250ms`、`CDN_PREFETCH_SAFE_BUFFER_MS=15s`；
 * 10s 还没出画面，用户已经明显感到"卡住了"，且远大于任何正常起播（真机实测正常起播 < 1.5s）。
 */
internal object FirstFrameTimeoutPolicy {

    /** 起播后多久还没出画面即判超时（对齐 `CdnHealthPolicy` 的 −180 分语义）。 */
    const val TIMEOUT_MS = 10_000L

    fun isTimedOut(
        nowMs: Long,
        startedAtMs: Long,
        firstFrameRendered: Boolean,
        playWhenReady: Boolean,
        buffering: Boolean,
        alreadyReported: Boolean
    ): Boolean {
        if (alreadyReported) return false
        if (!playWhenReady) return false
        if (firstFrameRendered) return false
        if (!buffering) return false
        if (startedAtMs <= 0L) return false
        return nowMs - startedAtMs >= TIMEOUT_MS
    }
}
