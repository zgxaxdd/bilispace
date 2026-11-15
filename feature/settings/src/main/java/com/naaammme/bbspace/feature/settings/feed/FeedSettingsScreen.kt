package com.naaammme.bbspace.feature.settings.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.CollapsingTopBarScaffold
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.settings.FeedApiType
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixIconButton
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixSwitch
import com.naaammme.bbspace.feature.settings.SettingsViewModel
import kotlin.math.roundToInt
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedSettingsScreen(
    onBack: () -> Unit,
    onNavigateToInterest: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val hdFeed by viewModel.hdFeed.collectAsStateWithLifecycle()
    val hdFeedAvailable by viewModel.hdFeedAvailable.collectAsStateWithLifecycle()
    val personalizedRcmd by viewModel.personalizedRcmd.collectAsStateWithLifecycle()
    val lessonsMode by viewModel.lessonsMode.collectAsStateWithLifecycle()
    val teenagersMode by viewModel.teenagersMode.collectAsStateWithLifecycle()
    val teenagersAge by viewModel.teenagersAge.collectAsStateWithLifecycle()
    val homeSingleColumn by viewModel.homeFeedSingleColumn.collectAsStateWithLifecycle()
    val feedApiType by viewModel.feedApiType.collectAsStateWithLifecycle()
    var teenagersAgeDraft by remember(teenagersAge) { mutableFloatStateOf(teenagersAge.toFloat()) }
    val teenagersAgeText = teenagersAgeDraft.roundToInt().coerceIn(1, 17).toString()

    LaunchedEffect(Unit) {
        viewModel.refreshHdFeedAvailable()
    }

    CollapsingTopBarScaffold(
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text("推荐设置") },
                navigationIcon = {
                    AppMiuixIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(BiliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup()
                            .padding(BiliSpacing.lg)
                    ) {
                        Text("推荐来源", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(BiliSpacing.xs))
                        Text(
                            "选择首页推荐取流方式；切换后首页会自动重新加载。默认为手机端推荐（与旧版一致）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(BiliSpacing.sm))
                        FeedApiTypeOption(
                            title = "手机端推荐（默认）",
                            description = "B 站 App 推荐算法；内容最全，含直播卡 / 番剧卡 / 课堂卡等，登录后更准确",
                            selected = feedApiType == FeedApiType.MOBILE,
                            onSelect = { viewModel.updateFeedApiType(FeedApiType.MOBILE) }
                        )
                        FeedApiTypeOption(
                            title = "网页端推荐",
                            description = "B 站网页版推荐算法；只返回普通视频（没有直播 / 番剧 / 课堂卡），" +
                                "且该档下卡片的「不感兴趣」不可用",
                            selected = feedApiType == FeedApiType.WEB,
                            onSelect = { viewModel.updateFeedApiType(FeedApiType.WEB) }
                        )
                        FeedApiTypeOption(
                            title = "双源合并",
                            description = "同时请求手机端与网页端，交错合并去重；单边失败自动返回另一边（可能更费流量）",
                            selected = feedApiType == FeedApiType.MERGED,
                            onSelect = { viewModel.updateFeedApiType(FeedApiType.MERGED) }
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BiliSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("首页布局", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(BiliSpacing.xs))
                            Text(
                                "开启后首页使用单列大图，关闭为双列网格；也可在首页顶栏切换",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppMiuixSwitch(
                            checked = homeSingleColumn,
                            onCheckedChange = viewModel::updateHomeFeedSingleColumn
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BiliSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("HD 推荐模式", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(BiliSpacing.xs))
                            Text(
                                if (hdFeedAvailable) {
                                    "切换 HD 推荐接口，每页返回更多条目"
                                } else {
                                    "需先扫码绑定当前账号 HD key"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppMiuixSwitch(
                            checked = hdFeed,
                            enabled = hdFeedAvailable,
                            onCheckedChange = viewModel::updateHdFeed
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BiliSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("个性化推荐", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(BiliSpacing.xs))
                            Text(
                                "基于观看历史推荐内容，关闭后随机推荐",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppMiuixSwitch(
                            checked = personalizedRcmd,
                            onCheckedChange = viewModel::updatePersonalizedRcmd
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BiliSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("课堂推荐模式", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(BiliSpacing.xs))
                            Text(
                                "只推荐学习相关视频",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppMiuixSwitch(
                            checked = lessonsMode,
                            onCheckedChange = viewModel::updateLessonsMode
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BiliSpacing.lg)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("未成年推荐", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(BiliSpacing.xs))
                                Text(
                                    "按指定年龄请求未成年推荐内容",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            AppMiuixSwitch(
                                checked = teenagersMode,
                                onCheckedChange = viewModel::updateTeenagersMode
                            )
                        }
                        Spacer(Modifier.height(BiliSpacing.md))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "年龄",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (teenagersMode) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                teenagersAgeText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (teenagersMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Slider(
                            value = teenagersAgeDraft,
                            onValueChange = { teenagersAgeDraft = it },
                            onValueChangeFinished = {
                                viewModel.updateTeenagersAge(teenagersAgeDraft.roundToInt())
                            },
                            enabled = teenagersMode,
                            valueRange = 1f..17f,
                            steps = 15
                        )
                    }
                }
            }
            item {
                Card(
                    onClick = onNavigateToInterest,
                    modifier = Modifier.fillMaxWidth().appElasticPress()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BiliSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("内容偏好调节", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(BiliSpacing.xs))
                            Text(
                                "查看和调整你的内容兴趣标签",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 推荐来源单选行（PT-10 §4.6）。
 *
 * 用 [selectable] + [Role.RadioButton] 而不是 `Modifier.clickable`：整行可点、无障碍语义正确，
 * 且与 [RadioButton] 共用同一份 [selected]（`onClick = null` 避免重复点击区域）。
 */
@Composable
private fun FeedApiTypeOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .padding(vertical = BiliSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(BiliSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
