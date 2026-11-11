package com.lentikr.reminder

import android.app.Application
import com.lentikr.reminder.timer.AtkCrash
import com.lentikr.reminder.timer.AtkEngine
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ReminderApp : Application() {
  override fun onCreate() {
    super.onCreate()
    // Record uncaught exceptions so a device-only crash can be diagnosed from
    // 「系统菜单 → 关于软件」 on the next launch.
    AtkCrash.install(this)
    // 3.6.2 保活：应用进程创建即尝试启动常驻前台服务，使进程在锁屏休眠期间不被 ColorOS 速冻，
    // 从而 setAlarmClock 到点能准时唤醒执行。后台启动前台服务可能受 Android 12+ 限制抛异常，故兜底。
    try {
      val engine = AtkEngine(this)
      if (engine.isKeepAliveOn()) engine.startKeepAlive()
    } catch (e: Exception) {
    }
  }
}
