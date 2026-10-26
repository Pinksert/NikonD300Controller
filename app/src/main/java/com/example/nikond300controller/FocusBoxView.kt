package com.example.nikond300controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView

/** 一个对焦点：id 是写给机身 0xD108 的值，nx/ny 是画面上的归一化位置 */
data class AfPoint(val id: Int, val nx: Float, val ny: Float)

/**
 * D300 「11 点」模式下的对焦点。
 *
 * id 与坐标来自 remoteyourcam-usb `NikonCamera.getFocusPoints()` 里 D300 分支的实机数据
 * （原代码是 `FocusPoint(id, posx, posy, radius)`）。这些 id 是按机身内部编码排的，
 * 不是 0..10 的序号 —— 写错 id 机身会直接拒，所以这张表不能自己编。
 */
val D300_AF_POINTS_11 = listOf(
    AfPoint(1, 0.50f, 0.50f),   // 中心
    AfPoint(3, 0.50f, 0.36f),   // 中心列·上
    AfPoint(5, 0.50f, 0.64f),   // 中心列·下
    AfPoint(21, 0.65f, 0.50f),  // 右列·中
    AfPoint(23, 0.65f, 0.40f),
    AfPoint(25, 0.65f, 0.60f),
    AfPoint(31, 0.75f, 0.50f),  // 最右
    AfPoint(39, 0.35f, 0.50f),  // 左列·中
    AfPoint(41, 0.35f, 0.40f),
    AfPoint(43, 0.35f, 0.60f),
    AfPoint(49, 0.25f, 0.50f)   // 最左
)

/** 51 点阵里已实机验证过的 11 个 id（0xD108 真机写成功、方向键走通） */
val D300_AF_KNOWN_IDS = setOf(1, 3, 5, 21, 23, 25, 31, 39, 41, 43, 49)

/**
 * D300 「51 点」模式的全点阵。
 *
 * **拓扑来自 D300 说明书取景器图**（把图里 51 个对焦框逐框测量得到）：
 * 11 列 × 5 行、去掉 4 个角 = 51 点，每行框数 9/11/11/11/9。
 * 这与 libgphoto2 / imaging-resource 引尼康官方图说的描述一致
 * （「45 点排成 9 列 × 5 行矩形，其余 6 点分居中行左右各 3 点」）。
 *
 * 间距取均匀网格：横向 0.25 + 0.05×列，纵向 0.40 + 0.05×行 ——
 * 与 remoteyourcam 实机 11 点表的 5 个横向锚点（0.25/0.35/0.50/0.65/0.75）
 * 和 3 个纵向锚点（0.40/0.50/0.60）完全吻合（它给的 0.36/0.64 与
 * 同表 0.40/0.60 自相矛盾，是手绘值，不采信）。
 *
 * **id 现在是官方编号**（2026-10-26 落码）：尼康官方 PTP 规格书
 * `D300UsbPtpE_01.pdf` 第 69 页给了 PropertyValue 与对焦点位置的对照图，
 * 把每个数字的坐标量出来还原后就是下表的 11 列 × 5 行（去掉最左/最右列的
 * 上下 4 个角 = 51 点）—— **中心 = 1，左右两半各自蛇形编号**。
 *
 * 这样就不再需要机身上的「标定 51 点」：每个格子都是真编号，写 0xD108 直接生效。
 * 交叉校验过：项目里 11 个实机锚点（(5,0)=3 (5,2)=1 (5,4)=5 (8,0)=23 (8,2)=21
 * (8,4)=25 (2,0)=41 (2,2)=39 (2,4)=43 (0,2)=49 (10,2)=31）与官方表逐个吻合。
 */
