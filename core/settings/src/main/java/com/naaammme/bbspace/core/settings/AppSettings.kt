package com.naaammme.bbspace.core.settings

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
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

/** 设置镜像（SharedPreferences）文件名：首帧同步读用，见 [AppSettings.peekThemeConfigBlocking]。 */
private const val SETTINGS_MIRROR_PREFS = "app_settings_mirror"

/** 镜像是否写过完整快照的门闩键（不与任何 DataStore 键名冲突）。 */
private const val MIRROR_READY_KEY = "__mirror_ready__"

@Singleton
class AppSettings @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val defaultThemeConfig = ThemeConfig()

    /**
     * 镜像解析结果的进程内缓存（后台线程加载，首帧只读这个 volatile 字段）。
     *
     * 为什么不直接在首帧读 SharedPreferences：SP **首次**访问会在调用线程读盘 + 解析 XML，
     * 而 `peekThemeConfigBlocking()` 恰好跑在首帧组合（主线程）。真机冷启动对照实测里，
     * 那一步就是"优化后反而比基线慢 ~5%"的嫌疑来源 —— 预热线程不一定赢下与首帧的竞态。
     * 改成后台线程整体加载、主线程读引用后，首帧成本是一个 volatile 读。
     */
    @Volatile
    private var mirrorThemeConfig: ThemeConfig? = null

    init {
        // 预热：AppSettings 是 @Singleton，构造发生在 Application.onCreate 的 Hilt 注入链路上，
        // 距离首帧组合有充足时间（实测百毫秒级），几乎不会输给竞态。
        // 真输了也只是回退到带超时的 runBlocking 慢路径，不会更差。
        Thread {
            runCatching {
                val sp = context.getSharedPreferences(SETTINGS_MIRROR_PREFS, Context.MODE_PRIVATE)
                if (sp.getBoolean(MIRROR_READY_KEY, false)) {
                    mirrorThemeConfig = buildThemeConfig(MirrorReader(sp))
                }
            }
        }.apply {
            isDaemon = true
            name = "settings-mirror-warmup"
            start()
        }
    }
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

    val themeConfig: Flow<ThemeConfig> = context.appSettingsDataStore.data
        .map { prefs ->
            // **首读即回填镜像**：镜像若只在写设置时创建，那么"从不改设置"的用户永远没有镜像，
            // 每次冷启动都会走 `runBlocking` 兜底 —— 快路径形同虚设（真机冷启动对照实测发现）。
            // DataStore 的每次读（首读 + 每次变更）都顺手刷一份快照，apply() 是异步落盘，
            // 且与现有镜像一致时直接跳过写盘；map 之后的 distinctUntilChanged 保证下游不多发值。
            persistSettingsMirror(prefs)
            val config = buildThemeConfig(DataStoreReader(prefs))
            // 同步刷新进程内缓存：详情页是独立 Activity、首帧会再次 peek，
            // 缓存不跟着设置更新会让它在设置变更后短暂用旧主题。
            mirrorThemeConfig = config
            config
        }
        .distinctUntilChanged()

    // ── 设置镜像：让「首帧主题 = 真实配置」不再需要在主线程 runBlocking ──────────
    //
    // 背景：主题首帧必须就是真实配置，否则第一帧按默认（MATERIAL/FROSTED）渲染、
    // 第二帧才翻成用户配置 —— 界面风格、玻璃材质、配色整体闪一次。旧修法是在
    // MainActivity / VideoDetailActivity 的**首次组合（主线程）**里
    // `runBlocking { withTimeoutOrNull(300) { themeConfig.first() } }`，代价是主线程
    // 阻塞至 DataStore IO 返回（上限 300ms），恰好压在冷启动与无缝打开的首帧上。
    //
    // 现在：所有写入经 [editSettingsMirrored] 同步写一份 SharedPreferences 快照，
    // 首帧改读镜像（一次 mmap 小文件读，微秒级）。只有镜像缺失（升级后首启、
    // 或数据被清）才回退那条带超时的慢路径 —— 那种情况下也必须回退，否则会闪主题。
    //
    // **所有写入必须过 editSettingsMirrored**：漏一处镜像就漂移，表现为下次启动主题不对。

    /** 只读访问抽象：让「构造 ThemeConfig」这段逻辑同时服务 DataStore 与镜像，避免两处漂移。 */
    private interface PrefReader {
        fun str(name: String): String?
        fun int(name: String): Int?
        fun bool(name: String): Boolean?
        fun float(name: String): Float?
    }

    private class DataStoreReader(
        private val prefs: androidx.datastore.preferences.core.Preferences
    ) : PrefReader {
        override fun str(name: String): String? = prefs[stringPreferencesKey(name)]
        override fun int(name: String): Int? = prefs[intPreferencesKey(name)]
        override fun bool(name: String): Boolean? = prefs[booleanPreferencesKey(name)]
        override fun float(name: String): Float? = prefs[floatPreferencesKey(name)]
    }

    private class MirrorReader(
        private val sp: android.content.SharedPreferences
    ) : PrefReader {
        override fun str(name: String): String? = if (sp.contains(name)) sp.getString(name, null) else null
        override fun int(name: String): Int? = if (sp.contains(name)) sp.getInt(name, 0) else null
        override fun bool(name: String): Boolean? = if (sp.contains(name)) sp.getBoolean(name, false) else null
        override fun float(name: String): Float? = if (sp.contains(name)) sp.getFloat(name, 0f) else null
    }

    /**
     * ThemeConfig 的**唯一构造点**（DataStore 与镜像共用）。
     *
     * 枚举值解析加了 runCatching：坏值（手改/跨版本残留）不该让整个 flow 抛异常 ——
     * 原实现在 `valueOf` 处是直接抛的，DataStore 里有一个脏字符串就会让主题流整体失效。
     */
    private fun buildThemeConfig(r: PrefReader): ThemeConfig = ThemeConfig(
        themeMode = r.str(themeModeKey.name)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: defaultThemeConfig.themeMode,
        seedColor = Color(r.int(seedColorKey.name) ?: defaultThemeConfig.seedColor.toArgb()),
        useDynamicColor = r.bool(useDynamicColorKey.name) ?: defaultThemeConfig.useDynamicColor,
        paletteStyle = r.str(paletteStyleKey.name)?.let { runCatching { PaletteStyle.valueOf(it) }.getOrNull() }
            ?: defaultThemeConfig.paletteStyle,
        swapBaseColors = r.bool(swapBaseColorsKey.name) ?: defaultThemeConfig.swapBaseColors,
        fontScale = r.float(fontScaleKey.name) ?: defaultThemeConfig.fontScale,
        uiScale = r.float(uiScaleKey.name) ?: defaultThemeConfig.uiScale,
        roundScreenSafePaddingScale = r.float(roundScreenSafePaddingScaleKey.name)
            ?: defaultThemeConfig.roundScreenSafePaddingScale,
        pullRefreshDistanceDp = r.float(pullRefreshDistanceKey.name)
            ?: defaultThemeConfig.pullRefreshDistanceDp,
        animationSpeed = r.str(animationSpeedKey.name)?.let { runCatching { AnimationSpeed.valueOf(it) }.getOrNull() }
            ?: defaultThemeConfig.animationSpeed,
        transitionStyle = r.str(transitionStyleKey.name)?.let { runCatching { TransitionStyle.valueOf(it) }.getOrNull() }
            ?: defaultThemeConfig.transitionStyle,
        isPureBlack = r.bool(isPureBlackKey.name) ?: defaultThemeConfig.isPureBlack,
        preferredFrameRate = r.str(frameRateModeKey.name)?.let { runCatching { FrameRateMode.valueOf(it) }.getOrNull() }
            ?: defaultThemeConfig.preferredFrameRate,
        cornerStyle = r.str(cornerStyleKey.name)?.let { runCatching { CornerStyle.valueOf(it) }.getOrNull() }
            ?: defaultThemeConfig.cornerStyle,
        // 兜底统一走 defaultThemeConfig：默认值只在 ThemeConfig 数据类定义一处，
        // 将来翻转默认风格（MATERIAL → IOS）时本文件零改动。
        uiStyle = r.str(uiStyleKey.name)?.let { runCatching { UiStyle.valueOf(it) }.getOrNull() }
            ?: defaultThemeConfig.uiStyle,
        glassMaterial = resolveGlassMaterial(r),
        glassStrength = r.float(glassStrengthKey.name) ?: defaultThemeConfig.glassStrength
    )

    /**
     * 首帧同步读主题：**纯内存读**（[mirrorThemeConfig]，后台线程已加载），
     * 不碰 DataStore、也不碰 SharedPreferences 文件。
     *
     * 返回 null 表示镜像还没加载好或不存在（升级后首启、清数据、竞态输掉），调用方应回退
     * 带超时的慢路径 —— 这种情况下宁可阻塞一次，也不能让首帧主题闪一下。
     */
    fun peekThemeConfigBlocking(): ThemeConfig? = mirrorThemeConfig

    /** 设置写入的**唯一收口**：写 DataStore 之后把快照同步写进镜像。 */
    private suspend fun editSettingsMirrored(transform: suspend (MutablePreferences) -> Unit) {
        val next = context.appSettingsDataStore.edit(transform)
        persistSettingsMirror(next)
    }

    /**
     * 把 DataStore 的**完整快照**写进镜像。
     *
     * 用 `clear()` + 回填而不是逐键覆盖：DataStore 里被 `remove` 的键必须从镜像里
     * 消失（`resetAllSettings` 会 `clear()`），否则镜像会残留旧值。
     * SharedPreferences 的 `clear()` 语义是"先清空再应用本次 put"，正好是快照语义。
     *
     * **与镜像一致时直接返回**：这条路径挂在 `themeConfig` 的读取上（每次冷启动首帧都会
     * 走），不做判等就等于每次启动都白写一次盘。`sp.all` 是内存快照，判等是纯内存比较。
     */
    private fun persistSettingsMirror(prefs: androidx.datastore.preferences.core.Preferences) {
        val sp = context.getSharedPreferences(SETTINGS_MIRROR_PREFS, Context.MODE_PRIVATE)
        val next = LinkedHashMap<String, Any>(prefs.asMap().size)
        for ((key, value) in prefs.asMap()) {
            when (value) {
                is String -> next[key.name] = value
                is Int -> next[key.name] = value
                is Boolean -> next[key.name] = value
                is Float -> next[key.name] = value
                is Long -> next[key.name] = value
            }
        }
        val current = sp.all
        val same = sp.getBoolean(MIRROR_READY_KEY, false) &&
            current.size - 1 == next.size &&
            next.all { (k, v) -> current[k] == v }
        if (same) return
        val editor = sp.edit()
        editor.clear()
        for ((k, v) in next) {
            when (v) {
                is String -> editor.putString(k, v)
                is Int -> editor.putInt(k, v)
                is Boolean -> editor.putBoolean(k, v)
                is Float -> editor.putFloat(k, v)
                is Long -> editor.putLong(k, v)
            }
        }
        editor.putBoolean(MIRROR_READY_KEY, true)
        // apply() 而非 commit()：写盘异步化，不占调用线程；崩溃丢一次快照的代价
        // 只是下次启动多走一次慢路径（有超时兜底），可接受。
        editor.apply()
    }

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
        editSettingsMirrored { prefs ->
            if (prefs[transitionDefaultMigratedKey] == true) return@editSettingsMirrored
            if (prefs[transitionStyleKey] == TransitionStyle.SHARED_AXIS_Z.name) {
                prefs[transitionStyleKey] = TransitionStyle.SHARED_AXIS_X.name
            }
            prefs[transitionDefaultMigratedKey] = true
        }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        editSettingsMirrored { it[themeModeKey] = mode.name }
    }

    suspend fun updateSeedColor(color: Color) {
        editSettingsMirrored { it[seedColorKey] = color.toArgb() }
    }

    suspend fun updateUseDynamicColor(use: Boolean) {
        editSettingsMirrored { it[useDynamicColorKey] = use }
    }

    suspend fun updatePaletteStyle(style: PaletteStyle) {
        editSettingsMirrored { it[paletteStyleKey] = style.name }
    }

    suspend fun updateSwapBaseColors(enabled: Boolean) {
        editSettingsMirrored { it[swapBaseColorsKey] = enabled }
    }

    suspend fun updateFontScale(scale: Float) {
        editSettingsMirrored { it[fontScaleKey] = scale }
    }

    suspend fun updateUiScale(scale: Float) {
        editSettingsMirrored { it[uiScaleKey] = scale }
    }

    suspend fun updateRoundScreenSafePaddingScale(scale: Float) {
        editSettingsMirrored { it[roundScreenSafePaddingScaleKey] = scale }
    }

    suspend fun updatePullRefreshDistance(distanceDp: Float) {
        editSettingsMirrored { it[pullRefreshDistanceKey] = distanceDp }
    }

    suspend fun updateAnimationSpeed(speed: AnimationSpeed) {
        editSettingsMirrored { it[animationSpeedKey] = speed.name }
    }

    suspend fun updateTransitionStyle(style: TransitionStyle) {
        editSettingsMirrored { it[transitionStyleKey] = style.name }
    }

    suspend fun updateIsPureBlack(isPure: Boolean) {
        editSettingsMirrored { it[isPureBlackKey] = isPure }
    }

    suspend fun updateFrameRateMode(mode: FrameRateMode) {
        editSettingsMirrored { it[frameRateModeKey] = mode.name }
    }

    suspend fun updateCornerStyle(style: CornerStyle) {
        editSettingsMirrored { it[cornerStyleKey] = style.name }
    }

    suspend fun updateUiStyle(style: UiStyle) {
        editSettingsMirrored { it[uiStyleKey] = style.name }
    }

    suspend fun updateGlassMaterial(material: com.naaammme.bbspace.core.designsystem.glass.GlassMaterial) {
        editSettingsMirrored { it[glassMaterialKey] = material.name }
    }

    suspend fun updateGlassStrength(strength: Float) {
        editSettingsMirrored { it[glassStrengthKey] = strength.coerceIn(0.5f, 2f) }
    }

    /**
     * 三档材质读取与旧值迁移（终版方案 3.1）：新 key 缺失时由旧两键推导——
     * 开+STABLE→FROSTED、开+RICH→LIQUID、关→OFF；全新安装默认 FROSTED。
     *
     * 入参是 [PrefReader] 而不是 DataStore 的 Preferences：镜像与 DataStore
     * 必须走**同一套**取值与迁移逻辑，否则两条路径会算出不同的材质。
     */
    private fun resolveGlassMaterial(
        r: PrefReader
    ): com.naaammme.bbspace.core.designsystem.glass.GlassMaterial {
        r.str(glassMaterialKey.name)?.let { raw ->
            return com.naaammme.bbspace.core.designsystem.glass.GlassMaterial.entries
                .firstOrNull { it.name == raw }
                ?: defaultThemeConfig.glassMaterial
        }
        // 旧 key 缺失 = 从未设置过（新装 / 未开过玻璃）→ 采用新默认 FROSTED，
        // 不能把"从未设置"当"用户主动关闭"（AI 复核发现，属实）
        val legacyEnabled = r.bool(glassEnabledLegacyKey.name)
            ?: return defaultThemeConfig.glassMaterial
        val legacyStyle = r.str(glassStyleLegacyKey.name)
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
        editSettingsMirrored { it[hdFeedKey] = enabled }
    }

    suspend fun updatePersonalizedRcmd(enabled: Boolean) {
        editSettingsMirrored { it[personalizedRcmdKey] = enabled }
    }

    suspend fun updateLessonsMode(enabled: Boolean) {
        editSettingsMirrored { it[lessonsModeKey] = enabled }
    }

    suspend fun updateTeenagersMode(enabled: Boolean) {
        editSettingsMirrored { it[teenagersModeKey] = enabled }
    }

    suspend fun updateTeenagersAge(age: Int) {
        editSettingsMirrored { it[teenagersAgeKey] = age.coerceIn(MIN_TEENAGERS_AGE, MAX_TEENAGERS_AGE) }
    }

    suspend fun updateAutoCheckEnabled(enabled: Boolean) {
        editSettingsMirrored { it[autoCheckUpdateKey] = enabled }
    }

    suspend fun updateUseSystemDns(enabled: Boolean) {
        editSettingsMirrored { it[useSystemDnsKey] = enabled }
    }

    suspend fun updateFixBottomBar(enabled: Boolean) {
        editSettingsMirrored { it[fixBottomBarKey] = enabled }
    }

    suspend fun updateTopProgressiveFade(enabled: Boolean) {
        editSettingsMirrored { it[topProgressiveFadeKey] = enabled }
    }

    suspend fun updatePlayerControlsBlur(enabled: Boolean) {
        editSettingsMirrored { it[playerControlsBlurKey] = enabled }
    }

    suspend fun updatePlayerControlsMaterial(material: GlassMaterial) {
        editSettingsMirrored { it[playerControlsMaterialKey] = material.name }
    }

    suspend fun markInterestDone() {
        editSettingsMirrored { it[interestDoneKey] = true }
    }

    private val blockGaiaKey = booleanPreferencesKey("block_gaia")
    val blockGaia: Flow<Boolean> = context.appSettingsDataStore.data.map { it[blockGaiaKey] ?: false }

    suspend fun updateBlockGaia(enabled: Boolean) {
        editSettingsMirrored { it[blockGaiaKey] = enabled }
    }

    // ===== 首页布局 =====
    private val homeFeedSingleColumnKey = booleanPreferencesKey("home_feed_single_column")

    /** 首页推荐流是否使用单列大图布局，默认开启（预览图更大）。 */
    val homeFeedSingleColumn: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[homeFeedSingleColumnKey] ?: true }

    suspend fun setHomeFeedSingleColumn(enabled: Boolean) {
        editSettingsMirrored { it[homeFeedSingleColumnKey] = enabled }
    }

    // ===== 空降助手（SponsorBlock） =====
    private val sponsorBlockEnabledKey = booleanPreferencesKey("sponsor_block_enabled")

    /** 空降助手总开关，默认关闭。 */
    val sponsorBlockEnabled: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[sponsorBlockEnabledKey] ?: false }

    suspend fun updateSponsorBlockEnabled(enabled: Boolean) {
        editSettingsMirrored { it[sponsorBlockEnabledKey] = enabled }
    }

    private val sponsorBlockServerUrlKey = stringPreferencesKey("sponsor_block_server_url")

    /** 空降助手社区服务器地址，留空使用官方默认。 */
    val sponsorBlockServerUrl: Flow<String> =
        context.appSettingsDataStore.data.map { it[sponsorBlockServerUrlKey] ?: "" }

    suspend fun updateSponsorBlockServerUrl(url: String) {
        editSettingsMirrored { it[sponsorBlockServerUrlKey] = url.trim() }
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
        editSettingsMirrored { it[sponsorBlockUserIdKey] = next }
        return next
    }

    suspend fun regenerateSponsorBlockUserId(): String {
        editSettingsMirrored { it.remove(sponsorBlockUserIdKey) }
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
        editSettingsMirrored { it[sponsorBlockCategoryTypesKey] = json }
    }

    private val sponsorBlockToastKey = booleanPreferencesKey("sponsor_block_toast")

    /** 跳过提示 Toast，默认开启。 */
    val sponsorBlockToast: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[sponsorBlockToastKey] ?: true }

    suspend fun updateSponsorBlockToast(enabled: Boolean) {
        editSettingsMirrored { it[sponsorBlockToastKey] = enabled }
    }

    private val sponsorBlockMarkersKey = booleanPreferencesKey("sponsor_block_markers")

    /** 进度条片段标记，默认开启。 */
    val sponsorBlockMarkers: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[sponsorBlockMarkersKey] ?: true }

    suspend fun updateSponsorBlockMarkers(enabled: Boolean) {
        editSettingsMirrored { it[sponsorBlockMarkersKey] = enabled }
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
    private val prefetchVideoKey = booleanPreferencesKey("prefetch_video")
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
                prefetchVideo = prefs[prefetchVideoKey] ?: defaultPlaybackPrefs.prefetchVideo,
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
        editSettingsMirrored { it[enableHdrAnd8kKey] = enabled }
    }

    suspend fun updateDefaultVideoQuality(quality: Int) {
        editSettingsMirrored { it[defaultVideoQualityKey] = quality }
    }

    suspend fun updateDefaultAudioQuality(quality: Int) {
        editSettingsMirrored { it[defaultAudioQualityKey] = quality }
    }

    suspend fun updateForceHost(value: Int) {
        editSettingsMirrored { it[forceHostKey] = value }
    }

    suspend fun updateNeedTrial(enabled: Boolean) {
        editSettingsMirrored { it[needTrialKey] = enabled }
    }

    suspend fun updatePreferredCodec(codec: Int) {
        editSettingsMirrored { it[preferredCodecKey] = codec }
    }

    suspend fun updateEnableWebPlayback(enabled: Boolean) {
        editSettingsMirrored { it[enableWebPlaybackKey] = enabled }
    }

    suspend fun setBufferProfile(profile: PlayerBufferProfile) {
        editSettingsMirrored { it[playerBufferProfileKey] = profile.ordinal }
    }

    suspend fun setBackgroundPlayback(enabled: Boolean) {
        editSettingsMirrored { it[bgPlayKey] = enabled }
    }

    /** ColorOS 无缝过渡动画开关（默认开）；关闭退回覆盖层路径。 */
    suspend fun setSeamlessTransition(enabled: Boolean) {
        editSettingsMirrored { it[seamlessTransitionKey] = enabled }
    }

    /** 竖屏连播预加载开关（默认开）：见 `PlayerPlaybackPrefs.prefetchVideo`。 */
    suspend fun setPrefetchVideo(enabled: Boolean) {
        editSettingsMirrored { it[prefetchVideoKey] = enabled }
    }

    suspend fun setReportPlayback(enabled: Boolean) {
        editSettingsMirrored { it[reportPlaybackKey] = enabled }
    }

    suspend fun setPreferSoftwareDecode(enabled: Boolean) {
        editSettingsMirrored { it[preferSoftDecKey] = enabled }
    }

    suspend fun setDecoderFallback(enabled: Boolean) {
        editSettingsMirrored { it[decFallbackKey] = enabled }
    }

    suspend fun setAutoRotateFullscreen(enabled: Boolean) {
        editSettingsMirrored { it[autoRotateFullscreenKey] = enabled }
    }

    suspend fun setGestureSpeed(speed: Float) {
        editSettingsMirrored { it[gestureSpeedKey] = speed.coerceIn(0.25f, 3f) }
    }

    suspend fun setDoubleTapLike(enabled: Boolean) {
        editSettingsMirrored { it[doubleTapLikeKey] = enabled }
    }

    suspend fun setRepeatMode(mode: PlayerRepeatMode) {
        editSettingsMirrored { it[repeatModeKey] = mode.name }
    }

    suspend fun setVideoCdnMode(mode: VideoCdnMode) {
        editSettingsMirrored { it[videoCdnModeKey] = mode.name }
    }

    suspend fun setDanmaku(config: DanmakuConfig) {
        editSettingsMirrored {
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
        editSettingsMirrored {
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
