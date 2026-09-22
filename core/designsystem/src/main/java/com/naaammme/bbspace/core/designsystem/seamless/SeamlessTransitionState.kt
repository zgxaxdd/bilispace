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

    /** 返回阶段的绝对截止时刻（[isReturnRunning] 的自愈依据）。 */
    private var returnDeadline = 0L

    /**
     * 返回动画是否**当前仍在进行**（供 UI 读取，带截止时刻自愈）。
     *
     * 与裸读 [returnRunning] 的区别：系统的 `onEnd` 回调在部分固件上会丢失，
     * 那时 [returnRunning] 会永久停在 true。调用方（底栏小横条按它决定内容是否可见）
     * 若直接读裸值，回调丢失后小横条会永久留在"返回中"的显示状态。
     * 这里以 [returnDeadline] 兜底：超过返回动画硬上限即视为已结束。
     */
    val isReturnRunning: Boolean
        get() = returnRunning && System.currentTimeMillis() < returnDeadline

    /** 打开阶段的绝对截止时刻：awaitOpenEnd 以此为准，与等待者何时调用无关。 */
    private var openDeadline = 0L

    /**
     * 源侧卡片是否应当淡出自身内容（封面 + 简介区）。
     *
     * ## 为什么需要这个信号
     *
     * 系统 morph 的是**锚点 View 的矩形**，而锚点本身是零绘制透明的——它只提供几何，
     * 不提供像素。卡片真正的像素（封面图 + 简介区）留在应用窗口里，**既不参与 morph、
     * 也不会自动消失**。于是动画期间能看到「封面在放大飞走，简介区却原地不动」的割裂，
     * 这正是用户反馈的「简介没被带上」。
     *
     * ## 为什么不能把锚点扩到整卡
     *
     * 卡片是「4:3 封面 + 简介区」（整卡接近 1:1），而详情页播放器是 16:9。
     * 把锚点扩到整卡等于让系统把这个近方形矩形压进 16:9 —— 简介会被严重纵向压扁。
     * 所以几何必须继续锚在封面，**割裂要靠同步淡出来消除**：卡片内容与 morph 同时淡出，
     * 视觉上就是「整张卡片一起飞走并融进播放器」。
     *
     * ## 为什么不用系统回调
     *
     * 官方 `AnimationCallback.animationProgress` 本可做逐帧同步，但**本机实测该回调
     * 从不送达**（见 [OPEN_DEADLINE_MS] 的实测记录）。因此改用 [openRunning] /
     * [returnRunning] 这两个由源侧主动置位、可靠得多的信号来驱动同一件事。
     */
    val shouldFadeSourceCard: Boolean
        get() = openRunning || isReturnRunning

    /**
     * 发起本次无缝过渡的**源卡片标识**（`null` = 无过渡，或来源不是卡片）。
     *
     * 为什么需要它：淡出是"整卡级"效果，若只按 [shouldFadeSourceCard] 全局判断，
     * 那么从小横条（或任何非卡片入口）发起过渡时，**当前可见的所有卡片都会一起淡出**。
     * 卡片用自己稳定唯一的 key 与此值比对，只有真正发起过渡的那张才淡出。
     *
     * 取值约定：卡片传视频目标的稳定标识（见 [HomeVideoPage] 的调用点）；
     * 小横条等非卡片来源传 `null`，因此不会有任何卡片被淡出。
     */
    var sourceCardKey by mutableStateOf<String?>(null)
        private set

    /** 源卡片应当淡出，且自己就是那张卡。 */
    fun shouldFadeCard(key: String?): Boolean =
        shouldFadeSourceCard && key != null && key == sourceCardKey

    /** 记录本次过渡的源卡片标识；与 [beginOpen] 一起调用。 */
    fun markSourceCard(key: String?) {
        sourceCardKey = key
    }

    /** 打开阶段的起始时刻：用于判断"打开动画是否早已播完"（回调缺失时唯一可用依据）。 */
    private var openStartedAt = 0L

    /**
     * 系统无缝动画的**实时进度**（0..1，逐帧回调）。
     *
     * ## 实测修正（重要）
     *
     * 本文件此前记录"该固件 AnimationCallback 从不送达"。**2026-09-21 实机复测推翻了这个结论**：
     * 干净日志里 `onAnimationStart`（打开与返回各一次）与 `animationProgress`（每帧一次，
     * 从 0.0 递增到 ~1.0）都正常送达；确实缺失的只有 `onAnimationEnd`。
     * 因此 [OPEN_DEADLINE_MS] 的时长兜底**仍然必要**（onEnd 不来就得靠它复位），
     * 但**进度是可用的**——这比按时长猜要准得多。
     *
     * ## 用途
     *
     * 驱动源卡片内容的淡出：系统 morph 到哪一帧，卡片就淡到哪一帧，
     * 两者严格同步（按固定时长猜会因设备/负载不同而错位）。
     * 详见 [shouldFadeCard]。
     */
    var progress by mutableStateOf(0f)
        private set

    /** 记录一帧系统进度。由 [OplusViewSeamlessCompat.AnimationCallback.onProgress] 转发。 */
    fun onProgress(progress: Float) {
        this.progress = progress.coerceIn(0f, 1f)
    }

    /** 注册成功 / 打开动画开始：置位打开阶段。 */
    fun beginOpen() {
        openRunning = true
        progress = 0f
        openStartedAt = System.currentTimeMillis()
        openDeadline = openStartedAt + OPEN_DEADLINE_MS
    }

    /** 打开动画结束。 */
    fun endOpen() {
        openRunning = false
        progress = 0f
        // 源卡片标识只在过渡窗口内有意义，结束即清，避免下一轮误命中
        sourceCardKey = null
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
        progress = 0f
        returnDeadline = System.currentTimeMillis() + RETURN_DEADLINE_MS
    }

    /** 返回动画结束（或确认不会发生）。 */
    fun endReturn() {
        returnRunning = false
        progress = 0f
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
        // 回调缺失时的兜底路径也要清源卡片标识，否则它会一直残留到下一次过渡
        sourceCardKey = null
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
