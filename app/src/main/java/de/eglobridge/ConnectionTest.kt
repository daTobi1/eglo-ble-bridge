package de.eglobridge

import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * "Verbindung testen" in the settings: checks network, MQTT broker, MQTT login, the Home Assistant
 * dashboard and the update server one after another and explains each failure in plain words.
 * Blocking – call it from a background thread.
 */
object ConnectionTest {

    data class Step(val ok: Boolean, val title: String, val detail: String)

    fun run(host: String, port: Int, user: String, password: String, kioskUrl: String): List<Step> {
        val steps = ArrayList<Step>()

        // 1. own network address
        val ip = ownIp()
        steps += if (ip != null) Step(true, "Netzwerk", "Tablet hat die Adresse $ip")
        else Step(false, "Netzwerk", "Keine Netzwerkadresse – ist das WLAN verbunden?")

        // 2. broker reachable (TCP)
        val tcp = if (host.isEmpty()) Step(false, "MQTT-Broker", "Keine Adresse eingetragen")
        else tcpCheck(host, port)
        steps += tcp

        // 3. MQTT login
        steps += if (!tcp.ok) Step(false, "MQTT-Anmeldung", "Übersprungen (Broker nicht erreichbar)")
        else mqttLogin(host, port, user, password)

        // 4. Home Assistant dashboard (kiosk address)
        if (kioskUrl.isNotBlank()) steps += httpCheck("Home Assistant", kioskUrl)

        // 5. update server
        steps += httpCheck("Update-Server (GitHub)",
            "https://github.com/daTobi1/eglo-ble-bridge/releases/download/latest/version.json")

        // 6. running bridge
        val hub = BridgeService.hub
        steps += when {
            !BridgeService.running -> Step(false, "Bridge-Dienst", "Läuft nicht – „Speichern & Starten“ tippen")
            hub?.connected == true -> Step(true, "Bridge-Dienst", "Läuft und ist mit MQTT verbunden")
            else -> Step(false, "Bridge-Dienst", "Läuft, ist aber (noch) nicht mit MQTT verbunden")
        }
        return steps
    }

    private fun ownIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    } catch (_: Throwable) { null }

    private fun tcpCheck(host: String, port: Int): Step = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 5000) }
        Step(true, "MQTT-Broker", "$host:$port ist erreichbar")
    } catch (e: UnknownHostException) {
        Step(false, "MQTT-Broker", "Adresse „$host“ unbekannt – Tippfehler?")
    } catch (e: SocketTimeoutException) {
        Step(false, "MQTT-Broker", "$host:$port antwortet nicht (Zeitüberschreitung) – falsche IP oder anderes Netz?")
    } catch (e: ConnectException) {
        Step(false, "MQTT-Broker", "$host:$port lehnt ab – läuft der Broker (Mosquitto) und stimmt der Port?")
    } catch (e: Throwable) {
        Step(false, "MQTT-Broker", "$host:$port nicht erreichbar: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun mqttLogin(host: String, port: Int, user: String, password: String): Step {
        var c: MqttAsyncClient? = null
        return try {
            c = MqttAsyncClient("tcp://$host:$port", "eglo-bridge-test-" + System.currentTimeMillis() % 100000,
                MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 8
                if (user.isNotEmpty()) { userName = user; this.password = password.toCharArray() }
            }
            c.connect(opts).waitForCompletion(10000)
            if (c.isConnected) Step(true, "MQTT-Anmeldung", if (user.isEmpty()) "Ohne Benutzer angemeldet" else "Als „$user“ angemeldet")
            else Step(false, "MQTT-Anmeldung", "Keine Antwort vom Broker")
        } catch (e: MqttException) {
            val why = when (e.reasonCode) {
                MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() -> "Benutzer oder Passwort falsch"
                MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() ->
                    if (user.isEmpty()) "Broker verlangt Benutzer und Passwort" else "Benutzer oder Passwort falsch (nicht berechtigt)"
                MqttException.REASON_CODE_INVALID_CLIENT_ID.toInt() -> "Client-ID abgelehnt"
                MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> "Zeitüberschreitung bei der Anmeldung"
                else -> "Fehler ${e.reasonCode}: ${e.message ?: ""}"
            }
            Step(false, "MQTT-Anmeldung", why)
        } catch (e: Throwable) {
            Step(false, "MQTT-Anmeldung", e.message ?: e.javaClass.simpleName)
        } finally {
            try { if (c?.isConnected == true) c.disconnect().waitForCompletion(2000) } catch (_: Throwable) { }
            try { c?.close() } catch (_: Throwable) { }
        }
    }

    private fun httpCheck(title: String, url: String): Step {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code in 200..399) Step(true, title, "${URL(url).host} antwortet (HTTP $code)")
            else Step(false, title, "${URL(url).host} antwortet mit HTTP $code")
        } catch (e: UnknownHostException) {
            Step(false, title, "Adresse unbekannt – kein Internet/DNS oder Tippfehler")
        } catch (e: SocketTimeoutException) {
            Step(false, title, "Keine Antwort (Zeitüberschreitung)")
        } catch (e: ConnectException) {
            Step(false, title, "Verbindung abgelehnt – Adresse oder Port falsch?")
        } catch (e: Throwable) {
            Step(false, title, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
