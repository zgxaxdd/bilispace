package com.naaammme.bbspace.core.designsystem.glass.liquid

/**
 * 玻璃表面上的前景色调（**纯逻辑**，可 JVM 单测）。
 *
 * 语义按上游 `LiquidGlassAdaptiveReadability`：
 * - [Light] = 浅色前景（白字/白图标）→ 背景偏暗时需要；
 * - [Dark] = 深色前景（黑字/黑图标）→ 背景偏亮时需要。
 *
 * ⚠️ 与 `玻璃强度` 无关：这是**背景亮度**的响应，不是材质档位。
 */
enum class LiquidGlassForegroundTone {
    Light,
    Dark
}

/**
 * 亮度采样器的**注入接口**（纯逻辑层只认它）。
 *
 * 为什么做成 `fun interface` 而不是在核心逻辑里调 `PixelCopy`：
 * 1. JVM 单测里没有 Activity/Window/Bitmap，`PixelCopy` 一调就是 "not mocked"；
 * 2. 采样策略（窗口区域、频率、失败回退）属于 Android 侧实现细节，
 *    放在 core 的纯逻辑里会让这一层无法复用到其它宿主（预览/测试/桌面）。
 *
 * Android 侧实现见同包 `PixelCopyLuminanceSampler`（默认 `enabled = false`）。
 */
fun interface LiquidGlassLuminanceSampler {
    /** 返回 0..1 的背景亮度；拿不到时返回 null（**不要**返回 0 冒充黑）。 */
    fun sample(): Float?
}

/**
 * 前景色调的**迟滞判定**（纯逻辑）。
 *
 * ## 为什么必须迟滞（0.58 / 0.42 双阈值）
 * 单阈值会在亮度恰好跨过它时来回翻：背景里一段亮字幕/一张白图经过，前景色
 * 就黑→白→黑地闪一次（观感是"玻璃在抽搐"）。双阈值 = 只有**明确**变亮/变暗才换，
 * 中间带内保持现有色调。阈值取上游实测值：
 * - 由浅切深：亮度 **> 0.58**（背景确实亮了，白字开始糊）；
 * - 由深切浅：亮度 **< 0.42**（背景确实暗了，黑字开始糊）；
 * - 首次判定（无历史）：`>= 0.5` 取深色，否则浅色。
 */
object LiquidGlassReadabilityPolicy {

    /** 首次判定门槛（无历史时的中点）。 */
    const val InitialDarkThreshold = 0.5f

    /** 浅色前景 → 深色前景：亮度必须高于它（背景明确变亮）。 */
    const val LightToDarkThreshold = 0.58f

    /** 深色前景 → 浅色前景：亮度必须低于它（背景明确变暗）。 */
    const val DarkToLightThreshold = 0.42f

    /**
     * 解析前景色调。
     *
     * @param previous 上一次的色调；null = 还没有历史
     * @param luminance 本次采样（0..1）；null/NaN = 本次没采到
     * @return 解析后的色调。**没采到且无历史 ⇒ [LiquidGlassForegroundTone.Light]**
     *   （保守值：玻璃浮层默认就是白字语言，深底上白字至少可读；反过来黑字
     *   在暗背景上会直接消失）
     */
    fun resolveTone(
        previous: LiquidGlassForegroundTone?,
        luminance: Float?
    ): LiquidGlassForegroundTone {
        if (luminance == null || !luminance.isFinite()) {
            return previous ?: LiquidGlassForegroundTone.Light
        }
        val value = luminance.coerceIn(0f, 1f)
        return when (previous) {
            null -> if (value >= InitialDarkThreshold) {
                LiquidGlassForegroundTone.Dark
            } else {
                LiquidGlassForegroundTone.Light
            }

            LiquidGlassForegroundTone.Light ->
                if (value > LightToDarkThreshold) {
                    LiquidGlassForegroundTone.Dark
                } else {
                    previous
                }

            LiquidGlassForegroundTone.Dark ->
                if (value < DarkToLightThreshold) {
                    LiquidGlassForegroundTone.Light
                } else {
                    previous
                }
        }
    }
}

