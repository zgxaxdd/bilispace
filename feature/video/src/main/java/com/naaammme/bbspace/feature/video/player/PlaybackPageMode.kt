package com.naaammme.bbspace.feature.video.player

import androidx.compose.runtime.saveable.Saver

/**
 * 播放页的**页面模式**：刷视频会话（story / swipe）与详情页全屏状态的**单一真源**。
 *
 * ## 为什么要有它（第 34 轮重构的核心）
 * 用户原话：「刷视频模式全是bug，完全无法正常使用，请全面重构相关功能」。
 * 静态复核找到的结构性根因是：同一件事（"现在处于哪种页面模式"）被 **5 个可变布尔
 * 互相推导**：
 *
 * | 布尔 | 定义位置（重构前） | 谁在改 |
 * |---|---|---|
 * | `isFull` | `VideoScreen.kt:239` | `enterFullscreen` / `exitFullscreen` / 下拉提交 |
 * | `swipeSessionActive` | `:372` | `:377` 的 effect（三处分支） |
 * | `swipeModeRequested` | `:376` | 下拉提交置真 / 离开全屏复位 |
 * | `swipeLandscape` | `:463` | 横屏钮翻转 / `:464` effect 复位 |
 * | `storyMode`（参数） | `:184` 入参 | 不变量 |
 *
 * 派生的 `fullOn` / `effectiveStoryMode` / `portraitSwipeActive` / `pagerActive`
 * 又从上面 5 个各自算一遍 ⇒ **合成态**（例如 `isFull=true` 而
 * `swipeSessionActive=false` 的那一拍）没有任何类型约束，`pagerActive` 一假，
 * 刷视频流就塌回单视频播放页。这就是用户看到的"画面忽然跳回去"一类症状。
 *
 * ## 设计
 * 三个状态 + 六个纯迁移函数。**页面上不再有中间布尔**：所有派生量都是本状态上的
 * 只读属性（`isFull` / `swipeSessionActive` / `swipeModeRequested` / `landscapeForced`
 * / `locksPortrait` / `isStory`），页面只能通过迁移函数改变它们。
 *
 * 纯逻辑（无 Compose 依赖、无 Android 依赖），可直接 JVM 单测 ——
 * 见 `PlaybackPageModeTest` 的四条迁移路径。
 */
sealed interface PlaybackPageMode {

    /** 详情页紧凑态（竖屏、非全屏）——默认模式。 */
    data object Compact : PlaybackPageMode

    /**
     * 详情页全屏（单视频全屏；**可能**已携带"进刷视频"的显式请求）。
     *
     * @param swipeRequested 「这次进全屏的来路是简介区边缘下滑」。
     *   它是**一次性**的：离开全屏即清、按钮进全屏不带（R3：全屏钮只进全屏）。
     * @param sessionActive 刷视频会话真的被激活了（= 有显式请求 **且** 队列非空）。
     *   与 [swipeRequested] 分开，是因为"用户想刷"与"系统真的有内容可刷"是两件事：
     *   队列为空时保持单视频全屏，正是既有且正确的行为。
     * @param landscape 用户显式要横屏（只对当前这一支视频生效）。
     */
    data class DetailFullscreen(
        val swipeRequested: Boolean = false,
        val sessionActive: Boolean = false,
        val landscape: Boolean = false,
    ) : PlaybackPageMode

    /**
     * 刷视频模式（首页顶栏入口直开）：**常驻全屏**，没有紧凑态。
     * 锚点/队列由 `bootstrapStorySession` 负责；退出（还原/返回）= 露出当前这支的详情页。
     *
     * @param landscape 用户显式要横屏（同样只对当前这一支生效）。
     */
    data class Story(val landscape: Boolean = false) : PlaybackPageMode

    // ── 派生量（只读；页面不许自己再推一遍）────────────────────────────────────

    /** 是否处于全屏（story 恒真）。 */
    val isFull: Boolean get() = this !is Compact

