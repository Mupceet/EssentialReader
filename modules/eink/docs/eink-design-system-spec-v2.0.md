# EssentialReader E-Ink Design System Specification v2.0

Version: 2.0  
Status: Proposed  
Target: EssentialReader E-Ink Android Application  
UI Framework: Jetpack Compose  
Primary Package: `io.legado.app.eink`  
Scope: E-Ink Native Design System

---

## 0. Document Purpose

本规范定义 EssentialReader 面向 E-Ink 屏幕的统一 UI Design System。

本规范不是 Material Design 的 E-Ink 主题，也不是普通 Android UI 组件的简单 E-Ink 适配层。

目标是建立一套：

- 面向 E-Ink 物理特性的 UI 设计体系
- 面向阅读器应用场景的交互体系
- 面向低刷新率设备的状态与刷新体系
- 支持不同灰阶能力和不同 E-Ink 硬件的可移植体系
- 可以长期维护和商业化的 Compose Design System
- 可以被 Agent 按规范持续实现和审查的工程标准

核心目标：

1. Readability over decoration
2. Stable visual states over motion
3. Controlled grayscale over binary black/white
4. Semantic state over animation
5. Page navigation over continuous scrolling
6. Input-agnostic interaction over touch-only interaction
7. Explicit refresh intent over component-controlled refresh
8. Device capability over hard-coded waveform modes
9. Composition over feature-specific components
10. Visual cost and refresh cost must be considered as first-class design constraints

---

# 1. Fundamental Principles

## 1.1 E-Ink Native

所有组件必须首先考虑 E-Ink 的物理显示特性，而不是首先考虑 LCD/OLED Android UI 的传统设计。

必须考虑：

- 低刷新率
- 全刷成本
- 局刷限制
- 残影
- 灰阶显示能力
- 黑白转换成本
- 大面积颜色变化造成的视觉冲击
- 动画造成的连续刷新
- 触摸反馈不适合连续动画
- 物理按键和方向键输入
- 阅读场景下的长时间注视
- 户外强光环境下的可读性

---

## 1.2 E-Ink != Black and White Only

不得将 E-Ink Design System 定义为纯黑白 UI。

正确原则：

> High Contrast + Controlled Grayscale

允许并鼓励在有明确语义的情况下使用稳定灰阶。

灰阶用于：

- 次要文字
- 元数据
- disabled 状态
- divider
- surface 层级
- selection 辅助
- scrim
- placeholder
- 辅助图标
- 非主要视觉边界

但是禁止通过大量透明度叠加、渐变、阴影等方式制造复杂灰度。

---

## 1.3 Controlled Grayscale

Design System 必须定义受控灰阶 Token。

禁止业务组件随意使用：

```kotlin
Color.Gray
Color.LightGray
Color.Black.copy(alpha = ...)
Color.White.copy(alpha = ...)
```

业务代码不得直接决定灰阶。

必须使用：

```kotlin
EInkTheme.colorScheme.*
EInkTheme.grayscale.*
```

推荐基础灰阶：

```text
Black       #000000
Gray900     #1A1A1A
Gray700     #333333
Gray500     #666666
Gray400     #808080
Gray300     #999999
Gray200     #CCCCCC
Gray100     #E6E6E6
Gray50      #F5F5F5
White       #FFFFFF
```

具体设备不要求实际显示全部灰阶。

Design System 定义的是语义空间，而不是要求所有设备都输出全部灰度。

---

# 2. Design System Architecture

最终体系必须形成以下层次：

```text
E-Ink Design System
│
├── Foundation
│   ├── Color
│   ├── Typography
│   ├── Shape
│   ├── Spacing
│   ├── Dimension
│   └── Iconography
│
├── Interaction
│   ├── Interaction State
│   ├── Press
│   ├── Focus
│   ├── Selection
│   └── Input
│
├── Surface
│   ├── Surface
│   ├── Container
│   ├── Card
│   ├── Divider
│   └── Scrim
│
├── Controls
│   ├── Button
│   ├── IconButton
│   ├── Checkbox
│   ├── RadioButton
│   ├── Switch
│   ├── Slider
│   ├── TextField
│   └── SearchField
│
├── Navigation
│   ├── TopBar
│   ├── ActionBar
│   ├── NavigationBar
│   ├── Tabs
│   ├── PageIndicator
│   └── Pager
│
├── Content
│   ├── Text
│   ├── ListItem
│   ├── Section
│   ├── EmptyState
│   ├── LoadingState
│   └── ErrorState
│
├── Reader
│   ├── ReaderPage
│   ├── PageTurn
│   ├── ReadingProgress
│   └── ReaderControls
│
├── Refresh
│   ├── RefreshIntent
│   ├── RefreshPolicy
│   ├── RefreshController
│   └── DeviceRefreshAdapter
│
└── Device
    ├── EInkDeviceProfile
    ├── EInkCapabilities
    └── InputCapabilities
```

---

# 3. Package Architecture

目标 package：

```text
io.legado.app.eink
```

推荐最终结构：

