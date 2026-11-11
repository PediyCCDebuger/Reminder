package com.lentikr.reminder.timer

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nnci.addintimer.Lunar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The alarm sound belongs to the 任务执行界面 itself, not to the notification channel: some
 * ROMs silently downgrade notification importance, and then the timer would fire with no sound.
 * A single shared player lets the notification actions (终止 / 贪睡) stop it from the receiver.
 *
 * 2.6.0 switched from [Ringtone] to [MediaPlayer] because the ALARM parameters require control
 * the Ringtone API does not offer:
 *   act[9]  音量大小  → maximum volume (不改变 / 10%..100%)
 *   act[10] 渐响模式  → ramp from ~10% of that volume to the maximum over 10..90 seconds
 *   act[12] 振动类型  → 无振动 / 跟随音乐起停 / 音乐前振动10–30秒
 * Ringtone can only start at full volume, which is exactly the "渐强被忽略，一上来就是最高音量"
 * complaint. MediaPlayer also takes a partial wake lock of its own (setWakeMode), so the tone
 * keeps playing with the screen off instead of being frozen a second after it starts.
 */
object AtkAlarmSound {
  private var player: MediaPlayer? = null
  private var ringtone: Ringtone? = null
  private var vibrator: Vibrator? = null
  private var handler: Handler? = null
  private var rampTask: Runnable? = null
  private var currentId = -1L
  private var active = false
  // Smart Alarm acquires a PARTIAL_WAKE_LOCK at the very top of AlarmReceiver.onReceive().
  // This singleton keeps it alive after the receiver returns, until the alarm is dismissed.
  private var cpuLock: PowerManager.WakeLock? = null
  // An active media session legitimates the mediaPlayback foreground service: without it,
  // Android 14+ kills the service ~1s after startForeground because the mediaPlayback type
  // is "misused" (no session). That kill took the ringtone down with it, which is exactly the
  // "锁屏 / 退到后台就听不到闹钟" symptom.
  private var mediaSession: MediaSession? = null

  fun isPlaying(): Boolean = active

