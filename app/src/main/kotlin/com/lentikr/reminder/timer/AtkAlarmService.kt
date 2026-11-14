package com.lentikr.reminder.timer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps a firing alarm alive while the screen is locked.
 *
 * 3.1.0: the exact alarm's PendingIntent is a **broadcast** (see [AtkEngine.fireIntent]).
 * [AtkReceiver] only takes a wake lock, starts the tone and starts this service. THIS SERVICE
 * is the component that mounts the HIGH-importance full-screen notification, via
 * startForeground(), from a genuinely foreground process — the architecture used by
 * SunriseAlarm (verified working on this device) and by Smart Alarm's LaunchAlarm service.
 * Posting the notification from the background broadcast (3.0.0) was silently downgraded by
 * Android 12+/ColorOS, so the 执行界面 never came up over the keyguard.
 *
 * 3.6.0 根因修复（对齐 Smart Alarm-v2.7.7，本机验证可用）：ColorOS / 一加 13 连 setFullScreenIntent
 * 一并吞掉，导致 3.4.0/3.5.0 一直「到点不亮屏、要解锁点通知才响」。Smart Alarm 在本机可用，关键在
 * 于它**不用 FSI**，而是由前台服务（LaunchAlarm）在 startForeground 之后**直接 startActivity**
 * 拉起 AlarmActivity（flags=NEW_TASK|CLEAR_TASK），Activity 自身 setShowWhenLocked +
 * setTurnScreenOn + 屏幕唤醒锁负责点亮屏幕并盖在锁屏之上。因此本服务改为：进入前台后**立即直接
 * 拉起执行界面**作为主路径，FSI 通知仅作兜底（被吞也不影响）。
 *
 * Failure modes are made visible instead of silent: every startForeground attempt, the sound
 * start, the activity launch and the re-arm are written to [AtkDiag] so 权限自检 → 诊断日志 can
 * show exactly which link of the chain broke on the device.
 */
/**
 * 3.7.1：一次响铃在「前台服务 / 执行界面」之间共享的状态。两者同进程，用进程内单例即可，
 * 不需要落盘：任务终止 / 贪睡 / 下次触发时都会 reset()。
 * - [mutedId]：本次响铃已被静音（按钮静音或翻转静音），界面只显示贪睡 / 终止、不再播铃。
 * - [faceOwned]：用户已主动解锁并把执行界面拉到前台，此后不再因屏幕事件自动暂停。
 * - [faceVisible]：执行界面当前是否可见，用于诊断。
 */
object AtkAlarmState {
  @Volatile var mutedId: Long = -1L
  @Volatile var faceOwned = false
  @Volatile var faceVisible = false
  fun reset() {
    mutedId = -1L
    faceOwned = false
    faceVisible = false
  }
}

class AtkAlarmService : Service() {

  companion object {
    const val EXTRA_ACTION = "svc_action"
    const val ACTION_START = "svc_start"
    const val ACTION_STOP = "svc_stop"
    const val ACTION_SNOOZE = "svc_snooze"
    /** 3.7.1：静音（只停铃声 / 振动，任务与执行界面保留，等用户点贪睡 / 终止）。 */
    const val ACTION_MUTE = "svc_mute"
    /** Set by 60 秒后真实触发 so the test fires the chain even on a disabled task. */
    const val EXTRA_FORCE = "svc_force"
    /** 标记本次 service 启动来自「贪睡重响」，用于自动贪睡计数跨重响延续、不重置。 */
    const val EXTRA_FROM_SNOOZE = "svc_from_snooze"
    /** 3.7.3：静音时长到点后，服务还活着也要当作「全新一次响铃」重新挂载通知 / 振动 / 监听。 */
    const val EXTRA_FRESH_MOUNT = "svc_fresh_mount"
    private const val WAKE_TIMEOUT_MS = 20 * 60 * 1000L
    // 3.6.5：自动贪睡剩余次数（按闹钟 id 保存，跨贪睡重响仍有效）。
    private val autoSnoozeLeft = mutableMapOf<Long, Int>()
  }

