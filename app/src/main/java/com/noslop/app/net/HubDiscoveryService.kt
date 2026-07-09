package com.noslop.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.noslop.app.debug.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.net.InetAddress

data class DiscoveredHub(
    val serviceName: String,
    val hostName: String,
    val ipAddress: String,
    val port: Int
)

class HubDiscoveryService(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _discoveredHubs = MutableStateFlow<List<DiscoveredHub>>(emptyList())
    val discoveredHubs: StateFlow<List<DiscoveredHub>> = _discoveredHubs.asStateFlow()

    private val SERVICE_TYPE = "_ssh._tcp."
    private val TAG = "HubDiscovery"

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    // Coroutine scope for active scanning
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var scanJob: kotlinx.coroutines.Job? = null

    fun startDiscovery() {
        if (discoveryListener != null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Logger.info(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Logger.info(TAG, "Service found: ${service.serviceName}")
                // Only resolve if it's not already in our list
                val existing = _discoveredHubs.value.find { it.serviceName == service.serviceName }
                if (existing == null) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Logger.error(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Logger.info(TAG, "Resolve Succeeded: ${serviceInfo.serviceName}")
                            val ip = serviceInfo.host?.hostAddress
                            val hostName = serviceInfo.host?.hostName
                            
                            if (ip != null) {
                                val hub = DiscoveredHub(
                                    serviceName = serviceInfo.serviceName,
                                    hostName = hostName ?: "Unknown Host",
                                    ipAddress = ip,
                                    port = serviceInfo.port
                                )
                                _discoveredHubs.update { current ->
                                    val newList = current.toMutableList()
                                    newList.removeAll { it.serviceName == hub.serviceName }
                                    newList.add(hub)
                                    newList
                                }
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Logger.info(TAG, "Service lost: ${service.serviceName}")
                _discoveredHubs.update { current ->
                    current.filterNot { it.serviceName == service.serviceName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Logger.info(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logger.error(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logger.error(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to start discovery: ${e.message}")
        }

        // Simultaneously start active subnet scanning
        startActiveSubnetScan()
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Logger.error(TAG, "Failed to stop discovery: ${e.message}")
            }
            discoveryListener = null
        }
        scanJob?.cancel()
        _discoveredHubs.value = emptyList()
    }

    private fun startActiveSubnetScan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            val prefixes = getLocalIpPrefixes()
            if (prefixes.isEmpty()) return@launch
            Logger.info(TAG, "Starting active SSH scan on subnets: $prefixes")

            val deferreds = prefixes.flatMap { prefix ->
                (1..254).map { i ->
                    async {
                        val targetIp = "$prefix$i"
                        try {
                            val socket = java.net.Socket()
                            // 500ms timeout for the connection
                            socket.connect(java.net.InetSocketAddress(targetIp, 22), 500)
                            socket.close()
                            
                            // If we succeed, it has SSH open!
                            val hub = DiscoveredHub(
                                serviceName = "ActiveScan-$targetIp",
                                hostName = "SSH Host ($targetIp)",
                                ipAddress = targetIp,
                                port = 22
                            )
                            
                            // Deduplicate by IP
                            _discoveredHubs.update { current ->
                                if (current.any { it.ipAddress == targetIp }) {
                                    current
                                } else {
                                    current + hub
                                }
                            }
                            Logger.info(TAG, "Active scan found SSH at: $targetIp")
                        } catch (e: Exception) {
                            // Connection refused or timed out, ignore
                        }
                    }
                }
            }
            // Wait for all to finish
            deferreds.awaitAll()
            Logger.info(TAG, "Active SSH scan completed")
        }
    }

    private fun getLocalIpPrefixes(): List<String> {
        val prefixes = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        val lastDot = ip.lastIndexOf('.')
                        if (lastDot != -1) {
                            prefixes.add(ip.substring(0, lastDot + 1))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to get local IP prefixes: ${e.message}")
        }
        return prefixes.distinct()
    }
}
