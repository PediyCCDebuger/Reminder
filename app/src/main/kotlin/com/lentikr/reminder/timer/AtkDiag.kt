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
   * 3.7.0：两个日志开关彻底分离。
   * - 触发日志（本对象写的 atk_diag 诊断链）**只受权限自检里的「调试」开关**控制；
   * - 执行日志（AtkStore.log 写的 actlog 表、查看日志）**只受系统设置里的「执行日志」开关**控制。
   * 3.6.9 曾把两者做成 AND 关系，导致关掉「执行日志」后连排查「到点不响」的触发日志也没了。
   */
  fun isDebug(context: Context): Boolean =
    try {
      context.applicationContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        .getBoolean(DEBUG_KEY, true)
    } catch (e: Exception) {
      true
    }

  /** 系统设置 → 常规设置 → 执行日志：只控制 actlog 执行记录，不影响触发日志。 */
  fun isExecLogEnabled(context: Context): Boolean =
    try {
      context.applicationContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        .getString("exec_log", "开启") != "关闭"
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
