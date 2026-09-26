package com.naaammme.bbspace.infra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SingleFileDashIndexParser] 的纯字节单测。
 *
 * ## 为什么这段逻辑值得单独钉
 * 解析失败在调用侧是 `error("单文件 dash 索引缺失")`，上层只把它显示成播放错误、
 * **没有降级路径** —— 也就是"合法但少见的 m4s 头部"会直接变成该视频起播失败。
 * 这类缺陷只能靠合成字节复现（真机拿到一个用扩展大小头的 m4s 成本极高），
 * 而它又完全是纯函数，所以是本仓少见的"能真跑起来的单元测试"，不是源码扫描守卫。
 *
 * ## 期望值的算法
 * 每个盒的 size **含自己的头**：32 位头 = 8 字节，扩展头 = 16 字节。
 * 逐盒累加得到偏移，测试里把偏移写成字面量，出错时能一眼看出差在哪。
 */
class SingleFileDashIndexParserTest {

    private fun u32(v: Long) = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte()
    )

    private fun u64(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    private fun four(type: String): ByteArray {
        val bytes = type.toByteArray(Charsets.ISO_8859_1)
        // 防假绿：写错 4 字符类型名会让整条断言退化成"解析不出"，而那是被测试期望的 null
        assertTrue("MP4 盒类型必须是 4 字符，收到 '$type'", bytes.size == 4)
        return bytes
    }

    /** 常规 32 位头盒：总长 8 + body。 */
    private fun box(type: String, body: Int): ByteArray =
        u32(8L + body) + four(type) + ByteArray(body)

    /** size==1 + 8 字节 largesize 的扩展头盒：总长 16 + body。 */
    private fun largeBox(type: String, body: Int): ByteArray =
        u32(1) + four(type) + u64(16L + body) + ByteArray(body)

    /** size==0：该盒延展到数据末尾。 */
    private fun eofBox(type: String, body: Int): ByteArray =
        u32(0) + four(type) + ByteArray(body)

    /** 声明长度超过实际字节的盒（预取窗口把索引截断的情形）。 */
    private fun truncatedBox(type: String, declaredTotal: Long, actualBody: Int): ByteArray =
        u32(declaredTotal) + four(type) + ByteArray(actualBody)

    // ── 1 · 常规形态：改动前后必须完全一致 ────────────────────────────────────

    /**
     * ftyp(28) + moov(108) + sidx(72) + mdat(58)：
     * moov 末 = 28+108 = 136，sidx 起点 = 136、长度 72。
     * 这条是**回归钉**：绝大多数 bilibili m4s 走的就是这条路径，改解析器不许动它。
     */
    @Test
    fun standardLayoutIsUnchanged() {
        val data = box("ftyp", 20) + box("moov", 100) + box("sidx", 64) + box("mdat", 50)
        val index = SingleFileDashIndexParser.parse(data)
        assertEquals(SingleFileDashIndex(136L, 136L, 72L), index)
    }

    /** 没有 moov / 没有 sidx 都必须判"定不出索引"，而不是给出半个索引让 ExoPlayer 拿到错 Range。 */
    @Test
    fun missingMoovOrSidxIsRejected() {
        assertNull(SingleFileDashIndexParser.parse(box("ftyp", 20) + box("sidx", 64) + box("mdat", 50)))
        assertNull(SingleFileDashIndexParser.parse(box("ftyp", 20) + box("moov", 100) + box("mdat", 50)))
    }

    /**
     * sidx 排在 moov 之前必须**判失败**，这是刻意的而不是没做完。
     * init 段定义是"开头到 moov 末"，若 sidx 在前，两个 Range 会重叠且 init 缺数据 ——
     * 放宽它只会把"起播失败"变成"花屏/坏帧"，更难查。这条断言防止后人顺手"兼容"掉。
     */
    @Test
    fun sidxBeforeMoovStaysRejected() {
        val data = box("ftyp", 20) + box("sidx", 64) + box("moov", 100)
        assertNull(SingleFileDashIndexParser.parse(data))
    }

    // ── 2 · 少见的合法形态：改动前会被判非法 ──────────────────────────────────

    /**
     * 扩展大小头（size==1 + largesize）：部分封装器对任何顶层盒都写 64 位长度。
     * 改动前 `readUInt32` 读到 1 就当盒长 1 字节，指针随即错位，最终返回 null → 起播失败。
     * 这里 ftyp(28) + largeMoov(116) + sidx(72)：moov 末 = 28+116 = 144。
     */
    @Test
    fun largesizeHeaderIsParsed() {
        val data = box("ftyp", 20) + largeBox("moov", 100) + box("sidx", 64) + box("mdat", 50)
        val index = SingleFileDashIndexParser.parse(data)
        assertEquals(SingleFileDashIndex(144L, 144L, 72L), index)
    }

    /** 扩展头与普通头混排（真实文件里 sidx/mdat 常仍用 32 位）。 */
    @Test
    fun mixedHeaderWidthsAreParsed() {
        val data = box("ftyp", 20) + largeBox("moov", 100) + largeBox("sidx", 64)
        val index = SingleFileDashIndexParser.parse(data)
        assertEquals(SingleFileDashIndex(144L, 144L, 80L), index)
    }

    /**
     * size==0（延展到末尾）：规范上只对"最后一个盒"合法，本扫描器通常在 sidx 处就收尾了，
     * 所以这条主要是把语义做对（防御性），不是声称修掉了某个线上故障。
     * 改动前 size==0 被 `takeIf { it > 0 }` 直接判非法 → 整盘放弃。
     */
    @Test
    fun zeroSizeModeExtendsToKnownEnd() {
        val data = box("ftyp", 20) + box("moov", 100) + eofBox("sidx", 64)
        val index = SingleFileDashIndexParser.parse(data)
        // ftyp 28 + moov 108 = 136 起，size==0 → 到数据末尾 28+108+72=208
        assertEquals(SingleFileDashIndex(136L, 136L, 72L), index)
    }

    // ── 3 · 坏输入只返回 null，绝不抛 / 绝不循环 ─────────────────────────────

    /**
     * 各种畸形输入。改动前 `readUInt32` 里带 `throw EOFException`，
     * 一旦哪天循环守卫变了就是抛异常；现在整条链不抛，调用方只需处理 null。
     */
    @Test
    fun malformedInputYieldsNullInsteadOfThrowing() {
        val cases = mapOf(
            "空" to ByteArray(0),
            "不足一个盒头（7 字节）" to ByteArray(7),
            "恰好一个盒头但没有体（8 字节 size=0）" to (u32(0) + four("free")),
            "全 0xFF（size=2^32-1，远超窗口）" to ByteArray(64) { 0xFF.toByte() },
            "盒长比自己的头还短" to (u32(4) + four("free") + ByteArray(16)),
            "largesize 只给了一半" to (u32(1) + four("moov") + u32(16)),
            "moov 后紧跟被截断的 sidx" to (box("moov", 100) + truncatedBox("sidx", 5000, 10)),
            "sidx 声明长度小于头" to (box("moov", 100) + u32(6) + four("sidx") + ByteArray(64))
        )
        for ((name, bytes) in cases) {
            val parsed = runCatching { SingleFileDashIndexParser.parse(bytes) }
            assertTrue("$name：不该抛异常，实际抛了 ${parsed.exceptionOrNull()}", parsed.isSuccess)
            assertNull("$name：坏输入必须判为定不出索引", parsed.getOrNull())
        }
    }

    /** 预取窗口只有 91536 字节，索引被截断时**必须**判失败（而不是给出越界 Range）。 */
    @Test
    fun truncatedIndexIsRejected() {
        val data = box("ftyp", 20) + box("moov", 100) + truncatedBox("sidx", 4_000_000, 10)
        assertNull(SingleFileDashIndexParser.parse(data))
    }

    /** 大端读取本身要正确（曾把 uint32 读进 Int，>2^31 变负数即被当非法）。 */
    @Test
    fun hugeBoxSizeDoesNotBecomeNegativeAndIsRejected() {
        // size = 0xFFFFFFFF：作为 Int 是 -1，旧写法 takeIf{it>0} 直接 null（蒙对）；
        // 新写法按无符号 Long 读，因超出可读边界而判 null —— 结论一致但理由是明确的。
        val data = u32(0xFFFF_FFFFL) + four("mdat") + ByteArray(32)
        assertNull(SingleFileDashIndexParser.parse(data))
    }
}
