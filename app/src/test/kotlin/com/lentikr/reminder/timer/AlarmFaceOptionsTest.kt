package com.lentikr.reminder.timer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 3.7.1：执行界面三个新行为依赖的参数默认值与选项顺序。
 * 纯 JVM 校验，避免「手动贪睡选时间 / 终止确认」再次变成读取不到或顺序颠倒的死参数。
 */
class AlarmFaceOptionsTest {

  @Test
  fun snoozePickerListsLongestFirstLikeTheOriginal() {
    assertEquals(
      listOf("45分钟", "30分钟", "15分钟", "10分钟", "5分钟", "3分钟"),
      AtkOptions.SNAP_DUR.asReversed(),
    )
  }

  @Test
  fun handSnapAndStopConfirmDefaultsMatchTheOriginal() {
    // act[24] 默认 0 = 选择贪睡时间；act[16] 默认 1 = 要求确认。
    assertEquals("0", AlarmSpec.DEFAULTS[24])
    assertEquals("1", AlarmSpec.DEFAULTS[16])
    assertEquals(listOf("选择贪睡时间", "使用预设贪睡时间"), AtkOptions.HAND_SNAP)
    assertEquals(listOf("无确认", "要求确认"), AtkOptions.TIP_CLOSE)
  }

  @Test
  fun snoozeOptionTextParsesToMinutes() {
    assertEquals(
      listOf(45, 30, 15, 10, 5, 3),
      AtkOptions.SNAP_DUR.asReversed().map { it.filter { c -> c.isDigit() }.toInt() },
    )
  }
}
