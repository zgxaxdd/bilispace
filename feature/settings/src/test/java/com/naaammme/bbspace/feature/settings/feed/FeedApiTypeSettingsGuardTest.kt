package com.naaammme.bbspace.feature.settings.feed

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PT-10 V9：推荐来源设置项的**限制说明必须写在文案里**（结构断言）。
 *
 * 为什么这条要单独守：WEB 档有两条用户一眼会当成 bug 的限制 ——
 * ① 卡片没有「不感兴趣」入口；② 只返回普通视频（没有直播/番剧/课堂卡）。
 * 说明文案被后人"顺手精简"掉，这两个限制就会以 bug 的形式回到用户面前
 * （方案 §4.3 / §7 明确要求"必须在设置项描述里告知用户"）。
 */
class FeedApiTypeSettingsGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    private val screen: List<String> by lazy {
        val file = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${FS}src${FS}main${FS}") }
            .filterNot { it.path.contains("${FS}build${FS}") }
            .firstOrNull { it.path.endsWith("feed${FS}FeedSettingsScreen.kt") }
            ?: throw AssertionError("找不到 FeedSettingsScreen.kt（repoRoot=${repoRoot.absolutePath}）")
        file.readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) "" else line
        }
    }

    @Test
    fun threeOptionsAreOffered() {
        listOf(
            "FeedApiType.MOBILE",
            "FeedApiType.WEB",
            "FeedApiType.MERGED"
        ).forEach { option ->
            assertEquals(
                "设置页必须提供 $option 这一档（方案 §4.6 三项单选）。",
                1,
                screen.count { it.contains("feedApiType == $option") }
            )
        }
    }

    @Test
    fun webLimitationsAreDocumentedInTheUi() {
        assertTrue(
            "WEB 档说明必须写明「不感兴趣」不可用 —— 否则用户会当成 bug 反馈。",
            screen.any { it.contains("不感兴趣") }
        )
        assertTrue(
            "WEB 档说明必须写明只返回普通视频（无直播/番剧/课堂卡）—— 否则内容构成变化会被当成 bug。",
            screen.any { it.contains("只返回普通视频") }
        )
    }

    @Test
    fun mobileIsLabelledAsDefault() {
        assertTrue(
            "MOBILE 档必须标明是默认档，且文案要能看出「与旧版一致」。",
            screen.any { it.contains("手机端推荐（默认）") } &&
                screen.any { it.contains("默认") && it.contains("旧版") }
        )
    }

    @Test
    fun selectionWritesThroughToSettings() {
        assertTrue(
            "三档选择都必须落盘（viewModel.updateFeedApiType）。",
            screen.count { it.contains("viewModel.updateFeedApiType(FeedApiType.") } == 3
        )
    }
}
