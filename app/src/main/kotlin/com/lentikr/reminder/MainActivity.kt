package com.lentikr.reminder

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lentikr.reminder.timer.AtkEngine
import com.lentikr.reminder.timer.TimerApp

/**
 * 唯一入口。3.7.3 起仓库已移除 fork 的上游纪念日代码（Hilt / Room / DataStore / Navigation 3 /
 * ViewModel / 主题设置层都不再需要），主界面直接是 TimerApp，它自带 AtkTheme 深色主题。
 */
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // Android 13+ 需要运行时授权，否则闹钟通知与常驻图标都发不出来。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }
    setContent { TimerApp() }
    warnFullScreenOnce()
  }

  /**
   * Android 14+ 会静默降级全屏 intent，表现为「闹钟到点只出现在通知栏，要解锁点通知才打开界面」。
   * 首次启动提示一次，之后交给「系统设置 → 服务状态」复查。
   */
  private fun warnFullScreenOnce() {
    if (Build.VERSION.SDK_INT < 34) return
    val nm = getSystemService(NotificationManager::class.java) ?: return
    if (nm.canUseFullScreenIntent()) return
    val prefs = getSharedPreferences("atk_settings", MODE_PRIVATE)
    if (prefs.getBoolean("full_screen_warned", false)) return
    prefs.edit().putBoolean("full_screen_warned", true).apply()
    AlertDialog.Builder(this)
      .setTitle("锁屏闹钟需要「全屏通知」权限")
      .setMessage(
        "Android 14 起应用默认不能在全屏或锁屏状态弹出界面。\n\n" +
          "请到「设置 → 应用 → 特殊权限 → 全屏通知」允许 Reminder。" +
          "否则闹钟到点只会出现在通知栏，需要解锁后点通知才会打开执行界面。\n\n" +
          "开启后可在 App 的「系统菜单 → 系统设置 → 服务状态」复查。",
      )
      .setPositiveButton("去开启") { _, _ -> AtkEngine(this).openFullScreenSettings() }
      .setNegativeButton("稍后", null)
      .show()
  }
}
