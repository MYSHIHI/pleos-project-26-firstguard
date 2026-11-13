package io.github.myshihi.pleosproject26.n01crashresponse

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Android-free incident rules.
 *
 * Automation first: whatever the vehicle can tell us is filled in automatically (people on board, road type,
 * leak warnings, evacuation), whatever the app can do is done automatically (door unlock, trunk, windows,
 * agency reports), and the occupant is asked for one answer only: "OK" or "need help".
 *
 * Kept honest:
 *  - Every automatic value carries its source (vehicle signal / estimate) and the occupant can overrule it.
 *    Once overruled, automation does not change it back.
 *  - Silence is never read as "safe". Unknown seats stay unknown.
 *  - Agency reports are MOCK. Real calls only through the phone screen the occupant dials from.
 */

enum class Seat(val label: String, val sdkArea: String, val alias: String) {
    FL("운전석", "ROW_1_LEFT", "운전자"),
    FR("조수석", "ROW_1_RIGHT", "동승자 A"),
    RL("뒷좌석 왼쪽", "ROW_2_LEFT", "동승자 B"),
    RR("뒷좌석 오른쪽", "ROW_2_RIGHT", "동승자 C");
}

enum class Source(val label: String) { VEHICLE("차량 신호"), SAMPLE("샘플"), USER("사용자"), APP("앱 추정"), MOCK("모의") }

/** Seat occupancy reference. on == null means "not received" and is never read as an empty seat. */
data class SeatRef(val on: Boolean?, val source: Source, val at: Long, val note: String = "")

enum class PersonState(val label: String) {
    UNCHECKED("미확인"), PRESENT("탑승 · 상태 확인 중"), NOT_ON_BOARD("탑승 안 함"), ANSWERED_OK("괜찮다고 응답"),
    NEEDS_HELP("도움 필요"), NO_RESPONSE("응답 없음"), EVACUATED("하차 감지");
}

enum class Trigger(val label: String) { MANUAL_SOS("수동 SOS"), AIRBAG("에어백 전개 신호"), HARD_STOP("급정지 징후") }

enum class Factor(val label: String) {
    AIRBAG("에어백 전개"),
    HARD_STOP("급정지 징후"),
    WARNING_LAMP("차량 경고등"),
    FIRE("화재·연기"),
    FUEL_LEAK("연료·누유·가스 누출"),
    PEDESTRIAN("다른 사람 부상(대인)"),
    HIGHWAY("고속도로·자동차전용도로"),
    IN_LANE("차로 위에 멈춤"),
    SAFE_SPOT("안전한 곳에 정차");

    companion object { val ADJUSTABLE = listOf(FIRE, FUEL_LEAK, PEDESTRIAN, HIGHWAY, IN_LANE, SAFE_SPOT) }
}

enum class Response(val label: String) {
    NOT_ASKED("묻지 않음"), WAITING("응답 기다리는 중"), OK("괜찮다고 응답"), HELP("도움 요청"), NONE("응답 없음");
}

enum class Agency(val label: String, val role: String, val number: String?) {
    RESCUE("119 구조·구급", "부상·화재·응답 없음·갇힘", "119"),
    POLICE("112 경찰", "대인사고·교통 위험·2차 사고", "112"),
    ROAD("도로 운영기관", "고속도로 사고 처리·통제", null),
    INSURER("보험사·긴급출동", "사고 접수·견인", null);
}

enum class ContactState(val label: String) {
    NONE("보내지 않음"), DIAL_OPENED("전화 화면 열림 · 발신은 직접"), CALLED("통화했다고 표시"),
    MOCK_SENT("신고 카드 모의 전송"), MOCK_ACK("모의 접수 응답");
}

data class ContactStatus(val state: ContactState, val at: Long, val note: String = "")

enum class CarAction(val label: String) { UNLOCK("문 잠금 해제"), TRUNK("트렁크 열기"), WINDOWS("창문 내리기") }

enum class ActionState(val label: String) {
    WAITING("기어 P 확인 후 자동 실행"), REQUESTED("요청 중"), DONE("차량 응답 완료"), PARTIAL("일부 완료"),
    FAILED("실패"), UNSUPPORTED("이 차량에서 지원 안 함"), BLOCKED("정차 확인 안 됨");
}

data class ActionResult(val state: ActionState, val at: Long, val detail: String = "")

data class Place(val text: String, val source: Source)

data class LogEntry(val at: Long, val source: Source, val text: String)

