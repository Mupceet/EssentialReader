# E-Ink Compose 模块移植手册（:modules:eink → legado 系宿主）

把 `:modules:eink` 模块嵌入任意 legado 系（或同域阅读器）上游时按本手册
操作。文档随模块走、归档于 `contract/` 目录（宿主接入面的一部分）。

**文档分工**（各司其职，不互相重复）：

| 文档 | 内容 |
|---|---|
| **本文** | 移植流程：消费门槛、嵌入/消费步骤、适配维度盘点与差异存档、兼容注意、验证清单、升级协议 |
| `contract/README.md`（同目录） | 代码侧接入面地图：端口总表、跨界数据类型、装配时序、能力裁剪方言 |
| `gradle/libs.versions.toml` 头部（模块内） | 依赖版本语义、消费门槛依据、升档协议的**权威注释**——改任何依赖版本前先读它 |
| `build.gradle.kts` 版本注释（模块内） | 发版沿革与历史坐标（0.1.0 旧栈、oldstack/min21 孪生等） |
| 宿主参照实现 | EssentialReader 墨本主线（分支 `eink/port/md3/modules`）`app/src/main/java/io/legado/app/eink/`——现行最全实现（入口子类 + bridge 26 文件），逐文件对照移植 |

## 0. 消费门槛（动手前先对表）

模块依赖集整体钉「保守档」，单一坐标服务新旧宿主：模块按保守档编译、
POM 随保守版发布，新栈宿主解析时自动升到自己的更高版本（Gradle 取
max，二进制兼容；墨本主线 app 即此形态：BOM 2026.08.00 / Coil 3.6.2 /
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
直接框架调用，宿主 bridge 侧超 21 的调用自行 SDK 门控（参照墨本主线的
fontVariationSettings API 26 处理，§3.3）。

## 1. 架构与职责边界

```text
:modules:eink（模块 = 可整体复制的 E-Ink Compose 应用核心，零引擎依赖）
├─ contract/                        ★ 移植契约（本手册与接入面 README 在此）
│    EInkEngineRegistry（装配注册表）+ EInkHostActivity（入口模板基类，
│    宿主只实现 onInstallEngines / onExitToFullMode 两钩子，另有可选
│    UI 字体钩子 uiFontFamily）+ 必填端口 8 个（GlobalSettings 设置端口 +
│    Bookshelf / Search / Toc / BookDetail / ChangeSource / Cover /
│    Reader 业务端口，及各自伴生回调与结果类型）+ 可选端口 5 个
│    （AppUpdate / Marks / BookshelfGroup / BackupSync / PageTurnEffect）+
│    EngineHandles（不透明句柄）+ ReaderPageSnapshot（排版产物快照系）+
│    ReaderTextStyle / ReaderStyleCatalog（排版参数快照与协商目录）
├─ app/                             EInkKeyEventHub 按键枢纽 + EInkApp 根
│                                   Composable 与栈导航
├─ designsystem/ feature/ arch/ debug/ util/ res/
│                                   （模块内部实现与自包含资源，宿主不读）
├─ gradle/libs.versions.toml        ★ 模块自有版本目录（保守档钉版与升档
│                                   协议权威注释，经 einkLibs 挂载）
├─ build.gradle.kts                 AGP 9 形态
└─ build-agp8.gradle.kts            AGP < 9 宿主构建变体（宿主 settings 经
                                    buildFileName 选用，见 §2 步骤 3b）

app/.../eink/（宿主 = 入口子类 + 桥接层，移植时按目标引擎重写）
├─ EinkMainActivity.kt              入口子类（墨本主线约百行）：两必钩子 +
│                                   可选字体钩子 uiFontFamily + 可选前台
│                                   挂接（端口弹宿主 DialogFragment 时经
│                                   onStart/onStop 挂 EInkBridge）+ 退出
│                                   补偿（写回分流键、launcher 组件解析、
│                                   深浅色同步，见 §2 步骤 7）
└─ bridge/                          ★ 唯一需要重写的部分：端口实现 + 快照映射
     EInkBridge.kt          装配入口（Registry.install + 设置快照对齐）；
                            GlobalSettingsImpl、EinkLegacyPrefsStore（自有
                            偏好存储位）与前台宿主 Activity 挂接同在此文件
     *EngineImpl（12 个）    各端口实现（5 个可选端口随宿主能力取舍）
     快照映射与支撑          ReaderPageSnapshotMapper、HostStyleCatalog、
                            BookshelfUiMapper / Sorter / StyleMapper、
                            ReaderProgressSyncer / Policy、BackupSyncCheckPolicy、
                            PageTurnEffectCodes、ReaderStyleMutations、
                            ReaderTipFonts、ReaderChapterPager、EInkBookmarkState
```

