package com.naaammme.bbspace.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「收起视频页」这条契约的结构守卫（Bug ①：后台回收详情页后无法返回推荐页）。
 *
 * ## 病灶回顾
 *
 * `AppNavHost.dismissPlaybackHost` 曾把 `CapsuleIndicatorState.activateForVideoTarget()`
 * 的返回值当成"收起已完成"——而它只回答"是否由胶囊接管播放"（目标是 Video 即 true）。
 * 于是视频目标下 `closeVideoHost()` 成了不可达分支、`hostMode` 永远停在 Expanded，
 * 返回键被整条吞掉。第二环：该路径不 `markVideoClosedByUser()`，MainActivity 的自愈
 * （`tryRestoreVideoPage`）会在下一次 onResume/onWindowFocusChanged 把页面重新拉起。
 *
 * 这两条都是**运行期状态机**问题，写法层面的断言钉不住它，但可以钉住"修复的形状"：
 * 1. `PlaybackHostState` 必须存在 `collapseToCapsule()`，且它**同时**做两件事——
 *    标记用户主动收起 + 真正置 Hidden；且不得关停播放会话（否则胶囊就没内容了）。
 * 2. `dismissPlaybackHost` 必须调用它。
 * 3. 一次性 hack `forcedDismissMode` 不得回归（它假装收起、永不复位，把小横条重新
 *    展开也挡死）。
 */
class PlaybackHostDismissGuardTest {

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

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效。",
            mainSources.size > 100
        )
    }

    /** collapseToCapsule 必须「标记用户收起 + 置 Hidden」，且不关停播放会话。 */
    @Test
    fun collapseToCapsuleMarksUserClosedAndHidesWithoutStoppingPlayback() {
        val body = blockBody(sourceOf("playback${File.separator}PlaybackHostState.kt"), "fun collapseToCapsule(")
        assertTrue(
            "collapseToCapsule 必须标记 isUserClosedVideo = true —— 否则 MainActivity 的自愈" +
                "（shouldRestoreVideoPage）会在下一次 onResume 把页面重新拉起，返回照样失效。",
            body.any { it.contains("isUserClosedVideo = true") }
        )
        assertTrue(
            "collapseToCapsule 必须真正把 hostMode 置 Hidden —— 「胶囊接管播放」不等于" +
                "「页面已收起」，hostMode 是展开层可见性的唯一真值。",
            body.any { it.contains("_hostMode.value = PlaybackHostMode.Hidden") }
        )
        assertTrue(
            "collapseToCapsule 不得关停播放会话（playbackSession.close）—— 胶囊要接着播。",
            body.none { it.contains("playbackSession.close()") }
        )
    }

    /** dismissPlaybackHost 必须走 collapseToCapsule，而不是"胶囊接管就什么都不做"。 */
    @Test
    fun dismissPlaybackHostActuallyCollapses() {
        val body = blockBody(sourceOf("navigation${File.separator}AppNavHost.kt"), "val dismissPlaybackHost")
        assertTrue(
            "dismissPlaybackHost 必须调用 collapseToCapsule()：否则视频目标下返回键被" +
                "胶囊分支吞掉，页面收不起来（Bug ① 的主因）。",
            body.any { it.contains("collapseToCapsule()") }
        )
    }

    /** 一次性 hack forcedDismissMode 不得回归。 */
    @Test
    fun forcedDismissModeHackIsGone() {
        val lines = sourceOf("navigation${File.separator}AppNavHost.kt")
        val hits = lines.count { it.contains("forcedDismissMode") }
        assertTrue(
            "forcedDismissMode 已删除（它假装收起、hostMode 不离开 Expanded 就永不复位，" +
                "反而把小横条重新展开挡死）。现在出现 $hits 处。",
            hits == 0
        )
    }

    /** 源卡片在回退 morph 期间必须淡出（否则原卡片 + 幽灵两份封面同屏）。 */
    @Test
    fun sourceCardFadesDuringFallbackMorph() {
        val home = sourceOf("video${File.separator}HomeVideoPage.kt")
        assertTrue(
            "HomeVideoPage 的 sourceCardFadeModifier 必须接 coverExpandSourceFade " +
                "（回退 morph 期间源卡片随幽灵进度淡出；系统无缝路径该值恒为 1f，行为不变）。",
            home.any { it.contains("coverExpandSourceFade(") }
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。")
        return file.readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
                trimmed.startsWith("import ")
            ) {
                ""
            } else {
                line
            }
        }
    }

    /** 取以 [signaturePrefix] 开头的函数/属性体（含签名），按花括号配平定界。 */
    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`，无法验证其内部结构。", start >= 0)
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
}
