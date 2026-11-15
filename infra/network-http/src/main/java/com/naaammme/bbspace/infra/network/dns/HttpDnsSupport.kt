package com.naaammme.bbspace.infra.network.dns

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * FL-11：**多源并行的首个成功**编排（纯逻辑，JVM 可测）。
 *
 * 原实现是 `for (server in SERVERS) { resolve(server) ?: continue }` —— 5 个服务器
 * 串行探测，最坏 5×1s 叠加在首帧关键路径上（PF-01 把它列为真机手感项）。
 * 本函数并行提交、按预算等待、首个非空结果即返回并取消其余。
 *
 * 契约：
 * - 总耗时 ≤ [totalBudgetMs]（全部失败也**不**线性叠加）；
 * - 并发度由调用方给的 [executor] 决定（须是有界池）；
 * - 任一任务抛异常按"该源失败"处理，继续等其余源。
 */
internal fun <T> parallelFirstSuccess(
    tasks: List<Callable<T?>>,
    executor: ExecutorService,
    totalBudgetMs: Long
): T? {
    if (tasks.isEmpty()) return null
    val futures: List<Future<T?>> = tasks.map { executor.submit(it) }
    val deadline = System.nanoTime() + totalBudgetMs * 1_000_000L
    try {
        for (future in futures) {
            val remainMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainMs <= 0L) break
            val value = try {
                future.get(remainMs, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                null
            }
            if (value != null) return value
        }
    } finally {
        futures.forEach { it.cancel(true) }
    }
    return null
}

/**
 * W8：**全 DNS 子系统唯一的命名有界 IO 池**。
 *
 * 修复前有两份 `newFixedThreadPool(3)`：本文件的 `http-dns-probe`（HTTPDNS 多源并行探测）
 * 与 [BiliDns] 的 `bili-dns-refresh`（DNS 预热/过期异步刷新）。两份池各自的线程名不同、
 * 互不知情，出问题时 `adb shell ps -T` 只能看到两撮匿名线程，抓栈也分不清"这是探测还是刷新"，
 * 而两者本质是同一类工作（阻塞式 DNS HTTP 读），完全没必要各养 3 个线程。
 *
 * 现在合并为**一个** 3 线程命名池（线程数**不增**：原 3+3=6 → 3），线程名带序号
 * (`bili-dns-io-1..3`) 便于 `debuggerd -b <tid>` / `ps -T` 直接定位。
 *
 * 并发语义不变：仍是最多 3 个 DNS IO 任务同时进行；`parallelFirstSuccess` 的"最多 3 并发"
 * 与 BiliDns 的刷新任务共享这 3 个槽位 —— 最坏情况下互相排队（本来就在同一条网络瓶颈上），
 * 但换来"全仓只有一处 DNS IO 线程来源"。
 */
internal object DnsIo {
    /** 与合并前的单池一致（3）；**不许**为了"看起来更快"调大（会放大 DNS 请求风暴）。 */
    const val THREADS = 3

    private val seq = java.util.concurrent.atomic.AtomicInteger(0)

    val executor: ExecutorService = Executors.newFixedThreadPool(THREADS) { runnable ->
        Thread(runnable, "bili-dns-io-${seq.incrementAndGet()}").apply { isDaemon = true }
    }
}

/** FL-11：HTTPDNS 服务器并行探测池（W8 起与 DNS 刷新共用 [DnsIo.executor]，线程数不增）。 */
internal val httpDnsProbeExecutor: ExecutorService get() = DnsIo.executor

// 默认 1s 超时的 HTTPDNS/DoH GET, 失败抛异常由调用方记录
// （FL-11：原注释写"2s 超时"，与默认值 1000ms 不符；PF-01 已把超时值列为
//  需真机的权衡项，本轮只纠注释、不动数值）
internal fun httpDnsGet(
    url: String,
    timeoutMs: Int = 1000,
    headers: Map<String, String> = emptyMap()
): String {
    var conn: HttpURLConnection? = null
    try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            headers.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }
        return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
    } finally {
        conn?.disconnect()
    }
}

// 解析 {dns:[{ttl,ips:[...]}]} 明文响应, 阿里和 B 站自建 HTTPDNS 共用
internal fun parseDnsResultJson(response: String): DnsResult? {
    val dnsArray = JSONObject(response).optJSONArray("dns") ?: return null
    val addresses = mutableListOf<InetAddress>()
    var minTtl = Long.MAX_VALUE
    for (i in 0 until dnsArray.length()) {
        val entry = dnsArray.getJSONObject(i)
        val ttl = entry.optLong("ttl", 60L)
        if (ttl < minTtl) minTtl = ttl
        val ips = entry.optJSONArray("ips") ?: continue
        for (j in 0 until ips.length()) {
            try {
                addresses.add(InetAddress.getByName(ips.getString(j)))
            } catch (_: Exception) {
            }
        }
    }
    val distinct = addresses.distinct()
    if (distinct.isEmpty()) return null
    val ttl = if (minTtl == Long.MAX_VALUE) 60L else minTtl
    return DnsResult(distinct, ttl)
}

// 解析 DoH JSON 响应，只收 A/AAAA 记录并取最小 TTL
internal fun parseDohDnsResultJson(response: String): DnsResult? {
    val json = JSONObject(response)
    if (json.optInt("Status", -1) != 0) return null
    val answers = json.optJSONArray("Answer") ?: return null
    val addresses = mutableListOf<InetAddress>()
    var minTtl = Long.MAX_VALUE
    for (i in 0 until answers.length()) {
        val entry = answers.getJSONObject(i)
        when (entry.optInt("type")) {
            1, 28 -> {
                val ttl = entry.optLong("TTL", 60L)
                if (ttl < minTtl) minTtl = ttl
                val ip = entry.optString("data")
                if (ip.isBlank()) continue
                try {
                    addresses.add(InetAddress.getByName(ip))
                } catch (_: Exception) {
                }
            }
        }
    }
    val distinct = addresses.distinct()
    if (distinct.isEmpty()) return null
    val ttl = if (minTtl == Long.MAX_VALUE) 60L else minTtl
    return DnsResult(distinct, ttl)
}
