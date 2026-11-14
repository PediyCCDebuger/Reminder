package com.lentikr.reminder.timer

/**
 * 3.7.3：首页任务排序。此前只按系统设置排一次，数据库原始顺序不稳定 + 停用任务的下次执行时间
 * 恒为 0，导致「每次进入顺序都不同」。现在固定为：启用任务在前、停用任务在后，两个分组内部
 * 都按「任务排序方式」排序；排序值相同再用 id 兜底，保证结果完全确定。
 */
internal enum class TimerSortMode { EXEC_TIME, CREATED, NAME }

internal fun parseTimerSortMode(label: String?): TimerSortMode = when (label) {
  "按名称" -> TimerSortMode.NAME
  "按创建时间" -> TimerSortMode.CREATED
  else -> TimerSortMode.EXEC_TIME
}

internal fun sortTimers(
  items: List<AtkItem>,
  mode: TimerSortMode,
  nextMillis: (AtkItem) -> Long,
): List<AtkItem> {
  val byMode: Comparator<AtkItem> = when (mode) {
    TimerSortMode.NAME -> compareBy<AtkItem> { it.name.lowercase() }
    TimerSortMode.CREATED -> compareBy<AtkItem> { it.id }
    TimerSortMode.EXEC_TIME -> compareBy<AtkItem> { item ->
      try {
        nextMillis(item)
      } catch (e: Exception) {
        0L
      }
    }
  }
  return items.sortedWith(
    Comparator { a, b ->
      val group = when {
        a.enabled == b.enabled -> 0
        a.enabled -> -1
        else -> 1
      }
      if (group != 0) group else {
        val bySetting = byMode.compare(a, b)
        if (bySetting != 0) bySetting else a.id.compareTo(b.id)
      }
    },
  )
}
