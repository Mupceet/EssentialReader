E-Ink Android Design System & Compose UI Engineering Specification
Version: 1.0
Status: Draft
Target: Android E-Ink Reader Application

======================================================================
1. Document Purpose
======================================================================

本规范定义本项目的 E-Ink 专用 Android UI 体系、Compose 使用约束、
组件设计规范、刷新策略以及工程级限制。

本项目不是“普通 Android App + E-Ink 适配”。

本项目采用：

    E-Ink First
    Static First
    Reading First
    Refresh Aware

作为核心设计原则。

所有 UI、交互、动画、滚动、组件和状态更新设计，都必须首先考虑
E-Ink 显示器的刷新成本、闪烁、残影、响应特性和阅读体验。

----------------------------------------------------------------------
1.1 Core Principles
----------------------------------------------------------------------

P1. Zero Motion
    UI 默认不存在任何动画。

P2. Static State Transition
    页面状态通过直接替换完成，而不是 Transition。

P3. Refresh Awareness
    Compose 状态变化不应直接等价于 E-Ink 屏幕刷新。

P4. Minimal Visual Complexity
    优先使用黑、白、灰、留白、排版和分割线建立视觉层次。

P5. No Material Motion
    Material Design 的动画、Ripple、Elevation、Motion 不作为本项目
    的 UI 基础。

P6. Reading First
    阅读内容永远优先于 UI 装饰。

P7. Predictable Interaction
    用户操作应立即产生确定性结果，不使用动画延迟用户对状态变化
    的感知。

P8. Static Layout
    尽量避免由于状态变化导致大范围 Layout Shift。

P9. Batch Updates
    高频状态变化必须聚合后再更新 UI。

P10. Architecture Enforcement
    “禁止动画”必须通过工程约束实现，而不能仅依赖开发者自觉。


======================================================================
2. Technology Strategy
======================================================================

推荐使用 Jetpack Compose，但只使用 Compose Foundation / Runtime / UI
提供的基础能力建立自己的 E-Ink Design System。

推荐架构：

    Application
        |
        v
    Screen
        |
        v
    E-Ink Design System
        |
        +-- EInkTheme
        +-- EInkTypography
        +-- EInkButton
        +-- EInkList
        +-- EInkDialog
        +-- EInkTopBar
        +-- EInkNavigation
        +-- EInkReader
        |
        v
    Jetpack Compose Foundation
        |
        v
    Android UI / Surface
        |
        v
    E-Ink Display

禁止：

    Application
        |
        v
    Material 3
        |
        v
    Compose
        |
        v
    E-Ink

Material 3 可以作为视觉和 API 设计参考，但不能成为本项目的
基础 Design System。


======================================================================
3. Compose Dependency Policy
======================================================================

3.1 Allowed

允许使用：

    androidx.compose.runtime
    androidx.compose.ui
    androidx.compose.foundation
    androidx.compose.foundation.layout
    androidx.compose.foundation.text
    androidx.compose.foundation.gestures
    androidx.compose.foundation.lazy

允许使用 Compose 的：

    @Composable
    remember
    rememberSaveable
    mutableStateOf
    derivedStateOf
    CompositionLocal
    Modifier
    Layout
    BasicText
    BasicTextField
    Canvas
    Box
    Row
    Column
    LazyColumn
    LazyRow
    BasicTextField
    clickable
    pointerInput

但是具体使用仍然必须符合 E-Ink UI Policy。


3.2 Restricted

以下 API 必须经过明确评估后使用：

    LazyColumn
    LazyRow
    scrollable
    verticalScroll
    horizontalScroll
    draggable
    pointerInput
    Canvas

原因：

这些 API 本身并非禁止，但可能产生高频状态变化、高频重组或高频
绘制。

特别是滚动场景必须遵循 Scroll Policy。


3.3 Forbidden

禁止直接使用：

    androidx.compose.animation.*
    androidx.compose.animation.core.*

禁止：

    AnimatedVisibility
    AnimatedContent
    Crossfade
    animateContentSize
    animate*AsState
    updateTransition
    Transition
    rememberInfiniteTransition
    InfiniteTransition

禁止所有自定义 AnimationSpec：

    tween
    spring
    keyframes
    repeatable
    infiniteRepeatable
    snap

