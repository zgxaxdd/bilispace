package com.naaammme.bbspace.feature.video.player

import androidx.compose.runtime.Composable
import com.naaammme.bbspace.core.designsystem.component.IOSAlertDialog

/**
 * 刷视频会话的**拉黑二次确认**（2026-10-19 第 34 轮拆分自 `PortraitSwipePager.kt`，
 * 并把对话框语言统一到 iOS 原语）。
 *
 * ## 这个文件为什么只剩一个 composable（`SwipeMoreSheetContent` 已删）
 *
 * 第 34 轮拆分时，这里还带着一个 `SwipeMoreSheetContent`（「更多」面板的行清单）。
 * 但 pager 后来改成了**直接构造 `List<IOSActionSheetAction>`**：
 * `IOSActionSheet` 的唯一入口就是 `actions: List<IOSActionSheetAction>`，
 * **没有 content 变体**，所以那个"行清单 composable"接不进去。
 *
 * 于是它变成**零调用点的死代码**（全仓只有定义、没有一处 `SwipeMoreSheetContent(` 调用）。
 * 本轮把它连同它的私有 `SwipeMoreRow` 一起删掉 —— 理由不是"代码短"，
 * 而是**守卫必须钉在真实调用点上**：`VideoLogicGuardTest` 里那两条断言
 * （行文案 + `if (canUndoDislike)` 门控）原本钉在这个死组件上，
 * 等于在守一份永远不会被渲染的代码；删掉后它们改钉 pager 里的内联动作清单。
 *
 * ## SW-24 的界面构成（为什么只有这几项）
 * 不感兴趣（+ 撤回）/ 弹幕开关 / 复制链接 / 系统分享 / 拉黑该 UP 主。
 * 举报无接口、稍后再看仓库只读（`WatchLaterRepository` 仅 fetchPage）、
 * 下载在详情页已有完整流程（会话内不新造第二条入口）、画质在播放设置侧栏
 * ——都不在这里做假按钮。每一项都直连既有能力。
 *
 * ## 第 34 轮：三套对话框语言收敛为**一套**
 * 重构前本会话同时用了三套：
 * - `ModalBottomSheet`（收藏面板 + 更多面板）；
 * - `AlertDialog` + `TextButton`（拉黑二次确认）—— **Material 方角对话框**；
 * - 以及 Material3 默认主题。
 *
 * 用户档位是 `ui_style=IOS`，于是会话里"收藏面板是 iOS 观感、拉黑确认是 Material 方角"
 * 两套并列 —— 这正是用户说的"风格没统一"在弹层上的表现。
 * 现在：**动作清单一律走 `IOSActionSheet`**（在 pager 的 `SessionChromeState` 里），
 * **确认框一律走 [IOSAlertDialog]**（本文件）。两者都是 designsystem 既有原语，
 * 本文件只调用、不新增、不修改组件。
 *
 * ## 收藏面板为什么仍是 `ModalBottomSheet`（不是漏改）
 * 它是**多选表单**（选文件夹 + 新建文件夹 + 提交），而 `IOSActionSheet` 只能表达
 * "一组动作"。D8 要统一的是**确认框与动作清单**，不是所有弹层。见 `SwipeFavSheet` 的 KDoc。
 */

/**
 * PT-06：拉黑 UP 的**二次确认**（拉黑对账号实际生效，必须显式确认）。
 *
 * 2026-10-19 第 34 轮：从 Material `AlertDialog` + `TextButton`
 * 改为 designsystem 的 [IOSAlertDialog] —— 用户档位是 `ui_style=IOS`，
 * 确认框必须与全站同一套观感（也顺带消掉了本文件对 `TextButton` 的依赖）。
 *
 * 文案保持原样（"拉黑后其投稿将不再推荐给你，且该操作会对你的 B 站账号实际生效"),
 * 因为它描述的是**真实后果**，不是风格问题。
 */
@Composable
internal fun SwipeBlockUpConfirmDialog(
    upName: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    IOSAlertDialog(
        onDismiss = onDismiss,
        title = "拉黑该 UP 主？",
        message = buildString {
            append("拉黑后其投稿将不再推荐给你，且该操作会对你的 B 站账号实际生效（可随时在网页端撤销）。")
            upName?.takeIf(String::isNotBlank)?.let { append("\n\n目标：@").append(it) }
        },
        confirmLabel = "确认拉黑",
        onConfirm = onConfirm,
        // 拉黑对账号实际生效 → 破坏性动作色（systemRed），与 HIG 一致
        destructive = true
    )
}
