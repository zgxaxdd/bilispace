package com.naaammme.bbspace.infra.player

import java.util.concurrent.atomic.AtomicLong

/**
 * 播放字节缓存（PB-03 的 128MiB `SimpleCache`）的**进程级读数**（N2）。
 *
 * ## 为什么需要它
 *
 * 缓存接上了，但**命中率本身没有埋点**：`readPrefixThroughCache` 只打 HIT/MISS 的一次性日志，
 * `PlayUrlCacheMetrics` 记的是 playurl（地址）那层。于是"切页省了多少流量、省了几秒"
 * 在生产上无法回答，`PreloadManager`（N1）与 TTL 调参也就没有判据 —— 只能靠猜。
 *
 * ## 口径
 *
 * 数据源是 Media3 `CacheDataSource.EventListener`：
 * - `onCachedBytesRead(cacheSizeBytes, cachedBytesRead)` —— 第二个参数是**自上次回调以来**
 *   从缓存读到的字节（`notifyBytesRead()` 在通知后把内部计数清零），所以这里是**累加**而不是赋值；
 * - `onCacheIgnored(reason)` —— 这次读取压根没走缓存（例如 `LENGTH_UNSET` 的流式请求）。
 *   单独计数：它和"读了但没命中"是两件事，混在一起会把"闸门关掉了缓存"误读成"缓存没用"。
 *
 * 与 [PlayUrlCacheMetrics] 同款的**进程级 object**：调用方（数据源工厂）不必为指标多接一条依赖。
 * 计数器只增，`reset()` 只给测试与"诊断页清零"用。
 *
 * 线程：`CacheDataSource` 的回调发生在播放线程/IO 线程上，因此用 `AtomicLong`。
 */
object PlaybackByteCacheMetrics {

    private val cachedBytesRead = AtomicLong()
    private val cacheIgnoredCount = AtomicLong()

    /** `CacheDataSource` 报告"这段时间从缓存读到了多少字节"。 */
    fun recordCachedBytesRead(bytes: Long) {
        if (bytes > 0L) cachedBytesRead.addAndGet(bytes)
    }

    /** `CacheDataSource` 报告"这次读取被缓存闸门跳过"。 */
    fun recordCacheIgnored() {
        cacheIgnoredCount.incrementAndGet()
    }

    fun cachedBytes(): Long = cachedBytesRead.get()

    fun ignoredCount(): Long = cacheIgnoredCount.get()

    fun reset() {
        cachedBytesRead.set(0L)
        cacheIgnoredCount.set(0L)
    }
}
