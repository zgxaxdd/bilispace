package com.naaammme.bbspace.feature.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class UserViewModel @Inject constructor(
    private val authRepo: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserUiState())
    val uiState: StateFlow<UserUiState> = _uiState.asStateFlow()

    init {
        // ⚠️ 不要在 init 里**同步**读账户信息（主线程磁盘读，实测 195ms）。
        //
        // `hiltViewModel()` 在**组合期**构造本 ViewModel，因此 init 块跑在主线程上；
        // 而 `AuthStore.getUserInfo()` 走 SharedPreferences —— 进程内首次访问该文件
        // 会**同步读盘**。StrictMode 实测抓到 `DiskReadViolation ~duration=195 ms`，
        // 堆栈落在 `AppNavHost.MainTabsScaffold` 的组合路径上。
        //
        // 这与上游 BiliPai 的 "move account session reads off main thread" 是同一问题。
        // 修法：把首次读移到 IO 线程。代价是首帧用户信息为空（未登录时本来也是空），
        // 一帧后回填 —— 远好于卡住主线程 195ms。
        viewModelScope.launch {
            val user = withContext(Dispatchers.IO) { authRepo.getUserInfo() }
            _uiState.update { it.copy(user = user) }
        }
        viewModelScope.launch {
            authRepo.currentMidFlow
                .collect { mid ->
                    if (mid > 0) {
                        fetchMineInfo()
                    } else {
                        _uiState.value = UserUiState()
                    }
                }
        }
    }

    fun fetchMineInfo() {
        val credential = authRepo.getSavedCredential() ?: return
        viewModelScope.launch {
            authRepo.fetchMineInfo(credential).onSuccess {
                if (it.mid == 0L) {
                    _uiState.update { state ->
                        state.copy(
                            user = null,
                            showAccountExpiredDialog = true
                        )
                    }
                } else {
                    _uiState.update { state ->
                        state.copy(
                            user = it,
                            showAccountExpiredDialog = false
                        )
                    }
                }
            }
        }
    }

    fun dismissAccountExpiredDialog() {
        _uiState.update { it.copy(showAccountExpiredDialog = false) }
    }
}
