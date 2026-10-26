package com.example.nikond300controller

object PtpConstants {
    // PTP Packet Types
    const val PACKET_TYPE_COMMAND = 1
    const val PACKET_TYPE_DATA = 2
    const val PACKET_TYPE_RESPONSE = 3
    const val PACKET_TYPE_EVENT = 4

    // PTP Operation Codes
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMB = 0x100A
    /**
     * 标准 PTP **DeleteObject = 0x100B**（不是 0x910B！）。
     *
     * ⚠️ 曾经写成 0x910B，于是"点删除 = 软件卡死"：机身没有那个操作码的处理函数，
     * 请求挂住不回，usbLock 一直被占着，后面所有命令排队等待 —— 整个 App 看着像死了。
     * 三条证据都指向标准码：
     * 1. 固件里 tap 的操作调度表（`0x18A028` 起那 19 条标准操作）明明白白有 `100B DeleteObject`，
     *    而厂商段（0x90C0–0x9206）里根本没有 0x910B；
     * 2. libgphoto2 / remoteyourcam-usb 这两套开源实现里都是 `DeleteObject = 0x100B`；
     * 3. 真机 DeviceInfo 的 OperationsSupported 里列的就是 DeleteObject(0x100b)。
     *
     * 参数 = 对象句柄，第二参数（格式码）可省略。**删了相机上就没了，不可恢复。**
     */
    const val OP_DELETE_OBJECT = 0x100B

    /**
     * SendObjectInfo（0x100C）/ SendObject（0x100D）—— **往机身上写文件**。
     *
     * 用法：先 0x100C 带一个 ObjectInfo 数据集（存哪张卡、挂在哪个目录下、文件名、
     * 多大），机身回一个新对象句柄；再 0x100D 把文件字节发过去。
     * 目前只为一件事服务：**NCP 滤镜导入**（把 Picture Control 文件写到卡根目录，
     * 机身再自己「管理优化校准 → 载入/保存 → 复制到相机」装进 C-1…C-9）。
     */
    const val OP_SEND_OBJECT_INFO = 0x100C
    const val OP_SEND_OBJECT = 0x100D

    /** 未定义类型：机身不认识扩展名时就是它，写 .ncp 用这个即可 */
    const val FORMAT_UNDEFINED = 0x3000

    /**
     * GetPartialObject（0x101B）—— 只读对象的一段，不必把整个文件拖回来。
     *
     * NEF 的用处就在这里：一张 15 MB 的 RAW，真正需要的预览 JPEG 只占其中
     * 一两百 KB。有了这个操作码就能「先读头部定位、再只读预览那一段」，
     * 省掉十几 MB 的 USB 传输（实测能差一个数量级的等待时间）。
     *
     * 参数（PTP 规范）：`[ObjectHandle u32][Offset u64][MaxBytes u32]`，
     * Offset 的 64 位用两个连续的 u32 表示（低 32 位在前）。
     * 官方 Type0001.md3 的操作码能力表也把 0x101B 和 0x100A/0x9203 归在同一组
     * 「图像获取」函数里，说明 D300 固件确实有这条路。
     */
    const val OP_GET_PARTIAL_OBJECT = 0x101B

    const val OP_GET_DEVICE_PROP_DESC = 0x1014
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_SET_DEVICE_PROP_VALUE = 0x1016
    const val OP_INITIATE_CAPTURE = 0x100E

    /**
     * FormatStore（0x100F）—— 格式化存储卡，1 个参数（storageId）。
     *
     * 这是个**会丢照片**的操作，界面必须先二次确认再发；机身格式化期间会长时间不应答，
     * 所以等待响应的超时得单独放大（见 [PtpUsbConnection.formatStore]）。
     */
    const val OP_FORMAT_STORE = 0x100F

    // PTP Response Codes
    const val RESP_OK = 0x2001
    const val RESP_DEVICE_BUSY = 0x2002
    const val RESP_SESSION_NOT_OPEN = 0x2003
    const val RESP_INVALID_TRANSACTION_ID = 0x2004
    const val RESP_OPERATION_NOT_SUPPORTED = 0x2005
    const val RESP_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RESP_PARAMETER_READ_ONLY = 0x201C
    const val RESP_SESSION_ALREADY_OPEN = 0x201E
    const val RESP_NIKON_HARDWARE_ERROR = 0x2019 // Focus Not Locked

