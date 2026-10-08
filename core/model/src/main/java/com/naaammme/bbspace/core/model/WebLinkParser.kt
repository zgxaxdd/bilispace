package com.naaammme.bbspace.core.model

import java.net.URI
import java.util.Locale

object WebLinkParser {

    /** opusType：图文动态（与动态流卡片同口径）。 */
    private const val OPUS_TYPE_DYNAMIC = 0

    /** opusType：专栏文章（与 `HistoryTarget.Article` → `navigateToDynamicDetail(id, 1)` 同口径）。 */
    private const val OPUS_TYPE_ARTICLE = 1

    /**
     * 站内路由解析。语义是"这条 URL **能不能映射成**站内路由"：
     * 映射不了就返回 [WebLinkTarget.Stay]，由应用内 WebView 继续加载原 URL
     * （b23.tv 短链的 302 正是靠这条活路，见 `WebViewScreen.routeUrl`）。
     *
     * ## B-1：host 必须按域名边界校验
     *
     * 修复前 `host.startsWith("space")` / `startsWith("live")` 两个析取项**不依赖
     * scheme 与域名**，于是 `https://spaceevil.example/2` 会被解析成 `ToSpace(2)`；
     * `host.endsWith("bilibili.com")` 同样会命中 `notbilibili.com`。
     * 真机复现（2026-10-04）：用显式组件投递 `https://spaceevil.example/2`，
     * 应用直接进了 UID 2 的空间页。
     */
    fun parse(url: String): WebLinkTarget {
        val uri = runCatching { URI(url) }.getOrNull() ?: return WebLinkTarget.Stay
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().trimEnd('/')
        return when {
            uri.scheme == "bilibili" && host == "video" -> parseVideo(uri, path)
            uri.scheme == "bilibili" && host == "bangumi" -> parseBangumi(uri, path)
            (uri.scheme == "bilibili" && host == "space") ||
                (isBiliHost(host) && host.startsWith("space.")) ->
                path.substringAfterLast('/').toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let(WebLinkTarget::ToSpace)
                    ?: WebLinkTarget.Stay
            (uri.scheme == "bilibili" && host == "live") ||
                (isBiliHost(host) && host.startsWith("live.")) ->
                path.substringAfterLast('/').toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let(WebLinkTarget::ToLive)
                    ?: WebLinkTarget.Stay
            isBiliHost(host) && path.startsWith("/video/") -> parseVideo(uri, path)
            isBiliHost(host) && path.startsWith("/bangumi/play/") -> parseBangumi(uri, path)
            // F-2（2026-10-05）：专栏 / 图文动态。参考 `_ref/BiliPai` v0.2.9 的
            // OPUS_DYNAMIC_REGEX（`bilibili.com/opus/<id>`）；专栏沿用本仓既有口径
            // （read/cv 的 cv 号即 opusId，opusType = 1，见 AppNavHost 的 HistoryTarget.Article）。
            isBiliHost(host) && path.startsWith("/opus/") ->
                parseOpus(path.substringAfterLast('/'), OPUS_TYPE_DYNAMIC)
            isBiliHost(host) && path.startsWith("/read/cv") ->
                parseOpus(path.substringAfterLast('/').removePrefix("cv"), OPUS_TYPE_ARTICLE)
            // bilibili:// 形态的站内 deep link（本仓历史/收藏链路一直在用的两种）
            uri.scheme == "bilibili" && host == "opus" && path.startsWith("/detail/") ->
                parseOpus(path.substringAfterLast('/'), OPUS_TYPE_ARTICLE)
            uri.scheme == "bilibili" && host == "article" ->
                parseOpus(path.substringAfterLast('/'), OPUS_TYPE_ARTICLE)
            isShortHost(host) -> WebLinkTarget.Stay
            else -> WebLinkTarget.Stay
        }
    }

