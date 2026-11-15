package com.naaammme.bbspace.feature.user.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@Composable
fun AccountExpiredDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账号已过期") },
        text = { Text("当前账号已经过期，请务必删除此账号!!!") },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                // 全局拖拽反馈（第 34 轮 T2）：对话框动作（浮层小件）。
                modifier = Modifier.appElasticPress()
            ) {
                Text("知道了")
            }
        }
    )
}
