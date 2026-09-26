package com.naaammme.bbspace.resource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 重量级视图资源的"创建必配释放钩子"守卫（BACKLOG ST-04）。
 *
 * ## 病灶
 * `feature/auth/.../GeetestDialog.kt` 在 `AndroidView(factory = { WebView(ctx) })` 里造
 * WebView，**全文件没有 onRelease / onDispose，也没有 destroy()**。
 * WebView 不是普通 View：它带自己的渲染进程与 native 堆，离开组合体时"没人引用"并不等于
 * 能回收 —— 每过一次人机验证就泄漏一个，而极验在短信登录失败重试时会被反复触发。
 * 同仓的 `WebViewScreen.kt:175-188` 用的是正确写法（`DisposableEffect { onDispose { destroy() } }`），
 * 说明这条约定本来存在，只是没有机器拦住"漏一处"。
 *
 * ## 为什么这条**不带白名单**
 * B3 的 `HttpResponseReleaseGuardTest` 留了一条例外（TicketGenerator 语义已闭合但写法不同）。
 * 这条不留：两个 WebView 创建点都规规矩矩，任何"例外"都只会在下一次评审时被顺手加宽。
 *
 * ## 防假绿
 * 先断言扫描器真读到源码、且**确实扫到 ≥2 个 WebView 创建点** —— 正则失配会让下面那条对空集合永真。
 * 注释行与 import 行清空后再匹配（在注释里写 `WebView(` 不该让计数虚增）。
 */
class ResourceReleaseGuardTest {

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

    /** 真正 new 出 WebView 的文件（`\b` 把 `navigateToWebView(` 这类同名函数排除掉）。 */
    private fun webViewFactories(): List<Pair<String, File>> = mainSources.mapNotNull { file ->
        val code = codeOf(file)
        if (WEBVIEW_CONSTRUCTOR.containsMatchIn(code)) {
            file.relativeTo(repoRoot).path.replace('\\', '/') to file
        } else {
            null
        }
    }

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效。",
            mainSources.size > 100
        )
        val found = webViewFactories()
        assertTrue(
            "只找到 ${found.size} 个 WebView 创建点（预期 ≥2：GeetestDialog 与 WebViewScreen）——" +
                "少于 2 说明正则失配，下面的断言会对空集合永真。",
            found.size >= 2
        )
    }

    /** 每个 WebView 创建点必须同时有释放钩子与 destroy()。 */
    @Test
    fun everyWebViewIsReleasedAndDestroyed() {
        val offenders = webViewFactories().filterNot { (_, file) ->
            val code = codeOf(file)
            code.contains("destroy()") && (code.contains("onRelease") || code.contains("onDispose"))
        }.map { (rel, _) -> rel }
        assertTrue(
            "WebView 必须在离开组合体时 destroy()：光靠 GC 回收不掉它自带的渲染进程与 native 堆。\n" +
                "缺钩子的文件：\n" + offenders.joinToString("\n") +
                "\n两种正确写法：AndroidView 传 `onRelease = { it.destroy() }`，" +
                "或 `DisposableEffect(key) { onDispose { ... destroy() } }`（见 WebViewScreen.kt）。",
            offenders.isEmpty()
        )
    }

    /** Geetest 的 JS 桥要在 destroy 前先摘掉（否则销毁后到达的 post{} 仍持有整个捕获作用域）。 */
    @Test
    fun geetestBridgeIsRemovedBeforeDestroy() {
        val entry = webViewFactories().firstOrNull { (rel, _) -> rel.endsWith("GeetestDialog.kt") }
            ?: throw AssertionError("没扫到 GeetestDialog.kt —— 路径变了要同步本测试。")
        val text = codeOf(entry.second)
        assertTrue("GeetestDialog 必须先 removeJavascriptInterface(\"GeetestBridge\")", text.contains("""removeJavascriptInterface("GeetestBridge")"""))
        val removeAt = text.indexOf("""removeJavascriptInterface("GeetestBridge")""")
        val destroyAt = text.indexOf("destroy()")
        assertTrue("摘桥必须发生在 destroy() 之前", removeAt in 0 until destroyAt)
    }

    private fun codeOf(file: File): String = file.readLines().map { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
            trimmed.startsWith("import ")
        ) {
            ""
        } else {
            line
        }
    }.joinToString("\n")

    private companion object {
        val WEBVIEW_CONSTRUCTOR = Regex("""\bWebView\s*\(""")
    }
}
