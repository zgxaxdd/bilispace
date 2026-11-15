package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.motion.MotionTierPolicy
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.rememberMotionTier
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

/**
 * 统一的媒体卡片容器：固定底色、按主题形状裁剪内容（封面圆角跟随卡片）。
 * 可点击时带轻微按压缩放（0.97）反馈，松手弹性回弹。
 * 传入 [onLongClick] 时，点击与长按均由 combinedClickable 统一处理（与 Surface 点击不叠加）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BiliCardSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    /**
     * 铺满**整张卡片**的覆盖层槽位（画在内容之上、Surface 之内）。
     *
     * 为什么需要它：卡片内容装在 `Column` 里（因为要用 `Modifier.weight`），
     * 而 `Modifier.matchParentSize()` 是 `BoxScope` 限定修饰符——放在 Column 里
     * 会被当成真实布局子项。想让某个元素覆盖整卡（例如 OPPO 无缝动画的锚点
     * 需要"封面 + 简介"整个矩形作为起飞区域），就必须有一个 Box 层的槽位。
     *
     * 传 `null` 时零开销：不会多套一层 Box。
     */
    fullCardOverlay: (@Composable BoxScope.() -> Unit)? = null,
    /**
     * 是否挂全局拖拽反馈的整卡形变。**锚点宿主必须传 `false`**。
     *
     * ## 为什么必须有这个开关（第 34 轮，OPPO 无缝动画 bug 的真正根因）
     *
     * 本组件是首页卡 / 排行卡 / 空间页卡的共同容器，而这三类卡的 OPPO 无缝锚点
     * （`SeamlessAnchor`）**恰好放在 [fullCardOverlay] 里**（铺满整卡，因为锚点矩形
     * 就是系统的过渡几何范围）。
     *
     * 于是同一张卡上出现了两个互相矛盾的"卡片现在长在哪"：
     * - 框架取锚点矩形走 `View.getLocationInWindow()` / `getLocationOnScreen()` ——
     *   **布局期**坐标，看不到 `graphicsLayer` 的形变；
     * - 本组件的形变是 `Modifier.appElasticPress` → `graphicsLayer` —— **绘制期**变换，
     *   只改像素落点，不改布局。
     *
     * 用户在**按住卡片**（卡片正被放大 ~2% 并被手指拖偏若干像素）时点开视频，
     * 框架就按"屏幕上已经不存在的矩形"起飞 ⇒ 动画从错误的位置/大小开始。
     * 排行页最明显：卡片在瀑布流里，点击前常带一次未走完的按压回弹。
     *
     * ## 判据
     * 「本卡是否是系统过渡的锚点宿主」：锚点真组合时（`seamless.active`）整卡形变
     * **必须让位**给系统过渡；只有不支持无缝的设备（`active=false`，锚点不组合、
     * 走普通打开）才保留按压反馈 —— 那种情况下没有框架取矩形，形变不会和任何东西打架。
     *
     * ⇒ 调用点一律写 `elasticPressEnabled = !seamless.active`（或等价的 `!canSeamless`）。
     * 守卫：`SeamlessAnchorElasticPressGuardTest` 钉住三个已知调用点。
     *
     * ## 为什么不做成"有 fullCardOverlay 就自动关"
     * 那样会把两件事绑死：`fullCardOverlay` 是**布局槽位**（谁都可以放东西），
     * 而本参数是**过渡几何的让位判据**。隐式推导会让"以后在覆盖层里放别的装饰"
     * 悄悄丢掉按压反馈，且读代码的人看不出因果。
     */
    elasticPressEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    // 纯黑深色下的发丝描边（仅默认不透明容器色生效，见函数文档）
    val hairline = biliDarkHairlineBorder(containerColor)
    // ## 全局拖拽反馈（2026-10-18 第十九轮，**原语层**接入，仅可点分支）
    //
    // 本组件是首页卡 / 排行卡 / 空间页卡 / UP 主头部卡的共同容器。任务书
    // task-9 A 明确"**仅当**可点时挂"——不可点的卡片没有按压目标，挂了只会
    // 白付一份 pointerInput 节点与形变状态。
    //
    // **位置在 `clip(shape)` 之前（更外层）**：`appElasticPress` 的
    // `graphicsLayer` 必须包住已裁剪的子树，形变才会连卡片形状一起动；
    // 若挂在 clip 之内，clip 用的是未变换的 bounds，按下时内容会被裁掉一角。
    //
    // **长按场景降级为"只按压、不跟手"**（`dragEnabled = onLongClick == null`，
    // 与上游 BiliPai 同判据）：`onLongClick` 意味着这里有长按手势（复制/选择/更多菜单），
    // 跟手位移会与"按住不动"的长按意图打架；关掉 position 追踪后仍有按压鼓起，
    // 手感不丢。这也是 task-9 B 里"长按/滑动行不要挂"的精细版本。
    //
    // ⚠️ 与下面既有的 0.97 按压缩放**叠加**（两层 graphicsLayer 相乘）：
    // 大卡高度 ~200dp 时弹性膨胀 ≈ 2%，净效果 ≈ 0.97 × 1.02 ≈ 0.99，仍是"被按下"的
    // 缩小方向，观感不变；保留 0.97 是因为它是既有观感，不属本任务改动范围。
    val pressedModifier = if ((onClick != null || onLongClick != null) && elasticPressEnabled) {
        modifier.appElasticPress(dragEnabled = onLongClick == null)
    } else {
        modifier
    }
    val clippedModifier = pressedModifier.clip(shape)
    // W3：设备动效分档 + 无障碍「动效=关」—— 两者任一命中时**不做按压缩放**。
    // 为什么放在卡片（而不是每个调用点）：按压缩放是信息流里**每张卡**都会跑的
    // 常驻形变，低端机上它是"每卡每帧一次图层变换"；不需要时连目标值都不该动。
    // 注意 animateFloatAsState 调用点本身**保持不动**（守卫棘轮：调用点数只许减），
    // 这里只改 targetValue。
    val motionTier = rememberMotionTier()
    val pressAnimEnabled = MotionTierPolicy.enablesAmbientMotion(motionTier) &&
        !LocalAnimations.current.reduceMotion
    val pressScaleGoal = if (pressAnimEnabled) 0.97f else 1f
    // 只有需要覆盖层时才多套一层 Box，避免给所有卡片白加一层布局节点
    val body: @Composable () -> Unit = {
        if (fullCardOverlay != null) {
            Box {
                Column(content = content)
                fullCardOverlay()
            }
        } else {
            Column(content = content)
        }
    }
    if (onClick != null || onLongClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            targetValue = if (isPressed) pressScaleGoal else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "cardPressScale"
        )
        // 缩放值在 graphicsLayer 块内延迟读取：按压动画的每一帧只更新图层属性，
        // 不再按帧重组卡片 scope（Modifier.scale(pressScale) 会在组合期读值，
        // 每个 feed/排行卡片的按压回弹都会按帧重组整张卡片）
        val pressModifier = clippedModifier.graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        }
        if (onLongClick != null) {
            // 长按场景：点击/长按都由 combinedClickable 处理，Surface 仅作容器
            Surface(
                modifier = pressModifier.combinedClickable(
                    enabled = enabled,
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick,
                    interactionSource = interactionSource
                ),
                shape = shape,
                color = containerColor,
                contentColor = contentColor,
                border = hairline
            ) {
                body()
            }
        } else {
            Surface(
                onClick = onClick ?: {},
                enabled = enabled,
                modifier = pressModifier,
                shape = shape,
                color = containerColor,
                contentColor = contentColor,
                border = hairline,
                interactionSource = interactionSource
            ) {
                body()
            }
        }
    } else {
        Surface(
            modifier = clippedModifier,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = hairline
        ) {
            body()
        }
    }
}

