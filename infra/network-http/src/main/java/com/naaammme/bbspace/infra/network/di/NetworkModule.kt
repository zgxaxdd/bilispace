package com.naaammme.bbspace.infra.network.di

import android.content.Context
import com.naaammme.bbspace.infra.crypto.BuvidFetcher
import com.naaammme.bbspace.infra.crypto.DeviceIdentity
import com.naaammme.bbspace.infra.crypto.GuestIdGenerator
import com.naaammme.bbspace.infra.crypto.HwIdGenerator
import com.naaammme.bbspace.infra.crypto.LegalRegionCache
import com.naaammme.bbspace.infra.crypto.RegionCodeCache
import com.naaammme.bbspace.infra.crypto.TicketGenerator
import com.naaammme.bbspace.infra.network.dns.BiliDns
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideDeviceIdentity(@ApplicationContext context: Context): DeviceIdentity {
        return DeviceIdentity(context)
    }

    @Provides
    @Singleton
    fun provideHwIdGenerator(deviceIdentity: DeviceIdentity): HwIdGenerator {
        return HwIdGenerator(deviceIdentity)
    }

    @Provides
    @Singleton
    fun provideBiliDns(
        @ApplicationContext context: Context,
        regionCodeCache: RegionCodeCache
    ): BiliDns {
        return BiliDns(context, regionCodeCache)
    }

    @Provides
    @Singleton
    fun provideOkHttpConnectionPool(): ConnectionPool {
        return ConnectionPool(10, 5, TimeUnit.MINUTES)
    }

    /**
     * 全站唯一的 OkHttpClient（D2）：所有链路共用它，所以这里的数字会同时作用到
     * 首页 feed、详情、下载、弹幕、票据与流媒体。
     *
     * 原先三个超时一个都没写，全部吃 OkHttp 默认值（connect/read/write 各 10s，call 无上限）。
     * 默认值本身不算差，但"没写"有两个问题：
     * 1. 升级 OkHttp 大版本时默认值会变，行为跟着变而没人 review；
     * 2. 没人知道这里是刻意留空还是忘了写 —— 于是下一个人会"顺手补个 callTimeout"，
     *    而那会给几百 MB 的下载与流式读判死刑（callTimeout 覆盖整个 call，含 body 传输）。
     *
     * 这里**显式写下与默认值相同的数字**：行为零变化，但意图进了 diff，
     * 并由 `NetworkTimeoutGuardTest` 钉住"不许加 callTimeout"。
     * 真正的取值收紧（例如 connect 从 10s 降到几秒）属网络手感，需真机对照 → L4-21。
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        biliDns: BiliDns,
        connectionPool: ConnectionPool
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(biliDns)
            .connectionPool(connectionPool)
            .addInterceptor(BrotliInterceptor)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideTicketGenerator(
        @ApplicationContext context: Context,
        deviceIdentity: DeviceIdentity,
        okHttpClient: OkHttpClient
    ): TicketGenerator {
        return TicketGenerator(context, deviceIdentity, okHttpClient)
    }

    @Provides
    @Singleton
    fun provideGuestIdGenerator(
        @ApplicationContext context: Context,
        deviceIdentity: DeviceIdentity,
        okHttpClient: OkHttpClient
    ): GuestIdGenerator {
        return GuestIdGenerator(context, deviceIdentity, okHttpClient)
    }

    @Provides
    @Singleton
    fun provideRegionCodeCache(): RegionCodeCache {
        return RegionCodeCache()
    }

    @Provides
    @Singleton
    fun provideLegalRegionCache(
        @ApplicationContext context: Context
    ): LegalRegionCache {
        return LegalRegionCache(context)
    }

    @Provides
    @Singleton
    fun provideBuvidFetcher(
        okHttpClient: OkHttpClient,
        deviceIdentity: DeviceIdentity
    ): BuvidFetcher {
        return BuvidFetcher(okHttpClient, deviceIdentity)
    }

    /**
     * 与 OkHttp 当前默认值一致，写出来是为了让意图进 diff（见 provideOkHttpClient 的注释）。
     * **不许改成 callTimeout**：它计的是整个 call（含 body 传输与全部重试/重定向），
     * 会给大文件下载与流式读判死刑。
     */
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val READ_TIMEOUT_MS = 10_000L
    private const val WRITE_TIMEOUT_MS = 10_000L
}
