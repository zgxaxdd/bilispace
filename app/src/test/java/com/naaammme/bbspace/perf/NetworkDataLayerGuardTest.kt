package com.naaammme.bbspace.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 网络与数据层修复的接线守卫（AP-21 ② / AP-22 ③ / AP-23 / AP-24）。
 *
 * 判定锚在调用形态上（W20），跳过注释行（W7）：
 * - AP-21：`AppSigner.sign` 必须在 `withContext(Dispatchers.IO)` 之后（签名与 header
 *   构建不许留在调用方（主）线程）；
 * - AP-22：通知大图标 ImageRequest 必须有 `.size(...)`；404 回退的 `substringBefore('@')`
 *   全仓**恰好 1 处**（收口在 ImageClientModule，不许再散点第二份"同资源第二条键"）；
 * - AP-23：搜索历史查询必须带 LIMIT，且写入走 recordAndTrim（插入与淘汰同事务）；
 * - AP-24：三处 Room Flow 必须 distinctUntilChanged；BiliDns 缓存必须有容量上限。
 *
 * AP-23 的"本机 SQLite 回放"未落地为真数据库测试：本仓既有 Room 纪律（
 * RoomSchemaGuardTest / RoomMigrationCopyListTest）就是对着 **SQL 字面量** 校验的，
 * 引入 JDBC Room 回放会带来一套仅测试用的重依赖；此处沿用仓内既有口径。
 */
class NetworkDataLayerGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun sourceOf(suffix: String): List<String> {
        val file = File(repoRoot, suffix.replace('/', File.separatorChar))
        assertTrue("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。", file.isFile)
        return file.readLines()
    }

    private fun codeLines(lines: List<String>): List<String> = lines.filter { line ->
        val t = line.trimStart()
        !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import "))
    }

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        require(!signaturePrefix.startsWith(" ") && !signaturePrefix.startsWith("\t")) {
            "signaturePrefix 带了前导缩进（W23）。"
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

    /** AP-21：三个签名方法内，签名必须发生在 withContext(IO) 之后。 */
    @Test
    fun signingRunsInsideIoBlock() {
        val lines = codeLines(
            sourceOf("infra/network-http/src/main/java/com/naaammme/bbspace/infra/network/BiliRestClient.kt")
        )
        val signatures = listOf(
            "suspend fun getSigned(",
            "suspend fun postSignedRaw(",
            "suspend fun postSignedReadHeader("
        )
        for (sig in signatures) {
            val body = blockBody(lines, sig)
            val ioAt = body.indexOfFirst { it.contains("withContext(Dispatchers.IO)") }
            val signAt = body.indexOfFirst { it.contains("AppSigner.sign(") }
            assertTrue(
                "$sig 必须把 AppSigner.sign 放进 withContext(Dispatchers.IO) 块内" +
                    "（AP-21：MD5 签名 + ~8 次 SP 读的 header 构建不许留在主线程）。" +
                    "io@$ioAt sign@$signAt",
                ioAt in 0 until signAt
            )
        }
    }

    /** AP-22：通知大图标必须带 size；404 回退的剥后缀写法全仓恰好 1 处。 */
    @Test
    fun notificationIconIsSizedAndFallbackIsSingleSourced() {
        val service = codeLines(
            sourceOf("app/src/main/java/com/naaammme/bbspace/playback/PlaybackService.kt")
        )
        val iconBody = blockBody(service, "override fun getCurrentLargeIcon(")
        assertTrue(
            "通知大图标 ImageRequest 必须 .size(NOTIFICATION_ICON_SIZE_PX, …) —— " +
                "enqueue 不经 Compose 约束测量，缺 size 会按封面原图整幅解码（AP-22）。",
            iconBody.any { it.contains(".size(NOTIFICATION_ICON_SIZE_PX, NOTIFICATION_ICON_SIZE_PX)") }
        )
        val mainSources = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
        val fallbackSites = mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@mapIndexedNotNull null
                // 只锚"404 回退换 URL"这一形态：从**请求 URL** 上剥后缀；
                // BiliImageUrl / PortraitSwipeQueue 里的 `body.substringBefore('@')`
                // 是换尺寸档位的 URL 构建（正确用法），不在此列。
                if (line.contains("req.url.toString().substringBefore('@')")) "${file.name}:${index + 1}" else null
            }
        }
        assertEquals(
            "封面 404 回退（req.url 剥尺寸后缀重请求）应恰好 1 处（收口在 ImageClientModule）；" +
                "散点第二份 = 同一资源的第二条键（FINDINGS P1）。实际：$fallbackSites",
            1,
            fallbackSites.size
        )
    }

    /** AP-23：查询带 LIMIT + 写入走 recordAndTrim（插入与淘汰同事务）。 */
    @Test
    fun searchHistoryIsBoundedAndTrimmed() {
        val dao = sourceOf("core/search/src/main/java/com/naaammme/bbspace/core/search/SearchHistoryDao.kt")
        val daoText = dao.joinToString("\n")
        assertTrue(
            "搜索历史的两个 observe 查询都必须带 LIMIT（AP-23：不再全表读）。",
            dao.joinToString("\n").let { text ->
                Regex("""LIMIT :limit""").findAll(text).count() == 3 // 2 个 observe + 1 个 trim 子查询
            }
        )
        assertTrue(
            "SearchHistoryDao 必须提供 trimToLimit + recordAndTrim（插入与淘汰同事务）。",
            daoText.contains("protected abstract suspend fun trimToLimit(limit: Int)") &&
                daoText.contains("open suspend fun recordAndTrim(")
        )
        val repo = codeLines(
            sourceOf("core/search/src/main/java/com/naaammme/bbspace/core/search/SearchRepository.kt")
        )
        assertTrue(
            "SearchRepository.recordHistory 必须走 recordAndTrim（不许回退到裸 record）。",
            repo.any { it.contains("searchHistoryDao.recordAndTrim(") }
        )
    }

    /** AP-24：三处 Room Flow 去重 + DNS 缓存上限。 */
    @Test
    fun roomFlowsDedupeAndDnsCacheIsBounded() {
        val flows = listOf(
            "core/history/src/main/java/com/naaammme/bbspace/core/history/PlaybackHistoryRepository.kt",
            "core/download/src/main/java/com/naaammme/bbspace/core/download/VideoDownloadRepository.kt",
            "core/search/src/main/java/com/naaammme/bbspace/core/search/SearchRepository.kt"
        )
        for (rel in flows) {
            val lines = codeLines(sourceOf(rel))
            assertTrue(
                "$rel 的对外 Flow 必须 distinctUntilChanged（AP-24：同值重复发射驱动下游重组）。",
                lines.any { it.contains("distinctUntilChanged()") }
            )
        }
        val dns = codeLines(
            sourceOf("infra/network-http/src/main/java/com/naaammme/bbspace/infra/network/dns/BiliDns.kt")
        )
        assertTrue(
            "BiliDns.cache 必须有容量上限与淘汰（AP-24：CDN 主机名轮换会撑爆无上限 map）。",
            dns.any { it.contains("DNS_CACHE_MAX_ENTRIES") } &&
                dns.any { it.contains("cache.size >= DNS_CACHE_MAX_ENTRIES") }
        )
    }
}
