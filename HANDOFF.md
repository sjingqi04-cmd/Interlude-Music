# Interlude Music 定制版 — 开发交接汇总

> 供后续 AI / 开发工具接手。本文基于 2026-11-11 的实际工作区状态（`git diff` 35 个文件，+1309 / -447）。
> 项目基于 Metrolist（Kotlin + Jetpack Compose + Material 3 + Media3/ExoPlayer）。

## 1. 交付物与构建

| 项 | 值 |
|---|---|
| 工作目录 | `D:\Interlude-Music` |
| APK | `D:\Interlude-Music\Interlude-Music-custom.apk`（29.36 MB） |
| 源码包 | `D:\Interlude-Music\Interlude-Music-source.zip`（21.84 MB，1143 文件） |
| applicationId | `com.interlude.music.debug`（`.debug` 后缀，勿改） |
| versionCode / versionName | **17 / 1.5.3**（用户明确要求抬版本以便覆盖安装，勿随意回退） |
| minSdk / targetSdk / compileSdk | 26 / 36 / 37 |
| 签名 | debug keystore，SHA-256 `79fd4d108b55893eb400a42f0b10b8426dfef5d847a5bfaa36197b2b789de47d` |
| **覆盖安装契约** | 签名 + applicationId + 版本号不变 → 不丢登录/数据。**动这些之前必须问用户** |

构建命令（Windows PowerShell）：
```powershell
$env:JAVA_HOME="D:\dev\jdk21"; $env:ANDROID_HOME="D:\Android\Sdk"; $env:ANDROID_SDK_ROOT="D:\Android\Sdk"
.\gradlew.bat :app:assembleFossDebug --console=plain --no-daemon
```
产物：`app/build/outputs/apk/foss/debug/app-foss-debug.apk`，再复制到根目录 `Interlude-Music-custom.apk`。
验证：`aapt2 dump badging`（版本）、`apksigner verify --print-certs`（签名）。

`app/build.gradle.kts` 的 **debug 变体刻意开了 R8**（`isMinifyEnabled=true` / `isShrinkResources=true` / `isDebuggable=false`），
原因：官方 release 流畅而 debug 卡顿，是 ART JIT + Compose debug check 所致。关掉会重现动画卡顿。
`AndroidManifest.xml` 的 application 加了 `android:hardwareAccelerated="true"` 和 `android:largeHeap="true"`。

## 2. 已实现功能清单（按文件）

### 睡眠定时（改动最多，注意遗留）
- `playback/SleepTimer.kt`：新增 `@Volatile activeFlag` + `onActiveChanged: (Boolean)->Unit` 回调。
  `start()/clear()/completeTimerAndPause()` 统一走 `setActive(true/false)`，`isActive` 直接返回 `activeFlag`。
  **为何用普通 volatile 字段**：之前用 `mutableStateOf` 跨 Service/UI snapshot 读到旧值，导致状态显示错乱。
- `playback/MusicService.kt`：新增 `sleepTimerActive = MutableStateFlow(false)`，构造 SleepTimer 时把回调接到它。
  另有 `setBufferDurationsMs(50_000, 120_000, 750, 2_000)`（max buffer 50s→120s，减少切歌空窗）。
  `autoSkipOnError` 在连续错误超限时改为**先延时重试一次**再停，缓解"播完自动停"。
  `updateNotification()` 自定义布局顺序调为 repeat→shuffle→like→radio→playlist。
- `ui/player/Player.kt` + `Queue.kt`：睡眠定时对话框
  - **最终 UI：只保留一个 Slider**（用户明确要求删掉数字行；此前加过 FilterChip 预设、`OutlinedTextField`，都已删除）。
  - `SleepTimerPresets` 仍定义在 `constants/PreferenceKeys.kt`（当前**未被引用**，可留可删）。
  - 状态显示：`dialogTimerOn` 本地 state + `LaunchedEffect(sleepTimerEnabled)` 同步；
    点「确定」→ `runCatching { start() }` 成功后置 true、弹 Toast；状态文字「定时进行中/定时未开启」，按钮高亮实心/暗禁用切换。
  - 两个对话框都**点确定后不自动关闭**（为了让用户当场看到状态变化）。
