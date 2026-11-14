package com.lentikr.reminder.timer

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.nnci.addintimer.Rule
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

private data class ChoiceState(
  val title: String,
  val options: List<String>,
  val selected: Int,
  val onPick: (Int) -> Unit,
)

private data class MultiState(
  val title: String,
  val options: List<String>,
  val selected: Set<Int>,
  val onPick: (Set<Int>) -> Unit,
)

private data class InputState(
  val title: String,
  val initial: String,
  val numeric: Boolean = false,
  val onPick: (String) -> Unit,
)

/** Pending 选择时间 popup: original hour/minute stepper instead of the Material clock dial. */
private data class TimeState(
  val hour: Int,
  val minute: Int,
  val onPick: (Int, Int) -> Unit,
)

/**
 * 3.7.0：Pending 选择日期 popup。用 Compose 状态驱动，替代框架 DatePickerDialog——后者在这台
 * 机器上显示的是 2011 年的 Holo 日历，且回调不在当前界面重组作用域内，选完日期一栏不刷新。
 */
private data class DateState(
  val y: Int,
  val m: Int,
  val d: Int,
  val onPick: (Int, Int, Int) -> Unit,
)

/** Pending ringtone/music picker: label → stored value (URI, 缺省铃音, 无). */
private data class RingListState(
  val title: String,
  val options: List<Pair<String, String>>,
  val selected: String,
)

/**
 * AddinTimer 定时提醒 property editor: silver title strip, two chunky tabs,
 * grouped spinners, and the blue-framed 确定 / 取消 bar at the bottom.
 */
@Composable
fun AtkEditScreen(
  item: AtkItem?,
  preset: Int = 0,
  page: Int = 0,
  onSave: (AtkItem) -> Unit,
  onClose: () -> Unit,
  onMessage: (String) -> Unit,
) {
  val draft = remember(item?.id, preset, page) {
    if (item != null) AtkItem.parse(item.id, item.param()) else AtkItem.newAlarm(preset, page)
  }
  var tab by remember { mutableIntStateOf(0) }
  var revision by remember { mutableIntStateOf(0) }
  @Suppress("UNUSED_VARIABLE") val observeRevision = revision
  val change: (() -> Unit) -> Unit = { block -> block(); revision++ }

  Column(
    Modifier
      .fillMaxSize()
      .background(AtkSkin.bg),
  ) {
    AtkTitleBar(if (draft.kind == "ALARM") "定时提醒" else draft.kindLabel())
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
      AtkTabButton("◐", "时间设置", tab == 0, { tab = 0 }, Modifier.weight(1f))
      Spacer(Modifier.width(6.dp))
      AtkTabButton("▤", "参数设置", tab == 1, { tab = 1 }, Modifier.weight(1f))
    }
    Box(Modifier.weight(1f)) {
      if (tab == 0) {
        TimeTab(draft, change)
      } else {
        ParamTab(draft, change, onMessage)
      }
    }
    AtkActionBar(
      onOk = {
        if (draft.name.isBlank()) {
          onMessage("任务名称不能为空")
        } else {
          // 3.7.0：倒计时「从现在开始」= 以保存时刻为锚点的一次性倒计时。锚点存 base[6]
          // （原版的「下次执行时间」字段，本项目未占用），保证重启 / 重排程后不会重新计时。
          if (draft.rule.mode == Rule.MODE_TARGET && draft.rule.hourCycle != 0) {
            draft.nextMs = System.currentTimeMillis() +
              draft.rule.cdMinutes.coerceAtLeast(1) * 60_000L
          } else {
            draft.nextMs = 0L
          }
          // 3.7.0：新建「快速提醒」以保存时刻重新锚定。编辑页可能停留超过一分钟，
          // 打开时预置的「一分钟后」会过期，导致指定日期的一次性任务永不触发。
          if (item == null && preset == 1 && draft.name == "快速提醒" &&
            draft.rule.mode == Rule.MODE_ONCE
          ) {
            val at = java.time.LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0)
            draft.hour = at.hour
            draft.minute = at.minute
            draft.rule.sy = at.year
            draft.rule.sm = at.monthValue
            draft.rule.sd = at.dayOfMonth
          }
          onSave(draft)
        }
      },
      onCancel = onClose,
    )
  }
}

// ---------------------------------------------------------------- 时间设置

