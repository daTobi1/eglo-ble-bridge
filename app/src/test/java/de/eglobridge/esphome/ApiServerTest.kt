package de.eglobridge.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApiServerTest {

    @Test
    fun protoRoundTrip() {
        val w = ProtoWriter()
            .uint(1, 300).bool(2, true).string(3, "Grüße").fixed32(4, -123456, force = true)
            .float(5, 0.75f).message(6, ProtoWriter().string(1, "a")).message(6, ProtoWriter().string(1, "b"))
        val m = ProtoMessage.parse(w.bytes())
        assertEquals(300, m.int(1))
        assertTrue(m.bool(2))
        assertEquals("Grüße", m.string(3))
        assertEquals(-123456, m.int(4))
        assertEquals(0.75f, m.float(5), 0f)
        assertEquals(listOf("a", "b"), m.messages(6).map { it.string(1) })
    }

    @Test
    fun objectIdHashMatchesEsphome() {
        // ESPHome: fnv1_hash("media_player") as used for entity keys
        assertEquals(2232357057L.toInt(), ApiServer.fnv1Hash("media_player"))
    }

    private class FakeDevice : ApiDevice {
        val subscribed = CountDownLatch(1)
        val events = mutableListOf<Pair<Int, Map<String, String>>>()
        val eventLatch = CountDownLatch(1)
        override fun identity() = DeviceIdentity("eglo-test", "Test", "02:00:00:00:00:01", "M", "X", "1.0")
        override fun voiceFeatureFlags() = Va.FEATURE_VOICE_ASSISTANT or Va.FEATURE_API_AUDIO
        override fun mediaPlayerFormats() = listOf(MediaFormat("mp3", 0, 0, Va.FORMAT_DEFAULT))
        override fun mediaPlayerFeatures() = Va.MPF_PLAY_MEDIA
        override fun mediaPlayerState() = Triple(Va.MP_IDLE, 0.5f, false)
        override fun micMuted() = false
        override fun onMediaPlayerCommand(command: Int?, volume: Float?, url: String?, announcement: Boolean) {}
        override fun onMicMuteCommand(muted: Boolean) {}
        override fun onVoiceSubscription(subscribed: Boolean) { if (subscribed) this.subscribed.countDown() }
        override fun onVoiceResponse(port: Int, error: Boolean) {}
        override fun onVoiceEvent(type: Int, data: Map<String, String>) { events += type to data; eventLatch.countDown() }
        override fun onTimerEvent(type: Int, id: String, name: String, totalSeconds: Int, secondsLeft: Int, active: Boolean) {}
        override fun onAnnounce(mediaId: String, text: String, preannounceMediaId: String, startConversation: Boolean) {}
        override fun voiceConfiguration() = VoiceConfig(emptyList(), emptyList(), 1)
        override fun onSetActiveWakeWords(ids: List<String>) {}
    }

    private fun frame(type: Int, w: ProtoWriter): ByteArray {
        val p = w.bytes()
        val b = ByteArrayOutputStream()
        b.write(0); b.write(ProtoWriter.varint(p.size.toLong())); b.write(ProtoWriter.varint(type.toLong())); b.write(p)
        return b.toByteArray()
    }

    private fun DataInputStream.varint(): Int {
        var shift = 0; var r = 0
        while (true) {
            val b = readUnsignedByte()
            r = r or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return r
            shift += 7
        }
    }

    private fun DataInputStream.readFrame(): Pair<Int, ProtoMessage> {
        assertEquals(0, readUnsignedByte())
        val len = varint(); val type = varint()
        val data = ByteArray(len); readFully(data)
        return type to ProtoMessage.parse(data)
    }

    @Test
    fun handshakeEntitiesAndVoice() {
        val dev = FakeDevice()
        val server = ApiServer(dev, 16999)
        server.start()
        try {
            Socket("127.0.0.1", 16999).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream())

                out.write(frame(Msg.HELLO_REQUEST, ProtoWriter().string(1, "test").uint(2, 1).uint(3, 10)))
                val (t1, hello) = inp.readFrame()
                assertEquals(Msg.HELLO_RESPONSE, t1)
                assertEquals(1, hello.int(1))
                assertEquals("eglo-test", hello.string(4))

                out.write(frame(Msg.DEVICE_INFO_REQUEST, ProtoWriter()))
                val (t2, info) = inp.readFrame()
                assertEquals(Msg.DEVICE_INFO_RESPONSE, t2)
                assertEquals("02:00:00:00:00:01", info.string(3))
                assertEquals(Va.FEATURE_VOICE_ASSISTANT or Va.FEATURE_API_AUDIO, info.int(17))

                out.write(frame(Msg.LIST_ENTITIES_REQUEST, ProtoWriter()))
                val types = generateSequence { inp.readFrame().first }.takeWhile { it != Msg.LIST_ENTITIES_DONE }.toList()
                assertEquals(listOf(Msg.LIST_ENTITIES_MEDIA_PLAYER, Msg.LIST_ENTITIES_SWITCH), types)

                out.write(frame(Msg.SUBSCRIBE_VOICE_ASSISTANT, ProtoWriter().bool(1, true)))
                assertTrue(dev.subscribed.await(2, TimeUnit.SECONDS))
                assertTrue(server.sendVoiceRequest(true, flags = Va.REQUEST_USE_WAKE_WORD))
                val (t3, req) = inp.readFrame()
                assertEquals(Msg.VOICE_ASSISTANT_REQUEST, t3)
                assertTrue(req.bool(1))
                assertEquals(Va.REQUEST_USE_WAKE_WORD, req.int(3))

                out.write(frame(Msg.VOICE_ASSISTANT_EVENT, ProtoWriter().uint(1, Va.EVENT_TTS_END)
                    .message(2, ProtoWriter().string(1, "url").string(2, "http://ha/x.mp3"))))
                assertTrue(dev.eventLatch.await(2, TimeUnit.SECONDS))
                assertEquals(Va.EVENT_TTS_END to mapOf("url" to "http://ha/x.mp3"), dev.events.first())
            }
        } finally {
            server.stop()
        }
    }
}
