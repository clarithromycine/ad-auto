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
}
