#!/usr/bin/env node
/**
 * MCP server for the EGLO BLE Bridge.
 *
 * Talks to all bridges (Android tablets, Raspberry Pi) through the same MQTT broker they use.
 * It only reads the retained topics the bridges publish and sends the same commands Home Assistant
 * would send – the bridges need no changes.
 *
 * Configuration (environment):
 *   EGLO_MQTT_URL       mqtt://192.168.178.120:1883
 *   EGLO_MQTT_USER      MQTT user (optional)
 *   EGLO_MQTT_PASSWORD  MQTT password (optional)
 *   EGLO_LOG_LINES      log lines kept per bridge (default 300)
 */
import mqtt from "mqtt";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const BASE = "eglo_bridge";
const URL = process.env.EGLO_MQTT_URL || "mqtt://192.168.178.120:1883";
const LOG_LINES = parseInt(process.env.EGLO_LOG_LINES || "300", 10);
const VERSION = "1.0.0";

const EFFECTS = ["Kein Effekt", "Farbwechsel", "Sequenz 2", "Sequenz 3", "Sequenz 4", "Sequenz 5",
  "Sequenz 6", "Sequenz 7", "Kerze", "Lagerfeuer", "Musik"];
const LAMP_SETTINGS = ["fire_speed", "fire_strength", "fire_brightness", "effect_speed"];
const MIN_K = 2700, MAX_K = 6500;

const err = (...a) => console.error("[eglo-mcp]", ...a);   // stdout belongs to MCP

// ===================================================================== state from MQTT

/** id -> lamp */
const lamps = new Map();
/** bridge id -> bridge */
const bridges = new Map();
const global = {};
/** last payload of every topic below eglo_bridge/ (except music frames) */
const raw = new Map();
/** waiters for state changes: [{test(topic,payload), resolve}] */
let waiters = [];

function lamp(id) {
  let l = lamps.get(id);
  if (!l) {
    l = { id, name: `Lampe ${id}`, links: {}, settings: {}, favorites: {} };
    lamps.set(id, l);
  }
  return l;
}

function bridge(bid) {
  let b = bridges.get(bid);
  if (!b) {
    b = { id: bid, name: bid, lamp_assignment: {}, log: [] };
    bridges.set(bid, b);
  }
  return b;
}

const json = (s) => { try { return JSON.parse(s); } catch { return undefined; } };
const onOff = (s) => s.toUpperCase() === "ON";
const num = (s) => { const n = parseFloat(s); return Number.isFinite(n) ? n : undefined; };

