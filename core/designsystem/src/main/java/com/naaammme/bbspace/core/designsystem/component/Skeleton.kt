package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations

/**
 * 微光进度的共享通道。
 *
 * 为什么用 CompositionLocal 而不是逐层传参：骨架族有 8 个组合函数
 * （VideoGridCardSkeleton / VideoListCardSkeleton / CommentCardSkeleton …），
 * 每个内部又有多层嵌套的 [SkeletonBlock]。逐层加参数要改十几处签名、且以后新增
 * 骨架很容易漏传。容器提供一次、叶子按需读取，是最不容易出错的做法。
 *
 * 默认 null：没有容器提供时骨架保持静止（不引入任何动画开销）。
 */
private val LocalSkeletonShimmer = compositionLocalOf<Float?> { null }

/**
 * 创建一份**整屏共享**的微光进度值（0..1 循环）。
 *
 * 必须在骨架容器级调用一次并下发给各 [SkeletonBlock]，**不要每个色块各调一次**：
 * 一屏 8 张卡 × 每卡 5 个色块 = 40 个独立无限动画，是明确的掉帧来源；而且各扫各的
 * 会显得杂乱。频率取 [LocalAnimations] 的 medium × 6（扫光比常规动效慢才自然），
 * 用户把动画速度设为「关闭」时时长退化为极小值，视觉上等价静止。
 */
@Composable
fun rememberSkeletonShimmer(): Float {
    val anim = LocalAnimations.current
    val transition = rememberInfiniteTransition(label = "skeletonShimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = anim.medium * 6, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonShimmerProgress"
    )
    return progress
}

/**
 * 为内部所有 [SkeletonBlock] 提供微光进度。
 *
 * **幂等**：若外层已提供过（LocalSkeletonShimmer != null），这里直接透传、不新建动画。
 * 这条很关键——骨架组件既有"整屏容器"（[VideoListSkeletonList]），也有被逐个调用的
 * "单卡骨架"（[VideoGridCardSkeleton] 等）。不幂等的话，一个 8 卡骨架屏会创建 8 个
 * 各自的无限动画；幂等后外层提供一次、内层复用，全屏仍只有一份动画。
 *
 * 之所以由各骨架组件自带 provider，而不是只放在调用方容器里：骨架组件在各页被**逐个
 * 调用**，靠调用方记得包一层必然漏（首页 FeedCardSkeleton 就漏了，导致首页看不到微光）。
 */
@Composable
fun ProvideSkeletonShimmer(
    content: @Composable () -> Unit
) {
    if (LocalSkeletonShimmer.current != null) {
        content()
        return
    }
    val progress = rememberSkeletonShimmer()
    CompositionLocalProvider(LocalSkeletonShimmer provides progress, content = content)
}

@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = Color.Unspecified
) {
    val blockColor = if (color == Color.Unspecified) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        color
    }
    // 微光由容器通过 LocalSkeletonShimmer 下发；未提供时为 null → 完全静止、零开销。
    val shimmer = LocalSkeletonShimmer.current
    var m = modifier.background(color = blockColor, shape = shape)
    if (shimmer != null) {
        m = m.drawWithContent {
            drawContent()
            // 扫光带宽占宽度比例；从左侧完全外扫到右侧完全外
            val band = 0.4f
            val head = -band + shimmer * (1f + 2 * band)
            val x0 = size.width * head
            val x1 = size.width * (head + band)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0f),
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0f)
                    ),
                    start = Offset(x0, 0f),
                    end = Offset(x1, size.height)
                )
            )
        }
    }
    Box(modifier = m)
}

/**
 * 「列表首次加载」骨架容器：整屏 LazyColumn + [VideoListCardSkeleton] × [count]。
 *
 * 为什么要抽出来：这段容器（contentPadding 12/12、间距 10dp、key/contentType 约定）
 * 原本在收藏、搜索、历史三处**逐字重复**，任何一个参数调整都要改三遍，正是过去
 * 「改一次加载态要动多处」的来源。视觉与行为保持与三处原实现完全一致。
 *
 * @param count 骨架条数；各页原先分别是 8（收藏/搜索）与 8（历史默认），沿用调用方取值。
 * @param keyPrefix 列表项 key 前缀，用于区分同页面内多组骨架、避免 key 冲突。
 */
@Composable
fun VideoListSkeletonList(
    count: Int,
    modifier: Modifier = Modifier,
    keyPrefix: String = "skeleton"
) {
    // 微光只在这里创建一次（整屏共享），经 LocalSkeletonShimmer 下发给内部所有色块：
    // 同屏同步扫过，且只付一份动画成本。
    // 注意这里是**提供 CompositionLocal**，不是用外层 Box 包裹 LazyColumn——
    // 后者会切断列表的约束/测量链（此前正是这样把首页搞成只渲染前几条、后面白屏）。
    ProvideSkeletonShimmer {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                count = count,
                key = { index -> "${keyPrefix}_$index" },
                contentType = { "skeleton" }
            ) {
                VideoListCardSkeleton()
            }
        }
    }
}

