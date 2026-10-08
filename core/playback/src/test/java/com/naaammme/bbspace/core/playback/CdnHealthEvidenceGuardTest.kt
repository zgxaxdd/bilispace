package com.naaammme.bbspace.core.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「健康度记账必须基于真实证据」的接线守卫（R-18，2026-10-07 缓存审查）。
 *
 * ## 抓到的口径错配
 *
 * 会话层 `recordCdnHealth()` 只知道"开播选了哪条 host"，不知道字节来自缓存还是网络。
 * 字节缓存命中时播放**不走 CDN 传输层**，但 `PlaybackState.Buffering` 照样发生
 * （起播热身、解码抖动、seek）⇒ 与网络无关的卡顿被算到某条没出力的线头上。
 * 真机实测：28 秒**纯缓存**重播给 `upos-sz-mirrorcosb` 记了 `bufferingCount +4`
 * （−220 分）并让它在设置页显示"易缓冲"。
 *
 * ## 修法与守卫
 *
 * `CdnHealthPolicy.shouldRecordSessionEvent(event, networkActive)`：
 * `Buffering`（惩罚）必须有网络证据；`PlaybackReady`（奖励、封顶）照记。
 * 本守卫钉住"会话层真的调了这条判据、并且把引擎的网络活动信号传了进去"。
 */
class CdnHealthEvidenceGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun codeOf(uniqueSuffix: String): List<String> {
        val normalized = uniqueSuffix.replace('/', File.separatorChar)
        val matches = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
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

    /** 会话层必须过判据、且把引擎的网络活动信号传进去。 */
    @Test
    fun sessionAccountingConsultsNetworkEvidence() {
        val session = codeOf("core/playback/StreamPlaybackSessionImpl.kt")
        // 判据落在统一的 `recordHealthEvent(...)`（ready/buffering/起播超时三条都走它）。
        val body = blockBody(session, "private fun recordHealthEvent(")
        assertTrue(
            "会话层记账必须先过 `CdnHealthPolicy.shouldRecordSessionEvent(...)` —— " +
                "否则纯缓存播放会把与网络无关的卡顿算到某条线头上（实测 −220 分）。",
            body.any { it.contains("CdnHealthPolicy.shouldRecordSessionEvent(") }
        )
        assertTrue(
            "判据必须吃引擎的 `isNetworkActiveWithin(...)`（缓存命中 ⇒ 无网络读 ⇒ 不记缓冲惩罚）",
            body.any { it.contains("playerEngine.isNetworkActiveWithin(") }
        )
        assertTrue(
            "缓冲与起播超时两条惩罚路径都必须走这个统一入口",
            blockBody(session, "private fun recordCdnHealth(")
                .any { it.contains("recordHealthEvent(CdnHealthPolicy.Event.Buffering)") } &&
                blockBody(session, "private fun recordCdnHealth(")
                    .any { it.contains("recordHealthEvent(CdnHealthPolicy.Event.FirstFrameTimeout)") }
        )
    }

    /** 记账目标必须是"真正在供数的那条 host"，不是"开播选中的那条"（R-19）。 */
    @Test
    fun sessionAttributesToTheServingHost() {
        val session = codeOf("core/playback/StreamPlaybackSessionImpl.kt")
        assertTrue(
            "会话层必须优先用 `playerEngine.currentServingHost()`，拿不到才退回选中那条 —— " +
                "否则换线之后会把好线的功劳记到坏线头上",
            session.any { it.contains("playerEngine.currentServingHost()") }
        )
        assertTrue(
            "引擎必须真的把这个信号接进数据源链（不是只声明一个方法）",
            codeOf("infra/player/SingleFileDashMediaSourceFactory.kt")
                .any { it.contains("servingHostListener = servingHostListener") }
        )
        assertTrue(
            "换线数据源要在**真正 open 成功**的那条 URL 上回调 host",
            codeOf("infra/player/CdnFailoverDataSource.kt")
                .any { it.contains("servingHostListener::onServingHost") }
        )
        assertTrue(
            "预取链**不许**回传供数 host（后台预取不该影响\"播放在用哪条\"）",
            blockBody(codeOf("infra/player/PlaybackMediaCache.kt"), "suspend fun prefetchRange(")
                .none { it.contains("servingHostListener") }
        )
    }

    /** 起播超时（`FirstFrameTimeout`，−180 分）从 R-19 起必须真的产生。 */
    @Test
    fun firstFrameTimeoutIsActuallyReported() {
        val engine = codeOf("infra/player/Media3PlayerEngine.kt")
        assertTrue(
            "引擎必须按 `FirstFrameTimeoutPolicy` 判定（纯函数、有边界测试）",
            blockBody(engine, "private fun checkFirstFrameTimeout(")
                .any { it.contains("FirstFrameTimeoutPolicy.isTimedOut(") }
        )
        assertTrue(
            "判定必须挂在 500ms 轮询里（否则没人调它）",
            blockBody(engine, "private fun updatePlaybackProgress(")
                .any { it.contains("checkFirstFrameTimeout()") }
        )
        assertTrue(
            "计数器必须进 `PlayerPlaybackState`（会话层靠增量消费）",
            engine.any { it.contains("firstFrameTimeoutSeq = firstFrameTimeoutSeq") } &&
                codeOf("infra/player/PlayerPlaybackState.kt").any { it.contains("val firstFrameTimeoutSeq") }
        )
        assertTrue(
            "会话层必须把它记成 `Event.FirstFrameTimeout`（不能只加个字段不用）",
            codeOf("core/playback/StreamPlaybackSessionImpl.kt")
                .any { it.contains("state.firstFrameTimeoutSeq != lastFirstFrameTimeoutSeq") } &&
                codeOf("core/playback/StreamPlaybackSessionImpl.kt")
                    .any { it.contains("CdnHealthPolicy.Event.FirstFrameTimeout") }
        )
    }

