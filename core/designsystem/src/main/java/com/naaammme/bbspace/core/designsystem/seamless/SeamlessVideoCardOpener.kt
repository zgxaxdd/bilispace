package com.naaammme.bbspace.core.designsystem.seamless

import android.app.Activity
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.PixelCopy
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.common.media.BiliCoverSize
import com.naaammme.bbspace.core.common.media.coverUrlFor
import com.naaammme.bbspace.core.model.VideoTarget
import com.oplus.animation.OplusViewSeamless
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「视频卡片 → 详情」系统无缝打开的共享执行器（按官方接入指引实现）。
 *
 * 官方语义（open.oppomobile.com 文档 13772）：
 * - setSeamlessView 在 startActivity 前调用，同一 bundle 传给 startActivity；
 * - 打开/返回成对动画由框架执行，返回终点按 view 实时位置自动调整；
 * - 框架在打开与返回时都会「绘制 view 内容」作为过渡画面；
 * - BUNDLE_RADIUS 有圆角必传；BUNDLE_COLOR/RECT/BITMAP 是框架取色/取区域/绘制
 *   内容**失败时的兜底**，官方默认不传（主动传 COLOR 只会改变纯色兜底的颜色，
 *   此前返回阶段的白/黑纯色缩回即来源于此）。
 *
 * 关键适配：锚点是零绘制透明 View，框架"绘制 view 内容"时它没有内容 → 返回阶段
 * 退回纯色兜底。因此点击时把封面（**软件位图**）画进锚点 background+foreground，
 * 打开与返回的框架绘制共用这份内容；返回结束清除，平时保持零绘制。
 * 位图必须转软件配置：返回阶段的快照为软件画布，Coil 默认解码的硬件位图在软件
 * 画布上绘制会抛异常（此前"打开偶发退回默认动画、返回必现纯色"的根因）。
 * 另在打开动画结束后把同一软件位图补进 bundle 的 BUNDLE_BITMAP——打开已结束
 * 不影响开画面面，若框架在返回开始时重新读取 bundle，则多一层内容兜底。
 */
