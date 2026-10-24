package com.example.nikond300controller

/**
 * 读照片的拍摄时间与曝光参数（EXIF）。
 *
 * 为什么要自己解析而不是用 androidx.exifinterface：
 * - D300 的 **NEF 是大端 TIFF（MM）**，ExifInterface 只认 JPEG/部分 RAW 变体，读不了；
 * - 而且我们只需要 IFD0 + ExifIFD 里的十来个标准标签，自己走一遍 IFD 反而更可控。
 *
 * 复用 [ShutterCountReader] 那套"先找 TIFF 头、再按字节序走 IFD"的思路：
 * JPEG 要先找 APP1 里的 "Exif\0\0"，NEF 本身就是 TIFF，直接从 0 开始。
 *
 * 读不到就留空，界面不显示那一项 —— 不猜、不填假值。
 */
object PhotoExif {

    /** 一行摘要需要的字段，全部都可能是 null */
    data class Summary(
        val dateTime: String? = null,
        val exposureTime: String? = null,
        val fNumber: String? = null,
        val iso: String? = null,
        val focalLength: String? = null,
        val exposureBias: String? = null,
        val whiteBalance: String? = null,
        val meteringMode: String? = null,
        /** 曝光档位（曝光模式）：拍摄那一刻模式转盘在哪一档，中文短名 */
        val exposureProgram: String? = null,
        /** 曝光档位的机内字母代号（P / S / A / M），非 D300 转盘档位时为空 */
        val exposureProgramCode: String? = null
    ) {
        /** 是否什么都没读到 */
        val isEmpty: Boolean
            get() = dateTime == null && exposureTime == null && fNumber == null &&
                iso == null && focalLength == null && exposureBias == null &&
                whiteBalance == null && meteringMode == null && exposureProgram == null

        /**
         * 给查看器按行分组（仿机内「总体数据」页的图标风格）：
         * 第一行日期 / 第二行 ISO·快门·光圈·焦距 / 第三行 曝光档位·补偿·白平衡·测光。
         * 缺哪项就跳过哪项，不会出现 "null" 或 "—"；整行都缺则该行不渲染。
         */
        fun rows(): List<List<Chip>> {
            val date = ArrayList<Chip>(1)
            val exposure = ArrayList<Chip>(4)
            // 曝光档位放第一位：它是"这张片是怎么曝的"的总纲，后面的补偿/测光挂在它下面
            val extra = ArrayList<Chip>(4)
            dateTime?.let { date.add(Chip(it, iconRes = R.drawable.ic_date)) }
            iso?.let { exposure.add(Chip(it, symbol = "ISO")) }
            exposureTime?.let { exposure.add(Chip(it, symbol = "⏱")) }
            // 光圈用机内写法 F + 数值（F5.6）；补偿值自身已带 +/- 号，前面不再加 "±"
            fNumber?.let { exposure.add(Chip(it, symbol = "F")) }
            focalLength?.let { exposure.add(Chip(it)) }
            exposureProgram?.let {
                // 以前这里挂的是 ◑ —— 脱离机身界面没人猜得出那是曝光模式。
                extra.add(Chip(it, symbol = exposureProgramCode.orEmpty(), symbolBold = true))
            }
            exposureBias?.let { extra.add(Chip(it, signOrange = true)) }
            whiteBalance?.let { extra.add(Chip(it, symbol = "WB")) }
            meteringMode?.let { extra.add(Chip(it, symbol = "⊙")) }
            return listOf(date, exposure, extra)
        }
    }

    /** 查看器里的一小块：矢量图标 / 文本符号 + 数值 */
    data class Chip(
        val value: String,
        /** 文本符号（ISO / F / WB …），空串表示不带 */
        val symbol: String = "",
        /** 矢量图标资源，0 表示不带 */
        val iconRes: Int = 0,
        /** 数值首位的 +/- 是否染橙色（曝光补偿） */
        val signOrange: Boolean = false,
        /** 文本符号要不要加粗（曝光档位的 P / S / A / M） */
        val symbolBold: Boolean = false
    )

    fun parse(data: ByteArray): Summary = runCatching { parseInner(data) }.getOrDefault(Summary())

