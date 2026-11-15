package io.github.welshcat.weekalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.time.ZoneId

/**
 * 알람 예약 담당. 어디서 호출되든(앱 실행, 알람 도착, 재부팅, 시간 변경, 15분 감시 작업)
 * 항상 "지금 + 설정"으로부터 다시 계산해서 같은 결과를 예약한다 (몇 번 불려도 안전).
 */
object Scheduler {
    const val ACTION_FIRE = "io.github.welshcat.weekalarm.FIRE"
    const val ACTION_WEEKLY = "io.github.welshcat.weekalarm.WEEKLY"
    private const val RC_MAIN = 1
    private const val RC_BACKUP = 2
    private const val RC_WEEKLY = 3
    private const val RC_SHOW = 4
    private const val RC_RESUME = 5
    private const val GUARD_JOB_ID = 77
    private const val BACKUP_DELAY = 60_000L   // 보조 예약: 원래 시각 + 1분

    fun reschedule(ctx: Context, reason: String) {
        try {
            val week = Store.week(ctx)
            val s = Store.settings(ctx)
            val now = System.currentTimeMillis()
            val inFlight = Store.scheduledOccAt(ctx)
            val d = Store.updateState(ctx) { st ->
                val dec = Planner.decide(week, s, st, ZoneId.systemDefault(), now, inFlight)
                dec to dec.state
            }
            if (d.next != null) {
                setAlarm(ctx, d.next, d.fireAt, d.late)
                Store.setScheduled(ctx, d.fireAt, d.next.at, d.next.kind.name)
                if (d.late) Store.log(ctx, "놓친 알람 즉시 울림 예약: ${Fmt.dateTime(d.next.at)} ${Fmt.kind(d.next)} ($reason)")
            } else {
                cancelAlarm(ctx)
                Store.setScheduled(ctx, 0L, 0L, "")
            }
            d.missed?.let {
                Store.log(ctx, "놓친 알람: ${Fmt.dateTime(it.at)} (울릴 수 있는 시간이 지남)")
                Notifs.missed(ctx, it)
            }
            scheduleWeekly(ctx, s, now)
            ensureGuardJob(ctx)
            resumeIfInterrupted(ctx, s, now)
        } catch (e: Exception) {
            Store.log(ctx, "예약 오류 ($reason): $e")
            Notifs.warning(ctx, "알람 예약 중 오류가 발생했습니다. 앱을 열어 확인해 주세요.")
        }
    }

