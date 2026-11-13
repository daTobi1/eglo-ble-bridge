# EGLO BLE Bridge für Raspberry Pi / Linux

Die gleiche Brücke wie die Android-App: Sie verbindet EGLO-Connect/AwoX-Bluetooth-Mesh-Lampen mit Home Assistant (MQTT Discovery). Sie läuft hier als Linux-Dienst auf einem Raspberry Pi (oder jedem Linux mit BlueZ).

- Python-Port der App: gleiche Mesh-Krypto (identische Testvektoren), gleiche MQTT-Topics und Entitäten
- arbeitet **zusammen mit den Android-Tablets**: Jede Bridge meldet, wie gut sie jede Lampe erreicht, und die mit dem besten Empfang steuert sie
- bis zu 3 direkte BLE-Verbindungen, schnelles Wiederfinden nach Stromausfall, Lagerfeuer-Effekt, Favoriten
- Musikmodus: Der Pi wendet die Musik-Frames einer Tablet-Bridge an; mit USB-Mikrofon kann er auch selbst zuhören (optional)

- Bildschirmsteuerung für ein Pi-Wandpanel mit Touch-Display: Die Hintergrundbeleuchtung geht nach einer einstellbaren Zeit aus, und ein Fingertipp weckt sie wieder (siehe unten).

Nicht enthalten (gibt es nur auf dem Tablet): Kiosk-Browser, Akku-Sensoren, APK-Update. Als Kiosk-Browser auf dem Pi eignet sich z. B. TouchKio oder Chromium im Kiosk-Modus.

## Installation

```bash
sudo apt install -y git
git clone https://github.com/daTobi1/eglo-ble-bridge
cd eglo-ble-bridge/pi
sudo ./install.sh
```

Das Skript installiert BlueZ, legt den Dienstbenutzer `eglo-bridge` an und richtet die Bridge in `/opt/eglo-bridge` ein. Danach fragt es nach:

- **Name der Bridge**, z. B. `Pi Küche`. Er muss sich von den Tablets unterscheiden, weil beim ersten Start daraus die MQTT-Kennung gebildet wird. Diese bleibt danach fest; ein späteres Umbenennen (Entität „Name“ in HA oder `config/set`) ändert nur den Anzeigenamen.
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

## Bildschirm (Pi mit Touch-Display)

Hat der Pi ein Display mit steuerbarer Hintergrundbeleuchtung (offizielles Raspberry-Pi-Touch-Display und die meisten DSI-Panels, zu finden unter `/sys/class/backlight`), bekommt das Bridge-Gerät in Home Assistant dieselben Entitäten wie ein Tablet:

| Entität | Funktion |
|---|---|
| **Bildschirm** (Schalter) | Hintergrundbeleuchtung an/aus, z. B. per Automation (Bewegungsmelder, Türklingel, Babyphone) |
| **Bildschirmhelligkeit** (1–100 %) | Helligkeit des Displays |
| **Bildschirm aus nach** (0–120 min) | Ohne Berührung geht das Licht nach dieser Zeit aus; `0` = nie |

Ein Fingertipp aufs dunkle Display schaltet es wieder ein. Dieser erste Tipp wird abgefangen und löst nichts auf dem Dashboard aus. Zeit und Helligkeit werden in der Konfiguration gespeichert. Wird die Bridge beendet, schaltet sie das Display wieder ein. `install.sh` (auch `update`) gibt dem Dienst über eine udev-Regel und die Gruppen `video` und `input` die nötigen Rechte. HDMI-Monitore haben keine Hintergrundbeleuchtung unter `/sys/class/backlight`; dort bleibt die Funktion aus.

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
