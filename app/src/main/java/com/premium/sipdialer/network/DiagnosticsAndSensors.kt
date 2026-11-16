package com.premium.sipdialer.network

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

data class SipServerProbeResult(
    val localIp: String,
    val publicIp: String,
    val resolvedDnsIp: String,
    val tcpPingMs: Long,
    val isReachable: Boolean
)

object NetworkDiagnosticsEngine {
    fun getLocalIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            "Unavailable"
        }
    }

    suspend fun runFullDiagnostics(domain: String, port: Int = 5060): SipServerProbeResult = withContext(Dispatchers.IO) {
        val localIp = getLocalIpAddress()
        val publicIp = try {
            URL("https://api.ipify.org").readText().trim()
        } catch (e: Exception) {
            "Unavailable"
        }

        val resolvedIp = try {
            InetAddress.getByName(domain).hostAddress ?: "Resolution Failed"
        } catch (e: Exception) {
            "DNS Error"
        }

        var pingTime = -1L
        var reachable = false
        try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(domain, port), 2500)
            }
            pingTime = System.currentTimeMillis() - start
            reachable = true
        } catch (e: Exception) {
            // If TCP port is closed (UDP-only SIP server), fallback to InetAddress reachability check
            try {
                val start = System.currentTimeMillis()
                reachable = InetAddress.getByName(domain).isReachable(2500)
                if (reachable) pingTime = System.currentTimeMillis() - start
            } catch (_: Exception) {}
        }

        SipServerProbeResult(
            localIp = localIp,
            publicIp = publicIp,
            resolvedDnsIp = resolvedIp,
            tcpPingMs = pingTime,
            isReachable = reachable
        )
    }
}

class ProximityScreenController(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    fun startMonitoring(isSpeakerOrBluetoothActive: Boolean) {
        if (isSpeakerOrBluetoothActive || proximitySensor == null) {
            stopMonitoring()
            return
        }
        if (wakeLock == null && powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "PremiumSIPDialer::ProximityWakeLock"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(60 * 60 * 1000L)
        }
        sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {}
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
