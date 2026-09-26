package com.naaammme.bbspace.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 同步 `Call.execute()` 的 Response 必须成对释放（BACKLOG ST-05）。
 *
 * ## 病灶
 * `okHttpClient.newCall(request).execute()` 返回的 `Response` 持有那条 Exchange。
 * 常见错觉是"`body.string()` 会自己关流，所以不用 use" —— 它只在**正常读完**时关；
 * 读体途中连接被掐、超时、或对端半关，`string()` 是抛着出来的，那次响应就没人关。
 * 全仓只有一个共享连接池（`NetworkModule.kt:49`，10 个连接），漏够十次之后
 * **所有**接口一起排队等池，表现为毫无关联的界面卡住 —— 排查时几乎不会想到是
 * 冷启动那条 buvid 请求干的。
 *
 * ## 为什么白名单而不是"零例外"
 * `TicketGenerator` 是 `val response = withContext(IO){ …execute() }` 紧跟一句
 * `response.use { … }`，语义上已闭合；把它改成表达式形式要顺手动 ticket 的
 * `return`（非局部返回在 `withContext` 里不合法），风险大于收益，留作
 * BACKLOG ST-06（该文件的取消语义）一起做。
 * 白名单是**精确比对**：新增一处裸 execute() 会红；把白名单里那条修好了也会红
 * （提醒你把它从清单里删掉）—— 棘轮只许减不许加。
 */
class HttpResponseReleaseGuardTest {

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
        // 实测基线：全仓 main 源码远多于 100 个文件；阈值沿用 PlaybackHostDismissGuardTest。
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效，此时下面的断言会全部空转。",
            mainSources.size > 100
        )
        val total = mainSources.sumOf { file ->
            codeLines(file).count { EXECUTE_CALL.containsMatchIn(it) }
        }
        assertTrue(
            "全仓只找到 $total 处 `.execute()`（预期 ≥15）—— 正则失配会让本守卫变成永真。",
            total >= 15
        )
    }

    /** 每条未成对释放的 execute()，除了白名单里那一条。 */
    @Test
    fun everySyncCallReleasesItsResponse() {
        val offenders = mutableListOf<String>()
        for (file in mainSources) {
            val rel = file.relativeTo(repoRoot).path.replace('\\', '/')
            codeLines(file).forEachIndexed { index, line ->
                if (!EXECUTE_CALL.containsMatchIn(line)) return@forEachIndexed
                val after = line.substringAfter("execute()")
                if (after.contains(".use") || after.contains(".close")) return@forEachIndexed
                if (rel in WHITELIST) return@forEachIndexed
                offenders += "$rel:${index + 1} -> ${line.trim()}"
            }
        }
        assertTrue(
            "同步 execute() 的 Response 必须包在 .use{} / .close() 里（漏一次就少一个池连接）。\n" +
                offenders.joinToString("\n") +
                "\n若这是有意为之：改 WHITELIST 并写明为什么比 use 更安全；否则" +
                " `val response = …execute()` 请改成 `…execute().use { response -> … }`。",
            offenders.isEmpty()
        )
    }

    /** 白名单本身不许烂掉：列了却已经不存在 = 该清掉。 */
    @Test
    fun whitelistEntriesStillExist() {
        for (rel in WHITELIST) {
            val file = File(repoRoot, rel.replace('/', File.separatorChar))
            assertTrue("白名单里的 $rel 已经没有裸 execute() 了，请从 WHITELIST 删掉。", file.isFile)
        }
    }

    /** 注释与 import 不算代码（写注释提到 execute() 不该让计数虚增）。 */
    private fun codeLines(file: File): List<String> = file.readLines().map { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
            trimmed.startsWith("import ")
        ) {
            ""
        } else {
            line
        }
    }

    private companion object {
        val EXECUTE_CALL = Regex("""\.execute\(\)""")

        /** 已闭合但写法不同的例外（理由见类注释）。 */
        val WHITELIST = setOf(
            "infra/crypto/src/main/java/com/naaammme/bbspace/infra/crypto/TicketGenerator.kt"
        )
    }
}
