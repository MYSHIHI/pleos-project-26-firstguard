package io.github.myshihi.pleosproject26.n01crashresponse

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.Safety
import ai.pleos.playground.vehicle.api.Seat as SeatApi
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Latest vehicle readings as the app last received them. null = not received / unknown, never "closed" or "empty". */
class VehicleSnapshot {
    val seats = mutableMapOf<Seat, SeatRef>()
    var seatStatus = "확인 중"
    /**
     * Pleos Connect 2.1.0 emulator: Virtual Console "Occupied" arrives as SDK false and "Empty" as true (also seen in E10).
     * When this correction is on (emulator default), the value is flipped so "occupied" means a person may be there.
     */
    var invertSeats = false
    fun seatRef(raw: Boolean?) = SeatRef(raw?.let { if (invertSeats) !it else it }, Source.VEHICLE, System.currentTimeMillis(),
        if (invertSeats && raw != null) "에뮬레이터 보정" else "")
    var airbagStatus = "확인 중"
    val airbagSeats = mutableSetOf<Seat>()
    val airbag = mutableMapOf<Seat, Boolean?>()
    var telltaleStatus = "확인 중"
    var warnings: List<String> = emptyList()
    var gear: String? = null
    var gearStatus = "확인 중"
    val doorOpen = mutableMapOf<Seat, Boolean?>()
    var doorStatus = "확인 중"
    val doorLocked = mutableMapOf<Seat, Boolean?>()
    var lockStatus = "확인 중"
    var speedKph: Float? = null
    var speedStatus = "확인 중"

    /**
     * Door / trunk / window requests require confirmed gear P. Zero speed alone does not confirm Park.
     * (On Pleos Connect 2.1.0 the vehicle itself rejected door-lock and trunk writes in D at 0 km/h with 50001 and accepted them in P.)
     * Unknown is never treated as stopped.
     */
    val stopped: Boolean get() = gear == "P"
    val stopText: String get() = when {
        gear == "P" -> "기어 P"
        gear == null && speedKph != null && speedKph!! < 0.5f -> "속도 0 · 기어 미수신 · P 확인 대기"
        gear == null && speedKph == null -> "정차 여부 미확인"
        speedKph != null && speedKph!! < 0.5f -> "멈췄지만 기어 $gear · P로 바꾼 뒤 요청"
        else -> "움직이는 중일 수 있음"
    }
}

