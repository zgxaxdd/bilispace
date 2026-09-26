package com.naaammme.bbspace.infra.web

import com.naaammme.bbspace.core.common.BiliConstants
import com.naaammme.bbspace.core.common.UserAgentBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class WbiKeys(
    val imgKey: String,
    val subKey: String
)

/**
 * WBI 签名密钥提供方：从 nav 接口的 wbi_img 提取 img_key/sub_key 并进程内缓存。
 * 由 WebPlayUrlClient 与排行榜等需要 WBI 签名的 Web 请求共享。
 */
@Singleton
class WbiKeyProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    @Volatile
    private var cached: WbiKeys? = null

    /** 缓存对应的日期（epoch day）：wbi img_key/sub_key 服务端按天轮换，跨天必须重取，
     *  否则签名持续校验失败（对照 piliplus getWbiKeys 的按天比较）。 */
    @Volatile
    private var cachedEpochDay: Long = -1

    suspend fun getKeys(): WbiKeys {
        cached?.takeIf { cachedEpochDay == todayEpochDay() }?.let { return it }
        return withContext(Dispatchers.IO) {
            val now = todayEpochDay()
            cached?.takeIf { cachedEpochDay == now }?.let { return@withContext it }
            val request = Request.Builder()
                .url("${BiliConstants.BASE_URL_API}$NAV_ENDPOINT")
                .get()
                .addHeader("referer", "https://www.bilibili.com/")
                .addHeader("user-agent", UserAgentBuilder.buildWebUserAgent())
                .build()
            val json = okHttpClient.newCall(request).execute().use { response ->
                JSONObject(response.body?.string() ?: error("nav empty response"))
            }
            val wbiImg = json.optJSONObject("data")
                ?.optJSONObject("wbi_img")
                ?: error("nav 缺少 wbi_img")
            WbiKeys(
                imgKey = wbiImg.optString("img_url").extractWbiKey(),
                subKey = wbiImg.optString("sub_url").extractWbiKey()
            ).also {
                cached = it
                cachedEpochDay = now
            }
        }
    }

    private fun todayEpochDay(): Long = java.time.LocalDate.now().toEpochDay()

    private fun String.extractWbiKey(): String {
        return substringAfterLast('/').substringBefore('.').take(WBI_KEY_LENGTH)
    }

    private companion object {
        const val NAV_ENDPOINT = "/x/web-interface/nav"
        const val WBI_KEY_LENGTH = 32
    }
}
