package com.lentikr.reminder.timer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Visual language of AddinTimer 2.1G: charcoal background, silver gradient title bar,
 * blue gradient action bar, silver gradient spinners and dark section strips.
 * Every widget is drawn explicitly so the look does not depend on the host app's theme.
 */
object AtkSkin {
  val bg = Color(0xFF2B2B2B)
  val bgDeep = Color(0xFF1C1C1C)
  val bgRow = Color(0xFF232323)
  val section = Color(0xFF333333)
  val sectionText = Color(0xFFCBCBCB)
  val label = Color(0xFFE8E8E8)
  val sub = Color(0xFF8FAAC4)
  val blue = Color(0xFF2196E5)
  val blueLight = Color(0xFF5CBBF2)
  val green = Color(0xFF74CC24)
  val red = Color(0xFFE23737)
  val divider = Color(0xFF404040)
  val ink = Color(0xFF191919)

  val silver = Brush.verticalGradient(
    listOf(Color(0xFFFEFEFE), Color(0xFFE9E9E9), Color(0xFFD6D6D6), Color(0xFFC4C4C4)),
  )
  val titleBar = Brush.verticalGradient(listOf(Color(0xFFE8E8E8), Color(0xFFAAAAAA)))
  val blueBar = Brush.verticalGradient(
    listOf(Color(0xFF0A55B2), Color(0xFF1585DA), Color(0xFF39B4F5)),
  )
  val darkButton = Brush.verticalGradient(listOf(Color(0xFF4C4C4C), Color(0xFF191919)))
  val tabOff = Brush.verticalGradient(listOf(Color(0xFF353535), Color(0xFF141414)))
  val dialogTitle = Brush.verticalGradient(listOf(Color(0xFF20548F), Color(0xFF0A2650)))
  val dialogBlue = Brush.verticalGradient(listOf(Color(0xFF0F79BE), Color(0xFF0A4B91)))
  val lightPanel = Color(0xFFF1F1F1)
}

/** Force AddinTimer's dark palette even when the host app follows a light system theme. */
@Composable
fun AtkTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = AtkSkin.blueLight,
      background = AtkSkin.bg,
      surface = AtkSkin.bg,
      onBackground = Color.White,
      onSurface = Color.White,
      error = AtkSkin.red,
    ),
    content = content,
  )
}

/** Silver title strip at the very top of every original screen. */
@Composable
fun AtkTitleBar(
  title: String,
  suffix: String = "",
  suffixColor: Color = AtkSkin.red,
  onBack: (() -> Unit)? = null,
  onMenu: (() -> Unit)? = null,
) {
  Box(
    Modifier
      .fillMaxWidth()
      .height(34.dp)
      .background(AtkSkin.titleBar),
  ) {
    Row(
      Modifier.fillMaxSize().padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (onBack != null) {
        Text(
          "‹", color = AtkSkin.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.clickable(onClick = onBack).padding(end = 8.dp),
        )
      }
      Text(title, color = AtkSkin.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
      if (suffix.isNotEmpty()) {
        Text(suffix, color = suffixColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
      }
      Spacer(Modifier.weight(1f))
      if (onMenu != null) {
        Text(
          "≡", color = Color(0xFF333333), fontSize = 22.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.clickable(onClick = onMenu).padding(horizontal = 8.dp),
        )
      }
    }
  }
}

/** The big blue "新增任务" bar. */
@Composable
fun AtkBlueButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .fillMaxWidth()
      .height(54.dp)
      .padding(horizontal = 2.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(AtkSkin.blueBar)
      .border(1.dp, Color(0xFF0A458F), RoundedCornerShape(3.dp))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("⊕", color = Color.White, fontSize = 20.sp)
      Spacer(Modifier.width(10.dp))
      Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
  }
}

/** Dark strip that separates groups (常规设置 / 执行策略 / 时循环设置 ...). */
@Composable
fun AtkSection(text: String) {
  Box(
    Modifier.fillMaxWidth().height(26.dp).background(AtkSkin.section),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(
      text, color = AtkSkin.sectionText, fontSize = 13.sp,
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}

/** Silver gradient spinner box with a small ▼ on the right. */
@Composable
fun AtkSpinner(value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .height(44.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(AtkSkin.silver)
      .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(3.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        value,
        color = AtkSkin.ink,
        fontSize = 17.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(6.dp))
      Text("▼", color = Color(0xFF333333), fontSize = 12.sp)
    }
  }
}

/**
 * A property row: fixed-width label on the left, spinner on the right.
 * The label never wraps into the value, which is what broke "选择月份" before.
 */
@Composable
fun AtkSpinnerRow(label: String, value: String, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      label,
      color = AtkSkin.label,
      fontSize = 15.sp,
      maxLines = 1,
      modifier = Modifier.width(92.dp),
    )
    AtkSpinner(value, onClick, Modifier.weight(1f))
  }
}

