package com.naaammme.bbspace.core.designsystem.glass

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.metrics.performance.JankStats

/** 帧守卫下发的保守策略：动效档 + 强制低预算（方案 4.2 第 3 条）。 */
data class GlassGuardPolicy(
    val effectiveMotionTier: MotionTier,
    val forceLowBlurBudget: Boolean
)

val LocalGlassGuard = staticCompositionLocalOf { GlassGuardPolicy(MotionTier.Normal, false) }

/**
 * 帧率守卫（JankStats 窗口统计 + 双阈值滞后，阈值照搬 BiliPai 实测值）：
 * - jank ≥ 7.5% 连续 2 个窗口 → 降级（Reduced + forceLowBudget）
 * - jank ≤ 4.0% → 恢复
 * - 降级后 60s 冷却内不评估恢复（防抖动）
 * 经 [LocalGlassGuard] 分发，只失效玻璃叶子，不引发页面重组。
 */
@Stable
class GlassGuardTracker internal constructor() {
    private val frameCounts = IntArray(WINDOW_SLOTS)
    private val jankCounts = IntArray(WINDOW_SLOTS)
    private var slot = 0
    private var consecutiveHighWindows = 0

    var policy by mutableStateOf(GlassGuardPolicy(MotionTier.Normal, false))
        private set

    private var lastDowngradeAtMs = 0L

    fun onFrame(isJank: Boolean) {
        val idx = slot % WINDOW_SLOTS
        frameCounts[idx]++
        if (isJank) jankCounts[idx]++
    }

    /** 每 [WINDOW_MS] 调一次：结算当前窗口的 jank 占比并施加滞后。 */
    fun tick(nowMs: Long) {
        slot++
        // 结算即清空：形成真正的滑动窗口。此前计数无限累积，jank 百分比被
        // 历史稀释，守卫永不触发（AI 复核发现，属实）。
        val frames = frameCounts[0]
        val janks = jankCounts[0]
        frameCounts[0] = 0
        jankCounts[0] = 0
        if (frames < MIN_FRAMES_PER_WINDOW) return
        val percent = janks * 100f / frames

        val downgraded = policy.forceLowBlurBudget
        if (!downgraded) {
            if (percent >= HIGH_JANK_THRESHOLD_PERCENT) {
                consecutiveHighWindows++
                if (consecutiveHighWindows >= REQUIRED_HIGH_JANK_WINDOWS) {
                    policy = GlassGuardPolicy(MotionTier.Reduced, forceLowBlurBudget = true)
                    lastDowngradeAtMs = nowMs
                    consecutiveHighWindows = 0
                    clearSlots()
                }
            } else {
                consecutiveHighWindows = 0
            }
        } else {
            if (percent <= RECOVER_JANK_THRESHOLD_PERCENT &&
                nowMs - lastDowngradeAtMs >= DOWNGRADE_COOLDOWN_MS
            ) {
                policy = GlassGuardPolicy(MotionTier.Normal, forceLowBlurBudget = false)
                clearSlots()
            }
        }
    }

    private fun clearSlots() {
        java.util.Arrays.fill(frameCounts, 0)
        java.util.Arrays.fill(jankCounts, 0)
    }

    companion object {
        const val HIGH_JANK_THRESHOLD_PERCENT = 7.5f
        const val RECOVER_JANK_THRESHOLD_PERCENT = 4.0f
        const val DOWNGRADE_COOLDOWN_MS = 60_000L
        const val REQUIRED_HIGH_JANK_WINDOWS = 2
        const val WINDOW_MS = 3_000L
        const val MIN_FRAMES_PER_WINDOW = 30
        const val WINDOW_SLOTS = 1
    }
}

/**
 * 创建并跟踪 JankStats；非 Activity 宿主（预览等）返回不工作的空 tracker。
 * 窗口统计由调用方用 [GlassGuardTracker.tick] 定期结算。
 */
@Composable
fun rememberGlassGuardTracker(): GlassGuardTracker {
    val tracker = remember { GlassGuardTracker() }
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context as? Activity
        var stats: JankStats? = null
        if (activity != null) {
            stats = JankStats.createAndTrack(activity.window) { frameData ->
                tracker.onFrame(frameData.isJank)
            }
            stats.isTrackingEnabled = true
        }
        onDispose {
            stats?.isTrackingEnabled = false
        }
    }
    return tracker
}
