package com.naaammme.bbspace.feature.settings.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.settings.AppSettings
import com.naaammme.bbspace.core.auth.AuthStore
import com.naaammme.bbspace.core.auth.CacheManager
import com.naaammme.bbspace.core.model.LoginCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.naaammme.bbspace.core.common.cache.PlaybackCacheControl
import javax.inject.Inject

data class PrivacyUiState(
    val deviceInfo: Map<String, String> = emptyMap(),
    val sessionInfo: Map<String, String> = emptyMap(),
    val ticketInfo: Map<String, String> = emptyMap(),
    val guestInfo: Map<String, String> = emptyMap(),
    val regionCode: String = "",
    val hdInfo: Map<String, String> = emptyMap(),
    val accounts: List<LoginCredential> = emptyList(),
    val currentMid: Long = 0L,
    val importResult: String? = null,
    /** PB-03：播放字节缓存占用（字节）。 */
    val playbackCacheBytes: Long = 0L,
    /** PB-03：清理播放缓存的结果提示（播放中被拒绝时也要说清，不能弹"已清除"）。 */
    val playbackCacheToast: String? = null
)

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val authStore: AuthStore,
    private val cacheManager: CacheManager,
    private val appSettings: AppSettings,
    /** PB-03：播放字节缓存的占用/清理（接口在 core:common，实现在 infra:player）。 */
    private val playbackCacheControl: PlaybackCacheControl
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrivacyUiState())
    val uiState: StateFlow<PrivacyUiState> = _uiState.asStateFlow()

    val blockGaia = appSettings.blockGaia.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    init {
        refresh()
        refreshPlaybackCacheSize()
    }

    fun refresh() {
        _uiState.value = PrivacyUiState(
            deviceInfo = cacheManager.getDeviceInfo(),
            sessionInfo = mapOf(
                "guestId" to cacheManager.guestId,
                "sessionId" to cacheManager.sessionId,
                "loginSessionId" to cacheManager.loginSessionId
            ),
            ticketInfo = cacheManager.getTicketInfo(),
            guestInfo = mapOf("guestId" to cacheManager.guestId),
            regionCode = cacheManager.getRegionCode(),
            hdInfo = mapOf(
                "currentMid" to authStore.mid.toString(),
                "hasHdAccessKey" to authStore.hasHdAccessKeyForCurrent().toString(),
                "hdAccessKey" to authStore.getHdAccessKeyForCurrent()
            ),
            accounts = authStore.getAllAccounts(),
            currentMid = authStore.mid
        )
    }

    fun setBlockGaia(enabled: Boolean) {
        viewModelScope.launch { appSettings.updateBlockGaia(enabled) }
    }

    fun exportAllAccounts(): String = authStore.exportAllAccounts()

    fun importAccounts(json: String) {
        val result = authStore.importAccounts(json)
        _uiState.value = _uiState.value.copy(
            importResult = if (result.isNotEmpty()) "导入 ${result.size} 个账号" else "导失败你写错",
            accounts = authStore.getAllAccounts()
        )
    }

    fun clearImportResult() {
        _uiState.value = _uiState.value.copy(importResult = null)
    }

    fun clearImageCache() {
        // Coil 磁盘缓存在调用线程同步清理，必须切 IO 避免主线程卡顿
        viewModelScope.launch(Dispatchers.IO) {
            cacheManager.clearImageCache()
        }
    }

    fun clearAllCache() {
        viewModelScope.launch(Dispatchers.IO) {
            cacheManager.clearAllCache()
        }
        refresh()
    }

    // ===== PB-03：播放字节缓存（占用 + 清理）=====
    //
    // 刻意**不并进 clearAllCache()**：「清除所有缓存」是同步菜单动作，而播放缓存清理
    // ① 是挂起的、② 有"播放中拒绝"的守卫 —— 混进去会让"已清除所有缓存"在播放中变成假话。
    // 播放缓存给独立的按钮与独立的提示。

    fun refreshPlaybackCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { playbackCacheControl.cachedBytes() }.getOrDefault(0L)
            _uiState.value = _uiState.value.copy(playbackCacheBytes = bytes)
        }
    }

    /**
     * 清理播放缓存。**播放中会被拒绝**（[PlaybackCacheControl.clear] 返回 false）——
     * 这时必须如实告诉用户"没清"，而不是弹一个"已清除"。
     */
    fun clearPlaybackCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val cleared = runCatching { playbackCacheControl.clear() }.getOrDefault(false)
            val bytes = runCatching { playbackCacheControl.cachedBytes() }.getOrDefault(0L)
            _uiState.value = _uiState.value.copy(
                playbackCacheBytes = bytes,
                playbackCacheToast = if (cleared) "已清除播放缓存" else "正在播放，暂不能清理播放缓存"
            )
        }
    }

    fun consumePlaybackCacheToast() {
        if (_uiState.value.playbackCacheToast == null) return
        _uiState.value = _uiState.value.copy(playbackCacheToast = null)
    }
}

/**
 * 字节数的人读文本（PB-03：设置页显示播放缓存占用）。
 *
 * 纯函数、`Locale.ROOT`（与 `WebFeedMapper.formatDuration` 同一条理由：默认 locale
 * 会在部分语言下输出本地数字）。1 位小数、KB/MB/GB 三档 —— 与系统设置里的缓存显示同量级。
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.ROOT, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
    return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0)
}
