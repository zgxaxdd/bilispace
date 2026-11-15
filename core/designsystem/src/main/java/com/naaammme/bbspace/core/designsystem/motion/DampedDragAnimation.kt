package com.naaammme.bbspace.core.designsystem.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.naaammme.bbspace.core.designsystem.theme.BiliAnimations
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * 阻尼跟手动画（W3 · A 级纯逻辑 + 一个 `Animatable` 驱动壳）。
 *
 * ## 上游对应物
 * `design-system/.../ui/animation/DampedDragAnimation.kt`（BiliPai 的底栏/顶栏指示器
 * 拖拽内核，commit 778fb38…）。上游把它做成了 600 行的状态机（飞掷投影、速度形变、
 * 按压 bloom、MutatorMutex 抢锁）。本仓本轮**只取其中可复用的四个纯判据**，理由：
 * 1. 上游那套的消费面是它自己的 Dock/顶栏，本仓的 Dock 在 `app` 层、不在我的写域；
 * 2. 本仓守卫对 `animate*AsState`（Lint 基线）与 `tween/spring` 数字字面量（基线 4）
 *    都是只许降 —— 照搬上游会立刻撞红；
 * 3. **纯函数可 JVM 单测**，而"跟手手感"在无设备条件下本来就无法验收，把可证的部分
 *    （阻尼/回弹/吸附/越界钳制）单独钉住，比搬一个跑不起来的状态机更有价值。
 *
 * ## 四个纯判据
 * - [followOffset]：手指位移 → 视觉位移（乘阻尼系数，跟手但不完全 1:1）；
 * - [rubberBandOffset]：越界后的**橡皮筋**压缩（越拉越沉，松手回弹）；
 * - [projectedOffset]：松手瞬间按速度做**短时投影**（飞掷的方向感）；
 * - [releaseTarget]：在投影落点上按 `latticeStepPx` 吸附并钳回边界（回弹目标）。
 *
 * 全部对 `NaN`/非法输入做兜底：拖拽手势在取消/多指切换时会给到非有限值，
 * 一个 NaN 进 `Animatable` 会把整条动画钉死（这是上游注释里点过的坑）。
 */
object DampedDragAnimation {

    /** 跟手阻尼：视觉位移 = 手指位移 × 该系数（0.55 = "跟手但略滞后"）。 */
    const val DefaultResistance = 0.55f

    /** 越界后的附加压缩：超出部分只按 28% 计入（橡皮筋）。 */
    const val OutOfBoundsResistance = 0.28f

    /** 松手速度投影窗口（秒）：0.12s 足够读出"往哪扔"，又不会飞过头。 */
    const val VelocityProjectionSeconds = 0.12f

    /** 飞掷判定阈值（px/s）：小于它看作"松手停在原地"，只做 lattice 吸附。 */
    const val FlingThresholdPxPerSecond = 420f

    /**
     * 跟手位移：`rawDeltaPx` 是这一帧手指的水平位移，返回本帧应施加的视觉位移。
     *
     * [resistance] 而不是"1:1 跟手"：1:1 会让指示器与手指完全重合，松手时反而
     * 读不出"它要追上来"的弹性；0.55 是上游同语言在本仓的取值。
     * 非有限输入一律返回 0（见文件头 NaN 说明）。
     */
    fun followOffset(
        rawDeltaPx: Float,
        resistance: Float = DefaultResistance
    ): Float {
        if (!rawDeltaPx.isFinite() || !resistance.isFinite()) return 0f
        return rawDeltaPx * resistance.coerceIn(0f, 1f)
    }

