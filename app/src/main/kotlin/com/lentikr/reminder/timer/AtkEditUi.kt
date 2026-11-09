package com.lentikr.reminder.timer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.nnci.addintimer.Rule
import java.time.LocalDate

/**
 * Faithful rebuild of AddinTimer's two-tab property editor:
 * 时间设置 (常规设置 / 执行策略 / 具体设置 / 时循环设置 / 其他设置) and
 * 参数设置 (通知设置 / 贪睡设置 / 界面设置 / 其他设置).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtkEditScreen(
  item: AtkItem?,
  onSave: (AtkItem) -> Unit,
  onDelete: (Long) -> Unit,
  onClose: () -> Unit,
  onMessage: (String) -> Unit,
) {
  val draft = remember(item?.id) { item?.copyItem() ?: AtkItem.newAlarm() }
  var tab by remember { mutableIntStateOf(0) }
  var revision by remember { mutableIntStateOf(0) }
  @Suppress("UNUSED_VARIABLE") val observeRevision = revision
  var confirmDelete by remember { mutableStateOf(false) }
  val change: (() -> Unit) -> Unit = { block -> block(); revision++ }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (item == null) "新建定时器" else "编辑定时器") },
        navigationIcon = { TextButton(onClick = onClose) { Text("返回") } },
        actions = {
          if (item != null) {
            TextButton(onClick = { confirmDelete = true }) { Text("删除") }
          }
          TextButton(onClick = {
            if (draft.name.isBlank()) {
              onMessage("任务名称不能为空")
            } else {
              onSave(draft)
            }
          }) { Text("保存") }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("时间设置") })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("参数设置") })
      }
      if (tab == 0) {
        TimeTab(draft, change, onMessage)
      } else {
        ParamTab(draft, change, onMessage)
      }
    }
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("删除定时器") },
      text = { Text("确定删除「${draft.name}」？") },
      confirmButton = {
        TextButton(onClick = { confirmDelete = false; onDelete(draft.id) }) { Text("删除") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
  }

}

/** Deep copy so cancelling never mutates the stored object. */
private fun AtkItem.copyItem(): AtkItem {
  val c = AtkItem.parse(this.id, this.param())
  c.id = this.id
  return c
}

// ---------------------------------------------------------------- 时间设置 tab

