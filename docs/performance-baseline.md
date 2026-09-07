# BBSpace 性能基线

> 建立于 2026-09-06。规则：**后续所有性能优化（如 Baseline Profile 调优）必须以本表对照，不得在无基线数据时宣称提升。**
> 本地为开发机（Windows），无连接真机/模拟器（`adb devices` 为空），下表全部标注「待真机」。

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

## 2. 基线表（v1.9.0 工作树，release 构建）

| 机型 | 系统版本 | App 版本 | 冷启动 TotalTime(ms) | 首页滚动 Janky% | 详情页滚动 Janky% | 首屏 PSS(MB) | 深滚后 PSS(MB) | 退出回首页 PSS(MB) |
|------|---------|----------|---------------------|-----------------|-------------------|--------------|----------------|--------------------|
| 待真机 | - | 1.9.0 | 待真机 | 待真机 | 待真机 | 待真机 | 待真机 | 待真机 |

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
- 冷启动收益验证：合并基线前后各跑一次 §1 冷启动流程对照（待真机）。
