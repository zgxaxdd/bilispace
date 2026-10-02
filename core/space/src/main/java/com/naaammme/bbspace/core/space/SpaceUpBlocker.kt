package com.naaammme.bbspace.core.space

import com.naaammme.bbspace.core.common.relation.UpBlocker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PT-06：把 bbspace **既有**的 gRPC 拉黑能力（[SpaceRepository.modifyBlacklist]）
 * 以 [UpBlocker] 能力接口的形式暴露给刷视频链路（feature:video）。
 *
 * **不要在这里加 REST `x/relation/modify`**——那是上游 BiliPai 的协议路径，
 * bbspace 的 gRPC `ModifyRelation` 是等价且已在用的实现（移植任务书 §0.1 X1）。
 */
@Singleton
internal class SpaceUpBlocker @Inject constructor(
    private val repo: SpaceRepository
) : UpBlocker {
    override suspend fun modifyBlacklist(mid: Long, isBlack: Boolean) {
        repo.modifyBlacklist(fid = mid, isBlack = isBlack)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SpaceUpBlockerModule {

    @Binds
    internal abstract fun bindUpBlocker(impl: SpaceUpBlocker): UpBlocker
}
