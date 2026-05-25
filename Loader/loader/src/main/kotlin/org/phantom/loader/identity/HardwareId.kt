package org.phantom.loader.identity

import java.security.MessageDigest

class HardwareIdUnavailable(message: String) : RuntimeException(message)

object HardwareId {

    private val cached: String by lazy { computeInternal(defaultGuidReader(), JdkMacAddressReader) }

    /** Returns the HWID for this machine. Computed once per JVM. */
    fun compute(): String = cached

    /** Test seam: explicit readers, no caching. */
    internal fun compute(guidReader: MachineGuidReader, macReader: MacAddressReader): String =
        computeInternal(guidReader, macReader)

    private fun computeInternal(guidReader: MachineGuidReader, macReader: MacAddressReader): String {
        val guid = guidReader.read()?.takeIf { it.isNotBlank() }
            ?: throw HardwareIdUnavailable("machine GUID unavailable")
        val mac = macReader.read()?.takeIf { it.isNotBlank() }
            ?: throw HardwareIdUnavailable("MAC address unavailable")

        val combined = "$guid:$mac"
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(combined.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    private fun defaultGuidReader(): MachineGuidReader {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> WindowsMachineGuidReader
            os.contains("mac") || os.contains("darwin") -> MacMachineGuidReader
            else -> LinuxMachineGuidReader
        }
    }
}
