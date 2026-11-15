package com.naaammme.bbspace.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import com.naaammme.bbspace.core.common.log.DiagnosticLog
import kotlin.math.roundToInt

/**
 * 内存**读数**（W5，2026-10-17 夜第二轮）。
 *
 * ## 为什么先做读数而不是先"优化"
 * 本仓 `MemoryTrimPolicy` 已经把"什么时候 trim / clear"做成纯函数并有单测，但**没有任何
 * 一处能回答"那一刻到底用了多少"**：onTrimMemory 里只打一句 reason，
 * 图片缓存水位 / Java 堆 / GC 次数 / 线程数全靠真机 `dumpsys meminfo` 手工看。
 * 结果是"优化"只能凭印象——这正是上一轮交接书里"零测量数据"的同一种病。
 *
 * ## 分工
 * - [MemoryDiagPolicy]：**纯函数**（要不要报、怎么格式化），JVM 可测；
 * - [MemoryDiagReporter]：Android 侧取数（`Debug.getRuntimeStat` / `Runtime` /
 *   Coil 缓存 / `Thread.activeCount`）后交给策略格式化；
 * - 调用点：`BiliApplication.onTrimMemory` / `onLowMemory`（既有执行点，**不新建线程**）。
 *
 * ## 为什么用 `Debug.getRuntimeStat`
 * `art.gc.gc-count` / `art.gc.gc-time` / `art.gc.blocking-gc-count` 是 ART 自己维护的
 * 进程级计数（API 23+），不需要设备、不需要 perfetto。把它们和"此刻堆占用"放在同一行，
 * 才能判断"卡顿是不是 GC 造成的"——只报堆大小是看不出来的。
 */
data class MemorySnapshot(
    /** `onTrimMemory(level)` 的 level；`onLowMemory` 时传 [LEVEL_LOW_MEMORY]。 */
    val level: Int,
    val reason: String,
    val javaHeapUsedMb: Int,
    val javaHeapMaxMb: Int,
    val javaHeapAllocatedMb: Int,
    val imageCacheUsedMb: Int,
    val imageCacheMaxMb: Int,
    val threadCount: Int,
    val gcCount: Long,
    val gcTimeMs: Long,
    val blockingGcCount: Long,
    val uptimeMs: Long
) {
    /** 图片缓存占其上限的百分比（0..100，上限为 0 时返回 0）。 */
    val imageCachePercent: Int
        get() = if (imageCacheMaxMb <= 0) 0 else (imageCacheUsedMb * 100f / imageCacheMaxMb).roundToInt()

    /** Java 堆占用百分比（0..100）。 */
    val heapPercent: Int
        get() = if (javaHeapMaxMb <= 0) 0 else (javaHeapUsedMb * 100f / javaHeapMaxMb).roundToInt()
}

/** 纯判据与格式化（不依赖 Android，JVM 可逐条钉）。 */
object MemoryDiagPolicy {

    /** `onLowMemory` 的伪 level（系统级内存紧张，没有具体 level 值）。 */
    const val LEVEL_LOW_MEMORY = -1

    /**
     * 是否值得打一行。
     *
     * 只在**真有内存压力**时输出（正常的前后台切换不刷屏）：
     * - `onLowMemory`（伪 level）永远打；
     * - `TRIM_MEMORY_RUNNING_*`（10~15，前台但已吃紧）与 `TRIM_MEMORY_*`（60~80，后台）打；
     * - `TRIM_MEMORY_UI_HIDDEN`(20) 只有图片缓存超过 [UI_HIDDEN_CACHE_REPORT_PERCENT] 才打
     *   （它是"进后台"的常规回调，几乎每次都会来）。
     */
    fun shouldReport(snapshot: MemorySnapshot): Boolean {
        if (snapshot.level == LEVEL_LOW_MEMORY) return true
        return when (snapshot.level) {
            in 10..15 -> true
            in 40..80 -> true
            else -> snapshot.imageCachePercent >= UI_HIDDEN_CACHE_REPORT_PERCENT
        }
    }

