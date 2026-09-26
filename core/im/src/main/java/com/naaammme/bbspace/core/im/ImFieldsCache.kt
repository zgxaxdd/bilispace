package com.naaammme.bbspace.core.im

import android.content.Context
import com.naaammme.bbspace.core.common.account.PerAccountDataCleaner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IM 的按账号本地缓存名，**唯一的定义处**。
 *
 * 每个 mid 一个独立的 SharedPreferences 文件（`IMFieldsCache$mid`），用来存
 * "发送用设备 id"与"拉取用伪造设备 id"（见 `ImRepository.getDeviceId` 的注释）。
 * 名字此前只写在 `ImRepository` 那一行里，而清理侧必须用同一个字符串 ——
 * 两边各写一份的话，改一次命名清理就静默失效（这正是 ST-12 的病灶形态）。
 */
internal object ImFieldsCache {
    const val PREFS_PREFIX = "IMFieldsCache"

    fun prefsName(mid: Long): String = "$PREFS_PREFIX$mid"
}

/**
 * 移除账号时清掉它的 IM 设备 id 缓存（BACKLOG ST-12）。
 *
 * `AuthStore.removeAccount` 原先只删 `account_$mid` / 用户信息那两个 key，
 * 于是 `shared_prefs/IMFieldsCache$mid.xml` 永久留在磁盘上：账号被删了，
 * 它那份"设备身份"还在，且没有任何东西会再去读它或清它。
 *
 * 用 `deleteSharedPreferences` 而不是 `edit { clear() }`：后者只把内容清空，
 * 文件仍在（这是该 API 的既有语义，不是优化癖）。
 */
@Singleton
class ImFieldsCacheCleaner @Inject constructor(
    @param:ApplicationContext private val context: Context
) : PerAccountDataCleaner {

    override fun clean(mid: Long) {
        if (mid <= 0L) return
        context.deleteSharedPreferences(ImFieldsCache.prefsName(mid))
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ImCleanupModule {
    @Binds
    @IntoSet
    abstract fun bindImFieldsCacheCleaner(
        impl: ImFieldsCacheCleaner
    ): PerAccountDataCleaner
}
