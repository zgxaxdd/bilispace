package com.naaammme.bbspace.core.common.account

/**
 * "移除账号时，本模块那份按 mid 存的本地数据也要跟着清掉"的契约。
 *
 * 放在 `core:common` 是因为它两端都要看见：账号侧（`core:auth`）负责在 `removeAccount`
 * 时逐个调用，数据侧（各 feature/core 模块）负责注册自己的实现。
 * 让 `core:auth` 直接依赖某个具体模块会形成反向依赖（那些模块本来就依赖 auth），
 * 所以这里只放接口，实现由 Hilt 的多绑定集合注入。
 *
 * 实现要点：
 * - **必须幂等**：同一 mid 清两次不该报错，文件本来不存在也是成功。
 * - **必须快**：它在账号移除的路径上被同步调用，别在这里做网络或大 IO。
 * - 抛异常会被账号侧吞掉并记日志（不能因为某个模块的缓存清不掉就让用户删不了账号），
 *   所以不要把"清理失败"当成需要上抛的理由。
 */
interface PerAccountDataCleaner {
    fun clean(
        mid: Long
    )
}
