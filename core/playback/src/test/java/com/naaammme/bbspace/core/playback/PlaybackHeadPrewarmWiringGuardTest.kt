package com.naaammme.bbspace.core.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PB-04 的接线守卫：
 * 1. 预热发生在**取流成功之后**，且**不改变** `preloadPlayUrl` 的返回类型（契约不变）；
 * 2. 预热是**尽力而为**（失败被吞掉，不会把预取变成失败）；
 * 3. 门控输入是**网络类型**（"仅 Wi-Fi"由实现内部判，调用方不许自己乱判）；
 * 4. 大小常量单一来源（PB-04 验收 5：1.5MiB / 256KiB 各只定义一处）。
 */
class PlaybackHeadPrewarmWiringGuardTest {

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
            "后缀 `$uniqueSuffix` 命中 ${matches.size} 个文件（要求恰好 1）" +
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

    private fun impl() = codeOf("repository${FS}VideoPlayerRepositoryImpl.kt")

    /** 验收 4：预热在取流之后、且返回类型不变。 */
    @Test
    fun prewarmHappensAfterFetchAndKeepsTheBooleanContract() {
        val body = blockBody(impl(), "override suspend fun preloadPlayUrl(")
        val fetchAt = body.indexOfFirst { it.contains("fetchPlaybackSource(request)") }
        val prewarmAt = body.indexOfFirst { it.contains("headPrewarmer.prewarm(") }
        assertTrue("必须先取流", fetchAt >= 0)
        assertTrue("取流成功后要预热", prewarmAt >= 0)
        assertTrue(
            "预热必须在 fetch 之后（顺序反了就没有 URL 可预热）fetch@$fetchAt prewarm@$prewarmAt",
            fetchAt < prewarmAt
        )
        assertTrue(
            "**返回类型不许变**（仍是 Boolean，且由 runCatching 的成败决定）",
            body.any { it.contains("override suspend fun preloadPlayUrl(request: PlaybackRequest): Boolean") } &&
                body.any { it.contains(".isSuccess") }
        )
    }

    /** 验收 3：预热失败不改变预取结果（尽力而为）。 */
    @Test
    fun prewarmFailureIsSwallowed() {
        val body = blockBody(impl(), "override suspend fun preloadPlayUrl(")
        assertTrue(
            "预热必须包在 runCatchingCancellable 里：失败只吞掉，" +
                "绝不让预取从 true 变 false。",
            body.any { it.contains("runCatchingCancellable {") } &&
                body.any { it.contains("headPrewarmer.prewarm(") }
        )
        val prewarmBlock = blockBody(body, "runCatchingCancellable {")
        assertTrue(
            "预热块内不许出现 `return false` / 抛异常（那会把预取结果带偏）",
            prewarmBlock.none { it.contains("return false") }
        )
    }

    /** 门控输入必须是网络类型（而不是硬编码 true / 别的信号）。 */
    @Test
    fun gateInputIsNetworkType() {
        val body = blockBody(impl(), "override suspend fun preloadPlayUrl(")
        assertTrue(
            "必须传 networkTypeProvider.isUnmetered()（'仅 Wi-Fi' 的判据在实现内部，唯一一处）",
            body.any { it.contains("isUnmetered = networkTypeProvider.isUnmetered()") }
        )
    }

    /** 验收 5：两个大小常量各只定义一处。 */
    @Test
    fun sizeConstantsHaveASingleDefinition() {
        val policy = codeOf("PlaybackHeadPrewarmPolicy.kt")
        assertEquals(
            "VIDEO_HEAD_BYTES 只允许定义一处",
            1,
            policy.count { it.contains("const val VIDEO_HEAD_BYTES") }
        )
        assertEquals(
            "AUDIO_HEAD_BYTES 只允许定义一处",
            1,
            policy.count { it.contains("const val AUDIO_HEAD_BYTES") }
        )
        // 全仓不许再出现这些字面量（防"顺手写死一个 1.5MiB"）
        val strays = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${FS}src${FS}main${FS}") }
            .filterNot { it.path.contains("${FS}build${FS}") }
            .filterNot { it.path.endsWith("PlaybackHeadPrewarmPolicy.kt") }
            .flatMap { file ->
                file.readLines()
                    .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .filter { it.contains("1_572_864") || it.contains("262_144") }
            }
            .toList()
        assertTrue("预热的两个大小字面量只许出现在 PlaybackHeadPrewarmPolicy：$strays", strays.isEmpty())
    }

