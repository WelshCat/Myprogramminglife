package io.github.welshcat.weekalarm

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 방해 금지 모드 뚫기.
 * 방해 금지(완전 무음, 또는 '알람' 허용이 꺼진 중요 알림 모드)가 알람을 막고 있으면,
 * 울리는 동안만 '알람만 허용' 모드로 바꾸고 끝나면 원래대로 되돌린다.
 * 사용자가 '방해 금지 모드 접근' 권한을 한 번 허용해야 한다.
 *
 * 주의: 안드로이드 15부터 targetSdk 35 이상 앱은 전역 방해 금지 모드를 바꿀 수 없다.
 * 이 앱은 직접 설치용이라 targetSdk 34로 빌드해서 기존 방식을 쓴다.
 */
object Dnd {
    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun accessGranted(ctx: Context) = try { nm(ctx).isNotificationPolicyAccessGranted } catch (_: Exception) { false }

    /** 지금 방해 금지 모드가 알람 소리를 막고 있는지. */
    fun blocksAlarms(ctx: Context): Boolean = try {
        val nm = nm(ctx)
        when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true                       // 완전 무음
            NotificationManager.INTERRUPTION_FILTER_PRIORITY ->                        // 중요 알림만: 알람 허용 여부
                Build.VERSION.SDK_INT >= 28 &&
                    (nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0
            else -> false
        }
    } catch (_: Exception) { false }

    /** 울리기 직전에 호출. */
    fun overrideIfNeeded(ctx: Context, s: Settings) {
        if (!s.dndOverride || !blocksAlarms(ctx)) return
        if (!accessGranted(ctx)) {
            Store.log(ctx, "방해 금지 모드가 알람을 막고 있음 — 권한이 없어 소리가 안 날 수 있음")
            return
        }
        try {
            val nm = nm(ctx)
            if (Store.savedZen(ctx) < 0) Store.setSavedZen(ctx, nm.currentInterruptionFilter)
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            Store.log(ctx, "방해 금지 모드 → 울리는 동안 '알람만 허용'으로 변경")
        } catch (e: Exception) {
            Store.log(ctx, "방해 금지 모드 변경 실패: $e")
        }
    }

    /** 울림이 끝나면 원래 방해 금지 모드로 (사용자가 그 사이 직접 바꿨으면 건드리지 않음). */
    fun restoreIfNeeded(ctx: Context) {
        val orig = Store.savedZen(ctx)
        if (orig < 0 || RingState.current != null) return
        try {
            val nm = nm(ctx)
            if (accessGranted(ctx) && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS) {
                nm.setInterruptionFilter(orig)
                Store.log(ctx, "방해 금지 모드 원래대로 복원")
            }
        } catch (e: Exception) {
            Store.log(ctx, "방해 금지 모드 복원 실패: $e")
        }
        Store.setSavedZen(ctx, -1)
    }
}
