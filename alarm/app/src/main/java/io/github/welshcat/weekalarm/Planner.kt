package io.github.welshcat.weekalarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/*
 * 알람 시각 계산 (순수 Kotlin — 안드로이드 API를 쓰지 않아 PC에서 테스트할 수 있다).
 *
 * 핵심 원칙
 *  - "다음에 울릴 알람 하나"를 항상 지금 시각과 설정으로부터 새로 계산한다 (상태가 꼬일 여지를 줄인다).
 *  - lastHandledAt: 여기까지의 알람은 처리(울림/건너뜀)했다는 표시. 중복 울림을 막고 놓친 알람을 찾는 기준.
 */

enum class Kind { PRE, MAIN, SNOOZE, TEST }

/** 요일 하나의 알람. */
data class DayAlarm(val enabled: Boolean = false, val hour: Int = 7, val minute: Int = 0)

data class Settings(
    // 미리 알람
    val preEnabled: Boolean = true,
    val preLeadMinutes: Int = 60,          // 본 알람 몇 분 전부터
    val preIntervalMinutes: Int = 15,      // 몇 분 간격으로
    val skipPreButton: Boolean = true,     // 울림 화면에 "남은 미리 알람 건너뛰기" 버튼
    // 다시 알림 (본 알람에만)
    val snoozeEnabled: Boolean = false,
    val snoozeMinutes: Int = 5,
    val snoozeMaxCount: Int = 3,
    // 소리
    val ringMinutes: Int = 5,              // 끄지 않으면 이 시간 후 자동으로 멈춤
    val volumePercent: Int = 80,
    val gradualVolume: Boolean = true,
    val vibrate: Boolean = true,
    val soundUri: String? = null,          // null = 기본 알람음
    val soundName: String? = null,
    // 안전장치
    val lateRingEnabled: Boolean = true,   // 놓친 알람을 늦게라도 울리기
    // 주간 알림
    val weeklyReminder: Boolean = true,
    val weeklyDay: Int = 1,                // 1=월 … 7=일
    val weeklyHour: Int = 0,
    val weeklyMinute: Int = 0,
)

data class RuntimeState(
    val lastHandledAt: Long = 0L,
    val skipPreForMainAt: Long = 0L,       // 이 본 알람의 남은 미리 알람은 건너뜀
    val snoozeAt: Long = 0L,
    val snoozeMainAt: Long = 0L,
    val snoozeCount: Int = 0,
    val testAt: Long = 0L,
)

/** 울릴 알람 하나. at = 원래 울려야 하는 시각, mainAt = 연결된 본 알람 시각. */
data class Occurrence(val at: Long, val kind: Kind, val mainAt: Long, val minutesBefore: Int = 0)

/** 예약 결정. */
data class Decision(
    val next: Occurrence?,     // 예약할 알람 (없으면 null)
    val fireAt: Long,          // 실제 예약 시각 (늦게 울리는 알람이면 지금+1초)
    val late: Boolean,         // 놓친 알람을 지금 울리는 것인지
    val state: RuntimeState,   // 갱신된 상태
    val missed: Occurrence?,   // 너무 늦어서 울리지 않고 알림만 보낼 알람
)

object Planner {
    const val MINUTE = 60_000L
    const val DAY = 24 * 60 * MINUTE
    const val LATE_GRACE_MAIN = 60 * MINUTE   // 본 알람: 60분 이내면 늦게라도 울림
    const val LATE_GRACE_PRE = 5 * MINUTE     // 미리 알람: 5분 이내만 (곧 다음 미리 알람이 오므로)

    private fun priority(k: Kind) = when (k) { Kind.MAIN -> 0; Kind.SNOOZE -> 1; Kind.TEST -> 2; Kind.PRE -> 3 }

