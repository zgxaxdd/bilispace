package com.naaammme.bbspace.infra.network.dns

import com.naaammme.bbspace.core.common.log.Logger
import java.util.concurrent.Callable

/**
 * 阿里 HTTPDNS
 * JSON 明文，支持 IPv4+IPv6
 *
 * 响应格式:
 * {
 *   "dns": [
 *     {"host": "...", "ips": ["1.2.3.4"], "type": 1,  "ttl": 51},
 *     {"host": "...", "ips": ["2409:..."], "type": 28, "ttl": 51}
 *   ]
 * }
 * type=1 是 IPv4 (A 记录)，type=28 是 IPv6 (AAAA 记录)
 */
object AlibabaHttpDns {
    private const val TAG = "AlibabaHttpDns"
    private const val ACCOUNT_ID = "191607"
    private val SERVERS = listOf(
        "203.119.206.8",
        "203.119.206.9",
        "203.119.204.80",
        "203.119.238.240",
        "203.119.238.194",
    )

    /**
     * 通过阿里 HTTPDNS 解析域名（FL-11：5 个服务器**并行**探测，首个成功即用）。
     *
     * 原实现串行 for 循环 —— 最坏 5×1s 叠加在首帧关键路径上（PF-01）。现在并行提交、
     * 总预算 [TOTAL_BUDGET_MS] 封顶：全部失败也不线性叠加。并发由共享有界池
     * （3 并发）约束。
     *
     * @return DnsResult 包含 IP 列表和 TTL，失败返回 null
     */
    fun resolve(hostname: String): DnsResult? {
        val result = parallelFirstSuccess(
            tasks = SERVERS.map { server -> Callable { resolveByServer(server, hostname) } },
            executor = httpDnsProbeExecutor,
            totalBudgetMs = TOTAL_BUDGET_MS
        )
        if (result == null) {
            Logger.w(TAG) { "Alibaba HTTPDNS resolve failed for $hostname" }
        }
        return result
    }

    private fun resolveByServer(server: String, hostname: String): DnsResult? {
        return try {
            val url = "http://$server/$ACCOUNT_ID/resolve?host=$hostname&query=4,6"
            parseDnsResultJson(httpDnsGet(url))
        } catch (e: Exception) {
            Logger.d(TAG) { "Alibaba HTTPDNS server $server failed for $hostname: ${e.message}" }
            null
        }
    }

    /** FL-11：全部源失败时的总耗时上限（并发下 ≈ 单源最坏值 + 调度开销）。 */
    private const val TOTAL_BUDGET_MS = 2_000L
}
