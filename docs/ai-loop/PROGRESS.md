# PROGRESS —— ai-loop 已完成台账

> 本文件是状态外置的"已完成"账。每批次收尾必须更新。
> 队列在 `BACKLOG.md`，调研沉淀在 `FINDINGS.md`，真机待验证在 `L4-待真机清单.md`。

---

## 批次 B0 · 2026-09-26 · 现场恢复与遗留变更归档

**背景**：接手时工作树有 **88 处未提交变更**（75 改 + 13 新），`docs/ai-loop/` 尚不存在。
经核对代码注释与 `docs/2026-09-22-BBSpace可执行优化方案.md` §9/§10，这批变更是前序会话
**已完成并真机 A-B-A 实测过**的工作（可执行优化 B1–B9 + iOS 风格升级阶段 A/B/C 的一部分），
不是半成品。

**为什么不直接在上面改**：混合 88 个未提交文件会让本会话的每一处 diff 都无法单独回滚，
违反任务书 §2.3「小步提交 / diff 可读性是生命线」。因此先按主题分组归档，再开工。

**基线证据（归档前实测）**：`.\gradlew.bat :app:assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL，
**165 个单测 / 0 失败**（app 26、core:common 11、core:designsystem 100、core:rank 9、feature:video 15、infra:player 4）。

**10 个归档提交**（全部本地，**未推送**）：

| commit | 主题 | 文件数 | 归属任务书分区 |
|---|---|---|---|
| `706a456e` | docs：两份方案归档 + `dist/` 进 .gitignore | 3 | — |
| `377f48ae` | ui(designsystem)：令牌补全（色彩角色/Inter/深色染色/sheet 形状）+ 2 个色彩测试 | 14 | C 好看 |
| `1ea4f723` | ui(designsystem)：iOS 控件组件（ActionSheet/Alert/Grabber/BackButton） | 4 | C 好看 |
| `b38d0eba` | perf(settings)：设置镜像快照 API（B6） | 1 | B 流畅 |
| `f146dfd2` | fix(core)：协议正确性（点赞取反/QR 轮询/buvid3/wbi/下载竞态） | 6 | A 稳定 |
| `55371b62` | fix(feature)：取消语义收口（B4/B9）+ 状态正确性 | 26 | A 稳定 |
| `ea82c30e` | ui(feature)：调用侧接入 iOS 控件（7 处弹层收口）+ 结构守卫 | 14 | C 好看 |
| `ccee21cc` | perf(app)：首帧读镜像 + POST_NOTIFICATIONS 运行时申请 | 3 | B 流畅 |
| `cb0cb80b` | fix(app/playback)：`collapseToCapsule` 单一状态机，删 `forcedDismissMode` | 6 | A 稳定 |
| `ecc58f6e` | perf(video,designsystem)：B1/B2/B3/B5/B7/B8 落地 + 2 个棘轮 | 12 | B 流畅 |

**诚实记录（不掩盖）**：
1. **中间提交未逐个编译验证**。原工作树是交叉验证过的整体，但同一文件内含多个主题
   （`HomeVideoPage.kt` 同时含取色与 grabber、`MainActivity.kt` 同时含镜像与通知权限），
   非交互模式下无法拆 hunk，只能整文件归组。**只有 tip（`ecc58f6e`）是构建验证过的状态**。
   逐 commit bisect 可能命中不可编译的中间点。
2. 这批改动里有 **两处 B 级（主观观感）默认值已被翻转**：浅色 `onSurfaceVariant` 0x99→0xC8、
   iOS 深色底由纯黑改 seed 染色炭色。代码注释写明依据是"用户反馈 + 拍板（2026-09-26）"，
   即前序会话在有真机的条件下取得的决定，**本会话不推翻**，转入 L4 走查确认。
3. `UiStyle` 默认值仍为 `MATERIAL`（方案 §6 明确等真机走查干净后再翻），本批次未动。

**遗留项（继承，未关闭）**：见 `L4-待真机清单.md` L4-1 ~ L4-8；`BACKLOG.md` 的 A/B/C 三区。

---

## 批次 B1 · 2026-09-26 · 单文件 DASH 的 MP4 盒解析稳健化

**分区**：A-稳定 P1（任务书 §1 的 TODO 埋点）｜**级别**：A 级·可机器判定
**详情**：`report-2026-09-26-B1.md`

- 改动：新增 `infra/player/.../SingleFileDashIndexParser.kt`（三形态盒头、任何输入不抛异常）、
  `SingleFileDashMediaSourceFactory.kt` 改为委托并删掉私有解析函数、新增 9 条 JVM 单测
- 验证：`:app:assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL in 4m18s；
  **174 单测 / 0 失败**（B0 基线 165，本批 +9）
