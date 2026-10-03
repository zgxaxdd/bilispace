package com.naaammme.bbspace.core.playback

/**
 * 续播与"看完"判定的**单一真值源**（PT-03，语义移植自上游 BiliPai
 * `core-player/.../core/player/PlaybackResumePolicy.kt:4-9`）。
 *
 * ## 与上游的差异（PR 必读）
 * - 上游只有一个 8 行函数（显式 > 本地 > 服务端 → 95% 重播 → 钳位）；bbspace 保留
 *   "显式 seek > 本地历史 > 服务端"的来源优先级在 `StreamPlaybackSessionImpl.resolveStartMs`，
 *   本文件只提供**判定原语**与合并后的纯函数。
 * - "看完"判据：bbspace 原来是"距结尾 ≤3s"，上游是"≥95% 时长"。**本版两者取或**——
 *   3s 规则保住短视频的既有行为（30s 视频的 3s=10%，比 95% 更严），95% 规则补上长视频
 *   （3 小时视频的 3s≈0.03%，3s 规则形同虚设）。
 * - 上游会把续播位置钳到 `duration−1000`；bbspace 原先不钳 → 本版一并补齐
 *   （显式 seek 也钳，与上游一致；单测钉住边界）。
 * - 本地已看完（或 ≥95%）时**直接从 0 重播**，不再回退到服务端位置（上游候选语义）；
 *   bbspace 原实现会回退到服务端——这是有意的行为变更。
 */
object PlaybackResumePolicy {

    /**
     * "贴结尾"阈值（ms）。
     *
     * **单一真值源**：原 `StreamPlaybackSessionImpl` 与 `PlaybackReportSession` 各有一份
     * 同名 `COMPLETE_THRESHOLD_MS = 3_000L`（无一致性断言的重复常量等于制造第二个真值源），
     * 已收敛到此处。两处语义都是"距结尾 ≤3s 算看完"：前者管续播、后者管上报，
     * 由 [isWatchedThrough] / [isWithinTail] 分别承载。
     */
    const val COMPLETE_TAIL_MS = 3_000L

    /** 上游 95% 规则：看过 ≥95% 时长即视为看完（从 0 重播）。 */
    const val WATCHED_THROUGH_FRACTION = 0.95

    /**
     * 是否应视为"已看完"：≥95% 时长 **或** 落在结尾 3s 内（取或，理由见类 KDoc）。
     * `durationMs <= 0` 时恒 false（未知时长不判看完，与上游 `durationMs <= 0` 早退一致）。
     */
    fun isWatchedThrough(progressMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        if (progressMs >= durationMs * WATCHED_THROUGH_FRACTION) return true
        return durationMs - progressMs <= COMPLETE_TAIL_MS
    }

    /** 上报侧的"贴结尾"判定（与 [isWatchedThrough] 共用同一阈值真值源）。 */
    fun isWithinTail(positionMs: Long, durationMs: Long): Boolean {
        return durationMs > 0L && durationMs - positionMs <= COMPLETE_TAIL_MS
    }

    /**
     * 续播位置判定（纯函数，无 IO）。
     *
     * 优先级保留 bbspace 既有语义：显式 seek 永远优先（**不触发** 95% 重播——上游同款：
     * 95% 检查只对非显式候选生效）；其后本地（>0 且未标记看完）→ 服务端；候选"已看完"
     * 则从 0 重播；最后钳到 `[0, duration−1000]`（`durationMs <= 0` 时只保底 ≥0）。
     */
    fun resolveResumePosition(
        explicitMs: Long?,
        localMs: Long?,
        localFinished: Boolean,
        serverMs: Long?,
        durationMs: Long
    ): Long {
        if (explicitMs != null) return clampResumePosition(explicitMs, durationMs)
        val local = localMs?.takeIf { it > 0L && !localFinished }
        val candidate = local ?: serverMs?.takeIf { it > 0L } ?: return 0L
        if (isWatchedThrough(candidate, durationMs)) return 0L
        return clampResumePosition(candidate, durationMs)
    }

    /** 上游规则：续播位置不许落在最后 1 秒内（避免一进来就触发 ENDED）。 */
    private fun clampResumePosition(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return positionMs.coerceAtLeast(0L)
        return positionMs.coerceIn(0L, (durationMs - 1_000L).coerceAtLeast(0L))
    }
}