    // 属性类拒绝码（写 DeviceProp 失败时常见）
    const val RESP_DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val RESP_INVALID_PROP_FORMAT = 0x200B      // 数据类型不符
    const val RESP_INVALID_PROP_VALUE = 0x200C       // 取值不合法
    const val RESP_INVALID_PROP_FORMAT_1 = 0x201B
    const val RESP_INVALID_PROP_VALUE_1 = 0x201C

    /**
     * 机身拒绝该参数/取值。MTP 扩展段（0xA0xx），D300 写 0x5013 取景模式时实测会回这个。
     * 与 0x200C「取值不合法」不同，它表示"这个写法我不接受"，通常指向数据类型或当前状态。
     */
    const val RESP_PARAM_REJECTED = 0xA005

    /**
     * Invalid Status —— 机身当前状态不允许执行这个操作。
     *
     * 这是尼康/微软扩展段（0xA0xx）里最常见的一个。gphoto2 维护者 Marcus Meissner 在
     * 邮件列表里对 D90/D300 的 liveview 报错给出的解释就是
     * `Nikon enable liveview failed (0xa004: Invalid Status)`。
     *
     * 含义要点：它不是协议格式错误（字节层面是对的），而是"机身现在不答应"。
     * 所以重试前要先让机身状态发生变化，光重发同一条命令通常没用。
     */
    const val RESP_INVALID_STATUS = 0xA004

    // Device Property Codes
    /**
     * 电量。**只有 0x5001 是电量**（官方属性表 Setup 页 BatteryLevel）。
     *
     * 注意别再用 0xD1B3 当电量：官方名是 `ExposureIndicateLightup`（曝光指示点亮），
     * 返回的是"曝光刻度是否点亮"那种 0/1 小值，当成百分比会把界面显示成 1%。
     */
    const val PROP_BATTERY_LEVEL = 0x5001
    const val PROP_WHITE_BALANCE = 0x5005
    const val PROP_NIKON_ISO_AUTO = 0xD054
    const val PROP_NIKON_ISO_AUTO_MAX_ISO = 0xD183
    const val PROP_NIKON_ISO_AUTO_MIN_SHUTTER = 0xD164

    /** 曝光指示点亮（官方 ExposureIndicateLightup）。不是电量，别拿它算百分比 */
    const val PROP_NIKON_EXPOSURE_INDICATE = 0xD1B3

    // ---- 机身状态位（诊断用，官方属性表里都在）----
    /** 警告状态（官方 WarningStatus）：位掩码，0 = 没有警告 */
    const val PROP_NIKON_WARNING_STATUS = 0xD102
    /** 取景禁止条件（官方 LiveViewProhibitionCondition）：非 0 = 机身现在不允许开取景 */
    const val PROP_NIKON_LV_PROHIBIT = 0xD1A4
    /** 剩余可拍张数（官方 RemainingExposure） */
    const val PROP_NIKON_REMAINING_EXPOSURE = 0xD103
    const val PROP_NIKON_WB_COLOR_TEMP = 0xD01E
    const val PROP_NIKON_WB_PRESET_NO = 0xD01F
    const val PROP_F_NUMBER = 0x5007
    const val PROP_FOCAL_LENGTH = 0x5008
    const val PROP_EXPOSURE_TIME = 0x500D
    const val PROP_EXPOSURE_PROGRAM_MODE = 0x500E
    const val PROP_EXPOSURE_INDEX = 0x500F // ISO
    const val PROP_EXPOSURE_BIAS_COMPENSATION = 0x5010

    /**
     * DateTime（0x5011）—— **机身时钟**，PTP 标准属性，数据类型是字符串。
     *
     * 格式：`YYYYMMDDThhmmss.s` 后跟时区（`Z` 或 `±hh:mm`），例如
     * `20261024T221800.0+08:00`。尼康机身里那个小电池掉电后时间会归零，
     * EXIF 里的时间戳就全是 2000 年 —— 相册排序、间隔拍摄的时点都会跟着错。
     *
     * ⚠️ 它**不是**整数属性，读写都要走字符串那套（见 [PtpUsbConnection.setDevicePropString] /
     * [PtpUsbConnection.getDevicePropString]），拿 [PtpUsbConnection.getDevicePropValue]
     * 去读只会得到一个无意义的数字。写之前先查描述符的 get/set 位：
     * 部分机身把它报成只读。
     */
    const val PROP_DATE_TIME = 0x5011

    const val PROP_FOCUS_MODE = 0x500A

