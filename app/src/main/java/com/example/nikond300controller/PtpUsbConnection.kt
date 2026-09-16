package com.example.nikond300controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    fun capture(): Boolean = synchronized(usbLock) {
        drain()
        // 0. Nikon AF and Capture (0x9207) - This is the standard "press shutter button" command for Nikon
        android.util.Log.d("PTP_TX", "COMMAND: Nikon AF and Capture (0x9207)")
        var packet = createCommandPacket(PtpConstants.OP_NIKON_AF_AND_CAPTURE, 0xFFFFFFFF.toInt())
        if (sendPacket(packet)) {
            val response = receiveResponseWithRetry(3)
            if (response?.responseCode == PtpConstants.RESP_OK) {
                logger("Capture Triggered (0x9207)")
                return true
            }
            android.util.Log.d("PTP_RX", "Capture 0x9207 failed: 0x${Integer.toHexString(response?.responseCode ?: 0)}")
        }

        // 1. Fallback to standard PTP Initiate Capture (0x100E)
        android.util.Log.d("PTP_TX", "COMMAND: Standard PTP Initiate Capture (0x100E)")
        packet = createCommandPacket(PtpConstants.OP_INITIATE_CAPTURE, 0, 0)
        if (sendPacket(packet)) {
            val response = receiveResponseWithRetry(3)
            if (response?.responseCode == PtpConstants.RESP_OK) {
                logger("Capture Triggered (0x100E)")
                return true
            }
            android.util.Log.d("PTP_RX", "Capture 0x100E failed: 0x${Integer.toHexString(response?.responseCode ?: 0)}")
        }
        
        return false
    }

    fun terminateCapture(): Boolean = synchronized(usbLock) {
        val packet = createCommandPacket(PtpConstants.OP_NIKON_TERMINATE_CAPTURE, 0)
        if (!sendPacket(packet)) return false
        val response = receiveResponseWithRetry()
        return response?.responseCode == PtpConstants.RESP_OK
    }

    fun setDevicePropValue(propCode: Int, value: Int, size: Int = 2, logDesc: String = ""): Boolean = synchronized(usbLock) {
        drain()
        if (logDesc.isNotEmpty()) android.util.Log.d("PTP_TX_CMD", logDesc)
        
        val packet = createCommandPacket(PtpConstants.OP_SET_DEVICE_PROP_VALUE, propCode)
        if (!sendPacket(packet)) {
            logger("Failed to send Command 0x1016 for Prop 0x${Integer.toHexString(propCode)}")
            return false
        }
        
        val valBytes = when (size) {
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
        
        val data = createDataPacket(PtpConstants.OP_SET_DEVICE_PROP_VALUE, valBytes)
        if (!sendPacket(data)) {
            logger("Failed to send Data for Prop 0x${Integer.toHexString(propCode)}")
            return false
        }
        
        val response = receiveResponseWithRetry()
        if (response == null) {
            logger("Timeout waiting for Response (Prop 0x${Integer.toHexString(propCode)})")
            return false
        }
        
        val success = response.responseCode == PtpConstants.RESP_OK
        val resHex = "0x${Integer.toHexString(response.responseCode).uppercase()}"
        logger("Prop 0x${Integer.toHexString(propCode)} set to $value: ${if(success) "Success" else "Failed ($resHex)"}")
        return success
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
                4 -> data.int // Will return -1 for 0xFFFFFFFF (Bulb)
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

        // Send Nikon Device Ready
        val readyPacket = createCommandPacket(PtpConstants.OP_NIKON_DEVICE_READY)
        sendPacket(readyPacket)
        receiveResponseWithRetry()
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
        
        setDevicePropValue(PtpConstants.PROP_NIKON_LIVE_VIEW, 0, size = 2)
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

    fun setShutterBulb(): Boolean = synchronized(usbLock) {
        val bulbVal = 0xFFFFFFFF.toInt()
        
        // Priority 1: Nikon-specific property 0xD100
        // D300 / D700 era cameras often strictly require this for software bulb
        if (setDevicePropValue(PtpConstants.PROP_NIKON_SHUTTER_SPEED, bulbVal, size = 4, logDesc = "Try Nikon Shutter Prop 0xD100")) {
            return true
        }

        // Priority 2: Standard Prop 0x500D
        if (setDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME, bulbVal, size = 4, logDesc = "Try Bulb Standard 0x500D")) {
            return true
        }

        // Priority 3: Scan Enum List
        val supported = getDevicePropSupportedValues(PtpConstants.PROP_EXPOSURE_TIME)
        if (supported != null && supported.isNotEmpty()) {
            val lastValue = supported.last()
            if (lastValue != bulbVal) {
                logger("Found custom bulb value in enum: 0x${Integer.toHexString(lastValue).uppercase()}")
                if (setDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME, lastValue, size = 4, logDesc = "Try Bulb from Enum")) {
                    return true
                }
            }
        }

        return false
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
            return "$manufacturer $model"
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
        return connection.bulkTransfer(ep, bytes, len, 2000) >= 0
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

    private fun receiveResponseWithRetry(retries: Int = 3): PtpResponse? {
        for (i in 0 until retries) {
            val response = receiveResponse()
            if (response != null) {
                if (response.responseCode == PtpConstants.RESP_DEVICE_BUSY) {
                    logger("Device Busy, retrying... (${i + 1}/$retries)")
                    Thread.sleep(300)
                    continue
                }
                return response
            }
            Thread.sleep(200)
        }
        return null
    }

    private fun receiveResponse(): PtpResponse? {
        if (lastResponse != null) {
            val res = lastResponse
            lastResponse = null
            return res
        }
        
        val ep = inEndpoint ?: return null
        val buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        val result = connection.bulkTransfer(ep, buffer.array(), 512, 2000)
        if (result < 12) return null
        val type = buffer.getShort(4).toInt()
        val code = buffer.getShort(6).toInt()
        android.util.Log.d("PTP_RX", "Response Code: 0x${Integer.toHexString(code).uppercase()}")
        return PtpResponse(type, code, buffer.getInt(8))
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
        val response = receiveResponseWithRetry()
        return response?.responseCode == PtpConstants.RESP_OK
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