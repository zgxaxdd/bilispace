package com.naaammme.bbspace.core.live

import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B7（2026-10-13，上游 v0.3.6 同步）：直播弹幕**线协议**的健壮性。
 *
 * ## 为什么这组测试现在才写得出
 *
 * 这些判据原先住在 `LiveRoomMessageRepository` 的 private 方法里 —— 一个 Hilt 注入类，
 * 于是"恶意包会不会打崩解析器"**没有任何真字节级的测试能覆盖**，只能靠扫源码式的结构
 * 守卫看着（那种守卫证明不了行为）。本轮把纯逻辑抽到 [LiveDanmakuProtocol]，
 * 才第一次能用**真字节**构造恶意输入并断言解析器不崩。
 *
 * 上游 BiliPai 的同款测试是 `DanmakuProtocolLimitsTest`（含真实 brotli wire payload）。
 *
 * ## 覆盖的三个缺陷（都是本轮实际修掉的）
 *
 * 1. **无限递归**：`unpackNestedPackets` ↔ `unpackBusinessPayload` 互相递归且无深度参数
 *    ⇒ 每层再套一层压缩、每层都合法时递归无限展开直到 `StackOverflowError`。
 * 2. **每帧包数无上限**：一个 2MB 帧可塞进几十万个小包，逐个 JSON 解析长时间占用线程。
 * 3. **损坏压缩包冒泡**：`inflate` 抛异常会一路冒到 socket 读取的 catch ⇒ 整条连接重连。
 *
 * ⚠️ 第 1 条的测试**必须真的断言"不抛 StackOverflowError"**，而不是只断言"返回空"——
 * 只断言返回值的话，递归爆栈时测试会以 `StackOverflowError` 失败（而不是断言失败），
 * 那样错误信息会指向测试自身，看不出根因。这里显式 try/catch 并给出可读断言消息。
 */
class LiveDanmakuProtocolLimitsTest {

    private fun json(cmd: String): ByteArray = """{"cmd":"$cmd"}""".toByteArray()

