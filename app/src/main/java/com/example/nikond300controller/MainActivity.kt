package com.example.nikond300controller

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import android.widget.ScrollView
import android.util.Log
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.jvm.Volatile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var customSettingsButton: Button
    private lateinit var galleryButton: Button
    
    private lateinit var isoUpButton: Button
    private lateinit var isoDownButton: Button
    private lateinit var apertureUpButton: Button
    private lateinit var apertureDownButton: Button
    private lateinit var shutterUpButton: Button
    private lateinit var shutterDownButton: Button
    private lateinit var logScrollView: ScrollView

    // 生命周期：仅在 Activity 处于前台时才做 USB 轮询 / Live View 取帧
    @Volatile
    private var isForeground = true

    // 属性刷新去重：并发请求合并为一次，避免 USB 线程堆积
    private val propertyUpdateLock = Any()

    @Volatile
    private var isPropertyUpdateRunning = false

    @Volatile
    private var isPropertyUpdatePending = false

    // 相册：每次打开递增，旧的加载循环因此自动放弃，实现“可取消”
    @Volatile
    private var galleryLoadToken = 0

    private var pendingStorageAction: (() -> Unit)? = null

    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingStorageAction?.invoke()
            else Toast.makeText(this, "需要存储权限才能保存到相册", Toast.LENGTH_SHORT).show()
            pendingStorageAction = null
        }

    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private companion object {
        const val MAX_LOG_LINES = 400
        const val FILE_AUTHORITY = "com.example.nikond300controller.fileprovider"
    }

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

    /**
     * PTP ExposureProgramMode (0x500E) 的标准取值。
     * 关键：未知值不能再吞成 "--"，必须把原始码显示出来，
     * 否则肉眼完全无法判断相机到底是不支持、还是返回了厂商扩展值。
     */
    private fun exposureProgramLabel(raw: Int): String {
        val suffix = when (raw) {
            -999999 -> "读取失败"
            0 -> "Undefined"
            1 -> "M"
            2 -> "P"
            3 -> "A"
            4 -> "S"
            5 -> "Creative"
            6 -> "Action"
            7 -> "Portrait"
            8 -> "Landscape"
            else -> "未知 0x${Integer.toHexString(raw).uppercase()}"
        }
        return "Mode: $suffix"
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
        customSettingsButton = findViewById(R.id.customSettingsButton)
        galleryButton = findViewById(R.id.galleryButton)
        
        isoUpButton = findViewById(R.id.isoUpButton)
        isoDownButton = findViewById(R.id.isoDownButton)
        apertureUpButton = findViewById(R.id.apertureUpButton)
        apertureDownButton = findViewById(R.id.apertureDownButton)
        shutterUpButton = findViewById(R.id.shutterUpButton)
        shutterDownButton = findViewById(R.id.shutterDownButton)
        logScrollView = findViewById(R.id.logScrollView)

        connectButton.setOnClickListener {
            if (isConnected) disconnect() else findAndConnect()
        }

        captureButton.setOnClickListener {
            thread {
                try {
                    isPollingPaused = true 
                    
                    if (isLiveViewRunning) {
                        runOnUiThread { log("Capture: Stopping LV...") }
                        isLiveViewRunning = false
                        ptpConnection?.endLiveView()
                        runOnUiThread { liveViewButton.text = "LV" }
                        Thread.sleep(2500) 
                    }

                    val focusMode = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCUS_MODE) ?: -1
                    val afModes = listOf(2, 3, 4, 0x8001, 0x8002, 0x8003, 0x8010, 0x8011, 0x8012)
                    if (focusMode in afModes) {
                        runOnUiThread { log("Auto-focusing...") }
                        val afSuccess = ptpConnection?.afDrive() ?: false
                        if (!afSuccess) {
                            runOnUiThread {
                                log("AF Failed: Cannot trigger shutter without focus lock.")
                                Toast.makeText(this@MainActivity, "AF Failed: Cannot trigger shutter without focus lock.", Toast.LENGTH_SHORT).show()
                            }
                            return@thread 
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
                                Toast.makeText(this@MainActivity, "Capture Failed: Focus not locked (Camera Blocked).", Toast.LENGTH_LONG).show()
                            } else {
                                log("Capture: Failed (Check Busy/Focus)")
                                Toast.makeText(this@MainActivity, "Capture Rejected: Camera Busy or Out of Focus.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    
                    if (success) {
                        Thread.sleep(3000) 
                    }
                } catch (e: Exception) { 
                    runOnUiThread { log("Capture Error: ${e.message}") } 
                } finally {
                    isPollingPaused = false 
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
                                log("Failed to change mode (Nikon D300 exposure mode is controlled by dial)")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { log("Mode set error: ${e.message}") }
                    }
                    
                    Thread.sleep(500)
                    updateProperties()

                    if (wasLiveViewRunning) {
                        Thread.sleep(500)
                        startLiveView()
                    }
                }
                true
            }
            popup.show()
        }

        // 长按 Mode 按钮：扫描相机属性，用于定位曝光模式到底对应哪个属性码
        modeButton.setOnLongClickListener {
            scanExposureModeProperties()
            true
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
            if (currentApIndex > 0) {
                currentApIndex--
                setAperture(dynamicApertures[currentApIndex].first, dynamicApertures[currentApIndex].second)
            }
        }
        
        apertureDownButton.setOnClickListener {
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
            if (currentShIndex > 0) { 
                currentShIndex--
                setShutter(shutters[currentShIndex].first, shutters[currentShIndex].second)
            }
        }
        
        shutterDownButton.setOnClickListener {
            if (currentShIndex < shutters.size - 1) { 
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

        isoAutoButton.setOnLongClickListener {
            showIsoAutoSettingsDialog()
            true
        }

        focusButton.setOnClickListener {
            thread {
                try {
                    isPollingPaused = true
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

        customSettingsButton.setOnClickListener {
            showCustomSettingsDialog()
        }

        galleryButton.setOnClickListener {
            showGalleryDialog()
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
            customSettingsButton.isEnabled = isConnected
            galleryButton.isEnabled = isConnected
            
            isoUpButton.isEnabled = isConnected
            isoDownButton.isEnabled = isConnected
            apertureUpButton.isEnabled = isConnected
            apertureDownButton.isEnabled = isConnected
            shutterUpButton.isEnabled = isConnected
            shutterDownButton.isEnabled = isConnected
        }
    }

    private fun startLiveView() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        container.addView(TextView(this).apply {
            text = "实时取景流 (Live View Stream)"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        })

        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                (resources.displayMetrics.widthPixels * 0.75).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        container.addView(iv)

        dialog.setContentView(container)
        dialog.setOnDismissListener {
            stopLiveView()
        }
        dialog.show()

        thread {
            try {
                isPollingPaused = true
                if (ptpConnection?.startLiveView() == true) {
                    isLiveViewRunning = true
                    runOnUiThread { 
                        liveViewButton.text = "Stop LV"
                        log("Live View active")
                    }
                    log("步骤 2: 循环发送 0x9203 获取画帧...")
                    while (isLiveViewRunning && dialog.isShowing) {
                        if (!isForeground) {
                            Thread.sleep(300)
                            continue
                        }
                        val bmp = ptpConnection?.getLiveViewFrame()
                        if (bmp != null) {
                            runOnUiThread { iv.setImageBitmap(bmp) }
                        }
                        // 30-50ms sleep gap gives approx 20fps smooth live view stream mapping
                        Thread.sleep(40)
                    }
                } else {
                    runOnUiThread { 
                        log("Live View activation failed")
                        dialog.dismiss()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { 
                    log("LV Error: ${e.message}")
                    dialog.dismiss()
                }
            } finally {
                isPollingPaused = false
            }
        }
    }

    private fun stopLiveView() {
        if (!isLiveViewRunning) return
        isLiveViewRunning = false
        thread {
            try {
                isPollingPaused = true
                ptpConnection?.endLiveView()
                runOnUiThread { 
                    liveViewButton.text = "LV"
                    log("Live View off")
                }
            } catch (e: Exception) { 
                runOnUiThread { log("Stop LV Error: ${e.message}") } 
            } finally {
                isPollingPaused = false
            }
        }
    }

    /**
     * 合并并发的属性刷新请求：同一时刻只允许一个读属性线程运行，
     * 期间到达的请求合并成一次，避免 USB 线程堆积（相机 Busy 重试时会明显放大）。
     */
    private fun updateProperties() {
        synchronized(propertyUpdateLock) {
            if (isPropertyUpdateRunning) {
                isPropertyUpdatePending = true
                return
            }
            isPropertyUpdateRunning = true
        }
        thread {
            while (true) {
                try {
                    readPropertiesFromCamera()
                } catch (e: Exception) {
                    runOnUiThread { log("Prop Error: ${e.message}") }
                }
                val pending = synchronized(propertyUpdateLock) {
                    if (isPropertyUpdatePending) {
                        isPropertyUpdatePending = false
                        true
                    } else {
                        isPropertyUpdateRunning = false
                        false
                    }
                }
                if (!pending) break
            }
        }
    }

    private fun readPropertiesFromCamera() {
        if (!isConnected || isPollingPaused) return 
        try {
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
            val isoAuto = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO) ?: -999999
            Thread.sleep(50)

            val batteryLevel = if (stdBattery >= nikonBattery) stdBattery else nikonBattery

            val supportedAps = ptpConnection?.getDevicePropSupportedValues(PtpConstants.PROP_F_NUMBER)

            runOnUiThread {
                if (batteryLevel != -999999) {
                    batteryText.text = "$batteryLevel%"
                    batteryText.setTextColor(when {
                        batteryLevel > 50 -> Color.parseColor("#2E7D32")
                        batteryLevel > 20 -> Color.parseColor("#F57C00")
                        else -> Color.RED
                    })
                } else {
                    batteryText.text = "--%"
                }

                wbButton.text = "WB"

                isoAutoButton.text = "ISO Auto"
                if (isoAuto != -999999) {
                    isoAutoButton.setBackgroundColor(if (isoAuto == 1) Color.parseColor("#4CAF50") else Color.parseColor("#757575"))
                }

                val apStr = if (aperture != -999999) "f/${aperture/100.0}" else "--"
                val shStr = if (shutter != -999999) {
                    shutterMap[shutter] ?: getShutterString(shutter)
                } else "--"

                val focalStr = if (focalLengthRaw != -999999 && focalLengthRaw > 0) "${focalLengthRaw / 100}mm" else "--"

                val biasStr = if (biasRaw != -999999) {
                    val b = biasRaw.toShort()
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

                isoButton.text = if (iso != -999999 && iso > 0) "$iso" else "ISO"
                apertureButton.text = apStr
                shutterButton.text = shStr
                modeButton.text = exposureProgramLabel(mode)

                propText.text = "Focus: $focusStr | Focal: $focalStr | Bias: $biasStr"

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
                    val deviceInfo = ptpConnection?.readDeviceInfoWithProps()
                    runOnUiThread {
                        if (deviceInfo != null && deviceInfo.first != null) {
                            statusText.text = "Connected: ${deviceInfo.first}"
                            val props = deviceInfo.second
                            val supportsStd = props.contains(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE)
                            if (supportsStd) {
                                log("曝光模式: 相机声明支持 0x500E")
                            } else {
                                log("曝光模式: 相机未声明支持 0x500E，将显示原始属性码以便定位")
                                Log.d("D300_MODE", "0x500E 不在相机支持的属性列表中，共支持 ${props.size} 个")
                                Log.d("D300_MODE", "PROPS=" + props.joinToString(",") { "0x%04X".format(it) })
                            }
                            // 个人设定菜单：同样先看相机是否真的声明了这些属性
                            val csmAll = csmGroups.flatMap { it.items }
                            val csmMissing = csmAll.filter { !props.contains(it.code) }
                            if (csmMissing.isEmpty()) {
                                log("个人设定: ${csmAll.size} 项均被相机声明支持")
                            } else {
                                log("个人设定: 相机未声明支持 ${csmMissing.size}/${csmAll.size} 项")
                                Log.d("D300_CSM", "UNSUPPORTED_BY_CAMERA=" + csmMissing.joinToString(",") { "0x%04X".format(it.code) })
                            }

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
        if (!isConnected && ptpConnection == null) return
        isLiveViewRunning = false
        isPollingActive = false
        isPollingPaused = false
        isConnected = false
        thread {
            ptpConnection?.close() // close() 内部会先发 0x1003 CloseSession
            ptpConnection = null
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
            var lastIsoAuto = -1

            while (isPollingActive && isConnected) {
                // 退到后台（或调用了外部 App 看图）时停止 USB 轮询，省电且避免和前台任务抢总线
                if (isPollingPaused || !isForeground) {
                    Thread.sleep(1000)
                    continue
                }
                try {
                    val focal = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCAL_LENGTH) ?: -999999
                    if (focal != -999999) {
                        val iso = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_INDEX) ?: -999999
                        val ap = ptpConnection?.getDevicePropValue(PtpConstants.PROP_F_NUMBER) ?: -999999
                        val sh = ptpConnection?.getDevicePropValue(activeShutterProp) ?: -999999
                        val mode = ptpConnection?.getDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE) ?: -999999
                        val focus = ptpConnection?.getDevicePropValue(PtpConstants.PROP_FOCUS_MODE) ?: -999999
                        val batteryStd = ptpConnection?.getDevicePropValue(PtpConstants.PROP_BATTERY_LEVEL) ?: -999999
                        val batteryNikon = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_BATTERY_LEVEL) ?: -999999
                        val isoAuto = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO) ?: -999999

                        if (focal != lastFocal || iso != lastIso || ap != lastAp || sh != lastSh || mode != lastMode || focus != lastFocus || 
                            batteryStd != lastBatteryStd || batteryNikon != lastBatteryNikon || isoAuto != lastIsoAuto) {
                            
                            lastFocal = focal
                            if (iso != -999999) lastIso = iso
                            if (ap != -999999) lastAp = ap
                            if (sh != -999999) lastSh = sh
                            if (mode != -999999) lastMode = mode
                            if (focus != -999999) lastFocus = focus
                            if (isoAuto != -999999) lastIsoAuto = isoAuto
                            updateProperties()
                        }
                    }
                } catch (e: Exception) {
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
            if (isFinishing || isDestroyed) return@runOnUiThread
            val stamp = logTimeFormat.format(Date())
            infoText.append("\n[$stamp] $message")
            trimInfoLog()
            logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /** 日志不能无限增长，否则长时间运行会吃掉大量内存 */
    private fun trimInfoLog() {
        val allLines = infoText.text.split("\n")
        if (allLines.size <= MAX_LOG_LINES) return
        infoText.text = allLines.subList(allLines.size - MAX_LOG_LINES, allLines.size).joinToString("\n")
    }

    private fun showIsoAutoSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 60)

            addView(TextView(context).apply {
                text = "ISO Auto Settings (D300)"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            })

            val maxIsoOptions = mapOf(
                0 to "ISO 400",
                1 to "ISO 800",
                2 to "ISO 1600",
                3 to "ISO 3200",
                4 to "Hi 1 (6400)"
            )

            val minShutterOptions = mapOf(
                0 to "1/4000", 1 to "1/3200", 2 to "1/2500", 3 to "1/2000",
                4 to "1/1600", 5 to "1/1250", 6 to "1/1000", 7 to "1/800",
                8 to "1/640", 9 to "1/500", 10 to "1/400", 11 to "1/320",
                12 to "1/250", 13 to "1/200", 14 to "1/160", 15 to "1/125",
                16 to "1/100", 17 to "1/80", 18 to "1/60", 19 to "1/50",
                20 to "1/40", 21 to "1/30", 22 to "1/15", 23 to "1/8",
                24 to "1/4", 25 to "1/2", 26 to "1s"
            )

            // Max ISO Section
            addView(TextView(context).apply { text = "Max Sensitivity (High Limit)" })
            val maxIsoBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                text = "Loading..."
                setOnClickListener { btn ->
                    val popup = android.widget.PopupMenu(context, btn)
                    maxIsoOptions.toSortedMap().forEach { (idx, name) -> popup.menu.add(0, idx, idx, name) }
                    popup.setOnMenuItemClickListener { item ->
                        val index = item.itemId
                        thread {
                            ptpConnection?.setDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO_MAX_ISO, index, size = 1)
                            runOnUiThread { text = maxIsoOptions[index] }
                        }
                        true
                    }
                    popup.show()
                }
            }
            addView(maxIsoBtn)

            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

            // Min Shutter Speed Section
            addView(TextView(context).apply { text = "Min Shutter Speed (P/A Mode)" })
            val minShutterBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                text = "Loading..."
                setOnClickListener { btn ->
                    val popup = android.widget.PopupMenu(context, btn)
                    minShutterOptions.toSortedMap().forEach { (idx, name) -> popup.menu.add(0, idx, idx, name) }
                    popup.setOnMenuItemClickListener { item ->
                        val index = item.itemId
                        thread {
                            ptpConnection?.setDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO_MIN_SHUTTER, index, size = 1)
                            runOnUiThread { text = minShutterOptions[index] }
                        }
                        true
                    }
                    popup.show()
                }
            }
            addView(minShutterBtn)

            // Initial Load
            thread {
                val currentMaxIdx = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO_MAX_ISO) ?: -1
                val currentMinShIdx = ptpConnection?.getDevicePropValue(PtpConstants.PROP_NIKON_ISO_AUTO_MIN_SHUTTER) ?: -1
                
                runOnUiThread {
                    maxIsoBtn.text = maxIsoOptions[currentMaxIdx] ?: (if (currentMaxIdx != -1) "Index $currentMaxIdx" else "Select Max ISO")
                    minShutterBtn.text = minShutterOptions[currentMinShIdx] ?: (if (currentMinShIdx != -1) "Index $currentMinShIdx" else "Select Min Shutter")
                }
            }
        }
        dialog.setContentView(container)
        dialog.show()
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
                4 to "Sunny",
                5 to "Fluorescent",
                6 to "Incandescent",
                7 to "Flash",
                0x8010 to "Cloudy",
                0x8011 to "Sunny shade",
                0x8012 to "Color temp",
                0x8013 to "Preset"
            )

            val gridLayout = android.widget.GridLayout(context).apply {
                columnCount = 2
                alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            }

            wbModes.forEach { pair ->
                val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = pair.second
                    textSize = 14f
                    isAllCaps = false
                    val params = android.widget.GridLayout.LayoutParams().apply {
                        width = 0
                        height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                        setMargins(8, 8, 8, 8)
                    }
                    layoutParams = params
                    setOnClickListener {
                        setWhiteBalance(pair.first, pair.second)
                        dialog.dismiss()
                        if (pair.first == 0x8012) {
                            showColorTempDialog()
                        } else if (pair.first == 0x8013) {
                            showWbPresetDialog()
                        }
                    }
                }
                gridLayout.addView(button)
            }
            addView(gridLayout)
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showColorTempDialog() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 60)
            
            addView(TextView(context).apply {
                text = "Set Color Temperature (K)"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 10)
            })

            addView(TextView(context).apply {
                text = "Range: 2500K - 10000K"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, 30)
            })

            val input = android.widget.EditText(context).apply {
                hint = "Kelvin (2500 - 10000)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("5000")
            }
            addView(input)

            val setBtn = MaterialButton(context).apply {
                text = "SET KELVIN"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 20, 0, 0) }
                setOnClickListener {
                    val kelvinStr = input.text.toString()
                    val kelvin = kelvinStr.toIntOrNull()
                    
                    if (kelvin == null || kelvin < 2500 || kelvin > 10000) {
                        val errorMsg = "Invalid Kelvin: Range is 2500 - 10000"
                        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                        log("Error: $errorMsg (Input: $kelvinStr)")
                        return@setOnClickListener
                    }

                    thread {
                        try {
                            log("Setting Color Temp to ${kelvin}K...")
                            val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_NIKON_WB_COLOR_TEMP, kelvin, size = 2) ?: false
                            runOnUiThread { log("Set Color Temp: ${if (success) "Success" else "Failed"}") }
                            if (success) ptpConnection?.deviceReady()
                        } catch (e: Exception) {
                            runOnUiThread { log("Set Color Temp Error: ${e.message}") }
                        }
                        Thread.sleep(400)
                        updateProperties()
                    }
                    dialog.dismiss()
                }
            }
            addView(setBtn)
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showWbPresetDialog() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 60)
            
            addView(TextView(context).apply {
                text = "Select WB Preset"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            })

            val gridLayout = android.widget.GridLayout(context).apply {
                columnCount = 3
            }

            for (i in 0..4) {
                val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "d-$i"
                    val params = android.widget.GridLayout.LayoutParams().apply {
                        width = 0
                        height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                        setMargins(8, 8, 8, 8)
                    }
                    layoutParams = params
                    setOnClickListener {
                        thread {
                            try {
                                log("Setting WB Preset to d-$i...")
                                val success = ptpConnection?.setDevicePropValue(PtpConstants.PROP_NIKON_WB_PRESET_NO, i, size = 1) ?: false
                                runOnUiThread { log("Set WB Preset: ${if (success) "Success" else "Failed"}") }
                                if (success) ptpConnection?.deviceReady()
                            } catch (e: Exception) {
                                runOnUiThread { log("Set WB Preset Error: ${e.message}") }
                            }
                            Thread.sleep(400)
                            updateProperties()
                        }
                        dialog.dismiss()
                    }
                }
                gridLayout.addView(button)
            }
            addView(gridLayout)
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

    // ===== 个人设定菜单 (CSM) 定义 =====
    // 提到类级别，这样「接入自检」和「菜单读取」用的是同一份属性码清单，不会两边漂移。
    private class CsmItem(val code: Int, val name: String, val options: Map<Int, String>)
    private class CsmGroup(val name: String, val items: List<CsmItem>)

    private val csmGroups = listOf(
        CsmGroup("a: 自动对焦 (Autofocus)", listOf(
            CsmItem(PtpConstants.PROP_NIKON_CSM_A1, "a1: AF-C 优先选择", mapOf(0 to "快门释放", 1 to "对焦", 2 to "释放 + 对焦")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_A2, "a2: AF-S 优先选择", mapOf(0 to "对焦", 1 to "快门释放")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_A5, "a5: 触发自动对焦", mapOf(0 to "快门/AF-ON", 1 to "仅 AF-ON")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_A7, "a7: 对焦点循环方式", mapOf(0 to "不循环", 1 to "循环"))
        )),
        CsmGroup("b: 测光/曝光 (Metering/Exposure)", listOf(
            CsmItem(PtpConstants.PROP_NIKON_CSM_B1, "b1: 感光度步长值", mapOf(0 to "1/3 步长", 1 to "1/2 步长", 2 to "1 步长")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_B2, "b2: 曝光控制 EV 步长", mapOf(0 to "1/3 步长", 1 to "1/2 步长", 2 to "1 步长")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_B3, "b3: 曝光补偿/微调", mapOf(0 to "1/3 步长", 1 to "1/2 步长", 2 to "1 步长")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_B4, "b4: 简易曝光补偿", mapOf(0 to "开启 (自动重设)", 1 to "开启", 2 to "关闭")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_B5, "b5: 中央重点区域", mapOf(0 to "6mm", 1 to "8mm", 2 to "10mm", 3 to "13mm", 4 to "平均"))
        )),
        CsmGroup("c: 计时/AE 锁定 (Timers/AE Lock)", listOf(
            CsmItem(PtpConstants.PROP_NIKON_CSM_C1, "c1: 快门释放按钮 AE-L", mapOf(0 to "开启", 1 to "关闭")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_C2, "c2: 自动测光关闭延迟", mapOf(0 to "4秒", 1 to "6秒", 2 to "8秒", 3 to "16秒", 4 to "30秒", 5 to "1分钟", 6 to "5分钟", 7 to "10分钟", 8 to "30分钟", 9 to "无限")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_C3, "c3: 自拍延迟", mapOf(0 to "2秒", 1 to "5秒", 2 to "10秒", 3 to "20秒")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_C4, "c4: 显示屏关闭延迟", mapOf(0 to "5秒", 1 to "10秒", 2 to "20秒", 3 to "1分钟", 4 to "10分钟"))
        )),
        CsmGroup("d: 拍摄/显示 (Shooting/Display)", listOf(
            CsmItem(PtpConstants.PROP_NIKON_CSM_D3, "d3: 蜂鸣音", mapOf(0 to "高", 1 to "低", 2 to "关闭")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_D6, "d6: 文件编号次序", mapOf(0 to "开启", 1 to "关闭", 2 to "重设")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_D7, "d7: 拍摄信息显示", mapOf(0 to "自动", 1 to "手动"))
        )),
        CsmGroup("e: 包围/闪光 (Bracketing/Flash)", listOf(
            CsmItem(PtpConstants.PROP_NIKON_CSM_E1, "e1: 闪光同步速度", mapOf(0 to "1/320秒 (自动FP)", 1 to "1/250秒 (自动FP)", 2 to "1/250秒", 3 to "1/200秒", 4 to "1/160秒", 5 to "1/125秒", 6 to "1/100秒", 7 to "1/80秒", 8 to "1/60秒")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_E2, "e2: 闪光快门速度", mapOf(0 to "1/60秒", 1 to "1/30秒", 2 to "1/15秒", 3 to "1/8秒", 4 to "1/4秒", 5 to "1/2秒", 6 to "1秒", 7 to "2秒", 8 to "4秒", 9 to "8秒", 10 to "15秒", 11 to "30秒")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_E4, "e4: 模拟闪光", mapOf(0 to "开启", 1 to "关闭")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_E5, "e5: 自动包围设定", mapOf(0 to "曝光和闪光", 1 to "仅曝光", 2 to "仅闪光", 3 to "白平衡包围", 4 to "动态D-Lighting包围")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_E7, "e7: 包围顺序", mapOf(0 to "量度->不足->过度", 1 to "不足->量度->过度"))
        )),
        CsmGroup("f: 控制 (Controls)", listOf(
            CsmItem(PtpConstants.PROP_NIKON_CSM_F2, "f2: 多重选择器", mapOf(0 to "激活非工作测光", 1 to "无作动")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_F4, "f4: 指定 FUNC. 按钮", mapOf(0 to "FV锁定", 1 to "AE/AF锁定", 2 to "仅AE锁定", 3 to "闪光关闭")),
            CsmItem(PtpConstants.PROP_NIKON_CSM_F7, "f7: 自定义指令拨盘", mapOf(0 to "反转旋转关闭", 1 to "反转旋转开启"))
        ))
    )

    private fun showCustomSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        
        val groups = csmGroups

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        rootLayout.addView(TextView(this).apply {
            text = "个人设定菜单 (Custom Settings)"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val buttonMap = mutableMapOf<MaterialButton, CsmItem>()

        for (group in groups) {
            itemsContainer.addView(TextView(this).apply {
                text = group.name
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1976D2"))
                setPadding(0, 20, 0, 10)
            })

            for (item in group.items) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10, 10, 10, 10)
                }

                row.addView(TextView(this).apply {
                    text = item.name
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "读取中..."
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setOnClickListener { button ->
                        val popup = android.widget.PopupMenu(context, button)
                        item.options.toSortedMap().forEach { (idx, label) ->
                            popup.menu.add(0, idx, idx, label)
                        }
                        popup.setOnMenuItemClickListener { menuItem ->
                            val selectedIdx = menuItem.itemId
                            thread {
                                isPollingPaused = true
                                val success = ptpConnection?.setDevicePropValue(item.code, selectedIdx, size = 1) ?: false
                                
                                // Automatically sync/reread from the camera to verify internal state updated successfully
                                val verifiedVal = ptpConnection?.getDevicePropValue(item.code) ?: -1
                                
                                isPollingPaused = false
                                runOnUiThread {
                                    if (success && verifiedVal != -999999 && verifiedVal != -1) {
                                        text = item.options[verifiedVal] ?: "选项 $verifiedVal"
                                    } else if (success) {
                                        text = item.options[selectedIdx] ?: "未知值 ($selectedIdx)"
                                    } else {
                                        log("修改 ${item.name} 失败")
                                    }
                                }
                            }
                            true
                        }
                        popup.show()
                    }
                }
                buttonMap[btn] = item
                row.addView(btn)
                itemsContainer.addView(row)
            }
        }

        scroll.addView(itemsContainer)
        rootLayout.addView(scroll)
        dialog.setContentView(rootLayout)

        // Load values asynchronously
        // 先在主线程暂停轮询再开线程，避免和 2 秒一次的轮询线程抢同一条 USB 总线
        isPollingPaused = true
        thread {
            try {
                for ((btn, item) in buttonMap) {
                    if (!isConnected) break
                    var result = ptpConnection?.readDeviceProp(item.code) ?: break
                    if (result.value == -999999) {
                        Thread.sleep(120)                      // 相机可能是瞬时 Busy，给一次复活机会
                        result = ptpConnection?.readDeviceProp(item.code) ?: break
                    }
                    val value = result.value
                    runOnUiThread {
                        btn.text = if (value != -999999) {
                            item.options[value] ?: "选项 $value"
                        } else {
                            describePropFailure(result)
                        }
                    }
                    if (value == -999999) {
                        Log.d(
                            "D300_CSM",
                            "READ 0x%04X (%s) failed: resp=0x%04X payload=%d transportOk=%s"
                                .format(item.code, item.name, result.responseCode, result.payloadSize, result.transportOk)
                        )
                        log("个人设定 ${item.name} 读取失败 (0x${Integer.toHexString(item.code).uppercase()} 响应 0x${Integer.toHexString(result.responseCode).uppercase()})")
                    }
                    Thread.sleep(60)
                }
            } finally {
                isPollingPaused = false
            }
        }

        dialog.show()
    }

    /** 把 PTP 响应码翻译成人能看懂的原因，别再一律显示“不可用” */
    private fun describePropFailure(result: PtpUsbConnection.PropReadResult): String = when (result.responseCode) {
        0x200A -> "相机不支持"
        0x2019 -> "相机忙"
        0x200F -> "被拒绝访问"
        0x0000 -> "传输失败"
        else -> "失败 0x${Integer.toHexString(result.responseCode).uppercase()}"
    }

    private fun showGalleryDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_gallery, null)
        dialog.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.galleryRecycler)
        val emptyText = view.findViewById<TextView>(R.id.emptyText)
        val countText = view.findViewById<TextView>(R.id.countText)
        val progressBar = view.findViewById<ProgressBar>(R.id.thumbProgress)

        lateinit var adapter: GalleryAdapter
        adapter = GalleryAdapter(
            onItemClick = { photo, _ -> showPhotoSheet(photo, adapter) },
            onItemLongClick = { photo, position -> confirmDeletePhoto(photo, adapter, position, null) }
        )
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateGalleryCount()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateGalleryCount()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateGalleryCount()
            private fun updateGalleryCount() {
                countText.text = "${adapter.itemCount} 张照片"
                emptyText.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            }
        })

        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.setHasFixedSize(false)
        recycler.adapter = adapter

        val token = ++galleryLoadToken
        dialog.setOnDismissListener { galleryLoadToken++ } // 关闭相册即取消未完成的加载任务
        dialog.show()

        loadGalleryPhotos(adapter, emptyText, progressBar, token)
    }

    private fun loadGalleryPhotos(
        adapter: GalleryAdapter,
        emptyText: TextView,
        progressBar: ProgressBar,
        token: Int
    ) {
        thread {
            isPollingPaused = true
            try {
                val handles = if (galleryLoadToken == token) ptpConnection?.getObjectHandles() else null
                val ordered = handles?.reversedArray() // 最新拍摄的排在最前
                if (ordered == null || ordered.isEmpty()) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        emptyText.visibility = View.VISIBLE
                        Toast.makeText(this@MainActivity, "未发现任何照片或读取失败", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                runOnUiThread {
                    progressBar.max = ordered.size
                    progressBar.progress = 0
                    progressBar.visibility = View.VISIBLE
                }

                galleryLoop@ for ((index, handle) in ordered.withIndex()) {
                    if (galleryLoadToken != token) break@galleryLoop

                    val info = ptpConnection?.getObjectInfo(handle)
                    val photo = GalleryPhoto(
                        handle = handle,
                        fileName = info?.first ?: "IMG_${handle}.JPG",
                        byteSize = info?.second ?: 0L
                    )
                    runOnUiThread { adapter.addPhotos(listOf(photo)) }

                    if (galleryLoadToken != token) break@galleryLoop
                    val thumb = ptpConnection?.getThumb(handle)
                    runOnUiThread {
                        adapter.setThumbAt(index, thumb)
                        progressBar.progress = index + 1
                    }
                }
                runOnUiThread { log("Gallery: ${ordered.size} 张照片加载完成") }
            } catch (e: Exception) {
                runOnUiThread { log("Gallery Error: ${e.message}") }
            } finally {
                isPollingPaused = false
                runOnUiThread { progressBar.visibility = View.GONE }
            }
        }
    }

    private fun showPhotoSheet(photo: GalleryPhoto, adapter: GalleryAdapter) {
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.widthPixels * 0.9).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            photo.thumb?.let { setImageBitmap(it) }
        }
        container.addView(imageView)

        val statusText = TextView(this).apply {
            text = "${photo.fileName}\n正在下载原图..."
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 12)
        }
        container.addView(statusText)

        val loadingBar = ProgressBar(this).apply { setPadding(0, 0, 0, 20) }
        container.addView(loadingBar)

        val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun actionButton(label: String): MaterialButton = MaterialButton(this).apply {
            text = label
            isEnabled = false
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(4, 0, 4, 0) }
        }
        val viewBtn = actionButton("打开")
        val saveBtn = actionButton("保存")
        val shareBtn = actionButton("分享")
        actionsRow.addView(viewBtn)
        actionsRow.addView(saveBtn)
        actionsRow.addView(shareBtn)
        container.addView(actionsRow)

        val deleteBtn = MaterialButton(this).apply {
            text = "从相机删除"
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 24, 4, 0) }
            setOnClickListener {
                confirmDeletePhoto(photo, adapter, adapter.indexOfHandle(photo.handle), sheet)
            }
        }
        container.addView(deleteBtn)

        sheet.setContentView(container)
        sheet.show()

        thread {
            isPollingPaused = true
            val bytes = ptpConnection?.getObjectData(photo.handle)
            isPollingPaused = false
            runOnUiThread {
                loadingBar.visibility = View.GONE
                if (bytes == null) {
                    statusText.text = "${photo.fileName}\n原图下载失败"
                    return@runOnUiThread
                }
                statusText.text = "${photo.fileName}\n${formatFileSize(bytes.size.toLong())}"
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { imageView.setImageBitmap(it) }

                viewBtn.isEnabled = true
                saveBtn.isEnabled = true
                shareBtn.isEnabled = true
                viewBtn.setOnClickListener { openPhotoWithExternalApp(photo, bytes) }
                saveBtn.setOnClickListener { savePhoto(photo, bytes) }
                shareBtn.setOnClickListener { sharePhoto(photo, bytes) }
            }
        }
    }

    private fun cachePhotoFile(photo: GalleryPhoto, data: ByteArray): File? {
        return try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            File(dir, photo.fileName).apply { writeBytes(data) }
        } catch (e: Exception) {
            log("Cache failed: ${e.message}")
            null
        }
    }

    private fun photoMimeType(fileName: String): String = when {
        fileName.endsWith(".nef", true) -> "image/x-nikon-nef"
        fileName.endsWith(".tif", true) || fileName.endsWith(".tiff", true) -> "image/tiff"
        fileName.endsWith(".png", true) -> "image/png"
        else -> "image/jpeg"
    }

    private fun openPhotoWithExternalApp(photo: GalleryPhoto, data: ByteArray) {
        val file = cachePhotoFile(photo, data) ?: run {
            Toast.makeText(this, "打开失败", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, FILE_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, photoMimeType(photo.fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "没有可打开该照片的应用", Toast.LENGTH_SHORT).show()
            log("Open photo failed: ${e.message}")
        }
    }

    private fun sharePhoto(photo: GalleryPhoto, data: ByteArray) {
        val file = cachePhotoFile(photo, data) ?: run {
            Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, FILE_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = photoMimeType(photo.fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享照片"))
    }

    private fun savePhoto(photo: GalleryPhoto, data: ByteArray) {
        val action = { doSavePhotoToGallery(photo, data) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingStorageAction = action
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        action()
    }

    private fun doSavePhotoToGallery(photo: GalleryPhoto, data: ByteArray) {
        thread {
            try {
                val fileName = photo.fileName
                val mimeType = photoMimeType(fileName)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/D300Controller")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = contentResolver.insert(collection, values)
                        ?: throw IllegalStateException("MediaStore insert 失败")
                    contentResolver.openOutputStream(uri)?.use { it.write(data) }
                        ?: throw IllegalStateException("无法写入流")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "D300Controller"
                    ).apply { mkdirs() }
                    val file = File(dir, fileName)
                    file.writeBytes(data)
                    @Suppress("DEPRECATION")
                    sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
                }

                runOnUiThread {
                    log("已保存到相册: Pictures/D300Controller/$fileName")
                    Toast.makeText(
                        this@MainActivity,
                        "已保存到相册: Pictures/D300Controller/$fileName",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    log("保存失败: ${e.message}")
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDeletePhoto(
        photo: GalleryPhoto,
        adapter: GalleryAdapter,
        position: Int,
        sheet: BottomSheetDialog?
    ) {
        AlertDialog.Builder(this)
            .setTitle("删除照片")
            .setMessage("确定要从相机的存储卡删除 ${photo.fileName} 吗？此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deletePhoto(photo, adapter, position, sheet) }
            .show()
    }

    private fun deletePhoto(photo: GalleryPhoto, adapter: GalleryAdapter, position: Int, sheet: BottomSheetDialog?) {
        thread {
            isPollingPaused = true
            val deleted = try {
                ptpConnection?.deleteObject(photo.handle) ?: false
            } finally {
                isPollingPaused = false
            }
            runOnUiThread {
                if (deleted) {
                    val pos = if (position >= 0) position else adapter.indexOfHandle(photo.handle)
                    if (pos >= 0) adapter.removeAt(pos)
                    log("已删除 ${photo.fileName}")
                    sheet?.dismiss()
                } else {
                    Toast.makeText(this@MainActivity, "删除失败，相机可能正忙", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "--"
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /**
     * 长按 Mode 按钮触发：把相机「声明支持」的设备属性连同当前值写入 logcat（tag = D300_SCAN）。
     * 用途：确定这台 D300 到底用哪个属性码暴露 M/A/S/P，
     * 而不是凭经验猜。把拨盘转到不同档位各扫一次，比对哪个属性码变了即可。
     */
    private fun scanExposureModeProperties() {
        Toast.makeText(this, "正在扫描相机属性，请稍候...", Toast.LENGTH_SHORT).show()
        thread {
            isPollingPaused = true
            val startedAt = System.currentTimeMillis()
            try {
                val codes = ptpConnection?.readDeviceInfoWithProps()?.second ?: IntArray(0)
                log("属性扫描开始: ${codes.size} 个属性")
                Log.d("D300_SCAN", "=== BEGIN count=${codes.size} ===")
                Log.d("D300_SCAN", "PROPS=" + codes.joinToString(",") { "0x%04X".format(it) })
                for (code in codes) {
                    if (!isConnected || System.currentTimeMillis() - startedAt > 90_000) break
                    val value = ptpConnection?.getDevicePropValue(code) ?: -999999
                    Log.d("D300_SCAN", "VAL 0x%04X = %d".format(code, value))
                }
                Log.d("D300_SCAN", "=== END ===")
                runOnUiThread { log("属性扫描完成，请执行: adb logcat -s D300_SCAN") }
            } catch (e: Exception) {
                runOnUiThread { log("扫描失败: ${e.message}") }
            } finally {
                isPollingPaused = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
    }

    override fun onPause() {
        isForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
        isPollingActive = false
        isLiveViewRunning = false
        isConnected = false
        galleryLoadToken++
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        thread { ptpConnection?.close(); ptpConnection = null }
    }
}
