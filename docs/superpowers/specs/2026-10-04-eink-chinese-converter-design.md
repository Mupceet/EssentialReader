# E-Ink「其它设置」简繁转换 设计

- 日期：2026-10-04
- 状态：设计已确认（呈现形态经用户选定：入口行 + 单选弹窗）
- 分支：`eink/port/md3/modules`（宿主仓） + `eink/lib`（eink-lib 子模块指针推进）

## 1. 背景与目标

宿主完整模式的排版设置里有「简繁转换」下拉（关闭/繁体转简体/简体转繁体，DataStore 键
`chineseConverterType`，Int：0/1/2），由 `ContentProcessor` 在每次章节内容装载时对正文与
章标题实时转换（quick-chinese-transfer，`ChineseUtils.t2s/s2t`）。E-Ink 嵌入式阅读器
（eink-lib 子模块）目前完全没有暴露该能力。

目标：在 eink 阅读器排版「字体配置」弹窗新增简繁转换设置行，三档可选，**与完整模式共享同一
存储值**——任一侧修改，全局一致；改动后当前章以新转换重排。

## 2. 范围与非目标

范围：

- eink-lib `contract/GlobalSettings` 新增转发键与能力声明（零新引擎端口）。
- eink-lib 阅读器排版「字体配置」弹窗新增三选按钮行（弹窗内组合，无新组件/弹窗层级）。
- eink-lib `ReaderViewModel`/`ReaderUiState` 新增字段与设值函数（写 + 乐观更新 + 重排调度）。
- 宿主 `eink/bridge/EInkBridge.kt` `GlobalSettingsImpl` 覆写转发（读写既有 DataStore 键）。
- `EINK-PORTING.md` 移植手册差异表补一条。

非目标（明确不做）：

- 不新建通用单选弹窗组件进 designsystem（等第二个消费者再沉淀，`EInkDialog` KDoc 既定口径）。
- 不动 `EinkLegacyPrefsStore`（不新增自有键——本键转发宿主 DataStore）。
- 不加设置变更 Flow 监听（沿用「VM 写端口 + UiState 乐观更新」模式）。
- 不做按书粒度开关（宿主本就是全局设置）。
- 不动转换算法与宿主管线（`ContentProcessor`/`ChineseUtils` 原样复用）。

## 3. 现状事实（调研锚点）

宿主侧：

- 偏好键：`PreferKey.chineseConverterType`；`ReadSettingsRepository.setChineseConverterType`
  写 DataStore（"settings"，经 `AppConfigStore` 快照⊕pending-overlay，主线程写后内存即时可见）。
- 转换生效点：`help/book/ContentProcessor.kt:149-159`（`getContent` 内按
  `readGateway.currentSettings.chineseConverterType` 1→t2s / 2→s2t）；章标题
  `BookChapter.getDisplayTitle(..., chineseConverterType)` 同管线。
- 完整模式写入口：排版页下拉 → `ReadConfigUpdateDelegate` → 写 DataStore +
  `ReloadContent + RebuildWholeBookPageIndex` 动作集。

eink-lib 侧（路径 `eink-lib/modules/eink/src/main/java/io/legado/app/eink/`）：

- 「其它设置」面板：`feature/reader/ReaderMenus.kt:653-688` `ReaderOtherPanel`；行序按功能域
  分组：翻页交互（音量键翻页、水波纹翻页动画）→ 界面显示（隐藏状态栏、显示段评气泡）→
  阅读区手势（下拉添加书签、点击区域设置收尾）。通用行组件 `ToggleRow`:861、`OptionRow`:892
  （整行按压反色 + 右箭头）。
- 面板容器装载：`ReaderScreen.kt:918-935`。
- 契约：`contract/GlobalSettings.kt`——设置键按写入语义分档（fire-and-forget / 快照状态 /
  attach 期）；`showReviewBubbles` + `supportsReviewBubbles`（:119-138）是「转发宿主阅读设置 +
  切换需重排 + 能力门控行显隐」的完整先例；接口带默认实现供旧宿主降级。
