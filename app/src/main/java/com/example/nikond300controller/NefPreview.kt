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
    /** 尼康私有段。体积的绝大部分都在这（小样张 14 KB，长曝片能到 119 KB） */
    private const val TAG_MAKER_NOTE = 0x927C

    /**
     * APP1 里能装的 TIFF 上限。
     *
     * APP1 的长度字段只有 16 位（含长度字段自身 2 字节 → 实际 65533），
     * 再减去 "Exif\0\0" 这 6 字节，留给 TIFF 的就是这个数。
     */
    private const val APP1_LIMIT = 65533 - 6

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
        // ★ 预览位置**不再是前置条件**：它只是用来判断"外置值有没有越过图像区"的参考线。
        // v1 把它当成了必须品——定位不到就整段放弃，白丢一次搬 EXIF 的机会。
        // 现在找不到就传 -1，重建 TIFF 照样能跑（值区只按文件大小校验）。
        val imageStart = runCatching { locate(nef)?.offset }.getOrNull() ?: -1L
        if (imageStart < 0) {
            android.util.Log.w("EXIF", "没定位到预览位置（NEF ${nef.size}B）—— 仍尝试重建 EXIF")
        }
        val app1 = runCatching { buildExifApp1(nef, jpg, imageStart) }.getOrNull()
        if (app1 == null) {
            android.util.Log.w("EXIF", "重建元数据段失败（预览起点 $imageStart / 文件 ${nef.size}B）")
            return jpg
        }
        if (app1.size > APP1_LIMIT + 6) {
            android.util.Log.w("EXIF", "元数据段 ${app1.size}B 超过 APP1 上限 ${APP1_LIMIT + 6}，只能不搬")
            return jpg
        }
        android.util.Log.i("EXIF", "已搬 EXIF：${app1.size}B")
        // 同一句话也进主日志：真机排查时大家都在看 D300_LOG，EXIF 这个 tag 容易被漏掉
        android.util.Log.d("D300_LOG", "EXIF 已写入：${app1.size}B")
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
    internal fun insertApp1(jpg: ByteArray, app1: ByteArray): ByteArray {
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
     * 从 NEF **重建**一份干净的 TIFF 元数据段，前面拼上 "Exif\0\0"。
     *
     * ★★ v1（已废）是在 NEF 头部"算出元数据区末尾 → copyOfRange(0, end)"整段切片。
     * 它在小样张上能用（元数据 15 KB），但**长曝 / 暗部多的片子 MakerNote 能到 119 KB**
     * （真机 DSC_5030.NEF 实测 121916 字节）—— 整段超过 APP1 的 65533 上限后只能
     * **整段放弃**，导出的 JPG 于是**一个 EXIF 字节都没有**。
     * 这正是"导出的 JPG 没有原始相机 EXIF"的根因。
     *
     * v2 改成重建：把 IFD0 / ExifIFD / GPS / Interop 的条目读出来，重新排布成一份新 TIFF。
     * 塞不进 65533 就**先丢 MakerNote**（尼康私有段，占体积的绝大部分，标准软件基本不读），
     * 还超就按值区体积从大到小继续丢。核心拍摄参数（日期 / 快门 / 光圈 / ISO / 焦距 /
     * 曝光补偿 / 测光 / 白平衡 / GPS）一条都不在那些大块里，所以永远保得住。
     *
     * 只走 IFD0 / ExifIFD / GPS / Interop 这四级：
     * ⚠️ **故意不跟 0x014A SubIFDs 和 IFD0 的 next-IFD** —— D300 的 NEF 里它们挂着真正的
     * RAW（4320×2868）和内嵌预览 JPEG，跟着算出来的段有 70~120 KB，根本塞不进 APP1。
     *
     * 另外顺手改两处，否则搬到 JPG 里会说不通：
     * - 丢掉 IFD0 里描述 RAW/缩略图的 0x0111 StripOffsets / 0x0116 RowsPerStrip /
     *   0x0117 StripByteCounts / 0x014A SubIFDs（后两个指向搬不走的子 IFD）；
     * - 0x0100 ImageWidth / 0x0101 ImageLength 改成**输出图的真实尺寸**
     *   （IFD0 原本报的是 160×120 缩略图，不改成 4288×2848 相册里就显示错尺寸）。
     *
     * @param imageStart 预览 JPEG 的起点，只用来判断"外置值有没有越过图像区"，可传 -1
     */
    internal fun buildExifApp1(nef: ByteArray, jpg: ByteArray, imageStart: Long): ByteArray? {
        if (nef.size < 16) return null
        val little = when {
            nef[0] == 'I'.code.toByte() && nef[1] == 'I'.code.toByte() -> true
            nef[0] == 'M'.code.toByte() && nef[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (u16(nef, 2, little) != 0x2A) return null

        val ifd0 = (u32(nef, 4, little) and 0xFFFFFFFFL).toInt()
        // ⚠️ 别写成 `ifd0 <= 8`：TIFF 头正好占 8 字节，**IFD0 绝大多数就落在偏移 8**，
        // 那样写会把全部正常文件挡在门外（表现为"导出 JPG 一点 EXIF 都没有"）。
        if (ifd0 < 8 || ifd0 + 6 > nef.size) return null

        val groups = collectIfds(nef, ifd0, little, imageStart) ?: return null
        val size = jpegSize(jpg)
        val tiff = packTiff(nef, groups, little, size) ?: return null

        val head = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        return head + tiff
    }

    // ---- 重建 TIFF：条目容器 ----

    /** 一个要搬走的 TIFF 条目；[blob] 是它的值字节（≤4 字节时内联） */
    private class Item(
        val tag: Int,
        val type: Int,
        val count: Long,
        val blob: ByteArray,
        /** 只给内联值用：把输出图尺寸写进去 */
        val isSize: Boolean = false
    ) {
        val externalBytes: ByteArray? get() = if (blob.size > 4) blob else null
    }

    /** 一组 IFD：key = ifd0 / exif / gps / interop */
    private class IfdSet {
        val order = mutableListOf<String>()          // 保持 kind 顺序，布局要用
        val map = LinkedHashMap<String, MutableList<Item>>()
        fun put(kind: String, items: List<Item>) {
            if (items.isEmpty()) return
            map[kind] = items.toMutableList()
            if (kind !in order) order.add(kind)
        }
        fun keys(): List<String> = order.filter { map.containsKey(it) }
    }

    /**
     * 读出 IFD0 及它挂着的 ExifIFD / GPS / Interop（Interop 也可能挂在 ExifIFD 下）。
     *
     * @param imageStart 预览 JPEG 起点；外置值越过它就当坏数据丢掉（那其实是图像数据）
     */
    private fun collectIfds(
        data: ByteArray, ifd0: Int, little: Boolean, imageStart: Long
    ): IfdSet? {
        val root = readIfd(data, ifd0, little, imageStart) ?: return null
        val set = IfdSet()
        // IFD0 里描述 RAW/缩略图的几条直接丢：搬到 JPG 上没有意义，还可能是野偏移
        set.put("ifd0", root.first.filter { it.tag !in DROP_IFD0 })

        var exifAt = -1
        var gpsAt = -1
        for (it in root.first) {
            when (it.tag) {
                TAG_EXIF_IFD -> exifAt = u32(data, ifd0 + 2 + root.first.indexOf(it) * 12 + 8, little).toInt()
                TAG_GPS_IFD -> gpsAt = u32(data, ifd0 + 2 + root.first.indexOf(it) * 12 + 8, little).toInt()
            }
        }
        if (exifAt > 0) {
            val ex = readIfd(data, exifAt, little, imageStart) ?: return set
            set.put("exif", ex.first.filter { it.tag != TAG_INTEROP })
            val interAt = ex.second
            if (interAt > 0) {
                val io = readIfd(data, interAt, little, imageStart)
                if (io != null) set.put("interop", io.first)
            }
        }
        if (gpsAt > 0) {
            val gp = readIfd(data, gpsAt, little, imageStart)
            if (gp != null) set.put("gps", gp.first)
        }
        return set
    }

    /**
     * 读一份 IFD 的条目。
     *
     * @return Pair(条目列表, Interop 子 IFD 的偏移)；没有 Interop 时 second = -1
     */
    private fun readIfd(
        data: ByteArray, off: Int, little: Boolean, imageStart: Long
    ): Pair<List<Item>, Int>? {
        if (off <= 0 || off + 2 > data.size) return null
        val count = u16(data, off, little)
        if (count == 0 || count > 512) return null

        val items = ArrayList<Item>(count)
        var interop = -1
        for (i in 0 until count) {
            val e = off + 2 + i * 12
            if (e + 12 > data.size) break
            val tag = u16(data, e, little)
            val type = u16(data, e + 2, little)
            val cnt = u32(data, e + 4, little) and 0xFFFFFFFFL
            val valAt = (u32(data, e + 8, little) and 0xFFFFFFFFL).toInt()

            val bytes = typeSize(type) * cnt
            val blob = if (bytes <= 4) {
                data.copyOfRange(e + 8, e + 8 + bytes.toInt().coerceAtMost(4))
            } else {
                // 外置值：越过图像区 / 文件尾的都是坏数据（切切片那版就是被它们坑的）
                if (bytes > data.size.toLong() || valAt <= 0 || valAt + bytes > data.size) continue
                if (imageStart > 0 && valAt + bytes > imageStart) continue
                data.copyOfRange(valAt, (valAt + bytes).toInt())
            }
            if (tag == TAG_INTEROP && bytes <= 4) interop = valAt
            items.add(Item(tag, type, cnt, blob, tag == TAG_IMAGE_WIDTH || tag == TAG_IMAGE_LENGTH))
        }
        return items to interop
    }

    /** 按 [APP1_LIMIT] 打包成一份新 TIFF；塞不下就先丢 MakerNote，再按体积降序丢 */
    private fun packTiff(
        nef: ByteArray, set: IfdSet, little: Boolean, size: Pair<Int, Int>?
    ): ByteArray? {
        val keys = set.keys()
        if (keys.isEmpty()) return null

        /** 挑出要保留的条目；[drop] 是 (kind,tag) 的集合 */
        fun select(drop: Set<Pair<String, Int>>): LinkedHashMap<String, List<Item>> {
            val out = LinkedHashMap<String, List<Item>>()
            for (k in keys) {
                val kept = set.map[k]!!.filter { (k to it.tag) !in drop }
                if (kept.isNotEmpty()) out[k] = kept
            }
            return out
        }

        fun totalOf(sel: Map<String, List<Item>>): Int {
            var n = 8
            for ((_, items) in sel) {
                n += 2 + items.size * 12 + 4
                for (it in items) it.externalBytes?.let { b -> n += b.size + (b.size and 1) }
            }
            return n
        }

        var drop = emptySet<Pair<String, Int>>()
        var sel = select(drop)
        val dropped = ArrayList<String>()

        // ① 先丢 MakerNote：尼康私有段，动辄十几到一百多 KB，标准软件基本不读。
        //    丢掉它，DSC_5030 那种片子从 123 KB 降到 1 KB —— 全部标准参数一个不少。
        if (totalOf(sel) > APP1_LIMIT) {
            val withMn = drop
            drop = keys.map { it to TAG_MAKER_NOTE }.toSet()
            val trial = select(drop)
            if (totalOf(trial) <= APP1_LIMIT) {
                sel = trial
                dropped.add("MakerNote")
            } else {
                drop = withMn
            }
        }
        // ② 还超就按"值区体积"从大到小丢，尺寸标签永远不丢
        if (totalOf(sel) > APP1_LIMIT) {
            val big = ArrayList<Triple<Int, String, Int>>()
            for ((k, items) in sel) {
                for (it in items) it.externalBytes?.let { b ->
                    if (it.tag != TAG_IMAGE_WIDTH && it.tag != TAG_IMAGE_LENGTH)
                        big.add(Triple(b.size, k, it.tag))
                }
            }
            big.sortByDescending { it.first }
            val acc = drop.toMutableSet()
            for ((_, k, tag) in big) {
                if (totalOf(sel) <= APP1_LIMIT) break
                acc.add(k to tag)
                val trial = select(acc)
                if (trial.isEmpty()) break
                sel = trial
                dropped.add("0x%04X@%s".format(tag, k))
            }
            drop = acc
        }
        if (sel.isEmpty()) return null

        // ---- 布局：TIFF 头 → 各级 IFD 目录 → 值区 ----
        val kinds = keys.filter { sel.containsKey(it) }
        val dirAt = LinkedHashMap<String, Int>()
        var pos = 8
        for (k in kinds) {
            dirAt[k] = pos
            pos += 2 + sel[k]!!.size * 12 + 4
        }
        val dataStart = pos

        val externals = ArrayList<Triple<String, Item, Int>>()
        val dataBuf = ArrayList<Byte>()
        for (k in kinds) {
            for (it in sel[k]!!) {
                val b = it.externalBytes ?: continue
                externals.add(Triple(k, it, dataStart + dataBuf.size))
                dataBuf.addAll(b.toList())
                if (b.size and 1 == 1) dataBuf.add(0)     // 值区按偶对齐
            }
        }

        val buf = ByteArray(dataStart + dataBuf.size)
        System.arraycopy(nef, 0, buf, 0, 8)               // TIFF 头原样带过去
        for (i in dataBuf.indices) buf[dataStart + i] = dataBuf[i]

        // 写目录：tag/type/count 照抄；内联值直接落，外置值的偏移稍后回填
        for (k in kinds) {
            val base = dirAt[k]!!
            val items = sel[k]!!
            put16(buf, base, items.size, little)
            var p = base + 2
            for (it in items) {
                put16(buf, p, it.tag, little)
                put16(buf, p + 2, it.type, little)
                put32(buf, p + 4, it.count.toInt(), little)
                if (it.blob.size <= 4) {
                    System.arraycopy(it.blob, 0, buf, p + 8, it.blob.size)
                    // ⚠️ 写完内联值**不能再写 0 占位** —— 那会把刚写进去的值抹掉
                    // （所有字符串/小数组会读成空，v2 初版就栽在这一行）
                    for (z in it.blob.size until 4) buf[p + 8 + z] = 0
                }
                p += 12
            }
            put32(buf, p, 0, little)                      // next-IFD = 0
        }

        // 回填：外置值偏移
        for ((k, it, at) in externals) {
            val base = dirAt[k]!!
            val idx = sel[k]!!.indexOf(it)
            if (idx >= 0) put32(buf, base + 2 + idx * 12 + 8, at, little)
        }
        // 回填：子 IFD 指针 + 输出图尺寸
        val ifd0Items = sel["ifd0"]
        if (ifd0Items != null) {
            val base = dirAt["ifd0"]!!
            for (i in ifd0Items.indices) {
                val it = ifd0Items[i]
                when (it.tag) {
                    TAG_EXIF_IFD -> dirAt["exif"]?.let { put32(buf, base + 2 + i * 12 + 8, it, little) }
                    TAG_GPS_IFD -> dirAt["gps"]?.let { put32(buf, base + 2 + i * 12 + 8, it, little) }
                    TAG_IMAGE_WIDTH -> if (size != null) writeNum(buf, base + 2 + i * 12 + 8, it, size.first, little)
                    TAG_IMAGE_LENGTH -> if (size != null) writeNum(buf, base + 2 + i * 12 + 8, it, size.second, little)
                }
            }
        }
        val exifItems = sel["exif"]
        if (exifItems != null) {
            val base = dirAt["exif"]!!
            for (i in exifItems.indices) {
                if (exifItems[i].tag == TAG_INTEROP)
                    dirAt["interop"]?.let { put32(buf, base + 2 + i * 12 + 8, it, little) }
            }
        }

        android.util.Log.i(
            "EXIF", "已重建 TIFF ${buf.size}B（APP1 ${buf.size + 6}B）" +
                if (dropped.isEmpty()) "，全部保住" else "，丢掉：${dropped.joinToString(" / ")}"
        )
        return buf
    }

    /** 把尺寸写回 ImageWidth/Length（只支持 count==1 的 SHORT / LONG，D300 是 LONG） */
    private fun writeNum(buf: ByteArray, at: Int, it: Item, v: Int, little: Boolean) {
        if (it.count != 1L) return
        when (it.type) {
            3 -> put16(buf, at, v.coerceAtMost(0xFFFF), little)
            4 -> put32(buf, at, v, little)
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
