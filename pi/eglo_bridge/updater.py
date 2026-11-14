"""Update the Pi bridge from Home Assistant.

The bridge runs as an unprivileged, sandboxed service and cannot update itself. It only asks
for an update by creating ``update-request`` in its config directory; the root unit pair
``eglo-bridge-update.path`` / ``eglo-bridge-update.service`` (installed by ``install.sh``) sees
the file, deletes it and runs ``install.sh update`` (git pull, reinstall, restart).
"""

from __future__ import annotations

import re
import urllib.request
from pathlib import Path

LATEST_URL = "https://raw.githubusercontent.com/daTobi1/eglo-ble-bridge/main/pi/eglo_bridge/__init__.py"
RELEASE_URL = "https://github.com/daTobi1/eglo-ble-bridge/commits/main/pi"
PATH_UNIT = Path("/etc/systemd/system/eglo-bridge-update.path")
REQUEST_NAME = "update-request"

_VERSION_RE = re.compile(r"""__version__\s*=\s*["']([^"']+)["']""")


def parse_version_file(text: str) -> str | None:
    m = _VERSION_RE.search(text)
    return m.group(1).strip() if m else None


def version_key(v: str) -> tuple[int, ...]:
    """'0.10.2' -> (0, 10, 2); non-numeric parts count as 0."""
    out = []
    for part in v.split("."):
        m = re.match(r"\d+", part)
        out.append(int(m.group(0)) if m else 0)
    return tuple(out)


def is_newer(latest: str | None, current: str) -> bool:
    return bool(latest) and version_key(latest) > version_key(current)


def fetch_latest(timeout: float = 15.0) -> str | None:
    """Version of the Pi bridge on GitHub (main), None if GitHub cannot be reached."""
    req = urllib.request.Request(LATEST_URL, headers={"User-Agent": "eglo-ble-bridge-pi"})
    with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 - fixed https URL
        return parse_version_file(r.read(65536).decode("utf-8", "replace"))


def updater_installed() -> bool:
    """True once ``install.sh`` has installed the root update units."""
    return PATH_UNIT.exists()


def request_update(config_dir: Path) -> None:
    """Ask the root update unit to update (raises OSError if the file cannot be written)."""
    (config_dir / REQUEST_NAME).write_text("update\n")
