package com.example.nikond300controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

class PtpUsbConnection(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val logger: (String) -> Unit
) {

    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var interruptEndpoint: UsbEndpoint? = null

    private var transactionId = 0
    private var lastResponse: PtpResponse? = null
    
    /**
     * 所有对 [UsbDeviceConnection] 的读写都必须串行化。
     * 之前只有部分方法加锁，轮询线程 / Live View 线程 / 相册线程会并发抢同一个 Bulk IN 端点，
     * 导致响应包被别的线程吃掉、画面撕裂甚至 USB 管道挂死。
     */
    private val usbLock = Any()

    init {
        connection.claimInterface(usbInterface, true)
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    inEndpoint = endpoint
                } else {
                    outEndpoint = endpoint
                }
            } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                interruptEndpoint = endpoint
            }
        }
    }

    fun openSession(): Boolean = synchronized(usbLock) {
        transactionId = 0
        val packet = createCommandPacket(PtpConstants.OP_OPEN_SESSION, 1)
        if (!sendPacket(packet)) return false
        val response = receiveResponseWithRetry()
        return response?.responseCode == PtpConstants.RESP_OK || response?.responseCode == PtpConstants.RESP_SESSION_ALREADY_OPEN
    }

    /** 正式关闭 PTP 会话，避免相机侧 session 泄漏（多次插拔后报 Session Already Open） */
    fun closeSession(): Boolean = synchronized(usbLock) {
        return try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_CLOSE_SESSION)
            if (!sendPacket(packet)) return false
            receiveResponse(1500)?.responseCode == PtpConstants.RESP_OK
        } catch (e: Exception) {
            false
        }
    }

    fun capture(): Int = synchronized(usbLock) {
        drain()
        return executeSingleCaptureAttempt(PtpConstants.OP_INITIATE_CAPTURE, 0)
    }

    fun clearPipe(): Unit = synchronized(usbLock) {
        inEndpoint?.let { connection.clearHalt(it) }
        outEndpoint?.let { connection.clearHalt(it) }
    }

    private fun UsbDeviceConnection.clearHalt(endpoint: UsbEndpoint): Boolean {
        return controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or 0x02, // 0x02 is USB_RECIP_ENDPOINT
            0x01, // CLEAR_FEATURE
            0x00, // ENDPOINT_HALT
            endpoint.address,
            null,
            0,
            1000
        ) >= 0
    }

    private fun executeSingleCaptureAttempt(opCode: Int, vararg params: Int): Int {
        val opName = "0x${Integer.toHexString(opCode).uppercase()}"
        logger("Triggering Capture ($opName)...")
        
        val packet = createCommandPacket(opCode, *params)
        if (!sendPacket(packet)) return -1
        
        val response = receiveResponseWithRetry(5)
        
        if (response == null || response.responseCode == 0xA002 || response.responseCode == 0xA008) {
            val codeHex = if (response != null) "0x${Integer.toHexString(response.responseCode).uppercase()}" else "TIMEOUT"
            logger("Capture Rejected: $codeHex. Rescuing pipe...")
            clearPipe()
            return response?.responseCode ?: -1
        }

        val code = response.responseCode
        if (code == PtpConstants.RESP_OK) {
            logger("Capture Triggered Successfully ($opName)")
            Thread.sleep(300)
            clearEventsThoroughly()
            return code
        } else {
            val codeHex = "0x${Integer.toHexString(code).uppercase()}"
            if (code == PtpConstants.RESP_NIKON_HARDWARE_ERROR) {
                logger("Capture $opName failed: Focus Not Locked ($codeHex)")
            } else {
                logger("Capture $opName failed: $codeHex")
            }
            clearPipe()
            logger("Pipe Rescue: ClearHalt executed on input/output endpoints.")
            return code
        }
    }

    fun setDevicePropValue(propCode: Int, value: Int, size: Int = 2, logDesc: String = ""): Boolean = synchronized(usbLock) {
        val finalSize = if (propCode == PtpConstants.PROP_NIKON_LIVE_VIEW) 1 else size
        val valBytes = when (finalSize) {
            1 -> byteArrayOf(value.toByte())
            2 -> {
                val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                b.putShort(value.toShort())
                b.array()
            }
            4 -> {
                val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                b.putInt(value)
                b.array()
            }
            else -> byteArrayOf(value.toByte())
        }

        val valStr = "0x${Integer.toHexString(value).uppercase()}"
        val bytesHex = bytesToHex(valBytes, valBytes.size)
        android.util.Log.d("PTP_TX_PROP", "Set Prop 0x${Integer.toHexString(propCode).uppercase()} to value $valStr (Hex: $bytesHex)")

        for (i in 0 until 3) {
            drain()
            if (logDesc.isNotEmpty()) android.util.Log.d("PTP_TX_CMD", logDesc)

            val packet = createCommandPacket(PtpConstants.OP_SET_DEVICE_PROP_VALUE, propCode)
            if (!sendPacket(packet)) {
                logger("Failed to send Command 0x1016 for Prop 0x${Integer.toHexString(propCode).uppercase()}")
                return false
            }

            val data = createDataPacket(PtpConstants.OP_SET_DEVICE_PROP_VALUE, valBytes)
            if (!sendPacket(data)) {
                logger("Failed to send Data for Prop 0x${Integer.toHexString(propCode).uppercase()}")
                return false
            }

            val response = receiveResponse(3000)

            if (response?.responseCode == PtpConstants.RESP_OK) {
                logger("Prop 0x${Integer.toHexString(propCode).uppercase()} set to $valStr: Success")
                return true
            }

            if (response == null || response.responseCode == 0xA008 || response.responseCode == PtpConstants.RESP_DEVICE_BUSY) {
                val resHex = if (response != null) "0x${Integer.toHexString(response.responseCode).uppercase()}" else "TIMEOUT"
                logger("Prop 0x${Integer.toHexString(propCode).uppercase()} $resHex. Triggering Recovery Sequence...")
                
                deviceReady()
                drain()
                Thread.sleep(1000)
                
                // Dummy Read to clear bus
                getDevicePropValue(propCode)
                Thread.sleep(500)
                
                continue
            }

            val resHex = "0x${Integer.toHexString(response.responseCode).uppercase()}"
            logger("Prop 0x${Integer.toHexString(propCode).uppercase()} set to $valStr: Failed ($resHex)")
            return false
        }
        return false
    }

    /**
     * 属性读取的完整现场。
     * [responseCode] 是 PTP 响应码（0x200A=不支持、0x2019=相机忙…），
     * 之前调用方只能拿到一个 -999999，完全无法区分「相机不支持」和「传输失败」。
     */
    data class PropReadResult(
        val value: Int,
        val responseCode: Int,
        val payloadSize: Int,
        val transportOk: Boolean
    )

    fun getDevicePropValue(propCode: Int): Int = readDeviceProp(propCode).value

    fun readDeviceProp(propCode: Int): PropReadResult = synchronized(usbLock) {
        try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_GET_DEVICE_PROP_VALUE, propCode)
            if (!sendPacket(packet)) return PropReadResult(-999999, 0, -1, false)

            val data = receiveData()

            // 无论有没有数据，都必须把响应消费掉：
            // 否则残留的 lastResponse 会被下一条事务当成自己的响应，造成连锁错乱。
            val response = receiveResponseWithRetry()

            if (data == null) {
                return PropReadResult(-999999, response?.responseCode ?: 0, -1, false)
            }

            data.position(12)
            val payload = data.remaining()
            val value = when (payload) {
                1 -> data.get().toInt() and 0xFF
                2 -> data.short.toLong().toInt() and 0xFFFF
                4 -> data.int
                else -> -999999
            }
            return PropReadResult(value, response?.responseCode ?: 0, payload, true)
        } catch (e: Exception) {
            return PropReadResult(-999999, 0, -1, false)
        }
    }

    fun startLiveView(): Boolean = synchronized(usbLock) {
        logger("步骤 1: 发送 0x9201 启动 LV...")
        
        val packet = createCommandPacket(PtpConstants.OP_NIKON_START_LIVE_VIEW)
        if (!sendPacket(packet)) return false
        val response = receiveResponseWithRetry()
        
        val code = response?.responseCode ?: 0
        if (code == PtpConstants.RESP_OK) {
            drain()
            Thread.sleep(200)
            return true
        }
        
        val codeHex = "0x${Integer.toHexString(code).uppercase()}"
        if (code == 0xA004) {
            logger("提示: 尼康 D300 必须将相机机身左上方顶部的【快门释放模式拨盘】物理旋转切换到 [Lv] 档位，且保证电量充足，才能激活实时取景！")
        }
        logger("Start Live View failed ($codeHex)")
        return false
    }

    fun endLiveView(): Boolean = synchronized(usbLock) {
        logger("步骤 3: 发送 0x9202 关闭 LV 释放反光板...")
        val packet = createCommandPacket(PtpConstants.OP_NIKON_END_LIVE_VIEW)
        sendPacket(packet)
        receiveResponseWithRetry()
        return true
    }

    fun getLiveViewFrame(): Bitmap? = synchronized(usbLock) {
        val packet = createCommandPacket(PtpConstants.OP_NIKON_GET_LIVE_VIEW_IMAGE)
        if (!sendPacket(packet)) return null
        val data = receiveData(512 * 1024) ?: return null
        receiveResponseWithRetry()
        
        val array = data.array()
        val limit = data.limit()
        
        // Search for JPEG SOI marker (FF D8)
        var offset = 12
        while (offset < limit - 1) {
            if (array[offset] == 0xFF.toByte() && array[offset+1] == 0xD8.toByte()) {
                break
            }
            offset++
        }
        
        if (offset >= limit - 1) {
            // logger("JPEG SOI not found in LV frame")
            return null
        }
        
        return try {
            BitmapFactory.decodeByteArray(array, offset, limit - offset)
        } catch (e: Exception) { null }
    }

    fun getThumb(objectHandle: Int): Bitmap? = synchronized(usbLock) {
        try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_GET_THUMB, objectHandle)
            if (!sendPacket(packet)) return null
            val data = receiveData(128 * 1024) ?: return null
            receiveResponseWithRetry()
            
            val array = data.array()
            val limit = data.limit()
            var offset = 12
            while (offset < limit - 1) {
                if (array[offset] == 0xFF.toByte() && array[offset+1] == 0xD8.toByte()) {
                    break
                }
                offset++
            }
            if (offset >= limit - 1) return null
            return BitmapFactory.decodeByteArray(array, offset, limit - offset)
        } catch (e: Exception) {
            return null
        }
    }

    fun getObjectData(objectHandle: Int): ByteArray? = synchronized(usbLock) {
        try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_GET_OBJECT, objectHandle)
            if (!sendPacket(packet)) return null
            
            // Allow up to 32MB for full size photo download transfer buffer
            val data = receiveData(32 * 1024 * 1024) ?: return null
            receiveResponseWithRetry()
            
            val limit = data.limit()
            if (limit <= 12) return null
            return data.array().copyOfRange(12, limit)
        } catch (e: Exception) {
            return null
        }
    }

    fun getObjectHandles(): IntArray? = synchronized(usbLock) {
        drain()
        android.util.Log.d("PTP_TX", "COMMAND: Get Object Handles (0x1007)")
        val packet = createCommandPacket(PtpConstants.OP_GET_OBJECT_HANDLES, 0xFFFFFFFF.toInt(), 0, 0)
        if (!sendPacket(packet)) return null
        
        val data = receiveData(256 * 1024) 
        if (data == null) {
            android.util.Log.e("PTP_RX", "Get Object Handles: No data received")
            receiveResponseWithRetry(1)
            return null
        }
        
        val response = receiveResponseWithRetry(1)
        if (response?.responseCode != PtpConstants.RESP_OK) {
            android.util.Log.e("PTP_RX", "Get Object Handles: Failed with 0x${Integer.toHexString(response?.responseCode ?: 0)}")
            return null
        }
        
        try {
            data.position(12)
            if (data.remaining() < 4) return IntArray(0)
            
            val count = data.int
            val actualCount = Math.min(count, data.remaining() / 4)
            val handles = IntArray(actualCount)
            for (i in 0 until actualCount) handles[i] = data.int
            return handles
        } catch (e: Exception) {
            return null
        }
    }

    fun getDevicePropDesc(propCode: Int): ByteArray? = synchronized(usbLock) {
        try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_GET_DEVICE_PROP_DESC, propCode)
            if (!sendPacket(packet)) return null
            val data = receiveData(16384) ?: return null
            receiveResponseWithRetry()
            
            val limit = data.limit()
            if (limit < 12) return null
            return data.array().copyOfRange(0, limit)
        } catch (e: Exception) {
            return null
        }
    }

    fun getDevicePropSupportedValues(propCode: Int): IntArray? = synchronized(usbLock) {
        val raw = getDevicePropDesc(propCode) ?: return null
        try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(12) // Skip PTP Header
            
            // DevicePropCode 通常是 16 位，但尼康部分机身在这里填的是 32 位（0x0001Dxxx）。
            // 先读一个字，只有确认它确实是目标属性码才继续，否则整体偏移会错 2 字节，后面全是垃圾。
            val firstWord = buffer.getShort().toInt() and 0xFFFF
            val secondWord = buffer.getShort().toInt() and 0xFFFF
            val dataType = when {
                firstWord == (propCode and 0xFFFF) -> secondWord
                firstWord == 0x0001 && secondWord == (propCode and 0xFFFF) -> buffer.getShort().toInt() and 0xFFFF
                else -> return null // 结构对不上，宁可返回 null 也不要解析出错误列表
            }
            val getSet = buffer.get()
            
            val typeSize = when (dataType) {
                0x0001, 0x0002 -> 1 // INT8, UINT8
                0x0003, 0x0004 -> 2 // INT16, UINT16
                0x0005, 0x0006 -> 4 // INT32, UINT32
                else -> 2
            }
            
            // Skip FactoryDefault and CurrentValue
            buffer.position(buffer.position() + (typeSize * 2))
            
            val formFlag = buffer.get().toInt()
            if (formFlag == 2) { // Enumeration
                val count = buffer.getShort().toInt() and 0xFFFF
                val values = IntArray(count)
                for (i in 0 until count) {
                    values[i] = when (typeSize) {
                        1 -> buffer.get().toInt() and 0xFF
                        2 -> buffer.getShort().toInt() and 0xFFFF
                        4 -> buffer.getInt()
                        else -> 0
                    }
                }
                return values
            }
        } catch (e: Exception) {}
        return null
    }

    fun getDeviceInfo(): String? = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_GET_DEVICE_INFO)
        if (!sendPacket(packet)) return null
        val data = receiveData(16384) ?: return null
        val response = receiveResponseWithRetry()
        if (response?.responseCode != PtpConstants.RESP_OK) return null
        return parseDeviceInfo(data).first
    }

    /**
     * 返回「设备名 + 相机在 GetDeviceInfo 中声明支持的设备属性码列表」。
     * 用于确认某个属性（例如曝光程序模式 0x500E）在这台机身上到底支不支持，
     * 而不是靠猜。
     */
    fun readDeviceInfoWithProps(): Pair<String?, IntArray> = synchronized(usbLock) {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_GET_DEVICE_INFO)
        if (!sendPacket(packet)) return (null to IntArray(0))
        val data = receiveData(16384) ?: return (null to IntArray(0))
        val response = receiveResponseWithRetry()
        if (response?.responseCode != PtpConstants.RESP_OK) return (null to IntArray(0))
        return parseDeviceInfo(data)
    }

    private fun parseDeviceInfo(buffer: ByteBuffer): Pair<String?, IntArray> {
        try {
            buffer.position(12)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.short
            buffer.int
            buffer.short
            skipString(buffer)
            buffer.short
            skipArray(buffer, 2)      // Supported Operations
            skipArray(buffer, 2)      // Supported Events
            val deviceProps = readU16Array(buffer) // Supported Device Properties
            skipArray(buffer, 2)      // Capture Formats
            skipArray(buffer, 2)      // Image Formats
            val manufacturer = readString(buffer)
            val model = readString(buffer)
            val simpleManufacturer = manufacturer.replace(" Corporation", "")
            return ("$simpleManufacturer $model" to deviceProps)
        } catch (e: Exception) {
            return (null to IntArray(0))
        }
    }

    private fun readU16Array(buffer: ByteBuffer): IntArray {
        val count = buffer.getInt()
        val values = IntArray(count)
        for (i in 0 until count) values[i] = buffer.getShort().toInt() and 0xFFFF
        return values
    }

    private fun readString(buffer: ByteBuffer): String {
        val len = buffer.get().toInt()
        if (len == 0) return ""
        val chars = CharArray(len - 1)
        for (i in 0 until len - 1) chars[i] = buffer.getShort().toInt().toChar()
        buffer.getShort()
        return String(chars)
    }

    private fun skipString(buffer: ByteBuffer) {
        val len = buffer.get().toInt()
        if (len > 0) buffer.position(buffer.position() + (len * 2))
    }

    private fun skipArray(buffer: ByteBuffer, elementSize: Int) {
        val count = buffer.getInt()
        buffer.position(buffer.position() + (count * elementSize))
    }

    private fun createCommandPacket(opCode: Int, vararg params: Int): ByteBuffer {
        val size = 12 + (params.size * 4)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(size)
        buffer.putShort(PtpConstants.PACKET_TYPE_COMMAND.toShort())
        buffer.putShort(opCode.toShort())
        buffer.putInt(transactionId++)
        for (param in params) buffer.putInt(param)
        return buffer
    }

    private fun createDataPacket(opCode: Int, data: ByteArray): ByteBuffer {
        val size = 12 + data.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(size)
        buffer.putShort(PtpConstants.PACKET_TYPE_DATA.toShort())
        buffer.putShort(opCode.toShort())
        buffer.putInt(transactionId - 1)
        buffer.put(data)
        return buffer
    }

    private fun sendPacket(buffer: ByteBuffer): Boolean {
        val ep = outEndpoint ?: return false
        val bytes = buffer.array()
        val len = buffer.limit()
        android.util.Log.d("PTP_TX", "Packet: ${bytesToHex(bytes, len)}")
        val result = connection.bulkTransfer(ep, bytes, len, 2000)
        if (result < 0) {
             android.util.Log.e("PTP_USB", "Write failed: result=$result. Device may have disconnected.")
             return false
        }
        return true
    }

    private fun receiveData(maxSize: Int = 4096): ByteBuffer? {
        val ep = inEndpoint ?: return null
        
        // Read the first chunk (up to 16KB) to safely parse the PTP header packet size
        val chunkBuf = ByteArray(16384)
        val initialRead = connection.bulkTransfer(ep, chunkBuf, chunkBuf.size, 5000)
        if (initialRead < 12) return null
        
        val header = ByteBuffer.wrap(chunkBuf).order(ByteOrder.LITTLE_ENDIAN)
        val packetSize = header.getInt(0)
        val type = header.getShort(4).toInt()
        
        if (type == PtpConstants.PACKET_TYPE_RESPONSE) {
            val code = header.getShort(6).toInt()
            lastResponse = PtpResponse(type, code, header.getInt(8))
            return null
        }
        
        if (type != PtpConstants.PACKET_TYPE_DATA) return null
        
        // Prevent excessive allocations or invalid packet sizes
        if (packetSize > 64 * 1024 * 1024 || packetSize < 12) return null
        
        val fullBuffer = ByteArray(packetSize)
        System.arraycopy(chunkBuf, 0, fullBuffer, 0, Math.min(initialRead, packetSize))
        
        var totalRead = Math.min(initialRead, packetSize)
        while (totalRead < packetSize) {
            // Nikon D300 sends data in max packet bounds. 
            // For large objects (RAW/JPEG), let's read up to 128KB chunks to prevent endpoint timeouts and rescue loop chokes
            val toRead = Math.min(packetSize - totalRead, 131072)
            val read = connection.bulkTransfer(ep, fullBuffer, totalRead, toRead, 10000)
            if (read <= 0) {
                // If a chunk read times out, clear pipe haltage and attempt a single rescue retry before dropping completely
                android.util.Log.w("PTP_USB", "Chunk read empty or timed out at position $totalRead. Rescuing pipe...")
                inEndpoint?.let { connection.clearHalt(it) }
                Thread.sleep(100)
                val retryRead = connection.bulkTransfer(ep, fullBuffer, totalRead, toRead, 5000)
                if (retryRead <= 0) break
                totalRead += retryRead
            } else {
                totalRead += read
            }
        }
        
        val result = try {
            val resBuf = ByteBuffer.wrap(fullBuffer, 0, totalRead).order(ByteOrder.LITTLE_ENDIAN)
            resBuf.limit(totalRead)
            android.util.Log.d("PTP_RX", "Data Packet: Length=$totalRead, Expected=$packetSize")
            resBuf
        } catch (e: Exception) { null }
        return result
    }

    private fun receiveResponseWithRetry(retries: Int = 3, retryDelayMs: Long = 800, timeoutMs: Int = 2000): PtpResponse? {
        // Simplified: return response immediately even if Busy/Timeout
        // so higher level transaction retry can take over if needed.
        return receiveResponse(timeoutMs)
    }

    private fun receiveResponse(timeoutMs: Int = 2000): PtpResponse? {
        if (lastResponse != null) {
            val res = lastResponse
            lastResponse = null
            return res
        }
        
        val ep = inEndpoint ?: return null
        val buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        
        val start = System.currentTimeMillis()
        for (i in 0 until 5) {
            Arrays.fill(buffer.array(), 0.toByte())
            buffer.clear()

            val result = connection.bulkTransfer(ep, buffer.array(), 512, timeoutMs)
            val elapsed = System.currentTimeMillis() - start
            
            if (result < 12) {
                 if (result > 0) android.util.Log.w("PTP_RX", "Short packet: $result bytes after ${elapsed}ms")
                 else if (result < 0) android.util.Log.w("PTP_RX", "Bulk transfer error: $result after ${elapsed}ms")
                 return null
            }

            // Robustly parse the 12-byte header
            val length = buffer.getInt(0)
            val type = buffer.getShort(4).toInt() and 0xFFFF
            val code = buffer.getShort(6).toInt() and 0xFFFF
            val transactionId = buffer.getInt(8)

            if (length < 12 || length > 1024 * 1024) {
                android.util.Log.e("PTP_RX", "Invalid PTP packet length: $length")
                return null
            }

            if (type == PtpConstants.PACKET_TYPE_RESPONSE) {
                android.util.Log.d("PTP_RX", "Response 0x${Integer.toHexString(code).uppercase()} in ${elapsed}ms")
                return PtpResponse(type, code, transactionId)
            } else if (type == PtpConstants.PACKET_TYPE_EVENT) {
                android.util.Log.d("PTP_RX", "Event 0x${Integer.toHexString(code).uppercase()} received at ${elapsed}ms")
            } else {
                android.util.Log.w("PTP_RX", "Type 0x${Integer.toHexString(type).uppercase()} packet at ${elapsed}ms")
                return PtpResponse(type, code, transactionId)
            }
        }
        android.util.Log.w("PTP_RX", "Response loop exhausted without receiving a valid Response packet.")
        return null
    }

    fun drain(): Unit = synchronized(usbLock) {
        if (inEndpoint == null) return
        val buffer = ByteArray(4096)
        var drainedCount = 0
        while (connection.bulkTransfer(inEndpoint, buffer, 4096, 15) > 0) {
            drainedCount++
            if (drainedCount > 30) break
        }
        lastResponse = null
    }

    fun deviceReady(): Boolean = synchronized(usbLock) {
        val packet = createCommandPacket(PtpConstants.OP_NIKON_DEVICE_READY)
        if (!sendPacket(packet)) return false
        val response = receiveResponse()
        
        // Clear events every time we check ready to keep camera responsive
        clearEventsThoroughly()
        
        return response?.responseCode == PtpConstants.RESP_OK
    }

    fun afDrive(): Boolean = synchronized(usbLock) {
        val packet = createCommandPacket(PtpConstants.OP_NIKON_AF_DRIVE)
        if (!sendPacket(packet)) return false
        val response = receiveResponse(5000)
        return response?.responseCode == PtpConstants.RESP_OK
    }


    /** 从相机存储卡删除指定对象（照片） */
    fun deleteObject(objectHandle: Int): Boolean = synchronized(usbLock) {
        return try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_DELETE_OBJECT, objectHandle)
            if (!sendPacket(packet)) return false
            val response = receiveResponse(8000)
            clearEventsThoroughly()
            response?.responseCode == PtpConstants.RESP_OK
        } catch (e: Exception) {
            false
        }
    }

    /** 读取对象的文件名与原始大小，用于相册展示与保存到相册时的文件命名 */
    fun getObjectInfo(objectHandle: Int): Pair<String, Long>? = synchronized(usbLock) {
        return try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_GET_OBJECT_INFO, objectHandle)
            if (!sendPacket(packet)) return null
            val data = receiveData(8192)
            receiveResponseWithRetry()
            if (data == null) return null
            data.order(ByteOrder.LITTLE_ENDIAN)
            data.position(12)
            data.int                       // StorageID
            data.short                     // ObjectFormat
            data.short                     // ProtectionStatus
            val size = data.int.toLong() and 0xFFFFFFFFL // ObjectCompressedSize
            // Fixed payload layout: filename string starts at byte 52 of the ObjectInfo dataset
            data.position(12 + 52)
            val fileName = readString(data)
            if (fileName.isBlank()) null else (fileName to size)
        } catch (e: Exception) {
            null
        }
    }

    fun clearEventsThoroughly(): Unit = synchronized(usbLock) {
        // Efficient event clearing without forced sleeps between iterations
        for (i in 0 until 10) {
            val packet = createCommandPacket(PtpConstants.OP_NIKON_GET_EVENT)
            if (sendPacket(packet)) {
                val data = receiveData(2048) // Short timeout inside receiveData
                receiveResponse()
                // If no data returned, queue is likely empty
                if (data == null || data.limit() <= 12) break 
            } else break
        }
    }

    fun close() {
        // 先体面地关闭会话（0x1003），再释放 USB 接口
        try { closeSession() } catch (e: Exception) {}
        try {
            connection.releaseInterface(usbInterface)
            connection.close()
        } catch (e: Exception) {}
    }

    private fun bytesToHex(bytes: ByteArray, len: Int): String {
        val hexChars = CharArray(len * 2)
        val hexArray = "0123456789ABCDEF".toCharArray()
        for (i in 0 until len) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    data class PtpResponse(val type: Int, val responseCode: Int, val transactionId: Int)
}