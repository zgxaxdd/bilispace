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
     * 内存分级释放。原则：**只释放"重建便宜"的东西**。
     *
     * 绝不在此处做的事（会直接换来卡顿或流量）：
     * - 不清磁盘缓存：清了回看要重新走网络 + 重新解码。
     * - 不碰播放器缓冲：清了立刻卡顿（本项目播放器缓冲由 Media3 自管，此处不介入）。
     * - 不在仍有前台界面时清图片内存缓存：会导致当前列表封面肉眼可见地重新加载。
     *
     * 分级：
     * - UI_HIDDEN（退到后台、仍有内存余量）：清图片内存缓存。重建只走磁盘缓存，便宜。
     * - BACKGROUND（后台且已开始吃紧）：同上（clear 幂等）。
     * - RUNNING_LOW / RUNNING_CRITICAL（前台但吃紧）：**只在界面不可见时**才动图片内存缓存；
     *   可见时交给系统回收，避免"前台清缓存 → 列表重新加载"的负体验。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                    clearImageMemoryCache("UI_HIDDEN")
                }

                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                    // 界面不可见才清；可见时清了会立刻被重新填充，得不偿失。
                    if (!isUiVisible) {
                        clearImageMemoryCache("RUNNING_LOW")
                    }
                }
            }
        } catch (e: Throwable) {
            // onTrimMemory 里抛异常会拖垮应用；降级为静默。
            Logger.w(TAG) { "onTrimMemory($level) 处理失败: ${e.message}" }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // 系统级内存紧张：一律释放图片内存缓存（此时已无更温和的选项）。
        try {
            clearImageMemoryCache("onLowMemory")
        } catch (e: Throwable) {
            Logger.w(TAG) { "onLowMemory 处理失败: ${e.message}" }
        }
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
