package com.wisight.adauto.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 广告检测器：遍历当前屏幕上各窗口的节点树，按 AdRules 匹配并执行跳过动作。
 */
class AdDetector(private val service: AccessibilityService) {

    private companion object {
        const val TAG = "AdDetector"
    }
    /** 两次跳过动作之间的最小间隔，避免重复触发 */
    private val minActionInterval = 1500L
    /**
     * 穿山甲广告（"立即领取"触发）的冷却时间：
     * 广告被滑走后"立即领取"按钮可能短暂残留，若用默认 1500ms 会重复滑动，
     * 这里给更长的冷却以留出广告消失/界面切换的时间。
     */
    private val pangleAdCooldown = 6000L
    /** 下次允许执行动作的时间戳 */
    private var nextAllowedAt = 0L
    private var lastActionAt = 0L

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!SettingsManager.adSkipEnabled) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(TAG, "自动跳过已关闭，界面变化不检测")
            }
            return
        }
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // 窗口切换时打印窗口结构，用于排查“穿山甲广告窗口”的可检测特征
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            logWindows()
        }

        // 内容变化事件非常频繁，做节流
        val now = SystemClock.uptimeMillis()
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastActionAt < 800) return

        detectAndAct()
    }

    /** 打印当前所有无障碍窗口的结构，排查广告窗口特征 */
    private fun logWindows() {
        try {
            val wins = service.windows
            val sb = StringBuilder("windows[${wins.size}]:")
            for (w in wins) {
                val root = w.root
                val pkg = root?.packageName?.toString().orEmpty()
                val cls = root?.className?.toString().orEmpty()
                sb.append(" {t=${w.type},a=${w.isActive},pkg=$pkg,cls=$cls}")
            }
            Log.i(TAG, sb.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "logWindows failed: $t")
        }
    }

    /** 供“立即检测”按钮调用 */
    fun scanOnce(onResult: (String) -> Unit = {}) {
        detectAndAct(onResult)
    }

    private fun detectAndAct(onResult: (String) -> Unit = {}) {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectFromAllWindows(nodes)
        if (nodes.isEmpty()) {
            onResult("无障碍服务尚未就绪")
            return
        }

        val action = AdRules.match(nodes)
        if (action != null) {
            val now = SystemClock.uptimeMillis()
            if (now < nextAllowedAt) {
                recycleAll(nodes)
                return
            }
            // 穿山甲广告（"立即领取"触发）用更长冷却：广告滑走后按钮可能残留，避免重复滑动
            val cooldown = if (action.reason.contains("穿山甲广告")) pangleAdCooldown else minActionInterval
            nextAllowedAt = now + cooldown
            lastActionAt = now
            Log.i(TAG, "匹配到广告: ${action.type} (${action.reason})")
            // 打印匹配节点文本，便于排查（注意：穿山甲 SurfaceView 视频广告的文字不在无障碍树里）
            action.node?.let { n ->
                Log.i(TAG, "匹配节点 text=${n.text?.toString().orEmpty().take(20)} class=${n.className}")
            }
            val ok = perform(action)
            Log.i(TAG, "执行${if (ok) "成功" else "失败"}: ${action.type} (${action.reason})")
            onResult(if (ok) "检测到广告，已自动跳过（${action.reason}）" else "跳过动作执行失败")
        } else {
            onResult("当前界面未检测到广告")
        }
        recycleAll(nodes)
    }

    private fun perform(action: AdAction): Boolean {
        return when (action.type) {
            AdActionType.CLICK -> {
                val node = action.node ?: return false
                try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Throwable) {
                    false
                }
            }
            AdActionType.SWIPE_UP -> swipe(up = true)
            AdActionType.SWIPE_DOWN -> swipe(up = false)
            AdActionType.SWIPE_LEFT -> swipe(horizontal = true, toRight = false)
            AdActionType.SWIPE_RIGHT -> swipe(horizontal = true, toRight = true)
            AdActionType.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun swipe(up: Boolean = true, horizontal: Boolean = false, toRight: Boolean = true): Boolean {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val path = Path()
        if (horizontal) {
            val fromX = if (toRight) w * 0.2f else w * 0.8f
            val toX = if (toRight) w * 0.8f else w * 0.2f
            path.moveTo(fromX, h * 0.5f)
            path.lineTo(toX, h * 0.5f)
        } else {
            val fromY = if (up) h * 0.75f else h * 0.25f
            val toY = if (up) h * 0.25f else h * 0.75f
            path.moveTo(w * 0.5f, fromY)
            path.lineTo(w * 0.5f, toY)
            Log.i(TAG, "swipe: display=${w}x$h path=(${w * 0.5f},$fromY)->(${w * 0.5f},$toY)")
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun collectNodes(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            out.add(node)
            for (i in 0 until node.childCount) {
                stack.add(node.getChild(i) ?: continue)
            }
        }
    }

    /**
     * 收集屏幕上所有交互窗口（应用窗口 + 系统弹窗/悬浮广告等）的节点。
     * 广告文案常出现在非活动窗口（弹窗/WebView 覆盖层），仅用
     * rootInActiveWindow 会漏检，这里全部收集以提高召回。
     */
    private fun collectFromAllWindows(out: MutableList<AccessibilityNodeInfo>) {
        val windows = try {
            service.windows
        } catch (_: Throwable) {
            emptyList()
        }
        for (win in windows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                win.type != AccessibilityWindowInfo.TYPE_SYSTEM
            ) continue
            val root = win.root ?: continue
            collectNodes(root, out)
        }
        // 兜底：某些设备上 windows 列表为空时退回活动窗口
        if (out.isEmpty()) {
            service.rootInActiveWindow?.let { collectNodes(it, out) }
        }
    }

    @Suppress("DEPRECATION") // recycle() 对旧系统仍必要，API 33+ 上为 no-op
    private fun recycleAll(nodes: List<AccessibilityNodeInfo>) {
        for (n in nodes) {
            try {
                n.recycle()
            } catch (_: Throwable) {
            }
        }
    }
}
