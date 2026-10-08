package com.naaammme.bbspace.core.model

/**
 * 弹幕**分段窗口**策略 —— 逐字移植自 BiliPai
 * `feature/video/danmaku/DanmakuSegmentWindowPolicy.kt`。
 *
 * ## 移植时改了什么（只有两处，判定与常量一字未动）
 *
 * 1. **可见性 `internal` → 公开**：源的消费方在同模块（`app`）；本仓弹幕会话在
 *    `core:playback`，跨模块必须公开。
 * 2. **分段换算复用本仓既有实现**：源自己算 `positionMs / 360_000 + 1`，
 *    本仓 [VOD_DANMAKU_SEGMENT_DURATION_MS] / [toDanmakuWindowId] 已是同一件事
 *    （同样是 6 分钟、同样 1 起算）—— 不重复造第二份真相，改为调用既有函数。
 *
 * ## 它解决什么（本仓缺的那一块）
 *
 * 本仓 `VodDanmakuSession` 原先是**单窗口**：位置跨段时才去拉新段，拉到就换，
 * 于是"段边界附近的空窗"只能靠一次临近时提前取一段来缓解，且**同一段会被重复请求**、
 * **回看时在途请求白跑**。
 *
 * 源的做法是**锚点 ± 1**：当前段加上前后各一段一起算作"该显示/该预取"的窗口，
 * 并给出"何时该请求、何时该取消在途请求"的判定。
 *
 * > ✅ **接线已完成**（本仓 `VodDanmakuSession`）：会话按 ±1 窗口工作，三条判据都是
 * > 真实决策点（`shouldReplaceDanmakuWindow` 决定要不要换窗口、`shouldRequestDanmakuWindow`
 * > 决定要不要发请求、`shouldCancelPendingDanmakuWindow` 决定要不要撤销在途批次），
 * > 并有单测 `core:playback` 的 `VodDanmakuSessionTest` 覆盖"跨段直接上屏 / 不重复请求 /
 * > 回看撤销在途批次"。生效核对见 `docs/port-analysis/10-移植件生效核对总表.md`。
 */
const val DANMAKU_SEGMENT_DURATION_MS = VOD_DANMAKU_SEGMENT_DURATION_MS

/**
 * 位置 → 分段号（1 起算）。
 *
 * 源：`(positionMs.coerceAtLeast(0L) / DANMAKU_SEGMENT_DURATION_MS).toInt() + 1`
 */
fun segmentIndexForPosition(positionMs: Long): Int =
    positionMs.coerceAtLeast(0L).toDanmakuWindowId().toInt()

/**
 * 该位置对应的**窗口**（锚点 ± 1，裁剪到 `1..totalSegments`）。
 *
 * 源：`(anchor - 1..anchor + 1).filter { it in 1..safeTotal }`
 */
fun segmentWindowForPosition(positionMs: Long, totalSegments: Int): List<Int> {
    val safeTotal = totalSegments.coerceAtLeast(1)
    val anchor = segmentIndexForPosition(positionMs).coerceIn(1, safeTotal)
    return (anchor - 1..anchor + 1).filter { it in 1..safeTotal }
}

/** 当前显示的窗口与目标窗口不一致 ⇒ 需要换。 */
fun shouldReplaceDanmakuWindow(
    activeSegments: Collection<Int>,
    positionMs: Long,
    totalSegments: Int
): Boolean = activeSegments.toSet() != segmentWindowForPosition(positionMs, totalSegments).toSet()

/**
 * 是否需要发起请求。
 *
 * 源注释对应的是"已经在途的请求不许被同窗口的重复请求顶掉，但**换成别的窗口**时必须重新发"。
 */
fun shouldRequestDanmakuWindow(
    activeSegments: Collection<Int>,
    pendingSegments: Collection<Int>,
    requestInFlight: Boolean,
    positionMs: Long,
    totalSegments: Int,
    hasMissingSegments: Boolean = false
): Boolean {
    val requestedSegments = segmentWindowForPosition(positionMs, totalSegments).toSet()
    if (activeSegments.toSet() == requestedSegments && !hasMissingSegments) return false
    return !requestInFlight || pendingSegments.toSet() != requestedSegments
}

/** 源注释：*A return to the displayed window must also invalidate a different pending window.* */
fun shouldCancelPendingDanmakuWindow(
    pendingSegments: Collection<Int>,
    positionMs: Long,
    totalSegments: Int
): Boolean = pendingSegments.isNotEmpty() &&
    pendingSegments.toSet() != segmentWindowForPosition(positionMs, totalSegments).toSet()
