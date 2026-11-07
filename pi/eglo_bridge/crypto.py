"""Telink / AwoX BLE mesh crypto (port of MeshCrypto.kt)."""

from __future__ import annotations

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def _pad16(b: bytes) -> bytes:
    if len(b) > 16:
        raise ValueError("value longer than 16 bytes")
    return b + bytes(16 - len(b))


def encrypt(key: bytes, value: bytes) -> bytes:
    """AES-128-ECB with reversed key, reversed input and reversed output (Telink style)."""
    if len(key) != 16:
        raise ValueError("key must be 16 bytes")
    enc = Cipher(algorithms.AES(key[::-1]), modes.ECB()).encryptor()
    out = enc.update(_pad16(value)[::-1]) + enc.finalize()
    return out[::-1]


def _xor(a: bytes, b: bytes) -> bytes:
    return bytes(x ^ y for x, y in zip(a, b))


def name_xor_password(mesh_name: bytes, mesh_password: bytes) -> bytes:
    return _xor(_pad16(mesh_name), _pad16(mesh_password))


def make_pair_packet(mesh_name: bytes, mesh_password: bytes, session_random: bytes) -> bytes:
    """Pair request written to the pair characteristic: 0x0c + random(8) + enc(...)[0..8]."""
    if len(session_random) != 8:
        raise ValueError("session random must be 8 bytes")
    enc = encrypt(_pad16(session_random), name_xor_password(mesh_name, mesh_password))
    return bytes([0x0C]) + session_random + enc[:8]


def make_session_key(mesh_name: bytes, mesh_password: bytes,
                     session_random: bytes, response_random: bytes) -> bytes:
    rr = (response_random + bytes(8))[:8]
    return encrypt(name_xor_password(mesh_name, mesh_password), session_random + rr)


def reversed_mac(mac: str) -> bytes:
    """MAC "AA:BB:CC:DD:EE:FF" -> bytes reversed (FF EE DD CC BB AA)."""
    h = mac.replace(":", "").replace("-", "")
    if len(h) != 12:
        raise ValueError(f"invalid MAC {mac}")
    return bytes.fromhex(h)[::-1]


def make_checksum(key: bytes, nonce: bytes, payload: bytes) -> bytes:
    check = encrypt(key, _pad16(nonce + bytes([len(payload) & 0xFF])))
    for i in range(0, len(payload), 16):
        check = encrypt(key, _xor(check, _pad16(payload[i:i + 16])))
    return check


def crypt_payload(key: bytes, nonce: bytes, payload: bytes) -> bytes:
    """CTR-like stream cipher; used for both encryption and decryption."""
    base = bytearray(_pad16(bytes([0]) + nonce))
    out = bytearray(len(payload))
    for i in range(0, len(payload), 16):
        enc_base = encrypt(key, bytes(base))
        for j in range(i, min(i + 16, len(payload))):
            out[j] = payload[j] ^ enc_base[j - i]
        base[0] = (base[0] + 1) & 0xFF
    return bytes(out)


def make_command_packet(session_key: bytes, mac: str, dest: int, command: int,
                        data: bytes, seq: bytes) -> bytes:
    """Build an encrypted 20 byte command packet. ``seq`` is a 3 byte sequence number."""
    if len(seq) != 3:
        raise ValueError("seq must be 3 bytes")
    a = reversed_mac(mac)
    nonce = a[:4] + bytes([0x01]) + seq
    raw = bytes([dest & 0xFF, (dest >> 8) & 0xFF, command & 0xFF, 0x60, 0x01]) + data
    if len(raw) > 15:
        raise ValueError("command data too long")
    payload = raw + bytes(15 - len(raw))
    check = make_checksum(session_key, nonce, payload)
    enc = crypt_payload(session_key, nonce, payload)
    return seq + check[:2] + enc


def decrypt_packet(session_key: bytes, mac: str, packet: bytes) -> bytes:
    """Decrypt a 20 byte notification; returns the packet with bytes 7.. decrypted."""
    a = reversed_mac(mac)
    nonce = a[:3] + packet[:5]
    return packet[:7] + crypt_payload(session_key, nonce, packet[7:])
