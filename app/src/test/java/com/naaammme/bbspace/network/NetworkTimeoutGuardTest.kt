package com.naaammme.bbspace.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 全站唯一 OkHttpClient 的超时纪律（BACKLOG PF-01 / FINDINGS D2）。
 *
 * ## 为什么是禁令而不是数值审查
 * `NetworkModule.provideOkHttpClient` 建的是全仓唯一的 client（`OkHttpClient.Builder()`
 * 全仓只出现一次），首页、详情、下载、弹幕、票据与流媒体全共用一套超时。
 * 于是**任何"顺手补一个 callTimeout"都会给几百 MB 的下载和流式读判死刑** ——
 * `callTimeout` 计的是整个 call（含 body 传输、重定向与全部重试），不是"请求超时"。
 * 这类改动在编译期和单测里都不会报错，只在用户点下载时表现为"永远失败"。
 *
 * 数值本身该收紧到多少（connect 从 10s 往下）属网络手感，需真机对照 → L4-21，
 * 本守卫只管"不许出现 callTimeout / 不许把三个显式超时删掉"。
 */
class NetworkTimeoutGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val moduleRel =
        "infra/network-http/src/main/java/com/naaammme/bbspace/infra/network/di/NetworkModule.kt"

    private fun code(): String {
        val file = File(repoRoot, moduleRel.replace('/', File.separatorChar))
        assertTrue("找不到 $moduleRel —— 路径变了本守卫会空转", file.isFile)
        return file.readLines().joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) "" else line
        }
    }

    /** 先确认全仓确实只有这一处建 client（否则本守卫的保护范围是假的）。 */
    @Test
    fun thereIsExactlyOneSharedClientBuilder() {
        val builders = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .filter { it.readText().contains("OkHttpClient.Builder()") }
            .map { it.relativeTo(repoRoot).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "`OkHttpClient.Builder()` 现在出现在 ${builders.size} 个文件里：$builders。" +
                "本守卫只审 NetworkModule；新增 client 要么复用注入的那一个，" +
                "要么把它的超时一并纳进本文件的规则。",
            builders.size <= MAX_CLIENT_BUILDERS
        )
    }

    @Test
    fun sharedClientSetsItsTimeoutsExplicitly() {
        val code = code()
        listOf("connectTimeout", "readTimeout", "writeTimeout").forEach { name ->
            assertTrue(
                "共享 client 必须显式写 $name（原先一个都没写，全靠 OkHttp 默认值，" +
                    "于是升级会静默变行为、也没人看得出这里是刻意的）",
                Regex("""\.$name\(""").containsMatchIn(code)
            )
        }
    }

    /** 这条是整个守卫的目的：callTimeout 会砍断下载与流式读。 */
    @Test
    fun callTimeoutIsNeverSetOnTheSharedClient() {
        assertFalse(
            "禁止在共享 client 上设 callTimeout：它覆盖整个 call（含 body 传输与全部重试），" +
                "等于给几百 MB 的下载和直播/点播的流式读判死刑。" +
                "确实需要总时限的**单条请求**，请用 per-request 的 timeout()，" +
                "并把它放进自己的 client，不要动全站这一个。",
            Regex("""callTimeout\(""").containsMatchIn(code())
        )
    }

    /** 不许把超时设成 0（= 无限），也不许把值改到离谱。 */
    @Test
    fun timeoutsAreNeitherZeroedNorAbsurd() {
        val code = code()
        val zeros = Regex("""\.(connect|read|write)Timeout\(\s*0\s*L?\s*,""").findAll(code).map { it.value }.toList()
        assertTrue("这些超时被设成 0（无限）：$zeros", zeros.isEmpty())

        val constants = Regex("""private const val (CONNECT|READ|WRITE)_TIMEOUT_MS = (\d+)_?(\d*)L""")
            .findAll(code())
            .associate { it.groupValues[1] to (it.groupValues[2] + it.groupValues[3]).toLong() }
        assertEquals("三个超时常量都要在（写死在 Builder 上也行，但值必须可见）", 3, constants.size)
        constants.forEach { (name, value) ->
            assertTrue("$name = $value ms 超出允许区间 1..60_000", value in 1L..60_000L)
        }
        // 与本批写下的值一致：显式化但不改变行为
        assertEquals(
            "connect 仍应是 OkHttp 默认的 10s —— 收紧取值要真机对照（L4-21）",
            TimeUnit.SECONDS.toMillis(10),
            constants["CONNECT"]
        )
    }

    private companion object {
        /** 目前只有 NetworkModule 一处；要放宽必须连着把新 client 的超时纳进本守卫。 */
        const val MAX_CLIENT_BUILDERS = 1
    }
}
