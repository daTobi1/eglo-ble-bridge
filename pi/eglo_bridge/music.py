"""Music mode from a microphone (port of MusicMode.kt).

Optional: needs ``numpy`` and ``sounddevice`` (pip extra ``music``) and a USB microphone.
Loudness -> brightness (automatic gain), spectrum -> hue, beats -> hue jumps.
``on_frame`` is called from the audio thread roughly every 64 ms.
"""

from __future__ import annotations

import math
import threading
import time
from typing import Callable

from . import log
from .lights import hsv_to_rgb

RATE = 16000
N = 1024  # ~64 ms per FFT frame


def available() -> bool:
    """True if the audio libraries are installed and an input device exists."""
    try:
        import numpy  # noqa: F401
        import sounddevice as sd
    except Exception:  # noqa: BLE001 - missing libs or PortAudio
        return False
    try:
        return any(d.get("max_input_channels", 0) > 0 for d in sd.query_devices())
    except Exception:  # noqa: BLE001
        return False


class MusicMode:
    def __init__(self, on_frame: Callable[[int, int, int, float, bool], None]):
        self.on_frame = on_frame
        self.sensitivity = 1.0  # 0.5 .. 2.0
        self._running = False
        self._thread: threading.Thread | None = None

    @property
    def is_running(self) -> bool:
        return self._running

    def start(self) -> bool:
        if self._running:
            return True
        try:
            import numpy  # noqa: F401
            import sounddevice as sd
        except Exception as err:  # noqa: BLE001
            log.e("Mikrofon: numpy/sounddevice nicht installiert", err)
            return False
        stream = None
        rates = [RATE]
        try:
            native = int(sd.query_devices(kind="input")["default_samplerate"])
            if native and native != RATE:
                rates.append(native)  # most USB microphones only offer 44.1/48 kHz
        except Exception:  # noqa: BLE001
            pass
        last_err: Exception | None = None
        for rate in rates:
            try:
                stream = sd.InputStream(samplerate=rate, channels=1, dtype="int16", blocksize=N)
                stream.start()
                break
            except Exception as err:  # noqa: BLE001
                last_err, stream = err, None
        if stream is None:
            log.e("Mikrofon konnte nicht geöffnet werden", last_err)
            return False
        self._running = True
        self._thread = threading.Thread(target=self._loop, args=(stream, int(stream.samplerate)),
                                        name="music", daemon=True)
        self._thread.start()
        log.i("Musikmodus gestartet")
        return True

    def stop(self) -> None:
        if not self._running:
            return
        self._running = False
        if self._thread is not None:
            self._thread.join(0.5)
        self._thread = None
        log.i("Musikmodus gestoppt")

    def _loop(self, stream, rate: int) -> None:
        import numpy as np

        window = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N) / (N - 1))
        freqs = np.arange(N // 2) * rate / N
        bass_mask = (freqs < 250)
        mid_mask = (freqs >= 250) & (freqs < 2000)
        high_mask = freqs >= 2000
        bass_mask[0] = mid_mask[0] = high_mask[0] = False  # skip DC like the Kotlin loop
        peak = 1e-3
        avg_energy = 0.0
        smooth_level = 0.0
        base_hue = 0.0
        smooth_hue = 0.0
        last_beat = 0.0
        try:
            while self._running:
                data, _overflow = stream.read(N)
                s = data[:, 0].astype(np.float64) / 32768.0
                rms = math.sqrt(float(np.mean(s * s))) * self.sensitivity
                spec = np.fft.fft(s * window)[: N // 2]
                mag = spec.real ** 2 + spec.imag ** 2
                bass = float(mag[bass_mask].sum())
                mid = float(mag[mid_mask].sum()) * 2.0
                high = float(mag[high_mask].sum()) * 6.0
                total = bass + mid + high + 1e-12

                peak = max(peak * 0.997, rms)
                level = max(0.0, min(1.0, rms / peak))
                lv = math.log(1 + 9 * level) / math.log(10.0)
                smooth_level = lv if lv > smooth_level else smooth_level * 0.8 + lv * 0.2

                now = time.monotonic()
                beat = bass > avg_energy * 1.6 and bass > 1e-4 and now - last_beat > 0.25
                avg_energy = avg_energy * 0.95 + bass * 0.05
                if beat:
                    last_beat = now
                    base_hue = (base_hue + 47) % 360

                band_hue = (120.0 * mid + 240.0 * high) / total
                target_hue = (base_hue + band_hue) % 360
                d = target_hue - smooth_hue
                if d > 180:
                    d -= 360
                if d < -180:
                    d += 360
                smooth_hue = (smooth_hue + d * (1.0 if beat else 0.35) + 360) % 360

                r, g, b = hsv_to_rgb(smooth_hue, 1.0, 1.0)
                self.on_frame(r, g, b, smooth_level, beat)
        except Exception as err:  # noqa: BLE001
            log.e("Musikmodus-Fehler", err)
        finally:
            try:
                stream.stop()
                stream.close()
            except Exception:  # noqa: BLE001
                pass
            self._running = False
