# E-Ink Compose 模块移植指南（:modules:eink → legado 系上游）

本文档是 E-Ink Compose 版本的**移植契约**：把本仓（legadoMD3-port，
`:modules:eink` 模块主仓）的 E-Ink 界面嵌入到其它 legado 系上游时，按此
清单操作。模块与桥接层的职责边界见 §1，移植时**模块树零改动，只重写
桥接层**。代码侧接入面地图见模块内 `src/main/java/io/legado/app/eink/contract/README.md`。

> 原始版本探明于 EssentialReader 仓（2026-08 实测）；2026-09 适配本仓的
> 快照渲染架构与 `contract/` 契约归并结构。§3/§3.2 差异表保留当时的实测
> 记录，其中 §3.2 的目标即本仓宿主。

## 1. 架构与职责边界

```
:modules:eink（模块 = 可整体复制的 E-Ink Compose 应用核心，零引擎依赖）
├─ contract/                        ★ 移植契约（接入面地图见目录内 README）
│    EInkEngineRegistry（装配）+ EInkSettings（模块自有偏好）+
│    EInkKeyEventHub（按键枢纽）+ GlobalSettings + BookshelfEngine +
│    SearchEngine + TocEngine + BookDetailEngine + ChangeSourceEngine +
│    CoverEngine + ReaderEngine（各端口及其伴生回调/结果类型）+
│    EngineHandles（BookHandle/SourceHandle/SearchResultHandle/SearchResultRef）+
│    ReaderPageSnapshot（排版产物快照系）+ ReaderTextStyle（排版参数快照）+
│    各页 UiModel（跨界展示模型）
├─ app/                             EInkApp 根 Composable + EInkScreen/EInkNavController 栈导航
├─ designsystem/                    设计系统（theme/content/control/interaction/
│                                   navigation/pager/refresh/widget，EInkXxx 组件）
├─ feature/                         全部 Screen + ViewModel
│                                   （bookshelf/home/search/bookdetail/toc/changesource/reader）
├─ arch/                            UiState/UserMessage 约定 + @EInkImmutable
├─ debug/                           组件画廊 / 主题调试页
├─ util/                            Flow 扩展
└─ res/                             全部资源带 eink_ 前缀（字符串 + 29 个图标，
                                    与宿主 res 零同名，合并零遮蔽，自包含）

app/.../eink/（宿主 = 入口 + 桥接层，移植时按目标引擎重写）
├─ EInkMainActivity.kt              入口（EInkBridge.install、keyEventHub 分发、
│                                   fontScale attach、EInkTheme+EInkApp 接线）
└─ bridge/                          ★ 唯一需要重写的部分：端口实现 + 快照映射
     EInkBridge.kt          装配入口（EInkSettings.attach + Registry.install +
                            封面开关与设置快照对齐；GlobalSettings 在此）
     BookshelfEngineImpl    书架流/目录刷新管线（对齐 MainViewModel.updateToc）
     SearchEngineImpl       多源搜索适配 + 搜索历史
     TocEngineImpl          书籍解析(notShelf 落库)/目录拉取管线
     BookDetailEngineImpl   查找链/目录预取管线/书架操作
     ChangeSourceEngineImpl 跨源搜索/换源迁移管线（ReadBook 会话重载）
     ReaderEngineImpl       ReadBook 全表面转发 + CallBack 适配 + 样式映射
                            （含 BookHandleImpl/SourceHandleImpl 等句柄包装）
     ReaderPageSnapshotMapper  TextPage → ReaderPageSnapshot 映射（宿主唯一
                            新增渲染职责；字段漂移在此消化，模块一份画布）
```

数据与渲染规则：端口只进出模块自有类型（UiModel / `ReaderTextStyle` /
`ReaderPageSnapshot` / 不透明句柄）。编排逻辑（并发、状态机、防抖、自动翻页
定时）全部在模块 VM；桥接层只有一行转发与字段映射。渲染为**快照式**：
排版引擎留在宿主，宿主映射 `TextPage` → `ReaderPageSnapshot`，模块自持画布
（`feature/reader/ReaderPageSnapshotCanvas`）绘制——宿主不再有画布注入。

## 2. 移植步骤（嵌入目标上游）

