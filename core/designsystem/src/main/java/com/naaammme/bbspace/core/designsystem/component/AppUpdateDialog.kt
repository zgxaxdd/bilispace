package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import com.naaammme.bbspace.core.designsystem.component.SelectableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@Immutable
data class AppUpdateDialogState(
    val title: String,
    val desc: String,
    val confirmText: String? = null,
    val url: String? = null
)

@Composable
fun AppUpdateDialog(
    state: AppUpdateDialogState,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            SelectableText(state.title)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
            ) {
                SelectableText(
                    text = state.desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val url = state.url
                    if (url == null) {
                        onDismiss()
                    } else {
                        onOpenUrl(url)
                    }
                },
                // 全局拖拽反馈（第 34 轮 T2）：对话框动作是纯浮层小件，
                // 不参与任何无缝过渡，也不在 PlayerView 祖先链上。
                modifier = Modifier.appElasticPress()
            ) {
                Text(state.confirmText ?: "知道了")
            }
        },
        dismissButton = if (state.url != null) {
            {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.appElasticPress()
                ) {
                    Text("关闭")
                }
            }
        } else {
            null
        }
    )
}
