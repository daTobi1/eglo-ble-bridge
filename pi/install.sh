#!/usr/bin/env bash
# Install / update the EGLO BLE Bridge on a Raspberry Pi (Raspberry Pi OS / Debian).
#
#   git clone https://github.com/daTobi1/eglo-ble-bridge && cd eglo-ble-bridge/pi
#   sudo ./install.sh            # install + interactive setup
#   sudo ./install.sh update     # git pull, reinstall, restart
#   sudo ./install.sh music      # optional: microphone support (numpy, sounddevice)
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PREFIX=/opt/eglo-bridge
VENV="$PREFIX/venv"
CONF_DIR=/etc/eglo-bridge
SVC_USER=eglo-bridge

if [[ $EUID -ne 0 ]]; then
  echo "Bitte mit sudo ausführen: sudo $0 $*" >&2
  exit 1
fi

install_pkg() {
  # build from a clean temporary copy: building in-tree as root leaves root-owned
  # build/ and *.egg-info in the checkout, and a stale build/lib can resurrect deleted modules
  local tmp
  tmp="$(mktemp -d)"
  cp -r "$SRC"/. "$tmp"/
  rm -rf "$tmp"/build "$tmp"/*.egg-info "$tmp"/tests
  "$VENV/bin/pip" install --upgrade --quiet "$tmp$1"
  rm -rf "$tmp"
}

case "${1:-install}" in
  update)
    if [[ -d "$SRC/../.git" ]]; then
      sudo -u "$(stat -c %U "$SRC/..")" git -C "$SRC/.." pull --ff-only
    fi
    install_pkg ""
    systemctl restart eglo-bridge
    echo "Aktualisiert auf $("$VENV/bin/eglo-bridge" --version)."
    exit 0
    ;;
  music)
    apt-get install -y -q libportaudio2
    install_pkg "[music]"
    systemctl restart eglo-bridge || true
    echo "Mikrofon-Unterstützung installiert."
    exit 0
    ;;
  install) ;;
  *) echo "Verwendung: sudo $0 [install|update|music]" >&2; exit 1 ;;
esac

echo "==> Pakete"
apt-get update -q
apt-get install -y -q python3-venv python3-pip bluez

echo "==> Bluetooth einschalten"
rfkill unblock bluetooth || true
systemctl enable --now bluetooth

echo "==> Dienstbenutzer $SVC_USER"
if ! id "$SVC_USER" >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin "$SVC_USER"
fi
usermod -aG bluetooth "$SVC_USER"
getent group audio >/dev/null && usermod -aG audio "$SVC_USER"

echo "==> Programm nach $PREFIX"
mkdir -p "$PREFIX"
[[ -x "$VENV/bin/python" ]] || python3 -m venv "$VENV"
"$VENV/bin/pip" install --upgrade --quiet pip
install_pkg ""
ln -sf "$VENV/bin/eglo-bridge" /usr/local/bin/eglo-bridge

echo "==> Konfiguration in $CONF_DIR"
mkdir -p "$CONF_DIR"
chown "$SVC_USER:$SVC_USER" "$CONF_DIR"
chmod 700 "$CONF_DIR"
if [[ ! -s "$CONF_DIR/config.json" ]]; then
  sudo -u "$SVC_USER" "$VENV/bin/eglo-bridge" setup || {
    echo "Einrichtung nicht abgeschlossen – später mit: sudo -u $SVC_USER eglo-bridge setup" >&2
  }
else
  echo "Vorhandene Konfiguration bleibt erhalten."
fi

echo "==> systemd-Dienst"
install -m 644 "$SRC/eglo-bridge.service" /etc/systemd/system/eglo-bridge.service
if ! getent group audio >/dev/null; then
  sed -i 's/^SupplementaryGroups=bluetooth audio/SupplementaryGroups=bluetooth/' /etc/systemd/system/eglo-bridge.service
fi
systemctl daemon-reload
if sudo -u "$SVC_USER" "$VENV/bin/python" -c 'import sys; from eglo_bridge.config import Config; sys.exit(0 if Config.load().is_complete else 1)'; then
  systemctl enable eglo-bridge
  systemctl restart eglo-bridge
else
  echo "Konfiguration unvollständig – Dienst wird noch nicht gestartet."
  echo "Nach „sudo -u $SVC_USER eglo-bridge setup“: sudo systemctl enable --now eglo-bridge"
  exit 0
fi

echo
echo "Fertig. Log ansehen:   journalctl -u eglo-bridge -f"
echo "Empfang prüfen:        sudo systemctl stop eglo-bridge && sudo -u $SVC_USER eglo-bridge scan; sudo systemctl start eglo-bridge"
