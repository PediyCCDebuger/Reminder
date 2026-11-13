package com.lentikr.reminder.timer

/**
 * 3.7.2：执行界面「滑动方式」底部滑块的方向判定，抽成纯函数便于 JVM 单测。
 * 滑块从中间出发：向左（负偏移）拖过阈值 = 贪睡，向右 = 终止，幅度不足 = 不触发并回弹。
 */
internal enum class SlideChoice { NONE, SNOOZE, STOP }

internal fun slideChoice(offsetX: Float, threshold: Float): SlideChoice = when {
  threshold <= 0f -> SlideChoice.NONE
  offsetX <= -threshold -> SlideChoice.SNOOZE
  offsetX >= threshold -> SlideChoice.STOP
  else -> SlideChoice.NONE
}