    /**
     * 0x500A FocusMode 的取值（PTP 标准）：1=手动(MF) / 2=自动(AF) / 3=自动微距。
     * 尼康另有私有值（本机实测 0x8010 = AF-A），都不在"手动"这一档里。
     * 拍摄前临时写成 1，机身在 0x100E 的释放序列里就不会再自动对焦。
     */
    const val FOCUS_MODE_MF = 1
    const val PROP_NIKON_LIVE_VIEW = 0x5013
    const val PROP_NIKON_SHUTTER_SPEED = 0xD100

    // ---------------------------------------------------------------------
    // 曝光与画质：标准 PTP 段（0x50xx）
    //
    // ⚠️ 真机属性 dump（_ref/d300_props.txt）只覆盖了厂商段 0xD000–0xD1FF，
    // 0x50xx 段没有实测记录 —— 所以这三项**不硬编码档位表**，一律先读
    // GetDevicePropDesc(0x1014)，按机身自述的数据类型/值域生成菜单，
    // 写入宽度也跟着描述符走。机身不回描述符时界面显示"机身不支持"，不发写命令。
    // ---------------------------------------------------------------------

    /**
     * ImageSize（0x5003）—— 机身画质/尺寸档。取值语义由厂商定义，
     * 描述符若不枚举就只有区间，此时界面按原始值列档（带十六进制提示）。
     */
    const val PROP_IMAGE_SIZE = 0x5003

    /**
     * ExposureMeteringMode（0x500B）—— 测光模式。
     *
     * 标准 PTP 段里 D300 报的是 0x0002/0x0003/0x0005 这类标准值
     * （中央重点 / 矩阵 / 点测光），详见 [METERING_NAMES]。
     * 注意别和 0x501C（FocusMeteringMode，AF 区域模式）搞混 —— 两者名字很像。
     */
    const val PROP_METERING_MODE = 0x500B

    /**
     * 测光模式的取值名（PTP 标准枚举；机身报别的值就显示原始码，不硬翻）。
     *
     * ⚠️ 按 PTP/EXIF 标准：0x0003 = Spot（点测光）、0x0005 = MultiSegment（矩阵测光）。
     * 以前这张表把 3 和 5 写反了（3→矩阵、5→点测光），和 [PhotoExif] 那边读同一张照片
     * 得出的结果对不上 —— 现在两边统一用标准口径。
     */
    val METERING_NAMES = mapOf(
        0x0001 to "平均测光",
        0x0002 to "中央重点测光",
        0x0003 to "点测光",
        0x0004 to "多点测光",
        0x0005 to "矩阵测光",
        0x0006 to "局部测光",
        0x0007 to "中央重点（宽区）"
    )

    /**
     * FlashExposureCompensation（0xD126）—— 闪光补偿。
     *
     * 真机 dump：`0xD126 INT8 当前=0 值域=区间`（没有"只读"标记 → 可写）。
     * libgphoto2 对尼康闪光补偿按 **1/6 EV** 换算（`ptp.c` 里 flashcomp 的 1.0/6.0 表），
     * 但单位最终以机身描述符的区间/步进为准，界面按 ±EV 显示。
     */
    const val PROP_NIKON_FLASH_COMP = 0xD126

    /**
     * FlashMode（0xD167）—— 内置闪光灯模式。
     *
     * 真机 dump：`0xD167 UINT8 当前=0 值域=0..3`，4 档。
     * D300 机身的闪光模式档位（说明书「闪光模式」一节）：前帘同步 / 防红眼 /
     * 后帘同步 / 关闭 —— 具体哪一档对应哪个数由机身描述符的枚举顺序给出，
     * **不按数字顺序硬套名字**，界面上按描述符枚举逐档标注。
     */
    const val PROP_NIKON_FLASH_MODE = 0xD167

    /**
     * RecordingMedia（记录媒介）。
     *
     * remoteyourcam-usb 的 `NikonOpenSessionAction` 里，开完会话后唯一的动作就是
     * `SetDevicePropValue(NikonRecordingMedia, 1, uint8)` —— 它把这一步放在会话建立阶段，
     * 说明它是尼康机身进入"受 PC 控制"状态的前置条件，而不是某个具体功能才需要的。
     *
     * 官方 Type0001.md3 的 226 项属性表（0x180103582，26 字节/项）里 0xD10B 的条目是
     * `D10B 0000 0001 0000 0000 0000 0002 …`：最小值 1、取值个数 2 —— 合法域就是 {1, 2}，
     * 所以写 1 或 2 都不会触发"取值越界"。
     */
    const val PROP_NIKON_RECORDING_MEDIA = 0xD10B

