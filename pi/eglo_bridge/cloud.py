"""Minimal client for the AwoX/EGLO Parse cloud (port of AwoxCloud.kt).

Used to import the mesh credentials and the device list. Blocking - run it in a thread.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass

from .config import MeshDevice, normalize_mac

CONNECT_URL = "https://l4hparse-prod.awox.cloud/parse/"
HOME_CONTROL_URL = "https://l4hparse-hc-prod.awox.cloud/parse/"
APP_ID = "55O69FLtoxPt67LLwaHGpHmVWndhZGn9Wty8PLrJ"
CLIENT_KEY = "PyR3yV65rytEicteNlQHSVNpAGvCByOrsLiEqJtI"


@dataclass
class CloudResult:
    mesh_name: str
    mesh_password: str
    devices: list[MeshDevice]


class AwoxCloud:
    def __init__(self, username: str, password: str):
        self.username = username
        self.password = password
        self.installation_id = str(uuid.uuid4())
        self.api_url = CONNECT_URL
        self.object_id = ""
        self.session_token = ""

    def _post(self, url: str, body: dict, session: str | None = None) -> tuple[int, str]:
        headers = {
            "x-parse-application-id": APP_ID,
            "x-parse-installation-id": self.installation_id,
            "x-parse-client-key": CLIENT_KEY,
            "content-type": "application/json",
        }
        if session:
            headers["x-parse-session-token"] = session
        req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=20) as r:  # noqa: S310 - fixed https URLs
                return r.status, r.read().decode()
        except urllib.error.HTTPError as err:
            return err.code, err.read().decode(errors="replace")

    def _login(self) -> None:
        body = {"username": self.username.lower().strip(), "password": self.password, "_method": "GET"}
        code, text = self._post(CONNECT_URL + "login", body)
        if code != 200:
            self.api_url = HOME_CONTROL_URL
            code, text = self._post(HOME_CONTROL_URL + "login", body)
            if code != 200:
                try:
                    err = json.loads(text).get("error", text)
                except ValueError:
                    err = text
                raise RuntimeError(f"Login fehlgeschlagen: {err}")
        o = json.loads(text)
        self.object_id = o["objectId"]
        self.session_token = o["sessionToken"]

    def _fetch_class(self, name: str) -> list[dict]:
        where = {"owner": {"__type": "Pointer", "className": "_User", "objectId": self.object_id}}
        code, text = self._post(self.api_url + "classes/" + name, {"where": where, "_method": "GET"},
                                self.session_token)
        if code != 200:
            raise RuntimeError(f"Laden von {name} fehlgeschlagen ({code}): {text}")
        return json.loads(text)["results"]

    def load(self) -> CloudResult:
        self._login()
        mesh_name = mesh_password = ""
        for c in self._fetch_class("Credential"):
            if c.get("service") == "mesh":
                mesh_name = c.get("client_id", "")
                mesh_password = c.get("access_token", "")
                break
        if not mesh_name:
            raise RuntimeError("Keine Mesh-Zugangsdaten im AwoX-Konto gefunden")

        devices: list[MeshDevice] = []
        for d in self._fetch_class("Device"):
            typ = d.get("type", "")
            if not typ.startswith(".ble.tlmesh"):
                continue
            address = d.get("address", "")
            mac = d.get("macAddress", "")
            if not address or not mac:
                continue
            devices.append(MeshDevice(
                meshId=int(address),
                name=d.get("displayName", f"EGLO {address}"),
                mac=normalize_mac(mac),
                model=d.get("modelName", "unknown"),
                vendor=d.get("vendor", "EGLO"),
                firmware=d.get("version", ""),
                type=typ,
            ))
        return CloudResult(mesh_name, mesh_password, devices)
