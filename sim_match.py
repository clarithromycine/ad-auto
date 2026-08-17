# -*- coding: utf-8 -*-
"""
Replicate AdRules.match() / AdDetector logic against a uiautomator dump
to prove WHY a false positive happens (no execution, pure analysis).
"""
import re, sys
import xml.etree.ElementTree as ET

raw = open('ui_dump.xml', 'rb').read()
try:
    xml = raw.decode('utf-8')
except UnicodeDecodeError:
    xml = raw.decode('utf-16')

# uiautomator dumps sometimes contain raw '&' (not escaped). Fix them.
xml = re.sub(r'&(?!amp;|lt;|gt;|quot;|apos;|#\d+;)', '&amp;', xml)
# drop trailing "UI hierchary dumped to: ..." line(s) after the root element
end = xml.rfind('</hierarchy>')
if end != -1:
    xml = xml[:end + len('</hierarchy>')]

class Node:
    __slots__ = ('text', 'desc', 'cls', 'clickable', 'pkg', 'bounds', 'children')
    def __init__(self, text, desc, cls, clickable, pkg, bounds, children):
        self.text, self.desc, self.cls = text, desc, cls
        self.clickable, self.pkg, self.bounds = clickable, pkg, bounds
        self.children = children

def conv(el):
    return Node(
        text=el.get('text', '') or '', desc=el.get('content-desc', '') or '',
        cls=el.get('class', '') or '',
        clickable=(el.get('clickable') == 'true'),
        pkg=el.get('package', '') or '', bounds=el.get('bounds', '') or '',
        children=[conv(c) for c in el],
    )

root = conv(ET.fromstring(xml))

# flatten visible nodes with text/desc (uiautomator dump lacks isVisibleToUser;
# approximate by dropping zero-size bounds)
def flatten(n):
    out = []
    if n.bounds:
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.bounds)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            if not (x1 == 0 and y1 == 0 and x2 == 0 and y2 == 0):
                if n.text.strip() or n.desc.strip():
                    out.append(n)
    for c in n.children:
        out.extend(flatten(c))
    return out

nodes = flatten(root)
print('collected nodes with text/desc:', len(nodes))

# parent map for findClickable
parents = {}
def build_parents(n, par=None):
    if par is not None:
        parents[id(n)] = par
    for c in n.children:
        build_parents(c, n)
build_parents(root)

def pageTextOf(ns):
    parts = []
    for n in ns:
        if n.text.strip():
            parts.append(n.text)
        if n.desc.strip():
            parts.append(n.desc)
    return ' '.join(parts)

pageText = pageTextOf(nodes)
compactText = re.sub(r'\s+', '', pageText)

# ---------- rules from AdRules.kt ----------
SWIPE_UP_KEYWORDS = ["上滑继续观看","上滑继续","上滑继续看短剧","上滑继续看","上滑看短剧","向上滑动继续观看","上滑解锁","上滑看下一集"]
PLAYBACK_CONTROL_KEYWORDS = ["倍速","选集","热评","分享","评论","展开","暂停","下一集","全集","已完结","作者声明","跟播","点赞","收藏","弹幕"]
EPISODE_REGEX = re.compile(r'第\d+集')
AD_CONTEXT_KEYWORDS = ["广告","advertisement"]
COUNTDOWN_KEYWORDS = ["秒后可继续","s后可继续","S后可继续","秒后继续","s后继续","后可继续","后继续观看","后继续播放","后可观看","倒计时","countdown","CountDown"]
COUNTDOWN_REGEX = re.compile(r'\d+\s*(?:秒|s|S)\s*(?:后|后可继续|后继续|后可观看|后观看|后播放)?')
CLICK_RULES = [
    ("跳过广告", ["跳过广告","跳过此广告","跳過廣告","Skip Ad","SkipAd"], False, False),
    ("跳过", ["跳过","跳過","skip","Skip"], True, False),
    ("关闭", ["关闭广告","关闭","×","✕"], True, False),
    ("知道了", ["知道了","确定"], True, False),
    # updated 2026-08-17: bare 继续 removed (caused false clicks in Twitter etc.)
    ("继续(倒计时结束)", ["继续观看","继续播放","立即观看","立即播放"], True, True),
]

def find_clickable_parents(n):
    cur = n
    while True:
        if cur.clickable:
            return cur
        par = parents.get(id(cur))
        if par is None:
            return None
        cur = par

print()
print('=== hasPlaybackControls check ===')
has_pc = any(k in pageText or k in compactText for k in PLAYBACK_CONTROL_KEYWORDS) or bool(EPISODE_REGEX.search(compactText))
print('playback controls present:', has_pc)
for k in PLAYBACK_CONTROL_KEYWORDS:
    if k in pageText or k in compactText:
        print('   hit keyword:', k)

print()
print('=== SWIPE_UP check ===')
swipe_hits = [k for k in SWIPE_UP_KEYWORDS if k in pageText or k in compactText]
print('swipe-up keywords hit:', swipe_hits)

print()
print('=== Pangle 立即领取 check (com.phoenix.read only) ===')
pangle = [n for n in nodes if n.pkg == 'com.phoenix.read' and ('立即领取' in n.text or '立即领取' in n.desc)]
print('pangle claim CTA nodes:', len(pangle))

print()
print('=== hasCountdown check ===')
kw_hits = [k for k in COUNTDOWN_KEYWORDS if k in pageText or k in compactText]
rx_hits = [m.group(0) for m in COUNTDOWN_REGEX.finditer(pageText)]
print('countdown keyword hits:', kw_hits)
print('countdown regex hits:', rx_hits)
has_countdown = bool(kw_hits) or bool(rx_hits)
ad_hits = [k for k in AD_CONTEXT_KEYWORDS if k in pageText or k in compactText]
has_ad_context = bool(ad_hits) or has_countdown
print('hasCountdown =', has_countdown, '| hasAdContext =', has_ad_context)

def matches(n, kw):
    return kw in n.text or kw in n.desc

print()
print('=== CLICK_RULES simulation ===')
matched_any = False
for name, texts, req_ctx, req_cd in CLICK_RULES:
    if req_ctx and not has_ad_context:
        continue
    if req_cd and not has_countdown:
        continue
    for n in nodes:
        for kw in texts:
            if kw and matches(n, kw):
                clickable = find_clickable_parents(n)
                if clickable is not None:
                    print('>>> MATCH: rule=%r keyword=%r node_text=%r node_desc=%r' % (name, kw, n.text[:30], n.desc[:30]))
                    print('    clickable target: text=%r desc=%r class=%s bounds=%s' % (clickable.text[:30], clickable.desc[:30], clickable.cls, clickable.bounds))
                    matched_any = True
                break
        if matched_any:
            break
    if matched_any:
        break
if not matched_any:
    print('(current screen does NOT trigger any click rule)')

print()
print('=== nodes containing 继续 ===')
for n in nodes:
    if '继续' in n.text or '继续' in n.desc:
        print(' - text=', n.text[:60], '| desc=', n.desc[:40], '| clickable=', n.clickable, '| bounds=', n.bounds)
