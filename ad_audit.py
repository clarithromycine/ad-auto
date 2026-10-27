"""横屏有效性审计：独立抓屏 + 三层判据，检查 App 是否漏跳。

用法:
    python ad_audit.py [秒数] [采样间隔秒]

原理：不依赖 App 自己的判定 —— 直接 adb screencap 抓帧，用与 App 完全一致的三层
判据（形状 A/B + 颜色纯度 + 近似矩形）判断这一帧是否存在"广告按钮"。
只有当 CTA 连续出现在 >=2 次采样（说明不是一闪而过的画面）且这段时间 App 没有任何
上滑动作时，才判定为一次"漏跳"。
"""
import io
import subprocess
import sys
import time
from collections import deque

from PIL import Image

STEP = 4
COLOR_TOL = 12
UNIFORMITY_MIN = 0.85
RECT_SPAN = 0.80
RECT_ROW_MIN = 0.70
RECT_COL_MIN = 0.70
# 形态 A
A_FILL, A_MIN_W, A_MIN_H, A_MAX_H, A_MIN_ASPECT, A_MIN_CY = 0.65, 0.25, 20, 0.30, 2.5, 0.30
# 形态 B
B_FILL, B_MIN_W, B_MAX_W, B_MIN_H, B_MAX_H, B_MIN_CX, B_MIN_CY = (
    0.75, 0.05, 0.20, 0.03, 0.15, 0.75, 0.80)


def is_orange(r, g, b):
    return r >= 140 and 40 <= g <= 200 and b <= 110 and (r - b) >= 70 and r >= g >= b


def check(img):
    """返回 (是否命中, 描述)"""
    w, h = img.size
    px = img.load()
    gw, gh = (w + STEP - 1) // STEP, (h + STEP - 1) // STEP
    colors = {}
    for gy in range(gh):
        y = gy * STEP
        if y >= h:
            break
        for gx in range(gw):
            x = gx * STEP
            if x >= w:
                break
            c = px[x, y]
            if is_orange(*c):
                colors[(gx, gy)] = c

    seen = set()
    best = None
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
        if len(cells) < 4:
            continue
        xs = [c[0] for c in cells]
        ys = [c[1] for c in cells]
        x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
        bw = (x1 - x0 + 1) * STEP
        bh = (y1 - y0 + 1) * STEP
        fill = len(cells) / float((x1 - x0 + 1) * (y1 - y0 + 1))
        aspect = bw / max(bh, 1)
        cxf = (x0 + x1) / 2.0 * STEP / w
        cyf = (y0 + y1) / 2.0 * STEP / h
        n = len(cells)
        mr = sum(colors[c][0] for c in cells) / n
        mg = sum(colors[c][1] for c in cells) / n
        mb = sum(colors[c][2] for c in cells) / n
        near = sum(1 for c in cells
                   if abs(colors[c][0] - mr) <= COLOR_TOL
                   and abs(colors[c][1] - mg) <= COLOR_TOL
                   and abs(colors[c][2] - mb) <= COLOR_TOL)
        uni = near / float(n)
        row_lo, row_hi, col_lo, col_hi = {}, {}, {}, {}
        for (cx, cy) in cells:
            row_lo[cy] = min(row_lo.get(cy, 10 ** 9), cx)
            row_hi[cy] = max(row_hi.get(cy, -1), cx)
            col_lo[cx] = min(col_lo.get(cx, 10 ** 9), cy)
            col_hi[cx] = max(col_hi.get(cx, -1), cy)
        wsel = RECT_SPAN * (x1 - x0 + 1)
        hsel = RECT_SPAN * (y1 - y0 + 1)
        rowc = sum(1 for k in row_lo if row_hi[k] - row_lo[k] + 1 >= wsel) / float(len(row_lo))
        colc = sum(1 for k in col_lo if col_hi[k] - col_lo[k] + 1 >= hsel) / float(len(col_lo))

        is_a = (fill >= A_FILL and bw >= w * A_MIN_W and bh >= A_MIN_H
                and bh <= h * A_MAX_H and aspect >= A_MIN_ASPECT and cyf >= A_MIN_CY)
        is_b = (fill >= B_FILL and w * B_MIN_W <= bw <= w * B_MAX_W
                and h * B_MIN_H <= bh <= h * B_MAX_H and cxf >= B_MIN_CX and cyf >= B_MIN_CY)
        hit = (is_a or is_b) and uni >= UNIFORMITY_MIN and rowc >= RECT_ROW_MIN and colc >= RECT_COL_MIN
        d = f"{bw}x{bh}@({x0 * STEP},{y0 * STEP}) 填充{fill:.2f} 纯度{uni:.2f} 行跨{rowc:.2f} 列跨{colc:.2f}"
        if hit and (best is None or len(cells) > best[0]):
            best = (len(cells), d)
    if best:
        return True, best[1]
    return False, ""


def screencap():
    out = subprocess.run(["adb", "exec-out", "screencap", "-p"],
                         capture_output=True, check=False)
    if out.returncode != 0 or len(out.stdout) < 1000:
        return None
    try:
        return Image.open(io.BytesIO(out.stdout)).convert("RGB")
    except Exception:
        return None


def foreground():
    out = subprocess.run(["adb", "shell", "dumpsys", "window"], capture_output=True, text=True,
                         errors="ignore")
    for line in out.stdout.splitlines():
        if "mCurrentFocus" in line:
            return line.strip()
    return "?"


def main():
    dur = float(sys.argv[1]) if len(sys.argv) > 1 else 120
    gap = float(sys.argv[2]) if len(sys.argv) > 2 else 2.5
    print(f"审计 {dur:.0f}s，每 {gap}s 采样一次。Ctrl+C 提前结束。\n")
    start = time.time()
    prev_hit = False
    run = 0            # 连续命中计数
    misses = 0
    samples = 0
    hits = 0
    while time.time() - start < dur:
        t = time.strftime("%H:%M:%S")
        img = screencap()
        if img is None:
            print(f"{t}  截图失败")
        else:
            samples += 1
            hit, desc = check(img)
            if hit:
                hits += 1
                run += 1
                print(f"{t}  {img.size[0]}x{img.size[1]}  ★ 橙色按钮 {desc}")
                if run == 2:
                    print(f"{t}   ↑ 连续 2 帧出现 CTA —— 检查 App 是否在该时段动作")
                    misses += 1
            else:
                if run >= 2:
                    print(f"{t}   （CTA 消失，此前连续 {run} 帧）")
                run = 0
                print(f"{t}  {img.size[0]}x{img.size[1]}  -")
            prev_hit = hit
        time.sleep(gap)
    print(f"\n采样 {samples} 次，命中 {hits} 次，连续命中段 {misses} 段")
    print("前台:", foreground())
    print("\n下一步：把上面的时间线与 `adb logcat -d -s AdDetector:V` 对账 —— "
          "连续命中段内若没有任何 SWIPE_UP，就是漏跳。")


if __name__ == "__main__":
    main()
