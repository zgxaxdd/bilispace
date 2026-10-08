package com.naaammme.bbspace.core.model.player

/**
 * CDN 线路**健康度**的纯策略（对照 BiliPai `CdnRegionPolicy.kt:100-120,398-475` +
 * `CdnTransferPolicy.kt:43-57`）。
 *
 * ## 它解决什么
 *
 * B 站会下发同一支视频的**多条 CDN 线路**（`base_url` + `backup_url[]`），但
 * "哪条快"随地区、运营商、时段变化 —— 服务端给的顺序**不代表**当下最优。
 * 本策略把「这条线在我这台设备上实际表现如何」记成一份可衰减的分数：
 * 分数高的优先用，出过错的自动降到后面。这就是"智能选线"的全部内核。
 *
 * ## 为什么是纯函数 + 无全局状态
 *
 * 本仓口径：**数值与判定在这里，存放位置由调用方定**。所以本文件里
 * 没有单例、没有 StateFlow、没有时间戳来源 —— `nowMs` 一律由调用方传入，
 * 于是全部可 JVM 单测（对照 BiliPai 那份 736 行、拖着 `PluginStore` 与
 * Compose 设置页的实现）。
 *
 * ## 时间口径（**易踩的坑**）
 *
 * BiliPai 的运行时用 `System.nanoTime()`（单调），健康度时间戳用
 * `System.currentTimeMillis()`（墙钟）—— 两者混用会让衰减算出负数。
 * 本策略**只接受一种时间**（墙钟毫秒），调用方必须保持一致。
 */
object CdnHealthPolicy {

    /** 无任何观测时的中性分。**不是 0** —— 0 是"已被判死刑"，会让新线路永远排不上。 */
    const val BASELINE_SCORE = 1_000

    /** 播放成功一次加多少分。 */
    const val READY_CREDIT = 25

    /** `readyCount` 计入加分时的封顶（防"老线路靠资历霸榜"）。 */
    const val READY_CREDIT_CAP = 10

    /** 起播超时一次扣多少分。 */
    const val FIRST_FRAME_TIMEOUT_PENALTY = 180

    /** 播放器报错 / 音轨缺失一次扣多少分。 */
    const val PLAYER_ERROR_PENALTY = 220

    /** 缓冲一次扣多少分。 */
    const val BUFFERING_PENALTY = 55

    /**
     * **换线/开流失败**的惩罚（2026-10-06 闭环修复新增）。
     *
     * 为什么不复用 PLAYER_ERROR_PENALTY（220）：换线是"逐条试"的，一次播放里可能
     * 同时失败好几条线 —— 用 220 记会让**所有**线路迅速变负分，选线彻底失效。
     * 40 分 ≈ 1.6 次成功加分的量级：既不轻纵，也不误伤。
     */
    const val LINE_OPEN_FAILURE_PENALTY = 40

    /** 失败计数**封顶**：避免"同一支坏视频反复失败"把线路永久钉死。 */
    const val LINE_OPEN_FAILURE_CAP = 8

    /** 探测速率换分的除数：每 128 kbps 加 1 分。 */
    const val SPEED_DIVISOR = 128L

    /** 速率加分的封顶。 */
    const val SPEED_CREDIT_CAP = 500

    /** 探测延迟换罚的除数：每 20ms 扣 1 分。 */
    const val LATENCY_DIVISOR = 20L

    /** 延迟扣分的封顶。 */
    const val LATENCY_PENALTY_CAP = 250

    /** 衰减周期：每满一个周期，各项计数**减半**（`ushr 1`）。 */
    const val DECAY_PERIOD_MS = 30L * 60L * 1_000L

    /** 最多一次折现多少个周期（防止"离线一个月回来"把计数算成 0 的假干净）。 */
    const val MAX_DECAY_PERIODS = 16

    /** 探测结果的保鲜期：超过它，延迟/速率作废（线路质量会变，旧样本不能当结论）。 */
    const val PROBE_STALE_MS = 2L * 60L * 1_000L

