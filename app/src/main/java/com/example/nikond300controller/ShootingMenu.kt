package com.example.nikond300controller

/**
 * 拍摄菜单（Shooting Menu）—— 只收机身上「拍摄菜单」**真正有**的项
 * （说明书书页 252–257 的清单：文件夹 / 文件名称 / 影像品质 / 影像尺寸 / JPEG 压缩 /
 * NEF (RAW) 记录 / 白平衡 / 色彩空间 / 动态 D-Lighting / 长时间曝光噪点消减 /
 * 高 ISO 噪点消减 / 即时取景 / 多重曝光 / 间隔定时拍摄…）。
 *
 * 2026-10-22 按说明书做了一次大扫除：原先混在这里的
 * 曝光补偿(=b4)、曝光延迟模式(=d9)、闪光同步速度(=e1)、闪光快门速度(=e2)、
 * AF 辅助照明(=a9)、动态 AF 区域(=a3)、自动包围及步长张数(=e 组 / BKT 按钮)
 * 全部归还「个人设定菜单」（CsmMenu）—— 机内菜单在哪，App 就在哪。
 *
 * 说明：
 * - [options] 只写"确定知道语义"的档位；没写的档位会显示成「值 N」并把原始值露出来 ——
 *   宁可显示原始码，也不硬编一个可能错的中文名（D300 的档位编码各机型并不统一）。
 * - 有些项机身上**不支持**（色彩空间 0xD032 / JPEG 策略 0xD031 / RAW 位深 0xD149
 *   真机不应答），界面会显示读取失败 —— 保留条目是为了跟机内菜单对得上。
 * - 影像品质 / 影像尺寸 / 白平衡在拍摄页有专门的芯片入口，这里不重复列。
 */
object ShootingMenu {

    data class Item(
        val code: Int,
        val name: String,
        /** 说明 / 单位提示，显示在二级页的副标题里 */
        val hint: String = "",
        val options: Map<Long, String> = emptyMap()
    )

    /** 按机身上「拍摄菜单」的分组顺序排列 */
    val groups: List<Pair<String, List<Item>>> = listOf(

        "画质与色彩" to listOf(
            Item(
                0xD032, "色彩空间",
                // 真机取值：0 = Adobe RGB、1 = sRGB（D300 只有这两档）
                options = mapOf(0L to "Adobe RGB", 1L to "sRGB")
            ),
            Item(
                0xD14E, "动态 D-Lighting",
                options = mapOf(
                    0L to "关闭", 1L to "低", 3L to "标准", 5L to "高",
                    7L to "较高", 9L to "自动"
                )
            ),
            Item(
                0xD031, "JPEG 压缩策略",
                options = mapOf(0L to "尺寸优先", 1L to "画质优先")
            ),
            Item(
                0xD149, "NEF (RAW) 位深",
                hint = "NEF (RAW) 记录的位深",
                options = mapOf(0L to "12 bit", 1L to "14 bit")
            )
        ),

        "降噪" to listOf(
            Item(
                0xD06B, "长时间曝光降噪",
                options = mapOf(0L to "关闭", 1L to "开启")
            ),
            Item(
                0xD070, "高 ISO 降噪",
                options = mapOf(
                    0L to "关闭", 1L to "低", 2L to "标准", 3L to "高"
                )
            )
        ),

        "即时取景" to listOf(
            Item(
                0xD1A1, "取景驱动模式",
                hint = "即时取景里的驱动方式（单张 / 低速连拍 / 高速连拍）",
                options = mapOf(0L to "单张", 1L to "低速连拍", 2L to "高速连拍")
            ),
            Item(
                0xD1A3, "取景放大倍率",
                hint = "取景画面放大档位；0 = 不放大",
                options = emptyMap()
            )
        )
    )

    val items: List<Item> = groups.flatMap { it.second }

    /** 值 → 档位名；不认识的档位把原始值露出来，绝不猜 */
    fun label(item: Item, v: Long): String = item.options[v] ?: "值 $v"
}
