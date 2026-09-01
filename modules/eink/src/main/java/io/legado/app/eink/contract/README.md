# contract/ — 宿主接入面（端口契约）

本目录是 `:modules:eink` 对宿主的**全部**外部面：宿主需要实现的接口、
需要构造或读写的数据类型、需要调用的装配引导都在这里。目录之外全部是
模块内部实现（设计系统、feature 屏幕与 ViewModel、导航），接入方不需要
阅读。完整移植流程、各上游差异表与环境事项见
`modules/eink/docs/eink-porting.md`；本 README 是代码侧的接入面地图。

## ① 端口契约（宿主实现）

| 类型 | 职责 | 模块内消费方 |
|---|---|---|
| `BookshelfEngine` | 书架书籍流 + 单本目录刷新管线 + 预缓存泵联动 | 书架页 |
| `BookDetailEngine` | 书籍查找链 + 目录预取 + 加/移出书架 | 详情页 |
| `SearchEngine`（含 `SearchSession` / `SearchSessionCallback`） | 搜索历史 + 多源搜索会话（回调式） | 搜索页 |
| `TocEngine`（含 `TocFetchResult`） | 书籍解析 + 目录联网拉取 + 进度写回 | 目录页 |
| `ChangeSourceEngine` | 跨源搜索 + 换源迁移 + `bookChanged` 事件 | 换源页 |
| `CoverEngine` | 封面加载策略（默认封面开关 + Coil 请求选项） | 封面组件 |
| `ReaderEngine`（含 `ReaderEngineCallback` / `ReaderBookSnapshot` / `ReaderTipSpec` / `BookPrepResult`） | 阅读会话状态机 + 排版参数写入 + 翻页 + 页快照读取 | 阅读页 |
| `GlobalSettings` | 宿主全局设置视图（并发数、启动开关、音量键、抗锯齿、字体缩放等） | 多屏 + 「我的」页 |
| `EInkKeyEventHub` | 入口 Activity 按键转发枢纽（宿主实例化并 dispatch） | 阅读页音量键 |

## ② 跨界数据类型（宿主构造 / 读写）

- **`EngineHandles.kt`** — `BookHandle` / `SourceHandle` / `SearchResultHandle` /
  `SearchResultRef`：引擎实体的不透明句柄。宿主 bridge 包装真实实体，
  模块只持有、回传，不解读。
- **`EInkPageSnapshot.kt`** — 排版产物快照（含 `EInkSnapshotLine` /
  `EInkImageSlot` / `ReaderPaintSpec` / `ReaderShadowSpec`）：宿主把引擎
  TextPage 映射而来（本仓见宿主 `ReaderPageSnapshotMapper`），模块自持
  画布据此绘制。坐标系、所有权规则见文件头 KDoc。
- **`ReaderTextStyle.kt`** — 排版参数快照：设置面板编辑 →
  `ReaderEngine.applyStyle` 整体写入宿主配置；`currentStyle()` 读回。
  字段单位与编辑区间见文件头 KDoc。
- **各页 UiModel** — `BookshelfItemUiModel` / `BookDetailUiModel` /
  `SearchBookUiModel` + `SearchHistoryUiModel` / `TocBookUiModel` +
  `ChapterUiModel` / `ChangeSourceBookUiModel` + `ChangeSourceResultUiModel`：
  宿主把实体映射为稳定展示快照，计算（作者清洗、未读数、简介 trim 等）
  在映射期一次完成，不进组合期。

## ③ 装配与引导（宿主调用）

- **`EInkEngineRegistry`** — 端口注册表：入口 Activity.onCreate 调用
  `install(...)` 注册全部实现，必须早于任何 E-Ink Composable 组合
  （VM 构造期取用）。未注册的端口被访问时会抛出指名端口与修复入口的
  IllegalStateException。
- **`EInkSettings`** — 模块自有界面偏好的 SharedPreferences（随模块走，
  不占宿主配置）；`attach(context)` 已折叠进宿主装配模板
  `EInkBridge.install(context)`，宿主入口无需单独调用。

## 宿主接入清单（速览）

1. Gradle：宿主 app `implementation project(':modules:eink')`。
2. 入口 Activity.onCreate：`EInkBridge.install(this)`（内部完成
   `EInkSettings.attach` + `EInkEngineRegistry.install` + 封面开关与
   宿主设置快照对齐）。
3. setContent：`EInkTheme(darkTheme = …) { EInkApp(onExitToFullMode = …) }`。
   `EInkApp` / `EInkTheme` 不在本目录——它们是模块 UI 的根入口（分别位于
   模块 `app/` 与 `designsystem/theme/`），接线方式见 porting 文档 §2。
4. 按键：`onKeyDown` / `onKeyUp` 先经
   `EInkEngineRegistry.keyEventHub.dispatch(event)`，未消费再交还系统。
5. 字体缩放：`attachBaseContext` 应用 `GlobalSettings.fontScaleSetting`
   （写入后需 recreate 入口才生效，语义见其 KDoc）。
6. Manifest 注册入口 Activity（configChanges 等见 porting 文档 §2）。

宿主侧模板代码（本仓）：`app/src/main/java/io/legado/app/eink/`
（`EinkMainActivity.kt` + `bridge/` 九个文件）。
