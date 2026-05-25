package org.phantom.loader.identity

import java.net.NetworkInterface

internal interface MacAddressReader {
    /** Returns lowercase colon-separated MAC like `aa:bb:cc:dd:ee:ff` or null. */
    fun read(): String?
}

internal object JdkMacAddressReader : MacAddressReader {
    override fun read(): String? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
        }.getOrNull().orEmpty()

        return interfaces
            .filter { nif ->
                runCatching {
                    !nif.isLoopback && !nif.isVirtual && nif.isUp && nif.hardwareAddress != null
                }.getOrDefault(false)
            }
            .sortedBy { it.name }
            .firstNotNullOfOrNull { nif ->
                val bytes = nif.hardwareAddress ?: return@firstNotNullOfOrNull null
                bytes.joinToString(":") { String.format("%02x", it) }
            }
    }
}
