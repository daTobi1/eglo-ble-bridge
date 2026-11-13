"""MCP endpoint of the bridge (port of McpServer.kt).

Model Context Protocol, JSON-RPC over HTTP POST without sessions on ``http://<pi>:8765/mcp``.
An assistant controls lamps and sets up bridges through this bridge's own MQTT connection,
so it needs no MQTT login of its own.
"""

from __future__ import annotations

import asyncio
import json
import re
import time
from typing import Any, Callable

from . import log

PORT = 8765
B = "eglo_bridge"
EFFECTS = ["Kein Effekt", "Farbwechsel", "Sequenz 2", "Sequenz 3", "Sequenz 4", "Sequenz 5", "Sequenz 6",
           "Sequenz 7", "Kerze", "Lagerfeuer", "Musik"]
COMMANDS = ["reconnect", "restart", "reimport", "check_update", "install_update", "reload_kiosk"]


def _p(type_: str, desc: str, **extra) -> dict:
    return {"type": type_, "description": desc, **extra}


def _tool(name: str, desc: str, props: dict, required: list[str] | None = None) -> dict:
    return {"name": name, "description": desc,
            "inputSchema": {"type": "object", "properties": props, "required": required or []}}


BRIDGE_ARG = _p("string", "Name oder ID; leer = diese Bridge")
TOOLS = [
    _tool("eglo_overview", "Alle Bridges (Tablets/Pi) und Lampen: online, an/aus, Helligkeit, Farbe, welche Bridge steuert.", {}),
    _tool("eglo_set_light",
          f"Schaltet Lampen: an/aus, Helligkeit %, Farbtemperatur 2700–6500 K, RGB (#RRGGBB) oder Effekt ({', '.join(EFFECTS)}). "
          'lamps=["alle"] für alle.',
          {"lamps": _p("array", 'Namen/Teilnamen/IDs oder ["alle"]', items={"type": "string"}),
           "on": _p("boolean", "an/aus"), "brightness_pct": _p("number", "1–100"),
           "color_temp_kelvin": _p("integer", "2700–6500"), "rgb": _p("string", "#RRGGBB"),
           "effect": _p("string", "Effekt", enum=EFFECTS)}, ["lamps"]),
    _tool("eglo_bridge_settings", "Einstellungen und Geräteinfo einer Bridge (ohne Passwörter).", {"bridge": BRIDGE_ARG}),
    _tool("eglo_configure_bridge",
          "Richtet eine Bridge ein. Schlüssel in settings: name, debug_log, kiosk_enabled, kiosk_url, kiosk_pin, "
          "kiosk_screen_timeout, kiosk_brightness, media_volume, screen_on (Tablet); screen_timeout, screen_brightness (Pi); "
          "mqtt_host, mqtt_port, mqtt_user, mqtt_password (werden nur nach erfolgreicher Testverbindung übernommen); "
          "awox_user, awox_password; reimport, restart. Umbenennen behält Gerät und Entitäten in Home Assistant.",
          {"bridge": BRIDGE_ARG, "settings": _p("object", "zu ändernde Einstellungen")}, ["settings"]),
    _tool("eglo_bridge_command", f"Befehl an eine Bridge: {', '.join(COMMANDS)}.",
          {"bridge": BRIDGE_ARG, "command": _p("string", "Befehl", enum=COMMANDS)}, ["command"]),
    _tool("eglo_assign", 'Legt fest, ob eine Bridge für eine Lampe (oder "alle") zuständig ist.',
          {"bridge": BRIDGE_ARG, "lamp": _p("string", 'Lampe oder "alle"'),
           "responsible": _p("boolean", "zuständig ja/nein")}, ["lamp", "responsible"]),
    _tool("eglo_effects", "Musikmodus und gemeinsame Effekt-Einstellungen. Ohne Parameter: aktuellen Stand lesen.",
          {"music_mode": _p("boolean", "Musikmodus"), "music_source": _p("string", "Bridge, die per Mikrofon zuhört"),
           "music_sensitivity": _p("integer", "50–200 %"), "effect_speed": _p("integer", "0–100 %"),
           "fire_speed": _p("integer", "0–100 %"), "fire_strength": _p("integer", "0–100 %"),
           "fire_brightness": _p("integer", "5–100 %")}),
    _tool("eglo_log", "Letzte Log-Zeilen dieser Bridge.",
          {"lines": _p("integer", "Anzahl (Standard 50)"), "contains": _p("string", "Filtertext")}),
]


def _norm(s: str) -> str:
    s = s.lower().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


