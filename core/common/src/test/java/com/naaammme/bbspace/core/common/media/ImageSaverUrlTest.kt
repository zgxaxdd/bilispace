package com.naaammme.bbspace.core.common.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片地址必须走**受控失败**，而不是把 Java 内部类型名弹给用户（BACKLOG ST-10）。
 *
 * 唯一的调用方 `PreviewImageGrid` 是 `Toast.makeText(context, err.message ?: "保存图片失败")`，
 * 所以异常 message 就是用户看到的文案。
 *
 * 这里只测 `openImageConnection`：它不碰 Context/网络（`openConnection` 只建对象不连接），
 * 因此能在纯 JVM 上跑。
 */
class ImageSaverUrlTest {

    @Test
    fun nonHttpSchemeFailsWithAMeaningfulMessage() {
        val err = assertThrows(IllegalArgumentException::class.java) {
            ImageSaver.openImageConnection("file:///sdcard/DCIM/a.jpg")
        }
        assertEquals("不支持的图片协议", err.message)
    }

    /**
     * `content:` 在 JVM 上没有协议处理器，会先撞 `MalformedURLException`（"unknown protocol"），
     * 而在 Android 上它有 handler、会走到"不支持的图片协议"那一条 ——
     * 所以这里断言的是**契约**（受控失败、且绝不是 ClassCastException），不是具体走哪个分支。
     */
    @Test
    fun contentSchemeFailsControlledAndNeverWithACastMessage() {
        val err = assertThrows(IllegalArgumentException::class.java) {
            ImageSaver.openImageConnection("content://media/external/images/media/1")
        }
        val message = err.message.orEmpty()
        assertTrue("message 要能直接弹给用户，不能是 Java 内部文案：$message", message.isNotBlank())
        assertTrue(
            "绝不能把 ClassCastException 的原文交给 Toast：$message",
            !message.contains("cannot be cast", ignoreCase = true)
        )
    }

    /** 空串/怪值：originImageUrl 取不到时会把原串直接传进来，所以这条路一定会被走到。 */
    @Test
    fun malformedUrlFailsControlledNotWithJavaInternals() {
        val err = assertThrows(IllegalArgumentException::class.java) {
            ImageSaver.openImageConnection("")
        }
        assertEquals("无效的图片地址", err.message)
        assertTrue("原始异常要留在 cause 里，日志才有得查", err.cause != null)
    }

    @Test
    fun httpUrlGetsTheConfiguredConnection() {
        val conn = ImageSaver.openImageConnection("https://i0.hdslb.com/bfs/face/a.jpg")
        // openConnection 只建对象、不联网，所以这条在离线单测里也成立
        assertEquals("https", conn.url.protocol)
        assertEquals(15_000, conn.connectTimeout)
        assertEquals(30_000, conn.readTimeout)
        assertTrue(conn.instanceFollowRedirects)
    }
}
