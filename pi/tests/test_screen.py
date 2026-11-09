"""Display control (backlight timeout, touch wake-up) with a fake sysfs and fake touch device."""

from __future__ import annotations

import asyncio
import os

from eglo_bridge import screen as screen_mod
from eglo_bridge.sched import Scheduler
from eglo_bridge.screen import (
    BTN_TOUCH,
    EV_ABS,
    EV_KEY,
    EVENT,
    Screen,
    find_backlight,
    find_touch_devices,
)

DEVICES = """I: Bus=0018 Vendor=0000 Product=0000 Version=0000
N: Name="generic ft5x06 (79)"
H: Handlers=mouse0 event2
B: EV=b
B: KEY=400 0 0 0 0 0 0 0 0 0 0
B: ABS=2658000 3

I: Bus=0003 Vendor=046d Product=c52b Version=0111
N: Name="Logitech Keyboard"
H: Handlers=sysrq kbd event0 leds
B: EV=120013
B: KEY=1000000000007 ff9f207ac14057ff febeffdfffefffff fffffffffffffffe
"""


def _backlight(tmp_path, bl_power=True):
    bl = tmp_path / "backlight" / "10-0045"
    bl.mkdir(parents=True)
    (bl / "max_brightness").write_text("255\n")
    (bl / "brightness").write_text("255\n")
    if bl_power:
        (bl / "bl_power").write_text("0\n")
    return bl


def test_find_backlight_and_touch(tmp_path):
    bl = _backlight(tmp_path)
    assert find_backlight(tmp_path / "backlight") == bl
    assert find_backlight(tmp_path / "missing") is None
    f = tmp_path / "devices"
    f.write_text(DEVICES)
    # 32-bit kernel: bitmap in 32-bit words
    assert find_touch_devices(f, tmp_path, long_bits=32) == [tmp_path / "event2"]
    # 64-bit kernel (Raspberry Pi OS 64 bit): one 64-bit word
    f.write_text(DEVICES.replace("B: ABS=2658000 3", "B: ABS=660800000000003"))
    assert find_touch_devices(f, tmp_path, long_bits=64) == [tmp_path / "event2"]


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def test_timeout_touch_wake_and_grab(tmp_path, monkeypatch):
    bl = _backlight(tmp_path)
    grabs: list[int] = []
    monkeypatch.setattr(screen_mod.fcntl, "ioctl", lambda _fd, _req, v: grabs.append(v))
    r, w = os.pipe()
    touch = tmp_path / "event2"
    clock = Clock()

    async def run():
        sched = Scheduler(asyncio.get_running_loop())
        sc = Screen(sched, bl, [], clock=clock)
        changes = []
        sc.on_change = lambda: changes.append(sc.on)
        sc.start(timeout_min=1, brightness_pct=50)
        # use the pipe as touch device
        sc._fds[r] = touch
        sched.loop.add_reader(r, sc._read, r)
        assert (bl / "brightness").read_text() == "128"
        assert (bl / "bl_power").read_text() == "0"

        # 61 s without touch -> backlight off, touch grabbed
        clock.t += 61
        sc._tick()
        assert not sc.on and (bl / "bl_power").read_text() == "4"
        assert grabs == [1] and changes[-1] is False

        # finger down wakes the display, but stays grabbed until it is lifted
        os.write(w, EVENT.pack(0, 0, EV_KEY, BTN_TOUCH, 1) + EVENT.pack(0, 0, EV_ABS, 0x35, 100))
        await asyncio.sleep(0.05)
        assert sc.on and (bl / "bl_power").read_text() == "0"
        assert (bl / "brightness").read_text() == "128"
        assert grabs == [1]
        os.write(w, EVENT.pack(0, 0, EV_KEY, BTN_TOUCH, 0))
        await asyncio.sleep(0.05)
        assert grabs == [1, 0]

        # touches reset the timer
        clock.t += 50
        os.write(w, EVENT.pack(0, 0, EV_ABS, 0x35, 1))
        await asyncio.sleep(0.05)
        clock.t += 50
        sc._tick()
        assert sc.on

        # timeout 0 = never
        sc.set_timeout(0)
        clock.t += 100000
        sc._tick()
        assert sc.on

        # off/on from Home Assistant, brightness
        sc.set_screen(False)
        assert (bl / "bl_power").read_text() == "4"
        sc.set_screen(True)
        assert (bl / "bl_power").read_text() == "0" and grabs[-1] == 0
        sc.set_brightness(100)
        assert (bl / "brightness").read_text() == "255"

        # stopping never leaves the panel dark
        sc.set_screen(False)
        sc.stop()
        assert sc.on and (bl / "bl_power").read_text() == "0"
        os.close(w)

    asyncio.run(run())


def test_without_bl_power_uses_brightness(tmp_path):
    bl = _backlight(tmp_path, bl_power=False)

    async def run():
        sc = Screen(Scheduler(asyncio.get_running_loop()), bl, [])
        sc.start(0, 40)
        assert (bl / "brightness").read_text() == "102"
        sc.set_screen(False)
        assert (bl / "brightness").read_text() == "0"
        sc.set_screen(True)
        assert (bl / "brightness").read_text() == "102"
        sc.stop()

    asyncio.run(run())


def test_no_backlight_is_inactive():
    async def run():
        sc = Screen(Scheduler(asyncio.get_running_loop()), None, [])
        assert not sc.available
        sc.start(5, 50)
        sc.set_screen(False)
        sc.stop()

    asyncio.run(run())
