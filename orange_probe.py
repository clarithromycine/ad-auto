"""橙色按钮探测标定工具（与 AdDetector.computeOrangeBar 完全一致的算法）。

用法:
    python orange_probe.py <screenshot.png> [step]

判据三层：
  1. 形状 A（横贯宽条）/ B（右下角小按钮）
  2. **颜色纯度**：块内像素是否高度一致（视频橙色是几百种色值，UI 按钮是同一色值）
  3. **近似矩形**：块内每行宽度 / 每列高度都接近外接矩形（行/列跨度一致性）
"""
import sys
from collections import deque

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow: pip install pillow")
    sys.exit(1)

# —— 与 AdDetector.kt 保持一致的阈值 ——
FILL_MIN = 0.65
MIN_WIDTH_FRAC = 0.25
MIN_HEIGHT_PX = 20
MAX_HEIGHT_FRAC = 0.30
MIN_ASPECT = 2.5
MIN_CENTER_Y_FRAC = 0.30

CTA_FILL_MIN = 0.75
CTA_MIN_WIDTH_FRAC = 0.05
CTA_MAX_WIDTH_FRAC = 0.20
CTA_MIN_HEIGHT_FRAC = 0.03
CTA_MAX_HEIGHT_FRAC = 0.15
CTA_MIN_CENTER_X_FRAC = 0.75
CTA_MIN_CENTER_Y_FRAC = 0.80

MIN_CELLS = 4
COLOR_TOL = 12
UNIFORMITY_MIN = 0.85
# 近似矩形：行/列跨度达到外接矩形 80% 的比例下限
RECT_SPAN_FRAC = 0.80
RECT_ROW_COVER_MIN = 0.70
RECT_COL_COVER_MIN = 0.70


def is_orange(r, g, b):
    return r >= 140 and 40 <= g <= 200 and b <= 110 and (r - b) >= 70 and r >= g >= b


