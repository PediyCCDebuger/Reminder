package com.lentikr.reminder.timer

import cn.nnci.addintimer.Lunar
import cn.nnci.addintimer.Rule
import java.util.Locale

/**
 * Binary-level reconstruction of AddinTimer 2.1G's per-timer settings.
 *
 * Every label and every spinner entry below was extracted from the original APK's
 * resources.arsc (string + array tables), and every field index from the disassembly of
 * GSW/AddinTimer/bu (base fields), ko (28-field rule string) and ad (27 ALARM action
 * parameters). Nothing here is guessed.
 */
object AtkOptions {
  val MODES = listOf("按周循环", "指定日期", "按天循环", "按年月循环", "按阴历循环", "倒计时")
  val MODE_SHORT = listOf("按周", "指定", "按天", "按月", "阴历", "倒计")
  val WEEK_DAYS = listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
  val WEEK_SHORT = listOf("日", "一", "二", "三", "四", "五", "六")
  val MONTHS = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
  val MONTH_DAYS = (1..31).map { it.toString() } + "最后一天"
  val MONTH_WEEKS = listOf("第一个", "第二个", "第三个", "第四个", "最后一个")
  val MONTH_TYPE = listOf("按天计算", "指周计算")
  val LUNAR_MONTHS = listOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")
  val LUNAR_DAYS = listOf(
    "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
    "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
    "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十", "最后一天",
  )
  val HOUR_LOOP_ENABLE = listOf("不启用", "启用")

  // "其他设置" on the 时间设置 tab
  val ACT_TIP = listOf("无通知立即执行", "倒计时等待通知")
  val AUTO_DEL = listOf("无动作", "立即删除")
  val SHOW_WIDGET = listOf("不允许显示在部件中", "允许显示在部件中")
  val CONFIRM_EXEC = listOf("无例外", "停止执行", "延迟5分钟执行")

  // 参数设置 tab (ALARM)
  val RING_TIME = listOf("不播报", "播报1次", "播报2次", "播报3次", "播报4次", "播报5次")
  val RING_SORT = listOf("音乐前", "音乐中")
  val RING_MODE = listOf("中文男生", "中文女生", "英文女生")
  val RING_DELAY = listOf("无延迟", "延迟5秒", "延迟10秒", "延迟15秒", "延迟20秒", "延迟25秒", "延迟30秒", "延迟35秒", "延迟40秒")
  val RING_INTERVAL = listOf("连续播报", "间隔10秒", "间隔20秒", "间隔30秒")
  val RING_TYPE = listOf("系统铃声", "媒体库", "随机音乐", "指定音乐", "录制音乐")
  val RING_REPEAT = (1..10).map { "重复${it}次" }
  val RING_VOL = listOf("不改变") + (1..10).map { "${it * 10}%" }
  val RING_VOL_STEP = listOf("立即") + (1..9).map { "渐响${it * 10}秒" }
  val FORCE_SPEAKER = listOf("关闭（保持当时模式）", "打开（忽略耳机模式）")
  val VIBRATE = listOf("无振动", "跟随音乐起停", "音乐前振动10秒", "音乐前振动20秒", "音乐前振动30秒")
  val SNAP_DUR = listOf("3分钟", "5分钟", "10分钟", "15分钟", "30分钟", "45分钟")
  val SNAP_REPEAT = listOf("无") + (1..10).map { "${it}次" }
  val LOCK_TYPE = listOf("不点亮屏幕", "点亮屏幕但不解锁", "点亮屏幕并解锁")
  val TIP_CLOSE = listOf("无确认", "要求确认")
  val SENSOR_LIGHT = listOf("关闭", "启动")
  val SENSOR_GRAVITY = listOf("关闭", "翻转贪睡", "翻转静音")
  val ANSWER_PROBLEM = listOf("关闭", "简单", "稍难")
  val STOP_STYLE = listOf("无", "点击滑块", "双击滑块")
  val BROADCAST_CONTENT = listOf("开始音", "年月日", "星期", "上下午", "时分")
  val HAND_SNAP = listOf("选择贪睡时间", "使用预设贪睡时间")
  val POWER_STYLE = listOf("暂停提醒", "自动贪睡")
  val UI_STYLE = listOf("滑动方式", "按钮方式")

  val KINDS = listOf(
    "ALARM" to "定时提醒",
    "HOURRING" to "整点报时",
    "FM" to "定时飞行",
    "SCENE" to "情景模式",
    "WIFI" to "定时Wifi",
    "BT" to "定时蓝牙",
    "WALLPAPER" to "定时壁纸",
    "RUNAPP" to "定时程序",
    "CALL" to "定时呼叫",
    "REBOOT" to "定时重启",
    "SHUTDOWN" to "定时关机",
    "SMS" to "定时短信",
    "AUDIOREC" to "定时录音",
    "TAKEPIC" to "定时拍照",
  )
}