即使动画 duration 为 0，也禁止。

原因：

    duration == 0

并不能从架构上保证：

    recomposition == 1
    layout == 1
    draw == 1
    refresh == 1

本项目需要从 API 层彻底消除动画。


======================================================================
4. Material Policy
======================================================================

4.1 General

不推荐：

    MaterialTheme
    Material3
    Button
    Card
    Surface
    NavigationBar
    NavigationRail
    ModalBottomSheet
    Snackbar
    FloatingActionButton

除非确认其内部行为不会引入不符合 E-Ink Policy 的机制。

推荐使用自己的：

    EInkTheme
    EInkButton
    EInkCard
    EInkSurface
    EInkDialog
    EInkNavigation
    EInkSnackbar


4.2 Ripple

禁止默认 Ripple。

禁止：

    rememberRipple()
    ripple()

所有可点击组件默认：

    indication = null

点击反馈通过状态变化实现，而不是 Ripple。


4.3 Elevation

禁止依赖 Material Elevation 建立视觉层级。

禁止：

    shadow
    tonalElevation
    shadowElevation

默认不使用阴影。

视觉层级使用：

    spacing
    typography
    divider
    border
    black/white/gray
    alignment


======================================================================
5. EInkUiPolicy
======================================================================

项目必须建立统一 UI Policy。

推荐：

    data class EInkUiPolicy(
        val animationsEnabled: Boolean = false,
        val rippleEnabled: Boolean = false,
        val shadowsEnabled: Boolean = false,
        val overscrollEnabled: Boolean = false,
        val flingEnabled: Boolean = false
    )

生产环境中：

    animationsEnabled = false
    rippleEnabled = false
    shadowsEnabled = false
    overscrollEnabled = false
    flingEnabled = false

不允许通过普通 App Setting 打开动画。

如果未来需要 LCD Preview / Development Mode，应使用独立的
Development-only configuration，而不是改变 Production Policy。


======================================================================
6. Theme
======================================================================

所有 UI 必须位于：

    EInkTheme {
        ...
    }

之内。

EInkTheme 负责：

    Color
    Typography
    Shape
    Spacing
    UI Policy
    Local configuration

示意：

    @Composable
    fun EInkTheme(
        content: @Composable () -> Unit
    ) {
        CompositionLocalProvider(
            LocalEInkUiPolicy provides EInkUiPolicy(
                animationsEnabled = false,
                rippleEnabled = false,
                shadowsEnabled = false,
                overscrollEnabled = false,
                flingEnabled = false
            )
        ) {
            content()
        }
    }


======================================================================
7. Color System
======================================================================

默认只使用：

    Black
    White
    Gray

推荐基础 Palette：

    EInkBlack
    EInkWhite
    EInkDarkGray
    EInkGray
    EInkLightGray

不要设计复杂的 Material Tonal Palette。

禁止通过大量近似灰色建立 Surface 层级。

优先：

    White background
    Black primary text
    Dark gray secondary text
    Light gray divider

注意：

如果目标设备支持多级灰阶，可以进一步增加灰阶，但不能因为支持
灰阶就大量使用灰阶。

灰阶必须具有明确语义。


======================================================================
8. Typography
======================================================================

阅读器的 Typography 是整个 Design System 最重要的组成部分之一。

必须定义：

    Display
    Title
    Heading
    Body
    BodyLarge
    BodySmall
    Caption
    Label

阅读正文优先考虑：

    fontSize
    lineHeight
    letterSpacing
    paragraphSpacing
    fontWeight

而不是颜色。

默认阅读正文：

    high contrast
    sufficient line height
    limited font weight
    no text shadow

正文不使用：

    gradient
    glow
    shadow
    animated text


======================================================================
9. Spacing
======================================================================

采用固定 Spacing Scale。

推荐：

    4
    8
    12
    16
    24
    32
    48

单位：

    dp

禁止在不同页面随意创造：

    7dp
    13dp
    19dp
    27dp

除非具有明确的视觉或硬件原因。

阅读页面允许独立使用阅读专用 spacing。


======================================================================
10. Shape
======================================================================

E-Ink UI 推荐：

    0dp
    2dp
    4dp
    8dp

