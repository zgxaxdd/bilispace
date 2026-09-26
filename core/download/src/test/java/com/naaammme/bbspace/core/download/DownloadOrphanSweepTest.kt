package com.naaammme.bbspace.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 孤儿下载目录的清理规则（BACKLOG ST-11）。
 *
 * `File` 在 JVM 上就是真文件系统，所以这里建真目录、写真文件，
 * 验证"该删的删干净、不该删的一个都不碰"—— 误删的代价是用户的缓存视频，
 * 比漏删严重得多，所以保守规则要用**反例**钉住。
 */
class DownloadOrphanSweepTest {

    private fun tempRoot(): File = java.nio.file.Files.createTempDirectory("bbspace-dl-root").toFile()

    private fun taskDir(
        root: File,
        name: String,
        files: List<String> = listOf("video.m4s", "audio.m4s")
    ): File {
        val dir = File(root, name)
        dir.mkdirs()
        files.forEach { File(dir, it).writeText("payload-of-$it") }
        return dir
    }

    @Test
    fun orphanDirectoryIsRemovedWithItsContents() {
        val root = tempRoot()
        val orphan = taskDir(root, "42")
        val removed = sweepOrphanTaskDirs(root, liveTaskIds = setOf("7"))
        assertEquals(listOf("42"), removed)
        assertFalse("孤儿目录必须真的被删掉（几百 MB 的分片就在里面）", orphan.exists())
    }

    @Test
    fun directoryBackedByARowIsKept() {
        val root = tempRoot()
        val live = taskDir(root, "42")
        val removed = sweepOrphanTaskDirs(root, liveTaskIds = setOf("42", "7"))
        assertEquals(emptyList<String>(), removed)
        assertTrue(live.exists())
        assertTrue("内容也得原样留着", File(live, "video.m4s").readText().isNotEmpty())
    }

    /** 保守规则：认不出是任务目录的一律不碰。 */
    @Test
    fun anythingThatIsNotATaskDirectoryIsLeftAlone() {
        val root = tempRoot()
        val notes = taskDir(root, "manual-notes", files = listOf("a.txt"))
        val strayFile = File(root, "99").apply { writeText("我是个文件不是目录") }
        val leadingZero = taskDir(root, "042")
        val removed = sweepOrphanTaskDirs(root, liveTaskIds = emptySet())
        assertEquals(emptyList<String>(), removed)
        assertTrue(notes.exists())
        assertTrue(strayFile.exists())
        assertTrue("名字带前导零的目录不当作任务 id（它不可能是 RowId 的规范写法）", leadingZero.exists())
    }

    @Test
    fun emptyLiveSetWithNoChildrenIsNoOpAndMissingRootIsSafe() {
        assertEquals(emptyList<String>(), sweepOrphanTaskDirs(tempRoot(), emptySet()))
        assertEquals(
            "根目录还不存在（从未下载过）时不该抛，也不该建目录",
            emptyList<String>(),
            sweepOrphanTaskDirs(File(tempRoot(), "video_download"), setOf("1"))
        )
    }

    @Test
    fun multipleOrphansAreReportedSorted() {
        val root = tempRoot()
        taskDir(root, "9")
        taskDir(root, "3")
        taskDir(root, "5", files = listOf("only-audio.m4s"))
        val removed = sweepOrphanTaskDirs(root, liveTaskIds = setOf("5"))
        assertEquals(listOf("3", "9"), removed)
        assertTrue(File(root, "5").exists())
    }

    /**
     * 接线检查：清理确实挂在启动恢复路径上，而且是 **fail closed**。
     * 纯函数写对了但没人调用 = 缺陷原地不动；而"查询失败当作没有任务"会删光用户缓存，
     * 所以这条也一起钉（扫描的是真正会编译进产物的源码）。
     */
    @Test
    fun sweepIsWiredIntoStartupAndFailsClosed() {
        val rel = "core/download/src/main/java/com/naaammme/bbspace/core/download/VideoDownloadRepository.kt"
        val file = repoRootFile(rel)
        assertTrue("找不到 $rel —— 文件改名会让本断言空转", file.isFile)
        val code = file.readLines().joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
        }
        val callMatch = BARE_CALL.find(code)
        assertTrue(
            "启动路径里没有 sweepOrphanTaskDirsSafely() 的调用。" +
                "注意本断言刻意匹配「独占一行的调用」而不是函数名 —— " +
                "第一版用 indexOf(函数名)，它匹配到了 `private suspend fun …() {` 那行定义，" +
                "于是把 init 里的调用删掉这种回归照样绿（实测：BUILD SUCCESSFUL）",
            callMatch != null
        )
        val callAt = callMatch!!.range.first
        val recoverAt = code.indexOf("recoverRunningTasks()")
        val drainAt = code.indexOf("ensureDrain()", callAt)
        assertTrue(
            "清理必须在 recoverRunningTasks 之后（否则会与正在收尾的任务抢目录）",
            recoverAt in 0..<callAt
        )
        assertTrue(
            "清理必须在 ensureDrain 之前（开始跑下载后再扫，会误删正在写的分片）",
            drainAt > callAt
        )
        assertTrue("id 集合必须走 dao.allIds()", code.contains("dao.allIds()"))
        assertTrue(
            "查询失败必须直接 return（fail closed），不能把空集合当成「没有任务」",
            Regex("""getOrNull\(\)\s*\?:\s*return""").containsMatchIn(code)
        )
    }

    private fun repoRootFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && File(dir, "settings.gradle.kts").isFile.not()) dir = dir.parentFile
        return File(dir ?: File(""), rel.replace('/', File.separatorChar))
    }

    private companion object {
        /** 独占一行的调用：前面不许挂 `fun`，行尾不许有 `{`。 */
        val BARE_CALL = Regex("""(?m)^\s*sweepOrphanTaskDirsSafely\(\)\s*$""")
    }
}
