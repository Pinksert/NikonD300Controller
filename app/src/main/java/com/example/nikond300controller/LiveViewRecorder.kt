package com.example.nikond300controller

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 LiveView 帧录成 MP4 —— D300 机身本身没有录像功能，
 * 唯一能拿到动态画面的途径就是 0x9203 取景帧，所以录像只能在手机侧做。
 *
 * 结构上是「编码器 Surface 输入 + MediaMuxer 输出」：
 * - 每帧 [submitFrame] 把 Bitmap 画到编码器的输入 Surface 上（lockCanvas/unlockCanvasAndPost）；
 * - 编码/封装跑在独立的 drain 线程里，不占取景循环的时间。
 *
 * 两个必须这么写的原因：
 * 1. **不用 ByteBuffer 输入**：那要自己把 Bitmap 转 NV12/I420，而各家编码器对
 *    YUV 的 stride/对齐要求不一致（有的要 I420 有的要 NV12），踩不完的坑。
 *    Surface 输入由编码器自己完成颜色转换，尺寸对得上就能出画面。
 * 2. **时间戳要在 drain 里归一化**：Surface 输入没法指定 pts（unlockCanvasAndPost
 *    用的是系统时间），所以拿到的 presentationTimeUs 是从开机算起的绝对时间。
 *    直接用会让 MediaMuxer 写出一个时长错乱的文件，必须减掉第一帧的时间。
 */
class LiveViewRecorder(private val context: Context) {

    @Volatile
    var isRecording = false
        private set

    /** 已提交给编码器的帧数（不等于已写盘，编码器有延迟） */
    @Volatile
    var frameCount = 0
        private set

    /**
     * 因为编码器还没吃掉上一帧而被丢掉的帧数。
     *
     * 取景循环是整条链路的节拍器：它多等 1ms，取景和录像就一起慢 1ms。
     * 所以宁可丢一帧（6~12fps 下根本看不出来），也不能让编码把取景拖住。
     */
    @Volatile
    var droppedFrames = 0
        private set

    /** 提交串行化（非阻塞）：同一时刻只允许一帧在写编码器 Surface */
    private val surfaceLock = java.util.concurrent.locks.ReentrantLock()
    private val drawPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    /** 编码画布的目标区域：宽高按偶数对齐后可能要缩一点点 */
    private val dstRect = android.graphics.Rect()

    /** 启动时刻，用来算界面上的计时 */
    @Volatile
    private var startWallMs = 0L

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null

    private var trackIndex = -1
    private var muxerStarted = false
    private var basePtsUs = -1L

    /** 输出目标二选一：Q+ 走 MediaStore 公开目录，低版本退回 App 专属目录 */
    private var outUri: Uri? = null
    private var outFile: File? = null
    private var pfd: android.os.ParcelFileDescriptor? = null

    /** 最近一次失败原因，界面拿它兜底提示 */
    @Volatile
    var lastError: String? = null
        private set

    /** 录制了多少毫秒 */
    fun elapsedMs(): Long = if (startWallMs == 0L) 0L else System.currentTimeMillis() - startWallMs

    /**
     * 开始录制。
     *
     * 分辨率不在这里定 —— 取景帧的尺寸机身说了算，等到第一帧来了才知道。
     * 这里只准备输出文件和状态，真正的编码器在第一帧到达时才配置（见 [ensureStarted]）。
     */
    @Synchronized
    fun start(): Boolean {
        if (isRecording) return true
        lastError = null
        frameCount = 0
        droppedFrames = 0
        basePtsUs = -1L
        trackIndex = -1
        muxerStarted = false
        startWallMs = System.currentTimeMillis()

        if (!prepareOutput()) {
            lastError = "无法创建输出文件"
            startWallMs = 0L
            return false
        }
        isRecording = true
        return true
    }