    /** PB-03 依赖：预热的实现必须写进**同一份**字节缓存（不许另建一份）。 */
    @Test
    fun prewarmWritesIntoTheSameCache() {
        val cache = codeOf("PlaybackMediaCache.kt")
        assertTrue(
            "预热实现必须在 PlaybackMediaCache 内、且用 awaitCache() 拿到同一份 SimpleCache",
            cache.any { it.contains("override suspend fun prewarm(") }
        )
        val prewarmBody = blockBody(cache, "private suspend fun readHeadIntoCache(")
        assertTrue(
            "预热链必须 setCache(awaitCache())（同一份缓存 = 预热真的能被开播命中）",
            prewarmBody.any { it.contains("setCache(awaitCache())") }
        )
        assertTrue(
            "预热链必须用与播放一致的键策略（否则写进去也命中不到）",
            prewarmBody.any { it.contains("PlaybackCacheKeyFactory()") }
        )
    }

    /**
     * B12（2026-10-13，上游 v0.3.6 同步）：**在途取流必须合并**。
     *
     * ## 缺陷
     * 预取（`preloadPlayUrl`）与开播（`fetchPlaybackSource`）是两条**独立发起**的路径，
     * 共用 `PlayUrlCache`，但缓存只在请求**成功之后**才写入 ⇒ "预取在途、用户已经滑到"
     * 这个窗口里两条路径都 cache miss，**同一个 key 发两次完整 gRPC 往返**。
     * 真机读数里 `t1 建源` 占首帧 ~72%，重复一次就是白等一个往返。
     *
     * ## 断言（锚在写法形态上，W20/W21）
     * ① 取流必须经过 `OnDemandRequestCache.getOrLoad(`（合并层在场）；
     * ② 真正发请求的那一段必须被**抽成单独方法**并被合并层包住 ——
     *    否则"合并"只是装饰，网络调用仍可能被并发执行；
     * ③ **结果缓存仍归 `PlayUrlCache`**：合并层不得再缓存结果
     *    （单一真值源；两套 TTL 会打架）。
     */
    @Test
    fun playUrlFetchIsCoalescedInFlight() {
        val impl = impl()
        val fetchBody = blockBody(impl, "override suspend fun fetchPlaybackSource(")
        assertTrue(
            "B12：取流必须走 OnDemandRequestCache.getOrLoad(（在途合并层），" +
                "否则预取与开播会对同一个 key 各发一次完整往返。",
            fetchBody.any { it.contains("inFlightPlayUrl") } &&
                fetchBody.any { it.contains(".getOrLoad(cacheKey)") }
        )
        assertTrue(
            "B12：真正的网络/解析段必须抽成独立方法（loadPlaybackSource）并被合并层包住 ——" +
                "直接写在 fetch 体内的话，合并层包不住它，并发仍会重复发请求。",
            impl.any { it.contains("private suspend fun loadPlaybackSource(") }
        )
        val coalesceAt = fetchBody.indexOfFirst { it.contains(".getOrLoad(cacheKey)") }
        val loadAt = fetchBody.indexOfFirst { it.contains("loadPlaybackSource(request)") }
        assertTrue(
            "B12：loadPlaybackSource 必须作为 getOrLoad 的**参数**传入（在合并层内部执行）" +
                "coalesce@$coalesceAt load@$loadAt。",
            coalesceAt >= 0 && loadAt >= coalesceAt &&
                // 同一行也算合法（lambda 可以写成 `getOrLoad(cacheKey) { loadPlaybackSource(request) }`），
                // 关键是 loadPlaybackSource 出现在**合并调用之后**，而不是 fetch 体里另起一段。
                fetchBody[loadAt].contains("loadPlaybackSource(request)")
        )
        assertTrue(
            "B12 单一真值源：结果缓存只许归 PlayUrlCache 管 —— 合并层必须 maxAgeMs = 0" +
                "（≥ 两套 TTL 会互相打架，且 PlayUrlCache 的失效语义会被绕过）。",
            impl.any { it.contains("maxAgeMs = 0L") }
        )
        assertTrue(
            "B12 回归防护：网络调用不得再出现在 fetchPlaybackSource 体内" +
                "（那会让合并层形同虚设）。",
            fetchBody.none { it.contains("grpcClient.call(") } &&
                fetchBody.none { it.contains("webPlaybackResolver.fetchPlaybackSource(") }
        )
    }
}
