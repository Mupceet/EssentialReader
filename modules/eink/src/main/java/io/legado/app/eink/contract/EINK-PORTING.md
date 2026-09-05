# E-Ink Compose 模块移植手册（:modules:eink → legado 系宿主）

本文档是 E-Ink Compose 版本的**移植契约**：把本仓 `:modules:eink` 模块嵌入到
任意 legado 系上游时，按此清单操作。文档随模块走、归档于 `contract/`
目录（宿主接入面的一部分）；代码侧接入面地图见同目录 `README.md`。

> 版本沿革：2026-08 探明 EssentialReader 形态；2026-09 适配本仓快照渲染
> 架构与 `contract/` 契约归并；**2026-09-05 经「develop@01ee1e956（AGP 8.13
> 旧栈宿主）全量回放移植」实测修订**——步骤与硬门槛均以本次回放为准，
> 各宿主差异表见 §3。

## 0. 版本栈硬门槛（动手前先对表）

模块源码 minSdk 为 21，但**依赖栈的实际门槛是 minSdk 23**（Coil 3.5 全
构件 + Compose BOM 2026.06.01 全栈均声明 23）。宿主 minSdk < 23 时
manifest merge 直接失败，且 `tools:overrideLibrary` 不可行（涉及数十个
构件）——唯一路径是宿主 minSdk 升 23，或模块连同 BOM/Coil 整体降版并
自行验证。

| 维度 | 模块默认 | 宿主不满足时 | 实测（AGP 8.13 宿主） |
|---|---|---|---|
| minSdk | 源码 21 / 依赖栈 23 | 宿主须 ≥ 23 | 21 → 23 通过 |
| compileSdk | 37 | 对齐宿主所支持的最高版 | 37 → 36 |
| Java 字节码 | 21 | 对齐宿主 compileOptions | 21 → 17 |
| Kotlin | 2.4 编写 | 2.3 可编（一版本前向元数据兼容） | 2.3.0 通过 |
| Compose BOM | 2026.06.01 | 低于此按 §4 保守回退 | 2026.06.01 通过 |

## 1. 架构与职责边界

```text
:modules:eink（模块 = 可整体复制的 E-Ink Compose 应用核心，零引擎依赖）
├─ contract/                        ★ 移植契约（本手册与接入面 README 在此）
│    EInkEngineRegistry（装配）+ EInkHostActivity（入口模板基类，宿主只
│    实现 onInstallEngines/onExitToFullMode 两钩子）+
│    GlobalSettings（模块全部设置项的唯一出入口）+ BookshelfEngine +
│    SearchEngine + TocEngine + BookDetailEngine + ChangeSourceEngine +
│    CoverEngine + ReaderEngine（各端口及其伴生回调/结果类型）+
│    EngineHandles（BookHandle/SourceHandle/SearchResultHandle）+
│    ReaderPageSnapshot（排版产物快照系）+ ReaderTextStyle（排版参数快照）+
│    各页 UiModel（跨界展示模型）
├─ app/                             EInkKeyEventHub 按键枢纽（模块自有）+
│                                   EInkApp 根 Composable + 栈导航
├─ designsystem/ feature/ arch/ debug/ util/ res/
│                                   （模块内部实现与自包含资源，宿主不读）
└─ build.gradle.kts                 AGP 9 形态（AGP < 9 宿主按 §2 步骤 3b 改）

app/.../eink/（宿主 = 入口子类 + 桥接层，移植时按目标引擎重写）
├─ EinkMainActivity.kt              入口子类（两钩子，约 30 行）
└─ bridge/                          ★ 唯一需要重写的部分：端口实现 + 快照映射
     EInkBridge.kt          装配入口（Registry.install + 设置快照对齐）
     BookshelfEngineImpl / SearchEngineImpl / TocEngineImpl /
     BookDetailEngineImpl / ChangeSourceEngineImpl / CoverEngineImpl /
     ReaderEngineImpl / ReaderPageSnapshotMapper
```

「模块树零改动，只重写 bridge」在**源码层面**成立；唯一例外是模块的
`build.gradle.kts`（构建栈适配，见 §2 步骤 3b）。

## 2. 移植步骤（嵌入目标上游）

