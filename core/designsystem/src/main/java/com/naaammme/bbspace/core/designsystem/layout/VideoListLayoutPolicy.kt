package com.naaammme.bbspace.core.designsystem.layout

/**
 * 视频列表列数策略（PT-09，语义移植自上游 BiliPai
 * `core/ui/components/.../VideoListLayoutControl.kt:119-121`）。
 *
 * 用户单列偏好优先；网格模式下每列保证 ≥320dp 的最小可读卡宽，
 * ≥960dp 的大窗口推导出第 3 列并封顶，避免大平板内容利用不足。
 * （"960dp 阈值"是 320×3 的自然涌现值，不是独立常量——与上游逐字一致。）
 */
internal fun resolveVideoListColumns(singleColumn: Boolean, availableWidthDp: Float): Int {
    if (singleColumn || availableWidthDp < 320f) return 1
    return (availableWidthDp / 320f).toInt().coerceIn(2, 3)
}
