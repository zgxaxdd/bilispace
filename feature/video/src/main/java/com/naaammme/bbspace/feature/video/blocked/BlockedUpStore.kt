package com.naaammme.bbspace.feature.video.blocked

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.naaammme.bbspace.core.common.coroutines.runCatchingCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// PT-06：刷视频「拉黑该 UP」的**本地名单**（SwipeSeenStore 同款范式）。
// 为什么不是 Room：新增表要 bump schema version + 迁移（FINDINGS W16），违背移植
// 任务书"不 bump version"的前提；名单是单用户小集合（≤200 条），DataStore 零迁移成本。
// 编解码用 org.json（RemotePlaybackReporter 同款）——feature:video 无 @Serializable 插件，
// core:swipe 的 SwipeSeenMemory 也是纯构建器写法。

private val Context.blockedUpDataStore by preferencesDataStore(name = "blocked_up")

/** 本地名单条目：mid + 时间戳 + 是否已同步到服务端。 */
data class BlockedUpEntry(
    val mid: Long,
    val blockedAtMs: Long,
    /** false = 只在本地（同步失败/未到）；true = gRPC 已成功。 */
    val synced: Boolean
)

/**
 * 纯内存名单（JVM 可测内核）：幂等 add、cap 淘汰（最旧先出）、同步标记、撤销。
 * 与存储解耦——[BlockedUpStore] 持有它并负责序列化。
 */
internal class BlockedUpMemory(
    val entries: List<BlockedUpEntry> = emptyList(),
    private val cap: Int = BLOCKED_UP_CAP
) {

    /** 本地先生效：幂等（同 mid 保留首条）；超出 [cap] 时淘汰最旧。 */
    fun add(mid: Long, nowMs: Long): BlockedUpMemory {
        if (mid <= 0L || entries.any { it.mid == mid }) return this
        val next = entries + BlockedUpEntry(mid = mid, blockedAtMs = nowMs, synced = false)
        val trimmed = if (next.size > cap) next.subList(next.size - cap, next.size) else next
        return BlockedUpMemory(trimmed, cap)
    }

    /** gRPC 成功后标记已同步；未记录的 mid 忽略。 */
    fun markSynced(mid: Long): BlockedUpMemory {
        val idx = entries.indexOfFirst { it.mid == mid }
        if (idx < 0 || entries[idx].synced) return this
        val next = entries.toMutableList()
        next[idx] = entries[idx].copy(synced = true)
        return BlockedUpMemory(next, cap)
    }

    /** 撤销拉黑（本地名单移除）。 */
    fun remove(mid: Long): BlockedUpMemory =
        BlockedUpMemory(entries.filterNot { it.mid == mid }, cap)

    fun contains(mid: Long): Boolean = entries.any { it.mid == mid }

    companion object {
        /** 与 SwipeSeenMemory 的 500 同量级口径：单用户拉黑名单的合理上界。 */
        const val BLOCKED_UP_CAP = 200
    }
}

/**
 * 「拉黑该 UP」本地名单的存取（PT-06）。
 *
 * 契约（PT-06 要点①）：**本地先生效、再异步同步，失败不回滚本地**——
 * [add] 立即落本地（synced=false）；gRPC 成功后调 [markSynced]；失败只留日志。
 */
@Singleton
class BlockedUpStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    private var cache: BlockedUpMemory? = null

    /** 本地先生效（幂等；同步状态见 [markSynced]）。 */
    suspend fun add(mid: Long, nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = loadLocked().add(mid, nowMs)
            cache = updated
            persistLocked(updated)
        }
    }

    /** gRPC 成功后的同步标记（失败不回滚本地——调用方不调本函数即可）。 */
    suspend fun markSynced(mid: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = loadLocked().markSynced(mid)
            cache = updated
            persistLocked(updated)
        }
    }

    /** 撤销（本地名单移除；服务端解除由调用方另行发起）。 */
    suspend fun remove(mid: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = loadLocked().remove(mid)
            cache = updated
            persistLocked(updated)
        }
    }

    suspend fun list(): List<BlockedUpEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked().entries }
    }

    private suspend fun loadLocked(): BlockedUpMemory {
        cache?.let { return it }
        val text = runCatchingCancellable {
            context.blockedUpDataStore.data.map { prefs -> prefs[BLOCKED_UP_KEY] }.first()
        }.getOrNull()
        val memory = text?.let { decode(it) } ?: BlockedUpMemory()
        cache = memory
        return memory
    }

    private suspend fun persistLocked(memory: BlockedUpMemory) {
        runCatchingCancellable {
            context.blockedUpDataStore.edit { prefs ->
                prefs[BLOCKED_UP_KEY] = encode(memory.entries)
            }
        }
    }

    private companion object {
        val BLOCKED_UP_KEY = stringPreferencesKey("blocked_up_json")

        fun encode(entries: List<BlockedUpEntry>): String {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("mid", entry.mid)
                        .put("at", entry.blockedAtMs)
                        .put("synced", entry.synced)
                )
            }
            return array.toString()
        }

        fun decode(text: String): BlockedUpMemory {
            return runCatching {
                val array = JSONArray(text)
                val entries = (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    BlockedUpEntry(
                        mid = item.optLong("mid"),
                        blockedAtMs = item.optLong("at"),
                        synced = item.optBoolean("synced")
                    )
                }.filter { it.mid > 0L }
                BlockedUpMemory(entries)
            }.getOrDefault(BlockedUpMemory())
        }
    }
}
