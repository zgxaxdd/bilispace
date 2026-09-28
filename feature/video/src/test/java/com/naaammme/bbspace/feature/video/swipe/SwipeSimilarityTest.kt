package com.naaammme.bbspace.feature.video.swipe

import com.naaammme.bbspace.core.model.VideoTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同一支视频被不同 UP 反复推荐」的相似度判据（移植 BiliPai `PortraitPagerSwitchPolicy.kt:407-497`）。
 *
 * 用户可感知的问题：连播队列来自"相关推荐接力"，同一支视频会被不同 UP 用不同标题后缀
 * （【合集】【完整版】…）重复推荐 —— 只做"标题全等 / 封面相等"挡不住，于是连着刷到同一支。
 * 这里钉住三条判据的**边界**（关键词交集 3 / 2+30 秒 / 同 UP+1+45 秒），
 * 以及"不该误杀"的那一侧（不同视频不能因为都有"教程""合集"就被判相似）。
 */
class SwipeSimilarityTest {

    private fun entry(
        bvid: String,
        title: String,
        cover: String = "https://i0.hdslb.com/bfs/archive/$bvid.jpg",
        author: String? = "UP主A",
        durationText: String? = null,
    ): SwipeEntry = SwipeEntry(
        target = VideoTarget.Ugc(aid = 0L, cid = 0L, bvid = bvid),
        bvid = bvid,
        title = title,
        cover = cover,
        author = author,
        durationText = durationText
    )

    // ===== 时长解析 =====

    @Test
    fun parsesDurationText() {
        assertEquals(754, swipeDurationSec("12:34"))
        assertEquals(3723, swipeDurationSec("1:02:03"))
        assertEquals(0, swipeDurationSec(null))
        assertEquals(0, swipeDurationSec(""))
        assertEquals(0, swipeDurationSec("未知"))
        assertEquals(0, swipeDurationSec("1:2:3:4"))
    }

    // ===== 关键词抽取 =====

    @Test
    fun extractsKeywordsAndDropsStopWords() {
        val keywords = extractSwipeTitleKeywords("【合集】竖屏 原神攻略 完整版")
        // 括号段整体丢弃；停用词（合集/竖屏/完整版）不参与
        assertFalse("停用词不许进关键词集合：$keywords", keywords.contains("合集"))
        assertFalse(keywords.contains("竖屏"))
        assertFalse(keywords.contains("完整版"))
        // 中文词是**最长连续汉字段**（2~6 字，与 BiliPai 的贪婪正则同款）：
        // "原神攻略" 之间没有分隔符，所以抽出来是一个 4 字词，而不是"原神"+"攻略"。
        assertTrue("应保留实质词：$keywords", keywords.contains("原神攻略"))
    }

    @Test
    fun extractsSeparatedChineseWords() {
        // 有空格分隔时才是逐词：这就是"关键词交集"判据能生效的形态
        val keywords = extractSwipeTitleKeywords("原神 深渊 攻略 详解")
        assertTrue("$keywords", keywords.containsAll(listOf("原神", "深渊", "攻略", "详解")))
    }

    @Test
    fun normalizesBracketAndPunctuationNoise() {
        // 同一支视频的两个不同后缀标题，规范化后应一致
        assertEquals(
            normalizeSwipeTitleForKeywords("【4K】某个视频 1080P"),
            normalizeSwipeTitleForKeywords("【合集】某个视频 1080P")
        )
    }

    // ===== 三条判据的边界 =====

    @Test
    fun similarWhenKeywordOverlapIsStrong() {
        // 交集 ≥3：标题词几乎一样 → 判相似（哪怕封面不同、时长不同）
        assertTrue(
            areSwipeEntriesSimilar(
                entry("BV1", "原神 深渊 攻略 详解", author = "A", durationText = "10:00"),
                entry("BV2", "原神 深渊 攻略 合集", author = "B", durationText = "20:00"),
            )
        )
    }

    @Test
    fun similarWhenWeakOverlapWithCloseDuration() {
        // 交集 2 且时长差 ≤30s → 判相似
        assertTrue(
            areSwipeEntriesSimilar(
                entry("BV1", "原神 深渊 实战", author = "A", durationText = "10:00"),
                entry("BV2", "原神 深渊 记录", author = "B", durationText = "10:20"),
            )
        )
        // 同样的交集但时长差 31 秒以上 → 不判（"原神 深渊"这种组合太常见）
        assertFalse(
            areSwipeEntriesSimilar(
                entry("BV1", "原神 深渊 实战", author = "A", durationText = "10:00"),
                entry("BV2", "原神 深渊 记录", author = "B", durationText = "10:31"),
            )
        )
    }

    @Test
    fun similarWhenSameOwnerWithLooseDuration() {
        // 同 UP + 交集 ≥1 + 时长差 ≤45s → 判相似
        assertTrue(
            areSwipeEntriesSimilar(
                entry("BV1", "原神 深渊 实战", author = "同一位UP", durationText = "10:00"),
                entry("BV2", "原神 日常 记录", author = "同一位UP", durationText = "10:40"),
            )
        )
        // 换个 UP 就不成立（时长差 40s、交集 1 条）
        assertFalse(
            areSwipeEntriesSimilar(
                entry("BV1", "原神 深渊 实战", author = "UP甲", durationText = "10:00"),
                entry("BV2", "原神 日常 记录", author = "UP乙", durationText = "10:40"),
            )
        )
    }

    @Test
    fun coverEqualityShortCircuits() {
        assertTrue(
            areSwipeEntriesSimilar(
                entry("BV1", "标题甲", cover = "https://x.com/a/cover.jpg?x=1"),
                entry("BV2", "完全不同的标题", cover = "https://x.com/a/cover.jpg?x=2"),
            )
        )
    }

    @Test
    fun differentVideosAreNotKilled() {
        // 负例：两个内容无关的视频，不能因为都有"教程/合集"这类通用词被判相似
        assertFalse(
            areSwipeEntriesSimilar(
                entry("BV1", "钢琴 入门 教程", author = "UP甲", durationText = "05:00"),
                entry("BV2", "显卡 超频 教程", author = "UP乙", durationText = "25:00"),
            )
        )
        // 空标题/无关键词时不该崩、也不该判相似
        assertFalse(
            areSwipeEntriesSimilar(
                entry("BV1", "", durationText = null),
                entry("BV2", "", durationText = null),
            )
        )
    }

    // ===== 队列级：真的挡住了重复推荐 =====

    @Test
    fun appendDropsRepeatedRecommendationWithDifferentSuffix() {
        val anchor = entry("BV0", "锚点视频")
        val accepted = listOf(
            entry("BV1", "【4K】原神 深渊 攻略", author = "UP甲", durationText = "10:00")
        )
        // 同一支视频换个 UP、换套后缀、时长也差一点 → 应该被相似度档挡掉
        val incoming = listOf(
            entry("BV2", "【合集】原神 深渊 攻略", author = "UP乙", durationText = "10:10"),
            entry("BV3", "完全无关的另一支视频", author = "UP丙", durationText = "03:00"),
        )
        val merged = appendSwipeEntries(existing = accepted, incoming = incoming, anchorKey = anchor.key)
        assertEquals("只应留下那支真正不同的视频", 2, merged.size)
        assertTrue(merged.any { it.bvid == "BV1" })
        assertTrue(merged.any { it.bvid == "BV3" })
        assertFalse("重复推荐必须被挡掉", merged.any { it.bvid == "BV2" })
    }
}
