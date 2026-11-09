package com.lentikr.reminder.timer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.nnci.addintimer.Occurrence
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private enum class Screen { LIST, EDIT, SETTINGS, TAGS, PERMISSIONS }

private val TAB_NAMES = listOf("常用", "生活", "纪念日", "生日")
private val CONTEXT_ENTRIES = listOf(
  "测试执行", "删除条目", "参考新建", "执行规划", "查看日志", "移动分类",
)
private val PLAN_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd (E) HH:mm")

@Composable
fun TimerApp() {
  AtkTheme {
    val context = LocalContext.current
    val store = remember { AtkStore(context) }
    val engine = remember { AtkEngine(context) }
    val prefs = remember { context.getSharedPreferences("atk_settings", Context.MODE_PRIVATE) }

    var screen by remember { mutableStateOf(Screen.LIST) }
    var tab by remember { mutableIntStateOf(0) }
    var editingId by remember { mutableStateOf(-1L) }
    var preset by remember { mutableIntStateOf(0) }
    var timerList by remember { mutableStateOf(store.all()) }
    var message by remember { mutableStateOf("") }
    var newTaskOpen by remember { mutableStateOf(false) }
    var systemMenuOpen by remember { mutableStateOf(false) }
    var backupOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var contextItem by remember { mutableStateOf<AtkItem?>(null) }
    var deleteTarget by remember { mutableStateOf<AtkItem?>(null) }
    var planItem by remember { mutableStateOf<AtkItem?>(null) }
    var logItem by remember { mutableStateOf<AtkItem?>(null) }
    var moveItem by remember { mutableStateOf<AtkItem?>(null) }

    fun refresh() {
      timerList = store.all()
      engine.rescheduleAll()
    }

    BackHandler(enabled = screen != Screen.LIST) { screen = Screen.LIST }

    val openImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) importUri = uri
    }
    val createExport = rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
      if (uri != null) {
        try {
          val n = store.exportAtk(context.contentResolver, uri)
          toast(context, "已备份 $n 条到 .atk")
        } catch (e: Exception) {
          toast(context, "备份失败：" + e.message)
        }
      }
    }

    fun export() {
      val name = "AddinTimer_" +
        SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA).format(Date()) + ".atk"
      createExport.launch(name)
    }

    Column(
      Modifier
        .fillMaxSize()
        .background(AtkSkin.bg)
        .statusBarsPadding()
        .navigationBarsPadding(),
    ) {
      when (screen) {
        Screen.LIST -> MainScreen(
          timerList = timerList,
          tab = tab,
          engine = engine,
          onTab = { tab = it },
          onNew = { newTaskOpen = true },
          onMenu = { systemMenuOpen = true },
          onEdit = { id -> editingId = id; preset = 0; screen = Screen.EDIT },
          onLongClick = { item -> contextItem = item },
          onToggle = { id, on -> store.setEnabled(id, on); refresh() },
        )

        Screen.EDIT -> AtkEditScreen(
          item = if (editingId >= 0) store.get(editingId) else null,
          preset = preset,
          onSave = { item -> store.save(item); refresh(); screen = Screen.LIST },
          onClose = { screen = Screen.LIST },
          onMessage = { message = it },
        )

        Screen.SETTINGS -> SettingsScreen(
          prefs = prefs,
          onBack = { screen = Screen.LIST },
          onPermissions = { screen = Screen.PERMISSIONS },
        )

        Screen.TAGS -> TagsScreen(
          onBack = { screen = Screen.LIST },
          onPick = { i -> tab = i; screen = Screen.LIST },
        )

        Screen.PERMISSIONS -> PermissionScreen(
          engine = engine,
          onBack = { screen = Screen.SETTINGS },
        )
      }
    }

    if (systemMenuOpen) {
      AtkSystemMenu(
        onPick = { i ->
          systemMenuOpen = false
          when (i) {
            0 -> newTaskOpen = true
            1 -> screen = Screen.SETTINGS
            2 -> screen = Screen.TAGS
            3 -> backupOpen = true
            4 -> {
              val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "AddinTimer 复刻版：导入旧版 .atk 备份即可继续使用原来的定时任务。")
              }
              try { context.startActivity(Intent.createChooser(send, "分享好友")) } catch (e: Exception) { }
            }
            5 -> message = "当前已是最新版本（2.2.0），无需更新。"
            6 -> {
              try {
                context.startActivity(
                  Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/PediyCCDebuger/Reminder/issues"),
                  ),
                )
              } catch (e: Exception) {
                message = "反馈地址：github.com/PediyCCDebuger/Reminder/issues"
              }
            }
            7 -> aboutOpen = true
          }
        },
        onDismiss = { systemMenuOpen = false },
      )
    }

    if (newTaskOpen) {
      AtkNewTaskDialog(
        onPick = { p ->
          newTaskOpen = false
          preset = p
          editingId = -1L
          screen = Screen.EDIT
        },
        onDismiss = { newTaskOpen = false },
      )
    }

    if (backupOpen) {
      AtkBackupDialog(
        onBackup = { backupOpen = false; export() },
        onRestore = { backupOpen = false; openImport.launch(arrayOf("*/*")) },
        onDismiss = { backupOpen = false },
      )
    }

    importUri?.let { uri ->
      AtkChoiceDialog(
        title = "恢复环境",
        options = listOf("替换当前所有定时器", "追加到当前列表"),
        onPick = { which ->
          importUri = null
          try {
            val n = store.importAtk(uri, which == 0)
            refresh()
            toast(context, if (which == 0) "已恢复 $n 条" else "已追加 $n 条")
          } catch (e: Exception) {
            message = "恢复失败：" + e.message
          }
        },
        onDismiss = { importUri = null },
      )
    }

    contextItem?.let { item ->
      AtkContextMenu(
        entries = CONTEXT_ENTRIES,
        onPick = { i ->
          contextItem = null
          when (i) {
            0 -> {
              engine.showNotification(item)
              store.log(item.id, 0, "测试执行")
              message = "已测试执行「${item.name}」"
            }
            1 -> deleteTarget = item
            2 -> {
              val copy = AtkItem.parse(-1L, item.param())
              copy.id = -1L
              val id = store.save(copy)
              refresh()
              editingId = id
              preset = 0
              screen = Screen.EDIT
            }
            3 -> planItem = item
            4 -> logItem = item
            5 -> moveItem = item
          }
        },
        onDismiss = { contextItem = null },
      )
    }

    deleteTarget?.let { item ->
      AtkMessageDialog(
        title = "删除条目",
        body = "确定删除「${item.name}」？",
        onDismiss = { deleteTarget = null },
        confirmText = "删除",
        onConfirm = {
          engine.cancel(item.id)
          store.delete(item.id)
          refresh()
          deleteTarget = null
        },
        dismissText = "取消",
      )
    }

    planItem?.let { item ->
      val times = Occurrence.next(item.rule, item.hour, item.minute, LocalDateTime.now(), 10)
      val body = if (times.isEmpty()) {
        "当前规则没有匹配的日期，请检查「执行策略」的具体设置。"
      } else {
        times.joinToString("\n") { it.format(PLAN_FMT) } +
          "\n\n未设置结束日期时，规则一直循环执行。"
      }
      AtkMessageDialog(
        title = "执行规划 · ${item.name}",
        body = body,
        onDismiss = { planItem = null },
      )
    }

    logItem?.let { item ->
      val lines = store.logs(item.id)
      AtkMessageDialog(
        title = "查看日志 · ${item.name}",
        body = if (lines.isEmpty()) "暂无执行日志" else lines.joinToString("\n"),
        onDismiss = { logItem = null },
      )
    }

    moveItem?.let { item ->
      AtkChoiceDialog(
        title = "移动分类",
        options = TAB_NAMES,
        selected = item.pageIndex(),
        onPick = { i ->
          item.moveToPage(i)
          store.save(item)
          refresh()
          moveItem = null
          tab = i
          toast(context, "已移动到「${TAB_NAMES[i]}」")
        },
        onDismiss = { moveItem = null },
      )
    }

    if (aboutOpen) {
      AtkMessageDialog(
        title = "关于软件",
        body = "AddinTimer 复刻版 2.2.0\n" +
          "界面与数据格式对齐 AddinTimer 2.1G：可导入原版 .atk 备份，保留 28 项时间规则与 27 项提醒参数。\n" +
          "仅保留「提醒管理」（定时提醒 / 快速提醒 / 整点报时）。\n" +
          "Android 12+ 请在「系统设置 → 服务状态」里完成精确闹钟、通知、电池优化与全屏通知授权。",
        onDismiss = { aboutOpen = false },
      )
    }

    if (message.isNotEmpty()) {
      AtkMessageDialog(title = "提示", body = message, onDismiss = { message = "" })
    }
  }
}

