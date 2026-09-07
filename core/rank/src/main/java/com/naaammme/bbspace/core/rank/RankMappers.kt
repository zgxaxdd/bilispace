package com.naaammme.bbspace.core.rank

import com.naaammme.bbspace.core.common.media.httpsImageUrlOrNull
import com.naaammme.bbspace.core.model.rank.RankPgcItem
import com.naaammme.bbspace.core.model.rank.RankVideoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * ranking/v2、popular 等接口的响应映射（纯函数，JVM 可测）。
 * 字段对照 PiliPlus HotVideoItemModel（model_hot_video_item.dart）。
 */
internal fun mapRankVideo(item: JSONObject?): RankVideoItem? {
    item ?: return null
    val aid = item.optLong("aid")
    if (aid <= 0L) return null // redirect_url 的 PGC/充电条目不进 UGC 卡片
    val stat = item.optJSONObject("stat")
    val owner = item.optJSONObject("owner")
    return RankVideoItem(
        aid = aid,
        bvid = item.optString("bvid").rankBlankToNull(),
        cid = item.optLong("cid"),
        title = item.optString("title"),
        cover = item.optString("pic").rankBlankToNull().httpsImageUrlOrNull(),
        durationSec = item.optLong("duration").coerceAtLeast(0L),
        ownerMid = owner?.optLong("mid") ?: 0L,
        ownerName = owner?.optString("name").orEmpty(),
        ownerFace = owner?.optString("face")?.rankBlankToNull()?.httpsImageUrlOrNull(),
        view = stat?.optLong("view") ?: 0L,
        danmaku = stat?.optLong("danmaku")?.takeIf { it > 0 } ?: stat?.optLong("danmu") ?: 0L,
        tname = item.optString("tname").rankBlankToNull()
    )
}

internal fun mapRankVideoList(list: JSONArray): List<RankVideoItem> {
    return buildList {
        for (i in 0 until list.length()) {
            mapRankVideo(list.optJSONObject(i))?.let(::add)
        }
    }
}

/**
 * pgc/web/rank/list 响应映射。字段对照 PiliPlus PgcRankItemModel：
 * seasonId/epId 从 url（…/bangumi/play/ss…|ep…）解析，无可靠 id 时返回 null。
 */
internal fun mapRankPgc(item: JSONObject?): RankPgcItem? {
    item ?: return null
    val title = item.optString("title").rankBlankToNull() ?: return null
    val url = item.optString("url").rankBlankToNull()
    val stat = item.optJSONObject("stat")
    return RankPgcItem(
        title = title,
        cover = item.optString("cover").rankBlankToNull()?.httpsImageUrlOrNull(),
        url = url,
        seasonId = url?.extractBangumiId("ss"),
        epId = url?.extractBangumiId("ep"),
        indexShow = item.optJSONObject("new_ep")?.optString("index_show")?.rankBlankToNull(),
        follow = stat?.optLong("follow") ?: 0L,
        view = stat?.optLong("view") ?: 0L
    )
}

internal fun mapRankPgcList(list: JSONArray?): List<RankPgcItem> {
    list ?: return emptyList()
    return buildList {
        for (i in 0 until list.length()) {
            mapRankPgc(list.optJSONObject(i))?.let(::add)
        }
    }
}

/** 从 bangumi url（…/bangumi/play/ss12345 或 ep…）解析 id */
internal fun String.extractBangumiId(prefix: String): Long? {
    val pattern = Regex("/bangumi/play/$prefix(\\d+)")
    return pattern.find(this)?.groupValues?.get(1)?.toLongOrNull()
}

private fun String.rankBlankToNull(): String? = takeIf(String::isNotBlank)
