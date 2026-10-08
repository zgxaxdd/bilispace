package com.naaammme.bbspace.feature.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CDN 并行/预取开关与线路诊断面板的**接线守卫**（结构断言）。
 *
 * 存在理由：本轮适配的失败模式不是"编译不过"，而是"东西都在、没人调用"——
 * 预取器、严格 Range 读、诊断 API 都曾长期是孤儿（见 08/09 报告）。纯函数测试挡不住这件事，
 * 所以这里逐字钉住四段链路：
 * 1. 设置项存在且**默认开**（`PlayerPlaybackPrefs`）；
 * 2. 设置页两个开关接到 ViewModel，ViewModel 接到持久化（AppSettings）；
 * 3. 设置流 → `PlayerConfig`（会话层）；
 * 4. 诊断面板真的挂在播放设置里，且 `CdnTransferRuntime` 的四个诊断 API 全部有调用方。
 */
class CdnAdaptiveUiGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun codeOf(fileName: String): String {
        val matches = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .filter { it.name == fileName }
            .toList()
        assertEquals(
            "`$fileName` 命中 ${matches.size} 个文件（要求恰好 1）：" + matches.joinToString { it.path },
            1,
            matches.size
        )
        return matches.single().readText()
            .lineSequence()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import ")
            }
            .joinToString("\n")
    }

    @Test
    fun settingsModelExposesBothSwitchesAndDefaultsThemOn() {
        val model = codeOf("PlayerSettingsModels.kt")
        assertTrue(
            "`cdnParallelDownload = true`（默认开：本仓并行是主链的一部分，不能因适配而回退）",
            model.contains("val cdnParallelDownload: Boolean = true")
        )
        assertTrue(
            "`cdnAdaptivePrefetch = true`（默认开：预取是本次适配新增的能力，闸门在缓冲与 Wi‑Fi）",
            model.contains("val cdnAdaptivePrefetch: Boolean = true")
        )
    }

    @Test
    fun bothSwitchesReachThePersistedSettings() {
        val settings = codeOf("AppSettings.kt")
        assertTrue("开关要落盘（DataStore 键）", settings.contains("booleanPreferencesKey(\"cdn_parallel_download\")"))
        assertTrue(settings.contains("booleanPreferencesKey(\"cdn_adaptive_prefetch\")"))
        assertTrue(
            "读侧必须回落到模型默认值（否则升级用户的既有偏好会被 null 覆盖成 false）",
            settings.contains("cdnParallelDownload = prefs[cdnParallelDownloadKey]") &&
                settings.contains("cdnAdaptivePrefetch = prefs[cdnAdaptivePrefetchKey]")
        )
        assertTrue(
            "写侧必须有 setter",
            settings.contains("suspend fun setCdnParallelDownload(enabled: Boolean)") &&
                settings.contains("suspend fun setCdnAdaptivePrefetch(enabled: Boolean)")
        )
        val viewModel = codeOf("VideoViewModel.kt")
        assertTrue(
            "ViewModel 必须把开关写回持久化（源仓库 CdnRegionPluginStore 同口径）",
            viewModel.contains("playerSettings.setCdnParallelDownload(enabled)") &&
                viewModel.contains("playerSettings.setCdnAdaptivePrefetch(enabled)")
        )
        val sheet = codeOf("VideoPlaybackSheet.kt")
        assertTrue(
            "设置页两个开关必须接到 ViewModel",
            sheet.contains("viewModel::updateCdnParallelDownload") &&
                sheet.contains("viewModel::updateCdnAdaptivePrefetch")
        )
    }

    @Test
    fun switchesFlowFromSettingsIntoPlayerConfig() {
        val session = codeOf("StreamPlaybackSessionImpl.kt")
        assertTrue(
            "会话层必须把设置灌进 PlayerConfig（否则开关只是 UI 上的装饰）",
            session.contains("cdnParallelDownload = playback.cdnParallelDownload") &&
                session.contains("cdnAdaptivePrefetch = playback.cdnAdaptivePrefetch")
        )
        assertTrue(
            "改设置必须**不重建播放器**地即时生效（setCdnTransferOptions）",
            session.contains("playerEngine.setCdnTransferOptions(")
        )
    }

    @Test
    fun diagnosticsPanelIsWiredAndUsesThePreviouslyDeadApis() {
        val sheet = codeOf("VideoPlaybackSheet.kt")
        assertTrue(
            "线路诊断面板必须能从播放设置打开",
            sheet.contains("VideoCdnDiagnosticsDialog(") && sheet.contains("showCdnDiagnostics")
        )
        val panel = codeOf("VideoCdnDiagnosticsDialog.kt")
        assertTrue("面板必须读运行时状态", panel.contains("CdnTransferRuntime.state"))
        assertTrue("钉住线路（曾是零调用方）", panel.contains("CdnTransferRuntime.preferHost("))
        assertTrue("恢复自动选线（曾是零调用方）", panel.contains("CdnTransferRuntime.restoreRoutes()"))
        assertTrue("手动冷却（曾是零调用方）", panel.contains("CdnTransferRuntime.avoid("))
        assertTrue("其它媒体线路诊断输入（曾是零调用方）", panel.contains("CdnTransferRuntime.recentMediaUrls()"))
    }
}
