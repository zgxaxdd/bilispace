package com.naaammme.bbspace.core.designsystem.seamless

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 系统无缝动画「打开 / 返回」两个阶段的进行中标记。
 *
 * 为什么需要：
 * - 打开阶段：详情页的封面收拢垫底必须等系统 leash 展开结束才开始，否则两个
 *   动画叠在一起；源侧（卡片）注册成功后立即置位，详情页据此同步时序。
 * - 返回阶段：详情 Activity 收起时系统执行「全屏 → 卡片」成对动画，期间迷你
 *   播放器不能出现（迷你 PlayerView 抢绑会让缩回画面变黑）、播放器不能提前关停
 *   （close 释放 surface 同样黑屏）。
 * - 首页列表滚动中断逻辑只能作用于返回阶段：打开动画期间用户手指带出的轻微
 *   滚动如果调 finishCurrentAnimation 会把刚启动的打开动画杀掉（系统退回默认
 *   窗口动画，表现为无缝动画时有时无）。
 *
 * 写入时机：
 * - 源侧回调（[OplusViewSeamlessCompat.AnimationCallback]）：onStart/onEnd 按
 *   isOpen 分流；打开在注册成功后还会预先置位（早于 startActivity，详情页组合时
 *   必然可见）。
 * - 详情 Activity：返回键收起且预期有返回动画时调 [beginReturn]（预判，回调可能
 *   滞后）；改走系统默认过渡时调 [endReturn]。
 * - 兜底：[awaitOpenEnd] / [awaitReturnEnd] 自带超时，系统回调丢失不会卡死。
 */
object SeamlessTransitionState {

    /** 打开阶段硬上限：超过即视为系统 onEnd 回调丢失，强制复位，遮罩不被多盖一秒。 */
    private const val OPEN_DEADLINE_MS = 1_200L

    /** 返回阶段硬上限（与既有 1.6s 兜底口径一致）。 */
    private const val RETURN_DEADLINE_MS = 1_600L

    /** 系统无缝打开动画进行中（注册成功即置位，onEnd(isOpen=true) 复位）。 */
    var openRunning by mutableStateOf(false)
        private set

    /** 系统无缝返回动画进行中（收起预判或 onStart(isOpen=false) 置位，结束复位）。 */
    var returnRunning by mutableStateOf(false)
        private set

    /** 打开阶段的绝对截止时刻：awaitOpenEnd 以此为准，与等待者何时调用无关。 */
    private var openDeadline = 0L

    /** 返回阶段的绝对截止时刻。 */
    private var returnDeadline = 0L

    /** 注册成功 / 打开动画开始：置位打开阶段。 */
    fun beginOpen() {
        openRunning = true
        openDeadline = System.currentTimeMillis() + OPEN_DEADLINE_MS
    }

    /** 打开动画结束。 */
    fun endOpen() {
        openRunning = false
    }

    /** 预期即将开始（或已开始）返回动画：置位返回阶段。 */
    fun beginReturn() {
        returnRunning = true
        returnDeadline = System.currentTimeMillis() + RETURN_DEADLINE_MS
    }

    /** 返回动画结束（或确认不会发生）。 */
    fun endReturn() {
        returnRunning = false
    }

    /**
     * 挂起等待打开动画结束（垫底收拢以此为启动信号）。
     * 截止时刻以 [beginOpen] 起算（绝对时间）——回调丢失时也不会把详情区多盖一秒，
     * 修复此前"每次打开都等满 1.2s 本地超时"的简介延迟。
     */
    suspend fun awaitOpenEnd() {
        while (openRunning && System.currentTimeMillis() < openDeadline) {
            delay(32)
        }
        openRunning = false
    }

    /** 挂起等待返回动画结束（迷你显隐/延迟关停以此为放行信号）；超时兜底。 */
    suspend fun awaitReturnEnd() {
        while (returnRunning && System.currentTimeMillis() < returnDeadline) {
            delay(32)
        }
        returnRunning = false
    }
}