/** Two chunky tab buttons at the top of the property editor. */
@Composable
fun AtkTabButton(symbol: String, text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .height(44.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(if (selected) AtkSkin.darkButton else AtkSkin.tabOff)
      .border(
        1.dp,
        if (selected) Color(0xFF7C7C7C) else Color(0xFF4A4A4A),
        RoundedCornerShape(6.dp),
      )
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(symbol, color = if (selected) Color.White else Color(0xFFAAAAAA), fontSize = 17.sp)
      Spacer(Modifier.width(8.dp))
      Text(text, color = if (selected) Color.White else Color(0xFFC4C4C4), fontSize = 17.sp)
    }
  }
}

private fun Modifier.actionButtonShape() = this.clip(RoundedCornerShape(2.dp))

/** Blue-framed 确定 / 取消 bar pinned to the bottom of the editor. */
@Composable
fun AtkActionBar(onOk: () -> Unit, onCancel: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(Color(0xFF161616))
      .padding(4.dp)
      .border(2.dp, Color(0xFF1490DE)),
  ) {
    AtkActionButton("✓", "确定", AtkSkin.green, Modifier.weight(1f), onOk)
    Spacer(Modifier.width(6.dp))
    AtkActionButton("✗", "取消", AtkSkin.red, Modifier.weight(1f), onCancel)
  }
}

@Composable
private fun AtkActionButton(
  symbol: String,
  text: String,
  symbolColor: Color,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  Box(
    modifier
      .height(52.dp)
      .actionButtonShape()
      .background(AtkSkin.silver)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(symbol, color = symbolColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.width(14.dp))
      Text(text, color = AtkSkin.ink, fontSize = 18.sp)
    }
  }
}

/** Classic single-choice spinner popup (dark caption, white list, grey cancel). */
@Composable
fun AtkChoiceDialog(
  title: String,
  options: List<String>,
  selected: Int = -1,
  onPick: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().background(AtkSkin.lightPanel)) {
      AtkDialogTitle(title)
      LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
        itemsIndexed(options) { index, option ->
          Box(
            Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .background(
                if (index == selected) Color(0xFFDCEBFA) else Color.White,
              )
              .clickable { onPick(index) }
              .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(option, color = Color.Black, fontSize = 17.sp, modifier = Modifier.weight(1f))
              if (index == selected) Text("●", color = AtkSkin.blue, fontSize = 12.sp)
            }
          }
          AtkLightDivider()
        }
      }
      AtkDialogCancel(onDismiss)
    }
  }
}

/** Multi-select popup used by 选择星期 / 选择月份 / 排除几号 etc. */
@Composable
fun AtkMultiDialog(
  title: String,
  options: List<String>,
  selected: Set<Int>,
  onConfirm: (Set<Int>) -> Unit,
  onDismiss: () -> Unit,
) {
  var temp by remember { mutableStateOf(selected) }
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().background(AtkSkin.lightPanel)) {
      AtkDialogTitle(title)
      LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
        itemsIndexed(options) { index, option ->
          Row(
            Modifier
              .fillMaxWidth()
              .heightIn(min = 46.dp)
              .background(Color.White)
              .clickable {
                val next = temp.toMutableSet()
                if (index in next) next.remove(index) else next.add(index)
                temp = next
              }
              .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              Modifier
                .size(24.dp)
                .border(2.dp, Color(0xFF888888), RoundedCornerShape(2.dp))
                .background(if (index in temp) Color(0xFFDCEBFA) else Color.White),
              contentAlignment = Alignment.Center,
            ) {
              if (index in temp) {
                Text("✓", color = AtkSkin.green, fontSize = 17.sp, fontWeight = FontWeight.Bold)
              }
            }
            Spacer(Modifier.width(12.dp))
            Text(option, color = Color.Black, fontSize = 17.sp)
          }
          AtkLightDivider()
        }
      }
      Row(Modifier.fillMaxWidth().background(Color(0xFFDDDDDD)).padding(8.dp)) {
        AtkPlainButton("确定", Modifier.weight(1f)) { onConfirm(temp) }
        Spacer(Modifier.width(8.dp))
        AtkPlainButton("取消", Modifier.weight(1f), onDismiss)
      }
    }
  }
}

