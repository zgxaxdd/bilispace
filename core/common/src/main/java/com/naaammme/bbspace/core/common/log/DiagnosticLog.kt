package com.naaammme.bbspace.core.common.log

/**
 * 「现场诊断日志」的统一收口与开关（Q-3，2026-10-04 审查报告）。
 *
 * ## 为什么需要它
 *
 * 全仓 main 源码里有 **29 处直写 `android.util.Log`（12 个文件）**，而且它们**刻意绕开**
 * [Logger]（`Logger.isDebug` 在 release 恒假）：这些点是"稳定版也要留痕"的取证手段 ——
 * MERGED 双源合并计数、刷视频补货的游标/条数、无缝转场的代数、弹幕窗口加载……
 * 有结构守卫专门钉住"不许退回 `Logger.d`"。
 *
 * 问题不在意图，而在**形态**：29 处散落在 12 个文件、没有任何统一开关，量产包里
 * 一律常开（连字符串拼接都照跑），想临时关掉也没有入口。
 *
 * 现在的口径：
 * - 全部收口到本对象，`enabled == false` 时**一个字节都不输出**（`if (enabled)` 短路）；
 * - 初值由 app 层设定一次（`BiliApplication` → `DiagnosticLogApplier`）：
 *   `BuildConfig.DEBUG || AppSettings.diagnosticLogEnabled`；debug 包与从前一样始终可见，
 *   量产包默认关，用户可在「设置 → 性能」里打开做现场取证；
 * - 新增取证日志必须走这里 —— 由 `DiagnosticLogGuardTest` 钉住（不许再出现裸
 *   `android.util.Log.<级别>(` 调用，[Logger] 与本文件自身除外）。
 *
 * 性能：同时提供 `String` 与 lambda 两个重载。lambda 版在关闭时**连消息都不拼**，
 * 高频路径（预取/补货/弹幕窗口）优先用 lambda 版；其余沿用 `String` 版以免动大范围调用点。
 */
object DiagnosticLog {

    /**
     * 是否输出。
     *
     * 默认 `false` —— 任何模块（含测试）直接用本对象时都不会意外往 logcat 灌数据；
     * 真正的初值由 app 层按 `BuildConfig.DEBUG || 设置项` 写入。
     */
    @Volatile
    var enabled: Boolean = false

    fun i(tag: String, message: String) {
        if (enabled) android.util.Log.i(tag, message)
    }

    inline fun i(tag: String, message: () -> String) {
        if (enabled) android.util.Log.i(tag, message())
    }

    fun d(tag: String, message: String) {
        if (enabled) android.util.Log.d(tag, message)
    }

    inline fun d(tag: String, message: () -> String) {
        if (enabled) android.util.Log.d(tag, message())
    }

    fun w(tag: String, message: String) {
        if (enabled) android.util.Log.w(tag, message)
    }

    inline fun w(tag: String, message: () -> String) {
        if (enabled) android.util.Log.w(tag, message())
    }

    /** 带异常对象的 warn（`VideoCardCoverColorStore` 的取色失败用它）。 */
    fun w(tag: String, message: String, throwable: Throwable) {
        if (enabled) android.util.Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        if (enabled) android.util.Log.e(tag, message)
    }

    inline fun e(tag: String, message: () -> String) {
        if (enabled) android.util.Log.e(tag, message())
    }

    /** 带异常对象的 error。 */
    fun e(tag: String, message: String, throwable: Throwable) {
        if (enabled) android.util.Log.e(tag, message, throwable)
    }
}
