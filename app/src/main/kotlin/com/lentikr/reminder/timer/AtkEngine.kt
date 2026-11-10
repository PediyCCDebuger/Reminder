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
import com.lentikr.reminder.R
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Exact alarms, notification channels, status-bar icon and permission diagnosis. */
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
    private const val CHANNEL_NEXT = "addintimer_next"
    private const val NEXT_ID = 900001
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (E)")

    fun toMillis(t: LocalDateTime): Long =
      t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  }

  fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL, "定时提醒", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "定时提醒"
        enableVibration(true)
        setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, Notification.AUDIO_ATTRIBUTES_DEFAULT)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
      notifications.createNotificationChannel(channel)
      val quiet = NotificationChannel(
        CHANNEL_NEXT, "下次提醒", NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "状态栏图标与最近的一次任务"
        setSound(null, null)
        enableVibration(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
      notifications.createNotificationChannel(quiet)
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

  /** Next fire time in epoch millis, or 0 when the rule yields nothing. Never throws. */
  fun nextMillis(item: AtkItem, after: LocalDateTime = LocalDateTime.now()): Long {
    try {
      val r = item.rule
      var t = Occurrence.first(r, item.hour, item.minute, after) ?: return 0L
      // 时循环: within the firing day, repeat every N minutes until 结束时间.
      if (item.hourLoop != 0 && item.loopMinutes > 0 && r.mode != 4) {
        val endMinutes = item.loopEndH * 60 + item.loopEndM
        val startMinutes = item.hour * 60 + item.minute
        val step = item.loopMinutes
        val nowMinutes = after.hour * 60 + after.minute
        if (endMinutes > startMinutes && step > 0 && after.toLocalDate() == t.toLocalDate()) {
          var m = startMinutes
          while (m < nowMinutes) m += step
          if (m in startMinutes..endMinutes) {
            t = t.withHour((m / 60).coerceIn(0, 23))
              .withMinute((m % 60).coerceIn(0, 59))
              .withSecond(0)
            if (!t.isAfter(after)) return toMillis(t.plusMinutes(step.toLong()))
          }
        }
      }
      return toMillis(t)
    } catch (e: Exception) {
      return 0L
    }
  }

  fun nextText(item: AtkItem): String {
    return try {
      if (!item.enabled) {
        "已停用"
      } else {
        val ms = nextMillis(item)
        if (ms <= 0) {
          "无匹配日期"
        } else {
          LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault())
            .format(FMT)
        }
      }
    } catch (e: Exception) {
      "无匹配日期"
    }
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
      // Exact-alarm permission missing (Android 13+/14+ or some ColorOS builds). Work down
      // the ladder so the timer still fires instead of silently doing nothing.
      try {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
      } catch (e2: SecurityException) {
        try {
          alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } catch (ignored: SecurityException) {
        }
      }
    }
  }

  fun cancel(id: Long) = alarms.cancel(fireIntent(id))

  /**
   * Original 测试执行 behaviour: open the full 任务执行界面 immediately, no intermediate
   * toast. The high-priority notification is posted first so the status bar also gets the
   * "current alarm" icon next to the persistent "next task" icon.
   */
  fun launchAlarmFace(id: Long) {
    val i = Intent(context, AtkAlarmActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      putExtra(EXTRA_ID, id)
    }
    try {
      context.startActivity(i)
    } catch (e: Exception) {
      // Background activity launch may be restricted on some ROMs; the full-screen
      // notification intent still opens the same surface in that case.
    }
  }

  /** Refresh only the persistent "下一次任务" notification, e.g. right after an alarm fires. */
  fun refreshNext() {
    try { showNextNotification(store.all()) } catch (e: Exception) { }
  }

  fun rescheduleAll() {
    val items = try { store.all() } catch (e: Exception) { emptyList() }
    for (item in items) {
      try { schedule(item) } catch (e: Exception) { }
    }
    try { showNextNotification(items) } catch (e: Exception) { }
  }

  /**
   * Post the "current alarm task" notification. Its small icon is the left-most alarm icon in
   * the status bar; the persistent next-task notification supplies the one next to it.
   * @param fullScreen when false the caller opens 任务执行界面 itself (测试执行), avoiding a
   *                   duplicate activity launch from the full-screen intent.
   */
  fun showNotification(item: AtkItem, fullScreen: Boolean = true) {
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
    // 测试执行 opens the face directly: use the quiet channel so a heads-up card never covers
    // the full-screen surface. A real firing keeps the HIGH channel + full-screen intent.
    val channelId = if (fullScreen) CHANNEL else CHANNEL_NEXT
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(context, channelId)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(context)
    }
    val n = builder
      .setSmallIcon(R.drawable.ic_atk_stat)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(Notification.BigTextStyle().bigText(body))
      .setPriority(if (fullScreen) Notification.PRIORITY_MAX else Notification.PRIORITY_LOW)
      .setCategory(Notification.CATEGORY_ALARM)
      .setAutoCancel(false)
      .setOngoing(true)
      .setContentIntent(full)
      .apply {
        if (fullScreen) {
          setFullScreenIntent(full, true)
          setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, Notification.AUDIO_ATTRIBUTES_DEFAULT)
          setVibrate(longArrayOf(0, 400, 300, 400))
        }
      }
      .addAction(R.drawable.ic_atk_stat, "贪睡 10 分钟", snooze)
      .addAction(R.drawable.ic_atk_stat, "终止提醒", stop)
      .build()
    try {
      notifications.notify(item.id.toInt(), n)
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS not granted; the full-screen activity still tries to show.
    }
  }

  /**
   * The original app keeps a small status-bar icon and a persistent notification showing the
   * most recent (i.e. next) task, e.g. 「早起床 - [明天 06:30]」. Silent low-priority channel
   * so it never competes with the actual alarm notification.
   */
  fun showNextNotification(items: List<AtkItem>) {
    val iconPref = try {
      context.getSharedPreferences("atk_settings", Context.MODE_PRIVATE)
        .getString("notify_icon", "应用程序图标")
    } catch (e: Exception) {
      "应用程序图标"
    }
    if (iconPref == "不显示") {
      try { notifications.cancel(NEXT_ID) } catch (e: Exception) { }
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_NEXT, "下次提醒", NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "显示最近的一次定时任务"
        setSound(null, null)
        enableVibration(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
      notifications.createNotificationChannel(channel)
    }
    val now = System.currentTimeMillis()
    var best: AtkItem? = null
    var bestAt = Long.MAX_VALUE
    for (item in items) {
      if (!item.enabled) continue
      val at = try { nextMillis(item) } catch (e: Exception) { 0L }
      if (at > now && at < bestAt) {
        bestAt = at
        best = item
      }
    }
    val whenText = try {
      val dayFmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
      val hmFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
      val today = dayFmt.format(java.util.Date(now))
      val thatDay = dayFmt.format(java.util.Date(bestAt))
      val tomorrow = dayFmt.format(java.util.Date(now + 24L * 60 * 60 * 1000))
      val hm = hmFmt.format(java.util.Date(bestAt))
      when (thatDay) {
        today -> "今天 $hm"
        tomorrow -> "明天 $hm"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
          .format(java.util.Date(bestAt)) + " $hm"
      }
    } catch (e: Exception) {
      ""
    }
    // The original keeps a status-bar icon even when every timer is disabled; cancelling the
    // notification made the icon disappear entirely on a fresh install (all samples are off).
    val bodyText = if (best == null) {
      "暂无启用的定时任务"
    } else {
      best.name.ifBlank { "定时提醒" } + " - [$whenText]"
    }
    val open = PendingIntent.getActivity(
      context, NEXT_ID,
      Intent(context, com.lentikr.reminder.MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(context, CHANNEL_NEXT)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(context)
    }
    val n = builder
      .setSmallIcon(R.drawable.ic_atk_stat)
      .setContentTitle(context.getString(R.string.app_name))
      .setContentText(bodyText)
      .setContentIntent(open)
      .setAutoCancel(false)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(Notification.PRIORITY_LOW)
      .setCategory(Notification.CATEGORY_REMINDER)
      .build()
    try { notifications.notify(NEXT_ID, n) } catch (e: SecurityException) { }
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