禁止大量使用：

    24dp
    32dp
    pill shape

原因：

复杂圆角在低分辨率和低灰阶显示器上视觉收益有限。

阅读器正文区域优先使用矩形布局。


======================================================================
11. Button Specification
======================================================================

Button 必须：

    static
    high contrast
    no ripple
    no animation
    no shadow

推荐：

    text + border

或者：

    black background + white text

Pressed 状态不产生动画。

允许：

    state == Pressed
        -> static alternate appearance

不允许：

    state == Pressed
        -> animation
        -> ripple
        -> scale
        -> alpha transition


======================================================================
12. Navigation
======================================================================

页面切换必须采用：

    immediate replacement

例如：

    when (screen) {
        Screen.Library -> LibraryScreen()
        Screen.Reader -> ReaderScreen()
        Screen.Settings -> SettingsScreen()
    }

禁止：

    AnimatedContent
    AnimatedVisibility
    Crossfade
    slideIn
    slideOut
    fadeIn
    fadeOut

页面导航过程中不应存在过渡动画。


======================================================================
13. Navigation Architecture
======================================================================

推荐：

    Navigation State
        |
        v
    Screen
        |
        v
    Composable

例如：

    sealed interface Screen {
        data object Library : Screen
        data class Reader(val bookId: String) : Screen
        data object Settings : Screen
    }

UI：

    when (val screen = currentScreen) {
        Screen.Library ->
            LibraryScreen()

        is Screen.Reader ->
            ReaderScreen(screen.bookId)

        Screen.Settings ->
            SettingsScreen()
    }

这样可以保证页面状态变化是离散的。


======================================================================
14. Scroll Policy
======================================================================

滚动是 E-Ink App 的高风险区域。

默认：

    fling = disabled
    overscroll = disabled
    animated scroll = forbidden

禁止：

    animateScrollTo
    smoothScrollTo

推荐：

    immediate scroll
    page-based navigation

阅读器优先：

    Page Up
    Page Down
    Tap Left
    Tap Right
    Swipe Page

而不是连续滚动。


======================================================================
15. Reader Page Policy
======================================================================

阅读器是整个 App 的核心。

优先：

    fixed page
    stable layout
    stable typography
    minimal UI chrome

翻页：

    Page N
        ->
    Page N + 1

直接替换页面内容。

禁止：

    page curl
    slide animation
    fade animation
    zoom animation


======================================================================
16. Reader Gesture Policy
======================================================================

推荐：

    Tap Left
        -> previous page

    Tap Right
        -> next page

    Tap Center
        -> toggle reader controls

    Swipe Left
        -> next page

    Swipe Right
        -> previous page

手势识别本身可以使用 Compose Gesture API。

但是：

    Gesture
        !=
    Animation

手势结束后直接进入目标状态。


======================================================================
17. Loading State
======================================================================

禁止：

    spinner
    rotating indicator
    shimmer
    animated skeleton

推荐：

    static loading message

例如：

    正在加载……

或者：

    Loading

如果需要视觉反馈：

    static progress indicator

不得持续产生动画刷新。


======================================================================
18. Error State
======================================================================

错误页面必须是静态页面。

推荐：

    Title
    Description
    Retry Button

例如：

    加载失败

    无法读取当前书籍。

    [ 重试 ]

禁止：

    shake animation
    error animation
    icon animation


======================================================================
19. Dialog Policy
======================================================================

Dialog 出现时：

    immediate show

Dialog 消失时：

    immediate hide

禁止：

    fade
    scale
    slide
    expand

Dialog 使用：

    border
    white background
    black text

不使用：

    shadow
    blur
    backdrop animation


======================================================================
20. Bottom Sheet Policy
======================================================================

默认禁止 ModalBottomSheet。

原因：

Bottom Sheet 天然包含：

    slide animation
    drag animation
    gesture-driven movement

如果业务确实需要底部操作面板：

使用静态：

    BottomActionPanel

页面状态直接切换：

    panelVisible = true

而不是动画进入。


======================================================================
21. Snackbar Policy
======================================================================

默认不推荐 Snackbar。

原因：

Snackbar 通常：

    appears
    animates
    disappears automatically

推荐：

    InlineMessage

