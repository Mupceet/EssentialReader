# -*- coding: utf-8 -*-
"""把墨本圆章 SVG 转成 Android vector drawable。

输入: tools/icon/svg/muben-seal-{filled,line}.svg（512 视窗，纯 M/L/Z 折线）
      - filled: path[0]=黑色章体圆盘, path[1]=白色「墨」字画
      - line:   path[0]=黑色圆环+字画
输出: tools/icon/out/ 三份 drawable。章体几何由 SVG 权威给出（章面已收进 66dp
      安全区、留呼吸边），这里只做整体居中，并断言字画最远顶点不超出 72dp
      典型可见圆（512 视窗半径 170.5px），防桌面蒙版裁切。
用法: python tools/icon/build_vectors.py
"""
import os
import re
import xml.etree.ElementTree as ET

BASE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(BASE, "out")
VISIBLE_RADIUS = 170.5  # 72/108 * 256
PAIR = re.compile(r"(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)")
SVG_NS = "{http://www.w3.org/2000/svg}"

def load_paths(svg_name):
    """返回 [(d, fill), ...]，保持 SVG 内顺序。"""
    root = ET.parse(os.path.join(BASE, "svg", svg_name)).getroot()
    paths = [(p.get("d"), p.get("fill")) for p in root.iter(f"{SVG_NS}path")]
    assert paths, f"{svg_name} 中没有 <path>"
    return paths

def center_paths(paths):
    """按全部 path 的联合包围盒居中（平移，不缩放）；断言顶点都在可见圆内。"""
    pts = [(float(x), float(y))
           for d, _ in paths for x, y in PAIR.findall(d)]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    tx, ty = 256 - (min(xs) + max(xs)) / 2, 256 - (min(ys) + max(ys)) / 2
    r_max = max(((x + tx - 256) ** 2 + (y + ty - 256) ** 2) ** 0.5 for x, y in pts)
    assert r_max <= VISIBLE_RADIUS + 0.5, f"字画超出可见圆: r={r_max:.1f}"

    def shift(d):
        return PAIR.sub(
            lambda m: f"{float(m.group(1)) + tx:.1f},{float(m.group(2)) + ty:.1f}", d)

    return [(shift(d), fill) for d, fill in paths]

def drawable_xml(paths):
    elems = "\n".join(
        f'    <path\n        android:fillColor="{fill}"\n'
        f'        android:fillType="evenOdd"\n'
        f'        android:pathData="{d}" />\n'
        for d, fill in paths)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="512"\n'
        '    android:viewportHeight="512">\n'
        f'{elems}'
        '</vector>\n'
    )

# (输出文件名, 源 SVG, 选用 path 下标, fill 覆盖；None=沿用 SVG 内 fill)
JOBS = [
    ("ic_launcher_foreground.xml", "muben-seal-filled.svg", [0, 1], None),
    # monochrome 只取字画，系统按主题着色，强制不透明白
    ("ic_launcher_foreground_m.xml", "muben-seal-filled.svg", [1], "#FFFFFFFF"),
    ("ic_launcher_foreground_zhuwen.xml", "muben-seal-line.svg", [0], None),
]

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for fname, svg, idx, fill_override in JOBS:
        paths = load_paths(svg)
        picked = [(d, fill_override or fill) for i, (d, fill) in enumerate(paths)
                  if i in idx]
        picked = center_paths(picked)
        out_path = os.path.join(OUT_DIR, fname)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(drawable_xml(picked))
        print(f"{fname}: paths={len(picked)}")

if __name__ == "__main__":
    main()