「模块树零改动，只重写 bridge」成立：AGP < 9 宿主的构建栈差异也由宿主
settings 选用模块自带的 `build-agp8.gradle.kts` 消化（§2 步骤 3b），不
修改模块任何文件。职责三分（模块界面编排 / 宿主引擎管线 / 入口模板生命
周期）见 `contract/README.md` §1；各端口的实现契约（职责边界、调用
时机、线程、失败语义）以**接口 KDoc 为权威**。

## 2. 源码嵌入步骤（形态一）

1. **获取模块树**，两种等价形态：
   - **子模块（推荐）**：引用 eink/lib 独立分支，升级＝推进子模块指针后
     提交（墨本主线即此形态：`eink-lib/` 目录 + settings projectDir
     重定向）：
     ```bash
     git submodule add -b eink/lib https://github.com/Mupceet/EssentialReader.git eink-lib
     ```
     settings 侧 `include ':modules:eink'` 配
     `project(':modules:eink').projectDir = file('eink-lib/modules/eink')`，
     步骤 3 的 einkLibs 挂载路径相应为
     `eink-lib/modules/eink/gradle/libs.versions.toml`；
   - **手工复制**：整个 `modules/eink/` 目录（含 build.gradle.kts、
     build-agp8.gradle.kts、gradle/libs.versions.toml、docs、
     consumer-rules.pro）。legado-with-MD3 首移植线（EssentialReader 仓
     `md3/port/eink` 分支）即此形态；复制后模块与宿主同仓演进，升级须
     手工搬运（见 §6）。
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
3b. **模块构建适配（AGP < 9 宿主）**：宿主 settings 对模块工程改用随
   模块发行的 AGP8 构建变体，模块文件零修改：
   ```groovy
   project(':modules:eink').buildFileName = 'build-agp8.gradle.kts'
   ```
   变体与默认 `build.gradle.kts` 逐段同步，差异仅两处（plugins 增加
   `kotlin-android`、`jvmToolchain` 顶层化——嵌套形态是 AGP 9 内置
   Kotlin 专属 DSL）。compileSdk 35 / Java 17 由变体自带，宿主只需满足
   §0 门槛，无需改模块。
4. **app 依赖**：`implementation project(':modules:eink')`；另需补一条
   模块不会传递的依赖——`compose-runtime`（模块对它是 implementation，
   不外泄；`GlobalSettings.useDefaultCover` 的快照状态语义需要）。宿主
   本身已是 Compose 应用时天然已含，纯 View 宿主须显式补。图片栈零宿主
   义务：Coil 为模块 implementation 依赖（契约 `CoverEngine` 是纯
   Kotlin 字节端口），宿主 app 代码与依赖清单均不出现 Coil。
5. **编写宿主入口与桥接层**：入口写一个 `EInkHostActivity` 子类（实现
   `onInstallEngines()` 与 `onExitToFullMode(context)` 两钩子）；`bridge/`
   按 §1 清单逐端口实现，**范围按 §3.0 两档裁剪**（最小可用 = 8 必填
   端口，其余自动降级；完整对齐 = 再接 5 可选端口与协商/字体/同步成员），
   参照实现逐文件对照（见文首分工表）。可选覆写 `uiFontFamily()` 为
   E-Ink 界面提供全局 UI 字体（默认 null = 平台默认字体；组合内调用，
   可订阅宿主状态流实时生效），不覆写不影响移植。**覆写时返回族必须
   字重可解析**（约束与参照实现见 §4「UI 字体钩子的字重约束」），否则
   界面标题加粗会整体静默丢失。
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
   启动分流点 + 退出写回。已验证形态（由旧到新）：
   - legadoM-Ink（2026-08）：`themeMode == "4"`（枚举皮肤位兼作模式位），
     WelcomeActivity 分流；
   - legado-with-MD3 首移植（2026-08，`md3/port/eink`）：实验室开关
     `labEInkDisplay`，MainActivity.onCreate 分流；
   - develop@01ee1e956：`themeMode == "4"`，MainActivity.onActivityCreated
     首行分流，退出写回 "0"；
   - 墨本主线（现行，2026-10）：**两键分离**——`eInkMode` 布尔（「我的」
     页「墨水屏模式」开关 = 模式状态）与 `labEInkDisplay`（实验室开关，
     仅门控开关条目显隐）互相独立（App 启动一次性迁移继承旧值）；
     MainActivity.onCreate 启动路由检查后分流；退出只写回
     `eInkMode = false`，跳转经**当前启用的 launcher 组件**解析而非显式
     指向 MainActivity（图标别名切换后原组件被禁用，显式指向会失败），
     并先补偿一次系统深浅色同步（E-Ink 存续期间没有完整模式 Activity
     在同步该网关）。
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

