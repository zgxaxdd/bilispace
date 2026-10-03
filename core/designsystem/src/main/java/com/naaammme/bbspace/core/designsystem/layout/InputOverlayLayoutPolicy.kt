package com.naaammme.bbspace.core.designsystem.layout

/**
 * 播放页输入弹层（评论、弹幕）在宽窗口下的最大宽度策略（PT-09，语义移植自上游
 * BiliPai `feature/video/ui/components/.../InputOverlayLayoutPolicy.kt:13`）。
 *
 * 紧凑窗口保持全宽；Medium 及以上（≥600dp）限宽 640dp 并水平居中，
 * 避免输入面板横贯整个平板窗口。bbspace 无折叠屏铰链安全区逻辑（见移植任务书 §4-S5），
 * 上游 KDoc 里"HingeSafeInputOverlayHost 先收进铰链安全区"一段不适用。
 */
internal fun resolveBottomInputOverlayMaxWidthDp(windowWidthDp: Int): Int {
    return if (windowWidthDp < 600) windowWidthDp else 640
}
