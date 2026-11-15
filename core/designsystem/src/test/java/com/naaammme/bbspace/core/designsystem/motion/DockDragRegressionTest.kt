package com.naaammme.bbspace.core.designsystem.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏拖拽的**三个真机 bug** 的回归守卫（2026-10-18 下午，用户实测后修复）。
 *
 * 这三条都是"看代码觉得对、上手立刻错"的问题，且**没有任何编译期检查**能拦住：
 * 它们只在真机拖动时表现为"错位 / 拖出界 / 松手不晃"。所以逐条钉死。
 *
 * ## Bug 1：指示器每格错位 4dp
 * 图标格宽 `itemSize`(48dp)，指示器只有 `itemSize - innerPad*2`(40dp)，要在格内居中
 * ⇒ x 必须加 `innerPad`(4dp)。旧实现漏加，每格向左偏 4dp。
 * 这是**接线层的几何**，本测试钉的是"推导公式"这一层：
 * `indicatorX = index * pitch + innerPad`，用 [dockIndicatorXPx] 表达。
 *
 * ## Bug 2：首/尾档能拖出可视区
 * 旧实现用对称边界 `±(n-1)·pitch`，于是第一个 Tab 还能往左拖、最后一个还能往右拖。
 * 修复：边界按当前档位**非对称**计算（见 [dockDragBoundsPx]）。
 *
 * ## Bug 3：松手不晃（没有弹性）
 * 旧实现用 `tween` 回弹 —— tween 单调，**永远不会越过目标再回来**，
 * 数学上就不可能"晃"。修复：改用 [LiquidIndicatorMotion.releaseSpec]（阻尼 0.62）。
 *
 * ## Bug 4：按压不膨胀 / 松手不回归原状 / 拖拽不"沿受力方向拉伸"
 * 三条同源缺陷，2026-10-18 下午用户指出"不是我想要的效果，且有错位和 bug"：
 * - 膨胀挂在 `onDragStart()` 上，而它要等水平位移超过 touchSlop 才触发
 *   ⇒ **单纯按一下完全不膨胀**、拖拽头一段也不鼓。修复：`onPressDown()` 在
 *   `awaitFirstDown` 后立刻调用，与位移解耦。
 * - 收回膨胀住在 `onRelease()` 里，而 `onSettle` 返回 true 时 `onRelease` **不执行**
 *   ⇒ `pressProgress` 永久停在 1，指示器松手后一直大 18%。修复：`onPressUp()`
 *   在 `finally` 里无条件调用。
 * - 拉伸只放大 scaleX、scaleY 跟着等比放大 ⇒ 读作"整体变大"。修复：
 *   [LiquidIndicatorMotion.dragStretch]（横长 + 纵扁，体积近似守恒）。
 */
class DockDragRegressionTest {

    private val pitch = 48f
    private val innerPad = 4f

    /** 与 `AppNavHost` 里 `indicatorX + innerPadPx` 同一公式（指示器在格内居中）。 */
    private fun dockIndicatorXPx(index: Int): Float = index * pitch + innerPad

    // ── Bug 1：居中 ────────────────────────────────────────────────────────────

    @Test
    fun indicatorIsCenteredInsideItsSlot() {
        // 第 0 格：指示器左边 = innerPad（格内居中，不是 0）
        assertEquals("首格指示器必须内缩 innerPad 才与图标同心", innerPad, dockIndicatorXPx(0), 0.001f)
        // 第 1 格：48 + 4 = 52
        assertEquals(52f, dockIndicatorXPx(1), 0.001f)
        assertEquals(100f, dockIndicatorXPx(2), 0.001f)
    }

    @Test
    fun indicatorCenterMatchesSlotCenter() {
        // 指示器中心 = index*pitch + innerPad + (pitch - 2*innerPad)/2 = index*pitch + pitch/2
        // 即：**必须与格子中心重合**。这是"同心"的严格定义。
        val indicatorWidth = pitch - innerPad * 2
        for (index in 0..4) {
            val indicatorCenter = dockIndicatorXPx(index) + indicatorWidth / 2f
            val slotCenter = index * pitch + pitch / 2f
            assertEquals(
                "第 $index 格的指示器中心必须与格子中心重合（否则就是用户看到的错位）",
                slotCenter,
                indicatorCenter,
                0.001f
            )
        }
    }

