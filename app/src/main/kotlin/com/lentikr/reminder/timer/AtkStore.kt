package com.lentikr.reminder.timer

import android.content.ContentResolver
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
    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL("CREATE TABLE IF NOT EXISTS timer (id INTEGER PRIMARY KEY AUTOINCREMENT, param TEXT NOT NULL)")
      db.execSQL("CREATE TABLE IF NOT EXISTS setting (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, value TEXT)")
      db.execSQL("CREATE TABLE IF NOT EXISTS actlog (id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER, timerid INTEGER, state INTEGER, info TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      db.execSQL("CREATE TABLE IF NOT EXISTS timer (id INTEGER PRIMARY KEY AUTOINCREMENT, param TEXT NOT NULL)")
      db.execSQL("CREATE TABLE IF NOT EXISTS setting (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, value TEXT)")
      db.execSQL("CREATE TABLE IF NOT EXISTS actlog (id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER, timerid INTEGER, state INTEGER, info TEXT)")
    }
  }

  private fun db(): SQLiteDatabase = helper.writableDatabase

  fun all(): List<AtkItem> {
    val out = ArrayList<AtkItem>()
    val c = db().rawQuery("SELECT id, param FROM timer ORDER BY id", null)
    c.use {
      while (it.moveToNext()) out.add(AtkItem.parse(it.getLong(0), it.getString(1) ?: ""))
    }
    return out
  }

  fun get(id: Long): AtkItem? {
    val c = db().rawQuery("SELECT param FROM timer WHERE id = ?", arrayOf(id.toString()))
    c.use {
      if (it.moveToFirst()) return AtkItem.parse(id, it.getString(0) ?: "")
    }
    return null
  }

  fun save(item: AtkItem): Long {
    val values = android.content.ContentValues()
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

  fun count(): Int {
    val c = db().rawQuery("SELECT COUNT(*) FROM timer", null)
    c.use { if (it.moveToFirst()) return it.getInt(0) }
    return 0
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
      c.use { while (it.moveToNext()) rows.add(it.getString(0) ?: "") }
    } finally {
      src.close()
      cache.delete()
    }
    val local = db()
    local.beginTransaction()
    try {
      if (replace) local.delete("timer", null, null)
      val values = android.content.ContentValues()
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
      val values = android.content.ContentValues()
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
  }
}
