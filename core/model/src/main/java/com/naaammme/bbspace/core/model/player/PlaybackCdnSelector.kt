package com.naaammme.bbspace.core.model

import com.naaammme.bbspace.core.model.player.CdnHealthPolicy
import com.naaammme.bbspace.core.model.player.CdnSmartSelectionPolicy

private const val PROXY_TF_HOST = "proxy-tf-all-ws.bilivideo.com"
private val MCDN_RESOURCE_REGEX = Regex(
    """^https?://(?:(?:\d{1,3}\.){3}\d{1,3}|[^/]+\.mcdn\.bilivideo\.(?:com|cn|net))(?::\d{1,5})?/v\d/resource""",
    RegexOption.IGNORE_CASE
)
private val REWRITABLE_UPGCXCODE_REGEX = Regex(
    """^https?://(?:upos-\w+-(?!302)\w+|(?:upos|proxy)-tf-[^/]+)\.(?:bilivideo|akamaized)\.(?:com|net)/upgcxcode""",
    RegexOption.IGNORE_CASE
)

fun selectPlaybackCdn(
    stream: PlaybackStream?,
    audio: PlaybackAudio?,
    mode: VideoCdnMode,
    healthByHost: Map<String, CdnHealthPolicy.Health> = emptyMap()
): PlaybackCdn? {
    val dash = stream as? PlaybackStream.Dash ?: return null
    val videoUrls = mergePlaybackUrls(dash.videoUrl, dash.videoBackupUrls)
    if (videoUrls.isEmpty()) return null
    // TODO: 音视频线路目前各自按 mode 独立选择，后续再评估是否需要统一 host
    return PlaybackCdn(
        videoUrl = selectPlaybackUrl(videoUrls, mode, healthByHost) ?: return null,
        audioUrl = selectPlaybackUrl(
            urls = mergePlaybackUrls(audio?.url, audio?.backupUrls.orEmpty()),
            mode = mode,
            healthByHost = healthByHost
        )
    )
}

/**
 * 候选线路的**最终次序**：首元素 = 已选主线路，其后为其余备选。
 *
 * 引擎的失败切换是"按这个列表依次试"，所以**这个次序就是选线策略的最终表达**。
 *
 * - 智能档：整表按健康度排（已选的那条本就在首位，因为它是同一份健康度选出来的）；
 * - 其余档：保持"已选优先 + 其余按服务端原序"，与既有行为逐字节一致。
 */
fun orderPlaybackUrlsForMode(
    urls: List<String>,
    selected: String,
    mode: VideoCdnMode,
    healthByHost: Map<String, CdnHealthPolicy.Health> = emptyMap()
): List<String> {
    if (mode == VideoCdnMode.Smart) {
        if (healthByHost.isEmpty()) {
            // 无观测：保持与既有默认档**同一形状**（已选优先 + 其余原序），
            // 避免"表头变了、引擎按新次序依次试"把长期可播的那条挤到后面（2026-10-06 故障）。
            return (listOf(selected) + urls.filter { it != selected }).distinct()
        }
        return smartOrder(urls, healthByHost)
    }
    return (listOf(selected) + urls.filter { it != selected }).distinct()
}

/**
 * 智能档的最终次序：**可直连的优先**，其内按健康度；PCDN 资源排在最后。
 *
 * ## 为什么必须有这一层（真实故障复盘，2026-10-06）
 *
 * 智能档在"没有观测数据"时按服务端**原序**取首条，而不少视频的首条是
 * **PCDN（`mcdn`）资源 URL**（形如 `http://58.222.41.85:8000/v1/resource/upgcxcode/...&os=mcdn`）。
 * 本仓单文件 DASH 必须先 `Range: bytes=0-91535` 拉索引段
 * （`SingleFileDashMediaSourceFactory.readIndex`），**裸 PCDN URL 读不到索引** ⇒
 * 报「单文件 dash 索引缺失」⇒ 视频打不开（用户实测：选了智能选线后任何视频都看不了）。
 * 旧默认档 `Backup2` 取第 3 条（普通 CDN），所以这个问题一直没暴露。
 *
 * ## 对齐 BiliPai 的语义（**不是我自己发明的规则**）
 *
 * 源的线路排序里 **`Source` 优先于健康度**：`orderCandidates` 的第一排序键是来源、
 * 第二才是分数（它甚至用测试把"给自定义线路灌满错误分也抢不走首位"钉死——
 * 见 `CdnRegionPolicyTest.kt:183-195`）。我把这一层丢了，让分数直接排全部候选，
 * 才会把"读不到索引的 PCDN"排到第一位。这里把「能否直连」作为同一层语义补回来。
 *
 * 若**全部**候选都是 PCDN，用既有代理重写兜底（`proxy-tf-all-ws.bilivideo.com?url=`），
 * 保证至少有一条可播 —— 这条重写本就是本仓处理 PCDN 的既有手段。
 */