@Composable
private fun TimeTab(draft: AtkItem, change: (() -> Unit) -> Unit) {
  val r = draft.rule
  var choice by remember { mutableStateOf<ChoiceState?>(null) }
  var multi by remember { mutableStateOf<MultiState?>(null) }
  var input by remember { mutableStateOf<InputState?>(null) }
  var time by remember { mutableStateOf<TimeState?>(null) }
  // 3.7.0：日期选择改为本界面内的 Compose 状态，选完必定重组刷新日期栏。
  var date by remember { mutableStateOf<DateState?>(null) }

  fun pickTime(h: Int, m: Int, cb: (Int, Int) -> Unit) {
    time = TimeState(h.coerceIn(0, 23), m.coerceIn(0, 59), cb)
  }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(bottom = 12.dp),
  ) {
    AtkSection("常规设置")
    AtkSpinnerRow("任务名称", draft.name.ifBlank { "未命名" }) {
      input = InputState("任务名称", draft.name) { v -> change { draft.name = v } }
    }
    AtkSpinnerRow("开始时间", String.format(Locale.CHINA, "%02d:%02d", draft.hour, draft.minute)) {
      pickTime(draft.hour, draft.minute) { h, m -> change { draft.hour = h; draft.minute = m } }
    }

    AtkSection("执行策略")
    AtkSpinnerRow("执行策略", AtkOptions.MODES[r.mode.coerceIn(0, 5)]) {
      choice = ChoiceState("执行策略", AtkOptions.MODES, r.mode) { v -> change { r.mode = v } }
    }
    when (r.mode) {
      Rule.MODE_WEEK -> {
        AtkSpinnerRow("选择星期", weekLabel(r.weekdays)) {
          multi = MultiState("选择星期", AtkOptions.WEEK_DAYS, parseIndexList(r.weekdays)) { s ->
            change { r.weekdays = joinIndexList(s) }
          }
        }
      }
      Rule.MODE_ONCE -> {
        AtkSpinnerRow("选择日期", dateText(r.sy, r.sm, r.sd)) {
          date = DateState(r.sy, r.sm, r.sd) { y, m, d ->
            change { r.sy = y; r.sm = m; r.sd = d }
          }
        }
      }
      Rule.MODE_DAYS -> {
        AtkSpinnerRow("间隔天数", "每 ${r.interval.coerceAtLeast(1)} 天") {
          input = InputState("间隔天数", r.interval.toString(), numeric = true) { v ->
            change { r.interval = (v.toIntOrNull() ?: 1).coerceAtLeast(1) }
          }
        }
        AtkSpinnerRow("开始日期", dateText(r.by, r.bm, r.bd)) {
          date = DateState(r.by, r.bm, r.bd) { y, m, d ->
            change { r.by = y; r.bm = m; r.bd = d }
          }
        }
      }
      Rule.MODE_YEAR_MONTH -> {
        AtkSpinnerRow("选择月份", monthLabel(r.months)) {
          multi = MultiState("选择月份", AtkOptions.MONTHS, parseIndexList(r.months)) { s ->
            change { r.months = joinIndexList(s) }
          }
        }
        AtkSpinnerRow("月内循环", AtkOptions.MONTH_TYPE[r.byWeek.coerceIn(0, 1)]) {
          choice = ChoiceState("月内循环", AtkOptions.MONTH_TYPE, r.byWeek) { v ->
            change { r.byWeek = v }
          }
        }
        if (r.byWeek == 0) {
          AtkSpinnerRow("选择天数", labelsOf(AtkOptions.MONTH_DAYS, parseIndexList(r.monthDays))) {
            multi = MultiState("选择天数", AtkOptions.MONTH_DAYS, parseIndexList(r.monthDays)) { s ->
              change { r.monthDays = joinIndexList(s) }
            }
          }
        } else {
          AtkSpinnerRow("出现顺序", labelsOf(AtkOptions.MONTH_WEEKS, parseIndexList(r.nthWeek))) {
            multi = MultiState("出现顺序", AtkOptions.MONTH_WEEKS, parseIndexList(r.nthWeek)) { s ->
              change { r.nthWeek = joinIndexList(s) }
            }
          }
          AtkSpinnerRow("选择星期", weekLabel(r.nthWeekday)) {
            multi = MultiState("选择星期", AtkOptions.WEEK_DAYS, parseIndexList(r.nthWeekday)) { s ->
              change { r.nthWeekday = joinIndexList(s) }
            }
          }
        }
        AtkSpinnerRow("排除几号", labelsOf(AtkOptions.MONTH_DAYS, parseIndexList(r.exclDays))) {
          multi = MultiState("排除几号", AtkOptions.MONTH_DAYS, parseIndexList(r.exclDays)) { s ->
            change { r.exclDays = joinIndexList(s) }
          }
        }
        AtkSpinnerRow("排除星期几", weekLabel(r.exclWeek)) {
          multi = MultiState("排除星期几", AtkOptions.WEEK_DAYS, parseIndexList(r.exclWeek)) { s ->
            change { r.exclWeek = joinIndexList(s) }
          }
        }
      }
      Rule.MODE_LUNAR -> {
        AtkSpinnerRow("选择阴历月", AtkOptions.LUNAR_MONTHS[(r.lunarMonth - 1).coerceIn(0, 11)]) {
          choice = ChoiceState(
            "选择阴历月", AtkOptions.LUNAR_MONTHS, (r.lunarMonth - 1).coerceIn(0, 11),
          ) { v -> change { r.lunarMonth = v + 1 } }
        }
        AtkSpinnerRow("选择阴历天", AtkOptions.LUNAR_DAYS[(r.lunarDay - 1).coerceIn(0, 30)]) {
          choice = ChoiceState(
            "选择阴历天", AtkOptions.LUNAR_DAYS, (r.lunarDay - 1).coerceIn(0, 30),
          ) { v -> change { r.lunarDay = v + 1 } }
        }
        AtkSpinnerRow("包含闰月", if (r.lunarLeap != 0) "包含" else "不包含") {
          choice = ChoiceState("包含闰月", listOf("不包含", "包含"), r.lunarLeap.coerceIn(0, 1)) { v ->
            change { r.lunarLeap = v }
          }
        }
      }
      Rule.MODE_TARGET -> {
        // 3.7.0：明确两种互斥语义，避免「倒计时分钟 / 目标日期」看起来能设却完全不生效。
        //   否 → 在目标日期的「开始时间」一次性提醒；
        //   是 → 从保存时刻起「倒计时分钟」分钟后一次性提醒（锚点存 base[6]）。
        AtkSpinnerRow("从现在开始", if (r.hourCycle != 0) "是" else "否") {
          choice = ChoiceState("从现在开始", listOf("否", "是"), r.hourCycle.coerceIn(0, 1)) { v ->
            change { r.hourCycle = v }
          }
        }
        if (r.hourCycle != 0) {
          AtkSpinnerRow("倒计时分钟", "${r.cdMinutes.coerceAtLeast(1)} 分钟") {
            input = InputState("倒计时分钟", r.cdMinutes.toString(), numeric = true) { v ->
              change { r.cdMinutes = (v.toIntOrNull() ?: 30).coerceAtLeast(1) }
            }
          }
          Text(
            "从保存任务起 N 分钟后响一次；目标日期与开始时间不生效。",
            color = AtkSkin.sub, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          )
        } else {
          AtkSpinnerRow("目标日期", dateText(r.ey, r.em, r.ed)) {
            date = DateState(r.ey, r.em, r.ed) { y, m, d ->
              change { r.ey = y; r.em = m; r.ed = d }
            }
          }
          Text(
            "在目标日期的「开始时间」响一次（一次性）。",
            color = AtkSkin.sub, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          )
        }
      }
    }

    AtkSection("时循环设置")
    AtkSpinnerRow("时循环", AtkOptions.HOUR_LOOP_ENABLE[if (draft.hourLoop != 0) 1 else 0]) {
      choice = ChoiceState(
        "时循环", AtkOptions.HOUR_LOOP_ENABLE, if (draft.hourLoop != 0) 1 else 0,
      ) { v -> change { draft.hourLoop = v } }
    }
    if (draft.hourLoop != 0) {
      AtkSpinnerRow("每几分钟", "${draft.loopMinutes.coerceAtLeast(1)} 分钟") {
        input = InputState("每几分钟", draft.loopMinutes.toString(), numeric = true) { v ->
          change { draft.loopMinutes = (v.toIntOrNull() ?: 60).coerceAtLeast(1) }
        }
      }
      AtkSpinnerRow(
        "结束时间",
        String.format(Locale.CHINA, "%02d:%02d", draft.loopEndH, draft.loopEndM),
      ) {
        pickTime(draft.loopEndH, draft.loopEndM) { h, m ->
          change { draft.loopEndH = h; draft.loopEndM = m }
        }
      }
    }

    AtkSection("其他设置")
    AtkSpinnerRow("执行前通知", AtkOptions.ACT_TIP[draft.preNotify.coerceIn(0, 1)]) {
      choice = ChoiceState("执行前通知", AtkOptions.ACT_TIP, draft.preNotify.coerceIn(0, 1)) { v ->
        change { draft.preNotify = v }
      }
    }
    AtkSpinnerRow(
      "桌面小部件",
      AtkOptions.SHOW_WIDGET[if (draft.showWidget != 0) 1 else 0],
    ) {
      choice = ChoiceState(
        "桌面小部件", AtkOptions.SHOW_WIDGET, if (draft.showWidget != 0) 1 else 0,
      ) { v -> change { draft.showWidget = v } }
    }

    Spacer(Modifier.height(10.dp))
    Text(
      "当前规则：" + r.describe(),
      color = AtkSkin.sectionText,
      fontSize = 12.sp,
      modifier = Modifier.padding(horizontal = 8.dp),
    )
    Text(
      "不设结束日期，规则一直循环执行（下次：" +
        AtkEngine(LocalContext.current).nextText(draft) + "）",
      color = AtkSkin.sub,
      fontSize = 12.sp,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
  }

  choice?.let { c ->
    AtkChoiceDialog(c.title, c.options, c.selected, { c.onPick(it); choice = null }, { choice = null })
  }
  multi?.let { m ->
    AtkMultiDialog(m.title, m.options, m.selected, { m.onPick(it); multi = null }, { multi = null })
  }
  input?.let { n ->
    AtkInputDialog(n.title, n.initial, { n.onPick(it); input = null }, { input = null }, n.numeric)
  }
  time?.let { t ->
    AtkTimePickerDialog(
      initialHour = t.hour,
      initialMinute = t.minute,
      onConfirm = { h, m -> t.onPick(h, m); time = null },
      onDismiss = { time = null },
    )
  }
  date?.let { ds ->
    AtkDatePickerDialog(
      initialYear = ds.y,
      initialMonth = ds.m,
      initialDay = ds.d,
      onConfirm = { y, m, d -> ds.onPick(y, m, d); date = null },
      onDismiss = { date = null },
    )
  }
}

