package de.eglobridge.esphome

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf (proto3) encoder/decoder for the ESPHome native API.
 * Only the wire types the API uses: varint (0), fixed64 (1), length-delimited (2), fixed32 (5).
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    private fun rawVarint(v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    /** uint32 / enum / bool; zero values are skipped like proto3 does. */
    fun uint(field: Int, v: Long): ProtoWriter {
        if (v == 0L) return this
        tag(field, 0); rawVarint(v); return this
    }
    fun uint(field: Int, v: Int) = uint(field, v.toLong() and 0xFFFFFFFFL)
    fun bool(field: Int, v: Boolean) = uint(field, if (v) 1L else 0L)

    fun fixed32(field: Int, v: Int, force: Boolean = false): ProtoWriter {
        if (v == 0 && !force) return this
        tag(field, 5)
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        return this
    }
    fun float(field: Int, v: Float): ProtoWriter =
        if (v == 0f) this else fixed32(field, java.lang.Float.floatToIntBits(v), force = true)

    fun bytes(field: Int, v: ByteArray, force: Boolean = false): ProtoWriter {
        if (v.isEmpty() && !force) return this
        tag(field, 2); rawVarint(v.size.toLong()); out.write(v, 0, v.size); return this
    }
    fun string(field: Int, v: String?, force: Boolean = false): ProtoWriter =
        bytes(field, (v ?: "").toByteArray(Charsets.UTF_8), force)
    fun message(field: Int, w: ProtoWriter): ProtoWriter = bytes(field, w.bytes(), force = true)

    companion object {
        fun varint(v: Long): ByteArray {
            val w = ByteArrayOutputStream()
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                w.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
            }
            w.write(x.toInt())
            return w.toByteArray()
        }
    }
}

/** Decoded message: field number -> list of raw values (Long for varint/fixed, ByteArray for bytes). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {
    fun has(f: Int) = fields.containsKey(f)
    fun long(f: Int): Long = (fields[f]?.lastOrNull() as? Long) ?: 0L
    fun int(f: Int): Int = long(f).toInt()
    fun bool(f: Int): Boolean = long(f) != 0L
    fun float(f: Int): Float = java.lang.Float.intBitsToFloat(long(f).toInt())
    fun bytes(f: Int): ByteArray = (fields[f]?.lastOrNull() as? ByteArray) ?: ByteArray(0)
    fun string(f: Int): String = String(bytes(f), Charsets.UTF_8)
    fun strings(f: Int): List<String> = fields[f].orEmpty().filterIsInstance<ByteArray>().map { String(it, Charsets.UTF_8) }
    fun messages(f: Int): List<ProtoMessage> = fields[f].orEmpty().filterIsInstance<ByteArray>().map { parse(it) }
    fun message(f: Int): ProtoMessage? = messages(f).lastOrNull()

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val map = HashMap<Int, MutableList<Any>>()
            var i = 0
            fun varint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    if (i >= data.size) throw IllegalArgumentException("truncated varint")
                    val b = data[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IllegalArgumentException("varint too long")
                }
            }
            while (i < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> {
                        if (i + 8 > data.size) throw IllegalArgumentException("truncated fixed64")
                        var v = 0L
                        for (k in 7 downTo 0) v = (v shl 8) or (data[i + k].toLong() and 0xFF)
                        i += 8; v
                    }
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || i + len > data.size) throw IllegalArgumentException("truncated bytes")
                        val b = data.copyOfRange(i, i + len); i += len; b
                    }
                    5 -> {
                        if (i + 4 > data.size) throw IllegalArgumentException("truncated fixed32")
                        val v = (data[i].toLong() and 0xFF) or ((data[i + 1].toLong() and 0xFF) shl 8) or
                            ((data[i + 2].toLong() and 0xFF) shl 16) or ((data[i + 3].toLong() and 0xFF) shl 24)
                        i += 4; v
                    }
                    else -> throw IllegalArgumentException("unsupported wire type ${key and 7}")
                }
                map.getOrPut(field) { ArrayList() }.add(value)
            }
            return ProtoMessage(map)
        }
    }
}

/** Message type ids of the ESPHome native API (api.proto, `option (id)`). */
object Msg {
    const val HELLO_REQUEST = 1
    const val HELLO_RESPONSE = 2
    const val DISCONNECT_REQUEST = 5
    const val DISCONNECT_RESPONSE = 6
    const val PING_REQUEST = 7
    const val PING_RESPONSE = 8
    const val DEVICE_INFO_REQUEST = 9
    const val DEVICE_INFO_RESPONSE = 10
    const val LIST_ENTITIES_REQUEST = 11
    const val LIST_ENTITIES_SWITCH = 17
    const val LIST_ENTITIES_DONE = 19
    const val SUBSCRIBE_STATES_REQUEST = 20
    const val SWITCH_STATE = 26
    const val SUBSCRIBE_LOGS_REQUEST = 28
    const val SWITCH_COMMAND = 33
    const val SUBSCRIBE_HA_SERVICES = 34
    const val GET_TIME_REQUEST = 36
    const val GET_TIME_RESPONSE = 37
    const val SUBSCRIBE_HA_STATES = 38
    const val LIST_ENTITIES_MEDIA_PLAYER = 63
    const val MEDIA_PLAYER_STATE = 64
    const val MEDIA_PLAYER_COMMAND = 65
    const val SUBSCRIBE_VOICE_ASSISTANT = 89
    const val VOICE_ASSISTANT_REQUEST = 90
    const val VOICE_ASSISTANT_RESPONSE = 91
    const val VOICE_ASSISTANT_EVENT = 92
    const val VOICE_ASSISTANT_AUDIO = 106
    const val VOICE_ASSISTANT_TIMER_EVENT = 115
    const val VOICE_ASSISTANT_ANNOUNCE_REQUEST = 119
    const val VOICE_ASSISTANT_ANNOUNCE_FINISHED = 120
    const val VOICE_ASSISTANT_CONFIGURATION_REQUEST = 121
    const val VOICE_ASSISTANT_CONFIGURATION_RESPONSE = 122
    const val VOICE_ASSISTANT_SET_CONFIGURATION = 123
}

