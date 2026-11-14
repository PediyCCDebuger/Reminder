package com.lentikr.reminder

import android.app.Application
import com.lentikr.reminder.timer.AtkCrash

/** 3.7.3：移除上游 Hilt 注入后，Application 只负责安装崩溃日志收集。 */
class ReminderApp : Application() {
  override fun onCreate() {
    super.onCreate()
    // 记录未捕获异常，下次启动时可在「系统菜单 → 关于软件」查看。
    AtkCrash.install(this)
  }
}
