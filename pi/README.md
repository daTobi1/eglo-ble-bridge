# EGLO BLE Bridge für Raspberry Pi / Linux

Die gleiche Brücke wie die Android-App: Sie verbindet EGLO-Connect/AwoX-Bluetooth-Mesh-Lampen mit Home Assistant (MQTT Discovery). Sie läuft hier als Linux-Dienst auf einem Raspberry Pi (oder jedem Linux mit BlueZ).

- Python-Port der App: gleiche Mesh-Krypto (identische Testvektoren), gleiche MQTT-Topics und Entitäten
- arbeitet **zusammen mit den Android-Tablets**: Jede Bridge meldet, wie gut sie jede Lampe erreicht, und die mit dem besten Empfang steuert sie
- bis zu 3 direkte BLE-Verbindungen, schnelles Wiederfinden nach Stromausfall, Lagerfeuer-Effekt, Favoriten
- Musikmodus: Der Pi wendet die Musik-Frames einer Tablet-Bridge an; mit USB-Mikrofon kann er auch selbst zuhören (optional)

Nicht enthalten (gibt es nur auf dem Tablet): Kiosk-Bildschirm, Akku-Sensoren, APK-Update. Für ein Wandpanel mit Pi-Display eignet sich TouchKio.

## Installation

```bash
sudo apt install -y git
git clone https://github.com/daTobi1/eglo-ble-bridge
cd eglo-ble-bridge/pi
sudo ./install.sh
```

Das Skript installiert BlueZ, legt den Dienstbenutzer `eglo-bridge` an und richtet die Bridge in `/opt/eglo-bridge` ein. Danach fragt es nach:

- **Name der Bridge**, z. B. `Pi Küche`. Er muss sich von den Tablets unterscheiden, weil er die MQTT-Kennung bildet.
- **MQTT** (Mosquitto in Home Assistant): IP, Port 1883, Benutzer, Passwort
- **AwoX/EGLO-Connect-Konto**, damit die Bridge Mesh-Zugangsdaten und Lampen aus der Cloud importieren kann

Anschließend startet der Dienst `eglo-bridge` und läuft künftig automatisch beim Booten. In Home Assistant erscheint das Gerät „EGLO Bridge Pi Küche“ mit Status, Log, Debug-Schalter und den Buttons „Neu verbinden“, „Bridge neu starten“ und „Lampen neu importieren“.

## Bedienung

| Aufgabe | Befehl |
|---|---|
| Log live ansehen | `journalctl -u eglo-bridge -f` |
| Neustart | `sudo systemctl restart eglo-bridge` |
| Konfiguration ändern | `sudo -u eglo-bridge eglo-bridge setup` |
| Lampen neu importieren | `sudo -u eglo-bridge eglo-bridge import` (oder Button in HA) |
| Konfigurierte Lampen | `sudo -u eglo-bridge eglo-bridge devices` |
| Empfang am Standort prüfen | `sudo systemctl stop eglo-bridge && sudo -u eglo-bridge eglo-bridge scan; sudo systemctl start eglo-bridge` |
| Update | `sudo ./install.sh update` |
| Mikrofon-Unterstützung | `sudo ./install.sh music` |

Die Konfiguration liegt in `/etc/eglo-bridge/config.json` (nur für `eglo-bridge` lesbar, weil sie Passwörter enthält). Ein zweiter Bluetooth-Adapter lässt sich dort mit `"adapter": "hci1"` auswählen.

## Zuständigkeit mehrerer Bridges

Jede Bridge (Tablet, Pi) kann auf bestimmte Lampen beschränkt werden: Sie verbindet sich dann nur
mit diesen Lampen und steuert/meldet nur diese – die übrigen übernimmt eine andere Bridge.
Einstellbar in der Android-App („Für welche Lampen ist dieses Tablet zuständig?“) und in
Home Assistant beim Gerät der jeweiligen Bridge („Zuständig für alle Lampen“ bzw.
„Zuständig: <Lampe>“). Standard: alle Lampen.

## Hinweise

- **Integrierter Bluetooth-Chip des Pi 4:** WLAN und Bluetooth teilen sich einen Chip, und 2,4-GHz-WLAN stört den BLE-Empfang. Besser per LAN-Kabel oder mit 5-GHz-WLAN betreiben, sonst einen USB-Bluetooth-Stick verwenden.
- **Neben einem ESPHome-Bluetooth-Proxy (z. B. bt-proxy) auf demselben Adapter:** Das funktioniert, beide teilen sich dann aber die Verbindungen des Adapters. Für die EGLO-Lampen braucht es keinen zusätzlichen Proxy.
- Die HA-Integration „AwoX MESH control“ sollte deaktiviert bleiben, weil die Lampen nur wenige gleichzeitige Verbindungen erlauben.

## Entwicklung

```bash
cd pi
pip install -e ".[test]" amqtt
pytest
```

Die Tests enthalten eine simulierte Telink-Lampe mit echtem Pairing und echter Verschlüsselung (`tests/fake_ble.py`). Damit laufen Mesh-Logik und MQTT Ende-zu-Ende gegen einen echten Broker, ohne Hardware.
