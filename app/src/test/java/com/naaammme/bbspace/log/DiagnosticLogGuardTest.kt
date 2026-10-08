package com.naaammme.bbspace.log

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 诊断日志收口守卫（Q-3，2026-10-04 审查报告）。
 *
 * ## 病灶回顾
 *
 * 审查报告实测：main 源码里 **29 处 / 12 个文件**直写 `android.util.Log`，全部绕开
 * [com.naaammme.bbspace.core.common.log.Logger]（`Logger.isDebug` 在 release 恒假）——
 * 这是刻意的"稳定版也要留痕"，问题在于**形态**：散落 12 个文件、没有统一开关，
 * 量产包里一律常开。修法：全部收口到 `DiagnosticLog`，并按
 * `BuildConfig.DEBUG || AppSettings.diagnosticLogEnabled` 决定是否输出。
 *
 * ## 本守卫钉三件事
 * 1. **不许再出现裸 `android.util.Log.<级别>(` 调用** —— 除了两个"业主"文件：
 *    `Logger.kt` 与 `DiagnosticLog.kt` 自身；
 * 2. **开关真的接上了**：app 层按 `BuildConfig.DEBUG || 设置项` 赋值；
 * 3. **设置项真的存在**：`core:settings` 里有持久化键、性能页里有开关入口
 *    （否则"能开关"只是一句注释）。
 *
 * ## 防假绿
 * 先确认扫到的 main 源码 > 300 个、且已知样本（Logger.kt / DiagnosticLog.kt）在内；
 * 注释行与 import 行一律跳过（本仓注释里会大量提到 `android.util.Log`）。
 */
class DiagnosticLogGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val mainSources: List<File> by lazy {
        repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    private val owners = setOf(
        "core${File.separator}common${File.separator}src${File.separator}main${File.separator}java" +
            "${File.separator}com${File.separator}naaammme${File.separator}bbspace${File.separator}" +
            "core${File.separator}common${File.separator}log${File.separator}Logger.kt",
        "core${File.separator}common${File.separator}src${File.separator}main${File.separator}java" +
            "${File.separator}com${File.separator}naaammme${File.separator}bbspace${File.separator}" +
            "core${File.separator}common${File.separator}log${File.separator}DiagnosticLog.kt"
    )

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码（repoRoot=${repoRoot.absolutePath}）—— 少于 300 说明路径推导失效",
            mainSources.size > 300
        )
        owners.forEach { suffix ->
            assertTrue(
                "找不到日志业主文件 $suffix",
                mainSources.any { it.path.endsWith(suffix) }
            )
        }
    }

    @Test
    fun noBareAndroidUtilLogCallsOutsideItsOwners() {
        // 两种形态都要抓：
        // ① `android.util.Log.i(...)`（Q-3 报告口径的 29 处）；
        // ② `import android.util.Log` + 裸 `Log.i(...)`（**报告漏数的另外 30 处**，
        //    分布在 SeamlessVideoCardOpener / PortraitSwipePager / DanmakuOverlayState /
        //    OplusViewSeamlessCompat —— 本守卫补上这一半，否则收口只是"看起来收口"）。
        val re = Regex("""android\.util\.Log\.[idwe]\(|(^|[^.\w])Log\.[idwe]\(""")
        val offenders = mainSources
            .filterNot { file -> owners.any { file.path.endsWith(it) } }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        null
                    } else if (re.containsMatchIn(line)) {
                        "${file.relativeTo(repoRoot).path.replace('\\', '/')}:${index + 1}: ${trimmed.take(120)}"
                    } else {
                        null
                    }
                }
            }
        assertTrue(
            "这些位置直写 android.util.Log —— Q-3 已把全部取证日志（限定的 29 处 + 裸 `Log.x` 的 30 处）" +
                "收口到 DiagnosticLog（受统一开关控制），新增取证日志必须走 DiagnosticLog，" +
                "否则量产包又会常开刷 logcat：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun switchIsWiredToBuildConfigAndSetting() {
        val applier = mainSources.firstOrNull { it.path.endsWith("AppInitializer.kt") }
            ?: throw AssertionError("找不到 AppInitializer.kt")
        val applierText = applier.readText()
        assertTrue(
            "AppInitializer 必须把 DiagnosticLog.enabled 设为 `BuildConfig.DEBUG || <设置项>`：" +
                "debug 包保持始终可见，量产包默认关闭",
            applierText.contains("DiagnosticLog.enabled = BuildConfig.DEBUG || enabled")
        )

        val appSettings = repoRoot.resolve(
            "core/settings/src/main/java/com/naaammme/bbspace/core/settings/AppSettings.kt"
        )
        assertTrue("找不到 AppSettings.kt", appSettings.isFile)
        val settingsText = appSettings.readText()
        assertTrue(
            "AppSettings 必须有持久化键 diagnostic_log_enabled",
            settingsText.contains("""booleanPreferencesKey("diagnostic_log_enabled")""")
        )
        assertTrue(
            "AppSettings 必须暴露 diagnosticLogEnabled 流与写入口",
            settingsText.contains("val diagnosticLogEnabled: Flow<Boolean>") &&
                settingsText.contains("suspend fun updateDiagnosticLogEnabled(")
        )

        val screen = repoRoot.resolve(
            "feature/settings/src/main/java/com/naaammme/bbspace/feature/settings/performance/PerformanceSettingsScreen.kt"
        )
        assertTrue("找不到 PerformanceSettingsScreen.kt", screen.isFile)
        assertTrue(
            "性能页必须有打开诊断日志的开关入口（否则用户没有取证手段）",
            screen.readText().contains("updateDiagnosticLogEnabled")
        )
    }
}
