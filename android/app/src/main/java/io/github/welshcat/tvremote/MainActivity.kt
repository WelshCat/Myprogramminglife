package io.github.welshcat.tvremote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 버튼 동작. 레이아웃의 android:tag 이름과 같다. */
enum class Action {
    POWER, WAKE, INPUT, SETTINGS, STOP_APP,
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME,
    VOL_UP, VOL_DOWN, MUTE,
    REWIND, PLAY_PAUSE, STOP, FORWARD,
}

/** 기기 종류별 동작 구현. perform()은 네트워크를 쓰므로 백그라운드 스레드에서 호출한다. */
private interface Controller {
    fun supports(a: Action): Boolean
    fun perform(a: Action)
    fun refresh() {}
    fun close()
    val hint: String
}

private class GoogleTvController(val remote: GoogleTvRemote) : Controller {
    private val keys = mapOf(
        Action.POWER to AtvKey.POWER, Action.WAKE to AtvKey.WAKEUP, Action.INPUT to AtvKey.TV_INPUT,
        Action.SETTINGS to AtvKey.SETTINGS,
        Action.UP to AtvKey.DPAD_UP, Action.DOWN to AtvKey.DPAD_DOWN, Action.LEFT to AtvKey.DPAD_LEFT,
        Action.RIGHT to AtvKey.DPAD_RIGHT, Action.OK to AtvKey.DPAD_CENTER,
        Action.BACK to AtvKey.BACK, Action.HOME to AtvKey.HOME,
        Action.VOL_UP to AtvKey.VOLUME_UP, Action.VOL_DOWN to AtvKey.VOLUME_DOWN, Action.MUTE to AtvKey.VOLUME_MUTE,
        Action.REWIND to AtvKey.MEDIA_REWIND, Action.PLAY_PAUSE to AtvKey.MEDIA_PLAY_PAUSE,
        Action.STOP to AtvKey.MEDIA_STOP, Action.FORWARD to AtvKey.MEDIA_FAST_FORWARD,
    )
    override fun supports(a: Action) = a in keys
    override fun perform(a: Action) = remote.sendKey(keys.getValue(a))
    override fun refresh() = remote.connect()
    override fun close() = remote.close()
    override val hint = "• 전원·음량은 크롬캐스트가 HDMI-CEC로 TV에 전달합니다. TV 메뉴 > 시스템 > Anynet+(HDMI-CEC)를 켜 두세요.\n" +
        "• 음량이 안 바뀌면 크롬캐스트 설정 > 리모컨 및 액세서리 > TV 및 오디오 기기 제어에서 음량 제어를 'HDMI-CEC'로 설정하세요.\n" +
        "• TV 자체 메뉴와 방송 채널은 조작할 수 없습니다."
}

