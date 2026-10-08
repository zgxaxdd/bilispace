package com.naaammme.bbspace.infra.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N4「首帧解剖」的**接线**守卫（结构断言，不看日志内容，只看这五个点有没有真的被记）。
 *
 * ## 为什么单独钉这个
 * 埋点类改动最容易发生的失效不是"写错"，而是"后来被悄悄拆掉"：解剖行的价值全在
 * 五个点都记在同一支视频上，任何一个点脱钩，那一行就会长期打 `-`，而**看起来仍然是绿的**
 * （编译、单测都不管引擎里有没有调它）。仓库里同类先例是 `PF-03` 的冷启动阶段耗时。
 *
 * ## 判据按 W20/W21 写
 * 每条都匹配**调用形态**（`firstFrameTimeline.begin(`）而不是符号名，并且每条都限定在
 * 对应方法体**内部** —— 这样"把 begin 挪出 setSource"与"删掉 begin"都会红，
 * 而在注释里提一句 `firstFrameTimeline` 不会让它假绿（[codeOf] 已剥掉注释行）。
 */
class FirstFrameAnatomyWiringGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    /** 读到唯一命中的主源码文件，并剥掉注释与 import 行（防"注释里写了调用"造成的假绿）。 */
    private fun codeOf(uniqueSuffix: String): List<String> {
        val normalized = uniqueSuffix.replace('/', File.separatorChar)
        val matches = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${FS}src${FS}main${FS}") }
            .filterNot { it.path.contains("${FS}build${FS}") }
            .filter { it.path.endsWith(normalized) }
            .toList()
        assertTrue(
            "后缀 `$uniqueSuffix` 命中 ${matches.size} 个文件（要求恰好 1）：" +
                matches.joinToString { it.path },
            matches.size == 1
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

    /** 取某个方法/回调的**函数体**（含签名行），按花括号配对收口。 */
    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`，无法验证其内部接线。", start >= 0)
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

    private fun assertHas(body: List<String>, needle: String, where: String) {
        assertTrue(
            "$where 里必须出现 `$needle`（缺了这个点，解剖行会长期打 `-` 而没人发现）。",
            body.any { it.contains(needle) }
        )
    }

    private val engine by lazy { codeOf("infra/player/src/main/java/com/naaammme/bbspace/infra/player/Media3PlayerEngine.kt") }

    @Test
    fun t0AndT1AreRecordedInsideSetSource() {
        val body = blockBody(engine, "override suspend fun setSource(")
        assertHas(body, "firstFrameTimeline.begin(", "setSource")
        assertHas(body, "firstFrameTimeline.sourceReady(", "setSource")
        // t1 必须吃**本次建源**的索引证据，而不是恒为 null（那等于 t0→t1 这段无法归因）。
        assertHas(body, "sourceReady(dashIndexFromNetwork)", "setSource")
    }

    @Test
    fun decoderInitializationDurationIsConsumedNotDropped() {
        val body = blockBody(engine, "override fun onVideoDecoderInitialized(")
        assertHas(body, "firstFrameTimeline.decoderInitialized(decoderName, initializationDurationMs)", "onVideoDecoderInitialized")
    }

    @Test
    fun surfaceReadyComesFromTheMedia3SurfaceSignal() {
        assertHas(engine, "override fun onSurfaceSizeChanged(", "analyticsListener")
        val body = blockBody(engine, "override fun onSurfaceSizeChanged(")
        assertHas(body, "firstFrameTimeline.surfaceReady(", "onSurfaceSizeChanged")
    }

    @Test
    fun t4EmitsTheCompleteLineOnceInsideOnRenderedFirstFrame() {
        val body = blockBody(engine, "override fun onRenderedFirstFrame(")
        assertHas(body, "firstFrameTimeline.firstFrameRendered()", "onRenderedFirstFrame")
        assertHas(body, "completeLine()", "onRenderedFirstFrame")
        assertHas(body, "DiagnosticLog.i(FIRST_FRAME_TAG)", "onRenderedFirstFrame")
    }

    @Test
    fun timeoutEmitsThePartialLine() {
        val body = blockBody(engine, "private fun checkFirstFrameTimeout()")
        assertHas(body, "partialLine(", "checkFirstFrameTimeout")
        assertHas(body, "DiagnosticLog.w(FIRST_FRAME_TAG)", "checkFirstFrameTimeout")
    }

    @Test
    fun indexNetworkEvidenceIsHandedOverInsideBuildMediaSource() {
        val body = blockBody(engine, "private suspend fun buildMediaSource(")
        // 先清（避免拿上一支视频的索引证据），再由 DASH 分支写入。
        assertHas(body, "dashIndexFromNetwork = null", "buildMediaSource")
        assertHas(body, "dashIndexFromNetwork = build.indexFromNetwork", "buildMediaSource")
    }

    @Test
    fun anatomyUsesItsOwnTagAndTimelineIsTheSharedHolder() {
        assertHas(engine, "const val FIRST_FRAME_TAG = \"FirstFrame\"", "companion")
        assertHas(engine, "FirstFrameTimeline(android.os.SystemClock::elapsedRealtime)", "engine fields")
    }

    @Test
    fun timelineClassReportsEveryStageItAdvertises() {
        // 防"格式漂移"：解剖行是**给脚本解析**的，字段名一个都不许悄悄改。
        val timeline = codeOf("infra/player/src/main/java/com/naaammme/bbspace/infra/player/FirstFrameTimeline.kt")
        val body = blockBody(timeline, "private fun format(")
        listOf(
            "\" t0abs=\"", "\" t1=\"", "\" t2=\"", "\" t3=\"", "\" t4=\"",
            "\" index=\"", "\" codec=\"", "\" decoderInit=\"", "\" total=\"", "\" gen=\""
        ).forEach { assertHas(body, it, "FirstFrameTimeline.format") }
    }
}
