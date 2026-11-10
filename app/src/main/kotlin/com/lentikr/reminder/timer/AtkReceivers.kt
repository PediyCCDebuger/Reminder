package com.lentikr.reminder.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
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

  fun isPlaying(): Boolean = active

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
  }

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
      player?.let { if (it.isPlaying) it.stop() }
    } catch (e: Exception) {
    }
    try { player?.release() } catch (e: Exception) { }
    player = null
    try { ringtone?.stop() } catch (e: Exception) { }
    ringtone = null
    try { vibrator?.cancel() } catch (e: Exception) { }
    vibrator = null
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
        val item = if (id >= 0) store.get(id) else null
        AtkDiag.log(
          context,
          "触发 id=$id 任务=${item?.name ?: "查不到"} 启用=${item?.enabled}",
        )
        if (item != null && item.enabled) {
          // Sound first, straight from the receiver. If the process is torn down before the
          // service is up, the tone has already started instead of the alarm staying silent.
          AtkAlarmSound.play(context, item)
          engine.ensureChannel()
          engine.showNotification(item)
          startAlarmService(context, engine, id)
          store.log(id, 1, item.name)
          engine.schedule(item)
          engine.refreshNext()
        }
      }

      "app.lentikr.reminder.ATK_SNOOZE_FIRE" -> {
        val item = if (id >= 0) store.get(id) else null
        AtkDiag.log(context, "贪睡到点 id=$id 任务=${item?.name}")
        if (item != null && item.enabled) {
          AtkAlarmSound.play(context, item)
          engine.showNotification(item)
          startAlarmService(context, engine, id)
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
   * A real firing runs through [AtkAlarmService]: a foreground service survives the broadcast and
   * keeps ringing while the screen is locked. Android 12+ can refuse a background FGS start, so
   * the tone is already playing (see ACTION_FIRE) and the full-screen notification still opens
   * the 执行界面 if the service is rejected.
   */
  private fun startAlarmService(context: Context, engine: AtkEngine, id: Long) {
    val svc = Intent(context, AtkAlarmService::class.java).apply {
      putExtra(AtkAlarmService.EXTRA_ACTION, AtkAlarmService.ACTION_START)
      putExtra(AtkEngine.EXTRA_ID, id)
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(svc)
      } else {
        context.startService(svc)
      }
      AtkDiag.log(context, "前台服务: 启动请求已发出")
    } catch (e: Exception) {
      AtkDiag.log(context, "前台服务: 启动被拒 ${e.javaClass.simpleName}: ${e.message}")
      val item = runCatching { AtkStore(context).get(id) }.getOrNull() ?: return
      // FGS refused: the tone is already running in this process, just open the surface too.
      AtkAlarmSound.play(context, item)
      engine.showNotification(item)
      engine.launchAlarmFace(id)
    }
  }

  private fun stopAlarmService(context: Context) {
    try {
      context.stopService(Intent(context, AtkAlarmService::class.java))
    } catch (e: Exception) {
    }
  }
}

/** Full-screen alarm surface shown by the high-priority notification and 测试执行. */
class AtkAlarmActivity : ComponentActivity() {

  /** Held as state so a second alarm (onNewIntent) refreshes the visible task in place. */
  private val faceId = androidx.compose.runtime.mutableStateOf(-1L)

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
    faceId.value = intent.getLongExtra(AtkEngine.EXTRA_ID, -1L)
    val engine = AtkEngine(this)
    AtkDiag.log(this, "执行界面: 打开 id=${faceId.value}")
    AtkAlarmSound.play(this, loadItem(faceId.value))
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
      val item = remember(id) { loadItem(id) }
      AtkTheme {
        AlarmFace(
          item = item,
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

  private fun loadItem(id: Long): AtkItem? =
    if (id >= 0) runCatching { AtkStore(this).get(id) }.getOrNull() else null

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    faceId.value = intent.getLongExtra(AtkEngine.EXTRA_ID, -1L)
    AtkAlarmSound.play(this, loadItem(faceId.value))
  }

  override fun onDestroy() {
    AtkAlarmSound.stop()
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
