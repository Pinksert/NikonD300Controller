package com.example.nikond300controller

/**
 * 快门档位的**纯函数**集合 —— 数值 ↔ 显示串 ↔ 排序规则。
 *
 * 为什么要单独拆出来：这三个函数改坏了不报错，只是"菜单顺序看起来怪怪的"或者
 * "某一档显示成 10/25"，回归测试根本抓不到。放在 Activity 里（依赖
 * activeShutterProp 字段、又掺着 runOnUiThread）就没法在 JVM 上直接跑断言。
 * 抽成无状态 object 之后 `app/src/test` 里能直接测，见 ShutterSpeedsTest。
 *
 * 全部函数都不碰 Android API，也不读任何成员变量 —— 当前用的是哪条属性
 * （尼康 0xD100 还是标准 0x500D）由参数 [nikonProp] 显式传入。
 */
object ShutterSpeeds {

    /** B 门：机身 PTP 里用 -1 表示；0xD100 描述符通常不列它，得自己补上 */
    const val BULB = -1
    /** 闪光同步（X 同步）：机身 PTP 里用 -2 表示，同样不在描述符里 */
    const val FLASH = -2

    /** 档位排法：正常档（1/8000 → 30s）按曝光时长升序，B 门 / 闪光同步垫在最后 */
    fun rank(v: Int): Int = when (v) {
        BULB -> 1
        FLASH -> 2
        else -> 0
    }

    /**
     * 一个档位代表多少秒 —— **排序只能按它，不能按 PTP 原始值**。
     *
     * 这就是"档位顺序乱"的根因：0xD100 是 bit-packed（高 16 分子 / 低 16 分母），
     * 原始整数大小和快慢毫无关系。比如 1" 是 0x00010001（65537）、1/8000 是 0x00011F40（73536），
     * 按原始值升序排就变成 1" → 1/2 → 1/4 → … → 1/8000 → 2" → 30"，
     * 前半段慢到快、后半段又快回慢，用户看到的就是"乱的"。
     * 标准属性 0x500D 是 1/10000 秒为单位，原始值大小和时长倒是同向，但也统一换算成秒更省心。
     */
    fun seconds(v: Int, nikonProp: Boolean): Double {
        if (v == BULB || v == FLASH) return 0.0
        return if (nikonProp) {
            val numerator = (v shr 16) and 0xFFFF
            val denominator = v and 0xFFFF
            if (numerator <= 0 || denominator <= 0) 0.0 else numerator.toDouble() / denominator.toDouble()
        } else {
            v / 10000.0
        }
    }

    /** 快门档位统一排法：正常档按时长由短到长，B 门 / 闪光同步垫底（内部先后固定为 B 门 → 闪光同步） */
    fun sorted(values: Iterable<Int>, nikonProp: Boolean): List<Int> =
        values.sortedWith(
            compareBy<Int> { rank(it) }
                .thenBy { seconds(it, nikonProp) }
                .thenBy { it }
        )

    /**
     * 档位 → 菜单上给人看的串。
     *
     * @param nikonProp true = 尼康私有 0xD100（bit-packed）；false = 标准 0x500D（1/10000 秒）
     */
    fun label(v: Int, nikonProp: Boolean): String {
        if (v == BULB) return "B 门"
        if (v == FLASH) return "Flash"

        return if (nikonProp) {
            // Nikon bit-packed（0xD100）：高 16 位 = 分子，低 16 位 = 分母，单位秒。
            // 真机枚举里除了 1/8000…1/1 这些"分子为 1"的档，还有几档是非 1 分子：
            //   0x000A0019 = 10/25 → 1/2.5      0x000A0010 = 10/16 → 1/1.6
            //   0x000A000D = 10/13 → 1/1.3      0x000D000A = 13/10 → 1.3"
            // 只判"分子==1"和"分子>分母"是不够的：10/25 会原样显示成 "10/25" ——
            // 那不是快门档，用户看到只会以为读错了（这就是"快门速度显示错误"）。
            val numerator = (v shr 16) and 0xFFFF
            val denominator = v and 0xFFFF
            when {
                numerator <= 0 || denominator <= 0 ->
                    "0x${Integer.toHexString(v).uppercase()}"
                denominator == 1 -> "${numerator}\""
                numerator == 1 -> "1/$denominator"
                else -> {
                    val sec = numerator.toDouble() / denominator
                    if (sec >= 1.0) {
                        // 1.3" / 1.6" / 2.5" / 30" —— 整秒就不带小数
                        if (Math.abs(sec - Math.round(sec)) < 0.01) "${Math.round(sec)}\""
                        else String.format("%.1f\"", sec)
                    } else {
                        // 小于 1 秒的档一律写成 1/x：10/25 → 1/2.5，10/16 → 1/1.6
                        val inv = denominator.toDouble() / numerator
                        if (Math.abs(inv - Math.round(inv)) < 0.05) "1/${Math.round(inv)}"
                        else String.format("1/%.1f", inv)
                    }
                }
            }
        } else {
            // 标准 PTP 曝光时间（0x500D）：单位是 1/10000 秒
            val sec = v / 10000
            val rest = v % 10000
            if (sec > 0) {
                if (rest > 0) {
                    val frac = Math.round(1.0 / (rest * 0.0001))
                    "${sec}\" 1/$frac"
                } else {
                    "${sec}\""
                }
            } else if (rest > 0) {
                "1/${Math.round(1.0 / (rest * 0.0001))}"
            } else {
                "0\""
            }
        }
    }

    /** 打包一个 0xD100 档位（分子/分母）方便测试与构造默认值 */
    fun packed(numerator: Int, denominator: Int): Int =
        ((numerator and 0xFFFF) shl 16) or (denominator and 0xFFFF)
}