/** Text/number entry popup (任务名称, 间隔天数, 选择路径 ...). */
@Composable
fun AtkInputDialog(
  title: String,
  initial: String,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
  numeric: Boolean = false,
) {
  var text by remember { mutableStateOf(initial) }
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().background(AtkSkin.lightPanel)) {
      AtkDialogTitle(title)
      BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(color = Color.Black, fontSize = 17.sp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp)
          .background(Color.White)
          .border(1.dp, Color(0xFF888888))
          .padding(12.dp),
      )
      Row(Modifier.fillMaxWidth().background(Color(0xFFDDDDDD)).padding(8.dp)) {
        AtkPlainButton("确定", Modifier.weight(1f)) { onConfirm(text) }
        Spacer(Modifier.width(8.dp))
        AtkPlainButton("取消", Modifier.weight(1f), onDismiss)
      }
    }
  }
}

/** Simple message / confirm popup. */
@Composable
fun AtkMessageDialog(
  title: String,
  body: String,
  onDismiss: () -> Unit,
  confirmText: String = "知道了",
  onConfirm: () -> Unit = onDismiss,
  dismissText: String? = null,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().background(AtkSkin.lightPanel)) {
      AtkDialogTitle(title)
      Text(
        body,
        color = Color.Black,
        fontSize = 16.sp,
        modifier = Modifier.padding(16.dp),
      )
      Row(Modifier.fillMaxWidth().background(Color(0xFFDDDDDD)).padding(8.dp)) {
        AtkPlainButton(confirmText, Modifier.weight(1f), onConfirm)
        if (dismissText != null) {
          Spacer(Modifier.width(8.dp))
          AtkPlainButton(dismissText, Modifier.weight(1f), onDismiss)
        }
      }
    }
  }
}

@Composable
private fun AtkDialogTitle(text: String) {
  Box(
    Modifier.fillMaxWidth().height(46.dp).background(AtkSkin.dialogBlue),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(
      text, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
  }
}

@Composable
private fun AtkDialogCancel(onDismiss: () -> Unit) {
  Box(
    Modifier.fillMaxWidth().background(Color(0xFFD2D2D2)).padding(10.dp),
    contentAlignment = Alignment.Center,
  ) {
    AtkPlainButton("取消", Modifier.width(180.dp), onDismiss)
  }
}

@Composable
fun AtkPlainButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Box(
    modifier
      .height(44.dp)
      .clip(RoundedCornerShape(2.dp))
      .background(AtkSkin.silver)
      .border(1.dp, Color(0xFF999999), RoundedCornerShape(2.dp))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = AtkSkin.ink, fontSize = 16.sp)
  }
}

@Composable
fun AtkLightDivider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFD8D8D8)))
}

/** Bottom tab strip: 常用 / 生活 / 纪念日 / 生日 (page 100..103). */
@Composable
fun AtkTabBar(selected: Int, onSelect: (Int) -> Unit) {
  val icons = listOf("\uD83C\uDF3C", "\u2602", "\uD83D\uDCC5", "\uD83C\uDF88")
  val names = listOf("常用", "生活", "纪念日", "生日")
  Row(Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF0E0E0E))) {
    names.forEachIndexed { i, name ->
      val on = i == selected
      Box(
        Modifier
          .weight(1f)
          .fillMaxHeight()
          .background(if (on) AtkSkin.blueBar else AtkSkin.tabOff)
          .clickable { onSelect(i) }
          .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(icons[i], fontSize = 18.sp, color = if (on) Color.White else Color(0xFFBDBDBD))
          Spacer(Modifier.height(2.dp))
          Text(
            name,
            fontSize = 13.sp,
            color = if (on) Color.White else Color(0xFFC8C8C8),
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
          )
        }
      }
    }
  }
}

