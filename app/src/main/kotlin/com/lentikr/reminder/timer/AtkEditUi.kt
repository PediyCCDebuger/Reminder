package com.lentikr.reminder.timer

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nnci.addintimer.Rule
import java.time.LocalDate
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

/**
 * AddinTimer 定时提醒 property editor: silver title strip, two chunky tabs,
 * grouped spinners, and the blue-framed 确定 / 取消 bar at the bottom.
 */
@Composable
fun AtkEditScreen(
  item: AtkItem?,
  preset: Int = 0,
  onSave: (AtkItem) -> Unit,
  onClose: () -> Unit,
  onMessage: (String) -> Unit,
) {
  val draft = remember(item?.id, preset) {
    if (item != null) AtkItem.parse(item.id, item.param()) else AtkItem.newAlarm(preset)
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
    AtkTitleBar("定时提醒")
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
  val context = LocalContext.current
  val r = draft.rule
  var choice by remember { mutableStateOf<ChoiceState?>(null) }
  var multi by remember { mutableStateOf<MultiState?>(null) }
  var input by remember { mutableStateOf<InputState?>(null) }

  fun pickDate(y: Int, m: Int, d: Int, cb: (Int, Int, Int) -> Unit) {
    val yy = if (y > 0) y else LocalDate.now().year
    val mm = if (m in 1..12) m else LocalDate.now().monthValue
    val dd = if (d in 1..31) d else LocalDate.now().dayOfMonth
    DatePickerDialog(
      context,
      { _, year, month, day -> cb(year, month + 1, day) },
      yy, mm - 1, dd,
    ).show()
  }

  fun pickTime(h: Int, m: Int, cb: (Int, Int) -> Unit) {
    TimePickerDialog(context, { _, hour, minute -> cb(hour, minute) }, h, m, true).show()
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
          pickDate(r.sy, r.sm, r.sd) { y, m, d -> change { r.sy = y; r.sm = m; r.sd = d } }
        }
      }
      Rule.MODE_DAYS -> {
        AtkSpinnerRow("间隔天数", "每 ${r.interval.coerceAtLeast(1)} 天") {
          input = InputState("间隔天数", r.interval.toString(), numeric = true) { v ->
            change { r.interval = (v.toIntOrNull() ?: 1).coerceAtLeast(1) }
          }
        }
        AtkSpinnerRow("开始日期", dateText(r.by, r.bm, r.bd)) {
          pickDate(r.by, r.bm, r.bd) { y, m, d -> change { r.by = y; r.bm = m; r.bd = d } }
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
        AtkSpinnerRow("目标日期", dateText(r.ey, r.em, r.ed)) {
          pickDate(r.ey, r.em, r.ed) { y, m, d -> change { r.ey = y; r.em = m; r.ed = d } }
        }
        AtkSpinnerRow("倒计时分钟", "${r.cdMinutes.coerceAtLeast(1)} 分钟") {
          input = InputState("倒计时分钟", r.cdMinutes.toString(), numeric = true) { v ->
            change { r.cdMinutes = (v.toIntOrNull() ?: 30).coerceAtLeast(1) }
          }
        }
        AtkSpinnerRow("从现在开始", if (r.hourCycle != 0) "是" else "否") {
          choice = ChoiceState("从现在开始", listOf("否", "是"), r.hourCycle.coerceIn(0, 1)) { v ->
            change { r.hourCycle = v }
          }
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
      onMessage("已选择铃声：" + uri.lastPathSegment.orEmpty())
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
        if (spec.section != lastSection) {
          AtkSection(spec.section)
          lastSection = spec.section
        }
        val raw = draft.actValue(spec.index)
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
            AtkSpinnerRow(spec.label, raw.ifBlank { "缺省铃音" }) {
              if (spec.index == 7) {
                pathChoice = true
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
          0 -> change { draft.setAct(7, "缺省铃音") }
          1 -> change { draft.setAct(7, "系统铃声") }
          2 -> pickFile.launch(arrayOf("audio/*", "*/*"))
          3 -> input = InputState("选择路径", draft.actValue(7)) { v -> change { draft.setAct(7, v) } }
        }
      },
      { pathChoice = false },
    )
  }
}
