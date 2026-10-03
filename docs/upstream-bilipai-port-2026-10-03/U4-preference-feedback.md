# U4 报告：bbspace 与 BiliPai 的「刷视频喜好回流官方哔哩哔哩」机制逐项对照

> **标记约定**
> `【事实】` = 本报告已读到源码行，可直接复核；
> `【推断】` = 由已读代码推出的结论（未逐字读到该结论本身）；
> `【待真机/待抓包】` = 静态读码无法判定，必须真机或抓包；
> `需更细读` = 本次未读到足够证据，不作猜测。
>
> **只读声明**：本报告未修改任何仓库文件，未对 bbspace 做任何 git 写操作。
>
> **读取基线（只读 `git rev-parse` / `git status --porcelain` 得到）**
> - BB = `D:\111j\c\_ref\bbspace`，HEAD `a5df3387188cf1d41bd8b620b1d43011bfbc3a37`，
>   工作区 **104 项未提交改动**（本报告读的是这棵含未提交改动的工作树）。
> - BP = `D:\111j\c\_ref\BiliPai-024`（worktree，detached HEAD `6e6bdce36d7ee3e456947280cec105f7cb096d32`），工作区干净。
> - 下文行号：`BB:` 前缀指 bbspace 工作树，`BP:` 前缀指 BiliPai-024。路径省略仓库根。
>
> **权威边界**：本报告只判断"客户端是否发出了这些请求、参数是什么"。
> **服务端是否真的采纳（是否影响后续推荐排序）本次一律不判** → `【待真机/待抓包】`。

---

## ① 回流通道总图（文字版：行为 → 接口 → 参数）

### BB（bbspace）——共 6 条离机通道，其中 3 条与"喜好"直接相关

```
[行为]                        [接口]                                  [关键参数]                          [代码]
A1 起播(首帧)           →  POST /x/report/heartbeat/mobile         session,aid,cid,type,sub_type,          BB:core/playback/.../RemotePlaybackReporter.kt:210,26-36,80-143
                             (App 协议签名, start 包全零)              quality,video_duration,play_type,
                                                                     play_status,from,from_spmid,spmid,
                                                                     track_id,report_flow_data,sid,epid,
                                                                     polaris_action_id,extra
A1 暂停/播完/切页/退出   →  POST /x/v2/history/report               aid,cid,duration,progress(看完=-1),     BB:RemotePlaybackReporter.kt:211,47-58,145-169
                             (App 协议签名)                            type,device_ts,start_ts,
                                                                     source=player-old,scene=front
A1 播完                  →  POST /x/report/heartbeat/mobile(end)   start_ts,total_time,paused_time,        BB:RemotePlaybackReporter.kt:38-45,126-136
                                                                     played_time,last/max_play_progress_time,
                                                                     actual_played_time
   └ 同一时刻还写本地         Room `playback_history.db`                                                  BB:core/playback/.../LocalPlaybackHistoryRecorder.kt:14-42

A3 点赞                  →  POST /x/v2/view/like                    aid, like(0=赞/1=取消)                  BB:core/video/.../VideoActionRepository.kt:30-39,111
A3 投币                  →  POST /x/v2/view/coin/add                aid, multiply, select_like              BB:VideoActionRepository.kt:44-50,112
A3 一键三连              →  POST /x/web-interface/archive/like/triple  aid,eab_x,ramval,source,ga,csrf,     BB:VideoActionRepository.kt:55-79,113
                                                                     spmid,statistics (Web Cookie+csrf)
A3 收藏                  →  POST /x/v3/fav/resource/batch-deal      resources,fav add/del_media_ids,csrf    BB:core/favorite/.../FavoriteRepository.kt:154-171,397

A4 不感兴趣(首页+刷视频) →  GET  /x/feed/dislike                   id,goto,is_light_panel,from_spmid,      BB:core/feed/.../FeedDislikeRepository.kt:27-48,99
                                                                     spmid,report_data,action_id,
                                                                     reason_id | feedback_id
A4 撤回不感兴趣          →  GET  /x/feed/dislike/cancel            id,goto,...,action_id                    BB:FeedDislikeRepository.kt:50-63,100

A5 兴趣选择提交          →  GET  /x/v2/feed/index                 idx,pull,flush,ps,interest_id,          BB:core/feed/.../FeedRepository.kt:72-92,85-87
                             (+ /x/v2/feed/uinterest 拉选项)      interest_result,interest_pos_ids        BB:core/feed/.../InterestRepository.kt:27-49,109

A7 会话/痕迹标识         →  (不是独立通道) 注入到上面两条           action_id / polaris_action_id /         BB:core/feed/.../PageActionTracker.kt:10-21
                                                                     session=BiliSessionId.view(buvid)       BB:core/playback/.../PlaybackReporter.kt:36,65

A7 风控(与喜好无关)      →  gRPC bilibili.gaia.gw.Gaia/            DeviceAppList: source(first_installation   BB:infra/network-grpc/.../GaiaReporter.kt:26-130,
                             ExClimbAppleTrees                        |first_open) + 已装应用包名列表           :36,:132-136,:178-201
                             (首装 或 7 天一次; 可被设置关闭)

A8 刷视频本地记录        →  **零离机**（SwipeSeenMemory / SwipeQoeHub /                          BB:core/swipe/.../queue/SwipeSeenMemory.kt:35-127
                             SwipeAutoAdvancePolicy / SwipeSimilarity 全部本地）                BB:core/swipe/.../qoe/SwipeQoeHub.kt:12,17-81
```

### BP（BiliPai）——共 8 条离机通道，其中 4 条与"喜好"直接相关

```
[行为]                        [接口]                                  [关键参数]                          [代码]
A1 每 30s 周期心搏       →  POST x/click-interface/web/heartbeat     aid,bvid,cid,epid,sid,mid,             BP:core-data/.../PlaybackHeartbeatPolicy.kt:10-53
   + 起播首包 + 暂停/退出/   (Web 表单, csrf)                          played_time,real_played_time,realtime,
     换播放器/换视频        →                                        start_ts,type,sub_type,dt,outer,
                                                                     play_type,spmid,from_spmid,
                                                                     refer_url,csrf
                                                                                                          BP:app/.../HistoryRepository(HistoryRepository.kt):175-187
                                                                                                          BP:app/.../viewmodel/VideoPlaybackViewModel.kt:8903-8946
A1 专栏阅读历史          →  POST x/v2/history/report                (buildArticleHistoryReportFields)      BP:HistoryRepository.kt:189-216 ; ApiClient.kt:311-315
A3 点赞                  →  POST x/web-interface/archive/like        aid, like(1=赞/2=取消), csrf           BP:ActionRepository.kt:731-753 ; ApiClient.kt:1374-1380
A3 投币                  →  POST x/web-interface/coin/add            aid, multiply, select_like, csrf       BP:ActionRepository.kt:836-860 ; ApiClient.kt:1399-1406
A3 视频级点踩            →  POST /x/v2/view/dislike                  aid, dislike(0=踩/1=取消), csrf,         BP:ActionRepository.kt:779-812 ; ApiClient.kt:1389-1396
                             (App 端点)                               access_key
A3 收藏                  →  POST x/v3/fav/resource/deal              (api.favResourceDeal)                  BP:ApiClient.kt:1348 ; ActionRepository.kt:256
A3 三连                  →  3 次串行：like + coin(2,alsoLike) + fav                                          BP:ActionRepository.kt:893-922
A3 关注/取关             →  POST x/relation/modify                   fid, act(1=关注/2=取关), csrf           BP:ActionRepository.kt:222-254,232-234 ; ApiClient.kt:1330-1336
A4 首页卡片不感兴趣      →  GET  https://app.bilibili.com/x/feed/dislike  goto,id,reason_id|feedback_id,      BP:ActionRepository.kt:115-135,56-75 ; ApiClient.kt:535-538
                             (TV appkey 签名)                          build,mobi_app,access_key,appkey,ts
A4 拉黑 UP（同步官方）   →  POST x/relation/modify                   fid, act(5=拉黑/6=解除), csrf,re_src    BP:BlockedUpRepository.kt:311-332,450-479,461

A7 设备指纹              →  GET  x/frontend/finger/spi               (buvid3/buvid4)                        BP:ApiClient.kt:1505-1506 ; VideoRepository.kt:271,295-310
                             + POST x/internal/gaia-gateway/ExClimbWuzhi                                        BP:ApiClient.kt:1509-1513

  第三方(非官方 B 站)     →  Firebase Analytics                      screen_view,video_play,video_complete,  BP:core/util/AnalyticsHelper.kt:65,58-63,315-336,372-375
                                                                     like/share/favorite/follow          BP:VideoPlaybackViewModel.kt:3615
  反回流(方向相反)         →  去掉 Web 首页推荐请求的 Cookie            (不改请求体)                            BP:feature/plugin/HomeFeedAnonymizerPlugin.kt:58-85
A8 本地-only(不离机)     →  TodayWatchFeedbackStore / TodayWatchProfileStore /                    BP:core/store/TodayWatchFeedbackStore.kt:80-179
                             HomeNotInterestedPolicy / BiliPaiFeedFilterPlugin /                  BP:core/store/TodayWatchProfileStore.kt:31-141
                             markVideoNotInterested                                               BP:feature/home/HomeNotInterestedPolicy.kt:159-187
                                                                                                  BP:VideoPlaybackViewModel.kt:4506-4534
```

---

## ② 逐通道详析（A1~A8）

### A1 播放进度 / 观看历史上报

#### BB