- 引擎与重排：`ReaderEngine.relayout()` → 宿主 `bridge/ReaderEngineImpl.kt:568-579`
  `ReadBook.clearTextChapter() + removeLoading(±1) + ReadBook.loadContent(resetPageOffset=false)`
  → 重跑 `ContentProcessor` → 新 `contentHash` → `ReaderChapterPager.cacheKey` 未命中自动重分页
  → `onContentUpdated` 推新页快照。分页缓存在宿主侧，内容变即重排，无需清缓存文件。
- VM 模板：`toggleShowReviewBubbles`（`ReaderViewModel.kt:1016-1021`：写 globalSettings +
  UiState 乐观更新 + `scheduleRelayout()`）；`scheduleRelayout` 200ms 防抖（:1060-1066）。
- 弹窗外壳：`designsystem/control/EInkDialog.kt:64`——`onClose`（× 关闭 + 标题分隔线）与
  `showActions = false`（隐藏底部按钮，文档写明供"实时预览"类面板弹框使用）组合即得本设计形态。
- eink 进度面板只显示**章内页码**（`ReaderProgressPanel`:241，上一章/下一章 + 页滑条），
  无整书页码估算消费——eink 模式无需 `RebuildWholeBookPageIndex` 等价物。

## 4. 呈现设计

> 演进记录：初版为「其它设置」面板入口行 + 单选弹窗（同日实现后），按产品决策调整移入
> 排版体系，最终形态如下。

位置：**排版设置 → 字体配置弹窗**（`ReaderStyleDialog.Fonts` → `ReaderFontConfigDialog`），
「标题字重」行之后收尾：

```text
┌─ 字体配置 ──────────────────── × ┐
│ 字体选择  [系统默认][系统衬线][系统等宽] │
│           [最近字体][更多字体…（N）]    │
│ 正文字重   [细体][常规][粗体]  …       │
│ 标题字重   [细体][常规][粗体]  …       │
│ 简繁转换   [关闭转换][繁体转简体][简体转繁体] │ ← 新增
└──────────────────────────────────┘
```

- 行形态复用本弹窗既有约定（同字体预设行/字重预设行）：标签在左（按需占宽、垂直居中
  对齐按钮行），右侧三枚等分 `EInkButton`（当前档 `selected` 实心反白），点选即应用。
  零新组件、零弹窗层级。
- 选项文案：`0 → 关闭转换`、`1 → 繁体转简体`、`2 → 简体转繁体`（字面量内联于弹窗行）。
- `supportsChineseConverter == false` 时整行不渲染（旧宿主不留死开关，同本弹窗字重行
  的目录守卫显隐口径）。
- 点选即应用并触发重排——与宿主下拉「选中即应用」语义一致，无确认回路。

## 5. 契约与宿主接缝

eink-lib `contract/GlobalSettings.kt` 新增两成员（模板 = `showReviewBubbles` +
`supportsReviewBubbles`）：

```kotlin
/** 简繁转换档位（转发宿主阅读设置键 chineseConverterType，与完整模式共享同一存储）。 … */
var chineseConverterType: Int
    get() = 0
    set(value) {}

/** 简繁转换能力声明：false = 宿主不支持，模块隐藏「字体配置」弹窗的「简繁转换」行。 */
val supportsChineseConverter: Boolean get() = false
```

KDoc 要点：值域 0/1/2；fire-and-forget 写入档；**切换需重排——调用方写后显式触发重排**；
嵌入式宿主经设置网关 pending-overlay 内存同步可见（主线程写后重排即可读到新值）；旧宿主
默认实现 getter 恒 0、写入丢弃，设置行不渲染、行为不回退。

宿主 `app/src/main/java/io/legado/app/eink/bridge/EInkBridge.kt` `GlobalSettingsImpl` 覆写
（模板 = `volumeKeyPage`，:233-239）：

```kotlin
override var chineseConverterType: Int
    get() = readSettingsGateway.currentSettings.chineseConverterType
    set(value) {
        einkSettingsWriteScope.launch {
            readSettingsGateway.update { it.copy(chineseConverterType = value) }
        }
    }
override val supportsChineseConverter: Boolean get() = true
```

不新增 `EinkLegacyPrefsStore` 自有键——转发 DataStore，与完整模式天然同值。同步更新
`contract/EINK-PORTING.md` 差异表（新增条目，说明转发键与能力门控）。

