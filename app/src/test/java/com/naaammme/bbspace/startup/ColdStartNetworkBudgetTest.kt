package com.naaammme.bbspace.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 冷启网络预算与更新检查调度的结构守卫（AP-07 ③④）。
 *
 * ## 病灶回顾
 *
 * `MainActivity.onCreate` 里 `autoCheckUpdate()` 直接发起 `api.github.com` 请求 ——
 * 与冷启窗口里的首屏必需网络（首页流 / DNS 预热 / 连接预热）无预算地争抢带宽，
 * 弱网下拉长首屏；且失败静默（release 无日志）。修复 = 更新检查移出冷启
 * （首帧后固定延迟，"可延后"优先级）+ 冷启并发预算显式化（NetworkModule 的
 * Dispatcher 64/5，与 OkHttp 默认值相同，意图进 diff，作为 FL-11 抬并发时的对照基线）。
 *
 * ## 口径（W20：锚在调用上）
 * - `onCreate` 体内**不得**出现独占一行的 `autoCheckUpdate()`；
 * - `api.github.com` 全仓 main 源码**恰好 1 处**（收口在 AppUpdateChecker）；
 * - 失败路径必须留日志（AppUpdateChecker.check 的 onFailure）。
 */
class ColdStartNetworkBudgetTest {

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

    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("找不到 `$suffix` —— 此时断言无意义。")
        return file.readLines()
    }

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        require(!signaturePrefix.startsWith(" ") && !signaturePrefix.startsWith("\t")) {
            "signaturePrefix 带了前导缩进（W23）：本 helper 对行做 trimStart() 比较。"
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

    /**
     * 块体内判定必须跳过注释行 —— 被注释掉的调用等于没有调用
     * （W7；破坏性验证实测：注释式拔线/加行曾骗过 contains 与 trim== 判定）。
     */
    private fun codeLines(body: List<String>): List<String> = body.filter { line ->
        val t = line.trimStart()
        !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
    }

    /** W21 自检：探针形态必须能被抓到/放行。 */
    @Test
    fun scannerSelfCheck() {
        val probe = listOf("            autoCheckUpdate()", "        scheduleUpdateCheckAfterFirstFrame()")
        assertEquals(1, probe.count { it.trim() == "autoCheckUpdate()" })
        assertEquals(
            "自检失败：独占一行判定应只认 autoCheckUpdate 本身",
            0,
            probe.count { it.trim() == "autoCheckUpdate()" && it.contains("scheduleUpdateCheck") }
        )
    }

    /** onCreate 体内不得出现独占一行的 autoCheckUpdate()（冷启窗口内直发 = 病灶回潮）。 */
    @Test
    fun onCreateMustNotCallUpdateCheckInline() {
        val onCreate = codeLines(
            blockBody(
                sourceOf("app/src/main/java/com/naaammme/bbspace/MainActivity.kt"),
                "override fun onCreate("
            )
        )
        // "独占一行调用"按行首形态判定：`autoCheckUpdate()` 及带尾注释的写法都算
        //（尾注释曾骗过 trim== 判定——破坏性验证实测）。
        val offenders = onCreate.filter { it.trimStart().startsWith("autoCheckUpdate()") }
        assertTrue(
            "MainActivity.onCreate 里出现独占一行的 autoCheckUpdate() —— 冷启窗口内直发" +
                "api.github.com 会与首屏必需网络争抢带宽（AP-07 病灶回潮）。" +
                "请改走 scheduleUpdateCheckAfterFirstFrame()（首帧后延迟）。违规行：$offenders",
            offenders.isEmpty()
        )
        assertTrue(
            "onCreate 必须走 scheduleUpdateCheckAfterFirstFrame()（AP-07 ② 的接线锚点）。",
            onCreate.any { it.contains("scheduleUpdateCheckAfterFirstFrame()") }
        )
    }

    /** 调度函数必须在 lifecycleScope 里先 delay 再检查（可延后优先级的形态本身）。 */
    @Test
    fun updateCheckIsScheduledAfterFirstFrame() {
        val body = codeLines(
            blockBody(
                sourceOf("app/src/main/java/com/naaammme/bbspace/MainActivity.kt"),
                "private fun scheduleUpdateCheckAfterFirstFrame()"
            )
        )
        assertTrue(
            "scheduleUpdateCheckAfterFirstFrame 必须先 delay 再 autoCheckUpdate()（否则只是换了个调用点）。",
            body.indexOfFirst { it.contains("delay(") } in 0 until
                (body.indexOfFirst { it.trim().endsWith("autoCheckUpdate()") }.takeIf { it >= 0 } ?: Int.MAX_VALUE)
        )
    }

    /** api.github.com 全仓恰好 1 处（第三方域名收口在 AppUpdateChecker，不许再散点直连）。 */
    @Test
    fun githubApiIsSingleSourced() {
        val sites = mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    return@mapIndexedNotNull null
                }
                if (line.contains("api.github.com")) "${file.name}:${index + 1}" else null
            }
        }
        assertEquals(
            "api.github.com 应恰好 1 处（AppUpdateChecker.RELEASES_API）；实际：$sites",
            1,
            sites.size
        )
    }

    /** 更新检查失败必须留日志（不能静默 return）。 */
    @Test
    fun updateCheckFailureIsLogged() {
        val body = blockBody(
            sourceOf("core/settings/src/main/java/com/naaammme/bbspace/core/settings/update/AppUpdateChecker.kt"),
            "suspend fun check(): Result<AppUpdateCheckResult>"
        )
        assertTrue(
            "AppUpdateChecker.check 的 onFailure 分支必须留 Logger 日志（AP-07 ④：release 无日志=静默失败）。",
            body.any { it.contains("onFailure") } && body.any { it.contains("Logger.w") }
        )
    }

    /** 冷启并发预算必须显式写在 NetworkModule（FL-11 抬并发时的对照基线）。 */
    @Test
    fun coldStartConcurrencyBudgetIsExplicit() {
        val lines = sourceOf("infra/network-http/src/main/java/com/naaammme/bbspace/infra/network/di/NetworkModule.kt")
        assertTrue(
            "NetworkModule 必须显式设置 Dispatcher 并发上限（AP-07 ①：意图进 diff）。",
            lines.any { it.contains(".dispatcher(dispatcher)") } &&
                lines.any { it.contains("maxRequestsPerHost = COLD_START_MAX_REQUESTS_PER_HOST") }
        )
    }
}
