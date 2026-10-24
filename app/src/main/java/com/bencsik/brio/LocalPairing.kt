package com.bencsik.brio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.bencsik.brio.core.CloudRecord
import com.bencsik.brio.core.mergeCloudRecords
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

data class PairingState(val code: String? = null, val address: String? = null,
    val busy: Boolean = false, val status: String = "Sync your library with another Brio device on the same Wi-Fi.")

/** Temporary local pairing; no account, relay, project key, or video links are transmitted. */
class LocalPairing private constructor(context: Context) {
    private val context = context.applicationContext
    private val store = BrioStore(this.context)
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PairingState())
    val state = _state.asStateFlow()
    private var server: ServerSocket? = null
    private var serverJob: Job? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var expiresAt = 0L
    private var attempts = 0
    private val recordType = object : TypeToken<List<CloudRecord>>() {}.type

    init { store.migrateToDeviceLibrary() }

    fun startOffer() {
        stopOffer()
        try {
            val socket = ServerSocket(0).apply { soTimeout = 1000 }
            val code = (100000 + SecureRandom().nextInt(900000)).toString()
            server = socket
            expiresAt = System.currentTimeMillis() + 5 * 60_000
            attempts = 0
            _state.value = PairingState(code, localAddress()?.let { "$it:${socket.localPort}" },
                status = "Enter this code on your other device within 5 minutes.")
            val service = NsdServiceInfo().apply {
                serviceName = "Brio library"
                serviceType = SERVICE_TYPE
                port = socket.localPort
            }
            registration = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    _state.value = _state.value.copy(status = "Discovery unavailable. Enter the displayed IP address on the other device.")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }.also { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, it) }
            serverJob = scope.launch(Dispatchers.IO) {
                while (!socket.isClosed && System.currentTimeMillis() < expiresAt) {
                    try {
                        socket.accept().use(::serve)
                    } catch (_: SocketTimeoutException) { }
                    catch (_: java.net.SocketException) { break }
                    catch (_: Exception) { /* One bad request cannot end the offer. */ }
                }
                withContext(Dispatchers.Main) { stopOffer() }
            }
        } catch (e: Exception) {
            stopOffer()
            _state.value = _state.value.copy(status = "Couldn't start pairing: ${e.message ?: "Check your network"}")
        }
    }

    fun stopOffer() {
        server?.close(); server = null
        serverJob?.cancel(); serverJob = null
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        _state.value = _state.value.copy(code = null, address = null)
    }

    suspend fun connect(code: String, manualAddress: String = "") {
        if (!code.matches(Regex("[0-9]{6}"))) {
            _state.value = _state.value.copy(status = "Enter the six-digit code shown on the other device."); return
        }
        _state.value = _state.value.copy(busy = true, status = "Finding your other Brio device…")
        try {
            withContext(Dispatchers.IO) {
                if (manualAddress.isNotBlank()) {
                    val parts = manualAddress.trim().split(':')
                    require(parts.size == 2) { "Enter the displayed address as IP:port." }
                    val host = InetAddress.getByName(parts[0])
                    require(host.isSiteLocalAddress) { "Use a device IP address on the same local network." }
                    val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: error("Enter a valid port after the IP address.")
                    exchange(host, port, code)
                } else discoverAndExchange(code)
            }
            _state.value = _state.value.copy(status = "Library synced with the other device.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.value = _state.value.copy(status = "Pairing failed: ${e.message ?: "Try again while both apps are open"}") }
        finally { _state.value = _state.value.copy(busy = false) }
    }

    private fun serve(peer: Socket) {
        peer.soTimeout = 5000
        if (!peer.inetAddress.isSiteLocalAddress) return
        val incoming = read(peer)
        val payload = JsonParser.parseString(incoming).asJsonObject
        val valid = payload.get("code")?.asString == _state.value.code &&
            System.currentTimeMillis() < expiresAt && attempts < 5
        if (!valid) {
            attempts++
            write(peer, gson.toJson(mapOf("error" to "Invalid or expired code")))
            return
        }
        val entries = gson.fromJson<List<CloudRecord>>(payload.get("records"), recordType).orEmpty()
        val safe = mergeCloudRecords(emptyList(), entries)
        val ours = mergeCloudRecords(emptyList(), store.cloudRecords())
        store.applyCloud(safe)
        write(peer, gson.toJson(mapOf("records" to ours)))
        scope.launch { _state.value = _state.value.copy(status = "Library synced. You can close this code.") }
        // A code is one use only. Another device needs a fresh offer.
        expiresAt = 0
    }

    private suspend fun discoverAndExchange(code: String) {
        val results = Channel<Pair<InetAddress, Int>>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { results.close() }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        serviceInfo.host?.let { results.trySend(it to serviceInfo.port) }
                    }
                })
            }
        }
        val multicast = wifi?.createMulticastLock("brio-pairing")?.apply { setReferenceCounted(false) }
        try {
            multicast?.acquire()
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            var connected = false
            withTimeout(12_000) {
                for ((host, port) in results) {
                    if (!host.isSiteLocalAddress) continue
                    try { exchange(host, port, code); connected = true; return@withTimeout }
                    catch (_: Exception) { /* Another nearby Brio device may have a different code. */ }
                }
            }
            check(connected) { "Discovery unavailable. Enter the other device's displayed IP:port." }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            error("Device not found. Keep both apps open on the same Wi-Fi or enter the other device's IP address.")
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (multicast?.isHeld == true) multicast.release()
            results.close()
        }
    }

    private fun exchange(host: InetAddress, port: Int, code: String) {
        Socket().use { peer ->
            peer.connect(java.net.InetSocketAddress(host, port), 2500)
            peer.soTimeout = 5000
            write(peer, gson.toJson(mapOf("code" to code,
                "records" to mergeCloudRecords(emptyList(), store.cloudRecords()))))
            val response = JsonParser.parseString(read(peer)).asJsonObject
            response.get("error")?.asString?.let { error(it) }
            val records = gson.fromJson<List<CloudRecord>>(response.get("records"), recordType).orEmpty()
            store.applyCloud(mergeCloudRecords(emptyList(), records))
        }
    }

    private fun read(socket: Socket): String {
        val stream = DataInputStream(socket.getInputStream())
        val length = stream.readInt()
        require(length in 1..2_000_000) { "Pairing data is too large." }
        return ByteArray(length).also { stream.readFully(it) }.toString(Charsets.UTF_8)
    }
    private fun write(socket: Socket, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000) { "Library is too large for pairing." }
        DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
    }
    private fun localAddress(): String? = NetworkInterface.getNetworkInterfaces()?.toList()?.asSequence()
        ?.filter { it.isUp && !it.isLoopback }?.flatMap { it.inetAddresses.toList().asSequence() }
        ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    companion object {
        private const val SERVICE_TYPE = "_brio-sync._tcp."
        @Volatile private var instance: LocalPairing? = null
        fun get(context: Context): LocalPairing = instance ?: synchronized(this) {
            instance ?: LocalPairing(context).also { instance = it }
        }
    }
}
