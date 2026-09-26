package com.naaammme.bbspace.hygiene

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「包声明必须与所在目录同源」守卫（BACKLOG ST-24）。
 *
 * ## 为什么需要它
 * Kotlin **不要求**目录等于包名：把 `package com.naaammme.bbspace.infra.player` 的文件
 * 放进 `com/naammmee/...` 也照样编译、测试照样全绿 —— 本会话就连续犯了两次
 * （本仓包名 `com.naaammme` 极易抄错成 `naammmee`），最后靠人眼才发现。
 * 这类错误只污染"按包名定位源码"的工具与人的直觉，**编译期和测试期都不会报**。
 *
 * ## 规则为什么是"前缀"而不是"相等"
 * `core/model` 有 35 个文件走的是刻意约定：目录按业务分层（`model/video/Video.kt`），
 * 包名却是扁平的 `com.naaammme.bbspace.core.model`。要求相等会让守卫第一天就红，
 * 然后被加一堆豁免 —— 那正是把守卫写成噪音的起点。
 * 前缀规则足以拦住真正的错法：**包名的每一段必须按同样顺序出现在路径里**。
 *
 * ## 防假绿
 * 1. 断言扫描文件数 > 300（实测 437）且含已知样本；
 * 2. 单独一条断言"每个文件都读到了 package 声明" —— 否则前缀规则对读不到声明的文件
 *    是**永久成立**的，而那正是最需要报警的情况（本仓曾有 4 个文件以 UTF-8 BOM 开头，
 *    `^package` 会静默失配；B18 已剥掉并加了 `mainSourcesDoNotStartWithABom` 钉住，见 ST-25）。
 */
class PackageDirectoryConsistencyTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    /** 所有模块 `src/<variant>/java/` 下的 .kt（AGP 源码根约定）。 */
    private val sourceFiles: List<File> by lazy {
        repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .filter { JAVA_ROOT.containsMatchIn(it.path.replace('\\', '/')) }
            .toList()
    }

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${sourceFiles.size} 个 `src/*/java/` 下的 .kt" +
                "（repoRoot=${repoRoot.absolutePath}），少于 300 个说明路径推导失效。",
            sourceFiles.size > 300
        )
        assertTrue(
            "扫描结果必须含 MainActivity.kt，否则后面的断言全是空转。",
            sourceFiles.any { it.name == "MainActivity.kt" }
        )
    }

    /** 每个文件都必须能读到 package 声明（读不到 = 本守卫对它失效）。 */
    @Test
    fun everyScannedFileExposesAPackageDeclaration() {
        val missing = sourceFiles.filter { packageOf(it) == null }
            .map { it.relativeTo(repoRoot).path.replace('\\', '/') }
        assertTrue(
            "这些文件读不到 package 声明，前缀规则对它们等于没跑（常见原因：文件以 UTF-8 BOM 开头）：\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    /** 包声明必须是所在目录的前缀（按段比较）。 */
    @Test
    fun declaredPackageMatchesDirectory() {
        val offenders = sourceFiles.mapNotNull { file ->
            val declared = packageOf(file) ?: return@mapNotNull null
            val directory = directoryOf(file)
            val ok = directory == declared || directory.startsWith("$declared.")
            if (ok) {
                null
            } else {
                "${file.relativeTo(repoRoot).path.replace('\\', '/')}  package=$declared  dir=$directory"
            }
        }
        assertTrue(
            "有文件的 package 声明与目录对不上（Kotlin 不报错、测试也全绿，但按包名找文件会找不到）：\n" +
                offenders.joinToString("\n") +
                "\n修法：把文件移到与包名一致的目录下（包名本身通常才是对的）。" +
                "core/model 的\"扁平包 + 分层目录\"是既有约定，前缀规则已容忍。",
            offenders.isEmpty()
        )
    }

    /**
     * `src/main` 源码不许以 UTF-8 BOM 开头（BACKLOG ST-25）。
     *
     * BOM 不影响 Kotlin 编译，但会让任何**锚定行首**的守卫静默失配 ——
     * `^package` 匹配不到，于是那条文件在结构守卫眼里等于"没有声明"，
     * 前缀规则对它永久成立。本仓曾有 4 个这样的文件（`OfflineDanmakuSession`、
     * `ListenDetailViewModel`、`LiveDetailPane`、`SpaceViewModel`）。
     *
     * 只查 `src/main`：`src/test` 的 BOM 同样不该有，但先守住真正被守卫扫描的那一侧，
     * 避免一次改动牵动过多文件。
     */
    @Test
    fun mainSourcesDoNotStartWithABom() {
        val mainFiles = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
        // 防这条自己空转：扫不到文件时下面那条断言是对空集合成立的
        assertTrue(
            "BOM 扫描只看到 ${mainFiles.size} 个 main 源码（预期 >300），说明路径推导失效",
            mainFiles.size > 300
        )
        val withBom = mainFiles.filter { startsWithBom(it) }
            .map { it.relativeTo(repoRoot).path.replace('\\', '/') }
        assertTrue(
            "这些 main 源码以 UTF-8 BOM 开头，任何锚定行首的守卫都会静默漏掉它们：\n" +
                withBom.joinToString("\n") +
                "\n修法：剥掉首 3 个字节（EF BB BF），**不要**整文件重写以免动到行尾与中文。",
            withBom.isEmpty()
        )
    }

    /** 只看首 3 个字节，不把整个文件读成字符串（BOM 会被解码进首字符，容易看漏）。 */
    private fun startsWithBom(file: File): Boolean {
        val head = ByteArray(3)
        file.inputStream().use { input ->
            val read = input.read(head)
            return read == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        }
    }

    /** `src/<variant>/java/` 之后的目录，点分形式。 */
    private fun directoryOf(file: File): String =
        file.path.replace('\\', '/')
            .substringAfterLast("/java/")
            .substringBeforeLast('/')
            .replace('/', '.')

    /**
     * 取首个 package 行；**容忍 UTF-8 BOM**（EF BB BF），否则那 4 个带 BOM 的文件
     * 会让 `^package` 静默失配 —— 结构守卫最典型的假绿来源。
     */
    private fun packageOf(file: File): String? = file.readLines()
        .firstOrNull { it.startsWith(PACKAGE_PREFIX) || it.startsWith(BOM + PACKAGE_PREFIX) }
        ?.removePrefix(BOM)
        ?.removePrefix(PACKAGE_PREFIX)
        ?.trimEnd(';')
        ?.trim()

    private companion object {
        /** UTF-8 BOM 在 `readLines()` 里会作为首字符留着，必须显式吃掉。 */
        const val BOM = "﻿"
        const val PACKAGE_PREFIX = "package "
        val JAVA_ROOT = Regex("""/src/[A-Za-z0-9]+/java/""")
    }
}
