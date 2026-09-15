# 墨本阅读 APK 图标·「墨本」圆章设计

状态：v2 单字「墨」·残破中档已实现（2026-09-15 拍板，未提交）；v1「墨本」双字·轻档
已随 3.26.17-beta.10 发布。生成链与 SVG 源收编于 `tools/icon/`
（`make_icons.py` 生成 SVG → `build_vectors.py` 转 vector drawable）。

## 1. 目标与约束

- 替换现行 legado 彩色猫头鹰主图标（`@mipmap/ic_launcher`，紫 #656688 + 粉圈），消除与
  e-ink 产品气质的冲突。
- 硬约束：纯黑白两色（#000000 / #FFFFFF），无第三色、无灰阶设计色。
- 交付两版：**白文填充版**（黑底白字）与**朱文线条版**（白底黑字圆环）。
- minSdk 26，只需 adaptive icon（`mipmap-anydpi-v26`），无需 legacy PNG。

## 2. 设计定稿

母题为**圆形藏书印**，华文新魏（魏碑）打底。

**当前版（v2，2026-09-15 拍板）：单字「墨」居中，残破中档。** 相比已随 beta.10 发布的
「墨本」双字版（残破轻），单字占面从 0.35–0.37 提到 0.60 章面，48px 下笔画完全清晰，
中档残破相对字面不再显糊；代价是印文只剩「墨」，名号由章形与字体承载。

| 项 | 白文·填充版 | 朱文·线条版 |
|---|---|---|
| 构图 | 黑色圆底 + 白色居中「墨」 | 白底 + 黑色圆环栏 + 黑色居中「墨」 |
| 字面占比 | 字高 0.60 × 章面（2048 工序分辨率） | 同左（圆环外让其自然留白） |
| 圆环栏 | 无 | 外径 0.956 章面，栏宽 0.030 |
| 残破档位 | 中（单向侵蚀：边界噪声 + 字画内啄点） | 同左 |
| 用途 | 备选图标（经 `LauncherW` 别名「W」选用） | **主图标**（2026-09-15 用户真机定案与白文对调） |

> **对调说明**：设计初版为白文主图标、朱文备选；接入当日用户在 manifest 中对调——
> `application android:icon` 改指 `@mipmap/launcherw`（朱文为主），`LauncherW` 别名改指
> `@mipmap/ic_launcher`（白文为备选）；debug 变体 `ic_launcher_background_w` 同步纯白
> （debug 的 `ic_launcher_background` 保持 `#4D2800` 棕色以区分构建）。下文 §4.1 的层映射
> 机制不变，按资源各自生效。

印化原则：**只破不立**——磨损只表现为字画被侵蚀（边界噪声 + 字画内啄点），
不产生任何反向凸起。残破中档参数：边界噪声权重 0.10、啄点 24 个、半径 n/220–n/110、
随机种子 11（固定，保证两版与复现一致）。

## 3. 生成管线与产物

生成链已收编仓库 `tools/icon/`（上游工作区 `D:\Projects\XhsPromo\app-icon\` 同步）：
`make_icons.py` 出 SVG → `build_vectors.py` 转 vector drawable。工序：新魏字库渲染
2048 → 高斯阈值膨胀匀笔（r=0.0052n，阈值 100）→ 残破（中档）→ 像素边界行走 +
RDP（eps 3.0）描回矢量 → SVG（512 视窗，evenodd 填充，纯折线 path，无弧线指令）。
字面档位由 `seal_marks` 的 `chars` 参数表达：默认单字「墨」，墨本双字版可传参回跑。

产物：

- `tools/icon/svg/muben-seal-{filled,line}.svg`（矢量源）、`tools/icon/out/` 三份
  drawable（转换暂存，已 gitignore）
- 上游工作区另有 `preview-collage.png`、`seal-compare.png`、`preview-launcher-crop.png`
  （72dp 可见圆模拟）等预览

48px 下单字「墨」笔画完全清晰；这是单字版相对墨本双字版的主要收益。

## 4. 接入方案

### 4.1 adaptive icon 层映射

主图标 `mipmap-anydpi-v26/ic_launcher.xml`：

| 层 | 资源 | 说明 |
|---|---|---|
| background | 纯黑 | `@color/ic_launcher_background` = #000000 |
| foreground | 白文字画矢量（白色填充） | 圆拟合缩放：字画最远顶点落在 72dp 典型可见圆（512 视窗半径 170.5px）上，白文 ×1.108、按包围盒居中；如需绝对不裁可改用 66dp 严格安全圆（半径 156.7px）重跑 `build_vectors.py` |
| monochrome | 白文字画矢量（同形） | Android 13+ 主题图标，系统着色 |

朱文版经 `LauncherW` 别名（`@mipmap/launcherw`）接入：background 纯白 +
foreground/monochrome = 圆环 + 字画矢量，圆拟合 ×0.694（圆环外缘落在可见圆上，
与已发布墨本版包络一致）。应用内换图标选「W」即切换到白文版
（`LauncherIconHelp.changeIcon("W")` 按类名后缀启停 alias，纯资源生效）。

### 4.2 SVG → vector drawable 要点

- 描回的 path 全为 M/L/Z 折线，`android:pathData` 直接可用；
- 多环嵌套（墨字内封闭区）须 `android:fillType="evenOdd"`（API 24+，minSdk 26 无忧）；
- 实现保持 512 视窗（与 SVG 同一坐标系，圆拟合缩放直接写进坐标，无 `<group>` 包装）；
- 生成链在版本控制内：`tools/icon/make_icons.py` + `svg/` 源 + `build_vectors.py`。

### 4.3 已排除的方案

- 方形章（圆形蒙版下边框被裁残、观感差）→ 已改圆章；
- 楷体直出（无金石味）→ 已换新魏 + 印化；
- 双向残破（有凸起，物理不合理）→ 已改单向侵蚀；
- 墨本双字（48px 下「墨」字密集处偏糊）→ 迭代为单字「墨」；双字版随 beta.10 发布过，
  可经 `chars` 参数回跑。

## 5. 风险与未验证项

- **字体授权**：华文新魏为 Windows/Office 随附字库，其 EULA 未明确许可将字形轮廓
  转曲后嵌入商业分发产品。当前描回路径属字形衍生。缓解：管线已参数化
  （`SEAL_FONT` 常数），换开源字库（需带「墨」字且风格相近）可整套重跑；
  上架前需确认授权口径，此为发布阻塞项。
- 真机未验证：各 launcher 蒙版（圆形/方圆/方形）下的观感、monochrome 主题图标
  着色、应用内换图标别名切换后的桌面生效路径、e-ink 设备低刷新下的图标渲染。

## 6. 验证清单

- [x] `:app:assembleAppDebug` 资源编译通过；
- [ ] 真机：主图标在圆形/方圆蒙版 launcher 下观感；
- [ ] 真机：Android 13+ 主题图标（monochrome）着色正常；
- [ ] 真机：换图标设置切换到朱文版别名后桌面图标生效；
- [ ] 授权口径确认后再随 release 发布。