    /** 一行可读读数；字段顺序固定，便于 adb logcat 里 grep 与前后对比。 */
    fun format(snapshot: MemorySnapshot): String {
        val levelText = if (snapshot.level == LEVEL_LOW_MEMORY) "LOW_MEMORY" else "level=${snapshot.level}"
        return "mem[$levelText ${snapshot.reason}] " +
            "heap=${snapshot.javaHeapUsedMb}/${snapshot.javaHeapMaxMb}MB(${snapshot.heapPercent}%) " +
            "alloc=${snapshot.javaHeapAllocatedMb}MB " +
            "img=${snapshot.imageCacheUsedMb}/${snapshot.imageCacheMaxMb}MB(${snapshot.imageCachePercent}%) " +
            "threads=${snapshot.threadCount} " +
            "gc=${snapshot.gcCount}/${snapshot.gcTimeMs}ms blockingGc=${snapshot.blockingGcCount} " +
            "uptime=${snapshot.uptimeMs / 1000}s"
    }

    /** UI_HIDDEN 时的上报阈值：图片缓存吃到上限 60% 以上才值得记一笔。 */
    const val UI_HIDDEN_CACHE_REPORT_PERCENT = 60
}

/** Android 侧取数 + 输出。**全部调用点都在既有内存回调里**，不新建线程/定时器。 */
object MemoryDiagReporter {

    private const val MB = 1024L * 1024L

    /**
     * @param imageCache 图片缓存水位提供者。**必须允许返回 null**，且只在 ImageLoader 已经
     *   构造过之后才取值：`coil3.SingletonImageLoader.get(context)` 在**尚未初始化时会在
     *   主线程构造 ImageLoader**（含 DiskCache 目录创建 = 磁盘 IO），而本方法是挂在
     *   `onTrimMemory` 主线程回调里的 —— 冷启后立刻切后台再 trim 一次，就会在 StrictMode
     *   下报一条主线程磁盘读。由调用方（`BiliApplication`）自己判断"工厂是否跑过"。
     */
    fun report(
        context: Context,
        level: Int,
        reason: String,
        imageCache: (() -> ImageCacheStats?)? = null
    ) {
        val snapshot = runCatching { snapshot(context, level, reason, imageCache) }.getOrNull() ?: return
        if (!MemoryDiagPolicy.shouldReport(snapshot)) return
        val line = MemoryDiagPolicy.format(snapshot)
        // 用 DiagnosticLog：量产包默认关，现场诊断打开后才有输出（与 StallDiag 同一口径）。
        DiagnosticLog.i(TAG) { line }
    }

    /** 图片缓存水位（字节）。[used] 为 0 且 [max] 为 0 表示"未初始化"，与被判成 0% 等价。 */
    data class ImageCacheStats(val used: Long, val max: Long)

    private fun snapshot(
        context: Context,
        level: Int,
        reason: String,
        imageCache: (() -> ImageCacheStats?)?
    ): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val maxMb = (runtime.maxMemory() / MB).toInt()
        val allocatedMb = (runtime.totalMemory() / MB).toInt()
        val usedMb = ((runtime.totalMemory() - runtime.freeMemory()) / MB).toInt()
        val stats = imageCache?.invoke()
        val imageMaxMb = ((stats?.max ?: 0L) / MB).toInt()
        val imageUsedMb = ((stats?.used ?: 0L) / MB).toInt()
        // ActivityManager 只用于拿"设备总内存档位"的参照（不参与判据，仅随行记录意图）。
        val memoryClassMb = runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        }.getOrDefault(0)
        return MemorySnapshot(
            level = level,
            reason = if (memoryClassMb > 0) "$reason mc=${memoryClassMb}MB" else reason,
            javaHeapUsedMb = usedMb,
            javaHeapMaxMb = maxMb,
            javaHeapAllocatedMb = allocatedMb,
            imageCacheUsedMb = imageUsedMb,
            imageCacheMaxMb = imageMaxMb,
            threadCount = Thread.activeCount(),
            gcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L,
            gcTimeMs = Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: 0L,
            blockingGcCount = Debug.getRuntimeStat("art.gc.blocking-gc-count")?.toLongOrNull() ?: 0L,
            uptimeMs = SystemClock.elapsedRealtime()
        )
    }

    private const val TAG = "MemoryDiag"
}