    /**
     * 橡皮筋：把任意偏移压进 `[minOffsetPx, maxOffsetPx]` 的"软边界"。
     *
     * 边界内原样返回；越界部分乘 [OutOfBoundsResistance] 后仍向外延伸 ——
     * 视觉上"还能再拉一点，但越来越沉"，松手由 [releaseTarget] 弹回。
     *
     * **边界可以是非对称的**（首/尾档位只允许一个方向）：底栏在第一个 Tab 时
     * `minOffsetPx = 0`，在最后一个 Tab 时 `maxOffsetPx = 0`。早期实现用对称的
     * `±max`，于是首/尾档还能把指示器拖出可视区 —— 用户看到的"错位/异常"之一。
     */
    fun rubberBandOffset(
        offsetPx: Float,
        maxOffsetPx: Float,
        minOffsetPx: Float = -maxOffsetPx
    ): Float {
        if (!offsetPx.isFinite() || !maxOffsetPx.isFinite() || !minOffsetPx.isFinite()) return 0f
        val lower = minOf(minOffsetPx, maxOffsetPx)
        val upper = maxOf(minOffsetPx, maxOffsetPx)
        if (lower == 0f && upper == 0f) return 0f
        if (offsetPx in lower..upper) return offsetPx
        return if (offsetPx > upper) {
            upper + (offsetPx - upper) * OutOfBoundsResistance
        } else {
            lower - (lower - offsetPx) * OutOfBoundsResistance
        }
    }

    /**
     * 松手投影：在 `currentPx` 上按速度外推 [projectionSeconds]。
     *
     * 它只影响"往哪个方向吸附"，不直接决定最终位置 —— 最终位置还要过
     * [releaseTarget] 的 lattice 吸附与边界钳制。
     */
    fun projectedOffset(
        currentPx: Float,
        velocityPxPerSecond: Float,
        projectionSeconds: Float = VelocityProjectionSeconds
    ): Float {
        if (!currentPx.isFinite() || !velocityPxPerSecond.isFinite()) return 0f
        if (!projectionSeconds.isFinite()) return currentPx
        return currentPx + velocityPxPerSecond * projectionSeconds.coerceAtLeast(0f)
    }

    /**
     * 松手后的**回弹目标**。
     *
     * @param latticeStepPx 吸附格距（= 一个 item 的 pitch）。**传 0** 表示"没有格点"：
     *   直接回到静止位 0（拖拽把手/抓手这类"松手归位"的交互）。
     * @param thresholdPxPerSecond 飞掷阈值：低于它只看当前位置吸附，高于它把投影
     *   算进去（往扔的方向多走一格）。
     * @return 已按 lattice 吸附、并钳在 [boundsPx] 内的目标（px）。
     */
    fun releaseTarget(
        currentPx: Float,
        velocityPxPerSecond: Float,
        boundsPx: ClosedFloatingPointRange<Float>,
        latticeStepPx: Float,
        thresholdPxPerSecond: Float = FlingThresholdPxPerSecond
    ): Float {
        val min = minOf(boundsPx.start, boundsPx.endInclusive)
        val max = maxOf(boundsPx.start, boundsPx.endInclusive)
        if (!min.isFinite() || !max.isFinite()) return 0f
        val current = if (currentPx.isFinite()) currentPx.coerceIn(min, max) else 0f.coerceIn(min, max)
        // 没有格点：松手归位（把手类交互）
        if (!latticeStepPx.isFinite() || latticeStepPx <= 0f) {
            return 0f.coerceIn(min, max)
        }
        val threshold = if (thresholdPxPerSecond.isFinite()) {
            thresholdPxPerSecond.coerceAtLeast(0f)
        } else {
            FlingThresholdPxPerSecond
        }
        val velocity = if (velocityPxPerSecond.isFinite()) velocityPxPerSecond else 0f
        // 只有真的"扔"了才把投影算进去；否则松手就是"停在最近格点"
        val projected = if (abs(velocity) >= threshold) {
            projectedOffset(current, velocity)
        } else {
            current
        }
        val snapped = (projected / latticeStepPx).roundToInt() * latticeStepPx
        return snapped.coerceIn(min, max)
    }
}

/**
 * 拖拽状态壳：把上面的纯判据接到一个 `Animatable` 上。
 *
 * ## 为什么不是 `animateFloatAsState`
 * 本仓 `animate*AsState` 已被 Lint 基线钉死（只许降）；拖拽又需要"手指按住时
 * snapTo、松手才 animateTo"的混合语义 —— `Animatable` 正好两者都能做。
 *
 * ## 为什么偏移量以 `State<Float>` 暴露
 * 读取方（`graphicsLayer { }` / `offset { }`）应当在**绘制期**读值，避免每帧重组；
 * 返回 `State` 而不是 `Float` 就是为此（与播放器 `thumbScale` 同配方）。
 *
 * ## 边界为什么是**非对称**的
 * 底栏指示器在第一个 Tab 时只能往右拖（往左没有档位），在最后一个 Tab 时只能往左拖。
 * 早期实现用对称的 `±(n-1)·pitch`，于是在首/尾档还能把指示器拖出可视区（用户看到
 * 的"错位/异常"之一）。现在由 [minOffsetPx] / [maxOffsetPx] 分别表达：
 *   - 首个档位：`min = 0`（不许往左）
 *   - 末尾档位：`max = 0`（不许往右）
 *   - 中间档位：两侧都是 1 格（一次最多跨一档，防"一甩到底"）
 */