val D300_AF_GRID_51: List<AfPoint> = buildList {
    // ids[列][行]，列 0..10 左→右、行 0..4 上→下；0 = 该位置没有对焦点
    val ids = arrayOf(
        intArrayOf(0, 50, 49, 51, 0),        // 列 0（最左，只有中间 3 点）
        intArrayOf(46, 45, 44, 47, 48),      // 列 1
        intArrayOf(41, 40, 39, 42, 43),      // 列 2
        intArrayOf(36, 35, 34, 37, 38),      // 列 3
        intArrayOf(13, 12, 11, 14, 15),      // 列 4
        intArrayOf(3, 2, 1, 4, 5),           // 列 5（中心列）
        intArrayOf(8, 7, 6, 9, 10),          // 列 6
        intArrayOf(18, 17, 16, 19, 20),      // 列 7
        intArrayOf(23, 22, 21, 24, 25),      // 列 8
        intArrayOf(28, 27, 26, 29, 30),      // 列 9
        intArrayOf(0, 32, 31, 33, 0)         // 列 10（最右）
    )
    for (row in 0..4) for (col in 0..10) {
        val id = ids[col][row]
        if (id == 0) continue
        add(AfPoint(id, 0.25f + 0.05f * col, 0.40f + 0.05f * row))
    }
}

/**
 * 取景画面上的对焦图层，两种用法：
 *
 * 1. **[Mode.LIVE]** 叠在实时画面上，画机身报回来的 AF 框；点一下画面就把框挪过去。
 * 2. **[Mode.POINTS]** 当对焦屏用，画出 11 个可选对焦点，点一下就选中它。
 *
 * 坐标一律归一化（0..1，相对整幅画面）。屏幕 → 画面的换算走 ImageView 的显示矩阵，
 * 这样框（和点）贴的是画面本身，而不是贴着控件 —— 取景区被压扁或拉伸时都不会跑偏。
 */
