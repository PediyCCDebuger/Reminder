package com.lentikr.reminder.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the AddinTimer param format and the tab bug that corrupted newly created timers. */
class AtkModelTest {

  @Test
  fun sampleParamsRoundTrip() {
    assertEquals(18, AtkSamples.PARAMS.size)
    for (raw in AtkSamples.PARAMS) {
      assertEquals(raw, AtkItem.parse(7L, raw).param())
    }
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
  fun newAlarmKeepsTheTabItWasCreatedFrom() {
    for (tab in 0..3) {
      val created = AtkItem.newAlarm(0, tab)
      assertEquals(tab, created.pageIndex())
      val reloaded = AtkItem.parse(9L, created.param())
      assertEquals(tab, reloaded.pageIndex())
      assertEquals("新定时器", reloaded.name)
      assertEquals("ALARM", reloaded.kind)
      assertEquals(27, reloaded.act.size)
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
  fun parameterScreenOnlyShowsTheOriginalRows() {
    val labels = AlarmSpec.FIELDS.map { it.label }
    assertEquals(18, labels.size)
    assertTrue("播报位置" !in labels)
    assertTrue("声音模式" !in labels)
    assertTrue("延迟时间" !in labels)
    assertTrue("间隔时间" !in labels)
    assertEquals("播报时间", labels.first())
    assertEquals("提醒备注", labels.last())
  }
}
