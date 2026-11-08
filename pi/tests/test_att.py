"""ATT client against a simulated lamp on the other end of a SEQPACKET socket pair."""

from __future__ import annotations

import asyncio
import socket
import struct

from eglo_bridge import att
from eglo_bridge.connection import CHAR_COMMAND, CHAR_PAIR, CHAR_STATUS, SERVICE


def _uuid_bytes(uuid: str) -> bytes:
    return bytes.fromhex(uuid.replace("-", ""))[::-1]


CHARS = [  # (decl handle, props, value handle, uuid)
    (2, 0x02, 3, "00002a00-0000-1000-8000-00805f9b34fb"),
    (17, 0x1A, 18, CHAR_STATUS),
    (20, 0x0E, 21, CHAR_COMMAND),
    (26, 0x0A, 27, CHAR_PAIR),
]


class Lamp:
    """Answers ATT requests like a Telink lamp (no CCCD involved)."""

    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.written: list[tuple[int, bytes, bool]] = []
        sock.setblocking(False)

    def handle(self) -> None:
        try:
            pdu = self.sock.recv(512)
        except BlockingIOError:
            return
        op = pdu[0]
        if op == att.READ_BY_TYPE_REQ:
            start = struct.unpack_from("<H", pdu, 1)[0]
            rest = [c for c in CHARS if c[0] >= start]
            if not rest:
                self.sock.send(bytes([att.ERROR_RSP, op]) + struct.pack("<H", start) + bytes([att.ERR_ATTR_NOT_FOUND]))
                return
            # 16-bit and 128-bit entries come in separate responses
            first = rest[0]
            same = [c for c in rest if len(_uuid_bytes(c[3])) == len(_uuid_bytes(first[3]))
                    and (c[3].endswith("-0000-1000-8000-00805f9b34fb") == first[3].endswith("-0000-1000-8000-00805f9b34fb"))]
            entries = []
            for decl, props, vh, uuid in same:
                ub = _uuid_bytes(uuid)
                if uuid.endswith("-0000-1000-8000-00805f9b34fb"):
                    ub = ub[12:14]
                entries.append(struct.pack("<HBH", decl, props, vh) + ub)
            self.sock.send(bytes([att.READ_BY_TYPE_RSP, len(entries[0])]) + b"".join(entries))
        elif op == att.WRITE_REQ:
            h = struct.unpack_from("<H", pdu, 1)[0]
            self.written.append((h, pdu[3:], True))
            self.sock.send(bytes([att.WRITE_RSP]))
        elif op == att.WRITE_CMD:
            h = struct.unpack_from("<H", pdu, 1)[0]
            self.written.append((h, pdu[3:], False))
        elif op == att.READ_REQ:
            self.sock.send(bytes([att.READ_RSP]) + b"\x0d" + bytes(8))
        elif op == att.MTU_RSP:
            self.written.append((0, pdu, False))


def test_att_client_discovery_read_write_notify():
    async def run():
        loop = asyncio.get_running_loop()
        a, b = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        lamp = Lamp(b)
        loop.add_reader(b.fileno(), lamp.handle)
        disconnected = []
        c = att.AttClient("A4:C1:38:88:7B:5D", disconnected_callback=lambda cl: disconnected.append(cl))
        # inject the socket instead of a real L2CAP connect
        c._loop = loop
        c._sock = a
        a.setblocking(False)
        c._connected = True
        loop.add_reader(a.fileno(), c._on_readable)
        await c._discover()

        svc = c.services.get_service(SERVICE)
        assert svc is not None
        status = svc.get_characteristic(CHAR_STATUS)
        assert status.handle == 18 and "notify" in status.properties
        cmd = svc.get_characteristic(CHAR_COMMAND)
        assert "write" in cmd.properties and "write-without-response" in cmd.properties
        assert c.services.get_characteristic("00002a00-0000-1000-8000-00805f9b34fb").handle == 3

        got = []
        await c.start_notify(status, lambda ch, data: got.append((ch.uuid, bytes(data))))
        await c.write_gatt_char(status, b"\x01", response=True)
        await c.write_gatt_char(cmd, b"\xaa" * 20, response=False)
        reply = await c.read_gatt_char(svc.get_characteristic(CHAR_PAIR))
        assert reply[0] == 0x0D
        await asyncio.sleep(0.05)
        assert (18, b"\x01", True) in lamp.written and (21, b"\xaa" * 20, False) in lamp.written

        # notification from the lamp, and an MTU request from the lamp is answered
        b.send(bytes([att.NOTIFY]) + struct.pack("<H", 18) + b"\x11" * 20)
        b.send(bytes([att.MTU_REQ]) + struct.pack("<H", 100))
        await asyncio.sleep(0.05)
        assert got == [(CHAR_STATUS, b"\x11" * 20)]
        assert any(w[1][:1] == bytes([att.MTU_RSP]) for w in lamp.written)

        # error response -> AttError
        try:
            await c.write_gatt_char(att.Characteristic("x", 99, 0x08), b"\x00")
        except att.AttError:
            raise AssertionError("lamp answered the write")  # our fake lamp accepts every write

        # lamp goes away -> disconnected callback, pending requests fail
        loop.remove_reader(b.fileno())
        b.close()
        await asyncio.sleep(0.05)
        assert disconnected == [c] and not c.is_connected

    asyncio.run(run())
