# E-Ink Compose 模块移植指南（:modules:eink → legado 系上游）

本文档是 E-Ink Compose 版本的**移植契约**：把本项目（EssentialReader）的
E-Ink 界面嵌入到其它 legado 系上游（试验目标：GymMickey/legadoM-Ink）时，
按此清单操作。模块与桥接层的职责边界见下文，移植时**模块树零改动，只重写
桥接层**。

## 1. 架构与职责边界

```
:modules/eink（模块 = 可整体复制的 E-Ink Compose 应用核心，零引擎依赖）
├─ theme/ component/ modifier/ refresh/   设计系统（EInkXxx 组件）
├─ arch/                                  UiState/UserMessage 约定 + @EInkImmutable
├─ navigation/                            EInkScreen 路由 + EInkNavController 栈导航
├─ engine/                                ★ 移植契约接口（ports）
│    EInkEngineRegistry + GlobalSettings + BookshelfEngine + SearchEngine +
│    TocEngine + BookDetailEngine + ChangeSourceEngine + CoverEngine + ReaderEngine
│    (+ EngineHandles: BookHandle/SourceHandle/SearchResultHandle/EInkPageContent)
├─ bookshelf/ search/ toc/ bookdetail/ changesource/ home/ reader/
│                                          全部 Screen + ViewModel + UiModel
├─ debug/ widget/ util/ settings/          调试页 / 封面组件 / 并发工具 / EInkSettings
└─ res/                                    全部资源带 eink_ 前缀（字符串 + 图标，
                                           与宿主 res 零同名，合并零遮蔽，自包含）

app/.../eink/（宿主 = 入口 + 桥接层，移植时按目标引擎重写）
├─ EInkMainActivity.kt                     入口（Manifest 注册、EInkBridge.install、
│                                          初始 extras、宿主能力参数注入）
├─ bridge/                                 ★ 唯一需要重写的部分：端口实现
│    EInkBridge.kt         装配入口（GlobalSettings/CoverEngine 在此）
│    BookshelfEngineImpl   书架流/目录刷新管线（对齐 MainViewModel.updateToc）
│    SearchEngineImpl      SearchModel 回调适配 + 搜索历史 DAO
│    TocEngineImpl         书籍解析(notShelf 落库)/目录拉取管线
│    BookDetailEngineImpl  查找链/目录预取管线/书架操作
│    ChangeSourceEngineImpl 跨源搜索/换源迁移管线（ReadBook 会话重载）
│    ReaderEngineImpl      ReadBook 全表面转发 + CallBack 适配 + 样式映射
│                          （含 BookHandleImpl/SourceHandleImpl/TextPageContent 等）
└─ reader/ReaderPageCanvas.kt              绘制叶子（ChapterProvider 画笔 + TextPage
                                           坐标直接绘制，槽位注入模块 ReaderScreen）
```

数据规则：端口只进出模块自有类型（UiModel / ReaderTextStyle 快照 /
不透明句柄）。编排逻辑（并发、状态机、防抖、自动翻页定时）全部在模块 VM；
桥接层只有一行转发与字段映射。

## 2. 移植步骤（嵌入目标上游）

1. **复制模块树**：整个 `modules/eink/` 目录（含 build.gradle.kts、res、
   consumer-rules.pro）。
2. **settings.gradle**：`include ':modules:eink'`（legadoM-Ink 已有
   `:modules:book`、`:modules:rhino`，追加即可）。
3. **版本目录**：目标仓 `gradle/libs.versions.toml` 需有模块用到的条目：
   `compose-bom / compose-ui / compose-ui-graphics / compose-foundation /
   compose-runtime / compose-ui-tooling(-preview) / activity-compose /
   lifecycle-viewmodel-compose / lifecycle-runtime-compose /
   kotlinx-coroutines-(core|android) / glide-glide / glide-compose`，
   以及插件 `kotlin-compose`、`android-library`。版本跟随目标仓即可
   （见 §4 兼容性注意事项）。