或者：

    static message area

如果必须使用 Snackbar：

    no animation
    no auto-moving
    explicit static timeout behavior


======================================================================
22. State Update Policy
======================================================================

Compose State 必须区分：

    UI state
    application state
    display state
    refresh state

不要把所有状态直接绑定到 UI。

例如：

    BatteryService
        |
        v
    batteryState
        |
        v
    Compose

不应因为电池从：

    80%
    79%
    78%
    77%

而导致整个页面刷新。

应该使用：

    derivedState
    throttling
    batching

并且只更新真正发生变化的 UI。


======================================================================
23. Refresh Architecture
======================================================================

核心原则：

    Compose Recomposition
        !=
    Screen Refresh

推荐架构：

    Application State
          |
          v
    Compose State
          |
          v
    UI Tree
          |
          v
    Rendered Buffer
          |
          v
    Display Update Controller
          |
          +---- NONE
          |
          +---- PARTIAL
          |
          +---- FULL
          |
          v
    E-Ink Display


======================================================================
24. RefreshMode
======================================================================

定义：

    enum class RefreshMode {
        NONE,
        PARTIAL,
        FULL
    }

定义：

    data class DisplayUpdate(
        val refreshMode: RefreshMode
    )


======================================================================
25. Refresh Semantics
======================================================================

NONE：

    UI 状态变化不需要立即更新 E-Ink。

PARTIAL：

    小范围 UI 更新。

FULL：

    页面整体发生重大变化。


推荐：

    Page Change
        -> FULL

    Dialog Open
        -> FULL

    Reader Controls Toggle
        -> PARTIAL/FULL

    Page Number
        -> PARTIAL

    Battery
        -> PARTIAL

    Clock
        -> PARTIAL

实际 RefreshMode 必须根据目标 E-Ink Controller 的能力调整。


======================================================================
26. Refresh Scheduling
======================================================================

禁止每一个 Compose State Change 都立即触发硬件刷新。

推荐：

    State Change
        |
        v
    Update Request
        |
        v
    Refresh Scheduler
        |
        +---- merge
        +---- debounce
        +---- priority
        |
        v
    Display Update


======================================================================
27. Refresh Priority
======================================================================

定义：

    enum class EInkUpdatePriority {
        IMMEDIATE,
        NORMAL,
        DEFERRED,
        BATCHED
    }

推荐：

    Page Change
        -> IMMEDIATE

    Dialog
        -> IMMEDIATE

    User Interaction
        -> IMMEDIATE

    Battery
        -> DEFERRED

    Clock
        -> BATCHED

    Reading Statistics
        -> DEFERRED


======================================================================
28. Dirty Region
======================================================================

未来如果底层支持局部刷新，UI 层应该能够提供：

    DirtyRegion

例如：

    data class DirtyRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

但是：

不要在第一版强制 Compose 组件直接管理 DirtyRegion。

第一阶段：

    Screen-level Refresh

第二阶段：

    Component-level Refresh

第三阶段：

    Dirty-region Refresh


======================================================================
29. Component Design
======================================================================

组件必须遵循：

    Stateless where possible

推荐：

    EInkButton(
        enabled = enabled,
        onClick = onClick
    )

而不是：

    EInkButton(
        internal animation state
    )

组件内部不得隐藏：

    animation
    delayed transition
    coroutine-driven visual transition


======================================================================
30. Coroutine Policy
======================================================================

Coroutine 本身不是禁止的。

禁止通过 Coroutine 实现 UI Animation。

禁止：

    while(true) {
        delay(...)
        updateUi()
    }

用于产生视觉动画。

允许：

    load data
    process book
    debounce user input
    schedule refresh
    background indexing


======================================================================
31. Canvas Policy
======================================================================

Canvas 可以使用。

适合：

    reading progress
    divider
    icon
    simple graphic
    page indicator

不适合：

    animated graphic
    particle
    shader effect
    blur
    transition effect


======================================================================
32. Image Policy
======================================================================

图片必须考虑 E-Ink 显示能力。

优先：

    grayscale
    high contrast
    appropriate resolution

避免：

    large color images
    unnecessary alpha
    translucent overlays

图片不应因为 UI 状态变化而重复加载。


