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

    @Test
    fun `missing machine guid throws HardwareIdUnavailable`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(HardwareIdUnavailable::class.java) {
            HardwareId.compute(
                guidReader = FakeGuidReader(null),
                macReader = FakeMacReader("aa:bb:cc:dd:ee:ff"),
            )
        }
        org.junit.jupiter.api.Assertions.assertTrue(ex.message!!.contains("machine GUID"))
    }

    @Test
    fun `blank machine guid is treated as missing`() {
        org.junit.jupiter.api.Assertions.assertThrows(HardwareIdUnavailable::class.java) {
            HardwareId.compute(
                guidReader = FakeGuidReader("   "),
                macReader = FakeMacReader("aa:bb:cc:dd:ee:ff"),
            )
        }
    }

    @Test
    fun `missing mac throws HardwareIdUnavailable`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(HardwareIdUnavailable::class.java) {
            HardwareId.compute(
                guidReader = FakeGuidReader("ABCDEF-0001"),
                macReader = FakeMacReader(null),
            )
        }
        org.junit.jupiter.api.Assertions.assertTrue(ex.message!!.contains("MAC"))
    }

    @Test
    fun `same readers produce same hwid (deterministic)`() {
        val a = HardwareId.compute(FakeGuidReader("g"), FakeMacReader("m"))
        val b = HardwareId.compute(FakeGuidReader("g"), FakeMacReader("m"))
        assertEquals(a, b)
    }

    private class FakeGuidReader(private val value: String?) : MachineGuidReader {
        override fun read(): String? = value
    }

    private class FakeMacReader(private val value: String?) : MacAddressReader {
        override fun read(): String? = value
    }
}