/**
 * 可观察的前景色状态（把采样器与迟滞串起来）。
 *
 * 采样是**拉取式**的：宿主在合适的时机（滚动停止、转场结束、定时器）调用
 * [refresh]/[update]，而不是这一层自己起循环 —— 核心逻辑不持有线程/定时器，
 * 也就不会在 JVM 测试里空转。
 *
 * 线程语义：所有方法都应按同一线程调用（Android 侧为主线程）。
 *
 * 注意：本类是**可变状态持有者**（每次采样都写内部字段），刻意不加 `@Immutable`
 * —— 加了会让 Compose 把它当稳定值跳过重组，前景色切换就不会反映到界面。
 * 它也不实现 `State`：宿主读 [tone] 的地方本身应当是组合外的绘制/色值入口。
 *
 * ## 接线状态（2026-10-18，B 组收口）
 * **当前无生产消费者**（`grep LiquidGlassReadability` 只命中本包与单测）。它已具备
 * 完整能力但**故意不接线**，原因与接线步骤写在 `docs/2026-10-18-B组-未接线符号
 * 接线步骤.md`：
 * 1. 唯一有意义的消费点是"玻璃顶栏 / 底栏 Dock / 播放器浮层"的前景色，这三处分别
 *    在 `feature/home`、`app`、`feature/video` 里，接线必然跨模块（本轮不改 feature）；
 * 2. 采样是 `PixelCopy` 回读，**必须真机验证迟滞是否抖**（滚动停止/转场结束各采一次，
 *    双阈值 0.58/0.42 的中间带是否足够宽），无设备的静态改动属于"凭感觉"；
 * 3. 默认关闭是刻意的：默认路径必须零回读（见同包 `PixelCopyLuminanceSampler` 的 KDoc）。
 *
 * 接线方式（宿主侧，机器可验证的部分）：
 * - 采样器注入：`PixelCopyLuminanceSampler(activityProvider, sampleBoundsProvider, enabled = true)`；
 * - 状态注入：`LiquidGlassReadabilityState(sampler)`，在**滚动停止 / 转场结束**时
 *   `refresh()`（不要每帧）；
 * - 消费：把 `tone` 映射成前景色传给玻璃内容（`Light` → 白，`Dark` → onSurface/黑），
 *   并对 `MaterialTheme.colorScheme` 取色保持"不新增直取"的既有约束；
 * - 失败路径：`sample()` 返回 null 时保持上一次 `tone`（`update` 已保证），首次失败
 *   取保守的 `Light`。
 *
 * 单测：`LiquidGlassReadabilityPolicyTest`（迟滞/保守值）与
 * `LiquidGlassReadabilityStateTest`（null 不覆盖历史）已覆盖纯逻辑，接线后只需补
 * "采样节流"一条宿主测试。
 */
class LiquidGlassReadabilityState(
    private val sampler: LiquidGlassLuminanceSampler? = null
) {
    private var resolvedTone: LiquidGlassForegroundTone? = null

    /** 最近一次**有效**采样的亮度（诊断用；没采到过为 null）。 */
    var lastLuminance: Float? = null
        private set

    /** 当前色调（未判定前是保守的浅色前景）。 */
    val tone: LiquidGlassForegroundTone
        get() = resolvedTone ?: LiquidGlassForegroundTone.Light

    /** 是否已经有过一次有效判定（宿主据此决定是否要淡入切换）。 */
    val hasResolved: Boolean
        get() = resolvedTone != null

    /**
     * 用一次外部采样更新色调（采样为 null = 本次没采到，保持历史）。
     *
     * @return 更新后的 [tone]
     */
    fun update(luminance: Float?): LiquidGlassForegroundTone {
        val valid = luminance?.takeIf { it.isFinite() }
        // 没采到：**不动历史、也不标记"已判定"**（否则一次失败就锁定保守值，
        // 而且 `hasResolved` 会在从没成功采样的情况下变成 true）
        if (valid == null) return tone
        lastLuminance = valid
        resolvedTone = LiquidGlassReadabilityPolicy.resolveTone(resolvedTone, valid)
        return tone
    }

    /** 从注入的采样器拉一次并更新；没有采样器时等价于 [update]`(null)`。 */
    fun refresh(): LiquidGlassForegroundTone = update(sampler?.sample())

    /** 复位（切换宿主/关闭玻璃时调用）：下次采样按"首次判定"处理。 */
    fun reset() {
        resolvedTone = null
        lastLuminance = null
    }
}
