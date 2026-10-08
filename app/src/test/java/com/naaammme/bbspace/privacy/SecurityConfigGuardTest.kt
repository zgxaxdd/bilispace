package com.naaammme.bbspace.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 安全与隐私默认值的结构守卫（S-1 / S-2，2026-10-04）。
 *
 * ## 为什么需要它
 * 这三条都是"一次写死、永久生效"的配置型修复，而**配置最容易在后续改动里被悄悄回退**：
 * - 备份规则被改回模板 → 登录态又进云备份/换机迁移；
 * - manifest 又出现 `usesCleartextTraffic="true"` → 全应用明文放行回来；
 * - HTTPDNS 服务器 IP 增删后忘了同步 `network_security_config.xml` → 要么明文白名单失效
 *   （HTTPDNS 静默失败），要么有人"顺手"把 base-config 改回放行。
 *
 * 所以这里钉三件事：① 凭证类 sharedpref 被排除（云备份 + 换机两侧都要）；
 * ② 不存在全局明文放行；③ 源码里出现过的每个 HTTPDNS 明文端点 IP 都在白名单里。
 *
 * ## 防假绿
 * 每个断言先确认目标文件真的读到了并含预期锚点（例如 `full-backup-content`），
 * 否则"必须不存在 X"那类断言对空字符串是永久成立的。
 */
class SecurityConfigGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun file(relative: String): File {
        val f = File(repoRoot, relative.replace('/', File.separatorChar))
        assertTrue("找不到 $relative（repoRoot=${repoRoot.absolutePath}）", f.isFile)
        return f
    }

    /** 去掉注释与多余空白：只留真正生效的 XML 元素。 */
    private fun effectiveXml(relative: String): String =
        file(relative).readText()
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""\s+"""), " ")

    private val sensitivePrefs = listOf("auth.xml", "device_identity.xml", "ticket_prefs.xml", "guest_info.xml")

    @Test
    fun backupRulesExcludeCredentialPrefs() {
        val cloud = effectiveXml("app/src/main/res/xml/backup_rules.xml")
        assertTrue(
            "backup_rules.xml 必须还是 full-backup-content（读不到说明文件被换掉了）",
            cloud.contains("<full-backup-content>")
        )
        sensitivePrefs.forEach { name ->
            assertTrue(
                "backup_rules.xml 必须显式排除 sharedpref/$name —— 否则登录态会进云备份，" +
                    "并能在另一台设备上恢复出登录态（S-1）",
                cloud.contains("""<exclude domain="sharedpref" path="$name" />""") ||
                    cloud.contains("""<exclude domain="sharedpref" path="$name"/>""")
            )
        }
    }

    @Test
    fun dataExtractionRulesExcludeCredentialPrefsInBothDomains() {
        val rules = effectiveXml("app/src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml 必须含 <cloud-backup>", rules.contains("<cloud-backup>"))
        assertTrue(
            "device-transfer 段必须**存在**（修复前整段被注释掉）：换机迁移同样会带走凭证",
            rules.contains("<device-transfer>")
        )
        val cloudBackup = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val deviceTransfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        sensitivePrefs.forEach { name ->
            assertTrue(
                "cloud-backup 必须排除 sharedpref/$name",
                cloudBackup.contains("""path="$name"""")
            )
            assertTrue(
                "device-transfer 必须排除 sharedpref/$name（最易漏的一侧）",
                deviceTransfer.contains("""path="$name"""")
            )
        }
    }

    /**
     * 明文策略**必须有明确口径**（2026-10-06 修订）。
     *
     * ## 为什么把"禁止全局明文"改掉（不是放宽，是换守的东西）
     *
     * 原断言要求 `base-config cleartextTrafficPermitted="false"`。真机定位证明它守不住功能：
     * PCDN 把 CDN 主机名导到 `*.yirujs.com`（**动态第三方域**）并直连**裸 IP**，
     * 两者都无法用 `<domain>` 枚举 ⇒ 白名单永远漏 ⇒「单文件 dash 索引缺失」⇒ 部分视频打不开。
     * 源仓库 BiliPai 采用 `base-config cleartextTrafficPermitted="true"` + `usesCleartextTraffic="true"`，
     * 本仓按同一口径改，**长期稳定优先**（用户决策）。
     *
     * ## 换成了什么更有意义的东西
     *
     * 明文收紧的责任回到**应用层**：接口基址必须仍是 https（见 [apiBaseUrlsMustStayHttps]），
     * manifest 里也不许再出现那个既冗余又误导的 `usesCleartextTraffic` 标志。
     */
    @Test
    fun cleartextPolicyIsExplicitAndConfigIsWired() {
        val manifest = file("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "manifest 不该再出现 usesCleartextTraffic —— 挂了 networkSecurityConfig 之后它被忽略，" +
                "留着只会让后人误以为策略由它决定",
            !manifest.contains("""usesCleartextTraffic="true"""")
        )
        assertTrue(
            "manifest 必须挂上 networkSecurityConfig，否则下面的策略不生效",
            manifest.contains("""android:networkSecurityConfig="@xml/network_security_config"""")
        )
        val config = effectiveXml("app/src/main/res/xml/network_security_config.xml")
        assertTrue(
            "base-config 必须**显式写明**明文口径（不许靠默认值，否则改平台行为时没人知道发生了什么）",
            config.contains("""<base-config cleartextTrafficPermitted="true">""")
        )
    }

    /**
     * **接口基址必须是 https**（S-2 的原始意图，2026-10-06 独立成一条）。
     *
     * 全局放行明文之后，"主站请求静默走 http"就成了唯一需要防的事 —— 而它的入口只有
     * [BiliConstants] 这几条基址常量。把它们钉成 https，比守平台策略更贴近意图，也更难绕过。
     */
    @Test
    fun apiBaseUrlsMustStayHttps() {
        val constants = file("core/common/src/main/java/com/naaammme/bbspace/core/common/BiliConstants.kt")
            .readText()
        val names = listOf("BASE_URL_APP", "BASE_URL_API", "BASE_URL_PASSPORT", "BASE_URL_WEB")
        names.forEach { name ->
            assertTrue(
                "$name 必须是 https —— 全局放行明文后，接口走 http 就再没有平台层拦截了",
                constants.contains("""const val $name = "https://""")
            )
        }
    }

    /**
     * 媒体 CDN 的明文放行**不许被收紧**（2026-10-05 真机回归事故的守卫）。
     *
     * 事故：把全局明文禁掉（S-2）之后，Media3 直接报
     * `CLEARTEXT communication to upos-sz-mirrorali.bilivideo.com not permitted by
     * network security policy` —— B 站接口下发的**播放地址本身就是明文 http**，
     * 于是"所有视频都放不了"（下载/直播同一批地址）。
     *
     * 这条守卫的价值在于：该回归**不会有任何测试变红**（JVM 测试跑不到真实网络策略），
     * 只能靠真机发现。把域名写死在这里，下一次"顺手收紧"至少会让 PR 门亮红灯。
     */
    @Test
    fun mediaCdnCleartextMustStayAllowed() {
        val config = effectiveXml("app/src/main/res/xml/network_security_config.xml")
        val mediaHosts = listOf(
            "bilivideo.com", "bilivideo.cn", "bilivideo.net", "akamaized.net", "acgvideo.com", "hdslb.com"
        )
        mediaHosts.forEach { host ->
            assertTrue(
                "媒体 CDN `$host` 必须保持 cleartextTrafficPermitted=\"true\"（含子域）—— " +
                    "B 站下发的播放地址可能是 http://，禁掉明文会导致所有视频无法播放。" +
                    "真要收紧，先让播放链路把地址升级成 https 再回来改这里。",
                config.contains("""<domain includeSubdomains="true">$host</domain>""")
            )
        }
        assertTrue(
            "媒体 CDN 的明文放行必须独立的 domain-config（不要混进 DNS 白名单，便于后人读懂为什么）",
            config.contains("""<domain includeSubdomains="true">bilivideo.com</domain>""")
        )
    }

    @Test
    fun everyHttpDnsCleartextEndpointIsWhitelisted() {
        val dnsSources = listOf(
            "infra/network-http/src/main/java/com/naaammme/bbspace/infra/network/dns/BiliHttpDns.kt",
            "infra/network-http/src/main/java/com/naaammme/bbspace/infra/network/dns/TencentHttpDns.kt",
            "infra/network-http/src/main/java/com/naaammme/bbspace/infra/network/dns/AlibabaHttpDns.kt"
        )
        val config = effectiveXml("app/src/main/res/xml/network_security_config.xml")
        val ipRegex = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
        val endpoints = dnsSources.flatMap { relative ->
            file(relative).readLines()
                // 只看真正会被请求的 URL / 服务器常量行，避开注释里的举例
                .filter { line ->
                    val trimmed = line.trimStart()
                    !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")
                }
                .flatMap { line -> ipRegex.findAll(line).map { it.value } }
        }.distinct().sorted()
        assertTrue(
            "没扫到任何 HTTPDNS 端点 IP（扫描口径失效），此时下面的断言无意义",
            endpoints.size >= 5
        )
        val missing = endpoints.filterNot { config.contains("""<domain includeSubdomains="false">$it</domain>""") }
        assertEquals(
            "这些 HTTPDNS 明文端点不在 network_security_config 白名单里 —— " +
                "明文已被全局禁止时它们会静默失败（HTTPDNS 拿不到结果）。" +
                "改服务器 IP 列表时必须同步 app/src/main/res/xml/network_security_config.xml",
            emptyList<String>(),
            missing
        )
    }
}