  /** Hold the CPU awake from the alarm broadcast until the user dismisses the alarm. */
  fun holdCpu(context: Context) {
    try {
      if (cpuLock?.isHeld == true) return
      val pm = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as PowerManager
      cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AddinTimer:fire").apply {
        setReferenceCounted(false)
        acquire(20 * 60 * 1000L)
      }
      AtkDiag.log(context, "触发: 已获取 CPU 唤醒锁")
    } catch (e: Exception) {
      AtkDiag.log(context, "触发: CPU 唤醒锁失败 ${e.message}")
    }
  }

  private fun releaseCpu() {
    try {
      if (cpuLock?.isHeld == true) cpuLock?.release()
    } catch (e: Exception) {
    }
    cpuLock = null
  }

  /**
   * Play the tone configured on [item] (act[6] 铃声类型 + act[7] 选择路径). Passing null keeps
   * the old behaviour of the system default alarm tone.
   *
   * The call is idempotent for the same task: the receiver starts the tone first (so a locked
   * screen rings the instant the alarm fires), then the foreground service and later the
   * 执行界面 call play() again for the same task and must not restart it from the beginning.
   */
  fun play(context: Context, item: AtkItem? = null, force: Boolean = false) {
    val app = context.applicationContext
    val itemId = item?.id ?: -1L
    if (!force && active && currentId == itemId) {
      AtkDiag.log(app, "音: 任务 $itemId 正在播放，跳过重复调用")
      return
    }
    stop()
    currentId = itemId

    val uri = try {
      if (item != null) AtkRingtone.resolve(app, item) else AtkRingtone.defaultAlarm(app)
    } catch (e: Exception) {
      null
    }

    // 音量大小 (act[9]): 0 = 不改变 → full; n = n*10%.
    val volMode = item?.let { AtkItem.intOf(it.actValue(9)) } ?: 0
    val maxVol = if (volMode <= 0) 1f else (volMode * 10).coerceIn(10, 100) / 100f
    // 渐响模式 (act[10]): 0 = 立即; n = 渐响 n*10 秒.
    val rampMode = item?.let { AtkItem.intOf(it.actValue(10)) } ?: 0
    val rampSeconds = (rampMode * 10).coerceIn(0, 90)
    val startVol = if (rampSeconds > 0) (maxVol * 0.12f).coerceAtLeast(0.02f) else maxVol

    if (uri == null) {
      AtkDiag.log(app, "音: act[7]=「无」，只振动")
    } else if (startPlayer(app, uri, startVol)) {
      AtkDiag.log(app, "音: MediaPlayer 起播 目标音量=${(maxVol * 100).toInt()}% 渐响=${rampSeconds}s")
      if (rampSeconds > 0) scheduleRamp(startVol, maxVol, rampSeconds)
    } else if (startRingtone(app, uri)) {
      AtkDiag.log(app, "音: MediaPlayer 失败，回退 Ringtone（无渐响）")
    } else {
      AtkDiag.log(app, "音: 无法播放 $uri")
    }

    startVibration(app, item)
    // Safety net: never let a tone ring forever. If the ringing activity and the foreground
    // service are both gone but the process survives (e.g. the ROM killed the surface without
    // stopping the singleton player), force-stop after 10 minutes.
    try {
      Handler(Looper.getMainLooper()).postDelayed({
        if (active && currentId == itemId) {
          AtkDiag.log(app, "音: 到达 10 分钟上限，自动停止")
          stop()
        }
      }, 10 * 60 * 1000L)
    } catch (e: Exception) {
    }
    // Activate the media session AFTER the player is up so the mediaPlayback foreground service
    // is genuinely playing media when startForeground claims that type.
    activateSession(app)
    // 3.1.0: take the CPU wake lock LAST. play() calls stop() at its start, and stop() releases
    // this lock, so acquiring it before play() would silently undo it (Smart Alarm holds the
    // lock for the whole ringing period).
    holdCpu(app)
  }

  /**
   * Create and activate a media session while the alarm rings. Android 14+ only keeps a
   * `mediaPlayback` foreground service alive (and out of the "abusing FGS type" bucket) when an
   * active session exists; without it the system tears the service down within ~1 second, which
   * silenced the ringtone the moment the user left the app. The session is released in [stop()].
   */
  private fun activateSession(context: Context) {
    try {
      val ms = MediaSession(context.applicationContext, "AddinTimer:alarm")
      ms.setCallback(object : MediaSession.Callback() {})
      ms.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
      val state = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .setActions(PlaybackState.ACTION_PLAY)
        .build()
      ms.setPlaybackState(state)
      ms.isActive = true
      mediaSession?.release()
      mediaSession = ms
    } catch (e: Exception) {
      AtkDiag.log(context, "音: MediaSession 创建失败 ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  /** Exposed so the alarm notification can be linked to the session (Android 21+). */
  fun sessionToken(): MediaSession.Token? = try { mediaSession?.sessionToken } catch (e: Exception) { null }

  private fun startPlayer(context: Context, uri: android.net.Uri, volume: Float): Boolean = try {
    val p = MediaPlayer()
    p.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build(),
    )
    // Keeps the CPU alive for the whole ring even with the screen off.
    p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
    p.setDataSource(context, uri)
    p.isLooping = true
    p.setOnErrorListener { _, _, _ -> true }
    p.setVolume(volume, volume)
    p.prepare()
    p.start()
    player = p
    active = true
    true
  } catch (e: Exception) {
    AtkDiag.log(context, "音: MediaPlayer 异常 ${e.javaClass.simpleName}: ${e.message}")
    try { player?.release() } catch (ignored: Exception) { }
    player = null
    active = false
    false
  }

  private fun startRingtone(context: Context, uri: android.net.Uri): Boolean = try {
    val r = RingtoneManager.getRingtone(context, uri)
    if (r == null) {
      false
    } else {
      try { r.isLooping = true } catch (e: Exception) { }
      r.audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
      r.play()
      ringtone = r
      active = true
      true
    }
  } catch (e: Exception) {
    false
  }

  /** Raise MediaPlayer's own volume 1% per tick so 渐响 really starts quiet. */
  private fun scheduleRamp(from: Float, to: Float, seconds: Int) {
    val h = Handler(Looper.getMainLooper())
    handler = h
    var step = 0
    val task = object : Runnable {
      override fun run() {
        step++
        val ratio = step.toFloat() / seconds
        val v = (from + (to - from) * ratio).coerceIn(0f, 1f)
        try { player?.setVolume(v, v) } catch (e: Exception) { }
        if (step < seconds) h.postDelayed(this, 1000L)
      }
    }
    rampTask = task
    h.postDelayed(task, 1000L)
  }

  /** 振动类型 (act[12]): 0 无振动 / 1 跟随音乐起停 / 2..4 音乐前振动 10..30 秒. */
  private fun startVibration(context: Context, item: AtkItem?) {
    val mode = item?.let { AtkItem.intOf(it.actValue(12)) } ?: 1
    if (mode <= 0) return
    val vib: Vibrator = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
      }
    } catch (e: Exception) {
      return
    }
    try {
      if (!vib.hasVibrator()) return
      if (mode == 1) {
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 900), 0))
      } else {
        // 10 s per step: five 2-second cycles (450 ms on / 550 ms off).
        val cycles = mode * 5
        val pattern = LongArray(cycles * 2 + 1)
        pattern[0] = 0L
        for (i in 0 until cycles * 2) pattern[i + 1] = if (i % 2 == 0) 450L else 550L
        vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
      }
      vibrator = vib
    } catch (e: Exception) {
    }
  }

  fun stop() {
    try {
      rampTask?.let { handler?.removeCallbacks(it) }
    } catch (e: Exception) {
    }
    rampTask = null
    handler = null
    try {
      mediaSession?.release()
    } catch (e: Exception) {
    }
    mediaSession = null
    try {
      player?.let { if (it.isPlaying) it.stop() }
    } catch (e: Exception) {
    }
    try { player?.release() } catch (e: Exception) { }
    player = null
    try { ringtone?.stop() } catch (e: Exception) { }
    ringtone = null
    try { vibrator?.cancel() } catch (e: Exception) { }
    vibrator = null
    releaseCpu()
    active = false
    currentId = -1L
  }
}

class AtkReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    val store = AtkStore(context)
    val engine = AtkEngine(context)
    val id = intent.getLongExtra(AtkEngine.EXTRA_ID, -1L)

    when (action) {
      AtkEngine.ACTION_BOOT, Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
      Intent.ACTION_MY_PACKAGE_REPLACED -> {
        engine.ensureChannel()
        engine.rescheduleAll()
      }

      AtkEngine.ACTION_FIRE -> {
        val pending = goAsync()
        val force = intent.getBooleanExtra(AtkEngine.EXTRA_FORCE, false)
        val item = if (id >= 0) store.get(id) else null
        AtkDiag.log(
          context,
          "触发 id=$id 任务=${item?.name ?: "查不到"} 启用=${item?.enabled} force=$force",
        )
        if (item != null && (item.enabled || force)) {
          // 3.1.0 ROOT-CAUSE FIX. 3.0.0 posted the full-screen notification from THIS
          // background broadcast, on an id reused by 测试执行, with setOnlyAlertOnce(true).
          // Android 12+ only honours setFullScreenIntent for a genuinely NEW alerting
          // notification coming from an app in the foreground; a background re-post is treated
          // as a silent refresh, so SystemUI never launched the 执行界面. The log proved it:
          // "触发 / 全屏通知: 已从广播发出 / 执行界面: 已请求直接启动" all appeared, yet the
          // screen stayed dark until the user unlocked and tapped the notification.
          //
          // SunriseAlarm (verified working on this device) mounts the full-screen notification
          // as the FOREGROUND SERVICE's startForeground() notification; Smart Alarm posts it
          // from inside its LaunchAlarm service. So the notification now belongs to the
          // service, never to this broadcast.
          engine.ensureChannel()
          // 1) Tone first: a killed/slow service must not leave the alarm silent.
          //    play() also acquires the CPU wake lock for the whole ringing period.
          AtkAlarmSound.play(context, item)
          // 2) Start the FGS; IT mounts the full-screen notification via startForeground()
          //    and, once the process is foreground, raises the 执行界面 as a fallback.
          //    A direct startActivity here from the background is silently dropped on
          //    Android 10+ and would only consume the dedupe window.
          startAlarmService(context, engine, id, force)
          AtkDiag.log(context, "全屏通知: 已交给前台服务挂载 id=$id")
          store.log(id, 1, item.name)
          engine.schedule(item)
          engine.refreshNext()
        }
        // 3.4.0: 2.5s 看门狗。系统通过 FSI 把执行界面拉起来时，Activity 会标记 wasLaunched；
        // 若 2.5s 内仍未被拉起（被 ROM 吞掉 FSI），再退而求其次直接启动（解锁后仍可见，亮屏
        // 靠 Activity 自身唤醒锁兜底）。无论走哪条路，pending 都必须 finish 一次。
        val wId = id
        val wForce = intent.getBooleanExtra(AtkEngine.EXTRA_FORCE, false)
        Handler(Looper.getMainLooper()).postDelayed({
          try {
            if (!AtkAlarmActivity.wasLaunched(wId)) {
              AtkDiag.log(context, "执行界面: 2.5s 内 FSI 未拉起，启用直接启动兜底 id=$wId")
              engine.launchAlarmFace(wId, wForce, dedupe = false)
            }
          } finally {
            pending.finish()
          }
        }, 2500L)
      }

      "app.lentikr.reminder.ATK_SNOOZE_FIRE" -> {
        val item = if (id >= 0) store.get(id) else null
        AtkDiag.log(context, "贪睡到点 id=$id 任务=${item?.name}")
        if (item != null && item.enabled) {
          engine.ensureChannel()
          AtkAlarmSound.play(context, item)
          startAlarmService(context, engine, id, false)
          AtkDiag.log(context, "全屏通知: 已交给前台服务挂载（贪睡）id=$id")
          store.log(id, 2, item.name + " 贪睡")
        }
      }

      AtkEngine.ACTION_SNOOZE -> {
        AtkDiag.log(context, "贪睡 id=$id")
        AtkAlarmSound.stop()
        stopAlarmService(context)
        engine.dismiss(id)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
          context, (id * 7 + 6).toInt(),
          Intent(context, AtkReceiver::class.java).apply {
            this.action = "app.lentikr.reminder.ATK_SNOOZE_FIRE"
            putExtra(AtkEngine.EXTRA_ID, id)
          },
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + 10 * 60 * 1000L
        try {
          alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi)
        } catch (e: SecurityException) {
          alarms.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
      }

      AtkEngine.ACTION_STOP -> {
        AtkDiag.log(context, "终止 id=$id")
        AtkAlarmSound.stop()
        stopAlarmService(context)
        engine.dismiss(id)
      }
    }
  }

  /**
   * A real firing runs through [AtkAlarmService]: this is where the full-screen notification is
   * mounted (startForeground), because Android 12+ ignores a full-screen intent posted by a
   * background app. If the FGS start itself is refused, the broadcast falls back to a direct
   * notify() + startActivity as a last resort.
   */
  private fun startAlarmService(context: Context, engine: AtkEngine, id: Long, force: Boolean) {
    val svc = Intent(context, AtkAlarmService::class.java).apply {
      putExtra(AtkAlarmService.EXTRA_ACTION, AtkAlarmService.ACTION_START)
      putExtra(AtkEngine.EXTRA_ID, id)
      putExtra(AtkAlarmService.EXTRA_FORCE, force)
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(svc)
      } else {
        context.startService(svc)
      }
      AtkDiag.log(context, "前台服务: 启动请求已发出")
    } catch (e: Exception) {
      // Android 12+ / ColorOS may refuse a background FGS start. Without a service we have no
      // foreground context, so post the notification directly as the only remaining net.
      AtkDiag.log(context, "前台服务: 启动被拒 ${e.javaClass.simpleName}: ${e.message}")
      val item = runCatching { AtkStore(context).get(id) }.getOrNull() ?: return
      AtkAlarmSound.play(context, item)
      engine.showNotification(item, fullScreen = true, force = force)
      engine.launchAlarmFace(id, force, dedupe = true)
    }
  }

  private fun stopAlarmService(context: Context) {
    try {
      context.stopService(Intent(context, AtkAlarmService::class.java))
    } catch (e: Exception) {
    }
  }
}

