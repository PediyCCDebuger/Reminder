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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime

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
          engine.schedule(item)
        }
      }

      "app.lentikr.reminder.ATK_SNOOZE_FIRE" -> {
        val item = if (id >= 0) store.get(id) else null
        if (item != null && item.enabled) engine.showNotification(item)
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
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          AlarmFace(
            item = item,
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
}

@Composable
private fun AlarmFace(item: AtkItem?, onSnooze: () -> Unit, onStop: () -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("定时提醒", style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(24.dp))
    Text(
      item?.name ?: "定时提醒",
      style = MaterialTheme.typography.headlineLarge,
    )
    Spacer(Modifier.height(12.dp))
    Text(
      String.format("%02d:%02d", item?.hour ?: 0, item?.minute ?: 0),
      style = MaterialTheme.typography.displayMedium,
    )
    Spacer(Modifier.height(12.dp))
    Text(item?.rule?.describe() ?: "", style = MaterialTheme.typography.bodyLarge)
    if (!item?.message.isNullOrBlank()) {
      Spacer(Modifier.height(16.dp))
      Text(item!!.message, style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(Modifier.height(16.dp))
    Text(LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
      style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(48.dp))
    Button(onClick = onSnooze, Modifier.fillMaxWidth()) { Text("贪睡 10 分钟") }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text("终止提醒") }
  }
}
