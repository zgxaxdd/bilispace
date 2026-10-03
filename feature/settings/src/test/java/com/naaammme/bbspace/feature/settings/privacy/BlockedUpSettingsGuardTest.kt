package com.naaammme.bbspace.feature.settings.privacy

import com.naaammme.bbspace.core.settings.blocked.BlockedUpEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NX-08 的守卫：拉黑名单页（查看 / 撤销）。
 *
 * ## 为什么大部分是结构断言
 * 撤销要出网（gRPC），页面是 Compose；JVM 上端到端跑需要整套 fake。真正要守住的不变式
 * 是**写法层面**的：
 * ① 撤销走的是**同一条既有 gRPC**（`UpBlocker` → `SpaceRepository.modifyBlacklist`），
 *    不许另造 REST（PT-06 的 X1 结论）；
 * ② 撤销**先本地生效再同步**，失败不回滚；
 * ③ 三态齐全（`UI-04`：错误 > 空 > 加载）——"读不出来"不能被显示成"名单为空"；
 * ④ 搜索索引里的「拉黑名单」必须**真的能导航**（否则就是一个死入口，比没有还差）。
 *
 * 防假绿（照抄 `VideoLogicGuardTest` 两条）：先确认文件读到；扫描前过滤注释与 import 行
 * （本仓注释里必然出现 `modifyBlacklist`、`upBlocker` 这些词）。
 */
class BlockedUpSettingsGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    private fun codeOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val matches = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${FS}src${FS}main${FS}") }
            .filterNot { it.path.contains("${FS}build${FS}") }
            .filter { it.path.endsWith(normalized) }
            .toList()
        // **后缀必须唯一**：`SettingsScreen.kt` 会同时命中 Appearance/Feed/Performance/AudioVideo
        // 等一串 *SettingsScreen.kt —— 用 `firstOrNull` 会静默断言到错的文件上（本测试
        // 第一版就这么红过一次）。多命中一律当错误抛出。
        assertTrue(
            "后缀 `$suffix` 命中 ${matches.size} 个文件（要求恰好 1 个）：" +
                matches.joinToString { it.name } + " —— 请改用更长的路径后缀。",
            matches.size == 1
        )
        return matches.single().readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("import ")
            ) {
                ""
            } else {
                line
            }
        }
    }

    private fun scannerReadsSources() {
        assertTrue(
            "privacy 目录扫不到源码，断言无意义",
            codeOf("privacy${FS}BlockedUpViewModel.kt").size > 50
        )
    }

    @Test
    fun undoGoesThroughTheExistingGrpcDelegate() {
        scannerReadsSources()
        val vm = codeOf("privacy${FS}BlockedUpViewModel.kt")
        assertTrue(
            "撤销必须调 `upBlocker.modifyBlacklist(..., isBlack = false)`" +
                "（PT-06 X1：复用既有 gRPC，不移植上游 REST）。",
            vm.any { it.contains("upBlocker.modifyBlacklist(entry.mid, isBlack = false)") }
        )
        assertTrue(
            "撤销不许出现 REST 拉黑字面量（x/relation/modify）。",
            vm.none { it.contains("x/relation/modify") }
        )
        assertTrue(
            "依赖必须是 core:common 的 UpBlocker 接口（不加 feature→core:space 依赖边）——" +
                "写的是 `upBlocker: UpBlocker` 形参，而不是 import 行（import 行会被注释过滤器清掉）。",
            vm.any { it.contains("upBlocker: UpBlocker") }
        )
    }

    @Test
    fun undoIsLocalFirstAndDoesNotRollBackOnFailure() {
        scannerReadsSources()
        val vm = codeOf("privacy${FS}BlockedUpViewModel.kt")
        val removeAt = vm.indexOfFirst { it.contains("store.remove(entry.mid)") }
        val grpcAt = vm.indexOfFirst { it.contains("upBlocker.modifyBlacklist(") }
        assertTrue(
            "必须**先本地移除**再发 gRPC（本地先生效）remove@$removeAt grpc@$grpcAt",
            removeAt in 0 until grpcAt
        )
        assertTrue(
            "失败分支必须如实提示「本地已移除、服务端失败」，且**不得**把条目加回本地" +
                "（回滚会让用户以为撤销没生效）。",
            vm.any { it.contains("已从本地名单移除，但服务端解除失败") } &&
                vm.none { it.contains("store.add(") }
        )
    }

    @Test
    fun screenHasThreeStatesInUi04Order() {
        scannerReadsSources()
        val screen = codeOf("privacy${FS}BlockedUpScreen.kt")
        assertTrue(
            "三态必须用统一出口 StateMessageCard（UI-04 规范），不许自造卡片。",
            screen.count { it.contains("StateMessageCard(") } >= 2
        )
        assertTrue(
            "错误态必须 isError = true（与空态可区分）。",
            screen.any { it.contains("isError = true") }
        )
        assertTrue(
            "空态必须说明「怎么才会有条目」，而不是一个空白页。",
            screen.any { it.contains("名单为空") } && screen.any { it.contains("更多") }
        )
        // 优先级：错误分支必须写在空/加载分支之前（错误 > 空 > 加载）
        val errorAt = screen.indexOfFirst { it.contains("state.errorMessage != null ->") }
        val emptyAt = screen.indexOfFirst { it.contains("state.entries.isEmpty() ->") }
        val loadingAt = screen.indexOfFirst { it.contains("state.isLoading ->") }
        assertTrue(
            "三态顺序必须是 错误 → 加载 → 空/列表（UI-04：错误 > 空 > 加载）" +
                "error@$errorAt loading@$loadingAt empty@$emptyAt",
            errorAt in 0 until loadingAt && loadingAt < emptyAt
        )
    }

    @Test
    fun listRowShowsNameTimeAndSyncState() {
        scannerReadsSources()
        val screen = codeOf("privacy${FS}BlockedUpScreen.kt")
        assertTrue(
            "列表项必须显示 UP 名（显示名兜底在 VM 侧）+ 拉黑时间 + 同步状态。",
            screen.any { it.contains("blockedUpDisplayName(entry)") } &&
                screen.any { it.contains("formatBlockedAt(entry.blockedAtMs)") } &&
                screen.any { it.contains("blockedUpSyncLabel(entry)") }
        )
        assertTrue(
            "撤销按钮必须逐条挂在条目上（onUnblock = { viewModel.unblock(entry) }）。",
            screen.any { it.contains("onUnblock = { viewModel.unblock(entry) }") }
        )
    }

    /**
     * 搜索索引里新增的「拉黑名单」必须真的能导航。
     *
     * 这正是本项**实现过程中差点留下**的坑：`SettingEntries` 加了可搜索条目，
     * 但 `SettingsScreen` 的 `routeNav` 里没有该路由 → 搜索点进去毫无反应（死入口）。
     */
    @Test
    fun searchEntryIsReachableFromSettingsScreen() {
        val settings = codeOf("settings${FS}SettingsScreen.kt")
        assertTrue(
            "SettingsScreen 的 routeNav 必须包含 BLOCKED_UP_ROUTE，否则搜索结果是死入口。",
            settings.any { it.contains("BLOCKED_UP_ROUTE to onNavigateToBlockedUp") }
        )
        val entries = codeOf("settings${FS}SettingEntries.kt")
        assertTrue(
            "隐私分区必须有「拉黑名单」的可搜索条目。",
            entries.any { it.contains("SettingEntry(\"拉黑名单\"") }
        )
        assertTrue(
            "搜索索引与 routeNav 必须指向同一个路由常量。",
            entries.any { it.contains("BLOCKED_UP_ROUTE)") }
        )
    }

    // ===== 纯函数（显示名/时间/同步态）=====

    @Test
    fun displayNameFallsBackToMid() {
        assertEquals("某UP", blockedUpDisplayName(entry(name = "某UP")))
        assertEquals("UP 42", blockedUpDisplayName(entry(name = null)))
        assertEquals("UP 42", blockedUpDisplayName(entry(name = "   ")))
    }

    @Test
    fun syncLabelDistinguishesLocalOnly() {
        assertEquals("已同步", blockedUpSyncLabel(entry(synced = true)))
        assertEquals("仅本地（未同步）", blockedUpSyncLabel(entry(synced = false)))
    }

    @Test
    fun blockedAtFormatIsStable() {
        // 固定瞬时 → 固定文本（Locale.ROOT，与 WebFeedMapper 同一条理由）
        val text = formatBlockedAt(0L)
        assertTrue("必须含日期与时间两段：$text", text.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")))
        assertFalse(text.contains("年"))
    }

    private fun entry(
        name: String? = "某UP",
        synced: Boolean = false
    ) = BlockedUpEntry(mid = 42L, name = name, blockedAtMs = 1_700_000_000_000L, synced = synced)
}
