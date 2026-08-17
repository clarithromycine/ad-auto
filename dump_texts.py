import re, sys

raw = open('ui_dump.xml', 'rb').read()
try:
    t = raw.decode('utf-8')
except UnicodeDecodeError:
    t = raw.decode('utf-16')

print('=== TEXTS ===')
vals = [m.group(1) for m in re.finditer(r'text="([^"]*)"', t) if m.group(1).strip()]
for v in vals:
    print(' -', v)
print('=== CONTENT-DESC ===')
descs = [m.group(1) for m in re.finditer(r'content-desc="([^"]*)"', t) if m.group(1).strip()]
for d in descs:
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
