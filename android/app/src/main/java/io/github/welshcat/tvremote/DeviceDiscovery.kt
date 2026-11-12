package io.github.welshcat.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

enum class DeviceType { GOOGLE_TV, CAST }

data class Device(val host: String, val name: String, val type: DeviceType, val model: String = "") {
    val typeLabel: String get() = if (type == DeviceType.GOOGLE_TV) "Google TV" else "크롬캐스트"
}

/**
 * mDNS(Bonjour)로 같은 Wi-Fi의 크롬캐스트를 찾는다.
 *  - _androidtvremote2._tcp : 크롬캐스트 with Google TV / Android TV (전체 리모컨 가능)
 *  - _googlecast._tcp       : 모든 크롬캐스트 (캐스트 제어)
 */
class DeviceDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, String>>()
    private var resolving = false
    private val found = LinkedHashMap<String, Device>()

    fun discover(timeoutMs: Long = 4000, onDone: (List<Device>) -> Unit) {
        stop()
        found.clear()
        for (type in listOf(TYPE_ATV, TYPE_CAST)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post { resolveQueue.add(info to type); resolveNext() }
                }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                listeners += l
            } catch (_: Exception) {}
        }
        main.postDelayed({
            stop()
            onDone(found.values.sortedBy { it.type }.toList())
        }, timeoutMs)
    }

    /** NsdManager는 (API 34 미만에서) 한 번에 하나만 resolve할 수 있어서 순서대로 처리한다. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val (info, type) = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(si: NsdServiceInfo) {
                main.post {
                    val addr = si.host
                    if (addr is Inet4Address) add(addr.hostAddress ?: "", si, type)
                    resolving = false
                    resolveNext()
                }
            }
        })
    }

    private fun add(host: String, si: NsdServiceInfo, type: String) {
        if (host.isEmpty()) return
        val attrs = si.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
        val prev = found[host]
        if (type == TYPE_ATV) {
            found[host] = Device(host, prev?.name ?: si.serviceName, DeviceType.GOOGLE_TV, prev?.model ?: "")
        } else {
            val name = attrs["fn"]?.takeIf { it.isNotEmpty() } ?: si.serviceName
            val model = attrs["md"] ?: ""
            found[host] = Device(host, name, prev?.type ?: DeviceType.CAST, model)
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        for (l in listeners) try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
        listeners.clear()
        resolveQueue.clear()
        resolving = false
    }

    companion object {
        private const val TYPE_ATV = "_androidtvremote2._tcp"
        private const val TYPE_CAST = "_googlecast._tcp"

        /** IP를 직접 입력했을 때 포트로 종류를 판별한다 (네트워크 스레드에서 호출). */
        fun probe(host: String): Device? = when {
            Tls.isPortOpen(host, 6466) -> Device(host, host, DeviceType.GOOGLE_TV)
            Tls.isPortOpen(host, 8009) -> Device(host, host, DeviceType.CAST)
            else -> null
        }
    }
}
