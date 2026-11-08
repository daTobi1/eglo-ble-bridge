"""One authenticated BLE connection to a single lamp of the Telink/AwoX mesh.

Port of MeshConnection.kt from Android GATT callbacks to bleak (BlueZ). All methods
run on the bridge event loop. GATT operations are serialised with a lock.
"""

from __future__ import annotations

import asyncio
import os
import time
from collections import deque
from dataclasses import dataclass
from enum import Enum
from typing import Awaitable, Callable, Protocol

from bleak import BleakClient
from bleak.backends.device import BLEDevice

from . import crypto, log
from .sched import Scheduler

SERVICE = "00010203-0405-0607-0809-0a0b0c0d1910"
CHAR_STATUS = "00010203-0405-0607-0809-0a0b0c0d1911"
CHAR_COMMAND = "00010203-0405-0607-0809-0a0b0c0d1912"
CHAR_PAIR = "00010203-0405-0607-0809-0a0b0c0d1914"

COMMAND_SPACING_S = 0.18
OP_TIMEOUT_S = 8.0
CONNECT_TIMEOUT_S = 35.0
MAX_QUEUE = 40


class ConnectionEvents(Protocol):
    def on_paired(self, c: "MeshConnection") -> None: ...
    def on_closed(self, c: "MeshConnection", was_paired: bool, reason: str) -> None: ...
    def on_packet(self, c: "MeshConnection", packet: bytes) -> None: ...


class State(Enum):
    NEW = 0
    CONNECTING = 1
    PAIRING = 2
    PAIRED = 3
    CLOSED = 4


@dataclass
class Cmd:
    dest: int
    command: int
    data: bytes
    coalesce: bool


