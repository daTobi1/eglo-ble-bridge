package de.eglobridge

/**
 * Health of the shared microphone as plain logic (no Android classes), so it can be unit-tested.
 * [MicHub] feeds it its state; the result is shown in Home Assistant ("Sprach-Mikrofon").
 */
object MicHealth {
    /** No audio for this long while the microphone is open counts as hanging. */
    const val STALL_MS = 5_000L
    /** At most one automatic reopen per this interval (a hanging audio driver must not be hammered). */
    const val REOPEN_INTERVAL_MS = 30_000L
    /** While the microphone cannot be opened: next attempt after this long. */
    const val RETRY_MS = 15_000L
    /** Repeat an ongoing microphone error in the log at most this often. */
    const val LOG_REPEAT_MS = 5 * 60_000L

    const val OK = "liefert Ton"
    const val NO_DATA = "keine Daten"
    const val BLOCKED = "blockiert"
    const val SILENCED = "stumm (Android)"
    const val OFF = "aus"

    /**
     * @param listening someone (voice satellite, music mode) wants the microphone
     * @param open an AudioRecord is open
     * @param blocked the last attempt to open it failed
     * @param silent it delivers nothing but digital zeros (Android blocks it in the background)
     * @param msSinceAudio time since the last samples arrived
     * @param msSinceOpen time since it was opened
     */
    fun classify(
        listening: Boolean, open: Boolean, blocked: Boolean, silent: Boolean,
        msSinceAudio: Long, msSinceOpen: Long
    ): String = when {
        !listening -> OFF
        !open -> if (blocked) BLOCKED else OFF
        silent -> SILENCED
        isStalled(open, msSinceAudio, msSinceOpen) -> NO_DATA
        else -> OK
    }

    /** Open, past its start-up time and no samples for [STALL_MS]. */
    fun isStalled(open: Boolean, msSinceAudio: Long, msSinceOpen: Long): Boolean =
        open && msSinceOpen > STALL_MS && msSinceAudio > STALL_MS

    /** Whether the watchdog may reopen now (rate limit, see [REOPEN_INTERVAL_MS]). */
    fun mayReopen(msSinceLastReopen: Long): Boolean = msSinceLastReopen >= REOPEN_INTERVAL_MS

    /** Whether an ongoing error should be written to the log again. */
    fun shouldRepeatLog(msSinceLastLog: Long): Boolean = msSinceLastLog >= LOG_REPEAT_MS
}
