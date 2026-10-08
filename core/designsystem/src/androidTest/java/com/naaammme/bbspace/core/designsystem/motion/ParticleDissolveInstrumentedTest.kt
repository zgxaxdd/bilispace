package com.naaammme.bbspace.core.designsystem.motion

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.naaammme.bbspace.core.designsystem.motion.gl.isThanosEffectSupported
import com.naaammme.bbspace.core.designsystem.theme.AnimationSpeed
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.createAnimations
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机核验**粒子消散的运行期路径**（补 04 文档登记的"GL 渲染路径：静态契约已验证，运行期未验"）。
 *
 * 为什么这条必须真机：
 * - 消散要**真的抓一张位图**（`captureCardBitmap` 走真实 window/hardware canvas）、
 *   真的在 GLES 上下文里跑着色器（`ThanosEffectView` + `TextureView`）、
 *   再由帧回调在结束时派发 `onDissolveComplete`；
 * - 纯策略 / 契约测试（`ThanosParticlePolicyTest`、`ThanosGlContractTest`）只能证明
 *   "参数与着色器源码对得上"，证明不了"真机上真能跑完并把回调送达"。
 *
 * 判据有两条，缺一不可：
 * 1. **回调送达**（四个落点共用的收尾动作：不到，真删/真关就永远不执行）；
 * 2. **耗时下限**（≥800ms）：组件在"没窗口 / 不支持 GLES3 / 500ms 内没抓到卡片坐标"
 *    时会走**降级路径**（瞬时折叠 + 回调）。只断言回调会被降级路径蒙过去，
 *    所以这里额外用耗时区分"真的播了粒子"与"直接降级"。
 *
 * `systemReduceMotion` 在测试里被显式置 false（否则设备上动画缩放为 0 时会走"无障碍降级"分支，
 * 同样瞬时完成）——这样这条用例只测"GL 路径"。
 */
@RunWith(AndroidJUnit4::class)
class ParticleDissolveInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun glDissolveRunsOnDeviceAndDeliversCompletionCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val glSupported = isThanosEffectSupported(context)

        var completed = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAnimations provides createAnimations(
                    speed = AnimationSpeed.NORMAL,
                    systemReduceMotion = false
                )
            ) {
                var dissolving by remember { mutableStateOf(false) }
                Column {
                    Text(
                        text = "trigger",
                        modifier = Modifier.testTag("trigger").clickable { dissolving = true }
                    )
                    MaybeDissolvableVideoCard(
                        isDissolving = dissolving,
                        onDissolveComplete = { completed = true },
                        modifier = Modifier.testTag("card").size(240.dp, 120.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF3A6EA5))
                        ) {
                            Text("卡片内容")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val startedAtMs = SystemClock.elapsedRealtime()
        composeRule.onNodeWithTag("trigger").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { completed }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs

        assertTrue(
            "消散必须跑到 onDissolveComplete（GLES3 支持=$glSupported）—— " +
                "回调不到，四个落点的真删/真关动作就永远不执行。",
            completed
        )
        if (glSupported) {
            assertTrue(
                "GLES3 可用时必须真的播粒子（本次 ${elapsedMs}ms）。瞬时完成 = 走了降级路径，" +
                    "那说明真机上根本没跑起来（窗口/坐标/能力探针之一失效）。",
                elapsedMs >= 800L
            )
        }
    }

    /** GLES3 能力探针：同一次运行内必须稳定（抖动就意味着判据依赖了偶发状态）。 */
    @Test
    fun glVersionProbeIsDeterministic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = isThanosEffectSupported(context)
        val second = isThanosEffectSupported(context)
        assertTrue("能力探针必须可重复", first == second)
    }

    /**
     * 系统「移除动效」时必须**不起粒子**（瞬时折叠），但**收尾动作照样要送达**。
     *
     * 这条与上一条是一对：上一条证明"支持 GL 时会真播粒子"（3s 量级），这条证明
     * "reduceMotion 时退化成瞬时"（< 2s；降级路径的弹簧折叠约 0.4s）。
     * 两条一起把"系统开关 → 本仓判据 → 消散行为"这条链在真机上闭合
     * （判据本身另由 `SystemReduceMotionInstrumentedTest` 用真实系统设置验）。
     */
    @Test
    fun reduceMotionSkipsParticlesButStillDeliversCompletion() {
        var completed = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAnimations provides createAnimations(
                    speed = AnimationSpeed.NORMAL,
                    systemReduceMotion = true
                )
            ) {
                var dissolving by remember { mutableStateOf(false) }
                Column {
                    Text(
                        text = "trigger",
                        modifier = Modifier.testTag("trigger").clickable { dissolving = true }
                    )
                    MaybeDissolvableVideoCard(
                        isDissolving = dissolving,
                        onDissolveComplete = { completed = true },
                        modifier = Modifier.testTag("card").size(240.dp, 120.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF3A6EA5))
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val startedAtMs = SystemClock.elapsedRealtime()
        composeRule.onNodeWithTag("trigger").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { completed }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs

        assertTrue("reduceMotion 下收尾动作照样要送达（无障碍降级不能把动作吞掉）", completed)
        assertTrue(
            "reduceMotion 必须退化为瞬时折叠（实测 ${elapsedMs}ms）—— 起了粒子会是 3s 量级，" +
                "说明系统「移除动效」对消散不生效（源实现就有这个缺口，本仓第 9 轮特意补过）。",
            elapsedMs < 2_000L
        )
    }
}
