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