## 3. 适配维度盘点与差异存档

历史移植的实测差异，反映**当时**的模块版本与宿主形态；方法名与语义以
当前契约 KDoc 为准（3.2 为活表，随墨本主线演进同步更新）。

### 3.0 移植范围与适配维度（新宿主动工前）

移植范围先定档，再动工：

| 档 | bridge 内容 | 降级面（不接即自动降级，非阻塞） |
|---|---|---|
| **最小可用** | 8 必填端口 + 入口两钩子 + 入口接线 | 无字体协商（回落内置 17 参数基线，字体/字重/页眉页脚设置行隐藏）；无云端进度同步；5 可选端口全缺席（更新/书签笔记/书架分组/备份同步/翻页特效入口整体消失）；uiFontFamily 平台默认字体 |
| **完整对齐** | + 5 可选端口 + 排版协商目录 + 字体端口五成员 + 云端进度同步 + uiFontFamily + 自有偏好专属文件与备份分流 | 参照实现 = 墨本主线 bridge（§1 清单全集） |

新宿主动工前按下表逐维度盘点（每行来自下方存档的实测差异，查明一项
填一项，写 bridge 时对号入座；非 legado 血统的同域阅读器同样适用，
只是无存档可对照）：

| # | 适配维度（影响面） | 动工前查明 | 已知形态（→ 存档） |
|---|---|---|---|
| 1 | 阅读回调轨制（ReaderEngineImpl） | ReadBook 回调是单轨全量还是业务/渲染双轨 | 单轨全量接口含 onLayoutException/cancelSelect（3.3）↔ 双轨拆分、适配器同实现两轨注册，业务轨仅 4 方法（3.2） |
| 2 | 排版设置写路径（ReaderEngineImpl / ReaderStyleMutations） | 排版参数写入是否过网关护栏 | 直写 + save() + upStyle() 无护栏（3.3）↔ ReadStyleGateway mutation 逐键提交；比较误中须 `.let { it == 1 }` 类规避（3.2） |
| 3 | 设置存取面（GlobalSettingsImpl） | 每个设置键的读写通道与真实键名 | AppConfig/putPref 直读写，键名漂移逐键核对（autoRefreshBook 实为只读计算属性 PreferKey.autoRefresh、defaultToRead 无缓存字段）↔ 设置网关 + Koin 分域（3.2）↔ 混合例外（fontScale 同步快照 + remove 键语义） |
| 4 | 搜索 API（SearchEngineImpl） | 搜索会话形态、进度回调、搜索范围存储位 | SearchModel 五方法回调无 progress（3.3）↔ 多 onSearchProgress/onSourceStatesChanged（3.1）↔ SearchBooksUseCase 返回 Flow\<SearchRunEvent\> + 范围读 local_ui_status DataStore（3.2） |
| 5 | 换源迁移签名（ChangeSourceEngineImpl） | migrateTo 参数个数与候选 filter 参数 | 两参（3.3）↔ filter 三参 (name,author,kind)（3.1）↔ 再增 replaceEnableDefault/chineseConverterType（3.2） |
| 6 | 缓存启动（ReaderEngineImpl.startCache） | CacheBook.start 是否 suspend | 非 suspend 直调（3.3）↔ suspend（构造 CacheDownloadRequest 走非 suspend 重载，3.2） |
| 7 | 图片栈（CoverEngineImpl） | 宿主图片框架与单例、有无持久缓存 | Coil 挂宿主单例 ↔ Glide 宿主无 Coil 可挂（3.3）；0.2.0 起字节端口 + 模块传递 OkHttp 抓取器后各栈等价可完整表达（§4） |
| 8 | 排版产物结构（ReaderPageSnapshotMapper） | TextLine/TextPage/ImageColumn 字段集、排版函数位置 | 扩展字段 extraLetterSpacing/wordSpacing/isHtml（3.1）、searchResult 元素 TextBaseColumn（3.1）、ImageColumn 无 book 字段→loader 闭包按映射时刻全局书取图（3.3）、排版函数拆 TextChapterLayout.kt（3.1） |
| 9 | minSdk 与 API 门控（映射器与引擎实现） | 宿主 minSdk；bridge 侧超 21 调用清单 | 模块自身无 >21 调用；bridge 侧自行 SDK 门控（fontVariationSettings API 26 例，3.3） |
| 10 | 自有偏好存储位（EinkLegacyPrefsStore） | 宿主默认 prefs 文件是否被 DataStore MIGRATE_ALL_KEYS 迁移接管；宿主备份是否需带上 E-Ink 键 | 被接管 → 自有键必须专属文件（§4）；备份导出/恢复随宿主并按设备分流（路径类键跨设备跳过，3.2 增量） |
| 11 | 入口接线（入口子类） | 配置位形态、分流点、退出写回链 | 四种已验证形态见 §2 步骤 7 |
| 12 | 可选端口能力（install 装配） | 宿主有无更新检查/书签笔记/书架分组/云端备份同步/EPDC 翻页特效能力 | 决定 5 可选端口注册与否；不注册 = 入口消失的诚实降级（裁剪方言总表见 contract/README §5） |
| 13 | UI 字体（入口子类 uiFontFamily） | 宿主有无界面字体设置流、安装/清除流程 | 覆写须守 §4 字重约束；与宿主「外观 → 字体」同键同流程可双向生效（3.2） |
| 14 | 宿主弹层挂接（EInkBridge / ReaderEngineImpl） | 端口是否需弹宿主 DialogFragment（段评半屏 WebView 等） | 前台宿主 Activity 挂接模式 attachHostActivity，退后台静默放弃（3.2 增量） |

