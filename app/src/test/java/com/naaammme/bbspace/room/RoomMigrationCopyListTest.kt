package com.naaammme.bbspace.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 手写 Migration 的目标结构必须与 Room 导出的 schema 快照**逐字一致**（BACKLOG ST-02）。
 *
 * ## 为什么单独钉这一条
 * Room 跑完迁移后只把新的 identityHash 写进 `room_master_table`，**不会**拿快照去校验建出来的表。
 * 所以手写迁移少一列、错一个 NOT NULL，打开库时不报错，而是在第一次查询/写入时才炸，
 * 或者更糟 —— 静默把该列写成 NULL。本守卫是从 Kotlin 源码里把 SQL 字面量取出来比的，
 * 因此它测的就是真正会执行的那段文本，不是我另抄一份。
 *
 * ## 还钉了链式兼容性
 * 前一步写进去的列必须覆盖后一步读出来的列，否则停在中间版本的安装升上来会拿到 NULL 或直接抛错。
 */
class RoomMigrationCopyListTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val historyMainDir = File(
        repoRoot,
        "core/history/src/main/java/com/naaammme/bbspace/core/history"
    )

    private val migrationText: String by lazy {
        val file = File(historyMainDir, "PlaybackHistoryMigrations.kt")
        assertTrue("找不到 ${file.absolutePath} —— 迁移改名了，本守卫会空转。", file.isFile)
        file.readText().replace("\r\n", "\n")
    }

    private val declaredVersion: Int by lazy {
        val raw = VERSION_IN_DB.find(File(historyMainDir, "PlaybackHistoryDb.kt").readText())
        assertTrue("PlaybackHistoryDb.kt 里解析不到 version。", raw != null)
        raw!!.value.filter(Char::isDigit).toInt()
    }

    private data class Step(
        val from: Int,
        val to: Int,
        val statements: List<String>
    )

    /** 从源码里切出每个 `object : Migration(from, to)` 及其 execSQL 语句。 */
    private val steps: List<Step> by lazy {
        MIGRATION_HEADER.findAll(migrationText).map { match ->
            val bodyStart = migrationText.indexOf("override fun migrate", match.range.last)
            val bodyEnd = migrationText.indexOf("\n    }", bodyStart)
            Step(
                from = match.groupValues[1].toInt(),
                to = match.groupValues[2].toInt(),
                statements = execSqlOf(migrationText.substring(bodyStart, bodyEnd))
            )
        }.toList()
    }

    /** 链上以当前声明版本收尾的那一步，就是「当前表结构」的生产者。 */
    private val finalStep: Step by lazy {
        val step = steps.firstOrNull { it.to == declaredVersion }
        assertTrue(
            "PlaybackHistoryDb 声明 version=$declaredVersion，但迁移链里没有以它结尾的一步" +
                "（实际链=${steps.map { "${it.from}->${it.to}" }.sorted().joinToString()}）。",
            step != null
        )
        step!!
    }

    private val schemaJson: String by lazy {
        val file = File(
            repoRoot,
            "core/history/schemas/com.naaammme.bbspace.core.history.PlaybackHistoryDb/$declaredVersion.json"
        )
        assertTrue("找不到 schema 快照 ${file.absolutePath}（先跑 kspDebugKotlin 并提交）。", file.isFile)
        file.readText().replace("\r\n", "\n")
    }

    private val jsonColumns: List<String> by lazy {
        COLUMN_NAME.findAll(schemaJson).map { it.groupValues[1] }.toList()
    }

    private val finalTableName: String by lazy {
        TABLE_NAME_IN_JSON.find(schemaJson)?.groupValues?.get(1) ?: "playback_history"
    }

    @Test
    fun scannerActuallyReadsTheMigrationSource() {
        assertTrue("从迁移源码里只解析出 ${steps.size} 步（预期 ≥2）。", steps.size >= 2)
        assertTrue("schema 快照里只解析出 ${jsonColumns.size} 列（预期 >5）。", jsonColumns.size > 5)
        steps.forEach { step ->
            assertTrue(
                "Migration(${step.from}, ${step.to}) 解析出 0 条 execSQL —— 解析器失配会让本守卫永真。",
                step.statements.isNotEmpty()
            )
        }
    }

    /** 目标表的 CREATE 必须与快照的 createSql 完全一致（列序、类型、NOT NULL、主键）。 */
    @Test
    fun finalStepCreatesExactlyTheExportedTable() {
        val expected = ENTITY_CREATE.find(schemaJson)?.groupValues?.get(1)
        assertTrue("快照里解析不到表的 createSql。", expected != null)
        val actual = finalStep.statements.firstOrNull { it.startsWith("CREATE TABLE") }
        assertTrue("Migration(${finalStep.from}, ${finalStep.to}) 里没有 CREATE TABLE。", actual != null)

        val created = createdTableName(actual!!)
        // 迁移按惯例先建「正式名_new」再改名，比较时把两边的表名归一到同一个占位符。
        assertEquals(
            "手写 CREATE 与 schema 快照不一致：",
            normalize(expected!!.replace("\${TABLE_NAME}", "@T@")),
            normalize(actual.replace("`$created`", "`@T@`"))
        )

        val renamed = finalStep.statements.firstOrNull { it.contains("RENAME TO") }
        assertTrue("缺少把新表改回正式名的 RENAME TO。", renamed != null)
        assertTrue(
            "RENAME 的目标应是快照里的 $finalTableName，实际=$renamed",
            renamed!!.trimEnd().endsWith("`$finalTableName`")
        )
    }

    /** 拷贝列表 = 快照列表且同序；SELECT 若全是裸列名也要一致。 */
    @Test
    fun finalStepCopiesEveryExportedColumn() {
        val insert = finalStep.insertStatement()
        assertEquals(
            "Migration(${finalStep.from}, ${finalStep.to}) 的拷贝列表与 schema 快照不一致：",
            jsonColumns,
            insertColumns(insert)
        )
        val selected = selectColumns(insert)
        if (selected.all { BARE_COLUMN.matches(it) }) {
            assertEquals("SELECT 出来的列必须与写入列同名同序：", jsonColumns, selected.map { bareName(it) })
        } else {
            assertEquals(
                "SELECT 里含表达式（重算主键之类），列数必须仍与写入列一致：",
                jsonColumns.size,
                selected.size
            )
        }
    }

    /** 快照声明的每个索引都必须被这一步重建，且定义逐字一致。 */
    @Test
    fun finalStepRebuildsEveryExportedIndex() {
        val entries = INDEX_ENTRY.findAll(schemaJson).toList()
        assertTrue("快照里解析不到索引条目（该库确有索引，解析失配会让本条永真）。", entries.isNotEmpty())
        for (match in entries) {
            val name = match.groupValues[1]
            val expected = normalize(match.groupValues[2].replace("\${TABLE_NAME}", finalTableName))
            val actual = finalStep.statements.firstOrNull { it.contains(name) }
            assertTrue("索引 $name 没有在 Migration(${finalStep.from}, ${finalStep.to}) 里重建。", actual != null)
            assertEquals("索引 $name 的定义与快照不一致：", expected, normalize(actual!!))
        }
    }

    /** 前一步写入的列必须覆盖后一步读出的列。 */
    @Test
    fun eachStepWritesWhatTheNextStepReads() {
        val ordered = steps.sortedBy { it.from }
        for (i in 0 until ordered.size - 1) {
            val earlier = ordered[i]
            val later = ordered[i + 1]
            if (earlier.to != later.from) continue
            val written = earlier.statements.filter { it.startsWith("INSERT") }.flatMap { insertColumns(it) }.toSet()
            val read = later.statements.filter { it.startsWith("INSERT") }.flatMap { selectColumns(it) }
                .filter { BARE_COLUMN.matches(it) }
                .map { bareName(it) }
                .toSet()
            val missing = read - written
            assertTrue(
                "Migration(${earlier.from}, ${earlier.to}) 写入的列不覆盖 Migration(${later.from}, ${later.to}) " +
                    "读出的 $missing —— 停在 ${earlier.to} 的安装升上来会拿到 NULL 或直接抛错。",
                missing.isEmpty()
            )
        }
    }

    private fun Step.insertStatement(): String {
        val insert = statements.firstOrNull { it.startsWith("INSERT") }
        assertTrue("Migration($from, $to) 里没有 INSERT ... SELECT 拷贝语句。", insert != null)
        return insert!!
    }

    private fun bareName(token: String): String = token.trim().trim('`')

    private fun createdTableName(create: String): String =
        CREATE_TARGET.find(create)?.groupValues?.get(1) ?: error("CREATE 语句里解析不到表名：$create")

    /** 取 `INTO 表 ( a, b )` 里那一串列名。 */
    private fun insertColumns(insert: String): List<String> {
        val open = insert.indexOf('(', insert.indexOf("INTO"))
        assertTrue("拷贝语句里解析不到列清单：$insert", open > 0)
        val close = matchingParen(insert, open)
        return splitTopLevel(insert.substring(open + 1, close - 1)).map { bareName(it) }
    }

    /** 取 `SELECT ... FROM` 之间的列表（顶层逗号切分，表达式不会被切碎）。 */
    private fun selectColumns(insert: String): List<String> {
        val select = insert.indexOf("SELECT")
        val from = insert.lastIndexOf("FROM")
        assertTrue("拷贝语句里没有 SELECT ... FROM：$insert", select > 0 && from > select)
        return splitTopLevel(insert.substring(select + "SELECT".length, from)).map { it.trim() }
    }

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (ch in text) {
            if (quote != null) {
                cur.append(ch)
                if (ch == quote) quote = null
                continue
            }
            when (ch) {
                '"', '\'' -> { quote = ch; cur.append(ch) }
                '(' -> { depth++; cur.append(ch) }
                ')' -> { depth--; cur.append(ch) }
                ',' -> if (depth == 0) {
                    parts += cur.toString()
                    cur.setLength(0)
                } else {
                    cur.append(ch)
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) parts += cur.toString()
        return parts.filter { it.isNotBlank() }
    }

    /** 跳过字符串字面量后的括号配对，返回右括号下标 + 1。 */
    private fun matchingParen(text: String, openIndex: Int): Int {
        var i = openIndex
        var depth = 0
        var quote: Char? = null
        while (i < text.length) {
            val ch = text[i]
            if (quote != null) {
                if (ch == quote) quote = null
            } else {
                when (ch) {
                    '"', '\'' -> quote = ch
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        error("括号不配对：${text.take(120)}")
    }

    /** 把 `db.execSQL("…" + "…")` 里的 Kotlin 字符串字面量还原成一条 SQL。 */
    private fun execSqlOf(body: String): List<String> {
        val out = mutableListOf<String>()
        var idx = 0
        while (true) {
            val at = body.indexOf("db.execSQL(", idx)
            if (at < 0) break
            val open = at + "db.execSQL(".length - 1
            val after = matchingParen(body, open)
            val literal = LITERAL.findAll(body.substring(open + 1, after - 1)).joinToString("") { it.groupValues[1] }
            if (literal.isNotBlank()) out += normalize(literal)
            idx = after
        }
        return out
    }

    private fun normalize(sql: String): String = sql.trim().replace(WHITESPACE, " ")

    private companion object {
        val MIGRATION_HEADER = Regex("""Migration\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
        val VERSION_IN_DB = Regex("""version\s*=\s*\d+""")

        /** 表级 createSql：JSON 里第一个出现的 createSql 就是实体级（表）的，索引的在后面。 */
        val ENTITY_CREATE = Regex(""""createSql":\s*"([^"]+)"""")
        val TABLE_NAME_IN_JSON = Regex(""""tableName":\s*"([^"]+)"""")
        val COLUMN_NAME = Regex(""""columnName":\s*"([^"]+)"""")
        val INDEX_ENTRY = Regex(""""name":\s*"(index_[^"]+)"[\s\S]*?"createSql":\s*"([^"]+)"""")
        val CREATE_TARGET = Regex("""CREATE TABLE(?: IF NOT EXISTS)?\s+`?([A-Za-z0-9_]+)`?""")
        val BARE_COLUMN = Regex("""^\s*`?\w+`?\s*$""")
        val LITERAL = Regex(""""((?:[^"\\]|\\.)*)"""")
        val WHITESPACE = Regex("""\s+""")
    }
}