/**
 * 3.7.0：复刻原版「选择日期」——三列数字，上 + 下 −，中间可直接手工输入，底部
 * 设置 / 今天 / 取消。全部用 Compose 状态驱动，确定后立即重组刷新时间设置页的日期栏。
 * 年 / 月 / 日联动：月循环 1..12，日自动夹到当月天数（2026-02-29 → 2026-02-28）。
 */
@Composable
private fun AtkDatePickerDialog(
  initialYear: Int,
  initialMonth: Int,
  initialDay: Int,
  onConfirm: (Int, Int, Int) -> Unit,
  onDismiss: () -> Unit,
) {
  val today = LocalDate.now()
  var year by remember { mutableIntStateOf(if (initialYear in 1900..2100) initialYear else today.year) }
  var month by remember { mutableIntStateOf(if (initialMonth in 1..12) initialMonth else today.monthValue) }
  var day by remember { mutableIntStateOf(if (initialDay in 1..31) initialDay else today.dayOfMonth) }

  fun maxDay(y: Int, m: Int): Int = try {
    LocalDate.of(y.coerceIn(1900, 2100), m.coerceIn(1, 12), 1).lengthOfMonth()
  } catch (e: Exception) {
    31
  }
  fun setMonth(v: Int) {
    month = when {
      v > 12 -> 1
      v < 1 -> 12
      else -> v
    }
    day = day.coerceIn(1, maxDay(year, month))
  }
  fun setYear(v: Int) {
    year = v.coerceIn(1900, 2100)
    day = day.coerceIn(1, maxDay(year, month))
  }
  fun setDay(v: Int) {
    val max = maxDay(year, month)
    day = when {
      v > max -> 1
      v < 1 -> max
      else -> v
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .width(320.dp)
        .background(Color(0xFF1B1B1B))
        .border(1.dp, Color(0xFF4A4A4A)),
    ) {
      Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("▦", color = Color.White, fontSize = 17.sp)
        Spacer(Modifier.width(8.dp))
        Text("选择日期", color = Color.White, fontSize = 15.sp)
      }
      Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3C3C3C)))
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        DateStepperColumn(
          year, 4, 1900, 2100,
          { setYear(year + 1) }, { setYear(year - 1) }, { v -> setYear(v) },
        )
        Spacer(Modifier.width(8.dp))
        DateStepperColumn(
          month, 2, 1, 12,
          { setMonth(month + 1) }, { setMonth(month - 1) }, { v -> setMonth(v) },
        )
        Spacer(Modifier.width(8.dp))
        DateStepperColumn(day, 2, 1, maxDay(year, month), { setDay(day + 1) }, { setDay(day - 1) }) { v ->
          day = v.coerceIn(1, maxDay(year, month))
        }
      }
      Text(
        "年 / 月 / 日（点击数字可直接输入）",
        color = Color(0xFF9A9A9A), fontSize = 12.sp,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
      )
      Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) {
        TimeDialogButton("设置", Modifier.weight(1f)) {
          onConfirm(year, month, day.coerceIn(1, maxDay(year, month)))
        }
        Spacer(Modifier.width(10.dp))
        TimeDialogButton("今天", Modifier.weight(1f)) {
          onConfirm(today.year, today.monthValue, today.dayOfMonth)
        }
        Spacer(Modifier.width(10.dp))
        TimeDialogButton("取消", Modifier.weight(1f), onDismiss)
      }
    }
  }
}

