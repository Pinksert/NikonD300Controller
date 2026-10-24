package com.example.nikond300controller

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把一组**已拍好的照片**按序合成一段 MP4（延时 / 定格动画）。
 *
 * 为什么要单开一个类而不是复用 [LiveViewRecorder]：
 *   录制是"实时"的 —— 帧什么时候到由 USB 决定，时间戳只能跟着开机时钟走
 *   （LiveViewRecorder.drain 里做归一化只是把起点归零）；
 *   而这里是"离线后处理" —— 帧率由用户指定，时间戳必须**自己算**，
 *   否则合成出来的时长取决于解码有多快，换个手机就是另一个速度。
 *   两条路的取舍完全不同（那边宁可丢帧也不拖慢取景，这边一帧都不能丢），
 *   硬塞进同一个类只会互相迁就。
 *
 * 画质上这也是"D300 不能录像"的更好补法：用的是**完整照片**（4288×2848 解码后降采样），
 * 而不是取景帧那点 640×480，还不受取景帧率的限制。
 *
 * 编码管线与录制那套一致：Surface 输入（编码器自己做颜色转换，省掉手工转 YUV 的坑）
 * + MediaMuxer 输出。差别只在时间戳。
 */
class TimelapseComposer(private val context: Context) {

    /** 最近一次失败原因 */
    @Volatile
    var lastError: String? = null
        private set

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null

    private var trackIndex = -1
    private var muxerStarted = false
    @Volatile
    private var sawEos = false
    @Volatile
    private var eosSignaled = false

    private var outUri: Uri? = null
    private var outFile: File? = null
    private var pfd: ParcelFileDescriptor? = null

    private var width = 0
    private var height = 0
    private var fps = 24
    private var frameIndex = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = Rect()

    /** 已提交帧数（不等于已写盘，编码器有延迟） */
    @Volatile
    var submitted = 0
        private set

    private val fileName: String by lazy {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        "D300_TL_$ts.mp4"
    }

    /**
     * 起编码器。
     *
     * @param w/h 输出分辨率（内部会按偶数对齐 —— H.264 要求）
     * @param fps 帧率，决定每帧的时间戳间隔
     * @param bitrateMbps 码率（Mbps）；给 0 就按分辨率和帧率估一个
     */
    fun start(w: Int, h: Int, fps: Int, bitrateMbps: Int): Boolean {
        lastError = null
        frameIndex = 0
        submitted = 0
        width = w - (w % 2)
        height = h - (h % 2)
        this.fps = fps.coerceIn(1, 60)
        if (width <= 0 || height <= 0) { lastError = "输出尺寸不合法"; return false }

        if (!prepareOutput()) { lastError = "无法创建输出文件"; return false }

        val bitrate = if (bitrateMbps > 0) bitrateMbps * 1_000_000
        else (width * height * this.fps * 0.35).toInt().coerceIn(2_000_000, 24_000_000)

        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, this@TimelapseComposer.fps)
                // 关键帧密一点：延时片子里前后两帧差异很大，I 帧间隔太长的话
                // 拖动进度条会明显卡顿（这也是和实时录制不同的取舍）
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val s = codec.createInputSurface()
            codec.start()

            val mux = createMuxer()
            if (mux == null) {
                codec.stop(); codec.release(); s.release()
                lastError = "无法创建封装器"
                return false
            }

            encoder = codec
            surface = s
            muxer = mux
            dstRect.set(0, 0, width, height)

            drainThread = Thread({ drain() }, "tl-composer-drain").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            lastError = "编码器启动失败：${e.message}"
            false
        }
    }

    /**
     * 提交一帧。
     *
     * 与实时录制不同，这里**同步**画：一帧都不能丢。
     * 比例不一致时按"完整放进画面 + 补黑边"处理（同一台机器拍的照片比例本来就是一致的，
     * 混进竖构图时才会有黑边，总比把画面裁掉一截好）。
     */
    fun submitFrame(bitmap: Bitmap): Boolean {
        val s = surface ?: return false
        return try {
            val canvas = s.lockCanvas(null) ?: return false
            try {
                canvas.drawColor(android.graphics.Color.BLACK)
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                val scale = minOf(width / bw, height / bh)
                val dw = (bw * scale).toInt()
                val dh = (bh * scale).toInt()
                val left = (width - dw) / 2
                val top = (height - dh) / 2
                canvas.drawBitmap(bitmap, null, Rect(left, top, left + dw, top + dh), paint)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
            submitted++
            true
        } catch (e: Exception) {
            lastError = "写帧失败：${e.message}"
            false
        }
    }

    /** 收尾并落盘。@return 输出位置描述（供界面提示），失败返回 null */
    fun stop(): String? {
        eosSignaled = true
        try { encoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { drainThread?.join(6000) } catch (_: Exception) {}
        drainThread = null
        if (muxer != null) finishMuxer()

        if (submitted == 0) {
            lastError = lastError ?: "一帧都没写进去"
            outFile?.delete()
            return null
        }
        return if (outUri != null) "相册 Movies/D300" else outFile?.absolutePath
    }

    /** 中途放弃：收掉编码器并删掉半成品文件 */
    fun abort() {
        eosSignaled = true
        try { encoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { drainThread?.join(2000) } catch (_: Exception) {}
        drainThread = null
        if (muxer != null) finishMuxer()
        try { outUri?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
        outFile?.delete()
    }

    fun outputUri(): Uri? = outUri

    // ---------------- 内部 ----------------

    /**
     * 搬编码输出。
     *
     * ★ 关键差别：Surface 输入的 pts 是**系统时钟**，而系统时钟取决于解码快慢，
     * 直接拿去封装的话"24fps"会变成"你的手机解一张 NEF 要多久"。
     * 所以这里**完全丢弃**编码器给的 pts，按帧序号重算：第 n 帧 = n × 1e6 / fps。
     */
    private fun drain() {
        val info = MediaCodec.BufferInfo()
        val codec = encoder ?: return
        var outIndex = 0
        val stepUs = 1_000_000L / fps
        var idleSince = -1L
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                lastError = "取编码输出失败：${e.message}"
                break
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 发过 EOS 之后还连续 3 秒拿不到东西 = 队列已经空了，收工
                    if (!eosSignaled) continue
                    val now = System.currentTimeMillis()
                    if (idleSince < 0) idleSince = now
                    else if (now - idleSince > 3000) break
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
                        info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        info.presentationTimeUs = (outIndex++ * stepUs)
                        try {
                            muxer!!.writeSampleData(trackIndex, buf, info)
                        } catch (e: Exception) {
                            lastError = "写盘失败：${e.message}"
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { sawEos = true; break }
                }
            }
        }
    }

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
        try { surface?.release() } catch (_: Exception) {}
        surface = null

        if (outUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val v = ContentValues()
                v.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(outUri!!, v, null, null)
            } catch (_: Exception) {}
        }
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
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
                outUri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                true
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return false
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
                val fd = context.contentResolver.openFileDescriptor(outUri!!, "w") ?: return null
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
}
