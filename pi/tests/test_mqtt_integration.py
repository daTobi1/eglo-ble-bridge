"""Full bridge (fake BLE) against a real MQTT broker (amqtt), as Home Assistant sees it.

Skipped when amqtt is not installed.
"""

from __future__ import annotations

import asyncio
import json
import socket
import subprocess
import sys
import time

import paho.mqtt.client as paho
import pytest

import fake_ble
from eglo_bridge import __main__ as entry
from eglo_bridge import connection, mesh
from eglo_bridge.config import Config, MeshDevice

pytest.importorskip("amqtt")

BROKER = """
import asyncio, sys
from amqtt.broker import Broker
cfg = {"listeners": {"default": {"type": "tcp", "bind": "127.0.0.1:%d"}},
       "plugins": {"amqtt.plugins.authentication.AnonymousAuthPlugin": {"allow_anonymous": True}}}
async def main():
    b = Broker(cfg); await b.start(); await asyncio.Event().wait()
asyncio.run(main())
"""


def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


@pytest.fixture()
def broker():
    port = _free_port()
    proc = subprocess.Popen([sys.executable, "-c", BROKER % port], stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)
    for _ in range(100):
        try:
            socket.create_connection(("127.0.0.1", port), 0.2).close()
            break
        except OSError:
            time.sleep(0.1)
    yield port
    proc.terminate()
    proc.wait(5)


class HA:
    """Plays Home Assistant (and an Android tablet bridge) on the broker."""

    def __init__(self, port: int):
        self.msgs: dict[str, str] = {}
        self.c = paho.Client(paho.CallbackAPIVersion.VERSION2, client_id="ha-test")
        self.c.on_message = lambda _c, _u, m: self.msgs.__setitem__(m.topic, m.payload.decode())
        self.c.connect("127.0.0.1", port)
        self.c.subscribe([("homeassistant/#", 0), ("eglo_bridge/#", 0)])
        self.c.loop_start()

    def pub(self, topic: str, payload: str, retain: bool = False) -> None:
        self.c.publish(topic, payload, 1, retain)

    def close(self) -> None:
        self.c.loop_stop()
        self.c.disconnect()


async def _wait(cond, timeout=8.0):
    end = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > end:
            raise AssertionError("timeout")
        await asyncio.sleep(0.05)


