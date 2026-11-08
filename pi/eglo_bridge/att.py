"""Minimal BLE GATT client over a raw L2CAP ATT socket (Linux), used instead of BlueZ's
D-Bus GATT client for the lamp connections.

Why: to receive notifications through BlueZ (bleak ``start_notify``) BlueZ writes the
characteristic's CCCD. Telink/AwoX lamps often never answer that write - the ATT bearer
then blocks for 30 s and the link dies, while the Android app (which never writes the
CCCD) works fine. Telink lamps start notifying when 0x01 is written to the status
characteristic itself, so a raw ATT channel that simply receives every notification,
exactly like Android, avoids the problem.

The kernel lets a client ATT channel take precedence over bluetoothd's server channel, so
bluetoothd does not interfere with this connection. Scanning still uses bleak/BlueZ.

The class mimics the small part of ``bleak.BleakClient`` used by ``connection.py``.
"""

from __future__ import annotations

import asyncio
import ctypes
import errno
import fcntl
import os
import socket
import struct
from typing import Callable

AF_BLUETOOTH = 31
BTPROTO_L2CAP = 0
ATT_CID = 4
BDADDR_LE_PUBLIC = 1
BDADDR_LE_RANDOM = 2

# ATT opcodes
ERROR_RSP = 0x01
MTU_REQ, MTU_RSP = 0x02, 0x03
READ_BY_TYPE_REQ, READ_BY_TYPE_RSP = 0x08, 0x09
READ_REQ, READ_RSP = 0x0A, 0x0B
WRITE_REQ, WRITE_RSP = 0x12, 0x13
WRITE_CMD = 0x52
NOTIFY = 0x1B
INDICATE, CONFIRM = 0x1D, 0x1E
ERR_ATTR_NOT_FOUND = 0x0A
ERR_REQ_NOT_SUPPORTED = 0x06

_PROPS = [(0x02, "read"), (0x04, "write-without-response"), (0x08, "write"), (0x10, "notify"), (0x20, "indicate")]

_libc = ctypes.CDLL(None, use_errno=True)


class _SockaddrL2(ctypes.Structure):
    _fields_ = [("family", ctypes.c_ushort), ("psm", ctypes.c_ushort), ("bdaddr", ctypes.c_ubyte * 6),
                ("cid", ctypes.c_ushort), ("bdaddr_type", ctypes.c_ubyte)]


class AttError(Exception):
    def __init__(self, req: int, handle: int, code: int):
        super().__init__(f"ATT-Fehler 0x{code:02x} (Anfrage 0x{req:02x}, Handle {handle})")
        self.code = code


class Characteristic:
    def __init__(self, uuid: str, handle: int, props: int):
        self.uuid = uuid
        self.handle = handle
        self.properties = [name for bit, name in _PROPS if props & bit]

    def __repr__(self) -> str:
        return f"Characteristic({self.uuid}, handle={self.handle})"


class _Service:
    def __init__(self, chars: dict[str, Characteristic]):
        self._chars = chars

    def get_characteristic(self, uuid: str) -> Characteristic | None:
        return self._chars.get(uuid.lower())


class _Services:
    def __init__(self, chars: dict[str, Characteristic]):
        self._chars = chars

    def get_service(self, uuid: str) -> _Service | None:
        # the mesh characteristics share the service's UUID prefix (…1910 -> …1911/12/14)
        prefix = uuid.lower()[:-4]
        found = {u: c for u, c in self._chars.items() if u.startswith(prefix)}
        return _Service(found) if found else None

    def get_characteristic(self, uuid: str) -> Characteristic | None:
        return self._chars.get(uuid.lower())


def _uuid_from_bytes(b: bytes) -> str:
    if len(b) == 2:
        return f"0000{struct.unpack('<H', b)[0]:04x}-0000-1000-8000-00805f9b34fb"
    h = b[::-1].hex()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


