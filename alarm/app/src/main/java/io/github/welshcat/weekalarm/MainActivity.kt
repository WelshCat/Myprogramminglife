package io.github.welshcat.weekalarm

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var nextCard: LinearLayout
    private lateinit var healthCard: LinearLayout
    private lateinit var warnBanner: android.widget.TextView
    private lateinit var scroll: ScrollView
    private lateinit var dayList: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(C.BG)
        Notifs.ensureChannels(this)

        val root = vertical(this, 16)
        val header = horizontal(this)
        header.add(text("주간 알람", 26f, C.TEXT, bold = true), w = 0, weight = 1f)
        header.add(button("설정") { startActivity(Intent(this, SettingsActivity::class.java)) },
            w = LinearLayout.LayoutParams.WRAP_CONTENT)
        root.add(header, top = 8)

        nextCard = vertical(this, 16).apply { background = rounded(C.PANEL, 18) }
        root.add(nextCard, top = 16)
        // 문제가 있을 때만 위에 짧게 표시 (자세한 내용과 해결 버튼은 화면 아래 점검 카드)
        warnBanner = button("") { scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) } }.apply {
            setTextColor(C.BG); background = pressable(C.ACCENT, 14); textSize = 14f
        }
        root.add(warnBanner, top = 12)

        root.add(text("요일별 알람", 14f, C.MUTED, bold = true), top = 22)
        dayList = vertical(this)
        root.add(dayList, top = 8)

        root.add(button("여러 요일 한 번에 설정", C.ACCENT, C.BG) { openSheet(setOf(0, 1, 2, 3, 4)) }, top = 14)
        root.add(button("테스트 알람 (1분 뒤)") {
            Scheduler.scheduleTest(this)
            Toast.makeText(this, "1분 뒤에 테스트 알람이 울립니다. 화면을 꺼 두고 확인해 보세요.", Toast.LENGTH_LONG).show()
            refresh()
        }, top = 10)
        healthCard = vertical(this, 16).apply { background = rounded(C.PANEL, 18) }
        root.add(healthCard, top = 22)
        root.add(text("※ 설정한 알람은 매주 같은 요일에 반복돼요. 켜진 알람이 하나도 없으면 일요일 22:00에 설정 요청 알림이 와요 (설정에서 변경).", 12f, C.MUTED), top = 14)

        scroll = ScrollView(this).apply { addView(root); isFillViewport = true }
        setContentView(scroll)
        root.padForSystemBars()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Store.permissionAsked(this)) {
            Store.setPermissionAsked(this)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // 알람이 울리는 중이면 해제 화면으로 (알림이 안 보이는 상황에서도 끌 수 있도록)
        if (RingState.current != null) {
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        else Notifs.cancel(this, Notifs.ID_FALLBACK)   // 비상 알림(반복 알람음)이 울리는 중이면 앱을 여는 것으로 끈다
        Ringer.restoreVolumeIfNeeded(this)
        Scheduler.reschedule(this, "앱 열기")
        refresh()
        handler.postDelayed(ticker, 30_000)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private val ticker = object : Runnable {
        override fun run() { refreshNext(); handler.postDelayed(this, 30_000) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun refresh() { refreshNext(); refreshHealth(); refreshDays() }

    // ---------------- 다음 알람 ----------------
    private fun refreshNext() {
        nextCard.removeAllViews()
        val at = Store.scheduledAt(this)
        val occAt = Store.scheduledOccAt(this)
        nextCard.add(text("다음 알람", 13f, C.MUTED, bold = true))
        if (at <= 0) {
            nextCard.add(text("예정된 알람 없음", 22f, C.TEXT, bold = true), top = 4)
            nextCard.add(text("아래에서 요일별 알람을 켜 주세요", 14f, C.MUTED), top = 2)
            return
        }
        val kind = runCatching { Kind.valueOf(Store.scheduledKind(this)) }.getOrDefault(Kind.MAIN)
        val label = when (kind) { Kind.PRE -> "미리 알람"; Kind.MAIN -> "본 알람"; Kind.SNOOZE -> "다시 알림"; Kind.TEST -> "테스트 알람" }
        nextCard.add(text("${Fmt.dateTime(occAt)}  ·  $label", 24f, C.TEXT, bold = true), top = 4)
        nextCard.add(text("${Fmt.duration(at - System.currentTimeMillis())} 후", 14f, C.ACCENT), top = 2)
    }

    // ---------------- 점검 ----------------
    private data class Issue(val text: String, val fixLabel: String?, val fix: (() -> Unit)?)

    private fun issues(): List<Issue> {
        val out = ArrayList<Issue>()
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AlarmManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            out += Issue("[중요] 알림 권한이 꺼져 있어요. 알람 소리는 나지만 잠금 화면에 알람 화면이 뜨지 않아요 (앱을 열면 해제할 수 있어요).", "허용") {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) || !Store.permissionAsked(this))
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                else openAppNotificationSettings()
            }
        } else if (!nm.areNotificationsEnabled()) {
            out += Issue("[중요] 알림이 꺼져 있어요. 잠금 화면에 알람 화면이 뜨지 않아요.", "설정 열기") { openAppNotificationSettings() }
        } else {
            Notifs.ensureChannels(this)
            val ch = nm.getNotificationChannel(Notifs.CH_RING_ID)
            if (ch != null && ch.importance < NotificationManager.IMPORTANCE_HIGH) {
                out += Issue("[중요] '알람 울림' 알림이 꺼져 있거나 낮게 설정돼 있어요. 잠금 화면에 알람 화면이 뜨지 않아요.", "설정 열기") {
                    startSafe(Intent(SysSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(SysSettings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(SysSettings.EXTRA_CHANNEL_ID, Notifs.CH_RING_ID))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            out += Issue("정확한 알람 권한이 없어요. 알람이 늦게 울릴 수 있어요.", "허용") {
                startSafe(Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            out += Issue("전체 화면 알림 권한이 없어요. 잠금 화면에 알람 화면이 뜨지 않아요 (소리는 울려요).", "허용") {
                startSafe(Intent(SysSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            out += Issue("배터리 최적화 대상이에요. 제외하면 더 안정적으로 울려요.", "제외하기") {
                @Suppress("BatteryLife")
                startSafe(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        // 실제로 시스템에 예약되어 있는지 확인
        val ours = Store.scheduledAt(this)
        if (ours > System.currentTimeMillis()) {
            val sys = am.nextAlarmClock
            val sysIsOurs = sys?.showIntent?.creatorPackage == packageName
            val missing = when {
                sys == null || sys.triggerTime > ours -> true          // 시스템 다음 알람이 없거나 우리 것보다 늦음
                sysIsOurs -> sys.triggerTime != ours                    // 우리 알람이 맨 앞: 시각까지 일치해야 함
                else -> !Scheduler.isArmed(this)                        // 다른 앱 알람이 먼저: 우리 예약이 있는지 직접 확인
            }
            if (missing) out += Issue("알람이 시스템에 예약되어 있지 않아요.", "다시 예약") {
                Scheduler.reschedule(this, "수동 재예약"); refresh()
            }
        }
        return out
    }

    private fun refreshHealth() {
        healthCard.removeAllViews()
        val list = issues()
        warnBanner.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        warnBanner.text = "⚠  확인이 필요한 항목 ${list.size}개  ·  눌러서 보기"
        if (list.isEmpty()) {
            healthCard.add(text("✓  알람이 정상적으로 예약되어 있어요", 15f, C.GREEN, bold = true))
        } else {
            healthCard.add(text("⚠  확인이 필요해요 (${list.size})", 15f, C.ACCENT, bold = true))
            for (i in list) {
                val row = horizontal(this)
                row.add(text(i.text, 13f, C.TEXT), w = 0, weight = 1f)
                if (i.fixLabel != null) row.add(button(i.fixLabel) { i.fix?.invoke() }.apply { textSize = 13f; minHeight = dp(40) },
                    w = LinearLayout.LayoutParams.WRAP_CONTENT, side = 4)
                healthCard.add(row, top = 10)
            }
        }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            healthCard.add(text("삼성 휴대폰: 설정 › 배터리 › 백그라운드 사용 제한에서 이 앱이 '절전 상태 앱'/'깊은 절전 상태 앱'에 없어야 해요. '절전 예외 앱'에 추가하는 것을 권장해요.",
                12f, C.MUTED), top = 10)
        }
    }

    private fun openAppNotificationSettings() =
        startSafe(Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, packageName))

    private fun startSafe(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { Toast.makeText(this, "설정 화면을 열 수 없어요", Toast.LENGTH_SHORT).show() }
        }
    }

    // ---------------- 요일 목록 ----------------
    private fun refreshDays() {
        dayList.removeAllViews()
        val week = Store.week(this)
        val s = Store.settings(this)
        week.forEachIndexed { i, d ->
            val row = horizontal(this).apply {
                background = pressable(C.PANEL, 16)
                setPadding(dp(16), dp(12), dp(10), dp(12))
                isClickable = true
                setOnClickListener { openSheet(setOf(i)) }
            }
            val left = vertical(this)
            left.add(text("${Fmt.DAYS[i]}요일", 14f, if (i >= 5) C.ACCENT else C.MUTED, bold = true))
            left.add(text(Fmt.hm(d.hour, d.minute), 30f, if (d.enabled) C.TEXT else C.MUTED, bold = d.enabled))
            val sub = if (!d.enabled) "꺼짐" else if (s.preEnabled) {
                val t = ((d.hour * 60 + d.minute - Planner.firstPreMinutes(s)) % 1440 + 1440) % 1440
                "${Fmt.hm(t / 60, t % 60)}부터 ${s.preIntervalMinutes}분마다 미리 알람"
            } else "미리 알람 없음"
            left.add(text(sub, 12f, C.MUTED))
            row.add(left, w = 0, weight = 1f)
            @Suppress("UseSwitchCompatOrMaterialCode")
            val sw = Switch(this).apply {
                isChecked = d.enabled
                scaleX = 1.2f; scaleY = 1.2f
                setOnCheckedChangeListener { _, on ->
                    val w = Store.week(this@MainActivity).toMutableList()
                    w[i] = w[i].copy(enabled = on)
                    Store.saveWeek(this@MainActivity, w)
                    Scheduler.reschedule(this@MainActivity, "요일 켜기/끄기")
                    refresh()
                }
            }
            row.add(sw, w = LinearLayout.LayoutParams.WRAP_CONTENT, side = 8)
            dayList.add(row, top = if (i == 0) 0 else 8)
        }
    }

    private fun openSheet(days: Set<Int>) {
        val week = Store.week(this)
        val first = week[days.first()]
        TimeSheet(this, first.hour, first.minute, days) { h, m, chosen ->
            val w = Store.week(this).toMutableList()
            for (d in chosen) w[d] = DayAlarm(true, h, m)
            Store.saveWeek(this, w)
            Scheduler.reschedule(this, "시간 변경")
            refresh()
            Toast.makeText(this, "${chosen.sorted().joinToString("·") { Fmt.DAYS[it] }}  ${Fmt.hm(h, m)} 저장", Toast.LENGTH_SHORT).show()
        }.show()
    }
}
