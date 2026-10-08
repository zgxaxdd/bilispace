package com.naaammme.bbspace.feature.video.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naaammme.bbspace.core.model.VideoCdnMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * CDN 档位对话框的**真机验收**（instrumented）。
 *
 * ## 为什么要用这个方式（而不是 adb 点按）
 *
 * 实测：`adb shell input tap` 能驱动本 app 的导航图标，但**驱动不了列表项与对话框选项**
 * （十几次尝试全部落空，`input tap` 与带按住时长的 `input swipe` 都试过）。
 * 所以 CDN 档位的自动化验证只能走 **Compose 语义查找**：`onNodeWithText(...).performClick()`
 * —— 按文本定位，不依赖坐标，可重复、可回归。
 *
 * ## 覆盖的验收点
 *
 * 1. 对话框能打开（标题「选择 CDN」）；
 * 2. **「智能选线（推荐）」在列表里可见**（移植进来的新档位确实上屏了）；
 * 3. 点它 → 回调收到 `VideoCdnMode.Smart`（不是别的档）；
 * 4. 当前档位有勾选标记（`Icons.Default.Check`）—— 用"当前档=其它，点完变成 Smart"间接验证。
 */
class CdnModeDialogInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 智能选线的文案与描述来自 `VideoCdnMode.Smart`（移植时写在枚举里）。 */
    private val smartLabel = VideoCdnMode.Smart.label
    private val smartDescription = VideoCdnMode.Smart.description

    @Test
    fun dialogShowsEveryModeIncludingSmart() {
        composeRule.setContent {
            MaterialTheme {
                VideoCdnModeDialog(
                    currentMode = VideoCdnMode.Backup2,
                    onDismiss = {},
                    onSelect = {}
                )
            }
        }
        composeRule.onNodeWithText("选择 CDN").assertIsDisplayed()
        // 移植进来的智能档必须可见，且文案不能为空
        assertNotNull("Smart 档的 label 不该为空", smartLabel.takeIf { it.isNotBlank() })
        composeRule.onNodeWithText(smartLabel).assertIsDisplayed()
        composeRule.onNodeWithText(smartDescription).assertIsDisplayed()
        // 其它档位仍要在（没有被我加档位时挤掉）
        composeRule.onNodeWithText(VideoCdnMode.Backup2.label).assertIsDisplayed()
    }

    /** **核心断言**：点「智能选线（推荐）」必须回调 `VideoCdnMode.Smart`。 */
    @Test
    fun selectingSmartReportsSmartMode() {
        var selected: VideoCdnMode? = null
        composeRule.setContent {
            MaterialTheme {
                VideoCdnModeDialog(
                    currentMode = VideoCdnMode.Backup2,
                    onDismiss = {},
                    onSelect = { selected = it }
                )
            }
        }
        composeRule.onNodeWithText(smartLabel).performClick()
        composeRule.waitForIdle()
        assertEquals(
            "点「$smartLabel」必须选出 VideoCdnMode.Smart",
            VideoCdnMode.Smart,
            selected
        )
    }

    /** 反向：从 Smart 切回别的档也要能选（不能只让 Smart 可点）。 */
    @Test
    fun canSwitchAwayFromSmart() {
        var selected: VideoCdnMode? = null
        composeRule.setContent {
            MaterialTheme {
                VideoCdnModeDialog(
                    currentMode = VideoCdnMode.Smart,
                    onDismiss = {},
                    onSelect = { selected = it }
                )
            }
        }
        composeRule.onNodeWithText(VideoCdnMode.BaseUrl.label).performClick()
        composeRule.waitForIdle()
        assertEquals(VideoCdnMode.BaseUrl, selected)
    }

    /** 智能档必须**排在最前**（移植时按"推荐档置顶"落地，是刻意的产品顺序）。 */
    @Test
    fun smartIsTheFirstEntry() {
        assertEquals(
            "智能选线应当是枚举首项（推荐档置顶）",
            VideoCdnMode.Smart,
            VideoCdnMode.entries.first()
        )
    }
}