// ---------------------------------------------------------------- 主界面

@Composable
private fun MainScreen(
  timerList: List<AtkItem>,
  tab: Int,
  engine: AtkEngine,
  onTab: (Int) -> Unit,
  onNew: () -> Unit,
  onMenu: () -> Unit,
  onEdit: (Long) -> Unit,
  onLongClick: (AtkItem) -> Unit,
  onToggle: (Long, Boolean) -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    AtkTitleBar("AddinTimer", suffix = "(高级版)", onMenu = onMenu)
    Spacer(Modifier.height(4.dp))
    AtkBlueButton("新增任务", onNew)
    Spacer(Modifier.height(4.dp))
    val visible = timerList.filter { it.pageIndex() == tab }
    LazyColumn(Modifier.weight(1f).background(AtkSkin.bg)) {
      items(visible, key = { it.id }) { item ->
        AtkTimerRow(
          item = item,
          next = if (item.enabled) engine.nextMillis(item) else 0L,
          onClick = { onEdit(item.id) },
          onLongClick = { onLongClick(item) },
          onToggle = { on -> onToggle(item.id, on) },
        )
      }
      if (visible.isEmpty()) {
        item {
          Box(
            Modifier.fillMaxWidth().padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text("此分类下暂无定时器", color = Color(0xFF8C8C8C), fontSize = 15.sp)
          }
        }
      }
    }
    AtkTabBar(tab, onTab)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AtkTimerRow(
  item: AtkItem,
  next: Long,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onToggle: (Boolean) -> Unit,
) {
  val tomorrow = next > 0 && try {
    LocalDate.ofInstant(Instant.ofEpochMilli(next), ZoneId.systemDefault()) == LocalDate.now().plusDays(1)
  } catch (e: Exception) {
    false
  }
  Column {
    Row(
      Modifier
        .fillMaxWidth()
        .height(72.dp)
        .background(AtkSkin.bgRow)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Image(
        painter = painterResource(android.R.drawable.ic_lock_idle_alarm),
        contentDescription = null,
        colorFilter = ColorFilter.tint(
          if (item.enabled) AtkSkin.green else Color(0xFF9A9A9A),
        ),
        modifier = Modifier.size(38.dp),
      )
      Spacer(Modifier.width(8.dp))
      Column(Modifier.weight(1f)) {
        Text(
          item.name.ifBlank { "(未命名)" },
          color = if (item.enabled) Color.White else Color(0xFFB0B0B0),
          fontSize = 19.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
          item.summary(),
          color = AtkSkin.sub,
          fontSize = 14.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Column(
        Modifier.width(86.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center,
      ) {
        if (tomorrow) {
          Box(
            Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(Color(0xFFF2F2F2))
              .padding(horizontal = 6.dp, vertical = 1.dp),
          ) {
            Text("明天", color = Color(0xFF222222), fontSize = 12.sp)
          }
          Spacer(Modifier.height(2.dp))
        }
        Text(
          String.format(Locale.CHINA, "%02d:%02d", item.hour, item.minute),
          color = Color.White,
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(5.dp))
        Box(
          Modifier
            .width(46.dp)
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (item.enabled) AtkSkin.green else Color(0xFF8A8A8A))
            .clickable { onToggle(!item.enabled) },
        )
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(AtkSkin.divider))
  }
}

// ---------------------------------------------------------------- 系统设置

private data class SettingDef(
  val key: String,
  val title: String,
  val options: List<String>,
  val default: String,
)

private data class SettingChoice(val def: SettingDef, val current: String, val onPick: (String) -> Unit)

private val GENERAL_SETTINGS = listOf(
  SettingDef("notify_icon", "通知栏图标", listOf("应用程序图标", "定时器状态图标", "不显示"), "应用程序图标"),
  SettingDef("icon_click", "点击定时器图标", listOf("操作菜单", "编辑定时器", "执行任务"), "操作菜单"),
  SettingDef("sort", "任务排序方式", listOf("按执行时间", "按创建时间", "按名称"), "按执行时间"),
  SettingDef("log_keep", "执行日志保存期限", listOf("一个月", "三个月", "半年", "一年", "永久"), "一个月"),
)
private val TIMER_SETTINGS = listOf(
  SettingDef("power", "任务执行时电源模式", listOf("半唤醒(节电模式)", "全唤醒"), "半唤醒(节电模式)"),
  SettingDef("call", "通话中定时执行例外", listOf("无例外", "停止执行", "延迟5分钟执行"), "无例外"),
)
private val WAIT_SETTINGS = listOf(
  SettingDef("wait", "执行等待倒计时", listOf("5秒", "10秒", "15秒", "30秒", "不等待"), "5秒"),
  SettingDef("vibrate", "伴随振动模式", listOf("无振动", "短振动", "长振动"), "无振动"),
)
private val OTHER_SETTINGS = listOf(
  SettingDef("update", "检查更新周期", listOf("3天", "7天", "30天", "不检查"), "3天"),
)

@Composable
private fun SettingsScreen(
  prefs: android.content.SharedPreferences,
  onBack: () -> Unit,
  onPermissions: () -> Unit,
) {
  var choice by remember { mutableStateOf<SettingChoice?>(null) }
  var version by remember { mutableIntStateOf(0) }
  @Suppress("UNUSED_VARIABLE") val observeVersion = version

  fun valueOf(def: SettingDef): String = prefs.getString(def.key, def.default) ?: def.default

  Column(Modifier.fillMaxSize()) {
    AtkTitleBar("系统设置", onBack = onBack)
    LazyColumn(Modifier.weight(1f)) {
      item { AtkSection("常规设置") }
      item {
        AtkSettingRow("服务状态", "开启服务", onPermissions)
      }
      for (def in GENERAL_SETTINGS) {
        item {
          AtkSettingRow(def.title, valueOf(def)) {
            choice = SettingChoice(def, valueOf(def)) { v ->
              prefs.edit().putString(def.key, v).apply()
              version++
            }
          }
        }
      }
      item { AtkSection("定时器相关") }
      for (def in TIMER_SETTINGS) {
        item {
          AtkSettingRow(def.title, valueOf(def)) {
            choice = SettingChoice(def, valueOf(def)) { v ->
              prefs.edit().putString(def.key, v).apply()
              version++
            }
          }
        }
      }
      item { AtkSection("执行等待通知设置") }
      for (def in WAIT_SETTINGS) {
        item {
          AtkSettingRow(def.title, valueOf(def)) {
            choice = SettingChoice(def, valueOf(def)) { v ->
              prefs.edit().putString(def.key, v).apply()
              version++
            }
          }
        }
      }
      item { AtkSection("其它设置") }
      for (def in OTHER_SETTINGS) {
        item {
          AtkSettingRow(def.title, valueOf(def)) {
            choice = SettingChoice(def, valueOf(def)) { v ->
              prefs.edit().putString(def.key, v).apply()
              version++
            }
          }
        }
      }
      item {
        Text(
          "「服务状态」可打开新系统所需的权限自检（精确闹钟 / 通知 / 电池优化 / 全屏通知）。",
          color = AtkSkin.sectionText,
          fontSize = 13.sp,
          modifier = Modifier.padding(12.dp),
        )
      }
    }
  }

  choice?.let { c ->
    AtkChoiceDialog(
      title = c.def.title,
      options = c.def.options,
      selected = c.def.options.indexOf(c.current),
      onPick = { i -> c.onPick(c.def.options[i]); choice = null },
      onDismiss = { choice = null },
    )
  }
}

@Composable
private fun TagsScreen(onBack: () -> Unit, onPick: (Int) -> Unit) {
  val icons = listOf("\uD83C\uDF3C", "\u2602", "\uD83D\uDCC5", "\uD83C\uDF88")
  Column(Modifier.fillMaxSize()) {
    AtkTitleBar("管理标签", onBack = onBack)
    for (i in 0..3) {
      Row(
        Modifier
          .fillMaxWidth()
          .height(60.dp)
          .background(AtkSkin.bgDeep)
          .clickable { onPick(i) }
          .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(icons[i], fontSize = 22.sp, color = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(TAB_NAMES[i], color = Color.White, fontSize = 19.sp)
      }
      Box(Modifier.fillMaxWidth().height(1.dp).background(AtkSkin.divider))
    }
  }
}

/** 备份恢复 dialog: black caption, two white entries, grey cancel. */
@Composable
private fun AtkBackupDialog(onBackup: () -> Unit, onRestore: () -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF5F5F5))) {
      Box(
        Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF111111)),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text(
          "备份恢复", color = Color.White, fontSize = 20.sp,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
      Box(
        Modifier.fillMaxWidth().height(64.dp).background(Color.White).clickable(onClick = onBackup),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text("备份环境", color = Color.Black, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp))
      }
      AtkLightDivider()
      Box(
        Modifier.fillMaxWidth().height(64.dp).background(Color.White).clickable(onClick = onRestore),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text("恢复环境", color = Color.Black, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp))
      }
      Box(
        Modifier.fillMaxWidth().background(Color(0xFFD2D2D2)).padding(10.dp),
        contentAlignment = Alignment.Center,
      ) {
        AtkPlainButton("取消", Modifier.width(180.dp), onDismiss)
      }
    }
  }
}

