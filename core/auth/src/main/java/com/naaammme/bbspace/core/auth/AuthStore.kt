package com.naaammme.bbspace.core.auth

import android.content.Context
import androidx.core.content.edit
import com.naaammme.bbspace.core.common.account.PerAccountDataCleaner
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.model.Cookie
import com.naaammme.bbspace.core.model.LoginCredential
import com.naaammme.bbspace.core.model.User
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号凭证存储
 * 管理登录凭证、多账号
 */
@Singleton
class AuthStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val perAccountCleaners: Set<@JvmSuppressWildcards PerAccountDataCleaner>
) {
    companion object {
        private const val TAG = "AuthStore"
        private const val KEY_HD_KEY_PREFIX = "hd_key_"
        private const val KEY_HD_EXP_PREFIX = "hd_exp_"
    }

    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    // 当前凭证

    var guestMode: Boolean
        get() = prefs.getBoolean("guest_mode", false)
        set(value) = prefs.edit { putBoolean("guest_mode", value) }

    val mid: Long get() = if (guestMode) 0L else prefs.getLong("mid", 0)
    val accessToken: String get() = if (guestMode) "" else prefs.getString("access_token", "") ?: ""
    val refreshToken: String get() = if (guestMode) "" else prefs.getString("refresh_token", "") ?: ""

    /**
     * 当前账号的 Cookie 请求头（SESSDATA 等），供 Web 端交互接口使用
     *
     * 凭证唯一来源是短信登录（loginBySms 解析 cookie_info），扫码只绑定 HD key
     */
    fun cookieHeader(): String {
        // buvid3 对齐真实浏览器（每个 Web 请求都带）：缺失时手机网络 IP 上的
        // Web 请求更容易被风控拒绝（-352 一类），表现为排行等 Web 页"暂无内容"。
        // 未登录/游客同样携带——游客最缺这层风险缓冲。
        val buvid3 = "buvid3=$webBuvid3"
        val loginCookies = if (guestMode) "" else cookiesFromJson(prefs.getString("cookies_json", null))
            .joinToString("; ") { "${it.name}=${it.value}" }
        return if (loginCookies.isEmpty()) buvid3 else "$buvid3; $loginCookies"
    }

    /**
     * Web 端 buvid3 cookie 值：首次访问生成并持久化，格式对齐浏览器
     * （UUID + "infoc" 后缀）。与 app 协议的 DeviceIdentity.buvid（XU/XY 前缀）
     * 是两套标识，互不通用。
     */
    private val webBuvid3: String
        get() {
            prefs.getString("web_buvid3", null)?.let { return it }
            val generated = UUID.randomUUID().toString().uppercase(Locale.US) + "infoc"
            prefs.edit { putString("web_buvid3", generated) }
            return generated
        }

    /**
     * Web 端 csrf 令牌（bili_jct cookie 值），未登录或缺失时为空串
     */
    fun csrfToken(): String {
        if (guestMode) return ""
        return cookiesFromJson(prefs.getString("cookies_json", null))
            .firstOrNull { it.name == "bili_jct" }?.value.orEmpty()
    }

    /**
     * 是否具备 Web 端交互所需凭证（cookie + csrf 齐备）
     */
    /**
     * 是否具备 Web 端交互所需凭证（登录 cookie + csrf 齐备）。
     * 不再用 `cookieHeader().isNotEmpty()` 判断——buvid3 常驻后它恒为真；
     * 真正的凭证是登录 cookie（SESSDATA）与 csrf（bili_jct）。
     */
    fun hasWebCredential(): Boolean =
        !guestMode && csrfToken().isNotEmpty() && cookiesFromJson(prefs.getString("cookies_json", null)).isNotEmpty()

    fun saveHdAccessKey(mid: Long, key: String, expiresIn: Long) {
        if (mid == 0L || key.isEmpty()) return
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000
        prefs.edit {
            putString(hdKeyKey(mid), key)
            putLong(hdExpKey(mid), expiresAt)
        }
    }

    fun getHdAccessKeyForCurrent(): String {
        if (guestMode) return ""
        val currentMid = mid
        if (currentMid == 0L) return ""
        val key = prefs.getString(hdKeyKey(currentMid), "") ?: ""
        if (key.isEmpty()) return ""
        val expiresAt = prefs.getLong(hdExpKey(currentMid), 0)
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
            clearHdAccessKeyFor(currentMid)
            return ""
        }
        return key
    }

    fun hasHdAccessKeyForCurrent(): Boolean = getHdAccessKeyForCurrent().isNotEmpty()

    private fun clearHdAccessKeyFor(mid: Long) {
        prefs.edit {
            remove(hdKeyKey(mid))
            remove(hdExpKey(mid))
        }
    }

    private fun hdKeyKey(mid: Long): String = "$KEY_HD_KEY_PREFIX$mid"

    private fun hdExpKey(mid: Long): String = "$KEY_HD_EXP_PREFIX$mid"

    fun saveCredential(credential: LoginCredential) {
        prefs.edit {
            putLong("mid", credential.mid)
            putString("access_token", credential.accessToken)
            putString("refresh_token", credential.refreshToken)
            putLong("expires_in", credential.expiresIn)
            putLong("login_time", System.currentTimeMillis())
            putString("cookies_json", cookiesToJson(credential.cookies))
        }
        saveToAccountList(credential)
    }

    fun getSavedCredential(): LoginCredential? {
        if (guestMode) return null
        val mid = prefs.getLong("mid", 0)
        if (mid == 0L) return null
        return LoginCredential(
            mid = mid,
            accessToken = prefs.getString("access_token", "") ?: "",
            refreshToken = prefs.getString("refresh_token", "") ?: "",
            expiresIn = prefs.getLong("expires_in", 0),
            cookies = cookiesFromJson(prefs.getString("cookies_json", null))
        )
    }

    fun clearCredential() {
        val currentMid = prefs.getLong("mid", 0)
        val keys = listOf("mid", "access_token", "refresh_token", "expires_in", "login_time", "cookies_json")
        prefs.edit {
            keys.forEach(::remove)
        }
        if (currentMid != 0L) {
            clearHdAccessKeyFor(currentMid)
        }
    }

    // 多账号

    private fun saveToAccountList(credential: LoginCredential) {
        prefs.edit {
            putString("account_${credential.mid}", credentialToJson(credential))
        }
    }

    fun getAllAccounts(): List<LoginCredential> {
        return prefs.all.entries
            .filter { it.key.startsWith("account_") }
            .mapNotNull { credentialFromJson(it.value as? String) }
    }

    fun switchAccount(mid: Long): LoginCredential? {
        val json = prefs.getString("account_$mid", null) ?: return null
        val credential = credentialFromJson(json) ?: return null
        saveCredential(credential)
        return credential
    }

    fun removeAccount(mid: Long) {
        prefs.edit {
            remove("account_$mid")
            remove(userInfoKey(mid))
        }
        clearHdAccessKeyFor(mid)
        // 其它模块按 mid 存的本地数据（ST-12：IM 的设备 id 缓存就是这么永久留下的）。
        // 单个清理器失败不许阻止账号被移除 —— 用户看不到"删除失败"比看到一个脏缓存更重要，
        // 所以逐个吞掉并记日志；契约见 PerAccountDataCleaner。
        perAccountCleaners.forEach { cleaner ->
            val name = cleaner.javaClass.simpleName
            try {
                cleaner.clean(mid)
            } catch (e: Exception) {
                Logger.w(TAG) { "per-account cleanup failed mid=$mid cleaner=$name msg=${e.message}" }
            }
        }
        if (this.mid == mid) {
            clearCredential()
        }
    }

    fun exportAllAccounts(): String {
        val arr = JSONArray()
        getAllAccounts().forEach { arr.put(JSONObject(credentialToJson(it))) }
        return arr.toString(2)
    }

    fun importAccounts(json: String): List<LoginCredential> {
        val arr = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        val result = mutableListOf<LoginCredential>()
        for (i in 0 until arr.length()) {
            credentialFromJson(arr.optJSONObject(i)?.toString())?.let {
                saveToAccountList(it)
                result.add(it)
            }
        }
        return result
    }

    private fun credentialToJson(credential: LoginCredential): String {
        return JSONObject().apply {
            put("mid", credential.mid)
            put("access_token", credential.accessToken)
            put("refresh_token", credential.refreshToken)
            put("expires_in", credential.expiresIn)
            put("cookies", JSONArray(cookiesToJson(credential.cookies)))
            put("export_time", System.currentTimeMillis())
        }.toString()
    }

    private fun credentialFromJson(json: String?): LoginCredential? {
        if (json.isNullOrEmpty()) return null
        return try {
            val obj = JSONObject(json)
            LoginCredential(
                mid = obj.getLong("mid"),
                accessToken = obj.getString("access_token"),
                refreshToken = obj.getString("refresh_token"),
                expiresIn = obj.optLong("expires_in", 0),
                cookies = cookiesFromJson(obj.optJSONArray("cookies")?.toString())
            )
        } catch (e: JSONException) {
            Logger.w(TAG) { "credential json parse failed: ${e.message}" }
            null
        }
    }

    private fun cookiesToJson(cookies: List<Cookie>): String {
        val arr = JSONArray()
        cookies.forEach { cookie ->
            arr.put(JSONObject().apply {
                put("name", cookie.name)
                put("value", cookie.value)
                put("http_only", cookie.httpOnly)
                put("expires", cookie.expires)
                put("secure", cookie.secure)
            })
        }
        return arr.toString()
    }

    private fun cookiesFromJson(json: String?): List<Cookie> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Cookie(
                    name = obj.getString("name"),
                    value = obj.getString("value"),
                    httpOnly = obj.optBoolean("http_only", false),
                    expires = obj.optLong("expires", 0),
                    secure = obj.optBoolean("secure", false)
                )
            }
        } catch (e: JSONException) {
            Logger.w(TAG) { "cookies json parse failed: ${e.message}" }
            emptyList()
        }
    }

    // 用户信息
    fun saveUserInfo(user: User) {
        prefs.edit {
            putString(userInfoKey(user.mid), userToJson(user))
        }
    }

    fun getAllUserInfos(): Map<Long, User> {
        return prefs.all.entries
            .filter { it.key.startsWith("uinfo_") }
            .mapNotNull { entry ->
                val mid = entry.key.removePrefix("uinfo_").toLongOrNull() ?: return@mapNotNull null
                val user = userFromJson(entry.value as? String) ?: return@mapNotNull null
                mid to user
            }
            .toMap()
    }

    fun getUserInfo(): User? {
        val currentMid = mid
        if (currentMid == 0L) return null
        return userFromJson(prefs.getString(userInfoKey(currentMid), null))
    }

    fun clearUserInfo(mid: Long) {
        if (mid == 0L) return
        prefs.edit { remove(userInfoKey(mid)) }
    }

    private fun userInfoKey(mid: Long): String = "uinfo_$mid"

    private fun userToJson(user: User): String = JSONObject().apply {
        put("mid", user.mid)
        put("name", user.name)
        put("avatar", user.avatar)
        put("sign", user.sign)
        put("level", user.level)
        put("coins", user.coins)
        put("sex", user.sex)
        put("birthday", user.birthday)
        put("vip_type", user.vipType)
        put("vip_status", user.vipStatus)
        put("email_verified", user.emailVerified)
        put("phone_verified", user.phoneVerified)
        put("official_role", user.officialRole)
        put("silence", user.silence)
        put("dynamic", user.dynamic)
        put("following", user.following)
        put("follower", user.follower)
    }.toString()

    private fun userFromJson(json: String?): User? {
        if (json.isNullOrEmpty()) return null
        return try {
            val o = JSONObject(json)
            User(
                mid = o.getLong("mid"),
                name = o.optString("name"),
                avatar = o.optString("avatar"),
                sign = o.optString("sign"),
                level = o.optInt("level"),
                coins = o.optDouble("coins"),
                sex = o.optInt("sex"),
                birthday = o.optString("birthday"),
                vipType = o.optInt("vip_type"),
                vipStatus = o.optInt("vip_status"),
                emailVerified = o.optBoolean("email_verified"),
                phoneVerified = o.optBoolean("phone_verified"),
                officialRole = o.optInt("official_role"),
                silence = o.optBoolean("silence"),
                dynamic = o.optInt("dynamic"),
                following = o.optInt("following"),
                follower = o.optInt("follower")
            )
        } catch (e: JSONException) {
            Logger.w(TAG) { "user json parse failed: ${e.message}" }
            null
        }
    }
}