/** 一列：银色 + 条、白底可编辑数字、银色 − 条。 */
@Composable
private fun DateStepperColumn(
  value: Int,
  digits: Int,
  min: Int,
  max: Int,
  onStepUp: () -> Unit,
  onStepDown: () -> Unit,
  onSet: (Int) -> Unit,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    DateStepBar("+", onStepUp)
    Spacer(Modifier.height(8.dp))
    DateValueField(value, digits, min, max, onSet)
    Spacer(Modifier.height(8.dp))
    DateStepBar("−", onStepDown)
  }
}

@Composable
private fun DateValueField(value: Int, digits: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
  fun fmt(v: Int) = String.format(Locale.CHINA, "%0${digits}d", v)
  var text by remember { mutableStateOf(fmt(value)) }
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  LaunchedEffect(value) {
    val cur = text.toIntOrNull()
    if (cur != null && cur != value) text = fmt(value)
  }
  LaunchedEffect(focused) {
    if (!focused) text = fmt((text.toIntOrNull() ?: value).coerceIn(min, max))
  }
  Box(
    Modifier.width(88.dp).height(52.dp).background(Color(0xFFF6F6F6)),
    contentAlignment = Alignment.Center,
  ) {
    BasicTextField(
      value = text,
      onValueChange = { raw ->
        val ds = raw.filter { it.isDigit() }.take(digits)
        if (ds.isEmpty()) {
          text = ""
          onSet(min)
        } else {
          val n = ds.toIntOrNull() ?: min
          val clamped = n.coerceIn(min, max)
          text = if (clamped == n) ds else fmt(clamped)
          onSet(clamped)
        }
      },
      singleLine = true,
      interactionSource = interactionSource,
      textStyle = TextStyle(
        color = Color(0xFF111111),
        fontSize = if (digits > 2) 24.sp else 30.sp,
        textAlign = TextAlign.Center,
      ),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      cursorBrush = SolidColor(Color(0xFF111111)),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    )
  }
}

