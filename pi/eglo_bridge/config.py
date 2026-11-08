"""Configuration (JSON file instead of Android SharedPreferences) and device model."""

from __future__ import annotations

import json
import os
import socket
from dataclasses import asdict, dataclass, field
from pathlib import Path

DEFAULT_PATH = Path(os.environ.get("EGLO_BRIDGE_CONFIG", "/etc/eglo-bridge/config.json"))


def normalize_mac(raw: str) -> str:
    """Normalise MAC to upper case with colons."""
    h = raw.replace(":", "").replace("-", "").upper()
    if len(h) != 12:
        return raw.upper()
    return ":".join(h[i:i + 2] for i in range(0, 12, 2))


@dataclass
class MeshDevice:
    meshId: int  # noqa: N815 - same JSON keys as the Android app
    name: str = ""
    mac: str = ""
    model: str = ""
    vendor: str = ""
    firmware: str = ""
    type: str = ""

    @property
    def is_light(self) -> bool:
        return "light" in self.type

    @property
    def has_color(self) -> bool:
        return "color" in self.type

    @property
    def has_temperature(self) -> bool:
        return "temperature" in self.type

    @property
    def has_dimming(self) -> bool:
        return "dimming" in self.type

    @classmethod
    def from_json(cls, o: dict) -> "MeshDevice":
        return cls(
            meshId=int(o["meshId"]), name=o.get("name", ""), mac=normalize_mac(o.get("mac", "")),
            model=o.get("model", ""), vendor=o.get("vendor", ""), firmware=o.get("firmware", ""),
            type=o.get("type", ""),
        )


def _default_bridge_name() -> str:
    return "Pi " + socket.gethostname()


@dataclass
class Config:
    awoxUser: str = ""  # noqa: N815
    awoxPassword: str = ""  # noqa: N815
    meshName: str = ""  # noqa: N815
    meshPassword: str = ""  # noqa: N815
    mqttHost: str = ""  # noqa: N815
    mqttPort: int = 1883  # noqa: N815
    mqttUser: str = ""  # noqa: N815
    mqttPassword: str = ""  # noqa: N815
    #: name of this bridge in Home Assistant (also its MQTT id) - must be unique per bridge
    bridgeName: str = field(default_factory=_default_bridge_name)  # noqa: N815
    debugLog: bool = False  # noqa: N815
    musicSensitivity: float = 1.0  # noqa: N815
    #: Bluetooth adapter, e.g. "hci0" (empty = default)
    adapter: str = ""
    #: lamp connections: "att" = own ATT channel (default), "bluez" = BlueZ GATT via bleak
    bleBackend: str = "att"  # noqa: N815
    #: mesh ids of the lamps this bridge is responsible for; None = all lamps
    assignedLamps: list[int] | None = None  # noqa: N815
    devices: list[MeshDevice] = field(default_factory=list)

    path: Path = field(default=DEFAULT_PATH, repr=False, compare=False)

    def is_assigned(self, mesh_id: int) -> bool:
        return self.assignedLamps is None or mesh_id in self.assignedLamps

    @property
    def is_complete(self) -> bool:
        return bool(self.meshName and self.meshPassword and self.devices and self.mqttHost)

    @classmethod
    def load(cls, path: Path = DEFAULT_PATH) -> "Config":
        cfg = cls(path=path)
        if path.exists():
            raw = json.loads(path.read_text(encoding="utf-8"))
            for k, v in raw.items():
                if k == "devices":
                    cfg.devices = [MeshDevice.from_json(d) for d in v]
                elif k == "assignedLamps":
                    cfg.assignedLamps = None if v is None else [int(x) for x in v]
                elif k != "path" and hasattr(cfg, k):
                    setattr(cfg, k, v)
        return cfg

    def save(self) -> None:
        data = asdict(self)
        data.pop("path", None)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        os.chmod(tmp, 0o600)  # contains passwords
        tmp.replace(self.path)
