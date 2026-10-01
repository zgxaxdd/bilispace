package com.naaammme.bbspace.core.swipe.queue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 跨会话「已推记忆」的**纯状态**（任务书 §4.7 规则④ / SW-05，决策 D7）：
 * 一条视频在最近的推送里出现过 → 下个会话不再重复推。
 *
 * - **TTL**：默认 7 天——推荐流自己会更新，太久的记忆反而变成"误杀"；
 * - **上限**：默认 500 条，超出按"最旧先出"（LRU 语义）；
 * - **可关**：开关在设置层（`PlayerPlaybackPrefs.crossSessionDedup`，默认开），
 *   关掉时调用方直接不查不写，本类不管开关；
 * - 键用条目的 `legacyKey()` 字符串：这是**去重 map** 的用途（SW-03 的分工），
 *   不是页身份判断。
 *
 * 纯状态 + 纯 JSON 编解码（JsonObject 构建器，无 @Serializable 插件），
 * JVM 可测；持久化由宿主层（DataStore）决定何时落盘。
 */
data class SwipeSeenRecord(
    val key: String,
    val seenAtMs: Long,
)

class SwipeSeenMemory(
    val records: List<SwipeSeenRecord> = emptyList(),
) {

    /** 是否命中（含 TTL 过期判定）。 */
    fun contains(key: String, nowMs: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean {
        if (key.isBlank()) return false
        val record = records.fastFirstOrNull(key) ?: return false
        return nowMs - record.seenAtMs < ttlMs
    }

    /**
     * 记住一批键并淘汰过期 + 超限（最旧先出）。
     * 同键重复 remember 刷新时间戳（看过 = 刚推过）。
     * 批内按入参顺序给毫秒级递增时间戳：TTL 的粒度是天，这点偏移无感，
     * 但让"同一批里后进的是新的"在淘汰排序上可判定（否则并列时间戳的
     * 淘汰顺序是任意的）。
     */
    fun remember(
        keys: Collection<String>,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
        limit: Int = DEFAULT_LIMIT,
    ): SwipeSeenMemory {
        if (keys.isEmpty()) return this
        val fresh = keys.filter { it.isNotBlank() }.toSet()
        val refreshed = fresh.mapIndexed { index, key ->
            SwipeSeenRecord(key = key, seenAtMs = nowMs + index)
        }
        val kept = records.filterNot { it.key in fresh }
        val all = (refreshed + kept)
            .filterNot { nowMs - it.seenAtMs >= ttlMs }
            .sortedByDescending(SwipeSeenRecord::seenAtMs)
        return SwipeSeenMemory(records = all.take(limit.coerceAtLeast(0)))
    }

    private fun List<SwipeSeenRecord>.fastFirstOrNull(key: String): SwipeSeenRecord? =
        firstOrNull { it.key == key }

    // ── 持久化编解码 ─────────────────────────────────────────────────────────

    fun toJson(): String = buildJsonObject {
        put(
            KEY_RECORDS,
            buildJsonArray {
                records.forEach { record ->
                    add(
                        buildJsonObject {
                            put(KEY_RECORD_KEY, record.key)
                            put(KEY_RECORD_SEEN_AT, record.seenAtMs)
                        }
                    )
                }
            }
        )
    }.toString()

    companion object {
        /** 决策 D7 的默认参数。 */
        const val DEFAULT_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val DEFAULT_LIMIT: Int = 500

        private const val KEY_RECORDS = "records"
        private const val KEY_RECORD_KEY = "k"
        private const val KEY_RECORD_SEEN_AT = "t"

        /** 解析失败返回空记忆（记忆丢了最多是"重复推一次"，不能让会话起不来）。 */
        fun fromJson(json: String): SwipeSeenMemory = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val array = root[KEY_RECORDS] as? JsonArray ?: return@runCatching SwipeSeenMemory()
            SwipeSeenMemory(
                records = array.mapNotNull { element ->
                    (element as? JsonObject)?.let { obj ->
                        val key = obj[KEY_RECORD_KEY]?.jsonPrimitive?.contentOrNull
                        val seenAt = obj[KEY_RECORD_SEEN_AT]?.jsonPrimitive?.longOrNull
                        if (!key.isNullOrBlank() && seenAt != null) {
                            SwipeSeenRecord(key = key, seenAtMs = seenAt)
                        } else {
                            null
                        }
                    }
                }
            )
        }.getOrDefault(SwipeSeenMemory())

        /** 单测/预览用的空记忆。 */
        fun empty(): SwipeSeenMemory = SwipeSeenMemory()
    }
}

/** 便捷：给定 SwipeEntry 的键集合是否全部"已见过"。 */
fun SwipeSeenMemory.anySeen(keys: Collection<String>, nowMs: Long): Boolean =
    keys.any { contains(it, nowMs) }
