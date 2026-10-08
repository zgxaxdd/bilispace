package com.naaammme.bbspace.core.settings.blocked

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

// PT-06：「拉黑该 UP」的**本地名单**（SwipeSeenStore 同款范式）。
// 为什么不是 Room：新增表要 bump schema version + 迁移（FINDINGS W16），违背移植
// 任务书"不 bump version"的前提；名单是单用户小集合（≤200 条），DataStore 零迁移成本。
// 编解码用 org.json（RemotePlaybackReporter 同款）。
//
// NX-08：本文件原先在 feature:video，为了让**设置页的名单界面**也能读写（目标里写的
// "本地可见/可撤销"），搬到 core:settings —— feature:video 与 feature:settings 都
// 已经依赖 core:settings，因此**没有新增任何依赖边**（比放进 core:space 少两条边）。

private val Context.blockedUpDataStore by preferencesDataStore(name = "blocked_up")

/**
 * 本地名单条目。
 *
 * [name] 是**拉黑当时**的 UP 名（NX-08：名单页要显示"拉了谁"，而在列表页逐条回查
 * 用户名需要每条一次网络请求）。它可能为空（来源没给名字，如 story/relate），
 * 也可能与当前昵称不一致（用户改名）——这是刻意的取舍：**不为一个展示字段引入 N 次请求**。
 */
data class BlockedUpEntry(
    val mid: Long,
    val name: String? = null,
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
    fun add(mid: Long, name: String?, nowMs: Long): BlockedUpMemory =
        addWithEviction(mid, name, nowMs).first

    /**
     * 同 [add]，但把"是否发生了淘汰"一并报出来（F-6）。
     *
     * 修复前淘汰是**静默**的：名单满 200 之后最早的记录被丢掉，界面上没有任何信号 ——
     * 用户此后在「拉黑名单」页看不到它们（也就无法在应用内撤销），而它们会重新出现在
     * 推荐流里且没有任何解释。
     */
    fun addWithEviction(mid: Long, name: String?, nowMs: Long): Pair<BlockedUpMemory, Boolean> {
        if (mid <= 0L || entries.any { it.mid == mid }) return this to false
        // blank 名字退化为 null：列表页显示"UP <mid>"而不是空字符串
        val normalizedName = name?.takeIf(String::isNotBlank)
        val next = entries + BlockedUpEntry(
            mid = mid,
            name = normalizedName,
            blockedAtMs = nowMs,
            synced = false
        )
        val evicted = next.size > cap
        val trimmed = if (evicted) next.subList(next.size - cap, next.size) else next
        return BlockedUpMemory(trimmed, cap) to evicted
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
 * 「拉黑该 UP」本地名单的存取（PT-06；NX-08 起同时服务设置页的查看/撤销）。
 *
 * 契约（PT-06 要点①）：**本地先生效、再异步同步，失败不回滚本地**——
 * [add] 立即落本地（synced=false）；gRPC 成功后调 [markSynced]；失败只留日志。
 * 撤销同理：先本地移除（[remove]）再发 gRPC，失败同样不回滚（本地不再显示该条）。
 */
@Singleton
class BlockedUpStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    private var cache: BlockedUpMemory? = null

    /**
     * 本地先生效（幂等；同步状态见 [markSynced]）。
     *
     * @return 本次是否发生了**淘汰**（名单已满，最早的记录被移除）—— 调用方据此给用户
     *   一个可见信号（F-6：此前淘汰完全静默）。
     */
    suspend fun add(
        mid: Long,
        name: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (updated, evicted) = loadLocked().addWithEviction(mid, name, nowMs)
            cache = updated
            persistLocked(updated)
            evicted
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

    /** 全量名单，**最新在前**（名单页直接渲染，不再排序）。 */
    suspend fun list(): List<BlockedUpEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked().entries.asReversed().toList() }
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

    companion object {
        /** 本地名单上限（名单页「当前 N/CAP」与淘汰提示共用；F-6）。 */
        const val CAP: Int = BlockedUpMemory.BLOCKED_UP_CAP

        private val BLOCKED_UP_KEY = stringPreferencesKey("blocked_up_json")

        private fun encode(entries: List<BlockedUpEntry>): String {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("mid", entry.mid)
                        .put("name", entry.name ?: "")
                        .put("at", entry.blockedAtMs)
                        .put("synced", entry.synced)
                )
            }
            return array.toString()
        }

        private fun decode(text: String): BlockedUpMemory {
            return runCatching {
                val array = JSONArray(text)
                val entries = (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    BlockedUpEntry(
                        mid = item.optLong("mid"),
                        // 旧版本（PT-06）没有 "name" 字段 → optString 得 ""，退化 name=null
                        name = item.optString("name").takeIf(String::isNotBlank),
                        blockedAtMs = item.optLong("at"),
                        synced = item.optBoolean("synced")
                    )
                }.filter { it.mid > 0L }
                BlockedUpMemory(entries)
            }.getOrDefault(BlockedUpMemory())
        }
    }
}
