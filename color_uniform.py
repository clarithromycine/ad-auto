"""测量色块"纯度"（同一色值占比），用于评估「按钮=单一色值」判据。

用法:
    python color_uniform.py <screenshot.png> [step]

对每个连通块（按量化后的颜色聚类）输出:
    尺寸 / 填充率 / 长宽比 / 中心 / 主导色 / 主导占比(=纯度) / 色值离散度
"""
import sys
from collections import deque, Counter

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow: pip install pillow")
    sys.exit(1)

# 5 bit/通道（32 级）量化：实心按钮落进同一个桶，视频噪声会散开
SHIFT = 3


def q(c):
    r, g, b = c
    return ((r >> SHIFT) << SHIFT, (g >> SHIFT) << SHIFT, (b >> SHIFT) << SHIFT)


def analyze(path, step=4):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    gw, gh = (w + step - 1) // step, (h + step - 1) // step

    # 采样成网格，记录每格量化色
    grid = [[None] * gw for _ in range(gh)]
    for gy in range(gh):
        y = gy * step
        if y >= h:
            break
        for gx in range(gw):
            x = gx * step
            if x >= w:
                break
            grid[gy][gx] = q(px[x, y])

    print(f"截图 {path}  {w}x{h}  step={step}  网格 {gw}x{gh}")

    # 全图颜色分布
    allc = Counter()
    for row in grid:
        for c in row:
            if c:
                allc[c] += 1
    print(f"全图量化色种类: {len(allc)}   最常见前 5: {allc.most_common(5)}")

    # 按“完全相同量化色”做 4 邻域连通块
    seen = [[False] * gw for _ in range(gh)]
    blobs = []
    for sy in range(gh):
        for sx in range(gw):
            c0 = grid[sy][sx]
            if not c0 or seen[sy][sx]:
                continue
            dq = deque([(sx, sy)])
            seen[sy][sx] = True
            cells = []
            while dq:
                cx, cy = dq.popleft()
                cells.append((cx, cy))
                for nx, ny in ((cx - 1, cy), (cx + 1, cy), (cx, cy - 1), (cx, cy + 1)):
                    if 0 <= nx < gw and 0 <= ny < gh and grid[ny][nx] == c0 and not seen[ny][nx]:
                        seen[ny][nx] = True
                        dq.append((nx, ny))
            if len(cells) < 4:
                continue
            xs = [c[0] for c in cells]
            ys = [c[1] for c in cells]
            x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
            bw = (x1 - x0 + 1) * step
            bh = (y1 - y0 + 1) * step
            fill = len(cells) / float((x1 - x0 + 1) * (y1 - y0 + 1))
            # 纯度 = 该连通块内主导色占比（块内若混色，说明不是实心按钮）
            inside = Counter()
            for (cx, cy) in cells:
                inside[grid[cy][cx]] += 1
            dom, domn = inside.most_common(1)[0]
            purity = domn / float(len(cells))
            blobs.append(dict(size=len(cells), bw=bw, bh=bh, fill=fill, purity=purity,
                              dom=dom, x0=x0 * step, y0=y0 * step,
                              cxf=(x0 + x1) / 2.0 * step / w, cyf=(y0 + y1) / 2.0 * step / h,
                              aspect=bw / max(bh, 1)))

    blobs.sort(key=lambda b: -b["size"])
    print(f"\n按同色聚类得到 {len(blobs)} 个块，前 10 大:")
    print(f"{'#':>3} {'尺寸':>12} {'位置(x,y)':>14} {'填充':>5} {'纯度':>5} {'长宽比':>6} {'中心':>12}  主导色")
    for i, b in enumerate(blobs[:10], 1):
        print(f"{i:>3} {b['bw']:>5}x{b['bh']:<6} ({b['x0']:>4},{b['y0']:>4}) "
              f"{b['fill']:>5.2f} {b['purity']:>5.2f} {b['aspect']:>6.1f} "
              f"({b['cxf']:.2f},{b['cyf']:.2f})  #{b['dom'][0]:02X}{b['dom'][1]:02X}{b['dom'][2]:02X}")

    # 按钮形态候选：实心 + 纯度 1.0 + 形状像按钮
    print("\n按钮形态候选（填充≥0.80 且 长宽比 1.2~6 且 宽 4%~30% 屏宽 且 高 2%~12% 屏高）:")
    n = 0
    for b in blobs:
        if (b["fill"] >= 0.80 and 1.2 <= b["aspect"] <= 6
                and 0.04 * w <= b["bw"] <= 0.30 * w and 0.02 * h <= b["bh"] <= 0.12 * h):
            r, g, bl = b["dom"]
            warm = r >= 120 and r > bl and (r - bl) >= 40
            print(f"   {b['bw']}x{b['bh']} @({b['x0']},{b['y0']}) 填充 {b['fill']:.2f} "
                  f"纯度 {b['purity']:.2f} 中心({b['cxf']:.2f},{b['cyf']:.2f}) "
                  f"色 #{r:02X}{g:02X}{bl:02X} 暖色={warm}")
            n += 1
    if n == 0:
        print("   （无）")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    analyze(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 4)
