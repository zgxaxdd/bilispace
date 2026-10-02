package com.naaammme.bbspace.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 前台服务 startForeground 时机的结构守卫（AP-08 ③）。
 *
 * ## 病灶回顾
 *
 * `PlaybackService` 原先只靠 media3 `PlayerNotificationManager` 的通知发布回调
 * （`onNotificationPosted`）才 `startForeground`，而服务由 `startService` 启动 ——
 * 从启动到通知发布之间的窗口里，这是一个**可被系统回收的普通 started service**，
 * 后台播放存在中断面。修复 = `startForegroundService` 启动 + `onStartCommand` 内
 * **立即** `startForeground`（Android 8+ 的 5 秒配对约束），极简通知占位拿前台状态，
 * manager 随后用同一 NOTIFICATION_ID 发布完整通知原地更新。
 *
 * ## W20：锚在 onStartCommand 体内的调用本身
 */
class ForegroundServiceTimingTest {

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

    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("找不到 `$suffix` —— 此时断言无意义。")
        return file.readLines()
    }

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        require(!signaturePrefix.startsWith(" ") && !signaturePrefix.startsWith("\t")) {
            "signaturePrefix 带了前导缩进（W23）：本 helper 对行做 trimStart() 比较。"
        }
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`", start >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> {
                        depth++; opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        return body
    }

    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@count false
            if (trimmed.startsWith("import ")) return@count false
            re.containsMatchIn(line)
        }
    }

    /**
     * 块体内"必须调用 X"的判定**必须跳过注释行** —— 被注释掉的调用等于没有调用
     * （W7；破坏性验证实测：注释式拔线曾骗过 contains 判定）。
     */
    private fun codeLines(body: List<String>): List<String> = body.filter { line ->
        val t = line.trimStart()
        !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
    }

    /** onStartCommand 体内必须立即 startForeground（与 startForegroundService 的 5s 配对）。 */
    @Test
    fun onStartCommandEntersForegroundImmediately() {
        val lines = sourceOf("app/src/main/java/com/naaammme/bbspace/playback/PlaybackService.kt")
        val body = codeLines(blockBody(lines, "override fun onStartCommand("))
        assertTrue(
            "PlaybackService.onStartCommand 必须立即调用 startForegroundImmediately() —— " +
                "等通知发布回调才进前台的话，启动窗口内服务是可被回收的普通 started service" +
                "（AP-08 病灶；startForegroundService 的 5 秒约束也要求立即配对）。",
            body.any { it.contains("startForegroundImmediately()") }
        )
        val immediate = codeLines(blockBody(lines, "private fun startForegroundImmediately()"))
        assertTrue(
            "startForegroundImmediately 必须真的调用 startForeground(（W20：匹配调用本身）。",
            immediate.any { it.contains("startForeground(NOTIFICATION_ID, notification)") }
        )
    }

    /** 启动端必须用 startForegroundService（普通 startService 不触发 5s 配对保护）。 */
    @Test
    fun serviceIsStartedAsForegroundService() {
        val lines = sourceOf("app/src/main/java/com/naaammme/bbspace/AppInitializer.kt")
        assertTrue(
            "AppInitializer 启动 PlaybackService 必须用 startForegroundService（AP-08）。",
            countMatching(lines, """context\.startForegroundService\(intent\)""") == 1
        )
        assertTrue(
            "旧的普通 startService 启动形态不许回潮（那会重新打开可回收窗口）。",
            countMatching(lines, """context\.startService\(intent\)""") == 0
        )
    }

    /** 前台状态退出路径保持：通知被划掉 / 销毁时必须 stopForeground（防前台泄漏）。 */
    @Test
    fun foregroundStateIsReleasedOnTeardown() {
        val lines = sourceOf("app/src/main/java/com/naaammme/bbspace/playback/PlaybackService.kt")
        val cancelled = codeLines(blockBody(lines, "override fun onNotificationCancelled("))
        val destroyed = codeLines(blockBody(lines, "override fun onDestroy("))
        assertTrue(
            "通知被划掉时必须 stopForeground（前台状态只升不降的另一面：退出路径要降）。",
            cancelled.any { it.contains("stopForeground") }
        )
        assertTrue(
            "onDestroy 必须兜底 stopForeground（防前台服务泄漏）。",
            destroyed.any { it.contains("stopForeground") }
        )
    }
}
