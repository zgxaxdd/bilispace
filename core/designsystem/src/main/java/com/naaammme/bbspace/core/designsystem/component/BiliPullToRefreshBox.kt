package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalPullRefreshThreshold

private val MinPullRefreshIndicatorThreshold = 40.dp

/**
 * 下拉刷新容器：**内容跟随指示器下移**（BiliPai `B8-b` 移植）。
 *
 * ## 这一版改了什么（观感）
 *
 * 此前只有指示器那个圆点在下降，正文钉在原地 —— 读作"转了个圈"，没有
 * "把整页拉下来"的物理感。现在内容的位移量**与指示器底边同源**
 * （推导见 [resolvePullContentOffsetPx]），下拉时整页跟着走。
 *
 * ## 两个必须写下来的实现约束
 *
 * ① **`distanceFraction` 只能在 `graphicsLayer { }` 里读。** 它是每帧随手指变化的
 *    State；在组合期读会让**整屏内容**每一帧重组（12 个调用点、每个里面都是
 *    `LazyColumn`/`LazyVerticalStaggeredGrid`）。放在绘制期读则只失效绘制，
 *    不进入组合 —— 本仓 `FrameBudgetLintTest` 记录的两处合规写法
 *    （`AppNavHost:805`、`BiliCardSurface:68`）就是同一模式。
 *
 * ② **包裹内容的 `Box` 不给任何尺寸修饰符**（原样透传约束）。`Skeleton.kt:244` 记着
 *    一次真实事故：用 `Box` 包住列表会把首页搞成"只渲染前几条、后面白屏"——原因是
 *    约束/测量链被切断。这里安全的依据是两条一起成立：`graphicsLayer` 本身是
 *    **测量透明**的（同约束测量、同尺寸上报，只加一个绘制层），而新 Box 不带
 *    `fillMaxSize`/`size`，量出来的就是内容尺寸、并把入参约束原封不动往下传。
 *    `contentAlignment` 也原样透传给该层，内容不满屏时的对齐行为与改造前一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    val triggerThreshold = LocalPullRefreshThreshold.current
    val indicatorThreshold = triggerThreshold.coerceAtLeast(MinPullRefreshIndicatorThreshold)

    // 指示器下沿与内容顶边之间的净空（token，不新增 dp 字面量）。
    val gapPx = with(LocalDensity.current) { BiliSpacing.md.toPx() }
    val indicatorThresholdPx = with(LocalDensity.current) { indicatorThreshold.toPx() }

    Box(
        modifier = modifier.pullToRefresh(
            state = state,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            threshold = triggerThreshold
        ),
        contentAlignment = contentAlignment
    ) {
        Box(
            // **刻意不给尺寸修饰符**：这样这个 Box 原样透传入参约束、并把自己量成内容大小，
            // 与改造前"content() 直接挂在最外层 Box 上"逐像素、逐约束等价。
            //
            // 反例（为什么不写 `fillMaxSize()`）：`fillMaxSize` 会把尺寸定成**入参的最大值**，
            // 一旦宿主给的是无界高度（wrap-content 的父容器），内层就会拿到 `Infinity`
            // —— 那是崩溃而不是观感问题。本仓有 12 个调用点，其中 `modifier` 是参数、
            // 由宿主决定的（如 `MsgFeedPane`）无法在此处穷举其约束，所以选**透传**这条
            // 在所有约束下都与原行为一致的路。
            modifier = Modifier
                // 值在绘制期读（见类 KDoc ①）—— 下拉过程不触发任何重组。
                // `graphicsLayer` 是测量透明的：同约束测量、同尺寸上报，只加一个绘制层，
                // 不会切断列表的约束/测量链（那正是 `Skeleton.kt:244` 记录的事故成因）。
                .graphicsLayer {
                    translationY = resolvePullContentOffsetPx(
                        distanceFraction = state.distanceFraction,
                        indicatorThresholdPx = indicatorThresholdPx,
                        gapPx = gapPx
                    )
                },
            contentAlignment = contentAlignment
        ) {
            content()
        }
        // Keep the indicator fully visible when the trigger threshold is set very small.
        PullToRefreshDefaults.Indicator(
            modifier = Modifier.align(Alignment.TopCenter),
            state = state,
            isRefreshing = isRefreshing,
            maxDistance = indicatorThreshold
        )
    }
}