### 3.1 EssentialReader → legadoM-Ink（View 宿主首移植，2026-08 探明）

| 桥接文件 | 差异与处置 |
|---|---|
| SearchEngineImpl | `SearchModel.CallBack` 多 `onSearchProgress` 与 `onSourceStatesChanged`——补空 override |
| ChangeSourceEngineImpl | `searchBookAwait` filter 三参 `(name, author, kind)`——仅改一处 lambda |
| ReaderEngineImpl | 排版函数拆至 `TextChapterLayout.kt`（对外同名）；`TextPage.searchResult` 元素 `TextBaseColumn`（映射器走 else 分支） |
| ReaderEngineImpl / TocEngineImpl | `ChapterProvider.srcReplace*` 语义不同（未使用）；`splitLongChapter` 默认值翻转 |
| EInkBridge | 目标仓需提供 `isEInkPureMode` 系列配置 |
| ReaderPageSnapshotMapper | `TextLine` 多 `extraLetterSpacing/wordSpacing/isHtml`——快照契约消化 |

### 3.2 legado-with-MD3 家族（Koin/MD3 宿主；首移植实测 + 墨本主线现行）

首移植线 = EssentialReader 仓 `md3/port/eink` 分支（模块整树手工复制
形态，2026-08/09）；墨本主线 = `eink/port/md3/modules` 分支（eink-lib
子模块形态，2026-10 起，现行参照）。核心差异与处置：

