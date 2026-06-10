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

  private fun emitChange() {
    try {
      sendEvent("onNetworkChange", buildInfo())
    } catch (_: Exception) {
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

  // SoftAP / hotspot host interfaces vary by vendor/chipset.
  private val apNameRegex = Regex("^(ap0|wlan1|swlan|softap|ap_br_).*")

  private fun classify(name: String): Role = when {
    name == "wlan0" -> Role.WIFI
    apNameRegex.matches(name) -> Role.HOTSPOT
    name.startsWith("rmnet") || name.startsWith("ccmni") -> Role.CELLULAR
    name.startsWith("eth") -> Role.ETHERNET
    else -> Role.OTHER
  }

  // endregion

  // region Detection

  private fun enumerateInterfaces(): List<Iface> {
    val result = mutableListOf<Iface>()

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
        result.add(Iface(nif.name, ip, prefixToNetmask(prefix), prefix, classify(nif.name)))
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
      val routeGateway = linkProperties.routes
        .firstOrNull { it.isDefaultRoute && it.gateway != null }
        ?.gateway
        ?.hostAddress
      if (routeGateway != null) return routeGateway
      if (Build.VERSION.SDK_INT >= 30) linkProperties.dhcpServerAddress?.hostAddress else null
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

  /** Predict the usable client IPv4 range of a hotspot subnet, excluding the host. */
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
