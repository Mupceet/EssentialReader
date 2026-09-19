# E-Ink Compose 模块移植手册（:modules:eink → legado 系宿主）

把 `:modules:eink` 模块嵌入任意 legado 系上游时按本手册操作。文档随模块
走、归档于 `contract/` 目录（宿主接入面的一部分）。

**文档分工**（各司其职，不互相重复）：

| 文档 | 内容 |
|---|---|
| **本文** | 移植流程：消费门槛、嵌入/消费步骤、各宿主差异记录、兼容注意、验证清单 |
| `contract/README.md`（同目录） | 代码侧接入面地图：端口总表、跨界数据类型、装配时序、能力裁剪方言 |
| `gradle/libs.versions.toml` 头部（模块内） | 依赖版本语义、消费门槛依据、升档协议的**权威注释**——改任何依赖版本前先读它 |
| `build.gradle.kts` 版本注释（模块内） | 发版沿革与历史坐标（0.1.0 旧栈、oldstack/min21 孪生等） |

## 0. 消费门槛（动手前先对表）

模块依赖集整体钉「保守档」，单一坐标服务新旧宿主：模块按保守档编译、
POM 随保守版发布，新栈宿主解析时自动升到自己的更高版本（Gradle 取
max，二进制兼容；本仓 app 即此形态：BOM 2026.08.00 / Coil 3.6.2 /
lifecycle 2.11）。

| 维度 | 门槛 | 依据 |
|---|---|---|
| minSdk | ≥ 21 | 依赖集下限（Coil 3.0.4 档）；宿主**自有依赖**也须 ≤21 档（如自有 Coil 须 3.0.x 同档） |
| compileSdk | ≥ 35 | 模块 AAR 元数据 minCompileSdk = 35（＝依赖集地板：compose ui/foundation 1.9.4 与 core 1.15 实测均 35），对应 **AGP ≥ 8.6.0** |
| Kotlin | ≥ 2.3 | 模块以 K2.4 构建，元数据一版本前向可读；源码嵌入形态随宿主 KGP 编译，无此约束 |
| Java 字节码 | 17 | 宿主 D8 消解（AAR 形态）；源码嵌入随宿主 compileOptions |

2026-09 家族调研（legado.erchuang.online 索引，22 个 Android 仓）：原生
家族地板 AGP 8.13.2 / compileSdk 36 / Kotlin 2.3.0 / minSdk 21——当前门槛
对家族 **100% 覆盖**，其中 Kotlin 2.3 为零余量贴地档（升级依据与家族
分布详见模块 toml 头部注释）。API 21/22 真机行为需回归；模块无 >21 的
直接框架调用，宿主 bridge 侧超 21 的调用自行 SDK 门控（参照
EssentialReader 的 fontVariationSettings API 26 处理）。

## 1. 架构与职责边界

```text
:modules:eink（模块 = 可整体复制的 E-Ink Compose 应用核心，零引擎依赖）
├─ contract/                        ★ 移植契约（本手册与接入面 README 在此）
│    EInkEngineRegistry（装配注册表）+ EInkHostActivity（入口模板基类，
│    宿主只实现 onInstallEngines / onExitToFullMode 两钩子，另有可选
│    UI 字体钩子 uiFontFamily）+ 必填端口 8 个（GlobalSettings 设置端口 +
│    Bookshelf / Search / Toc / BookDetail / ChangeSource / Cover /
│    Reader 业务端口，及各自伴生回调与结果类型）+ 可选端口 4 个
│    （AppUpdate / ReaderSelection / Marks / BookshelfGroup）+
│    EngineHandles（不透明句柄）+ ReaderPageSnapshot（排版产物快照系）+
│    ReaderTextStyle / ReaderStyleCatalog（排版参数快照与协商目录）
├─ app/                             EInkKeyEventHub 按键枢纽 + EInkApp 根
│                                   Composable 与栈导航
├─ designsystem/ feature/ arch/ debug/ util/ res/
│                                   （模块内部实现与自包含资源，宿主不读）
├─ gradle/libs.versions.toml        ★ 模块自有版本目录（保守档钉版与升档
│                                   协议权威注释，经 einkLibs 挂载）
└─ build.gradle.kts                 AGP 9 形态（AGP < 9 宿主按 §2 步骤 3b 改）

app/.../eink/（宿主 = 入口子类 + 桥接层，移植时按目标引擎重写）
├─ EinkMainActivity.kt              入口子类（两钩子 + 可选字体钩子，约 50 行）
└─ bridge/                          ★ 唯一需要重写的部分：端口实现 + 快照映射
     EInkBridge.kt          装配入口（Registry.install + 设置快照对齐）
     *EngineImpl（12 个）    各端口实现（4 个可选端口随宿主能力取舍）
     快照映射与支撑          ReaderPageSnapshotMapper、HostStyleCatalog、
                            BookshelfUiMapper / Sorter / StyleMapper、
                            ReaderProgressSyncer / Policy、ReaderStyleMutations、
                            ReaderTipFonts、ReaderChapterPager、EInkBookmarkState
```

