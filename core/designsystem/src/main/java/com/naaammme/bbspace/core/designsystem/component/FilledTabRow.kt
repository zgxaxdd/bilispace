package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.miuix.AppMiuixSegmentedControl
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.rememberAppElasticPressState
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.LocalUiStyle
import com.naaammme.bbspace.core.designsystem.theme.UiStyle

/**
 * Material 分支选中胶囊的形状（**文件级常量**）。
 *
 * 提出来有两个原因，都要留着：
 * 1. `ListAllocationStructureTest.shapeAllocationBypassForms_areFrozenAtMeasuredBaseline`
 *    把全仓 `shape = RoundedCornerShape(` 的具名实参形态钉在上限里 —— 写在卡片参数上
 *    每次重组都会分配一份 Shape 对象，而且它正是那条棘轮要抓的写法；
 * 2. 18dp 是"高度一半 ⇒ 全圆端"的胶囊值（行高约 36dp），与播放器控件 `BiliRadius.playerControl`
 *    同一套 iOS 惯例算法，只是这里的行高不同 ⇒ 独立常量、不硬凑 token。
 */
private val TabPillShape = RoundedCornerShape(18.dp)

/**
 * 顶部 Tab，按界面风格分支：
 * - iOS：**委托 `AppMiuixSegmentedControl`**（miuix `TabRow`）——指示器位移由库内 spring 完成，
 *   本仓不再持有那段动画（原自绘 `IOSSegmented` 已在 2026-10-18 删除，见下方调用处注释）。
 * - Material：现代胶囊 Tab（未选中无底色、选中胶囊高亮），视觉与改造前一致。
 */
@Composable
fun FilledTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // 左右与搜索行**同一个** `BiliSpacing.md`（12dp）栅格：顶栏里两行 chrome 的边缘
    // 必须同列，否则滚动时能看出"上面一颗胶囊、下面一条通栏"的错位（真机可见）。
    contentPadding: PaddingValues = PaddingValues(
        start = BiliSpacing.md,
        end = BiliSpacing.md,
        top = 0.dp,
        bottom = 3.dp
    ),
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    if (LocalUiStyle.current == UiStyle.IOS) {
        // 2026-10-18（W2 批次 2）：iOS 分支改由 **Miuix 渲染层**绘制（`AppMiuixSegmentedControl`）。
        // 原来的自绘 `IOSSegmented`（BoxWithConstraints + animateDpAsState + offset{} 滑块）
        // 已删除：miuix 的 TabRow 内部就是"轨道内滑块 + 库内 spring"，语义与 iOS segmented
        // 一致，而且**本仓不再持有那段动画** —— `animate*AsState` 计数随之下降（棘轮只许降）。
        // ⚠️ 对应守卫 `ListAllocationStructureTest` 的两条断言已改写为"必须委托给本原语"。
        // ## 2026-10-19 真机（PLC110）：轨道改由**本层**画
        //
        // 现场：这一横列（FM/推荐/直播/排行/专栏）是一条**直角通栏**的灰带，
        // 与它上方 12dp 外边距 + 全圆端的搜索胶囊、下方 32dp 圆角的 Dock 完全不是一套语汇
        // ——用户原话「存在 bug 和 UI 风格不统一，没有圆角」。
        // 根因：miuix `TabRow` 的 `cornerRadius` 只作用于**选中滑块**，轨道底色
        // （`backgroundColor`）是由它内部的 Surface 铺的、不裁圆角，且宽高比整行铺满。
        //
        // 处置：把轨道收到本层——`clip(RoundedCornerShape(BiliRadius.searchPill))`
        // + 与搜索行**同一个** `BiliSpacing.md` 外边距，miuix 的轨道底色改成透明。
        // 这样圆角由我们保证（不依赖库版本），左右边缘也与搜索胶囊/底栏 Dock 对齐到
        // 同一条视觉中线。
        val trackShape = remember { RoundedCornerShape(BiliRadius.searchPill) }
        val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    start = BiliSpacing.md,
                    end = BiliSpacing.md,
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding()
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = (if (trailing == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.weight(1f)
                })
                    .clip(trackShape)
                    .background(trackColor)
            ) {
                AppMiuixSegmentedControl(
                    tabs = tabs,
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                    // 轨道已由外层画（见上），这里只画滑块与文字
                    transparentTrack = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            trailing?.invoke(this)
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = if (trailing == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.weight(1f)
                },
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    // ## 2026-10-18（S6）：未选中项**不再包 Card**
                    //
                    // 旧写法每个 Tab 都是 `Card(onClick, containerColor = Transparent)`：
                    // 未选中时底色虽透明，但 Card 仍带**自己的轮廓/高度/涟漪裁剪**，
                    // 叠加在父 Row 上，一排 Tab 读起来是"五颗独立按钮挤在一条里"
                    // （用户截图里那排胶囊套胶囊）。选中态才应该是"唯一一颗实心胶囊"。
                    //
                    // 现在：选中用 Card（要的就是实心胶囊），未选中用裸 Box + clickable
                    // —— 无容器、无轮廓、无阴影，视觉上"只有选中的那颗是按钮"。
                    // 顺带少 N−1 个 Card 实例（每帧省 outline/阴影计算）。
                    if (selected) {
                        Card(
                            onClick = { onSelect(index) },
                            // ## 全局拖拽反馈（2026-10-18 第十九轮，Material 分支）
                            //
                            // 两态都要挂（未选中裸 Box 见下方 else 分支）：Tab 是一排
                            // 等宽兄弟件，只给选中那颗反馈会读作"只有它是按钮"，
                            // 而点击目标其实是**每一颗**。
                            //
                            // 用 0.6 克制档：一排 Tab 每颗都窄（约 1/5 屏宽），
                            // 满强度按下时整排会此起彼伏地横移，像"格子在自己跳"。
                            //
                            // `highlightShape` 传 `TabPillShape`：光斑必须裁在
                            // 18dp 胶囊圆角内（默认 50% 会把方卡裁成椭圆）。
                            modifier = Modifier
                                .weight(1f)
                                .appElasticPress(
                                    state = rememberAppElasticPressState(feedbackStrength = 0.6f),
                                    highlightShape = TabPillShape
                                ),
                            shape = TabPillShape,
                            colors = CardDefaults.cardColors(
                                // 选中胶囊：`primary` 反白（深色主题=黑底白胶囊）。
                                // 现代信息流 Tab 的主流语言：靠"反色块"一眼可辨，
                                // 而不是靠 8% 的灰差。对比度 onPrimary on primary = 21:1。
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = tab,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = BiliSpacing.smPlus, vertical = 7.dp),
                                // 与 iOS 分支对齐到同一档：labelLarge(13)
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                // 与选中态同一条挂载（见上方 Card 的注释）：未选中项
                                // 只是视觉上无底，它**仍是可点件**，按压必须有反馈。
                                // 位置在内层 `clickable` 之前，包住整条链（含它的
                                // indication = null 与 7dp 内边距）。
                                .appElasticPress(
                                    state = rememberAppElasticPressState(feedbackStrength = 0.6f),
                                    highlightShape = TabPillShape
                                )
                                .clip(TabPillShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSelect(index) }
                                .padding(horizontal = BiliSpacing.smPlus, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            trailing?.invoke(this)
        }
    }
}
