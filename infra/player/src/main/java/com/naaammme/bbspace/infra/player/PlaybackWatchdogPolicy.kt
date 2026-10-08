package com.naaammme.bbspace.infra.player

/**
 * 稳态播放的**周期性观测行**（R5 丢帧 + N2 缓存命中，2026-10-08《视频播放链路性能分析·刷视频》）。
 *
 * ## 要回答的三个问题
 *
 * 1. **"切页/滑动的时候到底丢了几帧"** —— 在此之前全仓没有任何地方能回答。Media3 把计数放在
 *    `ExoPlayer.getVideoDecoderCounters()`（`renderedOutputBufferCount` / `droppedBufferCount` /
 *    `skippedOutputBufferCount` / `maxConsecutiveDroppedBufferCount`），没人读过；
 * 2. **"切页有没有重新起解码器"** —— 判据是 `PlayWatch` 的 `swaps=`（**`DecoderCounters` 实例身份变化**）
 *    与 `DecoderReuse` 的 `result=`，**不是** `decoderInitCount` 的差分：那份计数是每个解码器实例
 *    一份、新建时 `++`，取到的实例里它恒等于 1，差分永远是 0（真机实测踩到，见
 *    [PlaybackWatchSample.decoderSwaps]）。它与首帧解剖行的 `t2` 互为印证，
 *    也正是 D1a/D1b/N1 这类改动唯一能离线立起来的验收尺；
 * 3. **"这一段时间的字节有多少来自缓存"** —— [PlaybackByteCacheMetrics] 有累计值，但只有配上
 *    同窗口的网络字节（`CdnTransferRuntime` 的计数）才是命中率，否则"切页省了多少流量/几秒"
 *    无法回答，N1 与缓存 TTL 调参也没有判据。
 *
 * ## 为什么是"窗口增量 + 限频"
 *
 * 上面三类计数都是**累计值**，直接打日志既看不出"这一段"的水位，也会把 logcat 刷屏
 * （播放中缓存字节每秒都在涨）。[PlaybackWatchdog] 因此对每个量都记"上次读数"、只报差值，
 * 并且**窗口内没有任何变化就不打**（静止且无字节流动的播放不产生日志）。
 *
 * 一个必须记住的坑：解码器**重新初始化时 `DecoderCounters` 会被清零**，所以读数可能倒退。
 * 差值一律 `coerceAtLeast(0)`，并把基线移到新读数上 —— 于是"重建解码器"之后那一行
 * 的 `dropped=0` 是**窗口内没丢帧**，不是"计数丢了"。
 *
 * 纯判据（要不要打、比例算法、格式化）在 [PlaybackWatchdogPolicy]，不依赖 Android，JVM 可逐条钉。
 */

/** 一次读数：视频解码器计数（来自 `DecoderCounters`）+ 缓存与网络字节（进程级累计）。 */
internal data class PlaybackWatchSample(
    /** 渲染出来的帧数（`renderedOutputBufferCount`）。 */
    val renderedFrames: Long,
    /** 因为"预测来不及上屏"而被丢掉的帧数（`droppedBufferCount`）。 */
    val droppedFrames: Long,
    /** 主动跳过的输出帧（seek 等，**不计入丢帧率** —— 那是设计行为不是卡顿）。 */
    val skippedFrames: Long,
    /**
     * 进程级累计的"换过解码器计数实例"的次数（差值即"本窗口新建了几个解码器"）。
     *
     * ⚠️ 这里**不能**直接用 `DecoderCounters.decoderInitCount`：它是**每个解码器实例一份**的计数
     * （`MediaCodecRenderer:1453` 在 init 时 `decoderCounters.decoderInitCount++`，而
     * `counterResultSet` 随每次新建解码器换一份新的），所以取到的那个实例里它恒等于 1 ——
     * 差分永远是 0，看起来像"从来没重建过解码器"。**真机实测踩过**（2026-10-08，PLC110：
     * 连滑 10 支每支都有 `t2` + `decoderInit=26~57ms`，而 `decInits` 一路 0）。
     * 因此引擎改按"实例身份是否变化"记账，见 `Media3PlayerEngine.reportPlaybackWatch`。
     */
    val decoderSwaps: Long,
    /** 最长连续丢帧（比平均值更能指向"卡了一下"）。 */
    val maxConsecutiveDropped: Long,
    /** 缓存读到的累计字节。 */
    val cachedBytes: Long,
    /** 真的走网络拿到的累计字节。 */
    val networkBytes: Long,
    /** 被缓存闸门跳过（`onCacheIgnored`）的累计次数。 */
    val cacheIgnored: Long,
    /** 此刻真正在供数的 CDN host，拿不到传 null。 */
    val host: String?
)

