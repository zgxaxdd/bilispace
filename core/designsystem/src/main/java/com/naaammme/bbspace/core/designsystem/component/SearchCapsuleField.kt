package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.appSearchElasticPress
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

@Composable
fun SearchCapsuleField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    showClearAction: Boolean = true,
    containerColor: Color? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val isIOS = LocalUiStyle.current == UiStyle.IOS
    // iOS：#767680 12% 灰底胶囊（onSurfaceVariant@12%）、10dp 圆角、左侧放大镜（方案 2.3）
    val resolvedContainer = containerColor ?: if (isIOS) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val shape = if (isIOS) RoundedCornerShape(10.dp) else MaterialTheme.shapes.large
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // ## 全局拖拽反馈（2026-10-18 第十九轮，**原语层**接入）
        //
        // 用 `appSearchElasticPress()`（strength 0.6 宽控件档）而不是满强度：
        // 搜索框实测宽 300dp+，满强度按下时整条会横移，读作"页面在晃"。
        //
        // **为什么给文本框挂也不抢手势**：`appElasticPress` 只在
        // `PointerEventPass.Final` 观察、从不消费事件；文本选区/光标拖动的
        // movement 会被 IME/选区识别器先消费（`isConsumed` ⇒ 立即停手），
        // 父级滚动同理。这正是上游把它用于 search capsule 的原因。
        //
        // `highlightShape` 传本框自身的圆角：默认 CircleShape 会把宽框的
        // 光斑裁成椭圆（见 appSearchElasticPress 的默认值）。
        modifier = modifier.appSearchElasticPress(highlightShape = shape),
        singleLine = true,
        shape = shape,
        placeholder = { Text(placeholder) },
        leadingIcon = if (isIOS) {
            {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            null
        },
        trailingIcon = {
            if (showClearAction && value.isNotEmpty()) {
                // 全局拖拽反馈（第 34 轮 T2）：搜索框尾部"清空"图标钮。
                // 输入框本体不挂（原生编辑手势必须独占），只挂这颗独立按钮。
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.appElasticPress()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空"
                    )
                }
            }
        },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = resolvedContainer,
            unfocusedContainerColor = resolvedContainer,
            disabledContainerColor = resolvedContainer,
            errorContainerColor = resolvedContainer
        )
    )
}
