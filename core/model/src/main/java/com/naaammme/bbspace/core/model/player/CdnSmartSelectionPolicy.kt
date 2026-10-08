package com.naaammme.bbspace.core.model.player

import java.net.URI

/**
 * 智能选线的**排序与故障切换**策略（对照 BiliPai
 * `CdnRegionPolicy.kt:356-396` 与 `VideoPlaybackViewModel.kt:154-189`）。
 *
 * 与 [CdnHealthPolicy] 的分工：那边管"一条线多少分"，这边管
 * "多条线怎么排"与"卡住了往哪切"。
 *
 * ## 两条排序规则，别搞混（BiliPai 里就是两个函数，语义不同）
 *
 * - [orderUrlsByHealth]：**纯按分数**排（分数降序、同分保持原下标）——
 *   用于"用户没指定线路，让 App 自己挑最好的一条"。
 * - [orderCandidates]：**先按来源优先级、再按分数**——用于"用户显式指定了
 *   某条线（或自定义规则命中）时，那条必须排第一，哪怕它健康度差"。
 *
 * 第二条看着"不智能"，但它是有意的产品契约：用户手动选了线路，App 不能
 * 因为历史分数把它换掉。BiliPai 用测试把这条**钉死**了
 * （`CdnRegionPolicyTest.kt:183-195`：给自定义线路灌满错误分也抢不走首位）。
 */
object CdnSmartSelectionPolicy {

    /** 线路来源：决定 [orderCandidates] 的第一排序键。 */
    enum class Source {
        /** 用户显式指定的 host（重写出来的线路）。 */
        Preferred,

        /** 服务端原始候选。 */
        Original
    }

    /** 带来源的候选。 */
    data class Candidate(val url: String, val source: Source)

    /** 从 URL 取 host（小写）。取不到返回空串 —— 调用方据此跳过该条。 */
    fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")

    /**
     * 纯按健康度排序：**分数降序 → 原下标升序**。
     *
     * 同分保持原序是关键：服务端给的顺序在"都没观测过"时就是唯一线索
     * （BiliPai 的对应断言：空健康表时输出与输入逐元素相等）。
     */
    fun orderUrlsByHealth(
        urls: List<String>,
        healthByHost: Map<String, CdnHealthPolicy.Health>
    ): List<String> {
        return urls.distinct()
            .mapIndexed { index, url -> Triple(url, index, CdnHealthPolicy.score(healthByHost[hostOf(url)])) }
            .sortedWith(
                compareByDescending<Triple<String, Int, Int>> { it.third }
                    .thenBy { it.second }
            )
            .map { it.first }
    }

    /**
     * 来源优先 + 健康度次之的排序（用户指定线路时的语义）。
     *
     * @param strictPreferred 用户开启了"严格只用指定线路"：为 true 且存在
     *   指定线路时，**只返回指定线路**；也为 false 时把其余线路接在后面兜底
     *   （照 BiliPai 的 `strictCustomCdn` 语义：命中就独占，未命中就完整回退）。
     */
    fun orderCandidates(
        candidates: List<Candidate>,
        healthByHost: Map<String, CdnHealthPolicy.Health>,
        strictPreferred: Boolean = false
    ): List<Candidate> {
        val distinct = candidates.distinctBy { it.url }
        val preferred = distinct.filter { it.source == Source.Preferred }
        val others = distinct.filter { it.source == Source.Original }
        if (strictPreferred && preferred.isNotEmpty()) {
            return orderWithin(preferred, healthByHost)
        }
        return orderWithin(preferred, healthByHost) + orderWithin(others, healthByHost)
    }

    private fun orderWithin(
        candidates: List<Candidate>,
        healthByHost: Map<String, CdnHealthPolicy.Health>
    ): List<Candidate> {
        return candidates
            .mapIndexed { index, candidate ->
                Triple(candidate, index, CdnHealthPolicy.score(healthByHost[hostOf(candidate.url)]))
            }
            .sortedWith(
                compareByDescending<Triple<Candidate, Int, Int>> { it.third }
                    .thenBy { it.second }
            )
            .map { it.first }
    }

    /** 卡顿恢复的判定结果。 */
    data class StallRecovery(
        /** 要切到第几条；null = 不切。 */
        val nextIndex: Int? = null
    ) {
        val shouldSwitch: Boolean get() = nextIndex != null
    }

    /**
     * 「播着播着卡住了，该不该换一条线」——**六条判据缺一不可**。
     *
     * 逐条对照 BiliPai `resolvePlaybackStallRecoveryDecision`：
     *
     * | # | 条件 | 为什么 |
     * |---|---|---|
     * | 1 | `playbackState == STATE_BUFFERING` | READY/ENDED/IDLE 都不是"卡住" |
     * | 2 | `playWhenReady` | 用户主动暂停时不算卡 |
     * | 3 | `firstFrameRendered` | **首帧前不切** —— 那属于起播路径（有超时定时器管），两条路一起抢会换线换到飞起 |
     * | 4 | `forwardBufferMs <= 0` | 前面还有缓冲就不叫卡 |
     * | 5 | `candidateCount > 1` | 只有一条线时无从可切 |
     * | 6 | `!adaptivePlayback` | 自适应画质交给播放器自己做 ABR，换线等于拆掉自适应会话 |
     *
     * 选线规则：从 `currentIndex + 1` 起**环形**扫描，取第一条没试过的
     * （`offset` 从 1 开始 ⇒ 永不返回自己）。`attempted` 由调用方维护，
     * 生命周期应绑定 `bvid:cid`（换视频即清空）。
     */
    fun resolveStallRecovery(
        buffering: Boolean,
        playWhenReady: Boolean,
        firstFrameRendered: Boolean,
        forwardBufferMs: Long,
        currentIndex: Int,
        candidateCount: Int,
        attemptedIndexes: Set<Int>,
        adaptivePlayback: Boolean
    ): StallRecovery {
        if (!buffering) return StallRecovery()
        if (!playWhenReady) return StallRecovery()
        if (!firstFrameRendered) return StallRecovery()
        if (forwardBufferMs > 0L) return StallRecovery()
        if (candidateCount <= 1) return StallRecovery()
        if (adaptivePlayback) return StallRecovery()
        val next = (1 until candidateCount)
            .asSequence()
            .map { offset -> (currentIndex + offset) % candidateCount }
            .firstOrNull { index -> index !in attemptedIndexes }
        return StallRecovery(nextIndex = next)
    }
}
