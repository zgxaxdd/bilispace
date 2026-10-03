package com.naaammme.bbspace.feature.settings

import com.naaammme.bbspace.feature.settings.navigation.APPEARANCE_ROUTE
import com.naaammme.bbspace.feature.settings.navigation.BLOCKED_UP_ROUTE
import com.naaammme.bbspace.feature.settings.navigation.FEED_SETTINGS_ROUTE
import com.naaammme.bbspace.feature.settings.navigation.PERFORMANCE_ROUTE
import com.naaammme.bbspace.feature.settings.navigation.AUDIO_VIDEO_ROUTE
import com.naaammme.bbspace.feature.settings.navigation.PRIVACY_ROUTE
import com.naaammme.bbspace.feature.settings.navigation.SPONSOR_BLOCK_ROUTE
import com.naaammme.bbspace.feature.settings.navigation.SWIPE_QOE_ROUTE

data class SettingEntry(val title: String, val subtitle: String, val route: String)

val appearanceEntries = listOf(
    SettingEntry("主题模式", "浅色 深色 或跟随系统", APPEARANCE_ROUTE),
    SettingEntry("动态取色", "Android 12 以上从壁纸提取颜色", APPEARANCE_ROUTE),
    SettingEntry("反转颜色", "交换页面背景和低饱和容器色", APPEARANCE_ROUTE),
    SettingEntry("主题色", "选择应用主题颜色", APPEARANCE_ROUTE),
    SettingEntry("纯色背景", "深色纯黑 浅色纯白", APPEARANCE_ROUTE),
    SettingEntry("字体大小", "调整应用字体大小", APPEARANCE_ROUTE),
    SettingEntry("动画速度", "调整界面动画速度", APPEARANCE_ROUTE),
    SettingEntry("过渡动画", "调整二级页面切换动画（视频页开合与底栏 Tab 切换不在此列）", APPEARANCE_ROUTE),
    SettingEntry("圆角风格", "调整界面圆角风格", APPEARANCE_ROUTE),
)

val performanceEntries = listOf(
    SettingEntry("使用系统 DNS", "重启后生效 关闭后使用 HTTPDNS", PERFORMANCE_ROUTE),
    SettingEntry("屏幕刷新率", "设置应用渲染帧率上限", PERFORMANCE_ROUTE),
)

val audioVideoEntries = listOf(
    SettingEntry("默认画质", "设置默认视频和音频质量", AUDIO_VIDEO_ROUTE),
    SettingEntry("编码格式", "选择优先的编码格式", AUDIO_VIDEO_ROUTE),
    SettingEntry("强制 HTTPS", "使用 HTTPS 播放地址", AUDIO_VIDEO_ROUTE),
)

val feedEntries = listOf(
    SettingEntry("首页布局", "单列大图或双列网格", FEED_SETTINGS_ROUTE),
    SettingEntry("HD 推荐模式", "切换 HD 推荐接口", FEED_SETTINGS_ROUTE),
    SettingEntry("个性化推荐", "基于历史记录推荐内容", FEED_SETTINGS_ROUTE),
    SettingEntry("课堂推荐模式", "只推荐学习相关视频", FEED_SETTINGS_ROUTE),
    SettingEntry("未成年推荐", "按指定年龄请求未成年推荐内容", FEED_SETTINGS_ROUTE),
)

val privacyEntries = listOf(
    SettingEntry("禁止 Gaia 上报", "阻止应用列表风控数据上报", PRIVACY_ROUTE),
    // NX-08：拉黑名单（搜索索引；实际入口在隐私页内）
    SettingEntry("拉黑名单", "查看并撤销在刷视频页拉黑的 UP 主", BLOCKED_UP_ROUTE),
)

val sponsorBlockEntries = listOf(
    SettingEntry("启用空降助手", "自动或手动跳过恰饭 片头片尾片段", SPONSOR_BLOCK_ROUTE),
    SettingEntry("片段分类管理", "按分类设置跳过策略", SPONSOR_BLOCK_ROUTE),
    SettingEntry("进度条标记", "在进度条上标注片段区间", SPONSOR_BLOCK_ROUTE),
    SettingEntry("跳过提示", "跳过后显示提示信息", SPONSOR_BLOCK_ROUTE),
    SettingEntry("空降助手服务器", "社区片段数据来源地址", SPONSOR_BLOCK_ROUTE),
)

val swipeQoeEntries = listOf(
    SettingEntry("刷视频诊断", "本机首帧/翻页/补货指标，不上传", SWIPE_QOE_ROUTE),
)

val allSettingEntries =
    appearanceEntries + performanceEntries + audioVideoEntries + feedEntries + privacyEntries +
        sponsorBlockEntries + swipeQoeEntries
