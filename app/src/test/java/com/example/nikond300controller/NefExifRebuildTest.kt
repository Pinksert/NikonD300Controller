package com.example.nikond300controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
private fun le32(v: Int) = byteArrayOf(
    (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
    ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
)

/** IFD 条目：tag / type / count / 4 字节值字段（外置值时放偏移） */
private fun ifdEntry(tag: Int, type: Int, count: Int, value: Int): ByteArray =
    le16(tag) + le16(type) + le32(count) + le32(value)

/**
 * EXIF 重建（NEF → JPG 搬元数据）的自测。
 *
 * 这块是**最容易静默出错**的地方：v1 的整段切片在 MakerNote 撑爆 APP1 时
 * 直接放弃，导出的 JPG 一个 EXIF 字节都没有，界面上却照样显示"导出成功"。
 * 所以这里把三件事钉死：
 *   1. MakerNote 巨大时先丢它（而不是整段放弃），核心参数必须还在；
 *   2. MakerNote 装得下时要原样保留；
 *   3. 结果永远塞得进 APP1（≤ 65533）。
 */
class NefExifRebuildTest {

    /** 一个能"先写目录、回填偏移"的最小 TIFF 拼装器 */
    private class TiffBuilder {
        private val buf = ArrayList<Byte>()
        /** 待回填的偏移所在的字节位置（按写入顺序编号，见 [patch]） */
        private val slots = ArrayList<Int>()

        init {
            buf.addAll(listOf('I'.code.toByte(), 'I'.code.toByte(), 42.toByte(), 0.toByte()))
            buf.addAll(le32(8).toList())     // IFD0 落在偏移 8（TIFF 头正好 8 字节）
        }

        val size: Int get() = buf.size

        private fun add(bs: ByteArray) { for (b in bs) buf.add(b) }
        fun raw(bs: ByteArray) = add(bs)
        fun zeros(n: Int) = add(ByteArray(n))

        /**
         * 写一组 IFD。
         * @param items 每条 = Triple(tag, type, count)
         * @param valueOf 返回内联值；null = 这是个偏移，待 [patch] 回填
         */
        fun ifd(items: List<Triple<Int, Int, Int>>, valueOf: (Int) -> Int?) {
            val base = buf.size
            add(le16(items.size))
            items.forEachIndexed { i, (tag, type, count) ->
                val v = valueOf(i)
                add(ifdEntry(tag, type, count, v ?: 0))
                if (v == null) slots.add(base + 2 + i * 12 + 8)
            }
            add(le32(0))                     // next-IFD = 0
        }

        fun patch(slot: Int, value: Int) {
            val at = slots[slot]
            val b = le32(value)
            buf[at] = b[0]; buf[at + 1] = b[1]; buf[at + 2] = b[2]; buf[at + 3] = b[3]
        }

        fun build(): ByteArray = ByteArray(buf.size) { buf[it] }
    }

    /**
     * 拼一个最小 NEF（TIFF）：
     * IFD0 = ImageWidth(160) / ImageLength(120) / ImageDescription / ExifIFD 指针
     * ExifIFD = DateTimeOriginal(0x9003) / MakerNote(0x927C, [makerSize] 字节)
     */
    private fun buildNef(makerSize: Int): ByteArray {
        val b = TiffBuilder()

        b.ifd(
            listOf(
                Triple(0x0100, 4, 1),   // ImageWidth（LONG，内联）
                Triple(0x0101, 4, 1),   // ImageLength
                Triple(0x010E, 2, 5),   // ImageDescription（ASCII 5 字节，外置）
                Triple(0x8769, 4, 1)    // ExifIFD 指针（外置偏移）
            )
        ) { i -> if (i == 0) 160 else if (i == 1) 120 else null }

        val descAt = b.size
        b.raw("ABCD\u0000".toByteArray(Charsets.US_ASCII))

        val exifAt = b.size
        b.ifd(
            listOf(
                Triple(0x9003, 2, 20),          // DateTimeOriginal：19 字符 + 结尾 null
                Triple(0x927C, 7, makerSize)    // MakerNote：UNDEFINED，长度可变
            )
        ) { null }

        val dtoAt = b.size
        b.raw("2026:10:24 12:34:56\u0000".toByteArray(Charsets.US_ASCII))

        val makerAt = b.size
        b.raw(ByteArray(makerSize) { (it % 251).toByte() })

        b.patch(0, descAt)
        b.patch(1, exifAt)
        b.patch(2, dtoAt)
        b.patch(3, makerAt)
        return b.build()
    }

    /** 最小 JPEG：SOI + SOF0(4288×2848) + EOI —— jpegSize() 只认 SOF 里的宽高 */
    private fun buildJpeg(): ByteArray {
        val sof = intArrayOf(
            0xFF, 0xC0, 0x00, 0x11,       // SOF0 + 段长 17
            0x08,                         // 精度
            0x0B, 0x20,                   // height = 0x0B20 = 2848
            0x10, 0xC0,                   // width  = 0x10C0 = 4288
            0x03,                         // 3 个分量
            0x01, 0x11, 0x00,
            0x02, 0x11, 0x01,
            0x03, 0x11, 0x01
        ).map { it.toByte() }.toByteArray()
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + sof +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** 从重建出来的 APP1 里读一个 IFD 条目的内联 LONG 值 */
    private fun longValue(app1: ByteArray, ifdAt: Int, tag: Int): Long? {
        val base = 6 + ifdAt                  // app1 = "Exif\0\0"(6) + TIFF
        val count = (app1[base].toInt() and 0xFF) or ((app1[base + 1].toInt() and 0xFF) shl 8)
        for (i in 0 until count) {
            val e = base + 2 + i * 12
            val t = (app1[e].toInt() and 0xFF) or ((app1[e + 1].toInt() and 0xFF) shl 8)
            if (t != tag) continue
            return (app1[e + 8].toLong() and 0xFF) or
                ((app1[e + 9].toLong() and 0xFF) shl 8) or
                ((app1[e + 10].toLong() and 0xFF) shl 16) or
                ((app1[e + 11].toLong() and 0xFF) shl 24)
        }
        return null
    }

    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) if (haystack[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    // ---------------- 用例 ----------------

    @Test
    fun makerNoteTooBig_dropsItInsteadOfGivingUp() {
        // 真机 DSC_5030.NEF 的 MakerNote 实测 121916 字节，远超 APP1 能装下的量
        val nef = buildNef(makerSize = 121_916)
        val app1 = NefPreview.buildExifApp1(nef, buildJpeg(), -1L)

        assertNotNull("MakerNote 太大时也必须重建出元数据段", app1)
        val a = app1!!
        assertTrue("APP1 段必须塞得进 65533（实际 ${a.size}）", a.size <= 65533)
        assertEquals("Exif\u0000\u0000", String(a.copyOfRange(0, 6), Charsets.US_ASCII))

        // 核心参数一条都不能少
        assertTrue("DateTimeOriginal 必须保住", containsAscii(a, "2026:10:24 12:34:56"))
        assertTrue("ImageDescription 必须保住", containsAscii(a, "ABCD"))
        // MakerNote 那 12 万字节显然不该出现
        assertTrue("MakerNote 应该被丢掉（结果 ${a.size}B）", a.size < 4096)
    }

    @Test
    fun smallMakerNote_isKept() {
        val nef = buildNef(makerSize = 16)
        val app1 = NefPreview.buildExifApp1(nef, buildJpeg(), -1L)
        assertNotNull(app1)
        val a = app1!!
        assertTrue("小 MakerNote 不该被丢", containsAscii(a, "2026:10:24 12:34:56"))
        assertTrue("小 MakerNote 应原样保留（结果 ${a.size}B）", a.size > 60)
    }

    /**
     * ★ 尺寸改写：IFD0 报的是 NEF 里那张 160×120 缩略图，
     * 不改成输出图真实尺寸（4288×2848），相册里就会显示错尺寸。
     */
    @Test
    fun imageSizeIsRewrittenToOutputSize() {
        val nef = buildNef(makerSize = 16)
        val app1 = NefPreview.buildExifApp1(nef, buildJpeg(), -1L)!!
        val ifd0 = 8          // app1 前缀 6 字节 + TIFF 头 8 字节 → IFD0 在 TIFF 内偏移 8
        assertEquals(4288L, longValue(app1, ifd0, 0x0100))
        assertEquals(2848L, longValue(app1, ifd0, 0x0101))
    }

    @Test
    fun nonTiffInputIsRejected() {
        assertNull(NefPreview.buildExifApp1(ByteArray(64), buildJpeg(), -1L))
        assertNull(
            NefPreview.buildExifApp1(
                byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 1, 0) + ByteArray(60),
                buildJpeg(), -1L
            )
        )
    }
}