| 项 | 证据 |
|---|---|
| 心搏接口 | `BB:core/playback/src/main/java/com/naaammme/bbspace/core/playback/RemotePlaybackReporter.kt:210` → `HB_URL = "${BiliConstants.BASE_URL_API}/x/report/heartbeat/mobile"` |
| 历史接口 | `BB:RemotePlaybackReporter.kt:211` → `PLAYBACK_HISTORY_URL = "${BiliConstants.BASE_URL_API}/x/v2/history/report"` |
| 协议 | `BB:RemotePlaybackReporter.kt:69` `restClient.postSignedRaw(url, params, BiliRestProfile.APP)` → **App 协议（access_key + appkey 签名）**，非 Web 表单 |
| start 包 | `BB:RemotePlaybackReporter.kt:26-36`：`start_ts/total_time/paused_time/played_time/last_play_progress_time/max_play_progress_time/actual_played_time/list_play_time/miniplayer_play_time` 全填 `"0"`（`:116-125`）；从响应 `data.ts` 读回真实 `start_ts`（`:189-195`） |
| end 包 | `BB:RemotePlaybackReporter.kt:38-45` + `:126-136`：填累计值；`report.biz != UGC` 时额外补 `realtime`（`:138-140`） |
| 历史包 | `BB:RemotePlaybackReporter.kt:145-169`：`aid,cid,duration,progress,type,device_ts,start_ts,source="player-old",scene="front"`（`:203-204`） |
| 计数来源 | `BB:core/playback/.../PlaybackReportSession.kt:39-62` `sync()`：用 `SystemClock.elapsedRealtime()` 差值累加 `playedTimeMs`（在播）/`actualPlayedTimeMs`（在播）/`pausedTimeMs`（heartbeatStarted 且 `PlaybackState.Ready`）/`totalTimeMs`；`lastProgressSec`/`maxProgressSec` 取秒 |
| "看完"语义 | `BB:PlaybackReportSession.kt:68-74` `progressSec()`：`completePlayback=true` → 返回 **-1**（官方"看完"约定）；否则返回秒 |
| 完播判据 | `BB:PlaybackReportSession.kt:76-87` `isComplete()`：`source.isPreview` → false；`Ended` → true；否则 `durationMs - positionMs <= 3000`（`COMPLETE_THRESHOLD_MS`，`:97`） |
| 起播触发 | `BB:core/playback/.../StreamPlaybackSessionImpl.kt:92-99` + `114-119`：`firstFrameSeq > 0` 的**上升沿** → `startHeartbeat` + `startPlaybackHistory` |
| 暂停触发 | `BB:core/playback/.../PlaybackReporter.kt:121-123`：`prevPlayWhenReady && !playWhenReady && Ready` → `reportPlaybackHistory(complete=false)` |
| 播完触发 | `BB:PlaybackReporter.kt:124-130`：`Ended` 由非 Ended 进入 → `reportPlaybackHistory(complete=true)` + `endHeartbeat` |
| 切页 / 退出触发 | `BB:core/playback/.../StreamPlaybackSessionImpl.kt:509-559` `finishVideoPlayback()`：`:543-550` `detachSession` → `:552-558` `finalizeSession`（切页路径 `finalizeReportAsync=true` → `runtimeScope.launch(Dispatchers.IO)`，`:556-558`） |
| **无首帧则完全无信号** | `BB:PlaybackReporter.kt:164-166` `finalizeSession()`：`if (!active.playbackHistoryStarted) return` |
| 无周期心搏 | 【事实】全仓 grep `heartbeat`：`core/playback` 下只有 start/end 两类调用点，**没有任何 `delay()` 循环**；唯一 30s 心搏在 `BB:core/live/.../LiveRoomMessageRepository.kt:341-345,910`（直播弹幕心跳，与喜好无关） |
| 上报开关 | `BB:core/model/.../PlayerSettingsModels.kt:116` `reportPlayback: Boolean = true`（默认开）；持久化 `BB:core/settings/.../AppSettings.kt:634,666,766`；UI 开关 `BB:feature/video/.../VideoPlaybackSheet.kt:388-389`；闸门 `BB:RemotePlaybackReporter.kt:197-199` |
| 本地 + 远端都做 | `BB:PlaybackReporter.kt:222-229` `recordAndReportPlaybackHistory()`：先 `localHistoryRecorder.record()` 再 `remoteReporter.reportPlaybackHistory()` |
| 本地历史落点 | `BB:core/playback/.../LocalPlaybackHistoryRecorder.kt:14-42` → `BB:core/history/.../PlaybackHistoryRepository.kt:23-25`（Room `playback_history.db`，`：55-68`；每 uid 上限 1000 条 `:49`） |
| 错误处理 | `BB:RemotePlaybackReporter.kt:68-78`：非 0 code 只 `Logger.w`，**不重试、不排队**；用 `runCatchingCancellable`（注释 `:64-67` 说明为何不能用裸 `runCatching`） |
| 线程 | `StreamPlaybackSessionImpl.kt:114-130` `init { runtimeScope.launch { playerEngine.playbackState.collect { ... reporter.onPlaybackState(...) } } }`（会话作用域协程）；切页收尾在 `Dispatchers.IO`（`:556`） |
| **死代码** | 【事实】`BB:PlaybackReporter.kt:133-142` `finishSession()` 全仓**无任何调用点**（唯一引用即定义处），实际收尾走 `detachSession`+`finalizeSession` |

**刷视频模式翻页是否上报：是（在有首帧的前提下）**【事实】
```
BB:feature/video/.../player/PortraitSwipePager.kt:174-179  snapshotFlow{ pagerState.currentPage } → viewModel.onSwipePageChanged(page)
BB:PortraitSwipePager.kt:237-245                      LaunchedEffect(currentId, pages) → onSwipeTo(entry)
BB:feature/video/.../VideoViewModel.kt:907-926        openSwipeTarget(entry) → playbackController.openVideo(entry.target)   (:925)
BB:core/playback/.../StreamPlaybackSessionImpl.kt:330-352,374-380  openVideo → finishVideoPlayback(finalizeReportAsync = true)
BB:StreamPlaybackSessionImpl.kt:543-558               detachSession + finalizeSession → 上报 history + endHeartbeat
```
注意 `BB:VideoViewModel.kt:957-961` 的 `onSwipePageChanged` **只做状态机记账 + QoE Page 事件**，不直接上报（上报由上面的 `openVideo` 链路完成）。

本地历史与远端上报**都做**（`BB:PlaybackReporter.kt:227-228`）。

#### BP

| 项 | 证据 |
|---|---|
| 接口 | `BP:core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt:887-892` `@POST("x/click-interface/web/heartbeat") reportHeartbeat(@FieldMap)` → **Web 表单 + csrf** |
| 参数构造 | `BP:core-data/.../data/repository/PlaybackHeartbeatPolicy.kt:10-53`：`aid,bvid,cid,epid,sid,mid,played_time,real_played_time,realtime,start_ts,type,sub_type,dt,outer,play_type,spmid="333.788.0.0",from_spmid="",refer_url="",csrf` |
| play_type 语义 | `BP:PlaybackHeartbeatPolicy.kt:3-8`：`played==0 && real==0` → 1（首包），否则 0 |
| 业务入口 | `BP:app/.../data/repository/VideoRepository.kt:1023-1039` `reportPlayHeartbeat()` → `HistoryRepository.reportPlayback(...)` + 成功时 `HistoryRefreshBus.notifyChanged()`（`:1037`） |
| 真实上报 | `BP:app/.../HistoryRepository(HistoryRepository.kt):175-187`：`dataRequest{ ... api.reportHeartbeat(fields) }`；`:181` **隐私模式闸门**（`SettingsManager.kt:6366` 默认 **false**） |
| 周期心搏 | `BP:feature/video/viewmodel/VideoPlaybackViewModel.kt:8939-8944`：`while(true){ delay(30_000); reportPlaybackHeartbeatSnapshot(forceFlush=false, reason="interval") }` → **30 秒周期** |
| 起播首包 | `BP:VideoPlaybackViewModel.kt:8903-8936`：`shouldSendInitialPlaybackHeartbeat` 通过才发（要求 `isActivelyPlaying && !isInBackground && bvid/cid 有效`，`PlaybackAncillaryTaskPolicy.kt:36-46`） |
| 周期包闸门 | `BP:PlaybackAncillaryTaskPolicy.kt:24-34`：必须 `isPlaying && !isInBackground` |
| flush 触发点 | `BP:VideoPlaybackViewModel.kt:8401-8406` `flushPlaybackHeartbeatSnapshot(forceFlush=true)`；调用点：<br>· `:2297` `replace_player`<br>· `:3251` `switch_video`（`currentBvid != playbackRequest.bvid`）<br>· `:7510` `switch_page`（**分P切换**，非刷视频翻页；见 `:7485 pageIndex`）<br>· `BP:feature/video/state/VideoPlayerState.kt:1146` `dispose`<br>· `BP:feature/video/state/VideoPlayerState.kt:1206` `ON_PAUSE` |
| flush 门控 | `BP:PlaybackAncillaryTaskPolicy.kt:78-91`：`currentBvid/cid` 无效或 **`snapshot.playedTimeSec <= 0`** → 不上报 |
| 快照语义 | `BP:PlaybackAncillaryTaskPolicy.kt:59-76`：`playedTimeSec = 当前位置秒`；`realPlayedTimeSec = 累计实际播放秒`；`:8425-8439` `syncHeartbeatPlaybackTracking` 用 `elapsedRealtime` 累加 |
| start_ts 稳定性 | `BP:PlaybackAncillaryTaskPolicy.kt:48-57` + 测试 `:117`「heartbeat session start timestamp should stay stable once assigned」 |
| 本地进度 | `BP:core-player/.../PlaybackProgressManager.kt:69` `savePosition()` → SharedPreferences `video_progress`（**只存位置，不存观看记录列表**） |
| 本地观看/播放历史列表 | `BP:core/store/PlayHistoryStore.kt:37-93` → 仅"听视频/音乐"用途（消费方 `BP:feature/audio/screen/PlayHistorySheet.kt:42`、`BP:feature/video/screen/AudioModeMusicPlayer.kt:165`） |
| 专栏历史 | `BP:HistoryRepository.kt:189-216` `reportArticleView()` → `api.reportHistory` = `POST x/v2/history/report`（`ApiClient.kt:311-315`）→ **BB 的 history 接口在 BP 里只服务专栏** |
| 另一处实现 | `BP:core-player/.../SharedPlaybackSession.kt:236-242` `reportProgress()`（调 `HistoryRepository.reportPlayback`），但调用点仅 `BP:app-tv/.../TvPlayerScreen.kt:100`（TV 端每 60 tick）→ 手机主链路不走它 |

**刷视频（竖屏 pager）翻页是否上报：是（有位置 > 0 的前提下）**【事实】
```
BP:PortraitVideoPager → 页面切换 → BP:VideoDetailScreenStateHolder.kt:2913-2917 / 3050-3054  viewModel.loadVideo(...)
BP:VideoPlaybackViewModel.kt:3250-3254   if (currentBvid.isNotEmpty() && currentBvid != playbackRequest.bvid) {
                                             flushPlaybackHeartbeatSnapshot(reason = "switch_video")   // :3251
                                             recordCreatorWatchProgressSnapshot()                       // :3252 → 本地画像
                                             saveCurrentPosition()                                       // :3253
                                         }
BP:PlaybackAncillaryTaskPolicy.kt:84     playedTimeSec <= 0 → 不上报（位置的 0 秒划过 = 无信号）
```

#### 两侧差异（A1）——【事实】

| 维度 | BB | BP |
|---|---|---|
| 心搏端点 | `/x/report/heartbeat/mobile`（**App/mobile**） | `x/click-interface/web/heartbeat`（**Web**） |
| 历史端点 | `/x/v2/history/report`（**视频走这条**） | `x/v2/history/report`（**只给专栏用**） |
| 签名 | App 协议 `postSignedRaw` + `BiliRestProfile.APP` | Web 表单 + Cookie + csrf |
| 上报节奏 | **无周期心搏**（仅 start/end 两个包） | **30s 周期心搏** + start 包 + 5 类 flush |
| 触发点 | 首帧 / 暂停 / Ended / 切页·退出（`finalizeSession`） | 首帧 / 每 30s / 暂停(ON_PAUSE) / dispose / 换播放器 / 换视频 / 分P切换 |
| 参数丰富度 | 高：`session`、`polaris_action_id`、`quality`、`video_duration`、`network_type`、`from`、`from_spmid`、`play_status`、`user_status`、`auto_play`、`play_mode`、`is_auto_qn`、`track_id`、`report_flow_data`、`oaid`、`extra` | 低：无 `session`、无 `polaris_action_id`、无 `quality`、无 `video_duration`、无 `network_type`、无 `from/from_spmid` 实际值、无 `play_status/user_status/auto_play/play_mode`、无 `track_id/report_flow_data` |
| "看完"表达 | `progress = -1`（显式） | 无；只有 `played_time`/`real_played_time` |
| 本地历史 | **有**（Room `playback_history.db`，被 `resolveStartMs` 用于续播，`BB:StreamPlaybackSessionImpl.kt:724-743`） | **无视频观看历史**；只有位置缓存 `video_progress` 与音频 `PlayHistoryStore` |
| 上报闸门 | 设置项 `reportPlayback`（默认 **true**）+ 会话级 `reportEnabled` | 隐私模式（默认 **false** → 默认上报） |
| 无首帧行为 | 完全无信号（`PlaybackReporter.kt:166`） | 同效（`playedTimeSec<=0` 不上报）——机制不同、结果同 |
| 死代码 | `PlaybackReporter.finishSession()` 未接线 | 未发现同类死代码 |

---

### A2 完播 / 退出 / 跳过信号

