package com.naaammme.bbspace.core.designsystem.theme

import android.os.ParcelFileDescriptor
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机核验**「系统移除动效」**（移植自 BiliPai `ReduceMotion.kt`）。
 *
 * ## 为什么必须真机
 *
 * JVM 单测（`SystemReduceMotionTest`）验的是**纯判据**（给一个假的 resolver 值 → reduceMotion 真假）。
 * 它证明不了两件事，而这两件事恰好决定"功能到底有没有生效"：
 *
 * 1. **键名与取值语义在真系统上是否成立**：`Settings.Global.ANIMATOR_DURATION_SCALE`
 *    读出来是 float（1.0 / 0.5 / 0）—— 键名写错、按 int 读、或相等判断写错，单测全绿但真机永远 false；
 * 2. **`rememberSystemReduceMotion()` 的 ContentObserver 是否真的跟随**：用户是在**应用运行中**
 *    去开发者选项/无障碍里改的，不跟随就等于"改完要杀进程才生效"。
 *
 * 本测试用 `UiAutomation.executeShellCommand`（以 shell 身份）改真实系统设置，验完**还原原值**。
 */
@RunWith(AndroidJUnit4::class)
class SystemReduceMotionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.targetContext.contentResolver

    private var originalScale: String = "1.0"

    @Before
    fun rememberOriginalSetting() {
        originalScale = shell("settings get global animator_duration_scale").trim().ifBlank { "1.0" }
    }

    @After
    fun restoreOriginalSetting() {
        shell("settings put global animator_duration_scale $originalScale")
    }

    /** 判据必须跟随真实系统设置（含 `0` 这个"移除动效"的取值）。 */
    @Test
    fun readSystemReduceMotionFollowsTheRealSetting() {
        putScale("1.0")
        assertFalse("动效缩放 = 1.0 时不该判为 reduceMotion", readSystemReduceMotion(resolver))

        putScale("0")
        assertTrue(
            "动效缩放 = 0（系统「移除动画」/开发者选项关闭动画）必须判为 reduceMotion —— " +
                "键名或取值语义错了这里就会红。",
            readSystemReduceMotion(resolver)
        )

        putScale("0.5")
        assertFalse("只有 0 才算移除动效（源实现口径）", readSystemReduceMotion(resolver))
    }

    /** 运行中改系统设置，组合里的值必须跟着变（ContentObserver 接线）。 */
    @Test
    fun composeValueFollowsLiveSystemChange() {
        putScale("1.0")
        composeRule.setContent {
            Text(if (rememberSystemReduceMotion()) "reduced" else "normal")
        }
        composeRule.onAllNodesWithText("normal").fetchSemanticsNodes().let {
            assertTrue("初始应为 normal（动效缩放 = 1.0），实际 ${it.size} 个匹配", it.isNotEmpty())
        }

        putScale("0")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("reduced").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun putScale(value: String) {
        shell("settings put global animator_duration_scale $value")
        // 设置写入是跨进程的，给它一拍；避免读到旧值造成假红
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (readSystemReduceMotion(resolver) == (value == "0")) return
            if (shell("settings get global animator_duration_scale").trim() == value) return
            Thread.sleep(50)
        }
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            stream.readBytes().decodeToString()
        }
    }
}