| 桥接文件 | 差异与处置 |
|---|---|
| ReaderEngineImpl | **回调双轨**：业务/渲染拆两接口，适配器同实现、两轨注册（业务轨 ReadBook.CallBack 仅 4 方法）；排版写入必须走 `ReadStyleGateway` mutation + 显式 `save()`（架构护栏禁直写，比较也会误中，用 `.let { it == 1 }` 规避）；TitleSize 与 TextSize 同值写入 |
| ReaderEngineImpl (startCache) | `CacheBook.start` 为 suspend——改构造 `CacheDownloadRequest` 走非 suspend 重载 |
| SearchEngineImpl | `SearchModel` 已删——走 `SearchBooksUseCase.execute(...): Flow<SearchRunEvent>`；搜索范围读 local_ui_status DataStore |
| ChangeSourceEngineImpl | `migrateTo` 需补 `replaceEnableDefault`/`chineseConverterType` 两参；filter 三参 |
| EInkBridge | 全部设置经网关（OtherSettings/ReadSettings/DownloadCache/Cover/ChangeSource/ThemeSettings/BackupSettings Gateway + Koin；chineseConverterType 转发 ReadSettingsGateway 与完整模式同键，supportsChineseConverter 声明 true；setAppFont/clearAppFont/setAppFontPreset(0) 走 ThemeSettingsGateway + AppFontStore 与「外观 → 字体」同流程同键（选择/清除串行纪律 launchAppFontJob）；hasCustomAppFont/currentAppFontListPath 反显走 appFontPath 快照 + eink prefs 源 path/副本路径匹配（完整模式侧改动后失配为 null））；fontScaleSetting 仍走同步快照 |
| PageTurnEffectEngineImpl | 掌阅固件 EPDCDevice 反射（水波纹翻页，effect 编码见 PageTurnEffectCodes）：能力 = 品牌 iReader **且** 反射探测通过，任一不过档位行不渲染；档位键 `einkPageTurnRippleMode`（off/slow/standard/fast）落 EinkLegacyPrefsStore |

**墨本主线现行增量**（相对首移植线，2026-10）：

- **接入形态**：eink-lib 子模块（§2 步骤 1 推荐）；bridge 全量重写为
  §1 所示现行结构（入口子类 + bridge 26 文件）。
- **入口两键制与退出补偿**：`eInkMode`（模式状态）与 `labEInkDisplay`
  （条目显隐门控）分离 + 一次性迁移；退出写回 eInkMode、经启用中
  launcher 组件解析跳转、先补偿系统深浅色同步（§2 步骤 7 第四形态）。
- **MarksEngine 契约 v2**：原 ReaderSelectionEngineImpl（划选批注）并入
  MarksEngineImpl——书签/笔记/划选统一端口，笔记按 id 更新
  （updateMarkingNote）、书签载荷含段落边界语义；能力位按特性全有全无。
- **BackupSyncEngineImpl + BackupSyncCheckPolicy**：云端备份启动检查/
  标记/恢复管线宿主侧（backupSync 同语义）；`syncReadingProgress`
  （「我的」页开关）经 BackupSettingsGateway 转发宿主「同步阅读进度」
  主键，写时带宿主设置页同款父子联动。
- **自有偏好备份分流**：EinkLegacyPrefsStore.exportAll/importAll 随宿主
  备份导出恢复（合并写入不 clear）；路径类键（最近字体历史、字体反显
  源/副本）跨设备恢复跳过，与宿主 deviceLocalPreferenceKeys 同语义。
- **前台宿主挂接**：EInkBridge.attachHostActivity（入口 onStart/onStop
  挂载/卸载）——段评半屏 WebView 等 DialogFragment 的事务宿主与生命
  周期作用域来源；无前台活动时相关分派静默放弃（PageTurnEffect 旋转
  读数同源）。

### 3.3 develop@01ee1e956（legado-with-MD3 旧栈，2026-09-05 全量回放实测）

宿主形态：AGP 8.13.2 / Kotlin 2.3.0 / Groovy 构建脚本 / compileSdk 36 /
Java 17 / minSdk 21 / 无 Compose 无 Coil（图片栈 Glide）/ 无设置网关与
Koin / 无 AppConfigStore。模块构建适配当时为手工改 build.gradle.kts，
现由随模块发行的 `build-agp8.gradle.kts` 变体一步替代（§2 步骤 3b）。

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
  `availableFonts` / `setFontFolder` / `loadFontTypeface` /
  `headerFooterTypefaces` 五成员带默认实现，旧宿主零改动即降级（目录
  null → 回落内置 17 参数基线，字体/字重/标题/页眉页脚设置行全部隐藏）。
  要启用这些设置，宿主最低要求：实现目录声明（参照墨本主线
  `HostStyleCatalog`：逐参数声明可用性/值域/默认值/affectsLayout，值域
  与宿主排版设置 UI 同源）+ 字体文件夹枚举与 SAF 持久化（均 suspend、
  阻塞式，模块在 IO 上下文调用）+ 字体文件→Typeface 加载（不实现时
  选择列表预览回落平台默认字体，选择行为不受影响）。加粗
  能力由字重参数表达（body.weight / title.weight：0 常规 / 1 粗 / 2 细
  预设 + 100..900 自定义），端口无独立加粗键。
