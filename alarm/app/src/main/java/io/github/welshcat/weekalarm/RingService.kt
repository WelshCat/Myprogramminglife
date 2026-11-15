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
    private val timeout = Runnable { onTimeout() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> {
                val occ = Occ.get(intent)
                if (occ == null) { stopIfIdle(); return START_NOT_STICKY }
                startRinging(occ)
            }
            ACTION_DISMISS -> userDismiss()
            ACTION_SNOOZE -> userSnooze()
            ACTION_SKIP_PRE -> userSkipPre()
            else -> stopIfIdle()
        }
        // 시스템이 서비스를 죽였다가 살리면 마지막 울림 요청을 다시 전달받아 다시 울린다
        return START_REDELIVER_INTENT
    }

    private fun startRinging(occ: Occurrence) {
        val s = Store.settings(this)
        // 1) 즉시 포그라운드로 (전체 화면 알람 알림 포함)
        goForeground(Notifs.ringing(this, occ, s, Store.state(this)))
        WakeLocks.releaseStartup()
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weekalarm:ring")
                    .apply { setReferenceCounted(false); acquire((s.ringMinutes + 2) * 60_000L) }
            }
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

    private fun goForeground(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                try { startForeground(Notifs.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) }
                catch (e: Exception) { startForeground(Notifs.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifs.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(Notifs.ID_RING, n)
            }
        } catch (e: Exception) {
            // 포그라운드 실패해도 소리는 계속 시도하고, 알림은 일반 알림으로 띄운다
            Store.log(this, "포그라운드 시작 실패: $e")
            try { Notifs.post(this, Notifs.ID_RING, n) } catch (_: Exception) {}
        }
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

    private fun userSkipPre() {
        val occ = current ?: return stopIfIdle()
        Store.updateState(this) { st -> Unit to st.copy(skipPreForMainAt = occ.mainAt) }
        Store.log(this, "남은 미리 알람 건너뛰기 (본 알람 ${Fmt.time(occ.mainAt)}은 울림)")
        finishRinging()
        Scheduler.reschedule(this, "미리 알람 건너뛰기")
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
        RingState.set(null)
        ringer?.stop(); ringer = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        Notifs.cancel(this, Notifs.ID_RING)
        Notifs.cancel(this, Notifs.ID_FALLBACK)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        stopSelf()
    }

    private fun stopIfIdle() { if (current == null) { WakeLocks.releaseStartup(); stopSelf() } }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (current != null) { RingState.set(null); ringer?.stop() }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "io.github.welshcat.weekalarm.RING"
        const val ACTION_DISMISS = "io.github.welshcat.weekalarm.DISMISS"
        const val ACTION_SNOOZE = "io.github.welshcat.weekalarm.SNOOZE"
        const val ACTION_SKIP_PRE = "io.github.welshcat.weekalarm.SKIP_PRE"

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