/** Long-press popup: dark navy panel, white centred entries. */
@Composable
fun AtkContextMenu(entries: List<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .fillMaxWidth()
        .background(Color(0xFF0C2E60))
        .border(2.dp, Color(0xFF9EB9D8)),
    ) {
      entries.forEachIndexed { index, entry ->
        Box(
          Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable { onPick(index) },
          contentAlignment = Alignment.Center,
        ) {
          Text(entry, color = Color.White, fontSize = 18.sp)
        }
        if (index != entries.lastIndex) {
          Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3C6192)))
        }
      }
    }
  }
}

/**
 * System menu: light 2x4 grid. The whole overlay is inset above the system navigation bar;
 * without navigationBarsPadding the second row of labels lands underneath the 3-button bar.
 */
@Composable
fun AtkSystemMenu(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
  val names = listOf(
    "新增任务", "系统设置", "管理标签", "备份恢复",
    "分享好友", "检查更新", "建议反馈", "关于软件",
  )
  // Use the app's own vectors: framework drawables are not guaranteed on every OEM ROM.
  val icons = listOf(
    com.lentikr.reminder.R.drawable.ic_add,
    com.lentikr.reminder.R.drawable.ic_settings,
    com.lentikr.reminder.R.drawable.ic_calendar_month,
    com.lentikr.reminder.R.drawable.ic_restore_page,
    com.lentikr.reminder.R.drawable.ic_share,
    com.lentikr.reminder.R.drawable.ic_event_repeat,
    com.lentikr.reminder.R.drawable.ic_edit,
    com.lentikr.reminder.R.drawable.ic_info,
  )
  Box(
    Modifier
      .fillMaxSize()
      .background(Color(0x99000000))
      .clickable(onClick = onDismiss)
      .navigationBarsPadding(),
    contentAlignment = Alignment.BottomCenter,
  ) {
    Column(
      Modifier
        .fillMaxWidth()
        .background(Color(0xFFF0F0F0))
        .clickable(enabled = false, onClick = {})
        .padding(top = 10.dp, bottom = 14.dp),
    ) {
      for (row in 0..1) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          for (col in 0..3) {
            val i = row * 4 + col
            Column(
              Modifier.weight(1f).height(74.dp).clickable { onPick(i) },
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
            ) {
              Image(
                painter = painterResource(icons[i]),
                contentDescription = names[i],
                colorFilter = ColorFilter.tint(Color(0xFF777777)),
                modifier = Modifier.size(30.dp),
              )
              Spacer(Modifier.height(4.dp))
              Text(names[i], color = Color(0xFF222222), fontSize = 13.sp, maxLines = 1)
            }
          }
        }
      }
    }
  }
}

/** "提醒管理" popup shown by 新增任务 — only the reminder group, as requested. */
@Composable
fun AtkNewTaskDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
  val names = listOf("定时提醒", "快速提醒", "整点报时")
  val icons = listOf("\u23F0", "\u23F1", "\uD83D\uDD50")
  Dialog(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .fillMaxWidth()
        .border(2.dp, Color(0xFF9EB9D8))
        .background(Color(0xFF0A2A55)),
    ) {
      Box(
        Modifier.fillMaxWidth().height(48.dp).background(AtkSkin.dialogTitle),
        contentAlignment = Alignment.CenterStart,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
          Text("⊖", color = Color.White, fontSize = 20.sp)
          Spacer(Modifier.width(10.dp))
          Text("提醒管理", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
      }
      Row(
        Modifier
          .fillMaxWidth()
          .height(118.dp)
          .background(AtkSkin.dialogBlue),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        names.forEachIndexed { i, name ->
          Column(
            Modifier.weight(1f).clickable { onPick(i) },
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(icons[i], fontSize = 34.sp)
            Spacer(Modifier.height(8.dp))
            Text(name, color = Color.White, fontSize = 14.sp)
          }
        }
      }
    }
  }
}

/** A settings row: big title, blue current value, chevron. */
@Composable
fun AtkSettingRow(title: String, value: String, onClick: () -> Unit) {
  Column {
    Row(
      Modifier
        .fillMaxWidth()
        .height(66.dp)
        .background(AtkSkin.bgDeep)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(title, color = Color.White, fontSize = 19.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = AtkSkin.blueLight, fontSize = 14.sp)
      }
      Text("›", color = Color(0xFFBDBDBD), fontSize = 26.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(AtkSkin.divider))
  }
}
