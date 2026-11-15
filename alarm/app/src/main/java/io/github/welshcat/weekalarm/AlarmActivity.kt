package io.github.welshcat.weekalarm

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.time.LocalDateTime
import kotlin.math.hypot

/** 잠금 화면 위에 뜨는 알람 화면. 가운데 원을 바깥으로 밀어서 해제한다 (갤럭시 기본 알람 방식). */
class AlarmActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: android.widget.TextView
    private val onRingChanged = { if (RingState.current == null) finish() else bind() }
    private lateinit var root: LinearLayout
    private var boundOcc: Occurrence? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.decorView.setBackgroundColor(C.BG)

        root = vertical(this, 24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        setContentView(root)
        root.padForSystemBars()
        RingState.listen(onRingChanged)
        if (RingState.current == null) { finish(); return }
        bind()
    }

    private fun bind() {
        val occ = RingState.current ?: return
        if (occ == boundOcc) return
        boundOcc = occ
        val s = Store.settings(this)
        val st = Store.state(this)
        root.removeAllViews()

        val label = when (occ.kind) {
            Kind.PRE -> "미리 알람"
            Kind.MAIN -> "알람"
            Kind.SNOOZE -> "다시 알림"
            Kind.TEST -> "테스트 알람"
        }
        root.add(text(label, 18f, if (occ.kind == Kind.PRE) C.BLUE else C.ACCENT, bold = true).apply { gravity = Gravity.CENTER },
            top = 40)
        clock = text("", 72f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        root.add(clock, top = 8)
        val sub = when (occ.kind) {
            Kind.PRE -> "본 알람 ${Fmt.time(occ.mainAt)}까지 ${occ.minutesBefore}분 남음"
            Kind.MAIN, Kind.SNOOZE -> "${Fmt.time(occ.mainAt)} 알람"
            Kind.TEST -> "알람이 정상적으로 울립니다"
        }
        root.add(text(sub, 17f, C.MUTED).apply { gravity = Gravity.CENTER }, top = 4)

        val swipe = SwipeDismissView(this) {
            RingService.action(this, RingService.ACTION_DISMISS)
            finish()
        }
        root.add(FrameLayout(this).apply { addView(swipe, FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER)) },
            h = 0, weight = 1f)
        root.add(text("원을 바깥쪽으로 밀어서 해제", 14f, C.MUTED).apply { gravity = Gravity.CENTER })

        if (Planner.canSnooze(occ, s, st)) {
            root.add(button("다시 알림  ${s.snoozeMinutes}분", C.BTN, C.ACCENT) {
                RingService.action(this, RingService.ACTION_SNOOZE); finish()
            }, top = 20, side = 24)
        }
        root.add(View(this), h = dp(24))
        handler.removeCallbacks(tick)   // 새 알람으로 교체될 때 시계 갱신이 중복되지 않도록
        tick.run()
    }

    private val tick = object : Runnable {
        override fun run() {
            val n = LocalDateTime.now()
            clock.text = Fmt.hm(n.hour, n.minute)
            handler.postDelayed(this, 1000)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* 실수로 닫히지 않도록 무시 — 해제는 원을 밀어서 */ }

    override fun onDestroy() {
        RingState.unlisten(onRingChanged)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

/** 가운데 ✕ 원을 잡고 바깥 원까지 끌면 해제. */
class SwipeDismissView(ctx: Context, private val onDismiss: () -> Unit) : View(ctx) {
    private val baseR = ctx.dp(46).toFloat()
    private val paintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT }
    private val paintX = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.BG; strokeWidth = ctx.dp(4).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val paintDrag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT; alpha = 70 }
    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); color = C.MUTED; alpha = 120
    }
    private var dragR = 0f
    private var dragging = false
    private var done = false
    private var idlePulse = 0f
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { idlePulse = it.animatedValue as Float; if (!dragging) invalidate() }
    }

    init {
        contentDescription = "알람 해제. 두 번 탭하면 해제됩니다"
        isClickable = true
    }

    private val threshold get() = (minOf(width, height) / 2f) - context.dp(8)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); pulse.start() }
    override fun onDetachedFromWindow() { pulse.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        c.drawCircle(cx, cy, threshold, paintRing)
        val r = if (dragging) dragR else baseR + idlePulse * context.dp(18)
        paintDrag.alpha = if (dragging) 90 else (70 * (1 - idlePulse)).toInt()
        c.drawCircle(cx, cy, r, paintDrag)
        c.drawCircle(cx, cy, baseR, paintInner)
        val k = baseR * 0.32f
        c.drawLine(cx - k, cy - k, cx + k, cy + k, paintX)
        c.drawLine(cx - k, cy + k, cx + k, cy - k, paintX)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (done) return true
        val cx = width / 2f; val cy = height / 2f
        val dist = hypot(e.x - cx, e.y - cy)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dist > baseR * 1.4f) return false
                dragging = true; dragR = baseR
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                dragR = maxOf(baseR, dist)
                if (dist >= threshold) {
                    done = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onDismiss()
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && !done) {
                    ValueAnimator.ofFloat(dragR, baseR).apply {
                        duration = 200
                        addUpdateListener { dragR = it.animatedValue as Float; invalidate() }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) { dragging = false; invalidate() }
                        })
                    }.start()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {          // 접근성(TalkBack) 더블탭
        super.performClick()
        if (!done) { done = true; onDismiss() }
        return true
    }
}
