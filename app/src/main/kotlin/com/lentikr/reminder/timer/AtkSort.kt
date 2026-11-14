package com.lentikr.reminder.timer

import java.util.Locale

/**
 * 3.7.3：首页任务排序。此前只按系统设置排一次，数据库原始顺序不稳定 + 停用任务的下次执行时间
 * 恒为 0，导致「每次进入顺序都不同」。现在固定为：启用任务在前、停用任务在后，两个分组内部
 * 都按「任务排序方式」排序；排序值相同再用 id 兜底，保证结果完全确定。
 *
 * 3.7.4：所有进入首页的路径都必须调用本函数（见 AtkUi.refresh）。另外「按执行时间」的
 * 排序键改为一次性预计算，避免比较器里反复计算循环 / 农历规则、也避免每次取到不同的
 * 「当前时间」基准导致比较结果不一致。
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
  // 比较器会被调用 O(n log n) 次；nextMillis 内部要跑执行策略 / 农历换算，开销不小，
  // 且调用方默认以 LocalDateTime.now() 为基准，多次调用可能跨过分钟边界而得出不同结果。
  // 这里按 id 一次性算好并缓存，保证一次排序中每个任务只有一个确定的排序键。
  val execKeys: Map<Long, Long>? = if (mode == TimerSortMode.EXEC_TIME) {
    HashMap<Long, Long>(items.size).apply {
      for (item in items) {
        this[item.id] = try {
          nextMillis(item)
        } catch (e: Exception) {
          0L
        }
      }
    }
  } else {
    null
  }

  val byMode: Comparator<AtkItem> = when (mode) {
    // 用 ROOT 而非系统默认 locale，避免土耳其语等 locale 下大小写转换影响名称顺序。
    TimerSortMode.NAME -> compareBy<AtkItem> { it.name.lowercase(Locale.ROOT) }
    // timer.id 是 INTEGER PRIMARY KEY AUTOINCREMENT：单调递增、删除后不复用；导入旧版
    // .atk 时保留原 id。因此 id 顺序等价于创建顺序，可用作「按创建时间」的排序键。
    TimerSortMode.CREATED -> compareBy<AtkItem> { it.id }
    TimerSortMode.EXEC_TIME -> compareBy<AtkItem> { item -> execKeys?.get(item.id) ?: 0L }
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
