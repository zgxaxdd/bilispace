package com.naaammme.bbspace.core.designsystem.color

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 系统壁纸调色板的提取与缓存（移植自 BiliPai 上游 `WallpaperPaletteStore`，**裁剪版**）。
 *
 * ## 与上游的差异（有意为之，不是漏移植）
 *
 * 上游的 `WallpaperPaletteStore` 有两条来源：
 * 1. `extractSystemWallpaperPalette` —— 读**系统壁纸**的主色（[WallpaperManager.getWallpaperColors]）
 * 2. `extractWallpaperPaletteFromUri` —— 解码**App 自定义壁纸**图片、按 5 段切片聚类
 *
 * **BBSpace 只移植第 1 条**，因为第 2 条依赖"App 在首页背后显示一张自定义壁纸"这个功能，
 * 而 BBSpace **没有**这个功能（全仓无壁纸设置、无 WallpaperMedia）。
 * 只搬第 2 条而不搬它依赖的功能，会得到一段永远走不到的代码。
 *
 * ## 为什么用 getWallpaperColors 而不是自己解码系统壁纸
 *
 * `getWallpaperColors` 是系统 API（API 27+，项目 minSdk 33），由**系统**维护壁纸的
 * 主/次/三级色，比"自己拿 WallpaperManager.drawable 解码 + Palette 聚类"便宜得多，
 * 也不需要读壁纸位图的权限。上游在无自定义壁纸时走的也是这条路。
 *
 * ## 取不到时的降级
 *
 * ColorOS / 第三方 launcher 下 `getWallpaperColors` 可能返回 null（权限或实现差异），
 * 也可能抛异常。此时退回 [createDefaultThemePalette]（一组中性紫），**而不是返回 null** ——
 * 调用方（卡片染色）拿到 null 会退回中性玻璃表面，那与"壁纸取色"是两种不同的观感，
 * 会让同一台设备上的卡片在"取到"与"取不到"之间跳变。给一个稳定默认值更可控。
 */
object WallpaperPaletteStore {

    /**
     * 缓存容量。壁纸调色板**极少变**（用户换壁纸才变），所以这里主要不是省算力，
     * 而是省"重复读系统 API"的 Binder 往返。8 个槽位远超实际需要，留足余量。
     */
    private const val MAX_CACHE_SIZE = 8

    private const val CACHE_KEY_SYSTEM = "system"

    private val paletteCache = LruCache<String, WallpaperPalette>(MAX_CACHE_SIZE)

    private val _currentPalette = MutableStateFlow<WallpaperPalette?>(null)

    /** 当前壁纸调色板；尚未加载时为 null。 */
    val currentPalette: StateFlow<WallpaperPalette?> = _currentPalette.asStateFlow()

    /** 同步读取已缓存的调色板（供组合期判断"是否已经有值"）。 */
    fun getCachedPalette(): WallpaperPalette? = synchronized(paletteCache) {
        paletteCache.get(CACHE_KEY_SYSTEM)
    }

    /**
     * 异步加载系统壁纸调色板并写入 [currentPalette]。
     *
     * 命中缓存时**同步**置值，避免已经算过的调色板还要多等一帧才上色
     * （与 [VideoCardCoverColorStore.extractColorAsync] 同一套时序约定）。
     */
    fun loadSystemPalette(context: Context, scope: CoroutineScope) {
        getCachedPalette()?.let {
            _currentPalette.value = it
            return
        }
        val appContext = context.applicationContext
        scope.launch(Dispatchers.Default) {
            val palette = extractSystemWallpaperPalette(appContext) ?: createDefaultThemePalette()
            synchronized(paletteCache) {
                paletteCache.put(CACHE_KEY_SYSTEM, palette)
            }
            _currentPalette.value = palette
        }
    }

    /**
     * 读系统壁纸的主/次/三级色组成调色板。
     *
     * 三级色可能为 null（系统只保证 primary），因此 secondary 退回 primary、
     * tertiary 退回 secondary —— 保证 [WallpaperPalette.stops] 始终有 3 段，
     * 下游的 Y 轴插值不必处理"段数不足"。
     */
    internal fun extractSystemWallpaperPalette(context: Context): WallpaperPalette? {
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return@runCatching null
            val manager = WallpaperManager.getInstance(context)
            val colors = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?: return@runCatching null
            val primary = Color(colors.primaryColor.toArgb())
            val secondary = colors.secondaryColor?.toArgb()?.let { Color(it) } ?: primary
            val tertiary = colors.tertiaryColor?.toArgb()?.let { Color(it) } ?: secondary
            WallpaperPalette(
                topColor = primary,
                bottomColor = tertiary,
                dominantColor = secondary,
                stops = listOf(primary, secondary, tertiary)
            )
        }.getOrNull()
    }

    /**
     * 取不到壁纸色时的稳定默认值。
     *
     * 数值照抄上游 `createDefaultThemePalette`（Material 3 的紫/玫瑰基准色）——
     * 保持与上游观感一致，而不是自己挑一组颜色。
     */
    internal fun createDefaultThemePalette(): WallpaperPalette = WallpaperPalette(
        topColor = Color(0xFF6750A4),
        bottomColor = Color(0xFF7D5260),
        dominantColor = Color(0xFF6750A4),
        stops = listOf(
            Color(0xFF6750A4),
            Color(0xFF5B4D82),
            Color(0xFF6B4A6A),
            Color(0xFF7D5260),
            Color(0xFF8C4F5A)
        )
    )

    fun clearCache() {
        synchronized(paletteCache) {
            paletteCache.evictAll()
        }
    }

    fun clear() {
        clearCache()
        _currentPalette.value = null
    }
}