@Composable
private fun DateStepBar(label: String, onClick: () -> Unit) {
  Box(
    Modifier
      .width(88.dp)
      .height(38.dp)
      .background(AtkSkin.silver)
      .border(1.dp, Color(0xFF9A9A9A))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, color = Color(0xFF333333), fontSize = 24.sp, fontWeight = FontWeight.Bold)
  }
}

/**
 * The original 选择时间 popup: dark card, clock glyph + title, two stepper columns for hour and
 * minute, and a 设置 / 现在 / 取消 row at the bottom. Replaces the Material clock dial, which the
 * user rejected as "not the original".
 *
 * 2.6.0 adds the two things the original also allowed and the first version missed:
 *   • the hour / minute boxes are now editable — tap and type instead of clicking +/- twenty times;
 *   • the +/- buttons step by a selectable amount (1 / 5 / 10 分钟), with carry into the hour
 *     (11:58 +5 → 12:03) instead of wrapping inside the minute column.
 */
@Composable
private fun AtkTimePickerDialog(
  initialHour: Int,
  initialMinute: Int,
  onConfirm: (Int, Int) -> Unit,
  onDismiss: () -> Unit,
) {
  var hour by remember { mutableIntStateOf(initialHour.coerceIn(0, 23)) }
  var minute by remember { mutableIntStateOf(initialMinute.coerceIn(0, 59)) }
  var step by remember { mutableIntStateOf(1) }

  fun shift(delta: Int) {
    val total = ((hour * 60 + minute + delta) % 1440 + 1440) % 1440
    hour = total / 60
    minute = total % 60
  }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .width(320.dp)
        .background(Color(0xFF1B1B1B))
        .border(1.dp, Color(0xFF4A4A4A)),
    ) {
      Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("◔", color = Color.White, fontSize = 17.sp)
        Spacer(Modifier.width(8.dp))
        Text("选择时间", color = Color.White, fontSize = 15.sp)
      }
      Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3C3C3C)))
      Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TimeStepperColumn(
          value = hour,
          max = 23,
          onStepUp = { shift(60) },
          onStepDown = { shift(-60) },
          onSet = { hour = it.coerceIn(0, 23) },
        )
        Text(":", color = Color.White, fontSize = 30.sp, modifier = Modifier.padding(horizontal = 8.dp))
        TimeStepperColumn(
          value = minute,
          max = 59,
          onStepUp = { shift(step) },
          onStepDown = { shift(-step) },
          onSet = { minute = it.coerceIn(0, 59) },
        )
      }
      Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("每次增减", color = Color(0xFF9A9A9A), fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        TimeStepChip("1 分钟", step == 1) { step = 1 }
        Spacer(Modifier.width(8.dp))
        TimeStepChip("5 分钟", step == 5) { step = 5 }
        Spacer(Modifier.width(8.dp))
        TimeStepChip("10 分钟", step == 10) { step = 10 }
      }
      Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) {
        TimeDialogButton("设置", Modifier.weight(1f)) { onConfirm(hour, minute) }
        Spacer(Modifier.width(10.dp))
        TimeDialogButton("现在", Modifier.weight(1f)) {
          val now = LocalTime.now()
          onConfirm(now.hour, now.minute)
        }
        Spacer(Modifier.width(10.dp))
        TimeDialogButton("取消", Modifier.weight(1f), onDismiss)
      }
    }
  }
}

/**
 * One hour/minute column: silver "+" bar, an editable white value box, silver "−" bar.
 * [+] and [−] step by the caller-supplied amount (hour steps a whole hour, minute steps the
 * selected 1/5/10), and carry into the other field through [AtkTimePickerDialog.shift]. The
 * editable box sets its OWN field absolutely (see TimeValueField) so typing the hour never
 * disturbs the minute and the typed digits are never overwritten.
 */
