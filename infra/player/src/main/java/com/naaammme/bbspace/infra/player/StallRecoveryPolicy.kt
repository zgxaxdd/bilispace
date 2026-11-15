package com.naaammme.bbspace.infra.player

/**
 * 播放中途**卡死**的阶梯恢复策略（纯逻辑，可 JVM 单测；时间由调用方注入）。
 *
 * ## 为什么需要它（用户症状：长视频播一段时间后卡住、停在约 5 秒的缓冲上再也不动）
 *
 * 修复前引擎只有"错误回调驱动的恢复"：`onPlayerError` → `recoverSession`。
 * 而"卡住"在多数现场**不报错**——播放器停在 `STATE_BUFFERING`，既没有 `PlaybackException`，
 * 也没有任何周期性看门狗去发现"`bufferedPosition` 与 `position` 连续 N 秒都没动"。
 * 于是 ExoPlayer 永远等一个不会回来的 DataSource 窗口，界面就一直转圈。
 *
 * ## 判据
 *
 * 一次采样里，满足以下**全部**才算"无进展"：
 * - 用户仍想播（`playing == true`，即 `playWhenReady` 且状态是 READY/BUFFERING）；
 * - `positionMs` 与上一采样相同；
 * - `bufferedMs` 没有比历史基线更远（缓冲不再增长 = 加载器也没在干活）；
 * - 上述无进展已持续 ≥ [DEFAULT_STALL_THRESHOLD_MS]。
 *
 * 任一进展（position 前进、缓冲前进、seek 导致 position 变化）都会把阶梯**清零**：
 * 恢复动作一旦生效就从头再来，避免"一次抖动把 4 级阶梯全烧掉"。
 *
 * ## 阶梯（每级之间有 [DEFAULT_STEP_COOLDOWN_MS] 冷却，给上一级留出生效时间）
 *
 * 1. [Action.FLUSH] —— 原地 seek 一次（让 ExoPlayer flush 渲染器队列、重启加载器）；
 * 2. [Action.SEEK_TO_SELF] —— 降权当前供数 host 再原地 seek（换一条线路重新取数据，
 *    针对"单连接被挂死"这个最贴合的根因）；
 * 3. [Action.REBUILD] —— 走引擎既有的 [Media3PlayerEngine] 会话重建（复用
 *    `RebuildThrottle` 60s/3 次的防抖预算，不自带第二套预算）；
 * 4. [Action.REPORT_ERROR] —— 三次自动恢复都无效，落错误态交给 UI（用户可手动重试）。
 *
 * 阶梯**每档只触发一次**（`stage` 只增不减，直到出现进展），所以不会在同一档上反复
 * flush/seek 形成新的活锁 —— 这正是 2026-10-06 "每 3.3 秒重建一次" 那次事故的教训。
 */
