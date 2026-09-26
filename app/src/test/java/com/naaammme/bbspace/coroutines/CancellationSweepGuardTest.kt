package com.naaammme.bbspace.coroutines

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 取消语义收口的进度守卫（BACKLOG ST-06 / ST-06b）。
 *
 * ## 背景
 * 裸 `runCatching { suspendCall() }` 会捕获 `CancellationException`，把"用户取消/页面销毁"
 * 当成"网络失败"：界面渲染出错误态、`onFailure` 打错误日志、甚至在会话已拆掉之后回写状态。
 * 本仓已有 `runCatchingCancellable`（`core/common`）作为正解，
 * `VideoLogicGuardTest` 已把 feature 层的 11 个 ViewModel 钉住；这里补 core/infra 侧。
 * （注：Kotlin 块注释可嵌套，KDoc 里不要出现斜杠星号的组合，会把整段注释撑开。）
 *
 * ## 为什么不是"全仓禁止裸 runCatching"
 * 大量 `runCatching` 包的是**非挂起**代码（位图转换、JSON 解析、`VersionName` 读取），
 * 语义上就该吞掉异常。所以沿用本仓既有范式：**逐文件登记 + 数字只许变好**，
 * 而不是写一条注定要被加白名单的通配禁令。
 *
 * ## 第三组断言才是本测试的价值所在
 * 有两处（发评论 / 发直播弹幕之后写"我的发布"记录）**刻意不能**换成
 * `runCatchingCancellable`：那时请求已经成功，把取消传播回去会让界面把已成功的发送
 * 改判为失败（表现为"发出去了但输入框不清空/列表不刷新"）。
 * B14 已把这两处落成正解 `withContext(NonCancellable) { runCatching { … } }`，
 * 所以断言也从"解释注释还在"升级成三件事：写法确实是不可取消上下文包住的、
 * 每个 `runCatching` 都在这个包里、理由注释没被删。
 * 这类"看起来同类、实则相反"的点最容易被顺手一起改掉，所以把它变成机器检查。
 */
class CancellationSweepGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    /** 已收口文件 →（cancellable 出现次数下限，裸 runCatching 残留上限）。 */
    private val migrated = mapOf(
        "core/sponsorblock/src/main/java/com/naaammme/bbspace/core/sponsorblock/SponsorBlockClient.kt" to (3 to 1),
        "feature/download/src/main/java/com/naaammme/bbspace/feature/download/player/OfflineDanmakuSession.kt" to (1 to 0),
        // AppInitializer 裸 runCatching 上限 3（B10 从 4 降下来）：这 3 处包的都是非挂起代码
        // （execute().close() 连接预热 ×2、startService 兜底），语义上就该吞异常。
        "app/src/main/java/com/naaammme/bbspace/AppInitializer.kt" to (2 to 3),
        "core/download/src/main/java/com/naaammme/bbspace/core/download/VideoDownloadRepository.kt" to (1 to 0),
        // B12 关掉 ST-06 最后一处：导入 json 的整个操作被取消时不该 Toast「导入失败」。
        // 该文件此前全仓只有 1 处 runCatching，所以上限取 0 —— 再出现第二处就该先说明理由。
        "feature/bbspace/src/main/java/com/naaammme/bbspace/feature/bbspace/BbSpaceScreen.kt" to (1 to 0)
    )

    /**
     * 用 `try { … } catch (e: Exception)` 写法的那些文件 → 必须出现的 CE 重抛次数。
     * （B9：infra 三个客户端是这种形态，改不动成 runCatchingCancellable。）
     */
    private val migratedViaRethrow = mapOf(
        "infra/coldstart/src/main/java/com/naaammme/bbspace/infra/coldstart/ColdStartClient.kt" to 1,
        "infra/network-grpc/src/main/java/com/naaammme/bbspace/infra/grpc/GaiaReporter.kt" to 1,
        "infra/crypto/src/main/java/com/naaammme/bbspace/infra/crypto/TicketGenerator.kt" to 2
    )

    /** 刻意不收口的文件（必须留注释说明为什么，见类注释）。 */
    private val deferred = listOf(
        "core/comment/src/main/java/com/naaammme/bbspace/core/comment/CommentRepository.kt",
        "core/live/src/main/java/com/naaammme/bbspace/core/live/LiveRoomMessageRepository.kt"
    )

    @Test
    fun scannerActuallyReadsSources() {
        for (rel in migrated.keys + migratedViaRethrow.keys + deferred) {
            assertTrue("找不到 $rel（路径变了要同步本测试，否则断言会永久空转）", file(rel).isFile)
        }
    }

    @Test
    fun migratedFilesStayMigrated() {
        for ((rel, bounds) in migrated) {
            val (minCancellable, maxBare) = bounds
            val code = codeOf(file(rel))
            // 尾随 lambda 写法是 `runCatchingCancellable {`，也可能写成 `(...)`，两种都算
            val cancellable = Regex("""runCatchingCancellable\s*[\{(]""").findAll(code).count()
            val bare = Regex("""(?<!Cancellable)runCatching\s*\{""").findAll(code).count()
            assertTrue(
                "$rel 现在只有 $cancellable 处 runCatchingCancellable（登记下限 $minCancellable）—— " +
                    "减少意味着有站点退回裸 runCatching，取消又会被当失败。",
                cancellable >= minCancellable
            )
            assertTrue(
                "$rel 的裸 `runCatching {` 现在是 $bare 处，超过登记上限 $maxBare。" +
                    "要新增包裹挂起调用的 runCatching 请改 runCatchingCancellable；" +
                    "确实是非挂起代码才放宽，并在提交说明里写清楚。",
                bare <= maxBare
            )
        }
    }

    /** try/catch 形态的文件必须把 CE 显式重抛出足够多处。 */
    @Test
    fun rethrowFormStaysRethrowing() {
        for ((rel, minRethrow) in migratedViaRethrow) {
            val code = codeOf(file(rel))
            val rethrows = Regex("""catch\s*\(\s*e:\s*CancellationException\s*\)""").findAll(code).count()
            val broadCatches = Regex("""catch\s*\(\s*e:\s*Exception\s*\)""").findAll(code).count()
            assertTrue(
                "$rel 现在有 $rethrows 处 `catch (e: CancellationException)`（登记下限 $minRethrow），" +
                    "而宽 catch 有 $broadCatches 处 —— 少一处重抛就意味着某一处把取消当失败处理了。",
                rethrows >= minRethrow && rethrows <= broadCatches
            )
        }
    }

    /**
     * ST-06b 两处的正解形态必须保住，同时不许把**无关**站点一起算进来。
     *
     * 这两个文件里本来就有合法的裸 runCatching（JSON 解析、`close()` —— 非挂起代码，
     * 语义上就该吞异常），所以不能一刀切要求"文件内每个 runCatching 都被 NonCancellable 包住"。
     * 规则精确到记账那一句：`publishedRecordRepo.save(...)` 必须落在
     * `withContext(NonCancellable) { runCatching { … } }` 里；其余未包站点按文件登记上限（棘轮）。
     */
    @Test
    fun deferredSitesKeepTheirExplanation() {
        for (rel in deferred) {
            val target = file(rel)
            val raw = target.readText().replace("\r\n", "\n")
            val rows = codeLines(target)
            val allowance = deferredUnwrappedAllowance.getValue(rel)

            val saveSites = rows.mapIndexedNotNull { index, line ->
                if (!PUBLISHED_SAVE.containsMatchIn(line)) return@mapIndexedNotNull null
                val near = rows.subList(maxOf(0, index - SAVE_WINDOW), index).joinToString("\n")
                val wrapped = NON_CANCELLABLE.containsMatchIn(near) && RUN_CATCHING_OPEN.containsMatchIn(near)
                if (wrapped) null else "${rel}:${index + 1} -> ${line.trim()}"
            }
            assertTrue(
                "ST-06b 的这两处是\"操作已经成功、只剩本地记账\"，本地记录必须写在 " +
                    "withContext(NonCancellable) { runCatching { … } } 里。" +
                    "下面这些记账调用没被包住（写法被改回裸的，或被顺手换成了 runCatchingCancellable）：\n" +
                    saveSites.joinToString("\n"),
                saveSites.isEmpty()
            )

            val unwrapped = rows.mapIndexedNotNull { index, line ->
                if (!RUN_CATCHING_OPEN.containsMatchIn(line)) return@mapIndexedNotNull null
                val near = rows.subList(maxOf(0, index - WRAP_WINDOW), index + 1).joinToString("\n")
                if (NON_CANCELLABLE.containsMatchIn(near)) null else "${rel}:${index + 1} -> ${line.trim()}"
            }
            assertTrue(
                "$rel 未包 NonCancellable 的 runCatching 现在有 ${unwrapped.size} 处，" +
                    "登记上限 $allowance（这些都是非挂起代码：JSON 解析 / close()，本就该吞异常）。" +
                    "要新增包裹挂起调用的站点请改 runCatchingCancellable，或按 ST-06b 的判据说明理由：\n" +
                    unwrapped.joinToString("\n"),
                unwrapped.size <= allowance
            )

            assertTrue(
                "$rel 的 ST-06b 标记注释被删了 —— 这段\"为什么不能传播取消\"的理由是唯一的防误改说明。",
                raw.contains("ST-06b")
            )
        }
    }

    private fun file(rel: String): File = File(repoRoot, rel.replace('/', File.separatorChar))

    /** 逐行去注释/ import（保留原行号，报错里给的行号才对得上文件）。 */
    private fun codeLines(file: File): List<String> = file.readLines().map { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
            trimmed.startsWith("import ")
        ) {
            ""
        } else {
            line
        }
    }

    /** 注释与 import 清空后再计数（写注释提到 runCatching 不该让计数虚增）。 */
    private fun codeOf(file: File): String = codeLines(file).joinToString("\n")

    private companion object {
        val RUN_CATCHING_OPEN = Regex("""(?<!Cancellable)runCatching\s*\{""")
        val NON_CANCELLABLE = Regex("""withContext\(\s*NonCancellable\s*\)""")

        /** 记账那一句（ST-06b 要保住的就是它）。 */
        val PUBLISHED_SAVE = Regex("""publishedRecordRepo\.save\(""")

        /** `withContext(NonCancellable) {` 与被包裹的 `runCatching {` 之间允许的行数。 */
        const val WRAP_WINDOW = 2

        /** `runCatching {` 到 `publishedRecordRepo.save(` 之间允许的行数。 */
        const val SAVE_WINDOW = 3
    }

    /**
     * 各 deferred 文件里"未包 NonCancellable 的 runCatching"上限（棘轮，实测基线）。
     * 这些都是非挂起代码，本来就该吞异常：
     *  - CommentRepository 0 处（唯一的 runCatching 就是记账那处，已包 NonCancellable）
     *  - LiveRoomMessageRepository 2 处：JSON 解析 `runCatching { JSONObject(text) }`、
     *    `runCatching { close() }`
     */
    private val deferredUnwrappedAllowance = mapOf(
        "core/comment/src/main/java/com/naaammme/bbspace/core/comment/CommentRepository.kt" to 0,
        "core/live/src/main/java/com/naaammme/bbspace/core/live/LiveRoomMessageRepository.kt" to 2
    )
}
