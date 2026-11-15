package io.github.welshcat.weekalarm

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 저장소. 재부팅 후 잠금을 풀기 전에도 알람이 울리도록 "기기 보호 저장소"(Direct Boot)에 저장한다.
 * 상태 변경은 commit()으로 즉시 디스크에 쓴다 (프로세스가 죽어도 남도록).
 */
object Store {
    private val lock = Any()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("alarm", Context.MODE_PRIVATE)

    // ---------------- 주간 일정 ----------------
    fun week(ctx: Context): List<DayAlarm> {
        val raw = prefs(ctx).getString("week", null) ?: return List(7) { DayAlarm() }
        return try {
            raw.split(";").map { p ->
                val (e, h, m) = p.split(",")
                DayAlarm(e == "1", h.toInt().coerceIn(0, 23), m.toInt().coerceIn(0, 59))
            }.takeIf { it.size == 7 } ?: List(7) { DayAlarm() }
        } catch (e: Exception) { List(7) { DayAlarm() } }
    }

    fun saveWeek(ctx: Context, week: List<DayAlarm>) = synchronized(lock) {
        val raw = week.joinToString(";") { "${if (it.enabled) 1 else 0},${it.hour},${it.minute}" }
        // 설정을 바꾼 시점 이전의 알람은 "처리됨" — 방금 지난 시각으로 저장해도 소급해서 울리지 않는다
        val st = state(ctx).copy(lastHandledAt = System.currentTimeMillis())
        prefs(ctx).edit().putString("week", raw).also { writeState(it, st) }.commit()
    }

    // ---------------- 설정 ----------------
    fun settings(ctx: Context): Settings {
        val p = prefs(ctx); val d = Settings()
        return Settings(
            preEnabled = p.getBoolean("preEnabled", d.preEnabled),
            preLeadMinutes = p.getInt("preLead", d.preLeadMinutes),
            preIntervalMinutes = p.getInt("preInterval", d.preIntervalMinutes),
            skipPreButton = p.getBoolean("skipPreButton", d.skipPreButton),
            snoozeEnabled = p.getBoolean("snoozeEnabled", d.snoozeEnabled),
            snoozeMinutes = p.getInt("snoozeMinutes", d.snoozeMinutes),
            snoozeMaxCount = p.getInt("snoozeMax", d.snoozeMaxCount),
            ringMinutes = p.getInt("ringMinutes", d.ringMinutes),
            volumePercent = p.getInt("volume", d.volumePercent),
            gradualVolume = p.getBoolean("gradual", d.gradualVolume),
            vibrate = p.getBoolean("vibrate", d.vibrate),
            soundUri = p.getString("soundUri", null),
            soundName = p.getString("soundName", null),
            lateRingEnabled = p.getBoolean("lateRing", d.lateRingEnabled),
            weeklyReminder = p.getBoolean("weekly", d.weeklyReminder),
            weeklyDay = p.getInt("weeklyDay", d.weeklyDay),
            weeklyHour = p.getInt("weeklyHour", d.weeklyHour),
            weeklyMinute = p.getInt("weeklyMinute", d.weeklyMinute),
        )
    }

    fun saveSettings(ctx: Context, s: Settings) = synchronized(lock) {
        val st = state(ctx).copy(lastHandledAt = System.currentTimeMillis())
        prefs(ctx).edit()
            .putBoolean("preEnabled", s.preEnabled).putInt("preLead", s.preLeadMinutes)
            .putInt("preInterval", s.preIntervalMinutes).putBoolean("skipPreButton", s.skipPreButton)
            .putBoolean("snoozeEnabled", s.snoozeEnabled).putInt("snoozeMinutes", s.snoozeMinutes)
            .putInt("snoozeMax", s.snoozeMaxCount).putInt("ringMinutes", s.ringMinutes)
            .putInt("volume", s.volumePercent).putBoolean("gradual", s.gradualVolume)
            .putBoolean("vibrate", s.vibrate).putString("soundUri", s.soundUri).putString("soundName", s.soundName)
            .putBoolean("lateRing", s.lateRingEnabled).putBoolean("weekly", s.weeklyReminder)
            .putInt("weeklyDay", s.weeklyDay).putInt("weeklyHour", s.weeklyHour).putInt("weeklyMinute", s.weeklyMinute)
            .also { writeState(it, st) }
            .commit()
    }

    // ---------------- 실행 상태 ----------------
    fun state(ctx: Context): RuntimeState {
        val p = prefs(ctx)
        return RuntimeState(
            lastHandledAt = p.getLong("lastHandled", 0L),
            skipPreForMainAt = p.getLong("skipPreFor", 0L),
            snoozeAt = p.getLong("snoozeAt", 0L),
            snoozeMainAt = p.getLong("snoozeMainAt", 0L),
            snoozeCount = p.getInt("snoozeCount", 0),
            testAt = p.getLong("testAt", 0L),
        )
    }

    private fun writeState(e: SharedPreferences.Editor, st: RuntimeState) {
        e.putLong("lastHandled", st.lastHandledAt).putLong("skipPreFor", st.skipPreForMainAt)
            .putLong("snoozeAt", st.snoozeAt).putLong("snoozeMainAt", st.snoozeMainAt)
            .putInt("snoozeCount", st.snoozeCount).putLong("testAt", st.testAt)
    }

    /** 상태를 원자적으로 읽고-고치고-저장한다. */
    fun <T> updateState(ctx: Context, block: (RuntimeState) -> Pair<T, RuntimeState>): T = synchronized(lock) {
        val (result, st) = block(state(ctx))
        prefs(ctx).edit().also { writeState(it, st) }.commit()
        result
    }

    // ---------------- 예약 기록 (점검 화면용) ----------------
    fun scheduledAt(ctx: Context) = prefs(ctx).getLong("scheduledAt", 0L)
    fun setScheduled(ctx: Context, at: Long, occAt: Long, kind: String) {
        prefs(ctx).edit().putLong("scheduledAt", at).putLong("scheduledOccAt", occAt)
            .putString("scheduledKind", kind).commit()
    }
    fun scheduledKind(ctx: Context) = prefs(ctx).getString("scheduledKind", "") ?: ""
    fun scheduledOccAt(ctx: Context) = prefs(ctx).getLong("scheduledOccAt", 0L)

    // ---------------- 알람 음량 복원용 ----------------
    fun savedAlarmVolume(ctx: Context) = prefs(ctx).getInt("origVolume", -1)
    fun setSavedAlarmVolume(ctx: Context, v: Int) { prefs(ctx).edit().putInt("origVolume", v).commit() }

    fun permissionAsked(ctx: Context) = prefs(ctx).getBoolean("permAsked", false)
    fun setPermissionAsked(ctx: Context) { prefs(ctx).edit().putBoolean("permAsked", true).apply() }

    // ---------------- 진단 기록 ----------------
    fun log(ctx: Context, msg: String) {
        try {
            synchronized(lock) {
                val p = prefs(ctx)
                val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date())
                val lines = (p.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }.takeLast(150)
                p.edit().putString("log", (lines + "$ts  $msg").joinToString("\n")).apply()
            }
        } catch (_: Exception) {}
    }

    fun logText(ctx: Context) = prefs(ctx).getString("log", "") ?: ""
}
