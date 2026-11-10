package com.lentikr.reminder.timer

import android.content.ContentUris
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings

/**
 * Resolves the ringtone configured on an ALARM task.
 *
 * Reconstructed from GSW/AddinTimer/ad (27 ALARM action parameters):
 *   act[6] = 铃声类型 → 0 系统铃声 / 1 媒体库 / 2 随机音乐 / 3 指定音乐 / 4 录制音乐
 *   act[7] = 选择路径 → 「缺省铃音」/ 系统铃声 URI / 媒体库 content URI / 文件路径 / 手输文本
 *
 * Until 2.4.0 the player ignored both fields and always used the system default alarm tone,
 * which is why picking a custom song had no effect.
 */
object AtkRingtone {

  const val DEFAULT_LABEL = "缺省铃音"
  /** 系统铃声 list entry that means "keep silent". */
  const val SILENT_LABEL = "无"

  private fun rawPath(item: AtkItem): String = item.actValue(7).trim()

  /** URI to play, or null when this task is set to silence. */
  fun resolve(context: Context, item: AtkItem): Uri? {
    val type = AtkItem.intOf(item.actValue(6))
    val raw = rawPath(item)
    if (type == 2) return randomMusic(context) ?: defaultAlarm(context) // 随机音乐
    if (raw == SILENT_LABEL) return null
    if (raw.isEmpty() || raw == DEFAULT_LABEL) return defaultAlarm(context)
    val uri = runCatching { Uri.parse(raw) }.getOrNull()
    if (uri != null && !uri.scheme.isNullOrEmpty() && isReadable(context, uri)) return uri
    if (raw.startsWith("/")) {
      val f = java.io.File(raw)
      if (f.isFile) return Uri.fromFile(f)
    }
    return defaultAlarm(context)
  }

  fun defaultAlarm(context: Context): Uri =
    RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
      ?: Settings.System.DEFAULT_ALARM_ALERT_URI

  private fun isReadable(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
  } catch (e: Exception) {
    false
  }

  /** File name for a just-picked document (used by the confirmation toast). */
  fun nameOf(context: Context, uri: Uri): String {
    try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c ->
          if (c.moveToFirst() && !c.isNull(0)) {
            val name = c.getString(0)
            if (!name.isNullOrBlank()) return name
          }
        }
    } catch (e: Exception) {
    }
    return uri.lastPathSegment ?: "文件"
  }

  /** Label for the 选择路径 row: a file name, never a truncated content:// URI. */
  fun title(context: Context, item: AtkItem): String {
    val raw = rawPath(item)
    // The original shows a grey "点击设置" placeholder until a tone is picked.
    if (raw.isEmpty()) return "点击设置"
    if (raw == DEFAULT_LABEL) return DEFAULT_LABEL
    if (raw == SILENT_LABEL) return SILENT_LABEL
    val looksLikeUri = raw.startsWith("content://") || raw.startsWith("file://") ||
      raw.startsWith("/")
    if (!looksLikeUri) return raw
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return raw
    try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c ->
          if (c.moveToFirst() && !c.isNull(0)) {
            val name = c.getString(0)
            if (!name.isNullOrBlank()) return name
          }
        }
    } catch (e: Exception) {
      // Fall through to RingtoneManager / last path segment.
    }
    return try {
      RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: raw.substringAfterLast('/')
    } catch (e: Exception) {
      raw.substringAfterLast('/')
    }
  }

  /** 系统铃声 picker: 默认铃声 / 无 / every tone registered for TYPE_ALARM. */
  fun systemRingtones(context: Context): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    out.add("默认铃声" to DEFAULT_LABEL)
    out.add(SILENT_LABEL to SILENT_LABEL)
    try {
      val rm = RingtoneManager(context)
      rm.setType(RingtoneManager.TYPE_ALARM)
      val c = rm.cursor
      for (i in 0 until c.count) {
        if (!c.moveToPosition(i)) continue
        val title = c.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
        val uri = rm.getRingtoneUri(i)?.toString() ?: continue
        out.add(title to uri)
      }
      c.close()
    } catch (e: Exception) {
      // No ringtone provider on this ROM: the two fixed entries above still work.
    }
    return out
  }

  /** 媒体库 picker: every audio row in MediaStore. */
  fun mediaMusic(context: Context): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    try {
      context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
        null, null,
        MediaStore.Audio.Media.DISPLAY_NAME + " ASC",
      )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        while (c.moveToNext()) {
          val name = if (c.isNull(nameCol)) null else c.getString(nameCol)
          if (name.isNullOrBlank()) continue
          val id = c.getLong(idCol)
          val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
          out.add(name to uri.toString())
        }
      }
    } catch (e: Exception) {
    }
    return out
  }

  private fun randomMusic(context: Context): Uri? {
    val all = mediaMusic(context)
    if (all.isEmpty()) return null
    return runCatching { Uri.parse(all.random().second) }.getOrNull()
  }
}
