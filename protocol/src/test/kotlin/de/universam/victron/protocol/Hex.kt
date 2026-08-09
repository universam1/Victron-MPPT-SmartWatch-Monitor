package de.universam.victron.protocol

internal fun String.hexToBytes(): ByteArray {
    val cleaned = filterNot { it == ' ' }
    require(cleaned.length % 2 == 0)
    return ByteArray(cleaned.length / 2) { index ->
        cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
