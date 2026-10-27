package com.example.nikond300controller

/**
 * 自动对焦等待阶段的采样与判定策略（纯函数，便于单测）。
 *
 * ---- 采样节奏 ----
 * 0.3 秒内 30ms 一跳、1.2 秒内 45ms、之后 80ms。起步阶段最需要密采样
 * （对焦随时可能合上），1.2 秒后基本是慢镜头在磨，放稀一点省机身 CPU 和 USB 带宽。
 * 每轮还要读一次 0xD104（约 6–15ms），所以真实采样周期约 40–60ms。
 *
 * ---- 判定依据（2026-10-28 00:20 按官方回码表修正）----
 * 查官方 §5.3 回码表后**再次纠正**：
 * - `0x2019` = **Device_Busy**（机身忙），不是"Focus Not Locked"
 * - `0xA002` = **Out_of_Focus**（AF 结束但没对上焦）← 这才是"未合焦"的唯一信号
 * - `0x2001` = OK（正常终止）
 *
 * 真机探针序列因此读作：`0x2019`（忙）→ `0xA002`（没对上）→ `0x2001`（忙完了）。
 * 也就是说之前那次"已合焦"其实是**没对上**，0x2001 只代表机身闲下来了。
 *
 * 现在的模型：
 * - `0x2019 / 0x2002` → AF 还在跑，继续等
 * - **`0xA002` → AF 结束且未合焦 → 立即判失败**（不等 0x2001）
 * - `0x2001` → 机身空闲且没报 Out_of_Focus → 成功
 * - 其它回码 → 记日志后继续等（不为怪码打断对焦），由 6 秒上限兜底
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
     * 这个回码是不是"AF 还在跑"。
     *
     * 0x2019 = Nikon 的 Device_Busy（官方 §5.3.20），真机对焦全程都回它；
     * 0x2002 = 标准 DeviceBusy。两者都当"继续等"。
     */
    fun isStillFocusing(code: Int): Boolean = code == 0x2019 || code == 0x2002

    /** 这个回码是不是"AF 结束但没对上焦"（官方 §5.3.30 Out_of_Focus） */
    fun isOutOfFocus(code: Int): Boolean = code == 0xA002

    /** 这个回码是不是"机身已空闲"（AF 正常结束、可以继续拍照） */
    fun isReady(code: Int): Boolean = code == 0x2001
}
