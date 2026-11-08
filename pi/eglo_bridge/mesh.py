"""Manages up to MAX_CONNECTIONS simultaneous BLE connections into the AwoX/EGLO mesh.

Port of MeshManager.kt. The first connection goes to the best visible lamp; lamps that
are not heard through the mesh get their own direct connection when they are visible.
A permanent scan notices lamps as soon as they advertise again (e.g. after a power cut).
On BlueZ one scanner serves both purposes; it is paused while a connection is set up.
"""

from __future__ import annotations

import asyncio
import time
from collections import deque
from dataclasses import dataclass
from typing import Protocol

from bleak import BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

from . import log
from .config import Config, MeshDevice
from .connection import MeshConnection
from .sched import Scheduler

AWOX_MANUFACTURER_ID = 0x0160

C_POWER = 0xD0
C_COLOR = 0xE2
C_COLOR_BRIGHTNESS = 0xF2
C_WHITE_BRIGHTNESS = 0xF1
C_WHITE_TEMPERATURE = 0xF0
C_REQUEST_STATUS = 0xDA
C_PRESET = 0xC8
C_CANDLE = 0xC9
C_SEQUENCE_COLOR_DURATION = 0xF5
C_SEQUENCE_FADE_DURATION = 0xF6

R_STATUS = 0xDB
R_ONLINE_STATUS = 0xDC

BROADCAST = 0xFFFF

MAX_CONNECTIONS = 3
SCAN_S = 6.0
TICK_S = 15.0
MISSING_AFTER_S = 60.0
OFFLINE_AFTER_S = 120.0
WATCH_REACT_S = 15.0
MIN_RSSI = -97
#: the watch scan is duty-cycled: full-time active discovery on the Pi's combined
#: WLAN/BT chip hurts the BLE links and WLAN (Android used SCAN_MODE_LOW_POWER)
WATCH_ON_S = 5.0
WATCH_OFF_S = 10.0
#: BlueZ forgets non-connected devices ~30 s after discovery stopped; older entries
#: are passed as MAC string so bleak looks the lamp up itself
DEVICE_FRESH_S = 15.0


@dataclass
class LightStatus:
    mesh_id: int
    online: bool
    on: bool
    color_mode: bool
    sequence_mode: bool
    candle_mode: bool
    white_brightness: int
    temperature: int
    color_brightness: int
    r: int
    g: int
    b: int


class MeshListener(Protocol):
    def on_mesh_state(self, state: str) -> None: ...
    def on_status(self, status: LightStatus) -> None: ...
    def on_device_offline(self, mesh_id: int) -> None: ...


def parse_packet(p: bytes) -> LightStatus | None:
    """Decode a decrypted status notification (0xdc online status or 0xdb status)."""
    if len(p) < 20:
        return None
    cmd = p[7]
    if cmd == R_ONLINE_STATUS:
        mode = p[12]
        return LightStatus(
            mesh_id=p[19] * 256 + p[10], online=p[11] != 0,
            on=(mode & 1) == 1, color_mode=((mode >> 1) & 1) == 1,
            sequence_mode=((mode >> 2) & 1) == 1, candle_mode=((mode >> 4) & 1) == 1,
            white_brightness=p[13], temperature=p[14], color_brightness=p[15],
            r=p[16], g=p[17], b=p[18])
    if cmd == R_STATUS:
        mode = p[10]
        return LightStatus(
            mesh_id=p[4] * 256 + p[3], online=True,
            on=(mode & 1) == 1, color_mode=((mode >> 1) & 1) == 1,
            sequence_mode=((mode >> 2) & 1) == 1, candle_mode=((mode >> 4) & 1) == 1,
            white_brightness=p[11], temperature=p[12], color_brightness=p[13],
            r=p[14], g=p[15], b=p[16])
    return None


def _now() -> float:
    return time.monotonic()


