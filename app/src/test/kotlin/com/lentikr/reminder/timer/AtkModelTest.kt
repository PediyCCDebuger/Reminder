package com.lentikr.reminder.timer

import org.junit.Assert.assertEquals
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
      // 3.6.9：新建任务带扩展参数 act[27]「暂停时长」，共 28 项。原版按固定下标只读前 27 项，
      // 多余字段忽略；老数据（如 AtkSamples.PARAMS / 损坏行）解析后仍是 27 项，见下列测试。
      assertEquals(28, reloaded.act.size)
      assertEquals("2", reloaded.act[27]) // 默认「3分钟」
    }
  }

  @Test
  fun damagedRowNeverThrows() {
    val item = AtkItem.parse(1L, "")
    assertEquals("ALARM", item.kind)
    assertEquals(27, item.act.size)
    assertTrue(item.summary().isNotEmpty())
  }

  @Test
  fun parameterScreenShowsOriginalRowsPlusPauseDuration() {
    val labels = AlarmSpec.FIELDS.map { it.label }
    // 3.6.9：原版 18 行 +「暂停时长」（扩展字段，仅「电源键按下=暂停提醒」时显示）。
    assertEquals(19, labels.size)
    assertTrue("暂停时长" in labels)
    assertEquals(27, AlarmSpec.FIELDS.first { it.label == "暂停时长" }.index)
    assertTrue("播报位置" !in labels)
    assertTrue("声音模式" !in labels)
    assertTrue("延迟时间" !in labels)
    assertTrue("间隔时间" !in labels)
    assertEquals("播报时间", labels.first())
    assertEquals("提醒备注", labels.last())
  }
}
