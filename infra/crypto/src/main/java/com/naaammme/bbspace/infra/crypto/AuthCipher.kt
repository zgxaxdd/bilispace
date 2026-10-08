package com.naaammme.bbspace.infra.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.naaammme.bbspace.core.common.log.Logger
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地登录会话的静态加密（S-1 第二步，2026-10-05）。
 *
 * ## 为什么需要它
 *
 * 审查报告 S-1 的第一层问题（凭据进云备份 / 换机迁移）已在第一步用备份规则排除修掉；
 * 第二层是**明文落盘**本身：`shared_prefs/auth.xml` 里 `access_token` / `refresh_token` /
 * `cookies_json`（含 SESSDATA、bili_jct）全是人眼可读的文本 —— 真机 `run-as` 实读 3966 字节。
 * 任何能读应用私有目录的通道（root、备份/迁移工具、调试提取）都等于直接拿到登录态。
 *
 * ## 实现来源（照搬已被真机验证的上游做法）
 *
 * `_ref/BiliPai`（jay3-yy/BiliPai，v0.2.9）`core-data/.../SessionStorageCipher.kt`：
 * AndroidKeyStore 里生成一把 AES 密钥 + `AES/GCM/NoPadding`，密文格式
 * `v1:<base64url(iv)>:<base64url(密文)>`。本文件的差异只有前缀（`enc:v1:`）与日志接入
 * 本仓 `Logger`。
 *
 * ## 三条关键语义（都不是可选的）
 *
 * 1. **非密文即旧明文**：[decrypt] 遇到不带前缀的值**原样返回** —— 这正是老用户升级时的
 *    兼容读法，配合 [AuthStore][com.naaammme.bbspace.core.auth.AuthStore] 的一次性迁移，
 *    升级瞬间不会把任何人变成"未登录"。
 * 2. **加密失败返回 null，绝不回落写明文**：Keystore 在个别 ROM 上可能短暂不可用；
 *    此时调用方应**保持内存态、不覆盖原有磁盘值**（照 BiliPai 的取舍）—— 宁可本次不落盘，
 *    也不把凭证重新写成明文。
 * 3. **解密失败返回空串**：密钥被系统回收（清数据/卸载重装/换机恢复出的密文）时，
 *    表现为"未登录"而不是崩溃或拿密文当明文去发请求。
 */
object AuthCipher {

    private const val TAG = "AuthCipher"
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "bbspace.auth.v1"

    /** 密文前缀。**改动它必须同步迁移逻辑**（旧前缀会被当成"旧明文"原样返回）。 */
    private const val PREFIX = "enc:v1:"

    @Volatile
    private var cachedKey: SecretKey? = null

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    /**
     * 加密。
     * @return 密文；[plain] 为空时原样返回空串；Keystore 不可用时返回 `null`（调用方不要落盘）。
     */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                // GCM 不允许复用 IV：每次加密都由系统生成新的 IV（随密文一起存）
                init(Cipher.ENCRYPT_MODE, key())
            }
            PREFIX + b64(cipher.iv) + ":" + b64(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            Logger.w(TAG) { "凭据加密失败（Keystore 不可用？）：${e.javaClass.simpleName} ${e.message}" }
            // 绝不回落写明文：返回 null，让调用方保持内存态、不覆盖磁盘上的旧值
            return null
        }
    }

    /** 解密。非密文（旧明文）原样返回；解不开返回空串（表现为未登录）。 */
    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(":", limit = 2)
            require(parts.size == 2) { "密文格式不完整" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[0])))
            }
            String(cipher.doFinal(unb64(parts[1])), StandardCharsets.UTF_8)
        }.getOrElse { e ->
            // 密钥丢失（清数据后恢复出的旧密文等）→ 当作未登录，而不是崩或拿密文当明文用
            Logger.w(TAG) { "凭据解密失败，按未登录处理：${e.javaClass.simpleName}" }
            ""
        }
    }

    /**
     * 迁移用：已加密或空值原样返回；旧明文返回密文；Keystore 不可用返回 `null`。
     */
    fun encryptIfNeeded(stored: String): String? = when {
        stored.isEmpty() || isEncrypted(stored) -> stored
        else -> encrypt(stored)
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return@synchronized it }
            var store = KeyStore.getInstance(STORE).apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyGenerator.getInstance("AES", STORE).apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }.generateKey()
                // 生成后重新 load，才能观察到新别名（照 BiliPai 同款）
                store = KeyStore.getInstance(STORE).apply { load(null) }
            }
            (store.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
                .also { cachedKey = it }
        }
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun unb64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)
}
