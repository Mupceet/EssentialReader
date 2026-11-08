# 翻页设置页设计（eink 全局翻页输入统一）

日期：2026-11-02
分支：宿主 `eink/paging-settings`（基于 beta.43 `331af4d61`）+ 子模块 `eink-lib` 同名分支（基于 `82206ba20`）
状态：设计定稿，待实施

## 背景与目标

音量键翻页已全局化（beta.30，八屏 + 阅读页生效），但设置入口仍是两处简单开关（「我的」页 + 阅读界面「其它设置」面板，同键双入口）。宿主完整模式还支持鼠标滚轮翻页（`mouseWheelPage`）与自定义翻页按键（`prevKeyCodes`/`nextKeyCodes`），这两项在 eink 模式下零消费——eink 单 Activity 接管后没有滚轮事件分发，按键枢纽只判音量键。

目标：

1. 移除阅读界面「其它设置」面板的音量键翻页开关（全局设置不属于阅读面板）。
2. 「我的」页音量键开关重构为「翻页设置」页，含三项设置：音量键翻页（开关）、鼠标滚轮翻页（开关）、自定义翻页按键（捕获式录入）。
3. 三项设置在 eink 模式下**全局真实生效**（八屏 + 阅读页，与音量键同一套语义）；预览元素与生产判定同源。

## 已确认决策

| 决策点 | 结论 |
|---|---|
| 生效范围 | 全局生效：滚轮与自定义键接入输入枢纽，八屏 + 阅读页都吃，对齐音量键语义 |
| 录入形态 | 捕获式：点方向行进入录入态，按物理键即录为该方向键，键块展示，无需输入法 |
| 预览判定 | 三项全部同源判定：复用生产纯函数，命中提示并消费，不真翻页 |
| 阅读界面 | 纯移除音量键开关，不留「翻页设置」入口行 |

## 方案对比

- **甲（采用）：扩展既有输入枢纽。** 自定义键并入按键判定纯函数（`hubVolumePagingEvent` → `hubPagingKeyEvent`），滚轮新增同构栈式 `EInkWheelHub`。改动集中：纯函数 1 处、`EInkPagerInput` 1 处、`EInkHostActivity` 1 处、阅读页 1 处；八屏自动获得，预览与生产零逻辑分叉。
- 乙（弃）：各屏各自判定——判定散到八屏，重复且必漂移。
- 丙（弃）：Activity 层全局判定直接翻当前屏——Activity 需知道当前分页器，破坏枢纽栈式解耦（同屏多分页区 `enabled` 互斥机制正是为此存在）。

## 数据与契约

`GlobalSettings` 契约（eink-lib `contract/GlobalSettings.kt`）紧跟 `volumeKeyPage` 新增：

```kotlin
/** 滚轮翻页（eink 全局生效；完整模式仅阅读页生效——语义差异写明） */
var mouseWheelPage: Boolean

/** 自定义翻页键对：逗号分隔十进制键码串（"92,93"），默认空；一次原子提交 */
fun setPageKeys(prevKeys: String, nextKeys: String)
```

- 键串格式沿用宿主 `PreferKey.prevKeyCodes/nextKeyCodes`，默认空；两方向关联字段经 `setPageKeys` 一次 `copy(...)` 原子提交（对齐仓库设置纪律）。
- `EInkBridge` 实现转发 `readSettingsGateway`（宿主 `ReadSettings`/`setPageKeys` 能力现成，**宿主侧零存储新增**）。
- eink 侧录入的自定义键在完整模式阅读器同样生效（宿主 `isPrevKey` 同键判定）——跨模式一致。
- designsystem `pager` 包新增纯函数 `parseKeyCodes(s): Set<Int>` / `formatKeyCodes(keys): String`，枢纽与设置页共用。

## 判定链路

**纯函数**：`volumePagingKeyEvent` 改名 `pagingKeyEvent`（internal，模块内改名无兼容负担），签名加 `prevKeyCodes: Set<Int>, nextKeyCodes: Set<Int>`：

```
modalPresent → false（放行，同现状）
ACTION_DOWN：自定义键命中（repeat == 0 翻页，吞掉）＞ 音量键（受 volumeKeyPage 开关，同现状）
ACTION_UP：自定义键或生效中的音量键 → 吞掉
```

- 自定义键**不受**音量键开关控制（三项各自独立；对齐宿主完整模式优先级：自定义 > 音量）。
- 音量键允许被录为自定义键（判定自定义优先，行为可预期，不特判）。
- hub 包装 `hubVolumePagingEvent` → `hubPagingKeyEvent`：事件时实时读设置（fire-and-forget 语义，同现状），每次解析键串。
- 八屏（`EInkPagerInput` 注册）与阅读页（`ReaderScreen.kt:734` 直接注册）自动获得自定义键。

**滚轮**：

