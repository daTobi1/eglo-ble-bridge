"""Display control for a Pi wall panel (port of the kiosk screen of the Android app).

- switches the display backlight via ``/sys/class/backlight`` (official Raspberry Pi touch
  displays and most DSI panels); works with any kiosk browser and desktop session
- "Bildschirm aus nach": after N minutes without touch the backlight goes off (0 = never)
- a touch wakes the display; while it is dark the touchscreen is grabbed (EVIOCGRAB), so
  the waking touch does not reach the dashboard - like the black overlay on the tablet
- brightness in percent of the panel's ``max_brightness``

Without a backlight device (e.g. HDMI monitor) the module stays inactive and no entities
are announced. All methods run on the event loop.
"""

from __future__ import annotations

import fcntl
import os
import struct
import time
from collections.abc import Callable
from pathlib import Path

from . import log
from .sched import Scheduler

BACKLIGHT_ROOT = Path("/sys/class/backlight")
INPUT_DEVICES = Path("/proc/bus/input/devices")
INPUT_DIR = Path("/dev/input")

EV_KEY = 0x01
EV_ABS = 0x03
BTN_TOUCH = 0x14A
ABS_MT_POSITION_X = 0x35
#: _IOW('E', 0x90, int)
EVIOCGRAB = 0x40044590
#: struct input_event: struct timeval (2 x long), __u16 type, __u16 code, __s32 value
EVENT = struct.Struct("llHHi")
#: the bitmaps in /proc/bus/input/devices use the *kernel's* long (a 64-bit kernel may run 32-bit userland)
KERNEL_LONG_BITS = 64 if "64" in os.uname().machine else 32

TICK_S = 15.0
#: after a waking touch the grab is released at the latest after this time (finger lifted)
RELEASE_GRAB_S = 1.5


def find_backlight(root: Path = BACKLIGHT_ROOT) -> Path | None:
    """First backlight device that can be switched or dimmed."""
    try:
        entries = sorted(root.iterdir())
    except OSError:
        return None
    for p in entries:
        if (p / "bl_power").exists() or (p / "brightness").exists():
            return p
    return None


def _has_bit(bitmap: str, bit: int, long_bits: int = KERNEL_LONG_BITS) -> bool:
    """Test a bit in a /proc/bus/input/devices bitmap (hex words, most significant first)."""
    words = bitmap.split()
    idx = len(words) - 1 - bit // long_bits
    if idx < 0:
        return False
    try:
        return bool(int(words[idx], 16) >> (bit % long_bits) & 1)
    except ValueError:
        return False


def find_touch_devices(devices_file: Path = INPUT_DEVICES, input_dir: Path = INPUT_DIR,
                       long_bits: int = KERNEL_LONG_BITS) -> list[Path]:
    """Event devices of touchscreens (multi-touch position axis)."""
    try:
        text = devices_file.read_text()
    except OSError:
        return []
    found: list[Path] = []
    for block in text.split("\n\n"):
        handlers = abs_bits = ""
        for line in block.splitlines():
            if line.startswith("H: Handlers="):
                handlers = line.split("=", 1)[1]
            elif line.startswith("B: ABS="):
                abs_bits = line.split("=", 1)[1]
        if not abs_bits or not _has_bit(abs_bits, ABS_MT_POSITION_X, long_bits):
            continue
        for h in handlers.split():
            if h.startswith("event"):
                found.append(input_dir / h)
    return found


