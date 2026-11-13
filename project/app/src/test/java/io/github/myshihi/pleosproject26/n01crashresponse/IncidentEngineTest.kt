package io.github.myshihi.pleosproject26.n01crashresponse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentEngineTest {
    private val t0 = 1_762_840_000_000L
    private val seats = mapOf(
        Seat.FL to SeatRef(true, Source.VEHICLE, t0 - 5_000),
        Seat.FR to SeatRef(true, Source.VEHICLE, t0 - 5_000),
        Seat.RL to SeatRef(false, Source.VEHICLE, t0 - 5_000),
    )
    private fun engine(trigger: Trigger = Trigger.MANUAL_SOS, ctx: StartContext = StartContext(), place: Place? = null) =
        IncidentEngine().also { it.start(trigger, Source.VEHICLE, t0, seats, place, "", ctx) }
    private fun IncidentEngine.timeout() = checkResponseTimeout(t0 + IncidentEngine.RESPONSE_WINDOW_MS)

    @Test fun seatSignalsFillPeopleAutomaticallyButUnknownStaysUnknown() {
        val i = engine().active!!
        assertEquals(PersonState.PRESENT, i.people[Seat.FL])
        assertEquals(PersonState.PRESENT, i.people[Seat.FR])
        assertEquals(PersonState.NOT_ON_BOARD, i.people[Seat.RL])
        assertEquals(PersonState.UNCHECKED, i.people[Seat.RR])
        assertEquals(Source.VEHICLE, i.peopleSource[Seat.FL])
        assertEquals(Response.WAITING, i.response)
    }

    @Test fun speedBeforeStopEstimatesRoadSituation() {
        val i = engine(Trigger.HARD_STOP, StartContext(peakKph = 92f)).active!!
        assertEquals(Source.APP, i.factors[Factor.HIGHWAY])
        assertEquals(Source.APP, i.factors[Factor.IN_LANE])
        assertTrue(i.factorNotes[Factor.HIGHWAY]!!.contains("92"))
        val slow = engine(Trigger.HARD_STOP, StartContext(peakKph = 45f)).active!!
        assertFalse(slow.has(Factor.HIGHWAY))
        assertTrue(slow.has(Factor.IN_LANE))
    }

    @Test fun leakWarningRaisesLeakFactor() {
        val i = engine(ctx = StartContext(warnings = listOf("수소 누출"))).active!!
        assertTrue(i.has(Factor.WARNING_LAMP))
        assertEquals(Source.APP, i.factors[Factor.FUEL_LEAK])
        assertEquals("leave_now", IncidentEngine.assess(i).steps.first().id)
    }

    @Test fun oneAnswerAppliesToEveryoneOnBoard() {
        val e = engine()
        e.answer(true, t0 + 2_000)
        val i = e.active!!
        assertEquals(PersonState.ANSWERED_OK, i.people[Seat.FL])
        assertEquals(PersonState.ANSWERED_OK, i.people[Seat.FR])
        assertEquals(PersonState.UNCHECKED, i.people[Seat.RR])
        assertFalse(e.checkResponseTimeout(t0 + 60_000))
    }

    @Test fun silenceEscalatesAndNeverMeansSafe() {
        val e = engine(Trigger.AIRBAG)
        assertFalse(e.checkResponseTimeout(t0 + IncidentEngine.RESPONSE_WINDOW_MS - 1))
        assertTrue(e.timeout())
        val i = e.active!!
        assertEquals(Response.NONE, i.response)
        assertEquals(PersonState.NO_RESPONSE, i.people[Seat.FL])
        assertEquals(PersonState.UNCHECKED, i.people[Seat.RR])
        assertEquals(Level.URGENT, IncidentEngine.assess(i).level)
    }

    @Test fun reportsGoOutAutomaticallyOnlyAfterTheAnswerWindowAndOnlyAsMock() {
        val e = engine(Trigger.HARD_STOP, StartContext(peakKph = 90f))
        assertTrue(e.autoContacts(t0 + 1).isEmpty())          // still waiting for the answer
        e.timeout()
        val sent = e.autoContacts(t0 + 21_000)
        assertTrue(Agency.RESCUE in sent && Agency.POLICE in sent && Agency.ROAD in sent && Agency.INSURER in sent)
        assertTrue(e.active!!.contacts.values.all { it.state == ContactState.MOCK_SENT })
        assertTrue(e.autoContacts(t0 + 22_000).isEmpty())     // once only
    }

    @Test fun calmLocalIncidentSendsOnlyInsurer() {
        val e = engine()
        e.answer(true, t0 + 1_000)
        assertEquals(listOf(Agency.INSURER), e.autoContacts(t0 + 2_000))
    }

    @Test fun carPlanFollowsTheSituation() {
        val e = engine(Trigger.HARD_STOP, StartContext(peakKph = 90f))
        var plan = IncidentEngine.assess(e.active!!).carPlan
        assertTrue(CarAction.UNLOCK in plan && CarAction.TRUNK in plan)
        assertFalse(CarAction.WINDOWS in plan)
        e.timeout()
        plan = IncidentEngine.assess(e.active!!).carPlan
        assertTrue(CarAction.WINDOWS in plan)
        e.setFactor(Factor.SAFE_SPOT, true, t0 + 30_000)
        assertFalse(CarAction.TRUNK in IncidentEngine.assess(e.active!!).carPlan)
    }

    @Test fun userCorrectionWinsOverAutomation() {
        val e = engine(Trigger.HARD_STOP, StartContext(peakKph = 90f))
        e.setFactor(Factor.HIGHWAY, false, t0 + 1_000)
        assertFalse(e.active!!.has(Factor.HIGHWAY))
        e.warnings(listOf("수소 누출"), t0 + 2_000)
        e.setFactor(Factor.FUEL_LEAK, false, t0 + 3_000)
        e.warnings(listOf("수소 누출"), t0 + 4_000)
        assertFalse(e.active!!.has(Factor.FUEL_LEAK))
        e.setFactor(Factor.SAFE_SPOT, true, t0 + 5_000)
        assertFalse(e.active!!.has(Factor.IN_LANE))
    }

    @Test fun emptiedSeatAfterDoorOpenIsReadAsGotOut() {
        val e = engine()
        e.seatChanged(Seat.FL, false, t0 + 1_000)               // no door opened yet: no conclusion
        assertEquals(PersonState.PRESENT, e.active!!.people[Seat.FL])
        e.doorOpened(Seat.FL, t0 + 2_000)
        e.seatChanged(Seat.FL, false, t0 + 3_000)
        assertEquals(PersonState.EVACUATED, e.active!!.people[Seat.FL])
        e.seatChanged(Seat.RR, true, t0 + 4_000)                // late signal adds a person
        assertEquals(PersonState.PRESENT, e.active!!.people[Seat.RR])
    }

    @Test fun evacuationStepsDisappearWhenEveryoneIsOut() {
        val e = engine(Trigger.HARD_STOP, StartContext(peakKph = 90f))
        e.answer(true, t0 + 1_000)
        assertTrue(IncidentEngine.assess(e.active!!).steps.any { it.id == "guardrail" })
        e.doorOpened(Seat.FL, t0 + 2_000)
        e.seatChanged(Seat.FL, false, t0 + 3_000)
        e.seatChanged(Seat.FR, false, t0 + 3_000)
        val ids = IncidentEngine.assess(e.active!!).steps.map { it.id }
        assertFalse("guardrail" in ids)
        assertTrue("hazard" in ids)
    }

    @Test fun humanStepsAreOnlyPhysicalTasks() {
        val e = engine(Trigger.HARD_STOP, StartContext(peakKph = 90f))
        e.timeout()
        val steps = IncidentEngine.assess(e.active!!).steps
        assertTrue(steps.none { it.id == "trunk" || it.id == "check_people" || it.id == "insurer" })
        assertEquals(listOf("hazard", "guardrail", "call119", "photo"), steps.map { it.id })
    }

    @Test fun pedestrianInjuryPutsPoliceCallBefore119() {
        val e = engine()
        e.setFactor(Factor.PEDESTRIAN, true, t0 + 1)
        val ids = IncidentEngine.assess(e.active!!).steps.map { it.id }
        assertTrue(ids.indexOf("call112") < ids.indexOf("call119"))
    }

    @Test fun repeatedStartFoldsIntoSameIncident() {
        val e = engine()
        val id = e.active!!.id
        assertFalse(e.start(Trigger.AIRBAG, Source.VEHICLE, t0 + 2_000, seats, null, "운전석"))
        assertEquals(id, e.active!!.id)
        assertTrue(e.active!!.has(Factor.AIRBAG))
    }

    @Test fun seatSnapshotIsNotOverwrittenByLaterReadings() {
        val live = seats.toMutableMap()
        val e = IncidentEngine().also { it.start(Trigger.MANUAL_SOS, Source.USER, t0, live, null) }
        live[Seat.FL] = SeatRef(false, Source.VEHICLE, t0 + 10_000)
        assertEquals(true, e.active!!.seatsBefore[Seat.FL]?.on)
    }

    @Test fun falseAlarmClosesIntoHistory() {
        val e = engine(Trigger.HARD_STOP)
        e.falseAlarm(t0 + 4_000)
        assertNull(e.active)
        assertNotNull(e.history[0].closedAt)
    }

    @Test fun restartReasksInsteadOfGuessing() {
        val e = engine(Trigger.AIRBAG)
        val later = t0 + 10 * 60_000
        e.reaskAfterRestart(later)
        assertFalse(e.checkResponseTimeout(later + 1_000))
        assertTrue(e.active!!.contacts.isEmpty())
    }

    @Test fun minorOnlyWhenAllKnownAndSafe() {
        val e = IncidentEngine()
        val all = Seat.entries.associateWith { SeatRef(it == Seat.FL, Source.VEHICLE, t0) }
        e.start(Trigger.MANUAL_SOS, Source.USER, t0, all, Place("p", Source.VEHICLE))
        e.answer(true, t0 + 1)
        assertEquals(Level.CHECK, IncidentEngine.assess(e.active!!).level)
        e.setFactor(Factor.SAFE_SPOT, true, t0 + 2)
        assertEquals(Level.MINOR, IncidentEngine.assess(e.active!!).level)
    }

    @Test fun historyIsCapped() {
        val e = IncidentEngine()
        repeat(IncidentEngine.MAX_HISTORY + 3) { n ->
            e.start(Trigger.MANUAL_SOS, Source.USER, t0 + n * 1_000L, seats, null)
            e.close(t0 + n * 1_000L + 500)
        }
        assertEquals(IncidentEngine.MAX_HISTORY, e.history.size)
    }
}