1. **复制模块树**：整个 `modules/eink/` 目录（含 build.gradle.kts、docs、
   consumer-rules.pro）。
2. **settings.gradle**：`include ':modules:eink'`。
3. **版本目录**：目标仓 `gradle/libs.versions.toml` 需含以下**精确别名**
   （与模块 build.gradle.kts 的引用一一对应，名字不同则改目录侧别名）：
   - 库：`androidx-compose-bom`、`androidx-compose-ui`、
     `androidx-compose-foundation`、`androidx-compose-ui-tooling`、
     `androidx-compose-ui-tooling-preview`、`compose-runtime`、
     `androidx-lifecycle-viewmodel-compose`、`androidx-lifecycle-runtime-compose`、
     `activity-compose`、`coil-compose`、`junit`、coroutines bundle；
   - 插件：`android-library`、`kotlin-android`、`compose-compiler`
     （`org.jetbrains.kotlin.plugin.compose`，版本必须与宿主 Kotlin 完全
     一致）。版本跟随目标仓；宿主没有的条目（如纯 View 宿主的全部
     Compose 项）按 §0 门槛新增。
3b. **模块构建适配（AGP < 9 宿主必做，模块树唯一例外）**：
   - plugins 增加 `alias(libs.plugins.kotlin.android)`（AGP 9 内置
     Kotlin 时才可省略）；
   - `kotlin { jvmToolchain(...) }` 块从 `android {}` 内移到顶层
     （嵌套形态是 AGP 9 内置 Kotlin 专属 DSL）；
   - compileSdk / Java 版本对齐宿主（§0 表）。
4. **app 依赖**：`implementation project(':modules:eink')`；**另需补两条**
   模块不会传递的依赖——`coil-compose`（契约 `CoverEngine` 签名暴露
   Coil 类型，模块的 implementation 依赖不外泄）与 `compose-runtime`
   （`GlobalSettings.useDefaultCover` 的快照状态语义需要）。
5. **编写宿主入口与桥接层**：入口写一个 `EInkHostActivity` 子类（实现
   `onInstallEngines()` 与 `onExitToFullMode(context)` 两钩子）；`bridge/`
   九个文件按 §3 差异表适配引擎调用。
6. **Manifest**：注册入口（无桌面图标，由分流点进入）：
   ```xml
   <activity
       android:name=".eink.EInkMainActivity"
       android:configChanges="locale|layoutDirection|orientation|screenSize|smallestScreenSize|screenLayout|uiMode"
       android:windowSoftInputMode="adjustResize" />
   ```
7. **入口接线（通用模式）**：宿主任一持久化配置位（布尔或枚举值）+
   启动 Activity 分流 + 退出写回。已验证形态：
   - 本仓：实验室开关 `labEInkDisplay`，MainActivity.onCreate 分流；
   - EssentialReader：`themeMode == "4"`，WelcomeActivity 分流；
   - develop@01ee1e956：`themeMode == "4"`，MainActivity.onActivityCreated
     首行分流，退出写回 "0"。

## 2A. 二进制依赖形态（AAR：Maven Local / 远程）

源码嵌入（§2）之外的第二种消费形态：宿主依赖预构建产物而非模块源码。
模块的 `build.gradle.kts` 已内置 maven-publish 接线（release 单变体 +
sources jar，坐标 `io.legado.app.eink:eink`）。

1. **发布**（在模块所属构建中执行）：
   `./gradlew :modules:eink:publishReleasePublicationToMavenLocal`
   → 产物在 `~/.m2/repository/io/legado/app/eink/`。
2. **宿主接入**：settings.gradle 的 dependencyResolutionManagement
   repositories 加 `mavenLocal()`（将来远程依赖时替换为仓库 URL，宿主
   侧零其它改动）；app 依赖改为坐标
   `implementation 'io.legado.app.eink:eink:0.1.0'`。
