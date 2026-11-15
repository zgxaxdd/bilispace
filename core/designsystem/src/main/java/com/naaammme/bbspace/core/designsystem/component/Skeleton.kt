package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.motion.MotionTierPolicy
import com.naaammme.bbspace.core.designsystem.motion.rememberMotionTier
import com.naaammme.bbspace.core.designsystem.theme.BiliDetailLayout
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations

/**
 * 呼吸进度的共享通道。
 *
 * 为什么用 CompositionLocal 而不是逐层传参：骨架族有 8 个组合函数
 * （VideoGridCardSkeleton / VideoListCardSkeleton / CommentCardSkeleton …），
 * 每个内部又有多层嵌套的 [SkeletonBlock]。逐层加参数要改十几处签名、且以后新增
 * 骨架很容易漏传。容器提供一次、叶子按需读取，是最不容易出错的做法。
 *
 * 默认 null：没有容器提供时骨架保持静止（不引入任何动画开销）。
 */
private val LocalSkeletonBreath = compositionLocalOf<State<Float>?> { null }

/** 呼吸一个完整周期的时长（ms）。1.1s 接近自然呼吸节奏：比扫光慢、比静止明显。 */
private const val SKELETON_BREATH_CYCLE_MS = 1100

/** 呼吸端点：块底色的 alpha 倍数。0.45→1.0 的幅度在深浅两种主题上都清晰可见。 */
private const val SKELETON_BREATH_MIN = 0.45f
private const val SKELETON_BREATH_MAX = 1.0f

/**
 * 骨架色块的统一形状：**半高胶囊**。
 *
 * 为什么用 `percent = 50` 而不是固定 dp 圆角：骨架色块高度差异很大（20dp 的标题条、
 * 13dp 的简介条、11dp 的提示条）。固定 16dp 圆角在 20dp 高的条上会被 Compose 自动
 * 收敛到半高（10dp），在 11dp 高的条上则收敛到 5.5dp —— **同一个"16dp 圆角"在不同
 * 高度的块上观感完全不同**，一排看过去参差不齐。
 *
 * `percent = 50` 让每个块都精确成为"两端半圆的胶囊"，无论高度多少，圆角语言统一，
 * 也正是当下现代 UI 的骨架形态。
 */
private val SkeletonBarShape: Shape = RoundedCornerShape(percent = 50)

/**
 * 创建一份**整屏共享**的呼吸进度值（0..1 往返）。
 *
 * 必须在骨架容器级调用一次并下发给各 [SkeletonBlock]，**不要每个色块各调一次**：
 * 一屏 8 张卡 × 每卡 5 个色块 = 40 个独立无限动画，是明确的掉帧来源；而且各呼吸各的
 * 会显得杂乱。
 *
 * 返回 `State<Float>` 而不是 `Float`：**这是关键**。若直接返回 Float，[SkeletonBlock]
 * 就会在**组合期**读取它 → 动画每帧都让全部 ~40 个色块各重组一次（无限动画每秒 60 帧
 * 重组一整屏子树）。返回 State 后，消费方在**绘制期**读 `.value`，动画帧只让对应
 * 节点重绘、不触发任何重组。
 *
 * 用 `RepeatMode.Reverse` + 缓入缓出得到"淡出→淡入"的呼吸感；线性 Restart 会读作
 * 机械的闪烁。
 */
@Composable
fun rememberSkeletonBreath(): State<Float> {
    val anim = LocalAnimations.current
    // W3：设备动效分档（纯逻辑见 MotionTierPolicy）。中端机把呼吸周期拉长到 1.4 倍
    // ——同样的明暗变化、更低的重绘频率；低端机由 [ProvideSkeletonShimmer] 直接
    // 不提供时钟（走静止分支），这里拿到的倍率恒为 1。
    val cycleMultiplier = MotionTierPolicy.breathCycleMultiplier(rememberMotionTier())
    val transition = rememberInfiniteTransition(label = "skeletonBreath")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // 按用户动画速度缩放；速度设为「关闭」时 medium 退化为 1，这里仍有下限，
            // 视觉上等价静止（与旧 shimmer 的处理一致）
            animation = tween(
                durationMillis = (SKELETON_BREATH_CYCLE_MS * anim.medium * cycleMultiplier / 200f)
                    .toInt().coerceAtLeast(1),
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonBreathProgress"
    )
}

