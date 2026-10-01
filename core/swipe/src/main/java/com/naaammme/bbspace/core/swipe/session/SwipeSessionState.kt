package com.naaammme.bbspace.core.swipe.session

import com.naaammme.bbspace.core.swipe.SwipeEntry

/**
 * 刷视频会话的来路。两条入口的数据链完全不同（任务书 §2.2）：
 * - [DetailPullDown]：详情页简介区边缘下滑，队列从当前详情自带的 relates 起步；
 * - [StoryFeed]：首页顶栏芯片（story 入口），队列从首页推荐流 / story 竖屏流起步。
 */
enum class SwipeEntryVia { DetailPullDown, StoryFeed }

/**
 * 刷视频会话的状态机（任务书 §4.3 / SW-02）——会话的**单一真值源**。
 *
 * 迁移期约定（SW-02 的行为零变化红线）：
 * - **锚点流在 `Ready` 之前不发布**。`Bootstrapping` 虽然携带种子（给未来的状态派生
 *   UI 用），但 `SwipeSessionController.anchor` 在 story 流到达前保持 null——
 *   VideoScreen 的 pager 启用判据是"锚点非空 + 队列非空"，提前发布会让 pager 分支
 *   在流在途时激活，改变加载层的行为（守卫 `homeStoryEntryWiring` 锁的就是这条链）。
 * - `PlaybackFailed.id` 在 SW-03 类型化 `SwipeId` 落地前先用既有字符串 key 承载，
 *   SW-03 同批迁移。
 */
sealed interface SwipeSessionState {
    /** 无会话（退出后 / 从未进入）。 */
    data object Idle : SwipeSessionState

    /**
     * 起步在途（story 流尚未返回）。
     * UI 在此状态必须呈现封面 + 粒子的加载层（现状由 VideoScreen 的
     * `effectiveStoryMode` 判据承担；SW-13 之后改由本状态派生）。
     */
    data class Bootstrapping(
        val via: SwipeEntryVia,
        /** 种子条目（页 0 的候选）；在 [SwipeSessionState.Ready] 到来前**不**进锚点流。 */
        val anchor: SwipeEntry,
    ) : SwipeSessionState

    /** 可浏览：页 [SwipeSessionState.Ready.page] / 共 [SwipeSessionState.Ready.size] 页。 */
    data class Ready(val page: Int, val size: Int) : SwipeSessionState

    /**
     * 补货进行中（粘滞态：`PageChanged` 不清掉；退出回 `Idle`，补到货回 `Ready`）。
     * UI 停在最后一页时呈现"正在为你找更多视频…"。
     */
    data object Extending : SwipeSessionState

    /**
     * 补货跑完仍一无所获（粘滞态，同上）：停在最后一页时呈现"已经是最后一个了"。
     */
    data object Exhausted : SwipeSessionState

    /** 当前页取流/解码失败；[SwipeSessionState.PlaybackFailed.retryable] 为 false 时 UI 禁用重试按钮。 */
    data class PlaybackFailed(
        /** 当前页的身份键（SW-03 起为 [com.naaammme.bbspace.core.swipe.SwipeEntry] 的类型化 id）。 */
        val key: String,
        val retryable: Boolean,
    ) : SwipeSessionState
}
