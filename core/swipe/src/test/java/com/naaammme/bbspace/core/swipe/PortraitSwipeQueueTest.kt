package com.naaammme.bbspace.core.swipe

import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.StoryFeedItem
import com.naaammme.bbspace.core.model.VideoRelate
import com.naaammme.bbspace.core.model.VideoTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏全屏上下滑的队列纯逻辑（去重 / 多样性 / 补流判据）不变式。
 * 对应移植自 BiliPai `PortraitPagerSwitchPolicy` 的合并策略。
 */
class PortraitSwipeQueueTest {

    private fun entry(
        bvid: String,
        title: String = "标题 $bvid",
        cover: String = "https://i0.hdslb.com/bfs/archive/$bvid.jpg",
        author: String? = "UP主A"
    ): SwipeEntry = SwipeEntry(
        target = VideoTarget.Ugc(aid = 0L, cid = 0L, bvid = bvid),
        bvid = bvid,
        title = title,
        cover = cover,
        author = author,
        durationText = null
    )

    private fun relate(
        bvid: String?,
        title: String = "t",
        cover: String = "c",
        author: String? = null,
        aid: Long = 0L
    ) = VideoRelate(
        target = VideoTarget.Ugc(aid = aid, cid = 0L, bvid = bvid),
        title = title,
        cover = cover,
        author = author,
        durationText = null,
        viewText = null,
        danmakuText = null,
        reason = null
    )

    @Test
    fun `bvid 硬去重 - 锚点与既有条目都不会重复入队`() {
        val existing = listOf(entry("BV2"), entry("BV3"))
        val appended = appendSwipeEntries(
            existing = existing,
            incoming = listOf(entry("BV1"), entry("BV2"), entry("BV3")),
            anchorKey = "BV1"
        )
        assertEquals(listOf("BV2", "BV3"), appended.map { it.bvid })
    }

    @Test
    fun `规范化标题相同视为同一视频 - 括号后缀不改变判定`() {
        val existing = listOf(entry("BV2", title = "某教程 从入门到精通"))
        val appended = appendSwipeEntries(
            existing = existing,
            incoming = listOf(entry("BV9", title = "某教程 从入门到精通 【2026新版】")),
            anchorKey = "BV1"
        )
        // 同标题不同 bvid = 重复推荐，丢弃
        assertEquals(listOf("BV2"), appended.map { it.bvid })
    }

    @Test
    fun `封面 key 相同视为同一视频 - CDN 参数差异不改变判定`() {
        val existing = listOf(
            entry("BV2", title = "完全不同的标题", cover = "https://i0.hdslb.com/bfs/archive/abc.jpg@672w")
        )
        val appended = appendSwipeEntries(
            existing = existing,
            incoming = listOf(
                entry("BV9", title = "另一个标题", cover = "https://i2.hdslb.com/bfs/archive/abc.jpg?param=480p")
            ),
            anchorKey = "BV1"
        )
        assertEquals(listOf("BV2"), appended.map { it.bvid })
    }

    @Test
    fun `UP主多样性 - 最近窗口内同作者超限额不收`() {
        // 最近 12 条窗口里 UP主A 已占 2 条 → 第 3 条被拒；
        // 收进一个 UP主C 后窗口内 A 仍是 2 条 → 再来的 A 仍被拒。
        val existing = buildList {
            repeat(10) { i -> add(entry("BV0$i", author = "UP主B$i")) }
            add(entry("BV10", author = "UP主A"))
            add(entry("BV11", author = "UP主A"))
        }
        val appended = appendSwipeEntries(
            existing = existing,
            incoming = listOf(
                entry("BV12", author = "UP主A"),
                entry("BV13", author = "UP主C"),
                entry("BV14", author = "UP主A")
            ),
            anchorKey = "BV1"
        )
        assertEquals(existing.map { it.bvid } + listOf("BV13"), appended.map { it.bvid })
    }

    @Test
    fun `只追加不重排 - 追加后既有顺序保持`() {
        val existing = listOf(
            entry("BV2", author = null),
            entry("BV3", author = null)
        )
        val incoming = listOf(
            entry("BV5", author = null),
            entry("BV4", author = null)
        )
        val appended = appendSwipeEntries(
            existing = existing,
            incoming = incoming,
            anchorKey = "BV1"
        )
        // 硬约束不变：**已有页的顺序一个都不许动**（pager 以页号为身份）
        assertEquals(listOf("BV2", "BV3"), appended.take(2).map { it.bvid })
        // 新批次成员不丢（顺序由 arrangeSwipeEntries 的洗牌决定，见 SwipeArrangementTest）
        assertEquals(setOf("BV2", "BV3", "BV4", "BV5"), appended.map { it.bvid }.toSet())
        // 同一锚点（同一 seed）重复合并 → 顺序可复现
        val again = appendSwipeEntries(
            existing = existing,
            incoming = incoming.reversed(),
            anchorKey = "BV1"
        )
        assertEquals(appended.map { it.bvid }, again.map { it.bvid })
    }

