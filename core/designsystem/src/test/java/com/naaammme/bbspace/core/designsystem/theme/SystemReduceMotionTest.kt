package com.naaammme.bbspace.core.designsystem.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * 系统级「移除动效」接线的验收。
 *
 * ## 背景（这是一次真实的无障碍修复）
 *
 * 移植前：`reduceMotion` **只**由 App 内的「动效速度」设置决定
 * （`createAnimations(speed)` 里 `m == 0f`）。系统设置里的「移除动画」
 * （`ANIMATOR_DURATION_SCALE == 0`）**只有 `ForegroundEnterBox` 一处**读了，
 * 于是用户在系统里关掉动画后，粒子消散、骨架呼吸、转场照旧播。
 *
 * 修复：逐字移植 BiliPai 的 `ReduceMotion.kt`（→ [SystemReduceMotion]），
 * 并在 [createAnimations] 里与 App 设置**取或**。
 */
class SystemReduceMotionTest {

    @Test
    fun systemReduceMotionForcesTheFlagOn() {
        val anim = createAnimations(AnimationSpeed.NORMAL, systemReduceMotion = true)
        assertTrue("系统要求移除动效时 reduceMotion 必须为真", anim.reduceMotion)
    }

    @Test
    fun appSettingStillWorksOnItsOwn() {
        assertTrue(
            "App 内设为 OFF 时依然降级（设置入口没被这次改动弄丢）",
            createAnimations(AnimationSpeed.OFF, systemReduceMotion = false).reduceMotion
        )
        assertFalse(
            "两处都没要求时不许降级",
            createAnimations(AnimationSpeed.NORMAL, systemReduceMotion = false).reduceMotion
        )
    }

    /**
     * **只动标志，不动时长。**
     *
     * 时长口径仍由 App 设置单独决定 —— 若这里改了时长，等于一次改动同时改变
     * 所有动画的节奏（那是另一个话题，会牵动一批既有观感断言）。
     */
    @Test
    fun durationsAreNotAffectedByTheSystemFlag() {
        val normal = createAnimations(AnimationSpeed.NORMAL, systemReduceMotion = false)
        val reduced = createAnimations(AnimationSpeed.NORMAL, systemReduceMotion = true)
        assertEquals("short", normal.short, reduced.short)
        assertEquals("medium", normal.medium, reduced.medium)
        assertEquals("long", normal.long, reduced.long)
        assertEquals("extraLong", normal.extraLong, reduced.extraLong)
        assertEquals("morph", normal.morph, reduced.morph)
    }

    /** 默认参数必须存在：既有的 `createAnimations(speed)` 调用点不许被这次改动破坏。 */
    @Test
    fun defaultParameterKeepsOldCallSitesWorking() {
        val anim = createAnimations(AnimationSpeed.NORMAL)
        assertFalse(anim.reduceMotion)
        assertEquals(200, anim.medium)
    }

    /**
     * 接线守卫：`ProvideAnimations` 必须真的把系统值读进来并传下去。
     *
     * 只测 `createAnimations` 挡不住"移植了组件但忘了接" —— 那正是这次要修的 bug 形态。
     */
    @Test
    fun provideAnimationsActuallyWiresTheSystemValue() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val animationFile = File(
            root,
            "core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/theme/Animation.kt"
        )
        assumeTrue("Animation.kt 不在预期路径，跳过源码守卫", animationFile.isFile)
        val source = animationFile.readText()
        assertTrue(
            "ProvideAnimations 必须调用 rememberSystemReduceMotion()",
            source.contains("rememberSystemReduceMotion()")
        )
        assertTrue(
            "必须把系统值传给 createAnimations（否则读了也不用）",
            source.contains("createAnimations(speed, systemReduceMotion)")
        )
        val ported = File(
            root,
            "core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/theme/SystemReduceMotion.kt"
        )
        assertTrue("移植件必须存在", ported.isFile)
        val portedSource = ported.readText()
        assertTrue(
            "移植件必须读 ANIMATOR_DURATION_SCALE",
            portedSource.contains("Settings.Global.ANIMATOR_DURATION_SCALE")
        )
        assertTrue(
            "必须用 ContentObserver 实时跟随（源实现如此）",
            portedSource.contains("ContentObserver")
        )
    }

    /**
     * **粒子消散必须消费 `reduceMotion`**（第 9 轮补的缺口）。
     *
     * 源的 `ParticleDissolveEffect.kt` **没有**检查 reduceMotion —— 用户在系统里
     * 打开「移除动画」后粒子照旧播。本仓把系统开关接进 `LocalAnimations.reduceMotion`
     * 之后，消散如果不读它就等于那处接线对消散无效。
     *
     * 这条是**有意偏离源实现**的一处，所以必须有守卫：改回去（或漏掉）就红。
     */
    @Test
    fun dissolveActuallyHonoursReduceMotion() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val dissolve = File(
            root,
            "core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/motion/DissolvableSurface.kt"
        )
        assumeTrue("DissolvableSurface.kt 不在预期路径，跳过源码守卫", dissolve.isFile)
        val src = dissolve.readText()
        assertTrue(
            "消散必须在组合期读 LocalAnimations.current.reduceMotion",
            src.contains("LocalAnimations.current.reduceMotion")
        )
        assertTrue(
            "读到之后必须真的用它提前退出（读而不用 = 没修）",
            src.contains("if (reduceMotion) {")
        )
        assertTrue(
            "降级时必须直接交付完成路径（不能什么都不做，否则删除动作永远不执行）",
            src.contains("dispatchCompletionOnce()")
        )
        assertTrue(
            "必须写明这是有意偏离源实现（否则后人会当成 bug 改回去）",
            src.contains("有意偏离源实现")
        )
    }
}