class MeshManager:
    def __init__(self, sched: Scheduler, listener: MeshListener, adapter: str = ""):
        self.sched = sched
        self.listener = listener
        self.adapter = adapter

        self.running = False
        self.mesh_name = b""
        self.mesh_password = b""
        self.devices: list[MeshDevice] = []
        #: mesh ids of the lamps this bridge is responsible for; None = all
        self.assigned: set[int] | None = None

        self.connections: dict[str, MeshConnection] = {}
        self.connecting: MeshConnection | None = None
        self.scanning = False
        self._scan_followup = False
        self.scan_rssi: dict[str, int] = {}
        self.last_rssi: dict[str, int] = {}
        self.ble_devices: dict[str, tuple[BLEDevice, float]] = {}

        self.candidates: deque[str] = deque()
        self.attempts_for_mac: dict[str, int] = {}
        self.reconnect_delay = 5.0
        self.tick = 0
        self.primary_cycle = False
        self.futile_scans = 0

        self.last_seen: dict[int, float] = {}
        self.online: set[int] = set()
        self.heard_via: dict[int, str] = {}
        self._last_state_text = ""

        self.watching = False
        self.watch_reacted_at: dict[str, float] = {}
        #: lamps that refused the mesh credentials: the watch does not hammer them
        self.rejected: set[str] = set()

        self._scanner: BleakScanner | None = None
        self._scanner_on = False
        self._scanner_lock = asyncio.Lock()
        self._scanner_failed = False

    @property
    def lights(self) -> list[MeshDevice]:
        """Lamps this bridge is responsible for: only these get direct connections,
        are watched and are reported/controlled (others are left to other bridges)."""
        return [d for d in self.devices if d.is_light and self.is_responsible(d.meshId)]

    def is_responsible(self, mesh_id: int) -> bool:
        return self.assigned is None or mesh_id in self.assigned

    def _paired(self) -> list[MeshConnection]:
        return [c for c in self.connections.values() if c.is_paired]

    # ---------------------------------------------------------------- public API

    def start(self, cfg: Config) -> None:
        self.mesh_name = cfg.meshName.encode()
        self.mesh_password = cfg.meshPassword.encode()
        self.devices = list(cfg.devices)
        self.assigned = None if cfg.assignedLamps is None else set(cfg.assignedLamps)
        if len(self.mesh_name) > 16 or len(self.mesh_password) > 16:
            log.e("Mesh-Name/Passwort länger als 16 Bytes – ungültig")
            return
        self.running = True
        total = sum(1 for d in self.devices if d.is_light)
        if self.assigned is None:
            log.i(f"Mesh-Bridge startet mit {total} Lampen")
        else:
            log.i(f"Mesh-Bridge startet – zuständig für {len(self.lights)} von {total} Lampen: "
                  + (", ".join(d.name.strip() for d in self.lights) or "keine"))
        self._publish_state()
        self._scan_and_connect("Start")
        self.sched.post_delayed(TICK_S, self._tick, key="mesh-tick")

    async def stop(self) -> None:
        self.running = False
        for k in ("mesh-tick", "mesh-reconnect", "mesh-next", "mesh-watch"):
            self.sched.remove(k)
        self.sched.remove("watch-cycle")
        conns = list(self.connections.values())
        if self.connecting is not None and self.connecting not in conns:
            conns.append(self.connecting)
        for c in conns:
            c.close("Stopp")
        self.connections.clear()
        self.watching = False
        await self._set_scanner(False)
        # BlueZ keeps LE links after the process exits: wait for the disconnects
        tasks = [c.disconnect_task for c in conns if c.disconnect_task is not None]
        if tasks:
            await asyncio.wait(tasks, timeout=5)
        self._publish_state()

    def reconnect(self) -> None:
        log.i("Neuverbindung angefordert")
        if self.connecting is not None:
            self.connecting.close("Neuverbindung")
        for c in list(self.connections.values()):
            c.close("Neuverbindung")
        self.attempts_for_mac.clear()
        self.rejected.clear()
        self.reconnect_delay = 3.0
        self._schedule_reconnect(1.5)

    def set_assigned(self, assigned: set[int] | None) -> None:
        """Change the lamps this bridge is responsible for (None = all) at runtime."""
        before = {d.meshId for d in self.lights}
        self.assigned = None if assigned is None else set(assigned)
        after = {d.meshId for d in self.lights}
        if before == after:
            return
        removed, added = before - after, after - before
        names = lambda ids: ", ".join(self.device_name(i) for i in sorted(ids))  # noqa: E731
        if removed:
            log.i(f"Nicht mehr zuständig für: {names(removed)}")
        if added:
            log.i(f"Jetzt zuständig für: {names(added)}")
        if not self.running:
            return
        for mid in removed:
            self.heard_via.pop(mid, None)
            self.last_seen.pop(mid, None)
            if mid in self.online:
                self.online.discard(mid)
            self.listener.on_device_offline(mid)
        # direct links only to assigned lamps: drop the ones to lamps we gave away
        removed_macs = {d.mac for d in self.devices if d.meshId in removed}
        for mac in removed_macs:
            c = self.connections.get(mac)
            if c is not None:
                c.close("nicht mehr zuständig")
        if self.connecting is not None and self.connecting.mac in removed_macs:
            self.connecting.close("nicht mehr zuständig")
        self.candidates = deque(m for m in self.candidates if m not in removed_macs)
        if added:
            self.futile_scans = 0
            for c in self._paired():
                c.send(BROADCAST, C_REQUEST_STATUS, b"\x10")
            if not self._paired() and self.connecting is None:
                self.reconnect_delay = 3.0
                self._schedule_reconnect(1.0)
        if not self.lights:
            self._stop_watch()
        else:
            self._ensure_watch()
        self._publish_state()

    def link_score(self, mesh_id: int) -> int:
        """How well this bridge reaches a lamp (used to pick the bridge that controls it):
        0 = not reachable (or not assigned to this bridge), 100..199 = through the mesh,
        200..299 = direct connection. Signal strength is bucketed to 10 dB so ownership
        does not flap."""
        d = next((x for x in self.devices if x.meshId == mesh_id), None)
        if d is None or not self.is_responsible(mesh_id):
            return 0

        def rssi_part(mac: str) -> int:
            r = max(-99, min(-1, self.last_rssi.get(mac, -90)))
            return 99 + int(r / 10) * 10  # truncation like Kotlin: -45 -> 59, -85 -> 19

        direct = self.connections.get(d.mac)
        if direct is not None and direct.is_paired:
            return 200 + rssi_part(d.mac)
        via = self.heard_via.get(mesh_id)
        if via is not None:
            vc = self.connections.get(via)
            if vc is not None and vc.is_paired and _now() - self.last_seen.get(mesh_id, 0.0) < 120:
                return 100 + rssi_part(via)
        return 0

    def device_name(self, mesh_id: int) -> str:
        d = next((x for x in self.devices if x.meshId == mesh_id), None)
        return d.name.strip() if d else f"#{mesh_id}"

    def _name_for_mac(self, mac: str) -> str:
        d = next((x for x in self.devices if x.mac == mac), None)
        return d.name.strip() if d else mac

    # ---------------------------------------------------------------- state text

    def _publish_state(self) -> None:
        paired = self._paired()
        if not self.running:
            text = "gestoppt"
        elif paired:
            text = "verbunden: " + ", ".join(f"{self._name_for_mac(c.mac)} ({c.mac[-5:]})" for c in paired)
        elif self.connecting is not None:
            text = f"verbinde mit {self.connecting.mac}"
        elif self.scanning:
            text = "suche Lampen"
        else:
            text = "warte auf Lampen"
        if text != self._last_state_text:
            self._last_state_text = text
            self.listener.on_mesh_state(text)

    # ---------------------------------------------------------------- periodic maintenance

    def _tick(self) -> None:
        if not self.running:
            return
        self.tick += 1
        paired = self._paired()
        for c in paired:
            c.send(BROADCAST, C_REQUEST_STATUS, b"\x10")
        now = _now()
        for mid in list(self.online):
            if now - self.last_seen.get(mid, 0.0) > OFFLINE_AFTER_S:
                self.online.discard(mid)
                self.heard_via.pop(mid, None)
                log.w(f"{self.device_name(mid)} seit 2 Minuten ohne Meldung -> offline")
                self.listener.on_device_offline(mid)
        # look for lamps not reachable through the existing connections:
        # every minute at first, every 10 minutes after repeated misses
        every = 4 if self.futile_scans < 3 else 40
        if (paired and self.tick % every == 0 and self.connecting is None and not self.scanning
                and len(self.connections) < MAX_CONNECTIONS and self._missing_lights()):
            self._scan_for_missing()
        self._ensure_watch()
        self.sched.post_delayed(TICK_S, self._tick, key="mesh-tick")

    def _missing_lights(self) -> list[MeshDevice]:
        now = _now()
        return [d for d in self.lights
                if now - self.last_seen.get(d.meshId, 0.0) > MISSING_AFTER_S and d.mac not in self.connections]

    # ---------------------------------------------------------------- scanner (BlueZ)

    async def _set_scanner(self, on: bool, from_watch: bool = False) -> bool:
        """Start/stop the single BlueZ scanner. Returns False if it could not be started.
        Requests of the watch cycle are dropped while a scan or a connection attempt runs."""
        async with self._scanner_lock:
            if from_watch and (self.scanning or (on and self.connecting is not None) or not self.watching):
                return True
            if on == self._scanner_on:
                return True
            try:
                if on:
                    if self._scanner is None:
                        kwargs = {"detection_callback": self._on_advertisement}
                        if self.adapter:
                            kwargs["bluez"] = {"adapter": self.adapter}
                        self._scanner = BleakScanner(**kwargs)
                    await self._scanner.start()
                    self._scanner_on = True
                    if self._scanner_failed:
                        log.i("Bluetooth-Scan läuft wieder")
                    self._scanner_failed = False
                else:
                    self._scanner_on = False
                    if self._scanner is not None:
                        await self._scanner.stop()
                return True
            except Exception as err:  # noqa: BLE001
                if on:
                    if not self._scanner_failed or log.verbose:
                        log.w(f"Bluetooth-Scan nicht möglich: {err}")
                    self._scanner_failed = True
                    self._scanner = None
                return False

    async def _scanner_idle(self) -> None:
        """Called before a connection attempt: connecting during discovery is unreliable."""
        await self._set_scanner(False)

    def _on_advertisement(self, device: BLEDevice, adv: AdvertisementData) -> None:
        if not self.running:
            return
        mac = device.address.upper()
        known = any(d.mac == mac for d in self.devices)
        awox = AWOX_MANUFACTURER_ID in (adv.manufacturer_data or {})
        if not known and not awox:
            return
        self.ble_devices[mac] = (device, _now())
        rssi = adv.rssi
        if self.scanning:
            if mac not in self.scan_rssi:
                log.i(f"Gefunden: {self._name_for_mac(mac) if known else 'unbekannt'} {mac} RSSI {rssi}")
            self.scan_rssi[mac] = rssi
            self.last_rssi[mac] = rssi
        elif known:
            self._on_watch_seen(mac, rssi)

    def _start_scan(self, purpose: str, then) -> None:
        self.sched.spawn(self._scan_phase(purpose, then))

    async def _scan_phase(self, purpose: str, then) -> None:
        self.scan_rssi.clear()
        self.scanning = True
        self._scan_followup = True
        self._publish_state()
        try:
            self.sched.remove("watch-cycle")
            # restart discovery: BlueZ only re-reports a device after discovery was restarted
            # (or its RSSI changed by 8 dB), so a running watch scan would hear nothing new
            await self._set_scanner(False)
            if not await self._set_scanner(True):
                self.scanning = False
                self._publish_state()
                if purpose != "fehlende Lampen":
                    log.w("Bluetooth nicht bereit – neuer Versuch in 15 s")
                    self._schedule_reconnect(15.0)
                return
            await asyncio.sleep(SCAN_S)
            self.scanning = False
            self._publish_state()
            self._ensure_watch()
            # give the controller a moment before connecting
            await asyncio.sleep(0.5)
            if self.running:
                then()
        finally:
            # BlueZ reports advertisements immediately (unlike Android's batched low-power
            # scan): the watch must not start its own attempt while the scan result is handled
            self._scan_followup = False

    def _ensure_watch(self) -> None:
        if not self.running or self.scanning or self.connecting is not None:
            return
        if not self.lights:
            return
        self.watching = True
        if not self.sched.pending("watch-cycle"):
            self._watch_cycle()

    def _watch_cycle(self) -> None:
        """Listen WATCH_ON_S, pause WATCH_OFF_S, repeat - while idle."""
        if not self.watching or not self.running or self.scanning or self.connecting is not None:
            return
        if self._scanner_on:
            self.sched.spawn(self._set_scanner(False, from_watch=True))
            delay = WATCH_OFF_S
        else:
            self.sched.spawn(self._set_scanner(True, from_watch=True))
            delay = WATCH_ON_S
        self.sched.post_delayed(delay, self._watch_cycle, key="watch-cycle")

    def _stop_watch(self) -> None:
        self.watching = False
        self.sched.remove("watch-cycle")
        if self._scanner_on and not self.scanning:
            self.sched.spawn(self._set_scanner(False))

    # ---------------------------------------------------------------- connecting

    def _scan_and_connect(self, why: str) -> None:
        if not self.running or self.connecting is not None or self.scanning:
            return
        if not self.lights:
            if any(d.is_light for d in self.devices):
                log.w("Dieser Bridge ist keine Lampe zugeordnet – sie verbindet sich mit keiner Lampe")
            else:
                log.e("Keine Lampen konfiguriert – bitte zuerst aus der AwoX-Cloud importieren")
            return

        def after() -> None:
            known = list(dict.fromkeys(d.mac for d in self.lights if d.mac not in self.connections))
            seen = sorted((m for m in known if m in self.scan_rssi), key=lambda m: -self.scan_rssi[m])
            unseen = [m for m in known if m not in self.scan_rssi]
            self.candidates = deque(seen + unseen)
            self.attempts_for_mac.clear()
            self.primary_cycle = True
            log.i("Im Scan gefunden: " + (", ".join(f"{self._name_for_mac(m)} ({self.scan_rssi[m]})"
                                                    for m in seen) or "keine bekannte Lampe"))
            self._try_next_candidate()

        self._start_scan(why, after)

    def _scan_for_missing(self) -> None:
        def after() -> None:
            missing = self._missing_lights()
            visible = sorted((d for d in missing if self.scan_rssi.get(d.mac, -999) >= MIN_RSSI),
                             key=lambda d: -self.scan_rssi[d.mac])
            if not visible:
                self.futile_scans += 1
                log.i("Nicht erreichbar: " + ", ".join(d.name.strip() for d in missing)
                      + " – nicht direkt sichtbar")
                return
            self.primary_cycle = False
            log.i("Direktverbindung zusätzlich zu: "
                  + ", ".join(f"{d.name.strip()} ({self.scan_rssi[d.mac]})" for d in visible))
            self.candidates = deque(d.mac for d in visible)
            self.attempts_for_mac.clear()
            self._try_next_candidate()

        self._start_scan("fehlende Lampen", after)

    def _try_next_candidate(self) -> None:
        if not self.running or self.connecting is not None:
            return
        if len(self.connections) >= MAX_CONNECTIONS:
            return
        mac = self.candidates.popleft() if self.candidates else None
        while mac is not None and mac in self.connections:
            mac = self.candidates.popleft() if self.candidates else None
        if mac is None:
            if not self._paired():
                log.w(f"Keine Lampe erreichbar – neuer Versuch in {int(self.reconnect_delay)} s")
                self._schedule_reconnect(self.reconnect_delay)
                self.reconnect_delay = min(self.reconnect_delay * 2, 30.0)
            self._publish_state()
            return
        self._stop_watch()
        c = MeshConnection(self.sched, mac, self.mesh_name, self.mesh_password, self,
                           adapter=self.adapter, before_connect=self._scanner_idle)
        self.connecting = c
        self.connections[mac] = c
        self._publish_state()
        entry = self.ble_devices.get(mac)
        fresh = entry is not None and _now() - entry[1] < DEVICE_FRESH_S
        c.connect(entry[0] if fresh else mac)

    def _schedule_reconnect(self, delay: float) -> None:
        self.sched.remove("mesh-reconnect")
        self.sched.post_delayed(delay, self._reconnect_run, key="mesh-reconnect")

    def _reconnect_run(self) -> None:
        if self.running and not self._paired():
            self._scan_and_connect("Neuverbindung")

    def _later_next_candidate(self, delay: float) -> None:
        self.sched.post_delayed(delay, self._try_next_candidate, key="mesh-next")

    # ---------------------------------------------------------------- connection events

    def on_paired(self, c: MeshConnection) -> None:
        if self.connecting is c:
            self.connecting = None
        self.rejected.discard(c.mac)
        self.sched.post_delayed(3.0, self._ensure_watch, key="mesh-watch")
        self.reconnect_delay = 5.0
        self.attempts_for_mac.pop(c.mac, None)
        if self.primary_cycle:
            # first connection is up: further lamps are only added by the periodic
            # "missing lamps" scan, so the link is not disturbed by hopeless attempts
            self.candidates.clear()
            self.primary_cycle = False
        else:
            self.futile_scans = 0
        self._publish_state()
        c.send_first(BROADCAST, C_REQUEST_STATUS, b"\x10")
        d = next((x for x in self.devices if x.mac == c.mac), None)
        if d is not None:
            c.send(d.meshId, C_REQUEST_STATUS, b"\x10")
        self._later_next_candidate(1.0)

    def on_closed(self, c: MeshConnection, was_paired: bool, reason: str) -> None:
        if self.connections.get(c.mac) is c:
            del self.connections[c.mac]
        was_connecting = self.connecting is c
        if was_connecting:
            self.connecting = None
        self.sched.post_delayed(2.0, self._ensure_watch, key="mesh-watch")
        for mid in [k for k, v in self.heard_via.items() if v == c.mac]:
            del self.heard_via[mid]
        if not self.running:
            return

        if "abgelehnt" in reason:
            self.rejected.add(c.mac)
        if was_paired:
            log.w(f"Verbindung zu {self._name_for_mac(c.mac)} getrennt ({reason})")
        else:
            log.w(f"Verbindung zu {self._name_for_mac(c.mac)} fehlgeschlagen ({reason})")
            n = self.attempts_for_mac.get(c.mac, 0) + 1
            self.attempts_for_mac[c.mac] = n
            # BlueZ, too, fails a first attempt now and then: retry once
            if n < 2 and c.mac in self.scan_rssi:
                self.candidates.appendleft(c.mac)
            elif self._paired():
                self.futile_scans += 1

        if not self._paired():
            for mid in list(self.online):
                self.listener.on_device_offline(mid)
            self.online.clear()
        self._publish_state()

        if was_connecting and self.candidates:
            self._later_next_candidate(1.5)
        elif not self._paired() and self.connecting is None:
            if was_paired:
                self.reconnect_delay = 3.0
                self._schedule_reconnect(self.reconnect_delay)
            else:
                self._later_next_candidate(1.5)

    def on_packet(self, c: MeshConnection, p: bytes) -> None:
        if log.verbose:
            log.i(f"RX {p[7]:02x} via {c.mac[-5:]}: {p.hex()}")
        status = parse_packet(p)
        if status is None:
            return
        if not any(d.meshId == status.mesh_id for d in self.devices):
            if log.verbose:
                log.w(f"Meldung von unbekannter Mesh-ID {status.mesh_id}")
            return
        if not self.is_responsible(status.mesh_id):
            return  # another bridge reports this lamp
        if status.online:
            self.last_seen[status.mesh_id] = _now()
            self.heard_via[status.mesh_id] = c.mac
            if status.mesh_id not in self.online:
                self.online.add(status.mesh_id)
                log.i(f"{self.device_name(status.mesh_id)} online (über {self._name_for_mac(c.mac)})")
            self.listener.on_status(status)
        else:
            if status.mesh_id in self.online:
                self.online.discard(status.mesh_id)
                log.w(f"{self.device_name(status.mesh_id)} meldet offline")
            self.heard_via.pop(status.mesh_id, None)
            self.listener.on_device_offline(status.mesh_id)

    # ---------------------------------------------------------------- watch (fast re-detection)

    def _on_watch_seen(self, mac: str, rssi: int) -> None:
        """A known lamp advertises. If it is missing, ask the mesh for its state or connect to it."""
        self.last_rssi[mac] = rssi
        d = next((x for x in self.lights if x.mac == mac), None)
        if d is None or mac in self.connections or self._scan_followup or mac in self.rejected:
            return
        now = _now()
        missing = d.meshId not in self.online or now - self.last_seen.get(d.meshId, 0.0) > MISSING_AFTER_S
        if not missing:
            return
        if now - self.watch_reacted_at.get(mac, -1e9) < WATCH_REACT_S:
            return
        self.watch_reacted_at[mac] = now
        paired = self._paired()
        log.i(f"{d.name.strip()} sendet wieder (RSSI {rssi})")
        if not paired:
            # no connection at all: connect straight to this lamp
            self.sched.remove("mesh-reconnect")
            if self.connecting is None and not self.scanning:
                self.scan_rssi[mac] = rssi
                self.candidates = deque([mac])
                self.attempts_for_mac.clear()
                self.primary_cycle = True
                self._try_next_candidate()
            return
        # first ask through the mesh - cheap and fast if the lamp is reachable there
        for c in paired:
            c.send(d.meshId, C_REQUEST_STATUS, b"\x10")

        def check() -> None:
            if not self.running:
                return
            t = _now()
            still_missing = d.meshId not in self.online or t - self.last_seen.get(d.meshId, 0.0) > 5.0
            if (still_missing and self.connecting is None and not self.scanning
                    and len(self.connections) < MAX_CONNECTIONS and mac not in self.connections
                    and rssi >= MIN_RSSI):
                log.i(f"{d.name.strip()} nicht über das Mesh erreichbar – verbinde direkt")
                self.scan_rssi[mac] = rssi
                self.primary_cycle = False
                self.candidates = deque([mac])
                self.attempts_for_mac.clear()
                self._try_next_candidate()

        self.sched.post_delayed(4.0, check)

    # ---------------------------------------------------------------- commands

    def _route(self, dest: int, command: int, data: bytes, coalesce: bool) -> None:
        paired = self._paired()
        if not paired:
            log.w(f"Nicht verbunden – Befehl {command:02x} für {self.device_name(dest)} verworfen")
            return
        if dest == BROADCAST:
            for c in paired:
                c.send(dest, command, data, coalesce)
            return
        d = next((x for x in self.devices if x.meshId == dest), None)
        direct = self.connections.get(d.mac) if d else None
        if direct is not None and direct.is_paired:
            direct.send(dest, command, data, coalesce)
            return
        via_mac = self.heard_via.get(dest)
        via = self.connections.get(via_mac) if via_mac else None
        if via is not None and via.is_paired:
            via.send(dest, command, data, coalesce)
            return
        # unknown route: send through every connection, the mesh will forward it
        for c in paired:
            c.send(dest, command, data, coalesce)

    def set_power(self, dest: int, on: bool) -> None:
        self._route(dest, C_POWER, bytes([1 if on else 0, 0, 0]), False)

    def set_color(self, dest: int, r: int, g: int, b: int, coalesce: bool = True) -> None:
        self._route(dest, C_COLOR, bytes([0x04, r & 0xFF, g & 0xFF, b & 0xFF]), coalesce)

    def set_color_brightness(self, dest: int, v: int, coalesce: bool = True) -> None:
        self._route(dest, C_COLOR_BRIGHTNESS, bytes([v & 0xFF]), coalesce)

    def set_white_brightness(self, dest: int, v: int) -> None:
        self._route(dest, C_WHITE_BRIGHTNESS, bytes([v & 0xFF]), True)

    def set_white_temperature(self, dest: int, v: int) -> None:
        self._route(dest, C_WHITE_TEMPERATURE, bytes([v & 0xFF]), True)

    def set_preset(self, dest: int, preset: int) -> None:
        self._route(dest, C_PRESET, bytes([preset & 0xFF]), False)

    def set_candle(self, dest: int) -> None:
        self._route(dest, C_CANDLE, b"\x00", False)

    def set_sequence_color_duration(self, dest: int, ms: int) -> None:
        self._route(dest, C_SEQUENCE_COLOR_DURATION, (ms & 0xFFFFFFFF).to_bytes(4, "little"), True)

    def set_sequence_fade_duration(self, dest: int, ms: int) -> None:
        self._route(dest, C_SEQUENCE_FADE_DURATION, (ms & 0xFFFFFFFF).to_bytes(4, "little"), True)

    def request_status(self, dest: int) -> None:
        self._route(dest, C_REQUEST_STATUS, b"\x10", True)
