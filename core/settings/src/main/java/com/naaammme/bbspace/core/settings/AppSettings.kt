package com.naaammme.bbspace.core.settings

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.designsystem.theme.AnimationSpeed
import com.naaammme.bbspace.core.designsystem.theme.CornerStyle
import com.naaammme.bbspace.core.designsystem.theme.DEFAULT_PULL_REFRESH_DISTANCE_DP
import com.naaammme.bbspace.core.designsystem.theme.FrameRateMode
import com.naaammme.bbspace.core.designsystem.theme.PaletteStyle
import com.naaammme.bbspace.core.designsystem.theme.ThemeConfig
import com.naaammme.bbspace.core.designsystem.theme.ThemeMode
import com.naaammme.bbspace.core.designsystem.theme.TransitionStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.model.DanmakuConfig
import com.naaammme.bbspace.core.model.PlayerBufferProfile
import com.naaammme.bbspace.core.model.PlayerBufferSettings
import com.naaammme.bbspace.core.model.PlayerPlaybackPrefs
import com.naaammme.bbspace.core.model.PlayerRepeatMode
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.core.model.VideoCdnMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettings @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val defaultThemeConfig = ThemeConfig()
    private val defaultPlayerSettings = PlayerSettingsState()
    private val defaultBufferSettings = defaultPlayerSettings.buffer
    private val defaultPlaybackPrefs = defaultPlayerSettings.playback
    private val defaultDanmakuConfig = defaultPlayerSettings.danmaku

    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val seedColorKey = intPreferencesKey("seed_color")
    private val useDynamicColorKey = booleanPreferencesKey("use_dynamic_color")
    private val paletteStyleKey = stringPreferencesKey("palette_style")
    private val swapBaseColorsKey = booleanPreferencesKey("swap_base_colors")
    private val fontScaleKey = floatPreferencesKey("font_scale")
    private val uiScaleKey = floatPreferencesKey("ui_scale")
    private val roundScreenSafePaddingScaleKey = floatPreferencesKey("round_screen_safe_padding_scale")
    private val pullRefreshDistanceKey = floatPreferencesKey("pull_refresh_distance")
    private val animationSpeedKey = stringPreferencesKey("animation_speed")
    private val transitionStyleKey = stringPreferencesKey("transition_style")
    /** 一次性迁移门闩：默认转场从缩放改为左右滑动后，只改写一次已落盘的旧默认值。 */
    private val transitionDefaultMigratedKey = booleanPreferencesKey("transition_default_migrated_v1")
    private val isPureBlackKey = booleanPreferencesKey("is_pure_black")
    private val wallpaperCardTintKey = booleanPreferencesKey("wallpaper_card_tint")
    private val frameRateModeKey = stringPreferencesKey("frame_rate_mode")
    private val cornerStyleKey = stringPreferencesKey("corner_style")
    private val uiStyleKey = stringPreferencesKey("ui_style")
    private val glassMaterialKey = stringPreferencesKey("glass_material")
    private val glassStrengthKey = floatPreferencesKey("glass_strength")
    // 旧版两键保留读取用于迁移，不再写入
    private val glassEnabledLegacyKey = booleanPreferencesKey("glass_enabled")
    private val glassStyleLegacyKey = stringPreferencesKey("glass_style")
    private val hdFeedKey = booleanPreferencesKey("hd_feed")
    private val personalizedRcmdKey = booleanPreferencesKey("personalized_rcmd")
    private val lessonsModeKey = booleanPreferencesKey("lessons_mode")
    private val teenagersModeKey = booleanPreferencesKey("teenagers_mode")
    private val teenagersAgeKey = intPreferencesKey("teenagers_age")
    private val autoCheckUpdateKey = booleanPreferencesKey("auto_check_update")
    private val useSystemDnsKey = booleanPreferencesKey("use_system_dns")
    private val fixBottomBarKey = booleanPreferencesKey("fix_bottom_bar")
    private val topProgressiveFadeKey = booleanPreferencesKey("top_progressive_fade")
    private val playerControlsBlurKey = booleanPreferencesKey("player_controls_blur")
    private val playerControlsMaterialKey = stringPreferencesKey("player_controls_material")

    val themeConfig: Flow<ThemeConfig> = context.appSettingsDataStore.data.map { prefs ->
        ThemeConfig(
            themeMode = prefs[themeModeKey]?.let { ThemeMode.valueOf(it) }
                ?: defaultThemeConfig.themeMode,
            seedColor = Color(prefs[seedColorKey] ?: defaultThemeConfig.seedColor.toArgb()),
            useDynamicColor = prefs[useDynamicColorKey] ?: defaultThemeConfig.useDynamicColor,
            paletteStyle = prefs[paletteStyleKey]?.let { PaletteStyle.valueOf(it) }
                ?: defaultThemeConfig.paletteStyle,
            swapBaseColors = prefs[swapBaseColorsKey] ?: defaultThemeConfig.swapBaseColors,
            fontScale = prefs[fontScaleKey] ?: defaultThemeConfig.fontScale,
            uiScale = prefs[uiScaleKey] ?: defaultThemeConfig.uiScale,
            roundScreenSafePaddingScale = prefs[roundScreenSafePaddingScaleKey]
                ?: defaultThemeConfig.roundScreenSafePaddingScale,
            pullRefreshDistanceDp = prefs[pullRefreshDistanceKey]
                ?: defaultThemeConfig.pullRefreshDistanceDp,
            animationSpeed = prefs[animationSpeedKey]?.let { AnimationSpeed.valueOf(it) }
                ?: defaultThemeConfig.animationSpeed,
            transitionStyle = prefs[transitionStyleKey]?.let { TransitionStyle.valueOf(it) }
                ?: defaultThemeConfig.transitionStyle,
            isPureBlack = prefs[isPureBlackKey] ?: defaultThemeConfig.isPureBlack,
            wallpaperCardTint = prefs[wallpaperCardTintKey] ?: defaultThemeConfig.wallpaperCardTint,
            preferredFrameRate = prefs[frameRateModeKey]?.let { FrameRateMode.valueOf(it) }
                ?: defaultThemeConfig.preferredFrameRate,
            cornerStyle = prefs[cornerStyleKey]?.let { CornerStyle.valueOf(it) }
                ?: defaultThemeConfig.cornerStyle,
            // 兜底统一走 defaultThemeConfig：默认值只在 ThemeConfig 数据类定义一处，
            // 将来翻转默认风格（MATERIAL → IOS）时本文件零改动。
            uiStyle = prefs[uiStyleKey]?.let { UiStyle.valueOf(it) }
                ?: defaultThemeConfig.uiStyle,
            glassMaterial = resolveGlassMaterial(prefs),
            glassStrength = prefs[glassStrengthKey] ?: defaultThemeConfig.glassStrength
        )
    }.distinctUntilChanged()

    /**
     * 一次性迁移：把**旧默认值** `SHARED_AXIS_Z`（缩放）改写为新默认 `SHARED_AXIS_X`（左右滑动）。
     *
     * 为什么需要迁移而不是只改 `ThemeConfig` 默认值：`themeConfig` 的读法是
     * "prefs 里有值就用 prefs，没有才用默认"。老用户（以及任何在本改动之前
     * 跑过一次的设置）DataStore 里**已经落盘了 `SHARED_AXIS_Z`**，只改默认值
     * 对他们完全无效——开箱仍是整页缩放。
     *
     * 边界（重要）：
     * - 只迁移"恰好等于旧默认值"的记录。用户**主动选过**缩放时值也是
     *   `SHARED_AXIS_Z`，无法与"从未改过"区分，因此会被一并迁移。
     *   这是刻意的取舍：缩放是本次要淘汰的默认观感，而设置项本身仍在，
     *   用户想用缩放随时可以重新选回来。
     * - 用 [transitionDefaultMigratedKey] 做一次性门闩，迁移后不再触碰该键，
     *   用户在迁移之后手动改回 `SHARED_AXIS_Z` 不会被再次改写。
     */
    suspend fun migrateTransitionStyleDefaultIfNeeded() {
        context.appSettingsDataStore.edit { prefs ->
            if (prefs[transitionDefaultMigratedKey] == true) return@edit
            if (prefs[transitionStyleKey] == TransitionStyle.SHARED_AXIS_Z.name) {
                prefs[transitionStyleKey] = TransitionStyle.SHARED_AXIS_X.name
            }
            prefs[transitionDefaultMigratedKey] = true
        }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.appSettingsDataStore.edit { it[themeModeKey] = mode.name }
    }

    suspend fun updateSeedColor(color: Color) {
        context.appSettingsDataStore.edit { it[seedColorKey] = color.toArgb() }
    }

    suspend fun updateUseDynamicColor(use: Boolean) {
        context.appSettingsDataStore.edit { it[useDynamicColorKey] = use }
    }

    suspend fun updatePaletteStyle(style: PaletteStyle) {
        context.appSettingsDataStore.edit { it[paletteStyleKey] = style.name }
    }

    suspend fun updateSwapBaseColors(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[swapBaseColorsKey] = enabled }
    }

    suspend fun updateFontScale(scale: Float) {
        context.appSettingsDataStore.edit { it[fontScaleKey] = scale }
    }

    suspend fun updateUiScale(scale: Float) {
        context.appSettingsDataStore.edit { it[uiScaleKey] = scale }
    }

    suspend fun updateRoundScreenSafePaddingScale(scale: Float) {
        context.appSettingsDataStore.edit { it[roundScreenSafePaddingScaleKey] = scale }
    }

    suspend fun updatePullRefreshDistance(distanceDp: Float) {
        context.appSettingsDataStore.edit { it[pullRefreshDistanceKey] = distanceDp }
    }

    suspend fun updateAnimationSpeed(speed: AnimationSpeed) {
        context.appSettingsDataStore.edit { it[animationSpeedKey] = speed.name }
    }

    suspend fun updateTransitionStyle(style: TransitionStyle) {
        context.appSettingsDataStore.edit { it[transitionStyleKey] = style.name }
    }

    suspend fun updateIsPureBlack(isPure: Boolean) {
        context.appSettingsDataStore.edit { it[isPureBlackKey] = isPure }
    }

    suspend fun updateWallpaperCardTint(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[wallpaperCardTintKey] = enabled }
    }

    suspend fun updateFrameRateMode(mode: FrameRateMode) {
        context.appSettingsDataStore.edit { it[frameRateModeKey] = mode.name }
    }

    suspend fun updateCornerStyle(style: CornerStyle) {
        context.appSettingsDataStore.edit { it[cornerStyleKey] = style.name }
    }

    suspend fun updateUiStyle(style: UiStyle) {
        context.appSettingsDataStore.edit { it[uiStyleKey] = style.name }
    }

    suspend fun updateGlassMaterial(material: com.naaammme.bbspace.core.designsystem.glass.GlassMaterial) {
        context.appSettingsDataStore.edit { it[glassMaterialKey] = material.name }
    }

    suspend fun updateGlassStrength(strength: Float) {
        context.appSettingsDataStore.edit { it[glassStrengthKey] = strength.coerceIn(0.5f, 2f) }
    }

    /**
     * 三档材质读取与旧值迁移（终版方案 3.1）：新 key 缺失时由旧两键推导——
     * 开+STABLE→FROSTED、开+RICH→LIQUID、关→OFF；全新安装默认 FROSTED。
     */
    private fun resolveGlassMaterial(
        prefs: androidx.datastore.preferences.core.Preferences
    ): com.naaammme.bbspace.core.designsystem.glass.GlassMaterial {
        prefs[glassMaterialKey]?.let { raw ->
            return com.naaammme.bbspace.core.designsystem.glass.GlassMaterial.entries
                .firstOrNull { it.name == raw }
                ?: defaultThemeConfig.glassMaterial
        }
        // 旧 key 缺失 = 从未设置过（新装 / 未开过玻璃）→ 采用新默认 FROSTED，
        // 不能把"从未设置"当"用户主动关闭"（AI 复核发现，属实）
        val legacyEnabled = prefs[glassEnabledLegacyKey]
            ?: return defaultThemeConfig.glassMaterial
        val legacyStyle = prefs[glassStyleLegacyKey]
        return when {
            !legacyEnabled ->
                com.naaammme.bbspace.core.designsystem.glass.GlassMaterial.OFF
            legacyStyle == "RICH" ->
                com.naaammme.bbspace.core.designsystem.glass.GlassMaterial.LIQUID
            else ->
                com.naaammme.bbspace.core.designsystem.glass.GlassMaterial.FROSTED
        }
    }

    val hdFeed: Flow<Boolean> = context.appSettingsDataStore.data.map { it[hdFeedKey] ?: false }

    val personalizedRcmd: Flow<Boolean> = context.appSettingsDataStore.data.map { it[personalizedRcmdKey] ?: true }

    val lessonsMode: Flow<Boolean> = context.appSettingsDataStore.data.map { it[lessonsModeKey] ?: false }

    val teenagersMode: Flow<Boolean> = context.appSettingsDataStore.data.map { it[teenagersModeKey] ?: false }

    val teenagersAge: Flow<Int> = context.appSettingsDataStore.data.map {
        (it[teenagersAgeKey] ?: DEFAULT_TEENAGERS_AGE).coerceIn(MIN_TEENAGERS_AGE, MAX_TEENAGERS_AGE)
    }

    val autoCheckUpdate: Flow<Boolean> = context.appSettingsDataStore.data.map { it[autoCheckUpdateKey] ?: true }
    val useSystemDns: Flow<Boolean> = context.appSettingsDataStore.data.map { it[useSystemDnsKey] ?: false }
    val fixBottomBar: Flow<Boolean> = context.appSettingsDataStore.data.map { it[fixBottomBarKey] ?: true }

    /** 顶部渐进消隐（同步 BiliPai 上游）：内容滚入顶栏时按 Telegram 5 阶非线性融进底色。 */
    val topProgressiveFade: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[topProgressiveFadeKey] ?: true }

    /**
     * 播放页控件模糊：进度条 / 中央播放钮 / 控制条 / 顶栏浮层是否采样实时视频画面做
     * 磨砂玻璃。关闭或背板不可用时回落「黑遮罩 + 白前景」纯色（可读性优先）。
     */
    val playerControlsBlur: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[playerControlsBlurKey] ?: true }

    /**
     * 播放页控件用哪种玻璃材质（磨砂 / 液态），与全局 [ThemeConfig.glassMaterial] **独立**。
     *
     * 为什么单独给播放页一个开关：全局材质是给 chrome（Dock / 顶栏 / 胶囊）定的，
     * 而播放控件叠在**实时视频画面**上——液态的强折射在大面积动态画面上容易糊，
     * 磨砂更稳；反过来喜欢液态质感的人又希望播放控件也跟上。两者诉求冲突，
     * 所以拆成独立设置。默认磨砂（画面可读性优先）。
     */
    val playerControlsMaterial: Flow<GlassMaterial> =
        context.appSettingsDataStore.data.map { prefs ->
            prefs[playerControlsMaterialKey]?.let { raw ->
                runCatching { GlassMaterial.valueOf(raw) }.getOrNull()
            } ?: GlassMaterial.FROSTED
        }

    private val interestDoneKey = booleanPreferencesKey("interest_done")
    val interestDone: Flow<Boolean> = context.appSettingsDataStore.data.map { it[interestDoneKey] ?: false }

    suspend fun updateHdFeed(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[hdFeedKey] = enabled }
    }

    suspend fun updatePersonalizedRcmd(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[personalizedRcmdKey] = enabled }
    }

    suspend fun updateLessonsMode(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[lessonsModeKey] = enabled }
    }

    suspend fun updateTeenagersMode(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[teenagersModeKey] = enabled }
    }

    suspend fun updateTeenagersAge(age: Int) {
        context.appSettingsDataStore.edit { it[teenagersAgeKey] = age.coerceIn(MIN_TEENAGERS_AGE, MAX_TEENAGERS_AGE) }
    }

    suspend fun updateAutoCheckEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[autoCheckUpdateKey] = enabled }
    }

    suspend fun updateUseSystemDns(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[useSystemDnsKey] = enabled }
    }

    suspend fun updateFixBottomBar(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[fixBottomBarKey] = enabled }
    }

    suspend fun updateTopProgressiveFade(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[topProgressiveFadeKey] = enabled }
    }

    suspend fun updatePlayerControlsBlur(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[playerControlsBlurKey] = enabled }
    }

    suspend fun updatePlayerControlsMaterial(material: GlassMaterial) {
        context.appSettingsDataStore.edit { it[playerControlsMaterialKey] = material.name }
    }

    suspend fun markInterestDone() {
        context.appSettingsDataStore.edit { it[interestDoneKey] = true }
    }

    private val blockGaiaKey = booleanPreferencesKey("block_gaia")
    val blockGaia: Flow<Boolean> = context.appSettingsDataStore.data.map { it[blockGaiaKey] ?: false }

    suspend fun updateBlockGaia(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[blockGaiaKey] = enabled }
    }

    // ===== 首页布局 =====
    private val homeFeedSingleColumnKey = booleanPreferencesKey("home_feed_single_column")

    /** 首页推荐流是否使用单列大图布局，默认开启（预览图更大）。 */
    val homeFeedSingleColumn: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[homeFeedSingleColumnKey] ?: true }

    suspend fun setHomeFeedSingleColumn(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[homeFeedSingleColumnKey] = enabled }
    }

    // ===== 空降助手（SponsorBlock） =====
    private val sponsorBlockEnabledKey = booleanPreferencesKey("sponsor_block_enabled")

    /** 空降助手总开关，默认关闭。 */
    val sponsorBlockEnabled: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[sponsorBlockEnabledKey] ?: false }

    suspend fun updateSponsorBlockEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[sponsorBlockEnabledKey] = enabled }
    }

    private val sponsorBlockServerUrlKey = stringPreferencesKey("sponsor_block_server_url")

    /** 空降助手社区服务器地址，留空使用官方默认。 */
    val sponsorBlockServerUrl: Flow<String> =
        context.appSettingsDataStore.data.map { it[sponsorBlockServerUrlKey] ?: "" }

    suspend fun updateSponsorBlockServerUrl(url: String) {
        context.appSettingsDataStore.edit { it[sponsorBlockServerUrlKey] = url.trim() }
    }

    private val sponsorBlockUserIdKey = stringPreferencesKey("sponsor_block_user_id")

    /** 私人用户 ID（36 位随机串），首次使用时生成；丢失无法找回，务必只存本地。 */
    val sponsorBlockUserId: Flow<String?> =
        context.appSettingsDataStore.data.map { it[sponsorBlockUserIdKey]?.takeIf(String::isNotBlank) }

    suspend fun ensureSponsorBlockUserId(): String {
        val existing = context.appSettingsDataStore.data
            .map { it[sponsorBlockUserIdKey]?.takeIf(String::isNotBlank) }
            .first()
        if (existing != null) return existing
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        val next = buildString {
            repeat(36) { append(charset[random.nextInt(charset.length)]) }
        }
        context.appSettingsDataStore.edit { it[sponsorBlockUserIdKey] = next }
        return next
    }

    suspend fun regenerateSponsorBlockUserId(): String {
        context.appSettingsDataStore.edit { it.remove(sponsorBlockUserIdKey) }
        return ensureSponsorBlockUserId()
    }

    private val sponsorBlockCategoryTypesKey = stringPreferencesKey("sponsor_block_category_types")

    /**
     * 各分类的处理策略，key 为分类 wire 名，value 为 SbSkipType 序号。
     * 未配置的分类使用 SbCategory.defaultSkipType。
     */
    val sponsorBlockCategoryTypes: Flow<Map<String, Int>> =
        context.appSettingsDataStore.data.map { prefs ->
            val raw = prefs[sponsorBlockCategoryTypesKey]?.takeIf(String::isNotBlank)
                ?: return@map emptyMap()
            runCatching {
                val obj = org.json.JSONObject(raw)
                buildMap {
                    obj.keys().forEach { key ->
                        put(key, obj.optInt(key, -1).takeIf { it >= 0 } ?: return@forEach)
                    }
                }
            }.getOrDefault(emptyMap())
        }

    suspend fun updateSponsorBlockCategoryTypes(types: Map<String, Int>) {
        val json = org.json.JSONObject().apply {
            types.forEach { (key, value) -> put(key, value) }
        }.toString()
        context.appSettingsDataStore.edit { it[sponsorBlockCategoryTypesKey] = json }
    }

    private val sponsorBlockToastKey = booleanPreferencesKey("sponsor_block_toast")

    /** 跳过提示 Toast，默认开启。 */
    val sponsorBlockToast: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[sponsorBlockToastKey] ?: true }

    suspend fun updateSponsorBlockToast(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[sponsorBlockToastKey] = enabled }
    }

    private val sponsorBlockMarkersKey = booleanPreferencesKey("sponsor_block_markers")

    /** 进度条片段标记，默认开启。 */
    val sponsorBlockMarkers: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[sponsorBlockMarkersKey] ?: true }

    suspend fun updateSponsorBlockMarkers(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[sponsorBlockMarkersKey] = enabled }
    }

    private val enableHdrAnd8kKey = booleanPreferencesKey("enable_hdr_8k")
    private val defaultVideoQualityKey = intPreferencesKey("default_video_quality")
    private val defaultAudioQualityKey = intPreferencesKey("default_audio_quality")
    private val forceHostKey = intPreferencesKey("force_host")
    private val needTrialKey = booleanPreferencesKey("need_trial")
    private val preferredCodecKey = intPreferencesKey("preferred_codec_qn")
    private val enableWebPlaybackKey = booleanPreferencesKey("enable_web_playback")
    private val playerBufferProfileKey = intPreferencesKey("player_buffer_profile")
    private val preferSoftDecKey = booleanPreferencesKey("prefer_soft_dec")
    private val decFallbackKey = booleanPreferencesKey("dec_fallback")
    private val bgPlayKey = booleanPreferencesKey("bg_play")
    private val seamlessTransitionKey = booleanPreferencesKey("seamless_transition")
    private val autoRotateFullscreenKey = booleanPreferencesKey("auto_rotate_fullscreen")
    private val gestureSpeedKey = floatPreferencesKey("gesture_speed")
    private val doubleTapLikeKey = booleanPreferencesKey("double_tap_like")
    private val videoCdnModeKey = stringPreferencesKey("video_cdn_mode")
    private val repeatModeKey = stringPreferencesKey("player_repeat_mode")
    private val reportPlaybackKey = booleanPreferencesKey("report_playback")
    private val danmakuEnabledKey = booleanPreferencesKey("danmaku_enabled")
    private val danmakuAreaPercentKey = intPreferencesKey("danmaku_area_percent")
    private val danmakuOpacityKey = floatPreferencesKey("danmaku_opacity")
    private val danmakuTextScaleKey = floatPreferencesKey("danmaku_text_scale")
    private val danmakuSpeedKey = floatPreferencesKey("danmaku_speed")
    private val danmakuDensityKey = intPreferencesKey("danmaku_density")
    private val danmakuWeightFilterLevelKey = intPreferencesKey("danmaku_weight_filter_level")
    private val danmakuMergeDuplicatesKey = booleanPreferencesKey("danmaku_merge_duplicates")
    private val danmakuShowTopKey = booleanPreferencesKey("danmaku_show_top")
    private val danmakuShowBottomKey = booleanPreferencesKey("danmaku_show_bottom")
    private val danmakuShowScrollRlKey = booleanPreferencesKey("danmaku_show_scroll_rl")

    val enableHdrAnd8k: Flow<Boolean> = context.appSettingsDataStore.data.map { it[enableHdrAnd8kKey] ?: false }
    val defaultVideoQuality: Flow<Int> = context.appSettingsDataStore.data.map { it[defaultVideoQualityKey] ?: 64 }
    val defaultAudioQuality: Flow<Int> = context.appSettingsDataStore.data.map { it[defaultAudioQualityKey] ?: 0 }
    val forceHost: Flow<Int> = context.appSettingsDataStore.data.map { it[forceHostKey] ?: 0 }
    val needTrial: Flow<Boolean> = context.appSettingsDataStore.data.map { it[needTrialKey] ?: false }
    val preferredCodec: Flow<Int> = context.appSettingsDataStore.data.map { it[preferredCodecKey] ?: 2 }
    val enableWebPlayback: Flow<Boolean> = context.appSettingsDataStore.data.map { it[enableWebPlaybackKey] ?: false }
    // 播放偏好和外观 推荐等设置一样都属于持久偏好，不在这里做会话态缓存
    val state: Flow<PlayerSettingsState> = context.appSettingsDataStore.data.map { prefs ->
        PlayerSettingsState(
            buffer = PlayerBufferSettings(
                profile = prefs[playerBufferProfileKey]
                    .toPlayerBufferProfile(defaultBufferSettings.profile)
            ),
            playback = PlayerPlaybackPrefs(
                backgroundPlayback = prefs[bgPlayKey] ?: defaultPlaybackPrefs.backgroundPlayback,
                seamlessTransition = prefs[seamlessTransitionKey] ?: defaultPlaybackPrefs.seamlessTransition,
                reportPlayback = prefs[reportPlaybackKey] ?: defaultPlaybackPrefs.reportPlayback,
                preferSoftwareDecode = prefs[preferSoftDecKey]
                    ?: defaultPlaybackPrefs.preferSoftwareDecode,
                decoderFallback = prefs[decFallbackKey] ?: defaultPlaybackPrefs.decoderFallback,
                autoRotateFullscreen = prefs[autoRotateFullscreenKey]
                    ?: defaultPlaybackPrefs.autoRotateFullscreen,
                gestureSpeed = (
                    prefs[gestureSpeedKey] ?: defaultPlaybackPrefs.gestureSpeed
                ).coerceIn(0.25f, 3f),
                videoCdnMode = prefs[videoCdnModeKey]
                    ?.let(VideoCdnMode::valueOf)
                    ?: defaultPlaybackPrefs.videoCdnMode,
                doubleTapLike = prefs[doubleTapLikeKey] ?: defaultPlaybackPrefs.doubleTapLike,
                repeatMode = prefs[repeatModeKey]
                    ?.let(PlayerRepeatMode::valueOf)
                    ?: defaultPlaybackPrefs.repeatMode
            ),
            danmaku = DanmakuConfig(
                enabled = prefs[danmakuEnabledKey] ?: defaultDanmakuConfig.enabled,
                areaPercent = prefs[danmakuAreaPercentKey] ?: defaultDanmakuConfig.areaPercent,
                opacity = prefs[danmakuOpacityKey] ?: defaultDanmakuConfig.opacity,
                textScale = prefs[danmakuTextScaleKey] ?: defaultDanmakuConfig.textScale,
                speed = prefs[danmakuSpeedKey] ?: defaultDanmakuConfig.speed,
                densityLevel = prefs[danmakuDensityKey] ?: defaultDanmakuConfig.densityLevel,
                weightFilterLevel = (
                    prefs[danmakuWeightFilterLevelKey] ?: defaultDanmakuConfig.weightFilterLevel
                ).coerceIn(0, 10),
                mergeDuplicates = prefs[danmakuMergeDuplicatesKey]
                    ?: defaultDanmakuConfig.mergeDuplicates,
                showTop = prefs[danmakuShowTopKey] ?: defaultDanmakuConfig.showTop,
                showBottom = prefs[danmakuShowBottomKey] ?: defaultDanmakuConfig.showBottom,
                showScrollRl = prefs[danmakuShowScrollRlKey] ?: defaultDanmakuConfig.showScrollRl
            )
        )
    }.distinctUntilChanged()

    suspend fun updateEnableHdrAnd8k(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[enableHdrAnd8kKey] = enabled }
    }

    suspend fun updateDefaultVideoQuality(quality: Int) {
        context.appSettingsDataStore.edit { it[defaultVideoQualityKey] = quality }
    }

    suspend fun updateDefaultAudioQuality(quality: Int) {
        context.appSettingsDataStore.edit { it[defaultAudioQualityKey] = quality }
    }

    suspend fun updateForceHost(value: Int) {
        context.appSettingsDataStore.edit { it[forceHostKey] = value }
    }

    suspend fun updateNeedTrial(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[needTrialKey] = enabled }
    }

    suspend fun updatePreferredCodec(codec: Int) {
        context.appSettingsDataStore.edit { it[preferredCodecKey] = codec }
    }

    suspend fun updateEnableWebPlayback(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[enableWebPlaybackKey] = enabled }
    }

    suspend fun setBufferProfile(profile: PlayerBufferProfile) {
        context.appSettingsDataStore.edit { it[playerBufferProfileKey] = profile.ordinal }
    }

    suspend fun setBackgroundPlayback(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[bgPlayKey] = enabled }
    }

    /** ColorOS 无缝过渡动画开关（默认开）；关闭退回覆盖层路径。 */
    suspend fun setSeamlessTransition(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[seamlessTransitionKey] = enabled }
    }

    suspend fun setReportPlayback(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[reportPlaybackKey] = enabled }
    }

    suspend fun setPreferSoftwareDecode(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[preferSoftDecKey] = enabled }
    }

    suspend fun setDecoderFallback(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[decFallbackKey] = enabled }
    }

    suspend fun setAutoRotateFullscreen(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[autoRotateFullscreenKey] = enabled }
    }

    suspend fun setGestureSpeed(speed: Float) {
        context.appSettingsDataStore.edit { it[gestureSpeedKey] = speed.coerceIn(0.25f, 3f) }
    }

    suspend fun setDoubleTapLike(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[doubleTapLikeKey] = enabled }
    }

    suspend fun setRepeatMode(mode: PlayerRepeatMode) {
        context.appSettingsDataStore.edit { it[repeatModeKey] = mode.name }
    }

    suspend fun setVideoCdnMode(mode: VideoCdnMode) {
        context.appSettingsDataStore.edit { it[videoCdnModeKey] = mode.name }
    }

    suspend fun setDanmaku(config: DanmakuConfig) {
        context.appSettingsDataStore.edit {
            it[danmakuEnabledKey] = config.enabled
            it[danmakuAreaPercentKey] = config.areaPercent.coerceIn(25, 100)
            it[danmakuOpacityKey] = config.opacity.coerceIn(0.1f, 1f)
            it[danmakuTextScaleKey] = config.textScale.coerceIn(0.5f, 2f)
            it[danmakuSpeedKey] = config.speed.coerceIn(0.5f, 2f)
            it[danmakuDensityKey] = config.densityLevel.coerceIn(0, 2)
            it[danmakuWeightFilterLevelKey] = config.weightFilterLevel.coerceIn(0, 10)
            it[danmakuMergeDuplicatesKey] = config.mergeDuplicates
            it[danmakuShowTopKey] = config.showTop
            it[danmakuShowBottomKey] = config.showBottom
            it[danmakuShowScrollRlKey] = config.showScrollRl
        }
    }

    suspend fun resetAllSettings() {
        context.appSettingsDataStore.edit {
            val interestDone = it[interestDoneKey] ?: false
            it.clear()
            if (interestDone) {
                it[interestDoneKey] = true
            }
        }
    }

    private companion object {
        const val DEFAULT_TEENAGERS_AGE = 16
        const val MIN_TEENAGERS_AGE = 1
        const val MAX_TEENAGERS_AGE = 17
    }

    private fun Int?.toPlayerBufferProfile(defaultProfile: PlayerBufferProfile): PlayerBufferProfile {
        return PlayerBufferProfile.entries.getOrElse(this ?: defaultProfile.ordinal) {
            defaultProfile
        }
    }
}