    /**
     * opus 内容页（专栏 / 图文动态）解析。
     *
     * **不覆盖 `t.bilibili.com/<dynId>`**：那是动态 id，而本仓动态详情走的是
     * `OpusDetailReq`（`DynamicRepository` 里留着"以后是否迁到 DynDetailReq"的 TODO），
     * 用错接口只会给用户一个失败页 —— 所以这类链接继续留在浏览器，也不注册进 manifest：
     * 宁可不接管，也不"接管后报错"。
     */
    private fun parseOpus(rawId: String, opusType: Int): WebLinkTarget {
        val id = rawId.toLongOrNull() ?: return WebLinkTarget.Stay
        if (id <= 0L) return WebLinkTarget.Stay
        return WebLinkTarget.ToDynamic(opusId = id, opusType = opusType)
    }

    /**
     * **外部入口专用**（Manifest 声明的深链 / L-1）。与 [parse] 的区别只有一条：
     * "本应用声明接管、但解析不出站内路由"的链接返回 [WebLinkTarget.External]，
     * 由调用方回落系统浏览器 —— 修复前这种情况返回 [WebLinkTarget.Stay]，
     * 调用方把它丢掉，用户看到的是"点了链接应用装死、无提示、不回落"。
     *
     * 只覆盖本应用**确实声明了**的入口（`bilibili://` scheme / `*.bilibili.com` /
     * `b23.tv`）；`notbilibili.com` 一类不在声明范围内，仍返回 [WebLinkTarget.Stay]。
     */
    fun parseExternal(url: String): WebLinkTarget {
        val target = parse(url)
        if (target != WebLinkTarget.Stay) return target
        val uri = runCatching { URI(url) }.getOrNull() ?: return WebLinkTarget.Stay
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        return if (uri.scheme == "bilibili" || isBiliHost(host) || isShortHost(host)) {
            WebLinkTarget.External
        } else {
            WebLinkTarget.Stay
        }
    }

    /** b23.tv 短链：必须先解析 302 目标才能拿到站内路由（B-2）。 */
    fun isShortLink(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty() }.getOrNull().orEmpty()
        return isShortHost(host.lowercase(Locale.ROOT))
    }

    /**
     * B 站主域：**按域名边界**匹配。
     *
     * 不能用 `endsWith("bilibili.com")` —— 它会把 `notbilibili.com` / `evilbilibili.com`
     * 当成站内域名（B-1）。
     */
    private fun isBiliHost(host: String): Boolean =
        host == "bilibili.com" || host.endsWith(".bilibili.com")

    /** b23.tv 短链域（同样按边界匹配）。 */
    private fun isShortHost(host: String): Boolean =
        host == "b23.tv" || host.endsWith(".b23.tv")

    private val BV_REGEX = Regex("BV[0-9A-Za-z]+")

    private fun parseVideo(
        uri: URI,
        path: String
    ): WebLinkTarget {
        val rawUrl = uri.toString()
        val lastSegment = path.substringAfterLast('/')
        val bvid = VideoTargetTool.bvid(rawUrl)
            ?: lastSegment.takeIf { BV_REGEX.matches(it) }
        val aid = VideoTargetTool.aid(rawUrl)
            ?: lastSegment.removePrefix("av").toLongOrNull()
        val cid = VideoTargetTool.cid(rawUrl) ?: 0L
        if (aid == null && bvid == null) return WebLinkTarget.Stay
        return WebLinkTarget.ToVideo(
            VideoTarget.Ugc(
                aid = aid ?: 0L,
                cid = cid,
                bvid = bvid,
                src = VideoTargetTool.feed()
            )
        )
    }

    private fun parseBangumi(
        uri: URI,
        path: String
    ): WebLinkTarget {
        val epId = VideoTargetTool.epId(uri.toString())
        if (epId != null) {
            return WebLinkTarget.ToVideo(
                VideoTarget.Pgc(epId = epId, src = VideoTargetTool.feed())
            )
        }
        val seasonId = path.substringAfterLast('/')
            .removePrefix("ss").toLongOrNull()
            ?: return WebLinkTarget.Stay
        return WebLinkTarget.ToVideo(
            VideoTarget.Pgc(
                seasonId = seasonId,
                src = VideoTargetTool.feed()
            )
        )
    }
}