    @Test
    fun `relates 映射 - bvid 缺失时本地补全，补不出来才退回 aid 兜底`() {
        // relate 卡的 uri 是 aid 格式时 bvid 解析为空（真机 bug：只认 bvid 会把整页
        // 推荐全滤光 → 滑动队列为空）；bvid/aid 全缺才丢弃。
        // 现在 bvid 由**本地换算**补全（移植 BiliPai IdUtils，真值见 VideoTargetToolIdTest）
        // ——只有超出换算范围的 aid（avToBv 返回 null，宁可不给也不给错的）才真正落到
        // aid 兜底键。
        val converted = com.naaammme.bbspace.core.model.VideoTargetTool.avToBv(113_000_000L)
        assertTrue("113000000 应当能被换算（否则本用例失去意义）", converted != null)
        val entries = swipeEntriesFromRelates(
            listOf(
                relate("BV1"),
                relate(null, aid = 0L),
                relate(null, aid = 113_000_000L),
                relate(null, aid = Long.MAX_VALUE),
                relate("BV2")
            )
        )
        assertEquals(
            listOf("BV1", converted, "aid:${Long.MAX_VALUE}", "BV2"),
            entries.map { it.key }
        )
    }

    @Test
    fun `标题规范化 - 去括号段与标点并小写`() {
        assertEquals("从入门到精通", normalizeSwipeTitle("从入门到精通【2026新版】"))
        assertEquals("abc123", normalizeSwipeTitle("ABC-123!"))
        assertEquals("", normalizeSwipeTitle("【纯括号】"))
    }

    // ── 页身份（真机 bug：翻页后新视频不播放）──────────────────────────────────

    /**
     * 真机症状：竖屏全屏能上下滑，但**翻到下一页视频不播放**（只有封面）。
     *
     * 根因是页身份两侧在不同时刻算：队列条目的 `key` 建队时算（relate 卡没有 bvid →
     * `aid:…`），而 pager 的 `currentKey` 从 `videoState.ids` 算，`ids` 在取流/详情
     * 返回后会被服务端补齐的 bvid **整体覆盖** → 字符串在"取流完成"的一瞬间翻转，
     * 播放器页落空。下面这条用例就是那个瞬间。
     */
    @Test
    fun `页身份 - 取流补齐 bvid 前后必须一致（aid-only 条目）`() {
        val aidOnly = SwipeEntry(
            target = VideoTarget.Ugc(aid = 117_339_261_503_379L, cid = 42L, bvid = null),
            bvid = "",
            title = "t",
            cover = "c",
            author = null,
            durationText = null
        )
        // 建队时的身份键（relate 卡没有 bvid）
        assertEquals("aid:117339261503379", aidOnly.key)
        // 取流中：ids 还是请求参数（bvid 为空）
        assertTrue("取流中就该认定这一页归播放器", aidOnly.isCurrentVideo(null, 117_339_261_503_379L))
        // 取流完成：applyDetailResult 把 ids 整体换成服务端解析结果（bvid 被补齐）
        assertTrue(
            "取流补齐 bvid 后必须仍然认定是同一视频——否则播放器页在这一瞬间落空，" +
                "表现就是「能滑动但不播放下一个」（字符串 key 在这里会从 aid:… 翻成 BV…）",
            aidOnly.isCurrentVideo("BV1Kyas6wEuz", 117_339_261_503_379L)
        )
        // 反证：字符串比较在这里必然失配
        assertTrue(aidOnly.key != swipeKey("BV1Kyas6wEuz", 117_339_261_503_379L))
    }

    @Test
    fun `页身份 - 不同视频不命中（切到别的视频后旧页必须让出播放器）`() {
        val entry = SwipeEntry(
            target = VideoTarget.Ugc(aid = 100L, cid = 1L, bvid = "BV1xx411c7mD"),
            bvid = "BV1xx411c7mD",
            title = "t",
            cover = "c",
            author = null,
            durationText = null
        )
        assertTrue(entry.isCurrentVideo("BV1xx411c7mD", 100L))
        // 只看 aid 就够：bvid 后来被补齐/改写都不影响
        assertTrue(entry.isCurrentVideo("BV17x411w7KC", 100L))
        assertFalse(entry.isCurrentVideo("BV17x411w7KC", 200L))
        assertFalse(entry.isCurrentVideo(null, 200L))
    }