- **UI 字体钩子的字重约束**：覆写 `EInkHostActivity.uiFontFamily()` 时，
  返回族必须是字重可解析的 FontList 形态（如 `FontFamily(Font(file =
  ...))` 单 Normal 声明）；**禁止** `FontFamily(typeface)` 直接包装已加载
  的 `android.graphics.Typeface`——LoadedFontFamily 对所有字重请求原样
  返回该 Typeface，不进 Compose 字重合成（synthesizeTypeface），模块
  排版系统 display/headline/title/label 共 12 个 Bold 样式会静默降为
  Regular（界面标题全不加粗），且模块侧无法从 FontFamily 返回值探测或
  修复。正确形态下 Bold 请求经 `Typeface.create(typeface, weight,
  italic)` 合成假粗（API<28 走 BOLD 样式），与平台默认字体的加粗渲染
  同机制。经 SAF content:// 取字体时勿用 `Font(fileDescriptor)`（实际
  读取在 Compose 解析期，PFD 关闭即加载失败——复制到文件再走
  Font(file)）。参照实现：墨本主线 `ui/theme/ThemeComponents.kt` 的
  `loadCustomFont`（含回归锚点单测 `LoadCustomFontTest`）；模块内字体
  设置页预览走自身双字重族（`FontPreviewFamilies`），不经宿主此钩子，
  无此约束。
- **云端进度同步**：`ReaderEngine` 的 `syncCloudProgress` /
  `applyCloudProgress` 与回调 `onCloudProgressNewer` 均默认实现，旧宿主
  零改动降级（无同步行为，本地进度落库不受影响）。要启用须按
  `ReaderSyncTrigger` KDoc 的门槛矩阵复刻宿主 syncBookProgress /
  syncBookProgressPlus 行为，并走宿主自己的进度网关（墨本主线参照：
  `bridge/ReaderProgressSyncer`「一键全开」——宿主 Plus 子键恒视为开、
  仅主开关生效；配套 `GlobalSettings.syncReadingProgress` 开关，默认
  false = 未实现，属诚实降级）。
- **Compose 版本差**：模块按保守档编译（见 §0），宿主用更新栈时解析
  自动升版、模块代码运行于宿主版本。需要模块使用更新 API 时走 toml
  头部的升档协议，不在宿主侧适配。
- **SharedPreferences**：模块零自有存储——全部设置经 GlobalSettings
  端口。E-Ink 自有偏好（`readerTapZonesEncoding` 端口字符串整键原样
  存取（9 位编码，编解码在模块侧）、`pullDownBookmark`、
  `pageTurnRippleMode`、`recentFontPathsEncoding`）以
  `einkReaderTapZones` / `einkReaderPullDownBookmark` 等键落宿主侧
  **专属** prefs 文件（墨本主线为 `eink_preferences`，键名历史继承）。
  勿落宿主默认 prefs 文件——被 DataStore MIGRATE_ALL_KEYS 迁移接管的默认
  文件进程启动即被整文件清空，自有键会随每次重启反复重置。自有键随
  宿主备份导出/恢复时**按设备分流**：路径类键（最近字体、字体反显
  源/副本）异机必悬空，跨设备恢复跳过、同机恢复应用（参照墨本主线
  `EinkLegacyPrefsStore.exportAll/importAll` 与宿主 deviceLocalPreferenceKeys
  同语义）。
- **宿主弹层挂接**：端口实现需要弹宿主 DialogFragment（段评半屏
  WebView 等）时，事务宿主与生命周期作用域取自「当前在前的 E-Ink 宿主
  Activity」——参照墨本主线 EInkBridge.attachHostActivity（入口
  onStart/onStop 挂载/卸载），退后台后相关分派静默放弃。宿主无此类
  弹层需求时可整体省略。