@Composable
private fun TimeTab(draft: AtkItem, change: (() -> Unit) -> Unit, onMessage: (String) -> Unit) {
  val r = draft.rule
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
    Section("常规设置")
    TextFieldRow("任务名称", draft.name) { v -> change { draft.name = v } }
    Row(Modifier.fillMaxWidth()) {
      NumberField("开始时间 · 时", draft.hour.toString(), Modifier.weight(1f)) { v ->
        change { draft.hour = v.coerceIn(0, 23) }
      }
      Spacer(Modifier.width(8.dp))
      NumberField("分", draft.minute.toString(), Modifier.weight(1f)) { v ->
        change { draft.minute = v.coerceIn(0, 59) }
      }
      Spacer(Modifier.width(8.dp))
      NumberField("秒", draft.second.toString(), Modifier.weight(1f)) { v ->
        change { draft.second = v.coerceIn(0, 59) }
      }
    }
    SelectRow("循环规则", AtkOptions.MODES, r.mode) { v -> change { r.mode = v } }
    SelectRow("任务类型", AtkOptions.KINDS.map { it.second },
      AtkOptions.KINDS.indexOfFirst { it.first == draft.kind }.coerceAtLeast(0)) { v ->
      change { draft.kind = AtkOptions.KINDS[v].first; draft.ensureAlarmAct() }
    }
    SwitchRow("激活此定时器", draft.enabled) { v -> change { draft.enabled = v } }
    Text("循环规则：" + r.describe(), style = MaterialTheme.typography.bodySmall)

    Section("执行策略 · 具体设置")
    when (r.mode) {
      Rule.MODE_WEEK -> {
        MultiRow("选择星期", AtkOptions.WEEK_DAYS, parseIndexList(r.weekdays)) { s ->
          change { r.weekdays = joinIndexList(s) }
        }
      }
      Rule.MODE_ONCE -> {
        DateRow("指定日期", r.sy, r.sm, r.sd) { y, m, d -> change { r.sy = y; r.sm = m; r.sd = d } }
      }
      Rule.MODE_DAYS -> {
        NumberField("每几天", r.interval.toString(), Modifier.fillMaxWidth()) { v ->
          change { r.interval = v.coerceAtLeast(1) }
        }
        DateRow("开始日期", r.by, r.bm, r.bd) { y, m, d -> change { r.by = y; r.bm = m; r.bd = d } }
      }
      Rule.MODE_YEAR_MONTH -> {
        MultiRow("选择月份", AtkOptions.MONTHS, parseIndexList(r.months)) { s ->
          change { r.months = joinIndexList(s) }
        }
        SelectRow("月内循环", AtkOptions.MONTH_TYPE, r.byWeek) { v -> change { r.byWeek = v } }
        if (r.byWeek == 0) {
          MultiRow("选择天数", AtkOptions.MONTH_DAYS, parseIndexList(r.monthDays)) { s ->
            change { r.monthDays = joinIndexList(s) }
          }
        } else {
          MultiRow("出现顺序", AtkOptions.MONTH_WEEKS, parseIndexList(r.nthWeek)) { s ->
            change { r.nthWeek = joinIndexList(s) }
          }
          MultiRow("选择星期", AtkOptions.WEEK_DAYS, parseIndexList(r.nthWeekday)) { s ->
            change { r.nthWeekday = joinIndexList(s) }
          }
        }
        MultiRow("排除几号", AtkOptions.MONTH_DAYS, parseIndexList(r.exclDays)) { s ->
          change { r.exclDays = joinIndexList(s) }
        }
        MultiRow("排除星期几", AtkOptions.WEEK_DAYS, parseIndexList(r.exclWeek)) { s ->
          change { r.exclWeek = joinIndexList(s) }
        }
      }
      Rule.MODE_LUNAR -> {
        SelectRow("选择阴历月", AtkOptions.LUNAR_MONTHS, (r.lunarMonth - 1).coerceIn(0, 11)) { v ->
          change { r.lunarMonth = v + 1 }
        }
        SelectRow("选择阴历天", AtkOptions.LUNAR_DAYS, (r.lunarDay - 1).coerceIn(0, 30)) { v ->
          change { r.lunarDay = v + 1 }
        }
        SwitchRow("包含闰月", r.lunarLeap != 0) { v -> change { r.lunarLeap = if (v) 1 else 0 } }
      }
      Rule.MODE_TARGET -> {
        SwitchRow("从现在开始", r.hourCycle != 0) { v -> change { r.hourCycle = if (v) 1 else 0 } }
        DateRow("开始日期", r.ey, r.em, r.ed) { y, m, d -> change { r.ey = y; r.em = m; r.ed = d } }
        NumberField("倒计时分钟", r.cdMinutes.toString(), Modifier.fillMaxWidth()) { v ->
          change { r.cdMinutes = v.coerceAtLeast(1) }
        }
      }
    }

    Section("执行策略 · 结束与排除")
    DateRow("结束日期", r.ey, r.em, r.ed) { y, m, d -> change { r.ey = y; r.em = m; r.ed = d } }
    if (r.mode != Rule.MODE_YEAR_MONTH) {
      MultiRow("排除几号", AtkOptions.MONTH_DAYS, parseIndexList(r.exclDays)) { s ->
        change { r.exclDays = joinIndexList(s) }
      }
      MultiRow("排除星期几", AtkOptions.WEEK_DAYS, parseIndexList(r.exclWeek)) { s ->
        change { r.exclWeek = joinIndexList(s) }
      }
    }

    Section("时循环设置")
    SelectRow("时循环", AtkOptions.HOUR_LOOP_ENABLE, if (draft.hourLoop != 0) 1 else 0) { v ->
      change { draft.hourLoop = v }
    }
    NumberField("每几分钟", draft.loopMinutes.toString(), Modifier.fillMaxWidth()) { v ->
      change { draft.loopMinutes = v.coerceAtLeast(1) }
    }
    Row(Modifier.fillMaxWidth()) {
      NumberField("结束时间 · 时", draft.loopEndH.toString(), Modifier.weight(1f)) { v ->
        change { draft.loopEndH = v.coerceIn(0, 23) }
      }
      Spacer(Modifier.width(8.dp))
      NumberField("分", draft.loopEndM.toString(), Modifier.weight(1f)) { v ->
        change { draft.loopEndM = v.coerceIn(0, 59) }
      }
    }

    Section("其他设置")
    SelectRow("执行前通知", AtkOptions.ACT_TIP, draft.preNotify.coerceIn(0, 1)) { v ->
      change { draft.preNotify = v }
    }
    SelectRow("执行后动作", AtkOptions.AUTO_DEL, draft.afterAction.coerceIn(0, 1)) { v ->
      change { draft.afterAction = v }
    }
    SelectRow("桌面小部件", AtkOptions.SHOW_WIDGET,
      if (draft.showWidget != 0) 1 else 0) { v -> change { draft.showWidget = v } }

    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp)) {
        Text("当前规则", style = MaterialTheme.typography.labelLarge)
        Text(r.describe(), style = MaterialTheme.typography.bodyMedium)
        Text("下次：" + AtkEngine(LocalContext.current).nextText(draft),
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
      }
    }
    Spacer(Modifier.height(80.dp))
  }
}

// ---------------------------------------------------------------- 参数设置 tab

