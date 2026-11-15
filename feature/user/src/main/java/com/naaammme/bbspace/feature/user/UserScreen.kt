package com.naaammme.bbspace.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.feature.user.collage.rememberUserCollageState
import com.naaammme.bbspace.feature.user.collage.UserCollageSection
import com.naaammme.bbspace.feature.user.collage.rememberUserCollagePalette
import com.naaammme.bbspace.feature.user.collage.rememberUserCollageBackgroundBrush
import com.naaammme.bbspace.feature.user.component.AccountExpiredDialog

import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
enum class UserDest {
    History,
    Favorite,
    WatchLater
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToBbSpace: () -> Unit,
    onNavigate: (UserDest) -> Unit,
    onNavigateToDownload: () -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit = {},
    vm: UserViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val palette = rememberUserCollagePalette()
    val backgroundBrush = rememberUserCollageBackgroundBrush(palette)
    val collageState = rememberUserCollageState()

    Scaffold(
        modifier = Modifier.background(brush = backgroundBrush),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("我的") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                actions = {
                    // 全局拖拽反馈（第 34 轮 T2）："我的"顶栏三颗图标钮。
                    // 三者共处一个 actions Row、命中区同为 M3 IconButton 的 48dp
                    // ⇒ 天生等高，这里只需保证手感一致（同一个挂载）。
                    IconButton(
                        onClick = collageState::reset,
                        modifier = Modifier.appElasticPress()
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "重置布局"
                        )
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.appElasticPress()
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置"
                        )
                    }
                    IconButton(
                        onClick = onNavigateToAccount,
                        modifier = Modifier.appElasticPress()
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "账号管理"
                        )
                    }
                }
            )
        }
    ) { padding ->
        UserCollageSection(
            user = state.user,
            collageOffsets = collageState.offsets,
            onCollageOffsetChange = collageState::updateOffset,
            onOpenSpace = onOpenSpace,
            onNavigateToBbSpace = onNavigateToBbSpace,
            onNavigate = onNavigate,
            onNavigateToDownload = onNavigateToDownload,
            palette = palette,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md)
        )
    }

    if (state.showAccountExpiredDialog) {
        AccountExpiredDialog(onDismiss = vm::dismissAccountExpiredDialog)
    }
}
