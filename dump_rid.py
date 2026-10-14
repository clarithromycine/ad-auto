# -*- coding: utf-8 -*-
"""
列出 dump 里所有带 resource-id 的节点，并标注 clickable / visible-to-user /
important-for-accessibility，用于排查「具名 id」类广告特征（参考
ttlive_player_render_view 的成功经验）。

用法： python dump_rid.py [ui_now.xml]
"""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else 'ui_dump.xml'
raw = open(path, 'rb').read()
try:
    t = raw.decode('utf-8')
except UnicodeDecodeError:
    t = raw.decode('utf-16')

end = t.rfind('</hierarchy>')
if end != -1:
    t = t[:end + len('</hierarchy>')]

rows = []
for m in re.finditer(r'<node[^>]*>', t):
    tag = m.group(0)
    rid = re.search(r'resource-id="([^"]*)"', tag)
    if not rid or not rid.group(1).strip():
        continue
    cls = re.search(r'class="([^"]*)"', tag)
    b = re.search(r'bounds="([^"]*)"', tag)
    flags = []
    if 'clickable="true"' in tag:
        flags.append('CLICKABLE')
    if 'visible-to-user="false"' in tag:
        flags.append('INVISIBLE')
    imp = re.search(r'important-for-accessibility="([^"]*)"', tag)
    if imp and imp.group(1) != 'auto':
        flags.append('impa11y=' + imp.group(1))
    rows.append((rid.group(1), cls.group(1) if cls else '',
                 b.group(1) if b else '', ' '.join(flags)))

print('dump:', path)
print('rid nodes:', len(rows))
for rid, cls, b, flags in rows:
    print(' %-46s %-40s %-24s %s' % (rid, cls, b, flags))
