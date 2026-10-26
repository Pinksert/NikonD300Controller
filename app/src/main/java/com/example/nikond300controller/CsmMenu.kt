package com.example.nikond300controller

/**
 * D300 个人设定菜单（Custom Settings Menu）的条目定义。
 *
 * 编号、中文名、每个档位的中文说法，全部以 **D300 中文说明书「个人设定」章节**
 * （书页 265–310，即 `D300_NT(Sc)04.pdf` 第 291–336 页）为准。
 * 之前那版是照 Android Studio 半成品 + libgphoto2 老编号拼的，有几处跟说明书对不上
 * （a1/a2/a7 的选项顺序写反、c4 多了一档、d8 应为 d9、f5 实为 f7），这里已逐一改正。
 *
 * 两条铁律，改这个文件前先读一遍：
 *
 * 1. **机身才是唯一的裁判**。这里的 `options` 只是"翻译表"，真机 readPropDesc 报回来的
 *    值域永远优先；两者对不上时界面显示机身的原始值，不硬套这张表。所以这张表多写几档
 *    是无害的（值域外不会出现），**少写或写错顺序才会害用户改错机身设置**。
 *
 * 2. **属性码不硬猜**。libgphoto2 能给到明确命名的，用它的码；给不到的，`code` 写
 *    [PtpConstants.CSM_CODE_UNKNOWN]，界面上灰显为「待扫描」，由 App 的
 *    「扫描未适配项」按"机身值域档数 == 说明书选项数"去匹配。
 *    猜一个码写进机身 = 静默改坏用户设置，那比不做更糟。
 */
data class CsmItem(
    val code: Int,
    /** 机身菜单编号，例如 "a1"、"d9"；个人设定库 / 重设用 "B"、"R" */
    val id: String,
    /** 界面上显示的名字，例如 "AF-C 优先选择" */
    val name: String,
    /** 值 → 中文档位名（照说明书的顺序）。为空表示档位写法不确定，交给机身值域兜底。 */
    val options: Map<Int, String> = emptyMap(),
    /**
     * 属性码是否已经确认（真机应答过 / 有 libgphoto2 明确命名）。
     * false 的项由扫描匹配，未匹配前不允许写入。
     */
    val verified: Boolean = true,
    /** 扫描时在哪个区间找这个码；只在 verified == false 时有意义。 */
    val scanRange: IntRange = 0..0,
    /**
     * 说明书上这一项共有几档 —— 扫描时用它跟机身报回来的值域档数比对。
     * 默认取 [options] 的大小；档位写法不确定（比如 d5 的 1–100 张）时单独指定。
     */
    val expectedCount: Int = options.size,
    /**
     * 真机 dump 出来的候选码（多个候选/档数撞车时列出来，由用户逐个"试读"确认）。
     * 空列表 = 候选区间里没有符合档数的属性，只能等用户手动指定。
     */
    val candidates: List<Int> = emptyList()
) {
    val label: String get() = "$id · $name"
}

data class CsmGroup(val title: String, val items: List<CsmItem>)

object CsmMenu {

    // 2026-10-26：所有项都已按官方 PTP 规格书的"菜单归属"列落码，
    // 不再有 CSM_CODE_UNKNOWN 的待扫描项（扫描按钮会显示"已全部适配"）。

