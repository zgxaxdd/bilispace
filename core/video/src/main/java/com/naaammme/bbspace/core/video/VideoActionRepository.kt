package com.naaammme.bbspace.core.video

import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.core.common.BiliWebHeaderTool
import com.naaammme.bbspace.infra.network.BiliRestClient
import com.naaammme.bbspace.infra.network.BiliRestParamBuilder
import com.naaammme.bbspace.infra.network.BiliRestProfile
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频互动仓库：点赞、投币、一键三连
 *
 * 点赞/投币走 App 协议端点（access_key + appkey 签名），
 * 三连走 Web 协议端点（Cookie + csrf），端点与参数与 PiliPlus 实现对齐
 */
@Singleton
class VideoActionRepository @Inject constructor(
    private val restClient: BiliRestClient,
    private val restParamBuilder: BiliRestParamBuilder,
    private val authProvider: AuthProvider
) {
    /**
     * 点赞/取消点赞
     *
     * @return 服务器 toast 文案，可能为空
     */
    suspend fun setLike(aid: Long, like: Boolean): String {
        val json = postApp(LIKE_ENDPOINT) {
            put("aid", aid.toString())
            // App 协议语义与直觉相反：like=0 是点赞、like=1 是取消点赞
            // （对照 piliplus lib/http/video.dart 的 likeVideo：想点赞传 '0'）。
            // 传反表现为"点赞报 取消点赞失败/未点赞过，已点赞再点反而取消"。
            put("like", if (like) "0" else "1")
        }
        return json.optJSONObject("data")?.optString("toast").orEmpty()
    }

    /**
     * 投币，multiply 只能为 1 或 2
     */
    suspend fun addCoin(aid: Long, multiply: Int, selectLike: Boolean) {
        postApp(COIN_ENDPOINT) {
            put("aid", aid.toString())
            put("multiply", multiply.toString())
            put("select_like", if (selectLike) "1" else "0")
        }
    }

    /**
     * Web 端一键三连：点赞 + 投 1 币 + 收藏到默认收藏夹
     */
    suspend fun triple(aid: Long, bvid: String?): TripleResult {
        val csrf = webCsrf()
        val referer = "https://www.bilibili.com/video/" +
            (bvid?.takeIf(String::isNotBlank) ?: "av$aid")
        val json = restClient.postWebForm(
            url = "${BiliConstants.BASE_URL_API}$TRIPLE_ENDPOINT",
            form = mapOf(
                "aid" to aid.toString(),
                "eab_x" to "2",
                "ramval" to "0",
                "source" to "web_normal",
                "ga" to "1",
                "csrf" to csrf,
                "spmid" to "333.788.0.0",
                "statistics" to TRIPLE_STATISTICS
            ),
            headers = webHeaders(referer)
        )
        val data = json.optJSONObject("data")
        return TripleResult(
            like = data?.optBoolean("like") ?: true,
            coin = data?.optBoolean("coin") ?: true,
            fav = data?.optBoolean("fav") ?: true
        )
    }

    data class TripleResult(
        val like: Boolean,
        val coin: Boolean,
        val fav: Boolean
    )

    private suspend fun postApp(
        endpoint: String,
        business: MutableMap<String, String>.() -> Unit
    ): JSONObject {
        val accessToken = authProvider.accessToken
        check(accessToken.isNotBlank()) { "请先登录" }
        val ts = System.currentTimeMillis() / 1000L
        return restClient.postSigned(
            url = "${BiliConstants.BASE_URL_APP}$endpoint",
            params = restParamBuilder.app(BiliRestProfile.APP, ts, accessToken) + buildMap(business),
            profile = BiliRestProfile.APP
        )
    }

    private fun webCsrf(): String {
        val csrf = authProvider.csrfToken()
        check(csrf.isNotBlank()) { "登录凭证缺少 Web 授权，请重新登录" }
        return csrf
    }

    private fun webHeaders(referer: String): Map<String, String> =
        BiliWebHeaderTool.build(authProvider.cookieHeader(), referer)

    private companion object {
        const val LIKE_ENDPOINT = "/x/v2/view/like"
        const val COIN_ENDPOINT = "/x/v2/view/coin/add"
        const val TRIPLE_ENDPOINT = "/x/web-interface/archive/like/triple"
        const val TRIPLE_STATISTICS = """{"appId":100,"platform":5}"""
    }
}
