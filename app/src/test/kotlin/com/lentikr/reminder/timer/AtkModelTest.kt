package com.lentikr.reminder.timer

import cn.nnci.addintimer.Rule
import org.junit.Assert.assertEquals
import java.time.LocalDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the AddinTimer param format, sample states and lossless category moves. */
class AtkModelTest {

  @Test
  fun sampleParamsRoundTrip() {
    assertEquals(18, AtkSamples.PARAMS.size)
    for (raw in AtkSamples.PARAMS) {
      assertEquals(raw, AtkItem.parse(7L, raw).param())
    }
  }

  @Test
  fun builtInSamplesAreAllDisabled() {
    // The original AddinTimer ships its 18 examples switched OFF. Importing a backup must
    // never silently enable them.
    for (raw in AtkSamples.PARAMS) {
      assertFalse(AtkItem.parse(1L, raw).enabled)
    }
  }

  @Test
  fun samplesKeepTheirOriginalCategories() {
    val pages = AtkSamples.PARAMS.map { AtkItem.parse(1L, it).page }
    assertEquals(4, pages.count { it == 100 })   // 常用
    assertEquals(5, pages.count { it == 101 })   // 生活
    assertEquals(5, pages.count { it == 102 })   // 纪念日
    assertEquals(4, pages.count { it == 103 })   // 生日
  }

  @Test
  fun parseReallySplitsOnPipeAndDollar() {
    val item = AtkItem.parse(1L, AtkSamples.PARAMS[17]) // 早起床
    assertEquals("ALARM", item.kind)
    assertEquals("早起床", item.name)
    assertEquals(101, item.page)
    assertEquals(6, item.hour)
    assertEquals(30, item.minute)
    assertEquals(27, item.act.size)
    assertEquals("缺省铃音", item.act[7])
  }

  @Test
  fun movingCategoryRewritesOnlyBase1() {
    for (raw in AtkSamples.PARAMS) {
      val moved = replaceBaseField(raw, 1, "103")
      val original = AtkItem.parse(1L, raw)
      val result = AtkItem.parse(1L, moved)
      assertEquals(3, result.pageIndex())
      assertEquals(original.enabled, result.enabled)
      assertEquals(original.rule.serialize(), result.rule.serialize())
      assertEquals(original.kind, result.kind)
      assertEquals(original.name, result.name)
      // Every other base field and every action parameter stays byte-identical.
      assertEquals(
        replaceBaseField(raw, 1, original.page.toString()),
        replaceBaseField(moved, 1, original.page.toString()),
      )
    }
  }

  @Test
  fun movePreservesMixedEnabledFlagsFromABackup() {
    val raw = AtkSamples.PARAMS[17]
    val enabled = replaceBaseField(raw, 10, "1")
    assertTrue(AtkItem.parse(1L, enabled).enabled)
    val moved = replaceBaseField(enabled, 1, "102")
    val item = AtkItem.parse(1L, moved)
    assertTrue(item.enabled)
    assertEquals(2, item.pageIndex())
  }

  @Test
  fun newAlarmKeepsTheTabItWasCreatedFrom() {
    for (tab in 0..3) {
      val created = AtkItem.newAlarm(0, tab)
      assertEquals(tab, created.pageIndex())
      val reloaded = AtkItem.parse(9L, created.param())
      assertEquals(tab, reloaded.pageIndex())
      assertEquals("新定时器", reloaded.name)
      assertEquals("ALARM", reloaded.kind)
      // 3.7.3：新建任务带扩展参数 act[27]「暂停时长」、act[28]「静音时长」，共 29 项。
      // 用户明确不要求新版备份能被旧版 AddinTimer 读取；反过来，老数据（AtkSamples.PARAMS /
      // 损坏行）解析后仍是原来的 27 项，一个字段都不会丢，见下列测试。
      assertEquals(29, reloaded.act.size)
      assertEquals("2", reloaded.act[27]) // 默认「3分钟」
      assertEquals("2", reloaded.act[28]) // 默认「3分钟」
    }
  }

  @Test
  fun oldAddinTimerBackupIsStillReadLosslessly() {
    // 用户硬性要求：新版必须 100% 兼容旧版 AddinTimer 数据（不要求反向兼容）。
    // 示例备份里含 FM / SCENE 等非 ALARM 任务，这里用已知的 ALARM 行验证：27 个原有动作参数
    // 一项不多一项不少、param() 逐字节原样写回，扩展字段不污染旧数据。
    val raw = AtkSamples.PARAMS[17] // 早起床（ALARM）
    val item = AtkItem.parse(1L, raw)
    assertEquals("ALARM", item.kind)
    assertEquals(27, item.act.size)
    assertEquals("", item.actValue(28)) // 缺扩展字段，由引擎按默认 3 分钟兜底
    assertEquals(raw, item.param())
  }

  @Test
  fun muteDurationOptionsAndDefault() {
    assertEquals(
      listOf(1, 2, 3, 5, 10, 15, 30),
      AtkOptions.MUTE_DUR.map { it.filter { c -> c.isDigit() }.toInt() },
    )
    assertEquals("3分钟", AtkOptions.MUTE_DUR[AtkItem.intOf(AlarmSpec.DEFAULTS[28])])
  }

