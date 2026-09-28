package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable
import java.net.URI
import java.net.URLDecoder

sealed interface VideoTarget {
    val src: VideoSrc

    @Immutable
    data class Ugc(
        val aid: Long,
        val cid: Long,
        val bvid: String? = null,
        override val src: VideoSrc = VideoTargetTool.feed()
    ) : VideoTarget

    @Immutable
    data class Pgc(
        val aid: Long = 0L,
        val cid: Long = 0L,
        val epId: Long = 0L,
        val seasonId: Long? = null,
        val subType: Int? = null,
        override val src: VideoSrc = VideoTargetTool.feed()
    ) : VideoTarget

    @Immutable
    data class Pugv(
        val aid: Long = 0L,
        val epId: Long = 0L,
        val seasonId: Long? = null,
        override val src: VideoSrc = VideoTargetTool.feed()
    ) : VideoTarget
}

fun VideoTarget.isSameEntry(other: VideoTarget?): Boolean {
    other ?: return false
    return when (this) {
        is VideoTarget.Ugc -> other is VideoTarget.Ugc && aid == other.aid
        is VideoTarget.Pgc -> other is VideoTarget.Pgc &&
            (
                epId > 0L && other.epId > 0L && epId == other.epId ||
                    aid > 0L && other.aid > 0L && aid == other.aid ||
                    seasonId != null && seasonId == other.seasonId
                )
        is VideoTarget.Pugv -> other is VideoTarget.Pugv &&
            (
                epId > 0L && other.epId > 0L && epId == other.epId ||
                    aid > 0L && other.aid > 0L && aid == other.aid ||
                    seasonId != null && seasonId == other.seasonId
                )
    }
}

fun VideoTarget.toPlayableParams(): PlayableParams {
    val ids = when (this) {
        is VideoTarget.Ugc -> VideoRequestIds(
            aid = aid,
            cid = cid,
            bvid = bvid
        )

        is VideoTarget.Pgc -> VideoRequestIds(
            aid = aid,
            cid = cid,
            epId = epId,
            seasonId = seasonId ?: 0L
        )

        is VideoTarget.Pugv -> VideoRequestIds(
            aid = aid,
            epId = epId,
            seasonId = seasonId ?: 0L
        )
    }
    val biz = when (this) {
        is VideoTarget.Ugc -> PlayBizInfo(biz = PlayBiz.UGC)
        is VideoTarget.Pgc -> PlayBizInfo(
            biz = PlayBiz.PGC,
            subType = subType,
            seasonId = seasonId?.takeIf { it > 0L },
            epId = epId.takeIf { it > 0L }
        )

        is VideoTarget.Pugv -> PlayBizInfo(
            biz = PlayBiz.PUGV,
            seasonId = seasonId?.takeIf { it > 0L },
            epId = epId.takeIf { it > 0L }
        )
    }
    return when (this) {
        is VideoTarget.Ugc,
        is VideoTarget.Pgc,
        is VideoTarget.Pugv -> PlayableParams(
            ids = ids,
            src = src,
            biz = biz
        )
    }
}

@Immutable
data class VideoSrc(
    val from: String = VideoTargetTool.FROM_FEED,
    val fromSpmid: String = VideoTargetTool.FROM_SPMID_FEED,
    val trackId: String? = null,
    val reportFlowData: String? = null
)

object VideoTargetTool {
    const val SPMID = "united.player-video-detail.0.0"
    const val FROM_FEED = "7"
    const val FROM_HISTORY = "64"
    const val FROM_SPACE = "66"
    const val FROM_SEARCH = "3"
    const val FROM_RELATE = "2"
    const val FROM_DYNAMIC = "6"
    const val FROM_DEFAULT = "6" //60
    const val FROM_SPMID_FEED = "tm.recommend.0.0"
    const val FROM_SPMID_HISTORY = "main.my-history.0.0"
    const val FROM_SPMID_WATCH_LATER = "main.later-watch.0.0"
    const val FROM_SPMID_FAVORITE = "main.my-fav.0.0"
    const val FROM_SPMID_SPACE = "main.space-contribution.0.0"
    const val FROM_SPMID_SEARCH = "search.search-result.0.0"
    const val FROM_SPMID_DYNAMIC = "dt.dt.video.0"
    const val FROM_SPMID_DEFAULT = "default-value"
    private const val RELATE_SPMID_PRE = "united.player-video-detail.relatedvideo"

