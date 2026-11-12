package io.github.welshcat.tvremote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 이 앱(리모컨)의 신원: RSA 키 + 자체 서명 인증서.
 * Google TV는 페어링할 때 이 인증서를 기억하고, 이후 같은 인증서로 접속해야 명령을 받는다.
 */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    fun keyBytes(): ByteArray = privateKey.encoded            // PKCS#8
    fun certBytes(): ByteArray = certificate.encoded          // X.509 DER

    companion object {
        fun load(keyBytes: ByteArray, certBytes: ByteArray): ClientIdentity {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            return ClientIdentity(key, cert)
        }

        /** 2048비트 RSA 키와 10년짜리 자체 서명 인증서를 만든다 (Bouncy Castle 없이 DER 직접 작성). */
        fun generate(commonName: String = "atvremote"): ClientIdentity {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val sha256WithRsa = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.NULL)
            val name = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(commonName))))
            val now = System.currentTimeMillis()
            val tbs = Der.seq(
                Der.explicit(0, Der.int(BigInteger.valueOf(2))),                    // v3
                Der.int(BigInteger(63, SecureRandom())),                             // serial
                sha256WithRsa,
                name,
                Der.seq(Der.utcTime(Date(now - 86_400_000L)), Der.utcTime(Date(now + 10 * 365 * 86_400_000L))),
                name,
                kp.public.encoded,                                                   // SubjectPublicKeyInfo
            )
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(kp.private); update(tbs); sign()
            }
            val der = Der.seq(tbs, sha256WithRsa, Der.bitString(sig))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            cert.verify(kp.public)
            return ClientIdentity(kp.private, cert)
        }
    }
}

/** 인증서 작성에 필요한 최소 DER 인코더. */
internal object Der {
    val NULL = byteArrayOf(0x05, 0x00)

    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(content.size) + content
    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun bitString(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)
    fun explicit(n: Int, inner: ByteArray) = tlv(0xA0 or n, inner)

    fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split(".").map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = p
            stack.add((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) { stack.add(((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
            stack.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }
}

/** TV/크롬캐스트는 자체 서명 인증서를 쓰므로 서버 인증서를 검증하지 않는다 (같은 집 네트워크 전제). */
internal object TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

internal object Tls {
    fun context(identity: ClientIdentity?): SSLContext {
        val kms = identity?.let {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val pw = CharArray(0)
            ks.setKeyEntry("client", it.privateKey, pw, arrayOf(it.certificate))
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pw) }.keyManagers
        }
        return SSLContext.getInstance("TLS").apply { init(kms, arrayOf<TrustManager>(TrustAll), SecureRandom()) }
    }

    fun connect(ctx: SSLContext, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            raw.tcpNoDelay = true
            val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            s.startHandshake()
            return s
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
    }

    /** TCP 포트가 열려 있는지만 빠르게 확인 (기기 종류 판별용). */
    fun isPortOpen(host: String, port: Int, timeoutMs: Int = 1500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) { false }
}