```text
eink/
├── foundation/
│   ├── color/
│   ├── typography/
│   ├── shape/
│   ├── spacing/
│   ├── dimension/
│   └── icon/
│
├── interaction/
│   ├── InteractionState.kt
│   ├── InteractionAppearance.kt
│   ├── EinkClickable.kt
│   ├── Focus.kt
│   └── Input.kt
│
├── surface/
│   ├── EInkSurface.kt
│   ├── EInkContainer.kt
│   ├── EInkCard.kt
│   ├── EInkDivider.kt
│   └── EInkScrim.kt
│
├── control/
│   ├── EInkButton.kt
│   ├── EInkIconButton.kt
│   ├── EInkCheckbox.kt
│   ├── EInkRadioButton.kt
│   ├── EInkSwitch.kt
│   ├── EInkSlider.kt
│   ├── EInkTextField.kt
│   └── EInkSearchField.kt
│
├── navigation/
│   ├── EInkTopBar.kt
│   ├── EInkActionBar.kt
│   ├── EInkNavigationBar.kt
│   ├── EInkTabs.kt
│   ├── EInkPageIndicator.kt
│   └── EInkPager.kt
│
├── content/
│   ├── EInkText.kt
│   ├── EInkListItem.kt
│   ├── EInkSection.kt
│   ├── EInkEmptyState.kt
│   ├── EInkLoadingState.kt
│   └── EInkErrorState.kt
│
├── reader/
│   ├── EInkReaderPage.kt
│   ├── EInkPageTurn.kt
│   ├── EInkReadingProgress.kt
│   └── EInkReaderControls.kt
│
├── pager/
│   ├── EInkPagerState.kt
│   ├── EInkPagerLayout.kt
│   ├── EInkListPager.kt
│   └── EInkGridPager.kt
│
├── refresh/
│   ├── EInkRefreshIntent.kt
│   ├── EInkRefreshPolicy.kt
│   ├── EInkRefreshController.kt
│   └── EInkRefreshAdapter.kt
│
├── device/
│   ├── EInkDeviceProfile.kt
│   ├── EInkCapabilities.kt
│   └── EInkInputCapabilities.kt
│
└── theme/
    ├── EInkTheme.kt
    ├── EInkColorScheme.kt
    ├── EInkTypography.kt
    ├── EInkShapes.kt
    └── EInkSpacing.kt
```

迁移期间可以保留现有 package，通过逐步移动完成重构。

不得为了目录重构而一次性大规模破坏业务代码。

---

# 4. Design Tokens

## 4.1 Color Tokens

Color 必须分为：

```text
Palette
Semantic Color
Interaction Color
```

### Palette

定义基础灰阶：

```kotlin
EInkTheme.grayscale.black
EInkTheme.grayscale.gray900
EInkTheme.grayscale.gray700
EInkTheme.grayscale.gray500
EInkTheme.grayscale.gray400
EInkTheme.grayscale.gray300
EInkTheme.grayscale.gray200
EInkTheme.grayscale.gray100
EInkTheme.grayscale.gray50
EInkTheme.grayscale.white
```

### Semantic Color

组件不得直接依赖 palette。

必须优先依赖：

```text
background
surface
surfaceVariant

primaryContent
secondaryContent
tertiaryContent
disabledContent

border
borderStrong
divider

selected
selectedContent
focused
pressed
pressedContent

positive
warning
negative
informational
```

---

## 4.2 Color Emphasis

定义：

```text
Primary
Secondary
Tertiary
Disabled
```

推荐：

```text
Primary      #000000
Secondary    #333333 / #666666
Tertiary     #666666 / #999999
Disabled     #999999 / #CCCCCC
```

具体值由 Theme 决定。

组件不得自己定义：

```kotlin
Color(0xFF...)
```

---

# 5. Typography

Typography 是 E-Ink 应用的核心 Design Token。

优先级：

```text
Readability > hierarchy > decoration
```

至少定义：

```text
Display
Headline
Title
Subtitle
Body
BodyLarge
BodySmall
Label
Caption
Metadata
```

阅读器额外定义：

```text
ReadingTitle
ReadingBody
ReadingBodyLarge
ReadingBodySmall
ReadingMetadata
ReadingFootnote
```

Typography 必须统一控制：

- fontSize
- fontWeight
- lineHeight
- letterSpacing
- paragraph spacing

禁止为了视觉效果大量使用：

```text
Thin
ExtraLight
hairline
```

E-Ink 上必须优先保证笔画稳定性。

---

# 6. Shape

E-Ink 默认不使用复杂圆角。

默认：

```text
Rectangle
```

允许：

```text
Small radius
Medium radius
```

但是：

- 禁止大圆角成为默认风格
- 禁止 pill-shaped UI 成为默认组件形态
- 不使用连续变化的 shape animation
- 不使用复杂 clipping effect

推荐：

```text
Button        2dp~4dp
Card          2dp~6dp
Dialog        2dp~6dp
Input         2dp~4dp
```

具体值由 Theme 统一定义。

---

# 7. Border

Border 是 E-Ink 中替代 elevation 的主要层级表达方式之一。

允许：

```text
1dp standard
2dp strong
```

语义：

```text
border       普通结构边界
borderStrong 重要交互边界
focus        当前焦点
```

禁止：

- shadow-only hierarchy
- blur shadow
- soft shadow
- glow
- gradient border

---

# 8. Elevation and Shadow

E-Ink Design System 默认：

```text
Elevation = 0
Shadow = disabled
```

不允许组件依赖 Material elevation 表达主要层级。

层级必须通过：

```text
Color
Border
Spacing
Typography
```

表达。

如果特殊平台确实需要 shadow，必须经过 Design System 审核，并且不得成为默认实现。

---

# 9. Surface System

Surface 分为：

```text
Base
Surface
SurfaceVariant
Outlined
Emphasized
```

推荐：

```text
Base        white
Surface     gray50
Variant     gray100
Outlined    white + border
Emphasized  gray100/gray200
```

不要大量使用不同灰阶创建“卡片墙”。

E-Ink 更推荐通过：

```text
spacing + divider + typography
```

形成结构。

---

# 10. Card

Card 是可用组件，但不是默认布局容器。

正确：

```text
Card = Surface + Border/Container + Padding + Semantics
```

错误：

```text
所有内容都 Card 化
```

Card 不得：

- 默认 elevation
- 默认 shadow
- 默认大圆角
- 默认复杂背景
- 默认动画
- 默认点击 ripple

业务专属组件，例如：

