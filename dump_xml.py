# -*- coding: utf-8 -*-
"""Extract text/content-desc/clickable from a uiautomator dump file.
Usage: python dump_xml.py <path-to-dump.xml>
"""
import re, sys

path = sys.argv[1] if len(sys.argv) > 1 else 'ui_dump.xml'
raw = open(path, 'rb').read()
try:
    t = raw.decode('utf-8')
except UnicodeDecodeError:
    t = raw.decode('utf-16')

print('=== TEXTS ===')
for v in [m.group(1) for m in re.finditer(r'text="([^"]*)"', t) if m.group(1).strip()]:
    print(' -', v)
print('=== CONTENT-DESC ===')
for d in [m.group(1) for m in re.finditer(r'content-desc="([^"]*)"', t) if m.group(1).strip()]:
    print(' -', d)
print('=== CLICKABLE nodes (text/desc/bounds) ===')
for m in re.finditer(r'<node[^>]*clickable="true"[^>]*>', t):
    tag = m.group(0)
    txt = re.search(r'text="([^"]*)"', tag)
    desc = re.search(r'content-desc="([^"]*)"', tag)
    bounds = re.search(r'bounds="([^"]*)"', tag)
    rid = re.search(r'resource-id="([^"]*)"', tag)
    cls = re.search(r'class="([^"]*)"', tag)
    print(' -', 'text=' + (txt.group(1) if txt else ''),
          'desc=' + (desc.group(1) if desc else ''),
          'class=' + (cls.group(1) if cls else ''),
          'rid=' + (rid.group(1) if rid else ''),
          'bounds=' + (bounds.group(1) if bounds else ''))
