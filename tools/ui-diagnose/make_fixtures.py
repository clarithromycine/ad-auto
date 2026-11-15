#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成像素判据回归语料（合成帧 → PNG）。

为什么要合成帧：仓库里没有真机截图，而 `vision/OrangeButton` 等判据的采样分辨率与阈值
全部是从真机实测里标定出来的。这里把这些**实测样本**重建为确定性图像，
让 Kotlin 单测与 Python 标定工具跑在同一份语料上 —— 双实现一旦漂移，测试立刻失败。

文件名约定（单测与 fixture_check.py 都按这个约定读取）：

    <criterion>_<slug>_<hit|miss>.png
    criterion ∈ {orange, hint, pillar}

用法:
    python3 tools/ui-diagnose/make_fixtures.py
输出:
    app/src/test/resources/fixtures/*.png

只依赖标准库（自带极简 PNG 编码器），不需要 Pillow。
"""
import os
import struct
import zlib

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       '..', '..', 'app', 'src', 'test', 'resources', 'fixtures')

BG_DARK = (30, 30, 30)
ORANGE = (239, 142, 62)  # 实测广告 CTA 均值 #EF8E3E


def write_png(path, width, height, rows):
    """rows: 每行长度 = width*3 的 RGB 字节串。"""
    raw = b''.join(b'\x00' + bytes(r) for r in rows)

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
           + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
    with open(path, 'wb') as f:
        f.write(png)
    print('  %-34s %dx%d  %6.1f KB' % (os.path.basename(path), width, height,
                                       len(png) / 1024.0))


def blank(width, height, rgb):
    return [bytearray(bytes(rgb) * width) for _ in range(height)]


def fill_rect(rows, width, x0, y0, x1, y1, rgb):
    """填充闭区间矩形（1-based 坐标到像素索引，两端都含）。"""
    x1 = min(x1, width - 1)
    span = bytes(rgb) * (x1 - x0 + 1)
    for y in range(y0, y1 + 1):
        rows[y][x0 * 3:(x1 + 1) * 3] = span


# --------------------------------------------------------------------------
# 橙色按钮：屏幕 2670x1200（真机 小米 23127PN0CC）
# --------------------------------------------------------------------------
def orange_bar_hit():
    """实测「查看详情」宽条：2082x108、填充率 0.97 → 形态 A 命中。"""
    w, h = 2670, 1200
    rows = blank(w, h, BG_DARK)
    # x 296..2375（2080 宽，4 对齐）、y 800..907（108 高）→ 填充率 1.00
    fill_rect(rows, w, 296, 800, 2375, 907, ORANGE)
    return w, h, rows


def orange_scene_miss():
    """横屏正剧里的橙块：336x360、填充率 0.40 → 长宽比 0.93，不是按钮。"""
    w, h = 2670, 1200
    rows = blank(w, h, BG_DARK)
    x0, y0, x1, y1 = 1200, 300, 1535, 659
    # 竖脊 + 每 12px 一条横齿：连通成一块，填充率≈0.34，但形状不是横条
    fill_rect(rows, w, x0, y0, x0 + 3, y1, ORANGE)
    for y in range(y0, y1 + 1, 12):
        fill_rect(rows, w, x0, y, x1, y + 3, ORANGE)
    return w, h, rows


def orange_cta_hit():
    """右下角 CTA：216x84、中心(0.85, 0.92) → 形态 B 命中；并放一个更大的散碎块作干扰。"""
    w, h = 2670, 1200
    rows = blank(w, h, BG_DARK)
    # 干扰块：400x240、填充率≈0.34、长宽比 1.67（比 CTA 大得多，但不是按钮）
    fill_rect(rows, w, 400, 300, 403, 539, ORANGE)
    for y in range(300, 540, 12):
        fill_rect(rows, w, 400, y, 799, y + 3, ORANGE)
    # 真正的 CTA：216x84
    fill_rect(rows, w, 2160, 1060, 2375, 1143, ORANGE)
    return w, h, rows


# --------------------------------------------------------------------------
# 底部提示栏：屏幕 1280x720
# --------------------------------------------------------------------------
def hint_bar_hit():
    """纯黑底 + 居中白字：栏高≈10%、黑≈0.99、白≈0.008、跨度≈0.13、中心≈0.49。"""
    w, h = 1280, 720
    rows = blank(w, h, (90, 110, 130))
    fill_rect(rows, w, 0, 644, w - 1, h - 1, (0, 0, 0))
    for k in range(8):  # 8 个“字符”，每个 8x12
        gx = 544 + 24 * k
        fill_rect(rows, w, gx, 670, gx + 7, 681, (255, 255, 255))
    return w, h, rows


def hint_subtitle_miss():
    """暗场景视频 + 烧录字幕：栏高 19.8%、黑 0.91 → 黑底下限 0.95 挡不住用高度，靠黑度挡掉。"""
    w, h = 1280, 720
    rows = blank(w, h, (90, 110, 130))
    top = 578  # 142px = 19.7%
    for y in range(top, h):
        row = rows[y]
        for x in range(w):
            # 约 7.7% 的像素是“场景细节”（亮度 140），其余纯黑 → 黑度≈0.92（实测 0.91）
            if ((x // 4) + (y // 4)) % 13 == 0:
                row[x * 3:x * 3 + 3] = b'\x8c\x8c\x8c'
    for k in range(8):
        gx = 544 + 24 * k
        fill_rect(rows, w, gx, 690, gx + 7, 701, (255, 255, 255))
    return w, h, rows


# --------------------------------------------------------------------------
# 信箱（pillarbox）：屏幕 3200x2136
# --------------------------------------------------------------------------
def pillar_squeeze_hit():
    """竖屏素材塞进横屏：内容带 0.38 宽、中央高细节、两侧低细节灰色模糊填充。"""
    w, h = 3200, 2136
    left, right = 992, 2208          # 0.31 .. 0.69 → band 0.38
    row = bytearray(w * 3)
    for x in range(w):
        if left <= x < right:
            v = 20 if ((x - left) // 4) % 2 == 0 else 230
        else:
            v = 44                    # 模糊填充：有亮度、无高频细节
        row[x * 3:x * 3 + 3] = bytes((v, v, v))
    return w, h, [bytearray(row) for _ in range(h)]


def pillar_letterbox_miss():
    """16:9 正剧在 2.2:1 屏上保比例播放：内容带 0.80、两侧纯黑边 → 必须判 miss。"""
    w, h = 3200, 2136
    left, right = 320, 2880          # 0.10 .. 0.90 → band 0.80
    row = bytearray(w * 3)
    for x in range(w):
        if left <= x < right:
            v = 60 if ((x - left) // 8) % 2 == 0 else 200
        else:
            v = 0                     # 纯黑边
        row[x * 3:x * 3 + 3] = bytes((v, v, v))
    return w, h, [bytearray(row) for _ in range(h)]


FIXTURES = {
    'orange_bar_hit.png': orange_bar_hit,
    'orange_scene_miss.png': orange_scene_miss,
    'orange_cta_hit.png': orange_cta_hit,
    'hint_bar_hit.png': hint_bar_hit,
    'hint_subtitle_miss.png': hint_subtitle_miss,
    'pillar_squeeze_hit.png': pillar_squeeze_hit,
    'pillar_letterbox_miss.png': pillar_letterbox_miss,
}


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    print('writing fixtures to %s' % os.path.normpath(OUT_DIR))
    for name, fn in FIXTURES.items():
        w, h, rows = fn()
        write_png(os.path.join(OUT_DIR, name), w, h, rows)


if __name__ == '__main__':
    main()
