package com.example.nikond300controller

/**
 * 从 NEF 里定位内嵌的 JPEG 预览。
 *
 * D300 的 NEF 在 PTP 上报 `0x3802 TIFF/EP`，Android 的 BitmapFactory 解不了 ——
 * 但文件本身是 TIFF 容器，里面塞了至少一张 JPEG 预览（就是机身背屏回放用的那张）。
 * 把它抠出来就能在相册里看到真正的画面，而不只是 160×120 的缩略图。
 *
 * 定位靠这对标准 TIFF 标签：
 * - `0x0201 JPEGInterchangeFormat` —— 预览 JPEG 在文件里的偏移
 * - `0x0202 JPEGInterchangeFormatLength` —— 预览 JPEG 的字节数
 *
 * 一个 NEF 里可能有多组（缩略图 + 大预览），所以遍历所有 IFD、取**最长**的那份。
 */
object NefPreview {

    /** 一份内嵌 JPEG 的位置；[width]/[height] 取同一 IFD 里的 ImageWidth/ImageLength */
    class Location(
        val offset: Long,
        val length: Int,
        val width: Int,
        val height: Int
    )

    private const val TAG_JPEG_OFFSET = 0x0201
    private const val TAG_JPEG_LENGTH = 0x0202
    private const val TAG_IMAGE_WIDTH = 0x0100
    private const val TAG_IMAGE_LENGTH = 0x0101
    private const val TAG_SUB_IFDS = 0x014A
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_GPS_IFD = 0x8825
    private const val TAG_INTEROP = 0xA005

    /** IFD0 里只描述 RAW / 缩略图的标签：搬到 JPG 里没有意义，还会留下野偏移 */
    private val DROP_IFD0 = intArrayOf(0x0111, 0x0116, 0x0117, TAG_SUB_IFDS)

