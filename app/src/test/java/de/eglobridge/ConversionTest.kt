package de.eglobridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionTest {
    @Test
    fun kelvinMapping() {
        // device scale: 0 = coolest (6500 K), 0x7f = warmest (2700 K)
        assertEquals(0x7f, LightController.kelvinToDeviceTemp(2700))
        assertEquals(0, LightController.kelvinToDeviceTemp(6500))
        assertEquals(6500, LightController.deviceTempToKelvin(0))
        assertEquals(2700, LightController.deviceTempToKelvin(0x7f))
        val roundTrip = LightController.deviceTempToKelvin(LightController.kelvinToDeviceTemp(4000))
        assertEquals(4000.0, roundTrip.toDouble(), 30.0)
    }

    @Test
    fun brightnessMapping() {
        assertEquals(1, LightController.convert(0, 0, 255, 1, 0x7f))
        assertEquals(0x7f, LightController.convert(255, 0, 255, 1, 0x7f))
        assertEquals(255, LightController.convert(0x64, 0x0a, 0x64, 0, 255))
    }

    @Test
    fun macNormalisation() {
        assertEquals("A4:C1:38:88:7B:5D", MeshDevice.normalizeMac("a4c138887b5d"))
        assertEquals("A4:C1:38:88:7B:5D", MeshDevice.normalizeMac("a4:c1:38:88:7b:5d"))
    }
}
