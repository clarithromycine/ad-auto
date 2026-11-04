# -*- coding: utf-8 -*-
"""
分析一份 uiautomator dump（默认 sample/ui_dump.xml，可传路径参数），
输出界面上所有文字/描述节点 + 按 AdRules 的关键字判定结果，
用于排查“广告没能被跳过”的原因（纯分析，不执行动作）。

用法： python analyze_ui.py [ui_now.xml]
"""
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))

path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(_HERE, 'sample', 'ui_dump.xml')
raw = open(path, 'rb').read()
try:
    t = raw.decode('utf-8')
except UnicodeDecodeError:
    t = raw.decode('utf-16')

# 丢掉 uiautomator 在 XML 后面追加的 "UI hierchary dumped to:" 之类的行
end = t.rfind('</hierarchy>')
if end != -1:
    t = t[:end + len('</hierarchy>')]

nodes = []
for m in re.finditer(r'<node[^>]*>', t):
    tag = m.group(0)

    def g(key):
        mm = re.search(key + r'="([^"]*)"', tag)
        return mm.group(1) if mm else ''

    nodes.append(dict(
        text=g('text'), desc=g('content-desc'), cls=g('class'),
        rid=g('resource-id'), pkg=g('package'), bounds=g('bounds'),
        clickable=g('clickable') == 'true', scrollable=g('scrollable') == 'true',
        enabled=g('enabled') == 'true',
    ))

print('dump:', path)
pkgs = sorted({n['pkg'] for n in nodes if n['pkg']})
print('packages:', pkgs)
print('nodes:', len(nodes), ' clickable:', sum(1 for n in nodes if n['clickable']))

print('\n=== 有文字的节点（按屏幕顺序） ===')


def center_y(b):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
    return int(m.group(2)) if m else 0


named = [n for n in nodes if n['text'].strip() or n['desc'].strip()]
named.sort(key=lambda n: (center_y(n['bounds']), n['bounds']))
for n in named:
    print(' %-26s click=%-5s scroll=%-5s cls=%-60s rid=%-46s text=%r desc=%r' % (
        n['bounds'], n['clickable'], n['scrollable'], n['cls'], n['rid'], n['text'], n['desc']))

print('\n=== 可点击节点 ===')
for n in nodes:
    if not n['clickable']:
        continue
    print(' %-26s cls=%-60s rid=%-46s text=%r desc=%r' % (
        n['bounds'], n['cls'], n['rid'], n['text'], n['desc']))

# ---------- 按 AdRules 判定（镜像 Kotlin 规则，仅做提示，不保证完全一致） ----------
page = ' '.join((n['text'] + ' ' + n['desc']) for n in nodes)
compact = re.sub(r'\s+', '', page)

SWIPE_UP = ["上滑继续观看", "上滑继续", "上滑继续看短剧", "上滑继续看", "上滑看短剧",
            "向上滑动继续观看", "上滑解锁", "上滑看下一集"]
PLAYBACK = ["倍速", "选集", "热评", "分享", "评论", "展开", "暂停", "下一集",
            "全集", "已完结", "作者声明", "跟播", "点赞", "收藏", "弹幕"]
COUNTDOWN = ["秒后可继续", "s后可继续", "S后可继续", "秒后继续", "s后继续",
             "后可继续", "后继续观看", "后继续播放", "后可观看", "倒计时", "countdown"]

AD_RENDER_VIEW_ID = 'ttlive_player_render_view'

print('\n=== AdRules 判定 ===')
has_render = any(n['rid'].endswith('/' + AD_RENDER_VIEW_ID) for n in nodes)
print('直播广告渲染视图(%s): %s' % (AD_RENDER_VIEW_ID,
      '有 -> 命中[直播广告(渲染视图)] -> 上滑' if has_render else '无'))
print('上滑提示命中:', [k for k in SWIPE_UP if k in page or k in compact] or '无')
print('正剧控件命中:', [k for k in PLAYBACK if k in page or k in compact] or '无')
print('第X集:', re.findall(r'第\d+集', compact) or '无')
print('倒计时文案命中:', [k for k in COUNTDOWN if k in page or k in compact] or '无')
print('倒计时数字:', re.findall(r'(?:广告|倒计时)\s*(\d+)(?:\s*(?:秒|s|S))?|(\d+)\s*(?:秒|s|S)', page) or '无')
print('广告字样:', [n['text'] for n in named if n['text'].strip() == '广告'] or
      [n['desc'] for n in named if n['desc'].strip() == '广告'] or '无')
print('立即领取:', [n['bounds'] for n in named if '立即领取' in n['text'] or '立即领取' in n['desc']] or '无')

CLICK_RULES = [
    ('跳过广告', ["跳过广告", "跳过此广告", "跳過廣告", "Skip Ad", "SkipAd"], False),
    ('跳过', ["跳过", "跳過", "skip", "Skip"], True),
    ('关闭', ["关闭广告", "关闭", "×", "✕"], True),
    ('知道了', ["知道了", "确定"], True),
    ('继续(倒计时结束)', ["继续观看", "继续播放", "立即观看", "立即播放"], True),
]
has_countdown = bool([k for k in COUNTDOWN if k in page or k in compact]) or \
    bool(re.search(r'\d+\s*(?:秒|s|S)', page))
has_ctx = ('广告' in page) or has_countdown
for name, texts, need_ctx in CLICK_RULES:
    if need_ctx and not has_ctx:
        print('规则 %-16s 跳过（无广告上下文）' % name)
        continue
    hit = [n for n in named if any(k in n['text'] or k in n['desc'] for k in texts)]
    if hit:
        print('规则 %-16s 命中节点: %s' % (name, [(n['bounds'], n['text'] or n['desc'], n['clickable']) for n in hit]))
    else:
        print('规则 %-16s 无命中' % name)
