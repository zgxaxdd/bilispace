package com.naaammme.bbspace.core.im

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * IM 按账号缓存的清理契约（BACKLOG ST-12）。
 *
 * ## 为什么不是"fake Context 跑一遍删文件"
 * 那条路要 fake 出 `android.content.Context` / `SharedPreferences` 整套抽象类，
 * 本仓单测没有 Robolectric，也没有网络可以现装。与其写一个假装成 Context 的巨型桩
 * （它测的是我自己写的桩，不是 Android），不如钉住**真正会坏的地方**：
 * 1. 缓存名的唯一来源（两边各写一份字符串 = 改一次命名，清理静默失效，正是原缺陷的形状）；
 * 2. 账号移除路径确实遍历了清理器；
 * 3. 依赖方向不许反过来（auth 依赖 im 会成环，所以才有 common 里的接口 + 多绑定）。
 * 真机上"删账号后 shared_prefs 里不该再有 IMFieldsCache$mid.xml"另列 L4-20。
 */
class ImAccountCacheCleanupTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun source(rel: String): File = File(repoRoot, rel.replace('/', File.separatorChar))

    /** 去掉注释与 import，避免"注释里提了一句"被当成接线。 */
    private fun codeOf(file: File): String = file.readLines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) "" else line
    }

    @Test
    fun prefsNameIsDerivedFromThePrefix() {
        assertEquals("IMFieldsCache123456", ImFieldsCache.prefsName(123456L))
        assertEquals("IMFieldsCache", ImFieldsCache.PREFS_PREFIX)
        // 不同账号必须是不同文件，否则清一个会连带清掉别人的
        assertTrue(ImFieldsCache.prefsName(1L) != ImFieldsCache.prefsName(2L))
    }

    /**
     * 缓存名的**字面量**只许有一个定义处。
     * 查的是「引号 + 前缀」而不是「引号 + 前缀 + 引号」：硬编码写法通常是
     * `"IMFieldsCache$mid"` 这种带插值的变体，只查完整带闭引号的那一份会漏掉它
     * （本测试自己就被这样漏过一次，所以规则要写成能抓住实际坏写法的样子）。
     */
    @Test
    fun cacheNameHasASingleSourceOfTruth() {
        val literal = "\"${ImFieldsCache.PREFS_PREFIX}"
        val carriers = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .filter { it.readText().contains(literal) }
            .map { it.relativeTo(repoRoot).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "缓存名前缀的字面量 $literal… 只应出现在定义处，实际出现在：$carriers",
            carriers.size == 1 && carriers[0].endsWith("ImFieldsCache.kt")
        )
    }

    /** 读写侧必须用同一个名字来源，不许退回硬编码。 */
    @Test
    fun readerUsesTheSharedName() {
        val rel = "core/im/src/main/java/com/naaammme/bbspace/core/im/ImRepository.kt"
        assertTrue("找不到 $rel", source(rel).isFile)
        val code = codeOf(source(rel))
        assertTrue("ImRepository 必须用 ImFieldsCache.prefsName(mid) 取 prefs 名",
            code.contains("ImFieldsCache.prefsName(mid)"))
    }

    /** 移除账号时确实遍历了清理器。 */
    @Test
    fun removeAccountRunsTheCleaners() {
        val rel = "core/auth/src/main/java/com/naaammme/bbspace/core/auth/AuthStore.kt"
        assertTrue("找不到 $rel", source(rel).isFile)
        val code = codeOf(source(rel))
        val body = code.substring(code.indexOf("fun removeAccount"))
        assertTrue("removeAccount 必须遍历 perAccountCleaners", body.contains("perAccountCleaners.forEach"))
        assertTrue("必须把 mid 传给清理器", body.contains("cleaner.clean(mid)"))
        assertTrue(
            "清理器必须从构造函数注入（集合注入的声明要写在类头上，不能塞默认值）",
            code.contains("Set<@JvmSuppressWildcards PerAccountDataCleaner>")
        )
    }

    /** IM 侧必须真的把自己注册进集合，否则遍历到的是空集合。 */
    @Test
    fun imCleanerIsBoundIntoTheSet() {
        val rel = "core/im/src/main/java/com/naaammme/bbspace/core/im/ImFieldsCache.kt"
        assertTrue("找不到 $rel", source(rel).isFile)
        val code = codeOf(source(rel))
        assertTrue("清理器必须 @IntoSet 绑定", code.contains("@IntoSet"))
        assertTrue("绑定的目标类型必须是 PerAccountDataCleaner",
            code.contains(": PerAccountDataCleaner"))
        assertTrue("实现必须调用 deleteSharedPreferences 而不是只 clear()",
            code.contains("deleteSharedPreferences(ImFieldsCache.prefsName(mid))"))
    }

    /** 依赖方向：auth 不许反过来依赖 im（会成环）。 */
    @Test
    fun authDoesNotDependOnIm() {
        val authBuild = source("core/auth/build.gradle.kts")
        assertTrue("找不到 core/auth/build.gradle.kts", authBuild.isFile)
        val declared = authBuild.readLines().any { it.contains("\":core:im\"") }
        assertFalse("core:auth 依赖 core:im 会形成环，接口必须放在 core:common", declared)
        assertTrue(
            "接口必须待在 core:common（两端都能看见且无环）",
            source("core/common/src/main/java/com/naaammme/bbspace/core/common/account/PerAccountDataCleaner.kt").isFile
        )
    }
}
