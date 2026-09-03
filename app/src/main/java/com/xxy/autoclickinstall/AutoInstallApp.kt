package com.xxy.autoclickinstall

import android.app.Application
import com.xxy.autoclickinstall.core.AppContext
import com.xxy.autoclickinstall.core.SettingsStore
import com.xxy.autoclickinstall.shizuku.ShizukuHelper

class AutoInstallApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        SettingsStore.migrate()
        ShizukuHelper.init(this)
    }
}
