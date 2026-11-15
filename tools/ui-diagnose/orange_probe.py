#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""橙色按钮探测标定工具（`OrangeButton.detect` 的 Python 镜像）。

用法:
    python3 orange_probe.py <screenshot.png> [step]
    python3 orange_probe.py --check          # 跑语料回归（等价 fixture_check.py）

⚠️ 这份实现必须与
   app/src/main/java/com/wisight/adauto/core/vision/OrangeButton.kt
**逐条对齐**。2026-11-15 之前它对不齐，导致两个问题：
  1. 阈值漂移：填充率下限 0.65（Kotlin 0.80）、行/列跨度覆盖 0.70/0.70（Kotlin 0.90/0.88）；
  2. 语义漂移：它按**单像素**采样、并且**把颜色纯度当作判定条件**；而 Kotlin 是
     step×step **盒平均**后判定，且颜色纯度只作排查（两条截图路径色值不可比）。
   结果是同一条按钮在两边可能给出相反结论。

判据（与 Kotlin 一致）：
  1. 形状 A（横贯宽条）/ B（右下角小按钮）
  2. 近似矩形：块内每行宽度 / 每列高度都要接近外接矩形
  3. 颜色纯度只打印、不参与判定

只依赖 Pillow（读 PNG）。
"""
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    print("需要 Pillow: pip install pillow")
    sys.exit(1)

# —— 与 OrangeButton.kt 保持一致的阈值 ——
ORANGE_SAMPLE_STEP = 4

FILL_MIN = 0.80
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
COLOR_TOL = 12            # 仅排查用
UNIFORMITY_MIN = 0.85     # 仅排查用（**不是**判定条件）
RECT_SPAN_FRAC = 0.80
RECT_ROW_COVER_MIN = 0.90
RECT_COL_COVER_MIN = 0.88


def is_orange(r, g, b):
    return r >= 140 and 40 <= g <= 200 and b <= 110 and (r - b) >= 70 and r >= g >= b


def _build_grid(data, w, h, step):
    """step×step 盒平均 → 采样网格。返回 (grid, gw, gh, orange_cells)。"""
    gw = (w + step - 1) // step
    gh = (h + step - 1) // step
    grid = [0] * (gw * gh)
    orange_cells = 0
    acc_r = [0] * gw
    acc_g = [0] * gw
    acc_b = [0] * gw
    acc_n = [0] * gw
    gy = 0
    for y in range(h):
        row_off = y * w * 3
        for gx in range(gw):
            xs = gx * step
            xe = xs + step if xs + step < w else w
            sr = sg = sb = 0
            o = row_off + xs * 3
            for _ in range(xs, xe):
                sr += data[o]
                sg += data[o + 1]
                sb += data[o + 2]
                o += 3
            acc_r[gx] += sr
            acc_g[gx] += sg
            acc_b[gx] += sb
            acc_n[gx] += xe - xs
        if (y + 1) % step == 0 or y == h - 1:
            base = gy * gw
            for gx in range(gw):
                n = acc_n[gx]
                if n > 0:
                    r, g, b = acc_r[gx] // n, acc_g[gx] // n, acc_b[gx] // n
                    if is_orange(r, g, b):
                        grid[base + gx] = (r << 16) | (g << 8) | b
                        orange_cells += 1
                acc_r[gx] = 0
                acc_g[gx] = 0
                acc_b[gx] = 0
                acc_n[gx] = 0
            gy += 1
    return grid, gw, gh, orange_cells


def _blocks(grid, gw, gh, w, h, step):
    """逐块判定，返回块字典列表（顺序 = 扫描顺序）。"""
    out = []
    visited = [False] * len(grid)
    for start in range(len(grid)):
        if grid[start] == 0 or visited[start]:
            continue
        stack = [start]
        visited[start] = True
        cells = []
        while stack:
            idx = stack.pop()
            cells.append(idx)
            cx, cy = idx % gw, idx // gw
            for nb, ok in ((idx - 1, cx > 0), (idx + 1, cx < gw - 1),
                           (idx - gw, cy > 0), (idx + gw, cy < gh - 1)):
                if ok and grid[nb] != 0 and not visited[nb]:
                    visited[nb] = True
                    stack.append(nb)
        size = len(cells)
        if size < MIN_CELLS:
            continue

        xs = [c % gw for c in cells]
        ys = [c // gw for c in cells]
        x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
        bw = (x1 - x0 + 1) * step
        bh = (y1 - y0 + 1) * step
        cell_w = x1 - x0 + 1
        cell_h = y1 - y0 + 1
        fill = size / float(cell_w * cell_h)
        aspect = bw / max(bh, 1)
        cxf = ((x0 + x1) / 2.0 * step) / w
        cyf = ((y0 + y1) / 2.0 * step) / h

        cols = [grid[c] for c in cells]
        mr = sum((c >> 16) & 0xFF for c in cols) / float(size)
        mg = sum((c >> 8) & 0xFF for c in cols) / float(size)
        mb = sum(c & 0xFF for c in cols) / float(size)
        near = sum(1 for c in cols
                   if abs(((c >> 16) & 0xFF) - mr) <= COLOR_TOL
                   and abs(((c >> 8) & 0xFF) - mg) <= COLOR_TOL
                   and abs((c & 0xFF) - mb) <= COLOR_TOL)
        uniformity = near / float(size)

        row_lo, row_hi, col_lo, col_hi = {}, {}, {}, {}
        for c in cells:
            cx, cy = c % gw, c // gw
            row_lo[cy] = min(row_lo.get(cy, 1 << 30), cx)
            row_hi[cy] = max(row_hi.get(cy, -1), cx)
            col_lo[cx] = min(col_lo.get(cx, 1 << 30), cy)
            col_hi[cx] = max(col_hi.get(cx, -1), cy)
        # Kotlin 用整数门槛，这里保持一致（不能用浮点比较）
        row_span_need = int(cell_w * RECT_SPAN_FRAC)
        col_span_need = int(cell_h * RECT_SPAN_FRAC)
        rows_ok = sum(1 for cy in row_lo if row_hi[cy] - row_lo[cy] + 1 >= row_span_need)
        cols_ok = sum(1 for cx in col_lo if col_hi[cx] - col_lo[cx] + 1 >= col_span_need)
        row_cover = rows_ok / float(len(row_lo))
        col_cover = cols_ok / float(len(col_lo))
        rectangular = row_cover >= RECT_ROW_COVER_MIN and col_cover >= RECT_COL_COVER_MIN

        is_bar = (fill >= FILL_MIN and bw >= w * MIN_WIDTH_FRAC and bh >= MIN_HEIGHT_PX
                  and bh <= h * MAX_HEIGHT_FRAC and aspect >= MIN_ASPECT
                  and cyf >= MIN_CENTER_Y_FRAC)
        is_cta = (fill >= CTA_FILL_MIN
                  and w * CTA_MIN_WIDTH_FRAC <= bw <= w * CTA_MAX_WIDTH_FRAC
                  and h * CTA_MIN_HEIGHT_FRAC <= bh <= h * CTA_MAX_HEIGHT_FRAC
                  and cxf >= CTA_MIN_CENTER_X_FRAC and cyf >= CTA_MIN_CENTER_Y_FRAC)
        out.append(dict(size=size, bw=bw, bh=bh, fill=fill, aspect=aspect, cxf=cxf, cyf=cyf,
                        x0=x0 * step, y0=y0 * step, uni=uniformity,
                        rowc=row_cover, colc=col_cover,
                        shape="A" if is_bar else ("B" if is_cta else ""),
                        rect=rectangular,
                        hit=rectangular and (is_bar or is_cta),
                        mean=(int(mr), int(mg), int(mb))))
    return out


def analyze_path(path, step=ORANGE_SAMPLE_STEP):
    """返回 dict：hit / ratio_pct / bar / cta / best / blocks。"""
    im = Image.open(path).convert("RGB")
    w, h = im.size
    data = im.tobytes()
    grid, gw, gh, orange_cells = _build_grid(data, w, h, step)
    blocks = _blocks(grid, gw, gh, w, h, step)
    total = gw * gh
    ratio = 0.0 if total == 0 else orange_cells * 100.0 / total
    bar = next((b for b in blocks if b["shape"] == "A"), None)
    cta = next((b for b in blocks if b["shape"] == "B"), None)
    best = max(blocks, key=lambda b: b["size"]) if blocks else None
    return dict(hit=bool(bar or cta), ratio_pct=ratio, bar=bar, cta=cta, best=best,
                blocks=blocks, size=(w, h), grid=(gw, gh), step=step)


def probe(path, step=ORANGE_SAMPLE_STEP):
    r = analyze_path(path, step)
    w, h = r["size"]
    gw, gh = r["grid"]
    print(f"截图: {path}  尺寸 {w}x{h}  step={step}  网格 {gw}x{gh}")
    print(f"橙色格占比(盒平均后): {r['ratio_pct']:.2f}%   采样到橙色连通块 {len(r['blocks'])} 个")
    blocks = sorted(r["blocks"], key=lambda b: -b["size"])
    print(f"\n连通块 {len(blocks)} 个，按大小前 8:")
    for i, b in enumerate(blocks[:8], 1):
        tag = []
        if b["shape"]:
            tag.append(f"形态{b['shape']}")
            tag.append("矩形✓" if b["rect"] else "矩形✗")
        print(f"  #{i} {b['bw']:>4}x{b['bh']:<4} @({b['x0']},{b['y0']}) 填充 {b['fill']:.2f} "
              f"长宽比 {b['aspect']:.1f} 中心({b['cxf']:.2f},{b['cyf']:.2f}) "
              f"纯度 {b['uni']:.2f}(仅排查) 行跨 {b['rowc']:.2f} 列跨 {b['colc']:.2f} "
              f"色#{b['mean'][0]:02X}{b['mean'][1]:02X}{b['mean'][2]:02X} "
              f"{'/'.join(tag) if tag else '×'}")
    who = r["bar"] or r["cta"]
    print(f"\n判定: {'是广告橙色按钮' if r['hit'] else '不是橙色按钮'}"
          + (f"（形态{'A' if r['bar'] else 'B'} {who['bw']}x{who['bh']} "
             f"填充 {who['fill']:.2f} 行跨 {who['rowc']:.2f} 列跨 {who['colc']:.2f}）"
             if who else ""))
    return r


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--check":
        import fixture_check
        sys.exit(fixture_check.main())
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    probe(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else ORANGE_SAMPLE_STEP)
