#!/usr/bin/env node
// Forwards MCP (stdio, one JSON-RPC message per line) to the MCP endpoint of an EGLO bridge
// (http://<tablet-or-pi>:8765/mcp). The bridge does the work with its own MQTT connection, so
// this needs no credentials. With several addresses the next one is tried when one is unreachable.
import readline from "node:readline";

const urls = (process.env.EGLO_BRIDGE_URLS || "http://192.168.178.131:8765/mcp")
  .split(/[,\s]+/).filter(Boolean)
  .map((u) => (/^https?:\/\//.test(u) ? u : `http://${u}`))
  .map((u) => (/:\d+(\/|$)/.test(u.replace(/^https?:\/\//, "")) ? u : u.replace(/\/?$/, ":8765")))
  .map((u) => (u.endsWith("/mcp") ? u : u.replace(/\/?$/, "/mcp")));
let current = 0;

const log = (...a) => console.error("[eglo-proxy]", ...a);
const write = (o) => process.stdout.write(JSON.stringify(o) + "\n");

async function forward(body) {
  let lastErr;
  for (let i = 0; i < urls.length; i++) {
    const url = urls[(current + i) % urls.length];
    try {
      const r = await fetch(url, {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        body,
        signal: AbortSignal.timeout(40000),
      });
      current = (current + i) % urls.length;
      if (r.status === 202) return null;
      const text = await r.text();
      if (!r.ok) throw new Error(`HTTP ${r.status} von ${url}`);
      return text ? JSON.parse(text) : null;
    } catch (e) {
      lastErr = e;
      log(`${url} nicht erreichbar: ${e.message}`);
    }
  }
  throw lastErr;
}

let pending = 0;
let closed = false;
const rl = readline.createInterface({ input: process.stdin });
rl.on("line", async (line) => {
  if (!line.trim()) return;
  pending++;
  try { await handleLine(line); } finally {
    pending--;
    if (closed && pending === 0) process.exit(0);
  }
});
rl.on("close", () => { closed = true; if (pending === 0) process.exit(0); });

async function handleLine(line) {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  try {
    const res = await forward(line);
    if (res) write(res);
  } catch (e) {
    if (msg && msg.id !== undefined) {
      write({ jsonrpc: "2.0", id: msg.id, error: { code: -32000,
        message: `Keine Bridge erreichbar (${urls.join(", ")}): ${e.message}` } });
    }
  }
}
log(`leitet weiter an ${urls.join(", ")}`);
