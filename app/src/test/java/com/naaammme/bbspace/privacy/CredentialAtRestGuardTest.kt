package com.naaammme.bbspace.privacy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 凭据静态加密守卫（S-1 第二步，2026-10-05）。
 *
 * ## 为什么需要它
 *
 * 审查报告 S-1 的第二步是"把凭证加密落盘"。这件事**极容易在后续改动里被无声回退**：
 * 只要有人在 `AuthStore` 里对敏感键写回一句 `putString("cookies_json", …)`，
 * 功能完全正常、测试全绿，而 `shared_prefs/auth.xml` 又变回人眼可读的登录态。
 *
 * 所以这里钉三件事：
 * 1. `AuthStore` 里**敏感键的写入必须走 `putSecret(`**（读走 `secret(`）；
 * 2. 迁移入口存在且覆盖到 `account_*` / `hd_key_*`（它们同样是凭据）；
 * 3. `AuthCipher` 的三条关键语义（AndroidKeyStore + AES/GCM、非密文当旧明文、
 *    加密失败返回 null 而不是回落明文）没有被改写。
 *
 * ## 防假绿
 * 先确认两个文件都读到了、且 `putSecret`/`secret(` 真的出现在 `AuthStore` 里
 * （否则"不许出现 putString(...)"这类断言对空文件是永久成立的）。
 */
class CredentialAtRestGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun file(relative: String): File {
        val f = File(repoRoot, relative.replace('/', File.separatorChar))
        assertTrue("找不到 $relative（repoRoot=${repoRoot.absolutePath}）", f.isFile)
        return f
    }

    /** 去注释行：本文件的注释里会出现 `putString("access_token"` 这样的反例说明。 */
    private fun codeLines(relative: String): List<String> = file(relative).readLines().map { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) "" else line
    }

    private val authStore = "core/auth/src/main/java/com/naaammme/bbspace/core/auth/AuthStore.kt"
    private val authCipher = "infra/crypto/src/main/java/com/naaammme/bbspace/infra/crypto/AuthCipher.kt"

    private val sensitiveKeys = listOf("access_token", "refresh_token", "cookies_json")

    @Test
    fun sensitiveKeysAreWrittenThroughPutSecretOnly() {
        val lines = codeLines(authStore)
        // 自检：收口函数确实存在，否则下面两条"不许出现"是假绿
        assertTrue(
            "AuthStore 里必须存在 putSecret( 收口（写）与 secret( 收口（读）",
            lines.any { it.contains("private fun SharedPreferences.Editor.putSecret(") } &&
                lines.any { it.contains("private fun secret(") }
        )
        sensitiveKeys.forEach { key ->
            val plainWrites = lines.filter {
                it.contains("""putString("$key"""") || it.contains("""putString("$key", """)
            }
            assertTrue(
                "AuthStore 里不许再出现对敏感键的 putString(\"$key\", …)（S-1 第二步的加密收口会被绕过）：\n" +
                    plainWrites.joinToString("\n"),
                plainWrites.isEmpty()
            )
        }
        // Cookie 的 JSON 串也必须走加密（它含 SESSDATA / bili_jct）
        assertTrue(
            "cookies_json 的写入必须走 putSecret(\"cookies_json\", …)",
            lines.any { it.contains("""putSecret("cookies_json"""") }
        )
    }

    @Test
    fun migrationCoversAllCredentialKeys() {
        val lines = codeLines(authStore)
        val migration = lines.indexOfFirst { it.contains("fun migratePlaintextSecrets(") }
        assertTrue("AuthStore 必须提供 migratePlaintextSecrets() 迁移入口", migration >= 0)
        val body = lines.drop(migration).take(40).joinToString("\n")
        assertTrue(
            "迁移动必须覆盖三个固定敏感键：$sensitiveKeys",
            sensitiveKeys.all { body.contains("\"$it\"") }
        )
        assertTrue(
            "迁移动必须覆盖 account_*（多账号凭据 JSON）与 hd_key_*（HD 会话密钥）",
            body.contains("\"account_\"") && body.contains("KEY_HD_KEY_PREFIX")
        )
        assertTrue(
            "迁移必须跳过已加密值（幂等）",
            body.contains("AuthCipher.isEncrypted(")
        )
    }

    @Test
    fun authCipherKeepsItsSecuritySemantics() {
        val code = codeLines(authCipher).joinToString("\n")
        assertTrue(
            "AuthCipher 必须用 AndroidKeyStore 里的 AES 密钥",
            code.contains(""""AndroidKeyStore"""") && code.contains("""KeyGenerator.getInstance("AES"""")
        )
        assertTrue(
            "AuthCipher 必须用 AES/GCM/NoPadding",
            code.contains(""""AES/GCM/NoPadding"""") && code.contains("GCMParameterSpec")
        )
        assertTrue(
            "密文前缀必须存在（isEncrypted/decrypt 靠它区分新旧数据）",
            code.contains("""PREFIX = "enc:v1:"""")
        )
        assertTrue(
            "decrypt 必须把非密文当旧明文原样返回（升级兼容，否则老用户会被登出）",
            code.contains("if (!isEncrypted(stored)) return stored")
        )
        assertTrue(
            "加密失败必须返回 null（调用方不落盘），**不许**回落写明文",
            code.contains("fun encrypt(plain: String): String?") && code.contains("return null")
        )
    }
}
