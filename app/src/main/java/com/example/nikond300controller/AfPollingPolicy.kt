package com.example.nikond300controller

/**
 * 自动对焦等待阶段的采样与判定策略（纯函数，便于单测）。
 *
 * ---- 采样节奏 ----
 * 0.3 秒内 30ms 一跳、1.2 秒内 45ms、之后 80ms。起步阶段最需要密采样
 * （对焦随时可能合上），1.2 秒后基本是慢镜头在磨，放稀一点省机身 CPU 和 USB 带宽。
 * 每轮还要读一次 0xD104（约 6–15ms），所以真实采样周期约 40–60ms。
 *
 * ---- 判定依据（2026-10-28 00:00 真机探针日志修正）----
 * 之前把 0xD104 AFLockStatus 当"合焦判据"，还据此加过"空闲且未锁焦 → 判失败"的规则。
 * 实测日志显示：**整段对焦过程中 0xD104 一直是 0**，而 0x90C8 稳定回
 * **0x2019（Focus Not Locked）** —— 这才是"AF 还在跑"的真实信号。
 * 于是出现了"机身已经给合焦提示、App 却报未合焦"（判据选错 + 提前收手）。
 *
 * 现在的模型：
 * - `0x90C8 = 0x2019 / 0x2002` → AF 还在跑，继续等
 * - `0x90C8 = 0x2001` → 机身空闲、AF 结束 → 成功
 * - `0xD104 = 1` → 也算成功（个别机型/模式下会亮），当加分信号
 * - 其它回码 → 记日志后继续等（不为一个怪码打断对焦），由 6 秒上限兜底
 */
object AfPollingPolicy {

    /** 一共产等多久（毫秒）。AF 电机跑 6 秒还没结果，基本就是镜头在空转或挡住了 */
    const val MAX_WAIT_MS = 6000L

    /** 0x90C8 的探测周期（每 N 轮一次） */
    const val DEVICE_READY_EVERY = 3

    /** 事件队列清理周期（每 N 轮一次） */
    const val POLL_EVENTS_EVERY = 6

    /** 下一轮采样前该睡多久 */
    fun intervalMs(elapsedMs: Long): Long = when {
        elapsedMs < 300L -> 30L
        elapsedMs < 1200L -> 45L
        else -> 80L
    }

    /** 这一轮要不要问 0x90C8 DeviceReady */
    fun shouldProbeDeviceReady(round: Int): Boolean = round % DEVICE_READY_EVERY == 0

    /** 这一轮要不要清事件队列 */
    fun shouldPollEvents(round: Int): Boolean = round % POLL_EVENTS_EVERY == 0

    /**
     * 这个 0x90C8 回码是不是"AF 还在跑"。
     *
     * 0x2019 = Nikon「Focus Not Locked」，真机对焦全程都回它；
     * 0x2002 = 标准 DeviceBusy。两者都当"继续等"。
     */
    fun isStillFocusing(code: Int): Boolean = code == 0x2019 || code == 0x2002

    /** 这个回码是不是"机身已空闲"（AF 结束、可以继续拍照） */
    fun isReady(code: Int): Boolean = code == 0x2001
}
