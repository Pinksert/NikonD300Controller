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

    fun openSession(): Boolean {
        transactionId = 0
        val packet = createCommandPacket(PtpConstants.OP_OPEN_SESSION, 1)
        if (!sendPacket(packet)) return false
        val response = receiveResponseWithRetry()
        return response?.responseCode == PtpConstants.RESP_OK || response?.responseCode == PtpConstants.RESP_SESSION_ALREADY_OPEN
    }

    fun capture(): Int = synchronized(usbLock) {
        drain()
        return executeSingleCaptureAttempt(PtpConstants.OP_NIKON_INITIATE_CAPTURE, 0)
    }

    fun clearPipe() {
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

    fun getDevicePropValue(propCode: Int): Int = synchronized(usbLock) {
        try {
            drain()
            val packet = createCommandPacket(PtpConstants.OP_GET_DEVICE_PROP_VALUE, propCode)
            if (!sendPacket(packet)) return -999999
            
            val data = receiveData()
            if (data == null) return -999999
            
            receiveResponseWithRetry()
            
            data.position(12)
            return when (data.remaining()) {
                1 -> data.get().toInt() and 0xFF
                2 -> data.short.toLong().toInt() and 0xFFFF
                4 -> data.int // Will return -1 for 0xFFFFFFFF
                else -> -999999
            }
        } catch (e: Exception) {
            return -999999
        }
    }

    fun startLiveView(): Boolean {
        logger("Initiating Live View...")
        
        // D300 requires LiveViewControlMode (0x5013) to be set as UINT8 (size = 1)
        // Mode 2 = Tripod, Mode 1 = Hand-held
        if (!setDevicePropValue(PtpConstants.PROP_NIKON_LIVE_VIEW, 2, size = 1)) {
            logger("Tripod mode failed, trying Hand-held...")
            if (!setDevicePropValue(PtpConstants.PROP_NIKON_LIVE_VIEW, 1, size = 1)) {
                logger("Failed to set Live View property")
                return false
            }
        }
        
        Thread.sleep(200)

        // Send Nikon Device Ready (Check 0x90C8)
        deviceReady()
        Thread.sleep(100)

        // Start Live View command
        val packet = createCommandPacket(PtpConstants.OP_NIKON_START_LIVE_VIEW)
        if (!sendPacket(packet)) return false
        val response = receiveResponseWithRetry()
        
        if (response?.responseCode == PtpConstants.RESP_OK) {
            drain()
            Thread.sleep(300)
            return true
        }
        logger("Start Live View failed (0x${Integer.toHexString(response?.responseCode ?: 0)})")
        return false
    }

    fun endLiveView(): Boolean {
        val packet = createCommandPacket(PtpConstants.OP_NIKON_END_LIVE_VIEW)
        sendPacket(packet)
        receiveResponseWithRetry()
        
        // CRITICAL: D300 property 0x5013 MUST be size=1 (UINT8)
        setDevicePropValue(PtpConstants.PROP_NIKON_LIVE_VIEW, 0, size = 1)
        return true
    }

    fun getLiveViewFrame(): Bitmap? {
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

    fun getDevicePropDesc(propCode: Int): ByteArray? {
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
            
            val code = buffer.getShort()
            val dataType = buffer.getShort().toInt() and 0xFFFF
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

    fun getDeviceInfo(): String? {
        drain()
        val packet = createCommandPacket(PtpConstants.OP_GET_DEVICE_INFO)
        if (!sendPacket(packet)) return null
        val data = receiveData() ?: return null
        val response = receiveResponseWithRetry()
        if (response?.responseCode != PtpConstants.RESP_OK) return null
        return parseDeviceInfo(data)
    }

    private fun parseDeviceInfo(buffer: ByteBuffer): String? {
        try {
            buffer.position(12)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.short
            buffer.int
            buffer.short
            skipString(buffer)
            buffer.short
            skipArray(buffer, 2)
            skipArray(buffer, 2)
            skipArray(buffer, 2)
            skipArray(buffer, 2)
            skipArray(buffer, 2)
            val manufacturer = readString(buffer)
            val model = readString(buffer)
            val simpleManufacturer = manufacturer.replace(" Corporation", "")
            return "$simpleManufacturer $model"
        } catch (e: Exception) { return null }
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
        val tempBuffer = ByteArray(maxSize)
        
        var totalRead = connection.bulkTransfer(ep, tempBuffer, maxSize, 5000)
        if (totalRead < 12) return null
        
        val header = ByteBuffer.wrap(tempBuffer).order(ByteOrder.LITTLE_ENDIAN)
        val packetSize = header.getInt(0)
        val type = header.getShort(4).toInt()
        
        if (type == PtpConstants.PACKET_TYPE_RESPONSE) {
            val code = header.getShort(6).toInt()
            lastResponse = PtpResponse(type, code, header.getInt(8))
            return null
        }
        
        if (type != PtpConstants.PACKET_TYPE_DATA) return null
        
        while (totalRead < packetSize && totalRead < maxSize) {
            val read = connection.bulkTransfer(ep, tempBuffer, totalRead, Math.min(maxSize - totalRead, 16384), 2000)
            if (read <= 0) break
            totalRead += read
        }
        
        val result = try {
            val limitVal = Math.min(totalRead, packetSize)
            val resBuf = ByteBuffer.wrap(tempBuffer, 0, limitVal).order(ByteOrder.LITTLE_ENDIAN)
            resBuf.limit(limitVal)
            android.util.Log.d("PTP_RX", "Data Packet: ${bytesToHex(tempBuffer, limitVal)}")
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

    fun drain() {
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


    fun clearEventsThoroughly() {
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