/** 稳态观测的纯判据与格式化。 */
internal object PlaybackWatchdogPolicy {

    /** 观测行的最小间隔：2 秒 = 4 个 500ms 轮询 tick。 */
    const val REPORT_INTERVAL_MS = 2_000L

    /**
     * 这一 tick 要不要打观测行。三条都必要：
     * - 没有新数据就不打 —— 静止播放不该产生日志；
     * - 从没打过时**立刻**打第一行，否则"这一段有没有丢帧"要等满一个间隔才看得到；
     * - 满间隔才打后续行，避免刷屏（也让人敢在量产包里开着现场诊断日志）。
     */
    fun shouldReport(nowMs: Long, lastReportAtMs: Long, dirty: Boolean): Boolean {
        if (!dirty) return false
        if (lastReportAtMs < 0L) return true
        return nowMs - lastReportAtMs >= REPORT_INTERVAL_MS
    }

    /**
     * 百分比（四舍五入到整数）；分母为 0 时返回 -1（**"样本不足"是合法输出，0% 不是**）。
     *
     * 用整数而不是浮点：这一行要被脚本 grep，`6%` 与 `6.25%` 混着出现要解析器两种都写。
     */
    fun percent(part: Long, total: Long): Int {
        if (total <= 0L) return -1
        return ((part * 100L + total / 2L) / total).toInt()
    }

    /** 丢帧率的分母 = 丢掉的 + 渲染出来的（"该出现的帧"）；跳过的帧不算。 */
    fun dropPercent(dropped: Long, rendered: Long): Int = percent(dropped, dropped + rendered)

    /** 缓存命中率的分母 = 走缓存的字节 + 真的走网络的字节。 */
    fun cacheHitPercent(cachedBytes: Long, networkBytes: Long): Int =
        percent(cachedBytes, cachedBytes + networkBytes)

    /** 比例的可读形式：没有样本打 `-`，而不是伪装成 0%。 */
    fun percentText(value: Int): String = if (value < 0) "-" else "$value%"

    /** 一行观测（全部是**窗口内**的增量；host 是当前值而非增量）。 */
    fun line(
        dropped: Long,
        rendered: Long,
        skipped: Long,
        decoderSwaps: Long,
        maxConsecutiveDropped: Long,
        cachedBytes: Long,
        networkBytes: Long,
        cacheIgnored: Long,
        host: String?
    ): String = buildString {
        append("play-watch dropped=").append(dropped)
        append(" rendered=").append(rendered)
        append(" drop=").append(percentText(dropPercent(dropped, rendered)))
        append(" skipped=").append(skipped)
        append(" maxConsecDropped=").append(maxConsecutiveDropped)
        append(" swaps=").append(decoderSwaps)
        append(" cached=").append(cachedBytes)
        append(" net=").append(networkBytes)
        append(" hit=").append(percentText(cacheHitPercent(cachedBytes, networkBytes)))
        append(" ignored=").append(cacheIgnored)
        append(" host=").append(host?.takeIf { it.isNotBlank() } ?: "-")
    }
}

