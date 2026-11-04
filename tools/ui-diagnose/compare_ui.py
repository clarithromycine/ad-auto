# -*- coding: utf-8 -*-
"""
比较两份（或多份）uiautomator dump 的结构：节点数、资源 id 集合、类名直方图、
可点击节点集合、以及两两差异 —— 用于找出「广告界面」与「正剧界面」的区别特征。

用法： python compare_ui.py cap/ui_cur.xml cap/ui_drama.xml [more.xml ...]
"""
import re
import sys
import xml.etree.ElementTree as ET


def load(path):
    raw = open(path, 'rb').read()
    try:
        t = raw.decode('utf-8')
    except UnicodeDecodeError:
        t = raw.decode('utf-16')
    end = t.rfind('</hierarchy>')
    if end != -1:
        t = t[:end + len('</hierarchy>')]
    root = ET.fromstring(t)

    nodes = []
    for n in root.iter('node'):
        a = n.attrib
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a.get('bounds', ''))
        b = tuple(map(int, m.groups())) if m else (0, 0, 0, 0)
        nodes.append(dict(
            cls=a.get('class', ''), rid=a.get('resource-id', ''),
            text=a.get('text', ''), desc=a.get('content-desc', ''),
            click=a.get('clickable') == 'true', scroll=a.get('scrollable') == 'true',
            bounds=b, w=b[2] - b[0], h=b[3] - b[1],
            cx=(b[0] + b[2]) // 2, cy=(b[1] + b[3]) // 2,
        ))
    return nodes


def sig(n):
    """节点的结构签名（不含绝对坐标，但含尺寸与相对位置分类）"""
    return '%s|%s|%dx%d|click=%s|scroll=%s|text=%s|desc=%s' % (
        n['cls'].split('.')[-1], n['rid'].split('/')[-1], n['w'], n['h'],
        int(n['click']), int(n['scroll']), n['text'][:12], n['desc'][:12])


paths = sys.argv[1:]
data = {}
for p in paths:
    try:
        data[p] = load(p)
    except Exception as e:  # noqa: BLE001
        print(p, 'load failed:', e)

for p, nodes in data.items():
    rids = sorted({n['rid'].split('/')[-1] for n in nodes if n['rid']})
    cls = {}
    for n in nodes:
        key = n['cls'].split('.')[-1]
        cls[key] = cls.get(key, 0) + 1
    texts = [n['text'] for n in nodes if n['text'].strip()]
    print('==', p, 'nodes=%d click=%d text=%d' % (len(nodes), sum(1 for n in nodes if n['click']), len(texts)))
    print('   classes:', dict(sorted(cls.items(), key=lambda kv: -kv[1])))
    print('   rids(%d):' % len(rids), rids)

if len(data) >= 2:
    items = list(data.items())
    for i in range(len(items)):
        for j in range(i + 1, len(items)):
            (p1, n1), (p2, n2) = items[i], items[j]
            s1, s2 = [sig(n) for n in n1], [sig(n) for n in n2]
            from collections import Counter
            c1, c2 = Counter(s1), Counter(s2)
            only1 = list((c1 - c2).elements())
            only2 = list((c2 - c1).elements())
            print('\n--- diff %s  vs  %s ---' % (p1, p2))
            print(' only in %s (%d):' % (p1, len(only1)))
            for s in only1[:40]:
                print('   -', s)
            print(' only in %s (%d):' % (p2, len(only2)))
            for s in only2[:40]:
                print('   +', s)
