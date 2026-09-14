# 墨本圆章图标接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已定稿的「墨本」圆章（白文/朱文，轻档残破）接入应用：白文版替换主图标三层（foreground/background/monochrome），朱文版接入 `LauncherW` 备选别名。

**Architecture:** 生成链收编进 `tools/icon/`；`build_vectors.py` 把 SVG 字画 path（512 视窗、纯 M/L/Z 折线）按外接框实测缩放进 adaptive icon 66dp 安全圆（512 视窗下 313px），产出三份 vector drawable；再改颜色资源与两个 adaptive-icon XML 接线。不改任何 Kotlin（`LauncherIconHelp.changeIcon("W")` 按类名后缀切换别名，纯资源生效）。

**Tech Stack:** Python 3（生成/转换脚本，仅构建期使用）、Android vector drawable（`fillType="evenOdd"`，API 24+）、adaptive icon（minSdk 26，无 legacy PNG 需求）。

## Global Constraints

- 纯黑白两色：`#000000` / `#FFFFFF`，无第三色、无灰阶设计色（抗锯齿边缘灰度除外）。
- 不动 `launcher0-6` 别名与 `drawable/ic_launcher1-7*.xml` 遗留资源；`mipmap-*/ic_launcher*.webp` 为 API<26 遗留（minSdk 26 永走 anydpi-v26），本次不删。
- 设计定稿参数见 `docs/dev/app-icon-eink-seal.md`；SVG 源：白文 `svg/muben-seal-filled.svg`（圆底是 `<circle>` 元素，转换只取 `<path>`=字画）、朱文 `svg/muben-seal-line.svg`（圆环+字画单条 path）。
- **仓库约定：执行者不自行 commit。** 各 Task 的 commit 步骤仅在用户显式说"提交"后执行，message 用各步骤给出的文案。
- 构建命令用 `.\gradlew.bat`（JDK 21）。

**现状事实（已核实）：**

- `mipmap-anydpi-v26/ic_launcher.xml`：background=`@color/ic_launcher_background`、foreground=`@drawable/ic_launcher_foreground`、monochrome=`@drawable/ic_launcher_foreground_m`。
- `mipmap-anydpi-v26/ic_launcher_round.xml`：同 background/foreground，无 monochrome。
- `mipmap-anydpi-v26/launcherw.xml`：background=`@color/ic_launcher_background_w`、foreground=`@drawable/ic_launcher_foreground`（与主图标共用，须拆开）、monochrome=`@drawable/ic_launcher4`。
- `values/colors.xml`：`ic_launcher_background`=#2E2D3D、`ic_launcher_background_w`=#F2F1F6。
- 实测（轻档、2048 工序）：白文字画外接框 169×388（512 视窗），朱文整体 491×491。

---

### Task 1: 收编生成链并产出三份 vector drawable（暂存 out/）

**Files:**
- Create: `tools/icon/make_icons.py`（自 `D:\Projects\XhsPromo\app-icon\make_icons.py` 复制）
- Create: `tools/icon/svg/muben-seal-filled.svg`、`tools/icon/svg/muben-seal-line.svg`（复制）
- Create: `tools/icon/build_vectors.py`
- Create: `tools/icon/out/ic_launcher_foreground.xml`、`ic_launcher_foreground_m.xml`、`ic_launcher_foreground_zhuwen.xml`（脚本产物）

**Interfaces:**
- Produces: `tools/icon/out/` 三份 XML（108dp、viewport 512、单 `<path>` `fillType="evenOdd"`），Task 2/3 按文件名复制进 `res/drawable/`。
- 复现链：`make_icons.py`（XhsPromo 同名脚本收编，`SEAL_LEVEL=1`）→ `svg/` 两份源 → `build_vectors.py`。

- [ ] **Step 1: 复制生成链与 SVG 源进仓库**

```bash
mkdir -p tools/icon/svg
cp /d/Projects/XhsPromo/app-icon/make_icons.py tools/icon/make_icons.py
cp /d/Projects/XhsPromo/app-icon/svg/muben-seal-filled.svg tools/icon/svg/
cp /d/Projects/XhsPromo/app-icon/svg/muben-seal-line.svg tools/icon/svg/
```

- [ ] **Step 2: 写转换脚本 `tools/icon/build_vectors.py`**

