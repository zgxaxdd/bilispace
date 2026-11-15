package com.naaammme.bbspace.feature.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackPageMode] 的迁移单测（第 34 轮重构的**验收核心**）。
 *
 * ## 为什么这个测试必须存在
 * 重构前「现在处于哪种页面模式」由 5 个可变布尔互相推导（`isFull` /
 * `swipeSessionActive` / `swipeModeRequested` / `swipeLandscape` / `storyMode`），
 * 合成态没有任何类型约束 —— 这是"刷视频模式全是 bug"的结构性根因。
 * 收敛成一个 sealed 状态后，**状态迁移必须是纯函数且可逐一枚举**，
 * 本测试就是这个不变式的机器判据。
 *
 * ## 覆盖的四条路径（任务书硬验收）
 * 1. story → 退出 story → 详情（[storyExitLandsOnDetailNotClosed]）
 * 2. 详情 → 进 swipe → 退出 swipe → 详情（[detailSwipeRoundTrip]）
 * 3. 横屏 ↔ 竖屏（[landscapeOnlySticksToTheNamedVideo]）
 * 4. 返回键（[backActionFollowsMode]）
 *
 * ## 第 35 轮核对后补的「完整状态」断言（原测试的缺口）
 * 原测试逐条看布尔，但**没有一条**在迁移后把**全部派生量**一起断言，也没覆盖：
 * story 带横屏时退出（[storyExitDropsEverythingInOneTransition]）、
 * swipe 带横屏时退出是否清干净（[swipeExitDropsRequestLandscapeAndSession]）、
 * `allowsAutoLandscape` 为**真**的那条合法路径（原测试只断言过它为假，
 * 见 [swipeSessionLocksPortraitAndRefusesAutoLandscape]）、
 * 返回键在「面板开着 / 横屏中 / 普通详情」三态下的取值
 * （[backActionInPanelLandscapeAndPlainDetail]）、
 * 以及几条**永远不该有动作**的迁移（[storyAndCompactIgnoreSwipeTransitions]）。
 *
 * 另外补了两条**全局**判据：
 * - [derivedInvariantsHoldForEveryFieldCombination]：对**全部 11 种可构造形态**验证派生量
 *   之间的不变式（逐字段定义式，不依赖迁移）；
 * - [unreachableCombinationsCannotBeReachedByMigrations]：用迁移闭包证明**恰好只有 9 种可达**，
 *   即重构前那些非法合成态（`sessionActive=true` 却 `swipeRequested=false`）真的走不到。
 *
 * 发现但**没有改**状态机的一处语义不自洽，按"当前行为"钉住并留 TODO：
 * [landscapeToggledBeforeActivationIsStoredButInvisible]。
 *
 * ## 防"恒真"
 * 每条断言都锚在**具体迁移结果**上（`assertEquals(Story(landscape=true), ...)`），
 * 不是"assertTrue(x != null)"这类永真形态；且专门有
 * [staleBooleansCannotProduceImpossibleStates] 断言重构前那些合成态**不可表达**。
 */
class PlaybackPageModeTest {

    private val compact = PlaybackPageMode.Compact
    private val detail = PlaybackPageMode.DetailFullscreen()
    private val story = PlaybackPageMode.Story()

    // ── 路径 1：story → 退出 story → 详情 ─────────────────────────────────────

    /**
     * story 入口必须**常驻全屏**（`isFull` 恒真、`locksPortrait` 恒真），
     * 退出后必须落回**详情（Compact）而不是关闭页面** ——
     * 这是需求 3（「刷视频会话里点还原 → 露出当前这支的详情页」）的机器判据。
     */
    @Test
    fun storyExitLandsOnDetailNotClosed() {
        val initial = PlaybackPageMode.initial(storyMode = true)
        assertEquals(
            "story 入口必须以 Story 态起步（重构前是 `mutableStateOf(storyMode)` 初始化 isFull 与 swipeSessionActive），" +
                "否则首屏会先用详情布局闪一帧",
            story, initial
        )
        assertTrue("story 必须常驻全屏", initial.isFull)
        assertTrue("story 必须常驻会话激活", initial.swipeSessionActive)
        assertTrue("story 必须锁竖屏（连播节奏由 pager 说了算）", initial.locksPortrait)
        assertTrue("story 必须被识别为 story（决定退出语义）", initial.isStory)

        val afterExit = initial.exitFullscreen()
        assertEquals(
            "退出 story 必须落回**详情（Compact）**，而不是「关掉整页」——" +
                "需求 3 的语义是露出当前这支的详情页与评论",
            compact, afterExit
        )
        assertFalse("退出后不该再是全屏", afterExit.isFull)
        assertFalse("退出后不该再锁竖屏", afterExit.locksPortrait)
        assertFalse("退出后不再有一次性下滑请求", afterExit.swipeModeRequested)
        assertEquals(
            "退出 story 后返回键应当交给宿主栈（popPage / 关页），而不是再次 ClosePage",
            PlaybackPageMode.BackAction.PassThrough, afterExit.backAction()
        )

        // 幂等：再退一次不该回退到别的状态（重构前两个布尔各退一次会打架）
        assertEquals("exitFullscreen 必须幂等（Compact 上原地不动）", compact, afterExit.exitFullscreen())
    }