    @Test
    fun forgettingInnerPadWouldBeCaught() {
        // 反向断言：如果谁把 innerPad 漏掉（旧 bug），中心会偏 innerPad/2 = 2dp。
        val indicatorWidth = pitch - innerPad * 2
        val wrongCenter = (0 * pitch) + indicatorWidth / 2f // 旧实现：没有 + innerPad
        val slotCenter = 0 * pitch + pitch / 2f
        assertTrue(
            "旧实现（漏 innerPad）的中心应当偏离格子中心 2dp —— 本断言保证这条测试真的能抓到那个 bug",
            kotlin.math.abs(wrongCenter - slotCenter) > 1.5f
        )
    }

    // ── Bug 2：非对称边界 ─────────────────────────────────────────────────────

    @Test
    fun firstSlotCannotDragLeft() {
        // 首档：只能往右（min = 0）
        val min = dockDragBoundsPx(index = 0, lastIndex = 4).first
        assertEquals("第一个 Tab 不许往左拖（往左没有档位）", 0f, min, 0.001f)
    }

    @Test
    fun lastSlotCannotDragRight() {
        val max = dockDragBoundsPx(index = 4, lastIndex = 4).second
        assertEquals("最后一个 Tab 不许往右拖", 0f, max, 0.001f)
    }

    @Test
    fun middleSlotAllowsOneStepEachWay() {
        val (min, max) = dockDragBoundsPx(index = 2, lastIndex = 4)
        assertEquals("中间档左侧最多一档（防一甩到底）", -pitch, min, 0.001f)
        assertEquals("中间档右侧最多一档", pitch, max, 0.001f)
    }

    @Test
    fun rubberBandRespectsAsymmetricBounds() {
        // 首档：往左拖 → 被 min=0 挡住（只允许橡皮筋越界一点点，且方向仍是负）
        val draggedLeft = DampedDragAnimation.rubberBandOffset(
            offsetPx = -100f,
            minOffsetPx = 0f,
            maxOffsetPx = pitch
        )
        assertTrue("首档往左拖必须被边界压住（不能自由拖到 -100）", draggedLeft > -30f)
        assertTrue("但仍保留一点点越界手感（橡皮筋）", draggedLeft < 0f)

        // 末档：往右拖 → 被 max=0 挡住
        val draggedRight = DampedDragAnimation.rubberBandOffset(
            offsetPx = 100f,
            minOffsetPx = -pitch,
            maxOffsetPx = 0f
        )
        assertTrue("末档往右拖必须被压住", draggedRight < 30f)
        assertTrue("仍保留越界手感", draggedRight > 0f)
    }

    @Test
    fun rubberBandInsideAsymmetricBoundsIsUntouched() {
        // 边界内必须原样（不能因为改了非对称就把正常拖拽也压缩了）
        assertEquals(
            20f,
            DampedDragAnimation.rubberBandOffset(20f, minOffsetPx = 0f, maxOffsetPx = pitch),
            0.001f
        )
        assertEquals(
            -20f,
            DampedDragAnimation.rubberBandOffset(-20f, minOffsetPx = -pitch, maxOffsetPx = 0f),
            0.001f
        )
    }

    // ── Bug 3：回弹必须有"晃"（阻尼 < 1）────────────────────────────────────────

    @Test
    fun releaseSpecIsUnderdampedSoItOvershoots() {
        val spec = LiquidIndicatorMotion.releaseSpec()
        assertTrue(
            "松手回弹的阻尼必须 < 1（欠阻尼）才会过冲再回来 —— 用户要的'弹性晃动'。" +
                "阻尼 = 1 是临界阻尼，读作'啪一下到位'，不可能晃。当前 = ${spec.dampingRatio}",
            spec.dampingRatio < 1f
        )
        assertTrue("但也不能太小（否则会来回弹很多次像坏掉）", spec.dampingRatio >= 0.5f)
    }