/**
 * 为内部所有 [SkeletonBlock] 提供呼吸进度。
 *
 * **幂等**：若外层已提供过（LocalSkeletonBreath != null），这里直接透传、不新建动画。
 * 这条很关键——骨架组件既有"整屏容器"（[VideoListSkeletonList]），也有被逐个调用的
 * "单卡骨架"（[VideoGridCardSkeleton] 等）。不幂等的话，一个 8 卡骨架屏会创建 8 个
 * 各自的无限动画；幂等后外层提供一次、内层复用，全屏仍只有一份动画。
 *
 * 之所以由各骨架组件自带 provider，而不是只放在调用方容器里：骨架组件在各页被**逐个
 * 调用**，靠调用方记得包一层必然漏。
 */
@Composable
fun ProvideSkeletonShimmer(
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    // W3：设备动效分档（**无条件**调用，保证组合槽位稳定；内部有进程级缓存）。
    val motionTier = rememberMotionTier()
    if (LocalSkeletonBreath.current != null) {
        content()
        return
    }
    // FL-01 ③：动效设为「关闭」（reduceMotion）时不 provide —— LocalSkeletonBreath
    // 保持 null，SkeletonBlock 走完全静止分支。关闭动效 = 不启动常驻动画
    // （与玻璃预算 E-02 / AP-28 同一口径），性能与无障碍双收。
    // W3 追加：**低端机同口径** —— 设备能力不够时也不起常驻无限动画（判据来源不同，
    // 结果一致：不给时钟 ⇒ 骨架静止）。
    if (!enabled ||
        LocalAnimations.current.reduceMotion ||
        !MotionTierPolicy.enablesAmbientMotion(motionTier)
    ) {
        content()
        return
    }
    val breath = rememberSkeletonBreath()
    CompositionLocalProvider(LocalSkeletonBreath provides breath, content = content)
}

/**
 * AP-15：注入**外部创建**的共享呼吸时钟（与 [ProvideSkeletonShimmer] 的区别是
 * 呼吸由调用方在"骨架区可见"的条件下 hoist 出来，而不是这里创建）。
 *
 * 用途：普通 LazyColumn 的"翻页骨架"（`items(count = 2) { VideoListCardSkeleton() }`）
 * ——骨架区只在加载更多时存在，把呼吸的创建条件放在屏幕层的 [rememberSkeletonBreathOrNull]
 * 上，item 体内注入同一实例：N 个骨架共享一个时钟，骨架区消失即随组合销毁，
 * 不给常驻列表留无限动画。
 *
 * [breath] 为 null（未在骨架态 / reduceMotion）时透传：单卡骨架自带的幂等 provider
 * 会检查 reduceMotion 并保持静止（[SkeletonBlock] 完全静止分支）。
 */
@Composable
fun ProvideSkeletonBreath(
    breath: State<Float>?,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalSkeletonBreath provides breath, content = content)
}

/**
 * AP-15：创建共享呼吸的**条件入口** —— reduceMotion（动效=关）返回 null，
 * 骨架完全静止（FL-01 ③ 的同款口径）。只在骨架区可见时调用；
 * 给常驻可见的内容无条件调用等于留一条永不停止的无限动画。
 */
@Composable
fun rememberSkeletonBreathOrNull(): State<Float>? =
    if (LocalAnimations.current.reduceMotion) null else rememberSkeletonBreath()

