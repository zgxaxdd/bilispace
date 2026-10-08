package com.naaammme.bbspace.core.designsystem.motion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 粒子消散的**架构守卫**（移植自 BiliPai `DissolveRenderPolicyTest.kt` 的同类断言）。
 *
 * ## 它挡的是什么
 *
 * `DissolvableVideoCard` 即使**没有在消散**，也会挂一个 `rememberGraphicsLayer()`
 * 并每帧 `record { drawContent() }` + `drawLayer()` —— 对长列表是纯浪费
 * （BiliPai 的注释：*scrolling video lists mount dissolve tracking only when requested*）。
 * 所以滚动列表里**必须**走 [`MaybeDissolvableVideoCard`]：`isDissolving == false` 时
 * 它直接渲染内容，不挂任何离屏层。
 *
 * 源仓库用同类断言钉住了 3 个列表页；这里按 bbspace 的实际落点逐个钉。
 *
 * ## 为什么要写成源码扫描，而不是运行期断言
 *
 * "空闲时有没有挂离屏层"是**结构**问题（取决于用了哪个 composable），
 * 运行期测不到；而列表页一旦退化成直接调用，只会在真机上多出每帧离屏光栅。
 */
class DissolveRenderPolicyTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** 已接入消散的落点（每新增一处，就把它加进来 —— 守卫随之覆盖）。 */
    private val dissolveCallSites = listOf(
        "app/src/main/java/com/naaammme/bbspace/playback/capsule/CapsuleIndicator.kt",
        "feature/search/src/main/java/com/naaammme/bbspace/feature/search/history/SearchHistoryPanel.kt",
        "feature/settings/src/main/java/com/naaammme/bbspace/feature/settings/privacy/BlockedUpScreen.kt",
        "feature/download/src/main/java/com/naaammme/bbspace/feature/download/DownloadScreen.kt",
    )

    @Test
    fun callSitesExistAndUseTheLazyFriendlyWrapper() {
        assertTrue("落点表不能是空的（防假绿）", dissolveCallSites.size >= 3)
        val offenders = mutableListOf<String>()
        for (site in dissolveCallSites) {
            val file = File(repoRoot, site)
            assertTrue("落点文件不存在（表过期了？）：$site", file.isFile)
            val source = file.readText()
            if (!source.contains("MaybeDissolvableVideoCard(")) {
                offenders += "$site 没有使用 MaybeDissolvableVideoCard"
            }
            // 行首直接调用 DissolvableVideoCard（非 Maybe 前缀）就是漏挂了空闲保护
            val direct = Regex("""(?m)^\s*DissolvableVideoCard\(""").containsMatchIn(source)
            if (direct) {
                offenders += "$site 直接调用了 DissolvableVideoCard（空闲时会白挂离屏层）"
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** 消散的宿主必须只挂在**被解散的那一块**上，且状态与列表 key 对齐。 */
    @Test
    fun callSitesDriveTheStateMachineNotTheRemoval() {
        // 真删/真关必须发生在 onDissolveComplete 里（BiliPai 同序：先粒子，后动作）
        val missing = dissolveCallSites.filter { !File(repoRoot, it).readText().contains("onDissolveComplete") }
        assertTrue("以下落点没有把真正的动作放进 onDissolveComplete：$missing", missing.isEmpty())
    }
}
