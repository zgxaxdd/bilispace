package com.naaammme.bbspace.feature.video.player

/**
 * 播放器控制件（chrome）的显隐策略（SX-05）。
 *
 * ## 为什么把它抽出来
 * SX-05 要求「进入全屏即隐藏 / **任意点击、拖动**唤出 / 若干秒无操作再隐藏，
 * 且**超时常量走 token，不要写字面量**」。改动前这套判据散在
 * `VideoPlayerPane` 的一个 `LaunchedEffect` 里、超时是裸的 `delay(2_000)`：
 * - 判据写成一串 `&&` 条件 ⇒ 改不动、也测不了；
 * - 超时字面量没有名字 ⇒ 读代码的人不知道"2 秒"是什么档、为什么是 2 秒。
 *
 * 抽成「具名常量 + 纯判据」之后，判据可以**纯 JVM 单测**，
 * 设备侧只剩"看起来够不够沉浸"这一条（见 L4-116）。
 *
 * ## 关于"超时走 token"
 * 这个超时**故意不接** `LocalAnimations` 的时长阶梯：那是「动效速度」设置，
 * 用户在"减少动画/关动效"时会把它压到 ~1ms —— 而这是**空闲超时**、不是动画。
 * 若接上去，"关动效"的用户会看到"控件刚出现就消失"。
 * 所以按本仓同类做法（`SwipeChromeBurnInPolicy.IDLE_DIM_AFTER_MS` 也是具名常量 +
 * 纯判据 + 单测）定义为具名常量，并把取值理由写在下面；调用点不再出现裸字面量。
 */
object PlayerChromePolicy {

    /**
     * 控件出现后，无操作多久自动隐藏。
     *
     * 取 **2s**：与改动前的实际行为**逐字一致**（本轮只给它一个名字，不改手感）。
     * 足够看清标题/进度，又不至于在横屏满屏时长期占画面 —— SX-05 要的就是"尽量沉浸"。
     */
    const val AUTO_HIDE_MS = 2_000L

    /**
     * 控件是否该自动隐藏（纯判据）。
     *
     * 只有**全部**条件成立才隐藏 —— 任一条件不成立都说明"用户正在做别的事"：
     * - [controlsVisible]：本来就没显示，没什么可隐藏（也避免重复触发）；
     * - [isPlaying]：暂停时保持可见（用户就是要在控件上找进度/重播）；
     * - [gestureActive]：正在拖动调亮度/音量/长按加速 ⇒ 别在手指底下把控件收走；
     * - [dialogOpen] / [sheetOpen]：弹层在场时控件必须留着
     *   （SX-05 要点②：不得隐藏"退出全屏/返回"的可用性）。
     */
    fun shouldAutoHide(
        controlsVisible: Boolean,
        isPlaying: Boolean,
        gestureActive: Boolean,
        dialogOpen: Boolean,
        sheetOpen: Boolean,
    ): Boolean = controlsVisible && isPlaying && !gestureActive && !dialogOpen && !sheetOpen

    /**
     * 拖动时是否需要把控件**唤出**（SX-05 要点①：「任意点击、**拖动**唤出」）。
     *
     * 点击唤出早就有了（`videoGestures` 的 `onToggleControls`）；拖动原先只被
     * [shouldAutoHide] 排除、**不**唤出 —— 用户在横屏全屏里上下拖调亮度时控件始终不出现，
     * 正是"想沉浸却无处退"的反面。这里补上这条入口。
     *
     * 只在控件**当前不可见**时才需要唤出：已经可见时再写一次 state 只会白白重启
     * [shouldAutoHide] 的无操作计时（等于把控件无限续期）。
     */
    fun shouldRevealOnGesture(gestureActive: Boolean, controlsVisible: Boolean): Boolean =
        gestureActive && !controlsVisible
}
