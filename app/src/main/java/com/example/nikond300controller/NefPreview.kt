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
