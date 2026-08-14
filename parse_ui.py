import re, sys

raw = open('ui_dump.xml', 'rb').read()
try:
    text = raw.decode('utf-8')
except UnicodeDecodeError:
    text = raw.decode('utf-16')

# Find all switch nodes
for m in re.finditer(r'<node[^>]*resource-id="[^"]*switch[^"]*"[^>]*>', text):
    tag = m.group(0)
    rid = re.search(r'resource-id="([^"]*)"', tag).group(1)
    checked = re.search(r'checked="(true|false)"', tag)
    bounds = re.search(r'bounds="([^"]*)"', tag)
    print(rid, 'checked=', checked.group(1) if checked else '?', 'bounds=', bounds.group(1) if bounds else '?')

# Also find overall status text
for m in re.finditer(r'text="([^"]*(?:已开启|已关闭|开启|关闭)[^"]*)"', text):
    print('TEXT:', m.group(1))
