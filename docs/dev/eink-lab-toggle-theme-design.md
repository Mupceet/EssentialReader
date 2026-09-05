# 实验室「墨水屏显示」开启时默认切换「电子书」主题:设计

状态:设计定稿(待实施)。

决策记录:

- 2026-09-05(用户确认):开启开关时**无条件**把主题切为「电子书」(已手动选择
  其他主题或应用自定义主题包的用户也被覆盖,重新应用主题包可恢复)。
- 2026-09-05(用户确认):关闭开关/从 E-Ink 界面退回完整模式时**不回退**主题——
  「电子书」主题保持生效,正是完整模式体验的保障。
- 2026-09-05(用户确认):实现取方案 A(ViewModel 组合双 Gateway,非原子双写);
  顺带修主题选择器对当前选中项的可见性、更新开关文案。

## 1. 背景与问题

「实验室 → 墨水屏显示」开关(`LabConfigScreen.kt`)打开后,应用整栈切换进
E-Ink 界面;退出时(`EinkMainActivity.kt`)把 `labEInkDisplay` 置 false 回到完整
模式。当前开关与主题零联动:用户若原为动态取色/深色等彩色主题,退回完整模式后
彩色主题在墨水屏设备上观感差,对话框与阅读排版也不会走墨水屏友好分支
(`AppConfig.isEInkMode` 仅在 `appTheme == "4"` 时为 true)。

目标:打开开关即默认选中宿主自己的「电子书」主题(值 `"4"`,`AppThemeMode.Elink`,
纯黑白配色),使切换回完整模式后体验仍有保障。

现状事实:

- 主题写入路径:`ThemeSettingsGateway.update { it.copy(appTheme = "4") }`,键
  `PreferKey.appTheme`("app_theme"),`appTheme` 属于 gateway 映射持久化的 59 键
  之一;默认主题 `"0"` 动态取色。
- 已有单向关联:主题选择器中「电子书」项仅在实验室开关开启时可见
  (`ThemeConfigScreen.kt` 两处过滤 `value != "4" || showEInkTheme`);反向关联
  不存在,本设计补上。

非目标:

- 不改 E-Ink 界面进出逻辑、冷启动分流(`MainActivity` 按 flag 路由)与
  `modules/eink` 任何代码。
- 不做主题记忆/恢复(关闭时不回退)。
- 不为两个设置模型引入跨模型原子写或 `*SettingsUpdate` 分发类型。

## 2. 行为定义

1. **打开「墨水屏显示」开关**:先把主题无条件设为「电子书」(`"4"`,幂等,已选
   时无感),再翻 `labEInkDisplay`;随后现有 `LaunchedEffect` 整栈进入 E-Ink 界面
   (不变)。写入顺序为主题先行,保证最坏中断态是「主题已切、开关未开」(无害)
   而非「开关已开、主题仍彩色」(正是要防的状态)。
2. **关闭开关 / E-Ink 界面退出回完整模式**:不碰主题。「电子书」保持生效,
   `AppConfig.isEInkMode` 为 true,完整模式对话框、阅读排版走墨水屏友好分支。
   用户可在主题页手动换走。
3. **冷启动**:不变。

## 3. 实现设计(方案 A:ViewModel 组合双 Gateway)

### 3.1 `LabConfigViewModel.kt`

注入 `ThemeSettingsGateway`(Koin `viewModelOf` 自动解析新参数,无需改注册),
`onIntent` 中在现有 `settingsGateway.update` 之前:

```kotlin
if (intent is LabConfigIntent.SetEInkDisplay && intent.value) {
    themeSettingsGateway.update { it.copy(appTheme = "4") }
}
```

同一协程内顺序执行。`"4"` 沿用仓库现有裸值惯用法(`AppConfig.isEInkMode`、
`ThemeConfigScreen` 过滤同款),不新造单点常量。UiState 无需变化。

### 3.2 主题选择器可见性小修(必要,2 处)

`ThemeConfigScreen.kt` 两处过滤(Miuix 引擎下拉约 :210、主题色选择器约 :235)由

```kotlin
value != "4" || state.showEInkTheme
```

改为「当前选中即 `"4"` 时也可见」:

```kotlin
value != "4" || state.showEInkTheme || theme.appTheme == "4"
```

否则退出墨水屏后已选中的电子书主题在选择器里凭空消失,用户看不到当前选中项
也无法换走;一旦手动换到其他主题,`"4"` 恢复隐藏,维持原「非墨水屏不展示」意图。

### 3.3 开关文案更新(4 条 string,顺带修正与现状脱节的旧文案)

| 键 | 现值(zh-rCN) | 新值(zh-rCN) |
|---|---|---|
| `lab_eink_display_summary` | 在平板界面显示墨水屏选项 | 开启后整体切换到墨水屏界面,并默认切换为「电子书」主题 |
| `lab_eink_display_hint` | 可在 主题 → 显示 中调整墨水屏设置 | 已切换为「电子书」主题,退出墨水屏后完整模式将继续使用该主题 |

`values/strings.xml` 对应英文同步:
summary `Switch to the E-Ink interface and apply the "E-Book" theme when enabled`;
hint `"E-Book" theme applied; it stays active after returning to full mode`。

## 4. 测试与验证

- 新增 `LabConfigViewModelTest`(仿 `OtherConfigViewModelTest` 的 fake gateway
  模式):
  1. `SetEInkDisplay(true)` → 两个 gateway 均被更新(`appTheme == "4"`、
     `eInkDisplay == true`);
  2. `SetEInkDisplay(false)` → 仅 LabSettings 更新,主题 gateway 无写入;
  3. `SetEnabled` 等其他 intent → 主题 gateway 无写入。
- 命令(按序):
  1. `.\gradlew.bat :app:compileAppDebugKotlin`
  2. `.\gradlew.bat :app:testAppDebugUnitTest --tests "*LabConfigViewModelTest*"`
  3. `git diff --check`
- 手动确认(真机,实施后):开关打开→进入 E-Ink;退出回完整模式→主题为电子书、
  主题页能看到「电子书」为当前选中;换走后「电子书」重新隐藏。

## 5. 风险与未验证项

- 两次 DataStore 写非原子:接受(顺序已保证最坏态无害,窗口毫秒级)。
- 自定义主题包用户被覆盖:按用户拍板接受,重新应用可恢复;`customMode`、
  `bookInfoInputColor` 等主题包事务字段不受影响(不经普通 gateway 落盘)。
- 选择器可见性改动无 UI 自动化,依赖编译 + 真机手动确认。
