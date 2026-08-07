package com.wisight.adauto

import android.app.Application
import com.wisight.adauto.core.SettingsManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsManager.init(this)
    }
}
