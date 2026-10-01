package com.naaammme.bbspace.core.swipe.qoe

/**
 * 刷视频 QoE 的**纯换算**（任务书 §4.8 / SW-06）：事件 → 指标。
 * 环形缓冲与采集时机在 [SwipeQoeHub]；这里全部是可 JVM 单测的纯函数。
 *
 * 隐私口径（§4.8）：事件不含标题/UP/账号/设备标识；页号、耗时毫秒、
 * 来源标签（home-feed/story）这类无内容字段除外。
 */

/** 会话诊断事件（§4.8 事件表）。 */
enum class SwipeQoeEventType {
    Enter, Exit, Page, FirstFrame, PlayError, Rebuffer, Prefetch, Refill, Handoff, Gesture
}

/** 单条事件：[durationMs] 语义随类型（Prefetch=条数、Refill=净增、FirstFrame=页→帧毫秒）。 */
data class SwipeQoeEvent(
    val type: SwipeQoeEventType,
    val atMs: Long,
    val durationMs: Long? = null,
    val source: String? = null,
)

/** 一份只读快照（诊断页 / 导出文本的输入）。 */
data class SwipeQoeSnapshot(
    val events: List<SwipeQoeEvent>,
    val counters: Map<SwipeQoeEventType, Int>,
) {
    val total: Int get() = events.size
}

object SwipeQoeMath {

    /**
     * 最近秩（nearest-rank）百分位：`ceil(p/100 × N)` 第 K 个。无插值、可复现。
     * 空样本 / p 越界 → null（"样本不足"是合法输出，不许硬造 0）。
     */
    fun percentile(values: List<Long>, p: Double): Long? {
        if (values.isEmpty()) return null
        if (p !in 1.0..99.0) return null
        val sorted = values.sorted()
        val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /**
     * 首帧耗时配对：每条 [SwipeQoeEventType.FirstFrame] 与**它之前最近的一条**
     * Page/Enter 配对，差值 = 页→帧毫秒。
     *
     * 时间戳乱序/陈旧配对的防御（§8 "样本不足" 纪律）：
     * - 帧事件早于页事件（乱序）→ 不配对；
     * - 间隔超过 [MAX_PAIR_MS]（会话挂着很久才出的帧，不反映翻页手感）→ 不配对；
     * - 一条页事件只消费一次：连续两帧（解码器重复回调）里第二条找不到新页 → 丢弃。
     */
    fun firstFrameDeltas(events: List<SwipeQoeEvent>, maxPairMs: Long = MAX_PAIR_MS): List<Long> {
        var pendingPageAt: Long? = null
        val deltas = mutableListOf<Long>()
        for (event in events) {
            when (event.type) {
                SwipeQoeEventType.Page, SwipeQoeEventType.Enter -> pendingPageAt = event.atMs
                SwipeQoeEventType.FirstFrame -> {
                    val pageAt = pendingPageAt
                    pendingPageAt = null
                    if (pageAt != null) {
                        val delta = event.atMs - pageAt
                        if (delta >= 0L && delta <= maxPairMs) deltas += delta
                    }
                }
                else -> Unit
            }
        }
        return deltas
    }

    /** 翻页成功率：配对成功的首帧 / 翻页计数。无翻页 → null。 */
    fun pageSuccessRate(pageCount: Int, firstFrameCount: Int): Double? =
        if (pageCount <= 0) null else firstFrameCount.toDouble() / pageCount

    /** 导出文本（复制/落盘用）：固定字段顺序，便于脚本解析；不含任何内容字段。 */
    fun exportText(snapshot: SwipeQoeSnapshot): String = buildString {
        appendLine("qoe: export")
        appendLine("events_total=${snapshot.total}")
        SwipeQoeEventType.entries.forEach { type ->
            val count = snapshot.counters[type] ?: 0
            appendLine("count_${type.name.lowercase()}=$count")
        }
        val deltas = firstFrameDeltas(snapshot.events)
        appendLine("first_frame_samples=${deltas.size}")
        appendLine("first_frame_p50_ms=${percentile(deltas, 50.0) ?: -1}")
        appendLine("first_frame_p90_ms=${percentile(deltas, 90.0) ?: -1}")
        appendLine("first_frame_p99_ms=${percentile(deltas, 99.0) ?: -1}")
        val pages = snapshot.counters[SwipeQoeEventType.Page] ?: 0
        val frames = snapshot.counters[SwipeQoeEventType.FirstFrame] ?: 0
        appendLine(
            "page_success_rate=" + (pageSuccessRate(pages, frames)
                ?.let { "%.3f".format(it) } ?: "n/a")
        )
    }

    /** 页→帧配对的有效窗：超过它视为"不反映翻页手感"的陈旧帧。 */
    const val MAX_PAIR_MS: Long = 60_000L
}