/**
 * 纯黑深色下的卡片发丝描边（iOS 深色增强 2026-09-26）。
 *
 * 为什么需要：iOS 深色的底是纯黑（#000）、卡片是 #1C1C1E——两档都很暗且中间
 * 没有过渡，大面积信息流读作"一整块黑"（用户反馈"纯黑太单调"）。Telegram /
 * YouTube / X 的 AMOLED 主题普遍用 6–8% 白的 1px 轮廓把卡片从底色上"托"出来，
 * 本仓沿用该手法：只在 iOS 档 + 深色 + 纯黑底（背景恰好是 Color.Black）+
 * 卡片为默认不透明容器色时生效；玻璃卡（透明容器）与非纯黑底不描边。
 */
@Composable
internal fun biliDarkHairlineBorder(containerColor: Color): androidx.compose.foundation.BorderStroke? {
    if (LocalUiStyle.current != UiStyle.IOS) return null
    val bg = MaterialTheme.colorScheme.background
    if (bg.luminance() >= 0.06f) return null // 浅色不描边
    if (containerColor == Color.Transparent || containerColor == Color.Unspecified) return null
    if (containerColor != MaterialTheme.colorScheme.surfaceContainerLow &&
        containerColor != MaterialTheme.colorScheme.surfaceContainer
    ) return null
    // 纯黑底描边稍强（7%），染色炭底稍弱（5%）——炭底本身与卡片已有明度差
    // AP-29 ④：BorderStroke + Color.copy 原为每次组合新建；BorderStroke 是 @Immutable
    // 且实现 equals，remember 住后下游 Modifier.border 的相等性检查不再失效。
    return remember(bg, containerColor) {
        val alpha = if (bg == Color.Black) 0.07f else 0.05f
        androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = alpha))
    }
}