@Stable
class DampedDragState internal constructor(
    private val animations: BiliAnimations,
    private val minOffsetPx: Float,
    private val maxOffsetPx: Float,
    private val latticeStepPx: Float,
    private val scope: CoroutineScope
) {
    private val offsetAnimation = Animatable(0f)

    /**
     * 按压膨胀进度（0 = 静止，1 = 完全按下）。
     *
     * ## 为什么按压是"膨胀"（scale > 1）
     * 本仓普通按钮的按压是**缩小**（0.97 / 0.92），因为它们是"被按下去的实体"。
     * 底栏指示器是**液态的一块**：手指压上去应该"被挤出来"，像水球被按。
     * 用户明确描述了这个方向（"按压时元素膨胀"），所以这里与按钮族相反 ——
     * 不是不一致，是两种物件的物理语言不同。
     */
    private val pressAnimation = Animatable(0f)

    /** 绘制期读取的偏移（px）。 */
    val offset: State<Float> = offsetAnimation.asState()

    /** 绘制期读取的按压进度（0..1）。 */
    val pressProgress: State<Float> = pressAnimation.asState()

    /** 当前偏移（布局/手势回调里可直接用）。 */
    val currentOffsetPx: Float get() = offsetAnimation.value

    /** 手势层读取边界/格距（[dampedHorizontalDrag] 算落位时需要，避免调用方重复传）。 */
    internal val minOffsetPxForGesture: Float get() = minOffsetPx
    internal val maxOffsetPxForGesture: Float get() = maxOffsetPx
    internal val latticeStepPxForGesture: Float get() = latticeStepPx

    /** 是否正被按住（供调用方决定要不要显示"拖拽中"的额外形态）。 */
    var isDragging: Boolean = false
        private set

    /**
     * 手指**落到元素上**（还没移动）：立刻开始膨胀。
     *
     * ## 为什么必须与 [onDragStart] 分开（这是"按压不膨胀"的根因）
     * 早前只有 `onDragStart()`，而它是在"水平位移超过 touchSlop"之后才被调用。
     * 后果：**单纯按一下（不拖）时 `pressProgress` 恒为 0**，元素纹丝不动 ——
     * 用户要的"按压时元素膨胀"在点击路径上完全没发生；即使拖拽，也要先磨掉
     * 约 8dp 的 slop 才开始鼓，读起来是"手指先走一段、元素后知后觉"。
     *
     * 现在按下即膨胀（[Modifier.dampedHorizontalDrag] 在 `awaitFirstDown` 后立刻调用），
     * 与位移解耦：点击 = 鼓一下再收回，拖拽 = 鼓起后继续拉伸。
     *
     * 用 `Animatable` 而不是 `animateFloatAsState`：前者能在"按住时 snapTo、松手
     * 才 animateTo"的混合语义下工作，后者只有单一目标值。
     */
    fun onPressDown() {
        scope.launch { pressAnimation.animateTo(1f, LiquidIndicatorMotion.pressSpec()) }
    }

    /** 手指抬起、手势结束：**无论吸附由谁接管**，按压膨胀都必须收回。 */
    fun onPressUp() {
        isDragging = false
        scope.launch {
            if (animations.reduceMotion) {
                pressAnimation.snapTo(0f)
            } else {
                pressAnimation.animateTo(0f, LiquidIndicatorMotion.pressSpec())
            }
        }
    }

    /**
     * 拖拽接管：停住回弹动画（避免"抢手"），并确保处于膨胀态。
     *
     * 膨胀本身已由 [onPressDown] 在按下时启动，这里补一次是幂等的
     * （`animateTo` 目标是同一个 1f，不会重启动画）。
     */
    fun onDragStart() {
        isDragging = true
        scope.launch { offsetAnimation.stop() }
        scope.launch { pressAnimation.animateTo(1f, LiquidIndicatorMotion.pressSpec()) }
    }

    /** 跟手：叠加本帧位移（阻尼 + 橡皮筋）。 */
    fun dragBy(deltaPx: Float) {
        if (!deltaPx.isFinite()) return
        val next = DampedDragAnimation.rubberBandOffset(
            offsetPx = offsetAnimation.value + DampedDragAnimation.followOffset(deltaPx),
            minOffsetPx = minOffsetPx,
            maxOffsetPx = maxOffsetPx
        )
        scope.launch { offsetAnimation.snapTo(next) }
    }

    /**
     * 跟手到**绝对偏移**（相对手势起点）：调用方自己算累计位移时用它。
     *
     * 与 [dragBy] 的区别：`dragBy` 是增量式（每帧加一点），本函数是绝对式
     * （直接给定当前位置）。绝对式的好处是**不会累积误差** —— 增量式在
     * 丢帧/取消时可能残留几 px，长期拖拽会偏。
     *
     * 传入值仍过一遍 [DampedDragAnimation.rubberBandOffset]，所以越界表现一致。
     */
    fun dragToAbsolute(absoluteOffsetPx: Float) {
        if (!absoluteOffsetPx.isFinite()) return
        val next = DampedDragAnimation.rubberBandOffset(
            offsetPx = absoluteOffsetPx,
            minOffsetPx = minOffsetPx,
            maxOffsetPx = maxOffsetPx
        )
        scope.launch { offsetAnimation.snapTo(next) }
    }

    /**
     * 松手：按纯判据算回弹目标，并用**带回弹的 spring** 动画过去。
     *
     * ## 为什么不能用 tween（本函数之前的实现）
     * 原实现用 `tween(animations.morph, morphEasing)` —— tween 是**单调**的：
     * 从当前位置匀速（按缓动）走到目标，**永远不会越过目标再回来**。
     * 用户要的"松开后带弹性晃动并回归原状"在数学上就要求**过冲**，
     * 而 tween 做不到 ⇒ 手感读作"啪一下就到位"。
     * 现在改用 `releaseSpec()`（阻尼 0.62）：大约过冲 6~8%，两次振荡内收敛。
     *
     * 同时把按压进度一起收回（膨胀 → 原状），两者同一时长读作"一体"。
     *
     * @return 回弹目标偏移（px，相对本状态的 0 点）。调用方据此判断"落到第几格"。
     */
    fun onRelease(velocityPxPerSecond: Float): Float {
        isDragging = false
        val target = DampedDragAnimation.releaseTarget(
            currentPx = offsetAnimation.value,
            velocityPxPerSecond = velocityPxPerSecond,
            boundsPx = minOffsetPx..maxOffsetPx,
            latticeStepPx = latticeStepPx
        )
        scope.launch {
            if (animations.reduceMotion) {
                offsetAnimation.snapTo(target)
            } else {
                offsetAnimation.animateTo(target, LiquidIndicatorMotion.releaseSpec())
            }
        }
        scope.launch {
            if (animations.reduceMotion) {
                pressAnimation.snapTo(0f)
            } else {
                pressAnimation.animateTo(0f, LiquidIndicatorMotion.pressSpec())
            }
        }
        return target
    }

    /**
     * 松手后**保持偏移、只收回按压**（拖动切页时用）。
     *
     * 拖动切页的正确时序：新基点**瞬移**到位（由调用方 `snapTo` 基点），
     * 而拖动残余 offset 继续用 spring 收敛到 0 —— 两者叠加后视觉上就是
     * "指示器弹到新档位并晃两下"。若在这里把 offset 直接清零，晃动就没了。
     */
    fun settleWithoutSnap() {
        isDragging = false
        scope.launch {
            if (animations.reduceMotion) {
                offsetAnimation.snapTo(0f)
            } else {
                offsetAnimation.animateTo(0f, LiquidIndicatorMotion.releaseSpec())
            }
        }
        scope.launch {
            if (animations.reduceMotion) {
                pressAnimation.snapTo(0f)
            } else {
                pressAnimation.animateTo(0f, LiquidIndicatorMotion.pressSpec())
            }
        }
    }

    /**
     * 把一段位移从当前残余里"转走"（拖动切页的**原子换基**）。
     *
     * 场景：手指把指示器拖了 +52px，最近格点 +48px。若直接把基点也 +48、同时残余清零，
     * 两个 State 不在同一帧生效 ⇒ 视觉上**跳一下**。正确做法是先把残余减掉同一量
     * （+52 → +4），再让残余用 spring 收回 0 —— 跳被消掉，只剩 4px 的弹性余韵。
     */
    fun consumeOffset(deltaPx: Float) {
        if (!deltaPx.isFinite()) return
        scope.launch { offsetAnimation.snapTo(offsetAnimation.value - deltaPx) }
    }

    /** 外部强制归位（例如换页/收起控制层时）：偏移与按压都清零。 */
    fun reset() {
        isDragging = false
        scope.launch {
            offsetAnimation.snapTo(0f)
            pressAnimation.snapTo(0f)
        }
    }
}