    /**
     * 0xD10B 记录媒介。
     *
     * 实测能确认的只有一条：**写 1 时照片确实落卡**（DSC_4242→4246 连续编号为证）。
     * libgphoto2 的表写的是 0=Card / 1=SDRam，与实测相反；0 究竟代表什么没有定论，
     * 所以界面上只按"存储卡 / 机身缓存"两档显示，不再推测卡槽编号。
     *
     * 写卡就用 1。
     */
    const val RECORDING_MEDIA_CARD = 1
    const val RECORDING_MEDIA_SDRAM = 0

    /**
     * 当前 AF 对焦点（remoteyourcam-usb 里叫 NikonFocusArea，映射到 "CurrentFocusPoint"）。
     *
     * 官方 226 项属性表（0x180103582，26 字节/项）里这一项的记录是
     * `D108 0000 0000 0000 0000 0000 0033 …`，最小值 0、最大值 **0x33 = 51**
     * —— 正好是 D300 的 51 个对焦点，这条对应关系就是这么确认下来的。
     *
     * 取值是对焦点的 id（不是序号 0..50）。D300 在「11 点」模式下可用的 id 见
     * [com.example.nikond300controller.D300_AF_POINTS_11]。
     */
    const val PROP_NIKON_AF_AREA_POINT = 0xD108

    /**
     * AF 点数（自定义设定 a5）。官方属性表 `D08D … 0000 … 0001 …`，取值域 0..1：
     * - 0 = 51 点全可选
     * - 1 = 只有 11 点可选
     *
     * remoteyourcam-usb 里叫 NikonEnableAfAreaPoint，其代码注释
     * "has more points when EnableAFAreaPoint is 0" 正好印证这个方向。
     */
    const val PROP_NIKON_AF_POINT_COUNT = 0xD08D

    /**
     * AF 区域模式。官方属性表 `D08E … 0000 … 0005 …`，取值域 0..5 —— 与 D300 的
     * AF 区域模式拨盘档数一致（单点 / 动态 9 点 / 动态 21 点 / 动态 51 点 / 3D 跟踪 / 自动区域）。
     *
     * ⚠️ **真机已证伪**：0xD08E 机身不返回描述符（无响应），且 libgphoto2 名表里
     * 0xD08E 是 NormalAFOn。此常量保留只为历史注释，勿再使用。
     *
     * D300 真正的"AF 区域模式"走标准 PTP 属性 [PROP_FOCUS_METERING_MODE]（0x501C）。
     */
    const val PROP_NIKON_AF_AREA_MODE = 0xD08E

    /**
     * AF 区域模式（libgphoto2 名 FocusMeteringMode，标准 PTP 段）。
     *
     * D300 时代的尼康取值（config.c focus_metering 表）：
     * - 0x8010 = 单点 AF（Single Area）
     * - 0x8011 = 最近主体优先（Closest Subject）
     * - 0x8012 = 组动态 AF（Group Dynamic / 3D）
     * - 0x8013 = 动态区域 21 点（较新机型）
     * - 0x8014 = 动态区域 51 点（较新机型）
     * D300 支持哪几档以机身描述符为准，界面上对不上就显示原始值。
     */
    const val PROP_FOCUS_METERING_MODE = 0x501C

    /**
     * 实时取景模式：手持（Handheld）/ 三脚架（Tripod）。
     *
     * D300 的取景有两种工作模式，机身菜单里叫「手持模式」和「三脚架模式」，
     * 两者的对焦机构完全不同，这也解释了为什么 0x9205 在手持模式下"命令成功但框不动"：
     * - **手持模式**：反光板周期放下，用取景器那套 **51 点相位检测** AF。
     *   对焦点只能在固定的 51 个位置之间跳 → 只能走 [PROP_NIKON_AF_AREA_POINT] 写点 id。
     * - **三脚架模式**：全程抬起反光板、用传感器做**对比度检测** AF，
     *   AF 框可以在画面里任意位置移动 → 这时 0x9205 ChangeAfArea 才真正有意义。
     *
     * 候选码来自官方 226 项属性表（0x180103582）：手持/三脚架是**两档**，
     * 值域 0..1 的 `0xD1A0` 与 `0xD1A2` 是首选；`0xD1A3`(0..6) 更像取景图像尺寸。
     * 这几个码的属性类型/值域需要时可用 PtpUsbConnection.probeLiveViewModeProperties() 探测
     * （日志会打出类型/当前值/值域）；界面上已不再挂入口。
     */
    const val PROP_NIKON_LIVE_VIEW_MODE = 0xD1A0

