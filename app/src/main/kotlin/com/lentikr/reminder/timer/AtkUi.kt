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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.lentikr.reminder.R
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private enum class Screen { LIST, EDIT, SETTINGS, TAGS, PERMISSIONS }

private val CONTEXT_ENTRIES = listOf(
  "测试执行", "删除条目", "参考新建", "执行规划", "查看日志", "移动分类", "多选模式",
  "60 秒后真实触发",
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
    var timerList by remember { mutableStateOf(emptyList<AtkItem>()) }
    var tags by remember { mutableStateOf(AtkTags.load(context)) }
    var message by remember { mutableStateOf("") }
    var permissionPrompt by remember { mutableStateOf(false) }
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
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var batchMoveOpen by remember { mutableStateOf(false) }
    var batchDeleteOpen by remember { mutableStateOf(false) }

    fun refresh() {
      timerList = try { store.all() } catch (e: Exception) { emptyList() }
      try { engine.rescheduleAll() } catch (e: Exception) { }
    }

    /** Persist a new tag list and keep the current tab inside its bounds. */
    fun commitTags(next: List<AtkTag>) {
      tags = next
      AtkTags.save(context, next)
      if (tab >= next.size) tab = 0
    }

    fun renameTag(index: Int, name: String) =
      commitTags(tags.mapIndexed { i, t -> if (i == index) t.copy(name = name) else t })

    fun setTagIcon(index: Int, icon: String) =
      commitTags(tags.mapIndexed { i, t -> if (i == index) t.copy(icon = icon) else t })

    /** Swap two adjacent labels; swap the tasks' page values too so they follow their label. */
    fun moveTag(from: Int, to: Int) {
      if (from !in tags.indices || to !in tags.indices) return
      val next = tags.toMutableList()
      java.util.Collections.swap(next, from, to)
      try {
        store.remapPages(mapOf(from to to, to to from))
      } catch (e: Exception) {
        message = "调整顺序失败：" + e.message
      }
      commitTags(next)
      refresh()
    }

    /** Remove a label: its tasks move to the first tab, later tabs shift down by one. */
    fun deleteTag(index: Int) {
      if (tags.size <= 1 || index !in tags.indices) return
      val mapping = HashMap<Int, Int>()
      // Tasks of the deleted tab go to the first surviving tab (index 0).
      mapping[index] = 0
      for (p in index + 1 until tags.size) mapping[p] = p - 1
      try {
        store.remapPages(mapping)
      } catch (e: Exception) {
        message = "删除标签失败：" + e.message
      }
      val next = tags.toMutableList()
      next.removeAt(index)
      commitTags(next)
      refresh()
      toast(context, "已删除标签，任务已移到「${next.first().name}」")
    }

    // First install: the original AddinTimer ships 18 sample timers. Also republish the
    // "最近的一次任务" notification on every launch / boot so the status-bar icon stays.
    LaunchedEffect(Unit) {
      try {
        if (!prefs.getBoolean("samples_seeded", false)) {
          store.seedSamples()
          prefs.edit().putBoolean("samples_seeded", true).apply()
        }
        // 2.3.0 wrongly shipped all 18 samples enabled; correct that once, on upgrade only.
        if (!prefs.getBoolean("sample_states_fixed", false)) {
          store.resetBuiltInSampleStates()
          prefs.edit().putBoolean("sample_states_fixed", true).apply()
        }
        timerList = store.all()
        engine.rescheduleAll()
      } catch (e: Exception) {
        timerList = emptyList()
      }
      // Android 14+ can silently revoke USE_FULL_SCREEN_INTENT. Without it the alarm only
      // shows a notification and never comes up over the keyguard — exactly the reported
      // symptom — so surface the switch once per install instead of hiding it in 权限自检.
      try {
        val needFullScreen = android.os.Build.VERSION.SDK_INT >= 34 &&
          !engine.canUseFullScreenIntent()
        permissionPrompt = needFullScreen &&
          !prefs.getBoolean("fullscreen_prompt_seen", false)
      } catch (e: Exception) {
        permissionPrompt = false
      }
    }

    BackHandler(enabled = selectionMode || screen != Screen.LIST) {
      if (selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
      } else {
        screen = Screen.LIST
        // Picking 通知栏图标 in 系统设置 takes effect as soon as we return to the list.
        try { engine.rescheduleAll() } catch (e: Exception) { }
      }
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
          tags = tags,
          engine = engine,
          selectionMode = selectionMode,
          selectedIds = selectedIds,
          onTab = { tab = it; selectedIds = emptySet() },
          onNew = { newTaskOpen = true },
          onMenu = { systemMenuOpen = true },
          onEdit = { id ->
            if (selectionMode) {
              selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
            } else {
              editingId = id; preset = 0; screen = Screen.EDIT
            }
          },
          onLongClick = { item -> contextItem = item },
          onToggle = { id, on -> store.setEnabled(id, on); refresh() },
          onSelectMode = { on ->
            selectionMode = on
            if (!on) selectedIds = emptySet()
          },
          onSelectAll = {
            val visible = timerList.filter { it.pageIndex() == tab }.map { it.id }
            selectedIds = if (selectedIds.containsAll(visible)) emptySet() else visible.toSet()
          },
          onBatchMove = { batchMoveOpen = true },
          onBatchDelete = { batchDeleteOpen = true },
        )

        Screen.EDIT -> AtkEditScreen(
          item = if (editingId >= 0) store.get(editingId) else null,
          preset = preset,
          page = tab,
          onSave = { item ->
            tab = item.pageIndex()
            store.save(item)
            refresh()
            screen = Screen.LIST
          },
          onClose = { screen = Screen.LIST },
          onMessage = { message = it },
        )

        Screen.SETTINGS -> SettingsScreen(
          prefs = prefs,
          onBack = { screen = Screen.LIST },
          onPermissions = { screen = Screen.PERMISSIONS },
        )

        Screen.TAGS -> TagsScreen(
          tags = tags,
          onBack = { screen = Screen.LIST },
          onPick = { i -> tab = i; screen = Screen.LIST },
          onRename = { i, name -> renameTag(i, name) },
          onIcon = { i, icon -> setTagIcon(i, icon) },
          onDelete = { i -> deleteTag(i) },
          onMove = { from, to -> moveTag(from, to) },
          onMessage = { message = it },
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
                putExtra(Intent.EXTRA_TEXT, "Reminder：导入旧版 AddinTimer 的 .atk 备份即可继续使用原来的定时任务。")
              }
              try { context.startActivity(Intent.createChooser(send, "分享好友")) } catch (e: Exception) { }
            }
            5 -> message = "当前已是最新版本（${com.lentikr.reminder.BuildConfig.VERSION_NAME}），无需更新。"
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
        onSamples = {
          backupOpen = false
          try {
            store.seedSamples(replace = true)
            refresh()
            toast(context, "已载入 ${AtkSamples.PARAMS.size} 条原版示例任务")
          } catch (e: Exception) {
            message = "载入失败：" + e.message
          }
        },
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
            toast(
              context,
              (if (which == 0) "已恢复 $n 条" else "已追加 $n 条") +
                "；激活状态与分类均按备份原样保留",
            )
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
              // Original behaviour: 测试执行 opens the 任务执行界面 directly. The status bar
              // gets the current-task alarm icon plus the persistent next-task icon.
              // 3.1.0: force=true is essential — without it AtkAlarmActivity and
              // AtkAlarmService both bail out on a disabled task ("任务已停用，不响"), which is
              // why 测试执行 did nothing until the task was activated. A test is a user action,
              // so it must run regardless of the task's enabled flag.
              store.log(item.id, 0, "测试执行")
              engine.showNotification(item, fullScreen = false)
              engine.launchAlarmFace(item.id, force = true)
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
            6 -> {
              selectionMode = true
              selectedIds = setOf(item.id)
            }
            7 -> {
              // End-to-end check of the real firing path (alarm → foreground service → ring →
              // 执行界面) without waiting for the configured time: arm it 60 s out and lock the
              // screen. Uses its own request code so the task's normal schedule is untouched.
              engine.fireTestSoon(item.id)
              toast(context, "60 秒后按真实闹钟流程触发「${item.name}」，现在可以锁屏等待")
            }
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
      val times = try {
        Occurrence.next(item.rule, item.hour, item.minute, LocalDateTime.now(), 10)
      } catch (e: Exception) {
        emptyList<LocalDateTime>()
      }
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
      val lines = try { store.logs(item.id) } catch (e: Exception) { emptyList<String>() }
      AtkMessageDialog(
        title = "查看日志 · ${item.name}",
        body = if (lines.isEmpty()) "暂无执行日志" else lines.joinToString("\n"),
        onDismiss = { logItem = null },
      )
    }

    moveItem?.let { item ->
      AtkChoiceDialog(
        title = "移动分类",
        options = AtkTags.names(tags),
        selected = item.pageIndex(),
        onPick = { i ->
          store.moveToPage(listOf(item.id), i)
          refresh()
          moveItem = null
          tab = i
          toast(context, "已移动到「${tags[i].name}」")
        },
        onDismiss = { moveItem = null },
      )
    }

    if (batchMoveOpen) {
      AtkChoiceDialog(
        title = "批量移动分类（已选 ${selectedIds.size} 项）",
        options = AtkTags.names(tags),
        selected = tab,
        onPick = { i ->
          val n = try { store.moveToPage(selectedIds, i) } catch (e: Exception) { 0 }
          batchMoveOpen = false
          selectionMode = false
          selectedIds = emptySet()
          tab = i
          refresh()
          toast(context, "已移动 $n 项到「${tags[i].name}」")
        },
        onDismiss = { batchMoveOpen = false },
      )
    }

    if (batchDeleteOpen) {
      AtkMessageDialog(
        title = "批量删除",
        body = "确定删除选中的 ${selectedIds.size} 条任务？此操作不可撤销。",
        confirmText = "删除",
        dismissText = "取消",
        onDismiss = { batchDeleteOpen = false },
        onConfirm = {
          val n = try {
            var c = 0
            for (id in selectedIds) {
              engine.cancel(id)
              store.delete(id)
              c++
            }
            c
          } catch (e: Exception) { 0 }
          batchDeleteOpen = false
          selectionMode = false
          selectedIds = emptySet()
          refresh()
          toast(context, "已删除 $n 条任务")
        },
      )
    }

    if (aboutOpen) {
      val crash = AtkCrash.last(context)
      AtkMessageDialog(
        title = "关于软件",
        body = "Reminder ${com.lentikr.reminder.BuildConfig.VERSION_NAME} · AddinTimer 定时部分复刻版\n" +
          "数据格式与 AddinTimer 2.1G 完全一致，可导入 / 导出 .atk 备份。\n" +
          "导入时逐字节保留激活状态与分类，不自动启用任何任务。\n" +
          "已去除原版未注册版的激活数量上限，任意多条提醒可同时启用。\n" +
          "首次启动载入 18 条原版示例任务（默认全部未激活）；参数设置以原版截图为准。\n" +
          "标题栏「多选」可勾选多条任务，批量移动分类或批量删除。\n" +
          "Android 12+ 请在「系统设置 → 服务状态」完成精确闹钟、通知、电池优化与全屏通知授权。" +
          (if (crash.isNotBlank()) "\n\n最近一次崩溃日志：\n$crash" else ""),
        onDismiss = { aboutOpen = false },
      )
    }

    if (permissionPrompt) {
      AtkMessageDialog(
        title = "需要开启「全屏通知」",
        body = "检测到系统未授予本应用全屏通知权限。Android 14+ 默认关闭该权限，" +
          "关闭时闹钟到点只会在通知栏提示，锁屏下无法点亮屏幕并弹出执行界面。" +
          "请点「去开启」，在系统设置里允许本应用的全屏通知。",
        confirmText = "去开启",
        onConfirm = {
          permissionPrompt = false
          prefs.edit().putBoolean("fullscreen_prompt_seen", true).apply()
          engine.openFullScreenSettings()
        },
        dismissText = "暂不",
        onDismiss = {
          permissionPrompt = false
          prefs.edit().putBoolean("fullscreen_prompt_seen", true).apply()
        },
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
  tags: List<AtkTag>,
  engine: AtkEngine,
  selectionMode: Boolean,
  selectedIds: Set<Long>,
  onTab: (Int) -> Unit,
  onNew: () -> Unit,
  onMenu: () -> Unit,
  onEdit: (Long) -> Unit,
  onLongClick: (AtkItem) -> Unit,
  onToggle: (Long, Boolean) -> Unit,
  onSelectMode: (Boolean) -> Unit,
  onSelectAll: () -> Unit,
  onBatchMove: () -> Unit,
  onBatchDelete: () -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    AtkTitleBar(
      "Reminder",
      suffix = if (selectionMode) "  已选 ${selectedIds.size} 项" else "",
      suffixColor = AtkSkin.green,
      onMenu = onMenu,
      onSelect = { onSelectMode(!selectionMode) },
      selectMode = selectionMode,
    )
    Spacer(Modifier.height(4.dp))
    AtkBlueButton("新增任务", onNew)
    Spacer(Modifier.height(4.dp))
    val visible = timerList.filter { it.pageIndex() == tab }
    LazyColumn(Modifier.weight(1f).background(AtkSkin.bg)) {
      items(visible, key = { it.id }) { item ->
        AtkTimerRow(
          item = item,
          next = if (item.enabled) {
            try { engine.nextMillis(item) } catch (e: Exception) { 0L }
          } else {
            0L
          },
          selectionMode = selectionMode,
          selected = item.id in selectedIds,
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
    if (selectionMode) {
      Row(
        Modifier
          .fillMaxWidth()
          .height(52.dp)
          .background(Color(0xFF141414))
          .border(1.dp, AtkSkin.divider),
      ) {
        SelectionButton("全选", Modifier.weight(1f), onSelectAll)
        SelectionButton(
          "移动分类 (${selectedIds.size})",
          Modifier.weight(2f),
          onBatchMove,
          enabled = selectedIds.isNotEmpty(),
        )
        SelectionButton(
          "删除 (${selectedIds.size})",
          Modifier.weight(2f),
          onBatchDelete,
          enabled = selectedIds.isNotEmpty(),
        )
        SelectionButton("取消", Modifier.weight(1f), onClick = { onSelectMode(false) })
      }
    }
    AtkTabBar(tab, onTab, tags)
  }
}

@Composable
private fun SelectionButton(
  text: String,
  modifier: Modifier,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  Box(
    modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      color = if (enabled) Color.White else Color(0xFF6A6A6A),
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AtkTimerRow(
  item: AtkItem,
  next: Long,
  selectionMode: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onToggle: (Boolean) -> Unit,
) {
  // java.time.LocalDate.ofInstant is API 34; compare day strings instead so API 29+ works.
  val tomorrow = next > 0 && try {
    val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    dayFmt.format(Date(next)) == dayFmt.format(Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000))
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
      if (selectionMode) {
        Box(
          Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(2.dp, if (selected) AtkSkin.green else Color(0xFF9A9A9A), RoundedCornerShape(4.dp))
            .background(if (selected) AtkSkin.green else Color.Transparent),
          contentAlignment = Alignment.Center,
        ) {
          if (selected) Text("✓", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
      }
      Image(
        painter = painterResource(R.drawable.ic_atk_stat),
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
          "「服务状态」可打开新系统所需的权限自检（精确闹钟 / 通知 / 电池优化 / 全屏通知）。" +
            "通知栏图标设为「不显示」时，下拉里的「下一次任务」通知会被隐藏。",
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

private val TAG_ACTIONS = listOf("修改标签", "修改图标", "删除标签", "向上移动", "向下移动")

/**
 * 管理标签. Tap a row to jump to that tab; LONG-PRESS opens the original's action menu
 * (修改标签 / 修改图标 / 删除标签 / 向上移动 / 向下移动). All edits persist via [AtkTags] and
 * task→tab remapping is delegated to the caller so the database stays in sync.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagsScreen(
  tags: List<AtkTag>,
  onBack: () -> Unit,
  onPick: (Int) -> Unit,
  onRename: (Int, String) -> Unit,
  onIcon: (Int, String) -> Unit,
  onDelete: (Int) -> Unit,
  onMove: (Int, Int) -> Unit,
  onMessage: (String) -> Unit,
) {
  var menuIndex by remember { mutableStateOf(-1) }
  var renameIndex by remember { mutableStateOf(-1) }
  var iconIndex by remember { mutableStateOf(-1) }
  var deleteIndex by remember { mutableStateOf(-1) }

  Column(Modifier.fillMaxSize()) {
    AtkTitleBar("管理标签", onBack = onBack)
    tags.forEachIndexed { i, tag ->
      Row(
        Modifier
          .fillMaxWidth()
          .height(60.dp)
          .background(AtkSkin.bgDeep)
          .combinedClickable(
            onClick = { onPick(i) },
            onLongClick = { menuIndex = i },
          )
          .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(tag.icon, fontSize = 22.sp, color = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(tag.name, color = Color.White, fontSize = 19.sp)
      }
      Box(Modifier.fillMaxWidth().height(1.dp).background(AtkSkin.divider))
    }
    Text(
      "点击标签进入该分类；长按标签可修改名称、图标、删除或调整顺序。",
      color = AtkSkin.sectionText,
      fontSize = 13.sp,
      modifier = Modifier.padding(14.dp),
    )
  }

  if (menuIndex in tags.indices) {
    val i = menuIndex
    AtkLightContextMenu(
      entries = TAG_ACTIONS,
      onPick = { which ->
        menuIndex = -1
        when (which) {
          0 -> renameIndex = i
          1 -> iconIndex = i
          2 -> deleteIndex = i
          3 -> {
            if (i == 0) onMessage("已经在最上方") else onMove(i, i - 1)
          }
          4 -> {
            if (i == tags.lastIndex) onMessage("已经在最下方") else onMove(i, i + 1)
          }
        }
      },
      onDismiss = { menuIndex = -1 },
    )
  }

  if (renameIndex in tags.indices) {
    val i = renameIndex
    AtkInputDialog(
      title = "修改标签",
      initial = tags[i].name,
      onConfirm = { text ->
        renameIndex = -1
        if (text.isBlank()) onMessage("标签名不能为空") else onRename(i, text.trim())
      },
      onDismiss = { renameIndex = -1 },
    )
  }

  if (iconIndex in tags.indices) {
    val i = iconIndex
    AtkIconPickerDialog(
      current = tags[i].icon,
      onPick = { icon -> iconIndex = -1; onIcon(i, icon) },
      onDismiss = { iconIndex = -1 },
    )
  }

  if (deleteIndex in tags.indices) {
    val i = deleteIndex
    AtkMessageDialog(
      title = "删除标签",
      body = if (tags.size <= 1) {
        "至少需要保留一个标签，无法删除。"
      } else {
        val survivor = if (i == 0) tags[1].name else tags[0].name
        "确定删除标签「${tags[i].name}」？该分类下的任务会自动移到「$survivor」。"
      },
      confirmText = "删除",
      dismissText = "取消",
      onDismiss = { deleteIndex = -1 },
      onConfirm = {
        deleteIndex = -1
        if (tags.size <= 1) onMessage("至少需要保留一个标签") else onDelete(i)
      },
    )
  }
}

/** 备份恢复 dialog: black caption, white entries, grey cancel. */
@Composable
private fun AtkBackupDialog(
  onBackup: () -> Unit,
  onRestore: () -> Unit,
  onSamples: () -> Unit,
  onDismiss: () -> Unit,
) {
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
      BackupEntry("备份环境", onBackup)
      AtkLightDivider()
      BackupEntry("恢复环境", onRestore)
      AtkLightDivider()
      BackupEntry("载入原版示例任务", onSamples)
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
private fun BackupEntry(text: String, onClick: () -> Unit) {
  Box(
    Modifier.fillMaxWidth().height(64.dp).background(Color.White).clickable(onClick = onClick),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(text, color = Color.Black, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp))
  }
}

@Composable
private fun PermissionScreen(engine: AtkEngine, onBack: () -> Unit) {
  val context = LocalContext.current
  var version by remember { mutableIntStateOf(0) }
  var showLog by remember { mutableStateOf(false) }
  var logText by remember { mutableStateOf("") }
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
              name.contains("悬浮窗") -> engine.openOverlaySettings()
            }
          },
        )
      }
      item {
        AtkSettingRow("刷新状态", "重新检测以上权限", onClick = { version++ })
      }
      item { AtkSection("诊断") }
      item {
        AtkSettingRow("触发日志", "最近 200 条：触发 / 前台服务 / 播铃 / 执行界面", onClick = {
          logText = AtkDiag.text(context)
          showLog = true
        })
      }
      item {
        Text(
          "锁屏闹钟必须开启「全屏通知」，否则到点只在通知栏提示，要解锁后点通知才会打开执行界面。" +
            "一加 13 / ColorOS 另外还需要：允许自动启动、允许后台运行、允许锁屏显示、电池策略设为不限制。" +
            "任何一项缺失都可能导致提醒不响或延迟。到点不响时请先看「触发日志」：没有记录说明闹钟没被系统唤醒，" +
            "有记录但没有「起播」说明铃声/音频被拦。",
          color = AtkSkin.sectionText,
          fontSize = 13.sp,
          modifier = Modifier.padding(12.dp),
        )
      }
    }
  }
  if (showLog) {
    Dialog(onDismissRequest = { showLog = false }) {
      // 3.1.0: a hard 420.dp list pushed the bottom 清空/刷新/关闭 row and the newest log
      // lines off the screen on this device. Cap the whole dialog, keep the list bounded,
      // respect the navigation bar and start scrolled to the newest entries.
      Column(
        Modifier
          .fillMaxWidth()
          .heightIn(max = 480.dp)
          .background(AtkSkin.lightPanel)
          .navigationBarsPadding(),
      ) {
        AtkDialogTitle("触发日志")
        val lines = logText.split("\n")
        val listState = rememberLazyListState()
        LaunchedEffect(logText) {
          if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
        ) {
          items(lines) { line ->
            Text(
              line,
              color = Color(0xFF202020),
              fontSize = 12.sp,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
            )
          }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFFD2D2D2)).padding(8.dp)) {
          AtkPlainButton("清空", Modifier.weight(1f)) {
            AtkDiag.clear(context)
            logText = AtkDiag.text(context)
          }
          Spacer(Modifier.width(8.dp))
          AtkPlainButton("刷新", Modifier.weight(1f)) { logText = AtkDiag.text(context) }
          Spacer(Modifier.width(8.dp))
          AtkPlainButton("关闭", Modifier.weight(1f)) { showLog = false }
        }
      }
    }
  }
}

private fun toast(context: Context, text: String) {
  Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