3. **注意事项（实测）**：
   - 模块 implementation 依赖不随 AAR 外泄编译期可见性——宿主 bridge
     仍需自备 `coil-compose`（契约签名暴露 Coil 类型）与
     `compose-runtime`（快照状态）；
   - 宿主首次解析会经网络取 `compose-bom` 的 .pom（POM 路径不含
     BOM import 语义，compose-bom 作为普通依赖出现）——离线环境需
     预缓存或在宿主声明同一 BOM platform；镜像源环境下 dl.google.com
     直连偶发超时属网络问题，重试即可；
   - 仅发布 release 变体：宿主 debug 构建不再包含模块内
     `BuildConfig.DEBUG` 裁剪的调试入口（组件画廊等）；
   - 产物栈绑定：AAR 由哪个构建栈产出就带哪个栈的字节码/Kotlin 元数据
     ——跨栈消费前核对 §0（Kotlin 编译器可读一版本前向的元数据）。
     现行版本：**0.1.0 = 旧栈（AGP8.13/K2.3/Java17）构建**，develop
     宿主在用；**0.2.0 = 主栈（AGP9/K2.4/Java21）构建**，K2.3 宿主
     消费时元数据按一版本前向规则可读、Java 21 字节码经 D8 消解，
     均需真机回归确认后再切换坐标。

## 3. 引擎差异表（各宿主实测记录）

### 3.1 EssentialReader → legadoM-Ink（2026-08 探明）

| 桥接文件 | 差异与处置 |
|---|---|
| SearchEngineImpl | `SearchModel.CallBack` 多 `onSearchProgress` 与 `onSourceStatesChanged`——补空 override |
| ChangeSourceEngineImpl | `searchBookAwait` filter 三参 `(name, author, kind)`——仅改一处 lambda |
| ReaderEngineImpl | 排版函数拆至 `TextChapterLayout.kt`（对外同名）；`TextPage.searchResult` 元素 `TextBaseColumn`（映射器走 else 分支） |
| ReaderEngineImpl / TocEngineImpl | `ChapterProvider.srcReplace*` 语义不同（未使用）；`splitLongChapter` 默认值翻转 |
| EInkBridge | 目标仓需提供 `isEInkPureMode` 系列配置 |
| ReaderPageSnapshotMapper | `TextLine` 多 `extraLetterSpacing/wordSpacing/isHtml`——快照契约消化 |

### 3.2 legadoMD3-port 本仓形态（2026-08/09 实测）

| 桥接文件 | 差异与处置 |
|---|---|
| ReaderEngineImpl | **回调双轨**：业务/渲染拆两接口，适配器同实现、两轨注册；排版写入必须走 `ReadStyleGateway` mutation + 显式 `save()`（架构护栏禁直写，比较也会误中，用 `.let { it == 1 }` 规避）；TitleSize 与 TextSize 同值写入 |
| ReaderEngineImpl (startCache) | `CacheBook.start` 为 suspend——改构造 `CacheDownloadRequest` 走非 suspend 重载 |
| SearchEngineImpl | `SearchModel` 已删——走 `SearchBooksUseCase.execute(...): Flow<SearchRunEvent>`；搜索范围读 local_ui_status DataStore |
| ChangeSourceEngineImpl | `migrateTo` 需补 `replaceEnableDefault`/`chineseConverterType` 两参；filter 三参 |
| EInkBridge | 全部设置经网关（OtherSettings/ReadSettings/DownloadCache/Cover/ChangeSource Gateway + Koin）；fontScaleSetting 仍走同步快照 |

### 3.3 develop@01ee1e956（legado-with-MD3 旧栈，2026-09-05 全量回放实测）

宿主形态：AGP 8.13.2 / Kotlin 2.3.0 / Groovy 构建脚本 / compileSdk 36 /
Java 17 / minSdk 21（按 §0 升 23）/ 无 Compose 无 Coil（图片栈 Glide）/
无设置网关与 Koin / 无 AppConfigStore。

