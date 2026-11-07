"""Same checks as ConversionTest.kt plus status parsing and slugs."""

from eglo_bridge.config import normalize_mac
from eglo_bridge.lights import convert, device_temp_to_kelvin, kelvin_to_device_temp
from eglo_bridge.mesh import parse_packet
from eglo_bridge.mqtt import slug


def test_kelvin_mapping():
    # device scale: 0 = coolest (6500 K), 0x7f = warmest (2700 K)
    assert kelvin_to_device_temp(2700) == 0x7F
    assert kelvin_to_device_temp(6500) == 0
    assert device_temp_to_kelvin(0) == 6500
    assert device_temp_to_kelvin(0x7F) == 2700
    assert abs(device_temp_to_kelvin(kelvin_to_device_temp(4000)) - 4000) <= 30


def test_brightness_mapping():
    assert convert(0, 0, 255, 1, 0x7F) == 1
    assert convert(255, 0, 255, 1, 0x7F) == 0x7F
    assert convert(0x64, 0x0A, 0x64, 0, 255) == 255


def test_rounding_is_half_up_like_kotlin():
    # 0..10 -> 0..5: 5 -> 2.5 must round to 3 (Kotlin roundToInt), Python round() would give 2
    assert convert(5, 0, 10, 0, 5) == 3


def test_mac_normalisation():
    assert normalize_mac("a4c138887b5d") == "A4:C1:38:88:7B:5D"
    assert normalize_mac("a4:c1:38:88:7b:5d") == "A4:C1:38:88:7B:5D"


def test_parse_status_packet():
    # decrypted 0xdb status from the crypto test vector: mesh id 3, mode 0x03 (on, colour)
    st = parse_packet(bytes.fromhex("10203003000000db600103401050ff0080000000"))
    assert st is not None
    assert st.mesh_id == 3
    assert st.on and st.color_mode and not st.sequence_mode
    assert (st.white_brightness, st.temperature, st.color_brightness) == (0x40, 0x10, 0x50)
    assert (st.r, st.g, st.b) == (255, 0, 128)


def test_slug():
    assert slug("Pi Küche") == "pi_kueche"
    assert slug("Tablet SM-P600 1A2B") == "tablet_sm_p600_1a2b"
