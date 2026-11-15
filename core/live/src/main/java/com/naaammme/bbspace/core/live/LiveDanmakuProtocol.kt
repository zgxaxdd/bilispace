package com.naaammme.bbspace.core.live

import org.brotli.dec.BrotliInputStream
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * Bilibili 直播弹幕**线协议**的纯解码逻辑（B7，2026-10-13 上游 v0.3.6 同步）。
 *
 * ## 为什么要从 `LiveRoomMessageRepository` 里抽出来
 *
 * 这段逻辑原本是该 Hilt 类的 private 方法，于是**完全不可测**：它的输入是字节流、
 * 输出是 JSON 列表，没有任何 Android 依赖，却因为住在注入类里而只能靠"扫源码"式
 * 结构守卫看着（那种守卫证明不了"恶意包不会打崩解析器"）。
 *
 * 上游 BiliPai 早就把它放在独立的 `DanmakuProtocol` object 里，并配了
 * `DanmakuProtocolLimitsTest`（含真实 brotli wire payload 用例）。本仓抽出来后
 * 同样可以写**真字节级**的单测 —— 这才是这类"协议健壮性"缺陷该有的验证方式。
 *
 * ## 本仓与上游的差异（都是本仓形态决定的，不是偏离）
 *
 * 1. **不引入 `sequence` 字段**：上游 `Packet` 带 `sequence`，本仓业务侧从来不读它
 *    （`LivePacket` 只有 version/op/payload），所以这里也不带 —— 少一个不用的字段
 *    比"为了对齐上游"多一个字段好。
 * 2. **每帧包数上限放在循环条件里**：上游写在 `while` 条件中，语义相同。
 * 3. **`headLength > totalLength` 时 `break` 而不是 `continue`**：本仓原实现就是
 *    `break`，更保守（一旦头部非法就停止解析该缓冲，不冒险按可疑长度跳位）。
 *    **这是有意保留的更严取舍，不是缺陷。**
 * 4. **压缩层超深度在入口返回空列表**：本仓在这一层已经解完压了（`inflate` 在调用点
 *    执行），拦在入口才避免"先解压再丢弃"的浪费；上游在解码循环里 `continue`。
 *
 * ## 为什么这些上限是必需的（缺陷原文）
 *
 * 原实现 `unpackNestedPackets` ↔ `unpackBusinessPayload` **互相递归且没有深度参数**
 * ⇒ 一个精心构造的包（每层再套一层压缩、每层都合法）能让递归无限展开直到
 * `StackOverflowError`。包体合法性校验（长度区间）与嵌套深度**无关**，所以每一层
 * 都能通过校验。同理，单个 2MB 帧里可以塞进几十万个小包，逐个 JSON 解析会长时间
 * 占用 IO 线程。
 */
internal object LiveDanmakuProtocol {

    const val HEADER_SIZE = 16

    /**
     * 单包长度上限。
     *
     * 与上游 `MAX_LIVE_DANMAKU_FRAME_BYTES`（2MB）同值。本仓保留 2MB 而不是上游
     * 解压后的 8MB：**按本仓实测定**（`MAX_DECOMPRESSED_SIZE` 也是 2MB），
     * 真实弹幕帧远小于此；放宽只会放大恶意包的内存占用。
     */
    const val MAX_PACKET_SIZE = 2 * 1024 * 1024

    /** 解压后包体上限（防止 zip bomb）。 */
    const val MAX_DECOMPRESSED_SIZE = 2 * 1024 * 1024

    /**
     * 压缩嵌套深度上限（上游 `MAX_LIVE_DANMAKU_COMPRESSION_DEPTH` 同值 2）。
     *
     * 真实协议只有"外层压缩包 → 内层业务包"这一到两层；取 2 覆盖真实形状、
     * 堵住恶意递归。
     */
    const val MAX_COMPRESSION_DEPTH = 2

    /**
     * 单帧内解出的业务包数上限（上游 `MAX_LIVE_DANMAKU_PACKETS_PER_FRAME` 同值 4096）。
     *
     * 正常一帧几条到几十条，4096 是数量级的余量。
     */
    const val MAX_PACKETS_PER_FRAME = 4096

    const val VERSION_PLAIN = 0
    const val VERSION_ZLIB = 2
    const val VERSION_BROTLI = 3

    const val OP_HEARTBEAT = 2
    const val OP_HEARTBEAT_REPLY = 3
    const val OP_MESSAGE = 5
    const val OP_AUTH = 7
    const val OP_AUTH_REPLY = 8