| 桥接文件 | 差异与处置 |
|---|---|
| EInkBridge | 无网关无 Koin：全部设置直读写 `AppConfig`/`ReadBookConfig`/`putPref*` 扩展。键名漂移——`autoRefreshBook` 的键是 `PreferKey.autoRefresh`（且为只读计算属性，写须直接 `putPrefBoolean`）；`defaultToRead` 无缓存字段（直读写 pref）；`fontScale` 宿主无此设置（E-Ink 自管键，0 = 未设置） |
| ReaderEngineImpl | **回调单轨**：`ReadBook.CallBack` 全量接口（含 LayoutProgressListener 的 `onLayoutException`/`cancelSelect`），单接口实现单轨注册；排版写入 `ReadBookConfig` 直写 + `save()` + `upStyle()`（无护栏）；`CacheBook.start(ctx, book, start, end)` 非 suspend；`setAutoReadIntervalSec` 直写 `ReadBookConfig.autoReadSpeed` + `putPrefInt` |
| ReaderPageSnapshotMapper | `ImageColumn` 无 `book` 字段（引擎绘制读全局 `ReadBook.book`）——loader 闭包按映射时刻全局书取图；`fontVariationSettings` 为 API 26 而 minSdk 21/23——门控 `if (SDK_INT >= 26)` |
| SearchEngineImpl | 宿主 `SearchModel(scope, callBack)` 五方法回调（无 progress 回调，模块进度提示自动保持初值）；`trimIntro` 是 `SearchBook` 成员函数；`searchKeywordDao` 无按词删除（`get(word)` 再 `delete(it)`） |
| ChangeSourceEngineImpl | `migrateTo(newBook, toc)` 两参（无 replace/converter 形参）；filter 两参 |
| CoverEngineImpl | **Glide 宿主**：无宿主 Coil 单例可挂——实现退化为仅设目标尺寸；0.2.0 起网络封面经模块传递的 OkHttp 抓取器开箱可用，防盗链封面仍回退占位（见 §4） |
| BookshelfEngineImpl | 预缓存门槛键 `AppConfig.preDownloadNum`（非网关）；其余与通版一致 |
| TocEngineImpl / BookDetailEngineImpl | 零差异，通版直接可用 |

## 4. 兼容性注意事项

- **Compose BOM 版本差**：模块基于 BOM 2026.06.01 编写。模块只使用
  Foundation/UI/Runtime 稳定 API；构建报 unresolved 时回退保守写法
  （改动收敛在模块内，两边同时受益）。
- **图片加载（0.2.0 起）**：模块以 `api` 传递 `coil-compose` 与
  `coil-network-okhttp`——AAR 消费方**开箱可加载网络封面**（Coil 3 缺
  网络抓取器构件时 http 封面全部失败，曾致 develop 宿主封面不显示，
  0.1.0 及源码嵌入形态需宿主自行补 `coil-network-okhttp`）。防盗链/
  书源请求头仍经 `CoverEngine` 注入：Coil 宿主复用宿主单例
  ImageLoader 拦截器（本仓形态）；Glide 等其它栈宿主默认无防盗链
  （相关封面回退占位），恢复路径为模块的 Coil 实例注册带书源解析的
  网络拦截器。
- **资源合并**：模块资源全部 `eink_` 前缀，与任何宿主零同名。
- **D8/R8 元数据警告**：AGP 8.x 宿主消费新 Kotlin metadata 的库时 D8
  打 warning（不阻塞 debug；release R8 需真机回归确认）。
- **ProGuard**：模块 `consumer-rules.pro` 随模块走；Compose/Coil 规则由
  各自 consumer 提供。
- **SharedPreferences**：模块零自有存储——全部设置经 GlobalSettings
  端口，存储后端由宿主决定；E-Ink 自有偏好 `keepScreenOn` 以历史键
  `einkReaderKeepScreenOn` 落宿主默认 prefs 文件。

## 5. 移植验证清单

1. `./gradlew :modules:eink:assembleDebug` + 宿主编译/assemble（任务名按
   宿主 flavor 结构替换，如 `:app:compileAppDebugKotlin`；本仓的
   `verifyConfigArchitecture` 等专属门禁不适用于其它上游）。
2. 冷启动进入书架：列表/网格切换、翻页、长按详情。
3. 搜索→详情→加入书架→阅读：全链路。
4. 阅读器：翻页/菜单/排版调参（字号/边距/缩进即时重排）/缓存面板/自动翻页。
5. 目录页：跳章写回进度、缓存标记。
6. 换源：跨源搜索→应用换源→返回阅读页续读。
7. 回归重点：ReaderEngine 的 CallBack 转发链路（注册/注销/进度落库）。
8. 门槛项（§0 有升版时）：minSdk 23 以下真机不再支持，发布说明需明示。