class HardStopDetectorTest {
    @Test fun firesOnLargeQuickDropToStop() {
        val d = HardStopDetector()
        var t = 0L
        repeat(20) { assertNull(d.feed(t, 80f)); t += 100 }
        assertEquals(80f, d.feed(t, 0f))
    }

    @Test fun ignoresGradualBraking() {
        val d = HardStopDetector()
        var t = 0L; var v = 60f
        while (v > 0f) { assertNull(d.feed(t, v)); t += 100; v -= 1.5f }
        assertNull(d.feed(t, 0f))
    }

    @Test fun cooldownPreventsRepeats() {
        val d = HardStopDetector(cooldownMs = 60_000)
        assertNull(d.feed(0, 90f)); assertNotNull(d.feed(500, 0f))
        assertNull(d.feed(1_000, 90f)); assertNull(d.feed(1_500, 0f))
        assertNull(d.feed(70_000, 90f)); assertNotNull(d.feed(70_500, 0f))
    }

    @Test fun recentPeakForgetsOldSpeeds() {
        val p = RecentPeak(windowMs = 60_000)
        p.feed(0, 100f); p.feed(30_000, 40f)
        assertEquals(100f, p.peak(50_000))
        assertEquals(40f, p.peak(70_000))
    }
}

class IncidentCodecTest {
    @Test fun roundTripKeepsAutomaticAndManualFields() {
        val t0 = 1_762_840_000_000L
        val e = IncidentEngine()
        e.start(Trigger.HARD_STOP, Source.VEHICLE, t0, mapOf(Seat.FL to SeatRef(null, Source.VEHICLE, t0, "조회 실패"), Seat.FR to SeatRef(true, Source.VEHICLE, t0)),
            null, "", StartContext(peakKph = 88f))
        e.setFactor(Factor.IN_LANE, false, t0 + 1)
        e.doorOpened(Seat.FR, t0 + 2)
        e.contact(Agency.POLICE, ContactState.MOCK_SENT, t0 + 3, "자동 전송")
        e.carAction(CarAction.UNLOCK, ActionResult(ActionState.PARTIAL, t0 + 4, "성공 2 / 요청 4"))
        val (active, history) = IncidentCodec.decode(IncidentCodec.encode(e.active, e.history))
        val i = active!!
        assertEquals(e.active!!.id, i.id)
        assertNull(i.seatsBefore[Seat.FL]?.on)
        assertEquals(PersonState.UNCHECKED, i.people[Seat.FL])
        assertEquals(PersonState.PRESENT, i.people[Seat.FR])
        assertEquals(Source.VEHICLE, i.peopleSource[Seat.FR])
        assertEquals(Source.APP, i.factors[Factor.HIGHWAY])
        assertTrue(i.factorNotes[Factor.HIGHWAY]!!.contains("88"))
        assertTrue(Factor.IN_LANE in i.userFactors)
        assertTrue(Seat.FR in i.doorsOpened)
        assertEquals(ContactState.MOCK_SENT, i.contact(Agency.POLICE).state)
        assertEquals(ActionState.PARTIAL, i.actions[CarAction.UNLOCK]?.state)
        assertEquals(e.active!!.log.size, i.log.size)
        assertTrue(history.isEmpty())
    }

    @Test fun damagedOrOtherVersionDataStartsClean() {
        assertNull(IncidentCodec.decode("{not json").first)
        assertNull(IncidentCodec.decode("""{"version":1,"active":{"id":"x"}}""").first)
        assertNull(IncidentCodec.decode(null).first)
    }
}
