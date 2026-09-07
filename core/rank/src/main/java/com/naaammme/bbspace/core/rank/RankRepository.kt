package com.naaammme.bbspace.core.rank

import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.core.common.UserAgentBuilder
import com.naaammme.bbspace.core.model.rank.RankPgcItem
import com.naaammme.bbspace.core.model.rank.RankSeriesEntry
import com.naaammme.bbspace.core.model.rank.RankVideoItem
import com.naaammme.bbspace.infra.crypto.WbiSigner
import com.naaammme.bbspace.infra.network.BiliRestClient
import com.naaammme.bbspace.infra.web.WbiKeyProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 排行/热门数据仓库。全部为 Web 端点（对照 PiliPlus lib/http/video.dart）：
 * - GET /x/web-interface/popular            {pn, ps}            不签名
 * - GET /x/web-interface/ranking/v2         {rid, type:"all"}   WBI 签名
 * - GET /x/web-interface/popular/series/list  {web_location:333.934}  WBI 签名
 * - GET /x/web-interface/popular/series/one   {number, web_location}  WBI 签名
 * - GET /x/web-interface/popular/precious     {page_size:100, page}   WBI 签名
 * - GET /pgc/web/rank/list            {day:3, season_type}  WBI 签名（响应在 result.list）
 * - GET /pgc/season/rank/web/list     {day:3, season_type}  WBI 签名（响应在 data.list）
 */
@Singleton
class RankRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val wbiKeyProvider: WbiKeyProvider,
    private val authProvider: AuthProvider
) {

    suspend fun fetchPopular(page: Int, pageSize: Int = POPULAR_PAGE_SIZE): List<RankVideoItem> {
        val json = plainGet(
            endpoint = POPULAR_ENDPOINT,
            params = mapOf(
                "pn" to page.toString(),
                "ps" to pageSize.toString()
            )
        )
        return mapVideoList(json.dataList())
    }

    suspend fun fetchRanking(rid: Long): List<RankVideoItem> {
        val json = wbiGet(
            endpoint = RANKING_ENDPOINT,
            params = mapOf(
                "rid" to rid.toString(),
                "type" to "all"
            )
        )
        return mapVideoList(json.dataList())
    }

    suspend fun fetchSeriesList(): List<RankSeriesEntry> {
        val json = wbiGet(
            endpoint = SERIES_LIST_ENDPOINT,
            params = mapOf("web_location" to WEB_LOCATION)
        )
        return withContext(Dispatchers.Default) {
            buildList {
                val list = json.dataList()
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val number = item.optInt("number")
                    if (number <= 0) continue
                    add(
                        RankSeriesEntry(
                            number = number,
                            name = item.optString("name")
                        )
                    )
                }
            }
        }
    }

    suspend fun fetchSeriesOne(number: Int): List<RankVideoItem> {
        val json = wbiGet(
            endpoint = SERIES_ONE_ENDPOINT,
            params = mapOf(
                "number" to number.toString(),
                "web_location" to WEB_LOCATION
            )
        )
        return mapVideoList(json.dataList())
    }

    suspend fun fetchPrecious(page: Int): List<RankVideoItem> {
        val json = wbiGet(
            endpoint = PRECIOUS_ENDPOINT,
            params = mapOf(
                "page_size" to PRECIOUS_PAGE_SIZE.toString(),
                "page" to page.toString(),
                "web_location" to WEB_LOCATION
            )
        )
        return mapVideoList(json.dataList())
    }

    suspend fun fetchPgcRank(seasonType: Int, day: Int = PGC_DAY): List<RankPgcItem> {
        val json = wbiGet(
            endpoint = PGC_RANK_ENDPOINT,
            params = mapOf(
                "day" to day.toString(),
                "season_type" to seasonType.toString()
            )
        )
        return mapPgcList(json.optJSONObject("result")?.optJSONArray("list"))
    }

    suspend fun fetchPgcSeasonRank(seasonType: Int, day: Int = PGC_DAY): List<RankPgcItem> {
        val json = wbiGet(
            endpoint = PGC_SEASON_RANK_ENDPOINT,
            params = mapOf(
                "day" to day.toString(),
                "season_type" to seasonType.toString()
            )
        )
        return mapPgcList(json.dataList())
    }

    // ===== 请求与映射 =====

    private suspend fun wbiGet(
        endpoint: String,
        params: Map<String, String>
    ): JSONObject {
        val keys = wbiKeyProvider.getKeys()
        val signed = WbiSigner.sign(params = params, imgKey = keys.imgKey, subKey = keys.subKey)
        return restClient.getWeb(
            url = "${BiliConstants.BASE_URL_API}$endpoint",
            query = signed,
            headers = webHeaders()
        )
    }

    private suspend fun plainGet(
        endpoint: String,
        params: Map<String, String>
    ): JSONObject {
        return restClient.getWeb(
            url = "${BiliConstants.BASE_URL_API}$endpoint",
            query = params,
            headers = webHeaders()
        )
    }

    private fun webHeaders(): Map<String, String> = buildMap {
        put("user-agent", UserAgentBuilder.buildWebUserAgent())
        put("referer", "${BiliConstants.BASE_URL_WEB}/")
        put("accept", "application/json, text/plain, */*")
        authProvider.cookieHeader().takeIf(String::isNotBlank)?.let { put("cookie", it) }
    }

    private fun JSONObject.dataList(): JSONArray {
        return optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
    }

    private suspend fun mapVideoList(list: JSONArray): List<RankVideoItem> {
        return withContext(Dispatchers.Default) {
            mapRankVideoList(list)
        }
    }

    private suspend fun mapPgcList(list: JSONArray?): List<RankPgcItem> {
        return withContext(Dispatchers.Default) {
            mapRankPgcList(list)
        }
    }

    private companion object {
        const val POPULAR_ENDPOINT = "/x/web-interface/popular"
        const val RANKING_ENDPOINT = "/x/web-interface/ranking/v2"
        const val SERIES_LIST_ENDPOINT = "/x/web-interface/popular/series/list"
        const val SERIES_ONE_ENDPOINT = "/x/web-interface/popular/series/one"
        const val PRECIOUS_ENDPOINT = "/x/web-interface/popular/precious"
        const val PGC_RANK_ENDPOINT = "/pgc/web/rank/list"
        const val PGC_SEASON_RANK_ENDPOINT = "/pgc/season/rank/web/list"
        const val POPULAR_PAGE_SIZE = 20
        const val PRECIOUS_PAGE_SIZE = 100
        const val PGC_DAY = 3
        const val WEB_LOCATION = "333.934"
    }
}
