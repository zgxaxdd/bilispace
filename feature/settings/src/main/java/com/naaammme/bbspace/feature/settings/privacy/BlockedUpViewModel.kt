package com.naaammme.bbspace.feature.settings.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.common.relation.UpBlocker
import com.naaammme.bbspace.core.settings.blocked.BlockedUpEntry
import com.naaammme.bbspace.core.settings.blocked.BlockedUpStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「拉黑名单」页状态（NX-08）。
 *
 * 三态口径对齐 `docs/ai-loop/UI-04-三态覆盖清单.md`：**错误 > 空 > 加载**——
 * 有错误就不显示"名单为空"（否则用户会把"读不出来"当成"我没拉黑过人"）。
 */
data class BlockedUpUiState(
    val entries: List<BlockedUpEntry> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val message: String? = null
)

/**
 * NX-08：本地拉黑名单的**查看 / 撤销**。
 *
 * 撤销契约与 PT-06 的拉黑对称：**先本地生效（[BlockedUpStore.remove]），再异步同步
 * （[UpBlocker] → 既有 gRPC `SpaceRepository.modifyBlacklist`），失败不回滚本地**。
 * 为什么不回滚：本地已经"看不见这条"了，回滚会把刚撤销的条目又弹回来，用户会以为没生效；
 * 而服务端解除失败只影响"该 UP 是否还能刷到"，本地名单的语义是"我拉黑过谁"。
 * （这一点与拉黑侧**方向相反但理由相同**：都优先保证用户在当前界面上看到的结果稳定。）
 */
@HiltViewModel
class BlockedUpViewModel @Inject constructor(
    private val store: BlockedUpStore,
    private val upBlocker: UpBlocker
) : ViewModel() {

    private val _state = MutableStateFlow(BlockedUpUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val entries = runCatchingCancellable { store.list() }
                .getOrElse { e ->
                    Logger.w(TAG) { "读取本地拉黑名单失败：${e.message}" }
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = "读取本地名单失败，请重试"
                    )
                    return@launch
                }
            _state.value = _state.value.copy(entries = entries, isLoading = false, errorMessage = null)
        }
    }

    /** 撤销：本地先移除 → 再发 gRPC；失败保留"已本地移除"的结果并如实提示。 */
    fun unblock(entry: BlockedUpEntry) {
        viewModelScope.launch {
            store.remove(entry.mid)
            runCatchingCancellable { upBlocker.modifyBlacklist(entry.mid, isBlack = false) }
                .onSuccess {
                    Logger.d(TAG) { "撤销拉黑已同步 mid=${entry.mid}" }
                    _state.value = _state.value.copy(message = "已撤销拉黑")
                }
                .onFailure { e ->
                    Logger.w(TAG) { "撤销拉黑同步失败 mid=${entry.mid} msg=${e.message}（本地已移除）" }
                    _state.value = _state.value.copy(
                        message = "已从本地名单移除，但服务端解除失败（可稍后在网页端重试）"
                    )
                }
            load()
        }
    }

    fun consumeMessage() {
        if (_state.value.message.isNullOrEmpty()) return
        _state.value = _state.value.copy(message = null)
    }

    private companion object {
        const val TAG = "BlockedUpViewModel"
    }
}

/** 名单第一行的标题：没有名字时不显示空白（story/relate 来源没有 UP 名）。 */
internal fun blockedUpDisplayName(entry: BlockedUpEntry): String =
    entry.name?.takeIf(String::isNotBlank) ?: "UP ${entry.mid}"

/**
 * 拉黑时间文本（`yyyy-MM-dd HH:mm`）。
 *
 * 用 [Locale.ROOT] 固定格式（与 `WebFeedMapper.formatDuration` 同一条理由：
 * 默认 locale 会在部分语言下输出本地数字/历法）。
 */
internal fun formatBlockedAt(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))

/** 同步状态文本：列表要能一眼看出"这条只在本地"。 */
internal fun blockedUpSyncLabel(entry: BlockedUpEntry): String =
    if (entry.synced) "已同步" else "仅本地（未同步）"