======================================================================
33. Icon Policy
======================================================================

Icon：

    static
    monochrome
    high contrast

禁止：

    animated icon
    rotating icon
    morphing icon

例如 Loading Icon 不旋转。

推荐使用：

    "加载中"

或者静态 icon。


======================================================================
34. Accessibility
======================================================================

E-Ink First 不意味着牺牲 Accessibility。

所有可交互组件必须支持：

    semantics
    contentDescription
    enabled state
    focus

但是：

Focus feedback 不使用动画。

推荐：

    static border
    static background change


======================================================================
35. Touch Feedback
======================================================================

默认无 Ripple。

允许：

    pressed -> black background

或者：

    pressed -> border change

但是必须：

    instantaneous
    no animation


======================================================================
36. Focus
======================================================================

Focus 状态必须是静态视觉变化。

推荐：

    border thickness
    background color
    text weight

禁止：

    blinking cursor animation
    animated focus ring


======================================================================
37. Text Cursor
======================================================================

文本输入是特殊情况。

如果平台 TextField 默认 Cursor Blink：

必须评估目标 E-Ink 设备表现。

如果存在明显闪烁：

    disable cursor animation

或：

    使用静态 cursor / 自定义输入组件。

本规范默认：

    不允许 UI 中存在周期性视觉动画。


======================================================================
38. Periodic UI Updates
======================================================================

禁止 UI 高频周期刷新。

尤其禁止：

    16ms timer
    60fps update
    30fps update

除非属于：

    临时开发工具
    性能测试

普通业务 UI 不得依赖高频 timer。


======================================================================
39. Clock Policy
======================================================================

时钟属于特殊场景。

不要：

    每秒刷新整个 Screen

推荐：

    独立 Clock Region

并通过：

    partial refresh

更新。

如果目标设备对 partial refresh 支持不好：

    降低更新频率
    或用户主动查看


======================================================================
40. List Policy
======================================================================

List 推荐：

    LazyColumn

但必须避免：

    animated item placement
    animated content
    item fade
    item movement

禁止：

    animateItem
    animateItemPlacement

列表数据变化必须直接反映。

如果大量 item 变化：

    batch update


======================================================================
41. List Item
======================================================================

推荐：

    title
    secondary text
    metadata
    divider

例如：

    三国演义
    罗贯中
    第 132 / 800 页
    --------------------

不推荐：

    card
    shadow
    thumbnail-heavy layout
    animated selection


======================================================================
42. Selection
======================================================================

Selection 必须立即变化：

    selected = true

视觉变化：

    static background
    border
    text weight

禁止：

    selection animation


======================================================================
43. Search
======================================================================

搜索过程中不要对结果列表进行动画。

推荐：

    User input
        |
        v
    debounce
        |
        v
    Search
        |
        v
    replace result list

禁止：

    result fade
    result slide
    item animation


======================================================================
44. Page Transition
======================================================================

页面 Transition 统一定义为：

    NONE

所有 Screen Navigation 默认：

    immediate

未来如果某些 E-Ink Controller 支持硬件级快速刷新，
也不得因此恢复传统 UI Animation。

E-Ink 快速刷新 != LCD Animation。


======================================================================
45. Performance Rules
======================================================================

Compose 性能优化目标不是单纯：

    FPS

而是：

    minimal recomposition
    minimal layout
    minimal draw
    minimal buffer update
    minimal hardware refresh


======================================================================
46. Recomposition Rules
======================================================================

避免：

    mutable state 放在高层级

导致整个 Screen 重组。

推荐：

    state hoisting
    derivedStateOf
    stable models

尤其：

    ReaderScreen

必须避免因为：

    page progress
    clock
    battery
    network status

导致整个阅读页面重组。


======================================================================
47. Stable Model
======================================================================

UI Model 尽量保持稳定：

    @Immutable
    data class BookUiModel(...)

避免：

    mutable collections
    mutable UI model
    frequently changing object identity


======================================================================
48. Reader Architecture
======================================================================

推荐：

    ReaderViewModel
          |
          +-- Book
          +-- Page
          +-- Progress
          +-- Settings
          |
          v
    ReaderUiState
          |
          v
    ReaderScreen
          |
          +-- ReaderPage
          +-- ReaderControls
          +-- PageIndicator
          |
          v
    Refresh Scheduler