  private var wakeLock: PowerManager.WakeLock? = null
  private var currentId = -1L
  private var foreground = false
  /** Avoid re-cancelling (and re-alerting) the same alarm when onCreate starts us again. */
  private var mountedId = -1L
  // 3.6.5：翻转感应（act[18]）由前台服务统一监听，无论是否点亮屏幕都生效。
  private var sensorManager: SensorManager? = null
  private var gravityListener: SensorEventListener? = null
  private var flipMode = 0
  // 3.6.5：电源键按下（act[25]）与自动贪睡（act[14]）。
  private var powerReceiver: BroadcastReceiver? = null
  private var autoSnoozeHandler: Handler? = null
  private var autoSnoozeTask: Runnable? = null
  private var snapDurMin = 10
  /** 3.6.9：电源键「暂停提醒」的暂停时长（分钟），来自任务扩展参数 act[27]。 */
  private var pauseDurMin = 3
  /** 3.7.3：静音后重新执行的时长（分钟），来自任务扩展参数 act[28]。 */
  private var muteDurMin = 3
  /** 3.7.0：本次响铃开始时的屏幕状态，用于把「第一次按电源键」识别成一次屏幕状态翻转。 */
  private var powerBaselineOn = false
  /** 3.7.1：用户解锁（USER_PRESENT）监听，独立于电源键监听，静音后也要保留。 */
  private var unlockReceiver: BroadcastReceiver? = null
  /** 3.7.1：SCREEN_ON 后等待 USER_PRESENT 的延迟暂停任务。 */
  private var pauseHandler: Handler? = null
  private var pendingPauseTask: Runnable? = null
  /** 3.7.1：本次响铃的屏幕模式，供翻转静音决定是否立即重建执行界面。 */
  private var lightScreen = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    AtkDiag.log(this, "svc: onCreate")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START
    val id = intent?.getLongExtra(AtkEngine.EXTRA_ID, -1L) ?: -1L
    val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
    val fromSnooze = intent?.getBooleanExtra(EXTRA_FROM_SNOOZE, false) ?: false
    val freshMountReq = intent?.getBooleanExtra(EXTRA_FRESH_MOUNT, false) ?: false
    snoozeStartId = startId
    AtkDiag.log(
      this,
      "svc: onStartCommand action=$action id=$id force=$force fromSnooze=$fromSnooze fresh=$freshMountReq",
    )

    if (action == ACTION_STOP || action == ACTION_SNOOZE) {
      stopEverything(startId)
      return START_NOT_STICKY
    }
    // 3.7.1：执行界面的「静音」按钮只静音、不终止任务。服务保持前台与通知，等用户在界面上
    // 选择贪睡 / 终止；用户解锁（USER_PRESENT）时再把界面拉到前台。
    if (action == ACTION_MUTE) {
      // 3.7.3：静音期间服务可能被系统回收重建，这里按任务参数重新解析「静音时长」，
      // 拿到后挂一个到点重新执行的闹钟（与贪睡重响同一条链路），避免静音后再也不响。
      val (muteItem, _) = loadItem(id)
      val minutes = if (muteItem != null) AtkEngine(this).muteMinutes(muteItem) else muteDurMin
      muteOnly(id, minutes)
      return START_NOT_STICKY
    }

    val engine = AtkEngine(this)
    val (item, dbError) = loadItem(id)
    if (item == null) {
      // Distinguish "row missing" from "database threw on a cold start" so the 权限自检 →
      // 触发日志 page can tell the user exactly which link broke (the common case on ColorOS is
      // a swallowed SQLite exception when the alarm fires and starts this service from cold).
      AtkDiag.log(
        this,
        "svc: 任务获取失败 id=$id " + (if (dbError != null) "错误=$dbError" else "（数据库中无此 id）"),
      )
      stopEverything(startId)
      return START_NOT_STICKY
    }
    if (!item.enabled) {
      if (force) {
        // 60 秒后真实触发 explicitly forces the ring chain even on a disabled task, so the user
        // can verify 响铃 / 渐响 / 执行界面 without first enabling it.
        AtkDiag.log(this, "svc: 任务已停用，但 60 秒测试强制触发 id=$id（base[10]=${item.base.getOrNull(10) ?: "?"}）")
      } else {
        AtkDiag.log(this, "svc: 任务已停用 id=$id（base[10]=${item.base.getOrNull(10) ?: "?"}）")
        stopEverything(startId)
        return START_NOT_STICKY
      }
    }

    // 静音期间用户可能已解锁并停留在「只剩贪睡 / 终止」的界面上；重响时要把它刷回完整界面。
    val faceWasVisible = freshMountReq && AtkAlarmState.faceVisible
    currentId = id
    if (freshMountReq) {
      // 3.7.3：静音时长到点时本服务实例仍在，mountedId 还是同一个 id，会被当成「重复启动」而
      // 跳过补发振动 / 重新注册监听 / 拉起界面。强制按「全新一次响铃」重置这些状态。
      AtkDiag.log(this, "svc: 静音结束，强制按新一次响铃重新挂载 id=$id")
      AtkAlarmState.reset()
      mountedId = -1L
      unregisterFlipSensor()
      unregisterPowerReceiver()
      cancelPendingPause()
      cancelAutoSnooze()
    }
    acquireWakeLock()
    engine.ensureChannel()
    // 3.6.4: 屏幕模式 = 不点亮屏幕 (act[15]=0) 时，不走全屏 intent、用静默渠道，让屏幕真正不亮。
    lightScreen = AtkItem.intOf(item.actValue(15)) != 0
    // 3.6.5：全局「任务执行时电源模式」= 全唤醒 时，强制点亮屏幕（覆盖 per-timer act[15]）。
    if (engine.globalPowerForceLight()) lightScreen = true

