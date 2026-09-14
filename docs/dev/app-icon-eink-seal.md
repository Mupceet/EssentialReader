# 墨本阅读 APK 图标·「墨本」圆章设计

状态：已接入构建（2026-09-15），真机项待验证。生成链与 SVG 源收编于 `tools/icon/`
（`make_icons.py` 生成 SVG → `build_vectors.py` 转 vector drawable）。

## 1. 目标与约束

- 替换现行 legado 彩色猫头鹰主图标（`@mipmap/ic_launcher`，紫 #656688 + 粉圈），消除与
  e-ink 产品气质的冲突。
- 硬约束：纯黑白两色（#000000 / #FFFFFF），无第三色、无灰阶设计色。
- 交付两版：**白文填充版**（黑底白字）与**朱文线条版**（白底黑字圆环）。
- minSdk 26，只需 adaptive icon（`mipmap-anydpi-v26`），无需 legacy PNG。

## 2. 设计定稿

母题为**圆形藏书印**，印文「墨本」竖读（墨上本下），华文新魏（魏碑）打底。

| 项 | 白文·填充版 | 朱文·线条版 |
|---|---|---|
| 构图 | 黑色圆底 + 白色「墨本」字画 | 白底 + 黑色圆环栏 + 黑色「墨本」字画 |
| 字面占比 | 字高 0.37 × 章面（2048 工序分辨率） | 字高 0.35（给圆环让位） |
| 圆环栏 | 无 | 外径 0.956 章面，栏宽 0.030 |
| 残破档位 | 轻（崩口只出现在笔画边缘，字面清晰） | 同左 |
| 用途 | 备选图标（经 `LauncherW` 别名「W」选用） | **主图标**（2026-09-15 用户真机定案与白文对调） |

> **对调说明**：设计初版为白文主图标、朱文备选；接入当日用户在 manifest 中对调——
> `application android:icon` 改指 `@mipmap/launcherw`（朱文为主），`LauncherW` 别名改指
> `@mipmap/ic_launcher`（白文为备选）；debug 变体 `ic_launcher_background_w` 同步纯白
> （debug 的 `ic_launcher_background` 保持 `#4D2800` 棕色以区分构建）。下文 §4.1 的层映射
> 机制不变，按资源各自生效。

印化原则：**只破不立**——磨损只表现为字画被侵蚀（边界噪声 + 字画内啄点），
不产生任何反向凸起。残破轻档参数：边界噪声权重 0.06、啄点 12 个、半径 n/220–n/140、
随机种子 11（固定，保证两版与复现一致）。

## 3. 生成管线与产物

脚本 `D:\Projects\XhsPromo\app-icon\make_icons.py`（仓库外工作区），复现命令
`python make_icons.py`。工序：新魏字库渲染 2048 → 高斯阈值膨胀匀笔（r=0.0052n，
阈值 100）→ 残破（轻档）→ 像素边界行走 + RDP（eps 3.0）描回矢量 → SVG（512 视窗，
evenodd 填充，纯折线 path，无弧线指令）。

产物：

- `svg/muben-seal-filled.svg` / `svg/muben-seal-line.svg`（矢量源）
- `png/muben-seal-{filled,line}.png`（512px 成图）
- `preview-collage.png`（总览 + 96/64/48px 小尺寸）、`seal-compare.png`（残破四档对比）

48px 下「墨本」仍可辨识；「轻」档为小尺寸可读性与章味的平衡点（已拍板）。

## 4. 接入方案

### 4.1 adaptive icon 层映射

主图标 `mipmap-anydpi-v26/ic_launcher.xml`：

| 层 | 资源 | 说明 |
|---|---|---|
| background | 纯黑 | 现有 `@color/ic_launcher_background` 改黑或新增色值 |
| foreground | 白文字画矢量（白色填充） | 实测字画外接框 169×388px（512 视窗，轻档），整体 ×0.81 缩放至 66dp 安全圆（≤313px）并按包围盒居中 |
| monochrome | 白文字画矢量（同形） | Android 13+ 主题图标，系统着色 |

朱文版接入别名体系（如 `LauncherW` 的 `@mipmap/launcherw`）：background 纯白 +
foreground = 圆环 + 字画矢量，整体（实测外接框 491px，含圆环）×0.64 缩放至安全圆；
缩放后圆环外径约占可见蒙版直径的 91%，观感与标准 adaptive 图标一致。接入时需核对
应用内换图标设置对该别名的引用方式（`LauncherW`–`Launcher6` 共 8 个
activity-alias，默认全禁用）。

### 4.2 SVG → vector drawable 要点

- 描回的 path 全为 M/L/Z 折线，`android:pathData` 直接可用；
- 多环嵌套（墨字内封闭区）须 `android:fillType="evenOdd"`（API 24+，minSdk 26 无忧）；
- 视窗建议重定标到 108（adaptive 画布），字画路径按 4.1 比例缩放后再写入；
- 生成物属编码既定设计的转换结果，转换脚本应可复跑（建议把 `make_icons.py` 连同
  SVG 源收编进仓库 `tools/icon/`，保持生成链在版本控制内）。

### 4.3 已排除的方案

- 方形章（圆形蒙版下边框被裁残、观感差）→ 已改圆章；
- 楷体直出（无金石味）→ 已换新魏 + 印化；
- 双向残破（有凸起，物理不合理）→ 已改单向侵蚀。

## 5. 风险与未验证项

- **字体授权**：华文新魏为 Windows/Office 随附字库，其 EULA 未明确许可将字形轮廓
  转曲后嵌入商业分发产品。当前描回路径属字形衍生。缓解：管线已参数化
  （`SEAL_FONT` 常数），换开源字库（需带「墨本」二字且风格相近）可整套重跑；
  上架前需确认授权口径，此为发布阻塞项。
- 真机未验证：各 launcher 蒙版（圆形/方圆/方形）下的观感、monochrome 主题图标
  着色、应用内换图标别名切换后的桌面生效路径、e-ink 设备低刷新下的图标渲染。
- 朱文版替换哪个别名资源（`launcherw` 还是新增）属实施细节，接入时按现有切换
  机制最小改动执行。

## 6. 验证清单

- [x] `:app:assembleAppDebug` 资源编译通过；
- [ ] 真机：主图标在圆形/方圆蒙版 launcher 下观感；
- [ ] 真机：Android 13+ 主题图标（monochrome）着色正常；
- [ ] 真机：换图标设置切换到朱文版别名后桌面图标生效；
- [ ] 授权口径确认后再随 release 发布。