**BB**
- 【事实】**没有**任何独立的"完播率 / 看到第几秒 / 快速划过"事件类型。可被服务端读出的：
  - `/x/v2/history/report` 的 `progress`（秒）与 `progress=-1`（看完）——`BB:RemotePlaybackReporter.kt:156`、`BB:PlaybackReportSession.kt:68-74`；
  - heartbeat 的 `last_play_progress_time` / `max_play_progress_time` / `played_time` / `actual_played_time` / `paused_time`——`BB:RemotePlaybackReporter.kt:128-133`。
- 【事实】刷视频里"快速划过（没看完就翻页）"**会**在上述 `progress`/`played_time` 上留下"只看了 N 秒 / duration 是 M 秒"的痕迹（因为翻页走 `finalizeSession` → `reportPlaybackHistory(complete = isComplete(...))`，`BB:PlaybackReporter.kt:164-182`）。
- 【事实】但若**一帧都没出**就翻页 → `playbackHistoryStarted == false` → `finalizeSession` 直接 return（`BB:PlaybackReporter.kt:166`）→ **零信号**。
- 【事实】更早的"在队列里被跳过、但从未 openVideo"的条目 → **零信号**（队列条目只有被 `openSwipeTarget` 打开才产生会话，`BB:VideoViewModel.kt:907-926`）。
- 【推断】因此"快速划过"的粒度 = 播放器会话粒度，不是页面曝光粒度；BB **没有**独立的曝光/跳过上报。

**BP**
- 【事实】同样**没有**独立完播/跳过事件；只有 `played_time` / `real_played_time`（`BP:PlaybackHeartbeatPolicy.kt:33-35`）。
- 【事实】`playedTimeSec <= 0` 的 flush 被 `shouldFlushPlaybackHeartbeatSnapshot` 拒绝（`BP:PlaybackAncillaryTaskPolicy.kt:84`）→ 位置 0 的极快划过无信号。
- 【事实】BP 有一条 **BB 完全没有**的第三方行为遥测：Firebase Analytics
  - `BP:core/util/AnalyticsHelper.kt:65` `object AnalyticsHelper`；`:58-63` 注释列出 `screen_view / video_play / video_complete / search / like / share / favorite / follow / app_open / login`；
  - `:315-336` `logVideoPlay()` → `:323 logAnalyticsEvent("video_play")`；调用点 `BP:feature/video/viewmodel/VideoPlaybackViewModel.kt:3615`；
  - `:372-375` `logVideoComplete()` → `"video_complete"`；【事实】main 源码里**未见调用点**（只有定义与注释）。
- 【待真机/待抓包】Firebase 上报的具体字段与是否含 bvid（`:16-25` 有 `SENSITIVE_ANALYTICS_PARAM_KEYS` 与 `maybeLogAnalyticsParam` 过滤，暗示会剔除 video_id/title/up_name）需抓包确认。

**差异**：BB 无第三方遥测、无独立跳过事件；BP 有 Firebase 行为遥测（非官方 B 站）+ 30s 周期心搏。两侧都靠"history/heartbeat 的进度数值"隐式表达跳过，都**没有**显式的"skip/快速划过"事件。

---

### A3 点赞 / 投币 / 收藏 / 关注

#### BB（`BB:core/video/src/main/java/com/naaammme/bbspace/core/video/VideoActionRepository.kt`）

| 动作 | 方法 | 行号 | 端点 |
|---|---|---|---|
| 点赞 | `setLike(aid, like)` | `:30-39` | `LIKE_ENDPOINT = "/x/v2/view/like"`（`:111`）；参数 `aid`,`like`（**`like=0` 是点赞、`like=1` 是取消**，注释 `:33-35` 解释了这个反直觉语义） |
| 投币 | `addCoin(aid, multiply, selectLike)` | `:44-50` | `COIN_ENDPOINT = "/x/v2/view/coin/add"`（`:112`）；`aid`,`multiply`,`select_like` |
| 一键三连 | `triple(aid, bvid)` | `:55-79` | `TRIPLE_ENDPOINT = "/x/web-interface/archive/like/triple"`（`:113`）**单请求**，Web Cookie+csrf（`:101-108`），form：`aid,eab_x=2,ramval=0,source=web_normal,ga=1,csrf,spmid=333.788.0.0,statistics` |
| 收藏 | `dealFav(rid, type, addIds, delIds)` | `BB:core/favorite/.../FavoriteRepository.kt:154-171` | `FAV_BATCH_DEAL_ENDPOINT = "/x/v3/fav/resource/batch-deal"`（`:397`）；form：`resources="$rid:$type"`,`add_media_ids`,`del_media_ids`,`csrf`；`type` 2=UGC/24=PGC（`:152`） |
| 关注 | **不走 REST** | `BB:core/space/.../SpaceRepository.kt:41-63` | gRPC `bilibili.relation.interface.v1.RelationInterface/ModifyRelation`（`:362`）；`ACT_ADD_FOLLOWING`/`ACT_DEL_BLACK`（`:58,62`） |
| 关注调用点 | | `BB:feature/space/.../SpaceViewModel.kt:238`(关注) `:259`(拉黑) | **只有 UP 主空间页** |

**刷视频模式里的接线与真实性**【事实】
```
BB:feature/video/.../player/PortraitSwipePager.kt:673-718  AnimatedVisibility { SwipeSessionActionRail(...) }
   :682  onToggleLike    = viewModel::toggleLike
   :690  onLongPressLike = viewModel::triple            // SW-20 点赞长按=一键三连
   :702-710 onOpenFavorite = if (viewModel.openFavSheet()) showFavSheet = true
   （注：互动栏组件本体在 BB:feature/video/.../player/VideoPlayerPane.kt:1197-1300，收藏按钮 KDoc :1283-1293）

BB:feature/video/.../VideoViewModel.kt
   toggleLike      :1249-1269  → :1257 runCatchingCancellable { actionRepository.setLike(aid, target) }   ✔ 真接口
   submitCoin      :1272-1297  → :1279 actionRepository.addCoin(aid, multiply, withLike)                  ✔ 真接口
   triple          :1302-1316  → :1304 actionRepository.triple(aid, videoState.value.ids.bvid)            ✔ 真接口
                                （:1307-1313 失败降级 tripleFallback(:1548-1593)，逐项 like/coin/fav）
   submitFav       :1384-1433  → :1403 favoriteRepository.dealFav(rid, type, addIds, delIds)              ✔ 真接口
```
- 【事实】所有本地计数/态更新都发生在 **`onSuccess` 回调内**（乐观更新不是"先改本地再发请求"）：`toggleLike` `:1258-1265`、`submitCoin` `:1280-1294`、`submitFav` `:1405-1425`；失败走 `emitActionError`。
- 【事实】**刷视频模式没有"关注"按钮**：`BB:feature/video/.../player/VideoPlayerPane.kt:1355-1356` 明确记录「仓内关系 gRPC 只有 `ModifyRelation`（写），没有查询接口，`VideoUserState` 也不含关注位 → 不做假按钮」。
- 【事实】BB 的点赞/投币走 **App 协议**（`postApp` → `postSigned` + `BiliRestProfile.APP`，`:87-99`），三连与收藏走 **Web 协议**（Cookie+csrf）。

#### BP（`BP:app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt`）

| 动作 | 方法/行号 | 端点（ApiClient.kt） | 参数 |
|---|---|---|---|
| 点赞 | `likeVideo` `:731-753` | `POST x/web-interface/archive/like` `:1374-1380` | `aid`,`like`（**1=点赞/2=取消**，`:739`），`csrf` |
| 投币 | `coinVideo` `:836-860` | `POST x/web-interface/coin/add` `:1399-1406` | `aid`,`multiply`,`select_like`,`csrf`；错误码分支 `34004/34005/-104`（`:849-853`） |
| 视频级点踩 | `dislikeVideo` `:779-812` | `POST https://app.bilibili.com/x/v2/view/dislike` `:1389-1396` | `aid`,`dislike`（**0=踩/1=取消**，`:790`），`csrf`,`access_key`；幂等码 `65007/65005`（`:800`）；`-101` 提示需 TV 鉴权（`:802-804`） |
| 收藏 | `favoriteVideo` `:256` | `POST x/v3/fav/resource/deal` `:1348` | （`FavoriteFolderIdPolicy.kt:7` 说明需 `media_id`） |
| 一键三连 | `tripleAction` `:893-922` | **3 次串行**：`:901 likeVideo(true)` → `:905 coinVideo(2,true)` → `:910 favoriteVideo(true)` | 非单请求 |
| 关注/取关 | `followUser` `:222-254` | `POST x/relation/modify` `:1330-1336` | `fid`,`act`（`val act = if (follow) 1 else 2`，`:232`），`csrf` |
| 拉黑/解除 | `BlockedUpRepository.modifyBilibiliBlockedList` `:450-479` | 同上 `x/relation/modify` | `act = 5/6`（`:461`），`re_src`（`:68-73`） |

**刷视频（竖屏 pager）里的接线与真实性**【事实】
```
BP:feature/video/ui/pager/PortraitVideoPager.kt:3211-3507  PortraitFullscreenOverlay(...)
   :3277-3299  onLikeClick        → onToggleLike(activeAid, bvid, isLiked){...}
   :3301-3318  onLikeLongClick    → onTripleAction(activeAid, bvid, ...)
   :3319-3323  onCoinClick        → onOpenCoinDialog()
   :3324-3356  onFavoriteClick    → viewModel.toggleFavorite() / viewModel.showFavoriteFolderDialog(activeAid)
   :3352-3356  onFavoriteLongClick→ viewModel.showFavoriteFolderDialog(activeAid)
   :3256-3258  onFollowClick      → onToggleFollow(authorMid, isFollowing)        // BB 缺这一项
   :3259-3264  onDetailClick → showDetailSheet（更多面板走 :3501-3503 打开详情 sheet）

BP:feature/video/ui/overlay/PortraitInteractionBar.kt:76-92  参数表：onLikeClick / onLikeLongClick / onCoinClick /
   onFavoriteClick / onFavoriteLongClick / onCommentClick / onShareClick
   （:73 注释「操作：点赞（长按三连）、投币、评论、收藏、分享」）

BP:feature/video/viewmodel/VideoPlaybackViewModel.kt
   :1349  private val interactionUseCase = VideoInteractionUseCase()
   :4371/:4394 toggleFollow  → interactionUseCase.toggleFollow(...)
   :4419-4424  toggleFavorite→ interactionUseCase.toggleFavorite(...)
   :4463-4480  toggleLike    → interactionUseCase.toggleLike(aid, liked, bvid)
   :6918-6922  doCoin        → interactionUseCase.doCoin(..., currentBvid)
   :6939-6961  doTripleAction→ interactionUseCase.doTripleAction(aid)
BP:feature/video/usecase/VideoInteractionUseCase.kt:40(like) :58(dislike) :77(fav) :120(follow) :138(coin) :151(triple)
   → 全部落到 ActionRepository（上面那张表）→ ✔ 真接口
```

#### 差异小结（A3）