/** One row on the 参数设置 tab. Indices match GSW/AddinTimer/ad.a(String). */
data class ActSpec(
  val index: Int,
  val label: String,
  val kind: FieldKind,
  val options: List<String> = emptyList(),
  val section: String = "",
)

enum class FieldKind { SELECT, TEXT, NUMBER, MULTI }

object AlarmSpec {
  const val SECTION_NOTIFY = "通知设置"
  const val SECTION_SNOOZE = "贪睡设置"
  const val SECTION_UI = "界面设置"
  const val SECTION_OTHER = "其他设置"

  /**
   * Order follows the original 参数设置 screen: 通知设置 → 贪睡设置 → 界面设置,
   * then 其他设置 with the remaining fields (they stay writable but are scrolled out of sight
   * on the original screen too). Indices 19/20 are internal counters: not shown, never dropped.
   */
  val FIELDS: List<ActSpec> = listOf(
    // 通知设置（8 项，顺序与原版截图一致）
    ActSpec(1, "播报时间", FieldKind.SELECT, AtkOptions.RING_TIME, SECTION_NOTIFY),
    ActSpec(6, "铃声类型", FieldKind.SELECT, AtkOptions.RING_TYPE, SECTION_NOTIFY),
    ActSpec(7, "选择路径", FieldKind.TEXT, section = SECTION_NOTIFY),
    ActSpec(8, "重复次数", FieldKind.SELECT, AtkOptions.RING_REPEAT, SECTION_NOTIFY),
    ActSpec(9, "音量大小", FieldKind.SELECT, AtkOptions.RING_VOL, SECTION_NOTIFY),
    ActSpec(10, "渐响模式", FieldKind.SELECT, AtkOptions.RING_VOL_STEP, SECTION_NOTIFY),
    ActSpec(11, "强制外放", FieldKind.SELECT, AtkOptions.FORCE_SPEAKER, SECTION_NOTIFY),
    ActSpec(12, "振动类型", FieldKind.SELECT, AtkOptions.VIBRATE, SECTION_NOTIFY),
    // 贪睡设置
    ActSpec(13, "预设时间", FieldKind.SELECT, AtkOptions.SNAP_DUR, SECTION_SNOOZE),
    ActSpec(14, "自动贪睡", FieldKind.SELECT, AtkOptions.SNAP_REPEAT, SECTION_SNOOZE),
    // 界面设置
    ActSpec(15, "屏幕模式", FieldKind.SELECT, AtkOptions.LOCK_TYPE, SECTION_UI),
    ActSpec(18, "翻转感应", FieldKind.SELECT, AtkOptions.SENSOR_GRAVITY, SECTION_UI),
    ActSpec(26, "界面样式", FieldKind.SELECT, AtkOptions.UI_STYLE, SECTION_UI),
    ActSpec(21, "算术题解锁", FieldKind.SELECT, AtkOptions.ANSWER_PROBLEM, SECTION_UI),
    ActSpec(25, "电源键按下", FieldKind.SELECT, AtkOptions.POWER_STYLE, SECTION_UI),
    ActSpec(24, "手动贪睡", FieldKind.SELECT, AtkOptions.HAND_SNAP, SECTION_UI),
    ActSpec(16, "终止确认", FieldKind.SELECT, AtkOptions.TIP_CLOSE, SECTION_UI),
    // 其他设置
    ActSpec(0, "提醒备注", FieldKind.TEXT, section = SECTION_OTHER),
  )

  /**
   * Indices that exist in the original 27-field ALARM parameter list but are NOT shown on the
   * original 参数设置 screen (播报位置 / 声音模式 / 延迟时间 / 间隔时间 / 光线感应器 /
   * 暂停方式 / 播报内容 / 内部计数). They are still read, preserved and written back verbatim
   * so importing or editing a task never loses data.
   */
  val HIDDEN_INDICES: Set<Int> = setOf(2, 3, 4, 5, 17, 19, 20, 22, 23)

  /** Defaults from GSW/AddinTimer/ad.<init>(). */
  val DEFAULTS: List<String> = listOf(
    "", // 0  y 提醒备注
    "0", // 1  a
    "1", // 2  b
    "0", // 3  d
    "1", // 4  e
    "1", // 5  f
    "1", // 6  g
    "缺省铃音", // 7 h
    "0", // 8  i
    "8", // 9  j
    "1", // 10 k
    "0", // 11 l
    "2", // 12 m
    "2", // 13 n
    "1", // 14 o
    "2", // 15 p
    "1", // 16 q
    "0", // 17 t
    "0", // 18 u
    "0", // 19 z
    "0", // 20 A
    "0", // 21 v
    "2", // 22 w
    "0,4", // 23 c 播报内容多选
    "0", // 24 s
    "0", // 25 r
    "0", // 26 x
  )
}