    // ── 路径 2：详情 → 进 swipe → 退出 swipe → 详情 ──────────────────────────

    /**
     * 详情页的两条进入路径必须**可区分**：
     * - 下滑请求（`requestSwipeSession`）→ 会话可激活；
     * - 「全屏」钮（只 `enterFullscreen`）→ **不**开会话（R3）。
     *
     * 且退出后必须回到 **Compact（详情）**，而不是留在"全屏但无会话"的半截态。
     */
    @Test
    fun detailSwipeRoundTrip() {
        // ① 全屏钮：只进全屏，不带请求
        val plainFullscreen = compact.enterFullscreen()
        assertEquals(
            "「全屏」钮只能进全屏、不能带刷视频请求（R3：全屏钮不连播）",
            PlaybackPageMode.DetailFullscreen(swipeRequested = false, sessionActive = false),
            plainFullscreen
        )
        assertFalse("全屏钮路径下没有下滑请求", plainFullscreen.swipeModeRequested)
        assertFalse("没有请求就不激活会话", plainFullscreen.swipeSessionActive)
        assertEquals(
            "队列为空时 resolve 不得激活会话（保持单视频全屏，既有正确行为）",
            plainFullscreen,
            plainFullscreen.resolveSwipeSession(queueNotEmpty = true)
        )

        // ② 下滑提交：置请求，但**不**立即激活（等锚点+队列）
        val requested = compact.requestSwipeSession()
        assertEquals(
            "下滑提交只置一次性请求，不直接激活会话（锚点/队列由 VM 异步建立）",
            PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = false),
            requested
        )
        assertTrue("请求已置位", requested.swipeModeRequested)
        assertFalse("请求置位但队列未知 → 尚未激活", requested.swipeSessionActive)
        assertTrue(
            "★真机 bug 的关键：请求已置位、会话未激活的空窗里**也**必须锁竖屏，" +
                "否则「单视频全屏自动横屏」会在空窗里转一次屏、会话激活后再锁回来 —— " +
                "用户看到的就是「先横屏、再切回刷视频」的闪跳",
            requested.locksPortrait
        )
        assertFalse("锁竖屏时不得允许自动横屏", requested.allowsAutoLandscape)

