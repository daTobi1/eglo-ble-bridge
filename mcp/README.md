# EGLO Bridge – MCP für Claude Desktop

Jede Bridge (Android-App und Raspberry Pi) kann einen eigenen **MCP-Zugang** auf Port **8765**
anbieten (`http://<IP-der-Bridge>:8765/mcp`, JSON-RPC über HTTP). Die Bridge erledigt alles über ihre
eigene MQTT-Verbindung – der Assistent braucht keine Zugangsdaten.

Einstellung **„Einrichtung durch Assistent“** (App) bzw. `assistantAccess` (Pi, `eglo-bridge setup`):

| Wert | MCP-Zugang (Port 8765) | Einrichtung per MQTT `config/set` (Home Assistant) |
|---|---|---|
| `ha` – zentral über Home Assistant (Standard) | aus | an |
| `mcp` – dezentral | an | aus (wird ignoriert) |
| `both` – beides | an | an |

Lampen schalten und die bisherigen HA-Knöpfe (Neustart, Bildschirm …) funktionieren in jedem Modus.
Der MCP-Zugang hat kein Passwort – nur in einem vertrauenswürdigen WLAN einschalten.

Werkzeuge: `eglo_overview`, `eglo_set_light`, `eglo_bridge_settings`, `eglo_configure_bridge`,
`eglo_bridge_command`, `eglo_assign`, `eglo_effects`, `eglo_log`.

Diese Erweiterung (`eglo-bridge.mcpb`) verbindet Claude Desktop damit: Sie leitet nur weiter.
Einstellung „Bridge-Adressen“: IPs der Bridges, durch Komma getrennt; die erste erreichbare wird
benutzt.

```bash
npx @anthropic-ai/mcpb pack mcp eglo-bridge.mcpb
```