    /** 刷视频会话是否在渲染（story 恒真）。 */
    val swipeSessionActive: Boolean get() = this is Story || (this is DetailFullscreen && sessionActive)

    /** 「这次进全屏是下滑请求来的」——只有详情全屏态可能为真。 */
    val swipeModeRequested: Boolean get() = this is DetailFullscreen && swipeRequested

    /** 用户显式要横屏。 */
    val landscapeForced: Boolean
        get() = when (this) {
            is Story -> landscape
            is DetailFullscreen -> sessionActive && landscape
            Compact -> false
        }

    /** 是否处于 story（顶栏入口）模式。 */
    val isStory: Boolean get() = this is Story

    /**
     * 会话期间是否**锁竖屏**（刷视频连播的画面节奏由 pager 说了算，不听传感器）。
     *
     * 与 [swipeModeRequested] 分开的理由（真机 bug：每次下滑都转两次屏）：
     * 请求已置位、会话尚未激活的那一拍**也必须**按锁竖屏处理 —— 否则
     * 「单视频全屏自动横屏」分支会在空窗里朝 SENSOR_LANDSCAPE 发一次请求，
     * 会话激活后再锁回竖屏，用户就看见"先横屏、再切回刷视频"的闪跳。
     */
    val locksPortrait: Boolean get() = swipeSessionActive || swipeModeRequested || isStory

    /**
     * 是否允许「单视频全屏自动横屏」：全屏 + 未锁竖屏 + 没在等会话激活。
     * 这一条把原来 `DisposableEffect` 里的 `when` 分支收成一处判据。
     */
    val allowsAutoLandscape: Boolean get() = isFull && !locksPortrait

    // ── 迁移函数（唯一的改写入口）──────────────────────────────────────────

    /** 进全屏（「全屏」钮 / 下拉提交都会走到；story 态原地不动）。 */
    fun enterFullscreen(): PlaybackPageMode = when (this) {
        Compact -> DetailFullscreen()
        is DetailFullscreen, is Story -> this
    }

    /**
     * 简介区顶边下滑提交 = 刷视频的**唯一**显式入口。
     *
     * 语义是「**进全屏 + 带一次性请求**」：从 [Compact] 调用时直接落到
     * `DetailFullscreen(swipeRequested = true)`。这一点很重要 —— 重构前这里是
     * `isFull = true` 与 `swipeModeRequested = true` **两次独立赋值**，
     * 中间存在"已全屏但还没请求"的空窗，方向 effect 会在那段空窗里朝
     * SENSOR_LANDSCAPE 发一次请求，会话激活后再锁回竖屏 —— 真机表现为
     * 「先横屏、再切回刷视频」的闪跳。一次迁移彻底消除了那个空窗。
     *
     * 只置请求，**不**直接激活会话（会话要等锚点建好、队列非空）。
     */
    fun requestSwipeSession(): PlaybackPageMode = when (this) {
        Compact -> DetailFullscreen(swipeRequested = true)
        is DetailFullscreen -> copy(swipeRequested = true)
        is Story -> this
    }

    /**
     * 会话激活判定：`swipeRequested && queueNotEmpty`。
     * 幂等（反复调用结果相同），供 VideoScreen 的接线 effect 调用。
     */
    fun resolveSwipeSession(queueNotEmpty: Boolean): PlaybackPageMode = when (this) {
        is DetailFullscreen -> copy(sessionActive = swipeRequested && queueNotEmpty)
        else -> this
    }

    /**
     * 退出全屏 / 退出会话：
     * - story → **Compact**（需求 3：退出 story 要露出当前这支的详情页，而不是关掉整页）；
     * - 详情全屏 → Compact；
     * - Compact 原地不动。
     */
    fun exitFullscreen(): PlaybackPageMode = when (this) {
        Compact -> this
        is DetailFullscreen, is Story -> Compact
    }