    /** [from, to] 주변의 본 알람 시각들. 서머타임 등으로 없는 시각은 ZonedDateTime 규칙대로 앞으로 밀린다. */
    fun mainTimes(week: List<DayAlarm>, zone: ZoneId, from: Long, to: Long): List<Long> {
        val start = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1)
        val end = Instant.ofEpochMilli(to).atZone(zone).toLocalDate().plusDays(1)
        val out = ArrayList<Long>()
        var d: LocalDate = start
        while (!d.isAfter(end)) {
            val a = week[d.dayOfWeek.value - 1]
            if (a.enabled) out += ZonedDateTime.of(d, LocalTime.of(a.hour, a.minute), zone).toInstant().toEpochMilli()
            d = d.plusDays(1)
        }
        return out
    }

    /** fromExclusive < at <= toInclusive 인 모든 알람 (시간순). */
    fun occurrences(
        week: List<DayAlarm>, s: Settings, st: RuntimeState, zone: ZoneId,
        fromExclusive: Long, toInclusive: Long,
    ): List<Occurrence> {
        val usePre = s.preEnabled && s.preIntervalMinutes > 0 && s.preLeadMinutes >= s.preIntervalMinutes
        val lead = if (usePre) s.preLeadMinutes * MINUTE else 0L
        val all = ArrayList<Occurrence>()
        for (main in mainTimes(week, zone, fromExclusive, toInclusive + lead)) {
            all += Occurrence(main, Kind.MAIN, main)
            if (usePre && main != st.skipPreForMainAt) {
                var m = s.preIntervalMinutes
                while (m <= s.preLeadMinutes) {
                    all += Occurrence(main - m * MINUTE, Kind.PRE, main, m)
                    m += s.preIntervalMinutes
                }
            }
        }
        if (st.snoozeAt > 0) all += Occurrence(st.snoozeAt, Kind.SNOOZE, st.snoozeMainAt)
        if (st.testAt > 0) all += Occurrence(st.testAt, Kind.TEST, st.testAt)
        return all.asSequence()
            .filter { it.at > fromExclusive && it.at <= toInclusive }
            .groupBy { it.at }
            .map { (_, same) -> same.minByOrNull { priority(it.kind) }!! }
            .sortedBy { it.at }
    }

    fun next(week: List<DayAlarm>, s: Settings, st: RuntimeState, zone: ZoneId, after: Long): Occurrence? =
        occurrences(week, s, st, zone, after, after + 9 * DAY).firstOrNull()

    /** 지금 무엇을 예약해야 하는지 결정한다. 놓친 알람이 있으면 즉시 울리도록 한다. */
    fun decide(week: List<DayAlarm>, s: Settings, st0: RuntimeState, zone: ZoneId, now: Long): Decision {
        var last = st0.lastHandledAt
        // 첫 실행이거나, 시계가 뒤로 감긴 경우: 지금을 기준으로 삼는다 (뒤로 감긴 구간의 알람을 놓치지 않도록)
        if (last <= 0 || last > now + MINUTE) last = now
        var st = st0.copy(lastHandledAt = last)

        val missedList = if (last < now) occurrences(week, s, st, zone, last, now) else emptyList()
        val latest = missedList.lastOrNull()
        var missedNotify: Occurrence? = null
        if (latest != null) {
            val grace = if (latest.kind == Kind.PRE) LATE_GRACE_PRE else LATE_GRACE_MAIN
            if (s.lateRingEnabled && now - latest.at <= grace) {
                // 가장 최근에 놓친 알람을 지금 울린다. 그보다 이전 것들은 처리한 것으로 본다.
                st = cleanup(st.copy(lastHandledAt = latest.at - 1), now)
                return Decision(latest, now + 1000, true, st, null)
            }
            missedNotify = missedList.lastOrNull { it.kind != Kind.PRE }
        }
        st = cleanup(st.copy(lastHandledAt = maxOf(last, now)), now)
        val nx = next(week, s, st, zone, now)
        return Decision(nx, nx?.at ?: 0L, false, st, missedNotify)
    }

    /** 이미 지난 일회성 항목 정리. */
    private fun cleanup(st: RuntimeState, now: Long): RuntimeState {
        var s = st
        if (s.snoozeAt in 1..s.lastHandledAt) s = s.copy(snoozeAt = 0L, snoozeMainAt = 0L)
        if (s.testAt in 1..s.lastHandledAt) s = s.copy(testAt = 0L)
        if (s.skipPreForMainAt in 1 until now - DAY) s = s.copy(skipPreForMainAt = 0L)
        return s
    }

    /** 알람이 실제로 도착했을 때. 울려야 하면 true (이미 처리한 알람이면 false = 중복). */
    fun onFired(occ: Occurrence, st: RuntimeState): Pair<Boolean, RuntimeState> {
        if (occ.at <= st.lastHandledAt) return false to st
        var n = st.copy(lastHandledAt = occ.at)
        when (occ.kind) {
            Kind.MAIN -> n = n.copy(snoozeCount = 0)
            Kind.SNOOZE -> n = n.copy(snoozeAt = 0L)
            Kind.TEST -> n = n.copy(testAt = 0L)
            Kind.PRE -> {}
        }
        return true to n
    }

    fun canSnooze(occ: Occurrence, s: Settings, st: RuntimeState) =
        s.snoozeEnabled && (occ.kind == Kind.MAIN || occ.kind == Kind.SNOOZE) && st.snoozeCount < s.snoozeMaxCount

    fun snooze(occ: Occurrence, s: Settings, st: RuntimeState, now: Long) =
        st.copy(snoozeAt = now + s.snoozeMinutes * MINUTE, snoozeMainAt = occ.mainAt, snoozeCount = st.snoozeCount + 1)

    /** 다음 주간 알림 시각. */
    fun nextWeekly(s: Settings, zone: ZoneId, now: Long): Long {
        val nowZ = Instant.ofEpochMilli(now).atZone(zone)
        val dow = DayOfWeek.of(s.weeklyDay)
        var t = nowZ.toLocalDate().with(TemporalAdjusters.nextOrSame(dow))
            .atTime(s.weeklyHour, s.weeklyMinute).atZone(zone)
        if (!t.toInstant().isAfter(nowZ.toInstant())) t = t.plusWeeks(1)
        return t.toInstant().toEpochMilli()
    }
}
