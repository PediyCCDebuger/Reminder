package com.lentikr.reminder.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
          engine.showNotification(item)
          store.log(id, 1, item.name)
          engine.schedule(item)
          engine.refreshNext()
        }
      }

      "app.lentikr.reminder.ATK_SNOOZE_FIRE" -> {
        val item = if (id >= 0) store.get(id) else null
        if (item != null && item.enabled) {
          engine.showNotification(item)
          store.log(id, 2, item.name + " 贪睡")
        }
      }

      AtkEngine.ACTION_SNOOZE -> {
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

      AtkEngine.ACTION_STOP -> engine.dismiss(id)
    }
  }
}

/** Full-screen alarm surface shown by the high-priority notification. */
class AtkAlarmActivity : ComponentActivity() {
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
    val id = intent.getLongExtra(AtkEngine.EXTRA_ID, -1L)
    val item = if (id >= 0) AtkStore(this).get(id) else null
    val engine = AtkEngine(this)
    setContent {
      AtkTheme {
        AlarmFace(
          item = item,
          onMute = { engine.dismiss(id) },
          onSnooze = {
            sendBroadcast(Intent(this, AtkReceiver::class.java).apply {
              action = AtkEngine.ACTION_SNOOZE
              putExtra(AtkEngine.EXTRA_ID, id)
            })
            finish()
          },
          onStop = {
            engine.dismiss(id)
            finish()
          },
        )
      }
    }
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

  Box(Modifier.fillMaxSize().background(Color(0xFF171717))) {
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
      Spacer(Modifier.height(64.dp))
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
      Row(
        Modifier
          .fillMaxWidth()
          .height(68.dp)
          .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF1A1A1A))))
          .border(1.dp, Color(0xFF555555)),
      ) {
        AlarmFaceButton("♪", "静音", Modifier.weight(1f), onMute)
        AlarmFaceButton("◷", "贪睡", Modifier.weight(1f), onSnooze)
        AlarmFaceButton("ⓘ", "终止", Modifier.weight(1f), onStop)
      }
    }
  }
}

@Composable
private fun AlarmFaceButton(symbol: String, label: String, modifier: Modifier, onClick: () -> Unit) {
  Box(
    modifier.fillMaxSize().clickable(onClick = onClick),
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
