package com.naaammme.bbspace.feature.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「播放内核全面 SurfaceView」的接线守卫（2026-10-07）。
 *
 * ## 一、为什么需要这些断言（一次真机事故换来的）
 *
 * SurfaceView 是**独立图层**：只要祖先里有 `Modifier.clip(...)` 或非恒等的 `graphicsLayer{}`，
 * surface 就会被挖空 —— **画面全黑、声音与进度正常**。第一版只换了 `surface_type`，
 * 于是"内嵌黑、全屏正常"（差别正是那层圆角裁剪）。
 *
 * 因此全面启用 SurfaceView 的前提是：视频路径上**一个图层都不能有**：
 * | 原来 | 现在 |
 * |---|---|
 * | `clip(圆角)` | **播放窗口改直角（用户定稿）** ⇒ 不需要任何裁剪层 |
 * | `graphicsLayer { scale/translate }`（捏合缩放） | 写成 View 级 `scaleX/scaleY/translationX/Y` |
 * | `graphicsLayer { translationY }`（进全屏的形变补偿） | layout 期 `Modifier.offset {}` |
 * | `graphicsLayer { alpha/scale }`（进全屏淡入缩放） | 省电模式下整段跳过（瞬时到位） |
 * | 控件真模糊采样视频画面 | 按「模糊关」下发（画面不在应用图层，采不到） |
 *
 * 唯一保留 TextureView 的地方：刷视频 Pager（页面级缩放/透明动画必须图层）。
 *
 * ## 二、几何不变量（两个真机 bug 的固化）
 *
 * 1. **状态栏内缩只能有一个来源**：外层算 `statusTop`，播放页就直接用这个值
 *    （曾经两处各自解析：外层 141、页内 71 ⇒ 播放页多出 71px 黑、画面被挤成 letterbox）；
 * 2. **画面框高度 = `videoMorphPx`**：`playerMorphHeight` 给的是**整页高度**，
 *    所以它必须在外层，顶部内缩（`.padding(top = statusTop)`）加在**内层**；
 *    顺序反了内层高度表达式就整个变成"画面框" ⇒ 画面框 = `videoMorphPx + statusTop`
 *    （真机 dump：`PlayerView 1272×856` = 716 + 141）⇒ 16:9 画面被 FIT 居中、
 *    上下各一条 71px 黑边，且播放页越过播放带底边 141px 压到简介区。
 * 3. 播放带底边 = `statusTop + videoMorphPx`（= 画面底边）；
 * 4. 视频与简介之间的间距由**简介内容自己的 padding**给，且取值来自设计常量
 *    `BiliDetailLayout.INTRO_TOP_GAP`（骨架屏在 designsystem、看不到系统 inset，
 *    两侧必须共用同一个常量，否则详情到达时会跳版）；
 */
class PlayerSurfacePowersaveGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun sourceOf(fileName: String, ext: String = "kt"): String {
        val matches = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == ext }
            .filter { it.path.contains("src") && !it.path.contains("build") }
            .filter { it.name == fileName }
            .toList()
        assertEquals(
            "fileName=$fileName 命中 ${matches.size} 个文件（要求恰好 1）：" + matches.joinToString { it.path },
            1,
            matches.size
        )
        return matches.single().readText()
    }

    @Test
    fun bothSurfaceLayoutsExistWithExpectedType() {
        assertTrue(
            "默认布局必须保持 texture_view（关掉开关时的行为不变）",
            sourceOf("view_bili_player.xml", "xml").contains("app:surface_type=\"texture_view\"")
        )
        assertTrue(
            "省电模式布局必须是 surface_view —— 否则换了布局却没换合成方式，白改",
            sourceOf("view_bili_player_surface.xml", "xml").contains("app:surface_type=\"surface_view\"")
        )
    }

    @Test
    fun playerAreaIsSquareAndHasNoClipLayer() {
        val pane = sourceOf("VideoPlayerPane.kt")
        val screen = sourceOf("VideoScreen.kt")
        assertTrue(
            "播放窗口必须直角（用户定稿）：黑底就是矩形，不做任何 Compose clip",
            pane.contains(".background(Color.Black)") &&
                !pane.contains("Modifier.clip(playerCornerShape)") &&
                !pane.contains("Modifier.clip(PlayerWindowShape)")
        )
        assertTrue(
            "圆角机制整套不得回退（outline / 半径参数都已删）",
            !pane.contains("PlayerSurfaceOutlineProvider") &&
                !pane.contains("surfaceCornerRadius") &&
                !pane.contains("clipToOutline") &&
                !screen.contains("surfaceCornerRadius")
        )
        assertTrue(
            "播放带与展开布局的形状都必须直角",
            screen.contains("private val ExpandedPlayerShape = RectangleShape") &&
                screen.contains("private val CompactPlayerShape = RectangleShape")
        )
        assertTrue(
            "直角化不能让封面垫底变成死代码：`RectangleShape` 必须给出 0 半径" +
                "（`cornerPxOrNull` 返回 null 时 `CoverSettleBackdrop` 直接 return ⇒ 首帧前是纯黑）",
            screen.contains("if (this === RectangleShape) return BackdropCornerPx(0f, 0f, 0f, 0f)")
        )
    }

    @Test
    fun videoPathHasNoTransformLayers() {
        val pane = sourceOf("VideoPlayerPane.kt")
        assertTrue(
            "捏合缩放必须改写成 View 级变换（graphicsLayer 会建图层）",
            pane.contains("view.scaleX = zoomScale") && pane.contains("view.scaleY = zoomScale")
        )
        assertTrue(
            "非省电模式仍走原来的 graphicsLayer（不改变既有行为）",
            pane.contains("Modifier.graphicsLayer {") && pane.contains("scaleX = zoomState.scale")
        )
        val screen = sourceOf("VideoScreen.kt")
        assertTrue(
            "进全屏的形变补偿：省电模式用 layout 期 offset，老渲染保留 graphicsLayer",
            screen.contains("IntOffset(0, -(statusTopPx * fullscreenMorph.value).roundToInt())") &&
                screen.contains("translationY = -statusTopPx * fullscreenMorph.value")
        )
        assertTrue(
            "进全屏的淡入/缩放动画在省电模式下整段跳过（瞬时到位），且不误伤 Pager 宿主",
            screen.contains("animations.reduceMotion || (surfacePowersave == true && !pagerActive)") &&
                screen.contains("val pagerActive = swipeSessionActive && swipeAnchor != null")
        )
    }

    @Test
    fun geometryInvariants() {
        val pane = sourceOf("VideoPlayerPane.kt")
        val screen = sourceOf("VideoScreen.kt")
        assertTrue(
            "状态栏内缩只能有一个来源：播放页直接用外层的 statusTop（不再各自解析 inset）",
            screen.contains(".padding(top = statusTop)")
        )
        assertTrue(
            "播放页高度 = 画面高度 + statusTop（画面框才等于 videoMorphPx，满宽满框）",
            screen.contains(".playerMorphHeight { videoMorphPx() + statusTopPx }")
        )
        // **顺序守卫（2026-10-07 真机 dump 定案）**：`playerMorphHeight` 必须在外层、
        // 顶部内缩在内层。反过来写时内层高度表达式整个变成"画面框" ⇒
        // 画面框 = videoMorphPx + statusTop（真机 dump：PlayerView 1272×856 = 716 + 141），
        // 16:9 画面被 FIT 居中、上下各留 71px 黑边（用户报的"视频下方那条黑"），
        // 播放页还比播放带高 141px 压到简介区。改顺序=改几何，所以这里按索引钉死。
        val heightIdx = screen.indexOf(".playerMorphHeight { videoMorphPx() + statusTopPx }")
        val padIdx = screen.indexOf(".padding(top = statusTop)")
        assertTrue(
            "内嵌播放页调用点必须同时有这两句（索引 $heightIdx / $padIdx）",
            heightIdx >= 0 && padIdx >= 0
        )
        assertTrue(
            "`playerMorphHeight` 必须写在 `.padding(top = statusTop)` **之前**（外层）：" +
                "顺序反了 ⇒ 画面框多算一个 statusTop ⇒ 视频被 FIT 居中、上下各一条黑边",
            heightIdx < padIdx
        )
        assertTrue(
            "播放带高度 = statusTop + 画面高度（带底与画面底重合）",
            screen.contains("(statusTopPx + videoMorphPx()).coerceAtMost(maxHeightPx)")
        )
        assertTrue(
            "视频与简介的间距必须由简介内容自身 padding 给，不能用 offset 拉开" +
                "（那样中间会露出没有背景的黑段）。" +
                "**2026-10-12**：取值从 `statusTop`（状态栏高度）改为设计常量 " +
                "`BiliDetailLayout.INTRO_TOP_GAP` —— 用户第二次反馈「标题与视频相隔很远，" +
                "给人很空的感觉」。旧写法有两个结构性问题：① 间距由系统 inset 决定，" +
                "换机型就变；② 骨架屏 `VideoDetailInfoSkeleton` 在 designsystem、看不到 " +
                "statusTop，两侧永远差 ≈40dp，详情到达时整块内容向下跳一格。",
            screen.contains("introTopGap = BiliDetailLayout.INTRO_TOP_GAP") &&
                !screen.contains("contentTopPad = statusTop") &&
                !screen.contains("detailTopGapPx")
        )
        assertTrue(
            "根层必须铺主题背景色：铺黑或留空都会在播放区域的角上露出直角黑边",
            screen.contains(".background(MaterialTheme.colorScheme.background)")
        )
        assertTrue(
            "诊断：surface 类型必须可查（真机黑屏类问题只有它能一眼看出）",
            pane.contains("DiagnosticLog.i(\"PlayerSurface\")") && pane.contains("videoSurfaceView?.javaClass?.simpleName")
        )
    }

    @Test
    fun onlyPagerKeepsTextureView() {
        val pager = sourceOf("PortraitSwipePager.kt")
        val screen = sourceOf("VideoScreen.kt")
        assertTrue(
            "刷视频 Pager 的页面级缩放/透明动画必须图层 ⇒ 显式禁 SurfaceView，并写明原因",
            pager.contains("allowSurfaceView = false")
        )
        assertEquals(
            "宽屏展开与形变布局的图层问题已解决 ⇒ 不应再有其它禁用点",
            0,
            Regex("allowSurfaceView = false").findAll(screen).count()
        )
    }

    @Test
    fun powersaveForcesControlsBlurOff() {
        val pane = sourceOf("VideoPlayerPane.kt")
        assertTrue(
            "省电模式下必须把模糊按关处理（SurfaceView 的画面不在应用图层，玻璃采不到帧）",
            pane.contains("val effectiveControlsBlur = controlsBlur && !useSurfaceView")
        )
        assertTrue(
            "下发给叶子控件的也必须是 effectiveControlsBlur",
            pane.contains("LocalVideoControlsBlur provides effectiveControlsBlur")
        )
    }

    @Test
    fun enabledByDefaultAndWiredThroughUi() {
        val settings = sourceOf("AppSettings.kt")
        assertTrue(
            "键名固定（改动会让已存偏好丢失）",
            settings.contains("booleanPreferencesKey(\"player_surface_view\")")
        )
        assertTrue(
            "默认开（用户要求全面使用 SurfaceView）；关掉即回到 TextureView 老渲染",
            settings.contains("it[playerSurfaceViewKey] ?: true")
        )
        assertTrue(
            "写入方法必须存在且被设置页调用",
            settings.contains("suspend fun updatePlayerSurfaceView(enabled: Boolean)") &&
                sourceOf("SettingsViewModel.kt").contains("appSettings.updatePlayerSurfaceView(enabled)")
        )
        assertTrue(
            "设置页必须有入口（否则用户关不掉）",
            sourceOf("PerformanceSettingsScreen.kt").contains("省电模式（视频用 SurfaceView）")
        )
        assertTrue(
            "播放页与 VideoScreen 都要能读到它（外层布局要据此避开图层）",
            sourceOf("VideoViewModel.kt").contains("playerSettings.playerSurfaceView") &&
                sourceOf("VideoScreen.kt").contains("viewModel.playerSurfaceView.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "单次创建护栏：设置未读出前不建 PlayerView（否则会创建两次 ⇒ 首次进入黑屏）。" +
                "兜底值必须是 **true**（与 `AppSettings` 的出厂默认 `?: true` 同向）——" +
                "原先取 false 会让慢 DataStore 路径先建 TextureView 版、真值到达后再由 " +
                "`key(useSurfaceView)` 重建为 SurfaceView 版，恰好落回这条要防的「建两次」" +
                "（2026-10-11 审查，真机可复现黑帧）。",
            sourceOf("VideoPlayerPane.kt")
                .contains("val surfaceSetting = surfacePowersave ?: if (surfaceSettingTimedOut) true else null") &&
                sourceOf("VideoViewModel.kt").contains("StateFlow<Boolean?>")
        )
    }
}