- **资源合并**：模块资源全部 `eink_` 前缀，与任何宿主零同名。
- **D8/R8 元数据警告**：AGP 8.x 宿主消费新 Kotlin metadata 的库时 D8 打
  warning（不阻塞 debug；release R8 需真机回归确认）。
- **ProGuard**：模块 `consumer-rules.pro` 随模块走；Compose/Coil 规则由
  各自 consumer 提供。

## 5. 移植验证清单

1. `./gradlew :modules:eink:assembleDebug` + 宿主编译/assemble（任务名按
   宿主 flavor 结构替换，如 `:app:compileAppDebugKotlin`；墨本主线的
   `verifyConfigArchitecture` 等专属门禁不适用于其它上游）。
2. 冷启动进入书架：列表/网格切换、翻页、长按详情。
3. 搜索→详情→加入书架→阅读：全链路。
4. 阅读器：翻页/菜单/排版调参（字号/边距/缩进即时重排）/缓存面板/自动翻页。
5. 目录页：跳章写回进度、缓存标记。
6. 换源：跨源搜索→应用换源→返回阅读页续读。
7. 回归重点：ReaderEngine 的 CallBack 转发链路（注册/注销/进度落库）。
8. 界面字体（若宿主覆写 uiFontFamily）：选中自定义字体后对比系统默认
   态——界面标题（「我的」条目主标题、各页顶栏标题）加粗仍在；字体
   设置页预览标题与选中行名称加粗仍在（字重约束见 §4）。
9. 门槛项（§0 有变动时）：minSdk 低档真机与旧 AGP 宿主构建复核。
10. 可选端口与能力位核对：对照 `EInkEngineRegistry.install` 签名逐参
    确认已注册的可选端口（升级后新增参数默认 null，漏传即对应入口
    静默消失）；能力位（supportsChineseConverter 等）与宿主实际能力
    一致，不以空实现伪造。
11. 双模式设置往返：完整模式与 E-Ink 两侧互改共享键设置（封面、音量键
    翻页、界面字体等）——对侧进入时快照对齐、在场时状态流订阅两条
    路径行为一致。
12. 备份恢复含 E-Ink 自有键：导出→恢复后点击区域/下拉书签/水波纹档位/
    最近字体回得来；跨设备恢复时路径类自有键不覆盖本机。
13. 入口回路：分流开关往返进入退出；退出到完整模式后再进入；宿主支持
    图标别名/多入口时，退出跳转经当前启用 launcher 组件可达（§2 步骤 7
    第四形态）。

## 6. 模块升级与再同步（已嵌入宿主）

- **子模块形态升级**：在 `eink-lib/` 检出内 `git fetch` 后 checkout 到
  eink/lib 目标提交，外层提交指针变更。⚠ 指针推进 ≠ 子模块工作区跟进：
  外层仓库经克隆/推送转移到新检出后，子模块内容须重新 fetch + 归位
  （`git submodule status` 出现 `+` 前缀即未同步，构建前必查）；
  eink-lib 检出常处 detached HEAD，在检出内直接提交后须 ff 归位
  eink/lib 分支，再推进外层指针。
- **手工复制形态升级**：无指针可推——按 eink/lib 提交逐批搬运模块树
  （冲突集中在模块内部实现；contract/ 变更按下两条逐项核对）。
- **install() 签名核对**：模块升级新增的端口参数（可选端口）默认
  null——整体替换语义不保留旧装配，未显式传入则对应 UI 入口**静默
  消失**。每次升级先 diff `EInkEngineRegistry.install` 签名，再决定
  是否补注册。
- **契约演进保证**：既有端口的新增成员一律带默认实现（旧 bridge 零
  改动即降级），降级语义由模块侧契约兼容守护测试钉死。升级后宿主
  bridge 编译通过 ≠ 新能力已接通——按 §5 第 10 条复核降级面是否需要补。
- **双构建文件纪律**：模块 `build.gradle.kts` 与 `build-agp8.gradle.kts`
  逐段同步（差异仅两处，见 build-agp8 头注）；模块内构建相关改动两个
  文件一起改。
- **手册回写**：完成一次新移植或宿主差异变化后，把实测差异回写 §3
  对应存档（标明宿主与时间），§3.0 维度盘点表随之增补。
