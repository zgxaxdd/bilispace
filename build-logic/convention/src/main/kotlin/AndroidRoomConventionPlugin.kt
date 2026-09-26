import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Room 的统一配置：把 schema 快照导出到 `<module>/schemas/` 并纳入版本控制。
 *
 * 为什么必须开导出：`exportSchema = false` 时实体一改就没有任何可比对的旧 schema，
 * 既写不出 Migration（不知道该 ALTER 什么），也发现不了"升级被静默清库"——
 * `PlaybackHistoryDb` 的 2→3 就是这么把用户观看历史清掉一次的（BACKLOG ST-02）。
 *
 * 用 KSP 参数而不是 `androidx.room` 插件的 DSL：后者在 AGP 9 + 本仓 Kotlin/KSP 版本组合下
 * 只提供 `generateMigration` 一类能力，schema 位置仍以 `room.schemaLocation` 为准。
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")

            val schemaDir = layout.projectDirectory.dir("schemas").asFile.absolutePath
            extensions.configure<KspExtension> {
                arg("room.schemaLocation", schemaDir)
            }
        }
    }
}
