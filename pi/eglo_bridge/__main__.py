"""Entry point: ``eglo-bridge [run|setup|import|devices|scan]`` (replaces BridgeService + MainActivity)."""

from __future__ import annotations

import argparse
import asyncio
import getpass
import signal
import sys
from pathlib import Path

from . import __version__, log
from .config import DEFAULT_PATH, Config
from .sched import Scheduler


class Bridge:
    """Owns mesh, light controller and MQTT; restartable from Home Assistant."""

    def __init__(self, config_path: Path):
        self.config_path = config_path
        self.loop = asyncio.get_running_loop()
        self.sched = Scheduler(self.loop)
        self.mesh = None
        self.lights = None
        self.mqtt = None
        self.stopped = asyncio.Event()
        #: process exit code; non-zero lets systemd (Restart=on-failure) start us again
        self.exit_code = 0
        self._log_listener = lambda line: self.sched.post_threadsafe(self._forward_log, line)

    def _forward_log(self, line: str) -> None:
        if self.mqtt is not None:
            self.mqtt.on_log(line)

    def start(self) -> bool:
        from . import music
        from .lights import LightController
        from .mesh import MeshManager
        from .mqtt import MqttBridge, slug

        cfg = Config.load(self.config_path)
        if not cfg.is_complete:
            log.e(f"Konfiguration unvollständig ({self.config_path}) – bitte „eglo-bridge setup“ ausführen")
            return False
        log.i(f"EGLO Bridge {__version__} (Linux) gestartet")
        log.verbose = cfg.debugLog
        self.mesh = MeshManager(self.sched, self, adapter=cfg.adapter)
        self.lights = LightController(self.sched, self.mesh, cfg.devices)
        self.lights.music_sensitivity = cfg.musicSensitivity
        has_mic = music.available()
        self.mqtt = MqttBridge(self.sched, self.mesh, self.lights, __version__, slug(cfg.bridgeName),
                               cfg.bridgeName, has_mic)
        self.mqtt.on_service_command = self._on_service_command
        self.mqtt.start(cfg)
        log.listeners.append(self._log_listener)
        self.mesh.start(cfg)
        return True

    async def stop(self) -> None:
        if self._log_listener in log.listeners:
            log.listeners.remove(self._log_listener)
        if self.lights is not None:
            self.lights.shutdown()
        if self.mesh is not None:
            await self.mesh.stop()
        if self.lights is not None:
            self.lights.mark_all_offline()
        if self.mqtt is not None:
            await self.mqtt.stop()
        self.mesh = self.lights = self.mqtt = None

    # ---------------------------------------------------------------- MeshListener

    def on_mesh_state(self, state: str) -> None:
        if self.mqtt is not None:
            self.mqtt.on_bridge_state(state)
        if not state.startswith("verbunden") and self.lights is not None:
            self.lights.mark_all_offline()

    def on_status(self, status) -> None:
        if self.lights is not None:
            self.lights.on_status(status)

    def on_device_offline(self, mesh_id: int) -> None:
        if self.lights is not None:
            self.lights.set_available(mesh_id, False)

    # ---------------------------------------------------------------- commands from HA

    def _on_service_command(self, cmd: str) -> None:
        if cmd == "restart":
            self.sched.spawn(self._restart())
        elif cmd == "reimport":
            self.sched.spawn(self._reimport())
        else:
            log.w(f"Unbekannter Befehl „{cmd}“ (auf dem Pi nicht verfügbar)")

    async def _restart(self) -> None:
        log.i("Neustart der Bridge (aus Home Assistant)")
        await self.stop()
        # give the old MQTT session time to say goodbye before the new one says hello
        await asyncio.sleep(4)
        if not self.start():
            self.exit_code = 1
            self.stopped.set()

    async def _reimport(self) -> None:
        cfg = Config.load(self.config_path)
        try:
            log.i("Lampen werden neu aus der AwoX-Cloud importiert …")
            n = await self.loop.run_in_executor(None, _import_into, cfg)
            log.i(f"Import: {n} Geräte – Bridge startet neu")
        except Exception as err:  # noqa: BLE001
            log.e("Import fehlgeschlagen", err)
            return
        await self._restart()


def _import_into(cfg: Config) -> int:
    from .cloud import AwoxCloud

    r = AwoxCloud(cfg.awoxUser, cfg.awoxPassword).load()
    cfg.meshName = r.mesh_name
    cfg.meshPassword = r.mesh_password
    cfg.devices = r.devices
    cfg.save()
    return len(r.devices)


async def _run(config_path: Path) -> int:
    bridge = Bridge(config_path)
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, bridge.stopped.set)
        except NotImplementedError:
            pass
    if not bridge.start():
        return 2
    await bridge.stopped.wait()
    log.i("Bridge wird beendet")
    await bridge.stop()
    return bridge.exit_code


# -------------------------------------------------------------------- CLI helpers

