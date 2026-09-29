package com.codeyantra.armorchat.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.ParcelUuid
import com.codeyantra.armorchat.core.mesh.GattChunks
import com.codeyantra.armorchat.core.mesh.GattReassembler
import com.codeyantra.armorchat.core.mesh.MeshPolicy
import com.codeyantra.armorchat.messaging.SafeLog
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

interface MeshPipe {
    val initiator: Boolean
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray
    fun close()
}

/**
 * Bluetooth discovers and carries the encounter. Wi-Fi Direct is a second
 * pipe for a large sealed batch, used only when both phones agree.
 */
@SuppressLint("MissingPermission")
class NearbyMeshRadio(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifyLock = Any()
    private val serviceUuid = UUID.fromString(MeshPolicy.SERVICE_UUID)
    private val frameUuid = UUID.fromString(MeshPolicy.FRAME_UUID)
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var server: BluetoothGattServer? = null
    private var advertiserStarted = false
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var handler: (suspend (MeshPipe) -> Unit)? = null
    private val running = AtomicBoolean(false)
    var wifiDirectReady: Boolean = false
        private set

    fun start(onPipe: suspend (MeshPipe) -> Unit): String {
        stop()
        handler = onPipe
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            return "Turn Bluetooth on. Saved messages stay on this phone until their deadline."
        }
        wifiDirectReady = context.getSystemService(WifiP2pManager::class.java) != null
        running.set(true)
        openServer(manager)
        startAdvertising(adapter)
        startScan(adapter)
        return if (wifiDirectReady) {
            "Bluetooth is listening. A batch over 8 KB also tries Wi-Fi Direct, then falls back to Bluetooth."
        } else {
            "Bluetooth is listening. This phone has no Wi-Fi Direct."
        }
    }

    fun stop() {
        running.set(false)
        handler = null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        scanCallback?.let { runCatching { scanner?.stopScan(it) } }
        advertiseCallback?.let { runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        runCatching { server?.close() }
        server = null
        scanner = null
    }

    suspend fun wifiExchange(outgoing: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        if (!wifiDirectReady || outgoing.isEmpty()) return@withContext null
        withTimeoutOrNull(8_000) { wifiRound(outgoing) }
    }

    private fun openServer(manager: BluetoothManager) {
        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && running.get()) {
                    SafeLog.event("mesh_srv_conn", "state=connected status=$status")
                    val pipe = GattPipe(device.address, initiator = false, sender = { value ->
                        val characteristic = server?.getService(serviceUuid)?.getCharacteristic(frameUuid) ?: return@GattPipe -2
                        synchronized(notifyLock) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                server?.notifyCharacteristicChanged(device, characteristic, false, value) ?: -3
                            } else {
                                @Suppress("DEPRECATION")
                                characteristic.value = value
                                @Suppress("DEPRECATION")
                                if (server?.notifyCharacteristicChanged(device, characteristic, false) == true) 0 else -1
                            }
                        }
                    }, mtu = { serverMtu[device.address] ?: GattChunks.DEFAULT_MTU })
                    pipes[device.address] = pipe
                    launch(pipe)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    SafeLog.event("mesh_srv_conn", "state=disconnected status=$status")
                    pipes.remove(device.address)
                    serverMtu.remove(device.address)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                serverMtu[device.address] = mtu
                SafeLog.event("mesh_srv_mtu", "mtu=$mtu")
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) SafeLog.event("mesh_srv_notify_sent", "status=$status")
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                // The client's CCCD write is an ATT Write Request. Without this response the
                // client's GATT queue stays busy until the 30 s ATT timeout (status 201 on API 33+).
                val ok = descriptor.uuid == cccd
                SafeLog.event(
                    "mesh_srv_desc_write",
                    "cccd=$ok len=${value?.size ?: 0} prepared=$preparedWrite response_needed=$responseNeeded offset=$offset",
                )
                if (responseNeeded) {
                    val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                    val sent = server?.sendResponse(device, requestId, status, offset, value)
                    SafeLog.event("mesh_srv_desc_response", "status=$status sent=$sent")
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                val pipe = pipes[device.address] ?: pipes.values.firstOrNull()
                SafeLog.event(
                    "mesh_srv_char_write",
                    "frame=${characteristic.uuid == frameUuid} len=${value.size} prepared=$preparedWrite " +
                        "response_needed=$responseNeeded offset=$offset pipe=${pipe != null}",
                )
                pipe?.feed(value)
                if (responseNeeded) {
                    val sent = server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    if (sent != true) SafeLog.event("mesh_srv_char_response", "sent=$sent")
                }
            }
        }
        server = manager.openGattServer(context, callback)
        SafeLog.event("mesh_srv_open", "ok=${server != null} service=$serviceUuid frame=$frameUuid")
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            frameUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ),
        )
        service.addCharacteristic(characteristic)
        server?.addService(service)
    }

    private val pipes = java.util.concurrent.ConcurrentHashMap<String, GattPipe>()
    private val clients = java.util.concurrent.ConcurrentHashMap<String, ClientPipe>()
    private val serverMtu = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun startAdvertising(adapter: android.bluetooth.BluetoothAdapter) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val callback = object : AdvertiseCallback() {}
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, callback)
        advertiserStarted = true
    }

    private fun startScan(adapter: android.bluetooth.BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return
        this.scanner = scanner
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!running.get()) return
                connectAsClient(result.device)
            }
        }
        scanCallback = callback
        scanner.startScan(filters, settings, callback)
    }

    private fun connectAsClient(device: BluetoothDevice) {
        if (clients.isNotEmpty()) return
        scope.launch {
            val pipe = ClientPipe(device)
            clients[device.address] = pipe
            val opened = runCatching { withTimeout(12_000) { pipe.open() } }.getOrDefault(false)
            if (!opened) {
                SafeLog.event("mesh_gatt", "open_fail")
                pipe.close()
                clients.remove(device.address)
                return@launch
            }
            SafeLog.event("mesh_gatt", "open_ok")
            launch(pipe)
            clients.remove(device.address)
        }
    }

    private fun launch(pipe: MeshPipe) {
        scope.launch {
            try {
                withTimeout(20_000) { handler?.invoke(pipe) }
                SafeLog.event("mesh_xfer", "ok initiator=${pipe.initiator}")
            } catch (err: Exception) {
                SafeLog.event("mesh_xfer", "fail initiator=${pipe.initiator} cause=${err.javaClass.simpleName}")
            } finally {
                pipe.close()
            }
        }
    }

    private suspend fun wifiRound(outgoing: ByteArray): ByteArray? {
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: return null
        val channel = manager.initialize(context, context.mainLooper, null) ?: return null
        val device = discoverOne(manager, channel) ?: return null
        val connected = CompletableDeferred<android.net.wifi.p2p.WifiP2pInfo?>()
        @Suppress("DEPRECATION")
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) {
                if (!connected.isCompleted) connected.complete(null)
            }
        })
        manager.requestConnectionInfo(channel) { info ->
            if (info != null && info.groupFormed && !connected.isCompleted) connected.complete(info)
        }
        val info = connected.await() ?: return null
        return socketExchange(info, outgoing)
    }

    private suspend fun discoverOne(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pDevice? {
        val found = CompletableDeferred<WifiP2pDevice?>()
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.requestPeers(channel) { peers ->
                    if (!found.isCompleted) found.complete(peers.deviceList.firstOrNull())
                }
            }
            override fun onFailure(reason: Int) {
                if (!found.isCompleted) found.complete(null)
            }
        })
        return found.await()
    }

    private fun socketExchange(info: android.net.wifi.p2p.WifiP2pInfo, outgoing: ByteArray): ByteArray? {
        return try {
            val socket = if (info.isGroupOwner) {
                ServerSocket(WIFI_PORT).use { server ->
                    server.soTimeout = 5_000
                    server.accept()
                }
            } else {
                Socket(info.groupOwnerAddress, WIFI_PORT)
            }
            socket.use { link ->
                link.soTimeout = 6_000
                val out = link.getOutputStream()
                val length = ByteBuffer.allocate(4).putInt(outgoing.size).array()
                out.write(length)
                out.write(outgoing)
                out.flush()
                val input = link.getInputStream()
                val header = ByteArray(4)
                if (input.read(header) != 4) return null
                val size = ByteBuffer.wrap(header).int
                if (size < 0 || size > MeshPolicy.MAX_PAYLOAD_BYTES * MeshPolicy.MAX_OFFERS_PER_ENCOUNTER) return null
                val body = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(body, read, size - read)
                    if (n < 0) return null
                    read += n
                }
                body
            }
        } catch (_: Exception) {
            null
        }
    }

    private interface Addressable {
        val address: String
    }

    private inner class GattPipe(
        override val address: String,
        override val initiator: Boolean,
        private val sender: (ByteArray) -> Int,
        private val mtu: () -> Int,
    ) : MeshPipe, Addressable {
        private val incoming = Channel<ByteArray>(4)
        private val reassembler = GattReassembler()

        fun feed(chunk: ByteArray) {
            val bytes = reassembler.feed(chunk) ?: return
            SafeLog.event("mesh_srv_reassembled", "bytes=${bytes.size}")
            incoming.trySend(bytes)
        }

        override suspend fun send(bytes: ByteArray) {
            val chunks = GattChunks.split(bytes, mtu())
            SafeLog.event("mesh_srv_notify", "bytes=${bytes.size} mtu=${mtu()} chunks=${chunks.size}")
            chunks.forEachIndexed { index, chunk ->
                val code = sender(chunk)
                if (code != 0) {
                    SafeLog.event("mesh_srv_notify_fail", "code=$code chunk=$index/${chunks.size} len=${chunk.size}")
                    error("mesh notify failed")
                }
                delay(20)
            }
        }

        override suspend fun receive(): ByteArray = withTimeout(12_000) { incoming.receive() }
        override fun close() {
            incoming.close()
        }
    }

    private inner class ClientPipe(private val device: BluetoothDevice) : MeshPipe, Addressable {
        override val initiator: Boolean = true
        override val address: String = device.address
        private val incoming = Channel<ByteArray>(4)
        private val reassembler = GattReassembler()
        private var gatt: BluetoothGatt? = null
        private var characteristic: BluetoothGattCharacteristic? = null
        @Volatile private var writeAck: CompletableDeferred<Int>? = null
        @Volatile private var mtu: Int = GattChunks.DEFAULT_MTU
        private val ready = CompletableDeferred<Boolean>()

        suspend fun open(): Boolean {
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    SafeLog.event("mesh_cli_conn", "state=$newState status=$status sdk=${Build.VERSION.SDK_INT}")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        val asked = gatt.requestMtu(REQUESTED_MTU)
                        SafeLog.event("mesh_cli_mtu_request", "mtu=$REQUESTED_MTU queued=$asked")
                        if (!asked) gatt.discoverServices()
                    } else {
                        writeAck?.complete(-4)
                        if (!ready.isCompleted) ready.complete(false)
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) this@ClientPipe.mtu = mtu
                    SafeLog.event("mesh_cli_mtu", "mtu=$mtu status=$status")
                    gatt.discoverServices()
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    characteristic = gatt.getService(serviceUuid)?.getCharacteristic(frameUuid)
                    val char = characteristic
                    SafeLog.event(
                        "mesh_cli_services",
                        "status=$status service=${gatt.getService(serviceUuid) != null} frame=${char != null} " +
                            "props=${char?.properties ?: -1}",
                    )
                    if (char == null) {
                        ready.complete(false)
                        return
                    }
                    gatt.setCharacteristicNotification(char, true)
                    val descriptor = char.getDescriptor(cccd)
                    if (descriptor == null) {
                        if (!ready.isCompleted) ready.complete(true)
                        return
                    }
                    // Wait for onDescriptorWrite before any characteristic write: Android allows one
                    // outstanding GATT op per client, and a pending CCCD write makes writes fail (201).
                    val code = if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        if (gatt.writeDescriptor(descriptor)) 0 else -1
                    }
                    SafeLog.event("mesh_cli_cccd_write", "code=$code")
                    if (code != 0 && !ready.isCompleted) ready.complete(false)
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    SafeLog.event("mesh_cli_cccd_done", "status=$status")
                    if (!ready.isCompleted) ready.complete(status == BluetoothGatt.GATT_SUCCESS)
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    feed(value)
                }

                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (Build.VERSION.SDK_INT >= 33) return
                    @Suppress("DEPRECATION")
                    feed(characteristic.value ?: return)
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) SafeLog.event("mesh_cli_write_done", "status=$status")
                    writeAck?.complete(status)
                }
            }
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val ok = ready.await()
            if (ok) delay(350)
            return ok
        }

        private fun feed(chunk: ByteArray) {
            val bytes = reassembler.feed(chunk) ?: return
            SafeLog.event("mesh_cli_reassembled", "bytes=${bytes.size}")
            incoming.trySend(bytes)
        }

        override suspend fun send(bytes: ByteArray) {
            val char = characteristic ?: error("mesh characteristic missing")
            val link = gatt ?: error("mesh link missing")
            // One acknowledged ATT Write per chunk, each chunk <= MTU-3 so no prepared write,
            // and the next write starts only after onCharacteristicWrite.
            val chunks = GattChunks.split(bytes, mtu)
            val count = chunks.size
            SafeLog.event("mesh_cli_send", "bytes=${bytes.size} mtu=$mtu chunks=$count")
            chunks.forEachIndexed { index, chunk ->
                val ack = CompletableDeferred<Int>()
                writeAck = ack
                var code = writeOnce(link, char, chunk)
                var attempt = 0
                while (code != 0 && attempt < BUSY_RETRIES) {
                    SafeLog.event("mesh_write", "code=$code chunk=$index/$count len=${chunk.size} attempt=$attempt")
                    delay(BUSY_BACKOFF_MS)
                    attempt += 1
                    code = writeOnce(link, char, chunk)
                }
                if (code != 0) {
                    SafeLog.event("mesh_write", "gave_up code=$code chunk=$index/$count len=${chunk.size}")
                    error("mesh write failed")
                }
                val status = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { ack.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    SafeLog.event("mesh_write", "ack_fail status=$status chunk=$index/$count")
                    error("mesh write not acknowledged")
                }
            }
            SafeLog.event("mesh_cli_send_done", "bytes=${bytes.size} chunks=$count")
        }

        private fun writeOnce(link: BluetoothGatt, char: BluetoothGattCharacteristic, chunk: ByteArray): Int {
            return if (Build.VERSION.SDK_INT >= 33) {
                link.writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                char.value = chunk
                @Suppress("DEPRECATION")
                if (link.writeCharacteristic(char)) 0 else -1
            }
        }

        override suspend fun receive(): ByteArray = withTimeout(12_000) { incoming.receive() }

        override fun close() {
            incoming.close()
            runCatching { gatt?.close() }
        }
    }

    private companion object {
        const val WIFI_PORT = 8988
        const val REQUESTED_MTU = 185
        const val BUSY_RETRIES = 5
        const val BUSY_BACKOFF_MS = 60L
        const val WRITE_ACK_TIMEOUT_MS = 5_000L
    }
}

