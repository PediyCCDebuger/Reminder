package com.lentikr.reminder.timer

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling, in-app log for the alarm chain.
 *
 * A "到点不响" report is impossible to diagnose from the outside: the bug can be the alarm
 * not firing, the receiver dying, the foreground service being refused, or the audio session
 * failing — all of which look identical to the user. Every step of the chain therefore writes
 * one line here, and 权限自检 → 诊断日志 shows the last 200 lines on screen so a screenshot is
 * enough to pinpoint the broken link.
 */
object AtkDiag {
  private const val PREF = "atk_diag"
  private const val KEY = "lines"
  private const val MAX_LINES = 200
  private const val SETTINGS = "atk_settings"
  private const val DEBUG_KEY = "debug"

  /**
   * The 调试 switch in 权限自检 gates ALL logging. OFF means the chain never writes a line.
   * 3.6.9：系统设置里新增的「执行日志」总开关同样作为闸门——关闭后触发日志与执行记录都不再写入，
   * 需要排查「到点不响」时把两个开关都打开即可。两个开关是 AND 关系，互不冲突。
   */
  fun isDebug(context: Context): Boolean =
    try {
      val sp = context.applicationContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
      sp.getBoolean(DEBUG_KEY, true) && sp.getString("exec_log", "开启") != "关闭"
    } catch (e: Exception) {
      true
    }

  fun log(context: Context, message: String) {
    if (!isDebug(context)) return
    try {
      val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
      val line = "$stamp  $message"
      Log.i("AtkDiag", line)
      val sp = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
      val old = sp.getString(KEY, "").orEmpty()
      val lines = (old.split('\n').filter { it.isNotBlank() } + line).takeLast(MAX_LINES)
      sp.edit().putString(KEY, lines.joinToString("\n")).apply()
    } catch (e: Exception) {
      // Diagnostics must never break the alarm itself.
    }
  }

  fun text(context: Context): String = try {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
      .ifBlank { "（暂无记录：等一次闹钟触发后再来看）" }
  } catch (e: Exception) {
    ""
  }

  fun clear(context: Context) {
    try {
      context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    } catch (e: Exception) {
    }
  }
}