def _ask(prompt: str, default: str = "", secret: bool = False) -> str:
    shown = f" [{'***' if secret and default else default}]" if default else ""
    try:
        value = getpass.getpass(f"{prompt}{shown}: ") if secret else input(f"{prompt}{shown}: ")
    except EOFError:  # no terminal (e.g. install.sh without input): keep the default
        print()
        raise SystemExit("Keine Eingabe möglich – „eglo-bridge setup“ bitte in einem Terminal ausführen")
    return value.strip() or default


def _setup(path: Path) -> int:
    cfg = Config.load(path)
    print(f"EGLO Bridge {__version__} – Einrichtung ({path})\n")
    cfg.bridgeName = _ask("Name dieser Bridge in Home Assistant (eindeutig!)", cfg.bridgeName)
    cfg.mqttHost = _ask("MQTT-Broker (IP von Home Assistant)", cfg.mqttHost)
    cfg.mqttPort = int(_ask("MQTT-Port", str(cfg.mqttPort)))
    cfg.mqttUser = _ask("MQTT-Benutzer", cfg.mqttUser)
    cfg.mqttPassword = _ask("MQTT-Passwort", cfg.mqttPassword, secret=True)
    cfg.awoxUser = _ask("AwoX/EGLO-Connect-Konto (E-Mail)", cfg.awoxUser)
    cfg.awoxPassword = _ask("AwoX/EGLO-Connect-Passwort", cfg.awoxPassword, secret=True)
    cfg.save()
    print("\nImportiere Lampen aus der AwoX-Cloud …")
    try:
        n = _import_into(cfg)
    except Exception as err:  # noqa: BLE001
        print(f"Import fehlgeschlagen: {err}\nDie übrigen Einstellungen sind gespeichert; "
              "mit „eglo-bridge import“ erneut versuchen.")
        return 1
    print(f"{n} Geräte importiert:")
    _print_devices(cfg)
    return 0


def _print_devices(cfg: Config) -> None:
    for d in cfg.devices:
        kind = ", ".join(k for k, on in (("Farbe", d.has_color), ("Weißton", d.has_temperature),
                                         ("dimmbar", d.has_dimming)) if on) or "-"
        print(f"  #{d.meshId:<4} {d.name.strip():<28} {d.mac}  {d.model}  ({kind})")


async def _scan(path: Path, seconds: float) -> int:
    from bleak import BleakScanner

    from .mesh import AWOX_MANUFACTURER_ID

    cfg = Config.load(path)
    names = {d.mac: d.name.strip() for d in cfg.devices}
    seen: dict[str, int] = {}

    def cb(device, adv) -> None:
        mac = device.address.upper()
        if mac in names or AWOX_MANUFACTURER_ID in (adv.manufacturer_data or {}):
            seen[mac] = adv.rssi

    kwargs = {"detection_callback": cb}
    if cfg.adapter:
        kwargs["bluez"] = {"adapter": cfg.adapter}
    print(f"Suche {seconds:.0f} s nach EGLO/AwoX-Lampen …")
    async with BleakScanner(**kwargs):
        await asyncio.sleep(seconds)
    if not seen:
        print("Keine Lampe gefunden.")
        return 1
    for mac, rssi in sorted(seen.items(), key=lambda kv: -kv[1]):
        quality = "gut" if rssi >= -75 else "mittel" if rssi >= -88 else "schwach"
        print(f"  {names.get(mac, 'unbekannt'):<28} {mac}  RSSI {rssi:>4}  ({quality})")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="eglo-bridge", description="EGLO/AwoX BLE-Mesh ↔ MQTT-Bridge")
    p.add_argument("--config", type=Path, default=DEFAULT_PATH, help=f"Konfigurationsdatei ({DEFAULT_PATH})")
    p.add_argument("--version", action="version", version=__version__)
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("run", help="Bridge starten (Standard)")
    sub.add_parser("setup", help="MQTT/AwoX einrichten und Lampen importieren")
    sub.add_parser("import", help="Lampen neu aus der AwoX-Cloud importieren")
    sub.add_parser("devices", help="konfigurierte Lampen anzeigen")
    sp = sub.add_parser("scan", help="Empfang der Lampen an diesem Standort prüfen")
    sp.add_argument("--seconds", type=float, default=10.0)
    args = p.parse_args(argv)

    if args.cmd == "setup":
        return _setup(args.config)
    if args.cmd == "import":
        cfg = Config.load(args.config)
        if not cfg.awoxUser:
            cfg.awoxUser = _ask("AwoX/EGLO-Connect-Konto (E-Mail)")
            cfg.awoxPassword = _ask("AwoX/EGLO-Connect-Passwort", secret=True)
        n = _import_into(cfg)
        print(f"{n} Geräte importiert.")
        _print_devices(cfg)
        return 0
    if args.cmd == "devices":
        _print_devices(Config.load(args.config))
        return 0
    if args.cmd == "scan":
        return asyncio.run(_scan(args.config, args.seconds))
    return asyncio.run(_run(args.config))


if __name__ == "__main__":
    sys.exit(main())
