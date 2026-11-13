package com.lentikr.reminder.timer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 3.7.2：滑动方式滑块的方向判定。左 = 贪睡、右 = 终止、幅度不足 = 不触发（回弹）。
 * 纯 JVM 校验，避免把左右动作写反或阈值失效。
 */
class AlarmFaceSliderTest {

  @Test
  fun dragLeftMeansSnoozeAndRightMeansStop() {
    assertEquals(SlideChoice.SNOOZE, slideChoice(-100f, 60f))
    assertEquals(SlideChoice.STOP, slideChoice(100f, 60f))
  }

  @Test
  fun shortDragDoesNotTriggerAnAction() {
    assertEquals(SlideChoice.NONE, slideChoice(0f, 60f))
    assertEquals(SlideChoice.NONE, slideChoice(59f, 60f))
    assertEquals(SlideChoice.NONE, slideChoice(-59f, 60f))
  }

  @Test
  fun exactThresholdCountsAsAChoice() {
    assertEquals(SlideChoice.SNOOZE, slideChoice(-60f, 60f))
    assertEquals(SlideChoice.STOP, slideChoice(60f, 60f))
  }

  @Test
  fun unmeasuredTrackNeverFires() {
    // 布局尚未测量时阈值 0，任何偏移都不能触发动作，防止误终止。
    assertEquals(SlideChoice.NONE, slideChoice(500f, 0f))
    assertEquals(SlideChoice.NONE, slideChoice(-500f, 0f))
  }
}