1. **复制模块树**：整个 `modules/eink/` 目录（含 build.gradle.kts、docs、
   consumer-rules.pro）。
2. **settings.gradle**：`include ':modules:eink'`（legadoM-Ink 已有
   `:modules:book`、`:modules:rhino`，追加即可）。
3. **版本目录**：目标仓 `gradle/libs.versions.toml` 需有模块用到的条目：
   `compose-bom / compose-ui / compose-ui-graphics / compose-foundation /
   compose-runtime / compose-ui-tooling(-preview) / activity-compose /
   lifecycle-viewmodel-compose / lifecycle-runtime-compose /
   kotlinx-coroutines-(core|android) / coil-compose`，
   以及插件 `android-library`、`compose-compiler`（AGP 9 内置 Kotlin 时
   模块不 apply kotlin-android）。版本跟随目标仓即可（见 §4）。
4. **app 依赖**：目标 app 模块加 `implementation project(':modules:eink')`。
5. **复制 app 侧桥接层**：`app/src/main/java/io/legado/app/eink/` 下的
   `EinkMainActivity.kt` 与 `bridge/`（九个文件，含 ReaderPageSnapshotMapper），
   然后按 §3 差异表适配引擎调用。
6. **Manifest**：注册入口（无桌面图标，由分流点进入）：
   ```xml
   <activity
       android:name=".eink.EInkMainActivity"
       android:label="纯净阅读(墨水屏)"
       android:configChanges="locale|keyboardHidden|orientation|screenSize|smallestScreenSize|screenLayout|uiMode"
       android:windowSoftInputMode="adjustResize" />
   ```
7. **入口接线**（按目标上游的启动逻辑选一种分流模式）：
   - EssentialReader：`WelcomeActivity.startMainActivity()` 按
     `themeMode == "4"`（纯净阅读）分流；`MyFragment` 主题切换时启动并
     CLEAR_TASK。
   - legadoM-Ink：其 `isEInkMode` 为 `themeMode == "3"`，没有纯净模式概念——
     需在其 AppConfig 增加等价的 `isEInkPureMode`（themeMode "4"）+
     `einkPrevThemeMode` + `exitEInkPureMode()`（EssentialReader AppConfig
     的最小移植），或按需改用其自己的分流点。
   - 本仓（legadoMD3-port）：实验室「墨水屏显示」开关
     （`labEInkDisplay`，"settings" DataStore，`AppConfigStore.getBoolean`
     同步读 / `putBoolean` 写）——`MainActivity.onCreate` 在首启引导之后
     分流；实验室页拨开即时 CLEAR_TASK 切换；E-Ink 内「退出到完整模式」
     即关闭该开关并回到 MainActivity。

## 3. 引擎差异表（EssentialReader → legadoM-Ink）

桥接层逐文件差异点（探明于 2026-08，基于 legadoM-Ink/main @ b8cabc611，
已在 port/eink 分支实测构建通过）：

| 桥接文件 | 差异与处置 |
|---|---|
| SearchEngineImpl | `SearchModel.CallBack` 多 `onSearchProgress(completed, total, resultCount)` 与 `onSourceStatesChanged(records: List<SourceSearchRecord>)` 两个方法——补两个空 override；`SourceSearchRecord` 需 import `io.legado.app.model.webBook.SourceSearchRecord` |
| ChangeSourceEngineImpl | `WebBook.searchBookAwait` 的 `filter` lambda 为三参 `(name, author, kind)`（本仓两参）——仅改 `searchSourceBook` 内这一处 lambda |
| ReaderEngineImpl | `ReadBook.loadOrUpContent/loadContent/onChapterListUpdated` 多默认参数（调用兼容，无需改）；`ChapterProvider` 排版函数拆至 `TextChapterLayout.kt`（对外 API 同名）；`TextPage.searchResult` 元素类型变为 `TextBaseColumn`（映射器 `when(column)` 走 else 分支，不破坏） |
| ReaderEngineImpl / TocEngineImpl | `ChapterProvider.srcReplace*` 语义不同（未使用，无影响）；`Book.Config.splitLongChapter` 默认值翻转（行为差异，非编译问题） |
| EInkBridge (install) | 目标仓需提供 `isEInkPureMode` 系列配置（见 §2 步骤 7）；`SearchScope(AppConfig.searchScope)` 在对方为多书源格式，构造兼容 |
| ReaderPageSnapshotMapper | 对方 `TextLine` 新增 `extraLetterSpacing/wordSpacing/isHtml`——快照契约即为此设计：字段漂移在映射器消化，模块画布零改动（对方未消费这些偏移时直接忽略即可）；对方根提交已含全部 `*EInk` ReadBookConfig 字段，无需搬运 |
| 全局 | 对方 minSdk 21（模块已降到 21）；Kotlin 2.3.10 / Compose BOM 2025.04.01（见 §4）；其自带 E-Ink View 层适配（溢出菜单/WaitDialog 等）与 Compose 版并存不冲突 |