/** What the vehicle told us just before the incident opened. */
data class StartContext(val peakKph: Float? = null, val warnings: List<String> = emptyList())

class Incident(
    val id: String,
    val startedAt: Long,
    val trigger: Trigger,
    val triggerSource: Source,
    /** Seat references captured when the incident opened. Later readings never overwrite them. */
    val seatsBefore: Map<Seat, SeatRef>,
) {
    val people: MutableMap<Seat, PersonState> = Seat.entries.associateWith { PersonState.UNCHECKED }.toMutableMap()
    val peopleSource: MutableMap<Seat, Source> = linkedMapOf()
    val factors: MutableMap<Factor, Source> = linkedMapOf()
    val factorNotes: MutableMap<Factor, String> = linkedMapOf()
    /** Factors the occupant set or cleared by hand; automation leaves them alone afterwards. */
    val userFactors: MutableSet<Factor> = linkedSetOf()
    var response: Response = Response.NOT_ASKED
    var responseDeadline: Long = 0
    val contacts: MutableMap<Agency, ContactStatus> = linkedMapOf()
    val actions: MutableMap<CarAction, ActionResult> = linkedMapOf()
    val doorsOpened: MutableSet<Seat> = linkedSetOf()
    var place: Place? = null
    val log: MutableList<LogEntry> = mutableListOf()
    var closedAt: Long? = null

    fun contact(a: Agency) = contacts[a] ?: ContactStatus(ContactState.NONE, 0)
    fun has(f: Factor) = f in factors
    fun note(at: Long, source: Source, text: String) { log.add(LogEntry(at, source, text)) }
    fun onBoard(s: Seat) = people[s] !in setOf(PersonState.UNCHECKED, PersonState.NOT_ON_BOARD)
}

enum class Level(val label: String, val hint: String) {
    URGENT("긴급 대응", "구조 요청과 대피를 먼저 하세요"),
    CAUTION("주의", "2차 사고를 막고 안전한 곳으로 피하세요"),
    CHECK("확인 중", "앱이 차량 신호와 응답을 확인하고 있어요"),
    MINOR("경미 · 기록 중심", "안전한 곳에서 기록과 접수를 이어가세요");
}

enum class Need(val label: String) { NOW("자동 전송"), SOON("필요하면 전송"), LATER("필요 없음") }

enum class StepKind(val label: String) { EVACUATE("대피"), CALL("통화"), CAR("차량"), RECORD("기록") }

/** Something only a person can do. The app never waits for it to be ticked. */
data class Step(val id: String, val kind: StepKind, val title: String, val detail: String, val agency: Agency? = null)

data class Assessment(
    val level: Level,
    val reasons: List<String>,
    val steps: List<Step>,
    val needs: Map<Agency, Need>,
    val carPlan: Map<CarAction, String>,
)

class IncidentEngine {
    var active: Incident? = null
        private set
    val history: MutableList<Incident> = mutableListOf()

    fun restore(active: Incident?, history: List<Incident>) {
        this.active = active
        this.history.clear()
        this.history.addAll(history.take(MAX_HISTORY))
    }

    /** Opens an incident (with every automatic estimate the vehicle allows), or folds a repeated start into the open one. */
    fun start(trigger: Trigger, source: Source, now: Long, seatsNow: Map<Seat, SeatRef>, place: Place?,
              detail: String = "", context: StartContext = StartContext()): Boolean {
        val open = active
        if (open != null) {
            open.note(now, source, "${trigger.label} 다시 들어옴 → 같은 사건에서 이어서 처리" + suffix(detail))
            trigger.factor()?.let { addFactor(open, it, source, now, detail) }
            return false
        }
        val i = Incident(newId(now), now, trigger, source, seatsNow.toMap())
        i.note(now, source, "사건 시작 · ${trigger.label}" + suffix(detail))
        trigger.factor()?.let { i.factors[it] = source; if (detail.isNotBlank()) i.factorNotes[it] = detail }
        // People on board, from seat occupancy (reference only: a bag can be "occupied", so it is marked as an estimate).
        Seat.entries.forEach { s ->
            when (seatsNow[s]?.on) {
                true -> { i.people[s] = PersonState.PRESENT; i.peopleSource[s] = Source.VEHICLE }
                false -> { i.people[s] = PersonState.NOT_ON_BOARD; i.peopleSource[s] = Source.VEHICLE }
                null -> Unit
            }
        }
        val onBoard = Seat.entries.filter { i.people[it] == PersonState.PRESENT }
        i.note(now, Source.APP, "좌석 신호로 탑승 추정: " + (onBoard.joinToString { it.alias }.ifBlank { "없음" }) +
            Seat.entries.filter { seatsNow[it]?.on == null }.takeIf { it.isNotEmpty() }?.let { u -> " · 신호 없음: " + u.joinToString { it.label } }.orEmpty())
        // Road situation from the speed just before the stop.
        context.peakKph?.let { kph ->
            if (kph >= HIGHWAY_KPH) estimate(i, Factor.HIGHWAY, now, "사고 직전 약 ${kph.toInt()}km/h")
            if (kph >= MOVING_KPH) estimate(i, Factor.IN_LANE, now, "달리던 중 멈춤")
        }
        warnings(i, context.warnings, now)
        if (place != null) {
            i.place = place
            i.note(now, place.source, "위치: ${place.text}")
        } else i.note(now, Source.APP, "위치 미확인 · 수신되면 자동으로 붙임")
        i.response = Response.WAITING
        i.responseDeadline = now + RESPONSE_WINDOW_MS
        i.note(now, Source.APP, "탑승자 응답 확인 시작 (${RESPONSE_WINDOW_MS / 1000}초)")
        active = i
        return true
    }

