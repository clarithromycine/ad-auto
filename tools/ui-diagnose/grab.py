# -*- coding: utf-8 -*-
"""
稳健地抓取当前界面：截图 + uiautomator dump（失败自动重试，video 播放时
uiautomator 常报 "could not get idle state"），并打印关键结构指纹：
- 是否含 TextureView / ttlive_player_render_view（直播播放器）
- 是否含 SurfaceView（正剧播放器）
- 文字节点数量与内容、可点击节点数量
- 中央 539x140 胶囊（“点击进入直播间”提示条）及其子节点

用法： python grab.py 标签 [重试次数]
输出： ./cap/grab_<标签>.png / .xml（相对本脚本目录）
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

tag = sys.argv[1] if len(sys.argv) > 1 else 'x'
retries = int(sys.argv[2]) if len(sys.argv) > 2 else 10

CAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'cap')
os.makedirs(CAP, exist_ok=True)
png = os.path.join(CAP, 'grab_%s.png' % tag)
xml = os.path.join(CAP, 'grab_%s.xml' % tag)


def adb(*args):
    return subprocess.run(['adb'] + list(args), capture_output=True)


adb('shell', 'rm', '-f', '/sdcard/g.xml')
ok = False
for i in range(retries):
    r = adb('shell', 'uiautomator', 'dump', '/sdcard/g.xml')
    out = (r.stdout + r.stderr).decode('utf-8', 'replace')
    if 'dumped to' in out:
        ok = True
        break
    print('  dump retry %d: %s' % (i, out.strip()))
    time.sleep(1.5)

adb('shell', 'screencap', '-p', '/sdcard/g.png')
adb('pull', '/sdcard/g.png', png)
if ok:
    adb('pull', '/sdcard/g.xml', xml)

print('dump ok:', ok, '->', xml if ok else '(no xml)', png)
if not ok:
    sys.exit(0)

raw = open(xml, 'rb').read()
try:
    t = raw.decode('utf-8')
except UnicodeDecodeError:
    t = raw.decode('utf-16')
end = t.rfind('</hierarchy>')
if end != -1:
    t = t[:end + len('</hierarchy>')]
root = ET.fromstring(t)
nodes = list(root.iter('node'))

texts = [n.attrib.get('text', '') for n in nodes if n.attrib.get('text', '').strip()]
descs = [n.attrib.get('content-desc', '') for n in nodes if n.attrib.get('content-desc', '').strip()]
classes = {}
for n in nodes:
    k = n.attrib.get('class', '').split('.')[-1]
    classes[k] = classes.get(k, 0) + 1
rids = sorted({n.attrib.get('resource-id', '').split('/')[-1] for n in nodes
               if n.attrib.get('resource-id')})

print('nodes=%d clickable=%d texts=%d descs=%d' % (
    len(nodes), sum(1 for n in nodes if n.attrib.get('clickable') == 'true'), len(texts), len(descs)))
print('TextureView x%d  SurfaceView x%d  ttlive_player_render_view=%s' % (
    classes.get('TextureView', 0), classes.get('SurfaceView', 0),
    'ttlive_player_render_view' in rids))
print('classes:', dict(sorted(classes.items(), key=lambda kv: -kv[1])))
print('texts:', ' | '.join(texts[:20]) or '(none)')
print('descs:', ' | '.join(descs[:10]) or '(none)')
