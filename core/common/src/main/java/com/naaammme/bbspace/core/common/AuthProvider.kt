package com.naaammme.bbspace.core.common

/**
 * 登录态提供者
 * 网络层自动读取，调用方无需手动传递
 */
interface AuthProvider {
    val mid: Long
    val accessToken: String

    /** 是否具备 Web 端交互所需凭证（Cookie + bili_jct 齐备） */
    val hasWebCredential: Boolean

    /** Web 端请求的 Cookie 请求头（SESSDATA 等） */
    fun cookieHeader(): String

    /** Web 端 csrf 令牌（bili_jct cookie 值） */
    fun csrfToken(): String
}

/**
 * Web 端点（点赞三连/收藏管理/评论赞踩/动态互动）的公共请求头
 *
 * Web 端点鉴权依赖 Cookie 与 csrf，与 App 协议的 appkey 签名不同，
 * 这里统一构造，避免各仓库重复实现
 */
object BiliWebHeaderTool {
    fun build(cookieHeader: String, referer: String): Map<String, String> = mapOf(
        "cookie" to cookieHeader,
        "user-agent" to BiliConstants.WEB_USER_AGENT,
        "referer" to referer,
        "origin" to BiliConstants.BASE_URL_WEB
    )
}
