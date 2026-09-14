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
