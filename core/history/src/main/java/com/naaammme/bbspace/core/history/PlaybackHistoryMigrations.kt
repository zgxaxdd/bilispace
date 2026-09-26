package com.naaammme.bbspace.core.history

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * `playback_history` 的历史升级路径（BACKLOG ST-02）。
 *
 * ## 为什么要手写这两步
 * 这两次升版原先靠 `fallbackToDestructiveMigration(true)` 兜底：**2→3 已经把用户的本地观看历史
 * 清空过一次**（`215575de del local historymeta`），而 1→2 换过表名和主键格式，同样没人补过路径。
 * 停在 v1/v2 的安装下次打开仍会被清。两步都能无损做，所以这里给出真实迁移，并把全局破坏性回退删掉
 * —— 从此"改实体忘了写迁移"是**开发期崩溃**，不是**用户静默丢数据**。
 *
 * ## 为什么用"建新表-拷贝-删旧表-改名"而不是 ALTER DROP COLUMN
 * 本仓 minSdk 33，`ALTER TABLE ... DROP COLUMN`（要 SQLite 3.35）在目标机上是支持的，所以两种写法
 * 都能跑。选重建法的原因是它的成立条件更少：DROP COLUMN 在被删列进了索引/视图/触发器时会直接报错，
 * 而"这 6 列此刻没被索引"只是个当前事实，不是约束；重建法也是 Room 自动迁移自己生成的形态。
 * 代价是一次整表拷贝（该表有 `MAX_VIDEOS_PER_UID = 1000` 的上限）。
 *
 * ## 目标表结构不许凭记忆写
 * 两条迁移里的 CREATE 语句逐字取自 Room 导出的 schema 快照：v3 来自
 * `core/history/schemas/com.naaammme.bbspace.core.history.PlaybackHistoryDb/3.json`，v2 来自
 * 该版本的实体定义（`git show 215575de`）。列名与可空性由 `RoomMigrationCopyListTest` 对着 JSON 校验：
 * 改实体后忘了同步 CREATE 或拷贝列表，那条守卫就会红。
 */
object PlaybackHistoryMigrations {

    /**
     * 1→2：`local_history` → `playback_history`。
     *
     * 两处语义变化必须翻译，不能整表改名：
     * 1. **主键格式**：v1 是 `uid:video:biz:mainId:cid`（`LocalHistoryKey.videoId` 里多一段 kind），
     *    v2 起是 `uid:biz:mainId:cid`。直接把 `id` 抄过来会让所有历史行的主键与运行时
     *    `PlaybackHistoryKey.videoId()` 算出的值对不上，于是"继续观看"查不到旧行、
     *    重看同一集时插入新行 → 同一条视频留下两条记录。所以这里按 v2 的规则**重算 id**，
     *    规则与 `PlaybackHistoryKey.video()` 一致：PGC 且有 epId 时用 epId 作主 id，否则用 aid；
     *    biz 一律小写。
     * 2. **列收窄**：v1 的 `kind`/`hKey`/`bvid`/`watchAt` 在 v2 实体里不存在，直接放弃
     *    （`kind` 恒为 `video` —— v1 只有 `LocalHistoryRepoImpl` 一个写入方；`hKey` 已编码进 id）。
     *
     * `INSERT OR IGNORE` 而不是 `INSERT`：重算 id 后理论上是 1:1，但若 v1 里存在过别的 kind
     * 或脏行，撞主键会让整条迁移抛错、把用户钉死在打不开界面上。宁可少一行。
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playback_history_new` (" +
                    "`id` TEXT NOT NULL, `uid` INTEGER NOT NULL, `biz` TEXT NOT NULL, " +
                    "`aid` INTEGER NOT NULL, `cid` INTEGER NOT NULL, `epId` INTEGER, `seasonId` INTEGER, " +
                    "`title` TEXT NOT NULL, `cover` TEXT, `part` INTEGER, `partTitle` TEXT, " +
                    "`ownerUid` INTEGER, `ownerName` TEXT, " +
                    "`durationMs` INTEGER NOT NULL, `progressMs` INTEGER NOT NULL, " +
                    "`watchMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `finished` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO `playback_history_new` " +
                    "(`id`, `uid`, `biz`, `aid`, `cid`, `epId`, `seasonId`, " +
                    "`title`, `cover`, `part`, `partTitle`, `ownerUid`, `ownerName`, " +
                    "`durationMs`, `progressMs`, `watchMs`, `updatedAt`, `finished`) " +
                    "SELECT " +
                    "`uid` || ':' || LOWER(`biz`) || ':' || " +
                    "CASE WHEN UPPER(`biz`) = 'PGC' AND IFNULL(`epId`, 0) > 0 THEN `epId` ELSE `aid` END " +
                    "|| ':' || `cid`, " +
                    "`uid`, `biz`, `aid`, `cid`, `epId`, `seasonId`, " +
                    "`title`, `cover`, `part`, `partTitle`, `ownerUid`, `ownerName`, " +
                    "`durationMs`, `progressMs`, `watchMs`, `updatedAt`, `finished` " +
                    "FROM `local_history`"
            )
            db.execSQL("DROP TABLE IF EXISTS `local_history`")
            db.execSQL("ALTER TABLE `playback_history_new` RENAME TO `playback_history`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_history_uid_updatedAt` " +
                    "ON `playback_history` (`uid`, `updatedAt`)"
            )
        }
    }

    /**
     * 2→3：删掉 6 个 meta 列（`title`/`cover`/`part`/`partTitle`/`ownerUid`/`ownerName`）。
     *
     * 这 6 列在 `215575de` 之后**没有任何读取方**（`PlaybackHistoryEntity.toModel()` 不再映射它们，
     * 界面上的标题/封面改从远端详情取），所以删列是无损的：观看进度、时长、更新时间一行都不动。
     * 当初用破坏性回退把它们"删掉"连带删掉了整张表，这才是 ST-02 的病灶。
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playback_history_new` (" +
                    "`id` TEXT NOT NULL, `uid` INTEGER NOT NULL, `biz` TEXT NOT NULL, " +
                    "`aid` INTEGER NOT NULL, `cid` INTEGER NOT NULL, `epId` INTEGER, `seasonId` INTEGER, " +
                    "`durationMs` INTEGER NOT NULL, `progressMs` INTEGER NOT NULL, " +
                    "`watchMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `finished` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO `playback_history_new` " +
                    "(`id`, `uid`, `biz`, `aid`, `cid`, `epId`, `seasonId`, " +
                    "`durationMs`, `progressMs`, `watchMs`, `updatedAt`, `finished`) " +
                    "SELECT `id`, `uid`, `biz`, `aid`, `cid`, `epId`, `seasonId`, " +
                    "`durationMs`, `progressMs`, `watchMs`, `updatedAt`, `finished` " +
                    "FROM `playback_history`"
            )
            db.execSQL("DROP TABLE `playback_history`")
            db.execSQL("ALTER TABLE `playback_history_new` RENAME TO `playback_history`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_history_uid_updatedAt` " +
                    "ON `playback_history` (`uid`, `updatedAt`)"
            )
        }
    }

    /** 供 DI 与守卫共用：注册顺序无关，Room 自己找路径。 */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
