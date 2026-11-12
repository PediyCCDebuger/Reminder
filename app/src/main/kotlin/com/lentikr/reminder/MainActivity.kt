package com.lentikr.reminder

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lentikr.reminder.data.settings.AppSettings
import com.lentikr.reminder.data.settings.SettingsRepository
import com.lentikr.reminder.timer.AtkEngine
import com.lentikr.reminder.timer.TimerApp
import com.lentikr.reminder.ui.theme.ReminderTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    // Keep the splash screen until settings are read, so the first frame uses the right theme.
    installSplashScreen().setKeepOnScreenCondition { viewModel.settings.value == null }
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // Android 13+ needs a runtime grant, otherwise neither the alarm nor the persistent
    // "下一次任务" notification can be posted at all.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }
    setContent {
      val settings by viewModel.settings.collectAsStateWithLifecycle()
      ReminderTheme(settings ?: AppSettings()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { TimerApp() }
      }
    }
    warnFullScreenOnce()
  }

  /**
   * Android 14+ silently downgrades full-screen intents unless the user grants 全屏通知, which
   * shows up as "the alarm only opens after I unlock and tap the notification". Ask once, then
   * leave the check to 系统设置 → 服务状态.
   */
  private fun warnFullScreenOnce() {
    if (Build.VERSION.SDK_INT < 34) return
    val nm = getSystemService(NotificationManager::class.java) ?: return
    if (nm.canUseFullScreenIntent()) return
    val prefs = getSharedPreferences("atk_settings", MODE_PRIVATE)
    if (prefs.getBoolean("full_screen_warned", false)) return
    prefs.edit().putBoolean("full_screen_warned", true).apply()
    AlertDialog.Builder(this)
      .setTitle("锁屏闹钟需要「全屏通知」权限")
      .setMessage(
        "Android 14 起应用默认不能在全屏或锁屏状态弹出界面。\n\n" +
          "请到「设置 → 应用 → 特殊权限 → 全屏通知」允许 Reminder。" +
          "否则闹钟到点只会出现在通知栏，需要解锁后点通知才会打开执行界面。\n\n" +
          "开启后可在 App 的「系统菜单 → 系统设置 → 服务状态」复查。",
      )
      .setPositiveButton("去开启") { _, _ -> AtkEngine(this).openFullScreenSettings() }
      .setNegativeButton("稍后", null)
      .show()
  }
}

@HiltViewModel
class MainViewModel @Inject constructor(settingsRepository: SettingsRepository) : ViewModel() {
  /** Null until the settings file has been read once. */
  val settings: StateFlow<AppSettings?> =
    settingsRepository.settings
      .map<AppSettings, AppSettings?> { it }
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
