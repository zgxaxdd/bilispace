package com.naaammme.bbspace.core.sponsorblock

import android.content.Context
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小电视空降助手社区服务器（bsbsb.top）客户端。
 * 协议参考：https://github.com/hanydd/BilibiliSponsorBlock/wiki/API
 */
@Singleton
class SponsorBlockClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "SponsorBlockClient"
        const val DEFAULT_SERVER = "https://www.bsbsb.top"
    }

    private suspend fun serverUrl(): String =
        appSettings.sponsorBlockServerUrl.first()
            .trim()
            .trimEnd('/')
            .ifBlank { DEFAULT_SERVER }

    private suspend fun userId(): String = appSettings.ensureSponsorBlockUserId()

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun Request.Builder.sbHeaders(): Request.Builder =
        header("origin", "bbspace")
            .header("x-ext-version", versionName())

    /**
     * 获取视频的社区标注片段。404 视为「无片段」，返回空列表而不是错误。
     */
    suspend fun getSegments(bvid: String, cid: Long): Result<List<SbSegment>> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                var url = "${serverUrl()}/api/skipSegments?videoID=$bvid"
                if (cid > 0L) url += "&cid=$cid"
                val request = Request.Builder().url(url).sbHeaders().get().build()
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> emptyList()
                        response.isSuccessful -> parseSegments(response.body?.string())
                        else -> error("服务器错误 ${response.code}")
                    }
                }
            }.onFailure { e ->
                Logger.e(TAG, e as? Exception) { "获取空降片段失败" }
            }
        }

    private fun parseSegments(body: String?): List<SbSegment> {
        if (body.isNullOrBlank()) return emptyList()
        val array = JSONArray(body)
        val result = mutableListOf<SbSegment>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val segmentArr = item.optJSONArray("segment") ?: continue
            val start = segmentArr.optDouble(0, Double.NaN)
            if (start.isNaN()) continue
            val end = if (segmentArr.length() > 1) segmentArr.optDouble(1, start) else start
            val category = SbCategory.fromWire(item.optString("category")) ?: continue
            val actionType = SbActionType.fromWire(item.optString("actionType")) ?: continue
            val uuid = item.optString("UUID").takeIf(String::isNotBlank) ?: continue
            val cid = when (val rawCid = item.opt("cid")) {
                is Number -> rawCid.toLong()
                is String -> rawCid.toLongOrNull() ?: 0L
                else -> 0L
            }
            result += SbSegment(
                startSec = start,
                endSec = end,
                cid = cid,
                uuid = uuid,
                category = category,
                actionType = actionType,
                locked = item.optInt("locked", 0) != 0,
                votes = item.optInt("votes", 0),
                videoDurationSec = item.optDouble("videoDuration", 0.0)
            )
        }
        return result
    }

    /**
     * 上报一次跳过，用于社区统计（fire-and-forget，失败静默）。
     */
    suspend fun viewedSegment(uuid: String) {
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val url = "${serverUrl()}/api/viewedVideoSponsorTime?UUID=$uuid"
                val request = Request.Builder().url(url).sbHeaders()
                    .post(ByteArray(0).toRequestBody())
                    .build()
                okHttpClient.newCall(request).execute().use { it.close() }
            }.onFailure { e ->
                Logger.e(TAG, e as? Exception) { "上报跳过失败" }
            }
        }
    }

    /**
     * 检查服务器连通性（GET /api/ready）。
     */
    suspend fun checkServer(rawUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val base = rawUrl.trim().trimEnd('/').ifBlank { DEFAULT_SERVER }
            val request = Request.Builder().url("$base/api/ready").sbHeaders().get().build()
            okHttpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
