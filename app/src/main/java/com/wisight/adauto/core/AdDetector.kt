package com.wisight.adauto.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 广告检测器：遍历当前活动窗口的节点树，按 AdRules 匹配并执行跳过动作。
 */
class AdDetector(private val service: AccessibilityService) {

    /** 两次跳过动作之间的最小间隔，避免重复触发 */
    private val minActionInterval = 1500L
    private var lastActionAt = 0L

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!SettingsManager.adSkipEnabled) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // 内容变化事件非常频繁，做节流
        val now = SystemClock.uptimeMillis()
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastActionAt < 800) return

        detectAndAct()
    }

    /** 供“立即检测”按钮调用 */
    fun scanOnce(onResult: (String) -> Unit = {}) {
        detectAndAct(onResult)
    }

    private fun detectAndAct(onResult: (String) -> Unit = {}) {
        val root = service.rootInActiveWindow ?: run {
            onResult("无障碍服务尚未就绪")
            return
        }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        val action = AdRules.match(nodes)
        if (action != null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastActionAt < minActionInterval) {
                recycleAll(nodes)
                return
            }
            lastActionAt = now
            val ok = perform(action)
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
