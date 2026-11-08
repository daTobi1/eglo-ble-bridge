"""End-to-end simulation: mesh manager + light controller against fake Telink lamps."""

from __future__ import annotations

import asyncio

import pytest

import fake_ble
from eglo_bridge import connection, mesh
from eglo_bridge.config import Config, MeshDevice
from eglo_bridge.lights import LightController
from eglo_bridge.sched import Scheduler


class Listener:
    def __init__(self):
        self.states: list[str] = []
        self.status: dict[int, mesh.LightStatus] = {}
        self.offline: list[int] = []
        self.lights: LightController | None = None

    def on_mesh_state(self, s):
        self.states.append(s)

    def on_status(self, st):
        self.status[st.mesh_id] = st
        if self.lights:
            self.lights.on_status(st)

    def on_device_offline(self, mid):
        self.offline.append(mid)


@pytest.fixture(autouse=True)
def patch_ble(monkeypatch):
    monkeypatch.setattr(connection, "BleakClient", fake_ble.FakeClient)
    monkeypatch.setattr(mesh, "BleakScanner", fake_ble.FakeScanner)
    monkeypatch.setattr(mesh, "SCAN_S", 0.2)
    fake_ble.LAMPS.clear()
    yield
    fake_ble.LAMPS.clear()


def _cfg(*lamps: tuple[int, str, str]) -> Config:
    cfg = Config(meshName=fake_ble.MESH_NAME.decode(), meshPassword=fake_ble.MESH_PASS.decode(), mqttHost="x")
    cfg.devices = [MeshDevice(meshId=i, name=n, mac=m, type=".ble.tlmesh.light.dimming.temperature.color")
                   for i, n, m in lamps]
    return cfg


async def _wait(cond, timeout=5.0):
    end = asyncio.get_running_loop().time() + timeout
    while not cond():
        if asyncio.get_running_loop().time() > end:
            raise AssertionError("timeout")
        await asyncio.sleep(0.02)


def test_connect_pair_status_and_commands():
    async def run():
        fake_ble.LAMPS["AA:00:00:00:00:03"] = fake_ble.FakeLamp("AA:00:00:00:00:03", 3, rssi=-55, relays=[5])
        fake_ble.LAMPS["AA:00:00:00:00:05"] = fake_ble.FakeLamp("AA:00:00:00:00:05", 5, visible=False)
        sched = Scheduler(asyncio.get_running_loop())
        lst = Listener()
        m = mesh.MeshManager(sched, lst)
        lc = LightController(sched, m, _cfg((3, "Küche", "AA:00:00:00:00:03"),
                                            (5, "Wohnzimmer", "AA:00:00:00:00:05")).devices)
        lst.lights = lc
        m.start(_cfg((3, "Küche", "AA:00:00:00:00:03"), (5, "Wohnzimmer", "AA:00:00:00:00:05")))

        # connects to the visible lamp, learns both lamps through the mesh
        await _wait(lambda: 3 in lst.status and 5 in lst.status)
        assert any(s.startswith("verbunden: Küche") for s in lst.states)
        assert m.link_score(3) >= 200          # direct
        assert 100 <= m.link_score(5) < 200    # through the mesh
        assert lc.is_available(5)

        # HA command for the lamp behind the mesh is routed through the connected lamp
        lc.command(5, {"state": "ON", "brightness": 255, "color_temp": 2700})
        lamp3 = fake_ble.LAMPS["AA:00:00:00:00:03"]
        await _wait(lambda: any(c == 0xF0 and d == 5 for d, c, _ in lamp3.received))
        sent = [(d, c) for d, c, _ in lamp3.received if d == 5]
        assert (5, 0xF1) in sent  # white brightness
        assert (5, 0xF0) in sent  # temperature 2700 K -> 0x7f
        temp = next(data for d, c, data in lamp3.received if d == 5 and c == 0xF0)
        assert temp[0] == 0x7F

        lc.command(3, {"state": "OFF"})
        await _wait(lambda: not lamp3.on and any(c == 0xD0 for d, c, _ in lamp3.received if d == 3))
        await m.stop()

    asyncio.run(run())


def test_rejected_credentials_retry_and_backoff():
    async def run():
        fake_ble.LAMPS["AA:00:00:00:00:07"] = fake_ble.FakeLamp("AA:00:00:00:00:07", 7, reject=True)
        sched = Scheduler(asyncio.get_running_loop())
        lst = Listener()
        m = mesh.MeshManager(sched, lst)
        m.start(_cfg((7, "Flur", "AA:00:00:00:00:07")))
        connects = []
        orig = connection.MeshConnection.connect
        connection.MeshConnection.connect = lambda self, dev: (connects.append(self.mac), orig(self, dev))[1]
        try:
            # first attempt + exactly one retry, then back off (no parallel/duplicate attempts)
            await _wait(lambda: sched.pending("mesh-reconnect"), timeout=8)
            assert m.attempts_for_mac["AA:00:00:00:00:07"] == 2
            assert len(connects) == 2
            assert not m._paired()
        finally:
            connection.MeshConnection.connect = orig
        await m.stop()

    asyncio.run(run())


