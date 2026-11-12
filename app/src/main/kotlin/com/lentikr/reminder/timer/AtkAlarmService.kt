package com.lentikr.reminder.timer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps a firing alarm alive while the screen is locked.
 *
 * 3.1.0: the exact alarm's PendingIntent is a **broadcast** (see [AtkEngine.fireIntent]).
 * [AtkReceiver] only takes a wake lock, starts the tone and starts this service. THIS SERVICE
 * is the component that mounts the HIGH-importance full-screen notification, via
 * startForeground(), from a genuinely foreground process — the architecture used by
 * SunriseAlarm (verified working on this device) and by Smart Alarm's LaunchAlarm service.
 * Posting the notification from the background broadcast (3.0.0) was silently downgraded by
 * Android 12+/ColorOS, so the 执行界面 never came up over the keyguard.
 *
 * 3.6.0 根因修复（对齐 Smart Alarm-v2.7.7，本机验证可用）：ColorOS / 一加 13 连 setFullScreenIntent
 * 一并吞掉，导致 3.4.0/3.5.0 一直「到点不亮屏、要解锁点通知才响」。Smart Alarm 在本机可用，关键在
 * 于它**不用 FSI**，而是由前台服务（LaunchAlarm）在 startForeground 之后**直接 startActivity**
 * 拉起 AlarmActivity（flags=NEW_TASK|CLEAR_TASK），Activity 自身 setShowWhenLocked +
 * setTurnScreenOn + 屏幕唤醒锁负责点亮屏幕并盖在锁屏之上。因此本服务改为：进入前台后**立即直接
 * 拉起执行界面**作为主路径，FSI 通知仅作兜底（被吞也不影响）。
 *
 * Failure modes are made visible instead of silent: every startForeground attempt, the sound
 * start, the activity launch and the re-arm are written to [AtkDiag] so 权限自检 → 诊断日志 can
 * show exactly which link of the chain broke on the device.
 */
class AtkAlarmService : Service() {

  companion object {
    const val EXTRA_ACTION = "svc_action"
    const val ACTION_START = "svc_start"
    const val ACTION_STOP = "svc_stop"
    const val ACTION_SNOOZE = "svc_snooze"
    /** Set by 60 秒后真实触发 so the test fires the chain even on a disabled task. */
    const val EXTRA_FORCE = "svc_force"
    /** 标记本次 service 启动来自「贪睡重响」，用于自动贪睡计数跨重响延续、不重置。 */
    const val EXTRA_FROM_SNOOZE = "svc_from_snooze"
    private const val WAKE_TIMEOUT_MS = 20 * 60 * 1000L
    // 3.6.5：自动贪睡剩余次数（按闹钟 id 保存，跨贪睡重响仍有效）。
    private val autoSnoozeLeft = mutableMapOf<Long, Int>()
  }

  private var wakeLock: PowerManager.WakeLock? = null
  private var currentId = -1L
  private var foreground = false
  /** Avoid re-cancelling (and re-alerting) the same alarm when onCreate starts us again. */
  private var mountedId = -1L
  // 3.6.5：翻转感应（act[18]）由前台服务统一监听，无论是否点亮屏幕都生效。
  private var sensorManager: SensorManager? = null
  private var gravityListener: SensorEventListener? = null
  private var flipMode = 0
  // 3.6.5：电源键按下（act[25]）与自动贪睡（act[14]）。
  private var powerReceiver: BroadcastReceiver? = null
  private var autoSnoozeHandler: Handler? = null
  private var autoSnoozeTask: Runnable? = null
  private var snapDurMin = 10

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    AtkDiag.log(this, "svc: onCreate")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START
    val id = intent?.getLongExtra(AtkEngine.EXTRA_ID, -1L) ?: -1L
    val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
    val fromSnooze = intent?.getBooleanExtra(EXTRA_FROM_SNOOZE, false) ?: false
    snoozeStartId = startId
    AtkDiag.log(this, "svc: onStartCommand action=$action id=$id force=$force fromSnooze=$fromSnooze")

    if (action == ACTION_STOP || action == ACTION_SNOOZE) {
      stopEverything(startId)
      return START_NOT_STICKY
    }