/**
 * A single timer. `param` is exactly the AddinTimer `timer.param` text:
 * `<KIND>|<14 base fields joined by $>|<action parameters joined by |>`.
 */
class AtkItem {
  var id: Long = -1L
  var kind: String = "ALARM"
  var base: MutableList<String> = defaultBase().toMutableList()
  var rule: Rule = Rule()
  var act: MutableList<String> = ArrayList()
  /** Original text kept for diagnostics; a damaged row is never silently rewritten. */
  var rawParam: String = ""
  var parseError: String = ""

  var name: String
    get() = base[2]
    set(v) { base[2] = v }
  var hour: Int
    get() = intOf(base[3])
    set(v) { base[3] = v.toString() }
  var minute: Int
    get() = intOf(base[4])
    set(v) { base[4] = v.toString() }
  var second: Int
    get() = intOf(base[12])
    set(v) { base[12] = v.toString() }
  var enabled: Boolean
    get() = intOf(base[10]) != 0
    set(v) { base[10] = if (v) "1" else "0" }
  var page: Int
    get() = intOf(base[1])
    set(v) { base[1] = v.toString() }
  var nextMs: Long
    get() = try { base[6].toLong() } catch (e: Exception) { 0L }
    set(v) { base[6] = if (v > 0) v.toString() else "" }

  // 其他设置 on 时间设置 tab
  var preNotify: Int
    get() = intOf(base[8])
    set(v) { base[8] = v.toString() }
  var showWidget: Int
    get() = intOf(base[11])
    set(v) { base[11] = v.toString() }
  var afterAction: Int
    get() = intOf(base[13])
    set(v) { base[13] = v.toString() }

  // 时循环设置 (ko.y / ko.z / A / B)
  var hourLoop: Int
    get() = rule.endEnable
    set(v) { rule.endEnable = v }
  var loopMinutes: Int
    get() = rule.cycleMinutes
    set(v) { rule.cycleMinutes = v }
  var loopEndH: Int
    get() = rule.cycleEndH
    set(v) { rule.cycleEndH = v }
  var loopEndM: Int
    get() = rule.cycleEndM
    set(v) { rule.cycleEndM = v }

  var message: String
    get() = if (kind == "ALARM" && act.size > 0) act[0] else ""
    set(v) { if (kind == "ALARM" && act.size > 0) act[0] = v }

  var ringtone: String
    get() = if (kind == "ALARM" && act.size > 7) act[7] else ""
    set(v) { if (kind == "ALARM" && act.size > 7) act[7] = v }

  fun kindLabel(): String {
    for ((k, label) in AtkOptions.KINDS) if (k == kind) return label
    return kind
  }

  /** Bottom tabs of the original main screen are stored as 100..103 in base[1]. */
  fun pageIndex(): Int = (page - 100).coerceIn(0, AtkTags.MAX - 1)
  fun moveToPage(index: Int) { page = 100 + index.coerceIn(0, AtkTags.MAX - 1) }

  /** "[按周] 一,二,三,四,五" style second line used by the original list rows. */
  fun summary(): String = try {
    val r = rule
    when (r.mode) {
      Rule.MODE_WEEK -> {
        val days = parseIndexList(r.weekdays).sorted()
          .joinToString(",") { AtkOptions.WEEK_SHORT[it.coerceIn(0, 6)] }
        "[按周] " + days.ifEmpty { "未选择" }
      }
      Rule.MODE_ONCE -> "[指定] " + String.format(
        Locale.CHINA, "%04d-%02d-%02d", r.sy, r.sm, r.sd,
      )
      Rule.MODE_DAYS -> "[按天] " + if (r.interval <= 1) "每天" else "每 ${r.interval} 天"
      Rule.MODE_YEAR_MONTH -> {
        val months = parseIndexList(r.months).sorted().joinToString(",") { "${it + 1}月" }
        "[按月] " + months.ifEmpty { "未选择" }
      }
      Rule.MODE_LUNAR -> "[阴历] " +
        (if (r.lunarLeap == 1) "闰" else "") +
        Lunar.monthName(r.lunarMonth) + Lunar.dayName(r.lunarDay)
      Rule.MODE_TARGET -> "[倒计] " + String.format(
        Locale.CHINA, "%04d-%02d-%02d", r.ey, r.em, r.ed,
      )
      else -> ""
    }
  } catch (e: Exception) {
    ""
  }

  fun param(): String {
    if (kind == "ALARM") ensureAlarmAct()
    base[5] = rule.serialize()
    val sb = StringBuilder()
    sb.append(kind).append('|')
    sb.append(base.joinToString("$"))
    for (a in act) sb.append('|').append(a)
    return sb.toString()
  }