| 动作 | BB 端点 | BP 端点 | 差异 |
|---|---|---|---|
| 点赞 | `/x/v2/view/like`（App，like=0 为赞） | `x/web-interface/archive/like`（Web，like=1 为赞） | **协议不同**；语义常量相反，任一侧照抄都会翻车 |
| 投币 | `/x/v2/view/coin/add`（App） | `x/web-interface/coin/add`（Web） | 同上 |
| 收藏 | `/x/v3/fav/resource/batch-deal`（**批量**） | `x/v3/fav/resource/deal`（单个） | BB 支持一次提交多个收藏夹增删 |
| 三连 | `/x/web-interface/archive/like/triple`（**1 请求**） | 3 次串行 like+coin+fav | BB 原子性更好；BP 单点失败不阻断（`TripleResult` 三布尔） |
| 视频级点踩 | **无** | `/x/v2/view/dislike`（App） | **BB 缺** |
| 关注 | gRPC `ModifyRelation`，仅 UP 空间页 | `x/relation/modify` act=1/2，**已接入竖屏互动栏** | **BB 刷视频缺关注入口** |
| 拉黑 | gRPC `ModifyRelation`（无本地名单） | `x/relation/modify` act=5/6 + 本地 Room 名单 + 同步状态回执 | **BB 缺"本地屏蔽名单 ↔ 官方拉黑"通道** |

【待真机/待抓包】这些接口的返回码是否被服务端用于推荐调整——**不判**。

---

### A4 「不感兴趣」/ 负反馈

#### BB —— **真回写，且首页与刷视频共用同一接口**【事实】

```
BB:core/feed/src/main/java/com/naaammme/bbspace/core/feed/FeedDislikeRepository.kt
   :27-48  dislike(context, reason)
      :31  profile = context.restProfile()               // HD 或 APP（:86-88）
      :35  put("action_id", pageActionTracker.currentActionId())
      :36  putReason(reason)                             // DISLIKE→reason_id；FEEDBACK→feedback_id（:79-84）
      :37  reason.extra → put("extra", ...)
      :40  GET "${BiliConstants.BASE_URL_APP}/x/feed/dislike"     // 常量 :99
      :65-77 putBaseContext: id,goto,is_light_panel=false,from_spmid,spmid,
                             from_module?,track_id?,report_data?,mid?,rid?,tag_id?
   :50-63  cancelDislike(context) → "/x/feed/dislike/cancel"      // 常量 :100
```
调用点（两条，**同一个 Repository**）：
- 首页：`BB:feature/home/.../HomeViewModel.kt:289-314` `submitDislike()`（`:296 feedDislikeRepo.dislike`）；撤回 `:316-339`（`:323 cancelDislike`）；UI `BB:feature/home/.../HomeScreen.kt:283`、`BB:feature/home/video/HomeVideoPage.kt:864,1008`。
- **刷视频**：`BB:feature/video/.../VideoViewModel.kt:999-1030` `submitSwipeDislike()`（**`:1013 feedDislikeRepository.dislike(context, reason)`**）；撤回 `:1033-1049`（**`:1037 cancelDislike`**）。
  - 入口：`BB:feature/video/.../player/PortraitSwipePager.kt:790-812`（More 面板）；`:802 canDislike = currentEntry?.dislikeContext != null`；`:858 "不感兴趣"` / `:861 "撤回不感兴趣"`。
  - 理由来源：条目自带的 `threePointV2.reasons`（`BB:core/swipe/.../PortraitSwipeQueue.kt:166-168`），拿不到时兜底 `DEFAULT_DISLIKE_REASON_ID = 1`（`BB:VideoViewModel.kt:1003-1010,1617`）。
  - 本地也摘队列：`:1015 swipeSession.removeQueueEntry(...)` → `BB:core/swipe/.../SwipeDislikeRemovalPolicy.kt:17`（纯队列策略）。
  - **只有 feed 源带 `dislikeContext`**：`BB:core/swipe/.../PortraitSwipeQueue.kt:164`（feed 源有）；`:145` 注释与 `:136-147` 代码显示 **story 源无该通道**（story 条目来源 `BB:core/feed/.../StoryFeedRepository.kt:34,65` = `x/v2/feed/index/story`）→ story 条目在刷视频里**没有**"不感兴趣"入口。
- 【事实】BB **没有**"本地屏蔽 UP 名单"这类对等物（全仓 grep `BlockedUp` 仅命中 Gaia 的应用黑名单常量）；拉黑只能去 UP 空间页走 gRPC（`BB:SpaceRepository.kt:61-63`）。

#### BP —— **两条独立通道：首页走服务端，详情/竖屏走本地；另有官方拉黑同步**

```
【服务端回写】只发生在首页推荐卡片
BP:feature/home/HomeViewModel.kt:1036-1097  submitNotInterested(...)
   :1043  resolveHomeNotInterestedAction(...)                                  // 本地动作决议
   :1044  recordTodayWatchNegativeFeedback(...)                                // 写本地 TodayWatchFeedbackStore
   :1045-1052  blockCreator → blockedUpRepository.blockUp(...)                 // 本地 Room 名单
   :1067-1076  if (action.shouldSyncCreatorToBilibiliBlockedList)
                  → blockedUpRepository.blockUpWithBilibiliSync(...)            // ★ 服务端 x/relation/modify act=5
   :1078  val metadata = video.recommendationFeedback
   :1079  val supportsServerSync = metadata?.supportsServerSync == true && reason.id != null
   :1080-1091  if (supportsServerSync) ActionRepository.submitRecommendationFeedback(metadata, reason)   // ★ 服务端 /x/feed/dislike
               else _feedbackEvents.send(reason.toast...)                       // 纯本地提示

BP:app/.../data/repository/ActionRepository.kt
   :40-54   buildRecommendationFeedbackRequest(...): metadata.supportsServerSync && goto/param 非空 && reason.id>0 才构造
   :56-75   buildRecommendationFeedbackParams(...): goto,id,reason_id|feedback_id,build=1,mobi_app=android,
                                                  access_key,appkey=TV_APP_KEY,ts        (:61-63 断言二者只能有一个)
   :115-135 submitRecommendationFeedback(...)  :130 api.submitMobileFeedDislike(AppSignUtils.signForTvLogin(params))
BP:core-data/.../ApiClient.kt:535-538  @GET("https://app.bilibili.com/x/feed/dislike") submitMobileFeedDislike(@QueryMap)
BP:data/model/response/ListModels.kt:216,222-226,674,699  RecommendationFeedbackMetadata{goto,param,supportsServerSync}
                                                          supportsServerSync = goto.isNotBlank() && param.isNotBlank()

【纯本地过滤（零网络）】
BP:feature/home/HomeNotInterestedPolicy.kt:33-52   resolveHomeNotInterestedAction（纯函数）
                                       :77-119  理由表（远端 reasons 优先，否则内建 5 条）
                                       :121-157 关键词抽取
                                       :159-187 filterHomeVideosByNotInterestedFeedback  ← 本地列表过滤（纯函数）
                                       :193-204 matchesDislikedPartition / :206-219 shouldFilterByDislikedKeywords
BP:feature/home/HomeViewModel.kt:1123-1145 recordTodayWatchNegativeFeedback（只写本地 store）
                              :1147-1155 syncTodayWatchFeedbackFromStore
                              :1157-1168 filterHomeFeedbackVideos
BP:feature/home/components/HomeNotInterestedReasonSheet.kt:32-84  Sheet（远端 DISLIKE/FEEDBACK 分组展示）
BP:feature/plugin/BiliPaiFeedFilterPlugin.kt:55-92,437-487  纯规则过滤 shouldShowItem（白名单/时长/播放量/点赞率/
                              标题正则/本地屏蔽 mid/分区正则）——【事实】**零网络**（无任何 api/retrofit 引用）
BP:feature/plugin/HomeFeedAnonymizerPlugin.kt:58-85  只在 Web 首页推荐请求里隐藏 Cookie（HomeFeedAnonymizerRuntime）
                              :53-54 目标 URL = api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd
                              —— 这是**反回流**（让推荐流接近未登录公共热门），不是回写

【详情页/竖屏/相关视频的"不感兴趣" = 本地-only】
BP:feature/video/viewmodel/VideoPlaybackViewModel.kt:4506-4534 markVideoNotInterested()
      :4517-4525  只 TodayWatchFeedbackStore.getSnapshot + saveSnapshot   ← 【事实】**没有任何网络调用**
      :4530-4532  toast("已减少此类推荐")
      消费点：BP:feature/video/screen/VideoDetailScreenStateHolder.kt:3461 / :3736、BP:feature/video/screen/
              TabletCinemaLayout.kt:605、BP:feature/video/ui/overlay/VideoPlayerOverlay.kt:1485（onNotInterested = onDislike）
BP:feature/video/ui/components/RelatedVideoItem.kt:531-553 onReasonSelected
      :537-551  非 CREATOR 理由 → recordRelatedVideoFeedback(...)（:637-663）→ 只写 TodayWatchFeedbackStore
      :532-535  CREATOR 理由 → 走弹窗；:573-596 confirmButton → blockedUpRepository.blockUpWithBilibiliSync(:580)  ★ 服务端
BP:feature/video/ui/components/RelatedVideoActionPolicy.kt:16-70  相关视频 sheet 的 4 个 FEEDBACK + 3 个 DISLIKE 理由
                                                      :72-77 shouldRemoveRelatedVideoAfterFeedback（本地隐藏）
                                                      :88-96 filterRelatedVideosByHiddenBvids（本地过滤）
```

**明确判定（这是本报告最关键的区分）**【事实】

| 入口 | BB | BP |
|---|---|---|
| 首页推荐卡片"不感兴趣" | **服务端** `GET /x/feed/dislike`（`HomeViewModel.kt:296`） | **服务端** `GET https://app.bilibili.com/x/feed/dislike`（`HomeViewModel.kt:1082`），但**有门控**：`metadata.supportsServerSync` 为 false 时纯本地（`:1092-1094`） |
| **刷视频/竖屏"不感兴趣"** | **服务端**（同一个 `/x/feed/dislike`，`VideoViewModel.kt:1013`） | **竖屏互动栏没有这个入口**（`PortraitInteractionBar.kt:76-92` 参数表无该项）；详情页/相关视频卡上的"不感兴趣"= **本地-only**（`VideoPlaybackViewModel.kt:4506-4534`） |
| 撤回 | **服务端** `/x/feed/dislike/cancel`（`VideoViewModel.kt:1037`） | **无撤回接口** |
| 拉黑 UP → 官方 | **无**（只能去空间页手点 gRPC） | **有**：`x/relation/modify` act=5/6（`BlockedUpRepository.kt:461`），带 `re_src` 与同步状态回执 |
| 本地名单/过滤 | 无本地 UP 黑名单 | 有（Room `blocked_up`）+ `HomeNotInterestedPolicy`/`BiliPaiFeedFilterPlugin` 纯本地过滤 |
| 本地负反馈存储 | 无（`dislikedReasons` 只在 UI state 里，`HomeViewModel.kt:300-303`） | `TodayWatchFeedbackStore`（`core/store/TodayWatchFeedbackStore.kt:80-179`），跨进程保留 |

**一句话**：BB 的负反馈**只有一个出口但处处可达**（首页 + 刷视频共用 `/x/feed/dislike`）；
BP 的负反馈**有三个出口且行为不一致**（首页 → 服务端；详情/相关视频 → 本地；拉黑 → 服务端）。

---

### A5 兴趣选择（interest）

**BB**【事实】
```
BB:core/feed/.../InterestRepository.kt:27-49  fetchUinterest() → GET "${BASE_URL_APP}/x/v2/feed/uinterest"（常量 :109）
      :32  params = restParamBuilder.app(BiliRestProfile.APP, ts, accessToken) + {"need_all_label":"1"}
      :38-48 解析 labels / all_labels / uinterest_page_material / uinterest_distribution_material
BB:core/feed/.../FeedRepository.kt:72-92  fetchFeedWithInterest(idx,pull,flush,interestId,interestResult,interestPosIds)
      :82-88  GET /x/v2/feed/index + interest_id / interest_result / interest_pos_ids        ★ 提交即回流
BB:core/feed/.../FeedRepository.kt:145 普通请求带 interest_id = "0"
      :106,:425-467 解析响应里的 interest_choose（genders/ages/items/sub_items）
BB:feature/home/.../HomeViewModel.kt:172-204  submitInterest(interestId, interestResult, interestPosIds)
      :175 appSettings.markInterestDone(); :183-188 feedRepo.fetchFeedWithInterest(idx=0, pull=true, flush, ...)
BB:feature/home/interest/InterestViewModel.kt:46  fetchUinterest 消费点
```