    /**
     * @param head 文件开头的若干字节（几百 KB 就够，IFD 都在头部）
     * @return 最大的那份内嵌 JPEG 的位置；没有就 null
     */
    fun locate(head: ByteArray): Location? {
        if (head.size < 16) return null

        // 尼康 NEF 基本都是 Intel 小端（"II"），但两种都得认
        val little = when {
            head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> true
            head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (u16(head, 2, little) != 0x2A) return null

        val ifd0 = (u32(head, 4, little) and 0xFFFFFFFFL)
        if (ifd0 <= 0 || ifd0 >= head.size) return null

        val found = mutableListOf<Location>()
        walkIfd(head, ifd0, little, found, 0)
        return found.maxByOrNull { it.length }
    }

    private fun walkIfd(
        data: ByteArray,
        offset: Long,
        little: Boolean,
        out: MutableList<Location>,
        depth: Int
    ) {
        // IFD 链和 SubIFD 可能互相套，深度兜底防止异常文件把栈打爆
        if (depth > 4 || offset <= 0 || offset + 2 > data.size) return

        val count = u16(data, offset.toInt(), little)
        if (count == 0 || count > 512) return

        var jpegOffset = -1L
        var jpegLen = -1
        var width = 0
        var height = 0

        for (i in 0 until count) {
            val entry = offset.toInt() + 2 + i * 12
            if (entry + 12 > data.size) break

            val tag = u16(data, entry, little)
            val countVal = u32(data, entry + 4, little)
            val value = u32(data, entry + 8, little)

            when (tag) {
                TAG_JPEG_OFFSET -> jpegOffset = value and 0xFFFFFFFFL
                TAG_JPEG_LENGTH -> {
                    jpegLen = (value and 0xFFFFFFFFL).toInt()
                    // 偏移和长度一旦凑成一对就立刻登记：同一个 IFD 里可能
                    // 既有 160×120 的小缩略图又有大预览，都塞进候选里让
                    // 调用方挑最大的，而不是被后出现的（未必是较大的）覆盖掉。
                    if (jpegOffset > 0 && jpegLen > 4 && jpegOffset + jpegLen <= Int.MAX_VALUE) {
                        out.add(Location(jpegOffset, jpegLen, width, height))
                    }
                    jpegOffset = -1L
                    jpegLen = -1
                }
                TAG_IMAGE_WIDTH -> width = value.toInt()
                TAG_IMAGE_LENGTH -> height = value.toInt()
                TAG_SUB_IFDS, TAG_EXIF_IFD -> {
                    // count==1 时偏移就在 value 字段里；更多时 value 指向一个 u32 数组
                    if (countVal == 1L) {
                        walkIfd(data, value and 0xFFFFFFFFL, little, out, depth + 1)
                    } else {
                        val arrayAt = (value and 0xFFFFFFFFL).toInt()
                        for (k in 0 until countVal.toInt().coerceAtMost(8)) {
                            val p = arrayAt + k * 4
                            if (p + 4 > data.size) break
                            walkIfd(data, u32(data, p, little) and 0xFFFFFFFFL, little, out, depth + 1)
                        }
                    }
                }
            }
        }

        val nextAt = offset.toInt() + 2 + count * 12
        if (nextAt + 4 <= data.size) {
            val next = u32(data, nextAt, little) and 0xFFFFFFFFL
            if (next != 0L) walkIfd(data, next, little, out, depth + 1)
        }
    }

    /** 已经拿到完整文件字节时，直接从里面把预览那段切出来 */
    fun slice(full: ByteArray, loc: Location): ByteArray? {
        val from = loc.offset.toInt()
        val to = from + loc.length
        if (from < 0 || to > full.size || loc.length <= 0) return null
        return full.copyOfRange(from, to)
    }

    // ------------------------------------------------------------------
    // NEF → JPG：抠出来的预览要**带上原图的 EXIF**
    //
    // 机身内嵌的那张预览 JPEG 里通常一个 EXIF 字节都没有（它只是给背屏回放用的），
    // 直接落盘的话日期、ISO、快门、光圈、焦距、白平衡、补偿、测光全丢 ——
    // 导到手机相册里就变成"没有拍摄信息的图"，按时间排也排不进正确的位置。
    //
    // NEF 本身就是 TIFF 容器，而 JPEG 的 APP1 里装的也是一段 TIFF（"Exif\0\0" 之后
    // 就是这个文件的 TIFF 头 + 各级 IFD，IFD 里的偏移都是相对 TIFF 头算的）。
    // 两者坐标原点一致 → 把 NEF 开头那段元数据原样搬进 APP1 就完事，
    // 不用一条条标签重新拼（那样只会丢标签，尤其尼康 MakerNote）。
    // ------------------------------------------------------------------

    /**
     * 把 NEF 的 EXIF 原样搬到一张 JPEG 上（[jpg] 可以是 LibRaw 解出来的成品，
     * 不再限于机身内嵌的预览）。
     *
     * 拼不出元数据段时原样返回 [jpg]，宁可少一段 EXIF，也不能把图弄坏。
     */
    /**
     * 把 NEF 的 EXIF 原样搬到一张 JPEG 上（[jpg] 可以是 LibRaw 解出来的成品，
     * 不再限于机身内嵌的预览）。
     *
     * 拼不出元数据段时原样返回 [jpg]，宁可少一段 EXIF，也不能把图弄坏。
     *
     * ⚠️ 曾经这里**静默失败**：导出的 JPG 一个 EXIF 字节都没有，界面上却显示"导出成功"。
     * 现在每一条退路都往 logcat 打一句（tag = `EXIF`），现场拿 `adb logcat -s EXIF`
     * 就能知道是"没找到预览位置"还是"元数据段算出来太大塞不进 APP1"。
     */
    fun attachExif(jpg: ByteArray, nef: ByteArray): ByteArray {
        if (hasExif(jpg)) {
            android.util.Log.i("EXIF", "预览 JPEG 自带 APP1，不另插")
            return jpg
        }
        val loc = runCatching { locate(nef) }.getOrNull()
        if (loc == null) {
            android.util.Log.w("EXIF", "没定位到 NEF 里的预览位置（NEF ${nef.size}B），放弃搬 EXIF")
            return jpg
        }
        val imageStart = loc.offset
        val app1 = runCatching { buildExifApp1(nef, jpg, imageStart) }.getOrNull()
        if (app1 == null) {
            android.util.Log.w("EXIF", "切不出元数据段（预览起点 $imageStart / 文件 ${nef.size}B）")
            return jpg
        }
        if (app1.size + 2 > 0xFFFF) {
            // APP1 的长度字段只有 16 位，塞不下就只能在"完整"和"能写进 JPEG"里二选一
            android.util.Log.w("EXIF", "元数据段 ${app1.size + 2}B 超过 APP1 上限 65535，只能不搬")
            return jpg
        }
        android.util.Log.i("EXIF", "已搬 EXIF：${app1.size}B")
        return insertApp1(jpg, app1)
    }

    /** 预览里已经有 APP1（Exif）就别再插一份，否则一个文件里两段 EXIF 会打架 */
    private fun hasExif(jpg: ByteArray): Boolean {
        val limit = minOf(jpg.size - 4, 128 * 1024)
        for (i in 2..limit) {
            if (jpg[i] == 0xFF.toByte() && jpg[i + 1] == 0xE1.toByte()) {
                val j = i + 4
                return j + 4 <= jpg.size &&
                    jpg[j] == 'E'.code.toByte() && jpg[j + 1] == 'x'.code.toByte() &&
                    jpg[j + 2] == 'i'.code.toByte() && jpg[j + 3] == 'f'.code.toByte()
            }
            // APP0/JFIF 之类的段照常跳过，只看第一个 APP1
            if (jpg[i] == 0xFF.toByte() && jpg[i + 1] == 0xDA.toByte()) break
        }
        return false
    }

    /** 把 APP1 段插到 SOI 之后（JPEG 的 EXIF 必须紧跟 SOI） */
    private fun insertApp1(jpg: ByteArray, app1: ByteArray): ByteArray {
        // APP1 长度字段只有 16 位，塞不下就放弃插（元数据段超过 64KB 的 NEF 极少）
        if (app1.size + 2 > 0xFFFF) return jpg
        val out = ByteArray(jpg.size + app1.size + 4)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
        out[2] = 0xFF.toByte(); out[3] = 0xE1.toByte()
        val len = app1.size + 2
        out[4] = (len ushr 8).toByte(); out[5] = (len and 0xFF).toByte()
        System.arraycopy(app1, 0, out, 6, app1.size)
        System.arraycopy(jpg, 2, out, 6 + app1.size, jpg.size - 2)
        return out
    }

    /**
     * 从 NEF 头部切出元数据段，前面拼上 "Exif\0\0"。
     *
     * 切到哪为止：只走 IFD0 / ExifIFD / GPS / Interoperability 这几级**真正有用的** IFD，
     * 把"IFD 目录本身的尾部"和"每个超长值（>4 字节，值存在目录外）的尾部"都算进去取最大值。
     * 再和预览 JPEG 的起始偏移取小 —— 图像数据一定在那之后，元数据不可能越过它。
     *
     * ⚠️ **故意不跟 0x014A SubIFDs 和 IFD0 的 next-IFD**：D300 的 NEF 里 IFD0 只是那张
     * 160×120 的小缩略图，真正的 RAW（4352×2868）和你要抠的预览 JPEG 都挂在 SubIFDs 下，
     * 而且那些子 IFD 落在文件**末尾**（本样本在 72856 之后）。跟着它们算出来的元数据段有
     * 73 KB，APP1 的长度字段只有 16 位（上限 65533）根本塞不下 —— 只能整段放弃。
     * 只走主 IFD 那几级是 15 KB，MakerNote / GPS / 全部曝光参数一个不少。
     *
     * 另外顺手在副本上改三处，否则搬到 JPG 里会说不通：
     * - 丢掉 IFD0 里描述 RAW/缩略图的 0x0111 StripOffsets / 0x0116 RowsPerStrip /
     *   0x0117 StripByteCounts / 0x014A SubIFDs（后两个指向搬不走的子 IFD）；
     * - 0x0100 ImageWidth / 0x0101 ImageLength 改成**预览 JPEG 的真实尺寸**
     *   （IFD0 原本报的是 160×120 缩略图，不改成 4288×2848 相册里就显示错尺寸）；
     * - IFD0 的 next-IFD 指针清零（那级 IFD 不搬，留着就是野偏移）。
     */
    private fun buildExifApp1(nef: ByteArray, jpg: ByteArray, imageStart: Long): ByteArray? {
        if (nef.size < 16) return null
        val little = when {
            nef[0] == 'I'.code.toByte() && nef[1] == 'I'.code.toByte() -> true
            nef[0] == 'M'.code.toByte() && nef[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (u16(nef, 2, little) != 0x2A) return null

        val ifd0 = (u32(nef, 4, little) and 0xFFFFFFFFL).toInt()
        // ⚠️ 别写成 `ifd0 <= 8`：TIFF 头正好占 8 字节，**IFD0 绝大多数就落在偏移 8**，
        // 那样写会把全部正常文件挡在门外（表现为"导出 JPG 一点 EXIF 都没有"，
        // 而且因为是在数据装箱前返回，日志什么都不留下）。
        if (ifd0 < 8 || ifd0 + 6 > nef.size) return null
        val count = u16(nef, ifd0, little)
        if (count == 0 || count > 512) return null

        val acc = longArrayOf(8L)
        metaEnd(nef, ifd0.toLong(), little, acc, 0)
        val end = acc[0].coerceAtMost(minOf(nef.size.toLong(), imageStart)).toInt()
        if (end < ifd0 + 2 + count * 12 + 4 || end < 16) return null

        val tiff = nef.copyOfRange(0, end)
        val size = jpegSize(jpg)

        // IFD0 原地重写：丢掉几条、改两条，剩下的顺序不变（TIFF 要求标签升序）
        var p = ifd0 + 2
        var kept = 0
        for (i in 0 until count) {
            val e = ifd0 + 2 + i * 12
            if (e + 12 > tiff.size) break
            val tag = u16(tiff, e, little)
            if (tag in DROP_IFD0) continue
            for (k in 0 until 12) tiff[p + k] = tiff[e + k]
            val type = u16(tiff, e + 2, little)
            // ⚠️ TIFF 的 count 字段是 **4 字节**（LONG），不是 2 字节 ——
            // 用 u16 读会拿到高半截（大端文件里恒为 0），条件永远不成立。
            if (size != null && (tag == TAG_IMAGE_WIDTH || tag == TAG_IMAGE_LENGTH) &&
                (u32(tiff, e + 4, little) and 0xFFFFFFFFL) == 1L     // count == 1：值就在这 4 个字节里
            ) {
                val want = if (tag == TAG_IMAGE_WIDTH) size.first else size.second
                when (type) {
                    3 -> put16(tiff, p + 8, want, little)          // SHORT
                    4 -> put32(tiff, p + 8, want, little)          // LONG（D300 就是这种）
                }
            }
            p += 12
            kept++
        }
        for (k in 0 until 4) tiff[p + k] = 0     // next-IFD = 0
        if (kept == 0) return null
        put16(tiff, ifd0, kept, little)

        val head = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        return head + tiff
    }

    /** 递归走 IFD，把元数据区的末尾推进 [acc]（acc[0] = 目前算到的最大偏移） */
    private fun metaEnd(data: ByteArray, ifdOffset: Long, little: Boolean, acc: LongArray, depth: Int) {
        if (depth > 4) return
        val off = ifdOffset.toInt()
        if (off <= 0 || off + 2 > data.size) return
        val count = u16(data, off, little)
        if (count == 0 || count > 512) return

        val dirEnd = off + 2L + count.toLong() * 12L + 4L
        if (dirEnd > acc[0]) acc[0] = dirEnd

        for (i in 0 until count) {
            val e = off + 2 + i * 12
            if (e + 12 > data.size) break
            val tag = u16(data, e, little)
            val type = u16(data, e + 2, little)
            val cnt = u32(data, e + 4, little) and 0xFFFFFFFFL
            val value = u32(data, e + 8, little) and 0xFFFFFFFFL

            // 超过 4 字节的值存在目录之外，那段也算元数据区
            val bytes = typeSize(type) * cnt
            if (bytes > 4 && bytes <= data.size.toLong() && value + bytes <= data.size) {
                val stop = value + bytes
                if (stop > acc[0]) acc[0] = stop
            }

            // 只跟这三级：ExifIFD / GPS / Interoperability。SubIFDs 与 next-IFD 不跟（见上面注释）
            if (tag == TAG_EXIF_IFD || tag == TAG_GPS_IFD || tag == TAG_INTEROP) {
                if (value > 0) metaEnd(data, value, little, acc, depth + 1)
            }
        }
    }

    /** TIFF 每种类型的字节数；不认识的类型按 1 算（不会再拿它去扩元数据区） */
    private fun typeSize(type: Int): Long = when (type) {
        1, 2, 6, 7 -> 1L
        3, 8 -> 2L
        4, 9, 11 -> 4L
        5, 10, 12 -> 8L
        else -> 1L
    }

    /**
     * 从 JPEG 里读真实像素尺寸（SOF 段）。
     * 用来把 EXIF 里的 ImageWidth/Length 改成预览的实际大小 —— 否则相册会按
     * NEF 的 IFD0（160×120 缩略图）报尺寸。
     */
    private fun jpegSize(jpg: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < jpg.size) {
            if (jpg[i] != 0xFF.toByte()) { i++; continue }
            val m = jpg[i + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0xD9 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            val seg = ((jpg[i + 2].toInt() and 0xFF) shl 8) or (jpg[i + 3].toInt() and 0xFF)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = ((jpg[i + 5].toInt() and 0xFF) shl 8) or (jpg[i + 6].toInt() and 0xFF)
                val w = ((jpg[i + 7].toInt() and 0xFF) shl 8) or (jpg[i + 8].toInt() and 0xFF)
                if (w > 0 && h > 0) return w to h
                return null
            }
            if (seg < 2) return null
            i += 2 + seg
        }
        return null
    }

    private fun put16(b: ByteArray, at: Int, v: Int, little: Boolean) {
        if (at + 2 > b.size) return
        if (little) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v ushr 8) and 0xFF).toByte() }
        else { b[at] = ((v ushr 8) and 0xFF).toByte(); b[at + 1] = (v and 0xFF).toByte() }
    }

    private fun put32(b: ByteArray, at: Int, v: Int, little: Boolean) {
        if (at + 4 > b.size) return
        val b0 = v and 0xFF
        val b1 = (v ushr 8) and 0xFF
        val b2 = (v ushr 16) and 0xFF
        val b3 = (v ushr 24) and 0xFF
        if (little) {
            b[at] = b0.toByte(); b[at + 1] = b1.toByte(); b[at + 2] = b2.toByte(); b[at + 3] = b3.toByte()
        } else {
            b[at] = b3.toByte(); b[at + 1] = b2.toByte(); b[at + 2] = b1.toByte(); b[at + 3] = b0.toByte()
        }
    }

    private fun u16(b: ByteArray, at: Int, little: Boolean): Int {
        if (at + 2 > b.size) return 0
        val v0 = b[at].toInt() and 0xFF
        val v1 = b[at + 1].toInt() and 0xFF
        return if (little) (v1 shl 8) or v0 else (v0 shl 8) or v1
    }

    private fun u32(b: ByteArray, at: Int, little: Boolean): Long {
        if (at + 4 > b.size) return 0L
        val v0 = b[at].toLong() and 0xFF
        val v1 = b[at + 1].toLong() and 0xFF
        val v2 = b[at + 2].toLong() and 0xFF
        val v3 = b[at + 3].toLong() and 0xFF
        return if (little) (v3 shl 24) or (v2 shl 16) or (v1 shl 8) or v0
        else (v0 shl 24) or (v1 shl 16) or (v2 shl 8) or v3
    }
}
