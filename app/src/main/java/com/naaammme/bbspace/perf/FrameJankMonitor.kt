package com.naaammme.bbspace.perf

import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import com.naaammme.bbspace.core.common.log.DiagnosticLog

/**
 * 界面掉帧的**现场读数**（W7：本仓此前完全没有帧率测量能力）。
 *
 * ## 边界（为什么是这种写法）
 * - **只在主线程启**：`Choreographer` 必须主线程，启动前判 `Looper`，否则直接不启（不抛）。
 * - **每帧零分配**：只做整数运算 + 写 `IntArray` 桶。分配会污染被测对象本身（这也是
 *   `FrameBudgetLintTest` 的同一口径）。
 * - **不新建线程**：Choreographer 回调本身在主线程的 vsync 上，比 `Timer/Handler` 更贴
 *   "用户看到的帧"。
 * - **只在「现场诊断」开着时启**（`DiagnosticLog.enabled` = debug 包或用户手动打开）：
 *   量产包默认不上这条链。
 * - **空闲不出数**：没有帧就没有回调 —— 界面静止本身是流畅的，不需要读数。
 *
 * 读数的用法：`adb logcat -s FrameDiag`，与 `StallDiag`（播放链路）/ `MemoryDiag`（内存）
 * 三行合起来看，才能区分"卡在渲染"还是"卡在解码"还是"卡在 GC"。
 */
object FrameJankMonitor {

    private const val TAG = "FrameDiag"

    private val counts = IntArray(FrameJankPolicy.BUCKETS_MS.size + 1)

    private var lastFrameNs = 0L
    private var windowStartMs = 0L
    private var frames = 0
    private var jankFrames = 0
    private var freezeFrames = 0
    private var worstFrameMs = 0L

    private var gcBaseCount = 0L
    private var gcBaseTimeMs = 0L

    private var running = false
    private var choreographer: Choreographer? = null

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            onFrame(frameTimeNanos)
            choreographer?.postFrameCallback(this)
        }
    }

    /** 启动采样（重复调用无副作用）。非主线程或诊断关闭时静默不启。 */
    fun start() {
        if (running) return
        if (!DiagnosticLog.enabled) return
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val now = SystemClock.elapsedRealtime()
        windowStartMs = now
        lastFrameNs = 0L
        resetWindow()
        gcBaseCount = gcStat("art.gc.gc-count")
        gcBaseTimeMs = gcStat("art.gc.gc-time")
        choreographer = Choreographer.getInstance()
        running = true
        choreographer?.postFrameCallback(callback)
    }

    /** 停止采样（Activity 不可见 / 进程退后台时调用；重复调用无副作用）。 */
    fun stop() {
        if (!running) return
        running = false
        choreographer?.removeFrameCallback(callback)
        choreographer = null
    }

    private fun onFrame(frameTimeNanos: Long) {
        val previous = lastFrameNs
        lastFrameNs = frameTimeNanos
        if (previous == 0L) return
        val frameMs = (frameTimeNanos - previous) / 1_000_000L
        if (frameMs < 0L) return
        frames++
        counts[FrameJankPolicy.bucketOf(frameMs)]++
        if (frameMs > worstFrameMs) worstFrameMs = frameMs
        if (frameMs > FrameJankPolicy.JANK_FRAME_MS) jankFrames++
        if (frameMs > FrameJankPolicy.FREEZE_FRAME_MS) freezeFrames++
        val now = SystemClock.elapsedRealtime()
        if (now - windowStartMs >= FrameJankPolicy.REPORT_INTERVAL_MS) report(now)
    }

    private fun report(now: Long) {
        val elapsed = (now - windowStartMs).coerceAtLeast(1L)
        val gcCount = gcStat("art.gc.gc-count")
        val gcTime = gcStat("art.gc.gc-time")
        val total = frames
        if (total > 0) {
            val p95 = FrameJankPolicy.percentileMs(counts, total, 95)
            DiagnosticLog.i(TAG) {
                FrameJankPolicy.format(
                    elapsedMs = elapsed,
                    frames = total,
                    jankFrames = jankFrames,
                    freezeFrames = freezeFrames,
                    worstFrameMs = worstFrameMs,
                    p95Ms = p95,
                    gcCountDelta = gcCount - gcBaseCount,
                    gcTimeDeltaMs = gcTime - gcBaseTimeMs
                )
            }
        }
        gcBaseCount = gcCount
        gcBaseTimeMs = gcTime
        windowStartMs = now
        resetWindow()
    }

    private fun resetWindow() {
        java.util.Arrays.fill(counts, 0)
        frames = 0
        jankFrames = 0
        freezeFrames = 0
        worstFrameMs = 0L
    }

    private fun gcStat(key: String): Long =
        runCatching { Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L }.getOrDefault(0L)
}
