package org.phantom.loader.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HardwareIdTest {

    @Test
    fun `compute combines machine guid and mac, hashed sha256 hex lowercase`() {
        val hwid = HardwareId.compute(
            guidReader = FakeGuidReader("ABCDEF-0001"),
            macReader = FakeMacReader("aa:bb:cc:dd:ee:ff"),
        )

        // SHA-256("ABCDEF-0001:aa:bb:cc:dd:ee:ff") computed independently.
        val expected = sha256Hex("ABCDEF-0001:aa:bb:cc:dd:ee:ff")
        assertEquals(expected, hwid)
        assertEquals(64, hwid.length)
        assertEquals(hwid, hwid.lowercase())
    }

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
    }

    private class FakeGuidReader(private val value: String?) : MachineGuidReader {
        override fun read(): String? = value
    }

    private class FakeMacReader(private val value: String?) : MacAddressReader {
        override fun read(): String? = value
    }
}