    fun answer(ok: Boolean, now: Long) {
        val i = active ?: return
        i.response = if (ok) Response.OK else Response.HELP
        i.note(now, Source.USER, if (ok) "탑승자 응답: 괜찮아요" else "탑승자 응답: 도움이 필요해요")
        if (ok) Seat.entries.filter { i.people[it] == PersonState.PRESENT }.forEach {
            i.people[it] = PersonState.ANSWERED_OK; i.peopleSource[it] = Source.APP   // an answer, not a manual override
        }
    }

    /** Called by the UI clock. Silence escalates; it never dials and never means "safe". */
    fun checkResponseTimeout(now: Long): Boolean {
        val i = active ?: return false
        if (i.response != Response.WAITING || now < i.responseDeadline) return false
        i.response = Response.NONE
        i.note(now, Source.APP, "응답 없음 (${RESPONSE_WINDOW_MS / 1000}초) → 긴급 대응으로 올림")
        Seat.entries.filter { i.people[it] == PersonState.PRESENT }.forEach {
            i.people[it] = PersonState.NO_RESPONSE; i.peopleSource[it] = Source.APP
        }
        return true
    }

    /** Re-asks after an app restart instead of guessing what happened while the app was closed. */
    fun reaskAfterRestart(now: Long) {
        val i = active ?: return
        i.note(now, Source.APP, "앱 다시 열림 · 저장된 사건 복원 (접수·안전 판정 자동 생성 없음)")
        if (i.response == Response.WAITING) {
            i.responseDeadline = now + RESPONSE_WINDOW_MS
            i.note(now, Source.APP, "응답 확인이 끝나지 않아 다시 묻습니다")
        }
    }

    /** Agencies whose report should go out now and has not: marks them MOCK_SENT and returns them. */
    fun autoContacts(now: Long): List<Agency> {
        val i = active ?: return emptyList()
        if (i.response == Response.WAITING) return emptyList()
        val a = assess(i)
        return Agency.entries.filter { a.needs[it] == Need.NOW && i.contact(it).state == ContactState.NONE }.onEach {
            i.contacts[it] = ContactStatus(ContactState.MOCK_SENT, now, "자동 전송")
            i.note(now, Source.MOCK, "${it.label}에 신고 카드 자동 전송 · 모의 (실제 발신 아님)")
        }
    }

    fun setPerson(seat: Seat, state: PersonState, now: Long) {
        val i = active ?: return
        if (i.people[seat] == state && i.peopleSource[seat] == Source.USER) return
        i.people[seat] = state
        i.peopleSource[seat] = Source.USER
        i.note(now, Source.USER, "${seat.alias}(${seat.label}): ${state.label}로 고침")
    }

    /** Occupant overrules an automatic judgement (or adds what no sensor can see, such as fire or a pedestrian). */
    fun setFactor(f: Factor, on: Boolean, now: Long) {
        val i = active ?: return
        i.userFactors.add(f)
        if (on == i.has(f) && i.factors[f] == Source.USER) return
        if (on) {
            i.factors[f] = Source.USER
            i.factorNotes.remove(f)
            i.note(now, Source.USER, "상황 표시: ${f.label}")
            if (f == Factor.SAFE_SPOT) { i.factors.remove(Factor.IN_LANE); i.userFactors.add(Factor.IN_LANE) }
            if (f == Factor.IN_LANE) { i.factors.remove(Factor.SAFE_SPOT); i.userFactors.add(Factor.SAFE_SPOT) }
        } else {
            i.factors.remove(f)
            i.note(now, Source.USER, "상황 해제: ${f.label}")
        }
    }

