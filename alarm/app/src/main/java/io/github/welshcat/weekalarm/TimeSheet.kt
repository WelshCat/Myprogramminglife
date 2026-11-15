package io.github.welshcat.weekalarm

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView

/**
 * 타이핑 없는 시간 설정 창.
 *  - 시(00~23) / 분(00~59) 휠을 돌려서 선택 (끝에서 처음으로 이어짐)
 *  - −1시간 −10분 +10분 +1시간 버튼으로 미세 조정
 *  - 적용할 요일을 여러 개 고르면 한 번에 저장 (평일/주말/매일 바로가기)
 */
class TimeSheet(
    private val ctx: Context,
    private val hour: Int, private val minute: Int,
    initialDays: Set<Int>,
    private val onSave: (hour: Int, minute: Int, days: Set<Int>) -> Unit,
) {
    private val days = initialDays.toMutableSet()
    private val dayViews = ArrayList<TextView>()
    private lateinit var hourPicker: NumberPicker
    private lateinit var minutePicker: NumberPicker
    private lateinit var preview: TextView
    private var scrolling = 0

    fun show() {
        val root = vertical(ctx, 20)

        preview = ctx.text("", 14f, C.MUTED).apply { gravity = Gravity.CENTER }

        // 휠
        val wheels = horizontal(ctx).apply { gravity = Gravity.CENTER }
        hourPicker = picker(23, (0..23).map { "%02d".format(it) })
        minutePicker = picker(59, (0..59).map { "%02d".format(it) })
        wheels.add(hourPicker, w = ctx.dp(96))
        wheels.add(ctx.text(":", 34f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER }, w = ViewGroup.LayoutParams.WRAP_CONTENT, side = 10)
        wheels.add(minutePicker, w = ctx.dp(96))
        hourPicker.value = hour; minutePicker.value = minute
        hourPicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        minutePicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        root.add(wheels)
        root.add(preview, top = 4)

        // 빠른 조정
        val quick = horizontal(ctx)
        listOf("−1시간" to -60, "−10분" to -10, "+10분" to 10, "+1시간" to 60).forEach { (label, delta) ->
            quick.add(small(label) { shift(delta) }, w = 0, weight = 1f, side = 3)
        }
        root.add(quick, top = 12)

        // 요일
        root.add(ctx.text("적용할 요일", 13f, C.MUTED), top = 18)
        val dayRow = horizontal(ctx)
        for (i in 0..6) {
            val tv = TextView(ctx).apply {
                text = Fmt.DAYS[i]; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                minHeight = ctx.dp(42)
                setOnClickListener { if (!days.remove(i)) days += i; refreshDays() }
            }
            dayViews += tv
            dayRow.add(tv, w = 0, weight = 1f, side = 2)
        }
        root.add(dayRow, top = 6)
        val presets = horizontal(ctx)
        presets.add(small("평일") { setDays(setOf(0, 1, 2, 3, 4)) }, w = 0, weight = 1f, side = 3)
        presets.add(small("주말") { setDays(setOf(5, 6)) }, w = 0, weight = 1f, side = 3)
        presets.add(small("매일") { setDays((0..6).toSet()) }, w = 0, weight = 1f, side = 3)
        root.add(presets, top = 8)
        refreshDays()
        updatePreview()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("알람 시간")
            .setView(root)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (days.isEmpty()) { preview.text = "적용할 요일을 하나 이상 고르세요"; preview.setTextColor(C.RED); return@setOnClickListener }
                // 휠이 아직 돌고 있으면 멈출 때까지 기다렸다가 최종 값으로 저장
                fun save() {
                    if (scrolling > 0) { hourPicker.postDelayed({ save() }, 100); return }
                    onSave(hourPicker.value, minutePicker.value, days.toSet())
                    dialog.dismiss()
                }
                save()
            }
        }
        dialog.show()
    }

    private fun picker(max: Int, labels: List<String>) = NumberPicker(ctx).apply {
        minValue = 0; maxValue = max
        displayedValues = labels.toTypedArray()
        wrapSelectorWheel = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS   // 키보드 입력 막기 (돌려서만 선택)
        if (Build.VERSION.SDK_INT >= 29) { textSize = ctx.dp(30).toFloat(); textColor = C.TEXT }
        if (Build.VERSION.SDK_INT >= 29) selectionDividerHeight = ctx.dp(2)
        var mine = false
        setOnScrollListener { _, state ->
            val now = state != NumberPicker.OnScrollListener.SCROLL_STATE_IDLE
            if (now != mine) { scrolling += if (now) 1 else -1; mine = now }
        }
    }

    private fun small(label: String, onClick: () -> Unit) = ctx.button(label, C.BTN, C.TEXT, onClick).apply {
        textSize = 13f; minHeight = ctx.dp(40); setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), ctx.dp(6))
    }

    private fun shift(deltaMin: Int) {
        val total = ((hourPicker.value * 60 + minutePicker.value + deltaMin) % 1440 + 1440) % 1440
        hourPicker.value = total / 60; minutePicker.value = total % 60
        updatePreview()
    }

    private fun setDays(d: Set<Int>) { days.clear(); days += d; refreshDays() }

    private fun refreshDays() {
        dayViews.forEachIndexed { i, tv ->
            val on = i in days
            tv.background = ctx.pressable(if (on) C.ACCENT else C.BTN, 21)
            tv.setTextColor(if (on) C.BG else if (i >= 5) C.MUTED else C.TEXT)
        }
    }

    private fun updatePreview() {
        val s = Store.settings(ctx)
        preview.setTextColor(C.MUTED)
        val t = hourPicker.value * 60 + minutePicker.value
        preview.text = if (s.preEnabled) {
            val st = ((t - Planner.firstPreMinutes(s)) % 1440 + 1440) % 1440
            "미리 알람: ${Fmt.hm(st / 60, st % 60)}부터 ${s.preIntervalMinutes}분마다"
        } else "미리 알람 꺼짐"
    }
}
