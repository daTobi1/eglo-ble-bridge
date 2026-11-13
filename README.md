# EGLO BLE Bridge

Android-App, die ein altes Tablet zur **Brücke zwischen EGLO-Connect/AwoX-Bluetooth-Mesh-Lampen und Home Assistant** macht – ähnlich wie der ESP32-Hub [EspHome-AwoX-BLE-mesh-hub](https://github.com/fsaris/EspHome-AwoX-BLE-mesh-hub), nur ohne Zusatzhardware.

- verbindet sich per Bluetooth LE mit einer Lampe im Mesh und erreicht darüber alle Lampen
- meldet die Lampen per **MQTT Discovery** an Home Assistant (An/Aus, Helligkeit, Farbtemperatur, RGB)
- läuft als Hintergrunddienst, startet nach Neustart automatisch
- Diagnose-Entitäten in HA: Status, Log, Button „Neu verbinden“

> **Raspberry Pi / Linux:** Dieselbe Bridge gibt es auch als Linux-Dienst, siehe [`pi/`](pi/README.md). Pi und Tablets arbeiten zusammen; jede Lampe wird von der Bridge mit dem besten Empfang gesteuert.

> **KI-Assistent / MCP:** In der App unter *Einrichtung durch Assistent* wählbar: **zentral über Home Assistant** (Einstellungen per MQTT `config/set`), **dezentral** (eigener MCP-Zugang der Bridge auf Port 8765, `http://<IP>:8765/mcp`) oder **beides**. Siehe [`mcp/`](mcp/README.md).

## Installation

1. Auf dem Tablet die APK aus dem Release **[latest](../../releases/tag/latest)** herunterladen und installieren.
2. App öffnen, AwoX/EGLO-Connect-Konto eintragen → **Lampen aus AwoX-Cloud importieren**.
3. MQTT-Daten des Mosquitto-Brokers eintragen.
4. **Standort erlauben** („Immer zulassen“) und **Akku-Optimierung aus**.
5. **Speichern & Starten**.

Die Lampen erscheinen in Home Assistant unter *Einstellungen → Geräte & Dienste → MQTT*.

Der Name des Tablets lässt sich später in Home Assistant (Entität „Name“) oder per MQTT `eglo_bridge/bridge/<id>/config/set` ändern – Gerät und Entitäten in HA bleiben dabei erhalten. Über `config/set` lassen sich außerdem Kiosk, Dashboard-Adresse, PIN, Helligkeit, Medienlautstärke, MQTT- und AwoX-Zugang ändern; `eglo_bridge/bridge/<id>/config` zeigt die aktuellen Einstellungen und Geräteinfos.

## Sprach-Satellit (Home Assistant Assist)

Die Tablet-App kann sich Home Assistant gegenüber als **ESPHome-Sprachsatellit** ausgeben (wie ein Home Assistant Voice PE). Einschalten in der App: „Sprach-Satellit für Home Assistant“ (oder per MQTT `config/set` mit `{"voice_enabled": true}`).

- Home Assistant findet das Tablet über mDNS unter *Einstellungen → Geräte & Dienste* als ESPHome-Gerät; sonst manuell mit der IP des Tablets und Port **6053** hinzufügen (ohne Verschlüsselungsschlüssel).
- Es entstehen ein Assist-Satellit (mit Auswahl der Pipeline), ein Media Player und der Schalter „Mikrofon stumm“.
- Stufe 1: Das Wake-Word erkennt Home Assistant (z. B. openWakeWord in der Pipeline). Das Tablet schickt dazu das Mikrofon-Audio dauerhaft an Home Assistant.
- Antworten, Durchsagen (`assist_satellite.announce`), Rückfragen (`assist_satellite.start_conversation`) und Sprach-Timer werden auf dem Tablet abgespielt bzw. im Kiosk angezeigt. Ein abgelaufener Timer klingelt, bis man auf die Anzeige tippt.
- Musikmodus und Sprach-Satellit teilen sich das Mikrofon und laufen gleichzeitig.

## Zuständigkeit mehrerer Bridges

Jede Bridge (Tablet, Pi) kann auf bestimmte Lampen beschränkt werden: Sie verbindet sich dann nur
mit diesen Lampen und steuert/meldet nur diese – die übrigen übernimmt eine andere Bridge.
Einstellbar in der Android-App („Für welche Lampen ist dieses Tablet zuständig?“) und in
Home Assistant beim Gerät der jeweiligen Bridge („Zuständig für alle Lampen“ bzw.
„Zuständig: <Lampe>“). Standard: alle Lampen.

## Hinweise

- Die alte HA-Integration „AwoX MESH control“ sollte deaktiviert werden, da die Lampen nur wenige gleichzeitige Verbindungen erlauben.
- Die APK wird von GitHub Actions gebaut. Der Signierschlüssel (`app/debug.keystore`) liegt bewusst im Repo, damit jede neue Version als Update installiert werden kann. Es ist ein reiner Debug-Schlüssel ohne Schutzfunktion.

## Danksagung

Protokoll basierend auf [python-awox-mesh-light](https://github.com/Leiaz/python-awox-mesh-light) (Leiaz),
[home-assistant-awox](https://github.com/fsaris/home-assistant-awox) und
[EspHome-AwoX-BLE-mesh-hub](https://github.com/fsaris/EspHome-AwoX-BLE-mesh-hub) (fsaris).
