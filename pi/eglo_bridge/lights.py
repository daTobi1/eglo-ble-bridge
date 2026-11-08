"""Local light logic of one bridge (port of LightController.kt).

Keeps the last known state of the lamps this bridge can reach, translates Home Assistant
JSON commands into mesh commands, runs the campfire effect and applies music frames.
Coordination between several bridges lives in mqtt.py. Everything runs on the event loop.
"""

from __future__ import annotations

import math
import random
import time
from dataclasses import dataclass, replace
from typing import Callable

from . import log
from .config import MeshDevice
from .mesh import BROADCAST, LightStatus, MeshManager
from .sched import Scheduler

MIN_K = 2700
MAX_K = 6500

EFFECT_NONE = "Kein Effekt"
EFFECT_CANDLE = "Kerze"
EFFECT_MUSIC = "Musik"
EFFECT_FIRE = "Lagerfeuer"
#: AwoX colour sequences (preset 0..6)
SEQUENCES = ["Farbwechsel", "Sequenz 2", "Sequenz 3", "Sequenz 4", "Sequenz 5", "Sequenz 6", "Sequenz 7"]
EFFECTS = [EFFECT_NONE, *SEQUENCES, EFFECT_CANDLE, EFFECT_FIRE, EFFECT_MUSIC]

FIRE_TICK_S = 0.2


def _round(x: float) -> int:
    """Kotlin roundToInt (half up), not Python's banker's rounding."""
    return math.floor(x + 0.5)


def convert(value: int, min_from: int, max_from: int, min_to: int, max_to: int) -> int:
    """Same rounding/clamping as the HA AwoX integration."""
    normalized = (value - min_from) / (max_from - min_from)
    v = _round(normalized * (max_to - min_to) + min_to)
    return max(min_to, min(max_to, v))


def device_temp_to_kelvin(t: int) -> int:
    return convert(0x7F - max(0, min(0x7F, t)), 0, 0x7F, MIN_K, MAX_K)


def kelvin_to_device_temp(k: int) -> int:
    return 0x7F - convert(k, MIN_K, MAX_K, 0, 0x7F)


def hsv_to_rgb(h: float, s: float, v: float) -> tuple[int, int, int]:
    c = v * s
    hh = (h % 360) / 60.0
    x = c * (1 - abs(hh % 2 - 1))
    sector = int(hh)
    r1, g1, b1 = {0: (c, x, 0.0), 1: (x, c, 0.0), 2: (0.0, c, x),
                  3: (0.0, x, c), 4: (x, 0.0, c)}.get(sector, (c, 0.0, x))
    m = v - c
    return int((r1 + m) * 255), int((g1 + m) * 255), int((b1 + m) * 255)


@dataclass
class LightState:
    available: bool = False
    on: bool = False
    color_mode: bool = False
    white_brightness: int = 0x7F
    temperature: int = 0x40
    color_brightness: int = 0x64
    r: int = 255
    g: int = 255
    b: int = 255
    effect: str = EFFECT_NONE
    known: bool = False

    @property
    def brightness255(self) -> int:
        if self.color_mode:
            return convert(self.color_brightness, 0x0A, 0x64, 0, 255)
        return convert(self.white_brightness, 1, 0x7F, 0, 255)

    @property
    def kelvin(self) -> int:
        return device_temp_to_kelvin(self.temperature)

    def copy(self) -> "LightState":
        return replace(self)


def parse_state(o: dict) -> LightState:
    """Parse a HA JSON state message (as published by any bridge) into a LightState."""
    st = LightState(known=True, available=True)
    st.on = o.get("state") == "ON"
    st.color_mode = o.get("color_mode") == "rgb"
    br = int(o.get("brightness", 255))
    if st.color_mode:
        st.color_brightness = convert(br, 0, 255, 0x0A, 0x64)
    else:
        st.white_brightness = convert(br, 0, 255, 1, 0x7F)
    if "color_temp" in o:
        st.temperature = kelvin_to_device_temp(int(o["color_temp"]))
    c = o.get("color")
    if isinstance(c, dict):
        st.r, st.g, st.b = int(c.get("r", 0)), int(c.get("g", 0)), int(c.get("b", 0))
    st.effect = o.get("effect", EFFECT_NONE)
    return st


