package com.naaammme.bbspace.core.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上报字段接线守卫（报告 P1-1 / §2.5.4）。
 *
 * 三件事：
 * 1. **取值判据**（纯函数直调真代码）——蜂窝不得报成 WiFi、后台不得报成前台；
 * 2. **接线形状**（源码扫描）——`RemotePlaybackReporter` 必须真的调策略与
 *    `NetworkTypeProvider`，而不是又退化回常量。**只测纯函数挡不住这件事**：
 *    常量 `"1"` 同样能让纯函数测试全绿（这正是本仓“假绿”的历史教训）。
 * 3. **前后台信号必须从 `PlaybackReporter` 转发过去**，否则 `scene` 永远是前台。
 *
 * 防假绿：扫描前过滤注释与 import 行（注释里必然出现字段名）。
 */
class PlaybackReportFieldsPolicyTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun lines(relativePath: String): List<String> {
        val file = File(repoRoot, relativePath)
        assertTrue("找不到 $relativePath（repoRoot=${repoRoot.absolutePath}）", file.isFile)
        return file.readLines().map { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) "" else line
        }
    }

    // ── 不变式 1：network_type 反映真实网络类别 ────────────────────────────

    @Test
    fun meteredNetworkIsNotReportedAsWifi() {
        assertFalse(
            "蜂窝/未知网络不得报成 WiFi（原缺陷：NETWORK_TYPE_WIFI 恒 \"1\"）",
            PlaybackReportFieldsPolicy.networkType(isUnmetered = false) ==
                PlaybackReportFieldsPolicy.NETWORK_TYPE_UNMETERED
        )
    }

    @Test
    fun unmeteredNetworkIsReportedAsWifi() {
        assertEquals(
            PlaybackReportFieldsPolicy.NETWORK_TYPE_UNMETERED,
            PlaybackReportFieldsPolicy.networkType(isUnmetered = true)
        )
    }

    // ── 不变式 2：scene 反映宿主前后台 ────────────────────────────────────

    @Test
    fun backgroundHostIsNotReportedAsFront() {
        assertFalse(
            "后台/画中画不得报成前台（原缺陷：PLAYBACK_HISTORY_SCENE_FRONT 恒 \"front\"）",
            PlaybackReportFieldsPolicy.scene(isHostInBackground = true) ==
                PlaybackReportFieldsPolicy.SCENE_FOREGROUND
        )
    }

    @Test
    fun foregroundHostIsReportedAsFront() {
        assertEquals(
            PlaybackReportFieldsPolicy.SCENE_FOREGROUND,
            PlaybackReportFieldsPolicy.scene(isHostInBackground = false)
        )
    }

    /** 取值必须来自单一改动点：两个字段都不许在报告器里内联字面量。 */
    @Test
    fun reporterUsesThePolicyInsteadOfInlineConstants() {
        val body = lines(
            "core/playback/src/main/java/com/naaammme/bbspace/core/playback/RemotePlaybackReporter.kt"
        )
        assertTrue(
            "network_type 必须经 PlaybackReportFieldsPolicy.networkType(...) 取值",
            body.any { it.contains("PlaybackReportFieldsPolicy.networkType(") }
        )
        assertTrue(
            "network_type 必须接 NetworkTypeProvider.isUnmetered()（能力已在仓里，别再写常量）",
            body.any { it.contains("networkTypeProvider.isUnmetered()") }
        )
        assertTrue(
            "scene 必须经 PlaybackReportFieldsPolicy.scene(...) 取值",
            body.any { it.contains("PlaybackReportFieldsPolicy.scene(") }
        )
        assertTrue(
            "`network_type` 的赋值行必须走变量，不得内联字面量",
            body.any { it.contains("base[\"network_type\"] = networkType") }
        )
        assertTrue(
            "已删除的硬编码常量不许复活",
            body.none { it.contains("NETWORK_TYPE_WIFI") } &&
                body.none { it.contains("PLAYBACK_HISTORY_SCENE_FRONT") }
        )
    }

    /** 前后台信号必须真的从 PlaybackReporter 传到 RemotePlaybackReporter。 */
    @Test
    fun hostBackgroundSignalIsForwardedToRemoteReporter() {
        val body = lines(
            "core/playback/src/main/java/com/naaammme/bbspace/core/playback/PlaybackReporter.kt"
        )
        assertTrue(
            "PlaybackReporter.setHostBackground 必须把信号转发给 remoteReporter",
            body.any { it.contains("remoteReporter.setHostInBackground(") }
        )
    }
}
