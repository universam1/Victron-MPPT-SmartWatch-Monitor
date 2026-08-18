package de.universam.victron.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone/watch merge rule — the part of the sync that can silently lose a key if it is wrong. */
class ConfigMergeTest {

    private fun device(
        address: String,
        key: String = "a".repeat(32),
        updatedAt: Long = 0,
        label: String? = null,
    ) = DeviceConfig(
        address = address,
        advertisementKeyHex = key,
        label = label,
        updatedAtEpochMillis = updatedAt,
    )

    @Test
    fun `takes over a device the other side knows`() {
        val local = AppConfig()

        val merged = local.mergeDevices(listOf(device("AA:BB:CC:DD:EE:FF", updatedAt = 10)))

        assertEquals(1, merged.devices.size)
        assertEquals("AA:BB:CC:DD:EE:FF", merged.devices.single().address)
    }

    @Test
    fun `newer entry wins per device`() {
        val local = AppConfig(devices = listOf(device("A1", key = "1".repeat(32), updatedAt = 100)))

        val merged = local.mergeDevices(listOf(device("A1", key = "2".repeat(32), updatedAt = 200)))

        assertEquals("2".repeat(32), merged.devices.single().advertisementKeyHex)
    }

    @Test
    fun `older entry does not overwrite`() {
        val local = AppConfig(devices = listOf(device("A1", key = "1".repeat(32), updatedAt = 300)))

        val merged = local.mergeDevices(listOf(device("A1", key = "2".repeat(32), updatedAt = 200)))

        assertEquals("1".repeat(32), merged.devices.single().advertisementKeyHex)
    }

    @Test
    fun `addresses are matched case insensitively`() {
        val local = AppConfig(devices = listOf(device("aa:bb:cc:dd:ee:ff", updatedAt = 100)))

        val merged = local.mergeDevices(listOf(device("AA:BB:CC:DD:EE:FF", label = "MPPT", updatedAt = 200)))

        assertEquals(1, merged.devices.size)
        assertEquals("MPPT", merged.devices.single().label)
    }

    @Test
    fun `union keeps devices only one side has`() {
        val local = AppConfig(devices = listOf(device("A1", updatedAt = 1)))

        val merged = local.mergeDevices(listOf(device("B2", updatedAt = 1)))

        assertEquals(setOf("A1", "B2"), merged.devices.map { it.address }.toSet())
    }

    @Test
    fun `empty remote list changes nothing`() {
        val local = AppConfig(devices = listOf(device("A1", updatedAt = 1)))

        assertEquals(local, local.mergeDevices(emptyList()))
    }

    @Test
    fun `knows when it has something to contribute`() {
        val local = AppConfig(devices = listOf(device("A1", updatedAt = 200)))

        assertTrue(local.hasNewerThan(listOf(device("A1", updatedAt = 100))))
        assertTrue(local.hasNewerThan(emptyList()))
        assertFalse(local.hasNewerThan(listOf(device("A1", updatedAt = 200))))
        assertFalse(local.hasNewerThan(listOf(device("A1", updatedAt = 300))))
    }

    @Test
    fun `lookups are case insensitive`() {
        val config = AppConfig(devices = listOf(device("aa:bb", key = "f".repeat(32), label = "Van")))

        assertEquals("f".repeat(32), config.keyFor("AA:BB"))
        assertEquals("Van", config.labelFor("Aa:Bb"))
    }
}
