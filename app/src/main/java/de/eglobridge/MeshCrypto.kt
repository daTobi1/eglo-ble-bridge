package de.eglobridge

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Telink / AwoX BLE mesh crypto.
 * Ported from python-awox-mesh-light (packetutils.py) and fsaris/EspHome-AwoX-BLE-mesh-hub.
 * Pure Kotlin/JVM so it can be unit tested without a device.
 */
object MeshCrypto {

    private fun pad16(b: ByteArray): ByteArray = b.copyOf(16).also {
        require(b.size <= 16) { "value longer than 16 bytes" }
    }

    /** AES-128-ECB with reversed key, reversed input and reversed output (Telink style). */
    fun encrypt(key: ByteArray, value: ByteArray): ByteArray {
        require(key.size == 16) { "key must be 16 bytes" }
        val k = key.reversedArray()
        val v = pad16(value).reversedArray()
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"))
        return cipher.doFinal(v).reversedArray()
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(minOf(a.size, b.size)) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }

    fun nameXorPassword(meshName: ByteArray, meshPassword: ByteArray): ByteArray =
        xor(pad16(meshName), pad16(meshPassword))

    /** Pair request written to the pair characteristic: 0x0c + random(8) + enc(...)[0..8]. */
    fun makePairPacket(meshName: ByteArray, meshPassword: ByteArray, sessionRandom: ByteArray): ByteArray {
        require(sessionRandom.size == 8)
        val enc = encrypt(pad16(sessionRandom), nameXorPassword(meshName, meshPassword))
        return byteArrayOf(0x0c) + sessionRandom + enc.copyOfRange(0, 8)
    }

    fun makeSessionKey(
        meshName: ByteArray, meshPassword: ByteArray,
        sessionRandom: ByteArray, responseRandom: ByteArray
    ): ByteArray = encrypt(nameXorPassword(meshName, meshPassword), sessionRandom + responseRandom.copyOf(8))

    /** MAC "AA:BB:CC:DD:EE:FF" -> bytes reversed (FF EE DD CC BB AA). */
    fun reversedMac(mac: String): ByteArray {
        val hex = mac.replace(":", "").replace("-", "")
        require(hex.length == 12) { "invalid MAC $mac" }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }.reversedArray()
    }

    fun makeChecksum(key: ByteArray, nonce: ByteArray, payload: ByteArray): ByteArray {
        var check = encrypt(key, pad16(nonce + byteArrayOf(payload.size.toByte())))
        var i = 0
        while (i < payload.size) {
            val block = pad16(payload.copyOfRange(i, minOf(i + 16, payload.size)))
            check = encrypt(key, xor(check, block))
            i += 16
        }
        return check
    }

    /** CTR-like stream cipher; used for both encryption and decryption. */
    fun cryptPayload(key: ByteArray, nonce: ByteArray, payload: ByteArray): ByteArray {
        val base = pad16(byteArrayOf(0) + nonce)
        val out = ByteArray(payload.size)
        var i = 0
        while (i < payload.size) {
            val encBase = encrypt(key, base)
            val end = minOf(i + 16, payload.size)
            for (j in i until end) out[j] = (payload[j].toInt() xor encBase[j - i].toInt()).toByte()
            base[0] = (base[0] + 1).toByte()
            i += 16
        }
        return out
    }

    /**
     * Build an encrypted 20 byte command packet.
     * @param seq 3 byte sequence number (must change between packets)
     */
    fun makeCommandPacket(
        sessionKey: ByteArray, mac: String, dest: Int, command: Int, data: ByteArray, seq: ByteArray
    ): ByteArray {
        require(seq.size == 3)
        val a = reversedMac(mac)
        val nonce = a.copyOfRange(0, 4) + byteArrayOf(0x01) + seq
        val raw = byteArrayOf(
            (dest and 0xff).toByte(), ((dest shr 8) and 0xff).toByte(),
            command.toByte(), 0x60, 0x01
        ) + data
        require(raw.size <= 15) { "command data too long" }
        val payload = raw.copyOf(15)
        val check = makeChecksum(sessionKey, nonce, payload)
        val enc = cryptPayload(sessionKey, nonce, payload)
        return seq + check.copyOfRange(0, 2) + enc
    }

    /** Decrypt a 20 byte notification; returns the packet with bytes 7.. decrypted. */
    fun decryptPacket(sessionKey: ByteArray, mac: String, packet: ByteArray): ByteArray {
        val a = reversedMac(mac)
        val nonce = a.copyOfRange(0, 3) + packet.copyOfRange(0, 5)
        val payload = cryptPayload(sessionKey, nonce, packet.copyOfRange(7, packet.size))
        return packet.copyOfRange(0, 7) + payload
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