    /** 提交一帧。必须在下一帧覆盖这块 Bitmap 之前调用 —— 取景层是复用同一个 Bitmap 的。 */
    fun submitFrame(bitmap: Bitmap) {
        if (!isRecording) return
        if (inputSurface == null) {
            // 第一帧：尺寸确定，这时才真正起编码器
            if (!ensureStarted(bitmap.width, bitmap.height)) {
                // 起不来就立刻停掉，别让用户在"录制中"里空等
                lastError = lastError ?: "编码器启动失败"
                return
            }
        }
        val s = inputSurface ?: return

        // ★ 非阻塞提交：编码器还没消费上一帧就直接丢掉这一帧。
        // 以前这里是同步 lockCanvas，编码一慢就把取景循环一起拖慢 ——
        // 录像时取景明显掉帧、点按响应变钝，都是这一步造成的。
        if (!surfaceLock.tryLock()) {
            droppedFrames++
            return
        }
        try {
            val canvas = s.lockCanvas(null) ?: return
            try {
                // 用目标 Rect 绘制：编码尺寸是偶数对齐过的，1~2px 的差异在这里吸收掉，
                // 顺带给 Paint 开 FILTER_BITMAP，缩放时不会出现锯齿边
                if (dstRect.isEmpty) canvas.drawBitmap(bitmap, 0f, 0f, drawPaint)
                else canvas.drawBitmap(bitmap, null, dstRect, drawPaint)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
            frameCount++
        } catch (e: Exception) {
            lastError = "写帧失败：${e.message}"
        } finally {
            surfaceLock.unlock()
        }
    }

    /** 编码器按第一帧的尺寸初始化，并起 drain 线程 */
    private fun ensureStarted(width: Int, height: Int): Boolean {
        return try {
            val w = width - (width % 2)      // H.264 要求偶数
            val h = height - (height % 2)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                // 取景实际帧率由 USB 决定（优化节拍后约 10~12fps），这里只是声明值，
                // 真正的时间关系由帧时间戳决定，所以按 12 声明、实测不会失真。
                val fps = 12
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.5).toInt().coerceIn(1_000_000, 8_000_000))
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            val mux = createMuxer() ?: run {
                codec.stop(); codec.release(); surface.release()
                lastError = "无法创建封装器"
                return false
            }

            encoder = codec
            inputSurface = surface
            dstRect.set(0, 0, w, h)
            muxer = mux

            val t = Thread({ drain() }, "lv-recorder-drain").apply { isDaemon = true }
            drainThread = t
            t.start()
            true
        } catch (e: Exception) {
            lastError = "编码器启动失败：${e.message}"
            false
        }
    }

    /** 把编码输出搬进 MediaMuxer，顺手做时间戳归一化 */
    private fun drain() {
        val info = MediaCodec.BufferInfo()
        val codec = encoder ?: return
        while (true) {
            if (!isRecording && drainEosSignaled && sawEos) break
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                lastError = "取编码输出失败：${e.message}"
                break
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 停录时如果迟迟等不到输出就退出，别把线程挂死
                    if (drainEosSignaled && System.currentTimeMillis() - eosAtMs > 3000) break
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) { lastError = "编码器输出格式二次变化"; break }
                    try {
                        trackIndex = muxer!!.addTrack(codec.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    } catch (e: Exception) {
                        lastError = "封装器启动失败：${e.message}"
                        break
                    }
                }
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && muxerStarted && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        // Surface 输入拿不到自定义 pts，这里把绝对时间归零
                        if (basePtsUs < 0) basePtsUs = info.presentationTimeUs
                        info.presentationTimeUs -= basePtsUs
                        try {
                            muxer!!.writeSampleData(trackIndex, buf, info)
                        } catch (e: Exception) {
                            lastError = "写盘失败：${e.message}"
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawEos = true
                        break
                    }
                }
            }
        }
        finishMuxer()
    }

    @Volatile
    private var drainEosSignaled = false

    @Volatile
    private var sawEos = false

    @Volatile
    private var eosAtMs = 0L

    /** 收尾：关编码器 → 关封装器 → 释放输出。必须在 drain 跑完之后。 */
    private fun finishMuxer() {
        try {
            if (muxerStarted) muxer?.stop() else muxer?.release()
            muxerStarted = false
        } catch (e: Exception) {
            lastError = "封装收尾失败：${e.message}"
        }
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null

        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null

        // MediaStore 那路要把 IS_PENDING 清掉，文件才会出现在相册里
        val uri = outUri
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val v = ContentValues()
                v.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, v, null, null)
            } catch (_: Exception) {}
        }
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }

    /**
     * 结束录制。
     *
     * @return 产出文件的位置描述（供界面提示），失败返回 null
     */
    @Synchronized
    fun stop(): String? {
        if (!isRecording) return null
        isRecording = false
        val wroteFrames = frameCount

        drainEosSignaled = true
        eosAtMs = System.currentTimeMillis()
        try {
            encoder?.signalEndOfInputStream()
        } catch (_: Exception) {
            // 编码器还没起来（一帧都没到）就没有 EOS 可发
        }
        // 等 drain 收尾，最多 4 秒 —— 卡住也不能拖死界面
        try { drainThread?.join(4000) } catch (_: Exception) {}
        drainThread = null

        // 极端情况：drain 没跑完（一帧都没编码出来），这里兜底收一次
        if (muxer != null) finishMuxer()

        startWallMs = 0L
        if (wroteFrames == 0) {
            lastError = lastError ?: "没有录到任何画面"
            outFile?.delete()
            return null
        }
        return if (outUri != null) {
            "相册 Movies/D300"
        } else {
            outFile?.absolutePath
        }
    }

    // ---- 输出文件 ----

    private val fileName: String by lazy {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        "D300_LV_$ts.mp4"
    }

    private fun prepareOutput(): Boolean {
        return try {
            val name = fileName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/D300")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                outUri = uri
                true
            } else {
                // 低版本写 App 专属目录：不需要存储权限，代价是文件不在公共相册
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    ?: return false
                if (!dir.exists()) dir.mkdirs()
                outFile = File(dir, name)
                true
            }
        } catch (e: Exception) {
            lastError = "创建输出文件失败：${e.message}"
            false
        }
    }

    private fun createMuxer(): MediaMuxer? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && outUri != null) {
                // MediaMuxer(FileDescriptor) 是 API 26 才有的；低版本只能走 String path
                val fd = context.contentResolver.openFileDescriptor(outUri!!, "w")
                    ?: return null
                pfd = fd
                MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                val f = outFile ?: return null
                MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
        } catch (e: Exception) {
            lastError = "创建 MediaMuxer 失败：${e.message}"
            null
        }
    }

    /** 录制产出供外部（如通知相册）使用的 Uri；App 目录那路为 null */
    fun outputUri(): Uri? = outUri
}