    /** 断网恢复补一次重试（A2）：接线、一次性、摘回调，三条都要有。 */
    @Test
    fun networkReturnRetriesExactlyOnceAndUnregisters() {
        val engine = codeOf("infra/player/Media3PlayerEngine.kt")
        val callback = blockBody(engine, "private val networkCallback = object")
        assertTrue(
            "回调里必须过 `NetworkReturnRetryPolicy.shouldRetryOnNetworkReturn(...)`（五条判据）",
            callback.any { it.contains("NetworkReturnRetryPolicy.shouldRetryOnNetworkReturn(") }
        )
        assertTrue(
            "必须去抖（回调可能早于路由就绪）",
            callback.any { it.contains("delay(NetworkReturnRetryPolicy.DEBOUNCE_MS)") }
        )
        assertTrue(
            "必须一次性：补之前把 `offlineRetryUsed` 置 true（防重试风暴）",
            callback.any { it.contains("offlineRetryUsed = true") }
        )
        assertTrue(
            "断网跳过重建时必须记下待办，否则恢复后没人补",
            blockBody(engine, "override fun onPlayerError(").isEmpty() ||
                engine.any { it.contains("pendingOfflineRetry = true") }
        )
        assertTrue(
            "回调必须在 release() 里摘掉（引擎是单例，回调会一直持有它）",
            blockBody(engine, "override fun release(")
                .any { it.contains("unregisterNetworkCallback(networkCallback)") }
        )
    }

    /** 引擎必须能给出"最近有没有真实网络读"的信号，且非 DASH 链要退化成"照记"。 */
    @Test
    fun engineProvidesNetworkActivityWithSafeFallback() {
        val engine = codeOf("infra/player/Media3PlayerEngine.kt")
        val body = blockBody(engine, "override fun isNetworkActiveWithin(")
        assertTrue(
            "非 DASH 链拿不到 CDN 字节计数 ⇒ 必须返回 true（照老口径记），不许把记账整体关掉",
            body.any { it.contains("_currentSource.value !is EngineSource.SingleFileDash") } &&
                body.any { it.contains("return true") }
        )
        assertTrue(
            "信号必须来自 `CdnTransferRuntime` 的字节计数（缓存命中时它不涨）",
            body.any { it.contains("CdnTransferRuntime.enabled") }
        )
        val sample = blockBody(engine, "private fun sampleNetworkActivity(")
        assertTrue(
            "采样必须比对 `networkBytes` 增量（放在 500ms 进度轮询里，别每次读都取快照）",
            sample.any { it.contains("CdnTransferRuntime.state.value.networkBytes") }
        )
        assertTrue(
            "采样点必须在进度轮询里被调用",
            blockBody(engine, "private fun updatePlaybackProgress(")
                .any { it.contains("sampleNetworkActivity()") }
        )
        assertTrue(
            "建源时索引 MISS 必须显式种下「网络活动」—— 否则起播那一刻永远没有证据，" +
                "「起播卡顿」这一路信号整个丢掉",
            blockBody(engine, "private suspend fun buildMediaSource(")
                .any { it.contains("if (build.indexFromNetwork) markNetworkActivity()") }
        )
        assertTrue(
            "索引是否走网络必须由建源结果如实传出（不是猜的）",
            codeOf("infra/player/SingleFileDashMediaSourceFactory.kt")
                .any { it.contains("indexFromNetwork = streams.any { !it.fromCache }") }
        )
    }

    /** 索引读的成败只算在"真的走了网络"的那条候选头上（缓存命中不记）。 */
    @Test
    fun cachedIndexReadIsNotCreditedToAnyLine() {
        val factory = codeOf("infra/player/SingleFileDashMediaSourceFactory.kt")
        val body = blockBody(factory, "private suspend fun buildDashStream(")
        assertTrue(
            "回传健康度前必须排除缓存命中（`result.fromCache`）—— 字节来自磁盘时与 host 通不通无关",
            body.any { it.contains("!result.fromCache") }
        )
        assertTrue(
            "索引读结果必须带上 `fromCache`（来自 `PrefixRead`，不是自己猜）",
            factory.any { it.contains("fromCache = prefix.fromCache") }
        )
    }

    /** 预取下载的成败也要入账（与索引读/播放读对齐，不再只有进程内 rank 统计）。 */
    @Test
    fun prefetchDownloadsReportLineOutcome() {
        val cache = codeOf("infra/player/PlaybackMediaCache.kt")
        assertTrue(
            "`PlaybackMediaCache` 必须持有 `lineOutcomeListener`（DI 里绑到 CdnHealthLineOutcomeListener）",
            cache.any { it.contains("private val lineOutcomeListener: CdnLineOutcomeListener") }
        )
        val prefetch = blockBody(cache, "suspend fun prefetchRange(")
        assertTrue(
            "预取的 `CdnPlaybackDataSource` 必须传 `lineOutcomeListener = lineOutcomeListener`，",
            prefetch.any { it.contains("lineOutcomeListener = lineOutcomeListener") }
        )
    }
}
