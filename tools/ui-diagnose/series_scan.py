# -*- coding: utf-8 -*-
"""
按固定间隔连续抓取设备上的 uiautomator dump + 截图，并打印每帧的“结构指纹”，
用于找出「广告界面」与「正剧播放界面」在无障碍树里的区别特征。

用法： python series_scan.py [样本数] [间隔秒]
输出： ./cap/series/sNN.png + ./cap/series/sNN.xml（相对本脚本目录）
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

N = int(sys.argv[1]) if len(sys.argv) > 1 else 20
INTERVAL = float(sys.argv[2]) if len(sys.argv) > 2 else 3.5

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'cap', 'series')
os.makedirs(OUT, exist_ok=True)


def sh(*args, binary=False):
    r = subprocess.run(['adb'] + list(args), capture_output=True)
    if r.returncode != 0:
        return None if binary else ''
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def parse(path):
    raw = open(path, 'rb').read()
    try:
        t = raw.decode('utf-8')
    except UnicodeDecodeError:
        t = raw.decode('utf-16')
    end = t.rfind('</hierarchy>')
    if end != -1:
        t = t[:end + len('</hierarchy>')]
    return ET.fromstring(t)


def bounds(node):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds', ''))
    return tuple(map(int, m.groups())) if m else (0, 0, 0, 0)


def fingerprint(root):
    nodes = list(root.iter('node'))
    texts = [n.attrib.get('text', '') for n in nodes if n.attrib.get('text', '').strip()]
    descs = [n.attrib.get('content-desc', '') for n in nodes
             if n.attrib.get('content-desc', '').strip()]
    clickable = [n for n in nodes if n.attrib.get('clickable') == 'true']
    rids = [n.attrib.get('resource-id', '').split('/')[-1]
            for n in nodes if n.attrib.get('resource-id')]
    # 中央 539x140 的胶囊容器（“点击进入直播间”提示条）是否有子节点
    pill_kids = 0
    pill_found = False
    for n in nodes:
        if n.attrib.get('class', '').endswith('ViewGroup'):
            x1, y1, x2, y2 = bounds(n)
            if abs((x2 - x1) - 539) <= 4 and abs((y2 - y1) - 140) <= 4:
                pill_found = True
                pill_kids = max(pill_kids, len(list(n)))
    # 1087~1200 一带的“嵌套可点击 View 组”（疑似 CTA/红包按钮）
    cta = []
    for n in clickable:
        x1, y1, x2, y2 = bounds(n)
        if x1 >= 1080 and x2 - x1 <= 130:
            cta.append('%dx%d@y%d-%d' % (x2 - x1, y2 - y1, y1, y2))
    return dict(
        count=len(nodes),
        texts=texts,
        descs=descs,
        clickable=len(clickable),
        rids=rids,
        pill=pill_found,
        pill_kids=pill_kids,
        cta=sorted(set(cta)),
    )


for i in range(N):
    tag = 's%02d' % i
    sh('shell', 'screencap', '-p', '/sdcard/s.png')
    sh('shell', 'rm', '-f', '/sdcard/u.xml')
    sh('shell', 'uiautomator', 'dump', '/sdcard/u.xml')
    png = os.path.join(OUT, tag + '.png')
    xml = os.path.join(OUT, tag + '.xml')
    sh('pull', '/sdcard/s.png', png)
    sh('pull', '/sdcard/u.xml', xml)
    try:
        fp = fingerprint(parse(xml))
    except Exception as e:  # noqa: BLE001
        print(tag, 'parse failed:', e)
        time.sleep(INTERVAL)
        continue
    print('%s count=%-4d text=%-2d desc=%-2d click=%-3d pill=%-5s pillKids=%d cta=%s rids=%s'
          % (tag, fp['count'], len(fp['texts']), len(fp['descs']), fp['clickable'],
             fp['pill'], fp['pill_kids'], ','.join(fp['cta']), ','.join(fp['rids'])))
    if fp['texts']:
        print('        texts:', ' | '.join(fp['texts'][:12]))
    if fp['descs']:
        print('        descs:', ' | '.join(fp['descs'][:8]))
    sys.stdout.flush()
    time.sleep(INTERVAL)

print('done')
