package com.naaammme.bbspace.feature.dynamic.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.AuthProvider
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.dynamic.DynamicRepository
import com.naaammme.bbspace.feature.dynamic.navigation.DYNAMIC_DETAIL_OPUS_ID_ARG
import com.naaammme.bbspace.feature.dynamic.navigation.DYNAMIC_DETAIL_OPUS_TYPE_ARG
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DynamicDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: DynamicRepository,
    private val authProvider: AuthProvider
) : ViewModel() {

    val opusId: String = savedStateHandle.get<String>(DYNAMIC_DETAIL_OPUS_ID_ARG) ?: ""
    private val opusType: Int = savedStateHandle.get<Int>(DYNAMIC_DETAIL_OPUS_TYPE_ARG) ?: 0

    private val _uiState = MutableStateFlow(DynamicDetailUiState())
    val uiState: StateFlow<DynamicDetailUiState> = _uiState.asStateFlow()

    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val msg: SharedFlow<String> = _msg.asSharedFlow()

    private var likeBusy = false

    init {
        if (opusId.isNotBlank()) {
            load()
        }
    }

    fun retry() {
        if (opusId.isNotBlank()) {
            load()
        }
    }

    /** 动态详情点赞/取消点赞 */
    fun toggleLike() {
        val detail = _uiState.value.detail ?: return
        val stats = detail.stats ?: return
        if (opusId.isBlank() || likeBusy) return
        if (authProvider.mid <= 0L) {
            _msg.tryEmit("请先登录")
            return
        }
        val target = !stats.liked
        likeBusy = true
        viewModelScope.launch {
            runCatching { repo.thumbDyn(opusId, target) }.fold(
                onSuccess = {
                    likeBusy = false
                    _uiState.update {
                        val current = it.detail ?: return@update it
                        val s = current.stats ?: return@update it
                        it.copy(
                            detail = current.copy(
                                stats = s.copy(
                                    liked = target,
                                    like = (s.like + if (target) 1L else -1L).coerceAtLeast(0L)
                                )
                            )
                        )
                    }
                    _msg.tryEmit(if (target) "点赞成功" else "已取消点赞")
                },
                onFailure = { e ->
                    likeBusy = false
                    _msg.tryEmit(e.message ?: "点赞失败")
                }
            )
        }
    }

    private fun load() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val detail = repo.fetchOpusDetail(opusId, opusType)
                _uiState.update {
                    it.copy(detail = detail, isLoading = false, errorMessage = null)
                }
            } catch (e: Exception) {
                Logger.e(TAG, e) { "加载动态详情失败" }
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "加载失败")
                }
            }
        }
    }

    private companion object {
        const val TAG = "DynamicDetailViewModel"
    }
}
