package com.naaammme.bbspace.core.comment

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [readAtMost] 的边界测试（AP-17 ②：超长流只读 limit 附近的数据即抛，不读全流）。
 *
 * 病灶：原实现 `readBytes()` 先把整条 `content://` 流读成 ByteArray 再比上限 ——
 * 500MB 的流在被拒绝之前先吃掉 500MB 堆。修复后"拦得住读进来"。
 */
class ImageAttachmentHandlerLimitTest {

    /** 无限流：只要还在被读就吐数据（模拟"声明很长/实际很长"的 content:// 流）。 */
    private class EndlessStream : InputStream() {
        var consumed = 0L
        override fun read(): Int {
            consumed++
            return 0x7f
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = len.coerceAtMost(1024)
            consumed += n
            return n
        }
    }

    /** 抛出后仍可读的流包装：验证抛出时没有把底层流读完。 */
    private class CountingStream(private val delegate: InputStream) : InputStream() {
        var consumed = 0L
        override fun read(): Int {
            consumed++
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) consumed += n
            return n
        }
    }

    @Test
    fun endlessStreamThrowsBeforeReadingItAll() {
        val stream = EndlessStream()
        val limit = 5L * 1024 * 1024
        val exception = assertThrows(IllegalArgumentException::class.java) {
            readAtMost(stream, limit, "GIF图片不能超过5MB")
        }
        assertEquals("GIF图片不能超过5MB", exception.message)
        assertTrue(
            "远未读完就应抛出（消费 ${stream.consumed} 字节，远小于无限流）",
            stream.consumed in (limit + 1)..(limit + 128 * 1024)
        )
    }

    /** 恰好 limit：不许误判（正常图被拒 = 功能回归）。 */
    @Test
    fun exactlyLimitIsAccepted() {
        val limit = 1024L
        val data = ByteArray(limit.toInt()) { it.toByte() }
        val bytes = readAtMost(data.inputStream(), limit, "图片不能超过50MB")
        assertEquals(limit.toInt(), bytes.size)
    }

    /** 恰好 limit + 1：必须抛。 */
    @Test
    fun oneByteOverLimitThrows() {
        val limit = 1024L
        val data = ByteArray(limit.toInt() + 1)
        val counting = CountingStream(data.inputStream())
        assertThrows(IllegalArgumentException::class.java) {
            readAtMost(counting, limit, "图片不能超过50MB")
        }
        assertTrue(
            "消费量必须停在 limit 附近而不是全流（${counting.consumed} 字节）",
            counting.consumed <= limit + 128 * 1024
        )
    }

    /** 流中途断掉要如实抛 IOException，不许被吞成"超限"。 */
    @Test
    fun ioErrorsAreNotSwallowed() {
        val broken = object : InputStream() {
            private var reads = 0
            override fun read(): Int {
                reads++
                if (reads > 2) throw IOException("boom")
                return 0x41 // 先吐两个字节，然后断流
            }
        }
        assertThrows(IOException::class.java) {
            readAtMost(broken, 1024, "图片不能超过50MB")
        }
    }
}