/**
 * Full-screen alarm surface. It is **launched by SystemUI** through the full-screen intent of
 * the HIGH-importance notification the broadcast receiver posts (setFullScreenIntent), not by
 * `setAlarmClock()` and not by a background `startActivity()` — both of which OnePlus/ColorOS
 * block. This mirrors SunriseAlarm and Smart Alarm, the two apps verified working on the test
 * device. 测试执行 opens it directly from the foreground as before.
 *
 * It plays the tone itself (idempotent) and starts the foreground service for the persistent
 * notification + re-arm; dismissing this surface therefore does not silence an alarm the service
 * is still honouring (matches the system clock's behaviour when you swipe its surface away).
 */
class AtkAlarmActivity : ComponentActivity() {

  companion object {
    @Volatile
    private var launchedId = -1L
    @Volatile
    private var launchedAt = 0L
    /** True when this alarm's 执行界面 was actually launched by the system (via FSI) recently. */
    fun wasLaunched(id: Long): Boolean =
      launchedId == id && System.currentTimeMillis() - launchedAt < 60_000L
    fun markLaunched(id: Long) {
      launchedId = id
      launchedAt = System.currentTimeMillis()
    }
  }

  /** Held as state so a second alarm (onNewIntent) refreshes the visible task in place. */
  private val faceId = androidx.compose.runtime.mutableStateOf(-1L)
  private val faceForce = androidx.compose.runtime.mutableStateOf(false)
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
      )
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // Android 15+ forces edge-to-edge. Ask for insets explicitly so Compose's
    // statusBarsPadding()/navigationBarsPadding() work on every ROM and API level.
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    // Theme.Reminder.Alarm already sets black system bars with light icons.

    // 3.2.0 诊断：确认「执行界面」到底有没有被系统真正拉起来、当时是否锁屏。如果这条日志出现
    // 但屏幕仍是黑的，说明是渲染 / 主题问题；如果连这条都没有，说明 FSI / 后台 startActivity 被
    // 系统或 OEM 拦下，需要走 SYSTEM_ALERT_WINDOW 悬浮窗兜底（下个版本）。
    // 3.3.0：新增「亮屏」诊断。锁屏=false 且亮屏=false = 设备睡着了但没锁屏，本应被亮屏唤醒锁
    // 点亮；若 3.3.0 后 onResume 的 亮屏 仍为 false，才是 OEM 连唤醒锁都吞了，届时再上悬浮窗兜底。
    acquireWakeLock()
    try {
      val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      val interactive =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) pm.isInteractive
        else @Suppress("DEPRECATION") pm.isScreenOn
      AtkDiag.log(this, "执行界面: Activity 创建 锁屏=${km.isKeyguardLocked} 置顶=${isTaskRoot} 亮屏=$interactive")
    } catch (e: Exception) {
    }

    faceId.value = intent.getLongExtra(AtkEngine.EXTRA_ID, -1L)
    faceForce.value = intent.getBooleanExtra(AtkEngine.EXTRA_FORCE, false)
    markLaunched(faceId.value)
    AtkDiag.log(this, "执行界面: 打开 id=${faceId.value} force=${faceForce.value}")

    val item = loadItem(faceId.value)
    if (item == null) {
      AtkDiag.log(this, "执行界面: 任务获取失败 id=${faceId.value}")
      finish()
      return
    }
    if (!item.enabled && !faceForce.value) {
      AtkDiag.log(this, "执行界面: 任务已停用，不响 id=${faceId.value}（base[10]=${item.base.getOrNull(10) ?: "?"}）")
      finish()
      return
    }

    // Play the tone immediately, straight from this (foreground, lock-screen) activity. The tone
    // is a process-wide singleton, so it keeps ringing even if this surface is dismissed while the
    // foreground service — and its notification — are still up.
    AtkAlarmSound.play(this, item)
    // Start the foreground service for the persistent notification + re-arm. Because it is started
    // FROM a visible activity, the ROM treats it as foreground-started and does not tear it down
    // the way a cold background FGS start gets killed (the original cause of "锁屏听不到闹钟").
    startAlarmService(item.id, faceForce.value)
    val engine = AtkEngine(this)
    runCatching { AtkStore(this).log(item.id, 1, item.name) }
    runCatching { engine.schedule(item) }
    runCatching { engine.refreshNext() }

    // Back also terminates the current alarm, exactly like the 终止 button.
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        AtkAlarmSound.stop()
        stopService(Intent(this@AtkAlarmActivity, AtkAlarmService::class.java))
        AtkEngine(this@AtkAlarmActivity).dismiss(faceId.value)
        finish()
      }
    })
    setContent {
      val id by faceId
      val fitem = remember(id) { loadItem(id) }
      AtkTheme {
        AlarmFace(
          item = fitem,
          // 静音 only silences the tone; the 执行界面 stays up so 贪睡 / 终止 are still reachable.
          // The foreground service is stopped with it, otherwise cancelling its notification
          // from here would leave a "foreground without notification" service behind.
          onMute = {
            AtkAlarmSound.stop()
            stopService(Intent(this@AtkAlarmActivity, AtkAlarmService::class.java))
            engine.dismiss(id)
          },
          onSnooze = {
            AtkAlarmSound.stop()
            stopService(Intent(this@AtkAlarmActivity, AtkAlarmService::class.java))
            sendBroadcast(Intent(this, AtkReceiver::class.java).apply {
              action = AtkEngine.ACTION_SNOOZE
              putExtra(AtkEngine.EXTRA_ID, id)
            })
            finish()
          },
          onStop = {
            AtkAlarmSound.stop()
            stopService(Intent(this@AtkAlarmActivity, AtkAlarmService::class.java))
            engine.dismiss(id)
            finish()
          },
        )
      }
    }
  }

  private fun startAlarmService(id: Long, force: Boolean) {
    val svc = Intent(this, AtkAlarmService::class.java).apply {
      putExtra(AtkAlarmService.EXTRA_ACTION, AtkAlarmService.ACTION_START)
      putExtra(AtkEngine.EXTRA_ID, id)
      putExtra(AtkAlarmService.EXTRA_FORCE, force)
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
      AtkDiag.log(this, "执行界面: 前台服务启动请求已发出 id=$id")
    } catch (e: Exception) {
      AtkDiag.log(this, "执行界面: 前台服务启动被拒 ${e.javaClass.simpleName}: ${e.message}")
    }
  }

  /** Read the firing task, retrying a few times to survive a cold-start SQLite hiccup. */
  private fun loadItem(id: Long): AtkItem? {
    if (id < 0) return null
    var lastErr: String? = null
    for (attempt in 0 until 3) {
      try {
        val item = AtkStore(applicationContext).get(id)
        if (item != null) return item
        lastErr = "数据库无此 id"
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
    return null
  }

  private fun acquireWakeLock() {
    if (wakeLock != null) return
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      // 3.3.0 根因修复：3.2.0 只用了 setTurnScreenOn(true) / FLAG_TURN_SCREEN_ON，但本机
      // ColorOS 在一加 13 上，当 Activity 是由本 App 自己的前台服务用 startActivity 直接拉起
      // （而不是 SystemUI 通过全屏 intent 拉起）时，会无视这个「亮屏」提示——于是 Activity 虽
      // 已被创建并 onResume（日志可见），屏幕却一直是黑的，用户什么也看不到。
      // SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP 是跨 OEM 真正能强制点亮屏幕的做法：
      // 只要在本 Activity 创建时（屏幕还关着）获取它，就会立刻把显示点亮并保持常亮，直到用户
      // 关闭闹钟（onDestroy 释放）。FLAG_KEEP_SCREEN_ON 已挂在 window 上，配合它保持亮屏。
      wakeLock = pm.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "AddinTimer:alarm_face",
      ).apply { setReferenceCounted(false); acquire(20 * 60 * 1000L) }
      AtkDiag.log(this, "执行界面: 已获取屏幕唤醒锁（亮屏+唤醒）")
    } catch (e: Exception) {
      wakeLock = null
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val newId = intent.getLongExtra(AtkEngine.EXTRA_ID, -1L)
    faceId.value = newId
    faceForce.value = intent.getBooleanExtra(AtkEngine.EXTRA_FORCE, false)
    AtkDiag.log(this, "执行界面: onNewIntent id=$newId")
    val item = loadItem(newId) ?: return
    AtkAlarmSound.play(this, item)
    startAlarmService(newId, faceForce.value)
  }

  override fun onResume() {
    super.onResume()
    val interactive = try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) pm.isInteractive
      else @Suppress("DEPRECATION") pm.isScreenOn
    } catch (e: Exception) { false }
    AtkDiag.log(this, "执行界面: onResume 已可见 id=${faceId.value} 亮屏=$interactive")
  }

  override fun onDestroy() {
    // Do NOT stop the tone here: the foreground service (and this surface's singleton player) may
    // legitimately keep ringing after the activity is dismissed. The explicit 终止 / 静音 / 贪睡
    // buttons stop it. A 10-minute hard cap in AtkAlarmSound bounds the worst case.
    AtkDiag.log(this, "执行界面: onDestroy id=${faceId.value}")
    try {
      if (wakeLock?.isHeld == true) wakeLock?.release()
    } catch (e: Exception) {
    }
    wakeLock = null
    super.onDestroy()
  }
}

