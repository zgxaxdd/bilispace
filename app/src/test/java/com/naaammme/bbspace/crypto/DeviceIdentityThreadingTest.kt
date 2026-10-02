package com.naaammme.bbspace.crypto

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DeviceIdentity 线程纪律的结构守卫（AP-06 ②）。
 *
 * ## 病灶回顾
 *
 * DeviceIdentity 在 `Application.onCreate` 的 Hilt 注入链上被构造
 * （`imageOkHttpClient` ← ImageClientModule、`AppInitializer` ← ticket/buvid/guest
 * 三件套都传递引用它）。原实现在**构造（主线程、首帧之前）**里同步做了：
 * 3 次 `prefs.getString`（首次 = `awaitLoadedLocked()` 读盘 + 解析 XML）+
 * `Settings.Secure.getString` 的 **binder IPC** + 可能的 MediaDrm / NetworkInterface
 * 探测，且无任何超时兜底 —— 它不是 `runBlocking`，所以帧预算棘轮盯不到（AP-06 要点）。
 *
 * 修法：重字段全部 `by lazy` —— 构造变零 IO，取值顺序与落盘内容不变，
 * 重活推迟到首次访问（首个请求线程，一律后台）。本守卫钉住这个形态不被回退。
 *
 * ## W21 正反例内建；W22 单模块 `--rerun` 验证
 */
class DeviceIdentityThreadingTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val deviceIdentityLines: List<String> by lazy {
        val file = File(
            repoRoot,
            "infra/crypto/src/main/java/com/naaammme/bbspace/infra/crypto/DeviceIdentity.kt"
        )
        assertTrue("找不到 DeviceIdentity.kt（repoRoot=${repoRoot.absolutePath}）", file.isFile)
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

    /** W21 自检：正例抓得到新形态；反例（旧 eager 形态）不得被放行。 */
    @Test
    fun scannerSelfCheck() {
        val positives = listOf(
            "    private val prefs: SharedPreferences by lazy {",
            "    val mac: String by lazy {"
        )
        positives.forEach { line ->
            assertTrue(
                "自检失败：by-lazy 形态断言抓不到「$line」",
                countMatching(listOf(line), """by lazy""") == 1
            )
        }
        val negative = listOf(
            "    private val prefs: SharedPreferences =",
            "        appContext.getSharedPreferences(\"device_identity\", Context.MODE_PRIVATE)"
        )
        assertTrue(
            "自检失败：旧 eager 形态不该命中 by-lazy 断言",
            countMatching(negative, """private val prefs: SharedPreferences by lazy""") == 0
        )
    }

    /** SP 文件句柄（首访问 = 读盘 + 解析 XML）必须懒加载，不许在构造期打开。 */
    @Test
    fun prefsHandleIsLazy() {
        assertTrue(
            "DeviceIdentity.prefs 必须 `by lazy` —— 构造发生在 Application.onCreate 注入链上，" +
                "eager getSharedPreferences 会把首次读盘+解析 XML 拉回首帧之前（AP-06 病灶）。",
            countMatching(deviceIdentityLines, """private val prefs: SharedPreferences by lazy""") == 1
        )
    }

    /** binder IPC（Settings.Secure）必须包在 lazy 里，不许在构造线程发生。 */
    @Test
    fun androidIdBinderIpcIsLazy() {
        val declAt = deviceIdentityLines.indexOfFirst {
            it.trimStart().startsWith("private val androidIdValue: String by lazy {")
        }
        assertTrue(
            "androidId 的 Settings.Secure（binder IPC）必须包进 `by lazy`（AP-06：首帧前不许有 IPC）。",
            declAt >= 0
        )
        // IPC 调用必须落在这个 lazy 块体内（花括号配平到块尾）
        var depth = 0
        var opened = false
        var ipcInside = false
        for (i in declAt until deviceIdentityLines.size) {
            val line = deviceIdentityLines[i]
            if (line.contains("Settings.Secure.getString")) ipcInside = true
            for (ch in line) {
                when (ch) {
                    '{' -> {
                        depth++; opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        assertTrue("Settings.Secure.getString 必须在 androidIdValue 的 lazy 块体内", ipcInside)
    }

    /** mac / localBuvid / fp 三个重字段（SP 读 + MediaDrm/NetworkInterface 探测）必须懒加载。 */
    @Test
    fun identityFieldsAreLazy() {
        for ((field, why) in listOf(
            "mac" to "首启会触发 NetworkInterface 探测",
            "localBuvid" to "首启会触发 MediaDrm(WIDEVINE) 探测",
            "fp" to "依赖 localBuvid 与 SP 读"
        )) {
            assertTrue(
                "DeviceIdentity.$field 必须 `by lazy`（$why；构造期执行会把探测拉到主线程首帧之前）。",
                countMatching(deviceIdentityLines, """val $field: String by lazy""") == 1
            )
        }
    }

    /** 构造期的 init 块不许回来（所有重活都已下沉到 lazy 字段）。 */
    @Test
    fun noEagerInitBlock() {
        val inits = countMatching(deviceIdentityLines, """^\s*init\s*\{""")
        assertTrue(
            "DeviceIdentity 不得再有 init 块（AP-06：构造必须零 IO；新初始化逻辑请放进" +
                "对应字段的 lazy 体，让成本落在首次访问的线程上）。当前 init 数量：$inits",
            inits == 0
        )
    }
}
