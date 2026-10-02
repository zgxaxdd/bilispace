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

/** FL-11：HTTPDNS 服务器并行探测的共享有界池（上限 3 并发，daemon 不挡进程退出）。 */
internal val httpDnsProbeExecutor: ExecutorService =
    Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "http-dns-probe").apply { isDaemon = true }
    }

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