    private fun fireIntent(ctx: Context, occ: Occurrence, backup: Boolean) =
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE).also { Occ.put(it, occ); it.putExtra("backup", backup) }

    private fun pi(ctx: Context, rc: Int, intent: Intent) =
        PendingIntent.getBroadcast(ctx, rc, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun setAlarm(ctx: Context, occ: Occurrence, fireAt: Long, late: Boolean) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val main = pi(ctx, RC_MAIN, fireIntent(ctx, occ, false).putExtra("late", late))
        val show = PendingIntent.getActivity(ctx, RC_SHOW, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // 1차: 알람 시계 예약 (가장 강력함: 절전 모드 무시, 정확한 시각)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, show), main)
        } catch (e: Exception) {
            Store.log(ctx, "알람 시계 예약 실패: $e")
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, main)
            } catch (e2: Exception) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, main)
                Notifs.warning(ctx, "정확한 알람 권한이 없어 알람이 늦게 울릴 수 있습니다. 앱을 열어 권한을 허용해 주세요.")
            }
        }
        // 2차: 보조 예약 (1차가 어떤 이유로 사라져도 1분 뒤 울림. 1차가 울렸으면 중복으로 무시됨)
        // 정확한 알람이어야 안드로이드 12+에서 울림 서비스를 시작할 수 있다
        val backup = pi(ctx, RC_BACKUP, fireIntent(ctx, occ, true).putExtra("late", late))
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt + BACKUP_DELAY, backup)
        } catch (e: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt + BACKUP_DELAY, backup) }
            catch (e2: Exception) { Store.log(ctx, "보조 예약 실패: $e2") }
        }
    }

    private fun cancelAlarm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE)
        am.cancel(pi(ctx, RC_MAIN, i)); am.cancel(pi(ctx, RC_BACKUP, i))
    }

    private fun scheduleWeekly(ctx: Context, s: Settings, now: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, RC_WEEKLY, Intent(ctx, WeeklyReceiver::class.java).setAction(ACTION_WEEKLY))
        if (!s.weeklyReminder) { am.cancel(p); return }
        val at = Planner.nextWeekly(s, ZoneId.systemDefault(), now)
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
        catch (e: Exception) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }

    /** 15분마다 예약 상태를 점검하는 감시 작업 (재부팅 후에도 유지). */
    private fun ensureGuardJob(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(GUARD_JOB_ID) != null) return
        val info = JobInfo.Builder(GUARD_JOB_ID, ComponentName(ctx, GuardJobService::class.java))
            .setPeriodic(15 * 60_000L)
            .setPersisted(true)
            .build()
        try { js.schedule(info) } catch (e: Exception) { Store.log(ctx, "감시 작업 등록 실패: $e") }
    }

    /**
     * 울리던 알람이 사용자 조작 없이 중단됐으면(프로세스 종료, 서비스 경쟁 상태 등) 즉시 다시 울린다.
     * 정확한 알람으로 다시 걸어야 백그라운드에서도 울림 서비스를 시작할 수 있다.
     */
    fun resumeIfInterrupted(ctx: Context, s: Settings = Store.settings(ctx), now: Long = System.currentTimeMillis()) {
        val (occ, started) = Store.ringing(ctx) ?: return
        if (RingState.current != null) return                       // 이 프로세스에서 울리는 중
        if (now - started > (s.ringMinutes + 1) * 60_000L || now < started - 60_000L) {
            Store.setRinging(ctx, null); return                      // 이미 울림 시간이 지남
        }
        Store.log(ctx, "중단된 알람 다시 울림: ${Fmt.kind(occ)}")
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE).also { Occ.put(it, occ); it.putExtra("resume", true) }
        val p = pi(ctx, RC_RESUME, i)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(ctx, RC_SHOW, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { am.setAlarmClock(AlarmManager.AlarmClockInfo(now + 1000, show), p) }
        catch (e: Exception) {
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 1000, p) }
            catch (e2: Exception) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 1000, p) }
        }
    }

    /** 1분 뒤 테스트 알람. */
    fun scheduleTest(ctx: Context) {
        val at = System.currentTimeMillis() + 60_000L
        Store.updateState(ctx) { st -> Unit to st.copy(testAt = at) }
        Store.log(ctx, "테스트 알람 예약: ${Fmt.dateTime(at)}")
        reschedule(ctx, "테스트")
    }
}

