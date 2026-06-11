package com.localnetworkinfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.net.Inet4Address
import java.net.NetworkInterface

class LocalNetworkInfoModule : Module() {

  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val connectivityManager: ConnectivityManager
    get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var apStateReceiver: BroadcastReceiver? = null
  private var handlerThread: HandlerThread? = null
  private var handler: Handler? = null
  private val resampleRunnable = Runnable { doEmit() }

  override fun definition() = ModuleDefinition {
    Name("LocalNetworkInfo")

    Events("onNetworkChange")

    AsyncFunction("getLocalIpAsync") {
      return@AsyncFunction buildInfo()
    }

    AsyncFunction("getAllInterfacesAsync") {
      return@AsyncFunction enumerateInterfaces().map { it.toMap() }
    }

    OnStartObserving {
      startMonitoring()
    }

    OnStopObserving {
      stopMonitoring()
    }

    OnDestroy {
      stopMonitoring()
    }
  }

  // region Network change monitoring

  private fun startMonitoring() {
    if (handlerThread == null) {
      val thread = HandlerThread("expo.localnetworkinfo.emit").apply { start() }
      handler = Handler(thread.looper)
      handlerThread = thread
    }

    if (networkCallback == null) {
      val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = emitChange()
        override fun onLost(network: Network) = emitChange()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = emitChange()
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = emitChange()
      }
      try {
        // registerDefaultNetworkCallback is API 24+ (our minSdk is 24).
        connectivityManager.registerDefaultNetworkCallback(callback)
        networkCallback = callback
      } catch (_: Exception) {
        // Leave networkCallback null; the AP receiver below may still work.
      }
    }

