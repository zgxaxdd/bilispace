package com.naaammme.bbspace.core.model.rank

import androidx.compose.runtime.Immutable

/**
 * 排行内容类型；分区表与 PiliPlus lib/models/common/rank_type.dart 一致：
 * UGC 走 ranking/v2 的 rid，PGC 走 pgc/web/rank/list 的 season_type。
 */
enum class RankKind {
    POPULAR,
    UGC,
    PGC,
    SERIES,
    PRECIOUS
}

enum class RankType(
    val label: String,
    val kind: RankKind,
    val rid: Long = 0L,
    val seasonType: Int = 0
) {
    POPULAR("热门", RankKind.POPULAR),

    DOUGA("动画", RankKind.UGC, rid = 1005),
    MUSIC("音乐", RankKind.UGC, rid = 1003),
    DANCE("舞蹈", RankKind.UGC, rid = 1004),
    GAME("游戏", RankKind.UGC, rid = 1008),
    KNOWLEDGE("知识", RankKind.UGC, rid = 1010),
    TECH("科技", RankKind.UGC, rid = 1012),
    SPORTS("运动", RankKind.UGC, rid = 1018),
    CAR("汽车", RankKind.UGC, rid = 1013),
    FOOD("美食", RankKind.UGC, rid = 1020),
    ANIMAL("动物", RankKind.UGC, rid = 1024),
    KICHIKU("鬼畜", RankKind.UGC, rid = 1007),
    FASHION("时尚", RankKind.UGC, rid = 1014),
    ENT("娱乐", RankKind.UGC, rid = 1002),
    CINEPHILE("影视", RankKind.UGC, rid = 1001),

    BANGUMI("番剧", RankKind.PGC, seasonType = 1),
    GUOCHUANG("国创", RankKind.PGC, seasonType = 4),
    DOCUMENTARY("纪录片", RankKind.PGC, seasonType = 3),
    MOVIE("电影", RankKind.PGC, seasonType = 2),
    TV("剧集", RankKind.PGC, seasonType = 5),
    VARIETY("综艺", RankKind.PGC, seasonType = 7),

    SERIES("每周必看", RankKind.SERIES),
    PRECIOUS("入站必刷", RankKind.PRECIOUS)
}

/** ranking/v2、popular、popular/series/one、popular/precious 的视频条目 */
@Immutable
data class RankVideoItem(
    val aid: Long,
    val bvid: String?,
    val cid: Long,
    val title: String,
    val cover: String?,
    val durationSec: Long,
    val ownerMid: Long,
    val ownerName: String,
    val ownerFace: String?,
    val view: Long,
    val danmaku: Long,
    val tname: String?
)

/** pgc/web/rank/list 的剧集条目（seasonId/epId 从 url 解析） */
@Immutable
data class RankPgcItem(
    val title: String,
    val cover: String?,
    val url: String?,
    val seasonId: Long?,
    val epId: Long?,
    val indexShow: String?,
    val follow: Long,
    val view: Long
)

/** popular/series/list 的每周必看期数条目 */
@Immutable
data class RankSeriesEntry(
    val number: Int,
    val name: String
)

/** 排行页统一条目：卡片渲染按 Kind 分流 */
@Immutable
sealed interface RankEntry {
    val key: String

    @Immutable
    data class Video(val item: RankVideoItem) : RankEntry {
        override val key: String = "v_${item.aid}_${item.cid}"
    }

    @Immutable
    data class Pgc(val item: RankPgcItem) : RankEntry {
        override val key: String = "p_${item.seasonId ?: item.url ?: item.title}"
    }
}