- 破坏性验证：把解析器退回旧语义 → `largesizeHeaderIsParsed` / `zeroSizeModeExtendsToKnownEnd`
  / `mixedHeaderWidthsAreParsed` 三条 FAILED，其余 6 条（含常规布局回归钉）仍绿 → 恢复后全绿
- commit：`fix(player): 单文件 DASH 支持扩展大小盒头与零长度盒`

## 批次 B0.5 · 2026-09-26 · Phase A 排雷（三面源码审计）

- 三个并行审计：崩溃面+协程 / 生命周期+状态恢复 / 资源+内存+网络，共回收 33 条
- 产出 `BACKLOG.md`：A-稳定 22 条（P0×2 P1×10 P2×10）、B-流畅 6 条、C-好看 10 条
- **按 FINDINGS W4 复核纪律剔掉的错误**（重要，防止照抄审计动手）：
  1. ST-01 原报"Room 无 migration → 正在硬崩"：`git log -L` 查得 `VideoDownloadDb`/`SpaceNoteDb`
     的 `version = 2` 是**出生值**（`0c316e59`、`bbe7d220`），不存在 v1 老用户 → 降级为
     "下一次实体变更即硬崩"的埋雷；真实风险与修法见 BACKLOG
  2. ST-02 复核后**升级**为已发生的损失：`PlaybackHistoryDb` 在 `215575de` 2→3 且用破坏性回退，
     用户本地观看历史确实被清过一次
  3. 审计误报（已核，不要修）：`LiveRepository.kt:119`、`PlaybackCdnSelector.kt:37` 的 `first()`、
     `ImRepository.kt:412,416` 的 `[0]` 均有上游空值守卫；`AuthRepository` 的 JSON 取值全在
     `runCatchingCancellable` 内
  4. 已核为真：ST-03（MainActivity **根本没有 onDestroy**，回调永不清理）、ST-04、ST-05、UI-08、UI-10
- 顺带发现 4 处死代码/未消费真值源（UI-08 `materialTypography` 零调用、UI-09 `SecondaryLabelAAVariants`
  仅测试引用、UI-10 `IosDarkLiftedBackground` 零引用、ST-16 恒假 `?:` 分支）

## 批次 B2 · 2026-09-26 · ST-03 单例回调泄漏已销毁的 MainActivity

**分区**：A-稳定 P1｜**级别**：A 级·可机器判定｜**详情**：`report-2026-09-26-B2.md`

- 复核：`MainActivity` 原本**没有 `onDestroy` 重写**，`@Singleton PlaybackHostState.onPlaybackUiStarted`
  在 `onCreate` 赋值后永不清理 → 后台播放场景（Activity 销毁不重建）长期持有已死 Activity + Compose 树
- 改动：回调提为 `private val playbackUiStartedCallback`；新增 `onDestroy` **按身份比对**清理；
  新增 `SingletonCallbackLeakGuardTest`（4 条，通用那条从 `PlaybackHostState` 的声明反推，
  以后再加这种回调忘记清理会直接红）
- 验证：`BUILD SUCCESSFUL in 6m1s`，**178 单测 / 0 失败**（+4）
- 破坏性验证：摘掉 `= null` → 两条断言转红；恢复后全绿
- 自身失误并已修：B1 新测试的目录拼错（`com/naammmee`），因 Kotlin 不强制目录=包名而**躲过了全部门禁**，
  `git mv` 归位（`bb0edd3a`）→ 教训入 FINDINGS W8
- commit：`fix(app): 单例回调在 onDestroy 按身份比对清理`

## 批次 B3 · 2026-09-26 · （待开始）ST-04 + ST-05 一次性资源未关闭


