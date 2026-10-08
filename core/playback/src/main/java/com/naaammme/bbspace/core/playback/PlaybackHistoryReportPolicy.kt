package com.naaammme.bbspace.core.playback

/**
 * 历史上报的**发送节流判据**（纯函数，JVM 可直测）。
 *
 * 这一份同时服务两件事（报告 P2-2 与 P1-2）：
 *
 * ## 一、P2-2：pause 沿的重复上报
 * 此前每个「暂停沿」都直接发一次历史包，没有任何间隔/增量门控：
 * 连按暂停/播放 5 次 = 5 次请求；暂停后立刻退出 = 暂停沿 1 次 + `finalizeSession` 1 次。
 * 而**周期心跳早就有**这个门控（`PlaybackHeartbeatPolicy.shouldFlushPlaybackHeartbeatSnapshot`
 * 要求相对上次有进展）——历史上报没有，属两套纪律。
 * → [shouldReportHistoryProgress]：进度没往前走就不发。
 *
 * ## 二、P1-2：会话中途的远端写回
 * 本地有 5s 精度检查点，但**只写本地**；`finalizeSession` 只在正常退出时跑。
 * 于是「长视频中途被杀进程/断电」时远端永远停在起播点。
 * → [shouldSyncRemoteProgress]：低频（分钟级）+ 有实质增量才补发一次远端。
 * 它**必须独立成一条方法**，不能内联进 `checkpointLocalHistory` ——
 * `PlaybackHistoryFlushGuardTest` 的两条不变式明确要求「检查点只写本地」，
 * 那是仓库**有意**做的设计决策（避免稳态每分钟多次网络请求），
 * 本函数不是推翻它，而是在它之外另开一条**有节流**的通道。
 */
internal object PlaybackHistoryReportPolicy {

    /**
     * 距上次远端上报的**最小进度增量**（毫秒）。
     *
     * 1s 的取舍：暂停后位置就冻住了，所以"暂停 → 立刻退出"两次上报的位置完全相同
     * → 第二次被挡住；而真的看了 1s 以上再暂停，仍然照发（那确实是新信息）。
     * 取更大（如 5s）会把"看 3 秒就暂停"的信息也吞掉，没必要。
     */
    const val MIN_PROGRESS_DELTA_MS = 1_000L

    /**
     * 会话中途远端写回的**最小间隔**（毫秒）：5 分钟。
     *
     * 为什么是 5 分钟而不是更短：本仓**未抓包确认**服务端对同 aid/cid 的高频
     * `history/report` 是否限流（报告 §3.7.2 的真机前置）。在此前提下取一个
     * 明显保守的值——一支 1 小时的视频最多多发 12 次，而它换来的是
     * "中途被杀进程后远端进度不再停在起播点"。
     */
    const val REMOTE_SYNC_INTERVAL_MS = 5 * 60 * 1_000L

    /** 中途写回还要有实质增量（秒级抖动不值得发一次请求）。 */
    const val REMOTE_SYNC_MIN_DELTA_MS = 60_000L

    /**
     * pause 沿是否该发远端历史。
     *
     * @param lastReportedPositionMs 本会话**上一次真正发出**的位置（0 = 还没发过）
     */
    fun shouldReportHistoryProgress(
        positionMs: Long,
        lastReportedPositionMs: Long,
        minDeltaMs: Long = MIN_PROGRESS_DELTA_MS
    ): Boolean {
        if (minDeltaMs <= 0L) return true
        return positionMs - lastReportedPositionMs >= minDeltaMs
    }

    /**
     * 会话中途是否该补发一次远端进度。
     *
     * @param inBackground 后台不发：与周期心跳一致（`PlaybackHeartbeatPolicy
     *   .shouldSendPlaybackHeartbeat` 同样要求前台）。后台播放由既有的
     *   「进后台只写本地」通道负责，两条通道不要互相打架。
     */
    fun shouldSyncRemoteProgress(
        nowElapsedMs: Long,
        lastSyncElapsedMs: Long,
        positionMs: Long,
        lastSyncedPositionMs: Long,
        inBackground: Boolean,
        intervalMs: Long = REMOTE_SYNC_INTERVAL_MS,
        minDeltaMs: Long = REMOTE_SYNC_MIN_DELTA_MS
    ): Boolean {
        if (inBackground) return false
        if (intervalMs > 0L && lastSyncElapsedMs > 0L && nowElapsedMs - lastSyncElapsedMs < intervalMs) {
            return false
        }
        return positionMs - lastSyncedPositionMs >= minDeltaMs
    }
}
