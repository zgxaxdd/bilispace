package com.naaammme.bbspace.feature.video.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.BiliSheetGrabber
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.biliSheetShape
import com.naaammme.bbspace.feature.comment.CommentPanel
import com.naaammme.bbspace.feature.video.VideoViewModel
import kotlin.math.roundToInt

/**
 * 刷视频会话的**常驻 chrome**（2026-10-19 第 34 轮拆分自 `PortraitSwipePager.kt`）。
 *
 * 本文件收的都是"挂在 pager 之上、按会话状态显隐"的整块 UI：
 * 底部信息层 / 互动栏 / 评论悬浮卡 / 收藏面板。
 *
 * ## 为什么它们值得单独成文件
 * 这些块**每个都有自己的显隐判据与观感理由**（光只洒黑边、评论不要模糊、
 * 收藏是短表单所以用 sheet……），而主函数只需要知道"在什么条件下摆哪一块"。
 * 混在一起时主函数 1114 行，读的人必须先穿过几百行观感注释才能看到接线。
 *
 * 拆分原则：**本文件只管"长什么样 + 什么时候显示"**，不持有任何 effect；
 * 所有开合状态由 [PortraitSwipePager] 传入。
 */

/**
 * 会话级常驻**底部信息层**（@名 / 标题 / 话题 / 合集）。
 *
 * 与互动栏同一条决策：底部 chrome 曾长在 pane 里（pane 每页一个）→ 翻页时跟着
 * 页面位移；提升为会话级单例后原地换数据、不随页面位移，且只组合一份（省重组）。
 * **无门控**（2026-10-02 二次修正）：曾写 `if (!waitingFirstFrame)`，而翻页 veil
 * 每次亮 ~0.5s → 整块"消失再出现"，与互动栏同款毛病。常驻件就该常驻。
 *
 * 需求②：**横屏观影时清屏** —— 整块让位，画面上不剩任何常驻 UI。
 *
 * ## 2026-10-18 第 33 轮：整条底部渐变遮罩删除（已交付，勿回退）
 * 旧结构是「Box(38% 屏高) → 内层 Box(matchParentSize).background(SwipeBottomScrimBrush)」。
 * 现在外层 Box 只保留定位与防烧屏，可读性由信息层每行文字的**投影**承担。
 *
 * 参数刻意用**扁平原始值**（而不是 `VideoDetail`）：本组件只需要标题/UP/标签/合集
 * 这几项，传整个 detail 会把"信息层到底依赖哪些字段"藏起来。
 */
@Composable
internal fun SwipeSessionBottomZone(
    title: String?,
    owner: String?,
    tags: List<String>,
    seasonTitle: String?,
    staffs: List<com.naaammme.bbspace.core.model.VideoStaff>,
    resetKey: Any?,
    chromeBurnIn: SwipeChromeBurnIn,
    visible: Boolean,
    onOpenSpace: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(SWIPE_BOTTOM_ZONE_FRACTION)
            .applyBurnIn(chromeBurnIn)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
        ) {
            SwipeSessionInfoLine(
                title = title,
                owner = owner,
                tags = tags,
                seasonTitle = seasonTitle,
                staffs = staffs,
                onOpenSpace = onOpenSpace,
                resetKey = resetKey,
                // 手势条 clearance 由信息层自己承担（见常量注释）。
                modifier = Modifier.padding(bottom = SwipeBottomInfoInset)
            )
            // 底部**细进度条 / 可拖 seek** 已按用户决定移除（2026-10-02：
            // 「把底部细进度条这个功能删掉，我不需要」）——恢复时需连同判据与守卫一起恢复。
        }
    }
}

