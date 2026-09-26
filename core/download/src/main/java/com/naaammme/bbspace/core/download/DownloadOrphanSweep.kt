package com.naaammme.bbspace.core.download

import java.io.File

/**
 * 清掉下载根目录下的**孤儿任务目录**（BACKLOG ST-11）。
 *
 * ## 病灶
 * 删除路径是"先 `dao.delete(taskId)` 提交，再 `deleteRecursively()` 且**丢弃返回值**"。
 * 于是任何一次"行没了但目录没删干净"都会永久留下几百 MB 的目录 —— 启动恢复只按 DB 驱动，
 * 没有任何机制会再去看这些目录，用户也看不见它们。已知的产生路径见 `delete()` 里 D1 那段注释：
 * drain 抢占窗口内 runTask 会带着已删实体继续 `mkdirs` 并写完整个下载。
 *
 * ## 规则（刻意保守）
 * 只删满足全部条件的直接子项：名字能解析成 Long（= 任务 id）、确实是目录、
 * 且该 id 在当前 DB 里没有行。
 * 其它任何东西都留着：解析不出 id 的目录/文件可能是别人（未来的缓存、日志、手动放的素材）放在这里的，
 * 而"少删一个目录"与"误删用户的数据"代价不对称。
 *
 * ## 为什么是纯函数
 * `File` 操作在 JVM 上就能跑，不需要 Android Context，也不需要 Robolectric；
 * 目录名与 id 的映射规则才是这个缺陷的核心，值得单独钉住。
 * 取"哪些 id 还活着"由调用方负责 —— **拿不到就一个都别删**（见 repository 侧的 fail-closed）。
 *
 * @return 被删除的目录名（升序），供日志与测试断言。
 */
internal fun sweepOrphanTaskDirs(
    root: File,
    liveTaskIds: Set<String>
): List<String> {
    if (!root.isDirectory) return emptyList()
    val children = root.listFiles().orEmpty()
    val removed = mutableListOf<String>()
    for (child in children) {
        val id = child.name.toLongOrNull() ?: continue
        if (id.toString() != child.name) continue
        if (!child.isDirectory) continue
        if (child.name in liveTaskIds) continue
        if (child.deleteRecursively()) removed += child.name
    }
    return removed.sorted()
}
