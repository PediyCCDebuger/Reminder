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
 * On Android 14+ the app must hold the user-granted 全屏通知 (USE_FULL_SCREEN_INTENT) permission
 * before a full-screen intent is allowed to launch an activity over the keyguard. When that
 * permission is missing the intent is silently downgraded to a plain notification — exactly the
 * "only rings after I unlock and tap the notification" symptom. A foreground service holds a
 * wake lock and plays the tone regardless, so the alarm is at least audible on a locked screen,
 * and the full-screen intent still opens the surface as soon as the permission is granted.
 */
class AtkAlarmService : Service() {

  companion object {
    const val EXTRA_ACTION = "svc_action"
    const val ACTION_START = "svc_start"
    const val ACTION_STOP = "svc_stop"
    const val ACTION_SNOOZE = "svc_snooze"
    private const val WAKE_TIMEOUT_MS = 15 * 60 * 1000L
  }

  private var wakeLock: PowerManager.WakeLock? = null
  private var currentId = -1L
  private var foreground = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START
    val id = intent?.getLongExtra(AtkEngine.EXTRA_ID, -1L) ?: -1L

    if (action == ACTION_STOP || action == ACTION_SNOOZE) {
      stopEverything(startId)
      return START_NOT_STICKY
    }

    val engine = AtkEngine(this)
    val item = if (id >= 0) runCatching { AtkStore(this).get(id) }.getOrNull() else null
    if (item == null) {
      stopEverything(startId)
      return START_NOT_STICKY
    }

    currentId = id
    acquireWakeLock()
    val notification = engine.buildAlarmNotification(item)
    val notificationId = id.toInt()
    foreground = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(
          notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
      } else {
        startForeground(notificationId, notification)
      }
      true
    } catch (e: Exception) {
      // Notification permission revoked or the ROM refuses specialUse: never leave the
      // process in the "started but not foreground" state, it would be killed with an ANR.
      try {
        startForeground(notificationId, notification)
        true
      } catch (ignored: Exception) {
        false
      }
    }
    if (!foreground) {
      stopEverything(startId)
      return START_NOT_STICKY
    }

    AtkAlarmSound.play(this, item)
    engine.launchAlarmFace(id)
    return START_NOT_STICKY
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
    } catch (e: Exception) {
      wakeLock = null
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
    // stopService() bypasses onStartCommand, so clean up here as well.
    AtkAlarmSound.stop()
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
