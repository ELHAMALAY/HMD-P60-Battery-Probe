package com.example.hmdp60probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.Gravity
import android.widget.*
import java.util.UUID

class MainActivity : Activity() {
    private val requestCode = 44
    private lateinit var adapter: BluetoothAdapter
    private lateinit var scanner: BluetoothLeScanner
    private lateinit var status: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var details: TextView
    private lateinit var logBox: TextView
    private var gatt: BluetoothGatt? = null
    private val found = linkedMapOf<String, ScanResult>()
    private val batteryService = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val batteryLevel = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread {
                val key = result.device.address
                found[key] = result
                renderDevices()
                log("ADV ${result.device.name ?: "N/A"}  $key  RSSI ${result.rssi} dBm")
                result.scanRecord?.serviceUuids?.forEach { log("  ADV UUID ${it.uuid}") }
                result.scanRecord?.manufacturerSpecificData?.let { data ->
                    for (i in 0 until data.size()) {
                        val id = data.keyAt(i)
                        val bytes = data.valueAt(i)
                        log("  Manufacturer $id: ${bytes.toHex()}")
                    }
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { status.text = "Scan failed: $errorCode" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        scanner = adapter.bluetoothLeScanner
        buildUi()
        requestNeededPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        status = TextView(this).apply { text = "Ready. Turn on Bluetooth."; textSize = 16f }
        root.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply { text = "SCAN 12s"; setOnClickListener { startScan() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply { text = "STOP"; setOnClickListener { stopScan() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        root.addView(TextView(this).apply { text = "Nearby BLE devices — tap one to connect"; textSize = 17f })
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesBox)
        root.addView(TextView(this).apply { text = "GATT services / characteristics"; textSize = 17f })
        details = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        val scrollDetails = ScrollView(this); scrollDetails.addView(details)
        root.addView(scrollDetails, LinearLayout.LayoutParams(-1, 0, 2f))
        root.addView(TextView(this).apply { text = "Log"; textSize = 17f })
        logBox = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        val scrollLog = ScrollView(this); scrollLog.addView(logBox)
        root.addView(scrollLog, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun requestNeededPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), requestCode)
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPermissions()) { requestNeededPermissions(); return }
        if (!adapter.isEnabled) { status.text = "Enable Bluetooth first."; return }
        found.clear(); devicesBox.removeAllViews()
        status.text = "Scanning for 12 seconds…"
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        devicesBox.postDelayed({ stopScan() }, 12_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (hasPermissions()) try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
        status.text = "Scan stopped. Found ${found.size} device(s)."
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31)
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun renderDevices() {
        devicesBox.removeAllViews()
        found.values.sortedByDescending { it.rssi }.forEach { result ->
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "N/A"
            val uuids = result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString() } ?: "No advertised UUIDs"
            val b = Button(this).apply {
                text = "$name\n${result.device.address}  |  ${result.rssi} dBm\n$uuids"
                gravity = Gravity.START
                setOnClickListener { connect(result.device) }
            }
            devicesBox.addView(b)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!hasPermissions()) { requestNeededPermissions(); return }
        gatt?.close()
        details.text = "Connecting to ${device.address}…"
        status.text = "Connecting…"
        gatt = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            runOnUiThread {
                status.text = "GATT status=$statusCode state=${if (newState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else "DISCONNECTED"}"
                log("GATT connection status=$statusCode state=$newState")
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            runOnUiThread {
                val out = StringBuilder()
                out.append("Discovery status: $statusCode\n")
                g.services.forEach { service ->
                    out.append("\nSERVICE ${service.uuid} type=${service.type}\n")
                    service.characteristics.forEach { ch ->
                        val props = mutableListOf<String>()
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("INDICATE")
                        out.append("  CHAR ${ch.uuid} [${props.joinToString()}]\n")
                        if (ch.uuid == batteryLevel) out.append("  ^ STANDARD BATTERY LEVEL\n")
                    }
                }
                if (g.services.isEmpty()) out.append("No GATT services exposed.\n")
                details.text = out.toString()
                log("Discovered ${g.services.size} GATT services")
                g.services.flatMap { it.characteristics }.forEach { ch ->
                    if (ch.uuid == batteryLevel && ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        g.readCharacteristic(ch)
                    }
                }
            }
        }

        @Deprecated("Deprecated in Android API")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                val bytes = characteristic.value ?: byteArrayOf()
                runOnUiThread { log("READ ${characteristic.uuid}: ${bytes.toHex()} ${if (characteristic.uuid == batteryLevel && bytes.isNotEmpty()) "=> ${bytes[0].toInt() and 0xff}%" else ""}") }
            } else runOnUiThread { log("READ failed ${characteristic.uuid} status=$statusCode") }
        }

        @Deprecated("Deprecated in Android API")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: byteArrayOf()
            runOnUiThread { log("NOTIFY ${characteristic.uuid}: ${bytes.toHex()}") }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            runOnUiThread { log("Descriptor write ${descriptor.uuid} status=$statusCode") }
        }
    }

    private fun log(message: String) {
        logBox.append(message + "\n")
    }

    override fun onDestroy() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
