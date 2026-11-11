package com.lentikr.reminder.timer

import com.lentikr.reminder.MainActivity
import com.lentikr.reminder.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 3.6.2 常驻前台服务保活（针对 ColorOS / 一加 13 的「应用速冻 / 后台运行限制」）。
 *
 * 根因（来自真机日志）：应用进程在锁屏休眠期间被 ROM 冻结，setAlarmClock 到点后的广播被积压，
 * 直到用户解锁并点击通知栏把应用「解冻」才投递——表现为「不点击通知栏就永远不执行」。
 * Smart Alarm 在本机可用，是因为它引导用户做了全套保活（忽略电池优化 + 自启动 + 后台运行 +
 * 电池不限制）且带桌面小部件（小部件更新让系统不易冻结它）。
 *
 * 本服务在后台常驻一个低优先级前台通知，使 Reminder 进程保持存活、不被速冻，从而 setAlarmClock
 * 到点必然能准时唤醒并执行（响铃 + 拉起执行界面）。这是 ColorOS 上闹钟可靠响的最后一道工程保险。
 * 用户可在「系统设置 → 保活模式」关闭（关闭后退回到「依赖系统保活豁免」，不保证可靠）。
 */
class AtkKeepAliveService : Service() {

  companion object {
    const val NOTIF_ID = 910099
    const val CHANNEL = "addintimer_keepalive"
    const val ACTION_STOP = "keepalive_stop"
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    startInForeground()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    startInForeground()
    // START_STICKY：被系统回收后尽量重建（ColorOS 仍可能回收，但能争取存活）。
    return START_STICKY
  }

  private fun startInForeground() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
      CHANNEL, "保活（后台常驻）", NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "保持 Reminder 在后台存活，使锁屏闹钟准时响"
      setShowBadge(false)
    }
    nm.createNotificationChannel(channel)

    val launch = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pi = PendingIntent.getActivity(
      this, 0, launch,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val notification = NotificationCompat.Builder(this, CHANNEL)
      .setContentTitle("Reminder 正在保护定时任务")
      .setContentText("后台常驻，确保锁屏闹钟准时响（可在系统设置关闭）")
      .setSmallIcon(R.drawable.ic_atk_stat)
      .setContentIntent(pi)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIF_ID, notification)
    }
  }
}
