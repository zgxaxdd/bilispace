package com.naaammme.bbspace.core.common.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLConnection
import java.util.Locale

object ImageSaver {
    fun saveUrl(
        context: Context,
        url: String,
        album: String = "BBSpace",
        prefix: String = "bbspace"
    ): Uri {
        val rawUrl = originImageUrl(url) ?: url
        val resolver = context.contentResolver
        var outUri: Uri? = null
        return runCatching {
            val conn = openImageConnection(rawUrl)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    error("下载图片失败 $code")
                }
                val mime = resolveMimeType(rawUrl, conn.contentType)
                val ext = resolveExt(rawUrl, mime)
                val name = "${prefix}_${System.currentTimeMillis()}.$ext"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$album"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val savedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("创建图片失败")
                outUri = savedUri
                conn.inputStream.use { input ->
                    resolver.openOutputStream(savedUri, "w")?.use { output ->
                        input.copyTo(output)
                    } ?: error("打开图片文件失败")
                }
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(savedUri, done, null, null)
                savedUri
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            outUri?.let { resolver.delete(it, null, null) }
        }.getOrThrow()
    }

    /**
     * 只接受 http/https 的图片地址，别的协议给一句**人话**失败。
     *
     * 原先这一行直接写在 `runCatching` 之外：`(URL(rawUrl).openConnection() as HttpURLConnection)`。
     * scheme 是服务端可控的（`originImageUrl` 会改写图床地址，取不到就用原串，原串可能是空串或怪值），
     * 于是 `file:` / `content:` 之类会抛 ClassCastException，而调用方
     * （`PreviewImageGrid`）是把 `err.message` 直接弹进 Toast 的 ——
     * 用户看到一行 Java 内部类型名，真正该说的是"这个地址存不了"。
     * 顺带它还在 runCatching 之外：连"受控失败"都没进过失败通道。
     */
    internal fun openImageConnection(rawUrl: String): HttpURLConnection {
        val url = try {
            URL(rawUrl)
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException("无效的图片地址", e)
        }
        val conn = url.openConnection()
        if (conn !is HttpURLConnection) {
            throw IllegalArgumentException("不支持的图片协议")
        }
        return conn.apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
    }

    private fun resolveMimeType(
        url: String,
        contentType: String?
    ): String {
        val mime = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (mime != null) {
            return mime
        }
        return URLConnection.guessContentTypeFromName(url.substringBefore('?')) ?: "image/jpeg"
    }

    private fun resolveExt(
        url: String,
        mime: String
    ): String {
        val ext = url
            .substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        if (ext in SUPPORTED_EXT) {
            return ext
        }
        return when (mime.lowercase(Locale.ROOT)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            else -> "jpg"
        }
    }

    private val SUPPORTED_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
}
