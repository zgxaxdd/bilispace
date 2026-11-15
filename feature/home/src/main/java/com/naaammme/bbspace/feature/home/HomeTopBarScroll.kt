package com.naaammme.bbspace.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.naaammme.bbspace.core.designsystem.theme.BiliAnimations
import kotlinx.coroutines.CoroutineScope
import kotlin.math.round

/**
 * 首页顶栏的**滚动状态机**：只做"偏移累加 + fling 结束吸附"两件事。
 *
 * ## 历史（两次真机反馈，两次改这个类）
 *
 * ### 第一轮：Fix-1 吸附不变量（仍然是本类的核心职责）
 * 最早是 `HomeScreen` 里裸的一段 `NestedScrollConnection` + 一个 `mutableStateOf<Float>`，
 * 只累加偏移。真机反馈"顶栏卡住不回弹"的结构性原因是：**没有任何 fling 结束时的
 * 收敛逻辑**。若某次 fling 在 `0 < offsetPx < height` 区间结束（列表没滚到顶、
 * 或甩回顶部但没有 over-scroll 余量），顶栏就停在半隐状态不动。
 * 现在 [onPostFling] 把偏移吸附到最近端（`< height/2` → 0，否则 → height），
 * 用 [BiliAnimations.morph] 配 `morphEasing` 做动效，`reduceMotion` 时直接落位。
 *
 * ### 第二轮（2026-10-18 第二十轮）：剔除 `isScrolling` 与静默计时器
 * 上一轮这里还维护一个 `isScrolling`（"是否真在滚动"）+ 120ms 静默计时器，
 * 唯一的消费者是顶栏玻璃的 `glassEffect(isScrolling = ...)`（用于切换玻璃预算：
 * 滚动期降采样、静止期恢复实时档）。
 *
 * 那一整块已随用户第 33 轮反馈**删除**（顶栏改纯色 chrome，不再有任何实时模糊，
 * 见 `HomeScreen` 的说明）。既然消费者没了，这份状态就是纯开销 ——
 * 而且不是零成本：每个滚动位移都要**取消一个 Job 再启动一个 Job**（延迟 120ms），
 * 一次快滑几十上百次。所以整段删除，而不是留着"以后可能用得上"。
 *
 * ⚠️ 若将来有人把顶栏玻璃加回来，需要恢复的是**同时**两件东西：`isScrolling`
 * 状态 **和** 它对应的消费者。只恢复状态、没有消费者 = 白跑计时器；
 * 只恢复玻璃、没有状态 = 玻璃永远按"静止"预算采样（会逐帧重录，更贵）。
 *
 * ## 符号约定（沿用旧实现，勿改）
 * Compose 嵌套滚动约定：手指上滑（内容上移、浏览下方内容）时滚动位移 `delta.y` 为负
 * （与 M3 `EnterAlwaysScrollBehavior` 的 `heightOffset += available.y` 同一符号体系）。
 * 因此用 `-delta` 累加：上滑 → offset 增大 → 顶栏上移隐藏；下滑 → offset 减小 → 顶栏滑回。
 *
 * ## 为什么 offset 按像素取整
 * offset 由 `graphicsLayer` 消费，亚像素变化没有可见差异，却会让每个滚动 tick 都写一次
 * 状态、触发一次重绘。取整后大部分 tick 与上次相同，`mutableStateOf` 的结构相等判断
 * 直接短路，滚动期实际重绘次数显著低于位移次数。
 *
 * @param heightProvider 顶栏实测高度（px）；用 provider 而非值，避免闭包捕获首帧 0
 * @param animations 动效 token（`LocalAnimations`）；吸附动效用其 morph 档
 * @param scope 吸附动画的宿主（页面 `rememberCoroutineScope`，随页面销毁取消）
 */
@Stable
internal class HomeTopBarScrollState(
    private val heightProvider: () -> Int,
    private val animations: BiliAnimations,
    private val scope: CoroutineScope
) : NestedScrollConnection {

    /** 顶栏上移的像素偏移：0 = 完全展开，height = 完全隐藏。 */
    var offsetPx by mutableStateOf(0f)
        private set

    /** 复位（切 Tab / 刷新后 scrollToTop）：立即回到完全展开。 */
    fun reset() {
        offsetPx = 0f
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        // consumed = 子布局实际消费掉的位移；available = 子布局未消费的余量
        // （例如已滚到列表顶部后继续下拉的 over-scroll）。
        // 两者相加 = 本次真实滚动位移：既保证顶栏与内容 1:1 同步，
        // 又保证列表到顶后继续下拉仍能把顶栏拉回来。
        val delta = consumed.y + available.y
        if (delta != 0f) {
            val next = (offsetPx - delta)
                .coerceIn(0f, heightProvider().toFloat())
                .let { round(it) }
            if (next != offsetPx) {
                offsetPx = next
            }
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val height = heightProvider().toFloat()
        val current = offsetPx
        // 只在"半隐"时收敛；0 / height 两端已是合法静止态。
        if (height > 0f && current > 0f && current < height) {
            val target = if (current < height / 2f) 0f else height
            if (animations.reduceMotion) {
                offsetPx = target
            } else {
                Animatable(current).animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = animations.morph,
                        easing = animations.morphEasing
                    )
                ) {
                    offsetPx = value
                }
            }
        }
        return Velocity.Zero
    }
}