class MeshConnection:
    def __init__(self, sched: Scheduler, mac: str, mesh_name: bytes, mesh_password: bytes,
                 events: ConnectionEvents, adapter: str = "",
                 before_connect: Callable[[], Awaitable[None]] | None = None):
        self.sched = sched
        self.mac = mac
        self.mesh_name = mesh_name
        self.mesh_password = mesh_password
        self.events = events
        self.adapter = adapter
        self.before_connect = before_connect

        self.state = State.NEW
        self.client: BleakClient | None = None
        self.session_key: bytes | None = None
        self._session_random = b""
        self._cmd_response = True
        self._gatt_lock = asyncio.Lock()
        self._task: asyncio.Task | None = None
        self.disconnect_task: asyncio.Task | None = None
        self.commands: deque[Cmd] = deque()
        self._last_command_at = 0.0
        self._k = ("conn", id(self))  # timer key prefix

    @property
    def is_paired(self) -> bool:
        return self.state == State.PAIRED

    @property
    def is_closed(self) -> bool:
        return self.state == State.CLOSED

    # ------------------------------------------------------------------ lifecycle

    def connect(self, device: BLEDevice | str) -> None:
        self.state = State.CONNECTING
        log.i(f"Verbinde mit {self.mac} …")
        self._task = self.sched.spawn(self._connect(device))
        self.sched.post_delayed(CONNECT_TIMEOUT_S, self._connect_timeout, key=(self._k, "timeout"))

    def _connect_timeout(self) -> None:
        if self.state in (State.CONNECTING, State.PAIRING):
            self.close("Zeitüberschreitung")

    def close(self, reason: str) -> None:
        if self.state == State.CLOSED:
            return
        was_paired = self.state == State.PAIRED
        self.state = State.CLOSED
        for k in ("timeout", "pump"):
            self.sched.remove((self._k, k))
        task = self._task
        if task is not None and task is not asyncio.current_task() and not task.done():
            task.cancel()
        client = self.client
        self.client = None
        self.session_key = None
        self.commands.clear()
        if client is not None:
            # awaited by MeshManager.stop(): BlueZ keeps LE links after the process exits
            self.disconnect_task = self.sched.spawn(self._disconnect(client))
        self.events.on_closed(self, was_paired, reason)

    @staticmethod
    async def _disconnect(client: BleakClient) -> None:
        try:
            await asyncio.wait_for(client.disconnect(), 10)
        except Exception:  # noqa: BLE001
            pass

    def _on_disconnected(self, client: BleakClient) -> None:
        # bleak calls this on the event loop. While connect() is still running, bleak may
        # report a short disconnect (le-connection-abort-by-local) and retry by itself:
        # leave that to connect() / the connect timeout instead of aborting the retry.
        if client is self.client and self.state in (State.PAIRING, State.PAIRED):
            self.close("Verbindung getrennt")

    @staticmethod
    def _err(err: BaseException) -> str:
        """Readable error text - timeouts and some BlueZ errors have an empty message."""
        text = str(err).strip()
        return f"{type(err).__name__}: {text}" if text else type(err).__name__

    async def _gatt(self, name: str, coro_fn: Callable[[], Awaitable]):
        """Run one GATT operation exclusively, with a timeout like the Android op queue."""
        async with self._gatt_lock:
            if self.state == State.CLOSED:
                raise asyncio.CancelledError
            try:
                return await asyncio.wait_for(coro_fn(), OP_TIMEOUT_S)
            except asyncio.TimeoutError:
                self.close(f"GATT-Operation hängt ({name})")
                raise asyncio.CancelledError from None
            except Exception as err:  # noqa: BLE001
                self.close(f"{name} fehlgeschlagen ({self._err(err)})")
                raise asyncio.CancelledError from None

    async def _connect(self, device: BLEDevice | str) -> None:
        try:
            if self.before_connect is not None:
                await self.before_connect()
            kwargs = {"disconnected_callback": self._on_disconnected, "timeout": 20.0}
            if self.adapter:
                kwargs["bluez"] = {"adapter": self.adapter}
            client = BleakClient(device, **kwargs)
            self.client = client
            await client.connect()
            if self.state == State.CLOSED or client is not self.client:
                await self._disconnect(client)
                return
            log.i(f"BLE verbunden mit {self.mac}, prüfe Dienste …")

            svc = client.services.get_service(SERVICE)
            if svc is None:
                self.close("kein Mesh-Dienst")
                return
            status = svc.get_characteristic(CHAR_STATUS)
            command = svc.get_characteristic(CHAR_COMMAND)
            pair = svc.get_characteristic(CHAR_PAIR)
            if status is None or command is None or pair is None:
                self.close("Mesh-Charakteristik fehlt")
                return
            self._cmd_response = "write" in command.properties

            self.state = State.PAIRING
            self._session_random = os.urandom(8)
            pkt = crypto.make_pair_packet(self.mesh_name, self.mesh_password, self._session_random)
            await self._gatt("pair-write", lambda: client.write_gatt_char(pair, pkt, response=True))
            reply = bytes(await self._gatt("pair-read", lambda: client.read_gatt_char(pair)))
            await self._handle_pair_reply(client, status, reply)
        except asyncio.CancelledError:
            pass
        except Exception as err:  # noqa: BLE001
            if self.state != State.CLOSED:
                self.close(f"Verbindungsfehler: {self._err(err)}")

    async def _handle_pair_reply(self, client: BleakClient, status_char, value: bytes) -> None:
        if not value:
            self.close("Pair-Antwort fehlt")
            return
        if value[0] == 0x0D:
            self.session_key = crypto.make_session_key(
                self.mesh_name, self.mesh_password, self._session_random, value[1:9])
            try:
                async with self._gatt_lock:
                    await asyncio.wait_for(client.start_notify(status_char, self._on_notify), OP_TIMEOUT_S)
            except Exception as err:  # noqa: BLE001
                # not fatal: commands still work. But without BlueZ notifications no status
                # arrives at all - lamps then show as offline after 2 minutes. Say so loudly.
                log.e(f"{self.mac}: Status-Benachrichtigungen nicht aktivierbar – Lampen werden "
                      f"schalten, aber nach 2 min als offline gelten ({self._err(err)})")
            if self.state == State.CLOSED:
                return
            # AwoX/Telink: notifications are enabled by writing 0x01 to the status characteristic itself
            await self._gatt("notify-enable",
                             lambda: client.write_gatt_char(status_char, b"\x01", response=True))
            self.sched.remove((self._k, "timeout"))
            self.state = State.PAIRED
            log.i(f"Mesh-Anmeldung an {self.mac} erfolgreich")
            self.events.on_paired(self)
            self._pump()
        elif value[0] == 0x0E:
            self.close("Mesh-Zugangsdaten abgelehnt (anderes Konto / neu gekoppelt?)")
        else:
            self.close(f"unerwartete Pair-Antwort {value.hex()}")

    def _on_notify(self, _char, data: bytearray) -> None:
        key = self.session_key
        if key is None or self.state == State.CLOSED or len(data) < 20:
            return
        self.events.on_packet(self, crypto.decrypt_packet(key, self.mac, bytes(data)))

    # ------------------------------------------------------------------ commands

    def send(self, dest: int, command: int, data: bytes, coalesce: bool = False) -> None:
        """Queue a command. With ``coalesce`` a queued command with the same destination and
        command code is replaced (used for sliders / music mode)."""
        if self.state == State.CLOSED:
            return
        cmd = Cmd(dest, command, bytes(data), coalesce)
        if coalesce:
            for idx, c in enumerate(self.commands):
                if c.coalesce and c.dest == dest and c.command == command:
                    self.commands[idx] = cmd
                    return
        self.commands.append(cmd)
        while len(self.commands) > MAX_QUEUE:
            self.commands.popleft()
        self._pump()

    def send_first(self, dest: int, command: int, data: bytes) -> None:
        self.commands.appendleft(Cmd(dest, command, bytes(data), False))
        self._pump()

    def _pump(self) -> None:
        if self.state != State.PAIRED or self.sched.pending((self._k, "pump")) or not self.commands:
            return
        wait = self._last_command_at + COMMAND_SPACING_S - time.monotonic()
        if wait > 0 or self._gatt_lock.locked():
            self.sched.post_delayed(wait if wait > 0 else 0.03, self._pump, key=(self._k, "pump"))
            return
        cmd = self.commands.popleft()
        key = self.session_key
        client = self.client
        if key is None or client is None:
            return
        pkt = crypto.make_command_packet(key, self.mac, cmd.dest, cmd.command, cmd.data, os.urandom(3))
        self._last_command_at = time.monotonic()
        if not cmd.coalesce or log.verbose:
            log.i(f"TX {cmd.command:02x} an {cmd.dest} via {self.mac[-5:]}: {cmd.data.hex()}")
        self.sched.spawn(self._write_command(client, pkt, f"cmd-{cmd.command:02x}"))
        if self.commands:
            self.sched.post_delayed(COMMAND_SPACING_S, self._pump, key=(self._k, "pump"))

    async def _write_command(self, client: BleakClient, pkt: bytes, name: str) -> None:
        # Like Android's onCharacteristicWrite: a rejected write is logged, the link stays.
        # Only a hanging operation or a lost link closes the connection.
        async with self._gatt_lock:
            if self.state != State.PAIRED or client is not self.client:
                return
            try:
                await asyncio.wait_for(client.write_gatt_char(CHAR_COMMAND, pkt, response=self._cmd_response),
                                       OP_TIMEOUT_S)
            except asyncio.TimeoutError:
                self.close(f"GATT-Operation hängt ({name})")
            except asyncio.CancelledError:
                raise
            except Exception as err:  # noqa: BLE001
                if not client.is_connected:
                    self.close(f"Verbindung verloren ({self._err(err)})")
                else:
                    log.w(f"{self.mac}: {name} fehlgeschlagen ({self._err(err)})")