class McpServer:
    def __init__(self, mqtt_provider: Callable[[], Any], version: str, port: int = PORT):
        self.mqtt_provider = mqtt_provider
        self.version = version
        self.port = port
        self._server: asyncio.base_events.Server | None = None

    async def start(self) -> None:
        try:
            self._server = await asyncio.start_server(self._serve, "0.0.0.0", self.port)
            log.i(f"MCP-Zugang bereit auf Port {self.port}")
        except OSError as err:
            log.e("MCP-Zugang konnte nicht starten", err)

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None

    # ================================================================= HTTP

    async def _serve(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 15)
            lines = head.decode("utf-8", "replace").split("\r\n")
            parts = lines[0].split(" ")
            method, path = parts[0], (parts[1] if len(parts) > 1 else "/").split("?")[0].rstrip("/")
            headers = {}
            for line in lines[1:]:
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()
            length = int(headers.get("content-length", "0") or 0)
            body = await asyncio.wait_for(reader.readexactly(length), 15) if length else b""
            if method == "GET" and path in ("", "/mcp"):
                m = self.mqtt_provider()
                text = (f"EGLO Bridge MCP – {m.bridge_name if m else '-'}, MQTT "
                        f"{'verbunden' if m is not None and m.connected else 'nicht verbunden'}\n"
                        f"MCP-Endpunkt: POST http://<diese-IP>:{self.port}/mcp\n")
                await self._respond(writer, 200, "text/plain; charset=utf-8", text)
            elif method == "POST" and path == "/mcp":
                try:
                    req = json.loads(body.decode("utf-8"))
                except ValueError:
                    reply: Any = self._error(None, -32700, "Ungültiges JSON")
                else:
                    if isinstance(req, list):
                        reply = [r for r in [await self.handle(x) for x in req] if r is not None] or None
                    else:
                        reply = await self.handle(req)
                if reply is None:
                    await self._respond(writer, 202, "application/json", "")
                else:
                    await self._respond(writer, 200, "application/json", json.dumps(reply, ensure_ascii=False))
            elif method == "DELETE" and path == "/mcp":
                await self._respond(writer, 200, "application/json", "")
            else:
                await self._respond(writer, 404, "text/plain", "not found")
        except (asyncio.TimeoutError, asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            try:
                writer.close()
            except Exception:  # noqa: BLE001
                pass

    @staticmethod
    async def _respond(writer: asyncio.StreamWriter, code: int, ctype: str, body: str) -> None:
        data = body.encode("utf-8")
        status = {200: "OK", 202: "Accepted", 404: "Not Found"}.get(code, "Error")
        writer.write(f"HTTP/1.1 {code} {status}\r\nContent-Type: {ctype}\r\nContent-Length: {len(data)}\r\n"
                     f"Connection: close\r\n\r\n".encode() + data)
        await writer.drain()

    # ================================================================= JSON-RPC

    @staticmethod
    def _error(rid: Any, code: int, msg: str) -> dict:
        return {"jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": msg}}

    async def handle(self, req: dict) -> dict | None:
        if not isinstance(req, dict) or "id" not in req:
            return None  # notification
        rid, method, params = req["id"], req.get("method", ""), req.get("params") or {}
        if method == "initialize":
            result: dict = {"protocolVersion": params.get("protocolVersion") or "2025-06-18",
                            "capabilities": {"tools": {}},
                            "serverInfo": {"name": "eglo-bridge", "version": self.version},
                            "instructions": "Steuert EGLO-Connect-Lampen und richtet die EGLO-BLE-Bridges ein "
                                            "(Tablets, Raspberry Pi). Lampen und Bridges per Name, Teilname oder ID. "
                                            "Beginne mit eglo_overview."}
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            try:
                out = await self.call_tool(params.get("name", ""), params.get("arguments") or {})
                result = {"content": [{"type": "text", "text": json.dumps(out, ensure_ascii=False, indent=2)}]}
            except Exception as err:  # noqa: BLE001
                result = {"isError": True, "content": [{"type": "text", "text": f"Fehler: {err}"}]}
        else:
            return self._error(rid, -32601, f"Unbekannte Methode {method}")
        return {"jsonrpc": "2.0", "id": rid, "result": result}

    # ================================================================= tools

    def _mqtt(self):
        m = self.mqtt_provider()
        if m is None or not m.connected:
            raise RuntimeError("Bridge ist nicht mit MQTT verbunden")
        return m

    @staticmethod
    def _lamps(m) -> list:
        return [d for d in m.devices if d.is_light]

    def _resolve_lamp(self, m, q: str):
        allx = self._lamps(m)
        if q.strip().isdigit():
            for d in allx:
                if d.meshId == int(q):
                    return d
        n = _norm(q)
        exact = [d for d in allx if _norm(d.name) == n]
        if len(exact) == 1:
            return exact[0]
        part = [d for d in allx if n in _norm(d.name)]
        if len(part) == 1:
            return part[0]
        known = ", ".join(f"{d.name.strip()} ({d.meshId})" for d in allx)
        raise ValueError(f"{'Lampe „' + q + '“ nicht gefunden' if not part else '„' + q + '“ ist nicht eindeutig'}. Bekannt: {known}")

    @staticmethod
    def _bridges(t: dict) -> list[dict]:
        ids = sorted({k[len(B) + 8:].split("/")[0] for k in t if k.startswith(f"{B}/bridge/")})
        out = []
        for bid in ids:
            try:
                name = json.loads(t.get(f"{B}/bridge/{bid}/info", "{}")).get("name") or bid
            except ValueError:
                name = bid
            st = t.get(f"{B}/bridge/{bid}/status")
            out.append({"id": bid, "name": name, "online": None if st is None else st == "online"})
        return out

    def _resolve_bridge(self, m, t: dict, q: str | None) -> dict:
        allb = self._bridges(t)
        if not q:
            return next((b for b in allb if b["id"] == m.bridge_id),
                        {"id": m.bridge_id, "name": m.bridge_name, "online": True})
        n = _norm(q)
        for b in allb:
            if b["id"] == q or _norm(b["name"]) == n:
                return b
        part = [b for b in allb if n in _norm(b["name"]) or n.replace(" ", "_") in b["id"]]
        if len(part) == 1:
            return part[0]
        raise ValueError(f"Bridge „{q}“ nicht eindeutig/nicht gefunden. Bekannt: "
                         + ", ".join(f"{b['name']} ({b['id']})" for b in allb))

    @staticmethod
    def _lamp_view(t: dict, d, names: dict) -> dict:
        mid = d.meshId
        o: dict = {"id": mid, "name": d.name.strip()}
        av = t.get(f"{B}/light/{mid}/availability")
        o["available"] = None if av is None else av == "online"
        try:
            st = json.loads(t[f"{B}/light/{mid}/state"])
        except (KeyError, ValueError):
            st = None
        if st:
            o["on"] = st.get("state") == "ON"
            if "brightness" in st:
                o["brightness_pct"] = round(st["brightness"] / 2.55)
            o["color_mode"] = st.get("color_mode")
            if st.get("color_mode") == "color_temp" and "color_temp" in st:
                o["color_temp_kelvin"] = st["color_temp"]
            c = st.get("color")
            if st.get("color_mode") == "rgb" and c:
                o["rgb"] = "#{:02X}{:02X}{:02X}".format(c.get("r", 0), c.get("g", 0), c.get("b", 0))
            if st.get("effect") and st.get("effect") != "Kein Effekt":
                o["effect"] = st["effect"]
        best, best_score = None, 0
        prefix = f"{B}/light/{mid}/link/"
        for k, v in t.items():
            if not k.startswith(prefix):
                continue
            bid = k[len(prefix):]
            try:
                sc = int(v)
            except ValueError:
                continue
            if sc <= 0 or t.get(f"{B}/bridge/{bid}/status") == "offline":
                continue
            if sc > best_score or (sc == best_score and best is not None and bid < best):
                best, best_score = bid, sc
        o["controlled_by"] = names.get(best, best) if best else None
        return o

    @staticmethod
    async def _wait_for(seconds: float, cond) -> bool:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if cond():
                return True
            await asyncio.sleep(0.15)
        return cond()

    async def call_tool(self, name: str, a: dict) -> Any:  # noqa: C901 - one branch per tool
        m = self._mqtt()
        t = m.topics()
        names = {b["id"]: b["name"] for b in self._bridges(t)}

        if name == "eglo_overview":
            return {"this_bridge": m.bridge_name,
                    "bridges": [{**b, "status": t.get(f"{B}/bridge/{b['id']}/state")} for b in self._bridges(t)],
                    "lamps": [self._lamp_view(t, d, names) for d in self._lamps(m)]}

        if name == "eglo_set_light":
            qs = a.get("lamps") or []
            if not qs:
                raise ValueError("lamps fehlt")
            targets = self._lamps(m) if len(qs) == 1 and _norm(qs[0]) in ("alle", "all") \
                else [self._resolve_lamp(m, q) for q in qs]
            if a.get("on") is False:
                cmd: dict = {"state": "OFF"}
            else:
                cmd = {"state": "ON"}
                if "brightness_pct" in a:
                    cmd["brightness"] = round(max(1.0, min(100.0, float(a["brightness_pct"]))) * 2.55)
                if "color_temp_kelvin" in a:
                    cmd["color_temp"] = max(2700, min(6500, int(a["color_temp_kelvin"])))
                if "rgb" in a:
                    h = str(a["rgb"]).lstrip("#")
                    if len(h) != 6:
                        raise ValueError("rgb als #RRGGBB angeben")
                    cmd["color"] = {"r": int(h[0:2], 16), "g": int(h[2:4], 16), "b": int(h[4:6], 16)}
                if "effect" in a:
                    cmd["effect"] = a["effect"]
            before = {d.meshId: t.get(f"{B}/light/{d.meshId}/state") for d in targets}
            for d in targets:
                m.send(f"{B}/light/{d.meshId}/set", json.dumps(cmd))
            await self._wait_for(4, lambda: all(m.topic_cache.get(f"{B}/light/{d.meshId}/state") != before[d.meshId]
                                                for d in targets))
            now = m.topics()
            return {"sent": cmd, "lamps": [self._lamp_view(now, d, names) for d in targets]}

        if name == "eglo_bridge_settings":
            b = self._resolve_bridge(m, t, a.get("bridge"))
            try:
                cfg = json.loads(t[f"{B}/bridge/{b['id']}/config"])
            except (KeyError, ValueError):
                cfg = {"note": "Diese Bridge meldet keine Einstellungen (Version zu alt)."}
            cfg.update({"online": b["online"], "status": t.get(f"{B}/bridge/{b['id']}/state")})
            return cfg

        if name == "eglo_configure_bridge":
            b = self._resolve_bridge(m, t, a.get("bridge"))
            s = dict(a.get("settings") or {})
            if "screen_on" in s:
                m.send(f"{B}/bridge/{b['id']}/screen/set", "ON" if s.pop("screen_on") else "OFF")
            topic = f"{B}/bridge/{b['id']}/config"
            before = t.get(topic)
            if s:
                m.send(f"{topic}/set", json.dumps(s))
            slow = any(k in s for k in ("name", "restart", "reimport", "mqtt_host", "mqtt_port", "mqtt_user", "mqtt_password"))
            ok = not s or await self._wait_for(25 if slow else 5, lambda: m.topic_cache.get(topic) != before)
            try:
                after = json.loads(m.topic_cache.get(topic, "{}")).get("settings")
            except ValueError:
                after = None
            return {"bridge": b["name"], "confirmed": ok, "sent": list(s), "settings": after}

        if name == "eglo_bridge_command":
            b = self._resolve_bridge(m, t, a.get("bridge"))
            c = a.get("command", "")
            if c not in COMMANDS:
                raise ValueError(f"Unbekannter Befehl {c}")
            if c == "install_update":
                m.send(f"{B}/bridge/{b['id']}/update/install", "install")
            else:
                m.send(f"{B}/bridge/{b['id']}/cmd", "reload" if c == "reload_kiosk" else c)
            return {"bridge": b["name"], "command": c, "sent": True}

        if name == "eglo_assign":
            b = self._resolve_bridge(m, t, a.get("bridge"))
            on = "ON" if a.get("responsible") else "OFF"
            lq = str(a.get("lamp", ""))
            if _norm(lq) in ("alle", "all"):
                m.send(f"{B}/bridge/{b['id']}/lamps_all/set", on)
            else:
                m.send(f"{B}/bridge/{b['id']}/lamp/{self._resolve_lamp(m, lq).meshId}/set", on)
            await asyncio.sleep(1.5)
            now = m.topics()
            return {"bridge": b["name"], "all_lamps": now.get(f"{B}/bridge/{b['id']}/lamps_all"),
                    "lamps": {d.name.strip(): now[f"{B}/bridge/{b['id']}/lamp/{d.meshId}"]
                              for d in self._lamps(m) if f"{B}/bridge/{b['id']}/lamp/{d.meshId}" in now}}

        if name == "eglo_effects":
            if "music_source" in a:
                m.send(f"{B}/music/source/set", self._resolve_bridge(m, t, a["music_source"])["name"])
            if "music_sensitivity" in a:
                m.send(f"{B}/music_sensitivity/set", str(int(a["music_sensitivity"])))
            for k in ("effect_speed", "fire_speed", "fire_strength", "fire_brightness"):
                if k in a:
                    m.send(f"{B}/{k}/set", str(int(a[k])))
            if "music_mode" in a:
                m.send(f"{B}/music/set", "ON" if a["music_mode"] else "OFF")
            if a:
                await asyncio.sleep(1.2)
            now = m.topics()
            keys = {"music_mode": f"{B}/music", "music_source": f"{B}/music/source_name",
                    "music_sensitivity": f"{B}/music_sensitivity", "effect_speed": f"{B}/effect_speed",
                    "fire_speed": f"{B}/fire_speed", "fire_strength": f"{B}/fire_strength",
                    "fire_brightness": f"{B}/fire_brightness"}
            return {k: now[v] for k, v in keys.items() if v in now}

        if name == "eglo_log":
            n = max(1, min(400, int(a.get("lines") or 50)))
            f = str(a.get("contains") or "").lower()
            return [x for x in log.snapshot() if not f or f in x.lower()][-n:]

        raise ValueError(f"Unbekanntes Werkzeug {name}")
