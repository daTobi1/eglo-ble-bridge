"""Simulated Telink/AwoX lamps behind fake bleak classes (for tests without hardware)."""

from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass, field

from eglo_bridge import crypto
from eglo_bridge.connection import CHAR_COMMAND, CHAR_PAIR, CHAR_STATUS, SERVICE
from eglo_bridge.mesh import AWOX_MANUFACTURER_ID, C_REQUEST_STATUS, R_STATUS

MESH_NAME = b"zJs9KiuF"
MESH_PASS = b"secretpass123"


@dataclass
class FakeLamp:
    mac: str
    mesh_id: int
    rssi: int = -60
    visible: bool = True
    #: other mesh ids this lamp answers for (they are reachable through it in the mesh)
    relays: list[int] = field(default_factory=list)
    reject: bool = False
    received: list[tuple[int, int, bytes]] = field(default_factory=list)
    on: bool = False
    color_mode: bool = False
    rgb: tuple = (255, 255, 255)
    connected: bool = False
    #: start_notify fails this many times (BlueZ timeout) before it works
    notify_failures: int = 0
    #: lamp stops sending status notifications (stale link)
    silent: bool = False


LAMPS: dict[str, FakeLamp] = {}


class _Char:
    def __init__(self, uuid: str, props: list[str]):
        self.uuid = uuid
        self.properties = props


class _Service:
    def __init__(self):
        self.chars = {
            CHAR_STATUS: _Char(CHAR_STATUS, ["read", "write", "notify"]),
            CHAR_COMMAND: _Char(CHAR_COMMAND, ["read", "write", "write-without-response"]),
            CHAR_PAIR: _Char(CHAR_PAIR, ["read", "write"]),
        }

    def get_characteristic(self, uuid: str):
        return self.chars.get(uuid)


class _Services:
    def get_service(self, uuid: str):
        return _Service() if uuid == SERVICE else None


class FakeDevice:
    def __init__(self, address: str):
        self.address = address


class FakeAdv:
    def __init__(self, rssi: int):
        self.rssi = rssi
        self.manufacturer_data = {AWOX_MANUFACTURER_ID: b"\x00"}


class FakeClient:
    def __init__(self, device, disconnected_callback=None, timeout=20.0, **_kwargs):
        address = device.address if hasattr(device, "address") else device
        self.lamp = LAMPS.get(address.upper())
        self.disconnected_callback = disconnected_callback
        self.services = _Services()
        self._notify = None
        self._sr = b""
        self._rr = os.urandom(8)
        self.key: bytes | None = None

    @property
    def is_connected(self) -> bool:
        return bool(self.lamp and self.lamp.connected)

    async def connect(self):
        await asyncio.sleep(0.01)
        if self.lamp is None or not self.lamp.visible:
            raise TimeoutError("device not found")
        self.lamp.connected = True

    async def disconnect(self):
        if self.lamp:
            self.lamp.connected = False

    async def write_gatt_char(self, char, data, response=True):
        uuid = char if isinstance(char, str) else char.uuid
        data = bytes(data)
        if uuid == CHAR_PAIR:
            self._sr = data[1:9]
            expected = crypto.make_pair_packet(MESH_NAME, MESH_PASS, self._sr)
            if data != expected:
                raise AssertionError("bad pair packet")
        elif uuid == CHAR_COMMAND:
            assert self.key is not None
            seq = data[:3]
            nonce = crypto.reversed_mac(self.lamp.mac)[:4] + b"\x01" + seq
            payload = crypto.crypt_payload(self.key, nonce, data[5:])
            check = crypto.make_checksum(self.key, nonce, payload)
            assert check[:2] == data[3:5], "checksum mismatch"
            dest = payload[0] | (payload[1] << 8)
            cmd = payload[2]
            self.lamp.received.append((dest, cmd, payload[5:]))
            if cmd == 0xD0:
                self.lamp.on = payload[5] == 1
            elif cmd == 0xE2:
                self.lamp.on, self.lamp.color_mode = True, True
                self.lamp.rgb = tuple(payload[6:9])
            elif cmd in (0xF0, 0xF1):
                self.lamp.on, self.lamp.color_mode = True, False
            if cmd == C_REQUEST_STATUS:
                ids = [self.lamp.mesh_id, *self.lamp.relays] if dest == 0xFFFF else [dest]
                for mid in ids:
                    if mid == self.lamp.mesh_id or mid in self.lamp.relays:
                        asyncio.get_running_loop().call_later(0.01, self._send_status, mid)

    def _send_status(self, mid: int) -> None:
        if self._notify is None or self.key is None or self.lamp.silent:
            return
        p = bytearray(20)
        p[0:3] = os.urandom(3)
        p[3] = mid & 0xFF
        p[4] = (mid >> 8) & 0xFF
        p[7] = R_STATUS
        p[10] = (1 if self.lamp.on else 0) | (2 if self.lamp.color_mode else 0)
        p[11], p[12], p[13] = 0x40, 0x20, 0x50
        p[14], p[15], p[16] = self.lamp.rgb
        nonce = crypto.reversed_mac(self.lamp.mac)[:3] + bytes(p[:5])
        enc = bytes(p[:7]) + crypto.crypt_payload(self.key, nonce, bytes(p[7:]))
        self._notify(None, bytearray(enc))

    async def read_gatt_char(self, char):
        uuid = char if isinstance(char, str) else char.uuid
        if uuid == CHAR_PAIR:
            if self.lamp.reject:
                return bytearray(b"\x0e")
            self.key = crypto.make_session_key(MESH_NAME, MESH_PASS, self._sr, self._rr)
            return bytearray(b"\x0d" + self._rr)
        return bytearray()

    async def start_notify(self, char, cb):
        if self.lamp.notify_failures > 0:
            self.lamp.notify_failures -= 1
            raise TimeoutError()
        self._notify = cb


class FakeScanner:
    def __init__(self, detection_callback=None, **_kwargs):
        self.cb = detection_callback
        self.task: asyncio.Task | None = None

    async def start(self):
        self.task = asyncio.get_running_loop().create_task(self._advertise())

    async def stop(self):
        if self.task:
            self.task.cancel()
            self.task = None

    async def _advertise(self):
        while True:
            for lamp in list(LAMPS.values()):
                if lamp.visible and not lamp.connected:
                    self.cb(FakeDevice(lamp.mac), FakeAdv(lamp.rssi))
            await asyncio.sleep(0.05)
