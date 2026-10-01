package com.naaammme.bbspace.core.swipe.source

import com.naaammme.bbspace.core.swipe.SwipeEntry

/**
 * 多源编排器（任务书 §4.7 / SW-05）：**纯判据在这里，网络在源实现里**。
 *
 * 规则：
 * ① 源按 **cost 升序**消费（便宜的先问；同 cost 按 diversity 降序，多样的先问）；
 * ② 蜂窝（计费网络）下剔除 [SwipeSourceCost.allowedOnCellular] 为 false 的源；
 * ③ 去重统一在队列合并层（控制器 `appendSwipeEntries`），编排层**不**自行去重；
 * ④ 跨会话已推记忆（[com.naaammme.bbspace.core.swipe.queue.SwipeSeenMemory]）在
 *    VM 喂数据前过滤，编排层同样不管——分层各管一件事。
 */
class SwipeSourceRegistry(private val sources: List<SwipeSource>) {

    /**
     * 当前网络下可用的源，消费顺序（cost 升序 → diversity 降序）。
     * 纯函数：编排顺序的可测核心。
     */
    fun available(isUnmetered: Boolean): List<SwipeSource> = sources
        .filter { isUnmetered || it.cost.allowedOnCellular }
        .sortedWith(compareBy({ it.cost }, { -it.diversity }))

    /**
     * 按消费顺序问源，直到拿到一个**非空批**或全部问完。
     * 只取"第一批有货的"就返回——更深的接力由调用方拿着游标再进来问
     * （补货是分页驱动、一页一问的节奏，这里不替调用方拉满）。
     */
    suspend fun nextBatch(
        cursor: SwipeCursor,
        limit: Int,
        isUnmetered: Boolean,
    ): SwipeSourceBatch? {
        for (source in available(isUnmetered)) {
            val batch = source.next(cursor, limit)
            if (batch != null && !batch.isEmpty) return batch
        }
        return null
    }

    companion object {
        /** 补货一轮的净增目标（与 SwipeRefillPolicy 的 SWIPE_REFILL_TARGET 同口径）。 */
        const val REFILL_LIMIT = 8
    }
}
