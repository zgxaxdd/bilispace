package com.naaammme.bbspace.infra.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5/N2 观测埋点的**接线**守卫。
 *
 * 与 `FirstFrameAnatomyWiringGuardTest` 同一动机：埋点最常见的失效不是写错，而是**后来被悄悄拆掉**
 * —— 编译器与功能测试都不会管"引擎里有没有读 `videoDecoderCounters`"，
 * 而指标一旦断线，日志看起来仍然正常（只是永远 `dropped=0`），没人会发现。
 *
 * 每条都按 W20/W21 写成"匹配调用形态 + 限定在对应函数体内"，并给出一条**反向**断言
 * （写路径不许挂缓存监听器）—— 只有正向断言的守卫，改坏了也不红。
 */
class PlaybackObservabilityWiringGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    private fun linesOf(uniqueSuffix: String): List<String> {
        val normalized = uniqueSuffix.replace('/', File.separatorChar)
        val matches = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${FS}src${FS}main${FS}") }
            .filterNot { it.path.contains("${FS}build${FS}") }
            .filter { it.path.endsWith(normalized) }
            .toList()
        assertEquals(
            "后缀 `$uniqueSuffix` 命中 ${matches.size} 个文件（要求恰好 1）：" +
                matches.joinToString { it.path },
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

    private fun has(body: List<String>, needle: String) = body.any { it.contains(needle) }

    private val engine by lazy {
        linesOf("infra/player/src/main/java/com/naaammme/bbspace/infra/player/Media3PlayerEngine.kt")
    }
    private val cache by lazy {
        linesOf("infra/player/src/main/java/com/naaammme/bbspace/infra/player/PlaybackMediaCache.kt")
    }

    // ---- R5：解码器复用评估 ----

    @Test
    fun decoderReuseEvaluationIsTurnedIntoALogLine() {
        val body = blockBody(engine, "override fun onVideoInputFormatChanged(")
        assertTrue("onVideoInputFormatChanged 必须把评估结果交给 DecoderReuseReport", has(body, "DecoderReuseReport.line("))
        // 必须读**真值**而不是常量：result / discardReasons / decoderName 三个都缺一不可。
        assertTrue(has(body, "evaluation?.result"))
        assertTrue(has(body, "evaluation?.discardReasons"))
        assertTrue(has(body, "evaluation?.decoderName"))
        assertTrue("要走独立 tag，脚本才分得开", has(body, "DiagnosticLog.i(DECODER_REUSE_TAG)"))
    }

    // ---- R5：丢帧与解码器重建 ----

    @Test
    fun frameAndDecoderCountersAreSampledFromMedia3NotGuessed() {
        val body = blockBody(engine, "private fun reportPlaybackWatch(")
        assertTrue("必须读 Media3 的视频解码器计数", has(body, "player.videoDecoderCounters"))
        listOf(
            "renderedOutputBufferCount",
            "droppedBufferCount",
            "skippedOutputBufferCount",
            "decoderInitCount"
        ).forEach { field ->
            assertTrue("缺 `$field` 就等于这一路的观测白埋", has(body, field))
        }
        // 取不到 counters 只能降级成"这一行少一个字段"，不许打断进度轮询。
        assertTrue(has(body, "runCatching { player.videoDecoderCounters }"))
    }

    @Test
    fun watchdogIsSampledInsideTheExistingProgressPoll() {
        // 复用 500ms 轮询，不另起循环（多一个 while(true) delay 就是多一次唤醒）。
        val body = blockBody(engine, "private fun updatePlaybackProgress()")
        assertTrue("观测必须挂在已有的进度轮询里", has(body, "reportPlaybackWatch(player)"))
        assertTrue(has(engine, "DiagnosticLog.i(WATCH_TAG)"))
    }

    @Test
    fun cacheNetworkSplitKeepsDisabledRuntimeOutOfTheRatio() {
        val body = blockBody(engine, "private fun reportPlaybackWatch(")
        // CDN 传输层关掉时网络字节必须按 0 算 —— 否则命中率会假报（拿不到分母）。
        assertTrue("判据要用 CdnTransferRuntime.enabled", has(body, "CdnTransferRuntime.enabled"))
        assertTrue("命中率的分母要用网络计数", has(body, "CdnTransferRuntime.state.value.networkBytes"))
        assertTrue("命中率的分子要用缓存计数", has(body, "PlaybackByteCacheMetrics.cachedBytes()"))
    }

    // ---- N2：缓存监听器 ----

    @Test
    fun cacheReadListenerIsAccumulateNotAssign() {
        val body = blockBody(cache, "private val cacheReadEventListener")
        assertTrue(
            "onCachedBytesRead 给的是增量，必须 record（累加）而不是赋值",
            has(body, "PlaybackByteCacheMetrics.recordCachedBytesRead(cachedBytesRead)")
        )
        assertTrue(has(body, "PlaybackByteCacheMetrics.recordCacheIgnored()"))
    }

    @Test
    fun cacheReadListenerIsAttachedToBothReadPaths() {
        val playback = blockBody(cache, "suspend fun wrapForPlayback(")
        val index = blockBody(cache, "suspend fun readPrefixThroughCache(")
        assertTrue("播放链没挂监听器 ⇒ 分段字节命中率永远测不到", has(playback, ".setEventListener(cacheReadEventListener)"))
        assertTrue("索引链没挂监听器 ⇒ 切页省了多少索引字节永远测不到", has(index, ".setEventListener(cacheReadEventListener)"))
    }

    @Test
    fun writePathsAreDeliberatelyNotCountedAsHits() {
        // 反向断言：预热与分段预取是"往缓存里写"，把它们的字节算进命中率会得出
        // "预取越猛命中率越高"的假结论 —— 所以这两条链**不许**挂监听器。
        val head = blockBody(cache, "private suspend fun readHeadIntoCache(")
        val prefetch = blockBody(cache, "suspend fun prefetchRange(")
        assertFalse("预热链不该进命中率：$head", has(head, ".setEventListener(cacheReadEventListener)"))
        assertFalse("预取链不该进命中率：$prefetch", has(prefetch, ".setEventListener(cacheReadEventListener)"))
    }

    @Test
    fun cacheIgnoredReasonIsOnlyCountedNotBucketed() {
        // 钉住这条口径的理由（避免下一个人"顺手"把 reason 变成维度）：
        // 本仓读路径都带明确 length，reason 只会来自未包缓存的链。
        val body = blockBody(cache, "override fun onCacheIgnored(reason: Int)")
        // 除签名行以外不许出现 reason（那意味着有人把它升级成了维度）。
        assertTrue(
            "只计数，不按 reason 分流",
            body.drop(1).none { it.contains("reason") }
        )
    }
}
