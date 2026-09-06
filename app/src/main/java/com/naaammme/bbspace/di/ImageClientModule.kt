package com.naaammme.bbspace.di

import com.naaammme.bbspace.core.common.UserAgentBuilder
import com.naaammme.bbspace.infra.crypto.DeviceIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageClientModule {

    /**
     * 图片加载专用 OkHttp：基于主 client 派生（共享连接池与 HttpDNS），
     * 追加 hdslb UA 与封面 404 回退。不重建 ConnectionPool，
     * AppInitializer 的图片连接预热才能命中同一池。
     */
    @Provides
    @Singleton
    @Named("image")
    fun provideImageOkHttpClient(
        okHttpClient: OkHttpClient,
        deviceIdentity: DeviceIdentity
    ): OkHttpClient {
        val grpcUa = UserAgentBuilder.buildGrpcUserAgent(deviceIdentity.model, deviceIdentity.osVer)
        return okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.host.endsWith("hdslb.com")) {
                    chain.proceed(
                        req.newBuilder()
                            .header("User-Agent", grpcUa)
                            .build()
                    )
                } else {
                    chain.proceed(req)
                }
            }
            .addInterceptor { chain ->
                // 封面缩放参数（@640w_360h… 等）在部分原图上会被 CDN 拒绝（404），
                // 此时回退到无后缀的原图地址重试
                val req = chain.request()
                val response = chain.proceed(req)
                if (
                    response.code == 404 &&
                    req.url.host.endsWith("hdslb.com") &&
                    req.url.encodedPath.contains("@")
                ) {
                    response.close()
                    val originalUrl = req.url.toString().substringBefore('@')
                    return@addInterceptor chain.proceed(
                        req.newBuilder().url(originalUrl).build()
                    )
                }
                response
            }
            .build()
    }
}