@Composable
private fun TimeStepperColumn(
  value: Int,
  max: Int,
  onStepUp: () -> Unit,
  onStepDown: () -> Unit,
  onSet: (Int) -> Unit,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    TimeStepBar("+") { onStepUp() }
    Spacer(Modifier.height(8.dp))
    TimeValueField(value, max, onSet)
    Spacer(Modifier.height(8.dp))
    TimeStepBar("−") { onStepDown() }
  }
}

/**
 * White box that accepts a typed number, exactly like the original's editable time field.
 *
 * The typed value is reported back as an ABSOLUTE value for this field (hour or minute), never a
 * delta: typing 8 into the hour box must set the hour to 8, leaving the minute untouched. The
 * caller's step buttons still drive the shared carry via shift(), so a minute step of 5 at 11:58
 * still rolls over into 12:03.
 *
 * Re-formatting is limited to (a) an outside change (a +/- tap, value != shown digits) and
 * (b) losing focus, so the user's keystrokes are never clobbered mid-edit.
 */
@Composable
private fun TimeValueField(value: Int, max: Int, onSet: (Int) -> Unit) {
  var text by remember { mutableStateOf(String.format(Locale.CHINA, "%02d", value)) }
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  LaunchedEffect(value) {
    val cur = text.toIntOrNull()
    if (cur != null && cur != value) {
      text = String.format(Locale.CHINA, "%02d", value)
    }
  }
  LaunchedEffect(focused) {
    if (!focused) {
      val cur = text.toIntOrNull() ?: value
      text = String.format(Locale.CHINA, "%02d", cur.coerceIn(0, max))
    }
  }
  Box(
    Modifier.width(86.dp).height(52.dp).background(Color(0xFFF6F6F6)),
    contentAlignment = Alignment.Center,
  ) {
    BasicTextField(
      value = text,
      onValueChange = { raw ->
        val digits = raw.filter { it.isDigit() }.take(2)
        when {
          digits.isEmpty() -> {
            text = ""
            onSet(0)
          }
          else -> {
            val n = digits.toIntOrNull() ?: 0
            val clamped = n.coerceIn(0, max)
            // Show the clamped two-digit form only when the typed value was actually out of
            // range, so an in-range edit never flashes a reformatted string under the cursor.
            text = if (clamped == n) digits else String.format(Locale.CHINA, "%02d", clamped)
            onSet(clamped)
          }
        }
      },
      singleLine = true,
      interactionSource = interactionSource,
      textStyle = TextStyle(
        color = Color(0xFF111111),
        fontSize = 30.sp,
        textAlign = TextAlign.Center,
      ),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      cursorBrush = SolidColor(Color(0xFF111111)),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
  }
}

@Composable
private fun TimeStepBar(label: String, onClick: () -> Unit) {
  Box(
    Modifier
      .width(86.dp)
      .height(38.dp)
      .background(AtkSkin.silver)
      .border(1.dp, Color(0xFF9A9A9A))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, color = Color(0xFF333333), fontSize = 24.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun TimeStepChip(label: String, selected: Boolean, onClick: () -> Unit) {
  Box(
    Modifier
      .height(30.dp)
      .background(if (selected) SolidColor(Color(0xFF1490DE)) else AtkSkin.silver)
      .border(1.dp, if (selected) Color(0xFF0F6FA8) else Color(0xFF9A9A9A))
      .clickable(onClick = onClick)
      .padding(horizontal = 9.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label,
      color = if (selected) Color.White else Color(0xFF333333),
      fontSize = 13.sp,
    )
  }
}

@Composable
private fun TimeDialogButton(text: String, modifier: Modifier, onClick: () -> Unit) {
  Box(
    modifier
      .height(42.dp)
      .background(AtkSkin.silver)
      .border(1.dp, Color(0xFF9A9A9A))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = Color(0xFF222222), fontSize = 15.sp)
  }
}

private fun dateText(y: Int, m: Int, d: Int): String =
  String.format(Locale.CHINA, "%04d/%02d/%02d", y, m.coerceIn(1, 12), d.coerceIn(1, 31))

private fun weekLabel(csv: String): String {
  val set = parseIndexList(csv)
  if (set.isEmpty()) return "未选择"
  return set.sorted().joinToString(",") { AtkOptions.WEEK_SHORT[it.coerceIn(0, 6)] }
}

private fun monthLabel(csv: String): String {
  val set = parseIndexList(csv)
  if (set.isEmpty()) return "未选择"
  return set.sorted().joinToString(",") { "${it + 1}月" }
}

// ---------------------------------------------------------------- 参数设置

@Composable
private fun ParamTab(draft: AtkItem, change: (() -> Unit) -> Unit, onMessage: (String) -> Unit) {
  val context = LocalContext.current
  var choice by remember { mutableStateOf<ChoiceState?>(null) }
  var multi by remember { mutableStateOf<MultiState?>(null) }
  var input by remember { mutableStateOf<InputState?>(null) }
  var pathChoice by remember { mutableStateOf(false) }
  var ringList by remember { mutableStateOf<RingListState?>(null) }

  val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    android.Manifest.permission.READ_MEDIA_AUDIO
  } else {
    android.Manifest.permission.READ_EXTERNAL_STORAGE
  }
  val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) {
      val music = AtkRingtone.mediaMusic(context)
      if (music.isEmpty()) {
        onMessage("媒体库里没有找到音频文件，请改用系统铃声或从文件选择")
        pathChoice = true
      } else {
        ringList = RingListState("选择音乐", music, draft.actValue(7))
      }
    } else {
      onMessage("未授予读取媒体库权限，可改用系统铃声或从文件选择铃声")
      pathChoice = true
    }
  }

  fun openMusicPicker() {
    if (context.checkSelfPermission(audioPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      val music = AtkRingtone.mediaMusic(context)
      if (music.isEmpty()) {
        onMessage("媒体库里没有找到音频文件，请改用系统铃声或从文件选择")
        pathChoice = true
      } else {
        ringList = RingListState("选择音乐", music, draft.actValue(7))
      }
    } else {
      askAudio.launch(audioPermission)
    }
  }

  val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      try {
        context.contentResolver.takePersistableUriPermission(
          uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      } catch (e: Exception) {
        // Some providers do not offer persistable permissions.
      }
      change { draft.setAct(7, uri.toString()) }
      onMessage("已选择铃声：" + AtkRingtone.nameOf(context, uri))
    }
  }

  /**
   * 系统铃声 → the system's own ringtone picker (ACTION_RINGTONE_PICKER). On ColorOS this is the
   * 经典铃声 / 自然铃声 / 本地铃声 / 在线铃声 page — exactly what the original AddinTimer opened.
   * The in-app radio list remains only as a fallback for ROMs without a ringtone picker.
   */
  val pickSystemRingtone = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val data = result.data
      val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
      }
      if (uri == null) {
        change { draft.setAct(7, AtkRingtone.SILENT_LABEL) }
        onMessage("已选择「无」：该任务不响铃")
      } else {
        try {
          context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        } catch (e: Exception) {
          // System ringtone URIs are world-readable; persistence is best effort.
        }
        change { draft.setAct(7, uri.toString()) }
        onMessage("已选择铃声：" + AtkRingtone.nameOf(context, uri))
      }
    }
  }

  fun openSystemRingtone() {
    val current = try { AtkRingtone.resolve(context, draft) } catch (e: Exception) { null }
    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
      putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
      putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
      putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
      putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "铃声")
      if (current != null) {
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
      }
    }
    try {
      pickSystemRingtone.launch(intent)
    } catch (e: Exception) {
      // No ringtone picker on this ROM: keep the in-app list usable.
      ringList = RingListState("铃声", AtkRingtone.systemRingtones(context), draft.actValue(7))
    }
  }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(bottom = 12.dp),
  ) {
    if (draft.kind != "ALARM") {
      AtkSection("参数设置")
      Text(
        "此条为原版「${draft.kindLabel()}」类型，复刻版只执行提醒；以下参数按原样读写以免备份数据丢失。",
        color = AtkSkin.sectionText,
        fontSize = 13.sp,
        modifier = Modifier.padding(8.dp),
      )
      AtkSection("其他设置")
      draft.act.forEachIndexed { i, value ->
        AtkSpinnerRow("参数 $i", value.ifBlank { "(空)" }) {
          val captured = i
          input = InputState("参数 $captured", value) { v -> change { draft.setAct(captured, v) } }
        }
      }
      Spacer(Modifier.height(12.dp))
    } else {
      draft.ensureAlarmAct()
      var lastSection = ""
      for (spec in AlarmSpec.FIELDS) {
        // 3.6.9：「暂停时长」只在「电源键按下」= 暂停提醒 (act[25]=0) 时有意义，选自动贪睡时隐藏。
        if (spec.index == 27 && AtkItem.intOf(draft.actValue(25)) != 0) continue
        if (spec.section != lastSection) {
          AtkSection(spec.section)
          lastSection = spec.section
        }
        // 3.7.3：旧备份没有扩展参数 act[27]/act[28] 时，界面要显示与引擎兜底一致的默认值
        // （3 分钟），否则会显示成索引 0 的「1分钟」，让用户误以为设置不生效。
        val raw = if (spec.index >= 27 && draft.actValue(spec.index).isBlank()) {
          AlarmSpec.DEFAULTS.getOrElse(spec.index) { "" }
        } else {
          draft.actValue(spec.index)
        }
        when (spec.kind) {
          FieldKind.SELECT -> {
            val cur = AtkItem.intOf(raw).coerceIn(0, spec.options.lastIndex.coerceAtLeast(0))
            AtkSpinnerRow(spec.label, spec.options.getOrElse(cur) { raw }) {
              choice = ChoiceState(spec.label, spec.options, cur) { v ->
                change { draft.setAct(spec.index, v.toString()) }
              }
            }
          }
          FieldKind.MULTI -> {
            AtkSpinnerRow(spec.label, labelsOf(spec.options, parseIndexList(raw))) {
              multi = MultiState(spec.label, spec.options, parseIndexList(raw)) { s ->
                change { draft.setAct(spec.index, joinIndexList(s)) }
              }
            }
          }
          FieldKind.TEXT -> {
            // 选择路径 shows the resolved file name, never a truncated content:// URI.
            val shown = if (spec.index == 7) {
              AtkRingtone.title(context, draft)
            } else {
              raw.ifBlank { "(空)" }
            }
            AtkSpinnerRow(spec.label, shown) {
              if (spec.index == 7) {
                val type = AtkItem.intOf(draft.actValue(6))
                // 媒体库 / 指定音乐: straight to the music list, exactly like the original.
                if (type == 1 || type == 3) openMusicPicker() else pathChoice = true
              } else {
                input = InputState(spec.label, raw) { v -> change { draft.setAct(spec.index, v) } }
              }
            }
          }
          FieldKind.NUMBER -> {
            AtkSpinnerRow(spec.label, raw) {
              input = InputState(spec.label, raw, numeric = true) { v ->
                change { draft.setAct(spec.index, v) }
              }
            }
          }
        }
      }
      Spacer(Modifier.height(12.dp))
    }
  }

  choice?.let { c ->
    AtkChoiceDialog(c.title, c.options, c.selected, { c.onPick(it); choice = null }, { choice = null })
  }
  multi?.let { m ->
    AtkMultiDialog(m.title, m.options, m.selected, { m.onPick(it); multi = null }, { multi = null })
  }
  input?.let { n ->
    AtkInputDialog(n.title, n.initial, { n.onPick(it); input = null }, { input = null }, n.numeric)
  }
  if (pathChoice) {
    AtkChoiceDialog(
      "选择路径",
      listOf("缺省铃音", "系统铃声", "从文件选择铃声…", "手动输入路径"),
      -1,
      { i ->
        pathChoice = false
        when (i) {
          0 -> change { draft.setAct(7, AtkRingtone.DEFAULT_LABEL) }
          // 系统铃声 opens the ROM's own ringtone picker, exactly like the original app.
          1 -> openSystemRingtone()
          2 -> pickFile.launch(arrayOf("audio/*"))
          3 -> input = InputState("选择路径", draft.actValue(7)) { v -> change { draft.setAct(7, v) } }
        }
      },
      { pathChoice = false },
    )
  }
  ringList?.let { r ->
    AtkRadioDialog(
      title = r.title,
      options = r.options,
      selectedValue = r.selected,
      onConfirm = { value -> change { draft.setAct(7, value) }; ringList = null },
      onDismiss = { ringList = null },
    )
  }
}

