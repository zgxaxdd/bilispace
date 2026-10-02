package com.naaammme.bbspace.core.swipe

/**
 * 会话内 **back（含系统侧滑返回）优先级**的纯判据。
 *
 * 背景（用户真机反馈 2026-10-02）：「点开评论区看完后顺手侧滑返回，结果直接退出了
 * 刷视频模式、回到推荐页」——评论卡是自定义浮层、没接管 back，于是侧滑被宿主的
 * `handleBack` 接走（story 模式 = 关闭整页）。两条正确行为：
 *
 * 1. **面板开着 → 只关面板**（不退出会话）——侧滑的语义就是"退一层"；
 * 2. **面板刚关（[SHEET_CLOSE_GRACE_MS] 窗口内）→ 吞掉这一次**：用户的习惯动作是
 *    "点卡外关闭 + 立刻侧滑"，这一次不该把整个会话带走；窗口过后再滑仍会正常退出
 *    （不改变"显式返回 = 退出会话"的既有语义）。
 */
object SwipeBackPolicy {

    /** 关面板之后的 back 宽限窗口：这段时间内的 back 视为"顺手滑"而吞掉。 */
    const val SHEET_CLOSE_GRACE_MS = 400L

    enum class Action {
        /** 有面板开着：只关面板。 */
        CloseSheet,

        /** 刚关过面板：吞掉这一次 back（不退出会话）。 */
        Swallow,

        /** 正常放行（交给宿主：story 退出 / 全屏回缩 / 详情返回）。 */
        PassThrough,
    }

    /** 优先级：面板开着 > 宽限窗口 > 放行。 */
    fun resolve(anySheetOpen: Boolean, inSheetCloseGrace: Boolean): Action = when {
        anySheetOpen -> Action.CloseSheet
        inSheetCloseGrace -> Action.Swallow
        else -> Action.PassThrough
    }
}