    /** 同一线路两次探测之间的冷却。 */
    const val PROBE_COOLDOWN_MS = 10L * 60L * 1_000L

    /**
     * 一条线路的健康度快照。全部字段可序列化（若要跨进程持久化，直接序列化本类型）。
     */
    data class Health(
        val host: String = "",
        val readyCount: Int = 0,
        val firstFrameTimeoutCount: Int = 0,
        val bufferingCount: Int = 0,
        val playbackErrorCount: Int = 0,
        /** 换线/开流**失败**次数（与"播放器报错"区分：语义更轻，2026-10-06 闭环修复新增）。 */
        val lineOpenFailureCount: Int = 0,
        val probeLatencyMs: Long? = null,
        val probeSpeedKbps: Long? = null,
        val lastProbeAtMs: Long = 0L,
        val lastUpdatedAtMs: Long = 0L,
        val windowStartedAtMs: Long = 0L
    )

    /** 会改变健康度的事件。 */
    enum class Event {
        /** 播放就绪（出画面）。 */
        PlaybackReady,

        /** 起播超时。 */
        FirstFrameTimeout,

        /** 音轨缺失（改写线路后常见）。 */
        AudioTrackMissing,

        /** 播放器报错。 */
        PlayerError,

        /** 进入缓冲。 */
        Buffering,

        /**
         * **换线/开流失败**（2026-10-06 闭环修复新增）。
         *
         * 由 CdnFailoverDataSource 与索引读取实测上报。与 PlayerError 的区别：
         * 那是"整支视频失败了"，这只是"**这条线**这次没通" ⇒ 惩罚轻、且封顶。
         */
        LineOpenFailure
    }

    /** 记一次事件。**纯函数**：返回新快照，不改原对象。 */
    fun record(health: Health, event: Event, nowMs: Long): Health = when (event) {        Event.PlaybackReady -> health.copy(
            readyCount = health.readyCount + 1,
            lastUpdatedAtMs = nowMs
        )
        Event.FirstFrameTimeout -> health.copy(
            firstFrameTimeoutCount = health.firstFrameTimeoutCount + 1,
            lastUpdatedAtMs = nowMs
        )
        // 与 BiliPai 一致：音轨缺失与播放器报错**共用**同一个计数器
        Event.AudioTrackMissing,
        Event.PlayerError -> health.copy(
            playbackErrorCount = health.playbackErrorCount + 1,
            lastUpdatedAtMs = nowMs
        )
        Event.Buffering -> health.copy(
            bufferingCount = health.bufferingCount + 1,
            lastUpdatedAtMs = nowMs
        )
          Event.LineOpenFailure -> health.copy(
              lineOpenFailureCount = health.lineOpenFailureCount + 1,
              lastUpdatedAtMs = nowMs
          )
    }

    /**
     * **会话层**该不该把这次事件算到"开播时选中的那条 host"头上。
     *
     * ## 为什么需要这条判据（2026-10-07 审查发现）
     *
     * 会话层只知道"开播选了哪条 host"，**不知道这次字节是从缓存还是从网络来的**。
     * 而字节缓存（`PlaybackMediaCache`）命中时，播放根本不会走 CDN 传输层 ——
     * 于是"播放器进入 BUFFERING"（起播热身、解码抖动、seek 都会进）会被**算到某条没出力的线头上**：
     * 真机实测 28 秒**纯缓存**重播给 `upos-sz-mirrorcosb` 记了 `bufferingCount +4`（−220 分），
     * 还让它在设置页显示"易缓冲"。命中率越高，这个偏差越频繁。
     *
     * ## 判据
     *
     * | 事件 | 有网络证据 | 无网络证据（缓存命中） | 为什么 |
     * |---|---|---|---|
     * | [Event.Buffering] | 记 | **不记** | 它是**惩罚**（−55/次，可累计），必须基于"这条线真的在服务"的证据 |
     * | [Event.FirstFrameTimeout] | 记 | **不记** | 同样是惩罚（−180/次）；缓存命中的"起播慢"是播放器/解码的事，不是线路的事 |
     * | [Event.PlaybackReady] | 记 | 记 | 它是**奖励**且封顶（`READY_CREDIT_CAP`），"选它播成功了"本身算弱证据；不记会让缓存重播完全无反馈 |
     * | 其它事件 | 记 | 记 | 都不由会话层按 host 汇总（`LineOpenFailure` 等由传输层按真实 URL 记） |
     *
     * @param networkActive 最近是否有真实网络读（由引擎的 `isNetworkActiveWithin` 提供；
     *   非 DASH 链拿不到该信号时会返回 true ⇒ 退化成"照记"，不会把记账整体关掉）。
     */
    fun shouldRecordSessionEvent(event: Event, networkActive: Boolean): Boolean = when (event) {
        // 两类"惩罚"必须有证据；奖励与传输层事件不拦。
        Event.Buffering, Event.FirstFrameTimeout -> networkActive
        else -> true
    }

