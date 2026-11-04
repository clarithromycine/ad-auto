# -*- coding: utf-8 -*-
"""
打印一份 uiautomator dump 的完整节点树（缩进层级），包含 class / 资源 id / bounds /
clickable / scrollable / text / desc，用于分析“结构特征”（无障碍树里没有文字时，
只能靠节点结构、屏幕位置、资源 id 来识别广告）。

用法： python dump_tree.py [ui_now.xml]
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

_HERE = os.path.dirname(os.path.abspath(__file__))

path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(_HERE, 'sample', 'ui_dump.xml')
raw = open(path, 'rb').read()
try:
    t = raw.decode('utf-8')
except UnicodeDecodeError:
    t = raw.decode('utf-16')
end = t.rfind('</hierarchy>')
if end != -1:
    t = t[:end + len('</hierarchy>')]

root = ET.fromstring(t)


def short(cls):
    if not cls:
        return ''
    return cls.split('.')[-1]


def walk(node, depth=0):
    a = node.attrib
    b = a.get('bounds', '')
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
    w = h = cx = cy = 0
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        w, h, cx, cy = x2 - x1, y2 - y1, (x1 + x2) // 2, (y1 + y2) // 2
    rid = a.get('resource-id', '').replace('com.phoenix.read:id/', '')
    flags = []
    if a.get('clickable') == 'true':
        flags.append('CLICK')
    if a.get('scrollable') == 'true':
        flags.append('SCROLL')
    if a.get('focusable') == 'true':
        flags.append('focus')
    if a.get('enabled') != 'true':
        flags.append('DISABLED')
    label = '%s%-46s %-22s %4dx%-4d c=(%d,%d) %s' % (
        '  ' * depth, short(a.get('class', '')), rid, w, h, cx, cy, ','.join(flags))
    txt = a.get('text', '')
    desc = a.get('content-desc', '')
    if txt:
        label += ' text=%r' % txt
    if desc:
        label += ' desc=%r' % desc
    print(label)
    for ch in node:
        walk(ch, depth + 1)


print('dump:', path)
print('node count:', len(list(root.iter('node'))))
walk(root)