======================================================================
49. Reader State
======================================================================

建议：

    data class ReaderUiState(
        val bookId: String,
        val page: Int,
        val pageCount: Int,
        val controlsVisible: Boolean,
        val loading: Boolean,
        val error: ReaderError?
    )

状态必须是离散状态。

例如：

    page = 100

而不是：

    pageAnimationProgress = 0.73


======================================================================
50. UI Architecture Rule
======================================================================

所有 UI 状态必须可以用：

    State -> UI

直接描述。

不要使用：

    State A
        ->
    Transition State
        ->
    State B

即：

    A -> B

而不是：

    A -> A' -> A'' -> B


======================================================================
51. Forbidden Visual Effects
======================================================================

以下全部禁止：

    blur
    backdrop blur
    gradient animation
    opacity animation
    scale animation
    rotation animation
    translation animation
    shimmer
    pulse
    bounce
    shake
    ripple
    glow
    particle
    parallax
    animated shadow
    animated color
    animated size
    animated typography


======================================================================
52. Forbidden Android APIs / Concepts
======================================================================

如果用于 UI Animation，禁止：

    ViewPropertyAnimator
    ObjectAnimator
    ValueAnimator
    AnimatorSet
    TransitionManager
    LayoutTransition

禁止：

    Lottie
    GIF animation
    animated vector drawable

除非明确属于非 UI 的后台处理场景。


======================================================================
53. Android System Animation
======================================================================

应用不得依赖系统 Window Animation。

Dialog / Activity / Window transition 必须配置为：

    no animation

避免：

    Activity enter animation
    Activity exit animation
    Window transition
    shared element transition


======================================================================
54. Activity Architecture
======================================================================

推荐单 Activity。

例如：

    MainActivity
        |
        v
    EInkApp
        |
        v
    Screen State

避免多个 Activity 之间进行传统 Android 页面转场。


======================================================================
55. Configuration Changes
======================================================================

Configuration change 不应触发视觉 Transition。

页面重新创建后：

    restore state
    render directly


======================================================================
56. Density / Resolution
======================================================================

UI 必须支持不同 E-Ink：

    resolution
    density
    aspect ratio

不能假设：

    1080 x 1920
    420 dpi

是固定条件。

推荐使用：

    dp
    sp

但是阅读排版需要考虑实际物理尺寸。


======================================================================
57. Orientation
======================================================================

阅读器必须明确支持：

    portrait
    landscape

如果产品只支持一种方向：

必须在 Application 层固定，而不是运行过程中旋转动画。


======================================================================
58. Refresh Boundary
======================================================================

UI 层应该定义 Refresh Boundary。

推荐：

    App
      |
      +-- Library Boundary
      |
      +-- Reader Boundary
      |
      +-- Dialog Boundary
      |
      +-- Control Boundary

每个 Boundary 独立决定：

    refresh mode
    priority
    dirty region


======================================================================
59. Refresh Controller
======================================================================

推荐抽象：

    interface EInkRefreshController {

        fun requestRefresh(
            mode: RefreshMode,
            priority: EInkUpdatePriority
        )

        fun requestRefresh(
            region: DirtyRegion,
            mode: RefreshMode,
            priority: EInkUpdatePriority
        )
    }

Compose 不直接操作硬件。

Compose 只产生：

    UI state
    refresh intent

Hardware layer 决定：

    actual refresh


======================================================================
60. Refresh Intent
======================================================================

推荐：

    data class RefreshIntent(
        val mode: RefreshMode,
        val priority: EInkUpdatePriority,
        val reason: RefreshReason
    )

例如：

    enum class RefreshReason {
        PAGE_CHANGE,
        DIALOG,
        USER_INTERACTION,
        DATA_UPDATE,
        BATTERY,
        CLOCK,
        SYSTEM
    }


======================================================================
61. Refresh Scheduler
======================================================================

Refresh Scheduler 负责：

    deduplicate
    merge
    prioritize
    throttle
    batch

例如：

    Battery 80 -> 79
    Battery 79 -> 78
    Battery 78 -> 77

不应该：

    refresh
    refresh
    refresh

