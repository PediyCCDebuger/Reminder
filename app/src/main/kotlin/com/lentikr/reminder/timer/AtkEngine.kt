package com.lentikr.reminder.timer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import cn.nnci.addintimer.Occurrence
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Exact alarms, notification channel and runtime-permission diagnosis. */
class AtkEngine(private val context: Context) {

  private val store = AtkStore(context)
  private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  companion object {
    const val ACTION_FIRE = "app.lentikr.reminder.ATK_FIRE"
    const val ACTION_SNOOZE = "app.lentikr.reminder.ATK_SNOOZE"
    const val ACTION_STOP = "app.lentikr.reminder.ATK_STOP"
    const val ACTION_BOOT = "app.lentikr.reminder.ATK_BOOT"
    const val EXTRA_ID = "timerid"
    const val EXTRA_AT = "triggerAt"
    const val CHANNEL = "addintimer_alarm"
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (E)")

    fun toMillis(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  }

  fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL, "定时提醒", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "AddinTimer 兼容定时提醒"
        enableVibration(true)
        setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, Notification.AUDIO_ATTRIBUTES_DEFAULT)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
      notifications.createNotificationChannel(channel)
    }
  }

  private fun fireIntent(id: Long): PendingIntent {
    val i = Intent(context, AtkReceiver::class.java).apply {
      action = ACTION_FIRE
      putExtra(EXTRA_ID, id)
    }
    return PendingIntent.getBroadcast(
      context, (id * 7 + 1).toInt(), i,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun actionIntent(action: String, id: Long, code: Int): PendingIntent {
    val i = Intent(context, AtkReceiver::class.java).apply {
      this.action = action
      putExtra(EXTRA_ID, id)
    }
    return PendingIntent.getBroadcast(
      context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /** Next fire time in epoch millis, or 0 when the rule yields nothing. */
  fun nextMillis(item: AtkItem, after: LocalDateTime = LocalDateTime.now()): Long {
    val r = item.rule
    val first = Occurrence.first(r, item.hour, item.minute, after) ?: return 0L
    var t = first
    // 时循环: within the firing day, repeat every N minutes until 结束时间.
    if (item.hourLoop != 0 && item.loopMinutes > 0 && r.mode != 4) {
      val endMinutes = item.loopEndH * 60 + item.loopEndM
      val startMinutes = item.hour * 60 + item.minute
      val step = item.loopMinutes
      val nowMinutes = after.hour * 60 + after.minute
      if (endMinutes > startMinutes && after.toLocalDate() == t.toLocalDate()) {
        var m = startMinutes
        while (m < nowMinutes) m += step
        if (m <= endMinutes) {
          t = t.withHour(m / 60).withMinute(m % 60).withSecond(0)
          if (!t.isAfter(after)) return toMillis(t.plusMinutes(step.toLong()))
        }
      }
    }
    return toMillis(t)
  }

  fun nextText(item: AtkItem): String {
    if (!item.enabled) return "已停用"
    val ms = nextMillis(item)
    if (ms <= 0) return "无匹配日期"
    return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(FMT)
  }

  fun schedule(item: AtkItem) {
    val pi = fireIntent(item.id)
    if (!item.enabled) {
      alarms.cancel(pi)
      return
    }
    val ms = nextMillis(item)
    if (ms <= 0) {
      alarms.cancel(pi)
      return
    }
    val show = PendingIntent.getActivity(
      context, (item.id * 7 + 2).toInt(),
      Intent(context, com.lentikr.reminder.MainActivity::class.java).putExtra(EXTRA_ID, item.id),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val info = AlarmManager.AlarmClockInfo(ms, show)
    try {
      alarms.setAlarmClock(info, pi)
    } catch (e: SecurityException) {
      // Exact alarm permission missing: fall back to an inexact alarm so it still rings.
      try {
        alarms.set(AlarmManager.RTC_WAKEUP, ms, pi)
      } catch (ignored: SecurityException) {
      }
    }
  }

  fun cancel(id: Long) = alarms.cancel(fireIntent(id))

  fun rescheduleAll() {
    for (item in store.all()) {
      schedule(item)
      if (item.enabled) item.nextMs = nextMillis(item)
    }
  }

  fun showNotification(item: AtkItem) {
    ensureChannel()
    val title = item.name.ifBlank { "定时提醒" }
    val body = buildString {
      append(item.rule.describe())
      append(" · ")
      append(String.format("%02d:%02d", item.hour, item.minute))
      if (item.message.isNotBlank()) append('\n').append(item.message)
    }
    val full = PendingIntent.getActivity(
      context, (item.id * 7 + 3).toInt(),
      Intent(context, AtkAlarmActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_ID, item.id)
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val snooze = actionIntent(ACTION_SNOOZE, item.id, (item.id * 7 + 4).toInt())
    val stop = actionIntent(ACTION_STOP, item.id, (item.id * 7 + 5).toInt())
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(context, CHANNEL)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(context)
    }
    val n = builder
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(Notification.BigTextStyle().bigText(body))
      .setPriority(Notification.PRIORITY_MAX)
      .setCategory(Notification.CATEGORY_ALARM)
      .setAutoCancel(true)
      .setContentIntent(full)
      .setFullScreenIntent(full, true)
      .setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, Notification.AUDIO_ATTRIBUTES_DEFAULT)
      .setVibrate(longArrayOf(0, 400, 300, 400))
      .addAction(android.R.drawable.ic_media_play, "贪睡 10 分钟", snooze)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "终止提醒", stop)
      .build()
    try {
      notifications.notify(item.id.toInt(), n)
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS not granted; the full-screen activity still tries to show.
    }
  }

  fun dismiss(id: Long) = notifications.cancel(id.toInt())

  /** Human-readable permission checklist for the 权限自检 screen. */
  fun permissionReport(): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      out.add("精确闹钟（闹钟和提醒）" to alarms.canScheduleExactAlarms())
    }
    out.add("通知权限" to notifications.areNotificationsEnabled())
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    out.add("忽略电池优化" to pm.isIgnoringBatteryOptimizations(context.packageName))
    if (Build.VERSION.SDK_INT >= 34) {
      out.add("全屏通知" to notifications.canUseFullScreenIntent())
    }
    return out
  }

  fun openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val i = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:" + context.packageName)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      try { context.startActivity(i) } catch (e: Exception) { openAppDetails() }
    }
  }

  fun openNotificationSettings() {
    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
      putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try { context.startActivity(i) } catch (e: Exception) { openAppDetails() }
  }

  fun openBatterySettings() {
    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
      data = Uri.parse("package:" + context.packageName)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try { context.startActivity(i) } catch (e: Exception) { openAppDetails() }
  }

  fun openFullScreenSettings() {
    if (Build.VERSION.SDK_INT >= 34) {
      val i = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
        data = Uri.parse("package:" + context.packageName)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      try { context.startActivity(i) } catch (e: Exception) { openAppDetails() }
    }
  }

  private fun openAppDetails() {
    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:" + context.packageName)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(i)
  }
}