    /** 手动横屏开关（只对当前这一支视频生效；切视频由 [onVideoChanged] 收回）。 */
    fun toggleLandscape(): PlaybackPageMode = when (this) {
        is Story -> copy(landscape = !landscape)
        is DetailFullscreen -> copy(landscape = !landscape)
        Compact -> this
    }

    /**
     * 切换视频（ids 变化）：横屏**只对点名的那一支生效**，切走即收回
     * （对齐 BiliPai 的 `onRotateToLandscape` 语义）。
     */
    fun onVideoChanged(): PlaybackPageMode = when (this) {
        is Story -> copy(landscape = false)
        is DetailFullscreen -> copy(landscape = false)
        Compact -> this
    }

    /**
     * 返回键的**动作**（纯判据，页面只负责执行）。
     *
     * 与重构前 `handleBack` 的三分支逐条对应，但优先级收敛在这里，
     * 不再由页面自己按 `effectiveStoryMode` / `fullOn` 重新排一遍。
     */
    fun backAction(): BackAction = when (this) {
        Compact -> BackAction.PassThrough
        // story 没有紧凑态可回：返回 = 关闭整个页面（需求：按成对返回动画收起）
        is Story -> BackAction.ClosePage
        is DetailFullscreen -> BackAction.ExitFullscreen
    }

    /** [backAction] 的结果。 */
    enum class BackAction {
        /** story：关闭整个详情页。 */
        ClosePage,

        /** 详情全屏：形变回缩到详情页。 */
        ExitFullscreen,

        /** 紧凑态：交给宿主的栈（`popPage` / 关页）。 */
        PassThrough,
    }

    companion object {
        /** 初始模式：story 入口直接常驻全屏，其余从紧凑态起。 */
        fun initial(storyMode: Boolean): PlaybackPageMode = if (storyMode) Story() else Compact

        /**
         * 编码成**可读字符串**（而不是 index：后者一改枚举顺序就静默错档）。
         *
         * 抽成**纯函数**而不是直接写进 `Saver` 的 lambda：`Saver.save` 的 receiver 是
         * `SaverScope`（还带一个"值可保存"的校验），把它引进单测等于给纯逻辑加一层
         * Compose 依赖。纯函数版可以被 `PlaybackPageModeTest` 直接往返验证。
         */
        fun encode(mode: PlaybackPageMode): String = when (mode) {
            Compact -> "compact"
            is Story -> "story:${mode.landscape}"
            is DetailFullscreen ->
                "detail:${mode.swipeRequested}:${mode.sessionActive}:${mode.landscape}"
        }

        /**
         * [encode] 的逆运算。**未知/损坏的编码一律保守回 [Compact]** ——
         * 宁可少一层全屏（用户再点一次就能进），也不要卡在一个解释不了的坏状态里。
         */
        fun decode(raw: String?): PlaybackPageMode = when {
            raw == null -> Compact
            raw == "compact" -> Compact
            raw.startsWith("story:") -> Story(landscape = raw.substringAfter(':').toBoolean())
            raw.startsWith("detail:") -> {
                val parts = raw.substringAfter(':').split(':')
                DetailFullscreen(
                    swipeRequested = parts.getOrNull(0)?.toBoolean() ?: false,
                    sessionActive = parts.getOrNull(1)?.toBoolean() ?: false,
                    landscape = parts.getOrNull(2)?.toBoolean() ?: false
                )
            }
            else -> Compact
        }

        /**
         * `rememberSaveable` 用的 Saver（[encode] / [decode] 的薄适配）。
         *
         * 为什么需要它：重构前 4 个布尔各有自己的 `rememberSaveable`，进程重建后由
         * **各自的初值**重新推导，存在"模式对了但横屏对不上"的错档；现在整件事是
         * 一个原子值，要么整体恢复、要么整体回 Compact。
         */
        val Saver: Saver<PlaybackPageMode, String> = Saver(
            save = { mode -> encode(mode) },
            restore = { raw -> decode(raw) }
        )
    }
}
