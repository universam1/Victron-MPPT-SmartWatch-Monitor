package de.universam.victron.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import de.universam.victron.protocol.VictronRegisters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Short-lived GATT connection for reading/writing VE.Direct registers on a Victron device.
 *
 * The connection flow is: pair (Android system dialog) → connect → discover → keep-alive →
 * read/write register → disconnect. Designed to be opened, used once, and closed.
 *
 * This requires `BLUETOOTH_CONNECT` permission (Android 12+).
 */
public class VictronGatt(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Writes a single-byte register value and reads back the current state register.
     *
     * @param address BLE MAC address of the Victron device.
     * @param register The VE.Direct register to write (e.g. [VictronRegisters.LOAD_OUTPUT_CONTROL]).
     * @param value The u8 value to write.
     * @return The read-back [VictronRegisters.RegisterValue] from [readBackRegister], or `null` on failure.
     */
    public suspend fun writeU8AndReadBack(
        address: String,
        register: Int,
        value: Int,
        readBackRegister: Int = VictronRegisters.LOAD_OUTPUT_STATE,
        timeoutMs: Long = TIMEOUT_MS,
    ): VictronRegisters.RegisterValue? = withGatt(address, timeoutMs) { gatt, session ->
        // Write keep-alive first
        session.writeKeepAlive(gatt) ?: return@withGatt null

        // Write the register
        val writePayload = VictronRegisters.encodeWriteU8(register, value)
        session.writeTransport(gatt, writePayload) ?: return@withGatt null

        // Read back state
        val readPayload = VictronRegisters.encodeRead(readBackRegister)
        val response = session.writeAndNotify(gatt, readPayload) ?: return@withGatt null
        VictronRegisters.decodeResponse(response)
    }

    /**
     * Reads a register value.
     */
    public suspend fun readRegister(
        address: String,
        register: Int,
        timeoutMs: Long = TIMEOUT_MS,
    ): VictronRegisters.RegisterValue? = withGatt(address, timeoutMs) { gatt, session ->
        session.writeKeepAlive(gatt) ?: return@withGatt null
        val readPayload = VictronRegisters.encodeRead(register)
        val response = session.writeAndNotify(gatt, readPayload) ?: return@withGatt null
        VictronRegisters.decodeResponse(response)
    }

    // ---- internals --------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun <T> withGatt(
        address: String,
        timeoutMs: Long,
        block: suspend (BluetoothGatt, GattSession) -> T,
    ): T? {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return null
        val device: BluetoothDevice = adapter.getRemoteDevice(address) ?: return null
        val session = GattSession()

        val gatt = device.connectGatt(appContext, false, session.callback, BluetoothDevice.TRANSPORT_LE)
            ?: return null

        return try {
            withTimeout(timeoutMs) {
                session.awaitConnected()
                val discovered = session.awaitServiceDiscovery(gatt)
                if (!discovered) return@withTimeout null
                block(gatt, session)
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
        private val connectedDeferred = CompletableDeferred<Boolean>()
        private val servicesDeferred = CompletableDeferred<Boolean>()
        private var writeDeferred: CompletableDeferred<Boolean>? = null
        private var notifyDeferred: CompletableDeferred<ByteArray?>? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDeferred.complete(true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDeferred.complete(false)
                    servicesDeferred.complete(false)
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

            @Deprecated("Kept for API < 33 compat", ReplaceWith(""))
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                notifyDeferred?.complete(characteristic.value)
            }
        }

        suspend fun awaitConnected(): Boolean = connectedDeferred.await()

        @SuppressLint("MissingPermission")
        suspend fun awaitServiceDiscovery(gatt: BluetoothGatt): Boolean {
            gatt.discoverServices()
            return servicesDeferred.await()
        }

        @SuppressLint("MissingPermission")
        suspend fun writeKeepAlive(gatt: BluetoothGatt): Boolean? {
            val service = gatt.getService(VictronRegisters.SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(VictronRegisters.WRITE_UUID) ?: return null
            return writeCharacteristic(gatt, char, KEEP_ALIVE_PAYLOAD)
        }

        @SuppressLint("MissingPermission")
        suspend fun writeTransport(gatt: BluetoothGatt, payload: ByteArray): Boolean? {
            val service = gatt.getService(VictronRegisters.SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(VictronRegisters.WRITE_UUID) ?: return null
            return writeCharacteristic(gatt, char, payload)
        }

        /**
         * Writes payload to the write characteristic and waits for a notification response.
         */
        @SuppressLint("MissingPermission")
        suspend fun writeAndNotify(gatt: BluetoothGatt, payload: ByteArray): ByteArray? {
            val service = gatt.getService(VictronRegisters.SERVICE_UUID) ?: return null
            val writeChar = service.getCharacteristic(VictronRegisters.WRITE_UUID) ?: return null
            val notifyChar = service.getCharacteristic(VictronRegisters.NOTIFY_UUID) ?: writeChar

            // Enable notifications
            gatt.setCharacteristicNotification(notifyChar, true)

            notifyDeferred = CompletableDeferred()
            writeCharacteristic(gatt, writeChar, payload) ?: return null
            return notifyDeferred?.await()
        }

        @SuppressLint("MissingPermission")
        private suspend fun writeCharacteristic(
            gatt: BluetoothGatt,
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
        private const val TIMEOUT_MS = 15_000L
        /** Keep-alive payload: 0x01 (any non-0xFF value keeps the connection open for ~60s). */
        private val KEEP_ALIVE_PAYLOAD = byteArrayOf(0x01)
    }
}
