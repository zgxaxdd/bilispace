package com.naaammme.bbspace.core.designsystem.component

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.max
import kotlin.math.min

private fun popupPositionProvider(offsetPx: Int, selectionTopYPx: Int) = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(0, windowSize.width - popupContentSize.width)
        val selectionScreenY = anchorBounds.top + selectionTopYPx
        val y = (selectionScreenY - popupContentSize.height - offsetPx)
            .coerceIn(0, windowSize.height - popupContentSize.height)
        return IntOffset(x, y)
    }
}

private data class SelectionRange(val start: Int, val end: Int)

@Composable
fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    var selection by remember { mutableStateOf<SelectionRange?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val popupOffsetPx = remember(density) { with(density) { 8.dp.roundToPx() } }
    val selectionLineTopPx = remember(selection, textLayoutResult) {
        val sel = selection ?: return@remember 0
        val layout = textLayoutResult ?: return@remember 0
        val start = min(sel.start, sel.end).coerceIn(0, text.length)
        if (text.isNotEmpty() && start < text.length) {
            layout.getLineTop(layout.getLineForOffset(start)).toInt()
        } else 0
    }
    val positionProvider = remember(popupOffsetPx, selectionLineTopPx) {
        popupPositionProvider(popupOffsetPx, selectionLineTopPx)
    }

    /**
     * 选区背景路径（BACKLOG PF-05）。
     *
     * `TextLayoutResult.getPathForRange` 只有"返回一个新 Path"的重载 —— 用 javap 查了
     * ui-text 1.11.2 与 1.12.1，都只有 `getPathForRange(int, int): Path`，
     * 没有可以复用的 `dest: Path` 版本，所以"选区变了就分配一次"省不掉。
     *
     * 能省的是**重绘但选区没变**的那些分配：原先这段在 `drawBehind` 里，
     * 于是任何一次重绘（列表滚动、父级动画、别处状态失效）都会新分配一个 Path，
     * 而拖动选区只是其中少数时刻。提到带 key 的 `remember` 之后，
     * 只有 layout / 选区 / 文本真的变了才重新算 —— 也顺手把这条分配从
     * "DrawScope 里"挪到"组合期"，让它能被本仓的分配守卫看见（DrawScope 里的分配
     * 结构上扫不到，因为它不是组合体）。
     */
    val selectionPath = remember(textLayoutResult, selection, text.length) {
        val layout = textLayoutResult
        val sel = selection
        if (layout == null || sel == null) {
            null
        } else {
            val from = min(sel.start, sel.end).coerceIn(0, text.length)
            val to = max(sel.start, sel.end).coerceIn(0, text.length)
            if (from < to) layout.getPathForRange(from, to) else null
        }
    }

    Box(modifier = modifier) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { textLayoutResult = it },
            modifier = Modifier
                .drawBehind {
                    // 路径已在组合期按 key 算好；这里只画，不再分配
                    val path = selectionPath ?: return@drawBehind
                    drawPath(path, color = selectionColor)
                }
                .pointerInput(text) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset: Offset ->
                            val layout = textLayoutResult ?: return@detectDragGesturesAfterLongPress
                            val charOffset = layout.getOffsetForPosition(offset)
                            if (charOffset in 0..text.length) {
                                selection = SelectionRange(charOffset, charOffset)
                                isDragging = true
                            }
                        },
                        onDrag = { change, _ ->
                            val layout = textLayoutResult ?: return@detectDragGesturesAfterLongPress
                            val endOffset = layout.getOffsetForPosition(change.position).coerceIn(0, text.length)
                            selection = selection?.copy(end = endOffset)
                            change.consume()
                        },
                        onDragEnd = {
                            isDragging = false
                            val sel = selection
                            if (sel != null && sel.start == sel.end) {
                                selection = SelectionRange(0, text.length)
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            selection = null
                        }
                    )
                }
        )

        val sel = selection
        if (sel != null && !isDragging) {
            val start = min(sel.start, sel.end).coerceIn(0, text.length)
            val end = max(sel.start, sel.end).coerceIn(0, text.length)

            if (start < end) {
                val selectedText = text.substring(start, end)
                Popup(
                    popupPositionProvider = positionProvider,
                    onDismissRequest = { selection = null }
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 4.dp
                    ) {
                        TextButton(
                            onClick = {
                                selection = null
                                if (selectedText.isNotBlank()) {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipData.newPlainText(null, selectedText).toClipEntry()
                                        )
                                    }
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                }
                            },
                            // 全局拖拽反馈（第 34 轮 T2）：选区浮动菜单里的"复制"
                            // 是纯浮层小件，不参与过渡、不在播放器祖先链上。
                            //
                            // ⚠️ 只挂这一颗，**不挂**文本选区本体：选区拖动必须由
                            // 原生选择器独占（`appElasticPress` 虽不消费手势，但跟手
                            // 位移会让选区边界与手指错位）。
                            modifier = Modifier.appElasticPress()
                        ) {
                            Text("复制")
                        }
                    }
                }
            }
        }
    }
}