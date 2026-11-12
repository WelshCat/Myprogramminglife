package io.github.welshcat.tvremote

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

open class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)
class NeedsPairingException : RemoteException("페어링이 필요합니다")
class BadPairingCodeException(message: String) : RemoteException(message)

/** Android 키 코드 (Google TV 리모컨 프로토콜에서 쓰는 값). */
object AtvKey {
    const val HOME = 3; const val BACK = 4
    const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21; const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24; const val VOLUME_DOWN = 25; const val POWER = 26
    const val MENU = 82
    const val MEDIA_PLAY_PAUSE = 85; const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87; const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89; const val MEDIA_FAST_FORWARD = 90
    const val VOLUME_MUTE = 164; const val INFO = 165
    const val SETTINGS = 176; const val TV_INPUT = 178
    const val SLEEP = 223; const val WAKEUP = 224

    const val DIR_START_LONG = 1; const val DIR_END_LONG = 2; const val DIR_SHORT = 3
}

/**
 * 크롬캐스트 with Google TV / Android TV 리모컨 프로토콜 (v2).
 *  - 6467 포트: 페어링 (TV 화면의 6자리 코드 입력)
 *  - 6466 포트: 리모컨 명령
 * 전원·음량 키는 크롬캐스트가 HDMI-CEC로 TV에 전달한다.
 */