```text
BookCard
ShelfCard
ChapterCard
```

不得进入 Design System。

这些必须属于 feature 层。

---

# 11. Divider

Divider 是 E-Ink 中非常重要的低成本结构元素。

允许：

```text
1dp gray200/gray300
2dp strong divider
```

禁止：

```text
gradient divider
animated divider
shadow divider
```

Divider 应优先使用真实灰阶，而不是 alpha。

---

# 12. Interaction State

所有可交互组件必须共享统一状态模型：

```kotlin
data class EInkInteractionState(
    val enabled: Boolean,
    val pressed: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val checked: Boolean,
    val expanded: Boolean,
)
```

不是所有组件必须使用所有状态，但状态语义必须统一。

状态优先级必须明确。

推荐：

```text
Disabled
    >
Pressed
    >
Focused
    >
Selected / Checked
    >
Normal
```

具体组件可以根据语义调整，但不得自行创造冲突规则。

---

# 13. Pressed State

Pressed 是瞬时状态。

必须：

- 无动画
- 无 ripple
- 无 scale animation
- 无 fade
- 无 spring

推荐表现：

```text
Normal:
white background + black content

Pressed:
black background + white content
```

但是大面积组件不得默认整块反色。

对于大组件，优先：

```text
strong border
small selected marker
content emphasis
```

---

# 14. Selected State

Selected 是持久状态。

Selected 不等于 Pressed。

推荐：

```text
Tab:
black indicator

ListItem:
strong border / marker

Checkbox:
black check mark

Radio:
black center

Switch:
stable black/gray state

Button:
controlled fill
```

避免：

```text
整行大面积黑底
整页黑底
大面积反色
```

---

# 15. Focus State

Focus 必须作为一级状态。

原因：

E-Ink 设备可能使用：

- Touch
- D-Pad
- Physical key
- Keyboard
- Remote
- Accessibility

Focus 推荐：

```text
2dp strong border
```

或者：

```text
strong outline
```

禁止使用：

```text
glow
animated focus
pulsing focus
```

---

# 16. Input Model

Design System 必须 Input Agnostic。

支持：

```text
Touch
Key
DPad
Rotary
Keyboard
Accessibility
```

组件关注：

```text
Click
LongClick
Confirm
Back
Next
Previous
Focus
PageUp
PageDown
```

而不是只关注：

```text
Swipe
Drag
Touch gesture
```

---

# 17. Ripple

Ripple 默认禁止。

禁止：

```text
rememberRipple
ripple()
Material ripple
custom animated ripple
```

Pressed feedback 必须采用即时视觉状态。

---

# 18. Animation

E-Ink Design System 默认：

```text
Animation = OFF
```

禁止默认使用：

```text
AnimatedVisibility
AnimatedContent
Crossfade
animate*
animateContentSize
spring
tween
keyframes
InfiniteTransition
```

除非明确经过 E-Ink Design System 审核。

任何动画必须回答：

1. 为什么必须动画？
2. 能否使用状态瞬时切换？
3. 是否增加刷新次数？
4. 是否产生残影？
5. 是否影响物理按键操作？
6. 是否影响阅读连续性？

默认答案应该是：

```text
Use instant state transition.
```

---

# 19. Loading

禁止持续旋转的 loading indicator 作为默认 E-Ink Loading。

禁止：

```text
CircularProgressIndicator animation
spinner animation
pulsing animation
```

推荐：

```text
Loading…
Preparing…
Updating…
```

或者静态 progress bar：

```text
██████████░░░░░░
```

如果必须显示进度：

```text
0% → 25% → 50% → 75% → 100%
```

必须采用离散状态变化。

---

# 20. Dialog

Dialog 必须是 E-Ink Native。

要求：

- 白色或稳定浅灰背景
- 实线 border
- 无 shadow
- 无 blur
- 无动画
- 无 fade
- 无 scale
- scrim 使用稳定灰阶

Scrim 不应依赖：

```kotlin
Color.Black.copy(alpha = ...)
```

推荐：

```text
white background
+
controlled gray scrim
```

Dialog 打开和关闭属于 Navigation/Overlay 事件，不由 Dialog 自己决定刷新模式。

---

# 21. Search and Text Input

TextField 必须：

- 无 cursor animation
- 无 floating label animation
- 无 ripple
- 无 animated underline
- 无 shadow

输入状态：

```text
Normal
Focused
Error
Disabled
```

Focus 使用：

```text
strong border
```

而不是 glow。

输入过程中可以使用适合设备的局部刷新策略，但组件不能直接选择 A2/DU 等 waveform。

---

# 22. Pager

Pager 是 E-Ink Design System 的核心结构。

E-Ink Pager 不等价于普通 Scroll。

核心概念：

```text
Page
PageIndex
PageTurn
PageUp
PageDown
FirstPage
LastPage
```

Pager 必须支持：

```text
Touch
Physical key
DPad
Keyboard
Programmatic navigation
```

---

# 23. Pager State

Pager 状态独立于具体 Layout。

建议：

```kotlin
EInkPagerState
```

负责：

```text
currentPage
pageCount
canGoNext
canGoPrevious
goNext()
goPrevious()
goToPage()
```

不要将分页状态与：

```text
LazyColumn
LazyVerticalGrid
```

强耦合。

---

# 24. Pager Layout

Pager Layout 可以有：

```text
List
Grid
Custom
```

因此：

```text
EInkListPager
EInkGridPager
```

只是 Layout 实现，而不是独立的分页概念。

推荐：

```text
Pager
 ├── PagerState
 ├── PagerLayout
 └── PageIndicator
```

---

# 25. Continuous Scrolling

Continuous scrolling 可以支持，但不是 E-Ink Design System 的首选交互。

对于：

