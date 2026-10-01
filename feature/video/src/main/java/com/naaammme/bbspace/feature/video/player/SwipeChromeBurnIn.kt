package com.naaammme.bbspace.feature.video.player

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier

/**
 * 会话常驻控件的**防烧屏**呈现参数（用户要求，2026-10-01）。
 *
 * 由 [PortraitSwipePager]（会话级、唯一一处计时）算好后经本 CompositionLocal 下发，
 * pane 内的常驻件（底部信息层、常驻细进度条）在**同一个值**上做变暗 + 漂移——
 * 一处计时、多处读值，不做第二个计时器（与"覆盖层是纯参数组件"的既有手法一致）。
 *
 * 只影响**常驻**件：控制条（showCtrl）本来就会自动隐藏，不参与。
 */
data class SwipeChromeBurnIn(
    /** 常驻层整体透明度（静置变暗；1f = 满亮）。 */
    val alpha: Float = 1f,
    /** 漂移位移（像素）：低于视觉阈值，仅让像素不长期压在同一发光点。 */
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 0f,
)

/**
 * 常驻件统一的绘制变换：读值全在 graphicsLayer（零重组、不改布局）。
 * 顶层扩展（不是成员扩展）：调用点写作 `Modifier.applyBurnIn(burnIn)`。
 */
fun Modifier.applyBurnIn(burnIn: SwipeChromeBurnIn): Modifier = graphicsLayer {
    alpha = burnIn.alpha
    translationX = burnIn.offsetXPx
    translationY = burnIn.offsetYPx
}

/** 默认"不变暗不漂移"：非会话场景（详情页内嵌播放器）读到的就是它。 */
val LocalSwipeChromeBurnIn = compositionLocalOf { SwipeChromeBurnIn() }
