package com.lentikr.reminder.timer

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal crash recorder. The original AddinTimer had no such thing, but on modern Android
 * a device-only crash is otherwise invisible; the log is shown inside 关于软件 so it can be
 * reported without adb/root.
 */
object AtkCrash {
  private const val FILE = "atk_crash.log"
  private const val MAX_CHARS = 16000

  fun install(context: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      try {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val entry = "$stamp  ${thread.name}\n${Log.getStackTraceString(error)}\n\n"
        val file = File(context.applicationContext.filesDir, FILE)
        val old = if (file.exists()) file.readText() else ""
        file.writeText((entry + old).take(MAX_CHARS))
      } catch (ignored: Exception) {
      }
      previous?.uncaughtException(thread, error)
    }
  }

  fun last(context: Context): String = try {
    val file = File(context.applicationContext.filesDir, FILE)
    if (file.exists()) file.readText().take(1200) else ""
  } catch (e: Exception) {
    ""
  }

  fun clear(context: Context) {
    try { File(context.applicationContext.filesDir, FILE).delete() } catch (e: Exception) { }
  }
}