- `constants/PreferenceKeys.kt`：`AutoTranslateLyricsKey`、`SleepTimerPresets`。
- 字符串：`values/metrolist_strings.xml` 加了 `sleep_timer_off`、`sleep_timer_custom_minutes`(已无用)、
  `sleep_timer_running`、`sleep_timer_not_running` 等；中文版在 `values-zh-rCN/metrolist_strings.xml`。

### 歌词 / 翻译
- **新增** `api/FreeTranslationService.kt`：免 API key 翻译，MyMemory 优先 → Google gtx 兜底，
  `Semaphore(4)` 并发、`ConcurrentHashMap` 缓存、按行翻译，`zh-CN` 目标。
- `lyrics/LyricsTranslationHelper.kt`：`provider=="Free"` 分支；key 校验对 Free 放宽；
  `LanguageCodeToName` 加中文；默认 `provider="Free"`。翻译成功后有 `delay(3000)` 才归 Idle。
- `ui/component/ExperimentalLyrics.kt`、`OriginalLyrics.kt`：`aiProvider` 默认 `"Free"`、
  `translateLanguage` 默认 `"zh-CN"`、`AutoTranslateLyricsKey` 默认 true，
  自动翻译 `LaunchedEffect`（`autoTranslatedSongId` 去重）；**`useStreaming = aiProvider != "Free"`**（Free 关闭流式以提速）。
- `ui/menu/LyricsMenu.kt`：翻译项常显、新增「自动翻译歌词」开关。
- **`lyrics/LyricsHelper.kt`（性能优化重点）**：`getLyrics()` 从**串行试 provider** 改为
  `coroutineScope { enabledProviders.map { async { ... } } }` 并行竞速，按用户优先级取第一个成功结果；
  `MAX_LYRICS_FETCH_MS` 25s→15s，`PER_PROVIDER_TIMEOUT_MS` 8s→6s。解决了"打开歌词等空屏"的问题。

### 播放器 UI
- `ui/player/MiniPlayer.kt`：右侧「关注歌手/播放暂停」按钮最终改为 **分享** `MiniTransportShareButton`
  （`Intent.ACTION_SEND` 发 `https://music.youtube.com/watch?v=<id>`）。左侧带进度环的播放按钮保留。
- `ui/player/PlayerMenu.kt`：新增 `isFullScreen` / `onToggleFullScreen` 参数，NewActionGrid 里加「Fullscreen」项
  （仅当 `onToggleFullScreen != null` 时显示，即 Spotify + FullArt 两处入口）。
- `ui/player/SpotifyStylePlayerContent.kt`：顶栏全屏按钮；`AnimatedVisibility(!isFullScreen)` 包住传输+工具行；
  新增 `sleepTimerActive` 参数（图标随状态变主色）。
- `ui/player/FullArtPlayerContent.kt`：红心与全屏按钮并列（`actionButton` helper），底部行 bedtime/lyrics/queue；
  同样有 `sleepTimerActive` 参数。
- `ui/player/Queue.kt` 与 5 个 PlaylistScreen：新增「定位到正在播放歌曲」FAB（`HideOnScrollFAB.kt` 加了
  `onLocateClick/locateVisible/locateOnly`），`animateScrollToItem`，稳定 Lazy key。

### 全局 / 导航 / 性能
- `MainActivity.kt`：
  - `appBarScrollBehavior(canScroll=...)` 不再排除搜索页 → 搜索页也隐藏底栏。
  - `navBarHideTarget` + `animateFloatAsState(spring(...))` 驱动底栏隐藏；`BottomSheetPlayer` graphicsLayer 平移跟随。
  - 冷启动入场：`enterVisible` + `animateFloatAsState(tween(260))` 对根 BoxWithConstraints 做 alpha+微缩放淡入。
  - 一次性迁移：`TranslateLanguageKey=="en"` → `"zh-CN"`；OpenRouter 且无 key → `Free`。
  - `hasUpdate` 时才更新 latestVersionName。
