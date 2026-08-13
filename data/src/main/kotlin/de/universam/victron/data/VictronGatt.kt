package de.universam.victron.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import de.universam.victron.protocol.VeSmartProtocol
import de.universam.victron.protocol.VictronRegisters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * VeSmartService GATT client for Victron SmartSolar MPPT.
 *
 * Implements the path-based CBOR protocol from the VictronConnect APK disassembly:
 * - Service `68c10001` with control char `68c10002` and data char `68c10003`
 * - PIN authentication via control char
 * - Path list negotiation (device reports available paths with integer indices)
 * - SetPathValue for writing settings (e.g. load output mode)
 *
 * Requires `BLUETOOTH_CONNECT` permission (Android 12+).
 */
public class VictronGatt(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Sets the load output operation mode via the VeSmartService path-based protocol.
     *
     * @param address BLE MAC address of the Victron device.
     * @param value Load mode: [VictronRegisters.LOAD_ALWAYS_ON] (4) or [VictronRegisters.LOAD_AUTO] (1).
     * @param pin BLE PIN code for authentication.
     * @return true if the device acknowledged the write, false on any failure.
     */
    public suspend fun setLoadOutputMode(
        address: String,
        value: Int,
        pin: String = "000000",
        timeoutMs: Long = TIMEOUT_MS,
    ): Boolean = withGatt(address, pin, timeoutMs) { session ->
        val load = session.findLoadRegister() ?: return@withGatt false

        // The mode shares its register with the streetlight flag, so carry that bit over
        // rather than clearing it.
        val target = VictronRegisters.loadValuePreservingFlags(value, load.currentValue)
        Log.d(TAG, "load register on instance ${load.instance}: ${load.currentValue} -> $target")
        if (target == load.currentValue) {
            Log.d(TAG, "already $target, nothing to write")
            return@withGatt true
        }

        // VictronConnect has two write encodings (see encodeSetRegister); try the one the
        // settings UI uses first and fall back rather than failing outright.
        for (asByteString in listOf(false, true)) {
            val frame = VeSmartProtocol.encodeSetRegister(
                instance = load.instance,
                register = VictronRegisters.LOAD_OUTPUT_CONTROL,
                value = target,
                asByteString = asByteString,
            )
            Log.d(TAG, "SetValues (byteString=$asByteString): ${frame.toHex()}")
            session.writeData(frame) ?: return@withGatt false

            val readBack = session.readRegister(load.instance, VictronRegisters.LOAD_OUTPUT_CONTROL)
            if (readBack == target) {
                Log.d(TAG, "load register confirmed as $readBack")
                return@withGatt true
            }
            Log.w(TAG, "write not confirmed (read back $readBack, wanted $target)")
        }
        false
    } ?: false

    /**
     * Reads the current load output mode.
     *
     * @return the register value (1 = automatic, 4 = always on, bit 7 = streetlight), or null.
     */
    public suspend fun getLoadOutputMode(
        address: String,
        pin: String = "000000",
        timeoutMs: Long = TIMEOUT_MS,
    ): Int? = withGatt(address, pin, timeoutMs) { session ->
        session.findLoadRegister()?.currentValue
    }

    // ---- internals --------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun <T> withGatt(
        address: String,
        pin: String,
        timeoutMs: Long,
        block: suspend (GattSession) -> T,
    ): T? {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: run { Log.w(TAG, "BluetoothManager or adapter is null"); return null }
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
            ?: run { Log.w(TAG, "getRemoteDevice($address) returned null"); return null }

        Log.d(TAG, "withGatt: address=$address, bondState=${device.bondState}, pin=$pin")

        // Set PIN for auto-pairing if not yet bonded.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            device.setPin(pin.toByteArray())
        }

        val session = GattSession()
        val gatt = device.connectGatt(appContext, false, session.callback, BluetoothDevice.TRANSPORT_LE)
            ?: run { Log.w(TAG, "connectGatt returned null"); return null }
        session.gatt = gatt

        return try {
            withTimeout(timeoutMs) {
                Log.d(TAG, "withGatt: awaiting connection…")
                if (!session.awaitConnected()) { Log.w(TAG, "withGatt: connection failed"); return@withTimeout null }
                Log.d(TAG, "withGatt: connected, discovering services…")
                if (!session.awaitServiceDiscovery()) { Log.w(TAG, "withGatt: service discovery failed"); return@withTimeout null }
                Log.d(TAG, "withGatt: services discovered, enabling notifications…")
                session.enableNotifications() ?: run { Log.w(TAG, "withGatt: enableNotifications failed"); return@withTimeout null }
                Log.d(TAG, "withGatt: notifications enabled, initializing session…")
                if (!session.initSession()) { Log.w(TAG, "withGatt: session init failed"); return@withTimeout null }
                Log.d(TAG, "withGatt: session ready, running block")
                delay(200) // allow notifications to stabilize

                block(session)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "GATT operation timed out for $address")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "GATT permission denied", e)
            null
        } finally {
            @Suppress("MissingPermission")
            gatt.close()
        }
    }

    /**
     * Manages the async GATT callbacks, turning them into suspending operations.
     */
    private class GattSession {
        lateinit var gatt: BluetoothGatt

        private val connectedDeferred = CompletableDeferred<Boolean>()
        private val servicesDeferred = CompletableDeferred<Boolean>()
        private var writeDeferred: CompletableDeferred<Boolean>? = null
        private var descriptorDeferred: CompletableDeferred<Boolean>? = null
        /**
         * Every inbound notification, tagged with its source characteristic.
         *
         * Buffered and unbounded on purpose: writes are fire-and-forget
         * (WriteWithoutResponse), so a reply can land before the caller gets around to
         * awaiting it. A `CompletableDeferred` created after the write drops exactly
         * those frames — and drops every chunk after the first of a multi-chunk reply.
         */
        private val notifications = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> connectedDeferred.complete(true)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectedDeferred.complete(false)
                        servicesDeferred.complete(false)
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                servicesDeferred.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                descriptorDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            @Deprecated("Kept for API < 33 compat", ReplaceWith(""))
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val data = characteristic.value ?: return
                Log.d(TAG, "onNotify ${characteristic.uuid.toString().substring(4, 8)}: ${data.toHex()}")
                notifications.trySend(characteristic.uuid to data)
            }
        }

        suspend fun awaitConnected(): Boolean = connectedDeferred.await()

        @SuppressLint("MissingPermission")
        suspend fun awaitServiceDiscovery(): Boolean {
            gatt.discoverServices()
            return servicesDeferred.await()
        }

        /**
         * Initialize the VeSmartService session.
         * From HCI trace: write fa80ff (disconnect old session) then f980 (ready) to
         * the VeSmartService RX char (306b0002), device responds with f901 (session ready).
         * This must happen AFTER enabling notifications on 306b chars.
         */
        @SuppressLint("MissingPermission")
        suspend fun initSession(): Boolean {
            val smartService = gatt.getService(VictronRegisters.SMART_SERVICE_UUID) ?: run {
                Log.w(TAG, "VeSmartService not found for session init")
                return false
            }
            val rxChar = smartService.getCharacteristic(VictronRegisters.SMART_RX_UUID) ?: run {
                Log.w(TAG, "Smart RX char ${VictronRegisters.SMART_RX_UUID} not found")
                return false
            }

            // writeCborChunkSize(0x80, 0xff) — negotiate chunk size.
            writeCharDirect(rxChar, byteArrayOf(0xfa.toByte(), 0x80.toByte(), 0xff.toByte()))
            delay(100)

            // writeReadyToReceive(0x80) — grant the device credit to send.
            writeCharDirect(rxChar, byteArrayOf(0xf9.toByte(), 0x80.toByte()))

            // The ack is f9 01 and arrives on the RX char, not TX. Buffered, so it cannot
            // be missed even though it usually lands before we start waiting.
            val response = awaitNotification(VictronRegisters.SMART_RX_UUID, waitMs = 3000) {
                it.isNotEmpty() && it[0] == 0xf9.toByte()
            }
            if (response != null && response.size >= 2 && response[1] == 0x01.toByte()) {
                Log.d(TAG, "Session initialized (f901)")
                return true
            }
            Log.w(TAG, "Session init response: ${response?.toHex() ?: "none"}")
            return false
        }

        @SuppressLint("MissingPermission")
        private fun writeCharDirect(char: BluetoothGattCharacteristic, value: ByteArray) {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.value = value
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }

        /** Enable notifications on both control and data characteristics. */
        @SuppressLint("MissingPermission")
        suspend fun enableNotifications(): Boolean? {
            val service = gatt.getService(VictronRegisters.SMART_SERVICE_UUID) ?: run {
                Log.w(TAG, "VeSmartService ${VictronRegisters.SMART_SERVICE_UUID} not found")
                Log.d(TAG, "Available services: ${gatt.services.map { it.uuid }}")
                return null
            }
            Log.d(TAG, "Service found, characteristics: ${service.characteristics.map { "${it.uuid} props=0x${it.properties.toString(16)}" }}")

            // Enable notifications on all characteristics that support it.
            // The device exposes 68c10002 (Write+Notify) and 68c10003 (WriteWithoutResponse only).
            // Only 68c10002 has a CCCD descriptor; 68c10003 is write-only for outgoing data.
            // Responses arrive as notifications on 68c10002.
            for (char in service.characteristics) {
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    if (!enableNotify(char, char.uuid.toString().substring(4, 8))) return null
                }
            }
            return true
        }

        @SuppressLint("MissingPermission")
        private suspend fun enableNotify(
            characteristic: BluetoothGattCharacteristic,
            label: String,
        ): Boolean {
            gatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: run {
                Log.w(TAG, "CCCD descriptor not found on $label (${characteristic.uuid})")
                return false
            }
            descriptorDeferred = CompletableDeferred()
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            val initiated = gatt.writeDescriptor(cccd)
            if (!initiated) {
                Log.w(TAG, "writeDescriptor($label) returned false — GATT busy or not connected")
                return false
            }
            val result = descriptorDeferred?.await() == true
            if (!result) Log.w(TAG, "writeDescriptor($label) callback reported failure")
            return result
        }

        /**
         * Write a command to the VeSmartService TX characteristic (306b0003).
         *
         * Only the *last* chunk of a payload belongs on this characteristic — earlier chunks go
         * to 306b0004. Commands here are a handful of bytes against a negotiated 128-byte chunk
         * size, so they are always single-chunk and this is the right target.
         */
        @SuppressLint("MissingPermission")
        suspend fun writeData(payload: ByteArray): Boolean? {
            val service = gatt.getService(VictronRegisters.SMART_SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(VictronRegisters.SMART_TX_UUID) ?: return null
            return writeChar(char, payload)
        }

        /** The load control register, on whichever instance turned out to expose it. */
        class LoadRegister(val instance: Int, val currentValue: Int)

        /**
         * Locates the instance that answers for the load control register and reads its
         * current value.
         *
         * A device can expose several instances and the load register is not necessarily on
         * instance 0 — a capture of VictronConnect showed it queried on instance 3. Probing
         * with a read also verifies the addressing before anything is written.
         */
        suspend fun findLoadRegister(): LoadRegister? {
            for (instance in discoverInstances()) {
                val value = readRegister(instance, VictronRegisters.LOAD_OUTPUT_CONTROL)
                if (value != null) return LoadRegister(instance, value)
                Log.d(TAG, "instance $instance did not answer for the load register")
            }
            Log.w(TAG, "no instance exposed register 0x%04X".format(VictronRegisters.LOAD_OUTPUT_CONTROL))
            return null
        }

        /** Instance ids the device reports, falling back to a small probe range. */
        private suspend fun discoverInstances(): List<Int> {
            writeData(VeSmartProtocol.encodeGetDevices()) ?: return DEFAULT_INSTANCES
            val reply = awaitNotification(VictronRegisters.SMART_TX_UUID, waitMs = 3000) {
                it.isNotEmpty() && (it[0].toInt() and 0xFF) == VeSmartProtocol.OP_DEVICE_LIST
            }
            val parsed = reply?.let { VeSmartProtocol.parseDeviceList(it) }.orEmpty()
            Log.d(TAG, "device list ${reply?.toHex() ?: "none"} -> instances $parsed")
            return parsed.ifEmpty { DEFAULT_INSTANCES }
        }

        /** Reads one register, or null if the device does not answer for it. */
        suspend fun readRegister(instance: Int, register: Int): Int? {
            writeData(VeSmartProtocol.encodeGetRegister(instance, register)) ?: return null
            val reply = awaitNotification(VictronRegisters.SMART_TX_UUID, waitMs = 2500) { frame ->
                VeSmartProtocol.errorCode(frame) == null &&
                    VeSmartProtocol.parseRegisterValue(frame, register) != null
            } ?: return null
            return VeSmartProtocol.parseRegisterValue(reply, register)
        }

        /**
         * Takes the next buffered notification, optionally only from [from] and only if it
         * satisfies [accept]. Frames that do not match are consumed and logged — for a
         * strictly request/response protocol those are interleaved keepalives, which is
         * exactly what we want to skip.
         */
        suspend fun awaitNotification(
            from: UUID? = null,
            waitMs: Long = 5000,
            accept: (ByteArray) -> Boolean = { true },
        ): ByteArray? = withTimeoutOrNull(waitMs) {
            for ((uuid, data) in notifications) {
                if (from != null && uuid != from) {
                    Log.d(TAG, "skip notify from ${uuid.toString().substring(4, 8)}: ${data.toHex()}")
                    continue
                }
                if (!accept(data)) {
                    Log.d(TAG, "skip notify (rejected): ${data.toHex()}")
                    continue
                }
                return@withTimeoutOrNull data
            }
            null
        }

        /** Wait for a frame on the VeSmartService RX char (306b0002) — keepalive/ack channel. */
        suspend fun awaitControlNotification(waitMs: Long = 3000): ByteArray? =
            awaitNotification(VictronRegisters.SMART_RX_UUID, waitMs)

        /** Wait for a frame on the VeSmartService TX char (306b0003) — command responses. */
        suspend fun awaitDataNotification(waitMs: Long = 5000): ByteArray? =
            awaitNotification(VictronRegisters.SMART_TX_UUID, waitMs)

        @SuppressLint("MissingPermission")
        private suspend fun writeChar(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ): Boolean? {
            // VictronConnect uses WriteWithoutResponse (WriteCmd) for all protocol data.
            // This is fire-and-forget: no onCharacteristicWrite callback.
            if (Build.VERSION.SDK_INT >= 33) {
                val result = gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "writeCharacteristic failed: result=$result")
                    return false
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                if (!gatt.writeCharacteristic(characteristic)) {
                    Log.w(TAG, "writeCharacteristic returned false")
                    return false
                }
            }
            // Small delay to let the BLE stack flush — no callback for WriteWithoutResponse.
            delay(50)
            return true
        }
    }

    private companion object {
        private const val TAG = "VictronGatt"
        private const val TIMEOUT_MS = 20_000L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Probed when the device list cannot be read; a capture showed 0, 1 and 3 in use. */
        private val DEFAULT_INSTANCES = listOf(0, 1, 2, 3)

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