    /**
     * 0xD104 AFLockStatus（只读 UINT8，1 = 已锁焦）。
     *
     * 这是判断"到底合没合焦"最直接的属性 —— 机身响提示音、取景框变绿时它就是 1。
     * 0x90C8 DeviceReady 只表示"机身空闲"，两者不完全等价：
     * 三脚架模式用对比度 AF 时，机身先锁焦、再回 DeviceReady，
     * 中间那段时间光看 DeviceReady 会误判成"未合焦"。
     */
    const val PROP_NIKON_AF_LOCK_STATUS = 0xD104

    /** 实时取景图像尺寸/缩放相关，值域 0..6（官方表）。仅用于探测对照。 */
    const val PROP_NIKON_LIVE_VIEW_SIZE = 0xD1A3

    /**
     * 0xD1A3 = libgphoto2 的 `PTP_DPC_NIKON_LiveViewImageZoomRatio` —— **取景画面放大倍率**。
     *
     * 手动对焦时把画面放大看细节用的就是这个：0 = 全画面，往上逐级放大。
     * 具体档位各机型不同（D300 系常用 0/2/4/6/7 → 全画面/25%/50%/100%/200%），
     * 所以一律**读描述符取机身自述的合法值**再步进，不硬编。
     */
    const val PROP_NIKON_LV_ZOOM_RATIO = 0xD1A3

    /** 实时取景模式备用候选，值域 0..1（官方表）。仅用于探测对照。 */
    const val PROP_NIKON_LIVE_VIEW_MODE_ALT = 0xD1A2

    /** 实时取景图像缩放率候选，值域 0..2（官方表）。仅用于探测对照。 */
    const val PROP_NIKON_LIVE_VIEW_ZOOM = 0xD1A1

    /** 手持模式 = 0（相位检测 51 点），三脚架模式 = 1（对比度检测，AF 框自由移动）。 */
    const val LIVE_VIEW_MODE_HANDHELD = 0
    const val LIVE_VIEW_MODE_TRIPOD = 1

    // ---------------------------------------------------------------------
    // 个人设定菜单（Custom Settings Menu，简称 CSM）
    //
    // 属性码以 libgphoto2 的尼康属性名表（camlibs/ptp2/ptp.h，453 条）为准逐条核对过，
    // 不是照抄网上流传的编号 —— 那些编号常把 Canon 段的 0xD04x 混进来，
    // 也常把 a5 触发自动对焦与 a7 对焦点循环方式的码写反。
    // 每一项在代码末尾都标注了 libgphoto2 里的原始符号名，方便日后复查。
    // ---------------------------------------------------------------------

    // 编号（a1/b2/d9…）一律以 D300 说明书「个人设定」章节（中文版书页 265–310）为准。
    // libgphoto2 用的是 D2X/D200 时代的老编号，从 a4 起比 D300 少 1，别照它的名字编号。

    const val CSM_BANK_SELECT = 0xD040          // CSMMenuBankSelect（个人设定库 A/B/C/D）
    const val CSM_RESET_BANK = 0xD045           // ResetBank（重设个人设定）

    const val CSM_A1_AFC_PRIORITY = 0xD048      // A1AFCModePriority
    const val CSM_A2_AFS_PRIORITY = 0xD049      // A2AFSModePriority
    const val CSM_A3_GROUP_DYNAMIC_AF = 0xD184  // DynamicAFArea（libgphoto2 命名 + 真机 0x90CA 清单应答；0xD04A 不应答已弃用）
    const val CSM_A4_LOCKON = 0xD051            // AFLockOn（锁定跟踪对焦，真机 4 档 0..3，当前=3=关闭 吻合）
    const val CSM_A5_TRIGGER_AF = 0xD04B        // A4AFActivation（机身上的 a5 触发自动对焦）
    const val CSM_A6_AF_POINT_ILLUM = 0xD166    // FocusAreaLED（官方 menu=a6；旧值 0xD04E 不在官方表）
    const val CSM_A7_AF_POINT_WRAP = 0xD04F     // FocusAreaWrap
    const val CSM_A8_AF_POINT_SELECT = 0xD08D   // AFAreaPoint（0=51 点 / 1=11 点，真机确认）
    const val CSM_A10_MBD10_AFON = 0xD050       // VerticalAFON