private fun smartOrder(
    urls: List<String>,
    healthByHost: Map<String, CdnHealthPolicy.Health>
): List<String> {
    val ordered = CdnSmartSelectionPolicy.orderUrlsByHealth(urls, healthByHost)
    val playable = ordered.filterNot(::isPcdnResourceUrl)
    val pcdn = ordered.filter(::isPcdnResourceUrl)
    if (playable.isNotEmpty()) return playable + pcdn
    return pcdn.mapIndexed { index, url -> if (index == 0) proxyWrapPcdn(url) ?: url else url }
}

/** PCDN 资源 URL（`http(s)://<host>/v1/resource...`）：本仓不能直接播，必须先转代理。 */
private fun isPcdnResourceUrl(url: String): Boolean = MCDN_RESOURCE_REGEX.containsMatchIn(url)

/** 把 PCDN 资源 URL 包进既有代理（与重写档走同一条通道）。 */
private fun proxyWrapPcdn(url: String): String? = runCatching {
    "https://$PROXY_TF_HOST?url=" + java.net.URLEncoder.encode(url, "UTF-8")
}.getOrNull()

private fun selectPlaybackUrl(
    urls: List<String>,
    mode: VideoCdnMode,
    healthByHost: Map<String, CdnHealthPolicy.Health>
): String? {
    if (urls.isEmpty()) return null
    return when (mode) {
        // 智能选线：没有观测时保持服务端原序（原序本身就是唯一线索）
        VideoCdnMode.Smart -> if (healthByHost.isEmpty()) {
            // **无任何观测**：沿用既有默认档（Backup2）的选择 —— 即"不比现状更差"。
            // 真实故障复盘（2026-10-06）：本仓播放管线**必须先读出选中 URL 的 DASH 索引**
            // （`SingleFileDashMediaSourceFactory.readIndex`），而"第 1 条"经常是
            // PCDN（`os=mcdn`）或读不出索引的线路 ⇒ 报「单文件 dash 索引缺失」⇒ 全线打不开。
            // 旧默认档取第 3 条、长期可播，所以**没有证据时不该换掉它**。
            urls.getOrNull(2) ?: urls.getOrNull(1) ?: urls.first()
        } else {
            smartOrder(urls, healthByHost).first()
        }
        VideoCdnMode.BaseUrl -> urls.first()
        VideoCdnMode.Backup1 -> urls.getOrNull(1) ?: urls.first()
        VideoCdnMode.Backup2 -> urls.getOrNull(2) ?: urls.getOrNull(1) ?: urls.first()
        else -> rewritePlaybackUrl(urls, mode.host ?: return urls.first()) ?: urls.first()
    }
}

private fun rewritePlaybackUrl(
    urls: List<String>,
    targetHost: String
): String? {
    var mcdnResourceUrl: String? = null
    var mcdnUpgcxcodeUrl: String? = null
    for (url in urls) {
        if (REWRITABLE_UPGCXCODE_REGEX.containsMatchIn(url)) {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: continue
            if (parseQueryParams(uri.rawQuery)["os"].equals("mcdn", ignoreCase = true)) {
                mcdnUpgcxcodeUrl = url
            } else {
                return rewriteUriHost(uri, targetHost)
            }
            continue
        }
        if (MCDN_RESOURCE_REGEX.containsMatchIn(url)) {
            mcdnResourceUrl = url
            continue
        }
        if (url.contains("/upgcxcode/", ignoreCase = true)) {
            mcdnUpgcxcodeUrl = url
            continue
        }
        if (url.contains("szbdyd.com", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: continue
            val xyUsource = parseQueryParams(uri.rawQuery)["xy_usource"]?.takeIf(String::isNotBlank)
            return rewriteUriHost(
                uri = uri,
                host = xyUsource ?: targetHost,
                scheme = "https",
                port = 443
            )
        }
    }
    mcdnUpgcxcodeUrl?.let {
        val uri = runCatching { java.net.URI(it) }.getOrNull() ?: return null
        return rewriteUriHost(uri, targetHost)
    }
    mcdnResourceUrl?.let {
        val encoded = java.net.URLEncoder.encode(it, "UTF-8")
        return "https://$PROXY_TF_HOST?url=$encoded"
    }
    return null
}

private fun rewriteUriHost(
    uri: java.net.URI,
    host: String,
    scheme: String? = uri.scheme,
    port: Int = uri.port
): String {
    return java.net.URI(
        scheme,
        uri.userInfo,
        host,
        port,
        uri.path,
        uri.query,
        uri.fragment
    ).toString()
}

private fun parseQueryParams(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split("&")
        .mapNotNull { part ->
            val idx = part.indexOf('=')
            when {
                idx < 1 -> null
                else -> {
                    val key = java.net.URLDecoder.decode(part.substring(0, idx), "UTF-8")
                    val value = java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                    key to value
                }
            }
        }
        .toMap()
}

fun mergePlaybackUrls(primaryUrl: String?, backupUrls: List<String>): List<String> {
    return (listOfNotNull(primaryUrl) + backupUrls)
        .filter(String::isNotBlank)
        .distinct()
}