- 阅读器
- 设置
- 搜索结果
- 书架
- 目录

优先考虑分页。

Scroll 适合：

- 长文本
- 用户明确需要连续阅读
- 设备刷新能力允许
- 内容无法合理分页

不得因为 Compose 默认提供 LazyColumn，就默认采用连续滚动。

---

# 26. Page Turn

Page Turn 是 E-Ink 的一级用户行为。

Page Turn：

```text
previous
next
```

必须：

- 无滑动动画
- 无 content transition
- 无 fade
- 无 slide animation

页面状态直接切换。

然后：

```text
PageTurn
    ↓
RefreshIntent.PageTurn
```

由 Refresh Policy 决定设备刷新方式。

---

# 27. Page Indicator

Page Indicator 应该是低视觉成本组件。

推荐：

```text
12 / 48
```

或者：

```text
━━━━━━░░░░
```

避免：

- 动态进度动画
- bouncing indicator
- sliding indicator

---

# 28. Top Bar

TopBar 应该提供：

```text
navigation
title
subtitle
actions
```

默认：

```text
white background
black content
bottom divider
```

不使用：

```text
elevation
shadow
gradient
blur
```

TopBar 不负责决定 refresh waveform。

---

# 29. Action Bar

ActionBar 用于上下文操作。

例如：

```text
Select
Delete
Move
Mark
More
```

ActionBar 和 TopBar 必须有明确职责。

不得继续创建大量：

```text
EInkTopActionBar
EInkOperationBar
EInkOperationBarIcon
```

如果只是布局变化，应组合：

```text
TopBar
+
IconButton
+
ActionBar
```

而不是创建新的原子组件。

---

# 30. Icon

Icon 是 Foundation 层组件。

推荐尺寸：

```text
16dp  dense
20dp  compact
24dp  standard
28dp  navigation
32dp  primary action
```

Icon 必须保证：

- 足够笔画宽度
- 不使用过细线条
- 不使用半透明
- 不使用发光
- 不使用动画

IconButton 是交互组件。

Icon 本身不是交互组件。

---

# 31. Button

Button 类型应尽量少。

建议：

```text
Primary
Secondary / Outlined
Text
Icon
```

Button 默认：

```text
no elevation
no ripple
no animation
controlled border
controlled grayscale
```

Primary Button 可以使用：

```text
black background
white content
```

但不得在页面中大量使用 Primary Button。

E-Ink 页面应控制大面积黑色区域。

---

# 32. Selection Controls

Checkbox / Radio / Switch 必须使用稳定状态。

Checkbox：

```text
unchecked = outline
checked = black fill + white mark
```

Radio：

```text
unchecked = outline
checked = black center
```

Switch：

必须避免：

```text
animated thumb
animated track
```

状态立即切换。

---

# 33. Slider

Slider 必须是离散视觉状态。

推荐：

```text
track
thumb
value
```

Dragging 时：

- 不显示动画
- 不产生持续复杂动画
- 允许设备使用局部快速刷新

如果设备不适合连续刷新，应支持：

```text
step-based interaction
```

例如：

```text
-  + 
```

或者：

```text
10%
20%
30%
```

---

# 34. Empty State

Empty State 必须静态。

推荐：

```text
Icon
Title
Description
Action
```

避免：

```text
animated illustration
animated icon
```

---

# 35. Error State

Error State 使用语义化灰阶。

不要依赖：

```text
red-only
```

因为很多 E-Ink 是黑白屏。

错误必须通过：

```text
icon
typography
border
label
```

表达。

Color E-Ink 可以进一步使用 semantic color，但不能依赖颜色才能理解。

---

# 36. Accessibility

所有状态不得仅通过颜色区分。

例如：

```text
selected
disabled
error
warning
focused
```

必须同时具有：

```text
shape
icon
border
text
position
```

等非颜色信息。

Minimum touch target：

```text
44dp ~ 48dp
```

根据设备尺寸和输入方式调整。

实体按键设备可以使用更紧凑的视觉尺寸，但焦点区域仍必须足够明确。

---

# 37. Visual Cost

每个 Design System 组件必须考虑 Visual Cost。

定义：

```text
Low
Medium
High
Extreme
```

示例：

```text
Text                  Low
Divider               Low
Icon                  Low
Border                Low
Gray Surface          Low
Pressed inversion     Medium
Dialog scrim          Medium
Large black surface   High
Full-screen inversion Extreme
Animation             Extreme
```

Visual Cost 不是运行时必须暴露的 API，但必须作为设计审核标准。

---

# 38. Refresh Cost

同样定义：

```text
Low
Medium
High
Extreme
```

示例：

```text
Stable text           Low
Local state change    Low
Button press          Low
Page turn             Medium
Dialog open           Medium
Navigation            High
Full redraw           Extreme
Ghost clearing        Extreme
Animation             Extreme
```

---

# 39. Refresh Architecture

组件不得直接调用：

```text
A2
DU
GC16
GL16
Full Refresh
Partial Refresh
```

这些属于 Device / Platform 层。

正确架构：

```text
Component
    ↓
Semantic Event
    ↓
RefreshIntent
    ↓
RefreshPolicy
    ↓
DeviceProfile
    ↓
DeviceRefreshAdapter
    ↓
Hardware
```

---

# 40. Refresh Intent

定义：

```kotlin
enum class EInkRefreshIntent {
    ContentStable,
    Interactive,
    PageTurn,
    Navigation,
    Overlay,
    TextInput,
    FullRedraw,
    ClearGhosting,
}
```

组件只产生 Intent。

例如：

```text
Page changed
    →
RefreshIntent.PageTurn
```

而不是：

```text
Page changed
    →
A2
```

---

# 41. Refresh Policy

RefreshPolicy 根据：

