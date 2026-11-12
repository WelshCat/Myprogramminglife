package io.github.welshcat.tvremote

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * 삼성 레거시 리모컨 프로토콜 (2010~2013년형, UN32ES6400F 등). TCP 55000 포트.
 *
 * 패킷: 0x00 | len(appstring) | appstring | len(payload) | payload  (길이는 2바이트 리틀 엔디언)
 * 인증 payload: 0x64 0x00 | b64(내 IP) | b64(기기 ID) | b64(이름)
 * 키 payload:   0x00 0x00 0x00 | b64("KEY_VOLUP")
 *
 * 이 파일은 안드로이드 API에 의존하지 않는다 (JVM에서 테스트 가능).
 */
open class SamsungException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AccessDeniedException(message: String) : SamsungException(message)
class AuthTimeoutException(message: String) : SamsungException(message)

object SamsungProtocol {
    const val DEFAULT_PORT = 55000
    const val APP_STRING = "iphone..iapp.samsung"

    val RESP_GRANTED = byteArrayOf(0x64, 0x00, 0x01, 0x00)
    val RESP_DENIED = byteArrayOf(0x64, 0x00, 0x00, 0x00)

    private fun field(data: ByteArray): ByteArray =
        ByteBuffer.allocate(2 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(data.size.toShort()).put(data).array()

    private fun b64(text: String): ByteArray =
        Base64.getEncoder().encode(text.toByteArray(Charsets.UTF_8))

    fun packet(payload: ByteArray): ByteArray =
        byteArrayOf(0x00) + field(APP_STRING.toByteArray()) + field(payload)

    fun authPacket(ip: String, id: String, name: String): ByteArray =
        packet(byteArrayOf(0x64, 0x00) + field(b64(ip)) + field(b64(id)) + field(b64(name)))

    fun keyPacket(key: String): ByteArray =
        packet(byteArrayOf(0x00, 0x00, 0x00) + field(b64(key)))

    fun normalizeKey(key: String): String {
        val k = key.trim().uppercase()
        return if (k.startsWith("KEY_")) k else "KEY_$k"
    }
}

/** TV와의 연결을 유지하며, 끊어지면 자동으로 다시 연결한다. 스레드 안전. */
class SamsungRemote(
    val host: String,
    val port: Int = SamsungProtocol.DEFAULT_PORT,
    private val remoteName: String = "Android Remote",
    private val remoteId: String = "android-tv-remote",
    private val timeoutMs: Int = 5000,
    private val authTimeoutMs: Long = 30_000,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    val isConnected: Boolean @Synchronized get() = socket != null

    private fun readShortLE(): Int {
        val inp = input ?: throw EOFException()
        val lo = inp.readUnsignedByte()
        val hi = inp.readUnsignedByte()
        return lo or (hi shl 8)
    }

    private fun readResponse(): ByteArray {
        val inp = input ?: throw EOFException()
        inp.readUnsignedByte()
        val nameLen = readShortLE()
        inp.readFully(ByteArray(nameLen))
        val resp = ByteArray(readShortLE())
        inp.readFully(resp)
        return resp
    }

    private fun connectLocked() {
        closeLocked()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
        } catch (e: IOException) {
            s.close()
            throw e
        }
        socket = s
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()
        val localIp = s.localAddress.hostAddress ?: "0.0.0.0"
        output!!.write(SamsungProtocol.authPacket(localIp, remoteId, remoteName))
        output!!.flush()

        val deadline = System.currentTimeMillis() + authTimeoutMs
        while (true) {
            val resp = try {
                readResponse()
            } catch (e: SocketTimeoutException) {
                if (System.currentTimeMillis() > deadline) {
                    closeLocked()
                    throw AuthTimeoutException("TV 화면에서 리모컨 연결을 '허용'해 주세요")
                }
                continue
            }
            when {
                resp.contentEquals(SamsungProtocol.RESP_GRANTED) -> return
                resp.contentEquals(SamsungProtocol.RESP_DENIED) -> {
                    closeLocked()
                    throw AccessDeniedException(
                        "TV가 접근을 거부했습니다. TV 메뉴 > 네트워크 > AllShare 설정에서 이 기기를 '허용'으로 바꿔 주세요")
                }
                resp.isNotEmpty() && resp[0] == 0x0a.toByte() -> continue  // 허용 팝업 대기 중
                resp.isNotEmpty() && resp[0] == 0x65.toByte() -> {
                    closeLocked()
                    throw AuthTimeoutException("TV에서 인증이 취소되었습니다")
                }
            }
        }
    }

    @Synchronized
    fun connect() = connectLocked()

    @Synchronized
    fun sendKey(rawKey: String) {
        val key = SamsungProtocol.normalizeKey(rawKey)
        for (attempt in 1..2) {
            try {
                if (socket == null) connectLocked()
                output!!.write(SamsungProtocol.keyPacket(key))
                output!!.flush()
                try {
                    readResponse()
                } catch (e: SocketTimeoutException) {
                    closeLocked() // 응답이 중간에 끊겼을 수 있으니 다음 키에서 새로 연결
                }
                return
            } catch (e: SamsungException) {
                throw e
            } catch (e: IOException) {
                closeLocked()
                if (attempt == 2) {
                    throw SamsungException(
                        "TV($host)에 연결할 수 없습니다. TV가 켜져 있고 같은 Wi-Fi에 있는지 확인하세요", e)
                }
            }
        }
    }

    private fun closeLocked() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null; input = null; output = null
    }

    @Synchronized
    fun close() = closeLocked()
}

data class DiscoveredTv(val ip: String, val server: String)

/** SSDP로 같은 네트워크의 삼성 TV를 찾는다. 안드로이드에서는 MulticastLock을 잡은 상태에서 호출할 것. */
object TvDiscovery {
    fun discover(timeoutMs: Int = 3000): List<DiscoveredTv> {
        val msg = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: urn:samsung.com:device:RemoteControlReceiver:1\r\n\r\n").toByteArray()
        val found = LinkedHashMap<String, DiscoveredTv>()
        MulticastSocket().use { sock ->
            sock.soTimeout = 500
            sock.timeToLive = 2
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                val server = text.lineSequence()
                    .firstOrNull { it.startsWith("SERVER:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim() ?: ""
                val ip = pkt.address.hostAddress ?: continue
                found[ip] = DiscoveredTv(ip, server)
            }
        }
        return found.values.toList()
    }
}
