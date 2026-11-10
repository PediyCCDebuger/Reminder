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
    /**
     * The real firing notification. IMPORTANCE_HIGH + full-screen intent is the only supported way
     * to bring a surface up over the keyguard, but the channel itself is silent: the tone comes
     * from [AtkAlarmSound] so that 铃声类型 / 音量大小 / 渐响模式 / 振动类型 are honoured. A channel
     * sound would play the system default alarm tone at full volume first, which is precisely the
     * "渐强被忽略" behaviour.
     * New id (rather than editing CHANNEL): a channel's importance and sound are immutable once
     * created on Android 8+.
     */
    const val CHANNEL_ALARM_V2 = "addintimer_alarm_v2"
    /**
     * Status-bar channel. IMPORTANCE_LOW/MIN channels never draw a status-bar icon on Android 8+,
     * which is why the persistent icon and the 测试执行 icon were invisible. IMPORTANCE_DEFAULT
     * draws the icon; sound/vibration are muted below so it still behaves quietly.
     * The id must differ from 2.4.0's "addintimer_next": a channel's importance is immutable
     * after creation, so reusing the old id would keep it silent and icon-less forever.
     */
    const val CHANNEL_STATUS = "addintimer_status"
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
        setBypassDnd(true)
      }
      notifications.createNotificationChannel(channel)
      // Silent but MAX-importance: heads-up + full-screen intent, while the tone, its volume,
      // its ramp and the vibration pattern are all produced by AtkAlarmSound.
      val alarm = NotificationChannel(
        CHANNEL_ALARM_V2, "闹钟提醒（渐响）", NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "到点提醒：声音由 App 按铃声类型/音量/渐响设置播放"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)
      }
      notifications.createNotificationChannel(alarm)
      // Quiet but visible in the status bar: DEFAULT importance still renders the small icon,
      // while no sound / no vibration / no badge keeps it from disturbing the user.
      val status = NotificationChannel(
        CHANNEL_STATUS, "状态栏图标", NotificationManager.IMPORTANCE_DEFAULT,
      ).apply {
        description = "任务栏常驻图标与最近的一次任务"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
      notifications.createNotificationChannel(status)
    }
  }

  /**
   * The alarm PendingIntent starts [AtkAlarmService] as a foreground service, not a broadcast.
   * Alarms fired from `setAlarmClock()` put the app on the temporary allowlist, so the service
   * starts even when the app was cached/frozen — a plain broadcast to a frozen app can be held
   * back by aggressive ROMs, which then looks like "到点不响，解锁后才响".
   */
  private fun fireIntent(id: Long): PendingIntent {
    val i = Intent(context, AtkAlarmService::class.java).apply {
      action = AtkAlarmService.ACTION_START
      putExtra(AtkAlarmService.EXTRA_ACTION, AtkAlarmService.ACTION_START)
      putExtra(EXTRA_ID, id)
    }
    return PendingIntent.getForegroundService(
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

  /**
   * The broadcast PendingIntent used up to 2.5.0. Alarms armed by the previous build are still
   * registered in the system after an update, and `AlarmManager.cancel()` only matches the exact
   * same PendingIntent type — without this, one stale broadcast alarm per task would fire on top
   * of the new service-backed one and ring twice.
   */
  private fun legacyFireIntent(id: Long): PendingIntent {
    val i = Intent(context, AtkReceiver::class.java).apply {
      action = ACTION_FIRE
      putExtra(EXTRA_ID, id)
    }
    return PendingIntent.getBroadcast(
      context, (id * 7 + 1).toInt(), i,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  fun schedule(item: AtkItem) {
    val pi = fireIntent(item.id)
    runCatching { alarms.cancel(legacyFireIntent(item.id)) }
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
      AtkDiag.log(context, "排程: setAlarmClock 被拒（${e.message}），改用 setExact")
      try {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
      } catch (e2: SecurityException) {
        AtkDiag.log(context, "排程: setExact 也被拒（${e2.message}），改用非精确闹钟")
        try {
          alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } catch (ignored: SecurityException) {
          AtkDiag.log(context, "排程: 全部失败，任务 ${item.name} 无法定时")
        }
      }
    }
  }

  fun cancel(id: Long) {
    runCatching { alarms.cancel(fireIntent(id)) }
    runCatching { alarms.cancel(legacyFireIntent(id)) }
  }

  /**
   * Arm the real firing path 60 seconds from now, for the 60 秒后真实触发 menu entry.
   *
   * 测试执行 opens 执行界面 directly and therefore never exercises the alarm → foreground
   * service → ring chain; the only other way to check that chain is to wait for the configured
   * time. This uses a separate request code so the task's normal schedule is left untouched.
   */
  fun fireTestSoon(id: Long) {
    val i = Intent(context, AtkAlarmService::class.java).apply {
      action = AtkAlarmService.ACTION_START
      putExtra(AtkAlarmService.EXTRA_ACTION, AtkAlarmService.ACTION_START)
      putExtra(EXTRA_ID, id)
      // Force the ring chain even if the task is disabled, so the 60s test verifies 响铃 / 渐响 /
      // 执行界面 without first having to enable the task (测试执行 already ignores enabled).
      putExtra(AtkAlarmService.EXTRA_FORCE, true)
    }
    val pi = PendingIntent.getForegroundService(
      context, (id * 7 + 7).toInt(), i,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val at = System.currentTimeMillis() + 60_000L
    AtkDiag.log(context, "排程: 60 秒后真实触发测试 id=$id")
    // Use setAlarmClock (not setExactAndAllowWhileIdle): the latter is routinely deferred by
    // several minutes when the device is dozing, which made the test look like "nothing rang".
    // setAlarmClock is the highest-priority, doze-exempt alarm and is what the real schedule
    // already uses, so the test now exercises the exact same on-time path.
    val info = AlarmManager.AlarmClockInfo(at, alarmActivityIntent(id, (id * 7 + 9).toInt()))
    try {
      alarms.setAlarmClock(info, pi)
    } catch (e: SecurityException) {
      AtkDiag.log(context, "排程: 测试触发 setAlarmClock 被拒（${e.message}），改用 setExact")
      try {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
      } catch (e2: SecurityException) {
        alarms.set(AlarmManager.RTC_WAKEUP, at, pi)
      }
    }
  }

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
      AtkDiag.log(context, "执行界面: 已请求启动")
    } catch (e: Exception) {
      // Background activity launch may be restricted on some ROMs; the full-screen
      // notification intent still opens the same surface in that case.
      AtkDiag.log(context, "执行界面: 启动被拦 ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  /**
   * Minimal notification for the last-resort `startForeground()` attempt: some ROMs reject the
   * alarm notification itself (full-screen intent plus a MAX-importance channel), and being a
   * foreground service at all is what keeps the tone alive.
   */
  fun buildFallbackNotification(item: AtkItem): Notification {
    ensureChannel()
    val open = alarmActivityIntent(item.id, (item.id * 7 + 3).toInt())
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(context, CHANNEL_STATUS)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(context)
    }
    return builder
      .setSmallIcon(R.drawable.ic_atk_stat)
      .setContentTitle(item.name.ifBlank { "定时提醒" })
      .setContentText(alarmBody(item))
      .setPriority(Notification.PRIORITY_DEFAULT)
      .setCategory(Notification.CATEGORY_ALARM)
      .setContentIntent(open)
      .setAutoCancel(false)
      .setOngoing(true)
      .build()
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

  private fun alarmActivityIntent(id: Long, code: Int): PendingIntent = PendingIntent.getActivity(
    context, code,
    Intent(context, AtkAlarmActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(EXTRA_ID, id)
    },
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

  private fun alarmBody(item: AtkItem): String = buildString {
    append(item.rule.describe())
    append(" · ")
    append(String.format("%02d:%02d", item.hour, item.minute))
    if (item.message.isNotBlank()) append('\n').append(item.message)
  }

  /**
   * The real firing notification: HIGH importance + full-screen intent, which is the only
   * officially supported way to bring a surface up over the lock screen. Also reused as the
   * foreground-service notification so the sound keeps playing while the screen is locked.
   */
  fun buildAlarmNotification(item: AtkItem): Notification {
    ensureChannel()
    val full = alarmActivityIntent(item.id, (item.id * 7 + 3).toInt())
    val snooze = actionIntent(ACTION_SNOOZE, item.id, (item.id * 7 + 4).toInt())
    val stop = actionIntent(ACTION_STOP, item.id, (item.id * 7 + 5).toInt())
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(context, CHANNEL_ALARM_V2)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(context)
    }
    // Link the notification to the active media session so the mediaPlayback foreground service
    // is recognised as genuine media playback and not killed while the screen is locked.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      AtkAlarmSound.sessionToken()?.let { builder.setMediaSession(it) }
    }
    return builder
      .setSmallIcon(R.drawable.ic_atk_stat)
      .setContentTitle(item.name.ifBlank { "定时提醒" })
      .setContentText(alarmBody(item))
      .setStyle(Notification.BigTextStyle().bigText(alarmBody(item)))
      .setPriority(Notification.PRIORITY_MAX)
      .setCategory(Notification.CATEGORY_ALARM)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setAutoCancel(false)
      .setOngoing(true)
      .setContentIntent(full)
      .setFullScreenIntent(full, true)
      .addAction(R.drawable.ic_atk_stat, "贪睡 10 分钟", snooze)
      .addAction(R.drawable.ic_atk_stat, "终止提醒", stop)
      .build()
  }

  /**
   * Post the notification for [item].
   * @param fullScreen true for a real firing (HIGH + full-screen intent). 测试执行 passes false:
   *                   the caller opens 任务执行界面 itself, so this uses the quiet status-bar
   *                   channel — DEFAULT importance so the alarm icon appears next to the
   *                   persistent icon, but silent and without a heads-up card.
   */
  fun showNotification(item: AtkItem, fullScreen: Boolean = true) {
    val n = if (fullScreen) {
      buildAlarmNotification(item)
    } else {
      ensureChannel()
      val open = alarmActivityIntent(item.id, (item.id * 7 + 3).toInt())
      val stop = actionIntent(ACTION_STOP, item.id, (item.id * 7 + 5).toInt())
      val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, CHANNEL_STATUS)
      } else {
        @Suppress("DEPRECATION") Notification.Builder(context)
      }
      builder
        .setSmallIcon(R.drawable.ic_atk_stat)
        .setContentTitle(item.name.ifBlank { "定时提醒" })
        .setContentText(alarmBody(item))
        .setStyle(Notification.BigTextStyle().bigText(alarmBody(item)))
        .setPriority(Notification.PRIORITY_DEFAULT)
        .setCategory(Notification.CATEGORY_ALARM)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setAutoCancel(false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(open)
        .addAction(R.drawable.ic_atk_stat, "终止提醒", stop)
        .build()
    }
    try {
      notifications.notify(item.id.toInt(), n)
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS not granted; the full-screen activity still tries to show.
    }
  }

  /**
   * The original app keeps a small status-bar icon and a persistent notification showing the
   * most recent (i.e. next) task, e.g. 「早起床 - [明天 06:30]」. Uses the status channel so the
   * icon is actually drawn, but stays silent and badge-free.
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
    ensureChannel()
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
      Notification.Builder(context, CHANNEL_STATUS)
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
      .setPriority(Notification.PRIORITY_DEFAULT)
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      out.add("悬浮窗（后台弹出界面）" to Settings.canDrawOverlays(context))
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

  /** ROMs that ignore full-screen intents still let an app with this permission pop up. */
  fun openOverlaySettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
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
