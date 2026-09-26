package com.naaammme.bbspace.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 入队的读-改-写必须在同一个事务里，且那条"冲突却查不到行"不许再抛裸异常（ST-13）。
 *
 * ## 危害形状（复核时查清的，不是 BACKLOG 原来的说法）
 * 老代码是 `dao.insertIgnore(...)` 返回 -1 后再 `dao.findExistingId(...) ?: error("下载任务已存在但未找到记录")`。
 * 两条语句各自一个隐式事务，中间那一行可能被并发 `delete` 掉 → 走到 `error(...)`。
 * 而 `DownloadViewModel.enqueue` 的调用点在 `viewModelScope.launch` 里**没有 try/catch**，
 * 所以这个 IllegalStateException 不是"报错文案不好看"，是**一次崩溃**。
 *
 * ## 为什么这组是结构守卫而不是单元测试
 * 事务语义与"协程里抛裸异常"都得在真实的 Room + 主线程调度上才成立，JVM 单测给不了；
 * 能钉死的是三条静态事实：方法是 `@Transaction` 的、那句 `error(...)` 回来了没有、
 * 以及新分支 `Conflict` 在界面上有没有被处理（而不是被 `else -> Unit` 吞掉）。
 */
class DownloadEnqueueRaceGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun codeOf(rel: String): String {
        val file = File(repoRoot, rel.replace('/', File.separatorChar))
        assertTrue("找不到 $rel —— 路径变了本守卫会空转", file.isFile)
        return file.readLines().joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) "" else line
        }
    }

    @Test
    fun insertAndFindIsTransactional() {
        val dao = codeOf(DAO)
        val at = dao.indexOf("suspend fun insertAndFind")
        assertTrue("DAO 里没有 insertAndFind（入队去重路径被改回两次独立调用）", at > 0)
        val head = dao.substring(maxOf(0, at - 200), at)
        assertTrue("insertAndFind 必须带 @Transaction，否则冲突回读仍能插进一次并发删除", head.contains("@Transaction"))
        assertTrue("它必须同时调用 insertIgnore 与 findExistingId", dao.contains("insertIgnore(task)"))
        assertTrue(dao.contains("findExistingId("))
    }

    @Test
    fun conflictWindowIsReturnedNotThrown() {
        val repo = codeOf(REPOSITORY)
        assertFalse(
            "那句裸 error(...) 回来了：调用点在 viewModelScope.launch 里没有 try/catch，会崩",
            repo.contains("下载任务已存在但未找到记录")
        )
        assertTrue("入队必须走 insertAndFind", repo.contains("dao.insertAndFind("))
        assertTrue("冲突窗口必须有收口（返回 Conflict 而不是抛）", repo.contains("VideoDownloadEnqueueResult.Conflict"))
    }

    /** 新分支不许被 `else -> Unit` 静默吞掉，否则用户点了没反应。 */
    @Test
    fun uiHandlesTheNewBranch() {
        val vm = codeOf(VIEW_MODEL)
        val at = vm.indexOf("downloadRepository.enqueue(")
        assertTrue("找不到入队调用点", at > 0)
        val block = vm.substring(at, minOf(vm.length, at + 900))
        assertTrue("Conflict 分支必须给出可重试的提示", block.contains("VideoDownloadEnqueueResult.Conflict"))
        assertTrue(
            "AlreadyExists 与 Conflict 的文案必须不同（前者是真重复，后者是一条都没进队列）",
            block.contains("任务已存在") && block.contains("请再试一次")
        )
    }

    private companion object {
        const val DAO = "core/download/src/main/java/com/naaammme/bbspace/core/download/VideoDownloadDao.kt"
        const val REPOSITORY =
            "core/download/src/main/java/com/naaammme/bbspace/core/download/VideoDownloadRepository.kt"
        const val VIEW_MODEL =
            "feature/download/src/main/java/com/naaammme/bbspace/feature/download/DownloadViewModel.kt"
    }
}
