package com.naaammme.bbspace.core.designsystem.component

/**
 * 分页"增长守卫"（AP-10）：**只有列表确实变长才允许下一次预取**。
 *
 * ## 缺陷（AdaptiveMediaGrid.kt 的同款注释是本范式的出处）
 *
 * "预取下一页"的谓词如果随 `isLoadingMore` 翻转（直接写在谓词里，或经
 * `canLoadMore` 的 getter 折算进来），那么服务端返回**空页 / 全重复页**
 * （`next` 非空、列表不增长）时，`isLoadingMore` 的 true→false 会让预取
 * `LaunchedEffect` 反复重启 → 无界请求风暴。
 *
 * ## 语义
 *
 * - [tryAcquire]：领取"本次预取权"。领取后未复位前，再次调用一律 false ——
 *   effect 因任何原因重启（谓词翻转、remember key 重建）都不会再发请求。
 * - [onItemLineChanged]：列表条数变化时调用；**确实变长**（或换血后从 0 重新来）
 *   才复位守卫，空页 / 全重复页不复位。
 * - [onVisibility]：预取可见性变化时调用。只有**真的滚离**预取区
 *   （`shouldLoadMore=false` 且加载不在飞）才复位 —— 给"滚走再回来"留合法重试；
 *   加载中的假离（谓词含 `!isLoadingMore` 时翻成 false）**不复位**。
 *
 * ## 与 AdaptiveMediaGrid 的关系
 *
 * AdaptiveMediaGrid 内联实现了同一语义（其 effect 同时 key 了 isLoadingMore 与
 * itemCount），是本类的出处；它已被真机验证且注释完备，保持原样。
 * 本类供**普通 LazyColumn 各页**（搜索 / 动态 / IM 会话 / 评论主楼 / 楼中楼 /
 * 评论搜索 / 发布记录 / 收藏列表）使用，配对形态：
 *
 * ```
 * val loadMoreGuard = remember { LoadMoreGrowthGuard() }
 * LaunchedEffect(itemCount) { loadMoreGuard.onItemLineChanged(itemCount) }
 * LaunchedEffect(shouldLoadMore, busy) {
 *     loadMoreGuard.onVisibility(shouldLoadMore, busy)
 *     if (shouldLoadMore && loadMoreGuard.tryAcquire()) viewModel.loadMore()
 * }
 * ```
 */
class LoadMoreGrowthGuard {

    private var attempted = false
    private var lastGrowCount = -1

    /** 领取预取权：true = 调用方应立即发起一次 loadMore；false = 守卫未复位，跳过。 */
    fun tryAcquire(): Boolean {
        if (attempted) return false
        attempted = true
        return true
    }

    /**
     * 列表条数变化时调用。只有条数真的变了且 > 0 才复位（0 → 空窗口不复位，
     * 防止"清空重搜"的中间态误开闸；新首屏到达的 0 → N 自会复位）。
     */
    fun onItemLineChanged(itemCount: Int) {
        if (itemCount > 0 && itemCount != lastGrowCount) {
            attempted = false
            lastGrowCount = itemCount
        }
    }

    /**
     * 预取可见性变化时调用。[busy] = 加载在飞（isLoadingMore / 首屏 isLoading）。
     * `shouldLoadMore=false` 且 `busy=false`（真的滚离）才复位。
     */
    fun onVisibility(shouldLoadMore: Boolean, busy: Boolean) {
        if (!shouldLoadMore && !busy) attempted = false
    }
}