@Composable
private fun ParamTab(draft: AtkItem, change: (() -> Unit) -> Unit, onMessage: (String) -> Unit) {
  val context = LocalContext.current
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
    }
  }

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
    if (draft.kind != "ALARM") {
      Section("执行参数 · ${draft.kindLabel()}")
      Text(
        "此类型在原版用于执行系统任务，本复刻只保留提醒，参数按原样读写以免数据丢失。",
        style = MaterialTheme.typography.bodySmall,
      )
      draft.act.forEachIndexed { i, value ->
        TextFieldRow("参数 [$i]", value) { v -> change { draft.setAct(i, v) } }
      }
      Spacer(Modifier.height(80.dp))
    } else {

    draft.ensureAlarmAct()
    var lastSection = ""
    for (spec in AlarmSpec.FIELDS) {
      if (spec.section != lastSection) {
        Section(spec.section)
        lastSection = spec.section
      }
      when (spec.kind) {
        FieldKind.SELECT -> {
          val cur = AtkItem.intOf(draft.actValue(spec.index))
            .coerceIn(0, spec.options.lastIndex.coerceAtLeast(0))
          SelectRow(spec.label, spec.options, cur) { v ->
            change { draft.setAct(spec.index, v.toString()) }
          }
        }
        FieldKind.MULTI -> {
          MultiRow(spec.label, spec.options, parseIndexList(draft.actValue(spec.index))) { s ->
            change { draft.setAct(spec.index, joinIndexList(s)) }
          }
        }
        FieldKind.TEXT -> {
          TextFieldRow(spec.label, draft.actValue(spec.index)) { v ->
            change { draft.setAct(spec.index, v) }
          }
          if (spec.index == 7) {
            OutlinedButton(onClick = { pickFile.launch(arrayOf("audio/*", "*/*")) }) {
              Text("选择路径（铃声文件）")
            }
          }
        }
        FieldKind.NUMBER -> {
          NumberField(spec.label, draft.actValue(spec.index), Modifier.fillMaxWidth()) { v ->
            change { draft.setAct(spec.index, v.toString()) }
          }
        }
      }
    }
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp)) {
        Text("参数说明", style = MaterialTheme.typography.labelLarge)
        Text(
          "以上 27 项与 AddinTimer 的「参数设置」逐项对应，字段顺序来自原版 ad.a(String) 的反汇编。铃声路径为原版绝对路径时，在 Android 11+ 分区存储下不可读，请用「选择路径」重新选文件。",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    Spacer(Modifier.height(80.dp))
    }
  }
}

// ---------------------------------------------------------------- 通用控件

@Composable
private fun Section(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
  )
}

@Composable
private fun TextFieldRow(label: String, value: String, onChange: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
  )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = { v -> if (v.isEmpty()) onChange(0) else v.toIntOrNull()?.let(onChange) },
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = modifier.padding(vertical = 3.dp),
  )
}

@Composable
private fun DateRow(label: String, y: Int, m: Int, d: Int, onChange: (Int, Int, Int) -> Unit) {
  Text(label, style = MaterialTheme.typography.bodySmall,
    modifier = Modifier.padding(top = 6.dp))
  Row(Modifier.fillMaxWidth()) {
    NumberField("年", if (y > 0) y.toString() else "", Modifier.weight(1.2f)) { v ->
      onChange(v, m, d)
    }
    Spacer(Modifier.width(8.dp))
    NumberField("月", if (m > 0) m.toString() else "", Modifier.weight(1f)) { v ->
      onChange(y, v.coerceIn(0, 12), d)
    }
    Spacer(Modifier.width(8.dp))
    NumberField("日", if (d > 0) d.toString() else "", Modifier.weight(1f)) { v ->
      onChange(y, m, v.coerceIn(0, 31))
    }
  }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
private fun SelectRow(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
  var open by remember { mutableStateOf(false) }
  val safe = selected.coerceIn(0, options.lastIndex.coerceAtLeast(0))
  OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      Text(
        if (options.isEmpty()) "" else options[safe],
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
  if (open) {
    AlertDialog(
      onDismissRequest = { open = false },
      title = { Text(label) },
      text = {
        LazyColumn {
          itemsIndexed(options) { i, name ->
            TextButton(onClick = { onPick(i); open = false }, Modifier.fillMaxWidth()) {
              Row(Modifier.fillMaxWidth()) {
                Text(name, Modifier.weight(1f))
                if (i == safe) Text("当前")
              }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    )
  }
}

@Composable
private fun MultiRow(label: String, options: List<String>, selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
  var open by remember { mutableStateOf(false) }
  OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      Text(
        labelsOf(options, selected),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
  if (open) {
    var temp by remember { mutableStateOf(selected) }
    AlertDialog(
      onDismissRequest = { open = false },
      title = { Text(label) },
      text = {
        LazyColumn {
          itemsIndexed(options) { i, name ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Checkbox(
                checked = i in temp,
                onCheckedChange = { on ->
                  val next = temp.toMutableSet()
                  if (on) next.add(i) else next.remove(i)
                  temp = next
                },
              )
              Text(name)
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { onChange(temp); open = false }) { Text("确定") } },
      dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    )
  }
}