class GoogleTvRemote(
    val host: String,
    private val identity: ClientIdentity,
    private val clientName: String,
    private val listener: Listener? = null,
    private val remotePort: Int = 6466,
    private val pairingPort: Int = 6467,
    private val timeoutMs: Int = 5000,
) {
    interface Listener {
        fun onVolume(level: Int, max: Int, muted: Boolean) {}
        fun onPower(isOn: Boolean) {}
        fun onDisconnected() {}
    }

    // ---------------- 공통 프레이밍: varint 길이 + protobuf ----------------
    private fun OutputStream.writeMsg(bytes: ByteArray) {
        write(ProtoWriter.encodeVarint(bytes.size.toLong()) + bytes)
        flush()
    }

    private fun InputStream.readMsg(): ProtoMessage {
        val len = readVarint().toInt()
        if (len < 0 || len > 1_000_000) throw IOException("잘못된 메시지 길이")
        return ProtoMessage(readFully(len))
    }

    // ---------------- 페어링 ----------------
    private var pairingSocket: SSLSocket? = null

    private fun polo(block: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().int(1, 2).int(2, 200).apply(block).toByteArray()   // protocol_version=2, status=OK

    private fun ProtoWriter.hexEncoding() { int(1, 3); int(2, 6) }        // HEXADECIMAL, 6자리

    private fun expectPolo(inp: InputStream, field: Int, what: String): ProtoMessage {
        val msg = inp.readMsg()
        val status = msg.int(2, 200)
        if (status != 200L) {
            throw if (status == 402L) BadPairingCodeException("코드가 맞지 않습니다")
            else RemoteException("페어링 실패 (상태 $status)")
        }
        if (!msg.has(field)) throw RemoteException("페어링 중 예상치 못한 응답 ($what)")
        return msg
    }

    /** 페어링 시작. 성공하면 TV 화면에 6자리 코드가 표시된다. */
    @Synchronized
    fun startPairing() {
        cancelPairing()
        val s = try {
            Tls.connect(Tls.context(identity), host, pairingPort, timeoutMs)
        } catch (e: IOException) {
            throw RemoteException("크롬캐스트($host)에 연결할 수 없습니다", e)
        }
        try {
            val out = s.outputStream; val inp = s.inputStream
            out.writeMsg(polo { message(10) { string(1, "atvremote"); string(2, clientName) } })
            expectPolo(inp, 11, "pairing_request_ack")
            out.writeMsg(polo { message(20) { message(1) { hexEncoding() }; int(3, 1) } })   // ROLE_TYPE_INPUT
            expectPolo(inp, 20, "options")
            out.writeMsg(polo { message(30) { message(1) { hexEncoding() }; int(2, 1) } })
            expectPolo(inp, 31, "configuration_ack")
            s.soTimeout = 0  // 사용자가 코드를 입력할 때까지 기다린다
            pairingSocket = s
        } catch (e: Exception) {
            try { s.close() } catch (_: IOException) {}
            throw if (e is RemoteException) e else RemoteException("페어링을 시작하지 못했습니다", e)
        }
    }

    /** TV 화면에 표시된 6자리 코드로 페어링을 완료한다. */
    @Synchronized
    fun finishPairing(rawCode: String) {
        val s = pairingSocket ?: throw RemoteException("페어링을 다시 시작해 주세요")
        val code = rawCode.trim().uppercase()
        if (!Regex("^[0-9A-F]{6}$").matches(code)) throw BadPairingCodeException("6자리 코드(0-9, A-F)를 입력하세요")
        val secret = pairingSecret(identity.certificate, s.session.peerCertificates[0] as X509Certificate, code)
        if ((secret[0].toInt() and 0xFF) != code.substring(0, 2).toInt(16)) {
            throw BadPairingCodeException("코드가 맞지 않습니다. 다시 확인해 주세요")
        }
        try {
            s.soTimeout = timeoutMs
            s.outputStream.writeMsg(polo { message(40) { bytes(1, secret) } })
            expectPolo(s.inputStream, 41, "secret_ack")
        } catch (e: IOException) {
            throw RemoteException("페어링 중 연결이 끊겼습니다", e)
        } finally {
            cancelPairing()
        }
    }

    @Synchronized
    fun cancelPairing() {
        try { pairingSocket?.close() } catch (_: IOException) {}
        pairingSocket = null
    }

    // ---------------- 리모컨 연결 ----------------
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()

    val isConnected: Boolean get() = socket?.isClosed == false

    @Synchronized
    fun connect() {
        if (isConnected) return
        val ready = CountDownLatch(1)
        val s = try {
            Tls.connect(Tls.context(identity), host, remotePort, timeoutMs)
        } catch (e: SSLException) {
            throw NeedsPairingException()          // 등록되지 않은 인증서는 TLS 단계에서 거부된다
        } catch (e: IOException) {
            throw RemoteException("크롬캐스트($host)에 연결할 수 없습니다. 같은 Wi-Fi인지 확인하세요", e)
        }
        s.soTimeout = 0
        socket = s
        out = s.outputStream
        val gotConfigure = AtomicBoolean(false)
        val started = AtomicBoolean(false)

        Thread({
            val inp = s.inputStream
            try {
                while (true) {
                    val msg = inp.readMsg()
                    handle(msg)?.let { send(it) }
                    if (msg.has(1)) gotConfigure.set(true)
                    if (msg.has(40)) { started.set(true); ready.countDown() }   // remote_start: 준비 완료
                }
            } catch (e: Exception) {
                // 연결 종료
            } finally {
                closeSocket(s)
                ready.countDown()
                listener?.onDisconnected()
            }
        }, "googletv-reader").apply { isDaemon = true }.start()

        if (!ready.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS) || !started.get() || !isConnected) {
            closeSocket(s)
            if (!gotConfigure.get()) throw NeedsPairingException()
            throw RemoteException("크롬캐스트가 응답하지 않습니다")
        }
    }

    /** TV가 보낸 메시지에 대한 응답을 만든다 (없으면 null). */
    private fun handle(msg: ProtoMessage): ByteArray? {
        msg.message(1)?.let { cfg ->                                    // remote_configure
            val active = (cfg.int(1) and FEATURES.toLong()).toInt()
            features = active
            return ProtoWriter().message(1) {
                int(1, active)
                message(2) { int(3, 1); string(4, "1"); string(5, "atvremote"); string(6, "1.0.0") }
            }.toByteArray()
        }
        if (msg.has(2)) return ProtoWriter().message(2) { int(1, features) }.toByteArray()   // set_active
        msg.message(8)?.let { ping ->                                   // ping → pong
            return ProtoWriter().message(9) { int(1, ping.int(1)) }.toByteArray()
        }
        msg.message(40)?.let { listener?.onPower(it.bool(1)) }          // remote_start
        msg.message(50)?.let { v ->                                     // 음량 정보
            listener?.onVolume(v.int(7).toInt(), v.int(6).toInt(), v.bool(8))
        }
        return null
    }

    @Volatile private var features = FEATURES

    private fun send(bytes: ByteArray) {
        val o = out ?: throw RemoteException("연결되어 있지 않습니다")
        synchronized(writeLock) { o.writeMsg(bytes) }
    }

    /** 키 전송. 연결이 없으면 자동으로 연결한다. */
    fun sendKey(keyCode: Int, direction: Int = AtvKey.DIR_SHORT) {
        for (attempt in 1..2) {
            try {
                connect()
                send(ProtoWriter().message(10) { int(1, keyCode); int(2, direction) }.toByteArray())
                return
            } catch (e: RemoteException) {
                throw e
            } catch (e: IOException) {
                close()
                if (attempt == 2) throw RemoteException("크롬캐스트에 명령을 보내지 못했습니다", e)
            }
        }
    }

    private fun closeSocket(s: SSLSocket) {
        try { s.close() } catch (_: IOException) {}
        synchronized(this) { if (socket === s) { socket = null; out = null } }
    }

    fun close() {
        socket?.let { closeSocket(it) }
        cancelPairing()
    }

    companion object {
        // PING | KEY | POWER | VOLUME | APP_LINK
        const val FEATURES = 1 or 2 or 32 or 64 or 512

        /** SHA-256(클라이언트 n, e, 서버 n, e, 코드의 뒤 4자리) — TV가 같은 값을 계산해 비교한다. */
        fun pairingSecret(client: X509Certificate, server: X509Certificate, code: String): ByteArray {
            val c = client.publicKey as RSAPublicKey
            val sv = server.publicKey as RSAPublicKey
            val md = MessageDigest.getInstance("SHA-256")
            for (n in listOf(c.modulus, c.publicExponent, sv.modulus, sv.publicExponent)) md.update(unsigned(n))
            md.update(hex(code.substring(2)))
            return md.digest()
        }

        private fun unsigned(n: BigInteger): ByteArray {
            val b = n.toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
