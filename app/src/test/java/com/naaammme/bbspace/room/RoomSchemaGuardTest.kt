package com.naaammme.bbspace.room

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room 的 schema 必须**可差分**，升级路径必须**逐步有迁移**（BACKLOG ST-01 / ST-02）。
 *
 * ## 病灶
 * `exportSchema = false` 时，改实体这件事在版本库里不留任何痕迹：既写不出 Migration
 * （没有旧 schema 可比），也看不出"这次升版会把用户数据清掉"。本仓真实发生过一次 ——
 * `PlaybackHistoryDb` 在 `215575de` 把 version 2→3 且只挂了 `fallbackToDestructiveMigration`，
 * 用户本地观看历史被静默清空（`git log -L` 查得的结论，见 FINDINGS W6）。
 *
 * ## 每条断言拦的是哪一种下一步
 * 1. exportSchema 不许再关回去；
 * 2. 声明的 version 必须有对应的已提交 schema 快照（改了实体不重新导出 = 红）；
 * 3. 全仓不许出现 `fallbackToDestructiveMigration`：它把「开发期崩溃」降级成「用户静默丢数据」，
 *    而崩溃是这条链上唯一可接受的失败方式；
 * 4. version 高于**出生版本**的库必须逐级给出 Migration；新出现的库必须先在本文件登记出生值
 *    （登记时要附 `git log -L` 依据，凭记忆填 = 下次又要重查）。
 */
class RoomSchemaGuardTest {

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

    private data class Decl(
        val className: String,
        val qualifiedName: String,
        val version: Int,
        val exportSchema: Boolean?,
        val moduleDir: File,
        val relPath: String
    )

    private val declarations: List<Decl> by lazy {
        mainSources.mapNotNull { file ->
            val text = file.readText().replace("\r\n", "\n")
            val start = text.indexOf("@Database(")
            if (start < 0) return@mapNotNull null
            val blockEnd = matchingParenEnd(text, start, "@Database(".length)
            val block = text.substring(start, blockEnd)
            val version = VERSION_IN_BLOCK.find(block)?.value?.filter(Char::isDigit)?.toIntOrNull()
                ?: return@mapNotNull null
            val className = CLASS_AFTER_BLOCK.find(text.substring(blockEnd))?.groupValues?.get(1)
                ?: file.nameWithoutExtension
            val pkg = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()
            Decl(
                className = className,
                qualifiedName = if (pkg.isEmpty()) className else "$pkg.$className",
                version = version,
                exportSchema = EXPORT_SCHEMA.find(block)?.groupValues?.get(1)?.toBooleanStrictOrNull(),
                moduleDir = moduleDirOf(file),
                relPath = file.relativeTo(repoRoot).path.replace('\\', '/')
            )
        }
    }

    @Test
    fun scannerActuallyReadsDatabaseDeclarations() {
        // W9：先断言扫到了，否则后面每一条都是对空集合成立。
        assertTrue(
            "只找到 ${declarations.size} 个 @Database 声明（repoRoot=${repoRoot.absolutePath}），" +
                "少于 5 个说明扫描或解析失效，本守卫的其余断言会全部空转。",
            declarations.size >= 5
        )
        val found = declarations.map { it.className }.toSet()
        REQUIRED_DATABASES.forEach { need ->
            assertTrue("$need 必须被扫到（实际=$found），否则它逃过了本守卫。", need in found)
        }
    }

