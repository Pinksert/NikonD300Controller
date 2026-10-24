package com.example.nikond300controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快门档位三件事：显示串、时长、排序。
 *
 * 这三个函数改坏了**不会报错**，只是"菜单里 1/2.5 变成 10/25"或者"档位顺序看着怪"，
 * 肉眼回归基本抓不住 —— 所以在这里用断言把真机上踩过的坑钉死。
 */
class ShutterSpeedsTest {

    private val nikon = true
    private val standard = false

    private fun n(num: Int, den: Int) = ShutterSpeeds.packed(num, den)

    // ---------------- 显示串 ----------------

    @Test
    fun `尼康 bit-packed 档位的显示串`() {
        assertEquals("1/8000", ShutterSpeeds.label(n(1, 8000), nikon))
        assertEquals("1/250", ShutterSpeeds.label(n(1, 250), nikon))
        assertEquals("1\"", ShutterSpeeds.label(n(1, 1), nikon))
        assertEquals("30\"", ShutterSpeeds.label(n(30, 1), nikon))
    }

    /**
     * ★ 真机踩过的坑：非 1 分子的档。
     * 只判"分子==1 / 分子>分母"的话，10/25 会原样显示成 "10/25" ——
     * 那不是一个快门档，用户只会以为读错了（这就是"快门速度显示错误"）。
     */
    @Test
    fun `非 1 分子的档要折算成 1_x 或 x 秒`() {
        assertEquals("1/2.5", ShutterSpeeds.label(n(10, 25), nikon))   // 0x000A0019
        assertEquals("1/1.6", ShutterSpeeds.label(n(10, 16), nikon))   // 0x000A0010
        assertEquals("1/1.3", ShutterSpeeds.label(n(10, 13), nikon))   // 0x000A000D
        assertEquals("1.3\"", ShutterSpeeds.label(n(13, 10), nikon))   // 0x000D000A
    }

    @Test
    fun `B 门与闪光同步有固定文案`() {
        assertEquals("B 门", ShutterSpeeds.label(ShutterSpeeds.BULB, nikon))
        assertEquals("Flash", ShutterSpeeds.label(ShutterSpeeds.FLASH, nikon))
        // 换到标准属性也一样（它们跟属性编码方式无关）
        assertEquals("B 门", ShutterSpeeds.label(ShutterSpeeds.BULB, standard))
        assertEquals("Flash", ShutterSpeeds.label(ShutterSpeeds.FLASH, standard))
    }

    @Test
    fun `标准 0x500D 档位按 1 万分之一秒折算`() {
        // 单位是 1/10000 秒：25 → 25/10000 s = 1/400
        assertEquals("1/400", ShutterSpeeds.label(25, standard))
        assertEquals("1/10000", ShutterSpeeds.label(1, standard))
        assertEquals("30\"", ShutterSpeeds.label(300000, standard))
    }

    // ---------------- 时长 ----------------

    @Test
    fun `尼康档位换算成秒`() {
        assertEquals(1.0 / 8000.0, ShutterSpeeds.seconds(n(1, 8000), nikon), 1e-9)
        assertEquals(1.0, ShutterSpeeds.seconds(n(1, 1), nikon), 1e-9)
        assertEquals(30.0, ShutterSpeeds.seconds(n(30, 1), nikon), 1e-9)
    }

    @Test
    fun `B 门与闪光同步不参与时长比较`() {
        assertEquals(0.0, ShutterSpeeds.seconds(ShutterSpeeds.BULB, nikon), 0.0)
        assertEquals(0.0, ShutterSpeeds.seconds(ShutterSpeeds.FLASH, nikon), 0.0)
    }

    // ---------------- 排序 ----------------

    /**
     * ★★ 这条是"档位顺序乱"的回归测试。
     *
     * 0xD100 是 bit-packed，原始整数大小和快慢毫无关系：
     * 1" 是 0x00010001（65537），1/8000 是 0x00011F40（73536）——
     * 按原始值升序排会得到 1" → 1/2 → … → 1/8000 → 2" → 30"，
     * 前半段慢到快、后半段又快回慢。所以排序只能按 [ShutterSpeeds.seconds]。
     */
    @Test
    fun `档位按时长升序且特殊档垫底`() {
        val input = listOf(n(30, 1), n(1, 8000), ShutterSpeeds.BULB, n(1, 1), ShutterSpeeds.FLASH)
        val sorted = ShutterSpeeds.sorted(input, nikon)
        assertEquals(
            listOf(n(1, 8000), n(1, 1), n(30, 1), ShutterSpeeds.BULB, ShutterSpeeds.FLASH),
            sorted
        )
    }

    /** 上面那条如果改成"按原始整数排"，结果必须不一样 —— 用来证明这条断言真的在挡事 */
    @Test
    fun `按原始整数排序会得到错误顺序`() {
        val input = listOf(n(30, 1), n(1, 8000), n(1, 1))
        assertNotEquals(ShutterSpeeds.sorted(input, nikon), input.sorted())
    }

    @Test
    fun `标准属性的档位也按时长升序`() {
        // 0x500D：1/8000=1(单位 1/10000s 的倒数要看清) —— 这里只验证相对顺序
        val input = listOf(10000, 25, 1, 1_0000 * 30)
        val sorted = ShutterSpeeds.sorted(input, standard)
        assertEquals(listOf(1, 25, 10000, 300000), sorted)
    }

    @Test
    fun `排序结果里特殊档永远在最后两位`() {
        val input = listOf(ShutterSpeeds.FLASH, n(1, 60), ShutterSpeeds.BULB, n(2, 1))
        val sorted = ShutterSpeeds.sorted(input, nikon)
        assertEquals(ShutterSpeeds.BULB, sorted[sorted.size - 2])
        assertEquals(ShutterSpeeds.FLASH, sorted.last())
        assertTrue(sorted.size == 4)
    }
}
