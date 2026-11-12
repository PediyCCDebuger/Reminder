package com.lentikr.reminder.timer

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

/** A user-editable bottom-tab label. [page] value is always 100 + its position in the list. */
data class AtkTag(val name: String, val icon: String)

/**
 * Persistent tag (分类 / 标签) metadata.
 *
 * The original AddinTimer stores a task's tab in base[1] as 100..103, but stores the tab
 * names/icons separately. This build keeps that layout byte-for-byte compatible with `.atk`
 * backups while making the labels editable: names/icons live in SharedPreferences, and a
 * delete/reorder rewrites base[1] of the affected tasks so they stay with their label.
 */
object AtkTags {
  private const val PREFS = "atk_tags"
  private const val KEY = "list_v1"
  const val MAX = 16

  val DEFAULT = listOf(
    AtkTag("常用", "\uD83C\uDF3C"),
    AtkTag("生活", "\u2602\uFE0F"),
    AtkTag("纪念日", "\uD83D\uDCC5"),
    AtkTag("生日", "\uD83C\uDF88"),
  )

  /** Icons offered by 修改图标. Names are the emojis themselves, as in the original. */
  val ICONS = listOf(
    "\uD83C\uDF3C", "\u2602\uFE0F", "\uD83D\uDCC5", "\uD83C\uDF88", "\uD83C\uDF82",
    "⭐", "❤️", "\uD83C\uDFE0", "\uD83D\uDCBC", "\uD83C\uDFB5", "\uD83D\uDCDA",
    "\uD83C\uDF40", "\uD83C\uDF19", "☀️", "\uD83D\uDD14", "\uD83C\uDF81",
    "\uD83D\uDC3E", "\uD83C\uDF31", "\uD83D\uDD2E", "\uD83C\uDFC6",
  )

  private fun encode(s: String): String =
    Base64.encodeToString(s.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

  private fun decode(s: String): String = try {
    String(Base64.decode(s, Base64.NO_WRAP), StandardCharsets.UTF_8)
  } catch (e: Exception) {
    ""
  }

  fun load(context: Context): List<AtkTag> {
    val raw = try {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    } catch (e: Exception) {
      null
    } ?: return DEFAULT
    val out = ArrayList<AtkTag>()
    for (line in raw.split("\n")) {
      if (line.isBlank()) continue
      val parts = line.split(":", limit = 2)
      if (parts.size != 2) continue
      val name = decode(parts[0])
      val icon = decode(parts[1])
      if (name.isNotEmpty()) out.add(AtkTag(name, icon.ifEmpty { DEFAULT[0].icon }))
    }
    return out.take(MAX).ifEmpty { DEFAULT }
  }

  fun save(context: Context, tags: List<AtkTag>) {
    val raw = tags.take(MAX).joinToString("\n") { "${encode(it.name)}:${encode(it.icon)}" }
    try {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY, raw).apply()
    } catch (e: Exception) {
      // Preference write failure must never break the UI; state is still updated in memory.
    }
  }

  fun names(tags: List<AtkTag>): List<String> = tags.map { it.name }
}
