package com.naaammme.bbspace.core.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * 间距 token，仅在新代码与 UI 改造中逐步采用，不要求全局替换既有内联 dp。
 *
 * 阶段 A（iOS 升级 2026-09-25）补齐 6/10dp 两档：此前它们是仅有的
 * "高频使用却没有 token"的间距值（6dp×111 处、10dp×80 处，见 DesignTokenLintTest
 * 的棘轮记录），栅格列距 / 芯片内边距等语义从此有了单一改动点。
 */
object BiliSpacing {
    val xs = 4.dp
    val xsPlus = 6.dp
    val sm = 8.dp
    val smPlus = 10.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/**
 * 视频详情页**画面与简介区之间的几何**（单一来源）。
 *
 * ## 为什么必须只有一处定义
 * 「播放画面底边 → 简介区首个内容块」这段间距在页面上有**两个状态**共用：
 * 真实内容（`VideoDetailPage` 的 `LazyColumn.contentPadding.top`）与加载骨架
 * （`VideoDetailInfoSkeleton` 的 `padding(top)`）。骨架在 `core:designsystem`、
 * 调用点在 `feature:video`，值只能定义在两边都依赖的这一层 —— 任何一侧单独改数，
 * 都会在"骨架 → 真实内容"的交接处整块跳一格。
 *
 * ## 这一版改了什么（2026-10-12，用户第二次反馈「标题与视频相隔很远，给人很空的感觉」）
 *
 * 上一版（2026-10-11）的做法是在 `16dp + contentTopPad(=statusTop)` 上减掉 24dp。
 * 那个式子的**结构**是错的，不是数值不够大：
 *
 * 1. `contentTopPad` 取的是**状态栏高度**（本机 1272×2800 @3.5 实测 141px ≈ 40dp）。
 *    它是"按量级抄"来的，不是几何必需 —— 画面底边本来就远低于状态栏，不存在遮挡。
 *    于是"画面与正文之间留多少白"这件事，被一个**与设计无关的系统量**决定了。
 * 2. 减掉 24dp 后仍有 `16 + 40 − 24 ≈ 32dp` 的净间距，加上标题行盒的行距（titleLarge
 *    28sp 行高 / 22sp 字号 ⇒ 上方多出 ≈3dp），首行字形离画面底边约 35dp ——
 *    真机实测确实是"一整条空带"。
 * 3. 更糟的是骨架屏：它只知道 `BiliSpacing.lg`（16dp），**看不到 statusTop 这个系统 inset**。
 *    上一版把骨架改成 `(lg − TITLE_LIFT)` 又被 `coerceAtLeast(0.dp)` 夹到 0，而真实内容
 *    是 `16 + 40 − 24 = 32dp` —— 用户装的那版里两者**差 32dp**，详情一到就整块往下跳一格。
 *    这正是用户说的「骨架动画也需要相应调整」。
 *
 * 这一版把间距还给设计：**一个常量、两个状态、零算术**。40dp 的系统量彻底退出，
 * 骨架与真实内容引用同一个值，跳版在结构上不可能再发生。
 */
object BiliDetailLayout {
    /**
     * 画面底边 → 简介区首个内容块（标题块）的净间距。
     *
     * 取 [BiliSpacing.lg]（16dp）：主流客户端（B 站 / YouTube / 抖音）的"播放画面 →
     * 正文标题"呼吸量级都在 12~20dp。加上标题行盒自带的行距，首行字形离画面底边
     * 约 19dp —— 既贴得够近、不会读成"上半屏是空的"，也不会挤到画面下沿。
     */
    val INTRO_TOP_GAP = BiliSpacing.lg
}

/**
 * **卡片密度口径**：同一批数值，但从此只有一个改动点。
 *
 * ## 为什么要单独一层名字（而不是直接用 [BiliSpacing]）
 * 首页信息流卡与排行页卡的封面/信息区此前各自内联 `8.dp / 6.dp / 10.dp / 12.dp`，
 * 两边的注释都点过名："两处不同步就会出现首页贴边、排行页留白这种不一致"。
 * 光换成 `BiliSpacing.sm` 解决不了这件事——那仍是"两个页面各自挑一档"，
 * 下一个改密度的人还得两页各查一遍。语义名字（信息区内边距 / 页边距 / 标题到 meta）
 * 才是"改一处、全站卡片同步"的那个把手。
 *
 * ## 刻意**不新增任何 dp 字面量**
 * 全部指回 [BiliSpacing] 既有档位：`DesignTokenLintTest` 的棘轮统计的是字面量出现次数，
 * 这一层把调用点的字面量**净减少**（棘轮随之下降，见该测试的上限注释）。
 *
 * 数值本身与改动前逐像素一致 —— 这批改动不动观感，只把观感的旋钮收拢到一个位置。
 */
object BiliCardDensity {
    /** 卡信息区左右内边距（原 `8.dp` = [BiliSpacing.sm]）。 */
    val infoHorizontal = BiliSpacing.sm

    /** 卡信息区上下内边距（原 `6.dp` = [BiliSpacing.xsPlus]）。 */
    val infoVertical = BiliSpacing.xsPlus

    /** 标题 → meta 行的间距（原 `Spacer(4.dp)` = [BiliSpacing.xs]）。 */
    val titleToMeta = BiliSpacing.xs

    /** 网格页的左右边距（原 `10.dp` = [BiliSpacing.smPlus]）。 */
    val pageHorizontal = BiliSpacing.smPlus

    /** 网格页的底部留白（原 `12.dp` = [BiliSpacing.md]）。 */
    val pageBottom = BiliSpacing.md

    /** 封面上角标胶囊的内边距（原 `horizontal 12 / vertical 6`）。 */
    val badgeHorizontal = BiliSpacing.md
    val badgeVertical = BiliSpacing.xsPlus

    /**
     * 栅格**列间距**（`AdaptiveMediaGrid` 的全站默认值，原 `10.dp`）。
     * 与 [pageHorizontal] 同值是刻意的一致性：页边距必须 ≥ 列间距，否则两列会被
     * 读成"贴边溢出"而不是"在页边距里排布"（见该文件的原始注释）。
     */
    val gridSpacing = BiliSpacing.smPlus

    /** 栅格**行间距**（原 `12.dp`）。行距 > 列距是现代信息流"呼吸感"的来源。 */
    val rowSpacing = BiliSpacing.md
}