    /** Vehicle-reported factor (airbag, hard stop). Adds once; repeated signals only add a log line. */
    fun vehicleFactor(f: Factor, source: Source, now: Long, detail: String) {
        val i = active ?: return
        addFactor(i, f, source, now, detail)
    }

    /** Warning lamps while the incident is open. A leak lamp also raises the leak factor automatically. */
    fun warnings(list: List<String>, now: Long) { active?.let { warnings(it, list, now) } }

    private fun warnings(i: Incident, list: List<String>, now: Long) {
        if (list.isEmpty()) return
        if (!i.has(Factor.WARNING_LAMP)) addFactor(i, Factor.WARNING_LAMP, Source.VEHICLE, now, list.joinToString())
        list.firstOrNull { w -> LEAK_WARNINGS.any { w.contains(it) } }?.let { estimate(i, Factor.FUEL_LEAK, now, it) }
    }

    /** A door opened after the incident started. */
    fun doorOpened(seat: Seat, now: Long) {
        val i = active ?: return
        if (i.doorsOpened.add(seat)) i.note(now, Source.VEHICLE, "${seat.label} 문 열림 감지")
    }

    /** Seat signal changed during the incident: an emptied seat after a door opened is read as "got out". */
    fun seatChanged(seat: Seat, on: Boolean?, now: Long) {
        val i = active ?: return
        if (i.peopleSource[seat] == Source.USER) return
        val p = i.people[seat]
        if (on == false && i.doorsOpened.isNotEmpty() && p in MOVABLE) {
            i.people[seat] = PersonState.EVACUATED; i.peopleSource[seat] = Source.VEHICLE
            i.note(now, Source.VEHICLE, "${seat.alias}(${seat.label}) 하차 감지 · 문 열림 후 좌석 비움")
        } else if (on == true && p in setOf(PersonState.UNCHECKED, PersonState.NOT_ON_BOARD)) {
            i.people[seat] = PersonState.PRESENT; i.peopleSource[seat] = Source.VEHICLE
            i.note(now, Source.VEHICLE, "${seat.alias}(${seat.label}) 좌석 신호 → 탑승 추정")
        }
    }

    fun setPlace(place: Place?, now: Long) {
        val i = active ?: return
        if (i.place == place) return
        i.place = place
        i.note(now, place?.source ?: Source.APP, if (place == null) "위치 미확인으로 변경" else "위치: ${place.text}")
    }

    fun contact(a: Agency, state: ContactState, now: Long, note: String = "") {
        val i = active ?: return
        i.contacts[a] = ContactStatus(state, now, note)
        val src = when (state) { ContactState.MOCK_SENT, ContactState.MOCK_ACK -> Source.MOCK; else -> Source.USER }
        i.note(now, src, "${a.label}: ${state.label}" + suffix(note))
    }

    fun carAction(a: CarAction, result: ActionResult, auto: Boolean = false) {
        val i = active ?: return
        if (i.actions[a]?.state == result.state && result.state == ActionState.WAITING) return
        i.actions[a] = result
        val src = when (result.state) { ActionState.REQUESTED, ActionState.WAITING, ActionState.BLOCKED -> if (auto) Source.APP else Source.USER; else -> Source.VEHICLE }
        i.note(result.at, src, (if (auto && result.state == ActionState.REQUESTED) "자동 " else "") + "${a.label}: ${result.state.label}" + suffix(result.detail))
    }

    /** "Not an accident" from the occupant right after an automatic hint. Kept in history, not deleted. */
    fun falseAlarm(now: Long) {
        val i = active ?: return
        i.response = Response.OK
        i.note(now, Source.USER, "사고 아님으로 응답")
        close(now)
    }

    fun close(now: Long) {
        val i = active ?: return
        i.closedAt = now
        i.note(now, Source.USER, "사건 종료 (구조·접수 완료를 뜻하지 않음)")
        history.add(0, i)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        active = null
    }

    private fun estimate(i: Incident, f: Factor, now: Long, detail: String) {
        if (f in i.userFactors || i.has(f)) return
        if (f == Factor.IN_LANE && i.has(Factor.SAFE_SPOT)) return
        i.factors[f] = Source.APP
        i.factorNotes[f] = detail
        i.note(now, Source.APP, "자동 판단: ${f.label} 추정 · $detail")
    }

