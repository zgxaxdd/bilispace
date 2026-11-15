package com.naaammme.bbspace.feature.auth.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.IOSAlertDialog
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.model.LoginCredential
import com.naaammme.bbspace.core.model.User
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixActionTone
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixButton
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    viewModel: AccountViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onAddAccount: () -> Unit = {},
    onSwitched: () -> Unit = {}
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val currentMid by viewModel.currentMid.collectAsStateWithLifecycle()
    val userInfoMap by viewModel.userInfoMap.collectAsStateWithLifecycle()
    var pendingRemoveAccount by remember { mutableStateOf<LoginCredential?>(null) }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text("账号管理") },
                navigationIcon = {
                    IconButton(modifier = Modifier.appElasticPress(), onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(BiliSpacing.lg)
        ) {
            accounts.forEach { account ->
                    AccountCard(
                        account = account,
                        isCurrent = account.mid == currentMid,
                        userInfo = userInfoMap[account.mid],
                        onSwitch = { viewModel.switchAccount(account.mid); onSwitched() },
                        onRemove = { pendingRemoveAccount = account },
                        onLogout = { pendingRemoveAccount = account }
                    )
                    Spacer(modifier = Modifier.height(BiliSpacing.sm))
                }

                GuestCard(
                    isCurrent = currentMid == 0L,
                    onSwitch = { viewModel.switchToGuest(); onSwitched() }
                )

            Spacer(modifier = Modifier.height(BiliSpacing.lg))

            Button(
                onClick = onAddAccount,
                modifier = Modifier.fillMaxWidth().appElasticPress()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(BiliSpacing.sm))
                Text("添加账号")
            }
        }
    }

    pendingRemoveAccount?.let { account ->
        val isCurrent = account.mid == currentMid
        val confirm = {
            pendingRemoveAccount = null
            if (isCurrent) {
                viewModel.logout(account)
            } else {
                viewModel.removeAccount(account.mid)
            }
        }
        val cancel = { pendingRemoveAccount = null }
        if (LocalUiStyle.current == UiStyle.IOS) {
            // iOS：居中警告框（270dp/14dp 圆角/44dp 按钮/发丝分隔线），
            // 破坏性动作走 systemRed。MATERIAL 档保留 M3 AlertDialog。
            IOSAlertDialog(
                onDismiss = cancel,
                title = if (isCurrent) "退出当前账号" else "删除账号",
                message = if (isCurrent) "确认退出并删除当前账号吗？" else "确认删除这个账号吗？",
                confirmLabel = "确认",
                dismissLabel = "取消",
                destructive = true,
                onConfirm = confirm,
                onDismissAction = cancel
            )
        } else {
            AlertDialog(
                onDismissRequest = cancel,
                title = { Text(if (isCurrent) "退出当前账号" else "删除账号") },
                text = {
                    Text(
                        if (isCurrent) {
                            "确认退出并删除当前账号吗？"
                        } else {
                            "确认删除这个账号吗？"
                        }
                    )
                },
                confirmButton = {
                    // 2026-10-18（W9）：破坏性确认走 `AppMiuixActionTone.Danger`。
                    // 依据不是"想用一下 Danger"：同一弹窗的 **iOS 分支上方就写着 `destructive = true`**
                    // （本仓对"退出/删除账号"的既有定性就是破坏性），而 Material 分支此前是普通
                    // `TextButton` —— 两档语义不一致。这里只换渲染层，`onClick = confirm` 与
                    // 二次确认流程逐字未动。
                    AppMiuixButton(
                        text = "确认",
                        onClick = confirm,
                        tone = AppMiuixActionTone.Danger
                    )
                },
                dismissButton = {
                    TextButton(modifier = Modifier.appElasticPress(), onClick = cancel) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
private fun GuestCard(
    isCurrent: Boolean,
    onSwitch: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isCurrent) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(
                url = null,
                contentDescription = "游客",
                modifier = Modifier.size(56.dp),
                fallbackContent = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
            Spacer(modifier = Modifier.width(BiliSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "游客",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (isCurrent) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (!isCurrent) {
                OutlinedButton(
                    onClick = onSwitch,
                    modifier = Modifier.padding(end = BiliSpacing.xs).appElasticPress()
                ) {
                    Text("切换", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

//@Composable
//private fun EmptyState() {
//    Box(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 48.dp),
//        contentAlignment = Alignment.Center
//    ) {
//        Column(horizontalAlignment = Alignment.CenterHorizontally) {
//            Icon(
//                Icons.Default.Person,
//                contentDescription = null,
//                modifier = Modifier.size(64.dp),
//                tint = MaterialTheme.colorScheme.onSurfaceVariant
//            )
//          Spacer(modifier = Modifier.height(12.dp))
//            Text(
//                text = "暂无账号",
//                style = MaterialTheme.typography.bodyLarge,
//                color = MaterialTheme.colorScheme.onSurfaceVariant
//            )
//      }
//    }
//}

@Composable
private fun AccountCard(
    account: LoginCredential,
    isCurrent: Boolean,
    userInfo: User?,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
    onLogout: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isCurrent) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BiliSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            AvatarImage(
                url = userInfo?.avatar?.takeIf(String::isNotBlank),
                contentDescription = userInfo?.name ?: "UID: ${account.mid}",
                modifier = Modifier.size(56.dp),
                fallbackContent = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            Spacer(modifier = Modifier.width(BiliSpacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
                ) {
                    Text(
                        text = userInfo?.name ?: "UID: ${account.mid}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isCurrent) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (userInfo != null) {
                    Text(
                        text = "Lv${userInfo.level}  硬币 ${userInfo.coins.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (userInfo.sign.isNotEmpty()) {
                        Text(
                            text = userInfo.sign,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = "UID: ${account.mid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 操作按钮
            if (!isCurrent) {
                OutlinedButton(
                    onClick = onSwitch,
                    modifier = Modifier.padding(end = BiliSpacing.xs).appElasticPress()
                ) {
                    Text("切换", style = MaterialTheme.typography.labelSmall)
                }
            }

            IconButton(modifier = Modifier.appElasticPress(), onClick = if (isCurrent) onLogout else onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = if (isCurrent) "退出登录" else "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
