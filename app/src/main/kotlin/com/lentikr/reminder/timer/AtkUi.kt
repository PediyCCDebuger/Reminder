package com.lentikr.reminder.timer

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { LIST, EDIT, PERMISSIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerApp() {
  val context = LocalContext.current
  val store = remember { AtkStore(context) }
  val engine = remember { AtkEngine(context) }

  var screen by remember { mutableStateOf(Screen.LIST) }
  var editingId by remember { mutableStateOf(-1L) }
  var timerList by remember { mutableStateOf(store.all()) }
  var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
  var message by remember { mutableStateOf("") }

  fun refresh() {
    timerList = store.all()
    engine.rescheduleAll()
  }

  val openImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) importUri = uri
  }
  val createExport = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/octet-stream"),
  ) { uri ->
    if (uri != null) {
      try {
        val n = store.exportAtk(context.contentResolver, uri)
        toast(context, "已导出 $n 条到 .atk")
      } catch (e: Exception) {
        toast(context, "导出失败：" + e.message)
      }
    }
  }

  if (importUri != null) {
    AlertDialog(
      onDismissRequest = { importUri = null },
      title = { Text("导入 AddinTimer 备份") },
      text = { Text("选择「替换」会清空当前列表后导入；「追加」会保留现有定时器。原版普通版只激活 5 条，导入后可在列表里逐条开启。") },
      confirmButton = {
        TextButton(onClick = {
          val uri = importUri
          importUri = null
          try {
            val n = if (uri != null) store.importAtk(uri, true) else 0
            refresh()
            toast(context, "已替换导入 $n 条")
          } catch (e: Exception) {
            toast(context, "导入失败：" + e.message)
          }
        }) { Text("替换导入") }
      },
      dismissButton = {
        Row {
          TextButton(onClick = {
            val uri = importUri
            importUri = null
            try {
              val n = if (uri != null) store.importAtk(uri, false) else 0
              refresh()
              toast(context, "已追加 $n 条")
            } catch (e: Exception) {
              toast(context, "导入失败：" + e.message)
            }
          }) { Text("追加") }
          TextButton(onClick = { importUri = null }) { Text("取消") }
        }
      },
    )
  }

  if (message.isNotEmpty()) {
    AlertDialog(
      onDismissRequest = { message = "" },
      title = { Text("提示") },
      text = { Text(message) },
      confirmButton = { TextButton(onClick = { message = "" }) { Text("知道了") } },
    )
  }

  when (screen) {
    Screen.LIST -> ListScreen(
      timerList = timerList,
      engine = engine,
      onNew = { editingId = -1L; screen = Screen.EDIT },
      onEdit = { id -> editingId = id; screen = Screen.EDIT },
      onToggle = { id, on ->
        store.setEnabled(id, on)
        refresh()
      },
      onDelete = { id ->
        engine.cancel(id)
        store.delete(id)
        refresh()
      },
      onImport = { openImport.launch(arrayOf("*/*")) },
      onExport = {
        val name = "AddinTimer_" + SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA).format(Date()) + ".atk"
        createExport.launch(name)
      },
      onPermissions = { screen = Screen.PERMISSIONS },
      onReschedule = { refresh(); toast(context, "已重新排程全部定时器") },
      onDisableTasks = {
        for (it in store.all()) {
          if (it.kind != "ALARM" && it.kind != "HOURRING") {
            it.enabled = false
            store.save(it)
          }
        }
        refresh()
        toast(context, "已停用所有任务类定时器（数据保留）")
      },
    )

    Screen.EDIT -> AtkEditScreen(
      item = if (editingId >= 0) store.get(editingId) else null,
      onSave = { item ->
        store.save(item)
        refresh()
        screen = Screen.LIST
      },
      onDelete = { id ->
        engine.cancel(id)
        store.delete(id)
        refresh()
        screen = Screen.LIST
      },
      onClose = { screen = Screen.LIST },
      onMessage = { message = it },
    )

    Screen.PERMISSIONS -> PermissionScreen(engine = engine, onBack = { screen = Screen.LIST })
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreen(
  timerList: List<AtkItem>,
  engine: AtkEngine,
  onNew: () -> Unit,
  onEdit: (Long) -> Unit,
  onToggle: (Long, Boolean) -> Unit,
  onDelete: (Long) -> Unit,
  onImport: () -> Unit,
  onExport: () -> Unit,
  onPermissions: () -> Unit,
  onReschedule: () -> Unit,
  onDisableTasks: () -> Unit,
) {
  val active = timerList.count { it.enabled }
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("AddinTimer 定时器", style = MaterialTheme.typography.titleLarge)
            Text("共 ${timerList.size} 条 · $active 条激活", style = MaterialTheme.typography.bodySmall)
          }
        },
        actions = {
          TextButton(onClick = onImport) { Text("导入 .atk") }
          TextButton(onClick = onExport) { Text("导出") }
        },
      )
    },
    floatingActionButton = { FloatingActionButton(onClick = onNew) { Text("＋") } },
  ) { padding ->
    LazyColumn(
      Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Column(Modifier.padding(vertical = 8.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPermissions, Modifier.weight(1f)) { Text("权限自检") }
            OutlinedButton(onClick = onReschedule, Modifier.weight(1f)) { Text("立即重排") }
            OutlinedButton(onClick = onDisableTasks, Modifier.weight(1f)) { Text("停用任务类") }
          }
          Spacer(Modifier.height(4.dp))
          Text(
            "数据格式与原版 .atk 完全兼容；导入后请核对每条的「下次」时间。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(timerList, key = { it.id }) { item ->
        TimerCard(item, engine, onEdit, onToggle, onDelete)
      }
      if (timerList.isEmpty()) {
        item {
          Column(
            Modifier.fillMaxWidth().padding(vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text("还没有定时器", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("点右下角新增，或先导入你的 .atk 备份", style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
      item { Spacer(Modifier.height(80.dp)) }
    }
  }
}

@Composable
private fun TimerCard(
  item: AtkItem,
  engine: AtkEngine,
  onEdit: (Long) -> Unit,
  onToggle: (Long, Boolean) -> Unit,
  onDelete: (Long) -> Unit,
) {
  var confirmDelete by remember { mutableStateOf(false) }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(item.name.ifBlank { "(未命名)" }, style = MaterialTheme.typography.titleMedium)
          Text(
            "${item.kindLabel()} · ${String.format("%02d:%02d", item.hour, item.minute)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = item.enabled, onCheckedChange = { onToggle(item.id, it) })
      }
      Spacer(Modifier.height(6.dp))
      Text(item.rule.describe(), style = MaterialTheme.typography.bodyMedium)
      Text(
        "下次：" + engine.nextText(item),
        style = MaterialTheme.typography.bodySmall,
        color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (item.message.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(item.message, style = MaterialTheme.typography.bodySmall)
      }
      Spacer(Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onEdit(item.id) }) { Text("编辑设置") }
        TextButton(onClick = { confirmDelete = true }) { Text("删除") }
      }
    }
  }
  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("删除定时器") },
      text = { Text("确定删除「${item.name}」？此操作不可撤销。") },
      confirmButton = {
        TextButton(onClick = { confirmDelete = false; onDelete(item.id) }) { Text("删除") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionScreen(engine: AtkEngine, onBack: () -> Unit) {
  var version by remember { mutableStateOf(0) }
  val report = remember(version) { engine.permissionReport() }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("权限自检") },
        navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
      Text(
        "一加 13 / ColorOS 上，下面任何一项未开启都可能导致提醒不响或延迟。逐项点「去设置」开启后回来刷新。",
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(16.dp))
      for ((name, ok) in report) {
        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
          Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(name, style = MaterialTheme.typography.titleSmall)
              Text(
                if (ok) "已开启" else "未开启（建议立即开启）",
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
              )
            }
            if (!ok) {
              Button(onClick = {
                when {
                  name.contains("精确闹钟") -> engine.openExactAlarmSettings()
                  name.contains("通知") && name.contains("全屏").not() -> engine.openNotificationSettings()
                  name.contains("电池") -> engine.openBatterySettings()
                  name.contains("全屏") -> engine.openFullScreenSettings()
                }
              }) { Text("去设置") }
            }
          }
        }
      }
      Spacer(Modifier.height(16.dp))
      OutlinedButton(onClick = { version++ }, Modifier.fillMaxWidth()) { Text("刷新状态") }
      Spacer(Modifier.height(12.dp))
      Text(
        "另外请到系统「设置 → 应用管理 → 本应用」里开启「允许自动启动」「允许后台运行」，并把电池策略设为「不限制」。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun toast(context: Context, text: String) {
  Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
