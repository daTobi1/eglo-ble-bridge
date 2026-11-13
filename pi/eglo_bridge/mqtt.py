"""MQTT side of a bridge incl. coordination between several bridges (port of MqttBridge.kt).

- every bridge publishes how well it reaches each lamp (link score); the bridge with the best
  score "owns" the lamp: it executes commands and publishes its state
- lamps, music mode, music source, effect tempo and the per-lamp music selection are shared
  entities in Home Assistant; per-bridge entities (status, log, debug, reconnect) exist per bridge
- the selected music-source bridge listens with its microphone and publishes music frames;
  every bridge applies them to the lamps it owns

Kiosk, battery and APK update entities of the Android app are not part of the Pi port.
All handlers run on the event loop; paho's network thread only hands messages over.
"""

from __future__ import annotations

import json
import os
import platform
import re
import shutil
import socket
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import paho.mqtt.client as paho

from . import log
from .config import Config, MeshDevice
from .lights import (EFFECT_MUSIC, EFFECT_NONE, EFFECTS, MAX_K, MIN_K, LightController, LightState,
                     parse_state)
from .mesh import MeshManager
from .sched import Scheduler

BASE = "eglo_bridge"
MUSIC = f"{BASE}/music"
MUSIC_SET = f"{BASE}/music/set"
MUSIC_FRAME = f"{BASE}/music/frame"
SOURCE = f"{BASE}/music/source"
SOURCE_NAME = f"{BASE}/music/source_name"
SOURCE_SET = f"{BASE}/music/source/set"
SENS = f"{BASE}/music_sensitivity"
SENS_SET = f"{BASE}/music_sensitivity/set"
SPEED = f"{BASE}/effect_speed"
SPEED_SET = f"{BASE}/effect_speed/set"
FIRE_SPEED = f"{BASE}/fire_speed"
FIRE_STRENGTH = f"{BASE}/fire_strength"
FIRE_BRIGHT = f"{BASE}/fire_brightness"

RECOMPUTE_S = 10.0

#: per-lamp effect settings (key, HA name, icon, min)
LAMP_SETTINGS = [
    ("fire_speed", "Lagerfeuer-Tempo", "mdi:fire", 0),
    ("fire_strength", "Lagerfeuer-Stärke", "mdi:fire-alert", 0),
    ("fire_brightness", "Lagerfeuer-Helligkeit", "mdi:brightness-6", 5),
    ("effect_speed", "Effekt-Tempo", "mdi:speedometer", 0),
]
LAMP_SETTING_KEYS = [s[0] for s in LAMP_SETTINGS]


def slug(name: str) -> str:
    s = name.lower().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    s = re.sub(r"[^a-z0-9]+", "_", s).strip("_")
    return s or "tablet"


def _to_int(v: str) -> int | None:
    try:
        return int(float(v))
    except ValueError:
        return None


def _hw_model() -> str:
    try:
        return Path("/proc/device-tree/model").read_text().strip("\x00\n ") or "Linux"
    except OSError:
        return "Linux"


def device_info() -> dict:
    """Facts about this computer for the retained config topic."""
    info: dict = {"hostname": socket.gethostname(), "model": _hw_model(), "python": platform.python_version()}
    try:
        for line in Path("/etc/os-release").read_text().splitlines():
            if line.startswith("PRETTY_NAME="):
                info["os"] = line.split("=", 1)[1].strip('"')
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.0.2.1", 9))  # no packet is sent; picks the outgoing interface
        info["ip"] = s.getsockname()[0]
        s.close()
    except OSError:
        pass
    try:
        info["uptime_h"] = round(float(Path("/proc/uptime").read_text().split()[0]) / 3600, 1)
    except (OSError, ValueError):
        pass
    try:
        info["storage_free_mb"] = shutil.disk_usage("/").free // (1024 * 1024)
    except OSError:
        pass
    try:
        info["cpu_temp_c"] = round(int(Path("/sys/class/thermal/thermal_zone0/temp").read_text()) / 1000, 1)
    except (OSError, ValueError):
        pass
    try:
        info["load"] = round(os.getloadavg()[0], 2)
    except OSError:
        pass
    return info


def test_connection(host: str, port: int, user: str, password: str) -> bool:
    """True if a broker accepts these credentials (blocking, a few seconds at most)."""
    import threading
    ok = threading.Event()
    done = threading.Event()
    c = paho.Client(paho.CallbackAPIVersion.VERSION2, client_id=f"eglo-bridge-test-{int(time.time()) % 100000}")
    if user:
        c.username_pw_set(user, password)

    def on_connect(_c, _u, _f, reason_code, _p) -> None:
        if not reason_code.is_failure:
            ok.set()
        done.set()
    c.on_connect = on_connect
    try:
        c.connect(host, int(port), keepalive=10)
        c.loop_start()
        done.wait(8)
        c.disconnect()
        c.loop_stop()
    except Exception:  # noqa: BLE001
        return False
    return ok.is_set()


@dataclass
class BridgeInfo:
    name: str
    online: bool
    #: bridge can listen (microphone); old Android builds do not announce it -> assume yes
    mic: bool = True


