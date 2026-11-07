"""Same test vectors as MeshCryptoTest.kt (generated with python-awox-mesh-light)."""

from eglo_bridge import crypto

NAME = b"zJs9KiuF"
PASS = b"secretpass123"
SESSION_RANDOM = bytes([1, 2, 3, 4, 5, 6, 7, 8])
RESPONSE_RANDOM = bytes.fromhex("1122334455667788")
MAC = "A4:C1:38:88:7B:5D"


def test_pair_packet():
    assert crypto.make_pair_packet(NAME, PASS, SESSION_RANDOM).hex() == "0c01020304050607081818098e99ee92b6"


def test_session_key():
    key = crypto.make_session_key(NAME, PASS, SESSION_RANDOM, RESPONSE_RANDOM)
    assert key.hex() == "ac59af48f27b18d64e7bf314e0c595c8"


def test_command_packet():
    key = crypto.make_session_key(NAME, PASS, SESSION_RANDOM, RESPONSE_RANDOM)
    pkt = crypto.make_command_packet(key, MAC, 3, 0xD0, bytes([1, 0, 0]), bytes([5, 0, 0]))
    assert pkt.hex() == "0500006eb5ddf3888daa233c4cf41eb28ca3190d"


def test_decrypt_notification():
    key = crypto.make_session_key(NAME, PASS, SESSION_RANDOM, RESPONSE_RANDOM)
    dec = crypto.decrypt_packet(key, MAC, bytes.fromhex("102030030000006bed2a2d0dcf3e0c6738b45d95"))
    assert dec.hex() == "10203003000000db600103401050ff0080000000"
