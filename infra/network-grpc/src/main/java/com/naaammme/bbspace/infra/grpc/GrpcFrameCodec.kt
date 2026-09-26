package com.naaammme.bbspace.infra.grpc

import com.naaammme.bbspace.infra.network.BiliApiException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * gRPC Frame 编解码器
 * B站使用 gRPC over HTTP/1.1，需要手动构建 5 字节 frame 头部
 *
 * 数据包格式: [1字节压缩标志][4字节数据长度(big-endian)][实际数据]
 *
 * 压缩策略:
 * - protobuf > 1000 字节 → gzip 压缩（标志=1）
 * - protobuf ≤ 1000 字节 → 不压缩（标志=0）
 */
object GrpcFrameCodec {

    private const val COMPRESSION_THRESHOLD = 1000

    /** [1 字节压缩标志][4 字节长度][载荷] */
    private const val FRAME_HEADER_BYTES = 5

    data class EncodeResult(
        val frame: ByteArray,
        val compressed: Boolean
    )

    /**
     * 编码：根据 payload 大小决定是否 gzip 压缩
     * - > 1000 字节：gzip 压缩，标志=1
     * - ≤ 1000 字节：不压缩，标志=0
     */
    fun encode(protobufBytes: ByteArray): EncodeResult {
        return if (protobufBytes.size > COMPRESSION_THRESHOLD) {
            encodeCompressed(protobufBytes)
        } else {
            encodeRaw(protobufBytes)
        }
    }

    /**
     * gzip 压缩模式
     */
    private fun encodeCompressed(protobufBytes: ByteArray): EncodeResult {
        val compressed = gzipCompress(protobufBytes)
        val frame = ByteArrayOutputStream(5 + compressed.size)
        frame.write(1) // 压缩标志 = 1
        frame.write(toLengthBytes(compressed.size))
        frame.write(compressed)
        return EncodeResult(frame.toByteArray(), compressed = true)
    }

    /**
     * 无压缩模式
     */
    private fun encodeRaw(protobufBytes: ByteArray): EncodeResult {
        val frame = ByteArrayOutputStream(5 + protobufBytes.size)
        frame.write(0) // 压缩标志 = 0
        frame.write(toLengthBytes(protobufBytes.size))
        frame.write(protobufBytes)
        return EncodeResult(frame.toByteArray(), compressed = false)
    }

    /**
     * 解码：**按自己写的长度域取载荷**，畸形帧抛 `BiliApiException`。
     *
     * 原先 `encode` 认真写了 4 字节长度，`decode` 却直接 `copyOfRange(5, size)` 把它丢掉 ——
     * 于是"服务端少发一段"这种截断不会被识别成坏帧，而是把剩下的字节全当 payload 交给
     * protobuf/gzip 去解；抛出来的是 `IllegalStateException` / `ZipException` 这类
     * 不带协议语义的异常，上层无法区分"网络/协议坏了"与"代码错了"。
     *
     * 现在三条都受控：不足一个帧头、长度域超过实际可用字节、声明压缩却没有 `grpc-encoding`，
     * 一律 `BiliApiException`（与 `TicketGenerator` 里那处"短帧必须抛错"的同类修复一致）。
     *
     * 取 `declared` 个字节而不是 `size - 5`：多帧拼接的响应里，尾随的下一帧今天会被并进
     * 当前 payload 一起解（本来就是错的），现在只取声明的那一帧。本仓的调用方都是
     * 一元 RPC（一发一收一帧），这个改变只影响"本来就会解析失败"的输入。
     */
    fun decode(responseBytes: ByteArray, grpcEncoding: String? = null): ByteArray {
        if (responseBytes.size < FRAME_HEADER_BYTES) {
            throw BiliApiException(
                code = -1,
                message = "gRPC 响应体过短 (${responseBytes.size} 字节)，非有效 frame"
            )
        }

        val compressionFlag = responseBytes[0].toInt()
        val available = responseBytes.size - FRAME_HEADER_BYTES
        val declared = unsignedLength(responseBytes)
        if (declared > available) {
            throw BiliApiException(
                code = -1,
                message = "gRPC 帧长度域与实际不符：声明 $declared，可用 $available"
            )
        }

        val payload = responseBytes.copyOfRange(FRAME_HEADER_BYTES, FRAME_HEADER_BYTES + declared.toInt())
        return decodePayload(compressionFlag, payload, grpcEncoding)
    }

    fun decodePayload(
        compressionFlag: Int,
        payload: ByteArray,
        grpcEncoding: String? = null
    ): ByteArray {
        return if (compressionFlag == 0) {
            payload
        } else if (grpcEncoding == "gzip") {
            GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
        } else {
            throw BiliApiException(
                code = -1,
                message = "响应声明已压缩但没有可用的 grpc-encoding 头"
            )
        }
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun toLengthBytes(length: Int): ByteArray {
        return byteArrayOf(
            ((length shr 24) and 0xFF).toByte(),
            ((length shr 16) and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte()
        )
    }

    /**
     * 读 4 字节大端长度域，按**无符号**解释。
     *
     * 用 Int 直接拼会在最高位为 1 时变成负数（例如声明 2147483648 字节读成 -2147483648），
     * 那样"声明 > 可用"的判断就会被负数绕过，坏帧反而通过。
     */
    private fun unsignedLength(bytes: ByteArray): Long {
        return ((bytes[1].toLong() and 0xFF) shl 24) or
            ((bytes[2].toLong() and 0xFF) shl 16) or
            ((bytes[3].toLong() and 0xFF) shl 8) or
            (bytes[4].toLong() and 0xFF)
    }
}
