package io.github.myshihi.pleosproject26.n01crashresponse

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import java.util.Locale

/** Vehicle position from the platform location service (GPS). Coordinates only; no address lookup or upload. */
class LocationSource(context: Context, private val changed: (Place?, String) -> Unit) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listener = LocationListener { publish(it) }
    var latest: Place? = null
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            if (providers.isEmpty()) { changed(null, "위치 서비스 꺼짐"); return }
            providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }?.let { publish(it) }
            providers.forEach { manager.requestLocationUpdates(it, 2_000L, 0f, listener, Looper.getMainLooper()) }
            if (latest == null) changed(null, "위치 기다리는 중")
        } catch (_: SecurityException) { changed(null, "위치 권한 없음") }
        catch (_: Exception) { changed(null, "위치 읽기 실패") }
    }

    private fun publish(l: Location) {
        val text = String.format(Locale.KOREA, "위도 %.5f · 경도 %.5f (오차 약 %.0fm)", l.latitude, l.longitude, l.accuracy)
        latest = Place(text, Source.VEHICLE)
        changed(latest, "수신 중")
    }

    fun stop() { runCatching { manager.removeUpdates(listener) } }
}
