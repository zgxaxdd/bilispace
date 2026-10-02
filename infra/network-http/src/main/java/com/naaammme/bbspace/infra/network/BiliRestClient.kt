package com.naaammme.bbspace.infra.network

import com.naaammme.bbspace.infra.crypto.AppSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B站 RESTful API 客户端
 *
 * 按显式传入的 profile 选择 appkey 和 appsec
 * 对参数做签名并按 GET/POST 的协议形态发送
 * 统一注入 Header 并检查 code
 *
 * 公参不在这里隐式补，调用方先通过参数构建器准备好业务参数
 */
@Singleton
class BiliRestClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val headerBuilder: BiliHeaderBuilder
) {
    /**
     * 发送签名后的 POST 请求并要求 code == 0
     *
     * POST 参数会被签名后直接写入请求体
     */
    suspend fun postSigned(
        url: String,
        params: Map<String, String>,
        profile: BiliRestProfile = BiliRestProfile.APP
    ): JSONObject {
        return requireSuccess(postSignedRaw(url, params, profile))
    }

    /**
     * 发送签名后的 POST 请求并读取指定响应头
     *
     * AP-21：签名与请求体构建移入 IO 块（理由同 [getSigned]）。
     */
    suspend fun postSignedReadHeader(
        url: String,
        params: Map<String, String>,
        headerName: String,
        profile: BiliRestProfile = BiliRestProfile.APP
    ): Pair<JSONObject, String> = withContext(Dispatchers.IO) {
        val signedBody = AppSigner.sign(params, profile.appKey, profile.appSec)
        val requestBody = signedBody.toRequestBody(null)
        okHttpClient.newCall(
            Request.Builder().url(url).post(requestBody).withHeaders(profile).build()
        ).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: throw BiliApiException(-1, "Empty response"))
            requireSuccess(json) to resp.header(headerName).orEmpty()
        }
    }

    /**
     * 发送签名后的 GET 请求并要求 code == 0
     *
     * GET 参数会被签名后直接拼进 URL query。
     *
     * AP-21：签名（MD5）与 header 构建（约 8 次 SP 读）**在 IO 块内**执行 ——
     * 调用方（首页/排行 VM）在 Main.immediate 上直调，原先这两段全跑在主线程。
     * 签名输入与结果完全不变，只改执行线程（AP-T9 真机核对鉴权一致性）。
     */
    suspend fun getSigned(
        url: String,
        params: Map<String, String>,
        profile: BiliRestProfile = BiliRestProfile.APP
    ): JSONObject = withContext(Dispatchers.IO) {
        val signedQuery = AppSigner.sign(params, profile.appKey, profile.appSec)
        val fullUrl = "$url?$signedQuery"
        requireSuccess(executeJson(Request.Builder().url(fullUrl).get().withHeaders(profile).build()))
    }

    /**
     * 发送签名后的 POST 请求但不检查业务 code
     *
     * 用于二维码轮询这类需要调用方自行处理特殊返回码的接口
     *
     * AP-21：签名与请求体构建移入 IO 块（理由同 [getSigned]）。
     */
    suspend fun postSignedRaw(
        url: String,
        params: Map<String, String>,
        profile: BiliRestProfile = BiliRestProfile.APP
    ): JSONObject = withContext(Dispatchers.IO) {
        val signedBody = AppSigner.sign(params, profile.appKey, profile.appSec)
        val requestBody = signedBody.toRequestBody(null)
        executeJson(Request.Builder().url(url).post(requestBody).withHeaders(profile).build())
    }

    /**
     * 发送 Web 端 POST 表单请求（不做 appkey 签名）
     *
     * Web 端点鉴权依赖 Cookie（SESSDATA）与 csrf（bili_jct），
     * 由调用方把 Cookie/UA/Referer 显式放入 headers，这里不做任何隐式注入
     */
    suspend fun postWebForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap()
    ): JSONObject {
        val builder = Request.Builder()
            .url(buildUrl(url, query))
            .post(form.toWebFormBody())
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        return requireSuccess(executeJson(builder.build()))
    }

    /**
     * 发送 Web 端 POST JSON 请求（不做 appkey 签名），用于动态发布等 JSON body 端点
     */
    suspend fun postWebJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap()
    ): JSONObject {
        val jsonBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(buildUrl(url, query))
            .post(jsonBody)
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        return requireSuccess(executeJson(builder.build()))
    }

    /**
     * 发送 Web 端 GET 请求（不做 appkey 签名）
     */
    suspend fun getWeb(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): JSONObject {
        val builder = Request.Builder().url(buildUrl(url, query)).get()
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        return requireSuccess(executeJson(builder.build()))
    }

    private fun Map<String, String>.toWebFormBody(): RequestBody =
        entries.joinToString("&") { "${webEncode(it.key)}=${webEncode(it.value)}" }
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

    private fun buildUrl(url: String, query: Map<String, String>): String {
        if (query.isEmpty()) return url
        val queryString = query.entries.joinToString("&") { "${webEncode(it.key)}=${webEncode(it.value)}" }
        return "$url?$queryString"
    }

    private fun webEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * 检查 B站标准 JSON 响应的 code 字段
     */
    private fun requireSuccess(json: JSONObject): JSONObject {
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw BiliApiException(code, json.optString("message", "Unknown error"))
        }
        return json
    }

    /**
     * 在 IO 线程执行请求并解析 JSON 响应体
     */
    private suspend fun executeJson(request: Request): JSONObject {
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { resp ->
                JSONObject(resp.body?.string() ?: throw BiliApiException(-1, "Empty response"))
            }
        }
    }

    /**
     * 统一附加 RESTful 请求 Header
     */
    private fun Request.Builder.withHeaders(profile: BiliRestProfile): Request.Builder {
        headerBuilder.build(profile).forEach { (key, value) -> addHeader(key, value) }
        return this
    }
}
