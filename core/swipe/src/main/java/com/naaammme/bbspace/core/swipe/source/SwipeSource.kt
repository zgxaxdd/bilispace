package com.naaammme.bbspace.core.swipe.source

import com.naaammme.bbspace.core.swipe.SwipeEntry

/**
 * 多源编排在**会话阶段**的游标：`idx` 是推荐流分页游标（fetchFeed 的 idx），
 * `sourceId` 记录游标属于哪个源——不同源的分页空间互不相通。
 *
 * [webPage]（SX-04）是**第二套分页空间**：web 协议（`/x/web-interface/wbi/…/rcmd`）
 * 不向客户端下发任何游标，只能靠本地页序号翻页。推荐来源可切 WEB/MERGED（决策 D-1，
 * 刷视频接力跟随"推荐来源"设置）之后，同一个源必须**同时**持有两套游标，
 * 换算与推进规则见 `HomeFeedSwipeSource` 的 `SwipeFeedCursorPolicy`。
 * 只走 app 协议的调用点可以忽略本字段（默认 0）。
 */
data class SwipeCursor(
    val sourceId: String? = null,
    val idx: Long = 0L,
    val webPage: Int = 0,
)

/** 流量成本档位：决定蜂窝（计费网络）下该源是否可用。 */
enum class SwipeSourceCost {
    /** 本地内容（离线/下载），零流量。 */
    Offline,

    /** 轻接口（推荐流分页这类单请求 JSON/gRPC）。 */
    Light,

    /** 重接口（整页详情预热、大列表兜底）。 */
    Heavy;

    /** 蜂窝下是否允许使用（SW-05 编排矩阵的纯判据）。 */
    val allowedOnCellular: Boolean
        get() = this != Heavy
}

/**
 * 刷视频队列的一个**数据源**（任务书 §4.7 / SW-05）。
 *
 * 实现约束：
 * - `next` 只负责"按游标取一批候选"，**不做去重**（去重统一在队列合并
 *   `appendSwipeEntries` / 会话控制器——源内各自去重会让编排层失去全局视野）；
 * - 失败返回空批，不抛异常（源是尽力而为的；编排层按空批跳过）；
 * - 返回的条目应尽量带全 [com.naaammme.bbspace.core.swipe.SwipeEntry] 的
 *   补全字段（ownerMid/durationSec/dimension），SW-04 的三源映射结论在实现处落地。
 */
interface SwipeSource {
    /** 稳定标识（home-feed / story / relates / watch-later / offline…），同时是游标命名空间。 */
    val id: String

    /** 流量成本（决定蜂窝可用性）。 */
    val cost: SwipeSourceCost

    /** 多样性权重（0..1）。1 = 独立推荐流；relate 类派生流取低值。编排排序的次级键。 */
    val diversity: Float

    /**
     * 取下一批候选。
     * @param cursor 上一次的游标（首次用 [SwipeCursor] 默认值）。
     * @param limit 期望条数（源可忽略；编排层自己数"净增"）。
     * @return 批次（条目 + 新游标）；空批表示该源暂时无货。
     */
    suspend fun next(cursor: SwipeCursor, limit: Int): SwipeSourceBatch
}

/** 一个源的一次取货结果。 */
data class SwipeSourceBatch(
    val sourceId: String,
    val entries: List<SwipeEntry>,
    /** 续读游标；null = 该源到头了（编排层不再回来问它）。 */
    val nextCursor: SwipeCursor?,
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}