4. **app 依赖**：目标 app 模块加 `implementation project(':modules:eink')`。
5. **复制 app 侧桥接层**：`app/src/main/java/io/legado/app/eink/` 下的
   `EInkMainActivity.kt`、`bridge/`、`reader/ReaderPageCanvas.kt`，然后按
   §3 差异表适配引擎调用。
6. **Manifest**：注册入口（无桌面图标，由主题/启动页路由进入）：
   ```xml
   <activity
       android:name=".eink.EInkMainActivity"
       android:label="纯净阅读(墨水屏)"
       android:configChanges="locale|keyboardHidden|orientation|screenSize|smallestScreenSize|screenLayout|uiMode"
       android:windowSoftInputMode="adjustResize" />
   ```
7. **入口接线**（按目标上游的启动逻辑）：
   - 本仓：`WelcomeActivity.startMainActivity()` 按 `themeMode == "4"`（纯净
     阅读）分流到 EInkMainActivity；`MyFragment` 主题切换时启动并 CLEAR_TASK。
   - legadoM-Ink：其 `isEInkMode` 为 `themeMode == "3"`，没有纯净模式概念——
     需在其 AppConfig 增加等价的 `isEInkPureMode`（themeMode "4"）+
     `einkPrevThemeMode` + `exitEInkPureMode()`（本仓 AppConfig L29-52 的
     最小移植），或按需改用其自己的分流点。

## 3. 引擎差异表（EssentialReader → legadoM-Ink）

桥接层逐文件差异点（探明于 2026-08，基于 legadoM-Ink/main @ b8cabc611，
已在 port/eink 分支实测构建通过）：

| 桥接文件 | 差异与处置 |
|---|---|
| SearchEngineImpl | `SearchModel.CallBack` 多 `onSearchProgress(completed, total, resultCount)` 与 `onSourceStatesChanged(records: List<SourceSearchRecord>)` 两个方法——补两个空 override；`SourceSearchRecord` 需 import `io.legado.app.model.webBook.SourceSearchRecord` |
| ChangeSourceEngineImpl | `WebBook.searchBookAwait` 的 `filter` lambda 为三参 `(name, author, kind)`（本仓两参）——仅改 `searchSourceBook` 内这一处 lambda |
| ReaderEngineImpl | `ReadBook.loadOrUpContent/loadContent/onChapterListUpdated` 多默认参数（调用兼容，无需改）；`ChapterProvider` 排版函数拆至 `TextChapterLayout.kt`（对外 API 同名）；`TextPage.searchResult` 元素类型变为 `TextBaseColumn`（画布的 `when(column)` 走 else 分支，不破坏） |
| ReaderEngineImpl / TocEngineImpl | `ChapterProvider.srcReplace*` 语义不同（未使用，无影响）；`Book.Config.splitLongChapter` 默认值翻转（行为差异，非编译问题） |
| EInkBridge (install) | 目标仓需提供 `isEInkPureMode` 系列配置（见 §2 步骤 7）；`SearchScope(AppConfig.searchScope)` 在对方为多书源格式，构造兼容 |
| ReaderPageCanvas | 对方 `TextLine` 新增 `extraLetterSpacing/wordSpacing/isHtml`（本画布不处理这些偏移——排版细节会与对方 View 版有细微差异，属已知限制）；对方根提交已含全部 `*EInk` ReadBookConfig 字段，无需搬运 |
| 全局 | 对方 minSdk 21（模块已降到 21）；Kotlin 2.3.10 / Compose BOM 2025.04.01（见 §4）；其自带 E-Ink View 层适配（溢出菜单/WaitDialog 等）与 Compose 版并存不冲突 |

### 3.1 目标仓环境级事项（与代码无关，实测踩坑）

- **签名**：其 `gradle.properties` 提交了 `RELEASE_STORE_FILE=../legado.jks`
  （维护者本机路径）。无该文件时用命令行覆盖临时密钥：
  `-PRELEASE_STORE_FILE=<绝对路径> -PRELEASE_STORE_PASSWORD=… -PRELEASE_KEY_ALIAS=… -PRELEASE_KEY_PASSWORD=…`。