        // ③ 队列非空 → 激活
        val active = requested.resolveSwipeSession(queueNotEmpty = true)
        assertEquals(
            "队列非空时必须激活会话",
            PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = true),
            active
        )
        assertTrue("激活后会话可渲染", active.swipeSessionActive)
        assertTrue("激活后仍然锁竖屏", active.locksPortrait)

        // ④ 队列空 → 不激活（幂等，可反复调用）
        assertEquals(
            "队列为空时必须保持未激活（单视频全屏）",
            requested,
            requested.resolveSwipeSession(queueNotEmpty = false)
        )

        // ⑤ 退出 → 回详情
        assertEquals(
            "退出刷视频会话必须回到 Compact（详情页），不是留在全屏",
            compact, active.exitFullscreen()
        )
    }

    // ── 路径 3：横屏 ↔ 竖屏 ──────────────────────────────────────────────────

    /**
     * 手动横屏**只对点名的那一支视频生效**：切视频（`onVideoChanged`）必须收回。
     *
     * 对齐 BiliPai 的 `onRotateToLandscape` → `setPortraitFullscreen(false)`：
     * 连播全程是竖屏节奏，只有用户显式点名的那一条满屏。
     */
    @Test
    fun landscapeOnlySticksToTheNamedVideo() {
        val active = PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = true)

        val landscapeOn = active.toggleLandscape()
        assertEquals(
            "横屏开关必须在会话激活态生效",
            PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = true, landscape = true),
            landscapeOn
        )
        assertTrue("横屏态必须可以被页面读到", landscapeOn.landscapeForced)

        // 再点一次 = 收回
        assertEquals("横屏钮必须可逆（再点回竖屏）", active, landscapeOn.toggleLandscape())

        // 切视频 → 收回
        assertEquals(
            "★切到下一条视频必须收回横屏（横屏只对点名的那一支生效）",
            active, landscapeOn.onVideoChanged()
        )

        // story 态同理
        val storyLandscape = story.toggleLandscape()
        assertEquals("story 态也能手动横屏", PlaybackPageMode.Story(landscape = true), storyLandscape)
        assertEquals(
            "story 态切视频同样必须收回横屏",
            story, storyLandscape.onVideoChanged()
        )
    }

    /**
     * 未激活会话时**不报告横屏**（否则顶栏/悬浮钮会显示"已横屏"而实际没锁）。
     */
    @Test
    fun landscapeIsIgnoredOutsideActiveSession() {
        val notActive = PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = false)
        assertFalse(
            "会话未激活时 landscapeForced 必须为假 —— 否则 UI 显示「已横屏」但方向 effect 不会锁横屏",
            notActive.toggleLandscape().landscapeForced
        )
        assertFalse("Compact 态没有横屏概念", compact.toggleLandscape().landscapeForced)
        assertEquals("Compact 上 toggleLandscape 必须原地不动", compact, compact.toggleLandscape())
    }

    // ── 路径 4：返回键 ───────────────────────────────────────────────────────

    /**
     * 返回键的优先级必须收敛在一处（重构前 `handleBack` 与 pager 的 BackHandler
     * 各排一遍，`effectiveStoryMode` / `fullOn` 交叉判断）。
     */
    @Test
    fun backActionFollowsMode() {
        assertEquals(
            "紧凑态：返回交给宿主栈（popPage / 关页）",
            PlaybackPageMode.BackAction.PassThrough, compact.backAction()
        )
        assertEquals(
            "详情全屏：返回 = 形变回缩到详情",
            PlaybackPageMode.BackAction.ExitFullscreen, detail.backAction()
        )
        assertEquals(
            "story：返回 = 关闭页面（story 没有紧凑态可回）",
            PlaybackPageMode.BackAction.ClosePage, story.backAction()
        )
        assertEquals(
            "会话激活的详情全屏仍然只是「退出全屏」，不是关页",
            PlaybackPageMode.BackAction.ExitFullscreen,
            PlaybackPageMode.DetailFullscreen(sessionActive = true).backAction()
        )
    }

    // ── 反向断言：重构前那些合成态**不可表达** ────────────────────────────────

    /**
     * 重构前的两个布尔可以拼出**四种**组合，其中两种是坏的：
     *
     * | isFull | swipeSessionActive | 含义 | 重构前 | 重构后 |
     * |---|---|---|---|---|
     * | true | true | 刷视频流在渲染 | ✓ | `DetailFullscreen(sessionActive=true)` / `Story` |
     * | true | false | 全屏但没有会话 | ✓（合法） | `DetailFullscreen(sessionActive=false)` |
     * | false | true | **不合法**：会话激活却不全屏 | **可表达** | 不可表达 |
     * | false | false | 紧凑态 | ✓ | `Compact` |
     *
     * 第三行就是"画面忽然跳回去"的来源：`swipeSessionActive=true` 而
     * `isFull=false` 时，pager 分支与详情分支的判据会各自算出不同结论。
     */
    @Test
    fun staleBooleansCannotProduceImpossibleStates() {
        val allModes = listOf(
            compact,
            detail,
            PlaybackPageMode.DetailFullscreen(swipeRequested = true),
            PlaybackPageMode.DetailFullscreen(sessionActive = true),
            PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = true),
            PlaybackPageMode.DetailFullscreen(sessionActive = true, landscape = true),
            story,
            PlaybackPageMode.Story(landscape = true)
        )
        for (mode in allModes) {
            assertFalse(
                "★不合法合成态「会话激活但不全屏」必须不可表达，实际 mode=$mode " +
                    "(isFull=${mode.isFull}, swipeSessionActive=${mode.swipeSessionActive})",
                mode.swipeSessionActive && !mode.isFull
            )
            // 全屏是"锁竖屏"的必要条件之一（紧凑态一律不锁）
            assertFalse(
                "紧凑态不得锁竖屏，实际 mode=$mode",
                !mode.isFull && mode.locksPortrait
            )
            // 自动横屏必须蕴含全屏
            assertFalse(
                "自动横屏必须蕴含全屏，实际 mode=$mode",
                mode.allowsAutoLandscape && !mode.isFull
            )
        }
    }

    /**
     * 编解码必须**往返一致**（进程重建不能掉档）。
     *
     * 重构前 4 个布尔各有自己的 `rememberSaveable`，重建后由**各自的初值**重新推导，
     * 存在"模式对了但横屏没了对不上"的错档；现在整个状态是一个原子值。
     *
     * 验的是 [PlaybackPageMode.encode] / [PlaybackPageMode.decode]（纯函数）——
     * `Saver` 只是它的薄适配，不必在单测里构造 `SaverScope`（那会把 Compose 依赖
     * 引进纯逻辑测试）。
     */
    @Test
    fun saverRoundTripsEveryMode() {
        val modes = listOf(
            compact,
            PlaybackPageMode.DetailFullscreen(),
            PlaybackPageMode.DetailFullscreen(swipeRequested = true),
            PlaybackPageMode.DetailFullscreen(sessionActive = true, landscape = true),
            PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = true),
            story,
            PlaybackPageMode.Story(landscape = true)
        )
        for (mode in modes) {
            val encoded = PlaybackPageMode.encode(mode)
            val decoded = PlaybackPageMode.decode(encoded)
            assertEquals("编解码往返必须逐字段一致（encoded=$encoded）", mode, decoded)
        }
        // 未知 / 缺失编码必须保守回 Compact，而不是崩溃或卡在坏状态
        assertEquals(
            "未知编码必须保守回到 Compact",
            compact, PlaybackPageMode.decode("garbage")
        )
        assertEquals("null 编码必须回 Compact（首次组合）", compact, PlaybackPageMode.decode(null))
        // 自检：编码确实**区分**了不同模式（否则上面的往返断言是恒真的）
        val distinct = modes.map(PlaybackPageMode::encode).distinct()
        assertEquals(
            "编码必须能区分这 ${modes.size} 种模式，实际只产出 ${distinct.size} 种：$distinct",
            modes.size, distinct.size
        )
    }

    // ── 第 35 轮补齐：迁移后的**完整状态**断言 ─────────────────────────────────
    //
    // 上一版测试的问题不是"没测"，而是**测得太窄**：几乎每条迁移只断言一个布尔或一个
    // 工厂值，没有一个用例在迁移后把全部派生量（isFull / swipeSessionActive /
    // swipeModeRequested / landscapeForced / isStory / locksPortrait / allowsAutoLandscape）
    // 一起钉住。状态机的价值恰恰在"整体"，只钉一个字段等于允许其余字段在迁移里走样。
    //
    // 另有三处**真实缺口**（不是风格问题）：
    // 1. `allowsAutoLandscape` 只被断言过**为假**（detailSwipeRoundTrip / staleBooleans…），
    //    它**为真**的那条合法路径（全屏钮进单视频全屏 → 自动横屏）从未被验证；
    // 2. story 带横屏时退出、swipe 带横屏时退出，两条"一次清多个字段"的路径没测；
    // 3. 返回键只测了三种**模式**，没测「面板开着 / 横屏中」这两种**现场**。

    /**
     * 路径 1 的补全：story（含手动横屏）→ 退出 story → 详情，**一次迁移清干净**。
     *
     * 为什么值得单测：重构前这里是三次独立赋值（`isFull=false` /
     * `swipeSessionActive=false` / `swipeLandscape=false`），中间任何一次重组都会读到
     * "半截态"——例如 `isFull=false` 但 `swipeSessionActive=true`，正是 pager 分支
     * 与详情分支各自算出不同结论的那一拍。现在只可能整体落到 Compact。
     */
    @Test
    fun storyExitDropsEverythingInOneTransition() {
        val storyLandscape = PlaybackPageMode.Story(landscape = true)
        // 前置：确认这就是"story 会话里用户点了横屏"的那一刻
        assertTrue("前置：story 横屏必须可被页面读到", storyLandscape.landscapeForced)
        assertTrue("前置：story 恒全屏", storyLandscape.isFull)
        assertTrue("前置：story 恒会话激活", storyLandscape.swipeSessionActive)
        assertTrue("前置：story 恒锁竖屏（横屏是显式覆盖，不是解除锁）", storyLandscape.locksPortrait)

        val after = storyLandscape.exitFullscreen()

        // 完整状态：直接钉工厂值，而不是逐个布尔（后者会漏掉新加的派生量）
        assertEquals(
            "退出 story 必须**整体**落回 Compact —— 逐字段复位是重构前的写法，" +
                "它产生的中间态是「画面忽然跳回去」的来源",
            compact, after
        )
        assertFalse("isFull 必须为假", after.isFull)
        assertFalse("swipeSessionActive 必须为假", after.swipeSessionActive)
        assertFalse("swipeModeRequested 必须为假（一次性请求随退出清掉）", after.swipeModeRequested)
        assertFalse("landscapeForced 必须为假（切走即收回横屏）", after.landscapeForced)
        assertFalse("isStory 必须为假", after.isStory)
        assertFalse("locksPortrait 必须为假（不再锁竖屏）", after.locksPortrait)
        assertFalse("allowsAutoLandscape 必须为假（Compact 不全屏）", after.allowsAutoLandscape)
        assertEquals(
            "退出 story 后返回键必须交给宿主栈（popPage / 关页），而不是再次 ClosePage",
            PlaybackPageMode.BackAction.PassThrough, after.backAction()
        )
    }

    /**
     * 路径 2 的补全：详情 → 进 swipe → 手动横屏 → 退出 swipe → 详情。
     *
     * 断言的是**退出后不留任何残留**：请求（一次性）、横屏（只对点名那一支生效）、
     * 会话激活（依赖锚点/队列）三者必须一起消失。重构前它们分属三个不同作用域的
     * 变量，谁先谁后由 effect 执行顺序决定 —— 这是"退出后还是横屏/还是在连播"的来源。
     */
    @Test
    fun swipeExitDropsRequestLandscapeAndSession() {
        val entered = compact
            .requestSwipeSession()
            .resolveSwipeSession(queueNotEmpty = true)
            .toggleLandscape()
        assertEquals(
            "前置：下滑进 swipe + 队列非空 + 手动横屏 必须累积成同一个完整态",
            PlaybackPageMode.DetailFullscreen(
                swipeRequested = true, sessionActive = true, landscape = true
            ),
            entered
        )

        val afterExit = entered.exitFullscreen()
        assertEquals("退出 swipe 必须整体回到 Compact（详情页）", compact, afterExit)
        assertFalse("请求必须被清掉（否则下一次按钮进全屏会复用手势请求）", afterExit.swipeModeRequested)
        assertFalse("会话激活必须被清掉", afterExit.swipeSessionActive)
        assertFalse("横屏必须被收回", afterExit.landscapeForced)
        assertFalse("不再锁竖屏", afterExit.locksPortrait)

        // 只带请求（未激活）时退出，同样必须回 Compact —— 不能留下"全屏 + 请求"的半截态
        assertEquals(
            "「已请求但未激活」时退出也必须回 Compact",
            compact, compact.requestSwipeSession().exitFullscreen()
        )
        // 队列为空导致的"单视频全屏"退出的结果也一样
        assertEquals(
            "队列为空的全屏（未激活）退出同样回 Compact",
            compact, compact.enterFullscreen().resolveSwipeSession(false).exitFullscreen()
        )
    }

    /**
     * 路径 3 的补全：`allowsAutoLandscape` 的**真值**路径，以及手动横屏与两条锁判据的关系。
     *
     * 上一版测试只断言过 `allowsAutoLandscape == false`（请求空窗 / 会话激活 / story），
     * 于是"全屏钮进单视频全屏 → 允许自动横屏"这条**唯一合法真值**从未被验证：
     * 它一旦被写成恒假，`VideoScreen` 的方向 effect 就再也不会自动横屏，而单测全绿。
     */
    @Test
    fun swipeSessionLocksPortraitAndRefusesAutoLandscape() {
        // ① 全屏钮（无请求、无会话）：全屏但不锁竖屏 ⇒ 唯一允许自动横屏的形态
        val plain = compact.enterFullscreen()
        assertTrue("单视频全屏必须 isFull", plain.isFull)
        assertFalse("单视频全屏不锁竖屏", plain.locksPortrait)
        assertTrue(
            "★这是 allowsAutoLandscape 唯一的合法真值：全屏 && 未锁竖屏。" +
                "它若恒假，方向 effect 的「单视频全屏自动横屏」分支永远进不去，而所有" +
                "「必须为假」的断言仍然全绿 —— 上一版测试的盲区就在这里",
            plain.allowsAutoLandscape
        )

        // ② 请求置位（会话未激活的空窗）：立刻锁竖屏、自动横屏让位
        val requested = plain.requestSwipeSession()
        assertTrue("★空窗期也必须锁竖屏（真机闪跳的根因）", requested.locksPortrait)
        assertFalse("锁竖屏后不得允许自动横屏", requested.allowsAutoLandscape)

        // ③ 会话激活：仍然锁竖屏
        val active = requested.resolveSwipeSession(queueNotEmpty = true)
        assertTrue("会话激活后继续锁竖屏", active.locksPortrait)
        assertFalse("会话激活后不自动横屏", active.allowsAutoLandscape)

        // ④ 横屏往返：手动横屏**不改变**锁判据（方向由 when 的首个命中分支决定）
        val landscapeOn = active.toggleLandscape()
        assertEquals(
            "横屏态必须只翻 landscape 一个字段",
            PlaybackPageMode.DetailFullscreen(
                swipeRequested = true, sessionActive = true, landscape = true
            ),
            landscapeOn
        )
        assertTrue("横屏态仍锁竖屏（只是给 SENSOR_LANDSCAPE 一条显式通道）", landscapeOn.locksPortrait)
        assertFalse("横屏态不自动横屏", landscapeOn.allowsAutoLandscape)
        assertEquals(
            "横屏钮往返必须回到原态（其余字段一个不动）", active, landscapeOn.toggleLandscape()
        )
        assertEquals(
            "切视频只收回横屏、不解除锁（会话还在，节奏仍归 pager）",
            active, landscapeOn.onVideoChanged()
        )

        // ⑤ story：常驻全屏 + 常驻锁竖屏 ⇒ 永不自动横屏（横屏只能手动点名）
        assertTrue("story 恒锁竖屏", story.locksPortrait)
        assertFalse("story 永不自动横屏", story.allowsAutoLandscape)
        assertFalse("story 手动横屏也不自动横屏", story.toggleLandscape().allowsAutoLandscape)
    }

    /**
     * 路径 4 的补全：返回键在「面板开着 / 横屏中 / 普通详情」三种**现场**下的取值。
     *
     * ⚠️ 这里必须说清一件上一版测试没交代的事：**状态机没有"面板"这个概念**。
     * `backAction()` 只回答"页面模式允许什么动作"，而"面板先关一层面板"的优先级
     * 在 `PortraitSwipePager` 自己的 `BackHandler` + `SwipeBackPolicy` 里
     * （`player/PortraitSwipePager.kt:523-539`，后注册的 handler 优先）。
     * 所以"面板开着"时状态机的答案**不变**，这正是当前行为；测试按当前行为写，
     * 并留 TODO 说明真正的面板优先级该在哪里被验证。
     */
    @Test
    fun backActionInPanelLandscapeAndPlainDetail() {
        // ① 普通详情（紧凑态）：交给宿主栈
        assertEquals(
            "普通详情：返回交给宿主栈（popPage / 关页）",
            PlaybackPageMode.BackAction.PassThrough, compact.backAction()
        )
        // ② 普通详情全屏（全屏钮进入，无 swipe 请求）：形变回缩
        assertEquals(
            "普通详情全屏：返回 = 退出全屏",
            PlaybackPageMode.BackAction.ExitFullscreen, compact.enterFullscreen().backAction()
        )
        assertEquals(
            "只带请求的全屏（会话未激活）：返回同样是退出全屏",
            PlaybackPageMode.BackAction.ExitFullscreen,
            compact.requestSwipeSession().backAction()
        )

        // ③ 横屏中：详情全屏 + 手动横屏 ⇒ 仍是退出全屏（横屏只收横屏，不关页）
        val detailLandscape = PlaybackPageMode.DetailFullscreen(
            swipeRequested = true, sessionActive = true, landscape = true
        )
        assertEquals(
            "详情全屏横屏中：返回 = 退出全屏（不是关页）",
            PlaybackPageMode.BackAction.ExitFullscreen, detailLandscape.backAction()
        )
        // story + 横屏：story 没有紧凑态 ⇒ 返回仍关闭页面（横屏不改变这条）
        assertEquals(
            "story 横屏中：返回 = 关闭页面（story 没有紧凑态可回）",
            PlaybackPageMode.BackAction.ClosePage,
            PlaybackPageMode.Story(landscape = true).backAction()
        )

        // ④ 面板开着：状态机不知道面板，答案不变；面板优先由 pager 的 BackHandler 处理
        //
        // TODO(第 35 轮核对)：若以后要求"面板开着时返回键先关面板"，正确的落点**不是**
        // 给 backAction() 加参数 —— 那会把 UI 局部状态（sheet 可见性）塞进页面模式状态机，
        // 重演"5 个布尔互相推导"的老路。该优先级已经在
        // `core/swipe/.../SwipeBackPolicy.kt`（纯判据，`resolve(anySheetOpen, inSheetCloseGrace)`）
        // 里实现，并由 `core/swipe/src/test/.../SwipeBackPolicyTest.kt` 单独覆盖。
        // 这里只需钉住"状态机不掺和"这一点。
        assertEquals(
            "面板开着时状态机答案不变（面板优先级不在本状态机内，见 TODO）",
            PlaybackPageMode.BackAction.ExitFullscreen, detailLandscape.backAction()
        )
        assertEquals(
            "紧凑态 + 面板打开时同样会是 PassThrough（面板由宿主/pager 先消费）",
            PlaybackPageMode.BackAction.PassThrough, compact.backAction()
        )
    }

    /**
     * 不该发生的迁移必须**原地不动**（幂等性），否则页面里的接线 effect 会在
     * 不合适的时机把状态推向别的模式。
     *
     * 这些是"反向"用例：上一版只测了正向迁移，等于允许迁移函数在错误入参下乱改状态。
     */
    @Test
    fun storyAndCompactIgnoreSwipeTransitions() {
        // story 已常驻全屏/会话，再"进全屏 / 请求 swipe / resolve"都必须无动作
        assertEquals("story 上 enterFullscreen 必须原地不动", story, story.enterFullscreen())
        assertEquals("story 上 requestSwipeSession 必须原地不动", story, story.requestSwipeSession())
        assertEquals(
            "story 上 resolveSwipeSession 必须原地不动（队列判据不参与 story）",
            story, story.resolveSwipeSession(queueNotEmpty = true)
        )
        assertEquals(
            "story 带横屏时 resolve 也必须原地不动",
            PlaybackPageMode.Story(landscape = true),
            PlaybackPageMode.Story(landscape = true).resolveSwipeSession(queueNotEmpty = true)
        )

        // Compact 上只有 enter/request 有意义，其余一律原地不动
        assertEquals("Compact 上 resolveSwipeSession 必须原地不动", compact, compact.resolveSwipeSession(true))
        assertEquals("Compact 上 onVideoChanged 必须原地不动", compact, compact.onVideoChanged())
        assertEquals("Compact 上 toggleLandscape 必须原地不动", compact, compact.toggleLandscape())
        assertEquals("Compact 上 exitFullscreen 必须原地不动", compact, compact.exitFullscreen())

        // 切换视频：详情全屏只收横屏，必须**保留**请求与会话（否则翻页会退出连播）
        assertEquals(
            "切视频必须只收回横屏，请求与会话原样保留（否则会话内翻页会掉出 swipe）",
            PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = true),
            PlaybackPageMode.DetailFullscreen(
                swipeRequested = true, sessionActive = true, landscape = true
            ).onVideoChanged()
        )
    }

    /**
     * 前台真实存在的一处**语义不自洽**（第 35 轮核对发现，按"当前行为"钉住，**不改状态机**）。
     *
     * 会话未激活时 `toggleLandscape()` 照样把 `landscape = true` 写进状态，但
     * `landscapeForced` 读的是 `sessionActive && landscape` ⇒ 这个横屏**当场看不见**；
     * 而随后 `resolveSwipeSession(queueNotEmpty = true)` 一旦激活，它又**突然生效**。
     *
     * 真机不可达：会话未激活 ⇒ `PortraitSwipePager` 分支不成立（pager 没渲染），
     * 横屏圆钮点不到。所以是"状态层允许、UI 层不可达"的形态，属于可接受的技术债；
     * 但一旦有人把横屏入口挪到 pager 之外（例如顶栏），这里就会变成真 bug。
     *
     * TODO(第 35 轮)：若届时需要收紧，应在 `toggleLandscape()` 里判
     * `sessionActive || isStory` 再翻转（让不可见状态根本写不进去），而不是只改派生量。
     */
    @Test
    fun landscapeToggledBeforeActivationIsStoredButInvisible() {
        val notActive = PlaybackPageMode.DetailFullscreen(
            swipeRequested = true, sessionActive = false
        )
        val toggled = notActive.toggleLandscape()
        assertEquals(
            "当前行为：未激活时 toggleLandscape 仍写入 landscape",
            PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = false, landscape = true),
            toggled
        )
        assertFalse(
            "当前行为：未激活时 landscapeForced 看不见这个横屏（sessionActive && landscape）",
            toggled.landscapeForced
        )
        assertTrue(
            "★当前行为：一旦激活，之前那次点击「复活」——这就是不自洽点（TODO 见 KDoc）",
            toggled.resolveSwipeSession(queueNotEmpty = true).landscapeForced
        )
    }

    /**
     * 穷举**全部可构造模式**（Compact + DetailFullscreen 的 2³ + Story 的 2¹ = 11），
     * 逐个验证派生量之间的不变式。
     *
     * 为什么值得：逐条迁移测试只能证明"走过的路是对的"，穷举不变式能证明
     * **派生量之间没有互相打架的定义** —— 这正是 sealed 状态机相对 5 个布尔的核心收益。
     *
     * 注意这里刻意用 `buildList` 手工枚举**全部构造组合**（含迁移到不了的组合），
     * 因为派生量是对所有实例定义的。迁移**可达性**另由
     * [unreachableCombinationsCannotBeReachedByMigrations] 用迁移闭包证明。
     */
    @Test
    fun derivedInvariantsHoldForEveryFieldCombination() {
        val modes = buildList<PlaybackPageMode> {
            add(compact)
            for (requested in listOf(false, true)) {
                for (active in listOf(false, true)) {
                    for (landscape in listOf(false, true)) {
                        add(
                            PlaybackPageMode.DetailFullscreen(
                                swipeRequested = requested,
                                sessionActive = active,
                                landscape = landscape
                            )
                        )
                    }
                }
            }
            add(story)
            add(PlaybackPageMode.Story(landscape = true))
        }
        assertEquals("可构造模式穷举必须是 1 + 8 + 2 种", 11, modes.size)

        for (mode in modes) {
            // ① isFull 的定义只取决于"是不是 Compact"
            assertEquals(
                "isFull 必须等价于「非 Compact」，mode=$mode",
                mode !is PlaybackPageMode.Compact, mode.isFull
            )
            // ② 会话激活必然全屏（重构前那个非法合成态）
            assertFalse("会话激活必须蕴含全屏，mode=$mode", mode.swipeSessionActive && !mode.isFull)
            // ③ 锁竖屏必然全屏（紧凑态一律不锁）
            assertFalse("锁竖屏必须蕴含全屏，mode=$mode", mode.locksPortrait && !mode.isFull)
            // ④ 自动横屏的定义式（= 全屏 && 未锁竖屏）
            assertEquals(
                "allowsAutoLandscape 必须严格等于「全屏 && 未锁竖屏」，mode=$mode",
                mode.isFull && !mode.locksPortrait, mode.allowsAutoLandscape
            )
            // ⑤ 一次性请求只可能来自 DetailFullscreen.swipeRequested
            assertFalse(
                "swipeModeRequested 只可能来自 DetailFullscreen.swipeRequested，mode=$mode",
                mode.swipeModeRequested && mode !is PlaybackPageMode.DetailFullscreen
            )
            // ⑥ 横屏只可能是全屏态
            assertFalse("landscapeForced 必须蕴含全屏，mode=$mode", mode.landscapeForced && !mode.isFull)
            // ⑦ story 恒全屏 + 恒会话 + 恒锁竖屏
            if (mode.isStory) {
                assertTrue("story 必须恒全屏，mode=$mode", mode.isFull)
                assertTrue("story 必须恒会话激活，mode=$mode", mode.swipeSessionActive)
                assertTrue("story 必须恒锁竖屏，mode=$mode", mode.locksPortrait)
                assertFalse("story 不得报告一次性请求（它没有详情全屏的来路）", mode.swipeModeRequested)
            }
        }
    }

    /**
     * 迁移**闭包**：从两个初始态出发，反复施加全部迁移函数，可达集合必须是那 9 种。
     *
     * 为什么这条比逐条迁移测试更值：它同时证明了两件事 ——
     * ① 每种**不可达**组合（例如 `DetailFullscreen(sessionActive=true, swipeRequested=false)`，
     *    也就是"会话激活却没有请求"）真的**走不到**；
     * ② 迁移函数不会把状态推到枚举之外（例如某个分支返回了没预期的字段组合）。
     */
    @Test
    fun unreachableCombinationsCannotBeReachedByMigrations() {
        fun neighbours(mode: PlaybackPageMode): List<PlaybackPageMode> = listOf(
            mode.enterFullscreen(),
            mode.requestSwipeSession(),
            mode.resolveSwipeSession(queueNotEmpty = true),
            mode.resolveSwipeSession(queueNotEmpty = false),
            mode.exitFullscreen(),
            mode.toggleLandscape(),
            mode.onVideoChanged(),
        )

        val seen = linkedSetOf<PlaybackPageMode>()
        val queue = ArrayDeque<PlaybackPageMode>()
        listOf(PlaybackPageMode.initial(storyMode = false), PlaybackPageMode.initial(storyMode = true))
            .forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val mode = queue.removeFirst()
            if (!seen.add(mode)) continue
            neighbours(mode).forEach { next ->
                // 只入队**没见过的**值（否则每个节点都会把邻居反复塞回去 → 永不收敛）；
                // 另加容量护栏：状态只有 11 种，闭包不该无限展开（若迁移函数会造新值这里会爆）
                if (next !in seen && seen.size < 32) queue.addLast(next)
            }
        }
        assertTrue("闭包展开必须收敛到小集合，实际 ${seen.size} 种", seen.size <= 11)

        val expected = setOf(
            compact,
            story,
            PlaybackPageMode.Story(landscape = true),
            // 无请求、无会话（「全屏」钮进入的单视频全屏）：横屏位由 toggle 可达，但不可见
            PlaybackPageMode.DetailFullscreen(),
            PlaybackPageMode.DetailFullscreen(landscape = true),
            // 有请求、无会话（等锚点/队列的空窗）
            PlaybackPageMode.DetailFullscreen(swipeRequested = true),
            PlaybackPageMode.DetailFullscreen(swipeRequested = true, landscape = true),
            // 有请求、有会话（真在刷）
            PlaybackPageMode.DetailFullscreen(swipeRequested = true, sessionActive = true),
            PlaybackPageMode.DetailFullscreen(
                swipeRequested = true, sessionActive = true, landscape = true
            ),
        )
        assertEquals(
            "迁移闭包必须恰好是这 9 种 —— 多出来的是迁移函数造了计划外的状态，" +
                "少了的是某条迁移路径断了。实际=$seen",
            expected, seen
        )

        // 反向：那 2 种「没有请求却会话激活」的组合必须**走不到**
        // （它们是重构前 5 个布尔可以拼出的非法合成态；`sessionActive` 只由
        //  `resolveSwipeSession` 写，而它恒等于 `swipeRequested && queueNotEmpty`）
        val impossible = seen.filter { it is PlaybackPageMode.DetailFullscreen && it.sessionActive && !it.swipeRequested }
        assertEquals(
            "「会话激活却没有一次性请求」必须不可达，实际=$impossible",
            emptyList<PlaybackPageMode>(), impossible
        )

        // 自动横屏在可达域里恰好是"单视频全屏"两类（横屏位是否置上都不影响，
        // 因为会话没激活时 landscapeForced 本来就是假的）
        val auto = seen.filter { it.allowsAutoLandscape }.toSet()
        assertEquals(
            "可达域里允许自动横屏的必须只有「全屏钮进入的单视频全屏」（横屏位置没置都一样），实际=$auto",
            setOf(
                PlaybackPageMode.DetailFullscreen(),
                PlaybackPageMode.DetailFullscreen(landscape = true)
            ),
            auto
        )
        // 而其中"横屏位置上了"的那个，landscapeForced 必须是假 —— 横屏只对激活会话可见，
        // 这正是 [landscapeToggledBeforeActivationIsStoredButInvisible] 记录的不自洽点。
        assertFalse(
            "未激活时横屏位是休眠的（landscapeForced=false）",
            PlaybackPageMode.DetailFullscreen(landscape = true).landscapeForced
        )
    }

    // ── 初始态 ───────────────────────────────────────────────────────────────

    @Test
    fun initialModeFollowsStoryFlag() {
        assertEquals(
            "非 story 入口（详情 / 覆盖层）必须以 Compact 起步 —— 与重构前 `mutableStateOf(false)` 等价",
            compact, PlaybackPageMode.initial(storyMode = false)
        )
        assertEquals(
            "story 入口必须以 Story 起步（常驻全屏）",
            story, PlaybackPageMode.initial(storyMode = true)
        )
        // 非 story 入口的「全屏」钮只进全屏
        assertFalse(
            "Compact 起步进全屏不得带刷视频请求（R3）",
            PlaybackPageMode.initial(false).enterFullscreen().swipeModeRequested
        )
    }
}