而应该：

    update latest value
    refresh once


======================================================================
62. Full Refresh Policy
======================================================================

Full refresh 不应过于频繁。

典型触发：

    page change
    reader open
    major screen change
    accumulated ghosting

具体策略必须由目标硬件验证。

不要在 UI 层硬编码：

    every N pages

除非硬件测试证明该策略合理。


======================================================================
63. Partial Refresh Policy
======================================================================

Partial refresh 适合：

    page number
    battery
    clock
    static control
    small dialog area

但是必须考虑：

    ghosting
    waveform
    temperature
    controller limitations

因此：

    Partial refresh is a capability, not a guarantee.


======================================================================
64. Hardware Abstraction
======================================================================

UI System 不应该依赖某个具体 E-Ink Vendor。

不要在 Compose 层出现：

    EinkVendorX
    EinkControllerY

硬件相关逻辑位于：

    EInk Display HAL / Driver / Platform Adapter


======================================================================
65. Test Strategy
======================================================================

必须建立：

    UI test
    screenshot test
    recomposition test
    refresh intent test
    hardware refresh test

尤其测试：

    Page Change
    Dialog
    List
    Button
    Search
    Reader Controls


======================================================================
66. Animation Guard
======================================================================

项目必须建立 Animation Guard。

目标：

    防止任何 Animation API 进入 Production UI。

建议：

    Static Analysis
        +
    Code Review
        +
    Architecture Rule

最少应检查：

    androidx.compose.animation
    androidx.compose.animation.core
    android.animation
    android.view.animation


======================================================================
67. Forbidden API Lint
======================================================================

建议建立：

    EInkForbiddenApiLint

禁止：

    AnimatedVisibility
    AnimatedContent
    Crossfade
    animateContentSize
    animate*AsState
    updateTransition
    rememberInfiniteTransition
    animateScrollTo
    smoothScrollTo
    animateItem
    animateItemPlacement

以及 Android Animation Framework。


======================================================================
68. Material Guard
======================================================================

如果项目决定完全不使用 Material：

禁止：

    androidx.compose.material.*
    androidx.compose.material3.*

如果某些 Material API 确实需要使用：

必须：

    wrapper
    documented exception
    verified no-animation behavior


======================================================================
69. Component Naming
======================================================================

所有项目 UI 组件统一使用：

    EInkXxx

例如：

    EInkButton
    EInkTextField
    EInkDialog
    EInkList
    EInkTopBar
    EInkDivider
    EInkPageIndicator
    EInkReaderControls

不要：

    MyButton
    CustomButton
    CommonButton

避免多个 Design System 并存。


======================================================================
70. Package Structure
======================================================================

推荐：

    ui/
      eink/
        theme/
        component/
        layout/
        navigation/
        reader/
        refresh/
        policy/

例如：

    ui/eink/theme
    ui/eink/component
    ui/eink/reader
    ui/eink/refresh


======================================================================
71. Component API Design
======================================================================

组件 API 优先：

    state hoisted
    stateless
    explicit

例如：

    EInkButton(
        enabled = enabled,
        onClick = onClick,
        content = ...
    )

避免：

    EInkButton(
        internalState = ...
    )

组件不负责业务逻辑。


======================================================================
72. Preview
======================================================================

Compose Preview 可以正常使用。

建议提供：

    EInkPreview

用于验证：

    black/white
    grayscale
    typography
    spacing
    contrast


======================================================================
73. LCD Development Preview
======================================================================

开发阶段允许使用普通 LCD Preview。

但是 Preview 不得改变 E-Ink UI API。

例如：

    EInkButton

在 LCD Preview 中仍然：

    no ripple
    no animation

这样可以确保：

    Preview == Production UI semantics


======================================================================
74. Design Review Checklist
======================================================================

每一个新 UI 组件必须回答：

    1. 是否包含动画？
    2. 是否包含 Ripple？
    3. 是否包含 Shadow？
    4. 是否导致高频 State 更新？
    5. 是否可能导致高频 Recomposition？
    6. 是否可能导致高频 Draw？
    7. 是否需要 Partial Refresh？
    8. 是否需要 Full Refresh？
    9. 是否可以静态表达？
    10. 是否可以减少视觉复杂度？


