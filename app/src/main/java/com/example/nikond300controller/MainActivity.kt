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
import com.google.android.material.button.MaterialButton
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.util.Log
import kotlinx.coroutines.*
import kotlin.concurrent.thread
import kotlin.jvm.Volatile

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private lateinit var batteryText: TextView
    private lateinit var infoText: TextView
    private lateinit var propText: TextView
    private lateinit var connectButton: Button
    private lateinit var captureButton: Button
    private lateinit var modeButton: Button
    private lateinit var liveViewButton: Button
    private lateinit var isoButton: Button
    private lateinit var apertureButton: Button
    private lateinit var shutterButton: Button
    private lateinit var focusButton: Button
    private lateinit var wbButton: Button
    private lateinit var isoAutoButton: Button
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

    @Volatile
    private var activeShutterProp = PtpConstants.PROP_EXPOSURE_TIME
    @Volatile
    private var shutterPropSize = 4 // Default for 0x500D

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
    
    @Volatile
    private var cameraSupportedShutters: IntArray? = null
    @Volatile
    private var shutterMap: Map<Int, String> = emptyMap()

    private fun getShutterString(valCode: Int): String {
        if (valCode == -1) return "Bulb"
        if (valCode == -2) return "Flash"

        return if (activeShutterProp == PtpConstants.PROP_NIKON_SHUTTER_SPEED) {
            // Nikon bit-packed format (0xD100)
            val numerator = (valCode shr 16) and 0xFFFF
            val denominator = valCode and 0xFFFF
            when {
                denominator == 1 -> "${numerator}\""
                numerator == 1 -> "1/$denominator"
                numerator > denominator -> String.format("%.1f\"", numerator.toDouble() / denominator)
                else -> "$numerator/$denominator"
            }
        } else {
            // Standard PTP Exposure Time (0x500D) - Units of 1/10000s
            val seconds = valCode / 10000
            val rest = valCode % 10000
            if (seconds > 0) {
                if (rest > 0) {
                    val frac = Math.round(1.0 / (rest * 0.0001))
                    "${seconds}\" 1/$frac"
                } else {
                    "${seconds}\""
                }
            } else if (rest > 0) {
                "1/${Math.round(1.0 / (rest * 0.0001))}"
            } else {
                "0\""
            }
        }
    }

    private var shutters = emptyArray<Pair<Int, String>>()


    private var ptpConnection: PtpUsbConnection? = null
    private var isLiveViewRunning = false
    @Volatile
    private var isConnected = false
    @Volatile
    private var isPollingPaused = false

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
        batteryText = findViewById(R.id.batteryText)
        infoText = findViewById(R.id.infoText)
        propText = findViewById(R.id.propText)
        propText.text = "Focus: -- | Focal: -- | Bias: --"
        connectButton = findViewById(R.id.connectButton)
        captureButton = findViewById(R.id.captureButton)
        modeButton = findViewById(R.id.modeButton)
        liveViewButton = findViewById(R.id.liveViewButton)
        isoButton = findViewById(R.id.isoButton)
        apertureButton = findViewById(R.id.apertureButton)
        shutterButton = findViewById(R.id.shutterButton)
        focusButton = findViewById(R.id.focusButton)
        wbButton = findViewById(R.id.wbButton)
        isoAutoButton = findViewById(R.id.isoAutoButton)
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
                    isPollingPaused = true // STOP ALL POLLING
                    
                    // 1. Auto-end Live View if running (Extra safe for D300)
                    if (isLiveViewRunning) {
                        runOnUiThread { log("Capture: Stopping LV...") }
                        isLiveViewRunning = false
                        ptpConnection?.endLiveView()
                        runOnUiThread { liveViewButton.text = "LV" }
                        Thread.sleep(2500) // D300 mirror flip is VERY slow
                    }

                    // Check Focus Mode for AF-S or AF-C (and AF-A)
                    val focusMode = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCUS_MODE) ?: -1
                    val afModes = listOf(2, 3, 4, 0x8001, 0x8002, 0x8003, 0x8010, 0x8011, 0x8012)
                    if (focusMode in afModes) {
                        runOnUiThread { log("Auto-focusing...") }
                        val afSuccess = ptpConnection?.afDrive() ?: false
                        if (!afSuccess) {
                            runOnUiThread {
                                log("AF Failed: Cannot trigger shutter without focus lock.")
                                android.widget.Toast.makeText(this@MainActivity, "AF Failed: Cannot trigger shutter without focus lock.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return@thread // Abort the capture sequence immediately
                        }
                        
                        runOnUiThread { log("AF finished. Waiting for camera to settle...") }
                        var settled = false
                        for (i in 1..5) {
                            if (ptpConnection?.deviceReady() == true) {
                                settled = true
                                break
                            }
                            runOnUiThread { log("Camera busy, waiting...") }
                            Thread.sleep(400)
                        }
                        Thread.sleep(200)
                    }

                    log("Firing shutter...")
                    var responseCode = ptpConnection?.capture() ?: -1

                    // Improve capture execution for "Busy" codes (0xA008 or 0x2002)
                    if (responseCode == 0xA008 || responseCode == PtpConstants.RESP_DEVICE_BUSY) {
                        runOnUiThread { log("Capture reported Busy. Automated retry in 1s...") }
                        Thread.sleep(1000)
                        ptpConnection?.clearPipe()
                        responseCode = ptpConnection?.capture() ?: -1
                    }

                    val success = responseCode == PtpConstants.RESP_OK
                    
                    runOnUiThread { 
                        if (success) {
                            log("Capture: Success")
                        } else {
                            if (responseCode == PtpConstants.RESP_NIKON_HARDWARE_ERROR) {
                                log("Capture Failed: Focus not locked (Camera Blocked).")
                                android.widget.Toast.makeText(this@MainActivity, "Capture Failed: Focus not locked (Camera Blocked).", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                log("Capture: Failed (Check Busy/Focus)")
                                android.widget.Toast.makeText(this@MainActivity, "Capture Rejected: Camera Busy or Out of Focus.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    
                    if (success) {
                        Thread.sleep(3000) // Wait for card write
                    }
                } catch (e: Exception) { 
                    runOnUiThread { log("Capture Error: ${e.message}") } 
                } finally {
                    isPollingPaused = false // RESUME POLLING
                    thread { updateProperties() }
                }
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

        wbButton.setOnClickListener {
            showWhiteBalanceDialog()
        }

        isoAutoButton.setOnClickListener {
            thread {
                try {
                    isPollingPaused = true
                    log("Toggling ISO Auto...")
                    val currentAuto = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO) ?: 0
                    val nextVal = if (currentAuto == 1) 0 else 1
                    val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO, nextVal, size = 1) ?: false
                    
                        runOnUiThread {
                            log("ISO Auto: ${if (nextVal == 1) "ON" else "OFF"}")
                        }
                } catch (e: Exception) {
                    runOnUiThread { log("ISO Auto Error: ${e.message}") }
                } finally {
                    isPollingPaused = false
                    updateProperties()
                }
            }
        }

        focusButton.setOnClickListener {
            thread {
                try {
                    isPollingPaused = true
                    // Wait for any pending polling traffic to clear
                    Thread.sleep(300)
                    
                    log("AF: Dispatching drive command...")
                    val startTime = System.nanoTime()
                    
                    val success = ptpConnection?.afDrive() ?: false
                    
                    val endTime = System.nanoTime()
                    val overheadMs = (endTime - startTime) / 1_000_000.0
                    
                    runOnUiThread {
                        val resultText = if (success) "Focus Locked" else "AF Failed/Timeout"
                        log("AF Status: $resultText")
                        log("Time Overhead: ${String.format("%.2f", overheadMs)}ms")
                    }
                } catch (e: Exception) {
                    runOnUiThread { log("AF Execution Error: ${e.message}") }
                } finally {
                    isPollingPaused = false
                }
            }
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
            focusButton.isEnabled = isConnected
            wbButton.isEnabled = isConnected
            isoAutoButton.isEnabled = isConnected
            liveViewButton.isEnabled = isConnected
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
        if (!isConnected || isPollingPaused) return // Skip if paused
        thread {
            try {
                if (isPollingPaused) return@thread
                // Shortened delays for faster UI response
                Thread.sleep(100)
                val iso = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_INDEX) ?: -999999
                Thread.sleep(50)
                val aperture = ptpConnection?.getDevicePropValue(PtpConstants.PROP_F_NUMBER) ?: -999999
                Thread.sleep(50)
                val shutter = ptpConnection?.getDevicePropValue(activeShutterProp) ?: -999999
                Thread.sleep(50)
                val mode = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE) ?: -999999
                Thread.sleep(50)
                val focalLengthRaw = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCAL_LENGTH) ?: -999999
                Thread.sleep(50)
                val biasRaw = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_BIAS_COMPENSATION) ?: -999999
                Thread.sleep(50)
                val focusModeRaw = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCUS_MODE) ?: -999999
                Thread.sleep(50)
                val stdBattery = ptpConnection?.getDevicePropValue(PtpConstants.PROP_BATTERY_LEVEL) ?: -999999
                Thread.sleep(50)
                val nikonBattery = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_BATTERY_LEVEL) ?: -999999
                Thread.sleep(50)
                val wb = ptpConnection?.getDevicePropValue(PtpConstants.PROP_WHITE_BALANCE) ?: -999999
                Thread.sleep(50)
                val isoAuto = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO) ?: -999999
                Thread.sleep(50)

                val batteryLevel = if (stdBattery >= nikonBattery) stdBattery else nikonBattery
                
                val supportedAps = ptpConnection?.getDevicePropSupportedValues(PtpConstants.PROP_F_NUMBER)
                
                runOnUiThread {
                    if (batteryLevel != -999999) {
                        batteryText.text = "$batteryLevel%"
                        // Color coding battery level
                        batteryText.setTextColor(when {
                            batteryLevel > 50 -> android.graphics.Color.parseColor("#2E7D32") // Green
                            batteryLevel > 20 -> android.graphics.Color.parseColor("#F57C00") // Orange
                            else -> android.graphics.Color.RED
                        })
                    } else {
                        batteryText.text = "--%"
                    }

                    wbButton.text = when(wb) {
                        2 -> "Auto"
                        4 -> "Daylight"
                        5 -> "Incand"
                        6 -> "Fluor"
                        7 -> "Flash"
                        0x8010 -> "Cloudy"
                        0x8011 -> "Shade"
                        else -> "WB"
                    }

                    isoAutoButton.text = "ISO Auto"
                    if (isoAuto != -999999) {
                        isoAutoButton.setBackgroundColor(if (isoAuto == 1) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#757575"))
                    }

                    val modeStr = when (mode) {
                        1 -> "M"
                        2 -> "P"
                        3 -> "A"
                        4 -> "S"
                        else -> "--"
                    }
                    val apStr = if (aperture != -999999) "f/${aperture/100.0}" else "--"
                    val shStr = if (shutter != -999999) {
                        shutterMap[shutter] ?: getShutterString(shutter)
                    } else "--"
                    
                    val focalStr = if (focalLengthRaw != -999999 && focalLengthRaw > 0) "${focalLengthRaw / 100}mm" else "--"
                    
                    val biasStr = if (biasRaw != -999999) {
                        val b = biasRaw.toShort() // PTP Bias is often 16-bit signed
                        val ev = b / 1000.0
                        if (ev > 0) "+%.1f".format(ev) else "%.1f".format(ev)
                    } else "--"

                    val focusStr = when (focusModeRaw) {
                        1 -> "MF"
                        2, 0x8001, 0x8010 -> "AF-S"
                        3, 0x8002, 0x8011 -> "AF-C"
                        4, 0x8003, 0x8012 -> "AF-A"
                        0x8004 -> "AF-F"
                        -999999 -> "--"
                        else -> "0x${Integer.toHexString(focusModeRaw).uppercase()}"
                    }
                    
                    // Update button texts with actual values (Shortened to fit small screens)
                    isoButton.text = if (iso != -999999 && iso > 0) "$iso" else "ISO"
                    apertureButton.text = apStr
                    shutterButton.text = shStr
                    modeButton.text = "Mode: $modeStr"
                    
                    // Update main info text with Focus, Focal Length and Bias
                    propText.text = "Focus: $focusStr | Focal: $focalStr | Bias: $biasStr"
                    
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
                    
                    // Shutter Property Discovery
                    val supportedShuttersD100 = ptpConnection?.getDevicePropSupportedValues(PtpConstants.PROP_NIKON_SHUTTER_SPEED)
                    if (supportedShuttersD100 != null && supportedShuttersD100.isNotEmpty()) {
                        activeShutterProp = PtpConstants.PROP_NIKON_SHUTTER_SPEED
                        shutterPropSize = 4
                        cameraSupportedShutters = supportedShuttersD100
                        runOnUiThread { log("Shutter: Using Nikon Prop (0xD100)") }
                    } else {
                        activeShutterProp = PtpConstants.PROP_EXPOSURE_TIME
                        shutterPropSize = 4
                        cameraSupportedShutters = ptpConnection?.getDevicePropSupportedValues(PtpConstants.PROP_EXPOSURE_TIME)
                        runOnUiThread { log("Shutter: Using Standard Prop (0x500D)") }
                    }

                    if (cameraSupportedShutters != null) {
                        val hexString = cameraSupportedShutters?.joinToString(", ") { "0x${Integer.toHexString(it).uppercase()}" }
                        Log.d("D300_PROP", "Supported Shutters: $hexString")
                        
                        shutterMap = cameraSupportedShutters!!.associateWith { getShutterString(it) }
                        shutters = cameraSupportedShutters!!.map { it to getShutterString(it) }.toTypedArray()
                    }

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
        thread {
            ptpConnection?.close()
            ptpConnection = null
            isConnected = false
            updateButtonStates()
            runOnUiThread {
                statusText.text = "Status: Disconnected"
                batteryText.text = "--%"
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
                log("Setting Shutter to $name...")
                
                val success = ptpConnection?.setDevicePropValue(activeShutterProp, value, size = shutterPropSize, logDesc = "Setting ShutterSpeed to $name (Val: $value)") ?: false
                
                // For Nikon, sending DeviceReady after a critical property change helps UI sync
                if (success) {
                    Thread.sleep(100)
                    ptpConnection?.deviceReady()
                }

                runOnUiThread { log("Set Shutter: ${if (success) "Success" else "Failed"}") }
            } catch (e: Exception) {
                runOnUiThread { log("Set Shutter Error: ${e.message}") }
            }
            Thread.sleep(400)
            updateProperties()
        }
    }

    private fun setWhiteBalance(value: Int, name: String) {
        thread {
            try {
                log("Setting WB to $name...")
                val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_WHITE_BALANCE, value, size = 2) ?: false
                if (success) {
                    ptpConnection?.deviceReady()
                }
                runOnUiThread { log("Set WB: ${if (success) "Success" else "Failed"}") }
            } catch (e: Exception) {
                runOnUiThread { log("Set WB Error: ${e.message}") }
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
            var lastFocus = -1
            var lastBatteryStd = -1
            var lastBatteryNikon = -1

            var consecutiveFailures = 0

            while (isPollingActive && isConnected) {
                if (isPollingPaused) {
                    Thread.sleep(1000)
                    continue
                }
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
                        val focus = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCUS_MODE) ?: -999999
                        val batteryStd = ptpConnection?.getDevicePropValue(PtpConstants.PROP_BATTERY_LEVEL) ?: -999999
                        val batteryNikon = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_BATTERY_LEVEL) ?: -999999

                        if (focal != lastFocal || iso != lastIso || ap != lastAp || sh != lastSh || mode != lastMode || focus != lastFocus || 
                            batteryStd != lastBatteryStd || batteryNikon != lastBatteryNikon) {
                            
                            if (batteryStd != lastBatteryStd || batteryNikon != lastBatteryNikon) {
                                runOnUiThread { log("Battery Levels - Standard (0x5001): $batteryStd, Nikon (0xD1B3): $batteryNikon") }
                                lastBatteryStd = batteryStd
                                lastBatteryNikon = batteryNikon
                            }

                            lastFocal = focal
                            if (iso != -999999) lastIso = iso
                            if (ap != -999999) lastAp = ap
                            if (sh != -999999) lastSh = sh
                            if (mode != -999999) lastMode = mode
                            if (focus != -999999) lastFocus = focus
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

    private fun showWhiteBalanceDialog() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 60)
            
            addView(TextView(context).apply {
                text = "White Balance"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            })

            val wbModes = arrayOf(
                2 to "Auto",
                4 to "Daylight",
                5 to "Incandescent",
                6 to "Fluorescent",
                7 to "Flash",
                32784 to "Cloudy",
                32785 to "Shade"
            )

            wbModes.forEach { pair ->
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = pair.second
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 10)
                    }
                    setOnClickListener {
                        setWhiteBalance(pair.first, pair.second)
                        dialog.dismiss()
                    }
                })
            }
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showBracketingDialog() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 60)
            
            addView(TextView(context).apply {
                text = "Aperture Bracketing"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            })

            val rangeLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                
                addView(TextView(context).apply {
                    text = dynamicApertures.firstOrNull()?.second ?: "--"
                    textSize = 18f
                    setTextColor(Color.GRAY)
                })
                addView(TextView(context).apply {
                    text = " → "
                    textSize = 18f
                    setPadding(20, 0, 20, 0)
                })
                addView(TextView(context).apply {
                    text = dynamicApertures.lastOrNull()?.second ?: "--"
                    textSize = 18f
                    setTextColor(Color.GRAY)
                })
            }
            addView(rangeLayout)

            val manualRangeCheck = android.widget.CheckBox(context).apply {
                text = "Manual Range"
                isChecked = false
            }
            addView(manualRangeCheck)

            var manualStartIndex = 0
            var manualEndIndex = dynamicApertures.size - 1

            val manualRangeLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(0, 10, 0, 10)

                val startButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "Start: ${dynamicApertures.getOrNull(manualStartIndex)?.second ?: "--"}"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { btn ->
                        val popup = android.widget.PopupMenu(context, btn)
                        dynamicApertures.forEachIndexed { index, pair ->
                            popup.menu.add(0, index, index, pair.second)
                        }
                        popup.setOnMenuItemClickListener { item ->
                            manualStartIndex = item.itemId
                            text = "Start: ${dynamicApertures[manualStartIndex].second}"
                            true
                        }
                        popup.show()
                    }
                }
                addView(startButton)

                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(20, 1) })

                val endButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "End: ${dynamicApertures.getOrNull(manualEndIndex)?.second ?: "--"}"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { btn ->
                        val popup = android.widget.PopupMenu(context, btn)
                        dynamicApertures.forEachIndexed { index, pair ->
                            popup.menu.add(0, index, index, pair.second)
                        }
                        popup.setOnMenuItemClickListener { item ->
                            manualEndIndex = item.itemId
                            text = "End: ${dynamicApertures[manualEndIndex].second}"
                            true
                        }
                        popup.show()
                    }
                }
                addView(endButton)
            }
            addView(manualRangeLayout)

            manualRangeCheck.setOnCheckedChangeListener { _, isChecked ->
                manualRangeLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            }

            val stepsText = TextView(context).apply {
                text = "Total steps: ${dynamicApertures.size}"
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 20)
            }
            addView(stepsText)

            val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = dynamicApertures.size
                progress = 0
                visibility = View.GONE
                setPadding(0, 20, 0, 20)
            }
            addView(progressBar)

            val statusText = TextView(context).apply {
                text = "Ready to start"
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 30)
            }
            addView(statusText)

            val startBtn = MaterialButton(context).apply {
                text = "START BRACKETING"
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
            }
            addView(startBtn)

            var isRunning = false
            var bracketThread: Thread? = null

            fun stopBracketing() {
                isRunning = false
                bracketThread?.interrupt()
                runOnUiThread {
                    startBtn.text = "START BRACKETING"
                    startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                    statusText.text = "Stopped"
                    progressBar.visibility = View.GONE
                    dialog.setCancelable(true)
                }
            }
            
            startBtn.setOnClickListener {
                if (!isRunning) {
                    isRunning = true
                    dialog.setCancelable(false)
                    startBtn.text = "STOP"
                    startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 0
                    
                    bracketThread = thread {
                        try {
                            isPollingPaused = true
                            log("Starting Aperture Bracketing...")
                            
                            ptpConnection?.setDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, 3, size = 2)
                            Thread.sleep(1000) 

                            val targetList = if (manualRangeCheck.isChecked) {
                                val s = Math.min(manualStartIndex, manualEndIndex)
                                val e = Math.max(manualStartIndex, manualEndIndex)
                                dynamicApertures.slice(s..e)
                            } else {
                                dynamicApertures.toList()
                            }

                            runOnUiThread {
                                progressBar.max = targetList.size
                                stepsText.text = "Total steps: ${targetList.size}"
                            }

                            targetList.forEachIndexed { index, pair ->
                                if (!isRunning) return@forEachIndexed
                                
                                val stepInfo = "Step ${index + 1} of ${targetList.size}: ${pair.second}"
                                runOnUiThread { 
                                    statusText.text = stepInfo
                                    progressBar.progress = index + 1
                                }
                                
                                val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_F_NUMBER, pair.first, size = 2) ?: false
                                if (success) {
                                    Thread.sleep(600)
                                    ptpConnection?.capture()
                                    Thread.sleep(3000) 
                                } else {
                                    runOnUiThread { log("Failed to set ${pair.second}") }
                                }
                            }
                            runOnUiThread { 
                                log("Bracketing Complete")
                                statusText.text = "Complete"
                                startBtn.text = "START BRACKETING"
                                startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                                isRunning = false
                                progressBar.visibility = View.GONE
                                dialog.setCancelable(true)
                            }
                        } catch (e: Exception) {
                            if (isRunning) {
                                runOnUiThread { 
                                    statusText.text = "Error: ${e.message}"
                                    startBtn.text = "START BRACKETING"
                                    startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                                    isRunning = false
                                    dialog.setCancelable(true)
                                }
                            }
                        } finally {
                            isPollingPaused = false
                            thread { updateProperties() }
                        }
                    }
                } else {
                    stopBracketing()
                }
            }

            dialog.setOnDismissListener {
                if (isRunning) {
                    isRunning = false
                    bracketThread?.interrupt()
                }
            }
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showExpBracketingDialog() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 60)
            
            addView(TextView(context).apply {
                text = "Exposure Bracketing (Shutter)"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            })

            addView(TextView(context).apply { text = "EV Step (Indices, 1=1/3EV):" })
            val stepInput = android.widget.EditText(context).apply {
                hint = "Step (1=1/3, 3=1EV)"
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                setText("3")
            }
            addView(stepInput)

            addView(TextView(context).apply { text = "Number of Shots (3, 5, 7):" })
            val countInput = android.widget.EditText(context).apply {
                hint = "Count"
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                setText("3")
            }
            addView(countInput)

            val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                visibility = View.GONE
                setPadding(0, 20, 0, 20)
            }
            addView(progressBar)

            val statusText = TextView(context).apply {
                text = "Idle"
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 20)
            }
            addView(statusText)

            val startBtn = MaterialButton(context).apply {
                text = "START EXP BRACKET"
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
            }
            addView(startBtn)

            var isRunning = false
            var bracketThread: Thread? = null

            fun stopBracketing() {
                isRunning = false
                bracketThread?.interrupt()
                runOnUiThread {
                    startBtn.text = "START EXP BRACKET"
                    startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                    statusText.text = "Stopped"
                    progressBar.visibility = View.GONE
                    dialog.setCancelable(true)
                }
            }
            
            startBtn.setOnClickListener {
                if (!isRunning) {
                    val step = stepInput.text.toString().toIntOrNull() ?: 3
                    val count = countInput.text.toString().toIntOrNull() ?: 3
                    isRunning = true
                    dialog.setCancelable(false)
                    startBtn.text = "STOP"
                    startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
                    
                    bracketThread = thread {
                        try {
                            isPollingPaused = true
                            log("Starting Exposure Bracketing...")
                            val currentShVal = ptpConnection?.getDevicePropValue(activeShutterProp) ?: -999999
                            val startIndex = shutters.indexOfFirst { it.first == currentShVal }
                            
                            if (startIndex == -1) {
                                runOnUiThread { 
                                    log("Error: Current shutter speed not in standard list.")
                                    stopBracketing()
                                }
                                return@thread
                            }

                            val sequence = mutableListOf<Int>()
                            val half = count / 2
                            for (i in -half..half) {
                                val idx = startIndex + (i * step)
                                if (idx in shutters.indices) sequence.add(idx)
                            }

                            runOnUiThread {
                                progressBar.max = sequence.size
                                progressBar.progress = 0
                                progressBar.visibility = View.VISIBLE
                            }

                            sequence.forEachIndexed { i, shIdx ->
                                if (!isRunning) return@forEachIndexed
                                val pair = shutters[shIdx]
                                runOnUiThread { 
                                    statusText.text = "Capturing ${i + 1}/${sequence.size}: ${pair.second}"
                                    progressBar.progress = i + 1
                                }
                                
                                val success = ptpConnection?.setDevicePropValue(activeShutterProp, pair.first, size = shutterPropSize) ?: false
                                if (success) {
                                    Thread.sleep(800)
                                    ptpConnection?.capture()
                                    Thread.sleep(4000) 
                                } else {
                                    runOnUiThread { log("Failed to set ${pair.second}") }
                                }
                            }

                            runOnUiThread { 
                                log("Exp Bracketing Complete")
                                statusText.text = "Complete"
                                startBtn.text = "START EXP BRACKET"
                                startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                                isRunning = false
                                progressBar.visibility = View.GONE
                                dialog.setCancelable(true)
                                updateProperties()
                            }
                        } catch (e: Exception) {
                            if (isRunning) {
                                runOnUiThread { 
                                    statusText.text = "Error: ${e.message}"
                                    startBtn.text = "START EXP BRACKET"
                                    startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                                    isRunning = false
                                    dialog.setCancelable(true)
                                }
                            }
                        } finally {
                            isPollingPaused = false
                            thread { updateProperties() }
                        }
                    }
                } else {
                    stopBracketing()
                }
            }

            dialog.setOnDismissListener {
                if (isRunning) {
                    isRunning = false
                    bracketThread?.interrupt()
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
