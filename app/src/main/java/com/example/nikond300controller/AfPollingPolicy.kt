package com.example.nikond300controller

/**
 * 自动对焦等待阶段的采样策略（纯函数，便于单测）。
 *
 * 这些数字决定了「合焦被发现得有多快」和「没对上时多久能告诉用户」，改之前先看这里：
 *
 * - **采样间隔**：0.3 秒内 30ms、1.2 秒内 45ms、之后 80ms。
 *   起步阶段最需要密采样（对焦刚跑起来，随时可能合上）；1.2 秒后基本是慢镜头在磨，
 *   放稀一点省机身 CPU 和 USB 带宽。
 * - **锁焦判据**（0xD104）每轮都读，单次事务，这是"对上了"的直接信号。
 * - **空闲判据**（0x90C8）每 3 轮读一次：它用来区分"AF 还在跑"和"跑完了但没对上"，
 *   后者能当场收手 —— 旧实现要干等到 6 秒上限才知道失败。
 * - **事件队列**每 6 轮清一次：事件不是合焦判据，没必要跟着每轮跑。
 *
 * 每轮实际耗时 = 一次 0xD104 往返（约 6–15ms）+ 间隔，所以真实采样周期约 40–60ms，
 * 比旧版固定 100ms 细一倍，合焦判定误差相应减半。
 */
object AfPollingPolicy {

    /** 一共产等多久（毫秒）。AF 电机跑 6 秒还没结果，基本就是镜头在空转或挡住了 */
    const val MAX_WAIT_MS = 6000L

    /**
     * 0x90C1 发出去之后，机身可能还没来得及进入"忙"，这段时间的"空闲"不算数。
     * 否则会把"刚要开始对焦"误判成"对完了但没对上"。
     */
    const val IDLE_GUARD_MS = 250L

    /**
     * 一旦看到"空闲 + 未锁焦"，**不立刻收手**，再多观察这么久。
     *
     * 真机日志显示单次"空闲"有可能出现在 AF 刚刚起步的瞬间（0x90C1 是异步的），
     * 立刻判失败会把本来能合上的对焦掐掉。多给一个窗口：这段时间里只要
     * 0xD104 变 1 就按成功算；窗口走完仍然没锁焦，才认定"机身结束 AF 且没对上"。
     * 代价是把失败判定推迟 200ms，相比旧版干等 6 秒仍然快一个数量级。
     */
    const val IDLE_GRACE_MS = 200L

    /** 0x90C8 的探测周期（每 N 轮一次） */
    const val DEVICE_READY_EVERY = 3

    /** 事件队列清理周期（每 N 轮一次） */
    const val POLL_EVENTS_EVERY = 6

    /** 下一次采样前该睡多久 */
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
     * 是不是"AF 已经结束、但没对上焦"。
     *
     * @param deviceReady 0x90C8 是否回了 OK（机身空闲）
     * @param lockStatus 0xD104 的值（1 = 已锁焦）
     * @param elapsedMs 从发 0x90C1 到现在过了多久
     */
    fun isFinishedUnfocused(deviceReady: Boolean, lockStatus: Int, elapsedMs: Long): Boolean =
        deviceReady && lockStatus != 1 && elapsedMs > IDLE_GUARD_MS

    /**
     * 空闲观察窗口是否已经走满（走满才允许收手）。
     *
     * @param idleSinceMs 第一次看到"空闲 + 未锁焦"的时刻；-1 表示还没看到过
     * @param elapsedMs 从发 0x90C1 到现在过了多久
     */
    fun shouldStopUnfocused(idleSinceMs: Long, elapsedMs: Long): Boolean =
        idleSinceMs >= 0 && elapsedMs - idleSinceMs >= IDLE_GRACE_MS
}