### 3.1 目标仓环境级事项（legadoM-Ink，与代码无关，实测踩坑）

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

### 3.2 宿主形态参照：legadoMD3-port（本仓，2026-08 实测）

本仓即「深度重构 fork」形态（EssentialReader 仓当时实测目标
legado-with-MD3，main @ 6dc2972，worktree 提交 4430933）：AGP 9.2.1
内置 Kotlin（模块不 apply kotlin-android）、Kotlin 2.4.0 / Compose BOM
2026.06.01 / Gradle 9.6.1 / minSdk 26 / compileSdk 37 / Java 21 / Koin。
模块树零源码改动通过构建；`modules/eink/build.gradle.kts` 需按对方 catalog
重写（compose-bom→`androidx.compose.bom`、`libs.activity.compose`、
Coil3 直接坐标、去掉 kotlin-android 插件、Java 21）。下表是本仓宿主
桥接层的适配要点，可作同类「只读化配置 + 网关护栏」上游的参照：

| 桥接文件 | 差异与处置 |
|---|---|
| ReaderEngineImpl | **回调双轨**：`ReadBook.CallBack` 仅剩 4 方法（upMenuView/loadChapterList/notifyBookChanged/sureNewProgress），渲染回调拆到 `ReadBook.ReaderRenderCallback`（upContent/upContentAwait/pageChanged/contentLoadFinish/upPageAnim/cancelSelect + LayoutProgressListener.onLayoutException）——适配器同实现两接口，register/unregister 两轨都走；`durPageIndex` 为 durChapterPos 派生只读值（端口本就只读，无影响）；翻页 `moveToNextPage()/moveToPrevPage()` 同名 |
| ReaderEngineImpl | **排版写入必须走 ReadStyleGateway**（`:verifyConfigArchitecture` 护栏拦 `ReadBookConfig.* =` 直写，且正则无 `(?!=)` 排除——`ReadBookConfig.textBold == 1` 这种**比较**也会误中，用 `.let { it == 1 }` 规避）：18 个 `ReadStyleMutation`（TextSize/TitleSize/LetterSpacing/ParagraphIndent/LineSpacing/ParagraphSpacing/Padding*/Header/FooterPadding*/TextBold 全有键）逐个 `updateCurrentStyle` 后显式 `save()`（updateCurrentStyle 只改内存+publishState）；TitleSize 与 TextSize 同值写入（E-Ink 钉平标题=正文）；随后 `ChapterProvider.upStyle()` |
| ReaderPageSnapshotMapper | 护栏禁止 Compose 文件 import 兼容 Config——映射器为纯宿主侧文件，不受限；阴影规格不读 Paint（`shadowLayer*` getter 系 API 29+，minSdk 26 触即 NoSuchMethodError），改按 upStyle 同源条件从 ReadBookConfig 构造 `ReaderShadowSpec`；`ImageColumn` 自带 `book` 字段——映射闭包捕获 `column.book`（换书瞬间旧页不误取新书）；图片抗锯齿不经参数——`GlobalSettings.useAntiAlias` 由模块画布自取，宿主实现从 OtherSettingsGateway 读 |
| SearchEngineImpl | **SearchModel 已删除**，多源搜索重构为 `SearchBooksUseCase.execute(BookSearchRequest, control): Flow<SearchRunEvent>`（Started/Progress(upsertBooks…)/Finished）；自带 `BookSearchGateway` 3 方法 DAO 直连实现（对齐其 SearchRepositoryImpl 的 scope→parts 映射）；搜索范围读 local_ui_status DataStore 的 `search_scope` 键（会话内 IO 读一次） |
| BookshelfEngineImpl | 预缓存入队 `CacheBook.getOrCreate(source, book).addDownload(start, end)`；`startProcessJob(coroutineContext)` 同为 suspend |
| ReaderEngineImpl (startCache) | `CacheBook.start(ctx, book, start, end)` 为 **suspend**——直接构造 `CacheDownloadRequest(bookUrl, ChapterSelection.Range(...))` 走非 suspend 重载（与其实现等价） |
| ChangeSourceEngineImpl | `migrateTo(newBook, toc, replaceEnableDefault, chineseConverterType)` 需补两参（对齐其 ChangeBookSourceDialog：AppConfig.replaceEnableDefault / AppConfig.chineseConverterType）；searchBookAwait filter 三参同 legadoM-Ink |
| EInkBridge (GlobalSettings) | `changeSourceCheckAuthor` 存独立 local_ui_status DataStore，无同步门面——经 Koin `ChangeSourceSettingsGateway` 读；threadCount/autoRefreshBook/preDownloadNum 仍走（@Deprecated 但可用的）AppConfig 门面 |
| 入口接线 | `MainActivity.onCreate` 在 `checkStartupRoute()`（首启引导）之后分流；`LabConfigRouteScreen` 加 LaunchedEffect：开关为 true 即 CLEAR_TASK 切 `EInkMainActivity`；退出时 `AppConfigStore.putBoolean(labEInkDisplay, false)` + MainActivity CLEAR_TASK；defaultToRead 经 AppConfigStore 同步读，`bookDao.lastReadBook` 存在 |

