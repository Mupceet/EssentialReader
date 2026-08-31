# E-Ink 阅读页渲染叶子反转（页面快照模型）设计

- 日期：2026-08-31
- 分支：`md3/port/eink`
- 状态：已评审（设计对话定稿），待实施
- 范围：仅阅读界面渲染叶子；排版引擎、数据编排、端口整形均不在本期

## 1. 背景与动机

`modules/eink` 是自 EssentialReader 移植而来的 E-Ink Compose 应用核心，移植契约为
「模块树零改动，只重写桥接层」（见 EssentialReader 仓 `docs/eink-porting.md`）。
本仓已是该模块的活跃演进地（DS v2、fontScale 设置页等均在本仓先落地）。

阅读范围当前的移植痛点按两次实测移植（legadoM-Ink、legado-with-MD3）的差异表分类：

| 差异点 | 两次移植是否复现 | 能否通过模块迁移消除 |
|---|---|---|
| ReaderPageCanvas（TextLine 字段漂移 → 渲染已知限制） | 是 | 可以 |
| 回调双轨适配（CallBack / ReaderRenderCallback） | 是 | 否（宿主 API 形状决定） |
| 排版写入路径（ReadStyleGateway vs 直写） | 是 | 否（宿主护栏决定） |
| CacheBook suspend、searchBookAwait filter 三参等 | 是 | 否（宿主 API 决定） |
| prepareBookData 编排管线 | 否（两次零差异） | 能下沉但无实证收益 |

结论：渲染叶子（`:app` 内 `eink/reader/ReaderPageCanvas.kt`）是唯一「模块本可自持
却外包给宿主」的部分，且其宿主实现因上游 TextLine 字段漂移产生渲染分叉（移植文档
明示为「已知限制」）。本次将其反转收编进模块。

## 2. 决策记录

1. **核心动机 = 缩小移植面**：换上游移植时，bridge 需重写的宿主面更小、渲染正确性
   收敛到模块单点。
2. **排版引擎（ChapterProvider/TextChapterLayout/TextLine 等约 5000 行）留宿主**：
   宿主 View 阅读器（ReadBookRouteScreen → ReadView）仍是其活跃消费方，模块 fork
   会造成每上游双份维护与排版结果漂移；中立共享模块抽取依赖 Track-D 式出站解耦，
   列为远期，不在本期。
3. **本仓为模块主仓**：本次契约演进（页面内容模型、渲染接线方式）以本仓为准；
   EssentialReader 不回流。
4. **只做方案 A**（渲染叶子反转）；数据编排下沉（方案 B）与 ReaderEngine 端口整形
   （方案 C）已否决。

## 3. 契约与数据模型

删除 `EInkPageContent` 不透明句柄接口，改为模块自有的不可变页面快照。
新文件 `modules/eink/.../engine/EInkPageSnapshot.kt`：

```kotlin
@Stable class EInkPageSnapshot(
    val title: String,          // 章节标题（页眉/状态用，同现状 page.title）
    val readProgress: String,   // 进度文本（同现状 page.readProgress）
    val titleSpec: ReaderPaintSpec,     // 标题画笔规格
    val contentSpec: ReaderPaintSpec,   // 正文画笔规格
    val lines: List<EInkSnapshotLine>,
    val images: List<EInkImageSlot>,
)

@Stable class EInkSnapshotLine(
    val baseY: Float,           // TextLine.lineBase
    val isTitle: Boolean,       // 选择哪套画笔规格
    val chars: CharArray,       // TextColumn.charData 扁平化
    val x: FloatArray,          // 列起点（已含 API35 letterSpacing 半格补偿）
)

class EInkImageSlot(
    val x0: Float, val x1: Float, val lineTop: Float, val lineBottom: Float,
    val lineHeight: Float,
    val fullLine: Boolean,      // line.isImage：true 铺满，false 等比居中
    val loader: (w: Int, h: Int) -> Bitmap?,   // 宿主闭包：ImageProvider + runCatching
)

@Stable class ReaderPaintSpec(
    val textSizePx: Float,
    val letterSpacing: Float,
    val typeface: Typeface?,
    val fontVariationSettings: String?,   // 'wght' 可变字重（粗体渲染路径）
    val textSkewX: Float,                 // 斜体
    val isLinearText: Boolean,
    val shadow: ShadowSpec?,              // radius/dx/dy/color
)
// color 不进快照：模块按 EInkTheme.colorScheme.onBackground 自涂（B1 决策不变）
```

端口变化仅三处：

- `ReaderEngine.currentPage(): EInkPageSnapshot?`（替换 `EInkPageContent?`）；
- `GlobalSettings` 增加 `useAntiAlias: Boolean` 属性（图片画笔抗锯齿，模块内经
  `EInkEngineRegistry.globalSettings` 取用）；
- `ReaderEngine` / `ReaderViewModel` KDoc 同步改写（句柄→快照语义）。

`pageRenderer` 槽位从 `EInkApp` / `ReaderScreen` 签名中删除。

**画笔规格保真要求**：`ReaderPaintSpec` 必须与 `ChapterProvider.upStyle` 实际设置的
渲染属性一一对齐（textSize/letterSpacing/typeface/fontVariationSettings/textSkewX/
isLinearText/shadowLayer；isAntiAlias 恒 true）。排版配置与完整模式共用，完整模式
设置的斜体/阴影/可变字重在 eink 阅读页今天可见，迁移后必须同样可见——规格窄化即
隐性渲染退化。

