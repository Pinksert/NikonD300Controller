package com.example.nikond300controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 峰值对焦（Focus Peaking）叠加层。
 *
 * 手动对焦 / 三脚架取景时，光看 640×480 的取景画面判断"有没有合上焦"非常不靠谱 ——
 * 屏幕小、画面软，肉眼分不出"差一点"和"正合焦"。峰值对焦把画面里梯度大的地方
 * （也就是边缘）整片染成高亮颜色：焦平面扫过哪里，哪里就"发亮"，转对焦环时
 * 看着亮斑从无到有、从少到多，合焦位置一目了然。这是微距/手动头的标配辅助。
 *
 * 实现上的几个取舍：
 * - **为什么是独立 View 而不是画进 FocusBoxView**：FocusBoxView 管的是对焦框/点阵，
 *   语义不同，混在一起两边都难改。而且这个叠加层要在取景帧线程上算，独立才好控制。
 * - **为什么隔一个像素取一个（STEP=2）**：取景帧只有 640×480，全分辨率跑 Sobel
 *   是白白多算四倍。半分辨率下边缘位置最多偏一个像素，肉眼完全无感。
 * - **为什么用 |gx|+|gy| 而不是开方**：Sobel 幅度本来是 √(gx²+gy²)，开方在
 *   每帧几万个点上开销可观，而 |gx|+|gy| 的排序和它基本一致，判断阈值够用了。
 * - **双缓冲**：算是在取景线程上做、画是在 UI 线程上做，同一块 Bitmap 两边同时用
 *   会画出半成品，所以两块轮着来。
 */
class PeakingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 峰值开关。关掉时连算都不算，白送的帧率 */
    var peakingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) clear()
        }

    /**
     * 边缘强度阈值，低于它的梯度不算边缘。
     * 调大 → 只留最锐的轮廓（画面干净但可能漏掉弱边缘）；调小 → 满屏都是噪点。
     * 110 是在 640×480 取景帧上试出来比较均衡的一档。
     */
    var threshold: Int = 110

    /** 峰值颜色。默认红色，和取景画面里其他元素（白框、黄点）区分度最高 */
    var peakingColor: Int = Color.RED

    @Volatile
    private var drawMask: Bitmap? = null

    /** 双缓冲：下标 [activeIdx] 正在给 UI 线程画，另一个留给计算线程写 */
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var activeIdx = 0

    private var maskW = 0
    private var maskH = 0

    /** 复用的中间数组，避免每帧 new 出几 MB 的垃圾 */
    private var grayBuf: IntArray? = null
    private var rowBuf: IntArray? = null
    private var pxBuf: IntArray? = null

    private val paint = Paint()
    private val dstRect = RectF()

    /**
     * 拿一帧算峰值掩码。必须在**后台线程**同步调用（取景帧线程），
     * 因为这里要逐像素读这块 Bitmap —— 它随后就可能被下一帧覆盖。
     */
    fun submit(src: Bitmap) {
        if (!peakingEnabled) return
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return

        val mw = w / STEP
        val mh = h / STEP
        if (mw < 3 || mh < 3) return

        if (maskW != mw || maskH != mh || buffers[0] == null || buffers[1] == null) {
            maskW = mw
            maskH = mh
            buffers[0] = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            buffers[1] = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            pxBuf = IntArray(mw * mh)
            activeIdx = 0
        }

        var gray = grayBuf
        if (gray == null || gray.size < mw * mh) {
            gray = IntArray(mw * mh)
            grayBuf = gray
        }
        var row = rowBuf
        if (row == null || row.size < w) {
            row = IntArray(w)
            rowBuf = row
        }
        val px = pxBuf ?: return

        // ---- 1) 下采样成灰度。系数是 0.299/0.587/0.114 的定点近似（>>8） ----
        var gi = 0
        for (my in 0 until mh) {
            val sy = (my * STEP).coerceAtMost(h - 1)
            src.getPixels(row, 0, w, 0, sy, w, 1)
            for (mx in 0 until mw) {
                val c = row[mx * STEP]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[gi++] = (r * 77 + g * 151 + b * 28) shr 8
            }
        }

        // ---- 2) Sobel，超阈值的点染成峰值色，其余透明 ----
        val thr = threshold
        val col = peakingColor
        var p = 0
        for (y in 0 until mh) {
            val y0 = if (y > 0) y - 1 else 0
            val y1 = if (y < mh - 1) y + 1 else mh - 1
            val r0 = y0 * mw
            val rm = y * mw
            val r1 = y1 * mw
            for (x in 0 until mw) {
                val x0 = if (x > 0) x - 1 else 0
                val x1 = if (x < mw - 1) x + 1 else mw - 1
                val gx = (gray[r0 + x1] + 2 * gray[rm + x1] + gray[r1 + x1]) -
                    (gray[r0 + x0] + 2 * gray[rm + x0] + gray[r1 + x0])
                val gy = (gray[r1 + x0] + 2 * gray[r1 + x] + gray[r1 + x1]) -
                    (gray[r0 + x0] + 2 * gray[r0 + x] + gray[r0 + x1])
                val mag = (if (gx < 0) -gx else gx) + (if (gy < 0) -gy else gy)
                px[p++] = if (mag >= thr) col else Color.TRANSPARENT
            }
        }

        val writeIdx = 1 - activeIdx
        val buf = buffers[writeIdx] ?: return
        buf.setPixels(px, 0, mw, 0, 0, mw, mh)
        drawMask = buf          // volatile 发布，UI 线程下一次 onDraw 才看得到
        activeIdx = writeIdx
        postInvalidate()
    }

    fun clear() {
        drawMask = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = drawMask ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        // 必须和 liveViewImage 的 fitCenter 保持同一个矩形，否则峰值会错位
        val s = minOf(vw / m.width, vh / m.height)
        val dw = m.width * s
        val dh = m.height * s
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        dstRect.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(m, null, dstRect, paint)
    }

    private companion object {
        const val STEP = 2
    }
}
