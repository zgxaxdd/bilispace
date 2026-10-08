package com.naaammme.bbspace.infra.player

/**
 * 一次起播的**首帧解剖时间线**（N4，2026-10-08《视频播放链路性能分析·刷视频》§2.5-N4）。
 *
 * ## 为什么需要它
 *
 * 引擎本来就有两个端点：`setSource` 记 [Media3PlayerEngine] 的 `playbackStartAtMs`、
 * `onRenderedFirstFrame` 记出帧时刻。但**中间过程没有任何分解** —— 于是"这一页首帧 800ms"
 * 到底花在"取索引（网络）"、"初始化解码器"、还是"等 surface"上，静态与运行时都看不出来。
 * 缺这把尺子，切页相关的每一项优化（解码器复用、视图池、`PreloadManager`）
 * 都无法归因，也就无法验收（仓库纪律：不得在无基线数据时宣称提升）。
 *
 * ## 五个点
 *
 * | 点 | 语义 | 信号源 |
 * |---|---|---|
 * | t0 | 媒体源交给引擎（与起播计时同刻） | `setSource` |
 * | t1 | 媒体源建好并交给播放器（含 DASH 索引读、多线路重试） | `buildMediaSource` + `setMediaSource` |
 * | t2 | 视频解码器初始化完成 | `AnalyticsListener.onVideoDecoderInitialized` |
 * | t3 | 输出 surface 有了非零尺寸 | `AnalyticsListener.onSurfaceSizeChanged` |
 * | t4 | 第一帧真正渲染 | `AnalyticsListener.onRenderedFirstFrame` |
 *
 * 三点判据说明：
 * - **t3 是代理指标**：Media3 没有"surface 就绪"回调，`onSurfaceSizeChanged` 是它给出的
 *   最近似事件（surface 尺寸确定）。它可能早于 t2（视图先挂上），所以**不假设单调**。
 * - **t2 缺失 + t4 存在 ⇒ 解码器被复用**（`onVideoDecoderInitialized` 只在**初始化**时回调，
 *   复用已启动的解码器不会再报）。这正是"切页要不要 stop()"这类问题的**可证伪判据**，
 *   比任何静态推导都硬 —— 本类刻意把 `initializationDurationMs` 也留着（原来这个参数
 *   在引擎里被接收后直接丢弃）。
 * - **索引是否走网络**记在 t1 上（`index=NET|CACHE`）：t0→t1 这一段是"取流 + 建源"，
 *   只有带了这个标记才能判断这一段的长短是不是线路问题。
 *
 * ## 输出形态
 *
 * 一行 `key=value`，缺失的点写 `-`，差值全部相对 t0（绝对时刻另给一个，便于与
 * `PlayerSurface` / `CdnRoutes` / `PlaybackMediaCache` 那些日志对齐时间轴）：
 *
 * ```
 * first-frame kind=SingleFileDash total=412 t0abs=98213 t1=118 t2=260 t3=275 t4=412 index=NET codec=OMX.google.avc.decoder decoderInit=97
 * ```
 *
 * 时钟注入（[nowMs]）而不是直读 `SystemClock`，因此阶段推进与格式化都能在 JVM 上确定性测。
 */
internal class FirstFrameTimeline(private val nowMs: () -> Long) {

    /** 时间线序号：每次 [begin] 自增，用于识别"这行属于第几次开播"。 */
    var generation = 0L
        private set

    private var sourceKind = ""
    private var t0Ms = -1L
    private var t1Ms = -1L
    private var t2Ms = -1L
    private var t3Ms = -1L
    private var t4Ms = -1L
    private var decoderName: String? = null
    private var decoderInitDurationMs = -1L
    private var indexFromNetwork: Boolean? = null
    private var completeLineTaken = false
    private var partialLineTaken = false

    /** t0：一次新的开播（换源/重建都会走到这里，时间线随之归零）。 */
    fun begin(sourceKind: String) {
        generation += 1L
        this.sourceKind = sourceKind
        t0Ms = nowMs()
        t1Ms = -1L
        t2Ms = -1L
        t3Ms = -1L
        t4Ms = -1L
        decoderName = null
        decoderInitDurationMs = -1L
        indexFromNetwork = null
        completeLineTaken = false
        partialLineTaken = false
    }

