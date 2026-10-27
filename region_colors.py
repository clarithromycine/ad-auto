"""查看指定矩形区域的颜色分布（判断是否为单一纯色，占位）"""
import sys
from collections import Counter

from PIL import Image

path = sys.argv[1]
x0, y0, bw, bh = (int(v) for v in sys.argv[2:6])
step = int(sys.argv[6]) if len(sys.argv) > 6 else 2
shift = int(sys.argv[7]) if len(sys.argv) > 7 else 3

im = Image.open(path).convert("RGB")
px = im.load()
cnt = Counter()
total = 0
for y in range(y0, y0 + bh, step):
    for x in range(x0, x0 + bw, step):
        if 0 <= x < im.width and 0 <= y < im.height:
            r, g, b = px[x, y]
            cnt[((r >> shift) << shift, (g >> shift) << shift, (b >> shift) << shift)] += 1
            total += 1

print(f"区域 ({x0},{y0}) {bw}x{bh}  step={step} quant={1<<(8-shift)}级")
print(f"采样点 {total}，不同量化色 {len(cnt)} 种")
for c, n in cnt.most_common(8):
    print(f"  #{c[0]:02X}{c[1]:02X}{c[2]:02X}  {n:>6}  {n*100.0/total:5.1f}%")
print(f"主导色占比(纯度) = {cnt.most_common(1)[0][1]*100.0/total:.1f}%  前两种合计 = "
      f"{sum(n for _, n in cnt.most_common(2))*100.0/total:.1f}%")
