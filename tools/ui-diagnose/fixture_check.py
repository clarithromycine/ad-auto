#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""像素判据语料的 Python 侧回归检查。

语料与 Kotlin 单测共用同一批 PNG（app/src/test/resources/fixtures），
文件名约定 `<criterion>_<slug>_<hit|miss>.png`。

目前 Python 侧只镜像了**橙色**判据（`orange_probe.py`），所以这里只校验 orange_*。
hint_* / pillar_* 只有 Kotlin 实现 —— 有意不再复制第二份，避免继续扩大双实现漂移面。

用法:
    python3 fixture_check.py        # 或 python3 orange_probe.py --check
退出码: 0 = 全部一致；1 = 有分歧（说明 Kotlin 与 Python 已经漂移）
"""
import glob
import os
import sys

from orange_probe import analyze_path

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE_DIR = os.path.normpath(os.path.join(HERE, '..', '..', 'app', 'src', 'test', 'resources', 'fixtures'))


def main():
    files = sorted(glob.glob(os.path.join(FIXTURE_DIR, 'orange_*.png')))
    if not files:
        print('未找到语料: %s' % FIXTURE_DIR)
        return 1
    failed = 0
    print('orange 语料 %d 份（%s）' % (len(files), FIXTURE_DIR))
    for path in files:
        name = os.path.basename(path)
        if name.endswith('_hit.png'):
            expected = True
        elif name.endswith('_miss.png'):
            expected = False
        else:
            print('  ? %-28s 文件名必须以 _hit / _miss 结尾' % name)
            failed += 1
            continue
        r = analyze_path(path)
        ok = r['hit'] == expected
        if not ok:
            failed += 1
        detail = ''
        who = r['bar'] or r['cta']
        if who:
            detail = ' 形态%s %dx%d 填充 %.2f 行跨 %.2f 列跨 %.2f' % (
                'A' if r['bar'] else 'B', who['bw'], who['bh'],
                who['fill'], who['rowc'], who['colc'])
        print('  %s %-28s 期望 %-4s 实际 %-4s%s' % (
            'ok  ' if ok else 'FAIL', name,
            'hit' if expected else 'miss', 'hit' if r['hit'] else 'miss', detail))
    skipped = [os.path.basename(p) for p in
               sorted(glob.glob(os.path.join(FIXTURE_DIR, '*.png')))
               if not os.path.basename(p).startswith('orange_')]
    if skipped:
        print('\n(Python 未镜像、仅由 Kotlin 单测覆盖: %s)' % ', '.join(skipped))
    print('\n%s' % ('全部一致' if failed == 0 else '%d 份与 Kotlin 不一致' % failed))
    return 0 if failed == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
