# -*- coding: utf-8 -*-
"""墨本阅读 APK 图标候选稿：3 方向（印章/线装书/墨滴）× 填充/线条 双版本。

纯黑白。SVG 为矢量源（最终接 Android adaptive icon 时再转 vector drawable），
PNG 预览由同一份 SVG 渲染，保证所见即所得。
"""
import io
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont
from reportlab.graphics import renderPM
from svglib.svglib import svg2rlg

BASE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR = os.path.join(BASE, "svg")
PNG_DIR = os.path.join(BASE, "png")
os.makedirs(SVG_DIR, exist_ok=True)
os.makedirs(PNG_DIR, exist_ok=True)

BLACK = "#000000"
WHITE = "#FFFFFF"
S = 512  # 画布

def svg_doc(body, bg=WHITE):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{S}" height="{S}" '
            f'viewBox="0 0 {S} {S}">'
            f'<rect width="{S}" height="{S}" fill="{bg}"/>{body}</svg>')

# ---------------- ① 印章·墨本：华文新魏打底 + 印化处理 + 矢量轮廓跟踪 ----------------
# 圆章。填充版=白文（黑底白字），线条版=朱文（白底黑字+圆环边栏）。
# 印化工序：加粗匀笔（高斯阈值膨胀）→ 残破（只破不立：单向边界噪声 + 字画内啄点）→ 描回矢量。
import numpy as np

SEAL_FONT = "C:/Windows/Fonts/STXINWEI.TTF"
SEAL_RENDER = 2048  # 印化工序分辨率，描回矢量后缩到 512
SEAL_LEVELS = {"无": 0, "轻": 1, "中": 2, "重": 3}
SEAL_LEVEL = 2  # 已定档：单字墨配残破·中（2026-09-15 拍板；墨本双字版曾用轻档，可用 chars 参数回跑）

def _blur(arr, r):
    img = Image.fromarray(arr)
    return np.asarray(img.filter(ImageFilter.GaussianBlur(r)))

# 章面（印体）几何，512 视窗口径；2048 工序时 ×4
PLATE_R = 150   # 章体外半径：收进 66dp 安全区并留呼吸边，圆形/圆角矩形蒙版下都完整可见
RING_W = 15     # 朱文圆环宽

def seal_marks(zhuwen, level=SEAL_LEVEL, chars=None):
    """生成字画掩码（不含白文章底盘——底盘由 seal_svg 以矢量圆绘制）。
    chars=None 默认单字「墨」居中；墨本双字版传 (("墨", 0.28), ("本", 0.72))。"""
    chars = chars or (("墨", 0.5),)
    n = SEAL_RENDER
    img = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(img)
    # 字面随章体缩：占章面内径约 2/3；朱文有圆环再收一档（inner 为 512 口径）
    inner = (PLATE_R - RING_W) * 2 if zhuwen else PLATE_R * 2
    ch_size = 0.67 * inner / (S * 1.09) * (1 if len(chars) == 1 else 0.62)
    f = ImageFont.truetype(SEAL_FONT, int(n * ch_size))
    if zhuwen:  # 圆环边栏
        inset = int(n / 2 - PLATE_R * 4)
        sw = int(RING_W * 4)
        d.ellipse([inset, inset, n - inset, n - inset], outline=255, width=sw)
    for ch, cy in chars:
        bb = d.textbbox((0, 0), ch, font=f)
        d.text((n / 2 - (bb[0] + bb[2]) / 2, n * cy - (bb[1] + bb[3]) / 2),
               ch, font=f, fill=255)
    # 印化一：加粗匀笔——高斯后取低阈值，细画涨粗向粗画看齐（近似并笔）
    r = n * 0.0052
    marks = _blur(np.asarray(img), r) > 100
    if level > 0:
        marks = distress(marks, seed=11, level=level)
    return marks