/**
 * 记住一个 [DampedDragState]。
 *
 * @param maxOffsetPx 软边界（px）；可见宽度的一半是常见取值
 * @param latticeStepPx 吸附格距（px）；`0` = 松手归位到 0
 */
@Composable
fun rememberDampedDragState(
    minOffsetPx: Float,
    maxOffsetPx: Float,
    latticeStepPx: Float = 0f
): DampedDragState {
    val animations = LocalAnimations.current
    val scope = rememberCoroutineScope()
    return remember(animations, minOffsetPx, maxOffsetPx, latticeStepPx, scope) {
        DampedDragState(
            animations = animations,
            minOffsetPx = minOffsetPx,
            maxOffsetPx = maxOffsetPx,
            latticeStepPx = latticeStepPx,
            scope = scope
        )
    }
}

/**
 * 水平拖拽手势接线：把触摸事件喂给 [DampedDragState]。
 *
 * ## 为什么不用 `detectHorizontalDragGestures`（这是"错位"的**头号根因**）
 * 它内部走 `awaitTouchSlopOrCancellation`：**触发前的位移被丢弃**，回调里第一次
 * 收到的 `dragAmount` 只是"超出 slop 的那一点点"。后果是**指示器永久落后手指
 * 一个 touch slop（约 8dp）** —— 用户看到的就是"拖起来跟手但一直偏一截"。
 * 再叠加接线层漏加的 `innerPad`(4dp)，合计 ~12dp 错位。
 *
 * 上游 BiliPai 用自己写的 `inspectDragGestures`（`awaitFirstDown` + 逐事件
 * `positionChange()`），**从按下第一帧就上报位移**，没有 slop 损耗。
 * 本仓照此实现：按下即 `onDragStart`，之后每个事件都上报 `positionChange()`，
 * 并用 `position.x - downPosition.x` 作为**绝对位移**（不依赖增量累加，
 * 丢帧也不会漂）。
 *
 * ## 为什么暴露"绝对位置"而不是"残余偏移"
 * 早期设计是「基点 Animatable + 拖拽残余 Animatable」在 `offset{}` 里相加 ——
 * 两个 Animatable 各自 `scope.launch`，切页时一个 snapTo、一个清零，
 * **不在同一帧生效** ⇒ 必然出现一帧跳变（错位）。现在只维护一个 Animatable。
 *
 * ## 手势归属与横纵分工
 * 只在**水平位移超过垂直位移**时才接管（`shouldEngage`），否则让给宿主的纵向滚动。
 * 一旦接管，整个手势期间保持归属（不半路放手），并 `consume()` 防止宿主抢。
 *
 * @param basePositionPx 当前档位的基准位置（px），按下时取一次
 * @param onAbsolutePosition 跟手回调：报告指示器应处的**绝对位置**（px）
 * @param onSettle 松手回调：参数 = 吸附后的绝对位置（px）。返回 true = 调用方已接管
 *   （自己 spring 到该位置）；返回 false / 不传 = 走默认回弹。
 */
