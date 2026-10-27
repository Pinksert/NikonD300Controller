package com.example.nikond300controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.EditText
import android.text.InputFilter
import androidx.appcompat.app.AlertDialog
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.widget.ListPopupWindow
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.concurrent.thread
import kotlin.jvm.Volatile
import java.util.Locale

class MainActivity : AppCompatActivity(), SheetHost {

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private lateinit var batteryText: TextView
    private lateinit var infoText: TextView
    private lateinit var liveViewImage: ImageView
    private lateinit var focusBox: FocusBoxView
    private lateinit var liveViewPlaceholder: TextView

    /** 峰值对焦：叠加层 + 取景时才出现的开关 chip */
    private lateinit var peakingView: PeakingView
    private lateinit var peakButton: TextView

    /**
     * 触摸对焦开关：开着时，每改一次对焦点就自动跑一次对焦。
     *
     * 手持模式（51 点相位 AF）下「选点」只是把机身的对焦点挪过去，**机身并不会对焦** ——
     * 以前必须再按一次「AF」。三脚架模式下 0x9205 之后机身自己会跑对比度 AF（还响提示音），
     * 所以那条路不再叠加一次 0x90C1，免得跟机身自己那次打架。
     */
    private lateinit var touchAfButton: TextView
    private var touchAfEnabled = false
    /** 上一次自动对焦的发起时刻 —— 连按方向键时别把 AF 命令堆成一串 */
    @Volatile
    private var lastAutoAfMs = 0L

    // 对焦点控制全部收在取景框上：右上角 chip 切 11/51 点，画面上点对焦点（写 0xD108），
    // 三脚架模式点画面挪 AF 框（0x9205）。参数页那张「自动对焦」卡片已按用户要求整块删除。

    // 对焦点方向键（上下左右中）—— D300 只能按方向逐个移动对焦点
    // 方向键（上下左右中）已按用户要求取消：对焦点改在即时取景窗口的点阵上直接点选

    // 取景录像：D300 机身不能录像，只能把取景帧在手机侧编码成 MP4
    private lateinit var recButton: TextView

    /** 取景全屏：把画面铺满整屏，隐藏其它所有 UI */
    private lateinit var lvFullscreenButton: TextView
    @Volatile
    private var isLvFullscreen = false
    /** 全屏期间清掉的容器 padding（退出全屏要还原）；-1 = 还没记过 */
    private var lvSavedPadding = -1
    private lateinit var topStatusBar: LinearLayout
    /** 顶栏那一行（机身状态 + 相册 + 电量）—— 全屏时收起它，而不是收整块 topStatusBar */
    private lateinit var topBarRow: LinearLayout
    /** 全屏操作条：放大 / 缩小 / 对焦 / 拍摄（只在全屏时出现） */
    private lateinit var lvFullControls: LinearLayout
    private lateinit var lvZoomText: TextView
    /** 左上角取景工具条（录制 / 峰值 / 触摸对焦）—— 全屏时整条跟着画面上沿走 */
    private lateinit var lvTopTools: LinearLayout

    /**
     * 机身取景放大（0xD1A3）当前档位：0 = 全画面，往上逐级放大。
     *
     * 只在取景期间有意义；退出取景/拍摄前一律写回 0（"归位"）——
     * 机身会记住上次放大到几倍，不归位的话下次开取景直接是放大画面，
     * 而界面上还写着"全画面"，对焦点阵也跟着全画幅坐标画歪。
     */
    @Volatile
    private var lvZoomValue = 0

    /**
     * 未放大时最后一次从帧头读到的 AF 框中心（整幅画面归一化）。
     *
     * 放大是绕着对焦点裁的，所以"放大中心"就是按下放大那一刻的对焦点 ——
     * 之后机身可能已经把帧头坐标换成放大后的参照系，只能用放大前记下来的这个。
     */
    private var lvAfCenterNx = 0.5f
    private var lvAfCenterNy = 0.5f

    /**
     * 实时取景里是否已经合过焦（三脚架对比度 AF）。
     *
     * 合焦后镜头位置就定了；退出取景只是把反光板放下来，不会再动对焦。
     * 所以这种状态下按快门，应该直接拍 —— 不必再 "退出取景 → 0x90C1 再对一次"。
     */
    @Volatile
    private var lvAfLocked = false

    /**
     * 暂停取景读帧（拍摄/写入等独占 USB 的操作期间置 true）。
     *
     * 读帧循环以 ~14fps 不停发 0x9203；快门命令若和它同时打在同一条管道上，
     * 响应会被读帧那头吃掉 —— 表现就是"点了拍摄没反应"。
     * 注意：这里不能用 isLiveViewRunning 代替 —— 那会让循环直接退出、画面回不去。
     */
    @Volatile
    private var lvFramesPaused = false

    /** 取景启动看门狗：流程卡住时把按钮强制放出来，避免"点 LV 没反应" */
    private val lvWatchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private var lvWatchdogTask: Runnable? = null

    private var recorder: LiveViewRecorder? = null
    /** 录制计时/状态刷新（界面上的 ● 00:12 靠它走） */
    private val recTicker = android.os.Handler(android.os.Looper.getMainLooper())
    private var recTickerRunnable: Runnable? = null

    // 个人设定（Custom Settings）：扫描机身自述的属性并直接改值
    private lateinit var customScanButton: Button
    private lateinit var customHint: TextView
    private lateinit var customList: LinearLayout
    /** 上次扫描出来的属性，界面靠它复现列表 */
    private var customProps: List<PropDesc> = emptyList()
    @Volatile
    private var customScanning = false

    // 个人设定菜单（CSM）：a/b/c/d/e/f 分组，名称已知，逐项向机身读值改写
    private lateinit var csmReadButton: Button
    private lateinit var csmScanButton: Button
    private lateinit var csmHint: TextView
    private lateinit var csmList: LinearLayout
    /**
     * 扫描匹配出来的属性码（机身菜单编号 → 码）。
     *
     * 说明书上有完整菜单，但有几项在 libgphoto2 里找不到能对应的属性名 ——
     * 这些项不能硬猜码写进机身，只能扫一遍看哪个码的"值域档数"跟说明书的档数对得上。
     * 匹配结果存本地，下次打开直接生效。
     */
    private val csmCodeOverrides = HashMap<String, Int>()
    /** 每项右侧那个显示当前档位的 TextView，写值后直接改它的文本 */
    private val csmValueViews = LinkedHashMap<CsmItem, TextView>()
    /** 机身自述的合法取值：它才是裁判，我们那张中文表只是翻译 */
    private val csmAllowed = HashMap<CsmItem, List<Long>>()
    /** 各项读到的当前档位值，-1 = 没读到；取值菜单据此打勾 */
    private val csmCurrents = HashMap<CsmItem, Int>()
    /** 机身当前的曝光模式原始值（1=M 2=P 3=A 4=S），曝光模式菜单据此打勾 */
    @Volatile
    private var currentModeValue = -999999
    /** 机身当前的曝光补偿原始值（0x5010，1/1000 EV 为单位，可负），EV 菜单据此打勾 */
    @Volatile
    private var currentEvRaw = -999999
    /** 机身声明的数据类型，决定写入时用 1 字节还是 2 字节 */
    private val csmTypes = HashMap<CsmItem, Int>()
    @Volatile
    private var csmLoading = false

    // 实时取景模式：手持 / 三脚架
    // 参数页的「手持 / 三脚架」分段选择器已删（与拍摄页长按取景键重复），
    // 取景模式现在只在拍摄页体现：长按 LV 键切换 + LV 按钮图标。
    // 「探测取景属性」那一行也删了（只往日志里打调试信息，日常用不到）。

    /**
     * 当前取景模式：0=手持，1=三脚架，-1=还没读到。
     *
     * 这个变量决定取景画面的点击是否接管：三脚架模式是对比度检测 AF，
     * AF 框能在画面里自由移动，点画面才有意义；手持模式是 51 点相位检测，
     * 点哪儿机身都不认，只能用方向键。
     */
    @Volatile
    private var currentLiveViewMode = -1

    /** 机身当前对焦点 id，-1 = 还没读到 */
    @Volatile
    private var currentAfPointId = -1
    /** AF 点数：true = 51 点 */
    @Volatile
    // 对焦点数量：0xD08D 的 0 = 51 点、1 = 11 点。
    // 默认 **51 点**（D300 的主力用法），并记住用户上次的选择（SharedPreferences `af_prefs`）。
    private var afPointCount51 = true

    /** 参数页「对焦点数量」：两个方框直接选（51 / 11 点） */
    private var afPointCountGrid: SheetTileGrid? = null
    private var afPointCountBox: View? = null
    private lateinit var lvGridRow: SheetValueRow

    /** 参数页「触摸对焦」行：与取景画面左上角那枚 chip 是同一个开关 */
    private lateinit var touchAfRow: SheetValueRow

    // ---- 51 点标定结果（只读）----
    // 0xD108 的 51 点 id→坐标映射官方从未公开，是早年在这台机身上逐个写 id、
    // 读取景帧头的 AF 框位置实测出来的，存在 SharedPreferences（af51_calib）。
    // 标定入口已随参数页「自动对焦」卡片一起删掉：点位标完了，这里只负责读出来盖到几何占位上。
    private val calibLock = Any()
    private val calibResults = LinkedHashMap<Int, Pair<Float, Float>>()
    /** 上一次 0x9205 的时间戳：取景时连点会排队发一堆命令，做个节流 */
    @Volatile
    private var lastAfAreaSendMs = 0L

    // 取景叠加信息：对焦模式 + 焦距 / 曝光补偿 / 曝光模式（与设计稿 S1 同一行）
    private lateinit var focusChip: TextView
    private lateinit var focalChip: TextView
    private lateinit var evChip: TextView
    private lateinit var modeChip: TextView

    // 取景底部信息条
    private lateinit var focusStatusDot: View
    private lateinit var focusStatusText: TextView
    private lateinit var shotCountText: TextView
    @Volatile
    private var sessionShotCount = 0

    private lateinit var statusDot: View
    private lateinit var viewfinder: View
    private lateinit var shootSpacer: View
    private lateinit var logContainer: View
    private lateinit var logScroll: android.widget.ScrollView

    /** 日志保留条数上限，详见 [trimLog] */
    private val LOG_MAX_LINES = 300

    // 底部导航胶囊
    private lateinit var shootSection: android.view.ViewGroup
    private lateinit var navCapsule: LinearLayout
    private lateinit var paramsSection: View
    private lateinit var navShoot: View
    private lateinit var navParams: View
    private lateinit var navShootIcon: ImageView
    private lateinit var navParamsIcon: ImageView
    private lateinit var navShootText: TextView
    private lateinit var navParamsText: TextView
    /** 第三格是「相册」入口（独立 Activity，不参与页面高亮） */
    private lateinit var navGallery: View
    private lateinit var navGalleryIcon: ImageView
    private lateinit var navGalleryText: TextView

    private lateinit var connectButton: Button
    private lateinit var captureButton: ImageButton
    private lateinit var liveViewButton: ImageButton
    /** 顶栏右上角的「日志」入口（2026-10-24 起，相册挪进了底部导航第三格） */
    private lateinit var logButton: TextView
    private lateinit var isoButton: Button
    private lateinit var apertureButton: Button
    private lateinit var shutterButton: Button
    private lateinit var focusButton: ImageButton
    /** 参数页「曝光与画质」组里的白平衡行：右侧**实时显示机身当前白平衡模式** */
    private lateinit var wbRow: SheetValueRow
    private lateinit var apBracketingButton: Button
    private lateinit var expBracketingButton: Button
    
    private lateinit var isoUpButton: Button
    private lateinit var isoDownButton: Button
    private lateinit var apertureUpButton: Button
    private lateinit var apertureDownButton: Button
    private lateinit var shutterUpButton: Button
    private lateinit var shutterDownButton: Button

    // ---- 参数页 · 曝光与画质（测光 / 闪光补偿 / 画质）----
    // 这三项都是"先问机身描述符，再按它自述的值域出菜单"，所以只在开会话和打开二级页时读，
    // 不塞进 2 秒轮询（轮询已经连读 9 条属性，再加会让取景启动更慢）。
    private lateinit var flashCompRow: SheetValueRow
    private lateinit var imageQualityRow: SheetValueRow

    /** 「JPEG 压缩」（0x5004）：基本 / 标准 / 精细，管压缩率不管尺寸 */
    private lateinit var compressionRow: SheetValueRow
    private lateinit var mediaRow: SheetValueRow
    /** 测光模式当前档（供测光二级页标出高亮项；界面上的那行在「机身信息」里） */
    @Volatile private var currentMetering = -999999
    @Volatile private var currentFlashComp = -999999

    // 机身信息不再是参数页的一行：连上相机后点左上角的机型名即可打开（见 statusText 的点击）
    @Volatile private var shutterCount = -1          // 快门释放次数，-1 = 还没读
    @Volatile private var shutterCountReading = false
    @Volatile private var deviceInfoCache: DeviceInfo? = null
    @Volatile private var storages: List<StorageInfo> = emptyList()

    // ---- 参数页 · 拍摄流程 ----
    /** 自拍延时：参数页直接摆一排方框选，不再进二级页点下拉 */
    private var selfTimerGrid: SheetTileGrid? = null
    private var selfTimerBox: View? = null
    private lateinit var intervalRow: SheetValueRow
    private lateinit var autoDownloadRow: SheetValueRow
    /** 自拍延时（秒，0 = 关） */
    private var selfTimerSec = 0
    /** 间隔拍摄的默认张数 / 间隔秒数 */
    private var intervalCount = 5
    private var intervalSec = 5
    /** 拍完自动下载到手机相册；[autoDownloadRaw] 决定连 NEF 原片一起拉不拉 */
    private var autoDownload = false
    private var autoDownloadRaw = false

    // ---- 反光板预升 ----
    /** 是否走 Mup 两段式释放（抬起反光板 → 等待机震消散 → 释放曝光） */
    private var mirrorUpEnabled = false
    /**
     * 反光板抬起后到真正释放之间等待的秒数。
     *
     * D300 说明书给的是「约 1 秒」，但那是机内 d9 曝光延迟模式；这里是我们自己按两次快门，
     * 中间想等多久由用户定，默认 2 秒 —— 长曝/微距下 1 秒常常不够，机器还在抖。
     */
    private var mirrorUpDelaySec = 2
    private lateinit var mirrorUpRow: SheetValueRow

    // ---- 焦点包围（对焦堆叠）----
    /** 总共拍几张 */
    private var focusStackCount = 5
    /** 每站跨过几个对焦点：跨得越多，两张之间的焦点差越大 */
    private var focusStackStep = 2
    /** true = 由近到远（从画面下方往上走） */
    private var focusStackNearFirst = true
    /** 两站之间等几秒（等机身写完卡、机震散掉） */
    private var focusStackWaitSec = 2
    @Volatile
    private var isFocusStackRunning = false
    private var focusStackThread: Thread? = null
    private lateinit var focusStackRow: SheetValueRow
    @Volatile
    private var isIntervalRunning = false
    private var intervalThread: Thread? = null
    /** 机身事件里报的最新对象句柄 —— 自动下载靠它定位"刚拍的那张" */
    @Volatile
    private var lastNewObjectHandle = -1

    /** 取景帧上屏的最小间隔（纳秒）：40ms ≈ 25fps，再密只是让主线程白排队 */
    private val UI_POST_INTERVAL_NS = 40_000_000L
    /** 写参数后回读前的等待（毫秒），见 [settleAndRefresh] */
    private val SETTLE_MS = 300L

    // Current internal selection indices for arrow adjustment
    private var currentIsoIndex = 0
    private var currentApIndex = 0
    private var currentShIndex = 0

    @Volatile
    private var activeShutterProp = PtpConstants.PROP_EXPOSURE_TIME

    /**
     * 取景框显示（构图网格）：叠在实时取景画面上的辅助线。
     * **纯 App 侧** —— 机身那条（个人设定 d2「取景器网格显示」）属性码没确认下来，
     * 所以这里不写机身，只改 App 画什么。记住用户上次的选法。
     */
    private var lvGridStyle = FocusBoxView.GridStyle.OFF

    /** 机身当前的自动 ISO 开关（0=关 1=开，-999999=没读到）：ISO 菜单里「自动」那块据此点亮 */
    @Volatile
    private var currentIsoAuto = -999999

    // ---- 个人设定 b1 / b2 / b3：三套曝光步长 ----
    //
    // b1 ISO 感光度步长（0xD055）/ b2 曝光控制 EV 步长（0xD056）/ b3 曝光补偿步长（0xD057），
    // 机身值 0=1/3、1=1/2、2=1。机身把步长改了，快门 / 光圈 / ISO / 补偿的档位表就不再是
    // 1/3 一格 —— App 若不跟着变，列出的档位机身会直接拒（写不进去，用户看到就是"点了没反应"）。
    @Volatile
    private var csmIsoStep = -999999
    @Volatile
    private var csmEvStep = -999999
    @Volatile
    private var csmCompStep = -999999

    /** 机身 0x5010 自述的补偿档位（枚举，已还原成有符号）；读不到为 null */
    @Volatile
    private var evSupportedRaw: IntArray? = null

    /** ISO 1/2 步长档位 —— b1 设成 1/2 时机身给的就是这几个值 */
    private val isoLadderHalf = listOf(100, 140, 200, 280, 400, 560, 800, 1100, 1600, 2200, 3200, 4500, 6400)
    /** ISO 1 步长档位 */
    private val isoLadderFull = listOf(100, 200, 400, 800, 1600, 3200, 6400)

    @Volatile
    private var shutterPropSize = 4 // Default for 0x500D

    private val isos = arrayOf(
        100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400
    )
    
    private val apertures = arrayOf(
        140 to "f/1.4", 180 to "f/1.8", 200 to "f/2.0", 220 to "f/2.2", 250 to "f/2.5",
        280 to "f/2.8", 320 to "f/3.2", 350 to "f/3.5", 400 to "f/4.0",
        450 to "f/4.5", 500 to "f/5.0", 560 to "f/5.6", 630 to "f/6.3",
        710 to "f/7.1", 800 to "f/8.0", 900 to "f/9.0", 1000 to "f/10",
        1100 to "f/11", 1300 to "f/13", 1400 to "f/14", 1600 to "f/16",
        1800 to "f/18", 2000 to "f/20", 2200 to "f/22", 2500 to "f/25",
        2900 to "f/29", 3200 to "f/32"
    )

    private var dynamicApertures: Array<Pair<Int, String>> = apertures
    private var isPollingActive = false

    /**
     * App 是否在前台。**后台时一条周期性命令都不发** ——
     * 否则手机锁屏/切到别的应用，相机还一直被 2 秒一次地读属性，
     * 机身就永远停在"PC 连接"忙碌态，按键和菜单都不好用。
     */
    @Volatile
    private var isAppVisible = true
    
    @Volatile
    private var cameraSupportedShutters: IntArray? = null
    @Volatile
    private var shutterMap: Map<Int, String> = emptyMap()

    /** B 门：机身 PTP 里用 -1 表示；0xD100 描述符通常不列它，得自己补上 */
    private val SHUTTER_BULB = ShutterSpeeds.BULB
    /** 闪光同步（X 同步）：机身 PTP 里用 -2 表示，同样不在描述符里 */
    private val SHUTTER_FLASH = ShutterSpeeds.FLASH

    /** 当前生效的是尼康私有 0xD100 还是标准 0x500D —— 决定怎么拆档位、怎么排序 */
    private val usingNikonShutterProp: Boolean
        get() = activeShutterProp == PtpConstants.PROP_NIKON_SHUTTER_SPEED

    /** 档位排法：正常档（1/8000 → 30s）按曝光时长升序，B 门 / 闪光同步垫在最后 */
    private fun shutterRank(v: Int): Int = ShutterSpeeds.rank(v)

    /**
     * 一个档位代表多少秒 —— **排序只能按它，不能按 PTP 原始值**。
     *
     * 这就是"档位顺序乱"的根因：0xD100 是 bit-packed（高 16 分子 / 低 16 分母），
     * 原始整数大小和快慢毫无关系。比如 1" 是 0x00010001（65537）、1/8000 是 0x00011F40（73536），
     * 按原始值升序排就变成 1" → 1/2 → 1/4 → … → 1/8000 → 2" → 30"，
     * 前半段慢到快、后半段又快回慢，用户看到的就是"乱的"。
     * 标准属性 0x500D 是 1/10000 秒为单位，原始值大小和时长倒是同向，但也统一换算成秒更省心。
     */
    private fun shutterSeconds(v: Int): Double = ShutterSpeeds.seconds(v, usingNikonShutterProp)

    /** 快门档位统一排法：正常档按时长由短到长，B 门 / 闪光同步垫底 */
    private fun shuttersSorted(values: Iterable<Int>): List<Int> =
        ShutterSpeeds.sorted(values, usingNikonShutterProp)

    /**
     * 档位 → 菜单显示串。
     *
     * 实现整体搬到了 [ShutterSpeeds.label]（纯函数、有单元测试锁着）——
     * 这类"改坏了不报错、只是显示怪怪的"逻辑，靠肉眼看回归是抓不住的。
     */
    private fun getShutterString(valCode: Int): String =
        ShutterSpeeds.label(valCode, usingNikonShutterProp)

    private var shutters = emptyArray<Pair<Int, String>>()

    private var ptpConnection: PtpUsbConnection? = null

    // LV 循环在后台线程读、UI 线程写，必须 volatile，否则循环可能永远看不到停止信号
    @Volatile
    private var isLiveViewRunning = false

    /** 开启取景的链路可能跑十几秒，用它挡住期间的重复点击 */
    @Volatile
    private var isLiveViewStarting = false

    /** 取景帧循环所在线程，退出取景前要先等它结束 */
    private var liveViewThread: Thread? = null

    /** 退出取景也走后台线程，用它挡住期间的重复点击 */
    @Volatile
    private var isLiveViewStopping = false

    @Volatile
    private var isConnected = false

    /** NCP 滤镜导入的文件选择器（选 .ncp） */
    private lateinit var ncpPicker: androidx.activity.result.ActivityResultLauncher<Array<String>>
    private lateinit var backupExportLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var backupImportLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
    @Volatile
    private var isPollingPaused = false

    /** 轮询是不是因为"打开相册"才停的 —— 回到主界面时据此恢复，别把拍摄的暂停状态冲掉 */
    @Volatile
    private var pollingPausedForGallery = false

    /**
     * 「停止」按钮按下后为 true：所有还在跑的流程（拍摄、对焦、间隔、包围）
     * 在每个等待点检查它，置位就立刻收手返回，不再往机身上发命令。
     * 3 秒后自动清掉（见 [stopAllOperations]），否则下一次操作会被自己挡住。
     */
    @Volatile
    private var stopAllRequested = false

    /**
     * 包围曝光这类"对话框内的连续流程"注册进来的停止回调 ——
     * 它的线程和标志都是对话框里的局部变量，外面够不着，只能让它自己登记一个出口。
     */
    private var bracketStopHook: (() -> Unit)? = null

    /**
     * 当前不在**拍摄页**（在参数页 / 日志页）。
     *
     * 周期性轮询刷的是拍摄页那几颗芯片（ISO/光圈/快门/电量/曝光模式），
     * 用户根本看不见这些页面之外的东西，此时还 5 秒一轮地问机身，
     * 既让机身一直停在"连着电脑"的忙碌态，也把 USB 通道占着 ——
     * 真要调参数时反而要等轮询让路。
     */
    @Volatile
    private var pollingPausedForTab = false

    /**
     * 有二级页/菜单打开（[SheetHost.onSheetOpened]）。
     * 菜单里每一次选档位都要写机身，后台轮询同时还在读 9 条属性，两者抢同一条管道。
     */
    @Volatile
    private var pollingPausedForSheet = false

    /**
     * 周期性轮询此刻**该不该真的发命令**。
     *
     * 暂停原因有好几个来源（拍摄/相册/打开菜单/不在拍摄页/App 在后台），
     * 每个都自己管着一个布尔，判断时取或 —— 免得某一路恢复了就把别的路也带走。
     */
    private fun pollingBlocked(): Boolean =
        isPollingPaused || pollingPausedForTab || pollingPausedForSheet || !isAppVisible

    private val ACTION_USB_PERMISSION = "com.example.nikond300controller.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply { connectToDevice(this) }
                    } else {
                        log("USB 权限被拒绝")
                        connectButton.isEnabled = true
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                // ★ 收到广播**不要**立刻断开。
                // OTG 线被碰一下、或手机给 USB 口断电再上电（锁屏/切后台时很常见），
                // 系统都会先发 DETACHED 再发 ATTACHED —— 立刻 disconnect() 的话，
                // 每一次抖动都会变成一次完整的"断开 + 重连"，界面跟着闪一轮。
                // 这里先挂 1.2 秒，到点再确认设备是不是真没了（见 confirmUsbGone）。
                statusHandler.removeCallbacks(usbGoneRunnable)
                usbGoneAt = System.currentTimeMillis()
                log("收到 USB 拔出广播，等 1.2 秒确认是不是抖动…")
                statusHandler.postDelayed(usbGoneRunnable, 1200)
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                // 运行中重新插上相机：以前只有冷启动（onCreate 里的 intent）会连，
                // 热插拔全靠用户手动点按钮，拔一次就得重启 App 一次。
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device == null || device.vendorId != PtpConstants.USB_VENDOR_NIKON) return

                statusHandler.removeCallbacks(usbGoneRunnable)
                val flapped = usbGoneAt != 0L && System.currentTimeMillis() - usbGoneAt < 5000
                usbGoneAt = 0
                when {
                    flapped -> {
                        log("USB 抖了一下又回来了，静默重建会话（不打断当前操作）")
                        reconnectAfterFlap(device)
                    }
                    isConnected -> log("检测到 USB 设备插入，已有连接，忽略")
                    else -> {
                        log("检测到相机，正在自动连接…")
                        requestPermissionOrConnect(device)
                    }
                }
            }
        }
    }

    /**
     * 插入相机时 App 若已在前台，系统会把 intent 送到这里而不是 onCreate。
     * 不重写这个方法，热插拔就永远触发不了自动连接。
     */
    /**
     * 从相册（或其他页面）回到主界面。
     *
     * 相册那边就算收尾得再干净，也可能留下半截数据或未完成的机身状态；
     * 直接接着发命令，读回来的往往是上一次事务的响应，表现就是"莫名 busy"。
     * 所以回来先清一次管道、再等机身回到就绪态，然后才恢复轮询。
     */
    override fun onResume() {
        super.onResume()
        isAppVisible = true
        if (pollingPausedForGallery) {
            pollingPausedForGallery = false
            isPollingPaused = false
            thread {
                ptpConnection?.flushPendingData()
                ptpConnection?.deviceReady()
                runOnUiThread { log("已从相册返回，USB 通道已复位") }
            }
        }
        // 回到前台补一次刷新：后台那段时间里机身上的拨盘可能被动过
        if (isConnected) thread { updateProperties() }
    }

    override fun onPause() {
        super.onPause()
        // 退到后台就停手：让相机能退出 PC 忙碌态（否则机身一直有个"连着电脑"的会话）
        isAppVisible = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null && device.vendorId == PtpConstants.USB_VENDOR_NIKON && !isConnected) {
                log("检测到相机，正在自动连接…")
                requestPermissionOrConnect(device)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 点数是"记住的设置"，必须在 setupAfOverlay() 用之前读出来，
        // 否则启动那一下会先按默认值画一遍再跳。
        val afPrefs = getSharedPreferences(AF_PREFS, Context.MODE_PRIVATE)
        afPointCount51 = afPrefs.getBoolean(AF_KEY_51, true)
        // 取景模式也记住：机身退出取景会把 0xD1A0 重置成手持，
        // 只有"开取景前按上次选的那档写一遍"才能让它稳定停在用户要的模式上。
        // 只认 0/1：机身有时会把 0xD1A0 报成 0x8013 这类无效值，
        // 早期版本没校验就存进了 SP，再读出来会让"开取景前预设模式"整段被跳过，
        // 结果就是取景启动失败（0x2019）。这里做一次自愈。
        currentLiveViewMode = afPrefs.getInt(LV_MODE_KEY, PtpConstants.LIVE_VIEW_MODE_TRIPOD)
            .takeIf { it == PtpConstants.LIVE_VIEW_MODE_HANDHELD || it == PtpConstants.LIVE_VIEW_MODE_TRIPOD }
            ?: PtpConstants.LIVE_VIEW_MODE_TRIPOD
        // 取景框显示（构图网格）也是记住的设置，必须在 focusBox 画第一帧之前读出来
        lvGridStyle = FocusBoxView.GridStyle.values()
            .getOrElse(afPrefs.getInt(LV_GRID_KEY, 0)) { FocusBoxView.GridStyle.OFF }

        // NCP 滤镜导入：选手机上的一个 .ncp 文件（Picture Control Utility 导出的
        // 个人设定优化校准），写到机身卡的插槽里。注册必须在 onCreate 之前完成。
        ncpPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) showNcpImportSheet(uri)
        }

        // 备份导出 / 导入：注册同样必须在 onCreate 之前完成
        backupExportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) writeBackupTo(uri)
            }
        backupImportLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) readBackupFrom(uri)
            }

        // 返回键：全屏时先退出全屏；取景中先停取景再退出。
        // 直接 finish 会把读帧线程留在后台占着 USB（见 onDestroy 的说明）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isLvFullscreen -> toggleLiveViewFullscreen()
                    isLiveViewRunning || liveViewThread?.isAlive == true -> {
                        toast("正在退出取景…")
                        thread {
                            stopLiveViewBlocking()
                            runOnUiThread { finish() }
                        }
                    }
                    else -> finish()
                }
            }
        })

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        statusText = findViewById(R.id.statusText)
        // 左上角的机型名 = 机身信息入口（固件 / 序列号 / 快门数）。
        // 机身信息不再单独占参数页一行 —— 连上相机点一下这里就够了。
        statusText.setOnClickListener {
            if (isConnected) showDeviceInfoSheet() else toast("请先连接相机")
        }
        batteryText = findViewById(R.id.batteryText)
        infoText = findViewById(R.id.infoText)
        liveViewImage = findViewById(R.id.liveViewImage)
        focusBox = findViewById(R.id.focusBox)
        focusBox.gridStyle = lvGridStyle
        liveViewPlaceholder = findViewById(R.id.liveViewPlaceholder)

        setupAfOverlay()

        recButton = findViewById(R.id.recButton)
        recButton.setOnClickListener { toggleRecording() }

        peakingView = findViewById(R.id.peakingView)
        // 取景辅助的三项设置（模式 / 阈值 / 颜色）都是记住的，画第一帧之前读出来。
        // 阈值有 sane 区间兜底：旧版本存过的值若被改坏（0 或负数）会画出满屏噪点。
        peakingView.mode = if (afPrefs.getInt(PEAK_MODE_KEY, 0) == 1)
            PeakingView.OverlayMode.ZEBRA else PeakingView.OverlayMode.PEAKING
        peakingView.threshold = afPrefs.getInt(PEAK_THRESHOLD_KEY, 110).coerceIn(20, 240)
        peakingView.zebraThreshold = afPrefs.getInt(ZEBRA_THRESHOLD_KEY, 245).coerceIn(200, 255)
        peakingView.peakingColor = afPrefs.getInt(PEAK_COLOR_KEY, Color.RED)
        peakButton = findViewById(R.id.peakButton)
        peakButton.setOnClickListener { togglePeaking() }
        // 长按 = 阈值/颜色/模式。放在长按而不是再摆一颗按钮：
        // 取景页的按钮已经够密了，而这组值调好一次就很少再动。
        peakButton.setOnLongClickListener { showPeakingSheet(); true }

        touchAfButton = findViewById(R.id.touchAfButton)
        touchAfEnabled = getSharedPreferences(AF_PREFS, Context.MODE_PRIVATE)
            .getBoolean(AF_KEY_TOUCH_AF, false)
        renderTouchAfButton()
        touchAfButton.setOnClickListener { toggleTouchAf() }


        topStatusBar = findViewById(R.id.topStatusBar)
        topBarRow = findViewById(R.id.topBarRow)
        lvFullscreenButton = findViewById(R.id.lvFullscreenButton)
        lvFullscreenButton.setOnClickListener { toggleLiveViewFullscreen() }

        // ---- 全屏操作条（放大/缩小 · 对焦 · 拍摄）----
        lvFullControls = findViewById(R.id.lvFullControls)
        lvTopTools = findViewById(R.id.lvTopTools)
        lvZoomText = findViewById(R.id.lvZoomText)
        findViewById<View>(R.id.lvZoomOutButton).setOnClickListener { stepLvZoom(false) }
        findViewById<View>(R.id.lvZoomInButton).setOnClickListener { stepLvZoom(true) }
        // 点倍率文字 = 回到全画面（对焦完要恢复构图时最常用的一步）
        // 长按 = 弹出倍率菜单，直接选一个百分比（和曝光参数那套二级页一样）
        lvZoomText.setOnClickListener { applyLvZoom(0) }
        lvZoomText.setOnLongClickListener {
            showLvZoomSheet()
            true
        }
        findViewById<View>(R.id.lvFullAfButton).setOnClickListener { doFocusOnce() }
        findViewById<View>(R.id.lvFullCaptureButton).setOnClickListener { doCaptureOnce() }


        setupLiveViewModeControls()

        focusChip = findViewById(R.id.focusChip)
        focalChip = findViewById(R.id.focalChip)
        evChip = findViewById(R.id.evChip)
        modeChip = findViewById(R.id.modeChip)
        renderModeChip(-1)   // 未连接：通用图标 + 箭头；连上后由 updateProperties 换成 M/P/A/S 字母

        focusStatusDot = findViewById(R.id.focusStatusDot)
        focusStatusText = findViewById(R.id.focusStatusText)
        shotCountText = findViewById(R.id.shotCountText)

        statusDot = findViewById(R.id.statusDot)
        viewfinder = findViewById(R.id.viewfinder)
        // 转屏 / 折叠屏展开这类尺寸变化：全屏时按钮位置得跟着新的画面矩形重算
        viewfinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isLvFullscreen) applyFullscreenControlOffsets()
        }
        shootSpacer = findViewById(R.id.shootSpacer)
        setViewfinderExpanded(false)
        logContainer = findViewById(R.id.logContainer)
        logScroll = findViewById(R.id.logScroll)
        findViewById<TextView>(R.id.clearLogButton).setOnClickListener {
            infoText.text = ""
            log("日志已清空")
        }
        findViewById<TextView>(R.id.stopAllButton).setOnClickListener { stopAllOperations() }

        shootSection = findViewById(R.id.shootSection)
        navCapsule = findViewById(R.id.navCapsule)
        paramsSection = findViewById(R.id.paramsSection)
        navShoot = findViewById(R.id.navShoot)
        navParams = findViewById(R.id.navParams)
        navGallery = findViewById(R.id.navGallery)
        navShootIcon = findViewById(R.id.navShootIcon)
        navParamsIcon = findViewById(R.id.navParamsIcon)
        navGalleryIcon = findViewById(R.id.navGalleryIcon)
        navShootText = findViewById(R.id.navShootText)
        navParamsText = findViewById(R.id.navParamsText)
        navGalleryText = findViewById(R.id.navGalleryText)

        navShoot.setOnClickListener { switchTab(0) }
        navParams.setOnClickListener { switchTab(1) }
        navGallery.setOnClickListener { openGallery() }
        switchTab(0)

        connectButton = findViewById(R.id.connectButton)
        captureButton = findViewById(R.id.captureButton)
        liveViewButton = findViewById(R.id.liveViewButton)
        logButton = findViewById(R.id.logButton)
        isoButton = findViewById(R.id.isoButton)
        apertureButton = findViewById(R.id.apertureButton)
        shutterButton = findViewById(R.id.shutterButton)
        focusButton = findViewById(R.id.focusButton)
        apBracketingButton = findViewById(R.id.apBracketingButton)
        expBracketingButton = findViewById(R.id.expBracketingButton)
        
        isoUpButton = findViewById(R.id.isoUpButton)
        isoDownButton = findViewById(R.id.isoDownButton)
        apertureUpButton = findViewById(R.id.apertureUpButton)
        apertureDownButton = findViewById(R.id.apertureDownButton)
        shutterUpButton = findViewById(R.id.shutterUpButton)
        shutterDownButton = findViewById(R.id.shutterDownButton)
        setupParamExtraRows()

        // 未连接时给一句能照做的引导（状态条太窄放不下，这里放在日志里）
        if (!isConnected) {
            log("提示：机身「设定菜单 → USB」选 MTP/PTP，插上 OTG 线后点「连接相机」")
        }

        // 这个按钮只负责"连接"：连上之后它整块收起，
        // 断开连接挪到「相机信息」（点左上角机型名打开）里，避免误触。
        connectButton.setOnClickListener { findAndConnect() }

        // 顶栏「日志」：本机自己的记录，跟机身连不连没关系，随时可看
        logButton.setOnClickListener { switchTab(2) }

        captureButton.setOnClickListener { doCaptureOnce() }

        // 曝光模式菜单挂在拍摄页那颗显示 M/P/A/S 的芯片上（参数页不再重复放一个按钮）
        modeChip.setOnClickListener { view ->
            if (!isConnected) { toast("请先连接相机"); return@setOnClickListener }
            showExposureModeMenu(view)
        }

        // 曝光补偿（EV）一样可点：芯片显示当前值，点开就是和 ISO/光圈同一套的档位二级页
        evChip.setOnClickListener {
            showEvSheet()
        }

        // ISO 菜单 = 19 档手动值 + 末尾的「自动」（0xD054）。
        // 自动 ISO 原本单独摆在参数页，调感光度和开自动要换一页走两趟，
        // 现在合成一块：手动值与自动不冲突（开着自动时手动值仍是基准），点「自动」切换开关。
        isoButton.setOnClickListener {
            if (!isConnected) { toast("请先连接相机"); return@setOnClickListener }
            showIsoSheet()
        }
        
        isoUpButton.setOnClickListener {
            // 加减键只改基准值：自动 ISO 开着也不退出（说明书：手动值与自动不冲突）。
            // 档位表随 b1 步长变（1/3 → 19 档、1/2 → 13 档、1 → 7 档），所以按当前表取上界
            if (currentIsoIndex < isoLadder().size - 1) applyIsoIndex(currentIsoIndex + 1)
        }

        isoDownButton.setOnClickListener {
            if (currentIsoIndex > 0) applyIsoIndex(currentIsoIndex - 1)
        }

        apertureButton.setOnClickListener { view ->
            // 光圈档位直接来自机身描述符，b2 步长一变它自己就跟着变；
            // 这里补一块步长设定（就地改，不用进个人设定菜单翻三层）+ 一句提示。
            showExposureValueSheet(
                "光圈",
                "当前 ${dynamicApertures.getOrElse(currentApIndex) { 0 to "—" }.second} · 机身可用 ${dynamicApertures.size} 档",
                dynamicApertures.map { it.second },
                currentApIndex,
                stepCard = { dismiss -> evStepControlCard(dismiss) },
                hintText = evControlStepHint()
            ) { index ->
                if (index >= 0 && index < dynamicApertures.size) {
                    currentApIndex = index
                    setAperture(dynamicApertures[index].first, dynamicApertures[index].second)
                }
            }
        }
        
        apertureUpButton.setOnClickListener {
            if (currentApIndex > 0) {
                currentApIndex--
                setAperture(dynamicApertures[currentApIndex].first, dynamicApertures[currentApIndex].second)
            }
        }
        
        apertureDownButton.setOnClickListener {
            if (currentApIndex < dynamicApertures.size - 1) {
                currentApIndex++
                setAperture(dynamicApertures[currentApIndex].first, dynamicApertures[currentApIndex].second)
            }
        }

        shutterButton.setOnClickListener { showShutterSheet() }
        
        shutterUpButton.setOnClickListener {
            if (currentShIndex > 0) { 
                currentShIndex--
                setShutter(shutters[currentShIndex].first, shutters[currentShIndex].second)
            }
        }
        
        shutterDownButton.setOnClickListener {
            if (currentShIndex < shutters.size - 1) { 
                currentShIndex++
                setShutter(shutters[currentShIndex].first, shutters[currentShIndex].second)
            }
        }

        // 长按 LV 按钮 = 切换取景模式（手持 ⇄ 三脚架），不用再进参数页；
        // 按钮图标直接画成当前模式（人拿相机 / 三脚架），一眼知道现在是哪种。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            liveViewButton.tooltipText = "长按切换：手持 / 三脚架"
        }
        liveViewButton.setOnLongClickListener {
            toggleLiveViewModeByLongPress()
            true
        }
        renderLiveViewButtonIcon()

        liveViewButton.setOnClickListener {
            // 取景中再点一次 = 关闭取景。这条语义不能动摇：
            // 一旦这里因为内部标志漂移而变成"又开一次"，用户就再也关不掉取景，只能关相机电源。
            if (isLiveViewRunning) {
                stopLiveView()
                return@setOnClickListener
            }

            // 按钮画着"取景中"，内部却认为没在取景 —— 状态漂移。
            // 此时若按标志位去"开启"，用户就永远关不掉。先问机身一句它到底在不在取景，
            // 以机身的回答为准。
            if (liveViewUiState == 2) {
                setLiveViewState(1)
                liveViewButton.isEnabled = false
                thread {
                    val cameraInLv = try {
                        ptpConnection?.probeLiveViewActive() ?: false
                    } catch (e: Exception) {
                        false
                    }
                    runOnUiThread {
                        liveViewButton.isEnabled = isConnected
                        if (cameraInLv) {
                            log("状态已对齐：机身仍在取景，本次点击按关闭处理")
                            isLiveViewRunning = true
                            stopLiveView()
                        } else {
                            setLiveViewState(0)
                            startLiveView()
                        }
                    }
                }
                return@setOnClickListener
            }

            startLiveView()
        }

        // 白平衡行的点击在 setupParamExtraRows() 里挂（行是代码建的，不在这里 findViewById）

        // 自动 ISO 已经并进拍摄页的 ISO 菜单（末尾那块「自动」），参数页不再单列一行：
        // 调感光度和开自动分在两页，来回切一次就得等机身响应一次。

        focusButton.setOnClickListener { doFocusOnce() }

        apBracketingButton.setOnClickListener {
            showBracketingDialog()
        }

        expBracketingButton.setOnClickListener {
            showExpBracketingDialog()
        }

        updateButtonStates()

        // 第一次打开 App 主动把使用说明推到眼前 ——
        // 这是个需要 OTG 线、机身 USB 模式、USB 授权三步才连得上的工具，
        // 没有引导的话，用户装完只看到一排灰按钮，根本不知道要先做什么。
        val guideSp = getSharedPreferences("guide", Context.MODE_PRIVATE)
        if (!guideSp.getBoolean("helpShown", false)) {
            guideSp.edit().putBoolean("helpShown", true).apply()
            window.decorView.post { InfoActivity.open(this, InfoActivity.PAGE_HELP) }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        // 热插拔：运行中重新插上相机也要能自动连上，否则拔一次就得重启一次 App
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** 底部导航：拍摄 / 参数 / 日志 三页切换 */
    private fun switchTab(index: Int) {
        shootSection.visibility = if (index == 0) View.VISIBLE else View.GONE
        paramsSection.visibility = if (index == 1) View.VISIBLE else View.GONE
        logContainer.visibility = if (index == 2) View.VISIBLE else View.GONE
        // 切到日志页时把最新内容顶到视野里，省得每次都手动拖到底
        if (index == 2) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        // ★ 只有拍摄页才做周期性轮询。
        // 参数页/日志页根本看不见那些芯片（ISO/光圈/快门/电量），
        // 此时还 5 秒一轮地连读 9 条属性，纯属让机身一直停在"连着电脑"的忙碌态 ——
        // 用户在参数页调东西反而要等轮询让路。
        val onShootTab = index == 0
        pollingPausedForTab = !onShootTab
        // 切回拍摄页补一次刷新：待在别的页面那段时间里，机身上的拨盘可能被动过
        if (onShootTab && isConnected) thread { updateProperties() }

        // ★ 只有拍摄/参数两格里挑：第三格是相册入口（独立的 Activity），
        // 日志页由顶栏「日志」按钮打开，它没有自己的导航格 —— 所以停在日志页时
        // 这里不高亮任何一格，改由顶栏那颗按钮自己变成"选中态"表示你在哪儿。
        val items = listOf(
            Triple(navShoot, navShootIcon, navShootText),
            Triple(navParams, navParamsIcon, navParamsText)
        )
        items.forEachIndexed { i, (container, icon, label) ->
            val selected = i == index
            container.setBackgroundResource(
                if (selected) R.drawable.bg_nav_item_selected else R.drawable.bg_nav_item_ripple
            )
            val tint = if (selected) Color.parseColor("#0B0E13") else Color.parseColor("#8A94A6")
            icon.setColorFilter(tint)
            label.setTextColor(tint)
            label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        renderLogButtonActive(index == 2)
    }

    /** 顶栏「日志」按钮的选中态：日志页没有导航格，用它自己告诉用户"你现在在这儿" */
    private fun renderLogButtonActive(active: Boolean) {
        if (!::logButton.isInitialized) return
        logButton.background = ContextCompat.getDrawable(
            this, if (active) R.drawable.bg_nav_item_selected else R.drawable.bg_param_card
        )
        val tint = if (active) Color.parseColor("#0B0E13") else getColor(R.color.accent_amber)
        logButton.setTextColor(tint)
    }

    /**
     * 打开相册（底部导航第三格）。
     *
     * ⚠️ 两件必须做的事：
     * 1. **先停轮询**：它一直在发读属性命令，会和相册那边的遍历抢同一条 USB 管道，
     *    两边一起变慢，机身还容易回 DeviceBusy。
     * 2. **先退取景**：两边都要独占 USB 通道，而且必须等机身确认退出之后再跳过去 ——
     *    否则相册第一条命令会撞上还在跑的读帧线程。
     */
    private fun openGallery() {
        if (!isConnected) {
            Toast.makeText(this, "请先连接相机", Toast.LENGTH_SHORT).show()
            return
        }
        isPollingPaused = true
        pollingPausedForGallery = true
        if (isLiveViewRunning) {
            Toast.makeText(this, "正在停止取景…", Toast.LENGTH_SHORT).show()
            thread {
                stopLiveViewBlocking()
                runOnUiThread { startActivity(Intent(this@MainActivity, GalleryActivity::class.java)) }
            }
            return
        }
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    // ===================== 菜单打开期间让轮询停手 =====================

    override fun onSheetOpened() {
        // 菜单里每选一档都要往机身写一次；后台轮询还在 5 秒一轮地读 9 条属性，
        // 两者抢同一条 USB 通道 —— 表现就是"选个档位要等一下才生效"。
        pollingPausedForSheet = true
    }

    override fun onSheetClosed() {
        pollingPausedForSheet = false
        // 菜单里刚写过机身，回来立刻补一次刷新，界面上不用等下一轮轮询（最长 5 秒）
        if (isConnected && !pollingPausedForTab) {
            thread { settleAndRefresh() }
        }
    }

    /** 取景键当前画出来的样子：0 未取景 / 1 进行中 / 2 取景中。
     *  点击处理要拿它和 [isLiveViewRunning] 对账 —— 两者不一致就说明状态漂移了。 */
    private var liveViewUiState = 0

    /** 取景键三态：0 未取景 / 1 开启中 / 2 取景中（已开启用强调色底 + 深色图标） */
    private fun setLiveViewState(state: Int) {
        liveViewUiState = state
        if (state == 2) {
            liveViewButton.setBackgroundResource(R.drawable.bg_circle_amber)
            liveViewButton.setImageResource(R.drawable.ic_liveview_dark)
        } else {
            liveViewButton.setBackgroundResource(R.drawable.bg_circle_dark)
            liveViewButton.setImageResource(R.drawable.ic_liveview)
        }
        // 开启中用半透明表达"已受理、正在响应"，避免用户以为按钮失灵
        liveViewButton.alpha = if (state == 1) 0.45f else 1f
    }

    /**
     * 取景框收放：没开取景时它只是个状态条，占满整屏纯属浪费 —— 用户真正要看的是
     * 下面的曝光参数和快门键。开启取景后才把它撑开，吃掉剩余空间。
     *
     * 实现上是取景框与 [shootSpacer] 互换 weight：谁 visible 谁承重，
     * 这样参数三档和快门键在任何状态下都稳稳贴在底部导航条上方，不会跟着跳。
     */
    // ===================== 对焦点控制 =====================

    /** D300 中心对焦点的 id（remoteyourcam 的 D300 点表里 0.5/0.5 那个点） */
    private val CENTER_AF_POINT_ID = 1

    // ===================== 取值下拉菜单 =====================

    /**
     * 统一的档位取值菜单，替掉系统 PopupMenu。
     *
     * 系统 PopupMenu 在深色界面上是一块白底，字色和排版都跟页面不搭；
     * 这里换成深色圆角卡片 + 每档 46dp + **当前值前面打勾**，一眼看出机身现在在哪一档。
     *
     * @param items 档位名（已中文化）
     * @param selectedIndex 当前所在档；-1 表示未知，就不打勾
     */
    /**
     * 曝光参数（ISO / 光圈 / 快门）的档位选择。
     *
     * 之前用的是 `showValueMenu` —— 一列 46dp 的行，ISO 19 项、光圈 27 项、快门 30 多项，
     * 得滚着找，而且只能在半透明的窄条里逐行扫。
     * 换成 4 列网格：一屏基本能看全，点哪档就是哪档，当前档用强调色标出。
     */
    private fun showExposureValueSheet(
        title: String,
        subtitle: String,
        items: List<String>,
        selectedIndex: Int,
        /** 步长选择块（个人设定 b2）：快门 / 光圈共用一个步长设定。
         *  传工厂而不是 View —— 块里的按钮要能关掉这一页，得先有 page。 */
        stepCard: ((dismiss: () -> Unit) -> View)? = null,
        /** 覆盖底部那句提示（不传就用默认文案） */
        hintText: String? = null,
        onPick: (Int) -> Unit
    ) {
        if (items.isEmpty()) return
        val page = buildSheetPage(title, subtitle)
        val card = sheetCard(page.content, null)
        val grid = sheetTileGrid(4, items, selectedIndex) { idx ->
            onPick(idx)
            page.dialog.dismiss()
        }
        card.addView(grid.view)
        if (stepCard != null) page.content.addView(stepCard { page.dialog.dismiss() })
        page.content.addView(sheetHint(hintText ?: "点一下立即生效。"))
        page.show()
    }

    /** 快门 / 光圈共用：b2「曝光控制 EV 步长」的提示语 */
    private fun evControlStepHint(): String =
        "机身「个人设定 b2 · 曝光控制 EV 步长」当前为 ${stepLabel(csmEvStep) ?: "1/3 步长"}；" +
            "档位由机身描述符给出，会跟着步长变（1/2 步长时档位少一半，1 步长时只取整档）。"

    /** 快门 / 光圈共用：b2 步长选择块 */
    private fun evStepControlCard(dismiss: () -> Unit): LinearLayout =
        stepSelectCard("曝光控制 EV 步长（个人设定 b2）", listOf(0, 1, 2), csmEvStep) { v ->
            thread {
                val ok = writeStepProp(PtpConstants.CSM_B2_EV_STEP, v)
                runOnUiThread { onStepWritten(PtpConstants.CSM_B2_EV_STEP, v, ok, dismiss) }
            }
        }

    /**
     * ISO 菜单：19 档手动值 + 末尾的「自动」。
     *
     * 说明书（自动 ISO 感光度控制）：开着自动时，用户所选的 ISO 值**仍是基准值**，
     * 只是在该值达不到最佳曝光时机身才往上抬 —— 两者不冲突、不互斥。
     * 所以这里手动档和「自动」块同时高亮；选数字档**不会**关掉自动，只是改基准值。
     * 点「自动」= 切换开关；长按「自动」进细化设置（上限 / 最低快门）。
     */
    private fun showIsoSheet() {
        // b1（ISO 感光度步长）决定这一页列哪些 ISO：1/2 步长时只有 13 档，1 步长时 7 档
        var ladder = isoLadder()
        var autoIndex = ladder.size       // 自动那块紧跟最后一档
        var labels = ladder.map { "$it" } + "自动"
        val autoOn = currentIsoAuto == 1
        /** 当前基准 ISO 的显示值（档位表可能被刷新过，统一从这里取） */
        fun baseIso(): Int = ladder.getOrElse(currentIsoIndex) { 0 }

        val page = buildSheetPage(
            "ISO 感光度",
            if (autoOn) "自动 ISO 开启 · 基准 ISO ${baseIso()}（曝光不足时自动上调）"
            else "当前 ISO ${baseIso()} · ${ladder.size} 档 + 自动 · ${stepLabel(csmIsoStep) ?: "1/3 步长"}"
        )
        val card = sheetCard(page.content, null)

        // 点「自动」后抽屉不关：当场刷新高亮（手动档始终亮，「自动」按开关态），
        // 关抽屉由用户自己来（点外部或下拉），否则高亮根本来不及看。
        var gridRef: SheetTileGrid? = null
        val statusText = sheetStatusText(
            if (autoOn) "自动 ISO 已开启 · 基准 ISO ${baseIso()}"
            else "点数字档改基准值；点「自动」交给机身在曝光不足时上调"
        )
        /** 高亮 = 手动基准档 + （自动开着时的）「自动」块 */
        fun refreshHighlight() {
            val targets = buildList {
                add(currentIsoIndex)
                if (currentIsoAuto == 1) add(autoIndex)
            }
            gridRef?.selectMany(targets)
        }

        /** 档位表（步长）被刷新过，网格要能整块重建 */
        fun renderGrid() {
            card.removeAllViews()
            val grid = sheetTileGrid(
                4, labels, currentIsoIndex,
                onPick = { idx ->
                    if (idx == autoIndex) {
                        val turningOn = currentIsoAuto != 1
                        setIsoAuto(turningOn) {
                            refreshHighlight()
                            statusText.text = if (currentIsoAuto == 1)
                                "✓ 自动 ISO 已开启 · 基准 ISO ${baseIso()}（长按「自动」设上限/最低快门）"
                            else
                                "自动 ISO 已关闭 · 固定 ISO ${baseIso()}"
                        }
                    } else {
                        applyIsoIndex(idx)
                        page.dialog.dismiss()
                    }
                },
                onLongPick = { idx ->
                    if (idx != autoIndex) false
                    else {
                        // 长按「自动」：上限与最低快门不常用，收进二级页，别占着这块网格
                        page.dialog.dismiss()
                        showIsoAutoSettingsDialog()
                        true
                    }
                }
            )
            gridRef = grid
            if (currentIsoAuto == 1) grid.selectMany(listOf(currentIsoIndex, autoIndex))
            card.addView(grid.view)
        }
        renderGrid()
        page.content.addView(statusText)
        page.content.addView(
            stepSelectCard("ISO 步长（个人设定 b1）", listOf(0, 1), csmIsoStep) { v ->
                thread {
                    val ok = writeStepProp(PtpConstants.CSM_B1_ISO_STEP, v)
                    runOnUiThread { onStepWritten(PtpConstants.CSM_B1_ISO_STEP, v, ok) { page.dialog.dismiss() } }
                }
            }
        )
        page.show()

        // 后台读一次 b1：机身拨盘改了步长，这一页得按新步长重列
        thread {
            val b1 = readStepProp(PtpConstants.CSM_B1_ISO_STEP)
            runOnUiThread {
                if (b1 == -999999) return@runOnUiThread
                csmIsoStep = b1
                val fresh = isoLadder()
                if (fresh == ladder) return@runOnUiThread
                val oldValue = ladder.getOrNull(currentIsoIndex)
                ladder = fresh
                autoIndex = ladder.size
                labels = ladder.map { "$it" } + "自动"
                // 档位表换了：当前基准值按值重定位下标（1/3 → 1/2 时 125 这类档会消失）
                val idx = ladder.indexOf(oldValue ?: -1)
                if (idx >= 0) currentIsoIndex = idx
                else if (currentIsoIndex !in ladder.indices) currentIsoIndex = 0
                renderGrid()
                gridRef?.centerOn(currentIsoIndex)
                log("ISO 档位：b1 = ${stepLabel(b1) ?: b1}，共 ${ladder.size} 档")
            }
        }
    }

    /**
     * 当前 b1 步长下的 ISO 档位表。
     * 机身把步长设成 1/2 或 1 之后，1/3 那几档（125 / 160 / 250 …）机身是不会接受的。
     */
    private fun isoLadder(): List<Int> = when (csmIsoStep) {
        1 -> isoLadderHalf
        2 -> isoLadderFull
        else -> isos.toList()
    }

    /**
     * 选一档手动 ISO：只改基准值。
     * 自动 ISO 开着也不关 —— 说明书：两者不冲突，机身在基准值不够时才上调。
     */
    private fun applyIsoIndex(index: Int) {
        val ladder = isoLadder()
        if (index < 0 || index >= ladder.size) return
        currentIsoIndex = index
        val value = ladder[index]
        thread {
            try {
                isPollingPaused = true
                setIsoInternal(value)
            } catch (e: Exception) {
                runOnUiThread { log("设置 ISO 出错：${e.message}") }
            } finally {
                isPollingPaused = false
                settleAndRefresh()
            }
        }
    }

    /**
     * 写完机身参数后等一下再回读刷新。
     *
     * 注意这**不是失败重试** —— 写命令已经回 OK 了，等的是机身把新值落到属性区。
     * 立刻回读经常还是旧值，界面就成了"选了档位但显示没变"。
     * 300ms 是实测够用的下限；真要做到"确认生效"得为每个属性写一套回读比对，
     * 代价远大于收益，所以这里就是一个集中的、有名字的等待。
     */
    private fun settleAndRefresh() {
        Thread.sleep(SETTLE_MS)
        updateProperties()
    }

    /**
     * 自动 ISO 开关（0xD054）。
     * onDone：下发完成（无论成败）后在 UI 线程回调，给 ISO 菜单当场点亮「自动」块用。
     */
    private fun setIsoAuto(on: Boolean, onDone: (() -> Unit)? = null) {
        thread {
            try {
                isPollingPaused = true
                setIsoAutoInternal(on)
            } catch (e: Exception) {
                runOnUiThread { log("自动 ISO 设置出错：${e.message}") }
            } finally {
                isPollingPaused = false
                refreshIsoChip()
                onDone?.let { cb -> runOnUiThread(cb) }
                settleAndRefresh()
            }
        }
    }

    /** 只负责下发 0xD054，线程与收尾刷新交给调用方串起来 */
    private fun setIsoAutoInternal(on: Boolean) {
        log("正在设置自动 ISO：${if (on) "开" else "关"}…")
        val ok = ptpConnection?.setDevicePropValue(
            PtpConstants.PROP_NIKON_ISO_AUTO, if (on) 1 else 0, size = 1
        ) ?: false
        if (ok) {
            currentIsoAuto = if (on) 1 else 0
            ptpConnection?.deviceReady()
        }
        runOnUiThread { log("自动 ISO：${if (!ok) "设置失败" else if (on) "开" else "关"}") }
    }

    /** 拍摄页那颗 ISO 芯片：恒显手动档 —— 自动 ISO 开着时主页也保持显示原来手动设的值 */
    private fun refreshIsoChip() {
        runOnUiThread {
            isoButton.text = "${isos.getOrElse(currentIsoIndex) { 0 }}"
        }
    }

    /**
     * 参数页里的"标题 + 一排方框"：直接点选，不再点开二级页 / 弹下拉列表。
     *
     * 用户明确要求这类只有两三个选项的设置别做成下拉：方框一眼能看全当前是哪项，
     * 也能看到还有哪几项可选 —— 下拉里藏着的知识 Tree 被"当前值"那一行挤掉了。
     *
     * @return 外层容器（外面统一压暗用）和网格本身（改选中用）
     */
    private fun inlineTileField(
        host: LinearLayout,
        label: String,
        labels: List<String>,
        selectedIndex: Int,
        columns: Int = labels.size,
        onPick: (Int) -> Unit
    ): Pair<View, SheetTileGrid> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dpx(6), dpx(2), 0, dpx(4))
        })
        val grid = sheetTileGrid(columns, labels, selectedIndex) { idx -> onPick(idx) }
        box.addView(grid.view)
        host.addView(box)
        return box to grid
    }

    private fun showValueMenu(
        anchor: View,
        items: List<String>,
        selectedIndex: Int = -1,
        onPick: (Int) -> Unit
    ) {
        if (items.isEmpty()) return
        val density = resources.displayMetrics.density
        val rowH = (46 * density).toInt()

        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.bg_menu_card))
        popup.setAdapter(MenuAdapter(items, selectedIndex))
        // 宽度跟锚点对齐但不窄于 200dp；太高的话按屏幕 60% 截断，内部可滚
        popup.width = kotlin.math.max(anchor.width, (200 * density).toInt())
        val maxH = (resources.displayMetrics.heightPixels * 0.6).toInt()
        popup.height = kotlin.math.min(items.size * rowH, maxH)
        popup.isModal = true
        popup.setOnItemClickListener { _, _, position, _ ->
            onPick(position)
            popup.dismiss()
        }
        // 取值下拉菜单也算是"菜单打开"：弹出期间停掉周期性轮询，
        // 免得它跟马上要写进机身的那条命令抢同一条通道。
        popup.setOnDismissListener { onSheetClosed() }
        popup.show()
        onSheetOpened()
    }

    /**
     * 把曝光模式画成 M/P/A/S 字母当作芯片的前置图标。
     * 连上机身、拿到真实档位后就用它替掉通用的 ic_mode 图标，一眼就能对上机身拨盘。
     */
    private inner class ModeLetterDrawable(
        letter: String,
        sizeSp: Float = 13f,
        @ColorInt color: Int = ContextCompat.getColor(this, R.color.accent_amber)
    ) : Drawable() {
        private val text = letter
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = sizeSp * resources.displayMetrics.scaledDensity
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val box = (16 * resources.displayMetrics.density).toInt()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val fm = paint.fontMetrics
            val baseY = b.exactCenterY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, b.exactCenterX(), baseY, paint)
        }
        override fun getIntrinsicWidth() = box
        override fun getIntrinsicHeight() = box
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** 曝光模式芯片的前置图标：已知档位就画字母，未知还是通用图标；尾部的箭头始终保留。 */
    private fun renderModeChip(mode: Int) {
        val letter = when (mode) { 1 -> "M"; 2 -> "P"; 3 -> "A"; 4 -> "S"; else -> null }
        val start = if (letter != null) {
            ModeLetterDrawable(letter)
        } else {
            ContextCompat.getDrawable(this, R.drawable.ic_mode)?.mutate()
                ?.apply { setTint(ContextCompat.getColor(this@MainActivity, R.color.text_secondary)) }
        }
        val end = ContextCompat.getDrawable(this, R.drawable.ic_chevron_right)?.mutate()
            ?.apply { setTint(ContextCompat.getColor(this@MainActivity, R.color.text_secondary)) }
        modeChip.setCompoundDrawablesRelativeWithIntrinsicBounds(start, null, end, null)
        modeChip.compoundDrawablePadding = (4 * resources.displayMetrics.density).toInt()
    }

    /**
     * 曝光补偿档位表：-5 EV … +5 EV，1/3 EV 一档，共 31 档。
     * PTP 的 0x5010 是有符号 INT16，单位是 1/1000 EV（1000 = 1.0 EV）。
     */
    /**
     * 步长档位 → 文字。0=1/3、1=1/2、2=1；没读到（-999999 等）返回 null。
     */
    private fun stepLabel(v: Int): String? = when (v) {
        0 -> "1/3 步长"
        1 -> "1/2 步长"
        2 -> "1 步长"
        else -> null
    }

    /** 描述符里 INT16 的值是**无符号**读上来的（0xF9B6 → 64438），还原成有符号 */
    private fun asSigned16(v: Int): Int = v.toShort().toInt()

    /**
     * 曝光补偿的原始档位表（单位 1/1000 EV）。
     *
     * **优先读机身枚举**：b3 把步长改成 1/2 或 1 之后，机身自述的枚举自己就变了，
     * 不用我们在 App 里猜它认哪些值。读不到才按当前步长自己算。
     *
     * ⚠️ 自己算时**不能四舍五入**：0x5010 是 INT16、单位 1/1000 EV，尼康 1/3 步长的
     * 真实枚举是**截断值**（-1666 / -2333 / -2666，不是 -1667 / -2333 / -2667）。
     * 早先用 round 算，写 -1667 机身直接回 0x201C 拒掉 —— 这就是"点了没反应"的由来。
     * 整数除法天然向零截断，与机身一致。
     */
    private fun evRawLadder(): List<Int> {
        val fromCam = evSupportedRaw
        if (fromCam != null && fromCam.isNotEmpty()) {
            return fromCam.map { asSigned16(it) }.sorted()
        }
        return when (csmCompStep) {
            1 -> (-10..10).map { it * 500 }          // 1/2 步长
            2 -> (-5..5).map { it * 1000 }           // 1 步长
            else -> (-15..15).map { it * 1000 / 3 }  // 1/3 步长
        }
    }

    /**
     * 曝光补偿档位表（显示文字 + 写给机身的原始值）。
     *
     * 方块里只放数字（"+1.0" / "0.0" / "-0.3"）：单位 EV 写在二级页标题和说明上，
     * 31 块全带后缀会把方块挤宽。
     */
    private fun evSteps(): List<Pair<String, Int>> = evRawLadder().map { v ->
        val text = (if (v > 0) "+%.1f" else "%.1f").format(v / 1000.0)
        text to v
    }

    /** 后台读一次 b1 / b2 / b3，读完在 UI 线程回调。机身拨盘改了也能跟着变。 */
    private fun readExposureSteps(onDone: () -> Unit) {
        thread {
            csmIsoStep = readStepProp(PtpConstants.CSM_B1_ISO_STEP)
            csmEvStep = readStepProp(PtpConstants.CSM_B2_EV_STEP)
            csmCompStep = readStepProp(PtpConstants.CSM_B3_EV_STEP_COMP)
            runOnUiThread(onDone)
        }
    }

    private fun readStepProp(prop: Int): Int = try {
        ptpConnection?.getDevicePropValue(prop) ?: -999999
    } catch (_: Exception) {
        -999999
    }

    /**
     * 步长选择块：塞进曝光参数二级页（快门 / 光圈 / ISO / 曝光补偿各一块）。
     *
     * 机身上改步长要进个人设定菜单 b1 / b2 / b3 翻三层，而它直接决定这一页列哪些档 ——
     * 就地放一块，改完当页就能按新步长重列。
     */
    private fun stepSelectCard(
        label: String,
        options: List<Int>,
        current: Int,
        onPicked: (Int) -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addView(TextView(this@MainActivity).apply {
            text = label
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            letterSpacing = 0.08f
            setPadding(dpx(8), dpx(10), dpx(6), dpx(4))
        })
        val labels = options.map { stepLabel(it) ?: "值 $it" }
        addView(sheetTileGrid(options.size.coerceAtMost(3), labels, options.indexOf(current)) { idx ->
            onPicked(options[idx])
        }.view)
    }

    /**
     * 写一步长设定（后台调用）；机身认了返回 true。
     * 宽度先按 UINT8 试、不行再 UINT16 —— 和个人设定菜单里写 b 组用的是同一套顺序。
     */
    private fun writeStepProp(prop: Int, value: Int): Boolean = try {
        var ok = ptpConnection?.setDevicePropValue(prop, value, size = 1, maxRetries = 1) ?: false
        if (!ok) ok = ptpConnection?.setDevicePropValue(prop, value, size = 2, maxRetries = 1) ?: false
        ok
    } catch (_: Exception) {
        false
    }

    /** 步长设定写完之后：改缓存 + 提示 + 关页（档位表变了，得重开一次才看得到新表） */
    private fun onStepWritten(prop: Int, value: Int, ok: Boolean, dismiss: () -> Unit) {
        val name = stepLabel(value) ?: "值 $value"
        if (ok) {
            when (prop) {
                PtpConstants.CSM_B1_ISO_STEP -> csmIsoStep = value
                PtpConstants.CSM_B2_EV_STEP -> {
                    csmEvStep = value
                    // 步长换了，缓存的快门档位表作废：下次打开按机身新表重读。
                    // 否则"取并集"会把旧步长的档位一直留着，而机身已经不认那些值了
                    cameraSupportedShutters = null
                }
                PtpConstants.CSM_B3_EV_STEP_COMP -> {
                    csmCompStep = value
                    evSupportedRaw = null   // 步长变了，缓存的枚举作废，下次重新问机身
                }
            }
            log("曝光步长已设为 $name")
            toast("已设为 $name · 重开本页按新步长列档")
            dismiss()
        } else {
            val code = ptpConnection?.lastSetPropError ?: 0
            toast("写入失败：机身拒绝了 $name（0x${Integer.toHexString(code).uppercase()}）")
        }
    }

    /** 机身当前 EV 在档位表里最接近的那一项下标；读不到就 -1（不打勾）。 */
    private fun currentEvIndex(steps: List<Pair<String, Int>>): Int {
        if (currentEvRaw == -999999) return -1
        var best = -1
        var bestDiff = Int.MAX_VALUE
        for (i in steps.indices) {
            val diff = kotlin.math.abs(steps[i].second - currentEvRaw)
            if (diff < bestDiff) { bestDiff = diff; best = i }
        }
        return if (bestDiff > 200) -1 else best
    }

    /** 取景框显示的档位名（参数页那一行的右侧文字 + 二级页方块） */
    private fun lvGridLabel(style: FocusBoxView.GridStyle): String = when (style) {
        FocusBoxView.GridStyle.OFF -> "关闭"
        FocusBoxView.GridStyle.THIRDS -> "三分线"
        FocusBoxView.GridStyle.THIRDS_CENTER -> "三分线+中心"
        FocusBoxView.GridStyle.DENSE -> "密集网格"
    }

    /**
     * 取景框显示（构图网格）二级页。
     *
     * 纯 App 侧的显示开关：画在实时取景画面上，不写任何机身属性 ——
     * 机身那条（个人设定 d2「取景器网格显示」）在 d 区几个候选码上撞车，
     * 属性码没落实之前不猜、不发写命令。
     */
    private fun showLvGridSheet() {
        val styles = FocusBoxView.GridStyle.values().toList()
        val labels = styles.map { lvGridLabel(it) }
        val page = buildSheetPage("取景框显示", "实时取景画面上叠加的构图辅助线")
        val card = sheetCard(page.content, null)
        card.addView(sheetTileGrid(2, labels, styles.indexOf(lvGridStyle)) { idx ->
            lvGridStyle = styles[idx]
            focusBox.gridStyle = lvGridStyle
            getSharedPreferences(AF_PREFS, Context.MODE_PRIVATE)
                .edit().putInt(LV_GRID_KEY, idx).apply()
            if (::lvGridRow.isInitialized) lvGridRow.value.text = labels[idx]
            log("取景框显示：${labels[idx]}")
            page.dialog.dismiss()
        }.view)
        page.content.addView(
            sheetHint("只画在 App 的取景画面上，不写机身。机身那条（个人设定 d2「取景器网格显示」）的属性码还没确认，先不碰。")
        )
        page.show()
    }

    /**
     * 快门档位二级页。
     *
     * 档位表**每次打开都向机身重读一遍**，再和连接时那次取并集 ——
     * 之前只在连接成功那一刻读一次，之后整场会话都不再问机身；
     * 要是那一刻机身正好处于受限状态（取景开着、闪光灯同步限制、包围曝光进行中、
     * 曝光模式把可选范围收窄了），拿到的就是一份短表，用户看到的就是
     * "能选的档位变少了"，而且不重连 App 就回不来。
     */
    private fun showShutterSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        if (shutters.isEmpty()) { toast("还没读到机身快门档位表"); return }

        val page = buildSheetPage(
            "快门速度",
            "当前 ${shutters.getOrElse(currentShIndex) { 0 to "—" }.second} · ${shutters.size} 档"
        )
        val card = sheetCard(page.content, null)
        val status = sheetStatusText("正在向机身重读档位…")

        // 网格要能整块重建：档位表可能刚被机身刷新过（受限状态解除后档会变多）
        var gridRef: SheetTileGrid? = null
        fun renderGrid() {
            card.removeAllViews()
            val grid = sheetTileGrid(4, shutters.map { it.second }, currentShIndex) { index ->
                if (index >= 0 && index < shutters.size) {
                    currentShIndex = index
                    setShutter(shutters[index].first, shutters[index].second)
                }
                page.dialog.dismiss()
            }
            gridRef = grid
            card.addView(grid.view)
        }
        renderGrid()
        page.content.addView(status)
        // 快门档位直接来自机身描述符，b2 步长一变它自己就跟着变；这里补一块步长设定 + 提示
        page.content.addView(evStepControlCard { page.dialog.dismiss() })
        val hint = sheetHint("档位 1/8000 → 30s 由快到慢，B 门 / 闪光同步在最后。${evControlStepHint()}")
        page.content.addView(hint)
        page.show()
        // ★ 打开就把当前档那一行滚到中间：50 多档要滚好几屏，
        // 停在表头的话用户得从 1/8000 一路划到 30s 才知道自己在哪；
        // 居中之后往上滑是更快的档、往下滑是更慢的档，方向才对得上。
        gridRef?.centerOn(currentShIndex)

        val before = shutters.size
        thread {
            val after = refreshShutterLadder()
            val b2 = readStepProp(PtpConstants.CSM_B2_EV_STEP)
            runOnUiThread {
                if (b2 != -999999) csmEvStep = b2
                when {
                    after <= 0 -> status.text = "这次没读到描述符，沿用已缓存的 $before 档"
                    after != before -> {
                        status.text = "机身这次给了 $after 档（此前缓存 $before 档），已补全"
                        renderGrid()
                        gridRef?.centerOn(currentShIndex)
                    }
                    else -> status.text = "机身当前可选 $after 档，与缓存一致"
                }
                hint.text = "档位 1/8000 → 30s 由快到慢，B 门 / 闪光同步在最后。${evControlStepHint()}"
            }
        }
    }

    /**
     * 重新向机身要一次快门档位表，和已缓存的那份取**并集**后重排。
     *
     * 取并集而不是直接替换：机身受限时给的是短表，恢复后只会多不会少 ——
     * 用并集不会因为"这次恰好读到了受限状态"而把档位弄丢。
     *
     * @return 合并后的档位数；<=0 表示这次没读到（保留旧表）
     */
    private fun refreshShutterLadder(): Int {
        val conn = ptpConnection ?: return -1
        val fresh = conn.getDevicePropSupportedValues(activeShutterProp)
        if (fresh == null || fresh.isEmpty()) return -1

        val merged = LinkedHashSet<Int>()
        cameraSupportedShutters?.forEach { merged.add(it) }
        fresh.forEach { merged.add(it) }
        // B 门 / 闪光同步：D300 的 0xD100 描述符里**不带**这两档（枚举只到 30s），
        // 但机身上确实存在、手动控快门时也用得到，所以在这里补齐，挂在 30s 之后。
        merged.add(SHUTTER_BULB)
        merged.add(SHUTTER_FLASH)
        // 排序：正常档按曝光时长升序（1/8000 → 30s），B 门 / 闪光同步这两个负值档垫底
        val sorted = shuttersSorted(merged)

        val oldValue = shutters.getOrNull(currentShIndex)?.first
        cameraSupportedShutters = sorted.toIntArray()
        shutterMap = sorted.associateWith { getShutterString(it) }
        shutters = sorted.map { it to getShutterString(it) }.toTypedArray()
        // 表重排过，当前档要按值重新定位，不能沿用旧下标
        if (oldValue != null) {
            val idx = shutters.indexOfFirst { it.first == oldValue }
            if (idx >= 0) currentShIndex = idx
        }
        if (currentShIndex !in shutters.indices) currentShIndex = 0
        log("快门档位：机身自述 ${fresh.size} 档，合并后 ${shutters.size} 档")
        return shutters.size
    }

    /**
     * 曝光补偿菜单：挂在拍摄页那颗显示 EV 的芯片上。
     *
     * 和 ISO / 光圈 / 快门同一套二级页（底部抽屉 + 4 列方块）——
     * 以前用的是挂在芯片下方的下拉列表，31 档挤在窄条里得滚着找，
     * 而且和旁边几颗芯片点开的样式不一致。
     */
    private fun showEvSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        // 先用缓存的档位表把页面铺出来，后台读到机身枚举 / b3 之后再重建一次
        var steps = evSteps()
        val page = buildSheetPage(
            "曝光补偿",
            "当前 ${evLabel(currentEvRaw)} · ${evStepSummary()}"
        )
        val card = sheetCard(page.content, null)
        val status = sheetStatusText("点一下立即生效")
        val hint = sheetHint(evStepHint())

        /** 档位表被刷新过，网格要能整块重建 */
        fun renderGrid() {
            card.removeAllViews()
            card.addView(sheetTileGrid(4, steps.map { it.first }, currentEvIndex(steps)) { index ->
                val (label, raw) = steps[index]
                // 写成功才关页；失败要留在这一页让用户看见机身为什么拒（多半是步长对不上）
                status.text = "正在写入 $label EV …"
                thread {
                    try {
                        isPollingPaused = true
                        log("正在设置曝光补偿：$label（原始值 $raw）…")
                        val ok = ptpConnection?.setDevicePropValue(
                            PtpConstants.PROP_EXPOSURE_BIAS_COMPENSATION, raw, size = 2
                        ) ?: false
                        val code = ptpConnection?.lastSetPropError ?: 0
                        runOnUiThread {
                            if (ok) {
                                log("曝光补偿已设为 $label EV")
                                showTransientStatus("曝光补偿 $label EV")
                                page.dialog.dismiss()
                            } else {
                                status.text = "机身拒绝了 $label EV" +
                                    "（0x${Integer.toHexString(code).uppercase()}）—— 通常是步长对不上，" +
                                    "试试下面改一下曝光补偿步长"
                                log("曝光补偿设置失败：$label EV，机身返回 0x${Integer.toHexString(code).uppercase()}")
                            }
                        }
                        settleAndRefresh()
                    } catch (e: Exception) {
                        runOnUiThread { log("设置曝光补偿出错：${e.message}") }
                    } finally {
                        isPollingPaused = false
                    }
                }
            }.view)
        }
        renderGrid()
        page.content.addView(status)
        page.content.addView(
            stepSelectCard("曝光补偿步长（个人设定 b3）", listOf(0, 1, 2), csmCompStep) { v ->
                thread {
                    val ok = writeStepProp(PtpConstants.CSM_B3_EV_STEP_COMP, v)
                    runOnUiThread { onStepWritten(PtpConstants.CSM_B3_EV_STEP_COMP, v, ok) { page.dialog.dismiss() } }
                }
            }
        )
        page.content.addView(hint)
        page.show()

        // 后台：读机身枚举 + b3，读到了就按真实档位重建网格（步长非 1/3 时尤其重要）
        thread {
            val enum = try {
                ptpConnection?.getDevicePropSupportedValues(PtpConstants.PROP_EXPOSURE_BIAS_COMPENSATION)
            } catch (_: Exception) {
                null
            }
            val b3 = readStepProp(PtpConstants.CSM_B3_EV_STEP_COMP)
            runOnUiThread {
                if (b3 != -999999) csmCompStep = b3
                if (enum != null && enum.isNotEmpty()) evSupportedRaw = enum
                val fresh = evSteps()
                if (fresh != steps) {
                    steps = fresh
                    renderGrid()
                }
                hint.text = evStepHint()
                log("曝光补偿档位：${steps.size} 档 · ${evStepSummary()}")
            }
        }
    }

    /** 二级页副标题里那句"按什么步长、共几档" */
    private fun evStepSummary(): String {
        val s = stepLabel(csmCompStep) ?: "1/3 步长"
        return "-5 … +5 EV · 机身 $s"
    }

    /** 页面底部那句提示：告诉用户当前步长来自机身哪一项、以及改了会怎样 */
    private fun evStepHint(): String {
        val s = stepLabel(csmCompStep) ?: "1/3 步长"
        return "机身「个人设定 b3 · 曝光补偿步长」当前为 $s，本页档位按它列出。" +
            "改了步长机身会立刻按新步长走，本页重开一次即可看到新档位。"
    }

    /** 机身报回来的 EV 原始值 → "+0.3 EV" 这样的文字；读不到就 "—" */
    private fun evLabel(raw: Int): String {
        if (raw == -999999) return "—"
        val ev = raw / 1000.0
        return "${if (ev > 0) "+%.1f".format(ev) else "%.1f".format(ev)} EV"
    }

    /**
     * 曝光模式菜单。挂在拍摄页顶部那颗显示 M/P/A/S 的芯片上。
     * 档位名中文化，写回机身的还是它认的那几个编号（1=M / 2=P / 3=A / 4=S）。
     */
    private fun showExposureModeMenu(anchor: View) {
        val modes = listOf("M · 手动曝光", "P · 程序自动", "A · 光圈优先", "S · 快门优先")
        val values = listOf(1, 2, 3, 4)
        showValueMenu(anchor, modes, values.indexOf(currentModeValue)) { index ->
            val selectedMode = values[index]
            val modeTitle = modes[index]
            thread {
                val wasLiveViewRunning = isLiveViewRunning
                if (wasLiveViewRunning) {
                    stopLiveView()
                    Thread.sleep(500)
                }

                log("正在切换曝光模式：$modeTitle …")
                try {
                    val success = ptpConnection?.setDevicePropValue(
                        PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, selectedMode, size = 2
                    ) ?: false
                    runOnUiThread {
                        if (success) {
                            log("曝光模式已切换")
                        } else {
                            log("曝光模式切换失败（D300 的曝光模式由机身拨盘控制）")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { log("曝光模式切换出错：${e.message}") }
                }

                Thread.sleep(500)
                updateProperties()

                if (wasLiveViewRunning) {
                    Thread.sleep(500)
                    startLiveView()
                }
            }
        }
    }

    /** 菜单行适配器：当前档打勾 + 强调色字 */
    private inner class MenuAdapter(
        items: List<String>,
        private val selectedIndex: Int
    ) : ArrayAdapter<String>(this, R.layout.item_menu_row, R.id.menuLabel, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val label = view.findViewById<TextView>(R.id.menuLabel)
            val check = view.findViewById<TextView>(R.id.menuCheck)
            val isCurrent = position == selectedIndex
            check.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            label.setTextColor(
                if (isCurrent) getColor(R.color.accent_amber) else getColor(R.color.text_primary)
            )
            label.textSize = 14f
            return view
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()

    // ===================== 个人设定 =====================

    /**
     * 个人设定（机身菜单里的 a/b/c/d/e/f 组）。
     *
     * ⚠️ 这里**没有**硬编码"属性码 → 菜单名"的对照表，原因是拿不到可靠证据：
     * 官方模块（Type0001.md3）里这些属性是查表访问的，代码里不出现立即数，
     * 静态反编译配不出名称；机身侧也不会通过 PTP 返回菜单名。
     * 硬猜一个表写进 App，写错就是静默改坏机身设置 —— 那比不做更糟。
     *
     * 所以做成"先问后改"：扫描机身自述的属性（类型/当前值/可写/值域），
     * 列出来由用户按选项数去对机身菜单档位，确认了再改。
     */
    // ------------------------------------------------------------------
    // 个人设定菜单（CSM）
    //
    // 与下面那段"扫描原始属性"的分工：扫描是排错用的，它只报一堆 0xD0xx 码；
    // 这里才是真正的菜单 —— 名称已知，要的是"读出机身现在设的档位、改掉它"。
    // ------------------------------------------------------------------

    /** 这一项实际要用的属性码：已确认的用表里的，扫描匹配出来的用覆盖值 */
    private fun csmCode(item: CsmItem): Int = csmCodeOverrides[item.id] ?: item.code

    private fun loadCsmCodeOverrides() {
        val sp = getSharedPreferences("csm_codes", Context.MODE_PRIVATE)
        csmCodeOverrides.clear()
        // 存的是 "a4" -> 0xD046 这种，键就是机身菜单编号，肉眼也能核对
        for ((k, _) in sp.all) {
            val v = sp.getInt(k, 0)
            if (v != 0) csmCodeOverrides[k] = v
        }
        // 自愈：老版本扫描没有互斥检查，d2/d3/d8/d11 曾全匹配到同一个 0xD06B。
        // 两个项共用一个码 = 至少一个错，把撞码的全部作废；跟已确认项撞码的同样作废。
        val verifiedCodes = CsmMenu.items.filter { it.verified }.map { it.code }.toSet()
        val byCode = HashMap<Int, MutableList<String>>()
        for ((id, code) in csmCodeOverrides) byCode.getOrPut(code) { mutableListOf() }.add(id)
        val dropped = mutableListOf<String>()
        for ((code, ids) in byCode) {
            if (ids.size > 1 || code in verifiedCodes) {
                for (id in ids) { csmCodeOverrides.remove(id); dropped.add("$id(0x${Integer.toHexString(code).uppercase()})") }
                getSharedPreferences("csm_codes", Context.MODE_PRIVATE).edit().also { e ->
                    ids.forEach { e.remove(it) }
                }.apply()
            }
        }
        if (dropped.isNotEmpty()) {
            log("个人设定：作废了撞码的匹配 ${dropped.joinToString("、")}，等重新扫描或手动指定")
        }
    }

    private fun saveCsmCodeOverride(id: String, code: Int) {
        csmCodeOverrides[id] = code
        getSharedPreferences("csm_codes", Context.MODE_PRIVATE).edit().putInt(id, code).apply()
    }

    /**
     * 长按某一项 → 手动指定属性码。
     *
     * 扫描匹配是按"档数相同"猜的，遇到多个候选就不敢自动采纳；这时候用户在机身上拨一下那项、
     * 对照 App 读回来的值变没变，是最可靠的判定。这个对话框就是给他自己敲码试的：
     * 填完立刻 readPropDesc 试读，读到就把码存下来（覆盖扫描结果），读不到就提示换一个。
     */
    private fun showCsmCodeDialog(item: CsmItem, valueView: TextView) {
        val input = EditText(this).apply {
            setText(
                csmCode(item).takeIf { it != PtpConstants.CSM_CODE_UNKNOWN }
                    ?.let { "0x" + Integer.toHexString(it).uppercase() } ?: ""
            )
            hint = "例如 0xD04B"
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_disabled))
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * resources.displayMetrics.density).toInt(), 0,
                (20 * resources.displayMetrics.density).toInt(), 0)
            addView(TextView(this@MainActivity).apply {
                text = "${item.label}\n说明书档数：${item.expectedCount} 档"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("手动指定属性码")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("试读并保存") { _, _ ->
                val raw = input.text.toString().trim()
                val code = raw.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                if (code == null) { toast("格式不对，填十六进制，如 0xD04B"); return@setPositiveButton }
                trySetCsmCode(item, code, valueView)
            }
            .show()
    }

    /** 试着用这个码读一次机身；读到了才落盘，读不到说明码不对，别写进机身。 */
    private fun trySetCsmCode(item: CsmItem, code: Int, valueView: TextView) {
        if (!isConnected) { toast("请先连接相机"); return }
        thread {
            val desc = try { ptpConnection?.readPropDesc(code) } catch (_: Exception) { null }
            runOnUiThread {
                if (desc == null) {
                    log("0x${Integer.toHexString(code).uppercase()} 对 ${item.label} 无应答，换一个码试试")
                    toast("机身没应答这个码")
                    return@runOnUiThread
                }
                val min = desc.allowed.minOrNull() ?: -1
                val max = desc.allowed.maxOrNull() ?: -1
                saveCsmCodeOverride(item.id, code)
                log("${item.label} → 0x${Integer.toHexString(code).uppercase()} 已保存" +
                    "（值域 ${min}..${max}，共 ${desc.allowed.size} 档，" +
                    "说明书 ${item.expectedCount} 档）")
                valueView.text = "…"
                buildCsmSkeleton()
                loadCsmValues()
            }
        }
    }

    /**
     * 个人设定菜单（二级页）。
     *
     * 为什么收进抽屉：a–f 共 50 项、每项一行，铺在参数页会撑出两千多 dp，
     * 参数页往下要滑很久才到「实时取景模式」。收进来之后参数页只剩一行入口。
     *
     * **打开即读**：先搭骨架（每项一行、值栏留空），再逐项问机身，用户不用先点"读取"。
     * 读取期间按钮显示 0/50 → 50/50 的进度，读完自动变成可点的「重新读取」。
     */
    /** 当前正在看哪一组（组内页"重新读取"用它；null = 全部） */
    private var csmActiveGroup: CsmGroup? = null

    // ------------------------------------------------------------------
    // NCP 滤镜导入
    //
    // 「滤镜」这里指尼康的**个人设定优化校准**：Picture Control Utility 做出来的
    // .ncp 文件。说明书第 158–160 页写得很清楚：
    //   · 卡上最多存 99 个（插槽 1–99，文件就是根目录的 PICCONnn.NCP）
    //   · 机身用 管理优化校准 → 载入/保存 → 复制到相机，把卡上的插槽装进 C-1…C-9
    // 所以 PTP 这边要做的事只有一件：**把文件写进卡根目录**（0x100C + 0x100D），
    // 不需要任何私有命令。装进相机那一步还是得用户在机身上按（机身菜单不走 PTP）。
    // ------------------------------------------------------------------

    /** 卡根目录上已经占了哪些插槽（PICCONnn.NCP 的 nn） */
    private fun readNcpSlots(): List<Int> {
        val ptp = ptpConnection ?: return emptyList()
        val storage = ptp.firstStorageId()
        if (storage == 0) return emptyList()
        val handles = ptp.getObjectHandlesIn(storage, 0) ?: return emptyList()
        val out = mutableListOf<Int>()
        for (h in handles) {
            val info = ptp.getObjectInfo(h) ?: continue
            val m = Regex("PICCON(\\d{2})\\.NCP", RegexOption.IGNORE_CASE).find(info.filename) ?: continue
            m.groupValues[1].toIntOrNull()?.let { out.add(it) }
        }
        return out.sorted()
    }

    private fun showNcpImportSheet(uri: android.net.Uri) {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            toast("读不到这个文件")
            return
        }
        val name = runCatching {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && it.moveToFirst()) it.getString(idx) else null
            }
        }.getOrNull() ?: "已选文件"

        val page = buildSheetPage("NCP 滤镜导入")
        val status = sheetStatusText("$name · ${bytes.size} 字节")
        page.content.addView(status)

        val card = sheetCard(page.content, "卡上的插槽")
        var slot = 1
        var grid: SheetTileGrid? = null
        grid = sheetTileGrid(4, (1..99).map { "%02d".format(it) }, 0) { idx ->
            slot = idx + 1
            grid?.select(idx)
        }
        card.addView(grid.view)
        page.content.addView(sheetHint(
            "文件会写成卡根目录的 PICCONnn.NCP。传完之后到机身上：管理优化校准 → " +
                "载入/保存 → 复制到相机，选一个 C-1…C-9 的位置装进去。已占用的插槽会被覆盖。"
        ))

        val goBtn = sheetPrimaryButton("导入到机身卡（插槽 01）")
        goBtn.setOnClickListener {
            val target = slot
            goBtn.isEnabled = false
            status.text = "正在写入 PICCON%02d.NCP …".format(target)
            thread {
                val storage = ptpConnection?.firstStorageId() ?: 0
                val fileName = "PICCON%02d.NCP".format(target)
                val handle = ptpConnection?.sendFileToStorage(fileName, bytes, storage) ?: -1
                runOnUiThread {
                    goBtn.isEnabled = true
                    if (handle > 0) {
                        status.text = "$fileName 已写入机身卡（句柄 0x${Integer.toHexString(handle).uppercase()}）"
                        toast("已写入 $fileName，请到机身菜单载入")
                    } else {
                        status.text = "写入失败：机身拒绝了（详见日志）。换个插槽或先解锁卡试试。"
                    }
                }
            }
        }
        page.content.addView(goBtn)
        page.show()

        // 开页后把卡上已占用的插槽报出来：不然用户可能一不留神覆盖掉自己已有的滤镜
        thread {
            val used = readNcpSlots()
            runOnUiThread {
                if (used.isEmpty()) {
                    status.text = "$name · ${bytes.size} 字节 · 卡上还没有 NCP 滤镜"
                } else {
                    val list = used.joinToString("、") { "%02d".format(it) }
                    status.text = "$name · ${bytes.size} 字节 · 卡上已占用：$list"
                    // 默认落在第一个空插槽上
                    val free = (1..99).firstOrNull { it !in used }
                    if (free != null) {
                        slot = free
                        grid?.select(free - 1)
                    }
                }
                goBtn.text = "导入到机身卡（插槽 %02d）".format(slot)
            }
        }
    }

    /**
     * 个人设定菜单 —— **第一层：分区列表**（a/b/c/d/e/f），点一组进第二层。
     *
     * 机身菜单本身就是这么分的，52 项平铺在一页里滚起来太累；
     * 分区页每行还顺手显示"已适配 X/Y 项"，哪组还有没适配的项一眼就能看见。
     */
    private fun showCsmSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        csmActiveGroup = null
        val page = buildSheetPage("个人设定菜单")

        for (group in CsmMenu.groups) {
            val adapted = group.items.count { csmCode(it) != PtpConstants.CSM_CODE_UNKNOWN }
            val row = sheetValueRow(
                group.title,
                "$adapted/${group.items.size} 项"
            ) { _ -> showCsmGroupSheet(group) }
            // 整组都没适配的压暗一点，提示这组还没对上属性码
            row.view.alpha = if (adapted == 0) 0.55f else 1f
            page.content.addView(row.view)
        }

        // ---- 菜单最后一行：重设个人设定 ----
        //
        // 机身菜单里它也摆在个人设定菜单的末尾，所以跟着机身的排布放这儿。
        // 但**不放回「个人设定库」组里当「否 / 是」两档**：那是一次性动作（写 1 = 立刻恢复出厂），
        // 做成选档太容易误触且无法挽回 —— 点这一行进警告页，确认之后才发命令（和格式化存储卡同一套）。
        val resetEntry = sheetValueRow("重设个人设定", "不可撤销") { _ ->
            if (!isConnected) toast("请先连接相机") else showCsmResetSheet()
        }
        resetEntry.value.setTextColor(getColor(R.color.accent_red))
        page.content.addView(resetEntry.view)

        // ---- 维护区：整组级别的操作放这里 ----
        csmScanButton = sheetActionButton("扫描未适配项", R.color.text_secondary) { }
        page.content.addView(csmScanButton)

        customScanButton = sheetActionButton("扫描原始属性（排错用）", R.color.text_secondary) { }
        // 扫描结果的说明：扫之前不显示那行"机身不会通过 PTP…"（那是协议细节，没用），
        // 扫完由 renderCustomProps() 填上实际结果再显示出来
        customHint = sheetHint("")
        customHint.visibility = View.GONE
        customList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.content.addView(customScanButton)
        page.content.addView(customHint)
        page.content.addView(customList)

        page.show()

        setupCsmScanControl()
        setupCustomSettingsControls()
        refreshCsmScanButtonText()
    }

    /**
     * 个人设定菜单 —— **第二层：某一组的项目**。
     *
     * 进组就自动读，只读本组的那几项（不再把 52 项全问一遍，快得多）。
     */
    private fun showCsmGroupSheet(group: CsmGroup) {
        if (!isConnected) { toast("请先连接相机"); return }
        csmActiveGroup = group
        val page = buildSheetPage(group.title, "${group.items.size} 项 · 打开自动读取")

        csmReadButton = sheetActionButton("读取中…") { }
        csmHint = sheetHint("点任一项切换档位；长按可手输属性码。")
        csmList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        page.content.addView(csmReadButton)
        page.content.addView(csmHint)
        page.content.addView(csmList)

        page.show()

        setupCsmReadControl()
        loadCsmValues(group)
    }

    /** 抽屉里的动作按钮：与参数页的行同款底纹，高度略矮 */
    private fun sheetActionButton(
        text: String,
        colorRes: Int = R.color.text_primary,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13.5f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dpx(14), 0, dpx(12), 0)
        stateListAnimator = null
        setBackgroundResource(R.drawable.bg_setting_row)
        setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpx(46)
        ).apply { topMargin = dpx(2) }
        setOnClickListener { onClick() }
    }

    private fun dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 拍摄菜单二级页里每一项的取值视图，键是清单里的项 */
    private val shootRows = LinkedHashMap<ShootingMenu.Item, SheetValueRow>()

    /** 设置菜单的行缓存（结构同 [shootRows]，两个菜单各自独立） */
    private val setupRows = LinkedHashMap<ShootingMenu.Item, SheetValueRow>()

    /**
     * 拍摄菜单（二级页）。
     *
     * 和个人设定不同，这里每一行都能直接对应到一个 libgphoto2 里有名字的属性码
     * （[ShootingMenu]），所以不需要"扫描匹配"这一步：打开就逐项读描述符，
     * 有描述符就能显示当前档位、能写就允许改，没有的标「不支持」。
     *
     * 只读项（机身标 GetSet=0）会显示「只读」，点了不写 —— 例如取景状态这类。
     */
    private fun showShootingMenuSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage(
            "拍摄菜单",
            "机身上「拍摄菜单」里的项 · ${ShootingMenu.items.size} 项"
        )
        val sheetStatus = sheetStatusText("正在读取机身设置…")
        page.content.addView(sheetStatus)
        page.show()

        shootRows.clear()
        for ((title, list) in ShootingMenu.groups) {
            val card = sheetCard(page.content, title)
            for (item in list) {
                // 回调里要用这一行自己的值视图 —— 直接闭包引用 row 会"自己引用自己"，编译不过
                var rowRef: SheetValueRow? = null
                val row = sheetValueRow(item.name, "…") { _ ->
                    if (!isConnected) {
                        toast("请先连接相机")
                    } else {
                        showEnumPropSheet(
                            item.name,
                            "0x%04X · 机身「拍摄菜单」".format(item.code),
                            item.code,
                            { v -> ShootingMenu.label(item, v) },
                            item.hint,
                            item.hostMode
                        ) { label -> rowRef?.value?.text = label }
                    }
                }
                rowRef = row
                card.addView(row.view)
                shootRows[item] = row
            }
        }

        // 拍摄菜单库名（0xD011–0xD014）也是字符串属性，单独入口
        val bankCard = sheetCard(page.content, "库名（文字）")
        bankCard.addView(sheetHint("四个拍摄菜单库各有一个名字，也是字符串属性，用文字编辑页改。"))
        val editBank = sheetValueRow("编辑拍摄菜单库名", "A / B / C / D") { _ ->
            pickBankThenEditName(
                "拍摄菜单",
                listOf(
                    PtpConstants.PROP_NIKON_SHOOTING_BANK_NAME_A,
                    PtpConstants.PROP_NIKON_SHOOTING_BANK_NAME_B,
                    PtpConstants.PROP_NIKON_SHOOTING_BANK_NAME_C,
                    PtpConstants.PROP_NIKON_SHOOTING_BANK_NAME_D
                )
            )
        }
        bankCard.addView(editBank.view)

        // ---- 白平衡预设注释（0xD020–0xD024）：字符串属性，跟版权注释同一套编辑页 ----
        val wbCard = sheetCard(page.content, "白平衡预设注释（文字）")
        wbCard.addView(sheetHint(
            "机身测完白平衡之后可以给 d-1…d-5 各起一个名字。这五个是字符串属性，" +
                "数值写入会被机身拒绝，所以走文字编辑页。\n" +
                "对应的「数据」（0xD025–0xD029）在上面那一组里，只能看不能手改。"
        ))
        val wbCodes = intArrayOf(
            PtpConstants.PROP_NIKON_WB_PRESET_COMMENT0,
            PtpConstants.PROP_NIKON_WB_PRESET_COMMENT1,
            PtpConstants.PROP_NIKON_WB_PRESET_COMMENT2,
            PtpConstants.PROP_NIKON_WB_PRESET_COMMENT3,
            PtpConstants.PROP_NIKON_WB_PRESET_COMMENT4
        )
        wbCodes.forEachIndexed { idx, code ->
            val row = sheetValueRow("预设 d-${idx + 1} 注释", "点击编辑") { _ ->
                showTextPropSheet("预设 d-${idx + 1} 注释", code, "给这个白平衡预设起的名字", 20)
            }
            wbCard.addView(row.view)
        }

        // ---- 重设拍摄菜单（0xD015）：一次性动作，同样只给红色确认按钮 ----
        val resetRow = sheetValueRow("重设拍摄菜单", "不可撤销") { _ ->
            if (!isConnected) toast("请先连接相机") else showShootingResetSheet()
        }
        resetRow.value.setTextColor(getColor(R.color.accent_red))
        page.content.addView(resetRow.view)

        val wasPaused = isPollingPaused
        isPollingPaused = true
        thread {
            var supported = 0
            var writable = 0
            try {
                for (item in ShootingMenu.items) {
                    if (!isConnected) break
                    val desc = try {
                        ptpConnection?.readPropDesc(item.code)
                    } catch (_: Exception) {
                        null
                    }
                    if (desc != null) {
                        supported++
                        if (desc.writable) writable++
                    }
                    runOnUiThread {
                        val row = shootRows[item] ?: return@runOnUiThread
                        if (desc == null) {
                            row.value.text = "不支持"
                            row.view.alpha = 0.5f
                        } else {
                            val suffix = if (desc.writable) "" else " · 只读"
                            row.value.text = ShootingMenu.label(item, desc.currentValue) + suffix
                            row.view.alpha = if (desc.writable) 1f else 0.7f
                        }
                    }
                }
            } finally {
                isPollingPaused = wasPaused
                runOnUiThread {
                    sheetStatus.text = if (supported == 0) {
                        "机身一项都没应答（多半正忙，或没在 PC 控制模式），连上后重开一次。"
                    } else {
                        "机身应答 $supported/${ShootingMenu.items.size} 项（可写 $writable 项）· 点一项改档位"
                    }
                }
            }
        }
    }

    /**
     * 设置菜单（Setup）：与拍摄菜单同一套渲染方式 —— 逐项读描述符，
     * 点一行进通用属性页改档位。含 AF 微调那一组（8 项）。
     */
    private fun showSetupMenuSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage(
            "设置菜单",
            "机身上「设置菜单」里的项 · ${SetupMenu.items.size} 项"
        )
        val sheetStatus = sheetStatusText("正在读取机身设置…")
        page.content.addView(sheetStatus)
        page.show()

        setupRows.clear()
        for ((title, list) in SetupMenu.groups) {
            val card = sheetCard(page.content, title)
            for (item in list) {
                var rowRef: SheetValueRow? = null
                val row = sheetValueRow(item.name, "…") { _ ->
                    if (!isConnected) {
                        toast("请先连接相机")
                    } else {
                        showEnumPropSheet(
                            item.name,
                            "0x%04X · 机身「设置菜单」".format(item.code),
                            item.code,
                            { v -> SetupMenu.label(item, v) },
                            item.hint,
                            item.hostMode
                        ) { label -> rowRef?.value?.text = label }
                    }
                }
                rowRef = row
                card.addView(row.view)
                setupRows[item] = row
            }
        }

        // 版权信息是**字符串属性**（0xD090），走不了上面那套数值档位页，单独给入口
        val textCard = sheetCard(page.content, "版权信息（文字）")
        textCard.addView(sheetHint(
            "版权注释是字符串属性，数值型写入会被机身拒绝，所以用这个文字编辑页写。" +
                "「附加版权信息」开关在上面「拍摄信息」组里。"
        ))
        val editComment = sheetValueRow("编辑版权注释", "点击编辑") { _ ->
            showTextPropSheet(
                "版权注释", PtpConstants.PROP_NIKON_COMMENT,
                "写进照片的拍摄者 / 版权文字", 36
            )
        }
        textCard.addView(editComment.view)

        // 个人设定库名（0xD041–0xD044）同样是字符串属性，入口一并放这里
        val editCsmBank = sheetValueRow("编辑个人设定库名", "A / B / C / D") { _ ->
            pickBankThenEditName(
                "个人设定",
                listOf(
                    PtpConstants.PROP_NIKON_CUSTOM_BANK_NAME_A,
                    PtpConstants.PROP_NIKON_CUSTOM_BANK_NAME_B,
                    PtpConstants.PROP_NIKON_CUSTOM_BANK_NAME_C,
                    PtpConstants.PROP_NIKON_CUSTOM_BANK_NAME_D
                )
            )
        }
        textCard.addView(editCsmBank.view)

        val wasPaused = isPollingPaused
        isPollingPaused = true
        thread {
            var supported = 0
            var writable = 0
            try {
                for (item in SetupMenu.items) {
                    if (!isConnected) break
                    val desc = try {
                        ptpConnection?.readPropDesc(item.code)
                    } catch (_: Exception) {
                        null
                    }
                    if (desc != null) {
                        supported++
                        if (desc.writable) writable++
                    }
                    runOnUiThread {
                        val row = setupRows[item] ?: return@runOnUiThread
                        if (desc == null) {
                            row.value.text = "不支持"
                            row.view.alpha = 0.5f
                        } else {
                            val suffix = if (desc.writable) "" else " · 只读"
                            row.value.text = SetupMenu.label(item, desc.currentValue) + suffix
                            row.view.alpha = if (desc.writable) 1f else 0.7f
                        }
                    }
                }
            } finally {
                isPollingPaused = wasPaused
                runOnUiThread {
                    sheetStatus.text = if (supported == 0) {
                        "机身一项都没应答（多半正忙，或没在 PC 控制模式），连上后重开一次。"
                    } else {
                        "机身应答 $supported/${SetupMenu.items.size} 项（可写 $writable 项）· 点一项改档位"
                    }
                }
            }
        }
    }

    /** 组内页：只绑「重新读取本组」 */
    private fun setupCsmReadControl() {
        loadCsmCodeOverrides()
        csmReadButton.isEnabled = false
        csmReadButton.setOnClickListener {
            if (!isConnected) { toast("请先连接相机"); return@setOnClickListener }
            if (csmLoading) return@setOnClickListener
            loadCsmValues(csmActiveGroup)
        }
    }

    /** 分区页：绑「扫描未适配项」（这是整机级别的动作） */
    private fun setupCsmScanControl() {
        loadCsmCodeOverrides()
        csmScanButton.isEnabled = false
        csmScanButton.setOnClickListener {
            if (!isConnected) { toast("请先连接相机"); return@setOnClickListener }
            if (csmLoading) return@setOnClickListener
            scanUnresolvedCsm()
        }
        refreshCsmScanButtonText()
    }

    private fun refreshCsmScanButtonText() {
        val left = CsmMenu.unresolved.count { csmCode(it) == PtpConstants.CSM_CODE_UNKNOWN }
        csmScanButton.text =
            if (left == 0) "扫描未适配项（已全部适配）" else "扫描未适配项（还有 $left 项）"
    }

    /**
     * 给属性码未知的项找码。
     *
     * 思路很土但足够安全：拿说明书上这一项的**档数**去跟机身报回来的值域档数对。
     * 比如 d10「MB-D10 电池类型」说明书上是 4 档，那就在候选区间里找哪个属性
     * 报出来的值域刚好是 0…3 —— 对上了就八九不离十。
     * 对不上、或者同时有几个候选的，一律只写日志不擅自采纳（多个候选时取第一个并记下全部）。
     */
    private fun scanUnresolvedCsm() {
        val targets = CsmMenu.unresolved.filter { csmCode(it) == PtpConstants.CSM_CODE_UNKNOWN }
        if (targets.isEmpty()) { toast("没有待扫描的项了"); return }

        csmLoading = true
        csmReadButton.isEnabled = false
        csmScanButton.isEnabled = false
        isPollingPaused = true

        thread {
            // 已经被确认项占用的码不能再分配给别人；别的项已经保存的覆盖码同样要避开，
            // 否则两轮扫描会把同一个码发给两个不同的 CSM 项（d2/d3/d8/d11 抢 0xD06B 就是这么来的）
            val taken = (
                CsmMenu.items.filter { it.verified }.map { it.code } +
                csmCodeOverrides.values
                ).toMutableSet()
            // 还要排除"别的未适配项也把此码列为候选"的码 —— 两个项抢一个码时谁都不能自动要
            val contested = CsmMenu.unresolved
                .filter { it.id != "" && it.candidates.isNotEmpty() }
                .flatMap { it.candidates }
                .toSet()
            var matched = 0
            try {
                for (item in targets) {
                    if (!isConnected) break
                    val expect = item.expectedCount
                    val found = ArrayList<Int>()
                    for (code in item.scanRange) {
                        if (code in taken) continue
                        if (!isConnected) break
                        val desc = try {
                            ptpConnection?.readPropDesc(code)
                        } catch (_: Exception) {
                            null
                        }
                        // 只读属性不可能是 CSM 档位（读回来也改不了），直接排除
                        if (desc?.writable != true) continue
                        val allowed = desc.allowed
                        if (allowed.isEmpty()) continue
                        val min = allowed.minOf { it }
                        val max = allowed.maxOf { it }
                        // CSM 的值域基本都从 0 起；档数还必须跟说明书一致
                        if (min == 0L && max - min + 1 == expect.toLong()) found.add(code)
                    }

                    val hexOf = { c: Int -> "0x${Integer.toHexString(c).uppercase()}" }
                    when {
                        found.isEmpty() -> runOnUiThread {
                            log("个人设定 ${item.id}（${item.name}）：候选区间里没找到 ${expect} 档的可写属性，跳过")
                        }
                        found.size == 1 && found.first() !in contested -> {
                            val code = found.first()
                            taken.add(code)
                            saveCsmCodeOverride(item.id, code)
                            matched++
                            runOnUiThread {
                                log("个人设定 ${item.id}（${item.name}）→ ${hexOf(code)}，档数 ${expect} 吻合，已记住")
                            }
                        }
                        else -> {
                            // 多个候选不自动采纳：猜错就是改坏机身设置，宁可不动
                            val reason = if (found.size == 1) "此码同时是其他未适配项的候选" else "候选有 ${found.size} 个"
                            runOnUiThread {
                                log("个人设定 ${item.id}（${item.name}）：$reason ${found.joinToString("、") { hexOf(it) }}，需人工确认，暂不采纳")
                            }
                        }
                    }
                    runOnUiThread {
                        csmHint.text = "扫描中…（${item.id}）"
                        refreshCsmScanButtonText()
                    }
                }
            } finally {
                runOnUiThread {
                    isPollingPaused = false
                    csmLoading = false
                    csmReadButton.isEnabled = isConnected
                    csmScanButton.isEnabled = isConnected
                    refreshCsmScanButtonText()
                    csmHint.text = if (matched > 0) {
                        "扫描完，新匹配上 $matched 项，点「重新读取」把它们的档位读出来。"
                    } else {
                        "扫描完，没有新匹配上的项。可以点「重新读取」确认已适配项的档位。"
                    }
                    log("个人设定扫描结束：新匹配 $matched 项")
                }
            }
        }
    }

    /** 先搭好骨架：分组标题 + 每项一行，右侧档位栏留空，读到再填 */
    private fun buildCsmSkeleton(only: CsmGroup? = null) {
        csmList.removeAllViews()
        csmValueViews.clear()
        val dp = resources.displayMetrics.density
        val padH = (14 * dp).toInt()
        val padV = (10 * dp).toInt()

        // only != null 时只铺这一组（组内页），否则铺全部（一般在分区页不会用到）
        for (group in (if (only != null) listOf(only) else CsmMenu.groups)) {
            csmList.addView(TextView(this).apply {
                text = group.title
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(padH, (10 * dp).toInt(), padH, (3 * dp).toInt())
            })

            for (item in group.items) {
                val pending = csmCode(item) == PtpConstants.CSM_CODE_UNKNOWN
                val valueView = TextView(this).apply {
                    text = when {
                        !pending -> "…"
                        item.candidates.size > 1 -> "候选 ${item.candidates.size} 个"
                        item.candidates.size == 1 -> "待确认"
                        else -> "待扫描"
                    }
                    textSize = 13f
                    setTextColor(getColor(R.color.text_disabled))
                    setPadding((12 * dp).toInt(), 0, 0, 0)
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = getDrawable(R.drawable.bg_setting_row)
                    isClickable = true
                    setPadding(padH, padV, padH, padV)
                    addView(TextView(this@MainActivity).apply {
                        text = item.label
                        textSize = 13f
                        // 属性码还没确定的项整行压暗，一眼看出哪些还没适配
                        setTextColor(
                            if (pending) getColor(R.color.text_disabled)
                            else getColor(R.color.text_primary)
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    })
                    addView(valueView)
                    setOnClickListener { showCsmPicker(item, valueView) }
                    // 长按：手动指定属性码。扫描匹配不出结果时（或多个候选不敢定夺），
                    // 让用户照着日志里的候选自己敲一个码，立刻试读验证。
                    setOnLongClickListener {
                        showCsmCodeDialog(item, valueView)
                        true
                    }
                }
                csmValueViews[item] = valueView
                csmList.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (2 * dp).toInt() }
                )
            }
        }
    }

    /** 逐项问机身：类型、当前值、允许取哪些值。整轮下来几十次 USB 往返，别在主线程做。 */
    private fun loadCsmValues(only: CsmGroup? = null) {
        val scope = if (only != null) only.items else CsmMenu.items
        csmLoading = true
        csmReadButton.isEnabled = false
        if (::csmScanButton.isInitialized) csmScanButton.isEnabled = false
        csmReadButton.text = "读取中… 0/${scope.size}"
        csmHint.text = "正在向机身逐项询问…"
        csmAllowed.clear()
        csmTypes.clear()
        csmCurrents.clear()
        buildCsmSkeleton(only)

        // 读取要和 2 秒一次的轮询抢同一条 USB 总线，先把它按住
        isPollingPaused = true
        thread {
            // 属性码还没确定的项跳过：拿一个没验证过的码去问机身没有意义
            val readable = scope.filter { csmCode(it) != PtpConstants.CSM_CODE_UNKNOWN }
            val total = readable.size
            var supported = 0
            try {
                for ((i, item) in readable.withIndex()) {
                    if (!isConnected) break
                    val desc = try {
                        ptpConnection?.readPropDesc(csmCode(item))
                    } catch (_: Exception) {
                        null
                    }
                    if (desc != null) {
                        supported++
                        csmAllowed[item] = desc.allowed
                        csmTypes[item] = desc.dataType
                        // 记下当前档位：打开取值菜单时要在当前项前面打勾
                        csmCurrents[item] = desc.currentValue?.toInt() ?: -1
                    }
                    val current = desc?.currentValue
                    runOnUiThread {
                        csmReadButton.text = "读取中… ${i + 1}/$total"
                        val tv = csmValueViews[item]
                        if (tv == null) return@runOnUiThread
                        if (current == null) {
                            tv.text = "机身无应答"
                            tv.setTextColor(getColor(R.color.text_disabled))
                        } else {
                            tv.text = describeCsmValue(item, current.toInt())
                            // 和出厂值不一样就是被改过，用强调色标出来
                            tv.setTextColor(
                                if (current != desc.factoryValue) getColor(R.color.accent_amber)
                                else getColor(R.color.text_primary)
                            )
                        }
                    }
                }
            } finally {
                runOnUiThread {
                    isPollingPaused = false
                    csmLoading = false
                    csmReadButton.isEnabled = isConnected
                    if (::csmScanButton.isInitialized) csmScanButton.isEnabled = isConnected
                    csmReadButton.text = "重新读取本组"
                    val pending = scope.count { csmCode(it) == PtpConstants.CSM_CODE_UNKNOWN }
                    csmHint.text = when {
                        supported == 0 ->
                            "机身一项都没应答（多半是正忙，或没进 PC 控制模式）。连上后重试一次。"
                        pending > 0 ->
                            "已读出 $supported/$total 项；还有 $pending 项属性码未适配，点「扫描未适配项」去匹配。"
                        else ->
                            "已读出 $supported/$total 项。点任一项切换档位。"
                    }
                    log("个人设定：机身应答 $supported/$total 项" +
                        if (pending > 0) "，未适配 $pending 项" else "")
                }
            }
        }
    }

    /** 值 → 界面上的档位名。表里有就翻译，没有就显示原始值，绝不瞎猜 */
    private fun describeCsmValue(item: CsmItem, v: Int): String =
        item.options[v] ?: "值 $v"

    private fun showCsmPicker(item: CsmItem, valueView: TextView) {
        if (!isConnected) { toast("请先连接相机"); return }
        // 属性码没确定就绝不动手：拿猜的码去写，改坏的是用户机身里的设置
        if (csmCode(item) == PtpConstants.CSM_CODE_UNKNOWN) {
            // 有候选码的让用户逐个"试读"确认：在机身上拨一下这项，看哪一行的值跟着变
            val cands = item.candidates
            if (cands.isNotEmpty() && isConnected) {
                val hexOf = { c: Int -> "0x${Integer.toHexString(c).uppercase()}" }
                showValueMenu(valueView, cands.map { hexOf(it) }) { index ->
                    trySetCsmCode(item, cands[index], valueView)
                }
            } else {
                toast("${item.id} 的属性码还没确定，长按这项可手动指定")
            }
            return
        }

        val entries = ArrayList<Pair<Int, String>>()
        val allowed = csmAllowed[item]
        if (!allowed.isNullOrEmpty()) {
            // 机身报什么就给什么 —— 我们那张中文表只负责翻译成人话
            for (v in allowed) {
                val iv = v.toInt()
                entries.add(iv to describeCsmValue(item, iv))
            }
        } else if (item.options.isNotEmpty()) {
            item.options.toSortedMap().forEach { (k, label) -> entries.add(k to label) }
        } else {
            toast("这项还没读到机身给的档位范围，先点上面的读取")
            return
        }

        val currentIdx = entries.indexOfFirst { it.first == currentCsmValue(item) }
        showValueMenu(valueView, entries.map { it.second }, currentIdx) { i ->
            writeCsmValue(item, entries[i].first, valueView)
        }
    }

    /** 菜单里要打勾就得知道机身现在是哪一档；读不到就返回 -1（不打勾） */
    private fun currentCsmValue(item: CsmItem): Int = csmCurrents[item] ?: -1

    private fun writeCsmValue(item: CsmItem, value: Int, valueView: TextView) {
        if (!isConnected) { toast("请先连接相机"); return }
        val code = csmCode(item)
        if (code == PtpConstants.CSM_CODE_UNKNOWN) {
            toast("${item.id} 的属性码还没确定，不能写入")
            return
        }
        log("个人设定 ${item.label} → $value …")
        thread {
            // 宽度按机身声明的类型来；没声明就先按 UINT8 试，不行再 UINT16
            val type = csmTypes[item]
            val sizes = if (type == 3 || type == 4) listOf(2, 1) else listOf(1, 2)
            var ok = false
            for (s in sizes) {
                ok = ptpConnection?.setDevicePropValue(
                    code, value, size = s, logDesc = "", maxRetries = 1
                ) ?: false
                if (ok) break
            }
            val err = ptpConnection?.lastSetPropError ?: 0
            // 写完回读一次：机身有可能收下却不生效
            val readBack = if (ok) {
                try {
                    ptpConnection?.readPropDesc(code)?.currentValue?.toInt()
                } catch (_: Exception) {
                    null
                }
            } else null

            runOnUiThread {
                if (!ok) {
                    log("${item.label} 写入失败（0x${Integer.toHexString(err).uppercase()}）")
                    toast("机身拒绝了这个档位（0x${Integer.toHexString(err).uppercase()}）")
                    return@runOnUiThread
                }
                val shown = readBack ?: value
                valueView.text = describeCsmValue(item, shown)
                valueView.setTextColor(getColor(R.color.accent_amber))
                if (readBack != null && readBack != value) {
                    log("${item.label}：机身收下后回读为 $readBack（与写入的 $value 不同）")
                    toast("机身收下了，但回读是 ${describeCsmValue(item, readBack)}")
                } else {
                    log("${item.label} 已设为 ${describeCsmValue(item, shown)}")
                }
            }
        }
    }

    private fun setupCustomSettingsControls() {
        customScanButton.setOnClickListener {
            if (!isConnected) { toast("请先连接相机"); return@setOnClickListener }
            if (customScanning) return@setOnClickListener
            customScanning = true
            customScanButton.isEnabled = false
            customScanButton.text = "扫描中…"
            customList.removeAllViews()
            log("扫描自定义设定属性…")

            thread {
                // 先让机身自己报清单（0x90CA，一次事务）；真机 D300 报 185 项。
                // 报告失败再退回盲扫 0xD000..0xD1FF —— 后者要做 512 次事务，慢得多。
                val codes = ptpConnection?.getVendorPropCodes()
                val props: List<PropDesc> = if (codes != null && codes.isNotEmpty()) {
                    val list = codes.filter { it != 0 }
                    runOnUiThread { log("机身报出 ${list.size} 个厂商属性码，按清单读取…") }
                    ptpConnection?.scanProps(list.toIntArray()) { done, total ->
                        if (done % 16 == 0 || done == total) {
                            runOnUiThread { customScanButton.text = "扫描中… $done/$total" }
                        }
                    } ?: emptyList()
                } else {
                    runOnUiThread { log("机身没给属性清单，退回盲扫 0xD000..0xD1FF …") }
                    ptpConnection?.scanPropRange(0xD000, 0xD1FF) { done, total ->
                        if (done % 16 == 0 || done == total) {
                            runOnUiThread { customScanButton.text = "扫描中… $done/$total" }
                        }
                    } ?: emptyList()
                }

                runOnUiThread {
                    customScanning = false
                    customScanButton.isEnabled = true
                    customScanButton.text = "重新扫描"
                    customProps = props
                    renderCustomProps()
                    log("扫描完成：机身应答 ${props.size} 个属性")
                    props.forEach { log("  0x${Integer.toHexString(it.code).uppercase()} ${it.typeName} 当前=${it.currentValue} 值域=${it.rangeText}${if (it.writable) "" else "（只读）"}") }
                }
            }
        }
    }

    /** 把扫描结果画成一行行可点的设置项 */
    private fun renderCustomProps() {
        customList.removeAllViews()
        if (customProps.isEmpty()) {
            customHint.text = "机身没应答任何属性，或不在此码段。"
            customHint.visibility = View.VISIBLE
            return
        }
        val writable = customProps.count { it.writable }
        customHint.text = "机身应答 ${customProps.size} 项（可写 $writable 项）。点一项改值 —— 改前请确认它对应机身菜单哪一档。"
        customHint.visibility = View.VISIBLE

        val dp = resources.displayMetrics.density
        for (p in customProps) {
            // 只列能写、且值域有限的：只读项看不了改不了，值域过大的也不是菜单项
            if (!p.writable) continue
            if (p.allowed.isEmpty()) continue

            val row = TextView(this).apply {
                text = "0x${Integer.toHexString(p.code).uppercase()} · 当前 ${p.currentValue} · [${p.rangeText}]"
                textSize = 13f
                setTextColor(if (p.currentValue != p.factoryValue) getColor(R.color.accent_amber) else getColor(R.color.text_primary))
                setPadding((14 * dp).toInt(), (11 * dp).toInt(), (14 * dp).toInt(), (11 * dp).toInt())
                background = getDrawable(R.drawable.bg_setting_row)
                isClickable = true
                setOnClickListener { showPropValuePicker(p, this) }
            }
            customList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * dp).toInt() }
            )
        }
    }

    /** 弹出机身报回来的合法取值让用户挑 —— 不提供自由输入，避免写下机身不接受的值 */
    private fun showPropValuePicker(prop: PropDesc, row: TextView) {
        // 当前值由菜单自己打勾标注，档位名里就不用再写 ✓ 了
        val currentIdx = prop.allowed.indexOf(prop.currentValue)
        showValueMenu(row, prop.allowed.map { "设为 $it" }, currentIdx) { i ->
            writeCustomProp(prop, prop.allowed[i], row)
        }
    }

    private fun writeCustomProp(prop: PropDesc, value: Long, row: TextView) {
        if (!isConnected) { toast("请先连接相机"); return }
        log("写 0x${Integer.toHexString(prop.code).uppercase()} = $value …")
        thread {
            val conn = ptpConnection
            // 宽度按机身报的类型来：UINT8 用 1 字节，UINT16 用 2 字节
            val size = when (prop.dataType) {
                0x0001, 0x0002 -> 1
                0x0003, 0x0004 -> 2
                else -> 4
            }
            val ok = conn?.setDevicePropValue(
                prop.code, value.toInt(), size = size, logDesc = "", maxRetries = 1
            ) ?: false
            val err = conn?.lastSetPropError ?: 0
            runOnUiThread {
                if (ok) {
                    log("0x${Integer.toHexString(prop.code).uppercase()} 已设为 $value")
                    row.text = "0x${Integer.toHexString(prop.code).uppercase()} · 当前 $value · [${prop.rangeText}]"
                    row.setTextColor(getColor(R.color.accent_amber))
                } else {
                    log("写入失败：0x${Integer.toHexString(err).uppercase()}")
                    toast("机身拒绝了这个值（0x${Integer.toHexString(err).uppercase()}）")
                }
            }
        }
    }

    // ===================== 取景录像 =====================

    /**
     * D300 机身没有录像功能，但 0x9203 的取景帧本身就是动态画面 ——
     * 所以在手机侧把帧喂给编码器，攒成 MP4。画质/帧率受 USB 带宽限制，
     * 实测大约 5~10fps，这个不是 App 能改的。
     */
    private fun toggleRecording() {
        if (recorder?.isRecording == true) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!isLiveViewRunning) { toast("请先开启取景"); return }
        val rec = recorder ?: LiveViewRecorder(this).also { recorder = it }
        if (!rec.start()) {
            toast("无法开始录制：${rec.lastError ?: "未知原因"}")
            log("录制启动失败：${rec.lastError}")
            return
        }
        renderRecState(true)
        startRecTicker()
        log("开始录制取景画面…（录像只含机身画面，对焦框/点阵这些叠加层不会被录进去）")
        toast("开始录制")
    }

    /**
     * 停止录制。
     *
     * [LiveViewRecorder.stop] 内部要等编码器把残余帧排空（最多 4 秒），
     * 不能放在 UI 线程 —— 否则界面会卡住，看起来像"点了没反应"。
     * 所以按钮先切成「保存中…」，真正的收尾扔到后台。
     */
    private fun stopRecording() {
        val rec = recorder ?: return
        stopRecTicker()
        recButton.isEnabled = false
        recButton.text = "■ 保存中…"
        recButton.setBackgroundResource(R.drawable.bg_overlay_chip)
        recButton.setTextColor(getColor(R.color.text_secondary))

        thread {
            val frames = rec.frameCount
            val dropped = rec.droppedFrames
            val elapsed = rec.elapsedMs()
            val where = rec.stop()
            val err = rec.lastError
            runOnUiThread {
                recButton.isEnabled = true
                renderRecState(false)
                if (where != null) {
                    val fps = if (elapsed > 0) frames * 1000.0 / elapsed else 0.0
                    log("录制完成：$frames 帧 / %.1f 秒（约 %.1f fps，编码丢帧 $dropped），已保存到 $where"
                        .format(elapsed / 1000.0, fps))
                    toast("已保存到 $where")
                } else {
                    log("录制失败：$err")
                    toast("录制失败：${err ?: "未知原因"}")
                }
            }
        }
    }

    /** 录制中：红底 + 走时；空闲：常态芯片 + 「● 录制」 */
    private fun renderRecState(recording: Boolean) {
        if (recording) {
            recButton.setBackgroundResource(R.drawable.bg_rec_active)
            recButton.setTextColor(Color.WHITE)
            recButton.text = "● 00:00"
        } else {
            recButton.setBackgroundResource(R.drawable.bg_overlay_chip)
            recButton.setTextColor(getColor(R.color.accent_red))
            recButton.text = "● 录制"
        }
    }

    private fun startRecTicker() {
        stopRecTicker()
        val r = object : Runnable {
            override fun run() {
                val rec = recorder
                if (rec?.isRecording != true) return
                val ms = rec.elapsedMs()
                val s = (ms / 1000).toInt()
                recButton.text = String.format(
                    Locale.US, "● %02d:%02d · %d 帧", s / 60, s % 60, rec.frameCount
                )
                recTicker.postDelayed(this, 500)
            }
        }
        recTickerRunnable = r
        recTicker.post(r)
    }

    private fun stopRecTicker() {
        recTickerRunnable?.let { recTicker.removeCallbacks(it) }
        recTickerRunnable = null
    }

    /**
     * 取景模式（手持 / 三脚架）切换。
     *
     * 这两档的对焦机构完全不同，界面上"对焦点怎么移动"必须跟着变：
     * - **手持**：反光板周期放下，用取景器那套 51 点相位检测 AF。
     *   对焦点只能在一个个固定点位之间跳 → 只能按方向键写 0xD108，
     *   点画面做绝对定位机身不认（官方 Camera Control Pro 2 也只有方向键）。
     * - **三脚架**：反光板常抬，用传感器做对比度检测 AF，AF 框能在画面里
     *   任意位置移动 → 这时才把取景画面的点击接上，走 0x9205。
     */
    private fun setupLiveViewModeControls() {
        renderLiveViewMode(-1)
    }

    /**
     * LV 按钮上画哪个图标 —— 手持 = 人拿相机，三脚架 = 相机架三脚架。
     *
     * 两个图标按说明书的模式示意重绘（26×26、1.6 描边，与其他图标同风格），
     * 让"现在是哪一档、长按会切到哪一档"不用文字也能看懂。
     */
    private fun renderLiveViewButtonIcon() {
        if (!::liveViewButton.isInitialized) return
        val icon = if (currentLiveViewMode == PtpConstants.LIVE_VIEW_MODE_TRIPOD)
            R.drawable.ic_lv_tripod else R.drawable.ic_lv_handheld
        liveViewButton.setImageResource(icon)
    }

    /**
     * 长按 LV 按钮：手持 ⇄ 三脚架（写 0xD1A0），并记住这次选择。
     *
     * 取景进行中不切 —— 机身对取景中的 0xD1A0 写入一律拒绝，
     * 与其让用户按了没反应，不如直接说"先关掉取景"。
     */
    private fun toggleLiveViewModeByLongPress() {
        if (!isConnected) { toast("请先连接相机"); return }
        if (isLiveViewRunning) {
            toast("取景中不能切模式，先点一下关掉取景")
            return
        }
        val next = if (currentLiveViewMode == PtpConstants.LIVE_VIEW_MODE_TRIPOD)
            PtpConstants.LIVE_VIEW_MODE_HANDHELD else PtpConstants.LIVE_VIEW_MODE_TRIPOD
        val name = if (next == PtpConstants.LIVE_VIEW_MODE_TRIPOD) "三脚架" else "手持"
        thread {
            val ok = ptpConnection?.setLiveViewMode(next) ?: false
            runOnUiThread {
                if (ok) {
                    currentLiveViewMode = next
                    // 记住：下次开取景前会按这个值预设 0xD1A0
                    getSharedPreferences(AF_PREFS, Context.MODE_PRIVATE)
                        .edit().putInt(LV_MODE_KEY, next).apply()
                    renderLiveViewMode(next)
                    renderLiveViewButtonIcon()
                    log("取景模式：$name（长按 LV 按钮切换）")
                    toast("取景模式：$name")
                } else {
                    log("取景模式切换失败：$name")
                    toast("切换失败：机身没接受")
                }
            }
        }
    }

    private fun applyLiveViewMode(mode: Int) {
        if (!isConnected) { toast("请先连接相机"); return }
        val name = if (mode == PtpConstants.LIVE_VIEW_MODE_TRIPOD) "三脚架" else "手持"
        log("切换取景模式 → $name …")
        thread {
            val conn = ptpConnection
            val ok = conn?.setLiveViewMode(mode) ?: false
            val err = conn?.lastSetPropError ?: 0
            runOnUiThread {
                if (ok) {
                    currentLiveViewMode = mode
                    getSharedPreferences(AF_PREFS, Context.MODE_PRIVATE)
                        .edit().putInt(LV_MODE_KEY, mode).apply()
                    renderLiveViewMode(mode)
                    log("取景模式已切换为 $name")
                } else {
                    log("取景模式切换失败：0x${Integer.toHexString(err).uppercase()}")
                    toast("机身拒绝了取景模式切换（取景进行中时多半会拒）")
                    renderLiveViewMode(currentLiveViewMode)
                }
            }
        }
    }

    /**
     * 把取景模式渲染到界面。
     *
     * 参数页那组「手持 / 三脚架」分段按钮已删（拍摄页长按取景键就能切，重复入口），
     * 所以这里只管取景相关的界面状态：画面是否接管点击、点阵要不要叠、LV 按钮图标。
     */
    private fun renderLiveViewMode(mode: Int) {
        val handheld = mode == PtpConstants.LIVE_VIEW_MODE_HANDHELD
        val tripod = mode == PtpConstants.LIVE_VIEW_MODE_TRIPOD
        val live = isLiveViewRunning
        // 画面点击由谁接管：
        // - 取景中 · 三脚架 → 0x9205 绝对定位，AF 框能落在画面任意位置
        // - 取景中 · 手持 + 触摸对焦开 → 吸附到最近的对焦点（0xD108）再自动对焦
        //   手持是 51 点相位 AF，机身只认固定点位，发绝对坐标它不动；
        //   以前这条回调在手持下压根不挂，用户点了画面毫无反应（"触摸对焦没用"）。
        // - 其余情况不挂：未取景没有画面可点，触摸对焦关着时不该被误触改写对焦点。
        focusBox.onAreaPicked =
            if (live && (tripod || touchAfEnabled)) { { nx, ny -> onViewfinderTapped(nx, ny) } }
            else null
        // 点阵（固定点位的对焦点）何时显示：
        // - 未取景：显示 —— 用光学取景器拍的时候，就是靠它在手机上调对焦点（写 0xD108）
        // - 取景中·手持：显示（51 点相位检测只能在这些固定点之间跳）
        // - 取景中·三脚架：隐藏 —— 对比度 AF 可以点画面任意位置，点阵纯属挡画面
        //
        // ⚠️ 点阵 ≠ 可任意拖动：未取景时点阵只能"点某个点→切那个对焦点"，
        // 画面任意位置拖动（0x9205 绝对定位）只在「取景中·三脚架」才挂回调，
        // 见上面的 onAreaPicked。这两件事别混在一个条件里，否则未取景点画面会白发命令。
        focusBox.pointsOverlay = !live || handheld
        // 手持是固定点位：松手瞬间就把待确认框摆到真正的对焦点上，别画在手指处再跳一下。
        // 三脚架能自由挪，框就落在手指位置。
        focusBox.snapPreviewToPoints = handheld
        focusBox.setAfPoints(currentAfPoints(), currentAfPointId)
        renderAfPointRow()
        // 模式一变，LV 按钮上的图标也跟着换（人拿相机 / 三脚架）
        renderLiveViewButtonIcon()

        // ★ 三脚架取景中不要摆出"未合焦"四个字。
        // 0xD104 这种锁焦属性在手动拧对焦环、或机身刚拍完时会读成 0，
        // 但取景画面上明明已经清晰、机身也响了合焦提示音 ——
        // 用户看到"未合焦"只会以为坏了。这里统一显示"取景对焦"，
        // 对没对上由画面自己说话。
        if (live && handheld.not()) {
            runOnUiThread {
                focusStatusDot.setBackgroundResource(R.drawable.bg_status_dot_on)
                focusStatusText.text = "取景对焦"
            }
        }
    }

    /**
     * 取景框上的对焦图层初始化。
     *
     * 参数页那张「对焦屏 + AF 区域模式 + 11/51 + 标定 + 探测」卡片已经删掉：
     * 51 点与 11 点都标定完了，选点改在取景框里做 ——
     * 右上角 chip 切点数（写 0xD08D），画面上点对焦点（写 0xD108），
     * 三脚架模式点画面挪 AF 框（0x9205）。
     */
    private fun setupAfOverlay() {
        loadAfCalibration()
        focusBox.attachImageView(liveViewImage)
        focusBox.mode = FocusBoxView.Mode.LIVE
        // 未取景时也摆点阵：光学取景拍摄时，选对焦点就靠它（点某个点 → 写 0xD108）。
        // 真正的"点画面任意位置挪 AF 框"（0x9205）不在这里，只在取景中·三脚架才启用。
        focusBox.pointsOverlay = true
        focusBox.setAfPoints(currentAfPoints(), currentAfPointId)
        focusBox.onPointPicked = { id ->
            val p = currentAfPoints().firstOrNull { it.id == id }
            selectAfPoint(id, p?.nx ?: -1f, p?.ny ?: -1f)
        }
        renderAfPointRow()
    }

    /**
     * 选对焦点：先写 0xD108（机身的"当前对焦点"属性，官方表值域 0..51）。
     *
     * 取景中写属性可能被拒 —— 这时用 [fallbackNx]/[fallbackNy] 再试一次
     * 0x9205（取景 AF 区域绝对定位）。两条路的响应码都会写进日志，
     * 真机按一次方向键就能看出该走哪条。
     *
     * @param syncArea true = 写 0xD108 之后**再**用 0x9205 把画面上那个可见的 AF 框
     *   挪到同一个位置。手持取景下机身报回来的框未必跟着 0xD108 走，
     *   补这一发才能让"点哪儿、框去哪儿"在两种取景模式下表现一致
     *   （手持下机身多半收了不动，但也不会报错，代价只是一次几十毫秒的往返）。
     */
    private fun selectAfPoint(
        id: Int,
        fallbackNx: Float = -1f,
        fallbackNy: Float = -1f,
        syncArea: Boolean = false
    ) {
        if (!isConnected) { toast("请先连接相机"); return }
        thread {
            val conn = ptpConnection
            val notes = ArrayList<String>()
            var ok = conn?.setAfAreaPoint(id) ?: false
            var via = "0xD108"
            var errText = "0x${Integer.toHexString(conn?.lastSetPropError ?: 0).uppercase()}"

            // 0xD108 被拒 → 退回 0x9205；开了 syncArea → 哪怕 0xD108 成功了也补一发
            val needArea = conn != null && fallbackNx >= 0f && isLiveViewRunning &&
                (syncArea || !ok)
            if (needArea) {
                val t = conn?.mapNormalizedToAfArea(fallbackNx, fallbackNy)
                if (t != null) {
                    val code = conn.changeAfArea(t.first, t.second)
                    if (code == PtpConstants.RESP_OK) {
                        if (!ok) {
                            ok = true
                            via = "0x9205(${t.first},${t.second})"
                        } else {
                            notes.add("（0x9205 已同步 AF 框 ${t.first},${t.second}）")
                        }
                    } else {
                        errText += " / 0x9205=" +
                            (if (code < 0) "无响应" else "0x${Integer.toHexString(code).uppercase()}")
                    }
                }
            }

            val finalOk = ok
            val finalVia = via
            val finalErr = errText
            val finalNotes = notes.joinToString("")
            runOnUiThread {
                if (finalOk) {
                    currentAfPointId = id
                    focusBox.setAfPoints(currentAfPoints(), id)
                    log("对焦点已切到 id=$id（$finalVia）$finalNotes")
                    // 手持模式下"切点"只是选中、机身不对焦 ——
                    // 开着触摸对焦就在这里补一次，省掉"选完再按 AF"。
                    autoFocusOnAfPointChange("0xD108 选点 id=$id")
                } else {
                    log("对焦点切换失败 id=$id：$finalErr")
                    toast("机身拒绝了这个对焦点（取景中 / 非单点 AF 时会拒）")
                    focusBox.setAfPoints(currentAfPoints(), currentAfPointId)
                }
            }
        }
    }

    /**
     * 点取景画面 —— 「点哪对哪」的总入口。
     *
     * 两种取景模式走的是完全不同的对焦机构，必须分开处理：
     * - **三脚架**：对比度检测 AF，AF 框能在画面里任意挪 → 直接发 0x9205 绝对坐标，
     *   之后机身自己跑对比度 AF（[moveAfAreaTo]）。
     * - **手持**：51 点相位检测 AF，机身只认固定点位 → 把点击**吸附到最近那个对焦点**，
     *   写 0xD108 再补一发 0x90C1。以前手持下这条回调没挂（见 [renderLiveViewMode]），
     *   用户开了触摸对焦点画面却毫无反应，根子就在这里。
     */
    private fun onViewfinderTapped(nx: Float, ny: Float) {
        if (currentLiveViewMode == PtpConstants.LIVE_VIEW_MODE_TRIPOD) {
            moveAfAreaTo(nx, ny)
            return
        }
        if (!touchAfEnabled) return
        val p = nearestAfPoint(nx, ny)
        if (p == null) {
            toast("还没有可用的对焦点")
            return
        }
        // 点的位置离所有对焦点都远 → 说明是点在了 51 点覆盖不到的边角。
        // 手持是相位检测，机身只能在这 51 个固定点位上选，这里明说一句，
        // 免得用户以为"选了但没挪过去"；真要任意位置，得切三脚架模式走 0x9205。
        val far = (p.nx - nx) * (p.nx - nx) + (p.ny - ny) * (p.ny - ny) > 0.06f * 0.06f
        if (far) {
            toast("手持模式只能在这 ${currentAfPoints().size} 个对焦点上选，已选最近的一个；要任意位置请切三脚架模式")
        }
        // 先把框画到那一点上：取景帧最快也要 100ms 才回来，
        // 中间那段空窗就是"点了没反应"的体感来源。真帧回来时会被顶掉。
        focusBox.showPendingBox(p.nx, p.ny)
        focusBox.setAfPoints(currentAfPoints(), p.id)
        selectAfPoint(p.id, p.nx, p.ny, syncArea = true)
    }

    /**
     * 画面归一化坐标 → 最近的一个对焦点。
     *
     * **不设命中半径**：[FocusBoxView.nearestPoint] 那个阈值（51 点时 0.06）是给
     * "逐点选"用的，点偏一点就当没点中；这里是"点画面任意位置"，
     * 必须永远返回一个结果，否则又变回"点了没反应"。
     */
    private fun nearestAfPoint(nx: Float, ny: Float): AfPoint? {
        val list = currentAfPoints()
        if (list.isEmpty()) return null
        var best = list[0]
        var bestD = Float.MAX_VALUE
        for (p in list) {
            val d = (p.nx - nx) * (p.nx - nx) + (p.ny - ny) * (p.ny - ny)
            if (d < bestD) { bestD = d; best = p }
        }
        return best
    }

    /**
     * 点画面移动 AF 框（0x9205）—— **只在三脚架模式下有意义**。
     *
     * 手持模式是 51 点相位检测 AF，对焦点只能落在固定点位上，
     * 发绝对坐标机身会"命令成功但框不动"。所以 [renderLiveViewMode] 只会在
     * 三脚架模式把这条回调挂上去。
     *
     * 归一化坐标不能直接发：0x9205 要的是整幅画面（机身取景分辨率）的像素坐标，
     * 界面上拿到的是 640×480 的 JPEG，尺寸差了近 7 倍，换算错了框根本不会动。
     */
    private fun moveAfAreaTo(nx: Float, ny: Float) {
        if (!isConnected) { toast("请先连接相机"); return }
        if (!isLiveViewRunning) { toast("请先开启取景"); return }
        // ★★ 放大状态下**不发** 0x9205。
        //
        // 说明书（三脚架模式 · 放大）：画面放大后，多重选择器是用来"在放大区域里滚动画面"的。
        // 也就是说放大状态下改 AF 区域，机身会顺势把放大的那一块挪过去 ——
        // 用户看到的就是"点一下画面，画面自己跑掉了"（全屏时尤其明显）。
        // 要挪对焦点，先点倍率回到全画面再点。
        if (lvZoomValue != 0) {
            toast("放大状态下画面会跟着对焦点跑 —— 先点倍率回到全画面再选点")
            return
        }
        val conn = ptpConnection
        val target = conn?.mapNormalizedToAfArea(nx, ny)
        if (target == null) {
            toast("还没拿到取景画面尺寸，稍后再试")
            return
        }
        // 节流：连点会排队发出一堆命令，机身消化不过来，反而越来越卡
        val now = System.currentTimeMillis()
        if (now - lastAfAreaSendMs < 250) return
        lastAfAreaSendMs = now

        thread {
            val afStartMs = System.currentTimeMillis()
            val code = conn.changeAfArea(target.first, target.second)
            val ok = code == PtpConstants.RESP_OK
            runOnUiThread {
                if (ok) {
                    // 对焦点变了，之前那次合焦先作废
                    lvAfLocked = false
                    log("AF 框移至 (${target.first}, ${target.second})")
                    // 三脚架下机身自己会跑对比度 AF（下面那段就是等它锁焦），
                    // 所以 autoFocusOnAfPointChange 在这里什么都不会发 ——
                    // 调它是为了让"开着触摸对焦却没动作"这件事在日志里有一句解释。
                    autoFocusOnAfPointChange("0x9205 移框 (${target.first},${target.second})")
                    // ★ 0x9205 之后**机身自己会跑对比度 AF**（还会响提示音），
                    // 这条路不经过我们的 afDrive()，所以过去完全没记账 ——
                    // 结果"机身已经合焦，按拍摄又用光学 AF 对了一遍"。
                    // 这里主动等它锁焦（最多 3 秒），锁上就把标记记下来、并把耗时显示出来。
                    thread {
                        // ★ 判定用 0x90C8，不再用 0xD104。
                        // 真机日志证明 0xD104（AFLockStatus）在 D300 上对焦全程都是 0，
                        // 拿它等锁焦永远等不到 —— 于是机身已经合焦、还响了提示音，
                        // 界面上却停在"未合焦"。0x90C8 回 0x2001 才是"AF 结束"。
                        val finished = try {
                            ptpConnection?.waitAfFinish(2500) ?: false
                        } catch (_: Exception) {
                            false
                        }
                        val cost = System.currentTimeMillis() - afStartMs
                        runOnUiThread {
                            if (finished) {
                                lvAfLocked = true
                                setFocusStatus(true, cost)
                                log("机身对焦完成（0x9205 触发，机身已空闲）耗时 ${formatDuration(cost)}")
                            } else {
                                // 没等到空闲不代表没对上：可能机身还在慢慢磨。
                                // 这里只记时间、不把提示改成"未合焦"，免得和机身提示音打架。
                                log("2.5 秒内没等到机身空闲，耗时 ${formatDuration(cost)} —— 以画面为准")
                            }
                        }
                    }
                } else {
                    val hex = if (code < 0) "无响应" else "0x${Integer.toHexString(code).uppercase()}"
                    log("移动 AF 框失败：$hex")
                    // 手持模式下这条必然失败，直接点破原因，别让人以为是坏了
                    if (currentLiveViewMode == PtpConstants.LIVE_VIEW_MODE_HANDHELD) {
                        toast("手持模式不支持定位，请用方向键或切到三脚架模式")
                    } else {
                        toast("移动对焦点失败：$hex")
                    }
                }
            }
        }
    }

    /**
     * 切 11 点 / 51 点（写 0xD08D）。入口是取景框右上角那颗 chip。
     *
     * 点数变了点阵也跟着换：51 点画全 51 个（有标定结果就用实测坐标），
     * 11 点画常用的那 11 个 —— 机身在这两个模式下认的 id 不是同一套。
     */
    private fun setAfPointCount(all51: Boolean) {
        if (!isConnected) { toast("请先连接相机"); return }
        log("设置 AF 点数：${if (all51) 51 else 11} …")
        thread {
            val ok = ptpConnection?.setAfPointCount(all51) ?: false
            runOnUiThread {
                if (ok) {
                    afPointCount51 = all51
                    // 记住这档：下次打开 App 仍按它显示点阵（不用每次重设）
                    getSharedPreferences(AF_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(AF_KEY_51, all51).apply()
                    renderAfPointRow()
                    focusBox.setAfPoints(currentAfPoints(), currentAfPointId)
                    log("AF 点数已设为 ${if (all51) 51 else 11}")
                } else {
                    log("AF 点数设置失败：0x${Integer.toHexString(ptpConnection?.lastSetPropError ?: 0).uppercase()}")
                    toast("AF 点数设置失败")
                    // 方框已经点亮了没写进去的那档，这里按实际值收回来，别显示假状态
                    renderAfPointRow()
                }
            }
        }
    }

    /**
     * 参数页「对焦点数量」那一行的文案。
     *
     * 主页不再放 11/51 切换按钮（那属于设置，不是每次都要点的操作），
     * 改成参数页一行；这一行也是"当前是哪档"的唯一显示位置。
     */
    private fun afPointCountText(): String = if (afPointCount51) "51 点" else "11 点"

    /**
     * 对焦点数量：true = 51 点（false = 11 点）。
     *
     * 参数页上是两个方框（51 / 11），点一下就写机身 0xD08D ——
     * 原来是一行点开再弹下拉列表，两步操作换来<｜hy_place▁holder▁no▁813｜>同样两个选项，没必要。
     */
    private fun renderAfPointRow() {
        afPointCountGrid?.select(if (afPointCount51) 0 else 1)
        afPointCountBox?.alpha = if (isConnected) 1f else 0.45f
    }

    /** 当前点数模式下该画/该用的点阵；51 点用标定结果覆盖几何占位坐标 */
    private fun currentAfPoints(): List<AfPoint> {
        if (!afPointCount51) return D300_AF_POINTS_11
        val cal = synchronized(calibLock) { calibResults.toMap() }
        if (cal.isEmpty()) return D300_AF_GRID_51
        return D300_AF_GRID_51.map { p ->
            val hit = cal[p.id]
            if (hit != null) p.copy(nx = hit.first, ny = hit.second) else p
        }
    }

    /**
     * 读出早年标好的 51 点实测坐标（SharedPreferences `af51_calib`）。
     * 标定入口已经删了，这里只负责加载；没标过的点继续用 [D300_AF_GRID_51] 的几何占位。
     */
    private fun loadAfCalibration() {
        val sp = getSharedPreferences("af51_calib", Context.MODE_PRIVATE)
        // ★ 2026-10-26：51 点的 id 已换成官方编号（此前 40 个是阅读顺序占位）。
        //   旧的标定表存的是"占位 id → 实测坐标"，套到新 id 上会张冠李戴，
        //   而且现在官方坐标本身就是准的 —— 直接作废，不做半吊子合并。
        if (sp.getInt("grid_version", 1) < 2) {
            sp.edit().clear().putInt("grid_version", 2).apply()
            log("51 点已改用官方编号，旧标定（占位 id）作废")
            return
        }
        val n = sp.getInt("count", 0)
        if (n <= 0) return
        synchronized(calibLock) {
            calibResults.clear()
            for (id in 0..51) {
                val x = sp.getFloat("x$id", Float.NaN)
                val y = sp.getFloat("y$id", Float.NaN)
                if (!x.isNaN() && !y.isNaN()) calibResults[id] = x to y
            }
        }
        if (calibResults.isNotEmpty()) {
            log("已加载 51 点标定：${calibResults.size} 个实测点位")
        }
    }

    /**
     * 取景窗口尺寸**恒定**，不再随取景开关伸缩。
     *
     * 以前开取景会把窗口从 128dp 撑到占满剩余空间，关掉又缩回去 ——
     * 画面一进一出整页都在跳。现在窗口始终吃掉剩余空间：
     * 取景时显示实时画面，没取景时同一块区域显示对焦点阵，视觉上是同一块画布。
     */
    /**
     * 取景全屏：画面铺满整屏，其余 UI（顶栏 / 曝光卡片 / 底部导航）全部收起，
     * 只留一个「退出」按钮，和视频软件的全屏一个意思。
     *
     * 用的是"隐藏兄弟节点"而不是新建一个全屏 Activity —— 取景帧回调、AF 框叠加、
     * 录制按钮这些都挂在原布局上，搬去新界面反而要重连一遍。
     */
    /** 单张拍摄：底部快门键和全屏操作条共用（间隔拍摄/自拍延时走 shootOnce 的另一条路） */
    private fun doCaptureOnce() {
        thread {
            try {
                isPollingPaused = true
                shootOnce()
            } catch (e: Exception) {
                runOnUiThread { log("拍摄出错：${e.message}") }
            } finally {
                isPollingPaused = false
                thread { updateProperties() }
            }
        }
    }

    /** 一次自动对焦：底部 AF 键和全屏操作条共用 */
    private fun doFocusOnce() {
        thread {
            try {
                isPollingPaused = true
                // 以前这里固定 sleep(300) 等轮询让路 —— 每次对焦白等 0.3 秒。
                // 现在轮询会逐条检查暂停标志，最多只占用它正在读的那一条。
                log("AF：正在下发驱动指令…")
                runOnUiThread { focusStatusText.text = "对焦中…" }
                val startTime = System.nanoTime()

                val success = ptpConnection?.afDrive() ?: false
                // ★ 只要机身确认合焦就记住，不再要求"必须正在取景"。
                // 以前加了 isLiveViewRunning 前置条件，结果取景刚退出时合焦的那次没被记住，
                // 下一张照样白走一遍 AF —— 正是用户抱怨的"对完焦还要再对一次"。
                if (success) lvAfLocked = true

                val overheadMs = (System.nanoTime() - startTime) / 1_000_000

                runOnUiThread {
                    // 带上耗时：合焦提示直接显示"已合焦 · 0.9 秒"
                    setFocusStatus(success, overheadMs)
                    val resultText = if (success) "已合焦" else "对焦失败或超时"
                    log("AF 状态：$resultText（耗时 ${formatDuration(overheadMs)}，App 轮询判定）")
                }
            } catch (e: Exception) {
                runOnUiThread { log("AF 执行异常：${e.message}") }
            } finally {
                isPollingPaused = false
            }
        }
    }

    /**
     * 取景放大 / 缩小一档（0xD1A3），给手动对焦看细节用。
     *
     * 走的是**机身自己的放大**：机身把画面裁一块放大后回传，是真细节；
     * 在手机上把小画面插值拉伸只是把马赛克放大，对焦反而看不清。
     */
    private fun stepLvZoom(up: Boolean) {
        thread {
            try {
                isPollingPaused = true
                val v = ptpConnection?.stepLvZoom(up) ?: -1
                if (v < 0) runOnUiThread { toast("机身不支持取景放大（0xD1A3 无响应）") }
                else applyLvZoomResult(v)
            } catch (e: Exception) {
                runOnUiThread { log("取景放大失败：${e.message}") }
            } finally {
                isPollingPaused = false
            }
        }
    }

    /**
     * 放大档位 → 人话：**一律百分比**（用户要求，别再回到"放大 N"那种原始值）。
     *
     * D300 说明书：手持模式最大 3 倍、三脚架模式最大 13 倍 —— 档位值本身就是倍率
     * （1 = 100%、2 = 200%、3 = 300%…），0 是机身自己的"全画面"（不放大）。
     */
    private fun lvZoomLabel(v: Int): String =
        if (v <= 0) "全画面" else "${v * 100}%"

    /**
     * 写放大档位并同步界面 + 对焦图层。
     *
     * 放大后画面是裁一块出来的，点阵/点击坐标都要跟着换算（[FocusBoxView.setZoom]），
     * 否则"对焦点贴在放大的画面上却按整幅坐标画"，看起来就是错位。
     */
    private fun applyLvZoom(target: Int) {
        thread {
            try {
                isPollingPaused = true
                val v = ptpConnection?.setLvZoom(target, lvZoomValue) ?: -1
                if (v < 0) runOnUiThread { toast("机身不支持取景放大（0xD1A3 无响应）") }
                else applyLvZoomResult(v)
            } catch (e: Exception) {
                runOnUiThread { log("取景放大失败：${e.message}") }
            } finally {
                isPollingPaused = false
            }
        }
    }

    /** 档位写完之后：记状态、刷文案、把换算关系告诉对焦图层 */
    private fun applyLvZoomResult(v: Int) {
        runOnUiThread {
            lvZoomValue = v
            lvZoomText.text = lvZoomLabel(v)
            refreshLvZoomTransform()
            log("取景放大：${lvZoomLabel(v)}")
        }
    }

    /** 把当前倍率 + 放大中心交给对焦图层；未放大时清掉换算 */
    private fun refreshLvZoomTransform() {
        if (lvZoomValue <= 0) {
            focusBox.setZoom(1f, 0.5f, 0.5f)
            return
        }
        val scale = ptpConnection?.lvZoomScale() ?: lvZoomValue.toFloat()
        focusBox.setZoom(scale, lvAfCenterNx, lvAfCenterNy)
    }

    /**
     * 把放大倍率写回**全画面**（归位），不弹提示、不做 UI 反馈 ——
     * 用在"退出取景 / 拍摄前"这些必须收干净的时刻。
     *
     * 失败了也不折腾：机身重启/退取景本身就会把这个属性清掉，
     * 这里只是让"界面文案"和"机身状态"别长期对不上。
     */
    private fun resetLvZoomQuiet() {
        try {
            val v = ptpConnection?.setLvZoom(0, lvZoomValue) ?: return
            if (v >= 0) lvZoomValue = v
        } catch (_: Exception) {
            // 归位是锦上添花：写不进去也不该把退出取景/拍摄流程拖住
        }
    }

    private fun resetLvZoomAsync() {
        thread {
            try {
                isPollingPaused = true
                resetLvZoomQuiet()
                runOnUiThread {
                    lvZoomText.text = lvZoomLabel(lvZoomValue)
                    refreshLvZoomTransform()
                }
            } finally {
                isPollingPaused = false
            }
        }
    }

    /**
     * 放大倍率二级菜单（长按倍率文字打开）：和曝光参数那套一样，
     * 点一格直接切到那个百分比，当前档位用强调色标出。
     */
    private fun showLvZoomSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        if (!isLiveViewRunning) { toast("先开启取景再调放大倍率"); return }
        val page = buildSheetPage("取景放大倍率", "点一下切换 · 长按倍率文字也能打开")
        val card = sheetCard(page.content, null)
        val statusText = sheetStatusText("读取机身档位…")
        card.addView(statusText)
        page.content.addView(
            sheetHint("放大的是机身裁出来的一块画面（真细节），不是把小图拉大。")
        )
        page.show()

        thread {
            try {
                isPollingPaused = true
                val steps = ptpConnection?.lvZoomSteps().orEmpty()
                val cur = ptpConnection?.currentLvZoom() ?: lvZoomValue
                runOnUiThread {
                    if (steps.isEmpty()) {
                        statusText.text = "机身没给出可用档位"
                        return@runOnUiThread
                    }
                    val labels = steps.map { lvZoomLabel(it) }
                    val curIdx = steps.indexOf(cur).let { if (it >= 0) it else steps.indexOfFirst { s -> s >= cur } }
                    statusText.text = "当前 ${lvZoomLabel(cur)} · 共 ${steps.size} 档"
                    val grid = sheetTileGrid(3, labels, curIdx) { idx ->
                        page.dialog.dismiss()
                        applyLvZoom(steps[idx])
                    }
                    card.addView(grid.view)
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "读取档位失败：${e.message}" }
            } finally {
                isPollingPaused = false
            }
        }
    }

    private fun toggleLiveViewFullscreen() {
        if (!isLiveViewRunning) { toast("先开启取景再全屏"); return }
        isLvFullscreen = !isLvFullscreen
        applyLiveViewFullscreen()
    }

    private fun applyLiveViewFullscreen() {
        val show = if (isLvFullscreen) View.GONE else View.VISIBLE

        // ★ 退出全屏时把机身放大倍率复位到"全画面"。
        // 放大只在全屏下才有操作条（手动对焦时看细节用），退出全屏就该回到原始取景范围 ——
        // 否则画面会一直停在放大状态，看着像"取景画面缩不回去了"。
        if (!isLvFullscreen && isLiveViewRunning && lvZoomValue != 0) resetLvZoomAsync()

        // ⚠️ 绝不能隐藏 topStatusBar：它是整页的根容器（顶栏 + 内容区 + 导航胶囊都装在它里头），
        // 隐藏它等于把取景画面一起藏掉 —— 表现就是"全屏后一片黑，退出按钮也点不到"。
        // 全屏要收的只是顶栏那一行 + 连接按钮 + 底部导航。
        topBarRow.visibility = show
        navCapsule.visibility = show
        // 顶栏收起后容器自身的 16dp padding 还在，画面四周会留白 —— 全屏期间清零，退出时还原
        if (isLvFullscreen) {
            if (lvSavedPadding < 0) lvSavedPadding = topStatusBar.paddingTop
            topStatusBar.setPadding(0, 0, 0, 0)
        } else if (lvSavedPadding >= 0) {
            topStatusBar.setPadding(lvSavedPadding, lvSavedPadding, lvSavedPadding, lvSavedPadding)
        }
        // 取景框之外的所有兄弟节点一起收起（信息条、chips、曝光卡片、按钮行…）
        for (i in 0 until shootSection.childCount) {
            val child = shootSection.getChildAt(i)
            if (child.id != R.id.viewfinder) child.visibility = show
        }
        // 未连接时才显示的"连接相机"按钮：全屏期间强制收起
        if (isLvFullscreen) connectButton.visibility = View.GONE else updateButtonStates()

        lvFullscreenButton.text = if (isLvFullscreen) "退出" else "全屏"

        // 全屏操作条：放大/缩小（手动对焦）+ AF + 拍摄。
        // 非全屏时这些功能都在下方按钮行里，没必要占着画面。
        lvFullControls.visibility = if (isLvFullscreen) View.VISIBLE else View.GONE
        if (isLvFullscreen) {
            lvZoomText.text = lvZoomLabel(ptpConnection?.currentLvZoom() ?: 0)
        }

        // 系统栏也一并隐藏，画面才真的铺满
        runOnUiThread {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            if (isLvFullscreen) {
                ctl.hide(WindowInsetsCompat.Type.systemBars())
                ctl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                ctl.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        // 全屏后取景框尺寸才最终确定（顶栏 padding 刚被清零），
        // 所以要等一帧布局结束再按真实画面算按钮位置；退出全屏走同一函数还原。
        viewfinder.post { applyFullscreenControlOffsets() }
    }

    // =====================================================================
    // 全屏：把上/下两组按钮贴到画面上下沿
    // =====================================================================

    /**
     * 竖屏手机上 3:2 的取景画面只占屏幕中间一条，上下各留一大块黑边；
     * 而按钮是按**整个取景框**贴边排的（上 12dp / 下 52dp），
     * 于是"画面在中间、按钮在两头"，眼睛要在画面和按钮之间来回跳。
     *
     * 这里按画面真正画到哪（[lvImageBounds]，跟着 imageMatrix 走，放大时也准）
     * 重新给上下两组按钮定边距，让它们**贴着画面**。
     *
     * ★★ 方向很关键：是贴在画面**外面**，不是压在画面上。
     * 取景画面的每一格像素都可能是用户在看的构图，按钮盖上去那块就永远看不见了，
     * 手动对焦放大看细节时尤其致命（盖住的往往正是要看的地方）。
     * 所以上组整条坐在画面上沿之上（底边 = 画面顶 - 8dp），
     * 下组整条吊在画面下沿之下（顶边 = 画面底 + 8dp）。
     *
     * 空间不够时（横屏下画面顶满取景框）没有"画面外"可用，
     * 这时退让给底部信息条 —— 宁可压一点点画面，也不能和状态条叠成一团。
     *
     * 退出全屏时把记下来的原始边距还原，非全屏下的排布一点不变。
     */
    private var lvSavedControlMargins: IntArray? = null

    private fun applyFullscreenControlOffsets() {
        if (!::lvTopTools.isInitialized) return

        if (!isLvFullscreen) {
            lvSavedControlMargins?.let { m ->
                setTopMargin(lvTopTools, m[0])
                setTopMargin(lvFullscreenButton, m[1])
                setBottomMargin(lvFullControls, m[2])
            }
            return
        }
        if (lvSavedControlMargins == null) {
            lvSavedControlMargins = intArrayOf(
                topMarginOf(lvTopTools),
                topMarginOf(lvFullscreenButton),
                bottomMarginOf(lvFullControls)
            )
        }

        val frameH = viewfinder.height
        if (frameH <= 0) return
        val d = resources.displayMetrics.density
        val pad = (8 * d).toInt()
        // 底部信息条 34dp 高 + 12dp 边距，再留 6dp 间隔 —— 下组不许压到它上面
        val minBottom = (52 * d).toInt()

        val (imgTop, imgBottom) = lvImageBounds()

        // 上组：底边 = 画面上沿 - pad，反推顶边距 = imgTop - pad - 高度
        val toolsH = lvTopTools.height.takeIf { it > 0 } ?: (28 * d).toInt()
        val topMargin = (imgTop - pad - toolsH).coerceAtLeast(0)
        setTopMargin(lvTopTools, topMargin)
        setTopMargin(lvFullscreenButton, topMargin)

        // 下组：顶边 = 画面下沿 + pad，反推底边距 = frameH - (imgBottom + pad + 高度)
        val ctrlH = lvFullControls.height.takeIf { it > 0 } ?: (46 * d).toInt()
        val bottomMargin = (frameH - (imgBottom + pad + ctrlH)).coerceAtLeast(minBottom)
        setBottomMargin(lvFullControls, bottomMargin)
    }

    /** 取景画面在取景框里的上下沿；取不到（还没开取景）就按整块算 */
    private fun lvImageBounds(): Pair<Int, Int> {
        val iv = liveViewImage
        val dr = iv.drawable
        if (dr != null && dr.intrinsicWidth > 0 && dr.intrinsicHeight > 0 && iv.height > 0) {
            val r = android.graphics.RectF(
                0f, 0f, dr.intrinsicWidth.toFloat(), dr.intrinsicHeight.toFloat()
            )
            iv.imageMatrix.mapRect(r)   // drawable 像素坐标 → 控件坐标
            val inside = r.bottom <= iv.height + 2f
            if (r.height() > 1 && inside) {
                return (iv.top + r.top.toInt()).coerceAtLeast(0) to
                    (iv.top + r.bottom.toInt()).coerceAtMost(viewfinder.height)
            }
        }
        return 0 to viewfinder.height
    }

    private fun lpOf(v: View): FrameLayout.LayoutParams? =
        v.layoutParams as? FrameLayout.LayoutParams

    private fun topMarginOf(v: View): Int = lpOf(v)?.topMargin ?: 0

    private fun bottomMarginOf(v: View): Int = lpOf(v)?.bottomMargin ?: 0

    private fun setTopMargin(v: View, value: Int) {
        lpOf(v)?.let { lp ->
            if (lp.topMargin == value) return
            lp.topMargin = value
            v.layoutParams = lp
        }
    }

    private fun setBottomMargin(v: View, value: Int) {
        lpOf(v)?.let { lp ->
            if (lp.bottomMargin == value) return
            lp.bottomMargin = value
            v.layoutParams = lp
        }
    }

    private fun setViewfinderExpanded(@Suppress("UNUSED_PARAMETER") expanded: Boolean) {
        val lp = viewfinder.layoutParams as LinearLayout.LayoutParams
        lp.height = 0
        lp.weight = 1f
        viewfinder.layoutParams = lp
    }

    /**
     * 底部信息条的合焦提示。
     *
     * @param elapsedMs 这次对焦花掉的毫秒数。它是**App 自己轮询出来的**，不是机身给的：
     *   机身只会回一句"命令已接受"，之后 `afDrive()` 每 150ms 问一次 0x90C8 DeviceReady、
     *   顺便读 0xD104 AFLockStatus，看到"锁焦/就绪"才停表 —— 所以这个数比机身实际
     *   对焦时间最多多一个轮询间隔（150ms）加一次 USB 往返。三脚架模式点画面对焦
     *   （0x9205，机身自己跑对比度 AF）同理，靠轮询 0xD104 才知道。
     *   PTP 事件（0x90C7）里没有"对焦完成"这一项，指望不上推送。
     */
    private fun setFocusStatus(locked: Boolean, elapsedMs: Long? = null) {
        focusStatusDot.setBackgroundResource(
            if (locked) R.drawable.bg_status_dot_on else R.drawable.bg_status_dot_off
        )
        focusStatusText.text = if (locked) "已合焦" else "未合焦"
        if (elapsedMs != null && elapsedMs >= 0) {
            focusStatusText.text = "${focusStatusText.text} · ${formatDuration(elapsedMs)}"
        }
    }

    /** 毫秒 → 人看得懂的时长：小于 1 秒用毫秒，否则一位小数秒 */
    private fun formatDuration(ms: Long): String =
        if (ms < 1000) "${ms} 毫秒" else "${String.format("%.1f", ms / 1000.0)} 秒"

    private fun updateShotCount() {
        shotCountText.text = "本次已拍 $sessionShotCount 张"
    }

    private fun updateButtonStates() {
        runOnUiThread {
            // 已连接时整块收起（断开连接在「相机信息」里）；未连接才在顶部露出"连接相机"
            connectButton.text = "连接相机"
            connectButton.visibility = if (isConnected) View.GONE else View.VISIBLE
            statusDot.setBackgroundResource(if (isConnected) R.drawable.bg_status_dot_on else R.drawable.bg_status_dot_off)
            connectButton.setBackgroundResource(R.drawable.bg_btn_amber)
            captureButton.isEnabled = isConnected
            isoButton.isEnabled = isConnected
            apertureButton.isEnabled = isConnected
            shutterButton.isEnabled = isConnected
            focusButton.isEnabled = isConnected
            if (::wbRow.isInitialized) {
                wbRow.view.alpha = if (isConnected) 1f else 0.45f
            }
            liveViewButton.isEnabled = isConnected
            // 相册入口是底部导航第三格（由 switchTab 之外的 openGallery 处理）：
            // 未连接时整格调暗，点了会提示先连接相机。
            navGallery.alpha = if (isConnected) 1f else 0.35f
            apBracketingButton.isEnabled = isConnected
            expBracketingButton.isEnabled = isConnected
            
            isoUpButton.isEnabled = isConnected
            isoDownButton.isEnabled = isConnected
            apertureUpButton.isEnabled = isConnected
            apertureDownButton.isEnabled = isConnected
            shutterUpButton.isEnabled = isConnected
            shutterDownButton.isEnabled = isConnected

            // 这三个控件现在建在「个人设定」抽屉里（打开抽屉才存在）：
            // 没打开过就跳过 —— 直接访问 lateinit 会抛 UninitializedPropertyAccessException。
            if (::customScanButton.isInitialized) {
                customScanButton.isEnabled = isConnected && !customScanning
            }
            if (::csmReadButton.isInitialized) {
                csmReadButton.isEnabled = isConnected && !csmLoading
            }
            if (::csmScanButton.isInitialized) {
                csmScanButton.isEnabled = isConnected && !csmLoading
            }

            // 取景框右上角的点数 chip：没连接时压暗（点了会提示先连接）
            renderAfPointRow()

            // 参数页新增的行：未连接时压暗（点击仍会被各自的守卫拦住并提示）
            if (::flashCompRow.isInitialized) {
                val alpha = if (isConnected) 1f else 0.45f
                listOf(
                    flashCompRow, imageQualityRow, mediaRow,
                    intervalRow, autoDownloadRow
                ).forEach { it.view.alpha = alpha }
                if (::focusStackRow.isInitialized) focusStackRow.view.alpha = alpha
                // 方框那两组没有统一的 Row 外壳，单独压暗
                selfTimerBox?.alpha = alpha
                afPointCountBox?.alpha = alpha
            }

            // 断开时状态条要给出下一步该做什么，而不是干巴巴一句"未连接"
            if (!isConnected) restoreStatusText()
        }
    }

    /**
     * 峰值对焦开关。
     *
     * 只在取景中能按：开销是按帧算的（每帧要跑一遍 Sobel），没取景时不该有一分开销。
     * 关掉时 peakingView 会主动 clear() 把上一帧的掩码从屏幕上撤下来，
     * 否则会留一层"卡住的红边"在画面上。
     */
    private fun togglePeaking() {
        val on = !peakingView.peakingEnabled
        peakingView.peakingEnabled = on
        renderPeakButton()
        log(
            if (!on) "取景辅助已关闭"
            else if (peakingView.mode == PeakingView.OverlayMode.ZEBRA) "过曝斑马纹已开启：过曝处画斜纹"
            else "峰值对焦已开启：合焦处泛红"
        )
    }

    /** 取景页那颗「峰值 / 斑马纹」按钮的统一渲染：可见性之外，文字也要跟着当前模式走 */
    private fun renderPeakButton() {
        val on = peakingView.peakingEnabled
        val zebra = peakingView.mode == PeakingView.OverlayMode.ZEBRA
        peakButton.text = when {
            !on -> if (zebra) "斑马纹" else "峰值"
            zebra -> "斑马纹 ●"
            else -> "峰值 ●"
        }
        peakButton.setTextColor(
            if (on) getColor(R.color.accent_red) else getColor(R.color.text_disabled)
        )
    }

    /**
     * 取景辅助二级页（峰值按钮**长按**进）：模式 / 阈值 / 颜色。
     *
     * 阈值原来是写死在 PeakingView 里的常量 110 —— 那是在 640×480 取景帧上试出来的
     * 折中值，但换个镜头、换个光线（暗光噪点多、逆光轮廓硬）差别很大：
     * 有人嫌满屏噪点，有人嫌弱边缘上不了色。所以做成可调并记住。
     *
     * 斑马纹（过曝警告）是另一件事：不看梯度只**看亮度**，超过阈值的区域画斜纹。
     * 它解决的不是"合没合焦"而是"高光是不是要爆了" —— 三脚架上拍风景时
     * 取景画面又小又亮，肉眼很难判断天空是否已经死白。
     */
    private fun showPeakingSheet() {
        val prefs = getSharedPreferences(AF_PREFS, Context.MODE_PRIVATE)
        val page = buildSheetPage("取景辅助", "峰值对焦 / 过曝斑马纹（长按峰值按钮也能进）")

        val modeCard = sheetCard(page.content, "模式")
        val modeLabels = listOf("峰值对焦", "过曝斑马纹")
        val modeIdx = if (peakingView.mode == PeakingView.OverlayMode.ZEBRA) 1 else 0
        modeCard.addView(sheetTileGrid(2, modeLabels, modeIdx) { idx ->
            peakingView.mode =
                if (idx == 1) PeakingView.OverlayMode.ZEBRA else PeakingView.OverlayMode.PEAKING
            prefs.edit().putInt(PEAK_MODE_KEY, idx).apply()
            renderPeakButton()
            log("取景辅助：${modeLabels[idx]}")
        }.view)
        // sheetCard 自己就把卡片挂进 page.content 了，再 addView 一次会抛
        // "The specified child already has a parent"（真机 23:12 崩过）

        val thrCard = sheetCard(page.content, "阈值")
        thrCard.addView(sliderRow("峰值灵敏度", peakingView.threshold, 20, 240) { v ->
            peakingView.threshold = v
            prefs.edit().putInt(PEAK_THRESHOLD_KEY, v).apply()
        })
        thrCard.addView(sliderRow("斑马纹亮度", peakingView.zebraThreshold, 200, 255) { v ->
            peakingView.zebraThreshold = v
            prefs.edit().putInt(ZEBRA_THRESHOLD_KEY, v).apply()
        })


        val colorCard = sheetCard(page.content, "颜色")
        val colors = listOf("红", "黄", "绿", "青", "白")
        val cvals = listOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.WHITE
        )
        val cIdx = cvals.indexOf(peakingView.peakingColor).let { if (it < 0) 0 else it }
        colorCard.addView(sheetTileGrid(colors.size, colors, cIdx) { idx ->
            peakingView.peakingColor = cvals[idx]
            prefs.edit().putInt(PEAK_COLOR_KEY, cvals[idx]).apply()
            log("取景辅助颜色：${colors[idx]}")
        }.view)


        page.content.addView(
            sheetHint("峰值灵敏度调低 → 连弱边缘也上色（但噪点也会一起亮）；调高 → 只留最锐的轮廓。斑马纹亮度调到 255 就是只标死白，245 会提前两档提醒。")
        )
        page.show()
    }

    /** 一行「标签 + 当前值 + 滑块」，二级页里没有现成构件，这里自己搭一个 */
    private fun sliderRow(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(14), dpx(4), dpx(14), dpx(4))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val num = TextView(this).apply {
            text = value.toString()
            setTextColor(getColor(R.color.accent_amber))
            textSize = 13f
        }
        head.addView(num)
        val seek = androidx.appcompat.widget.AppCompatSeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                val real = v + min
                num.text = real.toString()
                if (fromUser) onChange(real)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        root.addView(head)
        root.addView(seek)
        return root
    }

    // =====================================================================
    // 机身时钟（0x5011 DateTime）
    //
    // 机身里那颗小电池掉电后时间会归零，EXIF 时间戳就全是 2000 年 ——
    // 相册排序、间隔拍摄的时点、照片归档全跟着错。PTP 标准把 DateTime 定成
    // 可写属性，所以 App 能顺手把手机时间写进去，不必去菜单里一格格拨。
    // =====================================================================

    /** "20261024T221800.0+08:00" → "2026-10-24 22:18:00"（机身墙上时间，不做时区换算） */
    private fun formatPtpWallClock(raw: String): String {
        if (raw.length < 15) return raw
        return "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)} " +
            "${raw.substring(9, 11)}:${raw.substring(11, 13)}:${raw.substring(13, 15)}"
    }

    /**
     * PTP 时间字符串 → epoch 毫秒（**已按尾部时区折算成真实瞬时**），解析不了返回 null。
     *
     * 折算很关键：机身给的是"它的墙上时间 + 它的时区"，手机给的是 UTC 瞬时，
     * 两边直接比字符串没有意义，也不该假设机身就是本地时区。
     *
     * ⚠️ 但 D300 实测（2026-10-26 23:43 真机日志）返回的是 **`20261026T234309`，
     * 尾部没有时区**。以前一律按 UTC 解释，于是机身明明跟手机同步，界面却报
     * "机身时钟快 7 小时"（差的就是 UTC+8）。所以：**带时区才按偏移折算，不带就按本地时区**。
     */
    private fun parsePtpDateTime(raw: String): Long? {
        val s = raw.trim()
        if (s.length < 15) return null
        return try {
            var i = 15
            while (i < s.length && s[i] != '+' && s[i] != '-' && s[i] != 'Z') i++
            val hasOffset = i < s.length
            val tz = if (hasOffset) java.util.TimeZone.getTimeZone("UTC")
            else java.util.TimeZone.getDefault()
            val cal = java.util.Calendar.getInstance(tz)
            cal.clear()
            cal.set(
                s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(),
                s.substring(9, 11).toInt(), s.substring(11, 13).toInt(), s.substring(13, 15).toInt()
            )
            var ms = cal.timeInMillis
            if (hasOffset && s[i] != 'Z') {
                val sign = if (s[i] == '-') -1 else 1
                val rest = s.substring(i + 1).split(":")
                val oh = rest.getOrNull(0)?.toIntOrNull() ?: 0
                val om = rest.getOrNull(1)?.toIntOrNull() ?: 0
                ms -= sign * (oh * 60 + om) * 60_000L
            }
            ms
        } catch (e: Exception) {
            log("机身时间解析失败（原始串 \"$raw\"）")
            null
        }
    }

    /** 把手机此刻的时间编成 PTP 字符串：20261024T221800.0+08:00 */
    private fun ptpTimeNow(): String {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        val cal = java.util.Calendar.getInstance()
        val offMin = (cal.get(java.util.Calendar.ZONE_OFFSET) +
            cal.get(java.util.Calendar.DST_OFFSET)) / 60_000
        val a = kotlin.math.abs(offMin)
        return sdf.format(java.util.Date()) + ".0" +
            (if (offMin < 0) "-" else "+") + String.format(Locale.US, "%02d:%02d", a / 60, a % 60)
    }

    /** 描述符里的 get/set 位：0 = 只读。发写命令之前先问一次，别硬撞 */
    private fun devicePropWritable(propCode: Int): Boolean {
        val raw = ptpConnection?.getDevicePropDesc(propCode) ?: return false
        // payload: 12 字节头 + 属性码(2) + 数据类型(2) + get/set(1)
        if (raw.size < 18) return false
        return (raw[16].toInt() and 0xFF) != 0
    }

    private fun showCameraClockSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage("机身时钟", "0x5011 DateTime · 机身内部时钟")
        val card = sheetCard(page.content, "时间")
        val (bodyRow, bodyValue) = sheetInfoRow("机身时间", "读取中…")
        val (phoneRow, phoneValue) = sheetInfoRow("手机时间", "")
        val (diffRow, diffValue) = sheetInfoRow("偏差", "--")
        card.addView(bodyRow); card.addView(phoneRow); card.addView(diffRow)


        phoneValue.text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(java.util.Date())

        val syncRow = sheetValueRow("用手机时间校准机身", "点击写入") { _ ->
            if (!isConnected) { toast("请先连接相机"); return@sheetValueRow }
            val text = ptpTimeNow()
            toast("正在写入 $text")
            thread {
                val conn = ptpConnection
                val writable = devicePropWritable(PtpConstants.PROP_DATE_TIME)
                val ok = if (writable) conn?.setDevicePropString(PtpConstants.PROP_DATE_TIME, text) ?: false else false
                runOnUiThread {
                    if (ok) {
                        log("机身时钟已同步为 $text")
                        toast("已同步")
                        bodyValue.text = formatPtpWallClock(text)
                        diffValue.text = "0 秒"
                    } else {
                        val why = if (!writable) "机身把 0x5011 报成只读" else "机身拒绝了写入（看日志里的回码）"
                        log("机身时钟同步失败：$why")
                        toast("同步失败：$why")
                    }
                }
            }
        }
        card.addView(syncRow.view)
        page.content.addView(
            sheetHint("机身内部电池掉电后时间会归零，EXIF 里就全是 2000 年 —— 相册排序和间隔拍摄的时点都会跟着错。同步只是把手机当前时间写进机身，不碰别的设置。")
        )
        page.show()

        thread {
            val raw = ptpConnection?.getDevicePropString(PtpConstants.PROP_DATE_TIME)
            runOnUiThread {
                if (raw == null) {
                    bodyValue.text = "读取失败"
                    diffValue.text = "--"
                    log("机身时钟：0x5011 没读到（机身不支持或会话忙）")
                    return@runOnUiThread
                }
                bodyValue.text = formatPtpWallClock(raw)
                val bodyMs = parsePtpDateTime(raw)
                if (bodyMs == null) {
                    diffValue.text = "无法解析（原始串 \"$raw\"）"
                } else {
                    val diffSec = (bodyMs - System.currentTimeMillis()) / 1000L
                    diffValue.text = describeClockDrift(diffSec)
                    if (kotlin.math.abs(diffSec) > 60) {
                        diffValue.setTextColor(getColor(R.color.accent_red))
                    }
                }
            }
        }
    }

    private fun describeClockDrift(diffSec: Long): String {
        val a = kotlin.math.abs(diffSec)
        val span = when {
            a < 5 -> "基本准确"
            a < 60 -> "$a 秒"
            a < 3600 -> "${a / 60} 分 ${a % 60} 秒"
            a < 86400 -> "${a / 3600} 小时"
            else -> "${a / 86400} 天"
        }
        return when {
            a < 5 -> span
            diffSec > 0 -> "快 $span"
            else -> "慢 $span"
        }
    }

    /**
     * 每次连上机身顺手看一眼时钟，偏差超过 1 分钟就提示一次。
     *
     * 静默读一次 0x5011 只要几十毫秒，但能挡掉"照片时间全是 2000 年"这种
     * 事后才发现、且没法补救（EXIF 已经写死在文件里）的问题。
     */
    private fun checkCameraClockDrift() {
        if (!isConnected) return
        thread {
            val raw = ptpConnection?.getDevicePropString(PtpConstants.PROP_DATE_TIME) ?: return@thread
            val bodyMs = parsePtpDateTime(raw) ?: return@thread
            val diffSec = (bodyMs - System.currentTimeMillis()) / 1000L
            if (kotlin.math.abs(diffSec) <= 60) {
                log("机身时钟：${describeClockDrift(diffSec)}")
                return@thread
            }
            runOnUiThread {
                log("⚠️ 机身时钟${describeClockDrift(diffSec)}，可在参数页「机身时钟」一键校准")
                toast("机身时钟${describeClockDrift(diffSec)}，可在参数页「机身时钟」里校准")
            }
        }
    }

    // =====================================================================
    // 诊断包导出
    //
    // 真机出问题的时候，最有价值的东西不是截图而是这四样：机身自述了什么、
    // 支持哪些操作码、当前各属性是什么、以及出事前那几百行日志。
    // 打成一个 txt 丢进下载目录，比让人手抄日志靠谱得多。
    // =====================================================================

    private fun appVersionText(): String = try {
        val p = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        "${p.versionName} ($code)"
    } catch (e: Exception) {
        "未知"
    }

    private fun exportDiagnostics() {
        val sb = StringBuilder()
        sb.append("D300 Controller 诊断包\n")
        sb.append("生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())}\n")
        sb.append("App 版本：${appVersionText()}　包名：$packageName\n")
        sb.append("手机：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("连接状态：${if (isConnected) "已连接" else "未连接"}\n")

        sb.append("\n---- 机身 ----\n")
        val info = deviceInfoCache
        if (info == null) {
            sb.append("（无 DeviceInfo；${if (isConnected) "连接中但没读到" else "未连接"}）\n")
        } else {
            sb.append("厂商：${info.manufacturer}\n型号：${info.model}\n固件：${info.versionOrDash()}\n序列号：${info.serialOrDash()}\n")
        }
        sb.append("快门数：${if (shutterCount >= 0) "$shutterCount 次" else "未读取"}\n")

        sb.append("\n---- 机身支持的操作码 ----\n")
        ptpConnection?.logSupportedOperations { sb.append(it).append('\n') }
            ?: sb.append("（未连接）\n")

        sb.append("\n---- 关键属性快照 ----\n")
        val snapshot = listOf(
            "0x5011 DateTime" to PtpConstants.PROP_DATE_TIME,
            "0xD100 快门" to PtpConstants.PROP_NIKON_SHUTTER_SPEED,
            "0xD10B 记录媒介" to PtpConstants.PROP_NIKON_RECORDING_MEDIA,
            "0xD1A0 取景模式" to PtpConstants.PROP_NIKON_LIVE_VIEW_MODE,
            "0x500A 对焦模式" to PtpConstants.PROP_FOCUS_MODE,
            "0xD054 ISO 自动" to PtpConstants.PROP_NIKON_ISO_AUTO
        )
        thread {
            val lines = StringBuilder()
            if (isConnected) {
                val media = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_RECORDING_MEDIA)
                lines.append(
                    "0xD10B 记录媒介：" + if (media == null || media == -999999) "读取失败"
                    else "0x${Integer.toHexString(media).uppercase()}" + "\n"
                )
                for ((name, code) in snapshot) {
                    val desc = ptpConnection?.describeProp(code)
                    lines.append("$name：$desc\n")
                }

                // ---- 机身状态位：用户报"按不下快门 / 进不了取景 / 拍一半卡住"时先看这三行 ----
                // 三个码都来自官方属性表（0xD102 WarningStatus、0xD1A4 LiveViewProhibitionCondition、
                // 0xD103 RemainingExposure），只看原始值不猜语义：非 0 就是"机身有话说"。
                lines.append("\n---- 机身状态位 ----\n")
                val statusBits = listOf(
                    "0xD102 警告状态 WarningStatus" to PtpConstants.PROP_NIKON_WARNING_STATUS,
                    "0xD1A4 取景禁止条件" to PtpConstants.PROP_NIKON_LV_PROHIBIT,
                    "0xD103 剩余可拍张数" to PtpConstants.PROP_NIKON_REMAINING_EXPOSURE
                )
                for ((label, code) in statusBits) {
                    val v = ptpConnection?.getDevicePropValue(code) ?: -999999
                    lines.append(
                        if (v == -999999) "$label：读取失败\n"
                        else "$label：0x${Integer.toHexString(v).uppercase()}（$v）\n"
                    )
                }

                // 会话发起者版本（0xD406）：界面「机身状态」里不列这一行（对拍照没用），
                // 但排错时要看是谁占了会话，所以留在诊断包里。
                lines.append(
                    "0xD406 会话发起者：" +
                        (try {
                            ptpConnection?.getDevicePropString(PtpConstants.PROP_NIKON_SESSION_INITIATOR_VERSION)
                        } catch (_: Exception) {
                            null
                        } ?: "读取失败") + "\n"
                )

                // ---- 全厂商属性快照（0x90CA 清单 + 逐条描述符）----
                //
                // 官方属性表（197 项）里有一批**只给名字、不给值域**的码（闪光灯状态、
                // 机身方向、缓冲张数…），它们到底在 D300 上应不应答、值域几档，
                // 只能靠这份快照说话。一次 0x90CA 拿到机身自报的清单，再逐条读描述符 ——
                // 比一条条盲试准，也比盲扫快。约十几秒，只在手动导出时跑。
                lines.append("\n---- 全厂商属性快照（0x90CA）----\n")
                val vendorCodes = try {
                    ptpConnection?.getVendorPropCodes()
                } catch (_: Exception) {
                    null
                }
                if (vendorCodes == null || vendorCodes.isEmpty()) {
                    lines.append("（机身没回 0x90CA 清单）\n")
                } else {
                    lines.append("机身自报 ${vendorCodes.size} 项\n")
                    for (c in vendorCodes) {
                        val desc = try {
                            ptpConnection?.describeProp(c)
                        } catch (_: Exception) {
                            null
                        }
                        lines.append("0x%04X  %s\n".format(c, desc ?: "描述符读取失败"))
                    }
                }
            } else {
                lines.append("（未连接）\n")
            }
            // 自定义属性码覆盖：这些码是手工试出来的，换台机身也许不同，值得一起带走
            val ov = getSharedPreferences("csm_codes", Context.MODE_PRIVATE).all
            lines.append("\n---- 属性码覆盖 ----\n")
            if (ov.isEmpty()) lines.append("（无）\n")
            else ov.toSortedMap().forEach { (k, v) ->
                lines.append("$k = 0x${Integer.toHexString((v as? Int) ?: 0).uppercase()}\n")
            }
            lines.append("\n---- 日志（最近 ${LOG_MAX_LINES} 行）----\n")
            lines.append(infoText.text).append('\n')

            val name = "D300诊断_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())}.txt"
            val ok = saveTextToDownloads(name, sb.toString() + lines.toString())
            runOnUiThread {
                if (ok) {
                    log("诊断包已导出到 下载/D300/$name")
                    toast("已导出到 下载/D300/$name")
                } else {
                    toast("导出失败：写不了下载目录")
                }
            }
        }
    }

    /** 写个文本文件到 下载/D300。走 MediaStore，不需要任何存储权限 */
    private fun saveTextToDownloads(name: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            log("导出失败：Android 10 以下不支持免权限写入公共目录")
            return false
        }
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/D300")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            log("导出诊断包失败：${e.message}")
            false
        }
    }

    // =====================================================================
    // 设置与标定数据的备份 / 恢复
    //
    // AF 51 点标定、手工试出来的属性码覆盖、流程设置全躺在 SharedPreferences 里，
    // 卸载就没了 —— 而换装正式签名包恰恰必须卸载重装（签名不同不能覆盖安装），
    // 标定一次要十几分钟，这个坑迟早会踩。所以做成导出成 JSON、可再导入。
    // =====================================================================

    private fun backupPrefNames(): List<String> =
        listOf(AF_PREFS, "af51_calib", "csm_codes", "workflow")

    private fun buildBackupJson(): String {
        val root = org.json.JSONObject()
        root.put("schema", 1)
        root.put("app", "NikonD300Controller")
        root.put("exportedAt", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date()))
        val prefsObj = org.json.JSONObject()
        for (name in backupPrefNames()) {
            val obj = org.json.JSONObject()
            for ((k, v) in getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                when (v) {
                    null -> obj.put(k, org.json.JSONObject.NULL)
                    is Set<*> -> {
                        val arr = org.json.JSONArray()
                        v.forEach { arr.put(it) }
                        obj.put(k, arr)
                    }
                    else -> obj.put(k, v)
                }
            }
            prefsObj.put(name, obj)
        }
        root.put("prefs", prefsObj)
        return root.toString(2)
    }

    /** @return 写进去的键值条数；格式不对直接抛，让调用方提示用户 */
    private fun applyBackupJson(text: String): Int {
        val root = org.json.JSONObject(text)
        val schema = root.optInt("schema", 0)
        if (schema != 1) throw IllegalArgumentException("不支持的备份版本 schema=$schema")
        val prefsObj = root.getJSONObject("prefs")
        var count = 0
        val names = prefsObj.keys()
        while (names.hasNext()) {
            val name = names.next()
            val obj = prefsObj.getJSONObject(name)
            val editor = getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            val ks = obj.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                when (val v = obj.get(k)) {
                    org.json.JSONObject.NULL -> editor.remove(k)
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Double -> editor.putFloat(k, v.toFloat())
                    is Float -> editor.putFloat(k, v)
                    is String -> editor.putString(k, v)
                    is org.json.JSONArray -> {
                        val set = LinkedHashSet<String>()
                        for (i in 0 until v.length()) set.add(v.getString(i))
                        editor.putStringSet(k, set)
                    }
                }
                count++
            }
            editor.apply()
        }
        return count
    }

    private fun showBackupSheet() {
        val page = buildSheetPage("备份与恢复", "标定数据 · 属性码覆盖 · 流程设置")
        val card = sheetCard(page.content, "备份内容")
        val items = listOf(
            AF_PREFS to "对焦偏好（触摸对焦、取景模式、峰值设置、AF 点数…）",
            "af51_calib" to "51 点对焦框标定（照着取景画面一格格调出来的坐标）",
            "csm_codes" to "手工试出来的属性码覆盖",
            "workflow" to "拍摄流程（自拍延时、间隔、反光板预升、自动下载）"
        )
        items.forEach { (name, desc) ->
            val n = getSharedPreferences(name, Context.MODE_PRIVATE).all.size
            card.addView(sheetInfoRow(desc, "$n 项").first)
        }


        val exportRow = sheetValueRow("导出备份", "存成 .json") { _ ->
            runCatching { backupExportLauncher.launch("D300备份_${
                java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
            }.json") }.onFailure { toast("打不开保存对话框：${it.message}") }
        }
        card.addView(exportRow.view)

        val importRow = sheetValueRow("导入备份", "选 .json 恢复") { _ ->
            runCatching { backupImportLauncher.launch(arrayOf("application/json", "*/*")) }
                .onFailure { toast("打不开文件选择器：${it.message}") }
        }
        card.addView(importRow.view)

        page.content.addView(
            sheetHint("换装正式签名的包必须卸载旧的（签名不同覆盖安装会失败），卸载就会清空这些设置 —— 先导出一份，装完再导回来，标定不用重做。导入会覆盖同名设置，之后需要重启 App 生效。")
        )
        page.show()
    }

    private fun writeBackupTo(uri: android.net.Uri) {
        thread {
            val json = runCatching { buildBackupJson() }.getOrNull()
            runOnUiThread {
                if (json == null) { toast("生成备份失败"); return@runOnUiThread }
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } != null
                } catch (e: Exception) {
                    log("写备份失败：${e.message}"); false
                }
                if (ok) {
                    log("备份已导出（${backupPrefNames().size} 组设置）")
                    toast("备份已导出")
                } else toast("导出失败：写不了这个文件")
            }
        }
    }

    private fun readBackupFrom(uri: android.net.Uri) {
        thread {
            val text = try {
                contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                log("读备份失败：${e.message}"); null
            }
            val count = text?.let { runCatching { applyBackupJson(it) }.getOrNull() }
            runOnUiThread {
                if (count == null) {
                    toast("导入失败：文件不是有效的备份")
                    return@runOnUiThread
                }
                log("已导入 $count 项设置，需要重启 App 生效")
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("导入完成")
                    .setMessage("已恢复 $count 项设置。这些值在 App 启动时读入，需要重启一次才生效。")
                    .setPositiveButton("立即重启") { _, _ -> recreate() }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }

    /**
     * 触摸对焦开关。
     *
     * 开着之后，改对焦点的两条路都会顺带对一次焦：
     * - 手持（51 点相位 AF）：点画面上的点阵 / 按方向键只是"选中"（写 0xD108），
     *   机身**不会**因此去对焦 —— 这时补一发 0x90C1 AfDrive，省掉"选完再按 AF"这一步。
     * - 三脚架：0x9205 之后机身自己会跑对比度 AF（还会响提示音），
     *   这条路**不再叠加** 0x90C1 —— 两次对焦会打架，还把"机身自己合焦"那次的状态冲掉。
     */
    private fun toggleTouchAf() {
        touchAfEnabled = !touchAfEnabled
        getSharedPreferences(AF_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(AF_KEY_TOUCH_AF, touchAfEnabled).apply()
        renderTouchAfButton()
        // 开关一变，画面点击该不该接管就得重算：手持模式下这条回调是跟着开关挂/摘的，
        // 不重渲染的话"开了触摸对焦点画面仍然没反应"。
        renderLiveViewMode(currentLiveViewMode)
        log(
            if (touchAfEnabled) "触摸对焦已开启：点画面任意位置即对焦（三脚架下机身自己会对，不再叠加）"
            else "触摸对焦已关闭"
        )
        // 未取景时点这个开关只是改设置，得说清楚"现在还没生效"，别让人以为坏了
        toast(
            when {
                !touchAfEnabled -> "触摸对焦已关闭"
                isLiveViewRunning -> "触摸对焦已开启：点画面任意位置即对焦"
                else -> "触摸对焦已开启 —— 进入实时取景后生效"
            }
        )
    }

    private fun renderTouchAfButton() {
        touchAfButton.text = if (touchAfEnabled) "触摸对焦 ●" else "触摸对焦"
        touchAfButton.setTextColor(
            if (touchAfEnabled) getColor(R.color.accent_amber) else getColor(R.color.text_disabled)
        )
        // ★ 它是**设置项**，不是"取景时才出现的操作按钮"：未取景时也留着。
        //   早先只在 isLiveViewRunning 时可见，结果"想先打开开关、再进画面"的人
        //   在取景外根本找不到入口。参数页另有一份同样的入口（touchAfRow）。
        touchAfButton.visibility = View.VISIBLE
        if (::touchAfRow.isInitialized) {
            touchAfRow.value.text = if (touchAfEnabled) "开启" else "关闭"
            touchAfRow.value.setTextColor(
                if (touchAfEnabled) getColor(R.color.accent_amber) else getColor(R.color.text_disabled)
            )
        }
    }

    /**
     * 对焦点**刚改完**时按需补一次对焦。
     *
     * @param via 触发来源，只进日志（排查"为什么又对了一次焦"时一眼能看出来）
     */
    private fun autoFocusOnAfPointChange(via: String) {
        if (!touchAfEnabled) return
        // 三脚架模式：0x9205 之后机身自己会跑对比度 AF（moveAfAreaTo 里已经等它锁焦），
        // 这里再发 0x90C1 会和机身那次抢反光板，还把状态标记冲掉。
        if (currentLiveViewMode == PtpConstants.LIVE_VIEW_MODE_TRIPOD) {
            log("触摸对焦：三脚架模式下机身已自行对焦，不再叠加（$via）")
            return
        }
        if (!isConnected) return
        // 节流：连按方向键时 AF 命令会堆成一串，机身消化不过来反而更慢
        val now = System.currentTimeMillis()
        if (now - lastAutoAfMs < 400) {
            log("触摸对焦：距上一次太近，跳过（$via）")
            return
        }
        lastAutoAfMs = now
        thread {
            val t0 = System.currentTimeMillis()
            runOnUiThread { log("触摸对焦：$via 后自动对焦…") }
            val ok = ptpConnection?.afDrive() ?: false
            val cost = System.currentTimeMillis() - t0
            runOnUiThread {
                setFocusStatus(ok, cost)
                log(
                    if (ok) "触摸对焦：已合焦（$via 触发）耗时 ${formatDuration(cost)}"
                    else "触摸对焦：未合焦（$via 触发）—— 太暗或没反差时正常，可手动对焦"
                )
            }
        }
    }


    private fun startLiveView(keepAfLock: Boolean = false) {
        // 防重入：整条开启链路可能跑十几秒。以前这期间不设防，用户等不及再点一次
        // 就会再起一个线程，几个线程同时抢 usbLock，越点越慢，最后谁都开不起来。
        if (isLiveViewStarting || isLiveViewRunning) return
        isLiveViewStarting = true

        // 立刻给反馈：让用户知道请求已被接受、机身正在响应。
        // 这一步是"LV 没反应"最直接的解药 —— 以前按钮点下去毫无变化，只能干等。
        runOnUiThread {
            setLiveViewState(1)
            liveViewButton.isEnabled = false
            liveViewPlaceholder.text = "正在开启取景…"
            liveViewPlaceholder.visibility = View.VISIBLE
            log("正在开启实时取景…")
        }

        // ★ 看门狗：启动链路里有几处等待（0x90C2 各试 20s、0x9201 忙等），
        // 万一某一步卡住，isLiveViewStarting 就一直为 true —— 按钮保持 disabled、
        // 再点还会被防重入挡掉，用户看到的就是"点取景毫无反应"，只能杀进程。
        // 75 秒还没起来就强制复位（正常启动一般 5~15 秒）。
        lvWatchdogTask?.let { lvWatchdog.removeCallbacks(it) }
        lvWatchdogTask = Runnable {
            if (isLiveViewStarting && !isLiveViewRunning) {
                isLiveViewStarting = false
                runOnUiThread {
                    liveViewButton.isEnabled = isConnected
                    setLiveViewState(0)
                    liveViewPlaceholder.visibility = View.GONE
                    log("取景启动超时（75 秒），已强制恢复按钮 —— 可再点一次 LV 重试")
                }
            }
        }.also { lvWatchdog.postDelayed(it, 75_000) }

        thread {
            // 记住取景循环所在线程：退出取景时必须先等它停下来，
            // 否则两条线程同时打同一条 USB 管道，退出命令的响应会被读帧那头吃掉。
            liveViewThread = Thread.currentThread()
            try {
                // ★ 开取景前先写一次取景模式（0xD1A0）。
                //
                // 机身收到 0x9202（退出取景）时会把这一项重置成 0（手持），
                // 而且"拍完再写回"经常不被接受 —— 结果就是三脚架模式拍一张、
                // 下次开取景又变回手持。改成每次开取景前按用户选的档写一遍，
                // 这样设过一次三脚架就一直有效，跟拍多少张无关。
                // 模式值只可能是 0/1；万一 currentLiveViewMode 是脏值（例如机身报过的 0x8013），
                // 这里按兜底值写一次 —— 不写的话机身可能带着无效模式去启动取景，直接 0x2019。
                val wantMode = when (currentLiveViewMode) {
                    PtpConstants.LIVE_VIEW_MODE_HANDHELD,
                    PtpConstants.LIVE_VIEW_MODE_TRIPOD -> currentLiveViewMode
                    else -> PtpConstants.LIVE_VIEW_MODE_TRIPOD
                }
                currentLiveViewMode = wantMode
                val okMode = ptpConnection?.setLiveViewMode(wantMode) ?: false
                val name = if (wantMode == PtpConstants.LIVE_VIEW_MODE_TRIPOD) "三脚架" else "手持"
                runOnUiThread { log("开取景前预设取景模式=$name（${if (okMode) "成功" else "失败"}）") }

                val result = ptpConnection?.startLiveView()
                    ?: LiveViewStartResult.fail("尚未连接相机")

                if (result.ok) {
                    isLiveViewRunning = true
                    // 用户主动开取景 → 清掉合焦标记（要重新对焦）；
                    // 拍完自动恢复 → keepAfLock=true，镜头没动过，合焦依然有效
                    if (!keepAfLock) lvAfLocked = false
                    runOnUiThread {
                        // 必须在这里把按钮重新点亮：开启时为了防连点把它禁用了，
                        // 而唯一解除禁用的 finally 要等取景循环结束才执行 ——
                        // 那期间按钮一直是灰的，用户点它没有任何反应，
                        // 于是就成了"取景进得去、退不出来，只能关相机电源"。
                        liveViewButton.isEnabled = true
                        setLiveViewState(2)
                        setViewfinderExpanded(true)
                        liveViewPlaceholder.visibility = View.GONE
                        liveViewImage.visibility = View.VISIBLE
                        // 退出取景时摘掉过（见 resetLiveViewButton），开取景要再挂回去
                        focusBox.attachImageView(liveViewImage)
                        // 取景时才谈得上录像（录制的是取景帧）
                        recButton.visibility = View.VISIBLE
                        // 峰值对焦同理：只对实时画面有意义，且开销是按帧算的
                        peakButton.visibility = View.VISIBLE
                        renderTouchAfButton()
                        lvFullscreenButton.visibility = View.VISIBLE
                        renderRecState(recorder?.isRecording == true)
                        // 取景一开，点阵要不要显示由取景模式决定（三脚架模式只留 AF 框）
                        renderLiveViewMode(currentLiveViewMode)
                        // ★ 放大倍率归位：机身会记住上次放大到几倍，开取景时先写回全画面。
                        // 不然一进取景就是放大的画面，而界面上写着"全画面"、点阵也按整幅坐标画 ——
                        // 表现就是"对焦点错位"（拍完再开取景尤其明显）。
                        resetLvZoomAsync()
                        log("Live View active —— ${if (focusBox.pointsOverlay) "点画面上的对焦点即可切换" else "点画面移动 AF 框"}")
                        // 点数以**用户记住的设置**为准：机身若停在上次的另一档，就写回去。
                        // （以前是反过来"跟随机身"，结果每次重开机身都会把 App 里选的档改掉）
                        thread {
                            val v = ptpConnection?.getDevicePropValue(
                                PtpConstants.PROP_NIKON_AF_POINT_COUNT
                            ) ?: -999999
                            val want = if (afPointCount51) 0 else 1
                            if (v != -999999 && v != want) {
                                log("机身点数是 ${if (v == 0) 51 else 11} 点，按设置改回 ${afPointCountText()}")
                                ptpConnection?.setAfPointCount(afPointCount51)
                            }
                        }
                    }
                    // 帧节拍：以前每帧固定 sleep(100)，加上取帧本身 50~60ms，实测只有 6fps。
                    // 改成"目标帧间隔 − 本帧已耗时"：取帧快就多睡、慢就少睡，帧率贴着 USB 能力走。
                    //
                    // ★ 计时一律用 nanoTime() 而不是 currentTimeMillis()：
                    //   后者是墙上时钟，取景途中被网络对时或用户改一下系统时间就会跳变，
                    //   spent 能算成负数或者好几秒，帧率当场乱掉。nanoTime 单调递增，
                    //   量间隔这件事只认它。
                    var lastUiPost = 0L
                    while (isLiveViewRunning) {
                        // 拍摄期间让路：快门命令要独占 USB，别和读帧抢
                        if (lvFramesPaused) {
                            Thread.sleep(50)
                            continue
                        }
                        val frameStart = System.nanoTime()
                        try {
                            val frame = ptpConnection?.getLiveViewFrame()
                            if (frame?.busy == true) {
                                // 对焦过程中机身一直回 busy。等短一点（40ms）：
                                // 一旦它喘过气就立刻恢复画面 —— 配合 afDrive 把锁拆细，
                                // 效果是"对焦时画面在动"，而不是整段僵住。
                                Thread.sleep(40)
                                continue
                            }
                            val bmp = frame?.bitmap
                            if (bmp != null) {
                                // 顺手记下整幅尺寸：0x9205 的坐标要用，且退出取景后还得留着
                                ptpConnection?.noteFrameGeometry(frame)
                                // 未放大时把 AF 框中心记下来：按放大那一刻机身就是绕着它裁的，
                                // 之后帧头坐标的参照系会变，只有这个值是"整幅画面"的
                                if (lvZoomValue == 0 && frame.afCenterX >= 0f && frame.afCenterY >= 0f) {
                                    lvAfCenterNx = frame.afCenterX
                                    lvAfCenterNy = frame.afCenterY
                                }

                                // ★ 录的是**机身原始帧**：对焦框和点阵都是 FocusBoxView
                                // 在 View 层 onDraw 画的，根本不在这块 Bitmap 里，
                                // 所以录像里不会出现任何叠加层（手持/三脚架都一样）。
                                // 提交必须在下一帧覆盖它之前完成 —— 所以是同步调用。
                                recorder?.submitFrame(bmp)

                                // 峰值对焦：同样必须同步算。这块 Bitmap 是池子里轮着用的，
                                // 下一帧就可能写进新内容，异步去读会读到半张新半张旧。
                                peakingView.submit(bmp)

                                // 屏幕刷新去抖：主线程忙时不给每一帧都排队，40ms（≈25fps）一次足够
                                val now = System.nanoTime()
                                if (now - lastUiPost >= UI_POST_INTERVAL_NS) {
                                    lastUiPost = now
                                    runOnUiThread {
                                        liveViewImage.setImageBitmap(bmp)
                                        focusBox.updateFromFrame(
                                            liveViewImage,
                                            frame.afCenterX, frame.afCenterY,
                                            frame.afWidth, frame.afHeight
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread { log("LV 帧错误: ${e.message}") }
                        }
                        val spent = (System.nanoTime() - frameStart) / 1_000_000L
                        val target = if (recorder?.isRecording == true) 66L else 70L
                        val wait = target - spent
                        if (wait > 5) Thread.sleep(wait)
                    }
                    focusBox.clear()
                    peakingView.clear()
                } else {
                    // 失败原因直接摆到用户面前：以前只有一行日志，而日志面板默认是收起的
                    runOnUiThread {
                        setLiveViewState(0)
                        setViewfinderExpanded(false)
                        liveViewPlaceholder.visibility = View.GONE
                        recButton.visibility = View.GONE
                        peakButton.visibility = View.GONE
                        // 触摸对焦开关**不跟着取景隐藏**：它是个设置项，界面上要一直能找到
                        renderTouchAfButton()
                        renderLiveViewMode(currentLiveViewMode)
                        log("Live View 启动失败：${result.reason}")

                        Toast.makeText(this@MainActivity, "取景启动失败：${result.reason}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    log("LV Error: ${e.message}")
                    // 循环异常退出后机身多半还留在取景状态，明确告诉用户下一步该点什么
                    log("取景循环已中断，机身可能仍在取景 —— 再点一次 LV 即可关闭取景")
                    Toast.makeText(this@MainActivity, "取景异常：${e.message}", Toast.LENGTH_LONG).show()
                }
                isLiveViewRunning = false
            } finally {
                isLiveViewStarting = false
                lvWatchdogTask?.let { lvWatchdog.removeCallbacks(it) }
                lvWatchdogTask = null
                runOnUiThread {
                    liveViewButton.isEnabled = isConnected
                    setLiveViewState(if (isLiveViewRunning) 2 else 0)
                }
            }
        }
    }

    /** 把 LV 按钮和取景区一起恢复成"未取景"的样子（以前几处复位只改了文字，底色会残留） */
    private fun resetLiveViewButton() {
        runOnUiThread {
            setLiveViewState(0)
            setViewfinderExpanded(false)
            liveViewButton.isEnabled = isConnected
            liveViewImage.visibility = View.GONE
            // ★ 最后一帧必须摘掉：ImageView 一旦 GONE，父容器就不再给它 layout，
            //   imageMatrix 会一直停在"上一次布局"（比如全屏那会儿）的尺寸上；
            //   对焦图层拿这个旧矩阵算画面矩形，点阵就整片画歪 ——
            //   也就是"拍完回到拍摄页，对焦点错位，重开一次取景才正常"。
            liveViewImage.setImageDrawable(null)
            focusBox.attachImageView(null)
            focusBox.setZoom(1f, 0.5f, 0.5f)
            // 倍率一起归位：退出取景前已经写过一次（stopLiveViewBlocking），
            // 这里是兜底 —— 界面上的倍率文案不许停在"200%"
            lvZoomValue = 0
            if (::lvZoomText.isInitialized) lvZoomText.text = lvZoomLabel(0)
            // 取景没了，录制的帧源也没了 —— 在录的话就地收尾，别留下半个文件
            if (recorder?.isRecording == true) stopRecording()
            recButton.visibility = View.GONE
            peakButton.visibility = View.GONE
            // 触摸对焦同理：退出取景后没有画面可点，chip 必须跟着收走
            touchAfButton.visibility = View.GONE
            lvFullscreenButton.visibility = View.GONE
            if (isLvFullscreen) {
                isLvFullscreen = false
                applyLiveViewFullscreen()
            }
            liveViewPlaceholder.visibility = View.GONE
            focusBox.clear()
            peakingView.clear()
            // 退出取景后点阵回到"未取景恒显示"的状态
            renderLiveViewMode(currentLiveViewMode)
        }
    }

    /**
     * 同步走完退出取景：置标志 → 等读帧循环退出 → 发 0x9202 → 复位界面。
     *
     * 关键在"等循环退出"这一步：取景时后台正以 10fps 发 0x9203 读帧，
     * 退出命令若和它同时打在一条 USB 管道上，机身收到的命令和回给我们的响应
     * 都会错位 —— 表现就是"点了退出没反应，只能关相机电源"。
     *
     * @return true = 机身确认已退出取景
     */
    private fun stopLiveViewBlocking(): Boolean {
        isLiveViewRunning = false

        // ★ 放大倍率先归位：机身会把它留着，下次开取景直接就是放大画面，
        //   而界面上还写着"全画面"、对焦点阵也按整幅坐标画 —— 就是"对焦点错位"。
        //   必须在发 0x9202 之前写：退出取景后这个属性多半就不让写了。
        if (lvZoomValue != 0) resetLvZoomQuiet()

        // 等读帧循环退出，但**绝不无限等**。循环若卡在某次 USB 读上，
        // 这里的 join 就必须有上限，否则退出流程永远走不到下一步 ——
        // 表现就是"点 LV 没反应，但点相册能退出"（相册走的是同一段代码，
        // 只是它不等循环，直接把命令排进队列）。
        try {
            liveViewThread?.join(3000)
        } catch (e: Exception) {
            log("等待取景循环退出被打断：${e.message}")
        }
        if (liveViewThread?.isAlive == true) {
            runOnUiThread { log("读帧循环未在 3s 内退出，继续发送退出命令") }
        }
        liveViewThread = null

        // 读帧循环已经停了，不会再有帧去引用池子里的 Bitmap，放掉让 GC 能收
        ptpConnection?.clearLiveViewBitmapPool()

        // 退出命令本身也设上限：机身不回应时不能把整条链路挂死，
        // 否则负责清理标志位的 finally 永远执行不到，之后每次点 LV 都会被防重入吞掉。
        var ok = false
        val worker = thread {
            try {
                ok = ptpConnection?.endLiveView() ?: false
            } catch (e: Exception) {
                runOnUiThread { log("Stop LV Error: ${e.message}") }
            }
        }
        // 上限从 15 秒压到 6 秒：机身不回应时干等十几秒纯属白占 USB 通道，
        // 期间别的命令一条都发不出去。到点没回就放开，让用户自己再点一次。
        worker.join(6000)
        if (worker.isAlive) {
            runOnUiThread { log("退出取景超时（6s 内机身无响应），可再点一次 LV 或用「停止」按钮") }
        }

        // 录制中退出取景：必须先收尾录像。
        // 取景一停就再没有帧提交，编码器却仍在"录制中"，MP4 会缺尾部甚至只剩黑帧。
        // 这里在 UI 线程调用 —— stopRecording 会动按钮状态，后台线程直接改会抛 CalledFromWrongThread。
        if (recorder?.isRecording == true) {
            runOnUiThread {
                log("取景已退出，录像自动收尾")
                stopRecording()
            }
        }

        resetLiveViewButton()
        return ok
    }

    private fun stopLiveView() {
        // 已经在退出中：明确告诉用户，而不是静默吞掉这次点击。
        // 以前这里直接 return，加上退出流程可能卡死（标志永不复位），
        // 结果就是"点 LV 永远退不出取景"。
        if (isLiveViewStopping) {
            Toast.makeText(this@MainActivity, "正在退出取景…", Toast.LENGTH_SHORT).show()
            return
        }
        isLiveViewStopping = true

        runOnUiThread {
            setLiveViewState(1)
            liveViewButton.isEnabled = false
            log("正在退出取景…")
        }

        thread {
            try {
                val ok = stopLiveViewBlocking()
                runOnUiThread {
                    if (ok) {
                        log("已退出实时取景")
                    } else {
                        val code = ptpConnection?.lastEndLiveViewCode ?: -1
                        val hex = if (code >= 0) "0x${Integer.toHexString(code).uppercase()}" else "无响应"
                        val msg = "退出取景未获机身确认（$hex）"
                        log(msg)
                        Toast.makeText(this@MainActivity, "$msg，再点一次 LV 可重试", Toast.LENGTH_LONG).show()
                        // 机身没确认退出，界面就不能假装已经关了。
                        // 这里保持"取景中"的外观，但 isLiveViewRunning 仍为 false：
                        // 再点一次 LV 会先探测到机身确实还在取景、接管画面，然后可以重试退出。
                        setLiveViewState(2)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { log("Stop LV Error: ${e.message}") }
            } finally {
                isLiveViewStopping = false
                runOnUiThread { liveViewButton.isEnabled = isConnected }
            }
        }
    }

    private fun updateProperties() {
        if (!isConnected || isPollingPaused) return 
        thread {
            try {
                if (isPollingPaused) return@thread
                Thread.sleep(100)
                val iso = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_INDEX) ?: -999999
                Thread.sleep(50)
                val aperture = ptpConnection?.getDevicePropValue(PtpConstants.PROP_F_NUMBER) ?: -999999
                Thread.sleep(50)
                val shutter = ptpConnection?.getDevicePropValue(activeShutterProp) ?: -999999
                Thread.sleep(50)
                val mode = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE) ?: -999999
                Thread.sleep(50)
                val focalLengthRaw = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCAL_LENGTH) ?: -999999
                Thread.sleep(50)
                val biasRaw = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_BIAS_COMPENSATION) ?: -999999
                Thread.sleep(50)
                val focusModeRaw = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCUS_MODE) ?: -999999
                Thread.sleep(50)
                // 电量只认 PTP 标准的 0x5001 BatteryLevel（官方属性表 Setup 页）。
                // 0xD1B3 的官方名是 ExposureIndicateLightup（曝光指示点亮），**不是电量** ——
                // 早先把它当"尼康专用电量"跟 0x5001 取最大值，一旦 0x5001 读不到就会
                // 把 0/1 显示成"1%"，是真真切切的假读数。少读一条还省一次 USB 往返。
                val stdBattery = ptpConnection?.getDevicePropValue(PtpConstants.PROP_BATTERY_LEVEL) ?: -999999
                Thread.sleep(50)
                val isoAuto = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO) ?: -999999
                Thread.sleep(50)
                // 白平衡：参数页那一行要显示"当前是自动还是晴天"，只能每次轮询带回来
                val whiteBalance = ptpConnection?.getDevicePropValue(PtpConstants.PROP_WHITE_BALANCE) ?: -999999
                Thread.sleep(50)

                // 机身当前对焦点：读到了就把对焦屏的高亮对上。
                // 不支持这个属性时返回 -999999，界面保持原样不去动它。
                val afPoint = ptpConnection?.getAfAreaPoint() ?: -999999
                Thread.sleep(50)

                // 取景模式（手持/三脚架）：机身上用拨盘改了也要跟着变，
                // 所以每次轮询都读回来，而不是只在点按钮时更新一次。
                val lvMode = ptpConnection?.getLiveViewMode() ?: -999999

                currentIsoAuto = isoAuto
                val batteryLevel = stdBattery
                val supportedAps = ptpConnection?.getDevicePropSupportedValues(PtpConstants.PROP_F_NUMBER)
                
                runOnUiThread {
                    if (batteryLevel != -999999) {
                        batteryText.text = "$batteryLevel%"
                        batteryText.setTextColor(when {
                            batteryLevel > 50 -> Color.parseColor("#30A46C")
                            batteryLevel > 20 -> Color.parseColor("#F57C00")
                            else -> Color.parseColor("#E5484D")
                        })
                    } else {
                        batteryText.text = "--%"
                    }

                    if (::wbRow.isInitialized && whiteBalance != -999999) {
                        wbRow.value.text = wbLabel(whiteBalance)
                    }

                    if (afPoint != -999999 && afPoint >= 0) {
                        currentAfPointId = afPoint
                        // 取景画面上叠的点阵也跟上：机身上用拨盘改了，画面上的高亮点
                        // 就得跟着换，否则画面和机身对不上，用户会以为点错了
                        if (focusBox.pointsOverlay) {
                            focusBox.setAfPoints(currentAfPoints(), afPoint)
                        }
                    }

                    // 取景模式只在这两个值里认，读不到就保持原样。
                    // ⚠️ 只在**取景进行中**同步：机身一旦退出取景就会把 0xD1A0 重置成 0（手持），
                    // 那时照抄机身值，会让"三脚架拍一张"变成"拍完自动变手持"。
                    if (isLiveViewRunning && (lvMode == 0 || lvMode == 1)) {
                        if (lvMode != currentLiveViewMode) {
                            currentLiveViewMode = lvMode
                            getSharedPreferences(AF_PREFS, Context.MODE_PRIVATE)
                                .edit().putInt(LV_MODE_KEY, lvMode).apply()
                            renderLiveViewMode(lvMode)
                        }
                    }

                    val modeStr = when (mode) {
                        1 -> "M"
                        2 -> "P"
                        3 -> "A"
                        4 -> "S"
                        else -> "--"
                    }
                    currentModeValue = mode
                    val apStr = if (aperture != -999999) "f/${aperture/100.0}" else "--"
                    val shStr = if (shutter != -999999) {
                        shutterMap[shutter] ?: getShutterString(shutter)
                    } else "--"
                    
                    val focalStr = if (focalLengthRaw != -999999 && focalLengthRaw > 0) "${focalLengthRaw / 100}mm" else "--"
                    
                    currentEvRaw = if (biasRaw != -999999) biasRaw.toShort().toInt() else -999999
                    val biasStr = if (biasRaw != -999999) {
                        val b = biasRaw.toShort() 
                        val ev = b / 1000.0
                        if (ev > 0) "+%.1f".format(ev) else "%.1f".format(ev)
                    } else "--"

                    val focusStr = when (focusModeRaw) {
                        1 -> "MF"
                        2, 0x8001, 0x8010 -> "AF-S"
                        3, 0x8002, 0x8011 -> "AF-C"
                        4, 0x8003, 0x8012 -> "AF-A"
                        0x8004 -> "AF-F"
                        -999999 -> "--"
                        else -> "0x${Integer.toHexString(focusModeRaw).uppercase()}"
                    }
                    
                    // 主页这颗芯片恒显手动档：自动 ISO 开着时 0x500F 读到的是机身
                    // 临时挑的值，按用户要求主页继续显示原来手动设的那一档
                    //（自动状态在 ISO 菜单里看「自动」块的高亮）。
                    val isoLadderNow = isoLadder()
                    isoButton.text = when {
                        currentIsoIndex in isoLadderNow.indices -> "${isoLadderNow[currentIsoIndex]}"
                        iso != -999999 && iso > 0 -> "$iso"
                        else -> "ISO"
                    }
                    apertureButton.text = apStr
                    shutterButton.text = shStr

                    // 设计稿把这几项做成取景左上角的一排芯片，不再堆在底部当一行文本
                    focusChip.text = focusStr
                    focalChip.text = focalStr
                    evChip.text = if (biasRaw != -999999) "$biasStr EV" else "-- EV"
                    renderModeChip(mode)
                    modeChip.text = when (mode) {
                        1 -> "手动曝光"
                        2 -> "程序自动"
                        3 -> "光圈优先"
                        4 -> "快门优先"
                        else -> "--"
                    }

                    if (supportedAps != null && supportedAps.isNotEmpty()) {
                        dynamicApertures = supportedAps.map { valCode ->
                            valCode to "f/${valCode / 100.0}"
                        }.toTypedArray()
                    }

                    try {
                        if (iso != -999999 && iso > 0) {
                            // 按**当前步长**的档位表定位：b1 改成 1/2 后机身不会再回到 125 这类 1/3 档
                            val newIsoIdx = isoLadderNow.indexOf(iso)
                            if (newIsoIdx >= 0) currentIsoIndex = newIsoIdx
                        }
                    } catch (e: Exception) {}
                    
                    try {
                        if (aperture != -999999 && aperture > 0) {
                            val newApIdx = dynamicApertures.indexOfFirst { it.first == aperture }
                            if (newApIdx >= 0) currentApIndex = newApIdx
                        }
                    } catch (e: Exception) {}
                    
                    try {
                        if (shutter != -999999) {
                            val newShIdx = shutters.indexOfFirst { it.first == shutter }
                            if (newShIdx >= 0) currentShIndex = newShIdx
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) { runOnUiThread { log("Prop Error: ${e.message}") } }
        }
    }

    private fun listPhotos() {
        thread {
            try {
                Thread.sleep(800)
                val handles = ptpConnection?.getObjectHandles()
                runOnUiThread {
                    if (handles != null) log("列出 ${handles.size} 个对象")
                    else log("照片列表读取失败")
                }
            } catch (e: Exception) { runOnUiThread { log("列举出错：${e.message}") } }
        }
    }

    private fun findAndConnect() {
        val nikonDevices = usbManager.deviceList.values.filter { it.vendorId == PtpConstants.USB_VENDOR_NIKON }
        if (nikonDevices.isEmpty()) {
            log("未检测到 Nikon 设备")
            return
        }

        // D300 机身「设定菜单 → USB」决定它枚举出来的 PID：
        //   0x041A = MTP/PTP，可以走 PTP 控制
        //   0x0419 = 大容量存储，Android 只当 U 盘，PTP 命令一律不响应
        val ptpDevice = nikonDevices.firstOrNull { it.productId == PtpConstants.USB_PID_D300_PTP }
        if (ptpDevice != null) {
            log("已找到 Nikon D300（MTP/PTP 模式）")
            connectButton.isEnabled = false
            requestPermissionOrConnect(ptpDevice)
            return
        }

        val mscDevice = nikonDevices.firstOrNull { it.productId == PtpConstants.USB_PID_D300_MSC }
        if (mscDevice != null) {
            log("相机当前是「大容量存储」模式，无法用 PTP 控制")
            log("请在机身：设定菜单 → USB → 改为 MTP/PTP，然后重新插拔")
            return
        }

        // 其他尼康机 / 未知 PID：不拦，交给 connectToDevice 的 interfaceClass == 6 兜底判断
        log("检测到 Nikon 设备（未知 PID 0x${Integer.toHexString(nikonDevices.first().productId).uppercase()}），尝试连接")
        connectButton.isEnabled = false
        requestPermissionOrConnect(nikonDevices.first())
    }

    private fun requestPermissionOrConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) connectToDevice(device)
        else {
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        log("正在初始化…")

        // 大容量存储模式下不要试图建立 PTP 连接：bulk 端点根本不是 PTP 的，
        // 强行 claim 只会得到一串超时和 0xA002 管道错误。
        if (device.productId == PtpConstants.USB_PID_D300_MSC) {
            log("相机处于「大容量存储」模式（PID 0x0419），已放弃连接")
            log("请在机身：设定菜单 → USB → 改为 MTP/PTP")
            connectButton.isEnabled = true
            return
        }

        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.find { it.interfaceClass == 6 }
        if (intf == null) { log("未找到 PTP 接口"); connectButton.isEnabled = true; return }
        
        ptpConnection?.close()
        val connection = usbManager.openDevice(device)
        if (connection == null) { log("USB 打开失败"); connectButton.isEnabled = true; return }

        ptpConnection = PtpUsbConnection(connection, intf) { msg -> runOnUiThread { log(msg) } }
        // 相册界面要复用这条连接（USB 设备只能打开一次，没法重建）
        PtpUsbConnection.activeInstance = ptpConnection
        ptpConnection?.eventHandler = { ev ->
            runOnUiThread {
                when (ev.code) {
                    PtpEvent.OBJECT_ADDED -> {
                        sessionShotCount++
                        updateShotCount()
                        // 记下句柄："拍完自动下载"靠它定位刚拍的这一张，
                        // 不用去猜"最新的那个 handle"（猜错会拉回别人的照片）
                        lastNewObjectHandle = ev.param
                        log("📷 照片已保存（对象 #${ev.param}）")
                    }
                    PtpEvent.CAPTURE_COMPLETE -> log("拍摄完成")
                    PtpEvent.PROP_CHANGED -> { /* 属性变更由轮询统一刷新 */ }
                }
            }
        }
        
        thread {
            try {
                if (ptpConnection?.openSession() == true) {
                    isConnected = true

                    val supportedShuttersD100 = ptpConnection?.getDevicePropSupportedValues(PtpConstants.PROP_NIKON_SHUTTER_SPEED)
                    if (supportedShuttersD100 != null && supportedShuttersD100.isNotEmpty()) {
                        activeShutterProp = PtpConstants.PROP_NIKON_SHUTTER_SPEED
                        shutterPropSize = 4
                        cameraSupportedShutters = supportedShuttersD100
                        runOnUiThread { log("Shutter: Using Nikon Prop (0xD100)") }
                    } else {
                        activeShutterProp = PtpConstants.PROP_EXPOSURE_TIME
                        shutterPropSize = 4
                        cameraSupportedShutters = ptpConnection?.getDevicePropSupportedValues(PtpConstants.PROP_EXPOSURE_TIME)
                        runOnUiThread { log("Shutter: Using Standard Prop (0x500D)") }
                    }

                    if (cameraSupportedShutters != null) {
                        val hexString = cameraSupportedShutters?.joinToString(", ") { "0x${Integer.toHexString(it).uppercase()}" }
                        Log.d("D300_PROP", "Supported Shutters: $hexString")

                        // 描述符给的档位顺序不一定规整，而且从来不带 B 门 / 闪光同步 ——
                        // 这里按"1/8000 → 30s 升序 + 这两档垫底"重排一次，和二级页看到的一致
                        val withSpecials = LinkedHashSet<Int>(cameraSupportedShutters!!.toList())
                        withSpecials.add(SHUTTER_BULB)
                        withSpecials.add(SHUTTER_FLASH)
                        val ordered = shuttersSorted(withSpecials)
                        cameraSupportedShutters = ordered.toIntArray()
                        shutterMap = ordered.associateWith { getShutterString(it) }
                        shutters = ordered.map { it to getShutterString(it) }.toTypedArray()
                    }

                    // 会话级前置条件：写 0xD10B RecordingMedia=1。
                    // 不补这一步，用户第一次点 LV 会先撞一次 0x9201 被拒，
                    // 再花几十秒扫候选前置命令才成功 —— 看起来就像按钮失灵。
                    ptpConnection?.primeLiveViewEntry()
                    // 把 App 里记住的对焦点数量同步给机身（机身可能停在上次另一档）
                    ptpConnection?.setAfPointCount(afPointCount51)

                    updateButtonStates()
                    runOnUiThread { log("就绪") }
                    val deviceInfo = ptpConnection?.readDeviceInfo()
                    deviceInfoCache = deviceInfo
                    // ★ 把机身自述支持的操作码打进日志：以后再遇到"这个按钮点了没反应"，
                    //  先看它到底认不认这条命令，而不是猜固件实现。（日志/ABD 都能捞这段）
                    ptpConnection?.logSupportedOperations { line -> runOnUiThread { log(line) } }
                    // 连上就看一眼机身时钟：差太多会让 EXIF 时间戳全错，事后没法补救
                    checkCameraClockDrift()
                    runOnUiThread {
                        if (deviceInfo != null && deviceInfo.model.isNotBlank()) {
                            // 走统一入口：机型名 + 尾部小箭头（点它开机身信息）
                            restoreStatusText()
                            updateProperties()
                            listPhotos()
                            startPolling()
                        } else {
                            statusText.text = "已连接（未读到机型）"
                            updateProperties()
                            startPolling()
                        }
                    }
                    // 新加的几组取值：连上就读一遍，别等用户点进二级页才知道支不支持
                    refreshExposureExtras()
                    refreshDeviceAndStorage()
                } else { 
                    runOnUiThread { log("PTP 会话建立失败"); connectButton.isEnabled = true } 
                }
            } catch (e: Exception) { 
                runOnUiThread { log("连接出错：${e.message}"); connectButton.isEnabled = true } 
            }
        }
    }

    private fun disconnect() {
        // 断连前先把取景退干净：USB 一关，机身就再也收不到 0x9202，
        // 会一直卡在取景状态（反光板抬起），只能靠关电源退出。
        val wasLiveView = isLiveViewRunning
        isLiveViewRunning = false
        isLiveViewStarting = false
        isPollingActive = false
        // 断连后相册界面不能再拿着旧连接发命令
        PtpUsbConnection.activeInstance = null
        thread {
            if (wasLiveView) stopLiveViewBlocking()
            ptpConnection?.close()
            ptpConnection = null
            isConnected = false
            // 快门数是"这台机身"的属性，换机/重连都要重新读
            shutterCount = -1
            deviceInfoCache = null
            updateButtonStates()
            runOnUiThread {
                restoreStatusText()
                batteryText.text = "--%"
                resetLiveViewButton()
                log("已断开连接")
            }
        }
    }

    /** 只负责下发 0x500F，线程与收尾刷新交给调用方（关自动 + 写 ISO 要串成一次操作） */
    private fun setIsoInternal(value: Int) {
        log("正在设置 ISO $value…")
        val success = ptpConnection?.setDevicePropValue(
            PtpConstants.PROP_EXPOSURE_INDEX, value, size = 2, logDesc = "设置 ISO $value"
        ) ?: false
        runOnUiThread { log("设置 ISO：${if (success) "成功" else "失败"}") }
    }

    private fun setAperture(value: Int, name: String) {
        thread {
            try {
                log("正在设置光圈 $name…")
                val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_F_NUMBER, value, size = 2, logDesc = "设置光圈 $name") ?: false
                runOnUiThread { log("设置光圈：${if (success) "成功" else "失败"}") }
            } catch (e: Exception) {
                runOnUiThread { log("设置光圈出错：${e.message}") }
            }
            Thread.sleep(300)
            updateProperties()
        }
    }

    private fun setShutter(value: Int, name: String) {
        thread {
            try {
                log("正在设置快门 $name…")
                
                val success = ptpConnection?.setDevicePropValue(activeShutterProp, value, size = shutterPropSize, logDesc = "设置快门 $name（值 $value）") ?: false
                
                if (success) {
                    Thread.sleep(100)
                    ptpConnection?.deviceReady()
                }

                runOnUiThread { log("设置快门：${if (success) "成功" else "失败"}") }
            } catch (e: Exception) {
                runOnUiThread { log("设置快门出错：${e.message}") }
            }
            Thread.sleep(400)
            updateProperties()
        }
    }

    private fun setWhiteBalance(value: Int, name: String) {
        thread {
            try {
                log("正在设置白平衡 $name…")
                val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_WHITE_BALANCE, value, size = 2) ?: false
                if (success) {
                    ptpConnection?.deviceReady()
                    // 参数页那一行当场跟上，别等下一轮轮询
                    runOnUiThread { if (::wbRow.isInitialized) wbRow.value.text = wbLabel(value) }
                }
                runOnUiThread { log("设置白平衡：${if (success) "成功" else "失败"}") }
            } catch (e: Exception) {
                runOnUiThread { log("设置白平衡出错：${e.message}") }
            }
            Thread.sleep(400)
            updateProperties()
        }
    }

    // =====================================================================
    // 单张拍摄：按钮 / 自拍延时 / 间隔拍摄共用同一条流程
    // =====================================================================

    /**
     * 拍一张：需要时先退出取景 → 自动对焦 → 释放快门 → 等机身回写。
     *
     * 必须在后台线程调用（内部有 Thread.sleep 与 USB 阻塞操作）。
     * 调用方负责 isPollingPaused 的置位与复位 —— 这里不碰轮询状态，
     * 否则间隔拍摄里每张都置一次会把用户的暂停意图冲掉。
     *
     * @return true = 机身回 0x2001 拍摄成功
     */
    private fun shootOnce(applySelfTimer: Boolean = true): Boolean {
        if (!isConnected) return false

        // 上一次「停止」留下的中止信号在这里清掉 —— 用户既然又按了拍摄，就是新的意图。
        stopAllRequested = false
        ptpConnection?.abortRequested = false

        // 自拍延时：**走机内设定**（2026-10-26 改）。
        // 以前是 App 自己数秒再发快门 —— 数的时候机身并不知道要拍照，抢在倒计时末尾
        // 拧对焦环、换构图都不算数，而且不会响机身的提示音。
        // 现在写 0xD063（时长）+ 0x5013=0x8011（自拍模式），发一次快门后
        // 由机身自己倒计时、自己释放；机内不接受时再退回 App 内倒计时。
        // 间隔拍摄时只在第一张前挂（之后每张的间隔由间隔时长控制，
        // 再叠一次自拍会让实际间隔比设定值多出好几秒）
        var selfTimerArmed = false
        if (applySelfTimer && selfTimerSec > 0) {
            selfTimerArmed = armCameraSelfTimer()
            if (!selfTimerArmed) runCountdown(selfTimerSec)
        }

        // 拍前是否在取景：拍完要自动恢复回来（见函数末尾）
        val wasInLiveView = isLiveViewRunning

        // ★★ 必须在**退出取景之前**问机身锁没锁焦（0xD104 AFLockStatus）。
        //
        // 两个坑都在这里：
        //   1) 三脚架模式点画面走的是 0x9205，机身自己跑对比度 AF、自己响提示音，
        //      这条路不经过 afDrive()，lvAfLocked 一直是 false；
        //   2) 把这段放在 stopLiveViewBlocking() 之后就完全失效 —— 那时
        //      isLiveViewRunning 已经是 false，条件直接不成立（上一版就是这么白写的）。
        if (!lvAfLocked && wasInLiveView) {
            val lock = try {
                ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_AF_LOCK_STATUS) ?: -1
            } catch (_: Exception) {
                -1
            }
            if (lock == 1) {
                lvAfLocked = true
                runOnUiThread { log("机身已锁焦（0xD104=1）—— 记为已合焦") }
            } else if (wasInLiveView && currentLiveViewMode == PtpConstants.LIVE_VIEW_MODE_TRIPOD) {
                // 三脚架取景中：画面对不上对，用户自己看得见（还可能拧了对焦环 / 用了 ▲▼）。
                // 0xD104=0 只是"机身没报锁焦"，不等于没对上 —— 直接按已对焦处理。
                lvAfLocked = true
                runOnUiThread { log("三脚架取景中（0xD104=$lock 不影响判定），按已对焦处理") }
            } else {
                runOnUiThread { log("机身此刻未报锁焦（0xD104=$lock）") }
            }
        }

        // ★ 取景里已经合过焦 → 退出取景后直接拍，不再走一遍 AF。
        // 手持和三脚架都算：两者都是"用户在取景里按了对焦按钮、机身确认合焦"，
        // 镜头位置已经定了；退出 0x9202 只是让反光板落下，不会动对焦。
        // 必须在这里取：下面 stopLiveViewBlocking() 一执行 isLiveViewRunning 就变 false。
        // ★★ 三脚架模式 + 拍前在取景 → **一律不走 AF**（不再依赖合焦标记/锁焦属性）。
        //
        // 原因：取景里的对焦是对比度 AF，用户已经在画面上把焦点调好了
        // （点画面对焦、或者用 ▲▼ 微调）。拍摄时若再发 0x90C1，走的是**相位 AF**，
        // 它会重新对焦、把辛苦调好的焦点顶掉 —— 用户看到的就是
        // "一按拍摄，屏幕黑掉，重新合一次焦，才真正拍"。
        //
        // 前几版试图用 lvAfLocked / 0xD104 去"判断到底合没合焦"，
        // 结果每次都卡在某个时序细节上（0x9205 那条路不记账、读取位置在退取景之后……）。
        // 这里改成规则优先：三脚架取景中，拍摄就是"按下去就拍"，不再问东问西。
        // ★ 只要**拍前在取景里**，就不再做那一次 AF —— 不再看模式、也不看合焦标记。
        //
        // 走过太多弯路：先是看 lvAfLocked（0x9205 那条路不置位），
        // 再去看 0xD104（手动对焦/刚拍完时读成 0），又去判断 currentLiveViewMode
        // （机身会在退出 0x9202 时把它重置成手持）—— 每一层都可能判错，
        // 一旦判错就会去发 0x90C1，而这台机器在取景后退反光板的瞬间经常回 0x2019，
        // 于是"对焦失败，照片也拍不出来"。
        //
        // 取景画面就在用户眼前：对没对上他自己看得见（还能拧对焦环）。
        // 拍摄时再补一次相位 AF，不但多余，还会把已经调好的焦点顶掉。
        // 所以规则简化成一句：**取景中按快门 = 拍，不对焦**。
        val lvWasShowing = wasInLiveView
        val lvAlreadyFocused = lvAfLocked || lvWasShowing

        // 对焦模式（AF-S / AF-C / … 还是 MF）：下面几处判定都要用，提前读一次
        val focusMode = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCUS_MODE) ?: -1
        val afModes = listOf(2, 3, 4, 0x8001, 0x8002, 0x8003, 0x8010, 0x8011, 0x8012)
        val needsAf = focusMode in afModes

        // 拍照前确保记录媒介是**存储卡**：取景若是异常中断（掉线、切页面），
        // 它可能停在"机身缓存"那一档，那样这一张不会进卡。
        // 先读再写，本来已是存储卡就只读一次，很便宜，所以每次拍摄都兜一遍。
        // （放在取景内拍摄之前：取景内拍摄也走这一档。）
        ptpConnection?.ensureRecordToCard()

        // =====================================================================
        // ★★★ 取景中拍摄：直接在取景里释放快门 —— 不退出取景、不再走相位 AF ★★★
        //
        // 用户现场反馈：三脚架模式画面明明已经合焦，点拍摄却"屏幕一黑、反光板落下、
        // 光学取景那套 AF 再对一次，对不上就拍不成"。
        //   · 黑屏 = 我们发的 0x9202（退出取景）；
        //   · 二次对焦 = 退出后机身回到 51 点相位 AF，又跑了一遍（甚至被 0x9205 挪到
        //     的点不在合法点里，必然对不上）。
        //
        // libgphoto2 对尼康的处理就是标准答案（camlibs/ptp2/library.c · camera_nikon_capture）：
        //   「if in liveview mode, we have to run non-af capture」→ `if (inliveview) af = 0;`
        // 取景里一律用**不带 AF** 的 0x90C0：既不发 0x9202，也不发 0x90C1；
        // 曝光由机身自己用快门帘完成，反光板和对焦都不动。
        // =====================================================================
        var shotInLiveView = false
        // 方案 B 用：临时切 MF 之前的对焦模式原值 + 该属性的写入宽度（拍完要按原样切回）
        var mfOriginal: Int? = null
        var mfPropSize = 2
        if (wasInLiveView) {
            // ★★ 0x9207（取景内落卡）已实测停用 —— **千万别再打开**。
            //
            // 真机日志（10-22 10:05）：`Triggering Capture (0x9207)` 发出后 4ms 就 TIMEOUT，
            // 这不是"机身慢"，是**机身把 USB 端点 STALL 了**：紧接着的 0x9202 超时、
            // 0x100E 连续 0x2002 → TIMEOUT（等了 67 秒），整条链路全废。
            // 不发 0x9207 的那些版本里 0x100E 是稳定落卡的，一发就崩 —— 副作用远大于收益。
            // 所以取景里按拍摄 = 老老实实退取景 + 0x100E（下面那段）。
            runOnUiThread {
                log("取景中拍摄：本机不支持取景内落卡（0x9207 会 STALL 端点），改走退出取景 + 0x100E")
                setFocusStatus(true)
            }
            // 读帧循环让路：它每 70ms 就发一次 0x9203，会和退出命令抢同一条管道
            lvFramesPaused = true
            try {
                Thread.sleep(200) // 让正在进行的那一帧收尾
            } finally {
                lvFramesPaused = false
            }
        }

        if (isLiveViewRunning && !shotInLiveView) {
            val modeBeforeShoot = currentLiveViewMode
            runOnUiThread { log("拍摄：正在退出实时取景…") }
            // 拍照前要把取景退干净：等读帧循环停下再发 0x9202，
            // 否则两条线程抢同一条 USB 管道，机身根本收不到退出命令
            stopLiveViewBlocking()
            // ★ 别死等固定 2.5 秒。日志证明：退出取景 + 恢复模式之后立刻发快门，
            // 机身还会回 0x2002（DeviceBusy）；死等既可能不够、也可能白等。
            // 改成轮询 deviceReady，就绪即走，最多等 5 秒。
            var readyMs = 0
            while (readyMs < 5000) {
                if (stopAllRequested) {
                    runOnUiThread { log("拍摄已中止（用户停止），不再释放快门") }
                    return false
                }
                if (ptpConnection?.deviceReady() == true) break
                // 120ms 一探（原来 250ms）：退出取景后机身多半在半秒内就绪，
                // 粗步进只是让用户白等；真慢的时候轮数够，仍然等满 5 秒。
                Thread.sleep(120)
                readyMs += 120
            }
            Thread.sleep(200) // 反光板最后一点余量（原来是 300ms，配合更密的探测收回 100ms）

            // ★★ 临时把对焦模式切成 MF（0x500A=1）。
            //
            // 这才是"不再二次对焦"的正解：我们发不发 0x90C1 只管得了自己那一步，
            // 机身在 0x100E 的释放序列里还会按自己的 AF 设定再对一次（本机 AF-A，必然对）。
            // 切成 MF 之后它就没有"要对焦"这回事了，快门直接释放。
            // 只在"当前确实是 AF"时切，拍完立刻切回原值（下面 [restoreFocusModeAfterShot]）。
            if (needsAf) {
                val sw = switchFocusModeToMf(focusMode)
                if (sw.first) {
                    mfOriginal = sw.second
                    mfPropSize = sw.third
                    // MF 下机身不会对焦 → 焦点位置无所谓，跳过复位
                } else {
                    // ★ 这里**不再**把对焦点复位到中央（0xD108=1）。
                    //
                    // 三脚架取景是对比度 AF，焦点是用户在画面里点出来的那一点；
                    // 复位到中央等于把用户选的构图焦点扔掉，还多一条命令占通道。
                    // 退出取景后机身若仍要按自己的 AF 设定对一次，那是机身行为，
                    // 我们不再替它"选点"—— 想完全不对焦就把机身/镜头拨到 M。
                    runOnUiThread { log("临时切 MF 未成功 —— 机身释放时仍会自动对焦（不再复位对焦点）") }
                }
            }

            // ★ 三脚架模式下拍完不能变手持：
            // 机身退出 0x9202 后会把取景模式（0xD1A0）重置成 0，下次开取景就是手持，
            // 相机的 AF 方式也跟着从"对比度检测"变成"相位检测" —— 用户明明没动过设置。
            // 所以按拍摄前的模式写回去（三脚架才需要，手持本来就是 0）。
            if (modeBeforeShoot == PtpConstants.LIVE_VIEW_MODE_TRIPOD) {
                val back = ptpConnection?.setLiveViewMode(PtpConstants.LIVE_VIEW_MODE_TRIPOD) ?: false
                currentLiveViewMode = PtpConstants.LIVE_VIEW_MODE_TRIPOD
                runOnUiThread {
                    renderLiveViewMode(PtpConstants.LIVE_VIEW_MODE_TRIPOD)
                    log(if (back) "已恢复取景模式：三脚架" else "取景模式恢复失败（仍是三脚架，下次开取景会按它写）")
                }
            }
        }

        // 手动对焦时无所谓“合焦”，直接走 0x90C0，省掉一次无效 AF
        // 一眼看清这次为什么对焦/为什么跳过 —— 之前全是间接推断，来回猜了好几轮
        runOnUiThread {
            log(
                "拍摄前判定：拍前取景=$wasInLiveView 模式=${if (currentLiveViewMode == PtpConstants.LIVE_VIEW_MODE_TRIPOD) "三脚架" else "手持"} " +
                    "合焦标记=$lvAfLocked 对焦模式=$focusMode 需要AF=$needsAf 跳过AF=$lvAlreadyFocused"
            )
        }

        var afReady = !needsAf || lvAlreadyFocused
        if (!needsAf) runOnUiThread {
            focusStatusDot.setBackgroundResource(R.drawable.bg_status_dot_off)
            focusStatusText.text = "手动对焦"
        }
        if (lvAlreadyFocused) {
            // ★ 这里**不**清 lvAfLocked。
            // 拍摄过程（0x9202 退反光板 → 0x100E → 反光板复位）自始至终没有动过镜头，
            // 所以合焦状态对下一张同样有效。
            // 以前"用过就清"，结果第二张起又要重新 AF ——
            // 用户感受到的就是"每次按快门都要先关屏幕再对一遍焦"。
            // 真正该让标记失效的是：开取景（重新进画面）、移动 AF 框（焦点换了）。
            runOnUiThread {
                log(
                    if (lvWasShowing && !lvAfLocked)
                        "取景中拍摄：跳过二次对焦（焦点已在取景画面里调好），直接释放快门"
                    else
                        "已合焦，跳过二次对焦，直接释放快门"
                )
                setFocusStatus(true)
            }
        }

        if (needsAf && !lvAlreadyFocused) {
            runOnUiThread { log("自动对焦中…") }
            val t0 = System.currentTimeMillis()
            afReady = ptpConnection?.afDrive() ?: false
            val afCost = System.currentTimeMillis() - t0
            runOnUiThread { setFocusStatus(afReady, afCost) }
            if (stopAllRequested) {
                runOnUiThread { log("拍摄已中止（用户停止），不再继续对焦/拍摄") }
                return false
            }
            if (afReady) {
                runOnUiThread { log("对焦完成，等待机身就绪…") }
                // 150ms 一探（原来 400ms）：对焦刚结束时机身通常只需一两百毫秒就绪，
                // 400ms 的步进等于每次都白等小半秒才敢按快门。轮数按 2 秒预算配。
                for (i in 1..13) {
                    if (stopAllRequested) {
                        runOnUiThread { log("拍摄已中止（用户停止）") }
                        return false
                    }
                    if (ptpConnection?.deviceReady() == true) break
                    runOnUiThread { log("机身忙，等待中…") }
                    Thread.sleep(150)
                }
                Thread.sleep(150)
            } else {
                // ★ AF 失败后**不能立刻按快门**。
                // 机身刚跑完一次没成功的 AF，此刻通常还在 0x2019（对不上焦）状态里，
                // 紧接着发的快门会被同一个理由拒掉 —— 用户看到的就是
                // "对焦失败，照片也拍不出来"。先等它回到就绪，再按。
                runOnUiThread { log("AF 未合焦，等机身就绪后再释放快门") }
                for (i in 1..11) {
                    if (stopAllRequested) {
                        runOnUiThread { log("拍摄已中止（用户停止），不再释放快门") }
                        return false
                    }
                    if (ptpConnection?.deviceReady() == true) break
                    Thread.sleep(150)
                }
                Thread.sleep(200)
            }
        }

        // ★ 取景内已经拍完了，下面的"0x100E"整段就跳过。
        var responseCode = PtpConstants.RESP_OK
        if (!shotInLiveView) {
            if (mirrorUpEnabled) {
                // ---- 反光板预升：连按两次快门 ----
                // 释放模式由 mirrorUpCapture 自己切到 Mup（0x5013=0x8012），不再要求手动拨盘；
                // 机身不接受时才退回"用户手动转 Mup"的老办法，错误提示里会说清是哪一种。
                // 若正在取景，反光板本就是抬起的，这里先落下来再走流程。
                if (isLiveViewRunning) {
                    runOnUiThread { log("反光板预升：先退出取景落下反光板…") }
                    lvFramesPaused = true
                    try {
                        Thread.sleep(200)
                    } finally {
                        lvFramesPaused = false
                    }
                    stopLiveViewBlocking()
                }
                lastNewObjectHandle = -1
                val res = mirrorUpCapture()
                responseCode = if (res.exposed) PtpConstants.RESP_OK else res.secondCode
                if (!res.raised) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "反光板预升失败：机身拒绝了第一次快门（失败原因见日志页）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                // 先把"新对象"清空：自动下载靠事件里的句柄定位刚拍的这张
                lastNewObjectHandle = -1
                log("释放快门…" + if (mfOriginal != null) "（MF 下直接释放，机身不再对焦）" else "")
                // ★ 只发一次。拍不成就拍不成，不再等 1.5 秒重来一遍 ——
                // 重试只会让"这一下没拍到"变成"卡十几秒还是没拍到"，
                // 期间 USB 通道被占满，取景画面也跟着僵住。
                responseCode = ptpConnection?.capture(afAlreadyLocked = afReady) ?: -1
            }
        }

        // 不管拍成没拍成，对焦模式都必须切回用户原来的设定 ——
        // 留在 MF 上，用户下次正常拍照会发现"对不了焦了"。
        mfOriginal?.let { restoreFocusModeAfterShot(it, mfPropSize) }

        val success = responseCode == PtpConstants.RESP_OK
        runOnUiThread {
            if (success) {
                log("拍摄成功")
            } else if (responseCode == PtpConstants.RESP_NIKON_HARDWARE_ERROR) {
                log("拍摄失败：未合焦（机身被占用）")
                Toast.makeText(this@MainActivity, "拍摄失败：未合焦（机身被占用）", Toast.LENGTH_LONG).show()
            } else {
                log("拍摄失败（检查机身忙/合焦）")
                Toast.makeText(this@MainActivity, "拍摄被拒：机身忙或未合焦", Toast.LENGTH_SHORT).show()
                showTransientStatus("拍摄被拒：机身忙或未合焦", error = true)
            }
        }

        // 机身写卡需要时间，紧接着发下一条命令容易撞上 DeviceBusy。
        // 走机内自拍时，快门响应只代表"开始倒计时"，真正的曝光在 N 秒之后，
        // 所以要按自拍时长多等，否则后面"重开会话列照片"会跑在照片落卡之前。
        if (success && !stopAllRequested) {
            Thread.sleep(if (selfTimerArmed) selfTimerSec * 1000L + 3000 else 3000)
        }

        // 自拍拍完把释放模式放回去，别让机身一直停在自拍挡
        if (selfTimerArmed) disarmCameraSelfTimer()

        // ★ 拍完把会话重开一次，让机身重新列一遍照片。
        //
        // D300 的对象列表是**按会话缓存**的，而且它自己写卡也有延迟：
        // 拍完当场列举、甚至马上重开会话，拿到的往往还是旧列表 ——
        // 用户切到相册看不到刚拍的那张，就会认为"按了快门但没写卡"（实际卡上有）。
        // 这里多等一拍再重开，相册那边进去就是新的。
        // 间隔拍摄不做（每张都重开会把间隔时间拖乱），它在整组结束时统一刷。
        // ★ 只有"拍完不打算回取景"时才重开会话刷列表。
        // 要回取景的话跳过这一步：重开会话会把机身打回普通会话状态，
        // 紧接着的 0x9201 十有八九被回 0x1015（当前拍摄条件不支持取景）——
        // 实测就是这样把恢复取景顶掉的。相册页面自己进页会 openSession，不愁列表旧。
        if (success && !isIntervalRunning && !wasInLiveView) {
            try {
                Thread.sleep(1200)
                if (reopenSessionForFreshList()) {
                    val n = ptpConnection?.collectPhotoHandles()?.size ?: 0
                    log("已刷新照片列表：卡上共 $n 个对象（刚拍的这张在相册里可见）")
                }
            } catch (e: Exception) {
                log("拍完刷新列表失败：${e.message}")
            }
        }

        // ★ 拍完**不再自动重开取景**（明确要求：拍完就停在普通拍摄界面）。
        //
        // 只有"取景内拍完"这一条路需要收尾：机身可能还留在取景状态，
        // 而 App 这边读帧循环也还在跑、按钮还是"取景中"的样式。
        // 所以这里退一次取景，把状态和界面都收干净 —— 想再看画面自己点 LV 就行。
        // 走"退出取景 + 0x100E"那条路的，前面已经退过了（isLiveViewRunning 已是 false）。
        if (success && wasInLiveView && isLiveViewRunning && !isIntervalRunning) {
            runOnUiThread { log("拍摄完成，正在退出实时取景…") }
            // 刚拍完机身还在写卡，立刻发 0x9202 容易被回 DeviceBusy，先缓一拍
            Thread.sleep(800)
            stopLiveViewBlocking()
        }
        return success
    }

    /**
     * 临时把机身对焦模式切成 **MF**（0x500A=1），让 0x100E 释放快门时**不再自动对焦**。
     *
     * 背景：我们管得了自己发不发 0x90C1，管不了机身 —— 0x100E 触发的是机身自己的
     * 释放序列，AF-A / AF-C 下它一定会再对一次焦。想让它不对焦，只有把对焦模式改成 MF。
     * 代价是反光板仍会落下（机械上躲不掉），但"合焦了又重对一遍、对不上就拍不成"没有了。
     *
     * @return Triple(是否切成功, 原值, 该属性的写入字节宽度)；失败时 first=false
     */
    private fun switchFocusModeToMf(currentMode: Int): Triple<Boolean, Int, Int> {
        val conn = ptpConnection ?: return Triple(false, currentMode, 2)

        // 先问机身这个属性到底多宽、能不能写 —— 宽度猜错写不进去（ISO/光圈都栽过）
        val desc = try {
            conn.readPropDesc(PtpConstants.PROP_FOCUS_MODE)
        } catch (_: Exception) {
            null
        }
        val size = when (desc?.dataType) {
            0x0001, 0x0002 -> 1
            0x0003, 0x0004 -> 2
            0x0005, 0x0006 -> 4
            else -> 2
        }
        if (desc != null && !desc.writable) {
            runOnUiThread { log("对焦模式 0x500A 机身声明为只读，无法临时切 MF") }
            return Triple(false, currentMode, size)
        }

        val ok = conn.setDevicePropValue(
            PtpConstants.PROP_FOCUS_MODE,
            PtpConstants.FOCUS_MODE_MF,
            size = size,
            logDesc = "临时切 MF（让 0x100E 释放时不再自动对焦）"
        )
        if (!ok) {
            runOnUiThread { log("临时切 MF 写入失败") }
            return Triple(false, currentMode, size)
        }

        // 写回确认：机身"收了"不等于"改了"，读回来不是 1 就当没成
        val now = try {
            conn.getDevicePropValue(PtpConstants.PROP_FOCUS_MODE)
        } catch (_: Exception) {
            -1
        }
        if (now != PtpConstants.FOCUS_MODE_MF) {
            runOnUiThread { log("临时切 MF 未生效（读回 0x${Integer.toHexString(now).uppercase()}）") }
            return Triple(false, currentMode, size)
        }
        runOnUiThread { log("已临时切为 MF（原模式 0x${Integer.toHexString(currentMode).uppercase()}），拍完切回") }
        return Triple(true, currentMode, size)
    }

    /**
     * 把对焦模式切回用户原来的设定。
     *
     * 必须切回：留在 MF 上，用户下次正常拍照会发现"怎么对不了焦了"。
     */
    private fun restoreFocusModeAfterShot(original: Int, size: Int) {
        val conn = ptpConnection ?: return
        runOnUiThread {
            log("正在把对焦模式切回 0x${Integer.toHexString(original).uppercase()}…")
        }
        val ok = conn.setDevicePropValue(
            PtpConstants.PROP_FOCUS_MODE, original, size = size,
            logDesc = "恢复对焦模式"
        )
        runOnUiThread {
            log(if (ok) "对焦模式已切回" else "对焦模式切回失败，请在机身上确认")
        }
    }

    /**
     * 记录媒介（0xD10B）显示名。
     *
     * 实测结论只到这一步：**写 1 时照片确实落卡**（DSC_4242→4246 连续编号为证），
     * 写 0 的那几条路都没拍成，所以 0 到底代表什么没有确切证据 ——
     * 界面上就按"存储卡 / 机身缓存"两档显示，不去编卡槽编号。
     */
    private fun mediaLabel(v: Long): String = when (v) {
        1L -> "存储卡"
        0L -> "机身缓存"
        else -> "值 $v"
    }

    /**
     * 自拍倒计时：把数字铺在取景框中间（那个占位标签平时是 GONE 的，
     * 这里临时借用，倒计时结束再收回去）。
     */
    /** 挂自拍之前机身的释放模式，拍完照它还原；-999999 = 没挂过 / 读不到 */
    private var selfTimerOrigMode = -999999

    /**
     * 把自拍延时交给机身：写 0xD063（时长）+ 0x5013=0x8011（自拍模式）。
     *
     * 成功后必须**只发一次**快门 —— 机身在倒计时结束时自己释放，
     * App 再补一次就变成连拍两张。拍完调 [disarmCameraSelfTimer] 还原释放模式。
     *
     * @return true = 机身已经进入自拍待发状态；false = 没走成，调用方退回 App 内倒计时
     */
    private fun armCameraSelfTimer(): Boolean {
        val conn = ptpConnection ?: return false
        val origMode = conn.getStillCaptureMode()
        val origDelay = conn.getSelfTimerDelay()
        if (!conn.setSelfTimerDelay(selfTimerSec)) {
            runOnUiThread { log("机身不接受自拍时长（0xD063），改用 App 内倒计时") }
            return false
        }
        if (!conn.setStillCaptureMode(PtpConstants.STILL_CAPTURE_SELF_TIMER)) {
            // ★ 回滚：自拍模式没切进去，机身 c3 却已经被改成 App 的秒数了。
            //   留着它，用户下次用手按机身快门会莫名其妙多等几秒。
            if (origDelay in 0..20) conn.setSelfTimerDelay(origDelay)
            runOnUiThread { log("机身不接受自拍模式（0x5013=0x8011），改用 App 内倒计时") }
            return false
        }
        selfTimerOrigMode = origMode
        runOnUiThread {
            log("自拍 ${selfTimerSec} 秒由机身执行（0xD063 + 0x5013=0x8011），倒计时结束机身自己释放")
            showTransientStatus("机身自拍倒计时 ${selfTimerSec} 秒…")
        }
        return true
    }

    /** 自拍拍完把释放模式放回去（机身不该一直停在自拍挡） */
    private fun disarmCameraSelfTimer() {
        val conn = ptpConnection ?: return
        val back = selfTimerOrigMode
        selfTimerOrigMode = -999999
        if (back < 0) return
        if (!conn.setStillCaptureMode(back)) {
            runOnUiThread {
                log("恢复机身释放模式失败（原值 0x${Integer.toHexString(back).uppercase()}）")
                Toast.makeText(
                    this@MainActivity,
                    "释放模式没能自动切回（机身停在自拍挡）—— 请在机身上把释放模式拨回去",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun runCountdown(seconds: Int) {
        for (s in seconds downTo 1) {
            if (!isConnected) break
            runOnUiThread {
                liveViewPlaceholder.text = s.toString()
                liveViewPlaceholder.textSize = 64f
                liveViewPlaceholder.visibility = View.VISIBLE
            }
            Thread.sleep(1000)
        }
        runOnUiThread {
            liveViewPlaceholder.visibility = View.GONE
            liveViewPlaceholder.textSize = 14f
            liveViewPlaceholder.text = "正在开启取景…"
        }
    }

    // =====================================================================
    // 拍完自动下载：把原图拉回手机相册
    // =====================================================================

    /**
     * 下完本轮新拍的照片。
     *
     * 为什么不是"拍完查最新句柄"：机身**按会话缓存照片列表**，拍完当场列举只有旧文件；
     * 重开会话能刷新，但刷新出来的列表是"重开那一刻"的快照 —— 重开后拍的那张又看不见了。
     * 所以必须**拍前记下已有句柄基线**，拍完重开、取差集，才知道哪几张是这轮新拍的。
     *
     * @param known 已知句柄集合（会在本方法里更新成本轮拍完后的全集）
     */
    private fun downloadNewShots(known: MutableSet<Int>) {
        if (!reopenSessionForFreshList()) {
            log("自动下载：重开会话失败，本次跳过")
            return
        }
        val handles = ptpConnection?.collectPhotoHandles()?.toSet() ?: return
        val fresh = handles.filter { it !in known }.sorted()
        known.clear()
        known.addAll(handles)
        if (fresh.isEmpty()) {
            log("自动下载：重开后没发现新照片（机身可能还没写完卡）")
            return
        }
        for (h in fresh) downloadByHandle(h)
    }

    /**
     * 关掉当前会话再重开，让机身把照片列表重新列一遍。
     *
     * 这是"拍完自动下载"能看到新照片的唯一办法（机身不推事件、也不在会话内刷新列表）。
     * 重开后要补一次取景前置（0xD10B），否则下次点 LV 会先吃一个 0xA004。
     */
    private fun reopenSessionForFreshList(): Boolean {
        val conn = ptpConnection ?: return false
        if (!isConnected) return false
        val wasPolling = isPollingActive
        isPollingActive = false      // 先让轮询线程退出去，别抢通道
        Thread.sleep(300)
        val ok = try {
            conn.openSession()       // openSession 内部会先 CloseSession 再重开
        } catch (e: Exception) {
            log("重开会话出错：${e.message}")
            false
        }
        if (!ok) {
            log("重开会话失败")
            if (wasPolling) startPolling()
            return false
        }
        conn.primeLiveViewEntry()
        if (wasPolling) startPolling()
        return true
    }

    /** 按句柄取原文件并写进手机相册 */
    private fun downloadByHandle(handle: Int) {
        val info = ptpConnection?.getObjectInfo(handle)
        if (info == null) {
            log("自动下载：读对象信息失败（句柄 #$handle）")
            return
        }
        if (info.isRaw && !autoDownloadRaw) {
            log("自动下载：${info.filename} 是 RAW，已跳过（可在「拍摄流程」里打开含 RAW）")
            return
        }
        log("自动下载：${info.filename}（${info.byteSize / 1024} KB）…")
        val bytes = ptpConnection?.getObject(handle, info.byteSize)
        if (bytes == null) {
            log("自动下载：读取原文件失败")
            return
        }
        val name = info.filename.ifBlank { "D300_${System.currentTimeMillis()}.jpg" }
        val ok = saveToPhoneGallery(name, bytes, info.isJpeg)
        log(if (ok) "已保存到 相册/D300/$name" else "保存到手机失败：$name")
    }

    /**
     * 写进系统相册的 Pictures/D300。跟相册页「保存」按钮同一套写法：
     * MediaStore + IS_PENDING，Android 10 之后不需要任何存储权限。
     */
    private fun saveToPhoneGallery(name: String, bytes: ByteArray, isJpeg: Boolean): Boolean {
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, if (isJpeg) "image/jpeg" else "image/x-nikon-nef")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/D300")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            log("保存照片出错：${e.message}")
            false
        }
    }

    // =====================================================================
    // 拍摄流程（自拍延时 / 间隔拍摄 / 拍完自动下载）
    // =====================================================================

    /** 自拍延时的可选秒数：0 = 关（整机几个常见档，不去身上一一对应也没意义） */
    private val SELF_TIMER_OPTIONS = listOf(0, 2, 5, 10, 20)
    private fun selfTimerLabel(sec: Int) = if (sec == 0) "关闭" else "$sec 秒"
    /** 当前自拍延时在 [SELF_TIMER_OPTIONS] 里的位置（-1 = 没落在预设上） */
    private fun selfTimerSecIndex() = SELF_TIMER_OPTIONS.indexOf(selfTimerSec)

    private fun refreshWorkflowRows() {
        // 这几行都是参数页建起来的（惰性 ← Preferences 恢复早于界面），没建成就别碰
        if (!::intervalRow.isInitialized) return
        selfTimerGrid?.select(selfTimerSecIndex())
        intervalRow.value.text = "$intervalCount 张 · 每 ${intervalSec} 秒"
        autoDownloadRow.value.text = when {
            !autoDownload -> "关闭"
            autoDownloadRaw -> "开 · JPEG + NEF"
            else -> "开 · 仅 JPEG"
        }
        if (::mirrorUpRow.isInitialized) {
            mirrorUpRow.value.text = if (mirrorUpEnabled) "开 · 等 ${mirrorUpDelaySec} 秒" else "关闭"
        }
        if (::focusStackRow.isInitialized) {
            focusStackRow.value.text = "$focusStackCount 张 · 每步 $focusStackStep 点 · " +
                (if (focusStackNearFirst) "近→远" else "远→近")
        }
    }

    private fun saveWorkflowSettings() {
        getSharedPreferences("workflow", Context.MODE_PRIVATE).edit().apply {
            putInt("selfTimer", selfTimerSec)
            putInt("intervalCount", intervalCount)
            putInt("intervalSec", intervalSec)
            putBoolean("autoDownload", autoDownload)
            putBoolean("autoDownloadRaw", autoDownloadRaw)
            putBoolean("mirrorUp", mirrorUpEnabled)
            putInt("mirrorUpDelay", mirrorUpDelaySec)
            putInt("focusStackCount", focusStackCount)
            putInt("focusStackStep", focusStackStep)
            putBoolean("focusStackNearFirst", focusStackNearFirst)
            putInt("focusStackWait", focusStackWaitSec)
            apply()
        }
    }

    /**
     * 间隔拍摄：总控在这里，界面上的进度/状态靠回调刷。
     *
     * 整段跑一个后台线程，每张走 [shootOnce]，之间有可打断的等待 ——
     * 点「停止」置 false 就够了，不用 interrupt（中断会让 USB 事务半途而废，
     * 正是之前把机身端点搞成 STALL 的那类事故）。
     */
    private fun startIntervalShooting(
        onProgress: (Int, Int) -> Unit,
        onStatus: (String) -> Unit,
        onFinish: (String) -> Unit
    ) {
        if (isIntervalRunning) return
        if (!isConnected) { toast("请先连接相机"); return }
        isIntervalRunning = true
        val total = intervalCount.coerceAtLeast(1)
        intervalThread = thread {
            var done = 0
            try {
                // 先停轮询再摸 USB：基线列举如果和 2 秒轮询抢通道，
                // 会撞出 GetStorageIDs 失败，机身还可能连着几条命令一起超时
                isPollingPaused = true
                Thread.sleep(200)
                // 取景中启动间隔拍摄：同样先退干净再开始（与包围曝光一致）
                exitLiveViewForBatch("间隔拍摄")
                // 拍前把已有照片句柄记下来：机身按会话缓存列表，拍完只有"差集"才知道哪几张是新的
                val knownHandles: MutableSet<Int> =
                    if (autoDownload) {
                        (ptpConnection?.collectPhotoHandles()?.toMutableSet() ?: mutableSetOf())
                    } else mutableSetOf()
                log("间隔拍摄开始：$total 张，间隔 $intervalSec 秒${if (selfTimerSec > 0) "，首张前自拍 $selfTimerSec 秒" else ""}")
                for (i in 1..total) {
                    if (!isIntervalRunning) break
                    runOnUiThread { onStatus("第 $i/$total 张：拍摄中") }
                    val ok = shootOnce(applySelfTimer = (i == 1))
                    done++
                    runOnUiThread { onProgress(done, total) }
                    if (ok && autoDownload) downloadNewShots(knownHandles)
                    if (!isIntervalRunning || i == total) break

                    // 可打断的间隔等待（200ms 步进，停止后最多 0.2 秒就退出）
                    var waited = 0
                    while (waited < intervalSec * 1000 && isIntervalRunning) {
                        Thread.sleep(200)
                        waited += 200
                        if (waited % 1000 == 0) {
                            val left = ((intervalSec * 1000 - waited) / 1000).coerceAtLeast(0)
                            runOnUiThread { onStatus("下一张还有 ${left + 1} 秒") }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { log("间隔拍摄出错：${e.message}") }
            } finally {
                val stopped = !isIntervalRunning
                isIntervalRunning = false
                isPollingPaused = false
                runOnUiThread {
                    onFinish(if (stopped) "已停止（共拍 $done 张）" else "已完成（共 $done 张）")
                    log(if (stopped) "间隔拍摄已停止" else "间隔拍摄完成")
                }
                thread { updateProperties() }
            }
        }
    }

    private fun stopIntervalShooting() {
        isIntervalRunning = false
    }

    /**
     * 日志页「停止」按钮：一键停掉**所有**正在跑的动作。
     *
     * 为什么要这么一个按钮：D300 的每条链路都可能卡在"等机身"上 ——
     * 对焦轮询最长 6 秒、退出取景最长十几秒、间隔拍摄睡在两张之间。
     * 以前想中止只能退出 App 或拔 OTG，期间 USB 通道一直被后台线程占着，
     * 连"再点一次"都发不出去。这里按下即给所有循环递中止信号，并把能收尾的收掉。
     */
    private fun stopAllOperations() {
        // ① 中止信号：正在轮询/等待的循环下一轮就退出（afDrive、waitForIdle、拍摄等待点）
        stopAllRequested = true
        ptpConnection?.abortRequested = true
        log("★ 正在停止所有操作…")

        // ② 间隔拍摄：循环每一步都看 isIntervalRunning，置位即收手（不 interrupt，
        //    它的等待本身就带标志检查，interrupt 反而会打断正在发的命令）
        if (isIntervalRunning) {
            isIntervalRunning = false
            log("· 间隔拍摄已停止")
        }

        // ②b 焦点包围：同一套标志位机制（每站之间等的时候也会看它）
        if (isFocusStackRunning) {
            isFocusStackRunning = false
            log("· 焦点包围已停止")
        }

        // ③ 包围曝光：线程和标志都在对话框里，靠它自己登记的回调收尾
        if (bracketStopHook != null) {
            bracketStopHook?.invoke()
            log("· 包围曝光已停止")
        }

        // ④ 录制：不收尾的话 MP4 缺尾部，文件直接废掉
        if (recorder?.isRecording == true) {
            stopRecording()
            log("· 录像已停止")
        }

        // ⑤ 取景：停读帧循环 + 发 0x9202（自带防重入和超时上限）
        if (isLiveViewRunning || isLiveViewStarting || isLiveViewStopping) {
            lvFramesPaused = false
            log("· 正在退出实时取景…")
            thread {
                try {
                    stopLiveViewBlocking()
                    // 兜底清一次管道：万一刚才有半截事务卡着，后面的命令才发得出去
                    ptpConnection?.clearPipe()
                    ptpConnection?.drain()
                } catch (e: Exception) {
                    runOnUiThread { log("停止取景出错：${e.message}") }
                }
            }
        }

        // ⑥ 周期性属性轮询先按住，免得刚停完又被后台线程唤醒机身
        isPollingPaused = true
        pollingPausedForGallery = false

        showTransientStatus("已停止所有操作")
        log("★ 已停止：取景 / 录制 / 间隔 / 包围 / 轮询")

        // 中止信号只留 3 秒 —— 它是给"此刻正在跑的循环"看的，
        // 一直留着会把用户下一次的正常操作也拦掉。
        thread {
            Thread.sleep(3000)
            stopAllRequested = false
            ptpConnection?.abortRequested = false
        }
    }

    /**
     * 拍摄流程剩下两项各自一个二级页：间隔拍摄 / 拍完自动下载。
     *
     * 原来三组挤在同一个抽屉里：调张数要滑过延时，开关下载又要滑过前面两组。
     * 自拍延时已经不在这儿了 —— 它只有五个选项，参数页直接摆一排方框更省事。
     */
    /** 间隔拍摄：张数 + 间隔时长 + 执行（开始/停止、进度、状态） */
    private fun showIntervalSheet() {
        val page = buildSheetPage("间隔拍摄", "张数 · 间隔时长 · 开始/停止")

        val card = sheetCard(page.content, "参数")
        val countEntries = listOf(3, 5, 10, 20, 50, 100)
        val secEntries = listOf(1, 2, 5, 10, 15, 30, 60)

        /**
         * 「标签 + 手动输入框」的一行。
         *
         * 预设那几档（3/5/10… 张、1/2/5… 秒）覆盖不了真实用法 ——
         * 想拍 250 张星轨、或者每张隔 8 秒，只能在预设里挑个最接近的将就。
         * 所以这里直接给输入框：输完按回车（或直接点别处）就生效。
         */
        fun numberField(
            label: String,
            initial: Int,
            min: Int,
            max: Int,
            unit: String,
            onCommit: (Int) -> Unit
        ): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpx(14), dpx(4), dpx(14), dpx(4))
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            // 范围与单位直接写进输入框的 hint：底部不再重复一遍，页面上少一坨说明文字
            val input = sheetNumberInput("$unit（$min – $max）", "$initial")
            input.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dpx(12) }
            input.setSingleLine(true)
            input.imeOptions = EditorInfo.IME_ACTION_DONE

            fun commit() {
                val raw = input.text.toString().trim()
                val v = raw.toIntOrNull()
                if (v == null) {
                    toast("请填 $min – $max 之间的数字")
                    input.setText("$initial")
                    return
                }
                val clamped = v.coerceIn(min, max)
                if (clamped != v) {
                    input.setText("$clamped")
                    toast("已限制在 $min – $max")
                }
                onCommit(clamped)
            }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { commit(); true } else false
            }
            input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
            row.addView(input)
            return row
        }

        var countField: View? = null
        var secField: View? = null
        var countGrid: SheetTileGrid? = null
        var secGrid: SheetTileGrid? = null

        fun refreshIntervalUi() {
            // 输入框和快选块要一起刷新：改了其中一个，另一个得跟着走
            (countField as? LinearLayout)?.let { row ->
                (row.getChildAt(1) as? EditText)?.setText("$intervalCount")
            }
            (secField as? LinearLayout)?.let { row ->
                (row.getChildAt(1) as? EditText)?.setText("$intervalSec")
            }
            countGrid?.select(countEntries.indexOf(intervalCount))
            secGrid?.select(secEntries.indexOf(intervalSec))
        }

        fun changeCount(v: Int) {
            intervalCount = v
            saveWorkflowSettings()
            refreshWorkflowRows()
            refreshIntervalUi()
        }

        fun changeSec(v: Int) {
            intervalSec = v
            saveWorkflowSettings()
            refreshWorkflowRows()
            refreshIntervalUi()
        }

        countField = numberField("拍摄张数", intervalCount, 1, 999, "张") { changeCount(it) }
        card.addView(countField)
        val cg = sheetTileGrid(4, countEntries.map { "$it 张" }, countEntries.indexOf(intervalCount)) { idx ->
            changeCount(countEntries[idx])
        }
        countGrid = cg
        card.addView(cg.view)

        secField = numberField("间隔时长", intervalSec, 1, 3600, "秒") { changeSec(it) }
        card.addView(secField)
        val sg = sheetTileGrid(4, secEntries.map { "$it 秒" }, secEntries.indexOf(intervalSec)) { idx ->
            changeSec(secEntries[idx])
        }
        secGrid = sg
        card.addView(sg.view)

        // 范围和单位已经写在输入框的 hint 里，这里只留那条真正会影响用法的说明
        card.addView(sheetHint("间隔从上一张拍摄完成的时刻算起；运行中取景会先自动退出。"))

        // ---- 执行 ----
        val cardRun = sheetCard(page.content, "执行")
        val progress = sheetProgress()
        cardRun.addView(progress)
        val sheetStatus = sheetStatusText(if (isIntervalRunning) "正在运行…" else "准备就绪")
        cardRun.addView(sheetStatus)

        val runBtn = sheetPrimaryButton(if (isIntervalRunning) "停止" else "开始间隔拍摄")
        page.content.addView(runBtn)

        fun setIdleUi() {
            runBtn.text = "开始间隔拍摄"
            runBtn.setBackgroundResource(R.drawable.bg_btn_amber)
            runBtn.setTextColor(Color.parseColor("#0B0E13"))
            progress.visibility = View.GONE
            page.dialog.setCancelable(true)
        }

        fun setRunningUi() {
            runBtn.text = "停止"
            runBtn.setBackgroundResource(R.drawable.bg_btn_red)
            runBtn.setTextColor(Color.WHITE)
            progress.visibility = View.VISIBLE
            progress.max = intervalCount
            progress.progress = 0
            page.dialog.setCancelable(false)
        }

        if (isIntervalRunning) setRunningUi()

        runBtn.setOnClickListener {
            if (isIntervalRunning) {
                stopIntervalShooting()
                setIdleUi()
                sheetStatus.text = "正在停止…"
            } else {
                setRunningUi()
                sheetStatus.text = "准备中…"
                startIntervalShooting(
                    onProgress = { done, total ->
                        progress.max = total
                        progress.progress = done
                    },
                    onStatus = { text -> sheetStatus.text = text },
                    onFinish = { text ->
                        sheetStatus.text = text
                        setIdleUi()
                    }
                )
            }
        }

        // 页面被滑掉时把任务停掉，但别硬中断线程（USB 事务半途而废会 STALL 端点）
        page.onDismiss {
            if (isIntervalRunning) stopIntervalShooting()
        }

        page.show()
    }

    /** 拍完自动下载：只放两个开关 */
    private fun showAutoDownloadSheet() {
        val page = buildSheetPage("拍完自动下载", "拍完把原图传到手机相册")

        val card = sheetCard(page.content, "下载")
        val (dlRow, dlSwitch) = sheetSwitchRow("下载到手机相册", autoDownload) { checked ->
            autoDownload = checked
            saveWorkflowSettings()
            refreshWorkflowRows()
        }
        card.addView(dlRow)

        val (rawRow, rawSwitch) = sheetSwitchRow("连 RAW（NEF）一起下载", autoDownloadRaw) { checked ->
            autoDownloadRaw = checked
            saveWorkflowSettings()
            refreshWorkflowRows()
        }
        rawSwitch.isEnabled = autoDownload
        rawRow.alpha = if (autoDownload) 1f else 0.4f
        dlSwitch.setOnCheckedChangeListener { _, checked ->
            autoDownload = checked
            rawSwitch.isEnabled = checked
            rawRow.alpha = if (checked) 1f else 0.4f
            saveWorkflowSettings()
            refreshWorkflowRows()
        }
        card.addView(rawRow)
        card.addView(sheetHint(
            "下载的是相机里的原图，存到手机 相册/D300。\n" +
                "NEF 一张十几 MB，走 USB 会明显变慢；只想要小图就别开这一项。"
        ))

        page.show()
    }

    // =====================================================================
    // 焦点包围（对焦堆叠）
    // =====================================================================

    /**
     * 焦点包围：**不进实时取景**，靠 51 个对焦点从"近"走到"远"，每站各拍一张。
     *
     * ★ 为什么不用 0x9205（AF 区域绝对定位）走这条：
     *   0x9205 只在实时取景里有效，而取景里按快门**必须先退取景**（0x9207 落卡会
     *   STALL 整条 USB 端点，实测已永久停用）—— 也就是每一张都要完整的
     *   "退取景 → 0x100E → 再进取景"来回，一次几秒到十几秒，十几张下来根本没法用，
     *   中途还得反复重写 0xD1A0 取景模式（机身退取景时会把它重置成手持）。
     *
     *   换成 0xD108 选点 + 机身自己的相位 AF，整段都在**普通拍摄状态**里跑：
     *   选点（0xD108）→ 0x90C1 合焦 → 0x100E 释放，每一步都是已经在用的成熟链路，
     *   不会因为取景的进出把管道搅乱。代价是只能在 51 个固定点位上取景深，
     *   不如 0x9205 那样任意 —— 但对微距和风景的景深合成，纵向一排点位完全够用。
     */
    private fun startFocusStack(
        onProgress: (Int, Int) -> Unit,
        onStatus: (String) -> Unit,
        onFinish: (String) -> Unit
    ) {
        if (isFocusStackRunning) return
        if (!isConnected) { toast("请先连接相机"); return }

        // 机身现在停在哪一点：从它开始往近/往远走，比"永远从中心开始"更符合直觉
        val points = currentAfPoints()
        if (points.isEmpty()) { toast("还没有可用的对焦点"); return }

        // 只在同一列附近挑点：跨列会让每一张的构图左右飘，后期叠不起来
        val anchor = points.firstOrNull { it.id == currentAfPointId }
            ?: nearestAfPoint(0.5f, 0.5f)
            ?: points[points.size / 2]
        val column = points.filter { kotlin.math.abs(it.nx - anchor.nx) < 0.08f }
            .ifEmpty { points.filter { kotlin.math.abs(it.nx - anchor.nx) < 0.16f } }
            .ifEmpty { points }
            .sortedBy { it.ny }          // ny 小 = 画面上方 = 远
        val startIdx = (column.indexOfFirst { it.id == anchor.id }).let { if (it >= 0) it else column.size / 2 }
        // 由近到远 = 从画面下方往上走（索引递减）；由远到近反之
        val dir = if (focusStackNearFirst) -1 else 1

        val seq = ArrayList<AfPoint>()
        var i = startIdx
        while (seq.size < focusStackCount) {
            val p = column.getOrNull(i) ?: break
            if (seq.none { it.id == p.id }) seq.add(p)
            val next = i + dir * focusStackStep
            if (next < 0 || next >= column.size) break
            i = next
        }
        if (seq.size < 2) {
            toast("这一列上能走的点位不够（换一档步长或减少张数）")
            return
        }

        isFocusStackRunning = true
        focusStackThread = thread {
            var done = 0
            try {
                isPollingPaused = true
                Thread.sleep(200)
                exitLiveViewForBatch("焦点包围")
                log(
                    "焦点包围开始：${seq.size} 张 · 每步跨 $focusStackStep 个点位 · " +
                        (if (focusStackNearFirst) "由近到远" else "由远到近")
                )
                runOnUiThread { log("对焦点序列：${seq.joinToString(" → ") { it.id.toString() }}") }

                for ((k, p) in seq.withIndex()) {
                    if (!isFocusStackRunning) break
                    runOnUiThread { onStatus("第 ${k + 1}/${seq.size} 张：对焦点 ${p.id}") }

                    // ① 选点。**不再另外发 0x90C1** —— shootOnce 内部本来就会
                    //    按当前对焦点跑一次 AF，这里补一发反而多跑一趟。
                    val ok = ptpConnection?.setAfAreaPoint(p.id) ?: false
                    if (ok) {
                        currentAfPointId = p.id
                        runOnUiThread {
                            focusBox.setAfPoints(currentAfPoints(), p.id)
                            log("焦点包围第 ${k + 1} 站：对焦点 ${p.id}")
                        }
                    } else {
                        runOnUiThread { log("对焦点 ${p.id} 被拒（取景中 / 非单点 AF 时会拒），继续下一站") }
                    }

                    // ② 拍这一张（第一张前走自拍延时，之后靠下面的等待拉开间隔）
                    shootOnce(applySelfTimer = (k == 0))
                    done++
                    runOnUiThread { onProgress(done, seq.size) }
                    if (!isFocusStackRunning || k == seq.lastIndex) break

                    var waited = 0
                    while (waited < focusStackWaitSec * 1000 && isFocusStackRunning) {
                        Thread.sleep(200)
                        waited += 200
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { log("焦点包围出错：${e.message}") }
            } finally {
                val stopped = !isFocusStackRunning
                isFocusStackRunning = false
                isPollingPaused = false
                runOnUiThread {
                    onFinish(if (stopped) "已停止（共拍 $done 张）" else "已完成（共拍 $done 张）")
                    log(if (stopped) "焦点包围已停止" else "焦点包围完成")
                }
                thread { updateProperties() }
            }
        }
    }

    private fun stopFocusStack() {
        isFocusStackRunning = false
    }

    /** 焦点包围的参数页：张数 · 步长 · 方向 · 每张之间等待 */
    private fun showFocusStackSheet() {
        val page = buildSheetPage("焦点包围", "对焦堆叠 · 近→远各拍一张")
        val card = sheetCard(page.content, "参数")

        val countEntries = listOf(3, 5, 8, 12, 16, 20)
        val stepEntries = listOf(1, 2, 3, 4)
        val waitEntries = listOf(1, 2, 3, 5, 8)

        val countGrid = sheetTileGrid(3, countEntries.map { "$it 张" },
            countEntries.indexOf(focusStackCount)) { idx ->
            focusStackCount = countEntries[idx]
            saveWorkflowSettings()
            refreshWorkflowRows()
        }
        card.addView(sheetHint("总共拍几张。张数越多景深覆盖越全，后期合成的选择也越多。"))
        card.addView(countGrid.view)

        card.addView(sheetHint("每站跨过几个对焦点。跨得多 → 每张之间的焦点差大（景深跨度大但可能断层）。"))
        val stepGrid = sheetTileGrid(4, stepEntries.map { "跨 $it 点" },
            stepEntries.indexOf(focusStackStep)) { idx ->
            focusStackStep = stepEntries[idx]
            saveWorkflowSettings()
            refreshWorkflowRows()
        }
        card.addView(stepGrid.view)

        card.addView(sheetHint("由近到远 = 从画面下方往上走（前景→远景），风景和微距通常选这个。"))
        val dirGrid = sheetTileGrid(2, listOf("由近到远", "由远到近"),
            if (focusStackNearFirst) 0 else 1) { idx ->
            focusStackNearFirst = (idx == 0)
            saveWorkflowSettings()
            refreshWorkflowRows()
        }
        card.addView(dirGrid.view)

        card.addView(sheetHint("两站之间等几秒（等机身写完卡、机震散掉）。"))
        val waitGrid = sheetTileGrid(5, waitEntries.map { "$it 秒" },
            waitEntries.indexOf(focusStackWaitSec)) { idx ->
            focusStackWaitSec = waitEntries[idx]
            saveWorkflowSettings()
            refreshWorkflowRows()
        }
        card.addView(waitGrid.view)

        card.addView(
            sheetHint(
                "走的是机身的固定对焦点（51 点模式下一整列），不进实时取景 ——\n" +
                    "取景里一拍就得退出取景，那样每一张都要来回一次，太慢也容易卡管道。\n" +
                    "机身对焦模式需要是 AF-S / AF-C（手动对焦下机身不会重新合焦，堆叠就没意义了）。"
            )
        )

        // ---- 执行 ----
        val run = sheetCard(page.content, "执行")
        val progress = sheetProgress()
        run.addView(progress)
        val status = sheetStatusText(if (isFocusStackRunning) "正在运行…" else "准备就绪")
        run.addView(status)

        val runBtn = sheetPrimaryButton(if (isFocusStackRunning) "停止" else "开始焦点包围")
        page.content.addView(runBtn)

        fun idle() {
            runBtn.text = "开始焦点包围"
            runBtn.setBackgroundResource(R.drawable.bg_btn_amber)
            runBtn.setTextColor(Color.parseColor("#0B0E13"))
            progress.visibility = View.GONE
            page.dialog.setCancelable(true)
        }

        fun running() {
            runBtn.text = "停止"
            runBtn.setBackgroundResource(R.drawable.bg_btn_red)
            runBtn.setTextColor(Color.WHITE)
            progress.visibility = View.VISIBLE
            progress.max = focusStackCount
            progress.progress = 0
            page.dialog.setCancelable(false)
        }

        if (isFocusStackRunning) running()

        runBtn.setOnClickListener {
            if (isFocusStackRunning) {
                stopFocusStack()
                idle()
                status.text = "正在停止…"
            } else {
                running()
                status.text = "准备中…"
                startFocusStack(
                    onProgress = { d, t ->
                        progress.max = t
                        progress.progress = d
                    },
                    onStatus = { status.text = it },
                    onFinish = { status.text = it; idle() }
                )
            }
        }

        page.onDismiss { if (isFocusStackRunning) stopFocusStack() }
        page.show()
    }

    // =====================================================================
    // 反光板预升（Mup）
    // =====================================================================

    /**
     * 反光板预升的**来龙去脉**（改这里之前先看一遍，别再去找那条不存在的指令）：
     *
     * 1. D300 **没有** "抬起反光板" 的私有 PTP 指令。
     *    权威私有操作码表（`D300_PTP私有指令权威对照.md` 里的 20 条：
     *    0x90C0–0x90CF / 0x9200–0x9207）里没有它；
     *    libgphoto2 `ptp.h` 的 `0x941d NikonMirrorUpCancel` / `0xD1F6 MirrorUpStatus`
     *    只出现在 **D780 / D6** 这类新机型的 DeviceInfo 里，D300 的
     *    OperationsSupported 不含它们。
     *    → 所以没法像改 ISO 那样"发一条命令就让反光板抬起来"。
     *
     * 2. 能走的是机身自己的 **Mup 释放模式**：把释放模式拨盘转到 Mup 后，
     *    第一次按快门 = 抬起反光板，第二次按 = 释放曝光。
     *    而 PTP 这边，一次 `0x100E InitiateCapture` 正好等于一次"按到底"，
     *    于是"按两次"= 连发两次 0x100E，中间夹一段等待机震消散的时间 ——
     *    这正是本函数干的事。
     *
     * 3. 顺带的好处：**第二次按时反光板已经抬起，相位 AF 不可用**，
     *    机身不会在释放前再对一次焦。也就是说 Mup 天然绕开了
     *    "合焦了又被相位 AF 顶掉 / 对不上就拍不成" 那个老问题，
     *    不需要再临时切 MF。
     *
     * 4. ⚠️ 前提与代价：
     *    · 用户必须**手动**把释放模式拨盘转到 Mup（PTP 改不了这个拨盘）；
     *      没转就按，等于连拍两张，所以界面上要把这条说清楚并要求确认。
     *    · 抬起后如果第二次命令没发出去，反光板会悬着 ——
     *      机身约 30 秒不动会自己放下，这是机械保护，不用我们兜底。
     *    · 抬起反光板后光学取景器全黑，属于 Mup 的正常现象。
     */
    private fun showMirrorUpSheet() {
        val page = buildSheetPage(
            "反光板预升",
            "机内释放模式 0x5013=0x8012 · 0x100E × 2"
        )

        // ---- 开关 ----
        val onCard = sheetCard(page.content, "开关")
        val (swRow, mupSwitch) = sheetSwitchRow("启用反光板预升", mirrorUpEnabled) { checked ->
            mirrorUpEnabled = checked
            saveWorkflowSettings()
            refreshWorkflowRows()
            if (checked) {
                log("反光板预升已开启 —— 拍摄时 App 会临时把机身释放模式切到 Mup，拍完自动切回")
            }
        }
        onCard.addView(swRow)

        // ---- 等待时长 ----
        val delayCard = sheetCard(page.content, "抬板后等待")
        val options = MIRROR_UP_DELAYS
        val grid = sheetTileGrid(
            options.size, options.map { "$it 秒" }, options.indexOf(mirrorUpDelaySec)
        ) { idx ->
            mirrorUpDelaySec = options[idx]
            saveWorkflowSettings()
            refreshWorkflowRows()
            log("反光板预升：抬板后等待 ${mirrorUpDelaySec} 秒")
        }
        delayCard.addView(grid.view)

        // ---- 说明 ----
        val hintCard = sheetCard(page.content, "这条命令到底在干什么")
        hintCard.addView(sheetHint(
            "D300 没有「抬起反光板」这种专门指令，但机身自己的**释放模式**里有一档叫 Mup，" +
                "它就是干这个的：第一下抬反光板、第二下才曝光。\n\n" +
                "这个释放模式是 PTP 属性 0x5013（StillCaptureMode），以前写它一律被拒，" +
                "是因为要先发 0x90C2(1) 切进主机模式它才可写。现在 App 自己就会做这件事：\n\n" +
                "· 拍摄时临时把机身释放模式切到 Mup（0x8012），拍完自动切回你原来的挡位\n" +
                "· 中间发两次 0x100E，分别对应「抬板」和「释放曝光」，间隔就是上面选的等待时长\n" +
                "· 反光板抬起后取景器全黑，是 Mup 的正常现象\n" +
                "· 第二次没发出去时，机身约 30 秒会自己把反光板放下，不用手动救\n\n" +
                "如果机身拒绝了模式切换（日志会写明原因），就退回老办法：需要你手动把" +
                "释放模式拨盘转到 Mup，App 只做连按两次快门。"
        ))

        // ---- 立刻试一张 ----
        val actionCard = sheetCard(page.content, "验证")
        val status = sheetStatusText("按一下走完整套 Mup 流程")
        actionCard.addView(status)
        val btn = sheetPrimaryButton("立刻试一张（Mup）")
        btn.setOnClickListener {
            if (!isConnected) {
                status.text = "还没连上相机"
                return@setOnClickListener
            }
            if (isLiveViewRunning) {
                status.text = "正在取景中 —— Mup 需要先落反光板，请先退出取景"
                return@setOnClickListener
            }
            btn.isEnabled = false
            status.text = "抬起反光板…"
            thread {
                try {
                    isPollingPaused = true
                    val res = mirrorUpCapture()
                    runOnUiThread {
                        status.text = when {
                            res.exposed -> "拍摄成功（抬板 ${res.firstCode.hex()} → 曝光 ${res.secondCode.hex()}）"
                            res.raised -> "反光板已抬起，但第二次快门被拒（${res.secondCode.hex()}）—— " +
                                "确认释放模式拨盘在 Mup，机身会自行落板"
                            else -> "第一次快门就被拒（${res.firstCode.hex()}）—— " +
                                "检查释放模式拨盘是否在 Mup"
                        }
                        log("反光板预升试拍：抬板 ${res.firstCode.hex()} · 曝光 ${res.secondCode.hex()}")
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "出错：${e.message}" }
                } finally {
                    isPollingPaused = false
                    runOnUiThread { btn.isEnabled = true }
                }
            }
        }
        actionCard.addView(btn)

        page.show()
    }

    private fun Int.hex(): String = if (this < 0) "TIMEOUT" else "0x${Integer.toHexString(this).uppercase()}"

    data class MirrorUpResult(
        /** 第一次快门（抬反光板）的响应码 */
        val firstCode: Int,
        /** 第二次快门（释放曝光）的响应码；没发到就是 -1 */
        val secondCode: Int
    ) {
        val raised: Boolean get() = firstCode == PtpConstants.RESP_OK
        val exposed: Boolean get() = secondCode == PtpConstants.RESP_OK
    }

    /**
     * 走一次完整的 Mup 释放：抬起反光板 → 等机震消散 → 释放曝光。
     *
     * 调用前必须**先退出取景**（反光板在取景时本就是抬起的，Mup 的第一按没意义，
     * 而且取景状态下机身不接受普通快门释放）。
     */
    private fun mirrorUpCapture(): MirrorUpResult {
        val conn = ptpConnection ?: return MirrorUpResult(-1, -1)

        // 先落反光板 / 等就绪：刚做完别的事时机身常在忙
        for (i in 1..8) {
            if (stopAllRequested || conn.abortRequested) return MirrorUpResult(-1, -1)
            if (conn.deviceReady() == true) break
            Thread.sleep(250)
        }

        // ★ 走机内设定：把释放模式临时切到 Mup（0x5013 = 0x8012）。
        //   官方路径 = 0x90C2(1) 主机模式 → 写 0x5013 → 切回机身模式（都在 setStillCaptureMode 里）。
        //   这就不需要用户手动把机身的释放模式拨盘转到 Mup 了 —— 以前那条"App 改不了拨盘"
        //   的说法，是当时没意识到 0x5013 在主机模式下可写。
        val origMode = conn.getStillCaptureMode()
        val viaCamera = conn.setStillCaptureMode(PtpConstants.STILL_CAPTURE_MIRROR_UP)
        runOnUiThread {
            if (viaCamera) log("释放模式已切到 Mup（机内设定，原值 ${origMode.hexMode()}）")
            else log("机身不接受 Mup 模式（0x5013=0x8012）—— 按「拨盘已在 Mup」继续")
        }
        // 模式刚变的那一下机身还在消化，立刻发快门会回 0x2002 Device_Busy（真机日志如此）
        if (viaCamera) Thread.sleep(400)

        try {
            log("反光板预升：第 1 次快门（抬起反光板）")
            var first = conn.capture(afAlreadyLocked = false)
            if (first == PtpConstants.RESP_DEVICE_BUSY) {
                // 抬板被"机身忙"挡回来是常态（尤其前一步刚跑完 AF）：等一会儿再发一次，
                // 别一被拒就判失败 —— 用户看到的是"反光板预升失败"，其实只是没等够
                log("反光板预升：机身忙，等 1.5 秒重发第一次快门")
                Thread.sleep(1500)
                for (i in 1..4) {
                    if (conn.deviceReady() == true) break
                    Thread.sleep(300)
                }
                first = conn.capture(afAlreadyLocked = false)
            }
            if (first != PtpConstants.RESP_OK) {
                log(
                    "反光板预升：抬板被拒（${first.hex()}）" +
                        if (viaCamera) "—— 机身刚接受 Mup 模式却拒绝了快门" else "—— 确认释放模式拨盘已转到 Mup"
                )
                return MirrorUpResult(first, -1)
            }

            // 抬板后等机震消散。分段睡：中途点「停止」能立刻撤
            val settle = mirrorUpDelaySec.coerceIn(1, 30) * 1000L
            log("反光板预升：反光板已抬起，等 ${mirrorUpDelaySec} 秒让机震消散")
            val step = 250L
            var waited = 0L
            while (waited < settle) {
                if (stopAllRequested || conn.abortRequested) {
                    log("反光板预升：等待中被中止 —— 机身会自行放下反光板")
                    return MirrorUpResult(first, -1)
                }
                Thread.sleep(step.coerceAtMost(settle - waited))
                waited += step
            }

            log("反光板预升：第 2 次快门（释放曝光）")
            val second = conn.capture(afAlreadyLocked = true)
            log("反光板预升：曝光 ${second.hex()}")
            return MirrorUpResult(first, second)
        } finally {
            // 释放模式放回去：机身不该一直停在 Mup
            if (viaCamera && origMode >= 0) {
                if (!conn.setStillCaptureMode(origMode)) {
                    log("恢复释放模式失败（原值 ${origMode.hexMode()}）—— 机身会停在 Mup")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "释放模式没能自动切回（机身停在 Mup）—— 请在机身上把释放模式拨回去",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /** 释放模式值 → 人话，日志里用（带原始码，方便对日志） */
    private fun Int.hexMode(): String = when (this) {
        PtpConstants.STILL_CAPTURE_SINGLE -> "单张（S） 0x0001"
        PtpConstants.STILL_CAPTURE_CONT_HIGH -> "高速连拍（CH） 0x0002"
        PtpConstants.STILL_CAPTURE_CONT_LOW -> "低速连拍（CL） 0x8010"
        PtpConstants.STILL_CAPTURE_SELF_TIMER -> "自拍 0x8011"
        PtpConstants.STILL_CAPTURE_MIRROR_UP -> "反光板预升（Mup） 0x8012"
        PtpConstants.STILL_CAPTURE_LIVE_VIEW -> "取景（LV） 0x8013"
        -999999 -> "读不到"
        else -> "0x${Integer.toHexString(this).uppercase()}"
    }

    /**
     * 释放模式值 → 界面上显示的中文（不露原始码）。
     *
     * 六挡全部来自官方规格书 §5.5.1.15 的值表：0x0001 单张 / 0x0002 CH /
     * 0x8010 CL / 0x8011 自拍 / 0x8012 反光板预升 / 0x8013 取景。
     * 机身报出表外的值时照实显示原始码 —— 那种情况说明我们对协议的假设错了，
     * 遮起来反而更难发现。
     */
    private fun releaseModeLabel(v: Int): String = when (v) {
        PtpConstants.STILL_CAPTURE_SINGLE -> "单张"
        PtpConstants.STILL_CAPTURE_CONT_HIGH -> "高速连拍"
        PtpConstants.STILL_CAPTURE_CONT_LOW -> "低速连拍"
        PtpConstants.STILL_CAPTURE_SELF_TIMER -> "自拍"
        PtpConstants.STILL_CAPTURE_MIRROR_UP -> "反光板预升"
        PtpConstants.STILL_CAPTURE_LIVE_VIEW -> "取景"
        -999999 -> "读取失败"
        else -> "0x${Integer.toHexString(v).uppercase()}"
    }

    // =====================================================================
    // 曝光与画质：测光模式 / 闪光补偿 / 画质
    // =====================================================================

    /** 属性的数据类型 → 写值字节宽度（跟描述符走，别一律按 2 字节写） */
    private fun propSize(dataType: Int): Int = when (dataType) {
        0x0001, 0x0002 -> 1
        0x0003, 0x0004 -> 2
        else -> 4
    }

    private fun meteringLabel(v: Long): String {
        val code = v.toInt() and 0xFFFF
        return PtpConstants.METERING_NAMES[code] ?: "0x%04X".format(code)
    }

    /**
     * 测光模式二级页：三档摆出来、标出机身当前那一档。
     *
     * ⚠️ 这台 D300 把 0x500B 的 GetSet 报成**只读**，App 写不动它 ——
     * 所以这里不是"选档器"：点一档会真的发一次写命令（万一哪台机身允许就当场生效），
     * 被拒时明确告诉用户"请用机身上的测光选择转盘切换"，而不是闷声失败。
     *
     * @param onLabel 写入成功后的回调：把新档位回填到调用方那一行（现在那行在「机身信息」里）
     */
    private fun showMeteringSheet(onLabel: (String) -> Unit = {}) {
        val entries = listOf(5 to "矩阵测光", 2 to "中央重点测光", 3 to "点测光")
        val current = currentMetering
        val page = buildSheetPage(
            "测光模式",
            if (current == -999999) "0x500B · 未读到" else "机身当前：${meteringLabel(current.toLong())}"
        )
        val card = sheetCard(page.content, null)
        val status = sheetStatusText("高亮项是机身当前测光方式")
        card.addView(
            sheetTileGrid(3, entries.map { it.second }, entries.indexOfFirst { it.first == current }) { idx ->
                val (code, label) = entries[idx]
                thread {
                    try {
                        isPollingPaused = true
                        // 测光模式 0x500B 默认报只读，官方 §2.4⑤ 说切主机模式后可写 ——
                        // 写的时候临时 0x90C2(1)，写完立刻切回，机身状态不会被留在主机模式
                        val ok = ptpConnection?.setPropInHostMode(
                            PtpConstants.PROP_METERING_MODE, code, 2
                        ) ?: false
                        runOnUiThread {
                            if (ok) {
                                status.text = "已设为 $label"
                                log("测光模式已设为 $label（主机模式写入）")
                                onLabel(label)
                                currentMetering = code
                                page.dialog.dismiss()
                            } else {
                                // 主机模式也不行才退回到"用转盘"，别让人反复点
                                status.text = "机身没有接受 $label —— 已尝试切主机模式仍被拒，请用机身上的测光选择转盘"
                                log("测光模式写入被拒（主机模式下 0x500B 仍不可写）")
                            }
                        }
                        Thread.sleep(300)
                        updateProperties()
                    } catch (e: Exception) {
                        runOnUiThread { log("设置测光模式出错：${e.message}") }
                    } finally {
                        isPollingPaused = false
                    }
                }
            }.view
        )
        page.content.addView(status)
        page.content.addView(
            sheetHint("测光模式（0x500B）默认报只读，但官方规格书 §2.4⑤ 写明：切进主机模式" +
                "（0x90C2(1)）之后它就可写。所以点一档时 App 会临时切主机模式写入、写完立刻切回。" +
                "如果仍被机身拒绝，就只能拨机身上的测光选择转盘 —— 转盘一动，这里和参数页都会跟着更新。")
        )
        page.show()
    }

    /**
     * 闪光补偿的显示：机身单位可能是 1/6 EV（libgphoto2 对尼康的换算）
     * 也可能是 1/1000 EV。按值域量级判断，别把 ±3 EV 显示成 ±180 EV。
     */
    private fun flashCompLabel(v: Long): String {
        val ev = if (kotlin.math.abs(v) > 30) v / 1000.0 else v / 6.0
        return (if (ev > 0) "+%.1f" else "%.1f").format(ev) + " EV"
    }

    /**
     * 影像尺寸（0x5003 ImageSize）的三档。
     *
     * ⚠️ 这一项**不是数值枚举**：官方规格书 §5.5.1.2 写明 DataType = String，
     * 值就是 `4288x2848` / `3216x2136` / `2144x1424` 这种"宽x高"字符串。
     * 以前把它当数值属性读（readPropDesc 对字符串类型直接返回 null），界面上就变成
     * "机身不支持该属性"—— 那是假的，机身支持得很。
     */
    private val imageSizeOptions = listOf(
        "4288x2848" to "大 · 4288×2848",
        "3216x2136" to "中 · 3216×2136",
        "2144x1424" to "小 · 2144×1424"
    )

    /** 机器报的尺寸字符串 → 人话；表外的值原样露出，别硬套 */
    private fun imageSizeText(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return "读取失败"
        return imageSizeOptions.firstOrNull { it.first.equals(v, ignoreCase = true) }?.second ?: v
    }

    /**
     * 影像尺寸二级页：字符串属性的三档选择。
     *
     * 走字符串通道（0x1015 读 / 0x1016 写），不能用普通数值枚举那一套；
     * 写完回读比对，机身改写或截断都照实报。
     */
    private fun showImageSizeSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage("影像尺寸", "0x5003 · 字符串属性")
        val card = sheetCard(page.content, "可选尺寸")
        val status = sheetStatusText("正在读取…")
        card.addView(status)
        val gridHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(gridHost)
        card.addView(sheetHint(
            "机身把这一项当成字符串报（例如 “4288x2848”），不是 0/1/2 那种数值档位，" +
                "所以读写都走字符串通道。三个尺寸来自官方规格书 §5.5.1.2。"
        ))
        page.show()

        thread {
            val cur = try {
                ptpConnection?.getDevicePropString(PtpConstants.PROP_IMAGE_SIZE)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                status.text = if (cur.isNullOrBlank()) "读取失败（机身没回值）" else "当前：${imageSizeText(cur)}"
                if (!cur.isNullOrBlank()) imageQualityRow.value.text = imageSizeText(cur)
                val selected = imageSizeOptions.indexOfFirst { it.first.equals(cur?.trim(), true) }
                // 回调里要能回高亮自己 —— 局部变量不能在初始化式里引用自己，走 gridRef 两步赋值
                var gridRef: SheetTileGrid? = null
                val grid = sheetTileGrid(
                    columns = 3,
                    labels = imageSizeOptions.map { it.second },
                    initialSelected = selected
                ) { pick ->
                    val target = imageSizeOptions[pick].first
                    status.text = "正在写入 ${imageSizeOptions[pick].second} …"
                    thread {
                        val ok = try {
                            ptpConnection?.setDevicePropString(PtpConstants.PROP_IMAGE_SIZE, target) ?: false
                        } catch (_: Exception) {
                            false
                        }
                        Thread.sleep(300)
                        val back = try {
                            ptpConnection?.getDevicePropString(PtpConstants.PROP_IMAGE_SIZE)
                        } catch (_: Exception) {
                            null
                        }
                        runOnUiThread {
                            when {
                                !ok -> status.text = "写入失败：机身拒绝了 $target"
                                back == null -> status.text = "已发出，但回读失败 —— 无法确认是否真的改掉了"
                                back.trim().equals(target, ignoreCase = true) -> {
                                    status.text = "已写入：${imageSizeText(back)}"
                                    imageQualityRow.value.text = imageSizeText(back)
                                    gridRef?.select(pick)
                                    log("影像尺寸已写入：$target")
                                }
                                else -> status.text = "回读是「$back」，与写入值不一致（机身可能改写了）"
                            }
                        }
                    }
                }
                gridRef = grid
                gridHost.addView(grid.view)
            }
        }
    }

    /**
     * JPEG 压缩等级 / 画质模式（0x5004 CompressionSetting）。
     *
     * 八挡全部照官方规格书 §5.5.1.3 的值表译，一个都不留原始码：
     * 0x00 BASIC / 0x01 NORMAL / 0x02 FINE / 0x03 TIFF(RGB) /
     * 0x04 RAW / 0x05 RAW+BASIC / 0x06 RAW+NORMAL / 0x07 RAW+FINE。
     * 机身报出表外的值才显示原始码 —— 那说明我们对档位的假设错了，该看见。
     */
    private fun compressionLabel(v: Long): String = when (v.toInt() and 0xFFFF) {
        0x00 -> "JPEG 基本"
        0x01 -> "JPEG 标准"
        0x02 -> "JPEG 精细"
        0x03 -> "TIFF（RGB）"
        0x04 -> "RAW"
        0x05 -> "RAW + JPEG 基本"
        0x06 -> "RAW + JPEG 标准"
        0x07 -> "RAW + JPEG 精细"
        else -> "0x%04X".format(v.toInt() and 0xFFFF)
    }

    /**
     * 通用「枚举型机身属性」二级页。
     *
     * 流程：读描述符 → 按机身自述的合法值出档位块 → 点选写入 → 回读校验。
     * 机身不给描述符（不支持）或标成只读时，一律不发写命令。
     */
    private fun showEnumPropSheet(
        title: String,
        subtitle: String,
        propCode: Int,
        labeler: (Long) -> String,
        /** 底部说明；空串表示不显示（不是每项都需要一段解释） */
        hint: String = "",
        /**
         * 写入时先切主机模式（0x90C2(1)）。0x500B 测光 / 0x501C AF 区域模式
         * 这两个属性默认报只读，只有主机模式下才可写 —— 官方 §2.4⑤。
         */
        hostMode: Boolean = false,
        onCurrentLabel: (String) -> Unit
    ) {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage(title, subtitle)
        val card = sheetCard(page.content, "可选档位")
        val gridHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(gridHost)
        if (hint.isNotBlank()) card.addView(sheetHint(hint))
        val sheetStatus = sheetStatusText("正在读取机身设置…")
        page.content.addView(sheetStatus)
        page.show()

        thread {
            val desc = ptpConnection?.readPropDesc(propCode)
            if (desc == null) {
                runOnUiThread {
                    sheetStatus.text = "机身不支持该属性（0x${Integer.toHexString(propCode).uppercase()} 没有描述符）"
                    onCurrentLabel("不支持")
                }
                return@thread
            }
            if (desc.allowed.isEmpty()) {
                runOnUiThread {
                    sheetStatus.text = "机身没给可选档位（类型 ${desc.typeName}）"
                    onCurrentLabel(labeler(desc.currentValue))
                }
                return@thread
            }
            val values = desc.allowed
            val size = propSize(desc.dataType)
            runOnUiThread {
                // 底部不再挂"当前：xx · Uint8"那行：当前档位已经由色块标出来了，
                // 数据类型（Uint8 之类）是机身协议的细节，对拍照没有任何用处。
                sheetStatus.text = ""
                sheetStatus.visibility = View.GONE
                onCurrentLabel(labeler(desc.currentValue))
                val grid = sheetTileGrid(
                    columns = if (values.size <= 6) 2 else 3,
                    labels = values.map { labeler(it) },
                    initialSelected = values.indexOf(desc.currentValue)
                ) { idx ->
                    if (!desc.writable && !hostMode) {
                        toast("机身把这一项标为只读，只能在机身上改")
                    } else {
                        writePropValue(
                            propCode, values[idx], size, labeler(values[idx]),
                            sheetStatus, onCurrentLabel, hostMode
                        )
                    }
                }
                gridHost.addView(grid.view)
                // 只读项要明说：否则用户会一直点档位、以为 App 坏了
                if (!desc.writable) {
                    if (hostMode) {
                        card.addView(sheetHint(
                            "机身把这一项默认报只读 —— 写入时 App 会自动切到主机模式" +
                                "（0x90C2(1)）、写完立刻切回。若仍被拒，说明这台机身不允许远程改，请用机身上的按键。"
                        ))
                    } else {
                        card.addView(sheetHint("⚠️ 机身把这项标为只读（GetSet=只读）：这里只能显示当前档位，改档请用机身上的拨盘/按键。"))
                    }
                }
            }
        }
    }

    /**
     * 字符串属性的编辑页：版权注释（0xD090）、拍摄菜单库名（0xD011–0xD014）、
     * 个人设定库名（0xD041–0xD044）。
     *
     * 这几个属性是 PTP 的 **string 类型**（描述符 dataType = 0xFFFF），走数值写入
     * 一律回 Invalid_Prop_Format —— 必须用 [PtpUsbConnection.setDevicePropString]。
     * 写完回读比对：机身可能截断或改写（比如长度上限比界面短），不一致就照实说。
     */
    private fun showTextPropSheet(title: String, propCode: Int, hintText: String, maxChars: Int = 36) {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage(title, "0x%04X · 字符串属性".format(propCode))
        val card = sheetCard(page.content, "当前值")
        val status = sheetStatusText("正在读取…")
        card.addView(status)
        val input = EditText(this).apply {
            setSingleLine()
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_disabled))
            hint = hintText
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxChars))
        }
        card.addView(input)
        if (hintText.isNotBlank()) card.addView(sheetHint(hintText))
        val saveBtn = sheetPrimaryButton("写入机身")
        page.content.addView(saveBtn)
        page.show()

        thread {
            val cur = ptpConnection?.getDevicePropString(propCode)
            runOnUiThread {
                status.text = when {
                    cur == null -> "机身不支持这一项（读不到值）"
                    cur.isEmpty() -> "当前为空"
                    else -> "当前：$cur"
                }
                input.setText(cur ?: "")
            }
        }

        saveBtn.setOnClickListener {
            val text = input.text.toString()
            saveBtn.isEnabled = false
            thread {
                val ok = ptpConnection?.setDevicePropString(propCode, text) ?: false
                Thread.sleep(300)
                val back = ptpConnection?.getDevicePropString(propCode)
                runOnUiThread {
                    saveBtn.isEnabled = true
                    when {
                        !ok -> status.text = "写入失败：机身拒绝了这段文字"
                        back == null -> status.text = "已发出，但回读失败 —— 无法确认是否真的写进去了"
                        back == text -> {
                            status.text = "已写入：$back"
                            log("$title 已更新：$back")
                        }
                        else -> status.text = "写入后读回的是「$back」，与输入不一致（机身可能截断或改写了）"
                    }
                }
            }
        }
    }

    /** 选一个菜单库（拍摄 A–D / 个人设定 A–D），然后编辑它的名字 */
    private fun pickBankThenEditName(kind: String, codes: List<Int>) {
        val names = listOf("A", "B", "C", "D")
        AlertDialog.Builder(this)
            .setTitle("编辑$kind 库名")
            .setItems(names.map { "$kind ${it} 库" }.toTypedArray()) { _, which ->
                showTextPropSheet(
                    "$kind ${names[which]} 库名",
                    codes[which],
                    "机身里这个库的名字（最多 20 个字符左右，超出会被机身截断）"
                )
            }
            .show()
    }

    /** 写一个属性并回读校验；回读不一致要如实报，别让用户以为改成功了 */
    private fun writePropValue(
        code: Int,
        value: Long,
        size: Int,
        label: String,
        sheetStatus: TextView,
        onCurrentLabel: (String) -> Unit,
        hostMode: Boolean = false
    ) {
        /** 这行状态文字平时是隐藏的（档位块已经标了当前值），只在写的时候冒出来 */
        fun show(msg: String) {
            runOnUiThread {
                sheetStatus.text = msg
                sheetStatus.visibility = View.VISIBLE
            }
        }
        thread {
            show(if (hostMode) "正在切主机模式并写入 $label …" else "正在写入 $label …")
            val ok = if (hostMode) {
                ptpConnection?.setPropInHostMode(code, value.toInt(), size) ?: false
            } else {
                ptpConnection?.setDevicePropValue(code, value.toInt(), size = size) ?: false
            }
            Thread.sleep(300)
            val readBack = ptpConnection?.getDevicePropValue(code) ?: -999999
            // getDevicePropValue 一律按无符号还原（1 字节 0..255），
            // 负档位（闪光补偿 -6 → 250）要按写入宽度掩码后再比，否则永远"不一致"
            val mask = when (size) {
                1 -> 0xFF
                2 -> 0xFFFF
                else -> 0
            }
            val expected = if (mask != 0) value.toInt() and mask else value.toInt()
            runOnUiThread {
                if (!ok) {
                    show("写入失败：机身拒绝了 $label")
                } else if (readBack == expected) {
                    show("已设为 $label（机身回读一致）")
                    onCurrentLabel(label)
                    refreshExposureExtras()
                } else {
                    show("机身接受但回读不一致（写入 $value，读回 $readBack）")
                }
            }
            thread { updateProperties() }
        }
    }

    /** 开会话/写完值后刷一次这三项的显示 —— 只读描述符与当前值，不发轮询 */
    private fun refreshExposureExtras() {
        if (!isConnected || isPollingPaused) return
        thread {
            // 会话刚建立时管道里常残留上一次事务的数据，立刻连读容易读到串包的值
            // （真机上见过"测光模式"两次读出不同档）。先让机身喘一口气再读。
            if (!isPollingActive) Thread.sleep(1200)
            val meter = ptpConnection?.getDevicePropValue(PtpConstants.PROP_METERING_MODE) ?: -999999
            // 闪光补偿是有符号 INT8：走描述符取当前值，避免 -6 被读成 250。
            // 描述符偶发读空（会话刚建立时管道里还有残留数据），补一次重试再判"不支持"。
            var flashDesc = ptpConnection?.readPropDesc(PtpConstants.PROP_NIKON_FLASH_COMP)
            if (flashDesc == null) {
                Thread.sleep(600)
                flashDesc = ptpConnection?.readPropDesc(PtpConstants.PROP_NIKON_FLASH_COMP)
            }
            val flash = flashDesc?.currentValue ?: -999999L
            // 0x5003 是**字符串**属性（"4288x2848"），按数值读永远是 -999999
            val size = try {
                ptpConnection?.getDevicePropString(PtpConstants.PROP_IMAGE_SIZE)
            } catch (_: Exception) {
                null
            }
            val compression = ptpConnection?.getDevicePropValue(PtpConstants.PROP_COMPRESSION_SETTING) ?: -999999
            val media = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_RECORDING_MEDIA) ?: -999999
            currentMetering = meter
            currentFlashComp = if (flash == -999999L) -999999 else flash.toInt()
            runOnUiThread {
                if (::flashCompRow.isInitialized) {
                    flashCompRow.value.text = if (flash == -999999L) "不支持" else flashCompLabel(flash)
                    imageQualityRow.value.text = imageSizeText(size)
                    if (::compressionRow.isInitialized) {
                        compressionRow.value.text =
                            if (compression == -999999) "不支持" else compressionLabel(compression.toLong())
                    }
                    mediaRow.value.text = if (media == -999999) "不支持" else mediaLabel(media.toLong())
                }
            }
        }
    }

    // =====================================================================
    // 机身信息与存储
    // =====================================================================

    private fun refreshDeviceAndStorage() {
        if (!isConnected) return
        thread {
            val info = ptpConnection?.readDeviceInfo()
            deviceInfoCache = info
            val ids = ptpConnection?.getStorageIds() ?: IntArray(0)
            val list = ArrayList<StorageInfo>()
            for (id in ids) {
                ptpConnection?.getStorageInfo(id)?.let { list.add(it) }
            }
            storages = list
            // 卡的信息只在「机身信息」里显示，那边打开时自己读一次；
            // 这里只刷新缓存，供「存储卡」二级页和拍照后的剩余张数用。
        }
    }

    /**
     * 「机身状态」页的条目：标签 + 属性码 + 值格式化。
     *
     * 值表全部来自官方规格书 `_sdk/txt/D300UsbPtpE_01.txt` 的 5.5.x 各节 ——
     * **不是猜的**：闪光同步 §5.5.1.9、机身方向 §5.5.6.1、曝光显示 §5.5.6.12、
     * 曝光指示 §5.5.6.13、信息屏错误 §5.5.6.16、闪光灯 §5.5.8.1–5.5.8.5、
     * CCD §5.5.11.1、USB 速度 §5.5.12.1、设备类型 §5.5.15.2。
     * 只有表外的值才露原始码 —— 那说明我们对协议的假设错了，该看见。
     *
     * @param stringProp 字符串属性（CCD 编号、会话发起者版本）走 0x1015 的字符串通道，
     *   按数值读会一律得到 -999999，伪装成「不支持」。
     */
    private data class BodyStatusItem(
        val label: String,
        val code: Int,
        val stringProp: Boolean = false,
        val fmt: (Int) -> String = { v -> "值 $v" }
    )

    /** 1/6 EV 为单位的补正值 → "+0.3 EV" 这种（官方 §5.5.6.13 / §5.5.8.5 都写明 1/6 EV） */
    private fun sixthEv(v: Int): String {
        // 这两条属性都是 INT8。0x1015 读回来的字节没有类型信息，负值会变成 250 这类无符号数，
        // 直接除 6 就成了 +41.7 EV —— 先按有符号字节还原再换算。
        val signed = if (v in 128..255) v - 256 else v
        val ev = signed / 6.0
        return (if (ev > 0) "+%.1f" else "%.1f").format(ev) + " EV"
    }

    private val bodyStatusItems: List<BodyStatusItem> by lazy {
        listOf(
            // ---- 闪光灯（§5.5.1.9 / §5.5.8.x）----
            BodyStatusItem("闪光模式", PtpConstants.PROP_FLASH_MODE) { v ->
                when (v) {
                    0x0004 -> "防红眼"
                    0x8010 -> "普通（前帘同步）"
                    0x8011 -> "慢速同步"
                    0x8012 -> "后帘同步"
                    0x8013 -> "防红眼 + 慢速"
                    else -> "值 $v"
                }
            },
            BodyStatusItem("外接闪光灯", PtpConstants.PROP_NIKON_EXT_SPEEDLIGHT_EXIST) { v ->
                when (v) {
                    0 -> "未连接"
                    1 -> "已连接"
                    else -> "值 $v"
                }
            },
            BodyStatusItem("闪光灯就绪", PtpConstants.PROP_NIKON_EXT_SPEEDLIGHT_STATUS) { v ->
                when (v) {
                    0 -> "未充电"
                    1 -> "就绪"
                    else -> "值 $v"
                }
            },
            BodyStatusItem("闪光灯通信", PtpConstants.PROP_NIKON_EXT_SPEEDLIGHT_SORT) { v ->
                when (v) {
                    0 -> "非通信"
                    1 -> "保留（D300 不用）"
                    2 -> "新通信"
                    else -> "值 $v"
                }
            },
            BodyStatusItem("闪光补偿", PtpConstants.PROP_NIKON_FLASH_COMPENSATION) { v -> sixthEv(v) },
            BodyStatusItem("闪光灯模式", PtpConstants.PROP_NIKON_NEW_EXT_SPEEDLIGHT_MODE) { v ->
                when (v) {
                    0 -> "关闭"
                    1 -> "i-TTL-BL"
                    2 -> "i-TTL"
                    3 -> "光圈联动自动"
                    4 -> "外置自动"
                    5 -> "手动（距离优先）"
                    6 -> "手动"
                    7 -> "多灯闪光"
                    else -> "值 $v"
                }
            },

            // ---- 机身姿态与供电 ----
            BodyStatusItem("机身方向", PtpConstants.PROP_NIKON_ORIENTATION) { v ->
                when (v) {
                    0 -> "横拍（或未固定）"
                    1 -> "竖拍（手柄朝上）"
                    2 -> "竖拍（手柄朝下）"
                    3 -> "横拍（倒置）"
                    else -> "值 $v"
                }
            },
            BodyStatusItem("缓冲区可拍", PtpConstants.PROP_BURST_NUMBER) { v -> "$v 张" },
            BodyStatusItem("外接电源", PtpConstants.PROP_NIKON_EXTERNAL_DC_IN) { v ->
                when (v) {
                    0 -> "未连接（用电池）"
                    1 -> "已连接"
                    else -> "值 $v"
                }
            },

            // ---- 曝光显示（§5.5.6.12）：值是「快门状态 × 光圈状态」的 3×3 组合 ----
            BodyStatusItem("曝光显示", PtpConstants.PROP_NIKON_EXPOSURE_DISPLAY_STATUS) { v ->
                val shutter = listOf("正常", "偏低", "偏高")
                val aperture = listOf("正常", "偏低", "偏高")
                if (v in 0..8) {
                    "快门${shutter[v / 3]} · 光圈${aperture[v % 3]}"
                } else {
                    "值 $v"
                }
            },
            BodyStatusItem("曝光指示", PtpConstants.PROP_NIKON_EXPOSURE_INDICATE_STATUS) { v -> sixthEv(v) },
            BodyStatusItem("信息屏报错", PtpConstants.PROP_NIKON_INFO_DISPLAY_ERROR) { v ->
                when (v) {
                    0 -> "无"
                    1 -> "有错误提示"
                    else -> "值 $v"
                }
            },

            // ---- 连接与身份 ----
            BodyStatusItem("USB 速度", PtpConstants.PROP_NIKON_USB_SPEED) { v ->
                when (v) {
                    0 -> "全速（12 Mbps）"
                    1 -> "高速（480 Mbps）"
                    else -> "值 $v"
                }
            },
            BodyStatusItem("CCD 编号", PtpConstants.PROP_NIKON_CCD_NUMBER, stringProp = true),
            BodyStatusItem("设备类型", PtpConstants.PROP_NIKON_PERCEIVED_DEVICE_TYPE) { v ->
                when (v) {
                    1 -> "数码相机"
                    else -> "值 $v"
                }
            }
        )
    }

    /**
     * 机身状态（只读）二级页：进页面就把全部状态读一遍，一条一条填。
     *
     * 读不到就写「不支持」，不猜、不隐藏。行先全部铺出来再逐条填值，避免读的过程中页面一路往下长；
     * 「应答 N/M 项」放在大标题正下方（页面副标题位），不塞进卡片里。
     */
    private fun showBodyStatusSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage("机身状态")
        // 大标题下面那行：读取时显示进度，读完换成应答统计
        val headline = TextView(this).apply {
            text = "正在读取…"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpx(10))
        }
        page.content.addView(headline)
        val card = sheetCard(page.content, "机身自报状态")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val valueViews = ArrayList<TextView>()
        for (item in bodyStatusItems) {
            val (row, value) = sheetInfoRow(item.label, "…")
            list.addView(row)
            valueViews.add(value)
        }
        card.addView(list)
        card.addView(sheetHint(
            "这些是机身自己报的状态，改不了。每个数值的中文含义都取自官方规格书的值表，" +
                "表外的值才显示原始码。\n" +
                "CCD 编号 = 传感器（CCD）的序列号，12 位批号 + 8 位序号，空格补齐，售后登记时用来认机身。\n" +
                "信息屏报错 = 机身 INFO 屏上有没有错误提示，触发原因有：释放序列错误、校验和错误、" +
                "卡硬件错误、主控启动异常 —— 原因消除后会自动消失。"
        ))
        page.show()

        val wasPaused = isPollingPaused
        isPollingPaused = true
        thread {
            var answered = 0
            try {
                for ((idx, item) in bodyStatusItems.withIndex()) {
                    if (!isConnected) break
                    val text = try {
                        if (item.stringProp) {
                            // 字符串属性（CCD 编号）：按数值读永远拿不到值
                            ptpConnection?.getDevicePropString(item.code)?.trim()?.takeIf { it.isNotEmpty() }
                                ?: "不支持"
                        } else {
                            val v = ptpConnection?.getDevicePropValue(item.code) ?: -999999
                            if (v == -999999) "不支持" else item.fmt(v)
                        }
                    } catch (_: Exception) {
                        "读取失败"
                    }
                    if (text != "不支持" && text != "读取失败") answered++
                    runOnUiThread {
                        val value = valueViews[idx]
                        value.text = text
                        if (text == "不支持" || text == "读取失败") {
                            value.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_disabled))
                        }
                        headline.text = "正在读取 ${idx + 1}/${bodyStatusItems.size} 项…"
                    }
                }
            } finally {
                isPollingPaused = wasPaused
                runOnUiThread {
                    headline.text = "全部只读 · 应答 $answered/${bodyStatusItems.size} 项"
                }
            }
        }
    }

    private fun showDeviceInfoSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        // 副标题原来写「型号 / 固件 / 序列号 / 快门数」，和下面四行逐字重复，删掉
        val page = buildSheetPage("机身信息")
        // 一台机身的身份 + 当前状态全放在这一组里：型号 / 固件 / 序列号 / 快门数 /
        // 镜头规格 / 释放模式 / 机身状态。以前拆成「机身 / 镜头 / 释放模式 / 机身状态」四张卡，
        // 每一张都只有一两行，读起来像四份残缺清单。
        // 卡片本身不再挂「机身」小标题：整页就叫「机身信息」，再标一次是重复。
        val card = sheetCard(page.content)
        val (modelRow, modelValue) = sheetInfoRow("型号", deviceInfoCache?.model ?: "读取中…")
        val (verRow, verValue) = sheetInfoRow("固件版本", deviceInfoCache?.versionOrDash() ?: "读取中…")
        val (serialRow, serialValue) = sheetInfoRow("序列号", deviceInfoCache?.serialOrDash() ?: "读取中…")
        val (shutterRow, shutterValue) = sheetInfoRow("快门数", if (shutterCount >= 0) "$shutterCount 次" else "点一下读取")
        shutterValue.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        card.addView(modelRow); card.addView(verRow); card.addView(serialRow); card.addView(shutterRow)
        // 读一次要拉一整张 NEF（0x101B 分段读机身不认），所以不自动读：点了才读
        shutterRow.setOnClickListener {
            if (shutterCount >= 0) {
                toast("快门数：$shutterCount 次")
            } else {
                loadShutterCount { text -> shutterValue.text = text }
            }
        }
        // 长按 = 只读诊断：问机身支持哪些属性码、逐条读值，看快门数是不是藏在某个属性里
        shutterRow.setOnLongClickListener {
            if (!isConnected) { toast("请先连接相机"); return@setOnLongClickListener true }
            toast("正在查机身属性里有没有快门数（只读，约十几秒）…")
            probeShutterCountProp()
            true
        }

        // 镜头规格（0xD0E3–0xD0E6）：规格书只给了码和名字，**没写单位**。
        // 按尼康惯例焦距是 mm×100、光圈是 f×100，折算后明显不合常理就退回原始值 ——
        // 宁可不折算，也不把 2.4mm 那种数字当成事实证明给用户看。
        val (lensSpecRow, lensSpecValue) = sheetInfoRow("镜头规格", "读取中…")
        card.addView(lensSpecRow)

        // 释放模式（0x5013）：自拍 / Mup / 取景 用 App 改完之后，这一行能确认机身到底停在哪一挡。
        // 反光板预升改走机内设定之后，它就是"有没有恢复成功"的照妖镜。
        val (scRow, scValue) = sheetInfoRow("释放模式", "读取中…")
        card.addView(scRow)

        // 测光模式与存储卡从参数页搬过来：这两项是机身当前状态 / 机身里的卡，
        // 不是"这次拍摄要调的参数"。测光那一行点进去只有三档 + 一句"只能用转盘改"。
        var meterRowRef: SheetValueRow? = null
        val meterRow = sheetValueRow("测光模式", "读取中…") { _ ->
            if (!isConnected) toast("请先连接相机") else showMeteringSheet { label ->
                meterRowRef?.value?.text = label
            }
        }
        meterRowRef = meterRow
        card.addView(meterRow.view)

        var storageRowRef: SheetValueRow? = null
        val storageEntry = sheetValueRow("存储卡", "读取中…") { _ ->
            if (!isConnected) toast("请先连接相机") else showStorageSheet { summary ->
                storageRowRef?.value?.text = summary
            }
        }
        storageRowRef = storageEntry
        card.addView(storageEntry.view)

        // 机身状态：15 条只读状态量单独一页（一次要问机身十几趟，不该拖慢这一页）
        val statEntry = sheetValueRow(
            "机身状态（只读）",
            // 值列很窄，写「闪光灯 / 方向 / 缓冲 · 16 项」会被截成「… · 16」
            "${bodyStatusItems.size} 项 · 只读"
        ) { _ ->
            if (!isConnected) toast("请先连接相机") else showBodyStatusSheet()
        }
        card.addView(statEntry.view)

        // 一张卡里放三项说明，合并成一小段，别让提示比数据还长
        card.addView(sheetHint(
            "镜头规格是按机身报的焦距范围（0xD0E3/E4）+ 光圈范围（0xD0E5/E6）拼的，" +
                "不是镜头型号名 —— PTP 里没有「镜头型号」这种字符串字段。\n" +
                "快门数来自最近一张照片的 EXIF（机身没有这个属性），点一下才读。"
        ))

        val card2 = sheetCard(page.content, "当前连接")
        val (makerRow, makerValue) = sheetInfoRow("厂商", deviceInfoCache?.manufacturer ?: "NIKON CORPORATION")
        // 值列很窄，长句会被裁掉（"机身需设为 MTP/P"），协议名只写短的，细节放下面的提示
        val (linkRow, linkValue) = sheetInfoRow("协议", "PTP over USB")
        card2.addView(makerRow); card2.addView(linkRow)

        // 断开连接放在这里：原来它是顶部一整条按钮，太容易误触，
        // 而"断开"本来就是针对这台机身的操作，归到机身信息里最自然。
        val discRow = sheetValueRow("断开连接", "点击断开") { _ ->
            page.dialog.dismiss()
            disconnect()
        }
        discRow.value.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        card2.addView(discRow.view)

        page.show()
        thread {
            // DeviceInfo 偶发读空（会话刚建立时管道里有残留），补一次重试再判失败
            var info = ptpConnection?.readDeviceInfo()
            if (info == null) {
                Thread.sleep(600)
                info = ptpConnection?.readDeviceInfo()
            }
            deviceInfoCache = info
            runOnUiThread {
                if (info == null) {
                    modelValue.text = "读取失败"
                    verValue.text = "--"
                    serialValue.text = "--"
                } else {
                    modelValue.text = info.model.ifBlank { "--" }
                    verValue.text = info.versionOrDash()
                    serialValue.text = info.serialOrDash()
                    makerValue.text = info.manufacturer.ifBlank { "--" }
                }
            }

            // ---- 镜头规格（只读，读不到就写"不支持"，不猜）----
            val focalMin = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_LENS_FOCAL_MIN) ?: -999999
            val focalMax = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_LENS_FOCAL_MAX) ?: -999999
            val apMin = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_LENS_APERTURE_MIN) ?: -999999
            val apMax = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_LENS_APERTURE_MAX) ?: -999999
            runOnUiThread {
                lensSpecValue.text = lensSpec(focalMin, focalMax, apMin, apMax)
            }

            // 释放模式：确认 App 改完之后机身停在哪一挡（尤其是"有没有从 Mup 恢复回来"）
            val scMode = try {
                ptpConnection?.getStillCaptureMode() ?: -999999
            } catch (_: Exception) {
                -999999
            }
            runOnUiThread {
                scValue.text = releaseModeLabel(scMode)
                if (scMode == PtpConstants.STILL_CAPTURE_MIRROR_UP ||
                    scMode == PtpConstants.STILL_CAPTURE_SELF_TIMER
                ) {
                    // 停在 Mup / 自拍挡多半是上一次自动恢复没成功，标红提醒
                    scValue.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                }
            }

            // 测光模式（0x500B）：机身报只读，这里只显示当前档
            val meter = try {
                ptpConnection?.getDevicePropValue(PtpConstants.PROP_METERING_MODE) ?: -999999
            } catch (_: Exception) {
                -999999
            }
            currentMetering = meter
            runOnUiThread {
                meterRowRef?.value?.text = if (meter == -999999) "不支持" else meteringLabel(meter.toLong())
            }

            // 存储卡：容量 + 约可拍张数（同一份缓存留给「存储卡」二级页）
            val ids = try {
                ptpConnection?.getStorageIds() ?: IntArray(0)
            } catch (_: Exception) {
                IntArray(0)
            }
            val storageList = ArrayList<StorageInfo>()
            for (id in ids) {
                try {
                    ptpConnection?.getStorageInfo(id)?.let { storageList.add(it) }
                } catch (_: Exception) {
                    // 单张卡读失败不影响别的卡
                }
            }
            storages = storageList
            runOnUiThread {
                storageRowRef?.value?.text = when {
                    storageList.isEmpty() -> "未检测到存储卡"
                    storageList.size == 1 -> "%.1f GB · 约可拍 %d 张"
                        .format(storageList[0].freeGb, storageList[0].freeImages)
                    else -> "共 %.1f GB · 约可拍 %d 张"
                        .format(storageList.sumOf { it.freeGb }, storageList.sumOf { it.freeImages })
                }
            }
        }
    }

    /**
     * 用焦距 / 光圈范围拼出镜头规格串，例如 `24–70 mm · f/2.8`。
     *
     * ⚠️ 这是**从机身报的范围推出来的规格**，不是型号名 —— PTP 里没有型号字符串字段。
     * 原始值按尼康惯例是 mm×100 / f×100；折算结果落在合理区间才用，否则退回原始数字
     * （单位猜错时让人看到 2400，比看到"2.4 mm"更容易判断）。
     */
    private fun lensSpec(focalMin: Int, focalMax: Int, apMin: Int, apMax: Int): String {
        fun mm(v: Int): String? {
            if (v <= 0 || v == -999999) return null
            val f = v / 100f
            return if (f in 1f..2000f) "%.0f".format(f) else v.toString()
        }
        fun ap(v: Int): String? {
            if (v <= 0 || v == -999999) return null
            val f = v / 100f
            return if (f in 0.5f..128f) "f/%.1f".format(f) else "f/$v"
        }
        val f1 = mm(focalMin)
        val f2 = mm(focalMax)
        val a1 = ap(apMin)
        val a2 = ap(apMax)
        val focal = when {
            f1 != null && f2 != null && f1 != f2 -> "$f1–$f2 mm"
            f1 != null -> "$f1 mm"
            f2 != null -> "$f2 mm"
            else -> null
        }
        val aperture = when {
            a1 != null && a2 != null && a1 != a2 -> if (a1 == a2) a1 else "$a1–$a2"
            a1 != null -> a1
            a2 != null -> a2
            else -> null
        }
        return listOfNotNull(focal, aperture).joinToString(" · ").ifBlank { "未读到" }
    }

    /**
     * 只读诊断：机身有没有哪个属性直接就是快门数。
     *
     * 四份分析资料交叉的结论是"没有"：固件 PTP 调度表只有 21 个厂商 opcode
     * （0x90C0–0x9206），**没有任何读 EEPROM 的通道**；Type0001 的 226 项属性表里
     * 超出 0xD1FF 的只有 0xD200/0xD201（Picture Control，D3 时代），跟快门数无关。
     *
     * 但结论还差一次真机闭环，所以这里做两件事：
     * 1) 0x90CA 让机身自己报出支持的属性码（比盲扫 0xD000–0xD1FF 准，也不受范围限制）；
     * 2) 逐条读值，挑出落在"快门数合理区间"里的码 —— 目标是本机已知的 78444。
     *
     * 结果只写日志，不改任何设置。全都不匹配，就可以确定售后工具那个
     * "通过 USB 读快门数"也只是读照片 EXIF（等于 [loadShutterCount] 的做法）。
     */
    private fun probeShutterCountProp() {
        val conn = ptpConnection ?: return
        val wasPaused = isPollingPaused
        isPollingPaused = true
        thread {
            try {
                Thread.sleep(200)
                val codes = conn.getVendorPropCodes()
                if (codes == null) {
                    log("诊断：0x90CA 没有返回数据（机身或会话不支持这条命令）")
                } else {
                    log("诊断：0x90CA 厂商属性清单 ${codes.size} 项 → " +
                        codes.joinToString(" ") { "0x%04X".format(it) })
                }

                // 清单拿不到就退回按范围扫（这次把 0xD200 以上也覆盖进来，
                // 之前真机 dump 只扫到 0xD1FF，范围外的码从来没被问过）
                val targets = when {
                    codes != null && codes.isNotEmpty() -> codes.toList()
                    else -> (0xD000..0xD2FF).toList()
                }
                log("诊断：开始逐条读值，共 ${targets.size} 项…")

                var dead = 0
                var hits = 0
                for (c in targets) {
                    val v = conn.getDevicePropValue(c)
                    if (v == -999999) {
                        // 连续问不到就停：机身掉线时继续硬问只会把端点拖进 STALL
                        if (++dead >= 12) { log("诊断：连续 12 项无应答，中止"); break }
                        continue
                    }
                    dead = 0
                    // 快门数的量级：几千到几十万次（本机 78444）
                    if (v in 1_000..5_000_000) {
                        hits++
                        val mark = if (v in 70_000..90_000) "  ← 接近 78444" else ""
                        log("诊断候选：0x%04X = %d%s".format(c, v, mark))
                    }
                }
                log("诊断完成：${targets.size} 项里 $hits 个值落在快门数量级（只写日志，未改任何设置）")
            } catch (e: Exception) {
                log("诊断出错：${e.message}")
            } finally {
                conn.deviceReady()
                isPollingPaused = wasPaused
            }
        }
    }

    /**
     * 读快门释放次数（点「快门数」那一行才触发）。
     *
     * 两条路，**先走不碰 USB 的那条**：
     * 1) 手机里已经下载过的 D300 照片 —— 直接本地解析 EXIF，零传输、不受相机状态影响；
     * 2) 本地没有，才从机身拉卡上最新一张。
     *
     * ⚠️ 机身那条路只能整张读取：0x101B 分段读取 D300 直接回"无数据"（真机实测），
     * 一张 NEF 十几 MB；而且大传输被打断过几次，相机就进 STALL，之后的命令全发不出去。
     * 所以本地能读就绝不碰机身。读取期间还要让 2 秒轮询让路，否则抢通道会搅成一串超时。
     */
    private fun loadShutterCount(onText: (String) -> Unit) {
        if (shutterCount >= 0) { onText("$shutterCount 次"); return }
        if (shutterCountReading) { onText("读取中…"); return }
        if (!isConnected) { onText("未连接"); return }
        shutterCountReading = true
        onText("读取中…")
        thread {
            // ---- 路径 1：手机里已下载的 D300 照片 ----
            val local = readShutterCountFromPhone()
            if (local != null) {
                shutterCount = local
                shutterCountReading = false
                runOnUiThread { onText("$local 次") }
                return@thread
            }

            // ---- 路径 2：从机身拉一张（慢、有风险，兜底用）----
            val wasPaused = isPollingPaused
            isPollingPaused = true
            var text = "读不到"
            try {
                Thread.sleep(200)
                val handles = ptpConnection?.collectPhotoHandles() ?: IntArray(0)
                if (handles.isEmpty()) {
                    text = "读不到（卡里没有照片）"
                } else {
                    val handle = handles.max()
                    val info = ptpConnection?.getObjectInfo(handle)
                    runOnUiThread { onText("读取原图中…") }
                    val bytes = ptpConnection?.getObject(handle, info?.byteSize ?: 0)
                    val n = bytes?.let { ShutterCountReader.read(it) }
                    if (n != null) {
                        shutterCount = n
                        log("快门数：$n 次（来源 ${info?.filename ?: "#$handle"}）")
                        text = "$n 次"
                    } else {
                        log("快门数：读取失败（${bytes?.size ?: 0} 字节，未找到 0xA7 字段）")
                        text = "读不到"
                    }
                }
            } catch (e: Exception) {
                log("快门数读取出错：${e.message}")
                text = "读取失败"
            } finally {
                // 大传输之后机身要喘口气，否则紧接着的列举/读属性都会撞忙
                ptpConnection?.deviceReady()
                isPollingPaused = wasPaused
                shutterCountReading = false
            }
            runOnUiThread { onText(text) }
        }
    }

    /**
     * 从手机相册里找已下载的 D300 照片（DSC_*）解析快门数。
     *
     * 只看最近几张：快门数在每张照片里都有，读最新那张即可；
     * 一次 8MB 的原图读取对手机内存也不算小，所以最多试 3 张就收手。
     * 解析不出来（不是尼康文件、或没有 MakerNote）继续试下一张。
     */
    private fun readShutterCountFromPhone(): Int? = try {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("DSC_%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            var tried = 0
            var found: Int? = null
            while (found == null && c.moveToNext() && tried < 3) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: "?"
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) continue
                tried++
                val n = ShutterCountReader.read(bytes)
                if (n != null) {
                    log("快门数：$n 次（来自手机内 $name）")
                    found = n
                } else {
                    log("手机内 $name 里没有快门数字段，试下一张")
                }
            }
            found
        }
    } catch (e: Exception) {
        log("查手机内照片出错：${e.message}")
        null
    }

    /**
     * 存储卡二级页：逐卡列容量，并把「格式化这张卡」直接放进每张卡的分组里。
     *
     * 格式化原来在参数页单占一行，但那个入口看不到"要格的是哪张卡、卡里还有多少东西" ——
     * 双卡机上等于闭着眼睛按删除。放到卡的详情里，容量/剩余和删除动作摆在同一个卡片上，
     * 少一次猜。格式化完成后这里就地重读，不用退出去再进来。
     */
    /**
     * @param onSummary 把"容量 · 约可拍"回填到调用方那一行（现在那行在「机身信息」里）
     */
    private fun showStorageSheet(onSummary: (String) -> Unit = {}) {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage("存储卡", "容量 / 剩余空间 / 格式化")
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.content.addView(host)
        val sheetStatus = sheetStatusText("正在读取存储卡…")
        page.content.addView(sheetStatus)
        page.show()
        renderStorageCards(host, sheetStatus, onSummary)
    }

    /** 读卡并把每张卡的卡片铺进 [host]；格式化成功后就地回调自己重绘 */
    private fun renderStorageCards(host: LinearLayout, sheetStatus: TextView, onSummary: (String) -> Unit = {}) {
        thread {
            val ids = ptpConnection?.getStorageIds() ?: IntArray(0)
            val list = ArrayList<StorageInfo>()
            for (id in ids) ptpConnection?.getStorageInfo(id)?.let { list.add(it) }
            storages = list
            runOnUiThread {
                host.removeAllViews()
                if (list.isEmpty()) {
                    sheetStatus.text = "没读到存储卡（机身没插卡，或卡不可用）"
                    onSummary("未检测到存储卡")
                    return@runOnUiThread
                }
                onSummary(
                    if (list.size == 1) "%.1f GB · 约可拍 %d 张".format(list[0].freeGb, list[0].freeImages)
                    else "共 %.1f GB · 约可拍 %d 张".format(
                        list.sumOf { it.freeGb }, list.sumOf { it.freeImages }
                    )
                )
                val single = list.size == 1
                sheetStatus.text = if (single) "已读取存储卡" else "共 ${list.size} 张卡"
                list.forEachIndexed { idx, s ->
                    // 标题只用"卡序 + 容量"：机身报回来的卷标不可靠
                    // （实测两次读到 019004 / 一串文件名），拿它当标题会让人以为卡换了
                    // 单卡时不出现"卡 1"——机器上只有一张卡，编号只会让人以为还有第二张
                    val title = if (single) "存储卡 · %.1f GB".format(s.capacityGb)
                                else "卡 ${idx + 1} · %.1f GB".format(s.capacityGb)
                    val card = sheetCard(host, title)
                    val (capRow, capValue) = sheetInfoRow("总容量", "%.1f GB".format(s.capacityGb))
                    val (freeRow, freeValue) = sheetInfoRow("剩余空间", "%.1f GB".format(s.freeGb))
                    val (shotRow, shotValue) = sheetInfoRow("可拍张数", "${s.freeImages} 张")
                    capValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    freeValue.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                    shotValue.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                    card.addView(capRow); card.addView(freeRow); card.addView(shotRow)

                    if (s.readOnly) {
                        // CF 卡没有机械写保护开关（只有 SD 卡才有），所以别一律甩锅给"开关"：
                        // 卡槽 1 是 CF、卡槽 2 是 SD（在电池仓里），两种卡的原因分开说
                        card.addView(sheetHint(
                            "这张卡机身报为只读，不能写入也不能格式化。\n" +
                            "CF 卡没有写保护开关，多半是文件系统损坏或卡本身出问题 —— 换张卡试试；\n" +
                            "若是插在电池仓里那个 SD 槽的卡，才需要看卡侧面的写保护拨片。"
                        ))
                    } else {
                        // 卡片内分隔线：上面是只读信息，下面是会动数据的动作
                        card.addView(View(this).apply {
                            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.stroke))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                (1 * resources.displayMetrics.density).toInt()
                            ).apply { topMargin = (10 * resources.displayMetrics.density).toInt(); bottomMargin = (4 * resources.displayMetrics.density).toInt() }
                        })
                        val fmt = sheetValueRow("格式化这张卡", "不可撤销") { _ ->
                            showFormatConfirmSheet(idx, s, single) {
                                renderStorageCards(host, sheetStatus, onSummary)
                            }
                        }
                        fmt.value.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                        card.addView(fmt.view)
                    }
                }
            }
        }
    }

    /**
     * 格式化的第二道确认：把"哪张卡、卡里有多少东西、删了没法恢复"摊开，
     * 用户点红色按钮才真正发 0x100F。这是全 App 唯一不可撤销的操作。
     */
    private fun showFormatConfirmSheet(slot: Int, s: StorageInfo, single: Boolean, onDone: () -> Unit) {
        if (!isConnected) { toast("请先连接相机"); return }
        // 只有一张卡时统一说"存储卡"，不提序号
        val who = if (single) "存储卡" else "卡 ${slot + 1}"
        val page = buildSheetPage("格式化$who", "会删除卡内全部照片，无法恢复")
        val card = sheetCard(page.content, "确认要删除的内容")

        val (sizeRow, sizeValue) = sheetInfoRow(
            "卡内数据",
            "已用 %.1f GB / 共 %.1f GB".format(s.usedGb, s.capacityGb)
        )
        sizeValue.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        card.addView(sizeRow)
        val (shotRow, shotValue) = sheetInfoRow("当前可拍", "${s.freeImages} 张")
        card.addView(shotRow)
        card.addView(sheetHint(
            "格式化会清空${who}上的所有照片和文件夹（含受保护的照片），\n" +
            "机身侧的这一步没有撤销，App 也恢复不了。需要留的照片先传到手机或电脑。"
        ))

        val sheetStatus = sheetStatusText("点下方红色按钮才会真正执行")
        page.content.addView(sheetStatus)

        val btn = sheetDangerButton("确认格式化$who")
        btn.isEnabled = !s.readOnly
        btn.alpha = if (s.readOnly) 0.4f else 1f
        page.content.addView(btn)

        page.show()

        btn.setOnClickListener {
            if (s.readOnly) { toast("这张卡是只读的，格式化不了"); return@setOnClickListener }
            btn.isEnabled = false
            btn.text = "正在格式化…机身可能需要十几秒"
            sheetStatus.text = "正在格式化，请不要拔线…"
            thread {
                val code = ptpConnection?.formatStore(s.storageId) ?: -1
                runOnUiThread {
                    if (code == PtpConstants.RESP_OK) {
                        sheetStatus.text = "格式化完成"
                        toast("已格式化：$who")
                        log("格式化完成：$who（storage=0x${Integer.toHexString(s.storageId).uppercase()}）")
                        refreshDeviceAndStorage()
                        page.dialog.dismiss()
                        onDone()
                    } else {
                        val hex = if (code == -1) "无响应" else "0x${Integer.toHexString(code).uppercase()}"
                        sheetStatus.text = "格式化失败（$hex）"
                        log("格式化失败：$hex")
                        btn.isEnabled = true
                        btn.text = "确认格式化$who"
                    }
                }
            }
        }
    }

    /**
     * 重设个人设定 —— 第二道确认页（和格式化存储卡同一套）。
     *
     * 机身菜单里它是「否 / 是」两档，但那是**一次性动作**：写 1 当前个人设定库
     * 立刻恢复出厂，机身不给第二次确认，也没有撤销。所以这里不排档位块，
     * 而是把后果摊开、只留一颗红色按钮 —— 不点就什么都不会发生。
     */
    private fun showCsmResetSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage("重设个人设定", "当前个人设定库恢复到出厂值")
        val card = sheetCard(page.content, "会重设什么")

        // 先把当前库读出来再摊牌：告诉用户重设的是 A/B/C/D 里的哪一个
        val bankRow = sheetInfoRow("当前个人设定库", "读取中…")
        card.addView(bankRow.first)
        card.addView(sheetHint(
            "a–f 六组全部项目（自动对焦、测光曝光、包围闪光、控制按键…）都会回到出厂默认值，\n" +
                "你一项项调过的设置全部丢失，机身不给撤销 —— 想留一份就先把各项值记下来。\n" +
                "照片和储存卡不受影响。"
        ))

        val sheetStatus = sheetStatusText("点下方红色按钮才会真正执行")
        page.content.addView(sheetStatus)

        val btn = sheetDangerButton("确认重设个人设定")
        page.content.addView(btn)
        page.show()

        thread {
            val desc = ptpConnection?.readPropDesc(PtpConstants.CSM_BANK_SELECT)
            runOnUiThread {
                val names = mapOf(0 to "A", 1 to "B", 2 to "C", 3 to "D")
                val cur = desc?.currentValue?.toInt()
                bankRow.second.text = if (cur != null) "${names[cur] ?: cur} 库" else "读取失败"
            }
        }

        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "正在重设…"
            sheetStatus.text = "正在重设，请不要拔线…"
            thread {
                // 宽度按机身声明的类型来；没声明就按 UINT8 试，不行再 UINT16
                val type = ptpConnection?.readPropDesc(PtpConstants.CSM_RESET_BANK)?.dataType ?: 0
                val sizes = if (type == 3 || type == 4) listOf(2, 1) else listOf(1, 2)
                var ok = false
                for (s in sizes) {
                    ok = ptpConnection?.setDevicePropValue(
                        PtpConstants.CSM_RESET_BANK, 1, size = s, logDesc = "", maxRetries = 1
                    ) ?: false
                    if (ok) break
                }
                val err = ptpConnection?.lastSetPropError ?: 0
                runOnUiThread {
                    if (ok) {
                        sheetStatus.text = "已重设：当前个人设定库恢复出厂值"
                        toast("已重设个人设定")
                        log("重设个人设定：已恢复到出厂值")
                        page.dialog.dismiss()
                    } else {
                        val hex = if (err == 0) "机身没应答" else "0x${Integer.toHexString(err).uppercase()}"
                        sheetStatus.text = "重设失败（$hex）"
                        log("重设个人设定失败：$hex")
                        btn.isEnabled = true
                        btn.text = "确认重设个人设定"
                    }
                }
            }
        }
    }

    /**
     * 重设拍摄菜单（0xD015 ResetShootingMenu）—— 和「重设个人设定」同一套二次确认。
     *
     * 为什么不做成档位块：它是**一次性动作**（写 1 就把当前拍摄菜单库恢复出厂），
     * 排成「否 / 是」两档等于把一颗"删除"按钮伪装成一个开关，误触没有挽回余地。
     * 这里只留一颗红按钮，不点就什么都不发生。
     */
    private fun showShootingResetSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage("重设拍摄菜单", "当前拍摄菜单库恢复到出厂值")
        val card = sheetCard(page.content, "会重设什么")

        val bankRow = sheetInfoRow("当前拍摄菜单库", "读取中…")
        card.addView(bankRow.first)
        card.addView(sheetHint(
            "影像品质 / 尺寸、白平衡、优化校准、降噪、动态 D-Lighting、多重曝光、" +
                "间隔定时这些拍摄设置全部回到出厂默认值，\n" +
                "你调过的一整库设置都会丢，机身不给撤销。\n" +
                "照片、储存卡、个人设定菜单（那是另一条「重设个人设定」）都不受影响。"
        ))

        val sheetStatus = sheetStatusText("点下方红色按钮才会真正执行")
        page.content.addView(sheetStatus)

        val btn = sheetDangerButton("确认重设拍摄菜单")
        page.content.addView(btn)
        page.show()

        thread {
            val desc = ptpConnection?.readPropDesc(PtpConstants.PROP_NIKON_SHOOTING_BANK)
            runOnUiThread {
                val names = mapOf(0 to "A", 1 to "B", 2 to "C", 3 to "D")
                val cur = desc?.currentValue?.toInt()
                bankRow.second.text = if (cur != null) "${names[cur] ?: cur} 库" else "读取失败"
            }
        }

        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "正在重设…"
            sheetStatus.text = "正在重设，请不要拔线…"
            thread {
                val type = ptpConnection?.readPropDesc(PtpConstants.PROP_NIKON_RESET_SHOOTING_MENU)?.dataType ?: 0
                val sizes = if (type == 3 || type == 4) listOf(2, 1) else listOf(1, 2)
                var ok = false
                for (s in sizes) {
                    ok = ptpConnection?.setDevicePropValue(
                        PtpConstants.PROP_NIKON_RESET_SHOOTING_MENU, 1, size = s, logDesc = "", maxRetries = 1
                    ) ?: false
                    if (ok) break
                }
                val err = ptpConnection?.lastSetPropError ?: 0
                runOnUiThread {
                    if (ok) {
                        sheetStatus.text = "已重设：当前拍摄菜单库恢复出厂值"
                        toast("已重设拍摄菜单")
                        log("重设拍摄菜单：已恢复到出厂值")
                        // 菜单值全变了，重开一次让每一行重新读一遍
                        page.dialog.dismiss()
                        if (isConnected) showShootingMenuSheet()
                    } else {
                        val hex = if (err == 0) "机身没应答" else "0x${Integer.toHexString(err).uppercase()}"
                        sheetStatus.text = "重设失败（$hex）"
                        log("重设拍摄菜单失败：$hex")
                        btn.isEnabled = true
                        btn.text = "确认重设拍摄菜单"
                    }
                }
            }
        }
    }

    // =====================================================================
    // 参数页新增的三组行：曝光与画质 / 机身与存储 / 拍摄流程
    // =====================================================================

    /**
     * 这三组行都放在参数页的卡片里（XML 只留空容器），行控件统一用 [sheetValueRow]：
     * 跟白平衡、包围曝光那些二级页同一套 48dp 行高、同样的强调色数值和箭头。
     * 这样以后加一门设置只要加一行，不用再写一段 XML。
     */
    private companion object {
        /** 空闲时读一轮属性的间隔（毫秒）。别调太小：相机是被这个"问"住的 */
        const val POLL_IDLE_INTERVAL_MS = 5000L

        const val AF_PREFS = "af_prefs"
        const val AF_KEY_51 = "af_count_51"
        /** 触摸对焦开关：开着时改对焦点会自动对焦 */
        const val AF_KEY_TOUCH_AF = "touch_af"
        const val LV_MODE_KEY = "lv_mode"
        const val LV_GRID_KEY = "lv_grid"
        /** 取景辅助：0 = 峰值对焦，1 = 过曝斑马纹 */
        const val PEAK_MODE_KEY = "peak_mode"
        const val PEAK_THRESHOLD_KEY = "peak_threshold"
        const val ZEBRA_THRESHOLD_KEY = "zebra_threshold"
        const val PEAK_COLOR_KEY = "peak_color"

        /** 反光板预升可选的等待时长（秒）。1 秒是机身 d9 的口径，长曝/微距往上加 */
        val MIRROR_UP_DELAYS = listOf(1, 2, 3, 5, 10)
    }

    private fun setupParamExtraRows() {
        // 上次用过的流程设置：自拍延时/间隔张数这些不该每次重进 App 都归零
        val sp = getSharedPreferences("workflow", Context.MODE_PRIVATE)
        selfTimerSec = sp.getInt("selfTimer", 0)
        intervalCount = sp.getInt("intervalCount", 5)
        intervalSec = sp.getInt("intervalSec", 5)
        autoDownload = sp.getBoolean("autoDownload", false)
        autoDownloadRaw = sp.getBoolean("autoDownloadRaw", false)
        mirrorUpEnabled = sp.getBoolean("mirrorUp", false)
        mirrorUpDelaySec = sp.getInt("mirrorUpDelay", 2)
        focusStackCount = sp.getInt("focusStackCount", 5)
        focusStackStep = sp.getInt("focusStackStep", 2)
        focusStackNearFirst = sp.getBoolean("focusStackNearFirst", true)
        focusStackWaitSec = sp.getInt("focusStackWait", 2)

        // ---- 曝光与画质 ----
        val eqList = findViewById<LinearLayout>(R.id.exposureQualityList)
        // 白平衡归在这一组：它调的是色彩还原，属于曝光画质这一挂；
        // 以前和「存储卡」同组（那组叫"机身"），两件事摆一起没人看得懂。
        // 右侧常显机身当前模式 —— 光写"白平衡"三个字等于没说现在是自动还是晴天。
        wbRow = sheetValueRow("白平衡", "待读取") { _ ->
            if (!isConnected) toast("请先连接相机") else showWhiteBalanceDialog()
        }
        eqList.addView(wbRow.view)

        // 测光模式 / 存储卡都搬到「机身信息」了：一个是机身当前状态，一个是机身里的卡，
        // 都不属于"这次拍摄要调的参数"。

        flashCompRow = sheetValueRow("闪光补偿", "待读取") { _ ->
            if (!isConnected) toast("请先连接相机") else showEnumPropSheet(
                "闪光补偿", "0xD126 · 内置/外接闪光灯曝光补偿",
                PtpConstants.PROP_NIKON_FLASH_COMP, ::flashCompLabel,
                "补偿只影响闪光输出，不影响环境光曝光。写入后对一下机身屏幕上的 ± 值。"
            ) { label -> flashCompRow.value.text = label }
        }
        eqList.addView(flashCompRow.view)

        imageQualityRow = sheetValueRow("影像尺寸", "待读取") { _ ->
            if (!isConnected) toast("请先连接相机") else showImageSizeSheet()
        }
        eqList.addView(imageQualityRow.view)

        // 影像品质（0x5004）：和上一行不是一回事 ——
        // 0x5003 是尺寸（大/中/小），这一项是画质模式（JPEG 三档 / TIFF / RAW / RAW+JPEG 三档）。
        // 八挡全部照官方规格书 §5.5.1.3 的值表译成中文，不再露 0x0003 这种原始码。
        compressionRow = sheetValueRow("影像品质", "待读取") { _ ->
            if (!isConnected) toast("请先连接相机") else showEnumPropSheet(
                "影像品质", "0x5004 · JPEG / TIFF / RAW 及其组合",
                PtpConstants.PROP_COMPRESSION_SETTING, ::compressionLabel,
                "机身上「影像品质」那一项：JPEG 三档压缩率、TIFF(RGB)、RAW，以及 RAW+JPEG 三种组合，" +
                    "共八挡。拍 RAW 组合时卡里会同时落一个 NEF 和一个 JPEG。"
            ) { label -> compressionRow.value.text = label }
        }
        eqList.addView(compressionRow.view)

        // 记录媒介：官方规格书明文——这个属性**只影响"用手按机身快门"时照片去哪**；
        // 用命令拍摄时（0x100E）恒定落卡，与它无关。所以这一行不是"App 拍照的开关"，
        // 早先的说明写成"切完拍一张看不到就换一档"，是把原因和结果搞反了，已改。
        mediaRow = sheetValueRow("记录媒介", "待读取") { _ ->
            if (!isConnected) toast("请先连接相机") else showEnumPropSheet(
                "记录媒介", "0xD10B · 只管机身快门，管不到 App 拍摄",
                PtpConstants.PROP_NIKON_RECORDING_MEDIA, ::mediaLabel,
                "官方规格书：这一项只在你「用手按机身快门」时决定照片写哪儿。" +
                    "App 里按快门走 0x100E，恒定写存储卡，切换它不会改变 App 拍的照片去向。" +
                    "所以别再拿它当「拍了但卡里没有」的开关 —— 那种情况先查卡是否写满、是否写保护。"
            ) { label -> mediaRow.value.text = label }
        }
        eqList.addView(mediaRow.view)

        // ---- 对焦点数量（原取景框右上角的 11/51 chip，已挪到参数页）----
        val afHost = findViewById<LinearLayout>(R.id.afCountList)
        val (afBox, afGrid) = inlineTileField(
            afHost, "对焦点数量", listOf("51 点", "11 点"), if (afPointCount51) 0 else 1, 2
        ) { pos ->
            if (!isConnected) {
                toast("请先连接相机")
                renderAfPointRow()
            } else {
                setAfPointCount(pos == 0)
            }
        }
        afPointCountBox = afBox
        afPointCountGrid = afGrid

        // ---- 取景框显示（构图网格）----
        val gridHost = findViewById<LinearLayout>(R.id.lvGridList)
        lvGridRow = sheetValueRow("取景框显示", lvGridLabel(lvGridStyle)) { _ ->
            showLvGridSheet()
        }
        gridHost.addView(lvGridRow.view)

        // 触摸对焦：取景画面左上角有一枚同样的 chip，但"未取景时那块是空的"，
        // 想先设好再进取景的人就找不到入口。参数页给一份常驻的，两处状态同步。
        touchAfRow = sheetValueRow("触摸对焦", if (touchAfEnabled) "开启" else "关闭") { _ ->
            toggleTouchAf()
        }
        gridHost.addView(touchAfRow.view)
        renderAfPointRow()

        // ---- 存储 ----
        // 「存储卡」那一行搬到「机身信息」了（卡是机身的部件，不是拍摄参数）；
        // 格式化也一并在那边「存储卡」二级页里，针对某张卡操作，不会按错卡。
        // 这一组现在只剩菜单入口，卡片标题也跟着改成「机身菜单」。

        // ---- 个人设定：a–f 共 50 项收进二级页，参数页只留这一行 ----
        val csmEntryHost = findViewById<LinearLayout>(R.id.csmEntryList)
        val csmEntry = sheetValueRow("个人设定菜单", "${CsmMenu.items.size} 项") { _ ->
            if (!isConnected) toast("请先连接相机") else showCsmSheet()
        }
        csmEntryHost.addView(csmEntry.view)

        // 拍摄菜单走同一套：这些项在 libgphoto2 里都有官方属性名，直接读描述符即可
        val shootEntry = sheetValueRow("拍摄菜单", "${ShootingMenu.items.size} 项") { _ ->
            if (!isConnected) toast("请先连接相机") else showShootingMenuSheet()
        }
        csmEntryHost.addView(shootEntry.view)

        // 设置菜单：版权 / 方向传感器 / 非 CPU 镜头 / AF 微调 / 柔性程序 / 光圈快门锁定 / AE-L 状态
        val setupEntry = sheetValueRow("设置菜单", "${SetupMenu.items.size} 项") { _ ->
            if (!isConnected) toast("请先连接相机") else showSetupMenuSheet()
        }
        csmEntryHost.addView(setupEntry.view)

        // ---- NCP 滤镜导入 ----
        // 尼康的「个人设定优化校准」就是装在卡根目录 PICCON01…99.NCP 上的滤镜文件，
        // 机身那边再走 管理优化校准 → 载入/保存 → 复制到相机 装进 C-1…C-9
        // （说明书第 158–160 页）。App 能做的是把手机上这个 .ncp 传进卡里的插槽。
        val ncpEntry = sheetValueRow("NCP 滤镜导入", "选 .ncp 传到卡上") { _ ->
            if (!isConnected) {
                toast("请先连接相机")
            } else {
                runCatching { ncpPicker.launch(arrayOf("*/*")) }
                    .onFailure { toast("打不开文件选择器：${it.message}") }
            }
        }
        csmEntryHost.addView(ncpEntry.view)

        // 优化校准原始数据（0x90CC 只读）：能看到机身里每个 PicCtrl 项的数据大小与名称
        val picCtrlEntry = sheetValueRow("优化校准数据", "只读 · 看机身里存了什么") { _ ->
            if (!isConnected) toast("请先连接相机") else showPicCtrlSheet()
        }
        csmEntryHost.addView(picCtrlEntry.view)

        // ---- 机身时钟 ----
        // 机身内部电池掉电后时间会归零，EXIF 里就全是 2000 年 —— 事后没法补救，
        // 所以给它一个固定入口，而不是等人在日志里发现。
        val clockEntry = sheetValueRow("机身时钟", "校准 EXIF 时间戳") { _ ->
            if (!isConnected) toast("请先连接相机") else showCameraClockSheet()
        }
        csmEntryHost.addView(clockEntry.view)

        // ---- 诊断包 ----
        // 出问题时最有信息量的不是截图，是"机身自述了什么 + 出事前那几百行日志"。
        // 不依赖连接也能导（未连接时导出的是手机侧信息 + 日志）。
        val diagEntry = sheetValueRow("导出诊断包", "存成 txt 便于排查") { _ -> exportDiagnostics() }
        csmEntryHost.addView(diagEntry.view)

        // ---- 备份 / 恢复 ----
        // 换装正式签名的包必须卸载旧的，卸载就清空 AF 标定 —— 先导出再装。
        val backupEntry = sheetValueRow("备份与恢复", "标定 · 属性码 · 流程设置") { _ -> showBackupSheet() }
        csmEntryHost.addView(backupEntry.view)

        // ---- 使用说明 / 关于 ----
        // 放在参数页最底下：不依赖相机连接，也没人天天点，
        // 但"第一次装上不知道干嘛"的时候必须能从主界面摸到，不能只藏在系统菜单里。
        val helpEntry = sheetValueRow("使用说明", "连接 · 拍摄 · 相册") { _ ->
            InfoActivity.open(this, InfoActivity.PAGE_HELP)
        }
        csmEntryHost.addView(helpEntry.view)

        val aboutEntry = sheetValueRow("关于", "版本 · 适配机身 · 边界") { _ ->
            InfoActivity.open(this, InfoActivity.PAGE_ABOUT)
        }
        csmEntryHost.addView(aboutEntry.view)

        // 重设个人设定不放在参数页：它属于个人设定菜单（机身菜单里也在那一页的末尾），
        // 现在是 showCsmSheet() 的最后一行 —— 见那里的说明。

        // ---- 拍摄流程（三行都进同一个二级页，入口好找）----
        val wfList = findViewById<LinearLayout>(R.id.workflowList)
        // 自拍延时：参数页直接一排方框选 —— 原来点进去还得再弹一次下拉，
        // 五个选项没必要藏两层。
        val (timerBox, timerGrid) = inlineTileField(
            wfList, "自拍延时", SELF_TIMER_OPTIONS.map { selfTimerLabel(it) },
            selfTimerSecIndex(),
            columns = SELF_TIMER_OPTIONS.size
        ) { pos ->
            selfTimerSec = SELF_TIMER_OPTIONS[pos]
            saveWorkflowSettings()
            refreshWorkflowRows()
            // 自拍是**机身**执行的：0xD063 写时长、0x5013=0x8011 切自拍模式，
            // 拍完自动恢复 —— 所以机身会在倒计时期间响提示音、闪对焦辅助灯。
            log(
                if (selfTimerSec > 0) "自拍延时 ${selfTimerSec} 秒 —— 由机身执行（0xD063 + 0x5013=0x8011），拍完恢复原释放模式"
                else "自拍延时已关闭"
            )
        }
        selfTimerBox = timerBox
        selfTimerGrid = timerGrid
        intervalRow = sheetValueRow("间隔拍摄", "5 张 · 每 5 秒") { _ -> showIntervalSheet() }
        wfList.addView(intervalRow.view)
        autoDownloadRow = sheetValueRow("拍完自动下载", "关闭") { _ -> showAutoDownloadSheet() }
        wfList.addView(autoDownloadRow.view)

        // 反光板预升：走机身的 Mup 释放模式，由 App 连按两次快门
        mirrorUpRow = sheetValueRow("反光板预升", "关闭") { _ -> showMirrorUpSheet() }
        wfList.addView(mirrorUpRow.view)

        // 焦点包围：机身没有这个功能（没有"拍一张把焦点往前挪一点"的指令），
        // 只能由 App 逐站选对焦点 + 逐张拍，后期再合成。
        focusStackRow = sheetValueRow("焦点包围", "5 张 · 每步 2 点") { _ -> showFocusStackSheet() }
        wfList.addView(focusStackRow.view)

        refreshWorkflowRows()
    }

    // =====================================================================
    // 稳定性：状态条提示 + USB 断线自动重连
    // =====================================================================

    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var statusResetRunnable: Runnable? = null
    @Volatile
    private var isReconnecting = false

    // ------------------------------------------------------------------
    // USB 抖动（"自己断一下又自己连上"）
    //
    // 症状：用着用着状态条跳成"已断开连接"，一两秒后又自己连回来，反复发生。
    //
    // 原因不是 PTP 协议，是 **Android 的 USB 广播太急**：OTG 线被碰一下、
    // 或者手机给 USB 口断电再上电（锁屏 / 切后台 / 省电策略都会做），
    // 系统会先发 ACTION_USB_DEVICE_DETACHED，紧接着再发 ATTACHED。
    // 旧代码收到 DETACHED 就立刻 disconnect() —— 于是每一次抖动都变成
    // 一次完整的"断开 + 重新连接"，界面跟着闪一轮。
    //
    // 现在改成：拔出广播先挂 1.2 秒，到点再确认设备是不是真的没了；
    // 期间收到插入广播就取消，并按"抖动"处理 —— 悄悄重建会话，不走断开流程。
    // ------------------------------------------------------------------
    /** 轮询连续失败次数：只有**连续**两次以上才认定链路真断了 */
    private var pollFailStreak = 0
    /** 上次发起自动重连的时间，用来防止短时间内反复重连 */
    private var lastReconnectAt = 0L

    private val usbGoneRunnable = Runnable { confirmUsbGone() }
    /** 最近一次收到拔出广播的时间；0 = 近期没有 */
    private var usbGoneAt = 0L

    /** 拔出广播后**延迟**确认：设备还在就是抖动，别断开 */
    private fun confirmUsbGone() {
        val back = usbManager.deviceList.values.firstOrNull {
            it.vendorId == PtpConstants.USB_VENDOR_NIKON
        }
        if (back != null) {
            log("USB 抖动已自愈（设备仍在列表里），不断开")
            return
        }
        log("相机已断开（USB 拔出）")
        disconnect()
    }

    /**
     * 抖动恢复：设备重新枚举过，旧的 UsbDeviceConnection 已经作废，
     * 必须整个丢掉重开，否则后面的命令全打在死连接上（表现为"什么都发不出去"）。
     * 全程不弹"连接中断"，也不让界面回到未连接态 —— 对用户来说这只是一次抖动。
     */
    private fun reconnectAfterFlap(device: UsbDevice) {
        if (isReconnecting) return
        isReconnecting = true
        thread {
            try {
                runCatching { ptpConnection?.close() }
                ptpConnection = null
                PtpUsbConnection.activeInstance = null
                isConnected = false
                // 抖动之后机身多半已经自己退出取景了，标志跟着清掉，
                // 否则界面还显示"取景中"、按钮也还按取景那套走
                isLiveViewRunning = false
                isLiveViewStarting = false
                Thread.sleep(300)
                runOnUiThread {
                    if (usbManager.hasPermission(device)) connectToDevice(device)
                    else requestPermissionOrConnect(device)
                }
            } finally {
                isReconnecting = false
            }
        }
    }

    /**
     * 顶部状态条临时提示（红色表示出错），5 秒后自动回到"已连接/未连接"。
     *
     * 以前失败只在日志面板里留一行、再弹一个 Toast —— 日志面板默认是收起的，
     * Toast 一闪就没了，用户根本不知道该看哪儿。状态条是他一直在看的那一行。
     */
    private fun showTransientStatus(message: String, error: Boolean = false) {
        runOnUiThread {
            statusText.text = message
            statusText.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (error) R.color.accent_red else R.color.text_secondary
                )
            )
            statusResetRunnable?.let { statusHandler.removeCallbacks(it) }
            val r = Runnable { restoreStatusText() }
            statusResetRunnable = r
            statusHandler.postDelayed(r, 5000)
        }
    }

    private fun restoreStatusText() {
        statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        statusText.text = when {
            // 状态条左边还有电池和相册，可用宽度很小：这里只放最短状态，
            // 具体该做什么放在日志面板启动时那条提示里
            !isConnected -> "未连接"
            // 连上后直接显示机型 —— 它本身就是"机身信息"的入口
            deviceInfoCache != null -> deviceInfoCache?.shortName ?: "已连接"
            else -> "已连接"
        }
        // 尾部小箭头：告诉用户这一行可以点开
        val chevron = if (isConnected) ContextCompat.getDrawable(this, R.drawable.ic_chevron_right)?.mutate() else null
        chevron?.let {
            it.setTint(ContextCompat.getColor(this, R.color.text_disabled))
            val d = (11 * resources.displayMetrics.density).toInt()
            it.setBounds(0, 0, d, d)
        }
        statusText.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null)
        statusText.compoundDrawablePadding = (3 * resources.displayMetrics.density).toInt()
    }

    /**
     * USB 通信中断后的自动重连。
     *
     * 之前一遇到 USB Error 就直接 disconnect()，用户得自己再点一次连接 ——
     * 而 OTG 接触不良是这类相机的常态。这里退避重试 3 次：
     * 每次都重新找设备、确认权限，再走一遍完整会话建立（openSession 自带清管道重试）。
     */
    private fun attemptAutoReconnect(reason: String) {
        if (isReconnecting) return
        isReconnecting = true
        isPollingActive = false
        log("USB 通信失败（$reason），开始自动重连")
        showTransientStatus("连接中断，正在自动重连…", error = true)
        thread {
            try {
                isLiveViewRunning = false
                isLiveViewStarting = false
                ptpConnection?.close()
                ptpConnection = null
                PtpUsbConnection.activeInstance = null
                isConnected = false
                runOnUiThread { updateButtonStates() }

                for (attempt in 1..3) {
                    if (isConnected) break
                    val device = usbManager.deviceList.values.firstOrNull {
                        it.vendorId == PtpConstants.USB_VENDOR_NIKON
                    }
                    if (device == null) {
                        log("重连 $attempt/3：没找到相机（检查 OTG 线/机身是否休眠）")
                        Thread.sleep(1500)
                        continue
                    }
                    if (!usbManager.hasPermission(device)) {
                        log("重连 $attempt/3：USB 权限需要重新授权")
                        runOnUiThread { requestPermissionOrConnect(device) }
                        break
                    }
                    log("重连 $attempt/3：重新建立 PTP 会话…")
                    runOnUiThread { connectToDevice(device) }
                    Thread.sleep(5000)
                }
                if (!isConnected) {
                    log("自动重连未成功，可手动点「连接相机」再试")
                    showTransientStatus("重连失败，请点「连接相机」重试", error = true)
                }
            } finally {
                isReconnecting = false
            }
        }
    }

    /**
     * 轮询里读一条属性，**读之前检查是否该让路**。
     *
     * 之前的写法是"一轮里连着读 9 条，读完才看 isPollingPaused"：
     * 用户点按钮时，轮询可能刚读到第 2 条，按钮的操作就得等剩下 7 条走完
     * （每条一次 USB 往返，合计 200~400ms）—— 这就是"按了没反应"的体感来源。
     * 现在每条之前都看一次标志，命中暂停就立刻返回，把通道让出来。
     */
    private fun pollProp(code: Int): Int {
        if (pollingBlocked()) return -999999
        return ptpConnection?.getDevicePropValue(code) ?: -999999
    }

    private fun startPolling() {
        if (isPollingActive) return
        isPollingActive = true
        thread {
            var lastFocal = -1
            var lastIso = -1
            var lastAp = -1
            var lastSh = -1
            var lastMode = -1
            var lastFocus = -1
            var lastBatteryStd = -1
            var lastIsoAuto = -1

            while (isPollingActive && isConnected) {
                // 开启取景期间也要让路：这里每轮要连读 8 个属性、每次都会 drain()，
                // 和正在握手开取景的线程抢同一把 usbLock，会把 LV 启动拖得更慢。
                // 后台 / 取景中 / 显式暂停 / 不在拍摄页 / 菜单打开：都不发命令，只保持线程活着
                if (pollingBlocked() || isLiveViewRunning || isLiveViewStarting) {
                    Thread.sleep(1000)
                    continue
                }
                try {
                    val focal = pollProp(PtpConstants.PROP_FOCAL_LENGTH)
                    if (focal != -999999) {
                        // 逐条走 pollProp：中途按钮一按（isPollingPaused=true）就立刻收手
                        val iso = pollProp(PtpConstants.PROP_EXPOSURE_INDEX)
                        val ap = pollProp(PtpConstants.PROP_F_NUMBER)
                        val sh = pollProp(activeShutterProp)
                        val mode = pollProp(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE)
                        val focus = pollProp(PtpConstants.PROP_FOCUS_MODE)
                        val batteryStd = pollProp(PtpConstants.PROP_BATTERY_LEVEL)
                        val isoAuto = pollProp(PtpConstants.PROP_NIKON_ISO_AUTO)

                        if (focal != lastFocal || iso != lastIso || ap != lastAp || sh != lastSh || mode != lastMode || focus != lastFocus || 
                            batteryStd != lastBatteryStd || isoAuto != lastIsoAuto) {
                            
                            lastFocal = focal
                            if (iso != -999999) lastIso = iso
                            if (ap != -999999) lastAp = ap
                            if (sh != -999999) lastSh = sh
                            if (mode != -999999) lastMode = mode
                            if (focus != -999999) lastFocus = focus
                            // 电量也要记下来，否则这一项恒为"变了"，每轮都会重刷整页 UI
                            if (batteryStd != -999999) lastBatteryStd = batteryStd
                            if (isoAuto != -999999) { lastIsoAuto = isoAuto; currentIsoAuto = isoAuto }
                            updateProperties()
                        }
                    }
                } catch (e: Exception) {
                    // 以前这里直接 disconnect()：OTG 松一下就得用户自己再点一次连接。
                    // 现在先尝试自动重连（退避 3 次），失败了才让用户手动点。
                    //
                    // ★ 但要**连续两次**才算真断线。单次异常太常见了：
                    //   机身正在写卡、刚拍完还在忙、OTG 抖了一下，
                    //   都可能让这一轮读属性失败，下一轮就正常了。
                    //   一次失败就走完整重连流程，就是"用着用着自己断一下又自己连上"。
                    pollFailStreak++
                    log("USB 通信异常（连续第 $pollFailStreak 次）：${e.message}")
                    val tooSoon = System.currentTimeMillis() - lastReconnectAt < 20_000
                    if (pollFailStreak < 2 || isReconnecting || tooSoon) {
                        if (tooSoon && pollFailStreak >= 2) log("距上次重连不到 20 秒，先观察，不重复重连")
                        Thread.sleep(2000)
                        continue
                    }
                    pollFailStreak = 0
                    lastReconnectAt = System.currentTimeMillis()
                    attemptAutoReconnect(e.message ?: "通信异常")
                    break
                }
                // 这一轮走通了，失败计数归零（连续才算数）
                pollFailStreak = 0
                // 空闲轮询间隔：2 秒 → 5 秒。
                // 9 条属性 × 每 2 秒 = 相机一直在被问话，机身自然停在 PC 忙碌态；
                // 5 秒足够跟上机身拨盘的变化，命令量直接降到原来的 40%。
                Thread.sleep(POLL_IDLE_INTERVAL_MS)
            }
            isPollingActive = false
        }
    }

    /** 距离上次整理日志的行数：每追加一条都 split 全文太费主线程，攒一批再整理 */
    private var logAppendSinceTrim = 0

    private fun log(message: String) {
        // 同时写一份到 logcat：界面上的日志面板默认收起，真机排查时靠 adb 拉日志
        // 比让人截图快得多（D300 的取景/对焦链路几乎全靠日志定位）。
        android.util.Log.d("D300_LOG", message)
        runOnUiThread {
            infoText.append("\n$message")
            // ★ 不再每条都 trimLog()。
            // trimLog 要 split 整段文本再拼回去，日志一长（几千行）就是每次几毫秒的主线程开销；
            // 而一次操作动辄写三五条日志，按钮"按下要等一下才变色"就有它的份。
            // 现在攒够 20 条才整理一次，超出上限最多多留 20 行，肉眼看不出来。
            if (++logAppendSinceTrim >= 20) {
                logAppendSinceTrim = 0
                trimLog()
            }
            (infoText.parent as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * 日志上限：轮询每几秒就刷一条，跑一晚上能攒出几万字符。
     * TextView 一直 append 会让文本越来越长，滚动和布局都要为此买单，
     * 所以超过上限就砍掉最早的条目，只保留最近这些。
     */
    private fun trimLog() {
        val lines = infoText.text.split("\n")
        if (lines.size <= LOG_MAX_LINES) return
        infoText.text = lines.takeLast(LOG_MAX_LINES).joinToString("\n")
    }

    /**
     * 自动 ISO 二级页面：上限 / 最低快门 + 总开关。
     * 入口是拍摄页 ISO 菜单里那块「自动」的长按 —— 这两个值不常改，不占主菜单。
     */
    private fun showIsoAutoSettingsDialog() {
        val maxIsoEntries = listOf(
            0 to "ISO 400", 1 to "ISO 800", 2 to "ISO 1600", 3 to "ISO 3200", 4 to "Hi 1（6400）"
        )
        val minShutterEntries = listOf(
            0 to "1/4000", 1 to "1/3200", 2 to "1/2500", 3 to "1/2000",
            4 to "1/1600", 5 to "1/1250", 6 to "1/1000", 7 to "1/800",
            8 to "1/640", 9 to "1/500", 10 to "1/400", 11 to "1/320",
            12 to "1/250", 13 to "1/200", 14 to "1/160", 15 to "1/125",
            16 to "1/100", 17 to "1/80", 18 to "1/60", 19 to "1/50",
            20 to "1/40", 21 to "1/30", 22 to "1/15", 23 to "1/8",
            24 to "1/4", 25 to "1/2", 26 to "1 秒"
        )

        var currentMaxIsoIdx = -1
        var currentMinShutterIdx = -1
        /** UI 刚搭起来要回填机身值，这时别把"同步界面"当成"改机身"写回去 */
        var viewReady = false

        val page = buildSheetPage("自动 ISO", "机身感光度自动控制")

        // ---- 分组：总开关 ----
        val cardSwitch = sheetCard(page.content, "自动控制")
        val (switchRow, isoSwitch) = sheetSwitchRow("自动 ISO", currentIsoAuto == 1) { checked ->
            if (!viewReady) return@sheetSwitchRow
            thread {
                try {
                    isPollingPaused = true
                    log("正在设置自动 ISO：${if (checked) "开" else "关"}…")
                    val ok = ptpConnection?.setDevicePropValue(
                        PtpConstants.PROP_NIKON_ISO_AUTO, if (checked) 1 else 0, size = 1
                    ) ?: false
                    if (ok) {
                        currentIsoAuto = if (checked) 1 else 0
                        ptpConnection?.deviceReady()
                    }
                    runOnUiThread { log("自动 ISO：${if (!ok) "设置失败" else if (checked) "开" else "关"}") }
                } catch (e: Exception) {
                    runOnUiThread { log("自动 ISO 设置出错：${e.message}") }
                } finally {
                    isPollingPaused = false
                    updateProperties()
                }
            }
        }
        cardSwitch.addView(switchRow)
        cardSwitch.addView(sheetHint("关掉时机身固定用手动设定的 ISO，下面两档也就跟着不起作用了。"))

        // ---- 分组：控制策略 ----
        val cardRange = sheetCard(page.content, "控制策略")

        var rowMaxIso: SheetValueRow? = null
        rowMaxIso = sheetValueRow("最高感光度", "读取中…") { anchor ->
            showValueMenu(
                anchor,
                maxIsoEntries.map { it.second },
                maxIsoEntries.indexOfFirst { it.first == currentMaxIsoIdx }
            ) { pos ->
                val entry = maxIsoEntries[pos]
                thread {
                    try {
                        isPollingPaused = true
                        log("正在设置最高 ISO：${entry.second}…")
                        val ok = ptpConnection?.setDevicePropValue(
                            PtpConstants.PROP_NIKON_ISO_AUTO_MAX_ISO, entry.first, size = 1
                        ) ?: false
                        if (ok) {
                            currentMaxIsoIdx = entry.first
                            ptpConnection?.deviceReady()
                            runOnUiThread {
                                rowMaxIso?.value?.text = entry.second
                                log("最高 ISO：${entry.second}")
                            }
                        } else {
                            runOnUiThread { log("最高 ISO 设置失败") }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { log("最高 ISO 设置出错：${e.message}") }
                    } finally {
                        isPollingPaused = false
                    }
                }
            }
        }
        cardRange.addView(rowMaxIso!!.view)

        var rowMinShutter: SheetValueRow? = null
        rowMinShutter = sheetValueRow("最低快门速度", "读取中…") { anchor ->
            showValueMenu(
                anchor,
                minShutterEntries.map { it.second },
                minShutterEntries.indexOfFirst { it.first == currentMinShutterIdx }
            ) { pos ->
                val entry = minShutterEntries[pos]
                thread {
                    try {
                        isPollingPaused = true
                        log("正在设置最低快门：${entry.second}…")
                        val ok = ptpConnection?.setDevicePropValue(
                            PtpConstants.PROP_NIKON_ISO_AUTO_MIN_SHUTTER, entry.first, size = 1
                        ) ?: false
                        if (ok) {
                            currentMinShutterIdx = entry.first
                            ptpConnection?.deviceReady()
                            runOnUiThread {
                                rowMinShutter?.value?.text = entry.second
                                log("最低快门：${entry.second}")
                            }
                        } else {
                            runOnUiThread { log("最低快门设置失败") }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { log("最低快门设置出错：${e.message}") }
                    } finally {
                        isPollingPaused = false
                    }
                }
            }
        }
        cardRange.addView(rowMinShutter!!.view)
        cardRange.addView(sheetHint("ISO 抬到上限仍不够快时，机身会先看这条底线：低于它宁可提高感光度也不放慢快门。"))

        page.show()

        // 三项都问一遍机身：轮询不一定刚读过，二级页面自己拿一次最新值最稳
        thread {
            val maxIdx = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO_MAX_ISO) ?: -1
            val minIdx = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO_MIN_SHUTTER) ?: -1
            val autoOn = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO) ?: -1
            runOnUiThread {
                if (maxIdx >= 0) {
                    currentMaxIsoIdx = maxIdx
                    rowMaxIso?.value?.text = maxIsoEntries.firstOrNull { it.first == maxIdx }?.second
                        ?: "档位 $maxIdx"
                } else {
                    rowMaxIso?.value?.text = "未读到"
                }
                if (minIdx >= 0) {
                    currentMinShutterIdx = minIdx
                    rowMinShutter?.value?.text = minShutterEntries.firstOrNull { it.first == minIdx }?.second
                        ?: "档位 $minIdx"
                } else {
                    rowMinShutter?.value?.text = "未读到"
                }
                if (autoOn >= 0) currentIsoAuto = autoOn
                isoSwitch.isChecked = autoOn == 1
                viewReady = true
            }
        }
    }

    /** 0x5005 白平衡 → 中文名（与白平衡二级页同一套档位） */
    private fun wbLabel(v: Int): String = when (v) {
        1 -> "手动预设"
        2 -> "自动"
        4 -> "晴天"
        5 -> "荧光灯"
        6 -> "白炽灯"
        7 -> "闪光灯"
        0x8010 -> "阴天"
        0x8011 -> "背阴"
        // 说明书上这一档叫「手动色温」（选它之后再输 K 值），只写「色温」会和前面
        // 那些按光源命名的档混在一起，看不出这是要自己填数值的
        0x8012 -> "手动色温"
        0x8013 -> "预设"
        else -> "未知"
    }

    /**
     * 白平衡二级页面：块状模式列表，读一次机身把当前那一档点亮——
     * 没有参照的话，切到"色温"还是"预设"全靠记。
     */
    private fun showWhiteBalanceDialog() {
        val wbModes = listOf(
            2 to "自动", 4 to "晴天", 5 to "荧光灯", 6 to "白炽灯", 7 to "闪光灯",
            0x8010 to "阴天", 0x8011 to "背阴", 0x8012 to "手动色温", 0x8013 to "预设"
        )

        val page = buildSheetPage("白平衡")

        val card = sheetCard(page.content, "模式")
        var gridRef: SheetTileGrid? = null
        gridRef = sheetTileGrid(2, wbModes.map { it.second }) { index ->
            val pair = wbModes[index]
            gridRef?.select(index)
            setWhiteBalance(pair.first, pair.second)
            page.dialog.dismiss()
            if (pair.first == 0x8012) {
                showColorTempDialog()
            } else if (pair.first == 0x8013) {
                showWbPresetDialog()
            }
        }
        card.addView(gridRef!!.view)
        card.addView(sheetHint("「手动色温」接着让你输 K 值；「预设」切到机身里存好的手动白平衡（d-0 … d-4）。"))

        // 白平衡预设测量：官方支持的"对白纸测一次"（0x90C0 的 P1 传 0…4）
        val measureCard = sheetCard(page.content, "预设测量")
        val measureRow = sheetValueRow("测量自定义白平衡", "对准白纸再点") { _ ->
            showWbMeasureSheet()
        }
        measureCard.addView(measureRow.view)
        measureCard.addView(sheetHint(
            "把相机对准白纸或灰卡（占满画面中心），再点这一项 —— " +
                "机身在取景状态下测一次现场光，把结果写进预设槽 d-1…d-5。" +
                "官方规格书 §4.4：这是 0x90C0 的 P1 传 0…4 的用途（P1=0xFFFFFFFF 才是正常拍摄）。"
        ))

        page.show()

        thread {
            val current = ptpConnection?.getDevicePropValue(PtpConstants.PROP_WHITE_BALANCE) ?: -1
            runOnUiThread {
                val idx = wbModes.indexOfFirst { it.first == current }
                if (idx >= 0) gridRef?.select(idx)
            }
        }
    }

    /**
     * 白平衡预设测量（官方 §4.4：0x90C0 的 P1 传 0x00000000–0x00000004 即「预设测量释放」）。
     *
     * 流程：对准白纸 → 选目标槽（d-1…d-5 对应 P1=0…4）→ 发 0x90C0(P1) →
     * 轮询 0x90C8 等机身测完 → 报结果。
     *
     * **不接 0x90C9 SetPreWbData**：那个接口要同时传增益值和最多 13440 B 的缩略图，
     * App 侧没有可靠的缩略图来源，硬造一份会把预设写坏。机身自己测出来的值它自己会存。
     */
    /**
     * 优化校准原始数据（0x90CC 只读）。
     *
     * 用它能看到机身里每个 PicCtrl 项到底存了什么：数据块多大、名称是什么。
     * **不提供写入** —— PicCtrlData 的字段表官方没公开，硬拼数据写回去会弄坏
     * 机身里的自定义优化校准；装滤镜继续走 NCP 文件导入那条路。
     */
    private fun showPicCtrlSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage("优化校准数据", "0x90CC · 只读")
        val status = sheetStatusText("正在读取机身里的项…")
        page.content.addView(status)
        val card = sheetCard(page.content, "机身里的优化校准项")
        page.content.addView(sheetHint(
            "名称是从数据块里能连续读出的可打印字符猜出来的 —— 官方没公开 PicCtrlData 的字段表，" +
                "所以名称可能带杂字。这一页只看不改。"
        ))
        page.show()

        thread {
            val desc = try {
                ptpConnection?.readPropDesc(PtpConstants.PROP_NIKON_ACTIVE_PIC_CTRL)
            } catch (_: Exception) {
                null
            }
            val items = desc?.allowed ?: emptyList()
            if (items.isEmpty()) {
                runOnUiThread {
                    status.text = "机身没报优化校准项清单（0xD200 无描述符或没给值域）"
                }
                return@thread
            }
            val rows = ArrayList<Triple<Long, Int, String>>()
            for (v in items) {
                if (!isConnected) break
                val data = try {
                    ptpConnection?.getPicCtrlData(v.toInt())
                } catch (_: Exception) {
                    null
                } ?: continue
                rows.add(Triple(v, data.size, guessPicCtrlName(data)))
            }
            runOnUiThread {
                status.text = "读到 ${rows.size}/${items.size} 项"
                for ((v, size, name) in rows) {
                    val (row, _) = sheetInfoRow("项 $v", "$name · $size 字节")
                    card.addView(row)
                }
            }
        }
    }

    /** 从 PicCtrlData 里猜名称：前 64 字节中最长的一段可打印 ASCII（≥3 字符） */
    private fun guessPicCtrlName(data: ByteArray): String {
        var best = ""
        val cur = StringBuilder()
        for (i in 0 until minOf(64, data.size)) {
            val c = data[i].toInt() and 0xFF
            if (c in 32..126) {
                cur.append(c.toChar())
            } else {
                if (cur.length > best.length) best = cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.length > best.length) best = cur.toString()
        return if (best.length >= 3) best else "（名称未识别）"
    }

    private fun showWbMeasureSheet() {
        if (!isConnected) { toast("请先连接相机"); return }
        val page = buildSheetPage("白平衡预设测量", "0x90C0 P1=0…4 · 先对准白纸再点槽位")
        val card = sheetCard(page.content, "写到哪个预设槽")
        val slots = listOf(0 to "d-1", 1 to "d-2", 2 to "d-3", 3 to "d-4", 4 to "d-5")
        var gridRef: SheetTileGrid? = null
        gridRef = sheetTileGrid(3, slots.map { it.second }) { idx ->
            gridRef?.select(idx)
            val pair = slots[idx]
            val conn = ptpConnection
            if (conn != null) {
                thread {
                    isPollingPaused = true
                    try {
                        runOnUiThread { log("正在测量白平衡 → ${pair.second} …（保持白纸占满画面中心）") }
                        val code = conn.initiateSdram(pair.first, 20000)
                        if (code != PtpConstants.RESP_OK) {
                            val hex = if (code < 0) "TIMEOUT" else "0x" + Integer.toHexString(code).uppercase()
                            runOnUiThread { log("测量被拒：$hex") }
                            return@thread
                        }
                        // 命令回 OK 只代表"开始了"，真正测完要问 DeviceReady
                        var ready = false
                        for (i in 0 until 60) {
                            if (!isConnected) break
                            if (conn.deviceReady() == true) { ready = true; break }
                            Thread.sleep(200)
                        }
                        Thread.sleep(400)
                        val wb = conn.getDevicePropValue(PtpConstants.PROP_WHITE_BALANCE)
                        runOnUiThread {
                            log(
                                if (ready) "白平衡测量完成 → ${pair.second}（机身白平衡现在 = ${wbLabel(wb)}）"
                                else "白平衡测量未在 12 秒内完成，多半是没对准白纸 —— 可以再试一次"
                            )
                            toast(if (ready) "${pair.second} 已测量" else "测量超时")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { log("白平衡测量出错：${e.message}") }
                    } finally {
                        isPollingPaused = false
                    }
                }
            }
        }
        card.addView(gridRef.view)
        card.addView(sheetHint(
            "用法：让白纸或灰卡占满画面中心 → 点一个槽。\n\n" +
                "· 测完存进那个槽；之后把白平衡切到「预设」并选同一个 d-× 就能用\n" +
                "· 光太暗、或画面里混进别的颜色，测出来会偏 —— 重新测一次覆盖即可\n" +
                "· 这里只做测量。增益回写（0x90C9）没接：它要求同时传 13440 B 缩略图，" +
                "App 造不出来，硬写会把预设弄坏"
        ))
        page.show()
    }

    private fun showColorTempDialog() {        val page = buildSheetPage("手动色温", "手动白平衡 · K 值")

        val cardInput = sheetCard(page.content, "目标色温")
        val input = sheetNumberInput("2500 – 10000", "5000")
        cardInput.addView(input)
        cardInput.addView(sheetHint("机身认的范围是 2500K – 10000K；数值越低越暖、越高越冷。"))

        val quick = listOf(
            3000 to "白炽灯", 4200 to "暖白荧光", 5200 to "日光", 6500 to "阴天", 8000 to "阴影"
        )
        val cardQuick = sheetCard(page.content, "常用光源")
        var quickGridRef: SheetTileGrid? = null
        quickGridRef = sheetTileGrid(2, quick.map { "${it.second} ${it.first}K" }) { index ->
            input.setText(quick[index].first.toString())
            input.setSelection(input.text.length)
            quickGridRef?.select(index)
        }
        cardQuick.addView(quickGridRef!!.view)

        val applyBtn = sheetPrimaryButton("写入机身")
        page.content.addView(applyBtn)
        applyBtn.setOnClickListener {
            val raw = input.text.toString()
            val kelvin = raw.toIntOrNull()
            if (kelvin == null || kelvin < 2500 || kelvin > 10000) {
                val errorMsg = "色温无效：范围 2500 - 10000"
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                log("出错：$errorMsg（输入：$raw）")
                return@setOnClickListener
            }
            page.dialog.dismiss()
            thread {
                try {
                    log("正在设置色温 ${kelvin}K…")
                    val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_NIKON_WB_COLOR_TEMP, kelvin, size = 2) ?: false
                    runOnUiThread { log("色温设置：${if (success) "成功" else "失败"}") }
                    if (success) ptpConnection?.deviceReady()
                } catch (e: Exception) {
                    runOnUiThread { log("设置色温出错：${e.message}") }
                }
                Thread.sleep(400)
                updateProperties()
            }
        }

        page.show()
    }

    private fun showWbPresetDialog() {
        val page = buildSheetPage("白平衡预设", "手动预设 d-0 … d-4")

        val card = sheetCard(page.content, "预设编号")
        var gridRef: SheetTileGrid? = null
        gridRef = sheetTileGrid(3, (0..4).map { "d-$it" }) { index ->
            gridRef?.select(index)
            page.dialog.dismiss()
            thread {
                try {
                    log("正在设置白平衡预设 d-$index…")
                    val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_NIKON_WB_PRESET_NO, index, size = 1) ?: false
                    runOnUiThread { log("白平衡预设设置：${if (success) "成功" else "失败"}") }
                    if (success) ptpConnection?.deviceReady()
                } catch (e: Exception) {
                    runOnUiThread { log("设置白平衡预设出错：${e.message}") }
                }
                Thread.sleep(400)
                updateProperties()
            }
        }
        card.addView(gridRef!!.view)
        card.addView(sheetHint("选的是机身里已经量好并保存的那组手动值，App 只负责切到哪一组。"))

        page.show()

        thread {
            val current = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_WB_PRESET_NO) ?: -1
            if (current in 0..4) runOnUiThread { gridRef.select(current) }
        }
    }

    /**
     * 光圈包围：把当前镜头能用的光圈逐档走一遍，每档一张。
     */
    /** 当前快门档位对应的曝光时长（毫秒）；B 门按 60 秒算，读不到按 5 秒 */
    private fun exposureMsNow(): Int {
        val v = ptpConnection?.getDevicePropValue(activeShutterProp) ?: -999999
        if (v == -999999) return 5000
        val label = shutterMap[v] ?: getShutterString(v)
        if (label.contains("门")) return 60_000
        return shutterLabelToMs(label) ?: 5000
    }

    /** "1/8000" → 0.125ms，"30\"" → 30000ms；解析不出来返回 null */
    private fun shutterLabelToMs(label: String): Int? {
        val s = label.trim()
        if (s.contains("/")) {
            val parts = s.split("/")
            val n = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
            val d = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
            if (d <= 0.0) return null
            return (n / d * 1000.0).toInt()
        }
        val sec = s.substringBefore("\"").trim().toDoubleOrNull() ?: return null
        return (sec * 1000.0).toInt()
    }

    /**
     * 等机身从"正在曝光/写卡"回到就绪。
     *
     * 长曝光时快门没关之前发任何命令都会被拒（DeviceBusy / 超时），
     * 以前固定 Thread.sleep(3000) 在 30 秒曝光下完全不够，会连锁把后面的档位全丢掉。
     */
    private fun waitCameraReady(budgetMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (!isConnected) return false
            if (ptpConnection?.deviceReady() == true) return true
            Thread.sleep(500)
        }
        return false
    }

    /**
     * 包围曝光 / 间隔拍摄这类"连续多张"的流程，开始前先把实时取景退干净。
     *
     * 为什么不能靠 shootOnce 自己退：它只在**第一张**前退一次，后面几档是在
     * "取景已退出"的中间状态下继续的；而包围流程每档之间还要改光圈/快门，
     * 很容易撞上 DeviceBusy。统一在入口退一次，后面的步骤就从同一个干净状态出发。
     *
     * 退出后顺手把取景模式写回三脚架 —— 机身收到 0x9202 会把它重置成手持。
     */
    private fun exitLiveViewForBatch(tag: String) {
        if (!isLiveViewRunning) return
        val modeBefore = currentLiveViewMode
        runOnUiThread { log("$tag：先退出实时取景") }
        stopLiveViewBlocking()
        Thread.sleep(2500)      // 反光板回落、机身回到待拍状态
        if (modeBefore == PtpConstants.LIVE_VIEW_MODE_TRIPOD) {
            ptpConnection?.setLiveViewMode(PtpConstants.LIVE_VIEW_MODE_TRIPOD)
            currentLiveViewMode = PtpConstants.LIVE_VIEW_MODE_TRIPOD
            runOnUiThread { renderLiveViewMode(PtpConstants.LIVE_VIEW_MODE_TRIPOD) }
        }
    }

    private fun showBracketingDialog() {
        val page = buildSheetPage("光圈包围")
        val dialog = page.dialog

        // 范围就用"起始 → 结束"两个可点的格子放在第一行，
        // 不再藏在「手动范围」开关后面 —— 夜景连拍时最常改的就是这两头。
        var startIdx = 0
        var endIdx = (dynamicApertures.size - 1).coerceAtLeast(0)
        var stepValueRef: TextView? = null

        fun targetList(): List<Pair<Int, String>> {
            if (dynamicApertures.isEmpty()) return emptyList()
            val s = minOf(startIdx, endIdx)
            val e = maxOf(startIdx, endIdx)
            return dynamicApertures.slice(s..e)
        }

        fun refreshSteps() {
            stepValueRef?.text = "${targetList().size} 张"
        }

        // ---- 起始 / 结束光圈：不加标题，两格加中间那个箭头已经说清楚这是范围 ----
        val cardRange = sheetCard(page.content)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 回调把"这个格子自己的数值 TextView"一起传出来：用外层变量会被自己引用（编译不过）
        fun rangeCell(label: String, initial: String, onTap: (View, TextView) -> Unit): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_setting_row)
                layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f)
                isClickable = true
            }
            box.addView(TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            val value = TextView(this).apply {
                text = initial
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_amber))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            box.addView(value)
            box.setOnClickListener { v -> onTap(v, value) }
            return box
        }

        val startCell = rangeCell(
            "起始光圈", dynamicApertures.getOrNull(startIdx)?.second ?: "--"
        ) { anchor, value ->
            if (dynamicApertures.isEmpty()) { toast("还没读到镜头光圈"); return@rangeCell }
            showValueMenu(anchor, dynamicApertures.map { it.second }, startIdx) { pos ->
                startIdx = pos
                value.text = dynamicApertures[pos].second
                refreshSteps()
            }
        }
        val endCell = rangeCell(
            "结束光圈", dynamicApertures.getOrNull(endIdx)?.second ?: "--"
        ) { anchor, value ->
            if (dynamicApertures.isEmpty()) { toast("还没读到镜头光圈"); return@rangeCell }
            showValueMenu(anchor, dynamicApertures.map { it.second }, endIdx) { pos ->
                endIdx = pos
                value.text = dynamicApertures[pos].second
                refreshSteps()
            }
        }
        cardRange.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62))
            addView(startCell)
            addView(TextView(this@MainActivity).apply {
                text = "→"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(62))
            })
            addView(endCell)
        })

        // ---- 倒计时 / 执行：分开两张卡片 ----
        // 倒计时这张**要写回「倒计时」标题**：删掉之后，四个格子上来就是 关/2 秒/5 秒/10 秒，
        // 没抬头的情况下看不出这排格子是管什么的（会被当成张数或步进）。
        val cardShoot = sheetCard(page.content, "倒计时")

        // 倒计时：按下「开始」之后先等几秒再拍第一张。
        // 没有它，人按完开始键手还搭在机身上，第一张最容易糊 —— 给一点离开机身的时间。
        val countdownEntries = listOf(0 to "关", 2 to "2 秒", 5 to "5 秒", 10 to "10 秒")
        var countdownSec = 0
        var cdGrid: SheetTileGrid? = null
        cdGrid = sheetTileGrid(4, countdownEntries.map { it.second }, 0) { idx ->
            countdownSec = countdownEntries[idx].first
            cdGrid?.select(idx)
        }
        cardShoot.addView(cdGrid.view)

        // ---- 分组：执行 ----
        val cardRun = sheetCard(page.content, "执行")
        val (stepRow, stepValue) = sheetInfoRow("总步数", "--")
        stepValueRef = stepValue
        stepValue.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        cardRun.addView(stepRow)
        refreshSteps()

        val progressBar = sheetProgress()
        cardRun.addView(progressBar)
        val statusText = sheetStatusText("准备就绪")
        cardRun.addView(statusText)

        val startBtn = sheetPrimaryButton("开始包围")
        page.content.addView(startBtn)

        /** 未运行时的外观：绿色底色换成统一的主按钮，同时把遮罩 cancellable 放开 */
        fun setIdleUi() {
            startBtn.text = "开始包围"
            startBtn.setBackgroundResource(R.drawable.bg_btn_amber)
            startBtn.setTextColor(Color.parseColor("#0B0E13"))
            progressBar.visibility = View.GONE
            dialog.setCancelable(true)
        }

        fun setRunningUi() {
            startBtn.text = "停止"
            startBtn.setBackgroundResource(R.drawable.bg_btn_red)
            startBtn.setTextColor(Color.WHITE)
            progressBar.visibility = View.VISIBLE
            dialog.setCancelable(false)
        }

        var isRunning = false
        var bracketThread: Thread? = null

        fun stopBracketing() {
            isRunning = false
            bracketThread?.interrupt()
            runOnUiThread {
                setIdleUi()
                statusText.text = "已停止"
            }
        }

        // 登记到日志页「停止」按钮：这个流程的线程和标志都是对话框里的局部变量，
        // 外面够不着，只能自己留一个出口让全局停止能叫停它。
        bracketStopHook = ::stopBracketing

        startBtn.setOnClickListener {
            if (!isRunning) {
                val plan = targetList()
                isRunning = true
                setRunningUi()
                progressBar.max = plan.size
                progressBar.progress = 0

                bracketThread = thread {
                    try {
                        isPollingPaused = true
                        // 取景中启动包围：先退出，再逐档曝光（见 exitLiveViewForBatch 注释）
                        exitLiveViewForBatch("光圈包围")
                        log("正在开始光圈包围…")

                        ptpConnection?.setDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, 3, size = 2)
                        Thread.sleep(1000)

                        runOnUiThread { stepValue.text = "${plan.size} 张" }

                        // 倒计时：逐秒报数，中途按「停止」立刻中断（isRunning 每轮都看）
                        if (countdownSec > 0) {
                            for (left in countdownSec downTo 1) {
                                if (!isRunning) break
                                runOnUiThread { statusText.text = "倒计时 $left 秒…" }
                                Thread.sleep(1000)
                            }
                            if (!isRunning) {
                                runOnUiThread { setIdleUi() }
                                return@thread
                            }
                        }

                        // 每档固定先对焦：机身在 MF 时 0x90C1 必然被拒，afDrive 返回 false
                        // 后 capture() 会自行交给机身兜底，所以不用进场前再探对焦模式。
                        val needsAf = true

                        var shot = 0
                        for (pair in plan) {
                            if (!isRunning) break
                            shot++
                            val apName = pair.second
                            val stepInfo = "第 $shot/${plan.size} 步：$apName"
                            runOnUiThread {
                                statusText.text = stepInfo
                                progressBar.progress = shot
                            }

                            // 设光圈失败多半是上一张还在曝光/写卡：等机身就绪再试一次，别直接跳档
                            var success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_F_NUMBER, pair.first, size = 2) ?: false
                            if (!success) {
                                runOnUiThread { statusText.text = "$stepInfo · 机身忙，等它就绪…" }
                                log("$stepInfo · 设光圈失败，等机身就绪后重试")
                                waitCameraReady(20_000)
                                success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_F_NUMBER, pair.first, size = 2) ?: false
                            }
                            if (success) {
                                Thread.sleep(600)

                                // 逐张对焦：先 0x90C1 AfDrive 并等到机身真正就绪，
                                // 合焦就走 0x90C0 直接释放快门；没合焦把 afAlreadyLocked 传 false，
                                // 让 capture() 改发 0x9207（机身自己再走一次 AF 再拍），不至于整步空转。
                                //
                                // ⚠️ AF 关掉时必须初始化为 true：那样 capture() 直接发 0x90C0。
                                // 以前恒为 false，于是每步都先发 0x9207 —— 这台 D300 对 0x9207 一律超时，
                                // 回退 0x90C0 才拍得成，而那一发失败事务会把管道搅乱，
                                // 后面几档连着超时、被当成"无法拍摄"跳过（用户实测就是这样丢档的）。
                                var afLocked = !needsAf
                                if (needsAf) {
                                    runOnUiThread { statusText.text = "$stepInfo · 对焦中" }
                                    afLocked = try {
                                        ptpConnection?.afDrive() ?: false
                                    } catch (e: Exception) {
                                        runOnUiThread { log("对焦异常：${e.message}") }
                                        false
                                    }
                                    runOnUiThread {
                                        statusText.text = stepInfo
                                        log("$stepInfo · 对焦${if (afLocked) "已合焦" else "未合焦，交给机身兜底"}")
                                    }
                                }

                                // 曝光时长决定"拍完要等多久"：30 秒的长曝只等 3 秒，
                                // 下一档设光圈必然撞"机身忙"，然后被当成失败跳过 —— 就是用户遇到的丢档
                                val exposureMs = exposureMsNow()
                                val code = ptpConnection?.capture(afAlreadyLocked = afLocked) ?: -1
                                if (code != PtpConstants.RESP_OK) {
                                    val hex = if (code < 0) "无响应" else "0x${Integer.toHexString(code).uppercase()}"
                                    runOnUiThread { log("$stepInfo · 快门被拒（$hex），等就绪后补拍一次") }
                                    waitCameraReady(15_000)
                                    val retry = ptpConnection?.capture(afAlreadyLocked = true) ?: -1
                                    if (retry != PtpConstants.RESP_OK) {
                                        runOnUiThread { log("$stepInfo · 补拍仍失败，跳过这一档") }
                                    }
                                }
                                // 关键：先按曝光时长"睡够"，再去问机身就绪。
                                // 只问 deviceReady 不行 —— 长曝光期间它也照样回 OK，
                                // 结果就是下一档设光圈撞上忙、被当成失败跳过。
                                runOnUiThread {
                                    statusText.text = if (exposureMs >= 2000)
                                        "$stepInfo · 曝光约 ${exposureMs / 1000} 秒，等待机身完成…"
                                    else "$stepInfo · 写卡中…"
                                }
                                if (exposureMs >= 1500) {
                                    Thread.sleep(exposureMs.coerceAtMost(150_000).toLong() + 1200)
                                } else {
                                    Thread.sleep(2000)
                                }
                                if (!waitCameraReady(20_000)) {
                                    runOnUiThread { log("$stepInfo · 机身迟迟没回就绪，继续下一档") }
                                }
                            } else {
                                runOnUiThread { log("设置失败：$apName（机身始终没回到就绪）") }
                            }
                        }
                        runOnUiThread {
                            log("包围完成")
                            statusText.text = "已完成"
                            setIdleUi()
                            isRunning = false
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            runOnUiThread {
                                statusText.text = "出错：${e.message}"
                                setIdleUi()
                                isRunning = false
                            }
                        }
                    } finally {
                        isPollingPaused = false
                        thread { updateProperties() }
                    }
                }
            } else {
                stopBracketing()
            }
        }

        page.onDismiss {
            bracketStopHook = null
            if (isRunning) {
                isRunning = false
                bracketThread?.interrupt()
            }
        }

        page.show()
    }

    /**
     * 快门包围：以机身当前快门为中心，按步长向两端各迈几档。
     */
    private fun showExpBracketingDialog() {
        val page = buildSheetPage("快门包围")
        val dialog = page.dialog

        val stepEntries = listOf(1 to "1/3 EV", 2 to "2/3 EV", 3 to "1 EV")
        val countEntries = listOf(3 to "3 张", 5 to "5 张", 7 to "7 张")
        var stepIdx = 2          // 默认 1 EV
        var countIdx = 0         // 默认 3 张
        var currentShutterIndex = -1
        var rangeValueRef: TextView? = null

        fun currentCounts() = countEntries[countIdx].first
        fun currentStep() = stepEntries[stepIdx].first

        fun sequenceOf(startIndex: Int, step: Int, count: Int): List<Int> {
            val out = mutableListOf<Int>()
            val half = count / 2
            for (i in -half..half) {
                val idx = startIndex + i * step
                if (idx in shutters.indices) out.add(idx)
            }
            return out
        }

        fun refreshRange() {
            val text = when {
                currentShutterIndex < 0 -> "--"
                else -> {
                    val seq = sequenceOf(currentShutterIndex, currentStep(), currentCounts())
                    if (seq.isEmpty()) {
                        "--"
                    } else {
                        "${shutters[seq.first()].second} → ${shutters[seq.last()].second}"
                    }
                }
            }
            rangeValueRef?.text = text
        }

        // ---- 分组：参数 ----
        val cardParam = sheetCard(page.content, "步进与张数")

        var stepRow: SheetValueRow? = null
        stepRow = sheetValueRow("EV 步长", stepEntries[stepIdx].second) { anchor ->
            showValueMenu(anchor, stepEntries.map { it.second }, stepIdx) { pos ->
                stepIdx = pos
                stepRow?.value?.text = stepEntries[pos].second
                refreshRange()
            }
        }
        cardParam.addView(stepRow.view)

        var countRow: SheetValueRow? = null
        countRow = sheetValueRow("拍摄张数", countEntries[countIdx].second) { anchor ->
            showValueMenu(anchor, countEntries.map { it.second }, countIdx) { pos ->
                countIdx = pos
                countRow?.value?.text = countEntries[pos].second
                refreshRange()
            }
        }
        cardParam.addView(countRow.view)

        // 当前快门：先让用户在按「开始」之前确认机身停在哪一档 ——
        // 包围是以它为圆心往两边迈的，圆心认错了整组都偏（快门菜单里看不到"机身现在在哪"）。
        val (curRow, curValue) = sheetInfoRow("当前快门", "读取中…")
        cardParam.addView(curRow)
        val (rangeRow, rangeValue) = sheetInfoRow("快门范围", "--")
        rangeValueRef = rangeValue
        rangeValue.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        cardParam.addView(rangeRow)
        cardParam.addView(sheetHint("以机身当前快门为中心，按步长往两边各迈一半张数；"
                + "两头被机身可用范围卡住时会少拍几张，日志里看得出来。"))

        // ---- 分组：执行 ----
        val cardRun = sheetCard(page.content, "执行")
        val progressBar = sheetProgress()
        cardRun.addView(progressBar)
        val statusText = sheetStatusText("待机")
        cardRun.addView(statusText)

        val startBtn = sheetPrimaryButton("开始快门包围")
        page.content.addView(startBtn)

        fun setIdleUi() {
            startBtn.text = "开始快门包围"
            startBtn.setBackgroundResource(R.drawable.bg_btn_amber)
            startBtn.setTextColor(Color.parseColor("#0B0E13"))
            progressBar.visibility = View.GONE
            dialog.setCancelable(true)
        }

        fun setRunningUi() {
            startBtn.text = "停止"
            startBtn.setBackgroundResource(R.drawable.bg_btn_red)
            startBtn.setTextColor(Color.WHITE)
            progressBar.visibility = View.VISIBLE
            dialog.setCancelable(false)
        }

        var isRunning = false
        var bracketThread: Thread? = null

        fun stopBracketing() {
            isRunning = false
            bracketThread?.interrupt()
            runOnUiThread {
                setIdleUi()
                statusText.text = "已停止"
            }
        }

        // 登记到日志页「停止」按钮：这个流程的线程和标志都是对话框里的局部变量，
        // 外面够不着，只能自己留一个出口让全局停止能叫停它。
        bracketStopHook = ::stopBracketing

        startBtn.setOnClickListener {
            if (!isRunning) {
                isRunning = true
                setRunningUi()

                bracketThread = thread {
                    try {
                        isPollingPaused = true
                        exitLiveViewForBatch("快门包围")
                        log("正在开始曝光包围…")
                        val currentShVal = ptpConnection?.getDevicePropValue(activeShutterProp) ?: -999999
                        val startIndex = shutters.indexOfFirst { it.first == currentShVal }

                        if (startIndex == -1) {
                            runOnUiThread {
                                log("出错：当前快门不在标准列表里")
                                stopBracketing()
                            }
                            return@thread
                        }

                        val sequence = sequenceOf(startIndex, currentStep(), currentCounts())
                        if (sequence.isEmpty()) {
                            runOnUiThread {
                                log("出错：按这个步长算出来的快门都超出可用范围")
                                stopBracketing()
                            }
                            return@thread
                        }

                        runOnUiThread {
                            progressBar.max = sequence.size
                            progressBar.progress = 0
                        }

                        sequence.forEachIndexed { i, shIdx ->
                            if (!isRunning) return@forEachIndexed
                            val pair = shutters[shIdx]
                            runOnUiThread {
                                statusText.text = "拍摄 ${i + 1}/${sequence.size}：${pair.second}"
                                progressBar.progress = i + 1
                            }

                            // 快门包围本身就是往长曝光方向走：设档失败/拍完就走的固定等待都不够用
                            var success = ptpConnection?.setDevicePropValue(activeShutterProp, pair.first, size = shutterPropSize) ?: false
                            if (!success) {
                                runOnUiThread { log("设快门失败：${pair.second}，等机身就绪后重试") }
                                waitCameraReady(20_000)
                                success = ptpConnection?.setDevicePropValue(activeShutterProp, pair.first, size = shutterPropSize) ?: false
                            }
                            if (success) {
                                Thread.sleep(800)
                                val exposureMs = if (pair.second.contains("门")) 60_000
                                    else (shutterLabelToMs(pair.second) ?: 4000)
                                // 同样是直接 0x90C0：不要先发 0x9207（本机必超时）
                                val code = ptpConnection?.capture(afAlreadyLocked = true) ?: -1
                                if (code != PtpConstants.RESP_OK) {
                                    val hex = if (code < 0) "无响应" else "0x${Integer.toHexString(code).uppercase()}"
                                    runOnUiThread { log("快门被拒（$hex，${pair.second}），等就绪后补拍一次") }
                                    waitCameraReady(15_000)
                                    ptpConnection?.capture(afAlreadyLocked = true)
                                }
                                if (exposureMs >= 2000) {
                                    runOnUiThread {
                                        statusText.text = "拍摄 ${i + 1}/${sequence.size}：${pair.second} · 曝光约 ${exposureMs / 1000} 秒"
                                    }
                                }
                                // 同样先睡够这一档的曝光时长，再确认机身就绪
                                if (exposureMs >= 1500) {
                                    Thread.sleep(exposureMs.coerceAtMost(150_000).toLong() + 1200)
                                } else {
                                    Thread.sleep(2000)
                                }
                                if (!waitCameraReady(20_000)) {
                                    runOnUiThread { log("${pair.second}：机身迟迟没回就绪，继续下一档") }
                                }
                            } else {
                                runOnUiThread { log("设置失败：${pair.second}（机身始终没回到就绪）") }
                            }
                        }

                        runOnUiThread {
                            log("曝光包围完成")
                            statusText.text = "已完成"
                            setIdleUi()
                            isRunning = false
                            updateProperties()
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            runOnUiThread {
                                statusText.text = "出错：${e.message}"
                                setIdleUi()
                                isRunning = false
                            }
                        }
                    } finally {
                        isPollingPaused = false
                        thread { updateProperties() }
                    }
                }
            } else {
                stopBracketing()
            }
        }

        page.onDismiss {
            bracketStopHook = null
            if (isRunning) {
                isRunning = false
                bracketThread?.interrupt()
            }
        }

        page.show()

        // 进来先看清机身现在停在哪档快门，好把"当前快门"和"范围"都填出来
        thread {
            val currentShVal = ptpConnection?.getDevicePropValue(activeShutterProp) ?: -999999
            val idx = shutters.indexOfFirst { it.first == currentShVal }
            runOnUiThread {
                if (idx >= 0) {
                    currentShutterIndex = idx
                    curValue.text = shutters[idx].second
                    refreshRange()
                } else {
                    // 机身报的值不在档位表里（B 门 / 闪光同步 / 刚切过模式）：
                    // 按原始值直接翻成文字，至少让用户看见机身现在是什么
                    curValue.text = if (currentShVal == -999999) "--"
                    else getShutterString(currentShVal)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 录制中的话必须收尾：不 stop 的话 MediaMuxer 写出的 MP4 没有 moov 头，
        // 文件在相册里看得见却播不了
        if (recorder?.isRecording == true) {
            recorder?.stop()
            stopRecTicker()
        }
        unregisterReceiver(usbReceiver)

        // ★ 取景没退就关掉 Activity 是个大坑：读帧循环写的是 while(isLiveViewRunning)，
        //   Activity 没了它照样在发 0x9203，USB 管道一直被占着 —— 下次进 App 连
        //   0x1002 OpenSession 都排不进去，表现就是"整个软件无响应，只能清后台重进"。
        //   所以这里必须先把循环停掉、让机身退出取景，再关连接。
        val lvThread = liveViewThread
        val ptp = ptpConnection
        val wasLiveView = isLiveViewRunning || lvThread?.isAlive == true
        isLiveViewRunning = false
        if (wasLiveView) {
            thread {
                try {
                    lvThread?.join(3000)
                } catch (_: Exception) {
                }
                try {
                    ptp?.endLiveView()
                } catch (_: Exception) {
                }
                try {
                    ptp?.close()
                } catch (_: Exception) {
                }
            }
        } else {
            ptpConnection?.close()
        }
    }
}