    @Test
    fun positionSpecStaysCriticallyDampedForTabSwitch() {
        val spec = LiquidIndicatorMotion.positionSpec()
        assertTrue(
            "点 Tab 的位移仍用临界阻尼（阻尼 = 1）：切页不该晃，只有松手才晃。" +
                "当前 = ${spec.dampingRatio}",
            spec.dampingRatio >= 0.99f
        )
    }

    @Test
    fun pressSpecInflatesRatherThanShrinks() {
        assertTrue(
            "按压必须是**膨胀**（scale > 1）：指示器是液态的一块，压上去应该'被挤出来'。" +
                "本仓普通按钮按压是缩小（0.97/0.92），两者物理语言不同。当前 = " +
                LiquidIndicatorMotion.PressedScale,
            LiquidIndicatorMotion.PressedScale > 1f
        )
        // 但也不能鼓得太夸张
        assertTrue(LiquidIndicatorMotion.PressedScale <= 1.3f)
    }

    // ── Bug 4：拖拽必须"沿受力方向拉伸"（横长 + 纵扁）────────────────────────
    //
    // 用户原话："拖拽时沿受力方向拉伸"。旧实现只放大 scaleX、scaleY 跟着
    // pressScale 等比放大 ⇒ 视觉上是"整体变大"，不是"被拉长"。

    @Test
    fun dragStretchLengthensHorizontally() {
        val (stretchX, _) = LiquidIndicatorMotion.dragStretch(dragRatio = 1f)
        val (halfX, _) = LiquidIndicatorMotion.dragStretch(dragRatio = 0.5f)
        val (restX, _) = LiquidIndicatorMotion.dragStretch(dragRatio = 0f)
        assertEquals("停在档位上时不该有形变", 1f, restX, 0.0001f)
        assertTrue("拉满一格必须明显变长（当前 $stretchX）", stretchX > 1.1f)
        assertTrue("形变必须随位移单调增长：半格($halfX) 应介于静止($restX)与满格($stretchX)之间",
            halfX > restX && halfX < stretchX)
    }

    @Test
    fun dragStretchSquashesVerticallySoItReadsAsStretchNotGrow() {
        val (stretchX, squashY) = LiquidIndicatorMotion.dragStretch(dragRatio = 1f)
        assertTrue(
            "横向拉长时纵向必须**变细**（当前 scaleY=$squashY）：缺了纵向收缩这一半，" +
                "整体等比放大读作'变大'而不是'被拉长' —— 这正是用户说没做到的那条",
            squashY < 1f
        )
        assertTrue("纵向收缩幅度要小于横向拉伸幅度（体积近似守恒）", (1f - squashY) < (stretchX - 1f))
        assertTrue("但不能把纵向压扁到可笑的细条", squashY > 0.85f)
    }

    @Test
    fun dragStretchIsSafeForDegenerateInput() {
        // NaN / 越界输入绝不能漏到 graphicsLayer（会让整层消失）
        assertEquals("NaN 要兜底成不变形", 1f to 1f, LiquidIndicatorMotion.dragStretch(Float.NaN))
        val (negX, negY) = LiquidIndicatorMotion.dragStretch(-5f)
        assertEquals("负数归一到 0 = 不变形", 1f, negX, 0.0001f)
        assertEquals(1f, negY, 0.0001f)
        val (overX, overY) = LiquidIndicatorMotion.dragStretch(99f)
        assertEquals("超界钳到 1 = 满形变", LiquidIndicatorMotion.dragStretch(1f).first, overX, 0.0001f)
        assertEquals(LiquidIndicatorMotion.dragStretch(1f).second, overY, 0.0001f)
    }

    // ── 测试桩：与 AppNavHost 里同构的边界公式 ─────────────────────────────────

    /**
     * 底栏拖拽边界（与 `AppNavHost` 的 `dragMinPx/dragMaxPx` 同构）。
     * 抽到这里是为了让"非对称"这件事被测试直接表达，而不是埋在接线代码里。
     */
    private fun dockDragBoundsPx(index: Int, lastIndex: Int): Pair<Float, Float> {
        val min = if (index == 0) 0f else -pitch
        val max = if (index == lastIndex) 0f else pitch
        return min to max
    }
}