fun Modifier.dampedHorizontalDrag(
    state: DampedDragState,
    basePositionPx: () -> Float,
    onAbsolutePosition: (absolutePx: Float) -> Unit,
    onSettle: ((absolutePx: Float) -> Boolean)? = null
): Modifier = this.pointerInput(state) {
    val velocityTracker = VelocityTracker()
    awaitEachGesture {
        // 必须用 awaitFirstDown(requireUnconsumed = false)：底栏上有子元素（图标）的
        // clickable 会先消费 down，若这里要求 unconsumed 就永远拿不到手势。
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val downPosition = down.position
        val gestureBasePx = basePositionPx()
        velocityTracker.resetTracking()
        velocityTracker.addPosition(down.uptimeMillis, downPosition)
        // 按下即膨胀：与位移解耦。此前膨胀要等水平位移超过 touchSlop 才启动，
        // 导致"单纯按一下不膨胀"、"拖拽头一段不跟手"。
        state.onPressDown()

        var engaged = false
        var cancelled = false
        var lastPosition = downPosition

        // ## 为什么整段要包 try/finally
        // `awaitEachGesture` 在**手势被系统取消**（多指接管、宿主抢焦点、作用域销毁）
        // 时是以抛异常的方式退出循环的。若按压收回写在循环之后，那些路径会把
        // `pressProgress` 永久留在 1 —— 指示器从此一直比静止态大 18%，只能等下一次
        // 触摸才恢复。`finally` 保证任何退出方式都收起膨胀。
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // 抬起：正常结束（未 engaged 就是一次点击，交给子元素处理，不上报）。
                    lastPosition = change.position
                    break
                }
                val totalDelta = change.position - downPosition
                if (!engaged) {
                    // 横纵分工：水平位移必须明显大于垂直，否则让给宿主（列表滚动）。
                    val absX = abs(totalDelta.x)
                    val absY = abs(totalDelta.y)
                    if (absX > absY && absX > viewConfiguration.touchSlop) {
                        engaged = true
                        state.onDragStart()
                    } else if (absY > absX && absY > viewConfiguration.touchSlop) {
                        // 明确是纵向手势：放弃接管，让宿主滚动。
                        cancelled = true
                        break
                    }
                }
                if (engaged) {
                    change.consume()
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    // 绝对式：直接用"当前手指位置 − 按下位置"，不做增量累加 ⇒ 永不漂移。
                    state.dragToAbsolute(DampedDragAnimation.followOffset(totalDelta.x))
                    onAbsolutePosition(gestureBasePx + state.currentOffsetPx)
                    lastPosition = change.position
                }
            }
        } finally {
            if (engaged) {
                if (cancelled) {
                    if (onSettle?.invoke(gestureBasePx) != true) {
                        state.onRelease(0f)
                        onAbsolutePosition(gestureBasePx)
                    }
                } else {
                    val velocity = velocityTracker.calculateVelocity().x
                    val targetOffset = DampedDragAnimation.releaseTarget(
                        currentPx = state.currentOffsetPx,
                        velocityPxPerSecond = velocity,
                        boundsPx = state.minOffsetPxForGesture..state.maxOffsetPxForGesture,
                        latticeStepPx = state.latticeStepPxForGesture
                    )
                    if (onSettle?.invoke(gestureBasePx + targetOffset) != true) {
                        state.onRelease(velocity)
                        onAbsolutePosition(gestureBasePx + targetOffset)
                    }
                }
            }
            // ## 按压收回必须独立于吸附路径（这是"松手后不回归原状"的根因）
            //
            // `onSettle` 返回 true 表示"调用方自己 spring 到吸附位置"，此时**不会**走
            // `state.onRelease(...)`（避免偏移被两方同时驱动）。但按压膨胀也住在
            // `onRelease` 里 —— 于是只要调用方接管吸附（本仓 AppNavHost 的两个分支
            // **都** return true），`pressProgress` 就永远停在 1：指示器松手后一直
            // 比静止态大 18%，再也不缩回去。
            //
            // 现在按下/抬手与位移彻底分离：无论 engaged 与否、无论谁接管吸附、
            // 无论正常抬起还是被系统取消，退出时都收起膨胀。
            // 这是"松开后回归原状"的字面要求。
            state.onPressUp()
        }
        // 未 engaged：什么也不做（点击交给子元素的 clickable，不消费、不上报）。
        lastPosition.hashCode() // 保持引用，避免 IDE 提示未使用
    }
}
