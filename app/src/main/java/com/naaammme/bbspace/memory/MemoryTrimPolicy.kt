package com.naaammme.bbspace.memory

import android.content.ComponentCallbacks2

/**
 * `onTrimMemory` 的分级编排，抽成**纯函数**以便 JVM 单测（移植自 BiliPai 上游
 * `BackgroundMemoryTrimPolicy` 的思路：策略与执行分离，策略可测）。
 *
 * ## 为什么值得单独抽出来
 *
 * 这段编排原本是 [android.app.Application.onTrimMemory] 里的一个 `when`，
 * 要验证它只能上真机 + `adb shell am send-trim-memory`。而它恰好是那种
 * **"改错了一帧内看不出来、只在切后台再回来时才咬人"** 的逻辑：
 * 分派错一档的代价要么是回前台整屏封面重解码（用户能看见的重刷），
 * 要么是后台该放内存时没放（被系统杀）。两类都不会崩、都不会进日志。
 *
 * 抽成纯函数后，"哪个 level 映射到哪个动作"这张表第一次有了能跑的测试。
 *
 * ## 表本身的设计意图（改动前先读这段）
 *
 * - `UI_HIDDEN` → **只 trim 不清空**。这条是本策略存在的全部理由：
 *   `TRIM_MEMORY_UI_HIDDEN` 在**每次**按 Home 时都会收到，它只表示"界面不可见"，
 *   不代表内存吃紧。原实现在这里 `memoryCache.clear()`，于是回前台后当前列表里
 *   所有可见封面都要从磁盘缓存重新解码 —— 就是"切后台再回来封面肉眼可见地重刷"。
 *   上游 BiliPai 也是同样的判断。
 * - `BACKGROUND / MODERATE / COMPLETE` → 清空：已在后台且确实吃紧，重建可接受。
 * - `RUNNING_LOW / RUNNING_CRITICAL` → **只在界面不可见时**清。界面可见时清了会
 *   立刻被重新填充，纯亏（这是本文件里唯一需要 `uiVisible` 参数的原因）。
 * - `RUNNING_MODERATE`（5）**不处理**：它只是"系统开始考虑回收后台进程"的轻度提示，
 *   比 UI_HIDDEN 还轻，动缓存只会白白牺牲回前台的即时性。
 * - 未列出的 level 一律 `Nothing`：**不猜**。新增分支要显式写进 when 并由测试覆盖。
 */
enum class ImageCacheAction {
    /** 什么都不做（未知 level、以及界面可见时的 RUNNING_*）。 */
    Nothing,

    /**
     * 按 LRU 淘汰到容量的 [TRIM_TO_RATIO] —— 让出内存但保留最近使用的可视封面。
     */
    Trim,

    /** 整个清空。只在"已在后台且吃紧"或系统级紧张时用。 */
    Clear
}

/** 封面代表色缓存（每条只是一个 Color）的收尾动作，与图片缓存同步收口。 */
enum class CoverColorAction {
    Keep,
    TrimToVisibleWindow,
    TrimToHistory
}

/**
 * 无缝动画「软件位图」缓存（`SeamlessCardOpener.coverMemo`，4 张 960×540 ARGB_8888
 * ≈ 8MB）的收尾动作。
 *
 * 这块内存**此前不在任何分级表里**：`applyTrimPlan` 只清 Coil 内存缓存与封面色缓存，
 * 而 coverMemo 挂在 `AppNavHost` 创建的 opener 上，`onTrimMemory` / `onLowMemory`
 * 都碰不到它 —— 内存紧张时这 8MB 照留不误。
 *
 * 释放是安全的：它是"重建便宜"的一类（下次无缝打开只需重新 `Bitmap.copy`，
 * 实测 5~20ms 主线程），与"绝不在此处清磁盘缓存/播放器缓冲"的原则不冲突。
 */
enum class SeamlessCoverAction {
    /** 不动（未知 level、界面可见时的 RUNNING_*）。 */
    Keep,

    /** 只留最近 1 条：下一次无缝打开大概率还是同一张卡。 */
    TrimToRecent,

    /** 全清：内存紧张时这 8MB 必须还给系统。 */
    Clear
}

/**
 * 一次 `onTrimMemory(level)` 应该做的事。
 *
 * @param reason 打日志用的档位名。**刻意由策略给出**而不是调用方现写：
 *               真机排查内存问题时 `adb logcat | grep` 就是靠这个字符串定位的，
 *               写错或漏写会让"为什么回前台重刷了"重新变成查不到的问题。
 */
data class MemoryTrimPlan(
    val imageCache: ImageCacheAction,
    val coverColors: CoverColorAction,
    val seamlessCovers: SeamlessCoverAction,
    val reason: String
)

/** trim 目标比例：留一半。与 [ImageCacheAction.Trim] 一起构成 UI_HIDDEN 的温和档位。 */
const val TRIM_TO_RATIO = 0.5f

/** 保留多少条封面色：约等于当前可视卡片量（双列一屏 4~8 张，留了余量）。 */
const val COVER_COLOR_VISIBLE_WINDOW = 16

/** 后台/系统紧张时保留的封面色条数。 */
const val COVER_COLOR_HISTORY = 8

/**
 * 把 [ComponentCallbacks2] 的 level 映射成动作。纯查表，无副作用、不碰任何单例。
 *
 * @param uiVisible 界面是否可见。只有 RUNNING_*（前台但吃紧）这一支会用到它。
 */
fun planTrimMemory(level: Int, uiVisible: Boolean): MemoryTrimPlan = when (level) {
    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
        MemoryTrimPlan(
            imageCache = ImageCacheAction.Trim,
            coverColors = CoverColorAction.TrimToVisibleWindow,
            // 无缝位图同样走温和档：留最近 1 条（回前台大概率还是那张卡），
            // 其余还给系统 —— 与 imageCache 的 Trim 同一取向。
            seamlessCovers = SeamlessCoverAction.TrimToRecent,
            reason = "UI_HIDDEN"
        )

    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
    ComponentCallbacks2.TRIM_MEMORY_MODERATE,
    ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
        MemoryTrimPlan(
            imageCache = ImageCacheAction.Clear,
            coverColors = CoverColorAction.TrimToHistory,
            seamlessCovers = SeamlessCoverAction.Clear,
            reason = "BACKGROUND"
        )

    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
        if (uiVisible) {
            // 可见时不清：清了下一帧就被重新填满，纯浪费解码。
            MemoryTrimPlan(
                imageCache = ImageCacheAction.Nothing,
                coverColors = CoverColorAction.Keep,
                seamlessCovers = SeamlessCoverAction.Keep,
                reason = "RUNNING_VISIBLE"
            )
        } else {
            MemoryTrimPlan(
                imageCache = ImageCacheAction.Clear,
                coverColors = CoverColorAction.TrimToHistory,
                seamlessCovers = SeamlessCoverAction.Clear,
                reason = "RUNNING_LOW"
            )
        }

    else -> MemoryTrimPlan(
        imageCache = ImageCacheAction.Nothing,
        coverColors = CoverColorAction.Keep,
        seamlessCovers = SeamlessCoverAction.Keep,
        reason = "IGNORED"
    )
}

/** [onLowMemory] 的编排：系统级紧张，没有更温和的选项了。 */
fun planLowMemory(): MemoryTrimPlan =
    MemoryTrimPlan(
        imageCache = ImageCacheAction.Clear,
        coverColors = CoverColorAction.TrimToHistory,
        seamlessCovers = SeamlessCoverAction.Clear,
        reason = "onLowMemory"
    )
