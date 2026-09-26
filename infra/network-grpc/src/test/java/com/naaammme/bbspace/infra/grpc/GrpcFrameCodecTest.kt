package com.naaammme.bbspace.infra.grpc

import com.naaammme.bbspace.infra.network.BiliApiException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gRPC 帧编解码必须**自己写的长度域自己要用**，坏帧要抛协议错误（BACKLOG ST-14）。
 *
 * 纯 JVM：codec 只碰 ByteArray 与 java.util.zip，没有 Android 依赖。
 */
class GrpcFrameCodecTest {

    private fun bytes(
        size: Int
    ): ByteArray = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun roundTripForRawPayload() {
        val payload = bytes(200)
        val frame = GrpcFrameCodec.encode(payload)
        assertEquals(false, frame.compressed)
        assertArrayEquals(payload, GrpcFrameCodec.decode(frame.frame))
    }

    /** 属性式：多种长度都必须在阈值两侧各自成立（阈值 1000 是个边界）。 */
    @Test
    fun roundTripHoldsAcrossTheCompressionThreshold() {
        listOf(0, 1, 999, 1000, 1001, 4096, 70_000).forEach { size ->
            val payload = bytes(size)
            val result = GrpcFrameCodec.encode(payload)
            val decoded = GrpcFrameCodec.decode(
                result.frame,
                if (result.compressed) "gzip" else "identity"
            )
            assertArrayEquals("长度 $size 往返不一致", payload, decoded)
        }
    }

    @Test
    fun headerDeclaresThePayloadLength() {
        val payload = bytes(37)
        val frame = GrpcFrameCodec.encode(payload).frame
        assertEquals(5, frame.size - payload.size)
        assertEquals(0, frame[0].toInt())
        val declared = ((frame[1].toInt() and 0xFF) shl 24) or
            ((frame[2].toInt() and 0xFF) shl 16) or
            ((frame[3].toInt() and 0xFF) shl 8) or
            (frame[4].toInt() and 0xFF)
        assertEquals(payload.size, declared)
    }

    /** 这就是原缺陷的形状：截断帧以前会被当成 payload 交给 protobuf/gzip。 */
    @Test
    fun truncatedFrameIsAProtocolErrorNotASilentGarbagePayload() {
        val frame = GrpcFrameCodec.encode(bytes(100)).frame
        val truncated = frame.copyOfRange(0, frame.size - 20)
        val err = assertThrows(BiliApiException::class.java) { GrpcFrameCodec.decode(truncated) }
        assertTrue("message 要说清声明与可用字节：${err.message}", err.message.contains("声明 100"))
        assertTrue(err.message.contains("可用 80"))
    }

    @Test
    fun frameShorterThanHeaderIsAProtocolError() {
        // 以前抛的是 IllegalStateException，上层没法和"代码 bug"区分
        val err = assertThrows(BiliApiException::class.java) {
            GrpcFrameCodec.decode(byteArrayOf(0, 0, 0))
        }
        assertTrue(err.message.contains("过短"))
    }

    /** 长度域最高位为 1 时必须按无符号解释，否则负数会绕过"声明 > 可用"的检查。 */
    @Test
    fun highBitLengthIsNotTreatedAsNegative() {
        val hostile = byteArrayOf(0, -1, -1, -1, -1, 1, 2, 3)
        val err = assertThrows(BiliApiException::class.java) { GrpcFrameCodec.decode(hostile) }
        assertTrue("应按 4294967295 而不是 -1 判定：${err.message}", err.message.contains("4294967295"))
    }

    /** 只取声明的那一帧，尾随字节不许混进 payload。 */
    @Test
    fun trailingBytesAreNotFoldedIntoThePayload() {
        val payload = bytes(10)
        val frame = GrpcFrameCodec.encode(payload).frame
        val withTrailer = frame + byteArrayOf(9, 9, 9)
        assertArrayEquals(payload, GrpcFrameCodec.decode(withTrailer))
    }

    @Test
    fun compressedWithoutEncodingHeaderIsAProtocolError() {
        val result = GrpcFrameCodec.encode(bytes(2000))
        assertTrue(result.compressed)
        val err = assertThrows(BiliApiException::class.java) {
            GrpcFrameCodec.decode(result.frame, null)
        }
        assertTrue(err.message.contains("grpc-encoding"))
    }

    @Test
    fun emptyPayloadIsLegalAndDecodesToEmpty() {
        val frame = GrpcFrameCodec.encode(ByteArray(0)).frame
        assertArrayEquals(ByteArray(0), GrpcFrameCodec.decode(frame))
    }
}