```python
# -*- coding: utf-8 -*-
"""把墨本圆章 SVG 字画转成 Android vector drawable。

输入: tools/icon/svg/muben-seal-{filled,line}.svg（512 视窗，纯 M/L/Z 折线）
输出: tools/icon/out/ 三份 drawable XML；字画按外接框实测缩放，
      整体落入 adaptive icon 66dp 安全圆（512 视窗下直径 313px）并按包围盒居中。
用法: python tools/icon/build_vectors.py
"""
import os
import re
import xml.etree.ElementTree as ET

BASE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(BASE, "out")
SAFE_DIAMETER = 313.0  # 66/108 * 512
PAIR = re.compile(r"(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)")

def load_first_path(svg_name):
    root = ET.parse(os.path.join(BASE, "svg", svg_name)).getroot()
    paths = [p.get("d") for p in root.iter("{http://www.w3.org/2000/svg}path")]
    assert paths, f"{svg_name} 中没有 <path>"
    return paths[0]

def fit_safe(d):
    """按外接框缩放平移，落入安全圆并居中；返回新 d 与缩放系数。"""
    pts = [(float(x), float(y)) for x, y in PAIR.findall(d)]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    x0, y0, x1, y1 = min(xs), min(ys), max(xs), max(ys)
    scale = min(SAFE_DIAMETER / (x1 - x0), SAFE_DIAMETER / (y1 - y0))
    tx = 256 - (x0 + x1) / 2 * scale
    ty = 256 - (y0 + y1) / 2 * scale

    def repl(m):
        return f"{float(m.group(1)) * scale + tx:.1f},{float(m.group(2)) * scale + ty:.1f}"

    return PAIR.sub(repl, d), scale

def drawable_xml(d, fill_color):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="512"\n'
        '    android:viewportHeight="512">\n'
        '    <path\n'
        f'        android:fillColor="{fill_color}"\n'
        '        android:fillType="evenOdd"\n'
        f'        android:pathData="{d}" />\n'
        '</vector>\n'
    )

JOBS = [
    # (输出文件名, 源 SVG, 填充色)；白文 SVG 的圆底是 <circle>，iter("path") 只取字画
    ("ic_launcher_foreground.xml", "muben-seal-filled.svg", "#FFFFFF"),
    ("ic_launcher_foreground_m.xml", "muben-seal-filled.svg", "#FFFFFF"),  # monochrome 由系统着色
    ("ic_launcher_foreground_zhuwen.xml", "muben-seal-line.svg", "#FF000000"),
]

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for fname, svg, fill in JOBS:
        d, scale = fit_safe(load_first_path(svg))
        assert 0.3 < scale <= 1.0, f"{fname} 缩放系数异常: {scale}"
        out_path = os.path.join(OUT_DIR, fname)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(drawable_xml(d, fill))
        print(f"{fname}: scale={scale:.3f}")

if __name__ == "__main__":
    main()
```

- [ ] **Step 3: 运行脚本并验证输出**

Run: `python tools/icon/build_vectors.py`
Expected:
```
ic_launcher_foreground.xml: scale=0.807
ic_launcher_foreground_m.xml: scale=0.807
ic_launcher_foreground_zhuwen.xml: scale=0.637
```
（白文 ≈313/388、朱文 ≈313/491，第三位小数内浮动可接受）

- [ ] **Step 4: 抽查 XML 结构**

Run: `head -c 400 tools/icon/out/ic_launcher_foreground_zhuwen.xml`
Expected: `<vector ... android:viewportWidth="512"` 开头，单 `<path>` 含 `android:fillType="evenOdd"` 与 `android:fillColor="#FF000000"`，无 `<group>`。

- [ ] **Step 5: Commit（暂缓，待用户显式说"提交"）**

```bash
git add tools/icon
git commit -m "chore(icon): 收编墨本圆章生成链，SVG 字画转 vector drawable"
```

---

### Task 2: 主图标接线（白文三层）

**Files:**
- Modify: `app/src/main/res/values/colors.xml`（`ic_launcher_background` → `#000000`）
- Overwrite: `app/src/main/res/drawable/ic_launcher_foreground.xml`、`app/src/main/res/drawable/ic_launcher_foreground_m.xml`（自 `tools/icon/out/` 复制）
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`（补 monochrome 行，与 ic_launcher.xml 对齐）

**Interfaces:**
- Consumes: Task 1 的 `tools/icon/out/ic_launcher_foreground.xml`、`ic_launcher_foreground_m.xml`。
- Produces: 主图标（`@mipmap/ic_launcher` 与 `@mipmap/ic_launcher_round`）= 黑底白文圆章；monochrome 供 Android 13+ 主题图标。

- [ ] **Step 1: 复制两份 foreground**

```bash
cp tools/icon/out/ic_launcher_foreground.xml app/src/main/res/drawable/ic_launcher_foreground.xml
cp tools/icon/out/ic_launcher_foreground_m.xml app/src/main/res/drawable/ic_launcher_foreground_m.xml
```

- [ ] **Step 2: 改主图标背景色为纯黑**

`values/colors.xml` 中：
```xml
    <color name="ic_launcher_background">#2E2D3D</color>
