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
import de.universam.victron.protocol.VictronRegisters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Short-lived GATT connection for reading/writing VE.Direct registers on a Victron SmartSolar.
 *
 * Protocol reverse-engineered from vvvrrooomm/victron (SmartSolar MPPT 150/35) and confirmed
 * against VictronConnect APK. The SmartSolar uses service `306b0001-...` for data, with
 * characteristics on handles 0x0021 (control), 0x0024 (single value), 0x0027 (bulk).
 *
 * Register commands are framed as raw bytes, not CBOR:
 * - GET:  `05 00 81 19 <reg-LE>`
 * - SET:  `08 00 81 19 <reg-LE> <value-bytes>`
 *
 * This requires `BLUETOOTH_CONNECT` permission (Android 12+).
 */
public class VictronGatt(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Writes a register value and reads back a confirmation.
     *
     * @param address BLE MAC address of the Victron device.
     * @param register The register to write (e.g. [VictronRegisters.LOAD_OUTPUT_CONTROL]).
     * @param value The u8 value to write.
     * @param pin BLE PIN code for pairing (default "000000").
     * @return The read-back [VictronRegisters.RegisterValue], or `null` on failure.
     */
    public suspend fun writeU8AndReadBack(
        address: String,
        register: Int,
        value: Int,
        readBackRegister: Int = VictronRegisters.LOAD_OUTPUT_STATE,
        pin: String = "000000",
        timeoutMs: Long = TIMEOUT_MS,
    ): VictronRegisters.RegisterValue? = withGatt(address, pin, timeoutMs) { gatt, session ->
        // Init handshake
        session.writeControl(gatt, INIT_HANDSHAKE_1) ?: return@withGatt null
        delay(50)
        session.writeControl(gatt, INIT_HANDSHAKE_2) ?: return@withGatt null
        delay(50)

        // Write the register
        val writePayload = VictronRegisters.encodeWriteU8(register, value)
        session.writeSingleValue(gatt, writePayload) ?: return@withGatt null
        delay(100)

        // Read back state
        val readPayload = VictronRegisters.encodeRead(readBackRegister)
        val response = session.writeSingleValueAndNotify(gatt, readPayload) ?: return@withGatt null
        VictronRegisters.decodeResponse(response)
    }

    /**
     * Reads a register value.
     */
    public suspend fun readRegister(
        address: String,
        register: Int,
        pin: String = "000000",
        timeoutMs: Long = TIMEOUT_MS,
    ): VictronRegisters.RegisterValue? = withGatt(address, pin, timeoutMs) { gatt, session ->
        session.writeControl(gatt, INIT_HANDSHAKE_1) ?: return@withGatt null
        delay(50)
        session.writeControl(gatt, INIT_HANDSHAKE_2) ?: return@withGatt null
        delay(50)

        val readPayload = VictronRegisters.encodeRead(register)
        val response = session.writeSingleValueAndNotify(gatt, readPayload) ?: return@withGatt null
        VictronRegisters.decodeResponse(response)
    }

    // ---- internals --------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun <T> withGatt(
        address: String,
        pin: String,
        timeoutMs: Long,
        block: suspend (BluetoothGatt, GattSession) -> T,
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

        return try {
            withTimeout(timeoutMs) {
                session.awaitConnected()
                val discovered = session.awaitServiceDiscovery(gatt)
                if (!discovered) return@withTimeout null

                // Enable notifications on single-value characteristic (0x0024)
                session.enableNotifications(gatt) ?: return@withTimeout null
                delay(100)

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

    private class GattSession {
        private val connectedDeferred = CompletableDeferred<Boolean>()
        private val servicesDeferred = CompletableDeferred<Boolean>()
        private var writeDeferred: CompletableDeferred<Boolean>? = null
        private var descriptorDeferred: CompletableDeferred<Boolean>? = null
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
                notifyDeferred?.complete(characteristic.value)
            }
        }

        suspend fun awaitConnected(): Boolean = connectedDeferred.await()

        @SuppressLint("MissingPermission")
        suspend fun awaitServiceDiscovery(gatt: BluetoothGatt): Boolean {
            gatt.discoverServices()
            return servicesDeferred.await()
        }

        /** Enable notifications on the single-value characteristic (306b0003). */
        @SuppressLint("MissingPermission")
        suspend fun enableNotifications(gatt: BluetoothGatt): Boolean? {
            val service = gatt.getService(DATA_SERVICE_UUID) ?: run {
                Log.w(TAG, "Data service 306b0001 not found")
                return null
            }
            val char = service.getCharacteristic(SINGLE_VALUE_UUID) ?: return null
            gatt.setCharacteristicNotification(char, true)

            // Write the CCCD descriptor to enable notifications
            val cccd = char.getDescriptor(CCCD_UUID) ?: return null
            descriptorDeferred = CompletableDeferred()
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
            return descriptorDeferred?.await()
        }

        /** Write to the control characteristic (306b0002, handle 0x0021). */
        @SuppressLint("MissingPermission")
        suspend fun writeControl(gatt: BluetoothGatt, payload: ByteArray): Boolean? {
            val service = gatt.getService(DATA_SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(CONTROL_UUID) ?: return null
            return writeCharacteristic(gatt, char, payload)
        }

        /** Write to the single-value characteristic (306b0003, handle 0x0024). */
        @SuppressLint("MissingPermission")
        suspend fun writeSingleValue(gatt: BluetoothGatt, payload: ByteArray): Boolean? {
            val service = gatt.getService(DATA_SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(SINGLE_VALUE_UUID) ?: return null
            return writeCharacteristic(gatt, char, payload)
        }

        /** Write to single-value and wait for a notification response. */
        @SuppressLint("MissingPermission")
        suspend fun writeSingleValueAndNotify(gatt: BluetoothGatt, payload: ByteArray): ByteArray? {
            val service = gatt.getService(DATA_SERVICE_UUID) ?: return null
            val char = service.getCharacteristic(SINGLE_VALUE_UUID) ?: return null
            notifyDeferred = CompletableDeferred()
            writeCharacteristic(gatt, char, payload) ?: return null
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

        /** SmartSolar data service (306b0001). Handles register reads/writes. */
        private val DATA_SERVICE_UUID = UUID.fromString("306b0001-b081-4037-83dc-e59fcc3cdfd0")

        /** Control characteristic (306b0002, handle 0x0021). Handshake + keepalive. */
        private val CONTROL_UUID = UUID.fromString("306b0002-b081-4037-83dc-e59fcc3cdfd0")

        /** Single-value characteristic (306b0003, handle 0x0024). Register read/write. */
        private val SINGLE_VALUE_UUID = UUID.fromString("306b0003-b081-4037-83dc-e59fcc3cdfd0")

        /** Bulk/streaming characteristic (306b0004, handle 0x0027). Multi-register ops. */
        @Suppress("unused")
        private val BULK_UUID = UUID.fromString("306b0004-b081-4037-83dc-e59fcc3cdfd0")

        /** Client Characteristic Configuration Descriptor. */
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Init handshake step 1: fa80ff on control char. */
        private val INIT_HANDSHAKE_1 = byteArrayOf(0xFA.toByte(), 0x80.toByte(), 0xFF.toByte())

        /** Init handshake step 2: f980 on control char. */
        private val INIT_HANDSHAKE_2 = byteArrayOf(0xF9.toByte(), 0x80.toByte())
    }
}