    @Test
    fun `页身份 - 两侧都没有 aid 时才退回比 bvid`() {
        val noAid = SwipeEntry(
            target = VideoTarget.Ugc(aid = 0L, cid = 0L, bvid = "BV1xx411c7mD"),
            bvid = "BV1xx411c7mD",
            title = "t",
            cover = "c",
            author = null,
            durationText = null
        )
        assertTrue(noAid.isCurrentVideo("BV1xx411c7mD", 0L))
        assertFalse(noAid.isCurrentVideo("BV17x411w7KC", 0L))
        // aid 缺失且 bvid 也拿不到 → 不命中（宁可不给播放器，也不能给错页）
        val neither = noAid.copy(bvid = "", target = VideoTarget.Ugc(aid = 0L, cid = 0L, bvid = null))
        assertFalse(neither.isCurrentVideo(null, 0L))
        assertFalse(neither.isCurrentVideo("BV1xx411c7mD", 0L))
    }

    @Test
    fun `身份键 - 与 SwipeEntry_key 同构`() {
        assertEquals("BV1xx411c7mD", swipeKey("BV1xx411c7mD", 2L))
        assertEquals("aid:2", swipeKey(null, 2L))
        assertEquals("aid:2", swipeKey("  ", 2L))
        assertEquals("aid:0", swipeKey(null, 0L))
    }

    // ── 条目映射：三个数据源都可能只给 aid，必须本地补全 bvid ──────────────────

    /**
     * relate 卡的 uri 是 aid 格式、首页推荐流的 App 接口对新视频可能不带 bvid、
     * story 流的 `player_args.bvid` 也可能为空 —— 三处都要补。
     *
     * BiliPai 的做法是**丢弃**没有 bvid 的推荐卡（`bvid.isEmpty() → null`），bbspace
     * 选择本地换算补齐：真机实测换算结果与取流后服务端解析出的 bvid 完全一致
     * （见 `VideoTargetToolIdTest` 的 live API 真值）。
     */
    @Test
    fun `条目映射 - 只有 aid 的卡片本地补全 bvid（relate）`() {
        val entries = swipeEntriesFromRelates(
            listOf(relate(null, aid = 117_339_261_503_379L, title = "只有 aid 的卡"))
        )
        assertEquals(1, entries.size)
        assertEquals("BV1Kyas6wEuz", entries[0].bvid)
        assertEquals("BV1Kyas6wEuz", entries[0].key)
        // 补的是 **target** 而不只是条目字段：进栈/上报/历史都拿 target
        assertEquals("BV1Kyas6wEuz", entries[0].target.bvid)
    }

    @Test
    fun `条目映射 - 只有 aid 的卡片本地补全 bvid（story 与首页推荐流）`() {
        val story = swipeEntryFromStory(
            StoryFeedItem(
                aid = 170_001L,
                cid = 279_786L,
                bvid = "",
                title = "t",
                cover = "c",
                author = null,
                authorMid = 0L,
                durationSec = 60L
            )
        )
        assertEquals("BV17x411w7KC", story?.bvid)
        assertEquals("BV17x411w7KC", story?.target?.bvid)

        val feed = swipeEntryFromFeedItem(
            feedItem(target = VideoTarget.Ugc(aid = 2L, cid = 62_131L, bvid = null))
        )
        assertEquals("BV1xx411c7mD", feed?.bvid)
        assertEquals("BV1xx411c7mD", feed?.target?.bvid)
    }

    @Test
    fun `条目映射 - 超范围 aid 换算不出来时退回 aid 兜底而不是丢弃`() {
        val entries = swipeEntriesFromRelates(listOf(relate(null, aid = Long.MAX_VALUE)))
        assertEquals(1, entries.size)
        assertEquals("", entries[0].bvid)
        assertEquals("aid:${Long.MAX_VALUE}", entries[0].key)
        assertTrue(entries[0].isCurrentVideo(null, Long.MAX_VALUE))
    }

    private fun feedItem(target: VideoTarget?): FeedItem = FeedItem(
        cardType = "small_cover_v2",
        cardGoto = "av",
        goto = "av",
        param = "1",
        uri = "bilibili://video/1",
        title = "t",
        cover = "c",
        coverLeftText1 = null,
        coverLeftText2 = null,
        coverRightText = null,
        idx = 0L,
        target = target,
        liveRoute = null,
        descButton = null,
        rcmdReason = null,
        args = null,
        threePointV2 = null,
        dislikeContext = null
    )

    @Test
    fun `补流判据 - 进入倒数第2页即触发`() {
        assertTrue(shouldExtendSwipeQueue(committedPage = 8, totalPages = 10))
        assertTrue(shouldExtendSwipeQueue(committedPage = 9, totalPages = 10))
        assertFalse(shouldExtendSwipeQueue(committedPage = 7, totalPages = 10))
        assertFalse(shouldExtendSwipeQueue(committedPage = 0, totalPages = 0))
    }
}
