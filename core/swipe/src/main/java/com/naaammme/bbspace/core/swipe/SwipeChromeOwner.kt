package com.naaammme.bbspace.core.swipe

/**
 * 刷视频会话**控制层（chrome）的归属**——"屏幕上此刻最多只有一套控制层"的单一真源。
 *
 * ## 为什么要它（第 35 轮 P0-1，真机复现的"严重 bug"）
 * 重构前会话里有**两套**各自独立计时的 chrome：
 * - **会话层**（`SwipeSessionChrome.kt`）：底部信息（@名 / 标题 / 话题）+ 右侧互动栏 + 横屏胶囊，
 *   常驻、无门控；
 * - **播放器层**（`VideoPlayerPane`）：顶栏（返回 / 弹幕 / 旋转 / ⋮）+ 底部控制条（进度 / 音轨 /
 *   画质 / 倍速 / 全屏），由 pane 自己的 `showCtrl` 门控、2 秒自动隐藏。
 *
 * 两套互不知道对方存在 ⇒ 用户点一下屏幕，就同时出现**两个 ⋮、两套顶部图标、两个退出入口**
 * （真机证据 `docs/evidence-r35/27-tap-once.png`）。这不是配色问题，是**没有单一判据**：
 * "现在哪一层控制 UI 该在屏幕上"被两个布尔各答了一次。
 *
 * ## 设计：一个三态枚举 + 四条纯迁移
 * [Story] = 会话层（竖屏默认，对应"沉浸刷视频"）；[Player] = 播放器层（点一下要操作时）；
 * [None] = 全让位（横屏观影的"清屏"，用户 2026-10-04 明确要求过）。
 *
 * 三条硬约束（由 `SwipeChromePolicyTest` 与源码守卫共同钉住）：
 * 1. **[Story] 与 [Player] 互斥**：两层的可见性判据都只读本枚举，任何时刻不可能同时为真；
 * 2. **自动隐藏只回 [Story]**（横屏回 [None]）：pane 的 2 秒超时不再落到"什么都没了"或
 *    "两层都出来了"这两种错态上；
 * 3. **方向变化是模式切换而不是"顺手隐藏"**：进横屏 = 观影模式 = [None]（清屏）；
 *    退横屏 = 回竖屏会话 = [Story]。
 *
 * 纯逻辑（无 Compose / 无 Android 依赖），可直接 JVM 单测。
 */
enum class SwipeChromeOwner {

    /** 会话层：底部信息层 + 互动栏（竖屏刷视频的默认外观）。 */
    Story,

    /** 播放器层：顶栏 + 进度 + 控件行（点一下屏幕才出现，2 秒无操作退回 [Story]）。 */
    Player,

    /** 全让位：横屏观影的"清屏"（不显示任何常驻/控制 UI）。 */
    None,
}

/**
 * [SwipeChromeOwner] 的**唯一**迁移入口（页面只负责调用，不自己算）。
 *
 * 与 `PlaybackPageMode` 同一手法：把"谁该显示"收成纯函数，页面不再各自推导。
 *
 * ⚠️ 这里**刻意只有一个写入口** [onChromeRequested]：点击屏幕（`videoGestures` 的
 * `onToggleControls`）与 2 秒自动隐藏走的是同一个请求通道，差别只在传入的布尔 ——
 * 两个函数会让"自动隐藏"变成"再点一次"那样又点亮控制条（第一版就是这么写错的）。
 */
object SwipeChromePolicy {

    /** 会话开始 / 退横屏后的初始归属：竖屏会话恒从 [SwipeChromeOwner.Story] 起。 */
    fun initial(landscapeForced: Boolean): SwipeChromeOwner =
        if (landscapeForced) SwipeChromeOwner.None else SwipeChromeOwner.Story

    /**
     * **唯一**的状态迁移：`wantsPlayerChrome` = true 表示要播放器层（点击唤出），
     * false 表示收起（再次点击 / 2 秒自动隐藏）。
     *
     * - 收起后的落点是 [autoHideTarget]：竖屏回会话层（信息/互动栏常驻是既定设计，
     *   不能"全没了"）；横屏回清屏（用户 2026-10-04："点横屏后不显示任何东西"）。
     * - 从清屏态唤出同样走这里 —— 横屏里没有别的入口能把人带回竖屏，
     *   所以"点一下能拿到播放器层"本身就是 P0-3 的出口。
     */
    fun onChromeRequested(
        wantsPlayerChrome: Boolean,
        landscapeForced: Boolean,
    ): SwipeChromeOwner = if (wantsPlayerChrome) SwipeChromeOwner.Player else autoHideTarget(landscapeForced)

    /** 收起后的落点：横屏 = 清屏观影，竖屏 = 回会话层。 */
    fun autoHideTarget(landscapeForced: Boolean): SwipeChromeOwner =
        if (landscapeForced) SwipeChromeOwner.None else SwipeChromeOwner.Story

    /** 方向变化（进/退手动横屏）后的归属：横屏 = 清屏观影，竖屏 = 回会话层。 */
    fun onOrientationChanged(landscapeForced: Boolean): SwipeChromeOwner = autoHideTarget(landscapeForced)

    /** 会话层此刻是否可见（**唯一**判据，别处不许再写一份）。 */
    fun storyVisible(owner: SwipeChromeOwner): Boolean = owner == SwipeChromeOwner.Story

    /** 播放器层此刻是否可见（**唯一**判据）。 */
    fun playerVisible(owner: SwipeChromeOwner): Boolean = owner == SwipeChromeOwner.Player

    /**
     * 横屏「竖屏 / 还原」提示条的展示时长（P0-3 验收：进横屏后 3 秒内用户无需点屏幕
     * 就能看到明确的反向入口）。
     */
    const val LANDSCAPE_EXIT_HINT_MS = 3_000L
}