class AttClient:
    """Async GATT client over a raw ATT socket (subset of the bleak API)."""

    def __init__(self, device, disconnected_callback: Callable | None = None, timeout: float = 20.0,
                 **_kwargs):
        self.address = (device.address if hasattr(device, "address") else str(device)).upper()
        self._addr_type = BDADDR_LE_PUBLIC
        details = getattr(device, "details", None)
        try:
            if isinstance(details, dict) and details.get("props", {}).get("AddressType") == "random":
                self._addr_type = BDADDR_LE_RANDOM
        except AttributeError:
            pass
        self._disconnected_callback = disconnected_callback
        self._timeout = timeout
        self._sock: socket.socket | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._lock = asyncio.Lock()
        self._pending: tuple[set[int], asyncio.Future] | None = None
        self._notify: dict[int, Callable] = {}
        self._chars: dict[str, Characteristic] = {}
        self.services = _Services(self._chars)
        self._connected = False

    # ------------------------------------------------------------------ connection

    @property
    def is_connected(self) -> bool:
        return self._connected

    async def connect(self) -> bool:
        self._loop = asyncio.get_running_loop()
        fd = _libc.socket(AF_BLUETOOTH, socket.SOCK_SEQPACKET, BTPROTO_L2CAP)
        if fd < 0:
            e = ctypes.get_errno()
            raise OSError(e, f"Bluetooth-Socket: {os.strerror(e)}")
        try:
            local = _SockaddrL2(AF_BLUETOOTH, 0, (ctypes.c_ubyte * 6)(*[0] * 6), ATT_CID, BDADDR_LE_PUBLIC)
            if _libc.bind(fd, ctypes.byref(local), ctypes.sizeof(local)) < 0:
                e = ctypes.get_errno()
                raise OSError(e, f"bind: {os.strerror(e)}")
            fcntl.fcntl(fd, fcntl.F_SETFL, fcntl.fcntl(fd, fcntl.F_GETFL) | os.O_NONBLOCK)
            peer = bytes.fromhex(self.address.replace(":", ""))[::-1]
            remote = _SockaddrL2(AF_BLUETOOTH, 0, (ctypes.c_ubyte * 6)(*peer), ATT_CID, self._addr_type)
            if _libc.connect(fd, ctypes.byref(remote), ctypes.sizeof(remote)) < 0:
                e = ctypes.get_errno()
                if e not in (errno.EINPROGRESS, errno.EAGAIN):
                    raise OSError(e, f"connect: {os.strerror(e)}")
                done = self._loop.create_future()
                self._loop.add_writer(fd, lambda: done.done() or done.set_result(None))
                try:
                    await asyncio.wait_for(done, self._timeout)
                finally:
                    self._loop.remove_writer(fd)
                sock_err = socket.socket(fileno=os.dup(fd))
                try:
                    err = sock_err.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR)
                finally:
                    sock_err.close()
                if err:
                    raise OSError(err, f"connect: {os.strerror(err)}")
        except BaseException:
            os.close(fd)
            raise
        self._sock = socket.socket(fileno=fd)
        self._sock.setblocking(False)
        self._connected = True
        self._loop.add_reader(fd, self._on_readable)
        try:
            await self._discover()
        except BaseException:
            await self.disconnect()
            raise
        return True

    async def disconnect(self) -> bool:
        self._teardown(notify=False)
        return True

    def _teardown(self, notify: bool) -> None:
        sock = self._sock
        was = self._connected
        self._sock = None
        self._connected = False
        if sock is not None:
            try:
                if self._loop is not None:
                    self._loop.remove_reader(sock.fileno())
            except (ValueError, OSError):
                pass
            try:
                sock.close()
            except OSError:
                pass
        if self._pending is not None and not self._pending[1].done():
            self._pending[1].set_exception(ConnectionError("Verbindung getrennt"))
        self._pending = None
        if notify and was and self._disconnected_callback is not None:
            self._disconnected_callback(self)

    # ------------------------------------------------------------------ ATT transport

    def _on_readable(self) -> None:
        sock = self._sock
        if sock is None:
            return
        try:
            data = sock.recv(512)
        except (BlockingIOError, InterruptedError):
            return
        except OSError:
            self._teardown(notify=True)
            return
        if not data:
            self._teardown(notify=True)
            return
        op = data[0]
        if op in (NOTIFY, INDICATE) and len(data) >= 3:
            handle = struct.unpack_from("<H", data, 1)[0]
            if op == INDICATE:
                self._send(bytes([CONFIRM]))
            cb = self._notify.get(handle)
            if cb is not None:
                char = next((c for c in self._chars.values() if c.handle == handle), None)
                cb(char, bytearray(data[3:]))
            return
        if self._pending is not None:
            expect, fut = self._pending
            if op in expect or op == ERROR_RSP:
                if not fut.done():
                    fut.set_result(data)
                return
        if op == MTU_REQ:  # the lamp asks for our MTU
            self._send(bytes([MTU_RSP]) + struct.pack("<H", 23))
        elif op & 0x40 == 0 and op not in (MTU_RSP, READ_RSP, WRITE_RSP, READ_BY_TYPE_RSP, ERROR_RSP):
            # some other request from the peer: we are no GATT server
            self._send(bytes([ERROR_RSP, op, 0, 0, ERR_REQ_NOT_SUPPORTED]))

    def _send(self, pdu: bytes) -> None:
        sock = self._sock
        if sock is None:
            raise ConnectionError("nicht verbunden")
        sock.send(pdu)

    async def _request(self, pdu: bytes, expect: int) -> bytes:
        async with self._lock:
            if not self._connected:
                raise ConnectionError("nicht verbunden")
            fut = self._loop.create_future()
            self._pending = ({expect}, fut)
            try:
                self._send(pdu)
                rsp = await fut
            finally:
                self._pending = None
            if rsp[0] == ERROR_RSP:
                req, handle, code = rsp[1], struct.unpack_from("<H", rsp, 2)[0], rsp[4]
                raise AttError(req, handle, code)
            return rsp

    async def _discover(self) -> None:
        """Find all characteristic declarations (Read By Type 0x2803 over the whole range)."""
        start = 1
        while start <= 0xFFFF:
            try:
                rsp = await asyncio.wait_for(
                    self._request(bytes([READ_BY_TYPE_REQ]) + struct.pack("<HHH", start, 0xFFFF, 0x2803),
                                  READ_BY_TYPE_RSP), 10)
            except AttError as err:
                if err.code == ERR_ATTR_NOT_FOUND:
                    break
                raise
            ln = rsp[1]
            last = start
            for i in range(2, len(rsp) - ln + 1, ln):
                e = rsp[i:i + ln]
                decl, props, value_handle = struct.unpack_from("<HBH", e, 0)
                uuid = _uuid_from_bytes(e[5:])
                self._chars[uuid] = Characteristic(uuid, value_handle, props)
                last = decl
            if last < start or ln == 0:
                break
            start = last + 1

    # ------------------------------------------------------------------ bleak-like API

    def _char(self, char) -> Characteristic:
        if isinstance(char, Characteristic):
            return char
        c = self._chars.get(str(char).lower())
        if c is None:
            raise ValueError(f"Charakteristik {char} nicht gefunden")
        return c

    async def write_gatt_char(self, char, data, response: bool = True) -> None:
        c = self._char(char)
        payload = struct.pack("<H", c.handle) + bytes(data)
        if response:
            await self._request(bytes([WRITE_REQ]) + payload, WRITE_RSP)
        else:
            self._send(bytes([WRITE_CMD]) + payload)

    async def read_gatt_char(self, char) -> bytearray:
        c = self._char(char)
        rsp = await self._request(bytes([READ_REQ]) + struct.pack("<H", c.handle), READ_RSP)
        return bytearray(rsp[1:])

    async def start_notify(self, char, callback: Callable, **_kwargs) -> None:
        """Only registers the callback - no CCCD write (see module doc)."""
        self._notify[self._char(char).handle] = callback

    async def stop_notify(self, char) -> None:
        self._notify.pop(self._char(char).handle, None)
