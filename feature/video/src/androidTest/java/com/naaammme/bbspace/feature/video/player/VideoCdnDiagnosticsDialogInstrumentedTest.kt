package com.naaammme.bbspace.feature.video.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naaammme.bbspace.infra.player.cdn.CdnTransferRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * CDN 线路诊断面板的**真机验收**（instrumented）。
 *
 * 为什么值得单独验：这个面板是"把 4 个零调用方的诊断 API 接活"的落点
 * （`preferHost` / `restoreRoutes` / `avoid` / `recentMediaUrls`）——
 * 只做结构断言（源码里出现这些字串）挡不住"点了没反应"。
 *
 * 真机环境（OPPO PLC110 / Android 16）验证方式：先在进程内把 `CdnTransferRuntime`
 * 灌成"有一条线路样本"的状态（它是纯 JVM 单例，测试进程内直接可用），
 * 再用 Compose 语义查找点"钉住"，断言运行时状态与 UI 同步变化。
 */
class VideoCdnDiagnosticsDialogInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val host = "upos-sz-mirrorali.bilivideo.com"

    @Before
    fun seedRuntimeWithOneNodeSample() {
        CdnTransferRuntime.configure(active = true, parallel = true)
        CdnTransferRuntime.start(host)
        CdnTransferRuntime.progress(host, bytes = 262_144, speedBps = 1_048_576, firstByteMs = 42L)
        CdnTransferRuntime.finish(host, success = true, status = 206)
        CdnTransferRuntime.playback(bufferMs = 20_000L, bitrateBps = 2_000_000L)
        // `publish()` 有 250ms 节流：等过窗口后再强制发布一次，保证 snapshot 已落到 StateFlow。
        Thread.sleep(400)
        CdnTransferRuntime.configure(active = true, parallel = true)
    }

    @After
    fun resetRuntime() {
        CdnTransferRuntime.restoreRoutes()
        CdnTransferRuntime.configure(active = false, parallel = false)
    }

    private fun renderDialog() {
        composeRule.setContent {
            MaterialTheme {
                VideoCdnDiagnosticsDialog(onDismiss = {})
            }
        }
    }

    @Test
    fun dialogShowsRuntimeSummaryAndNodeSample() {
        renderDialog()
        composeRule.onNodeWithText("CDN 线路诊断").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").assertIsDisplayed()
        // 节点行：主机名 + 操作按钮必须上屏（说明 state 真的被渲染出来了）
        composeRule.onNodeWithText(host).assertIsDisplayed()
        composeRule.onNodeWithText("钉住").assertIsDisplayed()
        composeRule.onNodeWithText("冷却 60s").assertIsDisplayed()
    }

    @Test
    fun pinningALineWritesThroughToTheRuntimeAndOffersRestore() {
        renderDialog()
        composeRule.onNodeWithText("钉住").performClick()
        composeRule.waitForIdle()
        assertEquals(
            "点「钉住」必须把该 host 写进 CdnTransferRuntime.preferredHost",
            host,
            CdnTransferRuntime.state.value.preferredHost
        )
        // 钉住后：行内按钮变成「使用中」，并且出现「恢复自动」
        composeRule.onNodeWithText("使用中").assertIsDisplayed()
        composeRule.onNodeWithText("恢复自动").assertIsDisplayed()

        composeRule.onNodeWithText("恢复自动").performClick()
        composeRule.waitForIdle()
        assertEquals(
            "点「恢复自动」必须清空 preferredHost",
            null,
            CdnTransferRuntime.state.value.preferredHost
        )
    }

    @Test
    fun coolingALineSetsItsCooldown() {
        renderDialog()
        composeRule.onNodeWithText("冷却 60s").performClick()
        composeRule.waitForIdle()
        val node = CdnTransferRuntime.state.value.nodes.firstOrNull { it.host == host }
        val now = CdnTransferRuntime.state.value.updatedAtMs
        assertEquals("冷却必须只作用于这一条线路", 1, CdnTransferRuntime.state.value.nodes.count { it.host == host })
        org.junit.Assert.assertTrue(
            "点「冷却 60s」必须把该线路的 cooldownUntilMs 推到将来（现在是 ${node?.cooldownUntilMs} / $now）",
            node != null && node.cooldownUntilMs > now
        )
    }
}