    private fun parseInner(data: ByteArray): Summary {
        val tiff = findTiffBase(data) ?: return Summary()
        val little = byteOrderLittle(data, tiff) ?: return Summary()
        val ifd0 = u32(data, tiff + 4, little)?.toInt() ?: return Summary()

        val exifIfd = entryNumber(data, tiff, ifd0, TAG_EXIF_IFD, little)?.toInt()

        val dateTimeOriginal = exifIfd?.let {
            entryAscii(data, tiff, it, TAG_DATETIME_ORIGINAL, little)
        } ?: entryAscii(data, tiff, ifd0, TAG_DATETIME, little)

        val exposureTime = exifIfd?.let { rational(data, tiff, it, TAG_EXPOSURE_TIME, little) }
        val fNumber = exifIfd?.let { rational(data, tiff, it, TAG_F_NUMBER, little) }
        val iso = exifIfd?.let { entryNumber(data, tiff, it, TAG_ISO, little) }
        val focal = exifIfd?.let { rational(data, tiff, it, TAG_FOCAL_LENGTH, little) }
        val bias = exifIfd?.let { signedRational(data, tiff, it, TAG_EXPOSURE_BIAS, little) }
        val wb = exifIfd?.let { entryNumber(data, tiff, it, TAG_WHITE_BALANCE, little) }
        val metering = exifIfd?.let { entryNumber(data, tiff, it, TAG_METERING_MODE, little) }
        val program = exifIfd?.let { entryNumber(data, tiff, it, TAG_EXPOSURE_PROGRAM, little) }

        return Summary(
            dateTime = formatDateTime(dateTimeOriginal),
            exposureTime = exposureTime?.let { formatShutter(it) },
            fNumber = fNumber?.let { formatAperture(it) },
            iso = iso?.takeIf { it > 0 }?.toString(),
            focalLength = focal?.let { "${it.toInt()} mm" },
            exposureBias = bias?.let { formatBias(it) },
            whiteBalance = when (wb?.toInt()) {
                0 -> "AUTO"
                1 -> "MANU"
                else -> null
            },
            // 写全称：只给"点测""中央重点"两个词，脱离机身界面就看不出那是测光方式
            meteringMode = when (metering?.toInt()) {
                1 -> "平均测光"
                2 -> "中央重点测光"
                3 -> "点测光"
                4 -> "多点测光"
                5 -> "矩阵测光"
                6 -> "局部测光"
                else -> null
            },
            exposureProgram = EXPOSURE_PROGRAMS[program?.toInt()]?.second,
            exposureProgramCode = EXPOSURE_PROGRAMS[program?.toInt()]?.first?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * 曝光档位（EXIF ExposureProgram 0x8822）：机内字母代号 + 中文短名。
     *
     * D300 转盘上只有 P / S / A / M 四档，机内「总体数据」页就是拿这几个字母标的，
     * 查看详情时也照这个口径 —— 字母比 4 个字的中文名更好扫，旁边留短名兜住可读性。
     * 5–8 那几档是其它厂的场景程序，这台机器写不出来，只留中文名、不带字母。
     */
    private val EXPOSURE_PROGRAMS = mapOf(
        1 to ("M" to "手动"),
        2 to ("P" to "程序自动"),
        3 to ("A" to "光圈优先"),
        4 to ("S" to "快门优先"),
        5 to ("" to "创意程序"),
        6 to ("" to "运动模式"),
        7 to ("" to "人像模式"),
        8 to ("" to "风景模式")
    )

    // ---------------- 格式化 ----------------

    /** EXIF 的日期格式是 "2026:10:21 10:17:18"，转成 "2026-10-21 10:17:18" */
    private fun formatDateTime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().trimEnd('\u0000')
        if (t.length < 19) return t
        return t.substring(0, 10).replace(':', '-') + t.substring(10)
    }

    /**
     * 曝光时间统一带单位 s：不足 1 秒 → "1/125s"，1 秒以上 → "2s" / "2.5s"。
     * （以前按机身 LCD 写法出 "2\"5"，混在带 s 的快门值里读不出单位，统一成秒。）
     */
    private fun formatShutter(v: Double): String? {
        if (v <= 0) return null
        return if (v >= 1.0) {
            val whole = v.toLong()
            val frac = v - whole
            if (frac < 0.05) "${whole}s" else "${String.format("%.1f", v)}s"
        } else {
            "1/${Math.round(1.0 / v)}s"
        }
    }

    /** 只出数字（"5.6"），ƒ 符号由 chip 自己加 */
    private fun formatAperture(v: Double): String? =
        if (v <= 0) null else if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

    private fun formatBias(v: Double): String {
        val sign = if (v > 0) "+" else if (v < 0) "-" else "±"
        return String.format("%s%.1f", sign, Math.abs(v))
    }

    // ---------------- TIFF / IFD 基础 ----------------

    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_DATETIME = 0x0132
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_EXPOSURE_TIME = 0x829A
    private const val TAG_F_NUMBER = 0x829D
    private const val TAG_ISO = 0x8827
    private const val TAG_FOCAL_LENGTH = 0x920A
    private const val TAG_EXPOSURE_BIAS = 0x9204
    private const val TAG_WHITE_BALANCE = 0xA403
    private const val TAG_METERING_MODE = 0x9207
    private const val TAG_EXPOSURE_PROGRAM = 0x8822

    /** NEF 就是 TIFF（从 0 开始）；JPEG 要先跳过 APP1 里的 "Exif\0\0" */
    private fun findTiffBase(data: ByteArray): Int? {
        if (data.size < 8) return null
        if (isTiffHeader(data, 0)) return 0
        val limit = minOf(data.size - 6, 128 * 1024)
        for (i in 0 until limit) {
            if (data[i] == 'E'.code.toByte() && data[i + 1] == 'x'.code.toByte() &&
                data[i + 2] == 'i'.code.toByte() && data[i + 3] == 'f'.code.toByte() &&
                data[i + 4] == 0.toByte() && data[i + 5] == 0.toByte() && isTiffHeader(data, i + 6)
            ) return i + 6
        }
        return null
    }

    private fun isTiffHeader(d: ByteArray, off: Int): Boolean {
        if (off + 8 > d.size) return false
        val a = d[off]; val b = d[off + 1]
        return when {
            a == 'I'.code.toByte() && b == 'I'.code.toByte() ->
                d[off + 2] == 42.toByte() && d[off + 3] == 0.toByte()
            a == 'M'.code.toByte() && b == 'M'.code.toByte() ->
                d[off + 2] == 0.toByte() && d[off + 3] == 42.toByte()
            else -> false
        }
    }

    private fun byteOrderLittle(d: ByteArray, off: Int): Boolean? {
        if (off + 2 > d.size) return null
        return when {
            d[off] == 'I'.code.toByte() && d[off + 1] == 'I'.code.toByte() -> true
            d[off] == 'M'.code.toByte() && d[off + 1] == 'M'.code.toByte() -> false
            else -> null
        }
    }

    /** 在 IFD 里定位标签，返回 (类型, 个数, 值字段, 值字段所在偏移) */
    private class Entry(val type: Int, val count: Long, val field: Long, val fieldPos: Int)

    private fun findEntry(d: ByteArray, base: Int, ifdOffset: Int, tag: Int, little: Boolean): Entry? {
        val ifd = base + ifdOffset
        if (ifd + 2 > d.size) return null
        val n = u16(d, ifd, little) ?: return null
        var p = ifd + 2
        var guard = 0
        while (guard < n && p + 12 <= d.size) {
            guard++
            val t = u16(d, p, little) ?: return null
            if (t == tag) {
                val type = u16(d, p + 2, little) ?: return null
                val cnt = u32(d, p + 4, little) ?: return null
                val field = u32(d, p + 8, little) ?: return null
                return Entry(type, cnt, field, p + 8)
            }
            p += 12
        }
        return null
    }

    private fun entryNumber(d: ByteArray, base: Int, ifd: Int, tag: Int, little: Boolean): Long? {
        val e = findEntry(d, base, ifd, tag, little) ?: return null
        return when (e.type) {
            3 -> u16(d, e.fieldPos, little)?.toLong()          // SHORT
            4 -> e.field                                      // LONG
            9 -> e.field                                      // SLONG
            else -> null
        }
    }

    private fun entryAscii(d: ByteArray, base: Int, ifd: Int, tag: Int, little: Boolean): String? {
        val e = findEntry(d, base, ifd, tag, little) ?: return null
        if (e.type != 2) return null
        val pos = if (e.count <= 4) e.fieldPos else base + e.field.toInt()
        if (pos < 0 || pos >= d.size) return null
        val end = minOf(d.size, pos + e.count.toInt())
        return String(d, pos, end - pos, Charsets.US_ASCII).substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    private fun rational(d: ByteArray, base: Int, ifd: Int, tag: Int, little: Boolean): Double? {
        val e = findEntry(d, base, ifd, tag, little) ?: return null
        if (e.type != 5) return null
        val pos = base + e.field.toInt()
        val num = u32(d, pos, little) ?: return null
        val den = u32(d, pos + 4, little) ?: return null
        if (den == 0L) return null
        return num.toDouble() / den.toDouble()
    }

    private fun signedRational(d: ByteArray, base: Int, ifd: Int, tag: Int, little: Boolean): Double? {
        val e = findEntry(d, base, ifd, tag, little) ?: return null
        if (e.type != 10) return null
        val pos = base + e.field.toInt()
        val num = s32(d, pos, little) ?: return null
        val den = s32(d, pos + 4, little) ?: return null
        if (den == 0) return null
        return num.toDouble() / den.toDouble()
    }

    private fun u16(d: ByteArray, off: Int, little: Boolean): Int? {
        if (off < 0 || off + 2 > d.size) return null
        val b0 = d[off].toInt() and 0xFF
        val b1 = d[off + 1].toInt() and 0xFF
        return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    private fun u32(d: ByteArray, off: Int, little: Boolean): Long? {
        if (off < 0 || off + 4 > d.size) return null
        val b = IntArray(4) { d[off + it].toInt() and 0xFF }
        val v = if (little) {
            b[0].toLong() or (b[1].toLong() shl 8) or (b[2].toLong() shl 16) or (b[3].toLong() shl 24)
        } else {
            (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        }
        return v and 0xFFFFFFFFL
    }

    private fun s32(d: ByteArray, off: Int, little: Boolean): Int? {
        val v = u32(d, off, little) ?: return null
        return v.toInt()
    }
}
