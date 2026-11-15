package io.github.welshcat.weekalarm

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** 지금 울리고 있는 알람 (울림 화면이 구독한다). */
object RingState {
    @Volatile var current: Occurrence? = null; private set
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun set(o: Occurrence?) { current = o; main.post { listeners.toList().forEach { it() } } }
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
}

/**
 * 알람을 울리는 포그라운드 서비스.
 * 시작에 실패하면 "계속 반복되는 비상 알림 소리"로 대체한다.
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var ringer: Ringer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var current: Occurrence? = null
    private var lastStartId = 0
    private val timeout = Runnable { onTimeout() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_RING -> {
                val occ = Occ.get(intent)
                val redelivered = flags and START_FLAG_REDELIVERY != 0
                // 시스템이 다시 전달한 요청은 아직 '울리는 중'으로 기록된 알람일 때만 울린다 (오래된 알람 재울림 방지)
                val stale = redelivered && run {
                    val r = Store.ringing(this)
                    r == null || r.first != occ ||
                        System.currentTimeMillis() - r.second > (Store.settings(this).ringMinutes + 1) * 60_000L
                }
                if (occ == null || stale) {
                    if (stale && occ != null) {
                        // startForegroundService 계약을 지키기 위해 잠깐 포그라운드로 올렸다가 바로 내린다
                        goForeground(Notifs.ringing(this, occ, Store.settings(this), Store.state(this)), occ, fallbackOnFail = false)
                        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                        // 기록된 '울리는 중' 알람이 다른 알람이면 지우지 않고, 아직 시간이 남았으면 다시 울린다
                        Scheduler.resumeIfInterrupted(this)
                    }
                    stopIfIdle(); return START_NOT_STICKY
                }
                startRinging(occ)
            }
            ACTION_DISMISS -> userDismiss()
            ACTION_SNOOZE -> userSnooze()
            // 울리는 중인 알림을 사용자가 밀어서 지우면 다시 띄운다 (해제 수단이 사라지지 않도록)
            ACTION_REPOST -> current?.let { goForeground(Notifs.ringing(this, it, Store.settings(this), Store.state(this)), it) }
                ?: stopIfIdle()
            else -> stopIfIdle()
        }
        // 시스템이 서비스를 죽였다가 살리면 마지막 울림 요청을 다시 전달받아 다시 울린다
        return START_REDELIVER_INTENT
    }

    private fun startRinging(occ: Occurrence) {
        val s = Store.settings(this)
        // 1) 즉시 포그라운드로 (전체 화면 알람 알림 포함)
        goForeground(Notifs.ringing(this, occ, s, Store.state(this)), occ)
        Store.setRinging(this, occ)
        WakeLocks.releaseStartup()
        try {
            // 새 알람으로 교체될 때도 매번 시간을 연장한다
            if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weekalarm:ring").apply { setReferenceCounted(false) }
            wakeLock?.acquire((s.ringMinutes + 2) * 60_000L)
        } catch (_: Exception) {}

        // 2) 이미 울리는 중이면 새 알람으로 교체
        handler.removeCallbacks(timeout)
        current = occ
        RingState.set(occ)
        ringer?.stop()
        ringer = Ringer(this).also { r ->
            try { r.start(s) } catch (e: Exception) {
                Store.log(this, "소리 시작 오류: $e → 비상 알림으로 대체")
                Notifs.fallback(this, occ)
            }
        }
        // 3) 끄지 않으면 정해진 시간 뒤 멈춤
        handler.postDelayed(timeout, s.ringMinutes.coerceAtLeast(1) * 60_000L)
    }

    private fun goForeground(n: Notification, occ: Occurrence, fallbackOnFail: Boolean = true) {
        val attempts = ArrayList<() -> Unit>()
        if (Build.VERSION.SDK_INT >= 34) {
            attempts += { startForeground(Notifs.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) }
            attempts += { startForeground(Notifs.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        } else if (Build.VERSION.SDK_INT >= 29) {
            attempts += { startForeground(Notifs.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        }
        attempts += { startForeground(Notifs.ID_RING, n) }
        for (a in attempts) {
            try { a(); return } catch (e: Exception) { Store.log(this, "포그라운드 시작 실패: ${e.javaClass.simpleName}") }
        }
        // 모두 실패: 소리는 계속 시도하고, 비상 알림(반복 알람음)도 함께 띄운다
        if (fallbackOnFail) Notifs.fallback(this, occ)
    }

    private fun userDismiss() {
        val occ = current ?: return stopIfIdle()
        if (occ.kind == Kind.MAIN || occ.kind == Kind.SNOOZE) {
            Store.updateState(this) { st -> Unit to st.copy(snoozeAt = 0L, snoozeMainAt = 0L, snoozeCount = 0) }
        }
        Store.log(this, "해제: ${Fmt.kind(occ)}")
        finishRinging()
        Scheduler.reschedule(this, "해제")
    }

    private fun userSnooze() {
        val occ = current ?: return stopIfIdle()
        val s = Store.settings(this)
        val ok = Store.updateState(this) { st ->
            if (Planner.canSnooze(occ, s, st)) true to Planner.snooze(occ, s, st, System.currentTimeMillis()) else false to st
        }
        Store.log(this, if (ok) "다시 알림 ${s.snoozeMinutes}분" else "다시 알림 불가 → 해제")
        finishRinging()
        Scheduler.reschedule(this, "다시 알림")
    }

    private fun onTimeout() {
        val occ = current ?: return stopIfIdle()
        val s = Store.settings(this)
        if (occ.kind == Kind.MAIN || occ.kind == Kind.SNOOZE) {
            val snoozed = Store.updateState(this) { st ->
                if (Planner.canSnooze(occ, s, st)) true to Planner.snooze(occ, s, st, System.currentTimeMillis()) else false to st
            }
            if (snoozed) Store.log(this, "응답 없음 → 자동 다시 알림 ${s.snoozeMinutes}분")
            else { Store.log(this, "응답 없음 → 울림 종료"); Notifs.missed(this, occ) }
        } else {
            Store.log(this, "응답 없음 → ${Fmt.kind(occ)} 종료")
        }
        finishRinging()
        Scheduler.reschedule(this, "자동 종료")
    }

    private fun finishRinging() {
        handler.removeCallbacks(timeout)
        current = null
        Store.setRinging(this, null)
        RingState.set(null)
        ringer?.stop(); ringer = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        Notifs.cancel(this, Notifs.ID_RING)
        Notifs.cancel(this, Notifs.ID_FALLBACK)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        // 마지막으로 받은 요청 번호로만 종료: 그 사이 새 알람이 들어왔으면 서비스가 멈추지 않는다
        stopSelfResult(lastStartId)
    }

    private fun stopIfIdle() { if (current == null) { WakeLocks.releaseStartup(); stopSelfResult(lastStartId) } }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val interrupted = current != null
        if (interrupted) { current = null; RingState.set(null); ringer?.stop(); ringer = null }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
        // 사용자가 끄지 않았는데 서비스가 종료됐으면 다시 울린다
        if (interrupted) Scheduler.resumeIfInterrupted(this)
    }

    companion object {
        const val ACTION_RING = "io.github.welshcat.weekalarm.RING"
        const val ACTION_DISMISS = "io.github.welshcat.weekalarm.DISMISS"
        const val ACTION_SNOOZE = "io.github.welshcat.weekalarm.SNOOZE"
        const val ACTION_REPOST = "io.github.welshcat.weekalarm.REPOST"

        fun start(ctx: Context, occ: Occurrence) {
            val i = Intent(ctx, RingService::class.java).setAction(ACTION_RING).also { Occ.put(it, occ) }
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // 서비스를 시작할 수 없으면: 소리가 계속 반복되는 비상 알림 + 전체 화면
                Store.log(ctx, "울림 서비스 시작 실패: $e → 비상 알림")
                Notifs.fallback(ctx, occ)
                WakeLocks.releaseStartup()
            }
        }

        fun action(ctx: Context, action: String) {
            try { ctx.startService(Intent(ctx, RingService::class.java).setAction(action)) }
            catch (e: Exception) { Store.log(ctx, "동작 전달 실패: $e") }
        }
    }
}
