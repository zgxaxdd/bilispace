package com.naaammme.bbspace.feature.auth.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.LoginState
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit = {},
    onBack: () -> Unit = {},
    onSwitchToSms: () -> Unit = {}
) {
    val loginState by viewModel.loginState.collectAsStateWithLifecycle()
    val qrBitmap by viewModel.qrBitmap.collectAsStateWithLifecycle()

    LaunchedEffect(loginState) {
        if (loginState is LoginState.QrSuccess) {
            onLoginSuccess()
        }
    }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text("登录") },
                navigationIcon = {
                    IconButton(modifier = Modifier.appElasticPress(), onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(BiliSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = loginState) {
                is LoginState.Idle, is LoginState.Loading -> {
                    LoadingContent()
                }
                is LoginState.QrCodeReady -> {
                    QrCodeContent(qrBitmap = qrBitmap)
                }
                is LoginState.Scanned -> {
                    ScannedContent()
                }
                is LoginState.QrSuccess -> {
                    QrSuccessContent()
                }
                is LoginState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = { viewModel.getQrCode() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(BiliSpacing.xxl))

            Text(
                text = "此登录方式暂时仅用于获取 HD 版 access_key，不用于账号登录。如需登录请使用手机号登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = BiliSpacing.lg)
            )

            Spacer(modifier = Modifier.height(BiliSpacing.sm))

            TextButton(modifier = Modifier.appElasticPress(), onClick = onSwitchToSms) {
                Text("手机号登录")
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(BiliSpacing.xxxl)
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(BiliSpacing.lg))
        Text("正在获取二维码...")
    }
}

@Composable
private fun QrCodeContent(qrBitmap: Bitmap?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(BiliSpacing.lg)
    ) {
        Card(
            modifier = Modifier.size(300.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        Spacer(modifier = Modifier.height(BiliSpacing.lg))
        Text(
            text = "请使用 B 站 APP 扫描二维码",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(BiliSpacing.sm))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(BiliSpacing.sm))
        Text(
            text = "等待扫码...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScannedContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(BiliSpacing.xxxl)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(BiliSpacing.xxl))
        Text(
            text = "已扫码",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(BiliSpacing.sm))
        Text(
            text = "请在手机上确认登录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(BiliSpacing.lg))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun QrSuccessContent() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(BiliSpacing.lg)
    ) {
        Column(
            modifier = Modifier.padding(BiliSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "HD key 绑定成功",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(BiliSpacing.sm))
            Text(
                text = "已保存当前账号的 HD 鉴权 key",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(BiliSpacing.xxxl)
    ) {
        Text(
            text = "❌",
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(modifier = Modifier.height(BiliSpacing.lg))
        Text(
            text = "登录失败",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(BiliSpacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(BiliSpacing.xxl))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().appElasticPress()
        ) {
            Text("重试")
        }
    }
}

