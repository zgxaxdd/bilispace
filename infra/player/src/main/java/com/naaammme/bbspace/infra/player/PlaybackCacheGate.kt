package com.naaammme.bbspace.infra.player

/**
 * PB-03 的**缓存闸门**（纯函数，JVM 可测）：什么样的播放源才该包字节缓存。
 *
 * ## 为什么不是一个"看 URL scheme"的判据
 * 任务书给的口径是"`LocalMerged` 是 `file://` → 只缓存 http/https"。实现时发现
 * **media3 1.11 的 `CacheKeyFactory.buildCacheKey` 返回非空 `String`**（没有"返回 null
 * 即绕过缓存"这条路），所以闸门只能落在"**包不包** `CacheDataSource`"这一层。
 * 落在这里还有个额外好处：判据从"URL 长什么样"升级为"这是哪种播放源"，
 * 顺手把**直播**这一档也挡在外面：
 *
 * | 源 | 缓存 | 为什么 |
 * |---|---|---|
 * | `SingleFileDash`（VOD DASH） | ✅ | 主要收益来源：回看/拖回退不再重下 |
 * | `Progressive`（VOD 单文件） | ✅ | 同上 |
 * | `LiveFlv`（直播） | ❌ **刻意不缓存** | 直播是**流式边缘**：缓存下来的是"那一刻"的字节，回看直播只会拿到过期分片；而直播本来也不会被回看。缓存它只有坏处（占盘 + 可能喂旧数据） |
 * | `LocalMerged`（`file://` 离线合并） | ❌ | 读的是本地文件，没有"缓存"语义；且任务书明确要求 |
 *
 * 上游只缓存 VOD（其 `PlaybackMediaCache` 挂在点播链上），本仓按"源的种类"表达同一件事。
 */
internal object PlaybackCacheGate {

    /** 播放源的缓存意图。 */
    enum class Kind {
        VOD_DASH,
        VOD_PROGRESSIVE,
        LIVE,
        LOCAL_FILE
    }

    /** 只有**点播**才进字节缓存。 */
    fun shouldCache(kind: Kind): Boolean = when (kind) {
        Kind.VOD_DASH, Kind.VOD_PROGRESSIVE -> true
        Kind.LIVE, Kind.LOCAL_FILE -> false
    }

    /** 由引擎源类型映射到缓存意图（唯一映射点，避免各处自己判 `is`）。 */
    fun kindOf(source: EngineSource): Kind = when (source) {
        is EngineSource.SingleFileDash -> Kind.VOD_DASH
        is EngineSource.Progressive -> Kind.VOD_PROGRESSIVE
        is EngineSource.LiveFlv -> Kind.LIVE
        is EngineSource.LocalMerged -> Kind.LOCAL_FILE
    }

    /** URL 方案闸门（防御性二次确认）：非 http/https 一律不进缓存。 */
    fun isCacheableUrl(url: String): Boolean {
        val lowered = url.lowercase()
        return lowered.startsWith("http://") || lowered.startsWith("https://")
    }
}
