package com.naaammme.bbspace.feature.home.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.error.UserFacingError
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.live.LiveRecommendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HomeLiveViewModel @Inject constructor(
    private val repository: LiveRecommendRepository
) : ViewModel() {

    companion object {
        private const val TAG = "HomeLiveViewModel"
    }

    private val _uiState = MutableStateFlow(HomeLiveUiState())
    val uiState = _uiState.asStateFlow()

    private var nextPage = 1
    private var relationPage = 1
    private var loginEvent = 1
    private var hasMore = true
    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        refresh()
    }

    fun refresh() {
        // 并发守卫（对齐 HomeArticleViewModel 的同款写法）：必须带 hasLoaded 前缀！
        // HomeLiveUiState 的初始态就是 isRefreshing=true（首屏骨架由它渲染），
        // 裸判 isRefreshing 会让首次 refresh() 永远被自己的守卫拦截——直播列表
        // 永远停在骨架、连请求都发不出去（真机实测：零 DNS、零异常、零超时）。
        // hasLoaded=false 的首载阶段放行；hasLoaded=true 后才按刷新互斥拦重入。
        val state = _uiState.value
        if (hasLoaded && (state.isRefreshing || state.isLoadingMore)) return
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        errorMessage = null
                    )
                }
                val page = repository.fetchRecommendPage(
                    page = 1,
                    relationPage = relationPage,
                    isRefresh = hasLoaded,
                    loginEvent = loginEvent
                )
                _uiState.update {
                    it.copy(
                        upList = page.upList,
                        items = page.items
                    )
                }
                nextPage = 2
                hasMore = page.hasMore
                hasLoaded = true
                loginEvent = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = UserFacingError.logAndMessage(TAG, e, "刷新直播推荐失败"))
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isRefreshing || state.isLoadingMore || !hasMore) return
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isLoadingMore = true,
                        errorMessage = null
                    )
                }
                val page = repository.fetchRecommendPage(
                    page = nextPage,
                    relationPage = relationPage,
                    isRefresh = false,
                    loginEvent = loginEvent
                )
                _uiState.update {
                    it.copy(
                        upList = it.upList ?: page.upList,
                        items = it.items + page.items
                    )
                }
                nextPage++
                hasMore = page.hasMore
                hasLoaded = true
                loginEvent = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = UserFacingError.logAndMessage(TAG, e, "加载更多直播推荐失败"))
                }
            } finally {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }
}