/**
 * 骨架色块。有容器下发呼吸进度时**整块明暗脉动**，否则静止。
 *
 * ## 为什么是"呼吸"而不是"扫光"（旧实现的实测问题）
 *
 * 旧实现是一道 `Color.White.copy(alpha = 0.14f)` 的斜向扫光，两个毛病：
 * 1. **浅色主题下几乎看不见** —— 白底上叠 14% 白，观感就是"骨架不动"；
 * 2. 扫光要每帧重算渐变 Brush，而呼吸只需改一个 alpha。
 *
 * 呼吸用**底色 alpha 的倍数**调制，因此深浅主题都清晰：色块在"淡出→淡入"地呼吸，
 * 而不是一道光扫过。
 */
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
    // 呼吸进度由容器通过 LocalSkeletonBreath 下发；未提供时为 null → 完全静止、零开销。
    // 这里拿的是 State，**不在组合期读值**，读取推迟到绘制回调里。
    val breath = LocalSkeletonBreath.current
    val m = if (breath == null) {
        modifier.background(color = blockColor, shape = shape)
    } else {
        // **必须先 clip(shape) 再画**。
        //
        // 踩过的坑：呼吸层用 `drawWithContent { drawRect(...) }` 直接画矩形，而
        // `drawRect` 不受 `background(shape=)` 的圆角约束 —— 于是圆角被这层直角矩形
        // 盖住，观感是"骨架是直角、太锋锐"（明明 shape 传的是胶囊）。
        // clip 之后呼吸层与底色层共用同一形状，圆角才真正生效。
        //
        // 用 **alpha 调制**而不是 lerp 到白/黑：后者在纯黑主题上会把色块"提亮"成灰、
        // 与背景脱节；alpha 调制保持色相不变，只是明暗呼吸。
        modifier
            .clip(shape)
            .background(color = blockColor)
            .drawWithContent {
                // 在绘制期读状态：动画帧只失效绘制、不触发重组
                val t = breath.value
                val a = SKELETON_BREATH_MIN +
                    (SKELETON_BREATH_MAX - SKELETON_BREATH_MIN) * t
                drawRect(color = blockColor.copy(alpha = a), size = size)
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
    // 微光只在这里创建一次（整屏共享），经 LocalSkeletonBreath 下发给内部所有色块：
    // 同屏同步扫过，且只付一份动画成本。
    // 注意这里是**提供 CompositionLocal**，不是用外层 Box 包裹 LazyColumn——
    // 后者会切断列表的约束/测量链（此前正是这样把首页搞成只渲染前几条、后面白屏）。
    ProvideSkeletonShimmer {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = BiliSpacing.md, vertical = BiliSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
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
                .aspectRatio(16f / 10f),
            // 封面块与真实卡片封面共用同一个 token：骨架要"预演"最终形状，
            // 否则加载完成时形状会突变（此前这里是默认直角，而真实卡片是圆角）。
            shape = SkeletonBarShape
        )
        Column(modifier = Modifier.padding(horizontal = BiliSpacing.smPlus, vertical = BiliSpacing.sm)) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(16.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Spacer(modifier = Modifier.height(BiliSpacing.xsPlus))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp),
                shape = MaterialTheme.shapes.extraSmall
            )
            Spacer(modifier = Modifier.height(BiliSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
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
            .padding(BiliSpacing.smPlus),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
    ) {
        SkeletonBlock(
            modifier = Modifier
                .weight(0.38f)
                .aspectRatio(16f / 10f),
            shape = SkeletonBarShape
        )
        Column(
            modifier = Modifier.weight(0.62f),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
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
            Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
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
            .padding(vertical = BiliSpacing.xs),
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
            .padding(BiliSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
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
                    verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
                ) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.34f)
                            .height(18.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                        SkeletonChip(width = 52.dp)
                        SkeletonChip(width = 68.dp)
                    }
                }
                SkeletonChip(width = 56.dp)
            }
        }
        Column(
            modifier = Modifier.padding(start = 56.dp),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)
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
                Row(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md)) {
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

/**
 * 视频详情介绍区骨架。
 *
 * **必须与 `VideoSummarySection` 的实际版式一一对应**，否则加载完成时会出现
 * 明显的"跳版"（骨架是旧版式的形状、真内容是新版式的形状）。
 * 当前版式顺序：标题块（标题 + 作者行）→ 统计行 → 操作栏 → 简介行。
 *
 * ## 对齐契约（改这里必须同步改 VideoDetailPage）
 *
 * - 水平内边距 = `contentHorizontalPad`（调用方传入，详情页为 16dp）；
 * - 顶部内边距 = [BiliSpacing.lg]（对应 `LazyColumn` 的 `contentPadding.top = infoTopPad`）；
 * - 内部行距 = [BiliSpacing.md]（对应 `VideoSummarySection` 的 `spacedBy`）；
 * - 标题块内行距 = 6dp（对应标题与作者行的紧凑分组）；
 * - 操作栏 5 项**等分**（对应 `ActionChip` 的 `weight(1f)`）。
 *
 * 遮罩里复用本组件时必须带上同样的内边距，否则会与真实内容错位一格。
 *
 * ## 圆角
 *
 * 全部走 [BiliRadius] 设计 token，不写字面量：文本条用 [BiliRadius.card]（16dp，与卡片
 * 一致），细条与胶囊用 [BiliRadius.searchPill]（20dp）。骨架用大圆角是为了与全局
 * "大圆角"语言一致 —— 旧的 `extraSmall`（4dp）在小色块上接近直角，观感陈旧。
 */
@Composable
fun VideoDetailInfoSkeleton(modifier: Modifier = Modifier) {
    ProvideSkeletonShimmer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                // 与 LazyColumn 的 contentPadding.top = introTopGap 对齐。
                //
                // 2026-10-12：改为**直接引用同一个常量** [BiliDetailLayout.INTRO_TOP_GAP]。
                // 上一版这里写的是 `(BiliSpacing.lg - TITLE_LIFT)`，被 coerce 夹到 0，
                // 而真实内容那侧是 `16dp + statusTop - TITLE_LIFT` = 32dp —— 骨架看不到
                // `statusTop`（它是系统 inset），于是加载态比真实内容高 32dp，详情一到达
                // 整块内容就往下跳一格。现在两侧都是"一个常量、零算术"，不可能再漂移。
                .padding(top = BiliDetailLayout.INTRO_TOP_GAP),
            // 与 VideoSummarySection 的 spacedBy(BiliSpacing.md) 保持一致
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.md)
        ) {
            // ── 标题块：标题（最多 3 行）+ 作者行 ──────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)) {
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .height(20.dp),
                    shape = SkeletonBarShape
                )
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .height(20.dp),
                    shape = SkeletonBarShape
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 头像：真实内容是 42dp 圆形（OwnerRow），骨架保持同尺寸同形状
                    SkeletonBlock(
                        modifier = Modifier.size(42.dp),
                        shape = CircleShape
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
                    ) {
                        SkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.32f)
                                .height(14.dp),
                            shape = SkeletonBarShape
                        )
                        SkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.48f)
                                .height(11.dp),
                            shape = SkeletonBarShape
                        )
                    }
                }
            }

            // ── 统计行：一行小字 ──────────────────────────────────────────
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.66f)
                    .height(12.dp),
                shape = SkeletonBarShape
            )

            // ── 操作栏：5 个等分项，图标在上、数值在下 ─────────────────────
            // 真实操作栏是 CapsuleCard（extraLarge 圆角 + surfaceContainerLow 底），
            // 骨架用同色同圆角，避免"骨架是方块、真内容是胶囊"的跳版。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.extraLarge
                    )
                    .padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                // 5 项等分：真实 ActionChip 用 weight(1f)，骨架必须同样等分，
                // 否则宽度对不上（旧版固定 84dp 在 5 项时会溢出）。
                repeat(5) {
                    ActionSkeletonChip(modifier = Modifier.weight(1f))
                }
            }

            // ── 简介：折叠态两行 + 右对齐的「展开全文」胶囊 ──────────────────
            //
            // 2026-10-11：与真实内容的 `DescBlock` 对齐 —— 动作入口从"正文最后一行
            // 小字"改成了**右对齐的主色胶囊**（带箭头）。骨架也必须同形同宽同位置，
            // 否则加载态与真实内容之间会"第三行小字 → 右侧一颗胶囊"地跳一下。
            Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.smPlus)) {
                Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(13.dp),
                        shape = SkeletonBarShape
                    )
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .height(13.dp),
                        shape = SkeletonBarShape
                    )
                }
                // 动作胶囊：右对齐、高度与 `DescToggleChip`（labelMedium + 上下 xsPlus
                // 内边距 + 图标 16dp）的实测高度对齐。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SkeletonBlock(
                        modifier = Modifier
                            .width(BiliSpacing.xxxl * 3.5f)
                            .height(BiliSpacing.xxxl),
                        shape = MaterialTheme.shapes.extraLarge
                    )
                }
            }
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
            .padding(BiliSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        SkeletonBlock(
            modifier = Modifier
                .weight(0.38f)
                .aspectRatio(16f / 10f),
            shape = SkeletonBarShape
        )
        Column(
            modifier = Modifier.weight(0.62f),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
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

/**
 * 操作栏单个骨架项：图标 + 数值两行。
 *
 * 尺寸对齐真实 `ActionChip`（图标 22dp + 2dp 间距 + 12sp 文本 ≈ 34dp 内容高，
 * 上下各 [BiliSpacing.sm] 内边距）。圆角用 `extraLarge`——真实 chip 的点击区
 * 是 `MaterialTheme.shapes.large`，而外层 CapsuleCard 是 `extraLarge`，
 * 骨架取后者，避免小块在大胶囊里显得突兀。
 *
 * [modifier] 由调用方传入 `Modifier.weight(1f)`，保证 5 项等分。
 */
@Composable
private fun ActionSkeletonChip(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = BiliSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SkeletonBlock(
            modifier = Modifier.size(22.dp),
            shape = CircleShape
        )
        SkeletonBlock(
            modifier = Modifier
                .width(28.dp)
                .height(11.dp),
            shape = SkeletonBarShape
        )
    }
}

// K-7（2026-10-04 审查报告）：这里原有一个 `private fun DetailEntryCardSkeleton()`
// （详情入口卡的骨架），**全仓零调用**——详情页最终用的是 `detailItems` 那套骨架。
// 属"组件换了、旧骨架忘了删"，故删除而不是留着当参考实现。
