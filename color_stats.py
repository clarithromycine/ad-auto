"""测量区域颜色的均值/标准差/容忍带占比 —— 用于「按钮=色值高度一致」判据标定。

用法: python color_stats.py <png> x y w h [step] [tol]
只统计"橙色系"像素（与 App 内同样的橙色判据），避免被白色文字/黑色背景干扰。
"""
import sys
import math

from PIL import Image


def is_orange(r, g, b):
    return r >= 140 and 40 <= g <= 200 and b <= 110 and (r - b) >= 70 and r >= g >= b


path = sys.argv[1]
x0, y0, bw, bh = (int(v) for v in sys.argv[2:6])
step = int(sys.argv[6]) if len(sys.argv) > 6 else 2
tol = int(sys.argv[7]) if len(sys.argv) > 7 else 20

im = Image.open(path).convert("RGB")
px = im.load()
vals = []
for y in range(y0, y0 + bh, step):
    for x in range(x0, x0 + bw, step):
        if 0 <= x < im.width and 0 <= y < im.height:
            c = px[x, y]
            if is_orange(*c):
                vals.append(c)

n = len(vals)
if n == 0:
    print("区域内没有橙色像素")
    sys.exit(0)

mr = sum(v[0] for v in vals) / n
mg = sum(v[1] for v in vals) / n
mb = sum(v[2] for v in vals) / n
sr = math.sqrt(sum((v[0] - mr) ** 2 for v in vals) / n)
sg = math.sqrt(sum((v[1] - mg) ** 2 for v in vals) / n)
sb = math.sqrt(sum((v[2] - mb) ** 2 for v in vals) / n)

inside = sum(1 for v in vals
             if abs(v[0] - mr) <= tol and abs(v[1] - mg) <= tol and abs(v[2] - mb) <= tol)
inside10 = sum(1 for v in vals
               if abs(v[0] - mr) <= 10 and abs(v[1] - mg) <= 10 and abs(v[2] - mb) <= 10)

print(f"区域 ({x0},{y0}) {bw}x{bh} step={step}  橙色像素 {n}")
print(f"  均值 #{int(mr):02X}{int(mg):02X}{int(mb):02X}   "
      f"标准差 R={sr:.1f} G={sg:.1f} B={sb:.1f}  (最大 {max(sr, sg, sb):.1f})")
print(f"  ±{tol} 容忍带内占比 = {inside*100.0/n:.1f}%    ±10 容忍带内占比 = {inside10*100.0/n:.1f}%")