class MqttBridge:
    def __init__(self, sched: Scheduler, mesh: MeshManager, lights: LightController,
                 version: str, bridge_id: str, bridge_name: str, has_mic: bool):
        self.sched = sched
        self.mesh = mesh
        self.lights = lights
        self.version = version
        self.bridge_id = bridge_id
        self.bridge_name = bridge_name
        self.has_mic = has_mic

        t = f"{BASE}/bridge/{bridge_id}"
        self.avail = f"{t}/status"
        self.info = f"{t}/info"
        self.state_text = f"{t}/state"
        self.log_topic = f"{t}/log"
        self.cmd_topic = f"{t}/cmd"
        self.debug_topic = f"{t}/debug"
        self.debug_set = f"{t}/debug/set"
        self.mic_topic = f"{t}/mic"
        #: which lamps this bridge is responsible for (per-bridge switches in HA)
        self.lamp_assign = f"{t}/lamp"
        self.all_assign = f"{t}/lamps_all"
        #: display control (only when the Pi has a backlight, see screen.py)
        self.screen_topic = f"{t}/screen"
        self.brightness_topic = f"{t}/brightness"
        self.screen_timeout_topic = f"{t}/screen_timeout"
        self.screen = None
        self.config_topic = f"{t}/config"
        self.config_set = f"{t}/config/set"
        #: settings changed remotely (dict from config/set)
        self.on_config_set: Callable[[dict], None] | None = None
        self.cfg: Config | None = None
        #: last payload of every received topic (for the MCP endpoint, see mcp.py)
        self.topic_cache: dict[str, str] = {}

        #: commands for the service: restart, reimport
        self.on_service_command: Callable[[str], None] | None = None
        #: the lamp assignment was changed in Home Assistant (list of mesh ids, None = all)
        self.on_assignment_changed: Callable[[list[int] | None], None] | None = None
        self.mic_state = ""

        self.client: paho.Client | None = None
        self._connected = False
        self._ever_connected = False
        self.devices: list[MeshDevice] = []
        self.bridge_state = "startet"
        self.running = False

        self.bridges: dict[str, BridgeInfo] = {}
        self.links: dict[int, dict[str, int]] = {}
        self.my_links: dict[int, int] = {}
        self.published: dict[int, int] = {}
        self.remote_states: dict[int, LightState] = {}
        self.lamp_avail: dict[int, bool] = {}
        self.selection: dict[int, bool] = {}
        self.music_on = False
        self.music_source_id: str | None = None
        self.sensitivity_pct = 100
        self.speed = 50
        self._last_source_options = ""
        self._speed_known = False
        self._fire_known: set[str] = set()
        self._lamp_known: set[str] = set()
        self._music_known = False
        self._sens_known = False
        self.favorites: dict[str, dict] = {}

    @property
    def connected(self) -> bool:
        return self._connected and self.client is not None

    # ================================================================= lifecycle

    def start(self, cfg: Config) -> None:
        self.cfg = cfg
        self.devices = list(cfg.devices)
        self.running = True
        self.bridges[self.bridge_id] = BridgeInfo(self.bridge_name, True, self.has_mic)
        self.lights.state_listeners.append(self._on_local_state)
        self.lights.availability_listeners.append(lambda _id, _a: self._recompute())
        self.lights.on_music_requested = lambda: self._request_music(True)

        def mic_frame(r: int, g: int, b: int, bright: int, beat: bool) -> None:
            if self.connected:
                self._publish(MUSIC_FRAME, f"{r},{g},{b},{bright},{1 if beat else 0}")
            else:
                self.lights.apply_music_frame(r, g, b, bright, beat)

        self.lights.on_mic_frame = mic_frame
        self.sched.post_delayed(RECOMPUTE_S, self._recompute_tick, key="mqtt-recompute")
        self.sched.post_delayed(600.0, self._config_tick, key="mqtt-config")
        self._connect(cfg)

    async def stop(self) -> None:
        self.running = False
        self.sched.remove("mqtt-recompute")
        self.sched.remove("mqtt-config")
        self.lights.stop_mic()
        c = self.client
        if c is None:
            return
        self.client = None
        # hand over: our links are gone; lamps nobody else reaches become unavailable
        orphaned = [d for d in self.devices
                    if not any(bid != self.bridge_id and sc > 0 and self.bridges.get(bid, BridgeInfo("", False)).online
                               for bid, sc in self.links.get(d.meshId, {}).items())]

        def goodbye() -> None:
            try:
                if self._connected:
                    for d in self.devices:
                        c.publish(f"{BASE}/light/{d.meshId}/link/{self.bridge_id}", "0", 1, True)
                    for d in orphaned:
                        c.publish(self._lamp_avail_topic(d.meshId), "offline", 1, True)
                    c.publish(self.avail, "offline", 1, True).wait_for_publish(2)
                    c.disconnect()
            except Exception:  # noqa: BLE001
                pass
            finally:
                c.loop_stop()

        await self.sched.loop.run_in_executor(None, goodbye)
        self._connected = False

    def _connect(self, cfg: Config) -> None:
        client_id = f"eglo-bridge-{self.bridge_id}-{int(time.time() * 1000) % 100000}"
        c = paho.Client(paho.CallbackAPIVersion.VERSION2, client_id=client_id, clean_session=True)
        c.max_inflight_messages_set(200)
        c.reconnect_delay_set(1, 15)
        if cfg.mqttUser:
            c.username_pw_set(cfg.mqttUser, cfg.mqttPassword)
        c.will_set(self.avail, "offline", qos=1, retain=True)

        def on_connect(_c, _u, _flags, reason_code, _props) -> None:
            if reason_code.is_failure:
                self.sched.post_threadsafe(log.e, f"MQTT-Verbindung abgelehnt: {reason_code}")
                return
            self.sched.post_threadsafe(self._on_connected)

        def on_disconnect(_c, _u, _flags, reason_code, _props) -> None:
            def lost() -> None:
                was = self._connected
                self._connected = False
                if was and self.running:
                    log.w(f"MQTT-Verbindung verloren: {reason_code}")
            self.sched.post_threadsafe(lost)

        def on_message(_c, _u, msg) -> None:
            payload = msg.payload.decode(errors="replace")
            if msg.topic != MUSIC_FRAME:
                if payload:
                    self.topic_cache[msg.topic] = payload
                else:
                    self.topic_cache.pop(msg.topic, None)
            if msg.topic == MUSIC_FRAME:
                self.sched.post_threadsafe(self._on_frame, payload)
            else:
                self.sched.post_threadsafe(self._on_message, msg.topic, payload)

        c.on_connect = on_connect
        c.on_disconnect = on_disconnect
        c.on_message = on_message
        self.client = c
        log.i(f"Verbinde mit MQTT {cfg.mqttHost}:{cfg.mqttPort} als „{self.bridge_name}“ …")
        c.connect_async(cfg.mqttHost, int(cfg.mqttPort), keepalive=30)
        c.loop_start()

    def _publish(self, topic: str, payload: str, retain: bool = False, qos: int = 0) -> None:
        c = self.client
        if c is None or not self._connected:
            return
        try:
            c.publish(topic, payload, qos, retain)
        except Exception as err:  # noqa: BLE001
            print(f"publish failed: {err}")

    def _pub_json(self, topic: str, obj: dict) -> None:
        self._publish(topic, json.dumps(obj, ensure_ascii=False), retain=True, qos=1)

    def _on_connected(self) -> None:
        if self.client is None:
            return
        self._connected = True
        log.i("MQTT wieder verbunden" if self._ever_connected else "MQTT verbunden")
        self._ever_connected = True
        # what we believed while offline is not on the broker: re-learn from retained messages
        self.lamp_avail.clear()
        topics = [f"{BASE}/bridge/+/status", f"{BASE}/bridge/+/info", self.cmd_topic, self.debug_set,
                  f"{self.lamp_assign}/+/set", f"{self.all_assign}/set", self.config_set,
                  f"{BASE}/bridge/+/state", f"{BASE}/bridge/+/config",
                  f"{BASE}/light/#", f"{BASE}/music/#", SENS, SENS_SET, SPEED, SPEED_SET,
                  f"{FIRE_SPEED}/#", f"{FIRE_STRENGTH}/#", f"{FIRE_BRIGHT}/#"]
        if self._has_screen:
            topics += [f"{self.screen_topic}/set", f"{self.brightness_topic}/set", f"{self.screen_timeout_topic}/set"]
        try:
            self.client.subscribe([(t, 0) for t in topics])
        except Exception as err:  # noqa: BLE001
            log.e("MQTT-Subscribe fehlgeschlagen", err)
        self._publish(self.avail, "online", retain=True, qos=1)
        self._pub_json(self.info, {"name": self.bridge_name, "version": self.version, "mic": self.has_mic,
                                   "platform": "linux"})
        self._publish(self.state_text, self.bridge_state, retain=True)
        self._publish(self.debug_topic, "ON" if log.verbose else "OFF", retain=True)
        self.mic_state = ""
        self._update_mic()
        self._publish_discovery()
        self._publish_assignment()
        self.publish_screen()
        self.publish_config()
        self.published.clear()  # force re-publish of link scores
        # give retained messages a moment to arrive, then decide ownership
        self.sched.post_delayed(2.5, lambda: (self._recompute(), self._ensure_selection_defaults()))

    # ================================================================= discovery

    @staticmethod
    def _lamp_avail_topic(mid: int) -> str:
        return f"{BASE}/light/{mid}/availability"

    @staticmethod
    def _state_topic(mid: int) -> str:
        return f"{BASE}/light/{mid}/state"

    @staticmethod
    def _command_topic(mid: int) -> str:
        return f"{BASE}/light/{mid}/set"

    @property
    def _bridge_device(self) -> dict:
        return {"identifiers": [f"eglo_bridge_{self.bridge_id}"], "name": f"EGLO Bridge {self.bridge_name}",
                "manufacturer": "eglo-ble-bridge", "model": _hw_model(), "sw_version": self.version}

    _global_device = {"identifiers": ["eglo_bridge_global"], "name": "EGLO Musik & Effekte",
                      "manufacturer": "eglo-ble-bridge", "model": "Gemeinsame Steuerung"}

    @staticmethod
    def _color_modes(d: MeshDevice) -> list[str]:
        a = []
        if d.has_temperature:
            a.append("color_temp")
        if d.has_color:
            a.append("rgb")
        if not a:
            a.append("brightness" if d.has_dimming else "onoff")
        return a

    def _publish_discovery(self) -> None:
        for d in (x for x in self.devices if x.is_light):
            mid = d.meshId
            dev_ref = {"identifiers": [f"eglo_bridge_dev_{mid}"]}
            cfg = {
                "name": None, "unique_id": f"eglo_bridge_{mid}", "schema": "json",
                "command_topic": self._command_topic(mid), "state_topic": self._state_topic(mid),
                "availability_topic": self._lamp_avail_topic(mid),
                "supported_color_modes": self._color_modes(d),
                "device": {"identifiers": [f"eglo_bridge_dev_{mid}"], "name": d.name.strip(),
                           "manufacturer": d.vendor or "EGLO", "model": d.model.replace("_", " "),
                           "sw_version": d.firmware},
            }
            if d.has_color or d.has_temperature or d.has_dimming:
                cfg.update({"brightness": True, "brightness_scale": 255})
            if d.has_temperature:
                cfg.update({"color_temp_kelvin": True, "min_kelvin": MIN_K, "max_kelvin": MAX_K})
            if d.has_color:
                cfg.update({"effect": True, "effect_list": EFFECTS})
                self._pub_json(f"homeassistant/switch/eglo_bridge_music_{mid}/config", {
                    "name": "Bei Musikmodus dabei", "unique_id": f"eglo_bridge_music_{mid}",
                    "command_topic": f"{BASE}/light/{mid}/music/set", "state_topic": f"{BASE}/light/{mid}/music",
                    "icon": "mdi:music-circle", "entity_category": "config", "device": dev_ref})
            self._pub_json(f"homeassistant/light/eglo_bridge_{mid}/config", cfg)
            if d.has_color or d.has_temperature:
                for n in (1, 2):
                    self._pub_json(f"homeassistant/button/eglo_bridge_{mid}_fav{n}/config", {
                        "name": f"Favorit {n}", "unique_id": f"eglo_bridge_{mid}_fav{n}",
                        "command_topic": f"{BASE}/light/{mid}/fav/set", "payload_press": f"recall{n}",
                        "icon": "mdi:numeric-1-circle" if n == 1 else "mdi:numeric-2-circle", "device": dev_ref})
                    self._pub_json(f"homeassistant/button/eglo_bridge_{mid}_fav{n}_save/config", {
                        "name": f"Favorit {n} speichern (aktuelle Einstellung)",
                        "unique_id": f"eglo_bridge_{mid}_fav{n}_save",
                        "command_topic": f"{BASE}/light/{mid}/fav/set", "payload_press": f"save{n}",
                        "icon": "mdi:content-save", "entity_category": "config", "device": dev_ref})
            if d.has_color:
                for key, name, icon, mn in LAMP_SETTINGS:
                    self._pub_json(f"homeassistant/number/eglo_bridge_{mid}_{key}/config", {
                        "name": name, "unique_id": f"eglo_bridge_{mid}_{key}",
                        "command_topic": f"{BASE}/light/{mid}/{key}/set", "state_topic": f"{BASE}/light/{mid}/{key}",
                        "min": mn, "max": 100, "step": 5, "unit_of_measurement": "%", "mode": "slider",
                        "icon": icon, "device": dev_ref})

        # ---- shared music / effect entities
        self._pub_json("homeassistant/switch/eglo_bridge_music/config", {
            "name": "Musikmodus", "unique_id": "eglo_bridge_music", "command_topic": MUSIC_SET,
            "state_topic": MUSIC, "icon": "mdi:music", "device": self._global_device})
        self._pub_json("homeassistant/number/eglo_bridge_music_sensitivity/config", {
            "name": "Musik-Empfindlichkeit", "unique_id": "eglo_bridge_music_sensitivity",
            "command_topic": SENS_SET, "state_topic": SENS, "min": 50, "max": 200, "step": 5,
            "unit_of_measurement": "%", "mode": "slider", "icon": "mdi:microphone-settings",
            "device": self._global_device})
        self._pub_json("homeassistant/number/eglo_bridge_effect_speed/config", {
            "name": "Effekt-Tempo", "unique_id": "eglo_bridge_effect_speed", "command_topic": SPEED_SET,
            "state_topic": SPEED, "min": 0, "max": 100, "step": 5, "unit_of_measurement": "%",
            "mode": "slider", "icon": "mdi:speedometer", "device": self._global_device})
        for topic, uid, name, icon, mn in (
            (FIRE_SPEED, "eglo_bridge_fire_speed", "Lagerfeuer-Tempo", "mdi:fire", 0),
            (FIRE_STRENGTH, "eglo_bridge_fire_strength", "Lagerfeuer-Stärke", "mdi:fire-alert", 0),
            (FIRE_BRIGHT, "eglo_bridge_fire_brightness", "Lagerfeuer-Helligkeit", "mdi:brightness-6", 5),
        ):
            self._pub_json(f"homeassistant/number/{uid}/config", {
                "name": name, "unique_id": uid, "command_topic": f"{topic}/set", "state_topic": topic,
                "min": mn, "max": 100, "step": 5, "unit_of_measurement": "%", "mode": "slider",
                "icon": icon, "device": self._global_device})
        self._publish_source_select(force=True)

        # ---- per-bridge entities
        bdev = self._bridge_device
        bid = self.bridge_id
        self._pub_json(f"homeassistant/sensor/eglo_bridge_{bid}_state/config", {
            "name": "Status", "unique_id": f"eglo_bridge_{bid}_state", "state_topic": self.state_text,
            "availability_topic": self.avail, "icon": "mdi:bluetooth-connect", "entity_category": "diagnostic",
            "device": bdev})
        self._pub_json(f"homeassistant/sensor/eglo_bridge_{bid}_log/config", {
            "name": "Log", "unique_id": f"eglo_bridge_{bid}_log", "state_topic": self.log_topic,
            "availability_topic": self.avail, "icon": "mdi:text-box-outline", "entity_category": "diagnostic",
            "device": bdev})
        self._pub_json(f"homeassistant/switch/eglo_bridge_{bid}_debug/config", {
            "name": "Debug-Log", "unique_id": f"eglo_bridge_{bid}_debug", "command_topic": self.debug_set,
            "state_topic": self.debug_topic, "availability_topic": self.avail, "icon": "mdi:bug",
            "entity_category": "config", "device": bdev})
        for cmd, name, icon in (("restart", "Bridge neu starten", "mdi:restart-alert"),
                                ("reimport", "Lampen neu importieren", "mdi:cloud-download"),
                                ("reconnect", "Neu verbinden", "mdi:restart")):
            self._pub_json(f"homeassistant/button/eglo_bridge_{bid}_{cmd}/config", {
                "name": name, "unique_id": f"eglo_bridge_{bid}_{cmd}", "command_topic": self.cmd_topic,
                "payload_press": cmd, "availability_topic": self.avail, "icon": icon,
                "entity_category": "config", "device": bdev})
        self._pub_json(f"homeassistant/switch/eglo_bridge_{bid}_lamps_all/config", {
            "name": "Zuständig für alle Lampen", "unique_id": f"eglo_bridge_{bid}_lamps_all",
            "command_topic": f"{self.all_assign}/set", "state_topic": self.all_assign,
            "availability_topic": self.avail, "icon": "mdi:lightbulb-group", "entity_category": "config",
            "device": bdev})
        for d in (x for x in self.devices if x.is_light):
            self._pub_json(f"homeassistant/switch/eglo_bridge_{bid}_lamp_{d.meshId}/config", {
                "name": f"Zuständig: {d.name.strip()}", "unique_id": f"eglo_bridge_{bid}_lamp_{d.meshId}",
                "command_topic": f"{self.lamp_assign}/{d.meshId}/set",
                "state_topic": f"{self.lamp_assign}/{d.meshId}",
                "availability_topic": self.avail, "icon": "mdi:lightbulb-on-outline",
                "entity_category": "config", "device": bdev})
        self._pub_json(f"homeassistant/sensor/eglo_bridge_{bid}_mic/config", {
            "name": "Mikrofon", "unique_id": f"eglo_bridge_{bid}_mic", "state_topic": self.mic_topic,
            "availability_topic": self.avail, "icon": "mdi:microphone", "entity_category": "diagnostic",
            "device": bdev})
        self._pub_json(f"homeassistant/text/eglo_bridge_{bid}_name/config", {
            "name": "Name", "unique_id": f"eglo_bridge_{bid}_name", "command_topic": self.config_set,
            "command_template": '{"name": {{ value | tojson }}}', "state_topic": self.config_topic,
            "value_template": "{{ value_json.name }}", "availability_topic": self.avail,
            "min": 1, "max": 40, "icon": "mdi:rename", "entity_category": "config", "device": bdev})
        self._pub_json(f"homeassistant/sensor/eglo_bridge_{bid}_ip/config", {
            "name": "IP-Adresse", "unique_id": f"eglo_bridge_{bid}_ip", "state_topic": self.config_topic,
            "value_template": "{{ value_json.device.ip }}", "json_attributes_topic": self.config_topic,
            "json_attributes_template": "{{ value_json.device | tojson }}", "availability_topic": self.avail,
            "icon": "mdi:ip-network", "entity_category": "diagnostic", "device": bdev})
        if self._has_screen:
            self._publish_screen_discovery(bdev)

    # ================================================================= display (Pi with touch panel)

    @property
    def _has_screen(self) -> bool:
        return self.screen is not None and self.screen.available

    def _publish_screen_discovery(self, bdev: dict) -> None:
        bid = self.bridge_id
        # same entities as the kiosk screen of the Android app
        self._pub_json(f"homeassistant/switch/eglo_bridge_{bid}_screen/config", {
            "name": "Bildschirm", "unique_id": f"eglo_bridge_{bid}_screen",
            "command_topic": f"{self.screen_topic}/set", "state_topic": self.screen_topic,
            "availability_topic": self.avail, "icon": "mdi:tablet-dashboard", "device": bdev})
        self._pub_json(f"homeassistant/number/eglo_bridge_{bid}_brightness/config", {
            "name": "Bildschirmhelligkeit", "unique_id": f"eglo_bridge_{bid}_brightness",
            "command_topic": f"{self.brightness_topic}/set", "state_topic": self.brightness_topic,
            "min": 1, "max": 100, "step": 1, "mode": "slider", "unit_of_measurement": "%",
            "availability_topic": self.avail, "icon": "mdi:brightness-6", "device": bdev})
        self._pub_json(f"homeassistant/number/eglo_bridge_{bid}_screen_timeout/config", {
            "name": "Bildschirm aus nach", "unique_id": f"eglo_bridge_{bid}_screen_timeout",
            "command_topic": f"{self.screen_timeout_topic}/set", "state_topic": self.screen_timeout_topic,
            "min": 0, "max": 120, "step": 1, "mode": "box", "unit_of_measurement": "min",
            "availability_topic": self.avail, "icon": "mdi:timer-outline", "entity_category": "config",
            "device": bdev})

    def publish_screen(self) -> None:
        """Publish the display state (called by Screen.on_change)."""
        if not self._has_screen or not self.connected:
            return
        sc = self.screen
        self._publish(self.screen_topic, "ON" if sc.on else "OFF", retain=True, qos=1)
        self._publish(self.brightness_topic, str(sc.brightness_pct), retain=True, qos=1)
        self._publish(self.screen_timeout_topic, str(sc.timeout_min), retain=True, qos=1)

    def _on_screen_message(self, topic: str, v: str) -> bool:
        if not self._has_screen:
            return False
        if topic == f"{self.screen_topic}/set":
            self.screen.set_screen(v.upper() == "ON")
        elif topic == f"{self.brightness_topic}/set":
            n = _to_int(v)
            if n is not None:
                self.screen.set_brightness(n)
        elif topic == f"{self.screen_timeout_topic}/set":
            n = _to_int(v)
            if n is not None:
                self.screen.set_timeout(n)
        else:
            return False
        return True

    # ================================================================= lamp assignment

    def _publish_assignment(self) -> None:
        self._publish(self.all_assign, "ON" if self.mesh.assigned is None else "OFF", retain=True)
        for d in (x for x in self.devices if x.is_light):
            self._publish(f"{self.lamp_assign}/{d.meshId}",
                          "ON" if self.mesh.is_responsible(d.meshId) else "OFF", retain=True)

    def set_assignment(self, assigned: list[int] | None) -> None:
        """Apply a new lamp assignment (None = all lamps), persist it and tell HA."""
        lights = [d.meshId for d in self.devices if d.is_light]
        if assigned is not None:
            assigned = sorted({m for m in assigned if m in lights})
        self.mesh.set_assigned(None if assigned is None else set(assigned))
        self._publish_assignment()
        self._recompute()
        if self.on_assignment_changed is not None:
            self.on_assignment_changed(assigned)

    def _on_assign_message(self, mid: int | None, on: bool) -> None:
        lights = [d.meshId for d in self.devices if d.is_light]
        if mid is None:  # "all lamps" switch
            if on:
                self.set_assignment(None)
            elif self.mesh.assigned is None:
                # switching "all" off keeps the current lamps, but as an explicit list
                # (new lamps from a later import are then not added automatically)
                self.set_assignment(lights)
            return
        if mid not in lights:
            return
        current = set(lights) if self.mesh.assigned is None else set(self.mesh.assigned)
        if on:
            current.add(mid)
        else:
            current.discard(mid)
        self.set_assignment(None if current >= set(lights) else sorted(current))

    def _publish_source_select(self, force: bool = False) -> None:
        """Select entity "Musikquelle" listing all bridges that can listen."""
        names = sorted({b.name for b in self.bridges.values() if b.mic})
        key = "|".join(names)
        if not force and key == self._last_source_options:
            return
        self._last_source_options = key
        self._pub_json("homeassistant/select/eglo_bridge_music_source/config", {
            "name": "Musikquelle (Tablet-Mikrofon)", "unique_id": "eglo_bridge_music_source",
            "command_topic": SOURCE_SET, "state_topic": SOURCE_NAME, "options": names or [self.bridge_name],
            "icon": "mdi:tablet", "device": self._global_device})
        src = self.bridges.get(self.effective_source())
        self._publish(SOURCE_NAME, src.name if src else self.bridge_name, retain=True)

    # ================================================================= ownership

    def _config_tick(self) -> None:
        self.publish_config()
        self.sched.post_delayed(600.0, self._config_tick, key="mqtt-config")

    def _recompute_tick(self) -> None:
        self._recompute()
        self.sched.post_delayed(RECOMPUTE_S, self._recompute_tick, key="mqtt-recompute")

    def _score(self, mid: int, bid: str) -> int:
        if bid == self.bridge_id:
            return self.my_links.get(mid, 0)
        return self.links.get(mid, {}).get(bid, 0)

    def owner_of(self, mid: int) -> str | None:
        """Bridge that controls the lamp: best link score, ties -> smaller id. None = nobody."""
        best: str | None = None
        best_score = 0
        for bid, b in self.bridges.items():
            if not b.online and bid != self.bridge_id:
                continue
            s = self._score(mid, bid)
            if s <= 0:
                continue
            if s > best_score or (s == best_score and best is not None and bid < best):
                best, best_score = bid, s
        return best

    def is_owner(self, mid: int) -> bool:
        return self.owner_of(mid) == self.bridge_id

    def _owned_lights(self) -> list[int]:
        return [d.meshId for d in self.devices if d.is_light and self.is_owner(d.meshId)]

    def _recompute(self) -> None:
        if not self.running:
            return
        for d in (x for x in self.devices if x.is_light):
            mid = d.meshId
            s = self.mesh.link_score(mid)
            self.my_links[mid] = s
            if self.published.get(mid) != s and self.connected:
                self.published[mid] = s
                self._publish(f"{BASE}/light/{mid}/link/{self.bridge_id}", str(s), retain=True, qos=1)
            owner = self.owner_of(mid)
            if owner == self.bridge_id:
                if self.lamp_avail.get(mid) is not True:
                    self.lamp_avail[mid] = True
                    self._publish(self._lamp_avail_topic(mid), "online", retain=True, qos=1)
                    st = self.lights.local_state(mid)
                    if st is not None and st.known:
                        self._publish_state(mid, st)
                    if self.music_on and self._selected(mid):
                        self.lights.set_music_target(mid, True)
            elif owner is None:
                if self.lamp_avail.get(mid) is not False:
                    self.lamp_avail[mid] = False
                    self._publish(self._lamp_avail_topic(mid), "offline", retain=True, qos=1)
                self.lights.set_music_target(mid, False)
                self.lights.drop_software_effect(mid)
            else:  # another bridge controls it
                self.lights.set_music_target(mid, False)
                self.lights.drop_software_effect(mid)
        self._publish_source_select()
        self._update_mic()

    # ================================================================= states

    def _on_local_state(self, mid: int, st: LightState) -> None:
        self._recompute()
        owner = self.owner_of(mid)
        if owner == self.bridge_id or (owner is None and st.available):
            self._publish_state(mid, st)

    def _publish_state(self, mid: int, st: LightState) -> None:
        d = next((x for x in self.devices if x.meshId == mid), None)
        if d is None:
            return
        o: dict = {"state": "ON" if st.on else "OFF"}
        if st.color_mode and d.has_color:
            o.update({"color_mode": "rgb", "brightness": st.brightness255,
                      "color": {"r": st.r, "g": st.g, "b": st.b}})
        elif d.has_temperature:
            o.update({"color_mode": "color_temp", "brightness": st.brightness255, "color_temp": st.kelvin})
        elif d.has_dimming or d.has_color:
            o.update({"color_mode": "brightness", "brightness": st.brightness255})
        if d.has_color:
            o["effect"] = st.effect
        self._publish(self._state_topic(mid), json.dumps(o, ensure_ascii=False), retain=True)

    def on_bridge_state(self, text: str) -> None:
        self.bridge_state = text
        self._publish(self.state_text, text, retain=True)

    def on_log(self, line: str) -> None:
        self._publish(self.log_topic, line[:250])

    # ================================================================= favourites

    def _selected(self, mid: int) -> bool:
        return self.selection.get(mid, True)

    def _ui_state(self, mid: int) -> LightState | None:
        local = self.lights.local_state(mid)
        owner = self.owner_of(mid)
        if owner == self.bridge_id or owner is None:
            st = local
        else:
            remote = self.remote_states.get(mid)
            st = remote.copy() if remote else local
        if st is not None:
            st.available = self.lamp_avail.get(mid, local is not None and local.available)
        return st

    def _current_as_command(self, mid: int) -> dict | None:
        st = self._ui_state(mid)
        if st is None or not st.known:
            return None
        d = next((x for x in self.devices if x.meshId == mid), None)
        if d is None:
            return None
        o: dict = {"state": "ON", "brightness": max(1, st.brightness255)}
        if st.color_mode and d.has_color:
            o["color"] = {"r": st.r, "g": st.g, "b": st.b}
        elif d.has_temperature:
            o["color_temp"] = st.kelvin
        # keep a running effect (not music: that depends on the music mode)
        if st.effect not in (EFFECT_NONE, EFFECT_MUSIC):
            o["effect"] = st.effect
        return o

    def _save_favorite(self, mid: int, n: int) -> None:
        if n not in (1, 2):
            return
        cmd = self._current_as_command(mid)
        if cmd is None:
            log.w(f"Favorit {n}: Zustand der Lampe unbekannt")
            return
        self.favorites[f"{mid}/{n}"] = cmd
        self._pub_json(f"{BASE}/light/{mid}/fav{n}", cmd)
        log.i(f"Favorit {n} für {self.mesh.device_name(mid)} gespeichert: {cmd}")

    def _recall_favorite(self, mid: int, n: int) -> None:
        cmd = self.favorites.get(f"{mid}/{n}")
        if cmd is None:
            log.w(f"Favorit {n} für {self.mesh.device_name(mid)} ist noch nicht belegt")
            return
        self.lights.command(mid, dict(cmd))

    def _set_all_lamps(self, key: str, value: int) -> None:
        for d in (x for x in self.devices if x.is_light and x.has_color):
            if self.connected:
                self._publish(f"{BASE}/light/{d.meshId}/{key}", str(value), retain=True)
            else:
                self._apply_lamp_setting(d.meshId, key, value)

    def _apply_lamp_setting(self, mid: int, key: str, value: int) -> None:
        self.lights.set_lamp_setting(mid, key, value)
        if key == "effect_speed" and self.is_owner(mid):
            self.lights.apply_sequence_speed([mid], value)

    def _ensure_selection_defaults(self) -> None:
        if not self._music_known:
            self._publish(MUSIC, "OFF", retain=True)
        if not self._sens_known:
            self._publish(SENS, str(self.sensitivity_pct), retain=True)
        if self.speed == 50 and not self._speed_known:
            self._publish(SPEED, "50", retain=True)
        colour = [d for d in self.devices if d.is_light and d.has_color]
        for d in colour:
            for key in LAMP_SETTING_KEYS:
                if f"{d.meshId}/{key}" not in self._lamp_known:
                    self._publish(f"{BASE}/light/{d.meshId}/{key}", str(self.lights.lamp_setting(d.meshId, key)),
                                  retain=True)
        if FIRE_SPEED not in self._fire_known:
            self._publish(FIRE_SPEED, str(self.lights.fire_speed), retain=True)
        if FIRE_STRENGTH not in self._fire_known:
            self._publish(FIRE_STRENGTH, str(self.lights.fire_strength), retain=True)
        if FIRE_BRIGHT not in self._fire_known:
            self._publish(FIRE_BRIGHT, str(self.lights.fire_brightness), retain=True)
        for d in colour:
            if d.meshId not in self.selection:
                self._publish(f"{BASE}/light/{d.meshId}/music", "ON", retain=True)
        if self.music_source_id is None:
            src = self.bridges.get(self.effective_source())
            self._publish(SOURCE_NAME, src.name if src else self.bridge_name, retain=True)

    # ================================================================= music

    def effective_source(self) -> str:
        """Bridge whose microphone is used: the chosen one if online (and able to listen),
        else the first online bridge with a microphone."""
        chosen = self.music_source_id
        if chosen is not None:
            b = self.bridges.get(chosen)
            mic_ok = b is None or b.mic
            if mic_ok and (chosen == self.bridge_id or (b is not None and b.online)):
                return chosen
        cands = [bid for bid, b in self.bridges.items() if (b.online or bid == self.bridge_id) and b.mic]
        return min(cands) if cands else self.bridge_id

    def _request_music(self, on: bool) -> None:
        if self.connected:
            self._publish(MUSIC_SET, "ON" if on else "OFF")
        else:
            self._set_music(on)

    def _set_music(self, on: bool) -> None:
        changed = self.music_on != on
        self.music_on = on
        if changed:
            log.i("Musikmodus an" if on else "Musikmodus aus")
        targets = [i for i in self._owned_lights() if self._selected(i) and self.lights.is_available(i)] if on else []
        self.lights.set_music_mode(on, targets)
        self._update_mic()

    def _update_mic(self) -> None:
        should_listen = self.music_on and self.effective_source() == self.bridge_id
        if should_listen and not self.lights.mic_running:
            if not self.has_mic or not self.lights.start_mic():
                if self.has_mic:
                    log.w("Mikrofon konnte nicht gestartet werden")
                self._set_mic_state("kein Mikrofon" if not self.has_mic else "Fehler beim Öffnen")
                return
        elif not should_listen and self.lights.mic_running:
            self.lights.stop_mic()
        if self.lights.mic_running:
            self._set_mic_state("hört zu")
        else:
            self._set_mic_state("bereit" if self.has_mic else "kein Mikrofon")

    def _set_mic_state(self, s: str) -> None:
        if s == self.mic_state:
            return
        self.mic_state = s
        self._publish(self.mic_topic, s, retain=True)

    def _on_frame(self, payload: str) -> None:
        p = payload.split(",")
        if len(p) < 5:
            return
        vals = [_to_int(x) for x in p[:4]]
        if any(v is None for v in vals):
            return
        r, g, b, br = vals  # type: ignore[misc]
        self.lights.apply_music_frame(r, g, b, br, p[4].strip() == "1")

    # ================================================================= incoming

    def topics(self) -> dict[str, str]:
        return dict(self.topic_cache)

    def send(self, topic: str, payload: str, retain: bool = False) -> None:
        """Publish on behalf of the MCP endpoint (same broker login as the bridge)."""
        self._publish(topic, payload, retain=retain, qos=1)

    def publish_config(self) -> None:
        """Retained snapshot of the settings (no passwords) and computer facts, see config/set."""
        c = self.cfg
        if c is None:
            return
        settings = {"mqtt_host": c.mqttHost, "mqtt_port": c.mqttPort, "mqtt_user": c.mqttUser,
                    "awox_user": c.awoxUser, "debug_log": log.verbose, "adapter": c.adapter,
                    "lamps": sum(1 for d in c.devices if d.is_light),
                    "assigned_lamps": "all" if c.assignedLamps is None else sorted(c.assignedLamps)}
        if self._has_screen:
            settings["screen_timeout"] = self.screen.timeout_min
            settings["screen_brightness"] = self.screen.brightness_pct
        try:
            dev = device_info()
        except Exception:  # noqa: BLE001
            dev = {}
        self._pub_json(self.config_topic, {"bridge_id": self.bridge_id, "name": self.bridge_name,
                                           "platform": "linux", "version": self.version,
                                           "settings": settings, "device": dev})

    def _on_message(self, topic: str, payload: str) -> None:  # noqa: C901 - mirrors the Kotlin dispatcher
        if not self.running:
            return
        v = payload.strip()
        if topic == self.config_set:
            try:
                data = json.loads(v)
                if not isinstance(data, dict):
                    raise ValueError
            except ValueError:
                log.w(f"Ungültige Einstellungen: {v[:100]}")
                return
            if self.on_config_set:
                self.on_config_set(data)
            return
        if topic == self.cmd_topic:
            if v == "reconnect":
                self.mesh.reconnect()
            elif self.on_service_command:
                self.on_service_command(v)
            return
        if self._on_screen_message(topic, v):
            return
        if topic == f"{self.all_assign}/set":
            self._on_assign_message(None, v.upper() == "ON")
            return
        if topic.startswith(self.lamp_assign + "/") and topic.endswith("/set"):
            mid = _to_int(topic[len(self.lamp_assign) + 1:-len("/set")])
            if mid is not None:
                self._on_assign_message(mid, v.upper() == "ON")
            return
        if topic == self.debug_set:
            log.verbose = v.upper() == "ON"
            log.i("Debug-Log an" if log.verbose else "Debug-Log aus")
            self._publish(self.debug_topic, "ON" if log.verbose else "OFF", retain=True)
            return
        if topic == MUSIC_SET:
            self._publish(MUSIC, "ON" if v.upper() == "ON" else "OFF", retain=True)
            return
        if topic == MUSIC:
            self._music_known = True
            self._set_music(v.upper() == "ON")
            return
        if topic == SOURCE_SET:
            bid = next((k for k, b in self.bridges.items() if b.name == v), None) or slug(v)
            self._publish(SOURCE, bid, retain=True)
            b = self.bridges.get(bid)
            self._publish(SOURCE_NAME, b.name if b else v, retain=True)
            return
        if topic == SOURCE:
            self.music_source_id = v or None
            src = self.effective_source()
            log.i(f"Musikquelle: {self.bridges[src].name if src in self.bridges else src}")
            self._update_mic()
            return
        if topic == SOURCE_NAME:
            return
        if topic in (f"{FIRE_SPEED}/set", f"{FIRE_STRENGTH}/set", f"{FIRE_BRIGHT}/set"):
            n = _to_int(v)
            if n is None:
                return
            n = max(0, min(100, n))
            state_topic = topic[: -len("/set")]
            self._publish(state_topic, str(n), retain=True)
            # the global slider sets all lamps
            self._set_all_lamps(state_topic[len(BASE) + 1:], n)
            return
        if topic in (FIRE_SPEED, FIRE_STRENGTH, FIRE_BRIGHT):
            n = _to_int(v)
            if n is None:
                return
            n = max(0, min(100, n))
            self._fire_known.add(topic)
            if topic == FIRE_SPEED:
                self.lights.fire_speed = n
            elif topic == FIRE_STRENGTH:
                self.lights.fire_strength = n
            else:
                self.lights.fire_brightness = max(5, n)
            return
        if topic == SENS_SET:
            n = _to_int(v)
            if n is not None:
                self._publish(SENS, str(n), retain=True)
            return
        if topic == SENS:
            self._sens_known = True
            try:
                f = float(v)
            except ValueError:
                return
            self.sensitivity_pct = int(f)
            self.lights.music_sensitivity = f / 100.0
            return
        if topic == SPEED_SET:
            n = _to_int(v)
            if n is None:
                return
            n = max(0, min(100, n))
            self._publish(SPEED, str(n), retain=True)
            self._set_all_lamps("effect_speed", n)
            return
        if topic == SPEED:
            self._speed_known = True
            n = _to_int(v)
            if n is not None:
                self.speed = n
            return

        parts = topic.split("/")
        # eglo_bridge/bridge/<bid>/status|info
        if len(parts) == 4 and parts[1] == "bridge":
            bid = parts[2]
            b = self.bridges.setdefault(bid, BridgeInfo(bid, False))
            if parts[3] == "status":
                online = v == "online"
                if bid != self.bridge_id and b.online != online:
                    log.i(f"Bridge „{b.name}“ ist {'online' if online else 'offline'}")
                if bid != self.bridge_id:
                    b.online = online
                elif not online and self.running and self.connected:
                    # a stale "offline" (e.g. the goodbye of an older session that overlapped a
                    # restart) must not leave us unavailable in HA while we are running
                    log.w("Eigener Status stand auf offline – setze wieder online")
                    self._publish(self.avail, "online", retain=True, qos=1)
            elif parts[3] == "info":
                try:
                    o = json.loads(v)
                    b.name = o.get("name", bid)
                    if bid != self.bridge_id:
                        b.mic = bool(o.get("mic", True))
                except (ValueError, AttributeError):
                    pass
            self._recompute()
            return
        if len(parts) < 4 or parts[1] != "light":
            return
        try:
            mid = int(parts[2])
        except ValueError:
            return
        sub = parts[3]
        if len(parts) == 4 and sub == "set":
            try:
                cmd = json.loads(v)
            except ValueError:
                log.w(f"Ungültiger Befehl: {v}")
                return
            if not isinstance(cmd, dict):
                return
            owner = self.owner_of(mid)
            if owner == self.bridge_id or (owner is None and self.lights.is_available(mid)):
                self.lights.command(mid, cmd)
        elif len(parts) == 4 and sub == "state":
            if v:
                try:
                    self.remote_states[mid] = parse_state(json.loads(v))
                except (ValueError, TypeError, AttributeError):
                    pass
        elif len(parts) == 4 and sub == "availability":
            self.lamp_avail[mid] = v == "online"
        elif len(parts) == 5 and sub == "link":
            self.links.setdefault(mid, {})[parts[4]] = _to_int(v) or 0
            if parts[4] != self.bridge_id:
                self._recompute()
        elif len(parts) == 5 and sub == "fav" and parts[4] == "set":
            owner = self.owner_of(mid)
            if owner != self.bridge_id and not (owner is None and self.lights.is_available(mid)):
                return
            if v.startswith("recall"):
                n = _to_int(v[len("recall"):])
                if n is not None:
                    self._recall_favorite(mid, n)
            elif v.startswith("save"):
                n = _to_int(v[len("save"):])
                if n is not None:
                    self._save_favorite(mid, n)
        elif len(parts) == 4 and sub in ("fav1", "fav2"):
            k = f"{mid}/{sub[-1]}"
            if not v:
                self.favorites.pop(k, None)
            else:
                try:
                    self.favorites[k] = json.loads(v)
                except ValueError:
                    pass
        elif len(parts) == 5 and sub in LAMP_SETTING_KEYS and parts[4] == "set":
            n = _to_int(v)
            if n is not None:
                self._publish(f"{BASE}/light/{mid}/{sub}", str(max(0, min(100, n))), retain=True)
        elif len(parts) == 4 and sub in LAMP_SETTING_KEYS:
            n = _to_int(v)
            if n is not None:
                self._lamp_known.add(f"{mid}/{sub}")
                self._apply_lamp_setting(mid, sub, max(0, min(100, n)))
        elif len(parts) == 5 and sub == "music" and parts[4] == "set":
            self._publish(f"{BASE}/light/{mid}/music", "ON" if v.upper() == "ON" else "OFF", retain=True)
        elif len(parts) == 4 and sub == "music":
            on = v.upper() == "ON"
            self.selection[mid] = on
            if self.music_on and self.is_owner(mid):
                self.lights.set_music_target(mid, on and self.lights.is_available(mid))
