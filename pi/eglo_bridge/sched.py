"""Tiny replacement for android.os.Handler on top of asyncio.

All bridge logic runs on one asyncio event loop. ``post_delayed`` with a key can be
cancelled with ``remove`` (like ``Handler.removeCallbacks(runnable)``).
"""

from __future__ import annotations

import asyncio
from collections import defaultdict
from typing import Any, Callable, Hashable


class Scheduler:
    def __init__(self, loop: asyncio.AbstractEventLoop):
        self.loop = loop
        self._keyed: dict[Hashable, list[asyncio.TimerHandle]] = defaultdict(list)
        self._tasks: set[asyncio.Task] = set()

    def post(self, fn: Callable[..., Any], *args: Any) -> None:
        self.loop.call_soon(fn, *args)

    def post_threadsafe(self, fn: Callable[..., Any], *args: Any) -> None:
        """Run ``fn`` on the loop; may be called from any thread (MQTT, audio)."""
        try:
            self.loop.call_soon_threadsafe(fn, *args)
        except RuntimeError:  # loop already closed during shutdown
            pass

    def post_delayed(self, delay_s: float, fn: Callable[[], Any], key: Hashable | None = None) -> None:
        if key is None:
            self.loop.call_later(delay_s, fn)
            return
        handles = self._keyed[key]
        handle: asyncio.TimerHandle

        def run() -> None:
            if handle in handles:
                handles.remove(handle)
            fn()

        handle = self.loop.call_later(delay_s, run)
        handles.append(handle)

    def remove(self, key: Hashable) -> None:
        for h in self._keyed.pop(key, []):
            h.cancel()

    def pending(self, key: Hashable) -> bool:
        return bool(self._keyed.get(key))

    def spawn(self, coro) -> asyncio.Task:
        """Start a background task and keep a reference so it is not garbage collected."""
        t = self.loop.create_task(coro)
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)
        return t

    def cancel_all(self) -> None:
        for key in list(self._keyed):
            self.remove(key)
