package com.naaammme.bbspace.feature.video.swipesource

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import com.naaammme.bbspace.core.swipe.SwipeEntry
import com.naaammme.bbspace.core.swipe.queue.SwipeSeenMemory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

private val Context.swipeSeenDataStore by preferencesDataStore(name = "swipe_seen")

/**
 * 跨会话「已推记忆」的持久化宿主（SW-05，决策 D7）。
 *
 * 纯状态（LRU + TTL + 编解码）在 core:swipe 的 [SwipeSeenMemory]——本类只管
 * DataStore 的读写时机：进程内缓存一份记忆，`filterFresh` 读缓存，
 * `remember` 写缓存 + 异步落盘（写入失败静默：记忆丢了最多重复推一次）。
 *
 * 隐私口径：只存条目的 legacyKey 字符串（bvid/aid 形态）+ 时间戳，
 * **不含标题/封面/UP/账号信息**。
 */
@Singleton
class SwipeSeenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private var cache: SwipeSeenMemory? = null

    /** 给定条目里"没见过"的那部分（跨会话已推库命中即丢）。 */
    suspend fun filterFresh(
        entries: List<SwipeEntry>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<SwipeEntry> = withContext(Dispatchers.IO) {
        // FL-17：调用方在 viewModelScope（Main.immediate）——O(500) 过滤 + JSON 解析
        // 不许跑在主线程。DataStore 的磁盘 IO 本来在 IO，但序列化/过滤是调用方上下文的活。
        mutex.withLock {
            val memory = loadLocked()
            entries.filterNot { memory.contains(it.legacyKey(), nowMs) }
        }
    }

    /** 记住一批已推条目（先到先记；超出上限按最旧淘汰）。 */
    suspend fun remember(
        entries: List<SwipeEntry>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = loadLocked().remember(entries.map { it.legacyKey() }, nowMs)
                cache = updated
                // FL-17：写失败不崩、不影响会话；但**取消必须原样传播** ——
                // runCatchingCancellable（CancellationSweepGuardTest 的纪律），
                // 裸 runCatching 会把 CancellationException 吞成"写失败"。
                runCatchingCancellable {
                    context.swipeSeenDataStore.edit { prefs ->
                        prefs[SEEN_JSON_KEY] = updated.toJson()
                    }
                }
            }
        }
    }

    private suspend fun loadLocked(): SwipeSeenMemory {
        cache?.let { return it }
        val json = runCatchingCancellable {
            context.swipeSeenDataStore.data.map { prefs -> prefs[SEEN_JSON_KEY] }.first()
        }.getOrNull()
        val memory = json?.let(SwipeSeenMemory::fromJson) ?: SwipeSeenMemory.empty()
        cache = memory
        return memory
    }

    private companion object {
        val SEEN_JSON_KEY = stringPreferencesKey("swipe_seen_json")
    }
}