```text
Intent
DeviceProfile
Current state
Region
Refresh history
```

决定具体刷新策略。

例如：

```text
PageTurn
    → device default fast/full policy

Dialog
    → overlay policy

TextInput
    → local interactive policy

ClearGhosting
    → full refresh
```

具体 waveform 必须由 Device Adapter 决定。

---

# 42. Device Profile

必须定义：

```kotlin
data class EInkDeviceProfile(
    val grayscaleLevels: Int,
    val supportsPartialRefresh: Boolean,
    val supportsFastRefresh: Boolean,
    val supportsFullRefresh: Boolean,
    val supportsColor: Boolean,
    val physicalPageKeys: Boolean,
    val touchInput: Boolean,
    val keyboardInput: Boolean,
)
```

后续可继续扩展。

Design System 不允许假设：

```text
E-Ink = monochrome
```

必须支持：

```text
Monochrome E-Ink
Color E-Ink
```

---

# 43. Waveform Abstraction

Application/UI 层不得直接依赖 waveform 名称。

UI 层只使用：

```text
Fast
Normal
Quality
Full
```

或者：

```text
Interactive
Stable
PageTurn
FullRedraw
```

Device Adapter 再映射：

```text
Interactive
    → A2 / DU / vendor mode

Stable
    → normal grayscale mode

FullRedraw
    → GC16 / GL16 / vendor full mode
```

---

# 44. Feature Boundary

Design System 只提供通用 UI。

以下不得进入 Design System：

```text
BookCard
BookShelfItem
ChapterItem
SearchBookResult
ReaderChapterHeader
ReadingBookInfo
```

这些属于 Feature Component。

正确结构：

```text
Feature
    ↓
EInk Design System
```

而不是：

```text
EInk Design System
    ↓
Book domain
```

---

# 45. Feature Components

Feature 可以组合：

```text
EInkCard
EInkText
EInkIcon
EInkListItem
EInkButton
EInkPager
```

形成：

```text
BookCard
SearchResultItem
ChapterItem
ShelfItem
```

Feature Component 可以有业务状态。

Design System Component 不得包含业务模型。

---

# 46. Theme

最终使用：

```kotlin
EInkTheme {
    ...
}
```

Theme 提供：

```text
colorScheme
grayscale
typography
shapes
spacing
dimensions
deviceProfile
```

组件必须从 Theme 获取设计参数。

禁止组件内部复制 Token。

---

# 47. Theme Variants

至少允许：

```text
Default
HighContrast
LargeText
```

未来可以支持：

```text
Outdoor
LowContrast
ColorEInk
```

Theme Variant 必须修改 Token，而不是让业务组件写：

```kotlin
if (...)
```

---

# 48. Compose Usage Rules

## Allowed

```text
Row
Column
Box
LazyColumn
LazyVerticalGrid
Text
BasicText
Canvas
Modifier
remember
derivedStateOf
```

## Controlled

```text
Surface
Scaffold
Card
Button
TextField
Dialog
```

必须使用 E-Ink wrapper。

## Prohibited by default

```text
AnimatedVisibility
AnimatedContent
Crossfade
animate*
animateContentSize
spring
tween
InfiniteTransition
Ripple
shadow
blur
gradient
```

---

# 49. Modifier Rules

E-Ink Modifier 应保持少而稳定。

推荐：

```text
einkBorder()
einkClickable()
einkFocus()
einkSurface()
```

不要建立大量：

```text
einkSomethingSpecial()
```

如果 Modifier 只是为了业务页面视觉效果，应留在 Feature 层。

Modifier 不得绕过 Theme。

---

# 50. Component API Rules

组件 API 必须：

- 默认提供 E-Ink 合理行为
- 默认关闭动画
- 默认关闭 ripple
- 默认不使用 shadow
- 默认使用 Theme Token
- 支持 enabled
- 支持 focus
- 支持 semantics
- 支持 keyboard / physical input
- 不直接操作 refresh hardware

组件 API 不应该暴露：

```text
waveform
refreshMode
A2
DU
GL16
GC16
```

---

# 51. State Hoisting

遵循 Compose State Hoisting。

组件状态应区分：

```text
UI State
Interaction State
Domain State
Device State
```

不得混合。

例如：

```text
Button
    UI: enabled
    Interaction: pressed/focused
    Device: none
```

Pager：

```text
Pager State
    currentPage
    pageCount
```

Refresh：

```text
Device State
    refresh capability
```

三者不要混在同一个 State 对象中。

---

# 52. Component Granularity

组件分为：

```text
Foundation
Atom
Molecule
Pattern
Feature
```

但是工程目录不强制使用 Atomic Design 命名。

推荐：

```text
Foundation
Controls
Navigation
Content
Pager
Reader
```

比：

```text
Atoms
Molecules
Organisms
```

更适合长期工程维护。

Atomic Design 作为设计思想，而不是 package naming。

---

# 53. Component Creation Rule

创建新组件前必须回答：

1. 是否至少有两个独立使用场景？
2. 是否存在统一的视觉语义？
3. 是否可以由现有组件组合完成？
4. 是否属于 Design System 而不是 Feature？
5. 是否需要独立状态模型？
6. 是否需要独立 Accessibility 行为？
7. 是否需要独立 E-Ink 交互行为？

如果只是一个页面专用布局：

```text
不要创建 Design System Component。
```

---

# 54. Duplication Rule

禁止出现：

```text
EInkOperationBarIcon
EInkTopActionBarIcon
EInkPageArrowButton
EInkBackButton
```

等只是 IconButton 的轻微变体。

优先：

```text
EInkIconButton
```

通过：

```text
size
variant
enabled
selected
contentDescription
```

组合。

---

# 55. Existing Component Migration

现有组件必须分类：

