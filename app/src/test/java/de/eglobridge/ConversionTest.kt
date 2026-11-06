package de.eglobridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionTest {
    @Test
    fun kelvinMapping() {
        // device scale: 0 = coolest (6500 K), 0x7f = warmest (2700 K)
        assertEquals(0x7f, MqttBridge.kelvinToDeviceTemp(2700))
        assertEquals(0, MqttBridge.kelvinToDeviceTemp(6500))
        assertEquals(6500, MqttBridge.deviceTempToKelvin(0))
        assertEquals(2700, MqttBridge.deviceTempToKelvin(0x7f))
        val roundTrip = MqttBridge.deviceTempToKelvin(MqttBridge.kelvinToDeviceTemp(4000))
        assertEquals(4000.0, roundTrip.toDouble(), 30.0)
    }

    @Test
    fun brightnessMapping() {
        assertEquals(1, MqttBridge.convert(0, 0, 255, 1, 0x7f))
        assertEquals(0x7f, MqttBridge.convert(255, 0, 255, 1, 0x7f))
        assertEquals(255, MqttBridge.convert(0x64, 0x0a, 0x64, 0, 255))
    }

    @Test
    fun macNormalisation() {
        assertEquals("A4:C1:38:88:7B:5D", MeshDevice.normalizeMac("a4c138887b5d"))
        assertEquals("A4:C1:38:88:7B:5D", MeshDevice.normalizeMac("a4:c1:38:88:7b:5d"))
    }
}