class SeamlessCardOpener(
    private val route: (VideoTarget, String?, Boolean, Bundle) -> Unit,
    private val cleanupScope: CoroutineScope? = null,
    private val enabled: () -> Boolean = { true }
) {

    /** 上一张被画了过渡内容的卡片；新打开时清理，避免软件位图副本累积。 */
    private var lastDecorated: View? = null

    /** 待执行的锚点清理协程；新打开时取消，防止误清正在使用的过渡位图。 */
    private var cleanupJob: Job? = null

    /**
     * 软件位图的小容量 LRU（最多 [COVER_MEMO_CAPACITY] 条）。
     *
     * 为什么不是单条：硬件位图转软件位图（`Bitmap.copy(ARGB_8888)`）是**主线程同步拷贝**，
     * 960×540 约 2MB、实测 5~20ms，而且发生在**点击那一帧**——正是系统要准备 Activity
     * 窗口 + 启动无缝动画的时刻，两者争主线程会让动画起步顿一下。单条缓存时用户在两张
     * 卡片之间来回点，每次切换都要重算；扩到多条即可覆盖"来回看同一批卡/连续开退"的真实场景。
     */
    private val coverMemo = LinkedHashMap<String, SoftwareCover>()

    private fun memoGet(key: String): SoftwareCover? {
        val hit = coverMemo.remove(key) ?: return null
        coverMemo[key] = hit // 重新插入 = 标记为最近使用
        return hit
    }

    private fun memoPut(key: String, cover: SoftwareCover) {
        coverMemo.remove(key)
        coverMemo[key] = cover
        while (coverMemo.size > COVER_MEMO_CAPACITY) {
            val eldest = coverMemo.keys.firstOrNull() ?: break
            coverMemo.remove(eldest)
        }
    }

    /**
     * 把卡片的 [GraphicsLayer] 冻结成一张位图，作为无缝过渡的"锚点内容"。
     *
     * ## 为什么必须这么做（"简介里有没有文字"的分水岭）
     *
     * 锚点是裸 `FrameLayout`，没有任何子 View；框架"绘制 view 内容"时只能拿到
     * 我们喂给锚点的东西。喂封面位图 → 简介区空白；自己合成灰底 → 没有文字。
     * 只有**卡片真实渲染像素**才包含标题、UP 名等文字，而那只能通过录制
     * Compose 的绘制结果得到（见 `Modifier.recordCardLayer`）。
     *
     * ## 为什么必须"冻结"
     *
     * 录制层持续刷新；点击后若不冻结，过渡途中卡片重绘（图片加载完成、角标更新）
     * 会让锚点内容跟着变，morph 出来的画面就会闪。
     *
     * ## 时机与成本
     *
     * `toImageBitmap` 是挂起函数，故调用方需在协程里先冻结、再启动过渡。
     * 冻结只是把已录好的层拷贝成位图（612×625 实测毫秒级），相比卡片按压反馈可忽略。
     *
     * @return 冻结出的位图；层尚未录制（宽高 ≤1）或失败时返回 null，
     *   调用方应退回原有装饰路径。
     */
    suspend fun freezeCardBitmap(layer: GraphicsLayer): Bitmap? = runCatching {
        if (layer.size.width <= 1 || layer.size.height <= 1) return@runCatching null
        val raw = layer.toImageBitmap().asAndroidBitmap()
        // **必须转软件配置**：`toImageBitmap()` 产出的是 HARDWARE 位图，而系统返回阶段
        // 用软件画布做快照，遇到硬件位图会抛
        // `IllegalArgumentException: Software rendering doesn't support hardware bitmaps`，
        // 导致 `setSeamlessView` 直接失败、无缝动画退化成普通跳转（实测踩过）。
        if (raw.config == Bitmap.Config.HARDWARE) {
            raw.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
        } else {
            raw
        }
    }.getOrNull()

    /**
     * **点击瞬间**截取锚点所在区域的真实屏幕像素，作为无缝过渡的锚点内容。
     *
     * ## 为什么必须用"截屏"而不是"每帧录制"
     *
     * 卡片内容含标题、UP 名等文字，而锚点是裸 `FrameLayout`（没有子 View），
     * 框架拿不到这些像素——只能由我们喂给它。得到"整卡真实像素"有两条路：
     *
     * 1. `Modifier.recordCardLayer`（`GraphicsLayer.record`）——**性能红线，已弃用**：
     *    它每个绘制帧都执行，等于每张可见卡片每帧多一次离屏渲染。
     *    实测挂到首页卡片后 jank 从 1.27% → 4.06%、slowDraw 9 → 31。
     * 2. `PixelCopy`（本函数）——**只在点击那一次**截一块卡片大小的区域。
     *    一次性成本，与滚动帧率无关。
     *
     * ## 时序
     *
     * `PixelCopy` 是异步 API。调用方应在点击处理里**先发起截屏**，在其回调里
     * 完成 `tryOpen` + `startActivity`——整体仍发生在同一次点击的处理链内，
     * 满足官方"`setSeamlessView` 必须在 `startActivity` 之前"的时序契约。
     * 卡片大小区域的截取在实测中是毫秒级，用户只会看到按压反馈。
     *
     * @param onCaptured 截屏结果回调（主线程）。失败时传 null，调用方应走降级装饰。
     */
    fun captureCardBitmap(anchor: View, onCaptured: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onCaptured(null)
            return
        }
        val w = anchor.width
        val h = anchor.height
        if (w <= 1 || h <= 1) {
            onCaptured(null)
            return
        }
        val window = (anchor.context as? Activity)?.window
        if (window == null) {
            onCaptured(null)
            return
        }
        // 锚点在窗口中的位置：PixelCopy 的 srcRect 用的是**窗口坐标**
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val srcRect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        // 必须用软件配置位图：系统返回阶段用软件画布快照，硬件位图会抛异常
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(window, srcRect, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    Log.i(TAG, "PixelCopy captured ${w}x$h for anchor content")
                    onCaptured(bitmap)
                } else {
                    Log.w(TAG, "PixelCopy failed result=$result -> fallback decoration")
                    bitmap.recycle()
                    onCaptured(null)
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure {
            Log.w(TAG, "PixelCopy threw: ${it.message} -> fallback decoration")
            bitmap.recycle()
            onCaptured(null)
        }
    }

    fun tryOpen(
        anchor: View?,
        target: VideoTarget?,
        coverUrl: String?,
        coverLarge: Boolean,
        anchorRadiusPx: Float,
        listCover: Pair<Float, Float>? = null,
        /**
         * 动画过程中锚点放大到全屏比例不一致时，view 之外区域的填充色（`BUNDLE_COLOR`）。
         * 官方默认不传、由框架从 view 内容取色；**锚点内容为空或与目标比例差异大时
         * 取色会落到黑色**，表现为"退回途中拖出一道黑轨迹"。调用方传入锚点自身
         * 的底色即可消除。
         */
        fallbackColor: Int? = null,
        /**
         * 动画期间保持锚点 View 可见（官方 `BUNDLE_VIEW_VISIBLE`，默认不可见）。
         *
         * 为什么需要：框架优先自行捕获锚点内容；捕获不到时才退回
         * `BUNDLE_BITMAP` / `BUNDLE_COLOR` 兜底（官方文档原文："用于框架绘制内容
         * 错误时兜底用"）。而**兜底位图是我们主动塞的封面**——对小横条这种
         * "锚点自身就有实时内容"的宿主，兜底一旦被采用，就会在锚点矩形里画出
         * 一张被拉伸的封面，表现为"打开和返回都有预览图的痕迹"。
         * 置 true 让框架直接采真实 View，绕开兜底路径。
         */
        keepAnchorVisible: Boolean = false,
        /**
         * 是否用封面位图装饰锚点（`background`/`foreground` + `BUNDLE_BITMAP`）。
         *
         * **仅列表卡片该用 true**：卡片锚点是零绘制透明 View，框架采不到内容，
         * 必须靠封面位图兜底才能 morph 出"封面展开"。
         *
         * 对小横条这类**锚点矩形与封面比例差极大**的宿主必须 false：
         * 小横条是 1188×168（≈7:1），封面是 16:9；框架一旦走兜底路径，会把封面
         * **拉伸铺满整个横条矩形** —— 就是用户看到的"预览图填充在视频小横条中"。
         * 此时改用 `BUNDLE_COLOR`（横条底色）兜底：最差情况是一块纯色，不会出现
         * 比例失真的封面。
         */
        decorateAnchor: Boolean = true,
        /**
         * 源卡片标识。注册成功后会写进 [SeamlessTransitionState.markSourceCard]，
         * 供**该卡片自己**在过渡期间淡出内容（封面 + 简介区）。
         *
         * 传 `null`（小横条等非卡片来源）= 不淡出任何卡片。详见
         * [SeamlessTransitionState.shouldFadeCard]。
         */
        sourceCardKey: String? = null,
        /**
         * 覆盖 [contentRect]：**取景矩形**，即锚点内"真正作为动画内容"的那块区域
         * （屏幕坐标）。不传则退回整个锚点矩形。
         *
         * ## 为什么需要它（官方 `BUNDLE_RECT`）
         *
         * 官方文档对 `BUNDLE_RECT` 的说明是「view 在屏幕上显示的区域」，
         * 框架按它计算取景与几何。当**锚点矩形 ≠ 内容矩形**时必须显式传：
         *
         * 本项目卡片是「4:3 封面 + 简介区」，锚点为了把简介一起带进过渡而铺满**整卡**
         * （比例接近 1:1）；但动画真正的内容是**封面**。若仍把整卡矩形当取景矩形，
         * 框架会把封面位图**拉伸铺满整卡**——表现为「点击后预览图先盖住简介区，
         * 再开始动画」（返回方向同理，缩回前也先盖一下）。
         *
         * 所以：锚点管"过渡范围"，取景矩形管"内容范围"，两者分开传。
         */
        contentRect: android.graphics.Rect? = null,
        /**
         * 锚点内**封面区域的高度**（px）。传入时框架会合成一张"整卡预览图"
         * （封面 + 简介区底色）来装饰锚点，而不是只用封面图。
         *
         * ## 为什么需要（这是"简介有没有被带上"的关键）
         *
         * 锚点铺满整卡后有两种装饰方式，都错过一次：
         * 1. **只用封面位图**：`RoundedBitmapDrawable` 会把它拉伸填满锚点边界。
         *    锚点是近 1:1 而封面是 4:3 → 预览图被纵向拉伸、盖住简介区，
         *    表现为"点击后预览图先盖住简介区，再开始动画"。
         * 2. **完全不装饰**：锚点是零绘制透明 View，框架捕获不到内容 →
         *    返回方向整块变黑（实测）。
         *
         * 正确做法是**自己合成整卡预览**：上半部分画封面、下半部分填简介区底色，
         * 比例与锚点一致。这样两个方向的预览图都等于"整张卡片长什么样"，
         * 与官方 B 站客户端的效果一致。
         */
        coverHeightPx: Int = 0,
        /**
         * **整卡真实渲染像素**（由 `SeamlessVideoCardOpener.freezeCardBitmap` 冻结得到）。
         *
         * 传入时优先用它装饰锚点——这是唯一能让过渡画面带上"简介里的文字"的途径：
         * 锚点是裸 `FrameLayout`，框架只能拿到我们喂进去的内容；封面位图与合成灰底
         * 都不含文字（见 [freezeCardBitmap] 注释）。
         */
        cardBitmap: Bitmap? = null,
    ): Boolean {
        if (anchor == null || target == null) return false
        if (!enabled()) return false
        if (!OplusViewSeamlessCompat.isVersionSupported()) return false
        // 快速连开时上一轮清理可能还没跑：先取消，否则它会把本次正在使用的
        // 过渡位图清掉，打开动画中途丢内容（表现为打开动画闪变）
        cleanupJob?.cancel()
        cleanupJob = null
        // 同步清掉上一张被装饰过的锚点。**必须同步做**，不能交给下面的延迟清理协程：
        // 那个协程的启动条件是 `closing == true`，而 `resetClosing()` 会把 closing 置回
        // false —— 一旦用户在上一轮返回后 800ms 内再次打开，协程会带着"已 closing"的
        // 残留判断直接睡 800ms，然后在**本次动画进行中**把锚点位图清掉，
        // 表现为"退回后闪一下封面"。这里先同步清干净，协程只负责本轮。
        lastDecorated?.let { last ->
            if (last !== anchor) {
                last.foreground = null
                last.background = null
            }
        }
        lastDecorated = null
        // 离开上一次会话时留下的 closing 标记必须在这里清掉（而不是等注册成功之后）：
        // 若上一次以 finishWithDefaultTransition 收起后又立刻打开新卡片，closing 会残留为 true，
        // 本函数启动的清理协程会在第一个 64ms 轮询就通过 closing 门、进入 800ms 延时，
        // 若用户在 800ms 内连续开关卡片，它就会在新的打开动画进行中把锚点过渡位图清掉
        // ——表现为打开/返回动画中途丢内容（一帧封面闪空白）。
        SeamlessTransitionState.resetClosing()
        // 装饰锚点：框架要"绘制 view 内容"时拿到这张图。
        // 两条路径（见 coverHeightPx 参数注释）：
        // - coverHeightPx > 0（卡片场景）：合成**整卡预览**（封面 + 简介区底色），
        //   比例与锚点一致，两个方向都不会拉伸、不会黑；
        // - 否则：退回纯封面图（小横条等锚点本就等于封面矩形的场景）。
        val cover = if (decorateAnchor) {
            // 优先级 1：整卡真实像素。这是唯一含"简介文字"的内容，必须优先。
            if (cardBitmap != null && !cardBitmap.isRecycled) {
                Log.i(
                    TAG,
                    "using frozen card bitmap ${cardBitmap.width}x${cardBitmap.height} " +
                        "for anchor ${anchor.width}x${anchor.height}"
                )
                SoftwareCover(cardBitmap)
            } else {
                // 优先级 2：退回封面图（小横条等锚点本就等于封面矩形的场景）。
                // 卡片场景若走到这里，简介区将没有文字——说明冻结失败，需查上游。
                val base = buildSoftwareCoverDrawable(anchor, coverUrl, coverLarge, anchorRadiusPx)
                if (cardBitmap == null) {
                    Log.w(
                        TAG,
                        "cardBitmap unavailable (freeze failed?) -> falling back to cover-only; " +
                            "info band will have no text"
                    )
                }
                base
            }
        } else {
            // 不装饰：锚点保持零绘制，让 BUNDLE_COLOR 承担兜底
            null
        }
        if (cover != null) {
            // 与下方 CoverImage 同像素（同图同圆角），平时不可见；框架"绘制 view 内容"
            // 时即拿到封面。background/foreground 双通道，覆盖框架不同的绘制路径。
            val drawable = cover.drawableFor(anchor.resources, anchorRadiusPx)
            anchor.background = drawable
            anchor.foreground = drawable
            Log.i(
                TAG,
                "anchor decorated: preview=${cover.bitmap.width}x${cover.bitmap.height} " +
                    "anchor=${anchor.width}x${anchor.height} coverH=$coverHeightPx " +
                    "isCardPreview=${coverHeightPx > 0}"
            )
        } else {
            Log.w(
                TAG,
                "anchor NOT decorated (decorateAnchor=$decorateAnchor, coverH=$coverHeightPx) " +
                    "-> return direction will have no preview content"
            )
        }
        val bundle = OplusViewSeamlessCompat.createOpenBundle(
            anchorRadiusPx = anchorRadiusPx,
            fallbackColor = fallbackColor,
            listCoverTopRatio = listCover?.first,
            listCoverBottomRatio = listCover?.second,
            // 取景矩形：调用方显式传入"封面区域"时用它，否则退回整个锚点矩形。
            // 卡片场景必须传封面区域，否则封面位图会被拉伸铺满整卡（见参数注释）。
            contentRect = contentRect ?: anchor.screenRect(),
            // 卡片都在列表里：返回时 view 位置相较打开时几乎必然变化，按官方文档
            // 改为 alpha 淡出（无回弹），避免 leash 回弹到已滚走的旧位置
            alphaOutOnPositionChange = true,
            keepAnchorVisible = keepAnchorVisible
        )
        val registered = OplusViewSeamlessCompat.registerOpenTransition(
            anchor,
            bundle,
            Callback(anchor, bundle, cover?.bitmap)
        )
        if (!registered) {
            anchor.foreground = null
            anchor.background = null
            Log.i(TAG, "registerOpenTransition=false, fallback to plain open")
            return false
        }
        Log.i(TAG, "registerOpenTransition ok, seamless open pending")
        // 诊断：锚点矩形。锚点铺满整卡后比例应接近 1:1（封面 4:3 + 简介区），
        // 若仍是 0.75 左右说明锚点又退回只覆盖封面，"简介没被带上"会复现。
        if (Logger.isDebug) {
            anchor.screenRect()?.let { r ->
                Log.d(
                    TAG,
                    "anchor rect=${r.width()}x${r.height()} " +
                        "ratio=${"%.2f".format(r.height().toFloat() / r.width())}"
                )
            }
        }
        SeamlessTransitionState.resetClosing()
        SeamlessTransitionState.beginOpen()
        // 记录源卡片：只有这张卡会在过渡期间淡出自身内容（封面 + 简介区）。
        // 顺序重要：必须在 beginOpen 之后，因为 endOpen/超时兜底会清掉这个值。
        SeamlessTransitionState.markSourceCard(sourceCardKey)
        lastDecorated = anchor
        cleanupJob = scheduleAnchorCleanup(anchor)
        route(target, coverUrl, coverLarge, bundle)
        return true
    }

    /**
     * 锚点过渡位图的确定性清理。实测系统 AnimationCallback 在部分固件上不送达，
     * 若只依赖 onEnd(isOpen=false) 清理，位图会永久残留为卡片前景，盖住封面上的
     * 播放量/时长角标。策略：视频页收起（markClosing / beginReturn）后延迟 800ms
     * 清理——成对返回动画约 450ms，此时刻已在动画结束之后；清理仍以 onEnd 为准
     * （回调正常时更精确），此处仅兜底。
     */
    private fun scheduleAnchorCleanup(anchor: View): Job? {
        val scope = cleanupScope ?: return null
        return scope.launch {
            var waited = 0L
            while (!SeamlessTransitionState.closing) {
                delay(64)
                waited += 64
                if (waited > 60_000L) return@launch
            }
            delay(800)
            if (lastDecorated === anchor) {
                Log.i(TAG, "cleanup anchor decoration (fallback, callback missed)")
                anchor.foreground = null
                anchor.background = null
                lastDecorated = null
            }
        }
    }

    /** 点击瞬间读取锚点屏幕矩形：系统取景计算失败时的兜底（纯内存读，无 IO）。 */
    private fun View.screenRect(): Rect? {
        if (width <= 0 || height <= 0) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
    }

    /**
     * 一张封面对应的软件位图 + 按圆角半径缓存的 drawable。
     *
     * 为什么 drawable 也要缓存：`RoundedBitmapDrawableFactory.create` 对 960×540 位图
     * 实测 5~10ms（真机 5 轮采样：5158~10337us，均值 6767us），而它原本发生在**点击那一帧**
     * ——正是系统准备 Activity 窗口 + 启动无缝动画的时刻。位图内容与圆角半径在同一张卡上
     * 是稳定的，因此按半径缓存 drawable，命中后点击帧只剩一次哈希查找。
     */
    private data class SoftwareCover(
        val bitmap: Bitmap,
        private val drawablesByRadius: MutableMap<Float, Drawable> = HashMap(2)
    ) {
        fun drawableFor(
            resources: android.content.res.Resources,
            radiusPx: Float
        ): Drawable = drawablesByRadius.getOrPut(radiusPx) {
            RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                cornerRadius = radiusPx
                setAntiAlias(true)
            }
        }

        /**
         * 合成"整卡预览图"：上半部分画封面、下半部分填简介区底色。
         *
         * ## 为什么必须合成而不是直接拉伸封面
         *
         * 锚点铺满整卡后，锚点边界比例（近 1:1）与封面比例（4:3）不同。
         * 直接把封面位图设为锚点背景，`RoundedBitmapDrawable` 会把它**拉伸填满**，
         * 于是预览图纵向拉长、盖住简介区（实测：点击后预览图先盖住简介再开始动画）。
         *
         * 这里按锚点真实尺寸新建位图，封面按**自身比例**居中裁切画在上部
         * （与卡片里 `ContentScale.Crop` 的观感一致），下部填卡片信息区底色。
         * 结果是"整张卡片长什么样"，两个方向的过渡都不会拉伸、也不会黑。
         *
         * @param infoColor 简介区底色（ARGB）。null 时用深灰兜底，避免出现纯黑。
         */
        fun withCardPreview(
            anchorWidthPx: Int,
            anchorHeightPx: Int,
            coverHeightPx: Int,
            infoColor: Int?,
        ): SoftwareCover? {
            if (anchorWidthPx <= 0 || anchorHeightPx <= 0 || coverHeightPx <= 0) return null
            // 封面高度不能超过锚点高度（布局异常时保护，避免 drawBitmap 越界）
            val coverH = coverHeightPx.coerceIn(1, anchorHeightPx)
            return runCatching {
                val out = Bitmap.createBitmap(anchorWidthPx, anchorHeightPx, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(out)
                // 下部：简介区底色。用卡片传入的真实底色，保证与屏幕所见一致。
                canvas.drawColor(infoColor ?: DEFAULT_INFO_BAND_COLOR)
                // 上部：封面，按 Crop 语义铺满 cover 区域
                val src = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                val dst = android.graphics.Rect(0, 0, anchorWidthPx, coverH)
                canvas.drawBitmap(bitmap, src, dst, android.graphics.Paint(Paint.FILTER_BITMAP_FLAG))
                SoftwareCover(out)
            }.getOrNull()
        }
    }

    /** 从内存缓存取封面位图并转软件配置（返回阶段框架快照为软件画布，可绘制）。 */
    private fun buildSoftwareCoverDrawable(
        anchor: View,
        coverUrl: String?,
        coverLarge: Boolean,
        radiusPx: Float
    ): SoftwareCover? {
        val resolved = if (coverLarge) {
            coverUrlFor(coverUrl, BiliCoverSize.Large)
        } else {
            coverUrlFor(coverUrl, BiliCoverSize.Small43)
        } ?: return null
        memoGet(resolved)?.let { return it }
        val image = SingletonImageLoader.get(anchor.context)
            .memoryCache
            ?.get(MemoryCache.Key(resolved))
            ?.image
        val cached = (image as? BitmapImage)?.bitmap
        if (cached == null) {
            Log.d(TAG, "cover bitmap not in memory cache: $resolved")
            return null
        }
        Log.d(TAG, "cover bitmap found, config=${cached.config}")
        // 系统返回阶段快照为软件画布：硬件位图必须转软件配置，否则绘制抛异常
        val safeBitmap = if (cached.config == Bitmap.Config.HARDWARE) {
            runCatching { cached.copy(Bitmap.Config.ARGB_8888, false) }
                .getOrNull()
                ?.also { Log.d(TAG, "hardware bitmap copied to software") }
                ?: return null
        } else {
            cached
        }
        return SoftwareCover(safeBitmap).also { memoPut(resolved, it) }
    }

    /**
     * 每次注册独立的回调（携带锚点与兜底位图）。
     * 打开结束 → 软件位图补进 bundle（返回内容兜底）；返回结束 → 清理锚点内容。
     */
    private class Callback(
        private val anchor: View,
        private val bundle: Bundle,
        private val coverBitmap: Bitmap?
    ) : OplusViewSeamlessCompat.AnimationCallback() {

        override fun onProgress(progress: Float) {
            // 转发给全局状态：源卡片据此**按系统真实进度**淡出内容，
            // 而不是按猜测的时长（实测该回调每帧送达，见 SeamlessTransitionState.progress）。
            SeamlessTransitionState.onProgress(progress)
            // 诊断：逐帧进度日志量很大（一帧一条），仅在 debug 打，避免 release 刷屏
            if (Logger.isDebug) Log.i(TAG, "progress=$progress")
        }

        override fun onStart(isOpen: Boolean) {
            // 诊断动画问题：确认系统回调是否送达（缺失 = 应用走 1.2s/1.6s 超时兜底）
            Log.i(TAG, "onAnimationStart isOpen=$isOpen")
            if (isOpen) SeamlessTransitionState.beginOpen() else SeamlessTransitionState.beginReturn()
        }

        override fun onEnd(isOpen: Boolean) {
            Log.i(TAG, "onAnimationEnd isOpen=$isOpen")
            if (isOpen) {
                SeamlessTransitionState.endOpen()
                // 打开已完成：补进 BUNDLE_BITMAP 不影响开画面面；若框架在返回开始时
                // 重新读取 bundle，则该位图作为"绘制内容错误"的内容兜底
                coverBitmap?.let {
                    // 回调由系统线程路径触发，任何异常都不应外抛
                    runCatching {
                        bundle.putParcelable(OplusViewSeamless.BUNDLE_BITMAP, it)
                        Log.d(TAG, "BUNDLE_BITMAP attached for return fallback")
                    }
                }
            } else {
                SeamlessTransitionState.endReturn()
                anchor.foreground = null
                anchor.background = null
            }
        }
    }

    private companion object {
        const val TAG = "OplusViewSeamless"

        /**
         * 软件封面 LRU 容量。4 条约 8MB ARGB_8888（960×540×4B），
         * 覆盖"首页首屏几张卡来回点 + 连续开退同一张卡"的真实场景。
         */
        const val COVER_MEMO_CAPACITY = 4

        /**
         * 简介区兜底底色（深灰）。
         *
         * 为什么不透明黑：锚点铺满整卡后，简介区若画成纯黑，过渡途中会出现
         * 一道明显黑带（历史上正是"返回时简介部分是黑的"的观感来源之一）。
         * 正常路径会由卡片传入真实底色（`fallbackColor`），这里只是兜底。
         */
        const val DEFAULT_INFO_BAND_COLOR = 0xFF2B2B2B.toInt()
    }
}

/** App 层在根组合提供；null 表示当前环境未接线（页面点击直接走普通打开）。 */
val LocalSeamlessCardOpener = staticCompositionLocalOf<SeamlessCardOpener?> { null }
