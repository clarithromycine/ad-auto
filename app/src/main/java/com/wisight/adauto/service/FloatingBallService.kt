package com.wisight.adauto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wisight.adauto.MainActivity
import com.wisight.adauto.R
import com.wisight.adauto.core.AdDetector
import com.wisight.adauto.core.SettingsManager
import kotlin.math.abs

/**
 * 悬浮球前台服务。
 * 在屏幕左上角显示一个可拖动的球形助手。
 * 点击：快捷开关“自动跳过广告”；长按：打开设置页。
 */
class FloatingBallService : Service() {

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "floating_ball"
        private const val TAG = "AdAutoBall"

        @Volatile
        var instance: FloatingBallService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var moved = false
    private var longPressed = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        longPressed = true
        Log.i(TAG, "long-press triggered, opening settings")
        openMainActivity()
        ballView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
            // 通知权限被拒绝等场景，忽略并继续
        }
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ballView == null) showBall()
        return START_STICKY
    }

    override fun onDestroy() {
        removeBall()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBall() {
        if (ballView != null) return
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val size = dp(56)
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(90)
        }

        val view = LayoutInflater.from(this).inflate(R.layout.view_floating_ball, null)
        // 注意：OnTouchListener 对事件返回 true 会跳过 View.onTouchEvent，导致
        // setOnLongClickListener 永不触发；因此长按检测在 buildTouchListener 内手动实现。
        view.setOnTouchListener(buildTouchListener(view, params))

        try {
            windowManager.addView(view, params)
            ballView = view
            layoutParams = params
            updateBallStyle()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun buildTouchListener(view: View, params: WindowManager.LayoutParams) =
        View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    AdDetector.isDragging = false
                    Log.d(TAG, "touch down at ${event.rawX.toInt()},${event.rawY.toInt()}")
                    longPressHandler.postDelayed(
                        longPressRunnable,
                        ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                        // 真正开始拖动：暂停广告检测，保证拖动流畅
                        AdDetector.isDragging = true
                    }
                    // 无论是否超过 slop 都跟随手指移动：避免刚按下拖动时有“死区”延迟
                    // （slop 只用于区分“点击 vs 拖动”，不影响跟手）
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (moved) {
                        // 拖动结束：恢复检测并补扫一次，避免拖动期间漏掉广告
                        AdDetector.isDragging = false
                        AdSkipAccessibilityService.instance?.scanOnce { }
                    }
                    if (!moved && !longPressed) onBallTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    longPressed = false
                    AdDetector.isDragging = false
                    true
                }
                else -> false
            }
        }

    private fun onBallTap() {
        val enabled = !SettingsManager.adSkipEnabled
        SettingsManager.adSkipEnabled = enabled
        Log.i(TAG, "ball tapped, adSkipEnabled -> $enabled")
        updateBallStyle()
        Toast.makeText(
            this,
            if (enabled) R.string.toast_skip_on else R.string.toast_skip_off,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateBallStyle() {
        val view = ballView ?: return
        val enabled = SettingsManager.adSkipEnabled
        view.background = ContextCompat.getDrawable(
            this,
            if (enabled) R.drawable.bg_ball else R.drawable.bg_ball_disabled,
        )
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun removeBall() {
        longPressHandler.removeCallbacksAndMessages(null)
        ballView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) {
            }
        }
        ballView = null
        layoutParams = null
    }

    /**
     * 取消挂起的长按检测（下拉通知栏/控制中心接管触摸时由无障碍服务调用），
     * 避免悬浮球被误判为“长按”而自动打开设置页；同时复位拖动暂停标志，
     * 防止通知栏手势把 isDragging 卡在 true 导致后续不再检测。
     */
    fun cancelPendingLongPress() {
        longPressHandler.removeCallbacks(longPressRunnable)
        longPressed = false
        moved = false
        AdDetector.isDragging = false
        Log.d(TAG, "cancel pending long-press (systemui takeover)")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_ball),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(getString(R.string.notif_ball_title))
            .setContentText(getString(R.string.notif_ball_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
