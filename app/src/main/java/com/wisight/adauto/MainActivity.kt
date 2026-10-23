package com.wisight.adauto

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wisight.adauto.core.SettingsManager
import com.wisight.adauto.databinding.ActivityMainBinding
import com.wisight.adauto.service.AdSkipAccessibilityService
import com.wisight.adauto.service.FloatingBallService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        binding.etCustomKeywords.setText(SettingsManager.customKeywords)
        binding.etSupportedPackages.setText(SettingsManager.supportedPackages)
        // 开关语义：开 = 仅检测下方 App（与文案一致）；关 = 检测所有 App（通用模式）。
        // 之前是直接映射 genericModeEnabled，导致“打开推荐开关”反而变成全 App 检测。
        binding.switchGenericMode.isChecked = !SettingsManager.genericModeEnabled
        updateScopeStatus()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        updateScopeStatus()
    }

    /** 刷新“自动跳过范围”的实时提示：直接显示出当前生效范围 / 待保存状态，避免误读开关含义 */
    private fun updateScopeStatus() {
        val limitToPackages = binding.switchGenericMode.isChecked
        val savedLimit = !SettingsManager.genericModeEnabled
        val text: String
        val color: Int
        when {
            limitToPackages != savedLimit -> {
                text = getString(R.string.rule_scope_status_pending)
                color = ContextCompat.getColor(this, R.color.text_secondary)
            }
            limitToPackages -> {
                text = getString(R.string.rule_scope_status_limited)
                color = ContextCompat.getColor(this, R.color.status_green)
            }
            else -> {
                text = getString(R.string.rule_scope_status_generic)
                color = ContextCompat.getColor(this, R.color.status_red)
            }
        }
        binding.tvScopeStatus.text = text
        binding.tvScopeStatus.setTextColor(color)
    }

    private fun setupListeners() {
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOverlay.setOnClickListener {
            openOverlaySettings()
        }
        binding.btnNotification.setOnClickListener {
            openNotificationSettings()
        }
        binding.switchSkip.setOnCheckedChangeListener { _, checked ->
            SettingsManager.adSkipEnabled = checked
            binding.switchSkip.isChecked = checked
            // 两个开关都关闭时才真正停掉服务（悬浮球“隐藏”时服务要继续留着，见下）
            if (!checked && !SettingsManager.ballEnabled) FloatingBallService.stop(this)
        }
        binding.switchBall.setOnCheckedChangeListener { _, checked ->
            SettingsManager.ballEnabled = checked
            // “关闭悬浮球”不是移除窗口，而是把球体变成完全透明（见 FloatingBallService.updateBallStyle）：
            // 窗口保留 → 前台服务不必停 → App 不会被 MIUI/HyperOS 在后台挂起（否则就不会跳广告了）。
            if (checked) startBall() else FloatingBallService.start(this)
        }
        // “自动跳过范围”开关只改 UI 状态提示，真正写入要等点“保存规则”（与包名输入框同组）
        binding.switchGenericMode.setOnCheckedChangeListener { _, _ -> updateScopeStatus() }
        binding.btnSaveRules.setOnClickListener {
            SettingsManager.customKeywords = binding.etCustomKeywords.text?.toString()?.trim().orEmpty()
            SettingsManager.supportedPackages = binding.etSupportedPackages.text?.toString()?.trim().orEmpty()
            // 开关语义：开 = 仅检测下方 App → genericModeEnabled = 取反
            SettingsManager.genericModeEnabled = !binding.switchGenericMode.isChecked
            updateScopeStatus()
            Toast.makeText(this, R.string.toast_rule_saved, Toast.LENGTH_SHORT).show()
        }
        binding.btnScanNow.setOnClickListener {
            scanNow()
        }
    }

    private fun scanNow() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.toast_accessibility_required, Toast.LENGTH_SHORT).show()
            return
        }
        val svc = AdSkipAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, R.string.toast_accessibility_required, Toast.LENGTH_SHORT).show()
            return
        }
        svc.scanOnce { msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startBall() {
        if (!isOverlayEnabled()) {
            Toast.makeText(this, R.string.toast_overlay_required, Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }
        try {
            FloatingBallService.start(this)
            Toast.makeText(this, R.string.toast_ball_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        val a11y = isAccessibilityEnabled()
        val overlay = isOverlayEnabled()
        val notif = Build.VERSION.SDK_INT < 33 || isNotificationEnabled()

        setStatus(binding.tvAccessibilityStatus, a11y)
        setStatus(binding.tvOverlayStatus, overlay)
        if (Build.VERSION.SDK_INT >= 33) {
            binding.cardNotification.visibility = View.VISIBLE
            setStatus(binding.tvNotificationStatus, notif)
        } else {
            binding.cardNotification.visibility = View.GONE
        }

        binding.switchSkip.isChecked = SettingsManager.adSkipEnabled
        binding.switchBall.isChecked = SettingsManager.ballEnabled

        binding.tvOverallStatus.setText(
            if (a11y && overlay) R.string.status_enabled else R.string.status_disabled
        )
    }

    private fun setStatus(view: TextView, enabled: Boolean) {
        view.setText(if (enabled) R.string.status_enabled else R.string.status_disabled)
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.status_green else R.color.status_red,
            )
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun isOverlayEnabled(): Boolean = Settings.canDrawOverlays(this)

    private fun isNotificationEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