```text
KEEP
MERGE
MOVE
REWRITE
DELETE
FEATURE
```

建议初步方向：

```text
EInkButton
    → KEEP / REWRITE

EInkCard
    → KEEP / REWRITE

EInkText
    → KEEP / REWRITE

EInkIconButton
    → KEEP

EInkPagedList
    → REWRITE into Pager architecture

EInkGridPagedList
    → MERGE into Grid Pager

PaginatedList
    → MERGE into Pager State/Layout

EInkTopBar
    → KEEP / REWRITE

EInkTopActionBar
    → MERGE

EInkOperationBar
    → MERGE / Navigation ActionBar

EInkOperationBarIcon
    → DELETE / replace by IconButton

EInkPageArrows
    → COMPOSE from IconButton

EInkPageIndicator
    → KEEP

EInkSearchBar
    → REWRITE as SearchField + layout

EInkBookCard
    → MOVE to bookshelf feature
```

最终决定必须基于实际调用关系，而不是机械迁移。

---

# 56. Reading Experience

EssentialReader 是阅读器。

阅读体验优先级：

```text
Reading content
    >
Navigation
    >
Controls
    >
Decoration
```

阅读页面不得被：

```text
card
border
toolbar
background decoration
animation
```

过度打扰。

---

# 57. Reader Page

Reader Page 应是特殊的低视觉成本容器。

默认：

```text
white / stable background
black primary text
controlled secondary gray
```

避免：

```text
card
shadow
gradient
animated transition
```

Page Turn：

```text
instant content replacement
+
RefreshIntent.PageTurn
```

---

# 58. Reader Controls

Reader Controls 可以：

```text
Page up/down
Table of contents
Settings
Bookmark
Progress
```

但控制栏应该尽可能：

```text
small
stable
high contrast
predictable
```

避免持续显示大量操作按钮。

---

# 59. Settings Experience

Settings 页面优先使用：

```text
Section
ListItem
Divider
Secondary text
Control
```

而不是大量 Card。

推荐：

```text
Section title
────────────────────
Setting item
Setting item
Setting item

Section title
────────────────────
Setting item
Setting item
```

这是 E-Ink 更自然的信息结构。

---

# 60. List Item

ListItem 是核心组件。

必须支持：

```text
leading
headline
supporting
trailing
selected
enabled
focused
```

推荐高度：

```text
compact
standard
comfortable
```

而不是大量自定义高度。

---

# 61. Large Black Area Rule

任何组件如果默认产生大面积黑色区域，必须经过审查。

例如：

```text
large black card
full black toolbar
full black selection
black dialog
```

默认禁止。

黑色应该更多用于：

```text
text
icon
border
small indicator
primary action
```

而不是页面背景。

---

# 62. Gray Area Rule

灰色必须有语义。

允许：

```text
surface separation
secondary content
disabled
scrim
divider
selection background
```

禁止：

```text
random gray decoration
multiple nearly identical gray levels
gray gradient
gray shadow
gray transparency stacking
```

---

# 63. Contrast Rule

所有主要信息必须在最常见的设备灰阶能力下可辨识。

Primary content：

```text
black on white
```

Secondary：

```text
dark gray on white
```

Disabled：

```text
medium gray
```

不得使用：

```text
light gray text on white
```

作为普通信息。

---

# 64. No Motion Principle

Design System 默认：

```text
Motion = 0
```

这不是：

```text
Accessibility preference
```

而是：

```text
E-Ink platform constraint
```

如果未来需要支持 LCD/OLED，不应该修改 E-Ink Design System，而应该使用其他 Design System。

---

# 65. E-Ink Design System vs Material

Material 可以作为：

```text
implementation reference
```

但不能作为：

```text
behavioral authority
```

如果 Material 行为与 E-Ink 原则冲突：

```text
E-Ink Design System wins.
```

例如：

```text
Material Ripple
    → E-Ink Pressed state

Material Elevation
    → E-Ink Border / Surface

Material Animation
    → Instant transition

Material Scroll-first
    → Pager-first

Material alpha
    → Controlled grayscale
```

---

# 66. Performance

E-Ink UI 不仅需要视觉优化，也需要 Compose 性能稳定。

避免：

```text
unnecessary recomposition
unstable state
large derived object creation
continuous animation
continuous state updates
```

尤其禁止因为：

```text
animation frame
scroll offset
progress
```

导致整个页面持续 recomposition。

---

# 67. Refresh-aware Rendering

组件不得因为内部状态变化自动触发全屏刷新。

Refresh 应由：

```text
RefreshController
```

统一调度。

组件只产生：

```text
semantic change
```

例如：

```text
Button pressed
Checkbox changed
Page changed
Dialog opened
```

RefreshController 决定：

```text
是否刷新
何时刷新
刷新区域
刷新策略
```

---

# 68. Refresh Batching

多个连续 UI 状态变化应该允许合并。

例如：

```text
Selection changed
+
Toolbar changed
+
Page indicator changed
```

不得触发：

```text
3 refreshes
```

而应尽可能：

```text
1 semantic update
→
1 refresh transaction
```

---

# 69. Full Refresh

Full refresh 是昂贵操作。

不得：

```text
每次 navigation full refresh
每次 dialog full refresh
每次 button click full refresh
```

只有：

```text
ghosting recovery
device-required redraw
large-scale corruption
explicit user request
```

等场景才允许。

---

# 70. Manual Ghost Clearing

可以提供：

```text
Clear Ghosting
Refresh Screen
```

等用户操作。

但它属于：

```text
Device / Reader Settings
```

不是：

```text
TopBar mandatory action
```

不要让每个页面默认显示“刷新屏幕”按钮。

---

# 71. Color E-Ink

Color E-Ink 必须被架构支持。

Color 不得改变 Design System 的核心原则：