    val engine = AtkEngine(this)
    val (item, dbError) = loadItem(id)
    if (item == null) {
      // Distinguish "row missing" from "database threw on a cold start" so the 权限自检 →
      // 触发日志 page can tell the user exactly which link broke (the common case on ColorOS is
      // a swallowed SQLite exception when the alarm fires and starts this service from cold).
      AtkDiag.log(
        this,
        "svc: 任务获取失败 id=$id " + (if (dbError != null) "错误=$dbError" else "（数据库中无此 id）"),
      )
      stopEverything(startId)
      return START_NOT_STICKY
    }
    if (!item.enabled) {
      if (force) {
        // 60 秒后真实触发 explicitly forces the ring chain even on a disabled task, so the user
        // can verify 响铃 / 渐响 / 执行界面 without first enabling it.
        AtkDiag.log(this, "svc: 任务已停用，但 60 秒测试强制触发 id=$id（base[10]=${item.base.getOrNull(10) ?: "?"}）")
      } else {
        AtkDiag.log(this, "svc: 任务已停用 id=$id（base[10]=${item.base.getOrNull(10) ?: "?"}）")
        stopEverything(startId)
        return START_NOT_STICKY
      }
    }

    currentId = id
    acquireWakeLock()
    engine.ensureChannel()
    // 3.6.4: 屏幕模式 = 不点亮屏幕 (act[15]=0) 时，不走全屏 intent、用静默渠道，让屏幕真正不亮。
    var lightScreen = AtkItem.intOf(item.actValue(15)) != 0
    // 3.6.5：全局「任务执行时电源模式」= 全唤醒 时，强制点亮屏幕（覆盖 per-timer act[15]）。
    if (engine.globalPowerForceLight()) lightScreen = true

    // 3.6.5：翻转感应（act[18]）由前台服务统一监听，无论是否点亮屏幕都生效。
    flipMode = AtkItem.intOf(item.actValue(18))
    if (flipMode != 0) registerFlipSensor(id)

    // Play the tone (idempotent: the activity that launched this service already started it, so
    // this is normally a no-op). Keeping the tone in one process-wide singleton means it survives
    // either component being torn down.
    AtkAlarmSound.play(this, item)

    // 3.1.0 DECISIVE STEP: mount the full-screen notification as THIS foreground service's
    // notification, exactly like SunriseAlarm's
    //   startForeground(NOTIF_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    // which is verified to wake this device from the lock screen. 3.0.0 posted it from the
    // background broadcast instead, where Android 12+ treats it as a silent refresh.
    // Cancel first so SystemUI sees a genuinely NEW alert (an update never fires the
    // full-screen intent), and never reuse the 测试执行 / persistent-icon notification id.
    val notifId = AtkEngine.alarmNotificationId(id)
    if (mountedId != id) {
      engine.cancelAlarmNotification(id)
      mountedId = id
    }
    val foregrounded = enterForeground(engine, item, notifId, force, lightScreen)
    AtkDiag.log(
      this,
      "全屏通知: startForeground 已挂载 id=$id notif=$notifId 前台=$foregrounded " +
        "全屏权限=${engine.canUseFullScreenIntent()} 点亮屏幕=$lightScreen",
    )
    if (!foregrounded) {
      // No foreground service at all: a plain notify() is the last remaining net.
      AtkDiag.log(this, "svc: 无法进入前台，改为兜底 notify + 本进程播铃")
      engine.showNotification(item, fullScreen = lightScreen, force = force)
    }
    // 3.6.0 主路径（对齐 Smart Alarm）：前台进程直接 startActivity 拉起执行界面。本机 ColorOS 连
    // FSI 都吞，但 Smart Alarm 用「前台服务直接 startActivity」在本机可用——前台进程发起的启动
    // 不会被当成后台启动，setTurnScreenOn + 屏幕唤醒锁（见 AtkAlarmActivity）能可靠点亮屏幕并盖在
    // 锁屏之上。dedupe=true 避免 Activity 自身 onCreate 又回头启动本服务时二次拉起造成闪屏。
    // FSI 通知（上方 startForeground 挂载）仅作兜底：被吞则静默失效，不影响本路径。
    // 3.6.4：屏幕模式 = 不点亮屏幕 (lightScreen=false) 时，不主动拉起执行界面（避免点亮屏幕），
    // 仅靠本服务的静默通知（V5 渠道）+ 播铃提醒，用户亮屏后点通知即可打开执行界面。
    if (lightScreen) engine.launchAlarmFace(id, force, dedupe = true)
    AtkDiag.log(this, "svc: 已直接拉起执行界面 id=$id（FSI 仅兜底，全屏权限=${engine.canUseFullScreenIntent()}）")

