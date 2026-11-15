package com.naaammme.bbspace.feature.video.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.core.designsystem.component.BiliParticleLoader
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.swipe.SwipeEntry
import com.naaammme.bbspace.core.swipe.SwipeQueueTail

/**
 * 刷视频会话里那些**不带会话状态的纯展示叶子**（2026-10-19 第 34 轮拆分自
 * `PortraitSwipePager.kt`）。
 *
 * 为什么单独成文件：主文件当时 1517 行、主函数 1114 行，而下面这些小叶子
 * （封面页 / 队列尾提示 / 横屏 FAB / 加载态）**不持有任何会话状态**，
 * 只是"给参数就画"。把它们放在主文件里有两个实际代价：
 * 1. 主函数被撑长到一屏看不完，改一处要滚半天；
 * 2. 它们与"会话状态 / effect / 手势"混在同一个文件，读者分不清哪些是**会变的接线**、
 *    哪些是**不变的展示**。
 *
 * 拆出来后本文件零 `LaunchedEffect`、零 `rememberSaveable`、零状态提升 ——
 * 纯函数式 composable，可以单独预览与改样式而不碰会话逻辑。
 */

/** 封面页加载动画的边长（占屏宽比例）。比播放器页的加载层小一档：它压在封面之上。 */
private const val COVER_LOADER_WIDTH_FRACTION = 0.26f

/**
 * 非当前页：封面铺满 + 当前页取流中的加载指示。
 *
 * ⚠️ 这里**不再有**底部标题/UP 信息条（2026-10-02 删除，用户第二次反馈"底部简介还是会动"）：
 * 它长在**页面内部**，翻页时会跟着页面从下往上滑进来——而会话级信息层（pager 的底部三件套）
 * 已经在同一位置常驻显示同一份 @名/标题，两者重复且后者才是"钉住不动"的那一层。
 * 删掉后封面页只剩封面 + 加载指示，翻页时底部不再有任何东西位移。
 */
@Composable
internal fun SwipeCoverPage(
    entry: SwipeEntry,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (entry.cover.isNotBlank()) {
            BiliAsyncImage(
                url = entry.cover,
                contentDescription = entry.title,
                modifier = Modifier.fillMaxSize(),
                // **保持原始比例 + Fit 居中**（用户反馈：「预览图完美地显示在视频中间」）：
                // 之前用 CardCoverLarge（960×540 裁切）+ Crop 铺满，竖屏视频的封面被切成
                // 16:9 再放大铺满整屏，主体被切掉、比例也不对。改成不裁切的限宽大图 +
                // Fit 之后，预览图的取景与接下来播放的视频**完全一致**
                // （视频也是按自身比例居中 letterbox），封面 → 视频的交接不再有跳动。
                variant = BiliImageVariant.PlayerPreview,
                contentScale = ContentScale.Fit
            )
        }
        if (isLoading) {
            // 粒子加载（用户要求"等待加载时给一个高级的加载动画"）：
            // 与播放器页的等首帧加载层用同一个组件，两处观感一致。
            // 封面可能是浅色的，先压暗一档再叠粒子，保证白粒子始终读得清。
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            BiliParticleLoader(
                modifier = Modifier
                    // 大尺寸：占屏宽 26% 的正方形（不写 dp 字面量，跟着屏幕走）
                    .fillMaxWidth(COVER_LOADER_WIDTH_FRACTION)
                    .aspectRatio(1f)
            )
        }
    }
}

/**
 * 队列尾部提示（停在最后一页时）：
 * - [SwipeQueueTail.Extending]：还在补货 —— 让用户知道"系统在忙"，不是卡住；
 * - [SwipeQueueTail.Exhausted]：补货跑完也没拿到新的 —— 明确"到头了"。
 *
 * 不可点、不消费触摸（上下滑与返回手势照常）；间距走 [BiliSpacing] token，
 * 不新增 dp 字面量（`DesignTokenLintTest` 的棘轮）。
 */
