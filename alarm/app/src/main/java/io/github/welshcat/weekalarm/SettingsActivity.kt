package io.github.welshcat.weekalarm

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var list: LinearLayout
    private var preview: Ringer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var s: Settings
        get() = Store.settings(this)
        set(v) { Store.saveSettings(this, v); Scheduler.reschedule(this, "설정 변경"); build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(C.BG)
        list = vertical(this, 16)
        setContentView(ScrollView(this).apply { addView(list) })
        build()
    }

    override fun onPause() { super.onPause(); stopPreview() }

    private fun build() {
        val cur = s
        list.removeAllViews()
        list.add(text("설정", 26f, C.TEXT, bold = true), top = 8)

        section("미리 알람")
        switchRow("미리 알람 사용", "본 알람 전부터 일정 간격으로 울림", cur.preEnabled) { s = s.copy(preEnabled = it) }
        if (cur.preEnabled) {
            choiceRow("시작 시점", listOf(30, 60, 90, 120), cur.preLeadMinutes, { "${Fmt.duration(it * 60_000L)} 전부터" }) {
                s = s.copy(preLeadMinutes = it, preIntervalMinutes = minOf(s.preIntervalMinutes, it))
            }
            choiceRow("간격", listOf(5, 10, 15, 20, 30).filter { it <= cur.preLeadMinutes }, cur.preIntervalMinutes, { "${it}분마다" }) {
                s = s.copy(preIntervalMinutes = it)
            }
            switchRow("'남은 미리 알람 건너뛰기' 버튼", "미리 알람이 울릴 때 나머지 미리 알람을 건너뛰는 버튼 표시 (본 알람은 항상 울림)",
                cur.skipPreButton) { s = s.copy(skipPreButton = it) }
        }

        section("다시 알림 (본 알람)")
        switchRow("다시 알림 사용", "본 알람에서 '다시 알림' 버튼 표시. 끄지 않고 울림이 끝나도 자동으로 다시 울림", cur.snoozeEnabled) {
            s = s.copy(snoozeEnabled = it)
        }
        if (cur.snoozeEnabled) {
            choiceRow("간격", listOf(5, 10, 15), cur.snoozeMinutes, { "${it}분" }) { s = s.copy(snoozeMinutes = it) }
            choiceRow("최대 횟수", listOf(1, 2, 3, 5, 10), cur.snoozeMaxCount, { "${it}번" }) { s = s.copy(snoozeMaxCount = it) }
        }

        section("소리")
        actionRow("알람음", cur.soundName ?: "기본 알람음") { pickSystemSound() }
        actionRow("내 음악 파일로 설정", "휴대폰에 저장된 음악 파일 선택") { pickFile() }
        if (cur.soundUri != null) actionRow("기본 알람음으로 되돌리기", "") { s = s.copy(soundUri = null, soundName = null) }
        volumeRow(cur.volumePercent)
        switchRow("점점 크게", "작은 소리로 시작해서 약 30초에 걸쳐 커짐", cur.gradualVolume) { s = s.copy(gradualVolume = it) }
        switchRow("진동", "", cur.vibrate) { s = s.copy(vibrate = it) }
        choiceRow("울림 시간", listOf(1, 3, 5, 10), cur.ringMinutes, { "${it}분 후 자동으로 멈춤" }) { s = s.copy(ringMinutes = it) }
        actionRow("미리 듣기 (5초)", "선택한 소리와 음량으로 재생") { playPreview() }

        section("안전장치")
        switchRow("놓친 알람 늦게라도 울리기", "휴대폰이 꺼져 있었거나 시간이 바뀌어 놓친 본 알람을 60분 이내면 바로 울림",
            cur.lateRingEnabled) { s = s.copy(lateRingEnabled = it) }

        section("새로운 한 주 알림")
        switchRow("주간 확인 알림", "한 주가 시작될 때 이번 주 알람을 확인하라는 알림", cur.weeklyReminder) { s = s.copy(weeklyReminder = it) }
        if (cur.weeklyReminder) {
            choiceRow("알림 요일", (1..7).toList(), cur.weeklyDay, { "${Fmt.DAYS[it - 1]}요일" }) { s = s.copy(weeklyDay = it) }
            choiceRow("알림 시각", (0..23).toList(), cur.weeklyHour, { Fmt.hm(it, 0) }) { s = s.copy(weeklyHour = it, weeklyMinute = 0) }
        }

        section("진단")
        actionRow("최근 기록 보기", "알람이 언제 예약되고 울렸는지 기록") {
            AlertDialog.Builder(this).setTitle("최근 기록")
                .setMessage(Store.logText(this).lines().reversed().joinToString("\n").ifBlank { "기록 없음" })
                .setPositiveButton("닫기", null).show()
        }
        actionRow("알람 다시 예약", "예약 상태를 새로 계산해서 다시 등록") {
            Scheduler.reschedule(this, "수동 재예약")
            Toast.makeText(this, "다시 예약했어요", Toast.LENGTH_SHORT).show()
        }
        list.add(text("주간 알람 1.0", 12f, C.MUTED), top = 24)
    }

    // ---------------- 행 만들기 ----------------
    private fun section(title: String) { list.add(text(title, 14f, C.ACCENT, bold = true), top = 24) }

    private fun card(): LinearLayout = horizontal(this).apply {
        background = pressable(C.PANEL, 14); setPadding(dp(16), dp(14), dp(12), dp(14)); isClickable = true
    }

    private fun labels(title: String, sub: String): LinearLayout = vertical(this).apply {
        add(text(title, 16f, C.TEXT))
        if (sub.isNotEmpty()) add(text(sub, 12f, C.MUTED), top = 2)
    }

    private fun switchRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val row = card()
        row.add(labels(title, sub), w = 0, weight = 1f)
        @Suppress("UseSwitchCompatOrMaterialCode")
        val sw = Switch(this).apply { isChecked = value; isClickable = false }
        row.add(sw, w = LinearLayout.LayoutParams.WRAP_CONTENT, side = 6)
        row.setOnClickListener { onChange(!value) }
        list.add(row, top = 8)
    }

    private fun <T> choiceRow(title: String, options: List<T>, value: T, label: (T) -> String, onPick: (T) -> Unit) {
        val row = card()
        row.add(labels(title, label(value)), w = 0, weight = 1f)
        row.add(text("›", 22f, C.MUTED), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        row.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.map(label).toTypedArray(), options.indexOf(value)) { d, i -> d.dismiss(); onPick(options[i]) }
                .setNegativeButton("취소", null).show()
        }
        list.add(row, top = 8)
    }

    private fun actionRow(title: String, sub: String, onClick: () -> Unit) {
        val row = card()
        row.add(labels(title, sub), w = 0, weight = 1f)
        row.add(text("›", 22f, C.MUTED), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        row.setOnClickListener { onClick() }
        list.add(row, top = 8)
    }

    private fun volumeRow(percent: Int) {
        val box = vertical(this).apply { background = rounded(C.PANEL, 14); setPadding(dp(16), dp(14), dp(16), dp(10)) }
        val label: TextView = text("알람 음량  $percent%", 16f, C.TEXT)
        box.add(label)
        box.add(text("울릴 때만 이 음량으로 맞추고, 끝나면 원래대로 돌려요 (휴대폰 알람 음량이 0이어도 울림)", 12f, C.MUTED), top = 2)
        val bar = SeekBar(this).apply {
            max = 9; progress = (percent / 10 - 1).coerceIn(0, 9)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { label.text = "알람 음량  ${(p + 1) * 10}%" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { s = s.copy(volumePercent = (sb.progress + 1) * 10) }
            })
        }
        box.add(bar, top = 8)
        list.add(box, top = 8)
    }

    // ---------------- 소리 선택 ----------------
    private fun pickSystemSound() {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "알람음")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.soundUri?.let { Uri.parse(it) })
        try { @Suppress("DEPRECATION") startActivityForResult(i, RC_RINGTONE) }
        catch (e: Exception) { Toast.makeText(this, "알람음 목록을 열 수 없어요", Toast.LENGTH_SHORT).show() }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        try { @Suppress("DEPRECATION") startActivityForResult(i, RC_FILE) }
        catch (e: Exception) { Toast.makeText(this, "파일 선택 화면을 열 수 없어요", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            RC_RINGTONE -> {
                @Suppress("DEPRECATION")
                val uri: Uri? = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (uri == null) { s = s.copy(soundUri = null, soundName = null); return }
                val name = runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull()
                s = s.copy(soundUri = uri.toString(), soundName = name ?: "선택한 알람음")
            }
            RC_FILE -> {
                val uri = data.data ?: return
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                val name = runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull()
                s = s.copy(soundUri = uri.toString(), soundName = name ?: "내 음악 파일")
                Toast.makeText(this, "파일을 지우거나 옮기면 기본 알람음으로 대신 울려요", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playPreview() {
        stopPreview()
        if (RingState.current != null) return
        preview = Ringer(this).also { it.start(s.copy(gradualVolume = false, vibrate = false), previewOnly = true) }
        handler.postDelayed({ stopPreview() }, 5000)
    }

    private fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        preview?.stop(); preview = null
    }

    companion object {
        private const val RC_RINGTONE = 1
        private const val RC_FILE = 2
    }
}