    /**
     * 记一次探测结果。**替换语义**（不是累加）：每次探测覆盖延迟/速率。
     *
     * 失败时传 `latencyMs = null, speedKbps = null`：既有的探测值被**清空** ——
     * 一条刚探测失败的线路不该继续吃上次的好成绩。
     */
    fun recordProbe(
        health: Health,
        latencyMs: Long?,
        speedKbps: Long?,
        success: Boolean,
        nowMs: Long
    ): Health {
        val base = health.copy(
            probeLatencyMs = latencyMs,
            probeSpeedKbps = speedKbps,
            lastProbeAtMs = nowMs,
            lastUpdatedAtMs = nowMs
        )
        return if (success) {
            base.copy(readyCount = base.readyCount + 1)
        } else {
            base.copy(playbackErrorCount = base.playbackErrorCount + 1)
        }
    }

    /**
     * 按时间**折现**历史惩罚：每满 [DECAY_PERIOD_MS] 各项计数减半。
     *
     * 三条语义（照 BiliPai，逐条都有理由）：
     * - 折现是**一次算总账**（`periods` 一次求出再 `ushr`），不是逐周期迭代；
     * - 最多折 [MAX_DECAY_PERIODS] 个周期（32 天后再干净也是"从 0 开始"，
     *   而不是"永远算不出来"）；
     * - 探测值有自己的保鲜期（[PROBE_STALE_MS]），**与计数衰减独立**：
     *   计数是"历史口碑"、探测值是"上次实测"。
     *
     * 幂等：同一个 `nowMs` 连调两次结果相同。
     */
    fun decay(health: Health, nowMs: Long): Health {
        val baseline = health.windowStartedAtMs.takeIf { it > 0L }
            ?: health.lastUpdatedAtMs.takeIf { it > 0L }
            ?: nowMs
        val periods = ((nowMs - baseline).coerceAtLeast(0L) / DECAY_PERIOD_MS)
            .coerceAtMost(MAX_DECAY_PERIODS.toLong())
            .toInt()
        val staleProbe = health.lastProbeAtMs > 0L && nowMs - health.lastProbeAtMs > PROBE_STALE_MS
        return health.copy(
            // ushr 而不是 /：计数语义上是非负的，位运算顺带把"外部写坏成负数"
            // 这种不可能情况挡在 0 一侧（负数 ushr 会变巨大正数 —— 所以取 min 兜底）
            readyCount = (health.readyCount ushr periods).coerceIn(0, READY_CREDIT_CAP * 10),
            firstFrameTimeoutCount = (health.firstFrameTimeoutCount ushr periods).coerceAtLeast(0),
            bufferingCount = (health.bufferingCount ushr periods).coerceAtLeast(0),
            playbackErrorCount = (health.playbackErrorCount ushr periods).coerceAtLeast(0),
              lineOpenFailureCount = (health.lineOpenFailureCount ushr periods).coerceAtLeast(0),
            probeLatencyMs = if (staleProbe) null else health.probeLatencyMs,
            probeSpeedKbps = if (staleProbe) null else health.probeSpeedKbps,
            windowStartedAtMs = if (periods > 0) nowMs else baseline
        )
    }

