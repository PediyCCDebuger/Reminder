package com.lentikr.reminder.timer

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Storage that is byte-for-byte compatible with an AddinTimer `.atk` backup: a plain SQLite
 * database with `timer(id INTEGER PRIMARY KEY, param TEXT)`. Import = read that table,
 * export = write that table.
 */
class AtkStore(private val context: Context) {

  private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, 1) {
    override fun onCreate(db: SQLiteDatabase) = createTables(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = createTables(db)

    private fun createTables(db: SQLiteDatabase) {
      db.execSQL("CREATE TABLE IF NOT EXISTS timer (id INTEGER PRIMARY KEY AUTOINCREMENT, param TEXT NOT NULL)")
      db.execSQL("CREATE TABLE IF NOT EXISTS setting (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, value TEXT)")
      db.execSQL("CREATE TABLE IF NOT EXISTS actlog (id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER, timerid INTEGER, state INTEGER, info TEXT)")
    }
  }

  private fun db(): SQLiteDatabase = helper.writableDatabase

  /** A single damaged row is skipped, never allowed to crash the whole list. */
  fun all(): List<AtkItem> {
    val out = ArrayList<AtkItem>()
    try {
      val c = db().rawQuery("SELECT id, param FROM timer ORDER BY id", null)
      c.use {
        while (it.moveToNext()) {
          val id = it.getLong(0)
          val raw = it.getString(1) ?: ""
          out.add(
            try {
              AtkItem.parse(id, raw)
            } catch (e: Exception) {
              AtkItem.parse(id, "")
            },
          )
        }
      }
    } catch (e: Exception) {
      // Database unavailable: show an empty list rather than crashing on startup.
    }
    return out
  }

  fun get(id: Long): AtkItem? = try {
    val c = db().rawQuery("SELECT param FROM timer WHERE id = ?", arrayOf(id.toString()))
    c.use { if (it.moveToFirst()) AtkItem.parse(id, it.getString(0) ?: "") else null }
  } catch (e: Exception) {
    null
  }

  fun save(item: AtkItem): Long {
    val values = ContentValues()
    values.put("param", item.param())
    if (item.id >= 0) {
      db().update("timer", values, "id = ?", arrayOf(item.id.toString()))
      return item.id
    }
    val id = db().insert("timer", null, values)
    item.id = id
    return id
  }

  fun delete(id: Long) {
    db().delete("timer", "id = ?", arrayOf(id.toString()))
  }

  fun clear() {
    db().delete("timer", null, null)
  }

  fun setEnabled(id: Long, on: Boolean) {
    val item = get(id) ?: return
    item.enabled = on
    save(item)
  }

  fun count(): Int = try {
    val c = db().rawQuery("SELECT COUNT(*) FROM timer", null)
    c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
  } catch (e: Exception) {
    0
  }

  /** The 18 built-in timers AddinTimer 2.1G creates on a fresh install. */
  fun seedSamples(replace: Boolean = false) {
    val local = db()
    if (!replace && count() > 0) return
    local.beginTransaction()
    try {
      if (replace) local.delete("timer", null, null)
      val values = ContentValues()
      for (p in AtkSamples.PARAMS) {
        values.clear()
        values.put("param", p)
        local.insert("timer", null, values)
      }
      local.setTransactionSuccessful()
    } finally {
      local.endTransaction()
    }
  }

  /**
   * The unregistered original refused to activate more than 5 timers, so old backups contain
   * many disabled rows. This build has no such cap; enable every imported timer while keeping
   * every other byte of the original parameter string untouched.
   */
  private fun enableParam(raw: String): String {
    val seg = BAR_SPLIT.split(raw, -1).toMutableList()
    // Only reminders are executed by this build; leave FM / SCENE rows exactly as backed up.
    if (seg.size < 2 || seg[0] != "ALARM") return raw
    val b = DOLLAR_SPLIT.split(seg[1], -1).toMutableList()
    if (b.size <= 10) return raw
    b[10] = "1"
    seg[1] = b.joinToString("$")
    return seg.joinToString("|")
  }

  /** Same actlog table the original app used; feeds 查看日志 in the long-press menu. */
  fun log(timerId: Long, state: Int, info: String) {
    val v = ContentValues()
    v.put("date", System.currentTimeMillis())
    v.put("timerid", timerId)
    v.put("state", state)
    v.put("info", info)
    db().insert("actlog", null, v)
  }

  fun logs(timerId: Long): List<String> {
    val out = ArrayList<String>()
    val c = db().rawQuery(
      "SELECT date, state, info FROM actlog WHERE timerid = ? ORDER BY id DESC LIMIT 20",
      arrayOf(timerId.toString()),
    )
    val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.CHINA)
    c.use {
      while (it.moveToNext()) {
        val whenText = try { fmt.format(java.util.Date(it.getLong(0))) } catch (e: Exception) { "?" }
        out.add("$whenText  ${it.getString(2) ?: ""} (${it.getInt(1)})")
      }
    }
    return out
  }

  /** Import an AddinTimer .atk (or any SQLite file with a timer/param table). Returns row count. */
  fun importAtk(uri: Uri, replace: Boolean): Int {
    val cache = File(context.cacheDir, "import_${System.currentTimeMillis()}.atk")
    val input = context.contentResolver.openInputStream(uri)
      ?: throw IllegalArgumentException("无法读取所选文件")
    input.use { src -> FileOutputStream(cache).use { output -> src.copyTo(output) } }
    val src = SQLiteDatabase.openDatabase(cache.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    val rows = ArrayList<String>()
    try {
      val c = src.rawQuery("SELECT param FROM timer ORDER BY id", null)
      c.use { while (it.moveToNext()) rows.add(enableParam(it.getString(0) ?: "")) }
    } finally {
      src.close()
      cache.delete()
    }
    val local = db()
    local.beginTransaction()
    try {
      if (replace) local.delete("timer", null, null)
      val values = ContentValues()
      for (p in rows) {
        values.clear()
        values.put("param", p)
        local.insert("timer", null, values)
      }
      local.setTransactionSuccessful()
    } finally {
      local.endTransaction()
    }
    return rows.size
  }

  /** Write a fresh, self-contained .atk file so it opens in the original AddinTimer too. */
  fun exportAtk(resolver: ContentResolver, uri: Uri): Int {
    val items = all()
    val tmp = File(context.cacheDir, "export_${System.currentTimeMillis()}.atk")
    if (tmp.exists()) tmp.delete()
    val out = SQLiteDatabase.openOrCreateDatabase(tmp.absolutePath, null)
    try {
      out.execSQL("CREATE TABLE IF NOT EXISTS timer (id INTEGER PRIMARY KEY AUTOINCREMENT, param TEXT NOT NULL)")
      out.execSQL("CREATE TABLE IF NOT EXISTS setting (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, value TEXT)")
      out.execSQL("CREATE TABLE IF NOT EXISTS actlog (id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER, timerid INTEGER, state INTEGER, info TEXT)")
      out.beginTransaction()
      val values = ContentValues()
      for (it in items) {
        values.clear()
        values.put("param", it.param())
        out.insert("timer", null, values)
      }
      values.clear()
      values.put("name", "ver")
      values.put("value", "2.1G")
      out.insert("setting", null, values)
      out.setTransactionSuccessful()
      out.endTransaction()
    } finally {
      out.close()
    }
    val os = resolver.openOutputStream(uri) ?: throw IllegalArgumentException("无法写入所选位置")
    os.use { stream -> tmp.inputStream().use { it.copyTo(stream) } }
    tmp.delete()
    return items.size
  }

  companion object {
    private const val DB_NAME = "at01.db"
    private val BAR_SPLIT =
      java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("|"))
    private val DOLLAR_SPLIT =
      java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("$"))
  }
}
