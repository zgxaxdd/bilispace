package com.naaammme.bbspace.core.playback.di

import com.naaammme.bbspace.core.playback.DownloadPlaybackController
import com.naaammme.bbspace.core.playback.DownloadPlaybackControllerImpl
import com.naaammme.bbspace.core.playback.LivePlaybackController
import com.naaammme.bbspace.core.playback.StreamPlaybackSession
import com.naaammme.bbspace.core.playback.StreamPlaybackSessionImpl
import com.naaammme.bbspace.core.playback.CdnHealthLineOutcomeListener
import com.naaammme.bbspace.core.playback.VideoPlaybackController
import com.naaammme.bbspace.core.playback.VideoPlayerRepository
import com.naaammme.bbspace.core.playback.repository.VideoPlayerRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import com.naaammme.bbspace.infra.player.CdnLineOutcomeListener
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    /** 闭环修复：换线/索引读取的真实结果 → 健康度（记账实现见 CdnHealthLineOutcomeListener）。 */
    @Binds
    @Singleton
    abstract fun bindCdnLineOutcomeListener(
        impl: CdnHealthLineOutcomeListener
    ): CdnLineOutcomeListener


    @Binds
    @Singleton
    abstract fun bindVideoPlayerRepository(impl: VideoPlayerRepositoryImpl): VideoPlayerRepository

    @Binds
    @Singleton
    abstract fun bindStreamPlaybackSession(impl: StreamPlaybackSessionImpl): StreamPlaybackSession

    @Binds
    @Singleton
    abstract fun bindVideoPlaybackController(impl: StreamPlaybackSessionImpl): VideoPlaybackController

    @Binds
    @Singleton
    abstract fun bindLivePlaybackController(impl: StreamPlaybackSessionImpl): LivePlaybackController

    @Binds
    @Singleton
    abstract fun bindDownloadPlaybackController(
        impl: DownloadPlaybackControllerImpl
    ): DownloadPlaybackController
}
