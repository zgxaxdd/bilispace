package com.naaammme.bbspace.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「进程级单例持有 UI 层回调」这条契约的结构守卫（BACKLOG ST-03）。
 *
 * ## 病灶
 * `PlaybackHostState` 是 `@Singleton`，上面挂着 `var onPlaybackUiStarted: (() -> Unit)?`，
 * 由 `MainActivity.onCreate` 赋值为一个捕获 `this` 的 lambda，而 **MainActivity 原本连
 * `onDestroy` 都没有重写** —— 于是回调永不清理。
 * 平时看不出来：正常重建时新实例的 onCreate 会把字段覆盖掉。真正漏的是
 * **后台播放**这条本仓的核心场景：MainActivity 可以销毁而不再重建（进程靠播放会话活着），
 * 单例就长期持有那个已死的 Activity —— 连带整棵 Compose 树与 ViewRootImpl。
 *
 * ## 为什么用源码扫描
 * 与本仓 [PlaybackHostDismissGuardTest] 同一惯例：这不是"某次调用的结果"，
 * 而是"谁注册谁必须反注册"的**写法不变式**，JVM 里没有 Activity 生命周期可跑。
 *
 * ## 防假绿
 * 1. 先断言扫描器真读到源码（`scannerActuallyReadsSources`）；
 * 2. 声明名由正则从真值文件里取，取不到就红（改签名会让守卫静默失效）；
 * 3. 断言"清理发生在 onDestroy 体内"，而不是"文件里某处有 = null"。
 */
class SingletonCallbackLeakGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val appMainSources: List<File> by lazy {
        File(repoRoot, "app${File.separator}src${File.separator}main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    @Test
    fun scannerActuallyReadsSources() {
        // 阈值依据实测：app/src/main 当前 19 个 .kt（薄模块，业务在 core/feature）。
        // 路径推导一旦失效就是 0，所以下限只需远小于 19。
        assertTrue(
            "只扫到 ${appMainSources.size} 个 app/src/main 源码文件" +
                "（repoRoot=${repoRoot.absolutePath}），少于 15 个说明路径推导失效。",
            appMainSources.size >= 15
        )
        assertTrue(
            "扫描结果必须同时含注册方与被挂回调的持有者，否则后两条断言会空转：" +
                "MainActivity.kt=${appMainSources.any { it.name == "MainActivity.kt" }} " +
                "PlaybackHostState.kt=${appMainSources.any { it.name == "PlaybackHostState.kt" }}",
            appMainSources.any { it.name == "MainActivity.kt" } &&
                appMainSources.any { it.name == "PlaybackHostState.kt" }
        )
    }

    /** 单例上声明的可空回调，赋值方必须同文件内清理。 */
    @Test
    fun singletonCallbacksAreClearedByWhoeverAssignsThem() {
        val declarations = codeOf(stateFile()).let { text ->
            Regex("""var\s+(\w+)\s*:\s*\(\(\) -> Unit\)\?""").findAll(text).map { it.groupValues[1] }.toList()
        }
        assertTrue(
            "在 PlaybackHostState 里没匹配到任何 `var x: (() -> Unit)?` 声明 —— " +
                "要么签名变了（守卫随之失效，必须同步本测试），要么文件没读到。",
            declarations.isNotEmpty()
        )
        for (name in declarations) {
            val assign = Regex("""\.\s*$name\s*=\s*(?!null)""")
            val clear = Regex("""\.\s*$name\s*=\s*null""")
            for (file in appMainSources.filter { assign.containsMatchIn(codeOf(it)) }) {
                assertTrue(
                    "${file.name} 给进程级单例的回调 `$name` 赋了值却没有清理：" +
                        "捕获 this 的 lambda 会随单例长期存活，把已销毁的宿主（Activity/Compose 树）一起拖住。",
                    clear.containsMatchIn(codeOf(file))
                )
            }
        }
    }

    /** 清理必须发生在 onDestroy 体内，而且要按身份比对（防误清新实例的回调）。 */
    @Test
    fun mainActivityClearsItsCallbackInOnDestroy() {
        val main = sourceOf("MainActivity.kt")
        val body = blockBody(main, "override fun onDestroy()")
        assertTrue(
            "onDestroy 里必须把 onPlaybackUiStarted 置 null —— 只靠新实例覆盖字段，" +
                "在『销毁后不重建』的路径上就是永久泄漏。",
            body.any { it.contains("onPlaybackUiStarted = null") }
        )
        assertTrue(
            "清理要按身份比对（`=== 自己的回调`）：重建时序下新实例可能已经装好它自己的回调，" +
                "无条件置 null 会把它的清掉，权限标记从此再也打不上。",
            body.any { it.contains("=== playbackUiStartedCallback") }
        )
    }

    /** 注册点必须用字段引用，不能重新写内联 lambda（那样身份比对永远不成立）。 */
    @Test
    fun registrationUsesTheStableFieldReference() {
        val main = codeOf(sourceFile("MainActivity.kt"))
        assertTrue(
            "MainActivity.onCreate 必须以 `= playbackUiStartedCallback` 注册（内联 lambda 会让 " +
                "onDestroy 里的身份比对失配，等于没清理）。",
            Regex("""onPlaybackUiStarted\s*=\s*playbackUiStartedCallback""").containsMatchIn(main)
        )
        assertTrue(
            "回调字段必须是 `private val`（每次取用都新建实例的话，身份比对同样失配）。",
            Regex("""private\s+val\s+playbackUiStartedCallback""").containsMatchIn(main)
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun stateFile(): File = sourceFile("playback${File.separator}PlaybackHostState.kt")

    private fun sourceFile(suffix: String): File {
        val normalized = suffix.replace('/', File.separatorChar)
        return appMainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。")
    }

    private fun sourceOf(suffix: String): List<String> = sourceFile(suffix).readLines().map { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
            trimmed.startsWith("import ")
        ) {
            ""
        } else {
            line
        }
    }

    /** 去掉注释与 import 后的文件文本（注释里出现 `= null` 不该让守卫误判为已清理）。 */
    private fun codeOf(file: File): String = sourceOf(file.name).joinToString("\n")

    /** 取以 [signaturePrefix] 开头的函数体（含签名），按花括号配平定界。 */
    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`，无法验证其内部结构。", start >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> { depth++; opened = true }
                    '}' -> depth--
                }
            }
            if (opened && depth == 0) break
        }
        return body
    }
}
