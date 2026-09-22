package com.naaammme.bbspace

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.naaammme.bbspace.core.designsystem.color.VideoCardCoverColorStore
import com.naaammme.bbspace.memory.COVER_COLOR_HISTORY
import com.naaammme.bbspace.memory.COVER_COLOR_VISIBLE_WINDOW
import com.naaammme.bbspace.memory.CoverColorAction
import com.naaammme.bbspace.memory.ImageCacheAction
import com.naaammme.bbspace.memory.MemoryTrimPlan
import com.naaammme.bbspace.memory.TRIM_TO_RATIO
import com.naaammme.bbspace.memory.planLowMemory
import com.naaammme.bbspace.memory.planTrimMemory
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import okio.Path.Companion.toOkioPath
import com.naaammme.bbspace.core.common.log.Logger
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class BiliApplication : Application(), SingletonImageLoader.Factory, ComponentCallbacks2 {

    @Inject
    lateinit var appInitializer: AppInitializer

    @Inject
    @Named("image")
    lateinit var imageOkHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        Logger.init(BuildConfig.DEBUG)
        if (BuildConfig.DEBUG) {
            setupStrictMode()
        }
        setupGlobalExceptionHandler()
        registerActivityLifecycleCallbacks(uiVisibilityCallbacks)
        appInitializer.initialize()
    }

    /** debug-only：主线程磁盘/网络访问检测，penaltyLog 不崩溃，避免误伤 */
    private fun setupStrictMode() {
        val policy = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .build()
        StrictMode.setThreadPolicy(policy)
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Logger.e("UncaughtException", throwable) { "线程 ${thread.name} 发生未捕获异常" }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 前台界面是否可见。用 ActivityLifecycleCallbacks 跟踪，避免引入额外依赖。 */
    private var isUiVisible = false

    /** 当前已 start（可见）的 Activity 数量。 */
    private var startedActivities = 0

    private val uiVisibilityCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            isUiVisible = startedActivities > 0
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            isUiVisible = startedActivities > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /**
     * 内存分级释放的**执行端**。原则：只释放"重建便宜"的东西。
     *
     * 绝不在此处做的事（会直接换来卡顿或流量）：
     * - 不清磁盘缓存：清了回看要重新走网络 + 重新解码。
     * - 不碰播放器缓冲：清了立刻卡顿（本项目播放器缓冲由 Media3 自管，此处不介入）。
     *
     * "哪个 level 对应 trim 还是 clear、为什么 UI_HIDDEN 绝不能 clear"这张分级表
     * 连同它的理由一起搬到了 [planTrimMemory]（`memory/MemoryTrimPolicy.kt`）——
     * 那边是纯函数、有 JVM 单测（MemoryTrimPolicyTest），写在这里时只能靠真机
     * `am send-trim-memory` 才能验证。**不要在这里重新加分支**，改表请改策略文件，
     * 否则会绕开那张表的测试。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        applyTrimPlan(planTrimMemory(level, isUiVisible))
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // 系统级内存紧张：一律释放图片内存缓存（此时已无更温和的选项）。
        applyTrimPlan(planLowMemory())
    }

    /**
     * 执行 [MemoryTrimPlan]。只认 plan，不做任何分级判断。
     *
     * 封面色缓存与图片缓存一起收口：量级很小（每条只是一个 Color），但漏掉它会让
     * "已滑走的历史封面"一直挂在进程里。
     *
     * 整个执行过程包在 try 里：`onTrimMemory` 里抛异常会直接拖垮应用，
     * 而这里的动作全是"尽力优化"性质，失败就该静默降级、只留痕。
     */
    private fun applyTrimPlan(plan: MemoryTrimPlan) {
        try {
            when (plan.imageCache) {
                ImageCacheAction.Nothing -> return
                ImageCacheAction.Trim -> trimImageMemoryCache(plan.reason, TRIM_TO_RATIO)
                ImageCacheAction.Clear -> clearImageMemoryCache(plan.reason)
            }
            when (plan.coverColors) {
                CoverColorAction.Keep -> Unit
                CoverColorAction.TrimToVisibleWindow ->
                    VideoCardCoverColorStore.trimToSize(COVER_COLOR_VISIBLE_WINDOW)

                CoverColorAction.TrimToHistory ->
                    VideoCardCoverColorStore.trimToSize(COVER_COLOR_HISTORY)
            }
        } catch (e: Throwable) {
            Logger.w(TAG) { "onTrimMemory(${plan.reason}) 处理失败: ${e.message}" }
        }
    }

    /**
     * 温和淘汰：按 LRU 把内存缓存压到 [fraction] 比例，**保留最近使用的封面**。
     *
     * 与 [clearImageMemoryCache] 的区别就是这个功能的关键：clear 之后回前台，
     * 当前列表的每一张封面都要重新解码；trim 之后可视卡片仍在缓存里，直接复用。
     */
    private fun trimImageMemoryCache(reason: String, fraction: Float = 0.5f) {
        val cache = SingletonImageLoader.get(this).memoryCache ?: return
        val target = (cache.maxSize * fraction).toLong().coerceAtLeast(1L)
        // 已经比目标小就不动：避免"切后台一次就 trim 一次"的无谓抖动
        if (cache.size <= target) return
        cache.trimToSize(target)
        Logger.d(TAG) { "已修剪图片内存缓存（$reason，${cache.size}/${cache.maxSize} 字节）" }
    }

    /** 仅清内存缓存；磁盘缓存与播放器缓冲一律不动。 */
    private fun clearImageMemoryCache(reason: String) {
        val cache = SingletonImageLoader.get(this).memoryCache ?: return
        cache.clear()
        Logger.d(TAG) { "已释放图片内存缓存（$reason）" }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private companion object {
        const val TAG = "BiliApplication"
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                if (SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(OkHttpNetworkFetcherFactory(callFactory = imageOkHttpClient))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    // 256MB：首页/排行/搜索是全图卡片流，100MB 在浏览几十屏后就被挤满，
                    // 回看时封面要走网络重新解码（列表滚动时肉眼可见的空白闪动）。
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(150)
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
            .build()
    }
}
