package com.example.nikond300controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var propText: TextView
    private lateinit var connectButton: Button
    private lateinit var captureButton: Button
    private lateinit var modeButton: Button
    private lateinit var liveViewButton: Button
    private lateinit var isoButton: Button
    private lateinit var apertureButton: Button
    private lateinit var shutterButton: Button
    private lateinit var bulbToolButton: Button
    private lateinit var apBracketingButton: Button
    private lateinit var expBracketingButton: Button
    
    private lateinit var isoUpButton: Button
    private lateinit var isoDownButton: Button
    private lateinit var apertureUpButton: Button
    private lateinit var apertureDownButton: Button
    private lateinit var shutterUpButton: Button
    private lateinit var shutterDownButton: Button

    // Current internal selection indices for arrow adjustment
    private var currentIsoIndex = 0
    private var currentApIndex = 0
    private var currentShIndex = 0

    private val isos = arrayOf(
        100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400
    )
    
    // Fallback full list of standard apertures. We will filter this array dynamically based on camera's actual capability descriptors if possible,
    // or safely check boundaries during direction-pad selection.
    private val apertures = arrayOf(
        140 to "f/1.4", 180 to "f/1.8", 200 to "f/2.0", 220 to "f/2.2", 250 to "f/2.5",
        280 to "f/2.8", 320 to "f/3.2", 350 to "f/3.5", 400 to "f/4.0",
        450 to "f/4.5", 500 to "f/5.0", 560 to "f/5.6", 630 to "f/6.3",
        710 to "f/7.1", 800 to "f/8.0", 900 to "f/9.0", 1000 to "f/10",
        1100 to "f/11", 1300 to "f/13", 1400 to "f/14", 1600 to "f/16",
        1800 to "f/18", 2000 to "f/20", 2200 to "f/22", 2500 to "f/25",
        2900 to "f/29", 3200 to "f/32"
    )

    private var dynamicApertures: Array<Pair<Int, String>> = apertures
    private var isPollingActive = false

    private val shutters = arrayOf(
        1 to "1/8000", 125.toByte().toInt() to "1/6400", 156.toByte().toInt() to "1/5000",
        2 to "1/4000", 3 to "1/3200", 4 to "1/2500",
        5 to "1/2000", 6 to "1/1600", 8 to "1/1250",
        10 to "1/1000", 12 to "1/800", 15 to "1/640",
        20 to "1/500", 25 to "1/400", 31 to "1/320",
        40 to "1/250", 50 to "1/200", 62 to "1/160",
        80 to "1/125", 100 to "1/100", 125 to "1/80",
        166 to "1/60", 200 to "1/50", 250 to "1/40",
        333 to "1/30", 400 to "1/25", 500 to "1/20",
        666 to "1/15", 769 to "1/13", 1000 to "1/10",
        1250 to "1/8", 1666 to "1/6", 2000 to "1/5",
        2500 to "1/4", 3333 to "1/3", 4000 to "1/2.5",
        5000 to "1/2", 6250 to "1/1.6", 7692 to "1/1.3",
        10000 to "1s", 13000 to "1.3s", 16000 to "1.6s",
        20000 to "2s", 25000 to "2.5s", 30000 to "3s",
        40000 to "4s", 50000 to "5s", 60000 to "6s",
        80000 to "8s", 100000 to "10s", 130000 to "13s",
        150000 to "15s", 200000 to "20s", 250000 to "25s",
        300000 to "30s", 0xFFFFFFFF.toInt() to "Bulb"
    )

    private var ptpConnection: PtpUsbConnection? = null
    private var isLiveViewRunning = false
    private var isConnected = false

    private var bulbJob: Job? = null
    private var bulbSheet: BottomSheetDialog? = null

    private val ACTION_USB_PERMISSION = "com.example.nikond300controller.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply { connectToDevice(this) }
                    } else {
                        log("Permission denied")
                        connectButton.isEnabled = true
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                log("USB Device Detached")
                disconnect()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)
        propText = findViewById(R.id.propText)
        connectButton = findViewById(R.id.connectButton)
        captureButton = findViewById(R.id.captureButton)
        modeButton = findViewById(R.id.modeButton)
        liveViewButton = findViewById(R.id.liveViewButton)
        isoButton = findViewById(R.id.isoButton)
        apertureButton = findViewById(R.id.apertureButton)
        shutterButton = findViewById(R.id.shutterButton)
        bulbToolButton = findViewById(R.id.bulbButton)
        apBracketingButton = findViewById(R.id.apBracketingButton)
        expBracketingButton = findViewById(R.id.expBracketingButton)
        
        isoUpButton = findViewById(R.id.isoUpButton)
        isoDownButton = findViewById(R.id.isoDownButton)
        apertureUpButton = findViewById(R.id.apertureUpButton)
        apertureDownButton = findViewById(R.id.apertureDownButton)
        shutterUpButton = findViewById(R.id.shutterUpButton)
        shutterDownButton = findViewById(R.id.shutterDownButton)

        connectButton.setOnClickListener {
            if (isConnected) disconnect() else findAndConnect()
        }

        captureButton.setOnClickListener {
            thread {
                try {
                    // 1. Auto-end Live View if running (Critical for Nikon DSLRs)
                    if (isLiveViewRunning) {
                        log("Closing LV for capture...")
                        stopLiveView()
                        Thread.sleep(800)
                    }

                    log("Firing shutter...")
                    val success = ptpConnection?.capture() ?: false
                    runOnUiThread { 
                        log("Capture: ${if (success) "Success" else "Failed"}")
                        if (!success) {
                            android.widget.Toast.makeText(this@MainActivity, "Capture Failed (Check Logcat)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (success) {
                        Thread.sleep(1500)
                        updateProperties()
                    }
                } catch (e: Exception) { runOnUiThread { log("Capture Error: ${e.message}") } }
            }
        }

        modeButton.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "M (Manual)")
            popup.menu.add(0, 2, 1, "P (Program)")
            popup.menu.add(0, 3, 2, "A (Aperture Priority)")
            popup.menu.add(0, 4, 3, "S (Shutter Priority)")
            popup.setOnMenuItemClickListener { item ->
                val selectedMode = item.itemId
                val modeTitle = if (item.title != null) item.title.toString() else "Unknown"
                thread {
                    // Stop Live View if running before mode change
                    val wasLiveViewRunning = isLiveViewRunning
                    if (wasLiveViewRunning) {
                        stopLiveView()
                        Thread.sleep(500)
                    }

                    log("Setting mode to $modeTitle...")
                    try {
                        val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, selectedMode, size = 2) ?: false
                        runOnUiThread {
                            if (success) {
                                log("Mode changed successfully")
                            } else {
                                log("Failed to change mode (Nikon D300 exposure mode is controlled by physical dial and cannot be changed via software)")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { log("Mode set error: ${e.message}") }
                    }
                    
                    Thread.sleep(500)
                    updateProperties()

                    // Restart Live View if it was running
                    if (wasLiveViewRunning) {
                        Thread.sleep(500)
                        startLiveView()
                    }
                }
                true
            }
            popup.show()
        }

        isoButton.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            isos.forEachIndexed { index, isoValue ->
                popup.menu.add(0, index, index, "ISO $isoValue")
            }
            popup.setOnMenuItemClickListener { item ->
                val itemId = item.itemId
                if (itemId >= 0 && itemId < isos.size) {
                    currentIsoIndex = itemId
                    setIso(isos[itemId])
                }
                true
            }
            popup.show()
        }
        
        isoUpButton.setOnClickListener {
            if (currentIsoIndex < isos.size - 1) {
                currentIsoIndex++
                setIso(isos[currentIsoIndex])
            }
        }
        
        isoDownButton.setOnClickListener {
            if (currentIsoIndex > 0) {
                currentIsoIndex--
                setIso(isos[currentIsoIndex])
            }
        }

        apertureButton.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            dynamicApertures.forEachIndexed { index, pair ->
                popup.menu.add(0, index, index, pair.second)
            }
            popup.setOnMenuItemClickListener { item ->
                val itemId = item.itemId
                if (itemId >= 0 && itemId < dynamicApertures.size) {
                    currentApIndex = itemId
                    setAperture(dynamicApertures[itemId].first, dynamicApertures[itemId].second)
                }
                true
            }
            popup.show()
        }
        
        apertureUpButton.setOnClickListener {
            // Up button should decrease numerical aperture value (Larger lens aperture, e.g., f/4 -> f/2.8)
            if (currentApIndex > 0) {
                currentApIndex--
                setAperture(dynamicApertures[currentApIndex].first, dynamicApertures[currentApIndex].second)
            }
        }
        
        apertureDownButton.setOnClickListener {
            // Down button should increase numerical aperture value (Smaller lens aperture, e.g., f/4 -> f/5.6)
            if (currentApIndex < dynamicApertures.size - 1) {
                currentApIndex++
                setAperture(dynamicApertures[currentApIndex].first, dynamicApertures[currentApIndex].second)
            }
        }

        shutterButton.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            shutters.forEachIndexed { index, pair ->
                popup.menu.add(0, index, index, pair.second)
            }
            popup.setOnMenuItemClickListener { item ->
                val itemId = item.itemId
                if (itemId >= 0 && itemId < shutters.size) {
                    currentShIndex = itemId
                    setShutter(shutters[itemId].first, shutters[itemId].second)
                }
                true
            }
            popup.show()
        }
        
        shutterUpButton.setOnClickListener {
            if (currentShIndex > 0) { // Up for faster shutter
                currentShIndex--
                setShutter(shutters[currentShIndex].first, shutters[currentShIndex].second)
            }
        }
        
        shutterDownButton.setOnClickListener {
            if (currentShIndex < shutters.size - 1) { // Down for slower shutter
                currentShIndex++
                setShutter(shutters[currentShIndex].first, shutters[currentShIndex].second)
            }
        }

        liveViewButton.setOnClickListener {
            if (isLiveViewRunning) stopLiveView() else startLiveView()
        }

        bulbToolButton.setOnClickListener {
            showBulbBottomSheet()
        }

        apBracketingButton.setOnClickListener {
            showBracketingDialog()
        }

        expBracketingButton.setOnClickListener {
            showExpBracketingDialog()
        }

        updateButtonStates()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun updateButtonStates() {
        runOnUiThread {
            connectButton.text = if (isConnected) "Disconnect" else "Connect Camera"
            captureButton.isEnabled = isConnected
            modeButton.isEnabled = isConnected
            isoButton.isEnabled = isConnected
            apertureButton.isEnabled = isConnected
            shutterButton.isEnabled = isConnected
            liveViewButton.isEnabled = isConnected
            bulbToolButton.isEnabled = isConnected
            apBracketingButton.isEnabled = isConnected
            expBracketingButton.isEnabled = isConnected
            
            isoUpButton.isEnabled = isConnected
            isoDownButton.isEnabled = isConnected
            apertureUpButton.isEnabled = isConnected
            apertureDownButton.isEnabled = isConnected
            shutterUpButton.isEnabled = isConnected
            shutterDownButton.isEnabled = isConnected
        }
    }

    private fun startLiveView() {
        thread {
            try {
                if (ptpConnection?.startLiveView() == true) {
                    isLiveViewRunning = true
                    runOnUiThread { 
                        liveViewButton.text = "Stop LV"
                        log("Live View active")
                    }
                    while (isLiveViewRunning) {
                        // In background but not updating UI as display is removed
                        ptpConnection?.getLiveViewFrame()
                        Thread.sleep(100)
                    }
                } else {
                    runOnUiThread { log("Live View activation failed") }
                }
            } catch (e: Exception) {
                runOnUiThread { log("LV Error: ${e.message}") }
                isLiveViewRunning = false
            }
        }
    }

    private fun stopLiveView() {
        isLiveViewRunning = false
        thread {
            try {
                ptpConnection?.endLiveView()
                runOnUiThread { 
                    liveViewButton.text = "LV"
                    log("Live View off")
                }
            } catch (e: Exception) { runOnUiThread { log("Stop LV Error: ${e.message}") } }
        }
    }

    private fun updateProperties() {
        thread {
            try {
                // Shortened delays for faster UI response
                Thread.sleep(100)
                val iso = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_INDEX) ?: -999999
                Thread.sleep(50)
                val aperture = ptpConnection?.getDevicePropValue(PtpConstants.PROP_F_NUMBER) ?: -999999
                Thread.sleep(50)
                val shutter = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME) ?: -999999
                Thread.sleep(50)
                val mode = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE) ?: -999999
                Thread.sleep(50)
                val focalLengthRaw = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCAL_LENGTH) ?: -999999
                Thread.sleep(50)
                val biasRaw = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_BIAS_COMPENSATION) ?: -999999
                Thread.sleep(50)
                val supportedAps = ptpConnection?.getDevicePropSupportedValues(PtpConstants.PROP_F_NUMBER)
                
                runOnUiThread {
                    val modeStr = when (mode) {
                        1 -> "M"
                        2 -> "P"
                        3 -> "A"
                        4 -> "S"
                        else -> "--"
                    }
                    val apStr = if (aperture != -999999) "f/${aperture/100.0}" else "--"
                    val shStr = if (shutter != -999999) {
                        when (shutter) {
                            1 -> "1/8000"
                            125.toByte().toInt() -> "1/6400"
                            156.toByte().toInt() -> "1/5000"
                            2 -> "1/4000"
                            3 -> "1/3200"
                            4 -> "1/2500"
                            5 -> "1/2000"
                            6 -> "1/1600"
                            8 -> "1/1250"
                            10 -> "1/1000"
                            12 -> "1/800"
                            15 -> "1/640"
                            20 -> "1/500"
                            25 -> "1/400"
                            31 -> "1/320"
                            40 -> "1/250"
                            50 -> "1/200"
                            62 -> "1/160"
                            80 -> "1/125"
                            100 -> "1/100"
                            125 -> "1/80"
                            166 -> "1/60"
                            200 -> "1/50"
                            250 -> "1/40"
                            333 -> "1/30"
                            400 -> "1/25"
                            500 -> "1/20"
                            666 -> "1/15"
                            769 -> "1/13"
                            1000 -> "1/10"
                            1250 -> "1/8"
                            1666 -> "1/6"
                            2000 -> "1/5"
                            2500 -> "1/4"
                            3333 -> "1/3"
                            4000 -> "1/2.5"
                            5000 -> "1/2"
                            6250 -> "1/1.6"
                            7692 -> "1/1.3"
                            10000 -> "1s"
                            13000 -> "1.3s"
                            16000 -> "1.6s"
                            20000 -> "2s"
                            25000 -> "2.5s"
                            30000 -> "3s"
                            40000 -> "4s"
                            50000 -> "5s"
                            60000 -> "6s"
                            80000 -> "8s"
                            100000 -> "10s"
                            130000 -> "13s"
                            150000 -> "15s"
                            200000 -> "20s"
                            250000 -> "25s"
                            300000 -> "30s"
                            0xFFFFFFFF.toInt() -> "Bulb"
                            else -> {
                                if (shutter in 1..9999) "1/${Math.round(10000.0 / shutter)}"
                                else if (shutter >= 10000) "${shutter / 10000}s"
                                else "Bulb" // Fallback for 0xFFFFFFFF
                            }
                        }
                    } else "--"
                    
                    val focalStr = if (focalLengthRaw != -999999 && focalLengthRaw > 0) "${focalLengthRaw / 100}mm" else "--"
                    
                    val biasStr = if (biasRaw != -999999) {
                        val b = biasRaw.toShort() // PTP Bias is often 16-bit signed
                        val ev = b / 1000.0
                        if (ev > 0) "+%.1f".format(ev) else "%.1f".format(ev)
                    } else "--"
                    
                    // Update button texts with actual values (Shortened to fit small screens)
                    isoButton.text = if (iso != -999999 && iso > 0) "$iso" else "ISO"
                    apertureButton.text = apStr
                    shutterButton.text = shStr
                    
                    // Update main info text with Mode, Focal Length and Bias
                    propText.text = "Mode: $modeStr | Focal: $focalStr | Bias: $biasStr"
                    
                    // Update dynamic aperture list if camera provided descriptor
                    if (supportedAps != null && supportedAps.isNotEmpty()) {
                        dynamicApertures = supportedAps.map { valCode ->
                            valCode to "f/${valCode / 100.0}"
                        }.toTypedArray()
                    }

                    try {
                        if (iso != -999999 && iso > 0) {
                            val newIsoIdx = isos.indexOf(iso)
                            if (newIsoIdx >= 0) currentIsoIndex = newIsoIdx
                        }
                    } catch (e: Exception) {}
                    
                    try {
                        if (aperture != -999999 && aperture > 0) {
                            val newApIdx = dynamicApertures.indexOfFirst { it.first == aperture }
                            if (newApIdx >= 0) currentApIndex = newApIdx
                        }
                    } catch (e: Exception) {}
                    
                    try {
                        if (shutter != -999999) {
                            val newShIdx = shutters.indexOfFirst { it.first == shutter }
                            if (newShIdx >= 0) currentShIndex = newShIdx
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) { runOnUiThread { log("Prop Error: ${e.message}") } }
        }
    }

    private fun listPhotos() {
        thread {
            try {
                Thread.sleep(800)
                val handles = ptpConnection?.getObjectHandles()
                runOnUiThread {
                    if (handles != null) log("Found ${handles.size} objects")
                    else log("Photo list failed")
                }
            } catch (e: Exception) { runOnUiThread { log("List Error: ${e.message}") } }
        }
    }

    private fun findAndConnect() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.vendorId == 1200) {
                log("Nikon D300 found")
                connectButton.isEnabled = false
                if (usbManager.hasPermission(device)) connectToDevice(device)
                else {
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
                    usbManager.requestPermission(device, permissionIntent)
                }
                return
            }
        }
        log("No Nikon found")
    }

    private fun connectToDevice(device: UsbDevice) {
        log("Initializing...")
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.find { it.interfaceClass == 6 }
        if (intf == null) { log("PTP Interface Missing"); connectButton.isEnabled = true; return }
        
        ptpConnection?.close()
        val connection = usbManager.openDevice(device)
        if (connection == null) { log("USB Open Failed"); connectButton.isEnabled = true; return }

        ptpConnection = PtpUsbConnection(connection, intf) { msg -> runOnUiThread { log(msg) } }
        
        thread {
            try {
                if (ptpConnection?.openSession() == true) {
                    isConnected = true
                    updateButtonStates()
                    runOnUiThread { log("Ready.") }
                    val deviceInfo = ptpConnection?.getDeviceInfo()
                    runOnUiThread {
                        if (deviceInfo != null) {
                            statusText.text = "Connected: $deviceInfo"
                            updateProperties()
                            listPhotos()
                            startPolling()
                        } else { statusText.text = "Connected" }
                    }
                } else { 
                    runOnUiThread { log("PTP Session Failed"); connectButton.isEnabled = true } 
                }
            } catch (e: Exception) { 
                runOnUiThread { log("Error: ${e.message}"); connectButton.isEnabled = true } 
            }
        }
    }

    private fun disconnect() {
        isLiveViewRunning = false
        isPollingActive = false
        bulbJob?.cancel()
        runOnUiThread { bulbSheet?.dismiss() }
        thread {
            ptpConnection?.close()
            ptpConnection = null
            isConnected = false
            updateButtonStates()
            runOnUiThread {
                statusText.text = "Status: Disconnected"
                log("Disconnected")
            }
        }
    }

    private fun setIso(value: Int) {
        thread {
            try {
                log("Setting ISO to $value...")
                val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_EXPOSURE_INDEX, value, size = 2, logDesc = "Setting ISO to $value") ?: false
                runOnUiThread { log("Set ISO: ${if (success) "Success" else "Failed"}") }
            } catch (e: Exception) {
                runOnUiThread { log("Set ISO Error: ${e.message}") }
            }
            Thread.sleep(300)
            updateProperties()
        }
    }

    private fun setAperture(value: Int, name: String) {
        thread {
            try {
                log("Setting Aperture to $name...")
                val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_F_NUMBER, value, size = 2, logDesc = "Setting Aperture to $name") ?: false
                runOnUiThread { log("Set Aperture: ${if (success) "Success" else "Failed"}") }
            } catch (e: Exception) {
                runOnUiThread { log("Set Aperture Error: ${e.message}") }
            }
            Thread.sleep(300)
            updateProperties()
        }
    }

    private fun setShutter(value: Int, name: String) {
        thread {
            try {
                if (value == 0xFFFFFFFF.toInt()) {
                    log("Switching to Manual Mode for Bulb...")
                    val modeSuccess = ptpConnection?.setDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, 1, size = 2, logDesc = "Setting ExposureMode to M (0x0001)") ?: false
                    if (!modeSuccess) {
                        runOnUiThread { log("Failed to switch to M mode. Aborting Bulb.") }
                        return@thread
                    }
                    Thread.sleep(300)
                    
                    log("Setting Shutter to Bulb...")
                    val bulbSuccess = ptpConnection?.setShutterBulb() ?: false
                    if (bulbSuccess) {
                        Thread.sleep(100)
                        ptpConnection?.deviceReady()
                        runOnUiThread { log("Set Shutter to Bulb: Success") }
                    } else {
                        runOnUiThread { log("Set Shutter to Bulb: Failed (Is the dial on M?)") }
                    }
                } else {
                    log("Setting Shutter to $name...")
                    val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME, value, size = 4, logDesc = "Setting ShutterSpeed to $name (Val: $value)") ?: false
                    
                    // For Nikon, sending DeviceReady after a critical property change helps UI sync
                    if (success) {
                        Thread.sleep(100)
                        ptpConnection?.deviceReady()
                    }

                    runOnUiThread { log("Set Shutter: ${if (success) "Success" else "Failed"}") }
                }
            } catch (e: Exception) {
                runOnUiThread { log("Set Shutter Error: ${e.message}") }
            }
            Thread.sleep(400)
            updateProperties()
        }
    }

    private fun startPolling() {
        if (isPollingActive) return
        isPollingActive = true
        thread {
            var lastFocal = -1
            var lastIso = -1
            var lastAp = -1
            var lastSh = -1
            var lastMode = -1

            var consecutiveFailures = 0

            while (isPollingActive && isConnected) {
                try {
                    // Poll focal length as a basic heartbeat
                    val focal = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCAL_LENGTH) ?: -999999
                    
                    if (focal == -999999) {
                        consecutiveFailures++
                        // Allow up to 3 failures (e.g. camera is busy writing to card after capture)
                        if (consecutiveFailures >= 3) {
                            runOnUiThread { log("Camera unresponsive, disconnecting...") }
                            disconnect()
                            break
                        }
                    } else {
                        consecutiveFailures = 0
                        
                        val iso = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_INDEX) ?: -999999
                        val ap = ptpConnection?.getDevicePropValue(PtpConstants.PROP_F_NUMBER) ?: -999999
                        val sh = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME) ?: -999999
                        val mode = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE) ?: -999999

                        if (focal != lastFocal || iso != lastIso || ap != lastAp || sh != lastSh || mode != lastMode) {
                            lastFocal = focal
                            if (iso != -999999) lastIso = iso
                            if (ap != -999999) lastAp = ap
                            if (sh != -999999) lastSh = sh
                            if (mode != -999999) lastMode = mode
                            updateProperties()
                        }
                    }
                } catch (e: Exception) {
                    // Critical USB exception
                    runOnUiThread { log("USB Error: ${e.message}") }
                    disconnect()
                    break
                }
                Thread.sleep(2000)
            }
            isPollingActive = false
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            infoText.append("\n$message")
        }
    }

    private fun showBulbBottomSheet() {
        val sheet = BottomSheetDialog(this)
        bulbSheet = sheet
        
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 60, 40, 80)
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE)
        }

        // 1. Progress & Countdown
        val progressBox = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, 400)
        }
        
        // Using standard ProgressBar for simplicity in code-only UI, or FrameLayout overlay
        val circularProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = false
            max = 1000
            progress = 0
            visibility = android.view.View.GONE
        }
        
        val countdownText = android.widget.TextView(this).apply {
            text = "Ready"
            textSize = 48f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = android.view.Gravity.CENTER
        }
        
        progressBox.addView(circularProgress, android.widget.FrameLayout.LayoutParams(300, 300, android.view.Gravity.CENTER))
        progressBox.addView(countdownText, android.widget.FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
        container.addView(progressBox)

        // 2. Stepper (- 10s +)
        var selectedSeconds = 10
        val stepperLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 40, 0, 40)
        }
        
        val decBtn = android.widget.Button(this).apply { text = "-" }
        val secondsText = android.widget.TextView(this).apply { 
            text = "${selectedSeconds}s"
            textSize = 24f
            setPadding(40, 0, 40, 0)
        }
        val incBtn = android.widget.Button(this).apply { text = "+" }
        
        stepperLayout.addView(decBtn)
        stepperLayout.addView(secondsText)
        stepperLayout.addView(incBtn)
        container.addView(stepperLayout)

        fun updateSecondsDisplay() {
            secondsText.text = if (selectedSeconds >= 60) "${selectedSeconds/60}m ${selectedSeconds%60}s" else "${selectedSeconds}s"
        }

        decBtn.setOnClickListener { if (selectedSeconds > 1) { selectedSeconds--; updateSecondsDisplay() } }
        incBtn.setOnClickListener { selectedSeconds++; updateSecondsDisplay() }

        // 3. Presets
        val presetsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val presetsLayout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val presetValues = listOf(1, 5, 10, 30, 60, 300, 600)
        val presetLabels = listOf("1s", "5s", "10s", "30s", "1m", "5m", "10m")
        
        presetValues.forEachIndexed { i, v ->
            val b = android.widget.Button(this).apply {
                text = presetLabels[i]
                setOnClickListener { selectedSeconds = v; updateSecondsDisplay() }
            }
            presetsLayout.addView(b)
        }
        presetsScroll.addView(presetsLayout)
        container.addView(presetsScroll)

        // 4. Action Button
        val actionBtn = android.widget.Button(this).apply {
            text = "START EXPOSURE"
            setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, 150).apply { topMargin = 60 }
        }
        container.addView(actionBtn)

        var isExposing = false
        
        actionBtn.setOnClickListener {
            if (!isExposing) {
                // START
                lifecycleScope.launch(Dispatchers.IO) {
                    // 0. Auto-end Live View if running (Required for Capture on D300)
                    if (isLiveViewRunning) {
                        runOnUiThread { log("Ending Live View for Bulb...") }
                        stopLiveView()
                        delay(800)
                    }

                    log("Bulb: Activating...")
                    // Ensure M mode
                    ptpConnection?.setDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, 1, size = 2)
                    delay(200)
                    
                    val bulbSuccess = ptpConnection?.setShutterBulb() ?: false
                    if (!bulbSuccess) {
                        runOnUiThread { 
                            android.widget.Toast.makeText(this@MainActivity, "Failed to activate Bulb mode", android.widget.Toast.LENGTH_SHORT).show()
                            log("Bulb activation failed (Parameter Error)")
                        }
                        return@launch
                    }
                    
                    ptpConnection?.deviceReady()
                    delay(300)
                    
                    if (!(ptpConnection?.capture() ?: false)) {
                        runOnUiThread { 
                            android.widget.Toast.makeText(this@MainActivity, "Capture Command Rejected (Is Focus Locked?)", android.widget.Toast.LENGTH_LONG).show()
                            log("Shutter trigger failed. Note: D300 may reject capture if AF cannot lock or in certain LV states.")
                        }
                        return@launch
                    }

                    isExposing = true
                    runOnUiThread {
                        actionBtn.text = "STOP"
                        actionBtn.setBackgroundColor(android.graphics.Color.RED)
                        circularProgress.visibility = android.view.View.VISIBLE
                        decBtn.isEnabled = false
                        incBtn.isEnabled = false
                        presetsScroll.visibility = android.view.View.GONE
                    }

                    val totalMs = selectedSeconds * 1000L
                    val startTime = System.currentTimeMillis()
                    
                    bulbJob = lifecycleScope.launch(Dispatchers.Main) {
                        try {
                            while (isActive) {
                                val elapsed = System.currentTimeMillis() - startTime
                                val remaining = totalMs - elapsed
                                
                                if (remaining <= 0) {
                                    countdownText.text = "00s"
                                    circularProgress.progress = 1000
                                    break
                                }
                                
                                val remSec = (remaining / 1000).toInt()
                                countdownText.text = String.format(java.util.Locale.US, "%02ds", remSec)
                                circularProgress.progress = (1000 * (totalMs - remaining) / totalMs).toInt()
                                
                                delay(100)
                            }
                        } finally {
                            stopBulbExposure()
                        }
                    }
                }
            } else {
                // STOP
                bulbJob?.cancel()
            }
        }

        sheet.setContentView(container)
        sheet.setOnDismissListener { 
            if (isExposing) bulbJob?.cancel()
            bulbSheet = null
        }
        sheet.show()
    }

    private fun stopBulbExposure() {
        lifecycleScope.launch(Dispatchers.IO) {
            log("Ending Bulb Exposure...")
            ptpConnection?.terminateCapture()
            delay(500)
            updateProperties()
            runOnUiThread {
                bulbSheet?.dismiss()
            }
        }
    }

    private fun showBracketingDialog() {
        val dialog = android.app.Dialog(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            
            addView(android.widget.TextView(context).apply {
                text = "Aperture Bracketing"
                textSize = 20f
                gravity = android.view.Gravity.CENTER
            })

            val infoText = android.widget.TextView(context).apply {
                text = "Total steps: ${dynamicApertures.size}\nRange: ${dynamicApertures.firstOrNull()?.second ?: "--"} to ${dynamicApertures.lastOrNull()?.second ?: "--"}"
                setPadding(0, 20, 0, 20)
            }
            addView(infoText)

            val statusText = android.widget.TextView(context).apply {
                text = "Idle"
                textSize = 16f
                setTextColor(android.graphics.Color.BLUE)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 20, 0, 20)
            }
            addView(statusText)

            val startBtn = android.widget.Button(context).apply { text = "Start Bracketing" }
            addView(startBtn)

            var isRunning = false
            
            startBtn.setOnClickListener {
                if (!isRunning) {
                    isRunning = true
                    startBtn.text = "STOP"
                    thread {
                        try {
                            log("Starting Aperture Bracketing...")
                            // Ensure we are in M or A mode
                            val currentMode = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE) ?: 1
                            if (currentMode != 1 && currentMode != 3) {
                                runOnUiThread { log("Switching to Aperture Priority (A)...") }
                                ptpConnection?.setDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, 3, size = 2)
                                Thread.sleep(500)
                            }

                            dynamicApertures.forEachIndexed { index, pair ->
                                if (!isRunning) return@forEachIndexed
                                
                                runOnUiThread { 
                                    statusText.text = "Capturing ${index + 1}/${dynamicApertures.size}: ${pair.second}"
                                }
                                
                                // Set Aperture
                                val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_F_NUMBER, pair.first, size = 2) ?: false
                                if (success) {
                                    Thread.sleep(300)
                                    ptpConnection?.capture()
                                    // Wait for write and mirror
                                    Thread.sleep(1500) 
                                } else {
                                    runOnUiThread { log("Failed to set ${pair.second}, skipping...") }
                                }
                            }
                            runOnUiThread { 
                                log("Bracketing Complete")
                                statusText.text = "Complete"
                                startBtn.text = "Start Bracketing"
                                isRunning = false
                            }
                        } catch (e: Exception) {
                            runOnUiThread { 
                                log("Bracket Error: ${e.message}")
                                statusText.text = "Error"
                                startBtn.text = "Start Bracketing"
                                isRunning = false
                            }
                        }
                    }
                } else {
                    isRunning = false
                    startBtn.text = "Start Bracketing"
                    statusText.text = "Stopped"
                }
            }
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showExpBracketingDialog() {
        val dialog = android.app.Dialog(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            
            addView(android.widget.TextView(context).apply {
                text = "Exposure Bracketing (Shutter)"
                textSize = 20f
                gravity = android.view.Gravity.CENTER
            })

            addView(android.widget.TextView(context).apply { text = "EV Step (Indices, 1=1/3EV):" })
            val stepInput = android.widget.EditText(context).apply {
                hint = "Step (1=1/3, 3=1EV)"
                inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_NUMBER
                setText("3")
            }
            addView(stepInput)

            addView(android.widget.TextView(context).apply { text = "Number of Shots (3, 5, 7):" })
            val countInput = android.widget.EditText(context).apply {
                hint = "Count"
                inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_NUMBER
                setText("3")
            }
            addView(countInput)

            val statusText = android.widget.TextView(context).apply {
                text = "Idle"
                textSize = 16f
                setTextColor(android.graphics.Color.BLUE)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 20, 0, 20)
            }
            addView(statusText)

            val startBtn = android.widget.Button(context).apply { text = "Start Exp Bracket" }
            addView(startBtn)

            var isRunning = false
            
            startBtn.setOnClickListener {
                if (!isRunning) {
                    val step = stepInput.text.toString().toIntOrNull() ?: 3
                    val count = countInput.text.toString().toIntOrNull() ?: 3
                    isRunning = true
                    startBtn.text = "STOP"
                    
                    thread {
                        try {
                            log("Starting Exposure Bracketing...")
                            // 1. Get current shutter index
                            val currentShVal = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME) ?: -999999
                            val startIndex = shutters.indexOfFirst { it.first == currentShVal }
                            
                            if (startIndex == -1) {
                                runOnUiThread { log("Error: Current shutter speed not in standard list."); isRunning = false; startBtn.text = "Start Exp Bracket" }
                                return@thread
                            }

                            // Calculate sequence: e.g. center, -step, +step
                            // For 3 shots: -step, center, +step
                            val sequence = mutableListOf<Int>()
                            val half = count / 2
                            for (i in -half..half) {
                                val idx = startIndex + (i * step)
                                if (idx in shutters.indices) {
                                    sequence.add(idx)
                                }
                            }

                            sequence.forEachIndexed { i, shIdx ->
                                if (!isRunning) return@forEachIndexed
                                val pair = shutters[shIdx]
                                runOnUiThread { statusText.text = "Capturing ${i + 1}/${sequence.size}: ${pair.second}" }
                                
                                // Set Shutter
                                val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME, pair.first, size = 4) ?: false
                                if (success) {
                                    Thread.sleep(300)
                                    ptpConnection?.capture()
                                    Thread.sleep(1500)
                                }
                            }

                            runOnUiThread { 
                                log("Exp Bracketing Complete")
                                statusText.text = "Complete"
                                startBtn.text = "Start Exp Bracket"
                                isRunning = false
                                updateProperties()
                            }
                        } catch (e: Exception) {
                            runOnUiThread { 
                                log("Exp Bracket Error: ${e.message}")
                                statusText.text = "Error"
                                startBtn.text = "Start Exp Bracket"
                                isRunning = false
                            }
                        }
                    }
                } else {
                    isRunning = false
                    startBtn.text = "Start Exp Bracket"
                    statusText.text = "Stopped"
                }
            }
        }
        dialog.setContentView(container)
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        ptpConnection?.close()
    }
}
