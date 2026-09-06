package com.naaammme.bbspace.navigation

import android.content.Context
import android.content.Intent
import com.naaammme.bbspace.core.model.VideoSrc
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.video.VideoDetailActivity

/** Intent extra：封面原始 URL（详情页垫底图用）。 */
const val EXTRA_COVER_URL = "bbspace.extra.VIDEO_DETAIL_COVER_URL"

/** Intent extra：封面是否单列大图变体（垫底图需命中同一张缓存位图）。 */
const val EXTRA_COVER_LARGE = "bbspace.extra.VIDEO_DETAIL_COVER_LARGE"

/** Intent extra：本次打开是否注册了无缝动画（决定返回动画的等待策略）。 */
const val EXTRA_FROM_SEAMLESS = "bbspace.extra.VIDEO_DETAIL_FROM_SEAMLESS"

/**
 * [VideoTarget] 与 Intent extra 的双向映射。
 *
 * VideoTarget 是纯值类型（sealed + data class），直接平铺进 extra：
 * kind 区分三个子类，公共字段 aid/cid 独立命名，可空字段仅在有值时写入，
 * VideoSrc 四字段全部带上（播放上报依赖 from/fromSpmid）。
 */
object VideoTargetSerializer {
    private const val EXTRA_KIND = "bbspace.extra.TARGET_KIND"
    private const val KIND_UGC = "ugc"
    private const val KIND_PGC = "pgc"
    private const val KIND_PUGV = "pugv"

    private const val EXTRA_AID = "bbspace.extra.TARGET_AID"
    private const val EXTRA_CID = "bbspace.extra.TARGET_CID"
    private const val EXTRA_BVID = "bbspace.extra.TARGET_BVID"
    private const val EXTRA_EP_ID = "bbspace.extra.TARGET_EP_ID"
    private const val EXTRA_SEASON_ID = "bbspace.extra.TARGET_SEASON_ID"
    private const val EXTRA_SUB_TYPE = "bbspace.extra.TARGET_SUB_TYPE"

    private const val EXTRA_SRC_FROM = "bbspace.extra.SRC_FROM"
    private const val EXTRA_SRC_SPMID = "bbspace.extra.SRC_SPMID"
    private const val EXTRA_SRC_TRACK_ID = "bbspace.extra.SRC_TRACK_ID"
    private const val EXTRA_SRC_REPORT_FLOW = "bbspace.extra.SRC_REPORT_FLOW"

    fun putTarget(intent: Intent, target: VideoTarget) {
        when (target) {
            is VideoTarget.Ugc -> {
                intent.putExtra(EXTRA_KIND, KIND_UGC)
                intent.putExtra(EXTRA_AID, target.aid)
                intent.putExtra(EXTRA_CID, target.cid)
                target.bvid?.let { intent.putExtra(EXTRA_BVID, it) }
            }

            is VideoTarget.Pgc -> {
                intent.putExtra(EXTRA_KIND, KIND_PGC)
                intent.putExtra(EXTRA_AID, target.aid)
                intent.putExtra(EXTRA_CID, target.cid)
                intent.putExtra(EXTRA_EP_ID, target.epId)
                target.seasonId?.let { intent.putExtra(EXTRA_SEASON_ID, it) }
                target.subType?.let { intent.putExtra(EXTRA_SUB_TYPE, it) }
            }

            is VideoTarget.Pugv -> {
                intent.putExtra(EXTRA_KIND, KIND_PUGV)
                intent.putExtra(EXTRA_AID, target.aid)
                intent.putExtra(EXTRA_EP_ID, target.epId)
                target.seasonId?.let { intent.putExtra(EXTRA_SEASON_ID, it) }
            }
        }
        intent.putExtra(EXTRA_SRC_FROM, target.src.from)
        intent.putExtra(EXTRA_SRC_SPMID, target.src.fromSpmid)
        target.src.trackId?.let { intent.putExtra(EXTRA_SRC_TRACK_ID, it) }
        target.src.reportFlowData?.let { intent.putExtra(EXTRA_SRC_REPORT_FLOW, it) }
    }

    fun getTarget(intent: Intent): VideoTarget? {
        val src = VideoSrc(
            from = intent.getStringExtra(EXTRA_SRC_FROM) ?: VideoTargetTool.FROM_FEED,
            fromSpmid = intent.getStringExtra(EXTRA_SRC_SPMID) ?: VideoTargetTool.FROM_SPMID_FEED,
            trackId = intent.getStringExtra(EXTRA_SRC_TRACK_ID),
            reportFlowData = intent.getStringExtra(EXTRA_SRC_REPORT_FLOW)
        )
        return when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_UGC -> VideoTarget.Ugc(
                aid = intent.getLongExtra(EXTRA_AID, 0L),
                cid = intent.getLongExtra(EXTRA_CID, 0L),
                bvid = intent.getStringExtra(EXTRA_BVID),
                src = src
            )

            KIND_PGC -> VideoTarget.Pgc(
                aid = intent.getLongExtra(EXTRA_AID, 0L),
                cid = intent.getLongExtra(EXTRA_CID, 0L),
                epId = intent.getLongExtra(EXTRA_EP_ID, 0L),
                seasonId = if (intent.hasExtra(EXTRA_SEASON_ID)) {
                    intent.getLongExtra(EXTRA_SEASON_ID, 0L)
                } else {
                    null
                },
                subType = if (intent.hasExtra(EXTRA_SUB_TYPE)) {
                    intent.getIntExtra(EXTRA_SUB_TYPE, 0)
                } else {
                    null
                },
                src = src
            )

            KIND_PUGV -> VideoTarget.Pugv(
                aid = intent.getLongExtra(EXTRA_AID, 0L),
                epId = intent.getLongExtra(EXTRA_EP_ID, 0L),
                seasonId = if (intent.hasExtra(EXTRA_SEASON_ID)) {
                    intent.getLongExtra(EXTRA_SEASON_ID, 0L)
                } else {
                    null
                },
                src = src
            )

            else -> null
        }
    }
}

/** 构造打开视频详情 Activity 的 Intent；无缝动画时同一 bundle 作为 options 传入。 */
fun videoDetailIntent(
    context: Context,
    target: VideoTarget,
    coverUrl: String?,
    coverLarge: Boolean = false,
    fromSeamless: Boolean = false
): Intent = Intent(context, VideoDetailActivity::class.java).apply {
    VideoTargetSerializer.putTarget(this, target)
    putExtra(EXTRA_COVER_URL, coverUrl)
    putExtra(EXTRA_COVER_LARGE, coverLarge)
    putExtra(EXTRA_FROM_SEAMLESS, fromSeamless)
}
