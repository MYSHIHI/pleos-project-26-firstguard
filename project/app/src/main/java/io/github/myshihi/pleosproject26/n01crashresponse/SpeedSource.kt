package io.github.myshihi.pleosproject26.n01crashresponse

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** PERF_VEHICLE_SPEED through the Android car service. Needs the runtime CAR_SPEED permission; without it the hard-stop hint is off. */
class SpeedSource(private val context: Context, private val speed: (Float?, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    private var generation = 0

    fun start() {
        stop()
        val token = generation
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (token != generation) return@createCar
                if (!ready) { speed(null, "차량 서비스 연결 끊김"); return@createCar }
                try {
                    val m = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    manager = m
                    val cb = object : CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(value: CarPropertyValue<*>) {
                            if (token != generation) return
                            val mps = value.value as? Float
                            handler.post { if (token == generation) speed(mps?.let { it * 3.6f }, "수신 중") }
                        }
                        override fun onErrorEvent(propertyId: Int, zone: Int) {
                            handler.post { if (token == generation) speed(null, "속도 수신 오류") }
                        }
                    }
                    callback = cb
                    @Suppress("DEPRECATION")
                    val ok = m.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)
                    if (!ok) speed(null, "속도 구독 실패")
                } catch (_: SecurityException) { speed(null, "속도 권한 없음 · 급정지 감지 꺼짐") }
                catch (_: Exception) { speed(null, "속도 읽기 미지원") }
            }
        } catch (_: LinkageError) { speed(null, "Android Automotive 필요") }
        catch (_: Exception) { speed(null, "차량 서비스 연결 실패") }
    }

    fun stop() {
        generation++
        try { callback?.let { manager?.unregisterCallback(it) } } catch (_: Exception) { }
        try { car?.disconnect() } catch (_: Exception) { }
        callback = null; manager = null; car = null
    }
}
