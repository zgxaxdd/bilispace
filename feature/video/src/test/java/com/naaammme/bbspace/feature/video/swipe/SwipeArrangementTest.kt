package com.naaammme.bbspace.feature.video.swipe

import com.naaammme.bbspace.core.model.VideoTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新批次的洗牌 + 相邻不同 UP 排布（移植 BiliPai `PortraitPagerSwitchPolicy.kt:280-330`）。
 *
 * 用户可感知的问题：相关推荐按相关性排序，一批里同一 UP 常常连着几条 ——
 * 原序入队就是"连着刷到同一个 UP"。这里钉四件事：
 * ① 同一 seed 可复现；② aid 条目（bvid 为空）不许被丢掉；③ 尽量相邻不同 UP；
 * ④ 相似条目在排布阶段也被挡掉。
 */
class SwipeArrangementTest {

    private fun entry(
        bvid: String,
        aid: Long = 0L,
        title: String = "标题 $bvid",
        cover: String = "https://i0.hdslb.com/bfs/archive/$bvid.jpg",
        author: String? = "UP主A",
        durationText: String? = null,
    ): SwipeEntry = SwipeEntry(
        target = VideoTarget.Ugc(aid = aid, cid = 0L, bvid = bvid),
        bvid = bvid,
        title = title,
        cover = cover,
        author = author,
        durationText = durationText
    )

    @Test
    fun sameSeedProducesSameOrder() {
        val input = (1..8).map { entry("BV$it", author = "UP$it") }
        val first = arrangeSwipeEntries(seed = 42, incoming = input).map { it.bvid }
        val second = arrangeSwipeEntries(seed = 42, incoming = input.reversed()).map { it.bvid }
        assertEquals("同 seed 必须可复现（否则真机上每次补货顺序都不一样）", first, second)
        assertNotEquals(
            "不同 seed 应当给出不同顺序（否则等于没洗牌）",
            first,
            arrangeSwipeEntries(seed = 7, incoming = input).map { it.bvid }
        )
    }

    @Test
    fun keepsAidOnlyEntries() {
        // C7 真机事故：BiliPai 的 `filter { bvid.isNotBlank() }` 会把 relate 的
        // aid 条目整页丢掉 → 队列空、无法上滑。这里按 key 去重，不丢。
        val input = listOf(
            entry("", aid = 111L, author = "甲", cover = "https://i0.hdslb.com/bfs/archive/a111.jpg"),
            entry("", aid = 222L, author = "乙", cover = "https://i0.hdslb.com/bfs/archive/a222.jpg"),
            entry("BV3", author = "丙"),
        )
        val arranged = arrangeSwipeEntries(seed = 1, incoming = input)
        assertEquals("aid 条目必须保留", 3, arranged.size)
        assertTrue(arranged.any { it.target.aid == 111L })
        assertTrue(arranged.any { it.target.aid == 222L })
    }

    @Test
    fun avoidsAdjacentSameOwnerWhenPossible() {
        // 三条同一 UP + 三条别的 UP：排布后相邻同 UP 的次数应当被压到最低
        val input = listOf(
            entry("BV1", author = "同UP"),
            entry("BV2", author = "同UP"),
            entry("BV3", author = "同UP"),
            entry("BV4", author = "甲"),
            entry("BV5", author = "乙"),
            entry("BV6", author = "丙"),
        )
        val arranged = arrangeSwipeEntries(seed = 3, incoming = input)
        val owners = arranged.map { it.displayAuthor }
        val adjacentSame = owners.zipWithNext().count { (a, b) -> a != null && a == b }
        assertEquals("理想情况下不应有相邻同 UP", 0, adjacentSame)
        assertEquals(6, arranged.size)
    }

    @Test
    fun respectsPrecedingOwnerAtTheHead() {
        val input = listOf(
            entry("BV1", author = "上一个UP"),
            entry("BV2", author = "别的UP"),
        )
        // 队列尾部是"上一个UP" → 新批次第一条应当尽量换成别的 UP
        val arranged = arrangeSwipeEntries(seed = 5, incoming = input, precedingOwner = "上一个UP")
        assertEquals("别的UP", arranged.first().displayAuthor)
    }

    @Test
    fun dropsSimilarEntriesDuringArrangement() {
        // 同一支视频换 UP、换后缀标题、时长相近 → 排布阶段就该只剩一条
        val input = listOf(
            entry("BV1", title = "【4K】原神 深渊 攻略", author = "甲", durationText = "10:00"),
            entry("BV2", title = "【合集】原神 深渊 攻略", author = "乙", durationText = "10:05"),
            entry("BV3", title = "钢琴 入门 教程", author = "丙", durationText = "03:00"),
        )
        val arranged = arrangeSwipeEntries(seed = 9, incoming = input)
        assertEquals(2, arranged.size)
        assertTrue(arranged.any { it.bvid == "BV3" })
    }

    @Test
    fun shortInputPassesThrough() {
        val single = listOf(entry("BV1"))
        assertEquals(single, arrangeSwipeEntries(seed = 1, incoming = single))
        assertEquals(emptyList<SwipeEntry>(), arrangeSwipeEntries(seed = 1, incoming = emptyList()))
    }
}
