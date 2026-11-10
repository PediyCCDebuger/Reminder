package com.lentikr.reminder

import android.app.Application
import com.lentikr.reminder.timer.AtkCrash
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ReminderApp : Application() {
  override fun onCreate() {
    super.onCreate()
    // Record uncaught exceptions so a device-only crash can be diagnosed from
    // 「系统菜单 → 关于软件」 on the next launch.
    AtkCrash.install(this)
  }
}
