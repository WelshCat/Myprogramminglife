package io.github.welshcat.tvremote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * 외부 라이브러리 없이 쓰는 최소한의 protobuf 인코더/디코더.
 * Android TV 리모컨 프로토콜과 Cast 프로토콜에 필요한 타입(varint, 길이 구분 필드)만 지원한다.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    fun int(field: Int, value: Long): ProtoWriter { tag(field, 0); varint(value); return this }
    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())
    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2); varint(value.size.toLong()); out.write(value); return this
    }
    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun encodeVarint(value: Long): ByteArray {
            val w = ProtoWriter(); w.varint(value); return w.toByteArray()
        }
    }
}

/** 디코딩된 protobuf 메시지. 같은 번호의 필드가 여러 번 나오면 모두 보관한다. */
class ProtoMessage(bytes: ByteArray) {
    private val varints = HashMap<Int, MutableList<Long>>()
    private val blobs = HashMap<Int, MutableList<ByteArray>>()

    init {
        var pos = 0
        fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                if (pos >= bytes.size) throw IllegalArgumentException("잘린 varint")
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalArgumentException("너무 긴 varint")
            }
        }
        while (pos < bytes.size) {
            val key = readVarint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> varints.getOrPut(field) { ArrayList() }.add(readVarint())
                1 -> pos += 8
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || pos + len > bytes.size) throw IllegalArgumentException("잘린 필드")
                    blobs.getOrPut(field) { ArrayList() }.add(bytes.copyOfRange(pos, pos + len))
                    pos += len
                }
                5 -> pos += 4
                else -> throw IllegalArgumentException("지원하지 않는 wire type")
            }
        }
    }

    fun has(field: Int) = varints.containsKey(field) || blobs.containsKey(field)
    fun int(field: Int, default: Long = 0): Long = varints[field]?.last() ?: default
    fun bool(field: Int): Boolean = int(field) != 0L
    fun bytes(field: Int): ByteArray? = blobs[field]?.last()
    fun string(field: Int): String = bytes(field)?.toString(Charsets.UTF_8) ?: ""
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { ProtoMessage(it) }
}

/** 스트림에서 varint 하나를 읽는다 (Android TV 프로토콜의 메시지 길이 접두어). */
fun InputStream.readVarint(): Long {
    var result = 0L; var shift = 0
    while (true) {
        val b = read()
        if (b < 0) throw EOFException("연결이 끊겼습니다")
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
        if (shift > 63) throw IllegalArgumentException("너무 긴 varint")
    }
}

fun InputStream.readFully(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r < 0) throw EOFException("연결이 끊겼습니다")
        off += r
    }
    return buf
}
