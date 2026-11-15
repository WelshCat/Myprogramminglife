package io.github.welshcat.weekalarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.*

var fails = 0
fun check(c: Boolean, m: () -> String) { if (!c) { fails++; println("FAIL: " + m()) } }
val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
fun t(s: String, z: ZoneId = SEOUL) = LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
fun f(ms: Long, z: ZoneId = SEOUL) = Instant.ofEpochMilli(ms).atZone(z).toLocalDateTime().toString()
fun week(vararg d: Pair<Int, String>): List<DayAlarm> {   // 1=월 … 7=일
    val w = MutableList(7) { DayAlarm() }
    for ((day, hm) in d) { val (h, m) = hm.split(":").map { it.toInt() }; w[day - 1] = DayAlarm(true, h, m) }
    return w
}
fun list(occ: List<Occurrence>, z: ZoneId = SEOUL) = occ.map { "${f(it.at, z).substring(11)}${it.kind.name[0]}" }

fun runAll(): Int {
    // 2026-11-16 은 월요일
    val w = week(1 to "07:00", 2 to "06:30", 7 to "00:30")
    val s = Settings()

    // 1) 1시간 전부터 15분 간격: 06:00 06:15 06:30 06:45 07:00
    val mon = Planner.occurrences(w, s, RuntimeState(), SEOUL, t("2026-11-16T05:00"), t("2026-11-16T08:00"))
    check(list(mon) == listOf("06:00P", "06:15P", "06:30P", "06:45P", "07:00M")) { "1h: ${list(mon)}" }
    check(mon.first().minutesBefore == 60 && mon[3].minutesBefore == 15) { "minutesBefore" }

    // 2) 2시간 전부터: 9번
    val two = Planner.occurrences(w, s.copy(preLeadMinutes = 120), RuntimeState(), SEOUL, t("2026-11-16T04:00"), t("2026-11-16T07:00"))
    check(two.size == 9 && list(two).first() == "05:00P" && list(two).last() == "07:00M") { "2h: ${list(two)}" }

    // 3) 미리 알람 끄기
    val off = Planner.occurrences(w, s.copy(preEnabled = false), RuntimeState(), SEOUL, t("2026-11-16T00:00"), t("2026-11-16T23:59"))
    check(list(off) == listOf("07:00M")) { "pre off: ${list(off)}" }

    // 4) 일요일 00:30 → 미리 알람이 토요일 밤 23:30부터
    val sun = Planner.occurrences(w, s, RuntimeState(), SEOUL, t("2026-11-21T23:00"), t("2026-11-22T01:00"))
    check(list(sun) == listOf("23:30P", "23:45P", "00:00P", "00:15P", "00:30M")) { "sun midnight: ${list(sun)}" }
    check(f(sun.first().at) == "2026-11-21T23:30") { "sat night ${f(sun.first().at)}" }

    // 5) 주 경계: 월요일 00:30 알람 → 일요일 밤 23:30부터
    val w2 = week(1 to "00:30")
    val mb = Planner.next(w2, s, RuntimeState(), SEOUL, t("2026-11-22T12:00"))!!
    check(f(mb.at) == "2026-11-22T23:30" && mb.kind == Kind.PRE) { "week boundary ${f(mb.at)}" }

    // 6) next: 매주 반복 (다음 주 월요일)
    val nx = Planner.next(w, s, RuntimeState(), SEOUL, t("2026-11-16T07:00"))!!
    check(f(nx.at) == "2026-11-17T05:30" && nx.kind == Kind.PRE) { "next tue pre ${f(nx.at)}" }
    val nx2 = Planner.next(week(1 to "07:00"), s, RuntimeState(), SEOUL, t("2026-11-16T07:00"))!!
    check(f(nx2.at) == "2026-11-23T06:00") { "next week ${f(nx2.at)}" }

    // 7) 모든 요일 꺼짐
    check(Planner.next(List(7) { DayAlarm() }, s, RuntimeState(), SEOUL, t("2026-11-16T00:00")) == null) { "none" }

    // 8) (건너뛰기 기능 삭제됨)
    val main = t("2026-11-16T07:00")

    // 9) 중복 방지 (주 예약 + 보조 예약)
    val occ = Occurrence(main, Kind.MAIN, main)
    val (r1, st1) = Planner.onFired(occ, RuntimeState(lastHandledAt = t("2026-11-16T06:45")))
    val (r2, _) = Planner.onFired(occ, st1)
    check(r1 && !r2) { "dedupe" }

    // 10) 다시 알림: 기본 꺼짐, 켜면 본 알람에만, 최대 횟수
    check(!Planner.canSnooze(occ, s, RuntimeState())) { "snooze default off" }
    val sOn = s.copy(snoozeEnabled = true, snoozeMaxCount = 2)
    check(Planner.canSnooze(occ, sOn, RuntimeState())) { "snooze main" }
    check(!Planner.canSnooze(mon[0], sOn, RuntimeState())) { "no snooze pre" }
    var st = Planner.snooze(occ, sOn, st1, main + 30_000)
    check(st.snoozeAt == main + 30_000 + 5 * 60_000) { "snooze at" }
    val sn = Planner.next(w, sOn, st, SEOUL, main + 31_000)!!
    check(sn.kind == Kind.SNOOZE && sn.mainAt == main) { "snooze next $sn" }
    st = Planner.onFired(sn, st).second
    st = Planner.snooze(sn, sOn, st, sn.at)
    check(!Planner.canSnooze(sn, sOn, st)) { "snooze max" }

    // 11) 놓친 알람: 폰이 꺼져 있다가 07:20에 켜짐 → 본 알람 늦게 울림
    val d1 = Planner.decide(w, s, RuntimeState(lastHandledAt = t("2026-11-16T05:55")), SEOUL, t("2026-11-16T07:20"))
    check(d1.late && d1.next?.kind == Kind.MAIN && d1.next!!.at == main && d1.fireAt == t("2026-11-16T07:20") + 1000) { "late main $d1" }
    // 늦은 알람 도착 → 울림, 이후 결정은 정상
    val (lr, lst) = Planner.onFired(d1.next!!, d1.state)
    check(lr) { "late fires" }
    val d1b = Planner.decide(w, s, lst, SEOUL, t("2026-11-16T07:20") + 2000)
    check(!d1b.late && f(d1b.next!!.at) == "2026-11-17T05:30") { "after late ${d1b}" }

    // 12) 너무 늦음(2시간 후 켜짐) → 울리지 않고 놓친 알람 알림
    val d2 = Planner.decide(w, s, RuntimeState(lastHandledAt = t("2026-11-16T05:55")), SEOUL, t("2026-11-16T09:30"))
    check(!d2.late && d2.missed?.kind == Kind.MAIN && f(d2.next!!.at) == "2026-11-17T05:30") { "too late $d2" }

    // 13) 놓친 미리 알람은 5분 이내만
    val d3 = Planner.decide(w, s, RuntimeState(lastHandledAt = t("2026-11-16T06:10")), SEOUL, t("2026-11-16T06:18"))
    check(d3.late && d3.next!!.kind == Kind.PRE && f(d3.next!!.at) == "2026-11-16T06:15") { "late pre $d3" }
    val d4 = Planner.decide(w, s, RuntimeState(lastHandledAt = t("2026-11-16T06:10")), SEOUL, t("2026-11-16T06:25"))
    check(!d4.late && f(d4.next!!.at) == "2026-11-16T06:30" && d4.missed == null) { "pre too late $d4" }

    // 14) 놓친 알람 늦게 울리기 끔
    val d5 = Planner.decide(w, s.copy(lateRingEnabled = false), RuntimeState(lastHandledAt = t("2026-11-16T05:55")), SEOUL, t("2026-11-16T07:20"))
    check(!d5.late && d5.missed?.kind == Kind.MAIN) { "late off $d5" }

    // 15) 첫 실행 / 시계를 뒤로 돌림 → 과거 알람을 울리지 않음
    val d6 = Planner.decide(w, s, RuntimeState(), SEOUL, t("2026-11-16T07:10"))
    check(!d6.late && d6.missed == null && d6.state.lastHandledAt == t("2026-11-16T07:10")) { "first run $d6" }
    val d7 = Planner.decide(w, s, RuntimeState(lastHandledAt = t("2026-11-16T09:00")), SEOUL, t("2026-11-16T05:50"))
    check(!d7.late && f(d7.next!!.at) == "2026-11-16T06:00") { "clock back $d7" }

    // 16) 정상 대기 중엔 아무 일 없음
    val d8 = Planner.decide(w, s, RuntimeState(lastHandledAt = t("2026-11-16T06:00")), SEOUL, t("2026-11-16T06:05"))
    check(!d8.late && d8.missed == null && f(d8.next!!.at) == "2026-11-16T06:15") { "idle $d8" }

    // 17) 테스트 알람
    val d9 = Planner.decide(w, s, RuntimeState(lastHandledAt = t("2026-11-16T03:00"), testAt = t("2026-11-16T03:01")), SEOUL, t("2026-11-16T03:00:30"))
    check(d9.next!!.kind == Kind.TEST) { "test $d9" }

    // 18) 서머타임 (뉴욕 2026-03-08 02:00 → 03:00): 02:30 알람은 03:30으로, 미리 알람은 실제 시간 기준
    val ny = ZoneId.of("America/New_York")
    val wd = week(7 to "02:30")   // 2026-03-08 = 일요일
    val dst = Planner.occurrences(wd, s, RuntimeState(), ny, t("2026-03-08T00:00", ny), t("2026-03-08T05:00", ny))
    check(list(dst, ny) == listOf("01:30P", "01:45P", "03:00P", "03:15P", "03:30M")) { "dst ${list(dst, ny)}" }

    // 19) 간격 설정 (10분, 30분 전부터)
    val c = Planner.occurrences(w, s.copy(preLeadMinutes = 30, preIntervalMinutes = 10), RuntimeState(), SEOUL, t("2026-11-16T06:00"), t("2026-11-16T07:00"))
    check(list(c) == listOf("06:30P", "06:40P", "06:50P", "07:00M")) { "interval ${list(c)}" }

    // 20) 알람 설정 요청 알림: 기본 일요일 22:00, 켜진 알람이 하나도 없을 때만
    check(f(Planner.nextWeekly(s, SEOUL, t("2026-11-16T09:00"))) == "2026-11-22T22:00") { "remind" }
    check(f(Planner.nextWeekly(s, SEOUL, t("2026-11-22T22:00"))) == "2026-11-29T22:00") { "remind exact" }
    check(f(Planner.nextWeekly(s, SEOUL, t("2026-11-22T21:59"))) == "2026-11-22T22:00") { "remind same day" }
    check(Planner.needsSetupReminder(List(7) { DayAlarm() })) { "remind when none" }
    check(!Planner.needsSetupReminder(week(3 to "07:00"))) { "no remind when any on" }

    // 21) 일주일 내내 연속 시뮬레이션: 알람이 하나도 빠지지 않고 정확히 한 번씩 울리는지
    val full = week(1 to "07:00", 2 to "06:30", 3 to "06:30", 4 to "06:30", 5 to "06:30", 6 to "09:00", 7 to "00:30")
    var state = RuntimeState(lastHandledAt = t("2026-11-15T12:00"))
    var now = t("2026-11-15T12:00")
    val rang = ArrayList<String>()
    repeat(500) {
        val d = Planner.decide(full, s, state, SEOUL, now); state = d.state
        val n = d.next ?: return@repeat
        if (n.at > t("2026-11-23T00:00")) return@repeat
        now = d.fireAt
        val (ring, ns) = Planner.onFired(n, state); state = ns
        if (ring) rang += "${f(n.at)}${n.kind.name[0]}"
    }
    val expected = Planner.occurrences(full, s, RuntimeState(), SEOUL, t("2026-11-15T12:00"), t("2026-11-23T00:00"))
    check(rang == expected.map { "${f(it.at)}${it.kind.name[0]}" } && rang.size == 35) { "week sim ${rang.size}: $rang" }

    // ===== 검토 후 추가된 시나리오 =====
    // 22) 여러 개를 놓쳤을 때 본 알람 우선: 일 23:30 본 알람 + 월 00:15 알람(미리 알람 23:15~00:00), 폰이 23:20~23:48 꺼짐
    val wn = week(7 to "23:30", 1 to "00:15")
    val d22 = Planner.decide(wn, s, RuntimeState(lastHandledAt = t("2026-11-22T23:16")), SEOUL, t("2026-11-22T23:48"))
    check(d22.late && d22.next!!.kind == Kind.MAIN && f(d22.next!!.at) == "2026-11-22T23:30") { "prefer main $d22" }
    // 늦은 본 알람이 도착하면 그 사이 지난 미리 알람(23:45)으로 곧바로 덮어쓰지 않는다
    val (r22, st22) = Planner.onFired(d22.next!!, d22.state, late = true, now = t("2026-11-22T23:48:01"))
    val d22b = Planner.decide(wn, s, st22, SEOUL, t("2026-11-22T23:48:02"))
    check(r22 && !d22b.late && f(d22b.next!!.at) == "2026-11-23T00:00") { "after late main $d22b" }
    // 23:52에 켜짐: 23:45 미리 알람은 7분 지남(5분 초과)이어도 본 알람(22분 지남)은 울린다
    val d22c = Planner.decide(wn, s, RuntimeState(lastHandledAt = t("2026-11-22T23:16")), SEOUL, t("2026-11-22T23:52"))
    check(d22c.late && d22c.next!!.kind == Kind.MAIN) { "main within grace $d22c" }

    // 23) 늦게 울리기 꺼짐이어도, 예약해 둔 알람의 전달이 늦은 것은 울린다 (보조 예약이 무력화되지 않도록)
    val off2 = s.copy(lateRingEnabled = false)
    val d23 = Planner.decide(w, off2, RuntimeState(lastHandledAt = t("2026-11-16T06:45")), SEOUL, t("2026-11-16T07:00:30"), inFlightAt = main)
    check(d23.late && d23.next!!.at == main && d23.missed == null) { "in-flight with late off $d23" }
    // 전달 여유(10분)가 지나면 놓친 알람으로 처리
    val d23b = Planner.decide(w, off2, RuntimeState(lastHandledAt = t("2026-11-16T06:45")), SEOUL, t("2026-11-16T07:12"), inFlightAt = main)
    check(!d23b.late && d23b.missed?.at == main) { "in-flight expired $d23b" }

    // 24) 알람 시각 직후 설정을 저장해도 도착 대기 중인 알람을 삼키지 않는다
    val wm = Planner.watermarkOnSave(now = main + 200, inFlightAt = main)
    check(wm == main - 1 && Planner.onFired(occ, RuntimeState(lastHandledAt = wm)).first) { "save watermark $wm" }
    check(Planner.watermarkOnSave(now = main - 5000, inFlightAt = main) == main - 5000) { "save normal" }

    // 25) 다시 알림의 원래 본 알람을 끄면 다시 알림도 무효
    val stSn = RuntimeState(snoozeAt = main + 5 * 60_000, snoozeMainAt = main, snoozeCount = 1)
    check(Planner.snoozeStillValid(w, stSn, SEOUL)) { "snooze valid" }
    check(!Planner.snoozeStillValid(week(2 to "06:30"), stSn, SEOUL)) { "snooze invalid when day off" }
    check(!Planner.snoozeStillValid(week(1 to "07:05"), stSn, SEOUL)) { "snooze invalid when time changed" }

    // 26) 일주일 시뮬레이션 (늦게 울리기 꺼짐 + 매 알람 직후 감시 작업이 끼어드는 상황)
    var st26 = RuntimeState(lastHandledAt = t("2026-11-15T12:00"))
    var now26 = t("2026-11-15T12:00")
    val rang26 = ArrayList<String>()
    var inFlight = 0L
    repeat(500) {
        val d = Planner.decide(full, off2, st26, SEOUL, now26, inFlight); st26 = d.state
        val n = d.next ?: return@repeat
        if (n.at > t("2026-11-23T00:00")) return@repeat
        inFlight = n.at
        // 알람 시각 0.2초 뒤 감시 작업이 먼저 실행됨 (수신기보다 먼저)
        val g = Planner.decide(full, off2, st26, SEOUL, d.fireAt + 200, inFlight); st26 = g.state
        now26 = d.fireAt + 300
        val (ring, ns) = Planner.onFired(n, st26, late = false, now = now26); st26 = ns
        if (ring) rang26 += "${f(n.at)}${n.kind.name[0]}"
    }
    check(rang26 == rang) { "race sim ${rang26.size} vs ${rang.size}: ${rang26.take(5)}" }

    // 27) 늦은 알람(06:55 미리 알람, 06:59:10에 결정)이 07:00:11에야 도착해도 07:00 본 알람은 살아 있어야 함
    val pre655 = Occurrence(t("2026-11-16T06:55"), Kind.PRE, main, 5)
    val (r27, st27) = Planner.onFired(pre655, RuntimeState(lastHandledAt = pre655.at - 1), late = true, now = t("2026-11-16T06:59:10"))
    val (r27b, _) = Planner.onFired(occ, st27)
    check(r27 && r27b) { "late delivery must not swallow main" }

    println(if (fails == 0) "ALL PASSED (27 scenarios, week sim ${rang.size} alarms)" else "$fails FAILED")
    return fails
}

/** 알람 시각 계산 시나리오 테스트 (자정, 주 경계, 서머타임, 놓친 알람, 중복 방지, 일주일 시뮬레이션 등). */
class PlannerTest {
    @Test fun allScenarios() = assertEquals(0, runAll())
}