    /**
     * 评分。**基线 1000**：没有观测的线路 = 中性，而不是垫底。
     *
     * ```
     * score = 1000 + min(speedKbps/128, 500) + 25*min(ready, 10)
     *              - min(latencyMs/20, 250) - 220*err - 180*ffto - 55*buf
     * ```
     *
     * 与 BiliPai 逐项同值（`CdnRegionPolicy.kt:398-407`），**含"没有下界钳制"这一点**：
     * 错误足够多时分数会变负 —— 那是正确行为（这条线该被换掉），钳到 0 反而会让
     * 多条烂线路并列、失去区分度。
     */
    fun score(health: Health?): Int {
        if (health == null) return BASELINE_SCORE
        val speedCredit = ((health.probeSpeedKbps ?: 0L) / SPEED_DIVISOR)
            .coerceIn(0L, SPEED_CREDIT_CAP.toLong())
            .toInt()
        val latencyPenalty = ((health.probeLatencyMs ?: 0L) / LATENCY_DIVISOR)
            .coerceIn(0L, LATENCY_PENALTY_CAP.toLong())
            .toInt()
        val readyCredit = health.readyCount.coerceIn(0, READY_CREDIT_CAP) * READY_CREDIT
        val errorPenalty = health.playbackErrorCount * PLAYER_ERROR_PENALTY +
            health.firstFrameTimeoutCount * FIRST_FRAME_TIMEOUT_PENALTY +
            health.bufferingCount * BUFFERING_PENALTY +
              health.lineOpenFailureCount.coerceIn(0, LINE_OPEN_FAILURE_CAP) * LINE_OPEN_FAILURE_PENALTY
        return BASELINE_SCORE + speedCredit + readyCredit - latencyPenalty - errorPenalty
    }

    /**
     * 给用户看的线路状态标签。判据**顺序即优先级**（重的排前面）。
     *
     * 与 BiliPai `resolveCdnHealthStatusLabel` 同表同序 —— 顺序错了会出现
     * "一条刚报错两次的线路显示『速度较好』"这种自相矛盾的结论。
     */
    fun statusLabel(health: Health?): String {
        if (health == null || health.lastUpdatedAtMs <= 0L) return "未检测"
        val errors = health.playbackErrorCount + health.firstFrameTimeoutCount
        return when {
            errors >= 2 -> "不稳定"
            health.bufferingCount >= 3 -> "易缓冲"
            (health.probeSpeedKbps ?: 0L) >= 2_048L -> "速度较好"
            (health.probeLatencyMs ?: Long.MAX_VALUE) <= 250L -> "延迟较低"
            health.readyCount > 0 -> "可用"
            else -> "已检测"
        }
    }

    /** 该线路现在是否允许探测（冷却判据）。 */
    fun canProbe(health: Health?, nowMs: Long, cooldownMs: Long = PROBE_COOLDOWN_MS): Boolean =
        probeCooldownRemainingMs(health, nowMs, cooldownMs) == 0L

    /** 距下次可探测还差多少毫秒（0 = 现在就可以）。 */
    fun probeCooldownRemainingMs(health: Health?, nowMs: Long, cooldownMs: Long = PROBE_COOLDOWN_MS): Long {
        val last = health?.lastProbeAtMs ?: 0L
        // 从未探测过（last == 0）时按"冷却是满的"处理：
        // BiliPai 直接减 0，于是 App 启动后 10 分钟内、**从未探测过的线路会被误判为冷却中**；
        // 这里显式返回 0，修掉那个怪癖。
        if (last <= 0L) return 0L
        return (cooldownMs - (nowMs - last)).coerceAtLeast(0L)
    }
}
