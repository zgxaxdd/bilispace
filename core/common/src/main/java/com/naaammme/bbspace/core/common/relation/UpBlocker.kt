package com.naaammme.bbspace.core.common.relation

/**
 * 拉黑/解除拉黑的**能力接口**（PT-06；PerAccountDataCleaner 同款模式——
 * 接口放在两端都要看见的 core:common，实现在属主模块经 Hilt 绑定，
 * 避免消费方（feature:video）直接依赖 core:space 形成依赖边）。
 *
 * 底层实现 = bbspace 既有的 gRPC `ModifyRelation`
 * （`SpaceRepository.modifyBlacklist` → `Act.ACT_ADD_BLACK/ACT_DEL_BLACK`）。
 * **禁止移植上游的 REST `x/relation/modify`**——那是另一条协议路径，bbspace 已有等价实现。
 */
interface UpBlocker {

    /**
     * 拉黑（`isBlack = true`）/ 解除拉黑（`false`）。
     * 实现方不抛业务失败以外的异常；网络失败由调用方决定重试/保留策略。
     */
    suspend fun modifyBlacklist(mid: Long, isBlack: Boolean)
}
