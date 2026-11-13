# EGLO Bridge – MCP für Claude Desktop

Jede Bridge (Android-App und Raspberry Pi) hat einen eigenen **MCP-Zugang** auf Port **8765**
(`http://<IP-der-Bridge>:8765/mcp`, JSON-RPC über HTTP). Die Bridge erledigt alles über ihre eigene
MQTT-Verbindung – der Assistent braucht keine Zugangsdaten.

Werkzeuge: `eglo_overview`, `eglo_set_light`, `eglo_bridge_settings`, `eglo_configure_bridge`,
`eglo_bridge_command`, `eglo_assign`, `eglo_effects`, `eglo_log`.

Diese Erweiterung (`eglo-bridge.mcpb`) verbindet Claude Desktop damit: Sie leitet nur weiter.
Einstellung „Bridge-Adressen“: IPs der Bridges, durch Komma getrennt; die erste erreichbare wird
benutzt.

```bash
npx @anthropic-ai/mcpb pack mcp eglo-bridge.mcpb
```
