package com.naaammme.bbspace.core.swipe.qoe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 刷视频 QoE 采集枢纽（任务书 §4.8 / SW-06）。
 *
 * - **进程级单例**（Kotlin object，无 Hilt）：会话跨 VM 存活，设置诊断页与
 *   采集点（VideoViewModel / pager）从任何位置可达；
 * - **内存环形缓冲 N=500**：满了丢最旧；不落盘、不联网、无第三方 SDK；
 * - **不采集内容**：调用方只给类型 / 耗时 / 来源标签（home-feed、story），
 *   标题、UP、账号、aid 一律不进这里；
 * - 指标换算全部在 [SwipeQoeMath]（纯函数、单测覆盖）；本类只管"记 + 存"。
 */
object SwipeQoeHub {

    /** 环形缓冲容量（§4.8：N=500）。 */
    const val CAPACITY = 500

    private val lock = Any()
    private val buffer = ArrayDeque<SwipeQoeEvent>(CAPACITY)
    private val counterMap = HashMap<SwipeQoeEventType, Int>()

    /** 具名计数器（SW-12 起用：playurl 命中/未命中等"次数"类指标，不是事件）。 */
    private val namedCounters = HashMap<String, Long>()

    private val versionFlow = MutableStateFlow(0)

    /** 版本号：每次记录 +1，诊断页订阅它做轻量刷新（不订阅大列表）。 */
    val version: StateFlow<Int> = versionFlow.asStateFlow()

    /**
     * 记录一条事件。线程安全（翻页回调 / 协程 / 主线程都可能到达）。
     * @param durationMs Prefetch=条数、Refill=净增、FirstFrame=页→帧毫秒（语义见事件类）。
     */
    fun record(
        type: SwipeQoeEventType,
        durationMs: Long? = null,
        source: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(SwipeQoeEvent(type = type, atMs = nowMs, durationMs = durationMs, source = source))
            counterMap[type] = (counterMap[type] ?: 0) + 1
            versionFlow.value += 1
        }
    }

    /** 当前快照（深拷贝，页面拿走后采集继续不受影响）。 */
    fun snapshot(): SwipeQoeSnapshot = synchronized(lock) {
        SwipeQoeSnapshot(
            events = buffer.toList(),
            counters = counterMap.toMap(),
            namedCounters = namedCounters.toMap()
        )
    }

    /**
     * 具名计数器累加（SW-12）：给"次数"类指标用（playurl 命中/未命中、预取取消次数…）。
     * 与事件分离——这类指标高频、不需要时间线。
     */
    fun recordCounter(name: String, delta: Long = 1L) {
        synchronized(lock) {
            namedCounters[name] = (namedCounters[name] ?: 0L) + delta
            versionFlow.value += 1
        }
    }

    /** 清空（诊断页的"清空"按钮；与 ErrorLog 同款交互）。 */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            counterMap.clear()
            namedCounters.clear()
            versionFlow.value += 1
        }
    }
}
