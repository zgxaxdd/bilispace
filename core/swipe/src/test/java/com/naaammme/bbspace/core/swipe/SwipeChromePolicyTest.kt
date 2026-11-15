package com.naaammme.bbspace.core.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SwipeChromePolicy] 的纯逻辑单测（第 35 轮 P0-1 / P0-3 / P0-4）。
 *
 * 逐条对应任务书验收判据：
 * - P0-1「竖屏任意时刻最多一套顶栏、一个 ⋮」⇒ [storyAndPlayerAreMutuallyExclusive]；
 * - P0-3「横屏后无需点屏幕就能看到还原入口」⇒ [landscapeStartsClearedAndKeepsAnExit]；
 * - P0-4「横屏是播放器模式」⇒ [autoHideFallsBackToStoryOnlyInPortrait]。
 */
class SwipeChromePolicyTest {

    @Test
    fun portraitSessionStartsOnStoryChrome() {
        assertEquals(
            "竖屏会话刚开始必须是会话层（底部信息 + 互动栏）—— 从播放器层起会让信息层一进页面就消失",
            SwipeChromeOwner.Story,
            SwipeChromePolicy.initial(landscapeForced = false)
        )
        assertEquals(
            "横屏会话刚开始是清屏（P0-4：横屏 = 播放器模式，不摆竖屏那套）",
            SwipeChromeOwner.None,
            SwipeChromePolicy.initial(landscapeForced = true)
        )
    }

    @Test
    fun storyAndPlayerAreMutuallyExclusive() {
        // 全枚举遍历：这是 P0-1 的核心不变量，不是抽样
        for (owner in SwipeChromeOwner.entries) {
            val story = SwipeChromePolicy.storyVisible(owner)
            val player = SwipeChromePolicy.playerVisible(owner)
            assertFalse(
                "owner=$owner 同时点亮了两层控制 UI —— 这正是真机「两个 ⋮ / 两套顶栏」的根因" +
                    "（docs/evidence-r35/27-tap-once.png）",
                story && player
            )
            assertTrue(
                "owner=$owner 两层都不显示且又不是清屏态：没有状态对应这种组合",
                story || player || owner == SwipeChromeOwner.None
            )
        }
    }

    @Test
    fun tappingScreenTogglesPlayerChrome() {
        assertEquals(
            "会话层点一下 → 唤出播放器层（顶栏 + 控制条）",
            SwipeChromeOwner.Player,
            SwipeChromePolicy.onChromeRequested(wantsPlayerChrome = true, landscapeForced = false)
        )
        assertEquals(
            "播放器层再点一下 → 收起回会话层（抖音同款：再点 = 继续沉浸，不是关掉一切）",
            SwipeChromeOwner.Story,
            SwipeChromePolicy.onChromeRequested(wantsPlayerChrome = false, landscapeForced = false)
        )
        assertEquals(
            "清屏态点一下 → 先给播放器层（横屏里没有别的入口能转回竖屏）",
            SwipeChromeOwner.Player,
            SwipeChromePolicy.onChromeRequested(wantsPlayerChrome = true, landscapeForced = true)
        )
    }

    @Test
    fun chromeRequestIsInvolutionInPortrait() {
        // 点击在竖屏是**对合**：连点两次必须回到原状态，否则"点两下回不去"会被读成卡死
        for (start in listOf(SwipeChromeOwner.Story, SwipeChromeOwner.Player)) {
            val once = SwipeChromePolicy.onChromeRequested(start != SwipeChromeOwner.Player, false)
            val twice = SwipeChromePolicy.onChromeRequested(once != SwipeChromeOwner.Player, false)
            assertEquals("owner=$start 连点两次必须回到原状态", start, twice)
        }
    }

    @Test
    fun autoHideFallsBackToStoryOnlyInPortrait() {
        assertEquals(
            "竖屏自动隐藏只收起**播放器层**，会话层必须回来 —— 两层一起消失就是用户说的" +
                "「控件消失再出现」（SwipeChromeBurnIn 的既定口径）",
            SwipeChromeOwner.Story,
            SwipeChromePolicy.autoHideTarget(landscapeForced = false)
        )
        assertEquals(
            "横屏自动隐藏 = 清屏（2026-10-04 用户定稿：横屏观影不显示任何东西）",
            SwipeChromeOwner.None,
            SwipeChromePolicy.autoHideTarget(landscapeForced = true)
        )
        assertFalse(
            "自动隐藏绝不能落回播放器层（那就是「点了没反应/收不起来」）",
            SwipeChromePolicy.playerVisible(SwipeChromePolicy.autoHideTarget(false)) ||
                SwipeChromePolicy.playerVisible(SwipeChromePolicy.autoHideTarget(true))
        )
    }

    @Test
    fun landscapeStartsClearedAndKeepsAnExit() {
        // P0-3：进横屏 = 清屏，但**不能出现"没有任何退出入口"**
        val afterRotate = SwipeChromePolicy.onOrientationChanged(landscapeForced = true)
        assertEquals("进横屏必须清屏（P0-4）", SwipeChromeOwner.None, afterRotate)
        val afterRequest = SwipeChromePolicy.onChromeRequested(
            wantsPlayerChrome = true,
            landscapeForced = true
        )
        assertEquals(
            "横屏清屏态要控制层必须能拿到播放器层 —— 那颗「退出横屏」圆钮就在它的顶栏里",
            SwipeChromeOwner.Player,
            afterRequest
        )
        assertTrue(
            "播放器层必须真的可见（否则「退出横屏」无处可点）",
            SwipeChromePolicy.playerVisible(afterRequest)
        )
        assertEquals(
            "退横屏后回到竖屏会话层（信息 / 互动栏恢复）",
            SwipeChromeOwner.Story,
            SwipeChromePolicy.onOrientationChanged(landscapeForced = false)
        )
        assertTrue(
            "P0-3 的验收窗口必须是秒级（真机：转屏后 3 秒内可发现），当前 " +
                "${SwipeChromePolicy.LANDSCAPE_EXIT_HINT_MS}ms",
            SwipeChromePolicy.LANDSCAPE_EXIT_HINT_MS in 1_000L..5_000L
        )
    }

    @Test
    fun everyOwnerIsReachableFromThePortraitStart() {
        // 反向可达性：不能有"进得去出不来"的状态
        val reachable = mutableSetOf(SwipeChromePolicy.initial(landscapeForced = false))
        var frontier = reachable.toList()
        repeat(3) {
            frontier = frontier.flatMap { owner ->
                listOf(
                    SwipeChromePolicy.onChromeRequested(owner != SwipeChromeOwner.Player, false),
                    SwipeChromePolicy.onChromeRequested(owner != SwipeChromeOwner.Player, true)
                )
            }.distinct()
            reachable += frontier
        }
        for (owner in SwipeChromeOwner.entries) {
            assertTrue("owner=$owner 从竖屏起点出发不可达 —— 迁移函数漏了一条边", owner in reachable)
        }
    }
}
