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
    val copyright: Int? = null,
    /**
     * 主稿件时长（秒）。viewunite 链路（`viewunite.v1.Arc.duration`）有值。
     * 注意：任务书 §1.4#6 说"主稿件尺寸在 view.proto:1171"，那是**旧 API**
     * （`bilibili.app.view.v1.ViewReply`）的 proto——本仓详情管线用的是
     * `viewunite.v1.ViewReply`，其 `Arc` **没有** dimension 字段。
     */
    val durationSec: Long? = null,
    /**
     * 主稿件画幅。viewunite 链路**取不到**（见 [durationSec] 注释），当前恒为
     * null——由 SW-40 播放后的 currentStream 回填，详情解析层不猜。
     */
    val dimension: VideoDimension? = null
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

/**
 * 视频画幅（宽/高/旋转角）。SW-04 起"proto 里已有、没解析"的 dimension 接出来：
 * - relate 卡：`viewunite.common.RelateAVCard.dimension`（width/height/rotate，int64）；
 * - 主稿件：`view.v1.ViewReply.dimension`（`archive.v1.Dimension`，同形状）。
 *
 * 首页 App 流（feed 源）是否携带尺寸未核实（需真机 dump 响应）——拿不到就留 null，
 * 走"未知放过"的过滤策略（SW-40），不猜。
 */
@Immutable
data class VideoDimension(
    val width: Int,
    val height: Int,
    val rotate: Int
) {
    /** 宽高比（宽/高）；高为 0 时返回 null。竖屏 = 比值 < 1（SW-40 的判据输入）。 */
    val aspectRatio: Float?
        get() = if (height > 0 && width > 0) width.toFloat() / height else null

    companion object {
        /**
         * proto 解析的统一兜底入口：宽/高非正（proto 缺省为 0，或数据异常）→ null，
         * 调用方一律走"未知放过"，不猜不拦。
         */
        fun from(width: Long, height: Long, rotate: Long): VideoDimension? {
            if (width <= 0L || height <= 0L) return null
            return VideoDimension(
                width = width.coerceAtMost(MAX_DIMENSION).toInt(),
                height = height.coerceAtMost(MAX_DIMENSION).toInt(),
                rotate = rotate.toInt()
            )
        }

        /** 防御上限：正常画幅宽高不会超过这个量级（int64 原始字段不设防会溢出 Int）。 */
        private const val MAX_DIMENSION = 100_000L
    }
}

@Immutable
data class VideoRelate(
    val target: VideoTarget.Ugc,
    val title: String,
    val cover: String,
    val author: String?,
    val durationText: String?,
    val viewText: String?,
    val danmakuText: String?,
    val reason: String?,
    /** 原始秒数（proto `RelateAVCard.duration`，int64 秒）；<= 0 / 缺失为 null。 */
    val durationSec: Long? = null,
    /** 画幅（proto `RelateAVCard.dimension`）；缺失或宽高非法为 null。 */
    val dimension: VideoDimension? = null
)