/** Enum values used by the voice assistant / media player messages. */
object Va {
    // DeviceInfoResponse.voice_assistant_feature_flags
    const val FEATURE_VOICE_ASSISTANT = 1
    const val FEATURE_SPEAKER = 2
    const val FEATURE_API_AUDIO = 4
    const val FEATURE_TIMERS = 8
    const val FEATURE_ANNOUNCE = 16
    const val FEATURE_START_CONVERSATION = 32

    // VoiceAssistantRequest.flags
    const val REQUEST_USE_VAD = 1
    const val REQUEST_USE_WAKE_WORD = 2

    // VoiceAssistantEvent
    const val EVENT_ERROR = 0
    const val EVENT_RUN_START = 1
    const val EVENT_RUN_END = 2
    const val EVENT_STT_START = 3
    const val EVENT_STT_END = 4
    const val EVENT_INTENT_START = 5
    const val EVENT_INTENT_END = 6
    const val EVENT_TTS_START = 7
    const val EVENT_TTS_END = 8
    const val EVENT_WAKE_WORD_START = 9
    const val EVENT_WAKE_WORD_END = 10
    const val EVENT_STT_VAD_START = 11
    const val EVENT_STT_VAD_END = 12
    const val EVENT_TTS_STREAM_START = 98
    const val EVENT_TTS_STREAM_END = 99
    const val EVENT_INTENT_PROGRESS = 100

    // VoiceAssistantTimerEvent
    const val TIMER_STARTED = 0
    const val TIMER_UPDATED = 1
    const val TIMER_CANCELLED = 2
    const val TIMER_FINISHED = 3

    // MediaPlayerState
    const val MP_IDLE = 1
    const val MP_PLAYING = 2
    const val MP_PAUSED = 3
    const val MP_ANNOUNCING = 4

    // MediaPlayerCommand
    const val MP_CMD_PLAY = 0
    const val MP_CMD_PAUSE = 1
    const val MP_CMD_STOP = 2
    const val MP_CMD_MUTE = 3
    const val MP_CMD_UNMUTE = 4
    const val MP_CMD_TOGGLE = 5
    const val MP_CMD_VOLUME_UP = 6
    const val MP_CMD_VOLUME_DOWN = 7

    // MediaPlayerEntityFeature (feature_flags of ListEntitiesMediaPlayerResponse)
    const val MPF_PAUSE = 1 shl 0
    const val MPF_VOLUME_SET = 1 shl 2
    const val MPF_VOLUME_MUTE = 1 shl 3
    const val MPF_PLAY_MEDIA = 1 shl 9
    const val MPF_VOLUME_STEP = 1 shl 10
    const val MPF_STOP = 1 shl 12
    const val MPF_PLAY = 1 shl 14
    const val MPF_MEDIA_ANNOUNCE = 1 shl 20

    // MediaPlayerFormatPurpose
    const val FORMAT_DEFAULT = 0
    const val FORMAT_ANNOUNCEMENT = 1
}