def test_bridge_end_to_end(broker, tmp_path, monkeypatch):
    monkeypatch.setattr(connection, "BleakClient", fake_ble.FakeClient)
    monkeypatch.setattr(mesh, "BleakScanner", fake_ble.FakeScanner)
    monkeypatch.setattr(mesh, "SCAN_S", 0.2)
    fake_ble.LAMPS.clear()
    lamp = fake_ble.FakeLamp("AA:00:00:00:00:03", 3, rssi=-55)
    fake_ble.LAMPS[lamp.mac] = lamp

    cfg = Config(path=tmp_path / "config.json", meshName=fake_ble.MESH_NAME.decode(),
                 meshPassword=fake_ble.MESH_PASS.decode(), mqttHost="127.0.0.1", mqttPort=broker,
                 bridgeName="Pi Küche")
    cfg.devices = [MeshDevice(meshId=3, name="EGLOPanel Küche", mac=lamp.mac, model="EGLO_panel",
                              type=".ble.tlmesh.light.dimming.temperature.color")]
    cfg.save()

    async def run():
        ha = HA(broker)
        bridge = entry.Bridge(cfg.path)
        assert bridge.start()
        try:
            # discovery + availability as Home Assistant expects them
            await _wait(lambda: "homeassistant/light/eglo_bridge_3/config" in ha.msgs)
            disc = json.loads(ha.msgs["homeassistant/light/eglo_bridge_3/config"])
            assert disc["command_topic"] == "eglo_bridge/light/3/set"
            assert "rgb" in disc["supported_color_modes"]
            assert ha.msgs["eglo_bridge/bridge/pi_kueche/status"] == "online"
            info = json.loads(ha.msgs["eglo_bridge/bridge/pi_kueche/info"])
            assert info["name"] == "Pi Küche" and info["platform"] == "linux"
            assert "homeassistant/sensor/eglo_bridge_pi_kueche_state/config" in ha.msgs

            # lamp connected via BLE -> owned by the Pi -> online with state
            await _wait(lambda: ha.msgs.get("eglo_bridge/light/3/availability") == "online")
            assert int(ha.msgs["eglo_bridge/light/3/link/pi_kueche"]) >= 200
            await _wait(lambda: "eglo_bridge/light/3/state" in ha.msgs)

            # HA switches the light on with colour
            ha.pub("eglo_bridge/light/3/set", json.dumps({"state": "ON", "color": {"r": 255, "g": 0, "b": 0}}))
            await _wait(lambda: any(c == 0xE2 for _d, c, _ in lamp.received))
            # a status request queued at connect time may answer with the old (white) state
            # first; the lamp's own report then confirms the colour
            bridge.mesh.request_status(3)
            await _wait(lambda: json.loads(ha.msgs["eglo_bridge/light/3/state"]).get("color_mode") == "rgb")
            st = json.loads(ha.msgs["eglo_bridge/light/3/state"])
            assert st["state"] == "ON" and st["color"] == {"r": 255, "g": 0, "b": 0}

            # a second bridge (Android tablet) reaches the lamp better -> it takes over
            ha.pub("eglo_bridge/bridge/tablet_x/status", "online", retain=True)
            ha.pub("eglo_bridge/bridge/tablet_x/info", json.dumps({"name": "Tablet X"}), retain=True)
            ha.pub("eglo_bridge/light/3/link/tablet_x", "299", retain=True)
            await _wait(lambda: bridge.mqtt.owner_of(3) == "tablet_x")
            before = len(lamp.received)
            ha.pub("eglo_bridge/light/3/set", json.dumps({"state": "OFF"}))
            await asyncio.sleep(0.6)
            assert len(lamp.received) == before  # the tablet executes it, not the Pi

            # tablet goes offline -> Pi owns the lamp again and executes commands
            ha.pub("eglo_bridge/bridge/tablet_x/status", "offline", retain=True)
            await _wait(lambda: bridge.mqtt.owner_of(3) == "pi_kueche")
            ha.pub("eglo_bridge/light/3/set", json.dumps({"state": "OFF"}))
            await _wait(lambda: any(c == 0xD0 and data[0] == 0 for _d, c, data in lamp.received))

            # a stale retained "offline" (old session overlapping a restart) heals itself
            ha.pub("eglo_bridge/bridge/pi_kueche/status", "offline", retain=True)
            await asyncio.sleep(0.3)
            await _wait(lambda: ha.msgs.get("eglo_bridge/bridge/pi_kueche/status") == "online")

            # HA button "reconnect"
            ha.pub("eglo_bridge/bridge/pi_kueche/cmd", "reconnect")
            await _wait(lambda: "Neuverbindung" in " ".join(entry.log.snapshot()[-20:]))
        finally:
            await bridge.stop()
            await _wait(lambda: ha.msgs.get("eglo_bridge/bridge/pi_kueche/status") == "offline")
            assert ha.msgs["eglo_bridge/light/3/link/pi_kueche"] == "0"
            assert ha.msgs["eglo_bridge/light/3/availability"] == "offline"
            ha.close()

    asyncio.run(run())


def test_music_source_skips_bridges_without_microphone():
    from eglo_bridge.mqtt import BridgeInfo, MqttBridge

    b = MqttBridge.__new__(MqttBridge)
    b.bridge_id = "pi_kueche"
    b.music_source_id = None
    b.bridges = {"pi_kueche": BridgeInfo("Pi Küche", True, mic=False),
                 "tablet_x": BridgeInfo("Tablet X", True, mic=True)}
    # "pi_kueche" < "tablet_x" alphabetically, but it cannot listen
    assert b.effective_source() == "tablet_x"


def test_chosen_music_source_without_microphone_is_skipped():
    from eglo_bridge.mqtt import BridgeInfo, MqttBridge

    b = MqttBridge.__new__(MqttBridge)
    b.bridge_id = "tablet_x"
    b.music_source_id = "pi_kueche"   # e.g. stale retained choice
    b.bridges = {"pi_kueche": BridgeInfo("Pi Küche", True, mic=False),
                 "tablet_x": BridgeInfo("Tablet X", True, mic=True)}
    assert b.effective_source() == "tablet_x"
