package com.example.btpairpro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.atomic.AtomicBoolean

class PairingService : Service() {
    private val TAG = "PairingService"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val adapter by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var running = AtomicBoolean(false)
    private var cancelCurrentAttempt = AtomicBoolean(false)

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getIntExtra(MainActivity.EXTRA_COMMAND, -1) ?: -1
            if (command == MainActivity.COMMAND_CANCEL_CURRENT) {
                cancelCurrentAttempt.set(true)
                broadcastLog("Manual cancel requested")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(commandReceiver, IntentFilter(MainActivity.ACTION_COMMAND))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(commandReceiver)
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val address = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ADDRESS)
        if (address == null) {
            broadcastLog("Device address missing")
            return START_NOT_STICKY
        }

        val attempts = intent.getIntExtra(MainActivity.EXTRA_ATTEMPTS, 1)
        val timeoutMs = intent.getLongExtra(MainActivity.EXTRA_TIMEOUT_MS, 20000L)
        val delayMs = intent.getLongExtra(MainActivity.EXTRA_DELAY_MS, 800L)
        val autoCancel = intent.getBooleanExtra(MainActivity.EXTRA_AUTO_CANCEL_ON_PAIRING_REQUEST, true)

        val device = adapter?.getRemoteDevice(address)
        if (device == null) {
            broadcastLog("Device not found: $address")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        if (running.get()) {
            broadcastLog("Service already running")
            return START_STICKY
        }

        running.set(true)
        cancelCurrentAttempt.set(false)

        scope.launch {
            var done = 0
            var success = 0
            var failed = 0
            var canceled = 0
            broadcastProgress(done, success, failed, canceled)

            for (i in 1..attempts) {
                if (!running.get()) break

                done += 1
                broadcastProgress(done, success, failed, canceled)
                broadcastLog("Starting attempt #$i on ${device.address}")

                val result = attemptPairing(device, timeoutMs, autoCancel)
                when (result) {
                    PairResult.SUCCESS -> {
                        success += 1
                        broadcastLog("Attempt #$i: SUCCESS")
                        removeBond(device)
                    }
                    PairResult.FAILED -> {
                        failed += 1
                        broadcastLog("Attempt #$i: FAILED")
                    }
                    PairResult.CANCELED -> {
                        canceled += 1
                        broadcastLog("Attempt #$i: CANCELED")
                        cancelCurrentAttempt.set(false)
                    }
                }

                broadcastProgress(done, success, failed, canceled)
                delay(delayMs)
            }

            broadcastLog("All attempts finished")
            running.set(false)
            stopSelf()
        }

        return START_STICKY
    }

    private suspend fun attemptPairing(
        device: BluetoothDevice,
        timeoutMs: Long,
        autoCancelOnPairingRequest: Boolean
    ): PairResult = withContext(Dispatchers.IO) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            broadcastLog("Device already bonded")
            return@withContext PairResult.SUCCESS
        }

        adapter?.cancelDiscovery()
        val started = try {
            device.createBond()
        } catch (e: Exception) {
            broadcastLog("createBond exception: ${e.message}")
            false
        }
        broadcastLog("createBond called: $started")

        val start = System.currentTimeMillis()
        return@withContext suspendCoroutine<PairResult> { cont ->
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return
                    when (intent.action) {
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice ?: return
                            if (dev.address != device.address) return
                            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                            val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                            broadcastLog("Bond state for ${dev.address}: $prev -> $state")
                            if (state == BluetoothDevice.BOND_BONDED) {
                                safeUnregister(this)
                                cont.resume(PairResult.SUCCESS)
                            } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                                safeUnregister(this)
                                cont.resume(PairResult.FAILED)
                            }
                        }
                        BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                            val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice ?: return
                            if (dev.address != device.address) return
                            broadcastLog("Pairing request detected for ${dev.address}")
                            if (autoCancelOnPairingRequest) {
                                try {
                                    cancelBondProcess(device)
                                } catch (e: Exception) {
                                    broadcastLog("cancelBondProcess failed: ${e.message}")
                                }
                                safeUnregister(this)
                                cont.resume(PairResult.CANCELED)
                            }
                        }
                    }
                }

                private fun safeUnregister(br: BroadcastReceiver) {
                    try {
                        unregisterReceiver(br)
                    } catch (_: Exception) {
                    }
                }
            }

            try {
                registerReceiver(receiver, filter)
            } catch (e: Exception) {
                broadcastLog("registerReceiver failed: ${e.message}")
                cont.resume(PairResult.FAILED)
                return@suspendCoroutine
            }

            scope.launch {
                while (true) {
                    delay(250)
                    if (cancelCurrentAttempt.get()) {
                        broadcastLog("Manual cancel requested during active attempt")
                        try {
                            cancelBondProcess(device)
                        } catch (e: Exception) {
                            broadcastLog("cancelBond failed: ${e.message}")
                        }
                        try {
                            unregisterReceiver(receiver)
                        } catch (_: Exception) {
                        }
                        cont.resume(PairResult.CANCELED)
                        return@launch
                    }

                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed > timeoutMs) {
                        broadcastLog("Attempt timed out after ${timeoutMs}ms")
                        try {
                            cancelBondProcess(device)
                        } catch (e: Exception) {
                            broadcastLog("cancelBond on timeout failed: ${e.message}")
                        }
                        try {
                            unregisterReceiver(receiver)
                        } catch (_: Exception) {
                        }
                        cont.resume(PairResult.FAILED)
                        return@launch
                    }
                }
            }
        }
    }

    private fun cancelBondProcess(device: BluetoothDevice) {
        try {
            val method: Method? = device.javaClass.getMethod("cancelBondProcess")
            method?.invoke(device)
            broadcastLog("Invoked cancelBondProcess via reflection")
        } catch (e: Exception) {
            broadcastLog("cancelBondProcess reflection failed: ${e.message}")
        }
    }

    private fun removeBond(device: BluetoothDevice) {
        try {
            val method: Method? = device.javaClass.getMethod("removeBond")
            method?.invoke(device)
            broadcastLog("Requested removeBond via reflection")
        } catch (e: Exception) {
            broadcastLog("removeBond reflection failed: ${e.message}")
        }
    }

    private fun broadcastLog(message: String) {
        Log.d(TAG, message)
        val intent = Intent(MainActivity.ACTION_SERVICE_UPDATE)
        intent.putExtra(MainActivity.EXTRA_LOG, message)
        sendBroadcast(intent)
    }

    private fun broadcastProgress(done: Int, success: Int, failed: Int, canceled: Int) {
        val intent = Intent(MainActivity.ACTION_SERVICE_UPDATE)
        intent.putExtra(MainActivity.EXTRA_DONE, done)
        intent.putExtra(MainActivity.EXTRA_SUCCESS, success)
        intent.putExtra(MainActivity.EXTRA_FAILED, failed)
        intent.putExtra(MainActivity.EXTRA_CANCELED, canceled)
        sendBroadcast(intent)
    }

    private fun startForegroundCompat() {
        val channelId = "btpair_channel"
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "BT Pairing", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BT Pairing")
            .setContentText("Running pairing attempts")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

        startForeground(1, notification)
    }

    enum class PairResult {
        SUCCESS,
        FAILED,
        CANCELED
    }
}