    const val CSM_B1_ISO_STEP = 0xD055          // EVISOStep
    const val CSM_B2_EV_STEP = 0xD056           // EVStep
    const val CSM_B3_EV_STEP_COMP = 0xD057      // EVStepExposureComp
    const val CSM_B4_EASY_EXPOSURE_COMP = 0xD058 // ExposureCompensation
    const val CSM_B5_CENTER_WEIGHT = 0xD059     // CenterWeightArea
    // b6 微调优化曝光在 D300 上是三个子项，libgphoto2 恰好给了三个一一对应的名字
    const val CSM_B6_MATRIX = 0xD05A            // ExposureBaseMatrix（矩阵测光微调）
    const val CSM_B6_CENTER = 0xD05B            // ExposureBaseCenter（中央重点测光微调）
    const val CSM_B6_SPOT = 0xD05C              // ExposureBaseSpot（点测光微调）

    const val CSM_C1_AEL_BUTTON = 0xD05E        // AELockMode
    const val CSM_F6_AEL_AFL_BUTTON = 0xD05F    // AELAFLMode（f6 设定 AE-L/AF-L 按钮）
    const val CSM_C2_METER_OFF = 0xD062         // MeterOff
    const val CSM_C3_SELF_TIMER = 0xD063        // SelfTimer
    const val CSM_C4_MONITOR_OFF = 0xD064       // MonitorOff

    const val CSM_D1_BEEP = 0xD160              // BeepOff
    // ---- 以下 d2/d3/d7/d8/d10/d11 的属性码来自**官方 PTP 规格书属性表**（197 项那版，
    // _sdk/d300_ptp_official.json），表里直接给了"菜单归属"列，不再需要靠扫描猜。
    const val CSM_D2_GRID = 0xD16C              // GridDisplay（官方 menu=d2 取景器网格显示）
    const val CSM_D3_FINDER_WARNING = 0xD181    // FinderWarningDisplay（官方 menu=d3）
    const val CSM_D4_CL_SHOOTING_SPEED = 0xD068 // D1ShootingSpeed（老编号 d1，D300 是 d4）
    const val CSM_D5_MAX_SHOTS = 0xD069         // D2MaximumShots（紧挨着 d4，D300 是 d5）
    const val CSM_D6_FILE_NUMBER_SEQ = 0xD06C   // FileNumberSequence
    // ⚠️ d7 是「拍摄信息显示」= 0xD187；0xD06F 官方归属是 **d8 LCD 照明**，早先错放在 d7
    const val CSM_D7_INFO_DISPLAY = 0xD187      // InformationScreenDisplaySetting（官方 menu=d7）
    const val CSM_D8_LCD_ILLUM = 0xD06F         // LCDIllumination（官方 menu=d8）
    // ⚠️ d10「MB-D10 电池类型」= 0xD182 CellKindSetting；0xD070 官方是 NoiseReductionHiIso
    //    （高 ISO 降噪，属于拍摄菜单，ShootingMenu 里用对了，CSM 里曾错当成电池类型）
    const val CSM_D10_BATTERY_TYPE = 0xD182     // CellKindSetting（官方 menu=d10）
    const val CSM_D11_CELL_ORDER = 0xD18E       // CellKindPrioritylevel（官方 menu=d11 电池顺序）
    const val CSM_D9_EXPOSURE_DELAY = 0xD06A    // ExposureDelayMode（说明书是 d9，不是 d8）

    const val CSM_E1_FLASH_SYNC_SPEED = 0xD074  // FlashSyncSpeed
    const val CSM_E2_FLASH_SHUTTER_SPEED = 0xD075 // FlashShutterSpeed
    // ⚠️ e3 内置闪光灯闪光控制 = 0xD167；旧值 0xD076 不在官方表里、真机也不应答
    const val CSM_E3_FLASH_CONTROL = 0xD167     // InternalFlashMode（官方 menu=e3）
    const val CSM_E3_FLASH_MANUAL = 0xD16D      // InternalFlashManual（e3·手动 的闪光量）
    const val CSM_E4_MODELING_FLASH = 0xD077    // E4ModelingFlash
    const val CSM_E5_BRACKET_SET = 0xD078       // BracketSet
    const val CSM_E6_MANUAL_BRACKETING = 0xD079 // E6ManualModeBracketing
    const val CSM_E7_BRACKET_ORDER = 0xD07A     // BracketOrder