  @Test
  fun damagedRowNeverThrows() {
    val item = AtkItem.parse(1L, "")
    assertEquals("ALARM", item.kind)
    assertEquals(27, item.act.size)
    assertTrue(item.summary().isNotEmpty())
  }

  @Test
  fun parameterScreenShowsOriginalRowsPlusPauseAndMuteDuration() {
    val labels = AlarmSpec.FIELDS.map { it.label }
    // 3.7.3：原版 18 行 +「暂停时长」（仅「电源键按下=暂停提醒」时显示）+「静音时长」。
    assertEquals(20, labels.size)
    assertTrue("暂停时长" in labels)
    assertEquals(27, AlarmSpec.FIELDS.first { it.label == "暂停时长" }.index)
    assertTrue("静音时长" in labels)
    assertEquals(28, AlarmSpec.FIELDS.first { it.label == "静音时长" }.index)
    assertTrue("播报位置" !in labels)
    assertTrue("声音模式" !in labels)
    assertTrue("延迟时间" !in labels)
    assertTrue("间隔时间" !in labels)
    assertEquals("播报时间", labels.first())
    assertEquals("提醒备注", labels.last())
  }

  @Test
  fun enabledTaskWithoutFutureIsAutoDisabled() {
    val item = AtkItem.newAlarm(0, 0)
    assertTrue(shouldAutoDisableExpired(item, 0L))
    assertFalse(shouldAutoDisableExpired(item, 1L))
    item.enabled = false
    assertFalse(shouldAutoDisableExpired(item, 0L))
  }

  @Test
  fun expiredOneShotNeedsInactiveSaveWarningEvenIfDisabledOrNextMillisMisleading() {
    val now = LocalDateTime.of(2026, 11, 20, 9, 30)
    val once = AtkItem.newAlarm(0, 0).apply {
      rule.mode = Rule.MODE_ONCE
      rule.sy = 2026; rule.sm = 11; rule.sd = 11
    }
    // 3.7.7：直接按指定日期判定，不再要求 enabled，也不被 nextMillis 的偶发偏差带偏。
    assertTrue(needsInactiveSaveWarning(once, 1L, now))
    once.enabled = false
    assertTrue(needsInactiveSaveWarning(once, 1L, now))

    val future = AtkItem.newAlarm(0, 0).apply {
      rule.mode = Rule.MODE_ONCE
      rule.sy = 2026; rule.sm = 11; rule.sd = 25
    }
    assertFalse(needsInactiveSaveWarning(future, 1L, now))

    val target = AtkItem.newAlarm(0, 0).apply {
      rule.mode = Rule.MODE_TARGET
      rule.ey = 2026; rule.em = 11; rule.ed = 11
    }
    assertTrue(needsInactiveSaveWarning(target, 1L, now))

    val countdown = AtkItem.newAlarm(0, 0).apply {
      rule.mode = Rule.MODE_TARGET
      rule.hourCycle = 1
      rule.ey = 2020; rule.em = 1; rule.ed = 1
    }
    assertFalse(needsInactiveSaveWarning(countdown, 1L, now))

    val days = AtkItem.newAlarm(0, 0).apply { rule.mode = Rule.MODE_DAYS }
    assertFalse(needsInactiveSaveWarning(days, 0L, now))
  }

  @Test
  fun yearMonthSummaryShowsConcreteDayInsteadOfOnlyMonth() {
    val item = AtkItem.newAlarm(0, 0).apply {
      rule.mode = Rule.MODE_YEAR_MONTH
      rule.months = "7"      // 0-based：8 月
      rule.monthDays = "7"   // 0-based：8 日
    }
    assertEquals("[按月] 8月8日", item.summary())

    // 多个日期也要全部显示，不能退化成只有月份。
    item.rule.monthDays = "7,14"
    assertEquals("[按月] 8月8日,15日", item.summary())

    // 月末选项（索引 30/31）在原版里会落到当月最后一天。
    item.rule.monthDays = "30"
    assertEquals("[按月] 8月月末", item.summary())

    // 指周计算：月份 + 第几个 + 周几。
    item.rule.byWeek = 1
    item.rule.nthWeek = "1"
    item.rule.nthWeekday = "1"
    assertEquals("[按月] 8月第2个周一", item.summary())
  }

  @Test
  fun lunarSummaryUsesFullChineseDayName() {
    val item = AtkItem.newAlarm(0, 0).apply {
      rule.mode = Rule.MODE_LUNAR
      rule.lunarMonth = 1
      rule.lunarDay = 1
    }
    assertEquals("[阴历] 正月初一", item.summary())

    item.rule.lunarDay = 10
    assertEquals("[阴历] 正月初十", item.summary())
    item.rule.lunarDay = 15
    assertEquals("[阴历] 正月十五", item.summary())
    item.rule.lunarDay = 21
    assertEquals("[阴历] 正月廿一", item.summary())

    item.rule.lunarMonth = 8
    item.rule.lunarDay = 8
    item.rule.lunarLeap = 1
    assertEquals("[阴历] 闰八月初八", item.summary())
  }

  @Test
  fun lunarDayLabelMatchesEditorOptions() {
    for (d in 1..30) {
      assertEquals(AtkOptions.LUNAR_DAYS[d - 1], cn.nnci.addintimer.Lunar.dayLabel(d))
    }
    assertEquals("最后一天", cn.nnci.addintimer.Lunar.dayLabel(31))
  }
}