**BP**【事实】全仓 grep `uinterest|interest_id|interest_pos_ids|interest_result` = **0 命中** → **BP 没有兴趣选择通道**（既没有拉选项，也没有提交）。
【推断】BP 的首页 feed 参数里也没有 `interest_id`（对比 `BP:VideoRepository.kt:703-716` 与 `:794-819` 的移动端/合并模式参数表，只有 `idx/pull/column/flush/autoplay_card/ps/...`）。

**差异**：这条通道 **BB 有、BP 没有**（方向与多数条目相反）。

---

### A6 推荐系统显式反馈

**BP 是什么**【事实】
1. `BP:app/src/test/java/com/android/purebilibili/data/repository/ActionRepositoryRecommendationFeedbackTest.kt`
   - 三个用例：`:14-36`「dislike 请求只带 reason_id」（断言 `params["reason_id"]=="4"` 且 `!containsKey("feedback_id")`）；
     `:38-60`「feedback 请求只带 feedback_id」；`:62-74`「本地兜底不构造服务端请求」（`reason.id` 为空 → `buildRecommendationFeedbackRequest` 返回 null）。
   - 结论：这是 `ActionRepository.submitRecommendationFeedback` 的**纯函数参数组装**测试。
2. `BP:core/store/TodayWatchFeedbackStore.kt:80-179` — **本地 SharedPreferences**（`PREFS_NAME="today_watch_feedback"`，`:81-82`）
   - 存：`dislikedBvids(≤200)` / `dislikedCreatorMids(≤120)` / `dislikedKeywords(≤80)` / `recentDislikedVideos(≤24)`（`:41-44`、`:9-22`）
   - 读取/保存在 `:90-134` / `:136-169`，`clear` `:171-179`
   - 消费方：`BP:feature/home/HomeViewModel.kt:1127-1145`（写）、`:1147-1155`（读）、`:1157-1168`（本地过滤）、
     `BP:VideoPlaybackViewModel.kt:4518-4525`、`BP:feature/video/ui/components/RelatedVideoItem.kt:650-661`、
     `BP:feature/plugin/TodayWatchPlugin.kt:277-299`
   - **无任何网络调用**（全对象只有 SharedPreferences）。
3. `BP:core/store/TodayWatchProfileStore.kt:31-141` — **本地观看画像**（`PREFS_NAME="today_watch_profile"`，`:32-33`）
   - `:42-76 recordWatchProgress()` 按 UP 累计 `totalWatchSec`/`engagementEvents`/`lastWatchAtSec`，上限 160 UP（`:34`）
   - `:78-... getCreatorSignals()` 用 `ln()` 加权算分（`:87-90`）
   - 写入点 `BP:VideoPlaybackViewModel.kt:8948-8972 recordCreatorWatchProgressSnapshot()`（**只在 30s 心搏成功后才记**，`:8941-8943`）
   - 消费方 `BP:feature/home/HomeViewModel.kt:655`、`BP:feature/plugin/TodayWatchPlugin.kt:217,282,295,299`
   - `BP:feature/plugin/TodayWatchPlugin.kt:113` description = 「**本地分析观看历史**，生成可定制推荐队列」→ **本地**
4. `BP:core/plugin/RecommendationPluginApi.kt:26 RecommendationFeedbackSignals` / `:42 feedbackSignals` — 插件侧信号结构（供本地推荐候选排序）。

**是否把"今日观看/评价"回流给推荐系统？**
- `TodayWatchFeedbackStore`：**【事实】不回流**（本地 SharedPreferences + 本地列表过滤 + 本地推荐插件候选）。
- `TodayWatchProfileStore`：**【事实】不回流**（同上；只喂本地候选择序）。
- **真正回流的是** `ActionRepository.submitRecommendationFeedback`（`:115-135`），由 **首页卡片**负反馈触发（`HomeViewModel.kt:1080-1091`），端点是 `GET https://app.bilibili.com/x/feed/dislike`。它**不是**"今日观看"回流，而是**"单条负反馈"回流**。
- 【待真机/待抓包】服务端是否据此调整推荐 —— 不判。

**BB 有没有等价物？**【事实】**没有**。
```
grep 'TodayWatch|RecommendationFeedback|recommendationFeedback|submitRecommendationFeedback' 于 BB 全仓 → 0 命中
grep 'Firebase|firebase|google.gms' 于 BB 全仓 → 0 命中（BB 无第三方遥测 SDK）
BB 的对应能力 = 本地 playback_history.db（续播用）+ /x/feed/dislike（负反馈回流），没有"本地画像 → 本地推荐候选"这一层
```

---

### A7 反爬 / 会话标识

**BB**【事实】

| 标识 | 定义 | 注入位置 | 生命周期 |
|---|---|---|---|
| `action_id` | `BB:core/feed/.../PageActionTracker.kt:12` 初值与 `:20 refresh()` 都用 `BiliSessionId.polarisAction()` | **只**出现在 `/x/feed/dislike` 与 `/cancel`：`BB:FeedDislikeRepository.kt:35`、`:55` | 进程级；`refresh()` 触发点唯一：`BB:feature/home/.../HomeScreen.kt:130-132`（`LaunchedEffect(Unit)`，进入首页组合时调用一次）→ `BB:HomeViewModel.kt:99-101` |
| `polaris_action_id`（播放侧，**独立一份**） | `BB:PlaybackReporter.kt:36` `pagePolarisId = BiliSessionId.polarisAction()`（`bindOwner` 内） | heartbeat 的 `polaris_action_id`：`BB:RemotePlaybackReporter.kt:109` | `bindOwner(token)` 在每次 `openVideo` 时调用（`BB:StreamPlaybackSessionImpl.kt:350-352`，token=`openId.incrementAndGet()`）→ **每次开播一个新 action_id**；同会话短路返回（`:330-342`）时不刷新 |
| `session` | `BB:PlaybackReporter.kt:65` `BiliSessionId.view(deviceIdentity.buvid)` | heartbeat 的 `session`：`BB:RemotePlaybackReporter.kt:89` | 每个播放会话一个；算法 `BB:infra/crypto/.../BiliSessionId.kt:18-27` = `SHA-1(buvid + nowMs + rand)` |
| polarisAction 算法 | `BB:BiliSessionId.kt:29-32` = `UUID.randomUUID().hashCode()` 格式化为 **8 位大写 HEX** | — | 无持久化（进程重启即变） |

**在回流链路里的作用**【推断】：
- `action_id` / `polaris_action_id` 是**同一次"浏览/播放行为序列"的关联键**——把 N 条离散上报（禁 dislike、心搏、history）在服务端串成"同一会话内的连续动作"，让推荐系统能区分"同一 session 内的连续划过"与"跨 session 的独立行为"。
- `session` 为播放会话提供 buvid 派生的稳定键（同 buvid + 时间 + 随机），用于把同一会话的 start/end/history 三条包关联起来（`start_ts` 由 start 包响应回填：`BB:RemotePlaybackReporter.kt:189-195`）。
- 【事实】**没有**任何"设备指纹重算/伪造"逻辑参与这条链路；BB 的 buvid 来自既有登录/指纹流程。

**另一个离机通道（与喜好无关）**：`BB:infra/network-grpc/.../GaiaReporter.kt`
- `:95-130 reportIfNeeded()`：首装或距上次 ≥ `UPLOAD_INTERVAL = 7 天`（`:36`、`:132-136`）→ `fetchPublicKey()`（gRPC `bilibili.gaia.gw.Gaia/ExGetAxe`，`:38`）→ `upload()`（gRPC `.../ExClimbAppleTrees`，`:39`）
- 上报内容：`DeviceAppList{ source = "first_installation"|"first_open"（:117）, systemAppList = 空, userAppList = 用户已装应用包名（≤100，:86,:178-201）}`，RSA/AES 加密（`:158-169`）
- 触发：`BB:app/.../AppInitializer.kt:143-146`（`if (!appSettings.blockGaia.first()) gaiaReporter.reportIfNeeded()`）
- 用户可关：`BB:core/settings/.../AppSettings.kt:502-506`、`BB:feature/settings/.../PrivacySettingsScreen.kt:99-102`、`BB:feature/settings/.../SettingEntries.kt:45`
- 【事实】内容是**已安装应用包名列表 + 拉黑 root/Xposed/分身包**（`:41-84`）→ 反爬/风控，**不含任何观看内容或喜好**。

**BP**【事实】
- 【事实】**没有 polaris / action_id / session 概念**（全仓 grep `polaris` = 0 命中）。
- BP 的设备指纹通道是另一套：`BP:core-data/.../ApiClient.kt:1505-1506` `GET x/frontend/finger/spi`（取 buvid3/buvid4）+ `:1509-1513` `POST x/internal/gaia-gateway/ExClimbWuzhi`（buvid 激活，`payload` 参数）；调用点 `BP:app/.../data/repository/VideoRepository.kt:271,295-310`。
- 【事实】BP 的心搏参数里没有 `spmid` 之外的行为关联键（`BP:PlaybackHeartbeatPolicy.kt:48 spmid="333.788.0.0"`、`:49 from_spmid=""`）→ **没有会话关联键**。
- 【事实】BP 的 feed dislike 参数同样没有 `action_id`（`BP:ActionRepository.kt:64-74`）。
- 第三方（非官方 B 站）：Firebase Analytics（`BP:core/util/AnalyticsHelper.kt:65-880`），含 `video_play`（`:315-336`）/`video_complete`（`:372-375`）/屏幕浏览/搜索/点赞/分享/收藏/关注（`:58-63` 注释）；隐私模式可关（`:153`、`:203-212`）。

**差异**：BB **有**两套 polaris action_id + 一套 session，用于把离散上报串成行为序列；BP **完全没有**这类会话关联键，只有 spmid 常量。BP 有 Firebase 第三方遥测（BB 无）。

---

### A8 刷视频专属的本地记录——**是否离机**

**BB 逐项判定**【事实】