def probe(path, step=4):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    gw, gh = (w + step - 1) // step, (h + step - 1) // step

    colors = {}
    orange = 0
    for y in range(h):
        for x in range(w):
            if is_orange(*px[x, y]):
                orange += 1
    ratio = orange * 100.0 / (w * h)

    for gy in range(gh):
        y = gy * step
        if y >= h:
            break
        for gx in range(gw):
            x = gx * step
            if x >= w:
                break
            c = px[x, y]
            if is_orange(*c):
                colors[(gx, gy)] = c

    print(f"截图: {path}  尺寸 {w}x{h}  step={step}  网格 {gw}x{gh}")
    print(f"橙色像素占比(逐像素): {ratio:.2f}%   采样橙色格 {len(colors)}")

    seen = set()
    blobs = []
    for key in list(colors.keys()):
        if key in seen:
            continue
        dq = deque([key])
        seen.add(key)
        cells = []
        while dq:
            cx, cy = dq.popleft()
            cells.append((cx, cy))
            for nb in ((cx - 1, cy), (cx + 1, cy), (cx, cy - 1), (cx, cy + 1)):
                if nb in colors and nb not in seen:
                    seen.add(nb)
                    dq.append(nb)
        if len(cells) < MIN_CELLS:
            continue

        xs = [c[0] for c in cells]
        ys = [c[1] for c in cells]
        x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
        bw = (x1 - x0 + 1) * step
        bh = (y1 - y0 + 1) * step
        fill = len(cells) / float((x1 - x0 + 1) * (y1 - y0 + 1))
        aspect = bw / max(bh, 1)
        cxf = (x0 + x1) / 2.0 * step / w
        cyf = (y0 + y1) / 2.0 * step / h

        n = len(cells)
        mr = sum(colors[c][0] for c in cells) / n
        mg = sum(colors[c][1] for c in cells) / n
        mb = sum(colors[c][2] for c in cells) / n
        near = sum(1 for c in cells
                   if abs(colors[c][0] - mr) <= COLOR_TOL
                   and abs(colors[c][1] - mg) <= COLOR_TOL
                   and abs(colors[c][2] - mb) <= COLOR_TOL)
        uniformity = near / float(n)

        # 近似矩形：每行的 x 跨度 / 每列的 y 跨度
        row_lo = {}
        row_hi = {}
        col_lo = {}
        col_hi = {}
        for (cx, cy) in cells:
            row_lo[cy] = min(row_lo.get(cy, 10 ** 9), cx)
            row_hi[cy] = max(row_hi.get(cy, -1), cx)
            col_lo[cx] = min(col_lo.get(cx, 10 ** 9), cy)
            col_hi[cx] = max(col_hi.get(cx, -1), cy)
        wsel = RECT_SPAN_FRAC * (x1 - x0 + 1)
        hsel = RECT_SPAN_FRAC * (y1 - y0 + 1)
        rows_ok = sum(1 for cy in row_lo if (row_hi[cy] - row_lo[cy] + 1) >= wsel)
        cols_ok = sum(1 for cx in col_lo if (col_hi[cx] - col_lo[cx] + 1) >= hsel)
        row_cover = rows_ok / float(len(row_lo))
        col_cover = cols_ok / float(len(col_lo))

        is_bar = (fill >= FILL_MIN and bw >= w * MIN_WIDTH_FRAC and bh >= MIN_HEIGHT_PX
                  and bh <= h * MAX_HEIGHT_FRAC and aspect >= MIN_ASPECT
                  and cyf >= MIN_CENTER_Y_FRAC)
        is_cta = (fill >= CTA_FILL_MIN
                  and w * CTA_MIN_WIDTH_FRAC <= bw <= w * CTA_MAX_WIDTH_FRAC
                  and h * CTA_MIN_HEIGHT_FRAC <= bh <= h * CTA_MAX_HEIGHT_FRAC
                  and cxf >= CTA_MIN_CENTER_X_FRAC and cyf >= CTA_MIN_CENTER_Y_FRAC)
        shape = "A" if is_bar else ("B" if is_cta else "")
        rect = row_cover >= RECT_ROW_COVER_MIN and col_cover >= RECT_COL_COVER_MIN
        hit = bool(shape) and uniformity >= UNIFORMITY_MIN and rect
        blobs.append(dict(size=n, bw=bw, bh=bh, fill=fill, aspect=aspect, cxf=cxf, cyf=cyf,
                          x0=x0 * step, y0=y0 * step, uni=uniformity, rowc=row_cover,
                          colc=col_cover, shape=shape, rect=rect, hit=hit,
                          mean=(int(mr), int(mg), int(mb))))

    blobs.sort(key=lambda b: -b["size"])
    print(f"\n连通块 {len(blobs)} 个，按大小前 8:")
    for i, b in enumerate(blobs[:8], 1):
        tag = []
        if b["shape"]:
            tag.append(f"形态{b['shape']}")
            tag.append("纯度✓" if b["uni"] >= UNIFORMITY_MIN else "纯度✗")
            tag.append("矩形✓" if b["rect"] else "矩形✗")
        print(f"  #{i} {b['bw']:>4}x{b['bh']:<4} @({b['x0']},{b['y0']}) 填充 {b['fill']:.2f} "
              f"长宽比 {b['aspect']:.1f} 中心({b['cxf']:.2f},{b['cyf']:.2f}) "
              f"纯度 {b['uni']:.2f} 行跨 {b['rowc']:.2f} 列跨 {b['colc']:.2f} "
              f"色#{b['mean'][0]:02X}{b['mean'][1]:02X}{b['mean'][2]:02X} "
              f"{'/'.join(tag) if tag else '×'}")

    hits = [b for b in blobs if b["hit"]]
    print(f"\n判定: {'是广告橙色按钮' if hits else '不是橙色按钮'}"
          + (f"（{hits[0]['bw']}x{hits[0]['bh']} 纯度 {hits[0]['uni']:.2f} "
             f"行跨 {hits[0]['rowc']:.2f} 列跨 {hits[0]['colc']:.2f}）" if hits else ""))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    probe(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 4)
