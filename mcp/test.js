// End-to-end test: local MQTT broker + simulated bridge + the MCP server over stdio.
import { createServer } from "node:net";
import aedesPkg from "aedes";
import mqtt from "mqtt";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import assert from "node:assert/strict";

const PORT = 18830 + Math.floor(Math.random() * 1000);
const broker = aedesPkg.createBroker ? await aedesPkg.createBroker() : aedesPkg();
const tcp = createServer(broker.handle);
await new Promise((r) => tcp.listen(PORT, r));

// ---- simulated tablet bridge
const B = "eglo_bridge";
const bid = "sm_p600_8eda";
const fake = mqtt.connect(`mqtt://127.0.0.1:${PORT}`);
await new Promise((r) => fake.on("connect", r));
const pubR = (t, v) => fake.publishAsync(t, typeof v === "string" ? v : JSON.stringify(v), { retain: true, qos: 1 });
const lampsDef = [
  { id: 11, name: "Wohnzimmer Decke", modes: ["color_temp", "rgb"] },
  { id: 12, name: "Küche", modes: ["color_temp"] },
];
for (const l of lampsDef) {
  await pubR(`homeassistant/light/eglo_bridge_${l.id}/config`, {
    unique_id: `eglo_bridge_${l.id}`, supported_color_modes: l.modes,
    device: { name: l.name, model: "EGLO_RGB_CCT", manufacturer: "EGLO", sw_version: "1.0" },
  });
  await pubR(`${B}/light/${l.id}/state`, { state: "OFF", color_mode: "color_temp", brightness: 128, color_temp: 3000 });
  await pubR(`${B}/light/${l.id}/availability`, "online");
  await pubR(`${B}/light/${l.id}/link/${bid}`, "80");
}
await pubR(`${B}/bridge/${bid}/status`, "online");
await pubR(`${B}/bridge/${bid}/info`, { name: "SM-P600 8EDA", version: "Build 40", mic: true, platform: "android" });
await pubR(`${B}/bridge/${bid}/state`, "verbunden (2 Lampen)");
await pubR(`${B}/bridge/${bid}/screen`, "ON");
await pubR(`${B}/bridge/${bid}/brightness`, "60");
await pubR(`${B}/bridge/${bid}/battery`, "87");
await pubR(`${B}/music`, "OFF");
const bcfg = { bridge_id: bid, name: "SM-P600 8EDA", platform: "android",
  settings: { kiosk_url: "http://ha/", media_volume: 30 }, device: { model: "SM-P600", android: "11", media_volume: 30 } };
await pubR(`${B}/bridge/${bid}/config`, bcfg);
const received = [];
fake.subscribe(`${B}/#`);
fake.on("message", (t, buf) => {
  const v = buf.toString();
  if (t.endsWith("/set") || t.endsWith("/cmd")) received.push([t, v]);
  const m = t.match(/^eglo_bridge\/light\/(\d+)\/set$/);
  if (m) {
    const c = JSON.parse(v);
    const st = { state: c.state, color_mode: c.color ? "rgb" : "color_temp", brightness: c.brightness ?? 255 };
    if (c.color) st.color = c.color; else st.color_temp = c.color_temp ?? 3000;
    pubR(`${B}/light/${m[1]}/state`, st);
    fake.publish(`${B}/bridge/${bid}/log`, `Befehl für ${m[1]}: ${v}`);
  }
  if (t === `${B}/bridge/${bid}/screen/set`) pubR(`${B}/bridge/${bid}/screen`, v);
  if (t === `${B}/bridge/${bid}/debug/set`) pubR(`${B}/bridge/${bid}/debug`, v);
  if (t === `${B}/bridge/${bid}/config/set`) {
    const o = JSON.parse(v);
    if (o.name) { bcfg.name = o.name; pubR(`${B}/bridge/${bid}/info`, { name: o.name, platform: "android" }); }
    if (o.media_volume !== undefined) bcfg.device.media_volume = o.media_volume;
    if (o.kiosk_url) bcfg.settings.kiosk_url = o.kiosk_url;
    fake.publish(`${B}/bridge/${bid}/log`, `Einstellungen geändert: ${Object.keys(o).join(", ")}`);
    pubR(`${B}/bridge/${bid}/config`, bcfg);
  }
});