/**
 * Single-choice list with an explicit 确定 / 取消 row, matching the original 铃声 and 选择音乐
 * pickers (outlined radio marks with a green dot, white panel, black title strip).
 */
@Composable
private fun AtkRadioDialog(
  title: String,
  options: List<Pair<String, String>>,
  selectedValue: String,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var picked by remember { mutableStateOf(selectedValue) }
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.width(330.dp).background(Color.White)) {
      AtkDialogTitle(title)
      LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(options) { entry ->
          Row(
            Modifier
              .fillMaxWidth()
              .background(Color.White)
              .clickable { picked = entry.second }
              .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              entry.first,
              color = Color.Black,
              fontSize = 16.sp,
              modifier = Modifier.weight(1f),
            )
            Box(
              Modifier
                .size(22.dp)
                .border(2.dp, Color(0xFF9E9E9E), CircleShape),
              contentAlignment = Alignment.Center,
            ) {
              if (picked == entry.second) {
                Box(Modifier.size(12.dp).background(AtkSkin.green, CircleShape))
              }
            }
          }
          AtkLightDivider()
        }
      }
      Row(
        Modifier.fillMaxWidth().background(Color(0xFFD2D2D2)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        DialogActionButton("✗", "取消", AtkSkin.red, Modifier.weight(1f), onDismiss)
        Spacer(Modifier.width(10.dp))
        DialogActionButton("✓", "确定", AtkSkin.green, Modifier.weight(1f)) { onConfirm(picked) }
      }
    }
  }
}

/** Silver pill button with a coloured glyph, matching the bottom bar of the editor. */
@Composable
private fun DialogActionButton(
  symbol: String,
  text: String,
  symbolColor: Color,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  Box(
    modifier
      .height(46.dp)
      .background(AtkSkin.silver)
      .border(1.dp, Color(0xFF9A9A9A))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(symbol, color = symbolColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.width(8.dp))
      Text(text, color = AtkSkin.ink, fontSize = 17.sp)
    }
  }
}
