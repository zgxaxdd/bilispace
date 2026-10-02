package com.naaammme.bbspace.memory

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "自建缓存必须登记进 trim 分级"的结构守卫（AP-04 ②）。
 *
 * ## 背景
 *
 * `applyTrimPlan`（BiliApplication）此前只认识 Coil / 封面色 / 无缝位图三个收口；
 * 进程级自建缓存 `VideoDetailRepository`（≤12 条整份详情）、`PlayUrlCache`
 * （≤16 条播放地址，`clear()` 全仓零调用）、`BiliDns.cache` 全部漏登记 ——
 * "内存收不回"的那类缺陷。本轮已补登记，本守卫钉住登记**本身**。
 *
 * ## W20 纪律：锚在调用上，不锚在名字/文件上
 *
 * 断言匹配的是 `applyTrimPlan` 函数体里的**清空调用本身**（`clearDetailCache()` /
 * `clearPlayUrlCache()` / `clearCache()`）与分级表里的档位判断
 * （`NetCacheAction.Clear`），不是文件级禁令（W18）。
 *
 * 局限性（诚实声明）：静态扫描无法证明"未来新增的缓存类字段一定被登记" ——
 * 那需要语义分析。本守卫钉住的是**已登记的三项不被悄悄拔线**；新增缓存的登记
 * 纪律靠本类 KDoc 与 review。破坏性验证见交付报告：拔掉任一调用 → FAILED。
 */
class MemoryTrimRegistrationTest {

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
            ?: throw AssertionError("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。")
        return file.readLines()
    }

    /** 取以 [signaturePrefix] 开头的函数体（含签名），按花括号配平定界。 */
    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        require(!signaturePrefix.startsWith(" ") && !signaturePrefix.startsWith("\t")) {
            "signaturePrefix 带了前导缩进（\"$signaturePrefix\"）：本 helper 对行做 trimStart() 比较，" +
                "缩进会导致永不匹配（W23）。请传去掉缩进的签名。"
        }
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
                        depth++; opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        return body
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

    /**
     * 块体内"必须调用 X"的判定**必须跳过注释行** —— 被注释掉的调用等于没有调用
     * （W7：覆盖面 = 会红的写法集合；破坏性验证实测：注释式拔线曾骗过 contains 判定）。
     */
    private fun codeLines(body: List<String>): List<String> = body.filter { line ->
        val t = line.trimStart()
        !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
    }

    /**
     * `applyTrimPlan` 必须真的执行三个补登记缓存的清空 —— 拔掉任何一条即红。
     * `Nothing -> Unit` 形态同时钉死（P5 的坑：`-> return` 会短路后续分支）。
     */
    @Test
    fun applyTrimPlanClearsAllRegisteredNetCaches() {
        val body = codeLines(
            blockBody(
                sourceOf("app/src/main/java/com/naaammme/bbspace/BiliApplication.kt"),
                "private fun applyTrimPlan("
            )
        )
        assertTrue(
            "applyTrimPlan 必须调用 clearDetailCache()（VideoDetailRepository 的整份详情缓存，" +
                "AP-04 补登记；拔线 = 内存收不回）。",
            body.any { it.contains("clearDetailCache()") }
        )
        assertTrue(
            "applyTrimPlan 必须调用 clearPlayUrlCache()（PlayUrlCache 的播放地址缓存，" +
                "AP-04 补登记，也是 PlayUrlCache.clear() 全仓唯一真实调用点）。",
            body.any { it.contains("clearPlayUrlCache()") }
        )
        assertTrue(
            "applyTrimPlan 必须调用 biliDns.clearCache()（DNS 缓存，AP-04 补登记）。",
            body.any { it.contains("biliDns.get().clearCache()") }
        )
        assertTrue(
            "Nothing/Keep 分支必须是 `-> Unit`，不许写 `-> return` —— 后者会短路掉" +
                "排在其后的所有分支（FINDINGS P5 的坑：加新分支看起来生效了，实际永远走不到）。",
            body.none { it.contains("Nothing -> return") || it.contains("Keep -> return") }
        )
    }

    /** 分级表必须包含 NetCacheAction 档位判断（策略层漏掉 = 执行层永远 Keep）。 */
    @Test
    fun trimPolicyCoversNetCaches() {
        val lines = sourceOf("app/src/main/java/com/naaammme/bbspace/memory/MemoryTrimPolicy.kt")
        assertTrue(
            "MemoryTrimPolicy 必须声明 NetCacheAction（AP-04 的补登记枚举不见了？）",
            countMatching(lines, """enum class NetCacheAction""") == 1
        )
        val planBody = blockBody(lines, "fun planTrimMemory(")
        assertTrue(
            "planTrimMemory 必须给 BACKGROUND 档显式设 NetCacheAction.Clear",
            planBody.any { it.trimStart().startsWith("netCaches = NetCacheAction.Clear") }
        )
        assertTrue(
            "planTrimMemory 必须给温和档显式设 NetCacheAction.Keep",
            planBody.any { it.trimStart().startsWith("netCaches = NetCacheAction.Keep") }
        )
    }

    /** dagger.Lazy 注入形态：防止有人把 Lazy 改回直接注入（把重依赖拉回 onCreate 主线程）。 */
    @Test
    fun netCacheInjectionsStayLazy() {
        val lines = sourceOf("app/src/main/java/com/naaammme/bbspace/BiliApplication.kt")
        for (field in listOf("videoDetailRepository", "videoPlayerRepository", "biliDns")) {
            assertTrue(
                "BiliApplication 的 $field 必须以 dagger.Lazy 注入 —— 直接注入具体类型会把" +
                    "grpc/DeviceIdentity 依赖链提前拉进 onCreate 主线程（AP-06 要消除的冷启开销）。",
                lines.any { it.trimStart().startsWith("lateinit var $field: dagger.Lazy<") }
            )
        }
    }
}