class FocusBoxView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Mode { LIVE, POINTS }

    var mode: Mode = Mode.LIVE
        set(value) { field = value; postInvalidate() }

    /**
     * [Mode.LIVE] 下是否在画面上叠加对焦点阵。
     *
     * 手持模式的取景是 51 点相位检测，对焦点只能在固定点位之间跳，
     * 点画面发绝对坐标（0x9205）机身收了也不动 —— 这时候唯一好用的选点方式
     * 就是把点阵画在实时画面上，点哪个点就写哪个 id 给 0xD108。
     * 三脚架模式能自由挪 AF 框，点阵反而挡视线，由 renderLiveViewMode() 关掉。
     *
     * **默认开**：取景窗口一进界面就该看到对焦点阵（未取景时窗口里也是点阵），
     * 这是现在选对焦点的主入口。
     */
    var pointsOverlay = true
        set(value) { field = value; postInvalidate() }

    /**
     * 构图网格（「取景框显示」）：叠在实时取景画面上的辅助线。
     *
     * **纯 App 侧的叠加层，协议里没有对应能力。** 别跟机身那个「取景器网格显示」混为一谈：
     * 后者是个人设定 d2 = **0xD16C GridDisplay**（官方属性表 menu=d2），管的是**光学取景器**
     * 里那几条刻线，跟这里画在手机屏幕上的线是两套东西，App 也不去写它。
     */
    enum class GridStyle { OFF, THIRDS, THIRDS_CENTER, DENSE }

    var gridStyle: GridStyle = GridStyle.OFF
        set(value) { field = value; postInvalidate() }

    /** 参照的 ImageView：算出画面在屏幕上的实际矩形。Mode.POINTS 下可以不设。 */
    private var imageView: ImageView? = null

    // ---- AF 框（归一化）----
    private var boxCx = -1f
    private var boxCy = -1f
    private var boxW = 0.05f
    private var boxH = 0.05f
    private var hasBox = false
    /** true = 这个框是"用户刚点、机身还没回帧"，画成半透明以示未确认 */
    private var boxPending = false

    // ---- 对焦点阵 ----
    private var points: List<AfPoint> = D300_AF_POINTS_11
    private var selectedId = -1
    private var highlightId = -1

    /** 取景中点了画面：回调归一化坐标（**相对整幅画面**，已把放大还原掉） */
    var onAreaPicked: ((Float, Float) -> Unit)? = null
    /** 对焦屏选点：回调机身要的 id */
    var onPointPicked: ((Int) -> Unit)? = null

    // ---- 机身放大（0xD1A3）----
    //
    // 放大时机身回传的是"裁一块放大后的画面"：屏幕上的 0..1 不再等于整幅画面的 0..1。
    // 不换算的话，对焦点阵会贴在错误的画面位置上（放大后点哪儿都对不上），
    // 三脚架模式下点画面发出的 0x9205 坐标也会偏出去。
    // 换算：显示 = 0.5 + (整幅 − 放大中心) × 倍率，反过来就是下面两个函数。
    private var zoomScale = 1f
    private var zoomCx = 0.5f
    private var zoomCy = 0.5f

    /**
     * 告诉图层"机身现在放大到几倍、绕着哪个点放大"。
     * @param scale 倍率（1 = 不放大）；@param cx/cy 放大中心，整幅画面的归一化坐标
     */
    fun setZoom(scale: Float, cx: Float, cy: Float) {
        zoomScale = if (scale > 1.02f) scale else 1f
        zoomCx = cx.coerceIn(0f, 1f)
        zoomCy = cy.coerceIn(0f, 1f)
        postInvalidate()
    }

    fun zoomScaleNow(): Float = zoomScale

    /**
     * 松手前把"待确认框"吸到最近的对焦点上（手持 51 点模式为 true）。
     *
     * 手持的 51 点是相位检测固定点位，机身只认那 51 个 id；手指落点在两点之间时，
     * 先画在手指处、等帧头回来再一跳，看起来像"点歪了又自己纠正"。
     * 这里干脆在松手瞬间就画到真正的点上。三脚架模式设 false（AF 框能自由挪）。
     */
    var snapPreviewToPoints = false

    /** 手指还按在画面上（拖动选点进行中） */
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f

    /** 拖动中吸附到的对焦点；null = 没吸到（点阵没显示 / 离得太远） */
    private var dragCandidate: AfPoint? = null

    /** 显示坐标（屏幕上的 0..1）→ 整幅画面坐标 */
    private fun displayToFull(x: Float, y: Float): Pair<Float, Float> {
        if (zoomScale <= 1f) return Pair(x, y)
        return Pair(zoomCx + (x - 0.5f) / zoomScale, zoomCy + (y - 0.5f) / zoomScale)
    }

    /** 整幅画面坐标 → 显示坐标；返回 null 表示这一项已经被放大推出可视区 */
    private fun fullToDisplay(x: Float, y: Float): Pair<Float, Float>? {
        if (zoomScale <= 1f) return Pair(x, y)
        val dx = 0.5f + (x - zoomCx) * zoomScale
        val dy = 0.5f + (y - zoomCy) * zoomScale
        // 留一点余量：正好压在边上的点还是画出来（半个点露在外面比整点消失好认）
        if (dx < -0.02f || dx > 1.02f || dy < -0.02f || dy > 1.02f) return null
        return Pair(dx, dy)
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9F0A")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(6f, 0f, 0f, Color.parseColor("#66000000"))
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
    }

    private val pointActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9F0A")
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    /**
     * 取景画面上「没被选中」的对焦点：画成**很淡的小实心点**。
     *
     * 51 点在屏幕上只隔 0.05（约 18dp），画成一圈圈空心圆会连成一张网，
     * 既挡住画面又让人分不清选了哪个。淡点 + 当前点高亮，画面干净得多，
     * 手指也不用再去"戳中"某个点 —— 见 [onTouchEvent] 的拖动选点。
     */
    private val pointFaintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#59FFFFFF")
        style = Paint.Style.FILL
    }

    /** 拖动过程中手指"吸"到的那个点：半透明橙圈，松手才生效 */
    private val pointCandidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FF9F0A")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    /** 当前点外面再套一圈白环：51 个点里一眼就能找到"现在对的是哪" */
    private val pointRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1AFFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1f
    }

    /** 构图网格：比点阵参考线亮一档（压在实拍画面上要看得见），仍不抢戏 */
    private val composePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1f
    }

    private val composeCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private val displayRect = RectF()
    private val afRect = RectF()
    private val tmpMatrix = Matrix()

    init {
        // 这个 View 盖在取景画面上，不吃掉触摸的话点击会穿到下面去
        isClickable = true
        isFocusable = false
    }

    fun attachImageView(iv: ImageView?) { imageView = iv }

    /** cx/cy: 对焦框中心（归一化）；w/h: 框宽高（归一化）；传 cx<0 隐藏 */
    fun updateFromFrame(imageView: ImageView, cx: Float, cy: Float, w: Float, h: Float) {
        if (cx < 0) {
            if (hasBox) { hasBox = false; postInvalidate() }
            return
        }
        boxCx = cx; boxCy = cy
        if (w > 0) boxW = w
        if (h > 0) boxH = h
        // 机身回了真实位置，覆盖掉之前那个"未确认"的乐观框
        boxPending = false
        hasBox = true
        postInvalidate()
    }

    /**
     * 用户点了画面、机身还没回帧时先画出来的框。
     *
     * 点按必须立刻有反应 —— 取景帧最快也要 100ms 才回来，中间那段空窗
     * 会让"点了对焦框没动"。等真帧回来时 [updateFromFrame] 会把它顶掉。
     */
    fun showPendingBox(cx: Float, cy: Float) {
        if (mode != Mode.LIVE) return
        boxCx = cx.coerceIn(0f, 1f)
        boxCy = cy.coerceIn(0f, 1f)
        boxPending = true
        hasBox = true
        postInvalidate()
    }

    fun setAfPoints(list: List<AfPoint>, selected: Int) {
        points = list
        selectedId = selected
        highlightId = -1
        postInvalidate()
    }

    /** 当前选中的对焦点 id，-1 表示还没读出来 */
    fun getSelectedId() = selectedId

    fun clear() {
        hasBox = false
        boxPending = false
        highlightId = -1
        postInvalidate()
    }

    /**
     * 画面在屏幕上的实际矩形。
     *
     * [Mode.LIVE]：跟着 ImageView 的显示矩阵走（fitCenter 之后的那块），
     * 拿不到图就退回整个控件。
     * [Mode.POINTS]：按 D300 的 3:2 画幅在控件里居中 —— 否则控件被拉宽时
     * 点位会横向散开，屏幕上的位置和机身实际对焦位置对不上。
     */
    private fun imageRect(): RectF {
        if (mode == Mode.POINTS) return rect3x2()
        val iv = imageView
        val d = iv?.drawable
        if (iv != null && d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
            val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            iv.imageMatrix.mapRect(r)   // drawable 像素坐标 -> 控件坐标
            // ★ 矩阵可能是"上一次布局"留下的：退出全屏后 ImageView 被置 GONE，
            //   父容器不再给它 layout，imageMatrix 还停在铺满整屏那会儿的尺寸上 ——
            //   拿它算出来的矩形会比本控件大一圈，点阵就整片画歪（"对焦点错位"）。
            //   只要算出来的框跑出本控件范围，就退回 3:2 居中。
            val inside = r.left >= -2f && r.top >= -2f &&
                r.right <= width + 2f && r.bottom <= height + 2f
            if (r.width() > 1 && r.height() > 1 && inside) return r
        }
        // 还没取景（没有画面）时也按 3:2 居中：不然点阵会被控件拉成奇怪的比例，
        // 屏幕上的位置和机身实际对焦位置对不上
        return rect3x2()
    }

    /** D300 是 3:2 画幅：在控件里按 3:2 居中取最大矩形 */
    private fun rect3x2(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val target = 3f / 2f
        var w = vw
        var h = w / target
        if (h > vh) { h = vh; w = h * target }
        return RectF((vw - w) / 2f, (vh - h) / 2f, (vw - w) / 2f + w, (vh - h) / 2f + h)
    }

    /** 屏幕坐标 -> 画面归一化坐标 */
    private fun toNormalized(x: Float, y: Float): Pair<Float, Float> {
        if (mode == Mode.POINTS) {
            val r = imageRect()
            return Pair(
                ((x - r.left) / r.width().coerceAtLeast(1f)).coerceIn(0f, 1f),
                ((y - r.top) / r.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
            )
        }
        val iv = imageView
        val d = iv?.drawable
        if (iv != null && d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
            if (iv.imageMatrix.invert(tmpMatrix)) {
                val pts = floatArrayOf(x, y)
                tmpMatrix.mapPoints(pts)
                val nx = (pts[0] / d.intrinsicWidth).coerceIn(0f, 1f)
                val ny = (pts[1] / d.intrinsicHeight).coerceIn(0f, 1f)
                // 放大状态下屏幕坐标要先还原成整幅画面坐标再往外给
                return displayToFull(nx, ny)
            }
        }
        return displayToFull(
            (x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
            (y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        )
    }

    /**
     * 触摸：**按下 → 拖动换点 → 松手才落点**。
     *
     * 为什么要拖动：51 个点在手机上彼此只隔约 18dp，比指尖小得多 ——
     * 直接"点中想要的那个点"几乎不可能，一按就吸到隔壁去了。
     * 改成按下去先给个候选（半透明橙圈），拖着走候选跟着换，
     * 松手那一刻才算数、才写机身。跟相机上用方向键挪对焦点是一个手感。
     *
     * 只有 0.2 秒以内的点按和长拖没有区别：按哪儿、松手就算哪儿，
     * 所以老用户"点一下"的习惯不受影响。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        // 对焦屏（POINTS）：点一下就选，不需要拖动
        if (mode != Mode.LIVE) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val (nx, ny) = toNormalized(event.x, event.y)
                val p = nearestPoint(nx, ny) ?: return true
                selectedId = p.id
                highlightId = p.id
                postInvalidate()
                onPointPicked?.invoke(p.id)
                performClick()
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragX = event.x
                dragY = event.y
                updateDragCandidate()
                postInvalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                dragX = event.x
                dragY = event.y
                updateDragCandidate()
                postInvalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return true
                dragging = false
                dragX = event.x
                dragY = event.y
                updateDragCandidate()
                commitPick()
                hapticTap()
                dragCandidate = null
                postInvalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                dragCandidate = null
                postInvalidate()
                return true
            }
        }
        return true
    }

    /** 手指位置 → 最近的对焦点（点阵没显示时不做吸附） */
    private fun updateDragCandidate() {
        if (!pointsOverlay || points.isEmpty()) {
            dragCandidate = null
            return
        }
        val (nx, ny) = toNormalized(dragX, dragY)
        // 拖动时不设命中半径：落在网格外的画面里，"最近的点"依旧是机身会选的那个
        dragCandidate = nearestPoint(nx, ny, anyDistance = true)
    }

    /**
     * 松手：把这一次选择交给宿主。
     *
     * 有 [onAreaPicked]（开了触摸对焦 / 三脚架）就交归一化坐标 —— 由宿主决定
     * 是"手持吸附到对焦点写 0xD108"还是"三脚架发 0x9205 绝对坐标"；
     * 没有就退回点阵逐点选。
     */
    private fun commitPick() {
        val (nx, ny) = toNormalized(dragX, dragY)
        val cb = onAreaPicked
        if (cb != null) {
            // 手持：先把待确认框摆到真正的对焦点上，避免"框先画在手指处、随后跳一下"
            val snap = if (snapPreviewToPoints) dragCandidate else null
            if (snap != null) {
                selectedId = snap.id
                showPendingBox(snap.nx, snap.ny)
                cb(snap.nx, snap.ny)
            } else {
                showPendingBox(nx, ny)
                cb(nx, ny)
            }
            return
        }
        // 没接管整块画面（没开触摸对焦）：退回点阵命中，点空了就当没点
        val p = dragCandidate ?: nearestPoint(nx, ny) ?: return
        selectedId = p.id
        highlightId = p.id
        onPointPicked?.invoke(p.id)
    }

    /** 松手时的轻微触感：确认"这一下生效了"，不必盯着屏幕找反馈 */
    private fun hapticTap() {
        try {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) {
            // 有的机型关掉了触感反馈，忽略
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 取最近的一个点。
     *
     * @param anyDistance true = 不设命中半径，永远返回最近的那个（拖动选点用：
     *   手指可能落在网格外，但"最近的点"始终有意义，跟机身行为一致）
     */
    private fun nearestPoint(nx: Float, ny: Float, anyDistance: Boolean = false): AfPoint? {
        if (points.isEmpty()) return null
        var best: AfPoint? = null
        var bestD = Float.MAX_VALUE
        for (p in points) {
            val d = (p.nx - nx) * (p.nx - nx) + (p.ny - ny) * (p.ny - ny)
            if (d < bestD) { bestD = d; best = p }
        }
        if (anyDistance) return best
        // 命中半径要跟点阵密度走：51 点的相邻间距只有 0.05，
        // 沿用 11 点的 0.18 会变成"点哪儿都中"；放宽到 0.09 是"一格半"，
        // 手指偏半格以内仍能选中想选的那个
        val threshold = if (points.size > 20) 0.09f else 0.18f
        return if (bestD <= threshold * threshold) best else null
    }

    /** 录制时置 true：完全跳过叠加层绘制（先用来判断"视频里的框"到底是不是我们画的） */
    var debugSuppressOverlay = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (debugSuppressOverlay) return
        displayRect.set(imageRect())
        if (displayRect.isEmpty) return

        if (mode == Mode.POINTS) {
            drawPoints(canvas, grid = true)
        } else {
            // 构图网格画在最底层：它是背景参考，不该压住对焦框和点阵
            drawCompositionGrid(canvas)
            // 取景中：机身报回来的 AF 框照画，点阵开着就叠在上面。
            // 两者不冲突 —— 框是机身实际对焦的位置，点是可选的位置。
            if (hasBox) drawBox(canvas)
            if (pointsOverlay) drawPoints(canvas, grid = false)
        }
    }

    /**
     * 构图网格（「取景框显示」）。
     *
     * 网格线是**画面的构图参考**，所以它贴的是画面本身 —— 机身一放大，
     * 线跟着一起放大平移（三线之间的距离拉开、被推出可视区的线就不画）。
     * 换算走和 AF 框同一套 [fullToDisplay]，放大状态下这两者才会一起动，
     * 否则放大看细节时"线在一个位置、画面在另一个位置"，反而更误导。
     */
    private fun drawCompositionGrid(canvas: Canvas) {
        if (gridStyle == GridStyle.OFF) return
        val w = displayRect.width()
        val h = displayRect.height()
        if (w <= 1 || h <= 1) return

        val fracs = when (gridStyle) {
            GridStyle.THIRDS, GridStyle.THIRDS_CENTER -> floatArrayOf(1f / 3f, 2f / 3f)
            GridStyle.DENSE -> floatArrayOf(0.25f, 0.5f, 0.75f)
            else -> return
        }
        // 竖线：整幅画面里 x = f 的那条。放大后若被推出可视区，fullToDisplay 返回 null，跳过。
        for (f in fracs) {
            val p = fullToDisplay(f, 0.5f) ?: continue
            val x = displayRect.left + p.first * w
            canvas.drawLine(x, displayRect.top, x, displayRect.bottom, composePaint)
        }
        // 横线：整幅画面里 y = f 的那条
        for (f in fracs) {
            val p = fullToDisplay(0.5f, f) ?: continue
            val y = displayRect.top + p.second * h
            canvas.drawLine(displayRect.left, y, displayRect.right, y, composePaint)
        }
        if (gridStyle == GridStyle.THIRDS_CENTER) {
            // 中心十字：整幅画面的正中心，同样跟着放大走
            val p = fullToDisplay(0.5f, 0.5f) ?: return
            val cx = displayRect.left + p.first * w
            val cy = displayRect.top + p.second * h
            val arm = (w.coerceAtMost(h) * 0.03f).coerceAtLeast(resources.displayMetrics.density * 6f)
            canvas.drawLine(cx - arm, cy, cx + arm, cy, composeCenterPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, composeCenterPaint)
        }
    }

    private fun drawPoints(canvas: Canvas, grid: Boolean) {
        val w = displayRect.width()
        val h = displayRect.height()
        // 三分构图参考线：对焦屏上有点参照才好判断选了哪；叠在实时画面上则不要，
        // 取景画面本身就是参照，再画线只会脏。
        if (grid) {
            for (i in 1..2) {
                canvas.drawLine(displayRect.left + w * i / 3f, displayRect.top,
                    displayRect.left + w * i / 3f, displayRect.bottom, gridPaint)
                canvas.drawLine(displayRect.left, displayRect.top + h * i / 3f,
                    displayRect.right, displayRect.top + h * i / 3f, gridPaint)
            }
        }
        // 51 个点同屏时点要画小一点，否则连成一片分不出选了哪个
        val dense = points.size > 20
        val base = w.coerceAtMost(h) * if (dense) 0.022f else 0.035f
        val r = base.coerceAtLeast(resources.displayMetrics.density * 3f)
        // 叠在实时画面上（liveView）时点要更淡更小 —— 它是"可选位置"的提示，
        // 不该盖住构图；对焦屏（grid=true）里没有画面，照旧画满。
        val onLive = !grid
        for (p in points) {
            // 放大时点阵要跟着画面一起放大：把整幅坐标换算成"当前屏幕上"的位置，
            // 被推出可视区的点直接不画（画在框外会让人以为机身还能选到那儿）
            val d = fullToDisplay(p.nx, p.ny) ?: continue
            val cx = displayRect.left + d.first * w
            val cy = displayRect.top + d.second * h
            val active = p.id == selectedId
            val candidate = dragging && p.id == dragCandidate?.id
            when {
                active -> {
                    // 当前点：实心橙点 + 白环，51 个点里一眼能找到
                    canvas.drawCircle(cx, cy, r * 1.3f, pointActivePaint)
                    canvas.drawCircle(cx, cy, r * 1.85f, pointRingPaint)
                }
                candidate -> canvas.drawCircle(cx, cy, r * 1.6f, pointCandidatePaint)
                onLive -> canvas.drawCircle(cx, cy, (r * 0.5f).coerceAtLeast(1.6f), pointFaintPaint)
                else -> canvas.drawCircle(cx, cy, r, pointPaint)
            }
        }
    }

    private fun drawBox(canvas: Canvas) {
        val w = displayRect.width()
        val h = displayRect.height()
        // 放大后 AF 框在画面里也跟着变大（框覆盖的是画面上固定的一块）
        val zs = if (zoomScale > 1f) zoomScale else 1f
        val bw = (boxW * zs * w).coerceAtMost(w).coerceAtLeast(resources.displayMetrics.density * 16f)
        val bh = (boxH * zs * h).coerceAtMost(h).coerceAtLeast(resources.displayMetrics.density * 16f)
        // ★ 放大时画面是绕着对焦点裁出来的，AF 框必然落在画面的正中间；
        //   帧头报回来的坐标在放大状态下参照系会变，直接用会画歪，所以这里按中心画。
        val (nx, ny) = if (zoomScale > 1f) Pair(0.5f, 0.5f) else Pair(boxCx, boxCy)
        val cx = displayRect.left + nx * w
        val cy = displayRect.top + ny * h
        afRect.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        if (afRect.isEmpty) return

        val paint = if (boxPending) {
            // 未确认：淡一点，机身回帧后会自动变成实色
            Paint(boxPaint).apply { alpha = 140 }
        } else boxPaint

        val len = afRect.width().coerceAtMost(afRect.height()) * 0.35f
        drawCorner(canvas, afRect.left, afRect.top, len, 1, 1, paint)
        drawCorner(canvas, afRect.right, afRect.top, len, -1, 1, paint)
        drawCorner(canvas, afRect.left, afRect.bottom, len, 1, -1, paint)
        drawCorner(canvas, afRect.right, afRect.bottom, len, -1, -1, paint)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, len: Float, sx: Int, sy: Int, paint: Paint) {
        canvas.drawLine(x, y, x + sx * len, y, paint)
        canvas.drawLine(x, y, x, y + sy * len, paint)
    }
}
