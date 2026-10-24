package com.example.nikond300controller

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.jvm.Volatile
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 相机相册：直接读 D300 存储卡里的照片。
 *
 * 三级加载，保证"先看到东西"：
 *   1. GetObjectHandles —— 一次事务拿全部句柄，很快
 *   2. 逐个 GetObjectInfo + GetThumb —— 缩略图到一张就往网格里塞一张，不用等全部
 *   3. 点开某张才 GetObject 拉原图 —— NEF 十几 MB，绝不能提前拉
 *
 * 注意：所有 PTP 调用都走同一把 usbLock，串行遍历即可，并发没有意义还会互相拖慢。
 */
class GalleryActivity : AppCompatActivity() {

    /** 一个网格单元：对象信息 + 已取到的缩略图 */
    private class Entry(val info: PtpObjectInfo) {
        var thumb: Bitmap? = null
    }

    /** 文件名拆出来的"前缀 + 流水号"：DSC_4237.JPG → ("DSC_", 4237) */
    private data class NameRank(val prefix: String, val seq: Int)

    private fun nameRank(filename: String): NameRank {
        // 整串比大小不靠谱（位数一变字典序就错），所以把尾部的数字摘出来按数值比
        val base = filename.substringBeforeLast('.')
        val digits = base.takeLastWhile { it.isDigit() }
        if (digits.isEmpty()) return NameRank(base, -1)
        return NameRank(base.removeSuffix(digits), digits.toIntOrNull() ?: -1)
    }

    /**
     * 排序：**新 → 旧**，主键是**文件名流水号**（号越大越新 —— 尼康 `DSC_4237` 这种命名
     * 就是按拍摄顺序递增的，机身自己也是这么显示的）。
     *
     * 后面依次用前缀、拍摄时间、句柄做同号/跨段的次序参考。
     *
     * ⚠️ 流水号到 9999 会回绕成 0001（同时机身会新建一个文件夹），
     * 那种情况下跨文件夹时"号大即新"会反 —— 这里按用户口径以文件名为准，
     * 前缀与时间只用来定同号内的先后。
     *
     * ★ 排序只在**列表建好时做一次**，之后顺序不再变：
     * 以前是"缩略图到一张就插到二分找到的位置"，每插一张后面所有格子都要被
     * 默认动画推着挪一次位，几百张连着插就是满屏乱跳（用户说的"动画乱"）。
     */
    private fun sortNewestFirst(infos: List<PtpObjectInfo>): List<PtpObjectInfo> {
        data class Row(val info: PtpObjectInfo, val rank: NameRank)
        return infos.map { Row(it, nameRank(it.filename)) }
            .sortedWith(
                compareByDescending<Row> { it.rank.seq }
                    .thenByDescending { it.rank.prefix }
                    .thenByDescending { it.info.captureDate }
                    .thenByDescending { it.info.handle.toLong() and 0xFFFFFFFFL }
            )
            .map { it.info }
    }

    /**
     * 文件夹名的流水号：`100NCD300` → 100、`101NCD300` → 101。
     * 机身拍满 9999 张会新建下一个号段，所以**号大的文件夹更新**。
     */
    private fun folderSeq(name: String): Int {
        val digits = name.takeWhile { it.isDigit() }
        return if (digits.isEmpty()) -1 else digits.toIntOrNull() ?: -1
    }

    /**
     * 相册排序：**新 → 旧**，默认就看第一个。
     *
     * 主键是文件夹号段；号段一样（或文件夹名不带数字）时，用里面照片的最大流水号、
     * 再退到最大拍摄时间来定先后。
     */
    private fun sortFoldersNewestFirst(list: List<PhotoFolder>): List<PhotoFolder> {
        data class Row(
            val folder: PhotoFolder,
            val seq: Int,
            val maxPhotoSeq: Int,
            val maxDate: String
        )
        return list.map { f ->
            Row(
                f,
                folderSeq(f.name),
                f.photos.maxOfOrNull { nameRank(it.filename).seq } ?: -1,
                f.photos.maxOfOrNull { it.captureDate } ?: ""
            )
        }.sortedWith(
            compareByDescending<Row> { it.seq }
                .thenByDescending { it.maxPhotoSeq }
                .thenByDescending { it.maxDate }
        ).map { it.folder }
    }

    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var countText: TextView
    private lateinit var viewerOverlay: FrameLayout
    private lateinit var fullImage: ImageView
    private lateinit var viewerProgress: ProgressBar
    private lateinit var viewerInfo: TextView
    private lateinit var exifBar: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var viewerJpgButton: Button
    private lateinit var viewerDeleteButton: Button

    // ---- 多选导出 ----
    private lateinit var selectionBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var selProgress: ProgressBar
    private lateinit var selStatus: TextView
    private lateinit var selJpgButton: Button
    private lateinit var selExportButton: Button

    /**
     * NEF → JPG 的压缩质量（只对转档生效；JPEG 原件是原样拷贝）。
     *
     * 以前底栏上专门摆一颗「JPG 品质」按钮，它跟「转为 JPG」是分不开的两半
     * （选了品质还得记得去开转换开关），现在合成一颗「JPG 导出」：
     * 点 = 直接导出，长按 = 改品质，当前档位就写在按钮第二行。
     */
    private var jpegQuality = NefDecoder.QUALITY_DEFAULT
    /** 正在导出时锁住操作条，避免重复点 */
    @Volatile
    private var exporting = false

    private val entries = mutableListOf<Entry>()
    private lateinit var adapter: ThumbAdapter

    private var currentEntry: Entry? = null
    private var currentBytes: ByteArray? = null

    /**
     * 查看器当前这张在 [entries] 里的下标（左右滑动要用）。
     * 相册切换 / 刷新会清空 entries，所以每次开查看器都重新定位一次。
     */
    private var viewerIndex = -1

    /**
     * 查看器的"这次到底要看哪张"的令牌。
     * 左右滑得快点，上一张的原图还在传（NEF 十几 MB 要好几秒），
     * 它传完又去刷画面就把新一张冲掉了 —— 每个后台回调都先比对令牌，过期就丢弃。
     */
    @Volatile
    private var viewerToken = 0
    private lateinit var refreshButton: ImageButton
    @Volatile
    private var loading = false

    // ------------------------------------------------------------------
    // 相册（卡里的文件夹）切换
    //
    // D300 的卡上是 存储 → DCIM → 100NCD300 → DSC_xxxx 这样的层级，
    // 拍满 9999 张机身会自己建 101NCD300 —— 所以"有几个文件夹就是有几个相册"。
    // 列举一次就全部分组带回来，之后切换相册**不再重新列举**（那要再付几百次 GetObjectInfo）。
    // ------------------------------------------------------------------
    private lateinit var albumSwitch: View
    private lateinit var albumNameText: TextView

    /** 已按"新 → 旧"排好的相册列表 */
    private var folders: List<PhotoFolder> = emptyList()

    /** 当前选中的相册下标（[folders] 内）；[showAll] 为 true 时表示看全部 */
    private var folderIndex = 0
    private var showAll = false

    /**
     * 渲染批次号：切相册时 +1。
     *
     * 上一批的缩略图循环还在跑（一张张 GetThumb 很慢），它会在下一轮开头发现
     * token 变了就自己退出，不会继续往网格里塞旧相册的图。
     */
    @Volatile
    private var loadToken = 0

