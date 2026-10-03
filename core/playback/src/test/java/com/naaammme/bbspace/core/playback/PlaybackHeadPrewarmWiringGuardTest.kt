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
}
