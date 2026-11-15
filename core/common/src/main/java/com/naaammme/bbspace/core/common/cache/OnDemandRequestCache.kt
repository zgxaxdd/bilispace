package com.naaammme.bbspace.core.common.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 按需请求的**在途合并 + 短期结果缓存**（B12，2026-10-13 上游 v0.3.6 同步）。
 *
 * ## 解决的缺陷（本仓真实存在，不是照搬上游）
 *
 * 竖屏连播有两条**独立发起**的 playurl 请求路径：
 * - 预取：`VideoViewModel.prefetchEntry()` → `preloadPlayUrl()`（`viewModelScope.launch`）；
 * - 开播：用户滑到该页 → `StreamPlaybackSessionImpl` → `fetchPlaybackSource()`。
 *
 * 两者**共用同一份 `PlayUrlCache`**，但缓存只在**请求成功之后**才写入。于是
 * "预取在途、用户已经滑到"这个窗口里，两条路径都会走 cache miss ⇒
 * **同一个 key 发出两次完整 gRPC 往返**，而它们要的完全是同一份数据。
 * 这个窗口在快速连滑时很常见（预取是滑动方向上的下一支，用户滑得快就追上了）。
 *
 * ## 语义（照上游 `OnDemandRequestCache`，逐条对齐）
 *
 * 1. **在途合并**：同 key 的并发调用共享**同一个** `Deferred`，只发一次请求；
 * 2. **等待者计数取消**：最后一个等待者离开时，若请求仍未完成则**取消**它 ——
 *    预取是"尽力而为"，用户改滑别处后那次请求就没有意义了，不该继续占带宽；
 * 3. **失败永不缓存**：`canCache` 默认只收 `isSuccess`。把失败缓存住会让
 *    "网络抖动一次 ⇒ 该视频在 TTL 内再也开不了"；
 * 4. **`cacheGeneration` 守卫**：`clearCompleted()` 之后，仍在途的旧请求**不得**把
 *    它的结果写回缓存（用户刚清过缓存，旧结果不该复活）。
 *
 * ## 与上游的差异（本仓形态决定）
 *
 * - 上游默认 `nowMs = System.nanoTime()/1_000_000`；本仓**必须注入** `nowMs`
 *   （无默认值）—— 本仓有 `PlayUrlCache` 用 `SystemClock.elapsedRealtime()` 的先例，
 *   测试里也要能自由推进时钟。默认值会让人忘了注入，注入点比默认值更安全。
 * - 上游 `scope` 默认自建 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`；本仓
 *   同样保留默认，但**调用方应显式传**自己的 scope（避免这个 scope 活得比持有者久）。
 *
 * ## 为什么不用现成的
 *
 * 本仓全仓唯一的在途 map 是 `VideoCardCoverColorStore`（`core/designsystem/.../color/`），
 * 而它**丢弃**重复调用者而不是共享结果 —— 那是"后到者直接返回 null"的语义，
 * 用在这里会让后到的播放请求拿不到流。语义不同，不能复用。
 *
 * 纯逻辑 + 注入时钟 + 注入 scope，JVM 单测可完全覆盖（含并发与取消）。
 */
class OnDemandRequestCache<Key : Any, Value>(
    private val maxEntries: Int,
    private val maxAgeMs: Long,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 哪些结果值得缓存。默认 `true`；取流场景应传 `{ it.isSuccess }`（失败永不缓存）。 */
    private val canCache: (Value) -> Boolean = { true },
) {

    init {
        require(maxEntries > 0) { "maxEntries 必须为正，实际 $maxEntries" }
        require(maxAgeMs >= 0L) { "maxAgeMs 不能为负，实际 $maxAgeMs" }
    }

    private data class Cached<Value>(val value: Value, val expiresAtMs: Long)

    /**
     * 加载结果：**把失败收成值**，而不是让 `Deferred` 失败。
     *
     * ## 为什么（本仓相对上游的一处加固）
     *
     * 上游直接写 `val value = load()` —— 加载失败时 `async` 就**失败**。而 `async` 失败
     * 会把异常同时送达两处：`await()` 的调用者（我们要的），以及**所属 scope 的
     * `CoroutineExceptionHandler`**（我们不要的）。后者在真机上意味着一次取流失败
     * 可能被上报成"未捕获异常"，而调用方其实已经妥善处理了它。
     *
     * 所以在这里把失败收成 `Err` 值：`Deferred` 只以两种方式结束 —— 成功（`Ok`）
     * 或取消（`CancellationException` 照常向上抛，取消不是错误）。调用方在
     * [getOrLoad] 里再把 `Err` 展开成异常抛出，对外语义完全等价，
     * 但 scope 永远看不到"未捕获的失败"。
     */
    private sealed interface LoadResult<out V> {
        data class Ok<V>(val value: V) : LoadResult<V>
        data class Err(val cause: Throwable) : LoadResult<Nothing>
    }

    private class InFlight<Value> {
        lateinit var deferred: Deferred<LoadResult<Value>>
        var waiters: Int = 0
    }

    private val lock = Any()

    /** accessOrder = true：命中即移到队尾，淘汰最久未用的（与 `PlayUrlCache` 同款）。 */
    private val cached = LinkedHashMap<Key, Cached<Value>>(16, 0.75f, true)
    private val inFlight = mutableMapOf<Key, InFlight<Value>>()

    /** 代际：`clearCompleted()` 自增，用来让"清空之前发出的在途请求"无法回写。 */
    private var cacheGeneration = 0L

    /** 只读缓存（不触发加载）。命中且未过期才返回。 */
    fun peek(key: Key): Value? = synchronized(lock) {
        val entry = cached[key] ?: return@synchronized null
        if (nowMs() >= entry.expiresAtMs) {
            cached.remove(key)
            null
        } else {
            entry.value
        }
    }

    /**
     * 取缓存或加载。同 key 的并发调用**共享同一次加载**。
     *
     * @param load 真正的加载动作（只在没有可用缓存、且没有在途请求时执行一次）。
     */
    suspend fun getOrLoad(key: Key, load: suspend () -> Value): Value {
        currentCoroutineContext().ensureActive()

        val request = synchronized(lock) {
            val entry = cached[key]
            if (entry != null && nowMs() < entry.expiresAtMs) return entry.value
            // 过期条目立刻清掉：否则下面的 `canCache` 写入会与它并存到 LRU 淘汰
            cached.remove(key)

            val existing = inFlight[key]
            val selected = existing ?: InFlight<Value>().also { created ->
                val generation = cacheGeneration
                // LAZY：先登记进 inFlight、再 start —— 否则 load() 可能在
                // `inFlight[key] = created` 之前就跑完，第二个调用者会看不到它。
                created.deferred = scope.async(start = CoroutineStart.LAZY) {
                    // 失败收成值（见 LoadResult 的 KDoc）：CancellationException 必须
                    // 原样抛出 —— 取消不是失败，吞掉它会让"最后一个等待者离开就取消"
                    // 这条语义失效。
                    val loaded: LoadResult<Value> = try {
                        LoadResult.Ok(load())
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (t: Throwable) {
                        LoadResult.Err(t)
                    }
                    currentCoroutineContext().ensureActive()
                    if (loaded is LoadResult.Ok && maxAgeMs > 0L && canCache(loaded.value)) {
                        synchronized(lock) {
                            // 双守卫：① 这个 deferred 仍是在途的那一个（没被 invalidate 换掉）；
                            // ② 期间没有 clearCompleted()（旧代际的结果不得复活）。
                            if (inFlight[key] === created && cacheGeneration == generation) {
                                cached[key] = Cached(loaded.value, nowMs() + maxAgeMs)
                                while (cached.size > maxEntries) {
                                    cached.remove(cached.keys.first())
                                }
                            }
                        }
                    }
                    loaded
                }
                inFlight[key] = created
            }
            selected.waiters += 1
            selected
        }

        return try {
            when (val result = request.deferred.await()) {
                is LoadResult.Ok -> {
                    currentCoroutineContext().ensureActive()
                    result.value
                }
                // 把收好的失败原样展开 —— 对外语义与"Deferred 直接失败"完全一致。
                is LoadResult.Err -> throw result.cause
            }
        } finally {
            synchronized(lock) {
                request.waiters -= 1
                if (request.waiters == 0) {
                    if (inFlight[key] === request) inFlight.remove(key)
                    // 没有等待者了 ⇒ 这次加载失去意义（预取场景：用户已经滑走）。
                    // 已经完成的不必取消；未完成的取消掉，别继续占带宽。
                    if (!request.deferred.isCompleted) request.deferred.cancel()
                }
            }
        }
    }

    /** 失效单个键：连缓存带在途一起作废。 */
    fun invalidate(key: Key) {
        synchronized(lock) {
            cached.remove(key)
            inFlight.remove(key)?.deferred?.cancel()
        }
    }

    /** 清空已完成的缓存，并**让当前在途请求失去回写资格**（代际自增）。 */
    fun clearCompleted() {
        synchronized(lock) {
            cached.clear()
            cacheGeneration += 1L
        }
    }

    /** 按谓词批量失效（缓存 + 在途）。 */
    fun invalidateWhere(matches: (Key) -> Boolean) {
        synchronized(lock) {
            cached.keys.filter(matches).forEach { key -> cached.remove(key) }
            inFlight.keys.filter(matches).forEach { key ->
                inFlight.remove(key)?.deferred?.cancel()
            }
        }
    }

    /** 仅供测试/诊断：当前在途请求数。 */
    fun inFlightCount(): Int = synchronized(lock) { inFlight.size }

    /** 仅供测试/诊断：当前缓存条目数。 */
    fun cachedCount(): Int = synchronized(lock) { cached.size }
}
