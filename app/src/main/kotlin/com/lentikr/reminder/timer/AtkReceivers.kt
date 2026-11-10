package com.lentikr.reminder.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
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
 */
object AtkAlarmSound {
  private var ringtone: Ringtone? = null
  private var vibrator: Vibrator? = null

  /**
   * Play the tone configured on [item] (act[6] 铃声类型 + act[7] 选择路径). Passing null keeps
   * the old behaviour of the system default alarm tone. Vibration always runs while the task
   * rings, matching the original 振动类型 default.
   */
  fun play(context: Context, item: AtkItem? = null) {
    stop()
    try {
      val uri = if (item != null) {
        AtkRingtone.resolve(context, item)
      } else {
        AtkRingtone.defaultAlarm(context)
      }
      if (uri != null) {
        val r = RingtoneManager.getRingtone(context, uri)
        if (r != null) {
          // Ringtone loops by default, but be explicit: some ROMs hand back a one-shot player.
          try { r.isLooping = true } catch (e: Exception) { }
          r.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
          r.play()
          ringtone = r
        }
      }
    } catch (e: Exception) {
      // No alarm tone configured on this device: vibration below still works.
    }
    try {
      val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
      }
      if (vib.hasVibrator()) {
        val pattern = longArrayOf(0, 500, 400, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
          @Suppress("DEPRECATION") vib.vibrate(pattern, 0)
        }
        vibrator = vib
      }
    } catch (e: Exception) {
    }
  }

  fun stop() {
    try { ringtone?.stop() } catch (e: Exception) { }
    ringtone = null
    try { vibrator?.cancel() } catch (e: Exception) { }
    vibrator = null
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
        if (item != null && item.enabled) {
          engine.ensureChannel()
          // Post the HIGH + full-screen notification first: it is the primary path that lets
          // Android bring the surface up over the keyguard, independent of the service start.
          engine.showNotification(item)
          startAlarmService(context, engine, id)
          store.log(id, 1, item.name)
          engine.schedule(item)
          engine.refreshNext()
        }
      }

      "app.lentikr.reminder.ATK_SNOOZE_FIRE" -> {
        val item = if (id >= 0) store.get(id) else null
        if (item != null && item.enabled) {
          engine.showNotification(item)
          startAlarmService(context, engine, id)
          store.log(id, 2, item.name + " 贪睡")
        }
      }

      AtkEngine.ACTION_SNOOZE -> {
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
        AtkAlarmSound.stop()
        stopAlarmService(context)
        engine.dismiss(id)
      }
    }
  }

  /**
   * A real firing runs through [AtkAlarmService]: a foreground service survives the broadcast and
   * keeps ringing while the screen is locked. Android 12+ can refuse a background FGS start, so
   * fall back to the notification + direct launch instead of dropping the alarm.
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
    } catch (e: Exception) {
      val item = runCatching { AtkStore(context).get(id) }.getOrNull() ?: return
      // FGS refused: at least start the tone from the receiver so a locked screen still rings.
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
          onMute = { AtkAlarmSound.stop(); engine.dismiss(id) },
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