    if (apStateReceiver == null) {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context?, intent: Intent?) = emitChange()
      }
      try {
        // Fired by the system whenever the WiFi hotspot is enabled/disabled.
        // NetworkCallback does NOT cover the device's own SoftAP, so we need this.
        val filter = IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
          @Suppress("UnspecifiedRegisterReceiverFlag")
          context.applicationContext.registerReceiver(receiver, filter)
        }
        apStateReceiver = receiver
      } catch (_: Exception) {
        // Hotspot-toggle events will be missed, but everything else still works.
      }
    }
  }

  private fun stopMonitoring() {
    handler?.removeCallbacks(resampleRunnable)
    handlerThread?.quitSafely()
    handlerThread = null
    handler = null

    networkCallback?.let {
      try {
        connectivityManager.unregisterNetworkCallback(it)
      } catch (_: Exception) {
      }
    }
    networkCallback = null

    apStateReceiver?.let {
      try {
        context.applicationContext.unregisterReceiver(it)
      } catch (_: Exception) {
      }
    }
    apStateReceiver = null
  }

  private fun doEmit() {
    try {
      sendEvent("onNetworkChange", buildInfo())
    } catch (_: Exception) {
    }
  }

  private fun emitChange() {
    // Emit an immediate snapshot, then re-sample over the next few seconds. On
    // Android the hotspot/station interface frequently receives its IPv4 a beat
    // AFTER the change event fires, and no further event follows — so a single
    // immediate snapshot can report "no connection" until the next manual
    // refresh. The trailing re-samples catch the late address assignment.
    doEmit()
    handler?.let { h ->
      h.removeCallbacks(resampleRunnable)
      h.postDelayed(resampleRunnable, 700)
      h.postDelayed(resampleRunnable, 1800)
      h.postDelayed(resampleRunnable, 3500)
    }
  }

  // endregion

  // region Interface model

  private enum class Role(val value: String) {
    WIFI("wifi"),
    HOTSPOT("hotspot"),
    CELLULAR("cellular"),
    ETHERNET("ethernet"),
    OTHER("other")
  }

  private data class Iface(
    val name: String,
    val ip: String,
    val netmask: String?,
    val prefixLength: Int,
    val role: Role
  ) {
    fun toMap(): Map<String, Any?> = mapOf(
      "name" to name,
      "ip" to ip,
      "netmask" to netmask,
      "role" to role.value
    )
  }

  /**
   * Classify an interface, using ConnectivityManager to confirm which interface
   * actually carries a WiFi *station* network. This is what lets us tell a
   * hotspot apart from a station even when the device hosts its SoftAP on
   * `wlan0` itself (common on single-radio phones) instead of a separate
   * `ap0` / `wlan1` / `swlan0` / `softap0` interface.
   */
  private fun classify(name: String, stationInterfaces: Set<String>): Role = when {
    // Confirmed by the system to be a real WiFi station (client) connection.
    name in stationInterfaces -> Role.WIFI
    name.startsWith("rmnet") || name.startsWith("ccmni") -> Role.CELLULAR
    name.startsWith("eth") -> Role.ETHERNET
    // Any WiFi-family interface that is NOT a confirmed station is the hotspot/SoftAP.
    name.startsWith("wlan") ||
      name.startsWith("ap") ||
      name.startsWith("swlan") ||
      name.startsWith("softap") -> Role.HOTSPOT
    else -> Role.OTHER
  }

  /** Interface names ConnectivityManager reports as carrying a WiFi station network. */
  private fun wifiStationInterfaceNames(): Set<String> {
    val names = mutableSetOf<String>()
    try {
      @Suppress("DEPRECATION")
      val networks = connectivityManager.allNetworks
      for (network in networks) {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
        connectivityManager.getLinkProperties(network)?.interfaceName?.let { names.add(it) }
      }
    } catch (_: Exception) {
    }
    return names
  }

  // endregion

  // region Detection

  private fun enumerateInterfaces(): List<Iface> {
    val result = mutableListOf<Iface>()
    val stationInterfaces = wifiStationInterfaceNames()

    val interfaces = try {
      NetworkInterface.getNetworkInterfaces() ?: return result
    } catch (_: Exception) {
      return result
    }

    for (nif in interfaces) {
      try {
        if (!nif.isUp || nif.isLoopback) continue
      } catch (_: Exception) {
        continue
      }

      // interfaceAddresses gives us the prefix length (for netmask + prediction).
      for (interfaceAddress in nif.interfaceAddresses) {
        val address = interfaceAddress.address
        if (address !is Inet4Address) continue
        if (address.isLoopbackAddress || address.isLinkLocalAddress) continue

        val ip = address.hostAddress ?: continue
        val prefix = interfaceAddress.networkPrefixLength.toInt()
        result.add(Iface(nif.name, ip, prefixToNetmask(prefix), prefix, classify(nif.name, stationInterfaces)))
      }
    }

    return result
  }

  private fun buildInfo(): Map<String, Any?> {
    val interfaces = enumerateInterfaces()

    val station = interfaces.firstOrNull { it.role == Role.WIFI }
    val host = interfaces.firstOrNull { it.role == Role.HOTSPOT }

    val isWifiConnected = station != null
    val isHotspotHost = host != null

    var ip: String? = null
    var role = "none"
    var interfaceName: String? = null
    var netmask: String? = null
    var gateway: String? = null
    var predicted: Map<String, String>? = null

    if (station != null) {
      ip = station.ip
      role = "wifi"
      interfaceName = station.name
      netmask = station.netmask
      // Prefer the real default-route gateway; fall back to a subnet guess.
      gateway = stationGateway() ?: deriveGateway(station.ip, station.prefixLength)
    } else if (host != null) {
      ip = host.ip
      role = "hotspot"
      interfaceName = host.name
      netmask = host.netmask
      gateway = host.ip // the hotspot host is its own gateway
      predicted = predictClientRange(host.ip, host.prefixLength)
    }

    return mapOf(
      "ip" to ip,
      "role" to role,
      "isWifiConnected" to isWifiConnected,
      "isHotspotHost" to isHotspotHost,
      "interfaceName" to interfaceName,
      "netmask" to netmask,
      "gateway" to gateway,
      "predictedClientRange" to predicted,
      "platform" to "android",
      "timestamp" to System.currentTimeMillis().toDouble()
    )
  }

  /** Real station gateway via LinkProperties (default route), with a DHCP fallback. */
  private fun stationGateway(): String? {
    return try {
      val network = connectivityManager.activeNetwork ?: return null
      val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
      // Ignore the 0.0.0.0 wildcard (appears as the "gateway" of a directly-connected
      // default route, e.g. when the device is tethering rather than a real station).
      val routeGateway = linkProperties.routes
        .firstOrNull { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }
        ?.gateway
        ?.hostAddress
      if (routeGateway != null) return routeGateway
      val dhcpServer = if (Build.VERSION.SDK_INT >= 30) linkProperties.dhcpServerAddress else null
      if (dhcpServer != null && !dhcpServer.isAnyLocalAddress) dhcpServer.hostAddress else null
    } catch (_: Exception) {
      null
    }
  }

  // endregion

  // region IPv4 math

  private fun prefixToNetmask(prefix: Int): String? {
    if (prefix < 0 || prefix > 32) return null
    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
    return "${(mask ushr 24) and 0xff}.${(mask ushr 16) and 0xff}.${(mask ushr 8) and 0xff}.${mask and 0xff}"
  }

  private fun ipToInt(ip: String): Int? {
    val parts = ip.split(".")
    if (parts.size != 4) return null
    var value = 0
    for (part in parts) {
      val octet = part.toIntOrNull() ?: return null
      if (octet < 0 || octet > 255) return null
      value = (value shl 8) or octet
    }
    return value
  }

  private fun intToIp(value: Int): String =
    "${(value ushr 24) and 0xff}.${(value ushr 16) and 0xff}.${(value ushr 8) and 0xff}.${value and 0xff}"

  /** Subnet-guess gateway (first host) — only used as an Android station fallback. */
  private fun deriveGateway(ip: String, prefix: Int): String? {
    val ipInt = ipToInt(ip) ?: return null
    if (prefix <= 0 || prefix >= 32) return null
    val mask = -1 shl (32 - prefix)
    val network = ipInt and mask
    return intToIp(network + 1)
  }

  /**
   * Predict the usable client IPv4 range of a hotspot subnet (network+1 ..
   * broadcast-1). The host occupies one address within this range unless it is
   * the first host, in which case the range starts at the next address.
   */
  private fun predictClientRange(hostIp: String, prefix: Int): Map<String, String>? {
    val ipInt = ipToInt(hostIp) ?: return null
    if (prefix <= 0 || prefix >= 31) return null
    val mask = -1 shl (32 - prefix)
    val network = ipInt and mask
    val broadcast = network or mask.inv()

    var first = network + 1
    if (first == ipInt) first += 1 // skip the host itself
    val last = broadcast - 1
    // Compare as unsigned so high-bit subnets (e.g. 192.168/172.16) order correctly.
    if (Integer.compareUnsigned(last, first) < 0) return null

    return mapOf("first" to intToIp(first), "last" to intToIp(last))
  }

  // endregion
}