    const val IO_BUFFER_SIZE = 8 * 1024

    /**
     * 解出业务包体（对外唯一入口）。
     *
     * @param version 协议版本（`VERSION_PLAIN` / `VERSION_ZLIB` / `VERSION_BROTLI`）
     * @param payload 包体
     */
    fun unpack(version: Int, payload: ByteArray): List<JSONObject> =
        unpackBusinessPayload(version, payload, depth = 0)

    private fun unpackBusinessPayload(version: Int, payload: ByteArray, depth: Int): List<JSONObject> {
        if (depth > MAX_COMPRESSION_DEPTH) return emptyList()
        return when (version) {
            VERSION_PLAIN -> parseJsonOrNull(payload)?.let(::listOf)
                ?: unpackNestedPackets(payload, depth)
            // **损坏的压缩包不得冒泡**：原先 `inflate`/`decodeBrotli` 抛的异常会一路
            // 冒到 socket 读取的 catch ⇒ 整条连接重连，用户丢掉整个弹幕流。
            // 一个坏包只该丢它自己（上游用 `catch (_: Exception) {}` 后 continue，同语义）。
            //
            // ⚠️ 捕 `Exception` 而不是 `Throwable`：`StackOverflowError`/`OutOfMemoryError`
            // 属 `Error`，必须继续冒泡（那说明有更深的问题；深度上限已把递归栈溢出的
            // 路径堵住）。
            VERSION_ZLIB -> runCatching { unpackNestedPackets(inflate(payload), depth) }
                .getOrDefault(emptyList())
            VERSION_BROTLI -> runCatching { unpackNestedPackets(decodeBrotli(payload), depth) }
                .getOrDefault(emptyList())
            else -> emptyList()
        }
    }

    /**
     * 拆一层嵌套包。
     *
     * 三处上限都在这里生效：每帧包数（循环条件）、压缩深度（[unpackBusinessPayload] 入口）、
     * 单包长度（`packetLen` 区间校验）。
     */
    private fun unpackNestedPackets(bytes: ByteArray, depth: Int): List<JSONObject> {
        if (bytes.isEmpty()) return emptyList()
        val packets = mutableListOf<JSONObject>()
        var offset = 0
        while (offset + HEADER_SIZE <= bytes.size) {
            if (packets.size >= MAX_PACKETS_PER_FRAME) break
            val packetLen = readInt32(bytes, offset)
            if (packetLen !in HEADER_SIZE..MAX_PACKET_SIZE || offset + packetLen > bytes.size) break
            val headerLen = readInt16(bytes, offset + 4)
            if (headerLen !in HEADER_SIZE..packetLen) break
            val version = readInt16(bytes, offset + 6)
            val op = readInt32(bytes, offset + 8)
            val body = bytes.copyOfRange(offset + headerLen, offset + packetLen)
            if (op == OP_MESSAGE) {
                packets += unpackBusinessPayload(version, body, depth + 1)
            } else if (op == OP_AUTH_REPLY) {
                parseJsonOrNull(body)?.let(packets::add)
            }
            offset += packetLen
        }
        return packets
    }

    private fun inflate(bytes: ByteArray): ByteArray =
        readAllBytes(InflaterInputStream(bytes.inputStream()))

    private fun decodeBrotli(bytes: ByteArray): ByteArray =
        readAllBytes(BrotliInputStream(bytes.inputStream()))

    private fun readAllBytes(input: InputStream): ByteArray {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(IO_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                total += read
                if (total > MAX_DECOMPRESSED_SIZE) {
                    throw IllegalStateException("直播消息包体超过限制")
                }
                out.write(buf, 0, read)
            }
            return out.toByteArray()
        }
    }

    fun parseJsonOrNull(payload: ByteArray): JSONObject? {
        val text = payload.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    fun readInt32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    fun readInt16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    fun readUInt32(bytes: ByteArray): Long = readInt32(bytes, 0).toLong() and 0xFFFFFFFFL

    /**
     * 组装一个协议包（测试与 ACK/心跳发送共用同一份拼装逻辑，避免"测试里手写一份
     * 与生产不一致的字节布局"）。
     */
    fun encode(version: Int, op: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        writeInt32(out, 0, HEADER_SIZE + payload.size)
        writeInt16(out, 4, HEADER_SIZE)
        writeInt16(out, 6, version)
        writeInt32(out, 8, op)
        writeInt32(out, 12, 0)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    private fun writeInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun writeInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
