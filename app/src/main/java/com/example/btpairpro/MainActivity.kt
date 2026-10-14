package com.example.btpairpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity(), DeviceAdapter.OnDeviceClickListener {
    private val TAG = "MainActivity"

    private lateinit var btnScan: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnCancelAttempt: MaterialButton
    private lateinit var btnSettings: ImageButton
    private lateinit var rvDevices: RecyclerView
    private lateinit var etAttempts: EditText
    private lateinit var tvCounters: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvSessionStatus: TextView
    private lateinit var tvScanStatus: TextView
    private lateinit var statAttempts: TextView
    private lateinit var statSuccess: TextView
    private lateinit var statFailed: TextView
    private lateinit var statCancelled: TextView
    private lateinit var statusBar: LinearLayout

    private val adapter by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val devices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceAdapter
    private var selectedDevice: BluetoothDevice? = null
    private lateinit var prefs: SharedPreferences

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
                            tvScanStatus.text = "تم العثور على ${devices.size} جهاز" 
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
                    val msg = intent.getStringExtra(EXTRA_LOG)
                    if (msg != null) appendLog(msg)
                    val done = intent.getIntExtra(EXTRA_DONE, -1)
                    val success = intent.getIntExtra(EXTRA_SUCCESS, -1)
                    val failed = intent.getIntExtra(EXTRA_FAILED, -1)
                    val canceled = intent.getIntExtra(EXTRA_CANCELED, -1)
                    if (done >= 0) updateCounters(done, success, failed, canceled)
                }
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> startDiscovery() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("kastm_settings", MODE_PRIVATE)

        btnScan = findViewById(R.id.btn_scan)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnCancelAttempt = findViewById(R.id.btn_cancel_attempt)
        btnSettings = findViewById(R.id.btn_settings)
        rvDevices = findViewById(R.id.rv_devices)
        etAttempts = findViewById(R.id.et_attempts)
        tvCounters = findViewById(R.id.tv_counters)
        tvLog = findViewById(R.id.tv_log)
        tvSessionStatus = findViewById(R.id.tv_session_status)
        tvScanStatus = findViewById(R.id.tv_scan_status)
        statAttempts = findViewById(R.id.stat_attempts)
        statSuccess = findViewById(R.id.stat_success)
        statFailed = findViewById(R.id.stat_failed)
        statCancelled = findViewById(R.id.stat_cancelled)
        statusBar = findViewById(R.id.status_bar)

        etAttempts.setText(prefs.getString("default_attempts", "10"))

        deviceAdapter = DeviceAdapter(this)
        rvDevices.adapter = deviceAdapter
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        btnScan.setOnClickListener { requestScanPermissionsAndDiscover() }
        btnStart.setOnClickListener { startProcess() }
        btnStop.setOnClickListener { stopProcess() }
        btnCancelAttempt.setOnClickListener { sendCancelCurrentAttempt() }
        btnSettings.setOnClickListener { showSettingsDialog() }

        registerReceiver()
        updateCounters(0, 0, 0, 0)
        updateSummaryCards(0, 0, 0, 0)
        tvSessionStatus.text = "جاهز"
        tvScanStatus.text = "لم يتم البحث بعد"
        statusBar.setBackgroundColor(getColor(R.color.status_idle))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
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
        tvScanStatus.text = "جارٍ البحث..."
        statusBar.setBackgroundColor(getColor(R.color.status_scanning))

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

        val timeoutMs = prefs.getLong("timeout_ms", 20000L)
        val delayMs = prefs.getLong("delay_ms", 800L)
        val autoCancel = prefs.getBoolean("auto_cancel", true)
        val removeBondAfterSuccess = prefs.getBoolean("remove_bond_after_success", true)

        prefs.edit {
            putString("default_attempts", attempts.toString())
        }

        val intent = Intent(this, PairingService::class.java).apply {
            putExtra(EXTRA_DEVICE_ADDRESS, device.address)
            putExtra(EXTRA_ATTEMPTS, attempts)
            putExtra(EXTRA_TIMEOUT_MS, timeoutMs)
            putExtra(EXTRA_DELAY_MS, delayMs)
            putExtra(EXTRA_AUTO_CANCEL_ON_PAIRING_REQUEST, autoCancel)
            putExtra(EXTRA_REMOVE_BOND_AFTER_SUCCESS, removeBondAfterSuccess)
        }

        tvSessionStatus.text = "جارٍ التشغيل"
        statusBar.setBackgroundColor(getColor(R.color.status_running))

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
        tvSessionStatus.text = "تم الإيقاف"
        statusBar.setBackgroundColor(getColor(R.color.status_idle))
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
        updateSummaryCards(done, success, failed, canceled)
    }

    private fun updateSummaryCards(done: Int, success: Int, failed: Int, canceled: Int) {
        statAttempts.text = done.toString()
        statSuccess.text = success.toString()
        statFailed.text = failed.toString()
        statCancelled.text = canceled.toString()
    }

    private fun appendLog(text: String) {
        Log.d(TAG, text)
        runOnUiThread {
            tvLog.append("${text}\n")
            (tvLog.parent as? ScrollView)?.post {
                (tvLog.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null, false)

        val timeoutField = view.findViewById<EditText>(R.id.et_timeout)
        val delayField = view.findViewById<EditText>(R.id.et_delay)
        val autoCancelSwitch = view.findViewById<SwitchMaterial>(R.id.switch_auto_cancel)
        val removeBondSwitch = view.findViewById<SwitchMaterial>(R.id.switch_remove_bond)

        timeoutField.setText(prefs.getLong("timeout_ms", 20000L).toString())
        delayField.setText(prefs.getLong("delay_ms", 800L).toString())
        autoCancelSwitch.isChecked = prefs.getBoolean("auto_cancel", true)
        removeBondSwitch.isChecked = prefs.getBoolean("remove_bond_after_success", true)

        AlertDialog.Builder(this)
            .setTitle("إعدادات التطبيق")
            .setView(view)
            .setPositiveButton("حفظ") { _, _ ->
                val timeout = timeoutField.text.toString().toLongOrNull() ?: 20000L
                val delay = delayField.text.toString().toLongOrNull() ?: 800L
                prefs.edit {
                    putLong("timeout_ms", timeout)
                    putLong("delay_ms", delay)
                    putBoolean("auto_cancel", autoCancelSwitch.isChecked)
                    putBoolean("remove_bond_after_success", removeBondSwitch.isChecked)
                }
                Snackbar.make(findViewById(android.R.id.content), "تم حفظ الإعدادات", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
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
        const val EXTRA_REMOVE_BOND_AFTER_SUCCESS = "remove_bond_after_success"
    }
}
