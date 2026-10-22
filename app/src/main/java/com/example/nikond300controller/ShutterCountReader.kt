package com.example.nikond300controller

/**
 * 从 NEF / JPEG 的 EXIF 里读**快门释放次数**（Nikon MakerNote 标签 0x00A7）。
 *
 * 为什么要这么绕：D300 不通过 PTP 暴露快门数 ——
 * libgphoto2 的属性表里只有 Canon / Olympus / Fuji 有快门计数，尼康段没有这一项，
 * 真机属性全表（0xD000–0xD1FF，166 项）里也没有。尼康的做法是把快门数写进每张照片的
 * MakerNote（ExifTool 的 Nikon 标签表：0x00A7 = ShutterCount，D3/D300 起的机器都有）。
 *
 * 所以这里做的是"从卡上最新一张照片里读"：
 * TIFF 头 → IFD0 → ExifIFD(0x8769) → MakerNote(0x927C) → 尼康 IFD 里的 0xA7。
 *
 * 只依赖标准 TIFF 结构 + 尼康 MakerNote 头（"Nikon\0" + 版本 + 内嵌 TIFF 头），
 * 不做任何机型分支：字段缺失就返回 null，界面显示"读不到"，绝不猜一个数。
 */
object ShutterCountReader {

    /**
     * @param data 整个文件的字节（NEF）或前若干 KB（够到 MakerNote 即可）
     * @return 快门次数；解析不出来返回 null
     */
    fun read(data: ByteArray): Int? = runCatching { parse(data) }.getOrNull()

    private fun parse(data: ByteArray): Int? {
        val tiff = findTiffBase(data) ?: return null
        val little = byteOrderLittle(data, tiff) ?: return null

        // IFD0 → ExifIFD
        val ifd0 = u32(data, tiff + 4, little)?.toInt() ?: return null
        val exifIfd = entryValue(data, tiff, ifd0, TAG_EXIF_IFD, little)?.toInt() ?: return null

        // ExifIFD → MakerNote
        val makerTag = entryValue(data, tiff, exifIfd, TAG_MAKER_NOTE, little)?.toInt() ?: return null
        val maker = tiff + makerTag
        if (maker + 12 > data.size) return null

        // 尼康 MakerNote：前 6 字节是 "Nikon\0"，紧跟 2 字节版本号，
        // 再往后偏移 10 处是它自己的 TIFF 头（II*\0 + IFD 偏移，偏移量相对 MakerNote 起点）
        val isNikon = data[maker] == 'N'.code.toByte() && data[maker + 1] == 'i'.code.toByte() &&
            data[maker + 2] == 'k'.code.toByte() && data[maker + 3] == 'o'.code.toByte() &&
            data[maker + 4] == 'n'.code.toByte()
        val mnBase = if (isNikon) maker + 10 else maker
        if (mnBase + 8 > data.size) return null

        val mnLittle = byteOrderLittle(data, mnBase) ?: return null
        val mnIfd = u32(data, mnBase + 4, mnLittle)?.toInt() ?: return null
        val count = entryValue(data, mnBase, mnIfd, TAG_SHUTTER_COUNT, mnLittle) ?: return null

        // 合理性兜底：快门数不可能为负，也不可能几百万（读到垃圾就直接放弃）
        if (count < 0 || count > 5_000_000) return null
        return count.toInt()
    }

    // ---- 常量（ExifTool 的 Nikon 标签表）----
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_MAKER_NOTE = 0x927C
    private const val TAG_SHUTTER_COUNT = 0x00A7

    /** NEF 就是 TIFF，直接从 0 开始；JPEG 要先跳过 APP1 的 "Exif\0\0" */
    private fun findTiffBase(data: ByteArray): Int? {
        if (data.size < 8) return null
        if (isTiffHeader(data, 0)) return 0
        // 有些封装（JPEG）里 TIFF 头前面还有一段 Exif 标识，在前 128KB 内找一次
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
        val a = d[off]
        val b = d[off + 1]
        // ⚠️ 42 的位置跟字节序走：II 是 2A 00，MM 是 00 2A。
        // 早期版本只认 "II"（42,0），结果这台 D300 写出的 NEF 是**大端**（MM），
        // 整张文件在第一步就被判成"不是 TIFF"，快门数永远读不出来（真机实测踩过）。
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

    /**
     * 在一个 IFD 里找标签，返回它的**值**。
     *
     * 只处理"值就写在 4 字节字段里"的类型（SHORT/LONG）和"值是偏移"的类型
     * （MakerNote 是 UNDEFINED，值字段存的就是相对 TIFF 头的偏移）。
     * 快门数在尼康 MakerNote 里是 LONG，直接取值；MakerNote/ExifIFD 取偏移。
     */
    private fun entryValue(d: ByteArray, base: Int, ifdOffset: Int, tag: Int, little: Boolean): Long? {
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
                return when (type) {
                    // SHORT(3) / LONG(4) / SLONG(9) 且只有一个值 → 值直接在字段里
                    3, 4, 9 -> if (cnt == 1L) field else offsetOf(d, base, field, cnt)
                    // UNDEFINED(7) / ASCII(2) / 其它 → 字段是偏移
                    else -> offsetOf(d, base, field, cnt)
                }
            }
            p += 12
        }
        return null
    }

    /** 值存在别处时字段是偏移量：原样返回，交给调用方加基点（这里只做越界检查） */
    private fun offsetOf(d: ByteArray, base: Int, field: Long, cnt: Long): Long? {
        val off = field
        if (off < 0) return null
        if (base + off > d.size) return null
        if (cnt < 0) return null
        return off
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
}
