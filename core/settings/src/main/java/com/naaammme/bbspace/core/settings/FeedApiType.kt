package com.naaammme.bbspace.core.settings

/**
 * 首页推荐来源（PT-10，语义移植自上游 BiliPai `SettingsManager.kt:5675-5683`）。
 *
 * | 档 | 取流协议 | 说明 |
 * |---|---|---|
 * | [WEB] | `x/web-interface/wbi/index/top/feed/rcmd`（WBI 签名） | 网页端算法；**只返回 UGC 视频**，且卡片**没有"不感兴趣"上下文** |
 * | [MOBILE] | `x/v2/feed/index`（appkey+sign） | **默认 = 现状**（bbspace 一直是这条） |
 * | [MERGED] | 双源并行交错合并 | 单边失败返回存活边 |
 *
 * ⚠️ **与上游的差异**：上游默认 `WEB`；bbspace 默认 [MOBILE]（维持现状），
 * 且 [fromValue] 的兜底同样是 [MOBILE]——脏值/缺省都要落在"维持现状"上。
 */
enum class FeedApiType(val value: Int) {
    WEB(0),

    /** 默认档：与 bbspace 既有行为逐字一致。 */
    MOBILE(1),

    MERGED(2);

    companion object {
        /** 缺省/脏值一律兜底 [MOBILE]（默认行为不变的红线）。 */
        fun fromValue(value: Int): FeedApiType = entries.find { it.value == value } ?: MOBILE
    }
}
