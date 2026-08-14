package com.wisight.adauto.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.wisight.adauto.core.AdDetector
import com.wisight.adauto.core.SettingsManager

/**
 * 无障碍服务。
 * 监听界面变化 -> AdDetector 检测广告 -> 自动执行跳过操作。
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AdSkipAccessibilityService? = null
            private set
    }

    private var detector: AdDetector? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        detector = AdDetector(this)
        // 无障碍已开启且悬浮球开关打开时，自动拉起悬浮球
        if (SettingsManager.ballEnabled) {
            try {
                FloatingBallService.start(this)
            } catch (_: Throwable) {
                // 后台启动被限制时忽略，用户可在设置页手动开启
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // 下拉通知栏/控制中心时，触摸可能被系统（com.android.systemui）接管，导致悬浮球收不到
        // UP/CANCEL，其 500ms 长按定时器会误触发而自动打开设置页。检测到系统界面窗口切换时，
        // 主动取消悬浮球挂起的长按。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == "com.android.systemui"
        ) {
            FloatingBallService.instance?.cancelPendingLongPress()
        }
        detector?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 供“立即检测”按钮调用 */
    fun scanOnce(callback: (String) -> Unit) {
        detector?.scanOnce(callback) ?: callback("无障碍服务尚未就绪")
    }
}