## 4. 宿主映射器

新增 `app/.../eink/bridge/ReaderPageSnapshotMapper.kt`（约 120 行，internal）：
TextPage → EInkPageSnapshot 的唯一新增宿主职责。

- **画笔规格**：从 `ChapterProvider.titlePaint/contentPaint` 全量拷贝 §3 属性表
  （在 `upStyle` 重建画笔后的当前实例上读取）；
- **坐标**：逐 TextLine 扁平化 chars/x；API35+ 的 letterSpacing 半格补偿在映射期
  算进 `x`（`Build.VERSION.SDK_INT` 作参数注入便于单测）；模块画布不再感知字距
  补偿；
- **图片**：`loader` 闭包捕获 ImageProvider 所需参数（book/src），`runCatching` 吞
  异常移入闭包内，失败返回 null；几何（x0/x1/lineTop/lineBottom/lineHeight/fullLine）
  由映射器决定，等比居中数学留在模块画布（需位图实际尺寸，仅绘制期可得）；
- **过滤**：ReviewColumn / TextHtmlColumn / 其他列类型不进快照（对齐现画布
  `else -> Unit` 行为）；
- **线程**：映射在引擎回调线程执行（`onUpContent` 内调用 `currentPage()`，与现状
  同线程上下文）；产物不可变、跨线程安全；
- **频率**：每次 pageVersion 变化仅映射一次（VM 已按版本号驱动状态更新，天然满足），
  无逐帧开销；
- **防御**：畸形行/列跳过，不抛异常。

## 5. 模块画布与删除物

- `modules/eink` 新增 `feature/reader/ReaderPageSnapshotCanvas.kt`（internal）：
  现 `ReaderPageCanvas` 绘制逻辑平移——自持两个 `Paint`（`remember(spec)` 应用规格
  + 主题色）、按预计算 `x` 画字、图片按 `fullLine` 铺满或等比居中、`key(pageVersion)`
  强制重绘；文字画笔 `isAntiAlias = true`（对齐引擎现状硬编码），图片画笔取
  `globalSettings.useAntiAlias`；
- 删除清单：
  - `:app` `eink/reader/ReaderPageCanvas.kt`（整个文件，`eink/reader/` 目录消失，
    `:app` 内 eink 专属 Compose 代码归零）；
  - `ReaderEngineImpl.TextPageContent` 句柄包装；
  - `EinkMainActivity` 的 pageRenderer lambda 及 `ReaderPageCanvas` /
    `TextPageContent` / `EInkBridge.useAntiAlias` 引用；
- `EinkMainActivity` 瘦身为纯入口：install + 路由 + 系统栏/按键，不含阅读绘制知识。

## 6. 错误处理与性能约束

- `loader` 失败（解码异常 / 尺寸 ≤ 0）返回 null，模块跳过该槽位（同现状
  runCatching 语义）；
- 快照为每页一次的扁平数组构建（千级字符），相对翻页重排版成本（Clear7 基线
  0.5s+）可忽略；
- **验收约束：翻页基线不得劣化**；退路：映射器内部按 `(page, version)` 缓存最近
  一份快照（仅在真机测量劣化时启用，默认不实现）。

## 7. 测试与验证

- `:app` 单测（testAppDebugUnitTest）：映射器纯逻辑——坐标/字符扁平化、API35 补偿
  分支（sdkInt 参数化）、图片槽位几何与 fullLine 标志、画笔规格全量拷贝。TextPage
  实体构造过重时，把逐行转换抽成基元参数纯函数再测；
- 真机 Clear7 清单（对照移植文档 §5.4）：
  - 排版调参即时重排：字号/字距/行距/段距/缩进/边距；
  - 粗体切换（可变字重路径）；
  - **画笔规格保真直接证据**：完整模式设斜体/阴影后，eink 阅读渲染不丢失；
  - 图片章节渲染（含换书瞬间旧页不误取新书目录——ImageColumn.book 语义由闭包捕获
    保持）；
  - 日/夜主题切换首帧字色正确（模块自涂主题色）；
  - 翻页性能对比基线（0.5s 量级不劣化）；
- Clear7 为 API 33（< 35），字距补偿分支真机不可达，以单测覆盖。

## 8. 明确不做

- 数据编排下沉（方案 B）、ReaderEngine 端口整形（方案 C）——已否决；
- EssentialReader 回流——本仓为模块主仓；
- EssentialReader 仓 `docs/eink-porting.md` 差异表更新（删 ReaderPageCanvas 行、
  新增映射器行、GlobalSettings +useAntiAlias）——后续文档任务，不在本期代码切片；
- 快照缓存优化——真机测量劣化时才启用。

## 9. 验收标准

1. `:app` 内不再存在 eink 专属 Compose 绘制代码；
2. 差异表面净变化：删 ReaderPageCanvas 整行与 antiAlias 跨层取值，新增
   「快照映射器」一行（数据映射而非渲染逻辑）+ GlobalSettings 一属性；
3. testAppDebugUnitTest 通过（含新增映射器测试）；
4. 真机 §7 清单通过，翻页性能不劣于基线。