@dataclass
class _Fire:
    heat: float = 0.7
    target: float = 0.8
    hue: float = 22.0
    #: per-lamp brightness from the HA slider while the fire runs (0..1)
    level: float = 1.0


class LightController:
    def __init__(self, sched: Scheduler, mesh: MeshManager, devices: list[MeshDevice]):
        self.sched = sched
        self.mesh = mesh
        self.devices = devices

        self.state_listeners: list[Callable[[int, LightState], None]] = []
        self.availability_listeners: list[Callable[[int, bool], None]] = []
        #: set by the MQTT layer: a lamp asked for effect "Musik" while music mode is off
        self.on_music_requested: Callable[[], None] | None = None
        #: set by the MQTT layer: frames from this bridge's microphone
        self.on_mic_frame: Callable[[int, int, int, int, bool], None] | None = None

        self._states: dict[int, LightState] = {}
        self._last_preset: dict[int, int] = {}

        # campfire defaults 0..100 (shared via MQTT); per-lamp values override them
        self.fire_speed = 50
        self.fire_strength = 50
        self.fire_brightness = 100
        self._lamp_settings: dict[int, dict[str, int]] = {}
        self._fire_targets: dict[int, _Fire] = {}
        self._rnd = random.Random()
        self._fire_idx = 0
        self._fire_running = False

        self._music_targets: set[int] = set()
        self._mic = None
        self._music_sensitivity = 1.0
        self.music_global_on = False
        self._last_music_brightness = -1
        self._last_mic_frame_at = 0.0

    # ------------------------------------------------------------------ helpers

    @property
    def lights(self) -> list[MeshDevice]:
        return [d for d in self.devices if d.is_light]

    def device(self, mesh_id: int) -> MeshDevice | None:
        return next((d for d in self.devices if d.meshId == mesh_id), None)

    @property
    def music_sensitivity(self) -> float:
        return self._music_sensitivity

    @music_sensitivity.setter
    def music_sensitivity(self, v: float) -> None:
        self._music_sensitivity = max(0.5, min(2.0, v))
        if self._mic is not None:
            self._mic.sensitivity = self._music_sensitivity

    def set_lamp_setting(self, mesh_id: int, key: str, value: int) -> None:
        self._lamp_settings.setdefault(mesh_id, {})[key] = value

    def lamp_setting(self, mesh_id: int, key: str) -> int:
        s = self._lamp_settings.get(mesh_id, {})
        if key in s:
            return s[key]
        return {"fire_speed": self.fire_speed, "fire_strength": self.fire_strength,
                "fire_brightness": self.fire_brightness}.get(key, 50)

    def is_available(self, mesh_id: int) -> bool:
        st = self._states.get(mesh_id)
        return st is not None and st.available

    def local_state(self, mesh_id: int) -> LightState | None:
        st = self._states.get(mesh_id)
        return st.copy() if st else None

    @property
    def mic_running(self) -> bool:
        if self._mic is not None and not self._mic.is_running:
            self._mic = None  # audio thread died (device unplugged / error): allow a restart
        return self._mic is not None

    def _st(self, mesh_id: int) -> LightState:
        return self._states.setdefault(mesh_id, LightState())

    def _notify_state(self, mesh_id: int) -> None:
        s = self._st(mesh_id).copy()
        for listener in list(self.state_listeners):
            listener(mesh_id, s)

    # ------------------------------------------------------------------ mesh -> state

    def on_status(self, s: LightStatus) -> None:
        st = self._st(s.mesh_id)
        st.on = s.on
        st.color_mode = s.color_mode
        st.white_brightness = s.white_brightness
        st.temperature = s.temperature
        st.color_brightness = s.color_brightness
        if s.mesh_id not in self._music_targets and s.mesh_id not in self._fire_targets:
            st.r, st.g, st.b = s.r, s.g, s.b
            if s.candle_mode:
                st.effect = EFFECT_CANDLE
            elif s.sequence_mode:
                st.effect = SEQUENCES[max(0, min(len(SEQUENCES) - 1, self._last_preset.get(s.mesh_id, 0)))]
            else:
                st.effect = EFFECT_NONE
        st.known = True
        self.set_available(s.mesh_id, True)
        self._notify_state(s.mesh_id)

    def set_available(self, mesh_id: int, available: bool) -> None:
        st = self._st(mesh_id)
        if st.available == available:
            return
        st.available = available
        if not available:
            self._music_targets.discard(mesh_id)
            self._fire_targets.pop(mesh_id, None)
        for listener in list(self.availability_listeners):
            listener(mesh_id, available)
        self._notify_state(mesh_id)

    def mark_all_offline(self) -> None:
        for d in self.devices:
            self.set_available(d.meshId, False)

    # ------------------------------------------------------------------ commands

    def command(self, mesh_id: int, root: dict) -> None:
        """Apply a HA style JSON command: {"state","brightness","color","color_temp","effect"}."""
        log.i(f"Befehl für {self.mesh.device_name(mesh_id)}: {root}")
        st = self._st(mesh_id)
        state_set = False
        effect = str(root.get("effect", ""))
        state_cmd = str(root.get("state", "")).upper()

        # any explicit colour / temperature / effect change ends music / campfire for this lamp
        ends = (effect and effect != EFFECT_FIRE) or "color" in root or "color_temp" in root or state_cmd == "OFF"
        if mesh_id in self._fire_targets and ends:
            del self._fire_targets[mesh_id]
            if st.effect == EFFECT_FIRE:
                st.effect = EFFECT_NONE
        ends_music = (effect and effect != EFFECT_MUSIC) or "color" in root or "color_temp" in root \
            or state_cmd == "OFF"
        if mesh_id in self._music_targets and ends_music:
            self._music_targets.discard(mesh_id)
            if st.effect == EFFECT_MUSIC:
                st.effect = EFFECT_NONE

        c = root.get("color")
        if isinstance(c, dict):
            r, g, b = int(c.get("r", 255)), int(c.get("g", 255)), int(c.get("b", 255))
            self.mesh.set_color(mesh_id, r, g, b)
            st.r, st.g, st.b = r, g, b
            st.color_mode = True
            st.on = True
            st.effect = EFFECT_NONE
            state_set = True

        fire = self._fire_targets.get(mesh_id)
        if "brightness" in root:
            br = int(root["brightness"])
            if fire is not None and "color" not in root and "color_temp" not in root:
                fire.level = max(0.05, min(1.0, br / 255.0))
                st.color_brightness = convert(br, 0, 255, 0x0A, 0x64)
            elif "color_temp" not in root and ("color" in root or st.color_mode):
                v = convert(br, 0, 255, 0x0A, 0x64)
                self.mesh.set_color_brightness(mesh_id, v)
                st.color_brightness = v
            else:
                v = convert(br, 0, 255, 1, 0x7F)
                self.mesh.set_white_brightness(mesh_id, v)
                st.white_brightness = v
            st.on = True
            state_set = True

        if "color_temp" in root:
            v = kelvin_to_device_temp(int(root["color_temp"]))
            self.mesh.set_white_temperature(mesh_id, v)
            st.temperature = v
            st.color_mode = False
            st.on = True
            st.effect = EFFECT_NONE
            state_set = True

        if effect:
            state_set = True
            st.on = True
            self._apply_effect(mesh_id, st, effect)

        if state_cmd == "ON":
            if not state_set:
                self.mesh.set_power(mesh_id, True)
            st.on = True
        elif state_cmd == "OFF":
            self.mesh.set_power(mesh_id, False)
            st.on = False
        st.known = True
        self._notify_state(mesh_id)

    def _apply_effect(self, mesh_id: int, st: LightState, effect: str) -> None:
        if effect in SEQUENCES:
            seq = SEQUENCES.index(effect)
            self._last_preset[mesh_id] = seq
            self.mesh.set_preset(mesh_id, seq)
            st.effect = effect
            st.color_mode = True
        elif effect == EFFECT_CANDLE:
            self.mesh.set_candle(mesh_id)
            st.effect = effect
        elif effect == EFFECT_FIRE:
            self._music_targets.discard(mesh_id)
            self._fire_targets.setdefault(mesh_id, _Fire())
            st.effect = effect
            st.color_mode = True
            if not self._fire_running:
                self._fire_running = True
                self.sched.post(self._fire_step)
        elif effect == EFFECT_MUSIC:
            self._fire_targets.pop(mesh_id, None)
            self._music_targets.add(mesh_id)
            st.effect = effect
            st.color_mode = True
            if not self.music_global_on and self.on_music_requested:
                self.on_music_requested()
        else:
            # "Kein Effekt": go back to the last static colour / white
            st.effect = EFFECT_NONE
            if st.color_mode:
                self.mesh.set_color(mesh_id, st.r, st.g, st.b, coalesce=False)
            else:
                self.mesh.set_white_temperature(mesh_id, st.temperature)

    def apply_sequence_speed(self, ids: list[int], speed: int) -> None:
        """Speed of the colour sequences: tempo 0 (slow) .. 100 (fast)."""
        color_ms = max(200, 5000 - max(0, min(100, speed)) * 48)
        fade_ms = color_ms // 2
        for mid in ids:
            self.mesh.set_sequence_color_duration(mid, color_ms)
            self.mesh.set_sequence_fade_duration(mid, fade_ms)

    # ------------------------------------------------------------------ campfire

    def drop_software_effect(self, mesh_id: int) -> None:
        """Stop the campfire on a lamp this bridge no longer controls."""
        self._fire_targets.pop(mesh_id, None)

    def _fire_step(self) -> None:
        if not self._fire_targets:
            self._fire_running = False
            return
        # update one lamp per tick (round robin) so the BLE link is not flooded
        ids = list(self._fire_targets)
        self._fire_idx = (self._fire_idx + 1) % len(ids)
        mid = ids[self._fire_idx]
        f = self._fire_targets.get(mid)
        if f is not None and self.is_available(mid):
            self._step_fire(mid, f, len(ids))
        # tempo 0 -> 450 ms, 100 -> 150 ms per tick (fastest lamp decides)
        max_speed = max(self.lamp_setting(i, "fire_speed") for i in ids)
        tick = (450 - max(0, min(100, max_speed)) * 3) / 1000.0
        self.sched.post_delayed(tick if len(ids) > 1 else max(tick, FIRE_TICK_S), self._fire_step, key="fire")

    def _step_fire(self, mid: int, f: _Fire, lamps: int) -> None:
        strength = max(0, min(100, self.lamp_setting(mid, "fire_strength"))) / 100.0
        speed_f = max(0, min(100, self.lamp_setting(mid, "fire_speed"))) / 100.0
        low = 1.0 - (0.15 + 0.6 * strength)
        r = self._rnd.random()
        if r < 0.08 * strength:
            f.target = max(0.08, low - 0.25 * strength)  # gust: fire dips
        elif r < 0.14 * strength:
            f.target = 1.0  # spark
        elif r < 0.55:
            f.target = low + self._rnd.random() * (1.0 - low)  # normal flicker
        react = (0.35 + 0.5 * speed_f) * (0.9 if lamps <= 1 else 1.0)
        f.heat += (f.target - f.heat) * react
        hue_target = 6.0 + f.heat * 32.0 + (self._rnd.random() - 0.5) * 6.0 * (0.3 + strength)
        f.hue += (hue_target - f.hue) * 0.7
        cr, cg, cb = hsv_to_rgb(max(0.0, min(50.0, f.hue)), 1.0, 1.0)
        cap = (max(5, min(100, self.lamp_setting(mid, "fire_brightness"))) / 100.0) * f.level
        bright = int(0x0A + math.pow(max(f.heat, 0.0), 1.4) * cap * (0x64 - 0x0A))
        bright = max(0x0A, min(0x64, bright))
        self.mesh.set_color(mid, cr, cg, cb, coalesce=True)
        self.mesh.set_color_brightness(mid, bright, coalesce=True)
        st = self._st(mid)
        st.r, st.g, st.b = cr, cg, cb

    # ------------------------------------------------------------------ music (distributed)

    def set_music_mode(self, on: bool, targets: list[int]) -> None:
        self.music_global_on = on
        if on:
            for mid in targets:
                if mid not in self._music_targets:
                    self._music_targets.add(mid)
                    st = self._st(mid)
                    st.effect, st.color_mode, st.on = EFFECT_MUSIC, True, True
                    self._notify_state(mid)
            self._last_music_brightness = -1
        else:
            old = list(self._music_targets)
            self._music_targets.clear()
            for mid in old:
                # leave the lamp on its last music colour; HA shows it as static colour
                self._st(mid).effect = EFFECT_NONE
                self._notify_state(mid)

    def set_music_target(self, mesh_id: int, on: bool) -> None:
        if on:
            if self.music_global_on and mesh_id not in self._music_targets:
                self._music_targets.add(mesh_id)
                st = self._st(mesh_id)
                st.effect, st.color_mode, st.on = EFFECT_MUSIC, True, True
                self._notify_state(mesh_id)
        elif mesh_id in self._music_targets:
            self._music_targets.discard(mesh_id)
            self._st(mesh_id).effect = EFFECT_NONE
            self._notify_state(mesh_id)

    def start_mic(self) -> bool:
        if self._mic is not None:
            return True
        from .music import MusicMode  # optional dependencies (numpy, sounddevice)

        def frame(r: int, g: int, b: int, level: float, beat: bool) -> None:
            # called from the audio thread
            self.sched.post_threadsafe(self._on_raw_mic_frame, r, g, b, level, beat)

        m = MusicMode(frame)
        m.sensitivity = self._music_sensitivity
        if not m.start():
            return False
        self._mic = m
        return True

    def _on_raw_mic_frame(self, r: int, g: int, b: int, level: float, beat: bool) -> None:
        now = time.monotonic()
        # about 4 colour updates per second (BLE mesh can't do much more), immediately on beats
        if beat or now - self._last_mic_frame_at >= 0.24:
            self._last_mic_frame_at = now
            bright = max(0x0A, min(0x64, int(0x0A + level * (0x64 - 0x0A))))
            if self.on_mic_frame:
                self.on_mic_frame(r, g, b, bright, beat)

    def stop_mic(self) -> None:
        if self._mic is not None:
            self._mic.stop()
        self._mic = None

    def apply_music_frame(self, r: int, g: int, b: int, bright: int, beat: bool) -> None:
        """Apply one music frame (from this or another bridge) to the local music lamps."""
        if not self._music_targets:
            return
        targets = [t for t in self._music_targets if self.is_available(t)]
        if not targets:
            return
        all_lamps = all(d.meshId in self._music_targets or not self.is_available(d.meshId) for d in self.lights)
        dests = [BROADCAST] if all_lamps and len(targets) > 1 else targets
        send_bright = abs(bright - self._last_music_brightness) >= 8 or beat
        for d in dests:
            self.mesh.set_color(d, r, g, b, coalesce=True)
            if send_bright:
                self.mesh.set_color_brightness(d, bright, coalesce=True)
        if send_bright:
            self._last_music_brightness = bright
        for t in targets:
            st = self._st(t)
            st.r, st.g, st.b, st.color_brightness = r, g, b, bright

    def shutdown(self) -> None:
        self.stop_mic()
        self._music_targets.clear()
        self._fire_targets.clear()
        self.sched.remove("fire")
        self._fire_running = False