    runCatching { AtkStore(this).log(id, 1, item.name) }
    runCatching { engine.schedule(item) }
    runCatching { engine.refreshNext() }

    // 3.6.5：电源键按下（act[25]）与自动贪睡（act[14]）。
    // act[25]：暂停提醒 / 自动贪睡——响铃中按电源键的处理。
    // act[14]：未手动关闭时，每隔「预设时间(act[13])」自动贪睡，最多 N 次。
    val powerStyle = AtkItem.intOf(item.actValue(25)) // 0 暂停提醒 / 1 自动贪睡
    val snapRepeat = AtkItem.intOf(item.actValue(14)).coerceIn(0, 10) // 0 = 无，N = N 次
    val snapIdx = AtkItem.intOf(item.actValue(13)).coerceIn(0, AtkOptions.SNAP_DUR.lastIndex)
    snapDurMin = snapMinutes(AtkOptions.SNAP_DUR[snapIdx]) // 预设时间（存的是选项索引）
    if (powerStyle != -1) registerPowerReceiver(powerStyle, id)
    if (snapRepeat > 0) {
      // 真正触发（fromSnooze=false）时重置自动贪睡次数，开启新一轮；贪睡重响（fromSnooze=true）
      // 时保留已递减的次数，避免无限贪睡或次日失效。
      if (!fromSnooze || autoSnoozeLeft[id] == null) autoSnoozeLeft[id] = snapRepeat
      armAutoSnooze(id)
    } else {
      autoSnoozeLeft.remove(id)
    }
    return START_NOT_STICKY
  }

  /** 解析「预设时间」分钟数：act[13] 存的是选项索引，先经 SNAP_DUR 取到「N分钟」文案再转数字。 */
  private fun snapMinutes(v: String): Int {
    val n = v.filter { it.isDigit() }.toIntOrNull() ?: 10
    return n.coerceIn(1, 60)
  }

  /**
   * 3.6.5：自动贪睡——闹钟响铃 snapDurMin 分钟后若仍未被关闭，自动贪睡一次。
   * 剩余次数存于 companion，贪睡重响重启服务后仍可继续，直到用尽。
   */
  private fun armAutoSnooze(id: Long) {
    val left = autoSnoozeLeft[id] ?: return
    if (left <= 0) return
    cancelAutoSnooze()
    autoSnoozeHandler = Handler(Looper.getMainLooper())
    autoSnoozeTask = object : Runnable {
      override fun run() {
        // 仅当本闹钟仍在响铃（未被关闭）时才自动贪睡。
        if (currentId == id && AtkAlarmSound.isPlaying()) {
          AtkDiag.log(this@AtkAlarmService, "svc: 自动贪睡触发 id=$id 剩余=${left - 1}")
          val remain = left - 1
          autoSnoozeLeft[id] = remain
          AtkAlarmSound.stop()
          AtkEngine(this@AtkAlarmService).armSnooze(id)
          stopEverything(startIdForSnooze())
        }
      }
    }
    autoSnoozeHandler?.postDelayed(autoSnoozeTask, snapDurMin * 60L * 1000L)
    AtkDiag.log(this, "svc: 自动贪睡已挂 ${snapDurMin} 分钟 id=$id 剩余=$left")
  }

  private fun cancelAutoSnooze() {
    try { autoSnoozeTask?.let { autoSnoozeHandler?.removeCallbacks(it) } } catch (e: Exception) { }
    autoSnoozeTask = null
    autoSnoozeHandler = null
  }

  /**
   * 3.6.5：电源键按下监听。用 ACTION_SCREEN_OFF 作为电源键代理（按电源键会熄灭屏幕），
   * 在响铃中按下电源键按 act[25] 处理：暂停提醒=停止响铃并保留排程；自动贪睡=贪睡一次。
   */
  private fun registerPowerReceiver(powerStyle: Int, id: Long) {
    powerReceiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SCREEN_OFF && currentId == id) {
          AtkDiag.log(this@AtkAlarmService, "svc: 电源键按下（灭屏）powerStyle=$powerStyle id=$id")
          unregisterPowerReceiver()
          if (powerStyle == 1) {
            // 自动贪睡：停止响铃并挂 10 分钟重响。
            AtkAlarmSound.stop()
            AtkEngine(this@AtkAlarmService).armSnooze(id)
            stopEverything(startIdForSnooze())
          } else {
            // 暂停提醒：停止响铃，保留该闹钟的后续排程（含自动贪睡），不 dismiss。
            AtkAlarmSound.stop()
            cancelAutoSnooze()
            autoSnoozeLeft.remove(id)
            stopEverything(startIdForSnooze())
          }
        }
      }
    }
    try {
      registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
      AtkDiag.log(this, "svc: 电源键监听已注册 id=$id")
    } catch (e: Exception) {
      AtkDiag.log(this, "svc: 电源键监听注册失败 ${e.message}")
    }
  }

  private fun unregisterPowerReceiver() {
    try { powerReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
    powerReceiver = null
  }

  /** onStartCommand 携带的 startId 在回调外不可得，用此保存以便 stopEverything。 */
  private var snoozeStartId = 0
  private fun startIdForSnooze(): Int = snoozeStartId


  /**
   * Read the firing task from the database, retrying a few times because the alarm may have
   * started this service from a cold process where the first SQLite open briefly contends with a
   * just-released connection (a common ColorOS cold-start hiccup that previously surfaced as a
   * silent "任务不存在或已停用"). Uses the application context so the read never depends on the
   * service's own (possibly not-yet-initialised) context.
   *
   * Returns the item plus, on failure, the last exception message so the diagnostic log can show
   * the real reason instead of a generic "not found".
   */
  private fun loadItem(id: Long): Pair<AtkItem?, String?> {
    if (id < 0) return null to "id 无效"
    var lastErr: String? = null
    for (attempt in 0 until 3) {
      try {
        val item = AtkStore(applicationContext).get(id)
        if (item != null) return item to null
        lastErr = "数据库无此 id（行不存在）"
      } catch (e: Exception) {
        lastErr = "${e.javaClass.simpleName}: ${e.message}"
      }
      if (attempt < 2) {
        try {
          Thread.sleep(150)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
        }
      }
    }
    return null to lastErr
  }

  /**
   * Android 14+ verifies the foreground service type against the manifest and the granted
   * permissions, and a mismatch throws instead of degrading.
   *
   * 3.0.0: `mediaPlayback` is tried LAST. On OnePlus/ColorOS (Android 14+) a mediaPlayback FGS
   * without an active, system-visible MediaSession is judged "type abuse" and the service is
   * torn down about a second after startForeground — that kill took the ringtone with it, which
   * is exactly what the 2.8.0 log `svc: onDestroy（前台=true）` showed. Both reference apps
   * verified working on this device use `specialUse` (SunriseAlarm) / a plain started service
   * (Smart Alarm), so specialUse is now the primary type.
   */
  private fun enterForeground(
    engine: AtkEngine,
    item: AtkItem,
    notificationId: Int,
    force: Boolean,
    lightScreen: Boolean,
  ): Boolean {
    val notification = runCatching { engine.buildAlarmNotification(item, force, lightScreen) }
      .getOrNull()
    if (notification == null) {
      AtkDiag.log(this, "svc: 通知构建失败")
      return false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val attempts = listOf(
        "specialUse" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        "mediaPlayback|specialUse" to (
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
          ),
        "mediaPlayback" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      )
      for ((label, type) in attempts) {
        try {
          startForeground(notificationId, notification, type)
          foreground = true
          AtkDiag.log(this, "svc: startForeground 成功 type=$label")
          return true
        } catch (e: Exception) {
          AtkDiag.log(this, "svc: startForeground($label) 失败 ${e.javaClass.simpleName}: ${e.message}")
        }
      }
    }
    if (try {
        startForeground(notificationId, notification)
        true
      } catch (e: Exception) {
        AtkDiag.log(this, "svc: startForeground 无类型失败 ${e.javaClass.simpleName}: ${e.message}")
        false
      }
    ) {
      foreground = true
      AtkDiag.log(this, "svc: startForeground 无类型成功")
      return true
    }
    // Last resort: a plain, quiet notification. Being a foreground service at all is what keeps
    // the audio session — and therefore the ringing — alive.
    return try {
      startForeground(notificationId, runCatching { engine.buildFallbackNotification(item) }
        .getOrElse { notification })
      foreground = true
      AtkDiag.log(this, "svc: startForeground 简化通知成功")
      true
    } catch (e: Exception) {
      AtkDiag.log(this, "svc: startForeground 全部失败 ${e.javaClass.simpleName}: ${e.message}")
      false
    }
  }

  /**
   * 3.6.5：在前台服务（常驻）注册重力 / 加速度传感器，监听翻转（屏幕朝下 z<-7）。翻转后：
   * flipMode==1 → 贪睡（与执行界面「贪睡」一致）；flipMode==2 → 静音（停止响铃并 dismiss）。
   * 放在服务而非 Activity，是因为「不点亮屏幕」模式下 Activity 不会被创建，此前翻转失效。
   */
  private fun registerFlipSensor(id: Long) {
    try {
      val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
      val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
      sensorManager = sm
      var armed = true
      gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          val z = event.values[2]
          if (armed && z < -7f && flipMode != 0) {
            armed = false
            if (flipMode == 1) flipSnooze(id) else flipMute(id)
          } else if (z > 7f) {
            armed = true
          }
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
      }
      sm.registerListener(gravityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
      AtkDiag.log(this, "svc: 翻转感应已注册 flipMode=$flipMode")
    } catch (e: Exception) {
      AtkDiag.log(this, "svc: 翻转感应注册失败 ${e.message}")
    }
  }

  private fun unregisterFlipSensor() {
    try { sensorManager?.unregisterListener(gravityListener) } catch (e: Exception) { }
    sensorManager = null
    gravityListener = null
  }

  private fun flipSnooze(id: Long) {
    AtkDiag.log(this, "svc: 翻转贪睡 id=$id")
    unregisterFlipSensor()
    AtkAlarmSound.stop()
    // 3.6.5：直接挂 10 分钟贪睡重响（engine.armSnooze），并停止本前台服务。
    // 此前经 ACTION_SNOOZE 广播绕一圈再停服务，时序不确定，导致贪睡重响丢失、闹钟不再响。
    AtkEngine(this).armSnooze(id)
    stopSelf()
  }

  private fun flipMute(id: Long) {
    AtkDiag.log(this, "svc: 翻转静音 id=$id")
    unregisterFlipSensor()
    AtkAlarmSound.stop()
    runCatching { AtkEngine(this).dismiss(id) }
    stopSelf()
  }

  private fun stopEverything(startId: Int) {
    AtkAlarmSound.stop()
    unregisterFlipSensor()
    unregisterPowerReceiver()
    cancelAutoSnooze()
    releaseWakeLock()
    if (foreground) {
      try {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } catch (e: Exception) {
      }
      foreground = false
    }
    if (currentId >= 0) {
      runCatching { AtkEngine(this).dismiss(currentId) }
      currentId = -1L
    }
    mountedId = -1L
    stopSelf(startId)
  }

  private fun acquireWakeLock() {
    if (wakeLock != null) return
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AddinTimer:alarm").apply {
        setReferenceCounted(false)
        acquire(WAKE_TIMEOUT_MS)
      }
      AtkDiag.log(this, "svc: 已获取唤醒锁")
    } catch (e: Exception) {
      wakeLock = null
      AtkDiag.log(this, "svc: 唤醒锁失败 ${e.message}")
    }
  }

  private fun releaseWakeLock() {
    try {
      if (wakeLock?.isHeld == true) wakeLock?.release()
    } catch (e: Exception) {
    }
    wakeLock = null
  }

  override fun onDestroy() {
    // stopService() bypasses onStartCommand, so clean up here as well. The tone is deliberately
    // NOT stopped here: every explicit stop path (终止 / 贪睡 / 静音 / the notification actions)
    // already stops it, and destroying the service while the 执行界面 is still ringing must not
    // silence the alarm.
    // The foreground / wakelock flags tell 权限自检 → 触发日志 whether this was a normal stop
    // (foreground already false, e.g. 终止 / 贪睡) or an unexpected kill by the system / OEM
    // (foreground still true here means the FGS was torn down externally — the root cause of
    // "锁屏 / 退到后台就听不到闹钟").
    AtkDiag.log(this, "svc: onDestroy（前台=$foreground, 仍持唤醒锁=${wakeLock?.isHeld == true}）")
    unregisterFlipSensor()
    unregisterPowerReceiver()
    cancelAutoSnooze()
    releaseWakeLock()
    if (foreground) {
      try {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } catch (e: Exception) {
      }
      foreground = false
    }
    super.onDestroy()
  }
}
