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
    AtkDiag.log(this, "svc: onStartCommand action=$action id=$id")

    if (action == ACTION_STOP || action == ACTION_SNOOZE) {
      stopEverything(startId)
      return START_NOT_STICKY
    }

    val engine = AtkEngine(this)
    val item = if (id >= 0) runCatching { AtkStore(this).get(id) }.getOrNull() else null
    if (item == null || !item.enabled) {
      AtkDiag.log(this, "svc: 任务不存在或已停用，结束")
      stopEverything(startId)
      return START_NOT_STICKY
    }

    currentId = id
    acquireWakeLock()
    engine.ensureChannel()

    val foregrounded = enterForeground(engine, item, id.toInt())
    if (!foregrounded) {
      // Being killed at the 5 s mark would cut the tone, so stop being a "started" service and
      // let the audio session (and the 执行界面) keep the process alive instead.
      AtkDiag.log(this, "svc: 无法进入前台，改为在本进程播铃")
    }

    // The receiver may already have started this exact tone; play() is a no-op in that case.
    AtkAlarmSound.play(this, item)
    engine.showNotification(item)
    runCatching { AtkStore(this).log(id, 1, item.name) }
    runCatching { engine.schedule(item) }
    runCatching { engine.refreshNext() }
    engine.launchAlarmFace(id)
    if (!foregrounded) stopSelf(startId)
    return START_NOT_STICKY
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
    AtkDiag.log(this, "svc: onDestroy")
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