/**
 * [PlaybackWatchdogPolicy] 的**窗口状态**（引擎持有一个实例，在 500ms 进度轮询里取行）。
 *
 * 单独一个类而不是把八个 var 摊在引擎里：这里有三条会写错的规则 —— 读数倒退要夹紧、
 * 只在有变化时出行、出行后基线必须前移。收成一个小类才能被单测逐条钉住。
 */
internal class PlaybackWatchdog(private val nowMs: () -> Long) {

    private var base: PlaybackWatchSample? = null
    private var lastReportAtMs = -1L

    /**
     * 该出行时返回那一行（并把窗口向前推进），否则返回 null。
     *
     * **第一次读数只建基线、不打行**：进程级累计值（缓存字节跨视频不清零）当作窗口增量打印，
     * 会把"上一支视频存下来的 9MB"说成"这一段读了 9MB" —— 那比不打更坏。
     * 基线建好后下一个 tick（500ms）只要有变化就会出行，第一行并不会等更久。
     *
     * 增量按**总读数取差**，而不是把每次读到的增量再累一次：这样即使某个 tick 被限频跳过，
     * 下一行仍然是完整窗口，中间的帧数与字节不会被丢掉。
     */
    fun report(sample: PlaybackWatchSample): String? {
        val previous = base
        if (previous == null) {
            base = sample
            return null
        }
        val window = windowOf(previous, sample)
        val now = nowMs()
        if (!PlaybackWatchdogPolicy.shouldReport(now, lastReportAtMs, window.hasActivity())) return null

        val line = PlaybackWatchdogPolicy.line(
            dropped = window.droppedFrames,
            rendered = window.renderedFrames,
            skipped = window.skippedFrames,
            decoderSwaps = window.decoderSwaps,
            maxConsecutiveDropped = sample.maxConsecutiveDropped,
            cachedBytes = window.cachedBytes,
            networkBytes = window.networkBytes,
            cacheIgnored = window.cacheIgnored,
            host = sample.host
        )
        lastReportAtMs = now
        base = sample
        return line
    }

    /** 窗口增量 = 两次读数之差（调用方保证已有基线，见 [report]）。 */
    private fun windowOf(previous: PlaybackWatchSample, current: PlaybackWatchSample): Diff =
        Diff(
            renderedFrames = delta(previous.renderedFrames, current.renderedFrames),
            droppedFrames = delta(previous.droppedFrames, current.droppedFrames),
            skippedFrames = delta(previous.skippedFrames, current.skippedFrames),
            decoderSwaps = delta(previous.decoderSwaps, current.decoderSwaps),
            cachedBytes = delta(previous.cachedBytes, current.cachedBytes),
            networkBytes = delta(previous.networkBytes, current.networkBytes),
            cacheIgnored = delta(previous.cacheIgnored, current.cacheIgnored)
        )

    /**
     * 取差并**夹到 0**：解码器重新初始化时 Media3 会把 `DecoderCounters` 清零，
     * 读数倒退不是"负增量"，而是"窗口从这里重新开始"。
     *
     * 正因为实例换代会让 `rendered/dropped` 从 0 重计，**换实例的那一窗口里
     * `rendered` 会偏小**（上一支视频尾巴上的帧不计入）—— 同一行里的 `swaps` 就是
     * 用来认出这种情况的，读表时先看 `swaps` 再看 `rendered`。
     */
    private fun delta(baseValue: Long, current: Long): Long =
        (current - baseValue).coerceAtLeast(0L)

    private data class Diff(
        val renderedFrames: Long,
        val droppedFrames: Long,
        val skippedFrames: Long,
        val decoderSwaps: Long,
        val cachedBytes: Long,
        val networkBytes: Long,
        val cacheIgnored: Long
    ) {
        /** 这一窗口里有没有任何一件事在动（全 0 就是"静止播放"，不打行）。 */
        fun hasActivity(): Boolean =
            renderedFrames > 0L || droppedFrames > 0L || skippedFrames > 0L ||
                decoderSwaps > 0L || cachedBytes > 0L || networkBytes > 0L || cacheIgnored > 0L
    }
}
