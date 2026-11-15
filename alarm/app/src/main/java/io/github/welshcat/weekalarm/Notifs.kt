package io.github.welshcat.weekalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager

object Notifs {
    const val ID_RING = 100
    const val ID_FALLBACK = 101
    const val ID_WEEKLY = 102
    const val ID_MISSED = 103
    const val ID_WARN = 104

    // 채널 소리는 만든 뒤에 바꿀 수 없어서 새 ID 사용 (예전 "ring"은 삭제)
    private const val CH_RING = "ring2"
    private const val CH_RING_OLD = "ring"
    private const val CH_FALLBACK = "fallback"
    private const val CH_WEEKLY = "weekly"
    private const val CH_INFO = "info"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun ensureChannels(ctx: Context) {
        val nm = nm(ctx)
        try { nm.deleteNotificationChannel(CH_RING_OLD) } catch (_: Exception) {}
        // 울림 알림: '소리' 알림으로 등록하되, 실제 알람음은 앱이 설정대로(선택한 소리·음량·점점 크게) 직접 낸다.
        // 채널 소리까지 알람음으로 하면 두 소리가 겹쳐 울리므로, 채널 소리는 앱에 포함된 무음 파일로 지정한다.
        nm.createNotificationChannel(NotificationChannel(CH_RING, "알람 울림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "알람이 울릴 때 표시되는 화면/알림 (알람 소리는 앱 설정의 알람음으로 울립니다)"
            setSound(android.net.Uri.parse("android.resource://${ctx.packageName}/raw/weekalarm_tone"),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        })
        // 비상용: 서비스가 실패하면 알림 자체가 알람음을 계속 반복한다
        nm.createNotificationChannel(NotificationChannel(CH_FALLBACK, "알람 (비상)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "알람 서비스를 시작하지 못했을 때 대신 울리는 알림"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 500, 700, 500, 700)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WEEKLY, "알람 설정 요청", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "켜진 알람이 하나도 없을 때 알람을 설정하라는 알림"
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "놓친 알람 / 경고", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "놓친 알람, 권한 문제 등"
        })
    }

    private fun alarmScreen(ctx: Context) = PendingIntent.getActivity(ctx, 10,
        Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun mainScreen(ctx: Context) = PendingIntent.getActivity(ctx, 11,
        Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun serviceAction(ctx: Context, action: String, rc: Int) = PendingIntent.getService(ctx, rc,
        Intent(ctx, RingService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun title(occ: Occurrence) = when (occ.kind) {
        Kind.PRE -> "미리 알람 · 본 알람 ${Fmt.time(occ.mainAt)}까지 ${occ.minutesBefore}분"
        Kind.MAIN -> "알람 ${Fmt.time(occ.mainAt)}"
        Kind.SNOOZE -> "다시 알림 (알람 ${Fmt.time(occ.mainAt)})"
        Kind.TEST -> "테스트 알람"
    }

    fun ringing(ctx: Context, occ: Occurrence, s: Settings, st: RuntimeState): Notification {
        ensureChannels(ctx)
        val b = Notification.Builder(ctx, CH_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title(occ))
            .setContentText("밀어서 해제하려면 누르세요")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(alarmScreen(ctx), true)
            .setContentIntent(alarmScreen(ctx))
            .setDeleteIntent(serviceAction(ctx, RingService.ACTION_REPOST, 23))
            .addAction(Notification.Action.Builder(null, "해제", serviceAction(ctx, RingService.ACTION_DISMISS, 20)).build())
        if (Planner.canSnooze(occ, s, st)) {
            b.addAction(Notification.Action.Builder(null, "다시 알림 ${s.snoozeMinutes}분",
                serviceAction(ctx, RingService.ACTION_SNOOZE, 21)).build())
        }
        return b.build()
    }

    /** 비상 알림: 알림 자체가 알람음을 끝없이 반복한다 (FLAG_INSISTENT). */
    fun fallback(ctx: Context, occ: Occurrence) {
        try {
            val ringMs = Store.settings(ctx).ringMinutes.coerceAtLeast(1) * 60_000L
            Ringer.raiseVolumeStatic(ctx, Store.settings(ctx).volumePercent)
            ensureChannels(ctx)
            val n = Notification.Builder(ctx, CH_FALLBACK)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title(occ))
                .setContentText("눌러서 끄기")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(mainScreen(ctx), true)
                .setContentIntent(mainScreen(ctx))
                .setDeleteIntent(PendingIntent.getBroadcast(ctx, 30,
                    Intent(ctx, SystemEventReceiver::class.java).setAction(ACTION_FALLBACK_CLEARED),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setTimeoutAfter(ringMs)                // 울림 시간이 지나면 자동으로 멈춤
                .build()
            n.flags = n.flags or Notification.FLAG_INSISTENT
            nm(ctx).notify(ID_FALLBACK, n)
            // 시간 초과로 사라질 때도 알람 음량을 원래대로 (사용자가 지우지 않아도)
            Scheduler.scheduleFallbackCleanup(ctx, System.currentTimeMillis() + ringMs + 5_000L)
        } catch (e: Exception) {
            Store.log(ctx, "비상 알림 실패: $e")
        }
    }

    /** 켜진 알람이 하나도 없을 때 보내는 "알람을 설정하세요" 알림. */
    fun setupReminder(ctx: Context) {
        ensureChannels(ctx)
        post(ctx, ID_WEEKLY, Notification.Builder(ctx, CH_WEEKLY)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("다음 주 알람을 설정해 주세요")
            .setContentText("지금 켜져 있는 알람이 하나도 없어요. 눌러서 설정하세요.")
            .setContentIntent(mainScreen(ctx))
            .setAutoCancel(true)
            .build())
    }

    fun missed(ctx: Context, occ: Occurrence) {
        ensureChannels(ctx)
        post(ctx, ID_MISSED, Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("놓친 알람: ${Fmt.dateTime(occ.at)}")
            .setContentText("알람이 꺼지지 않은 채 끝났거나 울릴 수 없는 상태였어요")
            .setContentIntent(mainScreen(ctx))
            .setAutoCancel(true)
            .build())
    }

    fun warning(ctx: Context, text: String) {
        try {
            ensureChannels(ctx)
            post(ctx, ID_WARN, Notification.Builder(ctx, CH_INFO)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("주간 알람 경고")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(mainScreen(ctx))
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {}
    }

    fun post(ctx: Context, id: Int, n: Notification) {
        try { nm(ctx).notify(id, n) } catch (e: SecurityException) { Store.log(ctx, "알림 권한 없음") }
    }

    const val CH_RING_ID = CH_RING
    const val CH_FALLBACK_ID = CH_FALLBACK
    const val ACTION_FALLBACK_CLEARED = "io.github.welshcat.weekalarm.FALLBACK_CLEARED"

    fun cancel(ctx: Context, id: Int) { try { nm(ctx).cancel(id) } catch (_: Exception) {} }
}
