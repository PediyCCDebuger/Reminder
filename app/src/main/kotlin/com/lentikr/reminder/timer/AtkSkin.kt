package com.lentikr.reminder.timer

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
  onSelect: (() -> Unit)? = null,
  selectMode: Boolean = false,
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
      if (onSelect != null) {
        Text(
          if (selectMode) "取消多选" else "多选",
          color = if (selectMode) AtkSkin.red else Color(0xFF333333),
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.clickable(onClick = onSelect).padding(horizontal = 8.dp),
        )
      }
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

/**
 * Classic single-choice spinner popup, matching AddinTimer 2.1G: black caption, white list,
 * an outlined radio circle on EVERY row with a green filled dot on the selected one, and a grey
 * cancel strip. (3.0.0: the previous build drew a blue caption, a light-blue highlight and a
 * small blue text dot — none of which exists in the original.)
 */
@Composable
fun AtkChoiceDialog(
  title: String,
  options: List<String>,
  selected: Int = -1,
  onPick: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
      AtkDialogTitle(title)
      LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
        itemsIndexed(options) { index, option ->
          Row(
            Modifier
              .fillMaxWidth()
              .heightIn(min = 56.dp)
              .background(Color.White)
              .clickable { onPick(index) }
              .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              option,
              color = Color.Black,
              fontSize = 18.sp,
              modifier = Modifier.weight(1f),
            )
            // Outlined radio circle on every row (not just the selected one); the selected
            // row gets the original's green filled dot inside it.
            Box(
              Modifier
                .size(24.dp)
                .border(2.dp, Color(0xFF9E9E9E), CircleShape),
              contentAlignment = Alignment.Center,
            ) {
              if (index == selected) {
                Box(Modifier.size(13.dp).background(AtkSkin.green, CircleShape))
              }
            }
          }
          AtkLightDivider()
        }
      }
      AtkDialogCancel(onDismiss)
    }
  }
}

/**
 * 修改图标 picker for 管理标签: black caption, white emoji grid, grey cancel — the same
 * classic window style as [AtkChoiceDialog].
 */
@Composable
fun AtkIconPickerDialog(
  current: String,
  onPick: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
      AtkDialogTitle("修改图标")
      Column(Modifier.fillMaxWidth().padding(10.dp)) {
        AtkTags.ICONS.chunked(5).forEach { row ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            row.forEach { icon ->
              Box(
                Modifier
                  .padding(4.dp)
                  .size(54.dp)
                  .clip(RoundedCornerShape(4.dp))
                  .border(
                    2.dp,
                    if (icon == current) AtkSkin.green else Color(0xFFE0E0E0),
                    RoundedCornerShape(4.dp),
                  )
                  .background(if (icon == current) Color(0xFFF2FBE9) else Color.White)
                  .clickable { onPick(icon) },
                contentAlignment = Alignment.Center,
              ) {
                Text(icon, fontSize = 26.sp)
              }
            }
          }
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
                .background(Color.White),
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
  message: String = "",
) {
  var text by remember { mutableStateOf(initial) }
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().background(AtkSkin.lightPanel)) {
      AtkDialogTitle(title)
      if (message.isNotBlank()) {
        Text(
          message,
          color = Color(0xFF222222),
          fontSize = 22.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
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

/**
 * 3.7.1：原版「手动选择贪睡时间」弹窗 —— 深色面板、白色居中选项、细分隔线，点一行立即选择，
 * 没有标题栏和底部按钮。选项由调用方按「从长到短」传入（45 / 30 / 15 / 10 / 5 / 3 分钟）。
 */
@Composable
fun AtkDarkListDialog(
  options: List<String>,
  onPick: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .fillMaxWidth()
        .background(Color(0xFF101010))
        .border(1.dp, Color(0xFF8A8A8A)),
    ) {
      options.forEachIndexed { index, option ->
        Box(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable { onPick(index) },
          contentAlignment = Alignment.Center,
        ) {
          Text(option, color = Color.White, fontSize = 18.sp)
        }
        if (index != options.lastIndex) {
          Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF4A4A4A)))
        }
      }
    }
  }
}

/** 3.7.1：原版「终止确认」警告框 —— 深色面板、白色标题与正文、底部银灰「确定 / 取消」。 */
@Composable
fun AtkDarkConfirmDialog(
  title: String,
  body: String,
  confirmText: String,
  dismissText: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .fillMaxWidth()
        .background(Color(0xFF101010))
        .border(1.dp, Color(0xFF8A8A8A)),
    ) {
      Text(
        title,
        color = Color.White,
        fontSize = 20.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )
      Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF4A4A4A)))
      Text(
        body,
        color = Color.White,
        fontSize = 17.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
      )
      Row(Modifier.fillMaxWidth().background(Color(0xFFD2D2D2)).padding(6.dp)) {
        AtkPlainButton(confirmText, Modifier.weight(1f), onConfirm)
        Spacer(Modifier.width(6.dp))
        AtkPlainButton(dismissText, Modifier.weight(1f), onDismiss)
      }
    }
  }
}