/** 알람 도착. 다음 알람부터 먼저 예약한 뒤 소리를 낸다 (울리는 중 오류가 나도 다음 알람은 안전). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WakeLocks.acquireStartup(ctx)
        val occ = Occ.get(intent)
        if (occ == null) { Scheduler.reschedule(ctx, "잘못된 알람"); return }
        val backup = intent.getBooleanExtra("backup", false)
        val late = intent.getBooleanExtra("late", false)
        if (intent.getBooleanExtra("resume", false)) {
            // 중단됐던 알람 다시 울리기: 아직 '울리는 중'으로 기록되어 있을 때만 (그새 해제했으면 무시)
            if (Store.ringing(ctx)?.first == occ && RingState.current == null) RingService.start(ctx, occ)
            else WakeLocks.releaseStartup()
            return
        }
        val ring = try {
            Store.updateState(ctx) { st -> Planner.onFired(occ, st, late, System.currentTimeMillis()) }
        } catch (e: Exception) {
            Store.log(ctx, "상태 저장 오류: $e"); true       // 의심스러우면 울린다
        }
        Scheduler.reschedule(ctx, "알람 도착")
        if (!ring) { WakeLocks.releaseStartup(); return }   // 이미 울린 알람 (보조 예약 등)
        Store.log(ctx, "울림: ${Fmt.dateTime(occ.at)} ${Fmt.kind(occ)}" + (if (backup) " [보조 예약으로]" else "") + (if (late) " [늦게]" else ""))
        RingService.start(ctx, occ)
    }
}

/** 재부팅(잠금 해제 전 포함), 앱 업데이트, 시간/시간대 변경, 권한 변경 시 다시 예약. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.log(ctx, "시스템 이벤트: ${intent.action?.substringAfterLast('.')}")
        Ringer.restoreVolumeIfNeeded(ctx)
        Scheduler.reschedule(ctx, intent.action?.substringAfterLast('.') ?: "system")
    }
}

/** 주간 알림 (새로운 한 주). */
class WeeklyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifs.weekly(ctx, Store.week(ctx))
        Store.log(ctx, "주간 알림 표시")
        Scheduler.reschedule(ctx, "주간 알림")
    }
}

/** 15분마다 실행되는 감시 작업. */
class GuardJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Scheduler.reschedule(this, "감시 작업")
        return false
    }
    override fun onStopJob(params: JobParameters?) = true
}

/** 알람 정보를 Intent에 담고 꺼내기. */
object Occ {
    fun put(i: Intent, o: Occurrence) {
        i.putExtra("at", o.at).putExtra("kind", o.kind.name).putExtra("mainAt", o.mainAt).putExtra("before", o.minutesBefore)
    }
    fun get(i: Intent?): Occurrence? {
        if (i == null) return null
        val at = i.getLongExtra("at", 0L)
        val kind = runCatching { Kind.valueOf(i.getStringExtra("kind") ?: "") }.getOrNull()
        if (at <= 0 || kind == null) return null
        return Occurrence(at, kind, i.getLongExtra("mainAt", at), i.getIntExtra("before", 0))
    }
}

/** 알람 도착 ~ 서비스 시작 사이에 기기가 다시 잠들지 않도록. */
object WakeLocks {
    private var startup: PowerManager.WakeLock? = null
    @Synchronized fun acquireStartup(ctx: Context) {
        try {
            if (startup?.isHeld == true) return
            startup = ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weekalarm:startup")
                .apply { setReferenceCounted(false); acquire(60_000L) }
        } catch (_: Exception) {}
    }
    @Synchronized fun releaseStartup() {
        try { if (startup?.isHeld == true) startup?.release() } catch (_: Exception) {}
        startup = null
    }
}

object Fmt {
    val DAYS = listOf("월", "화", "수", "목", "금", "토", "일")
    fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)
    fun time(ms: Long): String {
        val z = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return hm(z.hour, z.minute)
    }
    fun dateTime(ms: Long): String {
        val z = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return "${DAYS[z.dayOfWeek.value - 1]} ${hm(z.hour, z.minute)}"
    }
    fun kind(o: Occurrence) = when (o.kind) {
        Kind.PRE -> "미리 알람(${o.minutesBefore}분 전)"
        Kind.MAIN -> "본 알람"
        Kind.SNOOZE -> "다시 알림"
        Kind.TEST -> "테스트 알람"
    }
    fun duration(ms: Long): String {
        val totalMin = (ms.coerceAtLeast(0) + 59_999) / 60_000
        val d = totalMin / (24 * 60); val h = (totalMin / 60) % 24; val m = totalMin % 60
        return buildString {
            if (d > 0) append("${d}일 ")
            if (h > 0) append("${h}시간 ")
            append("${m}분")
        }.trim()
    }
    fun isSdk(v: Int) = Build.VERSION.SDK_INT >= v
}
