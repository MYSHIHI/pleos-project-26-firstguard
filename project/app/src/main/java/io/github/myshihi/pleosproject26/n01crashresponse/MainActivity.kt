package io.github.myshihi.pleosproject26.n01crashresponse

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class MainActivity : Activity() {
    private enum class Tab(val label: String) { STATUS("대응 현황"), PEOPLE("탑승자"), CARD("신고 카드"), LOG("사건 기록") }
    private enum class Overlay { NONE, TOOLS, END, HISTORY }

    private lateinit var ui: Ui
    private lateinit var store: IncidentStore
    private val handler = Handler(Looper.getMainLooper())
    private val engine = IncidentEngine()
    private val snap = VehicleSnapshot()
    private lateinit var vehicle: VehicleLink
    private lateinit var speed: SpeedSource
    private lateinit var location: LocationSource
    private lateinit var voice: VoiceGuide
    private val hardStop = HardStopDetector()
    private val recentPeak = RecentPeak()
    private var tab = Tab.STATUS
    private var overlay = Overlay.NONE
    private var historyItem: Incident? = null
    private var sampleWarning = false
    private var locationStatus = "확인 중"
    private var voiceStatus = "준비 중"
    private val lastDoor = mutableMapOf<Seat, Boolean?>()
    private val lastSeat = mutableMapOf<Seat, Boolean?>()
    private var automating = false

    private lateinit var root: FrameLayout
    private lateinit var topVehicle: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var overlayHost: FrameLayout
    private var signalsBox: LinearLayout? = null
    private var elapsedView: TextView? = null
    private var countdownView: TextView? = null
    private var contentScroll: ScrollView? = null
    private var refreshPending = false
    private var shownSignature = ""
    private var started = false

    private fun vehicleSignature() = listOf(snap.gear, snap.speedKph?.let { "%.0f".format(it) }, snap.speedStatus, snap.warnings,
        snap.telltaleStatus, snap.airbagStatus, snap.airbag, snap.seatStatus, snap.seats.mapValues { it.value.on to it.value.note },
        snap.doorStatus, snap.doorOpen, snap.lockStatus, snap.doorLocked, snap.gearStatus, locationStatus, location.latest).toString()

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private fun now() = System.currentTimeMillis()
    private fun time(at: Long) = if (at <= 0) "-" else clock.format(Date(at))

    private val tick = object : Runnable {
        override fun run() {
            val now = now()
            if (engine.checkResponseTimeout(now)) commit()
            else engine.active?.let { i ->
                setIfChanged(elapsedView, "사고 후 " + elapsed(now - i.startedAt))
                setIfChanged(countdownView, ceil((i.responseDeadline - now).coerceAtLeast(0) / 1000.0).toInt().toString())
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = IncidentStore(this)
        val (active, history) = store.load()
        engine.restore(active, history)
        if (active != null) { engine.reaskAfterRestart(now()); store.save(engine) }
        snap.invertSeats = store.invertSeats
        vehicle = VehicleLink(this, snap, ::onVehicleChanged, ::onAirbag)
        speed = SpeedSource(this) { kph, status -> onSpeed(kph, status) }
        location = LocationSource(this) { _, status -> locationStatus = status; onLocation() }
        buildShell()
        render()
        voice = VoiceGuide(this) { s -> handler.post { voiceStatus = s; if (engine.active != null) render() } }
        registerBack()
        val missing = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 7)
    }

    override fun onStart() {
        super.onStart()
        started = true
        vehicle.start()
        startSensors()
        handler.post(tick)
    }

    private fun startSensors() {
        if (checkSelfPermission(SPEED) == PackageManager.PERMISSION_GRANTED) speed.start()
        else snap.speedStatus = "속도 권한 없음 · 급정지 감지 꺼짐"
        if (checkSelfPermission(LOCATION) == PackageManager.PERMISSION_GRANTED) location.start() else locationStatus = "위치 권한 없음"
        refreshVehicle()
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(tick)
        vehicle.stop()
        speed.stop()
        location.stop()
        store.save(engine)
        super.onStop()
    }

    override fun onDestroy() { voice.release(); super.onDestroy() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (started) { speed.stop(); location.stop(); startSensors() }
    }

    /** Back closes a dialog first; otherwise it leaves the app without finishing it (the incident stays open and saved). */
    private fun registerBack() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (overlay != Overlay.NONE) { overlay = Overlay.NONE; historyItem = null; render() } else moveTaskToBack(true)
            }
        }
    }

    // ---- Vehicle events ----------------------------------------------------------------------------

    private fun onVehicleChanged() {
        val acked = store.ackedAirbags
        val cleared = acked.filter { snap.airbag[it] == false }
        if (cleared.isNotEmpty()) store.ackedAirbags = acked - cleared.toSet()
        val i = engine.active
        val before = i?.log?.size
        if (i != null) {
            val now = now()
            Seat.entries.forEach { s ->
                val door = snap.doorOpen[s]
                if (door == true && lastDoor[s] != true) engine.doorOpened(s, now)
                val seat = snap.seats[s]?.on
                if (s in lastSeat && seat != lastSeat[s]) engine.seatChanged(s, seat, now)
            }
            engine.warnings(snap.warnings, now)
        }
        Seat.entries.forEach { s -> lastDoor[s] = snap.doorOpen[s]; if (snap.seats[s] != null) lastSeat[s] = snap.seats[s]?.on }
        if (i != null && (automate() || i.log.size != before)) commit() else refreshVehicle()
    }

    private fun onAirbag(seat: Seat) {
        val acked = store.ackedAirbags
        if (seat in acked) return
        store.ackedAirbags = acked + seat
        if (engine.active != null) { engine.vehicleFactor(Factor.AIRBAG, Source.VEHICLE, now(), "${seat.label} 에어백"); commit() }
        else startIncident(Trigger.AIRBAG, Source.VEHICLE, "${seat.label} 에어백 전개 신호")
    }

    private fun onSpeed(kph: Float?, status: String) {
        snap.speedKph = kph
        snap.speedStatus = status
        if (kph != null) {
            val t = SystemClock.elapsedRealtime()
            recentPeak.feed(t, kph)
            hardStop.feed(t, kph)?.let { peak -> onHardStop(peak, Source.VEHICLE) }
        }
        refreshVehicle()
    }

    private fun onLocation() {
        val i = engine.active
        if (i != null && automate()) commit() else refreshVehicle()
    }

    private fun onHardStop(peak: Float, source: Source) {
        val detail = "약 ${peak.toInt()}km/h → 정지"
        if (engine.active != null) { engine.vehicleFactor(Factor.HARD_STOP, source, now(), detail); commit() }
        else startIncident(Trigger.HARD_STOP, source, detail, peak)
    }

    private fun startIncident(trigger: Trigger, source: Source, detail: String, peakKph: Float? = null) {
        val now = now()
        val place = location.latest ?: if (store.sampleLocation) Place(SAMPLE_PLACE, Source.SAMPLE) else null
        val peak = listOfNotNull(peakKph, recentPeak.peak(SystemClock.elapsedRealtime())).maxOrNull()
        val warnings = snap.warnings.ifEmpty { if (sampleWarning) listOf("에어백 경고등 (샘플)") else emptyList() }
        val opened = engine.start(trigger, source, now, snap.seats.toMap(), place, detail, StartContext(peak, warnings))
        if (opened) { voice.reset(); tab = Tab.STATUS }
        overlay = Overlay.NONE
        commit()
    }

    private fun commit() {
        automate()
        store.save(engine)
        render()
        speak()
    }

    // ---- Automation ------------------------------------------------------------------------------

    /** Applies everything the app can do by itself. Returns true when something changed. */
    private fun automate(): Boolean {
        if (automating) return false
        val i = engine.active ?: return false
        automating = true
        val before = i.log.size
        try {
            val now = now()
            location.latest?.let { p -> if (i.place == null || i.place?.source == Source.SAMPLE) engine.setPlace(p, now) }
            engine.autoContacts(now).forEach { scheduleMockAck(it) }
            if (i.response != Response.WAITING || i.trigger == Trigger.AIRBAG) {
                IncidentEngine.assess(i).carPlan.forEach { (a, reason) ->
                    val st = i.actions[a]?.state
                    if (st == null || st == ActionState.WAITING) {
                        if (snap.stopped) runCar(a, auto = true)
                        else engine.carAction(a, ActionResult(ActionState.WAITING, now, "$reason · ${snap.stopText}"), auto = true)
                    }
                }
            }
        } finally { automating = false }
        return i.log.size != before
    }

    private fun speak() {
        val i = engine.active ?: return
        val id = i.id
        when (i.response) {
            Response.WAITING -> voice.say("ask-$id", (if (i.trigger == Trigger.HARD_STOP) "급정지가 감지됐어요. " else "사고 신호를 받았어요. ") +
                "괜찮으시면 괜찮아요, 도움이 필요하면 도움이 필요해요를 눌러 주세요.", interrupt = true)
            Response.NONE -> voice.say("none-$id", "응답이 없어 긴급 대응을 시작합니다. 119에 사고 정보를 보냈습니다. 모의 전송입니다.")
            else -> Unit
        }
        if (i.response == Response.WAITING) return
        if (i.actions[CarAction.UNLOCK]?.state == ActionState.DONE) voice.say("unlock-$id", "문 잠금을 풀었습니다.")
        if (i.actions[CarAction.TRUNK]?.state == ActionState.DONE) voice.say("trunk-$id", "트렁크를 열었습니다.")
        IncidentEngine.assess(i).steps.filter { it.kind != StepKind.RECORD }.take(3).forEach { s -> voice.say("step-$id-${s.id}", s.title) }
    }

    private fun scheduleMockAck(a: Agency) {
        val id = engine.active?.id ?: return
        handler.postDelayed({
            val i = engine.active
            if (i?.id == id && i.contact(a).state == ContactState.MOCK_SENT) {
                engine.contact(a, ContactState.MOCK_ACK, now(), "모의 기관 응답 · 실제 접수 아님")
                commit()
            }
        }, 2_000)
    }

    // ---- Manual actions (kept for overrides) -------------------------------------------------------

    private fun dial(a: Agency) {
        val number = a.number ?: return
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        if (intent.resolveActivity(packageManager) == null) {
            engine.active?.note(now(), Source.APP, "전화 앱 없음 · 휴대폰으로 ${number}에 직접 연락하세요")
            commit(); return
        }
        engine.contact(a, ContactState.DIAL_OPENED, now(), "tel:$number")
        commit()
        try { startActivity(intent) } catch (_: Exception) {
            engine.active?.note(now(), Source.APP, "전화 화면을 열지 못함 · 휴대폰으로 연락하세요"); commit()
        }
    }

    private fun runCar(a: CarAction, auto: Boolean = false) {
        val now = now()
        if (!snap.stopped) {
            engine.carAction(a, ActionResult(ActionState.BLOCKED, now, snap.stopText), auto)
            if (!auto) commit()
            return
        }
        engine.carAction(a, ActionResult(ActionState.REQUESTED, now, snap.stopText), auto)
        if (!auto) commit()
        val id = engine.active?.id
        val done: (ActionResult) -> Unit = { r -> if (engine.active?.id == id) { engine.carAction(a, r); commit() } }
        when (a) {
            CarAction.UNLOCK -> vehicle.unlockDoors(done)
            CarAction.TRUNK -> vehicle.openTrunk(done)
            CarAction.WINDOWS -> vehicle.openWindows(done)
        }
    }

    // ---- Shell -------------------------------------------------------------------------------------

    private fun buildShell() {
        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(28), ui.dp(14), ui.dp(28), ui.dp(14))
            background = ui.bg(C.PANEL, 0)
        }
        top.addView(ui.row(Gravity.CENTER_VERTICAL, ui.text("FIRST GUARD", 26f, C.RED, bold = true), ui.space(14),
            ui.text("퍼스트가드 · 사고 초동대응", 20f, C.MUTED)))
        top.addView(ui.space(), LinearLayout.LayoutParams(0, 1, 1f))
        topVehicle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(topVehicle)
        top.addView(ui.space(18))
        top.addView(ui.button("시연 도구", C.AMBER, outline = true, size = 18f) { overlay = Overlay.TOOLS; render() }.apply { minHeight = ui.dp(52) })
        column.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body = FrameLayout(this)
        column.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlayHost = FrameLayout(this)
        root.addView(overlayHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
    }

    private fun render() {
        val keepScroll = contentScroll?.scrollY ?: 0
        val keepTab = tab
        signalsBox = null; elapsedView = null; countdownView = null; contentScroll = null
        body.removeAllViews()
        val i = engine.active
        body.addView(if (i == null) idleView() else incidentView(i), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        renderOverlay(i)
        refreshVehicle(now = true)
        contentScroll?.let { s -> if (keepTab == tab) s.post { s.scrollTo(0, keepScroll) } }
    }

    private fun refreshVehicle(now: Boolean = false) {
        if (!now) {
            if (refreshPending) return
            refreshPending = true
            handler.postDelayed({ refreshPending = false; if (vehicleSignature() != shownSignature) refreshVehicle(true) }, 250)
            return
        }
        shownSignature = vehicleSignature()
        topVehicle.removeAllViews()
        val gear = snap.gear
        topVehicle.addView(ui.chip("기어 ${gear ?: "?"}", if (gear == null) C.MUTED else C.BLUE, 17f))
        topVehicle.addView(ui.space(10))
        topVehicle.addView(ui.chip(snap.speedKph?.let { "속도 ${"%.0f".format(it)} km/h" } ?: "속도 미수신", if (snap.speedKph == null) C.MUTED else C.BLUE, 17f))
        topVehicle.addView(ui.space(10))
        topVehicle.addView(ui.chip(if (location.latest != null) "위치 수신" else "위치 없음", if (location.latest != null) C.BLUE else C.MUTED, 17f))
        topVehicle.addView(ui.space(10))
        val warn = snap.warnings.isNotEmpty()
        topVehicle.addView(ui.chip(if (warn) "경고등 ${snap.warnings.size}" else "경고등 없음", if (warn) C.AMBER else C.MUTED, 17f))
        signalsBox?.let { box -> box.removeAllViews(); signalRows(box) }
    }

    // ---- Idle -------------------------------------------------------------------------------------

    private fun idleView(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(28), ui.dp(24), ui.dp(28), ui.dp(24)) }
        val left = ui.column()
        val hero = ui.card(C.PANEL, 32)
        hero.addView(ui.text("대기 중 · 사고 신호를 지켜보고 있어요", 32f, bold = true))
        hero.addView(ui.text("에어백 전개나 급정지를 감지하면 스스로 사건을 열고, 한 번만 괜찮은지 물은 뒤 나머지는 자동으로 처리해요.", 20f, C.MUTED), ui.lp(top = 8))
        val sos = TextView(this).apply {
            text = "SOS\n사고 대응 시작"
            textSize = 34f * ui.scale; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.RED); setStroke(ui.dp(14), C.alpha(C.RED, 0.3f)) }
            setOnClickListener { startIncident(Trigger.MANUAL_SOS, Source.USER, "화면 버튼") }
        }
        val sosRow = ui.row(Gravity.CENTER_VERTICAL)
        sosRow.addView(sos, LinearLayout.LayoutParams(ui.dp(290), ui.dp(290)))
        sosRow.addView(ui.column(
            ui.text("자동으로 하는 일", 22f, C.TEXT, bold = true),
            ui.text("탑승자·도로 상황 판단 · 위치 첨부 · 문 잠금 해제 · 트렁크·창문 · 기관에 신고 카드 전송 · 음성 안내 · 사건 기록", 20f, C.MUTED),
            ui.space(h = 10),
            ui.text("119·112 전화는 직접 걸어요. 기관 전송은 모의로만 보여 줘요.", 20f, C.MUTED),
        ), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(36) })
        hero.addView(sosRow, ui.lp(top = 26))
        left.addView(hero)
        val signals = ui.card(C.PANEL, 26)
        signals.addView(ui.text("자동화에 쓰는 차량 신호", 24f, bold = true))
        signals.addView(ui.text("받은 값만 표시해요. 받지 못한 값은 '미수신'으로 남겨요.", 18f, C.MUTED), ui.lp(top = 4, bottom = 10))
        val box = ui.column(); signalsBox = box
        signals.addView(box)
        left.addView(signals, ui.lp(top = 20))
        page.addView(scroll(left), LinearLayout.LayoutParams(0, MATCH_PARENT, 1.35f))

        val right = ui.column()
        val recent = ui.card(C.PANEL, 26)
        recent.addView(ui.text("최근 사건 기록", 24f, bold = true))
        if (engine.history.isEmpty()) recent.addView(ui.text("아직 기록이 없어요.", 20f, C.MUTED), ui.lp(top = 12))
        engine.history.forEach { h ->
            val a = IncidentEngine.assess(h)
            val item = ui.card(C.PANEL2, 18)
            item.addView(ui.row(Gravity.CENTER_VERTICAL, ui.text(h.id, 20f, bold = true), ui.space(12), ui.chip(a.level.label, C.level(a.level), 15f), ui.space(8),
                ui.chip(h.trigger.label + " · " + h.triggerSource.label, C.source(h.triggerSource), 15f)))
            item.addView(ui.text("${dateTime(h.startedAt)} 시작 · ${h.closedAt?.let { "${time(it)} 종료" } ?: ""} · 기록 ${h.log.size}줄", 17f, C.MUTED), ui.lp(top = 6))
            item.setOnClickListener { historyItem = h; overlay = Overlay.HISTORY; render() }
            recent.addView(item, ui.lp(top = 12))
        }
        right.addView(recent)
        val limits = ui.card(C.alpha(C.AMBER, 0.08f), 26, C.alpha(C.AMBER, 0.5f))
        limits.addView(ui.text("이 앱을 쓸 수 없을 때", 22f, C.AMBER, bold = true))
        limits.addView(ui.text("사고로 차량 전원·화면·통신이 끊기면 이 앱이 동작하지 않을 수 있어요. 차량의 기본 SOS 버튼이나 휴대폰으로 119·112에 직접 연락하세요. 화면 조작 때문에 위험한 곳에 머무르지 마세요.", 19f, C.TEXT), ui.lp(top = 8))
        right.addView(limits, ui.lp(top = 20))
        page.addView(scroll(right), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = ui.dp(24) })
        return page
    }

    private fun signalRows(box: LinearLayout) {
        fun line(label: String, value: String, color: Int, status: String) {
            if (engine.active != null) {
                val head = ui.row(Gravity.CENTER_VERTICAL)
                head.addView(ui.text(label, 16f, C.MUTED), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                head.addView(ui.text(status, 14f, C.MUTED, lines = 1))
                box.addView(head, ui.lp(top = 10))
                box.addView(ui.text(value, 18f, color, bold = true), ui.lp(top = 1))
                return
            }
            val r = ui.row(Gravity.CENTER_VERTICAL)
            r.addView(ui.text(label, 19f, C.MUTED), LinearLayout.LayoutParams(ui.dp(150), WRAP_CONTENT))
            r.addView(ui.text(value, 19f, color, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.addView(ui.text(status, 16f, C.MUTED))
            box.addView(r, ui.lp(top = 8))
        }
        val deployed = snap.airbag.filterValues { it == true }.keys
        line("에어백 전개", if (deployed.isEmpty()) (if (snap.airbagSeats.isEmpty()) "신호 없음" else "전개 신호 없음") else "전개: " + deployed.joinToString { it.label },
            if (deployed.isEmpty()) C.TEXT else C.RED, snap.airbagStatus)
        line("급정지 징후", snap.speedKph?.let { "속도 ${"%.0f".format(it)} km/h · 감지 켜짐" } ?: "속도 미수신 · 감지 꺼짐",
            if (snap.speedKph == null) C.MUTED else C.TEXT, snap.speedStatus)
        line("차량 경고등", if (snap.warnings.isEmpty()) "켜진 주요 경고 없음" else snap.warnings.joinToString(), if (snap.warnings.isEmpty()) C.TEXT else C.AMBER, snap.telltaleStatus)
        line("좌석(탑승 추정)", Seat.entries.joinToString("  ") { "${it.label} ${seatShort(snap.seats[it])}" }, C.TEXT, snap.seatStatus)
        val open = Seat.entries.filter { snap.doorOpen[it] == true }
        line("문 열림(하차 감지)", if (snap.doorOpen.isEmpty()) "미수신" else if (open.isEmpty()) "열린 문 없음" else "열림: " + open.joinToString { it.label },
            if (open.isEmpty()) C.TEXT else C.AMBER, snap.doorStatus)
        val locked = snap.doorLocked.values.count { it == true }; val unlocked = snap.doorLocked.values.count { it == false }
        line("문 잠금", if (snap.doorLocked.isEmpty()) "미수신" else "잠김 $locked · 해제 $unlocked", C.TEXT, snap.lockStatus)
        line("기어", snap.gear ?: "미수신", C.TEXT, snap.gearStatus)
        line("위치", location.latest?.text ?: "미수신", if (location.latest == null) C.MUTED else C.TEXT, locationStatus)
    }

    private fun seatShort(r: SeatRef?) = when { r == null -> "미수신"; r.on == true -> "ON"; r.on == false -> "OFF"; else -> "값 없음" }
    private fun seatLong(r: SeatRef?) = when {
        r == null -> "미수신"
        r.on == null -> "값 없음" + if (r.note.isBlank()) "" else " (${r.note})"
        else -> "신호 ${if (r.on) "ON" else "OFF"} · ${time(r.at)}"
    }

    // ---- Incident ---------------------------------------------------------------------------------

    private fun incidentView(i: Incident): View {
        val a = IncidentEngine.assess(i)
        val color = C.level(a.level)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(18)) }

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.bg(C.alpha(color, 0.12f), 22, C.alpha(color, 0.7f), 2)
            setPadding(ui.dp(26), ui.dp(18), ui.dp(26), ui.dp(18))
        }
        banner.addView(ui.column(
            ui.row(Gravity.CENTER_VERTICAL, ui.chip("대응 단계 · 자동 판단", color, 15f), ui.space(8), ui.chip(i.trigger.label + " · " + i.triggerSource.label, C.source(i.triggerSource), 15f)),
            ui.text(a.level.label, 40f, color, bold = true).apply { setPadding(0, ui.dp(6), 0, 0) },
            ui.text(a.level.hint, 19f, C.TEXT),
        ), LinearLayout.LayoutParams(ui.dp(420), WRAP_CONTENT))
        val reasons = FlowLayout(this, ui.dp(8), ui.dp(8))
        reasons.addView(ui.text("판단 근거", 16f, C.MUTED).apply { setPadding(0, ui.dp(6), ui.dp(4), 0) })
        a.reasons.forEach { r -> reasons.addView(ui.chip(r, if (r.contains("미확인") || r.contains("기다리는") || r.contains("신호 없음")) C.BLUE else color, 16f)) }
        banner.addView(reasons, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(16); marginEnd = ui.dp(16) })
        val side = ui.column()
        side.gravity = Gravity.END
        elapsedView = ui.text("사고 후 " + elapsed(now() - i.startedAt), 24f, C.TEXT, bold = true).apply { gravity = Gravity.END }
        side.addView(elapsedView)
        side.addView(ui.text("${i.id} · ${time(i.startedAt)} 시작", 16f, C.MUTED).apply { gravity = Gravity.END })
        val btns = ui.row(Gravity.END)
        btns.addView(ui.button("119 전화 화면", C.RED, Color.WHITE, 19f) { dial(Agency.RESCUE) })
        btns.addView(ui.space(10))
        btns.addView(ui.button("사건 종료", C.MUTED, outline = true, size = 19f) { overlay = Overlay.END; render() })
        side.addView(btns, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 10))
        banner.addView(side)
        page.addView(banner)

        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(nav(i), LinearLayout.LayoutParams(ui.dp(230), MATCH_PARENT))
        val content = ui.column()
        when (tab) {
            Tab.STATUS -> status(i, a, content)
            Tab.PEOPLE -> people(i, content)
            Tab.CARD -> content.addView(reportCard(i))
            Tab.LOG -> log(i, content)
        }
        val sc = scroll(content); contentScroll = sc
        main.addView(sc, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = ui.dp(20); marginEnd = ui.dp(20) })
        main.addView(scroll(situation(i)), LinearLayout.LayoutParams(ui.dp(400), MATCH_PARENT))
        page.addView(main, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = ui.dp(16) })
        return page
    }

    private fun nav(i: Incident): View {
        val col = ui.column()
        Tab.entries.forEach { t ->
            val badge = when (t) {
                Tab.PEOPLE -> "${Seat.entries.count { i.onBoard(it) && i.people[it] != PersonState.EVACUATED }}명 차 안"
                Tab.LOG -> "${i.log.size}"
                else -> null
            }
            val selected = t == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(20), ui.dp(18), ui.dp(16), ui.dp(18))
                background = if (selected) ui.bg(C.PANEL2, 16, C.alpha(C.TEXT, 0.25f), 1) else ui.bg(Color.TRANSPARENT, 16)
                setOnClickListener { tab = t; render() }
            }
            item.addView(ui.text(t.label, 21f, if (selected) C.TEXT else C.MUTED, bold = selected), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (badge != null) item.addView(ui.chip(badge, C.MUTED, 14f))
            col.addView(item, ui.lp(bottom = 6))
        }
        return col
    }

    private fun header(box: LinearLayout, title: String, note: String) {
        box.addView(ui.text(title, 28f, bold = true))
        box.addView(ui.text(note, 18f, C.MUTED), ui.lp(top = 4, bottom = 14))
    }

    private fun status(i: Incident, a: Assessment, box: LinearLayout) {
        header(box, "직접 하실 일", "몸으로 해야 하는 일만 남겼어요. 확인 버튼을 누를 필요 없고, 나머지는 앱이 자동으로 처리해요.")
        a.steps.forEachIndexed { n, s ->
            val kindColor = when (s.kind) { StepKind.EVACUATE -> C.RED; StepKind.CALL -> C.VIOLET; StepKind.CAR -> C.AMBER; StepKind.RECORD -> C.GREEN }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = ui.bg(C.PANEL, 20, if (n == 0) C.alpha(kindColor, 0.8f) else null, 2)
                setPadding(ui.dp(22), ui.dp(16), ui.dp(22), ui.dp(16))
            }
            val num = ui.text("${n + 1}", 24f, C.BG, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(kindColor) }
            }
            card.addView(num, LinearLayout.LayoutParams(ui.dp(50), ui.dp(50)))
            card.addView(ui.column(ui.text(s.title, 25f, bold = true), ui.text(s.detail, 18f, C.MUTED)),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(20); marginEnd = ui.dp(12) })
            s.agency?.number?.let { num2 -> card.addView(ui.button("전화 화면 열기 $num2", C.RED, Color.WHITE, 18f) { dial(s.agency) }) }
            box.addView(card, ui.lp(bottom = 10))
        }

        box.addView(ui.text("앱이 자동으로 처리한 일", 28f, bold = true), ui.lp(top = 18))
        box.addView(ui.text("차량 신호로 판단하고 실행한 결과예요. 판단이 틀리면 오른쪽 '상황 판단'이나 탑승자 탭에서 고칠 수 있어요.", 18f, C.MUTED), ui.lp(top = 4, bottom = 12))
        val grid = ui.card(C.PANEL, 10)
        fun row(title: String, value: String, color: Int, detail: String = "", button: TextView? = null) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
            val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }
            r.addView(dot, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)))
            r.addView(ui.text(title, 19f, C.MUTED), LinearLayout.LayoutParams(ui.dp(190), WRAP_CONTENT).apply { marginStart = ui.dp(14) })
            r.addView(ui.column(ui.text(value, 20f, C.TEXT, bold = true), *(if (detail.isBlank()) emptyArray<View>() else arrayOf<View>(ui.text(detail, 16f, C.MUTED)))),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (button != null) { button.minHeight = ui.dp(50); r.addView(button) }
            grid.addView(r)
        }
        val ok = C.GREEN; val wait = C.AMBER; val off = C.MUTED
        row("사고 감지", "${i.trigger.label} · ${i.triggerSource.label}", ok, "${time(i.startedAt)} · 사건을 자동으로 열고 기록 시작")
        row("탑승자 응답", i.response.label, when (i.response) { Response.OK -> ok; Response.WAITING -> wait; Response.NOT_ASKED -> off; else -> C.RED },
            "한 번의 응답을 차 안의 탑승자 모두에게 적용")
        val present = Seat.entries.filter { i.onBoard(it) && i.people[it] != PersonState.EVACUATED }
        val out = Seat.entries.filter { i.people[it] == PersonState.EVACUATED }
        val unknown = Seat.entries.filter { i.people[it] == PersonState.UNCHECKED }
        row("탑승자 파악", "차 안 ${present.size}명" + (if (out.isNotEmpty()) " · 하차 ${out.size}명" else "") + (if (unknown.isNotEmpty()) " · 신호 없음 ${unknown.size}석" else ""),
            if (unknown.isEmpty()) ok else wait, "좌석 신호로 추정, 문이 열리고 좌석이 비면 하차로 판단")
        val auto = i.factors.filter { it.value == Source.APP || it.value == Source.VEHICLE }.keys.filter { it in Factor.ADJUSTABLE || it == Factor.WARNING_LAMP }
        row("도로·위험 판단", auto.joinToString { it.label }.ifBlank { "특이 신호 없음" }, if (auto.isEmpty()) off else ok,
            auto.mapNotNull { f -> i.factorNotes[f]?.let { "${f.label}: $it" } }.joinToString(" · "))
        row("위치 첨부", i.place?.text ?: "미확인", when (i.place?.source) { Source.VEHICLE -> ok; null -> off; else -> wait },
            i.place?.let { if (it.source == Source.VEHICLE) "차량 위치 수신 · 신고 카드에 자동 첨부" else "${it.source.label} · 실측 아님" } ?: locationStatus)
        CarAction.entries.forEach { ca ->
            val r = i.actions[ca]
            val planned = a.carPlan[ca]
            val color = when (r?.state) { ActionState.DONE -> ok; ActionState.WAITING, ActionState.REQUESTED, ActionState.PARTIAL -> wait; null -> off; else -> C.RED }
            val btn = when {
                r?.state in setOf(ActionState.FAILED, ActionState.BLOCKED, ActionState.UNSUPPORTED, ActionState.PARTIAL) ->
                    ui.button("다시 요청", C.BLUE, outline = true, size = 16f) { runCar(ca) }
                r == null && planned == null -> ui.button("지금 요청", C.BLUE, outline = true, size = 16f) { runCar(ca) }
                else -> null
            }
            row(ca.label, r?.state?.label ?: if (planned == null) "지금 상황에선 하지 않음" else "곧 자동 실행", color,
                listOfNotNull(planned, r?.detail?.takeIf { it.isNotBlank() }).joinToString(" · "), btn)
        }
        Agency.entries.forEach { ag ->
            val c = i.contact(ag)
            val need = a.needs[ag] ?: Need.LATER
            val color = when (c.state) { ContactState.MOCK_ACK, ContactState.CALLED -> ok; ContactState.NONE -> if (need == Need.NOW) wait else off; else -> wait }
            val value = if (c.state == ContactState.NONE) (if (need == Need.NOW) "응답 확인 후 자동 전송" else "지금 상황에선 보내지 않음") else c.state.label
            val btn = if (c.state == ContactState.NONE && need != Need.NOW)
                ui.button("모의 전송", C.VIOLET, outline = true, size = 16f) { engine.contact(ag, ContactState.MOCK_SENT, now(), "직접 요청"); scheduleMockAck(ag); commit() } else null
            row(ag.label, value + if (c.at > 0) " · ${time(c.at)}" else "", color, "신고 카드(위치·탑승자·위험·조치) 전달 · 모의", btn)
        }
        row("음성 안내", voiceStatus, if (voiceStatus.contains("켜짐")) ok else off, "질문과 할 일을 소리로 알려 줘요")
        row("사건 기록", "자동 저장 ${i.log.size}줄", ok, "앱을 다시 열어도 이어져요")
        box.addView(grid)
    }

    private fun people(i: Incident, box: LinearLayout) {
        header(box, "탑승자", "좌석 신호로 자동 추정했어요(짐도 '탑승'으로 보일 수 있어요). 응답·하차도 자동으로 반영되고, 틀렸을 때만 고치세요.")
        Seat.entries.chunked(2).forEach { pair ->
            val r = ui.row(Gravity.TOP)
            pair.forEachIndexed { idx, seat ->
                val st = i.people[seat] ?: PersonState.UNCHECKED
                val src = i.peopleSource[seat]
                val card = ui.card(C.PANEL, 22, C.alpha(C.person(st), 0.6f))
                card.addView(ui.row(Gravity.CENTER_VERTICAL, ui.text(seat.alias, 26f, bold = true), ui.space(10), ui.text(seat.label + " · 가상 별칭", 17f, C.MUTED)))
                card.addView(ui.row(Gravity.CENTER_VERTICAL, ui.chip(st.label, C.person(st), 20f, filled = true), ui.space(10),
                    ui.chip(when (src) { Source.VEHICLE -> "좌석·문 신호로 자동"; Source.USER -> "직접 고침"; Source.APP -> "응답으로 자동 반영"; else -> "신호 없음" }, C.source(src ?: Source.APP), 15f)),
                    ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 10))
                card.addView(ui.text("사고 전 좌석  " + seatLong(i.seatsBefore[seat]) + "   ·   지금  " + seatShort(snap.seats[seat]), 17f, C.MUTED), ui.lp(top = 12))
                val choices = FlowLayout(this, ui.dp(8), ui.dp(8))
                choices.addView(ui.text("고치기", 16f, C.MUTED).apply { setPadding(0, ui.dp(12), ui.dp(4), 0) })
                listOf(PersonState.NEEDS_HELP, PersonState.ANSWERED_OK, PersonState.EVACUATED, PersonState.NOT_ON_BOARD).forEach { p ->
                    choices.addView(ui.button(p.label, C.person(p), outline = p != st, size = 15f) { engine.setPerson(seat, p, now()); commit() }.apply { minHeight = ui.dp(46) })
                }
                card.addView(choices, ui.lp(top = 12))
                r.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (idx == 0) marginEnd = ui.dp(14) })
            }
            box.addView(r, ui.lp(bottom = 14))
        }
    }

    private fun reportCard(i: Incident): View {
        val a = IncidentEngine.assess(i)
        val card = ui.card(0xFFF6F7F9.toInt(), 30)
        fun dark(s: String, size: Float = 19f, bold: Boolean = false, color: Int = 0xFF1A1F27.toInt()) = ui.text(s, size, color, bold)
        card.addView(ui.row(Gravity.CENTER_VERTICAL, dark("사고 신고 카드", 30f, true), ui.space(14), dark(i.id, 20f, color = 0xFF5B6575.toInt())))
        card.addView(dark("차량 신호와 응답으로 자동 작성했어요. 기관 전송은 모의이고, 통화할 때 이 내용을 불러주세요.", 17f, color = 0xFF5B6575.toInt()), ui.lp(top = 4, bottom = 14))
        fun kv(k: String, v: String, tag: String? = null, tagColor: Int = C.AMBER) {
            val r = ui.row(Gravity.TOP)
            r.addView(dark(k, 18f, color = 0xFF5B6575.toInt()), LinearLayout.LayoutParams(ui.dp(170), WRAP_CONTENT))
            r.addView(dark(v, 19f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (tag != null) r.addView(ui.chip(tag, tagColor, 14f, filled = true))
            card.addView(r, ui.lp(top = 10))
            card.addView(View(this).apply { setBackgroundColor(0xFFE1E4EA.toInt()) }, ui.lp(h = 2).apply { topMargin = ui.dp(10) })
        }
        kv("감지·시작", "${dateTime(i.startedAt)} · ${i.trigger.label}", i.triggerSource.label, C.source(i.triggerSource))
        kv("위치", i.place?.text ?: "미확인", i.place?.source?.label ?: "미확인", if (i.place == null) C.MUTED else C.source(i.place!!.source))
        kv("대응 단계", "${a.level.label} · " + a.reasons.joinToString(", "))
        kv("탑승자", Seat.entries.joinToString("\n") { s -> "${s.alias}(${s.label}): ${i.people[s]?.label}" }, i.response.label, C.BLUE)
        kv("위험 요소", i.factors.entries.joinToString("\n") { (f, src) -> "${f.label} (${src.label}${i.factorNotes[f]?.let { " · $it" } ?: ""})" }.ifBlank { "알려진 위험 없음" })
        kv("차량 상태", "기어 ${snap.gear ?: "미수신"} · 경고등 ${snap.warnings.joinToString().ifBlank { "없음" }} · 열린 문 ${Seat.entries.filter { snap.doorOpen[it] == true }.joinToString { it.label }.ifBlank { "없음" }}", "현재값", C.BLUE)
        kv("진행한 조치", i.actions.entries.joinToString("\n") { "${it.key.label}: ${it.value.state.label}" }.ifBlank { "아직 없음" })
        kv("연락 상태", Agency.entries.joinToString("\n") { "${it.label}: ${i.contact(it).state.label}" })
        val foot = FlowLayout(this, ui.dp(8), ui.dp(8))
        if (i.place?.source == Source.SAMPLE) foot.addView(ui.chip("샘플 위치", C.AMBER, 15f, filled = true))
        if (i.factors.values.any { it == Source.APP }) foot.addView(ui.chip("자동 추정 포함", C.BLUE, 15f, filled = true))
        foot.addView(ui.chip("기관 전송은 모의", 0xFF5B6575.toInt(), 15f, filled = true))
        card.addView(foot, ui.lp(top = 16))
        return card
    }

    private fun log(i: Incident, box: LinearLayout) {
        header(box, "사건 기록", "자동으로 저장돼요. 차량 신호·앱 추정·사용자·모의·샘플을 구분해요.")
        i.log.asReversed().forEach { e ->
            val r = ui.row(Gravity.CENTER_VERTICAL)
            r.addView(ui.text(time(e.at), 18f, C.MUTED).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(ui.dp(110), WRAP_CONTENT))
            r.addView(ui.chip(e.source.label, C.source(e.source), 14f), LinearLayout.LayoutParams(ui.dp(110), WRAP_CONTENT))
            r.addView(ui.text(e.text, 19f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(10) })
            box.addView(r, ui.lp(bottom = 10))
        }
    }

    private fun situation(i: Incident): View {
        val col = ui.column()
        val sit = ui.card(C.PANEL, 22)
        sit.addView(ui.text("상황 판단", 24f, bold = true))
        sit.addView(ui.text("차량 신호로 자동 판단해요. 틀렸거나 센서가 모르는 것(화재·대인)만 눌러 고치세요.", 17f, C.MUTED), ui.lp(top = 2, bottom = 8))
        Factor.ADJUSTABLE.forEach { f ->
            val on = i.has(f)
            val src = i.factors[f]
            val color = when (f) { Factor.SAFE_SPOT -> C.GREEN; Factor.HIGHWAY, Factor.IN_LANE, Factor.FUEL_LEAK -> C.AMBER; else -> C.RED }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                background = if (on) ui.bg(C.alpha(color, 0.16f), 14, color, 2) else ui.bg(C.PANEL2, 14)
                setOnClickListener { engine.setFactor(f, !on, now()); commit() }
            }
            row.addView(ui.column(ui.text(f.label, 19f, if (on) C.TEXT else C.MUTED, bold = on),
                *(i.factorNotes[f]?.takeIf { on }?.let { arrayOf<View>(ui.text(it, 15f, C.MUTED)) } ?: emptyArray<View>())),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(ui.chip(when { !on -> "아니요"; src == Source.APP -> "예 · 자동 추정"; src == Source.VEHICLE -> "예 · 차량 신호"; src == Source.SAMPLE -> "예 · 샘플"; else -> "예 · 직접" },
                if (on) color else C.MUTED, 15f, filled = on))
            sit.addView(row, ui.lp(top = 8))
        }
        col.addView(sit)
        val veh = ui.card(C.PANEL, 22)
        veh.addView(ui.text("차량 신호 (지금)", 22f, bold = true))
        val box = ui.column(); signalsBox = box
        veh.addView(box)
        col.addView(veh, ui.lp(top = 14))
        return col
    }

    // ---- Overlays ----------------------------------------------------------------------------------

    private fun renderOverlay(i: Incident?) {
        overlayHost.removeAllViews()
        val view = when {
            i != null && i.response == Response.WAITING -> responseCheck(i)
            overlay == Overlay.TOOLS -> tools()
            overlay == Overlay.END && i != null -> endConfirm()
            overlay == Overlay.HISTORY && historyItem != null -> history(historyItem!!)
            else -> null
        } ?: return
        val dim = FrameLayout(this).apply { setBackgroundColor(C.alpha(Color.BLACK, 0.72f)); isClickable = true }
        dim.addView(view, FrameLayout.LayoutParams(view.layoutParams?.width ?: WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        overlayHost.addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun responseCheck(i: Incident): View {
        val card = ui.card(C.PANEL, 44, C.alpha(C.RED, 0.8f))
        card.layoutParams = FrameLayout.LayoutParams(ui.dp(1000), WRAP_CONTENT)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(ui.chip(i.trigger.label + " · " + i.triggerSource.label, C.source(i.triggerSource), 18f))
        val title = when (i.trigger) { Trigger.HARD_STOP -> "급정지가 감지됐어요\n괜찮으세요?"; Trigger.MANUAL_SOS -> "SOS를 받았어요\n괜찮으세요?"; else -> "사고 신호를 받았어요\n괜찮으세요?" }
        card.addView(ui.text(title, 44f, bold = true).apply { gravity = Gravity.CENTER }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 18))
        val cd = ui.text(ceil((i.responseDeadline - now()).coerceAtLeast(0) / 1000.0).toInt().toString(), 110f, C.RED, bold = true).apply { gravity = Gravity.CENTER }
        countdownView = cd
        card.addView(cd, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 6))
        card.addView(ui.text("이 질문 하나만 답하면 돼요. 응답이 없으면 긴급 대응으로 올리고 신고 카드를 자동으로 보내요(모의).", 20f, C.MUTED).apply { gravity = Gravity.CENTER })
        val btns = ui.row(Gravity.CENTER)
        if (i.trigger == Trigger.HARD_STOP) {
            btns.addView(ui.button("사고 아니에요", C.MUTED, outline = true, size = 26f) { engine.falseAlarm(now()); voice.reset(); commit() }.apply { minHeight = ui.dp(96) })
            btns.addView(ui.space(16))
        }
        btns.addView(ui.button("괜찮아요", C.GREEN, size = 26f) { engine.answer(true, now()); commit() }.apply { minHeight = ui.dp(96); minWidth = ui.dp(260) })
        btns.addView(ui.space(16))
        btns.addView(ui.button("도움이 필요해요", C.RED, Color.WHITE, size = 26f) { engine.answer(false, now()); commit() }.apply { minHeight = ui.dp(96); minWidth = ui.dp(300) })
        card.addView(btns, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 28))
        return card
    }

    private fun tools(): View {
        val card = ui.card(C.PANEL, 34, C.alpha(C.AMBER, 0.7f))
        card.layoutParams = FrameLayout.LayoutParams(ui.dp(820), WRAP_CONTENT)
        card.addView(ui.text("시연 도구", 32f, C.AMBER, bold = true))
        card.addView(ui.text("에뮬레이터에서 재현하기 어려운 신호를 샘플로 넣어요. 샘플은 차량에 쓰지 않고 사건 기록에 '샘플'로 남아요.", 18f, C.MUTED), ui.lp(top = 4, bottom = 10))
        fun item(label: String, note: String, action: () -> Unit) {
            val r = ui.row(Gravity.CENTER_VERTICAL)
            r.addView(ui.column(ui.text(label, 21f, bold = true), ui.text(note, 16f, C.MUTED)), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.addView(ui.button("실행", C.AMBER, size = 18f) { action() })
            card.addView(r, ui.lp(top = 12))
        }
        item("샘플 · 에어백 전개 (운전석)", "에뮬레이터 차량 서비스가 전개 값을 지원하지 않아 샘플로 재현") {
            if (engine.active != null) { engine.vehicleFactor(Factor.AIRBAG, Source.SAMPLE, now(), "운전석 (샘플)"); overlay = Overlay.NONE; commit() }
            else startIncident(Trigger.AIRBAG, Source.SAMPLE, "운전석 에어백 (샘플)")
        }
        item("샘플 · 급정지 징후 (80 → 0 km/h)", "실제 속도 신호로도 감지돼요: 콘솔에서 속도를 높였다가 0으로") { overlay = Overlay.NONE; onHardStop(80f, Source.SAMPLE) }
        item("샘플 · 차량 경고등 ${if (sampleWarning) "끄기" else "켜기"}", "실제 경고등은 콘솔 Safety → Telltales로도 켤 수 있어요") {
            sampleWarning = !sampleWarning
            if (sampleWarning) engine.active?.let { engine.vehicleFactor(Factor.WARNING_LAMP, Source.SAMPLE, now(), "에어백 경고등 (샘플)") }
            commit()
        }
        item("위치가 없을 때 샘플 위치 ${if (store.sampleLocation) "쓰지 않기" else "쓰기"}", "지금: " + if (store.sampleLocation) "차량 위치가 없으면 샘플 위치" else "차량 위치가 없으면 미확인") {
            store.sampleLocation = !store.sampleLocation; render()
        }
        item("좌석 신호 보정 ${if (store.invertSeats) "끄기" else "켜기"}", "에뮬레이터는 콘솔 Occupied가 반대로 들어와요 · 지금: " + if (store.invertSeats) "보정 켜짐" else "보정 꺼짐(SDK 값 그대로)") {
            store.invertSeats = !store.invertSeats; snap.invertSeats = store.invertSeats; snap.seats.clear()
            if (started) { vehicle.stop(); vehicle.start() }
            render()
        }
        item("저장된 사건 모두 지우기", if (engine.active != null) "진행 중인 사건이 있으면 먼저 종료하세요" else "기록 초기화") {
            if (engine.active == null) { store.clear(); engine.restore(null, emptyList()); overlay = Overlay.NONE; commit() }
        }
        card.addView(ui.button("닫기", C.MUTED, outline = true, size = 20f) { overlay = Overlay.NONE; render() }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 20))
        return card
    }

    private fun endConfirm(): View {
        val card = ui.card(C.PANEL, 38)
        card.layoutParams = FrameLayout.LayoutParams(ui.dp(780), WRAP_CONTENT)
        card.addView(ui.text("사건을 종료할까요?", 34f, bold = true))
        card.addView(ui.text("종료해도 구조나 접수가 끝났다는 뜻은 아니에요. 기록은 최근 사건에 남아요.", 20f, C.MUTED), ui.lp(top = 8))
        val btns = ui.row(Gravity.END)
        btns.addView(ui.button("계속 대응", C.GREEN, size = 22f) { overlay = Overlay.NONE; render() })
        btns.addView(ui.space(12))
        btns.addView(ui.button("종료", C.RED, outline = true, size = 22f) { engine.close(now()); voice.reset(); overlay = Overlay.NONE; tab = Tab.STATUS; commit() })
        card.addView(btns, ui.lp(top = 24))
        return card
    }

    private fun history(h: Incident): View {
        val col = ui.column()
        col.layoutParams = FrameLayout.LayoutParams(ui.dp(1100), WRAP_CONTENT)
        val sc = ScrollView(this)
        val inner = ui.column()
        inner.addView(reportCard(h))
        val lg = ui.card(C.PANEL, 24)
        lg.addView(ui.text("사건 기록", 22f, bold = true))
        h.log.forEach { e -> lg.addView(ui.text("${time(e.at)}  [${e.source.label}]  ${e.text}", 17f, C.MUTED), ui.lp(top = 4)) }
        inner.addView(lg, ui.lp(top = 14))
        sc.addView(inner)
        col.addView(sc, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(760)))
        col.addView(ui.button("닫기", C.MUTED, outline = true, size = 20f) { overlay = Overlay.NONE; historyItem = null; render() }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 14))
        return col
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    private fun setIfChanged(v: TextView?, s: String) { if (v != null && v.text.toString() != s) v.text = s }
    private fun scroll(v: View) = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(v) }
    private fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }
    private fun dateTime(at: Long) = SimpleDateFormat("M월 d일 HH:mm:ss", Locale.KOREA).format(Date(at))

    companion object {
        const val SPEED = "android.car.permission.CAR_SPEED"
        const val LOCATION = android.Manifest.permission.ACCESS_FINE_LOCATION
        val PERMISSIONS = listOf(SPEED, LOCATION)
        const val SAMPLE_PLACE = "가상 고속도로 하행 32.5km 지점 부근"
    }
}
