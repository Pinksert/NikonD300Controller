package com.example.nikond300controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/** 一次 LiveView 取帧的结果 */
class LiveViewFrame(
    val bitmap: Bitmap?,
    /** 相机忙，稍后重试 */
    val busy: Boolean = false,
    /** AF 对焦框中心（归一化 0..1，相对取景画面），无数据时为 -1 */
    val afCenterX: Float = -1f,
    val afCenterY: Float = -1f,
    /** AF 框宽高（归一化） */
    val afWidth: Float = 0f,
    val afHeight: Float = 0f,
    /**
     * 整幅画面的像素尺寸（机身原始取景分辨率，D300 约 4288×2848 的取景裁切）。
     *
     * 这两个值不是给 UI 用的 —— 0x9205 ChangeAfArea 的坐标参数就在整幅画面坐标系里，
     * 而界面上拿到的是 640×480 的 JPEG。要把"用户点了屏幕哪里"翻译成机身坐标，
     * 必须先有 whole 尺寸，否则算出来的坐标会偏出画面，机身直接拒。
     */
    val wholeWidth: Int = 0,
    val wholeHeight: Int = 0,
    /** AF 框尺寸（整幅画面像素），用于把用户点的位置 clamp 进可达范围 */
    val afFrameWidth: Int = 0,
    val afFrameHeight: Int = 0
)

/**
 * 快门类命令（0x100E / 0x90C0 / 0x9207）等回应的超时。
 *
 * 挑 1.5 秒的理由：机身收到快门命令通常几十毫秒就回，真正慢的是之后的写卡
 * （那段由调用方单独等，不该算在这里）。超过 1.5 秒没回基本就是数据阶段出错的病态状态，
 * 要赶紧放开 usbLock 让重试逻辑接管 —— 锁久了读帧、退出取景全堵住，App 看起来就是"卡死"。
 */
private const val CAPTURE_RESP_TIMEOUT_MS = 1500

/**
 * 删除对象（0x100B）的等回应超时。
 *
 * 比快门给得宽：删一张可能要擦一块 NAND，卡慢的时候几百毫秒不稀奇。
 * 但也不能无限等 —— 这条命令是"能不能用"都成疑的那种（见 [supportsOperation]），
 * 一旦机身不支持，它连失败回应都不给，锁着不放就会把整条 USB 通道拖死。
 */
private const val DELETE_RESP_TIMEOUT_MS = 2500

/**
 * 开启实时取景的结果。
 * 之前只返回 Boolean，界面上只能显示"失败"两个字，用户（和我们）都看不出卡在哪一步。
 * 带上 reason 后，任何失败都能直接显示给用户看。
 */
class LiveViewStartResult(val ok: Boolean, val reason: String) {
    companion object {
        fun ok() = LiveViewStartResult(true, "")
        fun fail(reason: String) = LiveViewStartResult(false, reason)
    }
}

/** PTP 事件（NikonGetEvent 返回） */
class PtpEvent(val code: Int, val param: Int) {
    companion object {
        const val OBJECT_ADDED = 0x4002
        const val PROP_CHANGED = 0x4006
        const val CAPTURE_COMPLETE = 0x400D
        /**
         * 机身内存里那张照片拍完了（RecInSdram 专用）。
         *
         * libgphoto2 里叫 PTP_EC_NIKON_CaptureCompleteRecInSdram，param 就是那张图的**对象句柄** ——
         * 它只在机身内存中存在，**不会进存储卡的对象列表**，所以列举卡是找不到它的。
         */
        const val CAPTURE_COMPLETE_REC_IN_SDRAM = 0xC102

        /**
         * SDRAM 里多出来一个对象（`PTP_EC_NIKON_ObjectAddedInSdram`）。
         * param = 那个对象的句柄 —— 它只活在机身内存里，卡的对象列表里找不到。
         */
        const val OBJECT_ADDED_IN_SDRAM = 0xC101

        /**
         * 机身准备好一张预览图了（`PTP_EC_NIKON_PreviewImageAdded`）。
         * 收到它之后走 0x9200 GetPreviewImage 就能把那张图拉下来。
         */
        const val PREVIEW_IMAGE_ADDED = 0xC104
    }
}

/**
 * 卡里的一个"相册" = 一个装着照片的文件夹。
 *
 * 尼康的目录结构是 `DCIM/100NCD300`，号段满了就建 `101NCD300`，
 * 所以"号最大 = 最新"这条经验在文件夹上同样成立（排序在相册页做，这里只管带回来）。
 *
 * [storageId] / [handle] 是切换/下钻用的坐标；[name] 是文件夹名（顶层照片为 ""）。
 */
data class PhotoFolder(
    val storageId: Int,
    val handle: Int,
    val name: String,
    val parentName: String,
    val photos: List<PtpObjectInfo>
)

/** 单个对象的信息（来自 0x1008 GetObjectInfo） */
/**
 * 一个属性在机身侧的自述：类型、能不能写、现在几、能取哪些值。
 *
 * 个人设定菜单的落地全靠它 —— 属性码和菜单名的对应拿不到静态证据，
 * 就只能先问机身"你能取哪些值"，再用选项个数去对上机身菜单的档位。
 */
data class PropDesc(
    val code: Int,
    val dataType: Int,
    val writable: Boolean,
    val factoryValue: Long,
    val currentValue: Long,
    /** 0x00=无约束，0x01=区间[min,max,step]，0x02=枚举列表 */
    val formFlag: Int,
    val allowed: List<Long>
) {
    val typeName: String get() = when (dataType) {
        0x0001 -> "INT8"; 0x0002 -> "UINT8"; 0x0003 -> "INT16"; 0x0004 -> "UINT16"
        0x0005 -> "INT32"; 0x0006 -> "UINT32"; else -> "0x%04X".format(dataType)
    }
    /** 值域描述，给界面当副标题用 */
    val rangeText: String get() = when (formFlag) {
        0x01 -> if (allowed.size >= 2) "${allowed.first()}..${allowed.last()}" else "区间"
        0x02 -> if (allowed.isEmpty()) "枚举(空)" else allowed.joinToString("/")
        else -> "不限"
    }
}

class PtpObjectInfo(
    val handle: Int,
    val filename: String,
    val byteSize: Long,
    val format: Int,
    val captureDate: String,
    val thumbFormat: Int,
    val pixWidth: Int,
    val pixHeight: Int
) {
    /** Android 能直接解码 */
    val isJpeg: Boolean get() = format == PtpConstants.OBJ_FORMAT_JPEG
    /**
     * D300 的 NEF 报 TIFF/EP 或尼康私有码，Android 解不了，只能存原文件。
     * 真机实测 D300 的 NEF 大多报 **0x3000 UNDEFINED**，也是 RAW。
     */
    val isRaw: Boolean get() =
        format == PtpConstants.OBJ_FORMAT_TIFF_EP || format == PtpConstants.OBJ_FORMAT_NEF ||
            format == PtpConstants.OBJ_FORMAT_UNDEFINED

    val formatLabel: String get() = when {
        isJpeg -> "JPEG"
        isRaw -> "NEF"
        else -> "0x${Integer.toHexString(format).uppercase()}"
    }
}

/**
 * GetDeviceInfo（0x1001）的完整结果。
 *
 * 之前只取"厂商 + 型号"拼登陆状态条，可机身上真正有用的是**固件版本**和**序列号** ——
 * 排查"是不是同一个机身"、对保修/固件升级都要这两条。PTP 的 DeviceInfo 里
 * DeviceVersion / SerialNumber 就在 Model 后面两个字符串，顺手一起读回来。
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val version: String,
    val serial: String,
    /**
     * 机身自报支持的操作码集合（GetDeviceInfo 的 OperationsSupported）。
     *
     * 要它只是为了**别硬发机身不支持的命令**：某些命令发下去机身不回任何东西，
     * 而 usbLock 一直被占着，整条 USB 通道跟着锁死（0x9207 那次就是这么栽的）。
     * 删除照片之前先问它，比事后扫地省心。
     */
    val operations: Set<Int> = emptySet()
) {
    /** 登录状态条那一行用：去掉 "NIKON Corporation" 里多余的 Corporation */
    val shortName: String get() = "${manufacturer.replace(" Corporation", "")} $model".trim()

    /** 空值统一显示成 "--"，界面不用每处都判空 */
    fun versionOrDash() = version.ifBlank { "--" }
    fun serialOrDash() = serial.ifBlank { "--" }
}

/**
 * GetStorageInfo（0x1005）—— 单张卡的类型/容量/剩余。
 *
 * D300 双卡槽时每张卡各来一次。容量单位是**字节**（u64），
 * 界面按 GB 显示；freeImages 是机身自己估的"还能拍多少张"，
 * 会随当前画质设置变化，比我们自己算的可信。
 */
data class StorageInfo(
    val storageId: Int,
    val storageType: Int,
    val accessCapability: Int,
    val maxCapacity: Long,
    val freeSpace: Long,
    val freeImages: Int,
    val description: String,
    val volumeLabel: String
) {
    /** 只读卡（accessCapability 0x0001 = 只读，0x0000/0x0002 可写）—— 格式化前要挡一下 */
    val readOnly: Boolean get() = accessCapability == 0x0001

    val capacityGb: Double get() = maxCapacity / 1024.0 / 1024.0 / 1024.0
    val freeGb: Double get() = freeSpace / 1024.0 / 1024.0 / 1024.0
    val usedGb: Double get() = (maxCapacity - freeSpace) / 1024.0 / 1024.0 / 1024.0

    val displayName: String
        get() = when {
            // 机身报的卷标通常就是一串编号（如 019004），别把它和"（存储卡）"拼在一起
            volumeLabel.isNotBlank() -> volumeLabel
            description.isNotBlank() -> description
            else -> "存储卡 0x${Integer.toHexString(storageId).uppercase()}"
        }
}

