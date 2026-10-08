package com.naaammme.bbspace.core.playback

import android.content.Context
import com.naaammme.bbspace.core.model.player.CdnHealthPolicy

/**
 * 智能选线健康度的**落盘**（2026-10-06，对齐 BiliPai 的插件私有存储口径）。
 *
 * ## 为什么必须落盘
 *
 * 落盘前 `CdnHealthStore` 就是 `private var healthByHost = emptyMap()` ✗ —— 进程重启即归零 ⇒
 * "越用越准"在**每次冷启后重来** ✗ ⇒ 智能选线的长期收益为零 ✗。
 * 源仓库把健康度写进插件私有存储，并在**读盘时一次性折现** ✓：
 * 「半小时前的两次报错」和「刚才的一次报错」不该被同等对待 ✓。
 *
 * ## 存储格式
 *
 * 用 `SharedPreferences`（本仓既有口径，见 `AppSettings`）✓，一个 host 一行字符串：
 * `host|ready|timeout|buffering|error|lineFail|probeLatency|probeSpeed|lastProbeAt|updatedAt|windowStart`
 *
 * 选择"紧凑字符串"而不是 JSON 的原因：这里只有 11 个数值/字符串，**不进网络、不跨模块** ✗，
 * 引一套序列化框架只为存 11 个数不划算 ✓；解析失败就**丢弃重来** ✓（健康度是可有可无的优化，
 * 绝不因为它坏了而影响播放 ✓）。
 */
internal object CdnHealthPersistence {

    private const val PREFS_NAME = "cdn_health"
    private const val KEY_ROWS = "rows"

    fun load(context: Context, nowMs: Long = System.currentTimeMillis()): Map<String, CdnHealthPolicy.Health> {
        val raw = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ROWS, null)
        }.getOrNull() ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return raw.lineSequence()
            .mapNotNull { decode(it) }
            // **读盘一次性折现**：放久了的老惩罚不该继续压着这条线 ✓
            .associate { it.host to CdnHealthPolicy.decay(it, nowMs) }
            .filterKeys { it.isNotBlank() }
    }

    /**
     * 落盘上限：只保留**最近更新的** [MAX_HOSTS] 个 host。
     *
     * 长期运行的必要护栏：每播一支视频就可能新增主机名（B 站 CDN 主机是动态的），
     * 无上限地写下去会让这个文件无限膨胀 ✗；而"很久没观测过的线路"本就该被遗忘 ✓
     * （与 [CdnHealthPolicy.decay] 的折现口径一致）。
     */
    private const val MAX_HOSTS = 64

    fun save(context: Context, healthByHost: Map<String, CdnHealthPolicy.Health>) {
        val trimmed = healthByHost.values
            .sortedByDescending { it.lastUpdatedAtMs }
            .take(MAX_HOSTS)
        runCatching {
        val payload = trimmed.joinToString("\n") { encode(it) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ROWS, payload)
                .apply()
        }
    }

    private fun encode(h: CdnHealthPolicy.Health): String = listOf(
        h.host,
        h.readyCount,
        h.firstFrameTimeoutCount,
        h.bufferingCount,
        h.playbackErrorCount,
        h.lineOpenFailureCount,
        h.probeLatencyMs ?: -1L,
        h.probeSpeedKbps ?: -1L,
        h.lastProbeAtMs,
        h.lastUpdatedAtMs,
        h.windowStartedAtMs
    ).joinToString("|")

    private fun decode(row: String): CdnHealthPolicy.Health? {
        if (row.isBlank()) return null
        val parts = row.split("|")
        if (parts.size != 11 || parts[0].isBlank()) return null
        val nums = parts.drop(1).map { it.toLongOrNull() ?: return null }
        val (ready, timeout, buffering, error, lineFail) = nums
        return CdnHealthPolicy.Health(
            host = parts[0],
            readyCount = ready.toInt(),
            firstFrameTimeoutCount = timeout.toInt(),
            bufferingCount = buffering.toInt(),
            playbackErrorCount = error.toInt(),
            lineOpenFailureCount = lineFail.toInt(),
            probeLatencyMs = nums[5].takeIf { it >= 0L },
            probeSpeedKbps = nums[6].takeIf { it >= 0L },
            lastProbeAtMs = nums[7],
            lastUpdatedAtMs = nums[8],
            windowStartedAtMs = nums[9]
        )
    }
}