「模块树零改动，只重写 bridge」在源码层面成立；唯一例外是模块的
`build.gradle.kts`（AGP < 9 宿主的构建栈适配，见 §2 步骤 3b）。职责三分
（模块界面编排 / 宿主引擎管线 / 入口模板生命周期）见 `contract/README.md`
§1；各端口的实现契约（职责边界、调用时机、线程、失败语义）以**接口
KDoc 为权威**。

## 2. 源码嵌入步骤（形态一）

1. **获取模块树**，两种等价形态：
   - **子模块（推荐）**：引用 eink/lib 独立分支（本仓 md3/port/eink 即
     此形态），升级＝推进子模块指针后提交：
     ```bash
     git submodule add -b eink/lib https://github.com/Mupceet/EssentialReader.git eink-lib
     ```
     settings 侧 `include ':modules:eink'` 配
     `project(':modules:eink').projectDir = file('eink-lib/modules/eink')`，
     步骤 3 的 einkLibs 挂载路径相应为
     `eink-lib/modules/eink/gradle/libs.versions.toml`；
   - **手工复制**：整个 `modules/eink/` 目录（含 build.gradle.kts、
     gradle/libs.versions.toml、docs、consumer-rules.pro）。
2. **settings.gradle**：`include ':modules:eink'`（子模块形态配
   projectDir 重定向，见步骤 1）。
3. **版本目录与挂载**：模块库依赖钉在自有版本目录
   `modules/eink/gradle/libs.versions.toml`（保守档与升档协议的权威注释
   在该文件头部，见 §0）。目标仓 settings 的
   `dependencyResolutionManagement` 加挂载：
   ```groovy
   // Groovy settings.gradle（KTS 同构：einkLibs { from(files(...)) }）
   versionCatalogs {
       einkLibs {
           from(files('modules/eink/gradle/libs.versions.toml'))
       }
   }
   ```
   目标仓根目录 `libs.versions.toml` 只需提供模块引用的**插件别名**：
   `android-library`、`kotlin-android`（AGP < 9 宿主必需）、
   `compose-compiler`（`org.jetbrains.kotlin.plugin.compose`，版本必须与
   宿主 Kotlin 完全一致）。插件版本跟随目标仓；宿主已用更高版本依赖时
   解析自动取 max，无需与模块钉版对齐。
3b. **模块构建适配（AGP < 9 宿主必做，模块树唯一例外）**：
   - plugins 增加 `alias(libs.plugins.kotlin.android)`（AGP 9 内置
     Kotlin 时才可省略）；
   - `kotlin { jvmToolchain(...) }` 块从 `android {}` 内移到顶层
     （嵌套形态是 AGP 9 内置 Kotlin 专属 DSL）；
   - compileSdk / Java 版本对齐宿主（§0 表）。
4. **app 依赖**：`implementation project(':modules:eink')`；另需补一条
   模块不会传递的依赖——`compose-runtime`（模块对它是 implementation，
   不外泄；`GlobalSettings.useDefaultCover` 的快照状态语义需要）。图片栈
   零宿主义务：Coil 为模块 implementation 依赖（契约 `CoverEngine` 是纯
   Kotlin 字节端口），宿主 app 代码与依赖清单均不出现 Coil。
5. **编写宿主入口与桥接层**：入口写一个 `EInkHostActivity` 子类（实现
   `onInstallEngines()` 与 `onExitToFullMode(context)` 两钩子）；`bridge/`
   按 §1 清单逐端口实现（§3 差异表只列各宿主与通版的差异）。可选覆写
   `uiFontFamily()` 为 E-Ink 界面提供全局 UI 字体（默认 null = 平台默认
   字体；组合内调用，可订阅宿主状态流实时生效），不覆写不影响移植。
