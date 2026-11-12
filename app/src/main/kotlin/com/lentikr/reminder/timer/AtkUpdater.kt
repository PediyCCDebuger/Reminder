package com.lentikr.reminder.timer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 3.6.4 自动更新：直接查询 GitHub Releases 最新版，下载 APK 并拉起系统安装。
 *
 * 直连 GitHub 在国内较慢，因此提供一组下载代理；代理访问格式为 `https://<proxy>/<原始直连链接>`
 * （即把完整的 browser_download_url 拼到代理域名之后）。
 */
object AtkUpdater {
  private const val REPO = "PediyCCDebuger/Reminder"
  private const val API = "https://api.github.com/repos/$REPO/releases/latest"

  data class ReleaseInfo(val tag: String, val apkUrl: String, val apkName: String)

  /** 直连之外可选的下载代理。第一个为「直连（不使用代理）」。 */
  val PROXIES = listOf(
    "直连（不使用代理）",
    "gh-proxy.org",
    "gh-proxy.com",
    "ghfast.top",
    "gh.acmsz.top",
    "github.chenc.dev",
    "gh.monlor.com",
  )

  /** 去掉可能的前导 v / V，便于版本比较。 */
  fun normalize(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

  /** 远端 tag 是否与当前版本不同（即存在更新）。 */
  fun isNewer(tag: String, current: String): Boolean = normalize(tag) != normalize(current)

  /** 拉取最新 Release；失败返回 null。必须在 IO 调度器之外调用（内部已切到 IO）。 */
  suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
    try {
      val conn = URL(API).openConnection() as HttpURLConnection
      conn.requestMethod = "GET"
      conn.setRequestProperty("User-Agent", "AddinTimer")
      conn.connectTimeout = 15000
      conn.readTimeout = 15000
      conn.connect()
      if (conn.responseCode != HttpURLConnection.HTTP_OK) {
        conn.disconnect()
        return@withContext null
      }
      val text = conn.inputStream.bufferedReader().use { it.readText() }
      conn.disconnect()
      val json = JSONObject(text)
      val tag = json.optString("tag_name", "")
      val assets = json.optJSONArray("assets") ?: return@withContext null
      var apkUrl = ""
      var apkName = ""
      for (i in 0 until assets.length()) {
        val a = assets.getJSONObject(i)
        val name = a.optString("name", "")
        if (name.endsWith(".apk", ignoreCase = true)) {
          apkUrl = a.optString("browser_download_url", "")
          apkName = name
          break
        }
      }
      if (tag.isBlank() || apkUrl.isBlank()) null else ReleaseInfo(tag, apkUrl, apkName)
    } catch (e: Exception) {
      null
    }
  }

  /** 下载 APK 到 cache/download/；失败返回 null。内部已切到 IO。 */
  suspend fun download(context: Context, info: ReleaseInfo, proxy: String): File? =
    withContext(Dispatchers.IO) {
      try {
        val dir = File(context.cacheDir, "download")
        dir.mkdirs()
        val out = File(dir, if (info.apkName.isBlank()) "AtkUpdate.apk" else info.apkName)
        val realUrl = if (proxy == "直连（不使用代理）") info.apkUrl else "https://$proxy/${info.apkUrl}"
        val conn = URL(realUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "AddinTimer")
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.connect()
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
          conn.disconnect()
          return@withContext null
        }
        conn.inputStream.use { input ->
          FileOutputStream(out).use { fos -> input.copyTo(fos) }
        }
        conn.disconnect()
        if (out.length() > 0) out else null
      } catch (e: Exception) {
        null
      }
    }

  /** 通过 FileProvider 拉起系统安装器安装下载好的 APK。 */
  fun install(context: Context, file: File) {
    try {
      val uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file,
      )
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(intent)
    } catch (e: Exception) {
      // 安装器可能不存在或被权限拦截；失败静默，由上层 toast 提示。
    }
  }
}