class VehicleLink(
    context: Context,
    private val snapshot: VehicleSnapshot,
    private val changed: () -> Unit,
    private val airbagDeployed: (Seat) -> Unit,
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var generation = 0
    private val cleanups = mutableListOf<() -> Unit>()

    private fun post(token: Int, action: () -> Unit) { handler.post { if (token == generation) { action(); changed() } } }
    private fun seatArea(s: Seat) = SeatArea(SeatValue.valueOf(s.sdkArea))
    private fun doorArea(s: Seat) = DoorArea(DoorValue.valueOf(s.sdkArea))
    private fun readable(t: AccessType) = t == AccessType.READ || t == AccessType.READ_WRITE
    private fun writable(t: AccessType) = t == AccessType.WRITE || t == AccessType.READ_WRITE

    fun start() {
        stop()
        val token = generation
        val v = try { Vehicle(app).also { it.initialize() } } catch (e: Exception) {
            val msg = "차량 연결 실패"
            snapshot.seatStatus = msg; snapshot.airbagStatus = msg; snapshot.telltaleStatus = msg
            snapshot.gearStatus = msg; snapshot.doorStatus = msg; snapshot.lockStatus = msg
            changed(); return
        }
        vehicle = v
        guard(token, { snapshot.seatStatus = it }) { seats(v.getSeat(), token) }
        guard(token, { snapshot.airbagStatus = it }) { airbags(v.getSafety(), token) }
        guard(token, { snapshot.telltaleStatus = it }) { telltales(v.getSafety(), token) }
        guard(token, { snapshot.gearStatus = it }) { gear(v, token) }
        guard(token, { snapshot.doorStatus = it }) { doors(v.getDoor(), token) }
        guard(token, { snapshot.lockStatus = it }) { locks(v.getDoor(), token) }
    }

    private fun guard(token: Int, fail: (String) -> Unit, block: () -> Unit) {
        try { block() } catch (e: Exception) { post(token) { fail("연결 실패 · ${e.javaClass.simpleName}") } }
    }

    // ---- Seat occupancy: reference only -------------------------------------------------------------
    private fun seats(api: SeatApi, token: Int) {
        api.checkSeatOccupancyCapabilities(onComplete = { config, areas -> post(token) {
            val ok = readable(config.accessType)
            val supported = Seat.entries.filter { ok && seatArea(it) in areas }
            snapshot.seatStatus = if (supported.isEmpty()) "좌석 점유 미지원" else "참고값 수신 ${supported.size}석"
            supported.forEach { s ->
                val l = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupancy: Boolean?) = post(token) {
                        snapshot.seats[s] = snapshot.seatRef(occupancy)
                    }
                    override fun onFailed(e: Exception) = post(token) { snapshot.seats[s] = SeatRef(null, Source.VEHICLE, System.currentTimeMillis(), "구독 실패") }
                }
                runCatching { api.registerSeatOccupancy(seatArea(s), l); cleanups += { runCatching { api.unregisterSeatOccupancy(seatArea(s), l) } } }
                runCatching { api.getCurrentSeatOccupancy(seatArea(s), onComplete = { v -> post(token) {
                    snapshot.seats[s] = snapshot.seatRef(v)
                } }, onFailed = { post(token) { snapshot.seats[s] = SeatRef(null, Source.VEHICLE, System.currentTimeMillis(), "조회 실패") } }) }
            }
        } }, onFailed = { post(token) { snapshot.seatStatus = "좌석 지원 조회 실패" } })
    }

    // ---- Airbag deployment: the only signal that opens an incident on its own -------------------------
    private fun airbags(api: Safety, token: Int) {
        api.checkAirbagDeployedCapabilities(onComplete = { config, areas -> post(token) {
            val ok = readable(config.accessType)
            val supported = Seat.entries.filter { ok && seatArea(it) in areas }
            snapshot.airbagSeats.clear(); snapshot.airbagSeats.addAll(supported)
            snapshot.airbagStatus = if (supported.isEmpty()) "에어백 전개 신호 미지원" else "구독 중 ${supported.size}석"
            supported.forEach { s ->
                val l = object : SeatAirbagDeployedListener {
                    override fun onSeatAirbagDeployUpdated(deployed: Boolean?) = post(token) { airbagValue(s, deployed) }
                    override fun onFailed(e: Exception) = post(token) { snapshot.airbagStatus = "구독 실패 · ${s.label}" }
                }
                runCatching { api.registerSeatAirbagDeployed(seatArea(s), l); cleanups += { runCatching { api.unregisterSeatAirbagDeployed(seatArea(s), l) } } }
                runCatching { api.hasSeatAirbagBeenDeployed(seatArea(s), onComplete = { v -> post(token) { airbagValue(s, v) } },
                    onFailed = { post(token) { snapshot.airbag[s] = null } }) }
            }
        } }, onFailed = { post(token) { snapshot.airbagStatus = "전개 신호 조회 불가 · 샘플로 재현" } })
    }

    private fun airbagValue(s: Seat, deployed: Boolean?) {
        snapshot.airbag[s] = deployed
        if (deployed == true) airbagDeployed(s)
    }

    // ---- Warning lamps: "major device problem" reference ----------------------------------------------
    private fun telltales(api: Safety, token: Int) {
        val apply: (Telltales?) -> Unit = { t ->
            snapshot.warnings = t?.telltales.orEmpty()
                .filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
                .mapNotNull { WATCHED[it.type] }
                .distinct()
        }
        api.checkTelltalesCapabilities(onComplete = { config -> post(token) {
            if (!readable(config.accessType)) { snapshot.telltaleStatus = "경고등 읽기 미지원"; return@post }
            snapshot.telltaleStatus = "경고등 구독 중"
            val l = object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(t: Telltales?) = post(token) { apply(t) }
                override fun onFailed(e: Exception) = post(token) { snapshot.telltaleStatus = "경고등 구독 실패" }
            }
            runCatching { api.registerTelltales(l); cleanups += { runCatching { api.unregisterTelltales(l) } } }
            runCatching { api.getTelltalesInfo(onComplete = { t -> post(token) { apply(t) } }, onFailed = { }) }
        } }, onFailed = { post(token) { snapshot.telltaleStatus = "경고등 지원 조회 실패" } })
    }

    // ---- Gear ----------------------------------------------------------------------------------------
    private fun gear(v: Vehicle, token: Int) {
        val api = v.getGear()
        val show: (VehicleGear?) -> Unit = { g ->
            snapshot.gear = when (g) {
                VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"
                null, VehicleGear.GEAR_UNKNOWN -> null; else -> "D"
            }
            snapshot.gearStatus = if (snapshot.gear == null) "기어 값 없음" else "수신 중"
        }
        val l = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = post(token) { show(gear) }
            override fun onFailed(e: Exception) = post(token) { snapshot.gearStatus = "기어 구독 실패" }
        }
        runCatching { api.registerGearSelection(l); cleanups += { runCatching { api.unregisterGearSelection(l) } } }
        api.getCurrentGearSelectionState(onComplete = { g -> post(token) { show(g) } }, onFailed = { post(token) { snapshot.gearStatus = "기어 조회 실패" } })
    }

    // ---- Doors (position = open reference) and locks ---------------------------------------------------
    private fun doors(api: Door, token: Int) {
        api.checkDoorPositionCapabilities(onComplete = { config, bounds -> post(token) {
            val ok = readable(config.accessType)
            val supported = Seat.entries.filter { ok && doorArea(it) in bounds.keys }
            snapshot.doorStatus = if (supported.isEmpty()) "문 열림 미지원" else "수신 중 ${supported.size}개 문"
            supported.forEach { s ->
                val l = object : DoorPositionListener {
                    override fun onPositionUpdated(position: Int?) = post(token) { snapshot.doorOpen[s] = position?.let { it > 0 } }
                    override fun onFailed(e: Exception) = post(token) { snapshot.doorOpen[s] = null }
                }
                runCatching { api.registerDoorPosition(doorArea(s), l); cleanups += { runCatching { api.unregisterDoorPosition(doorArea(s), l) } } }
                runCatching { api.getCurrentDoorPosition(doorArea(s), onComplete = { p -> post(token) { snapshot.doorOpen[s] = p?.let { it > 0 } } }, onFailed = { }) }
            }
        } }, onFailed = { post(token) { snapshot.doorStatus = "문 지원 조회 실패" } })
    }

    private fun locks(api: Door, token: Int) {
        api.checkDoorLockCapabilities(onComplete = { config, areas -> post(token) {
            val ok = readable(config.accessType)
            val supported = Seat.entries.filter { s -> ok && areas.any { DoorValue.valueOf(s.sdkArea) in it.values } }
            snapshot.lockStatus = if (supported.isEmpty()) "잠금 상태 미지원" else "수신 중"
            supported.forEach { s ->
                val area = areas.first { DoorValue.valueOf(s.sdkArea) in it.values }
                val l = object : DoorLockStateListener {
                    override fun onDoorLockStateUpdated(locked: Boolean?) = post(token) { snapshot.doorLocked[s] = locked }
                    override fun onFailed(e: Exception) = post(token) { snapshot.doorLocked[s] = null }
                }
                runCatching { api.registerDoorLockState(area, l); cleanups += { runCatching { api.unregisterDoorLockState(area, l) } } }
                runCatching { api.getDoorLockState(area, onComplete = { v -> post(token) { snapshot.doorLocked[s] = v } }, onFailed = { }) }
            }
        } }, onFailed = { post(token) { snapshot.lockStatus = "잠금 지원 조회 실패" } })
    }

    // ---- Escape support: only on an explicit request, only while the vehicle reports a stop -----------

    fun unlockDoors(done: (ActionResult) -> Unit) = control(done) { v, finish ->
        val api = v.getDoor()
        api.checkDoorLockCapabilities(onComplete = { config, areas ->
            if (!writable(config.accessType) || areas.isEmpty()) return@checkDoorLockCapabilities finish(ActionState.UNSUPPORTED, "잠금 제어 권한·영역 없음")
            batch(areas.size, finish) { ok, fail ->
                areas.forEach { a -> api.setDoorLock(a, false, onComplete = { _, locked -> if (locked == false) ok() else fail("잠김 유지") },
                    onFailed = { e -> fail(reason(e)) }) }
            }
        }, onFailed = { finish(ActionState.FAILED, "잠금 지원 조회 실패") })
    }

    fun openTrunk(done: (ActionResult) -> Unit) = control(done) { v, finish ->
        val api = v.getDoor()
        api.checkDoorPositionCapabilities(onComplete = { config, bounds ->
            val rear = bounds.entries.firstOrNull { DoorValue.REAR in it.key.values }
            if (!writable(config.accessType) || rear == null) return@checkDoorPositionCapabilities finish(ActionState.UNSUPPORTED, "트렁크 제어 영역 없음")
            api.setDoorPosition(rear.key, rear.value.maxValue, onComplete = { _, p -> finish(ActionState.DONE, "트렁크 위치 $p") },
                onFailed = { e -> finish(ActionState.FAILED, reason(e)) })
        }, onFailed = { finish(ActionState.FAILED, "문 지원 조회 실패") })
    }

    fun openWindows(done: (ActionResult) -> Unit) = control(done) { v, finish ->
        val api = v.getWindow()
        val side = setOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
        api.checkWindowPositionCapabilities(onComplete = { config, bounds ->
            val targets = bounds.entries.filter { e -> e.key.values.any { it in side } }
            if (!writable(config.accessType) || targets.isEmpty()) return@checkWindowPositionCapabilities finish(ActionState.UNSUPPORTED, "창문 제어 영역 없음")
            batch(targets.size, finish) { ok, fail ->
                targets.forEach { e ->
                    api.setWindowPosition(e.key, e.value.maxValue, onComplete = { _, _ -> ok() }, onFailed = { x -> fail(reason(x)) })
                }
            }
        }, onFailed = { finish(ActionState.FAILED, "창문 지원 조회 실패") })
    }

    private fun control(done: (ActionResult) -> Unit, block: (Vehicle, (ActionState, String) -> Unit) -> Unit) {
        val token = generation
        var finished = false
        val finish: (ActionState, String) -> Unit = { s, d ->
            handler.post { if (!finished && token == generation) { finished = true; done(ActionResult(s, System.currentTimeMillis(), d)) } }
        }
        handler.postDelayed({ finish(ActionState.FAILED, "차량 응답 없음 (8초)") }, 8_000)
        val v = vehicle ?: return finish(ActionState.FAILED, "차량 연결 없음")
        try { block(v, finish) } catch (e: Exception) { finish(ActionState.FAILED, reason(e)) }
    }

    private fun batch(count: Int, finish: (ActionState, String) -> Unit, run: (ok: () -> Unit, fail: (String) -> Unit) -> Unit) {
        var good = 0; var bad = 0; val reasons = mutableListOf<String>()
        val settle = {
            if (good + bad == count) finish(when { bad == 0 -> ActionState.DONE; good == 0 -> ActionState.FAILED; else -> ActionState.PARTIAL },
                "성공 $good / 요청 $count" + if (reasons.isEmpty()) "" else " · " + reasons.distinct().joinToString())
        }
        run({ synchronized(this) { good++; settle() } }, { r -> synchronized(this) { bad++; reasons += r; settle() } })
    }

    /** Vehicle-side rejection codes come back as "...Exception: 50001"; show them as a plain refusal with the code. */
    private fun reason(e: Exception): String {
        val code = Regex("""\b(\d{5})\b""").find(e.toString())?.value
        return if (code != null) "차량이 거절함 (코드 $code)" else "요청 실패 (${e.javaClass.simpleName})"
    }

    fun stop() {
        generation++
        handler.removeCallbacksAndMessages(null)
        cleanups.forEach { it() }; cleanups.clear()
        try { vehicle?.release() } catch (_: Exception) { }
        vehicle = null
    }

    companion object {
        val WATCHED = mapOf(
            TelltaleType.AIRBAG to "에어백 경고등", TelltaleType.MASTER_WARNING to "마스터 경고",
            TelltaleType.ENGINE_TEMPERATURE to "엔진 과열", TelltaleType.ENGINE_OIL_PRESSURE to "엔진 오일 압력",
            TelltaleType.H2_LEAK to "수소 누출", TelltaleType.EV_SYSTEM to "EV 시스템", TelltaleType.HEV_SYSTEM to "하이브리드 시스템",
            TelltaleType.FCEV_SYSTEM to "수소전기 시스템", TelltaleType.POWER_DOWN to "출력 제한", TelltaleType.LOW_VOLT_BATTERY to "저전압 배터리",
            TelltaleType.EMERGENCY_CALL to "긴급 호출", TelltaleType.ABS to "ABS", TelltaleType.MDPS to "조향 보조(MDPS)",
        )
    }
}
