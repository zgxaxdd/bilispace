package com.naaammme.bbspace.core.comment

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AP-17：**至多读 limit 字节**的有界读取 —— 读到超过 limit 的第一块就抛
 * [IllegalArgumentException]，**绝不把整条流读进内存**。
 *
 * 原实现 `readBytes()` 先把整条 `content://` 流读成 ByteArray（500MB 的流 =
 * 在被拒绝之前先吃掉 500MB 堆），读完才比 50MB 上限 —— "拦得住存下来、
 * 拦不住读进来"。GIF 与静态图两条上限都走本 helper（读前就知道 limit）。
 *
 * 纯 JVM 可测（[ImageAttachmentHandlerLimitTest]）：计数流包装断言只消费
 * limit 附近的数据；恰好 limit 不许误判（正常图不被拒）。
 */
internal fun readAtMost(input: InputStream, limit: Long, limitMessage: String): ByteArray {
    val chunk = ByteArray(64 * 1024)
    val buffer = ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        total += n
        if (total > limit) {
            throw IllegalArgumentException(limitMessage)
        }
        buffer.write(chunk, 0, n)
    }
    return buffer.toByteArray()
}

@Singleton
class ImageAttachmentHandler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    class PreparedImage(
        val bytes: ByteArray,
        val mimeType: String
    )

    /** 校验大小/尺寸，返回原始字节 */
    suspend fun prepare(uri: Uri): PreparedImage = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        readImage(uri, mimeType)
    }

    private fun readImage(uri: Uri, mimeType: String): PreparedImage {
        val isGif = mimeType.contains("gif", ignoreCase = true)
        val maxSize = if (isGif) MAX_GIF_SIZE else MAX_STATIC_SIZE
        // AP-17：limit 在读**之前**就传入 —— 超限在读取过程中即抛，不再先吃满内存
        val limitMessage = if (isGif) "GIF图片不能超过5MB" else "图片不能超过50MB"
        val bytes = readBytes(uri, maxSize, limitMessage)
        if (mimeType.contains("gif", ignoreCase = true)) {
            return PreparedImage(bytes, mimeType)
        }
        val (width, height) = decodeBounds(bytes)
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            throw IllegalArgumentException("图片尺寸过小，宽高至少${MIN_DIMENSION}px")
        }
        return PreparedImage(bytes, mimeType)
    }

    private fun readBytes(uri: Uri, limit: Long, limitMessage: String): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { readAtMost(it, limit, limitMessage) }
            ?: throw IllegalArgumentException("无法读取图片")
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val width = opts.outWidth
        val height = opts.outHeight
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("无法解码图片")
        }
        return width to height
    }

    companion object {
        private const val MAX_STATIC_SIZE = 50L * 1024 * 1024
        private const val MAX_GIF_SIZE = 5L * 1024 * 1024
        private const val MIN_DIMENSION = 10
    }
}
