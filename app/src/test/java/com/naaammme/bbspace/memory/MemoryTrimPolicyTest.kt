package com.naaammme.bbspace.memory

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /**
     * 无缝动画的软件位图 LRU（4×960×540 ARGB_8888 ≈ 8MB）也必须跟着同一张分级表走。
     *
     * 它此前**不在任何一档里**：`applyTrimPlan` 只清 Coil 内存缓存与封面色缓存，
     * 而这块位图挂在 `AppNavHost` 创建的 opener 上，`onTrimMemory` / `onLowMemory`
     * 都碰不到 —— 内存紧张时 8MB 照留不误。本测试钉住三件事：
     * UI_HIDDEN 只留最近 1 条（温和档）、后台/系统吃紧档全清、未知档不动。
     */
    @Test
    fun seamlessCoverCacheFollowsTheSameTiering() {
        assertEquals(
            "UI_HIDDEN 应温和裁剪（留最近 1 条）",
            SeamlessCoverAction.TrimToRecent,
            planTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, uiVisible = false).seamlessCovers
        )
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        )) {
            assertEquals(
                "level=$level 应清空无缝位图缓存",
                SeamlessCoverAction.Clear,
                planTrimMemory(level, uiVisible = false).seamlessCovers
            )
        }
        assertEquals(
            "界面可见的 RUNNING_* 不该动",
            SeamlessCoverAction.Keep,
            planTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, uiVisible = true).seamlessCovers
        )
        assertEquals(
            "界面不可见的 RUNNING_* 应清",
            SeamlessCoverAction.Clear,
            planTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, uiVisible = false).seamlessCovers
        )
        assertEquals(
            "未知 level 不动",
            SeamlessCoverAction.Keep,
            planTrimMemory(-1, uiVisible = true).seamlessCovers
        )
        assertEquals(
            "onLowMemory 与后台吃紧同档",
            SeamlessCoverAction.Clear,
            planLowMemory().seamlessCovers
        )
    }

    /**
     * AP-04 补登记的三个进程级自建缓存（详情 / 播放地址 / DNS）跟同一张分级表走。
     *
     * 分级理由（与 `NetCacheAction` KDoc 一一对应）：
     * - UI_HIDDEN → **Keep**：界面刚不可见 ≠ 吃紧。详情重建是完整网络往返、
     *   播放地址有 90s TTL 自愈、DNS 量小而价值在时延（后台播放重连仍受益）；
     * - BACKGROUND / 不可见的 RUNNING_* / onLowMemory → **Clear**：后台且吃紧，
     *   三者重建都可接受；
     * - 可见的 RUNNING_* / 未知档 → Keep（宁可漏放，不可误清）。
     */
    @Test
    fun netCachesFollowTheSameTiering() {
        assertEquals(
            "UI_HIDDEN 不许清详情/播放地址/DNS 缓存（分级理由见 NetCacheAction KDoc）",
            NetCacheAction.Keep,
            planTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, uiVisible = false).netCaches
        )
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        )) {
            assertEquals(
                "level=$level 应清空补登记的三个进程级缓存",
                NetCacheAction.Clear,
                planTrimMemory(level, uiVisible = false).netCaches
            )
        }
        assertEquals(
            "界面可见的 RUNNING_* 不该动",
            NetCacheAction.Keep,
            planTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, uiVisible = true).netCaches
        )
        assertEquals(
            "界面不可见的 RUNNING_* 应清",
            NetCacheAction.Clear,
            planTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, uiVisible = false).netCaches
        )
        assertEquals(
            "未知 level 不动",
            NetCacheAction.Keep,
            planTrimMemory(-1, uiVisible = true).netCaches
        )
        assertEquals(
            "onLowMemory 与后台吃紧同档",
            NetCacheAction.Clear,
            planLowMemory().netCaches
        )
    }

    // ── 图片内存缓存的绝对预算（2026-10-01，对照 BiliPai `8cfc4246b`）──────────────

    @Test
    fun trimTargetIsRatioAndAbsoluteBudgetWhicheverIsSmaller() {
        val mb = 1024L * 1024L
        // 小容量设备：比例(0.5×32MB=16MB) 比绝对预算(24MB) 更狠 → 走比例，行为与旧实现一致
        assertEquals(
            "派生容量本来就小时应保持'留一半'的旧语义",
            16L * mb,
            imageCacheTrimTargetBytes(maxSizeBytes = 32L * mb, currentSizeBytes = 30L * mb)
        )
        // 大堆设备：percent(0.25) 派生出 128MB，一半是 64MB —— 那根本不是"给系统让路"
        assertEquals(
            "大派生容量必须被绝对预算夹住",
            24L * mb,
            imageCacheTrimTargetBytes(maxSizeBytes = 128L * mb, currentSizeBytes = 120L * mb)
        )
        assertEquals(BACKGROUND_IMAGE_BUDGET_BYTES, 24L * mb)
    }

    @Test
    fun alreadyUnderBudgetMeansNoOp() {
        val mb = 1024L * 1024L
        // 已经在目标之内 → null = 什么都不做（原实现的早退，避免"每次切后台都 trim 一次"）
        assertNull(
            imageCacheTrimTargetBytes(maxSizeBytes = 48L * mb, currentSizeBytes = 20L * mb)
        )
        assertNull(
            "恰好等于目标也不许动",
            imageCacheTrimTargetBytes(maxSizeBytes = 48L * mb, currentSizeBytes = 24L * mb)
        )
        // 刚好超一字节也要动（边界不许含糊）
        assertEquals(24L * mb, imageCacheTrimTargetBytes(48L * mb, 24L * mb + 1))
    }

    @Test
    fun trimTargetNeverExceedsCapacityOrGoesBelowOne() {
        val mb = 1024L * 1024L
        // 容量小于预算：走比例那一档（2MB 容量 → 留 1MB），且永远不超过容量
        assertEquals(1L * mb, imageCacheTrimTargetBytes(2L * mb, 2L * mb))
        // 退化输入（0 容量 / 负数）不许算出 0 或负目标，也不许抛
        assertEquals(1L, imageCacheTrimTargetBytes(0L, 10L))
        assertEquals(1L, imageCacheTrimTargetBytes(-5L, 10L))
        // 比例被夹进 0..1：传 2f 也不许算出比容量更大的目标
        assertEquals(
            "ratio>1 必须被夹回 1（目标永远不超过容量）",
            2L,
            imageCacheTrimTargetBytes(2L, 3L, ratio = 2f)
        )
    }
}
