import ExpoModulesCore
import Network

public class LocalNetworkInfoModule: Module {
  private var monitor: NWPathMonitor?
  private let monitorQueue = DispatchQueue(label: "expo.modules.localnetworkinfo.monitor")

  public func definition() -> ModuleDefinition {
    Name("LocalNetworkInfo")

    Events("onNetworkChange")

    AsyncFunction("getLocalIpAsync") { () -> [String: Any?] in
      return LocalNetworkInfoModule.buildInfo()
    }

    AsyncFunction("getAllInterfacesAsync") { () -> [[String: Any?]] in
      return LocalNetworkInfoModule.enumerateInterfaces().map { $0.toDictionary() }
    }

    OnStartObserving {
      self.startMonitoring()
    }

    OnStopObserving {
      self.stopMonitoring()
    }

    OnDestroy {
      self.stopMonitoring()
    }
  }

  // MARK: - Network change monitoring

  private func startMonitoring() {
    guard monitor == nil else { return }
    let pathMonitor = NWPathMonitor()
    pathMonitor.pathUpdateHandler = { [weak self] _ in
      guard let self = self else { return }
      self.sendEvent("onNetworkChange", LocalNetworkInfoModule.buildInfo())
    }
    pathMonitor.start(queue: monitorQueue)
    monitor = pathMonitor
  }

  private func stopMonitoring() {
    monitor?.cancel()
    monitor = nil
  }

  // MARK: - Interface model

  private enum Role: String {
    case wifi
    case hotspot
    case cellular
    case ethernet
    case other
  }

  private struct Interface {
    let name: String
    let ipv4: String
    let netmask: String
    let role: Role

    /// Reliable Personal Hotspot host fingerprint: iOS always assigns the host
    /// 172.20.10.1 / 255.255.255.240 on a `bridge*` interface.
    var isHotspotHost: Bool {
      return name.hasPrefix("bridge")
        && ipv4 == "172.20.10.1"
        && netmask == "255.255.255.240"
    }

    func toDictionary() -> [String: Any?] {
      return [
        "name": name,
        "ip": ipv4,
        "netmask": netmask.isEmpty ? nil : netmask,
        "role": role.rawValue,
      ]
    }
  }

  // MARK: - Detection

  /// Classify an interface by its (de-facto, non-API) name. Heuristic, paired
  /// with the 172.20.10.1 fingerprint for reliable hotspot-host detection.
  private static func classify(_ name: String) -> Role {
    if name.hasPrefix("bridge") { return .hotspot }   // bridge100 = Personal Hotspot host
    if name == "en0" { return .wifi }                 // primary WiFi station
    if name.hasPrefix("pdp_ip") { return .cellular }  // pdp_ip0 = cellular
    if name.hasPrefix("en") { return .ethernet }      // en1/en2 = wired adapters
    return .other                                     // awdl0, llw0, utun*, ap1, lo0, ...
  }

  /// Enumerate up, non-loopback IPv4 interfaces using getifaddrs(3).
  private static func enumerateInterfaces() -> [Interface] {
    var result: [Interface] = []

    var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddrPtr) == 0, let first = ifaddrPtr else { return result }
    defer { freeifaddrs(ifaddrPtr) }

    for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
      let ifa = ptr.pointee

      guard let addr = ifa.ifa_addr else { continue }
      guard addr.pointee.sa_family == UInt8(AF_INET) else { continue }

      let flags = Int32(ifa.ifa_flags)
      guard (flags & IFF_UP) == IFF_UP, (flags & IFF_LOOPBACK) == 0 else { continue }

      let name = String(cString: ifa.ifa_name)

      // Use sa_len so the call is correct for AF_INET (16 bytes).
      var hostBuffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
      guard getnameinfo(addr, socklen_t(addr.pointee.sa_len),
                        &hostBuffer, socklen_t(hostBuffer.count),
                        nil, 0, NI_NUMERICHOST) == 0 else { continue }
      let ip = String(cString: hostBuffer)

