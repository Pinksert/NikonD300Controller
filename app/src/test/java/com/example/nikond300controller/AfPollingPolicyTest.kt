package com.example.nikond300controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对焦等待策略的自测。
 *
 * 这些数字直接决定「合焦多久被发现」和「没对上多久能告诉用户」，改策略必须同时改这里：
 * 断言写的是**行为契约**（越早越密、探针有周期、防误判保护期），不是背数字。
 */
class AfPollingPolicyTest {

    @Test
    fun `采样间隔随耗时变粗，且永远落在 30-80ms 内`() {
        // 起步阶段必须最密：这是合焦最可能发生的窗口
        assertEquals(30L, AfPollingPolicy.intervalMs(0))
        assertEquals(30L, AfPollingPolicy.intervalMs(299))
        // 中段
        assertEquals(45L, AfPollingPolicy.intervalMs(300))
        assertEquals(45L, AfPollingPolicy.intervalMs(1199))
        // 后段放稀，省机身 CPU
        assertEquals(80L, AfPollingPolicy.intervalMs(1200))
        assertEquals(80L, AfPollingPolicy.intervalMs(6000))
        // 单调不增（间隔只会变粗，不会忽然又变密）
        var prev = AfPollingPolicy.intervalMs(0)
        for (ms in 0..6000 step 10) {
            val cur = AfPollingPolicy.intervalMs(ms.toLong())
            assertTrue("间隔不该变密：$ms ms 处 $cur < $prev", cur >= prev)
            prev = cur
        }
        // 每轮还要加一次 0xD104 往返（约 6–15ms），所以真实周期不超过 95ms
        for (ms in 0..6000 step 50) {
            assertTrue("采样周期过长：${AfPollingPolicy.intervalMs(ms.toLong())}", AfPollingPolicy.intervalMs(ms.toLong()) <= 80L)
        }
    }

    @Test
    fun `DeviceReady 每 3 轮探一次，事件队列每 6 轮清一次`() {
        val readyRounds = (0 until 30).filter { AfPollingPolicy.shouldProbeDeviceReady(it) }
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21, 24, 27), readyRounds)
        val eventRounds = (0 until 30).filter { AfPollingPolicy.shouldPollEvents(it) }
        assertEquals(listOf(0, 6, 12, 18, 24), eventRounds)
        // 事件清理一定是 DeviceReady 那一轮的子集（同一轮里顺带清，不多发命令）
        assertTrue(eventRounds.all { AfPollingPolicy.shouldProbeDeviceReady(it) })
    }

    @Test
    fun `0x2019 与 0x2002 算还在跑，0x2001 才算 AF 结束`() {
        // 真机日志：整段对焦过程 0x90C8 都回 0x2019（Focus Not Locked）→ 必须继续等
        assertTrue(AfPollingPolicy.isStillFocusing(0x2019))
        assertTrue(AfPollingPolicy.isStillFocusing(0x2002))
        assertFalse(AfPollingPolicy.isStillFocusing(0x2001))
        // 只有 0x2001（机身空闲）才算 AF 结束
        assertTrue(AfPollingPolicy.isReady(0x2001))
        assertFalse(AfPollingPolicy.isReady(0x2019))
        assertFalse(AfPollingPolicy.isReady(0x2002))
        // 两个判定互斥且覆盖住"继续等 / 可以继续拍"这两种状态
        for (code in listOf(0x2001, 0x2002, 0x2019)) {
            assertFalse(
                "回码 0x${Integer.toHexString(code)} 不能同时算两种状态",
                AfPollingPolicy.isReady(code) && AfPollingPolicy.isStillFocusing(code)
            )
        }
    }

    @Test
    fun `最坏情况采样次数与旧版对比`() {
        // 旧版：固定 100ms × 60 轮 = 6 秒，最多 60 次采样
        // 新版：按变步长累计到 6 秒，采样次数明显更多 —— 合焦判定更细
        var t = 0L
        var count = 0
        while (t < AfPollingPolicy.MAX_WAIT_MS) {
            t += AfPollingPolicy.intervalMs(t) + 10   // 假设每轮事务 10ms
            count++
        }
        assertTrue("新版采样次数应不少于旧版的 60 次，实际 $count", count >= 60)
        assertTrue("但也不能疯狂刷机身（>200 次/6 秒）", count <= 200)
    }
}