6. **Manifest**：注册入口（无桌面图标，由分流点进入）：
   ```xml
   <activity
       android:name=".eink.EInkMainActivity"
       android:configChanges="locale|layoutDirection|orientation|screenSize|smallestScreenSize|screenLayout|uiMode"
       android:windowSoftInputMode="adjustResize" />
   ```
   `uiMode` 必须在 configChanges 中（主题跟随系统深浅色不重建，由
   onConfigurationChanged 推进 State 驱动重组）。
7. **入口接线（通用模式）**：宿主任一持久化配置位（布尔或枚举值）+
   启动 Activity 分流 + 退出写回。已验证形态：
   - 本仓：实验室开关 `labEInkDisplay`，MainActivity.onCreate 分流；
   - EssentialReader：`themeMode == "4"`，WelcomeActivity 分流；
   - develop@01ee1e956：`themeMode == "4"`，MainActivity.onActivityCreated
     首行分流，退出写回 "0"。
8. **验证**：按 §5 清单。

## 2A. 二进制依赖形态（形态二）

宿主依赖预构建 AAR 而非模块源码。模块 `build.gradle.kts` 已内置
maven-publish 接线（release 单变体 + sources jar，坐标
`io.legado.app.eink:eink`）。

1. **发布**（在模块所属构建中执行）：
   `./gradlew :modules:eink:publishReleasePublicationToMavenLocal`
   → 产物在 `~/.m2/repository/io/legado/app/eink/`。
2. **宿主接入**：settings 的 dependencyResolutionManagement repositories
   加 `mavenLocal()`（远程依赖时替换为仓库 URL）；app 依赖改为坐标
   `implementation 'io.legado.app.eink:eink:<版本>'`。
3. **注意事项**：
   - 模块 implementation 依赖不随 AAR 外泄编译期可见性——宿主 bridge
     仅需自备 `compose-runtime`（快照状态语义）；契约零图片框架类型，
     Coil 对宿主完全不可见，网络封面能力经 POM runtime 域自动传递、
     开箱可用；
   - 宿主首次解析会经网络取 `compose-bom` 的 .pom（POM 路径不含 BOM
     import 语义，compose-bom 作为普通依赖出现）——离线环境需预缓存或
     在宿主声明同一 BOM platform；镜像源环境下 dl.google.com 直连偶发
     超时属网络问题，重试即可；
   - 仅发布 release 变体：宿主 debug 构建不含模块内 `BuildConfig.DEBUG`
     裁剪的调试入口（组件画廊等）；
   - 产物栈绑定：AAR 带构建栈的字节码/Kotlin 元数据——当前以主栈
     （AGP9/K2.4）构建，K2.3 宿主按一版本前向规则可读、Java 17 字节码
     D8 消解，切换坐标前需真机回归确认。发版沿革与历史坐标见模块
     `build.gradle.kts` 版本注释。

## 3. 已有移植的差异记录（实测存档）

历史移植的实测差异，反映**当时**的模块版本与宿主形态；方法名与语义以
当前契约 KDoc 为准。

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
Java 17 / minSdk 21 / 无 Compose 无 Coil（图片栈 Glide）/ 无设置网关与
Koin / 无 AppConfigStore。

| 桥接文件 | 差异与处置 |
|---|---|
| EInkBridge | 无网关无 Koin：全部设置直读写 `AppConfig`/`ReadBookConfig`/`putPref*` 扩展。键名漂移——`autoRefreshBook` 的键是 `PreferKey.autoRefresh`（且为只读计算属性，写须直接 `putPrefBoolean`）；`defaultToRead` 无缓存字段（直读写 pref）；`fontScale` 宿主无此设置（E-Ink 自管键，0 = 未设置） |
| ReaderEngineImpl | **回调单轨**：`ReadBook.CallBack` 全量接口（含 LayoutProgressListener 的 `onLayoutException`/`cancelSelect`），单接口实现单轨注册；排版写入 `ReadBookConfig` 直写 + `save()` + `upStyle()`（无护栏）；`CacheBook.start(ctx, book, start, end)` 非 suspend；`setAutoReadIntervalSec` 直写 `ReadBookConfig.autoReadSpeed` + `putPrefInt` |
| ReaderPageSnapshotMapper | `ImageColumn` 无 `book` 字段（引擎绘制读全局 `ReadBook.book`）——loader 闭包按映射时刻全局书取图；`fontVariationSettings` 为 API 26 而 minSdk 21——门控 `if (SDK_INT >= 26)` |
| SearchEngineImpl | 宿主 `SearchModel(scope, callBack)` 五方法回调（无 progress 回调，模块进度提示自动保持初值）；`trimIntro` 是 `SearchBook` 成员函数；`searchKeywordDao` 无按词删除（`get(word)` 再 `delete(it)`） |
| ChangeSourceEngineImpl | `migrateTo(newBook, toc)` 两参（无 replace/converter 形参）；filter 两参 |
| CoverEngineImpl | **Glide 宿主**：无宿主 Coil 单例可挂——实现退化为仅设目标尺寸；0.2.0 起网络封面经模块传递的 OkHttp 抓取器开箱可用，防盗链封面仍回退占位（现行字节端口形态见 §4，Glide 宿主可完整表达） |
| BookshelfEngineImpl | 预缓存门槛键 `AppConfig.preDownloadNum`（非网关）；其余与通版一致 |
| TocEngineImpl / BookDetailEngineImpl | 零差异，通版直接可用 |