function onMessage(topic, buf) {
  const v = buf.toString();
  if (topic === `${BASE}/music/frame`) return;

  if (topic.startsWith("homeassistant/light/")) {
    // homeassistant/light/eglo_bridge_<id>/config – names and capabilities of the lamps
    const m = topic.match(/^homeassistant\/light\/eglo_bridge_(\d+)\/config$/);
    if (!m) return;
    const cfg = json(v);
    if (!cfg) return;
    const l = lamp(parseInt(m[1], 10));
    l.name = (cfg.device?.name || l.name).trim();
    l.model = cfg.device?.model;
    l.vendor = cfg.device?.manufacturer;
    l.firmware = cfg.device?.sw_version;
    l.color_modes = cfg.supported_color_modes;
    l.effects = cfg.effect_list;
    l.discovered = true;
    return;
  }

  raw.set(topic, v);
  const p = topic.split("/");

  if (p[1] === "light" && p.length >= 4) {
    const id = parseInt(p[2], 10);
    if (!Number.isFinite(id)) return;
    const l = lamp(id);
    const k = p[3];
    if (p.length === 4) {
      if (k === "state") l.state = json(v);
      else if (k === "availability") l.available = v === "online";
      else if (k === "music") l.music_mode_member = onOff(v);
      else if (k === "fav1" || k === "fav2") l.favorites[k] = v ? json(v) : null;
      else if (LAMP_SETTINGS.includes(k)) l.settings[k] = num(v);
    } else if (p.length === 5 && k === "link") {
      l.links[p[4]] = parseInt(v, 10) || 0;
    }
  } else if (p[1] === "bridge" && p.length >= 4) {
    const b = bridge(p[2]);
    const k = p.slice(3).join("/");
    switch (k) {
      case "status": b.online = v === "online"; break;
      case "info": {
        const o = json(v) || {};
        b.name = o.name || b.name; b.version = o.version; b.platform = o.platform; b.has_mic = o.mic;
        break;
      }
      case "state": b.state = v; break;
      case "log":
        if (v) {
          b.log.push(`${new Date().toISOString().slice(11, 19)} ${v}`);
          if (b.log.length > LOG_LINES) b.log.splice(0, b.log.length - LOG_LINES);
        }
        break;
      case "debug": b.debug_log = onOff(v); break;
      case "mic": b.mic = v; break;
      case "screen": b.screen_on = onOff(v); break;
      case "brightness": b.screen_brightness = num(v); break;
      case "screen_timeout": b.screen_timeout_min = num(v); break;
      case "url": b.kiosk_url = v; break;
      case "battery": b.battery = num(v); break;
      case "charging": b.charging = onOff(v); break;
      case "kiosk": b.kiosk_visible = onOff(v); break;
      case "update": b.update = json(v); break;
      case "lamps_all": b.responsible_for_all = onOff(v); break;
      default: {
        const m = k.match(/^lamp\/(\d+)$/);
        if (m) b.lamp_assignment[m[1]] = onOff(v);
      }
    }
  } else {
    const k = p.slice(1).join("/");
    const map = {
      "music": ["music_mode", onOff], "music/source": ["music_source_id", (x) => x],
      "music/source_name": ["music_source", (x) => x], "music_sensitivity": ["music_sensitivity", num],
      "effect_speed": ["effect_speed", num], "fire_speed": ["fire_speed", num],
      "fire_strength": ["fire_strength", num], "fire_brightness": ["fire_brightness", num],
    };
    if (map[k]) global[map[k][0]] = map[k][1](v);
  }

  if (waiters.length) {
    waiters = waiters.filter((w) => {
      if (w.test(topic, v)) { w.resolve(true); return false; }
      return true;
    });
  }
}

function waitFor(test, ms) {
  return new Promise((resolve) => {
    const w = { test, resolve };
    waiters.push(w);
    setTimeout(() => { waiters = waiters.filter((x) => x !== w); resolve(false); }, ms);
  });
}

// ===================================================================== MQTT connection

let client;
let ready;           // resolves once connected and retained messages had a moment to arrive
let lastError = "";

function connect() {
  const opts = {
    clientId: `eglo-mcp-${Math.random().toString(16).slice(2, 8)}`,
    username: process.env.EGLO_MQTT_USER || undefined,
    password: process.env.EGLO_MQTT_PASSWORD || undefined,
    reconnectPeriod: 5000,
    connectTimeout: 10000,
    clean: true,
  };
  client = mqtt.connect(URL, opts);
  ready = new Promise((resolve) => {
    client.once("connect", () => {
      client.subscribe([`${BASE}/#`, "homeassistant/light/+/config"], { qos: 0 }, () => {
        setTimeout(resolve, 1500);
      });
    });
  });
  client.on("connect", () => { lastError = ""; err("verbunden mit", URL); });
  client.on("reconnect", () => client.subscribe([`${BASE}/#`, "homeassistant/light/+/config"]));
  client.on("error", (e) => { lastError = e.message; err("MQTT-Fehler:", e.message); });
  client.on("message", onMessage);
}

async function ensureReady() {
  const ok = await Promise.race([ready.then(() => true), new Promise((r) => setTimeout(() => r(false), 8000))]);
  if (!ok || !client.connected) {
    throw new Error(`Keine Verbindung zum MQTT-Broker ${URL}${lastError ? ` (${lastError})` : ""}. ` +
      "Prüfe EGLO_MQTT_URL / EGLO_MQTT_USER / EGLO_MQTT_PASSWORD.");
  }
}

function publish(topic, payload, retain = false) {
  return new Promise((resolve, reject) =>
    client.publish(topic, String(payload), { qos: 1, retain }, (e) => (e ? reject(e) : resolve())));
}

// ===================================================================== helpers

const norm = (s) => s.toLowerCase()
  .replace(/ä/g, "ae").replace(/ö/g, "oe").replace(/ü/g, "ue").replace(/ß/g, "ss")
  .normalize("NFKD").replace(/[̀-ͯ]/g, "")
  .replace(/[^a-z0-9]+/g, " ").trim();

