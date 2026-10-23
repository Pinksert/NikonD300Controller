package com.example.nikond300controller

import android.graphics.Bitmap
import android.graphics.Color
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * NEF → JPG **真转档**（解 RAW，不是抠内嵌预览）。
 *
 * 以前的做法是把机身塞在 NEF 里的那张预览 JPEG 抠出来当成品：那张图只有 239 KB
 * （4288×2848 压到极限），画质差、体积也不像一张正常照片，而且一个 EXIF 字节都没有。
 * 现在走 LibRaw（dcraw 的库化版本）真的把 Bayer 数据解出来：
 * 霍夫曼解码 → 预测还原 → 线性化 → 机身白平衡 → AHD 彩色插值 → sRGB，
 * 出来的 JPG 是几 MB 的正常照片，EXIF 再原样从 NEF 搬回来（见 [NefPreview.attachExif]）。
 *
 * 链路：NEF 字节 → 临时文件 → native 解成 PPM → Bitmap → Bitmap.compress(JPEG)
 * 之所以绕一道 PPM：JPEG 编码直接用系统的 Bitmap.compress 就够了，
 * 没必要再拖一个 libjpeg 进 NDK 编；PPM 格式也简单，Kotlin 端几十行就能读。
 */
object NefDecoder {

    init {
        System.loadLibrary("nefdecoder")
    }

    /** @return 0 成功，其余为 LibRaw 错误码（用 [errorText] 翻成人话） */
    external fun convertToPpm(inPath: String, outPath: String): Int

    /** 把 [convertToPpm] 的错误码翻成一句中文/英文说明（LibRaw 自带文案） */
    external fun errorText(code: Int): String

    /** 输出 JPEG 质量。92 是"肉眼无损但体积还能接受"的常用档 */
    private const val JPEG_QUALITY = 92

    /**
     * @param nef NEF 原文件字节
     * @param cacheDir 临时目录中转（native 只吃文件路径）
     * @return 转好的 JPEG 字节（带 EXIF）；失败返回 null，调用方退回存原文件
     */
    fun toJpeg(nef: ByteArray, cacheDir: File): ByteArray? {
        // ★ 文件名必须按线程区分：批量导出是多个解码线程并发调这里，
        //   写死名字的话两个线程会抢同一个 nef_in.tmp —— 一个写一半被另一个覆盖，
        //   解出来就是花屏或者"文件不存在"。
        val tag = android.os.Process.myTid()
        val inFile = File(cacheDir, "nef_in_$tag.tmp")
        val outFile = File(cacheDir, "nef_out_$tag.ppm")
        return try {
            inFile.writeBytes(nef)
            val rc = convertToPpm(inFile.absolutePath, outFile.absolutePath)
            if (rc != 0) {
                android.util.Log.w("NefDecoder", "LibRaw 解码失败（$rc）：${errorText(rc)}")
                return null
            }
            val bmp = readPpm(outFile) ?: return null
            try {
                val jpeg = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
                // 解码出来的画面是"图像"，EXIF 得从原 NEF 搬过去 —— 不然导出去的照片
                // 没有拍摄日期/曝光参数，在手机相册里按时间排也排不进正确位置。
                NefPreview.attachExif(jpeg.toByteArray(), nef)
            } finally {
                bmp.recycle()
            }
        } catch (e: Throwable) {
            android.util.Log.w("NefDecoder", "NEF 转档异常：${e.message}")
            null
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }

    // ---------------- PPM（P6，8bit RGB）读取 ----------------

    /**
     * 读一份 PPM 成 Bitmap。
     * 逐行读、逐行塞进 Bitmap：整文件一次性读进内存要 37 MB，没必要。
     */
    private fun readPpm(file: File): Bitmap? {
        FileInputStream(file).use { fis ->
            val bis = BufferedInputStream(fis, 1 shl 16)
            if (token(bis) != "P6") return null
            val w = token(bis)?.toIntOrNull() ?: return null
            val h = token(bis)?.toIntOrNull() ?: return null
            val maxVal = token(bis)?.toIntOrNull() ?: return null
            if (w <= 0 || h <= 0 || maxVal != 255) return null

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val row = IntArray(w)
            val buf = ByteArray(w * 3)
            for (y in 0 until h) {
                var off = 0
                while (off < buf.size) {
                    val n = bis.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                var i = 0
                for (x in 0 until w) {
                    val r = buf[i++].toInt() and 0xFF
                    val g = buf[i++].toInt() and 0xFF
                    val b = buf[i++].toInt() and 0xFF
                    row[x] = Color.argb(255, r, g, b)
                }
                bmp.setPixels(row, 0, w, 0, y, w, 1)
            }
            return bmp
        }
    }

    /** PPM 头里的一个记号（空白分隔，跳过 # 开头的注释行） */
    private fun token(bis: BufferedInputStream): String? {
        var c = bis.read()
        while (c >= 0 && c.toChar().isWhitespace()) c = bis.read()
        if (c < 0) return null
        if (c.toChar() == '#') {                 // 注释：一直读到行尾再取下一个记号
            while (c >= 0 && c.toChar() != '\n') c = bis.read()
            return token(bis)
        }
        val sb = StringBuilder()
        while (c >= 0 && !c.toChar().isWhitespace()) {
            sb.append(c.toChar())
            c = bis.read()
        }
        return sb.toString()
    }
}
