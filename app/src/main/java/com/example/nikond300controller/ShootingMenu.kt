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

        "拍摄菜单库" to listOf(
            // 机身上那 4 个拍摄菜单库（A/B/C/D）。切库会连带换掉一整组拍摄设置，
            // 所以给个说明，别让人以为只是改个名字。
            Item(
                PtpConstants.PROP_NIKON_SHOOTING_BANK, "拍摄菜单库",
                hint = "A/B/C/D 四个库，切换会整套换掉拍摄设置",
                options = mapOf(0L to "A", 1L to "B", 2L to "C", 3L to "D")
            )
        ),

        "画质与色彩" to listOf(
            Item(
                0xD032, "色彩空间",
                // 真机取值：0 = Adobe RGB、1 = sRGB（D300 只有这两档）
                options = mapOf(0L to "Adobe RGB", 1L to "sRGB")
            ),
            // RAW 压缩方式：D300 只有「无损压缩 / 压缩 / 未压缩」三档，
            // 但具体编码由机身报，这里只把常见值翻译出来，其余显原始值。
            Item(
                PtpConstants.PROP_NIKON_RAW_COMPRESSION, "NEF 压缩类型",
                hint = "无损压缩 / 压缩 / 未压缩；档位以机身自述为准"
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

        "白平衡微调" to listOf(
            // 每个白平衡预设各自有一份微调（琥珀-蓝 / 绿-品红两轴）。
            // 编码机身自报，界面按值域列档，不硬写"±3"这种名字。
            Item(PtpConstants.PROP_NIKON_WB_TUNE_AUTO, "自动", hint = "自动白平衡的微调"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_INCANDESCENT, "白炽灯"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_FLUORESCENT, "荧光灯"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_SUNNY, "晴天"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_FLASH, "闪光灯"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_CLOUDY, "阴天"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_SHADE, "阴影"),
            Item(
                PtpConstants.PROP_NIKON_WB_TUNE_FLUOR_TYPE, "荧光灯类型",
                hint = "荧光灯微调的前置子项（灯管类型）"
            ),
            Item(
                PtpConstants.PROP_NIKON_WB_TUNE_COLOR_TEMP, "色温微调",
                hint = "选择色温（K）时的微调"
            ),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_PRESET0, "预设 d-1"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_PRESET1, "预设 d-2"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_PRESET2, "预设 d-3"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_PRESET3, "预设 d-4"),
            Item(PtpConstants.PROP_NIKON_WB_TUNE_PRESET4, "预设 d-5")
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