/**
 * 评论：**悬浮卡片**（用户明确要求：不要占满屏的弹层）。
 *
 * 为什么不是 `ModalBottomSheet`：连播里弹层会把整屏盖住、只剩一条评论列表，
 * 用户失去"我在看哪支视频"的上下文。悬浮卡片贴底、底下视频仍在，
 * 点卡片外任意处关闭 —— 与短视频应用的评论浮层一致。
 *
 * 观感与性能定稿（v5~v7）：
 * - 主题实底 → **alpha 半透明**（决策 D-2：只用透明、不要模糊，保证流畅度），
 *   卡片透出背后的视频，"悬浮"感成立；
 * - **不引入任何模糊**：`hazeBlur(` / `drawBackdrop(` 一个都不加（离屏模糊是每帧成本）；
 * - 进出走 `AnimatedVisibility` + LocalAnimations token，不新增 animate*AsState；
 * - `reduceMotion`（动效速度=关）：token 时长已压到 ~1ms，自然退化成瞬时到位。
 */
@Composable
internal fun SwipeCommentCard(
    visible: Boolean,
    viewModel: VideoViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animations = com.naaammme.bbspace.core.designsystem.theme.LocalAnimations.current
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(animations.medium, easing = animations.standard)),
            exit = fadeOut(tween(animations.medium, easing = animations.standard))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = COMMENT_SCRIM_ALPHA))
                    .clickable(onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = tween(animations.long, easing = animations.emphasized),
                // 从下方 1/3 高度滑入：读作"卡片升起来"，而不是整屏跳一下
                initialOffsetY = { it / 3 }
            ) + fadeIn(tween(animations.long, easing = animations.emphasized)),
            exit = slideOutVertically(
                animationSpec = tween(animations.medium, easing = animations.standard),
                targetOffsetY = { it / 3 }
            ) + fadeOut(tween(animations.medium, easing = animations.standard)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val commentCardShape = remember {
                RoundedCornerShape(
                    topStart = BiliRadius.sheetTop,
                    topEnd = BiliRadius.sheetTop
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = COMMENT_CARD_ALPHA),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = commentCardShape,
                shadowElevation = COMMENT_CARD_ELEVATION,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(COMMENT_CARD_HEIGHT_FRACTION)
            ) {
                CommentPanel(
                    subject = viewModel.commentSubject,
                    isActive = true,
                    contentPadding = PaddingValues(
                        horizontal = BiliSpacing.md,
                        vertical = BiliSpacing.sm
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * 会话内收藏：**底部面板**，不是评论那样的悬浮卡。
 *
 * 区别在于交互性质：评论要"边看边刷"（盖住画面就失去上下文），而收藏夹是一次
 * "选完即提交"的短表单 —— 与详情页同一形态，用户不必学两种样式。
 *
 * 形状/拉手/遮罩三件套走同一出口（[biliSheetShape] / [BiliSheetGrabber]），
 * 免得会话里的弹层与详情页差一个圆角半径。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeFavSheet(
    visible: Boolean,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = biliSheetShape(),
        scrimColor = Color.Black.copy(alpha = 0.40f),
        dragHandle = { BiliSheetGrabber() }
    ) {
        content()
    }
}

/** 评论悬浮卡片高度占屏比：留出上方约 1/3 屏，视频仍然可见。 */
internal const val COMMENT_CARD_HEIGHT_FRACTION = 0.68f

/** 卡片外遮罩：够暗以突出卡片，又不至于让视频"消失"。 */
internal const val COMMENT_SCRIM_ALPHA = 0.42f

/**
 * 评论悬浮卡片的底色 alpha（决策 D-2：**只用透明、不要模糊**）。
 *
 * 取 0.72 与 `HomeLivePage` 的半透明 surface 同档：卡片能透出背后视频，
 * 而卡片下还压着 [COMMENT_SCRIM_ALPHA] 的遮罩 ⇒ 正文对比度仍可接受。
 */
internal const val COMMENT_CARD_ALPHA = 0.72f

/** 卡片阴影高度（4dp 档：浮起但不"飘"）。 */
internal val COMMENT_CARD_ELEVATION = 8.dp

