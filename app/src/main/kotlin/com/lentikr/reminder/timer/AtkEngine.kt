package com.lentikr.reminder.timer

import android.app.AlarmManager
import android.media.AudioManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
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
    /** Set by 60 秒后真实触发 so the fired 执行界面 forces the ring chain on a disabled task. */
    const val EXTRA_FORCE = "force"
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
     * 3.0.0: a channel's importance is immutable once created. On ROMs that silently downgrade
     * importance (OnePlus/ColorOS), the old v2 channel can no longer deliver a full-screen
     * intent. A brand-new HIGH-importance id guarantees the alarm notification is eligible to
     * launch its full-screen Activity over the keyguard.
     */
    const val CHANNEL_ALARM_V3 = "addintimer_alarm_v3"
    /**
     * 3.1.0: 3.0.0's v3 channel was created while the notification carried setOnlyAlertOnce.
     * Once a channel has been created its importance is immutable, and some OEMs additionally
     * cache the "alerting" behaviour of a channel, so a fresh id is the only reliable way to
     * guarantee the new, un-suppressed notification is eligible for a full-screen intent.
     */
    const val CHANNEL_ALARM_V4 = "addintimer_alarm_v4"
    /**
     * 3.6.4: a DEFAULT-importance channel used when a task's 屏幕模式 is 「不点亮屏幕」. A HIGH
     * channel always raises a heads-up (which lights the screen even without a full-screen
     * intent), defeating the setting, so 不点亮屏幕 arms this quiet channel instead.
     */
    const val CHANNEL_ALARM_V5 = "addintimer_alarm_v5"
    /**
     * Status-bar channel. IMPORTANCE_LOW/MIN channels never draw a status-bar icon on Android 8+,
     * which is why the persistent icon and the 测试执行 icon were invisible. IMPORTANCE_DEFAULT
     * draws the icon; sound/vibration are muted below so it still behaves quietly.
     * The id must differ from 2.4.0's "addintimer_next": a channel's importance is immutable
     * after creation, so reusing the old id would keep it silent and icon-less forever.
     */
    const val CHANNEL_STATUS = "addintimer_status"
    private const val NEXT_ID = 900001
    /**
     * 3.1.0: the alarm notification MUST have its own id. 3.0.0 reused item.id for the quiet
     * 测试执行 notification, the persistent next-task notification AND the full-screen alarm
     * notification. Updating an already-visible id is treated by SystemUI as a refresh, not a
     * new alert, so the full-screen intent never fires. Smart Alarm likewise uses
     * 10_000_000 + alarmId for the alarm notification and a different id for its status icon.
     */
    private const val ALARM_NOTIF_ID_BASE = 910000
    private const val FACE_LAUNCH_GAP_MS = 8_000L
    @Volatile
    private var lastFaceLaunchAt = 0L
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (E)")

    /** Dedicated, never-reused notification id for a firing alarm. */
    fun alarmNotificationId(id: Long): Int = ALARM_NOTIF_ID_BASE + id.toInt()

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
      // 3.0.0 full-screen alarm channel. IMPORTANCE_HIGH + VISIBILITY_PUBLIC + bypass DND.
      // Sound stays null: the tone is produced by AtkAlarmSound so 铃声类型/音量/渐响 are honoured.
      val alarmV3 = NotificationChannel(
        CHANNEL_ALARM_V3, "闹钟提醒（全屏）", NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "到点提醒：锁屏时点亮屏幕并弹出执行界面"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)
      }
      notifications.createNotificationChannel(alarmV3)
      // 3.1.0: brand-new HIGH channel for the foreground service's full-screen notification.
      // IMPORTANCE_HIGH is required for SystemUI to honour setFullScreenIntent; the channel is
      // silent because the ringtone (with its 音量/渐响 settings) is produced by AtkAlarmSound.
      val alarmV4 = NotificationChannel(
        CHANNEL_ALARM_V4, "闹钟提醒（锁屏全屏）", NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "到点由前台服务发出，锁屏时点亮屏幕并弹出执行界面"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)
      }
      notifications.createNotificationChannel(alarmV4)
      // 3.6.4: quiet channel for 屏幕模式 = 不点亮屏幕 (no heads-up, no full-screen intent).
      val alarmV5 = NotificationChannel(
        CHANNEL_ALARM_V5, "闹钟提醒（不点亮屏幕）", NotificationManager.IMPORTANCE_DEFAULT,
      ).apply {
        description = "屏幕模式设为「不点亮屏幕」时使用：不弹横幅、不点亮屏幕"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)
      }
      notifications.createNotificationChannel(alarmV5)
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
   * The alarm PendingIntent goes to a **broadcast receiver**, never straight to an Activity.
   *
   * v2.9.0 armed `setAlarmClock()` with `getActivity(AtkAlarmActivity)`. On ColorOS / OnePlus
   * that background-activity-launch exemption is not honoured, so the surface never came up —
   * and the full-screen alarm notification was only posted from the Activity/Service, so it never
   * reached SystemUI either. Deadlock: only tapping the notification after unlocking rang.
   *
   * Both reference apps verified working on this device (SunriseAlarm, Smart Alarm) do this:
   *   setAlarmClock -> BroadcastReceiver -> post a HIGH-importance notification carrying
   *   setFullScreenIntent(Activity, true) + start a foreground service.
   * SystemUI (not our app) launches the full-screen Activity over the keyguard — the only
   * officially supported background-to-foreground path OEMs honour while the device is locked.
   * A new request code keeps this distinct from every PendingIntent armed by older builds.
   */
  private fun fireIntent(
    id: Long,
    code: Int = (id * 7 + 11).toInt(),
    force: Boolean = false,
  ): PendingIntent {
    val i = Intent(context, AtkReceiver::class.java).apply {
      action = ACTION_FIRE
      putExtra(EXTRA_ID, id)
      if (force) putExtra(EXTRA_FORCE, true)
    }
    return PendingIntent.getBroadcast(
      context, code, i,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /**
   * Every PendingIntent flavour armed by 2.5.0–2.9.0. AlarmManager.cancel() matches the exact
   * PendingIntent type, and PendingIntent equality ignores extras, so a bare Intent per flavour
   * is enough. Cancelling them on every (re)schedule stops an update from ringing twice.
   */
  private fun legacyFireIntents(id: Long): List<PendingIntent> {
    val f = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return listOf(
      legacyFireIntent(id), // <= 2.5.0: broadcast
      PendingIntent.getForegroundService( // 2.6.0–2.8.0: service
        context, (id * 7 + 1).toInt(), Intent(context, AtkAlarmService::class.java), f,
      ),
      PendingIntent.getActivity( // 2.9.0: activity
        context, (id * 7 + 1).toInt(), Intent(context, AtkAlarmActivity::class.java), f,
      ),
      PendingIntent.getActivity( // 2.9.0: 60s test
        context, (id * 7 + 7).toInt(), Intent(context, AtkAlarmActivity::class.java), f,
      ),
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

  // ---------------------------------------------------------------- 3.6.5：全局遗留选项
  private fun atkPrefs() = context.getSharedPreferences("atk_settings", Context.MODE_PRIVATE)

  /** 执行等待倒计时（全局 wait）：返回延迟毫秒，不等待为 0。 */
  fun globalWaitMs(): Long {
    return when (atkPrefs().getString("wait", "不等待") ?: "不等待") {
      "5秒" -> 5000L
      "10秒" -> 10000L
      "15秒" -> 15000L
      "30秒" -> 30000L
      else -> 0L
    }
  }

  /** 伴随振动模式（全局 vibrate）：无振动(-1, 不覆盖 per-timer) / 短振动(1) / 长振动(4)。 */
  fun globalVibrate(): Int {
    return when (atkPrefs().getString("vibrate", "无振动") ?: "无振动") {
      "短振动" -> 1
      "长振动" -> 4
      else -> -1
    }
  }

  /** 通话中例外（全局 call）：取值「无例外 / 停止执行 / 延迟5分钟执行」。 */
  fun globalCallMode(): String = atkPrefs().getString("call", "无例外") ?: "无例外"

  /** 任务执行时电源模式（全局 power）：「全唤醒」时强制点亮屏幕（覆盖 per-timer act[15]）。 */
  fun globalPowerForceLight(): Boolean =
    (atkPrefs().getString("power", "半唤醒(节电模式)") ?: "半唤醒(节电模式)") == "全唤醒"

  /** 当前是否处于通话中（无需权限，仅读 AudioManager 通话模式）。 */
  fun isInCall(): Boolean {
    return try {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      // AudioManager 无公开 isInCall() 接口，通话中以 MODE_IN_CALL / MODE_IN_COMMUNICATION 体现；
      // 统一用模式判断，不依赖 SDK 版本的特定属性。
      am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
    } catch (e: Exception) { false }
  }

  /** 通话中「延迟5分钟执行」：用同一 fireIntent 重排 delayMs 后触发（setAlarmClock，保证唤醒）。 */
  fun fireNowDelayed(id: Long, delayMs: Long) {
    val pi = fireIntent(id)
    for (old in legacyFireIntents(id)) runCatching { alarms.cancel(old) }
    val at = System.currentTimeMillis() + delayMs
    val aci = AlarmManager.AlarmClockInfo(at, pi)
    try {
      alarms.setAlarmClock(aci, pi)
    } catch (e: SecurityException) {
      try {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
      } catch (e2: SecurityException) {
        alarms.set(AlarmManager.RTC_WAKEUP, at, pi)
      }
    }
  }

  /**
   * 3.6.5：挂「贪睡」重响。minutes 分钟后以 ATK_SNOOZE_FIRE 广播重新触发（setAlarmClock，保证深睡/
   * Doze 下也能准时唤醒，避免此前 setExactAndAllowWhileIdle 被延后导致「贪睡后再也不响」）。
   * minutes 取任务的「预设时间」(act[13])，使翻转贪睡 / 自动贪睡 / 贪睡按钮都遵循用户在参数里设置的
   * 贪睡时长，而不是写死的 10 分钟。仅负责挂钟，不停止前台服务、不 dismiss。
   */
  fun armSnooze(id: Long, minutes: Int) {
    val m = minutes.coerceIn(1, 60)
    val pi = PendingIntent.getBroadcast(
      context, (id * 7 + 6).toInt(),
      Intent(context, AtkReceiver::class.java).apply {
        this.action = "app.lentikr.reminder.ATK_SNOOZE_FIRE"
        putExtra(EXTRA_ID, id)
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val at = System.currentTimeMillis() + m * 60 * 1000L
    val aci = AlarmManager.AlarmClockInfo(at, pi)
    try {
      alarms.setAlarmClock(aci, pi)
    } catch (e: SecurityException) {
      try {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
      } catch (e2: SecurityException) {
        alarms.set(AlarmManager.RTC_WAKEUP, at, pi)
      }
    }
    AtkDiag.log(context, "贪睡: 已挂 ${m} 分钟后重响 id=$id")
  }

  /** 解析任务「预设时间」为分钟数（act[13] 存的是 SNAP_DUR 选项索引）。 */
  fun snoozeMinutes(item: AtkItem): Int {
    val idx = AtkItem.intOf(item.actValue(13)).coerceIn(0, AtkOptions.SNAP_DUR.lastIndex)
    val n = AtkOptions.SNAP_DUR[idx].filter { it.isDigit() }.toIntOrNull() ?: 10
    return n.coerceIn(1, 60)
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
    // Drop stale alarms armed by previous builds (broadcast / service / activity flavours).
    for (old in legacyFireIntents(item.id)) runCatching { alarms.cancel(old) }
    if (!item.enabled) {
      alarms.cancel(pi)
      return
    }
    val ms = nextMillis(item)
    if (ms <= 0) {
      alarms.cancel(pi)
      return
    }
    // 3.6.1：排程统一走 [setExactAlarm]，其主路径是 setAlarmClock（对齐 Smart Alarm-v2.7.7，
    // 本机 ColorOS / 一加 13 验证可用）。setAlarmClock 保证锁屏深睡时也准时唤醒设备、不受 Doze
    // 延后；并且恢复「系统时钟显示下次闹钟」指示。3.5.0/3.6.0 一度改用 setExactAndAllowWhileIdle
    // 以规避当时 FSI 被吞的问题，但实测该类型在深睡下被延后投递（60 秒测试晚了约 91 秒且须解锁才送达），
    // 故 3.6.1 改回 setAlarmClock。界面已由 3.6.0 的前台服务直接 startActivity 承担，不再依赖 FSI，
    // 故 setAlarmClock + 直接 startActivity 正好等价于 Smart Alarm 的可用组合。
    // 3.6.0：排程日志不再打印任务名（隐私），改为在 rescheduleAll 处汇总一行。
    setExactAlarm(ms, pi, item.id)
  }

  /**
   * 3.6.1：主路径改用 [AlarmManager.setAlarmClock]，对齐 Smart Alarm-v2.7.7 在本机（一加13 /
   * ColorOS / Android14）验证可用的排程方式。
   *
   * 根因：3.6.0 用 setExactAndAllowWhileIdle，该类型在设备锁屏深睡 / Doze 下会被系统**延后投递**
   * （实测 60 秒测试实际在约 91 秒后才触发，且必须等用户解锁唤醒设备才送达），所以「到点不响、
   * 不亮屏」——并非界面链路问题，而是闹钟根本没按时投递。setAlarmClock 是 Android 唯一保证
   * 从深睡唤醒设备、不受 Doze 延后的用户闹钟类型，也不需要 SCHEDULE_EXACT_ALARM 权限。
   *
   * 为何此前不敢用 setAlarmClock：3.0.0–3.5.0 用它在 FSI（full-screen intent）路径下被系统压制
   * FSI、导致锁屏界面出不来。但 3.6.0 起界面已由「前台服务直接 startActivity」承担、不再依赖
   * FSI，所以 setAlarmClock + 直接 startActivity 正好等价于 Smart Alarm 的可用路径。
   *
   * 排程日志不再打印任务名（隐私）；正常设定静默返回 true，仅在降级/失败时记录且不带任务名。
   */
  private fun setExactAlarm(ms: Long, pi: PendingIntent, id: Long): Boolean {
    val tag = "#$id"
    val aci = AlarmManager.AlarmClockInfo(ms, pi)
    return try {
      alarms.setAlarmClock(aci, pi)
      true
    } catch (e: SecurityException) {
      AtkDiag.log(context, "排程: setAlarmClock 被拒（${e.message}），降级 setExactAndAllowWhileIdle")
      try {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        true
      } catch (e2: SecurityException) {
        AtkDiag.log(context, "排程: 降级 setAndAllowWhileIdle（${e2.message}）")
        try {
          alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
          true
        } catch (e3: SecurityException) {
          AtkDiag.log(context, "排程: 全部失败，任务 $tag 无法定时")
          false
        }
      }
    }
  }

  fun cancel(id: Long) {
    runCatching { alarms.cancel(fireIntent(id)) }
    runCatching { alarms.cancel(fireIntent(id, (id * 7 + 13).toInt())) }
    for (old in legacyFireIntents(id)) runCatching { alarms.cancel(old) }
  }

  /**
   * Arm the real firing path 60 seconds from now, for the 60 秒后真实触发 menu entry.
   *
   * 测试执行 opens 执行界面 directly and therefore never exercises the alarm → foreground
   * service → ring chain; the only other way to check that chain is to wait for the configured
   * time. This uses a separate request code so the task's normal schedule is left untouched.
   */
  fun fireTestSoon(id: Long) {
    // Exercise the *exact same* chain as a real alarm: setAlarmClock -> AtkReceiver
    // -> foreground service -> 直接 startActivity 执行界面. A separate request code leaves the
    // task's normal schedule untouched; EXTRA_FORCE makes the chain fire even on a disabled task.
    // 3.6.1：与真实排程一致用 setAlarmClock（对齐 Smart Alarm）。setAlarmClock 保证锁屏深睡时
    // 也能准时唤醒设备，不受 Doze 延后——这是 3.6.0 用 setExactAndAllowWhileIdle 导致「到点不响、
    // 不亮屏」的根因。此前担心 setAlarmClock 压制 FSI 是错的：3.6.0 起界面已不依赖 FSI。
    val pi = fireIntent(id, (id * 7 + 13).toInt(), force = true)
    val at = System.currentTimeMillis() + 60_000L
    AtkDiag.log(context, "排程: 60 秒后真实触发测试 id=$id")
    setExactAlarm(at, pi, id)
  }

  /**
   * Original 测试执行 behaviour: open the full 任务执行界面 immediately, no intermediate
   * toast. The high-priority notification is posted first so the status bar also gets the
   * "current alarm" icon next to the persistent "next task" icon.
   *
   * 3.1.0: the alarm chain (broadcast + foreground service) passes [dedupe] = true so the
   * Activity -> service -> Activity cycle cannot keep re-launching the face. Manual 测试执行
   * leaves it false, so a user-initiated test is never swallowed by a recent alarm.
   */
  fun launchAlarmFace(id: Long, force: Boolean = false, dedupe: Boolean = false) {
    if (dedupe) {
      val now = System.currentTimeMillis()
      if (now - lastFaceLaunchAt < FACE_LAUNCH_GAP_MS) {
        AtkDiag.log(context, "执行界面: 8 秒内已请求，跳过重复拉起 id=$id")
        return
      }
      lastFaceLaunchAt = now
    }
    val i = Intent(context, AtkAlarmActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      putExtra(EXTRA_ID, id)
      if (force) putExtra(EXTRA_FORCE, true)
    }
    try {
      context.startActivity(i)
      AtkDiag.log(context, "执行界面: 已请求直接启动 force=$force")
    } catch (e: Exception) {
      // Belt and braces only: the full-screen notification already asked SystemUI to open the
      // same surface. A blocked direct launch costs nothing here.
      AtkDiag.log(context, "执行界面: 直接启动被拦 ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  /**
   * 3.6.4：「点击定时器图标 = 执行任务」时立即按真实闹钟流程触发（force=true 忽略停用，等同测试执行）。
   * 走 [AtkReceiver.ACTION_FIRE]，与到点触发共用同一条链：播铃 → 前台服务挂载通知并拉起执行界面。
   */
  fun fireNow(id: Long) {
    try {
      context.sendBroadcast(
        Intent(context, AtkReceiver::class.java).apply {
          action = ACTION_FIRE
          putExtra(EXTRA_ID, id)
          putExtra(EXTRA_FORCE, true)
        },
      )
    } catch (e: Exception) {
      AtkDiag.log(context, "执行任务: 发送触发广播失败 ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  /**
   * Minimal notification for the last-resort `startForeground()` attempt: some ROMs reject the
   * alarm notification itself (full-screen intent plus a MAX-importance channel), and being a
   * foreground service at all is what keeps the tone alive.
   */
  fun buildFallbackNotification(item: AtkItem): Notification {
      ensureChannel()
      val open = alarmActivityIntent(item.id, (item.id * 7 + 11).toInt())
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
    var scheduled = 0
    for (item in items) {
      try {
        val before = nextMillis(item)
        schedule(item)
        // 仅统计真正排上了（启用且下次时间有效）的任务，用于一行汇总，不打印任务名。
        if (item.enabled && before > 0) scheduled++
      } catch (e: Exception) { }
    }
    try { showNextNotification(items) } catch (e: Exception) { }
    // 3.6.0：单行汇总，不再逐条打印任务名（隐私），也不在多调用点重复列出全部任务。
    AtkDiag.log(context, "排程: 已处理 ${items.size} 条任务（其中 $scheduled 条已排程）")
  }

  private fun alarmActivityIntent(id: Long, code: Int, force: Boolean = false): PendingIntent =
    PendingIntent.getActivity(
      context, code,
      Intent(context, AtkAlarmActivity::class.java).apply {
        // 3.5.0：对齐 SunriseAlarm 的 FSI/contentIntent flags（NEW_TASK | CLEAR_TASK），使执行界面
        // 成为独立 task root，挂锁屏时干净地盖在最上层，不会被叠进 MainActivity 的任务栈。
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        putExtra(EXTRA_ID, id)
        if (force) putExtra(EXTRA_FORCE, true)
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
   * The real firing notification: HIGH importance + full-screen intent. In 3.1.0 this is
   * mounted by the FOREGROUND SERVICE via startForeground() (SunriseAlarm's approach), NOT
   * posted from the background broadcast. Android 12+ only honours a full-screen intent when
   * the notification comes from an app that is actually in the foreground.
   *
   * 3.2.0 根因修复：3.1.0 参照 Smart Alarm 逆向结果给这条通知加了 `headsup=2` 的 extra。但在
   * ColorOS / 一加 13 上，这个 extra 会被当成「只弹顶部横幅（heads-up）」的强指令，把本应全屏
   * 的锁屏闹钟界面降级成一条小横幅——本机反复「只出通知、不亮屏、要解锁点通知才响」正是它造成的。
   * 同机的 SunriseAlarm 能用，靠的就是「不设这个 extra、只用 PRIORITY_HIGH + setFullScreenIntent、
   * 用 NotificationCompat 构建」。本次去掉 headsup，改用 NotificationCompat.PRIORITY_HIGH，并对齐
   * SunriseAlarm 的通知写法；循环防护仍交给 [launchAlarmFace] 的 dedupe 标志。
   *
   * 注意：不要再加 setOnlyAlertOnce（会抑制 full-screen intent），也不要再加任何 headsup extra。
   */
  fun buildAlarmNotification(item: AtkItem, force: Boolean = false, fullScreen: Boolean = true): Notification {
    ensureChannel()
    val fullIntent = alarmActivityIntent(item.id, (item.id * 7 + 3).toInt(), force)
    val snooze = actionIntent(ACTION_SNOOZE, item.id, (item.id * 7 + 4).toInt())
    val stop = actionIntent(ACTION_STOP, item.id, (item.id * 7 + 5).toInt())
    // 3.6.4: 屏幕模式 = 不点亮屏幕 (fullScreen=false) 用静默渠道，不弹横幅、不发全屏 intent，
    // 让「不点亮屏幕」真正生效（HIGH 渠道无论如何都会触发 heads-up 把屏点亮）。
    val channel = if (fullScreen) CHANNEL_ALARM_V4 else CHANNEL_ALARM_V5
    val builder = NotificationCompat.Builder(context, channel)
    builder
      .setSmallIcon(R.drawable.ic_atk_stat)
      .setContentTitle(item.name.ifBlank { "定时提醒" })
      .setContentText(alarmBody(item))
      .setStyle(NotificationCompat.BigTextStyle().bigText(alarmBody(item)))
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setContentIntent(fullIntent)
    if (fullScreen) {
      builder
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setFullScreenIntent(fullIntent, true)
    } else {
      builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }
    return builder
      .addAction(R.drawable.ic_atk_stat, "贪睡 10 分钟", snooze)
      .addAction(R.drawable.ic_atk_stat, "终止提醒", stop)
      .build()
  }

  /** Remove any stale alarm notification so the next post is a genuinely NEW alert. */
  fun cancelAlarmNotification(id: Long) {
    try {
      notifications.cancel(alarmNotificationId(id))
    } catch (e: Exception) {
    }
  }

  /**
   * Last-resort post of the full-screen notification, used ONLY when the foreground service
   * could not be started. The normal path is AtkAlarmService.startForeground().
   * 测试执行 passes false and gets the quiet status-bar notification on a *different* id, so
   * it can never turn the real alarm notification into a suppressed "update".
   */
  fun showNotification(item: AtkItem, fullScreen: Boolean = true, force: Boolean = false) {
    val alarm = fullScreen
    val notificationId = if (alarm) alarmNotificationId(item.id) else item.id.toInt()
    val n = if (alarm) {
      buildAlarmNotification(item, force)
    } else {
      ensureChannel()
      val open = alarmActivityIntent(item.id, (item.id * 7 + 11).toInt(), force)
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
      if (alarm) notifications.cancel(notificationId)
      notifications.notify(notificationId, n)
      AtkDiag.log(context, "全屏通知: 兜底 notify 已发出 id=${item.id} notif=$notificationId")
    } catch (e: SecurityException) {
      AtkDiag.log(context, "全屏通知: 兜底 notify 被拒 ${e.message}")
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
    val iconRes = when (iconPref) {
      "应用程序图标" -> R.mipmap.ic_launcher
      else -> R.drawable.ic_atk_stat
    }
    val n = builder
      .setSmallIcon(iconRes)
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

  /** Cancel both the 3.1.0 foreground-service alarm notification and the legacy quiet one. */
  fun dismiss(id: Long) {
    try { notifications.cancel(id.toInt()) } catch (e: Exception) { }
    cancelAlarmNotification(id)
  }

  /** Whether Android still lets this app launch a full-screen Activity over the keyguard. */
  fun canUseFullScreenIntent(): Boolean =
    if (Build.VERSION.SDK_INT >= 34) notifications.canUseFullScreenIntent() else true

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
      out.add("全屏通知" to canUseFullScreenIntent())
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      out.add("悬浮窗（后台弹出界面）" to Settings.canDrawOverlays(context))
    }
    return out
  }

  /**
   * ColorOS / 一加「自启动 / 后台运行」没有标准 Intent，且这是 setAlarmClock 被延迟的头号原因。
   * 优先尝试厂商自启动设置页，失败回退到应用详情页（用户可在其中找到自启动 / 后台运行 / 电池）。
   */
  fun openAutoStartSettings() {
    try {
      val oppo = Intent("com.coloros.safecenter.action.START_OPPO_SAFE_CENTER")
      oppo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(oppo)
    } catch (e: Exception) {
      openAppDetails()
    }
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

  /**
   * 3.6.4: 「后台运行（必做）」入口。各 ROM 没有统一的「允许后台运行」Intent，最稳妥的落点是应用
   * 详情页——用户可在其中找到自启动 / 后台运行 / 电池 / 通知等全部开关。
   */
  fun openBackgroundSettings() {
    openAppDetails()
  }
}