- `EInkHostActivity` 补 `onGenericMotionEvent` → 新增 `EInkWheelHub`（与 `EInkKeyEventHub` 同构栈式，只管 `AXIS_VSCROLL`），挂 `EInkEngineRegistry`。
- designsystem 纯函数 `wheelPagingEvent(axisValue, pagingEnabled, modalPresent, ...)`：下滚（axisValue < 0）= 下一页、上滚 = 上一页（对齐宿主方向）。
- 去抖：hub 层 200ms 冷却——首滚即翻页、冷却期内消费但忽略（比宿主 200ms trailing 在墨水屏上响应直接，防整页刷新洪泛；常量可调）。
- `EInkPagerInput` 内统一注册 wheelHub（随 `enabled` 同步开关，八屏自动）；阅读页补一处注册。

**预览同源**：设置页向两个枢纽栈顶注册处理器，调用同一 `pagingKeyEvent`/`wheelPagingEvent` 判定，命中→回调方向做提示而非翻页。

## 「翻页设置」页

**导航**：`EInkScreen.PagingSettings`（data object）+ `EInkApp` when 分支；「我的」的音量键 `MineToggleRow` 换成 `MineEntry 翻页设置`（描述：音量键 / 滚轮 / 自定义按键），回调经 HomeRoute 上抛 → `controller.navigate(PagingSettings)`。

**结构**：无 ViewModel（对齐 Mine/FontSettings 先例——无异步流，本地状态 + GlobalSettings fire-and-forget，提示计时 LaunchedEffect）。`PagingSettingsRoute` + 私有无状态 Screen，文件 `feature/home/PagingSettingsScreen.kt`。

**布局**（紧凑非滚动）：

```
┌ ◀  翻页设置 ─────────────────┐
│ [开关] 音量键翻页              │  ← 副文案：关闭时恢复系统音量调节
│ [开关] 鼠标滚轮翻页            │  ← 副文案：滚轮在阅读页与列表翻页
│ ── 自定义翻页按键 ──────────  │
│ ◀ 上一页  [方向上 19][PageUp 92] ✕ │  ← 点键块删除；点行进入录入
│ ▶ 下一页  [方向下 20] ✕        │
│ ┌──── 预览 ─────────────┐    │
│ │  ◀ 上一页 │ 下一页 ▶   │    │  ← 命中反色高亮 1.5s
│ └───────────────────────┘    │
│ 点方向行后按物理键录入；返回键  │
│ 退出录入，不可录为翻页键        │
└──────────────────────────────┘
```

**捕获式录入**：

- 点方向行 → 录入态（行内文案变「按物理键录入，再点结束」）；按物理键经 `setPageKeys` 原子写入，键块即时出现，预览条同时闪对应方向（录入反馈与预览同一条）。录入态只捕按键，滚轮仍走预览提示、不可录入。
- 冲突规则：一键只属一个方向，录入时自动从另一方向移除（键块消失即可见，静默迁移）；`keyCode ≤ 0` 拒录；BACK 不录（录入态下 BACK = 退出录入）。
- 键名显示：常用键映射中文名（音量上/下、方向上/下、上翻页/下翻页、空格…），冷门键回落「键码 N」；映射表 screen 私有。

**预览条**：双格等宽块，默认描边态；命中→对应格反色 1.5s；期间同方向再命中→刷新计时，异方向→立即切换重计时；不命中无变化。命中即在页内消费、不翻页；开关关闭时按键回落系统行为（音量调节照常、滚轮放行），与真实翻页语义一致。

## 移除与改造

- **阅读界面**：删 `ReaderMenus.kt:845` ToggleRow、`ReaderScreen` 的 `onToggleVolumeKeyPage` 传参、`ReaderViewModel.toggleVolumeKeyPage()`、`UiState.volumeKeyPage` 字段与 init 快照；「其它设置」面板其余行不动。
- **「我的」**：音量键 `MineToggleRow` → `MineEntry 翻页设置`。

## 边界

- 模态在场（`EInkModalPresence`）时预览与翻页判定一致放行。
- 滚轮在设置页被预览消费（开关开时），页面不依赖滚动，无冲突。
- 首页双 Tab `inputEnabled` 互斥不受影响（滚轮注册随 `EInkPagerInput` 的 `enabled` 同步）。
- ComponentGallery（debug）随 `EInkPagerInput` 自动获得滚轮与自定义键。

## 测试

eink-lib 既有 `modules/eink/src/test`：

- `parseKeyCodes/formatKeyCodes`：空串/空格/非法段/重复/乱序。
- `pagingKeyEvent` 矩阵（扩展现有 `VolumePagingKeyEventTest`）：自定义命中、优先于音量、音量关不影响自定义、repeat 不翻、抬起吞掉、modal 放行。
- `wheelPagingEvent`：方向/开关/modal；`EInkWheelHub` 冷却窗口。
- 真机手验：录入→预览→书架/目录/搜索/详情/换源/我的/字体设置翻页→阅读页→完整模式同键生效。

## 非目标

- 朗读时音量键子开关（`volumeKeyPageOnPlay`）不透出。
- 完整模式设置 UI（`ReadConfigScreen`/`MoreConfigSheet`）不动。
- 长按翻页策略（`keyPageOnLongPress`）、摇杆 `AXIS_Y` 不引入 eink。

## 交付形态

eink-lib 子模块为主体（判定/枢纽/设置页/两处 UI 改造/测试），宿主仅 `EInkBridge` 三成员 + 子模块指针推进。
