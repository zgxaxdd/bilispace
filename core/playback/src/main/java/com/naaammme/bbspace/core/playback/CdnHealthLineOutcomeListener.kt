package com.naaammme.bbspace.core.playback

import com.naaammme.bbspace.core.model.player.CdnHealthPolicy
import com.naaammme.bbspace.core.model.player.CdnSmartSelectionPolicy
import com.naaammme.bbspace.infra.player.CdnLineOutcomeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把传输层的**真实结果**记进健康度（2026-10-06 闭环修复的记账端）。
 *
 * ## 为什么必须由 `core:playback` 实现
 *
 * 只是依赖方向问题：换线发生在 `infra:player` 的 `CdnFailoverDataSource` 内部，
 * 而健康度存储在 `core:playback`。让 infra 反向依赖 playback 是错的，
 * 所以 `infra:player` 只定义回调口（[CdnLineOutcomeListener]），记账放在这里。
 *
 * ## 记什么、为什么这么记
 *
 * - **成功** → [CdnHealthPolicy.Event.PlaybackReady]（+25 分，且评分里有
 *   `READY_CREDIT_CAP` 上限 ⇒ 同一条线被反复上报也不会刷爆分数）；
 * - **失败** → [CdnHealthPolicy.Event.LineOpenFailure]（−40 分，且有计数上限）。
 *   **刻意不用 `PlayerError`（−220）**：换线是逐条试的，一次播放可能同时失败好几条，
 *   用重惩罚会把所有线路一起打到负分，选线就废了。
 *
 * ## 为什么报告"每条线"而不是"选中的那条"
 *
 * 换线之后就**不是**当初选中的那条在服务了 —— 只记"选中的那条"正是原先闭环断开的地方：
 * 坏线路永远不被降权，好线路永远不被加分。这里用 URL 里真实的 host 记账。
 */
@Singleton
class CdnHealthLineOutcomeListener @Inject constructor(
    private val cdnHealthStore: CdnHealthStore
) : CdnLineOutcomeListener {

    /**
     * 记账是 suspend 的（存储内部有互斥锁），而回调口必须在播放线程上**不阻塞**地返回，
     * 所以丢到一个应用级作用域里做。这个实例本身是单例，作用域与它同生命周期。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onOutcome(url: String, succeeded: Boolean) {
        val host = CdnSmartSelectionPolicy.hostOf(url)
        if (host.isBlank()) return
        scope.launch {
            runCatching {
                cdnHealthStore.record(
                    host = host,
                    event = if (succeeded) {
                        CdnHealthPolicy.Event.PlaybackReady
                    } else {
                        CdnHealthPolicy.Event.LineOpenFailure
                    }
                )
            }
        }
    }
}