    val groups: List<CsmGroup> = listOf(
        // ---------------------------------------------------------------- a 自动对焦
        CsmGroup("a · 自动对焦", listOf(
            CsmItem(PtpConstants.CSM_A1_AFC_PRIORITY, "a1", "AF-C 优先选择",
                mapOf(0 to "快门释放", 1 to "快门 + 对焦", 2 to "对焦")),
            CsmItem(PtpConstants.CSM_A2_AFS_PRIORITY, "a2", "AF-S 优先选择",
                mapOf(0 to "快门释放", 1 to "对焦")),
            CsmItem(PtpConstants.CSM_A3_GROUP_DYNAMIC_AF, "a3", "动态 AF 区域",
                mapOf(0 to "9 个对焦点", 1 to "21 个对焦点", 2 to "51 个对焦点", 3 to "3D 跟踪")),
            CsmItem(PtpConstants.CSM_A4_LOCKON, "a4", "锁定跟踪对焦",
                mapOf(0 to "长", 1 to "标准", 2 to "短", 3 to "关闭")),
            CsmItem(PtpConstants.CSM_A5_TRIGGER_AF, "a5", "触发自动对焦",
                mapOf(0 to "快门 / AF-ON 按钮", 1 to "仅 AF-ON 按钮")),
            CsmItem(PtpConstants.CSM_A6_AF_POINT_ILLUM, "a6", "AF 点照明",
                mapOf(0 to "自动", 1 to "开启", 2 to "关闭")),
            CsmItem(PtpConstants.CSM_A7_AF_POINT_WRAP, "a7", "对焦点循环方式",
                mapOf(0 to "循环", 1 to "不循环")),
            CsmItem(PtpConstants.CSM_A8_AF_POINT_SELECT, "a8", "AF 点选择",
                mapOf(0 to "51 个对焦点", 1 to "11 个对焦点")),
            // a9 = 0xD163 AFAssist（libgphoto2 明确命名，真机 0x90CA 清单应答）。
            // 原先 candidates 里的 0xD053 是扫描撞车的误候选。
            CsmItem(0xD163, "a9", "内置自动对焦辅助照明灯",
                mapOf(0 to "关闭", 1 to "开启")),
            CsmItem(PtpConstants.CSM_A10_MBD10_AFON, "a10", "MB-D10 的 AF-ON 按钮",
                mapOf(0 to "AF-ON", 1 to "AE/AF 锁定", 2 to "仅锁定自动曝光",
                    3 to "AE 锁定（释放时重设）", 4 to "AE 锁定（保持）",
                    5 to "仅锁定自动对焦", 6 to "与 FUNC. 按钮相同"))
        )),

        // ---------------------------------------------------------------- b 测光 / 曝光
        CsmGroup("b · 测光 / 曝光", listOf(
            CsmItem(PtpConstants.CSM_B1_ISO_STEP, "b1", "ISO 感光度步长值",
                mapOf(0 to "1/3 步长", 1 to "1/2 步长", 2 to "1 步长")),
            CsmItem(PtpConstants.CSM_B2_EV_STEP, "b2", "曝光控制 EV 步长",
                mapOf(0 to "1/3 步长", 1 to "1/2 步长", 2 to "1 步长")),
            CsmItem(PtpConstants.CSM_B3_EV_STEP_COMP, "b3", "曝光补偿 / 微调",
                mapOf(0 to "1/3 步长", 1 to "1/2 步长", 2 to "1 步长")),
            CsmItem(PtpConstants.CSM_B4_EASY_EXPOSURE_COMP, "b4", "简易曝光补偿",
                mapOf(0 to "开启（自动重设）", 1 to "开启", 2 to "关闭")),
            CsmItem(PtpConstants.CSM_B5_CENTER_WEIGHT, "b5", "中央重点区域",
                mapOf(0 to "φ 6 mm", 1 to "φ 8 mm", 2 to "φ 10 mm",
                    3 to "φ 13 mm", 4 to "全画面平均")),
            // b6 在 D300 上是三个子项（矩阵 / 中央重点 / 点测光），各 -1 … +1 EV、1/6 步长共 13 档。
            // libgphoto2 的 ExposureBaseMatrix/Center/Spot 三个名字正好一一对应，可以落码。
            // 档位数值怎么排（0..12 还是 -6..+6）不确定，交给机身值域显示，不硬写档位名。
            CsmItem(PtpConstants.CSM_B6_MATRIX, "b6·1", "微调优化曝光 · 矩阵测光",
                emptyMap(), expectedCount = 13),
            CsmItem(PtpConstants.CSM_B6_CENTER, "b6·2", "微调优化曝光 · 中央重点测光",
                emptyMap(), expectedCount = 13),
            CsmItem(PtpConstants.CSM_B6_SPOT, "b6·3", "微调优化曝光 · 点测光",
                emptyMap(), expectedCount = 13)
        )),

        // ---------------------------------------------------------------- c 计时 / AE 锁定
        CsmGroup("c · 计时 / AE 锁定", listOf(
            CsmItem(PtpConstants.CSM_C1_AEL_BUTTON, "c1", "快门释放按钮 AE-L",
                mapOf(0 to "开启", 1 to "关闭")),
            CsmItem(PtpConstants.CSM_C2_METER_OFF, "c2", "自动测光关闭延迟",
                mapOf(0 to "4 秒", 1 to "6 秒", 2 to "8 秒", 3 to "16 秒", 4 to "30 秒",
                    5 to "1 分钟", 6 to "5 分钟", 7 to "10 分钟", 8 to "30 分钟", 9 to "无限")),
            CsmItem(PtpConstants.CSM_C3_SELF_TIMER, "c3", "自拍延迟",
                mapOf(0 to "2 秒", 1 to "5 秒", 2 to "10 秒", 3 to "20 秒")),
            CsmItem(PtpConstants.CSM_C4_MONITOR_OFF, "c4", "显示屏关闭延迟",
                mapOf(0 to "10 秒", 1 to "20 秒", 2 to "1 分钟", 3 to "5 分钟", 4 to "10 分钟"))
        )),

        // ---------------------------------------------------------------- d 拍摄 / 显示
        CsmGroup("d · 拍摄 / 显示", listOf(
            CsmItem(PtpConstants.CSM_D1_BEEP, "d1", "蜂鸣音",
                mapOf(0 to "增强", 1 to "柔和", 2 to "关闭")),
            // ★ d2/d3/d7/d8/d10/d11 的属性码**不再靠扫描猜**：尼康官方 PTP 规格书
            //   （`_sdk/d300_ptp_official.json`，197 项属性表）直接给了"菜单归属"列，
            //   表里 menu=d2 → 0xD16C、d3 → 0xD181、d7 → 0xD187、d8 → 0xD06F、
            //   d10 → 0xD182、d11 → 0xD18E。
            //   旧值里 d7 借的是 0xD06F（其实是 d8 LCD 照明）、d10 借的是 0xD070
            //   （其实是拍摄菜单的高 ISO 降噪）—— 这两处会**改错机身设置**，已纠正。
            //   ⚠️ 各档的数值顺序仍未经真机核对，界面以机身描述符回的值域为准。
            CsmItem(PtpConstants.CSM_D2_GRID, "d2", "取景器网格显示",
                mapOf(0 to "开启", 1 to "关闭")),
            CsmItem(PtpConstants.CSM_D3_FINDER_WARNING, "d3", "取景器警告显示",
                mapOf(0 to "开启", 1 to "关闭")),
            // 1–7 幅/秒，值到底是 0..6 还是 1..7 不确定，不翻译
            CsmItem(PtpConstants.CSM_D4_CL_SHOOTING_SPEED, "d4", "CL 模式拍摄速度",
                emptyMap(), expectedCount = 7),
            // 1–100 张，值域写法交给机身
            CsmItem(PtpConstants.CSM_D5_MAX_SHOTS, "d5", "最多连拍张数",
                emptyMap(), expectedCount = 100),
            CsmItem(PtpConstants.CSM_D6_FILE_NUMBER_SEQ, "d6", "文件编号次序",
                mapOf(0 to "开启", 1 to "关闭", 2 to "重新设定")),
            CsmItem(PtpConstants.CSM_D7_INFO_DISPLAY, "d7", "拍摄信息显示",
                mapOf(0 to "自动", 1 to "手动 · 光亮时用暗字体", 2 to "手动 · 黑暗时用亮字体")),
            CsmItem(PtpConstants.CSM_D8_LCD_ILLUM, "d8", "LCD 照明",
                mapOf(0 to "开启", 1 to "关闭")),
            CsmItem(PtpConstants.CSM_D9_EXPOSURE_DELAY, "d9", "曝光延迟模式",
                mapOf(0 to "关闭", 1 to "开启")),
            CsmItem(PtpConstants.CSM_D10_BATTERY_TYPE, "d10", "MB-D10 电池类型",
                mapOf(0 to "LR6（AA 碱性）", 1 to "HR6（AA 镍氢）",
                    2 to "FR6（AA 锂）", 3 to "ZR6（AA 镍锰）")),
            CsmItem(PtpConstants.CSM_D11_CELL_ORDER, "d11", "电池顺序",
                mapOf(0 to "先用 MB-D10 中的电池", 1 to "先用相机中的电池"))
        )),

        // ---------------------------------------------------------------- e 包围 / 闪光
        CsmGroup("e · 包围 / 闪光", listOf(
            CsmItem(PtpConstants.CSM_E1_FLASH_SYNC_SPEED, "e1", "闪光灯同步速度",
                mapOf(0 to "1/320 秒（自动 FP）", 1 to "1/250 秒（自动 FP）", 2 to "1/250 秒",
                    3 to "1/200 秒", 4 to "1/160 秒", 5 to "1/125 秒", 6 to "1/100 秒",
                    7 to "1/80 秒", 8 to "1/60 秒")),
            CsmItem(PtpConstants.CSM_E2_FLASH_SHUTTER_SPEED, "e2", "闪光快门速度",
                mapOf(0 to "1/60 秒", 1 to "1/30 秒", 2 to "1/15 秒", 3 to "1/8 秒",
                    4 to "1/4 秒", 5 to "1/2 秒", 6 to "1 秒", 7 to "2 秒", 8 to "4 秒",
                    9 to "8 秒", 10 to "15 秒", 11 to "30 秒")),
            // e3 官方 menu=e3 → 0xD167 InternalFlashMode；旧值 0xD076 不在官方表、真机也不应答
            CsmItem(PtpConstants.CSM_E3_FLASH_CONTROL, "e3", "内置闪光灯闪光控制",
                mapOf(0 to "TTL", 1 to "手动", 2 to "重复闪光", 3 to "指令模式")),
            // e3 切到「手动」后调的就是这一项（官方 0xD16D），档位写法交给机身值域
            CsmItem(PtpConstants.CSM_E3_FLASH_MANUAL, "e3·手动", "手动闪光量",
                emptyMap()),
            CsmItem(PtpConstants.CSM_E4_MODELING_FLASH, "e4", "模拟闪光",
                mapOf(0 to "开启", 1 to "关闭")),
            CsmItem(PtpConstants.CSM_E5_BRACKET_SET, "e5", "自动包围曝光设定",
                mapOf(0 to "自动曝光和闪光灯", 1 to "仅适用自动曝光", 2 to "仅闪光",
                    3 to "白平衡包围")),
            CsmItem(PtpConstants.CSM_E6_MANUAL_BRACKETING, "e6", "自动包围曝光（手动）",
                mapOf(0 to "闪光 / 速度", 1 to "闪光 / 速度 / 光圈", 2 to "闪光 / 光圈",
                    3 to "仅闪光")),
            CsmItem(PtpConstants.CSM_E7_BRACKET_ORDER, "e7", "包围曝光顺序",
                mapOf(0 to "正常 → 不足 → 过度", 1 to "不足 → 正常 → 过度")),
            // ---- e5 的实际参数（机身 BKT 按钮拨的就是这几个值，说明书没有独立菜单项）----
            // 原先散在「拍摄菜单」页里，按机内菜单归属收进 e 组；0xD0C1 真机不支持，
            // 界面上会显示读取失败，留着只作展示。
            CsmItem(0xD0C0, "e5a", "自动包围（BKT）",
                mapOf(0 to "关闭", 1 to "开启")),
            CsmItem(0xD0C1, "e5b", "曝光包围步长",
                mapOf(0 to "1/3 EV", 1 to "1/2 EV", 2 to "2/3 EV", 3 to "1 EV")),
            CsmItem(0xD0C3, "e5c", "曝光包围张数",
                mapOf(0 to "2 张", 1 to "3 张", 2 to "5 张", 3 to "7 张", 4 to "9 张")),
            CsmItem(0xD0C4, "e5d", "白平衡包围步长",
                mapOf(0 to "1 档", 1 to "2 档", 2 to "3 档"))
        )),

        // ---------------------------------------------------------------- f 控制
        CsmGroup("f · 控制", listOf(
            CsmItem(PtpConstants.CSM_F1_CENTER_BUTTON, "f1", "多重选择器中央按钮",
                mapOf(0 to "选择中央对焦点", 1 to "高亮显示当前对焦点", 2 to "不使用")),
            CsmItem(PtpConstants.CSM_F2_MULTI_SELECTOR, "f2", "多重选择器",
                mapOf(0 to "重设测光关闭延迟", 1 to "不回应")),
            CsmItem(PtpConstants.CSM_F3_PHOTO_INFO, "f3", "照片信息 / 播放",
                mapOf(0 to "Info13/PB42", 1 to "Info42/PB13")),
            CsmItem(PtpConstants.CSM_F4_ASSIGN_FUNC_BUTTON, "f4", "指定 FUNC. 按钮",
                mapOf(0 to "预览", 1 to "FV 锁定", 2 to "AE/AF 锁定", 3 to "仅锁定自动曝光",
                    4 to "AE 锁定（释放时重设）", 5 to "AE 锁定（保持）", 6 to "仅锁定自动对焦",
                    7 to "闪光灯关闭", 8 to "曝光包围连续拍摄", 9 to "矩阵测光",
                    10 to "中央重点测光", 11 to "点测光", 12 to "无")),
            // f5 官方 menu=f5 → 0xD189 PreviewButton（可选第二键位 0xD18A）
            CsmItem(PtpConstants.CSM_F5_PREVIEW_BUTTON, "f5", "指定预览按钮",
                mapOf(0 to "预览", 1 to "FV 锁定", 2 to "AE/AF 锁定", 3 to "仅锁定自动曝光",
                    4 to "AE 锁定（释放时重设）", 5 to "AE 锁定（保持）", 6 to "仅锁定自动对焦",
                    7 to "闪光灯关闭", 8 to "曝光包围连续拍摄", 9 to "矩阵测光",
                    10 to "中央重点测光", 11 to "点测光", 12 to "无")),            CsmItem(PtpConstants.CSM_F6_AEL_AFL_BUTTON, "f6", "设定 AE-L/AF-L 按钮",
                mapOf(0 to "AE/AF 锁定", 1 to "仅锁定自动曝光", 2 to "AE 锁定（释放时重设）",
                    3 to "AE 锁定（保持）", 4 to "仅锁定自动对焦", 5 to "AF-ON")),
            CsmItem(PtpConstants.CSM_F7_CUSTOM_DIALS, "f7", "自定义指令拨盘",
                mapOf(0 to "反转方向 · 否", 1 to "反转方向 · 是")),
            // f8 官方 menu=f8 → 0xD089 UniversalMode
            CsmItem(PtpConstants.CSM_F8_DIAL_RELEASE, "f8", "释放按钮以使用拨盘",
                mapOf(0 to "否", 1 to "是")),
            CsmItem(PtpConstants.CSM_F9_NO_CARD, "f9", "无存储卡时？",
                mapOf(0 to "快门释放开启", 1 to "快门释放锁定")),
            // f10 官方 menu=f10 → 0xD18D IndicatorDisplay
            CsmItem(PtpConstants.CSM_F10_INDICATOR, "f10", "反转指示器",
                mapOf(0 to "正常", 1 to "反转"))
        )),

        // ---------------------------------------------------------------- 库
        //
        // 「重设个人设定」**不放在这一组里**：那是个一次性动作（写 1 就恢复出厂），
        // 塞进组里就变成要在「否 / 是」两档之间选 —— 一旦点错没有任何挽回。
        // 它现在是个人设定菜单页的**最后一行**（showCsmSheet），点进去先弹警告页
        // （和格式化存储卡同一套），确认之后才发命令。
        CsmGroup("个人设定库", listOf(
            CsmItem(PtpConstants.CSM_BANK_SELECT, "B", "个人设定库",
                mapOf(0 to "A", 1 to "B", 2 to "C", 3 to "D"))
        ))
    )

    val items: List<CsmItem> = groups.flatMap { it.items }

    /** 属性码还没确定的项 —— 「扫描未适配项」就扫这些 */
    val unresolved: List<CsmItem> = items.filter { !it.verified }
}
