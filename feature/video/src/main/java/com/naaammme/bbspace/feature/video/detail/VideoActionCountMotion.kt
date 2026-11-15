package com.naaammme.bbspace.feature.video.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations

/**
 * 互动计数（点赞 / 投币 / 收藏）的**变化动效**与"该不该动"的判据。
 *
 * 上游来源：BiliPai `3eb92ea04`（feat(video): 统一互动计数动效与三连长按状态），
 * 依据文件 `feature/video/ui/feedback/VideoActionCountText.kt:20-114` 与
 * `VideoActionCountMotionPolicyTest.kt`。
 *
 * ## 与上游的三处差异（本仓约束所致）
 * 1. **不用 `AnimatedContent` + tween 字面量**：上游 `transitionSpec` 里写了
 *    `tween(160)` / `tween(180)` / `tween(100)` / `tween(120)`。本仓
 *    `HardcodedAnimationDurationDetector` 把 `tween/spring` 的**数字字面量**判为 ERROR
 *    （A11y：会让"动效速度"档位与 reduceMotion 失效），所以这里改用
 *    `Animatable` + `tween(durationMillis = LocalAnimations.current.short)` —— 时长/曲线
 *    全部来自 token，动效速度档位与 reduceMotion 真正生效。
 * 2. **绘制期读值**：`Animatable` 的值只在 `graphicsLayer { }` 里读，动画每帧不触发
 *    Composable 重组（与 `IOSSwitch` / `BiliCardSurface` 的既有写法同源）。
 * 3. **只动"数字"本身**：上游用 AnimatedContent 做新旧两个文本的交叉滑入滑出；本仓
 *    改为对**新数字做一次轻微弹跳**（scale 0.84 → 1 + alpha 0.45 → 1）。同样的可读信号，
 *    但少一层内容切换与文本测量，且不需要第二个文本节点。
 *
 * ## 语义（与上游逐条一致）
 * - **首次组合不动画**：进详情页三个数字一起跳是上游明确要避免的；
 * - **后台刷新不动画**：只有"本地操作"（[VideoActionCountRequests] 被 arm）引起的
 *   变化才动；服务端回填/进入页面都保持静态；
 * - **数值真的变了才动**：格式化后相同的值（如 `1.2万` → `1.2万`）不动画，但请求
 *   仍然被消费（用原始计数 [VideoActionCountText] 的 `countValue` 判定）；
 * - **reduceMotion 瞬时到位**（上游 `LocalSystemReduceMotion`；本仓读
 *   `LocalAnimations.current.reduceMotion`，App 内速度档与系统移除动画两处取或）。
 */
@Stable
internal class VideoActionCountRequests {
    var like by mutableIntStateOf(0)
        private set
    var coin by mutableIntStateOf(0)
        private set
    var favorite by mutableIntStateOf(0)
        private set

    fun requestLike() {
        like++
    }

    fun requestCoin() {
        coin++
    }

    fun requestFavorite() {
        favorite++
    }

    fun requestTriple() {
        requestLike()
        requestCoin()
        requestFavorite()
    }
}

/** 是否该为这次文本变化播放计数动效（上游 `shouldAnimateVideoActionCountChange` 同义）。 */
internal fun shouldAnimateVideoActionCountChange(
    before: String,
    after: String,
    request: Int,
    consumedRequest: Int,
    reducedMotion: Boolean,
): Boolean = !reducedMotion && before != after && request != 0 && request != consumedRequest &&
    (before.any(Char::isDigit) || after.any(Char::isDigit))

/** 按动作分组的请求计数（每个 ActionCapsule 一份，生命周期随组合）。 */
@Composable
internal fun rememberVideoActionCountRequests(): VideoActionCountRequests =
    remember { VideoActionCountRequests() }

/**
 * 带"本地操作弹跳"的计数文本。
 *
 * @param request 该动作的请求计数（[VideoActionCountRequests] 的对应字段）
 * @param countValue **原始计数**（未格式化）——格式化会把 `12001` 与 `12100` 都压成
 *   `1.2万`，只看文本会漏掉"真的变了但看起来没变"的情况；传 null 表示没有原始计数
 *   （如分享数 / 下载无计数）。
 * @param identity 身份键（本仓用 aid）：切视频时重置状态，避免上一支视频的未消费请求
 *   让新视频的首个数字动一下。
 */
@Composable
internal fun VideoActionCountText(
    text: String,
    request: Int,
    color: Color,
    modifier: Modifier = Modifier,
    identity: Any? = Unit,
    countValue: Long? = null,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    val anim = LocalAnimations.current
    var displayed by remember(identity) { mutableStateOf(text) }
    var consumedRequest by remember(identity) { mutableIntStateOf(request) }
    var seenCount by remember(identity) { mutableStateOf(countValue) }
    val scale = remember(identity) { Animatable(1f) }
    val alpha = remember(identity) { Animatable(1f) }

    LaunchedEffect(identity, text, request, countValue, anim.reduceMotion) {
        val countChanged = seenCount != countValue
        if (displayed == text && !countChanged) return@LaunchedEffect
        val animate = shouldAnimateVideoActionCountChange(
            before = displayed,
            after = text,
            request = request,
            consumedRequest = consumedRequest,
            reducedMotion = anim.reduceMotion,
        )
        displayed = text
        // 消费无论是否真的动画：格式化后相同的值也应把这次请求用掉，
        // 否则下一次后台刷新会因为"陈旧的请求"错误地动一下（上游同款处理）。
        consumedRequest = request
        seenCount = countValue
        if (animate) {
            // 弹跳配方：先压小、再以 token 曲线回弹到 1；只读在 graphicsLayer，零重组。
            scale.snapTo(0.84f)
            alpha.snapTo(0.45f)
            scale.animateTo(1f, tween(durationMillis = anim.short, easing = anim.emphasized))
            alpha.animateTo(1f, tween(durationMillis = anim.short, easing = anim.standard))
        } else {
            scale.snapTo(1f)
            alpha.snapTo(1f)
        }
    }

    Text(
        text = displayed,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            this.alpha = alpha.value
        },
    )
}
