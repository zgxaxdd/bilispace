package com.naaammme.bbspace.feature.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PB-01 的接线守卫：**重试必须先失效播放地址缓存，再 `openVideo`**。
 *
 * ## 为什么是结构断言
 * 「顺序」这件事在 JVM 上端到端验需要整套 fake（repository + controller + 会话），
 * 而它要守的不变式恰恰是**调用顺序本身**：取流入口缓存优先、TTL 90s、自动重试退避
 * 1s/3s → 只要失效晚于 `openVideo`，取流已经发生、失效后缓存还会被重新写回，
 * 重试照旧拿同一份失效 URL（**功能静默失效，无任何报错**）。
 *
 * 防假绿（照抄 VideoLogicGuardTest 两条）：① 先确认文件读到；② 扫描前过滤注释行
 * （本仓的注释里必然出现 `invalidatePlayUrl`、`openVideo` 这些词）。
 */
class RetryInvalidationGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    private fun vmCode(): List<String> {
        val matches = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${FS}src${FS}main${FS}") }
            .filterNot { it.path.contains("${FS}build${FS}") }
            .filter { it.name == "VideoViewModel.kt" }
            .toList()
        assertEquals(
            "VideoViewModel.kt 必须唯一（命中：${matches.map { it.path }}）",
            1,
            matches.size
        )
        return matches.single().readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("import ")
            ) {
                ""
            } else {
                line
            }
        }
    }

    /** 取以 [signaturePrefix] 开头的函数体（含签名），按花括号配平定界。 */
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
                        depth++
                        opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        return body
    }

    private fun body() = blockBody(vmCode(), "private fun retryCurrentPlaybackInternal(")

    /**
     * 验收 3：`invalidatePlayUrl(` 必须**早于** `openVideo(`。
     *
     * 破坏性验证口径：把失效调用移到 `openVideo` 之后 → 本测试必须红。
     */
    @Test
    fun invalidationHappensBeforeOpeningTheVideo() {
        val body = body()
        val invalidateAt = body.indexOfFirst { it.contains("invalidatePlayUrl(") }
        val openAt = body.indexOfFirst { it.contains("openVideo(") }
        assertTrue(
            "retryCurrentPlaybackInternal 里必须调用 invalidatePlayUrl（PB-01：否则重试只拿回同一份失效 URL）",
            invalidateAt >= 0
        )
        assertTrue(
            "openVideo 必须仍在（重试的主体动作）",
            openAt >= 0
        )
        assertTrue(
            "**顺序错了等于没做**：失效必须写在 openVideo 之前 " +
                "（invalidate@$invalidateAt openVideo@$openAt）",
            invalidateAt < openAt
        )
    }

    /** 失效的是"当前重试目标"，不是别的视频（前缀覆盖它的所有 flavor 见 core:playback 单测）。 */
    @Test
    fun invalidationTargetsTheRetriedTarget() {
        val body = body()
        assertTrue(
            "失效入参必须由当前 target 推导（target.toPlayableParams().getResolveParams()），" +
                "而不是硬编码身份或换成别的视频。",
            body.any { it.contains("invalidatePlayUrl(target.toPlayableParams().getResolveParams())") }
        )
        assertTrue(
            "target 必须取自 targetStack 栈顶（重试的是当前页）",
            body.any { it.contains("_targetStack.value.lastOrNull()") }
        )
    }

    /**
     * 验收 4：**自动重试与手动重试共用同一函数** —— 两条路径都被这次修复覆盖。
     *
     * 只钉"存在调用点"不够：要钉住两条路径各自确实走到这里（手动走
     * `retryCurrentPlayback`，自动走退避后的调用）。
     */
    @Test
    fun bothManualAndAutoRetryGoThroughTheSamePath() {
        val vm = vmCode()
        val manual = blockBody(vm, "fun retryCurrentPlayback()")
        assertTrue(
            "手动重试（连播页「重试」按钮）必须转到 retryCurrentPlaybackInternal(fromAuto = false)",
            manual.any { it.contains("retryCurrentPlaybackInternal(fromAuto = false)") }
        )
        assertTrue(
            "自动重试必须转到 retryCurrentPlaybackInternal(fromAuto = true)" +
                "（否则自动重试仍然命中陈旧缓存）",
            vm.any { it.contains("retryCurrentPlaybackInternal(fromAuto = true)") }
        )
    }

    /** 失效能力本身属于取流仓库（core:playback），VM 不许自己再造一份缓存。 */
    @Test
    fun invalidationGoesThroughThePlaybackRepository() {
        val body = body()
        assertTrue(
            "必须走 playbackVideoRepository.invalidatePlayUrl(...)（与 preloadPlayUrl 同一个仓库、同一份 LRU）",
            body.any { it.contains("playbackVideoRepository.invalidatePlayUrl(") }
        )
        assertTrue(
            "VM 里不许**使用** PlayUrlCache（缓存只能在 core:playback 内部，避免出现第二份真值源）。" +
                "注意判据带点号：`PlayUrlCacheMetrics`（VM 合法的上报入口）不含 `PlayUrlCache.`，不会被误伤。",
            vmCode().none { it.contains("PlayUrlCache.") }
        )
    }
}
