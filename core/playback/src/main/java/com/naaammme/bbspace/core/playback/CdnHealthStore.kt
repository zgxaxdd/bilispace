package com.naaammme.bbspace.core.playback

import com.naaammme.bbspace.core.model.player.CdnHealthPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CDN 线路健康度的**进程内**存储与回写（对照 BiliPai `CdnRegionPlugin.recordPlaybackCdnEvent`
 * + `CdnRegionPluginStore.read/write`）。
 *
 * 判定与评分全在 [CdnHealthPolicy]（纯函数、可单测）；本类只负责"存哪儿、什么时候衰减"。
 *
 * ## 与 BiliPai 的两处**有意不同**
 *
 * 1. **不落盘**。BiliPai 把健康度写进插件私有 DataStore、读盘时一次性折现。本仓先只放内存：
 *    健康度的价值要在"同一台设备、同一张网、跨会话"上才显出来，而它的正确性又依赖
 *    衰减/淘汰参数；在拿到真机跨会话样本之前把一份**可能过期或写坏**的数据持久化，
 *    风险大于收益（一旦写坏，用户会长期被导到烂线路上）。所以先内存、重启归零 ——
 *    最坏情况是"每次冷启重新学习一遍"，而不是"一直错下去"。
 * 2. **读取即衰减**：`snapshot()` 每次都按当前时间折现，调用方拿到的一定是"此刻有效"的分数。
 *
 * ## 线程安全
 *
 * `record` 来自播放状态回调（主线程），`snapshot()` 来自构建播放源（可能就是同一条链）。
 * 用 [Mutex] 而不是 `@Synchronized`：本类是挂起上下文里用的，且临界区只做一次 map 拷贝。
 */
@Singleton
class CdnHealthStore @Inject constructor(
    /** D-1（2026-10-06）：落盘需要 Context —— 之前是纯内存，冷启即归零。 */
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {

    private val mutex = Mutex()
    // **构造时同步读盘**（SharedPreferences 读取极快，且只读一次）：
    // 冷启后直接拿到"带折现的历史观测"，而不是从零开始学。
    private var healthByHost: Map<String, CdnHealthPolicy.Health> =
        CdnHealthPersistence.load(context)

    /**
     * 当前所有线路的健康度快照（**已按现在的时间折现**）。
     *
     * 返回不可变副本，调用方可以随意持有 —— 不会随后续 `record` 变化。
     */
    suspend fun snapshot(nowMs: Long = System.currentTimeMillis()): Map<String, CdnHealthPolicy.Health> =
        mutex.withLock { healthByHost.mapValues { (_, value) -> CdnHealthPolicy.decay(value, nowMs) } }

    /**
     * 记一次线路事件。
     *
     * 写前先折现（照 BiliPai 的口径）：否则"半小时前的两次报错"会和"刚才的一次报错"
     * 被同等对待，惩罚会永久累积、线路永无翻身之日。
     */
    suspend fun record(
        host: String,
        event: CdnHealthPolicy.Event,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (host.isBlank()) return
        mutex.withLock {
            val previous = CdnHealthPolicy.decay(
                healthByHost[host] ?: CdnHealthPolicy.Health(host = host),
                nowMs
            )
            healthByHost = healthByHost + (host to CdnHealthPolicy.record(previous, event, nowMs))
            // 每次记账后落盘：健康度是"跨会话学习"的全部依据，丢了就等于没学。
            CdnHealthPersistence.save(context, healthByHost)
        }
    }

    /** 清空（测试与"换账号/换网络"用）。 */
    suspend fun clear() {
        mutex.withLock {
            healthByHost = emptyMap()
            CdnHealthPersistence.save(context, emptyMap())
        }
    }
}