internal class StallRecoveryPolicy(
    private val stallThresholdMs: Long = DEFAULT_STALL_THRESHOLD_MS,
    private val stepCooldownMs: Long = DEFAULT_STEP_COOLDOWN_MS
) {

    enum class Action {
        /** 无进展但还没到阈值 / 正在冷却 / 已走完阶梯。 */
        NONE,

        /** 第 1 级：原地 seek（flush 渲染器队列并让加载器重新要数据）。 */
        FLUSH,

        /** 第 2 级：降权当前 host + 原地 seek（换线路重取）。 */
        SEEK_TO_SELF,

        /** 第 3 级：重建会话（调用方负责吃 `RebuildThrottle` 额度）。 */
        REBUILD,

        /** 第 4 级：三次自动恢复都无效，落错误态。 */
        REPORT_ERROR
    }

    /** 上一采样时刻的读数；-1 = 还没有基线（或刚复位）。 */
    private var lastPositionMs = -1L
    private var lastBufferedMs = -1L
    private var lastAdvanceAtMs = -1L

    /** 0=flush 1=seek 2=rebuild 3=report 4=已报告（等进展后复位）。 */
    private var stage = 0
    private var lastActionAtMs = Long.MIN_VALUE / 2

    /**
     * 采一次样，返回**本次应当执行**的动作（至多一个）。
     *
     * @param nowMs 单调时钟（`SystemClock.elapsedRealtime`），调用方注入便于测试
     * @param positionMs `player.currentPosition`
     * @param bufferedMs `player.bufferedPosition`（绝对位置，不是剩余缓冲）
     * @param playing 用户仍想播且播放器处于 READY/BUFFERING
     */
    fun sample(nowMs: Long, positionMs: Long, bufferedMs: Long, playing: Boolean): Action {
        if (!playing) {
            reset()
            return Action.NONE
        }
        if (lastAdvanceAtMs < 0L) {
            lastPositionMs = positionMs
            lastBufferedMs = bufferedMs
            lastAdvanceAtMs = nowMs
            return Action.NONE
        }
        val positionAdvanced = positionMs != lastPositionMs
        val bufferAdvanced = bufferedMs > lastBufferedMs
        if (positionAdvanced || bufferAdvanced) {
            lastPositionMs = positionMs
            // 位置变化（含 seek 后退）时缓冲基线必须跟着走，否则"向后 seek 后缓冲低于旧基线"
            // 会被误判成"缓冲不再增长"，进而把一整段正常播放当成卡死。
            lastBufferedMs = bufferedMs
            lastAdvanceAtMs = nowMs
            if (stage != 0) stage = 0
            return Action.NONE
        }
        // 缓冲**回退**（我们自己 FLUSH 的原地 seek 会丢前景缓冲、ExoPlayer 也会回收缓冲）
        // 既不算进展，**也不能继续拿旧的高位基线当"仍无进展"的证据** —— 否则回退后的
        // 充值期一进来就被判停滞，4s 冷却一到就烧下一档，同一段卡死被阶梯反复烧。
        // ⇒ 把基线一起下调；之后缓冲只要**涨过这个新基线**就重新算进展。
        if (bufferedMs < lastBufferedMs) lastBufferedMs = bufferedMs
        val silentMs = nowMs - lastAdvanceAtMs
        if (silentMs < stallThresholdMs) return Action.NONE
        if (stage >= STAGE_DONE) return Action.NONE
        if (nowMs - lastActionAtMs < stepCooldownMs) return Action.NONE
        val action = when (stage) {
            0 -> Action.FLUSH
            1 -> Action.SEEK_TO_SELF
            2 -> Action.REBUILD
            else -> Action.REPORT_ERROR
        }
        lastActionAtMs = nowMs
        stage += 1
        return action
    }

    /** 距最近一次进展过了多久（诊断行用；没有基线时返回 0）。 */
    fun msSinceAdvance(nowMs: Long): Long {
        val base = lastAdvanceAtMs
        if (base < 0L) return 0L
        return (nowMs - base).coerceAtLeast(0L)
    }

    /** 当前阶梯档位（0..4，诊断行用）。 */
    fun stage(): Int = stage

    /** 复位（新会话 / 停止 / 释放 / 用户暂停）。 */
    fun reset() {
        lastPositionMs = -1L
        lastBufferedMs = -1L
        lastAdvanceAtMs = -1L
        stage = 0
        lastActionAtMs = Long.MIN_VALUE / 2
    }

    /**
     * 本策略自己那一刀（[Action.REBUILD]）把会话重建之后调用：**保留阶梯档位**。
     *
     * ## 为什么不能走 [reset]（评审 A1，确定性缺陷）
     * `recoverSession` 里会 `resetRuntimeState()` ⇒ 如果连阶梯一起清零，同一段卡死会走
     * `FLUSH(8s) → SEEK(12s) → REBUILD(16s) → 【清零】→ FLUSH → SEEK → REBUILD → …`
     * 直到 `RebuildThrottle`（60s/3 次）拒绝，约 **74s** 才落到 REPORT，期间烧掉 3 次
     * 整会话重建（每次丢缓冲、重取索引）—— 重建本该是**最后手段**，不是可反复用的一刀。
     *
     * 保留 `stage` 后：重建后第一次采样若仍无进展，冷却一到就直接升到下一档（REPORT），
     * 正确表达"重建也救不回来"。
     *
     * 但**读数基线必须作废**：位置/缓冲来自新 player 实例，与旧实例不可比。
     */
    fun onSessionRebuilt() {
        lastPositionMs = -1L
        lastBufferedMs = -1L
        lastAdvanceAtMs = -1L
    }

    companion object {
        /** 连续无进展多久才动第一刀（8s：比 ExoPlayer 自己的 12s 窗口超时更早，留出恢复余量）。 */
        const val DEFAULT_STALL_THRESHOLD_MS = 8_000L

        /** 每一级恢复动作之间的冷却（4s：seek 生效、加载器重启都需要几个心跳）。 */
        const val DEFAULT_STEP_COOLDOWN_MS = 4_000L

        /** 阶梯走完（已落错误态）的档位。 */
        const val STAGE_DONE = 4
    }
}
