package com.naaammme.bbspace.infra.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PB-03 的接线守卫（结构断言）：
 * 1. **缓存包在换线工厂之外**（两条链都要）——套在内侧会让主/备线路各存一份同一分片；
 * 2. **`SimpleCache` 只构造一处，且构造落在 IO 调度器上下文里**（目录锁 + `@WorkerThread`）；
 * 3. **`MemoryTrimPolicy` 不被牵动**（磁盘缓存与内存 trim 解耦）。
 *
 * 防假绿：先确认文件读到；扫描前过滤注释与 import 行。
 */
class PlaybackCacheWiringGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    private fun codeOf(uniqueSuffix: String): List<String> {
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

    /** 验收 3：DASH 链 —— 缓存必须在换线工厂**之外**（先建 failover，再交给缓存包）。 */
    @Test
    fun dashChainWrapsCacheOutsideFailover() {
        val body = blockBody(
            codeOf("infra${FS}player${FS}SingleFileDashMediaSourceFactory.kt"),
            "private suspend fun buildDataSourceFactory("
        )
        val failoverAt = body.indexOfFirst { it.contains("CdnFailoverDataSourceFactory(") }
        val cacheAt = body.indexOfFirst { it.contains("playbackMediaCache.wrapForPlayback(") }
        assertTrue("必须构造换线工厂", failoverAt >= 0)
        assertTrue("必须包一层播放缓存", cacheAt >= 0)
        assertTrue(
            "**位置错了**：必须先构造 CdnFailoverDataSourceFactory、再把它交给缓存包" +
                "（套在内侧 = 主备线路各存一份同一分片）。failover@$failoverAt cache@$cacheAt",
            failoverAt < cacheAt
        )
        assertTrue(
            "缓存必须包在**整条链**之外：现在是 `playbackMediaCache.wrapForPlayback(parallelAware)`，" +
                "而 parallelAware = CdnPlaybackDataSource(upstream = failover.createDataSource())。" +
                "2026-10-06 移植源仓库的 CdnPlaybackDataSource 后多了这一层，**源仓库同形**：" +
                "Cache → CdnPlaybackDataSource → 上游（见 BiliPai PlaybackMediaCache.kt:79-90）。" +
                "注意牙齿没丢：缓存仍必须在换线工厂**之外** —— 套在内侧会让主备线路各存一份同一分片。",
            body.any { it.contains("wrapForPlayback(parallelAware)") } &&
                body.any { it.contains("upstream = failover.createDataSource()") }
        )
    }

    /** 验收 3（第二条链）：Progressive/直播链同样顺序。 */
    @Test
    fun progressiveChainWrapsCacheOutsideFailover() {
        val body = blockBody(
            codeOf("infra${FS}player${FS}Media3PlayerEngine.kt"),
            "private suspend fun buildProgressiveMediaSourceFactory("
        )
        val failoverAt = body.indexOfFirst { it.contains("CdnFailoverDataSourceFactory(") }
        val cacheAt = body.indexOfFirst { it.contains("playbackMediaCache.wrapForPlayback(") }
        assertTrue(failoverAt >= 0 && cacheAt >= 0)
        assertTrue(
            "Progressive 链也必须先换线工厂再包缓存（failover@$failoverAt cache@$cacheAt）",
            failoverAt < cacheAt
        )
        assertTrue(
            "包之前必须过闸门（直播/本地文件不缓存）",
            body.any { it.contains("PlaybackCacheGate.shouldCache(") }
        )
    }

    /** 验收 4：`SimpleCache` 单处构造 + 落在 IO。 */
    @Test
    fun simpleCacheIsBuiltOnceOnIoDispatcher() {
        val whole = codeOf("infra${FS}player${FS}PlaybackMediaCache.kt")
        val constructions = whole.count { it.contains("SimpleCache(") }
        assertEquals(
            "SimpleCache 只允许在**一处**构造（目录锁：同目录第二个实例直接抛异常）",
            1,
            constructions
        )
        val buildBody = blockBody(whole, "private fun buildCache(")
        assertTrue("构造必须在 buildCache 里", buildBody.any { it.contains("SimpleCache(") })
        val awaitBody = blockBody(whole, "suspend fun awaitCache(")
        assertTrue(
            "懒建必须落在 Dispatchers.IO（构造器带 @WorkerThread，DI 图在主线程）",
            awaitBody.any { it.contains("withContext(Dispatchers.IO)") }
        )
    }

    /** 验收 5：`MemoryTrimPolicy` 与磁盘缓存解耦（本轮不许动它）。 */
    @Test
    fun memoryTrimPolicyStaysDecoupledFromDiskCache() {
        val trim = codeOf("memory${FS}MemoryTrimPolicy.kt")
        assertTrue(
            "MemoryTrimPolicy 不许出现播放缓存（磁盘缓存只能从设置页清；" +
                "BiliApplication 明文'不清磁盘缓存'）",
            trim.none { it.contains("PlaybackMediaCache") || it.contains("playback_media_cache") }
        )
        val app = codeOf("bbspace${FS}BiliApplication.kt")
        assertTrue(
            "应用级 trim 路径也不许去清播放缓存",
            app.none { it.contains("PlaybackMediaCache") }
        )
    }

    /** 清理必须有播放中守卫（设置页不能抽掉正在读的文件）。 */
    @Test
    fun clearIsBlockedWhileSourceIsActive() {
        val whole = codeOf("infra${FS}player${FS}PlaybackMediaCache.kt")
        val clearBody = blockBody(whole, "override suspend fun clear(")
        assertTrue(
            "clear() 必须先判 isSourceActive 并**拒绝**（返回 false），不能等到删目录才发现",
            clearBody.take(6).any { it.contains("isSourceActive") } &&
                clearBody.any { it.contains("return@withContext false") }
        )
        assertTrue(
            "release() 与 stopForReuse() 必须把 active 置回 false（否则清理永久被拒）",
            codeOf("infra${FS}player${FS}Media3PlayerEngine.kt")
                .count { it.contains("playbackMediaCache.markSourceActive(false)") } >= 2
        )
    }
}
