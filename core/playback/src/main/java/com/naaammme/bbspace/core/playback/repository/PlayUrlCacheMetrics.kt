package com.naaammme.bbspace.core.playback.repository

import java.util.concurrent.atomic.AtomicLong

/**
 * playurl 缓存的**进程级计数与出口**（SW-12）：命中率要能在"刷视频诊断"页读到，
 * 才能回答"预取到底有没有省掉往返"（任务书 §6 的门禁口径）。
 *
 * 设计取舍：
 * - 计数器是进程级 object（与 `SwipeQoeHub` 同款）：调用方（仓库实现）不必为指标
 *   多接一条依赖；纯逻辑类 [PlayUrlCache] 保持无全局耦合（它不碰这里）。
 * - 监听器只有一个（诊断适配器）：避免多播后重复计数；未注册时静默。
 */
object PlayUrlCacheMetrics {

    private val hitCount = AtomicLong()
    private val missCount = AtomicLong()

    @Volatile
    private var listener: ((hit: Boolean) -> Unit)? = null

    /** 注册/替换出口（feature 层把命中转发进 QoE 计数）；传 null 解除。 */
    fun setListener(value: ((hit: Boolean) -> Unit)?) {
        listener = value
    }

    /** 仓库在每次查缓存后调用一次（命中/未命中各一次）。 */
    fun record(hit: Boolean) {
        if (hit) hitCount.incrementAndGet() else missCount.incrementAndGet()
        listener?.invoke(hit)
    }

    fun hits(): Long = hitCount.get()

    fun misses(): Long = missCount.get()

    /** 命中率；无样本返回 null（"样本不足"是合法输出）。 */
    fun hitRate(): Double? {
        val total = hits() + misses()
        return if (total <= 0L) null else hits().toDouble() / total
    }

    fun reset() {
        hitCount.set(0L)
        missCount.set(0L)
    }
}