    const val CSM_F1_CENTER_BUTTON = 0xD080     // F1CenterButtonShootingMode
    const val CSM_F2_MULTI_SELECTOR = 0xD082    // F2Multiselector
    const val CSM_F3_PHOTO_INFO = 0xD083        // F3PhotoInfoPlayback
    const val CSM_F4_ASSIGN_FUNC_BUTTON = 0xD084 // F4AssignFuncButton
    const val CSM_F5_PREVIEW_BUTTON = 0xD189    // PreviewButton（官方 menu=f5）
    const val CSM_F5_PREVIEW_BUTTON2 = 0xD18A   // PreviewButton2（f5 的第二个键位）
    const val CSM_F7_CUSTOM_DIALS = 0xD085      // F5CustomizeCommDials（老编号 f5，D300 是 f7）
    const val CSM_F8_DIAL_RELEASE = 0xD089      // UniversalMode（官方 menu=f8 释放按钮以使用拨盘）
    const val CSM_F10_INDICATOR = 0xD18D        // IndicatorDisplay（官方 menu=f10 反转指示器）
    const val CSM_F9_NO_CARD = 0xD08A           // NoCFCard（无存储卡时？）

    /** 属性码未适配的标记：这类项只能靠扫描匹配，绝不硬猜一个码写进机身。 */
    const val CSM_CODE_UNKNOWN = 0

    // NIKON specific codes
    const val OP_NIKON_INITIATE_CAPTURE = 0x90C0
    const val OP_NIKON_AF_DRIVE = 0x90C1

    /**
     * Nikon SetControlMode —— 切换机身的工作/控制模式（libgphoto2 中的 `PTP_OC_NIKON_SetControlMode`）。
     *
     * 在官方 Type0001.md3 的操作码能力表（文件偏移 0x1032d0，每条 8 字节：
     * `[opCode u16][参数个数 u16][数据阶段 u32]`）里，它的记录是 `90C2 0001 00000000`，
     * 即**带 1 个参数、无数据阶段**。
     *
     * 它不在 MAID 命令分派表里，说明由连接/模式切换的独立路径调用。
     * D300 回 0xA004(Invalid Status) 拒绝 0x9201 时，很可能就是机身还没切进
     * PC 控制模式 —— 这条命令正是用来做那次切换的。
     */
    const val OP_NIKON_SET_CONTROL_MODE = 0x90C2
    const val OP_NIKON_START_LIVE_VIEW = 0x9201
    const val OP_NIKON_END_LIVE_VIEW = 0x9202
    const val OP_NIKON_GET_LIVE_VIEW_IMAGE = 0x9203

    /**
     * 0x9204 NikonMfDrive —— 手动驱动镜头对焦（LiveView 下微调对焦）。
     *
     * D300 固件调度表里有它的处理函数（`0x18A008 → 0xB6CE`），是真实可用的，
     * 不像 0x9207 那样"只在超时表登记过"。
     * 参数（libgphoto2 `ptp_nikon_mfdrive`）：flag + 步数。
     */
    const val OP_NIKON_MF_DRIVE = 0x9204

    /** flag=1：往"最近"方向驱动 */
    const val MF_DRIVE_TOWARD_CLOSEST = 0x1
    /** flag=2：往"无限远"方向驱动 */
    const val MF_DRIVE_TOWARD_INFINITY = 0x2

    /** 0xA00C：镜头已到行程尽头（对焦环拧到底了） */
    const val RESP_NIKON_MF_STEP_END = 0xA00C
    /** 0xA00E：这一步走不动（步数太大 / 镜头限位） */
    const val RESP_NIKON_MF_STEP_INSUFFICIENCY = 0xA00E
    /**
     * 0x90C3 NikonDeleteImagesInSdram —— 清空机身内存里那些"拍了但没落卡"的照片。
     *
     * 它是 [OP_NIKON_INITIATE_CAPTURE] 的收尾搭档：RecInSdram 拍完照片留在机身 RAM，
     * 由客户端取走（或不要了）之后再来这一条把那份空间还回去，
     * 否则连按几次机身内存就会被占满。
     * 参数 1 个（官方 MAID case 7），按上游口径传 1 = 全部删除。
     *
     * 注：随「曝光预览」功能一起下线的调用点已删除，常量与这段协议说明保留作参考。
     */
    const val OP_NIKON_DELETE_IMAGES_IN_SDRAM = 0x90C3