======================================================================
75. Screen Review Checklist
======================================================================

每一个 Screen 必须回答：

    1. 页面状态有哪些？
    2. 页面切换是否完全静态？
    3. 哪些状态会导致 UI 更新？
    4. 哪些状态需要实际屏幕刷新？
    5. 哪些更新可以 Batch？
    6. 哪些更新可以 Partial Refresh？
    7. 哪些更新必须 Full Refresh？
    8. 是否存在周期性刷新？
    9. 是否存在滚动？
    10. 是否存在动画 API？


======================================================================
76. Performance Definition
======================================================================

本项目性能不能只使用：

    FPS

衡量。

核心指标：

    Recomposition Count
    Layout Count
    Draw Count
    Buffer Update Count
    Partial Refresh Count
    Full Refresh Count
    Refresh Latency
    Ghosting
    Flicker
    Input-to-Visual Latency
    Battery Consumption


======================================================================
77. UX Definition
======================================================================

E-Ink App 的“流畅”定义：

    not animation smoothness

而是：

    low latency
    predictable
    stable
    readable
    low flicker
    low ghosting


======================================================================
78. Agent Implementation Rules
======================================================================

任何 Agent 修改 UI 时必须遵循：

    Rule 1:
        不得引入动画。

    Rule 2:
        不得直接引入 Material UI 组件。

    Rule 3:
        新组件必须进入 E-Ink Design System。

    Rule 4:
        不得把 Compose Recomposition 当成 Display Refresh。

    Rule 5:
        不得新增高频 UI State。

    Rule 6:
        页面切换必须直接替换。

    Rule 7:
        Reader 优先使用 Page Navigation。

    Rule 8:
        所有刷新需求必须通过 Refresh Controller。

    Rule 9:
        所有例外必须记录原因。

    Rule 10:
        如果不确定某 API 是否适合 E-Ink，默认禁止，
        先验证再使用。


======================================================================
79. Definition of Done
======================================================================

一个 UI Feature 只有满足以下条件才算完成：

    [ ] 没有 Animation API
    [ ] 没有 Ripple
    [ ] 没有 Shadow
    [ ] 没有自动视觉动画
    [ ] 页面 Transition 为 NONE
    [ ] Scroll 没有 fling / overscroll animation
    [ ] UI State 更新频率已评估
    [ ] Recomposition 已评估
    [ ] RefreshMode 已定义
    [ ] RefreshPriority 已定义
    [ ] Refresh Reason 已定义
    [ ] 大范围刷新行为已验证
    [ ] Partial Refresh 行为已验证（如果适用）
    [ ] Ghosting 已验证
    [ ] Flicker 已验证
    [ ] 真实 E-Ink 硬件已验证


======================================================================
80. Final Architecture
======================================================================

最终推荐形成以下体系：

                         Application
                              |
                              v
                        ViewModel / State
                              |
                              v
                       Compose UI Layer
                              |
                    +---------+---------+
                    |                   |
                    v                   v
              E-Ink Components    Refresh Intent
                    |                   |
                    v                   v
              Rendered UI        Refresh Scheduler
                    |                   |
                    +---------+---------+
                              |
                              v
                     EInk Refresh Controller
                              |
                              v
                       Display HAL / Driver
                              |
                              v
                         E-Ink Panel


核心边界：

    Compose
        负责：
            What should be displayed?

    Refresh Controller
        负责：
            When should the display update?

    Display HAL
        负责：
            How should the E-Ink hardware update?


======================================================================
81. Fundamental Principle
======================================================================

本项目最重要的工程原则：

    Compose State Change
        !=
    Recomposition
        !=
    Render
        !=
    Buffer Update
        !=
    E-Ink Refresh

这五个概念必须在架构上保持独立。


最终目标不是：

    “让 Compose 在墨水屏上运行得像 LCD 一样。”

而是：

    “利用 Compose 描述一个天然适合 E-Ink 的静态 UI，
     并通过独立的 Refresh Controller 控制真正的屏幕更新。”

E-Ink UI 的第一性原则：

    No Motion.
    No Unnecessary Refresh.
    No Unnecessary Recomposition.
    No Unnecessary Visual Complexity.
    Reading First.