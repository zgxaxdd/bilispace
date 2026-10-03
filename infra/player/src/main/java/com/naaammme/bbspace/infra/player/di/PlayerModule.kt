package com.naaammme.bbspace.infra.player.di

import androidx.media3.common.util.UnstableApi
import com.naaammme.bbspace.core.common.cache.PlaybackCacheControl
import com.naaammme.bbspace.infra.player.Media3PlayerEngine
import com.naaammme.bbspace.infra.player.PlaybackMediaCache
import com.naaammme.bbspace.infra.player.PlayerEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class PlayerModule {
    @Binds
    @UnstableApi
    @Singleton
    abstract fun bindPlayerEngine(impl: Media3PlayerEngine): PlayerEngine

    /**
     * PB-03：把播放字节缓存的**控制面**（占用 / 清理）桥接到 `core:common` 的接口，
     * 让设置链路能读到它而不必依赖 `infra:player`（不新增依赖边的既有范式，
     * 同 `core:common` 的 `UpBlocker` ← `core:space`）。
     */
    @Binds
    @UnstableApi
    @Singleton
    abstract fun bindPlaybackCacheControl(impl: PlaybackMediaCache): PlaybackCacheControl
}