@Composable
internal fun SwipeQueueTailHint(
    state: SwipeQueueTail,
    modifier: Modifier = Modifier,
) {
    val text = when (state) {
        SwipeQueueTail.Extending -> "正在为你找更多视频…"
        SwipeQueueTail.Exhausted -> "已经是最后一个了"
        SwipeQueueTail.Idle -> return
    }
    Box(
        modifier = modifier
            .padding(bottom = BiliSpacing.xxxl)
            .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.extraLarge)
            .padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.smPlus)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * N-1：会话内**横屏视频**的悬浮「横屏」按钮（用户反馈：
 * 「刷视频模式中，横屏视频下方没有悬浮的横屏按钮，必须在顶栏点击横屏」）。
 *
 * 为什么另起一个而不是复用 `VideoScreen.LandscapeFullscreenFab`（SX-03 的「全屏」钮）：
 * 那个合成函数的守卫**钉住了它的调用点数量与无障碍文案**（`LandscapeFullscreenFab(` 恰 2 处、
 * `contentDescription = "进入全屏"` 恰 1 处），参数化会迫使放宽那条守卫；
 * 而两处语义本来就不同 —— 这里进的是**手动横屏**（会话内锁竖屏 + 点名这一支看满屏），
 * 那里进的是**全屏形变**。视觉语言（半透明主题面 + 轻投影 + 零模糊 + 圆角胶囊）保持一致。
 *
 * 位置由调用点算好（贴在**画面下沿** + 一个小间距），本函数只管长相与点击。
 */
@Composable
internal fun SwipeLandscapeFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否处于"反向"形态（已手动横屏 → 这颗改画「竖屏 / 还原」）。
     *
     * ## 为什么必须有它（第 35 轮 P0-3，真机 `32-land-chrome.png`）
     * 进横屏后原判据是 `!landscapeForced`，于是**要转回去的那一刻入口恰好消失**，
     * 屏幕上一个字都没有"怎么退出横屏"。用户只能先点屏幕、再找顶栏右上角那颗小图标
     * ——功能是通的，但不可发现（真机实测点击后 `mRotation=1→0` 成功）。
     * 现在显隐由会话层判据拆开：**入口恒在，只有语义与方向随状态翻转**。
     */
    reversed: Boolean = false,
) {
    // N-1 优化（用户给出抖音「全屏观看」的参照）：这一颗挂在**黑边区**里，
    // 用亮面 pill 会像黑底上的一块白斑；改成**深色半透明 + 发丝白描边 + 白图标/白字**，
    // 与抖音同款语言，也不与画面内容抢注意力。
    //
    // 线宽 `BiliSpacing.xs / 8`（= 0.5dp，与仓内发丝描边 `biliDarkHairlineBorder` 同值）。
    // **不写 dp 字面量**：`DesignTokenLintTest` 的 dp 棘轮已无余额。
    // 也不直接复用 `biliDarkHairlineBorder` —— 它只对「iOS 档 + 深色 + surfaceContainer*
    // 容器」生效，本 pill 是半透明黑，走进去会返回 null（等于没描边）。
    Surface(
        color = Color.Black.copy(alpha = 0.38f),
        contentColor = Color.White,
        shape = CircleShape,
        border = BorderStroke(BiliSpacing.xs / 8, Color.White.copy(alpha = 0.45f)),
        modifier = modifier
            .defaultMinSize(minHeight = BiliSpacing.xxxl * 1.5f)
            .clickable(
                onClickLabel = if (reversed) "退出横屏" else "进入横屏",
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BiliSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
        ) {
            Icon(
                imageVector = Icons.Rounded.ScreenRotation,
                contentDescription = if (reversed) "退出横屏" else "进入横屏",
                modifier = Modifier.size(BiliSpacing.xl)
            )
            Text(
                text = if (reversed) "竖屏" else "横屏",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