```text
stable
controlled
semantic
high contrast
low motion
```

颜色应该是增强信息，而不是唯一的信息表达方式。

例如 Error：

```text
icon + text + structure
```

而不是：

```text
red only
```

---

# 72. Testing

Design System 必须建立视觉测试。

至少测试：

```text
Normal
Pressed
Focused
Selected
Disabled
Dialog
Pager
Loading
Error
Empty
```

每个组件至少在：

```text
White
Gray Surface
High Contrast
```

环境下验证。

---

# 73. E-Ink Device Test Matrix

至少覆盖：

```text
Monochrome low grayscale
Monochrome high grayscale
Color E-Ink
Partial refresh capable
No partial refresh
Fast refresh capable
Physical page keys
Touch-only
Touch + key
```

不能只在 Android Emulator 上判断设计是否正确。

---

# 74. Ghosting Evaluation

视觉测试必须观察：

```text
Repeated inversion
Repeated selection
Repeated page turn
Dialog open/close
Scrolling
Text input
Slider interaction
```

重点检查：

```text
ghosting
flicker
black block accumulation
gray contamination
refresh frequency
```

---

# 75. Design Review Checklist

新组件进入 Design System 前：

```text
[ ] 是否真正需要？
[ ] 是否可以组合已有组件？
[ ] 是否属于 Design System？
[ ] 是否支持 disabled？
[ ] 是否支持 focus？
[ ] 是否支持 physical input？
[ ] 是否无默认动画？
[ ] 是否无 ripple？
[ ] 是否无 shadow？
[ ] 是否使用 Theme Token？
[ ] 是否使用受控灰阶？
[ ] 是否避免大面积黑色？
[ ] 是否具有明确 selected/pressed 语义？
[ ] 是否没有直接操作 refresh？
[ ] 是否考虑 accessibility？
[ ] 是否评估 visual cost？
[ ] 是否评估 refresh cost？
```

---

# 76. Code Review Checklist

代码 Review 必须检查：

```text
[ ] 是否硬编码 Color？
[ ] 是否硬编码 alpha？
[ ] 是否使用 Material 默认 Ripple？
[ ] 是否使用 Material elevation？
[ ] 是否引入动画？
[ ] 是否产生持续 recomposition？
[ ] 是否直接调用 refresh？
[ ] 是否依赖具体 waveform？
[ ] 是否将业务模型放入 Design System？
[ ] 是否重复实现 Interaction State？
[ ] 是否重复实现 Button/IconButton？
[ ] 是否创建了不必要的新组件？
```

---

# 77. Prohibited Patterns

以下模式默认禁止：

```kotlin
Color.Black.copy(alpha = 0.XX)
```

```kotlin
Color.White.copy(alpha = 0.XX)
```

```kotlin
Modifier.shadow(...)
```

```kotlin
Modifier.blur(...)
```

```kotlin
animate*
```

```kotlin
AnimatedVisibility(...)
```

```kotlin
AnimatedContent(...)
```

```kotlin
Crossfade(...)
```

```kotlin
rememberInfiniteTransition(...)
```

```kotlin
ripple(...)
```

```kotlin
A2
DU
GC16
GL16
```

出现在通用 UI Component API 中。

特殊情况下必须经过 Design System review。

---

# 78. Naming

公共组件统一使用：

```text
EInk + SemanticName
```

例如：

```text
EInkButton
EInkIconButton
EInkText
EInkCard
EInkListItem
EInkTopBar
```

但不要创建过度具体的组件名称：

```text
EInkBookCard
EInkOperationBarIcon
EInkReaderBackButton
EInkSearchResultItem
```

这些应尽量属于 Feature 层或者由基础组件组合。

---

# 79. Migration Strategy

迁移必须分阶段。

## Phase 1 - Foundation

实现：

```text
Color
Grayscale
Typography
Shape
Spacing
Dimension
Theme
Interaction State
```

不增加业务组件。

---

## Phase 2 - Surface and Interaction

重构：

```text
Surface
Container
Card
Divider
Clickable
Focus
Icon
Button
IconButton
```

---

## Phase 3 - Content and Navigation

重构：

```text
Text
ListItem
Section
TopBar
ActionBar
Dialog
SearchField
Selection controls
```

---

## Phase 4 - Pager

重构：

```text
PagerState
PagerLayout
ListPager
GridPager
PageIndicator
PageTurn
```

移除重复：

```text
EInkPagedList
EInkGridPagedList
PaginatedList
```

概念重复部分。

---

## Phase 5 - Refresh Architecture

建立：

```text
RefreshIntent
RefreshPolicy
RefreshController
DeviceProfile
DeviceRefreshAdapter
```

将 waveform/device-specific implementation 从 UI 层剥离。

---

## Phase 6 - Feature Migration

迁移：

```text
Bookshelf
Search
Settings
TOC
BookDetail
Reader
Home
```

Feature 只依赖 Design System。

---

# 80. Migration Rules

禁止：

```text
一次性重写全部 UI
```

优先：

```text
Foundation
→ common components
→ navigation
→ pager
→ feature
```

每个阶段必须：

```text
build
test
visual verify
```

再进入下一阶段。

---

# 81. Backward Compatibility

迁移过程中可以保留 compatibility wrappers：

```text
OldComponent
    ↓
NewComponent
```

但 compatibility wrapper：

- 不得继续扩展
- 不得添加新 API
- 必须标记 deprecated
- 最终删除

---

# 82. Definition of Done

当一个组件完成 E-Ink Design System v2.0 迁移时，必须满足：