    private fun addFactor(i: Incident, f: Factor, source: Source, now: Long, detail: String?) {
        if (i.has(f)) {
            if (!detail.isNullOrBlank()) i.note(now, source, "${f.label} 신호 다시 수신" + suffix(detail))
            return
        }
        i.factors[f] = source
        if (!detail.isNullOrBlank()) i.factorNotes[f] = detail
        i.note(now, source, "상황 추가: ${f.label}" + suffix(detail ?: ""))
    }

    companion object {
        const val RESPONSE_WINDOW_MS = 20_000L
        const val MAX_HISTORY = 8
        const val HIGHWAY_KPH = 70f
        const val MOVING_KPH = 20f
        val LEAK_WARNINGS = listOf("수소 누출")
        private val MOVABLE = setOf(PersonState.PRESENT, PersonState.ANSWERED_OK, PersonState.NEEDS_HELP, PersonState.NO_RESPONSE)
        private fun suffix(detail: String) = if (detail.isBlank()) "" else " · $detail"
        fun newId(now: Long): String = "FG-" + SimpleDateFormat("MMdd-HHmmss", Locale.KOREA).format(Date(now))
        private fun Trigger.factor() = when (this) {
            Trigger.AIRBAG -> Factor.AIRBAG
            Trigger.HARD_STOP -> Factor.HARD_STOP
            Trigger.MANUAL_SOS -> null
        }

        fun assess(i: Incident): Assessment {
            val urgent = mutableListOf<String>()
            val caution = mutableListOf<String>()
            val open = mutableListOf<String>()
            fun tag(f: Factor) = when (i.factors[f]) { Source.APP -> " (추정)"; Source.SAMPLE -> " (샘플)"; else -> "" }
            if (i.has(Factor.AIRBAG)) urgent += "에어백 전개" + tag(Factor.AIRBAG)
            if (i.response == Response.NONE) urgent += "탑승자 응답 없음"
            if (i.response == Response.HELP) urgent += "도움 요청"
            Seat.entries.filter { i.people[it] == PersonState.NEEDS_HELP }.takeIf { it.isNotEmpty() }
                ?.let { s -> urgent += "도움 필요: " + s.joinToString { it.alias } }
            if (i.has(Factor.FIRE)) urgent += "화재·연기"
            if (i.has(Factor.PEDESTRIAN)) urgent += "대인사고"
            if (i.has(Factor.FUEL_LEAK)) caution += "누출 위험" + tag(Factor.FUEL_LEAK)
            if (i.has(Factor.HIGHWAY)) caution += "고속도로" + tag(Factor.HIGHWAY)
            if (i.has(Factor.IN_LANE)) caution += "차로 위 정차" + tag(Factor.IN_LANE)
            if (i.has(Factor.WARNING_LAMP)) caution += "차량 경고등"
            if (i.has(Factor.HARD_STOP)) caution += "급정지" + tag(Factor.HARD_STOP)
            if (i.response == Response.WAITING) open += "응답 기다리는 중"
            val unknownSeats = i.people.values.count { it == PersonState.UNCHECKED }
            if (unknownSeats > 0) open += "좌석 신호 없음 ${unknownSeats}석"
            if (i.place == null) open += "위치 미확인"

            val level = when {
                urgent.isNotEmpty() -> Level.URGENT
                caution.isNotEmpty() -> Level.CAUTION
                i.response == Response.WAITING || unknownSeats > 0 || !i.has(Factor.SAFE_SPOT) -> Level.CHECK
                else -> Level.MINOR
            }

            val danger = i.has(Factor.FIRE) || i.has(Factor.FUEL_LEAK)
            val road = (i.has(Factor.HIGHWAY) || i.has(Factor.IN_LANE)) && !i.has(Factor.SAFE_SPOT)
            val hurt = i.people.values.any { it == PersonState.NEEDS_HELP || it == PersonState.NO_RESPONSE }
            val rescue = i.has(Factor.AIRBAG) || i.has(Factor.FIRE) || i.has(Factor.PEDESTRIAN) ||
                i.response == Response.NONE || i.response == Response.HELP || hurt
            val aboard = Seat.entries.filter { i.people[it] in MOVABLE }
            val everyoneOut = i.people.values.any { it == PersonState.EVACUATED } && aboard.isEmpty()

            // Only what a person has to do with their own hands; everything else is automatic.
            val steps = mutableListOf<Step>()
            if (danger && !everyoneOut) steps += Step("leave_now", StepKind.EVACUATE, "지금 차에서 내려 멀리 떨어지세요",
                "화재·누출 위험이에요. 문 잠금은 앱이 풀어요")
            if (road) steps += Step("hazard", StepKind.CAR, "비상등을 켜세요", "앱이 켤 수 없는 기능이에요 · 차량의 비상등 버튼")
            if (road && !everyoneOut) steps += Step("guardrail", StepKind.EVACUATE, "가드레일 밖 안전한 곳으로 피하세요",
                "차 안이나 차 앞뒤에 머무르지 마세요 (C1 고속도로 사고 안전 조치)")
            if (!road && !danger && !i.has(Factor.SAFE_SPOT)) steps += Step("look_around", StepKind.EVACUATE,
                "주변이 위험하면 안전한 곳으로 피하세요", "차로 위라면 대피가 먼저예요")
            if (i.has(Factor.PEDESTRIAN)) steps += Step("call112", StepKind.CALL, "112에 전화해 상황을 알리세요",
                "대인사고는 경찰 신고 후 안내에 따르세요 (C1) · 신고 카드는 자동 전송됨(모의)", Agency.POLICE)
            if (rescue) steps += Step("call119", StepKind.CALL, "119와 통화하세요",
                "신고 카드는 자동 전송됨(모의) · 전화 화면에서 발신하고 카드 내용을 불러주세요", Agency.RESCUE)
            steps += Step("photo", StepKind.RECORD, "안전해지면 현장과 차량을 찍어 두세요", "사고 정황 증거 (C1)")

            val waiting = i.response == Response.WAITING
            val needs = mapOf(
                Agency.RESCUE to if (rescue) Need.NOW else Need.SOON,
                Agency.POLICE to if (i.has(Factor.PEDESTRIAN) || road) Need.NOW else Need.SOON,
                Agency.ROAD to if (i.has(Factor.HIGHWAY) && !i.has(Factor.SAFE_SPOT)) Need.NOW else Need.LATER,
                Agency.INSURER to if (waiting) Need.SOON else Need.NOW,
            )

            val carPlan = linkedMapOf<CarAction, String>()
            carPlan[CarAction.UNLOCK] = "구조·탈출을 위해 사고 후 문 잠금 해제"
            if (road) carPlan[CarAction.TRUNK] = "뒤차에 사고 차량임을 알림 (C1 고속도로 안전 조치)"
            if (i.has(Factor.FIRE) || i.response == Response.NONE || hurt)
                carPlan[CarAction.WINDOWS] = if (i.has(Factor.FIRE)) "연기 배출·탈출 보조" else "응답 없는 탑승자에게 구조대 접근"
            return Assessment(level, urgent + caution + open, steps, needs, carPlan)
        }
    }
}

