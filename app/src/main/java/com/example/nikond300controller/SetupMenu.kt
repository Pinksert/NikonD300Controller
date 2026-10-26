package com.example.nikond300controller

/**
 * 设置菜单（Setup Menu）里能用 PTP 读写的那批。
 *
 * 与 [ShootingMenu] 同样是「属性码 + 中文名 + 档位翻译表」，点击后走同一个
 * `showEnumPropSheet`（读描述符 → 按机身自述的合法值出档 → 写 → 回读校验）。
 *
 * ⚠️ 三条自己定下的规矩：
 *
 * 1. **AF 微调这一组是按镜头注册的**。顺序是：先看"已注册数量"和"当前镜头序号"，
 *    再决定要写"已注册镜头的微调"还是"未注册镜头的默认微调"。乱写会真的改变对焦精度，
 *    所以每项都写了用途说明，界面上不合并成一格。
 * 2. **版权注释是字符串属性**（`0xD090`），数值型写入会失败。这里只放它的开关
 *    （`0xD091`），正文靠机身菜单或后面的文本编辑页改。
 * 3. 档位名只写"确定知道语义"的；其余交给机身值域，界面会显示原始值 ——
 *    宁可看到 `值 7`，也不硬编一个可能错的中文名。
 */
/** 复用拍摄菜单的条目结构，省得两套完全一样的 data class */
private typealias SetupItem = ShootingMenu.Item

object SetupMenu {

    val groups: List<Pair<String, List<SetupItem>>> = listOf(

        "AF 微调" to listOf(
            SetupItem(
                PtpConstants.PROP_NIKON_AF_TUNE_ENABLE, "AF 微调",
                hint = "总开关。关掉之后所有镜头的微调值都不生效（但不会丢）",
                options = mapOf(0L to "关闭", 1L to "开启")
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_AF_TUNE_UNREG_LENS, "未注册镜头的微调",
                hint = "对所有没登记过的镜头统一生效；0 = 不微调"
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_AF_TUNE_REG_LENS, "已注册镜头的微调",
                hint = "只对当前选中的那条注册记录生效 —— 先看下面「正在编辑的序号」"
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_AF_TUNE_LENS_INDEX, "当前镜头序号",
                hint = "机身把当前镜头对到表里的第几条；-1 一类值表示没匹配上"
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_AF_TUNE_LENS_NO, "镜头号",
                hint = "这条注册记录对应的镜头编号。机身只给编号，不给型号名 —— " +
                    "型号对照见「机身信息」页（那里有镜头标识 + 按焦距/光圈推断的规格）"
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_AF_TUNE_INDEX_NUM, "已注册数量",
                hint = "表里一共登记了几个镜头（D300 最多 12 条）"
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_AF_TUNE_INDEX_NOW, "正在编辑的序号",
                hint = "改这一项 = 换一条记录去编辑；改完再回上面调微调量"
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_AF_TUNE_LENS_DELETE, "删除这条注册",
                hint = "写 1 会删掉当前序号那条记录 —— 只是删微调记录，不动机身别的东西"
            )
        ),

        "拍摄信息" to listOf(
            SetupItem(
                PtpConstants.PROP_NIKON_COMMENT_ENABLE, "附加版权信息",
                hint = "开着才会把版权注释写进照片；注释正文是字符串属性，要用编辑页改",
                options = mapOf(0L to "关闭", 1L to "开启")
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_ORIENTATION_SENSOR, "方向传感器",
                hint = "自动旋转相关",
                options = mapOf(0L to "关闭", 1L to "开启")
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_MANUAL_LENS_NO, "非 CPU 镜头焦距",
                hint = "手动镜头登记用的焦距档（配合「非 CPU 镜头数据」）"
            )
        ),

        "曝光显示与锁定" to listOf(
            SetupItem(
                PtpConstants.PROP_NIKON_FLEXIBLE_PROGRAM, "柔性程序",
                hint = "P 挡下程序线的偏移量，0 = 不偏移；正负方向由机身定义"
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_FNUMBER_LOCK, "光圈锁定",
                hint = "锁定后拨盘改不动光圈",
                options = mapOf(0L to "解锁", 1L to "锁定")
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_SHUTTER_SPEED_LOCK, "快门速度锁定",
                hint = "锁定后拨盘改不动快门",
                options = mapOf(0L to "解锁", 1L to "锁定")
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_AE_LOCK_STATUS, "AE-L 状态",
                hint = "只读：1 = 自动曝光已锁定"
            ),
            SetupItem(
                PtpConstants.PROP_NIKON_FV_LOCK_STATUS, "FV-L 状态",
                hint = "只读：1 = 闪光曝光已锁定"
            )
        )
    )

    val items: List<SetupItem> = groups.flatMap { it.second }

    fun label(item: SetupItem, v: Long): String = ShootingMenu.label(item, v)
}