    /** t1：媒体源建好并交给播放器。[indexFromNetwork] = DASH 索引真的走了网络（非 DASH 传 null）。 */
    fun sourceReady(indexFromNetwork: Boolean?) {
        this.indexFromNetwork = indexFromNetwork
        if (t0Ms > 0L && t1Ms < 0L) t1Ms = nowMs()
    }

    /** t2：视频解码器初始化完成（复用已启动的解码器**不会**回调这里）。 */
    fun decoderInitialized(decoderName: String, initializationDurationMs: Long) {
        this.decoderName = decoderName
        this.decoderInitDurationMs = initializationDurationMs
        if (t0Ms > 0L && t2Ms < 0L) t2Ms = nowMs()
    }

    /**
     * t3：surface 有了非零尺寸。
     *
     * 只认第一次非零：Media3 用 `(0,0)` 表示"surface 被摘掉"，换绑/全屏往返都会再报一次，
     * 那些都不算"这一次起播的 surface 就绪"。
     */
    fun surfaceReady(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (t0Ms > 0L && t3Ms < 0L) t3Ms = nowMs()
    }

    /** t4：第一帧渲染完成。 */
    fun firstFrameRendered() {
        if (t0Ms > 0L && t4Ms < 0L) t4Ms = nowMs()
    }

    /**
     * 还没有 setSource（引擎刚建好、或已经被释放）时不出行。
     *
     * 哨兵口径与 [FirstFrameTimeoutPolicy] 的 `startedAtMs <= 0` 保持一致：`t0 <= 0` 一律当成
     * "还没开播"，而不是"第 0 毫秒开播" —— 两处必须同一口径，否则同一次起播会出现
     * "超时判据说没开播、解剖行却照打"的分歧。
     */
    val isActive: Boolean get() = t0Ms > 0L

    /**
     * 完整解剖行（在 t4 到达时取）。同一次开播只给一次，之后返回 null ——
     * 出帧后 Media3 还可能再报 `onRenderedFirstFrame`（如重建 surface 后的第一帧），
     * 那些不该变成第二行。
     */
    fun completeLine(): String? {
        if (!isActive || t4Ms < 0L || completeLineTaken) return null
        completeLineTaken = true
        return format(reason = null)
    }

    /**
     * 残缺解剖行（起播超时这类"还没出帧"的场景取），用于回答"卡在哪一段"。
     *
     * 与 [completeLine] **互不消耗**：超时之后真的出帧了，仍然会出完整行 ——
     * "先报 10 秒还没画面、再报最终 12 秒花在哪"两条信息都要。
     */
    fun partialLine(reason: String): String? {
        if (!isActive || partialLineTaken) return null
        partialLineTaken = true
        return format(reason)
    }

    private fun format(reason: String?): String =
        buildString {
            append("first-frame kind=").append(sourceKind.ifBlank { "-" })
            append(" gen=").append(generation)
            append(" total=").append(delta(t4Ms))
            append(reasonPart(reason))
            append(" t0abs=").append(t0Ms)
            append(" t1=").append(delta(t1Ms))
            append(" t2=").append(delta(t2Ms))
            append(" t3=").append(delta(t3Ms))
            append(" t4=").append(delta(t4Ms))
            append(" index=").append(indexFlag())
            append(" codec=").append(decoderName ?: "-")
            append(" decoderInit=").append(if (decoderInitDurationMs >= 0L) decoderInitDurationMs.toString() else "-")
        }

    private fun reasonPart(reason: String?): String =
        if (reason == null) "" else " reason=$reason"

    /** 相对 t0 的毫秒差；那个点没发生过就写 `-`。 */
    private fun delta(atMs: Long): String = if (atMs < 0L) "-" else (atMs - t0Ms).toString()

    /**
     * 索引读取的网络证据：`NET` / `CACHE` / `-`。
     *
     * `-` 有两种含义（非 DASH 源、或建源还没走到这一步），刻意**不**把它们合成一个值：
     * 把"没有这个信息"和"信息是未命中"分清楚，是这行日志能被机器读的前提。
     */
    private fun indexFlag(): String = when (indexFromNetwork) {
        true -> "NET"
        false -> "CACHE"
        null -> "-"
    }
}