    fun feed(
        trackId: String? = null,
        reportFlowData: String? = null
    ): VideoSrc {
        return VideoSrc(
            from = FROM_FEED,
            fromSpmid = FROM_SPMID_FEED,
            trackId = trackId.blankToNull(),
            reportFlowData = reportFlowData.blankToNull()
        )
    }

    fun default(
        trackId: String? = null,
        reportFlowData: String? = null
    ): VideoSrc {
        return VideoSrc(
            from = FROM_DEFAULT,
            fromSpmid = FROM_SPMID_DEFAULT,
            trackId = trackId.blankToNull(),
            reportFlowData = reportFlowData.blankToNull()
        )
    }

    fun dynamic(
        trackId: String? = null,
        reportFlowData: String? = null
    ): VideoSrc {
        return VideoSrc(
            from = FROM_DYNAMIC,
            fromSpmid = FROM_SPMID_DYNAMIC,
            trackId = trackId.blankToNull(),
            reportFlowData = reportFlowData.blankToNull()
        )
    }

    fun history(
        trackId: String? = null,
        reportFlowData: String? = null
    ): VideoSrc {
        return VideoSrc(
            from = FROM_HISTORY,
            fromSpmid = FROM_SPMID_HISTORY,
            trackId = trackId.blankToNull(),
            reportFlowData = reportFlowData.blankToNull()
        )
    }

    fun watchLater(
        trackId: String? = null,
        reportFlowData: String? = null
    ): VideoSrc {
        return VideoSrc(
            from = FROM_HISTORY,
            fromSpmid = FROM_SPMID_WATCH_LATER,
            trackId = trackId.blankToNull(),
            reportFlowData = reportFlowData.blankToNull()
        )
    }

    fun favorite(
        trackId: String? = null,
        reportFlowData: String? = null
    ): VideoSrc {
        return VideoSrc(
            from = FROM_DYNAMIC,
            fromSpmid = FROM_SPMID_FAVORITE,
            trackId = trackId.blankToNull(),
            reportFlowData = reportFlowData.blankToNull()
        )
    }

    fun space(
        trackId: String? = null,
        reportFlowData: String? = null
    ): VideoSrc {
        return VideoSrc(
            from = FROM_SPACE,
            fromSpmid = FROM_SPMID_SPACE,
            trackId = trackId.blankToNull(),
            reportFlowData = reportFlowData.blankToNull()
        )
    }

    fun search(
        uri: String,
        fallbackTrackId: String? = null,
        fallbackReportFlowData: String? = null
    ): VideoSrc {
        return VideoSrc(
            from = FROM_SEARCH,
            fromSpmid = FROM_SPMID_SEARCH,
            trackId = arg(uri, "trackid")
                ?: arg(uri, "track_id")
                ?: fallbackTrackId.blankToNull(),
            reportFlowData = arg(uri, "report_flow_data")
                ?: fallbackReportFlowData.blankToNull()
        )
    }

    fun relate(
        trackId: String? = null,
        reportFlowData: String? = null,
        fromSpmidSuffix: String? = null
    ): VideoSrc {
        val suffix = fromSpmidSuffix.blankToNull() ?: "0"
        return VideoSrc(
            from = FROM_RELATE,
            fromSpmid = "$RELATE_SPMID_PRE.$suffix",
            trackId = trackId.blankToNull(),
            reportFlowData = reportFlowData.blankToNull()
        )
    }

    fun aid(uri: String): Long? {
        val path = runCatching { URI(uri).path }
            .getOrNull()
            .orEmpty()
            .trimEnd('/')
        return path.substringAfterLast('/')
            .blankToNull()
            ?.toLongOrNull()
    }

    fun bvid(uri: String): String? {
        val path = runCatching { URI(uri).path }
            .getOrNull()
            .orEmpty()
        val bv = BV_REGEX.find(path)?.value
            ?: arg(uri, "bvid")
            ?: arg(uri, "BVID")
        return bv.blankToNull()
    }

