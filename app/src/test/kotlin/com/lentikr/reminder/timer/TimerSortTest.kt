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

  /**
   * 3.7.5：中文名称按拼音序。字符串默认的 Unicode 码点序会把「中」(U+4E2D) 排到
   * 「安」(U+5B89) 前面，对中文用户近乎乱序；拼音序应为 an < bei < zao < zhong。
   */
  @Test
  fun nameModeSortsChineseByPinyinInsteadOfUnicode() {
    val raw = listOf(
      item(1, "早起床", true),
      item(2, "中", true),
      item(3, "北", true),
      item(4, "安", true),
    )
    assertEquals(
      listOf("安", "北", "早起床", "中"),
      sortTimers(raw, TimerSortMode.NAME, fixedNext).map { it.name },
    )
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

  /**
   * 3.7.4 回归：`store.all()` 是 `ORDER BY id`，内置示例与旧版备份里停用任务的 id 普遍更小。
   * 首次冷启动若把这份原始顺序直接塞给首页，就会显示成「停用任务排前面」，只有进一次
   * 系统设置再返回（BackHandler → refresh()）才变成启用在前。这里锁定「原始 id 序」也
   * 必须得到启用在前的结果，防止再次出现绕过 sortTimers() 的加载路径。
   */
  @Test
  fun databaseIdOrderWithDisabledIdsFirstStillPutsEnabledFirst() {
    val raw = listOf(
      item(1, "停用1", false),
      item(2, "停用2", false),
      item(3, "停用3", false),
      item(4, "停用4", false),
      item(5, "启用5", true),
      item(6, "启用6", true),
    )
    for (mode in TimerSortMode.entries) {
      assertEquals(
        listOf(5L, 6L, 1L, 2L, 3L, 4L),
        sortTimers(raw, mode, fixedNext).map { it.id },
      )
    }
  }

  /** 3.7.4：按执行时间的排序键每项只计算一次，且不受「当前时间」基准漂移影响。 */
  @Test
  fun execTimeComputesEachItemOnceAndIsStableAcrossSorts() {
    val raw = listOf(
      item(1, "a", false),
      item(2, "b", true),
      item(3, "c", true),
      item(4, "d", false),
    )
    var calls = 0
    // 每调用一次就返回不同的值：若比较器里重复求值，排序结果会随调用次数漂移。
    val drifting: (AtkItem) -> Long = { calls++; calls.toLong() * 1_000L }
    val first = sortTimers(raw, TimerSortMode.EXEC_TIME, drifting)
    assertEquals(raw.size, calls)
    val second = sortTimers(raw, TimerSortMode.EXEC_TIME, drifting)
    assertEquals(raw.size * 2, calls)
    assertEquals(first.map { it.id }, second.map { it.id })
  }

  @Test
  fun settingLabelMapsToMode() {
    assertEquals(TimerSortMode.EXEC_TIME, parseTimerSortMode("按执行时间"))
    assertEquals(TimerSortMode.CREATED, parseTimerSortMode("按创建时间"))
    assertEquals(TimerSortMode.NAME, parseTimerSortMode("按名称"))
    assertEquals(TimerSortMode.EXEC_TIME, parseTimerSortMode(null))
  }
}
