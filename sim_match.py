# -*- coding: utf-8 -*-
"""
Replicate AdRules.match() / AdDetector logic against a uiautomator dump
(package name in <hierarchy package="...">) to prove WHY a false positive
happens (no execution, pure analysis).

Includes the AdDetector scope gate added 2026-08-19: unless generic mode is
on, AdAuto only acts inside the supported short-drama/video packages
(default com.phoenix.read) -- see SettingsManager.supportedPackages.
"""
import re, os, sys
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

# ---------- AdDetector scope gate (2026-08-19) ----------
# 可用环境变量模拟用户配置：GENERIC_MODE=1 等价于开启“通用模式”（所有 App 都检测）
GENERIC_MODE = os.environ.get('GENERIC_MODE') == '1'  # mirror SettingsManager.genericModeEnabled
SUPPORTED_PACKAGES = {"com.phoenix.read"}  # mirror SettingsManager.supportedPackagesList()
root_el = ET.fromstring(xml)
# uiautomator puts package on the first <node>, not on <hierarchy>
def first_pkg(el):
    p = el.get('package', '') or ''
    if p:
        return p
    for c in el:
        return first_pkg(c)
    return ''
dump_pkg = first_pkg(root_el)
print('dump package:', dump_pkg or '(none)')
if not GENERIC_MODE and dump_pkg not in SUPPORTED_PACKAGES:
    print('>>> GATED: current App (%s) is not in supported packages -> AdAuto does NOT act here' % dump_pkg)
    sys.exit(0)
print('>>> in scope (generic mode OR supported package), continuing analysis')

# 2026-10-23: 红果专属判据只在 com.phoenix.read 上生效（稀疏树在别的 App 会误伤，如读书软件）
HONGUO_PACKAGE = 'com.phoenix.read'
IS_HONGUO = (dump_pkg == HONGUO_PACKAGE)

class Node:
    __slots__ = ('text', 'desc', 'cls', 'clickable', 'pkg', 'bounds', 'rid', 'children')
    def __init__(self, text, desc, cls, clickable, pkg, bounds, rid, children):
        self.text, self.desc, self.cls = text, desc, cls
        self.clickable, self.pkg, self.bounds = clickable, pkg, bounds
        self.rid = rid
        self.children = children