| 类 | 文件:行号 | 数据 | 存储 | **是否离机** |
|---|---|---|---|---|
| `SwipeQoeHub` | `BB:core/swipe/src/main/java/com/naaammme/bbspace/core/swipe/qoe/SwipeQoeHub.kt:17-81` | 事件类型 / 耗时 / 来源标签（**不含标题、UP、账号、aid**，`:13-14`） | **进程级内存环形缓冲 N=500**（`:20`、`:23`、`:45-46`），`:12` 注释：「满了丢最旧；**不落盘、不联网、无第三方 SDK**」 | **否**（明确零网络） |
| `SwipeQoeHub.recordCounter` | `:65-70` | 具名计数（playurl 命中/未命中等） | 内存 HashMap | **否** |
| `SwipeQoeHub.clear` | `:73-80` | 用户可在诊断页清空 | — | **否** |
| 诊断页 | `BB:feature/settings/.../qoe/SwipeDiagnosticsScreen.kt:46` | 展示 hub 快照 | — | **否**（`:46` 注释「**不联网、无第三方 SDK**」） |
| `SwipeSeenMemory` | `BB:core/swipe/.../queue/SwipeSeenMemory.kt:35-127` | `key`（条目 `legacyKey()`，形态为 bvid/aid）+ `seenAtMs`，**仅此两项**（`:30-33`、序列化 `:76-90` 只有 `k`/`t`） | 纯状态（LRU 500 `:95` / TTL 7 天 `:94`） | **否** |
| `SwipeSeenStore` | `BB:feature/video/.../swipesource/SwipeSeenStore.kt:20-87` | 上面那份 JSON | DataStore `swipe_seen`（`:20`、写入 `:66-68`） | **否**；隐私口径注释 `:29-30`「只存条目的 legacyKey 字符串 + 时间戳，**不含标题/封面/UP/账号信息**」 |
| `SwipeAutoAdvancePolicy` | `BB:core/swipe/.../SwipeAutoAdvancePolicy.kt`（`shouldAutoAdvanceToNextSwipePage`） | 4 个布尔入参的纯判据 | 无 | **否** |
| `SwipeSimilarity` | `BB:core/swipe/.../SwipeSimilarity.kt` | 标题规范化 + 关键词交集 + 时长差（移植自 BP `PortraitPagerSwitchPolicy.kt:407-497`）| 纯函数 | **否** |
| `SwipeDislikeRemovalPolicy` | `BB:core/swipe/.../SwipeDislikeRemovalPolicy.kt:17` | 队列摘除（锚点不可摘） | 纯函数 | **否** |
| `SwipeQoeMetrics` | `BB:core/swipe/.../qoe/SwipeQoeMetrics.kt:5` | 指标换算纯函数 | — | **否** |

**明确判定（A8）**【事实】：
> 这 7 个类**没有任何一条会离开设备**。它们全部只服务三件事：
> ① 本地队列去重/相似度（`SwipeSeenMemory`/`SwipeSeenStore`/`SwipeSimilarity`/`SwipeDislikeRemovalPolicy`）、
> ② 自动翻页判据（`SwipeAutoAdvancePolicy`）、
> ③ 本地诊断（`SwipeQoeHub`/`SwipeQoeMetrics`+诊断页）。
>
> 唯一会离机的是**它们触发的另外两条通道**——"不感兴趣"（A4，`/x/feed/dislike`）与播放会话上报（A1，heartbeat/history）。
> **它们本身不是回流通道，也不携带任何"喜好"数据。**
> 【推断】这直接决定了结论：BB 的"喜好回流"**不依赖**这些本地类，去掉它们回流依然成立；反过来说，它们的存在**不会**增加回流信息量。

**BP**【事实】
- 【事实】**没有** `SwipeSeen` / `Qoe` 类（全仓 grep `Qoe|QOE|SeenMemory|SeenStore` → 只命中 `autoAdvance`(Compose 测试 API) 与 `AnalyticsHelper` 的 `dedupe`）。
- BP 的对应能力全是**会话内内存态**，没有跨会话持久化：
  - `BP:feature/story/StoryFeedPolicy.kt:57-72` `seenBvids`（会话内 story 去重）
  - `BP:feature/video/ui/pager/PortraitPagerSwitchPolicy.kt:303-311` `deduplicated`（相似度去重，纯函数）
  - `BP:feature/video/ui/pager/PortraitVideoPager.kt:751,933-934,949-950` `lastAutoAdvancedBvid`（`remember`，进程内自动翻页幂等键）
- 【事实】BP 没有"刷视频 QoE 采集/诊断页"。
- 【推断】在 A8 这一项上 **BB 比 BP 更厚**（BB 是 BP 的增量，不是缺口）。

---

## ③ 对照表（B9）

> "是否回流服务端"= 客户端是否真的发出到 `bilibili.com` 域的请求（**不含**第三方 SDK）。
> "bbspace 是否缺"= 相对 BP 而言。

| # | 通道 | bbspace 实现（行号） | BiliPai 实现（行号） | 是否回流服务端 | 差异要点 | bbspace 是否缺 |
|---|---|---|---|---|---|---|
| 1 | 播放心搏（参数/节奏） | `BB:RemotePlaybackReporter.kt:26-45,80-143`（App 协议，`/x/report/heartbeat/mobile`，**无周期**） | `BP:PlaybackHeartbeatPolicy.kt:10-53` + `VideoPlaybackViewModel.kt:8903-8946`（Web 协议，`x/click-interface/web/heartbeat`，**30s 周期**） | ✅ 两侧都回流 | BB 参数**多 9 个**（session/polaris_action_id/quality/video_duration/network_type/from/play_status/user_status/auto_play…）；BP 多 30s 周期包 | 不缺；BB 少"周期包"，BP 少"会话关联与质量字段" |
| 2 | 观看历史落历史列表 | `BB:RemotePlaybackReporter.kt:47-58,145-169`（`/x/v2/history/report`，**视频走这条**） | `BP:HistoryRepository.kt:189-216`（`x/v2/history/report`，**只给专栏**） | ✅ 两侧都回流 | BB 用 history 端点写视频观看记录；BP 视频只靠 heartbeat | 不缺 |
| 3 | 本地观看历史 | `BB:LocalPlaybackHistoryRecorder.kt:14-42` + `core/history/.../PlaybackHistoryRepository.kt:23-25,49`（Room，1000 条/uid） | 仅位置缓存 `BP:core-player/.../PlaybackProgressManager.kt:69`；音频 `BP:core/store/PlayHistoryStore.kt:37-93` | ❌（本地） | BB 有可续播的本地观看库；BP 无 | 不缺（BB 更多） |
| 4 | 完播 / 跳过 / 快速划过 的**显式**事件 | 无（隐式：`progress=-1`，`BB:PlaybackReportSession.kt:68-74`） | 无（隐式：`played_time`，`BP:PlaybackHeartbeatPolicy.kt:33-35`；另有 Firebase `video_play`） | ⚠️ 无显式事件；Firebase 是第三方 | 两侧都靠数值隐式表达；**首帧未出/位置 0 划过 = 零信号**（`BB:PlaybackReporter.kt:166` / `BP:PlaybackAncillaryTaskPolicy.kt:84`） | 持平（BP 多一条第三方遥测） |
| 5 | 点赞 | `BB:VideoActionRepository.kt:30-39,111`（App `/x/v2/view/like`，like=0 为赞） | `BP:ActionRepository.kt:731-753` + `ApiClient.kt:1374-1380`（Web `x/web-interface/archive/like`，like=1 为赞） | ✅ 两侧都回流 | **协议与语义常量都相反** | 不缺 |
| 6 | 投币 | `BB:VideoActionRepository.kt:44-50,112`（App） | `BP:ActionRepository.kt:836-860` + `ApiClient.kt:1399-1406`（Web） | ✅ 两侧都回流 | 同上 | 不缺 |
| 7 | 收藏 | `BB:core/favorite/.../FavoriteRepository.kt:154-171,397`（batch-deal，**批量**） | `BP:ActionRepository.kt:256` + `ApiClient.kt:1348`（deal，单个） | ✅ 两侧都回流 | BB 支持一次多夹增删 | 不缺 |
| 8 | 一键三连 | `BB:VideoActionRepository.kt:55-79,113`（Web 单请求 triple） | `BP:ActionRepository.kt:893-922`（3 次串行） | ✅ 两侧都回流 | BB 原子性更好 | 不缺 |
| 9 | 视频级点踩 | **无** | `BP:ActionRepository.kt:779-812` + `ApiClient.kt:1389-1396`（`/x/v2/view/dislike`） | ✅（BP 独有） | BB 没有"点踩视频"这个动作 | ⚠️ **缺** |
| 10 | 关注 | gRPC `BB:core/space/.../SpaceRepository.kt:41-63,362`，**仅空间页**（`SpaceViewModel.kt:238`） | `BP:ActionRepository.kt:222-254` + `ApiClient.kt:1330-1336`，**已接入竖屏互动栏**（`PortraitVideoPager.kt:3256`） | ✅ 两侧都回流（协议不同） | **BB 刷视频没有关注入口**（`VideoPlayerPane.kt:1355-1356` 明确不做假按钮） | ⚠️ **刷视频缺入口** |
| 11 | 拉黑 UP → 官方 | gRPC `BB:SpaceRepository.kt:61-63`（无本地名单、无同步回执） | `BP:BlockedUpRepository.kt:311-332,450-479,461`（act=5/6 + `re_src` + 本地 Room 名单 + 状态回执） | ✅ 两侧都回流 | BB 无"本地名单 ↔ 官方拉黑"闭环 | ⚠️ **缺本地名单闭环** |
| 12 | **不感兴趣（首页）** | `BB:FeedDislikeRepository.kt:27-48,99`（`/x/feed/dislike`）+ `HomeViewModel.kt:289-314` | `BP:ActionRepository.kt:115-135` + `ApiClient.kt:535-538`（`https://app.bilibili.com/x/feed/dislike`）+ `HomeViewModel.kt:1078-1094` | ✅ 两侧都回流 | BB 参数更多（`action_id/report_data/track_id/spmid/from_spmid/mid/rid/tag_id`）；BP 用 TV appkey 签名且有 `supportsServerSync` 门控 | 不缺 |
| 13 | **不感兴趣（刷视频/竖屏）** | **服务端**：`BB:VideoViewModel.kt:999-1030`（`:1013`）+ `PortraitSwipePager.kt:790-812` | **竖屏无入口**；详情页/相关视频走**本地**：`BP:VideoPlaybackViewModel.kt:4506-4534`、`RelatedVideoItem.kt:531-553,637-663` | ✅ BB / ❌ BP（竖屏） | **方向相反：BB 这里通，BP 这里不通** | 不缺（BB 更强） |
| 14 | 不感兴趣 撤回 | `BB:FeedDislikeRepository.kt:50-63,100`（`/x/feed/dislike/cancel`），`HomeViewModel.kt:316-339` + `VideoViewModel.kt:1033-1049` | **无** | ✅ BB / ❌ BP | BB 有服务端撤回 | 不缺 |
| 15 | 本地负反馈过滤策略 | 无对等物（仅 UI state 里的 `dislikedReasons`，`HomeViewModel.kt:300-303`） | `BP:HomeNotInterestedPolicy.kt:159-187,193-219` + `HomeViewModel.kt:1157-1168` + `BP:BiliPaiFeedFilterPlugin.kt:437-487` | ❌（纯本地） | BP 有跨会话本地过滤，BB 没有 | 持平（性质不同，BB 靠服务端） |
| 16 | 兴趣选择（uinterest + interest_*） | `BB:InterestRepository.kt:27-49,109` + `FeedRepository.kt:72-92,85-87,145` + `HomeViewModel.kt:172-204` | **无** | ✅ BB / ❌ BP | **BB 独有** | 不缺（BB 更强） |
| 17 | 会话关联标识 | `BB:PageActionTracker.kt:10-21` + `PlaybackReporter.kt:36,65` + `BiliSessionId.kt:18-32` → `action_id`/`polaris_action_id`/`session` | **无**（grep `polaris` = 0） | ✅ BB / ❌ BP | BB 能把离散上报串成行为序列 | 不缺 |
| 18 | 设备指纹 / 风控上报 | `BB:GaiaReporter.kt:26-130`（gRPC 应用包名列表，7 天一次，可关）+ `BB:AppInitializer.kt:143-146` | `BP:ApiClient.kt:1505-1513`（buvid SPI + 激活） | ✅ 两侧都回流（**内容不同**） | BB 上报**已装应用列表**；BP 只做 buvid 激活 | 不缺 |
| 19 | 本地刷视频记录（去重/QoE/相似度） | `BB:SwipeSeenMemory.kt:35-127`、`SwipeSeenStore.kt:20-87`、`SwipeQoeHub.kt:17-81`、`SwipeAutoAdvancePolicy.kt`、`SwipeSimilarity.kt`、`SwipeDislikeRemovalPolicy.kt` | `BP:StoryFeedPolicy.kt:57-72`、`PortraitPagerSwitchPolicy.kt:303-311`、`PortraitVideoPager.kt:751,933-950`（全**会话内内存**） | ❌ 全部本地 | BB 有跨会话持久化 + QoE 诊断；BP 无 | 不缺（BB 更多） |
| 20 | 第三方行为遥测（非官方） | **无**（grep `Firebase` = 0） | `BP:core/util/AnalyticsHelper.kt:65-880`（Firebase：screen_view/video_play/...） | ⚠️ 出设备但**不是官方 B 站** | BP 多一条隐私面 | 不缺（对"回流官方"是中立/无关项） |
| 21 | 反回流（隐藏 Cookie） | **无** | `BP:HomeFeedAnonymizerPlugin.kt:58-85`（Web 首页推荐去 Cookie） | —（**方向相反**） | BP 主动降低回流 | 不缺 |

