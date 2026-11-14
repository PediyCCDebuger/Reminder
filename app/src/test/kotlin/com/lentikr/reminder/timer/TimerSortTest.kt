package com.lentikr.reminder.timer

import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.7.3：首页排序「先启用/停用分组，组内按系统设置」的纯 JVM 校验。 */
class TimerSortTest {

  private fun item(id: Long, name: String, enabled: Boolean): AtkItem =
    AtkItem.newAlarm(0, 0).apply {
      this.id = id
      this.name = name
      this.enabled = enabled
    }

  private val fixedNext: (AtkItem) -> Long = { 0L }

  @Test
  fun enabledTasksAlwaysComeFirstInEveryMode() {
    val raw = listOf(
      item(1, "停用甲", false),
      item(2, "启用乙", true),
      item(3, "停用乙", false),
      item(4, "启用甲", true),
    )
    for (mode in TimerSortMode.entries) {
      val sorted = sortTimers(raw, mode, fixedNext)
      assertEquals(listOf(true, true, false, false), sorted.map { it.enabled })
    }
  }

  @Test
  fun nameModeSortsCaseInsensitivelyInsideEachGroup() {
    val raw = listOf(
      item(1, "b", true),
      item(2, "Z", false),
      item(3, "A", true),
      item(4, "a", false),
    )
    assertEquals(
      listOf("A", "b", "a", "Z"),
      sortTimers(raw, TimerSortMode.NAME, fixedNext).map { it.name },
    )
  }

  @Test
  fun createdModeUsesIdInsideEachGroup() {
    val raw = listOf(
      item(9, "x", false),
      item(7, "x", true),
      item(1, "x", false),
      item(3, "x", true),
    )
    assertEquals(
      listOf(3L, 7L, 1L, 9L),
      sortTimers(raw, TimerSortMode.CREATED, fixedNext).map { it.id },
    )
  }

  @Test
  fun execTimeModeUsesNextMillisInsideEachGroupAndIdBreaksTies() {
    val raw = listOf(
      item(1, "a", false),
      item(2, "b", true),
      item(3, "c", true),
      item(4, "d", false),
    )
    val times = mapOf(1L to 90L, 2L to 30L, 3L to 10L, 4L to 20L)
    assertEquals(
      listOf(3L, 2L, 4L, 1L),
      sortTimers(raw, TimerSortMode.EXEC_TIME) { times[it.id] ?: 0L }.map { it.id },
    )
    // 同值时按 id 兜底，任何时刻进入首页结果都一致。
    assertEquals(
      listOf(2L, 3L, 1L, 4L),
      sortTimers(raw, TimerSortMode.EXEC_TIME, fixedNext).map { it.id },
    )
  }

  @Test
  fun settingLabelMapsToMode() {
    assertEquals(TimerSortMode.EXEC_TIME, parseTimerSortMode("按执行时间"))
    assertEquals(TimerSortMode.CREATED, parseTimerSortMode("按创建时间"))
    assertEquals(TimerSortMode.NAME, parseTimerSortMode("按名称"))
    assertEquals(TimerSortMode.EXEC_TIME, parseTimerSortMode(null))
  }
}
