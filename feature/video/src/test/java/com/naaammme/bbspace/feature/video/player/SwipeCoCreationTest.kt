package com.naaammme.bbspace.feature.video.player

import com.naaammme.bbspace.core.model.VideoStaff
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联合创作判定与文案（PT-08 验收①：1 人/多人/空 + 文案拼接）。
 * 上游语义：`staff.size > 1 && 成员里至少一个名字非空`（PortraitFullscreenOverlay.kt:672 的
 * bbspace 适配版——上游按 `mid != ownerMid` 过滤，bbspace 的 VideoStaff 无 mid）。
 */
class SwipeCoCreationTest {

    private fun staff(name: String, role: String = "出品") = VideoStaff(role = role, name = name)

    @Test
    fun emptyStaffIsNotCoCreation() {
        assertFalse(isCoCreation(emptyList()))
    }

    @Test
    fun singleStaffIsNotCoCreation() {
        assertFalse(isCoCreation(listOf(staff("甲"))))
    }

    @Test
    fun multipleStaffIsCoCreation() {
        assertTrue(isCoCreation(listOf(staff("甲"), staff("乙"))))
    }

    /** 上游兜底条件同源：全空名成员不算有效成员。 */
    @Test
    fun allBlankNamesIsNotCoCreation() {
        assertFalse(isCoCreation(listOf(staff(""), staff("  "))))
    }

    /** 一人有名一人空名：any 命中 → 联合创作（上游 `coCreators.isNotEmpty()` 同义）。 */
    @Test
    fun oneNamedMemberAmongManyCounts() {
        assertTrue(isCoCreation(listOf(staff("甲"), staff(""))))
    }

    @Test
    fun chipTextMatchesUpstreamTemplateVerbatim() {
        assertEquals(
            "等 3 人联合创作 ˅",
            coCreationChipText(listOf(staff("甲"), staff("乙"), staff("丙")))
        )
    }

    /** 弹层成员行过滤：空名成员不进弹层（与 chip 的 any 判据同源兜底）。 */
    @Test
    fun sheetMembersFilterBlankNames() {
        val members = coCreationSheetMembers(listOf(staff("甲"), staff(""), staff("乙")))
        assertEquals(listOf("甲", "乙"), members.map { it.name })
    }

    // ── 结构断言：staffs 必须在渲染点被消费（验收②，W20 锚调用） ─────────────

    @Test
    fun staffsAreConsumedAtTheRenderSite() {
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: File("").absoluteFile
        val pager = File(
            repoRoot,
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/PortraitSwipePager.kt"
        )
        assertTrue("PortraitSwipePager.kt 不见了", pager.isFile)
        val wired = pager.readLines().any {
            !it.trimStart().startsWith("//") && it.contains("staffs = playbackState.detail?.staffs")
        }
        assertTrue(
            "SwipeSessionInfoLine 的调用点必须把 detail.staffs 传进渲染链（PT-08 的接线锚点）",
            wired
        )
        val pane = File(
            repoRoot,
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/player/VideoPlayerPane.kt"
        )
        val chipRendered = pane.readLines().any {
            !it.trimStart().startsWith("//") && it.contains("coCreationChipText(staffs)")
        }
        assertTrue(
            "SwipeSessionInfoLine 内必须有联合创作 chip 的渲染（coCreationChipText）",
            chipRendered
        )
    }
}