    /**
     * 0x9200 NikonGetPreviewImage —— 直接向机身要"现在缓存里的那张预览图"。
     *
     * 三个参数（`0x9200 | 3 | 数据阶段=1`）是**官方**的用法：
     * 0x90C0 拍进 SDRAM 之后由它取回，而不是等到对象出现在卡上。
     * （随「曝光预览」功能一起下线的调用点已删除，常量与这段协议说明保留作参考。）
     * ⚠️ 固件对该命令强制校验缓冲区尺寸，只接受两种：
     * - `0x46600` = 288,256 字节（大预览，按 0x20000 分块读）
     * - `0x8000`  = 32,768  字节（小预览）
     * 尺寸不符直接回错（见 `FUN_1800a7490` 的反编译）。
     */
    const val OP_NIKON_GET_PREVIEW_IMAGE = 0x9200

    /** 0x9200 大预览图的缓冲区尺寸（固件强制值，改不得） */
    const val PREVIEW_IMAGE_BUF_LARGE = 0x46600
    /** 0x9200 小预览图的缓冲区尺寸（固件强制值） */
    const val PREVIEW_IMAGE_BUF_SMALL = 0x8000

    /**
     * 0x90C4 NikonGetLargeThumb —— 大缩略图。
     * SDRAM 里的对象给不出标准 0x100A 缩略图时，这条是另一条备选回读路径。
     */
    const val OP_NIKON_GET_LARGE_THUMB = 0x90C4

    const val OP_NIKON_GET_EVENT = 0x90C7
    const val OP_NIKON_DEVICE_READY = 0x90C8
    /** 取厂商属性码清单（remoteyourcam 在开会话时会先取这个） */
    const val OP_NIKON_GET_VENDOR_PROP_CODES = 0x90CA
    const val OP_NIKON_AF_AND_CAPTURE = 0x9207

    /**
     * **取消**正在进行的 AF 驱动（官方名 `AfDriveCancel`，见规格书 5.2 操作码表）。
     *
     * ⚠️ 别把它当"AF 之后的收尾命令"：它的语义是**中止**对焦动作。
     * 对焦已经合上之后再发它，等于把刚对好的焦取消掉。
     * 正确用法只有两处：用户中途点「停止」、以及 DeviceReady 超时收尾。
     */
    const val OP_NIKON_AF_DRIVE_CANCEL = 0x9206

    /**
     * ChangeAfArea —— 取景时把 AF 区域框（对比度 AF 框）挪到画面上的某一点。
     *
     * 官方 Type0001.md3 的操作码能力表（8 字节/条：`[opCode u16][参数个数 u16][数据阶段 u32]`）
     * 里它的记录是 `9205 0002 00000000`：**2 个参数、无数据阶段**。
     *
     * 两个参数是整幅画面（whole image）的**像素坐标**，不是归一化值、也不是 640×480 的 JPEG 坐标。
     * remoteyourcam-usb 的 `NikonCamera.setLiveViewAfArea()` 就是先 `posx * wholeWidth`
     * 再按 AF 框半宽 clamp 到 `[afW/2, wholeW - afW/2]` 区间后取整发出的。
     *
     * D700（与 D300 同 productId）的官方 DeviceInfo 里明确列了 NikonChangeAfArea(0x9205)。
     */
    const val OP_NIKON_CHANGE_AF_AREA = 0x9205

    // PTP 对象格式码（ObjectFormatCode，用于判断能不能直接解码显示）
    const val OBJ_FORMAT_JPEG = 0x3801      // EXIF/JPEG，Android 可直接解码
    const val OBJ_FORMAT_TIFF_EP = 0x3802   // TIFF/EP —— D300 的 NEF 会报这个
    const val OBJ_FORMAT_NEF = 0xB101       // 尼康私有 RAW 标识
    const val OBJ_FORMAT_UNDEFINED = 0x3000 // 未定义 —— 实测 D300 的 NEF 也报这个码
    const val OBJ_FORMAT_ASSOCIATION = 0x3001 // 目录（相册）

    // USB 标识（尼康 VID 0x04B0 = 1200）
    const val USB_VENDOR_NIKON = 1200
    const val USB_PID_D300_PTP = 0x041A    // MTP/PTP 模式（Imaging / Still Image / PTP）
    const val USB_PID_D300_MSC = 0x0419    // 大容量存储模式（Mass Storage / SCSI）
}