- `utils/Updater.kt`：`isUpdateAvailable()` **恒返回 false**（定制版不显示上游更新红点）。
- `res/values/styles.xml`：`Theme.Metrolist` 的 windowBackground 由 `#ffffff` 改为 `@color/launch_background`
  （`values/colors.xml` 浅色 `#FFFBFE` / `values-night/colors.xml` 深色 `#141218`，消除冷启动闪白）；
  新增 `Animation.Metrolist.Activity` + `res/anim/metrolist_fade_in|fade_out|hold.xml`（页面切换淡入淡出）。
- `playback/MusicService.kt` `playQueue()`：shuffle 持久化修复
  （`PersistentShuffleAcrossQueuesKey` + `RememberShuffleAndRepeatKey`，默认 `true`，在 `PlayerSettings.kt`）。
- `LocalPlaylistViewModel.kt` 加 `.flowOn(Dispatchers.Default)`；`HomeScreen.kt` 社区列表稳定 key。
- `ui/screens/settings/PlayerSettings.kt`：`PersistentShuffleAcrossQueuesKey` 默认改 true。
- `DownloadUtil.kt`、`AiSettings.kt` 等有配套小改。

## 3. 必须遵守的约束（AGENTS.md + 用户要求）

1. **只改默认英文** `app/src/main/res/values/metrolist_strings.xml`；其他语言仅在用户明确要求中文时才动（本项目已破例为中文加过新键）。
2. **不改 DB schema**、不改 README/markdown（本 `HANDOFF.md` 是用户本次明确要求的交接文档，属例外）。
3. **不 commit / push**，除非用户明确授权。
4. **版本号**：AGENTS.md 写「不得 bump」，但用户明确授权过 1.5.2 → 现为 1.5.3。**别再改**，否则覆盖安装会丢登录/数据。
5. **UI/动效必须贴合原项目风格**（Material 3、既有 spring 规格 `constants/Dimensions.kt`），不要生硬加控件。
   用户多次因"突兀的输入框/数字行"退回，最终睡眠定时只留 Slider。
6. 目标语言默认中文 `zh-CN`；翻译要免 API key（内置 Free provider）。

## 4. 已知遗留问题 / 未验证项（接手优先看这里）

- **睡眠定时状态显示曾长期反复出问题**（用户多轮投诉"点确定仍显示未开启"）。
  最终方案是本地 `dialogTimerOn` + flow + volatile flag 三重保证，但**未在真机验证过**。
  若再报，先确认用户装的是 1.5.3（此前十几版同 versionCode 16 可能导致覆盖安装未生效）。
- `res/anim/*.xml` 的 activity 切换动画、`values-night/colors.xml` 的启动背景**未在真机肉眼验证**。
- `LyricsHelper` 并行竞速：目前按优先级 `await()` 全部结果再选首个成功，
  严格说不是"谁先返回用谁"（会等最慢的那个直到超时才返回）。若要真正首达即用，应改成
  `select`/`channel` 或给每个 provider 分级 deadline。功能正确但还有提速空间。
- 切歌"自动停"只做了缓冲加大 + 延时重试，**根因（流地址解析失败）未彻底定位**。
- `sleep_timer_custom_minutes` 字符串、`SleepTimerPresets` 常量当前无用，可清理。
- `SubscribeButton`（MiniPlayer 内）已定义但未使用。
- 设备 `emulator-5554` 处于 offline，adb 装包不可用；所有验证只做了编译 + APK 解包校验，无真机测试。

## 5. 当前工作区状态

`git diff --stat` 35 文件，**全部未提交**。APK 与 zip 均为 2026-11-11 13:03 构建，对应当前源码。
接手后建议先跑一次 `assembleFossDebug` 确认基线可编译。
