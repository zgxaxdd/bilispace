package com.naaammme.bbspace.core.feed

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PT-10 V2 / V3 / V8 / V10：口径守卫（结构断言）。
 *
 * ## 为什么是结构断言
 * `FeedRepository` 的三个分支都要出网（WBI 签名 + OkHttp），在 JVM 单测里端到端跑
 * 需要一整套 fake rest client + fake WBI provider；而这几条验收真正要守的是
 * **"默认路径没被动过"** —— 这是写法层面的不变式，用源码结构断言反而更咬人。
 *
 * ## 防假绿（照抄 FrameBudgetLintTest / VideoLogicGuardTest 两条）
 * 1. 先确认文件真的读到了（`codeOf` 找不到文件直接断言失败）；
 * 2. 扫描前把注释行、块注释行、import 行清空（本仓库注释里必然出现
 *    `restClient.getWeb`、`fetchMobile` 这些词，不过滤就会把注释当成代码）。
 */
class FeedSourceDispatchGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val FS: String get() = File.separator

    @Test
    fun scannerActuallyReadsTheRepository() {
        val repo = codeOf("feed${FS}FeedRepository.kt")
        assertTrue(
            "只读到 ${repo.size} 行 FeedRepository.kt（repoRoot=${repoRoot.absolutePath}），" +
                "下面的断言将失去意义。",
            repo.size > 200
        )
    }

    // ── V2：默认来源 = MOBILE，走 fetchMobile 分支 ────────────────────────────

    @Test
    fun dispatcherSendsMobileToFetchMobile() {
        val dispatcher = blockBody(codeOf("feed${FS}FeedRepository.kt"), "suspend fun fetchFeed(cursor: FeedCursor")
        assertTrue(
            "分派必须读设置里的推荐来源：`when (appSettings.feedApiType.first())`。",
            dispatcher.any { it.contains("appSettings.feedApiType.first()") }
        )
        assertTrue(
            "MOBILE 分支必须且只能走 fetchMobile（默认路径 = 历史行为）。",
            dispatcher.any { it.contains("FeedApiType.MOBILE -> fetchMobile(") }
        )
        assertTrue(
            "WEB 分支走 fetchWebFeed、MERGED 分支走 fetchMergedFeed。",
            dispatcher.any { it.contains("FeedApiType.WEB -> fetchWebFeed(") } &&
                dispatcher.any { it.contains("FeedApiType.MERGED -> fetchMergedFeed(") }
        )
    }

    /**
     * SX-04 / 决策 D-1：**接力按来源取游标**，任何路径都不得让 web 侧的 `idx` 参与翻页。
     *
     * ## 为什么把旧断言反转（旧名 `legacyThreeArgEntryAlwaysUsesAppProtocol`）
     * 旧断言钉的是"旧入口 `fetchFeed(idx, …)` 恒走 app 协议"，其存在理由是：
     * **接力依赖 app 协议回传的 idx 翻页**，而 web 协议不下发 idx（恒 0），
     * 一旦接力也按设置切 WEB 就会拿 `idx=0` 反复拉同一页 —— 静默死循环。
     *
     * 决策 D-1（用户 2026-10-03 拍板：刷视频的推荐流必须与设置里的推荐来源保持同步）
     * 把这个前提**推翻**了：死循环风险改由**来源自持游标**正面解决
     * （app 侧 `FeedCursor.appIdx` / web 侧 `FeedCursor.webPage`，推进规则收在
     * `SwipeFeedCursorPolicy`），而不是靠"掐掉来源切换"。旧入口因此在同一提交里
     * 收敛删除（`grep` 确认删除前只剩接力一个调用点：首页早已在游标重载上）。
     */
    @Test
    fun swipeRelayFollowsFeedSourceWithSourceOwnedCursor() {
        val repo = codeOf("feed${FS}FeedRepository.kt")
        assertTrue(
            "旧入口 `fetchFeed(idx, pull, flush)` 必须已删除：它是「恒 app 协议」的唯一载体，" +
                "留着就等于给 WEB 档的静默死循环留后门（决策 D-1 后其存在理由不再成立）。",
            repo.none { it.contains("suspend fun fetchFeed(idx: Long") } &&
                repo.none { it.contains("return fetchMobile(idx, pull, flush)") }
        )
        assertTrue(
            "唯一的取流入口必须是**感知来源**的游标重载。",
            repo.any { it.contains("suspend fun fetchFeed(cursor: FeedCursor, pull: Boolean, flush: Int)") } &&
                repo.any { it.contains("appSettings.feedApiType.first()") }
        )

        val sources = codeOf("swipesource${FS}SwipeSources.kt")
        assertTrue(
            "接力必须改走游标重载（`fetchFeed(cursor = feedCursor, …)`），且不得再出现任何 `fetchFeed(idx =`。",
            sources.any { it.contains("feedRepository.fetchFeed(cursor = feedCursor, pull = false, flush = 0)") } &&
                sources.none { it.contains("fetchFeed(idx =") }
        )
        assertTrue(
            "**web 侧 idx 不得参与翻页**：接力游标必须两套分页空间各自推进 —— " +
                "web 页序号走 `SwipeFeedCursorPolicy`（webPage），app 侧才是 idx。" +
                "断言锚在「换算 / 推进调用」这个**调用形态**上（W18/W20/W21），不锚注释、不锚字面量。",
            sources.any { it.contains("SwipeFeedCursorPolicy.toFeedCursor(cursor)") } &&
                sources.any { it.contains("SwipeFeedCursorPolicy.advance(feedCursor)") }
        )
        assertTrue(
            "接力游标回填必须走同一个策略（不许在 VM 里手写 `+1`，也不许退回 idx 单游标）。",
            codeOf("feature${FS}video${FS}VideoViewModel.kt")
                .any { it.contains("SwipeFeedCursorPolicy.toFeedCursorOrInitial(batch.nextCursor)") }
        )
    }

    // ── V3：fetchMobile 与改动前的 fetchFeed 逐字一致 ─────────────────────────

    @Test
    fun fetchMobileIsTheOldImplementationVerbatim() {
        val mobile = blockBody(codeOf("feed${FS}FeedRepository.kt"), "private suspend fun fetchMobile(")
        assertTrue("hdFeed 开关读取必须原样保留", mobile.any { it.contains("val hdFeed = appSettings.hdFeed.first()") })
        assertTrue(
            "profile 选择必须原样保留",
            mobile.any { it.contains("val profile = if (hdFeed) BiliRestProfile.HD else BiliRestProfile.APP") }
        )
        assertTrue(
            "URL 拼接口径必须逐字不变：BASE_URL_APP + FEED_ENDPOINT",
            mobile.any { it.contains("url = \"\${BiliConstants.BASE_URL_APP}\$FEED_ENDPOINT\",") }
        )
        assertTrue(
            "签名取流必须仍是 getSigned + buildParams(idx, pull, flush, profile, hdFeed)",
            mobile.any { it.contains("params = buildParams(idx, pull, flush, profile, hdFeed),") } &&
                mobile.any { it.contains("profile = profile") }
        )
        assertTrue(
            "解析路径必须原样保留",
            mobile.any { it.contains("return parseResponse(json, hdFeed)") }
        )
        assertTrue(
            "fetchMobile 不许知道游标的存在 —— 它必须是「原样搬进来的旧实现」，" +
                "一旦它开始读 FeedCursor，就说明有人把零回归的安全边界改掉了。",
            mobile.none { it.contains("FeedCursor") }
        )
    }

    @Test
    fun appEndpointLiteralUnchanged() {
        val repo = codeOf("feed${FS}FeedRepository.kt")
        assertEquals(
            "V10：app 协议端点字面量必须唯一且未变（改它=改了默认取流口径）。",
            1,
            repo.count { it.contains("const val FEED_ENDPOINT = \"/x/v2/feed/index\"") }
        )
        assertEquals(
            "WEB 端点字面量必须唯一。",
            1,
            repo.count { it.contains("const val WEB_FEED_ENDPOINT = \"/x/web-interface/wbi/index/top/feed/rcmd\"") }
        )
    }

    // ── V8：WBI 密钥取不到 → 回退 MOBILE 且记日志（不静默） ────────────────────

    @Test
    fun webFeedFallsBackToMobileWithALog() {
        val web = blockBody(codeOf("feed${FS}FeedRepository.kt"), "private suspend fun fetchWebFeed(")
        assertTrue("WEB 档必须经 WbiKeyProvider 取密钥", web.any { it.contains("wbiKeyProvider.getKeys()") })
        assertTrue(
            "取密钥失败必须记日志（V8：不静默降级）",
            web.any { it.contains("Logger.w(TAG)") }
        )
        assertTrue(
            "取密钥失败必须回退 MOBILE 取流（方案 §4.7）",
            web.any { it.contains("return fetchMobile(cursor.appIdx, pull, flush)") }
        )
        assertTrue(
            "取消不能被吞（CancellationException 必须重抛）",
            web.any { it.contains("catch (e: CancellationException)") }
        )
    }

    @Test
    fun webRequestIsWbiSignedWithCookieHeaders() {
        val request = blockBody(codeOf("feed${FS}FeedRepository.kt"), "private suspend fun requestWebFeed(")
        assertTrue(
            "WEB 取流必须是 WBI 签名 + getWeb",
            request.any { it.contains("WbiSigner.sign(") } &&
                request.any { it.contains("restClient.getWeb(") }
        )
        assertTrue(
            "WBI 签名参数口径：ps / fresh_type / fresh_idx / y_num / feed_version",
            request.any { it.contains("\"ps\" to FEED_PAGE_SIZE.toString()") } &&
                request.any { it.contains("\"fresh_type\" to if (pull) \"4\" else \"3\"") } &&
                request.any { it.contains("\"fresh_idx\" to page.toString()") } &&
                request.any { it.contains("\"y_num\" to page.toString()") } &&
                request.any { it.contains("\"feed_version\" to System.currentTimeMillis().toString()") }
        )
        assertTrue(
            "WEB 端点必须是 api.bilibili.com + WEB_FEED_ENDPOINT",
            request.any { it.contains("url = \"\${BiliConstants.BASE_URL_API}\$WEB_FEED_ENDPOINT\",") }
        )
        val headers = blockBody(codeOf("feed${FS}FeedRepository.kt"), "private fun webHeaders(")
        assertTrue(
            "WEB 请求头必须带 Cookie（有登录态则个性化，无 Cookie 也能匿名取流）",
            headers.any { it.contains("BiliWebHeaderTool.build(") } &&
                headers.any { it.contains("cookieHeader = authStore.cookieHeader()") }
        )
    }

    // ── MERGED 并行 + 交错合并 ───────────────────────────────────────────────

    /**
     * SX-12：MERGED 的运行时证据必须 **release 可见**。
     *
     * 「MERGED 有没有真的把两路合起来」只能靠计数日志回答；而 `Logger.d` 是
     * debug 门控的（`Logger.isDebug`），稳定版根本打不出来 —— 上一会话之所以
     * 只能"在 debug 包上看"，就是因为这条日志被门控了，于是这条命题在真机上
     * **永远缺证据**。断言锚在「直写 Log.i」这个**调用形态**上（W18/W20/W21）。
     */
    @Test
    fun mergedCountLogIsVisibleInRelease() {
        val merged = blockBody(codeOf("feed${FS}FeedRepository.kt"), "private suspend fun fetchMergedFeed(")
        assertTrue(
            "MERGED 的计数日志必须直写 android.util.Log.i（release 可见），不得退回 Logger.d。",
            merged.any { it.contains("android.util.Log.i(") } &&
                merged.none { it.contains("Logger.d(TAG)") }
        )
        assertTrue(
            "日志必须给出两路条目数（web=… mobile=…）——这是「两路都在有效取流」的直接证据。",
            merged.any { it.contains("\"MERGED 档：web=") }
        )
        assertTrue(
            "还必须给出单边失败时的异常类名（L4-123「单边失败仍出内容」的取证口径）。",
            merged.any { it.contains("webErr=") } && merged.any { it.contains("mobileErr=") }
        )
    }

    @Test
    fun mergedRunsBothSourcesInParallelAndInterleaves() {
        val merged = blockBody(codeOf("feed${FS}FeedRepository.kt"), "private suspend fun fetchMergedFeed(")
        assertEquals(
            "MERGED 必须并行（两个 async），而不是串行等两边",
            2,
            merged.count { it.contains("async {") }
        )
        assertTrue(
            "合并必须走 FeedMergePolicy.mergeFeeds（交错去重，方案 §4.5）",
            merged.any { it.contains("FeedMergePolicy.mergeFeeds(web = webItems, app = mobileItems)") }
        )
        assertTrue(
            "单边失败要返回存活边 → 不允许「任一侧为空就整体失败」",
            merged.any { it.contains("webItems.isEmpty() && mobileItems.isEmpty()") }
        )
    }

    // ── HomeViewModel：游标按来源分持，MOBILE 口径不变 ────────────────────────

    @Test
    fun homeViewModelUsesSourceAwareCursors() {
        val vm = codeOf("feature${FS}home${FS}HomeViewModel.kt")
        assertTrue(
            "首屏必须读一次真实落盘来源（收集器首次发射可能还没到）",
            vm.any { it.contains("feedSource = appSettings.feedApiType.first()") }
        )
        assertTrue(
            "刷新游标走 FeedCursorPolicy.refreshCursor(feedSource, …)",
            vm.any { it.contains("FeedCursorPolicy.refreshCursor(feedSource") }
        )
        assertTrue(
            "翻页游标走 FeedCursorPolicy.loadMoreCursor(feedSource, feedCursor, …)",
            vm.any { it.contains("FeedCursorPolicy.loadMoreCursor(feedSource, feedCursor") }
        )
        assertTrue(
            "切换来源必须重置游标（两套语义不能混用，方案 §4.4）",
            vm.any { it.contains("feedCursor = FeedCursor.Initial") }
        )
        assertTrue(
            "MOBILE 的历史 idx 口径必须还在：刷新取首项 idx、翻页取末项 idx",
            vm.any { it.contains("_uiState.value.items.firstOrNull()?.idx ?: 0L") } &&
                vm.any { it.contains("_uiState.value.items.lastOrNull()?.idx ?: 0L") }
        )
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun codeOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${FS}src${FS}main${FS}") }
            .filterNot { it.path.contains("${FS}build${FS}") }
            .firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。")
        return file.readLines().map { line ->
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

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`，无法验证其内部结构。", start >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> {
                        depth++
                        opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        return body
    }
}