- **google-services.json**：仓库不含（本机/CI 提供），本地构建需放占位
  （覆盖全部 applicationId 含 `.debug` 后缀变体；其 .gitignore 已排除该文件）。
- **cronetlib**：`app/cronetlib/*.jar` 为本机依赖（gitignore），缺失时
  `lib/cronet` 全量编译失败——从任一已有环境复制 5 个 jar。
- **Gradle wrapper**：对方 8.14，本机已有 8.13 可直接用
  `<gradle-8.13>/bin/gradle.bat` 调用（AGP 8.13.2 最低要求即 8.13）。
- **构建变体**：appLegacy / appMax / appS 三 flavor，验证用
  `assembleAppLegacyDebug`。

### 3.2 目标仓二：legado-with-MD3（深度重构 fork，2026-08 实测）

目标 `D:\Projects\AndroidProjects\legado-with-MD3`（main @ 6dc2972）。
工程形态：AGP 9.2.1 内置 Kotlin（模块不 apply kotlin-android）、
Kotlin 2.4.0 / Compose BOM 2026.06.01 / Gradle 9.6.1 / minSdk 26 /
compileSdk 37 / Java 21 / Koin。模块树零源码改动通过构建；
`modules/eink/build.gradle.kts` 需按对方 catalog 重写
（compose-bom→`androidx.compose.bom`、`libs.activity.compose`、
glide-compose 直接坐标、去掉 kotlin-android 插件、Java 21）。
**入口机制**：接管其实验室「墨水屏显示」开关（`labEInkDisplay`，
存 "settings" DataStore，可经 `AppConfigStore.getBoolean` 同步读 /
`putBoolean` 写）——打开即切换、退出即关闭，替代 legadoM-Ink 的
themeMode "4" 纯净模式分流。

| 桥接文件 | 差异与处置 |
|---|---|
| ReaderEngineImpl | **回调双轨**：`ReadBook.CallBack` 仅剩 4 方法（upMenuView/loadChapterList/notifyBookChanged/sureNewProgress），渲染回调拆到 `ReadBook.ReaderRenderCallback`（upContent/upContentAwait/pageChanged/contentLoadFinish/upPageAnim/cancelSelect + LayoutProgressListener.onLayoutException）——适配器同实现两接口，register/unregister 两轨都走；`durPageIndex` 为 durChapterPos 派生只读值（端口本就只读，无影响）；翻页 `moveToNextPage()/moveToPrevPage()` 同名 |
| ReaderEngineImpl | **排版写入必须走 ReadStyleGateway**（对方根 build.gradle.kts 的 `:verifyConfigArchitecture` 护栏拦 `ReadBookConfig.* =` 直写，且正则无 `(?!=)` 排除——`ReadBookConfig.textBold == 1` 这种**比较**也会误中，用 `.let { it == 1 }` 规避）：17 个 `ReadStyleMutation`（TextSize/LetterSpacing/ParagraphIndent/LineSpacing/ParagraphSpacing/Padding*/Header/FooterPadding*/TextBold 全有键）逐个 `updateCurrentStyle` 后显式 `save()`（updateCurrentStyle 只改内存+publishState）；随后 `ChapterProvider.upStyle()` |
| ReaderPageCanvas | 护栏禁止 Compose 文件 import 兼容 Config——抗锯齿改 `antiAlias: Boolean` 参数，取值走 `EInkBridge.useAntiAlias`（Koin OtherSettingsGateway）；`ImageColumn` 自带 `book` 字段（换书瞬间旧页重绘不再误取新书），画布用 `column.book` |
| SearchEngineImpl | **SearchModel 已删除**，多源搜索重构为 `SearchBooksUseCase.execute(BookSearchRequest, control): Flow<SearchRunEvent>`（Started/Progress(upsertBooks…)/Finished）；自带 `BookSearchGateway` 3 方法 DAO 直连实现（对齐其 SearchRepositoryImpl 的 scope→parts 映射）；搜索范围读 local_ui_status DataStore 的 `search_scope` 键（会话内 IO 读一次） |
| BookshelfEngineImpl | 预缓存入队 `CacheBook.getOrCreate(source, book).addDownload(start, end)`；`startProcessJob(coroutineContext)` 同为 suspend |
| ReaderEngineImpl (startCache) | `CacheBook.start(ctx, book, start, end)` 为 **suspend**——直接构造 `CacheDownloadRequest(bookUrl, ChapterSelection.Range(...))` 走非 suspend 重载（与其实现等价） |
| ChangeSourceEngineImpl | `migrateTo(newBook, toc, replaceEnableDefault, chineseConverterType)` 需补两参（对齐其 ChangeBookSourceDialog：AppConfig.replaceEnableDefault / AppConfig.chineseConverterType）；searchBookAwait filter 三参同 legadoM-Ink |
| EInkBridge (GlobalSettings) | `changeSourceCheckAuthor` 存独立 local_ui_status DataStore，无同步门面——经 Koin `ChangeSourceSettingsGateway` 读；threadCount/autoRefreshBook/preDownloadNum 仍走（@Deprecated 但可用的）AppConfig 门面 |
| 入口接线 | `MainActivity.onCreate` 在 `checkStartupRoute()`（首启引导）之后分流；`LabConfigRouteScreen` 加 LaunchedEffect：开关为 true 即 CLEAR_TASK 切 `EInkMainActivity`；退出时 `AppConfigStore.putBoolean(labEInkDisplay, false)` + MainActivity CLEAR_TASK；defaultToRead 经 AppConfigStore 同步读，`bookDao.lastReadBook` 存在 |

