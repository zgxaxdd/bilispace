package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class VideoDetail(
    val title: String,
    val cover: String? = null,
    val owner: VideoOwner? = null,
    val stat: VideoStat? = null,
    val pubTs: Long? = null,
    val tags: List<String> = emptyList(),
    val desc: String = "",
    val staffs: List<VideoStaff> = emptyList(),
    val season: VideoSeason? = null,
    val pages: List<VideoPagePart> = emptyList(),
    val relates: List<VideoRelate> = emptyList(),
    val userState: VideoUserState? = null,
    val copyright: Int? = null
)

@Immutable
data class VideoDetailResult(
    val detail: VideoDetail,
    val ids: ResolvedVideoIds,
    val biz: PlayBiz
)

@Immutable
data class VideoOwner(
    val mid: Long,
    val name: String,
    val fansText: String?,
    val arcCountText: String?,
    val face: String?
)

@Immutable
data class VideoStat(
    val view: String,
    val danmaku: String,
    val reply: String,
    val like: String,
    val coin: String,
    val fav: String,
    val share: String,
    val likeValue: Long = 0,
    val coinValue: Long = 0,
    val favValue: Long = 0,
    val shareValue: Long = 0
)

/**
 * 当前登录用户对该视频的互动状态，来自 gRPC 详情响应的 req_user
 */
@Immutable
data class VideoUserState(
    val liked: Boolean = false,
    val coinCount: Int = 0,
    val faved: Boolean = false
)

@Immutable
data class VideoStaff(
    val role: String,
    val name: String
)

@Immutable
data class VideoSeason(
    val title: String,
    val subTitle: String?,
    val sections: List<VideoSeasonSection>
)

@Immutable
data class VideoSeasonSection(
    val title: String,
    val eps: List<VideoSeasonEpisode>
)

@Immutable
data class VideoSeasonEpisode(
    val target: VideoTarget,
    val cid: Long,
    val title: String,
    val subTitle: String?,
    val cover: String?
)

@Immutable
data class VideoPagePart(
    val cid: Long,
    val part: String,
    val durationSec: Long
)

@Immutable
data class VideoRelate(
    val target: VideoTarget.Ugc,
    val title: String,
    val cover: String,
    val author: String?,
    val durationText: String?,
    val viewText: String?,
    val danmakuText: String?,
    val reason: String?
)
