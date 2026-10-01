package com.naaammme.bbspace.core.playback.repository

import com.naaammme.bbspace.core.model.PlaybackRequest
import com.naaammme.bbspace.core.model.PlaybackSource

/**
 * 播放地址（playurl）**进程内 LRU**：正常开播与"相邻页预取"共用同一份缓存。
 *
 * ## 为什么要有它（对照 BiliPai）
 * BiliPai 的竖屏连播有一层 `PlayUrlCache`（`LruCache`，正常开播与预取共用），命中就省掉
 * 一整个 gRPC `PlayViewUnite` 往返 —— 这是"上滑之后画面来得快"的主要来源。
 * bbspace 此前 `fetchPlaybackSource` 是"一次请求一次响应"、没有任何缓存，翻回上一支视频
 * 也要重新取一次流。
 *
 * ## 键与过期（比 BiliPai 更保守，故意的）
 * - 键 = `aid#cid#epId#seasonId` + **影响请求内容的设置摘要**（HDR/8K、偏好编码、
 *   试看、forceHost）。设置变了就必须换键：`fnval`/`codec` 不同，响应里的流集合不同，
 *   命中错误的键会拿到"缺 8K/缺 H.265"的流表。
 * - TTL 只给 [TTL_MS]（90 秒）：响应用户观看进度回填 `resumePositionMs`，
 *   缓存太旧会让"重新打开同一支视频"从头播/回到旧进度。BiliPai 给 10 分钟，
 *   这里取"只在预取窗口内有意义"的保守值 —— 预取到真正开播通常只隔几秒。
 *
 * 纯逻辑 + 注入时钟，JVM 单测直接覆盖 LRU 与过期。
 */
class PlayUrlCache(
    private val clock: () -> Long,
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMs: Long = TTL_MS,
) {

    private data class Entry(val source: PlaybackSource, val atMs: Long)

    // accessOrder = true：get 会把条目移到队尾，淘汰最久未用的
    private val lru = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    fun get(key: String): PlaybackSource? {
        val entry = lru[key] ?: return null
        if (clock() - entry.atMs > ttlMs) {
            lru.remove(key)
            return null
        }
        return entry.source
    }

    fun put(key: String, source: PlaybackSource) {
        lru[key] = Entry(source, clock())
    }

    fun clear() {
        lru.clear()
    }

    fun size(): Int = lru.size

    companion object {
        /**
         * 容量（SW-12 调参）。
         *
         * 原值 8 与预取矩阵**不匹配**：WiFi+开关时一次预取 3 条、当前页 1 条、用户回看
         * 上一两支再各占 1 条，加上"切清晰度/开关变化"产生的第二个 flavor 键，
         * 8 会在一次快节奏连播里触顶——被淘汰的正好是"用户下一步要回去的那一支"。
         * 提到 16 覆盖"预取 3 + 回看 3 + 双 flavor 余量"；**TTL 不动**（见 [TTL_MS]）：
         * 容量与 TTL 解耦，扩容不会放大"陈旧 resume 进度"的风险（R-06）。
         */
        const val MAX_ENTRIES = 16

        /** 见类注释：比 BiliPai 的 10 分钟保守，避免陈旧 resume 进度。 */
        const val TTL_MS = 90_000L

        /**
         * 缓存键：身份 + 影响响应内容的设置摘要。
         *
         * `flavor` 由 [flavorOf] 生成；两侧（预取 / 开播）必须用同一份设置算出同一个值。
         */
        fun keyOf(request: PlaybackRequest, flavor: String): String {
            val ids = request.ids
            return buildString {
                append(ids.aid).append('#')
                append(ids.cid).append('#')
                append(ids.epId).append('#')
                append(ids.seasonId).append('#')
                append(flavor)
            }
        }

        /** 请求内容相关设置的摘要（与 [VideoPlayerRepositoryImpl.buildRequest] 读的那几项一一对应）。 */
        fun flavorOf(
            enableHdrAnd8k: Boolean,
            preferredCodec: Int,
            needTrial: Boolean,
            forceHost: Int,
        ): String = buildString {
            append("h").append(if (enableHdrAnd8k) 1 else 0)
            append("c").append(preferredCodec)
            append("t").append(if (needTrial) 1 else 0)
            append("f").append(forceHost)
        }
    }
}