环境事项：worktree 自带 `local.properties` 不入库需从主 checkout 复制；
worktree 分支 port/eink 提交 4430933。已保留的旧开关副作用：主题页
showEInkTheme（"电子书"主题显露）不再可达（打开即切走），无害未动。

## 4. 兼容性注意事项

- **Compose BOM 版本差**：本模块基于 BOM 2026.06.01 编写，对方为
  2025.04.01。模块只使用 Foundation/UI/Runtime 稳定 API；若构建报
  unresolved，回退该 API 的保守写法（改动收敛在模块内，两边同时受益）。
- **资源合并**：模块 res 全部资源名以 `eink_` 前缀开头（含 29 个图标，
  均为模块自有副本），与任何宿主 res 零同名 —— 合并时不会被宿主
  同名资源遮蔽。新增模块资源必须遵守同一前缀规则。
- **SharedPreferences**：`EInkSettings` 读写宿主默认 prefs 文件
  （`<packageName>_preferences`），键名 `einkBookshelfGrid /
  einkReaderKeepScreenOn / einkReaderAutoIntervalSec` 与历史版本一致；
  其初始化（attach）已折叠进宿主装配模板 `EInkBridge.install(context)`
  —— 宿主入口只需这一处调用（引擎注册表 `EInkEngineRegistry` 同样
  经此注册）。
- **ProGuard**：模块 `consumer-rules.pro` 随模块走；Compose/Glide 规则由
  各自 consumer 提供。

## 5. 移植验证清单

1. `./gradlew :modules:eink:assembleDebug` + 宿主 `assembleDebug` 通过。
2. 冷启动进入书架：列表/网格切换、翻页、长按详情。
3. 搜索→详情→加入书架→阅读：全链路。
4. 阅读器：翻页/菜单/排版调参（字号/边距/缩进即时重排）/缓存面板/自动翻页。
5. 目录页：跳章写回进度、缓存标记。
6. 换源：跨源搜索→应用换源→返回阅读页续读。
7. 回归重点：ReaderEngine 的 CallBack 转发链路（注册/注销/进度落库）。
