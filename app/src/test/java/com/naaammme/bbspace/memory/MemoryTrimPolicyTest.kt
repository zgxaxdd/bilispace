package com.naaammme.bbspace.memory

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [planTrimMemory] 的编排表测试（任务书 §7 第 8 条：把 onTrimMemory 分级抽成纯函数）。
 *
 * 这些断言保护的是一条**没有设备就看不见**的行为差异：分派错一档既不会崩、也不会
 * 进日志，只会让用户在"切后台再回来"时看到整屏封面重刷，或者让 App 在该放内存时
 * 没放而被系统杀。以前要验证它只能 `adb shell am send-trim-memory`，现在是一张表。
 */
class MemoryTrimPolicyTest {

    /**
     * 最重要的一条：`UI_HIDDEN` **绝不**清空。
     *
     * 按 Home 就会收到这一档。在这里 `clear()` 的代价是回前台后当前列表所有可见封面
     * 重新走磁盘解码 —— 正是当初那个"切后台再回来封面集体重刷"的现象。
     * 任何人想把它改回 clear，必须先把这条测试改掉并说明理由。
     */
    @Test
    fun uiHiddenTrimsInsteadOfClearing() {
        val plan = planTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, uiVisible = false)
        assertEquals(ImageCacheAction.Trim, plan.imageCache)
        assertEquals(CoverColorAction.TrimToVisibleWindow, plan.coverColors)
        assertEquals("UI_HIDDEN", plan.reason)
    }

    /** 真正"在后台且吃紧"的三档才清空。 */
    @Test
    fun backgroundTiersClear() {
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        )
        ) {
            val plan = planTrimMemory(level, uiVisible = false)
            assertEquals("level=$level 应清空图片内存缓存", ImageCacheAction.Clear, plan.imageCache)
            assertEquals("level=$level 应一并收口封面色缓存", CoverColorAction.TrimToHistory, plan.coverColors)
        }
    }

    /**
     * 前台但吃紧（RUNNING_LOW / RUNNING_CRITICAL）：**界面可见时不动**。
     *
     * 这是全表里唯一依赖 `uiVisible` 的一支，也是它存在的理由 ——
     * 可见时清了下一帧就被重新填满，纯亏一次解码。
     */
    @Test
    fun runningLowOnlyActsWhenUiHidden() {
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        )
        ) {
            val visible = planTrimMemory(level, uiVisible = true)
            assertEquals("level=$level 界面可见时不该动缓存", ImageCacheAction.Nothing, visible.imageCache)
            assertEquals(CoverColorAction.Keep, visible.coverColors)

            val hidden = planTrimMemory(level, uiVisible = false)
            assertEquals("level=$level 界面不可见时应清", ImageCacheAction.Clear, hidden.imageCache)
        }
    }

    /** 未知 level 一律不动 —— 宁可漏放，不可误清。 */
    @Test
    fun unknownLevelsDoNothing() {
        for (level in listOf(-1, 0, 999_999, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)) {
            val plan = planTrimMemory(level, uiVisible = true)
            assertEquals("level=$level", ImageCacheAction.Nothing, plan.imageCache)
            assertEquals("level=$level", CoverColorAction.Keep, plan.coverColors)
        }
    }

    /**
     * 每个分支都要有可区分的 reason。
     *
     * 真机查内存问题时唯一能 grep 的就是这个字符串，所以它属于策略而不是日志细节：
     * 两个语义不同的分支共用一个 reason，等于把"为什么回前台重刷了"重新变成查不到的事。
     */
    @Test
    fun everyBranchHasDistinctiveReason() {
        val reasons = listOf(
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN to false,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND to false,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW to true,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW to false,
            12345 to true
        ).map { (level, visible) -> planTrimMemory(level, visible).reason }

        assertTrue("reason 不能为空", reasons.none { it.isBlank() })
        assertEquals(
            "不同语义的分支必须给出可区分的 reason（用于 logcat 定位），实际：$reasons",
            reasons.distinct().size,
            reasons.size
        )
    }

    /** trim 比例必须在 (0,1) 开区间：1f 等于不 trim，0f 等于清空。 */
    @Test
    fun trimRatioIsActuallyAGradient() {
        assertTrue("TRIM_TO_RATIO=$TRIM_TO_RATIO", TRIM_TO_RATIO > 0f && TRIM_TO_RATIO < 1f)
        assertEquals(0.5f, TRIM_TO_RATIO, 0f)
        assertTrue(
            "可视窗口档必须比历史档留得多，否则分级失去意义",
            COVER_COLOR_VISIBLE_WINDOW > COVER_COLOR_HISTORY
        )
    }

    /**
     * onLowMemory 与"后台吃紧"同档。
     *
     * 这条测试同时钉住 ComponentCallbacks2 常量本身的取值：Kotlin 会把 Java 的
     * `static final int` 在编译期内联进单测字节码，所以若某个常量在旧 SDK 上取值
     * 与这里写死的不同，会由下面这条**显式失败**，而不是让整张表静默塌成 level=0。
     */
    @Test
    fun lowMemoryMatchesBackgroundTierAndConstantsAreWhatWeThink() {
        assertEquals(ImageCacheAction.Clear, planLowMemory().imageCache)
        assertEquals(planLowMemory().imageCache, planTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, false).imageCache)

        assertEquals(20, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(40, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertEquals(60, ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        assertEquals(80, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(5, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(10, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(15, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }
}