## 4. 兼容性注意事项

- **图片加载**：模块图片栈自闭环——自有 ImageLoader + 封面字节端口
  （`CoverEngine.fetchCoverBytes`）：宿主以自有管线返回封面字节（防盗链/
  书源请求头/地址规则解析/解密/持久缓存都在宿主侧），模块负责解码、按
  目标尺寸降采样、显示与内存缓存。任意图片栈宿主（Glide 等）都能经端口
  表达防盗链；无持久缓存的宿主实现每次冷启动重新抓取，建议自带文件缓存
  与失败冷却。阅读页内嵌插图不经此链路（页快照的 loader 闭包由宿主提供）。
- **排版协商与字体端口**：`ReaderEngine` 的 `styleCatalog` /
  `availableFonts` / `setFontFolder` / `headerFooterTypefaces` 四成员带
  默认实现，旧宿主零改动即降级（目录 null → 回落内置 17 参数基线，
  字体/字重/标题/页眉页脚设置行全部隐藏）。要启用这些设置，宿主最低
  要求：实现目录声明（参照本仓 `HostStyleCatalog`：逐参数声明可用性/
  值域/默认值/affectsLayout，值域与宿主排版设置 UI 同源）+ 字体文件夹
  枚举与 SAF 持久化（均 suspend、阻塞式，模块在 IO 上下文调用）。加粗
  能力由字重参数表达（body.weight / title.weight：0 常规 / 1 粗 / 2 细
  预设 + 100..900 自定义），端口无独立加粗键。
- **云端进度同步**：`ReaderEngine` 的 `syncCloudProgress` /
  `applyCloudProgress` 与回调 `onCloudProgressNewer` 均默认实现，旧宿主
  零改动降级（无同步行为，本地进度落库不受影响）。要启用须按
  `ReaderSyncTrigger` KDoc 的门槛矩阵复刻宿主 syncBookProgress /
  syncBookProgressPlus 行为，并走宿主自己的进度网关（本仓参照：
  `bridge/ReaderProgressSyncer`「一键全开」——宿主 Plus 子键恒视为开、
  仅主开关生效；配套 `GlobalSettings.syncReadingProgress` 开关，默认
  false = 未实现，属诚实降级）。
- **Compose 版本差**：模块按保守档编译（见 §0），宿主用更新栈时解析
  自动升版、模块代码运行于宿主版本。需要模块使用更新 API 时走 toml
  头部的升档协议，不在宿主侧适配。
- **SharedPreferences**：模块零自有存储——全部设置经 GlobalSettings
  端口。E-Ink 自有偏好（`readerTapZones` 整键 9 位编码、
  `pullDownBookmark`）以 `einkReaderTapZones` / `einkReaderPullDownBookmark`
  落宿主侧**专属** prefs 文件（本仓为 `eink_preferences`，键名历史继承）。
  勿落宿主默认 prefs 文件——被 DataStore MIGRATE_ALL_KEYS 迁移接管的默认
  文件进程启动即被整文件清空，自有键会随每次重启反复重置。
- **资源合并**：模块资源全部 `eink_` 前缀，与任何宿主零同名。
- **D8/R8 元数据警告**：AGP 8.x 宿主消费新 Kotlin metadata 的库时 D8 打
  warning（不阻塞 debug；release R8 需真机回归确认）。
- **ProGuard**：模块 `consumer-rules.pro` 随模块走；Compose/Coil 规则由
  各自 consumer 提供。

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
8. 门槛项（§0 有变动时）：minSdk 低档真机与旧 AGP 宿主构建复核。