@Composable
private fun PermissionScreen(engine: AtkEngine, onBack: () -> Unit) {
  var version by remember { mutableIntStateOf(0) }
  val report = remember(version) { engine.permissionReport() }
  Column(Modifier.fillMaxSize()) {
    AtkTitleBar("权限自检", onBack = onBack)
    LazyColumn(Modifier.weight(1f)) {
      item { AtkSection("常规设置") }
      items(report) { (name, ok) ->
        AtkSettingRow(
          title = name,
          value = if (ok) "已开启" else "未开启（点击前往系统设置）",
          onClick = {
            when {
              name.contains("精确闹钟") -> engine.openExactAlarmSettings()
              name.contains("通知") && !name.contains("全屏") -> engine.openNotificationSettings()
              name.contains("电池") -> engine.openBatterySettings()
              name.contains("全屏") -> engine.openFullScreenSettings()
            }
          },
        )
      }
      item {
        AtkSettingRow("刷新状态", "重新检测以上权限", onClick = { version++ })
      }
      item {
        Text(
          "一加 13 / ColorOS 还需要：允许自动启动、允许后台运行、电池策略设为不限制。" +
            "任何一项缺失都可能导致提醒不响或延迟。",
          color = AtkSkin.sectionText,
          fontSize = 13.sp,
          modifier = Modifier.padding(12.dp),
        )
      }
    }
  }
}

private fun toast(context: Context, text: String) {
  Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
