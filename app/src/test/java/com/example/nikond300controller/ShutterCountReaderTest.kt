package com.example.nikond300controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 快门数解析器的自测：拿手工拼出来的最小 TIFF + 尼康 MakerNote 验结构假设。
 *
 * 没有真 NEF 也能测——真正容易错的是缩进层级（IFD0 → ExifIFD → MakerNote → 尼康 IFD）
 * 和偏移基点（MakerNote 内部偏移相对 MakerNote 起点，而不是文件头）。
 */
class ShutterCountReaderTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    /** 一个 IFD 条目：tag/type/count/value(4 字节，SHORT 时低 2 字节有效) */
    private fun entry(tag: Int, type: Int, count: Int, value: Int): ByteArray =
        le16(tag) + le16(type) + le32(count) + le32(value)

    /**
     * 拼一个最小可解析结构：
     * TIFF 头(II*\0, IFD0 在 8) → IFD0 一个条目指向 ExifIFD →
     * ExifIFD 一个条目指向 MakerNote → MakerNote("Nikon\0"+版本+TIFF头) → 0xA7 = shutterCount
     */
    private fun buildSynthetic(shutterCount: Int): ByteArray {
        val out = ByteArrayOutputStream()

        // ---- TIFF 头 ----
        out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0))
        out.write(le32(8))                      // IFD0 偏移

        // ---- IFD0 @8：1 个条目（0x8769 → ExifIFD），值放在 8+2+12=22 之后 ----
        val ifd0At = 8
        val exifIfdAt = 26                      // IFD0 结束于 8+2+12+4=26
        out.write(le16(1))                      // 条目数
        out.write(entry(0x8769, 4, 1, exifIfdAt))
        out.write(le32(0))                      // 下一个 IFD = 0

        // ---- ExifIFD @26：1 个条目（0x927C → MakerNote 偏移），MakerNote 紧随其后 ----
        val makerAt = exifIfdAt + 2 + 12 + 4    // = 44
        out.write(le16(1))
        out.write(entry(0x927C, 7, 1, makerAt)) // UNDEFINED，值是偏移
        out.write(le32(0))

        // ---- MakerNote @44：头 10 字节 + 自己的 TIFF 头 + 尼康 IFD ----
        // 真机上就是 "Nikon\0"(6) + 版本(2) + 对齐(2) 共 10 字节，内嵌 TIFF 头从 +10 开始
        out.write("Nikon".toByteArray())
        out.write(byteArrayOf(0, 0x02, 0x10, 0, 0))   // \0 + 版本 2.10 + 2 字节对齐
        // 内嵌 TIFF 头（偏移 10），IFD 相对 MakerNote 起点在 10+8=18
        out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0))
        out.write(le32(8))
        out.write(le16(1))                       // 1 个条目
        out.write(entry(0x00A7, 4, 1, shutterCount))
        out.write(le32(0))

        return out.toByteArray()
    }

    @Test
    fun readsShutterCountFromNikonMakerNote() {
        assertEquals(12345, ShutterCountReader.read(buildSynthetic(12345)))
    }

    @Test
    fun readsZeroAndLargeValues() {
        assertEquals(0, ShutterCountReader.read(buildSynthetic(0)))
        assertEquals(987654, ShutterCountReader.read(buildSynthetic(987654)))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(ShutterCountReader.read(ByteArray(64)))
        assertNull(ShutterCountReader.read("not a tiff at all".toByteArray()))
    }

    // ---- 大端样本：D300 实际写出的 NEF 就是 MM（真机 DSC_4237.NEF 实测）----
    private fun be16(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    private fun be32(v: Int) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )
    private fun beEntry(tag: Int, type: Int, count: Int, value: Int): ByteArray =
        be16(tag) + be16(type) + be32(count) + be32(value)

    private fun buildSyntheticBigEndian(shutterCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
        out.write(be32(8))

        val exifIfdAt = 26
        out.write(be16(1))
        out.write(beEntry(0x8769, 4, 1, exifIfdAt))
        out.write(be32(0))

        val makerAt = exifIfdAt + 2 + 12 + 4
        out.write(be16(1))
        out.write(beEntry(0x927C, 7, 1, makerAt))
        out.write(be32(0))

        out.write("Nikon".toByteArray())
        out.write(byteArrayOf(0, 0x02, 0x10, 0, 0))
        out.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
        out.write(be32(8))
        out.write(be16(1))
        out.write(beEntry(0x00A7, 4, 1, shutterCount))
        out.write(be32(0))
        return out.toByteArray()
    }

    @Test
    fun readsBigEndianNef() {
        // 真机 DSC_4237.NEF 是 MM 字节序，这一条防的就是"只认 II"的回归
        assertEquals(78444, ShutterCountReader.read(buildSyntheticBigEndian(78444)))
    }
}