```
改为：
```xml
    <color name="ic_launcher_background">#000000</color>
```

- [ ] **Step 3: ic_launcher_round.xml 补 monochrome**

在 `</adaptive-icon>` 前加入：
```xml
    <monochrome android:drawable="@drawable/ic_launcher_foreground_m" />
```

- [ ] **Step 4: 资源编译验证**

Run: `.\gradlew.bat :app:processAppDebugResources`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit（暂缓，同 Task 1 约定）**

```bash
git add app/src/main/res/values/colors.xml app/src/main/res/drawable/ic_launcher_foreground.xml app/src/main/res/drawable/ic_launcher_foreground_m.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
git commit -m "feat(eink): 主图标换墨本白文圆章，背景纯黑，补 monochrome"
```

---

### Task 3: 朱文别名接线（LauncherW 备选图标）

**Files:**
- Overwrite: `app/src/main/res/drawable/ic_launcher_foreground_zhuwen.xml`（自 `tools/icon/out/` 复制，新文件）
- Modify: `app/src/main/res/values/colors.xml`（`ic_launcher_background_w` → `#FFFFFF`）
- Modify: `app/src/main/res/mipmap-anydpi-v26/launcherw.xml`（foreground/monochrome 改指朱文）

**Interfaces:**
- Consumes: Task 1 的 `tools/icon/out/ic_launcher_foreground_zhuwen.xml`。
- Produces: 应用内换图标选「W」（`LauncherIconHelp.changeIcon("W")`）后桌面图标为朱文圆章。

- [ ] **Step 1: 复制朱文 foreground**

```bash
cp tools/icon/out/ic_launcher_foreground_zhuwen.xml app/src/main/res/drawable/ic_launcher_foreground_zhuwen.xml
```

- [ ] **Step 2: 改别名背景色为纯白**

`values/colors.xml` 中：
```xml
    <color name="ic_launcher_background_w">#F2F1F6</color>
```
改为：
```xml
    <color name="ic_launcher_background_w">#FFFFFF</color>
```

- [ ] **Step 3: launcherw.xml 改指朱文资源**

整个文件替换为：
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background_w" />
    <foreground android:drawable="@drawable/ic_launcher_foreground_zhuwen" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground_zhuwen" />
</adaptive-icon>
```

- [ ] **Step 4: 资源编译验证**

Run: `.\gradlew.bat :app:processAppDebugResources`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit（暂缓）**

```bash
git add app/src/main/res/values/colors.xml app/src/main/res/drawable/ic_launcher_foreground_zhuwen.xml app/src/main/res/mipmap-anydpi-v26/launcherw.xml
git commit -m "feat(eink): 朱文圆章接入 launcherw 备选图标"
```

---

### Task 4: 全量验证与文档收尾

**Files:**
- Modify: `docs/dev/app-icon-eink-seal.md`（状态行、验证清单勾选）

**Interfaces:**
- Consumes: Task 1-3 全部落地。

- [ ] **Step 1: 打整包验证**

Run: `.\gradlew.bat :app:assembleAppDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 文本检查**

Run: `git diff --check`
Expected: 无输出（无空白错误）。

- [ ] **Step 3: 更新设计文档状态**

`docs/dev/app-icon-eink-seal.md` 顶部状态行改为：
```
状态：已接入构建（2026-09-15），真机项待验证。
```
验证清单勾选 `- [x]`：`:app:assembleAppDebug` 资源编译通过；其余真机项保持未勾。

- [ ] **Step 4: （可选，需真机连接）安装体验**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: 桌面图标为黑底白字圆章；应用内换图标选「W」后桌面为朱文圆章；Android 13+ 长按桌面看主题图标着色。

- [ ] **Step 5: Commit（暂缓）**

```bash
git add docs/dev/app-icon-eink-seal.md
git commit -m "docs(dev): 墨本圆章接入状态与验证清单更新"
```