/** The original full-screen 任务执行界面: title strip, date/lunar/clock, task name, 3 round buttons. */
@Composable
private fun AlarmFace(item: AtkItem?, onMute: () -> Unit, onSnooze: () -> Unit, onStop: () -> Unit) {
  val now = LocalDateTime.now()
  val solar = LocalDate.now()
  val lunar = try { Lunar.fromSolar(solar) } catch (e: Exception) { null }
  val lunarText = if (lunar != null) {
    val d = lunar[2]
    val day = when {
      d <= 10 -> "初" + lunarDay(d)
      d == 20 -> "二十"
      d == 30 -> "三十"
      d < 20 -> "十" + lunarDay(d - 10)
      else -> "廿" + lunarDay(d - 20)
    }
    "阴历" + (if (lunar[3] == 1) "闰" else "") + Lunar.monthName(lunar[1]) + day
  } else {
    ""
  }

  // Android 15+ enforces edge-to-edge: without these paddings the title strip collides with
  // the status bar and the bottom buttons sit underneath the 3-button navigation bar.
  Box(
    Modifier
      .fillMaxSize()
      .background(Color(0xFF171717))
      .statusBarsPadding()
      .navigationBarsPadding(),
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      drawPath(
        Path().apply {
          moveTo(0f, h * 0.22f); lineTo(w / 2f, h * 0.42f); lineTo(0f, h * 0.62f); close()
        },
        color = Color(0xFF242424),
      )
      drawPath(
        Path().apply {
          moveTo(w, h * 0.22f); lineTo(w / 2f, h * 0.42f); lineTo(w, h * 0.62f); close()
        },
        color = Color(0xFF202020),
      )
    }
    Column(Modifier.fillMaxSize()) {
      // AddinTimer Simplify your life
      Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Image(
          painter = painterResource(com.lentikr.reminder.R.drawable.ic_atk_stat),
          contentDescription = null,
          colorFilter = ColorFilter.tint(AtkSkin.green),
          modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("AddinTimer Simplify your life", color = Color.White, fontSize = 17.sp)
      }
      Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF4A4A4A)))
      Spacer(Modifier.height(14.dp))
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            solar.format(DateTimeFormatter.ofPattern("yyyy/M/d")),
            color = Color.White, fontSize = 19.sp,
          )
          Spacer(Modifier.height(4.dp))
          Text(lunarText, color = Color.White, fontSize = 17.sp)
          Spacer(Modifier.height(4.dp))
          Text(
            solar.format(DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)),
            color = Color.White, fontSize = 17.sp,
          )
        }
        Text(
          now.format(DateTimeFormatter.ofPattern("HH:mm")),
          color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light,
        )
      }
      Spacer(Modifier.height(18.dp))
      Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A3A3A)))
      Spacer(Modifier.height(48.dp))
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
          item?.name?.ifBlank { "定时提醒" } ?: "定时提醒",
          color = Color.White, fontSize = 26.sp,
        )
      }
      if (!item?.message.isNullOrBlank()) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Text(item?.message ?: "", color = Color(0xFFCFCFCF), fontSize = 17.sp)
        }
      }
      Spacer(Modifier.weight(1f))
      // Three equal, divider-separated buttons with icon + label, matching the build the user
      // preferred over the icon-only variant.
      Row(
        Modifier
          .fillMaxWidth()
          .height(68.dp)
          .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF1A1A1A))))
          .border(1.dp, Color(0xFF555555)),
      ) {
        AlarmFaceButton("♪", "静音", Modifier.weight(1f), onMute)
        AlarmFaceDivider()
        AlarmFaceButton("◷", "贪睡", Modifier.weight(1f), onSnooze)
        AlarmFaceDivider()
        AlarmFaceButton("ⓘ", "终止", Modifier.weight(1f), onStop)
      }
    }
  }
}

@Composable
private fun AlarmFaceDivider() {
  Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF555555)))
}

@Composable
private fun AlarmFaceButton(symbol: String, label: String, modifier: Modifier, onClick: () -> Unit) {
  Box(
    modifier.fillMaxHeight().clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier
          .size(34.dp)
          .clip(CircleShape)
          .border(2.dp, AtkSkin.green, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Text(symbol, color = AtkSkin.green, fontSize = 20.sp)
      }
      Spacer(Modifier.width(6.dp))
      Text(label, color = Color(0xFFDDDDDD), fontSize = 14.sp)
    }
  }
}

private fun lunarDay(d: Int): String {
  val n = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
  return n.getOrElse(d) { d.toString() }
}
