"""Small in-memory ring buffer log (port of BridgeLog.kt); also writes to stdout/journald."""

from __future__ import annotations

import sys
import time
from collections import deque
from typing import Callable

_MAX = 400
_lines: deque[str] = deque(maxlen=_MAX)
listeners: list[Callable[[str], None]] = []

#: log every mesh packet (RX/TX); toggled from Home Assistant
verbose = False


def _add(level: str, msg: str) -> None:
    line = f"{time.strftime('%H:%M:%S')} {level} {msg}"
    _lines.append(line)
    print(f"{level} {msg}", file=sys.stderr if level == "E" else sys.stdout, flush=True)
    for listener in list(listeners):
        try:
            listener(line)
        except Exception:  # noqa: BLE001 - a broken listener must not break logging
            pass


def i(msg: str) -> None:
    _add("I", msg)


def w(msg: str) -> None:
    _add("W", msg)


def e(msg: str, exc: BaseException | None = None) -> None:
    _add("E", f"{msg}: {type(exc).__name__}: {exc}" if exc is not None else msg)


def snapshot() -> list[str]:
    return list(_lines)