    @Test
    fun everyDatabaseExportsItsSchema() {
        val offenders = declarations.filter { it.exportSchema != true }
            .map { "${it.relPath} -> exportSchema=${it.exportSchema}" }
        assertTrue(
            "所有 @Database 必须 exportSchema = true：不导出快照就无法差分 schema，" +
                "下次改实体既写不出 Migration、也看不出会丢数据。\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun declaredVersionHasACommittedSchemaSnapshot() {
        val missing = declarations.filter { !it.snapshotFile().isFile }.map {
            "${it.relPath} 声明 version=${it.version}，但缺少 ${it.snapshotPath()}"
        }
        assertTrue(
            "每个库当前 version 的 schema JSON 必须提交进仓库（它是唯一的差分依据）。" +
                "改完实体重跑一次 kspDebugKotlin 再 git add：\n" + missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    /** 快照里的 version 要与代码声明一致（防手改 JSON、或提交了别的版本）。 */
    @Test
    fun schemaSnapshotAgreesWithTheDeclaration() {
        val mismatched = declarations.mapNotNull { decl ->
            val file = decl.snapshotFile()
            if (!file.isFile) return@mapNotNull null
            val inside = VERSION_IN_JSON.find(file.readText())?.value?.filter(Char::isDigit)?.toIntOrNull()
            if (inside != decl.version) {
                "${decl.relPath}: 代码 version=${decl.version}，快照 ${file.name} 内 version=$inside"
            } else {
                null
            }
        }
        assertTrue("schema 快照与声明不一致：\n" + mismatched.joinToString("\n"), mismatched.isEmpty())
    }

    @Test
    fun noDestructiveFallbackAnywhere() {
        val offenders = mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    return@mapIndexedNotNull null
                }
                if (!DESTRUCTIVE.containsMatchIn(line)) return@mapIndexedNotNull null
                "${file.relativeTo(repoRoot).path.replace('\\', '/')}:${index + 1} -> ${line.trim()}"
            }
        }
        assertTrue(
            "禁止 fallbackToDestructiveMigration：它把「忘写迁移」从开发期崩溃降级成用户数据被静默清空。" +
                "确实需要一次性清库时，写一条显式 Migration 并说明理由：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** 出生版本之上每一步都要有 Migration；新库必须先登记出生值。 */
    @Test
    fun everyUpgradeStepHasAMigration() {
        val problems = mutableListOf<String>()
        for (decl in declarations) {
            val birth = BIRTH_VERSIONS[decl.className]
            if (birth == null) {
                problems += "${decl.relPath}: 新出现的 ${decl.className} 没有登记出生版本 —— 用 " +
                    "git log -L '/version =/,+1:<该文件>' 查清首次引入的 version 后加进 BIRTH_VERSIONS"
                continue
            }
            if (decl.version < birth) {
                problems += "${decl.className} 的 version=${decl.version} 低于登记的出生版本 $birth" +
                    "（降版本安装会硬崩）"
                continue
            }
            val edges = migrationEdges(decl.moduleDir)
            for (from in birth until decl.version) {
                if (from + 1 !in edges.getOrDefault(from, emptySet())) {
                    problems += "${decl.className}: 缺 Migration($from, ${from + 1})，而该库 version 已到 " +
                        decl.version
                }
            }
        }
        assertTrue("升级路径不完整：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** 登记表不许烂掉：列了但源码里已不存在的库要报出来。 */
    @Test
    fun birthRegistryDoesNotRot() {
        val found = declarations.map { it.className }.toSet()
        val stale = BIRTH_VERSIONS.keys - found
        assertTrue("BIRTH_VERSIONS 里的 ${stale.joinToString()} 已不再是 @Database，请删掉。", stale.isEmpty())
    }

    /** Room 的落盘布局是 `<module>/schemas/<全限定类名>/<version>.json`。 */
    private fun Decl.snapshotFile(): File = File(moduleDir, "schemas/$qualifiedName/$version.json")

    /** 报错里给相对路径即可（模块名 + schemas 段足够定位）。 */
    private fun Decl.snapshotPath(): String = "${moduleDir.name}/schemas/$qualifiedName/$version.json"

    /** 从 `<module>/src/main/java/...` 里的文件退到模块根。 */
    private fun moduleDirOf(file: File): File {
        var cur: File? = file.absoluteFile.parentFile
        while (cur != null && cur.name != "src") cur = cur.parentFile
        return cur?.parentFile ?: file.absoluteFile.parentFile
    }

    /** 收集该模块 src/main 里所有 `Migration(from, to)`，按 from 归组。 */
    private fun migrationEdges(moduleDir: File): Map<Int, Set<Int>> {
        val srcMain = File(moduleDir, "src/main")
        val out = mutableMapOf<Int, MutableSet<Int>>()
        if (!srcMain.isDirectory) return out
        srcMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                MIGRATION_CTOR.findAll(file.readText()).forEach { m ->
                    val from = m.groupValues[1].toInt()
                    val to = m.groupValues[2].toInt()
                    out.getOrPut(from) { mutableSetOf() }.add(to)
                }
            }
        return out
    }

    /** 返回 marker 起始处那个左括号所配对的右括号下标 + 1。 */
    private fun matchingParenEnd(text: String, start: Int, markerLength: Int): Int {
        var i = start + markerLength
        var depth = 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return i
    }

    private companion object {
        val VERSION_IN_BLOCK = Regex("""version\s*=\s*\d+""")
        val VERSION_IN_JSON = Regex(""""version"\s*:\s*\d+""")
        val EXPORT_SCHEMA = Regex("""exportSchema\s*=\s*(true|false)""")
        val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
        val CLASS_AFTER_BLOCK = Regex("""abstract\s+class\s+(\w+)""")
        val DESTRUCTIVE = Regex("""fallbackToDestructive""")
        val MIGRATION_CTOR = Regex("""Migration\(\s*(\d+)\s*,\s*(\d+)\s*\)""")

        /** 缺任何一个说明扫描漏了或类改名了（数量阈值之外还要钉具体名字）。 */
        val REQUIRED_DATABASES = setOf(
            "PlaybackHistoryDb",
            "VideoDownloadDb",
            "SearchHistoryDb",
            "PublishedRecordDb",
            "SpaceNoteDb"
        )

        /**
         * 出生版本 = 该库首次引入时的 version，即「不存在比它更老的安装」。
         * 依据一律用 `git log -L '/version =/,+1:<文件>'` 查，不许凭印象：
         *  - PlaybackHistoryDb 1：`6a24805b` 引入时叫 LocalHistoryDb，出生即 1；2 见 `6e1469c8`，3 见 `215575de`
         *  - VideoDownloadDb 2：`0c316e59` 出生值即 2，所以不需要 1→2
         *  - SpaceNoteDb 2：`bbe7d220` 同上
         *  - SearchHistoryDb 1 / PublishedRecordDb 1：`4d88a66c` 等，出生即 1
         */
        val BIRTH_VERSIONS = mapOf(
            "PlaybackHistoryDb" to 1,
            "VideoDownloadDb" to 2,
            "SpaceNoteDb" to 2,
            "SearchHistoryDb" to 1,
            "PublishedRecordDb" to 1
        )
    }
}