---

## ④ 结论

### 4.1 bbspace 回流现状：**通 8 条，不通 2 条**

**通的（刷视频模式全程可达）**【事实】
1. **播放心搏** → `POST /x/report/heartbeat/mobile`（起播首包 + 播完末包；`BB:RemotePlaybackReporter.kt:26-45`）。
   - 每次 `openVideo` 生成新 `polaris_action_id`（`BB:PlaybackReporter.kt:36` + `BB:StreamPlaybackSessionImpl.kt:350-352`）。
2. **观看历史** → `POST /x/v2/history/report`（暂停 / 播完 / 切页 / 退出；`BB:PlaybackReporter.kt:121-130,164-182`）。
   - **翻页确实上报**：`PortraitSwipePager.kt:237-245` → `VideoViewModel.kt:925` → `StreamPlaybackSessionImpl.kt:543-558`。
3. **本地观看历史** → Room `playback_history.db`（`BB:LocalPlaybackHistoryRecorder.kt:14-42`）——**同时做**本地 + 远端。
4. **点赞** → `POST /x/v2/view/like`（`BB:VideoActionRepository.kt:30-39`）。
5. **投币** → `POST /x/v2/view/coin/add`（`:44-50`）。
6. **收藏** → `POST /x/v3/fav/resource/batch-deal`（`BB:FavoriteRepository.kt:154-171`）。
7. **一键三连** → `POST /x/web-interface/archive/like/triple`（`BB:VideoActionRepository.kt:55-79`）。
8. **不感兴趣（首页 + 刷视频共用）** → `GET /x/feed/dislike` + `GET /x/feed/dislike/cancel`（`BB:FeedDislikeRepository.kt:27-63`；刷视频入口 `BB:VideoViewModel.kt:1013,1037`）。

**不通 / 有阙口的**【事实】
1. **刷视频里的"关注"** → 没有入口（`BB:VideoPlayerPane.kt:1355-1356` 明确不做假按钮）；关注只能进 UP 空间页（`BB:SpaceViewModel.kt:238`）。
2. **"本地屏蔽 UP 名单 ↔ 官方拉黑"闭环** → 不存在（BB 全仓无 `BlockedUp*`）；只有空间页一次性 gRPC 写入（`BB:SpaceRepository.kt:61-63`）。
3. **刷视频的 story 源条目** → 无 `dislikeContext`（`BB:PortraitSwipeQueue.kt:145`），所以 story 卡上**没有**"不感兴趣"。
4. **首帧未出就翻页 / 位置 0 越过** → **零信号**（`BB:PlaybackReporter.kt:166`）。
5. **视频级"点踩"** → BB 无此动作（BP 有 `x/v2/view/dislike`）。
6. **周期心搏** → BB 没有；长视频若中途被杀进程，只有"起播包"落地（无 30s 中间包）——【推断】这会削弱"看到哪一秒"的完整度；【待真机/待抓包】是否真的影响服务端采纳，不判。

**A8 的明确回答**：`SwipeSeenMemory` / `SwipeSeenStore` / `SwipeQoeHub` / `SwipeAutoAdvancePolicy` / `SwipeSimilarity` / `SwipeDislikeRemovalPolicy` **没有任何一条会离开设备**（依据见 ② A8 表；`SwipeQoeHub.kt:12` 与 `SwipeSeenStore.kt:29-30` 有作者自述的隐私口径）→ **"喜好是否回流"的答案不取决于这些类**，取决于 A1/A4 两条通道。

### 4.2 与 BiliPai 的差距（差距多为**双向**）

**BB 比 BP 强 / BP 缺的**
- 兴趣选择通道整条（`uinterest` + `interest_id/interest_result/interest_pos_ids`）——BP 完全没有。
- 会话关联标识（`action_id` / `polaris_action_id` / `session`）——BP 完全没有。
- 刷视频"不感兴趣"**真的回写服务端**，且有服务端撤回——BP 竖屏无此入口，详情页的"不感兴趣"是本地-only。
- 心搏参数丰富度（9 个字段）与视频观看历史端点使用（BP 的 `x/v2/history/report` 只服务专栏）。
- 本地观看历史库（可续播）；刷视频的跨会话去重 + QoE 诊断。
- 无第三方遥测 SDK（隐私面更小）。

**BP 比 BB 强 / BB 缺的**
- **30 秒周期心搏**（BB 无）——长视频中途被杀时的"观看时长完整性"更稳。
- **视频级点踩** `/x/v2/view/dislike`（BB 无）。
- **竖屏互动栏里的关注按钮**（BB 明确不做）。
- **本地屏蔽名单 ↔ 官方拉黑同步**（`x/relation/modify` act=5/6 + 本地 Room + 状态回执）——这实际上是一条 BB 完全缺失的**服务端负反馈通道**。
- 首页"不感兴趣"支持**远端 reasons 直传**（`BP:HomeNotInterestedPolicy.kt:77-119` 远端优先 + `supportsServerSync` 门控）；BB 只把卡上 `threePointV2.reasons` 里的第一条 DISLIKE 用掉（`BB:VideoViewModel.kt:1003-1010`），首页同理。
- 本地负反馈跨会话过滤（`HomeNotInterestedPolicy` + `BiliPaiFeedFilterPlugin`）。

**两侧都缺的**
- 显式的"完播 / 跳过 / 快速划过"事件；页面级曝光上报；负反馈的"已采纳"回执可观测性。
- 【待真机/待抓包】以上所有接口的返回码是否表示"已采纳"。

### 4.3 一句话回答原问题

> **bbspace 的刷视频喜好"确实回流了官方"，主要通过 3 条通道：① 播放心搏（`/x/report/heartbeat/mobile`，起播+播完两包，带 `polaris_action_id`/`session`）② 观看历史（`/x/v2/history/report`，暂停/完播/翻页/退出四处触发）③ 显式负反馈（`/x/feed/dislike`，首页与刷视频共用，可撤回）。**
> 加上点赞/投币/收藏/三连四条互动通道，共 8 条离机通道在刷视频模式里全程可达。
> **缺的是**：显式跳过/完播事件、周期心搏、竖屏关注入口、点踩、以及"本地屏蔽名单 ↔ 官方拉黑"闭环（这条 BP 有而 BB 没有）。
> **A8 那批本地类（SwipeSeen/Qoe/SwipeAutoAdvance/SwipeSimilarity）全部不离机，不构成回流通道。**
> 反过来，BB 在**兴趣选择**与**会话关联标识**上比 BP 完整得多——所以"BP 更完整"这个前提**不成立**，两边是互补的。

---

## ⑤ 可同步项清单（落点 + 静态验收 + 成本）

> 判定口径：**只在"缺的是回流通道"且"静态可验收"时才列**。
> 所有项都**只读分析产物**，不含实际改动；成本按"改 1~2 个文件 + 1 个纯函数测试"估。

### S1（推荐，成本低）：刷视频补"视频级点踩"以外的**负反馈闭环**——不建议直接抄 BP 的点踩
- **落点**：不建议同步。理由：BP 的 `x/v2/view/dislike`（`BP:ActionRepository.kt:779-812`）需要 **APP 鉴权**（`:802-804` 错误码 `-101` 提示需 TV 鉴权），BB 的 App 协议已具备（`BB:VideoActionRepository.kt:87-99`），技术上可加；但"点踩单支视频"与 BB 已有的"不感兴趣"功能高度重叠，属**产品语义**决定，不是通道缺口 → **列为不可同步项**（见 ⑤ 末）。

### S2（推荐，成本中）：**30 秒周期心搏**
- **为什么值得**：BB 目前只有起播包与末包（`BB:RemotePlaybackReporter.kt:26-45`），进程被杀/长时间停留时中间态完全丢失。
- **落点**：
  - `BB:core/playback/src/main/java/com/naaammme/bbspace/core/playback/PlaybackReporter.kt`：新增一个 `runtimeScope/内部 scope` 的 30s 循环，复用现有 `snapshotEdge` 与 `remoteReporter.endHeartbeat` 的参数构造（只需把 `startPacket=false` 的分支抽成 `reportHeartbeatSnapshot()` 供周期调用）；**不要**新增网络层代码，`RemotePlaybackReporter.buildHeartbeatParams(active, startPacket = false)` 已现成（`BB:RemotePlaybackReporter.kt:126-136`）。
  - 触发与取消：在 `PlaybackReporter.startSession`（`:39-76`）启动、在 `endHeartbeat`（`:193-201`）/`detachSession`（`:144-162`）取消；与 `heartbeatStarted`（`PlaybackReporter.kt:92-96`）对齐。
- **依赖**：无新依赖。参照实现 `BP:VideoPlaybackViewModel.kt:8939-8944`（`delay(30_000)` 循环）与闸门 `BP:PlaybackAncillaryTaskPolicy.kt:24-34`（前台 + 在播才发）。
- **静态验收**（守卫式，与 BB 现有 `VideoLogicGuardTest` 同款）：
  1. `PlaybackReporter.kt` 中 `HB_URL` 的调用点从 2 类变为 3 类，且周期分支与 `startPacket = false` 共用同一参数构造函数（单测断言参数 map 相等）；
  2. 断言 `endHeartbeat` 后循环 job 已取消（`job.isActive == false`）；
  3. 断言后台/暂停时**不**发周期包（对应 `BP:PlaybackAncillaryTaskPolicy.kt:24-34` 的等价纯函数抽到 `core/swipe` 或 `core/playback` 并加单测）。
- **成本**：0.5~1 人日（含 1 个纯函数测试 + 1 个守卫断言）。

### S3（可选，成本低）：刷视频互动栏补**关注**按钮
- **为什么值得**：BB 竖屏是唯一没有关注入口的播放场景（`BB:VideoPlayerPane.kt:1355-1356` 记录了原因是"缺读取接口"）。
- **落点**：
  - 读：BB 已有 `VideoUserState`（`BB:VideoPlayerPane.kt:1211` 注释提到 `userState.faved`），**需先确认**详情接口是否含 `relation.attribute`（关注位）→ 需更细读 `BB:core/model/.../VideoDetail.kt` 的 `userState` 字段与 `BB:core/video/.../VideoDetailRepository.kt` 的解析。
  - 写：复用 `BB:core/space/.../SpaceRepository.kt:57-59 modifyRelation(fid, isFollow)`（gRPC 已有，无需新增网络）；
  - UI：`BB:feature/video/.../player/VideoPlayerPane.kt` 的 `SwipeSessionActionRail` 参数表 + `PortraitSwipePager.kt:679-717` 接线。