```text
[ ] Theme-driven colors
[ ] Controlled grayscale
[ ] No default animation
[ ] No ripple
[ ] No shadow
[ ] Unified interaction states
[ ] Focus support
[ ] Physical input compatibility
[ ] Accessibility
[ ] No direct refresh hardware dependency
[ ] No waveform dependency
[ ] Clear semantic state
[ ] Low visual cost
[ ] Appropriate refresh cost
[ ] Unit tests where applicable
[ ] Visual verification on E-Ink
```

---

# 83. Architecture Target

最终架构必须满足：

```text
                     Feature
                        │
                        ▼
              E-Ink Design System
                        │
       ┌────────────────┼────────────────┐
       │                │                │
   Foundation      Interaction       Components
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
                  Semantic Events
                        │
                        ▼
                 Refresh Controller
                        │
                        ▼
                  Refresh Policy
                        │
                        ▼
                 Device Profile
                        │
                        ▼
             Device Refresh Adapter
                        │
                        ▼
                     E-Ink HW
```

Design System 不得反向依赖具体 Feature。

Component 不得直接依赖 Hardware。

Feature 不得绕过 Design System 修改核心视觉规则。

---

# 84. Core Design Principles Summary

EssentialReader E-Ink Design System v2.0 的核心不是：

```text
Black + White
```

而是：

```text
High Contrast
+
Controlled Grayscale
+
Stable State
+
Low Motion
+
Page-oriented Interaction
+
Input Agnostic
+
Refresh-aware Architecture
```

核心原则：

```text
1. 可读性优先于装饰
2. 稳定状态优先于动画
3. 受控灰阶优先于纯黑白
4. 语义状态优先于视觉特效
5. 分页优先于连续滚动
6. Focus 与 Touch 同等重要
7. 组件产生刷新意图，而不是直接控制刷新
8. 应用依赖设备能力，而不是依赖具体 waveform
9. 通用组件与业务组件严格分离
10. Visual Cost 和 Refresh Cost 必须纳入设计决策
```

---

# 85. Final Product Definition

EssentialReader E-Ink Design System 的最终目标：

> Build a native E-Ink interaction language, not a monochrome Material theme.

中文定义：

> **建立一套原生的 E-Ink 交互语言，而不是制作一个单色版 Material Theme。**

它应该让开发者在使用组件时天然得到：

```text
少动画
低刷新
低残影
高对比
合理灰阶
清晰状态
物理按键友好
分页优先
阅读友好
设备无关
```

而不是要求每一个业务开发者自己记住这些规则。

因此，Design System 的最终成功标准不是：

```text
组件数量更多
```

而是：

```text
业务代码越简单，
E-Ink 行为越正确。
```

---

# 86. Agent Implementation Directive

Agent 在执行本规范时必须遵守以下顺序：

```text
1. Inspect current implementation.
2. Inventory every existing component.
3. Identify duplicate concepts.
4. Identify feature-specific components.
5. Build Foundation tokens first.
6. Build Interaction State abstraction.
7. Refactor Surface.
8. Refactor Controls.
9. Refactor Navigation.
10. Rebuild Pager architecture.
11. Rebuild Refresh architecture.
12. Migrate Feature components.
13. Remove deprecated duplicates.
14. Run build and tests.
15. Perform E-Ink visual verification.
```

Agent 不得：

```text
- 直接批量移动文件而不分析调用关系
- 为了目录漂亮而破坏 API
- 继续增加重复组件
- 直接复制 Material 组件实现
- 将 waveform 暴露到 UI API
- 使用动画解决 E-Ink 交互问题
- 用纯黑白替代受控灰阶体系
- 将业务组件加入 Design System
```

---

# 87. Priority

如果实现资源有限，优先级必须是：

```text
P0
Foundation
Theme
Grayscale
Interaction State
Animation/Ripple prohibition
Refresh abstraction

P1
Button
IconButton
Text
Surface
ListItem
TopBar
TextField
Pager

P2
Checkbox
Radio
Switch
Slider
Dialog
Search
PageIndicator

P3
Reader-specific patterns
Advanced layouts
Device profiles
Color E-Ink variants
```

不得在 P0/P1 未完成前大量增加新的业务组件。

---

# 88. v2.0 Success Criteria

当以下条件全部满足时，可以认为 EssentialReader E-Ink Design System v2.0 基本完成：

```text
1. 所有通用组件使用统一 Theme Token。
2. 所有灰阶均受 Design System 控制。
3. 所有交互组件拥有统一 State 语义。
4. 默认无 Ripple。
5. 默认无 Animation。
6. 默认无 Shadow。
7. Focus 可以通过视觉结构明确表达。
8. Physical key / DPad 可以操作核心组件。
9. Pager 成为正式的一等架构。
10. Refresh 与 Component 解耦。
11. UI 层不依赖 A2/DU/GC16/GL16。
12. Device Profile 独立存在。
13. Feature Component 与 Design System 分离。
14. 大面积黑色使用受到约束。
15. 灰阶具有明确语义。
16. Loading 为静态或离散状态。
17. Dialog / Overlay 不依赖动画。
18. Reader Page 采用低视觉成本设计。
19. 组件数量不再通过重复组件增长。
20. 新业务页面可以主要通过 Design System 组合完成。
```

---

# 89. Final Principle

最终请始终使用以下原则判断任何设计：

```text
If it looks good on an LCD but feels wrong on E-Ink,
it is wrong.

If it needs animation to explain the state,
the state design is probably wrong.

If it needs many shades of gray to look sophisticated,
the visual hierarchy is probably wrong.

If every component knows how the E-Ink panel refreshes,
the architecture is wrong.

If every feature creates its own button/card/list item,
the Design System is incomplete.

If a user can understand the UI without animation,
prefer the static design.

If a user can operate the UI without touch,
support that interaction.

If a design can be expressed with typography, spacing,
border and controlled grayscale,
prefer that over decoration.

E-Ink is not a slower LCD.
Design for the display medium itself.
```