class PtpUsbConnection(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val logger: (String) -> Unit
) {

    companion object {
        /**
         * 当前活跃的连接。USB 设备只能被打开一次，而且 UsbDeviceConnection 没法跨 Activity 序列化，
         * 所以相册界面靠这个进程内单例复用同一条连接。断开连接时必须置空。
         */
        @Volatile
        var activeInstance: PtpUsbConnection? = null
    }

    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var interruptEndpoint: UsbEndpoint? = null

    private var transactionId = 0
    private var lastResponse: PtpResponse? = null

    /**
     * 是否在 AF 驱动后补发 0x9206（官方 MAID→PTP 翻译表 case 3 的第三步）。
     * 该操作码的公开语义尚未在 libgphoto2 等资料中确认，默认关闭；
     * 真机实测确认无害后置 true 即可。
     */
    var sendAfDriveFollowUp: Boolean = false

    /**
     * 最近一次 SetDevicePropValue 的失败响应码（0 = 无失败）。
     * 用来给界面生成人能看懂的失败原因，而不是干巴巴一句"失败"。
     */
    var lastSetPropError: Int = 0
        private set

    /**
     * 「一键停止」的中止信号。界面上的停止按钮把它置 true 之后，
     * 所有还在轮询/等待的循环（[afDrive]、[waitForIdle] 等）下一轮就退出，
     * 不再继续往机身上发命令 —— 否则点了停止，USB 通道还被后台线程占着好几秒。
     *
     * 每次开始新操作前由调用方清掉（置 false）。
     */
    @Volatile
    var abortRequested: Boolean = false

    private val usbLock = Any()

    init {
        connection.claimInterface(usbInterface, true)
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    inEndpoint = endpoint
                } else {
                    outEndpoint = endpoint
                }
            } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                interruptEndpoint = endpoint
            }
        }
    }

    /**
     * 开会话（0x1002 OpenSession）。
     *
     * ⚠️ 真机坑：App 被强杀 / 崩溃 / 重装后，相机那头**还挂着上一次的会话**，
     * 此时直接 0x1002 会被拒（PTP Session Failed）。所以先发一发 0x1003 CloseSession
     * 把残留清掉（失败也无所谓），再开；第一次开不成再清一次重试一轮。
     */
    fun openSession(): Boolean {
        fun closeStale() {
            drain()
            val close = createCommandPacket(PtpConstants.OP_CLOSE_SESSION)
            if (sendPacket(close)) {
                receiveResponse(1000)   // 响应无所谓，能把管道里的旧东西冲掉就行
            }
        }
        fun attempt(): Boolean {
            transactionId = 0
            drain()
            val packet = createCommandPacket(PtpConstants.OP_OPEN_SESSION, 1)
            if (!sendPacket(packet)) return false
            val response = receiveResponseWithRetry()
            val ok = response?.responseCode == PtpConstants.RESP_OK ||
                response?.responseCode == PtpConstants.RESP_SESSION_ALREADY_OPEN
            android.util.Log.d(
                "PTP_SESS",
                "OpenSession：${
                    if (response == null) "机身无响应"
                    else "响应 0x${Integer.toHexString(response.responseCode).uppercase()}"
                }"
            )
            return ok
        }

        // 最多三轮：每轮先深排空残留数据（可能卡着上次没传完的大文件），
        // 再 CloseSession 清旧会话，然后开。实测能把"杀进程后连不上"救回来。
        for (round in 1..3) {
            flushPendingData()
            closeStale()
            if (attempt()) return true
            // 上一轮连不上就重挂一次 USB 接口：大传输被打断时相机会 STALL 端点，
            // 之后连命令都发不出去（sendPacket 直接失败），重新 claim 有机会把管道捋顺。
            recoverUsbPipe()
            Thread.sleep(400)
        }
        return false
    }

    /** 重新抢占 USB 接口，试图把 STALL 掉的管道恢复过来 */
    private fun recoverUsbPipe() {
        try {
            connection.releaseInterface(usbInterface)
            Thread.sleep(150)
            connection.claimInterface(usbInterface, true)
            connection.setInterface(usbInterface)
            android.util.Log.d("PTP_SESS", "recoverUsbPipe：已重新 claim 接口")
        } catch (e: Exception) {
            android.util.Log.w("PTP_SESS", "recoverUsbPipe 失败: ${e.message}")
        }
    }

    /**
     * 触发拍摄。
     *
     * 官方 Camera Control Pro 2 的 MAID→PTP 翻译表里，设备类型 7（即 D3/D300 这一代）
     * 拍摄走的是 **0x9207 AF-and-Capture**：机身自己先驱动 AF，合焦后直接释放快门。
     * 我们原来只发 0x90C0 InitiateCapture，机身在 AF-S / 单次对焦下若对焦环没提前合焦，
     * 就会回 0x2019（NikonHardwareError，日志里显示为 "未合焦（0x2019）"）。
     *
     * @param afAlreadyLocked 上一步 [afDrive] 已成功合焦。此时优先走 0x90C0（避免二次对焦），
     *                        只有它回 0x2019 时才补一发 0x9207。
     *                        未合焦（或手动对焦）时反过来：优先 0x9207，机身不支持再退回 0x90C0。
     */
    /**
     * 等机身把手上的活干完。
     *
     * 拍摄前这一步很关键：AF 判失败时，机身往往还在跑上一次对焦，
     * 此刻紧接着发拍摄命令，读回的不是这条命令的响应，就是直接超时
     * （实测就是 "拍摄被拒：等待机身超时"）。先等它转回就绪态再发。
     *
     * @return true 表示机身已就绪；false 表示仍在忙/无响应，调用方应先清管道。
     */
    private fun waitForIdle(budgetMs: Int = 3000): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            if (abortRequested) return false
            val p = createCommandPacket(PtpConstants.OP_NIKON_DEVICE_READY)
            if (!sendPacket(p)) return false
            val r = receiveResponse(2000)
            last = r?.responseCode ?: -1
            when (last) {
                PtpConstants.RESP_OK -> return true
                PtpConstants.RESP_DEVICE_BUSY,
                PtpConstants.RESP_NIKON_HARDWARE_ERROR -> Thread.sleep(200)
                else -> return false
            }
        }
        return last == PtpConstants.RESP_OK
    }

    fun capture(afAlreadyLocked: Boolean = false): Int = synchronized(usbLock) {
        drain()

        // ★ 只等一次、且只给 1.5 秒预算。
        // 以前是 3 秒 + 失败再清管道重来 1.5 秒，后面还叠着 3 次命令重试 ——
        // 一次拍不成能拖住十几秒，期间 usbLock 一直被占着，取景画面也跟着僵住。
        waitForIdle(1500)

        // ★★★ 只认 0x100E InitiateCapture（标准 PTP，D300 标准操作表里有它）★★★
        //
        // 另外两条的取舍：
        //   · 0x9207 InitiateCaptureRecInMedia —— 固件主调度表里没有它的处理函数，
        //     真机一发就把 USB 端点 STALL（4ms 超时，后续命令全废），已永久停用。
        //   · 0x90C0 InitiateCaptureRecIn**Sdram** —— 名字里就写着 Sdram：
        //     照片进机身内存、**不落卡**。拿它兜底会让界面显示"拍到了"而卡里没有，
        //     比干脆报失败更误导人，所以兜底也去掉了。
        //
        // 这样"拍不成"会在一两秒内如实报出来，而不是闷头重试十几秒。
        //
        // ★ 关键：StorageID 必须是真实的卡 ID。
        // 之前传 0xFFFFFFFF 时机身回 0x2008（InvalidStorageId）——
        // 这个错误码本身就是答案：0x100E 被支持，只是参数不能糊弄。
        val sid = firstStorageId()
        if (sid == 0) {
            logger("没读到存储 ID，放弃这次快门")
            return -1
        }

        // 注意第二个实参是**超时**，storageId 必须排在它后面
        val std = executeSingleCaptureAttempt(PtpConstants.OP_INITIATE_CAPTURE, CAPTURE_RESP_TIMEOUT_MS, sid, 0)
        if (std == PtpConstants.RESP_OK) return std
        val hex = if (std < 0) "TIMEOUT" else "0x${Integer.toHexString(std).uppercase()}"
        logger("0x100E 未成（$hex）—— 不再重试、不再换命令，直接结束这次拍摄")
        return std
    }

    /**
     * **取景中**释放快门：非 AF 的 0x90C0。
     *
     * 为什么要单开这一条路：三脚架模式画面已经合焦，按拍摄却被"退出取景 → 反光板落下 →
     * 光学取景那套相位 AF 再对一次"，对不上就整张拍不成。
     *
     * libgphoto2 对尼康的做法就是答案（camlibs/ptp2/library.c · camera_nikon_capture）：
     *   「if in liveview mode, we have to run non-af capture」
     *   `if (params->inliveview) af = 0;`
     * 取景里一律走**不带 AF** 的 0x90C0，既不发 0x9202（退取景），也不发 0x90C1（AF 驱动）；
     * 曝光由机身自己用快门帘完成，反光板和对焦都不动。拍完若机身自己结束了取景，
     * gphoto2 也是再补一发 0x9201 开回来。
     *
     * 参数取 libgphoto2 的写法（0xFFFFFFFF，`ptp_nikon_capture(params, 0xffffffff)`）；
     * 个别机型只认 0，所以 0xFFFFFFFF 被判"参数不支持"时再试 0。
     *
     * ★ 注意：这条**不落卡**（RecInSdram），只在 0x9207 也走不通时兜底用，
     *   让"按下去至少拍到了"，照片会留在机身内存里（可再用 0x1008/0x1009 取走）。
     *   正常路径请看 [captureNoAfToCard]。
     */
    /**
     * ★ 取景内快门**且落卡**：0x9207 InitiateCaptureRecInMedia。
     *
     * 为什么必须单开这条：0x90C0 的名字就是 `InitiateCaptureRecIn**Sdram**` ——
     * 它固定拍进机身内存，**卡里不会有**。真正能"media（存储卡）"的是 0x9207。
     *
     * 参数照 libgphoto2 `ptp.h` 的 `ptp_nikon_capture2`：
     *   param1 = af ? 0xFFFFFFFE : 0xFFFFFFFF   ← 取景里已合焦，传 0xFFFFFFFF（不再对焦）
     *   param2 = target：1 = SDRAM / **0 = 存储卡**
     * 少数机型（D3x）只认 1 个参数：回 0x2006 时退回 `0x9207(0xFFFFFFFF)`。
     *
     * ★★ 已停用（2026-10-22 真机实测）：D300 固件不处理 0x9207，而且**发它会把 USB 端点 STALL** ——
     * 之后的 0x9202 / 0x100E 全部跟着超时，整条链路瘫痪（只能重启机身/拔插 OTG 恢复）。
     * 代价远大于收益，**不要重新启用**（MainActivity 里已无调用点，保留实现仅供以后在别的机身上参考）。
     */
    fun captureNoAfToCard(): Int = synchronized(usbLock) {
        drain()
        if (!waitForIdle(2000)) {
            clearPipe()
            drain()
        }

        var code = executeSingleCaptureAttempt(
            PtpConstants.OP_NIKON_AF_AND_CAPTURE, 1200,
            0xFFFFFFFF.toInt(), 0          // 不对焦 + 目标 = 存储卡
        )
        if (code == PtpConstants.RESP_PARAMETER_NOT_SUPPORTED) {
            logger("0x9207 不认两参数（D3x 式），改用单参数 0xFFFFFFFF 再发一次")
            clearPipe()
            drain()
            code = executeSingleCaptureAttempt(
                PtpConstants.OP_NIKON_AF_AND_CAPTURE, 1200, 0xFFFFFFFF.toInt()
            )
        }
        if (code != PtpConstants.RESP_OK && (
                code == -1 || code == PtpConstants.RESP_DEVICE_BUSY ||
                    code == 0xA004 || code == PtpConstants.RESP_NIKON_HARDWARE_ERROR
                )
        ) {
            clearPipe()
            drain()
            Thread.sleep(600)
            code = executeSingleCaptureAttempt(
                PtpConstants.OP_NIKON_AF_AND_CAPTURE, 1200,
                0xFFFFFFFF.toInt(), 0
            )
        }
        return code
    }

    /**
     * 0x9207（取景内落卡）在本机到底认不认。
     *
     * null = 还没试过；false = 试过且无响应，本会话不再尝试；true = 可用。
     * 只按**会话**记：机身状态可能随重启变化，不该写进持久化配置。
     */
    @Volatile
    var lvCaptureToCardUsable: Boolean? = null
        private set

    fun markLvCaptureToCardUsable(ok: Boolean) {
        lvCaptureToCardUsable = ok
    }

    /** 本会话还能不能试 0x9207（没试过 = 能试一次） */
    fun lvCaptureToCardMayTry(): Boolean = lvCaptureToCardUsable != false

    fun captureNoAf(): Int = synchronized(usbLock) {
        drain()
        if (!waitForIdle(2500)) {
            logger("取景内拍摄：机身未就绪，清管道后再发一次")
            clearPipe()
            drain()
        }

        var code = -1
        for (attempt in 1..3) {
            code = executeSingleCaptureAttempt(
                PtpConstants.OP_NIKON_INITIATE_CAPTURE, CAPTURE_RESP_TIMEOUT_MS,
                0xFFFFFFFF.toInt()          // ← 操作参数（不是超时！超时是它前面那个）
            )
            if (code == PtpConstants.RESP_OK) break

            // 参数不被接受（0x2006）就换成 0 再试一次 —— 别把它当成"这台机器不支持取景内拍摄"
            if (code == PtpConstants.RESP_PARAMETER_NOT_SUPPORTED) {
                logger("0x90C0 不认 0xFFFFFFFF，改用参数 0 再发一次")
                code = executeSingleCaptureAttempt(
                    PtpConstants.OP_NIKON_INITIATE_CAPTURE, CAPTURE_RESP_TIMEOUT_MS, 0
                )
                if (code == PtpConstants.RESP_OK) break
            }

            val hex = if (code < 0) "TIMEOUT" else "0x${Integer.toHexString(code).uppercase()}"
            val retryable = code == -1 ||
                code == PtpConstants.RESP_DEVICE_BUSY ||
                code == PtpConstants.RESP_NIKON_HARDWARE_ERROR ||
                code == 0xA004 // Invalid Status：刚退出上一步时的常见瞬时状态
            logger("取景内快门（0x90C0）第 $attempt 次未成（$hex）" + if (retryable && attempt < 3) "，等 800ms 重试" else "")
            clearPipe()
            drain()
            if (!retryable || attempt == 3) break
            Thread.sleep(800)
        }
        return code
    }

    /**
     * 列举当前能看见的全部对象句柄（逐卡为主 + 全卡兜底）。
     *
     * D300 上「storage=全部卡 + parent=0」这一问法实测会回空或 0x2013（见 [getObjectHandlesIn]），
     * 所以主路是逐卡列举，全卡那一路只作兜底，两条结果合并去重。
     */
    private fun enumerateAllHandles(): Set<Int> {
        val out = LinkedHashSet<Int>()
        getStorageIds()?.forEach { sid ->
            getObjectHandlesIn(sid, 0)?.forEach { out.add(it) }
        }
        getObjectHandles()?.forEach { out.add(it) }
        return out
    }

    /** 上一次事件里报的新对象句柄（拍摄流程自己也要用） */
    @Volatile
    var lastNewObjectHandle: Int = -1
        private set

    fun clearPipe() {
        inEndpoint?.let { connection.clearHalt(it) }
        outEndpoint?.let { connection.clearHalt(it) }
    }

    private fun UsbDeviceConnection.clearHalt(endpoint: UsbEndpoint): Boolean {
        return controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or 0x02, // 0x02 is USB_RECIP_ENDPOINT
            0x01, // CLEAR_FEATURE
            0x00, // ENDPOINT_HALT
            endpoint.address,
            null,
            0,
            1000
        ) >= 0
    }

    /**
     * 发一次快门命令并等回应。
     *
     * ⚠️ 签名是 `(opCode, respTimeoutMs = 2000, vararg params)` —— **超时在第二个位置，参数在 vararg**。
     * 曾经有调用写成 `executeSingleCaptureAttempt(OP_NIKON_INITIATE_CAPTURE, 0xFFFFFFFF.toInt())`：
     * Kotlin 把 0xFFFFFFFF 当成**超时**（约 49 天），而 vararg 变成空数组 ——
     * 命令本体被丢了参数，超时也彻底失控。改参数一律显式写出超时。
     *
     * 保险起见这里再把超时夹到 [300, 3000]：一次快门回应不该超过 3 秒，
     * 超限就让调用方的重试逻辑接管，而不是死等把 usbLock 占住拖垮整条 USB 通道。
     */
    private fun executeSingleCaptureAttempt(
        opCode: Int,
        respTimeoutMs: Int = 2000,
        vararg params: Int
    ): Int {
        val opName = "0x${Integer.toHexString(opCode).uppercase()}"
        logger("Triggering Capture ($opName)...")

        val packet = createCommandPacket(opCode, *params)
        if (!sendPacket(packet)) return -1

        val response = receiveResponseWithRetry(5, timeoutMs = respTimeoutMs.coerceIn(300, 3000))
        
        if (response == null || response.responseCode == 0xA002 || response.responseCode == 0xA008) {
            val codeHex = if (response != null) "0x${Integer.toHexString(response.responseCode).uppercase()}" else "TIMEOUT"
            logger("Capture Rejected: $codeHex. Rescuing pipe...")
            clearPipe()
            return response?.responseCode ?: -1
        }

        val code = response.responseCode
        if (code == PtpConstants.RESP_OK) {
            logger("Capture Triggered Successfully ($opName)")
            Thread.sleep(300)
            clearEventsThoroughly()
            // 等待机身真正回到就绪态，避免紧接着的操作撞上 DeviceBusy。
            // 上限压到 5 轮（约 1 秒）：拍成之后机身写卡本来就慢，
            // 在这里干等两秒只是把 USB 通道白占着，调用方自己还有写卡等待。
            for (i in 0 until 5) {
                val rp = createCommandPacket(PtpConstants.OP_NIKON_DEVICE_READY)
                if (!sendPacket(rp)) break
                val rr = receiveResponse(2000)
                if (rr?.responseCode == PtpConstants.RESP_OK) break
                Thread.sleep(200)
            }
            return code
        } else {
            val codeHex = "0x${Integer.toHexString(code).uppercase()}"
            if (code == PtpConstants.RESP_NIKON_HARDWARE_ERROR) {
                logger("Capture $opName failed: Focus Not Locked ($codeHex)")
            } else {
                logger("Capture $opName failed: $codeHex")
            }
            clearPipe()
            logger("管道复位：已对输入/输出端点执行 ClearHalt")
            return code
        }
    }

    /**
     * @param maxRetries 重试次数。LiveView 这类要给用户即时反馈的路径会调低 —— 每次重试都可能
     *                   触发下面那段"恢复序列"（DeviceReady + 事件轮询 + 1.5s 等待），
     *                   单轮就能耗掉十几秒，3 轮加起来足以让用户以为按钮失灵。
     */
    fun setDevicePropValue(
        propCode: Int,
        value: Int,
        size: Int? = null,
        logDesc: String = "",
        maxRetries: Int = 3
    ): Boolean = synchronized(usbLock) {
        // size 用可空参数而不是默认值 2：之前用"是否等于默认值"来判断调用方有没有显式指定，
        // 结果上层查到机身声明 UINT16（size=2）后想按声明宽度重试，
        // 却被当成"没指定"又降级回 UINT8 —— 重试形同没发生，一直用错的宽度在写。
        val finalSize = size ?: if (propCode == PtpConstants.PROP_NIKON_LIVE_VIEW) 1 else 2
        val valBytes = when (finalSize) {
            1 -> byteArrayOf(value.toByte())
            2 -> {
                val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                b.putShort(value.toShort())
                b.array()
            }
            4 -> {
                val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                b.putInt(value)
                b.array()
            }
            else -> byteArrayOf(value.toByte())
        }

        val valStr = "0x${Integer.toHexString(value).uppercase()}"
        val bytesHex = bytesToHex(valBytes, valBytes.size)
        android.util.Log.d("PTP_TX_PROP", "Set Prop 0x${Integer.toHexString(propCode).uppercase()} to value $valStr (Hex: $bytesHex)")

        for (i in 0 until maxRetries) {
            drain()
            if (logDesc.isNotEmpty()) android.util.Log.d("PTP_TX_CMD", logDesc)

            val packet = createCommandPacket(PtpConstants.OP_SET_DEVICE_PROP_VALUE, propCode)
            if (!sendPacket(packet)) {
                logger("Failed to send Command 0x1016 for Prop 0x${Integer.toHexString(propCode).uppercase()}")
                return false
            }

            val data = createDataPacket(PtpConstants.OP_SET_DEVICE_PROP_VALUE, valBytes)
            if (!sendPacket(data)) {
                logger("Failed to send Data for Prop 0x${Integer.toHexString(propCode).uppercase()}")
                return false
            }

            val response = receiveResponse(3000)

            if (response?.responseCode == PtpConstants.RESP_OK) {
                lastSetPropError = 0
                logger("Prop 0x${Integer.toHexString(propCode).uppercase()} set to $valStr: Success")
                return true
            }

            lastSetPropError = response?.responseCode ?: -1

            if (response == null || response.responseCode == 0xA008 || response.responseCode == PtpConstants.RESP_DEVICE_BUSY) {
                val resHex = if (response != null) "0x${Integer.toHexString(response.responseCode).uppercase()}" else "TIMEOUT"
                logger("Prop 0x${Integer.toHexString(propCode).uppercase()} $resHex. Triggering Recovery Sequence...")

                deviceReady()
                drain()
                Thread.sleep(1000)

                // Dummy Read to clear bus
                getDevicePropValue(propCode)
                Thread.sleep(500)

                continue
            }

            val resHex = "0x${Integer.toHexString(response.responseCode).uppercase()}"
            logger("Prop 0x${Integer.toHexString(propCode).uppercase()} set to $valStr: Failed ($resHex)")
            return false
        }
        logger("Prop 0x${Integer.toHexString(propCode).uppercase()} 设置失败，已重试 $maxRetries 次")
        return false
    }

    fun getDevicePropValue(propCode: Int): Int = synchronized(usbLock) {
        try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_GET_DEVICE_PROP_VALUE, propCode)
            if (!sendPacket(packet)) return -999999
            
            val data = receiveData()
            if (data == null) return -999999
            
            receiveResponseWithRetry()
            
            data.position(12)
            return when (data.remaining()) {
                1 -> data.get().toInt() and 0xFF
                2 -> data.short.toLong().toInt() and 0xFFFF
                4 -> data.int // Will return -1 for 0xFFFFFFFF
                else -> -999999
            }
        } catch (e: Exception) {
            return -999999
        }
    }

    /**
     * 上一次成功启动取景时用到的前置命令。
     *
     * D300 回 0xA004 拒 0x9201 的根因一直没定位到，与其每次都猜，
     * 不如扫描一次并把命中的那条记下来，之后每次开取景都直接复用（快且稳）。
     */
    private var lvEntryPreamble: (() -> Unit)? = null

    /** 记录媒介的原始值，扫描完若没被用作前置命令就还原，避免照片被改写到别的去处 */
    private var recordingMediaOriginal: Int = -999999

    private fun hexOrFail(v: Int): String =
        if (v == -999999) "读取失败" else "0x${Integer.toHexString(v).uppercase()}"

    /**
     * 把可能挡住取景的机身状态一次性读出来。
     *
     * 0xA004 是"机身不答应"，但机身不会告诉你卡在哪一项 —— 只能自己把相关状态摊开。
     * 这里读的四项分别对应四类常见成因：记录媒介、取景模式、曝光程序、对焦模式。
     */
    private fun dumpLiveViewBlockers() {
        val media = getDevicePropValue(PtpConstants.PROP_NIKON_RECORDING_MEDIA)
        val lv = getDevicePropValue(PtpConstants.PROP_NIKON_LIVE_VIEW)
        val program = getDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE)
        val focus = getDevicePropValue(PtpConstants.PROP_FOCUS_MODE)
        logger(
            "机身状态：记录媒介(0xD10B)=${hexOrFail(media)} 取景模式(0x5013)=${hexOrFail(lv)} " +
                "曝光程序(0x500E)=${hexOrFail(program)} 对焦模式(0x500A)=${hexOrFail(focus)}"
        )
        logger("记录媒介描述符：${describeProp(PtpConstants.PROP_NIKON_RECORDING_MEDIA)}")
    }

    /**
     * 0x9201 反复被 0xA004 拒绝时，把候选"前置命令"挨个试一遍。
     *
     * 0xA004 的含义是"机身当前状态不允许"，不是命令写错了 —— 也就是说我们少了某一步
     * 官方驱动会做、而我们没做的准备动作。候选来自两条独立线索：
     *
     *   · remoteyourcam-usb 的 `NikonOpenSessionAction`：开完会话后唯一的动作就是
     *     写 `0xD10B RecordingMedia = 1`（UINT8）。放在会话建立阶段说明它是通用的
     *     前置条件，而不是某个具体功能才需要。官方属性表里 0xD10B 合法域 = {1,2}，两个都试。
     *   · 0x90C2 ChangeCameraMode：官方模块里它有引用计数（dev+0x44），说明进/出模式
     *     是成对调用的，参数值未必是 1，把 0/1/2 都试掉。
     *   · 0x5013 取景模式：已知在 D300 上写不进去（0xA005），留一条作对照 ——
     *     若扫描期间它能过，说明机身状态在这期间变了，本身就是重要线索。
     *
     * 每条都会把机身回码写进日志；命中的那条存进 [lvEntryPreamble]，下次开取景直接复用。
     *
     * @return 成功的响应；全部失败返回 null。
     */
    private fun sweepLiveViewEntry(): PtpResponse? {
        logger("启动被拒，扫描候选前置命令（每条的机身回码都会记下来）…")

        recordingMediaOriginal = getDevicePropValue(PtpConstants.PROP_NIKON_RECORDING_MEDIA)
        dumpLiveViewBlockers()

        data class Candidate(val label: String, val isMedia: Boolean = false, val preamble: () -> Unit)

        val candidates = listOf(
            Candidate("0x90C2 ChangeCameraMode(0)") { setControlMode(0) },
            Candidate("0x90C2 ChangeCameraMode(1)") { setControlMode(1) },
            Candidate("0x90C2 ChangeCameraMode(2)") { setControlMode(2) },
            Candidate("写 0xD10B RecordingMedia=1 (UINT8)", isMedia = true) {
                setDevicePropValue(PtpConstants.PROP_NIKON_RECORDING_MEDIA, 1, 1, "", 1)
            },
            Candidate("写 0xD10B RecordingMedia=2 (UINT8)", isMedia = true) {
                setDevicePropValue(PtpConstants.PROP_NIKON_RECORDING_MEDIA, 2, 1, "", 1)
            },
            Candidate("写 0x5013 取景模式=2 (UINT16)") {
                setDevicePropValue(PtpConstants.PROP_NIKON_LIVE_VIEW, 2, 2, "", 1)
            }
        )

        var winner: Candidate? = null
        var ok: PtpResponse? = null

        for (c in candidates) {
            val pre = try {
                c.preamble()
                "已执行"
            } catch (e: Exception) {
                "执行异常 ${e.message}"
            }
            Thread.sleep(250)
            deviceReadyOnce()
            drain()
            val p = createCommandPacket(PtpConstants.OP_NIKON_START_LIVE_VIEW)
            if (!sendPacket(p)) {
                logger("  · ${c.label} → $pre；0x9201 发送失败，跳过")
                continue
            }
            val r = receiveResponseWithRetry()
            val hex = r?.let { "0x${Integer.toHexString(it.responseCode).uppercase()}" } ?: "TIMEOUT"
            logger("  · ${c.label} → $pre；0x9201 回 $hex")
            if (r?.responseCode == PtpConstants.RESP_OK) {
                winner = c
                ok = r
                break
            }
            Thread.sleep(400)
        }

        // 媒介属性的改动会影响照片存到哪，只有它本身被选为前置命令时才保留
        if (winner?.isMedia != true && recordingMediaOriginal != -999999) {
            // ★ 只回写机身自己声明过的合法值。
            // 0xD10B 是 UINT16，机身报的合法域是 1/2 和 0x8000 段（32784..32787）。
            // 某些状态下读回来的"原值"是 0 —— 那不是合法档位，写进去必被 0x2019 拒绝，
            // 而且这一发失败事务会把管道搅乱，紧接着的开取景也跟着被拒（实测踩过）。
            val legal = recordingMediaOriginal == 1 || recordingMediaOriginal == 2 ||
                (recordingMediaOriginal and 0x8000) != 0
            if (legal) {
                setDevicePropValue(PtpConstants.PROP_NIKON_RECORDING_MEDIA, recordingMediaOriginal, 1, "", 1)
            } else {
                logger(
                    "记录媒介原值=0x${Integer.toHexString(recordingMediaOriginal).uppercase()} " +
                        "不在机身的合法域（1/2/0x8000 段），跳过回写"
                )
            }
        }

        return if (winner != null) {
            logger("前置命令命中：${winner.label}")
            lvEntryPreamble = winner.preamble
            ok
        } else {
            logger("候选前置命令全部无效 —— 挡住取景的不是这几项，看上面的机身状态判断")
            null
        }
    }

    /**
     * 开启实时取景。
     *
     * 返回 [LiveViewStartResult]，失败时带具体原因。以前只回 Boolean，
     * 而这条链路（写 0x5013 → DeviceReady → 0x9201）最坏能跑近两分钟且全程静默，
     * 用户完全看不出是卡住了还是失败了，只会觉得"LV 按钮没反应"。
     * 现在每步失败都说清楚，并且整体有超时兜底。
     */
    fun startLiveView(): LiveViewStartResult {
        fun codeHex(code: Int) = if (code >= 0) "0x${Integer.toHexString(code).uppercase()}" else "TIMEOUT"

        logger("正在启动实时取景…")
        // 分段计时：启动慢不慢、慢在哪一段，日志里一眼看得到，不用猜
        val t0 = System.currentTimeMillis()
        var mark = t0
        fun lap(label: String): Long {
            val now = System.currentTimeMillis()
            val cost = now - mark
            mark = now
            return cost
        }

        // 先探一眼机身是不是已经在取景了（比如用户刚在机身上按了 Lv 键）。
        // 是的话直接接管画面 —— 不用再撞 0x9201 那个 0xA004。
        if (probeLiveViewActive()) {
            logger("机身已在取景，直接接管画面显示（探测 ${lap("probe")}ms）")
            return LiveViewStartResult.ok()
        }
        val probeCost = lap("probe")

        // 启动序列对齐官方 Type0001.md3（D3/D300 这一代机型的驱动）：
        // case 8 就是 0x9201 后面紧跟一个 0x90C8 DeviceReady，**没有任何属性预设**。
        //
        // 我们原来按"D300 惯例"先写 0x5013（取景模式：2 三脚架 / 1 手持），实测一律被拒（0xA005）：
        //   · 官方模块里 0x5013 这个常量一次都没出现过（全量扫过 246 处写属性调用点，
        //     属性码只有 0x5007 这类常见的，取景模式属性不在其中）
        //   · 机身自己的描述符也印证：GetSet=0（只读），当前值本来就是 2
        //   · UINT8 和机身声明的 UINT16 两种宽度都试过，都回 0xA005 —— 不是宽度问题，是根本不可写
        // 这几轮无效写入既拖慢启动，还可能在 0x9201 之前把机身留在奇怪的状态，所以整个去掉。
        // 取景模式交给机身自己决定，这里只把它读出来记一行日志，方便排查画面异常。
        val currentMode = getDevicePropValue(PtpConstants.PROP_NIKON_LIVE_VIEW)
        val propNote = when (currentMode) {
            1 -> "机身取景模式=手持"
            2 -> "机身取景模式=三脚架"
            in 0x8010..0x8013 -> "机身取景模式=0x${Integer.toHexString(currentMode).uppercase()}"
            0 -> "机身未开启取景模式"
            -999999 -> "取景模式读取失败"
            else -> "机身取景模式=0x${Integer.toHexString(currentMode).uppercase()}"
        }
        logger("启动取景：$propNote")

        // 先把机身轮询到就绪态再发启动命令。官方流水线上每个操作后面都跟一个 0x90C8，
        // 而 0x9201 被拒最常见的原因就是机身"状态不答应"（0xA004 = Invalid Status），
        // 等它翻到就绪态再发，比上来就发成功率高。
        var ready = false
        for (i in 0 until 10) {
            if (deviceReadyOnce()) { ready = true; break }
            Thread.sleep(150)
        }
        if (!ready) logger("机身持续未就绪，仍尝试启动取景")
        val preReadyCost = lap("preReady")

        fun sendStart(): PtpResponse? {
            drain()
            val p = createCommandPacket(PtpConstants.OP_NIKON_START_LIVE_VIEW)
            if (!sendPacket(p)) return null
            return receiveResponseWithRetry()
        }

        // 上一次扫描命中的前置命令，这次直接先跑，省掉再扫一遍的时间
        lvEntryPreamble?.let { preamble ->
            logger("沿用上次命中的前置命令")
            try {
                preamble()
            } catch (e: Exception) {
                logger("前置命令执行异常：${e.message}")
            }
            // 原来这里固定睡 200ms 再问一句 DeviceReady。前置写属性后机身几乎立刻应答，
            // 改成"先问、不成就短睡"，多数情况下这 200ms 可以全省下来。
            if (!deviceReadyOnce()) {
                Thread.sleep(120)
                deviceReadyOnce()
            }
        }
        val preambleCost = lap("preamble")

        var response = sendStart()
        val startCmdCost = lap("0x9201")

        // 机身忙时稍等重试（尼康习惯上把 busy 和 0x2019 都用在这一类场景，两种都覆盖）
        var busyRetries = 0
        while ((response?.responseCode == PtpConstants.RESP_DEVICE_BUSY ||
                    response?.responseCode == PtpConstants.RESP_NIKON_HARDWARE_ERROR) && busyRetries < 3) {
            busyRetries++
            Thread.sleep(500)
            response = sendStart()
        }

        // Invalid Status 往往是机身还在收尾上一个动作，等它状态翻过去再试一次。
        // 注意：光重发同一条命令通常没用，中间必须留出机身改变状态的时间。
        if (response?.responseCode == PtpConstants.RESP_INVALID_STATUS) {
            // 原来固定睡 1.5 秒再重试：机身常常 300ms 就收尾完了，那 1.2 秒纯属白等。
            // 改成边等边问 DeviceReady，一就绪立刻补发（最坏仍是 1.5 秒）。
            logger("机身回 Invalid Status，等它收尾后重试一次")
            val until = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < until) {
                Thread.sleep(250)
                if (deviceReadyOnce()) break
            }
            response = sendStart()
        }

        // 仍然 Invalid Status —— 说明我们少了某一步官方驱动会做、而我们没做的前置动作。
        // 光靠猜是试不出来的（前面几轮已经试过等状态、0x90C2(1)），这里改成一次性扫描：
        // 把候选前置命令挨个试，每条都把机身回码记进日志，命中的那条直接采用并记住。
        if (response?.responseCode == PtpConstants.RESP_INVALID_STATUS) {
            val swept = sweepLiveViewEntry()
            if (swept != null) response = swept
        }

        if (response?.responseCode != PtpConstants.RESP_OK) {
            val hex = codeHex(response?.responseCode ?: -1)
            val hint = when (response?.responseCode) {
                PtpConstants.RESP_INVALID_STATUS ->
                    "机身状态不允许：模式转盘转到 M/A/S/P，释放模式设为单张，装好存储卡与 CPU 镜头，电量充足"
                PtpConstants.RESP_NIKON_HARDWARE_ERROR -> "机身拒绝（检查反光板 / 电量 / 镜头是否就位）"
                PtpConstants.RESP_DEVICE_BUSY -> "机身正忙，稍后再试"
                null -> "机身无响应，检查 USB 连接"
                else -> "当前拍摄条件不支持取景"
            }
            // 启动被拒时把机身对取景模式属性的声明读回来：
            // 能区分"机身整体不答应"（当前模式仍是 0）和"我们问错了方式"（属性可写）。
            logger("启动被拒，读回机身声明：${describeProp(PtpConstants.PROP_NIKON_LIVE_VIEW)}")
            // 注意：USB 连着的时候机身屏幕是黑的、按键也不起作用，
            // 所以"先去机身上按 Lv 键"这条路在连机状态下根本走不通，不要这样提示。
            return LiveViewStartResult.fail("0x9201 启动失败 $hex：$hint（$propNote）")
        }

        // 反光板抬升需要数秒，轮询 DeviceReady 直到机身真正就绪。
        // ★ 先查后睡：原来第一轮是"先睡 300ms 再查"，哪怕机身早就抬完了也要白等这 300ms。
        // 间隔收到 150ms、上限 12 轮（≈1.8 秒），到点就放行 —— 后面的取景循环自己会处理 busy。
        for (i in 0 until 12) {
            if (deviceReadyOnce()) break
            Thread.sleep(150)
        }
        val postReadyCost = lap("postReady")
        drain()
        logger(
            "取景已启动（$propNote）· 耗时 ${System.currentTimeMillis() - t0}ms " +
                "[探测 $probeCost / 等就绪 $preReadyCost / 前置 $preambleCost / 0x9201 $startCmdCost / 抬反光板 $postReadyCost]"
        )
        return LiveViewStartResult.ok()
    }

    /**
     * 只发 DeviceReady 并读响应，不做事件清理（供 LV 等待循环使用）。
     * 返回 true = 机身已就绪。**DeviceBusy(0x2002) 也算未就绪**，调用方应继续等 ——
     * 之前这里把 0x2002 当成就绪，导致"等反光板"的循环第一轮就退出，
     * 取景循环提前开跑，随后一路吃 DeviceBusy。
     */
    private fun deviceReadyOnce(): Boolean {
        val packet = createCommandPacket(PtpConstants.OP_NIKON_DEVICE_READY)
        if (!sendPacket(packet)) return false
        val response = receiveResponse(1000) ?: return false
        return response.responseCode == PtpConstants.RESP_OK
    }

    /**
     * 切换机身控制模式（0x90C2 SetControlMode，1 个参数）。
     *
     * 官方操作码能力表记它带 1 参数、无数据阶段。D300 拒绝 0x9201 时回的是
     * 0xA004 Invalid Status —— 不是"命令写错了"，而是"机身现在不答应"。
     * 机身要求先切进 PC 控制模式是很典型的成因，这条命令就是做那次切换的。
     *
     * 返回机身响应码；-1 表示发送失败或超时。
     */
    fun setControlMode(mode: Int): Int = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_NIKON_SET_CONTROL_MODE, mode)
        if (!sendPacket(packet)) return -1
        val response = receiveResponse(2000)
        return response?.responseCode ?: -1
    }

    /** 上一次 0x9202 的机身返回码（-1 = 无响应 / 发送失败），用于给用户报具体原因 */
    var lastEndLiveViewCode: Int = -1
        private set

    /**
     * 开完会话后预先把"取景前置条件"补上。
     *
     * 实测：D300 上第一次发 0x9201 必被拒（0x1015 / 0xA004），扫描候选前置命令后
     * 命中的是"写 0xD10B RecordingMedia=1" —— 之后 0x9201 立刻成功。
     * 官方 remoteyourcam-usb 的 NikonOpenSessionAction 在开完会话后唯一做的事
     * 也正是写这个属性，说明它属于会话级前置条件，而不是取景时才临时补一步。
     *
     * 提前写掉，用户第一次点 LV 就不用再走一遍"失败 + 扫描"（那一轮要几十秒，
     * 表面上看又像是"LV 按钮没反应"）。
     */
    fun primeLiveViewEntry() {
        // 取景前置：0x9201 要求记录媒介是 1（机身缓存）。这一步**只在开取景前临时做**，
        // 不再像以前那样连上就把 0xD10B 设成 1 —— 那样用户不开取景直接拍，
        // 照片会留在机身内存里，卡上什么都不加。
        lvEntryPreamble = {
            setDevicePropValue(
                PtpConstants.PROP_NIKON_RECORDING_MEDIA,
                PtpConstants.RECORDING_MEDIA_SDRAM, 1, "", 1
            )
        }
        // 反过来，连上会话后的默认状态必须是"写卡"
        ensureRecordToCard()
    }

    /**
     * 确保记录媒介是**存储卡**（0xD10B=0）。
     *
     * 为什么需要专门一条：0x9201 开实时取景的前置条件是把记录媒介切成 1（机身缓存），
     * 那是取景必须的临时状态。但只要它停在 1，之后拍的照片就只进机身内存 ——
     * 卡里文件数不变、可拍张数也不变，用户会以为"快门没写卡"。
     *
     * 所以：会话建立后、退出取景后都要调它切回 0。
     * 返回 true = 现在确实是"写卡"状态（本来就是 0 也算成功）。
     */
    fun ensureRecordToCard(): Boolean = try {
        val current = getDevicePropValue(PtpConstants.PROP_NIKON_RECORDING_MEDIA)
        when {
            current == PtpConstants.RECORDING_MEDIA_CARD -> true
            current == -999999 -> false          // 机身不答，别瞎写
            else -> {
                val ok = setDevicePropValue(
                    PtpConstants.PROP_NIKON_RECORDING_MEDIA,
                    PtpConstants.RECORDING_MEDIA_CARD, 1,
                    "记录媒介切回存储卡（原值=$current）", 1
                )
                logger(
                    if (ok) "记录媒介已切回存储卡（0xD10B=0）"
                    else "记录媒介切回存储卡失败（当前=$current），本张照片可能只进机身缓存"
                )
                ok
            }
        }
    } catch (e: Exception) {
        logger("记录媒介切回存储卡出错：${e.message}")
        false
    }

    /**
     * 结束实时取景。
     *
     * 官方 Type0001.md3 结束取景就是单独一个 0x9202，不带属性复位。
     * 原来这里还要把 0x5013 写回 0，但该属性在官方模块里从未出现、
     * 实测也一律被机身拒绝（0xA005），写了只是多一轮无效往返。
     *
     * 「点了退不出去、只能关相机电源」的两个真实原因：
     *  1) 本函数原来**不持 usbLock**，而取景循环线程正在 synchronized 里读帧。
     *     两条线程同时打同一条 USB 管道，0x9202 的响应会被读帧那头吃掉，
     *     机身那边也只收到半条命令，于是始终留在取景状态。
     *  2) 无论机身回什么都 return true，界面照常显示 "实时取景已关闭"，
     *     失败被彻底藏起来 —— 用户只能靠关机退出。
     *
     * 现在：持锁发送 → 最多 3 轮重试（机身忙就多等一会）→ 跟一个官方流水线上的
     * DeviceReady → 最后用 0x9203 复核机身是否真的不再返回取景帧。
     *
     * @return true = 机身确认已退出取景
     */
    fun endLiveView(): Boolean = synchronized(usbLock) {
        var lastCode = -1
        for (attempt in 1..3) {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_NIKON_END_LIVE_VIEW)
            if (!sendPacket(packet)) {
                logger("0x9202 发送失败（第 $attempt 次）")
                Thread.sleep(300)
                continue
            }
            // 3s 上限：机身不回应时不能让整条退出链路耗在等响应上，
            // 上层对本次退出还有 15s 的总闸，超时会明确报出来而不是干等
            val response = receiveResponse(3000)
            lastCode = response?.responseCode ?: -1
            lastEndLiveViewCode = lastCode
            if (lastCode == PtpConstants.RESP_OK) break

            val hex = if (lastCode >= 0) "0x${Integer.toHexString(lastCode).uppercase()}" else "TIMEOUT"
            logger("0x9202 第 $attempt 次返回 $hex")
            // 机身正忙最常见，给它收尾的时间再试；其他码也再试一次 ——
            // 取景状态下机身经常对单条命令给出一次性拒绝。
            Thread.sleep(if (lastCode == PtpConstants.RESP_DEVICE_BUSY) 800 else 400)
        }

        // 官方流水线：每个操作后面都跟一个 DeviceReady
        deviceReadyOnce()
        drain()

        if (lastCode != PtpConstants.RESP_OK) return false

        // 取景期间记录媒介是 1（机身缓存）—— 打开取景时临时切过去的。
        // 退出后必须切回 0，否则接下来拍的照片全落在机身内存里。
        ensureRecordToCard()

        // 复核：机身还在返回取景帧就说明它没真退出，再补一发
        Thread.sleep(400)
        if (probeLiveViewActive()) {
            logger("机身仍在返回取景帧，补发一次 0x9202")
            drain()
            val retry = createCommandPacket(PtpConstants.OP_NIKON_END_LIVE_VIEW)
            if (sendPacket(retry)) {
                val r = receiveResponse(3000)
                lastEndLiveViewCode = r?.responseCode ?: -1
                logger("补发 0x9202 返回 ${if (r != null) "0x${Integer.toHexString(r.responseCode).uppercase()}" else "TIMEOUT"}")
            }
            deviceReadyOnce()
            drain()
        }
        return !probeLiveViewActive()
    }

    // ===================== 对焦区域 / 对焦点控制 =====================

    /**
     * 取景画面的整幅尺寸与 AF 框尺寸（最近一帧从帧头读到的，整幅坐标系）。
     *
     * 0x9205 的坐标必须落在整幅画面里，而整幅尺寸只能从取景帧头拿到 ——
     * 不取景时无从得知，所以这里缓存最近一次的值，取景结束后继续沿用。
     */
    var lastWholeWidth: Int = 0
        private set
    var lastWholeHeight: Int = 0
        private set
    var lastAfFrameWidth: Int = 0
        private set
    var lastAfFrameHeight: Int = 0
        private set

    /** 每帧更新缓存：取景循环里顺手记一下，退出取景后仍可用于坐标换算 */
    fun noteFrameGeometry(frame: LiveViewFrame) {
        if (frame.wholeWidth > 0 && frame.wholeHeight > 0) {
            // 未放大时记下"整幅有多宽"：放大后机身报的整幅就是被裁那一块，
            // 两个一比就是真实倍率（比按档位猜准）
            if (lvZoomValue == 0) lvWholeBaseW = frame.wholeWidth
            lastWholeWidth = frame.wholeWidth
            lastWholeHeight = frame.wholeHeight
        }
        if (frame.afFrameWidth > 0) lastAfFrameWidth = frame.afFrameWidth
        if (frame.afFrameHeight > 0) lastAfFrameHeight = frame.afFrameHeight
    }

    /**
     * 把"用户点在画面的哪个位置"（归一化 0..1）翻译成 0x9205 需要的整幅像素坐标。
     *
     * clamp 是必须的：AF 框有自己的大小，中心点不能贴边，否则框会露出画面外，
     * 机身会直接拒绝（remoteyourcam 就是这么做的）。
     *
     * @return null 表示还不知道整幅尺寸（从没取过景），这时不该发命令
     */
    fun mapNormalizedToAfArea(nx: Float, ny: Float): Pair<Int, Int>? {
        val w = lastWholeWidth; val h = lastWholeHeight
        if (w <= 0 || h <= 0) return null
        val halfW = if (lastAfFrameWidth > 0) lastAfFrameWidth / 2 else 0
        val halfH = if (lastAfFrameHeight > 0) lastAfFrameHeight / 2 else 0
        val cx = (nx * w).coerceIn(halfW.toFloat(), (w - halfW).toFloat())
        val cy = (ny * h).coerceIn(halfH.toFloat(), (h - halfH).toFloat())
        return Pair(cx.toInt(), cy.toInt())
    }

    /**
     * 0x9205 ChangeAfArea：把取景时的 AF 区域框挪到整幅画面的 (centerX, centerY)。
     *
     * 必须持 usbLock，且不能和取景读帧并发 —— 取景循环以 10fps 打同一条管道，
     * 命令插进去会被读帧那头吃掉响应。调用方（MainActivity）负责在发之前
     * 把读帧让一让，这里只保证单次事务的完整性。
     *
     * @return 机身响应码（0x2001 = OK），-1 表示连命令都没发出去
     */
    fun changeAfArea(centerX: Int, centerY: Int): Int = synchronized(usbLock) {
        drain()
        val hex = { v: Int -> "0x${Integer.toHexString(v and 0xFFFF).uppercase()}" }
        android.util.Log.d(
            "PTP_TX",
            "COMMAND: ChangeAfArea (${hex(PtpConstants.OP_NIKON_CHANGE_AF_AREA)}) x=$centerX y=$centerY"
        )
        val packet = createCommandPacket(PtpConstants.OP_NIKON_CHANGE_AF_AREA, centerX, centerY)
        if (!sendPacket(packet)) {
            logger("0x9205 发送失败")
            return -1
        }
        val resp = receiveResponse(3000)
        val code = resp?.responseCode ?: -1
        val codeStr = if (resp != null) hex(code) else "TIMEOUT"
        logger("ChangeAfArea($centerX, $centerY) -> $codeStr")
        // 与官方一致：命令后跟 DeviceReady，等机身把这次区域变更消化掉
        deviceReadyOnce()
        code
    }

    /**
     * 尼康私有属性的宽度是试出来的，不是查表查出来的。
     *
     * 官方 226 项属性表那个"类型"字段不是 PTP 数据类型（0xD10B 标 0x0000，
     * 但我们按 UINT8 写是成功的）。所以这里按"先 UINT8 再 UINT16"试：
     * 机身回 0x200B（类型不符）或 0xA005（不接受这个写法）就换宽度再来一次。
     *
     * 只重试 1 轮：这些属性写失败是常态（取景中/拨盘不在位都会拒），
     * 走 setDevicePropValue 默认的 3 轮恢复序列动辄十几秒，界面会卡死。
     */
    private fun setNikonProp(propCode: Int, value: Int): Boolean {
        if (setDevicePropValue(propCode, value, size = 1, maxRetries = 1)) return true
        val err = lastSetPropError
        if (err == PtpConstants.RESP_INVALID_PROP_FORMAT ||
            err == PtpConstants.RESP_INVALID_PROP_FORMAT_1 ||
            err == PtpConstants.RESP_PARAM_REJECTED
        ) {
            return setDevicePropValue(propCode, value, size = 2, maxRetries = 1)
        }
        return false
    }

    /** 当前对焦点 id（0xD108）。返回 -999999 表示机身没应答 */
    fun getAfAreaPoint(): Int = getDevicePropValue(PtpConstants.PROP_NIKON_AF_AREA_POINT)

    /**
     * 选择对焦点（写 0xD108）。
     *
     * 注意时机：机身在取景状态、或者 AF 区域模式不是"单点"时多半会拒。
     * 这不是协议问题，改宽度重试也没用 —— 所以失败时把响应码原样带回给界面显示。
     */
    fun setAfAreaPoint(pointId: Int): Boolean {
        val ok = setNikonProp(PtpConstants.PROP_NIKON_AF_AREA_POINT, pointId)
        logger("设置对焦点 id=$pointId ${if (ok) "成功" else "失败（0x${Integer.toHexString(lastSetPropError).uppercase()}）"}")
        return ok
    }

    /** AF 点数：true=51 点全可选，false=仅 11 点 */
    fun setAfPointCount(all51: Boolean): Boolean {
        val v = if (all51) 0 else 1
        val ok = setNikonProp(PtpConstants.PROP_NIKON_AF_POINT_COUNT, v)
        logger("设置 AF 点数=${if (all51) "51" else "11"}（0xD08D=$v）${if (ok) "成功" else "失败（0x${Integer.toHexString(lastSetPropError).uppercase()}）"}")
        return ok
    }

    /** AF 区域模式 0..5：单点 / 动态9 / 动态21 / 动态51 / 3D跟踪 / 自动区域 */
    fun setAfAreaMode(mode: Int): Boolean {
        val ok = setNikonProp(PtpConstants.PROP_NIKON_AF_AREA_MODE, mode.coerceIn(0, 5))
        logger("设置 AF 区域模式=$mode ${if (ok) "成功" else "失败（0x${Integer.toHexString(lastSetPropError).uppercase()}）"}")
        return ok
    }

    /**
     * 把对焦相关属性的描述符读成人话，一行一个。
     *
     * 0xD08E 是按"紧邻 0xD08D + 值域 6 档"推出来的，官方模块里没有字符串直接指认它。
     * 真机跑一次这个探测，看机身自己报的值域对不对得上，就能复核。
     */
    fun probeAfProperties(): List<String> = listOf(
        "0xD108 对焦点" to PtpConstants.PROP_NIKON_AF_AREA_POINT,
        "0xD08D AF点数" to PtpConstants.PROP_NIKON_AF_POINT_COUNT,
        "0xD08E AF区域模式" to PtpConstants.PROP_NIKON_AF_AREA_MODE
    ).map { (name, code) -> "$name：${describeProp(code)}" }

    /**
     * 读实时取景模式（手持 / 三脚架）。
     *
     * 返回 0=手持、1=三脚架；机身没应答或属性不存在时返回负数。
     */
    fun getLiveViewMode(): Int = getDevicePropValue(PtpConstants.PROP_NIKON_LIVE_VIEW_MODE)

    /**
     * 切实时取景模式（手持 / 三脚架）。
     *
     * 两种模式的对焦机构不同，切换会连带改变界面上"对焦点该怎么控制"：
     * - 手持：相位检测，只能在这 51 个固定点之间跳 → 界面走方向键写 0xD108
     * - 三脚架：对比度检测，AF 框可自由移动 → 界面可以接受点画面定位（0x9205）
     *
     * 和机身菜单一样，**取景进行中切这个多半会被拒**，所以失败时把响应码带回界面提示，
     * 而不是静默失败。
     */
    fun setLiveViewMode(mode: Int): Boolean {
        val ok = setNikonProp(PtpConstants.PROP_NIKON_LIVE_VIEW_MODE, mode.coerceIn(0, 1))
        val name = if (mode == PtpConstants.LIVE_VIEW_MODE_TRIPOD) "三脚架" else "手持"
        logger("设置取景模式=$name（0xD1A0=$mode）${if (ok) "成功" else "失败（0x${Integer.toHexString(lastSetPropError).uppercase()}）"}")
        return ok
    }

    /**
     * 把取景相关的候选属性挨个读描述符，用来在真机上确认
     * "手持/三脚架"到底落在哪个码上（官方模块没有字符串直接指认）。
     */
    fun probeLiveViewModeProperties(): List<String> = listOf(
        "0xD1A0 取景模式" to PtpConstants.PROP_NIKON_LIVE_VIEW_MODE,
        "0xD1A2 取景模式(备)" to PtpConstants.PROP_NIKON_LIVE_VIEW_MODE_ALT,
        "0xD1A1 取景缩放" to PtpConstants.PROP_NIKON_LIVE_VIEW_ZOOM,
        "0xD1A3 取景图像尺寸" to PtpConstants.PROP_NIKON_LIVE_VIEW_SIZE,
        "0x5013 取景控制" to PtpConstants.PROP_NIKON_LIVE_VIEW
    ).map { (name, code) -> "$name：${describeProp(code)}" }

    // 事件回调：拍照完成、属性变更等（在 USB 线程上触发）
    var eventHandler: ((PtpEvent) -> Unit)? = null

    // ------------------------------------------------------------------
    // 取景帧 Bitmap 轮转池（参考 remoteyourcam 的 inBitmap 方案）
    //
    // 为什么必须 inMutable = true：inBitmap 要求目标位图**可写**。
    // 以前只设了 inBitmap 没设 inMutable，解出来的是 immutable Bitmap，
    // 于是从第二帧起 decode 就抛 IllegalArgumentException，被下面的 catch 吞掉后
    // 退化成"每帧新建一块"——复用从来没真正生效过，14fps 下等于每秒扔掉十几 MB 垃圾。
    //
    // 为什么是池子而不是一块：UI 侧是 40ms 去抖后 runOnUiThread 异步引用这块 Bitmap
    // （见 MainActivity 取景循环），录像侧也要在下一帧覆盖前画完。只留一块的话
    // "正在显示的"会被"正在解码的"写穿，画面撕裂。取景间隔 70ms、UI 去抖 40ms，
    // 同时在飞的帧最多 2 个，留 3 块足够。
    // ------------------------------------------------------------------
    private val lvBitmapPool = arrayOfNulls<Bitmap>(3)
    private var lvPoolCursor = 0

    /** 退出取景/断连时调：放掉引用，让这几块大 Bitmap 能被回收 */
    fun clearLiveViewBitmapPool() {
        for (i in lvBitmapPool.indices) lvBitmapPool[i] = null
        lvPoolCursor = 0
    }

    /**
     * 解一帧，尽量复用池子里的 Bitmap。
     * 池子为空或分辨率变了（机身切换取景尺寸）时会自动补新的进去。
     */
    private fun decodeLiveViewJpeg(array: ByteArray, offset: Int, length: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 1
            inMutable = true                       // ★ 没有这行 inBitmap 就是摆设
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        // 从最旧的一块开始试，尺寸对不上就换下一块
        for (i in lvBitmapPool.indices) {
            val idx = (lvPoolCursor + i) % lvBitmapPool.size
            val candidate = lvBitmapPool[idx]
            opts.inBitmap = if (candidate != null && !candidate.isRecycled) candidate else null
            val decoded = try {
                BitmapFactory.decodeByteArray(array, offset, length, opts)
            } catch (e: IllegalArgumentException) {
                null                                // 这块用不了，换下一块
            }
            if (decoded != null) {
                lvBitmapPool[idx] = decoded
                lvPoolCursor = (idx + 1) % lvBitmapPool.size
                return decoded
            }
        }

        // 兜底：池子全对不上，最后一次不作复用要求
        return try {
            BitmapFactory.decodeByteArray(array, offset, length)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 Nikon LiveView 帧。
     * D300 的帧数据在 JPEG 前有 64 字节专有头（D90/D5000 为 128，D7000/D5100 为 384），
     * 头内含大端序的 AF 对焦框坐标。参考 remoteyourcam-usb 的 NikonGetLiveViewImageCommand。
     */
    /**
     * 0x9204 NikonMfDrive —— 手动驱动镜头往前往后微调对焦。
     *
     * 这是"用手机当对焦环"的那条命令：LiveView 下每次给它一个方向和步数，
     * 镜头自己走这么多步，取景画面会跟着变（配合 afDrive 放锁，画面是连续看的）。
     *
     * @param towardClosest true = 往最近方向（flag=1）；false = 往无限远（flag=2）
     * @param steps 步数，1 步非常小；实测按 5~20 步一格比较舒服
     * @return 机身响应码：0x2001 成功 / 0xA00C 到底了 / 0xA00E 走不动 / -1 无响应
     */
    fun mfDrive(towardClosest: Boolean, steps: Int): Int = synchronized(usbLock) {
        drain()
        val flag = if (towardClosest) PtpConstants.MF_DRIVE_TOWARD_CLOSEST
                   else PtpConstants.MF_DRIVE_TOWARD_INFINITY
        val amount = steps.coerceIn(1, 2000)
        val packet = createCommandPacket(PtpConstants.OP_NIKON_MF_DRIVE, flag, amount)
        if (!sendPacket(packet)) return -1
        val response = receiveResponse(3000)
        var code = response?.responseCode ?: -1
        logger(
            "对焦微调 ${if (towardClosest) "近" else "远"} $amount 步 → " +
                (if (code < 0) "TIMEOUT" else "0x${Integer.toHexString(code).uppercase()}")
        )

        // 失败时补两条诊断信息，省得下一轮再来猜：
        // 1) 机身当前对焦模式 —— D300 在 MF 下会直接回 0xA004（Invalid Status），
        //    因为镜头马达归机身管，机身在"手动"时不让 PTP 驱它
        // 2) 换一组 flag 形式（0/1）再试一次，看是不是枚举值口径不同
        if (code != PtpConstants.RESP_OK) {
            val fm = try {
                getDevicePropValue(PtpConstants.PROP_FOCUS_MODE)
            } catch (_: Exception) {
                -1
            }
            val fmText = when (fm) {
                1 -> "MF（不支持马达驱动，需要切 AF-S/AF-C）"
                2 -> "AF-S"
                3 -> "AF-C"
                4 -> "AF-A"
                else -> "值 $fm"
            }
            logger("对焦微调诊断：机身对焦模式 = $fmText")

            clearPipe()
            drain()
            val altFlag = 0x0            // 另一套口径：0=近 / 1=远
            val altPacket = createCommandPacket(
                PtpConstants.OP_NIKON_MF_DRIVE,
                if (towardClosest) altFlag else altFlag + 1,
                amount
            )
            if (sendPacket(altPacket)) {
                val altResp = receiveResponse(2000)
                val altCode = altResp?.responseCode ?: -1
                logger(
                    "对焦微调诊断：改用 flag=0/1 形式 → " +
                        (if (altCode < 0) "TIMEOUT" else "0x${Integer.toHexString(altCode).uppercase()}")
                )
                if (altCode == PtpConstants.RESP_OK) code = altCode
            }
        }
        code
    }

    fun getLiveViewFrame(): LiveViewFrame? = synchronized(usbLock) {
        val packet = createCommandPacket(PtpConstants.OP_NIKON_GET_LIVE_VIEW_IMAGE)
        if (!sendPacket(packet)) return null
        // 官方取景帧缓冲：模式 7 要求 size == 0xE1080(921600)，模式 6 为 0xE1040(921152)，
        // 差值 448 就是帧头。512KB 装不下一整帧，这里给到 1 MiB。
        val data = receiveData(1024 * 1024)
        if (data == null) {
            // receiveData 可能已读到 Response（如 DeviceBusy）
            lastResponse?.let { res ->
                if (res.responseCode == 0x2019) return LiveViewFrame(null, busy = true)
            }
            receiveResponseWithRetry()
            return null
        }
        val response = receiveResponseWithRetry()
        if (response?.responseCode == 0x2019) return LiveViewFrame(null, busy = true)
        if (response?.responseCode != PtpConstants.RESP_OK) return null

        val array = data.array()
        val payloadStart = 12          // PTP 容器头 12 字节
        val payloadLen = data.limit() - payloadStart
        if (payloadLen <= 128) return null

        // --- 解析帧头（大端）---
        var afCenterX = -1f; var afCenterY = -1f; var afW = 0f; var afH = 0f
        var wholeW = 0; var wholeH = 0          // 整幅画面像素尺寸
        var frameW = 0; var frameH = 0          // AF 框像素尺寸（整幅坐标系）
        try {
            val be = ByteBuffer.wrap(array, payloadStart, payloadLen).order(ByteOrder.BIG_ENDIAN)
            val jpegW = be.short.toInt() and 0xFFFF
            val jpegH = be.short.toInt() and 0xFFFF
            wholeW = be.short.toInt() and 0xFFFF
            wholeH = be.short.toInt() and 0xFFFF
            if (jpegW > 0 && jpegH > 0 && wholeW > 0 && wholeH > 0) {
                val multX = jpegW.toFloat() / wholeW
                val multY = jpegH.toFloat() / wholeH
                be.position(payloadStart + 16) // 相对 payload 的偏移 16
                // 先留整幅坐标系的原始值：0x9205 要的就是这个坐标系
                frameW = be.short.toInt() and 0xFFFF
                frameH = be.short.toInt() and 0xFFFF
                val rawCX0 = be.short.toInt() and 0xFFFF
                val rawCY0 = be.short.toInt() and 0xFFFF
                val rawW = frameW * multX
                val rawH = frameH * multY
                val rawCX = rawCX0 * multX
                val rawCY = rawCY0 * multY
                if (rawW > 0 && rawCX >= 0 && rawCY >= 0) {
                    afW = (rawW / jpegW).coerceIn(0f, 1f)
                    afH = (rawH / jpegH).coerceIn(0f, 1f)
                    afCenterX = (rawCX / jpegW).coerceIn(0f, 1f)
                    afCenterY = (rawCY / jpegH).coerceIn(0f, 1f)
                }
            } else {
                wholeW = 0; wholeH = 0
            }
        } catch (e: Exception) { /* 帧头解析失败不影响出图 */ }

        // --- JPEG 位于 payload 偏移 64（D300）---
        var jpgOffset = payloadStart + 64
        // 兜底：若固定偏移处不是 SOI，退回扫描
        if (jpgOffset + 1 >= data.limit() ||
            array[jpgOffset] != 0xFF.toByte() || array[jpgOffset + 1] != 0xD8.toByte()) {
            jpgOffset = payloadStart
            while (jpgOffset < data.limit() - 1) {
                if (array[jpgOffset] == 0xFF.toByte() && array[jpgOffset + 1] == 0xD8.toByte()) break
                jpgOffset++
            }
            if (jpgOffset >= data.limit() - 1) return null
        }

        val jpgLen = data.limit() - jpgOffset
        if (jpgLen <= 0) return null

        val bitmap = decodeLiveViewJpeg(array, jpgOffset, jpgLen)
        return LiveViewFrame(bitmap, busy = false, afCenterX, afCenterY, afW, afH, wholeW, wholeH, frameW, frameH)
    }

    /** 取存储卡列表（0x1004 GetStorageIDs）。D300 双卡槽会回 0x11010000 / 0x11220000 这类 ID。 */
    /**
     * 0x90CA GetVendorPropCodes：让机身自己报"我支持哪些厂商属性码"。
     *
     * 以前是盲扫 0xD000–0xD1FF（96 项无应答才熔断），既慢又可能漏掉范围外的码。
     * 这条命令一次就能拿到官方清单；D300 固件调度表里确实有它的处理函数
     * （`0x189FB8 → 0xA16C`），所以在 D300 上是可用的。
     *
     * 数据格式（对应 libgphoto2 的 `ptp_unpack_uint16_t_array`）：
     * PTP 数据头 12 字节 + u16 个数 + u16[个数] 属性码。
     *
     * ⚠️ libgphoto2 注明个别新机型（V1/J1/J2）调这条会把相机搞崩，
     * 所以这里只允许"按需调一次"，绝对不能放进轮询。
     */
    fun getVendorPropCodes(): IntArray? = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_NIKON_GET_VENDOR_PROP_CODES)
        if (!sendPacket(packet)) return null
        val data = receiveData(4096) ?: run { receiveResponseWithRetry(1); return null }
        if (receiveResponseWithRetry(1)?.responseCode != PtpConstants.RESP_OK) return null
        try {
            data.order(ByteOrder.LITTLE_ENDIAN)
            data.position(12)
            if (data.remaining() < 2) return IntArray(0)
            val count = data.short.toInt() and 0xFFFF
            val n = minOf(count, data.remaining() / 2)
            IntArray(n) { data.short.toInt() and 0xFFFF }
        } catch (e: Exception) {
            null
        }
    }

    /** 缓存第一个存储 ID：标准 InitiateCapture 需要真实 ID，不能拿 0xFFFFFFFF 糊弄 */
    @Volatile
    private var cachedStorageId: Int = 0

    /**
     * 拿一个可用的 StorageID（给标准 0x100E 用）。
     *
     * 机身对 `0xFFFFFFFF` 会回 **0x2008 InvalidStorageId** —— 这一条实测出来的错误码
     * 正好说明：0x100E 本身是被支持的，只是参数必须填真实的卡 ID（真机是 0x10001）。
     */
    fun firstStorageId(): Int {
        cachedStorageId.takeIf { it != 0 }?.let { return it }
        val ids = getStorageIds()
        val sid = ids?.firstOrNull() ?: 0
        if (sid != 0) cachedStorageId = sid
        return sid
    }

    fun getStorageIds(): IntArray? = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_GET_STORAGE_IDS)
        if (!sendPacket(packet)) return null
        val data = receiveData(4096) ?: run { receiveResponseWithRetry(1); return null }
        if (receiveResponseWithRetry(1)?.responseCode != PtpConstants.RESP_OK) return null
        return try {
            data.position(12)
            if (data.remaining() < 4) return IntArray(0)
            val count = data.int
            val n = Math.min(count, data.remaining() / 4)
            IntArray(n) { data.int }
        } catch (e: Exception) { null }
    }

    fun getObjectHandles(parentHandle: Int = 0): IntArray? =
        getObjectHandlesIn(0xFFFFFFFF.toInt(), parentHandle)

    /**
     * 读一张卡的容量信息（0x1005 GetStorageInfo）。
     *
     * 数据阶段布局（PTP 规范，全部小端）：
     * `StorageType u16 / FileSystemType u16 / AccessCapability u16 /
     *  MaxCapacity u64 / FreeSpaceInBytes u64 / FreeSpaceInImages u32 /
     *  StorageDescription PTPString / VolumeLabel PTPString`
     */
    fun getStorageInfo(storageId: Int): StorageInfo? = synchronized(usbLock) {
        try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_GET_STORAGE_INFO, storageId)
            if (!sendPacket(packet)) return null
            val data = receiveData(4096) ?: run { receiveResponseWithRetry(1); return null }
            if (receiveResponseWithRetry(1)?.responseCode != PtpConstants.RESP_OK) return null

            data.position(12)
            data.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val storageType = data.short.toInt() and 0xFFFF
            data.short // FileSystemType，界面用不到
            val accessCap = data.short.toInt() and 0xFFFF
            val maxCapacity = data.long
            val freeSpace = data.long
            val freeImages = data.int
            // 机身报的字符串偶尔夹着控制字符/换行（实测卷标读到过 "\nSC_4233.NEF"），
            // 直接上屏会在卡片标题里多出一个空行
            val desc = readPtpString(data).trim { it < ' ' }
            val label = readPtpString(data).trim { it < ' ' }
            return StorageInfo(
                storageId = storageId,
                storageType = storageType,
                accessCapability = accessCap,
                maxCapacity = maxCapacity,
                freeSpace = freeSpace,
                freeImages = freeImages,
                description = desc,
                volumeLabel = label
            )
        } catch (e: Exception) {
            android.util.Log.e("PTP_RX", "GetStorageInfo 失败：${e.message}")
            return null
        }
    }

    /**
     * 格式化存储卡（0x100F FormatStore）。
     *
     * **会删光照片**，调用方必须先让用户二次确认。机身格式化期间可能十几秒不应答，
     * 所以这里单独给 30 秒超时，且失败时不再走"重试 3 轮"的通用恢复序列
     * （格式化重发一次等于再赌一次，绝不能自动重试）。
     *
     * @return PTP 响应码；-1 = 发不出去/没等到响应
     */
    fun formatStore(storageId: Int): Int = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_FORMAT_STORE, storageId)
        if (!sendPacket(packet)) {
            logger("格式化：命令发送失败")
            return -1
        }
        val response = receiveResponse(30_000)
        val code = response?.responseCode ?: -1
        if (code == PtpConstants.RESP_OK) {
            logger("格式化：机身已完成")
            // 格式化后机身状态要重新同步，否则紧接着的列举会读到旧缓存
            for (i in 0 until 8) {
                if (deviceReady()) break
                Thread.sleep(400)
            }
        }
        return code
    }

    /**
     * 列举对象句柄（0x1007 GetObjectHandles）。
     *
     * @param storageId 存储卡 ID；0xFFFFFFFF = 全部卡
     * @param parentHandle 父对象句柄；0 = 各卡的顶层
     *
     * ⚠️ D300 实测坑：storage=全部卡 + parent=0 的"标准问法"要么回空、要么 0x2013。
     * 正确姿势是**逐卡列举**（storageId=具体卡 ID），顶层用 parent=0，
     * 下钻子文件夹时 parent=文件夹句柄（storage 仍指定该卡）。
     */
    fun getObjectHandlesIn(storageId: Int, parentHandle: Int): IntArray? = synchronized(usbLock) {
        drain()
        android.util.Log.d("PTP_TX", "COMMAND: Get Object Handles (0x1007) storage=0x${Integer.toHexString(storageId)} parent=$parentHandle")
        val packet = createCommandPacket(PtpConstants.OP_GET_OBJECT_HANDLES, storageId, 0, parentHandle)
        if (!sendPacket(packet)) return null

        val data = receiveData(256 * 1024)
        if (data == null) {
            android.util.Log.e("PTP_RX", "列举对象：机身未返回数据")
            receiveResponseWithRetry(1)
            return null
        }

        val response = receiveResponseWithRetry(1)
        if (response?.responseCode != PtpConstants.RESP_OK) {
            android.util.Log.e("PTP_RX", "Get Object Handles: Failed with 0x${Integer.toHexString(response?.responseCode ?: 0)}")
            return null
        }

        try {
            data.position(12)
            if (data.remaining() < 4) return IntArray(0)

            val count = data.int
            val actualCount = Math.min(count, data.remaining() / 4)
            val handles = IntArray(actualCount)
            for (i in 0 until actualCount) handles[i] = data.int
            return handles
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * PTP 字符串的编码：开头 1 字节是字符数（含结尾的 0000），其后是 UTF-16LE，
     * 最后一个字符是终止符，读完要吞掉。
     */
    private fun readPtpString(buf: ByteBuffer): String {
        val count = buf.get().toInt() and 0xFF
        if (count == 0) return ""
        val chars = CharArray(count - 1)
        for (i in 0 until count - 1) chars[i] = buf.short.toInt().toChar()
        buf.short // 终止符
        return String(chars)
    }

    /** 读单个对象的信息（0x1008 GetObjectInfo） */
    fun getObjectInfo(handle: Int): PtpObjectInfo? = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_GET_OBJECT_INFO, handle)
        if (!sendPacket(packet)) return null

        val data = receiveData(8192)
        if (data == null) {
            receiveResponseWithRetry(1)
            return null
        }
        receiveResponseWithRetry(1)

        return try {
            data.position(12)
            data.int               // StorageID
            val format = data.short.toInt() and 0xFFFF
            data.short             // ProtectionStatus
            val byteSize = data.int.toLong() and 0xFFFFFFFFL
            val thumbFormat = data.short.toInt() and 0xFFFF
            data.int               // ThumbCompressedSize
            data.int               // ThumbPixWidth
            data.int               // ThumbPixHeight
            val pixWidth = data.int
            val pixHeight = data.int
            data.int               // ImageBitDepth
            data.int               // ParentObject
            data.short             // AssociationType
            data.int               // AssociationDesc
            data.int               // SequenceNumber
            val filename = readPtpString(data)
            val captureDate = readPtpString(data)
            PtpObjectInfo(handle, filename, byteSize, format, captureDate, thumbFormat, pixWidth, pixHeight)
        } catch (e: Exception) {
            android.util.Log.w("PTP_RX", "GetObjectInfo 解析失败: ${e.message}")
            null
        }
    }

    /** 取缩略图（0x100A GetThumb）。D300 回的是机身嵌的 JPEG，通常 160×120，够做网格。 */
    fun getThumb(handle: Int): Bitmap? = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_GET_THUMB, handle)
        if (!sendPacket(packet)) return null

        val data = receiveData(512 * 1024)
        if (data == null) {
            val resp = receiveResponseWithRetry(1)
            logger("GetThumb 0x${Integer.toHexString(handle)}：无数据（响应 0x${Integer.toHexString(resp?.responseCode ?: 0).uppercase()}）")
            return null
        }
        val resp = receiveResponseWithRetry(1)
        if (resp?.responseCode != PtpConstants.RESP_OK) {
            logger("GetThumb 0x${Integer.toHexString(handle)}：被拒（0x${Integer.toHexString(resp?.responseCode ?: 0).uppercase()}）")
            return null
        }

        val limit = data.limit()
        if (limit <= 12) {
            logger("GetThumb 0x${Integer.toHexString(handle)}：数据为空（${limit}B）")
            return null
        }
        return try {
            BitmapFactory.decodeByteArray(data.array(), 12, limit - 12)
                ?: run {
                    // 解不出来时打出文件头几个字节，方便判断是 Nikon 私有头还是非 JPEG
                    val head = data.array().copyOfRange(12, minOf(limit, 28).coerceAtMost(data.array().size))
                    logger("GetThumb 0x${Integer.toHexString(handle)}：解码失败，头部=${head.joinToString(" ") { "%02X".format(it) }}")
                    null
                }
        } catch (e: Exception) {
            android.util.Log.w("PTP_RX", "缩略图解码失败: ${e.message}")
            null
        }
    }

    /**
     * 取整个原文件（0x1009 GetObject）。
     * D300 一张 NEF 十几 MB，走 USB bulk 要好几秒 —— **必须在后台线程调用**。
     * @param expectedSize 来自 [getObjectInfo]，用来按需分配，避免固定开 24MB 缓冲。
     */
    fun getObject(handle: Int, expectedSize: Long = 0): ByteArray? = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_GET_OBJECT, handle)
        if (!sendPacket(packet)) return null

        val maxSize = if (expectedSize > 0) {
            (expectedSize + 4096).coerceAtMost(64L * 1024 * 1024).toInt()
        } else {
            24 * 1024 * 1024
        }

        val data = receiveData(maxSize)
        if (data == null) {
            val resp = receiveResponseWithRetry(1)
            logger("GetObject 0x${Integer.toHexString(handle)}：无数据（响应 0x${Integer.toHexString(resp?.responseCode ?: 0).uppercase()}）")
            return null
        }
        val resp = receiveResponseWithRetry(1)
        if (resp?.responseCode != PtpConstants.RESP_OK) {
            logger("GetObject 0x${Integer.toHexString(handle)}：被拒（0x${Integer.toHexString(resp?.responseCode ?: 0).uppercase()}）")
            return null
        }

        val limit = data.limit()
        if (limit <= 12) return null
        return data.array().copyOfRange(12, limit)
    }

    /**
     * 只读对象的一段（0x101B GetPartialObject）。
     *
     * @param offset 文件内偏移（64 位，实际传低 32 位 + 高 32 位两个参数）
     * @param maxBytes 最多读多少字节
     * @return 读到的字节；机身不支持或读失败时为 null
     */
    fun getPartialObject(handle: Int, offset: Long, maxBytes: Int): ByteArray? = synchronized(usbLock) {
        drain()
        val low = (offset and 0xFFFFFFFFL).toInt()
        val high = (offset ushr 32).toInt()
        val packet = createCommandPacket(
            PtpConstants.OP_GET_PARTIAL_OBJECT, handle, low, high, maxBytes
        )
        if (!sendPacket(packet)) return null

        val data = receiveData(maxBytes + 4096)
        if (data == null) {
            val resp = receiveResponseWithRetry(1)
            logger("GetPartialObject 0x${Integer.toHexString(handle)}：无数据（响应 0x${Integer.toHexString(resp?.responseCode ?: 0).uppercase()}）")
            return null
        }
        val resp = receiveResponseWithRetry(1)
        if (resp?.responseCode != PtpConstants.RESP_OK) {
            logger("GetPartialObject 0x${Integer.toHexString(handle)}：被拒（0x${Integer.toHexString(resp?.responseCode ?: 0).uppercase()}）")
            return null
        }

        val limit = data.limit()
        if (limit <= 12) return null
        data.array().copyOfRange(12, limit)
    }

    /**
     * 删除卡上的一个对象（标准 PTP `0x100B DeleteObject`）。
     *
     * ⚠️ 这台机身不一定真的支持这条命令 —— 见 [supportsOperation]：
     * 不支持的命令发下去可能**连失败回应都没有**，而 usbLock 一直被占着，
     * 表现为"按钮点了没反应、之后所有命令也发不出去"。
     * 所以调用方要先用 [supportsOperation] 问一次，别盲目发。
     *
     * @return Pair(是否成功, 说明) —— 说明直接给界面显示，别再让人去翻日志
     */
    fun deleteObject(handle: Int): Pair<Boolean, String> = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_DELETE_OBJECT, handle)
        if (!sendPacket(packet)) return false to "命令没发出去"
        val resp = receiveResponseWithRetry(3, timeoutMs = DELETE_RESP_TIMEOUT_MS)
        val code = resp?.responseCode
        val ok = code == PtpConstants.RESP_OK
        val mark = if (code == null) {
            // 没回应基本就是机身不认这条命令：端点很可能已经被拖到异常状态，先 clearing halt 救一把
            clearPipe()
            "机身没有任何回应（多半是不支持这条命令）"
        } else {
            describeResponse(code)
        }
        logger(
            if (ok) "已删除对象 0x${Integer.toHexString(handle).uppercase()}"
            else "删除对象 0x${Integer.toHexString(handle).uppercase()} 失败：$mark"
        )
        ok to mark
    }

    /** 把删除失败的那几个常见响应码说成人话（直接给界面显示） */
    private fun describeResponse(code: Int): String = when (code) {
        PtpConstants.RESP_OK -> "OK"
        PtpConstants.RESP_DEVICE_BUSY -> "机身正忙"
        0x200F -> "这张被机身保护了（先在机身上解除保护）"
        0x2011 -> "句柄不存在（列表可能过期了，退回相册再试）"
        0x2005, PtpConstants.RESP_PARAMETER_NOT_SUPPORTED -> "机身不接受这条命令"
        0x2001 -> "机身拒绝执行"
        else -> "0x${Integer.toHexString(code).uppercase()}"
    }

    // ------------------------------------------------------------------
    // 写文件到机身（SendObjectInfo + SendObject）
    //
    // 目前只服务 NCP 滤镜导入：尼康把"个人设定优化校准"存在**卡根目录**的
    // PICCON01.NCP … PICCON99.NCP 上（说明书第 158–160 页：插槽 1–99），
    // 机身那边再走 管理优化校准 → 载入/保存 → 复制到相机，装进 C-1…C-9。
    // 所以 App 这边要做的只是"把文件放到卡根目录下"，不需要机身额外的私有命令。
    // ------------------------------------------------------------------

    /**
     * 把一段字节写成卡上的一个文件。
     *
     * @param parentObject 0 = 卡根目录（机身把 PICCON 文件放在根上，不是 DCIM 里）
     * @return 新对象的句柄；失败返回 -1（机身不支持写 / 拒绝，看日志里的响应码）
     */
    fun sendFileToStorage(
        fileName: String,
        bytes: ByteArray,
        storageId: Int,
        parentObject: Int = 0
    ): Int = synchronized(usbLock) {
        try {
            drain()
            val info = buildObjectInfo(storageId, parentObject, fileName, bytes.size)
            if (!sendPacket(createCommandPacket(PtpConstants.OP_SEND_OBJECT_INFO, storageId, parentObject))) return -1
            if (!sendPacket(createDataPacket(PtpConstants.OP_SEND_OBJECT_INFO, info))) return -1
            val r1 = receiveResponse(5000)
            if (r1?.responseCode != PtpConstants.RESP_OK) {
                logger("SendObjectInfo 被拒（${r1?.let { "0x${Integer.toHexString(it.responseCode).uppercase()}" } ?: "TIMEOUT"}）")
                return -1
            }
            // 响应参数 [StorageID, ParentObject, ObjectHandle]
            val handle = r1.params.getOrElse(2) { 0 }
            android.util.Log.d("PTP_TX_CMD", "SendObjectInfo OK：$fileName → handle 0x${Integer.toHexString(handle).uppercase()}")

            drain()
            if (!sendPacket(createCommandPacket(PtpConstants.OP_SEND_OBJECT))) return -1
            if (!sendPacket(createDataPacket(PtpConstants.OP_SEND_OBJECT, bytes))) return -1
            val r2 = receiveResponse(15000)
            if (r2?.responseCode != PtpConstants.RESP_OK) {
                logger("SendObject 被拒（${r2?.let { "0x${Integer.toHexString(it.responseCode).uppercase()}" } ?: "TIMEOUT"}）")
                return -1
            }
            logger("$fileName 已写入存储卡（${bytes.size} 字节）")
            handle
        } catch (e: Exception) {
            logger("写文件出错：${e.message}")
            -1
        }
    }

    /**
     * 拼一个 ObjectInfo 数据集（PTP 规范 §5.3.2 的字段顺序，一个都不能挪）。
     * 日期/关键字都留空：PTP 允许空串（长度字节写 0），机身自己会填当前时间。
     */
    private fun buildObjectInfo(storageId: Int, parentObject: Int, fileName: String, size: Int): ByteArray {
        val nameField = ByteArray(1 + (fileName.length + 1) * 2)
        nameField[0] = (fileName.length + 1).toByte()
        for (i in fileName.indices) {
            val c = fileName[i].code
            nameField[1 + i * 2] = (c and 0xFF).toByte()
            nameField[2 + i * 2] = ((c ushr 8) and 0xFF).toByte()
        }

        val buf = java.nio.ByteBuffer.allocate(52 + nameField.size + 3).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(storageId)                                        // StorageID
        buf.putShort(PtpConstants.FORMAT_UNDEFINED.toShort())        // ObjectFormatCode
        buf.putShort(0)                                              // ProtectionStatus
        buf.putInt(size)                                             // ObjectCompressedSize
        buf.putShort(0)                                              // ThumbFormat
        buf.putInt(0)                                                // ThumbCompressedSize
        buf.putInt(0)                                                // ThumbPixWidth
        buf.putInt(0)                                                // ThumbPixHeight
        buf.putInt(0)                                                // ImagePixWidth
        buf.putInt(0)                                                // ImagePixHeight
        buf.putInt(0)                                                // ImageBitDepth
        buf.putInt(parentObject)                                     // ParentObject
        buf.putShort(0)                                              // AssociationType
        buf.putInt(0)                                                // AssociationDesc
        buf.putInt(0)                                                // SequenceNumber
        buf.put(nameField)                                           // Filename（UTF-16LE + 结尾 0）
        buf.put(0.toByte())                                          // CaptureDate（空串）
        buf.put(0.toByte())                                          // ModificationDate（空串）
        buf.put(0.toByte())                                          // Keywords（空串）
        return buf.array()
    }

    /**
     * 递归展开目录，收集所有照片句柄。
     *
     * D300 的照片不是放在根层的，而是 存储 → DCIM → 100NCD300 → DSC_xxxx.NEF 这样的层级。
     *
     * ⚠️ 实测坑（2026-10-20）：D300 **不认** storage=全部卡 + parent=0 的标准问法
     * （先回空列表、再回 0x2013），必须先 0x1004 拿到存储卡 ID，**逐卡列举**。
     * 所以遍历队列里带的是「(存储卡, 父句柄)」二元组，下钻时始终保持卡的上下文。
     *
     * 另注意：NEF 的 PTP 格式码是 0x3000（UNDEFINED），别把它当垃圾过滤掉；
     * 真正的目录是 0x3001（Association）。
     */
    fun collectPhotoHandles(): IntArray = collectPhotoInfos().map { it.handle }.toIntArray()

    /**
     * 按**文件夹（相册）**分组列举照片。
     *
     * D300 的卡上是 `存储 → DCIM → 100NCD300 → DSC_xxxx.NEF` 这样的层级，
     * 机身拍满 9999 张会自己新建 `101NCD300`，所以"卡里有几个文件夹"就是"有几个相册"。
     * 用户要的是"默认看最新的那个、能切换"，所以这里一次遍历就把分组信息带回来 ——
     * 切换相册时**不再重新列举**（那要再付几百次 GetObjectInfo）。
     *
     * 只返回**里面有照片**的文件夹（空文件夹机身也会建，列出来只会多一个空页）。
     */
    fun collectPhotoFolders(): List<PhotoFolder> {
        // 遍历队列：三元组 (storageId, folderHandle, 我的名字, 父目录的名字)
        data class Dir(val sid: Int, val handle: Int, val name: String, val parentName: String)
        val dirs = ArrayDeque<Dir>()

        val storages = getStorageIds()
        if (storages != null && storages.isNotEmpty()) {
            storages.forEach { dirs.addLast(Dir(it, 0, "", "")) }
        } else {
            // 拿不到卡列表就退回"全部卡 + 顶层"的问法，至少别空手而归
            dirs.addLast(Dir(0xFFFFFFFF.toInt(), 0, "", ""))
        }

        // key = "卡:文件夹句柄"，保持发现顺序；末尾统一转成 PhotoFolder
        class Bucket(
            val sid: Int,
            val handle: Int,
            val name: String,
            val parentName: String,
            val photos: MutableList<PtpObjectInfo> = mutableListOf()
        )
        val groups = LinkedHashMap<String, Bucket>()
        val seen = mutableSetOf<Int>()

        var level = 0
        var inLevel = dirs.size
        var nextLevel = 0
        var total = 0
        while (dirs.isNotEmpty() && level < 5 && total < 2000) {
            val dir = dirs.removeFirst()
            inLevel--

            val handles = getObjectHandlesIn(dir.sid, dir.handle)
            if (handles == null) {
                logger("collectPhotoFolders：卡 0x${Integer.toHexString(dir.sid)} 目录 ${dir.handle} 列举失败，跳过")
            } else {
                for (h in handles) {
                    val info = getObjectInfo(h) ?: continue
                    if (info.format == PtpConstants.OBJ_FORMAT_ASSOCIATION) {
                        // 子目录：同一张卡内下钻，名字沿用 GetObjectInfo 报的文件名
                        dirs.addLast(Dir(dir.sid, h, info.filename, dir.name))
                        nextLevel++
                    } else if (seen.add(h)) {
                        val key = "${dir.sid}:${dir.handle}"
                        groups.getOrPut(key) { Bucket(dir.sid, dir.handle, dir.name, dir.parentName) }
                            .photos.add(info)
                        total++
                    }
                }
            }

            if (inLevel == 0) {
                level++
                inLevel = nextLevel
                nextLevel = 0
            }
        }

        val result = groups.values.filter { it.photos.isNotEmpty() }
            .map { PhotoFolder(it.sid, it.handle, it.name, it.parentName, it.photos) }
        logger("collectPhotoFolders：${result.size} 个相册，共 ${result.sumOf { it.photos.size }} 张")
        return result
    }

    /**
     * 列举全部照片，**连同已经读到的 ObjectInfo 一起返回**。
     *
     * 为什么要有这个版本：0x1007 列举只能拿到句柄，要知道文件名/格式就必须再发一次
     * 0x1008 GetObjectInfo —— 而下面这段为了区分子目录，**本来就已经对每一项读过一遍 info**。
     * 以前调用方（相册）拿走句柄后又自己遍历读一遍，等于每张照片付两次往返；
     * 卡里几百张时就是几百次白跑的事务，直接体现在"相册加载慢、缩略图一张张蹦"。
     */
    fun collectPhotoInfos(): List<PtpObjectInfo> {
        val photos = ArrayList<PtpObjectInfo>()
        val seen = mutableSetOf<Int>()       // 去重防并发双跑
        val dirs = ArrayDeque<Pair<Int, Int>>()   // (storageId, folderHandle)

        val storages = getStorageIds()
        if (storages != null && storages.isNotEmpty()) {
            storages.forEach { dirs.add(it to 0) }
            logger("collectPhotoHandles：存储卡 ${storages.joinToString(" ") { "0x${Integer.toHexString(it)}" }}")
        } else {
            // 拿不到卡列表就退回"全部卡 + 顶层"的问法，至少别空手而归
            dirs.add(0xFFFFFFFF.toInt() to 0)
            logger("collectPhotoHandles：GetStorageIDs 失败，退回全卡列举")
        }

        var level = 0
        var inLevel = dirs.size
        var nextLevel = 0
        while (dirs.isNotEmpty() && level < 5 && photos.size < 2000) {
            val (storageId, parent) = dirs.removeFirst()
            inLevel--

            val handles = getObjectHandlesIn(storageId, parent)
            if (handles == null) {
                logger("collectPhotoHandles：卡 0x${Integer.toHexString(storageId)} 目录 $parent 列举失败，跳过")
            } else {
                logger("collectPhotoHandles：卡 0x${Integer.toHexString(storageId)} 目录 $parent 下 ${handles.size} 项")
                for (h in handles) {
                    val info = getObjectInfo(h)
                    if (info == null) {
                        logger("collectPhotoHandles：句柄 $h GetObjectInfo 失败，跳过")
                        continue
                    }
                    logger("collectPhotoHandles：  0x${Integer.toHexString(h)} fmt=0x${Integer.toHexString(info.format)} ${info.filename}")
                    if (info.format == PtpConstants.OBJ_FORMAT_ASSOCIATION) {
                        dirs.addLast(storageId to h)   // 子目录：同一张卡内下钻
                        nextLevel++
                    } else if (seen.add(h)) {
                        photos.add(info)               // 非目录即照片（含 0x3000 的 NEF）
                    }
                }
            }

            if (inLevel == 0) {
                level++
                inLevel = nextLevel
                nextLevel = 0
            }
        }
        logger("collectPhotoHandles: ${photos.size} 张")
        return photos
    }

    /**
     * 探测机身是否已经处于取景状态：试着取一帧，能拿到就说明已经在取景。
     *
     * 意义：D300 上 0x9201 StartLiveView 一直回 0xA004（Invalid Status），但用户在机身上
     * 按 Lv 键是能正常进取景的 —— 说明取景通道本身是通的，卡住的是"由 App 触发进入"这一步。
     * 那就先探一眼：如果机身已经在取景，直接接管画面，不必再发 0x9201。
     */
    /**
     * 探一眼机身是不是已经在取景。
     *
     * ⚠️ 首片数据的超时故意压到 1.2 秒：机身**不在**取景时，0x9203 多半什么都不回，
     * 原来 5 秒的首片超时就意味着"每次点开取景都要先白等 5 秒"——
     * 这是"LV 激活慢"最主要的一处（正常取景中数据是毫秒级就到的，短超时不会误判）。
     */
    fun probeLiveViewActive(): Boolean = synchronized(usbLock) {
        return try {
            drain()
            val p = createCommandPacket(PtpConstants.OP_NIKON_GET_LIVE_VIEW_IMAGE)
            if (!sendPacket(p)) return false
            val data = receiveData(1024 * 1024, firstTimeoutMs = 1200)
            val resp = receiveResponse(3000)
            val ok = resp?.responseCode == PtpConstants.RESP_OK && data != null && data.limit() > 64
            if (ok) logger("探测到机身已在取景状态（0x9203 返回 ${data!!.limit()} 字节）")
            ok
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // 取景画面放大（0xD1A3 LiveViewImageZoomRatio）
    //
    // 手动对焦要靠放大看细节：机身把取景画面裁一块放大后回传，比在手机上
    // 把小画面插值拉伸清楚得多（那是放大马赛克，不是放大细节）。
    // 档位各机型不同 → 一律读描述符拿机身自述的合法值，绝不硬编。
    // ------------------------------------------------------------------

    /** 0xD1A3 的合法档位（升序），读过一次就缓存 —— 每按一次都读描述符太占 USB */
    private var lvZoomStepsCache: List<Int> = emptyList()

    /**
     * App 侧缓存的当前放大档位（0 = 全画面）。
     *
     * 取景一开就写回 0 并同步这里 —— 机身会记住上次放大到几倍，
     * 不归位的话下次开取景直接就是放大的画面，而界面上还写着"全画面"。
     */
    @Volatile
    var lvZoomValue: Int = 0

    /** 整幅画面在**未放大**时的像素宽：拿它和当前帧的整幅宽比，就能量出真实倍率 */
    private var lvWholeBaseW = 0

    /**
     * 画面当前放大到几倍（1 = 没放大）。
     *
     * 优先用机身帧头量出来的真值（整幅宽缩了多少就是放大了多少）；
     * 量不出来时退回"档位值 = 倍率"—— D300 说明书：手持最大 3 倍、三脚架最大 13 倍，
     * 档位值正好是 1/2/3… 这样的倍率序列。
     */
    fun lvZoomScale(): Float {
        if (lvZoomValue <= 0) return 1f
        val measured = if (lvWholeBaseW > 0 && lastWholeWidth > 0)
            lvWholeBaseW.toFloat() / lastWholeWidth.toFloat() else 1f
        return if (measured > 1.05f) measured else lvZoomValue.toFloat().coerceAtLeast(1f)
    }

    /** 机身自述的放大档位；读不到描述符时退回 D300 系常见的一组 */
    fun lvZoomSteps(): List<Int> {
        if (lvZoomStepsCache.isNotEmpty()) return lvZoomStepsCache
        val fromBody = try {
            readPropDesc(PtpConstants.PROP_NIKON_LV_ZOOM_RATIO)?.allowed?.map { it.toInt() }?.sorted()
        } catch (_: Exception) {
            null
        }
        lvZoomStepsCache = if (!fromBody.isNullOrEmpty()) fromBody else listOf(0, 2, 4, 6, 7)
        return lvZoomStepsCache
    }

    /** 当前放大档位（-1 = 读不到） */
    fun currentLvZoom(): Int {
        return try {
            val v = getDevicePropValue(PtpConstants.PROP_NIKON_LV_ZOOM_RATIO)
            if (v == -999999) -1 else v
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * 放大 / 缩小一档。
     *
     * @return 写入后的档位值；机身拒绝或已在端点时返回原值，-1 = 完全读不到。
     */
    fun stepLvZoom(up: Boolean): Int {
        val steps = lvZoomSteps()
        val cur = currentLvZoom()
        if (cur < 0) return -1
        val idx = steps.indexOf(cur)
        // 当前值不在机身给的档位里（比如刚开取景机身报了个中间态）：按大小就近落档
        val base = if (idx >= 0) idx else steps.indexOfFirst { it >= cur }.let { if (it < 0) steps.size - 1 else it }
        val next = (base + if (up) 1 else -1).coerceIn(0, steps.size - 1)
        if (next == base && idx >= 0) return cur          // 已经到头
        return setLvZoom(steps[next], cur)
    }

    /**
     * 直接跳到某一档放大倍率（二级菜单里选百分比用）。
     *
     * @return 写入后的档位值；写入失败返回 [fallback]（通常是原值），-1 = 完全读不到
     */
    fun setLvZoom(target: Int, fallback: Int = lvZoomValue): Int {
        val ok = try {
            setDevicePropValue(PtpConstants.PROP_NIKON_LV_ZOOM_RATIO, target, size = 1)
        } catch (_: Exception) {
            false
        }
        val now = if (ok) target else fallback
        if (ok) lvZoomValue = target
        return now
    }

    fun getDevicePropDesc(propCode: Int): ByteArray? {
        try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_GET_DEVICE_PROP_DESC, propCode)
            if (!sendPacket(packet)) return null
            val data = receiveData(16384) ?: return null
            receiveResponseWithRetry()
            
            val limit = data.limit()
            if (limit < 12) return null
            return data.array().copyOfRange(0, limit)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 结构化地读一个属性的描述符。
     *
     * 个人设定（Custom Settings）那批属性在官方模块里是查表用的，静态反编译
     * 拿不到"属性码 → 菜单名"的对应（它们在代码里不是立即数）。所以只能反过来：
     * 先问机身自己"你这个属性是什么类型、现在几、能取哪些值"，再据此命名。
     * [describeProp] 是把这些信息压成一行给人读，这里则保留原结构给 UI 用。
     */
    fun readPropDesc(propCode: Int): PropDesc? {
        val raw = getDevicePropDesc(propCode) ?: return null
        return try {
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            b.position(12)
            b.short                                    // 属性码
            val dataType = b.short.toInt() and 0xFFFF
            val getSet = b.get().toInt() and 0xFF

            val valueSize = when (dataType) {
                0x0001, 0x0002 -> 1
                0x0003, 0x0004 -> 2
                0x0005, 0x0006 -> 4
                else -> return null                    // 字符串/复合类型，界面不做
            }

            fun readOne(): Long = when (valueSize) {
                // 有符号类型要保留符号：闪光补偿 0xD126 是 INT8，负档位
                // 若按无符号读会变成 250..255，菜单里就成了"看不懂的码"。
                1 -> if (dataType == 0x0001) b.get().toLong() else b.get().toLong() and 0xFF
                2 -> if (dataType == 0x0003) b.short.toLong() else b.short.toLong() and 0xFFFF
                4 -> if (dataType == 0x0005) b.int.toLong() else b.int.toLong() and 0xFFFFFFFFL
                else -> b.long
            }

            val factory = readOne()
            val current = readOne()
            val formFlag = b.get().toInt() and 0xFF

            val allowed = ArrayList<Long>()
            when (formFlag) {
                0x01 -> {
                    val min = readOne(); val max = readOne(); val step = readOne()
                    val s = if (step <= 0) 1 else step
                    var v = min
                    // 上限兜底：值域异常大的属性（比如 0..65535）不该枚举几百项
                    while (v <= max && allowed.size < 64) { allowed.add(v); v += s }
                }
                0x02 -> {
                    val n = b.short.toInt() and 0xFFFF
                    repeat(n.coerceAtMost(64)) { allowed.add(readOne()) }
                }
            }

            PropDesc(
                code = propCode,
                dataType = dataType,
                writable = getSet != 0,
                factoryValue = factory,
                currentValue = current,
                formFlag = formFlag,
                allowed = allowed
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 批量扫描一段属性码，返回机身有应答的那些。
     *
     * 不存在的码机身会直接回「属性码无效」，通常很快；但万一某一段机身不答
     * （等着超时，几秒一个），扫 512 个就会拖到几十分钟。所以加一道连续空应答
     * 熔断：连续 [maxMisses] 个没回应就认为这段没了，直接收工。
     */
    /**
     * 按机身自己给的码清单读属性描述符（配合 [getVendorPropCodes]）。
     *
     * 盲扫 0xD000..0xD1FF 要做 512 次事务，每次碰到不存在的码都得等机身回
     * "属性码无效"；D300 用 0x90CA 报出来的是 **185 项**，一次询问 + 185 次读取就够了。
     * 连续 12 个码读不到就判定会话已经不稳，收工（别硬问，那会把端点拖进 STALL）。
     */
    fun scanProps(codes: IntArray, onProgress: ((Int, Int) -> Unit)? = null): List<PropDesc> {
        val out = ArrayList<PropDesc>()
        var done = 0
        var misses = 0
        for (code in codes) {
            val d = readPropDesc(code)
            if (d != null) {
                out.add(d)
                misses = 0
            } else {
                if (++misses >= 12) break
            }
            done++
            onProgress?.invoke(done, codes.size)
        }
        return out
    }

    fun scanPropRange(
        from: Int,
        to: Int,
        maxMisses: Int = 96,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<PropDesc> {
        val out = ArrayList<PropDesc>()
        val total = to - from + 1
        var done = 0
        var misses = 0
        for (code in from..to) {
            val d = readPropDesc(code)
            if (d != null) {
                out.add(d)
                misses = 0
            } else {
                misses++
                if (misses >= maxMisses) break
            }
            done++
            onProgress?.invoke(done, total)
        }
        return out
    }

    /**
     * 把属性的描述符翻译成一行人话：数据类型、是否可写、当前值、合法值域。
     *
     * 写属性被拒时只看响应码是猜不出原因的 —— 类型不符、属性只读、取值越界
     * 在机身侧可能都归并成同一个拒绝码。描述符一次就把这些都摊开。
     */
    fun describeProp(propCode: Int): String {
        val raw = getDevicePropDesc(propCode) ?: return "机身未返回描述符（无响应）"
        return try {
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            b.position(12)
            b.short                                        // 属性码
            val dataType = b.short.toInt() and 0xFFFF
            val getSet = b.get().toInt() and 0xFF

            val typeName = when (dataType) {
                0x0001 -> "INT8"; 0x0002 -> "UINT8"; 0x0003 -> "INT16"; 0x0004 -> "UINT16"
                0x0005 -> "INT32"; 0x0006 -> "UINT32"; 0x0007 -> "INT64"; 0x0008 -> "UINT64"
                0xFFFF -> "STRING"; else -> "0x%04X".format(dataType)
            }
            val valueSize = when (dataType) {
                0x0001, 0x0002 -> 1
                0x0003, 0x0004 -> 2
                0x0005, 0x0006 -> 4
                0x0007, 0x0008 -> 8
                else -> -1
            }
            if (valueSize < 0) return "类型=$typeName，${if (getSet == 0) "只读" else "可读写"}（值域为字符串/复合，略）"

            fun readOne(): Long = when (valueSize) {
                1 -> if (dataType == 0x0001) b.get().toLong() else b.get().toLong() and 0xFF
                2 -> if (dataType == 0x0003) b.short.toLong() else b.short.toLong() and 0xFFFF
                4 -> if (dataType == 0x0005) b.int.toLong() else b.int.toLong() and 0xFFFFFFFFL
                else -> b.long
            }

            val factory = readOne()
            val current = readOne()
            val formFlag = b.get().toInt() and 0xFF

            val sb = StringBuilder()
            sb.append("类型=$typeName，${if (getSet == 0) "只读" else "可读写"}，出厂值=$factory，当前值=$current")
            when (formFlag) {
                0x01 -> {
                    val min = readOne(); val max = readOne(); val step = readOne()
                    sb.append("，合法范围=$min..$max（步进 $step）")
                }
                0x02 -> {
                    val n = b.short.toInt() and 0xFFFF
                    val vals = ArrayList<Long>()
                    for (i in 0 until n) {
                        if (b.remaining() < valueSize) break
                        vals.add(readOne())
                    }
                    sb.append("，合法取值=${vals.joinToString("/")}")
                }
                else -> sb.append("，未声明值域约束")
            }
            sb.toString()
        } catch (e: Exception) {
            "描述符解析失败：${e.message}"
        }
    }

    fun getDevicePropSupportedValues(propCode: Int): IntArray? = synchronized(usbLock) {
        val raw = getDevicePropDesc(propCode) ?: return null
        try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(12) // Skip PTP Header
            
            val code = buffer.getShort()
            val dataType = buffer.getShort().toInt() and 0xFFFF
            val getSet = buffer.get()
            
            val typeSize = when (dataType) {
                0x0001, 0x0002 -> 1 // INT8, UINT8
                0x0003, 0x0004 -> 2 // INT16, UINT16
                0x0005, 0x0006 -> 4 // INT32, UINT32
                else -> 2
            }
            
            // Skip FactoryDefault and CurrentValue
            buffer.position(buffer.position() + (typeSize * 2))
            
            val formFlag = buffer.get().toInt()
            if (formFlag == 2) { // Enumeration
                val count = buffer.getShort().toInt() and 0xFFFF
                val values = IntArray(count)
                for (i in 0 until count) {
                    values[i] = when (typeSize) {
                        1 -> buffer.get().toInt() and 0xFF
                        2 -> buffer.getShort().toInt() and 0xFFFF
                        4 -> buffer.getInt()
                        else -> 0
                    }
                }
                return values
            }
        } catch (e: Exception) {}
        return null
    }

    fun getDeviceInfo(): String? = readDeviceInfo()?.shortName

    /**
     * 机身自报支不支持某个操作码。
     *
     * 依据 GetDeviceInfo 里的 OperationsSupported —— 那是机身自己交出来的清单，
     * 比"发了看它回不回"可靠得多：不支持的命令很可能**连回应都没有**，
     * 而 usbLock 一直被占着，App 就是那个"点了没反应，之后什么都发不出去"的状态。
     *
     * @return true/false = 机身明确说了支不支持；**null = 问不到**（连机型信息都没读出来），
     * 这时调用方按"可以试，但要自己兜住超时"处理。
     */
    fun supportsOperation(opCode: Int): Boolean? {
        supportedOperations?.let { return opCode in it }
        val ops = readDeviceInfo()?.operations
        if (!ops.isNullOrEmpty()) supportedOperations = ops
        return ops?.contains(opCode)
    }

    /** GetDeviceInfo 那份清单只问一次，之后都用缓存 */
    @Volatile
    private var supportedOperations: Set<Int>? = null

    /**
     * 把机身自述支持的操作码整份打进日志，并顺手标几条我们最关心的。
     *
     * 用途：以后再遇到"这个按钮点了没反应"，先看机身到底认不认这条命令
     * （它的处理函数是否在固件调度表里），而不是去猜固件实现了什么。
     * —— 删除对象那条命令就是靠这一看才发现的：我们一直发的码机身根本不认。
     */
    fun logSupportedOperations(sink: (String) -> Unit) {
        val ops = readDeviceInfo()?.operations ?: run {
            sink("机身支持的操作码：没读到 DeviceInfo")
            return
        }
        if (!ops.isNullOrEmpty()) supportedOperations = ops

        val interesting = linkedMapOf(
            PtpConstants.OP_DELETE_OBJECT to "删除对象",
            PtpConstants.OP_NIKON_INITIATE_CAPTURE to "RecInSdram 拍摄",
            PtpConstants.OP_NIKON_DELETE_IMAGES_IN_SDRAM to "清空机身内存照片",
            PtpConstants.OP_NIKON_GET_PREVIEW_IMAGE to "取预览图",
            PtpConstants.OP_NIKON_GET_LARGE_THUMB to "大缩略图",
            PtpConstants.OP_GET_THUMB to "缩略图",
            PtpConstants.OP_GET_PARTIAL_OBJECT to "分段读取"
        )
        for ((code, name) in interesting) {
            sink(
                "机身操作能力：$name 0x${Integer.toHexString(code).uppercase()} = " +
                    (if (ops.contains(code)) "支持" else "**不支持**")
            )
        }
        sink(
            "机身操作能力（共 ${ops.size} 条）：" +
                ops.sorted().joinToString(" ") { "0x${Integer.toHexString(it).uppercase()}" }
        )
    }

    /**
     * 完整读一遍 DeviceInfo（0x1001）：厂商 / 型号 / 固件版本 / 序列号。
     *
     * 界面上的"机身信息"页要固件和序列号，而登录状态条只要型号 ——
     * 两者共用这一次读取，别再各发一遍命令。
     */
    fun readDeviceInfo(): DeviceInfo? {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_GET_DEVICE_INFO)
        if (!sendPacket(packet)) return null
        val data = receiveData() ?: return null
        val response = receiveResponseWithRetry()
        if (response?.responseCode != PtpConstants.RESP_OK) return null
        return parseDeviceInfo(data)
    }

    private fun parseDeviceInfo(buffer: ByteBuffer): DeviceInfo? {
        try {
            buffer.position(12)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.short
            buffer.int
            buffer.short
            skipString(buffer)
            buffer.short
            // 第一个数组就是 OperationsSupported —— 读下来而不是跳过（用途见 DeviceInfo.operations）
            val ops = mutableSetOf<Int>()
            val opCount = buffer.getInt()
            for (i in 0 until opCount) ops.add(buffer.short.toInt() and 0xFFFF)
            skipArray(buffer, 2)   // EventsSupported
            skipArray(buffer, 2)
            skipArray(buffer, 2)
            skipArray(buffer, 2)
            val manufacturer = readString(buffer)
            val model = readString(buffer)
            // DeviceVersion / SerialNumber 紧跟 Model —— 老机型也按规范给，读失败就当空
            val version = runCatching { readString(buffer) }.getOrDefault("")
            val serial = runCatching { readString(buffer) }.getOrDefault("")
            return DeviceInfo(manufacturer, model, version, serial, ops)
        } catch (e: Exception) { return null }
    }

    private fun readString(buffer: ByteBuffer): String {
        val len = buffer.get().toInt()
        if (len == 0) return ""
        val chars = CharArray(len - 1)
        for (i in 0 until len - 1) chars[i] = buffer.getShort().toInt().toChar()
        buffer.getShort()
        return String(chars)
    }

    private fun skipString(buffer: ByteBuffer) {
        val len = buffer.get().toInt()
        if (len > 0) buffer.position(buffer.position() + (len * 2))
    }

    private fun skipArray(buffer: ByteBuffer, elementSize: Int) {
        val count = buffer.getInt()
        buffer.position(buffer.position() + (count * elementSize))
    }

    private fun createCommandPacket(opCode: Int, vararg params: Int): ByteBuffer {
        val size = 12 + (params.size * 4)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(size)
        buffer.putShort(PtpConstants.PACKET_TYPE_COMMAND.toShort())
        buffer.putShort(opCode.toShort())
        buffer.putInt(transactionId++)
        for (param in params) buffer.putInt(param)
        return buffer
    }

    private fun createDataPacket(opCode: Int, data: ByteArray): ByteBuffer {
        val size = 12 + data.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(size)
        buffer.putShort(PtpConstants.PACKET_TYPE_DATA.toShort())
        buffer.putShort(opCode.toShort())
        buffer.putInt(transactionId - 1)
        buffer.put(data)
        return buffer
    }

    private fun sendPacket(buffer: ByteBuffer): Boolean {
        val ep = outEndpoint ?: return false
        val bytes = buffer.array()
        val len = buffer.limit()
        android.util.Log.d("PTP_TX", "Packet: ${bytesToHex(bytes, len)}")
        val result = connection.bulkTransfer(ep, bytes, len, 2000)
        if (result < 0) {
             android.util.Log.e("PTP_USB", "Write failed: result=$result. Device may have disconnected.")
             return false
        }
        return true
    }

    /**
     * 收一个数据包。
     *
     * 两个真机踩出来的坑（修之前 NEF 原文件 100% 读失败）：
     * 1. **别一次性要 maxSize**。8 MB 的 NEF 用单次 bulkTransfer 直接拿不回来，
     *    改成 16 KB 一片边收边拼（USB 上一片 16 KB 是稳的）。
     * 2. **尾巴上的响应包不能丢**。收满 packetSize 时，最后那一片常常已经把紧跟的
     *    12 字节响应包一起读进来了 —— 以前直接截断丢弃，接着 receiveResponse
     *    再去等一个永远不来的包，超时返回 null，上层就判成"读取失败"。
     *    现在多读到的部分先拆出来塞进 lastResponse。
     */
    // ------------------------------------------------------------------
    // 大块接收的复用缓冲
    //
    // 一张 NEF 十几 MB，getObject 甚至会按 expectedSize 把 maxSize 顶到 64 MB。
    // 以前每收一次就现开一块这么大的 ByteArray —— 批量导出 30 张，
    // 就是 30 个十几 MB 的大对象轮流进出堆，GC 全被它拖住。
    // 现在 ≥1 MiB 的接收复用同一块，尺寸不够才换大的。
    //
    // 能这么改的前提：所有大接收的调用方都在**本次调用内**把数据用掉 ——
    // getObject / getPartialObject 是 copyOfRange 拷出去，取景帧是当场解成 Bitmap，
    // probeLiveViewActive 只看 limit()。没有谁把 ByteBuffer 留着以后用，
    // 否则下一次接收会把它的内容写花。小接收（<1 MiB）仍然现分配，那点开销无所谓。
    // ------------------------------------------------------------------
    private val BIG_SCRATCH_MIN = 1024 * 1024
    private var bigScratch: ByteArray? = null

    private fun obtainScratch(size: Int): ByteArray {
        bigScratch?.let { if (it.size >= size) return it }
        val n = ByteArray(size)
        bigScratch = n
        return n
    }

    private fun receiveData(maxSize: Int = 4096, firstTimeoutMs: Int = 5000): ByteBuffer? {
        val ep = inEndpoint ?: return null
        val tempBuffer = if (maxSize >= BIG_SCRATCH_MIN) obtainScratch(maxSize) else ByteArray(maxSize)
        val header = ByteBuffer.wrap(tempBuffer).order(ByteOrder.LITTLE_ENDIAN)

        var totalRead = 0
        var packetSize = -1
        var first = true
        // 16 KB 一片：USB 上这个尺寸最稳，避免单次大传输被内核截断
        while (totalRead < maxSize) {
            val want = Math.min(16384, maxSize - totalRead)
            val timeout = if (first) firstTimeoutMs else 3000
            val read = connection.bulkTransfer(ep, tempBuffer, totalRead, want, timeout)
            first = false
            if (read <= 0) break
            totalRead += read
            if (packetSize < 0 && totalRead >= 12) {
                packetSize = header.getInt(0)
                val type = header.getShort(4).toInt()
                if (type == PtpConstants.PACKET_TYPE_RESPONSE) {
                    val code = header.getShort(6).toInt()
                    lastResponse = PtpResponse(type, code, header.getInt(8))
                    return null
                }
                if (type != PtpConstants.PACKET_TYPE_DATA) return null
            }
            if (packetSize > 0 && totalRead >= packetSize) break
        }
        if (totalRead < 12 || packetSize <= 0) return null

        // 多读出来的字节里如果带着响应包，拆出来留给后面的 receiveResponse 用
        if (totalRead >= packetSize + 12) {
            val rh = ByteBuffer.wrap(tempBuffer, packetSize, totalRead - packetSize)
                .order(ByteOrder.LITTLE_ENDIAN)
            if (rh.getShort(4).toInt() == PtpConstants.PACKET_TYPE_RESPONSE) {
                lastResponse = PtpResponse(
                    PtpConstants.PACKET_TYPE_RESPONSE,
                    rh.getShort(6).toInt(),
                    rh.getInt(8)
                )
            }
        }

        return try {
            val limitVal = Math.min(totalRead, packetSize)
            val resBuf = ByteBuffer.wrap(tempBuffer, 0, limitVal).order(ByteOrder.LITTLE_ENDIAN)
            resBuf.limit(limitVal)
            // 只打印包头，别再 hex 整个 8 MB —— 那会把传输拖死
            android.util.Log.d("PTP_RX", "Data Packet: ${limitVal}B (packetSize=$packetSize)")
            resBuf
        } catch (e: Exception) { null }
    }

    private fun receiveResponseWithRetry(retries: Int = 3, retryDelayMs: Long = 800, timeoutMs: Int = 2000): PtpResponse? {
        // Simplified: return response immediately even if Busy/Timeout
        // so higher level transaction retry can take over if needed.
        return receiveResponse(timeoutMs)
    }

    private fun receiveResponse(timeoutMs: Int = 2000): PtpResponse? {
        // ⚠️ bulkTransfer 的 timeout 传 0 = **无限等待**（不是"立即返回"）。
        // 上层曾经误把 0 当操作参数传进来（位置参数/vararg 错位），结果是整条
        // USB 通道卡死十几秒，usbLock 被一直占着，读帧、退出取景全堵住。
        // 这里兜底：超时小于 50ms 一律抬到 50ms，宁可多轮询一次也不能让端口永久阻塞。
        val wait = if (timeoutMs < 50) 50 else timeoutMs
        if (lastResponse != null) {
            val res = lastResponse
            lastResponse = null
            return res
        }
        
        val ep = inEndpoint ?: return null
        val buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        
        val start = System.currentTimeMillis()
        for (i in 0 until 5) {
            Arrays.fill(buffer.array(), 0.toByte())
            buffer.clear()

            val result = connection.bulkTransfer(ep, buffer.array(), 512, wait)
            val elapsed = System.currentTimeMillis() - start
            
            if (result < 12) {
                 if (result > 0) android.util.Log.w("PTP_RX", "Short packet: $result bytes after ${elapsed}ms")
                 else if (result < 0) android.util.Log.w("PTP_RX", "Bulk transfer error: $result after ${elapsed}ms")
                 return null
            }

            // Robustly parse the 12-byte header
            val length = buffer.getInt(0)
            val type = buffer.getShort(4).toInt() and 0xFFFF
            val code = buffer.getShort(6).toInt() and 0xFFFF
            val transactionId = buffer.getInt(8)

            if (length < 12 || length > 1024 * 1024) {
                android.util.Log.e("PTP_RX", "Invalid PTP packet length: $length")
                return null
            }

            val extra = if (length >= 16) (length - 12) / 4 else 0
            val params = IntArray(extra) { k -> buffer.getInt(12 + k * 4) }

            if (type == PtpConstants.PACKET_TYPE_RESPONSE) {
                android.util.Log.d("PTP_RX", "Response 0x${Integer.toHexString(code).uppercase()} in ${elapsed}ms")
                return PtpResponse(type, code, transactionId, params)
            } else if (type == PtpConstants.PACKET_TYPE_EVENT) {
                android.util.Log.d("PTP_RX", "Event 0x${Integer.toHexString(code).uppercase()} received at ${elapsed}ms")
            } else {
                android.util.Log.w("PTP_RX", "Type 0x${Integer.toHexString(type).uppercase()} packet at ${elapsed}ms")
                return PtpResponse(type, code, transactionId, params)
            }
        }
        android.util.Log.w("PTP_RX", "等待响应包超时：循环结束仍未收到合法响应")
        return null
    }

    fun drain() {
        if (inEndpoint == null) return
        val buffer = ByteArray(4096)
        var drainedCount = 0
        while (connection.bulkTransfer(inEndpoint, buffer, 4096, 15) > 0) {
            drainedCount++
            if (drainedCount > 30) break
        }
        lastResponse = null
    }

    /**
     * 深排空：把相机还在往外吐的残留数据读完。
     *
     * 场景：上一次连接被强杀时正好卡在 GetObject 传 8 MB NEF 的中途，
     * 相机会继续把剩下的数据往 USB 上灌，此时任何新命令它都不理（OpenSession 直接失败）。
     * [drain] 只清 120 KB，不够；这里给 3 秒、最多 8 MB，把管道真正冲干净。
     */
    fun flushPendingData(budgetMs: Int = 3000, maxBytes: Int = 8 * 1024 * 1024) {
        val ep = inEndpoint ?: return
        val buf = ByteArray(16384)
        val deadline = System.currentTimeMillis() + budgetMs
        var total = 0
        var idle = 0
        while (System.currentTimeMillis() < deadline && total < maxBytes) {
            val r = connection.bulkTransfer(ep, buf, 16384, 200)
            if (r > 0) { total += r; idle = 0 } else if (++idle >= 3) break
        }
        lastResponse = null
        if (total > 0) android.util.Log.d("PTP_RX", "flushPendingData: 清掉 ${total}B 残留数据")
    }

    fun deviceReady(): Boolean = synchronized(usbLock) {
        val packet = createCommandPacket(PtpConstants.OP_NIKON_DEVICE_READY)
        if (!sendPacket(packet)) return false
        val response = receiveResponse()
        
        // Clear events every time we check ready to keep camera responsive
        clearEventsThoroughly()
        
        return response?.responseCode == PtpConstants.RESP_OK
    }

    /**
     * 驱动自动对焦。
     *
     * 官方 MAID→PTP 翻译表 case 3（AF）的完整序列是三步：
     *   1) 0x90C1 AfDrive
     *   2) 0x90C8 DeviceReady —— 轮询等待机身完成对焦动作
     *   3) 0x9206 收尾
     * 我们此前只发了第 1 步，机身还在对焦行程中就返回了，
     * 紧接着的 0x90C0 自然拿到 0x2019 Focus Not Locked。
     */
    fun afDrive(): Boolean {
        // ---- 步骤 1：发 0x90C1（持锁，但要快）----
        val driveOk = synchronized(usbLock) {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_NIKON_AF_DRIVE)
            if (!sendPacket(packet)) {
                false
            } else {
                val response = receiveResponse(5000)
                val ok = response?.responseCode == PtpConstants.RESP_OK
                if (!ok) {
                    val codeHex =
                        if (response != null) "0x${Integer.toHexString(response.responseCode).uppercase()}"
                        else "TIMEOUT"
                    val hint =
                        if (response?.responseCode == PtpConstants.RESP_NIKON_HARDWARE_ERROR) "（未合焦）" else ""
                    logger("AF 驱动失败: $codeHex$hint")
                    clearPipe()
                }
                ok
            }
        }
        if (!driveOk) return false

        // ---- 步骤 2：轮询 DeviceReady ----
        //
        // ★ 这里**每轮单独持锁、睡眠放在锁外**，这是"对焦时取景画面要同步刷新"的关键。
        //
        // 以前整个 afDrive 是一个 synchronized 块，里面最多 20 轮 × 300ms 的等待，
        // 全程独占 usbLock —— 取景循环（getLiveViewFrame 也要这把锁）被彻底挡住，
        // 用户看到的就是"一按对焦，取景画面就僵住，对完才跳一下"。
        //
        // 拆开之后：每轮只占用锁做一次 0x90C8 往返（几十毫秒），
        // 剩下的 300ms 睡眠在锁外，取景循环正好用这段时间把新帧读出来 → 画面持续刷新。
        //
        // 另外 D300 在对焦中回的是 0x2019（还没合焦）而不是 0x2002(DeviceBusy)，
        // 这两种都当"继续等"。
        var ready = false
        var lockStatus = -1
        // 60 轮 × 100ms = 6 秒上限。
        // 总时长与老版（40×150ms）持平，但单轮间隔收紧到 100ms：
        // 合焦判定（DeviceReady 翻转 / 0xD104=1）的采样更密，
        // "对焦耗时 x.x 秒"的显示误差从 ±150ms 收到 ±100ms。
        for (i in 0 until 60) {
            // 用户点了「停止」：立刻收手，别再占着 USB 通道问机身
            if (abortRequested) {
                logger("对焦已中止（用户停止）")
                return false
            }
            val code = synchronized(usbLock) {
                val rp = createCommandPacket(PtpConstants.OP_NIKON_DEVICE_READY)
                if (!sendPacket(rp)) {
                    -1
                } else {
                    val rr = receiveResponse(2000)
                    if (i % 3 == 0) pollEvents()   // 事件轮询也要走 USB，间隔收紧后不必每轮都清
                    rr?.responseCode ?: -1
                }
            }

            when (code) {
                PtpConstants.RESP_OK -> ready = true
                PtpConstants.RESP_DEVICE_BUSY,
                PtpConstants.RESP_NIKON_HARDWARE_ERROR -> Unit   // 还在忙 / 还在跑 AF
                else -> {
                    if (code == -1) logger("DeviceReady 超时，停止等待")
                    break
                }
            }
            // ★ 补一条更直接的判据：0xD104 AFLockStatus（1 = 已锁焦）。
            // DeviceReady 说的是"机身空闲"，锁焦说的是"对上了"，两者不总同步；
            // 机身在对比度 AF 里先锁焦、后空闲，只看前者就会误报"未合焦"。
            if (!ready && i % 2 == 0) {
                lockStatus = try {
                    getDevicePropValue(PtpConstants.PROP_NIKON_AF_LOCK_STATUS)
                } catch (_: Exception) {
                    -1
                }
                if (lockStatus == 1) {
                    ready = true
                    logger("AF 已锁焦（0xD104=1），按合焦处理")
                }
            }

            if (ready) break
            // 锁外睡眠：这段时间取景循环可以自由取帧（100ms → 画面看起来是"在动"）
            Thread.sleep(100)
        }

        // ---- 步骤 3：0x9206 收尾（语义未确认，默认关闭）----
        if (ready && sendAfDriveFollowUp) {
            synchronized(usbLock) {
                val fp = createCommandPacket(PtpConstants.OP_NIKON_AF_DRIVE_FOLLOWUP)
                if (sendPacket(fp)) {
                    val fr = receiveResponse(2000)
                    if (fr?.responseCode != PtpConstants.RESP_OK) {
                        val hex =
                            if (fr != null) "0x${Integer.toHexString(fr.responseCode).uppercase()}"
                            else "TIMEOUT"
                        logger("AF 收尾 0x9206 返回 $hex（不影响对焦结果）")
                    }
                }
            }
        }

        logger(
            if (ready) "AF 驱动完成，机身已就绪"
            else "AF 驱动已发送，但机身未回到就绪态（0xD104 锁焦=$lockStatus）"
        )
        return ready
    }

    /** 读取事件队列并解析，逐个交给 eventHandler（拍照完成 / 属性变更 / 对象新增） */
    fun pollEvents() {
        readEventQueue(notify = true)
    }

    /**
     * 读空机身的事件队列，返回这次读到的全部事件。
     *
     * @param notify 是否把 OBJECT_ADDED / 属性变更 / 拍摄完成 交给 [eventHandler]。
     * 一般要 true；想"只把事件拿走、不惊动界面"的调用传 false ——
     * 例如只是要读一遍机身状态、不想让界面上的拍摄计数白加一张。
     */
    private fun readEventQueue(notify: Boolean): List<PtpEvent> {
        val collected = ArrayList<PtpEvent>()
        for (i in 0 until 10) {
            val packet = createCommandPacket(PtpConstants.OP_NIKON_GET_EVENT)
            if (!sendPacket(packet)) break
            val data = receiveData(2048)
            receiveResponse()
            if (data == null || data.limit() <= 14) break // 只有头 + count=0
            try {
                val buf = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                buf.position(12)
                val count = buf.short.toInt() and 0xFFFF
                var parsed = 0
                while (parsed < count && buf.remaining() >= 6) {
                    parsed++
                    val code = buf.short.toInt() and 0xFFFF
                    val param = buf.int
                    val ev = PtpEvent(code, param)
                    collected.add(ev)
                    if (notify) {
                        when (code) {
                            PtpEvent.OBJECT_ADDED, PtpEvent.PROP_CHANGED, PtpEvent.CAPTURE_COMPLETE ->
                                eventHandler?.invoke(ev)
                        }
                    }
                }
                if (parsed < count) break // 数据不足，队列异常
            } catch (e: Exception) { break }
        }
        return collected
    }

    fun clearEventsThoroughly() {
        pollEvents() // 兼容旧调用点：同样走解析路径，回调里自行过滤
    }

    fun close() {
        clearLiveViewBitmapPool()   // 断连后没必要再占着几块取景帧 Bitmap
        bigScratch = null           // 十几 MB 的接收缓冲同理
        try {
            connection.releaseInterface(usbInterface)
            connection.close()
        } catch (e: Exception) {}
    }

    private fun bytesToHex(bytes: ByteArray, len: Int): String {
        val hexChars = CharArray(len * 2)
        val hexArray = "0123456789ABCDEF".toCharArray()
        for (i in 0 until len) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * 响应包。[params] 是包里跟在 12 字节头后面的参数（SendObjectInfo 要靠它拿回
     * 机身新分配的对象句柄），多数命令没有参数，默认空数组。
     */
    data class PtpResponse(
        val type: Int,
        val responseCode: Int,
        val transactionId: Int,
        val params: IntArray = intArrayOf()
    )
}