package com.naaammme.bbspace.core.settings

import com.naaammme.bbspace.core.model.player.CdnRegionPolicy

/**
 * 属地优选 / 自定义改写规则的**持久化编码**（W4 收口，2026-10-17 夜）。
 *
 * ## 为什么是"自己编解码"而不是 JSON
 * 1. `core:settings` 只依赖 DataStore，**不带 kotlinx.serialization**（加依赖会牵动整个 settings 模块
 *    与离线构建）；而 `CdnRegionPolicy` 里已经手写了一个最小 JSON 解析器给**目录文件**用
 *    —— 那是只读、结构固定的资源，**不适合**拿来存用户可编辑的规则（写出去再读回来必须逐字节可逆）。
 * 2. 规则只有三个字段、行数是个位数，用**行/制表符**分隔足够，且**人可以直接看**：
 *    设置导出的文本贴到 bug 报告里，不用解码。
 *
 * ## 编码格式（写死在 [FORMAT_VERSION] 里，改格式必须换版本号）
 * ```
 * v1
 * <enabled 1|0>\t<pattern>\t<replacement>
 * <enabled 1|0>\t<pattern>\t<replacement>
 * ```
 * 首行是版本标记；后续每行一条规则。**读的时候**：非首行版本不对 ⇒ 整体按空处理（宁可丢规则，
 * 不可误用旧格式）；单行字段数不对/pattern 为空 ⇒ **只跳过这一行**（其余规则继续生效，
 * 不让一条脏数据废掉整份配置）。host 列表同理，一行一个 host。
 */
object CdnRegionConfigCodec {

    const val FORMAT_VERSION = "v1"
    private const val RULE_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = "\t"
    private const val VERSION_LINE = FORMAT_VERSION

    /** host 列表 → 存储文本（一行一个，保持顺序；空值/重复丢弃）。 */
    fun encodeHosts(hosts: List<String>): String =
        hosts.map(String::trim).filter(String::isNotEmpty).distinct().joinToString(RULE_SEPARATOR)

    /** 存储文本 → host 列表（容许 null/空串；顺序保持）。 */
    fun decodeHosts(raw: String?): List<String> =
        raw.orEmpty()
            .split(RULE_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    /** 规则列表 → 存储文本（含版本头；无规则时返回空串，避免写出一个只有版本头的空配置）。 */
    fun encodeRules(rules: List<CdnRegionPolicy.CustomRule>): String {
        val valid = rules.filter { it.pattern.isNotBlank() }
        if (valid.isEmpty()) return ""
        return buildString {
            append(VERSION_LINE)
            valid.forEach { rule ->
                append(RULE_SEPARATOR)
                append(if (rule.enabled) "1" else "0")
                append(FIELD_SEPARATOR)
                append(sanitize(rule.pattern))
                append(FIELD_SEPARATOR)
                append(sanitize(rule.replacement))
            }
        }
    }

    /** 存储文本 → 规则列表（版本不符 ⇒ 空；单行脏数据只跳过该行）。 */
    fun decodeRules(raw: String?): List<CdnRegionPolicy.CustomRule> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split(RULE_SEPARATOR)
        if (lines.isEmpty() || lines.first().trim() != VERSION_LINE) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size != 3) return@mapNotNull null
            val pattern = parts[1].trim()
            if (pattern.isEmpty()) return@mapNotNull null
            CdnRegionPolicy.CustomRule(
                pattern = pattern,
                replacement = parts[2].trim(),
                enabled = parts[0].trim() != "0"
            )
        }
    }

    /**
     * 字段里不许出现分隔符：制表符/换行都是**格式元字符**，留着会把一条规则读成两条。
     * 制表符直接删除（pattern 里没有任何正当用途）；换行同样删除。
     */
    private fun sanitize(value: String): String =
        value.replace(FIELD_SEPARATOR, "").replace(RULE_SEPARATOR, "").trim()
}
