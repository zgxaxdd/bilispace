package com.naaammme.bbspace.infra.player

/**
 * 单文件 DASH（`.m4s`）头部字节里的 MP4 盒扫描器：定出 init 段（文件开头 → moov 末）
 * 与 sidx 索引段的起点/长度，供 `Representation.SingleSegmentBase` 生成两个 Range。
 *
 * **为什么单独成对象**：这段逻辑只吃 `ByteArray`，不碰网络也不碰 Media3，
 * 所以能用合成字节在 JVM 单测里逐形态钉死（原来它是 Activity 级类里的 private 函数，
 * 没有任何测试能到达）。
 *
 * ISO/IEC 14496-12 §4.2 规定盒头有三种形态，本解析器三种都认：
 * - `size ∈ [9, 2^32)`：常规 32 位头，盒长 = size；
 * - `size == 1`：真实长度在随后 8 字节的 `largesize` 里，头占 16 字节；
 * - `size == 0`：该盒延展到数据末尾。
 *
 * 改动前的实现只认第一种，且把 `size == 0` 当作非法整盘放弃 —— 而后两种是合法文件。
 * 失败路径不是"降级"而是 `buildDashStream` 里的 `error("单文件 dash 索引缺失")`，
 * 上层只把 errorMessage 显示出来，**表现为该视频直接起播失败**。
 */
internal object SingleFileDashIndexParser {

    /**
     * 解析头部前缀；返回 null 表示"这段字节里定不出可用的 moov+sidx"。
     *
     * 约定：**任何输入都不抛异常**（截断、全 0、超大 size、只有一半的盒头一律返回 null），
     * 让调用方只需要处理"有索引/没索引"两种情况。
     *
     * moov 必须出现在 sidx 之前：init 段的定义是"开头到 moov 末"，若 sidx 排在 moov 前面，
     * 两个 Range 会互相重叠、喂给 ExoPlayer 的 init 又缺了 sidx 之前的数据，属于**不能放宽**
     * 的排布（放宽会把"起播失败"变成"花屏/坏帧"，更糟）。所以扫到 sidx 就收尾。
     */
    fun parse(data: ByteArray): SingleFileDashIndex? {
        val limit = data.size.toLong()
        var pos = 0L
        var moovEnd = -1L
        var sidxStart = -1L
        var sidxLength = -1L
        while (pos + 8 <= limit) {
            val header = pos.toInt()
            var size = readUnsignedInt32(data, header)
            var headerLength = 8L
            if (size == 1L) {
                // 扩展大小头：largesize 是随后 8 字节
                if (pos + 16 > limit) return null
                size = readUnsignedInt64(data, header + 8)
                headerLength = 16L
            } else if (size == 0L) {
                // size==0：延展到已知末尾（预取窗口之外没有信息，取当前可读边界）
                size = limit - pos
            }
            // 盒长比自己的头还短 = 结构已坏，继续走只会读到偏移的 type，判失败
            if (size < headerLength) return null
            // 被预取窗口截断：后面还有什么无从判定
            if (size > limit - pos) return null
            when {
                isBoxType(data, header + 4, 'm', 'o', 'o', 'v') -> moovEnd = pos + size
                isBoxType(data, header + 4, 's', 'i', 'd', 'x') -> {
                    sidxStart = pos
                    sidxLength = size
                    return buildIndex(moovEnd, sidxStart, sidxLength)
                }
            }
            pos += size
        }
        return buildIndex(moovEnd, sidxStart, sidxLength)
    }

    private fun buildIndex(moovEnd: Long, sidxStart: Long, sidxLength: Long): SingleFileDashIndex? {
        if (moovEnd <= 0L || sidxStart <= 0L || sidxLength <= 0L) return null
        return SingleFileDashIndex(
            initLength = moovEnd,
            indexStart = sidxStart,
            indexLength = sidxLength
        )
    }

    /** 大端 uint32，用 Long 承载：>2^31 的 size 在原实现里会变成负数被当成非法。 */
    private fun readUnsignedInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    /** 大端 uint64：超过 Long.MAX_VALUE 的盒长（>8EB）必然落在负数区间，由调用侧的 size 校验判非法。 */
    private fun readUnsignedInt64(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun isBoxType(
        data: ByteArray,
        offset: Int,
        c0: Char,
        c1: Char,
        c2: Char,
        c3: Char
    ): Boolean {
        return data[offset].toInt() == c0.code &&
            data[offset + 1].toInt() == c1.code &&
            data[offset + 2].toInt() == c2.code &&
            data[offset + 3].toInt() == c3.code
    }
}

/** init 段长度、sidx 起点与长度三个字节区间（原为工厂类的私有嵌套类，测试需要可见）。 */
internal data class SingleFileDashIndex(
    val initLength: Long,
    val indexStart: Long,
    val indexLength: Long
)
