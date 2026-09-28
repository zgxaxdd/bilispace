package com.naaammme.bbspace.core.common.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前默认网络是不是"不限流量"的那一类。
 *
 * 用途：竖屏连播的**相邻页预取**要按网络类型决定预几条（对齐 BiliPai
 * `resolvePortraitPlayUrlPreloadCount`：WiFi+开关=3、WiFi=2、开关=1、否则 0）——
 * 蜂窝下无脑预取会偷跑用户流量。
 *
 * 判据取"默认网络的 transport 是 WIFI/ETHERNET"，**不看信号强弱**：
 * 预取只是个尽力而为的加速，不值得为弱网再引一层判据（弱网下预取会自己超时失败）。
 */
@Singleton
class NetworkTypeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 无网络、权限缺失、取不到能力时一律 false（= 按"可能计费"保守处理）。 */
    fun isUnmetered(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