// ---- MCP server
const client = new Client({ name: "test", version: "1" });
await client.connect(new StdioClientTransport({
  command: process.execPath, args: [process.env.SERVER || "server.js"],
  env: { ...process.env, EGLO_MQTT_URL: `mqtt://127.0.0.1:${PORT}` }, stderr: "inherit",
}));
const call = async (name, args = {}) => {
  const r = await client.callTool({ name, arguments: args });
  const t = r.content[0].text;
  if (r.isError) return { error: t };
  return JSON.parse(t);
};

const tools = (await client.listTools()).tools.map((t) => t.name);
console.log("tools:", tools.join(", "));
assert.ok(tools.includes("eglo_set_light"));

const ov = await call("eglo_overview");
assert.equal(ov.lamps.length, 2);
assert.equal(ov.bridges[0].name, "SM-P600 8EDA");
assert.equal(ov.lamps.find((l) => l.id === 12).controlled_by, "SM-P600 8EDA");

const r1 = await call("eglo_set_light", { lamps: ["wohnzimmer"], rgb: "#ff8800", brightness_pct: 50 });
assert.equal(r1.results[0].confirmed, true);
assert.deepEqual(r1.results[0].rgb, [255, 136, 0]);
assert.equal(r1.results[0].brightness_pct, 50);

const r2 = await call("eglo_set_light", { lamps: ["alle"], on: false });
assert.equal(r2.results.length, 2);
assert.ok(r2.results.every((x) => x.on === false));

const r3 = await call("eglo_set_light", { lamps: ["kueche"], color_temp_kelvin: 4000 });
assert.equal(r3.results[0].color_temp_kelvin, 4000);

const bad = await call("eglo_set_light", { lamps: ["Garage"], on: true });
assert.match(bad.error, /nicht gefunden/);

const k = await call("eglo_kiosk", { bridge: "8eda", screen_on: false });
assert.equal(k.on, false);

const d = await call("eglo_bridge_debug", { bridge: "SM-P600 8EDA", on: true });
assert.equal(d.debug_log, true);

await call("eglo_bridge_command", { bridge: "8eda", command: "reconnect" });
assert.ok(received.some(([t, v]) => t === `${B}/bridge/${bid}/cmd` && v === "reconnect"));

const log = await call("eglo_bridge_log", { lines: 10 });
assert.ok(log["SM-P600 8EDA"].length >= 3);

await call("eglo_effects", { music_mode: true, fire_speed: 40 });
assert.ok(received.some(([t, v]) => t === `${B}/music/set` && v === "ON"));

const g = await call("eglo_get_lamp", { lamp: "12" });
assert.equal(g.link_scores["SM-P600 8EDA"], 80);

const lb = await call("eglo_list_bridges");
assert.equal(lb[0].device.model, "SM-P600");
const c1 = await call("eglo_configure_bridge", { bridge: "8eda", name: "Tablet Flur", media_volume: 55, kiosk_pin: "4711" });
assert.equal(c1.confirmed, true);
assert.equal(c1.sent.kiosk_pin, "***");
const sentCfg = received.filter(([t]) => t.endsWith("/config/set")).map(([, v]) => JSON.parse(v));
assert.deepEqual(sentCfg[0], { name: "Tablet Flur", media_volume: 55, kiosk_pin: "4711" });
const lb2 = await call("eglo_list_bridges");
assert.equal(lb2[0].name, "Tablet Flur");
assert.equal(lb2[0].device.media_volume, 55);
const c2 = await call("eglo_set_light", { lamps: ["küche"], on: true });   // bridge renamed, lamps still fine
assert.equal(c2.results[0].controlled_by, "Tablet Flur");

console.log("ALLE TESTS OK");
await client.close();
fake.end(true);
tcp.close();
broker.close();
process.exit(0);
