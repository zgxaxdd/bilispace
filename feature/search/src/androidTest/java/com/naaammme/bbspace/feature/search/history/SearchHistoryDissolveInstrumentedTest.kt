package com.naaammme.bbspace.feature.search.history

import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.naaammme.bbspace.core.designsystem.theme.AnimationSpeed
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import com.naaammme.bbspace.core.designsystem.theme.createAnimations
import com.naaammme.bbspace.core.model.SearchHistoryOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 搜索历史落点：「长按 → 粒子消散 → **然后才**真删」的真机核验（R-15）。
 *
 * 为什么这条值得单测：移植 BiliPai 的 4 个消散落点里，3 个（搜索历史长按／拉黑撤销／下载删除）
 * 只有结构断言（`DissolveRenderPolicyTest` 要求"必须用 Maybe 版、动作必须写在
 * onDissolveComplete 里"），没有真机链路。`SearchHistoryPanel` 是**纯参数组件**
 * （`histories` + 3 个回调，不依赖 ViewModel/DI），所以它是唯一能在 instrumented 测试里
 * 直连调用的落点 —— 于是把这 3 条里最有代表性的一条补成真机用例。
 *
 * 判据（对应"先粒子、后动作"这个产品契约，源实现同序）：
 * 1. **长按那一刻不许删数据**（动作被放错位置——比如写在 `onLongClick` 里——会立刻违反）；
 * 2. 消散跑完后 `onDelete` 必须收到那个词；
 * 3. 从长按到回调的耗时 ≥ 300ms（GLES 路径是 2~3s；瞬时完成说明走的是降级路径，
 *    与 R-13 用过的启发式同源）。
 */
@RunWith(AndroidJUnit4::class)
class SearchHistoryDissolveInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPressDissolvesFirstThenDeletes() {
        var deleted: String? = null
        var searched: String? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAnimations provides createAnimations(
                    speed = AnimationSpeed.NORMAL,
                    systemReduceMotion = false
                )
            ) {
                SearchHistoryPanel(
                    histories = listOf("甲词", "乙词", "丙词"),
                    order = SearchHistoryOrder.TIME,
                    onToggleOrder = {},
                    onSearch = { searched = it },
                    onDelete = { deleted = it }
                )
            }
        }
        composeRule.waitForIdle()

        val startedAtMs = SystemClock.elapsedRealtime()
        composeRule.onNodeWithText("甲词").performTouchInput { longClick() }

        assertNull(
            "长按那一刻不许直接删数据 —— 动作必须等粒子播完（onDissolveComplete）再执行，" +
                "否则看起来「碎了」其实数据早没了，粒子只是装饰。",
            deleted
        )

        composeRule.waitUntil(timeoutMillis = 10_000) { deleted != null }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs

        assertEquals("真删的必须是被长按的那个词", "甲词", deleted)
        assertNull("长按不该触发搜索", searched)
        assertTrue(
            "从长按到真删的耗时 ${elapsedMs}ms：粒子路径是秒级；瞬时说明落点根本没接消散",
            elapsedMs >= 300L
        )
    }

    @Test
    fun singleTapSearchesAndNeverDeletes() {
        var deleted: String? = null
        var searched: String? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAnimations provides createAnimations(
                    speed = AnimationSpeed.NORMAL,
                    systemReduceMotion = false
                )
            ) {
                SearchHistoryPanel(
                    histories = listOf("甲词", "乙词"),
                    order = SearchHistoryOrder.TIME,
                    onToggleOrder = {},
                    onSearch = { searched = it },
                    onDelete = { deleted = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("乙词").performClick()
        composeRule.waitForIdle()

        assertEquals("单击应触发搜索", "乙词", searched)
        assertNull("单击绝不该删除", deleted)
    }
}