/** Original AddinTimer dialogs use a plain black caption strip with white regular-weight text. */
@Composable
fun AtkDialogTitle(text: String) {
  Box(
    Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF111111)),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(
      text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Normal,
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

/** Bottom tab strip: labels come from 管理标签 (editable names + icons, page 100 + index). */
@Composable
fun AtkTabBar(selected: Int, onSelect: (Int) -> Unit, tags: List<AtkTag> = AtkTags.DEFAULT) {
  Row(Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF0E0E0E))) {
    tags.forEachIndexed { i, tag ->
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
          Text(tag.icon, fontSize = 18.sp, color = if (on) Color.White else Color(0xFFBDBDBD))
          Spacer(Modifier.height(2.dp))
          Text(
            tag.name,
            fontSize = 13.sp,
            color = if (on) Color.White else Color(0xFFC8C8C8),
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/**
 * 管理标签 long-press menu: plain white panel, black left-aligned entries with hairline
 * dividers (the original AddinTimer look, see 管理标签.png). Kept separate from the dark
 * [AtkContextMenu] used on the task list.
 */
@Composable
fun AtkLightContextMenu(
  entries: List<String>,
  onPick: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth(0.78f).background(Color.White)) {
      entries.forEachIndexed { index, entry ->
        Box(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable { onPick(index) }
            .padding(horizontal = 22.dp, vertical = 8.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          Text(entry, color = Color.Black, fontSize = 19.sp)
        }
        if (index != entries.lastIndex) AtkLightDivider()
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

/**
 * 3.7.0：三个入口改用同一套自绘矢量时钟图标。此前用的是三个 emoji 字符，系统字体给它们的
 * 字形尺寸与基线各不相同（「快速提醒」的 ⏱ 明显更小、文字也更靠下），换成固定 48dp 的
 * Canvas 图标 + 固定高度容器后，三个图标等大、文字基线完全对齐。
 */
@Composable
private fun AtkNewTaskIcon(kind: Int) {
  Canvas(Modifier.size(48.dp)) {
    val c = center
    val r = size.minDimension * 0.30f
    val stroke = 2.2.dp.toPx()
    val white = Color.White
    val accent = Color(0xFFFF5A5A)
    drawCircle(white, r, c, style = Stroke(stroke))
    drawLine(white, c, Offset(c.x, c.y - r * 0.58f), stroke, StrokeCap.Round)
    drawLine(white, c, Offset(c.x + r * 0.48f, c.y + r * 0.26f), stroke, StrokeCap.Round)
    drawCircle(accent, 2.2.dp.toPx(), c)
    when (kind) {
      0 -> { // 定时提醒：闹钟双铃 + 双脚
        drawCircle(accent, r * 0.22f, Offset(c.x - r * 0.78f, c.y - r * 0.92f))
        drawCircle(accent, r * 0.22f, Offset(c.x + r * 0.78f, c.y - r * 0.92f))
        drawLine(
          accent,
          Offset(c.x - r * 0.55f, c.y + r * 0.82f),
          Offset(c.x - r * 0.85f, c.y + r * 1.12f),
          stroke, StrokeCap.Round,
        )
        drawLine(
          accent,
          Offset(c.x + r * 0.55f, c.y + r * 0.82f),
          Offset(c.x + r * 0.85f, c.y + r * 1.12f),
          stroke, StrokeCap.Round,
        )
      }
      1 -> { // 快速提醒：秒表表冠 + 侧键
        drawLine(
          accent,
          Offset(c.x, c.y - r - 1.dp.toPx()),
          Offset(c.x, c.y - r - 7.dp.toPx()),
          4.dp.toPx(), StrokeCap.Round,
        )
        drawLine(
          accent,
          Offset(c.x + r * 0.86f, c.y - r * 0.62f),
          Offset(c.x + r * 1.14f, c.y - r * 0.86f),
          4.dp.toPx(), StrokeCap.Round,
        )
      }
      else -> { // 整点报时：12 个整点刻度
        for (i in 0 until 12) {
          val a = Math.toRadians(i * 30.0 - 90.0)
          val inner = r * 0.76f
          val outer = r * 0.94f
          val sin = kotlin.math.sin(a)
          val cos = kotlin.math.cos(a)
          drawLine(
            accent,
            Offset(c.x + (cos * inner).toFloat(), c.y + (sin * inner).toFloat()),
            Offset(c.x + (cos * outer).toFloat(), c.y + (sin * outer).toFloat()),
            1.6.dp.toPx(), StrokeCap.Round,
          )
        }
      }
    }
  }
}

/** "提醒管理" popup shown by 新增任务 — only the reminder group, as requested. */
@Composable
fun AtkNewTaskDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
  val names = listOf("定时提醒", "快速提醒", "整点报时")
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
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
              AtkNewTaskIcon(i)
            }
            Spacer(Modifier.height(6.dp))
            Text(name, color = Color.White, fontSize = 14.sp)
          }
        }
      }
    }
  }
}

/**
 * A settings row: big title, blue current value, chevron.
 *
 * 3.1.0: the row used a hard 66.dp height, which clipped the second line of a longer value
 * (「触发日志」的「最近 200 条：触发 / 前台服务 / 播铃 / 执行界面」被裁掉半行). Let the row
 * grow with its content instead.
 */
@Composable
fun AtkSettingRow(title: String, value: String, onClick: () -> Unit) {
  Column {
    Row(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 66.dp)
        .background(AtkSkin.bgDeep)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
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

/**
 * A settings row with a pill toggle on the right (the 调试 switch in 权限自检).
 * Tapping anywhere on the row flips the switch.
 */
@Composable
fun AtkSwitchRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 66.dp)
      .background(AtkSkin.bgDeep)
      .clickable { onToggle(!checked) }
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = Color.White, fontSize = 19.sp, modifier = Modifier.weight(1f))
    Box(
      Modifier
        .width(52.dp)
        .height(30.dp)
        .clip(RoundedCornerShape(15.dp))
        .background(if (checked) AtkSkin.green else Color(0xFF6A6A6A))
        .padding(3.dp),
      contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
      Box(Modifier.size(24.dp).background(Color.White, CircleShape))
    }
  }
  Box(Modifier.fillMaxWidth().height(1.dp).background(AtkSkin.divider))
}
