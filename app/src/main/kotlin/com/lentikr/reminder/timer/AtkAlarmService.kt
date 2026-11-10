package com.lentikr.reminder.timer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps a firing alarm alive while the screen is locked.
 *
 * This service is the single fire entry point: the exact alarm's PendingIntent is created with
 * `PendingIntent.getForegroundService()` (see [AtkEngine.fireIntent]), so the system itself wakes
 * the app and grants the foreground-service start exemption that a background broadcast does not
 * reliably get on Android 12+. The broadcast path in [AtkReceiver] still exists for snooze and for
 * alarms armed by an older build, and simply delegates here.
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
    private const val WAKE_TIMEOUT_MS = 20 * 60 * 1000L
  }

  private var wakeLock: PowerManager.WakeLock? = null
  private var currentId = -1L
  private var foreground = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    AtkDiag.log(this, "svc: onCreate")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START
    val id = intent?.getLongExtra(AtkEngine.EXTRA_ID, -1L) ?: -1L
    val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
    AtkDiag.log(this, "svc: onStartCommand action=$action id=$id force=$force")

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
        AtkDiag.log(this, "svc: 任务已停用，但 60 秒测试强制触发 id=$id（base[10]=${item.base.getOrNull(10) ?: "?"}, 名称=${item.name}）")
      } else {
        AtkDiag.log(this, "svc: 任务已停用 id=$id（base[10]=${item.base.getOrNull(10) ?: "?"}, 名称=${item.name}）")
        stopEverything(startId)
        return START_NOT_STICKY
      }
    }

    currentId = id
    acquireWakeLock()
    engine.ensureChannel()

    // Play the tone FIRST so the mediaPlayback foreground service is genuinely playing media by
    // the time startForeground() claims that type. Android 14+ tears down a mediaPlayback FGS that
    // has no active media session within ~1s, which is exactly why the alarm went silent the
    // moment the app was backgrounded. The receiver may already have started this exact tone;
    // play() is a no-op in that case.
    AtkAlarmSound.play(this, item)

    val foregrounded = enterForeground(engine, item, id.toInt())
    if (!foregrounded) {
      // Being killed at the 5 s mark would cut the tone, so stop being a "started" service and
      // let the audio session (and the 执行界面) keep the process alive instead.
      AtkDiag.log(this, "svc: 无法进入前台，改为在本进程播铃")
    }

    engine.showNotification(item)
    runCatching { AtkStore(this).log(id, 1, item.name) }
    runCatching { engine.schedule(item) }
    runCatching { engine.refreshNext() }
    engine.launchAlarmFace(id)
    if (!foregrounded) stopSelf(startId)
    return START_NOT_STICKY
  }

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
   * permissions, and a mismatch throws instead of degrading. Try the most accurate type first
   * (mediaPlayback, because we really are playing audio), then specialUse, then the untyped
   * pre-34 call.
   */
  private fun enterForeground(engine: AtkEngine, item: AtkItem, notificationId: Int): Boolean {
    val notification = runCatching { engine.buildAlarmNotification(item) }.getOrNull()
    if (notification == null) {
      AtkDiag.log(this, "svc: 通知构建失败")
      return false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val attempts = listOf(
        "mediaPlayback|specialUse" to (
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
          ),
        "mediaPlayback" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        "specialUse" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
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

  private fun stopEverything(startId: Int) {
    AtkAlarmSound.stop()
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
