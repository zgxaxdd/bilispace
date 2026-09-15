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
 *
 * 线程约定：本对象的状态是 Compose `mutableStateOf`，**只能在主线程写**。
 * 目前所有写入点都在主线程（源侧点击、详情 Activity 生命周期、组合期副作用）；
 * 若将来把系统的 AnimationCallback 回调接到某个系统线程上，必须先切回主线程再写，
 * 否则会触发快照线程检查异常（详见 OplusViewSeamlessCompat.AnimationCallback）。
 */
object SeamlessTransitionState {

    /**
     * 打开阶段硬上限。
     *
     * **实测依据（本机 PLC110 / ColorOS 16.0，`persist.sys.oplus.anim_level=1`）**：
     * 系统的 `AnimationCallback`（onAnimationStart/onAnimationEnd）在该固件上**从不送达**——
     * 干净的单次开关日志里只有 `registerOpenTransition ok` 与超时兜底，没有任何回调日志，
     * 而系统侧 `ViewSeamless Transition startAnimation` / `vs all spring finish` 都正常。
     * 因此**所有阶段推进都只能靠这里的时长兜底**，取值必须贴近系统真实动画时长，
     * 否则 App 的相位判断会长期滞后于系统（快速开关时表现最明显）。
     *
     * 系统弹簧动画实测：607 / 610 / 611 / 614 / 636 / 638 / 659 / 662 / 681ms
     * （见 OplusViewSeamlessRemoteManager 的 `vs all spring finish, total:`）。
     * 取 800ms 覆盖上述全部样本并留约 120ms 余量。
     */
    private const val OPEN_DEADLINE_MS = 800L

    /**
     * 返回阶段硬上限。同一批实测样本下取 800ms（原先 1_600ms 会让迷你播放器出现、
     * 以及上一段会话的延迟关停整体晚约一倍，快速开关时相位错位更明显）。
     */
    private const val RETURN_DEADLINE_MS = 800L

    /**
     * 打开动画的近似时长；回调缺失时的放行基准。
     * 取实测样本的下沿（607~681ms 中的 610ms）：用于「动画结束即可放行」的场合
     * （详情区遮罩淡出、[isOpenAnimSurelyDone] 判定），不必等满硬上限。
     */
    private const val OPEN_ANIM_APPROX_MS = 610L

    /** 系统无缝打开动画进行中（注册成功即置位，onEnd(isOpen=true) 复位）。 */
    var openRunning by mutableStateOf(false)
        private set

    /** 系统无缝返回动画进行中（收起预判或 onStart(isOpen=false) 置位，结束复位）。 */
    var returnRunning by mutableStateOf(false)
        private set

    /** 打开阶段的绝对截止时刻：awaitOpenEnd 以此为准，与等待者何时调用无关。 */
    private var openDeadline = 0L

    /** 打开阶段的起始时刻：用于判断"打开动画是否早已播完"（回调缺失时唯一可用依据）。 */
    private var openStartedAt = 0L

    /** 返回阶段的绝对截止时刻。 */
    private var returnDeadline = 0L

    /** 注册成功 / 打开动画开始：置位打开阶段。 */
    fun beginOpen() {
        openRunning = true
        openStartedAt = System.currentTimeMillis()
        openDeadline = openStartedAt + OPEN_DEADLINE_MS
    }

    /** 打开动画结束。 */
    fun endOpen() {
        openRunning = false
    }

    /**
     * 打开动画是否**确定**已经播完。
     *
     * 系统回调缺失时，[openRunning] 会在整个硬上限窗口内保持 true，无法回答"动画真的还在跑吗"。
     * 调用方（详情 Activity 收起时要决定是否中断进行中的打开动画）需要这个判断：
     * 在动画早已播完的情况下仍然调用 finishCurrentAnimation()，会平白把系统刚合并好的
     * 过渡打断成 "can't reverse, finish now"，表现为快速开关时动画抖一下。
     *
     * 判据是"距 beginOpen 已超过实测动画时长"：超时则无论 [openRunning] 为何都认为已播完。
     */
    fun isOpenAnimSurelyDone(): Boolean {
        if (!openRunning) return true
        return System.currentTimeMillis() - openStartedAt >= OPEN_ANIM_APPROX_MS
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
     * 视频页已开始收起（返回动画或普通关闭都会置位）。实测系统 onEnd 回调在部分
     * 固件上不送达，依赖回调的收起后动作（如锚点过渡位图清理）以此信号兜底。
     */
    var closing by mutableStateOf(false)
        private set

    /** 视频页收起开始：beginReturn / markClosing 内部调用。 */
    fun markClosing() {
        closing = true
    }

    /** 新一次无缝打开前复位收起标记。 */
    fun resetClosing() {
        closing = false
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
        val byDeadline = openRunning
        openRunning = false
        if (byDeadline) {
            android.util.Log.w(
                "OplusViewSeamless",
                "awaitOpenEnd hit deadline (system onEnd callback missed)"
            )
        }
    }

    /** 挂起等待返回动画结束（迷你显隐/延迟关停以此为放行信号）；超时兜底。 */
    suspend fun awaitReturnEnd() {
        while (returnRunning && System.currentTimeMillis() < returnDeadline) {
            delay(32)
        }
        val byDeadline = returnRunning
        returnRunning = false
        if (byDeadline) {
            android.util.Log.w(
                "OplusViewSeamless",
                "awaitReturnEnd hit deadline (system onEnd callback missed)"
            )
        }
    }

    /**
     * 挂起等待打开动画本身播完（近似 [OPEN_ANIM_APPROX_MS]）。
     * 系统回调缺失时"动画已结束"只能按时长近似判定；与 [awaitOpenEnd] 的区别是不等满
     * 硬上限——用于「动画结束即可放行、不必等回调」的场合（如详情区遮罩）。
     */
    suspend fun awaitOpenAnimDone() {
        val animDoneAt = openDeadline - (OPEN_DEADLINE_MS - OPEN_ANIM_APPROX_MS)
        while (openRunning && System.currentTimeMillis() < animDoneAt) {
            delay(32)
        }
    }
}