class Screen:
    def __init__(self, sched: Scheduler, backlight: Path | None = None,
                 touch_devices: list[Path] | None = None,
                 clock: Callable[[], float] = time.monotonic):
        self.sched = sched
        self.backlight = backlight
        self.touch_paths = touch_devices if touch_devices is not None else []
        self.clock = clock
        self.on = True
        #: minutes without touch until the backlight goes off; 0 = never
        self.timeout_min = 0
        self.brightness_pct = 80
        self.last_activity = clock()
        #: called after on/brightness/timeout changed (MQTT publishes the state)
        self.on_change: Callable[[], None] | None = None
        #: called after brightness/timeout were changed from Home Assistant (to persist them)
        self.on_settings_changed: Callable[[], None] | None = None
        self._fds: dict[int, Path] = {}
        self._grabbed = False
        self._touching = False
        self._running = False

    @classmethod
    def detect(cls, sched: Scheduler) -> Screen:
        return cls(sched, find_backlight(), find_touch_devices())

    @property
    def available(self) -> bool:
        return self.backlight is not None

    # ------------------------------------------------------------------ lifecycle

    def start(self, timeout_min: int, brightness_pct: int) -> None:
        if not self.available:
            log.i("Bildschirm: kein Backlight gefunden (/sys/class/backlight) – Bildschirmsteuerung aus")
            return
        self.timeout_min = max(0, min(120, int(timeout_min)))
        self.brightness_pct = max(1, min(100, int(brightness_pct)))
        self._running = True
        for p in self.touch_paths:
            try:
                fd = os.open(p, os.O_RDONLY | os.O_NONBLOCK)
            except OSError as err:
                log.w(f"Bildschirm: Touch {p} nicht lesbar ({err.strerror}) – Aufwecken per Touch nicht möglich")
                continue
            self._fds[fd] = p
            self.sched.loop.add_reader(fd, self._read, fd)
        if not self._fds:
            log.w("Bildschirm: kein Touchscreen gefunden – der Bildschirm geht nur über Home Assistant wieder an")
        log.i(f"Bildschirm: {self.backlight.name}, Touch: "
              f"{', '.join(p.name for p in self._fds.values()) or '-'}, aus nach {self.timeout_min} min")
        self.last_activity = self.clock()
        self._apply(True)
        self.sched.post_delayed(TICK_S, self._tick, key="screen-tick")

    def stop(self) -> None:
        if not self._running:
            return
        self._running = False
        self.sched.remove("screen-tick")
        self.sched.remove("screen-ungrab")
        self._grab(False)
        for fd in list(self._fds):
            try:
                self.sched.loop.remove_reader(fd)
                os.close(fd)
            except OSError:
                pass
        self._fds.clear()
        # never leave the panel dark when the bridge stops
        self._apply(True)

    # ------------------------------------------------------------------ commands (Home Assistant)

    def set_screen(self, on: bool) -> None:
        if not self._running:
            return
        self.last_activity = self.clock()
        if on == self.on:
            self._changed()
            return
        self._apply(on)
        if on:
            # nobody touches: release the grab right away; a waking touch releases it on lift-off
            if not self._touching:
                self._grab(False)
        else:
            self._grab(True)
        log.i("Bildschirm an" if on else "Bildschirm aus")
        self._changed()

    def set_brightness(self, pct: int) -> None:
        self.brightness_pct = max(1, min(100, int(pct)))
        if self._running and self.on:
            self._write_brightness()
        self._changed()
        if self.on_settings_changed:
            self.on_settings_changed()

    def set_timeout(self, minutes: int) -> None:
        self.timeout_min = max(0, min(120, int(minutes)))
        self.last_activity = self.clock()
        self._changed()
        if self.on_settings_changed:
            self.on_settings_changed()

    # ------------------------------------------------------------------ internals

    def _changed(self) -> None:
        if self.on_change:
            self.on_change()

    def _tick(self) -> None:
        if not self._running:
            return
        if (self.timeout_min > 0 and self.on and not self._touching
                and self.clock() - self.last_activity >= self.timeout_min * 60):
            log.i(f"Bildschirm aus (seit {self.timeout_min} min keine Berührung)")
            self.set_screen(False)
        self.sched.post_delayed(TICK_S, self._tick, key="screen-tick")

    def _read(self, fd: int) -> None:
        try:
            data = os.read(fd, EVENT.size * 64)
        except BlockingIOError:
            return
        except OSError as err:
            log.w(f"Bildschirm: Touch {self._fds.get(fd)} getrennt ({err.strerror})")
            self.sched.loop.remove_reader(fd)
            self._fds.pop(fd, None)
            try:
                os.close(fd)
            except OSError:
                pass
            return
        self.on_events([EVENT.unpack_from(data, i)[2:] for i in range(0, len(data) - EVENT.size + 1, EVENT.size)])

    def on_events(self, events: list[tuple[int, int, int]]) -> None:
        """Handle (type, code, value) input events of a touchscreen."""
        touched = False
        for etype, code, value in events:
            if etype == EV_KEY and code == BTN_TOUCH:
                self._touching = value != 0
                touched = True
            elif etype == EV_ABS:
                touched = True
        if not touched:
            return
        self.last_activity = self.clock()
        if not self.on:
            # this touch only wakes the display; it stays grabbed until the finger is lifted
            self.set_screen(True)
            self.sched.post_delayed(RELEASE_GRAB_S, lambda: self._grab(False) if self.on else None,
                                    key="screen-ungrab")
        elif self._grabbed and not self._touching:
            self.sched.remove("screen-ungrab")
            self._grab(False)

    def _grab(self, grab: bool) -> None:
        if grab == self._grabbed:
            return
        for fd, p in self._fds.items():
            try:
                fcntl.ioctl(fd, EVIOCGRAB, 1 if grab else 0)
            except OSError as err:
                log.d(f"Bildschirm: EVIOCGRAB {p}: {err.strerror}")
        self._grabbed = grab

    def _apply(self, on: bool) -> None:
        self.on = on
        bl = self.backlight
        if bl is None:
            return
        if (bl / "bl_power").exists():
            # 0 = FB_BLANK_UNBLANK, 4 = FB_BLANK_POWERDOWN
            self._write(bl / "bl_power", "0" if on else "4")
        if on:
            self._write_brightness()
        elif not (bl / "bl_power").exists():
            self._write(bl / "brightness", "0")

    def _write_brightness(self) -> None:
        bl = self.backlight
        if bl is None or not (bl / "brightness").exists():
            return
        try:
            mx = int((bl / "max_brightness").read_text().strip() or "255")
        except (OSError, ValueError):
            mx = 255
        self._write(bl / "brightness", str(max(1, round(mx * self.brightness_pct / 100))))

    @staticmethod
    def _write(path: Path, value: str) -> None:
        try:
            path.write_text(value)
        except OSError as err:
            log.e(f"Bildschirm: {path} nicht beschreibbar ({err.strerror}) – „sudo ./install.sh update“ ausführen")