    /**
     * aid → bvid 本地换算（纯计算，无网络）。**移植自 BiliPai `core/util/IdUtils.kt`
     * 的 `av2bv`**（同一套常量与两次交换）。
     *
     * ## 为什么需要它
     * relate 卡（详情响应的相关推荐模块）的 `basic.uri` 是 **aid 格式**
     * （`bilibili://video/{aid}`），[bvid] 解析为空；而播放链路的下游几乎都以 bvid
     * 为主键（页身份、历史、弹幕（bvid+cid）、分享、上报）。BiliPai 在"接口只给 aid"
     * 时同样用本地换算补齐（`BangumiRepository` / `LikedVideosRepository`）。
     *
     * ## 不要改回网上流传的旧版算法
     * 旧版是「固定模板 `BV1__4_1_7__` + 58 进制 6 位（SALT 表 `fZodR9…`）」，
     * 只对老 aid 成立：实测 **aid=117339261503379 旧算法得 BV1d54t1F77p，真值
     * BV1Kyas6wEuz**（live API 核对）——即新版长 aid 会算出**错误的 bvid**，
     * 比留空更有害（页身份、历史全部串到另一个视频上）。
     * 现在这一版（bit51 掩码 + 交换 3↔9、4↔7）对新老 aid 都成立，
     * 真值见 `VideoTargetToolIdTest`。
     *
     * @return 12 位 bvid；aid 非法或超出该编码可表示的范围时返回 null
     *   （**宁可不给，也不给错的**）。
     */
    fun avToBv(aid: Long): String? {
        if (aid <= 0L) return null
        val chars = CharArray(BV_LENGTH) { '0' }
        chars[0] = 'B'
        chars[1] = 'V'
        chars[2] = '1'
        var index = chars.lastIndex
        var value = (BV_MAX_AID or aid) xor BV_XOR_CODE
        while (value > 0L && index >= 3) {
            chars[index--] = BV_DATA[(value % BV_BASE).toInt()]
            value /= BV_BASE
        }
        // bit51 恒为 1 → value ∈ [2^51, 2^52) ⊂ [58^8, 58^9)：正常恰好写满 9 位
        // （index 停在 2）。位数不对说明 aid 超出编码范围，不猜。
        if (value > 0L || index != 2) return null
        swap(chars, 3, 9)
        swap(chars, 4, 7)
        return String(chars)
    }

    /** [avToBv] 的逆运算：bvid → aid。非 12 位 BV 号 / 含表外字符时返回 null。 */
    fun bvToAv(bvid: String): Long? {
        val ids = bvid.trim()
        if (ids.length != BV_LENGTH || !ids.startsWith("BV1", ignoreCase = true)) return null
        val chars = ids.toCharArray()
        swap(chars, 3, 9)
        swap(chars, 4, 7)
        var value = 0L
        for (i in 3 until chars.size) {
            val digit = BV_DATA.indexOf(chars[i])
            if (digit < 0) return null
            value = value * BV_BASE + digit
        }
        return (value and BV_MASK_CODE) xor BV_XOR_CODE
    }

    fun cid(uri: String): Long? {
        return arg(uri, "cid")?.toLongOrNull()
    }

    fun epId(uri: String): Long? {
        val path = runCatching { URI(uri).path }
            .getOrNull()
            .orEmpty()
            .trimEnd('/')
        val last = path.substringAfterLast('/')
        return when {
            last.startsWith("ep") -> last.removePrefix("ep").toLongOrNull()
            else -> arg(uri, "ep_id")?.toLongOrNull()
        }
    }

    fun arg(
        uri: String,
        key: String
    ): String? {
        val raw = runCatching { URI(uri).rawQuery }.getOrNull().orEmpty()
        if (raw.isBlank()) return null
        return raw.split('&')
            .asSequence()
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                val name = if (idx >= 0) part.substring(0, idx) else part
                if (name.isBlank()) return@mapNotNull null
                val value = if (idx >= 0) part.substring(idx + 1) else ""
                URLDecoder.decode(name, UTF_8) to URLDecoder.decode(value, UTF_8)
            }
            .firstOrNull { it.first == key }
            ?.second
            .blankToNull()
    }

    private fun String?.blankToNull(): String? {
        return this?.takeIf { it.isNotBlank() }
    }

    private fun swap(chars: CharArray, left: Int, right: Int) {
        val tmp = chars[left]
        chars[left] = chars[right]
        chars[right] = tmp
    }

    private const val UTF_8 = "UTF-8"
    private val BV_REGEX = Regex("BV[0-9A-Za-z]+")

    // ── aid ⇄ bvid 编码常量（BiliPai `core/util/IdUtils.kt` 原值，勿改）──────────
    private const val BV_LENGTH = 12
    private const val BV_BASE = 58L
    private const val BV_XOR_CODE = 23442827791579L
    private const val BV_MASK_CODE = 2251799813685247L // 2^51 - 1
    private const val BV_MAX_AID = 1L shl 51
    private const val BV_DATA = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
}
