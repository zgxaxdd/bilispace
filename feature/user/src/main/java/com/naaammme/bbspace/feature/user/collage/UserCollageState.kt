package com.naaammme.bbspace.feature.user.collage

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 我的页「拼贴」拖拽位置的会话级状态。
 *
 * ## 2026-10-19：**组合期不再碰磁盘**（真机 StrictMode 实测）
 *
 * 修复前：
 * ```kotlin
 * val prefs = remember(context) { context.getSharedPreferences(...) }   // ← 主线程磁盘读
 * val state = remember(prefs) { UserCollageState(prefs, loadUserCollageOffsets(prefs)) }
 * ```
 * `getSharedPreferences` 会 `ensurePrivateDirExists`（`File.exists`）+ 首次读取时把整份 XML
 * 解析进内存，全都在**主线程的组合期**发生。真机（PLC110 / debug 包 + StrictMode）抓到：
 *
 * ```
 * StrictMode policy violation; ~duration=65 ms: DiskReadViolation
 *   at java.io.File.exists(File.java:829)
 *   at android.app.ContextImpl.getSharedPreferencesPath(ContextImpl.java:1002)
 *   at ...UserCollageStateKt.rememberUserCollageState(UserCollageState.kt:23)
 *   at ...UserScreenKt.UserScreen(UserScreen.kt:52)
 * ```
 *
 * 65ms ≈ 掉 4 帧，且发生在**进入我的页的第一帧**上 —— 正是"进页面会顿一下"的来源。
 *
 * ## 现在的形状
 * 1. 组合期**只创建内存对象**（`remember { UserCollageState() }`，不碰 Context）；
 * 2. 打开 prefs 与解析偏移放到 `Dispatchers.IO`，完成后 `attach(...)` 喂进来；
 * 3. `attach` 只补**还没被用户改过**的 key（以防极端时序下把刚拖过的位置覆盖回旧值），
 *    并把 attach 之前攒下的 dirty 立刻落盘。
 *
 * 落盘（`flush`）本身走 `edit { }`（= `apply()`，异步写），不产生主线程磁盘读；
 * 但 `prefs` 还没 attach 时一律**不丢 dirty**，等 attach 后补写 —— 不能因为"慢了一步"
 * 就把用户的拖拽结果吞掉。
 */
@Composable
internal fun rememberUserCollageState(): UserCollageState {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    // 组合期不碰磁盘（见文件 KDoc）：先拿一个空壳，真实数据从 IO 回来再 attach。
    val state = remember { UserCollageState() }
    LaunchedEffect(state) {
        val prefs = withContext(Dispatchers.IO) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        val offsets = withContext(Dispatchers.IO) { loadUserCollageOffsets(prefs) }
        state.attach(prefs, offsets)
    }

    DisposableEffect(owner, state) {
        val lifecycle = owner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.flush()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    return state
}

@Stable
internal class UserCollageState {
    val offsets = mutableStateMapOf<String, Offset>()

    /**
     * prefs 在 IO 上打开后才可用（见 [attach]）。
     *
     * `@Volatile`：写发生在协程（IO 返回后回到组合线程），读发生在生命周期回调，
     * 两者不在同一线程上时也要看到最新值。
     */
    @Volatile
    private var prefs: SharedPreferences? = null

    /** 有没有未落盘的改动。attach 之前也保持为 true，避免丢写。 */
    @Volatile
    private var dirty = false

    /**
     * 接上真实的 prefs 与已存的偏移（由 [rememberUserCollageState] 在 IO 上取好后调用）。
     *
     * 只补**还没有值**的 key：极端时序下用户可能已经开始拖动（`offsets` 里已有该 key），
     * 那一次拖拽必须赢，不能被磁盘上的旧位置覆盖。
     */
    fun attach(prefs: SharedPreferences, initialOffsets: Map<String, Offset>) {
        this.prefs = prefs
        initialOffsets.forEach { (key, offset) ->
            if (!offsets.containsKey(key)) offsets[key] = offset
        }
        // attach 之前攒下的改动在这里补写（normal 路径下 dirty=false，是一次空跑）
        flush()
    }

    fun updateOffset(key: String, offset: Offset) {
        if (abs(offset.x) < COLLAGE_OFFSET_ZERO_EPSILON &&
            abs(offset.y) < COLLAGE_OFFSET_ZERO_EPSILON
        ) {
            offsets.remove(key)
        } else {
            offsets[key] = offset
        }
        dirty = true
    }

    fun reset() {
        offsets.clear()
        val current = prefs ?: run {
            // 还没 attach：记成 dirty，等 attach 后补写（否则磁盘上会留着旧布局）
            dirty = true
            return
        }
        dirty = false
        current.edit { remove(KEY_COLLAGE_LAYOUT) }
    }

    fun flush() {
        if (!dirty) return
        val current = prefs ?: return // 还没 attach：保留 dirty，等 attach 时补写
        dirty = false
        val encoded = encodeUserCollageOffsets(offsets)
        current.edit {
            if (encoded.isEmpty()) {
                remove(KEY_COLLAGE_LAYOUT)
            } else {
                putString(KEY_COLLAGE_LAYOUT, encoded)
            }
        }
    }
}

/** 编码成磁盘格式（`key:x,y|key:x,y`）。抽出来是为了让两个方向**只有一个格式来源**。 */
private fun encodeUserCollageOffsets(offsets: Map<String, Offset>): String =
    offsets.entries
        .sortedBy { it.key }
        .joinToString("|") { (key, offset) -> "$key:${offset.x},${offset.y}" }

private fun loadUserCollageOffsets(prefs: SharedPreferences): Map<String, Offset> {
    val encoded = prefs.getString(KEY_COLLAGE_LAYOUT, null)
        ?.takeIf(String::isNotBlank)
        ?: return emptyMap()

    return buildMap {
        encoded.split('|').forEach { item ->
            val keyValue = item.split(':', limit = 2)
            if (keyValue.size != 2 || keyValue[0].isBlank()) return@forEach
            val axes = keyValue[1].split(',', limit = 2)
            if (axes.size != 2) return@forEach
            val x = axes[0].toFloatOrNull() ?: return@forEach
            val y = axes[1].toFloatOrNull() ?: return@forEach
            put(keyValue[0], Offset(x, y))
        }
    }
}

private const val PREFS_NAME = "user_collage"
private const val KEY_COLLAGE_LAYOUT = "layout"
private const val COLLAGE_OFFSET_ZERO_EPSILON = 0.5f
