package scouterkit.ssh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// R11: the user scans for the scouter service with the glasses in the open case (only there are the arms
// bondable), picks a pair by its pair ID, and the app stores both addresses and pairs (bonds) with each arm in
// turn: Android 17's own Bluetooth settings refuse these arms ("needs an app to pair"). The phone takes one of
// each arm's 3 bond slots, the oldest is replaced. Nothing here runs without the user's tap.
@SuppressLint("MissingPermission")
class PairingActivity : Activity() {
    // The device as the scan found it: it carries the address type, which a bond needs.
    private class Arm(val device: android.bluetooth.BluetoothDevice, val left: Boolean) {
        val address: String get() = device.address
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var state: TextView
    private val pairs = LinkedHashMap<String, MutableMap<Boolean, Arm>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = page()
        page.addView(header("Pair glasses", "Glasses in the open case, case on USB"))
        list = card()
        list.addView(text("The arms pair only in the open case. The phone takes one of each arm's 3 bond slots (the " +
            "oldest is replaced); while it holds the arms, other hosts cannot connect.", 13.5f, Ui.MUTED))
        state = text("", 14.5f, Ui.ACCENT, Ui.BOLD).apply { setPadding(0, dp(10), 0, dp(4)) }
        list.addView(state)
        list.addView(button("Scan again", PLAIN) { scan() })
        page.addView(list)
        // The scan starts by itself (the glasses should already be in the open case).
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED) scan()
        else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED) scan()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan?.invoke()
        handler.removeCallbacksAndMessages(null)
    }

    private var stopScan: (() -> Unit)? = null
    private var scanning = false

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            !ScouterService.bluetooth(this)) {
            state.text = "allow Bluetooth first"
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        // BLE_ON (Bluetooth switched off, kept for other apps' scans) would scan: off counts as off.
        if (!ScouterService.bluetoothOn(this)) {
            state.text = "Bluetooth is off"
            return
        }
        val scanner = getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner ?: return
        if (scanning) return
        scanning = true
        pairs.clear()
        while (list.childCount > 3) list.removeViewAt(3) // the pairs found by the last scan
        state.text = "looking for the glasses…"
        val started = android.os.SystemClock.uptimeMillis()
        var ended = false
        lateinit var callback: ScanCallback
        // The scan ends 1.2 s after a whole pair (both arms) is seen (a second pair nearby shows up meanwhile), or
        // after 15 s. One whole pair: it is paired at once, no tap.
        fun end() {
            if (ended) return
            ended = true
            scanning = false
            stopScan = null
            handler.removeCallbacksAndMessages(null)
            runCatching { scanner.stopScan(callback) }
            showPairs()
        }
        callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val record = result.scanRecord?.getManufacturerSpecificData(0xFFFF) ?: return
                if (record.size < 3 || record[0].toInt() != 1 || record[1].toInt() and 2 != 0) return // format 1, has a panel
                val id = String(record, 2, record.size - 2, Charsets.UTF_8)
                val arms = pairs.getOrPut(id) { mutableMapOf() }
                val had = arms.size
                arms[record[1].toInt() and 1 != 0] = Arm(result.device, record[1].toInt() and 1 != 0)
                if (had < 2 && arms.size == 2) {
                    Log.i(ScouterService.TAG, "pairing: glasses $id found in ${android.os.SystemClock.uptimeMillis() - started} ms")
                    state.text = "found glasses $id"
                    handler.postDelayed({ end() }, 1200)
                }
            }
        }
        stopScan = { end() }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GlassesLink.SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        handler.postDelayed({ end() }, 15000)
    }

    private fun showPairs() {
        if (isFinishing) return
        state.text = if (pairs.isEmpty()) "no glasses found: is the case open, with the glasses in it?" else "found:"
        val whole = pairs.filterValues { it[true] != null && it[false] != null }
        if (whole.size == 1) { // the only pair: paired now
            val arms = whole.values.first()
            val settings = Settings(this)
            settings.left = arms[true]!!.address
            settings.right = arms[false]!!.address
            settings.glassesAllowed = true
            bond(listOf(arms[true]!!, arms[false]!!))
            return
        }
        for ((id, arms) in pairs) {
            val left = arms[true]
            val right = arms[false]
            val ok = left != null && right != null
            val pick = button("Glasses $id: ${if (left != null) "left" else "no left"}, ${if (right != null) "right" else "no right"}",
                if (ok) PRIMARY else PLAIN) {}
            pick.isEnabled = ok
            pick.setOnClickListener {
                val settings = Settings(this@PairingActivity)
                settings.left = left!!.address
                settings.right = right!!.address
                settings.glassesAllowed = true
                pick.isEnabled = false
                bond(listOf(left, right))
            }
            list.addView(pick)
        }
    }

    // Bonds the arms one after the other (left first), each while the case is open; Android may ask to confirm.
    // Done: back to Settings.
    private fun bond(arms: List<Arm>) {
        val next = arms.firstOrNull { it.device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED }
        if (next == null) {
            state.text = "both arms paired: connecting to them now"
            ScouterService.send(this)
            handler.postDelayed({ finish() }, 1200)
            return
        }
        val side = if (next.left) "left" else "right"
        state.text = "pairing the $side arm… (if Android asks, tap Pair)"
        var started = false
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != next.address) return
                when (i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, 0)) {
                    android.bluetooth.BluetoothDevice.BOND_BONDING -> started = true
                    android.bluetooth.BluetoothDevice.BOND_BONDED -> {
                        unregisterReceiver(this)
                        bond(arms)
                    }
                    android.bluetooth.BluetoothDevice.BOND_NONE -> if (started) {
                        unregisterReceiver(this)
                        state.text = "the $side arm did not pair: keep the glasses in the open case (on USB) and scan again"
                    }
                }
            }
        }
        registerReceiver(receiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        if (next.device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDING && !next.device.createBond()) {
            unregisterReceiver(receiver)
            state.text = "Android refused to pair the $side arm: is Bluetooth on?"
        }
    }
}
