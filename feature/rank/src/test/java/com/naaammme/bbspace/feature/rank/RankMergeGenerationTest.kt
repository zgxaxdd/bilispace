package com.naaammme.bbspace.feature.rank

import com.naaammme.bbspace.core.model.rank.RankEntry
import com.naaammme.bbspace.core.model.rank.RankType
import com.naaammme.bbspace.core.model.rank.RankVideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 排行页 loadMore 写回的**代际校验**状态测（AP-12 ③：type A 在途 → 切到 type B →
 * A 的响应到达后不得改写 B 的 state 与缓存）。
 *
 * 判定抽成 [RankViewModel.mergedStateIfCurrent] / [RankViewModel.failureStateIfCurrent]
 * 两个纯函数。任何把它们内联回 update lambda 的重构都必须把这份测试一起搬走。
 *
 * 缓存一侧由调用点保证：`mergedStateIfCurrent` 返回 null 时 `cacheCurrentLocked`
 * 不会被调用（loadMore 的 onSuccess 分支），结构守卫钉住该接线。
 */
class RankMergeGenerationTest {

    private fun entry(aid: Long) = RankEntry.Video(
        RankVideoItem(
            aid = aid,
            bvid = "BV$aid",
            cid = aid,
            title = "t$aid",
            cover = null,
            durationSec = 1L,
            ownerMid = 2L,
            ownerName = "up",
            ownerFace = null,
            view = 1L,
            danmaku = 1L,
            tname = null
        )
    )

    /** type A 在途 → 用户切到 type B → A 的响应到达：必须整包丢弃。 */
    @Test
    fun staleResponseTypeIsDroppedEntirely() {
        val stateB = RankUiState(type = RankType.MUSIC, entries = listOf(entry(100)))
        val merged = RankViewModel.mergedStateIfCurrent(
            cur = stateB,
            requestedType = RankType.POPULAR, // 请求时还是 POPULAR
            more = listOf(entry(200))
        )
        assertNull("过期响应必须返回 null（连 isLoadingMore 都不许动 B 的）", merged)
        // 失败路径同理：A 的错误不许弹到 B
        assertNull("过期失败必须返回 null", RankViewModel.failureStateIfCurrent(stateB, RankType.POPULAR, "网络错误"))
    }

    /** 类型未变：正常合并 + canLoadMore 按新页是否非空。 */
    @Test
    fun currentTypeMergesNormally() {
        val stateA = RankUiState(
            type = RankType.POPULAR,
            entries = listOf(entry(1), entry(2)),
            isLoadingMore = true,
            canLoadMore = true
        )
        val merged = RankViewModel.mergedStateIfCurrent(stateA, RankType.POPULAR, listOf(entry(2), entry(3)))
        assertEquals(
            "合并后 entries 按 key 去重（重叠窗口）",
            listOf("v_1_1", "v_2_2", "v_3_3"),
            merged?.entries?.map(RankEntry::key)
        )
        assertEquals(false, merged?.isLoadingMore)
        assertEquals(true, merged?.canLoadMore)
    }

    /** 空页（more 为空）→ canLoadMore=false；失败分支只复位加载与错误。 */
    @Test
    fun emptyPageClosesLoadMoreAndFailureOnlyTouchesFlags() {
        val stateA = RankUiState(type = RankType.POPULAR, entries = listOf(entry(1)), isLoadingMore = true)
        val merged = RankViewModel.mergedStateIfCurrent(stateA, RankType.POPULAR, emptyList())
        assertEquals(false, merged?.canLoadMore)
        val failed = RankViewModel.failureStateIfCurrent(stateA, RankType.POPULAR, "boom")
        assertEquals(false, failed?.isLoadingMore)
        assertEquals("boom", failed?.errorMessage)
        assertEquals("失败分支不得动 entries", 1, failed?.entries?.size)
    }
}