    // 3.7.1：本次响铃是否已挂载过通知。新的一次响铃（含贪睡 / 暂停重响）freshMount=true，
    // 会清掉上一次的「静音 / 用户已解锁接管」状态；执行界面 onCreate 回弹本服务时则为 false。
    val notifId = AtkEngine.alarmNotificationId(id)
    val freshMount = mountedId != id
    if (freshMount) {
      AtkAlarmState.reset()
      engine.cancelAlarmNotification(id)
      mountedId = id
    } else if (AtkAlarmState.faceOwned || AtkAlarmState.mutedId == id) {
      // 界面已交给用户 / 本次响铃已静音：不重复播铃、不重新注册监听、不重新挂自动贪睡。
      AtkDiag.log(this, "svc: 界面已接管或已静音，跳过重复响铃处理 id=$id")
      return START_NOT_STICKY
    }

    // 3.6.5：翻转感应（act[18]）由前台服务统一监听，无论是否点亮屏幕都生效。
    flipMode = AtkItem.intOf(item.actValue(18))
    if (flipMode != 0) registerFlipSensor(id)

    // Play the tone (idempotent: the activity that launched this service already started it, so
    // this is normally a no-op). Keeping the tone in one process-wide singleton means it survives
    // either component being torn down.
    AtkAlarmSound.play(this, item, vibrateOverride = engine.globalVibrate())

    // 3.1.0 DECISIVE STEP: mount the full-screen notification as THIS foreground service's
    // notification, exactly like SunriseAlarm's
    //   startForeground(NOTIF_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    // which is verified to wake this device from the lock screen. 3.0.0 posted it from the
    // background broadcast instead, where Android 12+ treats it as a silent refresh.
    // Cancel first so SystemUI sees a genuinely NEW alert (an update never fires the
    // full-screen intent), and never reuse the 测试执行 / persistent-icon notification id.
    val foregrounded = enterForeground(engine, item, notifId, force, lightScreen)
    AtkDiag.log(
      this,
      "全屏通知: startForeground 已挂载 id=$id notif=$notifId 前台=$foregrounded " +
        "全屏权限=${engine.canUseFullScreenIntent()} 点亮屏幕=$lightScreen",
    )
    // 3.6.9：第一次 play() 发生在后台广播里，部分 ROM 会静默丢弃后台发起的振动，导致「只有铃声
    // 没有振动」（点亮屏幕且执行界面仍在前台时才侥幸成功）。服务真正进入前台后强制补发一次，
    // 「不点亮屏幕」模式也能可靠振动。freshMount 保证每次响铃只补一次，避免振动被反复打断。
    if (freshMount) {
      AtkAlarmSound.ensureVibration(this, item, engine.globalVibrate(), force = true)
    }
    if (!foregrounded) {
      // No foreground service at all: a plain notify() is the last remaining net.
      AtkDiag.log(this, "svc: 无法进入前台，改为兜底 notify + 本进程播铃")
      engine.showNotification(item, fullScreen = lightScreen, force = force)
    }
    // 3.6.0 主路径（对齐 Smart Alarm）：前台进程直接 startActivity 拉起执行界面。本机 ColorOS 连
    // FSI 都吞，但 Smart Alarm 用「前台服务直接 startActivity」在本机可用——前台进程发起的启动
    // 不会被当成后台启动，setTurnScreenOn + 屏幕唤醒锁（见 AtkAlarmActivity）能可靠点亮屏幕并盖在
    // 锁屏之上。dedupe=true 避免 Activity 自身 onCreate 又回头启动本服务时二次拉起造成闪屏。
    // FSI 通知（上方 startForeground 挂载）仅作兜底：被吞则静默失效，不影响本路径。
    // 3.6.4：屏幕模式 = 不点亮屏幕 (lightScreen=false) 时，不主动拉起执行界面（避免点亮屏幕），
    // 仅靠本服务的静默通知（V5 渠道）+ 播铃提醒。3.7.1 起用户主动解锁会由解锁监听拉起界面，
    // 不必再点通知。
    if (lightScreen && !AtkAlarmState.faceOwned) engine.launchAlarmFace(id, force, dedupe = true)
    // 3.7.3：点亮屏模式上面已拉起非静音界面（dedupe 会跳过这里的重复请求）；不点亮屏模式
    // 若用户此前解锁过，则在这里把界面从静音形态刷回完整形态。
    if (faceWasVisible) engine.launchAlarmFace(id, force, muted = false, dedupe = true)
    AtkDiag.log(this, "svc: 已直接拉起执行界面 id=$id（FSI 仅兜底，全屏权限=${engine.canUseFullScreenIntent()}）")

