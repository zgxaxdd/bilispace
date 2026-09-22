package com.naaammme.bbspace.core.a11y

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 无障碍结构断言（任务书 T6 的"无设备验收"）。
 *
 * ## 为什么这些能离线验证、哪些不能
 *
 * "某个按钮实际占了多少 dp"是**布局结果**，没有设备/Robolectric 就测不了。
 * 但"某个按钮被显式声明成 32dp"是**源码事实**，可以。所以本类只断言源码事实，
 * 并且刻意分成两种强度：
 * - **硬断言**：与全站既有惯例冲突的写法（见 [iconOnlyButtonsAreAnnounced]），
 *   这类是纯粹的缺陷，不存在取舍。
 * - **棘轮**：已经存在、但**改动会有视觉代价**的小触控目标（见
 *   [subMinimumIconButtonSize_isFrozen]）。把它们钉住只许减，是因为把 32dp 的
 *   播放键放大到 48dp 会**真的改掉悬浮胶囊的布局** —— 没有设备就走查不了观感，
 *   盲改等于用可访问性换一次无法验证的视觉回归。
 *
 * ## 扫描器为什么要按实参边界扫
 *
 * `IconButton` 的内层 `Icon(modifier = Modifier.size(20.dp))` 是**完全正常**的
 * （48dp 触控区 + 20dp 图标），把它算成违规会让棘轮数字虚高、从此没人信它。
 * 区分两者只能靠括号配平：只在 `IconButton(` 的**实参列表内**找 `size(N.dp)`。
 * 这也是本类不直接用单行正则的原因。
 */
class AccessibilityStructureTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: File("").absoluteFile

    private val mainSources: List<File> by lazy {
        File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    /** 防假绿：扫不到源码时，下面所有"没有违规"的结论都不成立。 */
    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 300 个说明路径推导失效，本类断言此刻全在假绿。",
            mainSources.size > 300
        )
    }

    // ── 硬断言：图标按钮必须可被读屏念出 ──────────────────────────────────────

    /**
     * 返回箭头与"更多"菜单触发器的 `contentDescription` 不允许为 null。
     *
     * 制定本断言时全仓有 5 处违反，而**同仓另有 8 处返回箭头都写了 `"返回"`**
     * （BiliTopBar / AccountScreen / SmsLoginScreen / BbSpaceScreen / MsgFeedScreen /
     * ListenDetailScreen / SettingsScreen / WebViewScreen）。所以这不是"设计选择"，
     * 而是漏写：读屏用户在这些页面只能听到一个没有名字的 Button，
     * 分不清"返回"还是"删除"。
     *
     * 注意与 `TopAppBar` 的关系：`navigationIcon` / `actions` 槽位**不会**自动补标签，
     * 图标为 null 就等于这个按钮在无障碍树上没有名字。
     */
    @Test
    fun iconOnlyButtonsAreAnnounced() {
        val offenders = mainSources.flatMap { file ->
            val lines = codeLines(file)
            (lines.indices).flatMap { i ->
                // 从 `Icon(` 起按括号配平取整个实参块：写成多行的
                // `Icon(\n  imageVector = Icons.Default.Close,\n  contentDescription = null\n)`
                // 用单行正则是扫不出来的，而那种"扫不出来"正好等于假绿。
                if (!lines[i].contains("Icon(")) return@flatMap emptyList()
                val args = argumentBlock(lines, i) ?: return@flatMap emptyList()
                val iconOnly = args.contains("ArrowBack") || args.contains("MoreVert")
                val silent = Regex("""contentDescription\s*=\s*null""").containsMatchIn(args)
                if (iconOnly && silent) listOf("${file.name}:${i + 1}") else emptyList()
            }
        }
        assertTrue(
            "以下图标-only 按钮的 contentDescription 是 null，读屏用户听到的是无名按钮：" +
                offenders.joinToString() +
                "。返回箭头统一写 \"返回\"、菜单触发器写 \"更多操作\"（与全仓其余 8 处一致）。",
            offenders.isEmpty()
        )
    }

    // ── 硬断言：详情操作栏声明触控下限 ────────────────────────────────────────

    /**
     * 视频详情操作栏（点赞/投币/收藏/分享/下载）必须**显式**声明 48dp 触控下限。
     *
     * 这一项的来由值得记下来：任务书写的是"ActionChip 内容高约 34dp + 上下
     * BiliSpacing.sm = 42dp，不足 48dp"。逐行核对后**该数字是错的**：
     * 图标 22dp + 间隙 2dp + labelMedium 行高约 16dp = 40dp 内容，
     * 再加上下各 8dp 内边距 ≈ **56dp**，本来就够。
     *
     * 所以这里加 `defaultMinSize(minHeight = 48.dp)` 不改变任何像素，
     * 它的作用是把"靠内容算术碰巧够高"变成"声明上的保证"：日后有人删掉数值行
     * 或改小图标，56dp 会不知不觉掉到 48 以下，而没有任何编译期检查会拦住这件事。
     */
    @Test
    fun videoDetailActionsDeclareMinimumTouchHeight() {
        val summary = mainSources.firstOrNull { it.name == "VideoDetailSummary.kt" }
        assertTrue("找不到 VideoDetailSummary.kt，本断言在假绿。", summary != null)
        val lines = codeLines(summary!!)
        val min = lines.count { Regex("""defaultMinSize\(\s*minHeight\s*=\s*48\.dp""").containsMatchIn(it) }
        assertTrue(
            "VideoDetailSummary 的操作栏应显式声明 defaultMinSize(minHeight = 48.dp)。" +
                "当前找到 $min 处（应为 1 处）。",
            min == 1
        )
    }

    // ── 棘轮：显式压小 IconButton 触控区的位置 ───────────────────────────────

    /**
     * `IconButton(modifier = Modifier.size(N.dp))` 且 N < 48 的数量。
     *
     * M3 的 IconButton 默认就是 48dp 触控区，**显式 `.size(32.dp)` 会把它压小**，
     * 属于主动放弃规范的写法。制定本断言时（2026-09-22）全仓 3 处：
     * - `app/.../playback/capsule/CapsuleIndicator.kt:213`（播放/暂停，32dp）
     * - `app/.../playback/capsule/CapsuleIndicator.kt:221`（关闭，32dp）
     * - `feature/.../comment/editor/CommentEditorSheet.kt:191`（移除图片，20dp）
     *
     * **为什么冻结而不是直接改成 48**：前两处在悬浮胶囊里，胶囊整体高度就是按 32dp
     * 排的；第三处是叠在图片缩略角上的 20dp 关闭钮。放大它们会真改掉布局，
     * 而本任务没有设备可走查观感 —— 与其制造一次无法验证的视觉回归，
     * 不如把清单钉住、留给有设备时统一处理（见交付报告的"待设备验证"）。
     */
    @Test
    fun subMinimumIconButtonSize_isFrozen() {
        val hits = mainSources.flatMap { smallIconButtonSites(it) }
        assertTrue(
            "IconButton 被显式压到 48dp 以下的有 ${hits.size} 处，超过冻结上限 3 处：" +
                hits.joinToString() +
                "。若新增，请说明为什么不能沿用 IconButton 的 48dp 默认触控区；" +
                "若这次是把某处改回了 48dp，请把上限一起调小。",
            hits.size <= 3
        )
    }

    /**
     * 返回该文件里"IconButton 自己的实参列表中出现了 size(N.dp) 且 N<48"的位置。
     *
     * 按**出现次数**而不是文件数统计：CapsuleIndicator 一个文件里就有两处
     * （播放/关闭），按文件数会少报一处、让上限 3 变成实质上的 4。
     *
     * 括号配平界定实参范围，理由见类注释"扫描器为什么要按实参边界扫"。
     */
    private fun smallIconButtonSites(file: File): List<String> {
        val lines = codeLines(file)
        val hits = mutableListOf<String>()
        for (i in lines.indices) {
            if (!lines[i].contains("IconButton(")) continue
            val args = argumentBlock(lines, i) ?: continue
            val m = Regex("""size\((\d+(?:\.\d+)?)\.dp\)""").find(args)
            if (m != null && m.groupValues[1].toFloat() < 48f) {
                hits += "${file.name}:${i + 1}(size=${m.groupValues[1]}.dp)"
            }
        }
        return hits
    }

    /**
     * 取第 [start] 行上那次调用的**实参块**（含换行），按圆括号配平定界。
     *
     * 返回 null 表示在文件结束前没有配平（畸形写法，宁可跳过也不要把调用体
     * 里的内层组件误算成外层实参）。
     *
     * 为什么必须这样：`IconButton(...) { Icon(Modifier.size(20.dp)) }` 与
     * `IconButton(modifier = Modifier.size(32.dp)) ...` 在单行正则里长得一样，
     * 但前者完全合规（48dp 触控区 + 20dp 图标）。不区分就会把全仓正常图标
     * 都算成违规，棘轮数字失去意义。
     */
    private fun argumentBlock(lines: List<String>, start: Int): String? {
        var depth = 0
        val sb = StringBuilder()
        for (j in start until lines.size) {
            sb.append(lines[j])
            for (ch in lines[j]) {
                when (ch) {
                    '(' -> depth++
                    ')' -> depth--
                }
            }
            if (j > start && depth <= 0) return sb.toString()
        }
        return null
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    /**
     * 读源码并**把注释行与 import 行替换成空行**（保留行号，便于报位置）。
     *
     * 保留空行而不是过滤掉，是因为本类需要把"第几行"报给人看；
     * 同时避免解释性注释里写的 `size(32.dp)`、`contentDescription = null`
     * 之类的字样被算成违规（那会让"把为什么写清楚"变成有代价的事）。
     */
    private fun codeLines(file: File): List<String> =
        file.readLines().map { line ->
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
