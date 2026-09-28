package com.naaammme.bbspace.core.model.video

import com.naaammme.bbspace.core.model.VideoTargetTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `avToBv` / `bvToAv` 的真值钉：**每组 aid/bvid 都取自 B 站 live API**
 * （`GET x/web-interface/view?aid=`）核对过，不是自洽往返。
 *
 * 这组测试的存在理由（真机 bug，2026-09-28）：
 * 详情响应的 relate 卡 `basic.uri` 是 aid 格式，`VideoTargetTool.bvid(uri)` 解析为空，
 * 整条播放链路（页身份、历史、弹幕、上报）拿不到 bvid。BiliPai 的做法是本地换算补齐，
 * 但**换算算法选错会算出另一个视频的 bvid**——比留空更有害。所以这里把真值钉死：
 *
 * - `BV1Kyas6wEuz`（aid=117339261503379）是**新版长 aid**，网上流传的旧版
 *   「固定模板 `BV1__4_1_7__` + SALT 表」算法在它身上会给出 `BV1d54t1F77p`（错）。
 *   这条用例就是"不许换回旧算法"的哨兵。
 */
class VideoTargetToolIdTest {

    @Test
    fun `avToBv - live API 核对过的真值`() {
        // 新版长 aid：旧版模板算法在这里必错（会得到 BV1d54t1F77p）
        assertEquals("BV1Kyas6wEuz", VideoTargetTool.avToBv(117_339_261_503_379L))
        assertEquals("BV17x411w7KC", VideoTargetTool.avToBv(170_001L))
        assertEquals("BV1xx411c7mD", VideoTargetTool.avToBv(2L))
        assertEquals("BV17f4y1R7YS", VideoTargetTool.avToBv(286_347_735L))
    }

    @Test
    fun `bvToAv - live API 核对过的真值`() {
        assertEquals(117_339_261_503_379L, VideoTargetTool.bvToAv("BV1Kyas6wEuz"))
        assertEquals(170_001L, VideoTargetTool.bvToAv("BV17x411w7KC"))
        assertEquals(2L, VideoTargetTool.bvToAv("BV1xx411c7mD"))
        assertEquals(286_347_735L, VideoTargetTool.bvToAv("BV17f4y1R7YS"))
    }

    @Test
    fun `互逆 - avToBv 的结果能被 bvToAv 还原`() {
        for (aid in listOf(1L, 2L, 170_001L, 286_347_735L, 117_339_261_503_379L)) {
            val bvid = VideoTargetTool.avToBv(aid)
            assertEquals("aid=$aid 的 avToBv 结果不为 12 位 BV 号：$bvid", 12, bvid?.length)
            assertEquals("往返失真：aid=$aid bvid=$bvid", aid, VideoTargetTool.bvToAv(bvid!!))
        }
    }

    @Test
    fun `非法输入返回 null - 宁可不给也不给错的`() {
        assertNull(VideoTargetTool.avToBv(0L))
        assertNull(VideoTargetTool.avToBv(-100L))
        // 超出 bit51 编码范围：不能截断成"另一个视频"的 bvid
        assertNull(VideoTargetTool.avToBv(Long.MAX_VALUE))
        assertNull(VideoTargetTool.bvToAv(""))
        assertNull(VideoTargetTool.bvToAv("BV1short"))
        assertNull(VideoTargetTool.bvToAv("AV17x411w7KC"))
        // 12 位但含表外字符（'0' 不在 BV_DATA 里）
        assertNull(VideoTargetTool.bvToAv("BV1000000000"))
    }

    @Test
    fun `BV 号首字符集 - avToBv 恒以 BV1 开头且长度为 12`() {
        for (aid in 1L..2_000L) {
            val bvid = VideoTargetTool.avToBv(aid)!!
            assertEquals(12, bvid.length)
            assertEquals("BV1", bvid.take(3))
        }
    }
}
