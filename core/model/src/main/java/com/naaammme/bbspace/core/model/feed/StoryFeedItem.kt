package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

/**
 * 「刷视频模式」（竖屏短视频流）的条目。
 *
 * 数据源：B 站官方 App 的竖屏故事流接口 `x/v2/feed/index/story`
 * （移植自 BiliPai 上游 `StoryApi.getStoryFeed` / `StoryItem`，字段按其响应结构
 * 扁平化；`goto != "av"` 的非视频卡在仓库解析层直接丢弃，不出现在本模型）。
 * 每条自带 aid/cid/bvid（可直接播放）与 cover/title/author（封面页有完整内容）。
 */
@Immutable
data class StoryFeedItem(
    val aid: Long,
    val cid: Long,
    val bvid: String,
    val title: String,
    val cover: String,
    val author: String?,
    val authorMid: Long,
    /** 时长（秒）；接口原始单位即秒。 */
    val durationSec: Long,
    /**
     * 画幅（SW-04）：story 响应卡片上的 `dimension{width,height,rotate}`，有则解析，
     * 无则 null（"未知放过"，SW-40 不猜）。
     */
    val dimension: VideoDimension? = null
)