    runCatching { AtkStore(this).log(id, 1, item.name) }
    runCatching { engine.schedule(item) }
    runCatching { engine.refreshNext() }

    // 3.6.5：电源键按下（act[25]）与自动贪睡（act[14]）。
    // act[25]：暂停提醒 / 自动贪睡——响铃中按电源键的处理。
    // act[14]：未手动关闭时，每隔「预设时间(act[13])」自动贪睡，最多 N 次。
    val powerStyle = AtkItem.intOf(item.actValue(25)) // 0 暂停提醒 / 1 自动贪睡
    val snapRepeat = AtkItem.intOf(item.actValue(14)).coerceIn(0, 10) // 0 = 无，N = N 次
    val snapIdx = AtkItem.intOf(item.actValue(13)).coerceIn(0, AtkOptions.SNAP_DUR.lastIndex)
    snapDurMin = snapMinutes(AtkOptions.SNAP_DUR[snapIdx]) // 预设时间（存的是选项索引）
    pauseDurMin = engine.pauseMinutes(item) // 3.6.9：暂停提醒的恢复时长（act[27]）
    muteDurMin = engine.muteMinutes(item) // 3.7.3：静音后的恢复时长（act[28]）
    // 3.7.1：记录响铃开始时的屏幕状态作为基线。屏幕状态翻转仍是电源键的代理，但 SCREEN_ON
    // （亮屏）要等 1.1 秒判断是否随后解锁：解锁则交给执行界面，不解锁才按设置暂停。
    powerBaselineOn = try {
      (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (e: Exception) {
      lightScreen
    }
    if (powerStyle != -1 && !AtkAlarmState.faceOwned) {
      registerPowerReceiver(powerStyle, id, lightScreen, powerBaselineOn)
    }
    // 3.7.1：用户主动解锁屏幕时把执行界面交给用户（见 [onUserUnlocked]）。与电源键监听分开，
    // 静音后电源键监听已注销，但解锁监听仍在，仍能把「只剩贪睡 / 终止」的界面拉起来。
    registerUnlockReceiver(id)
    if (snapRepeat > 0) {
      // 真正触发（fromSnooze=false）时重置自动贪睡次数，开启新一轮；贪睡重响（fromSnooze=true）
      // 时保留已递减的次数，避免无限贪睡或次日失效。
      if (!fromSnooze || autoSnoozeLeft[id] == null) autoSnoozeLeft[id] = snapRepeat
      armAutoSnooze(id)
    } else {
      autoSnoozeLeft.remove(id)
    }
    return START_NOT_STICKY
  }

  /** 解析「预设时间」分钟数：act[13] 存的是选项索引，先经 SNAP_DUR 取到「N分钟」文案再转数字。 */
  private fun snapMinutes(v: String): Int {
    val n = v.filter { it.isDigit() }.toIntOrNull() ?: 10
    return n.coerceIn(1, 60)
  }

  /**
   * 3.6.5：自动贪睡——闹钟响铃 snapDurMin 分钟后若仍未被关闭，自动贪睡一次。
   * 剩余次数存于 companion，贪睡重响重启服务后仍可继续，直到用尽。
   */
  private fun armAutoSnooze(id: Long) {
    val left = autoSnoozeLeft[id] ?: return
    if (left <= 0) return
    cancelAutoSnooze()
    val handler = Handler(Looper.getMainLooper())
    val task = object : Runnable {
      override fun run() {
        // 仅当本闹钟仍在响铃（未被关闭）时才自动贪睡。
        if (currentId == id && AtkAlarmSound.isPlaying()) {
          AtkDiag.log(this@AtkAlarmService, "svc: 自动贪睡触发 id=$id 剩余=${left - 1}")
          val remain = left - 1
          autoSnoozeLeft[id] = remain
          AtkAlarmSound.stop()
          // 3.6.8：先停服务（dismiss 会取消挂起的贪睡闹钟），再挂本次自动贪睡，避免被 dismiss 取消。
          stopEverything(startIdForSnooze())
          AtkEngine(this@AtkAlarmService).armSnooze(id, snapDurMin)
        }
      }
    }
    autoSnoozeHandler = handler
    autoSnoozeTask = task
    handler.postDelayed(task, snapDurMin * 60L * 1000L)
    AtkDiag.log(this, "svc: 自动贪睡已挂 ${snapDurMin} 分钟 id=$id 剩余=$left")
  }

  private fun cancelAutoSnooze() {
    try { autoSnoozeTask?.let { autoSnoozeHandler?.removeCallbacks(it) } } catch (e: Exception) { }
    autoSnoozeTask = null
    autoSnoozeHandler = null
  }

  /**
   * 3.6.5：电源键按下监听，以「屏幕状态变化」作为电源键代理。
   * 3.7.1 关键修正：区分「按电源键」与「按亮后解锁」。
   * - SCREEN_OFF（灭屏）一定是按键 → 立即按 act[25] 暂停 / 贪睡；
   * - SCREEN_ON（亮屏）可能是「按亮锁屏看一眼」，也可能是「按亮后马上滑动解锁」。
   *   SCREEN_ON 先挂 1.1 秒延迟；若期间收到 USER_PRESENT（真正解锁），[onUserUnlocked] 会取消
   *   这次暂停并把执行界面交给用户。这就修掉了「暂停重响后解锁屏幕又触发一次暂停、过一会后台又响」。
   * 注册瞬间的同向事件与响铃最初 600ms 的自动亮/灭屏仍按噪声忽略。
   */
  private fun registerPowerReceiver(
    powerStyle: Int,
    id: Long,
    lightScreen: Boolean,
    baselineOn: Boolean,
  ) {
    // 幂等：执行界面启动本服务时会再走一次 onStartCommand，避免叠加多个监听导致一次按键触发多次。
    unregisterPowerReceiver()
    var screenOn = baselineOn
    val armedAt = System.currentTimeMillis()
    fun applyPowerAction() {
      cancelPendingPause()
      unregisterPowerReceiver()
      if (powerStyle == 1) {
        // 自动贪睡：停止响铃并按参数里的贪睡时长挂重响。
        // 3.6.8：先停服务（dismiss 会取消挂起的贪睡闹钟），再挂本次贪睡，避免被 dismiss 取消。
        AtkAlarmSound.stop()
        stopEverything(startIdForSnooze())
        AtkEngine(this).armSnooze(id, snapDurMin)
      } else {
        // 3.6.9：暂停提醒——本次响铃延后「暂停时长」后继续，绝不 dismiss。
        pauseOnly(id)
      }
    }
    powerReceiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context?, intent: Intent?) {
        val a = intent?.action ?: return
        if (a != "android.intent.action.SCREEN_OFF" && a != "android.intent.action.SCREEN_ON") return
        if (currentId != id) return
        val nowOn = a == "android.intent.action.SCREEN_ON"
        if (nowOn == screenOn) return // 同向噪声（重复广播、自动亮屏）
        screenOn = nowOn
        if (System.currentTimeMillis() - armedAt < 600L) {
          AtkDiag.log(this@AtkAlarmService, "svc: 忽略响铃瞬间的自动屏幕变化 id=$id")
          return
        }
        AtkDiag.log(
          this@AtkAlarmService,
          "svc: 屏幕变化（${if (nowOn) "亮屏" else "灭屏"}）powerStyle=$powerStyle " +
            "点亮屏幕=$lightScreen 基线=${if (baselineOn) "亮" else "灭"} id=$id",
        )
        if (!nowOn) {
          // 灭屏不可能是解锁的前奏，立即按电源键处理。
          applyPowerAction()
        } else {
          // 亮屏：等 1.1 秒看用户是否继续解锁；解锁则由 onUserUnlocked 取消本次暂停。
          AtkDiag.log(this@AtkAlarmService, "svc: 亮屏，等待是否解锁 id=$id")
          schedulePendingPause { applyPowerAction() }
        }
      }
    }
    try {
      val filter = IntentFilter()
      filter.addAction("android.intent.action.SCREEN_ON")
      filter.addAction("android.intent.action.SCREEN_OFF")
      registerReceiver(powerReceiver, filter)
      AtkDiag.log(
        this,
        "svc: 电源键监听已注册 id=$id lightScreen=$lightScreen 基线=${if (baselineOn) "亮" else "灭"}",
      )
    } catch (e: Exception) {
      AtkDiag.log(this, "svc: 电源键监听注册失败 ${e.message}")
    }
  }

  /**
   * 3.7.1：用户真正解锁屏幕（USER_PRESENT）。原版此时会直接把任务执行界面呈现出来，让用户选
   * 贪睡 / 终止；3.7.0 把解锁当成了「按电源键」，于是又暂停一次、过 N 分钟在后台重响。
   * 现在取消待生效的亮屏暂停、注销电源键监听（界面已交给用户），并拉起执行界面。若本次响铃
   * 已被静音（按钮静音 / 翻转静音），界面以「只剩贪睡 / 终止」的形态打开且不重新播铃。
   */
  private fun onUserUnlocked(id: Long) {
    cancelPendingPause()
    AtkAlarmState.faceOwned = true
    unregisterPowerReceiver()
    val muted = AtkAlarmState.mutedId == id
    AtkDiag.log(
      this,
      "svc: 用户解锁，交给执行界面 id=$id 静音=$muted 界面已可见=${AtkAlarmState.faceVisible}",
    )
    if (!muted && AtkAlarmState.faceVisible) {
      // 界面本来就亮在前台，重建只会闪一下；此处只需保证不再自动暂停。
      AtkDiag.log(this, "svc: 执行界面已在前台，跳过重复拉起 id=$id")
      return
    }
    AtkEngine(this).launchAlarmFace(id, false, muted = muted, dedupe = false)
  }

  private fun registerUnlockReceiver(id: Long) {
    unregisterUnlockReceiver()
    unlockReceiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) return
        val target = if (currentId >= 0) currentId else AtkAlarmState.mutedId
        if (target < 0) return
        onUserUnlocked(target)
      }
    }
    try {
      registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
      AtkDiag.log(this, "svc: 解锁监听已注册 id=$id")
    } catch (e: Exception) {
      AtkDiag.log(this, "svc: 解锁监听注册失败 ${e.message}")
    }
  }

  private fun unregisterUnlockReceiver() {
    try { unlockReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
    unlockReceiver = null
  }

  private fun schedulePendingPause(action: () -> Unit) {
    cancelPendingPause()
    val h = Handler(Looper.getMainLooper())
    val task = Runnable {
      pendingPauseTask = null
      action()
    }
    pauseHandler = h
    pendingPauseTask = task
    h.postDelayed(task, 1100L)
  }

  private fun cancelPendingPause() {
    try { pendingPauseTask?.let { pauseHandler?.removeCallbacks(it) } } catch (e: Exception) { }
    pendingPauseTask = null
    pauseHandler = null
  }

  /**
   * 3.7.1：静音 ≠ 终止。只停铃声 / 振动，注销翻转、电源键与自动贪睡（避免静音后又自动响），
   * 但**不 dismiss、不 stopSelf**：任务仍在，前台服务与解锁监听保留，用户解锁后能看到「只剩
   * 贪睡 / 终止」的执行界面；点贪睡 / 终止才真正结束。
   * 3.7.3：另外挂一个「静音时长」到点重新执行的闹钟 —— 静音后睡着也不会永远不响。
   * 用户随后点贪睡 / 终止时，AtkEngine.dismiss 会取消这个闹钟。
   */
  private fun muteOnly(id: Long, minutes: Int = muteDurMin) {
    currentId = id
    AtkAlarmState.mutedId = id
    AtkAlarmSound.stop()
    unregisterFlipSensor()
    unregisterPowerReceiver()
    cancelPendingPause()
    cancelAutoSnooze()
    // 服务若因静音才被拉起（前台服务此前已被系统回收），这里也要补上解锁监听。
    registerUnlockReceiver(id)
    AtkEngine(this).armMuteResume(id, minutes)
    AtkDiag.log(this, "svc: 已静音，保留任务与执行界面，${minutes} 分钟后重新执行 id=$id")
  }

  /**
   * 3.6.9：暂停提醒。与 [stopEverything] 的关键区别是**不调用 dismiss**，因此本次任务的排程、
   * 自动贪睡次数都保留，且挂上「暂停 N 分钟后继续执行」的闹钟。用户手动终止 / 贪睡时
   * AtkEngine.dismiss 会顺带取消这个闹钟，不会关掉后又自动响。
   */
  private fun pauseOnly(id: Long) {
    AtkDiag.log(this, "svc: 暂停提醒 ${pauseDurMin} 分钟后继续 id=$id")
    AtkAlarmSound.stop()
    unregisterFlipSensor()
    unregisterPowerReceiver()
    unregisterUnlockReceiver()
    cancelPendingPause()
    cancelAutoSnooze()
    releaseWakeLock()
    if (foreground) {
      try {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } catch (e: Exception) {
      }
      foreground = false
    }
    currentId = -1L
    mountedId = -1L
    AtkEngine(this).armPauseResume(id, pauseDurMin)
    stopSelf(startIdForSnooze())
  }

  private fun unregisterPowerReceiver() {
    try { powerReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
    powerReceiver = null
  }

  /** onStartCommand 携带的 startId 在回调外不可得，用此保存以便 stopEverything。 */
  private var snoozeStartId = 0
  private fun startIdForSnooze(): Int = snoozeStartId


  /**
   * Read the firing task from the database, retrying a few times because the alarm may have
   * started this service from a cold process where the first SQLite open briefly contends with a
   * just-released connection (a common ColorOS cold-start hiccup that previously surfaced as a
   * silent "任务不存在或已停用"). Uses the application context so the read never depends on the
   * service's own (possibly not-yet-initialised) context.
   *
   * Returns the item plus, on failure, the last exception message so the diagnostic log can show
   * the real reason instead of a generic "not found".
   */
  private fun loadItem(id: Long): Pair<AtkItem?, String?> {
    if (id < 0) return null to "id 无效"
    var lastErr: String? = null
    for (attempt in 0 until 3) {
      try {
        val item = AtkStore(applicationContext).get(id)
        if (item != null) return item to null
        lastErr = "数据库无此 id（行不存在）"
      } catch (e: Exception) {
        lastErr = "${e.javaClass.simpleName}: ${e.message}"
      }
      if (attempt < 2) {
        try {
          Thread.sleep(150)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
        }
      }
    }
    return null to lastErr
  }

  /**
   * Android 14+ verifies the foreground service type against the manifest and the granted
   * permissions, and a mismatch throws instead of degrading.
   *
   * 3.0.0: `mediaPlayback` is tried LAST. On OnePlus/ColorOS (Android 14+) a mediaPlayback FGS
   * without an active, system-visible MediaSession is judged "type abuse" and the service is
   * torn down about a second after startForeground — that kill took the ringtone with it, which
   * is exactly what the 2.8.0 log `svc: onDestroy（前台=true）` showed. Both reference apps
   * verified working on this device use `specialUse` (SunriseAlarm) / a plain started service
   * (Smart Alarm), so specialUse is now the primary type.
   */
  private fun enterForeground(
    engine: AtkEngine,
    item: AtkItem,
    notificationId: Int,
    force: Boolean,
    lightScreen: Boolean,
  ): Boolean {
    val notification = runCatching { engine.buildAlarmNotification(item, force, lightScreen) }
      .getOrNull()
    if (notification == null) {
      AtkDiag.log(this, "svc: 通知构建失败")
      return false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val attempts = listOf(
        "specialUse" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        "mediaPlayback|specialUse" to (
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
          ),
        "mediaPlayback" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      )
      for ((label, type) in attempts) {
        try {
          startForeground(notificationId, notification, type)
          foreground = true
          AtkDiag.log(this, "svc: startForeground 成功 type=$label")
          return true
        } catch (e: Exception) {
          AtkDiag.log(this, "svc: startForeground($label) 失败 ${e.javaClass.simpleName}: ${e.message}")
        }
      }
    }
    if (try {
        startForeground(notificationId, notification)
        true
      } catch (e: Exception) {
        AtkDiag.log(this, "svc: startForeground 无类型失败 ${e.javaClass.simpleName}: ${e.message}")
        false
      }
    ) {
      foreground = true
      AtkDiag.log(this, "svc: startForeground 无类型成功")
      return true
    }
    // Last resort: a plain, quiet notification. Being a foreground service at all is what keeps
    // the audio session — and therefore the ringing — alive.
    return try {
      startForeground(notificationId, runCatching { engine.buildFallbackNotification(item) }
        .getOrElse { notification })
      foreground = true
      AtkDiag.log(this, "svc: startForeground 简化通知成功")
      true
    } catch (e: Exception) {
      AtkDiag.log(this, "svc: startForeground 全部失败 ${e.javaClass.simpleName}: ${e.message}")
      false
    }
  }

  /**
   * 3.6.5：在前台服务（常驻）注册重力 / 加速度传感器，监听翻转（屏幕朝下 z<-7）。翻转后：
   * flipMode==1 → 贪睡（与执行界面「贪睡」一致）；flipMode==2 → 静音（3.7.1 起等同界面上的
   * 「静音」，只停声音、任务与执行界面保留，见 [flipMute] / [muteOnly]）。
   * 放在服务而非 Activity，是因为「不点亮屏幕」模式下 Activity 不会被创建，此前翻转失效。
   */
  private fun registerFlipSensor(id: Long) {
    try {
      val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
      val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
      sensorManager = sm
      // 3.6.8：初始 armed=false，只有先读到「屏幕朝上(z>7)」才置 true。否则重响时手机若仍翻转
      // (z<-7)，onSensorChanged 会立刻再次触发翻转贪睡 → 闹钟刚响一声就被静音/停止。
      var armed = false
      gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          val z = event.values[2]
          if (armed && z < -7f && flipMode != 0) {
            armed = false
            if (flipMode == 1) flipSnooze(id) else flipMute(id)
          } else if (z > 7f) {
            armed = true
          }
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
      }
      sm.registerListener(gravityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
      AtkDiag.log(this, "svc: 翻转感应已注册 flipMode=$flipMode")
    } catch (e: Exception) {
      AtkDiag.log(this, "svc: 翻转感应注册失败 ${e.message}")
    }
  }

  private fun unregisterFlipSensor() {
    try { sensorManager?.unregisterListener(gravityListener) } catch (e: Exception) { }
    sensorManager = null
    gravityListener = null
  }

  private fun flipSnooze(id: Long) {
    AtkDiag.log(this, "svc: 翻转贪睡 id=$id")
    unregisterFlipSensor()
    AtkAlarmSound.stop()
    // 3.6.5：直接挂「参数里设置的贪睡时长」重响（engine.armSnooze(minutes)），并停止本前台服务。
    // 此前写死 10 分钟，与用户在「预设时间」里设的贪睡时长（如 3 分钟）不符；且经 ACTION_SNOOZE
    // 广播绕一圈再停服务时序不确定，导致贪睡重响丢失、闹钟不再响。
    AtkEngine(this).armSnooze(id, snapDurMin)
    stopSelf()
  }

  /**
   * 3.7.1：翻转静音不再是「停止任务」。[muteOnly] 保留任务、前台服务与解锁监听，用户解锁后
   * 由 [onUserUnlocked] 拉起「只剩贪睡 / 终止」的执行界面。点亮屏幕模式下界面本来就在前台，
   * 直接重建为静音形态；不点亮屏幕模式下不主动拉起，等用户解锁（不主动点亮屏幕）。
   */
  private fun flipMute(id: Long) {
    AtkDiag.log(this, "svc: 翻转静音（不终止任务）id=$id 点亮屏幕=$lightScreen")
    muteOnly(id)
    if (lightScreen) {
      AtkEngine(this).launchAlarmFace(id, false, muted = true, dedupe = false)
    } else {
      AtkDiag.log(this, "svc: 不点亮屏幕模式，等待用户解锁后进入执行界面 id=$id")
    }
  }

  private fun stopEverything(startId: Int) {
    AtkAlarmSound.stop()
    unregisterFlipSensor()
    unregisterPowerReceiver()
    unregisterUnlockReceiver()
    cancelPendingPause()
    cancelAutoSnooze()
    AtkAlarmState.reset()
    releaseWakeLock()
    if (foreground) {
      try {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } catch (e: Exception) {
      }
      foreground = false
    }
    if (currentId >= 0) {
      runCatching { AtkEngine(this).dismiss(currentId) }
      currentId = -1L
    }
    mountedId = -1L
    stopSelf(startId)
  }

  private fun acquireWakeLock() {
    if (wakeLock != null) return
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AddinTimer:alarm").apply {
        setReferenceCounted(false)
        acquire(WAKE_TIMEOUT_MS)
      }
      AtkDiag.log(this, "svc: 已获取唤醒锁")
    } catch (e: Exception) {
      wakeLock = null
      AtkDiag.log(this, "svc: 唤醒锁失败 ${e.message}")
    }
  }

  private fun releaseWakeLock() {
    try {
      if (wakeLock?.isHeld == true) wakeLock?.release()
    } catch (e: Exception) {
    }
    wakeLock = null
  }

  override fun onDestroy() {
    // stopService() bypasses onStartCommand, so clean up here as well. The tone is deliberately
    // NOT stopped here: every explicit stop path (终止 / 贪睡 / 静音 / the notification actions)
    // already stops it, and destroying the service while the 执行界面 is still ringing must not
    // silence the alarm.
    // The foreground / wakelock flags tell 权限自检 → 触发日志 whether this was a normal stop
    // (foreground already false, e.g. 终止 / 贪睡) or an unexpected kill by the system / OEM
    // (foreground still true here means the FGS was torn down externally — the root cause of
    // "锁屏 / 退到后台就听不到闹钟").
    AtkDiag.log(this, "svc: onDestroy（前台=$foreground, 仍持唤醒锁=${wakeLock?.isHeld == true}）")
    unregisterFlipSensor()
    unregisterPowerReceiver()
    unregisterUnlockReceiver()
    cancelPendingPause()
    cancelAutoSnooze()
    releaseWakeLock()
    if (foreground) {
      try {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } catch (e: Exception) {
      }
      foreground = false
    }
    super.onDestroy()
  }
}
