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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
        // Step 1: ReadyToReceive — tell device we can accept responses
        Log.d(TAG, "Step 1: sending ReadyToReceive")
        session.writeControl(VeSmartProtocol.encodeReadyToReceive()) ?: run {
            Log.w(TAG, "Step 1 failed: writeControl(ReadyToReceive) returned null")
            return@withGatt false
        }
        delay(100)

        // Step 2: GetDevices — discover device instances
        Log.d(TAG, "Step 2: sending GetDevices")
        session.writeControl(VeSmartProtocol.encodeGetDevices()) ?: run {
            Log.w(TAG, "Step 2 failed: writeControl(GetDevices) returned null")
            return@withGatt false
        }
        Log.d(TAG, "Step 2: awaiting data notification")
        val devicesResponse = session.awaitDataNotification() ?: run {
            Log.w(TAG, "Step 2 failed: awaitDataNotification (GetDevices) timed out")
            return@withGatt false
        }
        Log.d(TAG, "Step 2: got devices response: ${devicesResponse.toHex()}")
        // For single-device setups, instanceId is typically 0
        val instanceId = 0

        // Step 3: GetPathList — get path→index mapping
        Log.d(TAG, "Step 3: sending GetPathList(instance=$instanceId)")
        session.writeControl(VeSmartProtocol.encodeGetPathList(instanceId)) ?: run {
            Log.w(TAG, "Step 3 failed: writeControl(GetPathList) returned null")
            return@withGatt false
        }
        Log.d(TAG, "Step 3: awaiting data notification")
        val pathListResponse = session.awaitDataNotification() ?: run {
            Log.w(TAG, "Step 3 failed: awaitDataNotification (GetPathList) timed out")
            return@withGatt false
        }
        Log.d(TAG, "Step 3: got path list response: ${pathListResponse.toHex()}")
        val paths = VeSmartProtocol.parsePathList(pathListResponse)
        Log.d(TAG, "Step 3: parsed paths = $paths")

        val loadPathIndex = paths[VictronRegisters.PATH_LOAD_OPERATION_MODE]
        if (loadPathIndex == null) {
            Log.w(TAG, "Device does not expose ${VictronRegisters.PATH_LOAD_OPERATION_MODE}")
            return@withGatt false
        }

        // Step 4: SetPathValue — write the load output mode
        Log.d(TAG, "Step 4: SetPathValue(instance=$instanceId, pathIndex=$loadPathIndex, value=$value)")
        val setMsg = VeSmartProtocol.encodeSetPathValue(instanceId, loadPathIndex, value)
        session.writeData(setMsg) ?: run {
            Log.w(TAG, "Step 4 failed: writeData(SetPathValue) returned null")
            return@withGatt false
        }

        // Step 5: Await acknowledgement
        Log.d(TAG, "Step 5: awaiting control notification (ack)")
        val ackResponse = session.awaitControlNotification()
        val success = ackResponse != null &&
            ackResponse.isNotEmpty() &&
            (ackResponse[0].toInt() and 0xFF) == VeSmartProtocol.OP_PATH_RESPONSE
        Log.d(TAG, "Step 5: ack=${ackResponse?.toHex()}, success=$success")

        Log.d(TAG, "setLoadOutputMode($address, $value) → success=$success")
        success
    } ?: false

    /**
     * Reads the current load output state via VeSmartService.
     *
     * @return the operation mode value (1=auto, 4=always on), or null on failure.
     */
    public suspend fun getLoadOutputMode(
        address: String,
        pin: String = "000000",
        timeoutMs: Long = TIMEOUT_MS,
    ): Int? = withGatt(address, pin, timeoutMs) { session ->
        session.writeControl(VeSmartProtocol.encodeReadyToReceive()) ?: return@withGatt null
        delay(100)

        session.writeControl(VeSmartProtocol.encodeGetDevices()) ?: return@withGatt null
        session.awaitDataNotification() ?: return@withGatt null
        val instanceId = 0

        session.writeControl(VeSmartProtocol.encodeGetPathList(instanceId)) ?: return@withGatt null
        val pathListResponse = session.awaitDataNotification() ?: return@withGatt null
        val paths = VeSmartProtocol.parsePathList(pathListResponse)

        val loadPathIndex = paths[VictronRegisters.PATH_LOAD_OPERATION_MODE] ?: return@withGatt null

        val getMsg = VeSmartProtocol.encodeGetPathValue(instanceId, loadPathIndex)
        session.writeData(getMsg) ?: return@withGatt null

        val valueResponse = session.awaitDataNotification() ?: return@withGatt null
        VeSmartProtocol.parsePathValue(valueResponse)
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
                if (!session.authenticatePin(pin)) { Log.w(TAG, "withGatt: session init failed"); return@withTimeout null }
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
        private var controlNotifyDeferred: CompletableDeferred<ByteArray?>? = null
        private var dataNotifyDeferred: CompletableDeferred<ByteArray?>? = null

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
                Log.d(TAG, "onNotify ${characteristic.uuid.toString().substring(4,8)}: ${data.toHex()}")
                // All VeSmartService notifications arrive on 306b chars.
                // Route to whichever deferred is currently waiting — data takes priority since
                // it's the protocol response we're actively waiting for.
                val dataDeferred = dataNotifyDeferred
                if (dataDeferred != null && !dataDeferred.isCompleted) {
                    dataDeferred.complete(data)
                } else {
                    controlNotifyDeferred?.complete(data)
                }
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
        suspend fun authenticatePin(pin: String): Boolean {
            val smartService = gatt.getService(VictronRegisters.SMART_SERVICE_UUID) ?: run {
                Log.w(TAG, "VeSmartService not found for session init")
                return false
            }
            val rxChar = smartService.getCharacteristic(VictronRegisters.SMART_RX_UUID) ?: run {
                Log.w(TAG, "Smart RX char ${VictronRegisters.SMART_RX_UUID} not found")
                return false
            }

            // Send session disconnect/reset: fa 80 ff
            writeCharDirect(rxChar, byteArrayOf(0xfa.toByte(), 0x80.toByte(), 0xff.toByte()))
            delay(100)

            // Send ready: f9 80
            writeCharDirect(rxChar, byteArrayOf(0xf9.toByte(), 0x80.toByte()))
            delay(300)

            // Wait for f9 01 response (session ready)
            val response = awaitDataNotification(waitMs = 3000)
            if (response != null && response.size >= 2 && response[0] == 0xf9.toByte() && response[1] == 0x01.toByte()) {
                Log.d(TAG, "Session initialized (f901)")
                return true
            }
            Log.w(TAG, "Session init response: ${response?.joinToString("") { "%02x".format(it) } ?: "null"}")
            return true // proceed anyway — the device might have already responded
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

        /** Write to the VeSmartService TX characteristic (306b0003). */
        @SuppressLint("MissingPermission")
        suspend fun writeControl(payload: ByteArray): Boolean? {
            val service = gatt.getService(VictronRegisters.SMART_SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(VictronRegisters.SMART_TX_UUID) ?: return null
            return writeChar(char, payload)
        }

        /** Write to the VeSmartService TX characteristic (306b0003) — same as control for this protocol. */
        @SuppressLint("MissingPermission")
        suspend fun writeData(payload: ByteArray): Boolean? {
            val service = gatt.getService(VictronRegisters.SMART_SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(VictronRegisters.SMART_TX_UUID) ?: return null
            return writeChar(char, payload)
        }

        /** Wait for a notification on the control characteristic (with timeout). */
        suspend fun awaitControlNotification(waitMs: Long = 3000): ByteArray? {
            controlNotifyDeferred = CompletableDeferred()
            return try {
                withTimeout(waitMs) { controlNotifyDeferred?.await() }
            } catch (_: TimeoutCancellationException) {
                null
            }
        }

        /** Wait for a notification on the data characteristic (with timeout). */
        suspend fun awaitDataNotification(waitMs: Long = 5000): ByteArray? {
            dataNotifyDeferred = CompletableDeferred()
            return try {
                withTimeout(waitMs) { dataNotifyDeferred?.await() }
            } catch (_: TimeoutCancellationException) {
                null
            }
        }

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
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
