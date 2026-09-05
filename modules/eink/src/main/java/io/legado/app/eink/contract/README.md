# contract/ — 宿主接入面（端口契约与实现指南）

本目录是 `:modules:eink` 对宿主的**全部**外部面：宿主需要实现的接口、
需要构造或读写的数据类型、需要调用的装配引导都在这里。目录之外全部是
模块内部实现（设计系统、feature 屏幕与 ViewModel、导航），接入方不需要
阅读。完整移植流程、各上游差异表与环境事项见
`modules/eink/docs/eink-porting.md`；本文是代码侧的接入面地图与实现指南。

## 1. 架构总览

```text
宿主 app
  ├─ 入口子类（继承模块 EInkHostActivity，实现 2 个钩子）
  └─ bridge/（本仓宿主参照实现：app/src/main/java/io/legado/app/eink/bridge/）
        │ 实现下方全部端口接口，经 EInkEngineRegistry.install 注册
        ▼
:modules:eink
  ├─ contract/        ← 本目录：宿主实现的端口 + 跨界数据类型 + 装配引导
  ├─ app/             EInkHostActivity 入口模板 + EInkApp 根 Composable
  ├─ designsystem/    E-Ink 设计系统（theme/control/pager/refresh/widget）
  ├─ feature/         全部 Screen + ViewModel（模块保留全部界面编排）
  └─ res/             自包含资源（全部 eink_ 前缀，与宿主零同名）
```

职责三分原则：

| 层          | 保留什么                                    |
|------------|-----------------------------------------|
| 模块 feature | 界面编排：加载/错误状态机、翻页交互、调参防抖、搜索合并去重排序、刷新并发调度 |
| 宿主 bridge  | 引擎管线：书籍解析落库、目录/正文网络拉取、排版与分页、缓存、进度迁移     |
| 入口模板基类     | 生命周期编排：装配时机、字体缩放、启动清理、直达阅读、主题、按键分发      |

## 2. 接入流程（七步）

1. **Gradle**：宿主 app `implementation project(':modules:eink')`（或等价
   的二进制依赖；版本栈要求见 porting 文档 §4）。
2. **实现端口**：为本目录 §3 表中的每个接口写宿主实现（本仓参照：
   `app/src/main/java/io/legado/app/eink/bridge/`，逐文件对照移植）。
   每个接口的 KDoc 是实现契约的权威说明——职责边界、调用时机、线程
   约定、失败语义都写在方法注释里。
3. **写装配入口**：一个无参函数（本仓为 `EInkBridge.install()`），调用
   `EInkEngineRegistry.install(...)` 注册全部实现，并做一次宿主设置快照
   对齐（本仓：封面开关快照）。
4. **写入口子类**：继承本目录的 [EInkHostActivity]（入口模板基类），
   实现两个钩子：
   ```kotlin
   class EInkMainActivity : EInkHostActivity() {
       override fun onInstallEngines() = EInkBridge.install()
       override fun onExitToFullMode(context: Context) { /* 关分流开关 + CLEAR_TASK 跳完整模式 */ }
   }
   ```
5. **Manifest**：注册入口子类（无桌面图标，由宿主流入点进入）：
   ```xml
   <activity
       android:name=".eink.EInkMainActivity"
       android:configChanges="locale|layoutDirection|orientation|screenSize|smallestScreenSize|screenLayout|uiMode"
       android:windowSoftInputMode="adjustResize" />
   ```
   `uiMode` 必须在 configChanges 中（主题跟随系统深浅色不重建，靠
   LocalConfiguration 更新驱动重组）。
6. **流入点接线**：按宿主启动逻辑分流（本仓：实验室开关 +
   MainActivity onCreate 分流；其它上游形态见 porting 文档 §2 步骤 7）。
7. **验证**：porting 文档 §5 的验证清单（冷启动、搜索→详情→加架→阅读
   全链路、目录跳章、换源、阅读调参即时重排）。

## 3. 端口契约总表（宿主实现）

| 接口                   | 职责                 | 关键实现义务                                             |
|----------------------|--------------------|----------------------------------------------------|
| `GlobalSettings`     | 模块全部设置项的唯一出入口      | 写入语义三档（fire-and-forget / 快照状态 / attach 期），见接口 KDoc |
| `BookshelfEngine`    | 书架流、目录批量刷新管线、预缓存联动 | 刷新单本书不抛异常；`lastReadBookUrl` 主线程同步单行查询              |
| `SearchEngine`       | 搜索历史 + 多书源搜索会话     | 回调事件顺序约定见 `SearchSessionCallback`；搜索范围解析在宿主侧       |
| `TocEngine`          | 目录页：书籍解析、目录拉取、进度写回 | 拉取失败经 `TocFetchResult` 返回，不抛异常                     |
| `BookDetailEngine`   | 详情页：查找链、目录预取、书架操作  | 预取静默失败（`Skipped`）；句柄可能被预取/重定向更新                    |
| `ChangeSourceEngine` | 跨源搜索 + 换源迁移        | 成功后重载会话并发射 `bookChanged`；进度迁移完整                    |
| `CoverEngine`        | 封面请求策略（防盗链/请求头/尺寸） | 宿主图片栈知识唯一出口；`ImageRequest.Builder` 配置块             |
| `ReaderEngine`       | 阅读会话状态机 + 排版引擎转发面  | 最重的端口：回调注册时序、排版快照读写、页快照映射，逐方法见 KDoc                |

## 4. 跨界数据类型（宿主构造/映射）

- **各页 UiModel**（`*UiModel` / `*UiModels` 文件）：宿主把引擎实体映射
  为稳定展示快照。三条义务：**预计算**（作者清洗、封面挑选、未读数、
  简介清洗在映射期完成，模块组合期零计算）；**稳定**（全基元/不可变
  字段）；**无引擎类型**（不携带宿主实体，引擎身份走句柄）。
- **`EngineHandles.kt`** — `BookHandle` / `SourceHandle` /
  `SearchResultHandle`：引擎实体的不透明句柄。宿主 bridge 包装真实实体，
  模块只持有回传，不解读；实体被替换时的句柄语义见各端口方法 KDoc。
- **`ReaderTextStyle.kt`** — 排版参数快照：设置面板编辑 →
  `ReaderEngine.applyStyle` 整体写入；字段单位与编辑区间见文件头 KDoc。
- **`ReaderPageSnapshot.kt`** — 排版产物页快照：宿主把引擎排版结果映射
  而来（渲染侧唯一职责），模块自持画布绘制。坐标原样拷贝、构建后
  不可变、画笔规格只含测量耦合参数。

## 5. 通用约定

- **装配时序**（入口模板基类已固化，宿主无需关心）：attachBaseContext
  首行 `onInstallEngines()` → fontScale 包装（经 GlobalSettings 同步读）
  → onCreate：启动清理（IO 协程）→ 直达阅读解析（主线程同步）→
  setContent 组合。任何端口读取都晚于装配。
- **失败模式**：端口未注册即被访问时，registry 抛出的
  IllegalStateException 会指名缺失的端口与修复入口。
- **线程约定**：suspend 方法在调用方协程上下文执行；回调线程不限定
  （模块自行切主线程）；标注「主线程同步」的少数方法（如
  `lastReadBookUrl`）在宿主实现中保持单查询轻量。
- **命名**：契约面禁用**非共识**缩写（如 `dur`、legado 式 `up` 前缀、
  含义模糊的 `primary`）；领域/行业共识缩写保留（`Toc`、`Sec`、
  `Url`、`Id` 等）。新增端口成员沿用此规则。
