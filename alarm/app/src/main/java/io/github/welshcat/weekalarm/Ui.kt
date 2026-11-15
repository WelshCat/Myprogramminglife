package io.github.welshcat.weekalarm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** 코드로 화면을 만드는 작은 도우미 (XML 없이). */
object C {
    val BG = Color.parseColor("#111317")
    val PANEL = Color.parseColor("#1B1E24")
    val BTN = Color.parseColor("#262A32")
    val PRESS = Color.parseColor("#3A404C")
    val TEXT = Color.parseColor("#E9ECF1")
    val MUTED = Color.parseColor("#8A919E")
    val ACCENT = Color.parseColor("#FF8A3D")
    val BLUE = Color.parseColor("#4C8DFF")
    val RED = Color.parseColor("#E5484D")
    val GREEN = Color.parseColor("#30A46C")
}

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Number, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1.5), stroke)
    }

fun Context.pressable(color: Int, radiusDp: Number, stroke: Int? = null) =
    RippleDrawable(ColorStateList.valueOf(C.PRESS), rounded(color, radiusDp, stroke), null)

fun Context.text(s: CharSequence, sizeSp: Float = 15f, color: Int = C.TEXT, bold: Boolean = false) =
    TextView(this).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.button(label: String, color: Int = C.BTN, textColor: Int = C.TEXT, onClick: () -> Unit) =
    TextView(this).apply {
        text = label; textSize = 15f; setTextColor(textColor); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(50)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = pressable(color, 14)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun vertical(ctx: Context, padDp: Int = 0) = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp))
}

fun horizontal(ctx: Context) = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.add(v: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                     weight: Float = 0f, top: Int = 0, side: Int = 0): View {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.topMargin = context.dp(top); p.leftMargin = context.dp(side); p.rightMargin = context.dp(side)
    addView(v, p); return v
}

/** Android 15부터 앱이 상태 표시줄/내비게이션 바 아래까지 그려지므로, 그만큼 안쪽 여백을 더한다. */
fun View.padForSystemBars() {
    val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(l + insets.systemWindowInsetLeft, t + insets.systemWindowInsetTop,
                r + insets.systemWindowInsetRight, b + insets.systemWindowInsetBottom)
        }
        insets
    }
    requestApplyInsets()
}
