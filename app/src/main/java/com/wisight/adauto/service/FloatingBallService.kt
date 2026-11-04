package com.wisight.adauto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wisight.adauto.MainActivity
import com.wisight.adauto.R
import com.wisight.adauto.core.AdDetector
import com.wisight.adauto.core.DebugTrace
import com.wisight.adauto.core.SettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        /** 调试浮层刷新间隔（毫秒）：只是读一个字符串，成本极低 */
        private const val DEBUG_TICK_MS = 500L

        /** 从判定快照里取目标 App 包名（`AdDetector` 写的 `fg=...` 那一行） */
        private val TARGET_PKG_REGEX = Regex("""fg=([A-Za-z0-9_.]+)""")

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

    /** 调试浮层（设置页开关控制）。不参与任何判定，详见 showDebugOverlay 的注释 */
    private var debugRoot: View? = null
    private var debugText: TextView? = null
    private var debugHeader: TextView? = null
    private val debugHandler = Handler(Looper.getMainLooper())
    private val debugTick = object : Runnable {
        override fun run() {
            updateDebugText()
            debugHandler.postDelayed(this, DEBUG_TICK_MS)
        }
    }

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
        // 设置页改了“显示悬浮球/自动跳过”后再次进来：刷新球体外观（可见 / 透明）
        updateBallStyle()
        // 调试浮层跟随设置状态（开关即时生效）
        refreshDebugOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        hideDebugOverlay()
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

    /**
     * 按当前设置刷新球体外观。
     *
     * “隐藏悬浮球”**不是移除窗口，而是把球体变成完全透明**（alpha=0，且不接收触摸）：
     * 窗口仍然挂在 WindowManager 上 → 前台服务不用停 → App 不会被系统在后台挂起。
     * 实测（MIUI/HyperOS）：真把服务停掉后，App 切到后台约 20 秒就收不到无障碍事件，
     * 广告就不会被跳过；而那神“假活”用 dumpsys 看不出来（进程在、无障碍仍显示已绑定）。
     */
    private fun updateBallStyle() {
        val view = ballView ?: return
        val params = layoutParams ?: return
        if (SettingsManager.ballEnabled) {
            // 显示：恢复正常不透明度与背景，并恢复可触摸
            view.alpha = 1f
            view.background = ContextCompat.getDrawable(
                this,
                if (SettingsManager.adSkipEnabled) R.drawable.bg_ball else R.drawable.bg_ball_disabled,
            )
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            // 隐藏：完全透明（等同“一张透明图片”），并且不能拦截触摸，
            // 否则左上角那片区域会点不动、也拖不了
            view.alpha = 0f
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // ==================== 调试浮层 ====================

    /**
     * 设置页切换“调试详情浮层”后调用（也可由 [onStartCommand] 触发）。
     */
    fun refreshDebugOverlay() {
        if (SettingsManager.debugOverlayEnabled) showDebugOverlay() else hideDebugOverlay()
    }

    /**
     * 显示调试浮层：实时打印“当前界面是怎么判定的”，并带一个「复制」按钮。
     *
     * **为什么它不会影响判定**（这是本功能最关键的设计点）：
     * 1. `importantForAccessibility = NO_HIDE_DESCENDANTS` —— 整个窗口（包括复制按钮）
     *    **完全不进入无障碍树**，任何 `AccessibilityService`（包括我们自己）都读不到它的文字/节点；
     * 2. 仍是 `FLAG_NOT_FOCUSABLE` —— 不抢焦点，不会成为 `mCurrentFocus`/活动窗口，
     *    因此完全不影响 `foregroundPackage()` 的包名判断；
     * 3. 即便前两条都失效，检测侧仍会把 `com.wisight.adauto` 的窗口从节点采集里剔除
     *    （`NON_AD_PACKAGES`），并且它的文字里也不含“跳过/广告”这类会被点击规则命中的词。
     *
     * ⚠️ 为了能点「复制」，窗口**不再是 `FLAG_NOT_TOUCHABLE`**（否则按不到按钮）。
     * 代价是浮层所占的那一小块区域（右上角 260dp 宽）不会再把手势传给下面的 App。
     * 这是"可交互"与"零触摸干扰"之间必须做的取舍：只用调试时才会出现，且不抢焦点、
     * 不影响任何判定逻辑。
     */
    private fun showDebugOverlay() {
        if (debugRoot != null) return
        if (!Settings.canDrawOverlays(this)) return

        val chipBg = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(0x33FFFFFF)
            setStroke(dp(1), 0x66FFFFFF)
        }
        val copyBtn = TextView(this).apply {
            text = "复制"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = chipBg
            isClickable = true
            setOnClickListener { copyDebugText() }
        }
        val header = TextView(this).apply {
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 10f
            setPadding(dp(6), 0, 0, 0)
            text = "调试浮层"
            typeface = Typeface.MONOSPACE
        }
        val detail = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            setPadding(0, dp(4), 0, 0)
            typeface = Typeface.MONOSPACE
            maxLines = 14
            ellipsize = TextUtils.TruncateAt.END
            text = "等待检测…"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xCC000000.toInt())
                setStroke(dp(1), 0x66FFFFFF)
            }
            addView(
                LinearLayout(this@FloatingBallService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(copyBtn)
                    addView(header)
                },
            )
            addView(detail)
            // 整个浮层都可点：点任意位置即复制（按钮太小容易点不准，且调试时不想擕）
            isClickable = true
            setOnClickListener { copyDebugText() }
            // 隔离①：整个浮层（含按钮）对无障碍完全隐身
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val params = WindowManager.LayoutParams(
            dp(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 隔离②：不抢焦点（可触摸是为了点「复制」）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(90)
            title = "AdAutoDebugOverlay"
        }
        try {
            windowManager.addView(root, params)
            debugRoot = root
            debugText = detail
            debugHeader = header
            debugHandler.removeCallbacks(debugTick)
            debugHandler.post(debugTick)
            Log.i(TAG, "debug overlay shown")
        } catch (t: Throwable) {
            Log.w(TAG, "debug overlay addView failed: $t")
            debugRoot = null
            debugText = null
            debugHeader = null
        }
    }

    private fun hideDebugOverlay() {
        debugHandler.removeCallbacks(debugTick)
        val v = debugRoot ?: return
        try {
            windowManager.removeView(v)
        } catch (_: Throwable) {
        }
        debugRoot = null
        debugText = null
        debugHeader = null
        DebugTrace.clear()
        Log.i(TAG, "debug overlay hidden")
    }

    /**
     * 把完整判定详情复制到剪贴板（复制的是完整文本，不是屏幕上被截断的那份）。
     *
     * 复制内容 = 判定快照 + **环境信息**。环境块只在这里拼，浮层上显示的字一个字都没变：
     * 用户把这段贴给开发者，开发者才知道“哪个版本、哪台机器、哪个分辨率、目标 App 什么版本”。
     * 全部信息都来自本机 `Build` / `PackageManager` / 判定快照，**没有任何网络请求**。
     */
    private fun copyDebugText() {
        val detail = DebugTrace.detail
        if (detail.isEmpty()) {
            Toast.makeText(this, "暂无调试信息", Toast.LENGTH_SHORT).show()
            return
        }
        val text = "#${DebugTrace.seq}\n$detail" + buildEnvBlock()
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ad-auto debug", text))
            Toast.makeText(this, "调试信息已复制", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "debug detail copied, ${text.length} chars")
        } catch (t: Throwable) {
            Log.w(TAG, "copy failed: $t")
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 上报用的环境信息块（只在「复制」时生成）。
     *
     * 刻意保持**纯文本 key=value**，方便开发者肉眼读、也方便以后脚本解析：
     * - `app`     本 App 版本（版本号 + versionCode）
     * - `device`  厂商 + 机型
     * - `android` 系统版本 / SDK / ROM 版本号
     * - `screen`  逻辑分辨率 / dpi / 横竖屏
     * - `target`  判定时前台 App 的包名与版本（取不到就省略）
     * - `time`    复制时刻（本地时间）
     *
     * 不含：页面文字、截图、已安装应用列表、设备标识（IMEI/OAID/ANDROID_ID）。
     */
    private fun buildEnvBlock(): String {
        val sb = StringBuilder()
        sb.append("\n———— 环境信息（仅你主动复制时生成，App 不会后台上传）————\n")
        sb.append("app=").append(appVersionText()).append('\n')
        sb.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("android=").append(Build.VERSION.RELEASE)
            .append(" SDK=").append(Build.VERSION.SDK_INT)
            .append('\n')
        val inc = Build.VERSION.INCREMENTAL
        val disp = Build.DISPLAY
        sb.append("rom=").append(
            if (disp.isNullOrEmpty() || disp == inc) inc else "$inc ($disp)",
        ).append('\n')
        // 屏幕用**真实物理分辨率**（`resources.displayMetrics` 会扣掉状态栏/导航栏，
        // 报上来的是 1200x2394 这种“可用区域”，排查时分不清到底是不是这个型号）
        val (sw, sh) = realScreenSize()
        sb.append("screen=").append(sw).append('x').append(sh)
            .append(" dpi=").append(resources.displayMetrics.densityDpi)
            .append(' ').append(if (sw > sh) "横屏" else "竖屏")
            .append('\n')
        val target = TARGET_PKG_REGEX.find(DebugTrace.detail)?.groupValues?.getOrNull(1)
        if (!target.isNullOrEmpty()) {
            sb.append("target=").append(target)
            pkgVersionName(target)?.let { sb.append(" ver=").append(it) }
            sb.append('\n')
        }
        sb.append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        return sb.toString()
    }

    /** 本 App 的 `版本名(versionCode)` */
    private fun appVersionText(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        "${pi.versionName ?: "?"}($code)"
    } catch (t: Throwable) {
        Log.w(TAG, "read app version failed: $t")
        "?"
    }

    /** 目标 App 的版本名；未安装/读不到时返回 null（按普通做法静默省略） */
    private fun pkgVersionName(pkg: String): String? = try {
        packageManager.getPackageInfo(pkg, 0).versionName
    } catch (t: Throwable) {
        null
    }

    /** 屏幕真实分辨率（含状态栏/导航栏占用的那部分，可直接与 `wm size` 对照） */
    private fun realScreenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun updateDebugText() {
        val tv = debugText ?: return
        val detail = DebugTrace.detail
        val autoSkip = if (SettingsManager.adSkipEnabled) "开" else "关"
        if (detail.isEmpty()) {
            tv.text = "等待检测…（自动跳过=$autoSkip）"
            debugHeader?.text = "调试浮层"
            return
        }
        val age = if (DebugTrace.updatedAt == 0L) -1L else SystemClock.uptimeMillis() - DebugTrace.updatedAt
        tv.text = detail
        debugHeader?.text = "调试浮层  #${DebugTrace.seq} ${age}ms前"
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
