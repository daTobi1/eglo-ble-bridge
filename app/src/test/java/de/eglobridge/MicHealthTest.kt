package de.eglobridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicHealthTest {
    private fun classify(
        listening: Boolean = true, open: Boolean = true, blocked: Boolean = false,
        silent: Boolean = false, sinceAudio: Long = 100, sinceOpen: Long = 60_000
    ) = MicHealth.classify(listening, open, blocked, silent, sinceAudio, sinceOpen)

    @Test
    fun deliversAudio() = assertEquals(MicHealth.OK, classify())

    @Test
    fun nobodyListening() = assertEquals(MicHealth.OFF, classify(listening = false, open = false))

    @Test
    fun cannotBeOpened() = assertEquals(MicHealth.BLOCKED, classify(open = false, blocked = true))

    @Test
    fun androidSilencesIt() = assertEquals(MicHealth.SILENCED, classify(silent = true))

    @Test
    fun noSamplesForAWhile() = assertEquals(MicHealth.NO_DATA, classify(sinceAudio = 6_000))

    @Test
    fun justOpenedIsNotStalled() {
        // right after opening there are no samples yet – that is not a hang
        assertEquals(MicHealth.OK, classify(sinceAudio = 6_000, sinceOpen = 1_000))
        assertFalse(MicHealth.isStalled(true, 6_000, 1_000))
    }

    @Test
    fun closedIsNotStalled() = assertFalse(MicHealth.isStalled(false, 60_000, 60_000))

    @Test
    fun reopenIsRateLimited() {
        assertFalse(MicHealth.mayReopen(10_000))
        assertTrue(MicHealth.mayReopen(30_000))
    }

    @Test
    fun errorIsRepeatedEveryFiveMinutes() {
        assertFalse(MicHealth.shouldRepeatLog(60_000))
        assertTrue(MicHealth.shouldRepeatLog(5 * 60_000))
    }
}
