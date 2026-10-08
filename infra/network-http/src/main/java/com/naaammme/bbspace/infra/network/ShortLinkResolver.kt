package com.naaammme.bbspace.infra.network

import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * b23.tv 短链解析（B-2，2026-10-04 真机证实缺口）。
 *
 * ## 为什么需要它
 *
 * B 站 App / 网页分享出来的链接**最常见**的形态是 `https://b23.tv/xxxx`，而它只有一个
 * 302 才指向真实地址。此前 manifest 没有注册 `b23.tv`，系统不会把链接交给本应用；
 * 现在注册了，就必须在外部入口处**先把 302 目标解析出来**再走 [com.naaammme.bbspace.core.model.WebLinkParser]，
 * 否则"接管后只能把用户再踢回浏览器"，比不接管更糟。
 *
 * 参考实现：`_ref/BiliPai` 的 `BilibiliUrlParser.resolveShortUrl`
 * （`instanceFollowRedirects = false` + HEAD + 读 `Location`）。
 *
 * ## 为什么关掉重定向跟随
 *
 * 我们要的是**302 的 Location**，不是最终页面；跟随重定向会真的把目标页拉下来
 * （浪费流量、也可能命中需要登录的页面）。这里用 `newBuilder()` 派生一个
 * 不跟随重定向的客户端：按 OkHttp 约定，派生客户端**共享同一个连接池与 Dispatcher**
 * （即 D2 的"全站唯一 OkHttpClient"约束没有被打破）。
 */
@Singleton
class ShortLinkResolver @Inject constructor(
    okHttpClient: OkHttpClient
) {

    private val noRedirectClient: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * @return 302 目标（可能是相对路径，已按请求 URL 解析成绝对地址）；解析不出来返回 null。
     *
     * 最多跟 [MAX_HOPS] 跳（照 `_ref/BiliPai` v0.2.9 的 `SHORT_LINK_MAX_HOPS = 3`）：
     * b23.tv 偶尔会连跳两次（短链 → 中转 → 真实地址），只跟一跳会拿回中间地址、
     * 解析不出站内路由，等于白跑一次回落。
     */
    suspend fun resolve(shortUrl: String): String? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            var current = shortUrl
            var lastLocation: String? = null
            repeat(MAX_HOPS) {
                val request = Request.Builder().url(current).head().build()
                // 必须 use{}：连接池释放由 HttpResponseReleaseGuardTest 钉住（全仓口径）
                val next = noRedirectClient.newCall(request).execute().use { response ->
                    val requestUrl = response.request.url
                    response.header("Location")
                        ?.let { location -> requestUrl.resolve(location)?.toString() }
                }
                if (next == null) return@runCatchingCancellable lastLocation
                // 指回自己 = 成环（或服务端把 Location 指向自身），就此收手
                if (next == current) return@runCatchingCancellable next
                lastLocation = next
                current = next
            }
            lastLocation
        }.getOrNull()
    }

    private companion object {
        /** 最多跟几跳（与 BiliPai 同值）。 */
        const val MAX_HOPS = 3
    }
}