    /**
     * 缩略图缓存（句柄 → 位图）。
     *
     * 切回之前看过的相册时不用再问机身一遍；带淘汰上限，几百张也不会把内存撑爆。
     */
    private val thumbCache = object : LinkedHashMap<Int, Bitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap?>?): Boolean =
            size > 240
    }

    // ------------------------------------------------------------------
    // 缩略图分批加载
    //
    // 一张缩略图 = 一趟 USB 往返。整卡几百张全传一遍既慢、又让机身一直忙碌，
    // 所以首屏只取一小批，剩下的等用户真的往下滑再要。
    // ------------------------------------------------------------------

    /** 首屏加载张数（3 列 × 约 8 行） */
    private val FIRST_BATCH = 24
    /** 每次下滑补充的张数 */
    private val NEXT_BATCH = 18

    /** 当前这一批要显示的照片（顺序已定：新 → 旧） */
    @Volatile
    private var currentList: List<PtpObjectInfo> = emptyList()

    /** 下一个要取缩略图的位置 */
    @Volatile
    private var loadCursor = 0

    /** 需要加载到第几张（不含）—— 下滑时抬高它，加载线程就接着干 */
    @Volatile
    private var loadWanted = 0

    private val loadGate = Object()

    @Volatile private var filledCount = 0
    @Volatile private var thumbFailCount = 0
    @Volatile private var disconnectedFlag = false

    /**
     * 页面是否还活着。
     *
     * 相册里的取列表/拉原图都是长事务，以前退出相册时这些线程**照样在发 USB 命令**，
     * 用户回到主界面一操作就撞上 DeviceBusy。现在退出即置 false，后台线程每一步都先看它。
     */
    @Volatile
    private var pageAlive = true

    /** 在跑的后台线程，退出时逐个给 1.5 秒收尾 */
    private val bgLock = Any()
    private val bgThreads = mutableListOf<Thread>()

    /** 启动后台任务并登记，退出相册时要能等到它 */
    private fun bg(block: () -> Unit): Thread {
        val t = thread {
            try {
                block()
            } finally {
                synchronized(bgLock) { bgThreads.remove(Thread.currentThread()) }
            }
        }
        synchronized(bgLock) { bgThreads.add(t) }
        return t
    }

    /** 同 [bg]，但把 Thread 交回来 —— 需要 join 的地方（比如导出流水线）用这个 */
    private fun bgThread(block: () -> Unit): Thread = bg(block)

    /**
     * NEF 预览缓存：句柄 → 预览位图。
     *
     * 一张预览要跑两趟 USB（读头部定位 + 读预览本体），来回点开同一张
     * 每次都重传就太亏了。缓存按句柄存，退出相册随 Activity 一起回收。
     *
     * ★ 上限按**字节**算，不再是无界 Map：一张 4288×2848 的预览位图就是 ~49 MB
     *   （ARGB_8888）。以前没有上限，连着翻十几张 NEF 就能把堆吃光崩掉。
     *   这里给到最大堆的 1/8，超了淘汰最久没看的。
     *   淘汰时**不** recycle —— 那块位图可能还挂在 fullImage 上正显示着。
     */
    private val previewCache = object : android.util.LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(16 * 1024)
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val previewLock = Any()

    // ------------------------------------------------------------------
    // 原文件字节缓存
    //
    // ★ 以前 NEF 要传**两遍**整文件：一遍抠预览（loadNefPreview），一遍"供保存"
    // （startFullFetch）。十几 MB × 2，慢不说，EXIF 还要等第二遍传完才出现 ——
    // 用户点开一张要对着空白详情区等十几秒。
    // 现在全文件只取一次，字节留在这里：预览、EXIF、保存三件事共用同一份，
    // 左右滑回刚看过的那张也能瞬间出图 + 立刻有参数。
    // 上限压到 2 份（≈30 MB），多了就淘汰最久没用的。
    // ------------------------------------------------------------------
    private val fullBytes = object : LinkedHashMap<Int, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean =
            size > 2
    }
    private val fullBytesLock = Any()

    /** 取一张照片的完整字节（带缓存 + 退避重试）；断连或机身不给就 null */
    private fun fetchFull(ptp: PtpUsbConnection, info: PtpObjectInfo): ByteArray? {
        synchronized(fullBytesLock) { fullBytes[info.handle]?.let { return it } }
        if (!pageAlive) return null

        // NEF 十几 MB 传一半失败很常见（机身忙 / USB 抖动）。
        // 用退避而不是固定等 600ms：偶发抖动时第一次 250ms 就回来了，不必每次都白等 600；
        // 真卡住（机身还在写卡）再把间隔拉到 1.2s 给它喘气。
        // 最后一次失败后**不再 sleep** —— 那纯粹是让用户干等一段注定没结果的时间。
        val backoffMs = longArrayOf(250, 600, 1200)
        var bytes: ByteArray? = null
        for (attempt in backoffMs.indices) {
            bytes = ptp.getObject(info.handle, info.byteSize)
            if (bytes != null || !pageAlive) break
            if (attempt < backoffMs.lastIndex) Thread.sleep(backoffMs[attempt])
        }

        if (bytes != null) synchronized(fullBytesLock) { fullBytes[info.handle] = bytes }
        return bytes
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        recycler = findViewById(R.id.recycler)
        progress = findViewById(R.id.progress)
        emptyText = findViewById(R.id.emptyText)
        countText = findViewById(R.id.countText)
        viewerOverlay = findViewById(R.id.viewerOverlay)
        fullImage = findViewById(R.id.fullImage)
        viewerProgress = findViewById(R.id.viewerProgress)
        viewerInfo = findViewById(R.id.viewerInfo)
        exifBar = findViewById(R.id.exifBar)
        saveButton = findViewById(R.id.saveButton)

        albumSwitch = findViewById(R.id.albumSwitch)
        albumNameText = findViewById(R.id.albumName)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.closeViewerButton).setOnClickListener { closeViewer() }
        saveButton.setOnClickListener { saveCurrent() }

        // 刷新键以前一直是 gone 的死控件：加载失败只能退出重进，没有重试入口
        refreshButton = findViewById(R.id.refreshButton)
        refreshButton.visibility = View.VISIBLE
        refreshButton.setOnClickListener { reload() }

        // ---- 多选导出 ----
        selectionBar = findViewById(R.id.selectionBar)
        selCount = findViewById(R.id.selCount)
        selProgress = findViewById(R.id.selProgress)
        selStatus = findViewById(R.id.selStatus)
        // 进多选只有一个入口：**长按任一张照片**（长按那张顺手就勾上），
        // 顶栏不再放「多选」按钮 —— 多一个入口就多一处状态要同步。
        findViewById<Button>(R.id.selAllButton).setOnClickListener {
            if (adapter.selected.size == entries.size) adapter.selected.clear()
            else entries.forEach { adapter.selected.add(it.info.handle) }
            adapter.notifyDataSetChanged()
            updateSelectionUi()
        }
        findViewById<Button>(R.id.selExitButton).setOnClickListener { setSelectMode(false) }
        // 两颗按钮都直接导出，区别只在"NEF 要不要转成 JPG"：
        // 「JPG 导出」转档（长按改品质，按钮第二行写着当前档位），
        // 「原图导出」相机里是什么字节就存什么字节。
        // 以前是「JPG 品质」+「转为 JPG」开关 +「导出到手机」三颗，
        // 选了品质还得记得开开关 —— 两件事拆两处，漏一步就白选。
        jpegQuality = getSharedPreferences("gallery", MODE_PRIVATE)
            .getInt("jpeg_quality", NefDecoder.QUALITY_DEFAULT)
        selJpgButton = findViewById(R.id.selJpgButton)
        selJpgButton.setOnClickListener { exportSelected(asJpg = true) }
        selJpgButton.setOnLongClickListener { showQualitySheet(); true }
        selExportButton = findViewById(R.id.selExportButton)
        selExportButton.setOnClickListener { exportSelected(asJpg = false) }

        // ---- 单张查看器：导出 JPG / 从相机删除 ----
        viewerJpgButton = findViewById(R.id.viewerJpgButton)
        // 先挑品质再导出：默认那一档（92）已经预选上，直接点就走默认，
        // 想换档也不用先去别处设 —— 和"导出"这件事在同一屏里完成。
        viewerJpgButton.setOnClickListener { exportCurrentAsJpgWithQuality() }
        viewerDeleteButton = findViewById(R.id.viewerDeleteButton)
        viewerDeleteButton.setOnClickListener { confirmDeleteCurrent() }

        // 全屏查看时，返回键先关查看层而不是直接退出相册
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewerOverlay.visibility == View.VISIBLE -> closeViewer()
                    // 多选状态下返回键先退出多选，别把整个相册关掉
                    adapter.selectMode -> setSelectMode(false)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        adapter = ThumbAdapter(
            entries,
            { openViewer(it) },
            { updateSelectionUi() },
            { entry -> enterSelectMode(entry) }
        )
        // 左右滑动切上一张 / 下一张（看大图时不必退出去再点下一格）
        setupViewerSwipe()
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter
        // 底栏上那两颗按钮的第二行/可用态要按 adapter 的选中集合初始化，
        // 所以必须等 adapter 建好之后再刷一次
        updateSelectionUi()
        // 关掉默认条目动画。
        // 默认 ItemAnimator 会给每次插入/移动做位移与淡入：加载时每来一张就插一次，
        // 后面所有格子都要被推着挪一回 —— 几百张连着来就是满屏乱跳（"动画乱"的来源）。
        // 现在改成"整表铺一次 + 逐格换内容"，本来就没有插入动画的用武之地，留着只会添乱。
        recycler.itemAnimator = null

        // 下滑续加载：滑到"已加载区域的底部还剩两行"时再要一批缩略图。
        // 提前两行是为了让图在用户滑到之前就位，而不是滑到了才开始传。
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                val last = lm.findLastVisibleItemPosition()
                if (last < 0) return
                if (last + lm.spanCount * 2 >= loadWanted) requestMoreThumbs()
            }
        })

        val ptp = PtpUsbConnection.activeInstance
        if (ptp == null) {
            progress.visibility = View.GONE
            emptyText.text = "未连接相机"
            emptyText.visibility = View.VISIBLE
        } else {
            loadPhotos(ptp)
        }
    }

    /** 手动刷新：失败后不必退出重进，直接在本页重试 */
    private fun reload() {
        if (loading) return
        val ptp = PtpUsbConnection.activeInstance
        if (ptp == null) {
            Toast.makeText(this, "未连接相机", Toast.LENGTH_SHORT).show()
            return
        }
        refreshButton.isEnabled = false
        refreshButton.animate().rotationBy(360f).setDuration(600).start()
        loadPhotos(ptp) {
            refreshButton.isEnabled = true
        }
    }

    private fun loadPhotos(ptp: PtpUsbConnection, onDone: (() -> Unit)? = null) {
        // 并发保护：onCreate 自动加载和刷新按钮可能前后脚进来（实测出现过两线程交错遍历），
        // 第二次直接并进第一次的收尾，不重跑
        if (loading) { onDone?.invoke(); return }
        loading = true
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        entries.clear()
        adapter.notifyDataSetChanged()

        bg {
            // 先重开会话：D300 的照片对象列表是**按会话缓存**的，
            // 不重开的话，刚在拍摄页拍完的照片在这里根本列不出来（真机实测：
            // 拍完当场列举只有旧文件，退出重连后才出现）。重开一次约 1 秒，值得。
            if (pageAlive) {
                runOnUiThread { countText.text = "正在刷新列表…" }
                ptp.openSession()
            }

            // 一次遍历就把"卡里有几个相册、每个相册有哪些照片"全带回来。
            // 切换相册时直接复用这次结果，不再重新列举。
            val found = ptp.collectPhotoFolders()
            if (found.isEmpty()) {
                runOnUiThread {
                    folders = emptyList()
                    updateAlbumSwitcher()
                    progress.visibility = View.GONE
                    loading = false
                    countText.text = "0 张"
                    emptyText.text = "相机里没有照片\n\n拍一张或插上已存照片的机身"
                    emptyText.visibility = View.VISIBLE
                    onDone?.invoke()
                }
                return@bg
            }

            folders = sortFoldersNewestFirst(found)
            // 默认显示**最新**的那个相册（用户在别处选过"全部"就保持全部）
            if (!showAll) folderIndex = folderIndex.coerceIn(0, folders.size - 1)
            runOnUiThread { updateAlbumSwitcher() }

            renderCurrentFolder(
                ptp,
                onFirstBatch = {
                    // 首屏到位就把进度圈和刷新键放开 —— 剩下的靠下滑慢慢补，
                    // 不能让刷新键等几百张全传完才亮
                    progress.visibility = View.GONE
                    loading = false
                    onDone?.invoke()
                },
                onFinish = { finishUi(it) }
            )
        }
    }

    /** 一批缩略图跑完（或中途断连）之后的收尾：把数量、提示写回界面 */
    private fun finishUi(disconnected: Boolean) {
        if (disconnected) {
            emptyText.text = "相机连接中断（USB 无响应）—— 插拔 OTG 或重启机身，再点右上角刷新"
            emptyText.visibility = View.VISIBLE
        } else if (entries.isEmpty()) {
            emptyText.visibility = View.VISIBLE
        }
        // 有失败项就把原因亮出来，别让用户对着灰格子猜
        if (!disconnected && thumbFailCount > 0) {
            emptyText.text = "部分缩略图读取失败（详见日志页），可点右上角刷新重试"
            emptyText.visibility = View.VISIBLE
        }
        updateCountText()
    }

    /** 顶栏数量：已加载 / 总数，还有没加载的提示一句"下滑加载更多" */
    private fun updateCountText() {
        val total = currentList.size
        val note = buildString {
            append("$filledCount/$total 张")
            if (thumbFailCount > 0) append(" · $thumbFailCount 张无缩略图")
            if (loadWanted < total) append(" · 下滑加载更多")
            if (disconnectedFlag) append(" · 连接中断")
        }
        countText.text = note
    }

    /**
     * 把当前选中的相册铺进网格，再**分批**按"新→旧"填缩略图。
     *
     * ★ 为什么要分批：一张缩略图就是一趟 USB 往返，卡里几百张的话，
     *   进一次相册就要把整卡缩略图全传一遍 —— 既慢，又让机身一直停在忙碌态
     *   （主界面那边想调个参数都得等）。而现在网格里的文件名/格式在列举阶段
     *   就已经拿到了，**不取缩略图也能先把网格铺出来**，所以只加载首屏那几十张，
     *   剩下的等用户往下滑再要。
     *
     * 分两步铺也是为了不出现"乱跳的动画"：顺序在铺网格时定死，之后只往格子里换内容，
     * 不插不删，RecyclerView 也就没有位移动画可做。
     */
    private fun renderCurrentFolder(
        ptp: PtpUsbConnection,
        onFirstBatch: (() -> Unit)? = null,
        onFinish: ((Boolean) -> Unit)? = null
    ) {
        val ordered = sortNewestFirst(
            if (showAll) folders.flatMap { it.photos } else folders[folderIndex].photos
        )
        val token = ++loadToken
        currentList = ordered
        filledCount = 0
        thumbFailCount = 0
        disconnectedFlag = false

        // ★ 整张网格一次铺出来（占位格 + 文件名 + 格式角标 + 已缓存的缩略图）。
        // 这一步不额外花 USB 往返 —— 文件名/格式在列举阶段已经读到了。
        val seeded = ordered.map { info ->
            Entry(info).also { e ->
                // 之前看过的相册切回来：缓存里有就直接用，不必再问机身
                val cached = synchronized(thumbCache) {
                    if (thumbCache.containsKey(info.handle)) thumbCache[info.handle] else null
                }
                if (cached != null) { e.thumb = cached; filledCount++ }
            }
        }
        runOnUiThread {
            if (token != loadToken) return@runOnUiThread
            entries.clear()
            entries.addAll(seeded)
            // 换相册了：勾选的是句柄，留着会对不上新列表里的照片
            if (adapter.selectMode) adapter.selected.clear()
            adapter.notifyDataSetChanged()
            updateSelectionUi()
            updateCountText()
        }
        // 等 UI 线程真的把列表换好，再开始往里填图 —— 否则后台读到的可能还是旧列表
        awaitUiQueue()

        // 只要首屏那点（3 列 × 8 行 ≈ 24 张），剩下的由下滑触发
        loadCursor = 0
        loadWanted = minOf(FIRST_BATCH, ordered.size)

        var firstBatchDone = false
        var deadStreak = 0

        while (true) {
            if (!pageAlive || token != loadToken) return   // 页面退出 / 切了别的相册

            val i = synchronized(loadGate) {
                if (loadCursor >= loadWanted || loadCursor >= ordered.size) -1 else loadCursor++
            }
            if (i < 0) {
                if (loadCursor >= ordered.size) break      // 全加载完了
                if (!firstBatchDone) {
                    firstBatchDone = true
                    runOnUiThread { onFirstBatch?.invoke() }
                }
                // 等用户下滑（requestMoreThumbs 会 notify 这个锁）；
                // 超时也醒来复查一次，免得 notify 漏掉就永久卡住
                synchronized(loadGate) { loadGate.wait(3000) }
                continue
            }

            if (entries[i].thumb != null) continue         // 已用缓存填上，跳过
            // 缩略图只认 GetThumb：0x101B 分段读取 D300 不支持（真机回"无数据"），
            // 这里要是退回整张读取，一个 NEF 就是十几 MB，网格会卡死。
            val bmp = ptp.getThumb(ordered[i].handle)
            synchronized(thumbCache) { thumbCache[ordered[i].handle] = bmp }
            if (bmp == null) {
                thumbFailCount++
                // 连续多张取不到 = USB 多半已经断了，继续循环只是白等重试
                if (++deadStreak >= 5) { disconnectedFlag = true; break }
            } else {
                deadStreak = 0
                filledCount++
            }

            runOnUiThread {
                if (token != loadToken) return@runOnUiThread   // 已经切走了，别再改网格
                // 只换这一格的内容，不插不删 —— RecyclerView 不会带出位移动画
                entries[i].thumb = bmp
                adapter.notifyItemChanged(i)
                updateCountText()
            }
        }

        if (!firstBatchDone) runOnUiThread { onFirstBatch?.invoke() }
        if (token != loadToken || !pageAlive) return   // 被打断：收尾交给新的一批
        runOnUiThread { onFinish?.invoke(disconnectedFlag) }
    }

    /** 已铺的网格滑到底之前再要一批缩略图 */
    private fun requestMoreThumbs() {
        synchronized(loadGate) {
            val total = currentList.size
            if (loadWanted >= total) return
            loadWanted = minOf(loadWanted + NEXT_BATCH, total)
            loadGate.notifyAll()
        }
    }

    /** 等前面 post 出去的 UI 任务真正跑完（runOnUiThread 是异步的） */
    private fun awaitUiQueue() {
        val latch = CountDownLatch(1)
        runOnUiThread { latch.countDown() }
        try { latch.await(2, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    /**
     * 刷新顶栏的相册名，并在有**多个**相册时挂上切换入口。
     *
     * 只有一个相册时没必要给下拉 —— 点了也只有一项，反而让人以为别处还有。
     */
    private fun updateAlbumSwitcher() {
        if (folders.isEmpty()) {
            albumNameText.visibility = View.GONE
            albumSwitch.setOnClickListener(null)
            albumSwitch.isClickable = false
            return
        }
        val name = if (showAll) "全部相册" else albumName(folders[folderIndex])
        albumNameText.text = if (folders.size > 1) "$name ▾" else name
        albumNameText.visibility = View.VISIBLE
        if (folders.size > 1) {
            albumSwitch.isClickable = true
            albumSwitch.setOnClickListener { showAlbumMenu() }
        } else {
            albumSwitch.setOnClickListener(null)
            albumSwitch.isClickable = false
        }
    }

    /** 相册显示名。顶层直接放着照片时文件夹名为空，给个不至于空白的兜底 */
    private fun albumName(f: PhotoFolder): String = f.name.ifBlank { "存储卡" }

    /** 相册在菜单里的名字：带上父目录，换过卡也不会两个同名分不清 */
    private fun folderPath(f: PhotoFolder): String =
        if (f.parentName.isBlank()) albumName(f) else "${f.parentName}/${albumName(f)}"

    private fun showAlbumMenu() {
        if (folders.size <= 1) return
        val total = folders.sumOf { it.photos.size }
        val items = folders.map { "${folderPath(it)} · ${it.photos.size} 张" } +
            "全部相册 · $total 张"
        val density = resources.displayMetrics.density
        val rowH = (46 * density).toInt()

        val popup = ListPopupWindow(this)
        popup.anchorView = albumSwitch
        popup.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.bg_menu_card))
        popup.setAdapter(
            AlbumMenuAdapter(items, if (showAll) folders.size else folderIndex)
        )
        popup.width = kotlin.math.max(albumSwitch.width, (220 * density).toInt())
        popup.height = kotlin.math.min(
            items.size * rowH,
            (resources.displayMetrics.heightPixels * 0.6).toInt()
        )
        popup.isModal = true
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            if (position >= folders.size) switchFolder(0, true) else switchFolder(position, false)
        }
        popup.show()
    }

    /** 切换相册：只是换一批照片上屏，不重新列举（列举结果已经在内存里） */
    private fun switchFolder(index: Int, all: Boolean) {
        val ptp = PtpUsbConnection.activeInstance
        if (ptp == null || folders.isEmpty()) return
        folderIndex = index.coerceIn(0, folders.size - 1)
        showAll = all
        updateAlbumSwitcher()

        // 上一批缩略图循环还在跑：这里把 token 顶掉，它下一轮就会自己退出
        loading = true
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        bg {
            renderCurrentFolder(
                ptp,
                onFirstBatch = {
                    progress.visibility = View.GONE
                    loading = false
                },
                onFinish = { finishUi(it) }
            )
        }
    }

    private inner class AlbumMenuAdapter(
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

    /** 查看器顶部固定信息：文件名 · 格式 · 大小 */
    private var viewerHead = ""

    /** 当前这张的 EXIF（拍摄时间 / 快门 / 光圈 / ISO / 焦距 / 补偿 / 白平衡 / 测光） */
    private var viewerExif: PhotoExif.Summary? = null

    /**
     * 详情区的统一出口：顶部信息 → 一行自定义说明 → EXIF chip 条（图标+数值）。
     *
     * 以前这里是各处自己拼 `viewerInfo.text = "$head\n..."`，谁最后写谁生效；
     * 现在统一走这里，EXIF 迟到（后台解析）也不会把已有信息冲掉。
     */
    private fun renderViewerInfo(detail: String) {
        val sb = StringBuilder(viewerHead)
        if (detail.isNotBlank()) sb.append("\n").append(detail)
        viewerInfo.text = sb.toString()
        renderExifBar()
    }

    /**
     * EXIF chip 条：仿机内「总体数据」页 —— 图标/符号+数值的小块，不再写"快门/光圈"这类词。
     * 三行：日期 / ISO·快门·光圈·焦距 / 曝光档位·补偿·白平衡·测光（分组由 [PhotoExif.Summary.rows] 定）。
     * EXIF 还没解析完时整块隐藏，不留空档。
     */
    private fun renderExifBar() {
        exifBar.removeAllViews()
        val rows = viewerExif?.rows().orEmpty().filter { it.isNotEmpty() }
        if (rows.isEmpty()) {
            exifBar.visibility = View.GONE
            return
        }
        exifBar.visibility = View.VISIBLE

        val d = resources.displayMetrics.density
        val amber = getColor(R.color.accent_amber)

        /** 一块 chip：矢量图标（有就置左）或文本符号染强调色做视觉锚点 */
        fun chip(c: PhotoExif.Chip): TextView {
            val tv = TextView(this).apply {
                textSize = 12f
                setTextColor(getColor(R.color.text_primary))
                setBackgroundResource(R.drawable.bg_tile)
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * d).toInt(); bottomMargin = (6 * d).toInt() }
            }
            if (c.iconRes != 0) {
                // 图标本身已经带强调色，直接挂上即可（12dp 描边小图标，和参数页那套一致）
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(c.iconRes, 0, 0, 0)
                tv.compoundDrawablePadding = (4 * d).toInt()
            }
            val body = android.text.SpannableString(
                if (c.symbol.isEmpty()) c.value else "${c.symbol} ${c.value}"
            )
            if (!c.symbol.isEmpty()) {
                body.setSpan(
                    android.text.style.ForegroundColorSpan(amber),
                    0, c.symbol.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // 曝光档位的 P / S / A / M：单字母要挑得出来，
                // 加粗之后和后面的中文短名拉开主次，一眼就能扫到是哪一档
                if (c.symbolBold) {
                    body.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        0, c.symbol.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            // 曝光补偿：值首位的符号（+ / - / 归零时的 ±）也染强调色，
            // 和上面图标/符号同一套高亮 —— 0 EV 时写成 "±0.0"，那个 ± 同样要亮
            if (c.signOrange && c.value.isNotEmpty() &&
                (c.value[0] == '+' || c.value[0] == '-' || c.value[0] == '±')
            ) {
                val at = if (c.symbol.isEmpty()) 0 else c.symbol.length + 1
                body.setSpan(
                    android.text.style.ForegroundColorSpan(amber),
                    at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            tv.text = body
            return tv
        }

        rows.forEach { rowChips ->
            // 自己折行，不用横向 LinearLayout：第三行加了「曝光档位」之后有四项，
            // 窄屏一行放不下，LinearLayout 会把最后那一块压扁而不是换行。
            exifBar.addView(WrapRow(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                rowChips.forEach { addView(chip(it)) }
            })
        }
    }

    /**
     * 让 chip 自动折行的容器：横向铺不下就往下一行排。
     *
     * 没引 Flexbox 那类库 —— 只为这一个地方加一条依赖不划算；
     * 逻辑就是"按顺序摆，放不下就换行"，几十行够用。
     */
    private class WrapRow(context: Context) : ViewGroup(context) {
        private var childX = IntArray(0)
        private var childY = IntArray(0)
        private val gap: Int = (6 * context.resources.displayMetrics.density).toInt()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val avail = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                Int.MAX_VALUE
            } else {
                (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            }
            childX = IntArray(childCount)
            childY = IntArray(childCount)
            val heights = IntArray(childCount)

            var x = 0
            var y = 0
            var lineH = 0
            var widest = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                measureChild(
                    c,
                    MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
                    heightMeasureSpec
                )
                val lp = c.layoutParams as? MarginLayoutParams
                val need = c.measuredWidth + (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0) + gap
                if (x > 0 && x + need - gap > avail) {
                    y += lineH
                    lineH = 0
                    x = 0
                }
                childX[i] = x + (lp?.leftMargin ?: 0)
                childY[i] = y + (lp?.topMargin ?: 0)
                heights[i] = c.measuredHeight
                x += need
                widest = maxOf(widest, x - gap)
                lineH = maxOf(lineH, c.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0))
            }

            setMeasuredDimension(
                resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(y + lineH + paddingTop + paddingBottom, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                c.layout(
                    paddingLeft + childX[i],
                    paddingTop + childY[i],
                    paddingLeft + childX[i] + c.measuredWidth,
                    paddingTop + childY[i] + c.measuredHeight
                )
            }
        }

        override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
        override fun generateDefaultLayoutParams(): LayoutParams =
            MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams =
            MarginLayoutParams(context, attrs)
        override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
    }

    /**
     * 解析 EXIF 并挂进详情区。
     *
     * JPEG 直接读 Exif；NEF 本身是 TIFF 容器，同一套解析也能读到 IFD0/ExifIFD 里的
     * 时间、曝光三要素（D300 的 NEF 是大端 MM，PhotoExif 已按字节序判断处理）。
     * 解析放在后台——NEF 十几 MB，别在主线程扫。
     */
    private fun attachExif(bytes: ByteArray?, detail: String, token: Int = viewerToken) {
        if (bytes == null || bytes.size < 64) return
        bg {
            val sum = PhotoExif.parse(bytes)
            if (sum.isEmpty) return@bg
            runOnUiThread {
                if (!pageAlive) return@runOnUiThread
                // 滑走了就不往旧那张上贴 EXIF
                if (token != viewerToken) return@runOnUiThread
                viewerExif = sum
                renderViewerInfo(detail)
            }
        }
    }

    // ------------------------------------------------------------------
    // 查看器的缩放 / 平移
    //
    // 单张预览要看细节（对焦准没准、噪点），只能放大。这里手搓 Matrix 缩放，
    // 没引 PhotoView 那类三方库：多一个依赖多一份兼容风险，而需要的只是
    // "双指缩放 + 拖着看 + 双击回位"这三件事，几百行够用。
    // ------------------------------------------------------------------

    /** 适配屏幕的基准缩放（fitCenter 的那个比例） */
    private var fitScale = 1f
    /** 当前实际缩放 / 平移（作用在图本身上的最终矩阵状态） */
    private var imgScale = 1f
    private var imgTx = 0f
    private var imgTy = 0f
    /** 最大放大倍数（相对"刚适配屏幕"时的画面） */
    private val maxZoom = 6f

    private val matrix = android.graphics.Matrix()

    /** 图比屏幕小就居中，比屏幕大就不许拖出黑边 */
    private fun clampPan(): Boolean {
        val d = fullImage.drawable ?: return false
        val vw = fullImage.width.toFloat()
        val vh = fullImage.height.toFloat()
        if (vw <= 0f || vh <= 0f) return false
        val imgW = d.intrinsicWidth * imgScale
        val imgH = d.intrinsicHeight * imgScale
        imgTx = if (imgW <= vw) (vw - imgW) / 2f else imgTx.coerceIn(vw - imgW, 0f)
        imgTy = if (imgH <= vh) (vh - imgH) / 2f else imgTy.coerceIn(vh - imgH, 0f)
        return true
    }

    private fun applyMatrix() {
        if (!clampPan()) return
        matrix.reset()
        matrix.setScale(imgScale, imgScale)
        matrix.postTranslate(imgTx, imgTy)
        fullImage.imageMatrix = matrix
    }

    /** 缩放到"刚适配屏幕"的比例并居中 —— 换图 / 双击复位都走这里 */
    private fun fitImageToView() {
        val d = fullImage.drawable
        val vw = fullImage.width.toFloat()
        val vh = fullImage.height.toFloat()
        if (d == null || vw <= 0f || vh <= 0f) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        fitScale = minOf(vw / dw, vh / dh)
        imgScale = fitScale
        imgTx = (vw - dw * imgScale) / 2f
        imgTy = (vh - dh * imgScale) / 2f
        applyMatrix()
    }

    /** 统一的上屏入口：换图一律回到"适配屏幕"，上一张的放大状态不带到下一张 */
    private fun setViewerBitmap(bmp: android.graphics.Bitmap?) {
        fullImage.setImageBitmap(bmp)
        imgScale = 1f
        imgTx = 0f
        imgTy = 0f
        // ImageView 刚可见时宽高还没量出来，等一帧再算缩放矩阵
        fullImage.post { fitImageToView() }
    }

    private fun isZoomed(): Boolean = imgScale > fitScale * 1.01f

    /**
     * 查看器手势：左右滑动切换上一张 / 下一张 + 双指缩放 + 拖动 + 双击复位。
     *
     * 手势挂在整块查看层上（底部信息条、按钮在上面，它们的点击自己消费，滑动照样能收到）。
     * 门槛：横向位移 > 60dp 且明显大于纵向（1.5 倍），免得竖着划一下就翻页；
     * **画面处于放大状态时翻页手势让位给拖动**，否则看细节时一滑就换图了。
     */
    private fun setupViewerSwipe() {
        fullImage.scaleType = ImageView.ScaleType.MATRIX
        val slop = (60 * resources.displayMetrics.density).toInt()

        val scaleDetector = android.view.ScaleGestureDetector(
            this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val d = fullImage.drawable ?: return false
                    if (d.intrinsicWidth <= 0) return false
                    val prev = imgScale
                    imgScale = (imgScale * detector.scaleFactor).coerceIn(fitScale, fitScale * maxZoom)
                    val f = imgScale / prev
                    // 以双指中心为锚点：手指按住的那个点缩放前后停在原处
                    imgTx = detector.focusX - (detector.focusX - imgTx) * f
                    imgTy = detector.focusY - (detector.focusY - imgTy) * f
                    applyMatrix()
                    return true
                }
            }
        )

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (isZoomed()) return false        // 放大时是在看细节，不翻页
                val startX = e1?.x ?: return false
                val dx = e2.x - startX
                val dy = e2.y - (e1.y)
                if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(dy) * 1.5f) return false
                showPhotoAt(if (dx < 0) viewerIndex + 1 else viewerIndex - 1)
                return true
            }

            /** 放大后单指拖动看画面；未放大时不拦，交给翻页 */
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!isZoomed()) return false
                imgTx -= distanceX
                imgTy -= distanceY
                applyMatrix()
                return true
            }

            /** 双击：放大 2.5 倍 ⇄ 回到适配屏幕 */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isZoomed()) {
                    val target = (fitScale * 2.5f).coerceAtMost(fitScale * maxZoom)
                    val f = target / imgScale
                    imgTx = e.x - (e.x - imgTx) * f
                    imgTy = e.y - (e.y - imgTy) * f
                    imgScale = target
                } else {
                    imgScale = fitScale
                }
                applyMatrix()
                return true
            }
        })

        viewerOverlay.isClickable = true
        viewerOverlay.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            scaleDetector.onTouchEvent(ev)
            gesture.onTouchEvent(ev)
            true
        }
    }

    /** 开查看器：定位下标并重载（已是打开状态时用于滑动切换） */
    private fun showPhotoAt(index: Int) {
        if (index < 0 || index >= entries.size) {
            Toast.makeText(
                this,
                if (entries.isEmpty()) "还没读到照片" else if (index < 0) "已是第一张" else "已是最后一张",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        openViewer(entries[index])
    }

    private fun openViewer(entry: Entry) {
        viewerIndex = entries.indexOf(entry)
        currentEntry = entry
        currentBytes = null
        viewerExif = null
        // ★ 令牌 +1：上一张还在传的原图/EXIF 回来时令牌对不上，一律丢弃，不许再动画面
        val token = ++viewerToken
        viewerOverlay.visibility = View.VISIBLE
        saveButton.isEnabled = false
        // 先把缓存的缩略图顶上去，避免拉原图那几秒白屏
        setViewerBitmap(entry.thumb)

        // 文件名单独一行（信息主体），格式和大小折到第二行，别挤成一条长串；
        // 末尾带上"第 x/y 张"，左右滑动时才知道滑到哪了
        val pos = if (entries.isNotEmpty()) " · 第 ${viewerIndex + 1}/${entries.size} 张" else ""
        val head = "${entry.info.filename}\n${entry.info.formatLabel} · ${formatSize(entry.info.byteSize)}$pos"
        viewerHead = head
        renderViewerInfo("正在读取原图…")
        viewerProgress.visibility = View.VISIBLE

        val ptp = PtpUsbConnection.activeInstance
        if (ptp == null) {
            viewerProgress.visibility = View.GONE
            renderViewerInfo("未连接相机")
            return
        }

        // ---- 删除按钮：先问机身认不认 0x100B，认不了就当场禁掉 ----
        //
        // 标准 PTP 的删除对象是 **0x100B**（不是 0x910B —— 那个码在 D300 固件
        // 的操作调度表里根本不存在，发过去机身一声不吭，usbLock 一直攥着，
        // 整条 USB 通道就全堵死了，表现就是"点了没反应 + 之后什么都发不出去"）。
        // 但"码对"≠"这台机器支持"：D300 的 OperationsSupported 未必列它，
        // 所以打开查看器时查一次，不支持就把按钮禁用并写明原因，
        // 而不是让用户按完删除才弹一句"做不到"。
        viewerDeleteButton.isEnabled = true
        viewerDeleteButton.alpha = 1f
        viewerDeleteButton.text = "从相机删除"
        bg {
            val canDelete = ptp.supportsOperation(PtpConstants.OP_DELETE_OBJECT)
            if (!pageAlive || token != viewerToken) return@bg
            runOnUiThread {
                if (!pageAlive || token != viewerToken) return@runOnUiThread
                if (canDelete == false) {
                    viewerDeleteButton.isEnabled = false
                    viewerDeleteButton.alpha = 0.35f
                    viewerDeleteButton.text = "删除（机身不支持）"
                    android.util.Log.i("GALLERY", "机身 OperationsSupported 不含 0x100B，已禁用删除")
                } else {
                    viewerDeleteButton.isEnabled = true
                    viewerDeleteButton.alpha = 1f
                    viewerDeleteButton.text = "从相机删除"
                }
            }
        }

        // 字节已经在缓存里（刚看过 / 刚导出过）：不用再走 USB，图 + EXIF 立刻到位
        val cachedBytes = synchronized(fullBytesLock) { fullBytes[entry.info.handle] }
        if (cachedBytes != null) {
            viewerProgress.visibility = View.GONE
            showBytes(entry, cachedBytes, token)
            return
        }

        // NEF 先看预览缓存：虽然字节没留住，位图还在就能先顶上，至少不是空白
        val cached = synchronized(previewLock) { previewCache.get(entry.info.handle) }
        if (cached != null) setViewerBitmap(cached)

        bg {
            if (!pageAlive) return@bg
            // ★ 只取一次：预览、EXIF、保存共用这一份字节
            // （以前 NEF 要传两遍整文件，EXIF 还得等第二遍传完才出现）
            val bytes = fetchFull(ptp, entry.info)
            runOnUiThread {
                // 已经滑到别的照片：这张的结果全部作废（画面 / EXIF / 保存按钮都不许动）
                if (token != viewerToken) return@runOnUiThread
                viewerProgress.visibility = View.GONE
                if (bytes == null) {
                    renderViewerInfo("原文件读取失败，无法保存")
                    return@runOnUiThread
                }
                showBytes(entry, bytes, token)
            }
        }
    }

    /**
     * 按屏幕尺寸采样解码的结果。
     * [width]/[height] 是**原始**像素尺寸（只是拿来给界面显示"4288×2848"），
     * [bitmap] 则是采样后的位图 —— 两者故意分开，别再拿 bitmap 的宽高当原图尺寸。
     */
    private class Decoded(val bitmap: Bitmap, val width: Int, val height: Int)

    /**
     * 采样解码：先用 inJustDecodeBounds 探尺寸，再按屏幕宽度算 inSampleSize。
     *
     * 为什么非要采样：4288×2848 全尺寸解一张就是 ~49 MB 的 ARGB_8888 位图，
     * 翻几张就顶到堆上限。1080p / 1440p 手机上算下来 sample 都是 2，
     * 内存降到 1/4，2 倍缩放以内看不出差别 —— 继续放大才会发虚，这是有意的取舍。
     */
    private fun decodeSampled(bytes: ByteArray, offset: Int, length: Int): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // ImageView 还没量出来时退回屏幕宽度
        val req = maxOf(fullImage.width, resources.displayMetrics.widthPixels).coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= req) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, offset, length, opts)
        } catch (e: Exception) {
            null
        } ?: return null
        return Decoded(bmp, bounds.outWidth, bounds.outHeight)
    }

    /** 完整字节到手后的统一出口：上画面 + 开保存 + 立刻解析 EXIF */
    private fun showBytes(entry: Entry, bytes: ByteArray, token: Int) {
        currentBytes = bytes
        saveButton.isEnabled = true
        if (entry.info.isJpeg) {
            val d = decodeSampled(bytes, 0, bytes.size)
            if (d != null) {
                setViewerBitmap(d.bitmap)
                val dims = "${d.width}×${d.height}"
                renderViewerInfo(dims)
                attachExif(bytes, dims, token)
            } else {
                renderViewerInfo("解码失败")
                attachExif(bytes, "解码失败", token)
            }
        } else {
            // NEF 本身是 TIFF，Android 解不了 —— 抠它内嵌的那张 JPEG 预览
            val d = decodeNefPreviewSampled(bytes)
            if (d != null) {
                setViewerBitmap(d.bitmap)
                synchronized(previewLock) { previewCache.put(entry.info.handle, d.bitmap) }
                val dims = "预览 ${d.width}×${d.height}"
                renderViewerInfo(dims)
                attachExif(bytes, dims, token)
            } else {
                val fallback = "${entry.info.pixWidth}×${entry.info.pixHeight} · NEF 无内嵌预览，可保存原文件"
                renderViewerInfo(fallback)
                attachExif(bytes, fallback, token)
            }
        }
    }

    /** 已经拿到完整 NEF 字节时的抠图路径（同样按屏幕采样，不再全尺寸解） */
    private fun decodeNefPreviewSampled(full: ByteArray): Decoded? {
        val loc = NefPreview.locate(full) ?: return null
        val jpeg = NefPreview.slice(full, loc) ?: return null
        return decodeSampled(jpeg, 0, jpeg.size)
    }

    private fun closeViewer() {
        viewerOverlay.visibility = View.GONE
        // 令牌 +1：关掉时还有原图在传，它回来时不许再动画面
        viewerToken++
        viewerIndex = -1
        // 放大状态一并清掉，下次打开从"适配屏幕"开始
        imgScale = 1f
        imgTx = 0f
        imgTy = 0f
        currentEntry = null
        currentBytes = null
        fullImage.setImageDrawable(null)
    }

    /**
     * 离开相册：把后台 USB 事务收干净再走。
     *
     * 两件事必须做，缺一个都会让主界面跟着遭殃：
     * 1. 置 pageAlive = false —— 遍历/拉原图的循环立刻停手
     * 2. **给后台线程 1.5 秒收尾**（不是硬杀）—— 8 MB 的 NEF 传一半被掐断，
     *    相机的 USB 端点会 STALL，之后连命令都发不出去（实测只能物理重启相机）。
     *    接着把管道里剩下的残留读干净，主界面才不会读到上一次的响应。
     */
    override fun onStop() {
        pageAlive = false
        super.onStop()
        thread {
            val threads = synchronized(bgLock) { bgThreads.toList() }
            for (t in threads) {
                try { t.join(1500) } catch (_: Exception) {}
            }
            // 残留数据清干净：主界面回来后发的命令才会对上正确的响应
            PtpUsbConnection.activeInstance?.flushPendingData()
        }
    }

    override fun onDestroy() {
        pageAlive = false
        super.onDestroy()
        // 预览缓存里存的都是解码后的位图，一张几百 KB 到几 MB，
        // Activity 走了就得还给系统，不能靠 GC 慢慢收
        synchronized(previewLock) {
            for (b in previewCache.snapshot().values) b.recycle()
            previewCache.evictAll()
        }
        // 缩略图缓存同理（注意：网格里的 Entry 引用的是同一批位图对象，
        // 这里整页都要走了才回收，不能在切换相册时清）
        synchronized(thumbCache) {
            for (b in thumbCache.values) b?.recycle()
            thumbCache.clear()
        }
        // 原文件字节：一张 NEF 十几 MB，整页走了就别占着（位图要 recycle，字节交给 GC 即可）
        synchronized(fullBytesLock) { fullBytes.clear() }
    }

    // ------------------------------------------------------------------
    // 多选 → 导出到手机
    //
    // 单张「保存到手机」解决不了"一次带走一批"的需求：卡里几百张，
    // 想留 30 张就得点开 30 次。这里在网格上直接勾选，一次导出。
    // ------------------------------------------------------------------

    /** 长按某张照片：进多选并顺手勾上它 */
    private fun enterSelectMode(entry: Entry) {
        adapter.selected.add(entry.info.handle)
        setSelectMode(true)
    }

    /** 进出多选模式 */
    private fun setSelectMode(on: Boolean) {
        adapter.selectMode = on
        if (!on) adapter.selected.clear()
        selectionBar.visibility = if (on) View.VISIBLE else View.GONE
        // 多选期间不进单张查看：那会盖住整屏，勾了一半看不见底下的操作条
        if (!on) {
            selProgress.visibility = View.GONE
            selStatus.visibility = View.GONE
        }
        adapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val n = adapter.selected.size
        selCount.text = if (n == 0) "已选 0 张（点照片勾选）" else "已选 $n 张"
        // 第二行把当前品质写出来：一是让人知道会压到几，二是"长按能改"才有被发现的机会
        selJpgButton.text = "JPG 导出\n品质 $jpegQuality"
        selExportButton.isEnabled = n > 0 && !exporting
        selJpgButton.isEnabled = n > 0 && !exporting
    }

    /** 把字节写进手机相册（Pictures/D300），成功返回文件名 */
    private fun writeToPhone(name: String, mime: String, bytes: ByteArray): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                // API 29+ 生效；低版本会被忽略，落进 Pictures 根目录，不会崩
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/D300")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 导出勾选的那批照片。
     *
     * @param asJpg true = NEF 用 LibRaw 真解成 JPG（JPEG 原件仍原样拷贝）；
     *              false = 原图导出，相机里是什么字节就存什么字节。
     */
    private fun exportSelected(asJpg: Boolean) {
        val ptp = PtpUsbConnection.activeInstance
        if (ptp == null) { Toast.makeText(this, "未连接相机", Toast.LENGTH_SHORT).show(); return }
        if (exporting) return
        val targets = entries.filter { adapter.selected.contains(it.info.handle) }
        if (targets.isEmpty()) { Toast.makeText(this, "还没勾选照片", Toast.LENGTH_SHORT).show(); return }

        exporting = true
        selProgress.visibility = View.VISIBLE
        selProgress.isIndeterminate = false
        selProgress.max = targets.size
        selProgress.progress = 0
        selStatus.visibility = View.VISIBLE
        selStatus.text = "准备导出 ${targets.size} 张…"
        updateSelectionUi()

        // ------------------------------------------------------------------
        // 导出流水线：1 个下载线程 + N 个解码线程
        //
        // 以前是一条线程串行"传完一张 → 解一张"，30 张 NEF 得等到天荒地老：
        // USB 传输和 LibRaw 解码根本不抢同一份资源，却硬被排成一队。
        // 现在拆开 —— USB 必须串行（PtpUsbConnection 内部有 usbLock，本来也并发不了），
        // 但解码是纯 CPU，可以多开。下载线程往前跑，把字节丢进有界队列，
        // 解码线程各自取一张解完再写盘。
        //
        // 队列有界是刻意的：一张 NEF 十几 MB，一张解出来的 Bitmap 又是 49 MB
        // （4288×2848 ARGB_8888），无界预取会直接把堆撑爆。
        // 解码线程数按机器内存给，小内存机退回 1（实质还是串行），宁可慢也别崩。
        // ------------------------------------------------------------------
        val maxMemMb = Runtime.getRuntime().maxMemory() / 1048576
        val decoders = when {
            maxMemMb < 192 -> 1
            maxMemMb < 384 -> 2
            else -> minOf(3, maxOf(2, Runtime.getRuntime().availableProcessors() / 2))
        }
        val queue = ArrayBlockingQueue<ExportJob>(maxOf(1, decoders))

        bg {
            val ok = AtomicInteger()
            val failed = AtomicInteger()
            val converted = AtomicInteger()
            val done = AtomicInteger()

            /** 单张落地：JPEG 直存，NEF 看开关决定转档还是存原文件 */
            fun processOne(e: Entry, bytes: ByteArray) {
                if (asJpg && !e.info.isJpeg) {
                    // NEF 转 JPG：**真解 RAW**（LibRaw），不再抠机身内嵌的那张预览 ——
                    // 那张预览只有 239 KB，画质和体积都不像一张正常照片。
                    // 解出来的图没有 EXIF，NefDecoder 内部会把原 NEF 的元数据搬进 APP1。
                    val jpg = NefDecoder.toJpeg(bytes, cacheDir, jpegQuality)
                    if (jpg == null) {
                        // 解不出来就退回存原文件，别把这张丢了
                        if (writeToPhone(e.info.filename, "image/x-nikon-nef", bytes)) ok.incrementAndGet()
                        else failed.incrementAndGet()
                        return
                    }
                    val outName = e.info.filename.substringBeforeLast('.') + ".jpg"
                    if (writeToPhone(outName, "image/jpeg", jpg)) {
                        ok.incrementAndGet(); converted.incrementAndGet()
                    } else failed.incrementAndGet()
                } else {
                    val mime = if (e.info.isJpeg) "image/jpeg" else "image/x-nikon-nef"
                    if (writeToPhone(e.info.filename, mime, bytes)) ok.incrementAndGet()
                    else failed.incrementAndGet()
                }
            }

            // 解码/写盘：队列里取一张做一张，取到哨兵就收工
            val workers = (0 until decoders).map {
                bgThread {
                    while (pageAlive) {
                        val job = queue.take()
                        val e = job.entry ?: break          // 哨兵
                        val bytes = job.bytes ?: break
                        try {
                            processOne(e, bytes)
                        } catch (t: Throwable) {
                            android.util.Log.w("Export", "导出 ${e.info.filename} 异常：${t.message}")
                            failed.incrementAndGet()
                        }
                        val d = done.incrementAndGet()
                        runOnUiThread {
                            // 转档那一步要解 RAW，一张好几秒 —— 说清楚"第几张"，
                            // 否则进度条停在同一个数上看着像卡死了
                            selStatus.text = "已完成 $d/${targets.size}：${e.info.filename}"
                            selProgress.progress = d
                        }
                    }
                }
            }

            try {
                // 下载：串行占 USB，往前多取一点让解码线程不断粮
                for (e in targets) {
                    if (!pageAlive) break
                    val bytes = fetchFull(ptp, e.info)
                    if (bytes == null) {
                        failed.incrementAndGet()
                        val d = done.incrementAndGet()
                        runOnUiThread { selProgress.progress = d }
                        continue
                    }
                    queue.put(ExportJob(e, bytes))
                }
            } finally {
                // 无论怎么退出的，都得把解码线程放出来，否则它们永远卡在 take()
                repeat(decoders) { queue.offer(ExportJob(null, null)) }
            }

            for (w in workers) {
                try { w.join() } catch (_: Exception) {}
            }

            runOnUiThread {
                exporting = false
                selProgress.progress = targets.size
                selStatus.text = buildString {
                    append("导出完成：${ok.get()} 张已存到 相册/D300")
                    if (converted.get() > 0) append("（其中 ${converted.get()} 张 NEF 已转 JPG）")
                    if (failed.get() > 0) append(" · ${failed.get()} 张失败（详见日志）")
                }
                Toast.makeText(
                    this,
                    if (failed.get() > 0) "导出完成，有 ${failed.get()} 张失败" else "已导出 ${ok.get()} 张到 相册/D300",
                    Toast.LENGTH_LONG
                ).show()
                updateSelectionUi()
            }
        }
    }

    /** 流水线里传的一张活；entry 为 null 是收工哨兵 */
    private data class ExportJob(val entry: Entry?, val bytes: ByteArray?)

    // ---------------- JPG 品质 ----------------

    /** 档位下面那行小字：光看数字不知道差别，得说清代价 */
    private fun qualityNote(q: Int): String = when {
        q >= 100 -> "几乎无损"
        q >= 95 -> "肉眼无损"
        q >= 92 -> "推荐"
        q >= 85 -> "更省空间"
        q >= 75 -> "压缩可见"
        else -> "体积最小"
    }

    /**
     * 挑导出 JPG 的压缩质量（底部抽屉，跟参数页那套二级页同款）。
     *
     * 只对 NEF 转档生效 —— JPEG 原件是整份拷贝，改不了也改不好。
     *
     * 以前这里用系统 `AlertDialog.setSingleChoiceItems`：白底蓝字的系统弹窗
     * 在这块深色盘面上很跳，一行还挤不下"档位 + 说明"。
     * 换成 [buildSheetPage] 之后：方块格子选档，下面一行小字说清代价，
     * 当前档高亮并在打开时就滚到视野里。
     *
     * @param onPicked 给了回调就"选完顺手执行"（单张导出走这条路）；
     *                 没给就只是把选出那档记住（多选底栏走这条路）。
     */
    private fun showQualitySheet(onPicked: ((Int) -> Unit)? = null) {
        val steps = NefDecoder.QUALITY_STEPS
        val current = steps.indexOf(jpegQuality).coerceAtLeast(0)

        val page = buildSheetPage("JPG 品质", "只对 NEF 转档生效 · JPEG 原件原样拷贝")

        val card = sheetCard(page.content, "压缩档位")
        // 回调里要把刚点的那格点亮，得先有变量 —— 点击一定发生在赋值之后，lateinit 安全
        lateinit var grid: SheetTileGrid
        grid = sheetTileGrid(
            columns = 3,
            labels = steps.map { q -> "$q\n${qualityNote(q)}" },
            initialSelected = current
        ) { which ->
            val q = steps[which]
            jpegQuality = q
            getSharedPreferences("gallery", MODE_PRIVATE).edit()
                .putInt("jpeg_quality", q).apply()
            grid.select(which)
            updateSelectionUi()
            page.dialog.dismiss()
            onPicked?.invoke(q)
        }
        card.addView(grid.view)
        page.onDismiss { grid.centerOn(steps.indexOf(jpegQuality).coerceAtLeast(0)) }

        val card2 = sheetCard(page.content, "怎么选")
        card2.addView(
            sheetHint(
                "92 是默认档：肉眼看不出和有损压缩的区别，体积也还能接受。\n" +
                    "要拿去后期就往上选 95 / 100；只是发朋友圈或同传异地，\n" +
                    "75 / 85 能把一张 8 MB 的图压到一半以下。\n\n" +
                    "相机里的 JPEG 是机身自己压好的，导出时**原样拷贝**，\n" +
                    "这里选什么都改不动它 —— 想改只能从 NEF 重新解。"
            )
        )

        page.show()
        grid.centerOn(current)
    }

    // ---------------- 单张：导出 JPG / 删除 ----------------

    /** 单张「导出为 JPG」：弹出品质选择，选完立即导出 */
    private fun exportCurrentAsJpgWithQuality() {
        val entry = currentEntry ?: return
        PtpUsbConnection.activeInstance
            ?: run {
                Toast.makeText(this, "未连接相机", Toast.LENGTH_SHORT).show()
                return
            }
        // JPEG 原件是整份拷贝、不经 LibRaw 重编码，品质选了也没用 —— 别拿假选项糊弄人
        if (entry.info.isJpeg) {
            exportCurrentAsJpg()
            return
        }
        showQualitySheet { exportCurrentAsJpg() }
    }

    /** 把当前这张导出成 JPG 到手机：NEF 走 LibRaw 真解 + 搬 EXIF，JPEG 原件直接拷 */
    private fun exportCurrentAsJpg() {
        val entry = currentEntry ?: return
        val ptp = PtpUsbConnection.activeInstance
        if (ptp == null) {
            Toast.makeText(this, "未连接相机", Toast.LENGTH_SHORT).show()
            return
        }
        viewerJpgButton.isEnabled = false
        viewerProgress.visibility = View.VISIBLE

        // 已有整份字节就直接用（查看大图已经拉过一次了，别再传第二遍）
        val cached = currentBytes
        thread {
            val bytes = cached ?: fetchFull(ptp, entry.info)
            if (bytes == null) {
                runOnUiThread {
                    Toast.makeText(this, "取原图失败", Toast.LENGTH_SHORT).show()
                    afterCurrentExport()
                }
                return@thread
            }
            val (outBytes, name) = if (entry.info.isJpeg) {
                bytes to entry.info.filename
            } else {
                val jpg = NefDecoder.toJpeg(bytes, cacheDir, jpegQuality)
                if (jpg == null) {
                    runOnUiThread { Toast.makeText(this, "这张 NEF 解不出来", Toast.LENGTH_SHORT).show() }
                    afterCurrentExport()
                    return@thread
                }
                jpg to (entry.info.filename.substringBeforeLast('.') + ".jpg")
            }
            val ok = writeToPhone(name, "image/jpeg", outBytes)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "已导出 $name（${formatSize(outBytes.size.toLong())}）" else "导出失败",
                    Toast.LENGTH_LONG
                ).show()
                afterCurrentExport()
            }
        }
    }

    private fun afterCurrentExport() {
        viewerProgress.visibility = View.GONE
        viewerJpgButton.isEnabled = true
    }

    /**
     * 删除当前这张（不可恢复）。
     *
     * 二次确认是必须的：PTP 删除没有回收站，而且这是在删用户相机里的原始素材，
     * 手机上的副本并不能代表这个动作可以撤销。
     */
    private fun confirmDeleteCurrent() {
        val entry = currentEntry ?: return
        val ptp = PtpUsbConnection.activeInstance
        if (ptp == null) {
            Toast.makeText(this, "未连接相机", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("从相机删除这张？")
            .setMessage("${entry.info.filename}\n\n卡上的原文件会被删掉，不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                viewerDeleteButton.isEnabled = false
                viewerProgress.visibility = View.VISIBLE
                thread {
                    // ★ 先问机身认不认这条命令，别硬发：
                    // 不支持的命令发出去后机身可能一声不吭，usbLock 一直攥着，
                    // 整条 USB 通道就全堵死了（"点了没反应 + 之后什么都发不出去"）。
                    val supported = ptp.supportsOperation(PtpConstants.OP_DELETE_OBJECT)
                    android.util.Log.i("GALLERY", "机身支持 0x100B DeleteObject？ $supported")
                    if (supported == false) {
                        runOnUiThread {
                            finishDelete(false, "机身不支持 USB 删除，请用机身上的删除键")
                        }
                        return@thread
                    }
                    val (ok, why) = ptp.deleteObject(entry.info.handle)
                    runOnUiThread { finishDelete(ok, why, entry) }
                }
            }
            .show()
    }

    /** 删除的收尾：结果提示 + 成功才从列表摘掉 */
    private fun finishDelete(ok: Boolean, why: String, entry: Entry? = null) {
        viewerProgress.visibility = View.GONE
        viewerDeleteButton.isEnabled = true
        if (!ok) {
            Toast.makeText(this, "删除失败：$why", Toast.LENGTH_LONG).show()
            return
        }
        val name = entry?.info?.filename
        if (entry != null) {
            Toast.makeText(this, "已删除 $name", Toast.LENGTH_SHORT).show()
            // 从列表里摘掉，别让它还挂在网格上显示（点开只会再报一遍错）
            val idx = entries.indexOfFirst { it.info.handle == entry.info.handle }
            if (idx >= 0) {
                entries.removeAt(idx)
                adapter.notifyItemRemoved(idx)
                updateCountText()
            }
        }
        if (entry != null) closeViewer()
    }

    private fun saveCurrent() {
        val entry = currentEntry ?: return
        val bytes = currentBytes ?: return
        saveButton.isEnabled = false

        // 保存到手机：纯本地 IO，不占 USB，不用登记进 bgThreads
        thread {
            val mime = if (entry.info.isJpeg) "image/jpeg" else "image/x-nikon-nef"
            val ok = writeToPhone(entry.info.filename, mime, bytes)
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "已保存到 相册/D300/${entry.info.filename}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "保存失败：无法创建文件", Toast.LENGTH_SHORT).show()
                }
                saveButton.isEnabled = true
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private class ThumbAdapter(
        private val entries: List<Entry>,
        private val onClick: (Entry) -> Unit,
        /** 勾选变化：用来刷新底部"已选 N 张" */
        private val onSelectionChanged: () -> Unit,
        /** 长按某张：直接进入多选并勾上它（不用先去顶栏点「多选」再回来找） */
        private val onEnterSelect: (Entry) -> Unit
    ) : RecyclerView.Adapter<ThumbAdapter.Holder>() {

        /** 多选模式：点格子变成勾选，右上角出现勾选框 */
        var selectMode = false

        /** 已勾选的照片句柄（用句柄而不是下标：切相册/刷新时下标会变） */
        val selected = mutableSetOf<Int>()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.thumb)
            val badge: TextView = view.findViewById(R.id.badge)
            val name: TextView = view.findViewById(R.id.filename)
            val check: android.widget.CheckBox = view.findViewById(R.id.check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gallery_photo, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            if (entry.thumb != null) {
                holder.thumb.setImageBitmap(entry.thumb)
                holder.thumb.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            } else {
                // 缩略图没取到（罕见）：留深色占位，至少网格位置不乱
                holder.thumb.setImageDrawable(null)
            }

            // 左下角文件名：不带后缀（格式由右下角那枚角标负责）
            holder.name.text = entry.info.filename.substringBeforeLast('.')

            // JPEG 是默认预期，不用标注；NEF 等解不了的要标出来
            if (entry.info.isJpeg) {
                holder.badge.visibility = View.GONE
            } else {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = entry.info.formatLabel
            }

            holder.check.visibility = if (selectMode) View.VISIBLE else View.GONE
            holder.check.isChecked = selected.contains(entry.info.handle)
            // 选中的格子整体压暗一点，隔着 thumbnail 也能一眼看出勾了哪些
            holder.itemView.alpha = if (selectMode && holder.check.isChecked) 0.65f else 1f

            holder.itemView.setOnClickListener {
                if (selectMode) {
                    val h = entry.info.handle
                    if (!selected.add(h)) selected.remove(h)
                    notifyItemChanged(position)
                    onSelectionChanged()
                } else {
                    onClick(entry)
                }
            }
            // 长按直接进入多选：不用先去顶栏点「多选」再回来找照片
            holder.itemView.setOnLongClickListener {
                if (!selectMode) onEnterSelect(entry)
                true
            }
        }

        override fun getItemCount() = entries.size
    }
}
