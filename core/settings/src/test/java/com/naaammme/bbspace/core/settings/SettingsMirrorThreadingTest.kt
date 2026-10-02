package com.naaammme.bbspace.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置镜像线程纪律的结构守卫 + 快照判等纯函数的状态测（AP-05）。
 *
 * ## 病灶回顾
 *
 * `themeConfig` 的 `map {}` 执行在**收集方上下文**，而 MainActivity /
 * VideoDetailActivity 的收集都在 `LaunchedEffect`（主线程）→ 每次创建这两个
 * Activity 都会在主线程跑一遍 `persistSettingsMirror`，其首次 `getSharedPreferences`
 * 会 `awaitLoadedLocked()`（读盘 + 解析 XML）—— `FINDINGS` P4「主线程不做盘 IO」
 * 教训在"回填"路径上的复活。修法：`themeConfig` 链尾加 `flowOn(Dispatchers.IO)`
 * （map 整体移到 IO，下游收集方仍按自己上下文收值），写路径
 * `editSettingsMirrored` 同样包 `withContext(Dispatchers.IO)`。
 *
 * ## 为什么是结构守卫
 *
 * "map 跑在哪个线程"是 Flow 装配期属性，JVM 上没有 Android 主线程可争——
 * 行为测不出差异，但**写法回退**（删掉 flowOn / 摘掉 withContext）会立刻把
 * 盘 IO 挪回主线程。所以钉写法，配 W21 正反例自检。
 */
class SettingsMirrorThreadingTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val appSettingsLines: List<String> by lazy {
        val file = File(repoRoot, "core/settings/src/main/java/com/naaammme/bbspace/core/settings/AppSettings.kt")
        assertTrue("找不到 AppSettings.kt（repoRoot=${repoRoot.absolutePath}）", file.isFile)
        file.readLines()
    }

    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@count false
            if (trimmed.startsWith("import ")) return@count false
            re.containsMatchIn(line)
        }
    }

    /** 取以 [signaturePrefix] 开头的函数体（含签名），按花括号配平定界。 */
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

    /** W21 正例自检：正则必须抓得到真实形态，否则下面的断言是永真。 */
    @Test
    fun scannerSelfCheck() {
        val probe = listOf("        .flowOn(Dispatchers.IO)")
        assertEquals(
            "自检失败：flowOn 的正则抓不到调用形态",
            1,
            countMatching(probe, """\.flowOn\(Dispatchers\.IO\)""")
        )
        val negative = listOf("// .flowOn(Dispatchers.IO) 是注释", "import kotlinx.coroutines.flow.flowOn")
        assertEquals(
            "自检失败：注释/import 行不应计数",
            0,
            countMatching(negative, """\.flowOn\(Dispatchers\.IO\)""")
        )
    }

    /** themeConfig 链必须以 flowOn(IO) 收尾（map 的回填随上游整体移到 IO）。 */
    @Test
    fun themeConfigBackfillRunsOnIo() {
        assertTrue(
            "themeConfig 链缺 `.flowOn(Dispatchers.IO)` —— map{} 的镜像回填会随收集方" +
                "（MainActivity/VideoDetailActivity 的 LaunchedEffect）跑回主线程，" +
                "首次 getSharedPreferences 阻塞读盘（AP-05 病灶回潮）。",
            countMatching(appSettingsLines, """\.flowOn\(Dispatchers\.IO\)""") >= 1
        )
    }

    /** 写路径的镜像快照也必须离开调用方线程（viewModelScope = Main.immediate）。 */
    @Test
    fun writePathMirrorPersistRunsOnIo() {
        val body = blockBody(appSettingsLines, "private suspend fun editSettingsMirrored(")
        assertTrue(
            "editSettingsMirrored 必须用 withContext(Dispatchers.IO) 包住 persistSettingsMirror —— " +
                "升级后首启/清数据后的第一次写入仍可能触发真实读盘。",
            body.any { it.contains("withContext(Dispatchers.IO)") && it.contains("persistSettingsMirror") }
        )
    }

    // ── 快照判等纯函数的状态测 ────────────────────────────────────────────────

    private val next = mapOf("theme_mode" to "DARK", "seed_color" to 0xFF0000)

    /** 镜像缺失（升级首启/清数据）→ 必须判"不一致"，触发回填（P4 的教训）。 */
    @Test
    fun missingMirrorIsStale() {
        assertTrue(
            "空镜像必须判 stale（否则'从不改设置'的用户永远没有镜像，快路径形同虚设）",
            !mirrorSnapshotUpToDate(emptyMap(), next)
        )
        assertTrue(
            "没有 ready 门闩的镜像也必须判 stale",
            !mirrorSnapshotUpToDate(mapOf("theme_mode" to "DARK", "seed_color" to 0xFF0000), next)
        )
    }

    /** 内容一致（恰好多一个门闩键）→ 跳过写盘。 */
    @Test
    fun consistentMirrorSkipsWrite() {
        val current = next + (MIRROR_READY_KEY_NAME to true)
        assertTrue(mirrorSnapshotUpToDate(current, next))
    }

    /** 多余键 / 缺键 / 值不同 → 必须判 stale（漏更新 = 下次冷启动旧主题首帧）。 */
    @Test
    fun driftedMirrorIsStale() {
        val ready = MIRROR_READY_KEY_NAME to true
        val extraKey = (next + ("stale_key" to 1) + ready)
        val missingKey = (mapOf("theme_mode" to "DARK") + ready)
        val wrongValue = (mapOf("theme_mode" to "LIGHT", "seed_color" to 0xFF0000) + ready)
        assertTrue("多余键必须判 stale", !mirrorSnapshotUpToDate(extraKey, next))
        assertTrue("缺键必须判 stale", !mirrorSnapshotUpToDate(missingKey, next))
        assertTrue("值漂移必须判 stale", !mirrorSnapshotUpToDate(wrongValue, next))
    }

    private companion object {
        // AppSettings 里是 private const；这里镜像字面值并钉住关系（改键名会红在状态测）。
        const val MIRROR_READY_KEY_NAME = "__mirror_ready__"
    }
}
