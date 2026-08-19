# -*- coding: utf-8 -*-
"""Print full node structure (class/bounds/text/desc/clickable) of ui_dump.xml"""
import re
import xml.etree.ElementTree as ET

raw = open('ui_dump.xml', 'rb').read()
try:
    xml = raw.decode('utf-8')
except UnicodeDecodeError:
    xml = raw.decode('utf-16')
xml = re.sub(r'&(?!amp;|lt;|gt;|quot;|apos;|#\d+;)', '&amp;', xml)
end = xml.rfind('</hierarchy>')
if end != -1:
    xml = xml[:end + len('</hierarchy>')]

root = ET.fromstring(xml)

def walk(el, depth=0):
    txt = (el.get('text', '') or '')
    desc = (el.get('content-desc', '') or '')
    cls = el.get('class', '') or ''
    bounds = el.get('bounds', '') or ''
    click = el.get('clickable', 'false')
    pkg = el.get('package', '') or ''
    if txt.strip() or desc.strip() or 'SurfaceView' in cls or 'ViewGroup' in cls:
        line = '  ' * depth
        line += '%s' % cls
        if click == 'true':
            line += ' [CLICKABLE]'
        line += ' %s' % bounds
        if txt.strip():
            line += ' text=%r' % txt[:60]
        if desc.strip():
            line += ' desc=%r' % desc[:60]
        print(line)
    for c in el:
        walk(c, depth + 1)

walk(root)