    private fun zlib(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    // ── 基线：正常包必须能解出来 ──────────────────────────────────────────────

    @Test
    fun plainJsonPacket_isParsed() {
        val result = LiveDanmakuProtocol.unpack(
            version = LiveDanmakuProtocol.VERSION_PLAIN,
            payload = json("DANMU_MSG")
        )
        assertEquals(1, result.size)
        assertEquals("DANMU_MSG", result.first().optString("cmd"))
    }

    @Test
    fun zlibFrameWithOneMessage_isParsed() {
        // 外层 ZLIB → 解压后是一个 OP_MESSAGE 包（真实协议最常见的形状）
        val inner = LiveDanmakuProtocol.encode(
            version = LiveDanmakuProtocol.VERSION_PLAIN,
            op = LiveDanmakuProtocol.OP_MESSAGE,
            payload = json("DANMU_MSG")
        )
        val result = LiveDanmakuProtocol.unpack(
            version = LiveDanmakuProtocol.VERSION_ZLIB,
            payload = zlib(inner)
        )
        assertEquals(1, result.size)
        assertEquals("DANMU_MSG", result.first().optString("cmd"))
    }

    @Test
    fun authReplyPackets_areCollected() {
        val frame = LiveDanmakuProtocol.encode(
            version = LiveDanmakuProtocol.VERSION_PLAIN,
            op = LiveDanmakuProtocol.OP_AUTH_REPLY,
            payload = json("AUTH_OK")
        )
        // ⚠️ 外层必须是 ZLIB：`VERSION_PLAIN` 的顶层包体会先被当作**单个 JSON** 试解析
        // （`{"cmd":"AUTH_OK"}` 本身就是合法 JSON ⇒ 直接返回 1 条），走不到嵌套扫描。
        // 要测"从字节流里扫出多个包"，必须从会进入 `unpackNestedPackets` 的版本进来。
        val result = LiveDanmakuProtocol.unpack(LiveDanmakuProtocol.VERSION_ZLIB, zlib(frame))
        assertEquals(1, result.size)
        assertEquals("AUTH_OK", result.first().optString("cmd"))
    }

    // ── 缺陷 1：无限递归 ──────────────────────────────────────────────────────

    /**
     * 构造 [depth] 层压缩嵌套，最内层是一个能解出 JSON 的 PLAIN 帧。
     *
     * ## 为什么形状是这样（这层语义容易搞错，写清楚）
     *
     * 协议里"压缩包"的**包体永远是帧**，不是裸 JSON。所以每多一层嵌套 =
     * 多一个 `ZLIB` 帧，其包体是**再压缩一层的帧**。解包时每下钻一层，`depth` 加一：
     *
     * ```
     * depth=1: unpack(ZLIB, zlib( F(ZLIB, zlib( F(PLAIN, json) )) ))
     *            └ 下钻 1 次到达 PLAIN 帧 → depth=2 ≤ 上限 ⇒ 解出 1 条
     * depth=2: 再套一层 → 到达 PLAIN 帧时 depth=3 > 上限 ⇒ 丢弃
     * ```
     *
     * ⚠️ 我第一版把最内层写成"`ZLIB` 帧包着裸 JSON"，那是**协议里不存在的形状**：
     * 解包器会去把裸 JSON 当帧解析，自然解不出东西（测试因此假红）。现在最内层是
     * PLAIN 帧，与真实协议一致。
     */
    private fun nestedZlib(depth: Int): ByteArray {
        var frame = LiveDanmakuProtocol.encode(
            version = LiveDanmakuProtocol.VERSION_PLAIN,
            op = LiveDanmakuProtocol.OP_MESSAGE,
            payload = json("DANMU_MSG")
        )
        repeat(depth) {
            frame = LiveDanmakuProtocol.encode(
                version = LiveDanmakuProtocol.VERSION_ZLIB,
                op = LiveDanmakuProtocol.OP_MESSAGE,
                payload = zlib(frame)
            )
        }
        return zlib(frame)
    }

    @Test
    fun deeplyNestedCompressedPackets_doNotBlowTheStack() {
        // 200 层：远超深度上限 2。旧实现在这里会递归 200 层并继续（真实攻击可以给上千层）。
        val result = try {
            LiveDanmakuProtocol.unpack(
                version = LiveDanmakuProtocol.VERSION_ZLIB,
                payload = nestedZlib(200)
            )
        } catch (e: StackOverflowError) {
            throw AssertionError(
                "B7 回归：深层嵌套压缩包把解析器打爆栈了 —— 说明压缩深度上限失效。" +
                    "上限应为 ${LiveDanmakuProtocol.MAX_COMPRESSION_DEPTH}。",
                e
            )
        }
        assertTrue(
            "B7：超过深度上限的嵌套必须**被丢弃**（返回空），而不是继续展开。" +
                "实际返回 ${result.size} 条。",
            result.isEmpty()
        )
    }

    @Test
    fun nestingWithinDepthLimit_stillWorks() {
        // 深度 1（真实协议形状）必须照常解出来 —— 上限不能把正常路径一起挡掉。
        val result = LiveDanmakuProtocol.unpack(
            version = LiveDanmakuProtocol.VERSION_ZLIB,
            payload = nestedZlib(1)
        )
        assertEquals("深度 1 属于真实协议形状，必须能解出 1 条。", 1, result.size)
        assertEquals("DANMU_MSG", result.first().optString("cmd"))
    }

    // ── 缺陷 2：每帧包数上限 ──────────────────────────────────────────────────

    @Test
    fun frameWithTooManyPackets_isCapped() {
        // 造 MAX+100 个 PLAIN 小包。每个都是合法 OP_MESSAGE（内容是非法 JSON ⇒ 解出 0 条，
        // 但**循环次数**仍受上限约束）。用 AUTH_REPLY 才能让每条都产出一个 JSON。
        val count = LiveDanmakuProtocol.MAX_PACKETS_PER_FRAME + 100
        val out = ByteArrayOutputStream()
        repeat(count) {
            out.write(
                LiveDanmakuProtocol.encode(
                    version = LiveDanmakuProtocol.VERSION_PLAIN,
                    op = LiveDanmakuProtocol.OP_AUTH_REPLY,
                    payload = json("M$it")
                )
            )
        }
        val result = LiveDanmakuProtocol.unpack(LiveDanmakuProtocol.VERSION_ZLIB, zlib(out.toByteArray()))
        assertEquals(
            "B7：单帧解出的包数必须被 MAX_PACKETS_PER_FRAME 截断（否则恶意帧会长时间占用线程）。",
            LiveDanmakuProtocol.MAX_PACKETS_PER_FRAME,
            result.size
        )
    }

    // ── 缺陷 3：损坏的压缩包不得冒泡 ──────────────────────────────────────────

    @Test
    fun corruptedZlibPayload_isSwallowedNotThrown() {
        val garbage = byteArrayOf(0x78, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val result = try {
            LiveDanmakuProtocol.unpack(LiveDanmakuProtocol.VERSION_ZLIB, garbage)
        } catch (e: Exception) {
            throw AssertionError(
                "B7 回归：损坏的压缩包把异常抛出去了 —— 在真机上这会冒到 socket 读取的 catch，" +
                    "导致**整条连接重连**、用户丢掉整个弹幕流。一个坏包只该丢它自己。",
                e
            )
        }
        assertTrue("损坏包应解出 0 条，实际 ${result.size} 条。", result.isEmpty())
    }

    @Test
    fun corruptedPacketDoesNotDiscardValidSiblings() {
        // 一帧里：合法 AUTH_REPLY + 损坏的 ZLIB OP_MESSAGE + 合法 AUTH_REPLY
        // ⇒ 两条合法的必须都留下（"该包作废、兄弟包继续"）。
        // 外层用 ZLIB 才能进入嵌套扫描（PLAIN 会先把整个包体当单个 JSON 试解析）。
        val good = { tag: String ->
            LiveDanmakuProtocol.encode(
                version = LiveDanmakuProtocol.VERSION_PLAIN,
                op = LiveDanmakuProtocol.OP_AUTH_REPLY,
                payload = json(tag)
            )
        }
        val broken = LiveDanmakuProtocol.encode(
            version = LiveDanmakuProtocol.VERSION_ZLIB,
            op = LiveDanmakuProtocol.OP_MESSAGE,
            payload = byteArrayOf(0x78, 0x01, 0x02, 0x03)
        )
        val frame = good("BEFORE") + broken + good("AFTER")
        val result = LiveDanmakuProtocol.unpack(
            LiveDanmakuProtocol.VERSION_ZLIB,
            zlib(frame)
        )
        assertEquals(
            "B7：损坏的压缩包只许丢它自己，兄弟包必须保留（实际解出 ${result.size} 条：" +
                result.joinToString { it.optString("cmd") } + "）。",
            2,
            result.size
        )
        assertEquals("BEFORE", result[0].optString("cmd"))
        assertEquals("AFTER", result[1].optString("cmd"))
    }

    // ── 边界：长度校验 ────────────────────────────────────────────────────────

    @Test
    fun packetLengthBeyondLimit_isRejected() {
        // 一个声称 packetLen 超过 MAX_PACKET_SIZE 的帧 ⇒ 必须停止解析（不得据此读取）
        val tooBig = LiveDanmakuProtocol.MAX_PACKET_SIZE + 1
        val bytes = ByteArray(32)
        bytes[0] = (tooBig ushr 24).toByte()
        bytes[1] = (tooBig ushr 16).toByte()
        bytes[2] = (tooBig ushr 8).toByte()
        bytes[3] = tooBig.toByte()
        bytes[4] = 0
        bytes[5] = 16 // headerLen = 16
        assertTrue(
            "B7：超过 MAX_PACKET_SIZE 的包长必须被拒绝（不得据此分配/读取）。",
            LiveDanmakuProtocol.unpack(LiveDanmakuProtocol.VERSION_ZLIB, zlib(bytes)).isEmpty()
        )
    }

    @Test
    fun emptyPayload_isHandled() {
        assertTrue(
            LiveDanmakuProtocol.unpack(LiveDanmakuProtocol.VERSION_PLAIN, ByteArray(0)).isEmpty()
        )
        assertTrue(
            LiveDanmakuProtocol.unpack(LiveDanmakuProtocol.VERSION_ZLIB, ByteArray(0)).isEmpty()
        )
    }

    @Test
    fun unknownVersion_yieldsNothing() {
        assertTrue(
            "未知协议版本必须安全返回空（不得当 PLAIN 处理）。",
            LiveDanmakuProtocol.unpack(99, json("X")).isEmpty()
        )
    }

    // ── 编解码往返（拼包与解包共用同一份字节布局）──────────────────────────────

    @Test
    fun encodeThenReadBack_isConsistent() {
        val frame = LiveDanmakuProtocol.encode(
            version = LiveDanmakuProtocol.VERSION_BROTLI,
            op = LiveDanmakuProtocol.OP_MESSAGE,
            payload = byteArrayOf(1, 2, 3)
        )
        assertEquals("总长 = 头部 16 + 包体 3", 19, LiveDanmakuProtocol.readInt32(frame, 0))
        assertEquals(16, LiveDanmakuProtocol.readInt16(frame, 4))
        assertEquals(LiveDanmakuProtocol.VERSION_BROTLI, LiveDanmakuProtocol.readInt16(frame, 6))
        assertEquals(LiveDanmakuProtocol.OP_MESSAGE, LiveDanmakuProtocol.readInt32(frame, 8))
    }

    @Test
    fun readUInt32_treatsHighBitAsUnsigned() {
        // 人气值等字段是 uint32：0xFFFFFFFF 必须读成 4294967295 而不是 -1
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(4294967295L, LiveDanmakuProtocol.readUInt32(bytes))
    }

    @Test
    fun constantsMatchUpstreamValues() {
        // 与上游 `004f00ef4` 对齐的三个上限（本仓 MAX_PACKET_SIZE 沿用 2MB 而非上游 8MB，
        // 理由见 LiveDanmakuProtocol 的 KDoc —— 这条断言把"有意分歧"钉成显式事实）。
        assertEquals(2, LiveDanmakuProtocol.MAX_COMPRESSION_DEPTH)
        assertEquals(4096, LiveDanmakuProtocol.MAX_PACKETS_PER_FRAME)
        assertEquals(2 * 1024 * 1024, LiveDanmakuProtocol.MAX_PACKET_SIZE)
        assertNotNull(LiveDanmakuProtocol)
    }
}