def distress(marks, seed, level):
    """印化二：残破，只破不立。字画只被啃小：单向边界噪声 + 字画内啄点，绝无反向凸起。"""
    rng = np.random.default_rng(seed)
    n = marks.shape[0]
    # 1) 边界噪声：与运算保证只蚀不涨
    nw = (0.06, 0.10, 0.15)[level - 1]
    base = _blur(marks.astype(np.uint8) * 255, 4).astype(np.float32) / 255
    noise = _blur((rng.random((n, n)) * 255).astype(np.uint8), 8).astype(np.float32) / 255
    marks = marks & ((base * (1 - nw) + noise * nw) > 0.5)
    # 2) 啄点：只在字画内（含边界带内侧）挖缺
    k = 15
    m8 = marks.astype(np.uint8) * 255
    big = np.asarray(Image.fromarray(m8).filter(ImageFilter.MaxFilter(k))) > 128
    small = np.asarray(Image.fromarray(m8).filter(ImageFilter.MinFilter(k))) > 128
    band = (big ^ small) & marks
    ys, xs = np.nonzero(band)
    order = rng.permutation(len(ys))[:(12, 24, 40)[level - 1]]
    rhi = (n // 140, n // 110, n // 80)[level - 1]
    yy, xx = np.ogrid[:n, :n]
    for i in order:
        y, x = int(ys[i]), int(xs[i])
        r = int(rng.integers(n // 220, rhi))
        marks &= ~((yy - y) ** 2 + (xx - x) ** 2 <= r * r)
    return marks

def _rdp(pts, eps):
    if len(pts) < 3:
        return pts
    (x0, y0), (x1, y1) = pts[0], pts[-1]
    dx, dy = x1 - x0, y1 - y0
    den = (dx * dx + dy * dy) ** 0.5 or 1.0
    dists = [abs(dy * x - dx * y + x1 * y0 - y1 * x0) / den for x, y in pts[1:-1]]
    imax = int(np.argmax(dists))
    if dists[imax] > eps:
        a = _rdp(pts[:imax + 2], eps)
        return a[:-1] + _rdp(pts[imax + 1:], eps)
    return [pts[0], pts[-1]]

def _rdp_closed(pts, eps):
    """闭合环（首尾同点）的 RDP：先按距起点最远处切成两段，避免零长弦塌缩。"""
    x0, y0 = pts[0]
    d2 = [(x - x0) ** 2 + (y - y0) ** 2 for x, y in pts]
    k = int(np.argmax(d2))
    return _rdp(pts[:k + 1], eps)[:-1] + _rdp(pts[k:], eps)

def trace_path(marks):
    """二值掩码 → SVG path d（像素边界行走 + RDP 简化，evenodd 填充）。"""
    n = marks.shape[0]
    edges = {}
    ys, xs = np.nonzero(marks)
    for y, x in zip(ys.tolist(), xs.tolist()):
        if y == 0 or not marks[y - 1, x]: edges.setdefault((x, y), []).append((x + 1, y))
        if x == n - 1 or not marks[y, x + 1]: edges.setdefault((x + 1, y), []).append((x + 1, y + 1))
        if y == n - 1 or not marks[y + 1, x]: edges.setdefault((x + 1, y + 1), []).append((x, y + 1))
        if x == 0 or not marks[y, x - 1]: edges.setdefault((x, y + 1), []).append((x, y))
    loops = []
    while edges:
        start = next(iter(edges))
        loop = [start]
        cur, prev = start, None
        while True:
            outs = edges.get(cur)
            if not outs:
                break
            if len(outs) == 1:
                nxt = outs.pop()
            else:  # 角点歧义：相对来向顺时针优先，保证绕行不断链
                nxt = max(outs, key=lambda q: (q[0] - cur[0]) * prev[1] - (q[1] - cur[1]) * prev[0]
                          if prev else 0)
                outs.remove(nxt)
            if not outs:
                del edges[cur]
            prev = (nxt[0] - cur[0], nxt[1] - cur[1])
            cur = nxt
            if cur == start:
                break
            loop.append(cur)
        if len(loop) > 8:
            simplified = _rdp_closed(loop + [loop[0]], 3.0)
            if len(simplified) > 2:
                loops.append(simplified)
    k = S / n
    parts = []
    for lp in loops:
        d = "M" + "L".join(f"{x * k:.1f},{y * k:.1f}" for x, y in lp) + "Z"
        parts.append(d)
    return "".join(parts)

def seal_svg(zhuwen, level=SEAL_LEVEL, chars=None):
    d = trace_path(seal_marks(zhuwen, level, chars))
    if zhuwen:  # 朱文：白底，黑圆环 + 黑字
        return svg_doc(f'<path d="{d}" fill="{BLACK}" fill-rule="evenodd"/>', WHITE)
    # 白文：白底 + 黑色章体圆盘（矢量随前景走，背景层不再承担定形）+ 白字
    # 圆盘用折线多边形（与描回路径同构，坐标正则才能正确处理）
    import math as _math
    r = PLATE_R
    seg = 72
    pts = [f"{256 + r * _math.sin(2 * _math.pi * i / seg):.1f},"
           f"{256 - r * _math.cos(2 * _math.pi * i / seg):.1f}" for i in range(seg)]
    plate = "M" + "L".join(pts) + "Z"
    body = (f'<path d="{plate}" fill="{BLACK}"/>'
            f'<path d="{d}" fill="{WHITE}" fill-rule="evenodd"/>')
    return svg_doc(body)

# ② 线装书：封面 + 题签（竖排题字）+ 左侧四针脚
BOOK_X, BOOK_Y, BOOK_W, BOOK_H, BOOK_R = 162, 116, 188, 280, 26
LABEL_X, LABEL_Y, LABEL_W, LABEL_H = 200, 142, 52, 116
STITCH_YS = (158, 224, 290, 356)

def book_cover(extra=""):
    return (f'<rect x="{BOOK_X}" y="{BOOK_Y}" width="{BOOK_W}" height="{BOOK_H}" '
            f'rx="{BOOK_R}" {extra}/>')

def book_filled():
    body = book_cover(f'fill="{BLACK}"')
    body += (f'<rect x="{LABEL_X}" y="{LABEL_Y}" width="{LABEL_W}" height="{LABEL_H}" '
             f'rx="10" fill="{WHITE}"/>')
    # 题签内竖排两行"题字"，右行长左行短（竖排从右读起）
    body += (f'<rect x="228" y="156" width="8" height="86" rx="4" fill="{BLACK}"/>'
             f'<rect x="210" y="156" width="8" height="58" rx="4" fill="{BLACK}"/>')
    for y in STITCH_YS:  # 白线脚穿过封面左缘
        body += f'<rect x="150" y="{y - 6}" width="36" height="12" rx="6" fill="{WHITE}"/>'
    return svg_doc(body)

def book_line():
    body = book_cover(f'fill="none" stroke="{BLACK}" stroke-width="16"')
    body += (f'<rect x="{LABEL_X}" y="{LABEL_Y}" width="{LABEL_W}" height="{LABEL_H}" '
             f'rx="10" fill="none" stroke="{BLACK}" stroke-width="10"/>')
    body += (f'<rect x="228" y="156" width="8" height="86" rx="4" fill="{BLACK}"/>'
             f'<rect x="210" y="156" width="8" height="58" rx="4" fill="{BLACK}"/>')
    for y in STITCH_YS:  # 针脚穿透封边
        body += (f'<line x1="146" y1="{y}" x2="194" y2="{y}" stroke="{BLACK}" '
                 f'stroke-width="12" stroke-linecap="round"/>')
    return svg_doc(body)

# ③ 墨滴：滴水锥形 + 滴内三行"书页文字"（末行短，段落感）
DROP_TIP = (256, 118)
DROP_C = (256, 296)
DROP_R = 102

def drop_path():
    tx, ty = DROP_TIP
    cx, cy = DROP_C
    d = cy - ty
    beta = math.acos(DROP_R / d)  # 切点相对轴线的张角
    dx, dy = DROP_R * math.sin(beta), DROP_R * math.cos(beta)
    p1 = (cx + dx, cy - dy)  # 右切点
    p2 = (cx - dx, cy - dy)  # 左切点
    # 顶角→右切点→绕底部大弧→左切点→合拢
    return (f'M{tx},{ty} L{p1[0]:.1f},{p1[1]:.1f} '
            f'A{DROP_R},{DROP_R} 0 1 1 {p2[0]:.1f},{p2[1]:.1f} Z')

DROP_LINES = ((202, 258, 108, 14), (202, 290, 108, 14), (202, 322, 64, 14))

def drop_bars(color):
    return "".join(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h / 2}" '
                   f'fill="{color}"/>' for x, y, w, h in DROP_LINES)

def drop_filled():
    return svg_doc(f'<path d="{drop_path()}" fill="{BLACK}"/>' + drop_bars(WHITE))

def drop_line():
    return svg_doc(f'<path d="{drop_path()}" fill="none" stroke="{BLACK}" '
                   f'stroke-width="16" stroke-linejoin="round"/>' + drop_bars(BLACK))

# ---------------- 渲染 ----------------

ICONS = {
    "seal-filled": ("印章·墨本（白文·填充）", seal_svg(zhuwen=False)),
    "seal-line": ("印章·墨本（朱文·线条）", seal_svg(zhuwen=True)),
    "book-filled": ("线装书（填充）", book_filled()),
    "book-line": ("线装书（线条）", book_line()),
    "drop-filled": ("墨滴（填充）", drop_filled()),
    "drop-line": ("墨滴（线条）", drop_line()),
}

def svg_to_png(svg_text, size):
    drawing = svg2rlg(io.StringIO(svg_text))
    k = size * 4 / S  # 4x 超采样后缩小，边缘平滑
    drawing.scale(k, k)
    drawing.width, drawing.height = drawing.width * k, drawing.height * k
    img = renderPM.drawToPIL(drawing)
    return img.resize((size, size), Image.LANCZOS).convert("RGB")

def circle_mask(img):
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size, size), fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out

def main():
    pngs = {}
    for name, (label, svg) in ICONS.items():
        svg_path = os.path.join(SVG_DIR, f"muben-{name}.svg")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write(svg)
        pngs[name] = (label, svg_to_png(svg, S))

    # 预览拼图：三行方向 × 两列（填充|线条），每格圆形蒙版模拟桌面 + 底部小尺寸一行
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 30)
    cell, pad, label_h = 300, 36, 52
    cols, rows = 2, 3
    # 画板宽度需容纳底部小尺寸行：起点(pad+cell)起两组 96/64/48
    small_set = (96 + 64 + 48) + 30  # 一组三个尺寸 + 间距
    W = max(cols * cell + (cols + 1) * pad, pad + cell + 2 * small_set + pad)
    grid_bottom = pad + rows * (cell + label_h + pad)
    H = grid_bottom + 56 + 40 + 3 * 96 + pad
    board = Image.new("RGB", (W, H), "#F4F4F4")
    draw = ImageDraw.Draw(board)
    col_labels = ["填充版", "线条版"]
    for i, t in enumerate(col_labels):
        x = pad + i * (cell + pad) + cell // 2
        draw.text((x, pad // 2 - 6), t, font=font, fill="#333", anchor="mm")
    names = [("seal-filled", "seal-line"), ("book-filled", "book-line"),
             ("drop-filled", "drop-line")]
    row_names = ["① 印章·墨本", "② 线装书", "③ 墨滴"]
    for r, pair in enumerate(names):
        y0 = pad + r * (cell + label_h + pad)
        for c, name in enumerate(pair):
            label, img = pngs[name]
            x0 = pad + c * (cell + pad)
            masked = circle_mask(img.resize((cell, cell), Image.LANCZOS))
            board.paste(masked, (x0, y0), masked)
            draw.rectangle((x0, y0, x0 + cell - 1, y0 + cell - 1),
                           outline="#CCCCCC", width=1)
            if c == 0:
                draw.text((4, y0 + cell + label_h // 2), row_names[r],
                          font=font, fill="#333", anchor="lm")
    # 小尺寸可读性：底部放 96/64/48，两行各三个方向
    small_sizes = (96, 64, 48)
    y_small = grid_bottom + 56 + 40
    draw.text((pad, y_small - 40), "小尺寸可读性 96 / 64 / 48 px：",
              font=ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 22), fill="#555")
    x0 = pad + 300
    for row in range(3):
        x = x0
        for name in [names[row][0], names[row][1]]:
            _, img = pngs[name]
            for s in small_sizes:
                board.paste(img.resize((s, s), Image.LANCZOS),
                            (x, y_small + row * 96 + (96 - s) // 2))
                x += s + 10
            x += 30

    out = os.path.join(BASE, "preview-collage.png")
    board.save(out)
    print("collage ->", out)
    for name in pngs:
        p = os.path.join(PNG_DIR, f"muben-{name}.png")
        pngs[name][1].save(p)
        print("icon   ->", p)

    # 残破档位对比条：白文/朱文 × 无/轻/中/重
    cell2, pad2 = 300, 30
    W2 = 4 * cell2 + 5 * pad2
    H2 = 2 * (cell2 + 46) + 3 * pad2
    strip = Image.new("RGB", (W2, H2), "#F4F4F4")
    ds = ImageDraw.Draw(strip)
    fl = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 26)
    for li, lname in enumerate(SEAL_LEVELS):
        ds.text((pad2 + li * (cell2 + pad2) + cell2 // 2, pad2 + 12),
                f"残破·{lname}", font=fl, fill="#333", anchor="mm")
    for ri, (rname, zw) in enumerate([("白文·填充", False), ("朱文·线条", True)]):
        y0 = 2 * pad2 + ri * (cell2 + 46)
        ds.text((6, y0 + cell2 // 2), rname, font=fl, fill="#333", anchor="lm")
        for li, lname in enumerate(SEAL_LEVELS):
            img_l = svg_to_png(seal_svg(zw, SEAL_LEVELS[lname]), cell2)
            strip.paste(img_l, (pad2 + li * (cell2 + pad2), y0))
    p2 = os.path.join(BASE, "seal-compare.png")
    strip.save(p2)
    print("compare ->", p2)

if __name__ == "__main__":
    main()
