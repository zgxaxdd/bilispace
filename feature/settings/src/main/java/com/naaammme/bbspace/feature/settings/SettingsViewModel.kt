package com.naaammme.bbspace.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.auth.AuthRepository
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.settings.FeedApiType
import com.naaammme.bbspace.core.designsystem.glass.GlassMaterial
import com.naaammme.bbspace.core.designsystem.theme.AnimationSpeed
import com.naaammme.bbspace.core.designsystem.theme.CornerStyle
import com.naaammme.bbspace.core.designsystem.theme.FrameRateMode
import com.naaammme.bbspace.core.designsystem.theme.PaletteStyle
import com.naaammme.bbspace.core.designsystem.theme.ThemeConfig
import com.naaammme.bbspace.core.designsystem.theme.ThemeMode
import com.naaammme.bbspace.core.designsystem.theme.TransitionStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedColorSpec
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedContrastLevel
import com.naaammme.bbspace.core.designsystem.theme.scheme.SeedPaletteStyle
import com.naaammme.bbspace.core.model.PlayerSettingsState
import com.naaammme.bbspace.core.model.player.CdnRegionPolicy
import com.naaammme.bbspace.core.sponsorblock.SbCategory
import com.naaammme.bbspace.core.sponsorblock.SbSkipType
import com.naaammme.bbspace.core.sponsorblock.SponsorBlockClient
import androidx.compose.ui.graphics.Color
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val authRepo: AuthRepository,
    private val sponsorBlockClient: SponsorBlockClient
) : ViewModel() {

    val hdFeedAvailable = MutableStateFlow(false)

    // ===== 首页布局 =====
    val homeFeedSingleColumn = appSettings.homeFeedSingleColumn.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true
    )

    fun updateHomeFeedSingleColumn(enabled: Boolean) {
        viewModelScope.launch { appSettings.setHomeFeedSingleColumn(enabled) }
    }

    // ===== 推荐来源（PT-10） =====
    val feedApiType = appSettings.feedApiType.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FeedApiType.MOBILE
    )

    fun updateFeedApiType(type: FeedApiType) {
        viewModelScope.launch { appSettings.updateFeedApiType(type) }
    }

    // ===== 空降助手 =====
    val sponsorBlockEnabled = appSettings.sponsorBlockEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val sponsorBlockCategoryTypes = appSettings.sponsorBlockCategoryTypes.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyMap()
    )

    val sponsorBlockToast = appSettings.sponsorBlockToast.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true
    )

    val sponsorBlockMarkers = appSettings.sponsorBlockMarkers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true
    )

    val sponsorBlockServerUrl = appSettings.sponsorBlockServerUrl.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )

    val sponsorBlockUserId = appSettings.sponsorBlockUserId.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    val sponsorBlockServerTestResult = MutableStateFlow<String?>(null)

    fun updateSponsorBlockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                // 首次开启时确保身份 ID 已生成
                appSettings.ensureSponsorBlockUserId()
            }
            appSettings.updateSponsorBlockEnabled(enabled)
        }
    }

    fun updateSponsorBlockCategoryType(category: SbCategory, type: SbSkipType) {
        viewModelScope.launch {
            val next = sponsorBlockCategoryTypes.value.toMutableMap()
            next[category.wire] = type.ordinal
            appSettings.updateSponsorBlockCategoryTypes(next)
        }
    }

    fun updateSponsorBlockToast(enabled: Boolean) {
        viewModelScope.launch { appSettings.updateSponsorBlockToast(enabled) }
    }

    fun updateSponsorBlockMarkers(enabled: Boolean) {
        viewModelScope.launch { appSettings.updateSponsorBlockMarkers(enabled) }
    }

    fun updateSponsorBlockServerUrl(url: String) {
        viewModelScope.launch { appSettings.updateSponsorBlockServerUrl(url) }
    }

    fun testSponsorBlockServer() {
        viewModelScope.launch {
            sponsorBlockServerTestResult.value = "正在测试…"
            val url = sponsorBlockServerUrl.value.ifBlank { SponsorBlockClient.DEFAULT_SERVER }
            sponsorBlockServerTestResult.value = if (sponsorBlockClient.checkServer(url)) {
                "连接正常"
            } else {
                "连接失败 请检查地址或网络"
            }
        }
    }

    fun regenerateSponsorBlockUserId() {
        viewModelScope.launch { appSettings.regenerateSponsorBlockUserId() }
    }

    fun sponsorBlockSkipTypeFor(category: SbCategory): SbSkipType =
        sponsorBlockCategoryTypes.value[category.wire]
            ?.let { ordinal -> SbSkipType.entries.getOrNull(ordinal) }
            ?: category.defaultSkipType

    val themeConfig = appSettings.themeConfig.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ThemeConfig()
    )

    val hdFeed = appSettings.hdFeed.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val personalizedRcmd = appSettings.personalizedRcmd.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true
    )

    val lessonsMode = appSettings.lessonsMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val teenagersMode = appSettings.teenagersMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val teenagersAge = appSettings.teenagersAge.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        16
    )

    val enableHdrAnd8k = appSettings.enableHdrAnd8k.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val defaultVideoQuality = appSettings.defaultVideoQuality.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        64
    )

    val defaultAudioQuality = appSettings.defaultAudioQuality.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val forceHost = appSettings.forceHost.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val needTrial = appSettings.needTrial.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val preferredCodec = appSettings.preferredCodec.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        2
    )

    val enableWebPlayback = appSettings.enableWebPlayback.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    // ===== CDN 智能选线（实验，W4 数据链路的 UI 入口）=====
    /**
     * 播放偏好快照：CDN 插件四项（属地优选/目标 host/自定义规则/严格模式）都从这里读。
     *
     * 不再拆四条独立 Flow：这四项在设置页**同屏编辑**，四条 Flow = 四个状态源，
     * 开关与文本写入后的回显会各自晚一帧、互相打架。单一快照的初值取
     * [PlayerSettingsState] 的出厂默认（全部关闭/为空），与 `AppSettings.state`
     * 的默认值一致，首帧不会出现"先亮一下再关掉"的开关滑动。
     */
    val playerSettings = appSettings.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlayerSettingsState()
    )

    fun updateCdnRegionEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setCdnRegionEnabled(enabled) }
    }

    /** 严格只用自定义线路：见 `PlayerPlaybackPrefs.cdnStrictCustomCdn`。 */
    fun updateCdnStrictCustomCdn(enabled: Boolean) {
        viewModelScope.launch { appSettings.setCdnStrictCustomCdn(enabled) }
    }

    fun updateCdnRegionHosts(hosts: List<String>) {
        viewModelScope.launch { appSettings.setCdnRegionHosts(hosts) }
    }

    fun updateCdnCustomRules(rules: List<CdnRegionPolicy.CustomRule>) {
        viewModelScope.launch { appSettings.setCdnCustomRules(rules) }
    }

    val useSystemDns = appSettings.useSystemDns.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    /** Q-3：现场诊断日志开关（量产默认关；debug 包不看它、始终可见）。 */
    val diagnosticLogEnabled = appSettings.diagnosticLogEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false
    )

    /**
     * 「省电模式」：视频画面用 SurfaceView（**默认开**，重进播放页生效）。
     *
     * 初值必须与 `AppSettings.playerSurfaceView` 的默认值（`?: true`）一致：
     * 给 `false` 会让设置页首帧显示"关"、数据到达后再跳成"开"（开关自己滑一下）。
     */
    val playerSurfaceView = appSettings.playerSurfaceView.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true
    )

    val topProgressiveFade = appSettings.topProgressiveFade.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true
    )

    fun updateTopProgressiveFade(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateTopProgressiveFade(enabled)
        }
    }

    /**
     * 播放页控件模糊开关。播放器侧不直接读本 ViewModel：经 [AppSettings] 的 Flow
     * 在 VideoScreen 组合期收集后以参数下发（叶子组件保持无 ViewModel 依赖）。
     */
    val playerControlsBlur = appSettings.playerControlsBlur.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true
    )

    fun updatePlayerControlsBlur(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updatePlayerControlsBlur(enabled)
        }
    }

    /**
     * 播放页控件玻璃材质（磨砂 / 液态），与全局 [ThemeConfig.glassMaterial] 独立。
     * 播放控件叠在实时视频画面上，液态强折射在大面积动态画面上容易糊，故可单独选。
     */
    val playerControlsMaterial = appSettings.playerControlsMaterial.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GlassMaterial.FROSTED
    )

    fun updatePlayerControlsMaterial(material: GlassMaterial) {
        viewModelScope.launch {
            appSettings.updatePlayerControlsMaterial(material)
        }
    }

    val fixBottomBar = appSettings.fixBottomBar.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true
    )

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            appSettings.updateThemeMode(mode)
        }
    }

    fun updateSeedColor(color: Color) {
        viewModelScope.launch {
            appSettings.updateSeedColor(color)
        }
    }

    fun updateUseDynamicColor(use: Boolean) {
        viewModelScope.launch {
            appSettings.updateUseDynamicColor(use)
        }
    }

    fun updatePaletteStyle(style: PaletteStyle) {
        viewModelScope.launch {
            appSettings.updatePaletteStyle(style)
        }
    }

    // ── 种子配色三维度（22 风格 / 色彩标准 / 对比度）──────────────────────────
    fun updateSeedPaletteStyle(style: SeedPaletteStyle) {
        viewModelScope.launch {
            appSettings.updateSeedPaletteStyle(style)
        }
    }

    fun updateSeedColorSpec(spec: SeedColorSpec) {
        viewModelScope.launch {
            appSettings.updateSeedColorSpec(spec)
        }
    }

    fun updateSeedContrastLevel(level: SeedContrastLevel) {
        viewModelScope.launch {
            appSettings.updateSeedContrastLevel(level)
        }
    }

    /**
     * 全局拖拽反馈开关（上游 BiliPai 同名功能）。
     *
     * 关掉后按钮/胶囊**仍会鼓起**，只是不再跟随手指沿受力方向拉伸 ——
     * 形变由 `press` 与 `position` 两个独立分量驱动，本开关只门控后者
     * （见 `core:designsystem` 的 `motion/AppElasticPress.kt`）。
     */
    fun updateGlobalDragFeedback(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateGlobalDragFeedback(enabled)
        }
    }

    fun updateSwapBaseColors(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateSwapBaseColors(enabled)
        }
    }

    fun updateFontScale(scale: Float) {
        viewModelScope.launch {
            appSettings.updateFontScale(scale)
        }
    }

    fun updateUiScale(scale: Float) {
        viewModelScope.launch {
            appSettings.updateUiScale(scale)
        }
    }

    fun updateRoundScreenSafePaddingScale(scale: Float) {
        viewModelScope.launch {
            appSettings.updateRoundScreenSafePaddingScale(scale)
        }
    }

    fun updatePullRefreshDistance(distanceDp: Float) {
        viewModelScope.launch {
            appSettings.updatePullRefreshDistance(distanceDp)
        }
    }

    fun updateAnimationSpeed(speed: AnimationSpeed) {
        viewModelScope.launch {
            appSettings.updateAnimationSpeed(speed)
        }
    }

    fun updateTransitionStyle(style: TransitionStyle) {
        viewModelScope.launch {
            appSettings.updateTransitionStyle(style)
        }
    }

    fun updateIsPureBlack(isPure: Boolean) {
        viewModelScope.launch {
            appSettings.updateIsPureBlack(isPure)
        }
    }

    fun updateFrameRateMode(mode: FrameRateMode) {
        viewModelScope.launch {
            appSettings.updateFrameRateMode(mode)
        }
    }

    fun updateCornerStyle(style: CornerStyle) {
        viewModelScope.launch {
            appSettings.updateCornerStyle(style)
        }
    }

    fun updateUiStyle(style: UiStyle) {
        viewModelScope.launch {
            appSettings.updateUiStyle(style)
        }
    }

    fun updateGlassMaterial(material: com.naaammme.bbspace.core.designsystem.glass.GlassMaterial) {
        viewModelScope.launch {
            appSettings.updateGlassMaterial(material)
        }
    }

    fun updateGlassStrength(strength: Float) {
        viewModelScope.launch {
            appSettings.updateGlassStrength(strength)
        }
    }

    fun updateHdFeed(enabled: Boolean) {
        viewModelScope.launch {
            refreshHdFeedAvailable()
            if (enabled && !hdFeedAvailable.value) {
                appSettings.updateHdFeed(false)
            } else {
                appSettings.updateHdFeed(enabled)
            }
        }
    }

    fun updatePersonalizedRcmd(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updatePersonalizedRcmd(enabled)
        }
    }

    fun updateLessonsMode(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateLessonsMode(enabled)
        }
    }

    fun updateTeenagersMode(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateTeenagersMode(enabled)
        }
    }

    fun updateTeenagersAge(age: Int) {
        viewModelScope.launch {
            appSettings.updateTeenagersAge(age)
        }
    }

    fun updateEnableHdrAnd8k(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateEnableHdrAnd8k(enabled)
        }
    }

    fun updateDefaultVideoQuality(quality: Int) {
        viewModelScope.launch {
            appSettings.updateDefaultVideoQuality(quality)
        }
    }

    fun updateDefaultAudioQuality(quality: Int) {
        viewModelScope.launch {
            appSettings.updateDefaultAudioQuality(quality)
        }
    }

    fun updateForceHost(value: Int) {
        viewModelScope.launch {
            appSettings.updateForceHost(value)
        }
    }

    fun updateNeedTrial(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateNeedTrial(enabled)
        }
    }

    fun updatePreferredCodec(codec: Int) {
        viewModelScope.launch {
            appSettings.updatePreferredCodec(codec)
        }
    }

    fun updateEnableWebPlayback(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateEnableWebPlayback(enabled)
        }
    }

    fun updateUseSystemDns(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateUseSystemDns(enabled)
        }
    }

    /** Q-3：现场诊断日志开关（写入后由 AppInitializer 的收集器同步到 DiagnosticLog）。 */
    fun updateDiagnosticLogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateDiagnosticLogEnabled(enabled)
        }
    }

    /** 「省电模式」开关：见 [AppSettings.playerSurfaceView]。 */
    fun updatePlayerSurfaceView(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updatePlayerSurfaceView(enabled)
        }
    }

    fun updateFixBottomBar(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.updateFixBottomBar(enabled)
        }
    }

    fun refreshHdFeedAvailable() {
        val available = authRepo.hasHdAccessKeyForCurrent()
        hdFeedAvailable.value = available
        if (!available) {
            viewModelScope.launch {
                appSettings.updateHdFeed(false)
            }
        }
    }

    fun resetAllSettings() {
        viewModelScope.launch {
            appSettings.resetAllSettings()
        }
    }
}
