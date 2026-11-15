package com.naaammme.bbspace.feature.settings.audioVideo

import com.naaammme.bbspace.core.model.player.CdnRegionPolicy

/**
 * 「CDN 智能选线（实验）」两栏文本输入的**解析与格式化**（纯函数，可 JVM 单测）。
 *
 * ## 为什么单独一层
 * 设置页里两栏都是**人写的多行文本**：
 * - 目标 host：一行一个，也接受逗号分隔；
 * - 自定义规则：每行 `pattern => replacement`。
 *
 * 解析规则必须与"写进 DataStore 的格式"分开：DataStore 那侧是
 * `CdnRegionConfigCodec`（v1 制表符格式，手工改配置的人不该看到），这里是**用户输入**时
 * 的宽松格式。两者之间用 [CdnRegionPolicy.CustomRule] 交接。
 *
 * ## 非法输入的口径
 * 规则行**逐行判定**：格式不对/正则非法的行**忽略并计数**（不整份丢弃，也不假装成功），
 * 设置页据此提示"已忽略 N 行"。这条口径与上游 `CdnRegionPolicy.validateCustomRules`
 * 的"单条规则定性"一致；这里额外把"正则编译失败"也算非法，避免用户看到"保存成功"
 * 但规则永远不会命中。
 */
internal object CdnRuleTextFormat {

    private val HOST_SPLITTER = Regex("""[,\s，、;；\r\n]+""")

    /** 规则行分隔符（上游设置页同款写法）。 */
    private const val RULE_ARROW = "=>"

    /** 规则解析结果。 */
    data class RulesParseResult(
        val rules: List<CdnRegionPolicy.CustomRule> = emptyList(),
        /** 被忽略的行号（1 基）——UI 用它提示"第几行有问题"。 */
        val invalidLineNumbers: List<Int> = emptyList(),
        val invalidLineCount: Int = invalidLineNumbers.size,
        /** 非空行总数：用于"0 条规则 = 清空"与"没写东西"区分。 */
        val nonEmptyLineCount: Int = 0
    )

    /** host 列表 → 文本（一行一个）。 */
    fun formatHosts(hosts: List<String>): String = hosts.joinToString("\n")

    /**
     * 文本 → host 列表：换行/英文逗号/中文逗号/顿号/分号/空白都算分隔符，
     * 逐项 trim、去空、去重（保持首次出现顺序）。
     *
     * 顺带容忍误粘贴的 URL：含 `://` 时取 URI 的 host；不含 scheme 时截掉 `/path`、
     * `?query` 与 `:port`。**不做域名合法性拒绝**（内网/自定义域名也应能填），
     * 只保证落库的是纯 host 形式。
     */
    fun parseHosts(raw: String): List<String> = raw
        .split(HOST_SPLITTER)
        .mapNotNull(::normalizeHost)
        .distinct()

    /** 规则列表 → 文本（一行一条，便于用户直接改）。 */
    fun formatRules(rules: List<CdnRegionPolicy.CustomRule>): String =
        rules.joinToString("\n") { rule -> "${rule.pattern} $RULE_ARROW ${rule.replacement}" }

    /**
     * 文本 → 规则列表。
     *
     * 每行格式：`pattern => replacement`（箭头两侧空格可省略）。空行忽略且不计入非法。
     * 非法 = 缺箭头 / 任一侧为空 / 正则是非法表达式。
     */
    fun parseRules(raw: String): RulesParseResult {
        var lineNo = 0
        var nonEmpty = 0
        val rules = mutableListOf<CdnRegionPolicy.CustomRule>()
        val invalid = mutableListOf<Int>()
        raw.lineSequence().forEach { line ->
            lineNo += 1
            if (line.isBlank()) return@forEach
            nonEmpty += 1
            val arrowAt = line.indexOf(RULE_ARROW)
            if (arrowAt < 0) {
                invalid += lineNo
                return@forEach
            }
            val pattern = line.substring(0, arrowAt).trim()
            val replacement = line.substring(arrowAt + RULE_ARROW.length).trim()
            if (pattern.isEmpty() || replacement.isEmpty()) {
                invalid += lineNo
                return@forEach
            }
            // 正则非法的规则永远不会命中：保存进设置只会让用户误以为已经生效。
            if (runCatching { Regex(pattern) }.isFailure) {
                invalid += lineNo
                return@forEach
            }
            rules += CdnRegionPolicy.CustomRule(
                pattern = pattern,
                replacement = replacement,
                enabled = true
            )
        }
        return RulesParseResult(
            rules = rules,
            invalidLineNumbers = invalid,
            nonEmptyLineCount = nonEmpty
        )
    }

    private fun normalizeHost(token: String): String? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        val host = if (trimmed.contains("://")) {
            runCatching { java.net.URI(trimmed).host }.getOrNull()
        } else {
            trimmed.substringBefore('/').substringBefore('?').substringBefore(':')
        }
        return host?.trim()?.takeIf(String::isNotEmpty)
    }
}
