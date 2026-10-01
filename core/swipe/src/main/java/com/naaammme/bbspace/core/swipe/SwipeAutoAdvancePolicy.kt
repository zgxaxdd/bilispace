package com.naaammme.bbspace.core.swipe

/**
 * 「播完要不要自动翻到下一支」的判据（对照 BiliPai 的 `playbackCompletionBehavior`：
 * 它把"播完干什么"做成可配枚举 STOP/REPEAT/CONTINUE，这里是最小可用版本 ——
 * 一个开关 + 与循环模式互斥的说明）。
 *
 * 为什么值得单测：这条判据写错的两种后果都很明显 ——
 * 漏判 = 用户关掉了开关却还在自动翻页；误判 = 开着开关却停在结尾（连播断掉）。
 *
 * @param autoAdvanceEnabled 设置项 `PlayerPlaybackPrefs.swipeAutoAdvance`
 * @param repeatOne 播放器当前是不是单曲循环（循环时不会收到 `STATE_ENDED`，
 *   所以这条其实轮不到判；显式写上是为了让"两者互斥"这件事在代码里可见）
 * @param isLastPage 当前是不是最后一页（最后一页后面没有内容可翻）
 * @param alreadyAdvancedForThisVideo 这一支是不是已经因为 `STATE_ENDED` 翻过一次
 *   （幂等键：某些机型会连发两次，翻两次就是"跳过了一支"）
 * @param isScrubbing 用户是不是正在拖进度条（SW-15）：拖动中播完不该翻页——
 *   用户的注意力在进度条上，此时翻页等于把他的操作"打断并丢弃"
 */
fun shouldAutoAdvanceToNextSwipePage(
    autoAdvanceEnabled: Boolean,
    repeatOne: Boolean,
    isLastPage: Boolean,
    alreadyAdvancedForThisVideo: Boolean,
    isScrubbing: Boolean = false,
): Boolean {
    if (isScrubbing) return false
    if (!autoAdvanceEnabled) return false
    if (repeatOne) return false
    if (isLastPage) return false
    if (alreadyAdvancedForThisVideo) return false
    return true
}
