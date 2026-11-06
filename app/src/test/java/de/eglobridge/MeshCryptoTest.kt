package de.eglobridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test vectors generated with the reference Python implementation
 * (python-awox-mesh-light/awoxmeshlight/packetutils.py).
 */
class MeshCryptoTest {
    private val name = "zJs9KiuF".toByteArray()
    private val pass = "secretpass123".toByteArray()
    private val sessionRandom = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val responseRandom = MeshCrypto.unhex("1122334455667788")
    private val mac = "A4:C1:38:88:7B:5D"

    @Test
    fun pairPacket() {
        assertEquals(
            "0c01020304050607081818098e99ee92b6",
            MeshCrypto.hex(MeshCrypto.makePairPacket(name, pass, sessionRandom))
        )
    }

    @Test
    fun sessionKey() {
        assertEquals(
            "ac59af48f27b18d64e7bf314e0c595c8",
            MeshCrypto.hex(MeshCrypto.makeSessionKey(name, pass, sessionRandom, responseRandom))
        )
    }

    @Test
    fun commandPacket() {
        val key = MeshCrypto.makeSessionKey(name, pass, sessionRandom, responseRandom)
        val pkt = MeshCrypto.makeCommandPacket(
            key, mac, 3, 0xd0, byteArrayOf(1, 0, 0), byteArrayOf(5, 0, 0)
        )
        assertEquals("0500006eb5ddf3888daa233c4cf41eb28ca3190d", MeshCrypto.hex(pkt))
    }

    @Test
    fun decryptNotification() {
        val key = MeshCrypto.makeSessionKey(name, pass, sessionRandom, responseRandom)
        val dec = MeshCrypto.decryptPacket(key, mac, MeshCrypto.unhex("102030030000006bed2a2d0dcf3e0c6738b45d95"))
        assertEquals("10203003000000db600103401050ff0080000000", MeshCrypto.hex(dec))
    }
}