private class CastController(val remote: CastRemote) : Controller {
    private val supported = setOf(Action.WAKE, Action.STOP_APP, Action.VOL_UP, Action.VOL_DOWN, Action.MUTE,
        Action.REWIND, Action.PLAY_PAUSE, Action.STOP, Action.FORWARD)
    override fun supports(a: Action) = a in supported
    override fun perform(a: Action) {
        when (a) {
            Action.WAKE -> remote.wakeTv()
            Action.STOP_APP -> remote.stopApp()
            Action.VOL_UP -> remote.volumeStep(+0.05)
            Action.VOL_DOWN -> remote.volumeStep(-0.05)
            Action.MUTE -> remote.toggleMute()
            Action.PLAY_PAUSE -> remote.playPause()
            Action.STOP -> remote.stopMedia()
            Action.REWIND -> remote.seekBy(-10.0)
            Action.FORWARD -> remote.seekBy(+10.0)
            else -> {}
        }
    }
    override fun refresh() = remote.refresh()
    override fun close() = remote.close()
    override val hint = "• 'TV 켜기'를 누르면 크롬캐스트가 HDMI-CEC로 TV를 켜고 입력을 크롬캐스트로 바꿉니다. TV 메뉴 > 시스템 > Anynet+(HDMI-CEC)를 켜 두세요.\n" +
        "• 음량은 크롬캐스트(캐스트) 음량입니다. TV 본체 음량과는 별개예요.\n" +
        "• ◀◀ ▶▶ 는 10초씩 이동합니다. 유튜브·넷플릭스 등을 휴대폰에서 캐스트한 뒤 사용하세요.\n" +
        "• 리모컨 없는 크롬캐스트는 TV를 끌 수 없습니다."
}

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var volumeText: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var hintText: TextView
    private val actionViews = HashMap<View, Action>()

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val probeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var discovery: DeviceDiscovery

    @Volatile private var device: Device? = null
    @Volatile private var controller: Controller? = null
    @Volatile private var lastOk = true
    private var lastError: String? = null
    private var pairingDialogShown = false

    private val volumeKeysEnabled get() = prefs.getBoolean(PREF_VOLUME_KEYS, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("remote", Context.MODE_PRIVATE)
        discovery = DeviceDiscovery(this)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        subText = findViewById(R.id.subText)
        volumeText = findViewById(R.id.volumeText)
        nowPlaying = findViewById(R.id.nowPlaying)
        hintText = findViewById(R.id.hint)

        bindButtons(findViewById(R.id.root))
        findViewById<View>(R.id.settingsButton).setOnClickListener { showDevicePicker() }

        val host = prefs.getString(PREF_HOST, null)
        val type = prefs.getString(PREF_TYPE, null)
        if (host != null && type != null) {
            useDevice(Device(host, prefs.getString(PREF_NAME, host) ?: host, DeviceType.valueOf(type)), save = false)
        } else {
            applyMode()
            showDevicePicker()
        }
    }

    override fun onStart() {
        super.onStart()
        val c = controller ?: return
        worker.execute { try { c.refresh(); onOk() } catch (e: Exception) { onError(e) } }
    }

    override fun onStop() {
        super.onStop()
        val c = controller
        worker.execute { c?.close() }   // 백그라운드에서는 연결을 끊는다
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.stop()
        worker.shutdown(); probeExecutor.shutdown()
    }

    // ---------------- 버튼 ----------------

    private fun bindButtons(view: View) {
        (view.tag as? String)?.let { tag ->
            val repeat = tag.startsWith("repeat:")
            val action = runCatching { Action.valueOf(tag.removePrefix("repeat:")) }.getOrNull()
            if (action != null) {
                actionViews[view] = action
                if (repeat) bindRepeat(view, action)
                else view.setOnClickListener { v -> haptic(v); perform(action) }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) bindButtons(view.getChildAt(i))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindRepeat(view: View, action: Action) {
        val repeater = object : Runnable {
            override fun run() {
                if (!lastOk) return
                perform(action)
                main.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        var handledByTouch = false
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true; haptic(v); perform(action)
                    main.postDelayed(repeater, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(repeater)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) { handledByTouch = true; v.performClick() }
                }
            }
            true
        }
        view.setOnClickListener {            // TalkBack 더블탭 등
            if (!handledByTouch) perform(action)
            handledByTouch = false
        }
    }

    private fun haptic(v: View) = v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    /** 연결된 기기 종류에 맞춰 버튼을 보이거나 숨긴다. */
    private fun applyMode() {
        val c = controller
        for ((v, a) in actionViews) v.visibility = if (c == null || c.supports(a)) View.VISIBLE else View.GONE
        // 모든 버튼이 숨겨진 줄/방향키 패널은 같이 숨긴다
        for (v in actionViews.keys) {
            val parent = v.parent as? ViewGroup ?: continue
            val anyVisible = (0 until parent.childCount).any { parent.getChildAt(it).visibility == View.VISIBLE }
            parent.visibility = if (anyVisible) View.VISIBLE else View.GONE
        }
        val dpad = findViewById<View>(R.id.dpad)
        dpad.visibility = if (c == null || c.supports(Action.OK)) View.VISIBLE else View.GONE
        hintText.text = c?.hint ?: ""
        if (c !is CastController) nowPlaying.visibility = View.GONE
    }

    // ---------------- 동작 실행 ----------------

    private fun perform(action: Action) {
        val c = controller ?: run { showDevicePicker(); return }
        worker.execute {
            try {
                c.perform(action)
                onOk()
            } catch (e: NeedsPairingException) {
                main.post { startPairing() }
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private fun onOk() {
        lastOk = true
        val d = device ?: return
        main.post { setStatus(true, d.name, "${d.typeLabel} · 연결됨") }
    }

    private fun onError(e: Exception) {
        lastOk = false
        val msg = e.message ?: "오류가 발생했습니다"
        main.post {
            device?.let { setStatus(false, it.name, "연결 안 됨 · ${it.host}") }
            if (msg != lastError) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            lastError = msg
            main.postDelayed({ lastError = null }, 4000)
        }
    }

    private fun setStatus(ok: Boolean?, title: String, sub: String) {
        val color = when (ok) { true -> R.color.green; false -> R.color.red; null -> R.color.muted }
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(color))
        statusText.text = title
        subText.text = sub
    }

    // ---------------- 기기 연결 ----------------

    private fun identity(): ClientIdentity {
        val keyFile = File(filesDir, "client.key")
        val certFile = File(filesDir, "client.crt")
        if (keyFile.exists() && certFile.exists()) {
            try { return ClientIdentity.load(keyFile.readBytes(), certFile.readBytes()) } catch (_: Exception) {}
        }
        val id = ClientIdentity.generate()
        keyFile.writeBytes(id.keyBytes()); certFile.writeBytes(id.certBytes())
        return id
    }

    private fun useDevice(d: Device, save: Boolean = true) {
        if (save) prefs.edit().putString(PREF_HOST, d.host).putString(PREF_NAME, d.name)
            .putString(PREF_TYPE, d.type.name).apply()
        val old = controller
        device = d
        lastOk = true
        controller = null
        volumeText.text = "–"
        nowPlaying.visibility = View.GONE
        setStatus(null, d.name, "${d.typeLabel} · 연결 중…")
        worker.execute {
            old?.close()
            val c: Controller = when (d.type) {
                DeviceType.GOOGLE_TV -> GoogleTvController(
                    GoogleTvRemote(d.host, identity(), "${Build.MODEL} (TV 리모컨)", gtvListener))
                DeviceType.CAST -> CastController(CastRemote(d.host, castListener))
            }
            controller = c
            main.post { applyMode() }
            try {
                c.refresh()
                onOk()
            } catch (e: NeedsPairingException) {
                main.post { startPairing() }
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private val gtvListener = object : GoogleTvRemote.Listener {
        override fun onVolume(level: Int, max: Int, muted: Boolean) {
            main.post { volumeText.text = if (muted) "음소거" else "$level / $max" }
        }
        override fun onPower(isOn: Boolean) {
            val d = device ?: return
            main.post { setStatus(true, d.name, "${d.typeLabel} · " + if (isOn) "켜짐" else "대기 중") }
        }
    }

    private val castListener = object : CastRemote.Listener {
        override fun onStatus(status: CastRemote.Status) {
            main.post {
                volumeText.text = if (status.muted) "음소거" else "${Math.round(status.volume * 100)}%"
                val app = status.app
                if (app == null || app.isIdleScreen) {
                    nowPlaying.visibility = if (app == null) View.GONE else View.VISIBLE
                    nowPlaying.text = "대기 화면"
                } else {
                    nowPlaying.visibility = View.VISIBLE
                    val state = when (status.playerState) {
                        "PLAYING" -> "재생 중"; "PAUSED" -> "일시정지"; "BUFFERING" -> "불러오는 중"; else -> null
                    }
                    nowPlaying.text = listOfNotNull(app.name, status.mediaTitle, state).joinToString(" · ")
                }
            }
        }
    }

    // ---------------- 페어링 (Google TV) ----------------

    private fun startPairing() {
        val c = controller as? GoogleTvController ?: return
        if (pairingDialogShown) return
        pairingDialogShown = true
        setStatus(null, device?.name ?: "", "페어링 준비 중…")
        worker.execute {
            try {
                c.remote.startPairing()
                main.post { showPairingCodeDialog(c, null) }
            } catch (e: Exception) {
                main.post { pairingDialogShown = false }
                onError(e)
            }
        }
    }

    private fun showPairingCodeDialog(c: GoogleTvController, error: String?) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
            hint = "예: 4A7F21"
            textSize = 24f
            letterSpacing = 0.2f
            error?.let { this.error = it }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(TextView(context).apply {
                text = "TV 화면에 표시된 6자리 코드를 입력하세요.\n(TV 입력이 크롬캐스트로 되어 있어야 코드가 보입니다)"
                textSize = 14f
            })
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Google TV 페어링")
            .setView(box)
            .setPositiveButton("확인", null)
            .setNegativeButton("취소") { _, _ ->
                pairingDialogShown = false
                worker.execute { c.remote.cancelPairing() }
            }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString()
                dialog.dismiss()
                worker.execute {
                    try {
                        c.remote.finishPairing(code)
                        c.remote.connect()
                        main.post {
                            pairingDialogShown = false
                            Toast.makeText(this, "페어링 완료! 이제 리모컨을 쓸 수 있어요.", Toast.LENGTH_LONG).show()
                        }
                        onOk()
                    } catch (e: BadPairingCodeException) {
                        main.post { showPairingCodeDialog(c, e.message) }
                    } catch (e: Exception) {
                        main.post { pairingDialogShown = false }
                        onError(e)
                    }
                }
            }
        }
        dialog.show()
    }

    // ---------------- 기기 선택 ----------------

    private fun showDevicePicker() {
        setStatus(null, device?.name ?: "크롬캐스트 찾는 중…", "같은 Wi-Fi에서 검색합니다")
        Toast.makeText(this, "크롬캐스트를 찾는 중…", Toast.LENGTH_SHORT).show()
        discovery.discover { list ->
            if (isFinishing) return@discover
            device?.let { setStatus(if (lastOk) null else false, it.name, "${it.typeLabel} · ${it.host}") }
                ?: setStatus(null, "크롬캐스트를 선택하세요", "")
            val labels = list.map { "${it.name}\n${it.typeLabel} · ${it.host}" } +
                "IP 주소 직접 입력" +
                ("휴대폰 볼륨 버튼으로 음량 조절: " + if (volumeKeysEnabled) "켜짐" else "꺼짐")
            AlertDialog.Builder(this)
                .setTitle(if (list.isEmpty()) "크롬캐스트를 찾지 못했습니다" else "기기 선택")
                .setItems(labels.toTypedArray()) { _, i ->
                    when {
                        i < list.size -> useDevice(list[i])
                        i == list.size -> showManualIp()
                        else -> {
                            prefs.edit().putBoolean(PREF_VOLUME_KEYS, !volumeKeysEnabled).apply()
                            Toast.makeText(this, "볼륨 버튼: " + if (volumeKeysEnabled) "켜짐" else "꺼짐", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNeutralButton("다시 검색") { _, _ -> showDevicePicker() }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun showManualIp() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "예: 192.168.0.30"
            setText(device?.host ?: "")
        }
        val box = LinearLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("크롬캐스트 IP 주소")
            .setMessage("Google Home 앱 > 기기 > 설정 > 기기 정보에서 IP를 확인할 수 있어요.")
            .setView(box)
            .setPositiveButton("연결") { _, _ ->
                val ip = input.text.toString().trim()
                if (!IP_REGEX.matches(ip)) {
                    Toast.makeText(this, "올바른 IP 주소를 입력하세요", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                probeExecutor.execute {
                    val d = DeviceDiscovery.probe(ip)
                    main.post {
                        if (d == null) Toast.makeText(this, "$ip 에서 크롬캐스트를 찾지 못했습니다", Toast.LENGTH_LONG).show()
                        else useDevice(d)
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------- 휴대폰 볼륨 버튼 ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysEnabled && controller != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { perform(Action.VOL_UP); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { perform(Action.VOL_DOWN); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysEnabled && controller != null &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val PREF_HOST = "device_host"
        private const val PREF_NAME = "device_name"
        private const val PREF_TYPE = "device_type"
        private const val PREF_VOLUME_KEYS = "volume_keys"
        private const val REPEAT_DELAY_MS = 450L
        private const val REPEAT_INTERVAL_MS = 200L
        private val IP_REGEX = Regex("""^((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)$""")
    }
}
