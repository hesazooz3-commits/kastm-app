package com.example.btpairpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity(), DeviceAdapter.OnDeviceClickListener {
    private val TAG = "MainActivity"

    private lateinit var btnScan: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnCancelAttempt: MaterialButton
    private lateinit var rvDevices: RecyclerView
    private lateinit var etAttempts: EditText
    private lateinit var tvCounters: TextView
    private lateinit var tvLog: TextView

    private val adapter by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val devices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceAdapter
    private var selectedDevice: BluetoothDevice? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (it.bondState != BluetoothDevice.BOND_BONDED && devices.none { d -> d.address == it.address }) {
                            devices.add(it)
                            deviceAdapter.submitList(devices.toList())
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    appendLog("Bond state: ${device?.address} $prev -> $state")
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    appendLog("Global pairing request: ${device?.address}")
                }
                ACTION_SERVICE_UPDATE -> {
                    val msg = intent.getStringExtra(EXTRA_LOG) ?: return
                    appendLog(msg)
                    val done = intent.getIntExtra(EXTRA_DONE, -1)
                    val success = intent.getIntExtra(EXTRA_SUCCESS, -1)
                    val failed = intent.getIntExtra(EXTRA_FAILED, -1)
                    val canceled = intent.getIntExtra(EXTRA_CANCELED, -1)
                    if (done >= 0) updateCounters(done, success, failed, canceled)
                }
            }
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            startDiscovery()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btn_scan)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnCancelAttempt = findViewById(R.id.btn_cancel_attempt)
        rvDevices = findViewById(R.id.rv_devices)
        etAttempts = findViewById(R.id.et_attempts)
        tvCounters = findViewById(R.id.tv_counters)
        tvLog = findViewById(R.id.tv_log)

        deviceAdapter = DeviceAdapter(this)
        rvDevices.adapter = deviceAdapter
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        btnScan.setOnClickListener { requestScanPermissionsAndDiscover() }
        btnStart.setOnClickListener { startProcess() }
        btnStop.setOnClickListener { stopProcess() }
        btnCancelAttempt.setOnClickListener { sendCancelCurrentAttempt() }

        registerReceiver()
        updateCounters(0, 0, 0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(btReceiver)
        } catch (_: Exception) {
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(ACTION_SERVICE_UPDATE)
        }
        registerReceiver(btReceiver, filter)
    }

    private fun requestScanPermissionsAndDiscover() {
        if (adapter == null) {
            Snackbar.make(findViewById(android.R.id.content), "Bluetooth غير مدعوم على هذا الجهاز", Snackbar.LENGTH_LONG).show()
            return
        }

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun startDiscovery() {
        devices.clear()
        deviceAdapter.submitList(devices.toList())
        adapter?.let {
            if (it.isDiscovering) it.cancelDiscovery()
            val started = it.startDiscovery()
            appendLog("Discovery started: $started")
        }
    }

    private fun startProcess() {
        val device = selectedDevice
        if (device == null) {
            Snackbar.make(findViewById(android.R.id.content), "اختر جهازاً من القائمة أولاً", Snackbar.LENGTH_SHORT).show()
            return
        }

        val attempts = etAttempts.text.toString().toIntOrNull()
        if (attempts == null || attempts <= 0) {
            Snackbar.make(findViewById(android.R.id.content), "ادخل عدد محاولات صالح", Snackbar.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, PairingService::class.java).apply {
            putExtra(EXTRA_DEVICE_ADDRESS, device.address)
            putExtra(EXTRA_ATTEMPTS, attempts)
            putExtra(EXTRA_TIMEOUT_MS, 20000L)
            putExtra(EXTRA_DELAY_MS, 800L)
            putExtra(EXTRA_AUTO_CANCEL_ON_PAIRING_REQUEST, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        appendLog("Service started for ${device.address} with $attempts attempts")
    }

    private fun stopProcess() {
        val intent = Intent(this, PairingService::class.java)
        stopService(intent)
        appendLog("Service stop requested")
    }

    private fun sendCancelCurrentAttempt() {
        val intent = Intent(ACTION_COMMAND)
        intent.putExtra(EXTRA_COMMAND, COMMAND_CANCEL_CURRENT)
        sendBroadcast(intent)
        appendLog("Cancel current attempt requested")
    }

    override fun onDeviceClick(device: BluetoothDevice) {
        selectedDevice = device
        Snackbar.make(findViewById(android.R.id.content), "Selected: ${device.name ?: "Unknown"}", Snackbar.LENGTH_SHORT).show()
    }

    private fun updateCounters(done: Int, success: Int, failed: Int, canceled: Int) {
        tvCounters.text = "محاولات: $done  | نجحت: $success  | فشل: $failed  | ملغية: $canceled"
    }

    private fun appendLog(text: String) {
        Log.d(TAG, text)
        runOnUiThread {
            tvLog.append("${text}\n")
            (tvLog.parent as? ScrollView)?.post {
                (tvLog.parent as ScrollView).fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    companion object {
        const val ACTION_SERVICE_UPDATE = "com.example.btpairpro.SERVICE_UPDATE"
        const val EXTRA_LOG = "extra_log"
        const val EXTRA_DONE = "extra_done"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_FAILED = "extra_failed"
        const val EXTRA_CANCELED = "extra_canceled"

        const val ACTION_COMMAND = "com.example.btpairpro.ACTION_COMMAND"
        const val EXTRA_COMMAND = "extra_command"
        const val COMMAND_CANCEL_CURRENT = 1

        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_ATTEMPTS = "extra_attempts"
        const val EXTRA_TIMEOUT_MS = "extra_timeout_ms"
        const val EXTRA_DELAY_MS = "extra_delay_ms"
        const val EXTRA_AUTO_CANCEL_ON_PAIRING_REQUEST = "extra_auto_cancel"
    }
}
