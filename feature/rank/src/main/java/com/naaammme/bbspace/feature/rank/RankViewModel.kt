package com.naaammme.bbspace.feature.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.model.rank.RankEntry
import com.naaammme.bbspace.core.model.rank.RankSeriesEntry
import com.naaammme.bbspace.core.model.rank.RankType
import com.naaammme.bbspace.core.common.error.UserFacingError
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.rank.RankRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RankUiState(
    val type: RankType = RankType.POPULAR,
    val seriesList: List<RankSeriesEntry> = emptyList(),
    val seriesNumber: Int? = null,
    val entries: List<RankEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class RankViewModel @Inject constructor(
    private val repo: RankRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RankUiState(type = DEFAULT_TYPE))
    val uiState: StateFlow<RankUiState> = _uiState.asStateFlow()

    /** 每类型的缓存：切回已看过的分区不重复请求 */
    private val cache = mutableMapOf<String, CachedRankPage>()

    /** 当前在途加载：新加载开始前取消，防止慢响应晚到覆盖新类型的数据 */
    private var loadJob: Job? = null

    init {
        load(type = DEFAULT_TYPE, refreshing = false)
    }

    fun select(type: RankType) {
        if (_uiState.value.type == type) return
        cacheCurrent()
        val cached = cache[cacheKey(type)]
        if (cached != null) {
            _uiState.update {
                it.copy(
                    type = type,
                    entries = cached.entries,
                    seriesNumber = cached.seriesNumber,
                    canLoadMore = cached.canLoadMore,
                    isLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    errorMessage = null
                )
            }
            return
        }
        load(type = type, refreshing = false)
    }

    fun selectSeries(number: Int) {
        if (_uiState.value.seriesNumber == number) return
        _uiState.update { it.copy(seriesNumber = number) }
        load(type = RankType.SERIES, refreshing = false)
    }

    fun refresh() {
        cache.remove(cacheKey(_uiState.value.type))
        load(type = _uiState.value.type, refreshing = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.canLoadMore || state.isLoading || state.isLoadingMore || state.isRefreshing) return
        val type = state.type
        val nextPage = when (type.kind) {
            com.naaammme.bbspace.core.model.rank.RankKind.POPULAR,
            com.naaammme.bbspace.core.model.rank.RankKind.PRECIOUS -> (pageByType[type] ?: 1) + 1
            else -> return
        }
        pageByType[type] = nextPage
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            runCatchingCancellable {
                when (type.kind) {
                    com.naaammme.bbspace.core.model.rank.RankKind.POPULAR ->
                        repo.fetchPopular(page = nextPage)
                    else -> repo.fetchPrecious(page = nextPage)
                }.map(RankEntry::Video)
            }.fold(
                onSuccess = { more ->
                    _uiState.update { cur ->
                        val merged = (cur.entries + more).distinctBy(RankEntry::key)
                        val next = cur.copy(
                            entries = merged,
                            isLoadingMore = false,
                            canLoadMore = more.isNotEmpty()
                        )
                        cacheCurrentLocked(next)
                        next
                    }
                },
                onFailure = { error ->
                    pageByType[type] = nextPage - 1
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            errorMessage = UserFacingError.messageOf(error)
                        )
                    }
                }
            )
        }
    }

    fun consumeError() {
        if (_uiState.value.errorMessage != null) {
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun load(type: RankType, refreshing: Boolean, retryAllowed: Boolean = true) {
        // 取消在途加载：快速切换分区时旧响应若晚到，会把新分区的数据整个覆盖掉
        //（runCatchingCancellable 会把取消原样传播，onFailure 不会收到它）。
        loadJob?.cancel()
        val seriesNumber = _uiState.value.seriesNumber
            ?: _uiState.value.seriesList.firstOrNull()?.number
        _uiState.update {
            it.copy(
                type = type,
                isLoading = !refreshing && it.entries.isEmpty(),
                isRefreshing = refreshing,
                isLoadingMore = false,
                errorMessage = null,
                canLoadMore = false
            )
        }
        loadJob = viewModelScope.launch {
            runCatchingCancellable {
                when (type.kind) {
                    com.naaammme.bbspace.core.model.rank.RankKind.POPULAR -> {
                        pageByType[type] = 1
                        repo.fetchPopular(page = 1).map(RankEntry::Video)
                    }

                    com.naaammme.bbspace.core.model.rank.RankKind.UGC -> {
                        pageByType.remove(type)
                        repo.fetchRanking(rid = type.rid).map(RankEntry::Video)
                    }

                    com.naaammme.bbspace.core.model.rank.RankKind.PGC -> {
                        pageByType.remove(type)
                        repo.fetchPgcRank(seasonType = type.seasonType).map(RankEntry::Pgc)
                    }

                    com.naaammme.bbspace.core.model.rank.RankKind.SERIES -> {
                        pageByType.remove(type)
                        ensureSeriesList()
                        val number = _uiState.value.seriesNumber
                            ?: _uiState.value.seriesList.firstOrNull()?.number
                            ?: error("暂无可用的每周必看")
                        _uiState.update { it.copy(seriesNumber = number) }
                        repo.fetchSeriesOne(number = number).map(RankEntry::Video)
                    }

                    com.naaammme.bbspace.core.model.rank.RankKind.PRECIOUS -> {
                        pageByType[type] = 1
                        repo.fetchPrecious(page = 1).map(RankEntry::Video)
                    }
                }
            }.fold(
                onSuccess = { entries ->
                    _uiState.update { cur ->
                        cur.copy(
                            entries = entries.distinctBy(RankEntry::key),
                            isLoading = false,
                            isRefreshing = false,
                            // 每周必看重新选择期数后刷新选中号
                            seriesNumber = if (type.kind == com.naaammme.bbspace.core.model.rank.RankKind.SERIES) {
                                seriesNumber ?: cur.seriesNumber
                            } else {
                                cur.seriesNumber
                            },
                            canLoadMore = type.kind == com.naaammme.bbspace.core.model.rank.RankKind.POPULAR ||
                                type.kind == com.naaammme.bbspace.core.model.rank.RankKind.PRECIOUS,
                            errorMessage = null
                        )
                    }
                    cacheCurrent()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = UserFacingError.messageOf(error)
                        )
                    }
                    // 初次进入（列表为空）的失败自动重试一次：设备冷启动后的首个
                    // Web 请求在弱网/风控下偶发被拒，手动刷新却能成功——与其让
                    // 用户看到错误再手动重试，不如自动补一发；只补一次，再失败
                    // 就如实展示错误卡片（errorMessage 已接入网格）。
                    if (!refreshing && retryAllowed && _uiState.value.entries.isEmpty()) {
                        viewModelScope.launch {
                            delay(1_500)
                            load(type = type, refreshing = false, retryAllowed = false)
                        }
                    }
                }
            )
        }
    }

    private suspend fun ensureSeriesList() {
        if (_uiState.value.seriesList.isNotEmpty()) return
        val list = repo.fetchSeriesList().sortedByDescending(RankSeriesEntry::number)
        _uiState.update { it.copy(seriesList = list) }
    }

    private fun cacheKey(type: RankType): String = when (type.kind) {
        com.naaammme.bbspace.core.model.rank.RankKind.SERIES ->
            "${type.name}#${_uiState.value.seriesNumber ?: "latest"}"
        else -> type.name
    }

    private fun cacheCurrent() {
        val cur = _uiState.value
        cacheCurrentLocked(cur)
    }

    private fun cacheCurrentLocked(cur: RankUiState) {
        // 加载在途（entries 还是空）时的快照不进缓存：否则"切走再切回"会把
        // 这份空快照当命中直接铺出来，表现为"暂无内容"，必须手动刷新才有数据。
        // 真正加载完的空分区（isLoading/isRefreshing 都已复位）仍照常缓存。
        if (cur.entries.isEmpty() && (cur.isLoading || cur.isRefreshing)) return
        cache[cacheKey(cur.type)] = CachedRankPage(
            entries = cur.entries,
            seriesNumber = cur.seriesNumber,
            canLoadMore = cur.canLoadMore
        )
    }

    private val pageByType = mutableMapOf<RankType, Int>()

    private data class CachedRankPage(
        val entries: List<RankEntry>,
        val seriesNumber: Int?,
        val canLoadMore: Boolean
    )

    private companion object {
        const val TAG = "RankViewModel"
        val DEFAULT_TYPE = RankType.POPULAR
    }
}
