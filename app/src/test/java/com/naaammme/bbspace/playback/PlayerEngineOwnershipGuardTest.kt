package com.naaammme.bbspace.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 引擎所有权仲裁必须**接在真实调用点上**（BACKLOG ST-07）。
 *
 * ## 为什么语义测试不够
 * `PlayerEngineArbiterTest` 测的是仲裁器本身的状态机；它能证明"非占用者 release 返回 false"，
 * 但证明不了页面收尾时**真的问了仲裁器**。接线一旦被改回无条件 `playerEngine.release()`，
 * 缺陷就原地复活，而所有单元测试照样全绿 —— 这类"逻辑对、接线丢"的回归只能靠扫源码拦。
 *
 * ## 规则
 * 1. 两台共用引擎的会话都必须从构造器拿到仲裁器；
 * 2. 每一次往引擎写媒体（`setSource`）之前必须 `claim`；
 * 3. 每一次 `playerEngine.release()` 必须由 `arbiter.release(...)` 把关（同行或紧邻上方）；
 * 4. 每一次 `playerEngine.stopForReuse(...)` 必须由 `arbiter.isHolder(...)` 把关。
 *    stopForReuse 同样会打断别人的在播会话，只是不打"释放"这张牌。
 */
class PlayerEngineOwnershipGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val downloadFile = File(
        repoRoot,
        "core/playback/src/main/java/com/naaammme/bbspace/core/playback/DownloadPlaybackControllerImpl.kt"
    )
    private val streamFile = File(
        repoRoot,
        "core/playback/src/main/java/com/naaammme/bbspace/core/playback/StreamPlaybackSessionImpl.kt"
    )

    /** 返回 (行号, 去掉注释后的代码行)；行号从 0 计。 */
    private fun lines(file: File): List<Pair<Int, String>> = file.readLines().mapIndexed { index, line ->
        val trimmed = line.trimStart()
        // 注释与 KDoc 里的字面值不算接线（否则文档里提一句就能凑过断言）
        val code = if (
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        ) {
            ""
        } else {
            line
        }
        index to code
    }

    @Test
    fun bothSessionsAreWiredToTheArbiter() {
        assertTrue("找不到 ${downloadFile.name}", downloadFile.isFile)
        assertTrue("找不到 ${streamFile.name}", streamFile.isFile)
        for (file in listOf(downloadFile, streamFile)) {
            assertTrue(
                "${file.name} 没有从构造器注入 PlayerEngineArbiter —— 接线被摘掉时本守卫必须红。",
                file.readText().contains("arbiter: PlayerEngineArbiter")
            )
        }
    }

    @Test
    fun scannerActuallySeesTheEngineCalls() {
        val releases = countMatches(downloadFile, ENGINE_RELEASE) + countMatches(streamFile, ENGINE_RELEASE)
        val writes = countMatches(downloadFile, ENGINE_SET_SOURCE) + countMatches(streamFile, ENGINE_SET_SOURCE)
        assertTrue(
            "两个会话里只找到 $releases 处 playerEngine.release()（预期 ≥2）—— " +
                "正则失配会让下面的断言全部空转。",
            releases >= 2
        )
        assertTrue(
            "两个会话里只找到 $writes 处 playerEngine.setSource（预期 ≥2），本守卫会空转。",
            writes >= 2
        )
    }

    /** 每次 release 都有人把关；同时arbiter.release 的出现次数不许超过真实 release。 */
    @Test
    fun everyEngineReleaseIsGatedByOwnership() {
        val ungated = mutableListOf<String>()
        for (file in listOf(downloadFile, streamFile)) {
            val rows = lines(file)
            rows.forEach { (index, line) ->
                if (!ENGINE_RELEASE.containsMatchIn(line)) return@forEach
                val window = rows.asSequence()
                    .filter { it.first in (index - GUARD_WINDOW)..index }
                    .joinToString("\n") { it.second }
                if (!ARBITER_RELEASE.containsMatchIn(window)) {
                    ungated += "${file.name}:${index + 1} -> ${line.trim()}"
                }
            }
        }
        assertTrue(
            "playerEngine.release() 必须由 arbiter.release(...) 把关，否则非占用者的收尾会拆掉别人的播放器：\n" +
                ungated.joinToString("\n"),
            ungated.isEmpty()
        )
    }

    @Test
    fun everyStopForReuseIsGatedByOwnership() {
        val ungated = mutableListOf<String>()
        for (file in listOf(downloadFile, streamFile)) {
            val rows = lines(file)
            rows.forEach { (index, line) ->
                if (!ENGINE_STOP.containsMatchIn(line)) return@forEach
                val window = rows.asSequence()
                    .filter { it.first in (index - GUARD_WINDOW)..index }
                    .joinToString("\n") { it.second }
                if (!ARBITER_HOLDER.containsMatchIn(window)) {
                    ungated += "${file.name}:${index + 1} -> ${line.trim()}"
                }
            }
        }
        assertTrue(
            "playerEngine.stopForReuse(...) 同样会打断别人的在播会话，必须由 arbiter.isHolder(...) 把关：\n" +
                ungated.joinToString("\n"),
            ungated.isEmpty()
        )
    }

    /** 写媒体之前先 claim —— 抢占式的归属规则里，claim 是唯一的归属来源。 */
    @Test
    fun everyEngineWriteClaimsOwnership() {
        for (file in listOf(downloadFile, streamFile)) {
            val rows = lines(file)
            var writes = 0
            val ungated = mutableListOf<String>()
            rows.forEach { (index, line) ->
                if (!ENGINE_SET_SOURCE.containsMatchIn(line)) return@forEach
                writes += 1
                val window = rows.asSequence()
                    .filter { it.first in (index - GUARD_WINDOW)..index }
                    .joinToString("\n") { it.second }
                if (!ARBITER_CLAIM.containsMatchIn(window)) {
                    ungated += "${file.name}:${index + 1} -> ${line.trim()}"
                }
            }
            assertTrue(
                "${file.name} 有 $writes 处 setSource，但其中这些没先 claim：\n" + ungated.joinToString("\n"),
                ungated.isEmpty()
            )
        }
    }

    /** 仲裁器不许被"绕过"：这两个文件里不许出现无归属检查的引擎拆毁写法。 */
    @Test
    fun noBackdoorThroughTheRawPlayer() {
        val offenders = listOf(downloadFile, streamFile).flatMap { file ->
            lines(file).mapNotNull { (index, line) ->
                if (!RAW_PLAYER_RELEASE.containsMatchIn(line)) return@mapNotNull null
                "${file.name}:${index + 1} -> ${line.trim()}"
            }
        }
        assertTrue(
            "不许绕过引擎直接 release 播放器实例（视图解绑由 PlayerViewTargetBinder 负责）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    private fun countMatches(file: File, pattern: Regex): Int =
        lines(file).count { pattern.containsMatchIn(it.second) }

    private companion object {
        /** 把关语句允许出现在同行或紧邻上方（写法上的 `{`/`->` 换行都算）。 */
        const val GUARD_WINDOW = 2

        val ENGINE_RELEASE = Regex("""playerEngine\.release\(\)""")
        val ENGINE_STOP = Regex("""playerEngine\.stopForReuse\(""")
        val ENGINE_SET_SOURCE = Regex("""playerEngine\.setSource\(""")
        val ARBITER_RELEASE = Regex("""arbiter\.release\(""")
        val ARBITER_HOLDER = Regex("""arbiter\.isHolder\(""")
        val ARBITER_CLAIM = Regex("""arbiter\.claim\(""")
        val RAW_PLAYER_RELEASE = Regex("""player\.release\(\)|\.value\?\.release\(\)""")
    }
}
