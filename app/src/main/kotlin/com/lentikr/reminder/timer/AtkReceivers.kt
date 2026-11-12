package com.lentikr.reminder.timer

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
  // 3.6.5：渐强改为渐变「闹钟」系统流音量（跨 ROM 可闻）。保存原音量以便停止时恢复。
  private var audioManager: AudioManager? = null
  private var originalAlarmVolume = -1
  private var maxAlarmVolume = 1
  private var streamRampActive = false
  private var streamRampTask: Runnable? = null

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
  fun play(context: Context, item: AtkItem? = null, force: Boolean = false, delayMs: Long = 0, vibrateOverride: Int = -1) {
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

    // 3.6.5：渐强改用 AudioManager 渐变系统「闹钟」流音量——这是跨 ROM 真正可闻的渐强方式。
    // 部分机型 MediaPlayer / Ringtone 的 setVolume 被忽略，导致此前「一上来最大声」或「一直比较小、
    // 根本不渐强」。播放器/铃声仅按用户设定的相对音量(maxVol)出声，音量大小由闹钟流音量 envelope
    // 控制：从约 15% 渐变到用户当前的闹钟音量；停止时恢复原音量。
    val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    audioManager = am
    originalAlarmVolume = try { am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: -1 } catch (e: Exception) { -1 }
    maxAlarmVolume = try { am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 1 } catch (e: Exception) { 1 }
    val targetStream = if (originalAlarmVolume > 0) originalAlarmVolume else maxAlarmVolume
    val startStream = (targetStream * 0.15f).toInt().coerceAtLeast(1)
    streamRampActive = rampSeconds > 0 && am != null
    if (streamRampActive) rampStream(startStream, targetStream, rampSeconds)

    // 3.6.5：实际起播逻辑封装为 lambda，便于在「执行等待倒计时」下延迟执行。
    val beginTone: () -> Unit = {
      if (uri == null) {
        AtkDiag.log(app, "音: act[7]=「无」，只振动")
      } else if (startPlayer(app, uri, maxVol)) {
        AtkDiag.log(app, "音: MediaPlayer 起播 相对音量=${(maxVol * 100).toInt()}% 渐响(闹钟流)=$rampSeconds s")
      } else if (startRingtone(app, uri, maxVol)) {
        AtkDiag.log(app, "音: MediaPlayer 失败，回退 Ringtone 相对音量=${(maxVol * 100).toInt()}%")
      } else {
        AtkDiag.log(app, "音: 无法播放 $uri")
      }
      // 振动（act[12] 或全局「伴随振动模式」覆盖）。
      startVibration(app, item, if (vibrateOverride >= 0) vibrateOverride else null)
      // Activate the media session AFTER the player is up so the mediaPlayback foreground service
      // is genuinely playing media when startForeground claims that type.
      activateSession(app)
      // Safety net: never let a tone ring forever. Force-stop after 10 minutes from tone start.
      try {
        Handler(Looper.getMainLooper()).postDelayed({
          if (active && currentId == itemId) {
            AtkDiag.log(app, "音: 到达 10 分钟上限，自动停止")
            stop()
          }
        }, 10 * 60 * 1000L)
      } catch (e: Exception) {
      }
    }

    // 3.1.0: take the CPU wake lock so the tone keeps ringing (and, for 执行等待倒计时, the
    // countdown stays accurate) with the screen off.
    holdCpu(app)
    if (delayMs > 0) {
      AtkDiag.log(app, "音: 执行等待倒计时 ${delayMs}ms 后起播")
      Handler(Looper.getMainLooper()).postDelayed(beginTone, delayMs)
    } else {
      beginTone()
    }
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

  private fun startRingtone(context: Context, uri: android.net.Uri, volume: Float): Boolean = try {
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
      // 3.6.5：Ringtone 路径同样支持渐响（API 28+）。此前回退到 Ringtone 时一上来就是最大音量，
      // 正是「渐强被忽略」的根因——部分机型 MediaPlayer 播放自定义铃声 URI 会失败从而走此路径。
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try { r.setVolume(volume) } catch (e: Exception) { }
      }
      ringtone = r
      active = true
      true
    }
  } catch (e: Exception) {
    false
  }

  /**
   * 3.6.5：渐强核心——逐秒抬升系统「闹钟」流音量，从 startIdx 渐变到 targetIdx。
   * 这是跨 ROM 真正可闻的渐强（MediaPlayer/Ringtone.setVolume 在不少机型被忽略）。
   * 播放器/铃声本身保持用户在「音量大小」里设定的相对音量，音量起伏完全由该 envelope 控制。
   */
  private fun rampStream(startIdx: Int, targetIdx: Int, seconds: Int) {
    val am = audioManager ?: return
    val h = Handler(Looper.getMainLooper())
    var step = 0
    val task = object : Runnable {
      override fun run() {
        step++
        val v = (startIdx + (targetIdx - startIdx) * step.toFloat() / seconds).toInt()
          .coerceIn(0, maxAlarmVolume)
        try { am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } catch (e: Exception) { }
        if (step < seconds) h.postDelayed(this, 1000L) else streamRampActive = false
      }
    }
    streamRampTask = task
    try { am.setStreamVolume(AudioManager.STREAM_ALARM, startIdx, 0) } catch (e: Exception) { }
    h.postDelayed(task, 1000L)
  }

  /** 停止时把「闹钟」流音量恢复到用户原本的设置，避免污染其它闹钟。 */
  private fun restoreStreamVolume() {
    if (originalAlarmVolume > 0) {
      try { audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0) } catch (e: Exception) { }
    }
    originalAlarmVolume = -1
    streamRampActive = false
  }

  /** 振动类型 (act[12]): 0 无振动 / 1 跟随音乐起停 / 2..4 音乐前振动 10..30 秒.
   *  override >=0 时为全局「伴随振动模式」覆盖（无振动=0 / 短振动=1 / 长振动=4）。 */
  private fun startVibration(context: Context, item: AtkItem?, override: Int? = null) {
    val mode = override ?: (item?.let { AtkItem.intOf(it.actValue(12)) } ?: 1)
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
    try { streamRampTask?.let { handler?.removeCallbacks(it) } } catch (e: Exception) { }
    streamRampTask = null
    restoreStreamVolume()
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
          "触发 id=$id 启用=${item?.enabled} force=$force",
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
          // 3.6.5：通话中例外（全局 call）。
          val callMode = engine.globalCallMode()
          if (callMode != "无例外" && engine.isInCall()) {
            if (callMode == "停止执行") {
              AtkDiag.log(context, "触发: 通话中，按设置停止执行 id=$id")
              engine.schedule(item)
              engine.refreshNext()
              pending.finish()
              return
            } else if (callMode == "延迟5分钟执行") {
              AtkDiag.log(context, "触发: 通话中，按设置延迟5分钟 id=$id")
              engine.fireNowDelayed(id, 5 * 60 * 1000L)
              pending.finish()
              return
            }
          }
          // 1) Tone first: a killed/slow service must not leave the alarm silent.
          //    play() also acquires the CPU wake lock for the whole ringing period.
          AtkAlarmSound.play(
            context, item,
            delayMs = engine.globalWaitMs(),
            vibrateOverride = engine.globalVibrate(),
          )
          // 2) Start the FGS; IT mounts the full-screen notification via startForeground()
          //    AND directly starts the 执行界面 (3.6.0: see AtkAlarmService). A foreground
          //    process launch is the only path ColorOS honours for turning the screen on.
          startAlarmService(context, engine, id, force, fromSnooze = false)
          AtkDiag.log(context, "全屏通知: 已交给前台服务挂载并直接拉起执行界面 id=$id")
          store.log(id, 1, item.name)
          engine.schedule(item)
          engine.refreshNext()
        }
        // 3.6.0：移除 2.5s 看门狗。直接拉起改由前台服务（处于前台的进程）同步完成，不再依赖被
        // ColorOS 吞掉的 FSI、也不再由本广播延迟兜底。goAsync 的 CPU 窗口在启动前台服务后立即结束；
        // 铃声/CPU 唤醒锁由 AtkAlarmSound 持有，前台服务继续保活。
        pending.finish()
      }

      "app.lentikr.reminder.ATK_SNOOZE_FIRE" -> {
        val item = if (id >= 0) store.get(id) else null
        AtkDiag.log(context, "贪睡到点 id=$id 任务=${item?.name}")
        if (item != null && item.enabled) {
          engine.ensureChannel()
          // 3.6.5：通话中例外（全局 call）同样作用于贪睡重响。
          val callMode = engine.globalCallMode()
          if (callMode != "无例外" && engine.isInCall()) {
            if (callMode == "停止执行") {
              AtkDiag.log(context, "贪睡到点: 通话中，停止执行 id=$id")
              return
            } else if (callMode == "延迟5分钟执行") {
              AtkDiag.log(context, "贪睡到点: 通话中，延迟5分钟 id=$id")
              engine.fireNowDelayed(id, 5 * 60 * 1000L)
              return
            }
          }
          AtkAlarmSound.play(
            context, item,
            delayMs = engine.globalWaitMs(),
            vibrateOverride = engine.globalVibrate(),
          )
          startAlarmService(context, engine, id, false, fromSnooze = true)
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
        // 3.6.5：贪睡重响改回 setAlarmClock（与真实排程一致），保证深睡 / Doze 下也能准时唤醒，
        // 避免此前 setExactAndAllowWhileIdle 被延后投递导致「贪睡后再也不响」。
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
  private fun startAlarmService(
    context: Context,
    engine: AtkEngine,
    id: Long,
    force: Boolean,
    fromSnooze: Boolean = false,
  ) {
    val svc = Intent(context, AtkAlarmService::class.java).apply {
      putExtra(AtkAlarmService.EXTRA_ACTION, AtkAlarmService.ACTION_START)
      putExtra(AtkEngine.EXTRA_ID, id)
      putExtra(AtkAlarmService.EXTRA_FORCE, force)
      putExtra(AtkAlarmService.EXTRA_FROM_SNOOZE, fromSnooze)
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
 * Full-screen alarm surface. 3.6.0 起它的主拉起路径是**前台服务直接 startActivity**（对齐 Smart
 * Alarm-v2.7.7，本机验证可用）：前台进程发起的启动不会被 ColorOS 当成后台启动，配合本 Activity 的
 * setShowWhenLocked + setTurnScreenOn + 屏幕唤醒锁即可可靠点亮屏幕并盖在锁屏之上。setFullScreenIntent
 * 通知仅作兜底（本机实测被吞，故不再依赖）。`setAlarmClock()` 与「广播里直接 startActivity」两种做法
 * OnePlus/ColorOS 都会拦截，已弃用。测试执行仍从 APP 前台直接打开本界面。
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
    /** Records that this alarm's 执行界面 was launched, used to dedupe re-launches from the
     *  foreground service (3.6.0: the service launches directly; the activity's own onCreate may
     *  re-enter the service, so we skip a second launch within the gap window). */
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

    // 3.6.4：屏幕模式（act[15] LOCK_TYPE）。0=不点亮屏幕：不去点亮屏幕，仅响铃 + 静默通知；
    // 1/2=点亮屏幕（但不解锁 / 并解锁）。不点亮屏幕时仍允许显示在锁屏之上（showWhenLocked），
    // 这样用户亮屏后可直接看到执行界面，只是不会主动把屏幕点亮。
    var lightScreen = AtkItem.intOf(item.actValue(15)) != 0
    // 3.6.5：全局「任务执行时电源模式」= 全唤醒 时，强制点亮屏幕（覆盖 per-timer act[15]）。
    if (AtkEngine(this).globalPowerForceLight()) lightScreen = true
    if (lightScreen) {
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
    } else {
      // 不点亮屏幕：仅声明可显示在锁屏之上（亮屏后可见），不调用 turnScreenOn，也不取屏幕唤醒锁。
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(true)
      } else {
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
      }
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
    acquireWakeLock(lightScreen)
    try {
      val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      val interactive =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) pm.isInteractive
        else @Suppress("DEPRECATION") pm.isScreenOn
      AtkDiag.log(this, "执行界面: Activity 创建 锁屏=${km.isKeyguardLocked} 置顶=${isTaskRoot} 亮屏=$interactive 点亮屏幕=$lightScreen")
    } catch (e: Exception) {
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

  private fun acquireWakeLock(lightScreen: Boolean) {
    if (wakeLock != null) return
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      // 3.6.4：不点亮屏幕模式（lightScreen=false）不获取屏幕唤醒锁，从根本上避免主动点亮屏幕。
      if (!lightScreen) {
        AtkDiag.log(this, "执行界面: 不点亮屏幕模式，跳过屏幕唤醒锁")
        wakeLock = null
        return
      }
      // 3.3.0 根因修复：本机 ColorOS 在 Activity 由前台服务用 startActivity 直接拉起时，会无视
      // setTurnScreenOn / FLAG_TURN_SCREEN_ON 的「亮屏」提示——Activity 虽已创建并 onResume（日志
      // 可见），屏幕却一直是黑的。SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP 是跨 OEM 真正能
      // 强制点亮屏幕的做法：在 Activity 创建时（屏幕还关着）获取它，就立刻把显示点亮并保持常亮，
      // 直到用户关闭闹钟（onDestroy 释放）。FLAG_KEEP_SCREEN_ON 已挂在 window 上，配合它保持亮屏。
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

  val context = LocalContext.current
  // 3.6.5：翻转感应（act[18]）已移交前台服务 AtkAlarmService 统一监听，本界面不再读取。
  val uiStyle = AtkItem.intOf(item?.actValue(26) ?: "1") // 0 滑动方式 / 1 按钮方式
  val answerProblem = AtkItem.intOf(item?.actValue(21) ?: "0") // 0 关闭 / 1 简单 / 2 稍难
  val topText = context
    .getSharedPreferences("atk_settings", Context.MODE_PRIVATE)
    .getString("top_text", "AddinTimer Simplify your life") ?: "AddinTimer Simplify your life"

  var mathOpen by remember { mutableStateOf(false) }
  var mathProblem by remember { mutableStateOf<Pair<String, Int>?>(null) }
  var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

  // 点击 终止 / 静音 时，若开启算术题解锁，先弹题，答对才执行。
  fun doAction(action: () -> Unit) {
    if (answerProblem > 0) {
      pendingAction = action
      mathProblem = makeMathProblem(answerProblem)
      mathOpen = true
    } else {
      action()
    }
  }

  // 3.6.5：翻转感应（act[18]）已移至前台服务 AtkAlarmService 统一处理，使「不亮屏」模式下也能
  // 翻转贪睡 / 静音（此前放在 Activity 内，不亮屏时不创建 Activity 而失效）。此处不再重复注册，
  // 避免亮屏模式下服务与界面双重触发。

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
      // 3.6.4：顶部文字可自定义（设置项 top_text），默认为 "AddinTimer Simplify your life"。
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
        if (topText.isNotBlank()) {
          Text(topText, color = Color.White, fontSize = 17.sp)
        }
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
      // 3.6.4：界面样式（act[26]）。0=滑动方式：左右/上滑触发；1=按钮方式：三个按钮。
      if (uiStyle == 0) {
        Box(
          Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF1A1A1A))))
            .border(1.dp, Color(0xFF555555))
            .pointerInput(Unit) {
              var dx = 0f
              var dy = 0f
              detectDragGestures(
                onDragStart = { dx = 0f; dy = 0f },
                onDrag = { _, delta -> dx += delta.x; dy += delta.y },
                onDragEnd = {
                  if (dx * dx > dy * dy) {
                    if (dx > 60) doAction(onStop) else if (dx < -60) doAction(onSnooze)
                  } else if (dy < -60) {
                    doAction(onMute)
                  }
                },
              )
            },
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("滑动操作", color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text("← 贪睡    终止 →    ↑ 静音", color = Color(0xFFCFCFCF), fontSize = 15.sp)
          }
        }
      } else {
        // Three equal, divider-separated buttons with icon + label, matching the build the user
        // preferred over the icon-only variant.
        Row(
          Modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF1A1A1A))))
            .border(1.dp, Color(0xFF555555)),
        ) {
          AlarmFaceButton("♪", "静音", Modifier.weight(1f)) { doAction(onMute) }
          AlarmFaceDivider()
          // 3.6.5：贪睡同样经过算术题解锁门控，使「算术题解锁」真正生效（三个动作统一走 doAction）。
          AlarmFaceButton("◷", "贪睡", Modifier.weight(1f)) { doAction(onSnooze) }
          AlarmFaceDivider()
          AlarmFaceButton("ⓘ", "终止", Modifier.weight(1f)) { doAction(onStop) }
        }
      }
    }
  }

  // 3.6.4：算术题解锁弹窗（act[21]）。答对才执行 终止 / 静音 / 贪睡。
  if (mathOpen) {
    val problem = mathProblem
    if (problem != null) {
      AtkInputDialog(
        title = "算术题解锁",
        initial = "",
        numeric = true,
        message = problem.first,
        onConfirm = { t ->
          mathOpen = false
          if (t.toIntOrNull() == problem.second) {
            pendingAction?.invoke()
            pendingAction = null
          } else {
            Toast.makeText(context, "答案错误，请重试", Toast.LENGTH_SHORT).show()
          }
        },
        onDismiss = { mathOpen = false; pendingAction = null },
      )
    }
  }
}

/** 3.6.4：生成算术题（act[21]：1=简单 加法；2=稍难 乘法），返回 "题目" 与正确答案。 */
private fun makeMathProblem(level: Int): Pair<String, Int> {
  val rnd = java.util.Random()
  return if (level >= 2) {
    val a = rnd.nextInt(9) + 2 // 2..10
    val b = rnd.nextInt(9) + 2
    Pair("$a × $b = ?", a * b)
  } else {
    val a = rnd.nextInt(15) + 6 // 6..20
    val b = rnd.nextInt(a) // 0..a
    Pair("$a + $b = ?", a + b)
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