      // Skip link-local AirDrop/Continuity addresses (awdl0/llw0 etc).
      if ip.hasPrefix("169.254.") { continue }

      var netmask = ""
      if let nm = ifa.ifa_netmask {
        var maskBuffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(nm, socklen_t(nm.pointee.sa_len),
                       &maskBuffer, socklen_t(maskBuffer.count),
                       nil, 0, NI_NUMERICHOST) == 0 {
          netmask = String(cString: maskBuffer)
        }
      }

      result.append(Interface(name: name, ipv4: ip, netmask: netmask, role: classify(name)))
    }

    return result
  }

  /// Build a `LocalIpInfo`-shaped dictionary, applying WiFi-over-hotspot precedence.
  private static func buildInfo() -> [String: Any?] {
    let interfaces = enumerateInterfaces()

    let station = interfaces.first { $0.role == .wifi }
    let host = interfaces.first { $0.isHotspotHost } ?? interfaces.first { $0.role == .hotspot }

    let isWifiConnected = station != nil
    let isHotspotHost = host != nil

    var ip: String?
    var role = "none"
    var interfaceName: String?
    var netmask: String?
    var gateway: String?
    var predicted: [String: String]?

    if let station = station {
      ip = station.ipv4
      role = "wifi"
      interfaceName = station.name
      netmask = station.netmask.isEmpty ? nil : station.netmask
      gateway = deriveGateway(ip: station.ipv4, netmask: station.netmask)
    } else if let host = host {
      ip = host.ipv4
      role = "hotspot"
      interfaceName = host.name
      netmask = host.netmask.isEmpty ? nil : host.netmask
      gateway = host.ipv4 // the hotspot host is its own gateway
      predicted = predictClientRange(hostIp: host.ipv4, netmask: host.netmask)
    }

    return [
      "ip": ip,
      "role": role,
      "isWifiConnected": isWifiConnected,
      "isHotspotHost": isHotspotHost,
      "interfaceName": interfaceName,
      "netmask": netmask,
      "gateway": gateway,
      "predictedClientRange": predicted,
      "platform": "ios",
      "timestamp": Date().timeIntervalSince1970 * 1000,
    ]
  }

  // MARK: - IPv4 math

  private static func ipToInt(_ ip: String) -> UInt32? {
    let parts = ip.split(separator: ".")
    guard parts.count == 4 else { return nil }
    var value: UInt32 = 0
    for part in parts {
      guard let octet = UInt32(part), octet <= 255 else { return nil }
      value = (value << 8) | octet
    }
    return value
  }

  private static func intToIp(_ value: UInt32) -> String {
    return "\((value >> 24) & 0xff).\((value >> 16) & 0xff).\((value >> 8) & 0xff).\(value & 0xff)"
  }

  /// Best-effort gateway guess for a station: the first host of the subnet.
  /// iOS exposes no public default-gateway API, so this is a heuristic.
  private static func deriveGateway(ip: String, netmask: String) -> String? {
    guard let ipInt = ipToInt(ip), let maskInt = ipToInt(netmask), maskInt != 0 else { return nil }
    let network = ipInt & maskInt
    return intToIp(network + 1)
  }

  /// Predict the usable client IPv4 range of a hotspot subnet, excluding the host.
  private static func predictClientRange(hostIp: String, netmask: String) -> [String: String]? {
    guard let ipInt = ipToInt(hostIp), let maskInt = ipToInt(netmask), maskInt != 0 else { return nil }
    let network = ipInt & maskInt
    let broadcast = network | ~maskInt
    guard broadcast > network + 1 else { return nil } // needs at least 2 usable hosts

    var first = network + 1
    if first == ipInt { first += 1 } // skip the host itself
    let last = broadcast - 1
    guard last >= first else { return nil }

    return ["first": intToIp(first), "last": intToIp(last)]
  }
}