@Composable
fun VideoGridCardSkeleton(modifier: Modifier = Modifier) {
    ProvideSkeletonShimmer {
    Column(modifier = modifier.fillMaxWidth()) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
        )
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(16.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(
                    modifier = Modifier
                        .width(60.dp)
                        .height(12.dp),
                    shape = MaterialTheme.shapes.extraSmall
                )
                SkeletonBlock(
                    modifier = Modifier
                        .width(60.dp)
                        .height(12.dp),
                    shape = MaterialTheme.shapes.extraSmall
                )
            }
        }
    }
    }
}

@Composable
fun VideoListCardSkeleton(modifier: Modifier = Modifier) {
    ProvideSkeletonShimmer {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SkeletonBlock(
            modifier = Modifier
                .weight(0.38f)
                .aspectRatio(16f / 10f),
            shape = MaterialTheme.shapes.medium
        )
        Column(
            modifier = Modifier.weight(0.62f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(18.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(18.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(
                    modifier = Modifier
                        .width(72.dp)
                        .height(12.dp),
                    shape = MaterialTheme.shapes.extraSmall
                )
                SkeletonBlock(
                    modifier = Modifier
                        .width(58.dp)
                        .height(12.dp),
                    shape = MaterialTheme.shapes.extraSmall
                )
            }
        }
    }
    }
}

@Composable
fun CommentHeaderSkeleton(modifier: Modifier = Modifier) {
    ProvideSkeletonShimmer {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkeletonBlock(
            modifier = Modifier
                .width(60.dp)
                .height(32.dp),
            shape = MaterialTheme.shapes.extraLarge
        )
    }
    }
}

@Composable
fun CommentCardSkeleton(modifier: Modifier = Modifier) {
    ProvideSkeletonShimmer {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            SkeletonBlock(
                modifier = Modifier.size(44.dp),
                shape = CircleShape
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.34f)
                            .height(18.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonChip(width = 52.dp)
                        SkeletonChip(width = 68.dp)
                    }
                }
                SkeletonChip(width = 56.dp)
            }
        }
        Column(
            modifier = Modifier.padding(start = 56.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(16.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.66f)
                    .height(16.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonBlock(
                        modifier = Modifier
                            .width(56.dp)
                            .height(14.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    )
                    SkeletonBlock(
                        modifier = Modifier
                            .width(52.dp)
                            .height(14.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                SkeletonBlock(
                    modifier = Modifier
                        .width(32.dp)
                        .height(18.dp),
                    shape = MaterialTheme.shapes.extraSmall
                )
            }
        }
    }
    }
}

@Composable
fun VideoDetailInfoSkeleton(modifier: Modifier = Modifier) {
    ProvideSkeletonShimmer {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.extraLarge
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .width(72.dp)
                    .aspectRatio(1f),
                shape = MaterialTheme.shapes.large
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(20.dp),
                    shape = MaterialTheme.shapes.extraSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonChip(width = 76.dp)
                    SkeletonChip(width = 88.dp)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.extraLarge
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(22.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .height(22.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonChip(width = 64.dp)
                SkeletonChip(width = 92.dp)
                SkeletonChip(width = 84.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonChip(width = 78.dp)
                SkeletonChip(width = 70.dp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.extraLarge
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionSkeletonChip()
            ActionSkeletonChip()
            ActionSkeletonChip()
        }

        DetailEntryCardSkeleton()
        DetailEntryCardSkeleton()
    }
    }
}

@Composable
fun VideoRelateCardSkeleton(modifier: Modifier = Modifier) {
    ProvideSkeletonShimmer {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        SkeletonBlock(
            modifier = Modifier
                .weight(0.38f)
                .aspectRatio(16f / 10f),
            shape = MaterialTheme.shapes.large
        )
        Column(
            modifier = Modifier.weight(0.62f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .height(18.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .height(18.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.46f)
                    .height(14.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
        }
    }
    }
}

@Composable
private fun SkeletonChip(width: Dp) {
    SkeletonBlock(
        modifier = Modifier
            .width(width)
            .height(28.dp),
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun ActionSkeletonChip() {
    SkeletonBlock(
        modifier = Modifier
            .width(84.dp)
            .height(34.dp),
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun DetailEntryCardSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(0.28f)
                .height(20.dp),
            shape = MaterialTheme.shapes.extraSmall
        )
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .height(18.dp),
            shape = MaterialTheme.shapes.extraSmall
        )
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .height(16.dp),
            shape = MaterialTheme.shapes.extraSmall
        )
    }
}
