package io.github.welshcat.tvremote

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

/**
 * Google Cast 프로토콜 (TCP 8009, TLS). 리모컨이 없는 크롬캐스트(동글형)용.
 *  - TV 켜기/입력 전환: 앱을 실행하면 크롬캐스트가 HDMI-CEC로 TV를 켜고 입력을 바꾼다.
 *  - 음량: 크롬캐스트(캐스트) 음량
 *  - 재생 제어: 지금 캐스트 중인 미디어 (유튜브, 넷플릭스 등)
 */
class CastRemote(
    val host: String,
    private val listener: Listener? = null,
    private val port: Int = 8009,
    private val timeoutMs: Int = 5000,
) {
    data class AppInfo(val appId: String, val name: String, val sessionId: String,
                       val transportId: String, val supportsMedia: Boolean, val isIdleScreen: Boolean)

    data class Status(
        val volume: Double = 1.0,
        val muted: Boolean = false,
        val app: AppInfo? = null,
        val mediaTitle: String? = null,
        val playerState: String? = null,   // PLAYING, PAUSED, BUFFERING, IDLE
        val currentTime: Double = 0.0,
    )

    interface Listener {
        fun onStatus(status: Status) {}
        fun onDisconnected() {}
    }

    @Volatile var status = Status(); private set

    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private val requestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pair<CountDownLatch, Array<JSONObject?>>>()
    private var heartbeat: ScheduledExecutorService? = null
    private var mediaTransport: String? = null
    private var mediaSessionId: Long? = null

    val isConnected: Boolean get() = socket?.isClosed == false

    // ---------------- 프레이밍: 4바이트 길이(빅 엔디언) + CastMessage protobuf ----------------
    private fun sendRaw(ns: String, dest: String, payload: JSONObject) {
        val msg = ProtoWriter()
            .int(1, 0)                         // CASTV2_1_0
            .string(2, SOURCE)
            .string(3, dest)
            .string(4, ns)
            .int(5, 0)                         // STRING
            .string(6, payload.toString())
            .toByteArray()
        val o = out ?: throw RemoteException("연결되어 있지 않습니다")
        synchronized(writeLock) {
            o.write(ByteBuffer.allocate(4).putInt(msg.size).array() + msg)
            o.flush()
        }
    }

    /** 요청을 보내고 같은 requestId의 응답을 기다린다. */
    private fun request(ns: String, dest: String, payload: JSONObject): JSONObject {
        val id = requestId.getAndIncrement()
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<JSONObject>(1)
        pending[id] = latch to box
        try {
            sendRaw(ns, dest, payload.put("requestId", id))
            if (!latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) throw RemoteException("크롬캐스트가 응답하지 않습니다")
            return box[0] ?: throw RemoteException("연결이 끊겼습니다")
        } finally {
            pending.remove(id)
        }
    }

    @Synchronized
    fun connect() {
        if (isConnected) return
        val s = try {
            Tls.connect(Tls.context(null), host, port, timeoutMs)
        } catch (e: IOException) {
            throw RemoteException("크롬캐스트($host)에 연결할 수 없습니다. 같은 Wi-Fi인지 확인하세요", e)
        }
        s.soTimeout = 0
        socket = s; out = s.outputStream
        mediaTransport = null; mediaSessionId = null

        Thread({
            val inp = DataInputStream(s.inputStream)
            try {
                while (true) {
                    val len = inp.readInt()
                    if (len < 0 || len > 1_000_000) throw IOException("잘못된 메시지 길이")
                    val buf = ByteArray(len); inp.readFully(buf)
                    onMessage(ProtoMessage(buf))
                }
            } catch (e: Exception) {
                // 연결 종료
            } finally {
                disconnect(s)
                listener?.onDisconnected()
            }
        }, "cast-reader").apply { isDaemon = true }.start()

        sendRaw(NS_CONNECTION, RECEIVER, JSONObject().put("type", "CONNECT"))
        heartbeat = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cast-heartbeat").apply { isDaemon = true } }
            .also { hb ->
                hb.scheduleWithFixedDelay({
                    try { sendRaw(NS_HEARTBEAT, RECEIVER, JSONObject().put("type", "PING")) } catch (_: Exception) {}
                }, 5, 5, TimeUnit.SECONDS)
            }
        refresh()
    }

    private fun onMessage(msg: ProtoMessage) {
        val ns = msg.string(4)
        val json = try { JSONObject(msg.string(6)) } catch (e: Exception) { return }
        val type = json.optString("type")
        if (ns == NS_HEARTBEAT && type == "PING") {
            sendRaw(NS_HEARTBEAT, msg.string(2), JSONObject().put("type", "PONG"))
            return
        }
        if (ns == NS_CONNECTION && type == "CLOSE") {
            if (msg.string(2) == RECEIVER) socket?.let { disconnect(it) }
            else if (msg.string(2) == mediaTransport) { mediaTransport = null; mediaSessionId = null }
            return
        }
        when (type) {
            "RECEIVER_STATUS" -> json.optJSONObject("status")?.let { parseReceiverStatus(it) }
            "MEDIA_STATUS" -> parseMediaStatus(json.optJSONArray("status"))
        }
        val id = json.optInt("requestId", 0)
        pending[id]?.let { (latch, box) -> box[0] = json; latch.countDown() }
    }

    private fun parseReceiverStatus(st: JSONObject) {
        val vol = st.optJSONObject("volume")
        var app: AppInfo? = null
        st.optJSONArray("applications")?.let { apps ->
            if (apps.length() > 0) {
                val a = apps.getJSONObject(0)
                val nss = a.optJSONArray("namespaces")
                var media = false
                if (nss != null) for (i in 0 until nss.length()) {
                    if (nss.optJSONObject(i)?.optString("name") == NS_MEDIA) media = true
                }
                app = AppInfo(
                    appId = a.optString("appId"),
                    name = a.optString("displayName"),
                    sessionId = a.optString("sessionId"),
                    transportId = a.optString("transportId"),
                    supportsMedia = media,
                    isIdleScreen = a.optBoolean("isIdleScreen", false) || a.optString("appId") == APP_BACKDROP,
                )
            }
        }
        if (app?.transportId != mediaTransport) { mediaTransport = null; mediaSessionId = null }
        status = status.copy(
            volume = vol?.optDouble("level", status.volume) ?: status.volume,
            muted = vol?.optBoolean("muted", status.muted) ?: status.muted,
            app = app,
            mediaTitle = if (app == null) null else status.mediaTitle,
            playerState = if (app == null) null else status.playerState,
        )
        listener?.onStatus(status)
    }

    private fun parseMediaStatus(arr: JSONArray?) {
        val m = arr?.optJSONObject(0)
        if (m == null) {
            mediaSessionId = null
            status = status.copy(mediaTitle = null, playerState = null)
        } else {
            mediaSessionId = m.optLong("mediaSessionId")
            val title = m.optJSONObject("media")?.optJSONObject("metadata")?.optString("title")
            status = status.copy(
                mediaTitle = title?.takeIf { it.isNotEmpty() } ?: status.mediaTitle,
                playerState = m.optString("playerState", status.playerState ?: ""),
                currentTime = m.optDouble("currentTime", status.currentTime),
            )
        }
        listener?.onStatus(status)
    }

    private fun receiver(type: String, extra: JSONObject.() -> Unit = {}): JSONObject {
        connect()
        return request(NS_RECEIVER, RECEIVER, JSONObject().put("type", type).apply(extra))
    }

    /** 상태 새로고침 (음량, 실행 중인 앱, 재생 중인 미디어). */
    fun refresh() {
        receiver("GET_STATUS")
        ensureMedia()
    }

    /** TV 켜기 + 입력 전환: 크롬캐스트 대기 화면 앱을 실행하면 CEC로 TV가 켜진다. */
    fun wakeTv() {
        val r = receiver("LAUNCH") { put("appId", APP_BACKDROP) }
        if (r.optString("type") == "LAUNCH_ERROR") {
            receiver("LAUNCH") { put("appId", APP_DEFAULT_MEDIA_RECEIVER) }
        }
    }

    /** 지금 캐스트 중인 앱 종료. */
    fun stopApp() {
        val sid = status.app?.sessionId ?: run { refresh(); status.app?.sessionId } ?: return
        receiver("STOP") { put("sessionId", sid) }
    }

    fun setVolume(level: Double) {
        receiver("SET_VOLUME") { put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0))) }
    }

    fun volumeStep(delta: Double) {
        if (!isConnected) connect()
        setVolume(Math.round((status.volume + delta) * 100) / 100.0)
    }

    fun toggleMute() {
        receiver("SET_VOLUME") { put("volume", JSONObject().put("muted", !status.muted)) }
    }

    // ---------------- 미디어 제어 ----------------
    /** 실행 중인 앱이 미디어를 지원하면 미디어 채널에 연결하고 상태를 받아온다. */
    private fun ensureMedia(): Boolean {
        val app = status.app ?: return false
        if (!app.supportsMedia) return false
        if (mediaTransport != app.transportId) {
            sendRaw(NS_CONNECTION, app.transportId, JSONObject().put("type", "CONNECT"))
            mediaTransport = app.transportId
            mediaSessionId = null
        }
        if (mediaSessionId == null) {
            try { request(NS_MEDIA, app.transportId, JSONObject().put("type", "GET_STATUS")) } catch (_: RemoteException) {}
        }
        return mediaSessionId != null
    }

    private fun media(type: String, extra: JSONObject.() -> Unit = {}) {
        connect()
        if (!ensureMedia()) {
            refresh()
            if (!ensureMedia()) throw RemoteException("지금 재생 중인 미디어가 없습니다")
        }
        request(NS_MEDIA, mediaTransport!!, JSONObject().put("type", type)
            .put("mediaSessionId", mediaSessionId!!).apply(extra))
    }

    fun playPause() = media(if (status.playerState == "PLAYING" || status.playerState == "BUFFERING") "PAUSE" else "PLAY")
    fun stopMedia() = media("STOP")
    fun seekBy(seconds: Double) = media("SEEK") { put("currentTime", (status.currentTime + seconds).coerceAtLeast(0.0)) }

    // ---------------- 종료 ----------------
    private fun disconnect(s: SSLSocket) {
        try { s.close() } catch (_: IOException) {}
        synchronized(this) {
            if (socket === s) {
                socket = null; out = null
                heartbeat?.shutdownNow(); heartbeat = null
                mediaTransport = null; mediaSessionId = null
            }
        }
        pending.values.forEach { it.first.countDown() }
    }

    fun close() {
        socket?.let { s ->
            try { sendRaw(NS_CONNECTION, RECEIVER, JSONObject().put("type", "CLOSE")) } catch (_: Exception) {}
            disconnect(s)
        }
    }

    companion object {
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        const val RECEIVER = "receiver-0"
        const val SOURCE = "sender-tvremote"
        const val APP_BACKDROP = "E8C28D3C"
        const val APP_DEFAULT_MEDIA_RECEIVER = "CC1AD845"
    }
}
