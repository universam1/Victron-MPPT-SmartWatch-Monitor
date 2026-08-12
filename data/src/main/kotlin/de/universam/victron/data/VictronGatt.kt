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
        session.writeControl(VeSmartProtocol.encodeReadyToReceive()) ?: return@withGatt false
        delay(100)

        // Step 2: GetDevices — discover device instances
        session.writeControl(VeSmartProtocol.encodeGetDevices()) ?: return@withGatt false
        val devicesResponse = session.awaitDataNotification() ?: return@withGatt false
        // For single-device setups, instanceId is typically 0
        val instanceId = 0

        // Step 3: GetPathList — get path→index mapping
        session.writeControl(VeSmartProtocol.encodeGetPathList(instanceId)) ?: return@withGatt false
        val pathListResponse = session.awaitDataNotification() ?: return@withGatt false
        val paths = VeSmartProtocol.parsePathList(pathListResponse)

        val loadPathIndex = paths[VictronRegisters.PATH_LOAD_OPERATION_MODE]
        if (loadPathIndex == null) {
            Log.w(TAG, "Device does not expose ${VictronRegisters.PATH_LOAD_OPERATION_MODE}")
            return@withGatt false
        }

        // Step 4: SetPathValue — write the load output mode
        val setMsg = VeSmartProtocol.encodeSetPathValue(instanceId, loadPathIndex, value)
        session.writeData(setMsg) ?: return@withGatt false

        // Step 5: Await acknowledgement
        val ackResponse = session.awaitControlNotification()
        val success = ackResponse != null &&
            ackResponse.isNotEmpty() &&
            (ackResponse[0].toInt() and 0xFF) == VeSmartProtocol.OP_PATH_RESPONSE

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
            ?: return null
        val device: BluetoothDevice = adapter.getRemoteDevice(address) ?: return null

        // Set PIN for auto-pairing if not yet bonded.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            device.setPin(pin.toByteArray())
        }

        val session = GattSession()
        val gatt = device.connectGatt(appContext, false, session.callback, BluetoothDevice.TRANSPORT_LE)
            ?: return null
        session.gatt = gatt

        return try {
            withTimeout(timeoutMs) {
                if (!session.awaitConnected()) return@withTimeout null
                if (!session.awaitServiceDiscovery()) return@withTimeout null
                session.enableNotifications() ?: return@withTimeout null
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
                when (characteristic.uuid) {
                    VictronRegisters.CONTROL_UUID -> controlNotifyDeferred?.complete(data)
                    VictronRegisters.DATA_UUID -> dataNotifyDeferred?.complete(data)
                }
            }
        }

        suspend fun awaitConnected(): Boolean = connectedDeferred.await()

        @SuppressLint("MissingPermission")
        suspend fun awaitServiceDiscovery(): Boolean {
            gatt.discoverServices()
            return servicesDeferred.await()
        }

        /** Enable notifications on both control and data characteristics. */
        @SuppressLint("MissingPermission")
        suspend fun enableNotifications(): Boolean? {
            val service = gatt.getService(VictronRegisters.SERVICE_UUID) ?: run {
                Log.w(TAG, "Service 68c10001 not found")
                return null
            }

            // Enable on control char (68c10002)
            val controlChar = service.getCharacteristic(VictronRegisters.CONTROL_UUID) ?: return null
            gatt.setCharacteristicNotification(controlChar, true)
            val controlCccd = controlChar.getDescriptor(CCCD_UUID) ?: return null
            descriptorDeferred = CompletableDeferred()
            @Suppress("DEPRECATION")
            controlCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(controlCccd)
            if (descriptorDeferred?.await() != true) return null

            // Enable on data char (68c10003)
            val dataChar = service.getCharacteristic(VictronRegisters.DATA_UUID) ?: return null
            gatt.setCharacteristicNotification(dataChar, true)
            val dataCccd = dataChar.getDescriptor(CCCD_UUID) ?: return null
            descriptorDeferred = CompletableDeferred()
            @Suppress("DEPRECATION")
            dataCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(dataCccd)
            return descriptorDeferred?.await()
        }

        /** Write to the control characteristic (68c10002). */
        @SuppressLint("MissingPermission")
        suspend fun writeControl(payload: ByteArray): Boolean? {
            val service = gatt.getService(VictronRegisters.SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(VictronRegisters.CONTROL_UUID) ?: return null
            return writeChar(char, payload)
        }

        /** Write to the data characteristic (68c10003). */
        @SuppressLint("MissingPermission")
        suspend fun writeData(payload: ByteArray): Boolean? {
            val service = gatt.getService(VictronRegisters.SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(VictronRegisters.DATA_UUID) ?: return null
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
            writeDeferred = CompletableDeferred()
            if (Build.VERSION.SDK_INT >= 33) {
                val result = gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                if (result != BluetoothGatt.GATT_SUCCESS) return false
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                if (!gatt.writeCharacteristic(characteristic)) return false
            }
            return writeDeferred?.await()
        }
    }

    private companion object {
        private const val TAG = "VictronGatt"
        private const val TIMEOUT_MS = 20_000L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