## 6. 状态与生效链路

eink-lib：

- `ReaderUiState` 新增 `chineseConverterType: Int = 0`；VM init 从
  `EInkEngineRegistry.globalSettings.chineseConverterType` 装载初值（:179-200 装载块）。
- `ReaderViewModel.setChineseConverterType(type: Int)`（模板 = `toggleShowReviewBubbles`）：
  1. `type == current` 直接返回（免无谓重排）；
  2. 写 `EInkEngineRegistry.globalSettings.chineseConverterType`；
  3. `_uiState.update { it.copy(chineseConverterType = type) }` 乐观更新；
  4. `scheduleRelayout()`。
- `ReaderScreen.kt` 字体配置弹窗调用接线 `chineseConverterType = uiState.chineseConverterType`、
  `onSetChineseConverterType = viewModel::setChineseConverterType`。

生效链（全复用现有机制，零新引擎方法）：

```text
setChineseConverterType → 写 globalSettings（宿主 pending-overlay 即时可见）
  → scheduleRelayout()（200ms 防抖）→ engine.relayout()
  → ReadBook.clearTextChapter() + loadContent(resetPageOffset = false)
  → ContentProcessor 以新值转换正文+章标题 → 新 contentHash
  → ReaderChapterPager.cacheKey 未命中 → 重分页 → onContentUpdated → 新页快照
```

## 7. 已知边界（与现有设置一致，不新增处理）

- eink 阅读器打开期间从完整模式改值：内容随下次重排/翻页自然用新值，但面板档位显示快照
  陈旧，重进阅读页对齐（同 `hideStatusBar` 等键的既定口径）。
- 目录页已加载时，章标题转换需重进目录才刷新（displayTitle 在章节装载时实时转换）。
- 转换可能微变字符数 → 章内页数变化；`resetPageOffset = false` 保持章内字符位置，预期阅读
  位置不变，列入真机复核。
- 书签重定位（`MarksEngineImpl.relocateMarking`）本就走 `ContentProcessor`，转换一致性自动保持。

## 8. 测试与验证

- eink-lib 单测（`:modules:eink:testDebugUnitTest`）：
  - 契约兼容守护（`ReaderSyncContractCompatTest` 同款）：手写 Legacy fake 只实现既有成员面
    （编译通过 = 新成员带默认实现零破坏），断言降级语义——恒 0 档、能力 false、写入丢弃
    不抛。
  - VM 设值逻辑不落单测——模块无 ReaderViewModel 实例化测试先例（VM 直读注册表），
    覆盖 = 编译 + 全量模块测试 + 真机；同值短路/重排调度随真机复核。选项文案为弹窗内
    字面量，无独立映射函数（初版映射函数随入口迁移删除）。
  - `supportsChineseConverter = false` 的设置行显隐是单行条件渲染，不单测，真机/宿主覆写
    `true` 的事实由装配保证。
- 宿主主验证集：`.\gradlew.bat testAppDebugUnitTest lintAppDebug verifyConfigArchitecture assembleAppDebug --continue --no-configuration-cache`。
- `git diff --check`（文本改动）。
- 真机复核（实现后）：转换生效正确性；重排后阅读位置保持；与完整模式下拉互改一致性；
  字体配置弹窗行交互与 e-ink 刷新表现。

## 9. 审查门禁问答

- 行为基线：宿主完整模式下拉的现有行为（同键、同管线）；eink 侧为新增入口，无回退面。
- 依赖边界净变化：零新 Gradle 依赖、零新端口；eink-lib 经既有 `GlobalSettings` 契约扩展
  （带默认实现，旧宿主编译与运行均不破坏）。
- 更小改法：设置行复用字体配置弹窗既有行形态（标签左 + 等分按钮，零新组件）、转发复用
  `volumeKeyPage` 模板——已是最小垂直切片。
- 错误/线程/取消语义：fire-and-forget 写入 + 防抖重排与 `showReviewBubbles` 等现有内容影响型
  设置完全同构；写入经 `einkSettingsWriteScope`（SupervisorJob + Main.immediate）。
- 已验证/未验证：链路结论来自代码调研（锚点见 §3）；转换视觉效果与真机重排行为待实现后复核。
