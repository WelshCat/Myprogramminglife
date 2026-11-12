package io.github.welshcat.tvremote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    /** 키 전송은 한 스레드에서 순서대로 (TV가 동시 요청을 싫어함). */
    private val keyExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var remote: SamsungRemote? = null
    @Volatile private var lastSendOk = true
    private var lastError: String? = null

    private val volumeKeysEnabled get() = prefs.getBoolean(PREF_VOLUME_KEYS, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("remote", Context.MODE_PRIVATE)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)

        bindKeyButtons(findViewById(R.id.root))
        setupToggle(R.id.toggleNumpad, R.id.numpad, "숫자 패드")
        setupToggle(R.id.toggleExtra, R.id.extra, "기타 키")
        findViewById<View>(R.id.settingsButton).setOnClickListener { showSettings() }

        val custom = findViewById<EditText>(R.id.customKey)
        val sendCustom = {
            val k = custom.text.toString().trim()
            if (k.isNotEmpty()) sendKey(SamsungProtocol.normalizeKey(k))
        }
        findViewById<View>(R.id.sendCustom).setOnClickListener { sendCustom() }
        custom.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendCustom(); true } else false
        }

        val ip = prefs.getString(PREF_TV_IP, null)
        if (ip.isNullOrBlank()) showSettings() else useTv(ip)
    }

    override fun onStop() {
        super.onStop()
        // 백그라운드에서는 연결을 끊어 TV/배터리 자원을 아낀다. 다음 버튼 누를 때 자동 재연결.
        val r = remote
        keyExecutor.execute { r?.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        keyExecutor.shutdown()
        ioExecutor.shutdown()
    }

    // ---------------- 버튼 연결 ----------------

    /** 레이아웃의 모든 View 중 tag가 "KEY_..." / "repeat:KEY_..." 인 것에 동작을 붙인다. */
    private fun bindKeyButtons(view: View) {
        val tag = view.tag as? String
        if (tag != null) {
            if (tag.startsWith("repeat:")) bindRepeat(view, tag.removePrefix("repeat:"))
            else if (tag.startsWith("KEY_")) view.setOnClickListener { v -> haptic(v); sendKey(tag) }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) bindKeyButtons(view.getChildAt(i))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindRepeat(view: View, key: String) {
        val repeater = object : Runnable {
            override fun run() {
                // 연결이 안 되는 상태에서 길게 누르면 요청이 쌓이므로 반복하지 않는다
                if (!lastSendOk) return
                sendKey(key)
                main.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        var handledByTouch = false
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    haptic(v)
                    sendKey(key)
                    main.postDelayed(repeater, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(repeater)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        handledByTouch = true
                        v.performClick()
                    }
                }
            }
            true
        }
        // 접근성(TalkBack) 더블탭 등 터치 없이 들어온 클릭은 한 번 전송
        view.setOnClickListener {
            if (!handledByTouch) sendKey(key)
            handledByTouch = false
        }
    }

    private fun setupToggle(buttonId: Int, panelId: Int, label: String) {
        val btn = findViewById<Button>(buttonId)
        val panel = findViewById<View>(panelId)
        btn.setOnClickListener {
            val show = panel.visibility != View.VISIBLE
            panel.visibility = if (show) View.VISIBLE else View.GONE
            btn.text = (if (show) "▾ " else "▸ ") + label
            if (show) panel.post { findViewById<View>(R.id.scroll).scrollBy(0, panel.height) }
        }
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ---------------- 키 전송 ----------------

    private fun sendKey(key: String) {
        val r = remote
        if (r == null) {
            showSettings()
            return
        }
        keyExecutor.execute {
            try {
                r.sendKey(key)
                lastSendOk = true
                main.post { setStatus(true, "TV ${r.host} · 연결됨") }
            } catch (e: SamsungException) {
                lastSendOk = false
                main.post { onSendError(e.message ?: "오류") }
            }
        }
    }

    private fun onSendError(msg: String) {
        setStatus(false, "연결 안 됨 · 설정에서 IP 확인")
        // 버튼을 연타해도 같은 오류 토스트가 쌓이지 않도록
        if (msg != lastError) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        lastError = msg
        main.postDelayed({ lastError = null }, 4000)
    }

    private fun setStatus(ok: Boolean, text: String) {
        statusDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (ok) R.color.green else R.color.red))
        statusText.text = text
    }

    private fun useTv(ip: String) {
        val old = remote
        keyExecutor.execute { old?.close() }
        lastSendOk = true
        remote = SamsungRemote(ip, remoteName = "${android.os.Build.MODEL} 리모컨")
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.muted))
        statusText.text = "TV $ip"
    }

    // 휴대폰 볼륨 버튼으로 TV 음량 조절
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysEnabled && remote != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { sendKey("KEY_VOLUP"); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { sendKey("KEY_VOLDOWN"); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysEnabled && remote != null &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    // ---------------- 설정 / TV 찾기 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showSettings() {
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        val hint = TextView(this).apply {
            text = "TV IP 주소 (TV 메뉴 > 네트워크 > 네트워크 상태에서 확인)"
            textSize = 13f
        }
        val ipInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE  // 숫자와 점 입력이 쉬운 키패드
            setText(prefs.getString(PREF_TV_IP, ""))
            this.hint = "예: 192.168.0.20"
            setSelection(text.length)
        }
        val volKeys = CheckBox(this).apply {
            text = "휴대폰 볼륨 버튼으로 TV 음량 조절"
            isChecked = volumeKeysEnabled
        }
        box.addView(hint); box.addView(ipInput); box.addView(volKeys)

        val dialog = AlertDialog.Builder(this)
            .setTitle("TV 설정")
            .setView(box)
            .setPositiveButton("저장", null)
            .setNeutralButton("자동 검색", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ip = ipInput.text.toString().trim()
                if (!IP_REGEX.matches(ip)) {
                    ipInput.error = "올바른 IP 주소를 입력하세요"
                    return@setOnClickListener
                }
                prefs.edit().putString(PREF_TV_IP, ip)
                    .putBoolean(PREF_VOLUME_KEYS, volKeys.isChecked).apply()
                useTv(ip)
                dialog.dismiss()
                Toast.makeText(this, "버튼을 누르면 TV 화면에 허용 팝업이 뜹니다. '허용'을 선택하세요.",
                    Toast.LENGTH_LONG).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                discoverTvs { ip -> ipInput.setText(ip); ipInput.setSelection(ip.length) }
            }
        }
        dialog.show()
    }

    private fun discoverTvs(onPick: (String) -> Unit) {
        val progress = Toast.makeText(this, "TV를 찾는 중… (TV가 켜져 있어야 합니다)", Toast.LENGTH_SHORT)
        progress.show()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tv-discovery").apply { setReferenceCounted(false) }
        ioExecutor.execute {
            val result = try {
                lock.acquire()
                TvDiscovery.discover(3000)
            } catch (e: Exception) {
                emptyList()
            } finally {
                if (lock.isHeld) lock.release()
            }
            main.post {
                if (isFinishing) return@post
                if (result.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("TV를 찾지 못했습니다")
                        .setMessage("• TV가 켜져 있고 휴대폰과 같은 Wi-Fi에 연결되어 있는지 확인하세요.\n" +
                            "• TV 메뉴 > 네트워크 > 네트워크 상태에서 IP를 확인해 직접 입력할 수 있습니다.")
                        .setPositiveButton("확인", null)
                        .show()
                } else if (result.size == 1) {
                    onPick(result[0].ip)
                    Toast.makeText(this, "찾음: ${result[0].ip}", Toast.LENGTH_SHORT).show()
                } else {
                    val labels = result.map { "${it.ip}\n${it.server}" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("TV 선택")
                        .setItems(labels) { _, i -> onPick(result[i].ip) }
                        .show()
                }
            }
        }
    }

    companion object {
        private const val PREF_TV_IP = "tv_ip"
        private const val PREF_VOLUME_KEYS = "volume_keys"
        private const val REPEAT_DELAY_MS = 450L
        private const val REPEAT_INTERVAL_MS = 180L
        private val IP_REGEX = Regex("""^((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)$""")
    }
}
