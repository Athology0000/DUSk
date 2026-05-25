package org.phantom.loader.identity

import java.util.concurrent.TimeUnit

internal interface MachineGuidReader {
    /** Returns the machine GUID/UUID or null if unavailable. */
    fun read(): String?
}

internal object WindowsMachineGuidReader : MachineGuidReader {
    override fun read(): String? {
        val process = runCatching {
            ProcessBuilder(
                "reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                "/v", "MachineGuid",
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return null

        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null

        // Lines look like: "    MachineGuid    REG_SZ    abc123-..."
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("MachineGuid", ignoreCase = true) }
            ?.substringAfter("REG_SZ", missingDelimiterValue = "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}

internal object LinuxMachineGuidReader : MachineGuidReader {
    override fun read(): String? {
        val candidates = listOf("/etc/machine-id", "/var/lib/dbus/machine-id")
        for (path in candidates) {
            val file = java.io.File(path)
            if (!file.isFile) continue
            val text = runCatching { file.readText().trim() }.getOrNull() ?: continue
            if (text.isNotEmpty()) return text
        }
        return null
    }
}

internal object MacMachineGuidReader : MachineGuidReader {
    override fun read(): String? {
        val process = runCatching {
            ProcessBuilder("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null

        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null

        // Looking for: "IOPlatformUUID" = "ABCDEFGH-..."
        val regex = Regex("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"")
        return regex.find(output)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
