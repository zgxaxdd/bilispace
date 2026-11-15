package com.naaammme.bbspace.core.model.player

import java.net.URI

/**
 * CDN **属地优选 + 自定义线路**策略（纯逻辑，可 JVM 单测）。
 *
 * 对照 BiliPai `feature/plugin/CdnRegionPolicy.kt`（origin/main `b94edf6d1`）中的
 * "区域目录 / 属地选择 / 运营商排序 / Host 改写 / 自定义规则 / 来源优先级合并"六块，
 * 只保留与本仓管线契合的部分：
 *
 * | 上游函数 | 本文件 | 说明 |
 * |---|---|---|
 * | `loadCdnRegionCatalog` + `Json.decodeFromString` | [parseCatalog] | 本仓注入的是 raw JSON 文本，解析器手写（core/model 无 serialization 依赖） |
 * | `selectCdnRegionForLocation` | [selectRegion] | 城市 → 省 → 国家 → 海外 → 别名，逐级命中；未命中不回退随机区域 |
 * | `sortCdnRegionHostsForIsp` | [sortHostsForIsp] | 电信/移动/联通/广电前缀加权，稳定排序 |
 * | `rewriteBilivideoHost` | [rewriteHost] | 只改 bilivideo.com 系 host，path/query/port 原样保留 |
 * | `validateCdnCustomRules` / `compileCdnCustomRules` | [validateCustomRules] / [compileCustomRules] | 正则非法/空值/禁用逐条定性 |
 * | `rewritePlaybackCdnCandidatesForCustomRules` | [applyCustomRules] | 首条命中即停（不链式），替换结果必须仍指向 B 站域 |
 * | `selectPlaybackCdnCandidatesForMode` | [mergeCandidates] | 严格模式：有条自定义候选时**只用**它；否则自定义 + 属地 + 原始 |
 *
 * ## 为什么是纯函数 + 无全局状态
 *
 * 与 [CdnHealthPolicy] / [CdnSmartSelectionPolicy] 同口径：数值与判定在这里，
 * "谁触发、什么时候刷新、结果存哪"由调用方（`infra/player/cdn/CdnRegionPlugin` 与会话层）决定。
 * `nowMs` 一律由调用方传入，因此全部可 JVM 单测。
 *
 * ## 与上一轮流畅度修复的边界（重要）
 *
 * 本策略**只决定候选列表的内容与顺序**。并行分片的槽位预算、并行降级/恢复、
 * 卡顿阶梯恢复全部在 `infra/player/cdn/CdnPlaybackDataSource` 与 `CdnTransferRuntime`，
 * 本文件不 import、不读写任何 permits / parallel 状态 —— 插件不可能绕过槽位预算。
 */
object CdnRegionPolicy {

    /** 属地信息快照（上游 `IpLocationSnapshot` 的纯数据对应物）。 */
    data class IpLocation(
        val addr: String = "",
        val country: String = "",
        val province: String = "",
        val city: String = "",
        val isp: String = ""
    )

    /** 属地命中的结果。[fallbackUsed] = true 表示没有任何目录区域命中（调用方应保持原序）。 */
    data class RegionSelection(
        val region: String = "",
        val hosts: List<String> = emptyList(),
        val fallbackUsed: Boolean = true
    )

    /** 一条自定义改写规则（对照上游 `CdnCustomRule`）。 */
    data class CustomRule(
        val pattern: String = "",
        val replacement: String = "",
        val enabled: Boolean = true
    )

    /** 规则校验结果：`error == null` 才是可编译规则。 */
    data class RuleValidation(val rule: CustomRule, val error: String? = null)

    /** 候选来源：决定合并后的第一排序键（数值越大越优先）。 */
    enum class Source(val priority: Int) {
        /** 用户自定义规则生成的地址。 */
        Custom(3),

        /** 属地 Host 改写生成的地址。 */
        Region(2),

        /** 服务端原始地址（兜底）。 */
        Original(1)
    }

    /** 带来源的候选地址。 */
    data class Candidate(val url: String, val source: Source)

    /** 属地缓存有效期（对照上游 `CDN_REGION_LOCATION_TTL_MS`）。 */
    const val LOCATION_TTL_MS: Long = 24L * 60L * 60L * 1_000L

    /** 海外区域在目录里的键名（上游同值）。 */
    const val OVERSEAS_REGION = "海外"