function knownLamps() {
  return [...lamps.values()].filter((l) => l.discovered || l.state || l.available !== undefined)
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** Lamp by mesh id, exact name or unique part of the name. */
function resolveLamp(q) {
  const s = String(q).trim();
  if (/^\d+$/.test(s) && lamps.has(parseInt(s, 10))) return lamps.get(parseInt(s, 10));
  const all = knownLamps();
  const n = norm(s);
  const exact = all.filter((l) => norm(l.name) === n);
  if (exact.length === 1) return exact[0];
  const part = all.filter((l) => norm(l.name).includes(n));
  if (part.length === 1) return part[0];
  const names = (part.length ? part : all).map((l) => `${l.name} (${l.id})`).join(", ");
  throw new Error(part.length ? `„${s}“ ist nicht eindeutig: ${names}` : `Lampe „${s}“ nicht gefunden. Bekannt: ${names}`);
}

function resolveLamps(list) {
  if (list.length === 1 && ["all", "alle"].includes(String(list[0]).toLowerCase())) {
    return knownLamps().filter((l) => l.discovered);
  }
  return list.map(resolveLamp);
}

function resolveBridge(q) {
  const all = [...bridges.values()];
  if (q === undefined || q === null || q === "") {
    const online = all.filter((b) => b.online);
    if (online.length === 1) return online[0];
    throw new Error(`Bitte Bridge angeben: ${all.map((b) => `${b.name} (${b.id})`).join(", ") || "keine bekannt"}`);
  }
  const n = norm(String(q));
  const hit = all.filter((b) => b.id === q || norm(b.name) === n);
  if (hit.length === 1) return hit[0];
  const part = all.filter((b) => norm(b.name).includes(n) || b.id.includes(n.replace(/ /g, "_")));
  if (part.length === 1) return part[0];
  throw new Error(`Bridge „${q}“ nicht eindeutig/nicht gefunden. Bekannt: ${all.map((b) => `${b.name} (${b.id})`).join(", ")}`);
}

/** Same rule as the bridges: best link score among online bridges, ties -> smaller id. */
function ownerOf(l) {
  let best = null, bestScore = 0;
  for (const [bid, s] of Object.entries(l.links)) {
    if (s <= 0 || bridges.get(bid)?.online === false) continue;
    if (s > bestScore || (s === bestScore && best !== null && bid < best)) { best = bid; bestScore = s; }
  }
  return best;
}

function lampView(l, withDetails = false) {
  const st = l.state || {};
  const owner = ownerOf(l);
  const o = {
    id: l.id,
    name: l.name,
    available: l.available ?? null,
    on: st.state ? st.state === "ON" : null,
    brightness_pct: st.brightness !== undefined ? Math.round(st.brightness / 2.55) : null,
    color_mode: st.color_mode ?? null,
  };
  if (st.color_mode === "color_temp") o.color_temp_kelvin = st.color_temp;
  if (st.color_mode === "rgb" && st.color) o.rgb = [st.color.r, st.color.g, st.color.b];
  if (st.effect && st.effect !== "Kein Effekt") o.effect = st.effect;
  o.controlled_by = owner ? (bridges.get(owner)?.name || owner) : null;
  if (withDetails) {
    o.model = l.model; o.vendor = l.vendor; o.firmware = l.firmware;
    o.color_modes = l.color_modes; o.effects = l.effects;
    o.link_scores = Object.fromEntries(Object.entries(l.links)
      .map(([bid, s]) => [bridges.get(bid)?.name || bid, s]));
    o.music_mode_member = l.music_mode_member;
    o.settings = l.settings;
    o.favorites = l.favorites;
    o.raw_state = l.state;
  }
  return o;
}

function bridgeView(b, withDetails = false) {
  const o = {
    id: b.id, name: b.name, online: b.online ?? null, platform: b.platform, version: b.version,
    status: b.state,
  };
  const owned = knownLamps().filter((l) => ownerOf(l) === b.id).map((l) => l.name);
  o.controls_lamps = owned;
  if (b.battery !== undefined) { o.battery_pct = b.battery; o.charging = b.charging; }
  if (b.screen_on !== undefined) {
    o.screen = { on: b.screen_on, brightness_pct: b.screen_brightness, timeout_min: b.screen_timeout_min,
      kiosk_visible: b.kiosk_visible, url: b.kiosk_url };
  }
  if (withDetails) {
    o.microphone = b.mic; o.debug_log = b.debug_log; o.update = b.update;
    o.responsible_for_all = b.responsible_for_all;
    o.responsible_for = Object.entries(b.lamp_assignment)
      .filter(([, on]) => on).map(([id]) => lamps.get(+id)?.name || id);
    o.log_lines_buffered = b.log.length;
  }
  return o;
}

const text = (o) => ({ content: [{ type: "text", text: typeof o === "string" ? o : JSON.stringify(o, null, 2) }] });

async function run(fn) {
  try {
    await ensureReady();
    return text(await fn());
  } catch (e) {
    return { content: [{ type: "text", text: `Fehler: ${e.message}` }], isError: true };
  }
}

function hexToRgb(h) {
  const m = String(h).replace("#", "").match(/^([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i);
  if (!m) throw new Error(`Ungültige Farbe „${h}“ (erwartet #RRGGBB)`);
  return m.slice(1).map((x) => parseInt(x, 16));
}

// ===================================================================== MCP server

const server = new McpServer({ name: "eglo-bridge", version: VERSION }, {
  instructions:
    "Steuert EGLO-Connect/AwoX-Bluetooth-Mesh-Lampen über die EGLO BLE Bridge (Android-Tablets und Raspberry Pi) per MQTT. " +
    "Lampen werden über Name (auch Teilname) oder Mesh-ID angesprochen. Mehrere Bridges teilen sich die Lampen: " +
    "Die Bridge mit dem besten Empfang (link score) steuert eine Lampe. Beginne mit eglo_overview.",
});

server.registerTool("eglo_overview", {
  title: "Überblick",
  description: "Überblick über alle Bridges (Tablets/Pi) und Lampen: online, an/aus, Helligkeit, Farbe, welche Bridge steuert. Plus Musikmodus/Effekt-Einstellungen.",
  inputSchema: {},
  annotations: { readOnlyHint: true },
}, () => run(async () => ({
  broker: URL,
  bridges: [...bridges.values()].map((b) => bridgeView(b)),
  lamps: knownLamps().map((l) => lampView(l)),
  music_and_effects: global,
})));

server.registerTool("eglo_get_lamp", {
  title: "Lampe im Detail",
  description: "Details einer Lampe: Zustand, Modell, Fähigkeiten, Empfang (link score) je Bridge, Favoriten, Effekt-Einstellungen.",
  inputSchema: { lamp: z.string().describe("Name, Teilname oder Mesh-ID") },
  annotations: { readOnlyHint: true },
}, ({ lamp: q }) => run(async () => lampView(resolveLamp(q), true)));

server.registerTool("eglo_set_light", {
  title: "Lampe(n) schalten",
  description:
    "Schaltet eine oder mehrere Lampen: an/aus, Helligkeit, Farbtemperatur, RGB-Farbe oder Effekt. " +
    `Effekte: ${EFFECTS.join(", ")}. Farbtemperatur ${MIN_K}–${MAX_K} K. Mit lamps=["alle"] werden alle Lampen geschaltet. ` +
    "Wartet kurz auf die Rückmeldung der Bridge und gibt den neuen Zustand zurück.",
  inputSchema: {
    lamps: z.array(z.string()).min(1).describe("Namen/Teilnamen/Mesh-IDs, oder [\"alle\"]"),
    on: z.boolean().optional().describe("true = an, false = aus"),
    brightness_pct: z.number().min(1).max(100).optional().describe("Helligkeit in %"),
    color_temp_kelvin: z.number().int().min(MIN_K).max(MAX_K).optional().describe("Weißton in Kelvin (2700 warm … 6500 kalt)"),
    rgb: z.union([z.array(z.number().int().min(0).max(255)).length(3), z.string()]).optional()
      .describe("Farbe als [r,g,b] oder \"#RRGGBB\""),
    effect: z.enum(EFFECTS).optional().describe("Effekt"),
  },
}, (a) => run(async () => {
  const targets = resolveLamps(a.lamps);
  if (!targets.length) throw new Error("Keine Lampen gefunden.");
  const cmd = {};
  if (a.rgb !== undefined) {
    const [r, g, b] = typeof a.rgb === "string" ? hexToRgb(a.rgb) : a.rgb;
    cmd.color = { r, g, b };
  }
  if (a.color_temp_kelvin !== undefined) cmd.color_temp = a.color_temp_kelvin;
  if (a.brightness_pct !== undefined) cmd.brightness = Math.round(a.brightness_pct * 2.55);
  if (a.effect !== undefined) cmd.effect = a.effect;
  if (a.on === false) {
    if (Object.keys(cmd).length) throw new Error("Ausschalten lässt sich nicht mit Farbe/Helligkeit/Effekt kombinieren.");
    cmd.state = "OFF";
  } else cmd.state = "ON";

  const results = await Promise.all(targets.map(async (l) => {
    const warn = [];
    if (l.available === false) warn.push("Lampe ist nicht erreichbar (keine Bridge hat Verbindung)");
    if (cmd.color && l.color_modes && !l.color_modes.includes("rgb")) warn.push("Lampe kann keine RGB-Farben");
    if (cmd.color_temp && l.color_modes && !l.color_modes.includes("color_temp")) warn.push("Lampe hat keine Farbtemperatur");
    const before = raw.get(`${BASE}/light/${l.id}/state`);
    const wait = waitFor((t, v) => t === `${BASE}/light/${l.id}/state` && v !== before, 4000);
    await publish(`${BASE}/light/${l.id}/set`, JSON.stringify(cmd));
    const confirmed = await wait;
    const o = lampView(l);
    o.confirmed = confirmed;
    if (warn.length) o.warnings = warn;
    return o;
  }));
  return { sent: cmd, results };
}));

server.registerTool("eglo_favorite", {
  title: "Favorit abrufen/speichern",
  description: "Ruft Favorit 1/2 einer Lampe ab (recall) oder speichert die aktuelle Einstellung als Favorit (save).",
  inputSchema: {
    lamp: z.string(),
    slot: z.union([z.literal(1), z.literal(2)]),
    action: z.enum(["recall", "save"]),
  },
}, ({ lamp: q, slot, action }) => run(async () => {
  const l = resolveLamp(q);
  await publish(`${BASE}/light/${l.id}/fav/set`, `${action}${slot}`);
  await new Promise((r) => setTimeout(r, 1500));
  return { lamp: l.name, action, slot, favorite: l.favorites[`fav${slot}`] ?? null, state: lampView(l) };
}));

server.registerTool("eglo_lamp_setting", {
  title: "Effekt-Einstellung einer Lampe",
  description: "Setzt eine Effekt-Einstellung einer einzelnen Lampe (0–100 %): fire_speed (Lagerfeuer-Tempo), fire_strength (Lagerfeuer-Stärke), fire_brightness (Lagerfeuer-Helligkeit, min 5), effect_speed (Effekt-Tempo).",
  inputSchema: {
    lamp: z.string(),
    setting: z.enum(LAMP_SETTINGS),
    value: z.number().min(0).max(100),
  },
}, ({ lamp: q, setting, value }) => run(async () => {
  const l = resolveLamp(q);
  await publish(`${BASE}/light/${l.id}/${setting}/set`, Math.round(value));
  await waitFor((t) => t === `${BASE}/light/${l.id}/${setting}`, 2000);
  return { lamp: l.name, settings: l.settings };
}));

server.registerTool("eglo_effects", {
  title: "Globale Effekt- und Musik-Einstellungen",
  description:
    "Gemeinsame Einstellungen aller Bridges: Musikmodus an/aus, Musikquelle (Tablet mit Mikrofon), Musik-Empfindlichkeit (50–200 %), " +
    "Effekt-Tempo und Lagerfeuer-Tempo/-Stärke/-Helligkeit (0–100 %, gelten für alle Lampen). " +
    "Optional: einzelne Lampen am Musikmodus beteiligen/ausschließen. Ohne Parameter: aktuellen Stand lesen.",
  inputSchema: {
    music_mode: z.boolean().optional(),
    music_source: z.string().optional().describe("Name der Bridge, die per Mikrofon zuhört"),
    music_sensitivity: z.number().min(50).max(200).optional(),
    effect_speed: z.number().min(0).max(100).optional(),
    fire_speed: z.number().min(0).max(100).optional(),
    fire_strength: z.number().min(0).max(100).optional(),
    fire_brightness: z.number().min(5).max(100).optional(),
    music_lamps: z.array(z.object({ lamp: z.string(), member: z.boolean() })).optional()
      .describe("Lampen am Musikmodus beteiligen (member=true) oder nicht"),
  },
}, (a) => run(async () => {
  const jobs = [];
  if (a.music_source !== undefined) jobs.push(publish(`${BASE}/music/source/set`, resolveBridge(a.music_source).name));
  if (a.music_sensitivity !== undefined) jobs.push(publish(`${BASE}/music_sensitivity/set`, Math.round(a.music_sensitivity)));
  if (a.effect_speed !== undefined) jobs.push(publish(`${BASE}/effect_speed/set`, Math.round(a.effect_speed)));
  for (const k of ["fire_speed", "fire_strength", "fire_brightness"]) {
    if (a[k] !== undefined) jobs.push(publish(`${BASE}/${k}/set`, Math.round(a[k])));
  }
  for (const m of a.music_lamps || []) {
    jobs.push(publish(`${BASE}/light/${resolveLamp(m.lamp).id}/music/set`, m.member ? "ON" : "OFF"));
  }
  if (a.music_mode !== undefined) jobs.push(publish(`${BASE}/music/set`, a.music_mode ? "ON" : "OFF"));
  await Promise.all(jobs);
  if (jobs.length) await new Promise((r) => setTimeout(r, 1200));
  return {
    ...global,
    music_lamps: knownLamps().filter((l) => l.music_mode_member !== undefined)
      .map((l) => ({ lamp: l.name, member: l.music_mode_member })),
  };
}));

server.registerTool("eglo_list_bridges", {
  title: "Bridges",
  description: "Alle Bridges (Tablets/Pi) mit Status, Version, Akku, Bildschirm/Kiosk, Mikrofon, Update-Stand und Zuständigkeit.",
  inputSchema: {},
  annotations: { readOnlyHint: true },
}, () => run(async () => [...bridges.values()].map((b) => bridgeView(b, true))));

server.registerTool("eglo_bridge_log", {
  title: "Bridge-Log",
  description:
    "Letzte Log-Zeilen einer Bridge (oder aller). Das Log wird live per MQTT empfangen und erst ab Start dieses MCP-Servers gepuffert; " +
    "für mehr Details vorher eglo_bridge_debug einschalten.",
  inputSchema: {
    bridge: z.string().optional().describe("Name/ID; leer = alle"),
    lines: z.number().int().min(1).max(1000).optional().describe("Anzahl Zeilen (Standard 50)"),
    contains: z.string().optional().describe("nur Zeilen mit diesem Text"),
  },
  annotations: { readOnlyHint: true },
}, ({ bridge: q, lines = 50, contains }) => run(async () => {
  const list = q ? [resolveBridge(q)] : [...bridges.values()];
  const out = {};
  for (const b of list) {
    let l = b.log;
    if (contains) l = l.filter((x) => x.toLowerCase().includes(contains.toLowerCase()));
    out[b.name] = l.slice(-lines);
  }
  return out;
}));

server.registerTool("eglo_bridge_debug", {
  title: "Debug-Log an/aus",
  description: "Schaltet das ausführliche Debug-Log einer Bridge ein oder aus.",
  inputSchema: { bridge: z.string(), on: z.boolean() },
}, ({ bridge: q, on }) => run(async () => {
  const b = resolveBridge(q);
  await publish(`${BASE}/bridge/${b.id}/debug/set`, on ? "ON" : "OFF");
  await waitFor((t) => t === `${BASE}/bridge/${b.id}/debug`, 3000);
  return { bridge: b.name, debug_log: b.debug_log };
}));

server.registerTool("eglo_bridge_command", {
  title: "Bridge-Befehl",
  description:
    "Befehl an eine Bridge: reconnect (Bluetooth-Mesh neu verbinden), restart (Bridge-Dienst neu starten), " +
    "reimport (Lampen aus AwoX-Cloud neu importieren), check_update (nach App-Update suchen), " +
    "install_update (verfügbares APK-Update installieren, nur Tablet), reload_kiosk (Dashboard neu laden, nur Tablet).",
  inputSchema: {
    bridge: z.string(),
    command: z.enum(["reconnect", "restart", "reimport", "check_update", "install_update", "reload_kiosk"]),
  },
}, ({ bridge: q, command }) => run(async () => {
  const b = resolveBridge(q);
  if (b.online === false) throw new Error(`Bridge „${b.name}“ ist offline.`);
  if (command === "install_update") await publish(`${BASE}/bridge/${b.id}/update/install`, "install");
  else await publish(`${BASE}/bridge/${b.id}/cmd`, command === "reload_kiosk" ? "reload" : command);
  await new Promise((r) => setTimeout(r, 2500));
  return { bridge: b.name, command, sent: true, status: b.state, recent_log: b.log.slice(-5) };
}));

server.registerTool("eglo_kiosk", {
  title: "Tablet-Bildschirm / Kiosk",
  description: "Steuert Bildschirm und Kiosk-Dashboard einer Bridge: Bildschirm an/aus, Helligkeit (1–100 %), Bildschirm-aus-Zeit (0–120 min, 0 = nie), Dashboard-URL.",
  inputSchema: {
    bridge: z.string(),
    screen_on: z.boolean().optional(),
    brightness_pct: z.number().int().min(1).max(100).optional(),
    screen_timeout_min: z.number().int().min(0).max(120).optional(),
    url: z.string().url().optional(),
  },
}, (a) => run(async () => {
  const b = resolveBridge(a.bridge);
  const t = `${BASE}/bridge/${b.id}`;
  const jobs = [];
  if (a.screen_on !== undefined) jobs.push(publish(`${t}/screen/set`, a.screen_on ? "ON" : "OFF"));
  if (a.brightness_pct !== undefined) jobs.push(publish(`${t}/brightness/set`, a.brightness_pct));
  if (a.screen_timeout_min !== undefined) jobs.push(publish(`${t}/screen_timeout/set`, a.screen_timeout_min));
  if (a.url !== undefined) jobs.push(publish(`${t}/url/set`, a.url));
  await Promise.all(jobs);
  if (jobs.length) await new Promise((r) => setTimeout(r, 1500));
  return bridgeView(b).screen ?? { note: "Diese Bridge meldet keinen Bildschirm." };
}));

server.registerTool("eglo_assign", {
  title: "Zuständigkeit festlegen",
  description:
    "Legt fest, für welche Lampen eine Bridge zuständig ist (sie verbindet sich nur mit diesen). " +
    "lamp=\"alle\" schaltet „Zuständig für alle Lampen“.",
  inputSchema: {
    bridge: z.string(),
    lamp: z.string().describe("Lampe oder \"alle\""),
    responsible: z.boolean(),
  },
}, ({ bridge: q, lamp: lq, responsible }) => run(async () => {
  const b = resolveBridge(q);
  const t = `${BASE}/bridge/${b.id}`;
  if (["all", "alle"].includes(lq.toLowerCase())) await publish(`${t}/lamps_all/set`, responsible ? "ON" : "OFF");
  else await publish(`${t}/lamp/${resolveLamp(lq).id}/set`, responsible ? "ON" : "OFF");
  await new Promise((r) => setTimeout(r, 2000));
  return bridgeView(b, true);
}));

server.registerTool("eglo_mqtt_topics", {
  title: "MQTT-Rohdaten",
  description: "Diagnose: letzte Werte aller Topics unter eglo_bridge/ (optional gefiltert).",
  inputSchema: { filter: z.string().optional().describe("Teilstring des Topics") },
  annotations: { readOnlyHint: true },
}, ({ filter }) => run(async () => Object.fromEntries([...raw.entries()]
  .filter(([t]) => !filter || t.includes(filter)).sort(([a], [b]) => a.localeCompare(b)))));

// ===================================================================== start

connect();
await server.connect(new StdioServerTransport());
err(`eglo-bridge MCP ${VERSION} läuft (Broker ${URL})`);

const shutdown = () => { try { client?.end(true); } catch { /* ignore */ } process.exit(0); };
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
process.stdin.on("close", shutdown);
