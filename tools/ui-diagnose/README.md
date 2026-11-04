# 界面诊断脚本

一组排查「**为什么这条广告没跳过**」用的小脚本。

它们只做**取证与分析** —— 不点屏幕、不划屏、不改 App 的规则。
所有判断逻辑都是从 App 里照搬过来的，所以结论可以直接对上 App 的行为。

## 前提

- 电脑有 Python 3（`orange_probe.py` / `ad_audit.py` 需要 `pillow`：`pip install pillow`）
- 手机连 USB 并已授权调试（`adb devices` 能看到设备）
- 电脑上 `adb` 在 PATH 里

## 标准排查流程

1. **复现**：让那条跳不过的广告**停在屏幕上**（别划走、别退出）。
2. **抓取**
   ```bash
   python grab.py ad1
   ```
   产出 `cap/grab_ad1.png` + `cap/grab_ad1.xml`，同时打印结构指纹
   （是否含 TextureView / SurfaceView、文字有哪些、可点击节点数）。
3. **看规则怎么判的**
   ```bash
   python analyze_ui.py cap/grab_ad1.xml
   ```
4. **看是哪条规则命中/没命中**
   ```bash
   python sim_match.py cap/grab_ad1.xml
   ```
   它复刻了 `AdRules.match()` 的完整分支顺序与包名闸门 —— 能直接回答
   "为什么这条没被认出来"或"为什么它误判了"。
5. 需要更多细节时再看结构：
   ```bash
   python dump_tree.py cap/grab_ad1.xml     # 完整节点树（缩进）
   python dump_rid.py  cap/grab_ad1.xml     # 所有带 resource-id 的节点
   ```
6. 想找"广告 vs 正剧"的区别特征：各抓一份再对比
   ```bash
   python compare_ui.py cap/grab_ad1.xml cap/grab_drama.xml
   ```

## 脚本一览

| 脚本 | 干什么 | 什么时候用 |
| --- | --- | --- |
| `grab.py` | 抓当前界面（截图 + 无障碍 dump，失败自动重试），打印结构指纹 | **每次都先用它**，拿到证据再看别的 |
| `analyze_ui.py` | 按 App 的关键字规则分析一份 dump，输出判定结果 | 快速确认"规则认不认这条广告" |
| `sim_match.py` | 复刻 `AdRules.match()` 全流程（含包名闸门、分支顺序） | 定位是哪条规则命中/漏掉、为什么误判 |
| `dump_tree.py` | 打印完整节点树（缩进、class/id/bounds/clickable/scrollable/text） | 树里没文字、只能靠结构判断时 |
| `dump_rid.py` | 列出所有带 `resource-id` 的节点并标注 clickable / 可见 | 找"具名 id"类特征 |
| `compare_ui.py` | 多份 dump 对比：节点数、id 集合、类名直方图、两两差异 | 找广告页与正剧页的区分点 |
| `series_scan.py` | 按固定间隔连续抓取，打印每帧指纹 | 广告一闪而过、单帧抓不准时 |
| `ad_audit.py` | 独立抓屏审计：不依赖 App 判定，检查是否**漏跳** | 怀疑"App 明明该跳却没跳" |
| `orange_probe.py` | 复现横屏「橙色按钮」判据（形状 A/B + 颜色纯度 + 近似矩形） | 横屏广告跳不过时，看按钮为什么没被认出来 |

## sample/ui_dump.xml

红果正剧页的一份真实 dump。脚本不传路径参数时默认用它，
方便在**没连设备**的情况下先验证脚本能跑通：

```bash
python analyze_ui.py
```

## 输出位置

抓取产物都在本目录下的 `cap/`（已加入 `.gitignore`，不会进仓库）。
