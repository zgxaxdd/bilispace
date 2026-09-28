package com.naaammme.bbspace.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 根导航「返回键」接线的结构守卫。
 *
 * ## 这条守卫守的是一个真机故障（2026-09-28）
 *
 * 用户反馈：「没有 OPPO 动画覆盖的（页面），比如设置页面，打开和退出动画不是一个，
 * 不管在设置里怎么调过渡动画都不生效」「主要是退出动画，一直是那个缩放动画」。
 *
 * 根因（javap 反编译依赖 AAR 得到，见 `docs/ai-loop/report-2026-09-28-C9.md`）：
 * - `navigation-compose` 升到 **2.10.1** 后，NavHost 的返回处理从
 *   `OnBackPressedDispatcher` 换成了 `androidx.navigationevent`
 *   （2.5.1 的 `NavHostKt` 引用 `setOnBackPressedDispatcher`/`enableOnBackPressed`；
 *   2.10.1 改成 `NavHostEventHandler : NavigationEventHandler`，
 *   启用判据是它自己的 `composeNavigator.backStack.size > 1`，且经
 *   `LifecycleStartEffect` 写入、`onStopOrDispose` 复位）；
 * - activity 1.13.0 的 `OnBackInvokedInput.updateBackInvokedCallbackState()` 只在
 *   **存在 enabled handler** 时才注册平台的 `OnBackInvokedCallback`；
 * - 两者叠加：**没有任何 enabled handler 时，返回键 → `Activity.onBackPressed()`
 *   → `finishAfterTransition()`**，也就是整个 App 退到桌面。
 *
 * 于是"退出"走的是**系统关闭任务的窗口动画**（那个缩放），而"打开"走 NavHost 的
 * `enterTransition` —— 两者永远不可能对称，且不受应用内过渡设置控制。
 *
 * ## 为什么是结构守卫
 *
 * 这条不变式牵涉两套库的内部实现（navigationevent 的处理器栈 + activity 的
 * OnBackInvoked 注册），在 JVM 上跑不起来；能钉住的是"这个接线必须存在、必须写在
 * 这个位置、必须用这个判据"，改动它就会红。
 */
class NavBackHandlingGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val appNavHost: List<String> by lazy {
        val f = File(
            repoRoot,
            "app/src/main/java/com/naaammme/bbspace/navigation/AppNavHost.kt"
        )
        assertTrue("找不到 AppNavHost.kt（repoRoot=${repoRoot.absolutePath}）", f.isFile)
        f.readLines().map { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") ||
                t.startsWith("import ")
            ) {
                ""
            } else {
                line
            }
        }
    }

    private fun count(regex: String): Int {
        val re = Regex(regex)
        return appNavHost.count { it.isNotBlank() && re.containsMatchIn(it) }
    }

    @Test
    fun scannerReadsAppNavHost() {
        assertTrue("AppNavHost.kt 只读到 ${appNavHost.size} 行，路径推导可能失效", appNavHost.size > 300)
    }

    /**
     * 必须由应用自己在根导航上注册一个返回处理器。
     *
     * 反例守卫：**不许**只调 `navController.enableOnBackPressed(true)` ——
     * 在 Compose 的 2.10.1 里 `NavController.onBackPressedCallback` 只在
     * `setOnBackPressedDispatcher(...)` 里才会被 `addCallback`，而 NavHost 已经
     * 不再调用它，那条链路是死的（grep 全仓 `setOnBackPressedDispatcher` 为 0）。
     */
    @Test
    fun rootBackHandlerIsRegistered() {
        assertTrue(
            "根导航必须自己注册返回处理器：`BackHandler(enabled = canPopRoot) { " +
                "rootNavController.popBackStack() }`。没有它，nav 2.10.1 的 " +
                "navigationevent 管线在 NavHost 处理器失效时找不到任何 enabled handler，" +
                "返回键会直接 finish 整个 Activity（症状=系统关闭动画、" +
                "流转场设置永远不生效）。",
            count("""BackHandler\(enabled = canPopRoot\)""") == 1 &&
                count("""rootNavController\.popBackStack\(\)""") >= 1
        )
        assertTrue(
            "不许改成 nav 的旧 API：`enableOnBackPressed` / `setOnBackPressedDispatcher` " +
                "在 Compose 2.10.1 下已是死链路（NavHost 不再调用后者）。",
            count("""enableOnBackPressed\(""") == 0 &&
                count("""setOnBackPressedDispatcher\(""") == 0
        )
    }

    /**
     * 判据必须与 `NavController.updateOnBackPressedCallbackEnabled()` 一致：
     * 只数**真实目的地**（NavGraph 根不算）。
     *
     * 写成 `backStack.size > 1` 会在"根图上只有 startDestination"时也判 true ——
     * 于是本处理器把返回键吃掉（`popBackStack()` 返回 false），用户退不出 App。
     */
    @Test
    fun backEnabledPredicateCountsRealDestinations() {
        assertTrue(
            "启用判据必须是「真实目的地 > 1」（排除 NavGraph 根）：" +
                "`rootBackStack.count { it.destination !is NavGraph } > 1`。",
            count("""count \{ it\.destination !is NavGraph \} > 1""") == 1
        )
    }

    /**
     * **位置**要求：注册必须写在 `NavHost(` **之前**。
     *
     * navigationevent 的处理器容器用 `addFirst` 插入、解析时取"第一个 enabled"
     * （javap 实证）→ **后注册者优先级更高**。写在 NavHost 之前，优先级天然是
     * 「目的地内部 BackHandler（评论面板/搜索/直播，后注册）> NavHost 自己的处理器
     * （带预测式返回预览）> 本兜底处理器」；写在之后会把兜底顶到最高优先级，
     * 抢掉预测式预览和面板的"先关面板再弹栈"语义。
     */
    @Test
    fun backHandlerIsRegisteredBeforeNavHost() {
        val handlerAt = appNavHost.indexOfFirst { it.contains("BackHandler(enabled = canPopRoot)") }
        // 只认 NavHost 的**调用**（行首就是 NavHost( ），别被 `fun AppNavHost(` 声明骗到
        val navHostAt = appNavHost.indexOfFirst { it.trimStart().startsWith("NavHost(") }
        assertTrue("找不到 BackHandler 或 NavHost 调用点", handlerAt >= 0 && navHostAt >= 0)
        assertTrue(
            "兜底返回处理器必须写在 `NavHost(` **之前**（后注册者优先级更高）：" +
                "现在 handler@$handlerAt vs NavHost@$navHostAt。",
            handlerAt < navHostAt
        )
    }

    /**
     * NavHost 的六个过渡参数都要接上（含预测式返回）。
     *
     * 不接 `predictivePop*` 时，2.10.1 在手势返回时走它内置的默认过渡 ——
     * 既与设置里的风格不一致，也让"返回到底走没走 NavHost"在日志上不可见
     * （本轮排查就是先被这一点误导过）。
     */
    @Test
    fun navHostWiresAllTransitionsIncludingPredictive() {
        assertTrue(
            "NavHost 必须接满六个过渡参数（enter/exit/popEnter/popExit + " +
                "predictivePopEnter/predictivePopExit），且都来自 buildNavTransitions。",
            count("""enterTransition = \{ transitions\.enter\(this\) \}""") == 1 &&
                count("""exitTransition = \{ transitions\.exit\(this\) \}""") == 1 &&
                count("""popEnterTransition = \{ transitions\.popEnter\(this\) \}""") == 1 &&
                count("""popExitTransition = \{ transitions\.popExit\(this\) \}""") == 1 &&
                count("""predictivePopEnterTransition = \{ _ -> transitions\.popEnter\(this\) \}""") == 1 &&
                count("""predictivePopExitTransition = \{ _ -> transitions\.popExit\(this\) \}""") == 1
        )
    }
}