/**
 * Hard-stop hint from speed samples. A large drop to (near) standstill within a short window
 * only raises a question to the occupants — it is not treated as a confirmed crash.
 */
class HardStopDetector(
    private val dropKph: Float = 40f,
    private val windowMs: Long = 2_000,
    private val stopKph: Float = 5f,
    private val cooldownMs: Long = 60_000,
) {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var lastFired: Long? = null

    /** Returns the peak speed (km/h) before the stop when the hint fires, otherwise null. */
    fun feed(at: Long, kph: Float): Float? {
        val previous = samples.lastOrNull()
        samples.addLast(at to kph)
        while (samples.size > 1 && samples.first().first < at - windowMs) samples.removeFirst()
        // Keep the immediately preceding sample even if it is slightly older (on-change properties).
        val peak = maxOf(samples.maxOf { it.second }, previous?.takeIf { at - it.first <= 5_000 }?.second ?: 0f)
        val cooled = lastFired?.let { at - it >= cooldownMs } ?: true
        if (kph <= stopKph && peak - kph >= dropKph && cooled) {
            lastFired = at
            samples.clear()
            samples.addLast(at to kph)
            return peak
        }
        return null
    }
}

/** Highest speed in the last [windowMs]; used to estimate the road situation when an incident opens. */
class RecentPeak(private val windowMs: Long = 60_000) {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    fun feed(at: Long, kph: Float) {
        samples.addLast(at to kph)
        while (samples.isNotEmpty() && samples.first().first < at - windowMs) samples.removeFirst()
    }
    fun peak(now: Long): Float? = samples.filter { it.first >= now - windowMs }.maxOfOrNull { it.second }
}