环境事项：worktree 自带 `local.properties` 不入库需从主 checkout 复制。
已保留的旧开关副作用：主题页 showEInkTheme（"电子书"主题显露）不再可达
（打开即切走），无害未动。

## 4. 兼容性注意事项

- **Compose BOM 版本差**：本模块基于 BOM 2026.06.01 编写（legadoM-Ink 为
  2025.04.01）。模块只使用 Foundation/UI/Runtime 稳定 API；若构建报
  unresolved，回退该 API 的保守写法（改动收敛在模块内，两边同时受益）。
- **图片加载**：模块与宿主共用 Coil 3 单例 ImageLoader（`EInkAsyncImage` /
  封面组件经 `CoverEngine.coverRequestOptions` 取得宿主策略：书源 origin
  头、目标尺寸、默认封面开关）。目标上游若用 Glide 等其它栈，桥接层
  `CoverEngine` 实现内完成请求策略映射，模块不感知。
- **资源合并**：模块 res 全部资源名以 `eink_` 前缀开头（含 29 个图标，
  均为模块自有副本），与任何宿主 res 零同名——合并时不会被宿主同名资源
  遮蔽。新增模块资源必须遵守同一前缀规则。
- **SharedPreferences**：`EInkSettings` 读写宿主默认 prefs 文件
  （`<packageName>_preferences`），键名 `einkBookshelfGrid /
  einkReaderKeepScreenOn / einkReaderAutoIntervalSec` 与历史版本一致；
  其初始化（attach）已折叠进宿主装配模板 `EInkBridge.install(context)`
  ——宿主入口只需这一处调用（引擎注册表 `EInkEngineRegistry` 同样
  经此注册）。
- **ProGuard**：模块 `consumer-rules.pro` 随模块走；Compose/Coil 规则由
  各自 consumer 提供。

## 5. 移植验证清单

1. `./gradlew :modules:eink:assembleDebug` + 宿主 `assembleDebug` 通过。
2. 冷启动进入书架：列表/网格切换、翻页、长按详情。
3. 搜索→详情→加入书架→阅读：全链路。
4. 阅读器：翻页/菜单/排版调参（字号/边距/缩进即时重排）/缓存面板/自动翻页。
5. 目录页：跳章写回进度、缓存标记。
6. 换源：跨源搜索→应用换源→返回阅读页续读。
7. 回归重点：ReaderEngine 的 CallBack 转发链路（注册/注销/进度落库）。