  fun ensureAlarmAct() {
    if (kind != "ALARM") return
    if (act.size < 27) {
      for (i in act.size until 27) act.add(AlarmSpec.DEFAULTS[i])
    }
  }

  fun actValue(i: Int): String = if (i in act.indices) act[i] else ""
  fun setAct(i: Int, v: String) {
    if (kind == "ALARM") ensureAlarmAct()
    while (act.size <= i) act.add("")
    act[i] = v
  }

  companion object {
    fun intOf(s: String): Int = try { s.trim().toInt() } catch (e: Exception) { 0 }

    fun defaultBase(): List<String> = listOf(
      "-1",  // 0 original id
      "100", // 1 page
      "",    // 2 name
      "8",   // 3 hour
      "0",   // 4 minute
      "",    // 5 rule string
      "",    // 6 next execution time (ms)
      "1",   // 7 O
      "0",   // 8 P 执行前通知 (bu.P)
      "0",   // 9 G 页内排序
      "1",   // 10 M 激活标志
      "1",   // 11 Q 桌面小部件 (bu.Q)
      "0",   // 12 秒
      "0",   // 13 R 执行后动作 (bu.R)
    )

    // Pattern.quote is essential: Pattern.LITERAL + "\\|" matches a literal backslash
    // followed by '|', NOT '|'. That mistake silently corrupted every saved/imported row.
    private val BAR = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("|"))
    private val DOLLAR = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("$"))

    /** Java-style split with limit=-1: trailing empty fields are preserved (lossless round-trip). */
    fun parse(id: Long, raw: String): AtkItem {
      val it = AtkItem()
      it.id = id
      it.rawParam = raw
      try {
        val seg = BAR.split(raw, -1)
        it.kind = if (seg.isNotEmpty() && seg[0].isNotEmpty()) seg[0] else "ALARM"
        val b: Array<String> = if (seg.size > 1) DOLLAR.split(seg[1], -1) else emptyArray<String>()
        val fields = ArrayList<String>(14)
        for (i in 0 until 14) fields.add(if (i < b.size) b[i] else defaultBase()[i])
        it.base = fields
        it.rule = Rule.parse(fields[5])
        for (i in 2 until seg.size) it.act.add(seg[i])
      } catch (e: Exception) {
        // One damaged row must never take down the whole app on startup.
        it.parseError = e.message ?: "unknown parse error"
        it.kind = "ALARM"
        it.base = defaultBase().toMutableList()
        it.base[2] = "(数据损坏)"
        it.rule = Rule()
        it.act = ArrayList()
      }
      if (it.kind == "ALARM") it.ensureAlarmAct()
      return it
    }

    /**
     * New item from the 提醒管理 group.
     * 0 = 定时提醒 (weekly), 1 = 快速提醒 (one shot), 2 = 整点报时 (hourly).
     */
    fun newAlarm(preset: Int = 0, page: Int = 0): AtkItem {
      val it = AtkItem()
      it.kind = "ALARM"
      it.hour = 8
      it.minute = 0
      // A new timer belongs to the tab it was created from (original behaviour).
      it.moveToPage(page)
      val r = Rule()
      it.rule = r
      when (preset) {
        1 -> {
          it.name = "快速提醒"
          r.mode = Rule.MODE_ONCE
          val n = java.time.LocalDate.now()
          r.sy = n.year; r.sm = n.monthValue; r.sd = n.dayOfMonth
        }
        2 -> {
          it.name = "整点报时"
          r.mode = Rule.MODE_DAYS
          r.interval = 1
          val n = java.time.LocalDate.now()
          r.by = n.year; r.bm = n.monthValue; r.bd = n.dayOfMonth
          it.minute = 0
          it.hourLoop = 1
          it.loopMinutes = 60
          it.loopEndH = 23
          it.loopEndM = 59
        }
        else -> {
          it.name = "新定时器"
          r.mode = Rule.MODE_WEEK
          r.weekdays = "1,2,3,4,5"
        }
      }
      it.act = AlarmSpec.DEFAULTS.toMutableList()
      it.base[5] = it.rule.serialize()
      return it
    }
  }
}

/** Comma-separated 0-based index list used by AddinTimer for multi-select fields. */
fun parseIndexList(s: String): Set<Int> {
  if (s.isBlank()) return emptySet()
  val out = HashSet<Int>()
  for (p in s.split(",")) {
    val v = AtkItem.intOf(p)
    if (v >= 0) out.add(v)
  }
  return out
}

fun joinIndexList(set: Set<Int>): String = set.sorted().joinToString(",")

fun labelsOf(options: List<String>, selected: Set<Int>): String {
  if (selected.isEmpty()) return "未选择"
  return selected.sorted().filter { it in options.indices }.joinToString("、") { options[it] }
}