- **依赖**：**先确认 `VideoUserState` 有 `following/relation` 位**；若没有，需要新增一次 `SpaceRepository` 的读请求（BB 该 Repo 现在只有写，见 `VideoPlayerPane.kt:1355-1356` 的原话）→ 那就是**中高成本**。
- **静态验收**：
  1. 守卫断言"互动栏关注按钮只在 `following` 真值源存在时渲染"（对齐 BB 既有口径：不做点了没反应的假按钮，`BB:VideoPlayerPane.kt:1215-1216`）；
  2. 断言点击走 `SpaceRepository.modifyRelation` 而非本地乐观更新；
  3. 断言未登录时不渲染。
- **成本**：若 `userState` 已含关注位 → 0.5 人日；否则 1.5~2 人日（新增读接口 + 状态源）。
- **风险**：gRPC 写 + 无读 → 可能出现"按钮态与服务端不同步"，需明确取舍。

### S4（推荐，成本低-中）：**本地屏蔽 UP 名单 + 官方拉黑同步**
- **为什么值得**：这是 BB **完全缺失的一条服务端负反馈通道**，也是"不感兴趣"的一个更强形式（BP `HomeViewModel.kt:1067-1076` 与 `RelatedVideoItem.kt:573-596` 都在用）。
- **落点**：
  - 新 Repo：`BB:core/space/.../SpaceRepository.kt` 已有 `modifyBlacklist(fid, isBlack)`（`:61-63`），**写就绪**；缺的是本地名单存储（可放 `BB:core/space/` 或 `core/history/` 同款 Room 模式，参照 `BB:core/history/.../PlaybackHistoryRepository.kt:53-73` 的 Module 写法）。
  - 接线：刷视频"更多"面板（`BB:feature/video/.../player/PortraitSwipePager.kt:790-812`）增加一行"屏蔽 UP 主"；列表过滤放到 `BB:core/swipe/.../PortraitSwipeQueue.kt`（条目已带 `ownerMid`，`PortraitSwipeQueue.kt:163` 显示 feed 源有 `up_id`）。
- **依赖**：无新依赖（gRPC 写已有；Room 模式仓内已有 3 处范例）。
- **静态验收**：
  1. 断言"屏蔽 UP"按钮只在 `entry.ownerMid > 0` 时出现（对齐 `BB:PortraitSwipeQueue.kt:163` 的 `takeIf { it > 0L }`）；
  2. 断言写入路径为"先本地 Room，再 gRPC 同步"，且 gRPC 失败**不回滚本地**（对齐 BP `BlockedUpRepository.kt:317-331` 的 `BlockedUpWriteResult` 语义：`localChanged=true` + `remoteStatus`）；
  3. 断言被屏蔽 mid 的条目在补货时被过滤（纯函数单测，参照 `BB:core/swipe/.../SwipeDislikeRemovalPolicyTest.kt` 的写法）；
  4. 守卫断言：`SpaceRepository.modifyBlacklist` 的调用点从 1 处（`SpaceViewModel.kt:259`）变 2 处且两处共用同一封装。
- **成本**：1~1.5 人日（1 个 Room 表 + 1 个 Repo + 1 个纯函数测试 + 1 个守卫）。

### S5（可选，成本低）：负反馈"更细的理由"透传
- **落点**：BB 已有完整 reasons 数据（`BB:PortraitSwipeQueue.kt:166-168` 已把 `threePointV2.reasons` 全量带进 `SwipeEntry`），但刷视频只取第一条 DISLIKE（`BB:VideoViewModel.kt:1003-1010`）。→ 让 More 面板列全部 reasons（对齐 `BP:HomeNotInterestedReasonSheet.kt:32-84` 的分组展示）。
- **静态验收**：断言面板渲染的是 `entry.dislikeReasons` 全量而非 `firstOrNull`；断言 `feedback_id` 分支可达（BB `FeedDislikeRepository.kt:79-84` 已支持 FEEDBACK，目前刷视频路径走不到）。
- **成本**：0.5 人日。

### 不可同步项 + 理由

| 项 | 不可同步的理由 |
|---|---|
| 视频级"点踩"（BP `x/v2/view/dislike`） | BB 已有"不感兴趣"覆盖同一用户意图；点踩需 APP 鉴权且服务端语义（`65007/65005/-101`，`BP:ActionRepository.kt:800-804`）与"不感兴趣"重叠 → **产品决策**，非通道缺口。且【待真机/待抓包】无法验证服务端是否区别对待两者。 |
| 把 `TodayWatchFeedbackStore` / `TodayWatchProfileStore` 搬到 BB | 这两个是**本地**机制（`BP:TodayWatchFeedbackStore.kt:80-179`、`BP:TodayWatchProfileStore.kt:31-141`），**对"回流官方"零贡献**。BB 若要做，应作为"本地推荐候选"功能立项，与本报告主题无关。 |
| Firebase Analytics | 第三方遥测，不是"回流官方 B 站"；BB 现有隐私口径（无第三方 SDK，`BB:SwipeQoeHub.kt:12`）明确排斥 → **不建议**。 |
| `HomeFeedAnonymizerPlugin`（去 Cookie） | 方向相反（**反回流**），且会改变首页推荐行为 → 不是"补通道"。 |
| 复用 BB 的 `PageActionTracker` 到 BP | 本报告只讨论"给 BB 补通道"；且 BP 侧无对应服务端消费证据（【待抓包】）。 |
| 兴趣选择通道的"反向同步"给 BP | 不在本任务范围（任务是查 BB 是否缺），且 BP 侧是否被服务端接受需【待真机/待抓包】。 |

---

## ⑥ 待抓包 / 待真机项

> 以下全部是**静态读码无法判定**的，任何"服务端是否真的采纳"的结论都必须由这些验证支撑。

| # | 待验事项 | 验证手段 | 预期可判定的问题 |
|---|---|---|---|
| V1 | `/x/report/heartbeat/mobile`（BB 起播包/末包）与 `x/click-interface/web/heartbeat`（BP 30s 包）的**返回码与 body** | 抓包（mitm，看 `code`/`message`/`data.ts`） | 是否表示采纳；BB 末包的 `played_time/actual_played_time` 是否被用于"播放量/完播"统计 |
| V2 | `/x/v2/history/report` 的返回码，以及**该视频是否出现在官方 App 的"观看历史"里** | 真机：BB 刷 3 支 → 打开官方 App 看历史列表 | ① 翻页上报是否真的落历史（这直接回答"刷视频喜好是否回流"的最强证据）② `progress=-1` 是否被识别为看完 |
| V3 | `/x/feed/dislike` 是否影响**首页推荐流**的下一次下发 | 真机 A/B：BB 对某 UP/分区点"不感兴趣"×N → 刷新首页看该 UP/分区是否减少 | 负反馈是否被采纳（**两侧同验**：BP 用 `x/feed/dislike`，BB 用同一路径） |
| V4 | `/x/feed/dislike` 是否影响**刷视频队列**（BB 的 `HomeFeedSwipeSource` → `fetchFeed(idx, pull=false, flush=0)` 是不是服务端已按负反馈调整过的流） | 真机：在刷视频里点"不感兴趣"→ 退出重进 → 看队列是否变化 | 关键：BB 的刷视频队列**只靠同一个 `feed/index`**（`BB:SwipeSources.kt:21-34`），若服务端不按 dislike 调整，则"刷视频里的负反馈"只影响本地队列（`BB:VideoViewModel.kt:1015`），**回流了但无效果** |
| V5 | `action_id` / `polaris_action_id` 是否被服务端用于**会话内行为序列**关联 | 抓包对比：同一次刷视频里 N 条上报的 action_id 是否相同/递增；再于服务端侧无法观测 → 只能看请求形态 | 【推断】目前只能证明"客户端发了"，无法证明"服务端用了" |
| V6 | BB 缺少周期心搏的实际后果 | 真机：刷一支 10 分钟视频，中途强杀进程 → 官方 App 历史里的进度停在几秒 | 若停在 0 或起播点，说明周期包缺失造成实际损失（→ S2 的价值量化） |
| V7 | 首帧未出就翻页是否**完全**无信号 | 抓包：极快连划 5 支 → 看有无任何 `heartbeat`/`history` 请求 | 验证 `BB:PlaybackReporter.kt:166` 的实际效果；也验证 BP `PlaybackAncillaryTaskPolicy.kt:84` |
| V8 | BP 的 `supportsServerSync` 在真实 `feed/index` 响应里的命中率 | 抓包 `x/v2/feed/index` 响应，看 `recommendation_feedback.goto/param` 是否落值（`BP:ListModels.kt:674-699`） | 决定 BP 首页负反馈"真回写"的比例——若普遍为 false，则 BP 的负反馈其实大多只在本地 |
| V9 | `x/relation/modify` act=5/6 是否真的把 UP 加进官方 App 的"黑名单" | 真机：BP 屏蔽某 UP → 打开官方 App 黑名单页 | 验证 BP 这条独有通道的真实性（→ S4 的价值） |
| V10 | 两侧的 `progress`/`played_time` 单位与语义是否被服务端按"秒"解释 | 抓包 + 官方 App 历史进度对比 | 单位错位是这类上报最常见的静默失败 |
| V11 | BB 的 `GaiaReporter` 应用列表上报是否被服务端接受、是否被"禁止 Gaia 上报"开关真正拦住 | 抓包：开关开/关各一次冷启动 | 确认隐私开关有效（`BB:AppInitializer.kt:143-146`） |
| V12 | Firebase `video_play` 实际字段（是否含可识别到 bvid/mid 的信息） | 抓包 Firebase endpoint | 判断 BP 的第三方遥测隐私面（`BP:AnalyticsHelper.kt:16-25` 有过滤，需实证） |
| V13 | 兴趣选择参数 `interest_result` / `interest_pos_ids` 的**格式**是否符合服务端预期 | 抓包：在 BB 首页提交一次兴趣选择（`BB:HomeViewModel.kt:183-188`）看返回 code 与首页是否变化 | BB 独有通道的有效性 |
| V14 | BB 刷视频 story 源条目（无 `dislikeContext`）是否有**别的**负反馈通道可用 | 抓包 + 读 `x/v2/feed/index/story` 响应是否含 `three_point_v2` | 决定 S5 是否需扩展 story 源 |

---

## 附：需更细读的项（本报告未下结论）

1. `BB:core/model/.../VideoDetail.kt` 的 `VideoUserState` 是否含**关注位**（`relation`/`attribute`）——决定 S3 的成本档位（`BB:VideoPlayerPane.kt:1355-1356` 的作者注释说"没有"，但注释写于何时、是否仍成立未核实）。
2. BB `x/v2/feed/index/story` 响应里是否有 `three_point_v2`（决定 story 源条目的负反馈通道，V14）。
3. BP 是否有"跨会话已推记忆"这一等价物（本报告只读了 `StoryFeedPolicy.kt:57-72` 的会话内 `seenBvids` 与 `PortraitVideoPager.kt:751` 的 `remember`，未穷尽搜索持久化层）。
4. BP 的 `HomeFeedAnonymizerRuntime` 除 Web 首页外是否还作用于 App 首页请求（`BP:core/network/policy/HomeFeedAnonymizerPolicy`，本报告只读了插件入口 `HomeFeedAnonymizerPlugin.kt:58-85`）。
5. BB `FeedRepository.buildParams`（`BB:FeedRepository.kt:110-173`）与 BP `fetchMobileFeed`/`fetchMergedMobileFeed` 参数表的**逐项差异**（本次只做了定性比较：BP 无 `login_event/open_event/interest_id/teenagers_mode/lessons_mode`）。
6. BB `PlaybackReporter.finishSession()` 是否本应被某条退出路径调用（现为死代码）——需确认不是"漏接线"（若是，则退出场景可能少一次上报）。