def conv(el):
    return Node(
        text=el.get('text', '') or '', desc=el.get('content-desc', '') or '',
        cls=el.get('class', '') or '',
        clickable=(el.get('clickable') == 'true'),
        pkg=el.get('package', '') or '', bounds=el.get('bounds', '') or '',
        rid=el.get('resource-id', '') or '',
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
            # 镜像 AccessibilityNodeInfo.isVisibleToUser：跳过零面积 / 上下颠倒的框
            # （红果广告里有一堆 [215,2038][276,1078] 这种帧，App 侧会被过滤掉）
            if x2 > x1 and y2 > y1:
                # 镜像 AdDetector.collectNodes：text / desc / resource-id / TextureView 四选一
                if n.text.strip() or n.desc.strip() or n.rid.strip() or 'TextureView' in n.cls:
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
# 2026-10-08: 显式“滑动锁”文案（5s后可继续上滑观看）
COUNTDOWN_KEYWORDS += ["后可继续上滑","后可上滑","秒后可上滑","s后可上滑"]
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

# ---------- 2026-09-18 直播样式广告：具名渲染视图 ----------
AD_RENDER_VIEW_ID = 'ttlive_player_render_view'
has_render_view = ('/' + AD_RENDER_VIEW_ID) in xml

# ---------- 2026-10-23 无 id 的 TextureView 渲染视图（试玩/游戏广告） ----------
# 正剧始终走 android.view.SurfaceView，不会出现 TextureView（实测对照）。
AD_RENDER_VIEW_CLASS = 'TextureView'
has_render_surface = any(AD_RENDER_VIEW_CLASS in n.cls for n in nodes)

# ---------- 2026-10-14 穿山甲图文/商城卡片广告 ----------
# 只用 banner（商城卡片固定文案）；“右边缘领取 CTA”已实测被推翻：
# 正剧状态下也有一个 text="领取"、bounds [1137,901][1179,989]、祖先
# ViewGroup [CLICKABLE] [1068,819][1200,1071] 的节点，与广告里完全一致。
SHOP_CARD_BANNER = '好物省心选'
MAX_SHOP_CARD_TEXT_NODES = 6

def is_shop_card_ad(ns):
    text_nodes = [n for n in ns if n.text.strip() or n.desc.strip()]
    if not text_nodes or len(text_nodes) > MAX_SHOP_CARD_TEXT_NODES:
        return False
    return SHOP_CARD_BANNER in compactText

# ---------- 2026-10-23 广告 feed item 容器（ay0 + i0t） ----------
# 实测三种广告形态都有、正剧没有（正剧时这两个容器为 GONE）。
AD_ITEM_CONTAINER_IDS = ['ay0', 'i0t']
_all_rids = re.findall(r'resource-id="([^"]*)"', xml)
has_ad_item = all(any(r.endswith('/' + i) for r in _all_rids) for i in AD_ITEM_CONTAINER_IDS)

shop_card = is_shop_card_ad(nodes)
print()
print('=== 直播广告渲染视图 / 商城卡片 check ===')
print('isHonguo(%s) = %s  <-- 红果专属判据闸门' % (HONGUO_PACKAGE, IS_HONGUO))
print('hasAdRenderView(ttlive_player_render_view) =', has_render_view)
print('hasAdRenderSurface(TextureView) =', has_render_surface)
print('hasAdItemContainer(ay0+i0t) =', has_ad_item)
print('isShopCardAd =', shop_card, '| text nodes = %d (limit %d)' % (len(nodes), MAX_SHOP_CARD_TEXT_NODES))

print()
print('=== hasPlaybackControls check ===')
has_pc = any(k in pageText or k in compactText for k in PLAYBACK_CONTROL_KEYWORDS) or bool(EPISODE_REGEX.search(compactText))
print('playback controls present:', has_pc)
for k in PLAYBACK_CONTROL_KEYWORDS:
    if k in pageText or k in compactText:
        print('   hit keyword:', k)

# ---------- 2026-10-23 名称无关兜底：稀疏树（仅竖屏） ----------
# 实测：正剧 nodes 88~90 / textLen 108~124；广告 nodes 20~24 / textLen 0~15。
# 横屏正剧实测 nodes=22 textLen=0，与广告撞车 -> 仅竖屏生效。
SPARSE_AD_MAX_NODES = 40
SPARSE_AD_MAX_TEXT_LEN = 30
_m = re.search(r'bounds="\[0,0\]\[(\d+),(\d+)\]"', xml)
SCREEN_W, SCREEN_H = (int(_m.group(1)), int(_m.group(2))) if _m else (0, 0)
looks_sparse = (
    SCREEN_W > 0 and SCREEN_H > 0 and SCREEN_W < SCREEN_H
    and len(nodes) <= SPARSE_AD_MAX_NODES
    and len(pageText) <= SPARSE_AD_MAX_TEXT_LEN
    and not has_pc
)
print('looksLikeSparseAdItem =', looks_sparse,
      '| screen=%dx%d nodes=%d textLen=%d' % (SCREEN_W, SCREEN_H, len(nodes), len(pageText)))

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

# ---------- 2026-10-23 倒计时广告：优先点“跳过/关闭”（对应 AdRules.match 的 0.5 分支） ----------
UPCOMING_AD_KEYWORDS = ["进入广告", "播放广告", "广告即将", "即将播放广告", "即将进入广告",
                       "后进入广告", "后播放广告", "开始播放广告", "即将开始播放", "即将开始广告"]
has_upcoming_ad = any(k in pageText or k in compactText for k in UPCOMING_AD_KEYWORDS)
countdown_click = None
if has_countdown and not has_upcoming_ad and not has_pc:
    for _name, _texts, _req_ctx, _req_cd in CLICK_RULES:
        if _req_cd:
            continue
        if _req_ctx and not has_ad_context:
            continue
        for _n in nodes:
            for _kw in _texts:
                if _kw and (_kw in _n.text or _kw in _n.desc):
                    _c = find_clickable_parents(_n)
                    if _c is not None:
                        countdown_click = (_name, _n, _c)
                        break
            if countdown_click:
                break
        if countdown_click:
            break
print()
print('=== 倒计时广告优先点跳过/关闭 ===')
print('countdown skip-click =', '(none)' if countdown_click is None
      else '%r on %s' % (countdown_click[0], countdown_click[2].bounds))

print()
print('=== FINAL DECISION (order: countdown-skip-click > swipe-up > playback-protection > render-view > shop-card > click rules) ===')
if countdown_click is not None:
    print('>>> ACTION: CLICK (%s(倒计时中优先)) node_text=%r' % (countdown_click[0], countdown_click[1].text[:20]))
elif swipe_hits:
    print('>>> ACTION: SWIPE_UP (上滑继续观看) — explicit swipe-up prompt, overrides playback protection')
elif has_pc:
    print('>>> ACTION: none — playback controls present (正剧保护)')
elif IS_HONGUO and shop_card:
    print('>>> ACTION: SWIPE_UP (穿山甲广告(商城卡片))')
elif IS_HONGUO and has_render_view:
    print('>>> ACTION: SWIPE_UP (直播广告(渲染视图))')
elif IS_HONGUO and has_render_surface:
    print('>>> ACTION: SWIPE_UP (广告(渲染视图TextureView))')
elif IS_HONGUO and has_ad_item:
    print('>>> ACTION: SWIPE_UP (广告(广告布局容器))')
elif shop_card:
    print('>>> ACTION: SWIPE_UP (穿山甲广告(商城卡片))')
else:
    print('>>> no swipe-up prompt and no playback protection -> checking click rules')
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
        if IS_HONGUO and looks_sparse:
            print('>>> ACTION: SWIPE_UP (广告(稀疏树)) [需 800ms 持久性确认]')

print()
print('=== nodes containing 继续 ===')
for n in nodes:
    if '继续' in n.text or '继续' in n.desc:
        print(' - text=', n.text[:60], '| desc=', n.desc[:40], '| clickable=', n.clickable, '| bounds=', n.bounds)
