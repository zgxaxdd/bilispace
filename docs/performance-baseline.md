# BBSpace 性能基线

> 建立于 2026-09-06。规则：**后续所有性能优化（如 Baseline Profile 调优）必须以本表对照，不得在无基线数据时宣称提升。**
> 2026-10-05 更新：接入真机 **OPPO PLC110 / Android 16**，下表 8 格已由「待真机」变为实测值。
> 测量口径见 §1；测量时的构建见 §2 表头。**样本量小（n=5），凡区间重叠的差异一律不当作提升。**

## 1. 测量方法

### 冷启动

```bash
# 冷启前先杀进程，重复 5 次取中位数
adb shell am force-stop com.naaammme.bbspace
sleep 3
adb shell am start -W -n com.naaammme.bbspace/.MainActivity
# 记录 "TotalTime"（ms）
```

### 滚动帧率（首页推荐流、视频详情页各测一次）

```bash
# 进入目标页面后连续滑动约 10s，期间采集：
adb shell dumpsys gfxinfo com.naaammme.bbspace framestats
# 关注 Janky frames 占比与 90th/95th/99th percentile 帧耗时
```

### 内存

```bash
# 分别在：冷启动首屏稳定后、首页深滚 30s 后、退出视频回首页 30s 后采集
adb shell dumpsys meminfo com.naaammme.bbspace
# 记录 TOTAL PSS 与 Views 数量（对比退出页面后 Views 是否回落，辅助判断泄漏）
```

## 2. 基线表（2026-10-05 实测）

构建：`:app:assembleRelease`（release / arm64-v8a，minify + R8，含 P0-1 修正后的 baseline profile）。
测量设备：OPPO PLC110 / Android 16，**真机、非测试专用机**（后台有其他应用，噪声偏大）。

| 机型 | 系统版本 | App 版本 | 冷启动 TotalTime(ms) | 首页滚动 Janky% | 详情页滚动 Janky% | 首屏 PSS(MB) | 深滚后 PSS(MB) | 退出回首页 PSS(MB) |
|------|---------|----------|---------------------|-----------------|-------------------|--------------|----------------|--------------------|
| OPPO PLC110 | 16 | 1.8.04 | **181**（n=5，中位；区间 179–188） | **1.33%**（16/1203） | **0.34%**（3/871） | **156.3** | **223.9** | **197.0** |

采集细节（可复现）：

- 冷启动：`force-stop` → 等 3s → `am start -W`；**前 3 次预热丢弃**，后 5 次取中位。
  ⚠️ 预热次数会显著影响结果：只预热 2 次时中位是 164ms（同一份 APK），**说明该指标噪声大**，
  比较两组数据必须用同一套流程。
- 滚动：`dumpsys gfxinfo <pkg> reset` → `input swipe` 连续滑动约 10s → 读 `Janky frames`。
  **滑动是脚本注入（非真人手指）**，帧率会比真人操作乐观，只可用于同口径横向比较。
- PSS：`dumpsys meminfo <pkg>` 的 `TOTAL PSS`（KB / 1024）。首屏 = 冷启后静置 12s；
  深滚后 = 首页连续滑 12 次、静置 25s；退出回首页 = 从详情页返回、静置 30s。
- 滚动帧耗时分位：首页 p50 13 / p90 17 / p95 19 / p99 26 ms；详情页 p50 6 / p90 8 / p95 10 / p99 12 ms。


## 3. 当前播放缓冲参数（现状记录，未改动）

`PlayerBufferProfile`（core/model），默认档位 **FastStart**：

| 档位 | minBuffer | maxBuffer | playBuffer | rebuffer | backBuffer | 适用 |
|------|-----------|-----------|------------|----------|------------|------|
| FastStart（默认） | 2s | 15s | 250ms | 500ms | 2s | 起播最快；弱网/倍速时缓冲余量小，可能卡顿 |
| Balanced | 5s | 20s | 500ms | 1s | 5s | 均衡 |
| Stable | 10s | 30s | 750ms | 1.5s | 5s | 弱网推荐 |

CDN 默认 `Backup2`（第三条备用线路）；配合 `CdnFailoverDataSource` 打开失败自动换线。

**弱网建议（分析，不改默认值）**：频繁断流的弱网用户可在「三个点 → 播放设置 → 缓冲策略」切到 Stable；默认 FastStart 对好网体验最优，改动需先有本表基线对照。

## 4. Baseline Profile

- 手工基线文件：`app/src/main/baseline-prof.txt`（启动/首屏/播放关键路径）。
- 验证方式：`unzip -l app-arm64-v8a-release.apk | grep baseline.prof` 应命中 `assets/dexopt/baseline.prof`；包体增量应 <100KB。

### 4.1 2026-10-05：修掉「自有 Compose 一条都没进 profile」

**实测缺陷**：原有 40 条 `HSPL<自有类>;->*()V` 只匹配「无参 void 方法」。展开产物
（`app/build/intermediates/r8_art_profile/release/expandReleaseArtProfileWildcards/baseline-prof.txt`）里
属于 bbspace 自有类的规则 79 条，**带 `Composer` 签名的 0 条** —— `HomeScreenKt` / `ThemeKt` /
`AppNavHostKt` 各自只剩一条 `-><clinit>()V`。即：**三方库进了 profile，自家首屏 Compose 一条没进**。

**修法**：用 release 编译产物 + `javap -p -s` 取出 10 个自有 Compose 类的真实签名，
追加 **94 条显式规则**（原有 51 条一条未删）。注意「换成包级通配」走不通：
实测 `HSPLcom/naaammme/bbspace/feature/home/**;->*()V` 展开后带 `Composer` 的仍是 0 条。

**构建期验收（硬）**：

| | 修改前 | 修改后 |
|---|---|---|
| 展开产物·自有类带 `Composer` 规则 | **0** | **94** |
| 打包进 APK 的 `assets/dexopt/baseline.prof` | 12,591 字节 | 12,717 字节 |
| APK 总大小 | 10,835,805 字节 | 10,835,805 字节（**无增量**） |

回归守卫：`app/src/test/.../perf/BaselineProfileComposeCoverageTest.kt`（棘轮：自有 Composer 规则数只许增）。

**运行期收益：实测不可分辨，不得宣称提升。**

同一份代码、只换 profile 组两份 APK，同一套流程各测 5 次：

| | 冷启动中位 | 5 次区间 |
|---|---|---|
| 旧 profile（0 条自有 Composer 规则） | 185 ms | 165–193 ms |
| 新 profile（94 条自有 Composer 规则） | 181 ms | 179–188 ms |

差 4ms 且**区间重叠**，n=5 下落在噪声内。要给出可信结论需要更大样本量与受控设备状态
（本机是日常用机，后台负载不可控）。**在此之前，本表只记录「profile 已正确覆盖自有路径」这一构建期事实。**

