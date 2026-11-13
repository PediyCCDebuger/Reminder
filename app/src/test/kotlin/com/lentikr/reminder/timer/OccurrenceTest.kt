package com.lentikr.reminder.timer

import cn.nnci.addintimer.Occurrence
import cn.nnci.addintimer.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** 3.7.0：守护「指定日期 / 倒计时目标日期 / 按天循环」的下次触发计算与规则往返。 */
class OccurrenceTest {

  private val now = LocalDateTime.of(2026, 11, 13, 17, 2)

  @Test
  fun onceModeFiresOnTheChosenDateAtTheChosenTime() {
    val r = Rule().apply {
      mode = Rule.MODE_ONCE
      sy = 2026; sm = 11; sd = 20
    }
    assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30), Occurrence.first(r, 9, 30, now))
  }

  @Test
  fun onceModeInThePastHasNoNextOccurrence() {
    val r = Rule().apply {
      mode = Rule.MODE_ONCE
      sy = 2020; sm = 1; sd = 1
    }
    assertNull(Occurrence.first(r, 8, 0, now))
  }

  @Test
  fun targetModeFiresOnTheTargetDate() {
    val r = Rule().apply {
      mode = Rule.MODE_TARGET
      ey = 2026; em = 12; ed = 25
    }
    assertEquals(LocalDateTime.of(2026, 12, 25, 10, 0), Occurrence.first(r, 10, 0, now))
  }

  @Test
  fun daysModeRepeatsEveryIntervalFromTheBaseDate() {
    val r = Rule().apply {
      mode = Rule.MODE_DAYS
      by = 2026; bm = 11; bd = 13
      interval = 2
    }
    assertEquals(LocalDateTime.of(2026, 11, 15, 8, 0), Occurrence.first(r, 8, 0, now))
  }

  @Test
  fun countdownOptionsSurviveRuleSerialization() {
    val r = Rule().apply {
      mode = Rule.MODE_TARGET
      ey = 2026; em = 11; ed = 20
      cdMinutes = 7
      hourCycle = 1
      endEnable = 1
      cycleMinutes = 60
    }
    val back = Rule.parse(r.serialize())
    assertEquals(7, back.cdMinutes)
    assertEquals(1, back.hourCycle)
    assertEquals(1, back.endEnable)
    assertEquals(60, back.cycleMinutes)
    assertEquals(2026, back.ey)
    assertEquals(11, back.em)
    assertEquals(20, back.ed)
  }
}
