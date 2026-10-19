package com.example.drivesyncfix

import android.app.Application

class SyncDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applyTheme(this)
    }
}
