package com.naaammme.bbspace.feature.video.swipesource

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.naaammme.bbspace.core.swipe.SwipeEntry
import com.naaammme.bbspace.core.swipe.queue.SwipeSeenMemory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
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
    ): List<SwipeEntry> = mutex.withLock {
        val memory = loadLocked()
        entries.filterNot { memory.contains(it.legacyKey(), nowMs) }
    }

    /** 记住一批已推条目（先到先记；超出上限按最旧淘汰）。 */
    suspend fun remember(
        entries: List<SwipeEntry>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (entries.isEmpty()) return
        mutex.withLock {
            val updated = loadLocked().remember(entries.map { it.legacyKey() }, nowMs)
            cache = updated
            runCatching {
                context.swipeSeenDataStore.edit { prefs ->
                    prefs[SEEN_JSON_KEY] = updated.toJson()
                }
            }
        }
    }

    private suspend fun loadLocked(): SwipeSeenMemory {
        cache?.let { return it }
        val json = runCatching {
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
