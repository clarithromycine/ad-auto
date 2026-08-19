package com.wisight.adauto.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用级设置，使用 SharedPreferences 持久化。
 * 悬浮球与无障碍服务共享同一份状态。
 */
object SettingsManager {
    private const val PREFS = "ad_auto_prefs"
    private const val KEY_AD_SKIP_ENABLED = "ad_skip_enabled"
    private const val KEY_BALL_ENABLED = "ball_enabled"
    private const val KEY_CUSTOM_KEYWORDS = "custom_keywords"
    private const val KEY_SUPPORTED_PACKAGES = "supported_packages"
    private const val KEY_GENERIC_MODE = "generic_mode"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 自动跳过广告总开关 */
    var adSkipEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_SKIP_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AD_SKIP_ENABLED, value).apply()
        }

    /** 是否显示悬浮球 */
    var ballEnabled: Boolean
        get() = prefs.getBoolean(KEY_BALL_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BALL_ENABLED, value).apply()
        }

    /** 用户自定义“跳过”按钮关键词，逗号分隔 */
    var customKeywords: String
        get() = prefs.getString(KEY_CUSTOM_KEYWORDS, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_KEYWORDS, value).apply()
        }

    /**
     * 支持的短剧/视频 App 包名（逗号分隔）。
     * 默认只在列出的 App 内自动检测广告，避免在聊天/设置/桌面等普通界面
     * 把页面上的“广告/关闭/跳过”等字样误判成广告而自动点击。
     */
    var supportedPackages: String
        get() = prefs.getString(KEY_SUPPORTED_PACKAGES, "com.phoenix.read").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SUPPORTED_PACKAGES, value).apply()
        }

    /** 支持的包名集合（去空白、去空项），供检测器快速判断 */
    fun supportedPackagesList(): Set<String> =
        supportedPackages.split(',', '，', '、', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * 通用模式：开启后在所有 App 内都尝试关键字匹配（误触风险更高）。
     * 默认关闭 = 仅在 supportedPackages 指定的 App 内自动跳过广告。
     */
    var genericModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_GENERIC_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GENERIC_MODE, value).apply()
        }
}
