# EGLO Bridge MCP-Server

MCP-Server, mit dem ein KI-Assistent (Claude Desktop, Claude Code o. ä.) die EGLO-Lampen und alle
EGLO-BLE-Bridges (Tablets, Raspberry Pi) steuern kann. Er spricht nur MQTT mit dem Broker, den auch die
Bridges nutzen – an den Bridges muss nichts geändert werden.

## Werkzeuge

| Tool | Funktion |
|---|---|
| `eglo_overview` | Alle Bridges und Lampen auf einen Blick (an/aus, Helligkeit, Farbe, welche Bridge steuert) |
| `eglo_get_lamp` | Details einer Lampe: Modell, Fähigkeiten, Empfang je Bridge, Favoriten, Effekt-Einstellungen |
| `eglo_set_light` | Lampe(n) schalten: an/aus, Helligkeit %, Kelvin, RGB (`#RRGGBB`), Effekt; `["alle"]` für alle |
| `eglo_favorite` | Favorit 1/2 abrufen oder speichern |
| `eglo_lamp_setting` | Lagerfeuer-Tempo/-Stärke/-Helligkeit, Effekt-Tempo einer Lampe |
| `eglo_effects` | Musikmodus, Musikquelle, Empfindlichkeit, globale Effekt-/Lagerfeuer-Werte, Lampen im Musikmodus |
| `eglo_list_bridges` | Bridges mit Status, Version, Akku, Bildschirm, Mikrofon, Update, Zuständigkeit |
| `eglo_bridge_log` | Log-Zeilen (live empfangen, ab Start des Servers gepuffert) |
| `eglo_bridge_debug` | Debug-Log einer Bridge an/aus |
| `eglo_bridge_command` | `reconnect`, `restart`, `reimport`, `check_update`, `install_update`, `reload_kiosk` |
| `eglo_kiosk` | Tablet-Bildschirm an/aus, Helligkeit, Bildschirm-aus-Zeit, Dashboard-URL |
| `eglo_assign` | Zuständigkeit einer Bridge für einzelne/alle Lampen |
| `eglo_mqtt_topics` | Rohdaten aller `eglo_bridge/…`-Topics zur Diagnose |

Lampen und Bridges werden per Name, Teilname (Umlaute egal) oder ID angesprochen.

## Installation in Claude Desktop

`eglo-bridge.mcpb` aus dem Release laden (oder selbst bauen, s. u.), doppelklicken → **Installieren**,
MQTT-Broker (`mqtt://192.168.178.120:1883`), Benutzer und Passwort eintragen. Claude Desktop bringt
Node.js mit, sonst ist nichts nötig.

Als Mosquitto-Benutzer eignet sich ein eigener Login (Mosquitto-App → Konfiguration → `logins`)
oder ein Home-Assistant-Benutzer.

## Manuell (Claude Code, andere Clients)

```bash
cd mcp && npm install
```

```json
{
  "mcpServers": {
    "eglo-bridge": {
      "command": "node",
      "args": ["/pfad/zu/eglo-ble-bridge/mcp/server.js"],
      "env": {
        "EGLO_MQTT_URL": "mqtt://192.168.178.120:1883",
        "EGLO_MQTT_USER": "…",
        "EGLO_MQTT_PASSWORD": "…"
      }
    }
  }
}
```

## Entwicklung

```bash
npm install
npm test          # lokaler Broker + simulierte Bridge + MCP-Client, Ende-zu-Ende
npm run bundle    # build/server.mjs (eine Datei, ohne node_modules)
npm run mcpb      # eglo-bridge.mcpb für Claude Desktop
```