def test_lamp_returns_after_power_cut_is_reconnected_by_watch():
    async def run():
        lamp = fake_ble.FakeLamp("AA:00:00:00:00:09", 9, visible=False)
        fake_ble.LAMPS[lamp.mac] = lamp
        sched = Scheduler(asyncio.get_running_loop())
        lst = Listener()
        m = mesh.MeshManager(sched, lst)
        m.start(_cfg((9, "Bad", lamp.mac)))
        await _wait(lambda: sched.pending("mesh-reconnect"), timeout=8)
        assert not m._paired()
        # power back: the lamp advertises again -> the watch scan connects without waiting
        lamp.visible = True
        await _wait(lambda: 9 in lst.status, timeout=8)
        await m.stop()

    asyncio.run(run())


def test_rejected_write_keeps_link_and_short_disconnect_during_connect_is_ignored(monkeypatch):
    async def run():
        lamp = fake_ble.FakeLamp("AA:00:00:00:00:04", 4)
        fake_ble.LAMPS[lamp.mac] = lamp
        sched = Scheduler(asyncio.get_running_loop())
        lst = Listener()
        m = mesh.MeshManager(sched, lst)
        m.start(_cfg((4, "Gang", lamp.mac)))
        await _wait(lambda: m._paired())
        conn = m.connections[lamp.mac]

        # the lamp rejects one write (ATT error) -> logged, link stays (like Android)
        orig = fake_ble.FakeClient.write_gatt_char
        calls = {"n": 0}

        async def flaky(self, char, data, response=True):
            calls["n"] += 1
            if calls["n"] == 1:
                raise RuntimeError("org.bluez.Error.Failed: ATT error 0x0e")
            return await orig(self, char, data, response)

        monkeypatch.setattr(fake_ble.FakeClient, "write_gatt_char", flaky)
        m.set_power(4, True)
        m.set_power(4, False)
        await _wait(lambda: any(c == 0xD0 for _d, c, _ in lamp.received))
        assert conn.is_paired and m.connections.get(lamp.mac) is conn

        # bleak reporting a disconnect while still connecting must not abort its own retry
        c2 = connection.MeshConnection(sched, "AA:00:00:00:00:99", b"x", b"y", m)
        c2.state = connection.State.CONNECTING
        c2.client = object()
        c2._on_disconnected(c2.client)
        assert not c2.is_closed
        await m.stop()

    asyncio.run(run())


def test_stale_device_is_passed_as_mac(monkeypatch):
    async def run():
        sched = Scheduler(asyncio.get_running_loop())
        m = mesh.MeshManager(sched, Listener())
        m.running = True
        m.devices = _cfg((6, "Bad", "AA:00:00:00:00:06")).devices
        dev = fake_ble.FakeDevice("AA:00:00:00:00:06")
        m.ble_devices["AA:00:00:00:00:06"] = (dev, mesh._now() - 60)
        got = []
        monkeypatch.setattr(connection.MeshConnection, "connect", lambda self, d: got.append(d))
        m.candidates.append("AA:00:00:00:00:06")
        m._try_next_candidate()
        assert got == ["AA:00:00:00:00:06"]          # stale -> MAC string, bleak looks it up
        m.connections.clear(); m.connecting = None
        m.ble_devices["AA:00:00:00:00:06"] = (dev, mesh._now())
        m.candidates.append("AA:00:00:00:00:06")
        m._try_next_candidate()
        assert got[-1] is dev                        # fresh -> BLEDevice
        m.running = False

    asyncio.run(run())


def test_assignment_limits_connections_and_reports():
    async def run():
        # both lamps visible, they hear each other through the mesh
        fake_ble.LAMPS["AA:00:00:00:00:03"] = fake_ble.FakeLamp("AA:00:00:00:00:03", 3, rssi=-50, relays=[5])
        fake_ble.LAMPS["AA:00:00:00:00:05"] = fake_ble.FakeLamp("AA:00:00:00:00:05", 5, rssi=-80, relays=[3])
        sched = Scheduler(asyncio.get_running_loop())
        lst = Listener()
        m = mesh.MeshManager(sched, lst)
        cfg = _cfg((3, "Küche", "AA:00:00:00:00:03"), (5, "Wohnzimmer", "AA:00:00:00:00:05"))
        cfg.assignedLamps = [5]
        m.start(cfg)

        # only the assigned lamp gets a connection although the other one is stronger
        await _wait(lambda: 5 in lst.status)
        assert set(m.connections) == {"AA:00:00:00:00:05"}
        assert m.link_score(5) >= 200
        await asyncio.sleep(0.3)
        assert 3 not in lst.status          # status of the other lamp is ignored
        assert m.link_score(3) == 0         # -> never owned by this bridge

        # give lamp 5 away, take lamp 3: the link is moved
        m.set_assigned({3})
        assert 5 in lst.offline
        await _wait(lambda: 3 in lst.status and "AA:00:00:00:00:03" in m.connections, timeout=8)
        assert "AA:00:00:00:00:05" not in m.connections
        assert m.link_score(5) == 0

        # all lamps again
        m.set_assigned(None)
        await _wait(lambda: m.link_score(5) > 0, timeout=8)
        await m.stop()

    asyncio.run(run())