    /**
     * 解析区域目录 JSON（形如 `{"上海": ["host", ...], "北京": [...]}`）。
     *
     * 纯 Kotlin 手写解析：core/model 没有 kotlinx.serialization 依赖，而目录格式是
     * **固定的"对象 → 字符串数组"**，不值得为它引依赖（违反"不许新增依赖"）。
     *
     * 语义与上游 `Json.decodeFromString<Map<String, List<String>>>` 对齐：
     * - 合法 JSON → 过滤空值/重复 host，保留顺序；空数组的区域被丢弃；
     * - 任何非法输入（截断、类型不符、未闭合）→ **空 Map**（上游 catch 后同样返回空表）。
     */
    fun parseCatalog(json: String?): Map<String, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { CatalogJsonReader(json).readRoot() }
            .getOrDefault(emptyMap())
            .filterValues { hosts -> hosts.any { it.isNotBlank() } }
            .mapValues { (_, hosts) -> hosts.filter { it.isNotBlank() }.distinct() }
    }

    /**
     * 按属地选区域（对照上游 `selectCdnRegionForLocation`）。
     *
     * 命中顺序：**城市 → 省 → 国家**（每个值都先原样、再规范化后各试一次）→
     * 非中国大陆且目录有"海外" → 省/市别名表；全部不中 ⇒ [RegionSelection.fallbackUsed]。
     */
    fun selectRegion(
        location: IpLocation,
        catalog: Map<String, List<String>>
    ): RegionSelection {
        val available = catalog.filterValues { it.isNotEmpty() }
        if (available.isEmpty()) return RegionSelection()
        val overseas = available[OVERSEAS_REGION]
        val normalizedCountry = normalizeRegionName(location.country)

        val directCandidates = listOf(location.city, location.province, location.country)
            .flatMap { value -> listOf(value, normalizeRegionName(value)) }
            .filter { it.isNotBlank() }
        directCandidates.firstNotNullOfOrNull { candidate ->
            available[candidate]?.let {
                return RegionSelection(candidate, sortHostsForIsp(it, location.isp), fallbackUsed = false)
            }
        }

        if (normalizedCountry.isNotBlank() && normalizedCountry !in MAINLAND_COUNTRY_NAMES) {
            overseas?.let {
                return RegionSelection(
                    OVERSEAS_REGION,
                    sortHostsForIsp(it, location.isp),
                    fallbackUsed = false
                )
            }
        }

        resolveAlias(location)?.let { alias ->
            available[alias]?.let {
                return RegionSelection(alias, sortHostsForIsp(it, location.isp), fallbackUsed = false)
            }
        }
        return RegionSelection()
    }

    /**
     * 按运营商给区域 host 加权：`-ct-`（电信）> `cm`（移动）> `cu`（联通）> `gd`（广电）。
     *
     * **稳定排序**（`sortedWith` 在 Kotlin/JVM 上是稳定的）：同分保持目录里的原序，
     * 与上游一致 —— 目录顺序本身也是"这个区域的常用顺序"。
     */
    fun sortHostsForIsp(hosts: List<String>, isp: String): List<String> {
        val carrier = resolveCarrierKey(isp) ?: return hosts.distinct()
        return hosts.distinct().sortedWith(
            compareByDescending<String> { host -> scoreHostForCarrier(host, carrier) }
        )
    }

    /**
     * 目录 host 与缓存 host 的合并（对照上游 `resolveCdnRegionHosts`）：
     * 缓存空 → 目录；目录空 → 缓存；缓存是目录子集 → 缓存（保留用户实际可用的子集）；
     * 否则目录（目录变了，旧缓存可能已失效）。
     */
    fun resolveHosts(
        region: String,
        cachedHosts: List<String>,
        catalog: Map<String, List<String>>,
        isp: String = ""
    ): List<String> {
        val catalogHosts = catalog[region].orEmpty()
        if (cachedHosts.isEmpty()) return sortHostsForIsp(catalogHosts, isp)
        if (catalogHosts.isEmpty()) return sortHostsForIsp(cachedHosts.distinct(), isp)
        return if (cachedHosts.all { it in catalogHosts }) {
            sortHostsForIsp(cachedHosts.distinct(), isp)
        } else {
            sortHostsForIsp(catalogHosts, isp)
        }
    }

    /** 缓存的选择是否仍然可用（区域非空、host 非空、且是目录里的子集）。 */
    fun hasUsableSelection(
        region: String,
        cachedHosts: List<String>,
        catalog: Map<String, List<String>>
    ): Boolean {
        if (region.isBlank() || cachedHosts.isEmpty()) return false
        val catalogHosts = catalog[region].orEmpty()
        if (catalogHosts.isEmpty()) return true
        return cachedHosts.all { it in catalogHosts }
    }

    /** 属地缓存该不该刷新（对照上游 `shouldRefreshCdnIpLocation`）。 */
    fun shouldRefreshLocation(
        enabled: Boolean,
        nowMs: Long,
        lastRefreshMs: Long,
        hasSelection: Boolean,
        ttlMs: Long = LOCATION_TTL_MS
    ): Boolean {
        if (!enabled) return false
        if (!hasSelection) return true
        if (lastRefreshMs <= 0L) return true
        return nowMs - lastRefreshMs >= ttlMs
    }

    /**
     * 日志里的 IP 打码（对照上游 `maskIpAddressForLog`）：
     * IPv4 保留前两段，IPv6 保留前两组，其余一律 `***`。
     */
    fun maskIp(addr: String): String {
        val trimmed = addr.trim()
        if (trimmed.isBlank()) return ""
        val ipv4 = trimmed.split(".")
        if (ipv4.size == 4 && ipv4.all { it.isNotBlank() }) {
            return "${ipv4[0]}.${ipv4[1]}.*.*"
        }
        val ipv6 = trimmed.split(":")
        if (ipv6.size > 2) return ipv6.take(2).joinToString(":") + ":*"
        return "***"
    }

    /**
     * 把 bilivideo.com 系 URL 的 host 换成 [newHost]；path/query/port/userinfo 原样保留。
     *
     * 只改写 **B 站自有域**：其它域（PCDN、代理、第三方镜像）一律返回 null ——
     * 上游同判据，避免把签名链接改到不受信的域上。
     */
    fun rewriteHost(url: String, newHost: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val originalHost = uri.host ?: return null
        if (originalHost != "bilivideo.com" && !originalHost.endsWith(".bilivideo.com")) return null
        if (newHost.isBlank()) return null
        val scheme = uri.scheme ?: return null
        val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$scheme://$userInfo$newHost$port$path$query$fragment"
    }

    /**
     * 按目标 host 批量生成改写候选（对照上游 `rewriteCdnUrlCandidates`）：
     * 改写结果排在前面、原始地址接在后面兜底，整体去重。
     */
    fun rewriteUrls(originalUrls: List<String>, preferredHosts: List<String>): List<String> {
        if (originalUrls.isEmpty() || preferredHosts.isEmpty()) return originalUrls.distinct()
        return buildList {
            originalUrls.forEach { original ->
                preferredHosts.forEach { host ->
                    rewriteHost(original, host)?.let { add(it) }
                }
            }
            addAll(originalUrls)
        }.distinct()
    }

    /** 逐条校验自定义规则（对照上游 `validateCdnCustomRules`；禁用规则永远合法）。 */
    fun validateCustomRules(rules: List<CustomRule>): List<RuleValidation> = rules.map { rule ->
        val error = when {
            !rule.enabled -> null
            rule.pattern.isBlank() -> "匹配表达式不能为空"
            rule.replacement.isBlank() -> "替换内容不能为空"
            else -> runCatching { Regex(rule.pattern) }
                .exceptionOrNull()
                ?.message
                ?.let { "正则表达式无效：$it" }
        }
        RuleValidation(rule, error)
    }

    /** 只编译"启用且合法"的规则（对照上游 `compileCdnCustomRules`）。 */
    fun compileCustomRules(rules: List<CustomRule>): List<Pair<CustomRule, Regex>> =
        validateCustomRules(rules)
            .filter { it.rule.enabled && it.error == null }
            .map { it.rule to Regex(it.rule.pattern) }

    /**
     * 对一组 URL 应用自定义规则（对照上游 `rewritePlaybackCdnCandidatesForCompiledCustomRules`）。
     *
     * 关键语义（上游测试钉死的两条）：
     * 1. **首条命中的规则独占**：命中后无论替换是否被接受都不再尝试后续规则（不链式）；
     * 2. 替换结果必须仍是 https 且指向 B 站域或当前候选 host，否则**保留原 URL**（不受信目标一律拒绝）。
     *
     * @return 只包含**真的被改写**的地址（未命中的不返回），且顺序与输入一致。
     */
    fun applyCustomRules(
        originalUrls: List<String>,
        compiledRules: List<Pair<CustomRule, Regex>>
    ): List<String> {
        if (compiledRules.isEmpty()) return emptyList()
        val allowedHosts = originalUrls.map(::hostOf).filter { it.isNotBlank() }.toSet()
        return originalUrls.mapNotNull { original ->
            val rewritten = rewriteByCustomRules(original, compiledRules, allowedHosts)
            rewritten.takeIf { it != original }
        }
    }

    /**
     * 合并三路候选并排序（对照上游 `selectPlaybackCdnCandidatesForMode`）。
     *
     * - `strictCustomCdn == true` 且存在自定义候选 ⇒ **只返回自定义候选**
     *   （健康度再差也排第一，用户指定优先 —— 上游 `CdnRegionPolicyTest` 的产品契约）；
     * - 否则：自定义 → 属地 → 原始，三组内各自按健康度降序、同分保持原序；
     * - 自定义候选为空时严格模式也**完整回退**（否则会把播放锁死）。
     *
     * @return 已排序、已去重的候选列表（首元素 = 应作为主线路的地址）。
     */
    fun mergeCandidates(
        originalUrls: List<String>,
        regionHosts: List<String>,
        customRules: List<CustomRule>,
        strictCustomCdn: Boolean,
        healthByHost: Map<String, CdnHealthPolicy.Health>
    ): List<Candidate> {
        val originals = originalUrls.filter(String::isNotBlank).distinct()
        if (originals.isEmpty()) return emptyList()
        val custom = applyCustomRules(originals, compileCustomRules(customRules))
            .map { Candidate(it, Source.Custom) }
        // 只把"改写出来的"标成 Region；原始地址仍归 Original（兜底语义不变）。
        val region = if (regionHosts.isEmpty()) {
            emptyList()
        } else {
            rewriteUrls(originals, regionHosts)
                .filterNot { it in originals }
                .distinct()
                .map { Candidate(it, Source.Region) }
        }
        val originalCandidates = originals.map { Candidate(it, Source.Original) }
        val merged = if (strictCustomCdn && custom.isNotEmpty()) {
            custom
        } else {
            custom + region + originalCandidates
        }
        val distinct = merged.distinctBy { it.url }
        return CdnSmartSelectionPolicy.orderCandidates(
            candidates = distinct.map { CdnSmartSelectionPolicy.Candidate(it.url, it.source.toSelectorSource()) },
            healthByHost = healthByHost,
            strictPreferred = strictCustomCdn
        ).map { Candidate(it.url, it.source.fromSelectorSource()) }
    }

    /** 从 URL 取 host（小写）；取不到返回空串。 */
    fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")

    // ── 内部实现 ────────────────────────────────────────────────────────────

    private fun rewriteByCustomRules(
        originalUrl: String,
        rules: List<Pair<CustomRule, Regex>>,
        allowedHosts: Set<String>
    ): String {
        rules.forEach { (rule, regex) ->
            if (!regex.containsMatchIn(originalUrl)) return@forEach
            val replaced = runCatching { regex.replaceFirst(originalUrl, rule.replacement) }.getOrNull()
                ?: return@forEach
            return if (isAllowedCustomCdnUrl(replaced, allowedHosts)) replaced else originalUrl
        }
        return originalUrl
    }

    private fun isAllowedCustomCdnUrl(url: String, allowedHosts: Set<String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        return uri.scheme.equals("https", ignoreCase = true) &&
            (host == "bilivideo.com" || host.endsWith(".bilivideo.com") || host in allowedHosts)
    }

    private fun resolveAlias(location: IpLocation): String? {
        CITY_REGION_ALIASES[normalizeRegionName(location.city)]?.let { return it }
        return PROVINCE_REGION_ALIASES[normalizeRegionName(location.province)]
    }

    private fun normalizeRegionName(value: String): String = value.trim()
        .removeSuffix("特别行政区")
        .removeSuffix("壮族自治区")
        .removeSuffix("回族自治区")
        .removeSuffix("维吾尔自治区")
        .removeSuffix("自治区")
        .removeSuffix("省")
        .removeSuffix("市")

    private fun resolveCarrierKey(isp: String): String? {
        val normalized = isp.trim().lowercase()
        return when {
            normalized.contains("电信") || normalized.contains("chinanet") ||
                normalized.contains("telecom") -> "ct"
            normalized.contains("移动") || normalized.contains("cmcc") ||
                normalized.contains("mobile") -> "cm"
            normalized.contains("联通") || normalized.contains("unicom") -> "cu"
            normalized.contains("广电") || normalized.contains("broadcast") -> "gd"
            else -> null
        }
    }

    private fun scoreHostForCarrier(host: String, carrier: String): Int {
        val normalized = host.lowercase()
        return when {
            normalized.contains("-$carrier-") -> 3
            normalized.contains("-$carrier.") -> 3
            normalized.contains("$carrier-") -> 2
            normalized.contains(carrier) -> 1
            else -> 0
        }
    }

    private fun Source.toSelectorSource(): CdnSmartSelectionPolicy.Source = when (this) {
        Source.Custom -> CdnSmartSelectionPolicy.Source.Preferred
        Source.Region -> CdnSmartSelectionPolicy.Source.Region
        Source.Original -> CdnSmartSelectionPolicy.Source.Original
    }

    private fun CdnSmartSelectionPolicy.Source.fromSelectorSource(): Source = when (this) {
        CdnSmartSelectionPolicy.Source.Preferred -> Source.Custom
        CdnSmartSelectionPolicy.Source.Region -> Source.Region
        CdnSmartSelectionPolicy.Source.Original -> Source.Original
    }

    private val MAINLAND_COUNTRY_NAMES = setOf("中国", "中国大陆", "中华人民共和国")

    private val PROVINCE_REGION_ALIASES = mapOf(
        "上海" to "上海",
        "北京" to "北京",
        "天津" to "天津",
        "重庆" to "成都",
        "广东" to "广州",
        "四川" to "成都",
        "陕西" to "西安",
        "湖北" to "武汉",
        "江苏" to "南京",
        "黑龙江" to "哈市",
        "内蒙古" to "呼市",
        "香港" to "香港",
        "澳门" to "澳门"
    )

    private val CITY_REGION_ALIASES = mapOf(
        "重庆" to "成都",
        "广州" to "广州",
        "深圳" to "深圳",
        "成都" to "成都",
        "西安" to "西安",
        "武汉" to "武汉",
        "南京" to "南京",
        "哈尔滨" to "哈市",
        "呼和浩特" to "呼市",
        "香港" to "香港",
        "澳门" to "澳门"
    )

    /**
     * 最小 JSON 解析器：只认"对象 → 字符串数组"的目录格式。
     *
     * 支持 `"`/`\\`/`/`/`b f n r t`/`\uXXXX` 转义与任意空白；任何结构/类型不符
     * 直接抛 [IllegalArgumentException]（外层 [parseCatalog] 统一转成空表，
     * 与上游 kotlinx.serialization 抛错后 catch 的口径一致）。
     */
    private class CatalogJsonReader(private val text: String) {
        private var pos = 0

        fun readRoot(): Map<String, List<String>> {
            skipWhitespace()
            val result = readObject()
            skipWhitespace()
            require(pos == text.length) { "trailing content at $pos" }
            return result
        }

        private fun readObject(): Map<String, List<String>> {
            expect('{')
            val map = LinkedHashMap<String, List<String>>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = readStringArray()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("unexpected '${peek()}' at $pos")
                }
            }
        }

        private fun readStringArray(): List<String> {
            expect('[')
            val list = ArrayList<String>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                skipWhitespace()
                list += readString()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("unexpected '${peek()}' at $pos")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                require(pos < text.length) { "unterminated string" }
                val ch = text[pos++]
                when (ch) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        require(pos < text.length) { "unterminated escape" }
                        when (val escaped = text[pos++]) {
                            '"', '\\', '/' -> builder.append(escaped)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(pos + 4 <= text.length) { "bad unicode escape" }
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad unicode escape at $pos")
                                builder.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$escaped' at $pos")
                        }
                    }
                    else -> builder.append(ch)
                }
            }
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            if (pos >= text.length) throw IllegalArgumentException("unexpected EOF")
            return text[pos]
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            val actual = peek()
            if (actual != expected) throw IllegalArgumentException("expected '$expected' got '$actual' at $pos")
            pos++
        }
    }
}
